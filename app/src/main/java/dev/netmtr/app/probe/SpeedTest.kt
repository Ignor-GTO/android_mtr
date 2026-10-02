package dev.netmtr.app.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.coroutineContext

object SpeedTest {
    suspend fun measure(onStatus: (String) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        val ping = PingClient()
        onStatus("Пинг…")
        val idle = mutableListOf<Double>()
        repeat(4) {
            coroutineContext.ensureActive()
            ping.oneRtt(LATENCY_HOST)?.let { idle += it }
        }
        onStatus("Замер загрузки…")
        val downloadLatency = mutableListOf<Double>()
        val download = coroutineScope {
            val samples = launch { collectLatency(ping, downloadLatency) }
            try {
                capture { transferDownload(onStatus) }
            } finally {
                samples.cancel()
                samples.join()
            }
        }
        onStatus("Замер отдачи…")
        val uploadLatency = mutableListOf<Double>()
        val upload = coroutineScope {
            val samples = launch { collectLatency(ping, uploadLatency) }
            try {
                capture { transferUpload(onStatus) }
            } finally {
                samples.cancel()
                samples.join()
            }
        }
        val down = download.getOrNull()
        val up = upload.getOrNull()
        SpeedResult(
            downloadMbps = down?.mbps,
            downloadBytes = down?.bytes ?: 0L,
            downloadMs = down?.ms ?: 0L,
            uploadMbps = up?.mbps,
            uploadBytes = up?.bytes ?: 0L,
            uploadMs = up?.ms ?: 0L,
            downloadError = download.exceptionOrNull()?.message ?: down?.error,
            uploadError = upload.exceptionOrNull()?.message ?: up?.error,
            pingMs = idle.minOrNull(),
            downloadLatencyMs = Rtt.median(downloadLatency),
            uploadLatencyMs = Rtt.median(uploadLatency),
        )
    }

    private suspend fun collectLatency(ping: PingClient, into: MutableList<Double>) {
        try {
            while (true) {
                coroutineContext.ensureActive()
                ping.oneRtt(LATENCY_HOST)?.let { sample ->
                    synchronized(into) { into += sample }
                }
            }
        } catch (_: CancellationException) {
        }
    }

    private suspend fun transferDownload(onStatus: (String) -> Unit): Sample {
        var lastError: String? = null
        for (url in downloadUrls()) {
            coroutineContext.ensureActive()
            try {
                return readFor(url, DOWNLOAD_LIMIT_MS, onStatus, "Загрузка")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error.message ?: "ошибка загрузки"
            }
        }
        return Sample(null, 0L, 0L, lastError ?: "не удалось скачать проверочный файл")
    }

    private suspend fun transferUpload(onStatus: (String) -> Unit): Sample {
        return try {
            writeFor(UPLOAD_URL, UPLOAD_LIMIT_MS, onStatus)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Sample(null, 0L, 0L, error.message ?: "ошибка отдачи")
        }
    }

    private suspend fun readFor(
        url: String,
        limitMs: Long,
        onStatus: (String) -> Unit,
        label: String,
    ): Sample {
        val connection = open(url, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("сервер ответил $code")
            }
            val buffer = ByteArray(16 * 1024)
            var bytes = 0L
            val started = System.nanoTime()
            connection.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val elapsed = elapsedMs(started)
                    if (elapsed >= limitMs) break
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    if (bytes % (256 * 1024) < read) {
                        onStatus("$label: ${TextFormat.mbps(mbps(bytes, elapsedMs(started)))}")
                    }
                }
            }
            val ms = elapsedMs(started).coerceAtLeast(1)
            if (bytes < 32 * 1024) throw IOException("слишком мало данных")
            return Sample(mbps(bytes, ms), bytes, ms, null)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun writeFor(url: String, limitMs: Long, onStatus: (String) -> Unit): Sample {
        val connection = open(url, "POST")
        connection.doOutput = true
        connection.setChunkedStreamingMode(0)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        val buffer = ByteArray(16 * 1024).also { ThreadLocalRandom.current().nextBytes(it) }
        var bytes = 0L
        val started = System.nanoTime()
        try {
            connection.outputStream.use { output ->
                while (elapsedMs(started) < limitMs && bytes < UPLOAD_CAP_BYTES) {
                    coroutineContext.ensureActive()
                    output.write(buffer)
                    bytes += buffer.size
                    onStatus("Отдача: ${TextFormat.mbps(mbps(bytes, elapsedMs(started).coerceAtLeast(1)))}")
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("сервер ответил $code")
            runCatching { connection.inputStream.use { it.readBytes() } }
            val ms = elapsedMs(started).coerceAtLeast(1)
            if (bytes < 32 * 1024) throw IOException("слишком мало данных")
            return Sample(mbps(bytes, ms), bytes, ms, null)
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
            setRequestProperty("User-Agent", "SpectrIT-NetMTR/2.0")
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun downloadUrls(): List<String> {
        val salt = System.currentTimeMillis()
        return listOf(
            "https://speed.cloudflare.com/__down?bytes=20000000&r=$salt",
            "https://proof.ovh.net/files/10Mb.dat",
        )
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun mbps(bytes: Long, ms: Long): Double {
        if (ms <= 0L) return 0.0
        return bytes * 8.0 / (ms / 1000.0) / 1_000_000.0
    }

    private data class Sample(val mbps: Double?, val bytes: Long, val ms: Long, val error: String?)

    private const val DOWNLOAD_LIMIT_MS = 8_000L
    private const val UPLOAD_LIMIT_MS = 6_000L
    private const val UPLOAD_CAP_BYTES = 8_000_000L
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val LATENCY_HOST = "1.1.1.1"
}
