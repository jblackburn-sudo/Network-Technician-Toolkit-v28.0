package com.example.networktoolkit.wifiscan

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networktoolkit.WifiPrivacyPreferences
import com.example.networktoolkit.WifiPrivacySettings
import com.example.networktoolkit.data.WifiReportExportPrivacyChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WifiSortOrder(val label: String) {
    SIGNAL_DESC("Signal (Strongest)"),
    SSID_ASC("SSID (A-Z)"),
    CHANNEL_ASC("Channel (Low-High)"),
    FREQUENCY_ASC("Frequency")
}

data class ChannelOccupancy(
    val channel: Int,
    val count: Int,
    val primaryBand: WifiBand
)

/**
 * ViewModel managing the state, filtering, sorting, auto-scanning, and report export for Wi-Fi scanning.
 */
class WifiScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = WifiScannerEngine(application)

    val rawState: StateFlow<WifiScannerState> = engine.state

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedBandFilter = MutableStateFlow("ALL")
    val selectedBandFilter: StateFlow<String> = _selectedBandFilter.asStateFlow()

    private val _selectedSecurityFilter = MutableStateFlow("ALL")
    val selectedSecurityFilter: StateFlow<String> = _selectedSecurityFilter.asStateFlow()

    private val _selectedSortOrder = MutableStateFlow(WifiSortOrder.SIGNAL_DESC)
    val selectedSortOrder: StateFlow<WifiSortOrder> = _selectedSortOrder.asStateFlow()

    private val _isAutoScanEnabled = MutableStateFlow(false)
    val isAutoScanEnabled: StateFlow<Boolean> = _isAutoScanEnabled.asStateFlow()

    private var autoScanJob: Job? = null

    private val privacySettingsFlow: Flow<WifiPrivacySettings> =
        WifiPrivacyPreferences.settingsFlow(application)

    // Combined filtered and sorted networks flow (respecting independent SSID & BSSID visibility gates)
    @Suppress("UNCHECKED_CAST")
    val filteredNetworks: StateFlow<List<WifiNetworkInfo>> = combine(
        engine.state,
        _searchQuery,
        _selectedBandFilter,
        _selectedSecurityFilter,
        _selectedSortOrder,
        privacySettingsFlow
    ) { args ->
        val state = args[0] as WifiScannerState
        val query = args[1] as String
        val bandFilter = args[2] as String
        val secFilter = args[3] as String
        val sortOrder = args[4] as WifiSortOrder
        val privacy = args[5] as WifiPrivacySettings

        var list = state.networks

        // 1. Search Query filter (only matches SSID if showSsid, only matches BSSID if showBssid)
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.US)
            list = list.filter { net ->
                val matchSsid = privacy.showSsid && net.displaySsid.lowercase(Locale.US).contains(q)
                val matchBssid = privacy.showBssid && net.bssid.lowercase(Locale.US).contains(q)
                val matchChannel = net.channelNumber.toString() == q
                matchSsid || matchBssid || matchChannel
            }
        }

        // 2. Band Filter
        when (bandFilter) {
            "2.4 GHz" -> list = list.filter { it.band == WifiBand.BAND_2_4_GHZ }
            "5 GHz" -> list = list.filter { it.band == WifiBand.BAND_5_GHZ }
            "6 GHz" -> list = list.filter { it.band == WifiBand.BAND_6_GHZ }
            "Hidden" -> list = list.filter { it.isHidden }
        }

        // 3. Security Filter
        when (secFilter) {
            "Open" -> list = list.filter { !it.isSecure }
            "Secure" -> list = list.filter { it.isSecure }
            "WPS" -> list = list.filter { it.hasWps }
        }

        // 4. Sorting (do not reveal hidden SSID alphabetical order when showSsid is OFF)
        when (sortOrder) {
            WifiSortOrder.SIGNAL_DESC -> list.sortedWith(
                compareByDescending<WifiNetworkInfo> { it.isConnected }
                    .thenByDescending { it.signalDbm }
            )
            WifiSortOrder.SSID_ASC -> if (privacy.showSsid) {
                list.sortedWith(
                    compareByDescending<WifiNetworkInfo> { it.isConnected }
                        .thenBy { it.displaySsid.lowercase(Locale.US) }
                )
            } else {
                list.sortedWith(
                    compareByDescending<WifiNetworkInfo> { it.isConnected }
                        .thenByDescending { it.signalDbm }
                )
            }
            WifiSortOrder.CHANNEL_ASC -> list.sortedWith(
                compareByDescending<WifiNetworkInfo> { it.isConnected }
                    .thenBy { it.channelNumber }
                    .thenByDescending { it.signalDbm }
            )
            WifiSortOrder.FREQUENCY_ASC -> list.sortedWith(
                compareByDescending<WifiNetworkInfo> { it.isConnected }
                    .thenBy { it.frequencyMhz }
                    .thenByDescending { it.signalDbm }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        startScan()
    }

    fun startScan() {
        engine.startScan()
    }

    fun refreshHardwareStatus() {
        engine.updateHardwareStatus()
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun onBandFilterSelected(filter: String) {
        _selectedBandFilter.value = filter
    }

    fun onSecurityFilterSelected(filter: String) {
        _selectedSecurityFilter.value = filter
    }

    fun onSortOrderSelected(order: WifiSortOrder) {
        _selectedSortOrder.value = order
    }

    fun toggleAutoScan() {
        val next = !_isAutoScanEnabled.value
        _isAutoScanEnabled.value = next

        autoScanJob?.cancel()
        if (next) {
            autoScanJob = viewModelScope.launch {
                while (isActive) {
                    startScan()
                    delay(8000) // Scan every 8 seconds to respect Android throttling
                }
            }
        }
    }

    fun getChannelOccupancy(networks: List<WifiNetworkInfo>): List<ChannelOccupancy> {
        return networks.groupBy { it.channelNumber }
            .map { (ch, apList) ->
                ChannelOccupancy(
                    channel = ch,
                    count = apList.size,
                    primaryBand = apList.firstOrNull()?.band ?: WifiBand.BAND_2_4_GHZ
                )
            }
            .sortedBy { it.channel }
    }

    // ==========================================
    // REPORT GENERATION AND SHARING
    // ==========================================

    fun generateTextReport(): String {
        val networks = rawState.value.networks
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = sdf.format(Date())

        sb.append("=================================================================\n")
        sb.append("          WI-FI SITE SURVEY & ACCESS POINT SCAN REPORT           \n")
        sb.append("=================================================================\n")
        sb.append("Scan Timestamp     : ").append(now).append("\n")
        sb.append("Device Platform    : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
        sb.append("Total Networks     : ").append(networks.size).append("\n")
        sb.append("Connected SSID     : ").append(rawState.value.connectedSsid ?: "None").append("\n")
        sb.append("Connected BSSID    : ").append(rawState.value.connectedBssid ?: "None").append("\n")

        val hiddenCount = networks.count { it.isHidden }
        val openCount = networks.count { !it.isSecure }
        val count24 = networks.count { it.band == WifiBand.BAND_2_4_GHZ }
        val count5 = networks.count { it.band == WifiBand.BAND_5_GHZ }
        val count6 = networks.count { it.band == WifiBand.BAND_6_GHZ }

        sb.append(String.format(Locale.US, "Band Distribution  : 2.4 GHz: %d | 5 GHz: %d | 6 GHz: %d\n", count24, count5, count6))
        sb.append(String.format(Locale.US, "Security Breakdown : Secure: %d | Open: %d | Hidden: %d\n", networks.size - openCount, openCount, hiddenCount))
        sb.append(String.format(Locale.US, "RF Scale Bounds    : Floor: %d dBm | Ceiling: %d dBm\n",
            com.example.networktoolkit.SignalStrengthTier.OVERALL_FLOOR_DBM,
            com.example.networktoolkit.SignalStrengthTier.OVERALL_CEILING_DBM))
        sb.append("=================================================================\n\n")

        if (networks.isEmpty()) {
            sb.append("No Wi-Fi access points discovered during this scan.\n")
        } else {
            sb.append(String.format(
                Locale.US,
                "%-4s %-22s %-17s %-10s %-24s %-5s %-7s %-16s\n",
                "#", "SSID", "BSSID (MAC)", "RSSI", "SIGNAL KIND (TIER)", "CH", "BAND", "SECURITY"
            ))
            sb.append("---- ---------------------- ----------------- ---------- ------------------------ ----- ------- ----------------\n")

            networks.forEachIndexed { index, net ->
                val connPrefix = if (net.isConnected) "*" else " "
                val ssidDisplay = if (net.isHidden) "[HIDDEN SSID]" else net.ssid.take(22)
                val tier = net.signalTier
                val kindCol = "${tier.label} (${tier.tierFloorDbm}..${tier.tierCeilingDbm})"
                sb.append(String.format(
                    Locale.US,
                    "%s%02d %-22s %-17s %4d dBm  %-24s %3d  %-7s %-16s\n",
                    connPrefix,
                    index + 1,
                    ssidDisplay,
                    net.bssid,
                    net.signalDbm,
                    kindCol.take(24),
                    net.channelNumber,
                    net.band.label,
                    net.securitySummary.take(16)
                ))
            }
            sb.append("\n* Indicates current active Wi-Fi association.\n")
        }

        sb.append("\n=================================================================\n")
        sb.append("                 END OF WI-FI SCANNER REPORT                     \n")
        sb.append("=================================================================\n")
        return sb.toString()
    }

    fun generateJsonReport(): String {
        val root = JSONObject()
        val networks = rawState.value.networks
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        root.put("reportTitle", "Wi-Fi Site Survey & Access Point Scan")
        root.put("timestamp", now)
        root.put("dateFormatted", sdf.format(Date(now)))
        root.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        root.put("totalAccessPoints", networks.size)
        root.put("connectedSsid", rawState.value.connectedSsid ?: "")
        root.put("connectedBssid", rawState.value.connectedBssid ?: "")
        root.put("rfFloorDbm", com.example.networktoolkit.SignalStrengthTier.OVERALL_FLOOR_DBM)
        root.put("rfCeilingDbm", com.example.networktoolkit.SignalStrengthTier.OVERALL_CEILING_DBM)

        val array = JSONArray()
        for (net in networks) {
            val tier = net.signalTier
            val obj = JSONObject().apply {
                put("bssid", net.bssid)
                put("ssid", net.ssid)
                put("isHidden", net.isHidden)
                put("displaySsid", net.displaySsid)
                put("signalDbm", net.signalDbm)
                put("signalKind", tier.label)
                put("signalCeilingFloorKind", tier.ceilingFloorKind)
                put("tierFloorDbm", tier.tierFloorDbm)
                put("tierCeilingDbm", tier.tierCeilingDbm)
                put("signalQualityPercent", net.signalQualityPercent)
                put("signalQualityLabel", net.signalQualityLabel)
                put("frequencyMhz", net.frequencyMhz)
                put("band", net.band.label)
                put("channel", net.channelNumber)
                put("channelWidth", net.channelWidth)
                put("security", net.securitySummary)
                put("isSecure", net.isSecure)
                put("hasWps", net.hasWps)
                put("standard", net.wifiStandard)
                put("capabilities", net.rawCapabilities)
                put("isConnected", net.isConnected)
                put("distanceEstimateMeters", net.estimatedDistanceMeters)
            }
            array.put(obj)
        }
        root.put("accessPoints", array)

        return root.toString(2)
    }

    fun collectSensitiveIdentifiers(context: Context): com.example.networktoolkit.data.SensitiveWifiIdentifiers {
        val state = rawState.value
        val ssids = buildList {
            add(state.connectedSsid)
            state.networks.forEach { if (!it.isHidden) add(it.ssid) }
        }
        val bssids = buildList {
            add(state.connectedBssid)
            state.networks.forEach { add(it.bssid) }
        }
        return com.example.networktoolkit.data.TextReportExportManager.collectIdentifiers(
            context = context,
            extraSsids = ssids,
            extraBssids = bssids
        )
    }

    fun getDefaultTxtFilename(): String {
        return com.example.networktoolkit.data.TextReportExportManager.suggestFilename("WiFi_Scanner_Report", "rtf")
    }

    @JvmOverloads
    fun createShareIntent(
        context: Context,
        isJson: Boolean,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val extension = if (isJson) "json" else "rtf"
        val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val filename = com.example.networktoolkit.data.TextReportExportManager.suggestFilename(
            prefix = "WiFi_Scanner_Report",
            extension = extension
        )
        val content = if (isJson) generateJsonReport() else generateTextReport()
        return com.example.networktoolkit.data.TextReportExportManager.createShareIntentWithPrivacy(
            context = context,
            rawContent = content,
            filename = filename,
            subject = "Wi-Fi Site Survey Scan Report - ${fileDateFormat.format(Date())}",
            chooserTitle = "Share Wi-Fi Scan Report via...",
            identifiers = collectSensitiveIdentifiers(context),
            isJson = isJson,
            privacyChoice = privacyChoice
        )
    }

    fun copyReportToClipboard(context: Context, isJson: Boolean) {
        val rawContent = if (isJson) generateJsonReport() else generateTextReport()
        val label = if (isJson) "Wi-Fi Scan (JSON)" else "Wi-Fi Scan Report"
        val identifiers = collectSensitiveIdentifiers(context)
        com.example.networktoolkit.data.TextReportExportManager.copyRedactedToClipboard(
            context = context,
            label = label,
            rawContent = rawContent,
            identifiers = identifiers,
            isJson = isJson,
            markSensitive = true
        ).onSuccess {
            Toast.makeText(context, "Redacted $label copied to clipboard", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
            Toast.makeText(
                context,
                err.message ?: "Unable to copy report cleanly without leaking sensitive identifiers",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoScanJob?.cancel()
        engine.unregisterReceiver()
    }
}
