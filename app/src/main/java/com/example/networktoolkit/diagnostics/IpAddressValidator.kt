package com.example.networktoolkit.diagnostics

/**
 * Validates and classifies IPv4 and IPv6 addresses, recognizing special-use, documentation,
 * private, loopback, and carrier-grade NAT ranges according to IETF RFC standards.
 */
object IpAddressValidator {

    enum class IpCategory {
        NORMAL_OPERATIONAL,
        DOCUMENTATION_RESERVED,
        LINK_LOCAL,
        LOOPBACK,
        CARRIER_GRADE_NAT,
        PRIVATE_NETWORK,
        SPECIAL_USE,
        PUBLIC_ROUTABLE,
        INVALID
    }

    data class IpClassification(
        val ip: String,
        val rangeName: String,
        val category: IpCategory,
        val isReservedOrDocumentation: Boolean,
        val isOperational: Boolean,
        val description: String
    )

    /**
     * Classifies an IPv4 or IPv6 address string.
     * [isVpnInterface] indicates if the address belongs to a virtual VPN interface.
     * VPN tunnels frequently use private RFC 1918 or CGNAT ranges which are fully valid and operational.
     */
    fun classifyIp(rawIp: String, isVpnInterface: Boolean = false): IpClassification {
        val cleanIp = rawIp.trim().split("/")[0].trim()

        if (cleanIp.isEmpty() || cleanIp.equals("Unavailable", ignoreCase = true)) {
            return IpClassification(
                ip = cleanIp,
                rangeName = "Unavailable",
                category = IpCategory.INVALID,
                isReservedOrDocumentation = false,
                isOperational = false,
                description = "No address assigned"
            )
        }

        // Check IPv4 format
        val parts = cleanIp.split(".")
        if (parts.size == 4) {
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                val o1 = octets[0]
                val o2 = octets[1]
                val o3 = octets[2]

                // TEST-NET-1: 192.0.2.0/24 (RFC 5737)
                if (o1 == 192 && o2 == 0 && o3 == 2) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "TEST-NET-1 (192.0.2.0/24)",
                        category = IpCategory.DOCUMENTATION_RESERVED,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "RFC 5737 Documentation & Example Net 1. Reserved for documentation; not operational."
                    )
                }

