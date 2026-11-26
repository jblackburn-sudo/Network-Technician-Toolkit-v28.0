package com.example.networktoolkit.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for formatting and exporting Room ping test history records into RFC 4180 compliant CSV files.
 */
object PingCsvExporter {

    private const val UTF8_BOM = "\uFEFF"

    /**
     * Generates a standard RFC 4180 compliant CSV string from the list of PingResultEntity records.
     * Includes a UTF-8 BOM so Microsoft Excel, Google Sheets, and LibreOffice immediately parse UTF-8 characters cleanly.
     */
    @JvmOverloads
    fun generateCsv(
        records: List<PingResultEntity>,
        identifiers: SensitiveWifiIdentifiers = SensitiveWifiIdentifiers(),
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()

        // Byte Order Mark for Excel UTF-8 compatibility
        sb.append(UTF8_BOM)

        // Header row
        val headers = listOf(
            "ID",
            "Timestamp_Epoch_ms",
            "Date_Time",
            "Target_Host",
            "Resolved_IP",
            "Protocol_Mode",
            "Packets_Transmitted",
            "Packets_Received",
            "Packet_Loss_Percent",
            "Min_RTT_ms",
            "Avg_RTT_ms",
            "Max_RTT_ms",
            "MDev_RTT_ms",
            "Duration_ms",
            "Connectivity_Status",
            "Log_Summary"
        )
        sb.append(headers.joinToString(separator = ",", transform = { escapeCsv(it) })).append("\r\n")

        val shouldRedact = privacyChoice != WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED

        // Data rows
        for (record in records) {
            val formattedDate = dateFormat.format(Date(record.timestamp))
            val status = when {
                record.packetsReceived == 0 -> "FAILED"
                record.packetLossPercentage > 0.0 -> "DEGRADED"
                else -> "PASSED"
            }

            val exportedHost = if (shouldRedact) {
                TextReportExportManager.redactTextLineContent(record.targetHost, identifiers)
            } else {
                record.targetHost
            }
            val exportedIp = if (shouldRedact) {
                TextReportExportManager.redactTextLineContent(record.resolvedIp, identifiers)
            } else {
                record.resolvedIp
            }
            val exportedLog = if (shouldRedact) {
                TextReportExportManager.redactTextLineContent(record.logOutput.trim(), identifiers)
            } else {
                record.logOutput.trim()
            }

            val row = listOf(
                record.id.toString(),
                record.timestamp.toString(),
                formattedDate,
                exportedHost,
                exportedIp,
                record.protocolMode,
                record.packetsTransmitted.toString(),
                record.packetsReceived.toString(),
                String.format(Locale.US, "%.1f", record.packetLossPercentage),
                String.format(Locale.US, "%.2f", record.minRttMs),
                String.format(Locale.US, "%.2f", record.avgRttMs),
                String.format(Locale.US, "%.2f", record.maxRttMs),
                String.format(Locale.US, "%.2f", record.mdevRttMs),
                record.durationMs.toString(),
                status,
                exportedLog
            )
            sb.append(row.joinToString(separator = ",", transform = { escapeCsv(it) })).append("\r\n")
        }

        return sb.toString()
    }

    /**
     * Escapes a single CSV field according to RFC 4180 rules.
     * Quotes fields containing commas, quotes, newlines, or carriage returns, and escapes internal quotes with double-quotes.
     */
    fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            val escaped = value.replace("\"", "\"\"")
            return "\"$escaped\""
        }
        return value
    }

    /**
     * Generates a descriptive default filename based on current timestamp.
     */
    fun getDefaultFilename(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "ping_history_report_$timestamp.csv"
    }

    /**
     * Writes the privacy-redacted CSV content to a user-selected SAF Uri (Storage Access Framework).
     */
    @JvmOverloads
    fun writeCsvToUri(
        context: Context,
        uri: Uri,
        records: List<PingResultEntity>,
        identifiers: SensitiveWifiIdentifiers? = null,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Int> {
        return runCatching {
            val ids = identifiers ?: TextReportExportManager.collectIdentifiers(context)
            val csvContent = generateCsv(records, ids, privacyChoice)
            if (privacyChoice != WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED) {
                TextReportExportManager.verifyPrivacyRedaction(csvContent, ids)
            }
            val outputStream: OutputStream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("Could not open output stream for selected destination")

            outputStream.use { stream ->
                stream.write(csvContent.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            records.size
        }
    }

    /**
     * Directly saves the CSV file (redacted by default) to the public Downloads folder using MediaStore on Android 10+ (API 29+)
     * or app external files directory as a fallback.
     */
    @JvmOverloads
    fun exportToDownloads(
        context: Context,
        records: List<PingResultEntity>,
        filename: String = getDefaultFilename(),
        identifiers: SensitiveWifiIdentifiers? = null,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Uri> {
        return runCatching {
            val ids = identifiers ?: TextReportExportManager.collectIdentifiers(context)
            val csvContent = generateCsv(records, ids, privacyChoice)
            if (privacyChoice != WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED) {
                TextReportExportManager.verifyPrivacyRedaction(csvContent, ids)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IllegalStateException("Failed to create download entry in MediaStore")

                val outStream: OutputStream = resolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Failed to open output stream in MediaStore")

                outStream.use { stream ->
                    stream.write(csvContent.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, filename)
                FileOutputStream(targetFile).use { stream ->
                    stream.write(csvContent.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                Uri.fromFile(targetFile)
            }
        }
    }

    /**
     * Creates a shareable CSV report file (redacted by default) in the app cache and returns an Intent configured with FileProvider.
     */
    @JvmOverloads
    fun createShareIntent(
        context: Context,
        records: List<PingResultEntity>,
        filename: String = getDefaultFilename(),
        identifiers: SensitiveWifiIdentifiers? = null,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        var reportFile: File? = null
        return runCatching {
            val ids = identifiers ?: TextReportExportManager.collectIdentifiers(context)
            val csvContent = generateCsv(records, ids, privacyChoice)
            if (privacyChoice != WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED) {
                TextReportExportManager.verifyPrivacyRedaction(csvContent, ids)
            }

            val reportsDir = File(context.cacheDir, "reports")
            if (!reportsDir.exists()) {
                reportsDir.mkdirs()
            }
            TextReportExportManager.cleanupExpiredShareCacheFiles(context, keepFilename = filename)
            val targetFile = File(reportsDir, filename)
            reportFile = targetFile
            FileOutputStream(targetFile).use { stream ->
                stream.write(csvContent.toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, targetFile)

            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Network Ping Diagnostic Report: $filename")
                putExtra(Intent.EXTRA_TEXT, "Attached is the Network Technician Toolkit ping diagnostic report with ${records.size} test records.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.onFailure {
            runCatching { reportFile?.takeIf { f -> f.exists() }?.delete() }
        }
    }
}
