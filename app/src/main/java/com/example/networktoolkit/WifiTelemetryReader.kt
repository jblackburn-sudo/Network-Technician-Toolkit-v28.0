package com.example.networktoolkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Snapshot of live Wi-Fi RF telemetry obtained directly from Android/Linux interfaces.
 * Missing measurements remain `null` and are never estimated or fabricated.
 */
data class WifiRfTelemetry(
    val isWifiEnabled: Boolean,
    val isWifiConnected: Boolean,
    val rssiDbm: Int?,
    val snrDb: Int?,
    val noiseFloorDbm: Int?,
    val ssid: String? = null,
    val bssid: String? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val interfaceName: String? = null,
    val ssidStatus: WifiIdentifierStatus = if (ssid != null) WifiIdentifierStatus.AVAILABLE else WifiIdentifierStatus.UNAVAILABLE,
    val bssidStatus: WifiIdentifierStatus = if (bssid != null) WifiIdentifierStatus.AVAILABLE else WifiIdentifierStatus.UNAVAILABLE
)

data class WifiIdentifierResolution(
    val ssid: String?,
    val bssid: String?,
    val ssidStatus: WifiIdentifierStatus,
    val bssidStatus: WifiIdentifierStatus
)

data class ProcNetWirelessEntry(
    val interfaceName: String,
    val linkQuality: Int?,
    val rssiDbm: Int?,
    val noiseFloorDbm: Int?
)

/**
 * Reads genuine Wi-Fi RSSI (dBm) and SNR (dB) telemetry from Android system services
 * and kernel `/proc/net/wireless` when exposed by the device driver.
 *
 * Strictly avoids estimating SNR from RSSI when background radio noise is unavailable,
 * and applies canonical validation for SSID and BSSID identifiers without fabricating values.
 */
object WifiTelemetryReader {

    private const val PROC_NET_WIRELESS_PATH = "/proc/net/wireless"
    private const val INVALID_RSSI = -127

    private val INVALID_BSSID_SENTINELS = setOf(
        "02:00:00:00:00:00",
        "00:00:00:00:00:00"
    )

    private val NON_SSID_SENTINELS = setOf(
        "<unknown ssid>",
        "unknown ssid",
        "<hidden ssid>",
        "<hidden or unnamed ssid>",
        "<location permission needed for ssid>",
        "unavailable (os restricted / location services disabled)",
        "unavailable (location permission required)"
    )

    /**
     * Normalizes and validates a raw Android Wi-Fi SSID.
     * Strips surrounding quotation marks BEFORE validating emptiness so `""` is rejected.
     * Never fabricates or substitutes status messages as an SSID.
     */
    @JvmStatic
    fun normalizeSsid(rawSsid: String?): String? {
        val trimmed = rawSsid?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val unquoted = if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        if (unquoted.isEmpty()) return null
        if (unquoted.all { it == '\u0000' }) return null
        if (unquoted.lowercase(Locale.US) in NON_SSID_SENTINELS) return null
        return unquoted
    }

    /**
     * Normalizes and validates a raw Android Wi-Fi BSSID.
     * Rejects null, blank, and Android sentinel MAC values (`02:00:00:00:00:00`, `00:00:00:00:00:00`).
     * Never fabricates or reconstructs a BSSID.
     */
    @JvmStatic
    fun normalizeBssid(rawBssid: String?): String? {
        val trimmed = rawBssid?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val canonicalLower = trimmed.lowercase(Locale.US).replace('-', ':')
        if (canonicalLower in INVALID_BSSID_SENTINELS) return null
        if (canonicalLower in setOf("unavailable", "unknown", "n/a", "na", "none")) return null
        return trimmed
    }

