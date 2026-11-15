package ru.dahuatools.downloader

import com.company.NetSDK.CB_fDisConnect
import com.company.NetSDK.CB_fHaveReConnect
import com.company.NetSDK.CB_fTimeDownLoadPosCallBack
import com.company.NetSDK.EM_LOGIN_SPAC_CAP_TYPE
import com.company.NetSDK.EM_QUERY_RECORD_TYPE
import com.company.NetSDK.INetSDK
import com.company.NetSDK.NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY
import com.company.NetSDK.NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY
import com.company.NetSDK.NET_PARAM
import com.company.NetSDK.NET_RECORDFILE_INFO
import com.company.NetSDK.NET_TIME
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

class SdkException(message: String) : Exception(message)

class LoginInfo(
    val handle: Long,
    val channels: Int,
    val serial: String,
    val leftTries: Int,
    val deviceTime: String?
)

class SearchResult(val items: List<RecordItem>, val truncated: Boolean)

/**
 * Тонкая обёртка над официальным Dahua NetSDK (com.company.NetSDK.INetSDK).
 * Все вызовы блокирующие: вызывать только из фонового потока (Dispatchers.IO).
 * Внутри callback'ов SDK сам SDK не вызывается (рекомендация документации Dahua).
 */
object NetSdk {

    @Volatile
    private var initialized = false

    // Сильные ссылки, чтобы callback'и не собрал GC, пока их держит нативный код.
    private var disconnectCb: CB_fDisConnect? = null
    private var reconnectCb: CB_fHaveReConnect? = null

    @Volatile
    var onEvent: ((String) -> Unit)? = null

    fun lastError(): String = String.format("0x%08X", INetSDK.GetLastError())

