package dev.netmtr.app.probe

import java.time.ZonedDateTime

enum class TestMode(val title: String) {
    FULL("Полная проверка"),
    MTR("MTR"),
    TRACE("Трассировка"),
    PING("Пинг"),
}

class ProbeException(message: String) : Exception(message)

data class HopRow(
    val hop: Int,
    val address: String,
    val addresses: List<String>,
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val bestMs: Double?,
    val avgMs: Double?,
    val worstMs: Double?,
    val lastMs: Double?,
    val jitterMs: Double?,
    val reachedTarget: Boolean,
)

data class PingSummary(
    val target: String,
    val transmitted: Int,
    val received: Int,
    val lossPercent: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    val jitterMs: Double?,
    val samples: List<Double>,
) {
    companion object {
        fun from(target: String, transmitted: Int, received: Int, samples: List<Double>): PingSummary {
            val recv = maxOf(received, 0)
            val sent = maxOf(transmitted, recv)
            return PingSummary(
                target = target,
                transmitted = sent,
                received = recv,
                lossPercent = if (sent == 0) 0.0 else (sent - recv) * 100.0 / sent,
                minMs = samples.minOrNull(),
                avgMs = samples.takeIf { it.isNotEmpty() }?.average(),
                maxMs = samples.maxOrNull(),
                jitterMs = Rtt.jitter(samples),
                samples = samples.toList(),
            )
        }
    }
}

data class NetworkSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val networkType: String,
    val vpn: Boolean,
    val validated: Boolean,
    val hasInternet: Boolean,
    val metered: Boolean,
    val ssid: String?,
    val carrier: String?,
    val localAddresses: List<String>,
    val gateway: String?,
    val dnsServers: List<String>,
    val downKbps: Int?,
    val upKbps: Int?,
)

data class DnsResult(
    val name: String,
    val elapsedMs: Long?,
    val addresses: List<String>,
    val error: String?,
)

data class HttpResult(
    val url: String,
    val code: Int?,
    val elapsedMs: Long?,
    val error: String?,
)

data class TcpResult(
    val host: String,
    val port: Int,
    val elapsedMs: Long?,
    val error: String?,
)

data class EdgeInfo(
    val ip: String?,
    val colo: String?,
    val location: String?,
)

data class TestResult(
    val mode: TestMode,
    val startedAt: ZonedDateTime,
    val finishedAt: ZonedDateTime,
    val host: String,
    val appVersion: String,
    val network: NetworkSnapshot?,
    val publicIp: String?,
    val colo: String?,
    val location: String?,
    val dns: List<DnsResult>,
    val http: HttpResult?,
    val tcp: TcpResult?,
    val gatewayPing: PingSummary?,
    val targetPing: PingSummary?,
    val hops: List<HopRow>,
    val cycles: Int?,
    val maxHops: Int?,
    val timeoutSec: Int?,
    val overheadMs: Double?,
    val overheadApplied: Boolean,
    val stopped: Boolean,
    val error: String?,
    val remarks: List<String> = emptyList(),
)

object Rtt {
    fun jitter(samples: List<Double>): Double? {
        if (samples.size < 2) return null
        var sum = 0.0
        for (i in 1 until samples.size) {
            sum += kotlin.math.abs(samples[i] - samples[i - 1])
        }
        return sum / (samples.size - 1)
    }
}

object Hosts {
    fun normalize(raw: String): String? {
        var host = raw.trim()
        if (host.startsWith("[") && host.endsWith("]") && host.length > 2) {
            host = host.substring(1, host.length - 1)
        }
        if (host.length !in 1..253) return null
        if (!host.all { it.isLetterOrDigit() || it in ".-:%" }) return null
        if (".." in host) return null
        return host
    }

    fun isIp(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return false
            n in 0..255 && part == n.toString()
        }
    }
}
