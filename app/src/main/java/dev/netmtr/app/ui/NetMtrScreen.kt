package dev.netmtr.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netmtr.app.BuildConfig
import dev.netmtr.app.MainViewModel
import dev.netmtr.app.probe.HopRow
import dev.netmtr.app.probe.PingSummary
import dev.netmtr.app.probe.TextFormat
import kotlinx.coroutines.launch

private val presets = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "dns.google")

@Composable
fun NetMtrScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NetMTR", style = MaterialTheme.typography.headlineMedium)
            Text(
                "WinMTR, трассировка и пинг. Отчёт можно отправить администратору.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::setHost,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running,
                singleLine = true,
                label = { Text("Адрес или IP") },
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = state.host == preset,
                        onClick = { viewModel.setHost(preset) },
                        enabled = !state.running,
                        label = { Text(preset) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper("Циклы MTR", state.cycles, 1..30, !state.running, viewModel::setCycles, Modifier.weight(1f))
                Stepper("Пингов", state.pingCount, 4..50, !state.running, viewModel::setPingCount, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper("Прыжки", state.maxHops, 1..40, !state.running, viewModel::setMaxHops, Modifier.weight(1f))
                Stepper("Таймаут, с", state.timeoutSec, 1..5, !state.running, viewModel::setTimeout, Modifier.weight(1f))
            }

            Button(
                onClick = viewModel::runFull,
                enabled = !state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Полная проверка")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::runMtr,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("MTR") }
                OutlinedButton(
                    onClick = viewModel::runTrace,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("Трасса") }
                OutlinedButton(
                    onClick = viewModel::runPing,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                ) { Text("Пинг") }
            }
            if (state.running) {
                TextButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                    Text("Остановить")
                }
            }

            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            if (state.running || state.progress != null) {
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            if (state.error != null && state.report == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        state.error.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (state.conclusions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Для администратора", style = MaterialTheme.typography.titleMedium)
                        state.conclusions.forEach { line ->
                            Text("• $line", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (state.info.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.info.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            state.pingSummary?.let { summary ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Пинг ${summary.target}", style = MaterialTheme.typography.titleMedium)
                        StatRow(summary)
                        LatencyBars(summary.samples, MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (state.hops.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Маршрут", style = MaterialTheme.typography.titleMedium)
                        HopTable(state.hops)
                        Text(
                            "Молчащий промежуточный прыжок при живых следующих узлах обычно фильтрует ICMP, а не обрывает канал.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val report = state.report ?: return@Button
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NetMTR ${state.host}")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, "Отправить отчёт администратору"))
                        } catch (_: ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar("Нет приложения, чтобы отправить текст") }
                        }
                    },
                    enabled = state.report != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Отправить") }
                OutlinedButton(
                    onClick = {
                        val report = state.report ?: return@OutlinedButton
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("NetMTR", report))
                        scope.launch { snackbar.showSnackbar("Отчёт скопирован") }
                    },
                    enabled = state.report != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Копировать") }
            }

            Text(
                "NetMTR ${BuildConfig.VERSION_NAME}. Держите приложение открытым, пока идёт проверка.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { onChange(value - 1) },
                enabled = enabled && value > range.first,
            ) { Text("−") }
            Text(
                value.toString(),
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(
                onClick = { onChange(value + 1) },
                enabled = enabled && value < range.last,
            ) { Text("+") }
        }
    }
}

@Composable
private fun StatRow(summary: PingSummary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Потери", TextFormat.pct(summary.lossPercent))
        Stat("Мин", TextFormat.ms(summary.minMs))
        Stat("Сред", TextFormat.ms(summary.avgMs))
        Stat("Макс", TextFormat.ms(summary.maxMs))
        Stat("Джиттер", TextFormat.ms(summary.jitterMs))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LatencyBars(samples: List<Double>, color: Color) {
    if (samples.isEmpty()) return
    val max = (samples.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (samples.size - 1).coerceAtLeast(0)) / samples.size).coerceAtLeast(1f)
        samples.forEachIndexed { index, sample ->
            val barHeight = (sample / max * size.height).toFloat().coerceAtLeast(2f)
            drawRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
private fun HopTable(hops: List<HopRow>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Text(
            TextFormat.hopHeader(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hops.forEach { row ->
            val color = when {
                row.received == 0 -> MaterialTheme.colorScheme.outline
                row.lossPercent >= 10 -> MaterialTheme.colorScheme.error
                row.reachedTarget -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                TextFormat.hopLine(row),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
            )
        }
    }
}
