package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.data.CredentialCryptoManager
import com.example.networktoolkit.data.NetworkNoteDao
import com.example.networktoolkit.data.NetworkNoteEntity
import com.example.networktoolkit.data.NetworkToolkitDatabase
import com.example.networktoolkit.signalmap.FloorPlanTemplates
import com.example.networktoolkit.signalmap.LiveConnectedTelemetry
import com.example.networktoolkit.signalmap.PropertyType
import com.example.networktoolkit.signalmap.SignalMapEngine
import com.example.networktoolkit.signalmap.SignalMapSurveyRepository
import com.example.networktoolkit.signalmap.SignalMapViewModel
import com.example.networktoolkit.signalmap.SignalSample
import com.example.networktoolkit.speedtest.SpeedTestEngine
import com.example.networktoolkit.speedtest.SpeedTestUiState
import com.example.networktoolkit.speedtest.SpeedTestViewModel
import com.example.networktoolkit.speedtest.buildSpeedTestReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase1CorrectiveFixesTest {

    // =========================================================================
    // PRIORITY 1: SPEED TEST ACCURACY & CANCELLATION
    // =========================================================================

    @Test
    fun `speed test stop immediately cancels active HTTP download and invokes onCancelled instead of onError`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val reqLine = reader.readLine().orEmpty()
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) {
                                    line = reader.readLine()
                                }
                                val out: OutputStream = s.getOutputStream()
                                if (reqLine.contains("/ping")) {
                                    val body = "ok".toByteArray()
                                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.write(body)
                                    out.flush()
                                } else if (reqLine.contains("/download")) {
                                    // Advertise large body and drip slowly so stop() interrupts socket
                                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 50000000\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.flush()
                                    val chunk = ByteArray(1024)
                                    while (!server.isClosed) {
                                        out.write(chunk)
                                        out.flush()
                                        Thread.sleep(150)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val engine = SpeedTestEngine(
                scope = scope,
                latencyEndpoints = listOf("http://127.0.0.1:$port/ping"),
                downloadEndpoints = listOf("http://127.0.0.1:$port/download?bytes=10000000"),
                uploadEndpoints = listOf("http://127.0.0.1:$port/upload")
            )

            val cancelledLatch = CountDownLatch(1)
            val errorRef = AtomicReference<String?>(null)
            val downloadStartedLatch = CountDownLatch(1)

            engine.startTest(object : SpeedTestEngine.Callback {
                override fun onPhaseChanged(phase: SpeedTestEngine.TestPhase) {
                    if (phase == SpeedTestEngine.TestPhase.DOWNLOAD) {
                        downloadStartedLatch.countDown()
                    }
                }
                override fun onLatencyMeasured(latencyMs: Double, jitterMs: Double) {}
                override fun onDownloadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onUploadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onCompleted(result: SpeedTestEngine.SpeedTestResult) {}
                override fun onCancelled() {
                    cancelledLatch.countDown()
                }
                override fun onError(errorMessage: String) {
                    errorRef.set(errorMessage)
                    cancelledLatch.countDown()
                }
            })

            var waitIterations = 0
            while (downloadStartedLatch.count > 0 && waitIterations < 60) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                waitIterations++
            }
            assertTrue("Download phase should begin promptly", downloadStartedLatch.count == 0L)
            Thread.sleep(80)
            engine.stop()

            waitIterations = 0
            while (cancelledLatch.count > 0 && waitIterations < 60) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                waitIterations++
            }
            assertTrue("onCancelled should be invoked quickly after stop()", cancelledLatch.count == 0L)
            assertNull("stop() must never trigger onError(): got ${errorRef.get()}", errorRef.get())
            assertFalse(engine.isRunning)
            scope.cancel()
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `speed test viewmodel ignores stale callbacks after stopSpeedTest and transitions cleanly to Idle`() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val reqLine = reader.readLine().orEmpty()
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) {
                                    line = reader.readLine()
                                }
                                val out: OutputStream = s.getOutputStream()
                                if (reqLine.contains("/ping")) {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                    out.flush()
                                } else {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 50000000\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.flush()
                                    val chunk = ByteArray(1024)
                                    while (!server.isClosed) {
                                        out.write(chunk)
                                        out.flush()
                                        Thread.sleep(150)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SpeedTestViewModel(
                            engineFactory = { vmScope ->
                                SpeedTestEngine(
                                    scope = vmScope,
                                    latencyEndpoints = listOf("http://127.0.0.1:$port/ping"),
                                    downloadEndpoints = listOf("http://127.0.0.1:$port/download"),
                                    uploadEndpoints = listOf("http://127.0.0.1:$port/upload")
                                )
                            }
                        ) as T
                    }
                }
            )[SpeedTestViewModel::class.java]
            vm.startSpeedTest()
            assertTrue(vm.uiState.value is SpeedTestUiState.Running)

            Thread.sleep(80)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.stopSpeedTest()
            assertTrue(vm.uiState.value is SpeedTestUiState.Idle)

            // Pump main looper after stop to confirm cancelled/stale async callbacks never overwrite Idle with Error
            Thread.sleep(150)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value is SpeedTestUiState.Idle)
            assertFalse(vm.uiState.value is SpeedTestUiState.Error)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching { server.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun `speed test upload returning HTTP 500 discards unconfirmed buffered bytes and marks upload unavailable`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val reqLine = reader.readLine().orEmpty()
                                var contentLength = 0
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) {
                                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                    }
                                    line = reader.readLine()
                                }
                                if (contentLength > 0) {
                                    val buf = CharArray(8192)
                                    var remaining = contentLength
                                    while (remaining > 0) {
                                        val r = reader.read(buf, 0, minOf(buf.size, remaining))
                                        if (r <= 0) break
                                        remaining -= r
                                    }
                                }
                                val out = s.getOutputStream()
                                when {
                                    reqLine.contains("/ping") -> {
                                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                    }
                                    reqLine.contains("/download") -> {
                                        val payload = ByteArray(131072) { 0x41 }
                                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.write(payload)
                                    }
                                    reqLine.contains("/upload") -> {
                                        // Server rejects upload payload with 500
                                        out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 4\r\nConnection: close\r\n\r\nfail".toByteArray())
                                    }
                                }
                                out.flush()
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val engine = SpeedTestEngine(
                scope = scope,
                latencyEndpoints = listOf("http://127.0.0.1:$port/ping"),
                downloadEndpoints = listOf("http://127.0.0.1:$port/download"),
                uploadEndpoints = listOf("http://127.0.0.1:$port/upload")
            )
            val resultRef = AtomicReference<SpeedTestEngine.SpeedTestResult?>(null)
            val doneLatch = CountDownLatch(1)

            engine.startTest(object : SpeedTestEngine.Callback {
                override fun onPhaseChanged(phase: SpeedTestEngine.TestPhase) {}
                override fun onLatencyMeasured(latencyMs: Double, jitterMs: Double) {}
                override fun onDownloadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onUploadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onCompleted(result: SpeedTestEngine.SpeedTestResult) {
                    resultRef.set(result)
                    doneLatch.countDown()
                }
                override fun onCancelled() {
                    doneLatch.countDown()
                }
                override fun onError(errorMessage: String) {
                    doneLatch.countDown()
                }
            })

            var uploadWait = 0
            while (doneLatch.count > 0 && uploadWait < 600) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                uploadWait++
            }
            assertTrue(doneLatch.count == 0L)
            val res = resultRef.get()
            assertNotNull("Test should complete with Download metrics intact", res)
            assertEquals(-1.0, res!!.uploadMbps, 0.001)
            assertEquals(0L, res.bytesUploaded)
            assertTrue(res.downloadMbps > 0.0)
            assertTrue(buildSpeedTestReport(res).contains("Single-Stream HTTP"))
            scope.cancel()
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `speed test latency phase pins single healthy endpoint instead of mixing primary and fallback RTTs`() = runBlocking {
        val hitPaths = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val sock = server.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                val reqLine = reader.readLine().orEmpty()
                                val path = reqLine.substringAfter(" ").substringBefore(" ")
                                hitPaths.add(path)
                                var line = reader.readLine()
                                while (!line.isNullOrEmpty()) {
                                    line = reader.readLine()
                                }
                                val out = s.getOutputStream()
                                when {
                                    path.startsWith("/badping") -> {
                                        out.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                    }
                                    path.startsWith("/goodping") -> {
                                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                    }
                                    else -> {
                                        val payload = ByteArray(65536) { 0x42 }
                                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.write(payload)
                                    }
                                }
                                out.flush()
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val engine = SpeedTestEngine(
                scope = scope,
                latencyEndpoints = listOf(
                    "http://127.0.0.1:$port/badping",
                    "http://127.0.0.1:$port/goodping"
                ),
                downloadEndpoints = listOf("http://127.0.0.1:$port/download"),
                uploadEndpoints = listOf("http://127.0.0.1:$port/download")
            )

            val doneLatch = CountDownLatch(1)
            val resultRef = AtomicReference<SpeedTestEngine.SpeedTestResult?>(null)
            engine.startTest(object : SpeedTestEngine.Callback {
                override fun onPhaseChanged(phase: SpeedTestEngine.TestPhase) {}
                override fun onLatencyMeasured(latencyMs: Double, jitterMs: Double) {}
                override fun onDownloadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onUploadProgress(currentMbps: Double, progress: Float, totalBytes: Long) {}
                override fun onCompleted(result: SpeedTestEngine.SpeedTestResult) {
                    resultRef.set(result)
                    doneLatch.countDown()
                }
                override fun onCancelled() { doneLatch.countDown() }
                override fun onError(errorMessage: String) { doneLatch.countDown() }
            })

            var latWait = 0
            while (doneLatch.count > 0 && latWait < 600) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                latWait++
            }
            assertTrue(doneLatch.count == 0L)
            val res = resultRef.get()
            assertNotNull(res)
            assertTrue(res!!.latencyEndpointUsed.contains("/goodping"))
            val badHits = hitPaths.count { it.startsWith("/badping") }
            val goodHits = hitPaths.count { it.startsWith("/goodping") }
            assertEquals("Only warmup should probe failed primary latency endpoint", 1, badHits)
            assertTrue("All 6 pinned latency probes + warmup should hit /goodping cleanly", goodHits >= 7)
            scope.cancel()
        } finally {
            runCatching { server.close() }
        }
    }

    // =========================================================================
    // PRIORITY 2: PING TEST MODE & REACHABILITY ACCURACY
    // =========================================================================

    @Test
    fun `ping socket exception classifier separates connection refused, timeout, dns failure, and unreachable`() {
        val (statusRefused, msgRefused) = CoroutinePingEngine.classifySocketException(
            ConnectException("failed to connect to /127.0.0.1 (port 80): ECONNREFUSED (Connection refused)"),
            12L
        )
        assertEquals(CoroutinePingEngine.ProbeStatus.CONNECTION_REFUSED, statusRefused)
        assertTrue(msgRefused.contains("RST", ignoreCase = true))

        val (statusTimeout, _) = CoroutinePingEngine.classifySocketException(
            SocketTimeoutException("connect timed out"),
            2000L
        )
        assertEquals(CoroutinePingEngine.ProbeStatus.TIMEOUT, statusTimeout)

        val (statusDns, _) = CoroutinePingEngine.classifySocketException(
            UnknownHostException("Unable to resolve host"),
            5L
        )
        assertEquals(CoroutinePingEngine.ProbeStatus.DNS_FAILURE, statusDns)

        val (statusUnreach, _) = CoroutinePingEngine.classifySocketException(
            NoRouteToHostException("No route to host (ENETUNREACH)"),
            8L
        )
        assertEquals(CoroutinePingEngine.ProbeStatus.UNREACHABLE, statusUnreach)
    }

    @Test
    fun `ping engine socket mode distinguishes open tcp port vs closed port RST and zero transmitted shows 100pct loss`() {
        val listener = ServerSocket(0)
        val openPort = listener.localPort
        val acceptThread = Thread {
            try {
                while (!listener.isClosed) {
                    listener.accept().close()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            // 1. Open port socket ping -> TCP/port, connectionRefusedCount == 0
            val openLatch = CountDownLatch(1)
            val openSummary = AtomicReference<CoroutinePingEngine.PingSummary?>(null)
            CoroutinePingEngine(scope = scope, socketProbePort = openPort).startPing(
                target = "127.0.0.1",
                count = 2,
                timeoutSec = 1,
                mode = CoroutinePingEngine.PingMode.SOCKET,
                callback = object : CoroutinePingEngine.PingCallback {
                    override fun onStarted(target: String, count: Int, mode: CoroutinePingEngine.PingMode) {}
                    override fun onDnsResolved(hostname: String, ip: String, dnsTimeMs: Long) {}
                    override fun onPacketReceived(packet: CoroutinePingEngine.PingPacket) {}
                    override fun onProgress(current: Int, total: Int) {}
                    override fun onSummary(summary: CoroutinePingEngine.PingSummary) {
                        openSummary.set(summary)
                    }
                    override fun onError(message: String) {}
                    override fun onCompleted(cancelled: Boolean) {
                        openLatch.countDown()
                    }
                }
            )
            var openWait = 0
            while (openLatch.count > 0 && openWait < 160) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                openWait++
            }
            assertTrue(openLatch.count == 0L)
            val open = openSummary.get()
            assertNotNull(open)
            assertEquals("TCP/$openPort", open!!.protocolUsed)
            assertEquals(2, open.received)
            assertEquals(0.0, open.lossPercentage, 0.01)
            assertEquals(0, open.connectionRefusedCount)

            // 2. Closed port (after closing listener) -> Connection Refused RST counts as host reachable!
            val closedPort = openPort
            listener.close()
            acceptThread.join(500)

            val closedLatch = CountDownLatch(1)
            val closedSummary = AtomicReference<CoroutinePingEngine.PingSummary?>(null)
            CoroutinePingEngine(scope = scope, socketProbePort = closedPort).startPing(
                target = "127.0.0.1",
                count = 2,
                timeoutSec = 1,
                mode = CoroutinePingEngine.PingMode.SOCKET,
                callback = object : CoroutinePingEngine.PingCallback {
                    override fun onStarted(target: String, count: Int, mode: CoroutinePingEngine.PingMode) {}
                    override fun onDnsResolved(hostname: String, ip: String, dnsTimeMs: Long) {}
                    override fun onPacketReceived(packet: CoroutinePingEngine.PingPacket) {}
                    override fun onProgress(current: Int, total: Int) {}
                    override fun onSummary(summary: CoroutinePingEngine.PingSummary) {
                        closedSummary.set(summary)
                    }
                    override fun onError(message: String) {}
                    override fun onCompleted(cancelled: Boolean) {
                        closedLatch.countDown()
                    }
                }
            )
            var closedWait = 0
            while (closedLatch.count > 0 && closedWait < 160) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                closedWait++
            }
            assertTrue(closedLatch.count == 0L)
            val closed = closedSummary.get()
            assertNotNull(closed)
            assertTrue(closed!!.received > 0)
            assertEquals(closed.received, closed.connectionRefusedCount)
            assertTrue(closed.reachabilityNote.contains("RST", ignoreCase = true))

            // 3. Summary calculation with 0 transmitted attempted probes yields 100.0% loss (not 0.0%)
            val engine = CoroutinePingEngine(scope = scope)
            val zeroTxSummary = engine.calculateSummary(
                target = "example.com",
                ip = "192.0.2.1",
                transmitted = 0,
                received = 0,
                rttList = emptyList(),
                totalTimeMs = 1000L,
                protocolUsed = "ICMP",
                wasCancelled = false,
                attempted = true
            )
            assertEquals(100.0, zeroTxSummary.lossPercentage, 0.01)
        } finally {
            runCatching { listener.close() }
            scope.cancel()
        }
    }

    @Test
    fun `ping d3 chart html breaks line and area paths at dropped packets instead of dipping to 0ms`() {
        val html = PingD3LatencyChartView.buildD3ChartHtml()
        assertTrue(html.contains("if (p.success)"))
        assertTrue(html.contains("prevSuccessPoint = null;"))
        assertTrue(html.contains("segmentStartPoint = null;"))
        assertTrue(html.contains("currentAreaSegment = \"\";"))
        assertTrue(html.contains("pathD += (pathD ? \" \" : \"\") + \"M \" + p.x + \" \" + p.y;"))
    }

    // =========================================================================
    // PRIORITY 3: SIGNAL MAPPER DATA INTEGRITY
    // =========================================================================

    @Test
    fun `signal mapper isolates simulated samples from real coverage statistics and removes simulated cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = SignalMapEngine(app)
        val rooms = FloorPlanTemplates.getRoomsForProperty(PropertyType.HOUSE)

        val realSample = SignalSample(
            roomId = rooms[0].id,
            roomName = rooms[0].name,
            relX = 0.2f,
            relY = 0.2f,
            rssiDbm = -54,
            snrDb = 36,
            noiseFloorDbm = -90,
            linkSpeedMbps = 433,
            frequencyMhz = 5180,
            channel = 36,
            bssid = "AA:BB:CC:DD:EE:01",
            ssid = "Field_AP",
            isSimulated = false
        )
        val simDeadZone1 = SignalSample(
            roomId = rooms[1].id,
            roomName = rooms[1].name,
            relX = 0.7f,
            relY = 0.7f,
            rssiDbm = -88,
            snrDb = 6,
            noiseFloorDbm = -94,
            linkSpeedMbps = 6,
            frequencyMhz = 2412,
            channel = 1,
            bssid = "00:1A:2B:3C:4D:5E",
            ssid = "Technician_Field_AP (Demo)",
            isSimulated = true
        )
        val simDeadZone2 = simDeadZone1.copy(roomId = rooms[2].id, roomName = rooms[2].name, rssiDbm = -90)

        val mixedSamples = listOf(realSample, simDeadZone1, simDeadZone2)
        val analysis = engine.calculateCoverageAnalysis(
            propertyType = PropertyType.HOUSE,
            rooms = rooms,
            samples = mixedSamples
        )
        assertEquals(3, analysis.totalSamples)
        assertEquals(1, analysis.realSamplesCount)
        assertEquals(2, analysis.simulatedSamplesCount)
        assertTrue(analysis.hasSimulatedData)
        assertFalse(analysis.isSimulatedOnly)
        assertEquals(-54, analysis.averageDbm)
        assertEquals("Simulated dead-zone samples must not pollute real dead-zone count", 0, analysis.deadZonesCount)

        val telemetry = LiveConnectedTelemetry(isConnected = true, ssid = "Field_AP", bssid = "AA:BB:CC:DD:EE:01", currentRssiDbm = -54)
        val textReport = engine.generateTextReport(PropertyType.HOUSE, rooms, mixedSamples, analysis, telemetry)
        assertTrue(textReport.contains("SIMULATED"))
        assertTrue(textReport.contains("Total Samples      : 3 (Real: 1, Simulated: 2)"))

        val json = JSONObject(engine.generateJsonReport(PropertyType.HOUSE, rooms, mixedSamples, analysis, telemetry))
        assertTrue(json.getBoolean("containsSimulatedData"))
        assertEquals(3, json.getInt("totalSamples"))
        assertEquals(1, json.getInt("realSamplesCount"))
        assertEquals(2, json.getInt("simulatedSamplesCount"))

        // Also verify 1 real + 1 simulated report counts when samples and analysis are paired consistently
        val twoSamples = listOf(realSample, simDeadZone1)
        val twoSampleAnalysis = engine.calculateCoverageAnalysis(PropertyType.HOUSE, rooms, twoSamples)
        val twoSampleText = engine.generateTextReport(PropertyType.HOUSE, rooms, twoSamples, twoSampleAnalysis, telemetry)
        assertTrue(twoSampleText.contains("Real: 1, Simulated: 1"))
        val twoSampleJson = JSONObject(engine.generateJsonReport(PropertyType.HOUSE, rooms, twoSamples, twoSampleAnalysis, telemetry))
        assertEquals(1, twoSampleJson.getInt("realSamplesCount"))
        assertEquals(1, twoSampleJson.getInt("simulatedSamplesCount"))

        // Verify ViewModel demo clear removes only simulated samples and preserves clean state
        SignalMapSurveyRepository(app).clearAllStoredSurveys()
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SignalMapViewModel(app) as T
                    }
                }
            )[SignalMapViewModel::class.java]
            vm.clearAllSamples()
            vm.populateDemoSurvey()
            assertTrue(vm.samples.value.isNotEmpty())
            assertTrue(vm.samples.value.all { it.isSimulated })
            vm.clearSimulatedSamples()
            assertTrue(vm.samples.value.isEmpty())
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `signal map survey repository migrates legacy surveys non-destructively and tags legacy demo samples`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences(SignalMapSurveyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val legacySurveyId = "legacy_office_1"
        val legacyJson = """
            {
              "schemaVersion": 1,
              "surveyId": "$legacySurveyId",
              "propertyType": "OFFICE",
              "propertyName": "Branch HQ",
              "samples": [
                {
                  "id": "real-1",
                  "roomName": "Conference Room",
                  "relX": 0.25,
                  "relY": 0.35,
                  "rssiDbm": -58,
                  "snrDb": 32,
                  "noiseFloorDbm": -90,
                  "linkSpeedMbps": 400,
                  "frequencyMhz": 5180,
                  "channel": 36,
                  "bssid": "AA:11:22:33:44:55",
                  "ssid": "Customer_Corp_WiFi",
                  "timestampMs": 1700000000000,
                  "notes": "Real survey pin"
                },
                {
                  "id": "demo-1",
                  "roomName": "Break Room",
                  "relX": 0.75,
                  "relY": 0.65,
                  "rssiDbm": -82,
                  "snrDb": 12,
                  "noiseFloorDbm": -94,
                  "linkSpeedMbps": 24,
                  "frequencyMhz": 2412,
                  "channel": 1,
                  "bssid": "00:1A:2B:3C:4D:5E",
                  "ssid": "Technician_Field_AP (Demo)",
                  "timestampMs": 1700000000000,
                  "notes": "Demo dead zone"
                }
              ]
            }
        """.trimIndent()
        prefs.edit()
            .putString("survey_doc_$legacySurveyId", legacyJson)
            .putStringSet("saved_survey_ids", setOf(legacySurveyId))
            .putString("active_survey_id", legacySurveyId)
            .commit()

        val repo = SignalMapSurveyRepository(app)
        val loaded = repo.loadActiveSurvey()
        assertNotNull("Legacy active survey should load cleanly", loaded)
        assertEquals(PropertyType.OFFICE, loaded!!.config.propertyType)
        assertEquals("Branch HQ", loaded.config.propertyName)
        assertEquals(2, loaded.samples.size)
        assertFalse(loaded.samples.first { it.id == "real-1" }.isSimulated)
        assertTrue(loaded.samples.first { it.id == "demo-1" }.isSimulated)
    }

    // =========================================================================
    // PRIORITY 4: NETWORK NOTES PRIVACY & CREDENTIAL SECURITY
    // =========================================================================

    @Test
    fun `credential crypto manager encrypts with ENC v1 random IVs, roundtrips, and fails closed on wrong key or tamper`() {
        val key1 = generateTestAesKey()
        val key2 = generateTestAesKey()

        val manager1 = CredentialCryptoManager(object : CredentialCryptoManager.KeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = key1
        })
        val manager2 = CredentialCryptoManager(object : CredentialCryptoManager.KeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = key2
        })

        val secret = "admin / S3cr3t-Core-Switch!"
        val encA = manager1.encrypt(secret) as CredentialCryptoManager.EncryptResult.Success
        val encB = manager1.encrypt(secret) as CredentialCryptoManager.EncryptResult.Success

        assertTrue(encA.ciphertext.startsWith(CredentialCryptoManager.PREFIX_V1))
        assertNotEquals("Random GCM IVs must produce distinct ciphertexts", encA.ciphertext, encB.ciphertext)
        assertFalse(encA.ciphertext.contains("S3cr3t"))

        val dec = manager1.decrypt(encA.ciphertext) as CredentialCryptoManager.DecryptResult.Success
        assertEquals(secret, dec.plaintext)

        val wrongKey = manager2.decrypt(encA.ciphertext)
        assertTrue(wrongKey is CredentialCryptoManager.DecryptResult.Failure)

        val tamperedCipher = encA.ciphertext.dropLast(4) + "AAAA"
        val tamperedRes = manager1.decrypt(tamperedCipher)
        assertTrue(tamperedRes is CredentialCryptoManager.DecryptResult.Failure)

        val failingProviderManager = CredentialCryptoManager(object : CredentialCryptoManager.KeyProvider {
            override fun getOrCreateSecretKey(): SecretKey {
                throw SecurityException("Keystore unavailable")
            }
        })
        assertTrue(failingProviderManager.encrypt("pw") is CredentialCryptoManager.EncryptResult.Failure)
    }

    @Test
    fun `room migration 3 to 4 preserves existing notes non-destructively and defaults isEncrypted to 0`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("migration_test_notes_v3_v4.db")
        dbFile.delete()
        dbFile.parentFile?.mkdirs()

        // Create schema v3 SQLite DB manually with pre-existing ping, traceroute, and notes rows
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ping_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetHost` TEXT NOT NULL,
                `resolvedIp` TEXT NOT NULL,
                `packetsTransmitted` INTEGER NOT NULL,
                `packetsReceived` INTEGER NOT NULL,
                `packetLossPercentage` REAL NOT NULL,
                `minRttMs` REAL NOT NULL,
                `avgRttMs` REAL NOT NULL,
                `maxRttMs` REAL NOT NULL,
                `mdevRttMs` REAL NOT NULL,
                `protocolMode` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `logOutput` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `network_notes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `ipOrHost` TEXT,
                `isPinned` INTEGER NOT NULL,
                `createdTimestamp` INTEGER NOT NULL,
                `modifiedTimestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `traceroute_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetHost` TEXT NOT NULL,
                `targetIp` TEXT NOT NULL,
                `totalHops` INTEGER NOT NULL,
                `isDestinationReached` INTEGER NOT NULL,
                `avgRttMs` REAL,
                `durationMs` INTEGER NOT NULL,
                `hopsJson` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO `network_notes` (`id`, `title`, `content`, `category`, `ipOrHost`, `isPinned`, `createdTimestamp`, `modifiedTimestamp`)
            VALUES (10, 'Legacy Core Creds', 'user=netops pass=TopSecret99', 'Credentials', '10.0.0.1', 1, 1000, 2000)
            """.trimIndent()
        )
        sqlite.version = 3
        sqlite.close()

        val roomDb = Room.databaseBuilder(context, NetworkToolkitDatabase::class.java, dbFile.name)
            .addMigrations(
                NetworkToolkitDatabase.MIGRATION_1_2,
                NetworkToolkitDatabase.MIGRATION_2_3,
                NetworkToolkitDatabase.MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()

        try {
            val migrated = roomDb.networkNoteDao().getNoteById(10L)
            assertNotNull("Legacy v3 row must survive v3->v4 migration intact", migrated)
            assertEquals("Legacy Core Creds", migrated!!.title)
            assertEquals("user=netops pass=TopSecret99", migrated.content)
            assertFalse(migrated.isEncrypted)
        } finally {
            roomDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun `network notes viewmodel encrypts credentials, preserves ciphertext on pin toggle, fails closed, and redacts default exports`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeDao = InMemoryNoteDao()
        val key1 = generateTestAesKey()
        val key2 = generateTestAesKey()
        val crypto1 = CredentialCryptoManager(object : CredentialCryptoManager.KeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = key1
        })

        // Seed a legacy plaintext Credentials note + a General note before creating ViewModel
        fakeDao.insertNote(
            NetworkNoteEntity(
                id = 1L,
                title = "Legacy Router Login",
                content = "root / legacyPass42",
                category = "Credentials",
                ipOrHost = "192.168.1.1",
                isEncrypted = false
            )
        )
        fakeDao.insertNote(
            NetworkNoteEntity(
                id = 2L,
                title = "Patch Panel",
                content = "Drop 14 -> Desk 3",
                category = "Rack / Patch",
                isEncrypted = false
            )
        )

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, fakeDao, crypto1) as T
                    }
                }
            )["valid", NetworkNotesViewModel::class.java]
            vm.migrateLegacyPlaintextCredentials()

            val storedLegacy = fakeDao.getNoteById(1L)!!
            assertTrue("Legacy credential note must be migrated to encrypted storage", storedLegacy.isEncrypted)
            assertTrue(storedLegacy.content.startsWith(CredentialCryptoManager.PREFIX_V1))
            assertFalse(storedLegacy.content.contains("legacyPass42"))

            // Save a new Credentials note and verify plaintext is never persisted to DAO
            vm.saveNote(
                id = 0L,
                title = "Core Firewall",
                content = "fwadmin / UltraSecret!",
                category = "Credentials",
                ipOrHost = "10.1.1.1",
                isPinned = false
            )
            var allStored = fakeDao.getAllNotesOnce()
            repeat(60) {
                if (allStored.any { it.title == "Core Firewall" }) return@repeat
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25)
                allStored = fakeDao.getAllNotesOnce()
            }
            val fwStored = allStored.first { it.title == "Core Firewall" }
            assertTrue(fwStored.isEncrypted)
            assertTrue(fwStored.content.startsWith(CredentialCryptoManager.PREFIX_V1))
            assertFalse(fwStored.content.contains("UltraSecret"))

            // Pin toggle must not overwrite encrypted content in Room with decrypted plaintext
            val uiState = awaitNotesUiState(vm) { it.notes.any { n -> n.title == "Core Firewall" } }
            val decryptedFw = uiState.notes.first { it.title == "Core Firewall" }
            assertEquals("fwadmin / UltraSecret!", decryptedFw.content)
            vm.togglePin(decryptedFw)
            repeat(60) {
                if (fakeDao.getNoteById(fwStored.id)?.isPinned == true) return@repeat
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
            val fwAfterPin = fakeDao.getNoteById(fwStored.id)!!
            assertTrue(fwAfterPin.isPinned)
            assertTrue(fwAfterPin.isEncrypted)
            assertTrue(fwAfterPin.content.startsWith(CredentialCryptoManager.PREFIX_V1))

            // Export defaults to redacted credentials
            val redactedReport = buildNetworkNotesTextReport(uiState.notes, redactCredentials = true)
            assertFalse(redactedReport.contains("UltraSecret"))
            assertFalse(redactedReport.contains("legacyPass42"))
            assertTrue(redactedReport.contains("[CREDENTIAL CONTENT REDACTED]"))
            assertTrue(redactedReport.contains("Drop 14 -> Desk 3"))

            val unredactedReport = buildNetworkNotesTextReport(uiState.notes, redactCredentials = false)
            assertTrue(unredactedReport.contains("UltraSecret"))

            // Wrong Keystore key must fail closed and preserve encrypted DB row instead of erasing
            val crypto2 = CredentialCryptoManager(object : CredentialCryptoManager.KeyProvider {
                override fun getOrCreateSecretKey(): SecretKey = key2
            })
            val vmWrongKey = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return NetworkNotesViewModel(app, fakeDao, crypto2) as T
                    }
                }
            )["wrong", NetworkNotesViewModel::class.java]
            val lockedState = awaitNotesUiState(vmWrongKey) { it.notes.isNotEmpty() }
            assertTrue(lockedState.decryptionErrorNoteIds.contains(fwStored.id))
            val fwStillEncryptedInDao = fakeDao.getNoteById(fwStored.id)!!
            assertEquals(fwAfterPin.content, fwStillEncryptedInDao.content)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `network notes activity enables FLAG_SECURE to shield displayed secrets`() {
        val controller = Robolectric.buildActivity(NetworkNotesActivity::class.java).setup()
        try {
            val flags = controller.get().window.attributes.flags
            assertTrue(
                "NetworkNotesActivity window must enable FLAG_SECURE",
                (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            )
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // PRIORITY 5: DNS LOOKUP ACCURACY & PRIVACY
    // =========================================================================

    @Test
    fun `dns lookup viewmodel avoids automatic lookup on launch and defaults Public DoH opt-in to false`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return DnsLookupViewModel(app) as T
                    }
                }
            )[DnsLookupViewModel::class.java]
            assertTrue(
                "DnsLookupViewModel must start Idle instead of issuing unrequested external DNS queries on init",
                vm.uiState.value is DnsLookupUiState.Idle
            )
            assertFalse("Public DoH fallback must default to false", vm.allowPublicDohFallback.value)
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `dns lookup engine classifies internal hostnames and enforces strict Private DNS privacy`() = runBlocking {
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("nas"))
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("printer.local"))
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("switch.lan"))
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("intranet.internal"))
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("192.168.1.1"))
        assertTrue(DnsLookupEngine.isLocalOrInternalHostname("1.1.168.192.in-addr.arpa"))
        assertFalse(DnsLookupEngine.isLocalOrInternalHostname("cloudflare.com"))
        assertFalse(DnsLookupEngine.isLocalOrInternalHostname("8.8.8.8"))

        // Spin up loopback DoH trap server to verify internal/strict queries NEVER touch DoH
        val dohHits = CopyOnWriteArrayList<String>()
        val trapServer = ServerSocket(0)
        val port = trapServer.localPort
        val serverThread = Thread {
            try {
                while (!trapServer.isClosed) {
                    val sock = trapServer.accept()
                    Thread {
                        try {
                            sock.use { s ->
                                val reqLine = BufferedReader(InputStreamReader(s.getInputStream())).readLine().orEmpty()
                                dohHits.add(reqLine)
                                val json = """{"Status":0,"Answer":[{"type":16,"TTL":123,"data":"v=spf1 -all"}]}"""
                                val bytes = json.toByteArray()
                                s.getOutputStream().write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/dns-json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()
                                )
                                s.getOutputStream().write(bytes)
                                s.getOutputStream().flush()
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val strictEnv = ActiveDnsEnvironment(
            isConnected = true,
            isInternetValidated = true,
            activeTransport = "Wi-Fi",
            networkAssignedDnsServers = listOf("192.168.1.1"),
            vpnDnsServers = emptyList(),
            physicalDnsServers = emptyList(),
            privateDnsInfo = PrivateDnsInfo(
                status = PrivateDnsStatus.HOSTNAME_ACTIVE,
                configuredHostname = "dns.quad9.net",
                detailMessage = "Strict DoT active",
                isExposedByOs = true
            )
        )

        try {
            // 1. Even with Public DoH opted IN, .lan/.internal/single-label MUST NEVER query DoH
            val internalRes = DnsLookupEngine.lookupInternal(
                target = "fileserver.lan",
                queryType = "TXT",
                dnsEnvironment = strictEnv,
                allowPublicDohFallback = true,
                dohEndpoints = listOf("http://127.0.0.1:$port/resolve")
            )
            assertTrue(internalRes.isFailure)
            assertTrue("Internal domain must never hit DoH endpoint", dohHits.isEmpty())

            // 2. With Strict Private DNS and allowPublicDohFallback = false, failed system A lookup must NOT fall back to DoH
            val strictNoFallback = DnsLookupEngine.lookupInternal(
                target = "example.org",
                queryType = "A",
                dnsEnvironment = strictEnv,
                allowPublicDohFallback = false,
                dohEndpoints = listOf("http://127.0.0.1:$port/resolve"),
                forwardResolver = { throw UnknownHostException("Strict DoT offline in unit test") }
            )
            assertTrue(strictNoFallback.isFailure)
            assertTrue("Strict Private DNS without opt-in must never bypass to external DoH", dohHits.isEmpty())

            // 3. When user EXPLICITLY opts into Public DoH for public domain TXT, DoH encodes query & preserves real TTL (not fake 300)
            val optInRes = DnsLookupEngine.lookupInternal(
                target = "example.org",
                queryType = "TXT",
                dnsEnvironment = strictEnv,
                allowPublicDohFallback = true,
                dohEndpoints = listOf("http://127.0.0.1:$port/resolve")
            )
            assertTrue(optInRes.isSuccess)
            val res = optInRes.getOrThrow()
            assertTrue(res.usedPublicDoh)
            assertEquals(123, res.records.first().ttl)
            assertEquals(1, dohHits.size)
            assertTrue(dohHits.first().contains("name=example.org&type=TXT"))
        } finally {
            runCatching { trapServer.close() }
        }
    }

    @Test
    fun `dns lookup engine reports null TTL for system InetAddress records and rejects raw IP pretending to be PTR`() = runBlocking {
        val onlineEnv = ActiveDnsEnvironment(
            isConnected = true,
            isInternetValidated = true,
            activeTransport = "Wi-Fi",
            networkAssignedDnsServers = listOf("192.168.1.1"),
            vpnDnsServers = emptyList(),
            physicalDnsServers = emptyList(),
            privateDnsInfo = PrivateDnsInfo(
                status = PrivateDnsStatus.DISABLED,
                detailMessage = "Off",
                isExposedByOs = true
            )
        )

        // Forward A lookup via InetAddress should have ttl = null (not fabricated 300)
        val mockAddr = InetAddress.getByAddress("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        val fwdRes = DnsLookupEngine.lookupInternal(
            target = "example.com",
            queryType = "A",
            dnsEnvironment = onlineEnv,
            allowPublicDohFallback = false,
            forwardResolver = { arrayOf(mockAddr) }
        )
        assertTrue(fwdRes.isSuccess)
        val aRec = fwdRes.getOrThrow().records.single()
        assertEquals("A", aRec.type)
        assertEquals("93.184.216.34", aRec.value)
        assertNull("System InetAddress records must not fabricate TTL 300", aRec.ttl)

        // Reverse PTR returning raw IP as canonicalHostName must fail PTR query instead of emitting bogus PTR record
        val noPtrAddr = InetAddress.getByAddress("198.51.100.77", byteArrayOf(198.toByte(), 51.toByte(), 100.toByte(), 77.toByte()))
        assertFalse(DnsLookupEngine.isValidReversePtrHostname("198.51.100.77", "198.51.100.77", "198.51.100.77"))
        assertTrue(DnsLookupEngine.isValidReversePtrHostname("dns.google.", "8.8.8.8", "8.8.8.8"))

        val ptrFail = DnsLookupEngine.lookupInternal(
            target = "198.51.100.77",
            queryType = "PTR",
            dnsEnvironment = onlineEnv,
            reverseResolver = { noPtrAddr }
        )
        assertTrue("Missing reverse hostname must fail PTR query instead of pretending IP is hostname", ptrFail.isFailure)
        val err = ptrFail.exceptionOrNull() as DnsLookupException
        assertEquals(DnsFailureCategory.RESOLUTION_FAILED, err.details.category)
        assertTrue(err.details.message.contains("No reverse PTR", ignoreCase = true))
    }

    // =========================================================================
    // TEST HELPERS
    // =========================================================================

    private fun awaitNotesUiState(
        vm: NetworkNotesViewModel,
        predicate: (NetworkNotesUiState) -> Boolean
    ): NetworkNotesUiState {
        var latest = vm.uiState.value
        val collectorJob = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.uiState.collect { /* keep SharingStarted.WhileSubscribed active */ }
        }
        try {
            repeat(120) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                latest = vm.uiState.value
                if (predicate(latest)) return latest
                Thread.sleep(25)
            }
        } finally {
            collectorJob.cancel()
        }
        return latest
    }

    private fun generateTestAesKey(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    private class InMemoryNoteDao : NetworkNoteDao {
        private val flow = MutableStateFlow<List<NetworkNoteEntity>>(emptyList())
        private var nextId = 100L

        override fun getAllNotesFlow(): Flow<List<NetworkNoteEntity>> = flow.map { list ->
            list.sortedWith(compareByDescending<NetworkNoteEntity> { it.isPinned }.thenByDescending { it.modifiedTimestamp })
        }

        override suspend fun getAllNotesOnce(): List<NetworkNoteEntity> = flow.value

        override suspend fun getNoteById(id: Long): NetworkNoteEntity? = flow.value.firstOrNull { it.id == id }

        override fun searchNotesFlow(query: String): Flow<List<NetworkNoteEntity>> = flow

        override fun getNotesByCategoryFlow(category: String): Flow<List<NetworkNoteEntity>> = flow

        override suspend fun insertNote(note: NetworkNoteEntity): Long {
            val assignedId = if (note.id != 0L) note.id else nextId++
            val saved = note.copy(id = assignedId)
            flow.value = flow.value.filterNot { it.id == assignedId } + saved
            return assignedId
        }

        override suspend fun updateNote(note: NetworkNoteEntity) {
            flow.value = flow.value.map { if (it.id == note.id) note else it }
        }

        override suspend fun updatePinStatus(id: Long, isPinned: Boolean, modifiedTimestamp: Long) {
            flow.value = flow.value.map {
                if (it.id == id) it.copy(isPinned = isPinned, modifiedTimestamp = modifiedTimestamp) else it
            }
        }

        override suspend fun deleteNote(note: NetworkNoteEntity) {
            flow.value = flow.value.filterNot { it.id == note.id }
        }

        override suspend fun deleteNoteById(id: Long) {
            flow.value = flow.value.filterNot { it.id == id }
        }
    }
}
