package dev.netmtr.app.probe

import kotlinx.coroutines.ensureActive
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
        onUpdate: (MtrProgress) -> Unit,
    ): List<HopRow> {
        ping.calibrate()
        val slots = linkedMapOf<Int, Slot>()
        var destinationTtl: Int? = null
        for (cycle in 1..cycles) {
            coroutineContext.ensureActive()
            val limit = destinationTtl ?: maxHops
            var trailingStars = 0
            for (ttl in 1..limit) {
                coroutineContext.ensureActive()
                onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                val probe = ping.probe(host, ttl, timeoutSec)
                if (probe is Probe.Failure) throw ProbeException(probe.message)
                val slot = slots.getOrPut(ttl) { Slot(ttl) }
                slot.sent++
                when (probe) {
                    is Probe.Echo -> {
                        slot.hear(probe.address, probe.rttMs, reachedTarget = true)
                        destinationTtl = ttl
                        trailingStars = 0
                        onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                        break
                    }
                    is Probe.Transit -> {
                        slot.hear(probe.address, probe.rttMs, reachedTarget = false)
                        trailingStars = 0
                    }
                    is Probe.Unreachable -> {
                        slot.hear(probe.address, probe.rttMs, reachedTarget = false)
                        onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                        break
                    }
                    Probe.Timeout -> {
                        trailingStars++
                        if (destinationTtl == null && trailingStars >= STAR_LIMIT && ttl >= STAR_LIMIT) {
                            onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
                            break
                        }
                    }
                    is Probe.Failure -> Unit
                }
                onUpdate(MtrProgress(cycle, cycles, ttl, limit, slots.rows()))
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

    companion object {
        private const val STAR_LIMIT = 6
    }
}
