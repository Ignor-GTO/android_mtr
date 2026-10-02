package dev.netmtr.app.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

object NetworkInfo {
    fun collect(context: Context): NetworkSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        val link = active?.let { connectivity.getLinkProperties(it) }
        val wifi = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
        }.getOrNull()

        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val base = when {
            caps == null -> "Нет активной сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            vpn -> "VPN"
            else -> "Другая сеть"
        }
        val type = if (vpn && base != "VPN" && base != "Нет активной сети") "$base через VPN" else base

        val gateways = link?.routes.orEmpty().mapNotNull { route ->
            val address = route.gateway?.hostAddress ?: return@mapNotNull null
            if (!route.isDefaultRoute || route.gateway?.isAnyLocalAddress == true) return@mapNotNull null
            address
        }.distinct()
        val gateway = gateways.firstOrNull { !it.contains(':') } ?: gateways.firstOrNull()

        val carrier = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            runCatching {
                context.getSystemService(TelephonyManager::class.java)?.networkOperatorName
            }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return NetworkSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            networkType = type,
            vpn = vpn,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            ssid = cleanSsid(wifi),
            carrier = carrier,
            localAddresses = link?.linkAddresses?.map { it.toString() }.orEmpty(),
            gateway = gateway,
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            downKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            upKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 },
        )
    }

    @Suppress("DEPRECATION")
    private fun cleanSsid(wifi: WifiManager?): String? {
        val raw = runCatching { wifi?.connectionInfo?.ssid }.getOrNull() ?: return null
        val value = raw.trim().trim('"')
        if (value.isBlank() || value.equals("<unknown ssid>", ignoreCase = true) || value == "0x") return null
        return value
    }
}
