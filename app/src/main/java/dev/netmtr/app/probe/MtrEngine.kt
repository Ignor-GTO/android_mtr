package dev.netmtr.app.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
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
        var destinationTtl: Int? = null
        for (cycle in 1..cycles) {
            coroutineContext.ensureActive()
            val limit = destinationTtl ?: maxHops
            var trailingStars = 0
            for (ttl in 1..limit) {
                coroutineContext.ensureActive()
                onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                val started = System.nanoTime()
                val probe = ping.probe(host, ttl, timeoutSec, payloadBytes = payloadBytes, numeric = !resolveNames)
                if (probe is Probe.Failure) throw ProbeException(probe.message)
                val slot = slots.getOrPut(ttl) { Slot(ttl) }
                slot.sent++
                var stopHop = false
                when (probe) {
                    is Probe.Echo -> {
                        slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = true)
                        destinationTtl = ttl
                        trailingStars = 0
                        stopHop = true
                    }
                    is Probe.Transit -> {
                        slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = false)
                        trailingStars = 0
                    }
                    is Probe.Unreachable -> {
                        slot.hear(names.label(probe.address, resolveNames), probe.rttMs, reachedTarget = false)
                        stopHop = true
                    }
                    Probe.Timeout -> {
                        trailingStars++
                        if (destinationTtl == null && trailingStars >= STAR_LIMIT && ttl >= STAR_LIMIT) {
                            stopHop = true
                        }
                    }
                    is Probe.Failure -> Unit
                }
                onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                val spent = (System.nanoTime() - started) / 1_000_000
                val wait = intervalMs - spent
                if (wait > 0) delay(wait)
                if (stopHop) break
            }
        }
        return slots.rows()
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

    companion object {
        private const val STAR_LIMIT = 6
    }
}
