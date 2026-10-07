package dev.netmtr.app.probe

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

class LanScanner(private val ping: PingClient) {
    suspend fun collect(
        context: Context,
        onUpdate: (LanSurvey) -> Unit,
    ): LanSurvey {
        val endpoint = endpoint(context)
            ?: return LanSurvey.failed("Нет локального IPv4. Поиск устройств работает в Wi‑Fi и Ethernet.")
        val targets = LanPlan.targets(endpoint.address, endpoint.prefix)
        if (targets.isEmpty()) {
            return LanSurvey.failed("Локальная сеть слишком мала для поиска устройств.")
        }
        val note = if (LanPlan.narrowedTo24(endpoint.prefix)) {
            "Сеть шире /24, проверен только ваш сегмент из 254 адресов."
        } else {
            null
        }
        val found = linkedMapOf<String, LanDevice>()
        var scanned = 0
        fun snapshot() = LanSurvey(
            localAddress = endpoint.address,
            prefix = endpoint.prefix,
            scanned = scanned,
            planned = targets.size,
            devices = found.values.sortedWith { left, right ->
                when {
                    left.gateway != right.gateway -> if (left.gateway) -1 else 1
                    else -> LanPlan.compareAddresses(left.address, right.address)
                }
            },
            note = note,
            error = null,
        )
        onUpdate(snapshot())
        for (batch in targets.chunked(BATCH)) {
            coroutineContext.ensureActive()
            val replies = coroutineScope {
                batch.map { address ->
                    async {
                        address to ping.echoFrom(address, timeoutSec = 1, payloadBytes = 32)
                    }
                }.awaitAll()
            }
            for ((address, rtt) in replies) {
                if (rtt == null) continue
                found[address] = LanDevice(
                    address = address,
                    mac = null,
                    name = null,
                    rttMs = rtt,
                    gateway = address == endpoint.gateway,
                )
            }
            scanned += batch.size
            onUpdate(snapshot())
        }
        val arp = LanPlan.parseArp(readArp())
        val targetSet = targets.toSet()
        for ((address, mac) in arp) {
            if (address !in targetSet && address != endpoint.gateway) continue
            val current = found[address]
            found[address] = (current ?: LanDevice(
                address = address,
                mac = mac,
                name = null,
                rttMs = null,
                gateway = address == endpoint.gateway,
            )).copy(mac = mac)
        }
        val raw = found.values.toList()
        val real = LanPlan.keepRealDevices(raw, endpoint.gateway)
        val dropped = raw.size - real.size
        val named = coroutineScope {
            real.map { device ->
                async { device.copy(name = lookupName(device.address)) }
            }.awaitAll()
        }
        found.clear()
        named.forEach { found[it.address] = it }
        val base = snapshot()
        val extra = if (dropped > 2) {
            "Одинаковый MAC у многих адресов — это ответ роутера, а не отдельные устройства."
        } else {
            null
        }
        val combined = listOfNotNull(base.note, extra).joinToString(" ").ifBlank { null }
        return base.copy(note = combined).also(onUpdate)
    }

    private suspend fun lookupName(address: String): String? = withTimeoutOrNull(400) {
        withContext(Dispatchers.IO) {
            val host = runCatching { InetAddress.getByName(address).hostName }.getOrNull() ?: return@withContext null
            if (host.isBlank() || host == address) null else host
        }
    }

    private fun readArp(): String {
        return runCatching { File("/proc/net/arp").readText() }.getOrDefault("")
    }

    private fun endpoint(context: Context): Endpoint? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val link = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        val local = link.linkAddresses.firstOrNull { address ->
            val ip = address.address
            ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress
        } ?: return null
        val gateway = link.routes.mapNotNull { route ->
            val address = route.gateway
            if (!route.isDefaultRoute || address !is Inet4Address) null else address.hostAddress
        }.firstOrNull()
        return Endpoint(
            address = (local.address as Inet4Address).hostAddress ?: return null,
            prefix = local.prefixLength,
            gateway = gateway,
        )
    }

    private data class Endpoint(val address: String, val prefix: Int, val gateway: String?)

    companion object {
        private const val BATCH = 24
    }
}
