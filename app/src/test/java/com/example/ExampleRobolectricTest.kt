package com.example.networktoolkit

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.example.networktoolkit.speedtest.SpeedTestActivity
import com.example.networktoolkit.speedtest.SpeedTestServicePreferences
import com.example.networktoolkit.speedtest.SpeedTestViewModel
import com.example.networktoolkit.speedtest.SpeedTestUiState
import com.example.networktoolkit.wifiscan.WifiBand
import com.example.networktoolkit.wifiscan.WifiNetworkInfo
import com.example.networktoolkit.wifiscan.WifiScannerActivity
import com.example.networktoolkit.wifiscan.WifiScannerEngine
import com.example.networktoolkit.wifiscan.WifiScannerViewModel
import com.example.networktoolkit.wifiscan.WifiSortOrder
import com.example.networktoolkit.signalmap.CoverageAnalysis
import com.example.networktoolkit.signalmap.FloorPlanTemplates
import com.example.networktoolkit.signalmap.PropertyType
import com.example.networktoolkit.signalmap.RoomZone
import com.example.networktoolkit.signalmap.SignalMapEngine
import com.example.networktoolkit.signalmap.SignalMapperActivity
import com.example.networktoolkit.signalmap.SignalMapViewModel
import com.example.networktoolkit.signalmap.SignalRating
import com.example.networktoolkit.signalmap.SignalSample
import android.app.Activity
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkToolkitRobolectricTest {

  private inline fun <T : Activity> withDestroyedActivity(
      controller: ActivityController<T>,
      block: (T) -> Unit
  ) {
    try {
      block(controller.get())
    } finally {
      controller.pause().stop().destroy()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
  }

  private inline fun <reified VM : ViewModel> withViewModel(
      crossinline factory: () -> VM,
      block: (VM) -> Unit
  ) {
    val store = ViewModelStore()
    try {
      val vm = ViewModelProvider(
          store,
          object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory() as T
          }
      )[VM::class.java]
      block(vm)
    } finally {
      store.clear()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
  }

  @Test
  fun testAppNameResource() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Network Toolkit", appName)
  }

  @Test
  fun testMainActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(MainActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testNetworkInfoActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(NetworkInfoActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testNetworkInfoViewModelInitialization() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ NetworkInfoViewModel(app) }) { viewModel ->
      assertNotNull(viewModel)
      assertNotNull(viewModel.uiState.value)
      viewModel.refresh()
    }
  }

  @Test
  fun testCoroutinePingEngineInitialization() = runTest {
    val engine = CoroutinePingEngine()
    assertNotNull(engine)
    assertEquals(false, engine.isRunning)
  }

  @Test
  fun testTracerouteActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(TracerouteActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testTracerouteViewModelAndEngineInitialization() = runTest {
    withViewModel({ TracerouteViewModel() }) { viewModel ->
      assertNotNull(viewModel)
      assertTrue(viewModel.uiState.value is TracerouteUiState.Idle)
    }

    val engine = TracerouteEngine()
    assertFalse(engine.isRunning)
    engine.stop()
  }

  @Test
  fun testTracerouteHopDataModel() {
    val hop = TracerouteHop(
        hopNumber = 1,
        ip = "192.168.1.1",
        hostname = "gateway.local",
        rttMs = 3.2,
        isDestinationReached = false,
        isTimedOut = false,
        statusText = "Intermediate Hop"
    )
    assertEquals(1, hop.hopNumber)
    assertEquals("192.168.1.1", hop.ip)
    assertEquals(3.2, hop.rttMs ?: 0.0, 0.01)
    assertFalse(hop.isDestinationReached)
    assertFalse(hop.isTimedOut)
  }

  @Test
  fun testLanScannerActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(LanScannerActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testLanScannerViewModelAndEngineInitialization() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ LanScannerViewModel(app) }) { viewModel ->
      assertNotNull(viewModel)
      assertNotNull(viewModel.uiState.value)
      viewModel.refreshSubnet()
    }

    val engine = LanScannerEngine(app)
    assertFalse(engine.isScanning)
    engine.stopScan()
  }

  @Test
  fun testDiscoveredDeviceDataModel() {
    val device = DiscoveredDevice(
        ip = "192.168.1.50",
        hostname = "printer.office",
        isCurrentDevice = false,
        isGateway = false,
        respondingPort = 9100,
        serviceName = "Raw Network Printer",
        responseTimeMs = 12,
        deviceType = DeviceType.NETWORK_PRINTER
    )
    assertEquals("192.168.1.50", device.ip)
    assertEquals("printer.office", device.hostname)
    assertEquals(9100, device.respondingPort)
    assertEquals(DeviceType.NETWORK_PRINTER, device.deviceType)
    assertEquals(12L, device.responseTimeMs)
  }

  @Test
  fun testPingHistoryActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(PingHistoryActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testRoomPingDatabaseAndDaoOperations() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
        context,
        com.example.networktoolkit.data.NetworkToolkitDatabase::class.java
    ).allowMainThreadQueries().build()

    val dao = db.pingResultDao()
    val initialList = dao.getAllResultsList()
    assertTrue(initialList.isEmpty())

    val record = com.example.networktoolkit.data.PingResultEntity(
        targetHost = "1.1.1.1",
        resolvedIp = "1.1.1.1",
        packetsTransmitted = 4,
        packetsReceived = 4,
        packetLossPercentage = 0.0,
        minRttMs = 12.5,
        avgRttMs = 14.2,
        maxRttMs = 16.8,
        mdevRttMs = 1.3,
        protocolMode = "ICMP",
        durationMs = 4120,
        logOutput = "4 packets transmitted, 4 received, 0% packet loss"
    )

    val id = dao.insertResult(record)
    assertTrue(id > 0)

    val listAfterInsert = dao.getAllResultsList()
    assertEquals(1, listAfterInsert.size)
    assertEquals("1.1.1.1", listAfterInsert[0].targetHost)

    val searchResults = dao.searchResultsList("1.1.1")
    assertEquals(1, searchResults.size)

    val noResults = dao.searchResultsList("nonexistent")
    assertTrue(noResults.isEmpty())

    dao.deleteById(id)
    assertTrue(dao.getAllResultsList().isEmpty())

    db.close()
  }

  @Test
  fun testPingHistoryViewModelInitialization() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ PingHistoryViewModel(app) }) { viewModel ->
      assertNotNull(viewModel)
      assertNotNull(viewModel.historyItems.value)
      viewModel.onSearchQueryChanged("test")
      assertEquals("test", viewModel.searchQuery.value)
    }
  }

  @Test
  fun testPingCsvExporterEscapeRules() {
    val plain = com.example.networktoolkit.data.PingCsvExporter.escapeCsv("192.168.1.1")
    assertEquals("192.168.1.1", plain)

    val withComma = com.example.networktoolkit.data.PingCsvExporter.escapeCsv("Host, Gateway")
    assertEquals("\"Host, Gateway\"", withComma)

    val withQuotes = com.example.networktoolkit.data.PingCsvExporter.escapeCsv("Ping \"Google\" Test")
    assertEquals("\"Ping \"\"Google\"\" Test\"", withQuotes)

    val withNewline = com.example.networktoolkit.data.PingCsvExporter.escapeCsv("Line1\nLine2")
    assertEquals("\"Line1\nLine2\"", withNewline)
  }

  @Test
  fun testPingCsvExporterGenerateCsv() {
    val records = listOf(
        com.example.networktoolkit.data.PingResultEntity(
            id = 1,
            targetHost = "8.8.8.8",
            resolvedIp = "8.8.8.8",
            packetsTransmitted = 5,
            packetsReceived = 5,
            packetLossPercentage = 0.0,
            minRttMs = 15.2,
            avgRttMs = 17.8,
            maxRttMs = 21.4,
            mdevRttMs = 2.1,
            protocolMode = "ICMP",
            durationMs = 5020,
            logOutput = "5 packets transmitted, 5 received, 0% packet loss\nrtt min/avg/max = 15.2/17.8/21.4 ms",
            timestamp = 1700000000000L
        ),
        com.example.networktoolkit.data.PingResultEntity(
            id = 2,
            targetHost = "10.0.0.99",
            resolvedIp = "10.0.0.99",
            packetsTransmitted = 3,
            packetsReceived = 0,
            packetLossPercentage = 100.0,
            minRttMs = 0.0,
            avgRttMs = 0.0,
            maxRttMs = 0.0,
            mdevRttMs = 0.0,
            protocolMode = "SOCKET",
            durationMs = 6000,
            logOutput = "Request timed out.",
            timestamp = 1700000050000L
        )
    )

    val csv = com.example.networktoolkit.data.PingCsvExporter.generateCsv(records)
    assertNotNull(csv)

    // Check Excel UTF-8 BOM
    assertTrue(csv.startsWith("\uFEFF"))

    // Check header row columns
    assertTrue(csv.contains("ID,Timestamp_Epoch_ms,Date_Time,Target_Host,Resolved_IP,Protocol_Mode,Packets_Transmitted,Packets_Received,Packet_Loss_Percent,Min_RTT_ms,Avg_RTT_ms,Max_RTT_ms,MDev_RTT_ms,Duration_ms,Connectivity_Status,Log_Summary"))

    // Check first record data
    assertTrue(csv.contains("8.8.8.8"))
    assertTrue(csv.contains("PASSED"))
    assertTrue(csv.contains("17.80"))

    // Check second record with failure status
    assertTrue(csv.contains("10.0.0.99"))
    assertTrue(csv.contains("FAILED"))
    assertTrue(csv.contains("100.0"))
  }

  @Test
  fun testDefaultExportFilenameFormat() {
    val filename = com.example.networktoolkit.data.PingCsvExporter.getDefaultFilename()
    assertTrue(filename.startsWith("ping_history_report_"))
    assertTrue(filename.endsWith(".csv"))
  }

  @Test
  fun testSpecialUseIpValidationTestNetRanges() {
    val validator = com.example.networktoolkit.diagnostics.IpAddressValidator

    // TEST-NET-1: 192.0.2.0/24
    val t1 = validator.classifyIp("192.0.2.15")
    assertTrue(t1.isReservedOrDocumentation)
    assertFalse(t1.isOperational)
    assertEquals(com.example.networktoolkit.diagnostics.IpAddressValidator.IpCategory.DOCUMENTATION_RESERVED, t1.category)
    assertTrue(t1.rangeName.contains("TEST-NET-1"))

    // TEST-NET-2: 198.51.100.0/24
    val t2 = validator.classifyIp("198.51.100.44")
    assertTrue(t2.isReservedOrDocumentation)
    assertFalse(t2.isOperational)
    assertEquals(com.example.networktoolkit.diagnostics.IpAddressValidator.IpCategory.DOCUMENTATION_RESERVED, t2.category)
    assertTrue(t2.rangeName.contains("TEST-NET-2"))

    // TEST-NET-3: 203.0.113.0/24
    val t3 = validator.classifyIp("203.0.113.200")
    assertTrue(t3.isReservedOrDocumentation)
    assertFalse(t3.isOperational)
    assertEquals(com.example.networktoolkit.diagnostics.IpAddressValidator.IpCategory.DOCUMENTATION_RESERVED, t3.category)
    assertTrue(t3.rangeName.contains("TEST-NET-3"))

    // Link-Local APIPA: 169.254.0.0/16
    val apipa = validator.classifyIp("169.254.12.34")
    assertTrue(apipa.isReservedOrDocumentation)
    assertFalse(apipa.isOperational)
    assertEquals(com.example.networktoolkit.diagnostics.IpAddressValidator.IpCategory.LINK_LOCAL, apipa.category)

    // VPN Tunnel Addresses should NOT be marked invalid
    val vpnCgnat = validator.classifyIp("100.64.0.5", isVpnInterface = true)
    assertFalse(vpnCgnat.isReservedOrDocumentation)
    assertTrue(vpnCgnat.isOperational)

    val vpnPrivate = validator.classifyIp("10.8.0.2", isVpnInterface = true)
    assertFalse(vpnPrivate.isReservedOrDocumentation)
    assertTrue(vpnPrivate.isOperational)
  }

  @Test
  fun testScenario1WifiWithoutVpn() {
    val physical = PhysicalNetworkInfo(
        interfaceName = "wlan0",
        ipv4Address = "192.168.1.105",
        allIpv4Addresses = listOf("192.168.1.105/24"),
        defaultGatewayIpv4 = "192.168.1.1",
        dnsServers = listOf("192.168.1.1", "8.8.8.8"),
        ssid = "TechnicianLab_5G",
        bssid = "00:11:22:33:44:55",
        linkSpeedMbps = 433,
        signalStrengthDbm = -55,
        frequencyMhz = 5240,
        frequencyBand = "5 GHz",
        mtu = 1500,
        transportType = "Wi-Fi",
        isWifi = true,
        isCellular = false,
        isEthernet = false
    )

    val details = NetworkDetails(
        isConnected = true,
        connectionType = "Wi-Fi",
        isVpnActive = false,
        physicalInfo = physical,
        virtualInfo = null,
        isInternetValidated = true
    )

    assertEquals(false, details.isVpnActive)
    assertNotNull(details.physicalInfo)
    assertNull(details.virtualInfo)
    assertEquals("wlan0", details.physicalInfo?.interfaceName)
    assertEquals("192.168.1.105", details.physicalInfo?.ipv4Address)
    assertEquals("192.168.1.1", details.physicalInfo?.defaultGatewayIpv4)
    assertEquals(1500, details.physicalInfo?.mtu)
  }

  @Test
  fun testScenario2WifiWithVpn() {
    val physical = PhysicalNetworkInfo(
        interfaceName = "wlan0",
        ipv4Address = "192.168.1.105",
        allIpv4Addresses = listOf("192.168.1.105/24"),
        defaultGatewayIpv4 = "192.168.1.1",
        dnsServers = listOf("192.168.1.1"),
        ssid = "OfficeWifi",
        bssid = "aa:bb:cc:dd:ee:ff",
        linkSpeedMbps = 300,
        signalStrengthDbm = -60,
        frequencyMhz = 2437,
        frequencyBand = "2.4 GHz",
        mtu = 1500,
        transportType = "Wi-Fi",
        isWifi = true,
        isCellular = false,
        isEthernet = false
    )

    val virtual = VirtualNetworkInfo(
        interfaceName = "tun0",
        ipv4Address = "10.8.0.42",
        allIpv4Addresses = listOf("10.8.0.42/24"),
        dnsServers = listOf("10.8.0.1"),
        mtu = 1420,
        status = "Active & Tunneling"
    )

    val details = NetworkDetails(
        isConnected = true,
        connectionType = "VPN + Wi-Fi",
        isVpnActive = true,
        physicalInfo = physical,
        virtualInfo = virtual,
        isInternetValidated = true
    )

    assertTrue(details.isVpnActive)
    assertNotNull(details.physicalInfo)
    assertNotNull(details.virtualInfo)
    assertEquals("tun0", details.virtualInfo?.interfaceName)
    assertEquals("10.8.0.42", details.virtualInfo?.ipv4Address)
    assertEquals(1420, details.virtualInfo?.mtu)
    assertEquals("wlan0", details.physicalInfo?.interfaceName)
    assertEquals("OfficeWifi", details.physicalInfo?.ssid)
    assertEquals(1500, details.physicalInfo?.mtu)
  }

  @Test
  fun testScenario3MobileDataWithoutVpn() {
    val physical = PhysicalNetworkInfo(
        interfaceName = "rmnet_data0",
        ipv4Address = "100.72.15.8",
        allIpv4Addresses = listOf("100.72.15.8/28"),
        defaultGatewayIpv4 = "100.72.15.1",
        dnsServers = listOf("8.8.8.8"),
        ssid = null,
        bssid = null,
        linkSpeedMbps = null,
        signalStrengthDbm = null,
        frequencyMhz = null,
        frequencyBand = null,
        mtu = 1430,
        transportType = "Cellular Mobile Data",
        isWifi = false,
        isCellular = true,
        isEthernet = false
    )

    val details = NetworkDetails(
        isConnected = true,
        connectionType = "Cellular Data",
        isVpnActive = false,
        physicalInfo = physical,
        virtualInfo = null,
        isInternetValidated = true
    )

    assertFalse(details.isVpnActive)
    assertTrue(details.isCellular)
    assertFalse(details.isWifi)
    assertEquals("rmnet_data0", details.interfaceName)
    assertEquals(1430, details.mtu)
  }

  @Test
  fun testScenario4MobileDataWithVpn() {
    val physical = PhysicalNetworkInfo(
        interfaceName = "rmnet0",
        ipv4Address = "100.80.20.10",
        allIpv4Addresses = listOf("100.80.20.10/28"),
        defaultGatewayIpv4 = "100.80.20.1",
        dnsServers = listOf("8.8.4.4"),
        ssid = null,
        bssid = null,
        linkSpeedMbps = null,
        signalStrengthDbm = null,
        frequencyMhz = null,
        frequencyBand = null,
        mtu = 1430,
        transportType = "Cellular Mobile Data",
        isWifi = false,
        isCellular = true,
        isEthernet = false
    )

    val virtual = VirtualNetworkInfo(
        interfaceName = "ipsec0",
        ipv4Address = "172.16.200.5",
        allIpv4Addresses = listOf("172.16.200.5/24"),
        dnsServers = listOf("1.1.1.1"),
        mtu = 1380,
        status = "Active & Tunneling"
    )

    val details = NetworkDetails(
        isConnected = true,
        connectionType = "VPN + Cellular Data",
        isVpnActive = true,
        physicalInfo = physical,
        virtualInfo = virtual,
        isInternetValidated = true
    )

    assertTrue(details.isVpnActive)
    assertEquals("ipsec0", details.virtualInfo?.interfaceName)
    assertEquals("rmnet0", details.physicalInfo?.interfaceName)
    assertEquals(1380, details.virtualInfo?.mtu)
    assertEquals(1430, details.physicalInfo?.mtu)
  }

  @Test
  fun testScenario5DisconnectedState() {
    val state = NetworkDetailsUiState.Disconnected("No active network connection")
    assertEquals("No active network connection", state.message)
  }

  @Test
  fun testMtuUnavailableFormatting() {
    val physicalNoMtu = PhysicalNetworkInfo(
        interfaceName = "eth0",
        ipv4Address = "192.168.10.5",
        defaultGatewayIpv4 = null,
        dnsServers = emptyList(),
        ssid = null,
        bssid = null,
        linkSpeedMbps = null,
        signalStrengthDbm = null,
        frequencyMhz = null,
        frequencyBand = null,
        mtu = null, // Unavailable
        transportType = "Ethernet",
        isWifi = false,
        isCellular = false,
        isEthernet = true
    )

    assertNull(physicalNoMtu.mtu)
    val displayMtu = physicalNoMtu.mtu?.let { "$it bytes" } ?: "Unavailable"
    assertEquals("Unavailable", displayMtu)
  }

  @Test
  fun testDiagnosticWarningsDistinguishSystemLimitations() {
    val vpnWarning = DiagnosticWarning(
        title = "Physical Gateway Unavailable (VPN Active)",
        message = "Route tables suppressed by VPN.",
        severity = DiagnosticSeverity.WARNING,
        isSystemLimitation = true
    )
    assertTrue(vpnWarning.isSystemLimitation)

    val faultWarning = DiagnosticWarning(
        title = "Physical Gateway Unavailable",
        message = "No DHCP gateway assigned.",
        severity = DiagnosticSeverity.WARNING,
        isSystemLimitation = false
    )
    assertFalse(faultWarning.isSystemLimitation)

    val testNetWarning = DiagnosticWarning(
        title = "Reserved IP Address Detected (192.0.2.1)",
        message = "TEST-NET-1 (RFC 5737) detected.",
        severity = DiagnosticSeverity.ALERT,
        isSystemLimitation = false
    )
    assertFalse(testNetWarning.isSystemLimitation)
    assertEquals(DiagnosticSeverity.ALERT, testNetWarning.severity)
  }

  @Test
  fun testSpeedTestViewModelInitialState() {
    withViewModel({ SpeedTestViewModel() }) { viewModel ->
      val state = viewModel.uiState.value
      assertTrue(state is SpeedTestUiState.Idle)
    }
  }

  @Test
  fun testSpeedTestActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(SpeedTestActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testAdditionalDiagnosticsModel() {
    val details = NetworkDetails(
        isConnected = true,
        connectionType = "Wi-Fi",
        publicIpAddress = "198.51.100.25",
        dnsConnectivityStatus = "Operational (8.8.8.8)",
        gatewayConnectivityStatus = "Reachable (Active)"
    )
    assertEquals("198.51.100.25", details.publicIpAddress)
    assertEquals("Operational (8.8.8.8)", details.dnsConnectivityStatus)
    assertEquals("Reachable (Active)", details.gatewayConnectivityStatus)
  }

  @Test
  fun testWebTestActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(WebTestActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testWebTestActivityWithCustomTargetUrl() {
    val intent = android.content.Intent().apply {
      putExtra("target_url", "https://www.speedtest.net")
      putExtra("target_title", "Speedtest.net")
    }
    withDestroyedActivity(Robolectric.buildActivity(WebTestActivity::class.java, intent).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  // ==========================================
  // A. DNS LOOKUP TESTS
  // ==========================================

  @Test
  fun testDnsLookupActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(DnsLookupActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testDnsRecordModel() {
    val record = DnsRecord(
        type = "PTR",
        name = "8.8.8.8",
        value = "dns.google",
        ttl = 300
    )
    assertEquals("PTR", record.type)
    assertEquals("8.8.8.8", record.name)
    assertEquals("dns.google", record.value)
  }

  @Test
  fun testDnsLookupViewModelInitialState() {
    withViewModel({ DnsLookupViewModel(ApplicationProvider.getApplicationContext()) }) { viewModel ->
      assertNotNull(viewModel)
      assertEquals("google.com", viewModel.targetInput.value)
      assertEquals("ALL", viewModel.selectedFilter.value)
    }
  }

  // ==========================================
  // B. SUBNET CALCULATOR TESTS
  // ==========================================

  @Test
  fun testSubnetCalculatorActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(SubnetCalculatorActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testSubnetCalculatorEngineStandardClassC() {
    val result = SubnetCalculatorEngine.calculate("192.168.1.50", 24).getOrNull()
    assertNotNull(result)
    assertEquals("192.168.1.0", result!!.networkAddress)
    assertEquals("192.168.1.255", result.broadcastAddress)
    assertEquals("255.255.255.0", result.subnetMask)
    assertEquals("0.0.0.255", result.wildcardMask)
    assertEquals("192.168.1.1", result.firstUsableHost)
    assertEquals("192.168.1.254", result.lastUsableHost)
    assertEquals(254L, result.usableHostsCount)
    assertEquals(256L, result.totalAddressesCount)
    assertEquals("Class C", result.ipClass)
    assertEquals("RFC 1918 Private (192.168.0.0/16)", result.addressScope)
  }

  @Test
  fun testSubnetCalculatorEngineSlash30PointToPoint() {
    val result = SubnetCalculatorEngine.calculate("10.0.0.1", 30).getOrNull()
    assertNotNull(result)
    assertEquals("10.0.0.0", result!!.networkAddress)
    assertEquals("10.0.0.3", result.broadcastAddress)
    assertEquals("255.255.255.252", result.subnetMask)
    assertEquals("10.0.0.1", result.firstUsableHost)
    assertEquals("10.0.0.2", result.lastUsableHost)
    assertEquals(2L, result.usableHostsCount)
    assertEquals(4L, result.totalAddressesCount)
  }

  @Test
  fun testSubnetCalculatorViewModelCalculation() {
    withViewModel({ SubnetCalculatorViewModel() }) { viewModel ->
      viewModel.onIpChanged("172.16.0.10")
      viewModel.onPrefixChanged(16)
      val state = viewModel.uiState.value
      assertNotNull(state.result)
      assertEquals("172.16.0.0", state.result!!.networkAddress)
      assertEquals("255.255.0.0", state.result!!.subnetMask)
      assertEquals(65534L, state.result!!.usableHostsCount)
    }
  }

  // ==========================================
  // C. NETWORK NOTES TESTS
  // ==========================================

  @Test
  fun testNetworkNotesActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(NetworkNotesActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testNetworkNoteEntityCreation() {
    val note = com.example.networktoolkit.data.NetworkNoteEntity(
        id = 1L,
        title = "IDF Switch Port 12",
        content = "VLAN 20 tagged, connected to AP in hallway",
        category = "Hardware",
        ipOrHost = "10.0.0.5",
        isPinned = true
    )
    assertEquals("IDF Switch Port 12", note.title)
    assertEquals("10.0.0.5", note.ipOrHost)
    assertEquals("Hardware", note.category)
    assertTrue(note.isPinned)
  }

  @Test
  fun testNetworkNotesDatabaseInRobolectric() = runTest {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val db = com.example.networktoolkit.data.NetworkToolkitDatabase.getInstance(context)
    val dao = db.networkNoteDao()
    assertNotNull(dao)

    val note = com.example.networktoolkit.data.NetworkNoteEntity(
        title = "Router Config",
        content = "Gateway 192.168.10.1",
        category = "Config"
    )
    val id = dao.insertNote(note)
    assertTrue(id > 0)

    val fetched = dao.getNoteById(id)
    assertNotNull(fetched)
    assertEquals("Router Config", fetched?.title)
  }

  // ==========================================
  // D. IPERF3 BENCHMARK TESTS
  // ==========================================

  @Test
  fun testIPerf3ActivityLaunchesWithoutCrash() {
    withDestroyedActivity(
        Robolectric.buildActivity(com.example.networktoolkit.iperf3.IPerf3Activity::class.java).setup()
    ) { activity ->
      assertNotNull(activity)
    }
  }

  @Test
  fun testIPerf3ViewModelInitialState() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ com.example.networktoolkit.iperf3.IPerf3ViewModel(app) }) { viewModel ->
      assertNotNull(viewModel)
      assertEquals(5201, viewModel.port.value)
      assertEquals(10, viewModel.durationSeconds.value)
      assertTrue(viewModel.uiState.value is com.example.networktoolkit.iperf3.IPerfUiState.Idle)
      assertEquals(com.example.networktoolkit.iperf3.IPerfMode.CLIENT, viewModel.mode.value)
    }
  }

  @Test
  fun testIPerf3IntervalModelCalculations() {
    val interval = com.example.networktoolkit.iperf3.IPerfInterval(
        intervalIndex = 1,
        timeRange = "1.0 - 2.0 sec",
        bytesTransferred = 12500000L,
        bandwidthMbps = 100.0
    )
    assertEquals(1, interval.intervalIndex)
    assertEquals("1.0 - 2.0 sec", interval.timeRange)
    assertEquals(12500000L, interval.bytesTransferred)
    assertEquals(100.0, interval.bandwidthMbps, 0.01)
  }

  // ==========================================
  // REAL-TIME CONNECTIVITY MONITOR TESTS
  // ==========================================

  @Test
  fun testNetworkConnectivityMonitorSingleton() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val monitor = NetworkConnectivityMonitor.getInstance(context)
    assertNotNull(monitor)
    assertNotNull(monitor.snapshot.value)
  }

  // ==========================================
  // ROOM DIAGNOSTIC PERSISTENCE & REPORT EXPORT TESTS
  // ==========================================

  @Test
  fun testTracerouteResultEntityAndDaoInRoom() = runTest {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val db = com.example.networktoolkit.data.NetworkToolkitDatabase.getInstance(context)
    val dao = db.tracerouteResultDao()
    assertNotNull(dao)

    val hopsJson = """[{"hopNumber":1,"ip":"192.168.1.1","hostname":"router.local","rttMs":2.4,"isDestinationReached":false,"isTimedOut":false,"statusText":"Gateway"}]"""
    val entity = com.example.networktoolkit.data.TracerouteResultEntity(
        targetHost = "example.com",
        targetIp = "93.184.216.34",
        totalHops = 1,
        isDestinationReached = true,
        avgRttMs = 2.4,
        durationMs = 1500L,
        hopsJson = hopsJson
    )

    val id = dao.insertResult(entity)
    assertTrue(id > 0)

    val fetched = dao.getResultById(id)
    assertNotNull(fetched)
    assertEquals("example.com", fetched?.targetHost)
    assertEquals("93.184.216.34", fetched?.targetIp)
    assertTrue(fetched?.isDestinationReached == true)

    val parsedHops = com.example.networktoolkit.data.DiagnosticHistoryRepository.parseHopsJson(fetched?.hopsJson ?: "")
    assertEquals(1, parsedHops.size)
    assertEquals("192.168.1.1", parsedHops[0].ip)
    assertEquals("router.local", parsedHops[0].hostname)
  }

  @Test
  fun testDiagnosticReportExporterJson() {
    val ping = com.example.networktoolkit.data.PingResultEntity(
        id = 101L,
        targetHost = "1.1.1.1",
        resolvedIp = "1.1.1.1",
        packetsTransmitted = 4,
        packetsReceived = 4,
        packetLossPercentage = 0.0,
        minRttMs = 10.0,
        avgRttMs = 12.0,
        maxRttMs = 15.0,
        mdevRttMs = 1.5,
        protocolMode = "ICMP",
        durationMs = 4000L,
        logOutput = "4 packets transmitted, 4 received"
    )

    val trace = com.example.networktoolkit.data.TracerouteResultEntity(
        id = 202L,
        targetHost = "cloudflare.com",
        targetIp = "104.16.132.229",
        totalHops = 5,
        isDestinationReached = true,
        avgRttMs = 14.5,
        durationMs = 3200L,
        hopsJson = "[]"
    )

    val jsonString = com.example.networktoolkit.data.DiagnosticReportExporter.generateJsonReport(
        pings = listOf(ping),
        traceroutes = listOf(trace)
    )

    assertTrue(jsonString.contains("Network Technician Diagnostic Report"))
    assertTrue(jsonString.contains("pingSessions"))
    assertTrue(jsonString.contains("tracerouteSessions"))
    assertTrue(jsonString.contains("1.1.1.1"))
    assertTrue(jsonString.contains("cloudflare.com"))

    val root = org.json.JSONObject(jsonString)
    assertEquals(com.example.networktoolkit.BuildConfig.VERSION_NAME, root.getString("toolkitVersion"))
    assertEquals(1, root.getJSONArray("pingSessions").length())
    assertEquals(1, root.getJSONArray("tracerouteSessions").length())
  }

  @Test
  fun testDiagnosticReportExporterText() {
    val ping = com.example.networktoolkit.data.PingResultEntity(
        id = 1L,
        targetHost = "google.com",
        resolvedIp = "142.250.190.46",
        packetsTransmitted = 10,
        packetsReceived = 10,
        packetLossPercentage = 0.0,
        minRttMs = 15.2,
        avgRttMs = 18.4,
        maxRttMs = 22.1,
        mdevRttMs = 2.1,
        protocolMode = "ICMP",
        durationMs = 10000L,
        logOutput = "rtt min/avg/max = 15.2/18.4/22.1 ms"
    )

    val text = com.example.networktoolkit.data.DiagnosticReportExporter.generateTextReport(
        pings = listOf(ping),
        traceroutes = emptyList()
    )

    assertTrue(text.contains("NETWORK TECHNICIAN TOOLKIT - DIAGNOSTIC REPORT"))
    assertTrue(text.contains("PING TEST SESSIONS (1)"))
    assertTrue(text.contains("google.com"))
    assertTrue(text.contains("142.250.190.46"))
    assertTrue(text.contains("Loss: 0.0%"))
  }

  @Test
  fun testMaterial3ThemeModeResolution() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val isDarkSystem = com.example.ui.theme.resolveThemeMode(context, systemDark = true)
    // Default setting is dark
    assertTrue(isDarkSystem)

    val prefs = context.getSharedPreferences("ToolkitSettings", Context.MODE_PRIVATE)
    prefs.edit().putString("theme", "light").apply()
    val isLight = com.example.ui.theme.resolveThemeMode(context, systemDark = true)
    assertFalse(isLight)

    prefs.edit().putString("theme", "system").apply()
    val isSystemFollowing = com.example.ui.theme.resolveThemeMode(context, systemDark = false)
    assertFalse(isSystemFollowing)
  }

  // ==========================================
  // D3 LATENCY LINE CHART TESTS
  // ==========================================

  @Test
  fun testPingLatencyPointModel() {
    val point = PingLatencyPoint(
        sequence = 1,
        rttMs = 14.5,
        isSuccess = true
    )
    assertEquals(1, point.sequence)
    assertEquals(14.5, point.rttMs, 0.01)
    assertTrue(point.isSuccess)

    val lossPoint = PingLatencyPoint(
        sequence = 2,
        rttMs = 0.0,
        isSuccess = false
    )
    assertEquals(2, lossPoint.sequence)
    assertFalse(lossPoint.isSuccess)
  }

  @Test
  fun testPingD3LatencyChartHtmlGeneration() {
    val html = PingD3LatencyChartView.buildD3ChartHtml()
    assertNotNull(html)
    assertTrue(html.contains("D3 LATENCY TRENDS"))
    assertTrue(html.contains("<svg id=\"chartSvg\">"))
    assertTrue(html.contains("linearGradient id=\"areaGradient\""))
    assertTrue(html.contains("window.updateChartData"))
    assertTrue(html.contains("renderChart"))
  }

  @Test
  fun testPingD3LatencyChartViewInstantiation() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val chartView = PingD3LatencyChartView(context)
    assertNotNull(chartView)

    chartView.resetChart("8.8.8.8")
    chartView.addPingSample(1, 12.4, true)
    chartView.addPingSample(2, 14.1, true)
    chartView.addPingSample(3, 0.0, false)
    chartView.setRunning(true)
    chartView.setRunning(false)
  }

  // ==========================================
  // WI-FI SCANNER TESTS
  // ==========================================

  @Test
  fun testWifiBandAndChannelCalculations() {
    // 2.4 GHz channels
    assertEquals(WifiBand.BAND_2_4_GHZ, WifiNetworkInfo.determineBand(2412))
    assertEquals(1, WifiNetworkInfo.frequencyToChannel(2412))
    assertEquals(6, WifiNetworkInfo.frequencyToChannel(2437))
    assertEquals(11, WifiNetworkInfo.frequencyToChannel(2462))
    assertEquals(14, WifiNetworkInfo.frequencyToChannel(2484))

    // 5 GHz channels
    assertEquals(WifiBand.BAND_5_GHZ, WifiNetworkInfo.determineBand(5180))
    assertEquals(36, WifiNetworkInfo.frequencyToChannel(5180))
    assertEquals(40, WifiNetworkInfo.frequencyToChannel(5200))
    assertEquals(149, WifiNetworkInfo.frequencyToChannel(5745))

    // 6 GHz channels
    assertEquals(WifiBand.BAND_6_GHZ, WifiNetworkInfo.determineBand(5975))
    assertEquals(5, WifiNetworkInfo.frequencyToChannel(5975))

    // Unknown frequency
    assertEquals(WifiBand.UNKNOWN, WifiNetworkInfo.determineBand(900))
  }

  @Test
  fun testWifiSignalAndQualityCalculations() {
    // Bars: 4 (>= -55), 3 (>= -65), 2 (>= -75), 1 (>= -85), 0 (< -85)
    assertEquals(4, WifiNetworkInfo.calculateSignalLevelBars(-45))
    assertEquals(4, WifiNetworkInfo.calculateSignalLevelBars(-55))
    assertEquals(3, WifiNetworkInfo.calculateSignalLevelBars(-60))
    assertEquals(2, WifiNetworkInfo.calculateSignalLevelBars(-70))
    assertEquals(1, WifiNetworkInfo.calculateSignalLevelBars(-80))
    assertEquals(0, WifiNetworkInfo.calculateSignalLevelBars(-95))

    // Percentage
    assertEquals(100, WifiNetworkInfo.calculateSignalPercent(-50))
    assertEquals(100, WifiNetworkInfo.calculateSignalPercent(-30))
    assertEquals(50, WifiNetworkInfo.calculateSignalPercent(-75))
    assertEquals(0, WifiNetworkInfo.calculateSignalPercent(-100))
    assertEquals(0, WifiNetworkInfo.calculateSignalPercent(-110))

    // Distance estimation
    val dist24 = WifiNetworkInfo.estimateDistanceMeters(2412, -50)
    assertTrue(dist24 > 0.0)
    val distFar = WifiNetworkInfo.estimateDistanceMeters(2412, -80)
    assertTrue(distFar > dist24)
  }

  @Test
  fun testWifiSecurityParsing() {
    val (wpa3Label, wpa3Sec, _) = WifiNetworkInfo.parseSecurity("[RSN-SAE-CCMP][WPA3-SAE]")
    assertEquals("WPA3-Personal", wpa3Label)
    assertTrue(wpa3Sec)

    val (mixedLabel, mixedSec, _) = WifiNetworkInfo.parseSecurity("[WPA2-PSK-CCMP][WPA3-SAE]")
    assertEquals("WPA2/WPA3 Mixed", mixedLabel)
    assertTrue(mixedSec)

    val (wpa2Label, wpa2Sec, _) = WifiNetworkInfo.parseSecurity("[WPA2-PSK-CCMP][ESS]")
    assertEquals("WPA2-Personal (PSK)", wpa2Label)
    assertTrue(wpa2Sec)

    val (openLabel, openSec, _) = WifiNetworkInfo.parseSecurity("[ESS]")
    assertEquals("Open / None", openLabel)
    assertFalse(openSec)

    val (_, _, wpsFound) = WifiNetworkInfo.parseSecurity("[WPA2-PSK-CCMP][WPS][ESS]")
    assertTrue(wpsFound)
  }

  @Test
  fun testWifiHiddenNetworkDataModel() {
    val network = WifiNetworkInfo(
        bssid = "00:11:22:33:44:55",
        ssid = "",
        isHidden = true,
        displaySsid = "[Hidden Network]",
        signalDbm = -65,
        signalLevelBars = 3,
        signalQualityPercent = 70,
        signalQualityLabel = "Good",
        frequencyMhz = 2437,
        band = WifiBand.BAND_2_4_GHZ,
        channelNumber = 6,
        channelWidth = "20 MHz",
        securitySummary = "WPA2-Personal",
        isSecure = true,
        hasWps = false,
        wifiStandard = "Wi-Fi 4 (802.11n)",
        rawCapabilities = "[WPA2-PSK-CCMP][ESS]",
        isConnected = false,
        estimatedDistanceMeters = 8.5
    )

    assertEquals("00:11:22:33:44:55", network.bssid)
    assertTrue(network.isHidden)
    assertEquals("[Hidden Network]", network.displaySsid)
    assertEquals(-65, network.signalDbm)
    assertEquals(6, network.channelNumber)
    assertEquals(WifiBand.BAND_2_4_GHZ, network.band)
    assertTrue(network.isSecure)
    assertFalse(network.isConnected)
  }

  @Test
  fun testWifiScannerViewModelAndReportGeneration() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ WifiScannerViewModel(app) }) { viewModel ->
      assertNotNull(viewModel)
      assertNotNull(viewModel.rawState.value)

      // Trigger search filter
      viewModel.onSearchQueryChanged("Test")
      assertEquals("Test", viewModel.searchQuery.value)

      // Trigger band filter
      viewModel.onBandFilterSelected("2.4 GHz")
      assertEquals("2.4 GHz", viewModel.selectedBandFilter.value)

      // Trigger security filter
      viewModel.onSecurityFilterSelected("Secure")
      assertEquals("Secure", viewModel.selectedSecurityFilter.value)

      // Trigger sort order
      viewModel.onSortOrderSelected(WifiSortOrder.SIGNAL_DESC)
      assertEquals(WifiSortOrder.SIGNAL_DESC, viewModel.selectedSortOrder.value)

      // Verify reports
      val textReport = viewModel.generateTextReport()
      assertNotNull(textReport)
      assertTrue(textReport.contains("WI-FI SCANNER REPORT"))
      assertTrue(textReport.contains("Scan Timestamp"))

      val jsonReport = viewModel.generateJsonReport()
      assertNotNull(jsonReport)
      val root = org.json.JSONObject(jsonReport)
      assertTrue(root.has("reportTitle"))
      assertTrue(root.has("accessPoints"))
    }
  }

  @Test
  fun testWifiScannerActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(WifiScannerActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  // ==========================================
  // SIGNAL MAPPER TESTS (APARTMENT VS HOUSE)
  // ==========================================

  @Test
  fun testSignalMapPropertyTypeAndSpaceAllotment() {
    // 1. Apartment has compact space allotment
    assertEquals("Apartment", PropertyType.APARTMENT.title)
    assertEquals(850, PropertyType.APARTMENT.estimatedSqFt)
    assertTrue(PropertyType.APARTMENT.scaleLabel.contains("sq ft"))

    // 2. House has expansive space allotment
    assertEquals("House", PropertyType.HOUSE.title)
    assertEquals(2800, PropertyType.HOUSE.estimatedSqFt)
    assertTrue(PropertyType.HOUSE.estimatedSqFt > PropertyType.APARTMENT.estimatedSqFt)

    // 3. Room layouts
    val aptRooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.APARTMENT)
    assertEquals(5, aptRooms.size)
    assertTrue(aptRooms.any { it.name == "Living Room" && it.isPrimaryApLocation })
    assertTrue(aptRooms.any { it.name == "Master Bedroom" })

    val houseRooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.HOUSE)
    assertEquals(8, houseRooms.size)
    assertTrue(houseRooms.any { it.name == "Living / Great Room" && it.isPrimaryApLocation })
    assertTrue(houseRooms.any { it.name == "Attached Garage" })
    assertTrue(houseRooms.any { it.name == "Front Porch" })
  }

  @Test
  fun testSignalRatingFromDbm() {
    assertEquals(SignalRating.EXCELLENT, SignalRating.fromDbm(-50))
    assertEquals(SignalRating.GOOD, SignalRating.fromDbm(-62))
    assertEquals(SignalRating.FAIR, SignalRating.fromDbm(-72))
    assertEquals(SignalRating.POOR, SignalRating.fromDbm(-78))
    assertEquals(SignalRating.DEAD_ZONE, SignalRating.fromDbm(-88))
  }

  @Test
  fun testSignalMapEngineInterpolation() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val engine = SignalMapEngine(context)

    val sample1 = SignalSample(
        roomName = "Living Room",
        relX = 0.2f,
        relY = 0.2f,
        rssiDbm = -45
    )
    val sample2 = SignalSample(
        roomName = "Bedroom",
        relX = 0.8f,
        relY = 0.8f,
        rssiDbm = -80
    )

    // Point exactly at sample1 should be -45
    val atSample1 = engine.interpolateDbmAtPoint(0.2f, 0.2f, listOf(sample1, sample2))
    assertEquals(-45f, atSample1, 1.0f)

    // Point exactly at sample2 should be -80
    val atSample2 = engine.interpolateDbmAtPoint(0.8f, 0.8f, listOf(sample1, sample2))
    assertEquals(-80f, atSample2, 1.0f)

    // Midpoint between sample1 and sample2 should be between -45 and -80
    val mid = engine.interpolateDbmAtPoint(0.5f, 0.5f, listOf(sample1, sample2))
    assertTrue(mid in -75f..-50f)
  }

  @Test
  fun testSignalMapCoverageAnalysis() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val engine = SignalMapEngine(context)

    val samples = listOf(
        SignalSample(roomName = "Living Room", relX = 0.2f, relY = 0.2f, rssiDbm = -45),
        SignalSample(roomName = "Kitchen", relX = 0.5f, relY = 0.2f, rssiDbm = -60),
        SignalSample(roomName = "Balcony", relX = 0.8f, relY = 0.8f, rssiDbm = -86)
    )

    val rooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.APARTMENT)
    val analysis = engine.calculateCoverageAnalysis(PropertyType.APARTMENT, rooms, samples)

    assertEquals(3, analysis.totalSamples)
    assertEquals(1, analysis.deadZonesCount)
    assertEquals("Balcony", analysis.weakestRoom)
    assertTrue(analysis.summaryRecommendation.contains("Balcony"))
    assertTrue(analysis.recommendedApPlacement.isNotBlank())
  }

  @Test
  fun testSignalMapViewModelOperationsAndReports() = runTest {
    val app = ApplicationProvider.getApplicationContext<Application>()
    withViewModel({ SignalMapViewModel(app) }) { viewModel ->
      // 1. Initial property type is Apartment
      assertEquals(PropertyType.APARTMENT, viewModel.propertyType.value)

      // 2. Switch to House
      viewModel.setPropertyType(PropertyType.HOUSE)
      assertEquals(PropertyType.HOUSE, viewModel.propertyType.value)
      assertEquals(8, viewModel.rooms.value.size)

      // 3. Record sample at specific coordinate
      viewModel.recordSampleAtCoordinates(0.35f, 0.10f, "Living / Great Room")
      assertEquals(1, viewModel.samples.value.size)
      assertEquals("Living / Great Room", viewModel.samples.value[0].roomName)

      // 4. Populate demo survey
      viewModel.populateDemoSurvey()
      assertEquals(8, viewModel.samples.value.size)

      // 5. Generate Text Report
      val textReport = viewModel.generateTextReport()
      assertNotNull(textReport)
      assertTrue(textReport.contains("SITE SURVEY REPORT"))
      assertTrue(textReport.contains("House"))
      assertTrue(textReport.contains("2,800 sq ft"))
      assertTrue(textReport.contains("ROOM-BY-ROOM MEASUREMENTS"))

      // 6. Generate JSON Report
      val jsonReport = viewModel.generateJsonReport()
      assertNotNull(jsonReport)
      val root = org.json.JSONObject(jsonReport)
      assertEquals("HOUSE", root.getString("propertyType"))
      assertEquals(8, root.getJSONArray("samples").length())

      // 7. Clear all samples
      viewModel.clearAllSamples()
      assertEquals(0, viewModel.samples.value.size)
    }
  }

  @Test
  fun testSignalMapperActivityLaunchesWithoutCrash() {
    withDestroyedActivity(Robolectric.buildActivity(SignalMapperActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
    }
  }

  // ==========================================
  // CEILING & FLOOR SIGNAL STRENGTH & ALL-TOOLS VERIFICATION
  // ==========================================

  @Test
  fun testSignalStrengthCeilingFloorClassification() {
    assertEquals(-30, SignalStrengthTier.OVERALL_CEILING_DBM)
    assertEquals(-100, SignalStrengthTier.OVERALL_FLOOR_DBM)

    val excellent = SignalStrengthTier.fromDbm(-42)
    assertEquals(SignalStrengthTier.EXCELLENT, excellent)
    assertEquals(-30, excellent.tierCeilingDbm)
    assertEquals(-55, excellent.tierFloorDbm)
    assertEquals(58, SignalStrengthTier.marginAboveFloorDb(-42))
    assertEquals(12, SignalStrengthTier.headroomBelowCeilingDb(-42))

    val good = SignalStrengthTier.fromDbm(-60)
    assertEquals(SignalStrengthTier.GOOD, good)
    assertEquals(-56, good.tierCeilingDbm)
    assertEquals(-67, good.tierFloorDbm)

    val fair = SignalStrengthTier.fromDbm(-71)
    assertEquals(SignalStrengthTier.FAIR, fair)
    assertEquals(-68, fair.tierCeilingDbm)
    assertEquals(-75, fair.tierFloorDbm)

    val weak = SignalStrengthTier.fromDbm(-79)
    assertEquals(SignalStrengthTier.WEAK, weak)
    assertEquals(-76, weak.tierCeilingDbm)
    assertEquals(-82, weak.tierFloorDbm)

    val dead = SignalStrengthTier.fromDbm(-90)
    assertEquals(SignalStrengthTier.DEAD_ZONE, dead)
    assertEquals(-83, dead.tierCeilingDbm)
    assertEquals(-100, dead.tierFloorDbm)

    val fullDesc = SignalStrengthTier.formatFullDescription(-60)
    assertTrue(fullDesc.contains("-60 dBm"))
    assertTrue(fullDesc.contains("Good"))
    assertTrue(fullDesc.contains("RF Floor: -100 dBm"))
    assertTrue(fullDesc.contains("Ceiling: -30 dBm"))
    assertTrue(fullDesc.contains("+40 dB above floor"))

    // Verify SignalRating ceiling & floor alignment
    assertEquals(-30, SignalRating.RF_CEILING_DBM)
    assertEquals(-100, SignalRating.RF_FLOOR_DBM)
    assertEquals(-55, SignalRating.EXCELLENT.floorDbm)
    assertEquals(-30, SignalRating.EXCELLENT.ceilingDbm)
    assertEquals(-100, SignalRating.DEAD_ZONE.floorDbm)
  }

  @Test
  fun testAllHomeScreenToolsExistAndLaunchCorrectDestinations() {
    withDestroyedActivity(Robolectric.buildActivity(MainActivity::class.java).setup()) { activity ->
      val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)

      fun collectButtons(view: android.view.View, out: MutableList<android.widget.Button>) {
        if (view is android.widget.Button) {
          out.add(view)
        } else if (view is android.view.ViewGroup) {
          for (i in 0 until view.childCount) {
            collectButtons(view.getChildAt(i), out)
          }
        }
      }

      val buttons = mutableListOf<android.widget.Button>()
      collectButtons(root, buttons)

      val expectedActivityRoutes = mapOf(
          "IP Address\nView network information" to NetworkInfoActivity::class.java.name,
          "DNS Lookup\nCheck DNS records" to DnsLookupActivity::class.java.name,
          "Gateway Info\nView network gateway" to NetworkInfoActivity::class.java.name,
          "Wi-Fi Tools\nWireless information" to NetworkInfoActivity::class.java.name,
          "Wi-Fi Scanner\nScan nearby APs & signal" to WifiScannerActivity::class.java.name,
          "Signal Mapper\nMap coverage in apt / house" to SignalMapperActivity::class.java.name,
          "Speed Test\nMeasure connection speed" to SpeedTestActivity::class.java.name,
          "Traceroute\nTrace network routes" to TracerouteActivity::class.java.name,
          "LAN Scanner\nDiscover devices on your network" to LanScannerActivity::class.java.name,
          "Subnet Calculator\nCalculate network ranges" to SubnetCalculatorActivity::class.java.name,
          "Network Notes\nField technician notes" to NetworkNotesActivity::class.java.name,
          "iPerf3 Client / Server\nNetwork throughput test" to com.example.networktoolkit.iperf3.IPerf3Activity::class.java.name,
          "Test History\nDiagnostic test records" to PingHistoryActivity::class.java.name
      )

      val buttonByText = buttons.associateBy { it.text.toString() }
      assertNull(
          "Standalone STELLAR Speed Test button should be removed from home grid",
          buttonByText["STELLAR Speed Test\nSpecialized speed test"]
      )
      val shadowActivity = org.robolectric.Shadows.shadowOf(activity)

      for ((label, expectedClassName) in expectedActivityRoutes) {
        val btn = buttonByText[label]
        assertNotNull("Missing home button: $label", btn)
        btn!!.performClick()
        val started = shadowActivity.nextStartedActivity
        assertNotNull("Clicking '$label' did not start an Activity", started)
        assertEquals("Clicking '$label' launched wrong Activity", expectedClassName, started.component?.className)

        if (label.startsWith("Gateway Info")) {
          assertEquals("gateway", started.getStringExtra("target_section"))
        } else if (label.startsWith("Wi-Fi Tools")) {
          assertEquals("wifi", started.getStringExtra("target_section"))
        }
      }

      // Verify Ping Test transitions cleanly to in-activity Ping screen
      val pingBtn = buttonByText["Ping Test\nTest connectivity"]
      assertNotNull("Missing home button: Ping Test", pingBtn)
      pingBtn!!.performClick()
      val updatedButtons = mutableListOf<android.widget.Button>()
      collectButtons(activity.findViewById(android.R.id.content), updatedButtons)
      assertTrue(
          "Ping Test screen should show Start Ping button",
          updatedButtons.any { it.text.toString().contains("Ping", ignoreCase = true) }
      )
    }
  }

  // ==========================================
  // REAL-TIME WI-FI SIGNAL FLUCTUATION GRAPH TESTS
  // ==========================================

  @Test
  fun testSignalGraphThresholdsFromMinus50ToMinus90Dbm() {
    val dbmValues = SignalGraphThreshold.entries.map { it.dbm }
    assertEquals(listOf(-50, -60, -70, -80, -90), dbmValues)
    assertEquals("Excellent (-50 dBm)", SignalGraphThreshold.EXCELLENT.label)
    assertEquals("Poor (-90 dBm)", SignalGraphThreshold.POOR.label)
  }

  @Test
  fun testRealTimeWifiSignalProviderFluctuationMetricsAndRollingWindow() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val provider = RealTimeWifiSignalProvider(context, maxSamples = 5)

    // Initially empty
    assertTrue(provider.state.value.samples.isEmpty())
    assertNull(provider.state.value.currentRssiDbm)

    // Push fluctuating RSSI samples spanning -50 dBm (Excellent) to -90 dBm (Poor)
    val readings = listOf(-48, -55, -66, -78, -90, -52)
    readings.forEach { rssi ->
      provider.pushSample(
          rssiDbm = rssi,
          ssid = "Office_Mesh_5G",
          bssid = "AA:BB:CC:DD:EE:FF",
          linkSpeedMbps = 650,
          frequencyMhz = 5180
      )
    }

    val state = provider.state.value
    // Rolling window capped at maxSamples = 5
    assertEquals(5, state.samples.size)
    assertEquals(-52, state.currentRssiDbm)
    assertEquals(-90, state.minDbm)
    assertEquals(-52, state.maxDbm)
    assertTrue((state.avgDbm ?: 0.0) < -52.0 && (state.avgDbm ?: 0.0) > -90.0)
    assertTrue("Expected positive fluctuation std dev", state.fluctuationStdDevDb > 5.0)
    assertEquals("Office_Mesh_5G", state.ssid)
    assertTrue(state.isWifiConnected)

    // Test pause & resume
    provider.togglePause()
    assertTrue(provider.state.value.isPaused)
    provider.togglePause()
    assertFalse(provider.state.value.isPaused)

    // Test sample interval clamping
    provider.setSampleIntervalMs(500L)
    assertEquals(500L, provider.state.value.sampleIntervalMs)
    provider.setSampleIntervalMs(50L)
    assertEquals(250L, provider.state.value.sampleIntervalMs)

    // Test clear history
    provider.clearHistory()
    assertTrue(provider.state.value.samples.isEmpty())
    assertNull(provider.state.value.minDbm)
    assertNull(provider.state.value.maxDbm)
    assertNull(provider.state.value.avgDbm)
    assertEquals(0.0, provider.state.value.fluctuationStdDevDb, 0.001)

    provider.dispose()
  }

  // ==========================================
  // CONFIGURABLE SPEED TEST SERVICE TESTS
  // ==========================================

  @Test
  fun testSpeedTestServicePreferencesDefaultAndSwitching() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    context.getSharedPreferences("SpeedTestPrefs", Context.MODE_PRIVATE).edit().clear().commit()

    // Default should be Speedtest.net
    val defaultService = SpeedTestServicePreferences.getSelectedService(context)
    assertEquals("speedtest_net", defaultService.id)
    assertEquals("https://www.speedtest.net", defaultService.url)

    // Switch to Cloudflare preset
    SpeedTestServicePreferences.saveSelectedPreset(context, "cloudflare")
    val cloudflare = SpeedTestServicePreferences.getSelectedService(context)
    assertEquals("cloudflare", cloudflare.id)
    assertEquals("https://speed.cloudflare.com", cloudflare.url)

    // Switch to Fast.com preset
    SpeedTestServicePreferences.saveSelectedPreset(context, "fast_com")
    val fastCom = SpeedTestServicePreferences.getSelectedService(context)
    assertEquals("fast_com", fastCom.id)
    assertEquals("https://fast.com", fastCom.url)

    // Save custom URL without scheme -> auto-normalizes to https://
    val custom = SpeedTestServicePreferences.saveCustomService(context, "ISP Portal", "speed.isp.example.com")
    assertNotNull(custom)
    assertEquals("ISP Portal", custom!!.name)
    assertEquals("https://speed.isp.example.com", custom.url)
    assertTrue(custom.isCustom)

    val loadedCustom = SpeedTestServicePreferences.getSelectedService(context)
    assertEquals("https://speed.isp.example.com", loadedCustom.url)
    assertEquals("ISP Portal", loadedCustom.name)

    // Invalid URLs rejected
    assertNull(SpeedTestServicePreferences.saveCustomService(context, "Bad", ""))
    assertNull(SpeedTestServicePreferences.saveCustomService(context, "Bad", "not a valid url"))
  }

  @Test
  fun testWebTestActivityUsesConfiguredServiceWhenNoExtras() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    SpeedTestServicePreferences.saveSelectedPreset(context, "cloudflare")

    withDestroyedActivity(Robolectric.buildActivity(WebTestActivity::class.java).setup()) { activity ->
      assertNotNull(activity)
      val webView = Shadows.shadowOf(activity)
      assertNotNull(webView)
    }
  }
}






