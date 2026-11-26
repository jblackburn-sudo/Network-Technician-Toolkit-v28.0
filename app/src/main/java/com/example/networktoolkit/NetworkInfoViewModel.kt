package com.example.networktoolkit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.networktoolkit.diagnostics.IpAddressValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong

/**
 * ViewModel responsible for monitoring and publishing real-time network connection details
 * using the Android ConnectivityManager, NetworkCapabilities, LinkProperties, and WifiManager APIs.
 *
 * Fully supports distinguishing physical network interfaces from virtual (VPN) interfaces,
 * detecting MTU values correctly without 0-byte placeholders, and generating intelligent
 * diagnostic warnings that distinguish network faults from Android OS visibility restrictions.
 */
class NetworkInfoViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager =
        application.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _uiState = MutableStateFlow<NetworkDetailsUiState>(NetworkDetailsUiState.Loading)
    val uiState: StateFlow<NetworkDetailsUiState> = _uiState.asStateFlow()

    private val queryGeneration = AtomicLong(0L)
    private var refreshJob: Job? = null
    private var liveWifiJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var rssiReceiver: BroadcastReceiver? = null

    @Volatile
    private var latestCallbackWifiNetwork: Network? = null

    @Volatile
    private var latestCallbackWifiInfo: WifiInfo? = null

    init {
        registerNetworkCallback()
        registerRssiReceiver()
        refresh()
    }

    /**
     * Manually triggers a re-query of all active connection details.
     * Cancels any in-flight refresh so stale probes cannot overwrite a newer network switch or disconnect.
     */
    fun refresh() {
        val generation = queryGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            queryNetworkDetails(generation)
        }
    }

    /**
     * Immediately refreshes live Wi-Fi RSSI (dBm), SNR (dB), and SSID/BSSID identifiers
     * without re-running HTTP probes.
     */
    fun refreshLiveWifiTelemetry() {
        liveWifiJob?.cancel()
        liveWifiJob = viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            if (currentState !is NetworkDetailsUiState.Success) return@launch
            val physical = currentState.details.physicalInfo ?: return@launch
            if (!physical.isWifi) return@launch

            val telemetry = WifiTelemetryReader.readLiveTelemetry(
                context = getApplication(),
                preferredInterfaceName = physical.interfaceName,
                cachedWifiInfo = latestCallbackWifiInfo
            )
            applyLiveWifiTelemetry(telemetry)
        }
    }

    internal fun applyLiveWifiTelemetry(telemetry: WifiRfTelemetry) {
        val currentState = _uiState.value
        if (currentState !is NetworkDetailsUiState.Success) return
        val details = currentState.details
        val physical = details.physicalInfo ?: return
        if (!physical.isWifi) return

        if (!telemetry.isWifiEnabled || !telemetry.isWifiConnected) {
            val clearedPhysical = physical.copy(
                ssid = null,
                bssid = null,
                ssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                bssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                signalStrengthDbm = null,
                snrDb = null,
                noiseFloorDbm = null,
                linkSpeedMbps = null,
                frequencyMhz = null,
                frequencyBand = null,
                wifiChannel = null
            )
            val updatedWarnings = generateDiagnosticWarnings(
                isInternetValidated = details.isInternetValidated,
                isVpnActive = details.isVpnActive,
                physicalInfo = clearedPhysical,
                virtualInfo = details.virtualInfo
            )
            _uiState.value = NetworkDetailsUiState.Success(
                details.copy(
                    physicalInfo = clearedPhysical,
                    diagnosticWarnings = updatedWarnings,
                    ssid = null,
                    bssid = null,
                    signalStrengthDbm = null,
                    snrDb = null,
                    noiseFloorDbm = null,
                    linkSpeedMbps = null,
                    frequencyMhz = null,
                    frequencyBand = null,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            return
        }

        val normalizedSsid = WifiTelemetryReader.normalizeSsid(telemetry.ssid)
        val normalizedBssid = WifiTelemetryReader.normalizeBssid(telemetry.bssid)
        val nextSsidStatus = if (normalizedSsid != null) {
            WifiIdentifierStatus.AVAILABLE
        } else {
            telemetry.ssidStatus.takeIf { it != WifiIdentifierStatus.AVAILABLE }
                ?: WifiTelemetryReader.resolveUnavailableStatus(getApplication())
        }
        val nextBssidStatus = if (normalizedBssid != null) {
            WifiIdentifierStatus.AVAILABLE
        } else {
            telemetry.bssidStatus.takeIf { it != WifiIdentifierStatus.AVAILABLE }
                ?: WifiTelemetryReader.resolveUnavailableStatus(getApplication())
        }

        val freqMhz = telemetry.frequencyMhz ?: physical.frequencyMhz
        val freqBand = freqMhz?.let { NetworkConnectionIdentifier.formatFrequencyBand(it) } ?: physical.frequencyBand
        val channel = freqMhz?.let { NetworkConnectionIdentifier.frequencyToWifiChannel(it) } ?: physical.wifiChannel

        val updatedPhysical = physical.copy(
            ssid = normalizedSsid,
            bssid = normalizedBssid,
            ssidStatus = nextSsidStatus,
            bssidStatus = nextBssidStatus,
            signalStrengthDbm = telemetry.rssiDbm,
            snrDb = telemetry.snrDb,
            noiseFloorDbm = telemetry.noiseFloorDbm,
            linkSpeedMbps = telemetry.linkSpeedMbps ?: physical.linkSpeedMbps,
            frequencyMhz = freqMhz,
            frequencyBand = freqBand,
            wifiChannel = channel
        )

        val updatedWarnings = if (
            updatedPhysical.ssidStatus != physical.ssidStatus ||
            updatedPhysical.bssidStatus != physical.bssidStatus
        ) {
            generateDiagnosticWarnings(
                isInternetValidated = details.isInternetValidated,
                isVpnActive = details.isVpnActive,
                physicalInfo = updatedPhysical,
                virtualInfo = details.virtualInfo
            )
        } else {
            details.diagnosticWarnings
        }

        _uiState.value = NetworkDetailsUiState.Success(
            details.copy(
                physicalInfo = updatedPhysical,
                diagnosticWarnings = updatedWarnings,
                ssid = updatedPhysical.ssid,
                bssid = updatedPhysical.bssid,
                signalStrengthDbm = updatedPhysical.signalStrengthDbm,
                snrDb = updatedPhysical.snrDb,
                noiseFloorDbm = updatedPhysical.noiseFloorDbm,
                linkSpeedMbps = updatedPhysical.linkSpeedMbps,
                frequencyMhz = updatedPhysical.frequencyMhz,
                frequencyBand = updatedPhysical.frequencyBand,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    internal fun isNetworkCallbackRegisteredForTesting(): Boolean = networkCallback != null

    internal fun isWifiReceiverRegisteredForTesting(): Boolean = rssiReceiver != null

    private suspend fun queryNetworkDetails(generation: Long = queryGeneration.get()) = withContext(Dispatchers.IO) {
        if (queryGeneration.get() != generation || !currentCoroutineContext().isActive) return@withContext

        val cm = connectivityManager
        if (cm == null) {
            if (queryGeneration.get() == generation) {
                _uiState.value = NetworkDetailsUiState.Disconnected("Connectivity service unavailable")
            }
            return@withContext
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            latestCallbackWifiInfo = null
            latestCallbackWifiNetwork = null
            if (queryGeneration.get() == generation) {
                _uiState.value = NetworkDetailsUiState.Disconnected("No active network connection")
            }
            return@withContext
        }

        val activeCaps = cm.getNetworkCapabilities(activeNetwork)
        val activeLinkProps = cm.getLinkProperties(activeNetwork)

        if (activeCaps == null) {
            if (queryGeneration.get() == generation) {
                _uiState.value = NetworkDetailsUiState.Disconnected("Network capabilities unavailable")
            }
            return@withContext
        }

        // Determine connection classification via Android ConnectivityManager & NetworkCapabilities
        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
        val isVpnActive = classification.isVpnActive
        val vpnNetwork: Network? = classification.vpnNetwork
        val physicalNetwork: Network? = classification.underlyingNetwork

        if (!classification.isWifi) {
            latestCallbackWifiInfo = null
            latestCallbackWifiNetwork = null
        }

        // Extract Virtual (VPN) Information if active
        val virtualInfo: VirtualNetworkInfo? = if (isVpnActive) {
            val vpnLinkProps = cm.getLinkProperties(vpnNetwork ?: activeNetwork) ?: activeLinkProps
            extractVirtualNetworkInfo(vpnLinkProps)
        } else {
            null
        }

        // Validation & Metering from active network
        val isValidated = activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isMetered = !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        // Extract Underlying Network Interface Information
        val physicalInfo: PhysicalNetworkInfo? = extractPhysicalNetworkInfo(
            physicalNetwork = physicalNetwork,
            activeNetwork = activeNetwork,
            classification = classification,
            isActiveValidated = isValidated
        )

        val connectionType = classification.connectionType

        // Proxy
        val proxyStr = activeLinkProps?.httpProxy?.let { proxy ->
            "${proxy.host}:${proxy.port}"
        }

        // Generate Connection Diagnostic Warnings
        val warnings = generateDiagnosticWarnings(
            isInternetValidated = isValidated,
            isVpnActive = isVpnActive,
            physicalInfo = physicalInfo,
            virtualInfo = virtualInfo
        )

        val dnsEnv = NetworkDnsInspector.inspect(getApplication())

        val details = NetworkDetails(
            isConnected = true,
            connectionType = connectionType,
            isVpnActive = isVpnActive,
            physicalInfo = physicalInfo,
            virtualInfo = virtualInfo,
            diagnosticWarnings = warnings,
            isInternetValidated = isValidated,
            isMetered = isMetered,
            httpProxy = proxyStr,
            publicIpAddress = if (isValidated) "Querying..." else "Unavailable (Offline)",
            dnsConnectivityStatus = if (isValidated) "Testing DNS..." else "Pending Validation",
            privateDnsInfo = dnsEnv.privateDnsInfo,
            gatewayConnectivityStatus = if (physicalInfo?.defaultGatewayIpv4 != null) "Testing..." else if (isVpnActive) "Suppressed by VPN Routing" else "Unavailable",
            lastUpdated = System.currentTimeMillis(),
            isBluetooth = classification.isBluetooth,
            networkTransport = classification.networkTransport,
            underlyingConnectionType = classification.underlyingTransport
        )

        if (queryGeneration.get() != generation || !currentCoroutineContext().isActive) return@withContext
        _uiState.value = NetworkDetailsUiState.Success(details)

        // Asynchronously probe DNS, Gateway, and Public IP
        val dnsStatus = testDnsConnectivity()
        val gwStatus = testGatewayConnectivity(physicalInfo?.defaultGatewayIpv4, isVpnActive)
        val publicIp = if (isValidated) fetchPublicIp() else "Unavailable (Offline)"

        if (queryGeneration.get() != generation || !currentCoroutineContext().isActive) return@withContext
        val latestDetails = (_uiState.value as? NetworkDetailsUiState.Success)?.details ?: details
        val enrichedDetails = latestDetails.copy(
            publicIpAddress = publicIp,
            dnsConnectivityStatus = dnsStatus,
            privateDnsInfo = NetworkDnsInspector.inspect(getApplication()).privateDnsInfo,
            gatewayConnectivityStatus = gwStatus
        )
        if (queryGeneration.get() == generation && currentCoroutineContext().isActive) {
            _uiState.value = NetworkDetailsUiState.Success(enrichedDetails)
        }
    }

    private fun testDnsConnectivity(): String {
        return try {
            val addr = InetAddress.getByName("dns.google")
            if (addr != null && addr.hostAddress != null) {
                "Operational (${addr.hostAddress})"
            } else {
                "Operational"
            }
        } catch (e: Exception) {
            try {
                val addr2 = InetAddress.getByName("cloudflare-dns.com")
                if (addr2 != null) "Operational" else "Resolution Failed"
            } catch (e2: Exception) {
                "Resolution Failed"
            }
        }
    }

    private fun testGatewayConnectivity(gatewayIp: String?, isVpnActive: Boolean): String {
        if (gatewayIp == null) {
            return if (isVpnActive) "Suppressed by VPN Routing" else "Unavailable"
        }
        return try {
            val addr = InetAddress.getByName(gatewayIp)
            val reachable = try {
                addr.isReachable(800)
            } catch (e: Exception) {
                false
            }
            if (reachable) {
                "Reachable (Active)"
            } else {
                var socketOk = false
                for (port in listOf(53, 80, 443)) {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(gatewayIp, port), 400)
                            socketOk = true
                        }
                        if (socketOk) break
                    } catch (ignored: Exception) {}
                }
                if (socketOk) "Reachable (Port open)" else "Active Route (ICMP Filtered)"
            }
        } catch (e: Exception) {
            "Configured Route"
        }
    }

    private fun fetchPublicIp(): String {
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://ifconfig.me/ip"
        )
        for (urlStr in endpoints) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "NetworkToolkit/2.0")
                if (conn.responseCode in 200..299) {
                    val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (ip.isNotEmpty() && ip.length < 50) {
                        return ip
                    }
                }
            } catch (ignored: Exception) {
            }
        }
        return "Unavailable (Restricted / Offline)"
    }

    private fun extractVirtualNetworkInfo(linkProps: LinkProperties?): VirtualNetworkInfo {
        val rawIfaceName = NetworkConnectionIdentifier.resolveInterfaceName(linkProps)
        val ifaceName = rawIfaceName ?: "Unavailable"

        val ipv4List = mutableListOf<String>()
        val ipv6List = mutableListOf<String>()
        var primaryIpv4: String? = null
        var primaryPrefixLength: Int? = null

        linkProps?.linkAddresses?.forEach { linkAddress ->
            val addr = linkAddress.address
            val prefix = linkAddress.prefixLength
            if (addr is Inet4Address) {
                val formatted = "${addr.hostAddress}/$prefix"
                ipv4List.add(formatted)
                if (primaryIpv4 == null) {
                    primaryIpv4 = addr.hostAddress
                    primaryPrefixLength = prefix
                }
            } else if (addr is Inet6Address) {
                ipv6List.add("${addr.hostAddress}/$prefix")
            }
        }

        // If LinkProperties didn't contain addresses and a concrete interface name exists, inspect it
        if (primaryIpv4 == null && rawIfaceName != null) {
            try {
                val ni = NetworkInterface.getByName(rawIfaceName)
                ni?.inetAddresses?.asSequence()?.forEach { addr ->
                    if (addr is Inet4Address && primaryIpv4 == null) {
                        primaryIpv4 = addr.hostAddress
                        ipv4List.add(addr.hostAddress ?: "")
                    }
                }
            } catch (_: Exception) {}
        }

        // DNS Servers
        val dnsList = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        // MTU (LinkProperties or NetworkInterface)
        val mtu = resolveInterfaceMtu(linkProps, rawIfaceName)

        // Default Gateway
        var vpnGateway: String? = null
        linkProps?.routes?.forEach { route ->
            if (route.isDefaultRoute && route.gateway is Inet4Address) {
                vpnGateway = route.gateway?.hostAddress
            }
        }

        val subnetMask = primaryPrefixLength?.let { NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(it) }

        return VirtualNetworkInfo(
            interfaceName = ifaceName,
            ipv4Address = primaryIpv4 ?: "Assigned by VPN",
            allIpv4Addresses = ipv4List,
            allIpv6Addresses = ipv6List,
            dnsServers = dnsList,
            mtu = mtu,
            status = "Active & Tunneling",
            defaultGatewayIpv4 = vpnGateway,
            ipv4PrefixLength = primaryPrefixLength,
            ipv4SubnetMask = subnetMask
        )
    }

    private fun extractPhysicalNetworkInfo(
        physicalNetwork: Network?,
        activeNetwork: Network,
        classification: NetworkConnectionIdentifier.ActiveNetworkClassification,
        isActiveValidated: Boolean
    ): PhysicalNetworkInfo? {
        val cm = connectivityManager ?: return null
        val isVpnActive = classification.isVpnActive
        val targetNet = physicalNetwork ?: if (!isVpnActive) activeNetwork else null

        val caps = targetNet?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val linkProps = targetNet?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        val isWifi = classification.isWifi
        val isCellular = classification.isCellular
        val isEthernet = classification.isEthernet
        val isBluetooth = classification.isBluetooth

        val transportType = NetworkConnectionIdentifier.classifyTransportName(
            hasWifi = isWifi,
            hasCellular = isCellular,
            hasEthernet = isEthernet,
            hasBluetooth = isBluetooth
        ) ?: NetworkConnectionIdentifier.CONNECTION_UNKNOWN

        // Interface Name reported strictly by LinkProperties
        val ifaceName = NetworkConnectionIdentifier.resolveInterfaceName(linkProps)

        // IPv4 / IPv6 addresses
        val ipv4List = mutableListOf<String>()
        val ipv6List = mutableListOf<String>()
        var primaryIpv4: String? = null
        var primaryPrefixLength: Int? = null

        linkProps?.linkAddresses?.forEach { linkAddress ->
            val addr = linkAddress.address
            val prefix = linkAddress.prefixLength
            if (addr is Inet4Address) {
                val formatted = "${addr.hostAddress}/$prefix"
                ipv4List.add(formatted)
                if (primaryIpv4 == null) {
                    primaryIpv4 = addr.hostAddress
                    primaryPrefixLength = prefix
                }
            } else if (addr is Inet6Address) {
                ipv6List.add("${addr.hostAddress}/$prefix")
            }
        }

        // If underlying network LinkProperties addresses were obscured during VPN, check named interface if known
        if (primaryIpv4 == null && ifaceName != null) {
            try {
                val ni = NetworkInterface.getByName(ifaceName)
                ni?.inetAddresses?.asSequence()?.forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (primaryIpv4 == null) primaryIpv4 = addr.hostAddress
                        ipv4List.add(addr.hostAddress ?: "")
                    } else if (addr is Inet6Address && !addr.isLoopbackAddress) {
                        ipv6List.add(addr.hostAddress ?: "")
                    }
                }
            } catch (_: Exception) {}
        }

        val subnetMask = primaryPrefixLength?.let { NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(it) }

        // Gateway
        var defaultGw4: String? = null
        var defaultGw6: String? = null
        linkProps?.routes?.forEach { route ->
            if (route.isDefaultRoute && route.gateway != null) {
                val gw = route.gateway
                if (gw is Inet4Address && defaultGw4 == null) {
                    defaultGw4 = gw.hostAddress
                } else if (gw is Inet6Address && defaultGw6 == null) {
                    defaultGw6 = gw.hostAddress
                }
            }
        }

        // DNS Servers
        val dnsList = linkProps?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        // MTU
        val mtu = resolveInterfaceMtu(linkProps, ifaceName)

        // Downstream / Upstream capability bandwidths (if exposed by ConnectivityManager)
        val downKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 }
        val upKbps = caps?.linkUpstreamBandwidthKbps?.takeIf { it > 0 }

        // Wi-Fi Parameters
        var cleanSsid: String? = null
        var bssid: String? = null
        var ssidStatus: WifiIdentifierStatus = WifiIdentifierStatus.NOT_APPLICABLE
        var bssidStatus: WifiIdentifierStatus = WifiIdentifierStatus.NOT_APPLICABLE
        var linkSpeedMbps: Int? = null
        var signalDbm: Int? = null
        var snrDb: Int? = null
        var noiseFloorDbm: Int? = null
        var frequencyMhz: Int? = null
        var frequencyBand: String? = null
        var wifiChannel: Int? = null
        var wifiStandard: String? = null

        if (isWifi) {
            val capWifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                caps.transportInfo as? WifiInfo
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val wmWifiInfo: WifiInfo? = runCatching { wifiManager?.connectionInfo }.getOrNull()
            val callbackWifiInfo = latestCallbackWifiInfo?.takeIf {
                latestCallbackWifiNetwork == null || latestCallbackWifiNetwork == targetNet
            }
            val primaryWifiInfo = capWifiInfo ?: callbackWifiInfo ?: wmWifiInfo

            val idResolution = WifiTelemetryReader.extractWifiIdentifiers(
                context = getApplication(),
                primaryWifiInfo = primaryWifiInfo,
                fallbackWifiInfo = callbackWifiInfo ?: wmWifiInfo,
                isWifiConnected = true
            )
            cleanSsid = idResolution.ssid
            bssid = idResolution.bssid
            ssidStatus = idResolution.ssidStatus
            bssidStatus = idResolution.bssidStatus

            if (primaryWifiInfo != null) {
                val speed = primaryWifiInfo.linkSpeed
                if (speed > 0) {
                    linkSpeedMbps = speed
                }

                val freq = primaryWifiInfo.frequency
                if (freq > 0) {
                    frequencyMhz = freq
                    frequencyBand = NetworkConnectionIdentifier.formatFrequencyBand(freq)
                    wifiChannel = NetworkConnectionIdentifier.frequencyToWifiChannel(freq)
                }

                wifiStandard = NetworkConnectionIdentifier.formatWifiStandard(primaryWifiInfo)
            }

            val telemetry = WifiTelemetryReader.readLiveTelemetry(
                context = getApplication(),
                preferredInterfaceName = ifaceName,
                cachedWifiInfo = primaryWifiInfo
            )
            signalDbm = telemetry.rssiDbm
            snrDb = telemetry.snrDb
            noiseFloorDbm = telemetry.noiseFloorDbm
            if (cleanSsid == null && telemetry.ssid != null) {
                cleanSsid = telemetry.ssid
                ssidStatus = WifiIdentifierStatus.AVAILABLE
            }
            if (bssid == null && telemetry.bssid != null) {
                bssid = telemetry.bssid
                bssidStatus = WifiIdentifierStatus.AVAILABLE
            }
            if (linkSpeedMbps == null) linkSpeedMbps = telemetry.linkSpeedMbps
            if (frequencyMhz == null && telemetry.frequencyMhz != null) {
                frequencyMhz = telemetry.frequencyMhz
                frequencyBand = NetworkConnectionIdentifier.formatFrequencyBand(telemetry.frequencyMhz)
                wifiChannel = NetworkConnectionIdentifier.frequencyToWifiChannel(telemetry.frequencyMhz)
            }
        }

        // Cellular Telemetry (strictly permission-safe, no fabricated values)
        val cellularTelemetry = if (isCellular) {
            NetworkConnectionIdentifier.readCellularTelemetry(getApplication())
        } else {
            null
        }

        // Ethernet link status
        val ethernetLinkStatus = if (isEthernet) {
            if (isActiveValidated || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                "Link Up (Internet Validated)"
            } else {
                "Link Up"
            }
        } else {
            null
        }

        return PhysicalNetworkInfo(
            interfaceName = ifaceName,
            ipv4Address = primaryIpv4,
            allIpv4Addresses = ipv4List,
            allIpv6Addresses = ipv6List,
            defaultGatewayIpv4 = defaultGw4,
            defaultGatewayIpv6 = defaultGw6,
            dnsServers = dnsList,
            ssid = cleanSsid,
            bssid = bssid,
            linkSpeedMbps = linkSpeedMbps,
            signalStrengthDbm = signalDbm,
            snrDb = snrDb,
            noiseFloorDbm = noiseFloorDbm,
            frequencyMhz = frequencyMhz,
            frequencyBand = frequencyBand,
            mtu = mtu,
            transportType = transportType,
            connectionStatus = if (isActiveValidated) "Connected (Validated)" else "Connected (Unvalidated)",
            isWifi = isWifi,
            isCellular = isCellular,
            isEthernet = isEthernet,
            isBluetooth = isBluetooth,
            ipv4PrefixLength = primaryPrefixLength,
            ipv4SubnetMask = subnetMask,
            wifiChannel = wifiChannel,
            wifiStandard = wifiStandard,
            cellularNetworkType = cellularTelemetry?.networkType,
            cellularOperatorName = cellularTelemetry?.operatorName,
            ethernetLinkStatus = ethernetLinkStatus,
            downstreamBandwidthKbps = downKbps,
            upstreamBandwidthKbps = upKbps,
            ssidStatus = ssidStatus,
            bssidStatus = bssidStatus
        )
    }

    private fun resolveInterfaceMtu(linkProps: LinkProperties?, ifaceName: String?): Int? {
        val propMtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            linkProps?.mtu
        } else {
            null
        }
        if (propMtu != null && propMtu > 0) {
            return propMtu
        }
        if (!ifaceName.isNullOrBlank()) {
            try {
                val ni = NetworkInterface.getByName(ifaceName)
                if (ni != null && ni.mtu > 0) {
                    return ni.mtu
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun generateDiagnosticWarnings(
        isInternetValidated: Boolean,
        isVpnActive: Boolean,
        physicalInfo: PhysicalNetworkInfo?,
        virtualInfo: VirtualNetworkInfo?
    ): List<DiagnosticWarning> {
        val warnings = mutableListOf<DiagnosticWarning>()

        // 1. Internet Connectivity Validated
        if (isInternetValidated) {
            warnings.add(
                DiagnosticWarning(
                    title = "Internet Connectivity Validated",
                    message = "Active connection has successfully verified outbound internet reachability.",
                    severity = DiagnosticSeverity.SUCCESS,
                    isSystemLimitation = false
                )
            )
        } else {
            warnings.add(
                DiagnosticWarning(
                    title = "Internet Connectivity Unvalidated",
                    message = "The network has not passed Android captive-portal verification; outbound traffic may be restricted.",
                    severity = DiagnosticSeverity.INFO,
                    isSystemLimitation = false
                )
            )
        }

        // 2. VPN Connection Detected
        if (isVpnActive) {
            val vpnName = virtualInfo?.interfaceName ?: "Unavailable"
            warnings.add(
                DiagnosticWarning(
                    title = "VPN Tunnel Active",
                    message = "Traffic is encapsulated and routed through virtual tunnel interface ($vpnName). Underlying interface routing is encapsulated.",
                    severity = DiagnosticSeverity.INFO,
                    isSystemLimitation = false
                )
            )
        }

        // 3. Default Gateway Availability
        if (physicalInfo != null) {
            if (physicalInfo.defaultGatewayIpv4 == null) {
                if (isVpnActive) {
                    warnings.add(
                        DiagnosticWarning(
                            title = "Underlying Default Gateway Unavailable (VPN Active)",
                            message = "The underlying default gateway is not exposed by Android route tables while the active VPN tunnel handles default routes.",
                            severity = DiagnosticSeverity.WARNING,
                            isSystemLimitation = true
                        )
                    )
                } else {
                    warnings.add(
                        DiagnosticWarning(
                            title = "Default Gateway Unavailable",
                            message = "No default IPv4 gateway was assigned by DHCP or network configuration. Subnet may be isolated or link-local.",
                            severity = DiagnosticSeverity.WARNING,
                            isSystemLimitation = false
                        )
                    )
                }
            }
        }

        // 4. Reserved or Unusual IP Address Detected
        val ipsToCheck = mutableListOf<Pair<String, Boolean>>() // Pair<IP, isVpn>
        physicalInfo?.ipv4Address?.let { ipsToCheck.add(it to false) }
        physicalInfo?.allIpv4Addresses?.forEach { ipsToCheck.add(it to false) }
        virtualInfo?.ipv4Address?.let { ipsToCheck.add(it to true) }
        virtualInfo?.allIpv4Addresses?.forEach { ipsToCheck.add(it to true) }

        for ((rawIp, isVpn) in ipsToCheck.distinctBy { it.first.split("/")[0] }) {
            val classification = IpAddressValidator.classifyIp(rawIp, isVpn)
            if (classification.isReservedOrDocumentation) {
                warnings.add(
                    DiagnosticWarning(
                        title = "Reserved IP Address Detected (${classification.ip})",
                        message = "${classification.rangeName}: ${classification.description}",
                        severity = DiagnosticSeverity.ALERT,
                        isSystemLimitation = false
                    )
                )
            } else if (classification.category == IpAddressValidator.IpCategory.LINK_LOCAL) {
                warnings.add(
                    DiagnosticWarning(
                        title = "Link-Local / APIPA IP Detected (${classification.ip})",
                        message = "Address is self-assigned (169.254.0.0/16). DHCP failed to provide an operational lease.",
                        severity = DiagnosticSeverity.WARNING,
                        isSystemLimitation = false
                    )
                )
            }
        }

        // 5. Underlying Network Information Incomplete
        if (physicalInfo != null) {
            val permissionMissing = physicalInfo.isWifi &&
                (physicalInfo.ssidStatus == WifiIdentifierStatus.PERMISSION_REQUIRED ||
                    physicalInfo.bssidStatus == WifiIdentifierStatus.PERMISSION_REQUIRED)
            val locationRestricted = physicalInfo.isWifi && !permissionMissing &&
                (physicalInfo.ssidStatus == WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED ||
                    physicalInfo.bssidStatus == WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED)
            if (permissionMissing) {
                warnings.add(
                    DiagnosticWarning(
                        title = "Wi-Fi SSID/BSSID Masked (OS Location Policy)",
                        message = "Wi-Fi network SSID and BSSID are masked by Android location privacy protections. Grant location permission to display wireless access point identifiers.",
                        severity = DiagnosticSeverity.INFO,
                        isSystemLimitation = true
                    )
                )
            } else if (locationRestricted) {
                warnings.add(
                    DiagnosticWarning(
                        title = "Wi-Fi Identifiers Restricted (Location Services Disabled)",
                        message = "Device Location Services are disabled, so Android withholds Wi-Fi SSID and BSSID identifiers.",
                        severity = DiagnosticSeverity.INFO,
                        isSystemLimitation = true
                    )
                )
            } else if (isVpnActive && physicalInfo.ipv4Address == null) {
                warnings.add(
                    DiagnosticWarning(
                        title = "Underlying Interface Addresses Isolated",
                        message = "Android OS security policies restrict underlying interface addresses while an exclusive VPN tunnel is active.",
                        severity = DiagnosticSeverity.INFO,
                        isSystemLimitation = true
                    )
                )
            }
        }

        return warnings
    }

    @SuppressLint("MissingPermission")
    private fun handleWifiCapabilitiesUpdate(
        network: Network,
        previousWifiNetwork: Network?,
        capWifiInfo: WifiInfo?
    ) {
        val currentState = _uiState.value
        if (currentState !is NetworkDetailsUiState.Success ||
            currentState.details.physicalInfo?.isWifi != true ||
            (previousWifiNetwork != null && previousWifiNetwork != network)
        ) {
            refresh()
            return
        }

        val physical = currentState.details.physicalInfo
        val telemetry = WifiTelemetryReader.readLiveTelemetry(
            context = getApplication(),
            preferredInterfaceName = physical.interfaceName,
            cachedWifiInfo = capWifiInfo ?: latestCallbackWifiInfo
        )
        val ssidChanged = telemetry.ssid != physical.ssid
        val bssidChanged = telemetry.bssid != physical.bssid
        applyLiveWifiTelemetry(telemetry)
        if (ssidChanged || bssidChanged) {
            refresh()
        }
    }

    private fun clearWifiIdentifiersOnNetworkLoss(lostNetwork: Network) {
        val wasTrackedWifi = lostNetwork == latestCallbackWifiNetwork
        if (wasTrackedWifi) {
            latestCallbackWifiNetwork = null
            latestCallbackWifiInfo = null
        }
        val activeNet = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        val currentState = _uiState.value as? NetworkDetailsUiState.Success ?: return
        val physical = currentState.details.physicalInfo ?: return
        if (!physical.isWifi) return
        if (wasTrackedWifi || activeNet == null || activeNet == lostNetwork) {
            val clearedPhysical = physical.copy(
                ssid = null,
                bssid = null,
                ssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                bssidStatus = WifiIdentifierStatus.NOT_APPLICABLE,
                signalStrengthDbm = null,
                snrDb = null,
                noiseFloorDbm = null,
                linkSpeedMbps = null,
                frequencyMhz = null,
                frequencyBand = null,
                wifiChannel = null
            )
            _uiState.value = NetworkDetailsUiState.Success(
                currentState.details.copy(
                    physicalInfo = clearedPhysical,
                    ssid = null,
                    bssid = null,
                    signalStrengthDbm = null,
                    snrDb = null,
                    noiseFloorDbm = null,
                    linkSpeedMbps = null,
                    frequencyMhz = null,
                    frequencyBand = null,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
    }

    private fun buildNetworkCallback(): ConnectivityManager.NetworkCallback {
        val handleAvailable: (Network) -> Unit = { _ ->
            refresh()
        }
        val handleLost: (Network) -> Unit = { network ->
            clearWifiIdentifiersOnNetworkLoss(network)
            refresh()
        }
        val handleCapabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { network, caps ->
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val capWifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps.transportInfo as? WifiInfo
                } else {
                    null
                }
                val previousWifiNetwork = latestCallbackWifiNetwork
                latestCallbackWifiNetwork = network
                if (capWifiInfo != null) {
                    latestCallbackWifiInfo = capWifiInfo
                }
                handleWifiCapabilitiesUpdate(network, previousWifiNetwork, capWifiInfo)
            } else {
                if (network == latestCallbackWifiNetwork) {
                    latestCallbackWifiNetwork = null
                    latestCallbackWifiInfo = null
                }
                refresh()
            }
        }
        val handleLinkPropertiesChanged: (Network, LinkProperties) -> Unit = { _, _ ->
            refresh()
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onLost(network: Network) = handleLost(network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    handleCapabilitiesChanged(network, networkCapabilities)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    handleLinkPropertiesChanged(network, linkProperties)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleAvailable(network)
                override fun onLost(network: Network) = handleLost(network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    handleCapabilitiesChanged(network, networkCapabilities)
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    handleLinkPropertiesChanged(network, linkProperties)
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return
        try {
            val request = NetworkRequest.Builder().build()
            val callback = buildNetworkCallback()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    private fun registerRssiReceiver() {
        val app = getApplication<Application>()
        if (rssiReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiManager.RSSI_CHANGED_ACTION -> {
                            refreshLiveWifiTelemetry()
                        }
                        WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                            @Suppress("DEPRECATION")
                            val extraWifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO, WifiInfo::class.java)
                            } else {
                                intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO)
                            }
                            if (extraWifiInfo != null) {
                                latestCallbackWifiInfo = extraWifiInfo
                            }
                            refresh()
                        }
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                            val wifiState = intent.getIntExtra(
                                WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN
                            )
                            if (wifiState == WifiManager.WIFI_STATE_DISABLED ||
                                wifiState == WifiManager.WIFI_STATE_DISABLING
                            ) {
                                latestCallbackWifiInfo = null
                                latestCallbackWifiNetwork = null
                            }
                            refresh()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            rssiReceiver = receiver
        } catch (_: Exception) {
            rssiReceiver = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        refreshJob = null
        liveWifiJob?.cancel()
        liveWifiJob = null
        latestCallbackWifiInfo = null
        latestCallbackWifiNetwork = null
        val receiver = rssiReceiver
        if (receiver != null) {
            try {
                getApplication<Application>().unregisterReceiver(receiver)
            } catch (_: Exception) {}
            rssiReceiver = null
        }
        val callback = networkCallback
        if (callback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }
}
