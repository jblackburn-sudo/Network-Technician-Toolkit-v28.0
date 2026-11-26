package com.example.networktoolkit.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.networktoolkit.BuildConfig
import com.example.networktoolkit.TracerouteHop
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic report exporter that compiles past ping and traceroute test sessions
 * into structured JSON or formatted plain-text reports for field technicians.
 * Supports sharing via email, messaging, or saving locally.
 */
object DiagnosticReportExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ==========================================
    // JSON REPORT GENERATION
    // ==========================================

    fun generateJsonReport(
        pings: List<PingResultEntity>,
        traceroutes: List<TracerouteResultEntity>
    ): String {
        val root = JSONObject()
        val now = System.currentTimeMillis()

        root.put("reportTitle", "Network Technician Diagnostic Report")
        root.put("toolkitVersion", BuildConfig.VERSION_NAME)
        root.put("exportTimestamp", now)
        root.put("exportDate", dateFormat.format(Date(now)))
        root.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")

        val pingsArray = JSONArray()
        for (p in pings) {
            val pingObj = JSONObject().apply {
                put("id", p.id)
                put("targetHost", p.targetHost)
                put("resolvedIp", p.resolvedIp)
                put("packetsTransmitted", p.packetsTransmitted)
                put("packetsReceived", p.packetsReceived)
                put("packetLossPercentage", p.packetLossPercentage)
                put("minRttMs", p.minRttMs)
                put("avgRttMs", p.avgRttMs)
                put("maxRttMs", p.maxRttMs)
                put("mdevRttMs", p.mdevRttMs)
                put("protocolMode", p.protocolMode)
                put("durationMs", p.durationMs)
                put("timestamp", p.timestamp)
                put("formattedDate", dateFormat.format(Date(p.timestamp)))
                put("logOutput", p.logOutput)
            }
            pingsArray.put(pingObj)
        }
        root.put("pingSessions", pingsArray)

        val traceroutesArray = JSONArray()
        for (t in traceroutes) {
            val traceObj = JSONObject().apply {
                put("id", t.id)
                put("targetHost", t.targetHost)
                put("targetIp", t.targetIp)
                put("totalHops", t.totalHops)
                put("isDestinationReached", t.isDestinationReached)
                put("avgRttMs", t.avgRttMs ?: -1.0)
                put("durationMs", t.durationMs)
                put("timestamp", t.timestamp)
                put("formattedDate", dateFormat.format(Date(t.timestamp)))

                val parsedHops = DiagnosticHistoryRepository.parseHopsJson(t.hopsJson)
                val hopsArray = JSONArray()
                for (h in parsedHops) {
                    val hopObj = JSONObject().apply {
                        put("hopNumber", h.hopNumber)
                        put("ip", h.ip)
                        put("hostname", h.hostname ?: "")
                        put("rttMs", h.rttMs ?: -1.0)
                        put("isDestinationReached", h.isDestinationReached)
                        put("isTimedOut", h.isTimedOut)
                        put("statusText", h.statusText)
                    }
                    hopsArray.put(hopObj)
                }
                put("hops", hopsArray)
            }
            traceroutesArray.put(traceObj)
        }
        root.put("tracerouteSessions", traceroutesArray)

        return root.toString(2)
    }

    fun generateSinglePingJson(ping: PingResultEntity): String {
        return generateJsonReport(listOf(ping), emptyList())
    }

    fun generateSingleTracerouteJson(trace: TracerouteResultEntity): String {
        return generateJsonReport(emptyList(), listOf(trace))
    }

    // ==========================================
    // PLAIN TEXT REPORT GENERATION
    // ==========================================

    fun generateTextReport(
        pings: List<PingResultEntity>,
        traceroutes: List<TracerouteResultEntity>
    ): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()

        sb.append("=================================================================\n")
        sb.append("         NETWORK TECHNICIAN TOOLKIT - DIAGNOSTIC REPORT          \n")
        sb.append("=================================================================\n")
        sb.append("Generated Date  : ").append(dateFormat.format(Date(now))).append("\n")
        sb.append("Toolkit Version : ").append(BuildConfig.VERSION_NAME).append("\n")
        sb.append("Device Platform : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n")
        sb.append("Total Ping Tests: ").append(pings.size).append("\n")
        sb.append("Total Traces    : ").append(traceroutes.size).append("\n")
        sb.append("=================================================================\n\n")

        // Section 1: Ping Test Results
        sb.append("-----------------------------------------------------------------\n")
        sb.append("                     PING TEST SESSIONS (")
            .append(pings.size).append(")\n")
        sb.append("-----------------------------------------------------------------\n")

        if (pings.isEmpty()) {
            sb.append("No ping sessions recorded in this export.\n\n")
        } else {
            pings.forEachIndexed { index, p ->
                sb.append(String.format(Locale.US, "[%02d] Target: %s (%s)\n", index + 1, p.targetHost, p.resolvedIp))
                sb.append("     Date & Time : ").append(dateFormat.format(Date(p.timestamp))).append("\n")
                sb.append(String.format(
                    Locale.US,
                    "     Packets     : Transmitted: %d, Received: %d, Loss: %.1f%%\n",
                    p.packetsTransmitted, p.packetsReceived, p.packetLossPercentage
                ))
                sb.append(String.format(
                    Locale.US,
                    "     Latency RTT : Min: %.1f ms | Avg: %.1f ms | Max: %.1f ms | MDev: %.1f ms\n",
                    p.minRttMs, p.avgRttMs, p.maxRttMs, p.mdevRttMs
                ))
                sb.append(String.format(
                    Locale.US,
                    "     Parameters  : Protocol: %s | Duration: %.2f sec\n",
                    p.protocolMode, p.durationMs / 1000.0
                ))
                if (p.logOutput.isNotBlank()) {
                    sb.append("     Console Log :\n")
                    p.logOutput.lines().take(6).forEach { line ->
                        sb.append("       ").append(line).append("\n")
                    }
                }
                sb.append("\n")
            }
        }

        // Section 2: Traceroute Sessions
        sb.append("-----------------------------------------------------------------\n")
        sb.append("                  TRACEROUTE TEST SESSIONS (")
            .append(traceroutes.size).append(")\n")
        sb.append("-----------------------------------------------------------------\n")

        if (traceroutes.isEmpty()) {
            sb.append("No traceroute sessions recorded in this export.\n\n")
        } else {
            traceroutes.forEachIndexed { index, t ->
                val statusStr = if (t.isDestinationReached) "DESTINATION REACHED" else "STOPPED / MAX HOPS"
                val avgRttStr = if (t.avgRttMs != null && t.avgRttMs > 0) String.format(Locale.US, "%.1f ms", t.avgRttMs) else "N/A"

                sb.append(String.format(Locale.US, "[%02d] Target: %s (%s)\n", index + 1, t.targetHost, t.targetIp))
                sb.append("     Date & Time : ").append(dateFormat.format(Date(t.timestamp))).append("\n")
                sb.append("     Status      : ").append(statusStr).append("\n")
                sb.append(String.format(
                    Locale.US,
                    "     Hop Metrics : Total Hops: %d | Avg Latency: %s | Duration: %.2f sec\n",
                    t.totalHops, avgRttStr, t.durationMs / 1000.0
                ))

                val parsedHops = DiagnosticHistoryRepository.parseHopsJson(t.hopsJson)
                if (parsedHops.isNotEmpty()) {
                    sb.append("     Hop Breakdown:\n")
                    sb.append("       Hop  IP Address         RTT (ms)   Hostname / Status\n")
                    sb.append("       ---  -----------------  ---------  -----------------\n")
                    parsedHops.forEach { h ->
                        val rttVal = if (h.rttMs != null) String.format(Locale.US, "%7.1f ms", h.rttMs) else "      *  "
                        val hostOrStatus = h.hostname?.takeIf { it.isNotBlank() } ?: h.statusText
                        sb.append(String.format(Locale.US, "       %2d   %-17s  %s  %s\n", h.hopNumber, h.ip, rttVal, hostOrStatus))
                    }
                }
                sb.append("\n")
            }
        }

        sb.append("=================================================================\n")
        sb.append("                 END OF NETWORK DIAGNOSTIC REPORT                \n")
        sb.append("=================================================================\n")

        return sb.toString()
    }

    fun generateSinglePingText(ping: PingResultEntity): String {
        return generateTextReport(listOf(ping), emptyList())
    }

    fun generateSingleTracerouteText(trace: TracerouteResultEntity): String {
        return generateTextReport(emptyList(), listOf(trace))
    }

    // ==========================================
    // INTENT SHARING & EXPORT UTILITIES
    // ==========================================

    /**
     * Creates an Intent to share the diagnostic report via email or any messaging app.
     * Applies mandatory SSID/BSSID privacy redaction + verification when sharing RTF reports.
     */
    @JvmOverloads
    fun createShareIntent(
        context: Context,
        reportContent: String,
        isJson: Boolean,
        subjectTitle: String = "Network Diagnostic Report",
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val extension = if (isJson) "json" else "rtf"
        val timestamp = filenameDateFormat.format(Date())
        val filename = TextReportExportManager.suggestFilename("Diagnostic_History_Report", extension)
        return TextReportExportManager.createShareIntentWithPrivacy(
            context = context,
            rawContent = reportContent,
            filename = filename,
            subject = "$subjectTitle - $timestamp",
            chooserTitle = "Share Diagnostic Report via...",
            identifiers = TextReportExportManager.collectIdentifiers(context),
            isJson = isJson,
            privacyChoice = privacyChoice
        )
    }

    /**
     * Copies privacy-redacted report content to the Android clipboard and marks it sensitive on Android 13+.
     */
    @JvmOverloads
    fun copyToClipboard(
        context: Context,
        text: String,
        label: String = "Diagnostic Report",
        isJson: Boolean = false
    ) {
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        TextReportExportManager.copyRedactedToClipboard(
            context = context,
            label = label,
            rawContent = text,
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

    /**
     * Returns a default filename for saving reports to disk.
     */
    fun getDefaultFilename(isJson: Boolean): String {
        val ext = if (isJson) "json" else "rtf"
        return TextReportExportManager.suggestFilename("Diagnostic_History_Report", ext)
    }
}
