package ru.dahuatools.downloader

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.NetSDK.NET_TIME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val logTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    // --- подключение (пароль в память только на время ввода, на диск не пишется) ---
    var ip by mutableStateOf(prefs.getString("ip", "") ?: "")
    var port by mutableStateOf(prefs.getString("port", "37777") ?: "37777")
    var user by mutableStateOf(prefs.getString("user", "admin") ?: "admin")
    var password by mutableStateOf("")
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Не подключено")
        private set
    var deviceInfo by mutableStateOf("")
        private set

    // --- форма задания ---
    var channel by mutableStateOf("1")
    var date by mutableStateOf(LocalDate.now().toString())
    var start by mutableStateOf("10:00:00")
    var end by mutableStateOf("10:15:00")
    var fileName by mutableStateOf("")
    var parallel by mutableIntStateOf(1)
    var message by mutableStateOf("Готов к работе")
        private set

    val found = mutableStateListOf<RecordItem>()
    val jobs = mutableStateListOf<DownloadJob>()
    val log = mutableStateListOf<String>()

    private var loginHandle = 0L
    private val running = HashMap<String, Job>()

    fun addLog(msg: String) {
        val line = "${LocalTime.now().format(logTime)} $msg"
        viewModelScope.launch(Dispatchers.Main.immediate) {
            log.add(0, line)
            if (log.size > 200) log.removeAt(log.lastIndex)
        }
    }

    // ------------------------------------------------------------------ подключение

    fun connect() {
        if (busy || connected) return
        val p = port.trim().toIntOrNull()
        if (ip.isBlank() || p == null || p !in 1..65535 || user.isBlank() || password.isEmpty()) {
            status = "Заполните IP, порт, логин и пароль"
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
                    NetSdk.findRecordings(loginHandle, q.channel0, q.from, q.to, 500)
                }
                found.addAll(res.items)
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

    // ------------------------------------------------------------------ очередь

    fun enqueue() {
        val q = buildQuery() ?: return
        val name = fileName.trim()
        if (name.isEmpty()) {
            message = "Укажите имя файла"
            return
        }
        jobs.add(
            DownloadJob(
                camera = q.channel0 + 1,
                date = date.trim(),
                start = start.trim(),
                end = end.trim(),
                fileName = name
            )
        )
        fileName = ""
        message = "Задание добавлено в очередь"
    }

    fun startQueue() {
        if (!connected) {
            message = "Сначала подключитесь к регистратору"
            return
        }
        val sem = Semaphore(parallel.coerceIn(1, 3))
        val ids = jobs.filter { it.status == JobStatus.QUEUED && !running.containsKey(it.id) }
            .map { it.id }
        if (ids.isEmpty()) {
            message = "В очереди нет заданий"
            return
        }
        ids.forEach { launchJob(it, sem) }
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

    private fun launchJob(id: String, sem: Semaphore) {
        val c = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var file: File? = null
            try {
                sem.withPermit {
                    val job = jobs.firstOrNull { it.id == id } ?: return@withPermit
                    if (job.status != JobStatus.QUEUED) return@withPermit
                    val (from, to) = parseRange(job.date, job.start, job.end)
                    val f = targetFile(job.fileName)
                    file = f
                    update(id) {
                        it.copy(status = JobStatus.RUNNING, progress = 0, message = "Загрузка…", path = f.absolutePath)
                    }
                    addLog("Старт: ${job.fileName} (канал ${job.camera}, ${job.date} ${job.start}–${job.end})")
                    withContext(Dispatchers.IO) {
                        NetSdk.downloadByTime(loginHandle, job.camera - 1, from, to, f) { pct ->
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                update(id) { it.copy(progress = pct) }
                            }
                        }
                    }
                    val mb = f.length() / 1024.0 / 1024.0
                    update(id) {
                        it.copy(
                            status = JobStatus.DONE,
                            progress = 100,
                            message = String.format("Готово: %.1f МБ\n%s", mb, f.absolutePath)
                        )
                    }
                    addLog("Готово: ${f.name}")
                }
            } catch (e: CancellationException) {
                file?.delete()
                update(id) { it.copy(status = JobStatus.CANCELED, message = "Отменено") }
                throw e
            } catch (e: SdkException) {
                file?.delete()
                update(id) { it.copy(status = JobStatus.ERROR, message = e.message ?: "Ошибка") }
                addLog("Ошибка задания: ${e.message}")
            } catch (e: Throwable) {
                file?.delete()
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
        if (i >= 0) jobs[i] = f(jobs[i])
    }

    private class Query(val channel0: Int, val from: NET_TIME, val to: NET_TIME)

    private fun buildQuery(): Query? {
        val c = channel.trim().toIntOrNull()
        if (c == null || c !in 1..4) {
            message = "Канал должен быть от 1 до 4"
            return null
        }
        return try {
            val (from, to) = parseRange(date, start, end)
            Query(c - 1, from, to)   // в SDK каналы нумеруются с 0
        } catch (e: SdkException) {
            message = e.message ?: "Проверьте параметры"
            null
        }
    }

    private fun parseRange(d: String, s: String, e: String): Pair<NET_TIME, NET_TIME> {
        val day = try {
            LocalDate.parse(d.trim())
        } catch (ex: DateTimeParseException) {
            throw SdkException("Дата: формат ГГГГ-ММ-ДД")
        }
        val t1 = try {
            LocalTime.parse(s.trim())
        } catch (ex: DateTimeParseException) {
            throw SdkException("Начало: формат ЧЧ:ММ:СС")
        }
        val t2 = try {
            LocalTime.parse(e.trim())
        } catch (ex: DateTimeParseException) {
            throw SdkException("Окончание: формат ЧЧ:ММ:СС")
        }
        if (!t1.isBefore(t2)) throw SdkException("Начало должно быть раньше окончания")
        return NetSdk.netTime(day, t1) to NetSdk.netTime(day, t2)
    }

    private fun targetFile(name: String): File {
        val ctx = getApplication<Application>()
        val dir = ctx.getExternalFilesDir("recordings") ?: File(ctx.filesDir, "recordings")
        dir.mkdirs()
        var base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        if (base.lowercase().endsWith(".dav")) base = base.dropLast(4)
        base = base.take(100)
        if (base.isEmpty()) base = "record"
        var f = File(dir, "$base.dav")
        var n = 1
        while (f.exists()) {
            f = File(dir, "${base}_$n.dav")
            n++
        }
        return f
    }

    override fun onCleared() {
        val h = loginHandle
        running.values.forEach { it.cancel() }
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
