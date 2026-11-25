package ru.dahuatools.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val DarkColors = darkColorScheme(
    primary = Color(0xFF35C3F3),
    onPrimary = Color(0xFF00212B),
    secondary = Color(0xFF9B8CFF),
    onSecondary = Color(0xFF14103A),
    tertiary = Color(0xFF2DD4A7),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6ECFA),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFE6ECFA),
    surfaceVariant = Color(0xFF1B2640),
    onSurfaceVariant = Color(0xFFA9B6D3),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF3A4A70)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A74B9),
    onPrimary = Color.White,
    secondary = Color(0xFF5B4BDB),
    tertiary = Color(0xFF0E9F7E),
    background = Color(0xFFF2F6FC),
    onBackground = Color(0xFF0E1726),
    surface = Color.White,
    onSurface = Color(0xFF0E1726),
    surfaceVariant = Color(0xFFE6EDF9),
    onSurfaceVariant = Color(0xFF47556F),
    error = Color(0xFFD64545),
    outline = Color(0xFFB6C2DA)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                DownloaderScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- общие элементы

@Composable
private fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---------------------------------------------------------------- каркас

@Composable
fun DownloaderScreen(vm: AppViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Header(vm)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
        ) {
            when (vm.tab) {
                0 -> ConnectionTab(vm)
                1 -> ArchiveTab(vm)
                else -> DownloadsTab(vm)
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            val active = vm.jobs.count { it.active }
            val tabs = listOf(
                "🔌" to "Подключение",
                "🎞" to "Архив",
                "⬇" to (if (active > 0) "Загрузки ($active)" else "Загрузки")
            )
            tabs.forEachIndexed { i, (icon, label) ->
                NavigationBarItem(
                    selected = vm.tab == i,
                    onClick = { vm.tab = i },
                    icon = { Text(icon, fontSize = 20.sp) },
                    label = { Text(label, maxLines = 1, fontSize = 11.sp) }
                )
            }
        }
    }
    if (vm.showSettings) SettingsDialog(vm)
}

@Composable
private fun Header(vm: AppViewModel) {
    val brush = Brush.horizontalGradient(listOf(Color(0xFF0A74B9), Color(0xFF6A4CFF)))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "DAHUA DOWNLOADER",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Архив регистратора",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.size(4.dp))
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (vm.connected) Color(0xFF2DD4A7) else Color(0xFFB0B8CC),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (vm.connected) "Подключено" else "Не подключено",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            TextButton(onClick = { vm.showSettings = true }) {
                Text("⚙", fontSize = 26.sp, color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- вкладка «Подключение»

@Composable
private fun ConnectionTab(vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppCard {
                Text("Регистратор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Подключение через Dahua P2P (тест)", modifier = Modifier.weight(1f))
                    Switch(checked = vm.p2pEnabled, onCheckedChange = { vm.setP2pMode(it) },
                        enabled = !vm.connected && !vm.busy)
                }
                if (vm.p2pEnabled) {
                    Field(vm.p2pSerial, { vm.p2pSerial = it }, "Серийный номер (SN)", enabled = !vm.connected)
                    Hint("P2P использует серийный номер и учётные данные регистратора. Совместимость порта 37777 зависит от модели и прошивки.")
                } else {
                    Field(vm.ip, { vm.ip = it }, "Адрес: IP или доменное имя", enabled = !vm.connected)
                    Field(vm.port, { vm.port = it }, "Порт (по умолчанию 37777)",
                        keyboard = KeyboardType.Number, enabled = !vm.connected)
                }
                Field(vm.user, { vm.user = it }, "Логин", enabled = !vm.connected)
                Field(vm.password, { vm.password = it }, "Пароль", password = true, enabled = !vm.connected)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.connect() }, enabled = !vm.connected && !vm.busy) {
                        Text("Подключиться")
                    }
                    OutlinedButton(onClick = { vm.disconnect() }, enabled = vm.connected && !vm.busy) {
                        Text("Отключиться")
                    }
                }
                Text(vm.status, fontWeight = FontWeight.SemiBold)
                if (vm.deviceInfo.isNotBlank()) Hint(vm.deviceInfo)
            }
        }

        item {
            AppCard {
                Text("Найти регистратор в сети", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Hint("Телефон должен быть в той же Wi-Fi сети, что и регистратор.")
                OutlinedButton(onClick = { vm.scan() }, enabled = !vm.scanning && !vm.busy && !vm.connected) {
                    Text(if (vm.scanning) "Поиск…" else "Искать в сети")
                }
                if (vm.scanNote.isNotBlank()) Hint(vm.scanNote)
                vm.devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.ip, fontWeight = FontWeight.Bold)
                            Hint(listOf(d.type, d.name, d.serial).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        TextButton(onClick = { vm.useDevice(d) }, enabled = !vm.connected) { Text("Выбрать") }
                    }
                }
            }
        }

        item {
            AppCard {
                Text("Доступ из интернета", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Для удалённого доступа используйте P2P-режим выше, если он поддерживается регистратором. " +
                        "Если P2P не подключается, безопасный резервный вариант — VPN до домашней сети.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Hint("Не открывайте порт регистратора напрямую в интернет. Используйте VPN, " +
                    "а затем подключайтесь по локальному IP регистратора.")
            }
        }

        item { Hint("Версия 0.4.0") }
    }
}

