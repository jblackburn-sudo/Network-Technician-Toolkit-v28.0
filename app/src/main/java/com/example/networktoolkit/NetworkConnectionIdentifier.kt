package com.example.networktoolkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Standardized network connection identification and classification utility based
 * exclusively on Android ConnectivityManager, NetworkCapabilities, LinkProperties,
 * and platform-safe Telephony/Wifi APIs.
 *
 * Strictly avoids inferring connection types from interface names (e.g. wlan0, rmnet, eth0),
 * fabricating missing interface names, or making unverified physical hardware claims.
 */
object NetworkConnectionIdentifier {

    const val CONNECTION_WIFI = "Wi-Fi Network Connection"
    const val CONNECTION_MOBILE_DATA = "Mobile Data Connection"
    const val CONNECTION_ETHERNET = "Ethernet Network Connection"
    const val CONNECTION_BLUETOOTH = "Bluetooth Network Connection"
    const val CONNECTION_VPN = "VPN Connection"
    const val CONNECTION_UNKNOWN = "Unknown Connection Type"
    const val CONNECTION_DISCONNECTED = "Disconnected"

    data class ActiveNetworkClassification(
        val isConnected: Boolean,
        val isVpnActive: Boolean,
        val isWifi: Boolean,
        val isCellular: Boolean,
        val isEthernet: Boolean,
        val isBluetooth: Boolean,
        val connectionType: String,
        val networkTransport: String,
        val underlyingTransport: String?,
        val vpnNetwork: Network?,
        val underlyingNetwork: Network?
    )

    data class CellularTelemetry(
        val networkType: String?,
        val operatorName: String?
    )

