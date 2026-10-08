package dev.netmtr.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netmtr.app.BuildConfig
import dev.netmtr.app.MainViewModel
import dev.netmtr.app.RunPhase
import dev.netmtr.app.probe.ChannelLoad
import dev.netmtr.app.probe.HopRow
import dev.netmtr.app.probe.Hosts
import dev.netmtr.app.probe.LanIdentity
import dev.netmtr.app.probe.LanSurvey
import dev.netmtr.app.probe.PingSummary
import dev.netmtr.app.probe.SpeedResult
import dev.netmtr.app.probe.TextFormat
import dev.netmtr.app.probe.WifiSurvey
import dev.netmtr.app.ui.theme.Spectr
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val presets = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9", "dns.google")
private val cardShape = RoundedCornerShape(20.dp)

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        content = content,
    )
}

@Composable
private fun HeroPanel(eyebrow: String, title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Brush.horizontalGradient(listOf(Spectr.indigo500, Spectr.purple)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(eyebrow, color = Spectr.indigo100, style = MaterialTheme.typography.labelLarge)
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(detail, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NetMtrScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    var pendingWifiAction by remember { mutableStateOf("") }
    var detailsOpen by remember { mutableStateOf(false) }
    val askWifi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (pendingWifiAction) {
            "full" -> viewModel.runFull()
            "wifi" -> viewModel.runWifi()
            "lan" -> viewModel.runLan()
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                viewModel.writePdf(uri)
                snackbar.showSnackbar("PDF сохранён")
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "Не удалось сохранить PDF")
            }
        }
    }

    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    val dark = isSystemInDarkTheme()
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    if (dark) {
                        listOf(Color(0xFF111827), Color(0xFF1F2937))
                    } else {
                        listOf(Color(0xFFEFF6FF), Color(0xFFE0E7FF))
                    },
                ),
            ),
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
        topBar = {
            if (state.running) {
                RunStatusBar(state.status, state.progress)
            }
        },
        bottomBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .navigationBarsPadding(),
            ) {
            when {
                state.running -> {
                    Button(
                        onClick = viewModel::stop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) { Text("Остановить") }
                }
                state.report != null -> {
                    FinishedActions(
                        detailsOpen = detailsOpen,
                        onToggleDetails = { detailsOpen = !detailsOpen },
                        onNewCheck = {
                            detailsOpen = false
                            viewModel.newCheck()
                        },
                        onSend = {
                            val report = state.report ?: return@FinishedActions
                            val telegram = viewModel.telegramText() ?: report
                            scope.launch {
                                try {
                                    val pdf = viewModel.sharePdfUri()
                                    shareReport(context, report, telegram, "Spectr IT NetMTR-2 ${state.host}", pdf)
                                } catch (_: ActivityNotFoundException) {
                                    snackbar.showSnackbar("Нет приложения, чтобы отправить отчёт")
                                } catch (error: Exception) {
                                    snackbar.showSnackbar(error.message ?: "Не удалось отправить PDF")
                                }
                            }
                        },
                        onSave = {
                            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now())
                            val safeHost = state.host.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            savePdf.launch("SpectrIT-NetMTR-2-$safeHost-$stamp.pdf")
                        },
                    )
                }
            }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.running -> LiveStage(state)
                state.report != null -> {
                    if (detailsOpen) FullReport(state) else BriefReport(state)
                }
                else -> SetupForm(
                    state = state,
                    viewModel = viewModel,
                    onFull = {
                        if (hasWifiPermission(context)) {
                            viewModel.runFull()
                        } else {
                            pendingWifiAction = "full"
                            askWifi.launch(wifiPermissions())
                        }
                    },
                    onWifi = {
                        if (hasWifiPermission(context)) {
                            viewModel.runWifi()
                        } else {
                            pendingWifiAction = "wifi"
                            askWifi.launch(wifiPermissions())
                        }
                    },
                    onLan = {
                        if (hasWifiPermission(context)) {
                            viewModel.runLan()
                        } else {
                            pendingWifiAction = "lan"
                            askWifi.launch(wifiPermissions())
                        }
                    },
                )
            }
        }
    }
    }
}

@Composable
private fun RunStatusBar(status: String, progress: Float?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Spectr.indigo500, Spectr.purple)))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(status, color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (progress == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(99.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(99.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
        }
    }
}

@Composable
private fun FinishedActions(
    detailsOpen: Boolean,
    onToggleDetails: () -> Unit,
    onNewCheck: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onToggleDetails, modifier = Modifier.fillMaxWidth()) {
            Text(if (detailsOpen) "Свернуть" else "Посмотреть развёрнуто")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Отправить") }
            OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        }
        Button(
            onClick = onNewCheck,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text("Новая проверка") }
    }
}

