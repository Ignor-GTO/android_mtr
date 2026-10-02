package dev.netmtr.app.probe

import java.util.Locale

object TextFormat {
    fun ms(value: Double?): String {
        return if (value == null) "—" else String.format(Locale.US, "%.1f", value)
    }

    fun msUnit(value: Double?): String {
        return if (value == null) "—" else "${ms(value)} мс"
    }

    fun pct(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    fun mbps(value: Double?): String {
        return if (value == null) "—" else String.format(Locale.US, "%.1f Мбит/с", value)
    }

    fun megabytes(bytes: Long): String {
        return String.format(Locale.US, "%.1f МБ", bytes / 1_000_000.0)
    }

    fun seconds(ms: Long): String {
        return String.format(Locale.US, "%.1f с", ms / 1000.0)
    }

    fun kbps(value: Int?): String? {
        if (value == null || value <= 0) return null
        return if (value >= 1000) {
            String.format(Locale.US, "%.0f Мбит/с", value / 1000.0)
        } else {
            "$value Кбит/с"
        }
    }

    fun hopHeader(): String {
        return "Hop " +
            "Узел".padEnd(40) +
            "Потери".padStart(8) +
            "Отпр".padStart(6) +
            "Прин".padStart(6) +
            "Лучш".padStart(8) +
            "Сред".padStart(8) +
            "Худш".padStart(8) +
            "Посл".padStart(8) +
            "Джит".padStart(8)
    }

    fun hopLine(row: HopRow): String {
        val extra = if (row.addresses.size > 1) " (+${row.addresses.size - 1})" else ""
        val addr = (row.address + extra).take(40)
        return String.format(Locale.US, "%3d ", row.hop) +
            addr.padEnd(40) +
            pct(row.lossPercent).padStart(8) +
            row.sent.toString().padStart(6) +
            row.received.toString().padStart(6) +
            ms(row.bestMs).padStart(8) +
            ms(row.avgMs).padStart(8) +
            ms(row.worstMs).padStart(8) +
            ms(row.lastMs).padStart(8) +
            ms(row.jitterMs).padStart(8)
    }
}
