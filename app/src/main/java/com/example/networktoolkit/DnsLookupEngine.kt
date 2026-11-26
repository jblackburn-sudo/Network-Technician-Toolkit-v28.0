package com.example.networktoolkit

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.example.networktoolkit.diagnostics.IpAddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class DnsRecord(
    val type: String, // A, AAAA, MX, TXT, NS, CNAME, PTR
    val name: String,
    val value: String,
    val ttl: Int? = null,
    val priority: Int? = null
)

enum class DnsFailureCategory(val displayTitle: String) {
    NO_NETWORK_CONNECTIVITY("No Network Connectivity"),
    INVALID_INPUT("Invalid Domain or IP"),
    TIMEOUT("DNS Lookup Timed Out"),
    NXDOMAIN("Non-Existent Domain (NXDOMAIN)"),
    SERVFAIL("DNS Server Failure (SERVFAIL)"),
    RESOLUTION_FAILED("DNS Resolution Failed")
}

data class DnsErrorDetails(
    val category: DnsFailureCategory,
    val message: String,
    val query: String,
    val queryType: String,
    val technicalDetails: String,
    val troubleshootingSteps: List<String>
)

class DnsLookupException(
    val details: DnsErrorDetails,
    cause: Throwable? = null
) : Exception(details.message, cause)

data class DnsLookupResult(
    val query: String,
    val queryType: String = "ALL",
    val responseTimeMs: Long,
    val records: List<DnsRecord>,
    val serverUsed: String,
    val resolverSummary: String = serverUsed,
    val activeTransport: String = "Unknown",
    val networkAssignedDnsServers: List<String> = emptyList(),
    val privateDnsInfo: PrivateDnsInfo? = null,
    val authoritative: Boolean = false,
    val usedPublicDoh: Boolean = false,
    val privacyNote: String = ""
)

object DnsLookupEngine {

    private val ipv4Regex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val INTERNAL_SUFFIXES = setOf(
        "local",
        "lan",
        "internal",
        "home",
        "home.arpa",
        "corp",
        "localdomain",
        "intranet",
        "private",
        "invalid",
        "test",
        "example"
    )

    internal val DEFAULT_DOH_ENDPOINTS = listOf(
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query"
    )

    internal data class DohQueryOutcome(
        val rcode: Int? = null, // 0 = NOERROR, 2 = SERVFAIL, 3 = NXDOMAIN, 5 = REFUSED
        val recordsAdded: Int = 0,
        val timedOut: Boolean = false,
        val errorMessage: String? = null
    )

    internal data class DohPolicyDecision(
        val allowDoh: Boolean,
        val typesToQuery: List<String>,
        val privacyNote: String,
        val skipReasonForUnavailableTypes: String? = null
    )

    internal fun isLocalOrInternalHostname(target: String): Boolean {
        val clean = target.trim().trimEnd('.').lowercase(Locale.US)
        if (clean.isEmpty()) return true
        if (clean == "localhost") return true
        if (ipv4Regex.matches(clean) || clean.contains(":")) {
            return isPrivateOrLocalIp(clean)
        }
        if (clean.endsWith(".in-addr.arpa") || clean.endsWith(".ip6.arpa")) {
            return true
        }
        if (!clean.contains(".")) {
            // Single-label unqualified hostnames (e.g. nas, router, fileserver)
            return true
        }
        return INTERNAL_SUFFIXES.any { suffix ->
            clean == suffix || clean.endsWith(".$suffix")
        }
    }

