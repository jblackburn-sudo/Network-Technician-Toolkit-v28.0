package com.example.networktoolkit

import com.example.networktoolkit.diagnostics.IpAddressValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.regex.Pattern

data class TracerouteHop(
    val hopNumber: Int,
    val ip: String,
    val hostname: String? = null,
    val rttMs: Double? = null,
    val isDestinationReached: Boolean = false,
    val isTimedOut: Boolean = false,
    val statusText: String = "",
    val alternateIps: List<String> = emptyList()
)

data class TracerouteSummary(
    val target: String,
    val targetIp: String,
    val hops: List<TracerouteHop>,
    val isDestinationReached: Boolean,
    val totalHops: Int,
    val avgRttMs: Double?,
    val durationMs: Long
)

data class ParsedPingProbe(
    val responded: Boolean = false,
    val hopIp: String? = null,
    val hostname: String? = null,
    val rttMs: Double? = null,
    val isDestinationReached: Boolean = false,
    val errorStatus: String? = null
)

data class ParsedNativeHop(
    val hopNumber: Int,
    val ip: String,
    val alternateIps: List<String> = emptyList(),
    val rttMs: Double? = null,
    val isTimedOut: Boolean = false,
    val isDestinationReached: Boolean = false,
    val statusText: String = ""
)

/**
 * Pure, deterministic parser and validator for system `ping` / `traceroute` output lines.
 * Supports IPv4 and IPv6 addresses, parenthesized vs numeric responders, ICMP error discrimination,
 * and strict validation against malformed/broadcast addresses.
 */
object TracerouteOutputParser {

    private val pingHeaderPattern = Pattern.compile("""^PING\s+\S+\s+\(([^)]+)\)""", Pattern.CASE_INSENSITIVE)
    private val parenIpPattern = Pattern.compile("""\bfrom\s+([^\s(]+)\s+\(([^)]+)\)""", Pattern.CASE_INSENSITIVE)
    private val directFromPattern = Pattern.compile("""\bfrom\s+([^\s,;]+)""", Pattern.CASE_INSENSITIVE)
    private val ipv4TokenPattern = Pattern.compile("""(?<!\d)(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?!\d)""")
    private val ipv6TokenPattern = Pattern.compile("""(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[A-Za-z0-9._-]+)?(?![0-9A-Fa-f:])""")
    private val timePattern = Pattern.compile("""time[=<]([0-9]+(?:\.[0-9]+)?)\s*ms""", Pattern.CASE_INSENSITIVE)
    private val nativeHopStartPattern = Pattern.compile("""^\s*([0-9]+)\s+(.+)$""")
    private val nativeRttPattern = Pattern.compile("""([0-9]+(?:\.[0-9]+)?)\s*ms""", Pattern.CASE_INSENSITIVE)

    @JvmStatic
    fun isValidHopResponderIp(rawCandidate: String?): Boolean {
        val clean = normalizeIpToken(rawCandidate) ?: return false
        if (clean == "255.255.255.255" || clean == "0.0.0.0" || clean == "::") return false
        val classification = IpAddressValidator.classifyIp(clean)
        if (classification.category == IpAddressValidator.IpCategory.INVALID) return false
        // Reject multicast addresses as hop responders; keep RFC 1112 240.0.0.0/4 (e.g., 255.0.0.x)
        // when genuinely emitted by carrier / 464XLAT infrastructure, annotated explicitly below.
        if (classification.rangeName.startsWith("Multicast", ignoreCase = true)) return false
        return clean.contains(".") || clean.contains(":")
    }

    @JvmStatic
    fun normalizeIpToken(rawToken: String?): String? {
        if (rawToken.isNullOrBlank()) return null
        var token = rawToken.trim().trimStart('(').trimEnd(')', ',', ';')
        if (token.isEmpty()) return null

        // Only strip a trailing ':' when doing so turns an otherwise-invalid token (e.g., "8.8.8.8:" or "2001:db8::1:")
        // into a valid IPv4 or IPv6 address.
        IpAddressValidator.sanitizeDiagnosticTarget(token)?.let { sanitized ->
            if (isIpLiteral(sanitized)) return sanitized
        }
        if (token.endsWith(":")) {
            val trimmedColon = token.dropLast(1)
            IpAddressValidator.sanitizeDiagnosticTarget(trimmedColon)?.let { sanitized ->
                if (isIpLiteral(sanitized)) return sanitized
            }
        }
        return null
    }

