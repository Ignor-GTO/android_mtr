package dev.netmtr.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.net.Inet4Address
import java.net.InetAddress

object WinIcmp {
    fun probe(host: String, ttl: Int?, timeoutMs: Int): DeskReply? {
        val api = library ?: return null
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address
            ?: return DeskReply.Timeout
        val handle = api.IcmpCreateFile()
        if (!valid(handle)) return null
        try {
            val options = Memory(16)
            options.clear()
            options.setByte(0, (ttl ?: 128).coerceIn(1, 255).toByte())
            val payload = ByteArray(32)
            val reply = Memory(256)
            reply.clear()
            val destination = pack(address.address)
            val started = System.nanoTime()
            val count = api.IcmpSendEcho(
                handle,
                destination,
                payload,
                payload.size.toShort(),
                options,
                reply,
                256,
                timeoutMs.coerceIn(200, 5000),
            )
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            if (count <= 0) return DeskReply.Timeout
            val from = unpack(reply.getInt(0))
            val status = reply.getInt(4)
            val reported = reply.getInt(8)
            if (from == "0.0.0.0") return DeskReply.Timeout
            val rtt = when {
                reported > 0 -> reported.toDouble()
                else -> elapsed.coerceAtLeast(0.1)
            }
            return when (status) {
                IP_SUCCESS -> DeskReply.Echo(from, rtt)
                IP_TTL_EXPIRED_TRANSIT, IP_TTL_EXPIRED_REASSEM -> DeskReply.Transit(from, rtt)
                IP_DEST_NET_UNREACHABLE, IP_DEST_HOST_UNREACHABLE -> DeskReply.Transit(from, rtt)
                else -> DeskReply.Timeout
            }
        } catch (stopped: InterruptedException) {
            throw stopped
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { api.IcmpCloseHandle(handle) }
        }
    }

    private fun valid(handle: Pointer?): Boolean {
        if (handle == null || handle == Pointer.NULL) return false
        val value = Pointer.nativeValue(handle)
        return value != 0L && value != -1L
    }

    private fun pack(bytes: ByteArray): Int {
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }

    private fun unpack(value: Int): String {
        return "${value and 0xff}.${(value shr 8) and 0xff}.${(value shr 16) and 0xff}.${(value shr 24) and 0xff}"
    }

    private val library: IcmpLibrary? = runCatching {
        Native.load("iphlpapi", IcmpLibrary::class.java)
    }.getOrNull()

    private const val IP_SUCCESS = 0
    private const val IP_DEST_NET_UNREACHABLE = 11002
    private const val IP_DEST_HOST_UNREACHABLE = 11003
    private const val IP_TTL_EXPIRED_TRANSIT = 11013
    private const val IP_TTL_EXPIRED_REASSEM = 11014
}

private interface IcmpLibrary : Library {
    fun IcmpCreateFile(): Pointer
    fun IcmpCloseHandle(handle: Pointer): Int
    fun IcmpSendEcho(
        icmpHandle: Pointer,
        destinationAddress: Int,
        requestData: ByteArray,
        requestSize: Short,
        requestOptions: Pointer?,
        replyBuffer: Pointer,
        replySize: Int,
        timeout: Int,
    ): Int
}
