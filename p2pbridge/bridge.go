package p2pbridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	dahua "github.com/blackjid/dahua-p2p"
)

type bridge struct {
	client     *dahua.Client
	listener   net.Listener
	devicePort uint32
	done       chan struct{}
	closeOnce  sync.Once
	mu         sync.Mutex
	conns      map[net.Conn]struct{}
	wg         sync.WaitGroup
}

var (
	stateMu       sync.Mutex
	current       *bridge
	lastError     string
	diagMu        sync.Mutex
	diagLines     []string
	dnsResolverMu sync.Mutex
)

const maxDiagnosticLines = 300

func recordDiagnostic(level, serial, username, password, format string, values ...any) {
	message := fmt.Sprintf(format, values...)
	for _, secret := range []string{serial, username, password} {
		if secret != "" {
			message = strings.ReplaceAll(message, secret, "[скрыто]")
		}
	}
	line := time.Now().Format("2006-01-02 15:04:05.000") + " [" + level + "] " + message
	diagMu.Lock()
	diagLines = append(diagLines, line)
	if len(diagLines) > maxDiagnosticLines {
		diagLines = append([]string(nil), diagLines[len(diagLines)-maxDiagnosticLines:]...)
	}
	diagMu.Unlock()
}

func Diagnostics() string {
	diagMu.Lock()
	defer diagMu.Unlock()
	return strings.Join(diagLines, "\n")
}

// connectWithDNSFallback first honors Android's configured DNS. If that resolver
// cannot resolve Dahua's documented main P2P host, it retries through public
// DNS resolvers. The override is scoped to the handshake and restored before
// returning; no server IP is hard-coded because Dahua uses dynamic addresses.
func connectWithDNSFallback(cfg dahua.Config, trace func(string, ...any)) (*dahua.Client, error) {
	const mainHost = "www.easy4ipcloud.com"
	dnsResolverMu.Lock()
	defer dnsResolverMu.Unlock()

	systemResolver := net.DefaultResolver
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	ips, systemErr := systemResolver.LookupIPAddr(ctx, mainHost)
	cancel()
	if systemErr == nil && len(ips) > 0 {
		trace("DNS system resolver: %s -> %v", mainHost, ips)
		return dahua.ConnectWithConfig(cfg)
	}
	trace("DNS system resolver failed for %s: %v; trying public DNS fallback", mainHost, systemErr)

	servers := []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"}
	var nextServer uint32
	fallbackResolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			index := atomic.AddUint32(&nextServer, 1) - 1
			server := servers[int(index)%len(servers)]
			dialer := net.Dialer{Timeout: 3 * time.Second}
			conn, err := dialer.DialContext(ctx, network, server)
			if err != nil {
				trace("DNS fallback socket to %s failed: %v", server, err)
				return nil, err
			}
			trace("DNS fallback query routed to %s", server)
			return conn, nil
		},
	}
	net.DefaultResolver = fallbackResolver
	defer func() { net.DefaultResolver = systemResolver }()

	ctx, cancel = context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	ips, err := fallbackResolver.LookupIPAddr(ctx, mainHost)
	if err != nil || len(ips) == 0 {
		if err == nil {
			err = fmt.Errorf("DNS returned no addresses")
		}
		return nil, fmt.Errorf("DNS не разрешает %s: системный DNS: %v; резервный DNS: %w", mainHost, systemErr, err)
	}
	trace("DNS fallback resolved %s -> %v", mainHost, ips)
	return dahua.ConnectWithConfig(cfg)
}

func Health() string {
	stateMu.Lock()
	b := current
	errText := lastError
	stateMu.Unlock()
	if b == nil {
		return "running=false; lastError=" + errText
	}
	closed := b.client.IsClosed()
	responsive := b.client.IsResponsive(15 * time.Second)
	retired := b.client.IsRetired()
	realms := b.client.ActiveRealms()
	bindFailures := b.client.BindFailures()
	return fmt.Sprintf("running=%t; closed=%t; responsive15s=%t; retired=%t; realms=%d; bindFailures=%d; lastError=%s",
		!closed, closed, responsive, retired, realms, bindFailures, errText)
}

