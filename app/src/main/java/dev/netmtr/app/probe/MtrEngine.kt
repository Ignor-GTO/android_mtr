package dev.netmtr.app.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class MtrProgress(
    val cycle: Int,
    val cycles: Int,
    val ttl: Int,
    val limit: Int,
    val hops: List<HopRow>,
)

class MtrEngine(private val ping: PingClient) {
    suspend fun run(
        host: String,
        cycles: Int,
        maxHops: Int,
        timeoutSec: Int,
        intervalMs: Long = 1_000,
        payloadBytes: Int = 64,
        resolveNames: Boolean = false,
        maxHosts: Int = 60,
        onUpdate: (MtrProgress) -> Unit,
    ): List<HopRow> {
        ping.calibrate()
        val names = HostCache(maxHosts.coerceAtLeast(1))
        val slots = linkedMapOf<Int, Slot>()
        var pathLimit: Int? = null
        for (cycle in 1..cycles) {
            coroutineContext.ensureActive()
            val cycleStarted = System.nanoTime()
            val limit = pathLimit ?: maxHops
            onUpdate(MtrProgress(cycle, cycles, 1, limit, slots.rows()))
            if (pathLimit == null) {
                pathLimit = discover(host, limit, timeoutSec, payloadBytes, resolveNames, names, slots) { ttl ->
                    onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                }
                val spent = (System.nanoTime() - cycleStarted) / 1_000_000
                val wait = intervalMs - spent
                if (wait > 0) delay(wait)
            } else {
                val probes = probeCycle(host, limit, timeoutSec, intervalMs, payloadBytes, resolveNames)
                for ((ttl, probe) in probes) {
                    hear(slots, names, ttl, probe, resolveNames)
                }
                onUpdate(MtrProgress(cycle, cycles, limit, limit, slots.rows()))
            }
        }
        return slots.rows()
    }

    private suspend fun probeCycle(
        host: String,
        limit: Int,
        timeoutSec: Int,
        intervalMs: Long,
        payloadBytes: Int,
        resolveNames: Boolean,
    ): List<Pair<Int, Probe>> = supervisorScope {
        val parent = coroutineContext.job
        val open = AtomicBoolean(true)
        val jobs = (1..limit).map { ttl ->
            ttl to async {
                try {
                    ping.probe(host, ttl, timeoutSec, payloadBytes = payloadBytes, numeric = !resolveNames)
                } catch (cancelled: CancellationException) {
                    if (parent.isActive && !open.get()) Probe.Timeout else throw cancelled
                }
            }
        }
        delay(intervalMs.coerceAtLeast(1))
        open.set(false)
        jobs.map { (ttl, deferred) ->
            if (!deferred.isCompleted) deferred.cancel()
            val probe = runCatching { deferred.await() }.getOrElse { error ->
                if (error is CancellationException) throw error
                Probe.Failure(error.message ?: "ошибка пинга", usage = false)
            }
            ttl to probe
        }
    }

    private suspend fun discover(
        host: String,
        limit: Int,
        timeoutSec: Int,
        payloadBytes: Int,
        resolveNames: Boolean,
        names: HostCache,
        slots: MutableMap<Int, Slot>,
        onHop: (Int) -> Unit,
    ): Int {
        var trailingStars = 0
        var lastTtl = 1
        for (ttl in 1..limit) {
            coroutineContext.ensureActive()
            lastTtl = ttl
            onHop(ttl)
            val probe = ping.probe(host, ttl, timeoutSec, payloadBytes = payloadBytes, numeric = !resolveNames)
            when (hear(slots, names, ttl, probe, resolveNames)) {
                Heard.Destination, Heard.Unreachable -> return ttl
                Heard.Timeout -> {
                    trailingStars++
                    if (trailingStars >= STAR_LIMIT && ttl >= STAR_LIMIT) return ttl
                }
                Heard.Transit -> trailingStars = 0
            }
            onHop(ttl)
        }
        return lastTtl
    }

    private suspend fun hear(
        slots: MutableMap<Int, Slot>,
        names: HostCache,
        ttl: Int,
        probe: Probe,
        resolveNames: Boolean,
    ): Heard {
        if (probe is Probe.Failure) throw ProbeException(probe.message)
        val slot = slots.getOrPut(ttl) { Slot(ttl) }
        slot.sent++
        return when (probe) {
            is Probe.Echo -> {
                slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = true)
                Heard.Destination
            }
            is Probe.Transit -> {
                slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = false)
                Heard.Transit
            }
            is Probe.Unreachable -> {
                slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = false)
                Heard.Unreachable
            }
            Probe.Timeout -> Heard.Timeout
            is Probe.Failure -> Heard.Timeout
        }
    }

    private class Slot(val hop: Int) {
        var address: String = "*"
        val seen = linkedSetOf<String>()
        var sent: Int = 0
        var received: Int = 0
        val samples = mutableListOf<Double>()
        var reachedTarget: Boolean = false

        fun hear(from: String, rttMs: Double?, reachedTarget: Boolean) {
            received++
            if (from.isNotBlank()) {
                address = from
                seen += from
            }
            if (rttMs != null) samples += rttMs
            if (reachedTarget) this.reachedTarget = true
        }

        fun row(): HopRow {
            return HopRow(
                hop = hop,
                address = address,
                addresses = seen.toList(),
                sent = sent,
                received = received,
                lossPercent = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent,
                bestMs = samples.minOrNull(),
                avgMs = samples.takeIf { it.isNotEmpty() }?.average(),
                worstMs = samples.maxOrNull(),
                lastMs = samples.lastOrNull(),
                jitterMs = Rtt.jitter(samples),
                reachedTarget = reachedTarget,
            )
        }
    }

    private fun Map<Int, Slot>.rows(): List<HopRow> {
        return values.filter { it.sent > 0 }.sortedBy { it.hop }.map { it.row() }
    }

    private class HostCache(private val limit: Int) {
        private val values = LinkedHashMap<String, String>(16, 0.75f, true)

        suspend fun label(address: String, resolve: Boolean): String {
            if (!resolve || address.isBlank() || address == "*") return address
            synchronized(values) { values[address] }?.let { return it }
            val name = withContext(Dispatchers.IO) {
                try {
                    val host = InetAddress.getByName(address).canonicalHostName
                    if (host.isBlank() || host.equals(address, ignoreCase = true)) address else host
                } catch (_: Exception) {
                    address
                }
            }
            synchronized(values) {
                values[address] = name
                while (values.size > limit) {
                    val eldest = values.entries.first().key
                    values.remove(eldest)
                }
            }
            return name
        }
    }

    private sealed interface Heard {
        data object Destination : Heard
        data object Unreachable : Heard
        data object Timeout : Heard
        data object Transit : Heard
    }

    companion object {
        private const val STAR_LIMIT = 6
    }
}
