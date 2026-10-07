package dev.netmtr.app.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPlanTest {
    @Test
    fun scansRestOfSlash24ExceptSelf() {
        val targets = LanPlan.targets("192.168.1.40", 24)
        assertEquals(253, targets.size)
        assertFalse("192.168.1.40" in targets)
        assertFalse("192.168.1.0" in targets)
        assertFalse("192.168.1.255" in targets)
        assertTrue("192.168.1.1" in targets)
        assertTrue("192.168.1.254" in targets)
    }

    @Test
    fun wideNetworkIsCutToLocalSlash24() {
        val targets = LanPlan.targets("10.1.2.5", 16)
        assertTrue(LanPlan.narrowedTo24(16))
        assertEquals(253, targets.size)
        assertTrue(targets.all { it.startsWith("10.1.2.") })
    }

    @Test
    fun dropsProxyArpCopiesOfTheGateway() {
        val gatewayMac = "aa:bb:cc:dd:ee:ff"
        val devices = listOf(
            LanDevice("192.168.1.1", gatewayMac, null, 1.0, gateway = true),
            LanDevice("192.168.1.2", gatewayMac, null, 1.2, gateway = false),
            LanDevice("192.168.1.3", gatewayMac, null, 1.1, gateway = false),
            LanDevice("192.168.1.20", "11:22:33:44:55:66", null, 4.0, gateway = false),
            LanDevice("192.168.1.30", null, null, 3.0, gateway = false),
        )
        val kept = LanPlan.keepRealDevices(devices, "192.168.1.1").map { it.address }
        assertEquals(listOf("192.168.1.1", "192.168.1.20", "192.168.1.30"), kept)
    }

    @Test
    fun readsCompleteArpEntries() {
        val table = LanPlan.parseArp(
            """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0
            192.168.1.9      0x1         0x0         00:00:00:00:00:00     *        wlan0
            10.0.0.2         0x1         0x2         11:22:33:44:55:66     *        wlan0
            """.trimIndent(),
        )
        assertEquals("aa:bb:cc:dd:ee:ff", table["192.168.1.1"])
        assertEquals("11:22:33:44:55:66", table["10.0.0.2"])
        assertFalse(table.containsKey("192.168.1.9"))
    }
}
