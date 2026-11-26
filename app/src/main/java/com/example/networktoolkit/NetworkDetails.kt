package com.example.networktoolkit

/**
 * Severity level of connection diagnostic warnings and messages.
 */
enum class DiagnosticSeverity {
    SUCCESS,
    INFO,
    WARNING,
    ALERT
}

/**
 * Intelligent diagnostic warning explaining network conditions or system limitations.
 * [isSystemLimitation] explicitly distinguishes between actual network faults (e.g., dead gateway)
 * and Android OS platform visibility limitations (e.g., location permission needed for Wi-Fi SSID,
 * or VPN route table isolation).
 */
data class DiagnosticWarning(
    val title: String,
    val message: String,
    val severity: DiagnosticSeverity,
    val isSystemLimitation: Boolean = false
)

/**
 * Availability state of Wi-Fi identifiers (SSID / BSSID), kept strictly separate from
 * the underlying identifier strings so status messages are never stored as network names.
 */
enum class WifiIdentifierStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    LOCATION_SERVICES_DISABLED_OR_RESTRICTED,
    UNAVAILABLE,
    NOT_APPLICABLE
}

/**
 * Underlying network interface details reported by Android LinkProperties/NetworkCapabilities
 * (e.g., Wi-Fi, Mobile Data, Ethernet, or Bluetooth link).
 */
data class PhysicalNetworkInfo(
    val interfaceName: String?,
    val ipv4Address: String?,
    val allIpv4Addresses: List<String> = emptyList(),
    val allIpv6Addresses: List<String> = emptyList(),
    val defaultGatewayIpv4: String?,
    val defaultGatewayIpv6: String? = null,
    val dnsServers: List<String> = emptyList(),
    val ssid: String?,
    val bssid: String?,
    val linkSpeedMbps: Int?,
    val signalStrengthDbm: Int?,
    val snrDb: Int? = null,
    val noiseFloorDbm: Int? = null,
    val frequencyMhz: Int?,
    val frequencyBand: String?,
    val mtu: Int?,
    val transportType: String,
    val connectionStatus: String = "Connected & Active",
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isEthernet: Boolean,
    val isBluetooth: Boolean = false,
    val ipv4PrefixLength: Int? = null,
    val ipv4SubnetMask: String? = null,
    val wifiChannel: Int? = null,
    val wifiStandard: String? = null,
    val cellularNetworkType: String? = null,
    val cellularOperatorName: String? = null,
    val ethernetLinkStatus: String? = null,
    val downstreamBandwidthKbps: Int? = null,
    val upstreamBandwidthKbps: Int? = null,
    val ssidStatus: WifiIdentifierStatus = when {
        !isWifi -> WifiIdentifierStatus.NOT_APPLICABLE
        ssid != null -> WifiIdentifierStatus.AVAILABLE
        else -> WifiIdentifierStatus.UNAVAILABLE
    },
    val bssidStatus: WifiIdentifierStatus = when {
        !isWifi -> WifiIdentifierStatus.NOT_APPLICABLE
        bssid != null -> WifiIdentifierStatus.AVAILABLE
        else -> WifiIdentifierStatus.UNAVAILABLE
    }
) {
    val ipv6Address: String? get() = allIpv6Addresses.firstOrNull()
}

/**
 * Virtual Network Interface details (e.g., VPN tunnel reported by ConnectivityManager).
 */
data class VirtualNetworkInfo(
    val interfaceName: String,
    val ipv4Address: String?,
    val allIpv4Addresses: List<String> = emptyList(),
    val allIpv6Addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val mtu: Int?,
    val status: String = "Active & Tunneling",
    val defaultGatewayIpv4: String? = null,
    val routingInfo: String = "Encapsulates default route (0.0.0.0/0) through virtual VPN tunnel",
    val ipv4PrefixLength: Int? = null,
    val ipv4SubnetMask: String? = null
) {
    val ipv6Address: String? get() = allIpv6Addresses.firstOrNull()
}

