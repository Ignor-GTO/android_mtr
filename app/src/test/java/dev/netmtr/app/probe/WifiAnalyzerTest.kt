package dev.netmtr.app.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAnalyzerTest {
    @Test
    fun marksOwnRouterAndCrowdedOverlappingChannel() {
        val points = listOf(
            ap("aa:aa:aa:aa:aa:01", 2437, -40),
            ap("bb:bb:bb:bb:bb:02", 2437, -55),
            ap("cc:cc:cc:cc:cc:03", 2442, -60),
            ap("dd:dd:dd:dd:dd:04", 5180, -48, width = 80, center = 5210),
        )
        val rows = WifiAnalyzer.analyze(points, ownFrequencyMhz = 2437, ownBssid = "aa:aa:aa:aa:aa:01")
        val channel6 = rows.first { it.channel == 6 && it.band == "2.4 ГГц" }
        val channel1 = rows.first { it.channel == 1 }
        val five = rows.first { it.frequencyMhz == 5180 }
        assertTrue(channel6.own)
        assertEquals("нагружена", channel6.load)
        assertEquals("свободна", channel1.load)
        assertEquals("5 ГГц", five.band)
        assertTrue(five.neighbors >= 1)
    }

    @Test
    fun emptyBandIsFree() {
        val rows = WifiAnalyzer.analyze(emptyList(), ownFrequencyMhz = null, ownBssid = null)
        assertEquals(13, rows.size)
        assertTrue(rows.all { it.load == "свободна" })
    }

    private fun ap(
        bssid: String,
        frequency: Int,
        level: Int,
        width: Int = 20,
        center: Int = frequency,
    ) = VisibleAp("net", bssid, frequency, level, width, center)
}
