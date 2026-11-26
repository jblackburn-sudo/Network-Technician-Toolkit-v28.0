package com.example.networktoolkit

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DnsPrivateDnsDiagnosticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `private dns state classifier covers all 7 required modes without null text or provider guessing`() {
        // 1. Private DNS disabled
        val disabled = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = false,
            linkPrivateDnsServerName = null,
            settingsMode = "off",
            settingsSpecifier = null
        )
        assertEquals(PrivateDnsStatus.DISABLED, disabled.status)
        assertNull(disabled.configuredHostname)
        assertFalse(disabled.detailMessage.contains("null", ignoreCase = true))

        // 2. Private DNS automatic mode with Private DNS active
        val autoActive = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = true,
            linkPrivateDnsServerName = null,
            settingsMode = "opportistic",
            settingsSpecifier = null
        )
        assertEquals(PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC, autoActive.status)
        assertEquals("Automatic (Active DoT)", autoActive.statusLabel)
        assertNull(autoActive.configuredHostname)

        // 3. Private DNS automatic mode with Private DNS inactive (fallback to network DNS)
        val autoInactive = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = false,
            linkPrivateDnsServerName = null,
            settingsMode = "opportistic",
            settingsSpecifier = null
        )
        assertEquals(PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC, autoInactive.status)
        assertEquals("Automatic (Opportunistic)", autoInactive.statusLabel)
        assertTrue(autoInactive.detailMessage.contains("falling back", ignoreCase = true))

        // 4. Hostname mode with an available hostname
        val nextDns = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = true,
            linkPrivateDnsServerName = "ab12cd.dns.nextdns.io",
            settingsMode = "hostname",
            settingsSpecifier = "ab12cd.dns.nextdns.io"
        )
        assertEquals(PrivateDnsStatus.HOSTNAME_ACTIVE, nextDns.status)
        assertEquals("ab12cd.dns.nextdns.io", nextDns.configuredHostname)
        assertTrue(nextDns.isExposedByOs)

        // 5. Hostname mode WITHOUT an exposed hostname (must not emit 'null' or guess provider)
        val hostnameHidden = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = true,
            linkPrivateDnsServerName = null,
            settingsMode = "hostname",
            settingsSpecifier = null
        )
        assertEquals(PrivateDnsStatus.HOSTNAME_ACTIVE, hostnameHidden.status)
        assertNull(hostnameHidden.configuredHostname)
        assertFalse(hostnameHidden.isExposedByOs)
        assertFalse(hostnameHidden.detailMessage.contains("null", ignoreCase = true))
        assertFalse(hostnameHidden.detailMessage.contains("nextdns", ignoreCase = true))
        assertFalse(hostnameHidden.detailMessage.contains("cloudflare", ignoreCase = true))
        assertFalse(hostnameHidden.detailMessage.contains("google", ignoreCase = true))

        // 6. Private DNS validation/connectivity failure
        val valFail = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = false,
            linkPrivateDnsServerName = null,
            settingsMode = "hostname",
            settingsSpecifier = "dns.quad9.net"
        )
        assertEquals(PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE, valFail.status)
        assertEquals("dns.quad9.net", valFail.configuredHostname)

        // 7. Private DNS information unavailable due to Android/OEM restrictions
        val unavailable = NetworkDnsInspector.resolvePrivateDnsState(
            sdkInt = 35,
            isConnected = true,
            isInternetValidated = true,
            linkIsPrivateDnsActive = null,
            linkPrivateDnsServerName = null,
            settingsMode = null,
            settingsSpecifier = null
        )
        assertEquals(PrivateDnsStatus.UNAVAILABLE, unavailable.status)
        assertFalse(unavailable.isExposedByOs)
        assertNull(unavailable.configuredHostname)
        assertEquals(NetworkDnsInspector.UNAVAILABLE_API_MESSAGE, unavailable.detailMessage)
    }

    @Test
    fun `dns lookup failure classifier distinguishes offline, nxdomain, servfail, and timeout`() {
        val offlineEnv = ActiveDnsEnvironment(
            isConnected = false,
            isInternetValidated = false,
            activeTransport = "Offline",
            networkAssignedDnsServers = emptyList(),
            vpnDnsServers = emptyList(),
            physicalDnsServers = emptyList(),
            privateDnsInfo = PrivateDnsInfo(
                status = PrivateDnsStatus.UNAVAILABLE,
                detailMessage = NetworkDnsInspector.UNAVAILABLE_API_MESSAGE,
                isExposedByOs = false
            )
        )
        val offlineErr = DnsLookupEngine.classifyFailure(UnknownHostException("offline"), "example.com", "A", offlineEnv, emptyList())
        assertEquals(DnsFailureCategory.NO_NETWORK_CONNECTIVITY, offlineErr.details.category)

        val onlineEnv = offlineEnv.copy(
            isConnected = true,
            isInternetValidated = true,
            activeTransport = "Wi-Fi",
            networkAssignedDnsServers = listOf("192.168.1.1")
        )

        val nxErr = DnsLookupEngine.classifyFailure(
            UnknownHostException("nx"),
            "nonexistent.invalid",
            "ALL",
            onlineEnv,
            listOf(DnsLookupEngine.DohQueryOutcome(rcode = 3))
        )
        assertEquals(DnsFailureCategory.NXDOMAIN, nxErr.details.category)

        val servfailErr = DnsLookupEngine.classifyFailure(
            null,
            "broken-dnssec.example",
            "TXT",
            onlineEnv,
            listOf(DnsLookupEngine.DohQueryOutcome(rcode = 2))
        )
        assertEquals(DnsFailureCategory.SERVFAIL, servfailErr.details.category)

        val timeoutErr = DnsLookupEngine.classifyFailure(
            SocketTimeoutException("timed out"),
            "slow.example.com",
            "A",
            onlineEnv,
            emptyList()
        )
        assertEquals(DnsFailureCategory.TIMEOUT, timeoutErr.details.category)
    }

    @Test
    fun `dns lookup screen renders distinct network assigned dns and private dns status card`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return DnsLookupViewModel(app) as T
                    }
                }
            )[DnsLookupViewModel::class.java]

            composeTestRule.setContent {
                DnsLookupScreen(viewModel = vm, onBackClick = {})
            }

            composeTestRule.onNodeWithTag("active_dns_config_card").assertIsDisplayed()
            composeTestRule.onNodeWithText("Network-Assigned DNS Servers (LinkProperties):").assertIsDisplayed()
            composeTestRule.onNodeWithText("Android Private DNS (DoT):").assertIsDisplayed()
            composeTestRule.onNodeWithTag("private_dns_status_badge").assertIsDisplayed()
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