// Start opens a Dahua cloud P2P tunnel and exposes the device TCP service on
// loopback. The password is used only for the live handshake and is never
// written to disk by this package.
func Start(serial, username, password string, localPort, devicePort int) string {
	stateMu.Lock()
	if current != nil {
		stateMu.Unlock()
		return "P2P-туннель уже запущен"
	}
	stateMu.Unlock()

	if serial == "" || username == "" || password == "" {
		return "Для P2P нужны серийный номер, логин и пароль регистратора"
	}
	if localPort < 1024 || localPort > 65535 || devicePort < 1 || devicePort > 65535 {
		return "Некорректный порт P2P"
	}

	trace := func(format string, values ...any) {
		recordDiagnostic("TRACE", serial, username, password, format, values...)
	}
	reportError := func(format string, values ...any) {
		recordDiagnostic("ERROR", serial, username, password, format, values...)
		remember(fmt.Sprintf(format, values...))
	}
	recordDiagnostic("INFO", serial, username, password, "Запуск P2P handshake; timeout=20s; maxRealms=4")
	cfg := dahua.Config{
		Serial: serial, Username: username, Password: password,
		Timeout: 20 * time.Second, MaxRealms: 4,
		Trace: trace, Error: reportError,
	}
	var client *dahua.Client
	var err error
	for attempt := 1; attempt <= 2; attempt++ {
		recordDiagnostic("INFO", serial, username, password, "P2P handshake: попытка %d/2", attempt)
		client, err = connectWithDNSFallback(cfg, trace)
		if err == nil {
			break
		}
		errText := strings.ToLower(err.Error())
		if strings.Contains(errText, "operation not permitted") || strings.Contains(errText, "permission denied") {
			recordDiagnostic("ERROR", serial, username, password, "UDP запрещён системой или сетевым фильтром; повтор handshake остановлен: %v", err)
			return remember("P2P: Android/сеть запрещает исходящий UDP. Проверьте VPN, сетевой фильтр или другую сеть.")
		}
		var netErr net.Error
		if attempt == 1 && errors.As(err, &netErr) && netErr.Timeout() {
			recordDiagnostic("WARN", serial, username, password, "UDP handshake timeout; повтор через 2 секунды: %v", err)
			time.Sleep(2 * time.Second)
			continue
		}
		break
	}
	if err != nil {
		recordDiagnostic("ERROR", serial, username, password, "Handshake завершился ошибкой: %v", err)
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			return remember(fmt.Sprintf("P2P: тайм-аут UDP-ответа облачного сервера Dahua: %v. Возможна блокировка UDP текущей сетью.", err))
		}
		return remember(fmt.Sprintf("P2P: %v", err))
	}

	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(localPort)))
	if err != nil {
		_ = client.Close()
		return remember(fmt.Sprintf("Локальный порт P2P недоступен: %v", err))
	}

	b := &bridge{
		client: client, listener: listener, devicePort: uint32(devicePort),
		done: make(chan struct{}), conns: make(map[net.Conn]struct{}),
	}
	stateMu.Lock()
	if current != nil {
		stateMu.Unlock()
		_ = listener.Close()
		_ = client.Close()
		return "P2P-туннель уже запущен"
	}
	current = b
	lastError = ""
	stateMu.Unlock()

	client.SetOnClose(func() {
		recordDiagnostic("ERROR", serial, username, password, "P2P tunnel OnClose: туннель закрыт; останавливаю listener и активные TCP relay")
		b.closeListener()
		b.closeConnections()
		remember("P2P-туннель закрыт удалённой стороной; активные TCP-соединения остановлены")
	})
	recordDiagnostic("INFO", serial, username, password, "P2P handshake успешен; локальный TCP listener=%d -> devicePort=%d", localPort, devicePort)
	go b.serve()
	return ""
}

func remember(message string) string {
	stateMu.Lock()
	lastError = message
	stateMu.Unlock()
	return message
}

func LastError() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	return lastError
}

func IsRunning() bool {
	stateMu.Lock()
	b := current
	stateMu.Unlock()
	return b != nil && !b.client.IsClosed()
}

// Stop closes the local listener, active TCP relays and the P2P session.
func Stop() {
	stateMu.Lock()
	b := current
	current = nil
	stateMu.Unlock()
	if b != nil {
		b.close()
	}
}

func (b *bridge) closeListener() {
	b.closeOnce.Do(func() {
		close(b.done)
		_ = b.listener.Close()
	})
}

