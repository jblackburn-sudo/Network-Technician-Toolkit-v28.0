package com.example.networktoolkit.iperf3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull

data class IPerfInterval(
    val intervalIndex: Int,
    val timeRange: String,
    val bytesTransferred: Long,
    val bandwidthMbps: Double,
    val retransmits: Int = 0
)

data class IPerfSummary(
    val mode: String, // "Client" or "Server"
    val direction: String = "Upload (Send)",
    val targetOrClient: String,
    val resolvedIp: String? = null,
    val port: Int,
    val protocol: String,
    val parallelStreams: Int = 1,
    val durationSeconds: Int,
    val connectTimeoutMs: Int = 5000,
    val totalBytes: Long,
    val avgBandwidthMbps: Double,
    val peakBandwidthMbps: Double,
    val intervals: List<IPerfInterval>,
    val isInterrupted: Boolean = false,
    val isStalled: Boolean = false,
    val diagnosticNote: String? = null,
    val terminationReason: IPerfTerminationReason = IPerfTerminationReason.COMPLETED,
    val diagnosticLogs: List<String> = emptyList()
)

object IPerf3TransferAnalyzer {

    fun calculateBandwidthMbps(bytes: Long, elapsedMs: Long): Double {
        if (bytes <= 0L || elapsedMs <= 0L) return 0.0
        return (bytes * 8.0) / ((elapsedMs / 1000.0) * 1_000_000.0)
    }

    fun detectStallAfterInitialBurst(
        intervals: List<IPerfInterval>,
        reverseMode: Boolean
    ): Boolean {
        if (reverseMode || intervals.size < 3) return false
        val hadInitialWrite = intervals.first().bytesTransferred > 0L
        val trailingZeroIntervals = intervals.takeLast(3).all { it.bytesTransferred == 0L }
        return hadInitialWrite && trailingZeroIntervals
    }

    fun buildDiagnosticNote(
        isInterrupted: Boolean,
        isStalled: Boolean,
        peerTerminated: Boolean,
        reverseMode: Boolean,
        totalBytes: Long,
        resolvedIp: String?,
        errorDetail: String? = null
    ): String? {
        return when {
            isStalled && !reverseMode -> {
                if (totalBytes <= 0L) {
                    "Upload transfer stalled with 0 bytes transmitted to ${resolvedIp ?: "remote host"}. " +
                        "This typically indicates peer backpressure/ZeroWindow, firewall filtering, or an unresponsive receive socket."
                } else {
                    val mb = String.format(Locale.US, "%.2f MB", totalBytes / (1024.0 * 1024.0))
                    "Upload transfer stalled after initial TCP send buffer ($mb); subsequent intervals recorded 0.00 MB. " +
                        "This typically indicates peer backpressure/termination, IPv6 Path MTU blackholing, or carrier/firewall filtering on outbound TCP data (${resolvedIp ?: "remote host"})."
                }
            }
            peerTerminated -> {
                "Remote iPerf3 server terminated the control session early (${errorDetail ?: "SERVER_TERMINATE"})."
            }
            isInterrupted && !errorDetail.isNullOrBlank() -> errorDetail
            isInterrupted -> "Benchmark interrupted before full duration completed; partial telemetry preserved."
            else -> errorDetail
        }
    }
}

internal object IPerf3Protocol {
    const val COOKIE_SIZE = 37
    const val DEFAULT_TCP_BLOCK_SIZE = 131_072
    const val TEST_START: Byte = 1
    const val TEST_RUNNING: Byte = 2
    const val TEST_END: Byte = 4
    const val PARAM_EXCHANGE: Byte = 9
    const val CREATE_STREAMS: Byte = 10
    const val SERVER_TERMINATE: Byte = 11
    const val CLIENT_TERMINATE: Byte = 12
    const val EXCHANGE_RESULTS: Byte = 13
    const val DISPLAY_RESULTS: Byte = 14
    const val IPERF_START: Byte = 15
    const val IPERF_DONE: Byte = 16
    const val ACCESS_DENIED: Byte = -1
    const val SERVER_ERROR: Byte = -2

    private const val COOKIE_CHARS = "abcdefghijklmnopqrstuvwxyz234567"
    private val rng = SecureRandom()

    fun generateCookieBytes(): ByteArray {
        val out = ByteArray(COOKIE_SIZE)
        for (i in 0 until COOKIE_SIZE - 1) {
            out[i] = COOKIE_CHARS[rng.nextInt(COOKIE_CHARS.length)].code.toByte()
        }
        out[COOKIE_SIZE - 1] = 0
        return out
    }

