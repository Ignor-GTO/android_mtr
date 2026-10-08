package dev.netmtr.desktop

import java.util.concurrent.TimeUnit

data class DeskPing(
    val sent: Int,
    val received: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
)

data class DeskHop(
    val hop: Int,
    val address: String,
    val sent: Int,
    val received: Int,
    val bestMs: Double?,
    val avgMs: Double?,
    val worstMs: Double?,
    val lastMs: Double?,
)

sealed interface DeskReply {
    data class Echo(val address: String, val rttMs: Double) : DeskReply
    data class Transit(val address: String, val rttMs: Double?) : DeskReply
    data object Timeout : DeskReply
}

object WinProbe {
    fun ping(host: String, count: Int, timeoutMs: Int): DeskPing {
        val samples = mutableListOf<Double>()
        repeat(count.coerceIn(1, 50)) {
            when (val reply = once(host, ttl = null, timeoutMs)) {
                is DeskReply.Echo -> samples += reply.rttMs
                is DeskReply.Transit -> reply.rttMs?.let { samples += it }
                DeskReply.Timeout -> Unit
            }
        }
        return DeskPing(
            sent = count.coerceIn(1, 50),
            received = samples.size,
            minMs = samples.minOrNull(),
            avgMs = samples.takeIf { it.isNotEmpty() }?.average(),
            maxMs = samples.maxOrNull(),
        )
    }

    fun mtr(
        host: String,
        cycles: Int,
        maxHops: Int,
        timeoutMs: Int,
        onUpdate: (List<DeskHop>) -> Unit,
    ): List<DeskHop> {
        val slots = linkedMapOf<Int, Slot>()
        var limit = maxHops.coerceIn(1, 30)
        val rounds = cycles.coerceIn(1, 50)
        for (cycle in 1..rounds) {
            if (cycle == 1) {
                limit = discover(host, limit, timeoutMs, slots, onUpdate)
            } else {
                for (ttl in 1..limit) {
                    hear(slots, ttl, once(host, ttl, timeoutMs))
                }
                onUpdate(slots.rows())
            }
        }
        return slots.rows()
    }

    private fun discover(
        host: String,
        maxHops: Int,
        timeoutMs: Int,
        slots: MutableMap<Int, Slot>,
        onUpdate: (List<DeskHop>) -> Unit,
    ): Int {
        var stars = 0
        for (ttl in 1..maxHops) {
            val reply = once(host, ttl, timeoutMs)
            hear(slots, ttl, reply)
            onUpdate(slots.rows())
            when (reply) {
                is DeskReply.Echo -> return ttl
                DeskReply.Timeout -> {
                    stars++
                    if (stars >= 6 && ttl >= 6) return ttl
                }
                is DeskReply.Transit -> stars = 0
            }
        }
        return maxHops
    }

    private fun hear(slots: MutableMap<Int, Slot>, ttl: Int, reply: DeskReply) {
        val slot = slots.getOrPut(ttl) { Slot(ttl) }
        slot.sent++
        when (reply) {
            is DeskReply.Echo -> slot.hear(reply.address, reply.rttMs)
            is DeskReply.Transit -> slot.hear(reply.address, reply.rttMs)
            DeskReply.Timeout -> Unit
        }
    }

    private fun once(host: String, ttl: Int?, timeoutMs: Int): DeskReply {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        calibrate()
        val command = mutableListOf("ping", "-4", "-n", "1", "-w", timeoutMs.coerceIn(200, 5000).toString())
        if (ttl != null) command += listOf("-i", ttl.toString())
        command += host
        val (text, elapsed) = runTimed(command, timeoutMs + 1500L)
        val reply = parse(text)
        if (reply is DeskReply.Transit && reply.rttMs == null) {
            return reply.copy(rttMs = (elapsed - overheadMs).coerceAtLeast(0.2))
        }
        return reply
    }

    private fun calibrate() {
        if (calibrated) return
        synchronized(this) {
            if (calibrated) return
            val (text, elapsed) = runTimed(listOf("ping", "-4", "-n", "1", "-w", "1000", "127.0.0.1"), 2000L)
            val reported = timeRe.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            overheadMs = (elapsed - reported).coerceIn(0.0, 120.0)
            calibrated = true
        }
    }

    private fun parse(text: String): DeskReply {
        val lower = text.lowercase()
        val timedOut = "timed out" in lower || "интервал ожидания" in lower || "превышен интервал" in lower
        val expired = "ttl expired" in lower || "срок жизни" in lower || "время жизни" in lower
        val from = fromRe.find(text)
        val address = from?.groupValues?.get(1)?.trim().orEmpty()
        val time = timeRe.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return when {
            expired && address.isNotBlank() -> DeskReply.Transit(address, time)
            address.isNotBlank() && time != null -> DeskReply.Echo(address, time)
            timedOut || text.isBlank() -> DeskReply.Timeout
            else -> DeskReply.Timeout
        }
    }

    private fun runTimed(command: List<String>, timeoutMs: Long): Pair<String, Double> {
        return runCatching {
            val started = System.nanoTime()
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!finished) process.destroy()
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            text to elapsed
        }.getOrDefault("" to timeoutMs.toDouble())
    }

    private var overheadMs: Double = 0.0
    private var calibrated: Boolean = false

    private class Slot(val hop: Int) {
        var address: String = "*"
        var sent: Int = 0
        var received: Int = 0
        val samples = mutableListOf<Double>()

        fun hear(from: String, rttMs: Double?) {
            received++
            if (from.isNotBlank()) address = from
            if (rttMs != null) samples += rttMs
        }

        fun row(): DeskHop {
            return DeskHop(
                hop = hop,
                address = address,
                sent = sent,
                received = received,
                bestMs = samples.minOrNull(),
                avgMs = samples.takeIf { it.isNotEmpty() }?.average(),
                worstMs = samples.maxOrNull(),
                lastMs = samples.lastOrNull(),
            )
        }
    }

    private fun Map<Int, Slot>.rows(): List<DeskHop> {
        return values.filter { it.sent > 0 }.sortedBy { it.hop }.map { it.row() }
    }

    private val fromRe = Regex("""(?i)(?:reply from|ответ от)\s+([0-9.]+)""")
    private val timeRe = Regex("""(?i)(?:time|время)\s*[=<]\s*([0-9]+)""")
}
