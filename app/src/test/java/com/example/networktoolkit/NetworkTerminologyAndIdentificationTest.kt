package com.example.networktoolkit

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkTerminologyAndIdentificationTest {

    @Test
    fun `classifyConnectionType produces standardized labels for all connection states`() {
        assertEquals(
            "Disconnected",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = false,
                hasVpn = false,
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false
            )
        )
        assertEquals(
            "Wi-Fi Network Connection",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = false,
                hasWifi = true,
                hasCellular = false,
                hasEthernet = false
            )
        )
        assertEquals(
            "Mobile Data Connection",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = false,
                hasWifi = false,
                hasCellular = true,
                hasEthernet = false
            )
        )
        assertEquals(
            "Ethernet Network Connection",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = false,
                hasWifi = false,
                hasCellular = false,
                hasEthernet = true
            )
        )
        assertEquals(
            "Bluetooth Network Connection",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = false,
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false,
                hasBluetooth = true
            )
        )
        assertEquals(
            "VPN Connection (over Wi-Fi Network Connection)",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = true,
                hasWifi = true,
                hasCellular = false,
                hasEthernet = false
            )
        )
        assertEquals(
            "VPN Connection (over Mobile Data Connection)",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = true,
                hasWifi = false,
                hasCellular = true,
                hasEthernet = false
            )
        )
        assertEquals(
            "VPN Connection",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = true,
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false
            )
        )
        assertEquals(
            "Unknown Connection Type",
            NetworkConnectionIdentifier.classifyConnectionType(
                isConnected = true,
                hasVpn = false,
                hasWifi = false,
                hasCellular = false,
                hasEthernet = false
            )
        )
    }

    @Test
    fun `prefixLengthToIpv4SubnetMask converts CIDR prefixes accurately and rejects invalid values`() {
        assertEquals("0.0.0.0", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(0))
        assertEquals("255.0.0.0", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(8))
        assertEquals("255.255.0.0", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(16))
        assertEquals("255.255.255.0", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(24))
        assertEquals("255.255.255.128", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(25))
        assertEquals("255.255.255.248", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(29))
        assertEquals("255.255.255.252", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(30))
        assertEquals("255.255.255.255", NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(32))

        assertNull(NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(-1))
        assertNull(NetworkConnectionIdentifier.prefixLengthToIpv4SubnetMask(33))
    }

    @Test
    fun `wifi frequency conversion maps 2_4GHz 5GHz and 6GHz channels accurately`() {
        assertEquals(1, NetworkConnectionIdentifier.frequencyToWifiChannel(2412))
        assertEquals(6, NetworkConnectionIdentifier.frequencyToWifiChannel(2437))
        assertEquals(11, NetworkConnectionIdentifier.frequencyToWifiChannel(2462))
        assertEquals(14, NetworkConnectionIdentifier.frequencyToWifiChannel(2484))
        assertEquals(36, NetworkConnectionIdentifier.frequencyToWifiChannel(5180))
        assertEquals(149, NetworkConnectionIdentifier.frequencyToWifiChannel(5745))
        assertEquals(1, NetworkConnectionIdentifier.frequencyToWifiChannel(5955))
        assertEquals(5, NetworkConnectionIdentifier.frequencyToWifiChannel(5975))
        assertNull(NetworkConnectionIdentifier.frequencyToWifiChannel(0))
        assertNull(NetworkConnectionIdentifier.frequencyToWifiChannel(1000))

        assertEquals("2.4 GHz", NetworkConnectionIdentifier.formatFrequencyBand(2412))
        assertEquals("5 GHz", NetworkConnectionIdentifier.formatFrequencyBand(5180))
        assertEquals("6 GHz (Wi-Fi 6E/7)", NetworkConnectionIdentifier.formatFrequencyBand(5975))
    }

    @Test
    fun `cellular active connection is never misclassified as Wi-Fi and uses ConnectivityManager capabilities`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        shadowCm.clearAllNetworks()

        val cellularNet = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
        @Suppress("DEPRECATION")
        val mobileInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val cellularCaps = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val cellularLinkProps = LinkProperties().apply {
            interfaceName = "rmnet_data0"
            setDnsServers(listOf(InetAddress.getByName("10.0.0.53")))
        }

        shadowCm.addNetwork(cellularNet, mobileInfo)
        shadowCm.setNetworkCapabilities(cellularNet, cellularCaps)
        shadowCm.setLinkProperties(cellularNet, cellularLinkProps)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(mobileInfo)

        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
        assertTrue(classification.isConnected)
        assertFalse(classification.isWifi)
        assertTrue(classification.isCellular)
        assertFalse(classification.isVpnActive)
        assertEquals(NetworkConnectionIdentifier.CONNECTION_MOBILE_DATA, classification.connectionType)
        assertEquals("Cellular (TRANSPORT_CELLULAR)", classification.networkTransport)
        assertEquals("rmnet_data0", NetworkConnectionIdentifier.resolveInterfaceName(cellularLinkProps))

        val dnsEnv = NetworkDnsInspector.inspect(context)
        assertTrue(dnsEnv.isConnected)
        assertEquals(NetworkConnectionIdentifier.CONNECTION_MOBILE_DATA, dnsEnv.activeTransport)
        assertEquals(listOf("10.0.0.53"), dnsEnv.networkAssignedDnsServers)
        assertTrue(dnsEnv.vpnDnsServers.isEmpty())
    }

    @Test
    fun `vpn over cellular resolves both VPN tunnel and underlying cellular connection without fabricating tun0 or wlan0`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        shadowCm.clearAllNetworks()

        val cellularNet = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)
        @Suppress("DEPRECATION")
        val mobileInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val cellularCaps = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val cellularLinkProps = LinkProperties().apply {
            interfaceName = "rmnet_data2"
            setDnsServers(listOf(InetAddress.getByName("172.16.0.10")))
        }

        val vpnNet = ShadowNetwork.newInstance(ConnectivityManager.TYPE_VPN)
        @Suppress("DEPRECATION")
        val vpnInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val vpnCaps = ShadowNetworkCapabilities.newInstance().apply {
            Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val vpnLinkProps = LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByName("10.8.0.1")))
            // Intentionally leave interfaceName unset to confirm no synthetic "tun0" is fabricated
        }

        shadowCm.addNetwork(cellularNet, mobileInfo)
        shadowCm.setNetworkCapabilities(cellularNet, cellularCaps)
        shadowCm.setLinkProperties(cellularNet, cellularLinkProps)

        shadowCm.addNetwork(vpnNet, vpnInfo)
        shadowCm.setNetworkCapabilities(vpnNet, vpnCaps)
        shadowCm.setLinkProperties(vpnNet, vpnLinkProps)

        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(vpnInfo)

        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
        assertTrue(classification.isConnected)
        assertTrue(classification.isVpnActive)
        assertTrue(classification.isCellular)
        assertFalse(classification.isWifi)
        assertEquals(
            "VPN Connection (over Mobile Data Connection)",
            classification.connectionType
        )
        assertEquals(
            "VPN (TRANSPORT_VPN) over Cellular (TRANSPORT_CELLULAR)",
            classification.networkTransport
        )
        assertNull(NetworkConnectionIdentifier.resolveInterfaceName(vpnLinkProps))
        assertNotNull(classification.underlyingNetwork)

        val dnsEnv = NetworkDnsInspector.inspect(context)
        assertEquals("VPN Connection (over Mobile Data Connection)", dnsEnv.activeTransport)
        assertEquals(listOf("10.8.0.1"), dnsEnv.vpnDnsServers)
        assertEquals(listOf("172.16.0.10"), dnsEnv.physicalDnsServers)
    }

    @Test
    fun `disconnected state and empty LinkProperties do not fabricate wlan0 or tun0`() {
        val snapshot = ConnectivitySnapshot()
        assertFalse(snapshot.isConnected)
        assertEquals(NetworkConnectionIdentifier.CONNECTION_DISCONNECTED, snapshot.transportName)
        assertNull(snapshot.vpnInterfaceName)
        assertNull(snapshot.physicalInterfaceName)
        assertNull(snapshot.activeInterfaceName)

        val emptyProps = LinkProperties()
        assertNull(NetworkConnectionIdentifier.resolveInterfaceName(emptyProps))
        assertNull(NetworkConnectionIdentifier.resolveInterfaceName(null))
    }

    @Test
    fun `readCellularTelemetry handles permission restrictions safely without throwing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val telemetry = NetworkConnectionIdentifier.readCellularTelemetry(context)
        assertNotNull(telemetry)
    }
}
