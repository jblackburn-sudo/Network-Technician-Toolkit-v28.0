package com.example.networktoolkit.iperf3

import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

enum class IPerfFailureCategory(val displayTitle: String) {
    DNS_RESOLUTION_FAILURE("DNS Resolution Failure"),
    TCP_CONNECTION_REFUSED("TCP Connection Refused"),
    CONNECTION_TIMEOUT("Connection Timeout"),
    NETWORK_UNREACHABLE("Network Unreachable"),
    NO_ACTIVE_NETWORK("No Active Network Connection"),
    POSSIBLE_FIREWALL_OR_FILTERING("Possible Firewall or Filtering"),
    INVALID_CONFIGURATION("Invalid Configuration"),
    IPERF3_PROTOCOL_INCOMPATIBILITY("iPerf3 Protocol Incompatibility"),
    SERVER_UNAVAILABLE("Server Unavailable"),
    UNEXPECTED_ERROR("Unexpected Error")
}

enum class IPerfTerminationReason(val displayTitle: String) {
    COMPLETED("Completed"),
    CONNECTION_FAILURE("Connection Failure"),
    PROTOCOL_NEGOTIATION_FAILURE("Protocol Negotiation Failure"),
    DATA_TRANSFER_STALL("Data-Transfer Stall"),
    PREMATURE_TERMINATION("Premature Termination"),
    USER_CANCELLED("User Cancelled")
}

object IPerf3Logger {
    private const val TAG = "IPerf3Engine"

    fun info(logs: MutableList<String>?, stage: String, message: String) {
        val line = "[INFO][$stage] $message"
        logs?.let { synchronized(it) { it.add(line) } }
        runCatching { android.util.Log.i(TAG, "[$stage] $message") }
    }

    fun warn(logs: MutableList<String>?, stage: String, message: String) {
        val line = "[WARN][$stage] $message"
        logs?.let { synchronized(it) { it.add(line) } }
        runCatching { android.util.Log.w(TAG, "[$stage] $message") }
    }

    fun error(logs: MutableList<String>?, stage: String, message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { " (${it::class.java.simpleName}: ${it.message})" }.orEmpty()
        val line = "[ERROR][$stage] $message$suffix"
        logs?.let { synchronized(it) { it.add(line) } }
        runCatching { android.util.Log.e(TAG, "[$stage] $message$suffix", throwable) }
    }
}

data class IPerfErrorDetails(
    val category: IPerfFailureCategory,
    val summaryMessage: String,
    val targetHost: String,
    val resolvedIp: String?,
    val port: Int?,
    val connectTimeoutMs: Int,
    val technicalDetails: String,
    val troubleshootingSteps: List<String>,
    val terminationReason: IPerfTerminationReason = defaultTerminationReasonFor(category)
) {
    companion object {
        fun defaultTerminationReasonFor(category: IPerfFailureCategory): IPerfTerminationReason = when (category) {
            IPerfFailureCategory.DNS_RESOLUTION_FAILURE,
            IPerfFailureCategory.TCP_CONNECTION_REFUSED,
            IPerfFailureCategory.CONNECTION_TIMEOUT,
            IPerfFailureCategory.NETWORK_UNREACHABLE,
            IPerfFailureCategory.NO_ACTIVE_NETWORK,
            IPerfFailureCategory.POSSIBLE_FIREWALL_OR_FILTERING -> IPerfTerminationReason.CONNECTION_FAILURE
            IPerfFailureCategory.IPERF3_PROTOCOL_INCOMPATIBILITY -> IPerfTerminationReason.PROTOCOL_NEGOTIATION_FAILURE
            IPerfFailureCategory.SERVER_UNAVAILABLE -> IPerfTerminationReason.PREMATURE_TERMINATION
            IPerfFailureCategory.INVALID_CONFIGURATION,
            IPerfFailureCategory.UNEXPECTED_ERROR -> IPerfTerminationReason.CONNECTION_FAILURE
        }
    }
}

class IPerfEngineException(
    val details: IPerfErrorDetails,
    cause: Throwable? = null
) : Exception(details.summaryMessage, cause)

class NoActiveNetworkException(message: String) : Exception(message)

class IPerfProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

class IPerfServerUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class IPerfServerIdleTimeoutException(message: String) : Exception(message)

enum class IPerfPresetCategory {
    LOCAL_EXAMPLE,
    PUBLIC_IPERF3
}

