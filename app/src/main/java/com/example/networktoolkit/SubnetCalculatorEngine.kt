package com.example.networktoolkit

import java.util.Locale

data class SubnetResult(
    val inputIp: String,
    val prefixLength: Int,
    val networkAddress: String,
    val broadcastAddress: String,
    val subnetMask: String,
    val wildcardMask: String,
    val firstUsableHost: String,
    val lastUsableHost: String,
    val usableHostsCount: Long,
    val totalAddressesCount: Long,
    val cidrNotation: String,
    val ipClass: String,
    val addressScope: String,
    val ipBinary: String,
    val maskBinary: String,
    val networkBinary: String,
    val broadcastBinary: String
)

object SubnetCalculatorEngine {

    fun calculate(ipString: String, prefix: Int): Result<SubnetResult> {
        val cleanPrefix = prefix.coerceIn(0, 32)
        val ipParts = ipString.trim().split(".")
        if (ipParts.size != 4) {
            return Result.failure(IllegalArgumentException("Invalid IPv4 address format. Must contain 4 octets."))
        }

        val octets = IntArray(4)
        for (i in 0..3) {
            val octet = ipParts[i].toIntOrNull()
            if (octet == null || octet < 0 || octet > 255) {
                return Result.failure(IllegalArgumentException("Octet ${i + 1} ('${ipParts[i]}') must be between 0 and 255."))
            }
            octets[i] = octet
        }

        val ipInt = (octets[0].toLong() shl 24) or
                (octets[1].toLong() shl 16) or
                (octets[2].toLong() shl 8) or
                octets[3].toLong()

        val maskInt = if (cleanPrefix == 0) 0L else (0xFFFFFFFFL shl (32 - cleanPrefix)) and 0xFFFFFFFFL
        val wildcardInt = maskInt.inv() and 0xFFFFFFFFL

        val networkInt = ipInt and maskInt
        val broadcastInt = ipInt or wildcardInt

        val totalAddresses = if (cleanPrefix == 0) 4294967296L else 1L shl (32 - cleanPrefix)
        val usableHosts = when (cleanPrefix) {
            32 -> 1L
            31 -> 2L // RFC 3021 Point-to-Point
            else -> (totalAddresses - 2).coerceAtLeast(0)
        }

        val firstUsableInt = when (cleanPrefix) {
            32 -> networkInt
            31 -> networkInt
            else -> networkInt + 1
        }

        val lastUsableInt = when (cleanPrefix) {
            32 -> networkInt
            31 -> broadcastInt
            else -> (broadcastInt - 1).coerceAtLeast(firstUsableInt)
        }

        val ipClass = determineClass(octets[0])
        val scope = determineScope(octets)

        return Result.success(
            SubnetResult(
                inputIp = formatIp(ipInt),
                prefixLength = cleanPrefix,
                networkAddress = formatIp(networkInt),
                broadcastAddress = formatIp(broadcastInt),
                subnetMask = formatIp(maskInt),
                wildcardMask = formatIp(wildcardInt),
                firstUsableHost = formatIp(firstUsableInt),
                lastUsableHost = formatIp(lastUsableInt),
                usableHostsCount = usableHosts,
                totalAddressesCount = totalAddresses,
                cidrNotation = "/$cleanPrefix",
                ipClass = ipClass,
                addressScope = scope,
                ipBinary = formatBinary(ipInt),
                maskBinary = formatBinary(maskInt),
                networkBinary = formatBinary(networkInt),
                broadcastBinary = formatBinary(broadcastInt)
            )
        )
    }

    private fun formatIp(intVal: Long): String {
        val o1 = (intVal shr 24) and 0xFF
        val o2 = (intVal shr 16) and 0xFF
        val o3 = (intVal shr 8) and 0xFF
        val o4 = intVal and 0xFF
        return "$o1.$o2.$o3.$o4"
    }

    private fun formatBinary(intVal: Long): String {
        val o1 = ((intVal shr 24) and 0xFF).toString(2).padStart(8, '0')
        val o2 = ((intVal shr 16) and 0xFF).toString(2).padStart(8, '0')
        val o3 = ((intVal shr 8) and 0xFF).toString(2).padStart(8, '0')
        val o4 = (intVal and 0xFF).toString(2).padStart(8, '0')
        return "$o1.$o2.$o3.$o4"
    }

    private fun determineClass(firstOctet: Int): String {
        return when (firstOctet) {
            in 0..127 -> "Class A"
            in 128..191 -> "Class B"
            in 192..223 -> "Class C"
            in 224..239 -> "Class D (Multicast)"
            else -> "Class E (Experimental)"
        }
    }

    private fun determineScope(o: IntArray): String {
        val o1 = o[0]
        val o2 = o[1]
        val o3 = o[2]
        return when {
            o1 == 10 -> "RFC 1918 Private (10.0.0.0/8)"
            o1 == 172 && o2 in 16..31 -> "RFC 1918 Private (172.16.0.0/12)"
            o1 == 192 && o2 == 168 -> "RFC 1918 Private (192.168.0.0/16)"
            o1 == 127 -> "Loopback (127.0.0.0/8)"
            o1 == 169 && o2 == 254 -> "Link-Local / APIPA (169.254.0.0/16)"
            o1 == 100 && o2 in 64..127 -> "Shared Address Space / CGNAT (100.64.0.0/10)"
            o1 == 192 && o2 == 0 && o3 == 2 -> "TEST-NET-1 (RFC 5737 Documentation)"
            o1 == 198 && o2 == 51 && o3 == 100 -> "TEST-NET-2 (RFC 5737 Documentation)"
            o1 == 203 && o2 == 0 && o3 == 113 -> "TEST-NET-3 (RFC 5737 Documentation)"
            o1 in 224..239 -> "Multicast Address"
            o1 in 240..255 -> "Reserved / Experimental"
            else -> "Public Routable Internet"
        }
    }

    fun getSubnetMaskForPrefix(prefix: Int): String {
        val cleanPrefix = prefix.coerceIn(0, 32)
        val maskInt = if (cleanPrefix == 0) 0L else (0xFFFFFFFFL shl (32 - cleanPrefix)) and 0xFFFFFFFFL
        return formatIp(maskInt)
    }
}
