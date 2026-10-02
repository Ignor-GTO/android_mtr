package dev.netmtr.app.probe

object Conclusions {
    fun build(result: TestResult): List<String> {
        val notes = mutableListOf<String>()
        if (result.stopped) {
            notes += "Проверка остановлена раньше конца. Ниже только то, что успело собраться."
        }
        result.error?.let { notes += it }
        notes += result.remarks

        val network = result.network
        if (network?.vpn == true) {
            notes += "Включён VPN: маршрут и потери могут относиться к туннелю, а не к провайдеру напрямую."
        }
        if (network != null && !network.validated && network.networkType != "Нет активной сети") {
            notes += "Система не подтвердила доступ в интернет. Возможен портал авторизации Wi‑Fi или обрыв."
        }

        result.speed?.let { speed ->
            if (speed.downloadError == null && speed.downloadMbps != null && speed.downloadMbps < 5.0) {
                notes += "Загрузка ${TextFormat.mbps(speed.downloadMbps)}. Для видео и больших файлов этого часто мало."
            } else if (speed.downloadMbps != null && speed.downloadError == null) {
                notes += "Загрузка ${TextFormat.mbps(speed.downloadMbps)}, отдача ${TextFormat.mbps(speed.uploadMbps)}."
            }
            if (speed.uploadError == null && speed.uploadMbps != null && speed.uploadMbps < 1.0) {
                notes += "Отдача ${TextFormat.mbps(speed.uploadMbps)}. Исходящий канал узкий: звонки и отправка файлов могут страдать."
            }
            speed.downloadError?.let { notes += "Замер загрузки не удался: $it." }
            speed.uploadError?.let { notes += "Замер отдачи не удался: $it." }
        }

        result.gatewayPing?.let { ping ->
            if (ping.transmitted > 0 && ping.lossPercent >= 5) {
                notes += "До шлюза ${ping.target} потери ${TextFormat.pct(ping.lossPercent)}. Чаще всего это Wi‑Fi или локальная сеть, а не магистраль провайдера."
            } else if ((ping.avgMs ?: 0.0) >= 50) {
                notes += "Высокая задержка до шлюза: ${TextFormat.msUnit(ping.avgMs)}. Проблема похожа на местное соединение."
            }
        }

        val http = result.http
        if (http != null) {
            when {
                http.error != null -> notes += "Проверка веб-доступа не удалась: ${http.error}."
                http.code == 204 -> notes += "Веб-доступ есть: контрольный адрес ответил кодом 204 за ${http.elapsedMs ?: "?"} мс."
                http.code != null && http.code in listOf(200, 301, 302, 303, 307, 308) ->
                    notes += "Контрольный адрес вернул код ${http.code}. Сеть может показывать страницу входа или фильтр."
                http.code != null -> notes += "Контрольный адрес вернул код ${http.code}."
            }
        }

        val ping = result.targetPing
        val tcp = result.tcp
        if (ping != null && tcp != null) {
            val pingFails = ping.transmitted > 0 && ping.received == 0
            val tcpOk = tcp.error == null
            if (pingFails && tcpOk) {
                notes += "Пинг до цели не проходит, но TCP ${tcp.port} открывается за ${tcp.elapsedMs} мс. ICMP, скорее всего, фильтруют, а канал при этом жив."
            } else if (!pingFails && ping.lossPercent < 5 && tcp.error != null) {
                notes += "Пинг до цели проходит, TCP ${tcp.port} не открылся. Для канала важнее пинг и MTR: порт может быть просто закрыт."
            }
        }

        if (ping != null && ping.transmitted > 0 && ping.lossPercent >= 5) {
            notes += "Пинг цели ${ping.target}: потери ${TextFormat.pct(ping.lossPercent)}, средняя ${TextFormat.msUnit(ping.avgMs)}, джиттер ${TextFormat.msUnit(ping.jitterMs)}."
        } else if (ping != null && ping.received > 0) {
            notes += "Пинг цели ${ping.target}: потери ${TextFormat.pct(ping.lossPercent)}, средняя ${TextFormat.msUnit(ping.avgMs)}, джиттер ${TextFormat.msUnit(ping.jitterMs)}."
        }

        val hops = result.hops
        if (hops.isNotEmpty()) {
            val silentMiddle = hops.filter { hop ->
                hop.received == 0 && hops.any { later -> later.hop > hop.hop && later.received > 0 }
            }
            if (silentMiddle.isNotEmpty()) {
                notes += "Нет ICMP от прыжков ${silentMiddle.joinToString { it.hop.toString() }}, хотя маршрут дальше отвечает. Так обычно фильтруют traceroute, это не доказывает обрыв."
            }
            hops.filter { it.sent >= 4 && it.received > 0 && it.lossPercent >= 10 }.forEach { hop ->
                val laterHealthy = hops.any { later ->
                    later.hop > hop.hop && later.received > 0 && later.lossPercent < 5
                }
                notes += if (laterHealthy && !hop.reachedTarget) {
                    "Прыжок ${hop.hop} (${hop.address}): потери ${TextFormat.pct(hop.lossPercent)}, дальше путь ровный. Похоже на лимит ICMP на маршрутизаторе."
                } else {
                    "Прыжок ${hop.hop} (${hop.address}): потери ${TextFormat.pct(hop.lossPercent)}. Этот участок стоит показать администратору."
                }
            }
            val jump = largestJump(hops)
            if (jump != null) {
                notes += "Скачок задержки между прыжком ${jump.fromHop} (${jump.fromAddress}) и ${jump.toHop} (${jump.toAddress}): +${TextFormat.ms(jump.deltaMs)} мс."
            }
            val reached = hops.any { it.reachedTarget }
            if (!reached && result.mode != TestMode.PING) {
                notes += "Конечный узел не ответил на traceroute. Если отдельный пинг или TCP при этом успешны, узел просто не отвечает на пробы с малым TTL."
            }
        }

        if (notes.none { it.startsWith("Проверка остановлена") || it.startsWith("Пинг") || it.startsWith("Прыжок") || it.startsWith("До шлюза") || it.startsWith("Скачок") } &&
            result.error == null &&
            !result.stopped
        ) {
            val healthyPing = ping == null || ping.lossPercent < 5
            if (healthyPing && (ping != null || http?.code == 204)) {
                notes += "По этому прогону явных потерь до цели не видно. Если сбой плавающий, повторите проверку в момент проблемы и отправьте оба отчёта."
            }
        }
        if (notes.isEmpty()) {
            notes += "Данных для вывода мало. Повторите полную проверку."
        }
        return notes.distinct()
    }

    private data class Jump(val fromHop: Int, val fromAddress: String, val toHop: Int, val toAddress: String, val deltaMs: Double)

    private fun largestJump(hops: List<HopRow>): Jump? {
        val withAvg = hops.mapNotNull { hop -> hop.avgMs?.let { hop to it } }
        var best: Jump? = null
        for (index in 1 until withAvg.size) {
            val previous = withAvg[index - 1]
            val current = withAvg[index]
            val delta = current.second - previous.second
            if (delta >= 40 && current.second >= 50 && (best == null || delta > best.deltaMs)) {
                best = Jump(previous.first.hop, previous.first.address, current.first.hop, current.first.address, delta)
            }
        }
        return best
    }
}
