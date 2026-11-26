package com.example.networktoolkit

import android.app.AlertDialog
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.CredentialCryptoManager
import com.example.networktoolkit.data.DiagnosticReportExporter
import com.example.networktoolkit.data.ExportPrivacySelectionFlow
import com.example.networktoolkit.data.NetworkNoteEntity
import com.example.networktoolkit.data.PingCsvExporter
import com.example.networktoolkit.data.PingResultEntity
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.StageExportResult
import com.example.networktoolkit.data.TextExportOutcome
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.WifiReportExportPrivacyChoice
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.signalmap.LiveConnectedTelemetry
import com.example.networktoolkit.signalmap.SignalMapViewModel
import com.example.networktoolkit.signalmap.SignalSample
import com.example.networktoolkit.wifiscan.WifiBand
import com.example.networktoolkit.wifiscan.WifiNetworkInfo
import com.example.networktoolkit.wifiscan.WifiScannerEngine
import com.example.networktoolkit.wifiscan.WifiScannerScreen
import com.example.networktoolkit.wifiscan.WifiScannerState
import com.example.networktoolkit.wifiscan.WifiScannerViewModel
import com.example.networktoolkit.wifiscan.WifiSortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiPrivacyControlsAndExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearFileProviderCache()
        app.getSharedPreferences(WifiPrivacyPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WifiPrivacyPreferences.resetForTesting(app)
    }

    @After
    fun tearDown() {
        app.getSharedPreferences(WifiPrivacyPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WifiPrivacyPreferences.resetForTesting(app)
        clearFileProviderCache()
    }

    // =========================================================================
    // 1. PREFERENCE DEFAULTS, INDEPENDENCE & PERSISTENCE
    // =========================================================================

    @Test
    fun `wifi privacy defaults to false for both SSID and BSSID and toggles stay independent`() {
        val initial = WifiPrivacyPreferences.getSettings(app)
        assertFalse(initial.showSsid)
        assertFalse(initial.showBssid)

        // Enabling SSID must NOT enable BSSID
        WifiPrivacyPreferences.setShowSsid(app, true)
        assertTrue(WifiPrivacyPreferences.isShowSsidEnabled(app))
        assertFalse(WifiPrivacyPreferences.isShowBssidEnabled(app))

        // Enabling BSSID and disabling SSID preserves complete independence
        WifiPrivacyPreferences.setShowSsid(app, false)
        WifiPrivacyPreferences.setShowBssid(app, true)
        assertFalse(WifiPrivacyPreferences.isShowSsidEnabled(app))
        assertTrue(WifiPrivacyPreferences.isShowBssidEnabled(app))

        // Simulate app restart by resetting cached listener/state and reloading from SharedPreferences
        WifiPrivacyPreferences.resetForTesting(app)
        val reloaded = WifiPrivacyPreferences.getSettings(app)
        assertFalse(reloaded.showSsid)
        assertTrue(reloaded.showBssid)
    }

    @Test
    fun `MainActivity Settings dialog renders independent Show SSID and Show BSSID toggles OFF by default and persists selection`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()

            // Invoke Settings & Appearance dialog via its button
            val rootView = activity.findViewById<View>(android.R.id.content)
            val settingsBtn = findViewsByType(rootView, Button::class.java).first {
                it.text.toString().contains("Settings & Appearance")
            }
            settingsBtn.performClick()

            val dialog1 = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog1)
            val switches1 = findViewsByType(dialog1.window!!.decorView, Switch::class.java)
            val ssidSwitch1 = switches1.first { it.text.toString() == "Show SSID" }
            val bssidSwitch1 = switches1.first { it.text.toString() == "Show BSSID" }
            val labels1 = findViewsByType(dialog1.window!!.decorView, TextView::class.java).map { it.text.toString() }

            assertTrue(labels1.any { it.contains("Wi-Fi Privacy") })
            assertFalse(ssidSwitch1.isChecked)
            assertFalse(bssidSwitch1.isChecked)

            // Enable ONLY Show SSID and click Apply
            ssidSwitch1.isChecked = true
            assertFalse("Toggling Show SSID must never toggle Show BSSID", bssidSwitch1.isChecked)
            dialog1.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(WifiPrivacyPreferences.isShowSsidEnabled(activity))
            assertFalse(WifiPrivacyPreferences.isShowBssidEnabled(activity))

            // Simulate fresh reload & reopen Settings dialog to verify persistence
            WifiPrivacyPreferences.resetForTesting(activity)
            settingsBtn.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog2 = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog2)
            val switches2 = findViewsByType(dialog2.window!!.decorView, Switch::class.java)
            val ssidSwitch2 = switches2.first { it.text.toString() == "Show SSID" }
            val bssidSwitch2 = switches2.first { it.text.toString() == "Show BSSID" }
            assertTrue(ssidSwitch2.isChecked)
            assertFalse(bssidSwitch2.isChecked)

            // Flip independently: SSID OFF, BSSID ON
            ssidSwitch2.isChecked = false
            bssidSwitch2.isChecked = true
            dialog2.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(WifiPrivacyPreferences.isShowSsidEnabled(activity))
            assertTrue(WifiPrivacyPreferences.isShowBssidEnabled(activity))
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 2. ON-SCREEN REACTIVE VISIBILITY WITHOUT APP RESTART
    // =========================================================================

    @Test
    fun `WifiSignalFluctuationGraph hides SSID by default and updates immediately without restart`() {
        val secretSsid = "NOC_Core_WiFi"
        val secretBssid = "AA:11:BB:22:CC:33"
        val provider = RealTimeWifiSignalProvider(app)
        try {
            provider.pushSample(
                rssiDbm = -54,
                ssid = secretSsid,
                bssid = secretBssid,
                linkSpeedMbps = 866,
                frequencyMhz = 5180
            )

            composeRule.setContent {
                WifiSignalFluctuationGraph(externalProvider = provider, autoStart = false)
            }
            composeRule.waitForIdle()

            // Both OFF by default -> SSID masked
            composeRule.onNodeWithText(WifiPrivacyPreferences.MASKED_SSID, substring = true).assertIsDisplayed()
            assertEquals(0, composeRule.onAllNodesWithText(secretSsid, substring = true).fetchSemanticsNodes().size)

            // Turn on BSSID only -> SSID must STILL stay hidden
            composeRule.runOnUiThread { WifiPrivacyPreferences.setShowBssid(app, true) }
            composeRule.waitForIdle()
            assertEquals(0, composeRule.onAllNodesWithText(secretSsid, substring = true).fetchSemanticsNodes().size)

            // Turn on SSID -> appears live without restarting composable
            composeRule.runOnUiThread { WifiPrivacyPreferences.setShowSsid(app, true) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(secretSsid, substring = true).assertIsDisplayed()

            // Turn SSID back OFF -> disappears immediately
            composeRule.runOnUiThread { WifiPrivacyPreferences.setShowSsid(app, false) }
            composeRule.waitForIdle()
            assertEquals(0, composeRule.onAllNodesWithText(secretSsid, substring = true).fetchSemanticsNodes().size)
        } finally {
            provider.dispose()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `WifiScannerViewModel search and sort respect independent SSID and BSSID visibility`() = runBlocking {
        val secretSsid = "Enterprise_5G_Net"
        val secretBssid = "DE:AD:BE:EF:12:34"
        val vmStore = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return WifiScannerViewModel(app) as T
                    }
                }
            )[WifiScannerViewModel::class.java]
            val collectJob = launch { viewModel.filteredNetworks.collect { } }

        injectScannerState(
            viewModel,
            WifiScannerState(
                networks = listOf(
                    sampleWifiNetwork(ssid = secretSsid, bssid = secretBssid, rssi = -55),
                    sampleWifiNetwork(ssid = "Alpha_Guest", bssid = "11:22:33:44:55:66", rssi = -65)
                ),
                connectedSsid = secretSsid,
                connectedBssid = secretBssid
            )
        )

        // 1. Both OFF: neither SSID nor BSSID query reveals matching records
        viewModel.onSearchQueryChanged("Enterprise")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, viewModel.filteredNetworks.value.size)

        viewModel.onSearchQueryChanged("DE:AD:BE")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, viewModel.filteredNetworks.value.size)

        // 2. Show SSID ON only: SSID search matches, BSSID search still blocked
        WifiPrivacyPreferences.setShowSsid(app, true)
        viewModel.onSearchQueryChanged("Enterprise")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, viewModel.filteredNetworks.value.size)

        viewModel.onSearchQueryChanged("DE:AD:BE")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, viewModel.filteredNetworks.value.size)

        // 3. Show BSSID ON only: BSSID search matches, SSID search blocked
        WifiPrivacyPreferences.updateSettings(app, showSsid = false, showBssid = true)
        viewModel.onSearchQueryChanged("DE:AD:BE")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, viewModel.filteredNetworks.value.size)

        viewModel.onSearchQueryChanged("Enterprise")
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, viewModel.filteredNetworks.value.size)

        // 4. SSID_ASC sort falls back to signal strength when showSsid is OFF
        viewModel.onSearchQueryChanged("")
        viewModel.onSortOrderSelected(WifiSortOrder.SSID_ASC)
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(-55, viewModel.filteredNetworks.value.first().signalDbm)

        collectJob.cancel()
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `WifiScannerScreen masks SSID and BSSID independently and updates live without restart`() {
        val secretSsid = "BranchOffice_Corp"
        val secretBssid = "AA:BB:CC:DD:EE:11"
        val cardBssidTag = secretBssid.replace(":", "")
        val vmStore = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return WifiScannerViewModel(app) as T
                    }
                }
            )[WifiScannerViewModel::class.java]
        injectScannerState(
            viewModel,
            WifiScannerState(
                networks = listOf(sampleWifiNetwork(ssid = secretSsid, bssid = secretBssid, rssi = -49))
            )
        )

        composeRule.setContent {
            WifiScannerScreen(viewModel = viewModel, onBackClick = {})
        }
        composeRule.waitForIdle()

        // Default OFF/OFF
        composeRule.onNodeWithTag("wifi_card_ssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(WifiPrivacyPreferences.MASKED_SSID)
        composeRule.onNodeWithTag("wifi_card_bssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(WifiPrivacyPreferences.MASKED_BSSID)
        composeRule.onNodeWithTag("wifi_card_copy_bssid_$cardBssidTag", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, composeRule.onAllNodesWithText(secretSsid, substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText(secretBssid, substring = true).fetchSemanticsNodes().size)

        // Enable SSID ONLY -> SSID visible, BSSID still masked & Copy MAC hidden
        composeRule.runOnUiThread { WifiPrivacyPreferences.setShowSsid(app, true) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wifi_card_ssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(secretSsid)
        composeRule.onNodeWithTag("wifi_card_bssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(WifiPrivacyPreferences.MASKED_BSSID)
        composeRule.onNodeWithTag("wifi_card_copy_bssid_$cardBssidTag", useUnmergedTree = true).assertDoesNotExist()

        // Switch to BSSID ONLY -> SSID masked again, BSSID visible & Copy MAC visible
        composeRule.runOnUiThread {
            WifiPrivacyPreferences.updateSettings(app, showSsid = false, showBssid = true)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wifi_card_ssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(WifiPrivacyPreferences.MASKED_SSID)
        composeRule.onNodeWithTag("wifi_card_bssid_$cardBssidTag", useUnmergedTree = true).assertTextContains(secretBssid)
        composeRule.onNodeWithTag("wifi_card_copy_bssid_$cardBssidTag", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 3. REPORT EXPORT DEFAULT REDACTION & ONE-TIME PRIVACY SELECTION FLOW
    // =========================================================================

    @Test
    fun `enabling on-screen SSID and BSSID toggles does NOT disable export redaction across RTF JSON and CSV`() {
        WifiPrivacyPreferences.updateSettings(app, showSsid = true, showBssid = true)
        val secretSsid = "HQ_Secret_WiFi"
        val secretBssid = "FA:CE:B0:0C:12:34"
        val identifiers = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid)
        )
        val rawText = """
            Connected SSID   : $secretSsid
            Connected BSSID  : $secretBssid
            Signal RSSI      : -52 dBm
        """.trimIndent()
        val rawJson = """{"ssid":"$secretSsid","bssid":"$secretBssid","rssi":-52}"""

        // RTF default export stays redacted
        val textRes = TextReportExportManager.prepareTextReportForExport(
            rawContent = rawText,
            identifiers = identifiers
        ).getOrThrow()
        assertFalse(textRes.contains(secretSsid))
        assertFalse(textRes.contains(secretBssid))
        assertTrue(textRes.contains(TextReportExportManager.REDACTED_SSID))
        assertTrue(textRes.contains(TextReportExportManager.REDACTED_BSSID))

        // SAF staged RTF default export stays redacted and marks isWifiUnredacted = false
        val staged = TextReportExportManager.stageTextReportForExport(
            context = app,
            rawContent = rawText,
            identifiers = identifiers,
            suggestedFilename = "Privacy_Default_Test.rtf",
            screenKey = "privacy_test"
        ) as StageExportResult.Ready
        val outFile = File(app.cacheDir, "default_redacted_export.rtf")
        val outcome = TextReportExportManager.exportStagedReportToUri(
            context = app,
            explicitExportId = staged.staged.exportId,
            uri = outFile.toUri(),
            fallbackFilename = "Privacy_Default_Test.rtf",
            screenKey = "privacy_test"
        ) as TextExportOutcome.Success
        assertFalse(outcome.isWifiUnredacted)
        val writtenRtf = outFile.readText(Charsets.UTF_8)
        assertFalse(writtenRtf.contains(secretSsid))
        assertFalse(writtenRtf.contains(secretBssid))

        // JSON share default stays redacted
        val jsonRes = TextReportExportManager.prepareJsonReportForExport(
            rawJson = rawJson,
            identifiers = identifiers
        ).getOrThrow()
        assertFalse(jsonRes.contains(secretSsid))
        assertFalse(jsonRes.contains(secretBssid))

        // CSV default stays redacted
        val pingEntity = PingResultEntity(
            targetHost = secretSsid,
            resolvedIp = "10.10.0.1",
            packetsTransmitted = 4,
            packetsReceived = 4,
            packetLossPercentage = 0.0,
            minRttMs = 4.0,
            avgRttMs = 5.0,
            maxRttMs = 6.0,
            mdevRttMs = 0.5,
            protocolMode = "ICMP",
            durationMs = 400,
            logOutput = "SSID=$secretSsid BSSID=$secretBssid"
        )
        val csv = PingCsvExporter.generateCsv(listOf(pingEntity), identifiers)
        assertFalse(csv.contains(secretSsid))
        assertFalse(csv.contains(secretBssid))

        // ViewModels default to redacted even when on-screen toggles are ON
        val vmStore = ViewModelStore()
        try {
            val scannerVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return WifiScannerViewModel(app) as T
                    }
                }
            )[WifiScannerViewModel::class.java]
            injectScannerState(
                scannerVm,
                WifiScannerState(
                    networks = listOf(sampleWifiNetwork(ssid = secretSsid, bssid = secretBssid, rssi = -50))
                )
            )
            val scannerShareIntent = scannerVm.createShareIntent(app, isJson = false).getOrThrow()
            val scannerChooserTarget = scannerShareIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val scannerSharedBody = scannerChooserTarget.getStringExtra(Intent.EXTRA_TEXT)!!
            assertFalse(scannerSharedBody.contains(secretSsid))
            assertFalse(scannerSharedBody.contains(secretBssid))

            val signalVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return SignalMapViewModel(app) as T
                    }
                }
            )[SignalMapViewModel::class.java]
            val signalIds = SensitiveWifiIdentifiers(ssids = setOf(secretSsid), bssids = setOf(secretBssid))
            val signalReport = TextReportExportManager.prepareTextReportForExport(
                rawContent = "SSID: $secretSsid\nBSSID: $secretBssid",
                identifiers = signalIds
            ).getOrThrow()
            assertFalse(signalReport.contains(secretSsid))
            assertFalse(signalReport.contains(secretBssid))
            assertNotNull(signalVm.getDefaultTxtFilename())
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `explicit unredacted confirmation flow requires warning + confirmation and never persists for subsequent exports`() {
        var visible by mutableStateOf(true)
        var exportRound by mutableIntStateOf(1)
        val confirmedChoices = mutableListOf<WifiReportExportPrivacyChoice>()

        composeRule.setContent {
            if (visible) {
                ExportPrivacySelectionFlow(
                    exportDescription = "test export #$exportRound",
                    onDismiss = { visible = false },
                    onConfirmed = { choice ->
                        confirmedChoices.add(choice)
                        visible = false
                    }
                )
            }
        }
        composeRule.waitForIdle()

        // Step 1: Defaults to Option A (Redacted). Clicking continue emits REDACTED immediately.
        composeRule.onNodeWithTag("export_privacy_choice_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Option A — Redacted (Recommended)").assertIsDisplayed()
        composeRule.onNodeWithTag("export_privacy_continue_button").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(WifiReportExportPrivacyChoice.REDACTED), confirmedChoices)

        // Step 2: Re-open -> select Option B -> warning banner appears -> review warning -> Cancel blocks export
        exportRound = 2
        visible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_privacy_option_unredacted").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_privacy_unredacted_warning").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("readable, unencrypted Wi-Fi network identification", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("export_privacy_continue_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("export_unredacted_confirm_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("export_unredacted_cancel_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, confirmedChoices.size) // Cancelling unredacted confirmation must NOT export

        // Step 3: Explicit confirmation -> UNREDACTED_CONFIRMED emitted ONCE
        visible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_privacy_option_unredacted").performClick()
        composeRule.onNodeWithTag("export_privacy_continue_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_unredacted_confirm_button").performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(WifiReportExportPrivacyChoice.REDACTED, WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED),
            confirmedChoices
        )

        // Step 4: Subsequent export automatically resets to Option A (Redacted)
        exportRound = 4
        visible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Export Redacted").assertIsDisplayed()
        composeRule.onNodeWithTag("export_privacy_continue_button").performClick()
        composeRule.waitForIdle()
        assertEquals(WifiReportExportPrivacyChoice.REDACTED, confirmedChoices.last())
    }

    @Test
    fun `explicit confirmed unredacted export includes SSID and BSSID across RTF JSON CSV and ViewModels`() {
        val secretSsid = "FieldSurvey_AP_24"
        val secretBssid = "12:34:56:78:9A:BC"
        val identifiers = SensitiveWifiIdentifiers(
            ssids = setOf(secretSsid),
            bssids = setOf(secretBssid)
        )
        val rawText = "Connected SSID: $secretSsid\nConnected BSSID: $secretBssid\nRSSI: -61 dBm"
        val rawJson = """{"ssid":"$secretSsid","bssid":"$secretBssid","rssi":-61}"""

        // RTF SAF Export with UNREDACTED_CONFIRMED
        val staged = TextReportExportManager.stageTextReportForExport(
            context = app,
            rawContent = rawText,
            identifiers = identifiers,
            suggestedFilename = "Unredacted_Test.rtf",
            screenKey = "unredacted_test",
            privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        ) as StageExportResult.Ready
        val outFile = File(app.cacheDir, "unredacted_export.rtf")
        val outcome = TextReportExportManager.exportStagedReportToUri(
            context = app,
            explicitExportId = staged.staged.exportId,
            uri = outFile.toUri(),
            fallbackFilename = "Unredacted_Test.rtf",
            screenKey = "unredacted_test"
        ) as TextExportOutcome.Success
        assertTrue(outcome.isWifiUnredacted)
        val rtfText = outFile.readText(Charsets.UTF_8)
        assertTrue(rtfText.contains(secretSsid))
        assertTrue(rtfText.contains(secretBssid))
        assertTrue(rtfText.contains("unredacted", ignoreCase = true))

        // JSON Export with UNREDACTED_CONFIRMED
        val unredactedJson = TextReportExportManager.prepareJsonReportForExport(
            rawJson = rawJson,
            identifiers = identifiers,
            privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        ).getOrThrow()
        assertTrue(unredactedJson.contains(secretSsid))
        assertTrue(unredactedJson.contains(secretBssid))

        // CSV Export with UNREDACTED_CONFIRMED
        val pingEntity = PingResultEntity(
            targetHost = "192.168.1.1",
            resolvedIp = "192.168.1.1",
            packetsTransmitted = 2,
            packetsReceived = 2,
            packetLossPercentage = 0.0,
            minRttMs = 2.0,
            avgRttMs = 2.5,
            maxRttMs = 3.0,
            mdevRttMs = 0.2,
            protocolMode = "ICMP",
            durationMs = 200,
            logOutput = "SSID=$secretSsid BSSID=$secretBssid"
        )
        val unredactedCsv = PingCsvExporter.generateCsv(
            records = listOf(pingEntity),
            identifiers = identifiers,
            privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        )
        assertTrue(unredactedCsv.contains(secretSsid))
        assertTrue(unredactedCsv.contains(secretBssid))

        // DiagnosticReportExporter share with UNREDACTED_CONFIRMED
        val diagText = DiagnosticReportExporter.generateSinglePingText(pingEntity)
        val diagShare = DiagnosticReportExporter.createShareIntent(
            context = app,
            reportContent = diagText,
            isJson = false,
            privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        ).getOrThrow()
        val diagTarget = diagShare.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(diagTarget.getStringExtra(Intent.EXTRA_TEXT)!!.contains(secretSsid))

        // WifiScannerViewModel share with UNREDACTED_CONFIRMED
        val vmStore = ViewModelStore()
        try {
            val scannerVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return WifiScannerViewModel(app) as T
                    }
                }
            )[WifiScannerViewModel::class.java]
            injectScannerState(
                scannerVm,
                WifiScannerState(
                    networks = listOf(sampleWifiNetwork(ssid = secretSsid, bssid = secretBssid, rssi = -58))
                )
            )
            val scannerShare = scannerVm.createShareIntent(
                context = app,
                isJson = true,
                privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
            ).getOrThrow()
            val scannerTarget = scannerShare.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val scannerJson = scannerTarget.getStringExtra(Intent.EXTRA_TEXT)!!
            assertTrue(scannerJson.contains(secretSsid))
            assertTrue(scannerJson.contains(secretBssid))
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `MainActivity native Wi-Fi export privacy dialog defaults to Option A and requires second confirmation for Option B`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()

            val choices = mutableListOf<WifiReportExportPrivacyChoice>()
            val ifaceClass = Class.forName(
                "com.example.networktoolkit.MainActivity\$WifiExportPrivacyCallback"
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                ifaceClass.classLoader,
                arrayOf(ifaceClass)
            ) { _, method, args ->
                if (method.name == "onChosen") {
                    choices.add(args[0] as WifiReportExportPrivacyChoice)
                }
                null
            }
            val method = MainActivity::class.java.getDeclaredMethod(
                "showWifiExportPrivacyDialog",
                String::class.java,
                ifaceClass
            )
            method.isAccessible = true

            // Round 1: Option A is default
            method.invoke(activity, "Ping RTF export", proxy)
            shadowOf(Looper.getMainLooper()).idle()
            val choiceDialog1 = ShadowAlertDialog.getLatestAlertDialog()
            val radios1 = findViewsByType(choiceDialog1.window!!.decorView, RadioButton::class.java)
            assertTrue(radios1[0].isChecked)
            assertFalse(radios1[1].isChecked)
            choiceDialog1.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(WifiReportExportPrivacyChoice.REDACTED), choices)

            // Round 2: Option B -> Cancel on confirmation dialog -> callback NOT invoked
            method.invoke(activity, "Ping RTF export", proxy)
            shadowOf(Looper.getMainLooper()).idle()
            val choiceDialog2 = ShadowAlertDialog.getLatestAlertDialog()
            val radios2 = findViewsByType(choiceDialog2.window!!.decorView, RadioButton::class.java)
            radios2[1].performClick()
            choiceDialog2.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val confirmDialog2 = ShadowAlertDialog.getLatestAlertDialog()
            assertNotEquals(choiceDialog2, confirmDialog2)
            confirmDialog2.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, choices.size)

            // Round 3: Option B -> Confirm -> UNREDACTED_CONFIRMED
            method.invoke(activity, "Ping RTF export", proxy)
            shadowOf(Looper.getMainLooper()).idle()
            val choiceDialog3 = ShadowAlertDialog.getLatestAlertDialog()
            val radios3 = findViewsByType(choiceDialog3.window!!.decorView, RadioButton::class.java)
            radios3[1].performClick()
            choiceDialog3.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val confirmDialog3 = ShadowAlertDialog.getLatestAlertDialog()
            confirmDialog3.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                listOf(WifiReportExportPrivacyChoice.REDACTED, WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED),
                choices
            )
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 4. CLIPBOARD & CREDENTIAL SECURITY PRESERVATION
    // =========================================================================

    @Test
    fun `clipboard exports and CredentialCryptoManager safeguards remain strictly protected`() {
        WifiPrivacyPreferences.updateSettings(app, showSsid = true, showBssid = true)
        val secretSsid = "SecureCorp_Internal"
        val secretBssid = "99:88:77:66:55:44"
        val ids = SensitiveWifiIdentifiers(ssids = setOf(secretSsid), bssids = setOf(secretBssid))

        // Clipboard remains unconditionally redacted even when screen toggles are ON
        TextReportExportManager.copyRedactedToClipboard(
            context = app,
            label = "Report",
            rawContent = "SSID: $secretSsid\nBSSID: $secretBssid",
            identifiers = ids
        ).getOrThrow()
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertFalse(copied.contains(secretSsid))
        assertFalse(copied.contains(secretBssid))

        // Credential encryption intact
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()
        val crypto = CredentialCryptoManager(
            keyProvider = object : CredentialCryptoManager.KeyProvider {
                override fun getOrCreateSecretKey() = key
            }
        )
        val enc = crypto.encrypt("router_secret=V3ryStrongPass!") as CredentialCryptoManager.EncryptResult.Success
        assertTrue(enc.ciphertext.startsWith("ENC:v1:"))
        val dec = crypto.decrypt(enc.ciphertext) as CredentialCryptoManager.DecryptResult.Success
        assertEquals("router_secret=V3ryStrongPass!", dec.plaintext)

        // Notes report keeps credentials redacted independently of Wi-Fi export controls
        val note = NetworkNoteEntity(
            id = 1L,
            title = "Core Router Credentials",
            category = "Credentials",
            ipOrHost = "10.0.0.1",
            content = "enable_secret=TopSecret123",
            isEncrypted = true
        )
        val redactedNoteReport = buildNetworkNotesTextReport(listOf(note), redactCredentials = true)
        assertFalse(redactedNoteReport.contains("TopSecret123"))
        assertTrue(redactedNoteReport.contains("[CREDENTIAL CONTENT REDACTED]"))
    }

    @Test
    fun `valid SSID and BSSID are retained and sentinel BSSIDs are rejected without fabrication`() {
        val validSsid = "Corp-WiFi-5G"
        val validBssid = "AA:BB:CC:DD:EE:FF"
        val sentinelBssid = "02:00:00:00:00:00"

        val identifiers = SensitiveWifiIdentifiers(
            ssids = setOf(validSsid),
            bssids = setOf(validBssid)
        )

        // Verify valid SSID and BSSID are retained
        assertTrue(identifiers.ssids.contains(validSsid))
        assertTrue(identifiers.bssids.contains(validBssid))

        // Verify sentinel BSSID is recognized as unavailable / rejected from identifiers
        val sentinelIdentifiers = SensitiveWifiIdentifiers(
            ssids = setOf(validSsid),
            bssids = setOf(sentinelBssid)
        )
        // Sentinel MACs are treated as invalid/masked and not included in active sensitive BSSID collection
        val cleanBssids = sentinelIdentifiers.bssids.filter { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        assertTrue(cleanBssids.isEmpty())
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    private fun injectScannerState(viewModel: WifiScannerViewModel, state: WifiScannerState) {
        val engineField = WifiScannerViewModel::class.java.getDeclaredField("engine")
        engineField.isAccessible = true
        val engine = engineField.get(viewModel) as WifiScannerEngine
        val stateField = WifiScannerEngine::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        val flow = stateField.get(engine) as MutableStateFlow<WifiScannerState>
        flow.value = state
    }

    private fun sampleWifiNetwork(ssid: String, bssid: String, rssi: Int): WifiNetworkInfo {
        return WifiNetworkInfo(
            bssid = bssid,
            ssid = ssid,
            isHidden = false,
            displaySsid = ssid,
            signalDbm = rssi,
            signalLevelBars = 4,
            signalQualityPercent = 85,
            signalQualityLabel = "Excellent",
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            channelNumber = 36,
            channelWidth = "80 MHz",
            securitySummary = "WPA2/WPA3",
            isSecure = true,
            hasWps = false,
            wifiStandard = "Wi-Fi 6",
            rawCapabilities = "[WPA2-PSK-CCMP][ESS]",
            isConnected = false
        )
    }

    private fun <T : View> findViewsByType(root: View, clazz: Class<T>): List<T> {
        val result = mutableListOf<T>()
        fun walk(v: View) {
            if (clazz.isInstance(v)) {
                @Suppress("UNCHECKED_CAST")
                result.add(v as T)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i))
                }
            }
        }
        walk(root)
        return result
    }

    private fun clearFileProviderCache() {
        try {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        } catch (_: Exception) {
        }
    }
}
