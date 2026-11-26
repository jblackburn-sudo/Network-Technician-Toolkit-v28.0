package com.example.networktoolkit

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.ui.theme.NetworkToolkitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiRssiSnrTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSnrQualityTierThresholds() {
        assertEquals(SnrQualityTier.EXCELLENT, SnrQualityTier.fromSnrDb(45))
        assertEquals(SnrQualityTier.EXCELLENT, SnrQualityTier.fromSnrDb(40))
        assertEquals("Excellent", SnrQualityTier.EXCELLENT.label)

        assertEquals(SnrQualityTier.GOOD, SnrQualityTier.fromSnrDb(39))
        assertEquals(SnrQualityTier.GOOD, SnrQualityTier.fromSnrDb(25))
        assertEquals("Good", SnrQualityTier.GOOD.label)

        assertEquals(SnrQualityTier.FAIR, SnrQualityTier.fromSnrDb(24))
        assertEquals(SnrQualityTier.FAIR, SnrQualityTier.fromSnrDb(15))
        assertEquals("Fair", SnrQualityTier.FAIR.label)

        assertEquals(SnrQualityTier.POOR, SnrQualityTier.fromSnrDb(14))
        assertEquals(SnrQualityTier.POOR, SnrQualityTier.fromSnrDb(5))
        assertEquals("Poor", SnrQualityTier.POOR.label)
    }

    @Test
    fun testParseProcNetWirelessExtractsRssiAndGenuineNoiseFloor() {
        val sampleProc = """
            Inter-| sta-| Quality        |   Discarded packets               | Missed | WE
             face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22
             wlan0: 0000   62.  -52.  -94.       0      0      0      0      0        0
        """.trimIndent()

        val entry = WifiTelemetryReader.parseProcNetWireless(sampleProc, preferredInterface = "wlan0")
        assertNotNull(entry)
        assertEquals("wlan0", entry!!.interfaceName)
        assertEquals(-52, entry.rssiDbm)
        assertEquals(-94, entry.noiseFloorDbm)
        val computedSnr = entry.rssiDbm!! - entry.noiseFloorDbm!!
        assertEquals(42, computedSnr)
        assertEquals(SnrQualityTier.EXCELLENT, SnrQualityTier.fromSnrDb(computedSnr))
    }

    @Test
    fun testParseProcNetWirelessRejectsSentinelZeroAndInvalidNoiseFloor() {
        val zeroNoiseProc = """
            Inter-| sta-| Quality        |   Discarded packets               | Missed | WE
             face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22
             wlan0: 0000   54.  -61.    0.       0      0      0      0      0        0
        """.trimIndent()

        val entryZero = WifiTelemetryReader.parseProcNetWireless(zeroNoiseProc)
        assertNotNull(entryZero)
        assertEquals(-61, entryZero!!.rssiDbm)
        assertNull("Noise floor 0 must be rejected instead of estimating SNR", entryZero.noiseFloorDbm)

        val sentinel256Proc = """
            Inter-| sta-| Quality        |   Discarded packets               | Missed | WE
             face | tus | link level noise |  nwid  crypt   frag  retry   misc | beacon | 22
             wlan0: 0000   54.  -68.  -256.      0      0      0      0      0        0
        """.trimIndent()

        val entrySentinel = WifiTelemetryReader.parseProcNetWireless(sentinel256Proc)
        assertNotNull(entrySentinel)
        assertEquals(-68, entrySentinel!!.rssiDbm)
        assertNull("Noise floor -256 must be rejected", entrySentinel.noiseFloorDbm)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testNetworkInfoViewModelAppliesLiveRssiAndSnrUpdates() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vmStore = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkInfoViewModel(app) as T
                    }
                }
            )[NetworkInfoViewModel::class.java]

            // Wait deterministically for NetworkInfoViewModel.init's refresh() coroutine
            // to complete before injecting the Wi-Fi fixture into _uiState.
            val refreshJobField = NetworkInfoViewModel::class.java.getDeclaredField("refreshJob")
            refreshJobField.isAccessible = true
            (refreshJobField.get(viewModel) as? Job)?.join()

        // Ensure Success state exists so applyLiveWifiTelemetry can update Wi-Fi fields
        val field = NetworkInfoViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<NetworkDetailsUiState>

        stateFlow.value = NetworkDetailsUiState.Success(
            NetworkDetails(
                isConnected = true,
                connectionType = "Wi-Fi",
                physicalInfo = PhysicalNetworkInfo(
                    interfaceName = "wlan0",
                    ipv4Address = "192.168.1.42",
                    defaultGatewayIpv4 = "192.168.1.1",
                    ssid = "Office_WiFi",
                    bssid = "AA:BB:CC:DD:EE:FF",
                    linkSpeedMbps = 400,
                    signalStrengthDbm = -58,
                    snrDb = null,
                    noiseFloorDbm = null,
                    frequencyMhz = 5180,
                    frequencyBand = "5 GHz",
                    mtu = 1500,
                    transportType = "Wi-Fi",
                    isWifi = true,
                    isCellular = false,
                    isEthernet = false
                )
            )
        )

        // Push live Wi-Fi telemetry change with both RSSI and SNR available
        viewModel.applyLiveWifiTelemetry(
            WifiRfTelemetry(
                isWifiEnabled = true,
                isWifiConnected = true,
                rssiDbm = -49,
                snrDb = 41,
                noiseFloorDbm = -90,
                linkSpeedMbps = 866,
                frequencyMhz = 5240
            )
        )

        val updated = (viewModel.uiState.value as NetworkDetailsUiState.Success).details
        assertEquals(-49, updated.signalStrengthDbm)
        assertEquals(41, updated.snrDb)
        assertEquals(-90, updated.noiseFloorDbm)
        assertEquals(866, updated.linkSpeedMbps)

        // Push update where SNR cannot be obtained -> must become null (Unavailable)
        viewModel.applyLiveWifiTelemetry(
            WifiRfTelemetry(
                isWifiEnabled = true,
                isWifiConnected = true,
                rssiDbm = -67,
                snrDb = null,
                noiseFloorDbm = null
            )
        )

        val updatedNoSnr = (viewModel.uiState.value as NetworkDetailsUiState.Success).details
        assertEquals(-67, updatedNoSnr.signalStrengthDbm)
        assertNull(updatedNoSnr.snrDb)
        } finally {
            vmStore.clear()
            Dispatchers.resetMain()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun testComposeRssiAndSnrSectionDistinctUnitsQualityAndLiveUpdates() {
        var rssiState by mutableStateOf<Int?>(-52)
        var snrState by mutableStateOf<Int?>(36)
        var noiseState by mutableStateOf<Int?>(-88)
        var wifiConnected by mutableStateOf(true)

        composeTestRule.setContent {
            NetworkToolkitTheme {
                WifiRssiAndSnrSection(
                    rssiDbm = rssiState,
                    snrDb = snrState,
                    noiseFloorDbm = noiseState,
                    isWifiConnected = wifiConnected
                )
            }
        }

        // Verify distinct RSSI and SNR labels, units, values, and quality indicators
        composeTestRule.onNodeWithText("RSSI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Received Signal Strength Indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unit: dBm").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_rssi_value", useUnmergedTree = true).assertTextEquals("-52 dBm")
        composeTestRule.onNodeWithTag("wifi_rssi_quality", useUnmergedTree = true).assertTextEquals("Quality: Excellent")

        composeTestRule.onNodeWithText("SNR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Signal-to-Noise Ratio").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unit: dB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("wifi_snr_value", useUnmergedTree = true).assertTextEquals("36 dB")
        composeTestRule.onNodeWithTag("wifi_snr_quality", useUnmergedTree = true).assertTextEquals("Quality: Good")

        // Simulate live Wi-Fi condition change: RSSI drops and SNR becomes Unavailable
        composeTestRule.runOnUiThread {
            rssiState = -73
            snrState = null
            noiseState = null
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("wifi_rssi_value", useUnmergedTree = true).assertTextEquals("-73 dBm")
        composeTestRule.onNodeWithTag("wifi_rssi_quality", useUnmergedTree = true).assertTextEquals("Quality: Fair")
        composeTestRule.onNodeWithTag("wifi_snr_value", useUnmergedTree = true).assertTextEquals("Unavailable")
        composeTestRule.onNodeWithTag("wifi_snr_quality", useUnmergedTree = true).assertTextEquals("Quality: Unavailable")

        // Simulate SNR Fair/Poor and RSSI Unavailable
        composeTestRule.runOnUiThread {
            rssiState = null
            snrState = 12
            noiseState = null
            wifiConnected = false
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("wifi_rssi_value", useUnmergedTree = true).assertTextEquals("Unavailable")
        composeTestRule.onNodeWithTag("wifi_rssi_quality", useUnmergedTree = true).assertTextEquals("Quality: Unavailable")
        composeTestRule.onNodeWithTag("wifi_snr_value", useUnmergedTree = true).assertTextEquals("12 dB")
        composeTestRule.onNodeWithTag("wifi_snr_quality", useUnmergedTree = true).assertTextEquals("Quality: Poor")
    }

    @Test
    fun testNetworkInfoActivityLaunchesWifiToolsSectionWithoutCrash() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            NetworkInfoActivity::class.java
        ).apply {
            putExtra("target_section", "wifi")
        }
        val controller = Robolectric.buildActivity(NetworkInfoActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertNotNull(activity)
            assertTrue(!activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
