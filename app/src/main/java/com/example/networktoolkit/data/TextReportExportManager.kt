package com.example.networktoolkit.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.networktoolkit.WifiTelemetryReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Holds sensitive Wi-Fi network identification strings collected from live telemetry,
 * survey samples, or scan results solely for pre-export redaction & privacy verification.
 * Original model/ViewModel state is never modified.
 */
data class SensitiveWifiIdentifiers(
    val ssids: Set<String> = emptySet(),
    val bssids: Set<String> = emptySet()
)

enum class ExportErrorCategory(val title: String) {
    PERMISSION_OR_URI_DENIED("Permission or URI Access Denied"),
    STORAGE_PROVIDER_UNAVAILABLE("File Picker Unavailable"),
    INVALID_DESTINATION_URI("Invalid Destination URI"),
    FILE_CREATION_OR_STREAM_ERROR("File Creation / Stream Error"),
    INSUFFICIENT_STORAGE("Insufficient Storage Space"),
    IO_WRITE_ERROR("File Write / I/O Failure"),
    PRIVACY_VERIFICATION_FAILED("Privacy Verification Failed"),
    STAGED_FILE_MISSING("Staged Export File Missing"),
    STAGED_FILE_CORRUPTED("Staged Export File Corrupted"),
    EMPTY_REPORT("Empty Report"),
    UNEXPECTED_ERROR("Unexpected Export Error")
}

enum class WifiReportExportPrivacyChoice {
    REDACTED,
    UNREDACTED_CONFIRMED
}

sealed class TextExportOutcome {
    data class Success(
        val uri: Uri,
        val displayName: String,
        val bytesWritten: Int,
        val isWifiUnredacted: Boolean = false
    ) : TextExportOutcome()

    data object Cancelled : TextExportOutcome()

    data class AlreadyProcessed(
        val exportId: String,
        val displayName: String
    ) : TextExportOutcome()

    data class Failure(
        val category: ExportErrorCategory,
        val userMessage: String,
        val exceptionName: String,
        val exceptionMessage: String,
        val operation: String
    ) : TextExportOutcome() {
        val formattedDiagnostics: String
            get() = buildString {
                appendLine(userMessage)
                appendLine()
                appendLine("Operation: $operation")
                appendLine("Exception: $exceptionName")
                if (exceptionMessage.isNotBlank()) {
                    append("Details: $exceptionMessage")
                }
            }.trim()
    }
}

data class StagedTextExport(
    val exportId: String,
    val stagedFilePath: String,
    val suggestedFilename: String,
    val byteLength: Int,
    val sha256: String,
    val screenKey: String
)

sealed class StageExportResult {
    data class Ready(val staged: StagedTextExport) : StageExportResult()
    data class Failed(val failure: TextExportOutcome.Failure) : StageExportResult()
}

class PrivacyVerificationException(
    message: String,
    val leakedFieldType: String
) : SecurityException(message)

class StagedExportMissingException(message: String) : FileNotFoundException(message)

class StagedExportCorruptedException(message: String) : IOException(message)

/**
 * Centralized Storage Access Framework (ACTION_CREATE_DOCUMENT), FileProvider sharing,
 * error-diagnostic, and mandatory SSID/BSSID privacy-redaction engine for RTF reports.
 */
object TextReportExportManager {

    const val REDACTED_SSID = "Hidden Network"
    const val REDACTED_BSSID = "Hidden MAC"
    const val MIME_TEXT_PLAIN = "text/plain"
    const val MIME_RTF = "application/rtf"
    const val PRIVACY_NOTICE_MESSAGE =
        "Privacy Notice: The Wi-Fi network name (SSID) and MAC address (BSSID) have been removed for privacy purposes."
    const val UNREDACTED_NOTICE_MESSAGE =
        "Privacy Notice: Wi-Fi network identification (SSID and BSSID) was explicitly included (unredacted) for this single export only by user confirmation."

    private val MAC_COLON_OR_DASH_REGEX =
        Regex("""(?i)\b([0-9a-f]{2}[:\-]){5}[0-9a-f]{2}\b""")
    private val MAC_DOT_REGEX =
        Regex("""(?i)\b[0-9a-f]{4}\.[0-9a-f]{4}\.[0-9a-f]{4}\b""")

    private val SSID_KEY_VALUE_LINE_REGEX =
        Regex("""(?im)^(\s*(?:\*\s*)?(?:Connected\s+)?SSID\s*:\s*)(.+)$""")
    private val BSSID_KEY_VALUE_LINE_REGEX =
        Regex("""(?im)^(\s*(?:\*\s*)?(?:Connected\s+)?BSSID(?:\s*\(MAC\))?\s*:\s*)(.+)$""")

    private val PLACEHOLDER_SSIDS = setOf(
        "",
        "n/a",
        "na",
        "none",
        "unknown",
        "unavailable",
        "not connected",
        "<unknown ssid>",
        "unknown ssid",
        "<hidden ssid>",
        "<hidden or unnamed ssid>",
        "<location permission needed for ssid>",
        "unavailable (os restricted / location services disabled)",
        "unavailable (location permission required)",
        "hidden network",
        "[hidden ssid]",
        "[hidden network]",
        "disconnected",
        "wi-fi disabled"
    )

    private val PLACEHOLDER_BSSIDS = setOf(
        "",
        "n/a",
        "na",
        "none",
        "unknown",
        "unavailable",
        "not connected",
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
        "unavailable (os restricted / location services disabled)",
        "unavailable (location permission required)",
        "hidden mac"
    )

    private val PLACEHOLDER_LINE_VALUES = setOf(
        "n/a",
        "na",
        "none",
        "unavailable",
        "unknown",
        "not connected",
        "<unknown ssid>",
        "<hidden or unnamed ssid>",
        "<location permission needed for ssid>",
        "unavailable (os restricted / location services disabled)",
        "unavailable (location permission required)"
    )

    @JvmStatic
    fun isPlaceholderOrStatusSsid(raw: String?): Boolean {
        val cleaned = raw?.trim()?.removeSurrounding("\"")?.trim()?.lowercase(Locale.US).orEmpty()
        if (cleaned.isEmpty()) return true
        if (cleaned in PLACEHOLDER_SSIDS) return true
        if (cleaned.startsWith("unavailable (") && cleaned.endsWith(")")) return true
        if (cleaned.startsWith("<") && cleaned.endsWith(">") &&
            (cleaned.contains("permission") || cleaned.contains("unknown") ||
                cleaned.contains("hidden") || cleaned.contains("unavailable"))
        ) {
            return true
        }
        return false
    }

    @JvmStatic
    fun isPlaceholderOrStatusBssid(raw: String?): Boolean {
        val cleaned = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (cleaned.isEmpty()) return true
        val canonicalMac = cleaned.replace('-', ':')
        if (canonicalMac in PLACEHOLDER_BSSIDS || cleaned in PLACEHOLDER_BSSIDS) return true
        if (cleaned.startsWith("unavailable (") && cleaned.endsWith(")")) return true
        return false
    }

    @JvmStatic
    fun buildTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    @JvmStatic
    fun suggestFilename(prefix: String, extension: String = "rtf"): String {
        val safePrefix = prefix
            .trim()
            .replace(Regex("""[^A-Za-z0-9_\-]"""), "_")
            .replace(Regex("""_+"""), "_")
            .trim('_')
            .ifBlank { "Network_Toolkit_Report" }
        return "${safePrefix}_${buildTimestamp()}.$extension"
    }