    @Synchronized
    fun ensureInit(log: (String) -> Unit) {
        if (initialized) return

        // Порядок важен: libssl зависит от libcrypto.so.1.0.0 (SONAME у libcrypto.so именно такой).
        val libs = listOf(
            "crypto" to false,
            "ssl" to false,
            "netsdk" to true,
            "configsdk" to true,
            "avnetsdk" to false,
            "jninetsdk" to true
        )
        for ((name, required) in libs) {
            try {
                System.loadLibrary(name)
                log("lib$name.so загружена")
            } catch (e: UnsatisfiedLinkError) {
                log("lib$name.so: ${e.message}")
                if (required) throw SdkException("Не удалось загрузить lib$name.so: ${e.message}")
            }
        }

        val dc = CB_fDisConnect { _, ip, port ->
            onEvent?.invoke("Связь с $ip:$port потеряна, SDK пробует переподключиться")
        }
        val rc = CB_fHaveReConnect { _, ip, port ->
            onEvent?.invoke("Связь с $ip:$port восстановлена")
        }
        disconnectCb = dc
        reconnectCb = rc

        val ok = try {
            INetSDK.Init(dc)
        } catch (e: Throwable) {
            throw SdkException("INetSDK.Init: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!ok) throw SdkException("INetSDK.Init вернул false")

        INetSDK.SetAutoReconnect(rc)
        INetSDK.SetConnectTime(5000, 1)
        val param = NET_PARAM()
        param.nConnectTime = 3000
        param.nWaittime = 5000
        INetSDK.SetNetworkParam(param)

        initialized = true
        log("SDK инициализирован, версия ${INetSDK.GetSDKVersion()}")
    }

    fun login(ip: String, port: Int, user: String, password: String): LoginInfo {
        val inp = NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY()
        putZ(ip, inp.szIP, "IP")
        putZ(user, inp.szUserName, "Логин")
        putZ(password, inp.szPassword, "Пароль")
        inp.nPort = port
        inp.emSpecCap = EM_LOGIN_SPAC_CAP_TYPE.EM_LOGIN_SPEC_CAP_TCP
        inp.pCapParam = null

        val out = NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY()
        val handle = INetSDK.LoginWithHighLevelSecurity(inp, out)
        if (handle == 0L) {
            val di = out.stuDeviceInfo
            val left = if (di != null) (di.byLeftLogTimes.toInt() and 0xFF) else -1
            val extra = if (left in 0..9) ", осталось попыток до блокировки: $left" else ""
            throw SdkException(
                "Вход не удался: ${loginErrorText(out.nError)} " +
                    "(код входа ${out.nError}, SDK ${lastError()})$extra"
            )
        }

        val di = out.stuDeviceInfo
        val serial = String(di.sSerialNumber, Charsets.UTF_8).substringBefore('\u0000').trim()
        val time = NET_TIME()
        val devTime = if (INetSDK.QueryDeviceTime(handle, time, 3000)) time.toString() else null
        return LoginInfo(
            handle = handle,
            channels = di.nChanNum,
            serial = serial,
            leftTries = di.byLeftLogTimes.toInt() and 0xFF,
            deviceTime = devTime
        )
    }

    fun logout(handle: Long) {
        if (handle != 0L) INetSDK.Logout(handle)
    }

    /** Поиск файлов записей. channel0 — номер канала с нуля. */
    fun findRecordings(
        login: Long,
        channel0: Int,
        from: NET_TIME,
        to: NET_TIME,
        limit: Int
    ): SearchResult {
        val h = INetSDK.FindFile(
            login, channel0, EM_QUERY_RECORD_TYPE.EM_RECORD_TYPE_ALL, null, from, to, false, 10000
        )
        if (h == 0L) throw SdkException("FindFile: ошибка SDK ${lastError()}")
        val items = ArrayList<RecordItem>()
        var truncated = false
        try {
            while (true) {
                if (items.size >= limit) {
                    truncated = true
                    break
                }
                val info = NET_RECORDFILE_INFO()
                val r = INetSDK.FindNextFile(h, info)
                if (r == 1) {
                    items.add(
                        RecordItem(
                            name = String(info.filename).substringBefore('\u0000'),
                            start = fmt(info.starttime),
                            end = fmt(info.endtime),
                            sizeKb = info.size.toLong() and 0xFFFFFFFFL
                        )
                    )
                } else if (r == 0) {
                    break
                } else {
                    throw SdkException("FindNextFile: ошибка SDK ${lastError()}")
                }
            }
        } finally {
            INetSDK.FindClose(h)
        }
        return SearchResult(items, truncated)
    }

    /**
     * Скачивание записи за период в один файл (формат DAV).
     * Прогресс в процентах отдаётся в onProgress только при изменении.
     * Отмена корутины останавливает загрузку в SDK.
     */
    suspend fun downloadByTime(
        login: Long,
        channel0: Int,
        from: NET_TIME,
        to: NET_TIME,
        target: File,
        onProgress: (Int) -> Unit
    ) {
        val events = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
        val cb = CB_fTimeDownLoadPosCallBack { _, total, done, _, _ ->
            events.trySend(total to done)
        }
        val handle = INetSDK.DownloadByTimeEx(
            login, channel0, EM_QUERY_RECORD_TYPE.EM_RECORD_TYPE_ALL,
            from, to, target.absolutePath, cb, null, null
        )
        if (handle == 0L) throw SdkException("DownloadByTimeEx: ошибка SDK ${lastError()}")

        try {
            var reached = false
            var lastPct = -1
            while (true) {
                val timeout = if (reached) 15_000L else 120_000L
                val ev = withTimeoutOrNull(timeout) { events.receive() }
                if (ev == null) {
                    if (reached) break
                    throw SdkException("Регистратор не присылает данные больше 2 минут")
                }
                val (total, done) = ev
                when {
                    done == -1 -> break                                   // конец загрузки
                    done == -2 -> throw SdkException("Не удалось записать файл на телефон")
                    total > 0 -> {
                        val pct = ((done.toLong() * 100) / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                        if (done >= total) reached = true
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { INetSDK.StopDownload(handle) }
            events.close()
            // cb остаётся достижимым до этой точки
            cb.hashCode()
        }

        if (!target.exists() || target.length() == 0L) {
            throw SdkException("Файл не создан или пустой (в этом периоде может не быть записи)")
        }
    }

    fun netTime(d: LocalDate, t: LocalTime): NET_TIME {
        val r = NET_TIME()
        r.dwYear = d.year.toLong()
        r.dwMonth = d.monthValue.toLong()
        r.dwDay = d.dayOfMonth.toLong()
        r.dwHour = t.hour.toLong()
        r.dwMinute = t.minute.toLong()
        r.dwSecond = t.second.toLong()
        return r
    }

    private fun fmt(t: NET_TIME?): String {
        if (t == null) return "?"
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            t.dwYear, t.dwMonth, t.dwDay, t.dwHour, t.dwMinute, t.dwSecond
        )
    }

    private fun putZ(src: String, dst: ByteArray, field: String) {
        val b = src.toByteArray(Charsets.UTF_8)
        if (b.size >= dst.size) {
            throw SdkException("$field слишком длинное (максимум ${dst.size - 1} байт)")
        }
        java.util.Arrays.fill(dst, 0.toByte())
        System.arraycopy(b, 0, dst, 0, b.size)
    }

    // Коды из таблицы ошибок входа Dahua; код всегда показывается рядом как есть.
    private fun loginErrorText(code: Int): String = when (code) {
        1 -> "неверный пароль"
        2 -> "пользователь не существует"
        3 -> "таймаут ответа регистратора"
        4 -> "учётная запись уже выполнила вход"
        5 -> "учётная запись заблокирована"
        6 -> "учётная запись в чёрном списке"
        7 -> "устройство занято"
        8 -> "ошибка дополнительного соединения"
        9 -> "нет основного соединения (проверьте IP, порт 37777 и сеть)"
        10 -> "превышено число подключений"
        11 -> "не найдена зависимая библиотека SDK"
        else -> "неизвестная ошибка"
    }
}
