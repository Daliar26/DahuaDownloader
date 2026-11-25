package ru.dahuatools.downloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Storage {

    const val FOLDER = "Dahua"

    /** Временная папка для загрузки из регистратора (после экспорта файл удаляется). */
    fun tempDir(ctx: Context): File {
        val dir = ctx.getExternalFilesDir("tmp") ?: File(ctx.filesDir, "tmp")
        dir.mkdirs()
        return dir
    }

    // Временные файлы нельзя автоматически удалять при запуске:
    // частичные DAV-файлы используются для продолжения прерванных загрузок.

    /**
     * Копирует файл в общую папку «Загрузки/Dahua», доступную из любого файлового менеджера.
     * Android 10+ — через MediaStore (разрешения не нужны).
     * Android 8–9 — прямая запись; там может понадобиться разрешение на память.
     * Возвращает человекочитаемый путь.
     */
    fun exportToDownloads(ctx: Context, src: File, displayName: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw SdkException("Не удалось создать файл в папке «Загрузки»")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw SdkException("Не удалось открыть файл для записи")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.exists() && !dir.mkdirs()) {
                throw SdkException("Нет доступа к папке «Загрузки» (нужно разрешение на память)")
            }
            var target = File(dir, displayName)
            var n = 1
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            while (target.exists()) {
                target = File(dir, "${base}_$n" + if (ext.isEmpty()) "" else ".$ext")
                n++
            }
            src.copyTo(target)
        }
        return "Загрузки/$FOLDER/$displayName"
    }
}
