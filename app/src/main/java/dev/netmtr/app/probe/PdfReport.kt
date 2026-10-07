package dev.netmtr.app.probe

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object PdfReport {
    fun write(result: TestResult, output: OutputStream) {
        val document = PdfDocument()
        try {
            PdfPainter(document).render(result)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private class PdfPainter(private val document: PdfDocument) {
        private val margin = 36f
        private val pageWidth = 595
        private val pageHeight = 842
        private val contentWidth = pageWidth - margin * 2
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = margin
        private var pageNumber = 0

        private val title = paint(18f, bold = true, color = 0xFF0F6E62.toInt())
        private val section = paint(13f, bold = true, color = 0xFF14211E.toInt())
        private val body = paint(10f, bold = false, color = 0xFF1C2824.toInt())
        private val small = paint(8.5f, bold = false, color = 0xFF1C2824.toInt())
        private val header = paint(8.5f, bold = true, color = 0xFF06332C.toInt())
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD5E4DE.toInt()
            strokeWidth = 0.8f
        }
        private val headerFill = Paint().apply { color = 0xFFE5F3EE.toInt() }

        fun render(result: TestResult) {
            text("Spectr IT NetMTR-2 — ${result.mode.title}", title, 22f)
            text(result.host, section, 18f)
            text("Начало: ${ReportText.timeFormat.format(result.startedAt)}", body, 14f)
            text("Конец: ${ReportText.timeFormat.format(result.finishedAt)}", body, 14f)
            gap(8f)
            result.speed?.let { speed ->
                heading("Скорость")
                table(
                    listOf(180f, 120f),
                    listOf("Показатель", "Значение"),
                    listOf(
                        listOf("DOWNLOAD Mbps", TextFormat.mbpsNumber(speed.downloadMbps)),
                        listOf("UPLOAD Mbps", TextFormat.mbpsNumber(speed.uploadMbps)),
                        listOf("Ping ms", TextFormat.latencyMs(speed.pingMs)),
                        listOf("↓ загрузка, мс", TextFormat.latencyMs(speed.downloadLatencyMs)),
                        listOf("↑ отдача, мс", TextFormat.latencyMs(speed.uploadLatencyMs)),
                    ),
                )
                val errors = listOfNotNull(speed.downloadError, speed.uploadError)
                if (errors.isNotEmpty()) paragraph(errors.joinToString("; "))
            }
            result.wifi?.let { survey ->
                heading("Частоты Wi‑Fi")
                paragraph(survey.routerLine())
                survey.note?.let { paragraph(it) }
                if (survey.channels.isNotEmpty()) {
                    table(
                        listOf(50f, 60f, 70f, 60f, 90f),
                        listOf("Канал", "МГц", "Диапазон", "Соседей", "Статус"),
                        survey.channels.map { channel ->
                            listOf(
                                channel.channel.toString(),
                                channel.frequencyMhz.toString(),
                                channel.band,
                                channel.neighbors.toString(),
                                if (channel.own) "${channel.load}, наш" else channel.load,
                            )
                        },
                    )
                }
            }
            result.lan?.let { survey ->
                heading("Устройства в сети")
                if (survey.error != null) {
                    paragraph(survey.error)
                } else {
                    survey.localAddress?.let { paragraph("Наш адрес: $it/${survey.prefix ?: "—"}") }
                    paragraph("Проверено ${survey.scanned} из ${survey.planned}. Ответили: ${survey.devices.size}.")
                    survey.note?.let { paragraph(it) }
                    if (survey.devices.isNotEmpty()) {
                        table(
                            listOf(110f, 150f, 90f, 50f),
                            listOf("Адрес", "Имя", "MAC", "мс"),
                            survey.devices.map { device ->
                                listOf(
                                    if (device.gateway) "${device.address} шлюз" else device.address,
                                    device.name ?: "—",
                                    LanIdentity.displayMac(device.mac),
                                    device.rttMs?.let { TextFormat.ms(it) } ?: "—",
                                )
                            },
                        )
                    }
                }
            }
            result.network?.let { network ->
                heading("Устройство и сеть")
                paragraph("${network.manufacturer} ${network.model}, Android ${network.androidRelease}")
                paragraph("Сеть: ${network.networkType}. VPN: ${if (network.vpn) "да" else "нет"}.")
                network.ssid?.let { paragraph("Wi‑Fi: $it") }
                paragraph("Шлюз: ${network.gateway ?: "не определён"}")
                paragraph("DNS: ${network.dnsServers.joinToString(", ").ifBlank { "нет" }}")
                paragraph("Адреса: ${network.localAddresses.joinToString(", ").ifBlank { "нет" }}")
            }
            heading("Внешний адрес")
            paragraph(result.publicIp ?: "Не удалось определить.")
            if (result.dns.isNotEmpty()) {
                heading("DNS")
                result.dns.forEach { item ->
                    paragraph(
                        if (item.error != null) {
                            "${item.name}: ${item.error}"
                        } else {
                            "${item.name}: ${item.elapsedMs ?: "?"} мс → ${item.addresses.joinToString(", ")}"
                        },
                    )
                }
            }
            val pingRows = listOfNotNull(result.gatewayPing, result.targetPing)
            if (pingRows.isNotEmpty()) {
                heading("Пинг")
                table(
                    listOf(70f, 120f, 70f, 70f, 70f),
                    listOf("Куда", "Адрес", "Потери", "Сред", "Джиттер"),
                    listOfNotNull(
                        result.gatewayPing?.let {
                            listOf("Шлюз", it.target, TextFormat.pct(it.lossPercent), TextFormat.ms(it.avgMs), TextFormat.ms(it.jitterMs))
                        },
                        result.targetPing?.let {
                            listOf("Цель", it.target, TextFormat.pct(it.lossPercent), TextFormat.ms(it.avgMs), TextFormat.ms(it.jitterMs))
                        },
                    ),
                )
            }
            if (result.hops.isNotEmpty()) {
                heading("MTR до ${result.host}")
                if (result.cycles != null) {
                    paragraph("Циклов: ${result.cycles}, таймаут: ${result.timeoutSec ?: "—"} с.")
                }
                if (result.intervalSec != null) {
                    paragraph(
                        "Интервал: ${String.format(java.util.Locale.US, "%.1f", result.intervalSec)} с, размер пинга: ${result.pingBytes ?: "—"} байт, кэш хостов: ${result.hostCache ?: "—"}, имена: ${if (result.resolveNames == true) "да" else "нет"}.",
                    )
                }
                val widths = listOf(28f, 150f, 52f, 40f, 40f, 52f, 52f)
                table(
                    widths,
                    listOf("#", "Узел", "Потери", "Отпр", "Прин", "Сред", "Джит"),
                    result.hops.map { hop ->
                        val address = if (hop.addresses.size > 1) "${hop.address} +${hop.addresses.size - 1}" else hop.address
                        listOf(
                            hop.hop.toString(),
                            address,
                            TextFormat.pct(hop.lossPercent),
                            hop.sent.toString(),
                            hop.received.toString(),
                            TextFormat.ms(hop.avgMs),
                            TextFormat.ms(hop.jitterMs),
                        )
                    },
                )
            }
            gap(10f)
            paragraph("Отчёт собран на устройстве. Приложение само никуда его не отправляет.")
            finishPage()
        }

        private fun heading(value: String) {
            gap(8f)
            text(value, section, 18f)
        }

        private fun paragraph(value: String) {
            wrap(value, body, contentWidth).forEach { line -> text(line, body, 13f) }
        }

        private fun text(value: String, paint: Paint, step: Float) {
            ensure(step)
            canvas?.drawText(value, margin, y, paint)
            y += step
        }

        private fun gap(amount: Float) {
            ensure(amount)
            y += amount
        }

        private fun table(widths: List<Float>, headers: List<String>, rows: List<List<String>>) {
            val scale = contentWidth / widths.sum()
            val columns = widths.map { it * scale }
            drawRow(columns, headers, header, fill = true)
            rows.forEach { drawRow(columns, it, small, fill = false) }
        }

        private fun drawRow(widths: List<Float>, cells: List<String>, paint: Paint, fill: Boolean) {
            val rowHeight = 16f
            ensure(rowHeight)
            var x = margin
            if (fill) {
                canvas?.drawRect(margin, y - 11f, margin + contentWidth, y + 4f, headerFill)
            }
            canvas?.drawLine(margin, y + 4f, margin + contentWidth, y + 4f, line)
            cells.forEachIndexed { index, cell ->
                val width = widths.getOrElse(index) { 40f }
                val shown = ellipsize(cell, paint, width - 4f)
                canvas?.drawText(shown, x + 2f, y, paint)
                x += width
            }
            y += rowHeight
        }

        private fun ensure(height: Float) {
            if (canvas == null || y + height > pageHeight - margin) {
                finishPage()
                pageNumber += 1
                val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val next = document.startPage(info)
                page = next
                canvas = next.canvas
                y = margin
            }
        }

        private fun finishPage() {
            val current = page ?: return
            document.finishPage(current)
            page = null
            canvas = null
        }

        private fun wrap(value: String, paint: Paint, width: Float): List<String> {
            if (value.isBlank()) return listOf("")
            val lines = mutableListOf<String>()
            var rest = value
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                var end = count
                if (end < rest.length && rest[end - 1] != ' ') {
                    val space = rest.lastIndexOf(' ', end - 1)
                    if (space > 0) end = space
                }
                lines += rest.substring(0, end).trim()
                rest = rest.substring(end).trimStart()
            }
            return lines.ifEmpty { listOf(value) }
        }

        private fun ellipsize(value: String, paint: Paint, width: Float): String {
            if (paint.measureText(value) <= width) return value
            val ellipsis = "…"
            val count = paint.breakText(value, true, width - paint.measureText(ellipsis), null).coerceAtLeast(1)
            return value.take(count) + ellipsis
        }

        private fun paint(size: Float, bold: Boolean, color: Int): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            }
        }
    }
}
