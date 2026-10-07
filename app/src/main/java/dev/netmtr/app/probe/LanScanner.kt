package dev.netmtr.app.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class LanScanner(private val ping: PingClient) {
    suspend fun collect(
        context: Context,
        onUpdate: (LanSurvey) -> Unit,
    ): LanSurvey {
        val endpoint = endpoint(context)
            ?: return LanSurvey.failed(
                if (vpnWithoutLan(context)) {
                    "Сейчас активен VPN. Поиск идёт по Wi‑Fi, а не по туннелю."
                } else {
                    "Нет локального IPv4. Поиск устройств работает в Wi‑Fi и Ethernet."
                },
            )
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
                        address to ping.echoFrom(address, timeoutSec = 1, payloadBytes = 32, source = endpoint.address)
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
        val arp = LanPlan.parseArp(readArp()) + LanIdentity.parseNeigh(readNeigh(endpoint.interfaceName))
        val routerMac = routerMac(context)
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
        val gateway = endpoint.gateway
        if (gateway != null && routerMac != null) {
            val current = found[gateway]
            if (current == null || current.mac == null) {
                found[gateway] = (current ?: LanDevice(gateway, routerMac, null, null, gateway = true)).copy(
                    mac = current?.mac ?: routerMac,
                    gateway = true,
                )
            }
        }
        val raw = found.values.toList()
        val kept = LanPlan.keepRealDevices(raw, endpoint.gateway, targets.size)
        val real = kept.devices
        val dropped = raw.size - real.size
        val asked = askDevices(context, real, endpoint.address)
        val named = withContext(Dispatchers.IO) {
            real.map { device ->
                val hostname = asked[device.address] ?: lookupDns(device.address)
                device.copy(name = hostname ?: LanIdentity.vendor(device.mac))
            }
        }
        found.clear()
        named.forEach { found[it.address] = it }
        val base = snapshot()
        val extra = when {
            kept.hidProxy -> "Почти каждый адрес ответил сразу и без своего MAC. Это ответ роутера или VPN, такие строки скрыты."
            dropped > 2 -> "Одинаковый MAC у многих адресов — это ответ роутера, а не отдельные устройства."
            named.isNotEmpty() && named.all { it.mac == null } -> "Android не показал MAC-адреса."
            else -> null
        }
        val combined = listOfNotNull(base.note, extra).joinToString(" ").ifBlank { null }
        return base.copy(note = combined).also(onUpdate)
    }

    private fun lookupDns(address: String): String? {
        val host = runCatching { InetAddress.getByName(address).hostName }.getOrNull() ?: return null
        if (host.isBlank() || host == address) return null
        return host
    }

    private suspend fun askDevices(context: Context, devices: List<LanDevice>, localAddress: String): Map<String, String> {
        if (devices.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = runCatching { wifi?.createMulticastLock("netmtr-names") }.getOrNull()
            runCatching { lock?.setReferenceCounted(false); lock?.acquire() }
            try {
                val local = InetAddress.getByName(localAddress)
                DatagramSocket(0, local).use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 250
                    val netbios = LanIdentity.netbiosQuery()
                    val group = InetAddress.getByName("224.0.0.251")
                    for (device in devices) {
                        val ip = runCatching { InetAddress.getByName(device.address) }.getOrNull() ?: continue
                        val query = LanIdentity.ptrQuery(device.address)
                        if (query.isNotEmpty()) {
                            runCatching { socket.send(DatagramPacket(query, query.size, ip, 5353)) }
                            runCatching { socket.send(DatagramPacket(query, query.size, ip, 5355)) }
                            runCatching { socket.send(DatagramPacket(query, query.size, group, 5353)) }
                        }
                        runCatching { socket.send(DatagramPacket(netbios, netbios.size, ip, 137)) }
                    }
                    val found = linkedMapOf<String, String>()
                    val deadline = System.nanoTime() + 2_500_000_000L
                    val buffer = ByteArray(1500)
                    while (System.nanoTime() < deadline && found.size < devices.size) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val source = packet.address?.hostAddress ?: continue
                        val data = packet.data.copyOf(packet.length)
                        val name = if (packet.port == 137) {
                            LanIdentity.parseNetbios(data)
                        } else {
                            LanIdentity.hostnames(data).firstOrNull()
                        }
                        if (!name.isNullOrBlank()) found[source] = name
                    }
                    found
                }
            } catch (_: Exception) {
                emptyMap()
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }
    }

    private fun readArp(): String {
        return listOf(
            runCatching { File("/proc/net/arp").readText() }.getOrDefault(""),
            runText(listOf("/system/bin/cat", "/proc/net/arp")),
        ).joinToString("\n")
    }

    private fun readNeigh(interfaceName: String?): String {
        val commands = listOf(
            listOf("/system/bin/ip", "neigh"),
            listOf("/system/bin/ip", "-4", "neigh", "show"),
            listOf("ip", "neigh"),
        ) + if (interfaceName.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(listOf("/system/bin/ip", "neigh", "show", "dev", interfaceName))
        }
        return commands.joinToString("\n") { runText(it) }
    }

    private fun runText(command: List<String>): String {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            val text = process.inputStream.bufferedReader().readText()
            if (!finished) process.destroy()
            text
        }.getOrDefault("")
    }

    @Suppress("DEPRECATION")
    private fun routerMac(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val bssid = runCatching { wifi.connectionInfo?.bssid }.getOrNull() ?: return null
        val mac = bssid.lowercase()
        if (mac == "02:00:00:00:00:00" || mac.count { it == ':' } != 5) return null
        return mac
    }

    @Suppress("DEPRECATION")
    private fun endpoint(context: Context): Endpoint? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lan = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!wifi && !ethernet) return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            readEndpoint(connectivity, network)
        }
        if (lan.isNotEmpty()) return lan.first()
        val active = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(active)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return null
        return readEndpoint(connectivity, active)
    }

    @Suppress("DEPRECATION")
    private fun vpnWithoutLan(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun readEndpoint(connectivity: ConnectivityManager, network: android.net.Network): Endpoint? {
        val link = connectivity.getLinkProperties(network) ?: return null
        val local = link.linkAddresses.firstOrNull { address ->
            val ip = address.address
            ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress
        } ?: return null
        val gateway = link.routes.mapNotNull { route ->
            val address = route.gateway
            if (!route.isDefaultRoute || address !is Inet4Address) null else address.hostAddress
        }.firstOrNull() ?: link.routes.mapNotNull { route ->
            val address = route.gateway
            if (address !is Inet4Address || address.isAnyLocalAddress) null else address.hostAddress
        }.firstOrNull()
        return Endpoint(
            address = (local.address as Inet4Address).hostAddress ?: return null,
            prefix = local.prefixLength,
            gateway = gateway,
            interfaceName = link.interfaceName,
        )
    }

    private data class Endpoint(
        val address: String,
        val prefix: Int,
        val gateway: String?,
        val interfaceName: String?,
    )

    companion object {
        private const val BATCH = 24
    }
}