                // TEST-NET-2: 198.51.100.0/24 (RFC 5737)
                if (o1 == 198 && o2 == 51 && o3 == 100) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "TEST-NET-2 (198.51.100.0/24)",
                        category = IpCategory.DOCUMENTATION_RESERVED,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "RFC 5737 Documentation & Example Net 2. Reserved for documentation; not operational."
                    )
                }

                // TEST-NET-3: 203.0.113.0/24 (RFC 5737)
                if (o1 == 203 && o2 == 0 && o3 == 113) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "TEST-NET-3 (203.0.113.0/24)",
                        category = IpCategory.DOCUMENTATION_RESERVED,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "RFC 5737 Documentation & Example Net 3. Reserved for documentation; not operational."
                    )
                }

                // Loopback: 127.0.0.0/8 (RFC 1122)
                if (o1 == 127) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Loopback (127.0.0.0/8)",
                        category = IpCategory.LOOPBACK,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "Localhost loopback address (RFC 1122)."
                    )
                }

                // Link-Local / APIPA: 169.254.0.0/16 (RFC 3927)
                if (o1 == 169 && o2 == 254) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Link-Local / APIPA (169.254.0.0/16)",
                        category = IpCategory.LINK_LOCAL,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "Auto-configured link-local address (RFC 3927). DHCP failed or unavailable."
                    )
                }

                // Carrier-Grade NAT (Shared Address Space): 100.64.0.0/10 (RFC 6598)
                if (o1 == 100 && (o2 in 64..127)) {
                    val desc = if (isVpnInterface) {
                        "Carrier-Grade NAT / Shared Space (RFC 6598). Operational tunnel address assigned by VPN gateway."
                    } else {
                        "Carrier-Grade NAT / Shared Address Space (RFC 6598). Used by ISPs for upstream NAT."
                    }
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "CGNAT / Shared Space (100.64.0.0/10)",
                        category = IpCategory.CARRIER_GRADE_NAT,
                        isReservedOrDocumentation = false,
                        isOperational = true,
                        description = desc
                    )
                }

                // Private Networks: RFC 1918
                // 10.0.0.0/8
                if (o1 == 10) {
                    val desc = if (isVpnInterface) {
                        "RFC 1918 Private Network (10.0.0.0/8). Operational tunnel address assigned by VPN."
                    } else {
                        "RFC 1918 Private Network (Class A)."
                    }
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Private Network (10.0.0.0/8)",
                        category = IpCategory.PRIVATE_NETWORK,
                        isReservedOrDocumentation = false,
                        isOperational = true,
                        description = desc
                    )
                }

                // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                if (o1 == 172 && (o2 in 16..31)) {
                    val desc = if (isVpnInterface) {
                        "RFC 1918 Private Network (172.16.0.0/12). Operational tunnel address assigned by VPN."
                    } else {
                        "RFC 1918 Private Network (Class B)."
                    }
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Private Network (172.16.0.0/12)",
                        category = IpCategory.PRIVATE_NETWORK,
                        isReservedOrDocumentation = false,
                        isOperational = true,
                        description = desc
                    )
                }

                // 192.168.0.0/16
                if (o1 == 192 && o2 == 168) {
                    val desc = if (isVpnInterface) {
                        "RFC 1918 Private Network (192.168.0.0/16). Operational tunnel address assigned by VPN."
                    } else {
                        "RFC 1918 Private Network (Class C, Home/Office)."
                    }
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Private Network (192.168.0.0/16)",
                        category = IpCategory.PRIVATE_NETWORK,
                        isReservedOrDocumentation = false,
                        isOperational = true,
                        description = desc
                    )
                }

                // Multicast: 224.0.0.0/4 (RFC 5771)
                if (o1 in 224..239) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Multicast (224.0.0.0/4)",
                        category = IpCategory.SPECIAL_USE,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "RFC 5771 Multicast address. Not valid for unicast host assignment."
                    )
                }

                // Reserved for Future Use: 240.0.0.0/4 (RFC 1112)
                if (o1 in 240..255) {
                    return IpClassification(
                        ip = cleanIp,
                        rangeName = "Reserved (240.0.0.0/4)",
                        category = IpCategory.SPECIAL_USE,
                        isReservedOrDocumentation = true,
                        isOperational = false,
                        description = "RFC 1112 Reserved for future use / Broadcast."
                    )
                }

                // Default Public Routable IPv4
                return IpClassification(
                    ip = cleanIp,
                    rangeName = "Public Routable IPv4",
                    category = IpCategory.PUBLIC_ROUTABLE,
                    isReservedOrDocumentation = false,
                    isOperational = true,
                    description = "Public globally-routable IPv4 address."
                )
            }
        }

        // IPv6 Check
        if (cleanIp.contains(":")) {
            return if (cleanIp.startsWith("fe80:", ignoreCase = true)) {
                IpClassification(
                    ip = cleanIp,
                    rangeName = "IPv6 Link-Local",
                    category = IpCategory.LINK_LOCAL,
                    isReservedOrDocumentation = false,
                    isOperational = true,
                    description = "IPv6 Link-Local address (fe80::/10)."
                )
            } else if (cleanIp.equals("::1", ignoreCase = true)) {
                IpClassification(
                    ip = cleanIp,
                    rangeName = "IPv6 Loopback",
                    category = IpCategory.LOOPBACK,
                    isReservedOrDocumentation = true,
                    isOperational = false,
                    description = "IPv6 Loopback address (::1)."
                )
            } else {
                IpClassification(
                    ip = cleanIp,
                    rangeName = "IPv6 Global Unicast",
                    category = IpCategory.NORMAL_OPERATIONAL,
                    isReservedOrDocumentation = false,
                    isOperational = true,
                    description = "Operational IPv6 address."
                )
            }
        }

        return IpClassification(
            ip = cleanIp,
            rangeName = "Unrecognized Format",
            category = IpCategory.INVALID,
            isReservedOrDocumentation = false,
            isOperational = false,
            description = "Unrecognized IP address format."
        )
    }

    private val IPV4_SEGMENT_REGEX = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val HOSTNAME_LABEL_REGEX = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")
    private val IPV6_ZONE_REGEX = Regex("""^[A-Za-z0-9._-]{1,32}$""")
    private val FORBIDDEN_TARGET_CHARS = setOf(
        ';', '&', '|', '$', '`', '<', '>', '(', ')', '{', '}',
        '\'', '"', '\\', '/', '!', '*', '?', '=', ',', '#', '@', '+'
    )

    /**
     * Validates and normalizes a network diagnostic target (IPv4, IPv6, or DNS hostname)
     * before DNS lookup or ProcessBuilder execution.
     * Prevents CLI option injection (leading '-') and shell/URL metacharacters.
     */
    @JvmStatic
    fun sanitizeDiagnosticTarget(rawTarget: String?): String? {
        val trimmed = rawTarget?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 253) return null
        if (trimmed.startsWith("-")) return null
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it in FORBIDDEN_TARGET_CHARS }) {
            return null
        }
        if (trimmed.contains("://")) return null

        val unwrapped = if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length > 2) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (!inner.contains(":")) return null
            inner
        } else {
            trimmed
        }

        if (unwrapped.startsWith("-")) return null

        if (unwrapped.contains(":")) {
            return if (isValidIpv6Target(unwrapped)) unwrapped else null
        }

        if (isValidIpv4Target(unwrapped)) {
            return unwrapped
        }

        val cleanHostname = unwrapped.removeSuffix(".")
        return if (isValidHostnameTarget(cleanHostname)) cleanHostname else null
    }

    @JvmStatic
    fun isValidDiagnosticTarget(rawTarget: String?): Boolean {
        return sanitizeDiagnosticTarget(rawTarget) != null
    }

    private fun isValidIpv4Target(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { IPV4_SEGMENT_REGEX.matches(it) }
    }

    private fun isValidIpv6Target(candidate: String): Boolean {
        val addrPart = candidate.substringBefore("%")
        val zonePart = if (candidate.contains("%")) candidate.substringAfter("%") else null
        if (zonePart != null && !IPV6_ZONE_REGEX.matches(zonePart)) return false
        if (addrPart.contains(":::")) return false

        val doubleColonCount = addrPart.split("::").size - 1
        if (doubleColonCount > 1) return false

        val halves = addrPart.split("::")
        val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(":")
        val rightGroups = if (halves.size > 1 && halves[1].isNotEmpty()) halves[1].split(":") else emptyList()

        val hasEmbeddedIpv4 = (rightGroups.lastOrNull()?.contains(".") == true) ||
            (doubleColonCount == 0 && leftGroups.lastOrNull()?.contains(".") == true)

        val groupsToValidate = if (hasEmbeddedIpv4) {
            val tail = if (rightGroups.isNotEmpty()) rightGroups.last() else leftGroups.last()
            if (!isValidIpv4Target(tail)) return false
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

        return groupsToValidate.all { group ->
            group.length in 1..4 && group.all { ch ->
                ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            }
        }
    }

    private fun isValidHostnameTarget(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || host.contains("..")) return false
        val labels = host.split(".")
        if (labels.any { !HOSTNAME_LABEL_REGEX.matches(it) }) return false
        if (labels.size == 4 && labels.all { it.all(Char::isDigit) }) return false
        if (labels.last().all { it.isDigit() }) return false
        return true
    }
}