    @JvmStatic
    fun hasWifiIdentifierPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun isLocationServicesEnabled(context: Context): Boolean {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun resolveUnavailableStatus(context: Context): WifiIdentifierStatus {
        return when {
            !hasWifiIdentifierPermission(context) -> WifiIdentifierStatus.PERMISSION_REQUIRED
            !isLocationServicesEnabled(context) -> WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED
            else -> WifiIdentifierStatus.UNAVAILABLE
        }
    }

    /**
     * Extracts validated SSID/BSSID identifiers along with separate availability status metadata.
     * Only consults [fallbackWifiInfo] when it does not conflict with [primaryWifiInfo]'s valid BSSID.
     */
    @JvmStatic
    fun extractWifiIdentifiers(
        context: Context,
        primaryWifiInfo: WifiInfo?,
        fallbackWifiInfo: WifiInfo? = null,
        isWifiConnected: Boolean = true
    ): WifiIdentifierResolution {
        if (!isWifiConnected) {
            return WifiIdentifierResolution(
                ssid = null,
                bssid = null,
                ssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                bssidStatus = WifiIdentifierStatus.NOT_APPLICABLE
            )
        }

        val compatibleFallback = when {
            primaryWifiInfo == null -> fallbackWifiInfo
            fallbackWifiInfo == null || fallbackWifiInfo === primaryWifiInfo -> null
            else -> {
                val pBssid = normalizeBssid(primaryWifiInfo.bssid)
                val fBssid = normalizeBssid(fallbackWifiInfo.bssid)
                if (pBssid == null || fBssid == null || pBssid.equals(fBssid, ignoreCase = true)) {
                    fallbackWifiInfo
                } else {
                    null
                }
            }
        }

        val cleanSsid = normalizeSsid(primaryWifiInfo?.ssid)
            ?: normalizeSsid(compatibleFallback?.ssid)
        val cleanBssid = normalizeBssid(primaryWifiInfo?.bssid)
            ?: normalizeBssid(compatibleFallback?.bssid)

        val unavailableStatus by lazy(LazyThreadSafetyMode.NONE) {
            resolveUnavailableStatus(context)
        }

        return WifiIdentifierResolution(
            ssid = cleanSsid,
            bssid = cleanBssid,
            ssidStatus = if (cleanSsid != null) WifiIdentifierStatus.AVAILABLE else unavailableStatus,
            bssidStatus = if (cleanBssid != null) WifiIdentifierStatus.AVAILABLE else unavailableStatus
        )
    }

    @JvmStatic
    @SuppressLint("MissingPermission")
    fun readLiveTelemetry(
        context: Context,
        preferredInterfaceName: String? = null,
        cachedWifiInfo: WifiInfo? = null
    ): WifiRfTelemetry {
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val isWifiEnabled = try {
            wm?.isWifiEnabled ?: false
        } catch (_: Exception) {
            false
        }

        val activeWifiInfo = resolveActiveWifiInfo(cm, wm)
        val wmConnectionInfo = try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo
        } catch (_: Exception) {
            null
        }
        val wifiInfo = activeWifiInfo ?: cachedWifiInfo ?: wmConnectionInfo
        val rawRssi = wifiInfo?.rssi ?: INVALID_RSSI
        val validWifiInfoRssi = rawRssi.takeIf { it in -120..-1 && it != INVALID_RSSI }

        val procEntry = readProcNetWireless(preferredInterfaceName)
        val rssiDbm = validWifiInfoRssi ?: procEntry?.rssiDbm

        val isConnected = isWifiEnabled && (
            rssiDbm != null ||
                (wifiInfo != null && wifiInfo.networkId != -1) ||
                hasActiveWifiTransport(cm)
            )

        if (!isConnected) {
            return WifiRfTelemetry(
                isWifiEnabled = isWifiEnabled,
                isWifiConnected = false,
                rssiDbm = null,
                snrDb = null,
                noiseFloorDbm = null,
                ssid = null,
                bssid = null,
                interfaceName = preferredInterfaceName ?: procEntry?.interfaceName,
                ssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                bssidStatus = WifiIdentifierStatus.NOT_APPLICABLE
            )
        }

        // Determine genuine SNR (dB) without estimation:
        // 1) Check if device WifiInfo exposes an SNR or noise floor getter
        // 2) Otherwise check kernel /proc/net/wireless noise floor (dBm)
        val directWifiInfoSnr = wifiInfo?.let { extractDirectSnrFromWifiInfo(it) }
        val directWifiInfoNoise = wifiInfo?.let { extractDirectNoiseFromWifiInfo(it) }

        val noiseFloorDbm = directWifiInfoNoise ?: procEntry?.noiseFloorDbm
        val computedSnrFromNoise = if (rssiDbm != null && noiseFloorDbm != null && rssiDbm >= noiseFloorDbm) {
            (rssiDbm - noiseFloorDbm).takeIf { it in 0..95 }
        } else {
            null
        }

        val snrDb = directWifiInfoSnr ?: computedSnrFromNoise

        val idResolution = extractWifiIdentifiers(
            context = appContext,
            primaryWifiInfo = wifiInfo,
            fallbackWifiInfo = cachedWifiInfo ?: wmConnectionInfo,
            isWifiConnected = true
        )
        val linkSpeed = wifiInfo?.linkSpeed?.takeIf { it > 0 }
        val frequency = wifiInfo?.frequency?.takeIf { it > 0 }

        return WifiRfTelemetry(
            isWifiEnabled = isWifiEnabled,
            isWifiConnected = true,
            rssiDbm = rssiDbm,
            snrDb = snrDb,
            noiseFloorDbm = noiseFloorDbm,
            ssid = idResolution.ssid,
            bssid = idResolution.bssid,
            linkSpeedMbps = linkSpeed,
            frequencyMhz = frequency,
            interfaceName = preferredInterfaceName ?: procEntry?.interfaceName,
            ssidStatus = idResolution.ssidStatus,
            bssidStatus = idResolution.bssidStatus
        )
    }