/**
 * Detailed network connection data model captured via ConnectivityManager,
 * NetworkCapabilities, LinkProperties, TelephonyManager, and WifiInfo APIs.
 */
data class NetworkDetails(
    val isConnected: Boolean = false,
    val connectionType: String = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
    val isVpnActive: Boolean = false,
    val physicalInfo: PhysicalNetworkInfo? = null,
    val virtualInfo: VirtualNetworkInfo? = null,
    val diagnosticWarnings: List<DiagnosticWarning> = emptyList(),
    val isInternetValidated: Boolean = false,
    val isMetered: Boolean = false,
    val httpProxy: String? = null,
    val publicIpAddress: String? = null,
    val dnsConnectivityStatus: String = "Unknown",
    val privateDnsInfo: PrivateDnsInfo? = null,
    val gatewayConnectivityStatus: String = "Unknown",
    val lastUpdated: Long = System.currentTimeMillis(),

    // Backwards-compatible & standardized convenience properties:
    val isWifi: Boolean = physicalInfo?.isWifi ?: false,
    val isCellular: Boolean = physicalInfo?.isCellular ?: false,
    val isEthernet: Boolean = physicalInfo?.isEthernet ?: false,
    val isVpn: Boolean = isVpnActive,
    val isBluetooth: Boolean = physicalInfo?.isBluetooth ?: false,
    val ssid: String? = physicalInfo?.ssid,
    val bssid: String? = physicalInfo?.bssid,
    val linkSpeedMbps: Int? = physicalInfo?.linkSpeedMbps,
    val downstreamBandwidthKbps: Int? = physicalInfo?.downstreamBandwidthKbps,
    val upstreamBandwidthKbps: Int? = physicalInfo?.upstreamBandwidthKbps,
    val signalStrengthDbm: Int? = physicalInfo?.signalStrengthDbm,
    val snrDb: Int? = physicalInfo?.snrDb,
    val noiseFloorDbm: Int? = physicalInfo?.noiseFloorDbm,
    val frequencyMhz: Int? = physicalInfo?.frequencyMhz,
    val frequencyBand: String? = physicalInfo?.frequencyBand,
    val primaryIpv4: String = virtualInfo?.ipv4Address ?: physicalInfo?.ipv4Address ?: "Unavailable",
    val ipv4Addresses: List<String> = (virtualInfo?.allIpv4Addresses ?: emptyList()) + (physicalInfo?.allIpv4Addresses ?: emptyList()),
    val ipv6Addresses: List<String> = (virtualInfo?.allIpv6Addresses ?: emptyList()) + (physicalInfo?.allIpv6Addresses ?: emptyList()),
    val defaultGatewayIpv4: String? = physicalInfo?.defaultGatewayIpv4 ?: virtualInfo?.defaultGatewayIpv4,
    val defaultGatewayIpv6: String? = physicalInfo?.defaultGatewayIpv6,
    val dnsServers: List<String> = virtualInfo?.dnsServers?.takeIf { it.isNotEmpty() } ?: physicalInfo?.dnsServers ?: emptyList(),
    val interfaceName: String? = if (isVpnActive) virtualInfo?.interfaceName else physicalInfo?.interfaceName,
    val mtu: Int? = if (isVpnActive) virtualInfo?.mtu else physicalInfo?.mtu,
    val networkTransport: String = "Unknown",
    val underlyingConnectionType: String? = null,
    val ipv4PrefixLength: Int? = if (isVpnActive) virtualInfo?.ipv4PrefixLength ?: physicalInfo?.ipv4PrefixLength else physicalInfo?.ipv4PrefixLength,
    val ipv4SubnetMask: String? = if (isVpnActive) virtualInfo?.ipv4SubnetMask ?: physicalInfo?.ipv4SubnetMask else physicalInfo?.ipv4SubnetMask
)

sealed class NetworkDetailsUiState {
    data object Loading : NetworkDetailsUiState()
    data class Success(val details: NetworkDetails) : NetworkDetailsUiState()
    data class Disconnected(val message: String = "No active network connection") : NetworkDetailsUiState()
}
