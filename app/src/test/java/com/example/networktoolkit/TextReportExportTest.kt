package com.example.networktoolkit

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.ExportErrorCategory
import com.example.networktoolkit.data.PrivacyVerificationException
import com.example.networktoolkit.data.SafTextExportController
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.StageExportResult
import com.example.networktoolkit.data.TextExportOutcome
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.signalmap.FloorPlanTemplates
import com.example.networktoolkit.signalmap.LiveConnectedTelemetry
import com.example.networktoolkit.signalmap.PropertyType
import com.example.networktoolkit.signalmap.SignalMapEngine
import com.example.networktoolkit.signalmap.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextReportExportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `signal map report redacts ssid and bssid, adds privacy notice, preserves diagnostics and unavailable snr`() {
        val secretSsid = "CorpFiber_5G_Lab"
        val secretBssid = "AA:BB:CC:11:22:33"
        val altBssid = "de:ad:be:ef:00:99"

        val rooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.HOUSE)
        val samples = listOf(
            SignalSample(
                roomName = "Living / Great Room",
                relX = 0.45f,
                relY = 0.25f,
                rssiDbm = -48,
                snrDb = 44,
                noiseFloorDbm = -92,
                linkSpeedMbps = 866,
                frequencyMhz = 5180,
                channel = 36,
                bssid = secretBssid,
                ssid = secretSsid
            ),
            SignalSample(
                roomName = "Attached Garage",
                relX = 0.85f,
                relY = 0.70f,
                rssiDbm = -84,
                snrDb = null,
                noiseFloorDbm = null,
                linkSpeedMbps = 72,
                frequencyMhz = 2437,
                channel = 6,
                bssid = altBssid,
                ssid = secretSsid
            )
        )
        val telemetry = LiveConnectedTelemetry(
            isConnected = true,
            ssid = secretSsid,
            bssid = secretBssid,
            currentRssiDbm = -48,
            snrDb = null,
            noiseFloorDbm = null,
            linkSpeedMbps = 866,
            frequencyMhz = 5180,
            channel = 36,
            isWifiEnabled = true
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = SignalMapEngine(context)
        val analysis = engine.calculateCoverageAnalysis(PropertyType.HOUSE, rooms, samples)
        val rawReport = engine.generateTextReport(
            propertyType = PropertyType.HOUSE,
            rooms = rooms,
            samples = samples,
            analysis = analysis,
            telemetry = telemetry
        )

        assertTrue(rawReport.contains(secretSsid))
        assertTrue(rawReport.contains(secretBssid))
        assertEquals(secretSsid, telemetry.ssid)
        assertEquals(secretBssid, telemetry.bssid)

        val identifiers = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid, altBssid)
        )
        val sanitized = TextReportExportManager
            .sanitizeAndVerifyTextReport(rawReport, identifiers)
            .getOrThrow()

        assertTrue(sanitized.contains(TextReportExportManager.PRIVACY_NOTICE_MESSAGE))
        assertFalse(sanitized.contains(secretSsid, ignoreCase = true))
        assertFalse(sanitized.contains(secretBssid, ignoreCase = true))
        assertFalse(sanitized.contains(altBssid, ignoreCase = true))
        assertTrue(sanitized.contains(TextReportExportManager.REDACTED_SSID))
        assertTrue(sanitized.contains(TextReportExportManager.REDACTED_BSSID))
        assertTrue(sanitized.contains("-48 dBm"))
        assertTrue(sanitized.contains("-84 dBm"))
        assertTrue(sanitized.contains("44 dB"))
        assertTrue(sanitized.contains("Unavailable"))
    }

    @Test
    fun `privacy verification blocks export if original ssid or mac remains after redaction`() {
        val rawReport = """
            ============================================================
            Connected SSID   : StudioGateway_24
            Connected BSSID  : 12:34:56:78:9A:BC
            Avg Signal       : -55 dBm
            ============================================================
        """.trimIndent()
        val ids = SensitiveWifiIdentifiers(
            ssids = setOf("StudioGateway_24"),
            bssids = setOf("12:34:56:78:9A:BC")
        )

        val leakedSsidAttempt = TextReportExportManager.sanitizeAndVerifyTextReport(
            rawContent = rawReport,
            identifiers = ids,
            postRedactionTransform = { it + "\nLeaked debug: StudioGateway_24" }
        )
        assertTrue(leakedSsidAttempt.isFailure)
        val ssidErr = leakedSsidAttempt.exceptionOrNull()
        assertTrue(ssidErr is PrivacyVerificationException)
        assertEquals("SSID", (ssidErr as PrivacyVerificationException).leakedFieldType)

        val leakedMacAttempt = TextReportExportManager.sanitizeAndVerifyTextReport(
            rawContent = rawReport,
            identifiers = ids,
            postRedactionTransform = { it + "\nExtra AP: 99-88-77-66-55-44" }
        )
        assertTrue(leakedMacAttempt.isFailure)
        assertTrue(leakedMacAttempt.exceptionOrNull() is PrivacyVerificationException)
    }

    @Test
    fun `short or generic dictionary word ssid redacts only wifi contexts without corrupting labels`() {
        val rawReport = """
            Connected SSID   : Home
            Connected BSSID  : F0:18:98:11:22:33
            Location         : Home Living Room
            Recommendation   : Good home Wi-Fi coverage
        """.trimIndent()
        val ids = SensitiveWifiIdentifiers(
            ssids = setOf("Home"),
            bssids = setOf("F0:18:98:11:22:33")
        )

        val sanitized = TextReportExportManager.sanitizeAndVerifyTextReport(rawReport, ids).getOrThrow()
        assertTrue(sanitized.contains("Connected SSID   : ${TextReportExportManager.REDACTED_SSID}"))
        assertTrue(sanitized.contains("Connected BSSID  : ${TextReportExportManager.REDACTED_BSSID}"))
        assertTrue(sanitized.contains("Location         : Home Living Room"))
        assertTrue(sanitized.contains("Recommendation   : Good home Wi-Fi coverage"))
    }

    @Test
    fun `staged export persists redacted rtf report on disk, writes to destination uri, and cleans up`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destFile = File.createTempFile("staged_saf_dest_", ".rtf", context.cacheDir)
        try {
            val rawReport = """
                NETWORK TOOLKIT — UTF-8 TEST (5 GHz • °C ✓ Ω) {braces}\slash
                Connected SSID   : SamsungGalaxyHome5G
                Connected BSSID  : AA:11:BB:22:CC:33
                Signal Level     : -57 dBm
            """.trimIndent()
            val ids = SensitiveWifiIdentifiers(
                ssids = setOf("SamsungGalaxyHome5G"),
                bssids = setOf("AA:11:BB:22:CC:33")
            )

            val stageResult = TextReportExportManager.stageRedactedTextReport(
                context = context,
                rawContent = rawReport,
                identifiers = ids,
                suggestedFilename = "WiFi_Signal_Map_Report.rtf",
                screenKey = "signal_map"
            )
            assertTrue(stageResult is StageExportResult.Ready)
            val staged = (stageResult as StageExportResult.Ready).staged
            assertTrue(staged.stagedFilePath.endsWith(".rtf"))
            assertTrue(staged.suggestedFilename.endsWith(".rtf"))
            val stagedFile = File(staged.stagedFilePath)
            assertTrue("Staged file must exist on disk before picker launches", stagedFile.exists())

            // Staged file itself must already be valid RTF, redacted, and contain privacy notice
            val stagedContent = stagedFile.readText(Charsets.UTF_8)
            assertTrue(stagedContent.startsWith("{\\rtf1\\ansi"))
            assertTrue(stagedContent.endsWith("}"))
            assertTrue(stagedContent.contains("Courier New"))
            assertTrue(stagedContent.contains(TextReportExportManager.PRIVACY_NOTICE_MESSAGE))
            assertFalse(stagedContent.contains("SamsungGalaxyHome5G"))
            assertFalse(stagedContent.contains("AA:11:BB:22:CC:33"))

            val outcome = TextReportExportManager.exportStagedReportToUri(
                context = context,
                explicitExportId = staged.exportId,
                uri = Uri.fromFile(destFile),
                fallbackFilename = "WiFi_Signal_Map_Report.rtf",
                screenKey = "signal_map"
            )
            assertTrue("Expected Success but got $outcome", outcome is TextExportOutcome.Success)
            val success = outcome as TextExportOutcome.Success
            assertEquals(staged.byteLength, success.bytesWritten)

            val written = destFile.readText(Charsets.UTF_8)
            assertEquals(stagedContent, written)
            // Verify Unicode RTF \u escapes and escaped braces/backslashes
            assertTrue(written.contains("\\u8212?")) // em-dash —
            assertTrue(written.contains("\\u8226?")) // bullet •
            assertTrue(written.contains("\\u176?"))  // degree °
            assertTrue(written.contains("\\u10003?")) // checkmark ✓
            assertTrue(written.contains("\\u937?"))  // Omega Ω
            assertTrue(written.contains("\\{braces\\}\\\\slash"))
            assertFalse("Staged file must be cleaned up after successful export", stagedFile.exists())

            // Duplicate callback delivery must be safely ignored without rewriting
            destFile.writeText("SENTINEL", Charsets.UTF_8)
            val duplicateOutcome = TextReportExportManager.exportStagedReportToUri(
                context = context,
                explicitExportId = staged.exportId,
                uri = Uri.fromFile(destFile),
                fallbackFilename = "WiFi_Signal_Map_Report.rtf",
                screenKey = "signal_map"
            )
            assertTrue(duplicateOutcome is TextExportOutcome.AlreadyProcessed)
            assertEquals("SENTINEL", destFile.readText(Charsets.UTF_8))
        } finally {
            destFile.delete()
        }
    }

    @Test
    fun `staged export recovers when in-memory exportId is lost after process recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destFile = File.createTempFile("process_death_recovery_", ".rtf", context.cacheDir)
        try {
            val ids = SensitiveWifiIdentifiers(
                ssids = setOf("ApartmentMesh_6E"),
                bssids = setOf("11:22:33:44:55:66")
            )
            val stageResult = TextReportExportManager.stageRedactedTextReport(
                context = context,
                rawContent = "SSID: ApartmentMesh_6E\nBSSID: 11:22:33:44:55:66\nRSSI: -61 dBm\nSNR: 29 dB",
                identifiers = ids,
                suggestedFilename = "Recovered_Report.rtf",
                screenKey = "wifi_scan"
            )
            val staged = (stageResult as StageExportResult.Ready).staged

            // Simulate process death losing the Activity's in-memory exportId string (explicitExportId = null)
            val recoveredOutcome = TextReportExportManager.exportStagedReportToUri(
                context = context,
                explicitExportId = null,
                uri = Uri.fromFile(destFile),
                fallbackFilename = "Fallback.rtf",
                screenKey = "wifi_scan"
            )
            assertTrue("Expected Success after process recovery but got $recoveredOutcome", recoveredOutcome is TextExportOutcome.Success)
            val saved = destFile.readText(Charsets.UTF_8)
            assertTrue(saved.startsWith("{\\rtf1\\ansi"))
            assertTrue(saved.contains(TextReportExportManager.PRIVACY_NOTICE_MESSAGE))
            assertFalse(saved.contains("ApartmentMesh_6E"))
            assertFalse(saved.contains("11:22:33:44:55:66"))
            assertTrue(saved.contains("-61 dBm"))
            assertTrue(saved.contains("29 dB"))
            assertFalse(File(staged.stagedFilePath).exists())
        } finally {
            destFile.delete()
        }
    }

    @Test
    fun `compose controller survives state restoration while saf file picker is open`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destFile = File.createTempFile("compose_recreate_saf_", ".rtf", context.cacheDir)
        try {
            var pendingRequestCode: Int? = null
            var latestController: SafTextExportController? = null
            var successOutcome: TextExportOutcome.Success? = null

            val fakeRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    pendingRequestCode = requestCode
                }
            }
            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = fakeRegistry
            }

            val restorationTester = StateRestorationTester(composeTestRule)
            restorationTester.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    latestController = rememberSafTextExportController(
                        screenKey = "compose_test_screen",
                        onSuccess = { successOutcome = it }
                    )
                }
            }

            composeTestRule.runOnIdle {
                latestController!!.launchSaveTxt(
                    suggestedFilename = "Compose_Survive_Recreation.rtf",
                    rawContent = "Connected SSID: RecreateNet\nConnected BSSID: CA:FE:BA:BE:00:01\nRSSI: -54 dBm",
                    identifiers = SensitiveWifiIdentifiers(
                        ssids = setOf("RecreateNet"),
                        bssids = setOf("CA:FE:BA:BE:00:01")
                    )
                )
            }

            composeTestRule.waitUntil(timeoutMillis = 5_000) { pendingRequestCode != null }

            // Emulate Android destroying and recreating the Activity/Composable hierarchy while DocumentsUI is open
            restorationTester.emulateSavedInstanceStateRestore()
            composeTestRule.waitForIdle()

            // Deliver the ActivityResult to the recreated registry entry
            composeTestRule.runOnIdle {
                fakeRegistry.dispatchResult(pendingRequestCode!!, Uri.fromFile(destFile))
            }

            composeTestRule.waitUntil(timeoutMillis = 5_000) { successOutcome != null }

            val content = destFile.readText(Charsets.UTF_8)
            assertTrue(content.startsWith("{\\rtf1\\ansi"))
            assertTrue(content.contains(TextReportExportManager.PRIVACY_NOTICE_MESSAGE))
            assertFalse(content.contains("RecreateNet"))
            assertFalse(content.contains("CA:FE:BA:BE:00:01"))
            assertTrue(content.contains("-54 dBm"))
        } finally {
            destFile.delete()
        }
    }

    @Test
    fun `picker cancellation cleans up staged file without showing error`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stageResult = TextReportExportManager.stageRedactedTextReport(
            context = context,
            rawContent = "Ping summary: avg 14.2 ms",
            identifiers = SensitiveWifiIdentifiers(),
            suggestedFilename = "Cancel_Test.txt",
            screenKey = "cancel_test"
        )
        val staged = (stageResult as StageExportResult.Ready).staged
        val stagedFile = File(staged.stagedFilePath)
        assertTrue(stagedFile.exists())

        val outcome = TextReportExportManager.handleSafPickerCancelled(
            context = context,
            explicitExportId = staged.exportId,
            screenKey = "cancel_test"
        )
        assertTrue(outcome is TextExportOutcome.Cancelled)
        assertFalse("Staged file should be deleted when user cancels picker", stagedFile.exists())
    }

    @Test
    fun `missing staged file produces meaningful diagnostic and failed write preserves staged copy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Missing staged file scenario
        val stage1 = (TextReportExportManager.stageRedactedTextReport(
            context = context,
            rawContent = "Traceroute: 8.8.8.8 reached",
            identifiers = SensitiveWifiIdentifiers(),
            suggestedFilename = "Missing_Test.txt",
            screenKey = "missing_test"
        ) as StageExportResult.Ready).staged
        File(stage1.stagedFilePath).delete()

        val tempDest = File.createTempFile("unused_dest_", ".txt", context.cacheDir)
        try {
            val missingOutcome = TextReportExportManager.exportStagedReportToUri(
                context = context,
                explicitExportId = stage1.exportId,
                uri = Uri.fromFile(tempDest),
                screenKey = "missing_test"
            )
            assertTrue(missingOutcome is TextExportOutcome.Failure)
            val missingFailure = missingOutcome as TextExportOutcome.Failure
            assertEquals(ExportErrorCategory.STAGED_FILE_MISSING, missingFailure.category)
            assertEquals("StagedExportMissingException", missingFailure.exceptionName)
        } finally {
            tempDest.delete()
        }

        // 2. Failed write must NOT delete staged report, allowing retry to succeed
        val stage2 = (TextReportExportManager.stageRedactedTextReport(
            context = context,
            rawContent = "DNS Lookup: 1.1.1.1 OK",
            identifiers = SensitiveWifiIdentifiers(),
            suggestedFilename = "Retry_Test.txt",
            screenKey = "retry_test"
        ) as StageExportResult.Ready).staged
        val stagedFile2 = File(stage2.stagedFilePath)
        val invalidParentUri = Uri.fromFile(File(context.cacheDir, "nonexistent_dir/locked.txt"))

        val failedWrite = TextReportExportManager.exportStagedReportToUri(
            context = context,
            explicitExportId = stage2.exportId,
            uri = invalidParentUri,
            screenKey = "retry_test"
        )
        assertTrue(failedWrite is TextExportOutcome.Failure)
        assertTrue("Failed write must preserve staged file so report is not lost", stagedFile2.exists())

        val validRetryDest = File.createTempFile("retry_dest_", ".txt", context.cacheDir)
        try {
            val retryOutcome = TextReportExportManager.exportStagedReportToUri(
                context = context,
                explicitExportId = stage2.exportId,
                uri = Uri.fromFile(validRetryDest),
                screenKey = "retry_test"
            )
            assertTrue("Retry after failed write should succeed: $retryOutcome", retryOutcome is TextExportOutcome.Success)
            assertFalse(stagedFile2.exists())
        } finally {
            validRetryDest.delete()
        }
    }

    @Test
    fun `export error classification distinguishes all storage and privacy failure categories`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val nullUriOutcome = TextReportExportManager.exportTextReportToUri(
            context = context,
            uri = null,
            rawContent = "Sample",
            identifiers = SensitiveWifiIdentifiers()
        )
        assertTrue(nullUriOutcome is TextExportOutcome.Failure)
        assertEquals(
            ExportErrorCategory.INVALID_DESTINATION_URI,
            (nullUriOutcome as TextExportOutcome.Failure).category
        )

        val secFailure = TextReportExportManager.classifyException(
            SecurityException("Permission Denial: writing com.android.externalstorage"),
            "Opening stream"
        )
        assertEquals(ExportErrorCategory.PERMISSION_OR_URI_DENIED, secFailure.category)
        assertEquals("SecurityException", secFailure.exceptionName)

        val pickerMissing = TextReportExportManager.classifyException(
            ActivityNotFoundException("No DocumentsUI"),
            "Opening SAF picker"
        )
        assertEquals(ExportErrorCategory.STORAGE_PROVIDER_UNAVAILABLE, pickerMissing.category)

        val streamError = TextReportExportManager.classifyException(
            FileNotFoundException("Provider failed to create document"),
            "Opening stream"
        )
        assertEquals(ExportErrorCategory.FILE_CREATION_OR_STREAM_ERROR, streamError.category)

        val enospc = TextReportExportManager.classifyException(
            IOException("write failed: ENOSPC (No space left on device)"),
            "Writing bytes"
        )
        assertEquals(ExportErrorCategory.INSUFFICIENT_STORAGE, enospc.category)

        val genericIo = TextReportExportManager.classifyException(
            IOException("Broken pipe"),
            "Writing bytes"
        )
        assertEquals(ExportErrorCategory.IO_WRITE_ERROR, genericIo.category)

        val privacyErr = TextReportExportManager.classifyException(
            PrivacyVerificationException("SSID leak detected", "SSID"),
            "Verifying report"
        )
        assertEquals(ExportErrorCategory.PRIVACY_VERIFICATION_FAILED, privacyErr.category)
    }
}