// ---------------------------------------------------------------- вкладка «Архив»

@Composable
private fun ArchiveTab(vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppCard {
                Text("Поиск записей", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!vm.connected) Hint("Сначала подключитесь к регистратору на вкладке «Подключение».")
                Field(vm.channel, { vm.channel = it }, "Канал камеры (1–4)", keyboard = KeyboardType.Number)
                Field(vm.date, { vm.date = it }, "Дата ГГГГ-ММ-ДД")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(vm.start, { vm.start = it }, "С (ЧЧ:ММ:СС)") }
                    Box(Modifier.weight(1f)) { Field(vm.end, { vm.end = it }, "По (ЧЧ:ММ:СС)") }
                }
                Hint("Время указывайте по часам регистратора.")
                Button(
                    onClick = { vm.search() },
                    enabled = vm.connected && !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Найти") }
                Hint(vm.message)
                Button(
                    onClick = { vm.downloadInterval() },
                    enabled = vm.connected && !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Скачать весь интервал одним файлом") }
            }
        }

        if (vm.found.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Найдено: ${vm.found.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.resultsExpanded = !vm.resultsExpanded }) {
                        Text(if (vm.resultsExpanded) "Свернуть" else "Развернуть")
                    }
                }
            }
            if (vm.resultsExpanded) {
                items(vm.found.take(vm.shownCount)) { r ->
                    val queued = vm.jobs.any { it.deviceFile == r.name && it.active }
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${r.start.substringAfter(' ')} – ${r.end.substringAfter(' ')}",
                                    fontWeight = FontWeight.Bold
                                )
                                Hint(String.format("%s · %.1f МБ", r.start.substringBefore(' '), r.sizeKb / 1024.0))
                            }
                            Text(if (queued) "✓ В загрузках" else "Фрагмент")
                        }
                    }
                }
                if (vm.found.size > vm.shownCount) {
                    item {
                        OutlinedButton(
                            onClick = { vm.shownCount += 20 },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Показать ещё (${vm.found.size - vm.shownCount})") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- вкладка «Загрузки»

@Composable
private fun statusColor(s: JobStatus): Color = when (s) {
    JobStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
    JobStatus.RUNNING -> MaterialTheme.colorScheme.primary
    JobStatus.CONVERTING -> MaterialTheme.colorScheme.secondary
    JobStatus.DONE -> MaterialTheme.colorScheme.tertiary
    JobStatus.ERROR -> MaterialTheme.colorScheme.error
    JobStatus.CANCELED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun DownloadsTab(vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Загрузки: ${vm.jobs.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.clearFinished() }, enabled = vm.jobs.any { !it.active }) {
                    Text("Очистить завершённые")
                }
            }
        }

        if (vm.jobs.isEmpty()) {
            item {
                AppCard {
                    Text("Пока пусто")
                    Hint("Найдите записи на вкладке «Архив» и нажмите «Скачать». Файлы появятся в папке «Загрузки/Dahua».")
                }
            }
        }

        items(vm.jobs, key = { it.id }) { job ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Pill(job.status.label, statusColor(job.status))
                }
                when (job.status) {
                    JobStatus.RUNNING -> {
                        LinearProgressIndicator(
                            progress = { job.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Hint("${job.progress}%")
                    }
                    JobStatus.CONVERTING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> {}
                }
                if (job.message.isNotBlank()) Hint(job.message)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (job.active) {
                        TextButton(onClick = { vm.cancelJob(job.id) }) { Text("Отменить") }
                    } else {
                        if (job.status == JobStatus.ERROR || job.status == JobStatus.CANCELED) {
                            TextButton(onClick = { vm.retryJob(job.id) }, enabled = vm.connected) {
                                Text("Повторить")
                            }
                        }
                        TextButton(onClick = { vm.removeJob(job.id) }) { Text("Убрать") }
                    }
                }
            }
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Журнал",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.showLog = !vm.showLog }) {
                        Text(if (vm.showLog) "Скрыть" else "Показать")
                    }
                }
                if (vm.showLog) {
                    SelectionContainer {
                        Text(
                            if (vm.log.isEmpty()) "Пока пусто" else vm.log.take(40).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    Hint("Текст можно выделить и скопировать.")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- настройки

@Composable
private fun SettingsDialog(vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        confirmButton = { TextButton(onClick = { vm.showSettings = false }) { Text("Готово") } },
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Одновременных загрузок", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { n ->
                            FilterChip(
                                selected = vm.parallel == n,
                                onClick = { vm.updateParallel(n) },
                                label = { Text("$n") }
                            )
                        }
                    }
                    Hint("Лучше начинать с 1: сколько параллельных загрузок выдерживает регистратор, пока не проверено.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Сохранять в MP4", fontWeight = FontWeight.SemiBold)
                        Hint(
                            if (vm.mp4Available) "Видео перепаковывается без потери качества."
                            else "Конвертер не входит в эту сборку: будет сохраняться исходный DAV."
                        )
                    }
                    Switch(
                        checked = vm.convertMp4 && vm.mp4Available,
                        onCheckedChange = { vm.setConvert(it) },
                        enabled = vm.mp4Available
                    )
                }
                Hint("Файлы сохраняются в папку «Загрузки/Dahua».")
            }
        }
    )
}