@Composable
private fun SetupForm(
    state: dev.netmtr.app.UiState,
    viewModel: MainViewModel,
    onFull: () -> Unit,
    onWifi: () -> Unit,
    onLan: () -> Unit,
) {
    HeroPanel(
        eyebrow = "Spectr IT",
        title = stringResource(dev.netmtr.app.R.string.app_name),
        detail = "WinMTR, трассировка и пинг. Отчёт можно отправить администратору.",
    )
    OutlinedTextField(
        value = state.host,
        onValueChange = viewModel::setHost,
        modifier = Modifier.fillMaxWidth(),
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
                label = { Text(preset) },
            )
        }
    }
    SettingsSection(
        title = "MTR",
        summary = "${state.cycles} циклов по ${String.format(java.util.Locale.US, "%.1f", state.intervalTenths / 10.0)} с · ${state.maxHops} прыжков",
    ) {
        Stepper("Циклы MTR", state.cycles, 10..200, true, viewModel::setCycles, Modifier.fillMaxWidth(), step = 10)
        Stepper("Прыжки", state.maxHops, 1..40, true, viewModel::setMaxHops, Modifier.fillMaxWidth())
        DecimalStepper("Интервал, с", state.intervalTenths, 1..50, true, viewModel::setIntervalTenths, Modifier.fillMaxWidth())
        Text(
            "Один цикл длится этот интервал: каждый прыжок получает один пакет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Stepper("Размер, байт", state.pingSize, 32..1472, true, viewModel::setPingSize, Modifier.fillMaxWidth(), step = 8)
        Stepper("Кэш хостов", state.maxHosts, 10..200, true, viewModel::setMaxHosts, Modifier.fillMaxWidth(), step = 10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.resolveNames, onCheckedChange = viewModel::setResolveNames)
            Text("Определять имена")
        }
    }
    SettingsSection(
        title = "Пинг",
        summary = "${state.pingCount} пакетов · таймаут ${state.timeoutSec} с",
    ) {
        Stepper("Пингов", state.pingCount, 1..50, true, viewModel::setPingCount, Modifier.fillMaxWidth())
        Stepper("Таймаут, с", state.timeoutSec, 1..5, true, viewModel::setTimeout, Modifier.fillMaxWidth())
    }
    Button(
        onClick = onFull,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text("Полная проверка") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("MTR", viewModel::runMtr, Modifier.weight(1f))
        ActionTile("Трасса", viewModel::runTrace, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("Пинг", viewModel::runPing, Modifier.weight(1f))
        ActionTile("Скорость", viewModel::runSpeed, Modifier.weight(1f))
    }
    ActionTile("Частоты Wi‑Fi", onWifi, Modifier.fillMaxWidth())
    ActionTile("Устройства в сети", onLan, Modifier.fillMaxWidth())
    Text(
        "Spectr IT NetMTR-2 ${BuildConfig.VERSION_NAME}. Держите приложение открытым, пока идёт проверка.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!open) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(if (open) "▾" else "▸", color = Spectr.indigo500, style = MaterialTheme.typography.titleMedium)
            }
            if (open) content()
        }
    }
}

@Composable
private fun LiveStage(state: dev.netmtr.app.UiState) {
    when (state.phase) {
        RunPhase.WIFI -> state.wifi?.let { WifiCard(it) } ?: StagePlaceholder(state.status)
        RunPhase.LAN -> state.lan?.let { DevicesCard(it) } ?: StagePlaceholder(state.status)
        RunPhase.GATEWAY, RunPhase.PING -> state.pingSummary?.let { PingCard(it) } ?: StagePlaceholder(state.status)
        RunPhase.MTR -> if (state.hops.isNotEmpty()) HopCard(state.hops) else StagePlaceholder(state.status)
        RunPhase.SPEED -> state.speed?.let { SpeedCard(it) } ?: StagePlaceholder(state.status)
        RunPhase.NETWORK, RunPhase.EDGE -> {
            if (state.info.isNotEmpty()) InfoCard(state.info) else StagePlaceholder(state.status)
        }
        RunPhase.IDLE -> StagePlaceholder(state.status)
    }
}

