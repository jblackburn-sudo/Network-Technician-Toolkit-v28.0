package com.example.networktoolkit

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.example.networktoolkit.iperf3.IPerf3Engine
import com.example.networktoolkit.iperf3.IPerf3ErrorClassifier
import com.example.networktoolkit.iperf3.IPerf3Presets
import com.example.networktoolkit.iperf3.IPerf3Protocol
import com.example.networktoolkit.iperf3.IPerf3Screen
import com.example.networktoolkit.iperf3.IPerf3Validator
import com.example.networktoolkit.iperf3.IPerf3ViewModel
import com.example.networktoolkit.iperf3.IPerfEngineException
import com.example.networktoolkit.iperf3.IPerfFailureCategory
import com.example.networktoolkit.iperf3.IPerfInterval
import com.example.networktoolkit.iperf3.IPerfPresetCategory
import com.example.networktoolkit.iperf3.IPerfProtocolException
import com.example.networktoolkit.iperf3.IPerfServerUnavailableException
import com.example.networktoolkit.iperf3.IPerfTerminationReason
import com.example.networktoolkit.iperf3.IPerfUiState
import com.example.networktoolkit.iperf3.NoActiveNetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Iperf3DiagnosticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `default target host is empty and empty submission fails validation`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
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

            assertEquals("", vm.targetHost.value)
            assertEquals("5201", vm.portInput.value)
            assertEquals(5000, vm.connectTimeoutMs.value)
            assertEquals(10, vm.durationSeconds.value)

            vm.startTest()
            val state = vm.uiState.value
            assertTrue(state is IPerfUiState.Error)
            val err = (state as IPerfUiState.Error).details
            assertEquals(IPerfFailureCategory.INVALID_CONFIGURATION, err.category)
            assertEquals(IPerf3Validator.EMPTY_HOST_MESSAGE, err.summaryMessage)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `host syntax validation accepts ipv4 ipv6 and hostnames while rejecting invalid inputs`() {
        // Valid hosts
        assertTrue(IPerf3Validator.isValidHostSyntax("192.168.1.50"))
        assertTrue(IPerf3Validator.isValidHostSyntax("127.0.0.1"))
        assertTrue(IPerf3Validator.isValidHostSyntax("::1"))
        assertTrue(IPerf3Validator.isValidHostSyntax("[::1]"))
        assertTrue(IPerf3Validator.isValidHostSyntax("2001:4860:4860::8888"))
        assertTrue(IPerf3Validator.isValidHostSyntax("fe80::1%wlan0"))
        assertTrue(IPerf3Validator.isValidHostSyntax("iperf-server.local"))
        assertTrue(IPerf3Validator.isValidHostSyntax("fileserver"))

        // Invalid hosts & IPv4/IPv6 syntax
        assertFalse(IPerf3Validator.isValidHostSyntax(""))
        assertFalse(IPerf3Validator.isValidHostSyntax("   "))
        assertFalse(IPerf3Validator.isValidHostSyntax("999.1.1.1"))
        assertFalse(IPerf3Validator.isValidHostSyntax("256.0.0.1"))
        assertFalse(IPerf3Validator.isValidHostSyntax("fe80:::1"))
        assertFalse(IPerf3Validator.isValidHostSyntax("gggg::1"))
        assertFalse(IPerf3Validator.isValidHostSyntax("http://192.168.1.1"))
        assertFalse(IPerf3Validator.isValidHostSyntax("host with spaces"))
        assertFalse(IPerf3Validator.isValidHostSyntax("bad..domain.com"))

        // Full config validation boundaries (ports, streams 1..8, duration 1..300s, timeout 1000..60000ms)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "0", 10, 1, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "65536", 10, 1, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "abc", 10, 1, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 0, 1, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 301, 1, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 10, 0, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 10, 9, 5000).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 10, 1, 500).isValid)
        assertFalse(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 10, 1, 61000).isValid)

        assertTrue(IPerf3Validator.validateClientConfig("192.168.1.50", "80", 10, 2, 4000).isValid)
        assertTrue(IPerf3Validator.validateClientConfig("192.168.1.50", "5201", 300, 8, 60000).isValid)
        assertFalse(IPerf3Validator.validateServerConfig("80").isValid)
        assertFalse(IPerf3Validator.validateServerConfig("70000").isValid)
        assertTrue(IPerf3Validator.validateServerConfig("5201").isValid)
    }

    @Test
    fun `presets separate local examples from public iperf3 servers without ordinary websites`() {
        val allHosts = (IPerf3Presets.localExamples + IPerf3Presets.publicServers).map { it.host.lowercase() }
        assertFalse(allHosts.contains("google.com"))
        assertFalse(allHosts.contains("cloudflare.com"))
        assertFalse(allHosts.contains("speedtest.net"))
        assertFalse(allHosts.contains("fast.com"))

        assertTrue(IPerf3Presets.localExamples.all { it.category == IPerfPresetCategory.LOCAL_EXAMPLE })
        assertTrue(IPerf3Presets.publicServers.all { it.category == IPerfPresetCategory.PUBLIC_IPERF3 })
        assertTrue(IPerf3Presets.publicServers.all { it.description.contains("guarantee", ignoreCase = true) || it.description.contains("not guaranteed", ignoreCase = true) })
    }

    @Test
    fun `error classifier categorizes all network and protocol failure modes cleanly`() {
        val dns = IPerf3ErrorClassifier.classify(UnknownHostException("no host"), "bad.invalid", null, 5201, 4000)
        assertEquals(IPerfFailureCategory.DNS_RESOLUTION_FAILURE, dns.category)
        assertNull(dns.resolvedIp)

        val refused = IPerf3ErrorClassifier.classify(ConnectException("ECONNREFUSED (Connection refused)"), "192.168.1.10", "192.168.1.10", 5201, 4000)
        assertEquals(IPerfFailureCategory.TCP_CONNECTION_REFUSED, refused.category)
        assertEquals("192.168.1.10", refused.resolvedIp)
        assertEquals(4000, refused.connectTimeoutMs)

        val timeout = IPerf3ErrorClassifier.classify(SocketTimeoutException("connect timed out"), "10.0.0.5", "10.0.0.5", 5201, 7000)
        assertEquals(IPerfFailureCategory.CONNECTION_TIMEOUT, timeout.category)
        assertEquals(7000, timeout.connectTimeoutMs)

        val unreach = IPerf3ErrorClassifier.classify(NoRouteToHostException("ENETUNREACH"), "10.99.99.1", "10.99.99.1", 5201, 5000)
        assertEquals(IPerfFailureCategory.NETWORK_UNREACHABLE, unreach.category)

        val offline = IPerf3ErrorClassifier.classify(NoActiveNetworkException("offline"), "iperf.he.net", null, 5201, 5000)
        assertEquals(IPerfFailureCategory.NO_ACTIVE_NETWORK, offline.category)

        val fw = IPerf3ErrorClassifier.classify(PortUnreachableException("filtered"), "192.168.1.20", "192.168.1.20", 5201, 5000)
        assertEquals(IPerfFailureCategory.POSSIBLE_FIREWALL_OR_FILTERING, fw.category)
        assertTrue(fw.troubleshootingSteps.isNotEmpty())

        val proto = IPerf3ErrorClassifier.classify(IPerfProtocolException("HTTP response"), "example.com", "93.184.216.34", 80, 5000)
        assertEquals(IPerfFailureCategory.IPERF3_PROTOCOL_INCOMPATIBILITY, proto.category)

        val busy = IPerf3ErrorClassifier.classify(IPerfServerUnavailableException("ACCESS_DENIED"), "iperf.he.net", "216.218.222.2", 5201, 5000)
        assertEquals(IPerfFailureCategory.SERVER_UNAVAILABLE, busy.category)

        val bindErr = IPerf3ErrorClassifier.classify(BindException("Address already in use"), "Local Server", null, 5201, 0)
        assertEquals(IPerfFailureCategory.SERVER_UNAVAILABLE, bindErr.category)

        val unexpected = IPerf3ErrorClassifier.classify(RuntimeException("unexpected pipe state"), "192.168.1.50", "192.168.1.50", 5201, 5000)
        assertEquals(IPerfFailureCategory.UNEXPECTED_ERROR, unexpected.category)
    }

    @Test
    fun `iperf3 screen shows empty placeholder, enforces non-empty target, and presets do not auto-start`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
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

            composeTestRule.setContent {
                IPerf3Screen(viewModel = vm, onBackClick = {})
            }

            composeTestRule.onNodeWithTag("iperf_host_input").performScrollTo().assertIsDisplayed().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Enter iPerf3 server IP or hostname").assertIsDisplayed()

            // Starting with empty host shows validation card
            composeTestRule.onNodeWithTag("iperf_start_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("iperf_error_card"))
            composeTestRule.onNodeWithTag("iperf_error_card").assertIsDisplayed()
            composeTestRule.onNodeWithText(IPerf3Validator.EMPTY_HOST_MESSAGE).assertIsDisplayed()

            // Selecting a preset only populates the host without starting benchmark
            vm.applyPresetHost("192.168.1.100", 5201)
            composeTestRule.waitForIdle()
            assertEquals("192.168.1.100", vm.targetHost.value)
            assertTrue(vm.uiState.value is IPerfUiState.Idle)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `loopback client and server complete ESnet iPerf3 TCP handshake cleanly`() = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()

        val serverJob = async {
            serverEngine.runServer(
                port = probePort,
                onClientConnected = {},
                onInterval = {}
            )
        }

        // Wait briefly for server socket bind
        delay(120L)

        val clientRes = clientEngine.runClient(
            host = "127.0.0.1",
            port = probePort,
            durationSeconds = 1,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )

        val serverRes = serverJob.await()
        assertTrue("Client should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("Server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertNotNull(summary.resolvedIp)
        assertTrue(summary.totalBytes > 0L)
        assertTrue(summary.avgBandwidthMbps > 0.0)
        assertEquals(3000, summary.connectTimeoutMs)
    }

    @Test
    fun `loopback reverse download mode completes cleanly and calculates throughput`() = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()

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
            durationSeconds = 1,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = true,
            hasActiveNetwork = true,
            onInterval = {}
        )

        val serverRes = serverJob.await()
        assertTrue("Reverse client should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("Reverse server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertEquals("Download (-R)", summary.direction)
        assertTrue(summary.totalBytes > 0L)
        assertTrue(summary.avgBandwidthMbps > 0.0)
    }

    @Test
    fun `loopback client fails cleanly with refused diagnostic when no server is listening`() = runBlocking {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val clientEngine = IPerf3Engine()

        val clientRes = clientEngine.runClient(
            host = "127.0.0.1",
            port = unusedPort,
            durationSeconds = 1,
            parallelStreams = 1,
            connectTimeoutMs = 2000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )

        assertTrue("Connection to closed port must fail, never succeed", clientRes.isFailure)
        val err = clientRes.exceptionOrNull()
        assertTrue(err is IPerfEngineException)
        val details = (err as IPerfEngineException).details
        assertEquals(IPerfFailureCategory.TCP_CONNECTION_REFUSED, details.category)
        assertEquals(IPerfTerminationReason.CONNECTION_FAILURE, details.terminationReason)
        assertEquals("127.0.0.1", details.resolvedIp)
        assertEquals(unusedPort, details.port)
        assertEquals(2000, details.connectTimeoutMs)
    }

    @Test
    fun `client params json matches ESnet iperf3 cJSON types without integer nodelay mismatch`() {
        val uploadDefault = IPerf3Protocol.buildClientParamsJson(
            durationSeconds = 10,
            parallelStreams = 1,
            reverse = false
        )
        assertTrue(uploadDefault.getBoolean("tcp"))
        assertEquals(10, uploadDefault.getInt("time"))
        assertEquals(1, uploadDefault.getInt("parallel"))
        assertEquals(IPerf3Protocol.DEFAULT_TCP_BLOCK_SIZE, uploadDefault.getInt("len"))
        assertFalse("reverse must be omitted when false", uploadDefault.has("reverse"))
        assertFalse("nodelay must be omitted when false", uploadDefault.has("nodelay"))
        assertFalse("window=0 must be omitted", uploadDefault.has("window"))
        assertFalse("MSS=0 must be omitted", uploadDefault.has("MSS"))

        val reverseWithNoDelay = IPerf3Protocol.buildClientParamsJson(
            durationSeconds = 10,
            parallelStreams = 2,
            reverse = true,
            noDelay = true
        )
        assertEquals(true, reverseWithNoDelay.get("reverse"))
        assertTrue(reverseWithNoDelay.get("reverse") is Boolean)
        assertEquals(true, reverseWithNoDelay.get("nodelay"))
        assertTrue("nodelay MUST be JSON boolean true, not integer 1", reverseWithNoDelay.get("nodelay") is Boolean)
    }

    @Test
    fun `loopback 5-second upload sustains transfer across intervals with structured diagnostic logs`() = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()
        val liveIntervals = mutableListOf<IPerfInterval>()

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
            durationSeconds = 5,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = { synchronized(liveIntervals) { liveIntervals.add(it) } }
        )

        val serverRes = serverJob.await()
        assertTrue("5s upload client should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("5s upload server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertFalse(summary.isStalled)
        assertFalse(summary.isInterrupted)
        assertEquals(IPerfTerminationReason.COMPLETED, summary.terminationReason)
        assertTrue("Expected >= 4 intervals for 5s upload, got ${summary.intervals.size}", summary.intervals.size >= 4)
        assertTrue(
            "Every 1s interval in 5s loopback upload must record nonzero transfer",
            summary.intervals.take(4).all { it.bytesTransferred > 0L && it.bandwidthMbps > 0.0 }
        )
        assertTrue(summary.avgBandwidthMbps > 0.0)
        assertTrue(summary.diagnosticLogs.any { it.contains("[CONFIG]") })
        assertTrue(summary.diagnosticLogs.any { it.contains("[CONNECT]") })
        assertTrue(summary.diagnosticLogs.any { it.contains("[TRANSFER_START]") })
        assertTrue(summary.diagnosticLogs.any { it.contains("[INTERVAL]") })
        assertTrue(summary.diagnosticLogs.any { it.contains("[OUTCOME]") && it.contains("COMPLETED") })
    }

    @Test
    fun `loopback 10-second upload sustains continuous throughput without stalling`() = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()

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
            durationSeconds = 10,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )

        val serverRes = serverJob.await()
        assertTrue("10s upload client should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("10s upload server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertFalse(summary.isStalled)
        assertEquals(IPerfTerminationReason.COMPLETED, summary.terminationReason)
        assertTrue("Expected >= 9 intervals for 10s upload, got ${summary.intervals.size}", summary.intervals.size >= 9)
        assertTrue(
            "No zero-byte stall intervals should occur in healthy 10s loopback upload",
            summary.intervals.take(9).all { it.bytesTransferred > 0L }
        )
        assertTrue(summary.avgBandwidthMbps > 0.0)
    }

    @Test
    fun `loopback 10-second reverse download regression test completes cleanly`() = runBlocking {
        val probePort = ServerSocket(0).use { it.localPort }
        val serverEngine = IPerf3Engine()
        val clientEngine = IPerf3Engine()

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
            durationSeconds = 10,
            parallelStreams = 1,
            connectTimeoutMs = 3000,
            reverseMode = true,
            hasActiveNetwork = true,
            onInterval = {}
        )

        val serverRes = serverJob.await()
        assertTrue("10s reverse download should succeed: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        assertTrue("10s reverse server should succeed: ${serverRes.exceptionOrNull()}", serverRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertEquals("Download (-R)", summary.direction)
        assertFalse(summary.isStalled)
        assertEquals(IPerfTerminationReason.COMPLETED, summary.terminationReason)
        assertTrue("Expected >= 9 intervals for 10s download, got ${summary.intervals.size}", summary.intervals.size >= 9)
        assertTrue(summary.avgBandwidthMbps > 0.0)
    }

    @Test
    fun `upload stall watchdog aborts early when peer stops draining socket and reports zero misleading avg throughput`() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val clientEngine = IPerf3Engine()

        // Simulated stalled peer: reads 448 KB initial burst, then leaves data socket open without draining
        val mockServerJob = async(Dispatchers.IO) {
            serverSocket.use { listener ->
                val ctrlSock: Socket = listener.accept()
                val ctrlIn = DataInputStream(ctrlSock.getInputStream())
                val ctrlOut = DataOutputStream(ctrlSock.getOutputStream())
                val cookie = ByteArray(37)
                ctrlIn.readFully(cookie)

                ctrlOut.writeByte(9) // PARAM_EXCHANGE
                ctrlOut.flush()

                val jsonLen = ctrlIn.readInt()
                val jsonBuf = ByteArray(jsonLen)
                ctrlIn.readFully(jsonBuf)

                ctrlOut.writeByte(10) // CREATE_STREAMS
                ctrlOut.flush()

                val dataSock = listener.accept()
                dataSock.receiveBufferSize = 32 * 1024
                val dataIn = DataInputStream(dataSock.getInputStream())
                val dataCookie = ByteArray(37)
                dataIn.readFully(dataCookie)

                ctrlOut.writeByte(1) // TEST_START
                ctrlOut.writeByte(2) // TEST_RUNNING
                ctrlOut.flush()

                // Read a small initial burst (~128 KB), then intentionally stop reading to simulate ZeroWindow stall
                val drainBuf = ByteArray(32 * 1024)
                var drained = 0
                while (drained < 128 * 1024) {
                    val r = dataIn.read(drainBuf)
                    if (r <= 0) break
                    drained += r
                }

                // Wait until client watchdog completes TEST_END (4) or socket closes
                runCatching {
                    ctrlSock.soTimeout = 7000
                    val endByte = ctrlIn.readByte()
                    if (endByte.toInt() == 4) {
                        ctrlOut.writeByte(13) // EXCHANGE_RESULTS
                        ctrlOut.flush()
                    }
                }
                runCatching { dataSock.close() }
                runCatching { ctrlSock.close() }
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
        runCatching { mockServerJob.await() }

        assertTrue("Stalled upload must return summary with stall diagnosis: ${clientRes.exceptionOrNull()}", clientRes.isSuccess)
        val summary = clientRes.getOrThrow()
        assertTrue("Expected stalled flag true", summary.isStalled)
        assertEquals(IPerfTerminationReason.DATA_TRANSFER_STALL, summary.terminationReason)
        assertTrue("Watchdog should abort before full 10s duration (took ${elapsedMs}ms)", elapsedMs < 8000L)
        assertEquals("Stalled transfer must not report misleading nonzero avg throughput", 0.0, summary.avgBandwidthMbps, 0.0001)
        assertEquals("Stalled transfer must not report misleading nonzero peak throughput", 0.0, summary.peakBandwidthMbps, 0.0001)
        assertNotNull(summary.diagnosticNote)
        assertTrue(summary.diagnosticLogs.any { it.contains("STALL_WATCHDOG") || it.contains("DATA_TRANSFER_STALL") })
    }

    @Test
    fun `protocol incompatibility vs invalid host failure reasons are distinctly classified`() = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val clientEngine = IPerf3Engine()

        // Non-iPerf3 HTTP endpoint closes after invalid handshake
        val httpJob = async(Dispatchers.IO) {
            serverSocket.use { listener ->
                val s = listener.accept()
                s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                s.close()
            }
        }

        val protoRes = clientEngine.runClient(
            host = "127.0.0.1",
            port = port,
            durationSeconds = 3,
            parallelStreams = 1,
            connectTimeoutMs = 2000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )
        httpJob.await()

        assertTrue(protoRes.isFailure)
        val protoErr = protoRes.exceptionOrNull() as IPerfEngineException
        assertEquals(IPerfFailureCategory.IPERF3_PROTOCOL_INCOMPATIBILITY, protoErr.details.category)
        assertEquals(IPerfTerminationReason.PROTOCOL_NEGOTIATION_FAILURE, protoErr.details.terminationReason)

        val dnsRes = clientEngine.runClient(
            host = "nonexistent-iperf-server.invalid",
            port = 5201,
            durationSeconds = 3,
            parallelStreams = 1,
            connectTimeoutMs = 2000,
            reverseMode = false,
            hasActiveNetwork = true,
            onInterval = {}
        )
        assertTrue(dnsRes.isFailure)
        val dnsErr = dnsRes.exceptionOrNull() as IPerfEngineException
        assertEquals(IPerfFailureCategory.DNS_RESOLUTION_FAILURE, dnsErr.details.category)
        assertEquals(IPerfTerminationReason.CONNECTION_FAILURE, dnsErr.details.terminationReason)
    }
}
