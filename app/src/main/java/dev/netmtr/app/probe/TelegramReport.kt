package dev.netmtr.app.probe

/**
 * Текст для Telegram в формате rich message: табличные блоки — это
 * `<table bordered striped>`, который клиент рисует как настоящую таблицу.
 */
object TelegramReport {
    fun build(result: TestResult): String = buildString {
        append("<h2>").append(esc("Spectr IT NetMTR-2 — ${result.mode.title} — ${result.host}")).append("</h2>")
        append("<p>")
        append(esc("Начало: ${ReportText.timeFormat.format(result.startedAt)}"))
        append("<br>")
        append(esc("Конец: ${ReportText.timeFormat.format(result.finishedAt)}"))
        append("<br>")
        append(esc("Приложение: ${result.appVersion}"))
        append("</p>")

        val notes = Conclusions.build(result)
        if (notes.isNotEmpty()) {
            append("<h3>Выводы</h3><ul>")
            notes.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul>")
        }

        result.speed?.let { appendSpeed(it) }
        appendPings(result)
        if (result.hops.isNotEmpty()) appendMtr(result)
        result.network?.let { appendNetwork(result, it) }
        if (result.dns.isNotEmpty()) appendDns(result.dns)

        append("<p>")
        append(esc("Таблицы в формате Telegram rich message. Приложение само отчёт не отправляет."))
        append("</p>")
    }

    private fun StringBuilder.appendSpeed(speed: SpeedResult) {
        table("Скорость", listOf("Направление", "Скорость", "Объём", "Время"), rightFrom = 1) {
            row(
                "Загрузка",
                speed.downloadError?.let { "ошибка" } ?: TextFormat.mbps(speed.downloadMbps),
                if (speed.downloadBytes == 0L) "—" else TextFormat.megabytes(speed.downloadBytes),
                if (speed.downloadMs == 0L) "—" else TextFormat.seconds(speed.downloadMs),
            )
            row(
                "Отдача",
                speed.uploadError?.let { "ошибка" } ?: TextFormat.mbps(speed.uploadMbps),
                if (speed.uploadBytes == 0L) "—" else TextFormat.megabytes(speed.uploadBytes),
                if (speed.uploadMs == 0L) "—" else TextFormat.seconds(speed.uploadMs),
            )
        }
        val errors = listOfNotNull(speed.downloadError, speed.uploadError)
        if (errors.isNotEmpty()) {
            append("<p>").append(esc(errors.joinToString("; "))).append("</p>")
        }
    }

    private fun StringBuilder.appendPings(result: TestResult) {
        val rows = listOfNotNull(
            result.gatewayPing?.let { "Шлюз" to it },
            result.targetPing?.let { "Цель" to it },
        )
        if (rows.isEmpty()) return
        table("Пинг", listOf("Куда", "Адрес", "Потери", "Сред", "Джиттер"), rightFrom = 2) {
            rows.forEach { (label, ping) ->
                row(
                    label,
                    ping.target,
                    TextFormat.pct(ping.lossPercent),
                    TextFormat.msUnit(ping.avgMs),
                    TextFormat.msUnit(ping.jitterMs),
                )
            }
        }
    }

    private fun StringBuilder.appendMtr(result: TestResult) {
        val caption = buildString {
            append("MTR до ${result.host}")
            if (result.cycles != null) append(", циклов ${result.cycles}")
        }
        table(caption, listOf("Прыжок", "Узел", "Потери", "Отпр", "Прин", "Сред", "Джиттер"), rightFrom = 2) {
            result.hops.forEach { hop ->
                val address = if (hop.addresses.size > 1) {
                    "${hop.address} (+${hop.addresses.size - 1})"
                } else {
                    hop.address
                }
                row(
                    hop.hop.toString(),
                    address,
                    TextFormat.pct(hop.lossPercent),
                    hop.sent.toString(),
                    hop.received.toString(),
                    TextFormat.ms(hop.avgMs),
                    TextFormat.ms(hop.jitterMs),
                )
            }
        }
    }

    private fun StringBuilder.appendNetwork(result: TestResult, network: NetworkSnapshot) {
        table("Сеть", listOf("Параметр", "Значение"), rightFrom = 99) {
            row("Устройство", "${network.manufacturer} ${network.model}")
            row("Android", "${network.androidRelease} (SDK ${network.sdkInt})")
            row("Тип", network.networkType)
            row("VPN", if (network.vpn) "да" else "нет")
            network.ssid?.let { row("Wi‑Fi", it) }
            network.carrier?.let { row("Оператор", it) }
            row("Шлюз", network.gateway ?: "не определён")
            row("DNS", network.dnsServers.joinToString(", ").ifBlank { "нет" })
            row("Адреса", network.localAddresses.joinToString(", ").ifBlank { "нет" })
            result.publicIp?.let { ip ->
                val extra = listOfNotNull(result.location, result.colo).joinToString(" · ")
                row("Внешний IP", if (extra.isBlank()) ip else "$ip ($extra)")
            }
        }
    }

    private fun StringBuilder.appendDns(items: List<DnsResult>) {
        table("DNS", listOf("Имя", "Время", "Адреса"), rightFrom = 1) {
            items.forEach { item ->
                if (item.error != null) {
                    row(item.name, "ошибка", item.error)
                } else {
                    row(
                        item.name,
                        "${item.elapsedMs ?: "?"} мс",
                        item.addresses.joinToString(", ").ifBlank { "—" },
                    )
                }
            }
        }
    }

    private class TableBuilder(private val out: StringBuilder, private val rightFrom: Int) {
        fun row(vararg cells: String) {
            out.append("<tr>")
            cells.forEachIndexed { index, cell ->
                val align = if (index >= rightFrom) " align=\"right\"" else ""
                out.append("<td").append(align).append(">").append(esc(cell)).append("</td>")
            }
            out.append("</tr>")
        }
    }

    private fun StringBuilder.table(
        caption: String,
        headers: List<String>,
        rightFrom: Int,
        rows: TableBuilder.() -> Unit,
    ) {
        append("<table bordered striped><caption>").append(esc(caption)).append("</caption><tr>")
        headers.forEachIndexed { index, header ->
            val align = if (index >= rightFrom) " align=\"right\"" else ""
            append("<th").append(align).append(">").append(esc(header)).append("</th>")
        }
        append("</tr>")
        TableBuilder(this, rightFrom).rows()
        append("</table>")
    }

    fun esc(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", " ")
    }
}
