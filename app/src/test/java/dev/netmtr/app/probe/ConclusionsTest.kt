package dev.netmtr.app.probe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ConclusionsTest {
    @Test
    fun gatewayLossPointsAtLocalNetwork() {
        val notes = Conclusions.build(
            sample(
                gatewayPing = ping("192.168.1.1", sent = 10, received = 7, samples = listOf(2.0, 3.0, 4.0)),
            ),
        )
        assertTrue(notes.any { it.contains("шлюза") && it.contains("локальн") })
    }

    @Test
    fun silentMiddleHopIsNotTreatedAsAnOutage() {
        val notes = Conclusions.build(
            sample(
                hops = listOf(
                    hop(1, "192.168.1.1", sent = 10, received = 10, avg = 1.0),
                    hop(2, "*", sent = 10, received = 0),
                    hop(3, "8.8.8.8", sent = 10, received = 10, avg = 20.0, target = true),
                ),
            ),
        )
        assertTrue(notes.any { it.contains("фильтр") })
    }

    @Test
    fun partialLossAtTheEndIsFlagged() {
        val notes = Conclusions.build(
            sample(
                hops = listOf(
                    hop(1, "192.168.1.1", sent = 10, received = 10, avg = 1.0),
                    hop(2, "8.8.8.8", sent = 10, received = 6, avg = 30.0, target = true),
                ),
            ),
        )
        assertTrue(notes.any { it.contains("Прыжок 2") && it.contains("администратору") })
    }

    private fun sample(
        gatewayPing: PingSummary? = null,
        hops: List<HopRow> = emptyList(),
    ): TestResult {
        val now = ZonedDateTime.parse("2026-10-02T12:00:00+05:00")
        return TestResult(
            mode = TestMode.FULL,
            startedAt = now,
            finishedAt = now,
            host = "8.8.8.8",
            appVersion = "1.0.0",
            network = null,
            publicIp = null,
            colo = null,
            location = null,
            dns = emptyList(),
            http = null,
            tcp = null,
            gatewayPing = gatewayPing,
            targetPing = null,
            hops = hops,
            cycles = 10,
            maxHops = 20,
            timeoutSec = 2,
            overheadMs = 15.0,
            overheadApplied = true,
            stopped = false,
            error = null,
        )
    }

    private fun ping(target: String, sent: Int, received: Int, samples: List<Double>): PingSummary {
        return PingSummary.from(target, sent, received, samples)
    }

    private fun hop(
        number: Int,
        address: String,
        sent: Int,
        received: Int,
        avg: Double? = null,
        target: Boolean = false,
    ): HopRow {
        val samples = if (avg == null) emptyList() else List(received.coerceAtLeast(1)) { avg }
        return HopRow(
            hop = number,
            address = address,
            addresses = if (address == "*") emptyList() else listOf(address),
            sent = sent,
            received = received,
            lossPercent = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent,
            bestMs = avg,
            avgMs = avg,
            worstMs = avg,
            lastMs = avg,
            jitterMs = null,
            reachedTarget = target,
        )
    }
}
