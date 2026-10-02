package dev.netmtr.app.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class TelegramReportTest {
    @Test
    fun tablesUseTelegramRichMarkup() {
        val html = TelegramReport.build(
            result(
                hops = listOf(hop(1, "192.168.1.1", sent = 10, received = 10)),
                speed = SpeedResult(
                    downloadMbps = 42.5,
                    downloadBytes = 8_000_000,
                    downloadMs = 1500,
                    uploadMbps = 11.0,
                    uploadBytes = 2_000_000,
                    uploadMs = 1400,
                    downloadError = null,
                    uploadError = null,
                ),
            ),
        )
        assertTrue(html.contains("<table bordered striped>"))
        assertTrue(html.contains("<caption>MTR до 8.8.8.8"))
        assertTrue(html.contains("<th>Прыжок</th>"))
        assertTrue(html.contains("<th align=\"right\">Потери</th>"))
        assertTrue(html.contains("<td align=\"right\">0.0%</td>"))
        assertTrue(html.contains("<caption>Скорость</caption>"))
        assertTrue(html.contains("42.5 Мбит/с"))
    }

    @Test
    fun escapesHtmlInCells() {
        val html = TelegramReport.build(result(hops = listOf(hop(2, "a<b>&c", sent = 4, received = 1))))
        assertTrue(html.contains("a&lt;b&gt;&amp;c"))
        assertFalse(html.contains("a<b>"))
    }

    private fun result(hops: List<HopRow>, speed: SpeedResult? = null): TestResult {
        val now = ZonedDateTime.parse("2026-10-02T12:00:00+05:00")
        return TestResult(
            mode = TestMode.FULL,
            startedAt = now,
            finishedAt = now,
            host = "8.8.8.8",
            appVersion = "1.1.0",
            network = null,
            publicIp = "203.0.113.5",
            colo = "FRA",
            location = "DE",
            dns = emptyList(),
            http = null,
            tcp = null,
            gatewayPing = null,
            targetPing = null,
            hops = hops,
            cycles = 10,
            maxHops = 20,
            timeoutSec = 2,
            overheadMs = 12.0,
            overheadApplied = true,
            stopped = false,
            error = null,
            speed = speed,
        )
    }

    private fun hop(number: Int, address: String, sent: Int, received: Int): HopRow {
        return HopRow(
            hop = number,
            address = address,
            addresses = listOf(address),
            sent = sent,
            received = received,
            lossPercent = (sent - received) * 100.0 / sent,
            bestMs = 1.0,
            avgMs = 2.0,
            worstMs = 3.0,
            lastMs = 2.0,
            jitterMs = 0.4,
            reachedTarget = false,
        )
    }
}
