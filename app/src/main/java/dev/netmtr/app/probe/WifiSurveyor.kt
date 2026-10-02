package dev.netmtr.app.probe

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object WifiSurveyor {
    suspend fun collect(context: Context): WifiSurvey {
        val app = context.applicationContext
        if (!permitted(app)) {
            return WifiSurvey.failed("Нет разрешения на сканирование Wi‑Fi. Разрешите устройства поблизости или геолокацию.")
        }
        val wifi = app.getSystemService(WifiManager::class.java)
            ?: return WifiSurvey.failed("Служба Wi‑Fi недоступна.")
        if (!wifi.isWifiEnabled) {
            return WifiSurvey.failed("Wi‑Fi выключен.")
        }
        return try {
            val own = readOwn(wifi)
            val (results, cached) = withContext(Dispatchers.Main) { readScan(app, wifi) }
            val points = results.map { it.toAp() }
            val channels = WifiAnalyzer.analyze(points, own.frequencyMhz, own.bssid)
            val note = when {
                cached && points.isEmpty() -> "Новое сканирование ограничено системой, а сохранённых сетей нет. Включите геолокацию и повторите."
                cached -> "Система ограничила новый запрос. Показаны последние увиденные сети."
                points.isEmpty() -> "Сети не видны. Включите геолокацию и повторите сканирование."
                else -> "Диапазон 2.4 ГГц разобран по всем каналам. На 5 и 6 ГГц перечислены только занятые частоты и частота нашего роутера."
            }
            WifiSurvey(
                connectedSsid = own.ssid,
                connectedBssid = own.bssid,
                connectedFrequencyMhz = own.frequencyMhz,
                connectedChannel = own.frequencyMhz?.let(WifiAnalyzer::channel)?.takeIf { it > 0 },
                connectedBand = own.frequencyMhz?.let(WifiAnalyzer::band),
                channels = channels,
                note = note,
                error = null,
            )
        } catch (error: SecurityException) {
            WifiSurvey.failed(error.message ?: "Нет доступа к результатам сканирования Wi‑Fi.")
        }
    }

    private fun permitted(context: Context): Boolean {
        val fine = granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            val nearby = granted(context, android.Manifest.permission.NEARBY_WIFI_DEVICES)
            return nearby || fine
        }
        return fine
    }

    private fun granted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun readOwn(wifi: WifiManager): OwnLink {
        val info = wifi.connectionInfo
        if (info == null || info.networkId == -1 || info.frequency <= 0) {
            return OwnLink(null, null, null)
        }
        val raw = info.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")
        val ssid = raw?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
        val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        return OwnLink(ssid, bssid, info.frequency)
    }

    @SuppressLint("MissingPermission")
    private suspend fun readScan(context: Context, wifi: WifiManager): Pair<List<ScanResult>, Boolean> {
        val scanned = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (continuation.isActive) continuation.resume(wifi.scanResults.orEmpty() to false)
                    }
                }
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                continuation.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                @Suppress("DEPRECATION")
                val started = wifi.startScan()
                if (!started && continuation.isActive) {
                    runCatching { context.unregisterReceiver(receiver) }
                    continuation.resume(wifi.scanResults.orEmpty() to true)
                }
            }
        }
        return scanned ?: (wifi.scanResults.orEmpty() to true)
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toAp(): VisibleAp {
        val width = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
            else -> 20
        }
        val center = if (centerFreq0 > 0) centerFreq0 else frequency
        val name = SSID?.trim()?.removePrefix("\"")?.removeSuffix("\"").orEmpty()
        return VisibleAp(
            ssid = name,
            bssid = BSSID.orEmpty(),
            frequencyMhz = frequency,
            levelDbm = level,
            widthMhz = width,
            centerMhz = center,
        )
    }

    private data class OwnLink(val ssid: String?, val bssid: String?, val frequencyMhz: Int?)
}
