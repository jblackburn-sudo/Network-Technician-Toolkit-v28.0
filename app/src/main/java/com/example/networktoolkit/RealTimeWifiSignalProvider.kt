package com.example.networktoolkit

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Threshold reference points from -50 dBm (Excellent) to -90 dBm (Poor)
 * shown on the real-time Wi-Fi signal fluctuation graph.
 */
enum class SignalGraphThreshold(
    val dbm: Int,
    val label: String,
    val hexColor: Long
) {
    EXCELLENT(-50, "Excellent (-50 dBm)", 0xFF00E676),
    GOOD(-60, "Good (-60 dBm)", 0xFF00E5FF),
    FAIR(-70, "Fair (-70 dBm)", 0xFFFFD600),
    WEAK(-80, "Weak (-80 dBm)", 0xFFFF9100),
    POOR(-90, "Poor (-90 dBm)", 0xFFFF1744)
}

/**
 * Single timestamped Wi-Fi signal measurement from the real-time provider.
 */
data class WifiSignalDataPoint(
    val sequence: Int,
    val timestampMs: Long,
    val rssiDbm: Int,
    val ssid: String? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null
)

/**
 * Reactive state emitted by [RealTimeWifiSignalProvider].
 */
data class RealTimeWifiGraphState(
    val samples: List<WifiSignalDataPoint> = emptyList(),
    val currentRssiDbm: Int? = null,
    val minDbm: Int? = null,
    val maxDbm: Int? = null,
    val avgDbm: Double? = null,
    val fluctuationStdDevDb: Double = 0.0,
    val ssid: String? = null,
    val bssid: String? = null,
    val frequencyMhz: Int? = null,
    val linkSpeedMbps: Int? = null,
    val isWifiEnabled: Boolean = true,
    val isWifiConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val sampleIntervalMs: Long = 1000L,
    val statusMessage: String = "Initializing real-time signal monitor..."
)

/**
 * Real-time data provider that streams live Wi-Fi RSSI signal fluctuations from
 * Android's [ConnectivityManager] and [WifiManager] over a rolling time window.
 */