    /**
     * Collects explicit sensitive SSID/BSSID identifiers plus currently connected device
     * Wi-Fi identifiers as defense-in-depth without modifying stored app data.
     */
    @JvmStatic
    fun collectIdentifiers(
        context: Context?,
        extraSsids: Collection<String?> = emptyList(),
        extraBssids: Collection<String?> = emptyList()
    ): SensitiveWifiIdentifiers {
        val rawSsids = mutableListOf<String?>()
        val rawBssids = mutableListOf<String?>()

        rawSsids.addAll(extraSsids)
        rawBssids.addAll(extraBssids)

        if (context != null) {
            try {
                val live = WifiTelemetryReader.readLiveTelemetry(context)
                rawSsids.add(live.ssid)
                rawBssids.add(live.bssid)
            } catch (_: Exception) {
            }
        }

        val cleanSsids = linkedSetOf<String>()
        rawSsids.forEach { raw ->
            val cleaned = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
            if (!isPlaceholderOrStatusSsid(cleaned)) {
                cleanSsids.add(cleaned)
                if (cleaned.endsWith(" (Demo)", ignoreCase = true)) {
                    val base = cleaned.removeSuffix(" (Demo)").removeSuffix(" (demo)").trim()
                    if (!isPlaceholderOrStatusSsid(base)) {
                        cleanSsids.add(base)
                    }
                }
            }
        }

        val cleanBssids = linkedSetOf<String>()
        rawBssids.forEach { raw ->
            val cleaned = raw?.trim().orEmpty()
            if (!isPlaceholderOrStatusBssid(cleaned)) {
                cleanBssids.add(cleaned)
            }
        }

        return SensitiveWifiIdentifiers(ssids = cleanSsids, bssids = cleanBssids)
    }

