package dev.netmtr.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Window
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Spectr IT NetMTR",
        state = rememberWindowState(width = 860.dp, height = 760.dp),
    ) {
        MaterialTheme {
            DeskApp()
        }
    }
}

@Composable
private fun DeskApp() {
    var host by remember { mutableStateOf("8.8.8.8") }
    var status by remember { mutableStateOf("Пинг, MTR, скорость, Wi‑Fi и устройства. Проверка идёт с этого компьютера.") }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val reportScroll = rememberScrollState()

    fun note(text: String) {
        scope.launch { status = text }
    }

    fun show(text: String) {
        scope.launch { report = text }
    }

    fun launch(needsHost: Boolean = true, block: suspend (String) -> String) {
        if (running) return
        val target = host.trim()
        if (needsHost && (target.isEmpty() || target.any { it.isWhitespace() })) {
            status = "Введите адрес без пробелов."
            return
        }
        running = true
        report = ""
        status = if (target.isBlank()) "Идёт проверка…" else "Идёт проверка $target…"
        job = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { block(target) }
                report = text
                status = "Готово."
            } catch (_: CancellationException) {
                status = "Остановлено."
            } catch (_: InterruptedException) {
                status = "Остановлено."
            } catch (error: Exception) {
                status = error.message ?: "Ошибка проверки"
            } finally {
                running = false
            }
        }
    }

    fun saveReport() {
        if (report.isBlank()) return
        val chooser = JFileChooser().apply {
            dialogTitle = "Сохранить отчёт"
            selectedFile = File("SpectrIT-NetMTR-otchet.pdf")
            fileFilter = FileNameExtensionFilter("PDF", "pdf")
        }
        val parent = Window.getWindows().firstOrNull { it.isActive }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return
        val picked = chooser.selectedFile ?: return
        val file = if (picked.extension.equals("pdf", ignoreCase = true)) picked else File(picked.parentFile, "${picked.name}.pdf")
        val text = report
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ReportFile.writePdf(text, file) }
                status = "Отчёт сохранён: ${file.absolutePath}"
            } catch (error: Exception) {
                status = error.message ?: "Не удалось сохранить отчёт"
            }
        }
    }

    fun sendReport() {
        if (report.isBlank()) return
        val text = report
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(System.getProperty("java.io.tmpdir"), "spectr-netmtr")
                    val target = File(dir, "SpectrIT-NetMTR-report.pdf")
                    ReportFile.writePdf(text, target)
                    target
                }
                ReportFile.copy(text)
                ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
                status = "Текст отчёта скопирован. PDF выделен в папке — его можно переслать в Telegram."
            } catch (error: Exception) {
                status = error.message ?: "Не удалось подготовить отчёт"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Spectr IT NetMTR", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF4F46E5))
            Text(status, color = Color(0xFF475569))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.take(253) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Адрес или IP") },
                enabled = !running,
            )
            Button(
                onClick = {
                    launch { target ->
                        val parts = mutableListOf<String>()
                        note("Пинг…")
                        parts += formatPing(target, WinProbe.ping(target, count = 10, timeoutMs = 2000))
                        show(parts.joinToString("\n\n"))
                        note("MTR…")
                        parts += formatHops(WinProbe.mtr(target, cycles = 10, maxHops = 20, timeoutMs = 1200) { rows ->
                            show((parts + formatHops(rows)).joinToString("\n\n"))
                        })
                        show(parts.joinToString("\n\n"))
                        note("Скорость…")
                        parts += formatSpeed(WinExtra.speed(::note))
                        show(parts.joinToString("\n\n"))
                        note("Wi‑Fi…")
                        parts += "Wi‑Fi\n${WinExtra.wifi()}"
                        show(parts.joinToString("\n\n"))
                        note("Устройства в сети…")
                        parts += formatLan(WinExtra.lan(::note))
                        parts.joinToString("\n\n")
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Полная проверка") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        launch { target ->
                            formatHops(WinProbe.mtr(target, cycles = 10, maxHops = 20, timeoutMs = 1200) { show(formatHops(it)) })
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("MTR") }
                OutlinedButton(
                    onClick = {
                        launch { target ->
                            formatHops(WinProbe.mtr(target, cycles = 1, maxHops = 20, timeoutMs = 1500) { show(formatHops(it)) })
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Трасса") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launch { target -> formatPing(target, WinProbe.ping(target, count = 10, timeoutMs = 2000)) } },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Пинг") }
                OutlinedButton(
                    onClick = { launch { formatSpeed(WinExtra.speed(::note)) } },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Скорость") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launch(needsHost = false) { "Wi‑Fi\n${WinExtra.wifi()}" } },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Wi‑Fi") }
                OutlinedButton(
                    onClick = { launch(needsHost = false) { formatLan(WinExtra.lan(::note)) } },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) { Text("Устройства") }
            }
            if (report.isNotBlank() && !running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { sendReport() }, modifier = Modifier.weight(1f)) { Text("Отправить") }
                    OutlinedButton(onClick = { saveReport() }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
            if (running) {
                OutlinedButton(onClick = { job?.cancel(); running = false; status = "Остановлено." }, modifier = Modifier.fillMaxWidth()) {
                    Text("Стоп")
                }
            }
            if (report.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .verticalScroll(reportScroll)
                        .padding(12.dp),
                ) {
                    Text(report, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}

private fun formatPing(host: String, ping: DeskPing): String {
    val loss = if (ping.sent == 0) 0.0 else (ping.sent - ping.received) * 100.0 / ping.sent
    return buildString {
        appendLine("Пинг $host")
        appendLine("Получено ${ping.received} из ${ping.sent}, потери ${pct(loss)}")
        appendLine("Мин ${ms(ping.minMs)}   Сред ${ms(ping.avgMs)}   Макс ${ms(ping.maxMs)}")
    }
}

private fun formatHops(hops: List<DeskHop>): String {
    return buildString {
        appendLine("Прыжок  Хост                         Потери   Отпр  Прин   Лучш   Сред   Худш   Посл")
        hops.forEach { hop ->
            val loss = if (hop.sent == 0) 0.0 else (hop.sent - hop.received) * 100.0 / hop.sent
            appendLine(
                hop.hop.toString().padStart(6) + "  " +
                    hop.address.take(28).padEnd(28) + "  " +
                    pct(loss).padStart(6) + "  " +
                    hop.sent.toString().padStart(4) + "  " +
                    hop.received.toString().padStart(4) + "  " +
                    ms(hop.bestMs).padStart(6) + "  " +
                    ms(hop.avgMs).padStart(6) + "  " +
                    ms(hop.worstMs).padStart(6) + "  " +
                    ms(hop.lastMs).padStart(6),
            )
        }
    }
}

private fun ms(value: Double?): String {
    if (value == null) return "—"
    if (value < 1.0) return "<1"
    if (value < 10.0) return String.format(Locale.US, "%.1f", value)
    return String.format(Locale.US, "%.0f", value)
}

private fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value)

private fun formatSpeed(speed: DeskSpeed): String {
    return buildString {
        appendLine("Скорость")
        appendLine("Загрузка  ${mbps(speed.downloadMbps)} Мбит/с")
        appendLine("Отдача    ${mbps(speed.uploadMbps)} Мбит/с")
        appendLine("Пинг      ${ms(speed.pingMs)} мс")
        speed.error?.let { appendLine(it) }
    }
}

private fun formatLan(lan: DeskLan): String {
    return buildString {
        appendLine("Устройства в сети: ${lan.devices.size}")
        lan.note?.let { appendLine(it) }
        if (lan.devices.isEmpty()) {
            appendLine("Живых адресов не найдено.")
        } else {
            lan.devices.forEach { device ->
                appendLine(
                    device.address.padEnd(16) +
                        (device.mac ?: "—").padEnd(20) +
                        (device.rttMs?.let { "${ms(it)} мс" } ?: "—"),
                )
            }
        }
    }
}

private fun mbps(value: Double?): String {
    return if (value == null) "—" else String.format(Locale.US, "%.2f", value)
}
