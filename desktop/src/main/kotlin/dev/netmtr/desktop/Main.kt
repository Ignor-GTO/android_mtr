package dev.netmtr.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Spectr IT NetMTR",
        state = rememberWindowState(width = 760.dp, height = 640.dp),
    ) {
        MaterialTheme {
            DeskApp()
        }
    }
}

@Composable
private fun DeskApp() {
    var host by remember { mutableStateOf("8.8.8.8") }
    var status by remember { mutableStateOf("Пинг и MTR для Windows. Проверка идёт с этого компьютера.") }
    var report by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun launch(block: suspend () -> String) {
        if (running) return
        val target = host.trim()
        if (target.isEmpty() || target.any { it.isWhitespace() }) {
            status = "Введите адрес без пробелов."
            return
        }
        running = true
        report = ""
        status = "Идёт проверка $target…"
        job = scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { block() }
                report = text
                status = "Готово."
            } catch (error: Exception) {
                status = error.message ?: "Ошибка проверки"
            } finally {
                running = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8FAFC)) {
        Column(
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        launch {
                            val ping = WinProbe.ping(host.trim(), count = 10, timeoutMs = 2000)
                            formatPing(host.trim(), ping)
                        }
                    },
                    enabled = !running,
                ) { Text("Пинг") }
                Button(
                    onClick = {
                        launch {
                            val hops = WinProbe.mtr(host.trim(), cycles = 10, maxHops = 20, timeoutMs = 1500) { rows ->
                                scope.launch { report = formatHops(rows) }
                            }
                            formatHops(hops)
                        }
                    },
                    enabled = !running,
                ) { Text("MTR") }
                if (running) {
                    OutlinedButton(onClick = { job?.cancel(); running = false; status = "Остановлено." }) {
                        Text("Стоп")
                    }
                }
            }
            if (report.isNotBlank()) {
                Text(
                    report,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
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
    return if (value == null) "—" else String.format(Locale.US, "%.0f", value)
}

private fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value)
