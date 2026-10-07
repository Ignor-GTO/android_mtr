package dev.netmtr.app.probe

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class DirectPath(val network: Network, val ipv4: String?)

object RouteBind {
    @Suppress("DEPRECATION")
    fun direct(connectivity: ConnectivityManager?): DirectPath? {
        if (connectivity == null) return null
        val networks = connectivity.allNetworks
        val vpn = networks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (!vpn) return null
        val ranked = networks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val rank = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> return@mapNotNull null
            }
            val link = connectivity.getLinkProperties(network)
            val ipv4 = link?.linkAddresses?.firstOrNull { address ->
                val ip = address.address
                ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress
            }?.address?.hostAddress
            rank to DirectPath(network, ipv4)
        }
        return ranked.minByOrNull { it.first }?.second
    }
}