func (b *bridge) closeConnections() {
	b.mu.Lock()
	conns := make([]net.Conn, 0, len(b.conns))
	for c := range b.conns {
		conns = append(conns, c)
	}
	b.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}

func (b *bridge) close() {
	b.closeListener()
	b.closeConnections()
	_ = b.client.Close()
	b.wg.Wait()
}

func (b *bridge) serve() {
	for {
		local, err := b.listener.Accept()
		if err != nil {
			select {
			case <-b.done:
				return
			default:
				remember(fmt.Sprintf("P2P listener: %v", err))
				return
			}
		}
		b.wg.Add(1)
		go func() {
			defer b.wg.Done()
			b.handle(local)
		}()
	}
}

func (b *bridge) handle(local net.Conn) {
	recordDiagnostic("INFO", "", "", "", "Принято локальное TCP-подключение от NetSDK")
	if tcp, ok := local.(*net.TCPConn); ok {
		_ = tcp.SetKeepAlive(true)
		_ = tcp.SetKeepAlivePeriod(15 * time.Second)
		_ = tcp.SetNoDelay(true)
	}
	defer local.Close()
	b.mu.Lock()
	b.conns[local] = struct{}{}
	b.mu.Unlock()
	defer func() {
		b.mu.Lock()
		delete(b.conns, local)
		b.mu.Unlock()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := b.client.LockNegotiate(ctx); err != nil {
		recordDiagnostic("ERROR", "", "", "", "LockNegotiate: %v", err)
		remember(fmt.Sprintf("P2P: ожидание канала: %v", err))
		return
	}
	b.client.WaitSettle()
	remote, err := b.client.Dial(b.devicePort)
	b.client.DoneNegotiate()
	b.client.UnlockNegotiate()
	if err != nil {
		recordDiagnostic("ERROR", "", "", "", "Dial devicePort=%d failed: %v; closed=%t; responsive15s=%t; bindFailures=%d", b.devicePort, err, b.client.IsClosed(), b.client.IsResponsive(15*time.Second), b.client.BindFailures())
		remember(fmt.Sprintf("P2P: не удалось открыть TCP-порт регистратора %d: %v", b.devicePort, err))
		return
	}
	b.mu.Lock()
	b.conns[remote] = struct{}{}
	b.mu.Unlock()
	defer func() {
		_ = remote.Close()
		b.mu.Lock()
		delete(b.conns, remote)
		b.mu.Unlock()
	}()

	recordDiagnostic("INFO", "", "", "", "Удалённый TCP realm открыт; activeRealms=%d", b.client.ActiveRealms())
	type relayResult struct {
		direction string
		bytes int64
		err error
	}
	errCh := make(chan relayResult, 2)
	started := time.Now()
	go func() {
		n, e := io.Copy(remote, local)
		errCh <- relayResult{"local->device", n, e}
	}()
	go func() {
		n, e := io.Copy(local, remote)
		errCh <- relayResult{"device->local", n, e}
	}()
	first := <-errCh
	recordDiagnostic("WARN", "", "", "", "TCP relay: направление %s завершилось; bytes=%d; elapsed=%s; err=%v",
		first.direction, first.bytes, time.Since(started).Round(time.Millisecond), first.err)

	var second relayResult
	if first.direction == "local->device" && first.err == nil {
		// NetSDK может закрыть свою сторону записи после отправки запроса,
		// продолжая ждать ответ регистратора. Не обрываем обратный поток по EOF.
		recordDiagnostic("INFO", "", "", "", "TCP relay: локальная сторона завершила отправку; оставляю device->local открытым для ответа")
		select {
		case second = <-errCh:
		case <-time.After(10 * time.Minute):
			recordDiagnostic("ERROR", "", "", "", "TCP relay: тайм-аут ожидания ответа регистратора после EOF локальной стороны")
			_ = local.Close()
			_ = remote.Close()
			second = <-errCh
		}
	} else {
		// Ошибка или завершение ответа регистратора — закрываем обе стороны.
		_ = local.Close()
		_ = remote.Close()
		second = <-errCh
	}
	recordDiagnostic("INFO", "", "", "", "TCP relay закрыт: %s=%d bytes; %s=%d bytes; elapsed=%s; activeRealms=%d",
		first.direction, first.bytes, second.direction, second.bytes,
		time.Since(started).Round(time.Millisecond), b.client.ActiveRealms())
}

var _ = errors.Is
