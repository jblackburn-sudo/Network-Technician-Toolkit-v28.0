package com.example.networktoolkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Modern network ping utility powered by Kotlin Coroutines.
 *
 * Features:
 * - Asynchronous DNS hostname resolution with lookup timing.
 * - Non-blocking packet streaming on Dispatchers.IO.
 * - ICMP system ping execution with Toybox/iputils output & summary parsing.
 * - TCP socket fallback with distinct classification (Open vs Connection Refused/RST vs Timeout vs Unreachable).
 * - Comprehensive live statistics: Min / Avg / Max RTT, Packet Loss %, and Jitter (mdev).
 * - Immediate cooperative cancellation via Coroutine Job.
 */
class CoroutinePingEngine @JvmOverloads constructor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val socketProbePort: Int = DEFAULT_SOCKET_PORT
) {

    companion object {
        const val DEFAULT_SOCKET_PORT = 80

        internal fun classifySocketException(e: Exception, elapsedMs: Long): Pair<ProbeStatus, String> {
            val msg = e.message.orEmpty()
            return when {
                e is UnknownHostException -> {
                    ProbeStatus.DNS_FAILURE to "DNS resolution failed (${msg.ifBlank { "unknown host" }})"
                }
                e is ConnectException &&
                    (msg.contains("refused", ignoreCase = true) || msg.contains("ECONNREFUSED", ignoreCase = true)) -> {
                    ProbeStatus.CONNECTION_REFUSED to "Connection refused (TCP RST received — host reachable, port closed)"
                }
                e is SocketTimeoutException -> {
                    ProbeStatus.TIMEOUT to "Request timeout after $elapsedMs ms"
                }
                e is NoRouteToHostException ||
                    msg.contains("ENETUNREACH", ignoreCase = true) ||
                    msg.contains("EHOSTUNREACH", ignoreCase = true) ||
                    msg.contains("unreachable", ignoreCase = true) -> {
                    ProbeStatus.UNREACHABLE to "Host/Network unreachable (${msg.ifBlank { "no route" }})"
                }
                e is IOException -> {
                    ProbeStatus.NETWORK_ERROR to "Socket network error (${msg.ifBlank { e.javaClass.simpleName }})"
                }
                else -> {
                    ProbeStatus.NETWORK_ERROR to "Probe error (${msg.ifBlank { e.javaClass.simpleName }})"
                }
            }
        }
    }

    enum class PingMode {
        AUTO,
        ICMP,
        SOCKET
    }

    enum class ProbeStatus {
        SUCCESS,
        CONNECTION_REFUSED,
        TIMEOUT,
        DNS_FAILURE,
        UNREACHABLE,
        NETWORK_ERROR
    }

    data class PingPacket(
        val sequence: Int,
        val bytes: Int,
        val host: String,
        val ip: String,
        val rttMs: Double,
        val ttl: Int?,
        val isSuccess: Boolean,
        val displayLine: String,
        val probeStatus: ProbeStatus = if (isSuccess) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
        val protocolUsed: String = "ICMP"
    )

    data class PingSummary(
        val target: String,
        val ip: String,
        val transmitted: Int,
        val received: Int,
        val lossPercentage: Double,
        val minRtt: Double,
        val avgRtt: Double,
        val maxRtt: Double,
        val mdevRtt: Double,
        val totalTimeMs: Long,
        val protocolUsed: String = "ICMP",
        val reachabilityNote: String = "",
        val connectionRefusedCount: Int = 0,
        val wasCancelled: Boolean = false
    )

    interface PingCallback {
        fun onStarted(target: String, count: Int, mode: PingMode)
        fun onDnsResolved(hostname: String, ip: String, dnsTimeMs: Long)
        fun onPacketReceived(packet: PingPacket)
        fun onProgress(current: Int, total: Int)
        fun onSummary(summary: PingSummary)
        fun onError(message: String)
        fun onCompleted(cancelled: Boolean)
    }

    private var activeJob: Job? = null
    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var activeSocket: Socket? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    /**
     * Start a ping test using Kotlin coroutines.
     */
    @JvmOverloads
    fun startPing(
        target: String,
        count: Int,
        timeoutSec: Int = 2,
        mode: PingMode = PingMode.AUTO,
        callback: PingCallback
    ) {
        stopPing()

        val boundedCount = count.coerceIn(1, 100)
        val boundedTimeout = timeoutSec.coerceIn(1, 30)

        val sanitizedTarget: String = com.example.networktoolkit.diagnostics.IpAddressValidator.sanitizeDiagnosticTarget(target)
            ?: run {
                callback.onError("Invalid target host or IP address: must be a valid IPv4, IPv6, or hostname without options or spaces.")
                callback.onCompleted(cancelled = false)
                return
            }

        activeJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var transmitted = 0
            var received = 0
            var connectionRefusedCount = 0
            val rttList = mutableListOf<Double>()
            var resolvedIp = sanitizedTarget
            var effectiveProtocol = when (mode) {
                PingMode.ICMP -> "ICMP"
                PingMode.SOCKET -> "TCP/$socketProbePort"
                PingMode.AUTO -> "ICMP"
            }
            var reachabilityNote = ""

            callback.onStarted(sanitizedTarget, boundedCount, mode)

            try {
                // 1. Asynchronous DNS Resolution
                withContext(Dispatchers.IO) {
                    val dnsStart = System.currentTimeMillis()
                    try {
                        val inetAddress = InetAddress.getByName(sanitizedTarget)
                        val dnsTime = System.currentTimeMillis() - dnsStart
                        resolvedIp = inetAddress.hostAddress ?: sanitizedTarget
                        withContext(Dispatchers.Main) {
                            callback.onDnsResolved(sanitizedTarget, resolvedIp, dnsTime)
                        }
                    } catch (e: Exception) {
                        ensureActive()
                        withContext(Dispatchers.Main) {
                            callback.onError("DNS resolution failed for \"$sanitizedTarget\": ${e.message}")
                        }
                        throw e
                    }
                }

                // 2. Execute Ping based on selected mode
                when (mode) {
                    PingMode.ICMP -> {
                        effectiveProtocol = "ICMP"
                        executeIcmpPing(
                            target = sanitizedTarget,
                            resolvedIp = resolvedIp,
                            count = boundedCount,
                            timeoutSec = boundedTimeout,
                            onPacket = { pkt ->
                                transmitted++
                                if (pkt.isSuccess) {
                                    received++
                                    rttList.add(pkt.rttMs)
                                }
                                callback.onPacketReceived(pkt)
                                callback.onProgress(transmitted, boundedCount)
                                callback.onSummary(
                                    calculateSummary(
                                        target = sanitizedTarget,
                                        ip = resolvedIp,
                                        transmitted = transmitted,
                                        received = received,
                                        rttList = rttList,
                                        totalTimeMs = System.currentTimeMillis() - startTime,
                                        protocolUsed = effectiveProtocol,
                                        reachabilityNote = if (received == 0 && transmitted > 0) "No ICMP echo replies received" else "",
                                        connectionRefusedCount = 0,
                                        wasCancelled = false,
                                        attempted = true
                                    )
                                )
                            },
                            onError = { err -> callback.onError(err) }
                        )
                    }
                    PingMode.SOCKET -> {
                        effectiveProtocol = "TCP/$socketProbePort"
                        executeSocketPing(
                            resolvedIp = resolvedIp,
                            host = sanitizedTarget,
                            count = boundedCount,
                            timeoutSec = boundedTimeout,
                            protocolLabel = effectiveProtocol,
                            onPacket = { pkt ->
                                transmitted++
                                if (pkt.isSuccess) {
                                    received++
                                    rttList.add(pkt.rttMs)
                                }
                                if (pkt.probeStatus == ProbeStatus.CONNECTION_REFUSED) {
                                    connectionRefusedCount++
                                }
                                reachabilityNote = buildSocketReachabilityNote(received, connectionRefusedCount, socketProbePort)
                                callback.onPacketReceived(pkt)
                                callback.onProgress(transmitted, boundedCount)
                                callback.onSummary(
                                    calculateSummary(
                                        target = sanitizedTarget,
                                        ip = resolvedIp,
                                        transmitted = transmitted,
                                        received = received,
                                        rttList = rttList,
                                        totalTimeMs = System.currentTimeMillis() - startTime,
                                        protocolUsed = effectiveProtocol,
                                        reachabilityNote = reachabilityNote,
                                        connectionRefusedCount = connectionRefusedCount,
                                        wasCancelled = false,
                                        attempted = true
                                    )
                                )
                            }
                        )
                    }
                    PingMode.AUTO -> {
                        // Attempt ICMP first; only accept ICMP as authoritative when at least 1 reply arrives.
                        // If ICMP is blocked, errors out, or has 100% loss, run a full TCP fallback probe
                        // without mixing failed ICMP probes into the fallback packet counts.
                        var icmpErrorDetail: String? = null
                        try {
                            executeIcmpPing(
                                target = sanitizedTarget,
                                resolvedIp = resolvedIp,
                                count = boundedCount,
                                timeoutSec = boundedTimeout,
                                onPacket = { pkt ->
                                    transmitted++
                                    if (pkt.isSuccess) {
                                        received++
                                        rttList.add(pkt.rttMs)
                                    }
                                    callback.onPacketReceived(pkt)
                                    callback.onProgress(transmitted, boundedCount)
                                    callback.onSummary(
                                        calculateSummary(
                                            target = sanitizedTarget,
                                            ip = resolvedIp,
                                            transmitted = transmitted,
                                            received = received,
                                            rttList = rttList,
                                            totalTimeMs = System.currentTimeMillis() - startTime,
                                            protocolUsed = "ICMP",
                                            reachabilityNote = "",
                                            connectionRefusedCount = 0,
                                            wasCancelled = false,
                                            attempted = true
                                        )
                                    )
                                },
                                onError = { err -> icmpErrorDetail = err }
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            icmpErrorDetail = e.message
                        }

                        if (received == 0 && isActive) {
                            val icmpNotice = if (transmitted > 0) {
                                "ICMP received 0/$transmitted replies (filtered or 100% ICMP loss). Running TCP/$socketProbePort fallback probes..."
                            } else {
                                "ICMP unavailable (${icmpErrorDetail ?: "no response"}). Switching to TCP/$socketProbePort fallback probes..."
                            }
                            withContext(Dispatchers.Main) {
                                callback.onError(icmpNotice)
                            }

                            // Reset probe counters so fallback reports accurate TCP-only totals
                            transmitted = 0
                            received = 0
                            connectionRefusedCount = 0
                            rttList.clear()
                            effectiveProtocol = "TCP/$socketProbePort (Fallback)"

                            executeSocketPing(
                                resolvedIp = resolvedIp,
                                host = sanitizedTarget,
                                count = boundedCount,
                                timeoutSec = boundedTimeout,
                                protocolLabel = effectiveProtocol,
                                onPacket = { pkt ->
                                    transmitted++
                                    if (pkt.isSuccess) {
                                        received++
                                        rttList.add(pkt.rttMs)
                                    }
                                    if (pkt.probeStatus == ProbeStatus.CONNECTION_REFUSED) {
                                        connectionRefusedCount++
                                    }
                                    reachabilityNote = buildSocketReachabilityNote(received, connectionRefusedCount, socketProbePort)
                                    callback.onPacketReceived(pkt)
                                    callback.onProgress(transmitted, boundedCount)
                                    callback.onSummary(
                                        calculateSummary(
                                            target = sanitizedTarget,
                                            ip = resolvedIp,
                                            transmitted = transmitted,
                                            received = received,
                                            rttList = rttList,
                                            totalTimeMs = System.currentTimeMillis() - startTime,
                                            protocolUsed = effectiveProtocol,
                                            reachabilityNote = reachabilityNote,
                                            connectionRefusedCount = connectionRefusedCount,
                                            wasCancelled = false,
                                            attempted = true
                                        )
                                    )
                                }
                            )
                        } else if (received > 0) {
                            effectiveProtocol = "ICMP"
                            reachabilityNote = "Host reachable via ICMP Echo"
                        }
                    }
                }

                // Final Summary
                val totalTime = System.currentTimeMillis() - startTime
                val finalNote = reachabilityNote.ifBlank {
                    when {
                        received > 0 -> "Host reachable via $effectiveProtocol"
                        else -> "No response from $sanitizedTarget via $effectiveProtocol"
                    }
                }
                val finalSummary = calculateSummary(
                    target = sanitizedTarget,
                    ip = resolvedIp,
                    transmitted = transmitted,
                    received = received,
                    rttList = rttList,
                    totalTimeMs = totalTime,
                    protocolUsed = effectiveProtocol,
                    reachabilityNote = finalNote,
                    connectionRefusedCount = connectionRefusedCount,
                    wasCancelled = false,
                    attempted = true
                )
                callback.onSummary(finalSummary)
                callback.onCompleted(cancelled = false)

            } catch (_: CancellationException) {
                val totalTime = System.currentTimeMillis() - startTime
                val cancelNote = if (transmitted > 0) {
                    "Stopped by user ($received/$transmitted probes received via $effectiveProtocol)"
                } else {
                    "Stopped by user before any probe completed"
                }
                val finalSummary = calculateSummary(
                    target = sanitizedTarget,
                    ip = resolvedIp,
                    transmitted = transmitted,
                    received = received,
                    rttList = rttList,
                    totalTimeMs = totalTime,
                    protocolUsed = effectiveProtocol,
                    reachabilityNote = cancelNote,
                    connectionRefusedCount = connectionRefusedCount,
                    wasCancelled = true,
                    attempted = transmitted > 0
                )
                callback.onSummary(finalSummary)
                callback.onCompleted(cancelled = true)
            } catch (e: Exception) {
                val totalTime = System.currentTimeMillis() - startTime
                val errorSummary = calculateSummary(
                    target = sanitizedTarget,
                    ip = resolvedIp,
                    transmitted = transmitted,
                    received = received,
                    rttList = rttList,
                    totalTimeMs = totalTime,
                    protocolUsed = effectiveProtocol,
                    reachabilityNote = e.message ?: "Probe failed",
                    connectionRefusedCount = connectionRefusedCount,
                    wasCancelled = false,
                    attempted = true
                )
                callback.onSummary(errorSummary)
                callback.onError("Ping stopped unexpectedly: ${e.message}")
                callback.onCompleted(cancelled = false)
            } finally {
                cleanup()
            }
        }
    }

    private fun buildSocketReachabilityNote(
        received: Int,
        connectionRefusedCount: Int,
        port: Int
    ): String {
        return when {
            connectionRefusedCount > 0 && connectionRefusedCount == received ->
                "Host reachable via TCP RST ($connectionRefusedCount/$received probes; tcp/$port closed)"
            connectionRefusedCount > 0 ->
                "Host reachable ($connectionRefusedCount TCP RST on port $port, ${received - connectionRefusedCount} TCP SYN-ACK)"
            received > 0 ->
                "Host reachable (tcp/$port open)"
            else ->
                "No TCP response on port $port (timed out or filtered)"
        }
    }

    /**
     * Cooperatively stop the active ping coroutine job.
     */
    fun stopPing() {
        activeJob?.cancel()
        activeJob = null
        cleanup()
    }

    private fun cleanup() {
        try {
            activeProcess?.destroy()
        } catch (_: Exception) {}
        activeProcess = null

        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
    }

    private suspend fun executeIcmpPing(
        target: String,
        resolvedIp: String,
        count: Int,
        timeoutSec: Int,
        onPacket: suspend (PingPacket) -> Unit,
        onError: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val safeTarget = com.example.networktoolkit.diagnostics.IpAddressValidator.sanitizeDiagnosticTarget(target)
        if (safeTarget == null) {
            onError("Invalid ping target host.")
            return@withContext
        }
        val boundedCount = count.coerceIn(1, 100)
        val boundedTimeout = timeoutSec.coerceIn(1, 30)
        val processBuilder = ProcessBuilder(
            "ping",
            "-c", boundedCount.toString(),
            "-W", boundedTimeout.toString(),
            safeTarget
        ).redirectErrorStream(true)

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            ensureActive()
            withContext(Dispatchers.Main) {
                onError("Cannot execute system ping process: ${e.message}")
            }
            return@withContext
        }

        activeProcess = process

        val timePattern = Pattern.compile("time[=<]([0-9.]+)\\s*ms")
        val seqPattern = Pattern.compile("(?:icmp_)?seq=([0-9]+)")
        val ttlPattern = Pattern.compile("ttl=([0-9]+)")
        val bytesPattern = Pattern.compile("([0-9]+)\\s+bytes")
        val summaryPattern = Pattern.compile("(\\d+)\\s+packets transmitted,\\s*(\\d+)\\s+(?:packets\\s+)?received")

        var parsedPacketEvents = 0
        var summaryTransmitted = 0
        var summaryReceived = 0
        var lastErrorOutput: String? = null

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String? = reader.readLine()
            while (line != null && isActive) {
                val currentLine = line.trim()
                val lower = currentLine.lowercase(Locale.US)

                val isIcmpErrorReply = lower.contains("time to live exceeded") ||
                    lower.contains("time exceeded") ||
                    lower.contains("destination host unreachable") ||
                    lower.contains("destination net unreachable") ||
                    lower.contains("destination port unreachable") ||
                    lower.contains("packet filtered") ||
                    lower.contains("request timeout") ||
                    lower.contains("no answer yet")

                if ((lower.contains("bytes from") || lower.contains("time=")) && !isIcmpErrorReply) {
                    parsedPacketEvents++
                    var seq = parsedPacketEvents
                    val seqMatcher = seqPattern.matcher(currentLine)
                    if (seqMatcher.find()) {
                        seq = seqMatcher.group(1)?.toIntOrNull() ?: parsedPacketEvents
                    }

                    var ttl: Int? = null
                    val ttlMatcher = ttlPattern.matcher(currentLine)
                    if (ttlMatcher.find()) {
                        ttl = ttlMatcher.group(1)?.toIntOrNull()
                    }

                    var bytes = 64
                    val bytesMatcher = bytesPattern.matcher(currentLine)
                    if (bytesMatcher.find()) {
                        bytes = bytesMatcher.group(1)?.toIntOrNull() ?: 64
                    }

                    var rtt = 0.0
                    val timeMatcher = timePattern.matcher(currentLine)
                    if (timeMatcher.find()) {
                        rtt = timeMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                    }

                    val packet = PingPacket(
                        sequence = seq,
                        bytes = bytes,
                        host = target,
                        ip = resolvedIp,
                        rttMs = rtt,
                        ttl = ttl,
                        isSuccess = true,
                        displayLine = currentLine,
                        probeStatus = ProbeStatus.SUCCESS,
                        protocolUsed = "ICMP"
                    )

                    withContext(Dispatchers.Main) {
                        onPacket(packet)
                    }
                } else if (isIcmpErrorReply) {
                    parsedPacketEvents++
                    val seqMatcher = seqPattern.matcher(currentLine)
                    val seq = if (seqMatcher.find()) {
                        seqMatcher.group(1)?.toIntOrNull() ?: parsedPacketEvents
                    } else {
                        parsedPacketEvents
                    }
                    val status = if (lower.contains("unreachable") || lower.contains("exceeded") || lower.contains("filtered")) {
                        ProbeStatus.UNREACHABLE
                    } else {
                        ProbeStatus.TIMEOUT
                    }
                    val packet = PingPacket(
                        sequence = seq,
                        bytes = 0,
                        host = target,
                        ip = resolvedIp,
                        rttMs = 0.0,
                        ttl = null,
                        isSuccess = false,
                        displayLine = currentLine,
                        probeStatus = status,
                        protocolUsed = "ICMP"
                    )
                    withContext(Dispatchers.Main) {
                        onPacket(packet)
                    }
                } else {
                    val summaryMatcher = summaryPattern.matcher(currentLine)
                    if (summaryMatcher.find()) {
                        summaryTransmitted = summaryMatcher.group(1)?.toIntOrNull() ?: summaryTransmitted
                        summaryReceived = summaryMatcher.group(2)?.toIntOrNull() ?: summaryReceived
                    } else if (
                        lower.contains("operation not permitted") ||
                        lower.contains("network is unreachable") ||
                        lower.contains("permission denied") ||
                        lower.contains("cannot assign") ||
                        lower.contains("unknown host")
                    ) {
                        lastErrorOutput = currentLine
                    }
                }

                line = reader.readLine()
            }

            // Toybox / Android ping often omits per-packet timeout lines when all probes time out,
            // printing only "N packets transmitted, 0 received, 100% packet loss" at exit.
            if (isActive && summaryTransmitted > parsedPacketEvents && summaryReceived == 0) {
                for (seq in (parsedPacketEvents + 1)..summaryTransmitted) {
                    if (!isActive) break
                    val missingPacket = PingPacket(
                        sequence = seq,
                        bytes = 0,
                        host = target,
                        ip = resolvedIp,
                        rttMs = 0.0,
                        ttl = null,
                        isSuccess = false,
                        displayLine = "ICMP request timeout for icmp_seq=$seq (no reply from $resolvedIp)",
                        probeStatus = ProbeStatus.TIMEOUT,
                        protocolUsed = "ICMP"
                    )
                    withContext(Dispatchers.Main) {
                        onPacket(missingPacket)
                    }
                }
            } else if (isActive && parsedPacketEvents == 0 && summaryTransmitted == 0) {
                val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                if (lastErrorOutput != null || exitCode != 0) {
                    withContext(Dispatchers.Main) {
                        onError(lastErrorOutput ?: "ICMP ping exited with code $exitCode and 0 replies")
                    }
                }
            }
        } finally {
            reader.close()
            process.destroy()
            if (activeProcess === process) {
                activeProcess = null
            }
        }
    }

    private suspend fun executeSocketPing(
        resolvedIp: String,
        host: String,
        count: Int,
        timeoutSec: Int,
        protocolLabel: String,
        onPacket: suspend (PingPacket) -> Unit
    ) = withContext(Dispatchers.IO) {
        val timeoutMs = timeoutSec * 1000
        val targetAddress = InetSocketAddress(resolvedIp, socketProbePort)

        for (seq in 1..count) {
            ensureActive()

            val pStartNano = System.nanoTime()
            var isSuccess = false
            var rtt = 0.0
            var probeStatus = ProbeStatus.TIMEOUT
            var display = ""

            val socket = Socket()
            activeSocket = socket
            try {
                socket.connect(targetAddress, timeoutMs)
                rtt = (System.nanoTime() - pStartNano) / 1_000_000.0
                isSuccess = true
                probeStatus = ProbeStatus.SUCCESS
                display = String.format(
                    Locale.US,
                    "64 bytes from %s: seq=%d time=%.2f ms (tcp/%d open)",
                    resolvedIp, seq, rtt, socketProbePort
                )
            } catch (e: Exception) {
                ensureActive()
                val elapsedNano = System.nanoTime() - pStartNano
                val elapsedMs = elapsedNano / 1_000_000L
                val (classifiedStatus, reason) = classifySocketException(e, elapsedMs)
                probeStatus = classifiedStatus
                if (classifiedStatus == ProbeStatus.CONNECTION_REFUSED) {
                    // An immediate TCP RST proves host IP reachability and measures kernel RTT accurately.
                    rtt = (elapsedNano / 1_000_000.0).coerceAtLeast(0.01)
                    isSuccess = true
                    display = String.format(
                        Locale.US,
                        "Host reachable via TCP RST from %s: seq=%d time=%.2f ms (tcp/%d Connection Refused)",
                        resolvedIp, seq, rtt, socketProbePort
                    )
                } else {
                    isSuccess = false
                    rtt = 0.0
                    display = String.format(
                        Locale.US,
                        "Probe failed for seq=%d (tcp/%d): %s",
                        seq, socketProbePort, reason
                    )
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
                if (activeSocket === socket) {
                    activeSocket = null
                }
            }

            val packet = PingPacket(
                sequence = seq,
                bytes = if (isSuccess) 64 else 0,
                host = host,
                ip = resolvedIp,
                rttMs = rtt,
                ttl = null,
                isSuccess = isSuccess,
                displayLine = display,
                probeStatus = probeStatus,
                protocolUsed = protocolLabel
            )

            withContext(Dispatchers.Main) {
                onPacket(packet)
            }

            // Interval delay between probes (1 second standard)
            if (seq < count && isActive) {
                delay(1000L)
            }
        }
    }

    internal fun calculateSummary(
        target: String,
        ip: String,
        transmitted: Int,
        received: Int,
        rttList: List<Double>,
        totalTimeMs: Long,
        protocolUsed: String = "ICMP",
        reachabilityNote: String = "",
        connectionRefusedCount: Int = 0,
        wasCancelled: Boolean = false,
        attempted: Boolean = transmitted > 0
    ): PingSummary {
        val loss = when {
            transmitted > 0 -> ((transmitted - received).coerceAtLeast(0).toDouble() / transmitted.toDouble()) * 100.0
            attempted && !wasCancelled -> 100.0
            else -> 0.0
        }

        val minRtt = if (rttList.isNotEmpty()) rttList.minOrNull() ?: 0.0 else 0.0
        val maxRtt = if (rttList.isNotEmpty()) rttList.maxOrNull() ?: 0.0 else 0.0
        val avgRtt = if (rttList.isNotEmpty()) rttList.average() else 0.0

        // Calculate standard deviation (mdev / jitter)
        val mdev = if (rttList.size > 1) {
            val variance = rttList.map { (it - avgRtt).pow(2) }.average()
            sqrt(variance)
        } else {
            0.0
        }

        return PingSummary(
            target = target,
            ip = ip,
            transmitted = transmitted,
            received = received,
            lossPercentage = loss,
            minRtt = minRtt,
            avgRtt = avgRtt,
            maxRtt = maxRtt,
            mdevRtt = mdev,
            totalTimeMs = totalTimeMs,
            protocolUsed = protocolUsed,
            reachabilityNote = reachabilityNote,
            connectionRefusedCount = connectionRefusedCount,
            wasCancelled = wasCancelled
        )
    }
}
