package ru.dahuatools.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

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
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun DownloaderScreen(vm: AppViewModel = viewModel()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "DAHUA DOWNLOADER",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text("Архив Dahua DHI-NVR2104-P-4KS2 · подключение по локальной сети")
            }

            // ---------------- подключение
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Подключение (LAN)", style = MaterialTheme.typography.titleMedium)
                        Field(vm.ip, { vm.ip = it }, "IP-адрес регистратора", enabled = !vm.connected)
                        Field(vm.port, { vm.port = it }, "Порт (по умолчанию 37777)",
                            keyboard = KeyboardType.Number, enabled = !vm.connected)
                        Field(vm.user, { vm.user = it }, "Логин", enabled = !vm.connected)
                        Field(vm.password, { vm.password = it }, "Пароль",
                            password = true, enabled = !vm.connected)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.connect() },
                                enabled = !vm.connected && !vm.busy
                            ) { Text("Подключиться") }
                            OutlinedButton(
                                onClick = { vm.disconnect() },
                                enabled = vm.connected && !vm.busy
                            ) { Text("Отключиться") }
                        }
                        Text(vm.status, fontWeight = FontWeight.Bold)
                        if (vm.deviceInfo.isNotBlank()) {
                            Text(vm.deviceInfo, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---------------- задание
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Новое задание", style = MaterialTheme.typography.titleMedium)
                        Field(vm.channel, { vm.channel = it }, "Канал камеры (1–4)",
                            keyboard = KeyboardType.Number)
                        Field(vm.date, { vm.date = it }, "Дата ГГГГ-ММ-ДД")
                        Field(vm.start, { vm.start = it }, "Время начала (по часам регистратора)")
                        Field(vm.end, { vm.end = it }, "Время окончания")
                        Field(vm.fileName, { vm.fileName = it }, "Имя файла")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.search() },
                                enabled = vm.connected && !vm.busy
                            ) { Text("Найти записи") }
                            Button(onClick = { vm.enqueue() }) { Text("В очередь") }
                        }
                        Text(vm.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------------- результаты поиска
            if (vm.found.isNotEmpty()) {
                item {
                    Text(
                        "Найдено файлов: ${vm.found.size}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(vm.found.take(30)) { r ->
                    Card(shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${r.start} – ${r.end}")
                            Text(
                                String.format("%.1f МБ · %s", r.sizeKb / 1024.0, r.name),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (vm.found.size > 30) {
                    item { Text("…и ещё ${vm.found.size - 30}") }
                }
            }

            // ---------------- управление очередью
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Одновременных загрузок")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..3).forEach { n ->
                                FilterChip(
                                    selected = vm.parallel == n,
                                    onClick = { vm.parallel = n },
                                    label = { Text("$n") }
                                )
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.startQueue() },
                            enabled = vm.connected
                        ) { Text("Начать загрузку") }
                        Text(
                            "Файлы сохраняются в формате .dav в папку приложения. " +
                                "Лучше начинать с 1 потока: сколько параллельных загрузок " +
                                "выдерживает регистратор, пока не проверено.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text(
                    "Очередь загрузок: ${vm.jobs.size}",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            items(vm.jobs, key = { it.id }) { job ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.fileName, fontWeight = FontWeight.Bold)
                        Text("Камера ${job.camera} · ${job.date} ${job.start} – ${job.end}")
                        Text(job.status.label)
                        if (job.status == JobStatus.RUNNING || job.status == JobStatus.DONE) {
                            LinearProgressIndicator(
                                progress = { job.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (job.message.isNotBlank()) {
                            Text(job.message, style = MaterialTheme.typography.bodySmall)
                        }
                        if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) {
                            TextButton(onClick = { vm.cancelJob(job.id) }) { Text("Отменить") }
                        }
                    }
                }
            }

            // ---------------- журнал
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Журнал (можно выделить и скопировать)", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                if (vm.log.isEmpty()) "Пока пусто" else vm.log.take(40).joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Версия 0.2.0 · LAN, тестовая",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
