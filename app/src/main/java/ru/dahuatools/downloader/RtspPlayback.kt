package ru.dahuatools.downloader

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exports a Dahua time-range playback stream over the RTSP-only P2P tunnel.
 * The device credentials are held in memory and are never persisted here.
 */
object RtspPlayback {
    private val deviceTime = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
    private const val FFmpegKitClass = "com.arthenica.ffmpegkit.FFmpegKit"

    fun download(
        username: String,
        password: String,
        channel: Int,
        from: LocalDateTime,
        to: LocalDateTime,
        target: File
    ): String? {
        if (channel !in 1..4) return "Недопустимый номер канала"
        if (!from.isBefore(to)) return "Время начала должно быть раньше окончания"
        if (username.isBlank() || password.isEmpty()) return "Нет учётных данных RTSP"

        val user = encode(username)
        val pass = encode(password)
        val start = from.format(deviceTime)
        val end = to.format(deviceTime)
        val url = "rtsp://$user:$pass@127.0.0.1:37778/cam/playback" +
            "?channel=$channel&starttime=$start&endtime=$end"

        target.parentFile?.mkdirs()
        target.delete()
        return try {
            val kit = Class.forName(FFmpegKitClass)
            val command = listOf(
                "-y", "-rtsp_transport", "tcp", "-i", quote(url),
                "-map", "0:v:0", "-map", "0:a?",
                "-c:v", "copy", "-c:a", "aac", "-b:a", "96k",
                "-movflags", "+faststart", "-f", "mp4", quote(target.absolutePath)
            ).joinToString(" ")
            val session = kit.getMethod("execute", String::class.java).invoke(null, command)
                ?: return "FFmpegKit не создал сессию RTSP"
            val rc = session.javaClass.getMethod("getReturnCode").invoke(session)
            val value = rc?.javaClass?.getMethod("getValue")?.invoke(rc) as? Int ?: -1
            val logs = session.javaClass.getMethod("getAllLogsAsString").invoke(session) as? String ?: ""
            if (value == 0 && target.exists() && target.length() > 0L) null
            else {
                target.delete()
                "RTSP-выгрузка не завершилась (код $value): " +
                    logs.takeLast(700).ifBlank { "поток не вернул видеоданные" }
            }
        } catch (e: Throwable) {
            target.delete()
            val cause = e.cause ?: e
            "Ошибка RTSP/FFmpeg: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
