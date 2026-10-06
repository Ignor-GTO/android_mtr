package dev.netmtr.app

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.netmtr.app.probe.DnsResult
import dev.netmtr.app.probe.HopRow
import dev.netmtr.app.probe.Hosts
import dev.netmtr.app.probe.HttpResult
import dev.netmtr.app.probe.MtrEngine
import dev.netmtr.app.probe.NetChecks
import dev.netmtr.app.probe.NetworkInfo
import dev.netmtr.app.probe.NetworkSnapshot
import dev.netmtr.app.probe.PdfReport
import dev.netmtr.app.probe.PingClient
import dev.netmtr.app.probe.PingSummary
import dev.netmtr.app.probe.ProbeException
import dev.netmtr.app.probe.ReportText
import dev.netmtr.app.probe.SpeedResult
import dev.netmtr.app.probe.SpeedTest
import dev.netmtr.app.probe.TcpResult
import dev.netmtr.app.probe.TelegramReport
import dev.netmtr.app.probe.TestMode
import dev.netmtr.app.probe.TestResult
import dev.netmtr.app.probe.TextFormat
import dev.netmtr.app.probe.WifiSurvey
import dev.netmtr.app.probe.WifiSurveyor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

enum class RunPhase {
    IDLE,
    NETWORK,
    WIFI,
    EDGE,
    GATEWAY,
    PING,
    MTR,
    SPEED,
}

