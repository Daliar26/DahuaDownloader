package ru.dahuatools.downloader

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostic journal. Never records passwords or authentication tokens.
 * File is kept in app-specific storage and can be exported by the user.
 */
object DiagnosticLog {
    private val lock = Any()
    @Volatile private var file: File? = null
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun initialize(context: Context) {
        synchronized(lock) {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            file = File(dir, "dahua-diagnostics.log")
            writeLocked("INFO", "LOGGER", "Журнал диагностики запущен; версия приложения 0.4.x")
        }
    }

    fun write(level: String = "INFO", tag: String, message: String) {
        synchronized(lock) { writeLocked(level, tag, message) }
    }

    private fun writeLocked(level: String, tag: String, message: String) {
        val target = file ?: return
        try {
            val line = "${formatter.get().format(Date())} [${level.uppercase(Locale.ROOT)}] [${Thread.currentThread().name}] [${tag}] ${message.replace("\\r", " ").replace("\\n", " ")}\n"
            // Rotate at 2 MiB; retain one previous log.
            if (target.exists() && target.length() > 2L * 1024 * 1024) {
                val old = File(target.parentFile, "dahua-diagnostics.previous.log")
                if (old.exists()) old.delete()
                target.renameTo(old)
            }
            target.appendText(line, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Diagnostics must never crash the download flow.
        }
    }

    fun file(context: Context): File {
        val existing = file
        if (existing != null) return existing!!
        initialize(context)
        return file ?: File(context.filesDir, "logs/dahua-diagnostics.log")
    }

    fun read(context: Context): String = runCatching {
        val current = file(context)
        val previous = File(current.parentFile, "dahua-diagnostics.previous.log")
        (if (previous.exists()) previous.readText(Charsets.UTF_8) + "\n" else "") +
            if (current.exists()) current.readText(Charsets.UTF_8) else ""
    }.getOrDefault("Не удалось прочитать журнал диагностики")

    fun clear(context: Context) {
        synchronized(lock) {
            val current = file(context)
            current.writeText("", Charsets.UTF_8)
            File(current.parentFile, "dahua-diagnostics.previous.log").delete()
            writeLocked("INFO", "LOGGER", "Журнал очищен пользователем")
        }
    }
}
