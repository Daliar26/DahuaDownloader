package ru.dahuatools.downloader

import com.company.NetSDK.CB_fDisConnect
import com.company.NetSDK.CB_fHaveReConnect
import com.company.NetSDK.CB_fSearchDevicesCB
import com.company.NetSDK.EM_LOGIN_SPAC_CAP_TYPE
import com.company.NetSDK.EM_QUERY_RECORD_TYPE
import com.company.NetSDK.EM_USEDEV_MODE
import com.company.NetSDK.INetSDK
import com.company.NetSDK.NET_IN_DOWNLOAD_PIECE_FILE
import com.company.NetSDK.NET_IN_LOGIN_WITH_HIGHLEVEL_SECURITY
import com.company.NetSDK.NET_OUT_DOWNLOAD_PIECE_FILE
import com.company.NetSDK.NET_OUT_LOGIN_WITH_HIGHLEVEL_SECURITY
import com.company.NetSDK.NET_PARAM
import com.company.NetSDK.NET_RECORDFILE_INFO
import com.company.NetSDK.NET_TIME
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /** Размер одного запроса докачки. 512 КБ — компромисс между числом обращений и памятью. */
    const val PIECE_CHUNK_BYTES = 512 * 1024

    @Volatile
    private var initialized = false

    // Сильные ссылки, чтобы callback'и не собрал GC, пока их держит нативный код.
    private var disconnectCb: CB_fDisConnect? = null
    private var reconnectCb: CB_fHaveReConnect? = null

    @Volatile
    var onEvent: ((String) -> Unit)? = null

    /** Вызывается, когда SDK сообщает о восстановлении связи после обрыва. */
    @Volatile
    var onReconnect: (() -> Unit)? = null

    fun lastError(): String = String.format("0x%08X", INetSDK.GetLastError())

    private fun trace(stage: String, message: String, failed: Boolean = false) {
        DiagnosticLog.write(if (failed) "ERROR" else "INFO", "NETSDK/$stage", message)
        onEvent?.invoke("[$stage] $message")
    }

    private fun methodInfo(m: java.lang.reflect.Method) =
        "${m.name}(${m.parameterTypes.joinToString { it.name }}) static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}"

    @Synchronized
    fun ensureInit(log: (String) -> Unit) {
        if (initialized) { trace("INIT", "SDK уже инициализирован"); return }
        trace("INIT", "Начало инициализации NetSDK")

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
                trace("INIT", "System.loadLibrary($name): OK")
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
            onReconnect?.invoke()
        }
        disconnectCb = dc
        reconnectCb = rc

        val ok = try {
            INetSDK.Init(dc)
        } catch (e: Throwable) {
            throw SdkException("INetSDK.Init: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!ok) { val code = lastError(); trace("INIT", "Init=false; SDK=$code", true); throw SdkException("INetSDK.Init вернул false (SDK $code)") }

        INetSDK.SetAutoReconnect(rc)
        INetSDK.SetConnectTime(5000, 1)
        val param = NET_PARAM()
        param.nConnectTime = 3000
        param.nWaittime = 5000
        INetSDK.SetNetworkParam(param)

        initialized = true
        log("SDK инициализирован, версия ${INetSDK.GetSDKVersion()}")
        trace("INIT", "Init успешно; версия=${INetSDK.GetSDKVersion()}")
        INetSDK::class.java.methods.filter { it.name == "DownloadByTimeEx" || it.name == "SetDeviceMode" }.forEach { trace("API", methodInfo(it)) }
    }

    fun login(ip: String, port: Int, user: String, password: String): LoginInfo {
        trace("LOGIN", "LoginWithHighLevelSecurity: $ip:$port; user=$user; пароль скрыт")
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
            val code = lastError()
            trace("LOGIN", "Login handle=0; nError=${out.nError}; SDK=$code", true)
            val di = out.stuDeviceInfo
            val left = if (di != null) (di.byLeftLogTimes.toInt() and 0xFF) else -1
            val extra = if (left in 0..9) ", осталось попыток до блокировки: $left" else ""
            throw SdkException(
                "Вход не удался: ${loginErrorText(out.nError)} " +
                    "(код входа ${out.nError}, SDK ${lastError()})$extra"
            )
        }

        trace("LOGIN", "Login успешно; handle=$handle")
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
        trace("SEARCH", "FindFile: login=$login; channel0=$channel0; type=ALL; from=$from; to=$to; limit=$limit")
        // Dahua requires the recording stream type to be selected before archive
        // queries on some NVR firmware. Use the same mode as DownloadByTimeEx.
        if (!INetSDK.SetDeviceMode(login, EM_USEDEV_MODE.SDK_RECORD_STREAM_TYPE, 0)) {
            val error = lastError()
            throw SdkException("SetDeviceMode перед FindFile завершился ошибкой SDK $error")
        }

        val h = INetSDK.FindFile(
            login, channel0, EM_QUERY_RECORD_TYPE.EM_RECORD_TYPE_ALL, null, from, to, false, 10000
        )
        if (h == 0L) {
            val error = lastError() // capture immediately; later SDK calls overwrite it
            trace("SEARCH", "FindFile вернул handle=0; SDK=$error", true)
            throw SdkException("FindFile вернул пустой handle (SDK $error)")
        }
        trace("SEARCH", "FindFile handle=$h")
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
                            camera = channel0 + 1,
                            start = fmt(info.starttime),
                            end = fmt(info.endtime),
                            sizeKb = info.size.toLong() and 0xFFFFFFFFL,
                            name = String(info.filename).substringBefore('\u0000')
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
     * Докачка записи по имени файла на регистраторе, с продолжением с байта,
     * на котором остановились (DownloadPieceFile — обычный синхронный запрос
     * "дай кусок файла со смещения X", без отдельного потокового соединения).
     *
     * Если target уже существует и частично заполнен (от прошлой попытки),
     * скачивание продолжается с его текущего размера — именно это даёт
     * настоящую докачку после обрыва связи, а не повтор с начала.
     *
     * total в onProgress равен -1, пока размер файла ещё не известен
     * (станет известен после первого успешного запроса).
     */
    suspend fun downloadFilePiece(
        login: Long,
        deviceFileName: String,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        var offset = if (target.exists()) target.length() else 0L
        var total = -1L

        RandomAccessFile(target, "rw").use { raf ->
            raf.seek(offset)
            while (true) {
                currentCoroutineContext().ensureActive()

                if (offset + PIECE_CHUNK_BYTES > Int.MAX_VALUE.toLong()) {
                    throw SdkException(
                        "Файл больше 2 ГБ — докачка по частям для таких файлов пока не поддержана " +
                            "(скачано ${offset / 1024 / 1024} МБ)"
                    )
                }

                val inp = NET_IN_DOWNLOAD_PIECE_FILE()
                putZ(deviceFileName, inp.szFileName, "Имя файла")
                inp.nOffSet = offset.toInt()
                inp.nNeedLength = PIECE_CHUNK_BYTES

                val out = NET_OUT_DOWNLOAD_PIECE_FILE(PIECE_CHUNK_BYTES)
                val ok = INetSDK.DownloadPieceFile(login, inp, out, 15000)
                if (!ok) {
                    throw SdkException(
                        "Обрыв при скачивании на ${offset / 1024 / 1024} МБ " +
                            "(SDK ${lastError()}). Можно продолжить позже с этого места."
                    )
                }

                if (total < 0) {
                    total = out.nFileLength.toLong() and 0xFFFFFFFFL
                }

                val got = out.nBufferLen
                if (got <= 0) break
                raf.write(out.szBuffer, 0, got)
                offset += got
                onProgress(offset, total)

                if (total in 1..offset) break
            }
        }

        if (!target.exists() || target.length() == 0L) {
            throw SdkException("Файл не создан или пустой (в этом периоде может не быть записи)")
        }
    }

    /**
     * Скачивает весь выбранный интервал одним файлом через Dahua DownloadByTimeEx.
     * SDK выполняет передачу асинхронно; callback удерживается до StopDownload.
     */
    suspend fun downloadByTime(
        login: Long,
        channel0: Int,
        from: LocalDateTime,
        to: LocalDateTime,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        trace("BYTIME", "Старт: login=$login; channel0=$channel0; from=$from; to=$to; target=${target.absolutePath}")
        target.parentFile?.mkdirs()
        target.delete() // DownloadByTimeEx не поддерживает продолжение по байтовому смещению.
        val api = INetSDK::class.java
        // Android NetSDK Java demo uses the 9-argument DownloadByTimeEx overload.
        val overloads = api.methods.filter { it.name == "DownloadByTimeEx" }
        overloads.forEach { trace("API", methodInfo(it)) }
        val download = api.methods
            .filter { it.name == "DownloadByTimeEx" }
            .sortedByDescending { it.parameterTypes.size }
            .firstOrNull { it.parameterTypes.size == 11 || it.parameterTypes.size == 9 }
            ?: throw SdkException(
                "INetSDK не содержит DownloadByTimeEx(11/9 параметров). Найдены: " +
                    api.methods.filter { it.name.contains("DownloadByTime", ignoreCase = true) }
                        .joinToString { m -> methodInfo(m) }
                        .ifBlank { "нет методов DownloadByTime" }
            )

        if (!INetSDK.SetDeviceMode(login, EM_USEDEV_MODE.SDK_RECORD_STREAM_TYPE, 0)) {
            val code = lastError()
            trace("BYTIME", "SetDeviceMode SDK_RECORD_STREAM_TYPE=false; SDK=$code", true)
            throw SdkException("Не удалось установить основной поток записи (SDK $code)")
        }
        trace("BYTIME", "SetDeviceMode SDK_RECORD_STREAM_TYPE=0 успешно; метод=${methodInfo(download)}")

        val callbackType = download.parameterTypes[6]
        if (!callbackType.isInterface) {
            throw SdkException("Неизвестный тип callback DownloadByTimeEx: ${callbackType.name}")
        }
        val finished = java.util.concurrent.CountDownLatch(1)
        val totalRef = java.util.concurrent.atomic.AtomicLong(-1L)
        val doneRef = java.util.concurrent.atomic.AtomicLong(0L)
        val callbackError = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = java.lang.reflect.Proxy.newProxyInstance(
            callbackType.classLoader, arrayOf(callbackType)
        ) { proxy, called, args ->
            when (called.name) {
                "toString" -> "DahuaTimeDownloadCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> {
                    val values = args ?: emptyArray()
                    trace("CALLBACK", "${called.name}; argc=${values.size}; args=${values.joinToString { it?.toString()?.take(80) ?: "null" }}")
                    // Android Java callback: handle,total,downloaded,index,recordInfo.
                    if (values.size >= 3) {
                        val totalRaw = (values[1] as? Number)?.toLong() ?: -1L
                        val doneRaw = (values[2] as? Number)?.toLong() ?: 0L
                        if (doneRaw == -2L) {
                            callbackError.set(true) // Dahua: failed / permission denied.
                            finished.countDown()
                        } else if (doneRaw == -1L) {
                            finished.countDown() // Dahua: completed sentinel.
                        } else {
                            val total = totalRaw and 0xFFFFFFFFL
                            val done = doneRaw and 0xFFFFFFFFL
                            if (total > 0) totalRef.set(total)
                            doneRef.set(done)
                            onProgress(done, totalRef.get())
                            if (total > 0 && done >= total) finished.countDown()
                        }
                    }
                    null
                }
            }
        }

        val args = arrayOfNulls<Any>(download.parameterTypes.size)
        args[0] = login
        args[1] = channel0
        args[2] = EM_QUERY_RECORD_TYPE.EM_RECORD_TYPE_ALL
        args[3] = netTime(from)
        args[4] = netTime(to)
        args[5] = target.absolutePath
        args[6] = callback
        for (index in 7 until args.size) {
            val type = download.parameterTypes[index]
            args[index] = when {
                !type.isPrimitive -> null
                type == java.lang.Long.TYPE -> 0L
                type == java.lang.Integer.TYPE -> 0
                type == java.lang.Boolean.TYPE -> false
                type == java.lang.Float.TYPE -> 0f
                type == java.lang.Double.TYPE -> 0.0
                type == java.lang.Short.TYPE -> 0.toShort()
                type == java.lang.Byte.TYPE -> 0.toByte()
                type == java.lang.Character.TYPE -> 0.toChar()
                else -> null
            }
        }
        trace("BYTIME", "Аргументы сформированы по типам параметров; callbackIndex=6")

        val handle = try {
            trace("BYTIME", "Вызов DownloadByTimeEx с 9 аргументами; callbackIndex=6")
            (download.invoke(null, *args) as? Number)?.toLong() ?: 0L
        } catch (e: Throwable) {
            val code = lastError()
            val cause = e.cause ?: e
            trace("BYTIME", "Исключение: ${cause.javaClass.name}: ${cause.message}; SDK=$code", true)
            throw SdkException("DownloadByTimeEx: ${cause.message}; SDK $code")
        }
        if (handle == 0L) {
            val code = lastError()
            trace("BYTIME", "DownloadByTimeEx вернул handle=0; SDK=$code", true)
            throw SdkException("DownloadByTimeEx не запустился (SDK $code)")
        }

        trace("BYTIME", "DownloadByTimeEx запущен; handle=$handle")
        val startedAt = System.currentTimeMillis()
        var lastBytes = -1L
        var lastChangeAt = startedAt
        try {
            while (!finished.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                val done = doneRef.get()
                if (done != lastBytes) {
                    lastBytes = done
                    lastChangeAt = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - lastChangeAt > 120_000L) {
                    throw SdkException("Нет данных от регистратора более 2 минут (SDK ${lastError()})")
                }
                if (System.currentTimeMillis() - startedAt > 4 * 60 * 60 * 1000L) {
                    throw SdkException("Превышено время загрузки интервала")
                }
            }
            if (callbackError.get()) {
                throw SdkException("Регистратор отклонил выгрузку интервала (SDK ${lastError()})")
            }
            if (!target.exists() || target.length() == 0L) {
                throw SdkException("Регистратор завершил выгрузку, но файл пустой (SDK ${lastError()})")
            }
            trace("BYTIME", "Успешно завершено; file=${target.absolutePath}; bytes=${target.length()}")
            onProgress(target.length(), target.length())
        } finally {
            try {
                val stop = api.methods.firstOrNull {
                    it.name == "StopDownload" && it.parameterTypes.size == 1
                }
                stop?.invoke(null, handle)
                trace("BYTIME", "StopDownload(handle=$handle)")
            } catch (_: Throwable) {
            }
        }
    }

    fun netTime(dt: LocalDateTime): NET_TIME {
        val r = NET_TIME()
        r.dwYear = dt.year.toLong()
        r.dwMonth = dt.monthValue.toLong()
        r.dwDay = dt.dayOfMonth.toLong()
        r.dwHour = dt.hour.toLong()
        r.dwMinute = dt.minute.toLong()
        r.dwSecond = dt.second.toLong()
        return r
    }

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parseDateTime(text: String): LocalDateTime = LocalDateTime.parse(text.trim(), timeFmt)

    /**
     * Поиск регистраторов в локальной сети средствами SDK (широковещательный запрос Dahua).
     * Вызывать после ensureInit и с включённым MulticastLock Wi-Fi.
     */
    fun discover(waitMs: Long): List<DiscoveredDevice> {
        val found = java.util.concurrent.CopyOnWriteArrayList<DiscoveredDevice>()
        val cb = CB_fSearchDevicesCB { d ->
            if (d != null) {
                found.add(
                    DiscoveredDevice(
                        ip = cstr(d.szIP),
                        port = d.nPort,
                        serial = cstr(d.szSerialNo),
                        type = cstr(d.szDeviceType),
                        name = cstr(d.szDevName)
                    )
                )
            }
        }
        val h = INetSDK.StartSearchDevices(cb)
        if (h == 0L) throw SdkException("StartSearchDevices: ошибка SDK ${lastError()}")
        try {
            Thread.sleep(waitMs)
        } finally {
            INetSDK.StopSearchDevices(h)
            cb.hashCode()
        }
        return found.filter { it.ip.isNotEmpty() }.distinctBy { it.ip }
    }

    private fun cstr(b: ByteArray?): String =
        if (b == null) "" else String(b, Charsets.UTF_8).substringBefore('\u0000').trim()

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