    private fun isIpLiteral(candidate: String): Boolean {
        return ipv4TokenPattern.matcher(candidate).matches() ||
            (candidate.contains(":") && IpAddressValidator.sanitizeDiagnosticTarget(candidate) == candidate)
    }

    @JvmStatic
    fun extractPingHeaderTargetIp(line: String): String? {
        val matcher = pingHeaderPattern.matcher(line.trim())
        if (!matcher.find()) return null
        return normalizeIpToken(matcher.group(1))
    }

    @JvmStatic
    fun extractResponderIpAndHostname(line: String): Pair<String?, String?> {
        // 1. Prefer `from <host> (<ip>)` so hostnames with digit labels cannot shadow the actual responder IP
        val parenMatcher = parenIpPattern.matcher(line)
        if (parenMatcher.find()) {
            val hostPart = parenMatcher.group(1)?.trim()?.takeIf { it.isNotEmpty() }
            val ipPart = normalizeIpToken(parenMatcher.group(2))
            if (ipPart != null && isValidHopResponderIp(ipPart)) {
                val cleanHost = hostPart?.takeIf { it != ipPart }
                return ipPart to cleanHost
            }
        }

        // 2. Check `from <ip>` (numeric output from `ping -n` or IP-only responders)
        val fromMatcher = directFromPattern.matcher(line)
        if (fromMatcher.find()) {
            val ipPart = normalizeIpToken(fromMatcher.group(1))
            if (ipPart != null && isValidHopResponderIp(ipPart)) {
                return ipPart to null
            }
        }

        // 3. Fallback: scan validated IPv4 / IPv6 literals on the line
        val ipv6Matcher = ipv6TokenPattern.matcher(line)
        while (ipv6Matcher.find()) {
            val candidate = normalizeIpToken(ipv6Matcher.group())
            if (candidate != null && isValidHopResponderIp(candidate)) {
                return candidate to null
            }
        }

        val ipv4Matcher = ipv4TokenPattern.matcher(line)
        while (ipv4Matcher.find()) {
            val candidate = normalizeIpToken(ipv4Matcher.group())
            if (candidate != null && isValidHopResponderIp(candidate)) {
                return candidate to null
            }
        }

        return null to null
    }

    @JvmStatic
    fun parsePingProbeLine(line: String): ParsedPingProbe {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ParsedPingProbe()

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("ping ") ||
            lower.startsWith("---") ||
            lower.contains("packets transmitted") ||
            lower.contains("rtt min/avg") ||
            lower.contains("round-trip min/avg")
        ) {
            return ParsedPingProbe()
        }

        val timeMatcher = timePattern.matcher(trimmed)
        val rtt = if (timeMatcher.find()) timeMatcher.group(1)?.toDoubleOrNull() else null

        // Check Time-to-Live / Hop-Limit Exceeded first
        if (lower.contains("time to live exceeded") || lower.contains("time exceeded")) {
            val (ip, host) = extractResponderIpAndHostname(trimmed)
            return ParsedPingProbe(
                responded = true,
                hopIp = ip,
                hostname = host,
                rttMs = rtt,
                isDestinationReached = false,
                errorStatus = null
            )
        }

        // Check non-echo ICMP error conditions BEFORE Echo Reply so lines like
        // `From 10.0.0.1 icmp_seq=1 Destination Net Unreachable` are NEVER marked Destination Reached.
        if (lower.contains("unreachable") ||
            lower.contains("filtered") ||
            lower.contains("prohibited") ||
            lower.contains("redirect") ||
            lower.contains("source quench") ||
            lower.contains("parameter problem")
        ) {
            val (ip, host) = extractResponderIpAndHostname(trimmed)
            val reason = when {
                lower.contains("prohibited") || lower.contains("filtered") -> "ICMP Administratively Filtered"
                lower.contains("net unreachable") -> "Destination Net Unreachable"
                lower.contains("host unreachable") -> "Destination Host Unreachable"
                lower.contains("port unreachable") -> "Destination Port Unreachable"
                else -> "ICMP Error Reported"
            }
            return ParsedPingProbe(
                responded = ip != null,
                hopIp = ip,
                hostname = host,
                rttMs = rtt,
                isDestinationReached = false,
                errorStatus = reason
            )
        }

