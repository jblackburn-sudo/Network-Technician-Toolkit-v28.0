package com.example.networktoolkit.wifiscan

import android.net.wifi.ScanResult
import android.os.Build
import com.example.networktoolkit.SignalStrengthTier
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

enum class WifiBand(val label: String) {
    BAND_2_4_GHZ("2.4 GHz"),
    BAND_5_GHZ("5 GHz"),
    BAND_6_GHZ("6 GHz"),
    UNKNOWN("Unknown")
}

/**
 * Model representing a detected Wi-Fi access point / network from a Wi-Fi scan.
 */
data class WifiNetworkInfo(
    val bssid: String,
    val ssid: String,
    val isHidden: Boolean,
    val displaySsid: String,
    val signalDbm: Int,
    val signalLevelBars: Int, // 0 to 4
    val signalQualityPercent: Int, // 0 to 100%
    val signalQualityLabel: String, // Excellent, Good, Fair, Poor
    val frequencyMhz: Int,
    val band: WifiBand,
    val channelNumber: Int,
    val channelWidth: String,
    val securitySummary: String,
    val isSecure: Boolean,
    val hasWps: Boolean,
    val wifiStandard: String,
    val rawCapabilities: String,
    val isConnected: Boolean = false,
    val estimatedDistanceMeters: Double = 0.0,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val signalTier: SignalStrengthTier
        get() = SignalStrengthTier.fromDbm(signalDbm)

    val signalKindSummary: String
        get() = SignalStrengthTier.formatCompactKind(signalDbm)

    val marginAboveFloorDb: Int
        get() = SignalStrengthTier.marginAboveFloorDb(signalDbm)

    companion object {
        fun fromScanResult(
            scanResult: ScanResult,
            connectedBssid: String? = null
        ): WifiNetworkInfo {
            val rawSsid = scanResult.SSID?.trim() ?: ""
            val isHidden = rawSsid.isEmpty() || rawSsid == "<unknown ssid>" || rawSsid.all { it == '\u0000' }
            val displaySsid = if (isHidden) "[Hidden Network]" else rawSsid

            val bssid = scanResult.BSSID?.uppercase(Locale.US) ?: "00:00:00:00:00:00"
            val isConnected = !connectedBssid.isNullOrBlank() && bssid.equals(connectedBssid, ignoreCase = true)

            val level = scanResult.level
            val signalBars = calculateSignalLevelBars(level)
            val signalPercent = calculateSignalPercent(level)
            val qualityLabel = when {
                level >= -50 -> "Excellent"
                level >= -60 -> "Very Good"
                level >= -70 -> "Good"
                level >= -80 -> "Fair"
                else -> "Poor / Weak"
            }

            val freq = scanResult.frequency
            val band = determineBand(freq)
            val channel = frequencyToChannel(freq)
            val channelWidth = parseChannelWidth(scanResult)
            val (securitySummary, isSecure, hasWps) = parseSecurity(scanResult.capabilities ?: "")
            val wifiStandard = determineWifiStandard(scanResult, freq)
            val distance = estimateDistanceMeters(freq, level)

            return WifiNetworkInfo(
                bssid = bssid,
                ssid = rawSsid,
                isHidden = isHidden,
                displaySsid = displaySsid,
                signalDbm = level,
                signalLevelBars = signalBars,
                signalQualityPercent = signalPercent,
                signalQualityLabel = qualityLabel,
                frequencyMhz = freq,
                band = band,
                channelNumber = channel,
                channelWidth = channelWidth,
                securitySummary = securitySummary,
                isSecure = isSecure,
                hasWps = hasWps,
                wifiStandard = wifiStandard,
                rawCapabilities = scanResult.capabilities ?: "",
                isConnected = isConnected,
                estimatedDistanceMeters = distance
            )
        }

        fun calculateSignalLevelBars(rssiDbm: Int): Int {
            return when {
                rssiDbm >= -55 -> 4
                rssiDbm >= -65 -> 3
                rssiDbm >= -75 -> 2
                rssiDbm >= -85 -> 1
                else -> 0
            }
        }

        fun calculateSignalPercent(rssiDbm: Int): Int {
            // -100 dBm is 0%, -50 dBm is 100%
            val percent = 2 * (rssiDbm + 100)
            return percent.coerceIn(0, 100)
        }

        fun determineBand(freqMhz: Int): WifiBand {
            return when {
                freqMhz in 2400..2495 -> WifiBand.BAND_2_4_GHZ
                freqMhz in 5150..5895 -> WifiBand.BAND_5_GHZ
                freqMhz in 5925..7125 -> WifiBand.BAND_6_GHZ
                else -> WifiBand.UNKNOWN
            }
        }

        fun frequencyToChannel(freqMhz: Int): Int {
            return when {
                freqMhz == 2484 -> 14
                freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
                freqMhz in 5170..5825 -> (freqMhz - 5000) / 5
                freqMhz in 5835..5895 -> (freqMhz - 5000) / 5
                freqMhz == 5935 -> 2
                freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
                else -> 0
            }
        }

        private fun parseChannelWidth(scanResult: ScanResult): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return when (scanResult.channelWidth) {
                    ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
                    ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
                    ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
                    ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
                    ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
                    else -> {
                        // Check if API 33+ has 320MHz
                        if (Build.VERSION.SDK_INT >= 33 && scanResult.channelWidth == 5) {
                            "320 MHz (Wi-Fi 7)"
                        } else {
                            "20 MHz"
                        }
                    }
                }
            }
            return "20 MHz"
        }

        fun parseSecurity(capabilities: String): Triple<String, Boolean, Boolean> {
            val capUpper = capabilities.uppercase(Locale.US)
            val hasWps = capUpper.contains("WPS")

            val securityType = when {
                capUpper.contains("WPA3-SAE") && capUpper.contains("WPA2") -> "WPA2/WPA3 Mixed"
                capUpper.contains("WPA3-SAE") -> "WPA3-Personal"
                capUpper.contains("WPA3-ENTERPRISE") || capUpper.contains("SUITE_B_192") -> "WPA3-Enterprise"
                capUpper.contains("OWE") -> "Enhanced Open (OWE)"
                capUpper.contains("WPA2-PSK") || capUpper.contains("RSN-PSK") -> "WPA2-Personal (PSK)"
                capUpper.contains("WPA2-ENTERPRISE") || capUpper.contains("WPA2-EAP") -> "WPA2-Enterprise"
                capUpper.contains("WPA-PSK") -> "WPA-Personal (PSK)"
                capUpper.contains("WPA-EAP") -> "WPA-Enterprise"
                capUpper.contains("WEP") -> "WEP (Legacy)"
                capUpper.contains("ESS") && !capUpper.contains("WPA") && !capUpper.contains("WEP") && !capUpper.contains("RSN") -> "Open / None"
                else -> "Open"
            }

            val isSecure = !securityType.startsWith("Open") && !securityType.contains("None")
            return Triple(securityType, isSecure, hasWps)
        }

        private fun determineWifiStandard(scanResult: ScanResult, freqMhz: Int): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (scanResult.wifiStandard) {
                    8 -> return "Wi-Fi 7 (802.11be)" // ScanResult.WIFI_STANDARD_11BE
                    ScanResult.WIFI_STANDARD_11AX -> {
                        return if (freqMhz > 5925) "Wi-Fi 6E (802.11ax)" else "Wi-Fi 6 (802.11ax)"
                    }
                    ScanResult.WIFI_STANDARD_11AC -> return "Wi-Fi 5 (802.11ac)"
                    ScanResult.WIFI_STANDARD_11N -> return "Wi-Fi 4 (802.11n)"
                    ScanResult.WIFI_STANDARD_LEGACY -> return "Legacy (802.11a/b/g)"
                }
            }

            // Fallback estimation by frequency band and capabilities
            val cap = scanResult.capabilities ?: ""
            return when {
                freqMhz > 5925 -> "Wi-Fi 6E"
                freqMhz > 5000 -> if (cap.contains("WPA3")) "Wi-Fi 6" else "Wi-Fi 5 (802.11ac)"
                else -> "Wi-Fi 4 (802.11n)"
            }
        }

        fun estimateDistanceMeters(freqMhz: Int, levelDbm: Int): Double {
            return try {
                val exp = (27.55 - (20 * log10(freqMhz.toDouble())) + kotlin.math.abs(levelDbm)) / 20.0
                val distance = 10.0.pow(exp)
                (distance * 10.0).roundToInt() / 10.0
            } catch (e: Exception) {
                0.0
            }
        }
    }
}
