package dev.netmtr.app

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.netmtr.app.probe.Conclusions
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

data class UiState(
    val host: String = "8.8.8.8",
    val cycles: Int = 10,
    val maxHops: Int = 20,
    val timeoutSec: Int = 2,
    val pingCount: Int = 20,
    val running: Boolean = false,
    val status: String = "Укажите адрес и запустите проверку. Отчёт никуда не уходит, пока вы сами его не отправите.",
    val progress: Float? = null,
    val info: List<String> = emptyList(),
    val hops: List<HopRow> = emptyList(),
    val pingSummary: PingSummary? = null,
    val speed: SpeedResult? = null,
    val conclusions: List<String> = emptyList(),
    val report: String? = null,
    val error: String? = null,
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

    fun setCycles(value: Int) = setNumber { it.copy(cycles = value.coerceIn(1, 30)) }
    fun setMaxHops(value: Int) = setNumber { it.copy(maxHops = value.coerceIn(1, 40)) }
    fun setTimeout(value: Int) = setNumber { it.copy(timeoutSec = value.coerceIn(1, 5)) }
    fun setPingCount(value: Int) = setNumber { it.copy(pingCount = value.coerceIn(4, 50)) }

    fun runFull() = launch(TestMode.FULL) { draft -> runFull(draft) }
    fun runMtr() = launch(TestMode.MTR) { draft -> runPath(draft, cycles = _state.value.cycles) }
    fun runTrace() = launch(TestMode.TRACE) { draft -> runPath(draft, cycles = 1) }
    fun runPing() = launch(TestMode.PING) { draft -> runPing(draft) }
    fun runSpeed() = launch(TestMode.SPEED) { draft -> runSpeed(draft) }

    fun stop() {
        job?.cancel()
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
                conclusions = emptyList(),
                hops = emptyList(),
                pingSummary = null,
                speed = null,
                info = emptyList(),
                progress = null,
                status = "Запуск…",
            )
        }
        job?.cancel()
        job = viewModelScope.launch {
            val lock = wakeLock()
            var held = false
            try {
                lock.acquire(20 * 60 * 1000L)
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
        captureNetwork(draft)
        _state.update { it.copy(status = "DNS, веб и внешний адрес…") }
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
            _state.update { it.copy(status = "Пинг шлюза $gateway") }
            try {
                draft.gatewayPing = ping.pingMany(gateway, count = 10, timeoutSec = settings.timeoutSec) { summary ->
                    draft.gatewayPing = summary
                    _state.update { it.copy(status = "Пинг шлюза: ${summary.received} ответов") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                draft.remarks += "Шлюз не пингуется: ${error.message ?: "ошибка"}"
            }
        }
        refreshInfo(draft)
        _state.update { it.copy(status = "Пинг ${draft.host}") }
        draft.targetPing = ping.pingMany(draft.host, settings.pingCount, settings.timeoutSec) { summary ->
            _state.update {
                it.copy(
                    pingSummary = summary,
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
        draft.targetPing = ping.pingMany(draft.host, settings.pingCount, settings.timeoutSec) { summary ->
            _state.update {
                it.copy(
                    pingSummary = summary,
                    status = "Пинг: ${summary.received} из ${summary.transmitted}",
                )
            }
        }
    }

    private suspend fun runSpeed(draft: Draft) {
        if (draft.network == null) captureNetwork(draft)
        val speed = SpeedTest.measure { status ->
            _state.update { it.copy(status = status, progress = null) }
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
        if (draft.network == null) captureNetwork(draft)
        _state.update { it.copy(status = "Калибровка задержки…", progress = 0f) }
        val hops = mtr.run(
            host = draft.host,
            cycles = cycles,
            maxHops = settings.maxHops,
            timeoutSec = settings.timeoutSec,
        ) { progress ->
            val fraction = (
                (progress.cycle - 1) + progress.ttl.toFloat() / progress.limit.coerceAtLeast(1)
                ) / progress.cycles.coerceAtLeast(1)
            _state.update {
                it.copy(
                    hops = progress.hops,
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
            lines += if (speed.downloadError == null) {
                "Загрузка: ${TextFormat.mbps(speed.downloadMbps)}"
            } else {
                "Загрузка: ${speed.downloadError}"
            }
            lines += if (speed.uploadError == null) {
                "Отдача: ${TextFormat.mbps(speed.uploadMbps)}"
            } else {
                "Отдача: ${speed.uploadError}"
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
                conclusions = Conclusions.build(result),
                hops = result.hops.ifEmpty { it.hops },
                pingSummary = result.targetPing ?: it.pingSummary,
                speed = result.speed ?: it.speed,
                info = it.info,
                error = result.error,
                progress = 1f,
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
            )
        }
    }
}
