package ru.dahuatools.downloader

import java.util.UUID

enum class JobStatus(val label: String) {
    QUEUED("В очереди"),
    RUNNING("Загрузка"),
    CONVERTING("Конвертация в MP4"),
    DONE("Готово"),
    ERROR("Ошибка"),
    CANCELED("Отменено")
}

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(),
    val camera: Int,          // номер канала как в интерфейсе: 1..4
    val from: String,         // ГГГГ-ММ-ДД ЧЧ:ММ:СС (время регистратора)
    val to: String,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Int = 0,
    val message: String = ""
) {
    val active: Boolean
        get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING || status == JobStatus.CONVERTING

    val title: String
        get() = "Камера $camera · $from – ${to.substringAfter(' ')}"

    /** Имя без расширения: Cam1_20261117_100000-101500 */
    val baseName: String
        get() {
            val d = from.substringBefore(' ').replace("-", "")
            val s = from.substringAfter(' ').replace(":", "")
            val e = to.substringAfter(' ').replace(":", "")
            return "Cam${camera}_${d}_$s-$e"
        }
}

data class RecordItem(
    val camera: Int,
    val start: String,
    val end: String,
    val sizeKb: Long,
    val name: String
)

data class DiscoveredDevice(
    val ip: String,
    val port: Int,
    val serial: String,
    val type: String,
    val name: String
)
