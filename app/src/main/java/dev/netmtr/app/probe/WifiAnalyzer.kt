package dev.netmtr.app.probe

data class VisibleAp(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val levelDbm: Int,
    val widthMhz: Int,
    val centerMhz: Int,
)

data class ChannelLoad(
    val frequencyMhz: Int,
    val channel: Int,
    val band: String,
    val neighbors: Int,
    val strongestDbm: Int?,
    val own: Boolean,
    val load: String,
)

data class WifiSurvey(
    val connectedSsid: String?,
    val connectedBssid: String?,
    val connectedFrequencyMhz: Int?,
    val connectedChannel: Int?,
    val connectedBand: String?,
    val channels: List<ChannelLoad>,
    val note: String?,
    val error: String?,
) {
    fun routerLine(): String {
        error?.let { return it }
        val frequency = connectedFrequencyMhz ?: return "Телефон не подключён к Wi‑Fi."
        val name = connectedSsid ?: "скрытая сеть"
        return "Наш роутер: $name, $frequency МГц, канал $connectedChannel, $connectedBand"
    }

    companion object {
        fun failed(message: String) = WifiSurvey(
            connectedSsid = null,
            connectedBssid = null,
            connectedFrequencyMhz = null,
            connectedChannel = null,
            connectedBand = null,
            channels = emptyList(),
            note = null,
            error = message,
        )
    }
}

object WifiAnalyzer {
    fun frequencyFor24(channel: Int): Int = if (channel == 14) 2484 else 2407 + channel * 5

    fun channel(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> 0
    }

    fun band(frequencyMhz: Int): String = when {
        frequencyMhz <= 0 -> "—"
        frequencyMhz < 3000 -> "2.4 ГГц"
        frequencyMhz < 5925 -> "5 ГГц"
        else -> "6 ГГц"
    }

    fun analyze(
        accessPoints: List<VisibleAp>,
        ownFrequencyMhz: Int?,
        ownBssid: String?,
    ): List<ChannelLoad> {
        val rows = mutableListOf<ChannelLoad>()
        val seen = mutableSetOf<Int>()
        for (number in 1..13) {
            val frequency = frequencyFor24(number)
            seen += frequency
            rows += row(frequency, number, accessPoints, ownFrequencyMhz, ownBssid)
        }
        val extra = buildSet {
            accessPoints.forEach { if (it.frequencyMhz >= 3000) add(it.frequencyMhz) }
            if (ownFrequencyMhz != null && ownFrequencyMhz >= 3000) add(ownFrequencyMhz)
        }
        extra.sorted().forEach { frequency ->
            if (frequency !in seen) {
                rows += row(frequency, channel(frequency), accessPoints, ownFrequencyMhz, ownBssid)
            }
        }
        return rows
    }

    private fun row(
        frequencyMhz: Int,
        channel: Int,
        accessPoints: List<VisibleAp>,
        ownFrequencyMhz: Int?,
        ownBssid: String?,
    ): ChannelLoad {
        val here = accessPoints.filter { covers(it, frequencyMhz) }
        val others = here.filter { ap ->
            ownBssid.isNullOrBlank() || !ap.bssid.equals(ownBssid, ignoreCase = true)
        }
        val strong = others.count { it.levelDbm >= -70 }
        val own = ownFrequencyMhz != null && ownFrequencyMhz == frequencyMhz
        val load = when {
            others.isEmpty() -> "свободна"
            others.size >= 3 || strong >= 2 -> "нагружена"
            else -> "слабо"
        }
        return ChannelLoad(
            frequencyMhz = frequencyMhz,
            channel = channel,
            band = band(frequencyMhz),
            neighbors = others.size,
            strongestDbm = here.maxOfOrNull { it.levelDbm },
            own = own,
            load = load,
        )
    }

    private fun covers(ap: VisibleAp, frequencyMhz: Int): Boolean {
        val center = if (ap.centerMhz > 0) ap.centerMhz else ap.frequencyMhz
        val half = ap.widthMhz.coerceAtLeast(20) / 2
        return kotlin.math.abs(center - frequencyMhz) <= half || ap.frequencyMhz == frequencyMhz
    }
}
