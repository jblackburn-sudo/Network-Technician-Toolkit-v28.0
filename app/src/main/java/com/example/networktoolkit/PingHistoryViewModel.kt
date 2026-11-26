package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networktoolkit.data.DiagnosticHistoryRepository
import com.example.networktoolkit.data.DiagnosticReportExporter
import com.example.networktoolkit.data.PingHistoryRepository
import com.example.networktoolkit.data.PingResultEntity
import com.example.networktoolkit.data.TracerouteResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticTab {
    ALL,
    PING,
    TRACEROUTE
}

/**
 * ViewModel managing the Room database history of network diagnostic test sessions (Ping & Traceroute).
 * Supports search, deletion, filtering, and report export (Text and JSON) via email and installed apps.
 */
class PingHistoryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val diagnosticRepo = DiagnosticHistoryRepository.getInstance(application)
    private val pingRepo = PingHistoryRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTab = MutableStateFlow(DiagnosticTab.ALL)
    val selectedTab: StateFlow<DiagnosticTab> = _selectedTab.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pingItems: StateFlow<List<PingResultEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                diagnosticRepo.allPingResults
            } else {
                diagnosticRepo.searchPingResults(query.trim())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Backward-compatible alias for existing callers
    val historyItems: StateFlow<List<PingResultEntity>> get() = pingItems

    @OptIn(ExperimentalCoroutinesApi::class)
    val tracerouteItems: StateFlow<List<TracerouteResultEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                diagnosticRepo.allTracerouteResults
            } else {
                diagnosticRepo.searchTracerouteResults(query.trim())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun setSelectedTab(tab: DiagnosticTab) {
        _selectedTab.value = tab
    }

    // ==========================================
    // DELETION OPERATIONS
    // ==========================================

    fun deletePingItem(id: Long) {
        viewModelScope.launch {
            diagnosticRepo.deletePingById(id)
        }
    }

    fun deleteTracerouteItem(id: Long) {
        viewModelScope.launch {
            diagnosticRepo.deleteTracerouteById(id)
        }
    }

    fun deleteItem(id: Long) {
        deletePingItem(id)
    }

    fun clearAll() {
        viewModelScope.launch {
            diagnosticRepo.clearAllDiagnostics()
        }
    }

    fun clearPingsOnly() {
        viewModelScope.launch {
            diagnosticRepo.clearAllPings()
        }
    }

    fun clearTraceroutesOnly() {
        viewModelScope.launch {
            diagnosticRepo.clearAllTraceroutes()
        }
    }

    // ==========================================
    // DIAGNOSTIC REPORT EXPORT & SHARE (TEXT & JSON)
    // ==========================================

    fun generateJsonReport(): String {
        return DiagnosticReportExporter.generateJsonReport(
            pings = pingItems.value,
            traceroutes = tracerouteItems.value
        )
    }

    fun generateTextReport(): String {
        return DiagnosticReportExporter.generateTextReport(
            pings = pingItems.value,
            traceroutes = tracerouteItems.value
        )
    }

    @JvmOverloads
    fun createShareReportIntent(
        context: Context,
        isJson: Boolean,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val report = if (isJson) generateJsonReport() else generateTextReport()
        return DiagnosticReportExporter.createShareIntent(
            context = context,
            reportContent = report,
            isJson = isJson,
            subjectTitle = "Network Diagnostic Test Report",
            privacyChoice = privacyChoice
        )
    }

    @JvmOverloads
    fun createShareSinglePingIntent(
        context: Context,
        ping: PingResultEntity,
        isJson: Boolean,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val report = if (isJson) {
            DiagnosticReportExporter.generateSinglePingJson(ping)
        } else {
            DiagnosticReportExporter.generateSinglePingText(ping)
        }
        return DiagnosticReportExporter.createShareIntent(
            context = context,
            reportContent = report,
            isJson = isJson,
            subjectTitle = "Ping Diagnostic Report - ${ping.targetHost}",
            privacyChoice = privacyChoice
        )
    }

    @JvmOverloads
    fun createShareSingleTracerouteIntent(
        context: Context,
        trace: TracerouteResultEntity,
        isJson: Boolean,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val report = if (isJson) {
            DiagnosticReportExporter.generateSingleTracerouteJson(trace)
        } else {
            DiagnosticReportExporter.generateSingleTracerouteText(trace)
        }
        return DiagnosticReportExporter.createShareIntent(
            context = context,
            reportContent = report,
            isJson = isJson,
            subjectTitle = "Traceroute Diagnostic Report - ${trace.targetHost}",
            privacyChoice = privacyChoice
        )
    }

    fun copyReportToClipboard(context: Context, isJson: Boolean) {
        val content = if (isJson) generateJsonReport() else generateTextReport()
        val label = if (isJson) "Diagnostic Report (JSON)" else "Diagnostic Report (Text)"
        DiagnosticReportExporter.copyToClipboard(context, content, label, isJson = isJson)
    }

    // ==========================================
    // BACKWARD COMPATIBLE CSV EXPORTS
    // ==========================================

    fun getDefaultFilename(): String = com.example.networktoolkit.data.PingCsvExporter.getDefaultFilename()

    @JvmOverloads
    fun generateCsvString(
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): String {
        val ids = com.example.networktoolkit.data.TextReportExportManager.collectIdentifiers(getApplication())
        return com.example.networktoolkit.data.PingCsvExporter.generateCsv(pingItems.value, ids, privacyChoice)
    }

    @JvmOverloads
    fun exportToUri(
        context: Context,
        uri: android.net.Uri,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED,
        onComplete: (Result<Int>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.example.networktoolkit.data.PingCsvExporter.writeCsvToUri(
                context = context,
                uri = uri,
                records = pingItems.value,
                privacyChoice = privacyChoice
            )
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    @JvmOverloads
    fun exportToDownloads(
        context: Context,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED,
        onComplete: (Result<android.net.Uri>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = com.example.networktoolkit.data.PingCsvExporter.exportToDownloads(
                context = context,
                records = pingItems.value,
                privacyChoice = privacyChoice
            )
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    @JvmOverloads
    fun createShareIntent(
        context: Context,
        privacyChoice: com.example.networktoolkit.data.WifiReportExportPrivacyChoice =
            com.example.networktoolkit.data.WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        return com.example.networktoolkit.data.PingCsvExporter.createShareIntent(
            context = context,
            records = pingItems.value,
            privacyChoice = privacyChoice
        )
    }

    fun savePingResult(
        targetHost: String,
        resolvedIp: String,
        transmitted: Int,
        received: Int,
        lossPct: Double,
        minRtt: Double,
        avgRtt: Double,
        maxRtt: Double,
        mdevRtt: Double,
        mode: String,
        durationMs: Long,
        logOutput: String
    ) {
        viewModelScope.launch {
            val entity = PingResultEntity(
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                packetsTransmitted = transmitted,
                packetsReceived = received,
                packetLossPercentage = lossPct,
                minRttMs = minRtt,
                avgRttMs = avgRtt,
                maxRttMs = maxRtt,
                mdevRttMs = mdevRtt,
                protocolMode = mode,
                durationMs = durationMs,
                logOutput = logOutput
            )
            diagnosticRepo.insertPing(entity)
        }
    }
}