    @SuppressLint("MissingPermission")
    fun resolveActiveWifiInfo(
        cm: ConnectivityManager?,
        wm: WifiManager?
    ): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
            try {
                val activeNetwork = cm.activeNetwork
                if (activeNetwork != null) {
                    val activeCaps = cm.getNetworkCapabilities(activeNetwork)
                    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        (activeCaps.transportInfo as? WifiInfo)?.let { return it }
                    }
                    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
                        val underlyingCaps = classification.underlyingNetwork?.let { cm.getNetworkCapabilities(it) }
                        if (underlyingCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                            (underlyingCaps.transportInfo as? WifiInfo)?.let { return it }
                        }
                    }
                }
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        (caps.transportInfo as? WifiInfo)?.let { return it }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return try {
            @Suppress("DEPRECATION")
            wm?.connectionInfo
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasActiveWifiTransport(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        return try {
            cm.allNetworks.any { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractDirectSnrFromWifiInfo(wifiInfo: WifiInfo): Int? {
        for (methodName in listOf("getSnr", "getCurrentSnr")) {
            try {
                val method = wifiInfo.javaClass.getMethod(methodName)
                val value = (method.invoke(wifiInfo) as? Number)?.toInt()
                if (value != null && value in 0..95) {
                    return value
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractDirectNoiseFromWifiInfo(wifiInfo: WifiInfo): Int? {
        for (methodName in listOf("getNoiseFloor", "getNoise", "getNoiseDbm")) {
            try {
                val method = wifiInfo.javaClass.getMethod(methodName)
                val value = (method.invoke(wifiInfo) as? Number)?.toInt()
                if (value != null && value in -120..-40) {
                    return value
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun readProcNetWireless(preferredInterface: String? = null): ProcNetWirelessEntry? {
        return try {
            val file = File(PROC_NET_WIRELESS_PATH)
            if (!file.exists() || !file.canRead()) return null
            parseProcNetWireless(file.readText(), preferredInterface)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses `/proc/net/wireless` output.
     *
     * Example line:
     * `wlan0: 0000   58.  -52.  -95.       0      0      0      0      0        0`
     * Only treats `noise` as valid dBm when within [-120, -40] dBm (rejects 0 / -256 sentinels).
     */
    fun parseProcNetWireless(
        content: String,
        preferredInterface: String? = null
    ): ProcNetWirelessEntry? {
        val entries = content.lineSequence()
            .map { it.trim() }
            .filter { it.contains(":") && !it.startsWith("Inter-") && !it.startsWith("face") }
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex <= 0) return@mapNotNull null
                val iface = line.substring(0, colonIndex).trim()
                val tokens = line.substring(colonIndex + 1).trim().split(Regex("\\s+"))
                if (tokens.size < 4) return@mapNotNull null

                val link = tokens[1].trimEnd('.').toDoubleOrNull()?.roundToInt()
                val levelRaw = tokens[2].trimEnd('.').toDoubleOrNull()?.roundToInt()
                val noiseRaw = tokens[3].trimEnd('.').toDoubleOrNull()?.roundToInt()

                val rssiDbm = levelRaw?.takeIf { it in -120..-1 }
                val noiseDbm = noiseRaw?.takeIf { it in -120..-40 }

                ProcNetWirelessEntry(
                    interfaceName = iface,
                    linkQuality = link,
                    rssiDbm = rssiDbm,
                    noiseFloorDbm = noiseDbm
                )
            }
            .toList()

        if (entries.isEmpty()) return null
        if (!preferredInterface.isNullOrBlank()) {
            entries.firstOrNull { it.interfaceName.equals(preferredInterface, ignoreCase = true) }
                ?.let { return it }
        }
        return entries.firstOrNull {
            it.interfaceName.startsWith("wlan", ignoreCase = true) ||
                it.interfaceName.startsWith("wifi", ignoreCase = true)
        } ?: entries.first()
    }
}
