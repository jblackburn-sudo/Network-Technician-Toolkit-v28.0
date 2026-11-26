package com.example.networktoolkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * High-level internet reachability status.
 */
enum class InternetStatus {
    ONLINE,
    CAPTIVE_OR_LIMITED,
    OFFLINE,
    CHECKING
}

/**
 * Local network default gateway responsiveness state.
 */
enum class GatewayStatus {
    RESPONSIVE,
    UNRESPONSIVE,
    SUPPRESSED_BY_VPN,
    NO_GATEWAY,
    CHECKING
}

/**
 * Real-time connectivity snapshot captured for immediate field diagnostics.
 */
data class ConnectivitySnapshot(
    val isConnected: Boolean = false,
    val internetStatus: InternetStatus = InternetStatus.CHECKING,
    val isVpnActive: Boolean = false,
    val vpnInterfaceName: String? = null,
    val physicalInterfaceName: String? = null,
    val localIp: String? = null,
    val gatewayIp: String? = null,
    val gatewayResponsivenessMs: Long? = null,
    val gatewayStatus: GatewayStatus = GatewayStatus.CHECKING,
    val transportName: String = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
    val lastCheckedTimestamp: Long = System.currentTimeMillis(),
    val activeInterfaceName: String? = null,
    val networkTransport: String = "None",
    val underlyingConnectionType: String? = null
)

/**
 * Real-time network connectivity monitor for field technician diagnostics.
 * Continuously tracks:
 * 1. Real-time Internet Validation status
 * 2. Active VPN tunnel encapsulation
 * 3. Default Gateway responsiveness (RTT in ms and reachability)
 */
class NetworkConnectivityMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null

    private val _snapshot = MutableStateFlow(ConnectivitySnapshot())
    val snapshot: StateFlow<ConnectivitySnapshot> = _snapshot.asStateFlow()

    fun interface ConnectivityListener {
        fun onConnectivityChanged(snapshot: ConnectivitySnapshot)
    }

    private val listeners = mutableListOf<ConnectivityListener>()

    init {
        registerNetworkCallbacks()
        refreshNow()
    }

    fun addListener(listener: ConnectivityListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        listener.onConnectivityChanged(_snapshot.value)
    }

    fun removeListener(listener: ConnectivityListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun registerNetworkCallbacks() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNow()
                }

                override fun onLost(network: Network) {
                    refreshNow()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    refreshNow()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    refreshNow()
                }
            })
        } catch (ignored: Exception) {
        }
    }

    fun refreshNow() {
        probeJob?.cancel()
        probeJob = scope.launch {
            probeConnectivity()
        }
    }

    private suspend fun probeConnectivity() = withContext(Dispatchers.IO) {
        val cm = connectivityManager
        if (cm == null) {
            emitSnapshot(
                ConnectivitySnapshot(
                    isConnected = false,
                    internetStatus = InternetStatus.OFFLINE,
                    gatewayStatus = GatewayStatus.NO_GATEWAY,
                    transportName = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
                    networkTransport = "Unavailable"
                )
            )
            return@withContext
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            emitSnapshot(
                ConnectivitySnapshot(
                    isConnected = false,
                    internetStatus = InternetStatus.OFFLINE,
                    gatewayStatus = GatewayStatus.NO_GATEWAY,
                    transportName = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
                    networkTransport = "None"
                )
            )
            return@withContext
        }

        val caps = cm.getNetworkCapabilities(activeNetwork)
        val linkProps = cm.getLinkProperties(activeNetwork)
        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
        val isVpn = classification.isVpnActive

        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val internetStatus = if (isValidated) {
            InternetStatus.ONLINE
        } else if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            InternetStatus.CAPTIVE_OR_LIMITED
        } else {
            InternetStatus.OFFLINE
        }

        val activeIfaceName = NetworkConnectionIdentifier.resolveInterfaceName(linkProps)
        val vpnIface: String? = if (isVpn) {
            classification.vpnNetwork?.let { net ->
                NetworkConnectionIdentifier.resolveInterfaceName(cm.getLinkProperties(net))
            } ?: activeIfaceName
        } else {
            null
        }

        val underlyingIface: String? = if (!isVpn) {
            activeIfaceName
        } else {
            classification.underlyingNetwork?.let { net ->
                NetworkConnectionIdentifier.resolveInterfaceName(cm.getLinkProperties(net))
            }
        }

        var gatewayIp: String? = null
        var localIp: String? = null

        // Resolve default gateway from routes
        linkProps?.routes?.forEach { route ->
            val gw = route.gateway
            if (route.isDefaultRoute && gw is java.net.Inet4Address) {
                if (gatewayIp == null) {
                    gatewayIp = gw.hostAddress
                }
            }
        }

        // Local IP
        linkProps?.linkAddresses?.forEach { la ->
            if (localIp == null && la.address is java.net.Inet4Address && !la.address.isLoopbackAddress) {
                localIp = la.address.hostAddress
            }
        }

        // Measure Gateway responsiveness
        val (gwStatus, rttMs) = testGatewayResponsiveness(gatewayIp, isVpn)

        val newSnapshot = ConnectivitySnapshot(
            isConnected = true,
            internetStatus = internetStatus,
            isVpnActive = isVpn,
            vpnInterfaceName = vpnIface,
            physicalInterfaceName = underlyingIface,
            localIp = localIp,
            gatewayIp = gatewayIp,
            gatewayResponsivenessMs = rttMs,
            gatewayStatus = gwStatus,
            transportName = classification.connectionType,
            lastCheckedTimestamp = System.currentTimeMillis(),
            activeInterfaceName = activeIfaceName ?: underlyingIface,
            networkTransport = classification.networkTransport,
            underlyingConnectionType = classification.underlyingTransport
        )

        emitSnapshot(newSnapshot)
    }

    private fun testGatewayResponsiveness(
        gatewayIp: String?,
        isVpnActive: Boolean
    ): Pair<GatewayStatus, Long?> {
        if (gatewayIp == null) {
            return if (isVpnActive) {
                Pair(GatewayStatus.SUPPRESSED_BY_VPN, null)
            } else {
                Pair(GatewayStatus.NO_GATEWAY, null)
            }
        }

        val startTime = SystemClock.elapsedRealtime()
        try {
            val addr = InetAddress.getByName(gatewayIp)
            val isReachable = try {
                addr.isReachable(600)
            } catch (e: Exception) {
                false
            }

            if (isReachable) {
                val rtt = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1)
                return Pair(GatewayStatus.RESPONSIVE, rtt)
            }

            for (port in listOf(53, 80)) {
                try {
                    Socket().use { socket ->
                        val socketStart = SystemClock.elapsedRealtime()
                        socket.connect(InetSocketAddress(gatewayIp, port), 400)
                        val rtt = (SystemClock.elapsedRealtime() - socketStart).coerceAtLeast(1)
                        return Pair(GatewayStatus.RESPONSIVE, rtt)
                    }
                } catch (ignored: Exception) {
                }
            }

            return Pair(GatewayStatus.UNRESPONSIVE, null)
        } catch (e: Exception) {
            return Pair(GatewayStatus.UNRESPONSIVE, null)
        }
    }

    private fun emitSnapshot(snapshot: ConnectivitySnapshot) {
        _snapshot.value = snapshot
        synchronized(listeners) {
            for (l in listeners) {
                try {
                    l.onConnectivityChanged(snapshot)
                } catch (ignored: Exception) {}
            }
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkConnectivityMonitor? = null

        @JvmStatic
        fun getInstance(context: Context): NetworkConnectivityMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkConnectivityMonitor(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
