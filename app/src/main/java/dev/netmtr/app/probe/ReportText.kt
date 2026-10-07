package dev.netmtr.app.probe

import java.time.format.DateTimeFormatter
import java.util.Locale

object ReportText {
    internal val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.US)

    fun build(result: TestResult): String = buildString {
        appendLine("Spectr IT NetMTR-2 — ${result.mode.title} — ${result.host}")
        appendLine("========================================")
        appendLine("Начало: ${result.startedAt.format(timeFormat)}")
        appendLine("Конец:  ${result.finishedAt.format(timeFormat)}")
        appendLine("Приложение: ${result.appVersion}")

        result.network?.let { network ->
            appendLine()
            appendLine("Устройство и сеть")
            appendLine("-----------------")
            appendLine("Устройство: ${network.manufacturer} ${network.model}")
            appendLine("Android: ${network.androidRelease} (SDK ${network.sdkInt})")
            network.securityPatch?.let { appendLine("Патч безопасности: $it") }
            appendLine("Тип сети: ${network.networkType}")
            appendLine("VPN: ${yesNo(network.vpn)}")
            appendLine("Интернет по данным системы: ${yesNo(network.hasInternet)}")
            appendLine("Доступ подтверждён: ${yesNo(network.validated)}")
            appendLine("Лимитное подключение: ${yesNo(network.metered)}")
            network.ssid?.let { appendLine("Wi‑Fi: $it") }
            network.carrier?.let { appendLine("Оператор: $it") }
            appendLine(
                "Локальные адреса: ${network.localAddresses.joinToString(", ").ifBlank { "нет" }}",
            )
            appendLine("Шлюз: ${network.gateway ?: "не определён"}")
            appendLine("DNS: ${network.dnsServers.joinToString(", ").ifBlank { "нет" }}")
            val down = TextFormat.kbps(network.downKbps)
            val up = TextFormat.kbps(network.upKbps)
            if (down != null || up != null) {
                appendLine("Оценка канала системой: ↓${down ?: "—"} ↑${up ?: "—"}")
            }
        }

        appendLine()
        appendLine("Внешний адрес")
        appendLine("--------------")
        if (result.publicIp == null) {
            appendLine("Не удалось определить.")
        } else {
            val extra = listOfNotNull(
                result.colo?.let { "узел $it" },
                result.location?.let { "код страны $it" },
            ).joinToString(", ")
            appendLine(if (extra.isBlank()) result.publicIp else "${result.publicIp} ($extra)")
        }

        if (result.dns.isNotEmpty()) {
            appendLine()
            appendLine("DNS")
            appendLine("---")
            appendLine("Время может включать системный кэш.")
            result.dns.forEach { item ->
                if (item.error != null) {
                    appendLine("${item.name}: ${item.error}")
                } else {
                    appendLine(
                        "${item.name}: ${item.elapsedMs ?: "?"} мс → ${item.addresses.joinToString(", ")}",
                    )
                }
            }
        }

        result.http?.let { http ->
            appendLine()
            appendLine("Веб-доступ")
            appendLine("----------")
            appendLine(http.url)
            when {
                http.error != null -> appendLine("Ошибка: ${http.error}")
                http.code == 204 -> appendLine("Код 204 за ${http.elapsedMs} мс. Похоже на обычный выход в интернет без перехвата.")
                http.code != null -> appendLine("Код ${http.code} за ${http.elapsedMs ?: "?"} мс.")
            }
        }

        result.tcp?.let { tcp ->
            appendLine()
            appendLine("TCP")
            appendLine("---")
            if (tcp.error == null) {
                appendLine("${tcp.host}:${tcp.port} открыт за ${tcp.elapsedMs} мс.")
            } else {
                appendLine("${tcp.host}:${tcp.port} не открылся: ${tcp.error}")
            }
        }

        result.speed?.let { appendSpeed(it) }
        result.wifi?.let { appendWifi(it) }
        result.lan?.let { appendLan(it) }

        result.gatewayPing?.let { appendPing("Пинг шлюза", it) }
        result.targetPing?.let { appendPing("Пинг цели", it) }

        if (result.hops.isNotEmpty() || result.mode == TestMode.MTR || result.mode == TestMode.TRACE || result.mode == TestMode.FULL) {
            appendLine()
            appendLine("MTR / трассировка")
            appendLine("-----------------")
            appendLine("Цель: ${result.host}")
            if (result.cycles != null) appendLine("Циклов: ${result.cycles}, максимум прыжков: ${result.maxHops ?: "—"}, таймаут: ${result.timeoutSec ?: "—"} с")
            if (result.intervalSec != null) {
                appendLine(
                    "Интервал: ${String.format(Locale.US, "%.1f", result.intervalSec)} с, размер пинга: ${result.pingBytes ?: "—"} байт, кэш хостов: ${result.hostCache ?: "—"}, имена: ${if (result.resolveNames == true) "да" else "нет"}",
                )
            }
            if (result.overheadApplied && result.overheadMs != null) {
                appendLine(
                    "Поправка на запуск ping: ${TextFormat.ms(result.overheadMs)} мс, вычтена из задержки промежуточных прыжков.",
                )
                appendLine("У конечного узла задержка взята из поля time, если ping его напечатал.")
            } else if (result.hops.isNotEmpty()) {
                appendLine("Поправка на запуск ping не применялась. Абсолютные цифры промежуточных прыжков завышены одинаково, смотрите разницу между ними.")
            }
            if (result.hops.isEmpty()) {
                appendLine("Таблица пуста.")
            } else {
                appendLine(TextFormat.hopHeader())
                result.hops.forEach { appendLine(TextFormat.hopLine(it)) }
                val mixed = result.hops.filter { it.addresses.size > 1 }
                if (mixed.isNotEmpty()) {
                    appendLine()
                    mixed.forEach { hop ->
                        appendLine("Прыжок ${hop.hop}: ответы пришли от ${hop.addresses.joinToString(", ")}.")
                    }
                }
            }
        }

        appendLine()
        appendLine("Как читать отчёт")
        appendLine("----------------")
        appendLine("• Потери только на промежуточном прыжке при чистых следующих прыжках обычно значат, что маршрутизатор молчит на traceroute.")
        appendLine("• Потери на пинге цели или на последнем отвечающем узле — повод для администратора сети.")
        appendLine("• Потери и большая задержка до шлюза указывают на Wi‑Fi или локальную сеть.")
        appendLine("• В списке устройств только адреса, которые ответили на пинг или уже есть в ARP. Молчащие телефоны не видны.")
        appendLine("• Скачок средней задержки показывает участок, где появляется основная задержка.")
        appendLine()
        appendLine("Отчёт собран на устройстве. Приложение само никуда его не отправляет.")
    }.trimEnd() + "\n"

    private fun StringBuilder.appendSpeed(speed: SpeedResult) {
        appendLine()
        appendLine("Скорость")
        appendLine("--------")
        appendLine("DOWNLOAD Mbps  ${TextFormat.mbpsNumber(speed.downloadMbps)}")
        appendLine("UPLOAD Mbps    ${TextFormat.mbpsNumber(speed.uploadMbps)}")
        appendLine("Ping ms        ${TextFormat.latencyMs(speed.pingMs)}")
        appendLine("↓ загрузка     ${TextFormat.latencyMs(speed.downloadLatencyMs)} мс")
        appendLine("↑ отдача       ${TextFormat.latencyMs(speed.uploadLatencyMs)} мс")
        val errors = listOfNotNull(speed.downloadError, speed.uploadError)
        if (errors.isNotEmpty()) appendLine(errors.joinToString("; "))
    }

    private fun StringBuilder.appendLan(survey: LanSurvey) {
        appendLine()
        appendLine("Устройства в сети")
        appendLine("------------------")
        if (survey.error != null) {
            appendLine(survey.error)
            return
        }
        survey.localAddress?.let { appendLine("Наш адрес: $it/${survey.prefix ?: "—"}") }
        appendLine("Проверено: ${survey.scanned} из ${survey.planned}. Ответили: ${survey.devices.size}.")
        survey.note?.let { appendLine(it) }
        if (survey.devices.isEmpty()) {
            appendLine("Живых адресов не найдено.")
            return
        }
        appendLine("Адрес            Имя / MAC                         мс")
        survey.devices.forEach { device ->
            val label = listOfNotNull(device.name, device.mac).joinToString(" · ").ifBlank { "—" }
            val role = if (device.gateway) " шлюз" else ""
            val rtt = device.rttMs?.let { TextFormat.ms(it) } ?: "—"
            appendLine("${device.address.padEnd(16)} ${label.take(32).padEnd(32)} $rtt$role")
        }
    }

    private fun StringBuilder.appendWifi(survey: WifiSurvey) {
        appendLine()
        appendLine("Частоты Wi‑Fi")
        appendLine("-------------")
        appendLine(survey.routerLine())
        survey.note?.let { appendLine(it) }
        if (survey.channels.isEmpty()) return
        appendLine("Канал  МГц    Диапазон  Соседей  Сигнал  Статус")
        survey.channels.forEach { channel ->
            val own = if (channel.own) " наш" else ""
            val signal = channel.strongestDbm?.let { "$it дБм" } ?: "—"
            appendLine(
                String.format(
                    Locale.US,
                    "%5d  %4d  %-8s  %7d  %7s  %s%s",
                    channel.channel,
                    channel.frequencyMhz,
                    channel.band,
                    channel.neighbors,
                    signal,
                    channel.load,
                    own,
                ),
            )
        }
    }

    private fun StringBuilder.appendPing(title: String, ping: PingSummary) {
        appendLine()
        appendLine(title)
        appendLine("-".repeat(title.length))
        appendLine("Цель: ${ping.target}")
        appendLine(
            "Отправлено ${ping.transmitted}, получено ${ping.received}, потери ${TextFormat.pct(ping.lossPercent)}",
        )
        appendLine(
            "Задержка мин/сред/макс: ${TextFormat.ms(ping.minMs)} / ${TextFormat.ms(ping.avgMs)} / ${TextFormat.ms(ping.maxMs)} мс",
        )
        appendLine("Джиттер: ${TextFormat.msUnit(ping.jitterMs)}")
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"
}
