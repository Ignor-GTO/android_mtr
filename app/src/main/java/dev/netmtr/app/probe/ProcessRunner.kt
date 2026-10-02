package dev.netmtr.app.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RunOutput(
    val exitCode: Int,
    val text: String,
)

class ProcessRunner {
    /**
     * [onLine] возвращает true, если процесс нужно остановить сразу.
     * Так traceroute не ждёт полный таймаут после ответа маршрутизатора.
     */
    suspend fun run(
        command: List<String>,
        timeoutMs: Long,
        onLine: (line: String, elapsedMs: Double) -> Boolean,
    ): RunOutput = coroutineScope {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            return@coroutineScope RunOutput(
                exitCode = -1,
                text = error.message ?: "не удалось запустить процесс",
            )
        }
        val started = System.nanoTime()
        val lines = mutableListOf<String>()
        try {
            coroutineContext.job.invokeOnCompletion { reason ->
                if (reason != null && process.isAlive) process.destroyForcibly()
            }
            val reader = async(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            lines += line
                            val elapsed = (System.nanoTime() - started) / 1_000_000.0
                            if (onLine(line, elapsed)) {
                                process.destroyForcibly()
                                break
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Поток закрывается, когда процесс останавливают досрочно.
                }
            }
            val finished = withContext(Dispatchers.IO) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!finished) process.destroyForcibly()
            reader.await()
            val code = if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                runCatching { process.exitValue() }.getOrDefault(-1)
            } else {
                runCatching { process.exitValue() }.getOrDefault(-1)
            }
            RunOutput(code, lines.joinToString("\n"))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