        // Genuine Echo Reply (`bytes from <ip>` with echo telemetry, excluding error lines handled above)
        val looksLikeEchoReply = lower.contains("bytes from") &&
            (lower.contains("icmp_seq") || lower.contains("icmp_req") || lower.contains("ttl=") || lower.contains("hlim=") || lower.contains("time="))
        if (looksLikeEchoReply) {
            val (ip, host) = extractResponderIpAndHostname(trimmed)
            return ParsedPingProbe(
                responded = true,
                hopIp = ip,
                hostname = host,
                rttMs = rtt,
                isDestinationReached = true,
                errorStatus = null
            )
        }

        return ParsedPingProbe()
    }

    @JvmStatic
    fun buildHopStatusText(ip: String, isDestinationReached: Boolean, errorStatus: String? = null): String {
        if (!errorStatus.isNullOrBlank()) {
            return errorStatus
        }
        if (isDestinationReached) {
            return "Destination Reached"
        }
        val classification = IpAddressValidator.classifyIp(ip)
        return when {
            classification.category == IpAddressValidator.IpCategory.SPECIAL_USE &&
                classification.rangeName.startsWith("Reserved (240.0.0.0/4)") ->
                "Intermediate Hop (Reserved 240.0.0.0/4 / Carrier-Translated)"
            classification.category == IpAddressValidator.IpCategory.CARRIER_GRADE_NAT ->
                "Intermediate Hop (Carrier CGNAT)"
            classification.category == IpAddressValidator.IpCategory.PRIVATE_NETWORK ->
                "Intermediate Hop (Private / Internal)"
            else -> "Intermediate Hop"
        }
    }

    @JvmStatic
    fun parseNativeTracerouteLine(line: String, target: String, targetIp: String): ParsedNativeHop? {
        val cleanLine = line.trim()
        if (cleanLine.isEmpty() || cleanLine.startsWith("traceroute", ignoreCase = true)) {
            return null
        }

        val startMatcher = nativeHopStartPattern.matcher(cleanLine)
        if (!startMatcher.find()) return null

        val hopNum = startMatcher.group(1)?.toIntOrNull() ?: return null
        val rest = startMatcher.group(2)?.trim() ?: return null

        // Collect all valid IPv4 & IPv6 responder tokens on this hop line
        val foundIps = mutableListOf<String>()
        val ipv6Matcher = ipv6TokenPattern.matcher(rest)
        while (ipv6Matcher.find()) {
            val ip = normalizeIpToken(ipv6Matcher.group())
            if (ip != null && isValidHopResponderIp(ip) && !foundIps.contains(ip)) {
                foundIps.add(ip)
            }
        }
        val ipv4Matcher = ipv4TokenPattern.matcher(rest)
        while (ipv4Matcher.find()) {
            val ip = normalizeIpToken(ipv4Matcher.group())
            if (ip != null && isValidHopResponderIp(ip) && !foundIps.contains(ip)) {
                foundIps.add(ip)
            }
        }

        if (foundIps.isEmpty()) {
            if (rest.contains("*")) {
                return ParsedNativeHop(
                    hopNumber = hopNum,
                    ip = "*",
                    isTimedOut = true,
                    isDestinationReached = false,
                    statusText = "Request Timed Out"
                )
            }
            return null
        }

        val primaryIp = foundIps.first()
        val extraIps = foundIps.drop(1)
        val rttMatcher = nativeRttPattern.matcher(rest)
        val rtt = if (rttMatcher.find()) rttMatcher.group(1)?.toDoubleOrNull() else null
        val isReached = foundIps.any { it == targetIp || it == target }

        return ParsedNativeHop(
            hopNumber = hopNum,
            ip = primaryIp,
            alternateIps = extraIps,
            rttMs = rtt,
            isTimedOut = false,
            isDestinationReached = isReached,
            statusText = buildHopStatusText(primaryIp, isReached)
        )
    }
}