data class IPerfPresetHost(
    val label: String,
    val host: String,
    val port: Int = 5201,
    val category: IPerfPresetCategory,
    val description: String
)

data class IPerfValidationResult(
    val isValid: Boolean,
    val sanitizedHost: String = "",
    val port: Int = 5201,
    val errorDetails: IPerfErrorDetails? = null
)

object IPerf3Validator {

    const val EMPTY_HOST_MESSAGE =
        "An iPerf3 server address is required. Enter the IP address or hostname of a device running an iPerf3 server."

    private val ipv4SegmentRegex = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val hostnameLabelRegex = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")
    private val ipv6AllowedCharsRegex = Regex("""^[0-9A-Fa-f:.%A-Za-z0-9_-]+$""")

    fun isLoopbackOrLocalHost(host: String): Boolean {
        val clean = normalizeHostInput(host).lowercase(Locale.US)
        return clean == "localhost" || clean == "127.0.0.1" || clean == "::1"
    }

    fun normalizeHostInput(rawHost: String): String {
        val trimmed = rawHost.trim()
        return if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length > 2) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }

    fun isValidIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { ipv4SegmentRegex.matches(it) }
    }

    fun isValidIpv6Syntax(rawHost: String): Boolean {
        val candidate = normalizeHostInput(rawHost)
        if (!candidate.contains(":")) return false
        if (!ipv6AllowedCharsRegex.matches(candidate)) return false

        // Separate optional zone index (e.g., fe80::1%wlan0)
        val addrPart = candidate.substringBefore("%")
        val zonePart = if (candidate.contains("%")) candidate.substringAfter("%") else null
        if (zonePart != null && zonePart.isEmpty()) return false

        if (addrPart.contains(":::")) return false
        val doubleColonCount = addrPart.split("::").size - 1
        if (doubleColonCount > 1) return false

        val halves = addrPart.split("::")
        val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(":")
        val rightGroups = if (halves.size > 1 && halves[1].isNotEmpty()) halves[1].split(":") else emptyList()

        // Optional embedded IPv4 tail in last group (e.g., ::ffff:192.0.2.1)
        val hasEmbeddedIpv4 = (rightGroups.lastOrNull()?.contains(".") == true) ||
            (doubleColonCount == 0 && leftGroups.lastOrNull()?.contains(".") == true)

        val allGroupsToValidate = if (hasEmbeddedIpv4) {
            val tail = if (rightGroups.isNotEmpty()) rightGroups.last() else leftGroups.last()
            if (!isValidIpv4(tail)) return false
            val withoutTail = if (rightGroups.isNotEmpty()) {
                leftGroups + rightGroups.dropLast(1)
            } else {
                leftGroups.dropLast(1)
            }
            if (doubleColonCount == 0 && withoutTail.size != 6) return false
            if (doubleColonCount == 1 && withoutTail.size >= 6) return false
            withoutTail
        } else {
            val combined = leftGroups + rightGroups
            if (doubleColonCount == 0 && combined.size != 8) return false
            if (doubleColonCount == 1 && combined.size >= 8) return false
            combined
        }

        return allGroupsToValidate.all { group ->
            group.length in 1..4 && group.all { ch ->
                ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            }
        }
    }

    fun isValidHostnameSyntax(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.any { it.isWhitespace() || it == '/' || it == '@' || it == '?' || it == '#' || it == ':' }) {
            return false
        }
        val withoutTrailingDot = host.removeSuffix(".")
        if (withoutTrailingDot.isEmpty() || withoutTrailingDot.contains("..")) return false
        val labels = withoutTrailingDot.split(".")
        if (labels.any { !hostnameLabelRegex.matches(it) }) return false
        // Avoid accepting malformed dotted numeric strings like 999.999.999.999 as hostnames
        if (labels.size == 4 && labels.all { it.all(Char::isDigit) }) return false
        // TLD or single-label name must contain at least one letter unless valid IPv4/IPv6
        if (labels.last().all { it.isDigit() }) return false
        return true
    }

    fun isValidHostSyntax(rawHost: String): Boolean {
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("[") || trimmed.contains(":")) {
            return isValidIpv6Syntax(trimmed)
        }
        return isValidIpv4(trimmed) || isValidHostnameSyntax(trimmed)
    }

    fun validateClientConfig(
        rawHost: String,
        rawPort: String,
        durationSeconds: Int,
        parallelStreams: Int,
        connectTimeoutMs: Int
    ): IPerfValidationResult {
        val trimmedHost = rawHost.trim()
        val parsedPort = rawPort.trim().toIntOrNull()

        if (trimmedHost.isEmpty()) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = EMPTY_HOST_MESSAGE,
                    targetHost = "(empty)",
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: targetHost is empty.",
                    troubleshootingSteps = listOf(
                        "Enter the IPv4/IPv6 address or hostname of a device running an iPerf3 server (e.g., iperf3 -s).",
                        "Or select a local gateway/LAN example or a public iPerf3 server below."
                    )
                )
            )
        }

        if (trimmedHost.contains("://") || trimmedHost.contains("/")) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Enter only an IP address or hostname without 'http://' or URL paths.",
                    targetHost = trimmedHost,
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: URL schemes and paths are not valid iPerf3 hostnames.",
                    troubleshootingSteps = listOf(
                        "Remove any http:// or https:// prefix and trailing path.",
                        "iPerf3 connects directly over TCP/UDP sockets, not web HTTP URLs."
                    )
                )
            )
        }

        if (!isValidHostSyntax(trimmedHost)) {
            val extraHint = if (trimmedHost.count { it == ':' } == 1) {
                " Put the port number in the Port field rather than after ':' in Host."
            } else {
                ""
            }
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid target server address '$trimmedHost'.$extraHint",
                    targetHost = trimmedHost,
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: '$trimmedHost' is not a valid IPv4, IPv6, or DNS hostname.",
                    troubleshootingSteps = listOf(
                        "Verify IPv4 octets are 0–255 (e.g., 192.168.1.50), or enclose IPv6 addresses cleanly.",
                        "For hostnames, use valid domain labels without spaces or invalid punctuation."
                    )
                )
            )
        }

        if (parsedPort == null || parsedPort !in 1..65535) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid port '${rawPort.trim()}'. Enter a TCP/UDP port between 1 and 65535.",
                    targetHost = normalizeHostInput(trimmedHost),
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: port '${rawPort.trim()}' is outside valid range 1..65535.",
                    troubleshootingSteps = listOf(
                        "Default iPerf3 server port is 5201.",
                        "Check what port was passed to 'iperf3 -s -p <port>' on the server."
                    )
                )
            )
        }

        if (parallelStreams !in 1..8) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid parallel stream count ($parallelStreams). Must be between 1 and 8.",
                    targetHost = normalizeHostInput(trimmedHost),
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: parallelStreams=$parallelStreams out of range 1..8.",
                    troubleshootingSteps = listOf("Select between 1 and 8 parallel streams.")
                )
            )
        }

        if (durationSeconds !in 1..300) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid test duration (${durationSeconds}s). Must be between 1 and 300 seconds.",
                    targetHost = normalizeHostInput(trimmedHost),
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: durationSeconds=$durationSeconds out of range 1..300.",
                    troubleshootingSteps = listOf("Set test duration between 1 and 300 seconds.")
                )
            )
        }

        if (connectTimeoutMs !in 1000..60000) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid connection timeout (${connectTimeoutMs} ms). Must be between 1,000 and 60,000 ms.",
                    targetHost = normalizeHostInput(trimmedHost),
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = connectTimeoutMs,
                    technicalDetails = "Validation error: connectTimeoutMs=$connectTimeoutMs out of range 1000..60000.",
                    troubleshootingSteps = listOf("Choose a connection timeout between 1s and 60s.")
                )
            )
        }

        return IPerfValidationResult(
            isValid = true,
            sanitizedHost = normalizeHostInput(trimmedHost),
            port = parsedPort
        )
    }

    fun validateServerConfig(rawPort: String): IPerfValidationResult {
        val parsedPort = rawPort.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Invalid listening port '${rawPort.trim()}'. Enter a port between 1 and 65535.",
                    targetHost = "Local Server",
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = 0,
                    technicalDetails = "Validation error: listening port '${rawPort.trim()}' is outside 1..65535.",
                    troubleshootingSteps = listOf("Use standard port 5201 or another unprivileged port between 1024 and 65535.")
                )
            )
        }
        if (parsedPort < 1024) {
            return IPerfValidationResult(
                isValid = false,
                errorDetails = IPerfErrorDetails(
                    category = IPerfFailureCategory.INVALID_CONFIGURATION,
                    summaryMessage = "Port $parsedPort is a privileged system port (<1024) and cannot be bound by unprivileged Android apps.",
                    targetHost = "Local Server",
                    resolvedIp = null,
                    port = parsedPort,
                    connectTimeoutMs = 0,
                    technicalDetails = "Validation error: unprivileged Android sockets require listening ports 1024..65535.",
                    troubleshootingSteps = listOf("Use port 5201 (standard iPerf3) or any port between 1024 and 65535.")
                )
            )
        }
        return IPerfValidationResult(isValid = true, sanitizedHost = "0.0.0.0", port = parsedPort)
    }
}

