package dev.netmtr.app.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

sealed interface Probe {
    data class Echo(val address: String, val rttMs: Double) : Probe
    data class Transit(val address: String, val rttMs: Double) : Probe
    data class Unreachable(val address: String, val reason: String, val rttMs: Double?) : Probe
    data object Timeout : Probe
    data class Failure(val message: String, val usage: Boolean) : Probe
}

class PingClient(
    private val runner: ProcessRunner = ProcessRunner(),
    private val pingBinary: String = defaultBinary(),
) {
    var overheadMs: Double = 0.0
        private set
    var overheadApplied: Boolean = false
        private set

    private var calibrated = false

    suspend fun calibrate() {
        if (calibrated) return
        val samples = mutableListOf<Double>()
        repeat(2) {
            coroutineContext.ensureActive()
            var reported: Double? = null
            var elapsed: Double? = null
            runner.run(localCommand(), CALIBRATION_TIMEOUT_MS) { line, elapsedMs ->
                val event = PingParser.parseLine(line)
                if (event is LineEvent.Echo && event.rttMs != null) {
                    reported = event.rttMs
                    elapsed = elapsedMs
                    true
                } else {
                    false
                }
            }
            val reportedMs = reported
            val elapsedMs = elapsed
            if (reportedMs != null && elapsedMs != null) {
                samples += (elapsedMs - reportedMs).coerceAtLeast(0.0)
            }
        }
        val raw = samples.minOrNull()
        if (raw != null && raw <= MAX_OVERHEAD_MS) {
            overheadMs = raw
            overheadApplied = true
        } else {
            overheadMs = 0.0
            overheadApplied = false
        }
        calibrated = true
    }

    suspend fun probe(
        host: String,
        ttl: Int,
        timeoutSec: Int,
        payloadBytes: Int = 64,
        numeric: Boolean = true,
    ): Probe {
        var last = Probe.Failure("ping не выполнился", usage = false)
        for ((index, command) in commands(host, count = 1, timeoutSec = timeoutSec, ttl = ttl, payloadBytes = payloadBytes, numeric = numeric).withIndex()) {
            coroutineContext.ensureActive()
            val result = probeOnce(command, timeoutSec)
            val retry = result is Probe.Failure && result.usage && index < 2
            if (retry) {
                last = result
                continue
            }
            return result
        }
        return last
    }

    suspend fun oneRtt(host: String, timeoutSec: Int = 1, payloadBytes: Int = 64): Double? {
        return try {
            val attempts = commands(host, count = 1, timeoutSec = timeoutSec, ttl = null, payloadBytes = payloadBytes, numeric = true)
            for ((index, command) in attempts.withIndex()) {
                val outcome = collect(host, command, 1, timeoutSec) {}
                if (outcome.usage && outcome.samples.isEmpty() && index < attempts.lastIndex) continue
                return outcome.samples.minOrNull()
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pingMany(
        host: String,
        count: Int,
        timeoutSec: Int,
        payloadBytes: Int = 64,
        onUpdate: (PingSummary) -> Unit,
    ): PingSummary {
        var lastError = "ping не выполнился"
        val attempts = commands(host, count = count, timeoutSec = timeoutSec, ttl = null, payloadBytes = payloadBytes, numeric = true)
        for ((index, command) in attempts.withIndex()) {
            coroutineContext.ensureActive()
            val outcome = collect(host, command, count, timeoutSec, onUpdate)
            if (outcome.usage && outcome.samples.isEmpty() && index < attempts.lastIndex) {
                lastError = outcome.fatal ?: lastError
                continue
            }
            if (outcome.fatal != null && outcome.samples.isEmpty() && outcome.stats == null) {
                throw ProbeException(outcome.fatal)
            }
            val transmitted = outcome.stats?.transmitted ?: maxOf(count, outcome.samples.size)
            val received = outcome.stats?.received ?: outcome.samples.size
            return PingSummary.from(host, transmitted, received, outcome.samples).also(onUpdate)
        }
        throw ProbeException(lastError)
    }

    private suspend fun probeOnce(command: List<String>, timeoutSec: Int): Probe {
        var event: LineEvent? = null
        var elapsedAtEvent: Double? = null
        val output = runner.run(command, (timeoutSec + 2) * 1000L) { line, elapsedMs ->
            val parsed = PingParser.parseLine(line)
            if (parsed != null) {
                event = parsed
                elapsedAtEvent = elapsedMs
                true
            } else {
                false
            }
        }
        when (val parsed = event) {
            is LineEvent.Echo -> return Probe.Echo(parsed.address, parsed.rttMs ?: corrected(elapsedAtEvent))
            is LineEvent.Exceeded -> return Probe.Transit(parsed.address, parsed.rttMs ?: corrected(elapsedAtEvent))
            is LineEvent.Unreachable -> return Probe.Unreachable(parsed.address, parsed.reason, parsed.rttMs ?: corrected(elapsedAtEvent))
            is LineEvent.Fatal -> return Probe.Failure(parsed.message, parsed.usage)
            null -> Unit
        }
        if (PingParser.isUsageError(output.text)) {
            return Probe.Failure(PingParser.fatalMessage(output.text) ?: "Утилита ping не приняла аргумент", usage = true)
        }
        val fatal = PingParser.fatalMessage(output.text)
        if (fatal != null) return Probe.Failure(fatal, usage = false)
        return Probe.Timeout
    }

    private suspend fun collect(
        host: String,
        command: List<String>,
        count: Int,
        timeoutSec: Int,
        onUpdate: (PingSummary) -> Unit,
    ): CollectOutcome {
        val samples = mutableListOf<Double>()
        var fatal: String? = null
        var usage = false
        var sent = 0
        var zeroBased = false
        val timeoutMs = count * 1_500L + timeoutSec * 1_000L + 5_000L
        val output = runner.run(command, timeoutMs) { line, _ ->
            fun noteSent() {
                val seq = PingParser.sequence(line) ?: return
                if (seq == 0) zeroBased = true
                val soFar = if (zeroBased) seq + 1 else seq
                if (soFar > sent) sent = soFar
            }
            when (val parsed = PingParser.parseLine(line)) {
                is LineEvent.Echo -> {
                    parsed.rttMs?.let { samples += it }
                    noteSent()
                    if (sent < samples.size) sent = samples.size
                    onUpdate(PingSummary.from(host, sent, samples.size, samples))
                    false
                }
                is LineEvent.Fatal -> {
                    fatal = parsed.message
                    usage = parsed.usage
                    true
                }
                else -> {
                    if (PingParser.isTimeout(line)) {
                        noteSent()
                        onUpdate(PingSummary.from(host, sent.coerceAtLeast(samples.size), samples.size, samples))
                    }
                    false
                }
            }
        }
        val stats = PingParser.parseStats(output.text)
        if (fatal == null) {
            val message = PingParser.fatalMessage(output.text)
            if (message != null && samples.isEmpty() && stats == null) {
                fatal = message
                usage = PingParser.isUsageError(output.text)
            }
        }
        return CollectOutcome(samples, stats, fatal, usage)
    }

    private fun corrected(elapsedMs: Double?): Double {
        if (elapsedMs == null) return 0.1
        return (elapsedMs - overheadMs).coerceAtLeast(0.1)
    }

    private fun commands(
        host: String,
        count: Int,
        timeoutSec: Int,
        ttl: Int?,
        payloadBytes: Int,
        numeric: Boolean,
    ): List<List<String>> {
        val family = if (host.contains(':')) "-6" else "-4"
        val full = mutableListOf(pingBinary, family)
        if (numeric) full += "-n"
        full += listOf("-c", count.toString(), "-W", timeoutSec.toString())
        if (count > 1) full += listOf("-i", "1")
        if (count == 1) full += listOf("-w", (timeoutSec + 1).toString())
        if (payloadBytes > 0) full += listOf("-s", payloadBytes.toString())
        if (ttl != null) full += listOf("-t", ttl.toString())
        full += host

        val simple = mutableListOf(pingBinary, "-c", count.toString(), "-W", timeoutSec.toString())
        if (ttl != null) simple += listOf("-t", ttl.toString())
        simple += host

        val minimal = mutableListOf(pingBinary, "-c", count.toString())
        if (ttl != null) minimal += listOf("-t", ttl.toString())
        minimal += host

        return listOf(full, simple, minimal)
    }

    private fun localCommand(): List<String> {
        return listOf(pingBinary, "-4", "-n", "-c", "1", "-W", "1", "127.0.0.1")
    }

    private data class CollectOutcome(
        val samples: List<Double>,
        val stats: PingStats?,
        val fatal: String?,
        val usage: Boolean,
    )

    companion object {
        private const val CALIBRATION_TIMEOUT_MS = 3_000L
        private const val MAX_OVERHEAD_MS = 120.0

        fun defaultBinary(): String {
            val system = File("/system/bin/ping")
            return if (system.canExecute()) system.absolutePath else "ping"
        }
    }
}
