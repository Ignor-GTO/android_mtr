package dev.netmtr.app.probe

sealed interface LineEvent {
    data class Echo(val address: String, val rttMs: Double?) : LineEvent
    data class Exceeded(val address: String, val rttMs: Double?) : LineEvent
    data class Unreachable(val address: String, val reason: String, val rttMs: Double?) : LineEvent
    data class Fatal(val message: String, val usage: Boolean) : LineEvent
}

data class PingStats(val transmitted: Int, val received: Int)

object PingParser {
    private val echoRe = Regex(
        """(?i)bytes from\s+(.+?):\s*(?:icmp_)?seq=\d+.*?time[=<]\s*([0-9.]+)\s*ms""",
    )
    private val echoNoTimeRe = Regex(
        """(?i)bytes from\s+(.+?):\s*(?:icmp_)?seq=\d+""",
    )
    private val fromRe = Regex(
        """(?i)^From\s+(.+?)\s*(?:icmp_)?seq=\d+\s+(.+)$""",
    )
    private val timeRe = Regex(
        """(?i)time[=<]\s*([0-9.]+)\s*ms""",
    )
    private val statsRe = Regex(
        """(\d+)\s+packets transmitted,\s+(\d+)\s+(?:packets\s+)?received""",
        RegexOption.IGNORE_CASE,
    )

    fun parseLine(line: String): LineEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        echoRe.find(trimmed)?.let { match ->
            return LineEvent.Echo(
                address = cleanAddress(match.groupValues[1]),
                rttMs = match.groupValues[2].toDoubleOrNull(),
            )
        }
        echoNoTimeRe.find(trimmed)?.let { match ->
            return LineEvent.Echo(
                address = cleanAddress(match.groupValues[1]),
                rttMs = timeRe.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull(),
            )
        }

        fromRe.matchEntire(trimmed)?.let { match ->
            val address = cleanAddress(match.groupValues[1])
            val rest = match.groupValues[2].trim()
            val rtt = timeRe.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
            val lower = rest.lowercase()
            return when {
                "time to live exceeded" in lower || "ttl exceeded" in lower ->
                    LineEvent.Exceeded(address, rtt)
                "unreachable" in lower || "prohibited" in lower ->
                    LineEvent.Unreachable(address, rest, rtt)
                else -> null
            }
        }

        if (isSkippable(trimmed)) return null
        if (isFatalContent(trimmed)) {
            val usage = isUsageLine(trimmed)
            return LineEvent.Fatal(humanize(trimmed), usage)
        }
        return null
    }

    fun parseStats(text: String): PingStats? {
        val match = statsRe.find(text) ?: return null
        val transmitted = match.groupValues[1].toIntOrNull() ?: return null
        val received = match.groupValues[2].toIntOrNull() ?: return null
        return PingStats(transmitted, received)
    }

    fun isUsageError(text: String): Boolean {
        return text.lineSequence().any { isUsageLine(it) }
    }

    fun fatalMessage(text: String): String? {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.isNotEmpty() && !isSkippable(candidate) && isFatalContent(candidate)
            }
        return line?.let { humanize(it) }
    }

    fun cleanAddress(raw: String): String {
        return raw.trim().trimEnd(':').trim()
    }

    private fun isSkippable(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("PING ", ignoreCase = true) ||
            trimmed.startsWith("---") ||
            trimmed.contains("packets transmitted", ignoreCase = true) ||
            trimmed.startsWith("rtt ", ignoreCase = true) ||
            trimmed.startsWith("round-trip", ignoreCase = true) ||
            trimmed.startsWith("From ", ignoreCase = true) ||
            trimmed.contains("bytes from", ignoreCase = true)
    }

    private fun isUsageLine(line: String): Boolean {
        val lower = line.lowercase()
        return "bad option" in lower ||
            "invalid option" in lower ||
            "unknown option" in lower ||
            lower.contains("usage:")
    }

    private fun isFatalContent(line: String): Boolean {
        val lower = line.lowercase()
        return isUsageLine(line) ||
            "unknown host" in lower ||
            "not known" in lower ||
            "no address associated" in lower ||
            "temporary failure in name resolution" in lower ||
            "operation not permitted" in lower ||
            "permission denied" in lower ||
            "network is unreachable" in lower
    }

    private fun humanize(line: String): String {
        val lower = line.lowercase()
        val reason = when {
            isUsageLine(line) -> "Утилита ping не приняла аргумент"
            "operation not permitted" in lower || "permission denied" in lower ->
                "Устройство запретило ICMP-пинг"
            "unknown host" in lower ||
                "not known" in lower ||
                "no address associated" in lower ||
                "temporary failure" in lower -> "Не удалось разрешить имя узла"
            "network is unreachable" in lower -> "Нет маршрута до сети"
            else -> "Ошибка ping"
        }
        return "$reason: ${line.trim().take(180)}"
    }
}