data class UiState(
    val host: String = "8.8.8.8",
    val cycles: Int = 100,
    val maxHops: Int = 20,
    val timeoutSec: Int = 2,
    val pingCount: Int = 10,
    val intervalTenths: Int = 10,
    val pingSize: Int = 64,
    val maxHosts: Int = 60,
    val resolveNames: Boolean = false,
    val running: Boolean = false,
    val status: String = "Укажите адрес и запустите проверку. Отчёт никуда не уходит, пока вы сами его не отправите.",
    val progress: Float? = null,
    val info: List<String> = emptyList(),
    val hops: List<HopRow> = emptyList(),
    val pingSummary: PingSummary? = null,
    val speed: SpeedResult? = null,
    val wifi: WifiSurvey? = null,
    val report: String? = null,
    val error: String? = null,
    val phase: RunPhase = RunPhase.IDLE,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ping = PingClient()
    private val mtr = MtrEngine(ping)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastResult: TestResult? = null

    fun setHost(value: String) {
        if (_state.value.running) return
        _state.update { it.copy(host = value.take(253)) }
    }

    fun setCycles(value: Int) = setNumber { it.copy(cycles = value.coerceIn(10, 200)) }
    fun setMaxHops(value: Int) = setNumber { it.copy(maxHops = value.coerceIn(1, 40)) }
    fun setTimeout(value: Int) = setNumber { it.copy(timeoutSec = value.coerceIn(1, 5)) }
    fun setPingCount(value: Int) = setNumber { it.copy(pingCount = value.coerceIn(1, 50)) }
    fun setIntervalTenths(value: Int) = setNumber { it.copy(intervalTenths = value.coerceIn(1, 50)) }
    fun setPingSize(value: Int) = setNumber { it.copy(pingSize = value.coerceIn(32, 1472)) }
    fun setMaxHosts(value: Int) = setNumber { it.copy(maxHosts = value.coerceIn(10, 200)) }
    fun setResolveNames(value: Boolean) = setNumber { it.copy(resolveNames = value) }

    fun runFull() = launch(TestMode.FULL) { draft -> runFull(draft) }
    fun runMtr() = launch(TestMode.MTR) { draft -> runPath(draft, cycles = _state.value.cycles) }
    fun runTrace() = launch(TestMode.TRACE) { draft -> runPath(draft, cycles = 1) }
    fun runPing() = launch(TestMode.PING) { draft -> runPing(draft) }
    fun runSpeed() = launch(TestMode.SPEED) { draft -> runSpeed(draft) }
    fun runWifi() = launch(TestMode.WIFI) { draft -> runWifiSurvey(draft) }

    fun stop() {
        job?.cancel()
    }

    fun newCheck() {
        if (_state.value.running) return
        lastResult = null
        _state.update {
            it.copy(
                report = null,
                hops = emptyList(),
                pingSummary = null,
                speed = null,
                wifi = null,
                info = emptyList(),
                error = null,
                progress = null,
                phase = RunPhase.IDLE,
                status = "Укажите адрес и запустите проверку. Отчёт никуда не уходит, пока вы сами его не отправите.",
            )
        }
    }

    fun telegramText(): String? = lastResult?.let(TelegramReport::build)

    suspend fun writePdf(uri: Uri) {
        val result = lastResult ?: error("Сначала выполните проверку")
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: error("Не удалось открыть файл")
            stream.use { PdfReport.write(result, it) }
        }
    }

    suspend fun sharePdfUri(): Uri = withContext(Dispatchers.IO) {
        val result = lastResult ?: error("Сначала выполните проверку")
        val app = getApplication<Application>()
        val dir = java.io.File(app.cacheDir, "reports").apply { mkdirs() }
        val file = java.io.File(dir, "SpectrIT-NetMTR-2.pdf")
        file.outputStream().use { PdfReport.write(result, it) }
        androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun setNumber(block: (UiState) -> UiState) {
        if (_state.value.running) return
        _state.update(block)
    }

    private fun launch(mode: TestMode, block: suspend (Draft) -> Unit) {
        if (_state.value.running) return
        val host = Hosts.normalize(_state.value.host)
        if (host == null) {
            _state.update { it.copy(error = "Введите IP или домен без пробелов и служебных символов.") }
            return
        }
        val draft = Draft(mode, host)
        _state.update {
            it.copy(
                running = true,
                host = host,
                error = null,
                report = null,
                hops = emptyList(),
                pingSummary = null,
                speed = null,
                wifi = null,
                info = emptyList(),
                progress = null,
                phase = when (mode) {
                    TestMode.WIFI -> RunPhase.WIFI
                    TestMode.MTR, TestMode.TRACE -> RunPhase.MTR
                    TestMode.SPEED -> RunPhase.SPEED
                    else -> RunPhase.NETWORK
                },
                status = "Запуск…",
            )
        }
        job?.cancel()
        job = viewModelScope.launch {
            val lock = wakeLock()
            var held = false
            try {
                lock.acquire(90 * 60 * 1000L)
                held = true
                block(draft)
                publish(draft, stopped = false)
            } catch (cancelled: CancellationException) {
                publish(draft, stopped = true)
                throw cancelled
            } catch (error: Exception) {
                draft.error = error.message ?: "Неизвестная ошибка"
                publish(draft, stopped = false)
            } finally {
                if (held && lock.isHeld) lock.release()
                _state.update { current -> current.copy(running = false) }
            }
        }
    }

    private suspend fun runFull(draft: Draft) {
        val settings = _state.value
        draft.cycles = settings.cycles
        draft.maxHops = settings.maxHops
        draft.timeoutSec = settings.timeoutSec
        _state.update { it.copy(phase = RunPhase.NETWORK, status = "Сеть…") }
        captureNetwork(draft)
        runWifiSurvey(draft)
        _state.update { it.copy(phase = RunPhase.EDGE, status = "DNS, веб и внешний адрес…", progress = null) }
        coroutineScope {
            val edgeTask = async { NetChecks.edge() }
            val names = buildList {
                add("google.com")
                add("cloudflare.com")
                if (!Hosts.isIp(draft.host)) add(draft.host)
            }
            val dnsTask = async { names.map { NetChecks.dns(it) } }
            val httpTask = async { NetChecks.httpGenerate204() }
            val tcpTask = async { NetChecks.tcp(draft.host) }
            val edge = edgeTask.await()
            draft.publicIp = edge.ip
            draft.colo = edge.colo
            draft.location = edge.location
            draft.dns += dnsTask.await()
            draft.http = httpTask.await()
            draft.tcp = tcpTask.await()
        }
        refreshInfo(draft)

        val gateway = draft.network?.gateway
        if (gateway != null) {
            _state.update { it.copy(phase = RunPhase.GATEWAY, progress = 0f, status = "Пинг шлюза $gateway") }
            try {
                draft.gatewayPing = ping.pingMany(gateway, count = settings.pingCount, timeoutSec = settings.timeoutSec, payloadBytes = settings.pingSize) { summary ->
                    draft.gatewayPing = summary
                    _state.update {
                        it.copy(
                            pingSummary = summary,
                            phase = RunPhase.GATEWAY,
                            progress = (summary.transmitted.toFloat() / settings.pingCount.coerceAtLeast(1)).coerceIn(0f, 1f),
                            status = "Пинг шлюза: ${summary.received} из ${summary.transmitted}",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                draft.remarks += "Шлюз не пингуется: ${error.message ?: "ошибка"}"
            }
        }
        refreshInfo(draft)
        _state.update { it.copy(phase = RunPhase.PING, progress = 0f, pingSummary = null, status = "Пинг ${draft.host}") }
        draft.targetPing = ping.pingMany(draft.host, settings.pingCount, settings.timeoutSec, settings.pingSize) { summary ->
            _state.update {
                it.copy(
                    pingSummary = summary,
                    phase = RunPhase.PING,
                    progress = (summary.transmitted.toFloat() / settings.pingCount.coerceAtLeast(1)).coerceIn(0f, 1f),
                    status = "Пинг цели: ${summary.received} из ${summary.transmitted}",
                )
            }
        }
        refreshInfo(draft)
        var pathError: String? = null
        try {
            runPath(draft, settings.cycles)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pathError = error.message ?: "Ошибка трассировки"
        }
        runSpeed(draft)
        if (pathError != null) throw ProbeException(pathError)
    }

    private suspend fun runPing(draft: Draft) {
        val settings = _state.value
        draft.timeoutSec = settings.timeoutSec
        captureNetwork(draft)
        _state.update { it.copy(phase = RunPhase.PING, progress = 0f, status = "Пинг ${draft.host}") }
        draft.targetPing = ping.pingMany(draft.host, settings.pingCount, settings.timeoutSec, settings.pingSize) { summary ->
            _state.update {
                it.copy(
                    pingSummary = summary,
                    phase = RunPhase.PING,
                    progress = (summary.transmitted.toFloat() / settings.pingCount.coerceAtLeast(1)).coerceIn(0f, 1f),
                    status = "Пинг: ${summary.received} из ${summary.transmitted}",
                )
            }
        }
    }

    private suspend fun runWifiSurvey(draft: Draft) {
        if (draft.network == null) captureNetwork(draft)
        _state.update { it.copy(phase = RunPhase.WIFI, progress = null, status = "Сканирование частот Wi‑Fi…") }
        val survey = try {
            WifiSurveyor.collect(getApplication())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WifiSurvey.failed(error.message ?: "Не удалось просканировать Wi‑Fi")
        }
        draft.wifi = survey
        _state.update { it.copy(wifi = survey) }
        refreshInfo(draft)
    }

    private suspend fun runSpeed(draft: Draft) {
        if (draft.network == null) captureNetwork(draft)
        _state.update { it.copy(phase = RunPhase.SPEED, progress = null, status = "Замер скорости…") }
        val speed = SpeedTest.measure { status ->
            _state.update { it.copy(phase = RunPhase.SPEED, status = status, progress = null) }
        }
        draft.speed = speed
        _state.update { it.copy(speed = speed) }
        refreshInfo(draft)
    }

    private suspend fun runPath(draft: Draft, cycles: Int) {
        val settings = _state.value
        draft.cycles = cycles
        draft.maxHops = settings.maxHops
        draft.timeoutSec = settings.timeoutSec
        draft.intervalSec = settings.intervalTenths / 10.0
        draft.pingBytes = settings.pingSize
        draft.hostCache = settings.maxHosts
        draft.resolveNames = settings.resolveNames
        if (draft.network == null) captureNetwork(draft)
        _state.update { it.copy(phase = RunPhase.MTR, status = "Калибровка задержки…", progress = 0f) }
        val hops = mtr.run(
            host = draft.host,
            cycles = cycles,
            maxHops = settings.maxHops,
            timeoutSec = settings.timeoutSec,
            intervalMs = settings.intervalTenths * 100L,
            payloadBytes = settings.pingSize,
            resolveNames = settings.resolveNames,
            maxHosts = settings.maxHosts,
        ) { progress ->
            val fraction = (
                (progress.cycle - 1) + progress.ttl.toFloat() / progress.limit.coerceAtLeast(1)
                ) / progress.cycles.coerceAtLeast(1)
            _state.update {
                it.copy(
                    hops = progress.hops,
                    phase = RunPhase.MTR,
                    progress = fraction.coerceIn(0f, 1f),
                    status = "MTR: цикл ${progress.cycle} из ${progress.cycles}, прыжок ${progress.ttl}",
                )
            }
        }
        draft.hops = hops
        draft.overheadMs = ping.overheadMs
        draft.overheadApplied = ping.overheadApplied
        _state.update { it.copy(hops = hops) }
    }

    private fun captureNetwork(draft: Draft) {
        val snapshot = NetworkInfo.collect(getApplication())
        draft.network = snapshot
        refreshInfo(draft)
        _state.update { it.copy(status = "Сеть: ${snapshot.networkType}") }
    }

    private fun refreshInfo(draft: Draft) {
        val lines = mutableListOf<String>()
        draft.network?.let { network ->
            val wifi = network.ssid?.let { " · $it" }.orEmpty()
            lines += "Сеть: ${network.networkType}$wifi"
            lines += "Шлюз: ${network.gateway ?: "не определён"}"
            if (network.dnsServers.isNotEmpty()) {
                lines += "DNS серверы: ${network.dnsServers.joinToString(", ")}"
            }
        }
        draft.publicIp?.let { ip ->
            val extra = listOfNotNull(draft.location, draft.colo).joinToString(" · ")
            lines += if (extra.isBlank()) "Внешний IP: $ip" else "Внешний IP: $ip ($extra)"
        }
        draft.dns.forEach { item ->
            lines += if (item.error != null) {
                "DNS ${item.name}: ${item.error}"
            } else {
                "DNS ${item.name}: ${item.elapsedMs} мс → ${item.addresses.firstOrNull() ?: "—"}"
            }
        }
        draft.http?.let { http ->
            lines += if (http.error != null) "Веб: ${http.error}" else "Веб: код ${http.code} за ${http.elapsedMs} мс"
        }
        draft.tcp?.let { tcp ->
            lines += if (tcp.error == null) "TCP 443: ${tcp.elapsedMs} мс" else "TCP 443: ${tcp.error}"
        }
        draft.gatewayPing?.let { summary ->
            lines += "Шлюз ${summary.target}: потери ${TextFormat.pct(summary.lossPercent)}, средняя ${TextFormat.msUnit(summary.avgMs)}"
        }
        draft.speed?.let { speed ->
            lines += "DOWNLOAD Mbps ${TextFormat.mbpsNumber(speed.downloadMbps)} · UPLOAD Mbps ${TextFormat.mbpsNumber(speed.uploadMbps)}"
            lines += "Ping ms ${TextFormat.latencyMs(speed.pingMs)} · ↓ ${TextFormat.latencyMs(speed.downloadLatencyMs)} · ↑ ${TextFormat.latencyMs(speed.uploadLatencyMs)}"
        }
        draft.wifi?.let { survey ->
            lines += survey.routerLine()
            if (survey.error == null) {
                val busy = survey.channels.filter { it.load == "нагружена" }
                val free = survey.channels.filter { it.band == "2.4 ГГц" && it.load == "свободна" }
                lines += "Нагружены: ${busy.joinToString { "канал ${it.channel} (${it.band})" }.ifBlank { "нет" }}"
                lines += "Свободны 2.4 ГГц: ${free.joinToString { it.channel.toString() }.ifBlank { "нет" }}"
            }
        }
        lines += draft.remarks
        _state.update { it.copy(info = lines) }
    }

    private fun publish(draft: Draft, stopped: Boolean) {
        val result = draft.toResult(stopped, BuildConfig.VERSION_NAME)
        lastResult = result
        _state.update {
            it.copy(
                report = ReportText.build(result),
                hops = result.hops.ifEmpty { it.hops },
                pingSummary = result.targetPing ?: it.pingSummary,
                speed = result.speed ?: it.speed,
                wifi = result.wifi ?: it.wifi,
                info = it.info,
                error = result.error,
                progress = 1f,
                phase = RunPhase.IDLE,
                status = when {
                    stopped -> "Остановлено. Частичный отчёт можно отправить."
                    result.error != null -> "Проверка закончилась с ошибкой. Отчёт всё равно можно отправить."
                    else -> "Готово. Отправьте отчёт администратору."
                },
            )
        }
    }

    private fun wakeLock(): PowerManager.WakeLock {
        val power = getApplication<Application>().getSystemService(PowerManager::class.java)
        return power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmtr:probe").apply {
            setReferenceCounted(false)
        }
    }

    private class Draft(val mode: TestMode, val host: String) {
        val startedAt: ZonedDateTime = ZonedDateTime.now()
        var network: NetworkSnapshot? = null
        var publicIp: String? = null
        var colo: String? = null
        var location: String? = null
        val dns = mutableListOf<DnsResult>()
        var http: HttpResult? = null
        var tcp: TcpResult? = null
        var gatewayPing: PingSummary? = null
        var targetPing: PingSummary? = null
        var hops: List<HopRow> = emptyList()
        var cycles: Int? = null
        var maxHops: Int? = null
        var timeoutSec: Int? = null
        var overheadMs: Double? = null
        var overheadApplied: Boolean = false
        var error: String? = null
        val remarks = mutableListOf<String>()
        var speed: SpeedResult? = null
        var intervalSec: Double? = null
        var pingBytes: Int? = null
        var hostCache: Int? = null
        var resolveNames: Boolean? = null
        var wifi: WifiSurvey? = null

        fun toResult(stopped: Boolean, version: String): TestResult {
            return TestResult(
                mode = mode,
                startedAt = startedAt,
                finishedAt = ZonedDateTime.now(),
                host = host,
                appVersion = version,
                network = network,
                publicIp = publicIp,
                colo = colo,
                location = location,
                dns = dns.toList(),
                http = http,
                tcp = tcp,
                gatewayPing = gatewayPing,
                targetPing = targetPing,
                hops = hops,
                cycles = cycles,
                maxHops = maxHops,
                timeoutSec = timeoutSec,
                overheadMs = overheadMs,
                overheadApplied = overheadApplied,
                stopped = stopped,
                error = error,
                remarks = remarks.toList(),
                speed = speed,
                intervalSec = intervalSec,
                pingBytes = pingBytes,
                hostCache = hostCache,
                resolveNames = resolveNames,
                wifi = wifi,
            )
        }
    }
}