object IPerf3ErrorClassifier {

    fun classify(
        throwable: Throwable,
        targetHost: String,
        resolvedIp: String?,
        port: Int?,
        connectTimeoutMs: Int
    ): IPerfErrorDetails {
        if (throwable is IPerfEngineException) return throwable.details

        val msg = (throwable.message ?: "").trim()
        val lower = msg.lowercase(Locale.US)
        val excName = throwable::class.java.simpleName
        val techDetails = if (msg.isNotEmpty()) "$excName: $msg" else excName

        return when {
            throwable is NoActiveNetworkException -> IPerfErrorDetails(
                category = IPerfFailureCategory.NO_ACTIVE_NETWORK,
                summaryMessage = "No active network connection is available to reach $targetHost.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Connect this device to Wi-Fi, Ethernet, or cellular data.",
                    "Confirm the target iPerf3 server is reachable over the active interface."
                )
            )

            throwable is UnknownHostException -> IPerfErrorDetails(
                category = IPerfFailureCategory.DNS_RESOLUTION_FAILURE,
                summaryMessage = "Could not resolve hostname '$targetHost' through DNS.",
                targetHost = targetHost,
                resolvedIp = null,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Check the spelling of '$targetHost' or enter its IPv4/IPv6 address directly.",
                    "Verify DNS / Private DNS connectivity in the DNS Lookup tool.",
                    "Local LAN hostnames (such as .local or router hostnames) may require an IP address on Android."
                )
            )