    /**
     * Pipeline Steps 1-3:
     * 1. Accept complete raw report text.
     * 2. Apply automatic SSID -> "Hidden Network" and BSSID/MAC -> "Hidden MAC" redaction.
     * 3. Verify original sensitive SSIDs, BSSIDs, and MAC addresses no longer appear.
     */
    @JvmStatic
    fun sanitizeAndVerifyTextReport(
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        operation: String = "Preparing redacted RTF report",
        postRedactionTransform: ((String) -> String)? = null
    ): Result<String> {
        if (rawContent.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot export an empty report."))
        }

        return try {
            val redacted = applyPrivacyRedaction(rawContent, identifiers)
            val candidate = postRedactionTransform?.invoke(redacted) ?: redacted
            verifyPrivacyRedaction(candidate, identifiers)
            Result.success(candidate)
        } catch (e: PrivacyVerificationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                PrivacyVerificationException(
                    message = "Privacy sanitization verification failed during '$operation': ${e.message ?: e::class.java.simpleName}",
                    leakedFieldType = "VerificationError"
                )
            )
        }
    }

    /**
     * JSON Privacy Redaction & Verification Pipeline:
     * 1. Parses JSON payload and redacts Wi-Fi SSID/BSSID/MAC fields prior to final serialization.
     * 2. Redacts explicit SSIDs, BSSIDs, and MAC patterns across nested string fields.
     * 3. Appends privacy notice metadata and verifies zero leakage in the final serialized JSON output.
     */
    @JvmStatic
    fun sanitizeAndVerifyJsonReport(
        rawJson: String,
        identifiers: SensitiveWifiIdentifiers,
        operation: String = "Preparing redacted JSON report",
        postRedactionTransform: ((String) -> String)? = null
    ): Result<String> {
        if (rawJson.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot export an empty JSON report."))
        }

        return try {
            val parsed = JSONTokener(rawJson).nextValue()
            val sanitizedRoot = when (parsed) {
                is JSONObject -> redactJsonObject(parsed, identifiers).apply {
                    put("privacyNotice", PRIVACY_NOTICE_MESSAGE)
                }
                is JSONArray -> redactJsonArray(parsed, identifiers)
                else -> throw IllegalArgumentException("Expected a JSON object or array for JSON report export.")
            }

            val serialized = when (sanitizedRoot) {
                is JSONObject -> sanitizedRoot.toString(2)
                is JSONArray -> sanitizedRoot.toString(2)
                else -> sanitizedRoot.toString()
            }

            val candidate = postRedactionTransform?.invoke(serialized) ?: serialized
            verifyPrivacyRedaction(candidate, identifiers)
            Result.success(candidate)
        } catch (e: PrivacyVerificationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                PrivacyVerificationException(
                    message = "JSON privacy sanitization verification failed during '$operation': ${e.message ?: e::class.java.simpleName}",
                    leakedFieldType = "VerificationError"
                )
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    fun prepareTextReportForExport(
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED,
        operation: String = "Preparing diagnostic text report for export"
    ): Result<String> {
        if (rawContent.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot export an empty report."))
        }
        return when (privacyChoice) {
            WifiReportExportPrivacyChoice.REDACTED -> sanitizeAndVerifyTextReport(
                rawContent = rawContent,
                identifiers = identifiers,
                operation = operation
            )
            WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED -> {
                val trailingNewline = rawContent.endsWith("\n")
                val withNotice = if (rawContent.contains(UNREDACTED_NOTICE_MESSAGE)) {
                    rawContent
                } else {
                    "$UNREDACTED_NOTICE_MESSAGE\n\n$rawContent"
                }
                val finalContent = if (trailingNewline && !withNotice.endsWith("\n")) {
                    "$withNotice\n"
                } else {
                    withNotice
                }
                Result.success(finalContent)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun prepareJsonReportForExport(
        rawJson: String,
        identifiers: SensitiveWifiIdentifiers,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED,
        operation: String = "Preparing JSON report for export"
    ): Result<String> {
        if (rawJson.isBlank()) {
            return Result.failure(IllegalArgumentException("Cannot export an empty JSON report."))
        }
        return when (privacyChoice) {
            WifiReportExportPrivacyChoice.REDACTED -> sanitizeAndVerifyJsonReport(
                rawJson = rawJson,
                identifiers = identifiers,
                operation = operation
            )
            WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED -> runCatching {
                val parsed = JSONTokener(rawJson).nextValue()
                when (parsed) {
                    is JSONObject -> parsed.apply {
                        put("privacyNotice", UNREDACTED_NOTICE_MESSAGE)
                    }.toString(2)
                    is JSONArray -> parsed.toString(2)
                    else -> throw IllegalArgumentException("Expected a JSON object or array for JSON report export.")
                }
            }
        }
    }

    private fun redactJsonObject(obj: JSONObject, identifiers: SensitiveWifiIdentifiers): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lowerKey = key.lowercase(Locale.US)
            val value = obj.opt(key)
            val redactedValue: Any? = when {
                lowerKey in setOf("ssid", "connectedssid", "displayssid", "networkname") && value is String -> {
                    if (isPlaceholderOrStatusSsid(value)) {
                        value
                    } else {
                        REDACTED_SSID
                    }
                }
                lowerKey in setOf("bssid", "connectedbssid", "mac", "macaddress", "apmac") && value is String -> {
                    if (isPlaceholderOrStatusBssid(value)) {
                        value
                    } else {
                        REDACTED_BSSID
                    }
                }
                value is JSONObject -> redactJsonObject(value, identifiers)
                value is JSONArray -> redactJsonArray(value, identifiers)
                value is String -> redactTextLinesOnly(value, identifiers)
                else -> value
            }
            out.put(key, redactedValue ?: JSONObject.NULL)
        }
        return out
    }

    private fun redactJsonArray(arr: JSONArray, identifiers: SensitiveWifiIdentifiers): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            val redactedValue: Any? = when (value) {
                is JSONObject -> redactJsonObject(value, identifiers)
                is JSONArray -> redactJsonArray(value, identifiers)
                is String -> redactTextLinesOnly(value, identifiers)
                else -> value
            }
            out.put(redactedValue ?: JSONObject.NULL)
        }
        return out
    }

    @JvmStatic
    fun redactTextLineContent(rawText: String, identifiers: SensitiveWifiIdentifiers): String {
        return redactTextLinesOnly(rawText, identifiers)
    }

    private fun redactTextLinesOnly(rawText: String, identifiers: SensitiveWifiIdentifiers): String {
        val sortedBssids = identifiers.bssids
            .filter { !isPlaceholderOrStatusBssid(it) }
            .sortedByDescending { it.length }

        val sortedSsids = identifiers.ssids
            .filter { !isPlaceholderOrStatusSsid(it) }
            .sortedByDescending { it.length }

        val processedLines = rawText.lines().map { originalLine ->
            var line = originalLine
            for (bssid in sortedBssids) {
                line = replaceInLinePreservingTableAlignment(
                    line = line,
                    target = bssid,
                    replacement = REDACTED_BSSID,
                    ignoreCase = true
                )
            }
            line = MAC_COLON_OR_DASH_REGEX.replace(line) { REDACTED_BSSID }
            line = MAC_DOT_REGEX.replace(line) { REDACTED_BSSID }

            for (ssid in sortedSsids) {
                line = replaceInLinePreservingTableAlignment(
                    line = line,
                    target = ssid,
                    replacement = REDACTED_SSID,
                    ignoreCase = ssid.length >= 3
                )
            }
            line = SSID_KEY_VALUE_LINE_REGEX.replace(line) { match ->
                val prefix = match.groupValues[1]
                val rawVal = match.groupValues[2].trim()
                if (rawVal.lowercase(Locale.US) in PLACEHOLDER_LINE_VALUES || isPlaceholderOrStatusSsid(rawVal)) {
                    match.value
                } else {
                    "$prefix$REDACTED_SSID"
                }
            }
            line = BSSID_KEY_VALUE_LINE_REGEX.replace(line) { match ->
                val prefix = match.groupValues[1]
                val rawVal = match.groupValues[2].trim()
                if (rawVal.lowercase(Locale.US) in PLACEHOLDER_LINE_VALUES || isPlaceholderOrStatusBssid(rawVal)) {
                    match.value
                } else {
                    "$prefix$REDACTED_BSSID"
                }
            }
            line
        }
        return processedLines.joinToString("\n")
    }

    /**
     * Redacts all sensitive SSIDs ("Hidden Network") and BSSIDs/MACs ("Hidden MAC")
     * across the full report document while preserving table spacing, units, and diagnostic data.
     */
    @JvmStatic
    fun applyPrivacyRedaction(
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers
    ): String {
        val sortedBssids = identifiers.bssids
            .filter { !isPlaceholderOrStatusBssid(it) }
            .sortedByDescending { it.length }

        val sortedSsids = identifiers.ssids
            .filter { !isPlaceholderOrStatusSsid(it) }
            .sortedByDescending { it.length }

        val processedLines = rawContent.lines().map { originalLine ->
            var line = originalLine

            // 1. Replace explicit BSSID values
            for (bssid in sortedBssids) {
                line = replaceInLinePreservingTableAlignment(
                    line = line,
                    target = bssid,
                    replacement = REDACTED_BSSID,
                    ignoreCase = true
                )
            }

            // 2. Replace any 6-octet MAC address or Cisco MAC pattern anywhere in the line
            line = MAC_COLON_OR_DASH_REGEX.replace(line) { match ->
                REDACTED_BSSID
            }
            line = MAC_DOT_REGEX.replace(line) { REDACTED_BSSID }

            // 3. Replace explicit SSID values
            for (ssid in sortedSsids) {
                line = replaceInLinePreservingTableAlignment(
                    line = line,
                    target = ssid,
                    replacement = REDACTED_SSID,
                    ignoreCase = ssid.length >= 3
                )
            }

            // 4. Key-value fallback for Connected SSID / SSID headers if non-placeholder
            line = SSID_KEY_VALUE_LINE_REGEX.replace(line) { match ->
                val prefix = match.groupValues[1]
                val rawVal = match.groupValues[2].trim()
                val lower = rawVal.lowercase(Locale.US)
                if (lower in PLACEHOLDER_LINE_VALUES || isPlaceholderOrStatusSsid(rawVal)) {
                    match.value
                } else {
                    "$prefix$REDACTED_SSID"
                }
            }

            // 5. Key-value fallback for Connected BSSID / BSSID headers if non-placeholder
            line = BSSID_KEY_VALUE_LINE_REGEX.replace(line) { match ->
                val prefix = match.groupValues[1]
                val rawVal = match.groupValues[2].trim()
                val lower = rawVal.lowercase(Locale.US)
                if (lower in PLACEHOLDER_LINE_VALUES || isPlaceholderOrStatusBssid(rawVal)) {
                    match.value
                } else {
                    "$prefix$REDACTED_BSSID"
                }
            }

            line
        }

        val trailingNewline = rawContent.endsWith("\n")
        val joined = processedLines.joinToString("\n")
        val withPrivacyNotice = if (joined.contains(PRIVACY_NOTICE_MESSAGE)) {
            joined
        } else {
            "$PRIVACY_NOTICE_MESSAGE\n\n$joined"
        }
        return if (trailingNewline && !withPrivacyNotice.endsWith("\n")) {
            "$withPrivacyNotice\n"
        } else {
            withPrivacyNotice
        }
    }

    private val GENERIC_REPORT_WORDS = setOf(
        "wifi", "wi-fi", "net", "network", "signal", "router", "channel", "home", "house",
        "apartment", "living", "room", "kitchen", "office", "test", "ping", "trace",
        "report", "survey", "device", "android", "good", "fair", "weak", "excellent",
        "poor", "dead", "zone", "speed", "link", "noise", "floor", "ceiling", "mbps", "dbm"
    )

    private fun isGenericDictionaryWord(ssid: String): Boolean {
        val trimmed = ssid.trim().lowercase(Locale.US)
        return trimmed.length <= 3 || trimmed in GENERIC_REPORT_WORDS
    }

    /**
     * Replaces occurrences of [target] with [replacement] while adjusting adjacent table-column
     * whitespace (when followed by 2+ spaces) so fixed-width ASCII tables stay neatly aligned.
     */
    private fun replaceInLinePreservingTableAlignment(
        line: String,
        target: String,
        replacement: String,
        ignoreCase: Boolean
    ): String {
        if (target.isEmpty() || !line.contains(target, ignoreCase = ignoreCase)) return line

        val isGeneric = isGenericDictionaryWord(target)
        val regex = Regex(Regex.escape(target), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        val matches = regex.findAll(line).toList()
        if (matches.isEmpty()) return line

        val sb = StringBuilder()
        var cursor = 0
        for (m in matches) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start < cursor) continue

            // Guard against corrupting substrings or report labels when an SSID is very short or a generic word
            if (isGeneric) {
                val charBefore = if (start > 0) line[start - 1] else ' '
                val charAfter = if (end < line.length) line[end] else ' '
                if (charBefore.isLetterOrDigit() || charAfter.isLetterOrDigit()) {
                    continue
                }
                // Only replace generic-word SSIDs in connection/table/quoted contexts
                val lowerLine = line.lowercase(Locale.US)
                val inWifiContext = lowerLine.contains("ssid") ||
                    lowerLine.contains("hidden mac") ||
                    MAC_COLON_OR_DASH_REGEX.containsMatchIn(line) ||
                    (start > 0 && (line[start - 1] == '"' || line[start - 1] == '\''))
                if (!inWifiContext) continue
            }

            sb.append(line, cursor, start)

            // Check if target is in a space-padded table column (followed by 2+ spaces before next non-space)
            var spaceCountAfter = 0
            while (end + spaceCountAfter < line.length && line[end + spaceCountAfter] == ' ') {
                spaceCountAfter++
            }

            if (spaceCountAfter >= 2) {
                val colWidth = m.value.length + spaceCountAfter
                if (colWidth >= replacement.length) {
                    sb.append(replacement)
                    val remainingSpaces = colWidth - replacement.length
                    repeat(remainingSpaces) { sb.append(' ') }
                    cursor = end + spaceCountAfter
                    continue
                }
            }

            sb.append(replacement)
            cursor = end
        }
        sb.append(line, cursor, line.length)
        return sb.toString()
    }

    private val RTF_RULE_LINE_REGEX = Regex("""^\s*[=\-_]{8,}\s*$""")
    private val RTF_SECTION_BANNER_REGEX = Regex("""^\s*(?:---+\s*.+\s*---|\[.+\])\s*$""")
    private val RTF_KEY_VALUE_REGEX = Regex("""^(\s*[A-Za-z0-9][A-Za-z0-9 /&().+\-#*]{1,26}\s*:\s*)(.+)$""")

    /**
     * Converts an already-redacted plain-text diagnostic report into a cleanly styled Rich Text Format
     * (.rtf) document using Courier New monospace alignment for tables/columns and Arial headings,
     * bold privacy notice styling, and standard \uN Unicode escaping for non-ASCII symbols.
     */
    @JvmStatic
    fun convertPlainTextToRtf(plainText: String): String {
        val lines = plainText.lines()
        val sb = StringBuilder(plainText.length + 1024)
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\\nouicompat\\deflang1033\n")
        sb.append("{\\fonttbl{\\f0\\fmodern\\fcharset0 Courier New;}{\\f1\\fswiss\\fcharset0 Arial;}}\n")
        sb.append("{\\colortbl ;\\red26\\green32\\blue44;\\red13\\green110\\blue130;\\red100\\green116\\blue139;}\n")
        sb.append("\\viewkind4\\uc1\\pard\\f0\\fs20\\cf1\n")

        for (i in lines.indices) {
            val line = lines[i]
            val prevIsRule = i > 0 && RTF_RULE_LINE_REGEX.matches(lines[i - 1])
            val nextIsRule = i + 1 < lines.size && RTF_RULE_LINE_REGEX.matches(lines[i + 1])

            when {
                line == PRIVACY_NOTICE_MESSAGE -> {
                    sb.append("\\pard\\sa140\\f1\\fs20\\b\\cf2 ")
                    appendRtfEscapedText(sb, line)
                    sb.append("\\b0\\cf1\\f0\\fs20\\par\n")
                }
                line.isBlank() -> {
                    sb.append("\\pard\\f0\\fs20\\par\n")
                }
                RTF_RULE_LINE_REGEX.matches(line) -> {
                    sb.append("\\pard\\f0\\fs18\\cf3 ")
                    appendRtfEscapedText(sb, line)
                    sb.append("\\cf1\\fs20\\par\n")
                }
                (prevIsRule && nextIsRule) || RTF_SECTION_BANNER_REGEX.matches(line) -> {
                    sb.append("\\pard\\sb60\\sa40\\f1\\fs22\\b\\cf2 ")
                    appendRtfEscapedText(sb, line.trim())
                    sb.append("\\b0\\cf1\\f0\\fs20\\par\n")
                }
                else -> {
                    val kv = RTF_KEY_VALUE_REGEX.matchEntire(line)
                    if (kv != null && !line.trimStart().startsWith("192.") && !line.trimStart().startsWith("http")) {
                        sb.append("\\pard\\f0\\fs20\\cf1\\b ")
                        appendRtfEscapedText(sb, kv.groupValues[1])
                        sb.append("\\b0 ")
                        appendRtfEscapedText(sb, kv.groupValues[2])
                        sb.append("\\par\n")
                    } else {
                        sb.append("\\pard\\f0\\fs20\\cf1 ")
                        appendRtfEscapedText(sb, line)
                        sb.append("\\par\n")
                    }
                }
            }
        }

        sb.append("}")
        return sb.toString()
    }

    private fun appendRtfEscapedText(sb: StringBuilder, text: String) {
        for (ch in text) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '{' -> sb.append("\\{")
                ch == '}' -> sb.append("\\}")
                ch == '\t' -> sb.append("\\tab ")
                ch == '\r' -> Unit
                ch.code in 32..126 -> sb.append(ch)
                ch.code > 127 -> {
                    val signed16 = if (ch.code <= 32767) ch.code else ch.code - 65536
                    sb.append("\\u").append(signed16).append('?')
                }
                else -> sb.append(' ')
            }
        }
    }

    /**
     * Pipeline Steps 1-4:
     * 1. Validate raw report content.
     * 2. Apply automatic SSID -> "Hidden Network" and BSSID/MAC -> "Hidden MAC" redaction + privacy notice.
     * 3. Verify original sensitive SSIDs, BSSIDs, and MAC addresses no longer appear.
     * 4. Format as a readable .rtf document and re-verify zero identifier leakage in final RTF output.
     */
    @JvmStatic
    fun sanitizeAndConvertToRtf(
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        operation: String = "Preparing redacted RTF report",
        postRedactionTransform: ((String) -> String)? = null
    ): Result<String> {
        val sanitizedResult = sanitizeAndVerifyTextReport(
            rawContent = rawContent,
            identifiers = identifiers,
            operation = operation,
            postRedactionTransform = postRedactionTransform
        )
        val sanitizedText = sanitizedResult.getOrElse { return Result.failure(it) }
        return try {
            val rtfContent = convertPlainTextToRtf(sanitizedText)
            verifyPrivacyRedaction(rtfContent, identifiers)
            Result.success(rtfContent)
        } catch (e: PrivacyVerificationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                PrivacyVerificationException(
                    message = "RTF generation or privacy verification failed during '$operation': ${e.message ?: e::class.java.simpleName}",
                    leakedFieldType = "VerificationError"
                )
            )
        }
    }

    /**
     * Explicit verification check ensuring zero unredacted sensitive SSIDs, BSSIDs, or MAC addresses
     * exist in the prepared export text. Throws [PrivacyVerificationException] if any leak is detected.
     */
    @JvmStatic
    @Throws(PrivacyVerificationException::class)
    fun verifyPrivacyRedaction(
        sanitizedContent: String,
        identifiers: SensitiveWifiIdentifiers
    ) {
        val contentToVerify = sanitizedContent.replace(PRIVACY_NOTICE_MESSAGE, "")

        for (bssid in identifiers.bssids) {
            val clean = bssid.trim()
            if (!isPlaceholderOrStatusBssid(clean)) {
                if (contentToVerify.contains(clean, ignoreCase = true)) {
                    throw PrivacyVerificationException(
                        message = "Export blocked: original BSSID/MAC was still detected in sanitized output.",
                        leakedFieldType = "BSSID"
                    )
                }
            }
        }

        if (MAC_COLON_OR_DASH_REGEX.containsMatchIn(contentToVerify) ||
            MAC_DOT_REGEX.containsMatchIn(contentToVerify)
        ) {
            throw PrivacyVerificationException(
                message = "Export blocked: an unredacted hardware MAC address was detected in sanitized output.",
                leakedFieldType = "MAC"
            )
        }

        for (ssid in identifiers.ssids) {
            val clean = ssid.trim()
            if (isPlaceholderOrStatusSsid(clean)) continue
            val leaked = if (!isGenericDictionaryWord(clean)) {
                contentToVerify.contains(clean, ignoreCase = true)
            } else {
                // For generic dictionary-word SSIDs, verify key-value & table lines don't retain the unredacted SSID
                contentToVerify.lineSequence().any { line ->
                    val lower = line.lowercase(Locale.US)
                    val isConnectionOrApRow = lower.contains("ssid") ||
                        lower.contains(REDACTED_BSSID.lowercase(Locale.US))
                    isConnectionOrApRow &&
                        Regex("""(?<!\w)${Regex.escape(clean)}(?!\w)""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(line)
                }
            }
            if (leaked) {
                throw PrivacyVerificationException(
                    message = "Export blocked: original Wi-Fi network SSID was still detected in sanitized output.",
                    leakedFieldType = "SSID"
                )
            }
        }
    }

    private const val STAGED_EXPORTS_PREFS = "saf_staged_exports_prefs"
    private const val STAGED_EXPORTS_DIR_NAME = "staged_txt_exports"
    private const val KEY_LATEST_ACTIVE_ID = "latest_active_export_id"
    private const val KEY_RECORD_INDEX = "staged_record_index"
    private const val PREFIX_RECORD = "record_"
    private const val PREFIX_SCREEN_ACTIVE = "active_screen_"

    private const val STATUS_PENDING = "PENDING"
    private const val STATUS_IN_PROGRESS = "IN_PROGRESS"
    private const val STATUS_COMPLETED = "COMPLETED"
    private const val STATUS_CANCELLED = "CANCELLED"
    private const val STATUS_FAILED = "FAILED"

    private val STAGED_EXPORT_LOCK = Any()

    private data class StagedRecordMeta(
        val exportId: String,
        val stagedFileName: String,
        val suggestedFilename: String,
        val sha256: String,
        val byteLength: Int,
        val status: String,
        val createdAtMs: Long,
        val screenKey: String,
        val savedDisplayName: String = "",
        val includeWifiIdentifiers: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("exportId", exportId)
            put("stagedFileName", stagedFileName)
            put("suggestedFilename", suggestedFilename)
            put("sha256", sha256)
            put("byteLength", byteLength)
            put("status", status)
            put("createdAtMs", createdAtMs)
            put("screenKey", screenKey)
            put("savedDisplayName", savedDisplayName)
            put("includeWifiIdentifiers", includeWifiIdentifiers)
        }

        companion object {
            fun fromJson(raw: String?): StagedRecordMeta? {
                if (raw.isNullOrBlank()) return null
                return try {
                    val obj = JSONObject(raw)
                    StagedRecordMeta(
                        exportId = obj.getString("exportId"),
                        stagedFileName = obj.getString("stagedFileName"),
                        suggestedFilename = obj.optString("suggestedFilename", "Network_Report.rtf"),
                        sha256 = obj.getString("sha256"),
                        byteLength = obj.getInt("byteLength"),
                        status = obj.optString("status", STATUS_PENDING),
                        createdAtMs = obj.optLong("createdAtMs", 0L),
                        screenKey = obj.optString("screenKey", "global"),
                        savedDisplayName = obj.optString("savedDisplayName", ""),
                        includeWifiIdentifiers = obj.optBoolean("includeWifiIdentifiers", false)
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun getStagingDir(context: Context): File {
        val dir = File(context.filesDir, STAGED_EXPORTS_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Unable to create private staging directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    private fun loadRecord(prefs: android.content.SharedPreferences, exportId: String): StagedRecordMeta? {
        return StagedRecordMeta.fromJson(prefs.getString("$PREFIX_RECORD$exportId", null))
    }

    private fun saveRecordSync(
        prefs: android.content.SharedPreferences,
        record: StagedRecordMeta,
        setActive: Boolean = false
    ) {
        val editor = prefs.edit()
            .putString("$PREFIX_RECORD${record.exportId}", record.toJson().toString())

        if (setActive) {
            editor.putString(KEY_LATEST_ACTIVE_ID, record.exportId)
            editor.putString("$PREFIX_SCREEN_ACTIVE${record.screenKey}", record.exportId)
        } else {
            if (prefs.getString(KEY_LATEST_ACTIVE_ID, null) == record.exportId &&
                (record.status == STATUS_COMPLETED || record.status == STATUS_CANCELLED)
            ) {
                editor.remove(KEY_LATEST_ACTIVE_ID)
            }
            if (prefs.getString("$PREFIX_SCREEN_ACTIVE${record.screenKey}", null) == record.exportId &&
                (record.status == STATUS_COMPLETED || record.status == STATUS_CANCELLED)
            ) {
                editor.remove("$PREFIX_SCREEN_ACTIVE${record.screenKey}")
            }
        }

        // Maintain bounded index (latest 30 records) so metadata prefs never grows indefinitely
        val currentIdx = try {
            JSONArray(prefs.getString(KEY_RECORD_INDEX, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val list = mutableListOf<String>()
        for (i in 0 until currentIdx.length()) {
            val id = currentIdx.optString(i)
            if (!id.isNullOrBlank() && id != record.exportId) {
                list.add(id)
            }
        }
        list.add(record.exportId)
        while (list.size > 30) {
            val evictedId = list.removeAt(0)
            if (evictedId != record.exportId) {
                val evictedMeta = loadRecord(prefs, evictedId)
                if (evictedMeta == null || evictedMeta.status != STATUS_PENDING) {
                    editor.remove("$PREFIX_RECORD$evictedId")
                }
            }
        }
        val newIdx = JSONArray()
        list.forEach { newIdx.put(it) }
        editor.putString(KEY_RECORD_INDEX, newIdx.toString())
        editor.commit()
    }

    private fun cleanupExpiredStagedFiles(context: Context, prefs: android.content.SharedPreferences, keepId: String?) {
        try {
            val stagingDir = File(context.filesDir, STAGED_EXPORTS_DIR_NAME)
            if (!stagingDir.exists()) return
            val now = System.currentTimeMillis()
            val stagedCutoffMs = now - 24L * 60L * 60L * 1000L
            val tmpCutoffMs = now - 60L * 60L * 1000L
            val activeGlobal = prefs.getString(KEY_LATEST_ACTIVE_ID, null)
            stagingDir.listFiles()?.forEach { file ->
                val name = file.name
                val matchesKeep = keepId != null && name.contains(keepId)
                val matchesActive = activeGlobal != null && name.contains(activeGlobal)
                val cutoff = if (name.endsWith(".tmp")) tmpCutoffMs else stagedCutoffMs
                if (!matchesKeep && !matchesActive && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Cleans up stale temporary share cache files and expired SAF staged export artifacts
     * at app startup without touching Room database files or SurveyStore preferences.
     */
    @JvmStatic
    fun cleanupStartupTempFiles(context: Context) {
        cleanupExpiredShareCacheFiles(context)
        synchronized(STAGED_EXPORT_LOCK) {
            try {
                val prefs = context.getSharedPreferences(STAGED_EXPORTS_PREFS, Context.MODE_PRIVATE)
                cleanupExpiredStagedFiles(context, prefs, keepId = null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Stages an already-redacted and privacy-verified RTF report atomically into app-private storage
     * and persists metadata synchronously in SharedPreferences BEFORE launching SAF ACTION_CREATE_DOCUMENT.
     * Ensures survival across Activity recreation and process death while the file picker is open.
     */
    @JvmStatic
    fun stageRedactedTextReport(
        context: Context,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        suggestedFilename: String,
        screenKey: String = "global"
    ): StageExportResult {
        return stageTextReportForExport(
            context = context,
            rawContent = rawContent,
            identifiers = identifiers,
            suggestedFilename = suggestedFilename,
            screenKey = screenKey,
            privacyChoice = WifiReportExportPrivacyChoice.REDACTED
        )
    }

    @JvmStatic
    @JvmOverloads
    fun stageTextReportForExport(
        context: Context,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        suggestedFilename: String,
        screenKey: String = "global",
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): StageExportResult {
        val cleanFilename = ensureRtfExtension(suggestedFilename.ifBlank { "Network_Toolkit_Report.rtf" })
        val isUnredacted = privacyChoice == WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        val rtfResult = if (isUnredacted) {
            prepareTextReportForExport(
                rawContent = rawContent,
                identifiers = identifiers,
                privacyChoice = privacyChoice,
                operation = "Preparing user-confirmed unredacted RTF report"
            ).map { convertPlainTextToRtf(it) }
        } else {
            sanitizeAndConvertToRtf(
                rawContent = rawContent,
                identifiers = identifiers,
                operation = "Privacy redaction & verification before staging SAF RTF export"
            )
        }
        val rtfContent = rtfResult.getOrElse { err ->
            return StageExportResult.Failed(
                classifyException(err, "Preparing RTF report before staging SAF export")
            )
        }

        val bytes = rtfContent.toByteArray(Charsets.UTF_8)
        val exportId = UUID.randomUUID().toString()
        val stagedFileName = "staged_${exportId}.rtf"
        val tempFileName = "staged_${exportId}.rtf.tmp"

        return synchronized(STAGED_EXPORT_LOCK) {
            var tempFile: File? = null
            try {
                val stagingDir = getStagingDir(context)
                val prefs = context.getSharedPreferences(STAGED_EXPORTS_PREFS, Context.MODE_PRIVATE)
                cleanupExpiredStagedFiles(context, prefs, keepId = exportId)

                val targetFile = File(stagingDir, stagedFileName)
                tempFile = File(stagingDir, tempFileName)

                FileOutputStream(tempFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Exception) {
                    }
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                tempFile = null

                val checksum = sha256Hex(bytes)
                val record = StagedRecordMeta(
                    exportId = exportId,
                    stagedFileName = stagedFileName,
                    suggestedFilename = cleanFilename,
                    sha256 = checksum,
                    byteLength = bytes.size,
                    status = STATUS_PENDING,
                    createdAtMs = System.currentTimeMillis(),
                    screenKey = screenKey,
                    includeWifiIdentifiers = isUnredacted
                )
                saveRecordSync(prefs, record, setActive = true)

                StageExportResult.Ready(
                    StagedTextExport(
                        exportId = exportId,
                        stagedFilePath = targetFile.absolutePath,
                        suggestedFilename = cleanFilename,
                        byteLength = bytes.size,
                        sha256 = checksum,
                        screenKey = screenKey
                    )
                )
            } catch (t: Throwable) {
                try {
                    tempFile?.delete()
                } catch (_: Exception) {
                }
                StageExportResult.Failed(
                    classifyException(t, "Staging RTF report before opening file picker")
                )
            }
        }
    }

    @JvmStatic
    fun resolvePendingExportId(
        context: Context,
        explicitExportId: String?,
        screenKey: String = "global"
    ): String? {
        val prefs = context.getSharedPreferences(STAGED_EXPORTS_PREFS, Context.MODE_PRIVATE)
        if (!explicitExportId.isNullOrBlank() && loadRecord(prefs, explicitExportId) != null) {
            return explicitExportId
        }
        val byScreen = prefs.getString("$PREFIX_SCREEN_ACTIVE$screenKey", null)
        if (!byScreen.isNullOrBlank() && loadRecord(prefs, byScreen) != null) {
            return byScreen
        }
        val globalId = prefs.getString(KEY_LATEST_ACTIVE_ID, null)
        if (!globalId.isNullOrBlank() && loadRecord(prefs, globalId) != null) {
            return globalId
        }
        return null
    }

    /**
     * Cleans up staged temporary report when the user cancels the SAF document picker.
     */
    @JvmStatic
    fun handleSafPickerCancelled(
        context: Context,
        explicitExportId: String?,
        screenKey: String = "global"
    ): TextExportOutcome {
        return synchronized(STAGED_EXPORT_LOCK) {
            val prefs = context.getSharedPreferences(STAGED_EXPORTS_PREFS, Context.MODE_PRIVATE)
            val resolvedId = resolvePendingExportId(context, explicitExportId, screenKey)
            if (resolvedId != null) {
                val record = loadRecord(prefs, resolvedId)
                if (record != null) {
                    if (record.status == STATUS_COMPLETED || record.status == STATUS_CANCELLED) {
                        return@synchronized TextExportOutcome.AlreadyProcessed(
                            exportId = resolvedId,
                            displayName = record.savedDisplayName.ifBlank { record.suggestedFilename }
                        )
                    }
                    try {
                        val file = File(File(context.filesDir, STAGED_EXPORTS_DIR_NAME), record.stagedFileName)
                        if (file.exists()) file.delete()
                    } catch (_: Exception) {
                    }
                    saveRecordSync(prefs, record.copy(status = STATUS_CANCELLED), setActive = false)
                }
            }
            TextExportOutcome.Cancelled
        }
    }

    /**
     * Completes a staged SAF export after ActivityResult returns:
     * - Resolves the staged export record (using [explicitExportId] or persisted SharedPreferences pointer).
     * - Prevents duplicate writes if the callback is delivered more than once.
     * - Verifies staged file existence & SHA-256 integrity without losing data on write failures.
     * - Writes UTF-8 bytes via ContentResolver.openOutputStream, flushes, and closes stream.
     * - Cleans up the staged file only after successful export.
     */
    @JvmStatic
    fun exportStagedReportToUri(
        context: Context,
        explicitExportId: String?,
        uri: Uri?,
        fallbackFilename: String = "Network_Report.rtf",
        screenKey: String = "global"
    ): TextExportOutcome {
        if (uri == null || uri == Uri.EMPTY) {
            return TextExportOutcome.Failure(
                category = ExportErrorCategory.INVALID_DESTINATION_URI,
                userMessage = "The storage picker did not return a valid destination URI.",
                exceptionName = IllegalArgumentException::class.java.simpleName,
                exceptionMessage = "Destination Uri was null or empty",
                operation = "Validating SAF destination URI"
            )
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "content" && scheme != "file") {
            return TextExportOutcome.Failure(
                category = ExportErrorCategory.INVALID_DESTINATION_URI,
                userMessage = "Unsupported destination URI scheme '$scheme'. Expected a content:// or file:// document URI.",
                exceptionName = IllegalArgumentException::class.java.simpleName,
                exceptionMessage = "Unsupported URI: $uri",
                operation = "Validating SAF destination URI"
            )
        }

        val prefs = context.getSharedPreferences(STAGED_EXPORTS_PREFS, Context.MODE_PRIVATE)
        val record: StagedRecordMeta
        val stagedFile: File

        synchronized(STAGED_EXPORT_LOCK) {
            val resolvedId = resolvePendingExportId(context, explicitExportId, screenKey)
            if (resolvedId == null) {
                return classifyException(
                    StagedExportMissingException(
                        "No persisted staged export metadata was found for id='${explicitExportId ?: "none"}' (screen='$screenKey')."
                    ),
                    operation = "Restoring persisted staged SAF export"
                )
            }

            val loaded = loadRecord(prefs, resolvedId)
            if (loaded == null) {
                return classifyException(
                    StagedExportMissingException(
                        "Persisted export metadata record '$resolvedId' could not be loaded."
                    ),
                    operation = "Restoring persisted staged SAF export"
                )
            }

            if (loaded.status == STATUS_COMPLETED ||
                loaded.status == STATUS_CANCELLED ||
                loaded.status == STATUS_IN_PROGRESS
            ) {
                return TextExportOutcome.AlreadyProcessed(
                    exportId = loaded.exportId,
                    displayName = loaded.savedDisplayName.ifBlank { loaded.suggestedFilename }
                )
            }

            stagedFile = File(File(context.filesDir, STAGED_EXPORTS_DIR_NAME), loaded.stagedFileName)
            if (!stagedFile.exists() || !stagedFile.isFile) {
                saveRecordSync(prefs, loaded.copy(status = STATUS_FAILED), setActive = false)
                return classifyException(
                    StagedExportMissingException(
                        "Staged report file is missing at '${stagedFile.absolutePath}'."
                    ),
                    operation = "Locating staged SAF RTF file on disk"
                )
            }

            record = loaded
            saveRecordSync(prefs, loaded.copy(status = STATUS_IN_PROGRESS), setActive = true)
        }

        var currentOperation = "Reading staged SAF RTF file"
        return try {
            val bytes = stagedFile.readBytes()
            if (bytes.size != record.byteLength || sha256Hex(bytes) != record.sha256) {
                synchronized(STAGED_EXPORT_LOCK) {
                    saveRecordSync(prefs, record.copy(status = STATUS_FAILED), setActive = false)
                }
                return classifyException(
                    StagedExportCorruptedException(
                        "Staged report file integrity check failed (expected ${record.byteLength} bytes / ${record.sha256}, actual ${bytes.size} bytes)."
                    ),
                    operation = "Verifying staged SAF RTF integrity"
                )
            }

            if (!record.includeWifiIdentifiers) {
                currentOperation = "Verifying staged privacy redaction before write"
                val decoded = bytes.toString(Charsets.UTF_8)
                verifyPrivacyRedaction(decoded, collectIdentifiers(context))
            }

            currentOperation = "Opening destination output stream"
            val outputStream: OutputStream = openSafOutputStream(context, uri)
            currentOperation = "Writing report bytes to destination stream"
            outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }

            val resolvedName = resolveDisplayName(
                context,
                uri,
                record.suggestedFilename.ifBlank { fallbackFilename }
            )
            synchronized(STAGED_EXPORT_LOCK) {
                saveRecordSync(
                    prefs,
                    record.copy(status = STATUS_COMPLETED, savedDisplayName = resolvedName),
                    setActive = false
                )
                try {
                    if (stagedFile.exists()) stagedFile.delete()
                } catch (_: Exception) {
                }
            }

            TextExportOutcome.Success(
                uri = uri,
                displayName = resolvedName,
                bytesWritten = bytes.size,
                isWifiUnredacted = record.includeWifiIdentifiers
            )
        } catch (t: Throwable) {
            // Preserve staged file & restore PENDING status so failed write does not destroy the report
            synchronized(STAGED_EXPORT_LOCK) {
                if (t !is PrivacyVerificationException) {
                    saveRecordSync(prefs, record.copy(status = STATUS_PENDING), setActive = true)
                } else {
                    saveRecordSync(prefs, record.copy(status = STATUS_FAILED), setActive = false)
                }
            }
            classifyException(t, currentOperation)
        }
    }

    private fun ensureRtfExtension(filename: String): String {
        val trimmed = filename.trim().ifBlank { "Network_Toolkit_Report.rtf" }
        return when {
            trimmed.endsWith(".rtf", ignoreCase = true) -> trimmed
            trimmed.endsWith(".txt", ignoreCase = true) -> trimmed.dropLast(4) + ".rtf"
            trimmed.endsWith(".text", ignoreCase = true) -> trimmed.dropLast(5) + ".rtf"
            else -> "$trimmed.rtf"
        }
    }

    /**
     * Direct SAF RTF export pipeline (retained for direct URI writes / unit testing):
     * 1. Generates sanitized RTF report + runs privacy verification.
     * 2. Opens SAF URI output stream with proper truncation/write modes.
     * 3. Writes RTF bytes, flushes, and closes stream cleanly.
     * 4. Confirms completion & resolves saved display name or returns structured diagnostics.
     */
    @JvmStatic
    @JvmOverloads
    fun exportTextReportToUri(
        context: Context,
        uri: Uri?,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        fallbackFilename: String = "Network_Report.rtf",
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): TextExportOutcome {
        if (uri == null || uri == Uri.EMPTY) {
            return TextExportOutcome.Failure(
                category = ExportErrorCategory.INVALID_DESTINATION_URI,
                userMessage = "The storage picker did not return a valid destination URI.",
                exceptionName = IllegalArgumentException::class.java.simpleName,
                exceptionMessage = "Destination Uri was null or empty",
                operation = "Validating SAF destination URI"
            )
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "content" && scheme != "file") {
            return TextExportOutcome.Failure(
                category = ExportErrorCategory.INVALID_DESTINATION_URI,
                userMessage = "Unsupported destination URI scheme '$scheme'. Expected a content:// or file:// document URI.",
                exceptionName = IllegalArgumentException::class.java.simpleName,
                exceptionMessage = "Unsupported URI: $uri",
                operation = "Validating SAF destination URI"
            )
        }

        val isUnredacted = privacyChoice == WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        val opLabel = if (isUnredacted) {
            "Preparing user-confirmed unredacted RTF report before SAF write"
        } else {
            "Privacy redaction & RTF verification before SAF write"
        }
        val rtfResult = if (isUnredacted) {
            prepareTextReportForExport(
                rawContent = rawContent,
                identifiers = identifiers,
                privacyChoice = privacyChoice,
                operation = opLabel
            ).map { convertPlainTextToRtf(it) }
        } else {
            sanitizeAndConvertToRtf(
                rawContent = rawContent,
                identifiers = identifiers,
                operation = opLabel
            )
        }
        val rtfContent = rtfResult.getOrElse { error ->
            return classifyException(
                throwable = error,
                operation = opLabel
            )
        }

        val bytes = rtfContent.toByteArray(Charsets.UTF_8)
        var currentOperation = "Opening destination output stream"

        return try {
            val outputStream: OutputStream = openSafOutputStream(context, uri)
            currentOperation = "Writing report bytes to destination stream"
            outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            val resolvedName = resolveDisplayName(context, uri, ensureRtfExtension(fallbackFilename))
            TextExportOutcome.Success(
                uri = uri,
                displayName = resolvedName,
                bytesWritten = bytes.size,
                isWifiUnredacted = isUnredacted
            )
        } catch (t: Throwable) {
            classifyException(t, currentOperation)
        }
    }

    private fun openSafOutputStream(context: Context, uri: Uri): OutputStream {
        val resolver = context.contentResolver
        for (mode in listOf("wt", "w")) {
            try {
                resolver.openOutputStream(uri, mode)?.let { return it }
            } catch (e: SecurityException) {
                throw e
            } catch (e: FileNotFoundException) {
                throw e
            } catch (_: IllegalArgumentException) {
                // Provider may not accept mode string; try next
            } catch (_: UnsupportedOperationException) {
                // Provider may not accept mode string; try next
            }
        }

        return resolver.openOutputStream(uri)
            ?: throw IOException("Storage provider returned a null OutputStream for URI: $uri")
    }

    /**
     * Cleans up expired temporary share files from cacheDir/reports to avoid lingering diagnostic files.
     */
    @JvmStatic
    @JvmOverloads
    fun cleanupExpiredShareCacheFiles(
        context: Context,
        maxAgeMs: Long = 60L * 60L * 1000L,
        keepFilename: String? = null
    ) {
        try {
            val reportsDir = File(context.cacheDir, "reports")
            if (!reportsDir.exists()) return
            val cutoffMs = System.currentTimeMillis() - maxAgeMs
            reportsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name != keepFilename && file.lastModified() < cutoffMs) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Copies sensitive plain text to the Android clipboard and marks it with
     * ClipDescription.EXTRA_IS_SENSITIVE on Android 13+ (API 33+) so OS clipboard overlays
     * mask the content.
     */
    @JvmStatic
    @JvmOverloads
    fun copySensitiveToClipboard(
        context: Context,
        label: String,
        text: String,
        markSensitive: Boolean = true
    ): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = ClipData.newPlainText(label, text)
        if (markSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = clip.description.extras ?: PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
        return true
    }

    /**
     * Sanitizes a diagnostic text or JSON report (removing SSIDs/BSSIDs/MACs and verifying
     * zero leakage) before placing it onto the clipboard with optional EXTRA_IS_SENSITIVE flag.
     */
    @JvmStatic
    @JvmOverloads
    fun copyRedactedToClipboard(
        context: Context,
        label: String,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers = collectIdentifiers(context),
        isJson: Boolean = false,
        markSensitive: Boolean = true
    ): Result<String> {
        val sanitizedResult = if (isJson) {
            sanitizeAndVerifyJsonReport(
                rawJson = rawContent,
                identifiers = identifiers,
                operation = "Sanitizing JSON report for clipboard"
            )
        } else {
            sanitizeAndVerifyTextReport(
                rawContent = rawContent,
                identifiers = identifiers,
                operation = "Sanitizing text report for clipboard"
            )
        }
        return sanitizedResult.mapCatching { sanitized ->
            verifyPrivacyRedaction(sanitized, identifiers)
            copySensitiveToClipboard(context, label, sanitized, markSensitive = markSensitive)
            sanitized
        }
    }

    /**
     * Shares a privacy-redacted RTF or JSON report via FileProvider
     * using cacheDir/reports (configured in res/xml/file_paths.xml).
     * Both RTF and JSON exports strictly enforce redaction and post-redaction verification.
     */
    @JvmStatic
    fun createRedactedShareIntent(
        context: Context,
        rawContent: String,
        filename: String,
        subject: String,
        chooserTitle: String = "Share Diagnostic Report",
        identifiers: SensitiveWifiIdentifiers = collectIdentifiers(context),
        isJson: Boolean = false
    ): Result<Intent> {
        return createShareIntentWithPrivacy(
            context = context,
            rawContent = rawContent,
            filename = filename,
            subject = subject,
            chooserTitle = chooserTitle,
            identifiers = identifiers,
            isJson = isJson,
            privacyChoice = WifiReportExportPrivacyChoice.REDACTED
        )
    }

    @JvmStatic
    @JvmOverloads
    fun createShareIntentWithPrivacy(
        context: Context,
        rawContent: String,
        filename: String,
        subject: String,
        chooserTitle: String = "Share Diagnostic Report",
        identifiers: SensitiveWifiIdentifiers = collectIdentifiers(context),
        isJson: Boolean = false,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ): Result<Intent> {
        val isUnredacted = privacyChoice == WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        val operation = if (isUnredacted) {
            if (isJson) "Preparing unredacted JSON share file" else "Preparing unredacted RTF share file"
        } else {
            if (isJson) "Redacting & preparing JSON share file" else "Redacting & preparing RTF share file"
        }
        var reportFile: File? = null
        return try {
            val preparedContent = if (isJson) {
                prepareJsonReportForExport(
                    rawJson = rawContent,
                    identifiers = identifiers,
                    privacyChoice = privacyChoice,
                    operation = operation
                ).getOrThrow()
            } else {
                prepareTextReportForExport(
                    rawContent = rawContent,
                    identifiers = identifiers,
                    privacyChoice = privacyChoice,
                    operation = operation
                ).getOrThrow()
            }

            val fileContent = if (isJson) {
                preparedContent
            } else {
                convertPlainTextToRtf(preparedContent)
            }

            if (!isUnredacted) {
                verifyPrivacyRedaction(fileContent, identifiers)
            }

            val resolvedFilename = if (isJson) filename else ensureRtfExtension(filename)

            val reportsDir = File(context.cacheDir, "reports")
            if (!reportsDir.exists() && !reportsDir.mkdirs()) {
                throw IOException("Unable to create cache directory: ${reportsDir.absolutePath}")
            }

            cleanupExpiredShareCacheFiles(context, keepFilename = resolvedFilename)

            val targetFile = File(reportsDir, resolvedFilename)
            reportFile = targetFile
            val bytes = fileContent.toByteArray(Charsets.UTF_8)
            FileOutputStream(targetFile).use { stream ->
                stream.write(bytes)
                stream.flush()
            }

            if (!isUnredacted) {
                val writtenContent = targetFile.readText(Charsets.UTF_8)
                verifyPrivacyRedaction(writtenContent, identifiers)
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, targetFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (isJson) "application/json" else MIME_RTF
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, preparedContent)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Result.success(Intent.createChooser(shareIntent, chooserTitle))
        } catch (t: Throwable) {
            runCatching { reportFile?.takeIf { it.exists() }?.delete() }
            Result.failure(t)
        }
    }

    data class ShareIntentPreparation(
        val chooserIntent: Intent?,
        val failure: TextExportOutcome.Failure?
    )

    @JvmStatic
    fun prepareRedactedShareIntent(
        context: Context,
        rawContent: String,
        filename: String,
        subject: String,
        chooserTitle: String
    ): ShareIntentPreparation {
        return prepareShareIntentWithPrivacy(
            context = context,
            rawContent = rawContent,
            filename = filename,
            subject = subject,
            chooserTitle = chooserTitle,
            privacyChoice = WifiReportExportPrivacyChoice.REDACTED
        )
    }

    @JvmStatic
    fun prepareShareIntentWithPrivacy(
        context: Context,
        rawContent: String,
        filename: String,
        subject: String,
        chooserTitle: String,
        privacyChoice: WifiReportExportPrivacyChoice
    ): ShareIntentPreparation {
        val operation = if (privacyChoice == WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED) {
            "Preparing unredacted RTF report for sharing"
        } else {
            "Preparing privacy-redacted RTF report for sharing"
        }
        val result = createShareIntentWithPrivacy(
            context = context,
            rawContent = rawContent,
            filename = filename,
            subject = subject,
            chooserTitle = chooserTitle,
            identifiers = collectIdentifiers(context),
            isJson = false,
            privacyChoice = privacyChoice
        )
        return result.fold(
            onSuccess = { ShareIntentPreparation(chooserIntent = it, failure = null) },
            onFailure = { err ->
                ShareIntentPreparation(
                    chooserIntent = null,
                    failure = classifyException(err, operation)
                )
            }
        )
    }

    @JvmStatic
    fun classifyException(
        throwable: Throwable,
        operation: String
    ): TextExportOutcome.Failure {
        val exName = throwable::class.java.simpleName.ifBlank { "Exception" }
        val exMsg = throwable.message ?: ""
        val lowerMsg = exMsg.lowercase(Locale.US)
        val causeMsg = throwable.cause?.message?.lowercase(Locale.US) ?: ""
        val combined = "$lowerMsg $causeMsg"

        val (category, userMsg) = when (throwable) {
            is PrivacyVerificationException -> {
                ExportErrorCategory.PRIVACY_VERIFICATION_FAILED to
                    "Export aborted before writing because sensitive Wi-Fi network identification (${throwable.leakedFieldType}) failed privacy verification."
            }
            is StagedExportMissingException -> {
                ExportErrorCategory.STAGED_FILE_MISSING to
                    "The staged report file could not be found after returning from the file picker."
            }
            is StagedExportCorruptedException -> {
                ExportErrorCategory.STAGED_FILE_CORRUPTED to
                    "The staged report file failed its size/checksum integrity verification and was not written."
            }
            is ActivityNotFoundException -> {
                ExportErrorCategory.STORAGE_PROVIDER_UNAVAILABLE to
                    "No Android file picker or storage provider app was found to select a destination folder."
            }
            is SecurityException -> {
                ExportErrorCategory.PERMISSION_OR_URI_DENIED to
                    "Android or the storage provider denied write permission for the selected file or URI."
            }
            is FileNotFoundException -> {
                ExportErrorCategory.FILE_CREATION_OR_STREAM_ERROR to
                    "The destination file could not be created or opened by the storage provider."
            }
            is IOException -> {
                if (combined.contains("enospc") ||
                    combined.contains("no space") ||
                    combined.contains("disk full") ||
                    combined.contains("quota") ||
                    combined.contains("edquot") ||
                    combined.contains("storage full")
                ) {
                    ExportErrorCategory.INSUFFICIENT_STORAGE to
                        "Insufficient free storage space to finish writing the exported report."
                } else {
                    ExportErrorCategory.IO_WRITE_ERROR to
                        "An I/O error occurred while communicating with the storage provider or writing the file."
                }
            }
            is IllegalArgumentException -> {
                if (combined.contains("empty")) {
                    ExportErrorCategory.EMPTY_REPORT to
                        "There is no report content available to export yet."
                } else {
                    ExportErrorCategory.INVALID_DESTINATION_URI to
                        "The selected destination URI or FileProvider configuration is invalid."
                }
            }
            is IllegalStateException -> {
                ExportErrorCategory.FILE_CREATION_OR_STREAM_ERROR to
                    "Could not open or initialize the destination file stream."
            }
            else -> {
                ExportErrorCategory.UNEXPECTED_ERROR to
                    "An unexpected error occurred during export."
            }
        }

        return TextExportOutcome.Failure(
            category = category,
            userMessage = userMsg,
            exceptionName = exName,
            exceptionMessage = exMsg,
            operation = operation
        )
    }

    private fun resolveDisplayName(context: Context, uri: Uri, fallback: String): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
