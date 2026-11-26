package com.example.networktoolkit

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.SensitiveWifiIdentifiers
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.WifiReportExportPrivacyChoice
import com.example.ui.theme.NetworkToolkitTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiSsidBssidRetrievalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wifiManager = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        locationManager = app.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        app.getSharedPreferences(WifiPrivacyPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WifiPrivacyPreferences.resetForTesting(app)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(locationManager).setLocationEnabled(true)
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
    }

    @After
    fun tearDown() {
        app.getSharedPreferences(WifiPrivacyPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WifiPrivacyPreferences.resetForTesting(app)
    }

    // =========================================================================
    // 1. SSID RETRIEVAL & VALIDATION
    // =========================================================================

    @Test
    fun `normalizeSsid preserves valid SSIDs, strips outer quotes before emptiness checks, and never returns Connected Wi-Fi`() {
        assertEquals("Corp-WiFi-5G", WifiTelemetryReader.normalizeSsid("Corp-WiFi-5G"))
        assertEquals("Corp-WiFi-5G", WifiTelemetryReader.normalizeSsid("\"Corp-WiFi-5G\""))
        assertEquals("Corp-WiFi-5G", WifiTelemetryReader.normalizeSsid("  \"Corp-WiFi-5G\"  "))

        assertNull(WifiTelemetryReader.normalizeSsid(null))
        assertNull(WifiTelemetryReader.normalizeSsid(""))
        assertNull(WifiTelemetryReader.normalizeSsid("   "))
        assertNull(WifiTelemetryReader.normalizeSsid("\"\""))
        assertNull(WifiTelemetryReader.normalizeSsid("\"   \""))
        assertNull(WifiTelemetryReader.normalizeSsid("<unknown ssid>"))
        assertNull(WifiTelemetryReader.normalizeSsid("\"<unknown ssid>\""))
        assertNull(WifiTelemetryReader.normalizeSsid("\u0000\u0000\u0000"))

        // Status strings must never be treated as valid SSIDs
        assertNull(WifiTelemetryReader.normalizeSsid("Unavailable (OS Restricted / Location Services Disabled)"))
        assertNull(WifiTelemetryReader.normalizeSsid("<Location permission needed for SSID>"))
        assertNull(WifiTelemetryReader.normalizeSsid("Unavailable (Location Permission Required)"))
    }

    @Test
    fun `WifiTelemetryReader and NetworkInfoViewModel apply consistent SSID and BSSID rules without fabricating Connected Wi-Fi`() = runTest {
        // Test empty-quoted SSID and <unknown ssid> across both WifiTelemetryReader and NetworkInfoViewModel
        val wifiInfo = createWifiInfo(
            ssid = "\"\"",
            bssid = "02:00:00:00:00:00",
            rssi = -54,
            linkSpeedMbps = 433,
            frequencyMhz = 5180,
            networkId = 1
        )
        setupActiveWifiConnection(wifiInfo)

        val telemetry = WifiTelemetryReader.readLiveTelemetry(app, cachedWifiInfo = wifiInfo)
        assertNull("Empty quoted SSID must resolve to null, never Connected Wi-Fi", telemetry.ssid)
        assertNull("Sentinel BSSID 02:00:00:00:00:00 must resolve to null", telemetry.bssid)
        assertEquals(WifiIdentifierStatus.UNAVAILABLE, telemetry.ssidStatus)
        assertEquals(WifiIdentifierStatus.UNAVAILABLE, telemetry.bssidStatus)

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkInfoViewModel(app) as T
                    }
                }
            )[NetworkInfoViewModel::class.java]
            invokeQueryNetworkDetailsSync(vm)
            val details = (vm.uiState.value as NetworkDetailsUiState.Success).details
            val phys = details.physicalInfo!!
            assertTrue(phys.isWifi)
            assertNull("Underlying SSID must remain null rather than status text or Connected Wi-Fi", phys.ssid)
            assertNull("Underlying BSSID must reject sentinel 02:00:00:00:00:00", phys.bssid)
            assertNull(details.ssid)
            assertNull(details.bssid)
            assertEquals(WifiIdentifierStatus.UNAVAILABLE, phys.ssidStatus)
            assertEquals(WifiIdentifierStatus.UNAVAILABLE, phys.bssidStatus)
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `extractWifiIdentifiers distinguishes permission required vs location services disabled vs unavailable`() {
        val unknownInfo = createWifiInfo(
            ssid = "<unknown ssid>",
            bssid = "02:00:00:00:00:00",
            rssi = -58,
            linkSpeedMbps = 300,
            frequencyMhz = 2437,
            networkId = 2
        )

        // 1. Permission denied -> PERMISSION_REQUIRED
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val permDenied = WifiTelemetryReader.extractWifiIdentifiers(app, unknownInfo, isWifiConnected = true)
        assertNull(permDenied.ssid)
        assertNull(permDenied.bssid)
        assertEquals(WifiIdentifierStatus.PERMISSION_REQUIRED, permDenied.ssidStatus)
        assertEquals(WifiIdentifierStatus.PERMISSION_REQUIRED, permDenied.bssidStatus)

        // 2. Permission granted, Location Services disabled -> LOCATION_SERVICES_DISABLED_OR_RESTRICTED
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(locationManager).setLocationEnabled(false)
        val locDisabled = WifiTelemetryReader.extractWifiIdentifiers(app, unknownInfo, isWifiConnected = true)
        assertNull(locDisabled.ssid)
        assertNull(locDisabled.bssid)
        assertEquals(WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED, locDisabled.ssidStatus)
        assertEquals(WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED, locDisabled.bssidStatus)

        // 3. Permission granted, Location Services enabled, Android still gives valid SSID/BSSID -> AVAILABLE
        shadowOf(locationManager).setLocationEnabled(true)
        val validInfo = createWifiInfo(
            ssid = "\"Corp-WiFi-5G\"",
            bssid = "AA:BB:CC:DD:EE:FF",
            rssi = -51,
            linkSpeedMbps = 866,
            frequencyMhz = 5240,
            networkId = 3
        )
        val available = WifiTelemetryReader.extractWifiIdentifiers(app, validInfo, isWifiConnected = true)
        assertEquals("Corp-WiFi-5G", available.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", available.bssid)
        assertEquals(WifiIdentifierStatus.AVAILABLE, available.ssidStatus)
        assertEquals(WifiIdentifierStatus.AVAILABLE, available.bssidStatus)
    }

    // =========================================================================
    // 2. BSSID VALIDATION
    // =========================================================================

    @Test
    fun `normalizeBssid retains valid MACs and rejects null, blank, and sentinel values`() {
        assertEquals("AA:BB:CC:DD:EE:FF", WifiTelemetryReader.normalizeBssid("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", WifiTelemetryReader.normalizeBssid(" aa:bb:cc:dd:ee:ff "))

        assertNull(WifiTelemetryReader.normalizeBssid(null))
        assertNull(WifiTelemetryReader.normalizeBssid(""))
        assertNull(WifiTelemetryReader.normalizeBssid("   "))
        assertNull(WifiTelemetryReader.normalizeBssid("02:00:00:00:00:00"))
        assertNull(WifiTelemetryReader.normalizeBssid("00:00:00:00:00:00"))
        assertNull(WifiTelemetryReader.normalizeBssid("02-00-00-00-00-00"))
        assertNull(WifiTelemetryReader.normalizeBssid("00-00-00-00-00-00"))
    }

    // =========================================================================
    // 3. PRIVACY CONTROLS & LIVE DISPLAY BEHAVIOR
    // =========================================================================

    @Test
    fun `NetworkInfoScreen respects independent SSID and BSSID privacy toggles live without altering underlying data`() {
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkInfoViewModel(app) as T
                    }
                }
            )[NetworkInfoViewModel::class.java]
            val details = NetworkDetails(
                isConnected = true,
                connectionType = NetworkConnectionIdentifier.CONNECTION_WIFI,
                isVpnActive = false,
                physicalInfo = PhysicalNetworkInfo(
                    interfaceName = "wlan0",
                    ipv4Address = "192.168.10.25",
                    defaultGatewayIpv4 = "192.168.10.1",
                    ssid = "Corp-WiFi-5G",
                    bssid = "AA:BB:CC:DD:EE:FF",
                    linkSpeedMbps = 866,
                    signalStrengthDbm = -52,
                    snrDb = 38,
                    noiseFloorDbm = -90,
                    frequencyMhz = 5240,
                    frequencyBand = "5 GHz",
                    wifiChannel = 48,
                    mtu = 1500,
                    transportType = "Wi-Fi (TRANSPORT_WIFI)",
                    isWifi = true,
                    isCellular = false,
                    isEthernet = false,
                    ssidStatus = WifiIdentifierStatus.AVAILABLE,
                    bssidStatus = WifiIdentifierStatus.AVAILABLE
                ),
                isInternetValidated = true
            )
            injectSuccessState(vm, details)

            composeTestRule.setContent {
                NetworkToolkitTheme {
                    NetworkInfoScreen(
                        viewModel = vm,
                        onBackClick = {}
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Defaults OFF/OFF: scroll to Wi-Fi section, masked in UI, real identifiers preserved in ViewModel
            assertFalse(WifiPrivacyPreferences.isShowSsidEnabled(app))
            assertFalse(WifiPrivacyPreferences.isShowBssidEnabled(app))
            composeTestRule.onNodeWithTag("network_info_lazy_column")
                .performScrollToNode(hasText(WifiPrivacyPreferences.MASKED_SSID))
            composeTestRule.onNodeWithText(WifiPrivacyPreferences.MASKED_SSID).performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText(WifiPrivacyPreferences.MASKED_BSSID).performScrollTo().assertIsDisplayed()
            assertEquals(0, composeTestRule.onAllNodesWithText("Corp-WiFi-5G", substring = true).fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithText("AA:BB:CC:DD:EE:FF", substring = true).fetchSemanticsNodes().size)
            assertEquals("Corp-WiFi-5G", (vm.uiState.value as NetworkDetailsUiState.Success).details.physicalInfo?.ssid)
            assertEquals("AA:BB:CC:DD:EE:FF", (vm.uiState.value as NetworkDetailsUiState.Success).details.physicalInfo?.bssid)

            // Enable SSID only -> live update displays real SSID while BSSID stays masked
            composeTestRule.runOnUiThread {
                WifiPrivacyPreferences.setShowSsid(app, true)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Corp-WiFi-5G").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText(WifiPrivacyPreferences.MASKED_BSSID).performScrollTo().assertIsDisplayed()
            assertEquals(0, composeTestRule.onAllNodesWithText("AA:BB:CC:DD:EE:FF", substring = true).fetchSemanticsNodes().size)

            // Switch to BSSID only -> live update masks SSID and reveals real BSSID
            composeTestRule.runOnUiThread {
                WifiPrivacyPreferences.updateSettings(app, showSsid = false, showBssid = true)
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(WifiPrivacyPreferences.MASKED_SSID).performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("AA:BB:CC:DD:EE:FF").performScrollTo().assertIsDisplayed()
            assertEquals(0, composeTestRule.onAllNodesWithText("Corp-WiFi-5G", substring = true).fetchSemanticsNodes().size)

            // Verify SharedPreferences persistence survives listener reset
            WifiPrivacyPreferences.resetForTesting(app)
            val saved = WifiPrivacyPreferences.getSettings(app)
            assertFalse(saved.showSsid)
            assertTrue(saved.showBssid)
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 4. EXPORT REDACTION & STATUS MESSAGE SEPARATION
    // =========================================================================

    @Test
    fun `export keeps default redacted, preserves confirmed unredacted identifiers, and ignores status messages`() {
        val ssid = "Corp-WiFi-5G"
        val bssid = "AA:BB:CC:DD:EE:FF"
        val restrictedStatus = "Unavailable (OS Restricted / Location Services Disabled)"
        val permissionStatus = "Unavailable (Location Permission Required)"

        val ids = TextReportExportManager.collectIdentifiers(
            context = null,
            extraSsids = listOf(ssid, restrictedStatus, permissionStatus, "<Location permission needed for SSID>"),
            extraBssids = listOf(bssid, "02:00:00:00:00:00", "00:00:00:00:00:00")
        )
        assertEquals(setOf(ssid), ids.ssids)
        assertEquals(setOf(bssid), ids.bssids)

        val validReport = "SSID:                     $ssid\nBSSID:                    $bssid\nSignal Strength (RSSI):   -55 dBm"
        val redacted = TextReportExportManager.prepareTextReportForExport(
            rawContent = validReport,
            identifiers = ids
        ).getOrThrow()
        assertFalse(redacted.contains(ssid))
        assertFalse(redacted.contains(bssid))
        assertTrue(redacted.contains(TextReportExportManager.REDACTED_SSID))
        assertTrue(redacted.contains(TextReportExportManager.REDACTED_BSSID))

        val unredacted = TextReportExportManager.prepareTextReportForExport(
            rawContent = validReport,
            identifiers = ids,
            privacyChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
        ).getOrThrow()
        assertTrue(unredacted.contains(ssid))
        assertTrue(unredacted.contains(bssid))

        // Status message in report must remain as status message, NOT treated as sensitive SSID or turned into Hidden Network
        val statusReport = "SSID:                     $restrictedStatus\nBSSID:                    $permissionStatus\nSignal Strength (RSSI):   -62 dBm"
        val redactedStatusReport = TextReportExportManager.prepareTextReportForExport(
            rawContent = statusReport,
            identifiers = SensitiveWifiIdentifiers(
                ssids = setOf(restrictedStatus),
                bssids = setOf(permissionStatus)
            )
        ).getOrThrow()
        assertTrue(redactedStatusReport.contains(restrictedStatus))
        assertTrue(redactedStatusReport.contains(permissionStatus))
        assertFalse(redactedStatusReport.contains("SSID:                     ${TextReportExportManager.REDACTED_SSID}"))
    }

    // =========================================================================
    // 5. LIVE WI-FI IDENTIFIER REFRESH & LIFECYCLE SAFETY
    // =========================================================================

    @Test
    fun `applyLiveWifiTelemetry updates SSID and BSSID on network switch, clears stale values, and preserves RF metrics`() = runTest {
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkInfoViewModel(app) as T
                    }
                }
            )[NetworkInfoViewModel::class.java]
            injectSuccessState(
                vm,
                NetworkDetails(
                    isConnected = true,
                    connectionType = NetworkConnectionIdentifier.CONNECTION_WIFI,
                    physicalInfo = PhysicalNetworkInfo(
                        interfaceName = "wlan0",
                        ipv4Address = "192.168.1.50",
                        defaultGatewayIpv4 = "192.168.1.1",
                        ssid = "Corp-WiFi-5G",
                        bssid = "AA:BB:CC:DD:EE:FF",
                        linkSpeedMbps = 433,
                        signalStrengthDbm = -58,
                        snrDb = 30,
                        noiseFloorDbm = -88,
                        frequencyMhz = 5180,
                        frequencyBand = "5 GHz",
                        wifiChannel = 36,
                        mtu = 1500,
                        transportType = "Wi-Fi (TRANSPORT_WIFI)",
                        isWifi = true,
                        isCellular = false,
                        isEthernet = false,
                        ssidStatus = WifiIdentifierStatus.AVAILABLE,
                        bssidStatus = WifiIdentifierStatus.AVAILABLE
                    )
                )
            )

            // 1. Switch to new Wi-Fi network -> SSID, BSSID, and RF metrics all update
            vm.applyLiveWifiTelemetry(
                WifiRfTelemetry(
                    isWifiEnabled = true,
                    isWifiConnected = true,
                    rssiDbm = -47,
                    snrDb = 42,
                    noiseFloorDbm = -89,
                    ssid = "Lab-WiFi-6E",
                    bssid = "11:22:33:44:55:66",
                    linkSpeedMbps = 1200,
                    frequencyMhz = 5955,
                    ssidStatus = WifiIdentifierStatus.AVAILABLE,
                    bssidStatus = WifiIdentifierStatus.AVAILABLE
                )
            )

            val switched = (vm.uiState.value as NetworkDetailsUiState.Success).details
            assertEquals("Lab-WiFi-6E", switched.physicalInfo?.ssid)
            assertEquals("11:22:33:44:55:66", switched.physicalInfo?.bssid)
            assertEquals("Lab-WiFi-6E", switched.ssid)
            assertEquals("11:22:33:44:55:66", switched.bssid)
            assertEquals(-47, switched.signalStrengthDbm)
            assertEquals(42, switched.snrDb)
            assertEquals(-89, switched.noiseFloorDbm)
            assertEquals(1200, switched.linkSpeedMbps)
            assertEquals(5955, switched.frequencyMhz)
            assertEquals("6 GHz (Wi-Fi 6E/7)", switched.frequencyBand)
            assertEquals(1, switched.physicalInfo?.wifiChannel)

            // 2. Switch to network with unavailable/unknown identifiers -> stale Lab-WiFi-6E is cleared
            vm.applyLiveWifiTelemetry(
                WifiRfTelemetry(
                    isWifiEnabled = true,
                    isWifiConnected = true,
                    rssiDbm = -64,
                    snrDb = 24,
                    noiseFloorDbm = -88,
                    ssid = null,
                    bssid = null,
                    linkSpeedMbps = 144,
                    frequencyMhz = 2437,
                    ssidStatus = WifiIdentifierStatus.UNAVAILABLE,
                    bssidStatus = WifiIdentifierStatus.UNAVAILABLE
                )
            )
            val unavailableUpdate = (vm.uiState.value as NetworkDetailsUiState.Success).details
            assertNull("Old SSID must NOT linger when new network SSID is unavailable", unavailableUpdate.physicalInfo?.ssid)
            assertNull("Old BSSID must NOT linger when new network BSSID is unavailable", unavailableUpdate.physicalInfo?.bssid)
            assertNull(unavailableUpdate.ssid)
            assertNull(unavailableUpdate.bssid)
            assertEquals(WifiIdentifierStatus.UNAVAILABLE, unavailableUpdate.physicalInfo?.ssidStatus)
            assertEquals(-64, unavailableUpdate.signalStrengthDbm)
            assertEquals(24, unavailableUpdate.snrDb)

            // 3. Restore real identifiers then simulate disconnect -> identifiers and RF cleared
            vm.applyLiveWifiTelemetry(
                WifiRfTelemetry(
                    isWifiEnabled = true,
                    isWifiConnected = true,
                    rssiDbm = -50,
                    snrDb = 35,
                    noiseFloorDbm = -85,
                    ssid = "Corp-WiFi-5G",
                    bssid = "AA:BB:CC:DD:EE:FF",
                    linkSpeedMbps = 866,
                    frequencyMhz = 5240
                )
            )
            vm.applyLiveWifiTelemetry(
                WifiRfTelemetry(
                    isWifiEnabled = true,
                    isWifiConnected = false,
                    rssiDbm = null,
                    snrDb = null,
                    noiseFloorDbm = null
                )
            )
            val disconnected = (vm.uiState.value as NetworkDetailsUiState.Success).details
            assertNull(disconnected.physicalInfo?.ssid)
            assertNull(disconnected.physicalInfo?.bssid)
            assertNull(disconnected.ssid)
            assertNull(disconnected.bssid)
            assertNull(disconnected.signalStrengthDbm)
            assertNull(disconnected.snrDb)
            assertEquals(WifiIdentifierStatus.NOT_APPLICABLE, disconnected.physicalInfo?.ssidStatus)
            assertEquals(WifiIdentifierStatus.NOT_APPLICABLE, disconnected.physicalInfo?.bssidStatus)
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `NetworkCallback registers without duplicates, refreshes live Wi-Fi switches, clears on loss, and unregisters on onCleared`() = runTest {
        val initialWifi = createWifiInfo(
            ssid = "\"Office-Primary\"",
            bssid = "AA:BB:CC:DD:EE:FF",
            rssi = -51,
            linkSpeedMbps = 866,
            frequencyMhz = 5180,
            networkId = 10
        )
        setupActiveWifiConnection(initialWifi)

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkInfoViewModel(app) as T
                    }
                }
            )[NetworkInfoViewModel::class.java]
            invokeQueryNetworkDetailsSync(vm)

        assertTrue(vm.isNetworkCallbackRegisteredForTesting())
        assertTrue(vm.isWifiReceiverRegisteredForTesting())
        val callbackCountBefore = shadowOf(connectivityManager).networkCallbacks.size
        assertEquals(1, callbackCountBefore)

        // Calling registerNetworkCallback again must be a no-op (no duplicate registrations)
        val regMethod = NetworkInfoViewModel::class.java.getDeclaredMethod("registerNetworkCallback")
        regMethod.isAccessible = true
        regMethod.invoke(vm)
        assertEquals(1, shadowOf(connectivityManager).networkCallbacks.size)

        val firstState = (vm.uiState.value as NetworkDetailsUiState.Success).details
        assertEquals("Office-Primary", firstState.physicalInfo?.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", firstState.physicalInfo?.bssid)

        // Simulate active Wi-Fi switching to a second network via NetworkCallback.onCapabilitiesChanged
        val switchedWifi = createWifiInfo(
            ssid = "\"Branch-Guest\"",
            bssid = "22:33:44:55:66:77",
            rssi = -60,
            linkSpeedMbps = 400,
            frequencyMhz = 2437,
            networkId = 11
        )
        val network2 = setupActiveWifiConnection(switchedWifi, netId = 102)
        val caps2 = connectivityManager.getNetworkCapabilities(network2)!!
        val registeredCallback = shadowOf(connectivityManager).networkCallbacks.first()
        registeredCallback.onCapabilitiesChanged(network2, caps2)
        shadowOf(Looper.getMainLooper()).idle()
        invokeQueryNetworkDetailsSync(vm)

        val switchedState = (vm.uiState.value as NetworkDetailsUiState.Success).details
        assertEquals("Branch-Guest", switchedState.physicalInfo?.ssid)
        assertEquals("22:33:44:55:66:77", switchedState.physicalInfo?.bssid)
        assertEquals(-60, switchedState.signalStrengthDbm)

        // Simulate network loss -> stale identifiers immediately cleared
        registeredCallback.onLost(network2)
        val afterLoss = (vm.uiState.value as NetworkDetailsUiState.Success).details
        assertNull(afterLoss.physicalInfo?.ssid)
        assertNull(afterLoss.physicalInfo?.bssid)

        // Verify onCleared unregisters callbacks cleanly
        vmStore.clear()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.isNetworkCallbackRegisteredForTesting())
        assertFalse(vm.isWifiReceiverRegisteredForTesting())
        assertEquals(0, shadowOf(connectivityManager).networkCallbacks.size)
        } finally {
            vmStore.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun createWifiInfo(
        ssid: String,
        bssid: String,
        rssi: Int,
        linkSpeedMbps: Int,
        frequencyMhz: Int,
        networkId: Int
    ): WifiInfo {
        val info = ShadowWifiInfo.newInstance()
        val shadow = shadowOf(info)
        shadow.setRssi(rssi)
        shadow.setLinkSpeed(linkSpeedMbps)
        shadow.setFrequency(frequencyMhz)
        shadow.setNetworkId(networkId)

        // ShadowWifiInfo.setSSID wraps with quotes in WifiInfo.getSSID(); strip one outer pair if test provided quoted Android format
        val rawInnerSsid = if (ssid.length >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid.substring(1, ssid.length - 1)
        } else {
            ssid
        }
        val shadowSsidMethod = shadow.javaClass.methods.firstOrNull {
            (it.name.equals("setSSID", ignoreCase = true)) && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }
        if (shadowSsidMethod != null) {
            shadowSsidMethod.invoke(shadow, rawInnerSsid)
        } else {
            val wifiSsidClass = Class.forName("android.net.wifi.WifiSsid")
            val wifiSsidObj = wifiSsidClass.getMethod("fromBytes", ByteArray::class.java)
                .invoke(null, rawInnerSsid.toByteArray(Charsets.UTF_8))
            WifiInfo::class.java.getMethod("setSSID", wifiSsidClass).invoke(info, wifiSsidObj)
        }

        // Set BSSID via ShadowWifiInfo / WifiInfo APIs across Robolectric SDK levels
        val shadowBssidMethod = shadow.javaClass.methods.firstOrNull {
            (it.name.equals("setBSSID", ignoreCase = true)) && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }
        if (shadowBssidMethod != null) {
            shadowBssidMethod.invoke(shadow, bssid)
        } else {
            WifiInfo::class.java.getMethod("setBSSID", String::class.java).invoke(info, bssid)
        }

        return info
    }

    private fun setupActiveWifiConnection(wifiInfo: WifiInfo, netId: Int = 101): Network {
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        shadowOf(wifiManager).setConnectionInfo(wifiInfo)

        val shadowCm = shadowOf(connectivityManager)
        shadowCm.clearAllNetworks()
        val network = ShadowNetwork.newInstance(netId)
        @Suppress("DEPRECATION")
        val netInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            android.net.NetworkInfo.State.CONNECTED
        )
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        try {
            val field = NetworkCapabilities::class.java.getDeclaredField("mTransportInfo")
            field.isAccessible = true
            field.set(caps, wifiInfo)
        } catch (_: Exception) {
        }
        shadowCm.addNetwork(network, netInfo)
        shadowCm.setNetworkCapabilities(network, caps)
        shadowCm.setDefaultNetworkActive(true)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(netInfo)
        return network
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectSuccessState(viewModel: NetworkInfoViewModel, details: NetworkDetails) {
        try {
            val refreshJobField = NetworkInfoViewModel::class.java.getDeclaredField("refreshJob")
            refreshJobField.isAccessible = true
            (refreshJobField.get(viewModel) as? Job)?.cancel()
            val genField = NetworkInfoViewModel::class.java.getDeclaredField("queryGeneration")
            genField.isAccessible = true
            (genField.get(viewModel) as? java.util.concurrent.atomic.AtomicLong)?.incrementAndGet()
        } catch (_: Exception) {
        }
        val field = NetworkInfoViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(viewModel) as MutableStateFlow<NetworkDetailsUiState>
        flow.value = NetworkDetailsUiState.Success(details)
    }

    private fun invokeQueryNetworkDetailsSync(viewModel: NetworkInfoViewModel) {
        val cm = connectivityManager
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            // Re-run extractPhysicalNetworkInfo / queryNetworkDetails synchronously via reflection on extractPhysicalNetworkInfo
            val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
            val caps = cm.getNetworkCapabilities(activeNetwork)
            val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val extractMethod = NetworkInfoViewModel::class.java.getDeclaredMethod(
                "extractPhysicalNetworkInfo",
                Network::class.java,
                Network::class.java,
                NetworkConnectionIdentifier.ActiveNetworkClassification::class.java,
                Boolean::class.javaPrimitiveType
            )
            extractMethod.isAccessible = true
            val physical = extractMethod.invoke(
                viewModel,
                classification.underlyingNetwork,
                activeNetwork,
                classification,
                isValidated
            ) as PhysicalNetworkInfo?

            injectSuccessState(
                viewModel,
                NetworkDetails(
                    isConnected = true,
                    connectionType = classification.connectionType,
                    isVpnActive = classification.isVpnActive,
                    physicalInfo = physical,
                    isInternetValidated = isValidated
                )
            )
        }
    }
}