            throwable is IPerfProtocolException -> IPerfErrorDetails(
                category = IPerfFailureCategory.IPERF3_PROTOCOL_INCOMPATIBILITY,
                summaryMessage = "Connected to ${resolvedIp ?: targetHost}:${port ?: "-"}, but the endpoint did not respond with a valid iPerf3 handshake.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Confirm that an iPerf3 server ('iperf3 -s') is running on port ${port ?: 5201} rather than HTTP, SSH, or another service.",
                    "Ordinary websites (e.g., google.com, Cloudflare, Speedtest.net) do not run iPerf3 servers.",
                    "Ensure the remote server is iPerf3-compatible (not legacy iPerf2)."
                )
            )

            throwable is IPerfServerUnavailableException -> IPerfErrorDetails(
                category = IPerfFailureCategory.SERVER_UNAVAILABLE,
                summaryMessage = msg.ifEmpty { "The iPerf3 server at $targetHost:${port ?: "-"} is currently busy or unavailable." },
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Public iPerf3 servers only handle one client at a time and may return Access Denied while busy.",
                    "Wait a few seconds and retry, or test against your own local 'iperf3 -s' server."
                )
            )

            throwable is PortUnreachableException || throwable is SecurityException ||
                lower.contains("eacces") || lower.contains("eperm") || lower.contains("filtered") -> IPerfErrorDetails(
                category = IPerfFailureCategory.POSSIBLE_FIREWALL_OR_FILTERING,
                summaryMessage = "Connection to $targetHost:${port ?: "-"} was blocked or returned an ICMP/permission rejection.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "A local host firewall, router ACL, or AP client isolation may be filtering port ${port ?: 5201}.",
                    "Check firewall rules on the target server (e.g., Windows Defender Firewall, ufw, iptables)."
                )
            )

            throwable is NoRouteToHostException ||
                lower.contains("enetunreach") || lower.contains("ehostunreach") ||
                lower.contains("network is unreachable") || lower.contains("no route to host") -> IPerfErrorDetails(
                category = IPerfFailureCategory.NETWORK_UNREACHABLE,
                summaryMessage = "Network or host is unreachable for $targetHost:${port ?: "-"}.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Confirm both devices are on the same subnet or have a valid route/VPN tunnel.",
                    "Check whether VPN routing is blocking local LAN addresses or IPv6 connectivity is missing."
                )
            )

            throwable is ConnectException &&
                (lower.contains("econnrefused") || lower.contains("connection refused")) -> IPerfErrorDetails(
                category = IPerfFailureCategory.TCP_CONNECTION_REFUSED,
                summaryMessage = "TCP connection was actively refused by ${resolvedIp ?: targetHost} on port ${port ?: "-"}.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "The host responded, which usually means no iPerf3 server is listening on port ${port ?: 5201}.",
                    "Start an iPerf3 server on the target device (e.g., 'iperf3 -s -p ${port ?: 5201}').",
                    "Verify the listening port number matches the server configuration."
                )
            )

            throwable is SocketTimeoutException || lower.contains("timed out") -> IPerfErrorDetails(
                category = IPerfFailureCategory.CONNECTION_TIMEOUT,
                summaryMessage = "Timed out after ${String.format(Locale.US, "%,d", connectTimeoutMs)} ms waiting for ${resolvedIp ?: targetHost}:${port ?: "-"} to respond.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Verify the target IP/hostname is online and running an iPerf3 server on port ${port ?: 5201}.",
                    "A host firewall, NAT, or AP client isolation could silently drop SYN packets on port ${port ?: 5201}.",
                    "If testing over high-latency links, try increasing Connection Timeout."
                )
            )

            throwable is BindException -> IPerfErrorDetails(
                category = IPerfFailureCategory.SERVER_UNAVAILABLE,
                summaryMessage = "Could not bind listening socket on port ${port ?: "-"}. The port may already be in use.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Stop any existing server session or choose a different listening port (e.g., 5202)."
                )
            )

            throwable is SocketException &&
                (lower.contains("econnreset") || lower.contains("connection reset") ||
                    lower.contains("epipe") || lower.contains("broken pipe")) -> IPerfErrorDetails(
                category = IPerfFailureCategory.SERVER_UNAVAILABLE,
                summaryMessage = "Connection to $targetHost:${port ?: "-"} was reset or closed by the remote host.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "The remote iPerf3 server closed or reset the session unexpectedly.",
                    "Confirm the server is idle, supports TCP iPerf3, and did not crash or reject the client."
                ),
                terminationReason = IPerfTerminationReason.PREMATURE_TERMINATION
            )

            else -> IPerfErrorDetails(
                category = IPerfFailureCategory.UNEXPECTED_ERROR,
                summaryMessage = "iPerf3 operation failed for $targetHost:${port ?: "-"}.",
                targetHost = targetHost,
                resolvedIp = resolvedIp,
                port = port,
                connectTimeoutMs = connectTimeoutMs,
                technicalDetails = techDetails,
                troubleshootingSteps = listOf(
                    "Inspect the technical details below and verify target host, port, and network connectivity."
                )
            )
        }
    }
}

