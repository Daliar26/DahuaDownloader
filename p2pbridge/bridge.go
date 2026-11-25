package p2pbridge

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
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
	stateMu   sync.Mutex
	current   *bridge
	lastError string
)

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
	if localPort < 1024 || localPort > 65535 || devicePort < 0 || devicePort > 65535 {
		return "Некорректный порт P2P"
	}

	client, err := dahua.ConnectWithConfig(dahua.Config{
		Serial: serial, Username: username, Password: password,
		Timeout: 20 * time.Second, MaxRealms: 4,
	})
	if err != nil {
		return remember(fmt.Sprintf("P2P: %v", err))
	}

	listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(localPort)))
	if err != nil {
		_ = client.Close()
		return remember(fmt.Sprintf("Локальный порт P2P недоступен: %v", err))
	}

	rtspPort := client.RTSPPort()
	if rtspPort == 0 {
		rtspPort = uint32(devicePort)
	}
	if rtspPort == 0 {
		rtspPort = 554
	}
	b := &bridge{
		client: client, listener: listener, devicePort: rtspPort,
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
		b.closeListener()
		remember("P2P-туннель закрыт удалённой стороной")
	})
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

func (b *bridge) close() {
	b.closeListener()
	b.mu.Lock()
	conns := make([]net.Conn, 0, len(b.conns))
	for c := range b.conns {
		conns = append(conns, c)
	}
	b.mu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
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
		remember(fmt.Sprintf("P2P: ожидание канала: %v", err))
		return
	}
	b.client.WaitSettle()
	remote, err := b.client.Dial(b.devicePort)
	b.client.DoneNegotiate()
	b.client.UnlockNegotiate()
	if err != nil {
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

	errCh := make(chan error, 2)
	go func() { _, e := io.Copy(remote, local); errCh <- e }()
	go func() { _, e := io.Copy(local, remote); errCh <- e }()
	<-errCh
	_ = local.Close()
	_ = remote.Close()
	<-errCh
}

