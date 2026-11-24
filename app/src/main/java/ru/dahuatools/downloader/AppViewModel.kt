package ru.dahuatools.downloader

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Сколько раз пробовать докачать файл автоматически после восстановления связи. */
private const val MAX_AUTO_RETRIES = 3

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val logTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    // --- навигация и настройки ---
    var tab by mutableIntStateOf(0)
    var showSettings by mutableStateOf(false)
    var showLog by mutableStateOf(false)
    var parallel by mutableIntStateOf(prefs.getInt("parallel", 1).coerceIn(1, 3))
        private set
    var convertMp4 by mutableStateOf(prefs.getBoolean("mp4", true))
        private set
    val mp4Available: Boolean = Mp4Converter.available()

    // --- подключение (пароль в память только на время ввода, на диск не пишется) ---
    var ip by mutableStateOf(prefs.getString("ip", "") ?: "")
    var port by mutableStateOf(prefs.getString("port", "37777") ?: "37777")
    var user by mutableStateOf(prefs.getString("user", "admin") ?: "admin")
    var password by mutableStateOf("")
    var p2pEnabled by mutableStateOf(prefs.getBoolean("p2p_enabled", false))
        private set
    var p2pSerial by mutableStateOf(prefs.getString("p2p_serial", "") ?: "")
    private var p2pStarted = false
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Не подключено")
        private set
    var deviceInfo by mutableStateOf("")
        private set

    // --- поиск устройств в сети ---
    val devices = mutableStateListOf<DiscoveredDevice>()
    var scanning by mutableStateOf(false)
        private set
    var scanNote by mutableStateOf("")
        private set

    // --- архив ---
    var channel by mutableStateOf("1")
    var date by mutableStateOf(LocalDate.now().toString())
    var start by mutableStateOf("10:00:00")
    var end by mutableStateOf("10:15:00")
    var message by mutableStateOf("Готов к работе")
        private set
    val found = mutableStateListOf<RecordItem>()
    var resultsExpanded by mutableStateOf(true)
    var shownCount by mutableIntStateOf(20)

    // --- загрузки ---
    val jobs = mutableStateListOf<DownloadJob>()
    val log = mutableStateListOf<String>()

    private var loginHandle = 0L
    private val running = HashMap<String, Job>()
    private var semaphore = Semaphore(parallel)

    init {
        // Не удаляем временные DAV-файлы при запуске: они нужны для продолжения загрузки.
        jobs.addAll(loadPersistedJobs())
    }

    private fun loadPersistedJobs(): List<DownloadJob> {
        val raw = prefs.getString("download_jobs", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val savedStatus = runCatching {
                        JobStatus.valueOf(item.optString("status", JobStatus.ERROR.name))
                    }.getOrDefault(JobStatus.ERROR)
                    val interrupted = savedStatus == JobStatus.QUEUED ||
                        savedStatus == JobStatus.RUNNING || savedStatus == JobStatus.CONVERTING
                    add(
                        DownloadJob(
                            id = item.optString("id").takeIf { it.isNotBlank() }
                                ?: java.util.UUID.randomUUID().toString(),
                            camera = item.optInt("camera", 1).coerceIn(1, 4),
                            from = item.optString("from"),
                            to = item.optString("to"),
                            deviceFile = item.optString("deviceFile"),
                            status = if (interrupted) JobStatus.ERROR else savedStatus,
                            progress = item.optInt("progress", 0).coerceIn(0, 100),
                            message = if (interrupted) {
                                "Приложение было перезапущено. Подключитесь к регистратору и нажмите «Повторить»."
                            } else item.optString("message"),
                            retryCount = item.optInt("retryCount", 0).coerceAtLeast(0)
                        )
                    )
                }
            }.filter { it.deviceFile.isNotBlank() && it.from.isNotBlank() && it.to.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistJobs() {
        try {
            val array = JSONArray()
            jobs.forEach { job ->
                array.put(
                    JSONObject()
                        .put("id", job.id)
                        .put("camera", job.camera)
                        .put("from", job.from)
                        .put("to", job.to)
                        .put("deviceFile", job.deviceFile)
                        .put("status", job.status.name)
                        .put("progress", job.progress)
                        .put("message", job.message)
                        .put("retryCount", job.retryCount)
                )
            }
            prefs.edit().putString("download_jobs", array.toString()).apply()
        } catch (_: Exception) {
            // Ошибка сохранения очереди не должна останавливать загрузку.
        }
    }

    fun addLog(msg: String) {
        val line = "${LocalTime.now().format(logTime)} $msg"
        viewModelScope.launch(Dispatchers.Main.immediate) {
            log.add(0, line)
            if (log.size > 200) log.removeAt(log.lastIndex)
        }
    }

    fun setP2pEnabled(enabled: Boolean) {
        if (connected || busy) return
        p2pEnabled = enabled
        prefs.edit().putBoolean("p2p_enabled", enabled).apply()
    }

    fun updateParallel(n: Int) {
        parallel = n.coerceIn(1, 3)
        semaphore = Semaphore(parallel)
        prefs.edit().putInt("parallel", parallel).apply()
    }

    fun setConvert(on: Boolean) {
        convertMp4 = on
        prefs.edit().putBoolean("mp4", on).apply()
    }

    // ------------------------------------------------------------------ подключение

    fun useDevice(d: DiscoveredDevice) {
        ip = d.ip
        port = if (d.port > 0) d.port.toString() else "37777"
        status = "Выбран ${d.ip}. Введите логин и пароль."
    }

    fun scan() {
        if (scanning || busy) return
        scanning = true
        scanNote = "Поиск в локальной сети…"
        devices.clear()
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val wifi = getApplication<Application>().applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    // Без MulticastLock Android отбрасывает широковещательные ответы.
                    val lock = try {
                        wifi?.createMulticastLock("dahua-discovery")?.also {
                            it.setReferenceCounted(false)
                            it.acquire()
                        }
                    } catch (e: SecurityException) {
                        null
                    }
                    try {
                        NetSdk.onEvent = { addLog(it) }
                        NetSdk.ensureInit { addLog(it) }
                        NetSdk.discover(4000)
                    } finally {
                        lock?.release()
                    }
                }
                devices.addAll(list)
                scanNote = if (list.isEmpty()) {
                    "Ничего не найдено. Телефон должен быть в той же Wi-Fi сети; " +
                        "на некоторых роутерах поиск заблокирован — введите IP вручную."
                } else {
                    "Найдено устройств: ${list.size}"
                }
                addLog(scanNote)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SdkException) {
                scanNote = e.message ?: "Ошибка поиска"
                addLog("Поиск: ${e.message}")
            } catch (e: Throwable) {
                scanNote = "Сбой поиска: ${e.javaClass.simpleName}: ${e.message}"
                addLog(scanNote)
            } finally {
                scanning = false
            }
        }
    }

    fun connect() {
        if (busy || connected) return
        val p = port.trim().toIntOrNull()
        if (ip.isBlank() || p == null || p !in 1..65535 || user.isBlank() || password.isEmpty()) {
            status = "Заполните адрес, порт, логин и пароль"
            return
        }
        val host = ip.trim()
        val login = user.trim()
        val pass = password
        busy = true
        status = "Подключение…"
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    NetSdk.onEvent = { addLog(it) }
                    NetSdk.onReconnect = { onConnectionRestored() }
                    NetSdk.ensureInit { addLog(it) }
                    NetSdk.login(host, p, login, pass)
                }
                loginHandle = info.handle
                connected = true
                status = "Подключено"
                deviceInfo = "Каналов: ${info.channels}, SN: ${info.serial}" +
                    (info.deviceTime?.let { ", время устройства: $it" } ?: "")
                password = ""
                prefs.edit().putString("ip", host).putString("port", p.toString())
                    .putString("user", login).apply()
                addLog("Вход выполнен. $deviceInfo")
                tab = 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: SdkException) {
                status = e.message ?: "Ошибка"
                addLog("Ошибка: ${e.message}")
            } catch (e: Throwable) {
                status = "Сбой: ${e.javaClass.simpleName}: ${e.message}"
                addLog(status)
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        if (!connected || busy) return
        busy = true
        val h = loginHandle
        val toStop = running.values.toList()
        toStop.forEach { it.cancel() }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    toStop.joinAll()
                    NetSdk.logout(h)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                addLog("Выход: ${e.message}")
            } finally {
                loginHandle = 0L
                connected = false
                status = "Отключено"
                deviceInfo = ""
                found.clear()
                busy = false
                addLog("Отключено")
            }
        }
    }

    /** Срабатывает, когда SDK сообщает, что связь с регистратором восстановлена. */
    private fun onConnectionRestored() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val candidates = jobs.filter {
                it.status == JobStatus.ERROR && it.retryCount < MAX_AUTO_RETRIES && !running.containsKey(it.id)
            }
            if (candidates.isEmpty()) return@launch
            addLog("Связь восстановлена — продолжаю ${candidates.size} загрузку(и) с места обрыва")
            candidates.forEach { job ->
                val i = jobs.indexOfFirst { it.id == job.id }
                if (i >= 0) {
                    jobs[i] = jobs[i].copy(
                        status = JobStatus.QUEUED,
                        message = "Автоматическое продолжение после обрыва связи",
                        retryCount = job.retryCount + 1
                    )
                }
                launchJob(job.id)
            }
            persistJobs()
        }
    }

    // ------------------------------------------------------------------ поиск архива

    fun search() {
        if (!connected) {
            message = "Сначала подключитесь к регистратору"
            return
        }
        if (busy) return
        val q = buildQuery() ?: return
        busy = true
        message = "Поиск записей…"
        found.clear()
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    NetSdk.findRecordings(
                        loginHandle, q.channel0, NetSdk.netTime(q.from), NetSdk.netTime(q.to), 500
                    )
                }
                found.addAll(res.items)
                shownCount = 20
                resultsExpanded = true
                message = when {
                    res.items.isEmpty() -> "За этот период записей нет"
                    res.truncated -> "Найдено файлов: ${res.items.size} (показаны первые 500)"
                    else -> "Найдено файлов: ${res.items.size}"
                }
                addLog(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SdkException) {
                message = e.message ?: "Ошибка поиска"
                addLog("Ошибка поиска: ${e.message}")
            } catch (e: Throwable) {
                message = "Сбой поиска: ${e.javaClass.simpleName}: ${e.message}"
                addLog(message)
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------ загрузки

    /**
     * Скачать один найденный файл. Если эта запись уже когда-то начинала скачиваться
     * (ошибка или отмена), продолжает тот же файл с места остановки, а не с начала.
     */
    fun downloadFile(r: RecordItem) {
        if (!connected) {
            message = "Сначала подключитесь к регистратору"
            return
        }
        val existing = jobs.firstOrNull { it.deviceFile == r.name }
        if (existing != null) {
            if (existing.active) {
                message = "Эта запись уже в загрузках"
                return
            }
            message = if (existing.status == JobStatus.DONE) "Скачивание заново" else "Продолжаю докачку"
            retryJob(existing.id)
            return
        }
        val job = DownloadJob(camera = r.camera, from = r.start, to = r.end, deviceFile = r.name)
        jobs.add(0, job)
        persistJobs()
        message = "Добавлено в загрузки"
        launchJob(job.id)
    }

    fun cancelJob(id: String) {
        val r = running[id]
        if (r != null) {
            r.cancel()
        } else {
            update(id) {
                if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.CANCELED, message = "Отменено") else it
            }
        }
    }

    /** Повторить вручную: продолжит докачку с уже скачанного места, а не с начала. */
    fun retryJob(id: String) {
        if (!connected) {
            message = "Сначала подключитесь к регистратору"
            return
        }
        update(id) {
            if (!it.active) {
                // DONE сбрасывается на скачивание заново (временный файл уже удалён при успехе),
                // ERROR/CANCELED продолжат с того места, где остался временный файл.
                val progress = if (it.status == JobStatus.DONE) 0 else it.progress
                it.copy(status = JobStatus.QUEUED, message = "", progress = progress)
            } else it
        }
        launchJob(id)
    }

    fun removeJob(id: String) {
        if (running.containsKey(id)) return
        tempDavFile(id).delete()
        tempMp4File(id).delete()
        jobs.removeAll { it.id == id }
        persistJobs()
    }

    fun clearFinished() {
        val toRemove = jobs.filter { !it.active }
        toRemove.forEach {
            tempDavFile(it.id).delete()
            tempMp4File(it.id).delete()
        }
        jobs.removeAll { !it.active }
        persistJobs()
    }

    private fun tempDavFile(id: String): File =
        File(Storage.tempDir(getApplication()), "$id.dav")

    private fun tempMp4File(id: String): File =
        File(Storage.tempDir(getApplication()), "$id.mp4")

    private fun launchJob(id: String) {
        if (running.containsKey(id)) return
        val sem = semaphore
        val c = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val ctx = getApplication<Application>()
            try {
                sem.withPermit {
                    val job = jobs.firstOrNull { it.id == id } ?: return@withPermit
                    if (job.status != JobStatus.QUEUED) return@withPermit
                    val tmpDav = tempDavFile(id)
                    val resuming = tmpDav.exists() && tmpDav.length() > 0

                    update(id) {
                        it.copy(
                            status = JobStatus.RUNNING,
                            message = if (resuming) "Продолжаю с ${tmpDav.length() / 1024 / 1024} МБ…"
                            else "Скачивание с регистратора…"
                        )
                    }
                    addLog(
                        (if (resuming) "Продолжение: " else "Старт: ") + job.title +
                            if (resuming) " (уже есть ${tmpDav.length() / 1024 / 1024} МБ)" else ""
                    )
                    withContext(Dispatchers.IO) {
                        NetSdk.downloadFilePiece(loginHandle, job.deviceFile, tmpDav) { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                update(id) { it.copy(progress = pct) }
                            }
                        }
                    }

                    var exportFile = tmpDav
                    var ext = "dav"
                    var mime = "application/octet-stream"
                    var note = ""
                    if (convertMp4 && Mp4Converter.available()) {
                        update(id) {
                            it.copy(status = JobStatus.CONVERTING, progress = 100, message = "Конвертация в MP4…")
                        }
                        val out = tempMp4File(id)
                        val err = withContext(Dispatchers.IO) { Mp4Converter.convert(tmpDav, out) }
                        if (err == null) {
                            exportFile = out
                            ext = "mp4"
                            mime = "video/mp4"
                        } else {
                            note = "\nMP4 не получился, сохранён исходный DAV"
                            addLog("Конвертация: $err")
                        }
                    } else if (convertMp4) {
                        note = "\nFFmpeg нет в этой сборке, сохранён исходный DAV"
                    }

                    val shown = withContext(Dispatchers.IO) {
                        Storage.exportToDownloads(ctx, exportFile, "${job.baseName}.$ext", mime)
                    }
                    val mb = exportFile.length() / 1024.0 / 1024.0
                    update(id) {
                        it.copy(
                            status = JobStatus.DONE,
                            progress = 100,
                            message = String.format("Сохранено: %s (%.1f МБ)%s", shown, mb, note)
                        )
                    }
                    addLog("Готово: $shown")
                    // Временные файлы больше не нужны только при успехе — докачка завершена.
                    tmpDav.delete()
                    tempMp4File(id).delete()
                }
            } catch (e: CancellationException) {
                // Частичный файл НЕ удаляем: это и есть точка, с которой продолжит докачка.
                update(id) { it.copy(status = JobStatus.CANCELED, message = "Остановлено, можно продолжить") }
                throw e
            } catch (e: SdkException) {
                // Частичный файл тоже остаётся — для докачки после «Повторить» или авто-продолжения.
                update(id) { it.copy(status = JobStatus.ERROR, message = e.message ?: "Ошибка") }
                addLog("Ошибка задания: ${e.message}")
            } catch (e: Throwable) {
                update(id) {
                    it.copy(status = JobStatus.ERROR, message = "Сбой: ${e.javaClass.simpleName}: ${e.message}")
                }
                addLog("Сбой задания: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running.remove(id)
            }
        }
        running[id] = c
        c.start()
    }

    // ------------------------------------------------------------------ вспомогательное

    private fun update(id: String, f: (DownloadJob) -> DownloadJob) {
        val i = jobs.indexOfFirst { it.id == id }
        if (i >= 0) {
            jobs[i] = f(jobs[i])
            persistJobs()
        }
    }

    private class Query(val channel0: Int, val from: LocalDateTime, val to: LocalDateTime)

    private fun buildQuery(): Query? {
        val c = channel.trim().toIntOrNull()
        if (c == null || c !in 1..4) {
            message = "Канал должен быть от 1 до 4"
            return null
        }
        val day = try {
            LocalDate.parse(date.trim())
        } catch (ex: DateTimeParseException) {
            message = "Дата: формат ГГГГ-ММ-ДД"
            return null
        }
        val t1 = try {
            LocalTime.parse(start.trim())
        } catch (ex: DateTimeParseException) {
            message = "Начало: формат ЧЧ:ММ:СС"
            return null
        }
        val t2 = try {
            LocalTime.parse(end.trim())
        } catch (ex: DateTimeParseException) {
            message = "Окончание: формат ЧЧ:ММ:СС"
            return null
        }
        if (!t1.isBefore(t2)) {
            message = "Начало должно быть раньше окончания"
            return null
        }
        return Query(c - 1, LocalDateTime.of(day, t1), LocalDateTime.of(day, t2))
    }

    override fun onCleared() {
        persistJobs()
        val h = loginHandle
        running.values.forEach { it.cancel() }
        NetSdk.onReconnect = null
        if (h != 0L) {
            Thread {
                try {
                    NetSdk.logout(h)
                } catch (ex: Throwable) {
                    // выход при закрытии приложения: ошибку показать некуда
                }
            }.start()
        }
        super.onCleared()
    }
}