/**
 * Traceroute engine executing via Kotlin ProcessBuilder and coroutines.
 * First tries executing the system 'traceroute' / 'traceroute6' command via ProcessBuilder;
 * if unavailable, executes hop-by-hop TTL / Hop-Limit probes via ProcessBuilder
 * (`ping -n -c 1 -t <ttl>` / `ping -6` / `ping6`).
 */
class TracerouteEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    interface Callback {
        fun onStarted(target: String, targetIp: String, maxHops: Int)
        fun onHopReceived(hop: TracerouteHop)
        fun onProgress(currentHop: Int, maxHops: Int)
        fun onCompleted(summary: TracerouteSummary)
        fun onError(message: String)
    }

    private var activeJob: Job? = null
    private var activeProcess: Process? = null

    val isRunning: Boolean
        get() = activeJob?.isActive == true

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        try {
            activeProcess?.destroy()
        } catch (_: Exception) {}
        activeProcess = null
    }

    fun startTraceroute(
        target: String,
        maxHops: Int = 20,
        timeoutSec: Int = 2,
        callback: Callback
    ) {
        stop()

        val sanitizedTarget: String = IpAddressValidator.sanitizeDiagnosticTarget(target)
            ?: run {
                callback.onError("Invalid target host or IP address: must be a valid IPv4, IPv6, or hostname without options or spaces.")
                return
            }

        activeJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val hopsList = mutableListOf<TracerouteHop>()
            var targetIp = sanitizedTarget

            try {
                // 1. Resolve target hostname once so every TTL probe traces a single consistent IP
                withContext(Dispatchers.IO) {
                    try {
                        val allAddresses = InetAddress.getAllByName(sanitizedTarget)
                        val preferred = allAddresses.firstOrNull { it is Inet4Address } ?: allAddresses.firstOrNull()
                        targetIp = preferred?.hostAddress ?: sanitizedTarget
                    } catch (e: Exception) {
                        ensureActive()
                        withContext(Dispatchers.Main) {
                            callback.onError("Could not resolve host: ${e.message}")
                        }
                        throw e
                    }
                }

                withContext(Dispatchers.Main) {
                    callback.onStarted(sanitizedTarget, targetIp, maxHops)
                }

                // 2. Try executing native 'traceroute' / 'traceroute6' command via ProcessBuilder
                var nativeTracerouteExecuted = false
                try {
                    nativeTracerouteExecuted = executeNativeTracerouteCommand(
                        target = sanitizedTarget,
                        targetIp = targetIp,
                        maxHops = maxHops,
                        timeoutSec = timeoutSec,
                        onHop = { hop ->
                            hopsList.add(hop)
                            callback.onHopReceived(hop)
                            callback.onProgress(hop.hopNumber, maxHops)
                        }
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    nativeTracerouteExecuted = false
                }

                // 3. Fallback to TTL/Hop-Limit hop-by-hop execution via ProcessBuilder if native binary unavailable
                if (!nativeTracerouteExecuted || hopsList.isEmpty()) {
                    executeTtlProcessBuilderTraceroute(
                        target = sanitizedTarget,
                        targetIp = targetIp,
                        maxHops = maxHops,
                        timeoutSec = timeoutSec,
                        onHop = { hop ->
                            hopsList.add(hop)
                            callback.onHopReceived(hop)
                            callback.onProgress(hop.hopNumber, maxHops)
                        }
                    )
                }

                val duration = System.currentTimeMillis() - startTime
                val destinationReached = hopsList.any { it.isDestinationReached }
                val measuredRtts = hopsList.mapNotNull { it.rttMs }
                val avgRtt = if (measuredRtts.isNotEmpty()) measuredRtts.average() else null

                val summary = TracerouteSummary(
                    target = sanitizedTarget,
                    targetIp = targetIp,
                    hops = hopsList.toList(),
                    isDestinationReached = destinationReached,
                    totalHops = hopsList.size,
                    avgRttMs = avgRtt,
                    durationMs = duration
                )

                callback.onCompleted(summary)

            } catch (e: CancellationException) {
                val duration = System.currentTimeMillis() - startTime
                val destinationReached = hopsList.any { it.isDestinationReached }
                val measuredRtts = hopsList.mapNotNull { it.rttMs }
                val avgRtt = if (measuredRtts.isNotEmpty()) measuredRtts.average() else null

                callback.onCompleted(
                    TracerouteSummary(
                        target = sanitizedTarget,
                        targetIp = targetIp,
                        hops = hopsList.toList(),
                        isDestinationReached = destinationReached,
                        totalHops = hopsList.size,
                        avgRttMs = avgRtt,
                        durationMs = duration
                    )
                )
            } catch (e: Exception) {
                callback.onError("Traceroute interrupted: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    private suspend fun executeNativeTracerouteCommand(
        target: String,
        targetIp: String,
        maxHops: Int,
        timeoutSec: Int,
        onHop: suspend (TracerouteHop) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val probeTarget = IpAddressValidator.sanitizeDiagnosticTarget(targetIp)
            ?: IpAddressValidator.sanitizeDiagnosticTarget(target)
            ?: return@withContext false
        val isIpv6 = probeTarget.contains(":")
        val binary = if (isIpv6) "traceroute6" else "traceroute"

        val processBuilder = ProcessBuilder(
            binary,
            "-n",
            "-m", maxHops.coerceIn(1, 64).toString(),
            "-w", timeoutSec.coerceIn(1, 30).toString(),
            probeTarget
        ).redirectErrorStream(true)

        val process: Process
        try {
            process = processBuilder.start()
        } catch (_: Exception) {
            return@withContext false
        }

        activeProcess = process

        var foundHops = 0
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line = reader.readLine()
            while (line != null && isActive) {
                val parsed = TracerouteOutputParser.parseNativeTracerouteLine(line, target, targetIp)
                if (parsed != null) {
                    foundHops++
                    val hop = TracerouteHop(
                        hopNumber = parsed.hopNumber,
                        ip = parsed.ip,
                        hostname = null,
                        rttMs = parsed.rttMs,
                        isDestinationReached = parsed.isDestinationReached,
                        isTimedOut = parsed.isTimedOut,
                        statusText = parsed.statusText,
                        alternateIps = parsed.alternateIps
                    )
                    withContext(Dispatchers.Main) {
                        onHop(hop)
                    }
                    if (parsed.isDestinationReached) break
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
            process.destroy()
        }

        return@withContext foundHops > 0
    }

    private fun buildPingCommand(probeTarget: String, ttl: Int, timeoutSec: Int, useLegacyPing6: Boolean): List<String> {
        val clampedTtl = ttl.coerceIn(1, 64).toString()
        val clampedTimeout = timeoutSec.coerceIn(1, 30).toString()
        return if (probeTarget.contains(":")) {
            if (useLegacyPing6) {
                listOf("ping6", "-n", "-c", "1", "-W", clampedTimeout, "-t", clampedTtl, probeTarget)
            } else {
                listOf("ping", "-6", "-n", "-c", "1", "-W", clampedTimeout, "-t", clampedTtl, probeTarget)
            }
        } else {
            listOf("ping", "-n", "-c", "1", "-W", clampedTimeout, "-t", clampedTtl, probeTarget)
        }
    }

    private suspend fun executeTtlProcessBuilderTraceroute(
        target: String,
        targetIp: String,
        maxHops: Int,
        timeoutSec: Int,
        onHop: suspend (TracerouteHop) -> Unit
    ) = withContext(Dispatchers.IO) {
        val safeProbeTarget = IpAddressValidator.sanitizeDiagnosticTarget(targetIp)
            ?: IpAddressValidator.sanitizeDiagnosticTarget(target)
            ?: return@withContext
        val isIpv6 = safeProbeTarget.contains(":")
        var preferLegacyPing6 = false

        for (ttl in 1..maxHops.coerceIn(1, 64)) {
            ensureActive()

            val startNanos = System.nanoTime()
            var hopIp: String? = null
            var parsedHostname: String? = null
            var rtt: Double? = null
            var reached = false
            var responded = false
            var errorStatus: String? = null
            var retryWithPing6 = false

            val commandsToTry = if (isIpv6 && !preferLegacyPing6) listOf(false, true) else listOf(preferLegacyPing6)
            for (useLegacyPing6 in commandsToTry) {
                val pb = ProcessBuilder(buildPingCommand(safeProbeTarget, ttl, timeoutSec, useLegacyPing6))
                    .redirectErrorStream(true)
                var process: Process? = null
                try {
                    process = pb.start()
                    activeProcess = process
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line = reader.readLine()
                    val outputLines = mutableListOf<String>()

                    while (line != null && isActive) {
                        outputLines.add(line)
                        val lower = line.lowercase(Locale.US)
                        if (isIpv6 && !useLegacyPing6 &&
                            (lower.contains("invalid option") || lower.contains("unknown option") || lower.contains("unrecognized option"))
                        ) {
                            retryWithPing6 = true
                        }
                        val parsed = TracerouteOutputParser.parsePingProbeLine(line)
                        if (parsed.responded) {
                            responded = true
                            if (parsed.hopIp != null) hopIp = parsed.hopIp
                            if (parsed.hostname != null) parsedHostname = parsed.hostname
                            if (parsed.rttMs != null) rtt = parsed.rttMs
                            if (parsed.isDestinationReached) reached = true
                            if (parsed.errorStatus != null) errorStatus = parsed.errorStatus
                        }
                        line = reader.readLine()
                    }
                    reader.close()
                    process.waitFor()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (isIpv6 && !useLegacyPing6) {
                        retryWithPing6 = true
                    }
                } finally {
                    process?.destroy()
                    activeProcess = null
                }

                if (retryWithPing6) {
                    preferLegacyPing6 = true
                    retryWithPing6 = false
                    continue
                }
                break
            }

            if (!responded) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
                val hop = TracerouteHop(
                    hopNumber = ttl,
                    ip = "*",
                    hostname = null,
                    rttMs = null,
                    isDestinationReached = false,
                    isTimedOut = true,
                    statusText = "Request Timed Out (${elapsedMs.toInt()} ms)"
                )
                withContext(Dispatchers.Main) {
                    onHop(hop)
                }
            } else {
                val finalIp = hopIp ?: "Unknown Hop"
                if (rtt == null) {
                    rtt = (System.nanoTime() - startNanos) / 1_000_000.0
                }
                val isTarget = reached && errorStatus == null &&
                    (finalIp == targetIp || finalIp == target || hopIp == null || reached)

                // Perform reverse DNS only for non-special/non-private routable hops when not already parsed
                var resolvedName: String? = parsedHostname
                if (resolvedName == null && finalIp != "Unknown Hop") {
                    val classification = IpAddressValidator.classifyIp(finalIp)
                    if (classification.isOperational &&
                        classification.category != IpAddressValidator.IpCategory.PRIVATE_NETWORK &&
                        classification.category != IpAddressValidator.IpCategory.CARRIER_GRADE_NAT &&
                        classification.category != IpAddressValidator.IpCategory.SPECIAL_USE
                    ) {
                        try {
                            val inet = InetAddress.getByName(finalIp)
                            val hostName = inet.hostName
                            if (hostName != finalIp) {
                                resolvedName = hostName
                            }
                        } catch (_: Exception) {}
                    }
                }

                val hop = TracerouteHop(
                    hopNumber = ttl,
                    ip = finalIp,
                    hostname = resolvedName,
                    rttMs = rtt,
                    isDestinationReached = isTarget,
                    isTimedOut = false,
                    statusText = TracerouteOutputParser.buildHopStatusText(finalIp, isTarget, errorStatus)
                )

                withContext(Dispatchers.Main) {
                    onHop(hop)
                }

                if (isTarget) {
                    break
                }
            }
        }
    }
}
