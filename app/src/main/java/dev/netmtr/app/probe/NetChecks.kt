package dev.netmtr.app.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object NetChecks {
    suspend fun dns(name: String): DnsResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val addresses = InetAddress.getAllByName(name)
                .mapNotNull { it.hostAddress }
                .distinct()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DnsResult(name, elapsed, addresses, null)
        } catch (error: Exception) {
            DnsResult(name, null, emptyList(), error.message ?: "ошибка DNS")
        }
    }

    suspend fun httpGenerate204(): HttpResult = withContext(Dispatchers.IO) {
        val url = URL(GENERATE_204)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NetMTR/1.0")
        }
        val started = System.nanoTime()
        try {
            connection.connect()
            val code = connection.responseCode
            val elapsed = (System.nanoTime() - started) / 1_000_000
            HttpResult(GENERATE_204, code, elapsed, null)
        } catch (error: Exception) {
            HttpResult(GENERATE_204, null, null, error.message ?: "нет ответа")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun tcp(host: String, port: Int = 443, timeoutMs: Int = 8_000): TcpResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            TcpResult(host, port, elapsed, null)
        } catch (error: Exception) {
            TcpResult(host, port, null, error.message ?: "соединение не открылось")
        }
    }

    suspend fun edge(): EdgeInfo = withContext(Dispatchers.IO) {
        val trace = runCatching { cloudflareTrace() }.getOrNull()
        if (trace?.ip != null) return@withContext trace
        val ip = runCatching { publicIpFrom("https://api.ipify.org") }.getOrNull()
        EdgeInfo(ip, trace?.colo, trace?.location)
    }

    private fun cloudflareTrace(): EdgeInfo {
        val text = getText("https://1.1.1.1/cdn-cgi/trace")
        val map = text.lineSequence().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
        }.toMap()
        return EdgeInfo(map["ip"], map["colo"], map["loc"])
    }

    private fun publicIpFrom(url: String): String? {
        val text = getText(url).trim()
        if (text.isEmpty() || text.length > 80 || text.any { it.isWhitespace() }) return null
        return text
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 6_000
            readTimeout = 6_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "NetMTR/1.0")
        }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val GENERATE_204 = "https://www.google.com/generate_204"
}
