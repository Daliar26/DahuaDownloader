package ru.dahuatools.downloader

import java.util.UUID

enum class JobStatus(val label: String) {
    QUEUED("В очереди"),
    RUNNING("Загрузка"),
    DONE("Готово"),
    ERROR("Ошибка"),
    CANCELED("Отменено")
}

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(),
    val camera: Int,          // номер канала как в интерфейсе: 1..4
    val date: String,         // ГГГГ-ММ-ДД
    val start: String,        // ЧЧ:ММ:СС
    val end: String,          // ЧЧ:ММ:СС
    val fileName: String,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    val message: String = "",
    val path: String = ""
)

data class RecordItem(
    val name: String,
    val start: String,
    val end: String,
    val sizeKb: Long
)
