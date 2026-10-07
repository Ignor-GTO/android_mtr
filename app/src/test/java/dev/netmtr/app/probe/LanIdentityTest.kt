package dev.netmtr.app.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanIdentityTest {
    @Test
    fun readsIpNeighLines() {
        val table = LanIdentity.parseNeigh(
            """
            192.168.1.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            192.168.1.9 dev wlan0 FAILED
            10.0.0.8 dev wlan0 lladdr 11:22:33:44:55:66 STALE
            """.trimIndent(),
        )
        assertEquals("aa:bb:cc:dd:ee:ff", table["192.168.1.1"])
        assertEquals("11:22:33:44:55:66", table["10.0.0.8"])
        assertEquals(null, table["192.168.1.9"])
    }

    @Test
    fun namesVendorFromOui() {
        assertEquals("Raspberry Pi", LanIdentity.vendor("B8:27:EB:00:11:22"))
        assertEquals("B8:27:EB:00:11:22", LanIdentity.displayMac("b8:27:eb:00:11:22"))
    }

    @Test
    fun ptrQueryTargetsReverseAddress() {
        val query = LanIdentity.ptrQuery("192.168.1.20")
        val text = query.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("20"))
        assertTrue(text.contains("in-addr"))
        assertTrue(text.contains("arpa"))
    }
}
