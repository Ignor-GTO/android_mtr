package dev.netmtr.desktop

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DeskSpeed(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val pingMs: Double?,
    val error: String?,
)

data class DeskDevice(
    val address: String,
    val mac: String?,
    val rttMs: Double?,
)

object WinExtra {
    fun speed(onStatus: (String) -> Unit): DeskSpeed {
        onStatus("Пинг…")
        val idle = WinProbe.ping("1.1.1.1", count = 4, timeoutMs = 1500).minMs
        onStatus("Замер загрузки…")
        val download = readDownload(onStatus)
        onStatus("Замер отдачи…")
        val upload = writeUpload(onStatus)
        val error = download.second ?: upload.second
        return DeskSpeed(download.first, upload.first, idle, error)
    }

    fun wifi(): String {
        val current = command(listOf("netsh", "wlan", "show", "interfaces"))
        val nearby = command(listOf("netsh", "wlan", "show", "networks", "mode=bssid"))
        if (current.isBlank() && nearby.isBlank()) return "Wi‑Fi адаптер не найден."
        return buildString {
            appendLine("Текущее подключение")
            appendLine(trimBlock(current).ifBlank { "Нет активного Wi‑Fi." })
            appendLine()
            appendLine("Сети рядом")
            appendLine(trimBlock(nearby).ifBlank { "Список сетей пуст." })
        }.trim()
    }

    fun lan(onStatus: (String) -> Unit): List<DeskDevice> {
        val local = localIpv4() ?: return emptyList()
        val prefix = local.substringBeforeLast('.')
        val self = local.substringAfterLast('.').toIntOrNull() ?: return emptyList()
        onStatus("Поиск в $prefix.0/24…")
        val alive = linkedMapOf<String, Double>()
        val pool = Executors.newFixedThreadPool(24)
        try {
            (1..254).filter { it != self }.chunked(48).forEach { batch ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val tasks = batch.map { host ->
                    Callable {
                        val address = "$prefix.$host"
                        address to WinProbe.ping(address, count = 1, timeoutMs = 350).minMs
                    }
                }
                pool.invokeAll(tasks, 8, TimeUnit.SECONDS).forEach { future ->
                    val pair = runCatching { future.get() }.getOrNull() ?: return@forEach
                    if (pair.second != null) alive[pair.first] = pair.second!!
                }
                onStatus("Проверено адреса, найдено ${alive.size}…")
            }
        } finally {
            pool.shutdownNow()
        }
        val arp = arpTable()
        return alive.map { (address, rtt) ->
            DeskDevice(address, arp[address], rtt)
        }.sortedBy { it.address.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun readDownload(onStatus: (String) -> Unit): Pair<Double?, String?> {
        val salt = System.currentTimeMillis()
        val urls = listOf(
            "https://speed.cloudflare.com/__down?bytes=20000000&r=$salt",
            "https://proof.ovh.net/files/10Mb.dat",
        )
        var last = "не удалось скачать проверочный файл"
        for (url in urls) {
            try {
                return readFor(url, 8_000L, onStatus) to null
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                last = error.message ?: last
            }
        }
        return null to last
    }

    private fun readFor(url: String, limitMs: Long, onStatus: (String) -> Unit): Double {
        val connection = open(url, "GET")
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("сервер ответил ${connection.responseCode}")
            val buffer = ByteArray(16 * 1024)
            var bytes = 0L
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1)
                    if (elapsed >= limitMs) break
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                }
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1)
            if (bytes < 32 * 1024) throw IllegalStateException("слишком мало данных")
            val mbps = bytes * 8.0 / (ms / 1000.0) / 1_000_000.0
            onStatus("Загрузка: ${"%.1f".format(mbps)} Мбит/с")
            return mbps
        } finally {
            connection.disconnect()
        }
    }

    private fun writeUpload(onStatus: (String) -> Unit): Pair<Double?, String?> {
        return try {
            writeFor("https://speed.cloudflare.com/__up", 6_000L, onStatus) to null
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            null to (error.message ?: "ошибка отдачи")
        }
    }

    private fun writeFor(url: String, limitMs: Long, onStatus: (String) -> Unit): Double {
        val connection = open(url, "POST")
        connection.doOutput = true
        connection.setChunkedStreamingMode(0)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        val buffer = ByteArray(16 * 1024)
        var bytes = 0L
        val started = System.nanoTime()
        try {
            connection.outputStream.use { output ->
                while ((System.nanoTime() - started) / 1_000_000L < limitMs && bytes < 8_000_000L) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    output.write(buffer)
                    bytes += buffer.size
                }
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("сервер ответил ${connection.responseCode}")
            runCatching { connection.inputStream.use { it.readBytes() } }
            val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1)
            if (bytes < 32 * 1024) throw IllegalStateException("слишком мало данных")
            val mbps = bytes * 8.0 / (ms / 1000.0) / 1_000_000.0
            onStatus("Отдача: ${"%.1f".format(mbps)} Мбит/с")
            return mbps
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 20_000
            requestMethod = method
            setRequestProperty("User-Agent", "SpectrIT-NetMTR/1.1")
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    private fun localIpv4(): String? {
        val text = command(listOf("ipconfig"))
        val addresses = Regex("""(?i)IPv4[^:\r\n]*:\s*(\d+\.\d+\.\d+\.\d+)""").findAll(text).map { it.groupValues[1] }
        return addresses.firstOrNull { address ->
            !address.startsWith("127.") && !address.startsWith("169.254.")
        }
    }

    private fun arpTable(): Map<String, String> {
        val text = command(listOf("arp", "-a"))
        val table = linkedMapOf<String, String>()
        val row = Regex("""(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5})""")
        row.findAll(text).forEach { match ->
            val mac = match.groupValues[2].replace('-', ':').lowercase()
            if (mac != "ff:ff:ff:ff:ff:ff") table[match.groupValues[1]] = mac
        }
        return table
    }

    private fun command(command: List<String>): String {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor(8, TimeUnit.SECONDS)
            text
        }.getOrDefault("")
    }

    private fun trimBlock(text: String): String {
        return text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(80)
            .joinToString("\n")
    }
}