object IPerf3Presets {
    val localExamples: List<IPerfPresetHost> = listOf(
        IPerfPresetHost(
            label = "192.168.1.100",
            host = "192.168.1.100",
            port = 5201,
            category = IPerfPresetCategory.LOCAL_EXAMPLE,
            description = "Example LAN workstation IP — requires an active 'iperf3 -s' server on that device."
        ),
        IPerfPresetHost(
            label = "10.0.0.100",
            host = "10.0.0.100",
            port = 5201,
            category = IPerfPresetCategory.LOCAL_EXAMPLE,
            description = "Example 10.x LAN host IP — requires an active 'iperf3 -s' server on that device."
        )
    )

    val publicServers: List<IPerfPresetHost> = listOf(
        IPerfPresetHost(
            label = "iperf.he.net",
            host = "iperf.he.net",
            port = 5201,
            category = IPerfPresetCategory.PUBLIC_IPERF3,
            description = "External public iPerf3 server (Hurricane Electric • Port 5201 • Single-client / no uptime guarantee)."
        ),
        IPerfPresetHost(
            label = "bouygues.iperf.fr",
            host = "bouygues.iperf.fr",
            port = 5201,
            category = IPerfPresetCategory.PUBLIC_IPERF3,
            description = "External public iPerf3 server (Bouygues Telecom • Port 5201 • External availability not guaranteed)."
        )
    )
}