    private fun isPrivateOrLocalIp(ip: String): Boolean {
        if (ip == "::1" || ip == "0.0.0.0") return true
        if (ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true
        if (!ipv4Regex.matches(ip)) return false
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b, _, _) = parts
        return a == 10 ||
            a == 127 ||
            a == 0 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    internal fun isValidReversePtrHostname(rawHostName: String?, queriedIp: String, resolvedHostIp: String?): Boolean {
        val cleanHost = rawHostName?.trim()?.trimEnd('.') ?: return false
        if (cleanHost.isEmpty()) return false
        if (cleanHost.equals(queriedIp, ignoreCase = true)) return false
        if (!resolvedHostIp.isNullOrBlank() && cleanHost.equals(resolvedHostIp, ignoreCase = true)) return false
        if (ipv4Regex.matches(cleanHost) || cleanHost.contains(":")) return false
        return true
    }

    @VisibleForTesting
    internal fun evaluateDohPolicy(
        cleanTarget: String,
        normalizedType: String,
        systemAOrAaaaRecordsFound: Boolean,
        env: ActiveDnsEnvironment?,
        allowPublicDohFallback: Boolean
    ): DohPolicyDecision {
        if (isLocalOrInternalHostname(cleanTarget)) {
            return DohPolicyDecision(
                allowDoh = false,
                typesToQuery = emptyList(),
                privacyNote = "Local/internal hostname stays strictly on Android system/network DNS and is never sent to external DoH servers.",
                skipReasonForUnavailableTypes = "Internal or single-label hostnames are never queried against external public DoH servers (dns.google / cloudflare-dns.com)."
            )
        }

        val pdnsStatus = env?.privateDnsInfo?.status
        val isStrictPrivateDns = pdnsStatus == PrivateDnsStatus.HOSTNAME_ACTIVE ||
            pdnsStatus == PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE

        if (!allowPublicDohFallback) {
            val note = if (isStrictPrivateDns) {
                "Strict Private DNS (${env?.privateDnsInfo?.configuredHostname ?: "DoT"}) respected — external public DoH servers were not contacted."
            } else {
                "System DNS Only mode — queries use active network/Private DNS only. Enable Public DoH Opt-In to query MX/TXT/NS/CNAME."
            }
            val reason = if (isStrictPrivateDns) {
                "Strict Private DNS mode is active; external public DoH fallback is disabled to avoid bypassing your configured DoT resolver."
            } else {
                "Android's system InetAddress resolver does not expose $normalizedType records, and Public DoH Opt-In is currently disabled."
            }
            return DohPolicyDecision(
                allowDoh = false,
                typesToQuery = emptyList(),
                privacyNote = note,
                skipReasonForUnavailableTypes = reason
            )
        }

        val typesToQuery = when (normalizedType) {
            "ALL" -> if (systemAOrAaaaRecordsFound) {
                listOf("MX", "TXT", "NS", "CNAME")
            } else {
                listOf("A", "AAAA", "MX", "TXT", "NS", "CNAME")
            }
            "MX", "TXT", "NS", "CNAME" -> listOf(normalizedType)
            "A", "AAAA" -> if (!systemAOrAaaaRecordsFound) listOf(normalizedType) else emptyList()
            else -> emptyList()
        }

        val note = if (isStrictPrivateDns) {
            "Public DoH explicitly enabled by user (may resolve via external DoH alongside Strict Private DNS for requested records)."
        } else {
            "Public DoH explicitly enabled by user for extended records / public comparison."
        }
        return DohPolicyDecision(
            allowDoh = typesToQuery.isNotEmpty(),
            typesToQuery = typesToQuery,
            privacyNote = note
        )
    }

    suspend fun lookup(
        target: String,
        queryType: String = "ALL",
        context: Context? = null,
        dnsEnvironment: ActiveDnsEnvironment? = null,
        allowPublicDohFallback: Boolean = false
    ): Result<DnsLookupResult> = lookupInternal(
        target = target,
        queryType = queryType,
        context = context,
        dnsEnvironment = dnsEnvironment,
        allowPublicDohFallback = allowPublicDohFallback,
        dohEndpoints = DEFAULT_DOH_ENDPOINTS
    )

    @VisibleForTesting
    internal suspend fun lookupInternal(
        target: String,
        queryType: String = "ALL",
        context: Context? = null,
        dnsEnvironment: ActiveDnsEnvironment? = null,
        allowPublicDohFallback: Boolean = false,
        dohEndpoints: List<String> = DEFAULT_DOH_ENDPOINTS,
        reverseResolver: (String) -> InetAddress = { InetAddress.getByName(it) },
        forwardResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): Result<DnsLookupResult> = withContext(Dispatchers.IO) {
        val env = dnsEnvironment ?: context?.let { NetworkDnsInspector.inspect(it) }
        val normalizedType = queryType.trim().uppercase(Locale.US).ifEmpty { "ALL" }
        val rawCandidate = target.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")

        val cleanTarget = IpAddressValidator.sanitizeDiagnosticTarget(rawCandidate)
        if (cleanTarget == null) {
            return@withContext Result.failure(
                DnsLookupException(
                    DnsErrorDetails(
                        category = DnsFailureCategory.INVALID_INPUT,
                        message = "Enter a valid hostname (e.g., example.com) or IP address.",
                        query = rawCandidate.ifBlank { "(empty)" },
                        queryType = normalizedType,
                        technicalDetails = "Validation error: malformed domain/IP or unsafe characters in DNS target.",
                        troubleshootingSteps = listOf("Enter a valid domain name or IPv4/IPv6 address without spaces or special characters.")
                    )
                )
            )
        }

        val isLoopbackTarget = cleanTarget.equals("localhost", ignoreCase = true) ||
            cleanTarget == "127.0.0.1" ||
            cleanTarget == "::1"

        if (env != null && !env.isConnected && !isLoopbackTarget) {
            return@withContext Result.failure(
                DnsLookupException(
                    DnsErrorDetails(
                        category = DnsFailureCategory.NO_NETWORK_CONNECTIVITY,
                        message = "No active network connection. Connect to Wi-Fi, cellular, or Ethernet before running DNS diagnostics.",
                        query = cleanTarget,
                        queryType = normalizedType,
                        technicalDetails = "ConnectivityManager reports no active network (offline state).",
                        troubleshootingSteps = listOf(
                            "Enable Wi-Fi or mobile data and verify connectivity.",
                            "Offline status is distinct from NXDOMAIN or resolver failures."
                        )
                    )
                )
            )
        }

        val startTime = SystemClock.elapsedRealtime()
        val records = mutableListOf<DnsRecord>()
        val isIp = ipv4Regex.matches(cleanTarget) || cleanTarget.contains(":")
        val resolverLabel = buildSystemResolverLabel(env)

        if (isIp) {
            try {
                coroutineContext.ensureActive()
                val addr = reverseResolver(cleanTarget)
                val rawHostName = addr.canonicalHostName?.trim()?.trimEnd('.')
                val validPtr = isValidReversePtrHostname(rawHostName, cleanTarget, addr.hostAddress)
                val rtt = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)

                if (normalizedType == "PTR" && !validPtr) {
                    return@withContext Result.failure(
                        DnsLookupException(
                            DnsErrorDetails(
                                category = DnsFailureCategory.RESOLUTION_FAILED,
                                message = "No reverse PTR hostname found for '$cleanTarget'.",
                                query = cleanTarget,
                                queryType = normalizedType,
                                technicalDetails = "System reverse resolver returned '${rawHostName ?: cleanTarget}' without a valid PTR hostname.",
                                troubleshootingSteps = listOf(
                                    "Many ISP, residential, and anycast IPs do not publish a reverse PTR record.",
                                    "If querying an internal IP, verify local LAN/VPN reverse DNS zone delegation."
                                )
                            )
                        )
                    )
                }

                if ((normalizedType == "ALL" || normalizedType == "PTR") && validPtr && rawHostName != null) {
                    records.add(
                        DnsRecord(
                            type = "PTR",
                            name = cleanTarget,
                            value = rawHostName,
                            ttl = null
                        )
                    )
                }
                if (normalizedType != "PTR") {
                    if ((normalizedType == "ALL" || normalizedType == "A") && addr is Inet4Address) {
                        records.add(DnsRecord(type = "A", name = cleanTarget, value = addr.hostAddress ?: cleanTarget, ttl = null))
                    } else if ((normalizedType == "ALL" || normalizedType == "AAAA") && addr is Inet6Address) {
                        records.add(DnsRecord(type = "AAAA", name = cleanTarget, value = addr.hostAddress ?: cleanTarget, ttl = null))
                    }
                }

                val summary = if (validPtr) {
                    "Reverse DNS via Android System Resolver ($resolverLabel)"
                } else {
                    "Reverse PTR unavailable (resolver returned raw IP only) — $resolverLabel"
                }

                return@withContext Result.success(
                    DnsLookupResult(
                        query = cleanTarget,
                        queryType = normalizedType,
                        responseTimeMs = rtt,
                        records = records,
                        serverUsed = resolverLabel,
                        resolverSummary = summary,
                        activeTransport = env?.activeTransport ?: NetworkConnectionIdentifier.CONNECTION_UNKNOWN,
                        networkAssignedDnsServers = env?.networkAssignedDnsServers ?: emptyList(),
                        privateDnsInfo = env?.privateDnsInfo,
                        usedPublicDoh = false,
                        privacyNote = "Reverse DNS uses Android System Resolver only (no external DoH query sent)."
                    )
                )
            } catch (e: Exception) {
                return@withContext Result.failure(
                    classifyFailure(e, cleanTarget, normalizedType, env, dohOutcomes = emptyList())
                )
            }
        }

        // Standard Forward DNS Lookup via Android System Resolver
        var systemResolverException: Throwable? = null
        var usedSystemResolver = false
        if (normalizedType in listOf("ALL", "A", "AAAA")) {
            try {
                coroutineContext.ensureActive()
                val addresses = forwardResolver(cleanTarget)
                usedSystemResolver = true
                for (addr in addresses) {
                    if (addr is Inet4Address && (normalizedType == "ALL" || normalizedType == "A")) {
                        records.add(
                            DnsRecord(
                                type = "A",
                                name = cleanTarget,
                                value = addr.hostAddress ?: "",
                                ttl = null
                            )
                        )
                    } else if (addr is Inet6Address && (normalizedType == "ALL" || normalizedType == "AAAA")) {
                        records.add(
                            DnsRecord(
                                type = "AAAA",
                                name = cleanTarget,
                                value = addr.hostAddress ?: "",
                                ttl = null
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                systemResolverException = e
            }
        }

        // Privacy-gated Public DoH evaluation (strictly opt-in and never for local/internal hostnames)
        val dohPolicy = evaluateDohPolicy(
            cleanTarget = cleanTarget,
            normalizedType = normalizedType,
            systemAOrAaaaRecordsFound = records.any { it.type == "A" || it.type == "AAAA" },
            env = env,
            allowPublicDohFallback = allowPublicDohFallback
        )

        val dohOutcomes = mutableListOf<DohQueryOutcome>()
        var dohEndpointUsed: String? = null

        if (dohPolicy.allowDoh && dohPolicy.typesToQuery.isNotEmpty()) {
            for (endpoint in dohEndpoints) {
                coroutineContext.ensureActive()
                var endpointResponded = false
                for (qType in dohPolicy.typesToQuery) {
                    coroutineContext.ensureActive()
                    val outcome = queryDohRecords(endpoint, cleanTarget, qType, records)
                    dohOutcomes.add(outcome)
                    if (outcome.rcode != null) {
                        endpointResponded = true
                        if (outcome.recordsAdded > 0 || dohEndpointUsed == null) {
                            dohEndpointUsed = endpointHostLabel(endpoint)
                        }
                    }
                }
                if (endpointResponded) break
            }
        }

        val rtt = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)

        if (records.isEmpty()) {
            val validEmptyType = dohOutcomes.any { it.rcode == 0 } && normalizedType !in listOf("ALL", "A")
            if (validEmptyType) {
                return@withContext Result.success(
                    DnsLookupResult(
                        query = cleanTarget,
                        queryType = normalizedType,
                        responseTimeMs = rtt,
                        records = emptyList(),
                        serverUsed = dohEndpointUsed ?: resolverLabel,
                        resolverSummary = "Queried ${dohEndpointUsed ?: resolverLabel} — 0 $normalizedType records published (NOERROR)",
                        activeTransport = env?.activeTransport ?: NetworkConnectionIdentifier.CONNECTION_UNKNOWN,
                        networkAssignedDnsServers = env?.networkAssignedDnsServers ?: emptyList(),
                        privateDnsInfo = env?.privateDnsInfo,
                        usedPublicDoh = dohEndpointUsed != null,
                        privacyNote = dohPolicy.privacyNote
                    )
                )
            }

            if (systemResolverException == null && !dohPolicy.allowDoh && dohPolicy.skipReasonForUnavailableTypes != null) {
                return@withContext Result.failure(
                    DnsLookupException(
                        DnsErrorDetails(
                            category = DnsFailureCategory.RESOLUTION_FAILED,
                            message = dohPolicy.skipReasonForUnavailableTypes,
                            query = cleanTarget,
                            queryType = normalizedType,
                            technicalDetails = "External DoH query skipped (${dohPolicy.privacyNote}).",
                            troubleshootingSteps = if (isLocalOrInternalHostname(cleanTarget)) {
                                listOf("Query A, AAAA, or ALL to resolve internal hostnames via the local network/VPN resolver.")
                            } else {
                                listOf(
                                    "Toggle 'Public DoH Opt-In' on if you want to query MX/TXT/NS/CNAME via dns.google / cloudflare-dns.com.",
                                    "Alternatively select A, AAAA, or ALL to resolve via Android System Resolver."
                                )
                            }
                        )
                    )
                )
            }

            return@withContext Result.failure(
                classifyFailure(systemResolverException, cleanTarget, normalizedType, env, dohOutcomes)
            )
        }

        val distinctRecords = records.distinctBy { "${it.type}_${it.value}" }
        val summaryText = buildString {
            if (usedSystemResolver && distinctRecords.any { it.type == "A" || it.type == "AAAA" }) {
                append("A/AAAA via Android System Resolver ($resolverLabel)")
                if (dohEndpointUsed != null) {
                    append("; extended records via $dohEndpointUsed")
                }
            } else if (dohEndpointUsed != null) {
                append("$normalizedType records via $dohEndpointUsed")
            } else {
                append(resolverLabel)
            }
        }

        Result.success(
            DnsLookupResult(
                query = cleanTarget,
                queryType = normalizedType,
                responseTimeMs = rtt,
                records = distinctRecords,
                serverUsed = resolverLabel,
                resolverSummary = summaryText,
                activeTransport = env?.activeTransport ?: NetworkConnectionIdentifier.CONNECTION_UNKNOWN,
                networkAssignedDnsServers = env?.networkAssignedDnsServers ?: emptyList(),
                privateDnsInfo = env?.privateDnsInfo,
                usedPublicDoh = dohEndpointUsed != null,
                privacyNote = dohPolicy.privacyNote
            )
        )
    }

    private fun endpointHostLabel(endpoint: String): String {
        return when {
            endpoint.contains("dns.google") -> "dns.google (DoH)"
            endpoint.contains("cloudflare-dns.com") -> "cloudflare-dns.com (DoH)"
            else -> runCatching { "${URL(endpoint).host} (DoH)" }.getOrDefault("$endpoint (DoH)")
        }
    }

    internal fun buildSystemResolverLabel(env: ActiveDnsEnvironment?): String {
        if (env == null) return "Android System Resolver"
        val pdns = env.privateDnsInfo
        return when (pdns.status) {
            PrivateDnsStatus.HOSTNAME_ACTIVE -> "Private DNS (${pdns.configuredHostname ?: "Strict DoT"})"
            PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC -> {
                if (pdns.statusLabel.contains("Active", ignoreCase = true)) {
                    "Android Automatic Private DNS (DoT)"
                } else {
                    env.networkAssignedDnsServers.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ")
                        ?.let { "Network DNS ($it)" }
                        ?: "Android System DNS"
                }
            }
            PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE ->
                "Private DNS (${pdns.configuredHostname ?: "Strict"} — Validation Issue)"
            PrivateDnsStatus.DISABLED, PrivateDnsStatus.UNAVAILABLE ->
                env.networkAssignedDnsServers.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?.let { "Network DNS ($it)" }
                    ?: "Android System DNS"
        }
    }

    internal fun classifyFailure(
        systemThrowable: Throwable?,
        query: String,
        queryType: String,
        env: ActiveDnsEnvironment?,
        dohOutcomes: List<DohQueryOutcome>
    ): DnsLookupException {
        if (env != null && !env.isConnected) {
            return DnsLookupException(
                DnsErrorDetails(
                    category = DnsFailureCategory.NO_NETWORK_CONNECTIVITY,
                    message = "No active network connection. Connect to Wi-Fi or cellular data before performing DNS diagnostics.",
                    query = query,
                    queryType = queryType,
                    technicalDetails = "ConnectivityManager activeNetwork is null.",
                    troubleshootingSteps = listOf(
                        "Connect to Wi-Fi, Cellular, or Ethernet and retry."
                    )
                ),
                systemThrowable
            )
        }

        val hasNxdomain = dohOutcomes.any { it.rcode == 3 }
        val hasServfail = dohOutcomes.any { it.rcode == 2 || it.rcode == 5 }
        val hasTimeout = systemThrowable is SocketTimeoutException || dohOutcomes.any { it.timedOut }

        return when {
            hasNxdomain -> DnsLookupException(
                DnsErrorDetails(
                    category = DnsFailureCategory.NXDOMAIN,
                    message = "Domain '$query' does not exist (DNS RCODE 3: NXDOMAIN).",
                    query = query,
                    queryType = queryType,
                    technicalDetails = "Resolver returned NXDOMAIN (RCODE 3)${systemThrowable?.message?.let { "; System: $it" } ?: ""}.",
                    troubleshootingSteps = listOf(
                        "Check the domain spelling.",
                        "If this is an internal LAN/corporate hostname, public DNS servers will return NXDOMAIN."
                    )
                ),
                systemThrowable
            )

            hasServfail -> DnsLookupException(
                DnsErrorDetails(
                    category = DnsFailureCategory.SERVFAIL,
                    message = "DNS server failure (SERVFAIL / REFUSED) while resolving '$query'.",
                    query = query,
                    queryType = queryType,
                    technicalDetails = "Resolver returned SERVFAIL/REFUSED${systemThrowable?.message?.let { "; System: $it" } ?: ""}.",
                    troubleshootingSteps = listOf(
                        "Check DNSSEC or authoritative nameserver health for '$query'.",
                        "Verify Private DNS or upstream resolver status."
                    )
                ),
                systemThrowable
            )

            hasTimeout -> DnsLookupException(
                DnsErrorDetails(
                    category = DnsFailureCategory.TIMEOUT,
                    message = "DNS lookup timed out while querying '$query'.",
                    query = query,
                    queryType = queryType,
                    technicalDetails = systemThrowable?.message ?: dohOutcomes.firstOrNull { it.timedOut }?.errorMessage ?: "SocketTimeoutException",
                    troubleshootingSteps = listOf(
                        "Check if Private DNS (DoT port 853) or DNS port 53/443 is blocked on this network.",
                        "Verify internet reachability if the link is unvalidated or behind a captive portal."
                    )
                ),
                systemThrowable
            )

            systemThrowable is UnknownHostException -> {
                val extraHint = if (env?.privateDnsInfo?.status == PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE) {
                    "Configured Private DNS (${env.privateDnsInfo.configuredHostname ?: "hostname"}) appears inactive or failing validation, which may prevent resolution."
                } else {
                    "System resolver could not resolve '$query' (${systemThrowable.message ?: "UnknownHostException"})."
                }
                DnsLookupException(
                    DnsErrorDetails(
                        category = DnsFailureCategory.RESOLUTION_FAILED,
                        message = "Failed to resolve '$query'. $extraHint",
                        query = query,
                        queryType = queryType,
                        technicalDetails = "UnknownHostException: ${systemThrowable.message ?: "Unable to resolve host"}",
                        troubleshootingSteps = listOf(
                            "Verify the hostname spelling and internet connectivity.",
                            "Check the Private DNS configuration status card above for validation failures."
                        )
                    ),
                    systemThrowable
                )
            }

            else -> DnsLookupException(
                DnsErrorDetails(
                    category = DnsFailureCategory.RESOLUTION_FAILED,
                    message = "No $queryType DNS records could be resolved for '$query'.",
                    query = query,
                    queryType = queryType,
                    technicalDetails = systemThrowable?.let { "${it::class.java.simpleName}: ${it.message}" }
                        ?: dohOutcomes.firstOrNull { !it.errorMessage.isNullOrBlank() }?.errorMessage
                        ?: "Empty DNS response",
                    troubleshootingSteps = listOf(
                        "Verify the hostname, network connectivity, and Private DNS status."
                    )
                ),
                systemThrowable
            )
        }
    }

    internal suspend fun queryDohRecords(
        endpoint: String,
        name: String,
        type: String,
        outputList: MutableList<DnsRecord>
    ): DohQueryOutcome {
        var conn: HttpURLConnection? = null
        return try {
            coroutineContext.ensureActive()
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val encodedType = URLEncoder.encode(type, "UTF-8")
            val separator = if (endpoint.contains("?")) "&" else "?"
            val urlStr = "$endpoint${separator}name=$encodedName&type=$encodedType"
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "NetworkToolkit/${BuildConfig.VERSION_NAME}")
            }

            coroutineContext.ensureActive()
            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                coroutineContext.ensureActive()
                val json = JSONObject(responseText)
                val rcode = if (json.has("Status")) json.optInt("Status", 0) else 0
                var added = 0
                if (json.has("Answer")) {
                    val answers = json.getJSONArray("Answer")
                    for (i in 0 until answers.length()) {
                        val obj = answers.getJSONObject(i)
                        val recordTypeNumber = obj.optInt("type", 0)
                        val recordType = typeNameFromInt(recordTypeNumber, type)
                        val recordData = obj.optString("data", "").trim('"')
                        val ttl = if (obj.has("TTL") && !obj.isNull("TTL")) {
                            obj.optInt("TTL").takeIf { it >= 0 }
                        } else {
                            null
                        }
                        if (recordData.isNotBlank()) {
                            outputList.add(
                                DnsRecord(
                                    type = recordType,
                                    name = name,
                                    value = recordData,
                                    ttl = ttl
                                )
                            )
                            added++
                        }
                    }
                }
                DohQueryOutcome(rcode = rcode, recordsAdded = added)
            } else {
                runCatching { conn.errorStream?.close() }
                DohQueryOutcome(errorMessage = "HTTP $code from $endpoint")
            }
        } catch (e: SocketTimeoutException) {
            DohQueryOutcome(timedOut = true, errorMessage = "DoH timeout: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DohQueryOutcome(errorMessage = "${e::class.java.simpleName}: ${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun typeNameFromInt(typeInt: Int, fallback: String): String {
        return when (typeInt) {
            1 -> "A"
            28 -> "AAAA"
            15 -> "MX"
            16 -> "TXT"
            2 -> "NS"
            5 -> "CNAME"
            12 -> "PTR"
            else -> fallback
        }
    }
}
