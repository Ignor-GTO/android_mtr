package dev.netmtr.app.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingParserTest {
    @Test
    fun parsesEchoReply() {
        val event = PingParser.parseLine("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms")
        val echo = event as LineEvent.Echo
        assertEquals("8.8.8.8", echo.address)
        assertEquals(12.3, echo.rttMs!!, 0.001)
    }

    @Test
    fun parsesBusyBoxSeq() {
        val event = PingParser.parseLine("64 bytes from 1.1.1.1: seq=0 ttl=57 time=11.2 ms")
        val echo = event as LineEvent.Echo
        assertEquals("1.1.1.1", echo.address)
        assertEquals(11.2, echo.rttMs!!, 0.001)
    }

    @Test
    fun parsesIpv6Echo() {
        val event = PingParser.parseLine(
            "64 bytes from 2001:4860:4860::8888: icmp_seq=1 ttl=118 time=21.5 ms",
        )
        val echo = event as LineEvent.Echo
        assertEquals("2001:4860:4860::8888", echo.address)
        assertEquals(21.5, echo.rttMs!!, 0.001)
    }

    @Test
    fun parsesTtlExceeded() {
        val event = PingParser.parseLine("From 192.168.1.1: icmp_seq=1 Time to live exceeded")
        val hop = event as LineEvent.Exceeded
        assertEquals("192.168.1.1", hop.address)
        assertNull(hop.rttMs)
    }

    @Test
    fun parsesIpv6TtlExceededWithoutExtraColonSpace() {
        val event = PingParser.parseLine("From 2001:db8::1 icmp_seq=1 Time to live exceeded")
        val hop = event as LineEvent.Exceeded
        assertEquals("2001:db8::1", hop.address)
    }

    @Test
    fun parsesUnreachable() {
        val event = PingParser.parseLine("From 10.0.0.1 icmp_seq=1 Destination Host Unreachable")
        val hop = event as LineEvent.Unreachable
        assertEquals("10.0.0.1", hop.address)
        assertTrue(hop.reason.contains("Unreachable"))
    }

    @Test
    fun ignoresHeader() {
        assertNull(PingParser.parseLine("PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data."))
    }

    @Test
    fun parsesLossStatisticsWithErrors() {
        val stats = PingParser.parseStats(
            "1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms",
        )
        assertEquals(1, stats?.transmitted)
        assertEquals(0, stats?.received)
    }

    @Test
    fun parsesBusyBoxStatistics() {
        val stats = PingParser.parseStats("20 packets transmitted, 18 packets received, 10% packet loss")
        assertEquals(20, stats?.transmitted)
        assertEquals(18, stats?.received)
    }

    @Test
    fun explainsUnknownHost() {
        val event = PingParser.parseLine("ping: unknown host example.invalid") as LineEvent.Fatal
        assertTrue(event.message.contains("разрешить"))
        assertTrue(!event.usage)
    }

    @Test
    fun marksBadOptionAsUsage() {
        val event = PingParser.parseLine("ping: bad option --zzz") as LineEvent.Fatal
        assertTrue(event.usage)
    }
}