    /**
     * Inspects [ConnectivityManager.activeNetwork] and any validated non-VPN underlying network
     * without assuming Wi-Fi priority over active Cellular/Ethernet/Bluetooth links.
     */
    @JvmStatic
    fun inspectActiveConnectivity(cm: ConnectivityManager?): ActiveNetworkClassification {
        if (cm == null) {
            return ActiveNetworkClassification(
                isConnected = false,
                isVpnActive = false,
                isWifi = false,
                isCellular = false,
                isEthernet = false,
                isBluetooth = false,
                connectionType = CONNECTION_DISCONNECTED,
                networkTransport = "Unavailable",
                underlyingTransport = null,
                vpnNetwork = null,
                underlyingNetwork = null
            )
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            return ActiveNetworkClassification(
                isConnected = false,
                isVpnActive = false,
                isWifi = false,
                isCellular = false,
                isEthernet = false,
                isBluetooth = false,
                connectionType = CONNECTION_DISCONNECTED,
                networkTransport = "None",
                underlyingTransport = null,
                vpnNetwork = null,
                underlyingNetwork = null
            )
        }

        val activeCaps = runCatching { cm.getNetworkCapabilities(activeNetwork) }.getOrNull()
        val isVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        var vpnNetwork: Network? = if (isVpn) activeNetwork else null
        var underlyingNetwork: Network? = if (!isVpn) activeNetwork else null

        if (isVpn) {
            val allNetworks = runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks
            }.getOrNull() ?: emptyArray()

            // Prefer validated internet-capable non-VPN transport reported by ConnectivityManager
            var fallbackCandidate: Network? = null
            for (net in allNetworks) {
                val c = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (vpnNetwork == null) vpnNetwork = net
                    continue
                }
                val hasKnownTransport = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (!hasKnownTransport) continue

                val hasInternet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isValidated && hasInternet) {
                    underlyingNetwork = net
                    break
                } else if (hasInternet && fallbackCandidate == null) {
                    fallbackCandidate = net
                } else if (underlyingNetwork == null && fallbackCandidate == null) {
                    fallbackCandidate = net
                }
            }
            if (underlyingNetwork == null) {
                underlyingNetwork = fallbackCandidate
            }
        }

        val targetCaps = if (isVpn) {
            underlyingNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        } else {
            activeCaps
        }

        val isWifi = targetCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = targetCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isEthernet = targetCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isBluetooth = targetCaps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true

        val underlyingType = classifyNonVpnConnectionType(
            hasWifi = isWifi,
            hasCellular = isCellular,
            hasEthernet = isEthernet,
            hasBluetooth = isBluetooth
        )
        val underlyingTransport = classifyTransportName(
            hasWifi = isWifi,
            hasCellular = isCellular,
            hasEthernet = isEthernet,
            hasBluetooth = isBluetooth
        )

        val connectionType = classifyConnectionType(
            isConnected = true,
            hasVpn = isVpn,
            hasWifi = isWifi,
            hasCellular = isCellular,
            hasEthernet = isEthernet,
            hasBluetooth = isBluetooth
        )

        val transportLabel = buildNetworkTransportSummary(
            hasVpn = isVpn,
            hasWifi = isWifi,
            hasCellular = isCellular,
            hasEthernet = isEthernet,
            hasBluetooth = isBluetooth
        )

        return ActiveNetworkClassification(
            isConnected = true,
            isVpnActive = isVpn,
            isWifi = isWifi,
            isCellular = isCellular,
            isEthernet = isEthernet,
            isBluetooth = isBluetooth,
            connectionType = connectionType,
            networkTransport = transportLabel,
            underlyingTransport = if (isVpn) underlyingType else null,
            vpnNetwork = vpnNetwork,
            underlyingNetwork = underlyingNetwork
        )
    }

    /**
     * Pure standardized connection-type classifier matching professional Android/networking terminology.
     */
    @JvmStatic
    fun classifyConnectionType(
        isConnected: Boolean,
        hasVpn: Boolean,
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasBluetooth: Boolean = false
    ): String {
        if (!isConnected) return CONNECTION_DISCONNECTED

        val baseType = classifyNonVpnConnectionType(
            hasWifi = hasWifi,
            hasCellular = hasCellular,
            hasEthernet = hasEthernet,
            hasBluetooth = hasBluetooth
        )

        return if (hasVpn) {
            if (baseType != null) {
                "$CONNECTION_VPN (over $baseType)"
            } else {
                CONNECTION_VPN
            }
        } else {
            baseType ?: CONNECTION_UNKNOWN
        }
    }

    @JvmStatic
    fun classifyNonVpnConnectionType(
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasBluetooth: Boolean = false
    ): String? {
        return when {
            hasWifi -> CONNECTION_WIFI
            hasCellular -> CONNECTION_MOBILE_DATA
            hasEthernet -> CONNECTION_ETHERNET
            hasBluetooth -> CONNECTION_BLUETOOTH
            else -> null
        }
    }

    @JvmStatic
    fun classifyTransportName(
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasBluetooth: Boolean = false
    ): String? {
        return when {
            hasWifi -> "Wi-Fi (TRANSPORT_WIFI)"
            hasCellular -> "Cellular (TRANSPORT_CELLULAR)"
            hasEthernet -> "Ethernet (TRANSPORT_ETHERNET)"
            hasBluetooth -> "Bluetooth (TRANSPORT_BLUETOOTH)"
            else -> null
        }
    }

    @JvmStatic
    fun buildNetworkTransportSummary(
        hasVpn: Boolean,
        hasWifi: Boolean,
        hasCellular: Boolean,
        hasEthernet: Boolean,
        hasBluetooth: Boolean = false
    ): String {
        val underlying = classifyTransportName(
            hasWifi = hasWifi,
            hasCellular = hasCellular,
            hasEthernet = hasEthernet,
            hasBluetooth = hasBluetooth
        )
        return when {
            hasVpn && underlying != null -> "VPN (TRANSPORT_VPN) over $underlying"
            hasVpn -> "VPN (TRANSPORT_VPN)"
            underlying != null -> underlying
            else -> "Unknown Network Transport"
        }
    }

    /**
     * Converts an IPv4 CIDR prefix length (0..32) into a standard dotted-decimal subnet mask.
     */
    @JvmStatic
    fun prefixLengthToIpv4SubnetMask(prefixLength: Int): String? {
        if (prefixLength !in 0..32) return null
        val mask: Int = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        val o1 = (mask ushr 24) and 0xFF
        val o2 = (mask ushr 16) and 0xFF
        val o3 = (mask ushr 8) and 0xFF
        val o4 = mask and 0xFF
        return "$o1.$o2.$o3.$o4"
    }

    /**
     * Converts a Wi-Fi center frequency (MHz) to its standard IEEE 802.11 channel number.
     */
    @JvmStatic
    fun frequencyToWifiChannel(freqMhz: Int): Int? {
        return when {
            freqMhz == 2484 -> 14
            freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
            freqMhz in 5160..5895 -> (freqMhz - 5000) / 5
            freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
            else -> null
        }
    }

    @JvmStatic
    fun formatFrequencyBand(freqMhz: Int): String {
        return when {
            freqMhz in 2400..2495 -> "2.4 GHz"
            freqMhz in 5150..5895 -> "5 GHz"
            freqMhz in 5925..7125 -> "6 GHz (Wi-Fi 6E/7)"
            else -> "$freqMhz MHz"
        }
    }

    /**
     * Reads IEEE 802.11 Wi-Fi standard from [WifiInfo] on Android 11+ (API 30+).
     * Returns null when not supported or unknown — never estimates from link speed.
     */
    @JvmStatic
    fun formatWifiStandard(wifiInfo: WifiInfo?): String? {
        if (wifiInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (wifiInfo.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g (Legacy)"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 / 6E (802.11ax)"
            8 -> "Wi-Fi 7 (802.11be)" // ScanResult.WIFI_STANDARD_11BE (API 33+)
            else -> null
        }
    }

    /**
     * Reads cellular operator name and data network type safely without requiring additional permissions.
     * Catches SecurityException if OEM/Android restricts TelephonyManager.dataNetworkType.
     */
    @JvmStatic
    fun readCellularTelemetry(context: Context): CellularTelemetry {
        val tm = runCatching {
            context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull() ?: return CellularTelemetry(networkType = null, operatorName = null)

        val operatorName = runCatching {
            tm.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val hasPhoneStatePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasBasicPhonePerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_BASIC_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

        val rawType: Int? = if (hasPhoneStatePerm || hasBasicPhonePerm) {
            readDataNetworkTypeSafe(tm)
        } else {
            null
        }

        val typeLabel = when (rawType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            19 -> "4G LTE-CA" // NETWORK_TYPE_LTE_CA
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi Calling)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "3G HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSUPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD -> "3G CDMA / EVDO"
            TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            else -> null
        }

        return CellularTelemetry(
            networkType = typeLabel,
            operatorName = operatorName
        )
    }

    @SuppressLint("MissingPermission")
    private fun readDataNetworkTypeSafe(tm: TelephonyManager): Int? {
        return try {
            tm.dataNetworkType
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun getJavaConnectionType(cm: ConnectivityManager?): String {
        return inspectActiveConnectivity(cm).connectionType
    }

    @JvmStatic
    fun getJavaTransportSummary(cm: ConnectivityManager?): String {
        return inspectActiveConnectivity(cm).networkTransport
    }

    @JvmStatic
    fun resolveInterfaceName(linkProps: LinkProperties?): String? {
        return linkProps?.interfaceName?.trim()?.takeIf { it.isNotEmpty() }
    }
}