class RealTimeWifiSignalProvider(
    context: Context,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES
) {
    companion object {
        const val DEFAULT_MAX_SAMPLES = 60
        const val GRAPH_CEILING_DBM = -30
        const val GRAPH_FLOOR_DBM = -100
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var latestCallbackWifiInfo: WifiInfo? = null
    private var sequenceCounter = 0

    private val _state = MutableStateFlow(RealTimeWifiGraphState())
    val state: StateFlow<RealTimeWifiGraphState> = _state.asStateFlow()

    /**
     * Starts real-time streaming from system Wi-Fi telemetry.
     */
    fun start() {
        registerWifiNetworkCallback()
        if (pollingJob?.isActive == true) return

        _state.value = _state.value.copy(isStreaming = true)
        pollingJob = scope.launch {
            while (isActive) {
                if (!_state.value.isPaused) {
                    sampleFromDevice()
                }
                delay(_state.value.sampleIntervalMs)
            }
        }
    }

    /**
     * Stops streaming and releases system network callbacks.
     */
    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        unregisterWifiNetworkCallback()
        _state.value = _state.value.copy(isStreaming = false)
    }

    /**
     * Permanently disposes provider coroutine scope.
     */
    fun dispose() {
        stop()
        scope.cancel()
    }

    fun togglePause() {
        val nextPaused = !_state.value.isPaused
        _state.value = _state.value.copy(
            isPaused = nextPaused,
            statusMessage = if (nextPaused) "Stream paused" else "Streaming live Wi-Fi RSSI"
        )
    }

    fun setSampleIntervalMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(250L, 5000L)
        _state.value = _state.value.copy(sampleIntervalMs = clamped)
    }

    fun clearHistory() {
        sequenceCounter = 0
        _state.value = _state.value.copy(
            samples = emptyList(),
            minDbm = null,
            maxDbm = null,
            avgDbm = null,
            fluctuationStdDevDb = 0.0
        )
    }

    @SuppressLint("MissingPermission")
    fun sampleFromDevice() {
        val wm = wifiManager
        val isWifiOn = try {
            wm?.isWifiEnabled ?: false
        } catch (_: Exception) {
            false
        }

        if (!isWifiOn) {
            _state.value = _state.value.copy(
                isWifiEnabled = false,
                isWifiConnected = false,
                currentRssiDbm = null,
                statusMessage = "Wi-Fi radio is disabled"
            )
            return
        }

        val wifiInfo = resolveActiveWifiInfo()
        val rssi = wifiInfo?.rssi ?: -127
        if (wifiInfo == null || rssi == -127 || rssi == 0) {
            _state.value = _state.value.copy(
                isWifiEnabled = true,
                isWifiConnected = false,
                currentRssiDbm = null,
                ssid = null,
                bssid = null,
                frequencyMhz = null,
                linkSpeedMbps = null,
                statusMessage = "No active Wi-Fi connection detected"
            )
            return
        }

        val cleanSsid = wifiInfo.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val cleanBssid = wifiInfo.bssid
            ?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        val speed = wifiInfo.linkSpeed.takeIf { it > 0 }
        val freq = wifiInfo.frequency.takeIf { it > 0 }

        pushSample(
            rssiDbm = rssi,
            ssid = cleanSsid,
            bssid = cleanBssid,
            linkSpeedMbps = speed,
            frequencyMhz = freq
        )
    }

    /**
     * Pushes a new RSSI reading into the rolling real-time buffer and recomputes
     * min, max, average, and fluctuation standard deviation.
     */
    fun pushSample(
        rssiDbm: Int,
        ssid: String? = _state.value.ssid,
        bssid: String? = _state.value.bssid,
        linkSpeedMbps: Int? = _state.value.linkSpeedMbps,
        frequencyMhz: Int? = _state.value.frequencyMhz,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val clampedRssi = rssiDbm.coerceIn(GRAPH_FLOOR_DBM, GRAPH_CEILING_DBM)
        sequenceCounter += 1

        val point = WifiSignalDataPoint(
            sequence = sequenceCounter,
            timestampMs = timestampMs,
            rssiDbm = clampedRssi,
            ssid = ssid,
            linkSpeedMbps = linkSpeedMbps,
            frequencyMhz = frequencyMhz
        )

        val updated = (_state.value.samples + point).takeLast(maxSamples)
        val rssiValues = updated.map { it.rssiDbm }
        val minVal = rssiValues.minOrNull()
        val maxVal = rssiValues.maxOrNull()
        val avgVal = if (rssiValues.isNotEmpty()) rssiValues.average() else null
        val stdDev = if (rssiValues.size >= 2 && avgVal != null) {
            val variance = rssiValues.sumOf { (it - avgVal).pow(2.0) } / rssiValues.size
            sqrt(variance)
        } else {
            0.0
        }

        _state.value = _state.value.copy(
            samples = updated,
            currentRssiDbm = clampedRssi,
            minDbm = minVal,
            maxDbm = maxVal,
            avgDbm = avgVal,
            fluctuationStdDevDb = stdDev,
            ssid = ssid,
            bssid = bssid,
            frequencyMhz = frequencyMhz,
            linkSpeedMbps = linkSpeedMbps,
            isWifiEnabled = true,
            isWifiConnected = true,
            statusMessage = "Live Wi-Fi stream (${updated.size}s window)"
        )
    }

    @SuppressLint("MissingPermission")
    private fun resolveActiveWifiInfo(): WifiInfo? {
        latestCallbackWifiInfo?.let { return it }

        val cm = connectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
            try {
                val activeNetwork = cm.activeNetwork
                val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    (caps.transportInfo as? WifiInfo)?.let { return it }
                }
            } catch (_: Exception) {
            }
        }

        return try {
            @Suppress("DEPRECATION")
            wifiManager?.connectionInfo
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerWifiNetworkCallback() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        latestCallbackWifiInfo = networkCapabilities.transportInfo as? WifiInfo
                    }
                }

                override fun onLost(network: Network) {
                    latestCallbackWifiInfo = null
                }
            }
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (_: Exception) {
            // Permission or platform restriction fallback to WifiManager polling
        }
    }

    private fun unregisterWifiNetworkCallback() {
        val cm = connectivityManager ?: return
        val cb = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
        latestCallbackWifiInfo = null
    }
}