    fun writeJsonFrame(out: DataOutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun readJsonFrame(input: DataInputStream): JSONObject {
        val len = input.readInt()
        if (len <= 0 || len > 1_048_576) {
            throw IPerfProtocolException("Invalid iPerf3 JSON frame length ($len bytes).")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return JSONObject(String(buf, Charsets.UTF_8))
    }

    fun buildClientParamsJson(
        durationSeconds: Int,
        parallelStreams: Int,
        reverse: Boolean,
        noDelay: Boolean = false,
        blockSizeBytes: Int = DEFAULT_TCP_BLOCK_SIZE
    ): JSONObject {
        return JSONObject().apply {
            put("tcp", true)
            put("omit", 0)
            put("time", durationSeconds)
            put("parallel", parallelStreams)
            put("len", blockSizeBytes)
            put("pacing_timer", 1000)
            if (reverse) {
                put("reverse", true)
            }
            if (noDelay) {
                put("nodelay", true)
            }
            put("client_version", "3.12")
        }
    }

    fun buildResultsJson(
        cookie: String,
        totalBytes: Long,
        streamsCount: Int,
        elapsedSeconds: Double = 1.0
    ): JSONObject {
        val streams = JSONArray()
        val perStreamBytes = if (streamsCount > 0) totalBytes / streamsCount else totalBytes
        val safeEndTime = elapsedSeconds.coerceAtLeast(0.01)
        for (i in 1..streamsCount.coerceAtLeast(1)) {
            streams.put(JSONObject().apply {
                put("id", i)
                put("bytes", perStreamBytes)
                put("retransmits", -1)
                put("jitter", 0.0)
                put("errors", 0)
                put("omitted_errors", 0)
                put("packets", 0)
                put("omitted_packets", 0)
                put("start_time", 0.0)
                put("end_time", safeEndTime)
            })
        }
        return JSONObject().apply {
            put("cpu_util_total", 0.0)
            put("cpu_util_user", 0.0)
            put("cpu_util_system", 0.0)
            put("sender_has_retransmits", -1)
            put("streams", streams)
            put("cookie", cookie)
        }
    }
}

class IPerf3Engine {

    companion object {
        const val DEFAULT_SERVER_IDLE_TIMEOUT_MS = 120_000L
        const val UPLOAD_STALL_WATCHDOG_MS = 3500L
    }

    private val isRunning = AtomicBoolean(false)
    private val gracefulStopRequested = AtomicBoolean(false)
    private var activeServerSocket: ServerSocket? = null
    private val activeClientSockets = mutableListOf<Socket>()
    private val activeClientChannels = mutableListOf<SocketChannel>()
    private val activeSelectors = mutableListOf<Selector>()
    private val lastDiagnosticLogs = mutableListOf<String>()

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    fun isRunning(): Boolean = isRunning.get()

    fun getLastDiagnosticLogs(): List<String> = synchronized(lastDiagnosticLogs) {
        lastDiagnosticLogs.toList()
    }

    fun requestGracefulInterrupt() {
        gracefulStopRequested.set(true)
        isRunning.set(false)
        closeActiveClientSockets()
    }

    fun stop() {
        isRunning.set(false)
        try {
            activeServerSocket?.close()
        } catch (_: Exception) {}
        activeServerSocket = null
        closeActiveClientSockets()
    }

    private fun wakeActiveSelectors() {
        synchronized(activeSelectors) {
            for (sel in activeSelectors) {
                runCatching { sel.wakeup() }
            }
        }
    }

    private fun closeActiveClientSockets() {
        synchronized(activeSelectors) {
            for (sel in activeSelectors) {
                runCatching { sel.wakeup() }
                runCatching { sel.close() }
            }
            activeSelectors.clear()
        }
        synchronized(activeClientChannels) {
            for (ch in activeClientChannels) {
                runCatching { ch.close() }
            }
            activeClientChannels.clear()
        }
        synchronized(activeClientSockets) {
            for (s in activeClientSockets) {
                runCatching { s.close() }
            }
            activeClientSockets.clear()
        }
    }

    private fun registerSocket(socket: Socket) {
        synchronized(activeClientSockets) {
            activeClientSockets.add(socket)
        }
    }

    private fun registerChannel(channel: SocketChannel) {
        synchronized(activeClientChannels) {
            activeClientChannels.add(channel)
        }
    }

    private fun registerSelector(selector: Selector) {
        synchronized(activeSelectors) {
            activeSelectors.add(selector)
        }
    }

    private fun unregisterSelector(selector: Selector) {
        synchronized(activeSelectors) {
            activeSelectors.remove(selector)
        }
    }

    /**
     * Executes an iPerf3-compatible TCP benchmark client test against an iPerf3 server.
     */
    suspend fun runClient(
        host: String,
        port: Int,
        durationSeconds: Int,
        parallelStreams: Int,
        connectTimeoutMs: Int = 5000,
        reverseMode: Boolean = false,
        hasActiveNetwork: Boolean = true,
        onStatusUpdate: (String) -> Unit = {},
        onInterval: (IPerfInterval) -> Unit,
        noDelay: Boolean = false
    ): Result<IPerfSummary> = withContext(Dispatchers.IO) {
        if (!isRunning.compareAndSet(false, true)) {
            val details = IPerf3ErrorClassifier.classify(
                IllegalStateException("A benchmark test is already running."),
                host, null, port, connectTimeoutMs
            )
            return@withContext Result.failure(IPerfEngineException(details))
        }
        gracefulStopRequested.set(false)

        synchronized(lastDiagnosticLogs) { lastDiagnosticLogs.clear() }
        val cleanHost = IPerf3Validator.normalizeHostInput(host)
        var resolvedIp: String? = null
        val totalBytesCounter = AtomicLong(0)
        val intervals = mutableListOf<IPerfInterval>()
        var peakBandwidth = 0.0
        var transferStartMs = 0L
        var failurePhase = IPerfTerminationReason.CONNECTION_FAILURE
        val directionLabel = if (reverseMode) "Download (-R)" else "Upload (Send)"

        IPerf3Logger.info(
            lastDiagnosticLogs,
            "CONFIG",
            "host=$cleanHost port=$port direction=$directionLabel duration=${durationSeconds}s streams=$parallelStreams timeout=${connectTimeoutMs}ms noDelay=$noDelay"
        )

        try {
            // 1. Pre-check connectivity for non-loopback hosts
            if (!hasActiveNetwork && !IPerf3Validator.isLoopbackOrLocalHost(cleanHost)) {
                throw NoActiveNetworkException("Device has no active Wi-Fi, Cellular, or Ethernet connection.")
            }

            // 2. Separate DNS resolution step so DNS failures vs TCP connect failures are distinct
            onStatusUpdate("Resolving host $cleanHost...")
            val resolvedAddress = InetAddress.getByName(cleanHost)
            resolvedIp = resolvedAddress.hostAddress ?: cleanHost

            // 3. Open iPerf3 Control Connection using explicit connectTimeoutMs
            onStatusUpdate("Connecting to $resolvedIp:$port (timeout ${connectTimeoutMs} ms)...")
            val controlConnectStart = monotonicMillis()
            val controlSocket = Socket()
            registerSocket(controlSocket)
            controlSocket.tcpNoDelay = true
            controlSocket.soTimeout = connectTimeoutMs.coerceAtLeast(3000)
            controlSocket.connect(InetSocketAddress(resolvedAddress, port), connectTimeoutMs)
            IPerf3Logger.info(
                lastDiagnosticLogs,
                "CONNECT",
                "Control socket connected to $resolvedIp:$port in ${monotonicMillis() - controlConnectStart}ms"
            )

            failurePhase = IPerfTerminationReason.PROTOCOL_NEGOTIATION_FAILURE
            val ctrlIn = DataInputStream(controlSocket.getInputStream())
            val ctrlOut = DataOutputStream(controlSocket.getOutputStream())

            // 4. Send 37-byte iPerf3 session cookie
            val cookie = IPerf3Protocol.generateCookieBytes()
            val cookieStr = String(cookie, 0, IPerf3Protocol.COOKIE_SIZE - 1, Charsets.US_ASCII)
            ctrlOut.write(cookie)
            ctrlOut.flush()

            // 5. Read server handshake byte (expected PARAM_EXCHANGE = 9)
            val initialState = try {
                ctrlIn.readByte()
            } catch (eof: EOFException) {
                throw IPerfProtocolException(
                    "Connection to $resolvedIp:$port closed before completing iPerf3 handshake.",
                    eof
                )
            }

            when (initialState) {
                IPerf3Protocol.PARAM_EXCHANGE -> Unit
                IPerf3Protocol.ACCESS_DENIED -> throw IPerfServerUnavailableException(
                    "iPerf3 server at $resolvedIp:$port is currently busy with another client (ACCESS_DENIED)."
                )
                IPerf3Protocol.SERVER_ERROR -> throw IPerfServerUnavailableException(
                    "iPerf3 server at $resolvedIp:$port reported an internal server error (SERVER_ERROR)."
                )
                else -> throw IPerfProtocolException(
                    "Unexpected protocol byte ($initialState) from $resolvedIp:$port; endpoint is not an iPerf3 server."
                )
            }

            // 6. Exchange test parameters JSON (strictly compliant with ESnet cJSON types)
            onStatusUpdate("Negotiating iPerf3 parameters with $resolvedIp:$port...")
            val paramsJson = IPerf3Protocol.buildClientParamsJson(
                durationSeconds = durationSeconds,
                parallelStreams = parallelStreams,
                reverse = reverseMode,
                noDelay = noDelay
            )
            IPerf3Logger.info(lastDiagnosticLogs, "HANDSHAKE", "Sending params JSON: $paramsJson")
            IPerf3Protocol.writeJsonFrame(ctrlOut, paramsJson)

            // 7. Expect CREATE_STREAMS (10)
            val createStreamsState = try {
                ctrlIn.readByte()
            } catch (eof: EOFException) {
                throw IPerfProtocolException("Server closed connection after parameter exchange.", eof)
            }
            if (createStreamsState == IPerf3Protocol.ACCESS_DENIED) {
                throw IPerfServerUnavailableException("iPerf3 server denied stream creation (busy).")
            }
            if (createStreamsState == IPerf3Protocol.SERVER_ERROR) {
                throw IPerfServerUnavailableException("iPerf3 server failed while preparing test streams.")
            }
            if (createStreamsState != IPerf3Protocol.CREATE_STREAMS) {
                throw IPerfProtocolException("Expected CREATE_STREAMS (10), got $createStreamsState.")
            }
            IPerf3Logger.info(lastDiagnosticLogs, "HANDSHAKE", "Received CREATE_STREAMS; opening $parallelStreams stream(s)")

            // 8. Open parallel data sockets/channels and send session cookie on each
            val dataSockets = mutableListOf<Socket>()
            val uploadChannels = mutableListOf<SocketChannel>()
            if (reverseMode) {
                for (i in 0 until parallelStreams) {
                    val dataSock = Socket()
                    registerSocket(dataSock)
                    dataSock.tcpNoDelay = noDelay
                    dataSock.receiveBufferSize = 256 * 1024
                    dataSock.soTimeout = connectTimeoutMs.coerceAtLeast(4000)
                    dataSock.connect(InetSocketAddress(resolvedAddress, port), connectTimeoutMs)
                    val dataOut = dataSock.getOutputStream()
                    dataOut.write(cookie)
                    dataOut.flush()
                    dataSockets.add(dataSock)
                }
            } else {
                for (i in 0 until parallelStreams) {
                    val channel = SocketChannel.open()
                    registerChannel(channel)
                    channel.configureBlocking(true)
                    val dataSock = channel.socket()
                    registerSocket(dataSock)
                    dataSock.tcpNoDelay = noDelay
                    dataSock.soTimeout = connectTimeoutMs.coerceAtLeast(4000)
                    // Allow Linux/Android kernel TCP auto-tuning (tcp_wmem) instead of clamping SO_SNDBUF
                    dataSock.connect(InetSocketAddress(resolvedAddress, port), connectTimeoutMs)
                    val cookieBuf = ByteBuffer.wrap(cookie)
                    while (cookieBuf.hasRemaining()) {
                        channel.write(cookieBuf)
                    }
                    uploadChannels.add(channel)
                    dataSockets.add(dataSock)
                }
            }

            // 9. Wait for TEST_START (1) and TEST_RUNNING (2)
            var runningReached = false
            while (isRunning.get() && !runningReached) {
                val stateByte = try {
                    ctrlIn.readByte()
                } catch (eof: EOFException) {
                    throw IPerfProtocolException("Control connection ended before TEST_RUNNING.", eof)
                }
                when (stateByte) {
                    IPerf3Protocol.TEST_START -> onStatusUpdate("Server initialized streams; starting test...")
                    IPerf3Protocol.TEST_RUNNING -> runningReached = true
                    IPerf3Protocol.ACCESS_DENIED -> throw IPerfServerUnavailableException("Server busy (ACCESS_DENIED).")
                    IPerf3Protocol.SERVER_ERROR -> throw IPerfServerUnavailableException("Server aborted test (SERVER_ERROR).")
                    IPerf3Protocol.SERVER_TERMINATE -> throw IPerfServerUnavailableException("Server terminated session.")
                    else -> throw IPerfProtocolException("Unexpected control state ($stateByte) before TEST_RUNNING.")
                }
            }

            // 10. Execute parallel data transfer over configured durationSeconds
            failurePhase = IPerfTerminationReason.PREMATURE_TERMINATION
            val startTime = monotonicMillis()
            transferStartMs = startTime
            val transferActive = AtomicBoolean(true)
            val peerTerminated = AtomicBoolean(false)
            val peerTerminatedReason = AtomicReference<String?>(null)
            val workerFailure = AtomicReference<Throwable?>(null)
            val lastWriteProgressMs = AtomicLong(startTime)
            val stallWatchdogTriggered = AtomicBoolean(false)

            IPerf3Logger.info(
                lastDiagnosticLogs,
                "TRANSFER_START",
                "Started $directionLabel transfer to $resolvedIp:$port mode=${if (reverseMode) "blocking-read" else "nonblocking-SocketChannel"}"
            )

            // Monitor control channel during transfer so SERVER_TERMINATE / SERVER_ERROR / unexpected EOF
            // stops stalled tests immediately; short soTimeout ensures clean join before TEST_END.
            runCatching { controlSocket.soTimeout = 200 }
            val controlMonitor = launch(Dispatchers.IO) {
                while (isRunning.get() && transferActive.get()) {
                    try {
                        val b = ctrlIn.readByte()
                        when (b) {
                            IPerf3Protocol.SERVER_TERMINATE -> {
                                peerTerminatedReason.set("SERVER_TERMINATE")
                                peerTerminated.set(true)
                                transferActive.set(false)
                                wakeActiveSelectors()
                                IPerf3Logger.warn(lastDiagnosticLogs, "CONTROL", "Received SERVER_TERMINATE from peer")
                                break
                            }
                            IPerf3Protocol.SERVER_ERROR -> {
                                peerTerminatedReason.set("SERVER_ERROR")
                                peerTerminated.set(true)
                                transferActive.set(false)
                                wakeActiveSelectors()
                                IPerf3Logger.warn(lastDiagnosticLogs, "CONTROL", "Received SERVER_ERROR from peer")
                                break
                            }
                            IPerf3Protocol.ACCESS_DENIED -> {
                                peerTerminatedReason.set("ACCESS_DENIED")
                                peerTerminated.set(true)
                                transferActive.set(false)
                                wakeActiveSelectors()
                                IPerf3Logger.warn(lastDiagnosticLogs, "CONTROL", "Received ACCESS_DENIED from peer")
                                break
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Poll interval check
                    } catch (_: EOFException) {
                        if (isRunning.get() && transferActive.get()) {
                            peerTerminatedReason.set("Control channel closed by server")
                            peerTerminated.set(true)
                            transferActive.set(false)
                            wakeActiveSelectors()
                            IPerf3Logger.warn(lastDiagnosticLogs, "CONTROL", "Control channel EOF during transfer")
                        }
                        break
                    } catch (e: Exception) {
                        if (isRunning.get() && transferActive.get() && !gracefulStopRequested.get()) {
                            peerTerminatedReason.compareAndSet(null, e.message ?: e::class.java.simpleName)
                            transferActive.set(false)
                            wakeActiveSelectors()
                            IPerf3Logger.warn(lastDiagnosticLogs, "CONTROL", "Control monitor exited: ${e.message}")
                        }
                        break
                    }
                }
            }

            val workerJobs = if (reverseMode) {
                dataSockets.map { dataSock ->
                    launch(Dispatchers.IO) {
                        val buf = ByteArray(64 * 1024)
                        val input = dataSock.getInputStream()
                        while (isRunning.get() && transferActive.get() && (monotonicMillis() - startTime) < durationSeconds * 1000L) {
                            val n = try {
                                input.read(buf)
                            } catch (e: Exception) {
                                if (isRunning.get() && transferActive.get() && !gracefulStopRequested.get()) {
                                    workerFailure.compareAndSet(null, e)
                                }
                                -1
                            }
                            if (n <= 0) break
                            totalBytesCounter.addAndGet(n.toLong())
                        }
                    }
                }
            } else {
                uploadChannels.map { channel ->
                    launch(Dispatchers.IO) {
                        val selector = Selector.open()
                        registerSelector(selector)
                        val buf = ByteBuffer.allocate(IPerf3Protocol.DEFAULT_TCP_BLOCK_SIZE).apply {
                            for (i in 0 until capacity()) {
                                put(0x55.toByte())
                            }
                            clear()
                        }
                        try {
                            channel.configureBlocking(false)
                            channel.register(selector, SelectionKey.OP_WRITE)
                            while (isRunning.get() && transferActive.get() && (monotonicMillis() - startTime) < durationSeconds * 1000L) {
                                if (!buf.hasRemaining()) {
                                    buf.clear()
                                }
                                val written = try {
                                    channel.write(buf)
                                } catch (e: Exception) {
                                    if (isRunning.get() && transferActive.get() && !gracefulStopRequested.get()) {
                                        workerFailure.compareAndSet(null, e)
                                        IPerf3Logger.warn(lastDiagnosticLogs, "UPLOAD_STREAM", "Write failed: ${e.message}")
                                    }
                                    break
                                }
                                if (written > 0) {
                                    totalBytesCounter.addAndGet(written.toLong())
                                    lastWriteProgressMs.set(monotonicMillis())
                                } else {
                                    // OS send buffer temporarily full; wait via Selector without uninterruptible write block
                                    val selected = try {
                                        selector.select(150L)
                                    } catch (_: Exception) {
                                        break
                                    }
                                    if (selected > 0) {
                                        selector.selectedKeys().clear()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning.get() && transferActive.get() && !gracefulStopRequested.get()) {
                                workerFailure.compareAndSet(null, e)
                                IPerf3Logger.warn(lastDiagnosticLogs, "UPLOAD_STREAM", "Selector error: ${e.message}")
                            }
                        } finally {
                            unregisterSelector(selector)
                            runCatching { selector.close() }
                        }
                    }
                }
            }

            // Track 1-second intervals cleanly and enforce active upload stall watchdog
            var lastIntervalTime = startTime
            var lastBytes = 0L
            var intervalIndex = 1
            while (isRunning.get() && transferActive.get()) {
                val now = monotonicMillis()
                val elapsedMs = now - startTime
                if (elapsedMs >= durationSeconds * 1000L) break

                // Stop loop promptly if all workers exited prematurely (e.g., broken pipe / peer closed)
                if (workerJobs.all { !it.isActive } && !peerTerminated.get()) {
                    break
                }

                val sleepMs = (1000L - (now - lastIntervalTime)).coerceIn(50L, 250L)
                kotlinx.coroutines.delay(sleepMs)

                val tickNow = monotonicMillis()
                val intervalElapsed = tickNow - lastIntervalTime
                if (intervalElapsed >= 1000L) {
                    val currentTotalBytes = totalBytesCounter.get()
                    val intervalBytes = (currentTotalBytes - lastBytes).coerceAtLeast(0L)
                    val intervalMbps = IPerf3TransferAnalyzer.calculateBandwidthMbps(intervalBytes, intervalElapsed)

                    val startSecStr = String.format(Locale.US, "%.1f", (lastIntervalTime - startTime) / 1000.0)
                    val endSecStr = String.format(Locale.US, "%.1f", (tickNow - startTime) / 1000.0)

                    val item = IPerfInterval(
                        intervalIndex = intervalIndex++,
                        timeRange = "$startSecStr - $endSecStr sec",
                        bytesTransferred = intervalBytes,
                        bandwidthMbps = intervalMbps
                    )
                    intervals.add(item)
                    if (intervalMbps > peakBandwidth) peakBandwidth = intervalMbps
                    IPerf3Logger.info(
                        lastDiagnosticLogs,
                        "INTERVAL",
                        "[${item.timeRange}] bytes=$intervalBytes mbps=${String.format(Locale.US, "%.2f", intervalMbps)}"
                    )
                    onInterval(item)

                    lastIntervalTime = tickNow
                    lastBytes = currentTotalBytes
                }

                // Active stall detection for upload mode: abort early if link stalls for >= 3.5s
                if (!reverseMode && (tickNow - startTime) >= UPLOAD_STALL_WATCHDOG_MS) {
                    val stalledDurationMs = tickNow - lastWriteProgressMs.get()
                    val zeroTotalStall = totalBytesCounter.get() == 0L && stalledDurationMs >= UPLOAD_STALL_WATCHDOG_MS
                    val trailingBurstStall = intervals.size >= 3 &&
                        intervals.takeLast(3).all { it.bytesTransferred == 0L } &&
                        stalledDurationMs >= UPLOAD_STALL_WATCHDOG_MS
                    if (zeroTotalStall || trailingBurstStall) {
                        stallWatchdogTriggered.set(true)
                        transferActive.set(false)
                        wakeActiveSelectors()
                        IPerf3Logger.warn(
                            lastDiagnosticLogs,
                            "STALL_WATCHDOG",
                            "Upload stalled for ${stalledDurationMs}ms at totalBytes=${totalBytesCounter.get()}; aborting remainder of ${durationSeconds}s test"
                        )
                        break
                    }
                }
            }

            // Signal transfer stop and let non-blocking upload workers & control monitor exit cleanly before TEST_END
            transferActive.set(false)
            isRunning.set(false)
            wakeActiveSelectors()
            withTimeoutOrNull(350L) {
                controlMonitor.join()
            }
            controlMonitor.cancel()

            val closeStreamsEarly = reverseMode || gracefulStopRequested.get() || peerTerminated.get() || stallWatchdogTriggered.get()
            if (closeStreamsEarly) {
                dataSockets.forEach { ds -> runCatching { ds.close() } }
                uploadChannels.forEach { ch -> runCatching { ch.close() } }
            }
            withTimeoutOrNull(450L) {
                workerJobs.forEach { it.join() }
            }
            workerJobs.forEach { it.cancel() }

            val finalElapsedMs = (monotonicMillis() - startTime).coerceAtLeast(1L)
            val finalTotalBytes = totalBytesCounter.get()

            // If zero bytes were ever transferred and a real worker socket exception occurred without user stop, surface the error cleanly
            val err = workerFailure.get()
            if (finalTotalBytes == 0L && err != null && !gracefulStopRequested.get() && !peerTerminated.get() && !stallWatchdogTriggered.get()) {
                throw err
            }

            // Emit final partial interval if meaningful bytes or partial window remains
            val tailMs = (monotonicMillis() - lastIntervalTime).coerceAtLeast(50L)
            if (finalTotalBytes > lastBytes && tailMs >= 50L) {
                val tailBytes = finalTotalBytes - lastBytes
                val tailMbps = IPerf3TransferAnalyzer.calculateBandwidthMbps(tailBytes, tailMs.coerceAtLeast(100L))
                val item = IPerfInterval(
                    intervalIndex = intervalIndex,
                    timeRange = String.format(
                        Locale.US,
                        "%.1f - %.1f sec",
                        (lastIntervalTime - startTime) / 1000.0,
                        finalElapsedMs / 1000.0
                    ),
                    bytesTransferred = tailBytes,
                    bandwidthMbps = tailMbps
                )
                intervals.add(item)
                if (tailMbps > peakBandwidth) peakBandwidth = tailMbps
                IPerf3Logger.info(
                    lastDiagnosticLogs,
                    "INTERVAL",
                    "[${item.timeRange}] bytes=$tailBytes mbps=${String.format(Locale.US, "%.2f", tailMbps)}"
                )
                onInterval(item)
            }

            // 11. Graceful iPerf3 TEST_END handshake (best effort so server clears cleanly)
            if (!gracefulStopRequested.get() && !peerTerminated.get() && !controlSocket.isClosed) {
                try {
                    ctrlOut.writeByte(IPerf3Protocol.TEST_END.toInt())
                    ctrlOut.flush()
                    controlSocket.soTimeout = 1500
                    val nextByte = ctrlIn.readByte()
                    if (nextByte == IPerf3Protocol.EXCHANGE_RESULTS) {
                        IPerf3Protocol.writeJsonFrame(
                            ctrlOut,
                            IPerf3Protocol.buildResultsJson(
                                cookie = cookieStr,
                                totalBytes = finalTotalBytes,
                                streamsCount = parallelStreams,
                                elapsedSeconds = finalElapsedMs / 1000.0
                            )
                        )
                        runCatching { IPerf3Protocol.readJsonFrame(ctrlIn) }
                        runCatching { ctrlIn.readByte() } // Optional DISPLAY_RESULTS
                    }
                    ctrlOut.writeByte(IPerf3Protocol.IPERF_DONE.toInt())
                    ctrlOut.flush()
                } catch (_: Exception) {
                    // Transfer already measured accurately; teardown errors shouldn't discard valid results
                }
            }

            if (!closeStreamsEarly) {
                uploadChannels.forEach { ch -> runCatching { ch.close() } }
                dataSockets.forEach { ds -> runCatching { ds.close() } }
            }

            val wasInterrupted = gracefulStopRequested.get() || peerTerminated.get() || err != null
            val isStalled = !gracefulStopRequested.get() && !peerTerminated.get() && (
                stallWatchdogTriggered.get() ||
                    IPerf3TransferAnalyzer.detectStallAfterInitialBurst(intervals, reverseMode) ||
                    (!reverseMode && finalTotalBytes == 0L)
                )
            val streamErrMsg = err?.let { "Data stream closed early (${it.message ?: it::class.java.simpleName})." }
            val diagnosticNote = IPerf3TransferAnalyzer.buildDiagnosticNote(
                isInterrupted = wasInterrupted,
                isStalled = isStalled,
                peerTerminated = peerTerminated.get(),
                reverseMode = reverseMode,
                totalBytes = finalTotalBytes,
                resolvedIp = resolvedIp,
                errorDetail = peerTerminatedReason.get() ?: streamErrMsg
            )

            val terminationReason = when {
                gracefulStopRequested.get() -> IPerfTerminationReason.USER_CANCELLED
                peerTerminated.get() || err != null -> IPerfTerminationReason.PREMATURE_TERMINATION
                isStalled -> IPerfTerminationReason.DATA_TRANSFER_STALL
                else -> IPerfTerminationReason.COMPLETED
            }

            // Never report misleading nonzero summary throughput when a transfer stalled
            val avgMbps = if (isStalled) 0.0 else IPerf3TransferAnalyzer.calculateBandwidthMbps(finalTotalBytes, finalElapsedMs)
            val reportedPeakMbps = if (isStalled) 0.0 else peakBandwidth.coerceAtLeast(avgMbps)

            IPerf3Logger.info(
                lastDiagnosticLogs,
                "OUTCOME",
                "reason=${terminationReason.name} totalBytes=$finalTotalBytes avgMbps=${String.format(Locale.US, "%.2f", avgMbps)} elapsedMs=$finalElapsedMs"
            )

            val summary = IPerfSummary(
                mode = "Client",
                direction = directionLabel,
                targetOrClient = "$cleanHost:$port",
                resolvedIp = resolvedIp,
                port = port,
                protocol = "TCP",
                parallelStreams = parallelStreams,
                durationSeconds = durationSeconds,
                connectTimeoutMs = connectTimeoutMs,
                totalBytes = finalTotalBytes,
                avgBandwidthMbps = avgMbps,
                peakBandwidthMbps = reportedPeakMbps,
                intervals = intervals,
                isInterrupted = wasInterrupted,
                isStalled = isStalled,
                diagnosticNote = diagnosticNote,
                terminationReason = terminationReason,
                diagnosticLogs = getLastDiagnosticLogs()
            )
            Result.success(summary)
        } catch (e: CancellationException) {
            if (gracefulStopRequested.get()) {
                val partialTotal = totalBytesCounter.get()
                val elapsedMs = if (transferStartMs > 0L) (monotonicMillis() - transferStartMs).coerceAtLeast(1L) else 0L
                val avgMbps = if (elapsedMs > 0L) IPerf3TransferAnalyzer.calculateBandwidthMbps(partialTotal, elapsedMs) else 0.0
                IPerf3Logger.info(
                    lastDiagnosticLogs,
                    "OUTCOME",
                    "reason=USER_CANCELLED totalBytes=$partialTotal avgMbps=${String.format(Locale.US, "%.2f", avgMbps)}"
                )
                Result.success(
                    IPerfSummary(
                        mode = "Client",
                        direction = directionLabel,
                        targetOrClient = "$cleanHost:$port",
                        resolvedIp = resolvedIp,
                        port = port,
                        protocol = "TCP",
                        parallelStreams = parallelStreams,
                        durationSeconds = durationSeconds,
                        connectTimeoutMs = connectTimeoutMs,
                        totalBytes = partialTotal,
                        avgBandwidthMbps = avgMbps,
                        peakBandwidthMbps = peakBandwidth.coerceAtLeast(avgMbps),
                        intervals = intervals.toList(),
                        isInterrupted = true,
                        isStalled = false,
                        diagnosticNote = "Benchmark interrupted by technician.",
                        terminationReason = IPerfTerminationReason.USER_CANCELLED,
                        diagnosticLogs = getLastDiagnosticLogs()
                    )
                )
            } else {
                IPerf3Logger.warn(lastDiagnosticLogs, "OUTCOME", "reason=USER_CANCELLED (coroutine cancelled)")
                Result.failure(e)
            }
        } catch (e: Throwable) {
            if (gracefulStopRequested.get()) {
                val partialTotal = totalBytesCounter.get()
                val elapsedMs = if (transferStartMs > 0L) (monotonicMillis() - transferStartMs).coerceAtLeast(1L) else 0L
                val avgMbps = if (elapsedMs > 0L) IPerf3TransferAnalyzer.calculateBandwidthMbps(partialTotal, elapsedMs) else 0.0
                IPerf3Logger.info(
                    lastDiagnosticLogs,
                    "OUTCOME",
                    "reason=USER_CANCELLED totalBytes=$partialTotal avgMbps=${String.format(Locale.US, "%.2f", avgMbps)}"
                )
                Result.success(
                    IPerfSummary(
                        mode = "Client",
                        direction = directionLabel,
                        targetOrClient = "$cleanHost:$port",
                        resolvedIp = resolvedIp,
                        port = port,
                        protocol = "TCP",
                        parallelStreams = parallelStreams,
                        durationSeconds = durationSeconds,
                        connectTimeoutMs = connectTimeoutMs,
                        totalBytes = partialTotal,
                        avgBandwidthMbps = avgMbps,
                        peakBandwidthMbps = peakBandwidth.coerceAtLeast(avgMbps),
                        intervals = intervals.toList(),
                        isInterrupted = true,
                        isStalled = false,
                        diagnosticNote = "Benchmark interrupted by technician.",
                        terminationReason = IPerfTerminationReason.USER_CANCELLED,
                        diagnosticLogs = getLastDiagnosticLogs()
                    )
                )
            } else {
                val baseDetails = IPerf3ErrorClassifier.classify(
                    throwable = e,
                    targetHost = cleanHost,
                    resolvedIp = resolvedIp,
                    port = port,
                    connectTimeoutMs = connectTimeoutMs
                )
                val resolvedReason = if (failurePhase == IPerfTerminationReason.PROTOCOL_NEGOTIATION_FAILURE &&
                    baseDetails.category == IPerfFailureCategory.SERVER_UNAVAILABLE
                ) {
                    IPerfTerminationReason.PROTOCOL_NEGOTIATION_FAILURE
                } else {
                    baseDetails.terminationReason
                }
                val details = baseDetails.copy(terminationReason = resolvedReason)
                IPerf3Logger.error(
                    lastDiagnosticLogs,
                    "OUTCOME",
                    "reason=${details.terminationReason.name} category=${details.category.name} message=${details.summaryMessage}",
                    e
                )
                Result.failure(IPerfEngineException(details, e))
            }
        } finally {
            stop()
        }
    }

    /**
     * Executes an iPerf3-compatible TCP server listening on [port].
     * Supports standard `iperf3 -c` clients (via ESnet iPerf3 control handshake) as well as raw TCP streams.
     */
    suspend fun runServer(
        port: Int = 5201,
        idleTimeoutMs: Long = DEFAULT_SERVER_IDLE_TIMEOUT_MS,
        onClientConnected: (String) -> Unit,
        onInterval: (IPerfInterval) -> Unit
    ): Result<IPerfSummary> = withContext(Dispatchers.IO) {
        if (!isRunning.compareAndSet(false, true)) {
            val details = IPerf3ErrorClassifier.classify(
                IllegalStateException("A benchmark test is already running."),
                "Local Server", null, port, 0
            )
            return@withContext Result.failure(IPerfEngineException(details))
        }

        val intervals = mutableListOf<IPerfInterval>()
        val totalBytesCounter = AtomicLong(0)
        var clientAddress = "Unknown Client"
        var peakBandwidth = 0.0
        var reportedDurationSec = 0
        var streamCount = 1
        var reverseRequested = false

        try {
            val server = ServerSocket(port)
            activeServerSocket = server
            val boundedTimeoutMs = idleTimeoutMs.coerceIn(100L, 600_000L)
            server.soTimeout = boundedTimeoutMs.toInt()

            val firstSocket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                throw IPerfServerIdleTimeoutException(
                    "Local iPerf3 server automatically stopped after ${boundedTimeoutMs / 1000L}s with no client connection."
                )
            }
            server.soTimeout = 10_000
            registerSocket(firstSocket)
            clientAddress = firstSocket.inetAddress?.hostAddress ?: firstSocket.remoteSocketAddress.toString()
            onClientConnected(clientAddress)

            val firstIn = DataInputStream(firstSocket.getInputStream())
            val firstOut = DataOutputStream(firstSocket.getOutputStream())

            // Peek/read initial 37 bytes to check if client sent a standard iPerf3 null-terminated ASCII cookie
            val headerProbe = ByteArray(IPerf3Protocol.COOKIE_SIZE)
            firstIn.readFully(headerProbe)
            val isStandardIperf3Cookie = headerProbe[IPerf3Protocol.COOKIE_SIZE - 1] == 0.toByte() &&
                headerProbe.slice(0 until IPerf3Protocol.COOKIE_SIZE - 1).all { b ->
                    val c = b.toInt().toChar()
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
                }

            val startTime: Long
            var lastIntervalTime: Long
            var lastBytes = 0L
            var intervalIndex = 1

            if (isStandardIperf3Cookie) {
                val cookieStr = String(headerProbe, 0, IPerf3Protocol.COOKIE_SIZE - 1, Charsets.US_ASCII)
                firstOut.writeByte(IPerf3Protocol.PARAM_EXCHANGE.toInt())
                firstOut.flush()

                val params = IPerf3Protocol.readJsonFrame(firstIn)
                streamCount = params.optInt("parallel", 1).coerceIn(1, 8)
                reportedDurationSec = params.optInt("time", 10).coerceIn(1, 300)
                reverseRequested = params.optBoolean("reverse", false)

                firstOut.writeByte(IPerf3Protocol.CREATE_STREAMS.toInt())
                firstOut.flush()

                val dataSockets = mutableListOf<Socket>()
                for (i in 0 until streamCount) {
                    val dataSock = server.accept()
                    registerSocket(dataSock)
                    val streamCookie = ByteArray(IPerf3Protocol.COOKIE_SIZE)
                    DataInputStream(dataSock.getInputStream()).readFully(streamCookie)
                    dataSockets.add(dataSock)
                }

                firstOut.writeByte(IPerf3Protocol.TEST_START.toInt())
                firstOut.writeByte(IPerf3Protocol.TEST_RUNNING.toInt())
                firstOut.flush()

                startTime = monotonicMillis()
                lastIntervalTime = startTime

                val clientDone = AtomicBoolean(false)
                val controlMonitor = launch(Dispatchers.IO) {
                    try {
                        while (isRunning.get() && !clientDone.get()) {
                            val b = firstIn.readByte()
                            if (b == IPerf3Protocol.TEST_END || b == IPerf3Protocol.CLIENT_TERMINATE) {
                                clientDone.set(true)
                                break
                            }
                        }
                    } catch (_: Exception) {
                        clientDone.set(true)
                    }
                }

                val dataJobs = dataSockets.map { ds ->
                    launch(Dispatchers.IO) {
                        val buf = ByteArray(64 * 1024)
                        if (reverseRequested) {
                            val out = ds.getOutputStream()
                            while (isRunning.get() && !clientDone.get() &&
                                (monotonicMillis() - startTime) < reportedDurationSec * 1000L
                            ) {
                                try {
                                    out.write(buf)
                                    totalBytesCounter.addAndGet(buf.size.toLong())
                                } catch (_: Exception) {
                                    break
                                }
                            }
                        } else {
                            val inp = ds.getInputStream()
                            while (isRunning.get() && !clientDone.get()) {
                                val read = try {
                                    inp.read(buf)
                                } catch (_: Exception) {
                                    -1
                                }
                                if (read <= 0) break
                                totalBytesCounter.addAndGet(read.toLong())
                            }
                        }
                    }
                }

                while (isRunning.get() && !clientDone.get()) {
                    if (dataJobs.all { !it.isActive }) break
                    kotlinx.coroutines.delay(250L)
                    val now = monotonicMillis()
                    val intervalElapsed = now - lastIntervalTime
                    if (intervalElapsed >= 1000L) {
                        val currentTotalBytes = totalBytesCounter.get()
                        val intervalBytes = currentTotalBytes - lastBytes
                        val intervalSec = intervalElapsed / 1000.0
                        val intervalMbps = (intervalBytes * 8.0) / (intervalSec * 1_000_000.0)
                        val item = IPerfInterval(
                            intervalIndex = intervalIndex++,
                            timeRange = String.format(
                                Locale.US,
                                "%.1f - %.1f sec",
                                (lastIntervalTime - startTime) / 1000.0,
                                (now - startTime) / 1000.0
                            ),
                            bytesTransferred = intervalBytes,
                            bandwidthMbps = intervalMbps
                        )
                        intervals.add(item)
                        if (intervalMbps > peakBandwidth) peakBandwidth = intervalMbps
                        onInterval(item)
                        lastIntervalTime = now
                        lastBytes = currentTotalBytes
                    }
                }

                clientDone.set(true)
                controlMonitor.cancel()
                dataJobs.forEach { it.cancel() }

                try {
                    firstOut.writeByte(IPerf3Protocol.EXCHANGE_RESULTS.toInt())
                    firstOut.flush()
                    firstSocket.soTimeout = 1500
                    runCatching { IPerf3Protocol.readJsonFrame(firstIn) }
                    IPerf3Protocol.writeJsonFrame(
                        firstOut,
                        IPerf3Protocol.buildResultsJson(cookieStr, totalBytesCounter.get(), streamCount)
                    )
                    firstOut.writeByte(IPerf3Protocol.DISPLAY_RESULTS.toInt())
                    firstOut.flush()
                } catch (_: Exception) {}
            } else {
                // Fallback raw stream compatibility
                startTime = monotonicMillis()
                lastIntervalTime = startTime
                totalBytesCounter.addAndGet(IPerf3Protocol.COOKIE_SIZE.toLong())
                val buffer = ByteArray(64 * 1024)
                while (isRunning.get()) {
                    val read = firstIn.read(buffer)
                    if (read == -1) break
                    totalBytesCounter.addAndGet(read.toLong())
                    val now = monotonicMillis()
                    val intervalElapsed = now - lastIntervalTime
                    if (intervalElapsed >= 1000L) {
                        val currentTotalBytes = totalBytesCounter.get()
                        val intervalBytes = currentTotalBytes - lastBytes
                        val intervalSec = intervalElapsed / 1000.0
                        val intervalMbps = (intervalBytes * 8.0) / (intervalSec * 1_000_000.0)
                        val item = IPerfInterval(
                            intervalIndex = intervalIndex++,
                            timeRange = String.format(
                                Locale.US,
                                "%.1f - %.1f sec",
                                (lastIntervalTime - startTime) / 1000.0,
                                (now - startTime) / 1000.0
                            ),
                            bytesTransferred = intervalBytes,
                            bandwidthMbps = intervalMbps
                        )
                        intervals.add(item)
                        if (intervalMbps > peakBandwidth) peakBandwidth = intervalMbps
                        onInterval(item)
                        lastIntervalTime = now
                        lastBytes = currentTotalBytes
                    }
                }
            }

            val finalElapsedMs = (monotonicMillis() - startTime).coerceAtLeast(1L)
            val finalTotalBytes = totalBytesCounter.get()
            val avgMbps = (finalTotalBytes * 8.0) / ((finalElapsedMs / 1000.0) * 1_000_000.0)

            val summary = IPerfSummary(
                mode = "Server",
                direction = if (reverseRequested) "Upload (-R to Client)" else "Receive (Download)",
                targetOrClient = clientAddress,
                resolvedIp = clientAddress,
                port = port,
                protocol = "TCP",
                parallelStreams = streamCount,
                durationSeconds = ((finalElapsedMs / 1000L).toInt()).coerceAtLeast(1),
                connectTimeoutMs = 0,
                totalBytes = finalTotalBytes,
                avgBandwidthMbps = avgMbps,
                peakBandwidthMbps = peakBandwidth.coerceAtLeast(avgMbps),
                intervals = intervals
            )

            Result.success(summary)
        } catch (e: Throwable) {
            if (e is IPerfServerIdleTimeoutException) {
                Result.failure(e)
            } else if (isRunning.get()) {
                val details = IPerf3ErrorClassifier.classify(
                    throwable = e,
                    targetHost = "Local Server",
                    resolvedIp = null,
                    port = port,
                    connectTimeoutMs = 0
                )
                Result.failure(IPerfEngineException(details, e))
            } else {
                Result.failure(CancellationException("Server stopped by technician."))
            }
        } finally {
            stop()
        }
    }
}
