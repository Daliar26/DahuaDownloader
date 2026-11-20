package ru.dahuatools.downloader

import java.io.File

/**
 * Перепаковка DAV (контейнер Dahua) в MP4 через FFmpeg.
 * FFmpeg подключается через рефлексию: если библиотеки нет в сборке,
 * приложение не падает, а сохраняет исходный DAV.
 *
 * Сначала пробуем сохранить видео как есть (без перекодирования) и звук в AAC,
 * при неудаче — видео без звука.
 */
object Mp4Converter {

    private const val KIT = "com.arthenica.ffmpegkit.FFmpegKit"

    fun available(): Boolean = try {
        Class.forName(KIT)
        true
    } catch (e: Throwable) {
        false
    }

    /** null — успех, иначе текст причины. */
    fun convert(dav: File, mp4: File): String? {
        if (!available()) return "библиотека FFmpeg не входит в эту сборку"
        val input = "\"${dav.absolutePath}\""
        val output = "\"${mp4.absolutePath}\""

        val withAudio = run("-y -i $input -c:v copy -c:a aac -b:a 96k -movflags +faststart $output")
        if (withAudio.first && mp4.exists() && mp4.length() > 0) return null

        mp4.delete()
        val noAudio = run("-y -i $input -c:v copy -an -movflags +faststart $output")
        if (noAudio.first && mp4.exists() && mp4.length() > 0) return null

        mp4.delete()
        val log = (noAudio.second.ifBlank { withAudio.second }).trim()
        return "FFmpeg: " + log.takeLast(300).ifBlank { "нет сообщения об ошибке" }
    }

    private fun run(command: String): Pair<Boolean, String> {
        return try {
            val kit = Class.forName(KIT)
            val session = kit.getMethod("execute", String::class.java).invoke(null, command)
                ?: return false to "execute вернул null"
            val rc = session.javaClass.getMethod("getReturnCode").invoke(session)
            val value = rc?.javaClass?.getMethod("getValue")?.invoke(rc) as? Int ?: -1
            val logs = session.javaClass.getMethod("getAllLogsAsString").invoke(session) as? String ?: ""
            (value == 0) to logs
        } catch (e: Throwable) {
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