@Composable
private fun StagePlaceholder(status: String) {
    AppCard {
        Text(status, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BriefReport(state: dev.netmtr.app.UiState) {
    HeroPanel(eyebrow = "Краткий отчёт", title = state.host, detail = state.status)
    state.error?.let { message ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                message,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    state.speed?.let { speed -> BriefSpeed(speed) }
    state.pingSummary?.let { summary ->
        SectionCard("Пинг ${summary.target}") {
            if ((summary.avgMs ?: 99.0) < 2.0 && !Hosts.isPrivate(summary.target)) {
                Text(
                    "Слишком быстро для узла в интернете. Ответ, скорее всего, отдал VPN.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatRow(summary)
        }
    }
    state.wifi?.let { survey -> BriefWifi(survey) }
    state.lan?.let { survey -> DevicesCard(survey) }
    if (state.hops.isNotEmpty()) HopCard(state.hops)
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun BriefSpeed(speed: SpeedResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171A1F)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Скорость", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Metric("Загрузка Mbps", TextFormat.mbpsNumber(speed.downloadMbps), Color(0xFF3DDC97), Modifier.weight(1f), labelColor = Color(0xFF9AA0A6))
                Metric("Отдача Mbps", TextFormat.mbpsNumber(speed.uploadMbps), Color(0xFFC084FC), Modifier.weight(1f), labelColor = Color(0xFF9AA0A6))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("Пинг мс", TextFormat.latencyMs(speed.pingMs), Color(0xFFF5C518), Modifier.weight(1f), labelColor = Color(0xFF9AA0A6))
                Metric("↓ мс", TextFormat.latencyMs(speed.downloadLatencyMs), Color(0xFF2EC4B6), Modifier.weight(1f), labelColor = Color(0xFF9AA0A6))
                Metric("↑ мс", TextFormat.latencyMs(speed.uploadLatencyMs), Color(0xFFB388FF), Modifier.weight(1f), labelColor = Color(0xFF9AA0A6))
            }
        }
    }
}

@Composable
private fun BriefWifi(survey: WifiSurvey) {
    SectionCard("Wi‑Fi") {
        when {
            survey.error != null -> Text(survey.error, color = MaterialTheme.colorScheme.error)
            survey.connectedFrequencyMhz == null -> Text("Телефон не подключён к Wi‑Fi.")
            else -> {
                Text(survey.connectedSsid ?: "Скрытая сеть", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    Metric("МГц", survey.connectedFrequencyMhz.toString(), modifier = Modifier.weight(1f))
                    Metric("Канал", survey.connectedChannel?.toString() ?: "—", modifier = Modifier.weight(1f))
                    Metric("Диапазон", survey.connectedBand ?: "—", modifier = Modifier.weight(1f))
                }
                val busy = survey.channels.count { it.load == "нагружена" }
                val free = survey.channels.count { it.band == "2.4 ГГц" && it.load == "свободна" }
                Row(Modifier.fillMaxWidth()) {
                    Metric("Нагружены", busy.toString(), modifier = Modifier.weight(1f))
                    Metric("Свободны 2.4", free.toString(), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
    labelColor: Color? = null,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FullReport(state: dev.netmtr.app.UiState) {
    HeroPanel(eyebrow = "Развёрнутый отчёт", title = state.host, detail = state.status)
    if (state.hops.isNotEmpty()) HopCard(state.hops)
    if (state.info.isNotEmpty()) InfoCard(state.info)
    state.speed?.let { SpeedCard(it) }
    state.wifi?.let { WifiCard(it) }
    state.lan?.let { DevicesCard(it) }
    state.pingSummary?.let { PingCard(it) }
}

@Composable
private fun DevicesCard(survey: LanSurvey) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Устройства в сети", style = MaterialTheme.typography.titleMedium)
            if (survey.error != null) {
                Text(survey.error, color = MaterialTheme.colorScheme.error)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Metric("Найдено", survey.devices.size.toString(), modifier = Modifier.weight(1f))
                    Metric("Проверено", "${survey.scanned}/${survey.planned}", modifier = Modifier.weight(1f))
                }
                survey.localAddress?.let { address ->
                    Text(
                        "Наш адрес $address/${survey.prefix ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                survey.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (survey.devices.isEmpty() && survey.scanned >= survey.planned && survey.planned > 0 && survey.note == null) {
                    Text("Живых адресов не найдено. Часть телефонов не отвечает на пинг.")
                }
                survey.devices.forEach { device ->
                    val title = device.name ?: "Без имени"
                    val mark = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
                        ?: device.address.substringAfterLast('.').take(2)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        Brush.linearGradient(listOf(Spectr.indigo500, Spectr.purple)),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(mark, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (device.gateway) "$title · шлюз" else title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    LanIdentity.displayMac(device.mac),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Spectr.indigo500,
                                )
                            }
                            Surface(shape = RoundedCornerShape(99.dp), color = Spectr.indigo.copy(alpha = 0.12f)) {
                                Text(
                                    device.rttMs?.let { "${TextFormat.ms(it)} мс" } ?: "ARP",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (isSystemInDarkTheme()) Spectr.indigo100 else Spectr.indigo,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(lines: List<String>) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сеть", style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(parts[0], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(parts[1], style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PingCard(summary: PingSummary) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Пинг ${summary.target}", style = MaterialTheme.typography.titleMedium)
            if ((summary.avgMs ?: 99.0) < 2.0 && !Hosts.isPrivate(summary.target)) {
                Text(
                    "Слишком быстро для узла в интернете. Ответ, скорее всего, отдал VPN.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatRow(summary)
            LatencyBars(summary.samples, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HopCard(hops: List<HopRow>) {
    AppCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Маршрут", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            HopTable(hops)
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
    step: Int = 1,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { onChange(value - step) },
                enabled = enabled && value - step >= range.first,
            ) { Text("−") }
            Text(
                value.toString(),
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(
                onClick = { onChange(value + step) },
                enabled = enabled && value + step <= range.last,
            ) { Text("+") }
        }
    }
}

@Composable
private fun DecimalStepper(
    label: String,
    tenths: Int,
    range: IntRange,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { onChange(tenths - 1) },
                enabled = enabled && tenths > range.first,
            ) { Text("−") }
            Text(
                String.format(java.util.Locale.US, "%.1f", tenths / 10.0),
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalIconButton(
                onClick = { onChange(tenths + 1) },
                enabled = enabled && tenths < range.last,
            ) { Text("+") }
        }
    }
}

@Composable
private fun SpeedCard(speed: SpeedResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171A1F)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                SpeedFigure("DOWNLOAD Mbps", TextFormat.mbpsNumber(speed.downloadMbps), Color(0xFF3DDC97), Modifier.weight(1f))
                SpeedFigure("UPLOAD Mbps", TextFormat.mbpsNumber(speed.uploadMbps), Color(0xFFC084FC), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ping ms", color = Color(0xFFF5C518), style = MaterialTheme.typography.labelMedium)
                    Text(
                        TextFormat.latencyMs(speed.pingMs),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                LatencyMark("↓", TextFormat.latencyMs(speed.downloadLatencyMs), Color(0xFF2EC4B6))
                LatencyMark("↑", TextFormat.latencyMs(speed.uploadLatencyMs), Color(0xFFB388FF))
            }
            val errors = listOfNotNull(speed.downloadError, speed.uploadError)
            if (errors.isNotEmpty()) {
                Text(errors.joinToString("\n"), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SpeedFigure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        Text(value, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LatencyMark(icon: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = Color(0xFF171A1F), fontWeight = FontWeight.Bold)
        }
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WifiCard(survey: WifiSurvey) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Частоты Wi‑Fi", style = MaterialTheme.typography.titleMedium)
            Text(survey.routerLine(), style = MaterialTheme.typography.bodyMedium)
            survey.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            survey.channels.forEach { channel -> ChannelRow(channel) }
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelLoad) {
    val color = when (channel.load) {
        "нагружена" -> Spectr.rose
        "слабо" -> Spectr.amber
        else -> Spectr.emerald
    }
    val fill = when (channel.load) {
        "нагружена" -> 0.92f
        "слабо" -> 0.48f
        else -> 0.16f
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Канал ${channel.channel} · ${channel.frequencyMhz} МГц", style = MaterialTheme.typography.titleSmall)
            if (channel.own) {
                Surface(shape = RoundedCornerShape(99.dp), color = Spectr.indigo) {
                    Text(
                        "наш",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { fill },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            "${channel.band} · соседей ${channel.neighbors} · ${channel.load}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun wifiPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasWifiPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT >= 33) {
        val nearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        return nearby || fine
    }
    return fine
}

@Composable
private fun StatRow(summary: PingSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Получено ${summary.received} из ${summary.transmitted}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Потери", TextFormat.pct(summary.lossPercent), Modifier.weight(1f))
            Stat("Мин, мс", TextFormat.ms(summary.minMs), Modifier.weight(1f))
            Stat("Сред, мс", TextFormat.ms(summary.avgMs), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Макс, мс", TextFormat.ms(summary.maxMs), Modifier.weight(1f))
            Stat("Джиттер, мс", TextFormat.ms(summary.jitterMs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    Column {
        hops.forEach { row ->
            val accent = when {
                row.received == 0 -> MaterialTheme.colorScheme.outline
                row.lossPercent >= 10 -> Spectr.rose
                row.reachedTarget -> Spectr.emerald
                else -> Spectr.indigo500
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(row.hop.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "потери ${TextFormat.pct(row.lossPercent)} · ${row.received}/${row.sent} · " +
                            "лучш ${TextFormat.ms(row.bestMs)} · сред ${TextFormat.ms(row.avgMs)} · " +
                            "худш ${TextFormat.ms(row.worstMs)} · посл ${TextFormat.ms(row.lastMs)} · джит ${TextFormat.ms(row.jitterMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    TextFormat.msUnit(row.avgMs),
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
