package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.iperf3.IPerf3Engine
import com.example.networktoolkit.iperf3.IPerf3TransferAnalyzer
import com.example.networktoolkit.iperf3.IPerf3ViewModel
import com.example.networktoolkit.iperf3.IPerfInterval
import com.example.networktoolkit.iperf3.IPerfMode
import com.example.networktoolkit.iperf3.IPerfUiState
import com.example.networktoolkit.speedtest.MetricsOverviewGrid
import com.example.networktoolkit.speedtest.SpeedTestCalculator
import com.example.networktoolkit.speedtest.SpeedTestEngine
import com.example.networktoolkit.speedtest.SpeedTestUiState
import com.example.networktoolkit.speedtest.buildSpeedTestReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticInvestigationFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =========================================================================
    // TRACEROUTE PARSING, VALIDATION & REPORT TESTS
    // =========================================================================

    @Test
    fun `traceroute parser preserves real 240_0_0_0_4 carrier responders and annotates scope accurately`() {
        val line = "From 255.0.0.1 icmp_seq=1 Time to live exceeded"
        val parsed = TracerouteOutputParser.parsePingProbeLine(line)

        assertTrue(parsed.responded)
        assertEquals("255.0.0.1", parsed.hopIp)
        assertFalse(parsed.isDestinationReached)

        val status = TracerouteOutputParser.buildHopStatusText(
            ip = parsed.hopIp!!,
            isDestinationReached = parsed.isDestinationReached,
            errorStatus = parsed.errorStatus
        )
        assertTrue(status.contains("Reserved 240.0.0.0/4"))

        val json = TracerouteGraphDataBuilder.buildHopsJson(
            listOf(
                TracerouteHop(
                    hopNumber = 2,
                    ip = parsed.hopIp!!,
                    hostname = null,
                    rttMs = 31.4,
                    isDestinationReached = false,
                    statusText = status
                )
            )
        )
        assertEquals("Reserved", json.getJSONObject(0).getString("ipScope"))
    }

    @Test
    fun `traceroute parser does not mark icmp error lines with icmp_seq as destination reached`() {
        val unreachableLine = "From 10.177.61.38 icmp_seq=1 Destination Net Unreachable"
        val parsedUnreach = TracerouteOutputParser.parsePingProbeLine(unreachableLine)
        assertEquals("10.177.61.38", parsedUnreach.hopIp)
        assertFalse(parsedUnreach.isDestinationReached)
        assertEquals("Destination Net Unreachable", parsedUnreach.errorStatus)

        val filteredLine = "From 192.0.2.1 icmp_seq=1 Packet filtered"
        val parsedFiltered = TracerouteOutputParser.parsePingProbeLine(filteredLine)
        assertEquals("192.0.2.1", parsedFiltered.hopIp)
        assertFalse(parsedFiltered.isDestinationReached)
        assertEquals("ICMP Administratively Filtered", parsedFiltered.errorStatus)
    }

    @Test
    fun `traceroute parser handles ipv4 ipv6 parenthesized host digits and native traceroute lines`() {
        val hostWithIpDigits = "From 192.0.2.1.dsl.isp.net (198.51.100.2) icmp_seq=1 Time to live exceeded"
        val parsedParen = TracerouteOutputParser.parsePingProbeLine(hostWithIpDigits)
        assertEquals("198.51.100.2", parsedParen.hopIp)
        assertFalse(parsedParen.isDestinationReached)

        val ipv4Reply = "64 bytes from 192.178.110.193: icmp_seq=1 ttl=57 time=63.1 ms"
        val parsedV4Reply = TracerouteOutputParser.parsePingProbeLine(ipv4Reply)
        assertEquals("192.178.110.193", parsedV4Reply.hopIp)
        assertTrue(parsedV4Reply.isDestinationReached)
        assertEquals(63.1, parsedV4Reply.rttMs!!, 0.01)

        val ipv6Exceeded = "From 2001:470:0:236::1 icmp_seq=1 Time exceeded: Hop limit"
        val parsedV6Exceeded = TracerouteOutputParser.parsePingProbeLine(ipv6Exceeded)
        assertEquals("2001:470:0:236::1", parsedV6Exceeded.hopIp)
        assertFalse(parsedV6Exceeded.isDestinationReached)

        val ipv6Reply = "64 bytes from 2001:4860:4860::8888: icmp_seq=1 hlim=57 time=18.4 ms"
        val parsedV6Reply = TracerouteOutputParser.parsePingProbeLine(ipv6Reply)
        assertEquals("2001:4860:4860::8888", parsedV6Reply.hopIp)
        assertTrue(parsedV6Reply.isDestinationReached)
        assertEquals(18.4, parsedV6Reply.rttMs!!, 0.01)

        val nativeTimeout = TracerouteOutputParser.parseNativeTracerouteLine(
            line = " 2  * * *",
            target = "google.com",
            targetIp = "192.178.110.193"
        )
        assertTrue(nativeTimeout!!.isTimedOut)

        val nativeV6 = TracerouteOutputParser.parseNativeTracerouteLine(
            line = " 3  255.0.0.2 (255.0.0.2)  41.2 ms  40.8 ms",
            target = "google.com",
            targetIp = "192.178.110.193"
        )
        assertEquals(3, nativeV6!!.hopNumber)
        assertEquals("255.0.0.2", nativeV6.ip)
        assertEquals(41.2, nativeV6.rttMs!!, 0.01)

        assertFalse(TracerouteOutputParser.isValidHopResponderIp("255.255.255.255"))
        assertFalse(TracerouteOutputParser.isValidHopResponderIp("0.0.0.0"))
        assertFalse(TracerouteOutputParser.isValidHopResponderIp("::"))
        assertFalse(TracerouteOutputParser.isValidHopResponderIp("224.0.0.1"))
        assertFalse(TracerouteOutputParser.isValidHopResponderIp("999.1.1.1"))
        assertTrue(TracerouteOutputParser.isValidHopResponderIp("255.0.0.1"))
    }

    @Test
    fun `traceroute report annotates carrier synthetic 240_0_0_0_4 hops when present`() {
        val report = buildTracerouteReport(
            target = "google.com",
            hops = listOf(
                TracerouteHop(1, "255.0.0.1", null, 28.0, false, false, "Intermediate Hop (Reserved 240.0.0.0/4 / Carrier-Translated)"),
                TracerouteHop(2, "192.178.110.193", null, 58.2, true, false, "Destination Reached")
            )
        )
        assertTrue(report.contains("255.0.0.1"))
        assertTrue(report.contains("Carrier Note"))
        assertTrue(report.contains("240.0.0.0/4"))
    }

    // =========================================================================
    // IPERF3 UPLOAD, CONTROL MONITOR, STALL DETECTION & STOP TESTS
    // =========================================================================

    @Test
    fun `iperf3 multi-second loopback upload sustains transfer across intervals without zeroing out`(): Unit = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()
        val intervals = mutableListOf<IPerfInterval>()

        val serverJob = async {
            serverEngine.runServer(
                port = probePort,
                onClientConnected = {},
                onInterval = {}
            )
        }

        delay(120L)

        val clientRes = clientEngine.runClient(
            host = "127.0.0.1",
            port = probePort,
            durationSeconds = 2,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = { synchronized(intervals) { intervals.add(it) } }
        )

        val serverRes = serverJob.await()
        assertTrue("Client upload should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("Server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertFalse(summary.isStalled)
        assertFalse(summary.isInterrupted)
        assertTrue("Expected multi-second intervals, got ${summary.intervals.size}", summary.intervals.size >= 2)
        assertTrue(
            "Subsequent intervals should also transfer data on loopback",
            summary.intervals.drop(1).any { it.bytesTransferred > 0L }
        )
    }

    @Test
    fun `iperf3 control channel monitor terminates client early when server sends SERVER_TERMINATE`(): Unit = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val clientEngine = IPerf3Engine()

        val mockServerJob = async(Dispatchers.IO) {
            serverSocket.use { listener ->
                val ctrlSock: Socket = listener.accept()
                val ctrlIn = DataInputStream(ctrlSock.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSock.getOutputStream())
                val cookie = ByteArray(37)
                ctrlIn.readFully(cookie)

                // Send PARAM_EXCHANGE (9)
                ctrlOut.writeByte(9)
                ctrlOut.flush()

                // Read client JSON params
                val jsonLen = ctrlIn.readInt()
                val jsonBuf = ByteArray(jsonLen)
                ctrlIn.readFully(jsonBuf)

                // Send CREATE_STREAMS (10) and accept data stream without draining it
                ctrlOut.writeByte(10)
                ctrlOut.flush()
                val dataSock = listener.accept()
                val dataIn = DataInputStream(dataSock.getInputStream())
                val dataCookie = ByteArray(37)
                dataIn.readFully(dataCookie)

                // Send TEST_START (1) and TEST_RUNNING (2)
                ctrlOut.writeByte(1)
                ctrlOut.writeByte(2)
                ctrlOut.flush()

                // Remote server sends SERVER_TERMINATE (11) after 500ms during a 10s upload
                delay(500L)
                ctrlOut.writeByte(11)
                ctrlOut.flush()

                dataSock.close()
                ctrlSock.close()
            }
        }

        val startMs = System.currentTimeMillis()
        val clientRes = clientEngine.runClient(
            host = "127.0.0.1",
            port = port,
            durationSeconds = 10,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )
        val elapsedMs = System.currentTimeMillis() - startMs
        mockServerJob.await()

        assertTrue(
            "Control channel monitor must terminate early (< 5s), took ${elapsedMs}ms",
            elapsedMs < 5000L
        )
        assertTrue("Expected interrupted summary on SERVER_TERMINATE: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertTrue(summary.isInterrupted)
        assertNotNull(summary.diagnosticNote)
        assertTrue(summary.diagnosticNote!!.contains("SERVER_TERMINATE", ignoreCase = true))
    }

    @Test
    fun `iperf3 stall analyzer detects 0_56 MB initial kernel buffer burst followed by zero intervals`() {
        val stalledIntervals = listOf(
            IPerfInterval(1, "0.0 - 1.0 sec", 589_824L, 4.72),
            IPerfInterval(2, "1.0 - 2.0 sec", 0L, 0.0),
            IPerfInterval(3, "2.0 - 3.0 sec", 0L, 0.0),
            IPerfInterval(4, "3.0 - 4.0 sec", 0L, 0.0)
        )
        assertTrue(IPerf3TransferAnalyzer.detectStallAfterInitialBurst(stalledIntervals, reverseMode = false))
        assertFalse(IPerf3TransferAnalyzer.detectStallAfterInitialBurst(stalledIntervals, reverseMode = true))

        val note = IPerf3TransferAnalyzer.buildDiagnosticNote(
            isInterrupted = false,
            isStalled = true,
            peerTerminated = false,
            reverseMode = false,
            totalBytes = 589_824L,
            resolvedIp = "2001:470:0:236::2",
            errorDetail = null
        )
        assertTrue(note!!.contains("send buffer", ignoreCase = true))

        val healthyIntervals = listOf(
            IPerfInterval(1, "0.0 - 1.0 sec", 450_000L, 3.6),
            IPerfInterval(2, "1.0 - 2.0 sec", 420_000L, 3.36),
            IPerfInterval(3, "2.0 - 3.0 sec", 430_000L, 3.44)
        )
        assertFalse(IPerf3TransferAnalyzer.detectStallAfterInitialBurst(healthyIntervals, reverseMode = false))
    }

    @Test
    fun `iperf3 viewmodel client stop transitions to interrupted summary without unexpected error`(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        shadowCm.clearAllNetworks()
        val wifiNet = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        @Suppress("DEPRECATION")
        val wifiInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val wifiCaps = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowCm.addNetwork(wifiNet, wifiInfo)
        shadowCm.setNetworkCapabilities(wifiNet, wifiCaps)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(wifiInfo)

        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val serverJob = async {
            serverEngine.runServer(
                port = probePort,
                onClientConnected = {},
                onInterval = {}
            )
        }
        delay(120L)

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return IPerf3ViewModel(app) as T
                    }
                }
            )[IPerf3ViewModel::class.java]
            vm.setMode(IPerfMode.CLIENT)
            vm.onTargetHostChanged("127.0.0.1")
            vm.onPortInputChanged(probePort.toString())
            vm.onDurationChanged(15)
            vm.startTest()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            var enteredRunning = false
            for (i in 0..30) {
                delay(50L)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (vm.uiState.value is IPerfUiState.Running) {
                    enteredRunning = true
                    break
                }
            }
            assertTrue("Expected Running state before stopping", enteredRunning)

            delay(350L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.stopTest()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            var finalState: IPerfUiState = vm.uiState.value
            for (i in 0..40) {
                delay(50L)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                finalState = vm.uiState.value
                if (finalState !is IPerfUiState.Running) break
            }
            assertTrue(
                "User-stopped client test must produce Completed(isInterrupted=true), not Error: $finalState",
                finalState is IPerfUiState.Completed
            )
            val summary = (finalState as IPerfUiState.Completed).summary
            assertTrue(summary.isInterrupted)
            assertNotNull(summary.diagnosticNote)
            assertTrue(
                summary.diagnosticNote!!.contains("stopped by the user", ignoreCase = true) ||
                    summary.diagnosticNote!!.contains("interrupted", ignoreCase = true)
            )
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            serverEngine.stop()
            runCatching { serverJob.await() }
        }
    }

    // =========================================================================
    // SPEED TEST ACCURACY & UNAVAILABLE METRICS TESTS
    // =========================================================================

    @Test
    fun `speed test calculator computes multi-chunk upload throughput and rfc3550 jitter accurately`() {
        // Simulates 5 MB uploaded during a 150 ms burst window -> ~266.67 Mbps
        // (Previously clamped to fixed 64 KB constant chunk ~3.49 Mbps!)
        val burstMbps = SpeedTestCalculator.calculateMbps(bytes = 5_000_000L, elapsedNanos = 150_000_000L)
        assertEquals(266.67, burstMbps, 0.05)

        // Known RFC 3550 jitter check: samples [20, 25, 22, 26] -> avg = 23.25, diffs = [5, 3, 4] -> jitter = 4.0
        val (avg, jitter) = SpeedTestCalculator.calculateLatencyAndJitter(listOf(20.0, 25.0, 22.0, 26.0))
        assertEquals(23.25, avg, 0.001)
        assertEquals(4.0, jitter, 0.001)

        // Single sample -> valid latency, unavailable jitter (-1.0)
        val (singleAvg, singleJitter) = SpeedTestCalculator.calculateLatencyAndJitter(listOf(42.5))
        assertEquals(42.5, singleAvg, 0.001)
        assertEquals(-1.0, singleJitter, 0.001)

        // Empty -> both unavailable (-1.0)
        val (emptyAvg, emptyJitter) = SpeedTestCalculator.calculateLatencyAndJitter(emptyList())
        assertEquals(-1.0, emptyAvg, 0.001)
        assertEquals(-1.0, emptyJitter, 0.001)
    }

    @Test
    fun `speed test ui and report show Unavailable or dashes instead of misleading zero or Optimal badges`() {
        val partialResult = SpeedTestEngine.SpeedTestResult(
            downloadMbps = 48.5,
            uploadMbps = -1.0,
            latencyMs = -1.0,
            jitterMs = -1.0,
            peakDownloadMbps = 62.3,
            peakUploadMbps = -1.0,
            bytesDownloaded = 48_500_000L,
            bytesUploaded = 0L,
            serverName = "Cloudflare Global Edge CDN"
        )

        val report = buildSpeedTestReport(partialResult)
        assertTrue(report.contains("48.50 Mbps (Peak: 62.30 Mbps)"))
        assertTrue(report.contains("Upload Speed:   Unavailable"))
        assertTrue(report.contains("Latency / Ping: Unavailable"))
        assertTrue(report.contains("Jitter:         Unavailable"))

        composeTestRule.setContent {
            MetricsOverviewGrid(uiState = SpeedTestUiState.Completed(partialResult))
        }

        composeTestRule.onNodeWithText("48.5").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peak: 62.3 Mbps").assertIsDisplayed()
        // Upload, Latency, Jitter should display "--" and "Unavailable" rather than "0", "Optimal", or "Stable"
        composeTestRule.onAllNodesWithText("--").assertCountEquals(3)
        composeTestRule.onAllNodesWithText("Unavailable").assertCountEquals(3)
        composeTestRule.onAllNodesWithText("Optimal").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Stable").assertCountEquals(0)
    }
}
