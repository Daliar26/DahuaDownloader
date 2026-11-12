package ru.dahuatools.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.UUID

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(),
    val camera: Int,
    val date: String,
    val start: String,
    val end: String,
    val fileName: String,
    val status: String = "В очереди"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DownloaderScreen()
            }
        }
    }
}

@Composable
fun DownloaderScreen() {
    var serial by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf("1") }
    var date by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("10:00:00") }
    var end by remember { mutableStateOf("10:15:00") }
    var fileName by remember { mutableStateOf("") }
    var parallel by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("Готов к работе") }

    val jobs = remember { mutableStateListOf<DownloadJob>() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "DAHUA DOWNLOADER",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text("Удалённый менеджер видеозаписей")
            }

            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Подключение P2P",
                            style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = serial,
                            onValueChange = { serial = it },
                            label = { Text("Серийный номер") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Dahua DHI-NVR2104-P-4KS2")
                        Text("Подключение SDK пока не настроено.")
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Новое задание",
                            style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = camera,
                            onValueChange = { camera = it },
                            label = { Text("Канал камеры (1–4)") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = date,
                            onValueChange = { date = it },
                            label = { Text("Дата ГГГГ-ММ-ДД") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text("Время начала") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it },
                            label = { Text("Время окончания") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("Имя файла") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val ch = camera.toIntOrNull()
                                val valid =
                                    ch != null && ch in 1..4 &&
                                    date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                                    start.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) &&
                                    end.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) &&
                                    start < end &&
                                    fileName.isNotBlank()

                                if (valid) {
                                    jobs.add(
                                        DownloadJob(
                                            camera = ch!!,
                                            date = date,
                                            start = start,
                                            end = end,
                                            fileName = fileName.trim()
                                        )
                                    )
                                    fileName = ""
                                    message = "Задание добавлено в очередь"
                                } else {
                                    message = "Проверь параметры задания"
                                }
                            }
                        ) {
                            Text("Добавить в очередь")
                        }
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Количество потоков")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..3).forEach { n ->
                                FilterChip(
                                    selected = parallel == n,
                                    onClick = { parallel = n },
                                    label = { Text("$n") }
                                )
                            }
                        }

                        Text(
                            "Реальная параллельная загрузка появится после интеграции Dahua SDK.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text(
                    "Очередь загрузок: ${jobs.size}",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            items(jobs, key = { it.id }) { job ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(job.fileName, fontWeight = FontWeight.Bold)
                        Text("Камера ${job.camera}")
                        Text("${job.date} ${job.start} – ${job.end}")
                        Text(job.status)
                    }
                }
            }

            item {
                Text(message)
                Text(
                    "Версия 0.1.0 · Прототип",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
