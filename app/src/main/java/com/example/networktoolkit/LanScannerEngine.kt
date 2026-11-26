package com.example.networktoolkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

enum class DeviceType {
    GATEWAY,
    CURRENT_DEVICE,
    WEB_SERVER,
    SSH_SERVER,
    FILE_SERVER,
    NETWORK_PRINTER,
    DNS_SERVER,
    GENERIC_HOST
}

data class DiscoveredDevice(
    val ip: String,
    val hostname: String?,
    val isCurrentDevice: Boolean = false,
    val isGateway: Boolean = false,
    val respondingPort: Int? = null,
    val serviceName: String? = null,
    val responseTimeMs: Long = 0,
    val deviceType: DeviceType = DeviceType.GENERIC_HOST
)

data class SubnetInfo(
    val localIp: String,
    val gatewayIp: String?,
    val prefixLength: Int,
    val networkAddress: String,
    val broadcastAddress: String,
    val firstHost: String,
    val lastHost: String,
    val totalHosts: Int
)

/**
 * LAN Scanner performing a concurrent subnet sweep for active IP addresses
 * using Kotlin Coroutines and Socket connections.
 */
class LanScannerEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    interface Callback {
        fun onSubnetDetected(subnetInfo: SubnetInfo)
        fun onDeviceFound(device: DiscoveredDevice)
        fun onProgress(scanned: Int, total: Int)
        fun onCompleted(devices: List<DiscoveredDevice>, durationMs: Long)
        fun onError(message: String)
    }

    private var activeJob: Job? = null

    val isScanning: Boolean
        get() = activeJob?.isActive == true

    fun stopScan() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Inspects active network interfaces and computes the local IPv4 subnet details.
     */
    fun detectSubnetInfo(): SubnetInfo? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val net = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(net) ?: return null

            var localIp: Inet4Address? = null
            var prefixLength = 24

            for (linkAddr in lp.linkAddresses) {
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    localIp = addr
                    prefixLength = linkAddr.prefixLength
                    break
                }
            }

            if (localIp == null) return null

            var gatewayIp: String? = null
            for (route in lp.routes) {
                if (route.isDefaultRoute && route.gateway is Inet4Address) {
                    gatewayIp = (route.gateway as Inet4Address).hostAddress
                    break
                }
            }

            // Cap sweep prefix to /24 (max 254 hosts) for responsiveness on mobile networks
            val effectivePrefix = if (prefixLength < 24) 24 else prefixLength

            val addressInt = ipToInt(localIp)
            val mask = -1 shl (32 - effectivePrefix)
            val netAddrLong = (addressInt and mask).toLong() and 0xffffffffL
            val bcastLong = (netAddrLong or (mask.inv().toLong() and 0xffffffffL))

            val firstHostLong = netAddrLong + 1
            val lastHostLong = bcastLong - 1
            val totalHosts = (lastHostLong - firstHostLong + 1).coerceAtLeast(0).toInt()

            return SubnetInfo(
                localIp = localIp.hostAddress ?: "127.0.0.1",
                gatewayIp = gatewayIp,
                prefixLength = effectivePrefix,
                networkAddress = longToIp(netAddrLong),
                broadcastAddress = longToIp(bcastLong),
                firstHost = longToIp(firstHostLong),
                lastHost = longToIp(lastHostLong),
                totalHosts = totalHosts
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Starts the subnet sweep using Kotlin coroutines and socket connections.
     */
    fun startScan(
        customSubnet: SubnetInfo? = null,
        callback: Callback
    ) {
        stopScan()

        val subnet = customSubnet ?: detectSubnetInfo()
        if (subnet == null) {
            callback.onError("Unable to detect local IPv4 subnet. Please verify Wi-Fi/Ethernet connection.")
            return
        }

        callback.onSubnetDetected(subnet)

        activeJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val discovered = mutableListOf<DiscoveredDevice>()

            try {
                withContext(Dispatchers.IO) {
                    val firstHostLong = ipToLong(subnet.firstHost)
                    val lastHostLong = ipToLong(subnet.lastHost)
                    val totalHosts = subnet.totalHosts

                    val targetIps = (firstHostLong..lastHostLong).map { longToIp(it) }

                    // Limit concurrent socket probes to 32 parallel coroutines for high throughput and system stability
                    val semaphore = Semaphore(32)
                    var scannedCount = 0

                    val scanJobs = targetIps.map { ip ->
                        async {
                            ensureActive()
                            val device = semaphore.withPermit {
                                probeHost(
                                    ip = ip,
                                    localIp = subnet.localIp,
                                    gatewayIp = subnet.gatewayIp
                                )
                            }

                            scannedCount++
                            val currentScanned = scannedCount
                            withContext(Dispatchers.Main) {
                                callback.onProgress(currentScanned, totalHosts)
                                if (device != null) {
                                    discovered.add(device)
                                    callback.onDeviceFound(device)
                                }
                            }
                        }
                    }

                    scanJobs.awaitAll()
                }

                val duration = System.currentTimeMillis() - startTime
                // Sort by IP address numerically
                val sorted = discovered.sortedBy { ipToLong(it.ip) }
                callback.onCompleted(sorted, duration)

            } catch (e: CancellationException) {
                val duration = System.currentTimeMillis() - startTime
                val sorted = discovered.sortedBy { ipToLong(it.ip) }
                callback.onCompleted(sorted, duration)
            } catch (e: Exception) {
                callback.onError("LAN scan interrupted: ${e.message}")
            } finally {
                stopScan()
            }
        }
    }

    /**
     * Probes an individual IP address using socket connections on common LAN service ports.
     */
    private suspend fun probeHost(
        ip: String,
        localIp: String,
        gatewayIp: String?
    ): DiscoveredDevice? = withContext(Dispatchers.IO) {
        val isCurrent = (ip == localIp)
        val isGw = (ip == gatewayIp)

        // If it's this device, return immediately as active
        if (isCurrent) {
            return@withContext DiscoveredDevice(
                ip = ip,
                hostname = "This Android Device",
                isCurrentDevice = true,
                isGateway = false,
                respondingPort = null,
                serviceName = "Local Host",
                responseTimeMs = 0,
                deviceType = DeviceType.CURRENT_DEVICE
            )
        }

        val startNanos = System.nanoTime()

        // 1. First probe ICMP / Echo reachability with short timeout
        var reachable = false
        try {
            val addr = InetAddress.getByName(ip)
            reachable = addr.isReachable(200)
        } catch (_: Exception) {}

        val probePorts = intArrayOf(80, 443, 22, 445, 53, 9100)
        var connectedPort: Int? = null
        var service: String? = if (reachable) "ICMP Echo" else null

        // 2. Probe common service ports via TCP Socket
        for (port in probePorts) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, port), 220)
                connectedPort = port
                service = when (port) {
                    80 -> "HTTP Web Server"
                    443 -> "HTTPS Secure Web"
                    22 -> "SSH Remote Terminal"
                    445 -> "SMB File Sharing"
                    53 -> "DNS Server"
                    9100 -> "Raw Network Printer"
                    else -> "TCP Port $port"
                }
                socket.close()
                break
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        if (reachable || connectedPort != null || isGw) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            // Reverse DNS resolution
            var resolvedName: String? = null
            try {
                val inet = InetAddress.getByName(ip)
                val h = inet.hostName
                if (h != null && h != ip) {
                    resolvedName = h
                }
            } catch (_: Exception) {}

            val devType = when {
                isGw -> DeviceType.GATEWAY
                connectedPort == 80 || connectedPort == 443 -> DeviceType.WEB_SERVER
                connectedPort == 22 -> DeviceType.SSH_SERVER
                connectedPort == 445 -> DeviceType.FILE_SERVER
                connectedPort == 9100 -> DeviceType.NETWORK_PRINTER
                connectedPort == 53 -> DeviceType.DNS_SERVER
                else -> DeviceType.GENERIC_HOST
            }

            return@withContext DiscoveredDevice(
                ip = ip,
                hostname = resolvedName ?: if (isGw) "Default Gateway / Router" else null,
                isCurrentDevice = false,
                isGateway = isGw,
                respondingPort = connectedPort,
                serviceName = service ?: if (isGw) "Network Gateway" else "Active Host",
                responseTimeMs = elapsedMs.coerceAtLeast(1),
                deviceType = devType
            )
        }

        return@withContext null
    }

    private fun ipToInt(address: Inet4Address): Int {
        val b = address.address
        return ((b[0].toInt() and 0xff) shl 24) or
               ((b[1].toInt() and 0xff) shl 16) or
               ((b[2].toInt() and 0xff) shl 8) or
               (b[3].toInt() and 0xff)
    }

    private fun longToIp(v: Long): String {
        return "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        if (parts.size != 4) return 0L
        return ((parts[0].toLong() and 0xff) shl 24) or
               ((parts[1].toLong() and 0xff) shl 16) or
               ((parts[2].toLong() and 0xff) shl 8) or
               (parts[3].toLong() and 0xff)
    }
}
