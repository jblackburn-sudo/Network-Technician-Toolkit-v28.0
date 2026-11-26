package com.example.networktoolkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.util.Locale

enum class PrivateDnsStatus(val displayLabel: String) {
    DISABLED("Disabled (Off)"),
    AUTOMATIC_OPPORTUNISTIC("Automatic (Opportunistic DoT)"),
    HOSTNAME_ACTIVE("Active (Hostname Configured)"),
    VALIDATION_OR_CONNECTIVITY_FAILURE("Validation / Connectivity Failure"),
    UNAVAILABLE("Status Unavailable")
}

data class PrivateDnsInfo(
    val status: PrivateDnsStatus,
    val statusLabel: String = status.displayLabel,
    val configuredHostname: String? = null,
    val detailMessage: String,
    val isExposedByOs: Boolean
)

data class ActiveDnsEnvironment(
    val isConnected: Boolean,
    val isInternetValidated: Boolean,
    val activeTransport: String,
    val networkAssignedDnsServers: List<String>,
    val vpnDnsServers: List<String>,
    val physicalDnsServers: List<String>,
    val privateDnsInfo: PrivateDnsInfo
)

/**
 * Inspects active network-assigned DNS servers and Android Private DNS (DNS-over-TLS)
 * using LinkProperties and non-privileged Android Settings APIs without inventing values.
 */
object NetworkDnsInspector {

    const val UNAVAILABLE_API_MESSAGE =
        "Private DNS provider information unavailable through the current Android system APIs."

    fun inspect(context: Context): ActiveDnsEnvironment {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Safe best-effort Settings.Global read (no extra permissions needed; wrapped in case OEM restricts)
        val settingsMode = runCatching {
            Settings.Global.getString(appContext.contentResolver, "private_dns_mode")
        }.getOrNull()?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

        val settingsSpecifier = runCatching {
            Settings.Global.getString(appContext.contentResolver, "private_dns_specifier")
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        if (cm == null) {
            return ActiveDnsEnvironment(
                isConnected = false,
                isInternetValidated = false,
                activeTransport = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
                networkAssignedDnsServers = emptyList(),
                vpnDnsServers = emptyList(),
                physicalDnsServers = emptyList(),
                privateDnsInfo = resolvePrivateDnsState(
                    sdkInt = Build.VERSION.SDK_INT,
                    isConnected = false,
                    isInternetValidated = false,
                    linkIsPrivateDnsActive = null,
                    linkPrivateDnsServerName = null,
                    settingsMode = settingsMode,
                    settingsSpecifier = settingsSpecifier
                )
            )
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            return ActiveDnsEnvironment(
                isConnected = false,
                isInternetValidated = false,
                activeTransport = NetworkConnectionIdentifier.CONNECTION_DISCONNECTED,
                networkAssignedDnsServers = emptyList(),
                vpnDnsServers = emptyList(),
                physicalDnsServers = emptyList(),
                privateDnsInfo = resolvePrivateDnsState(
                    sdkInt = Build.VERSION.SDK_INT,
                    isConnected = false,
                    isInternetValidated = false,
                    linkIsPrivateDnsActive = null,
                    linkPrivateDnsServerName = null,
                    settingsMode = settingsMode,
                    settingsSpecifier = settingsSpecifier
                )
            )
        }

        val activeCaps = cm.getNetworkCapabilities(activeNetwork)
        val activeLinkProps = cm.getLinkProperties(activeNetwork)
        val classification = NetworkConnectionIdentifier.inspectActiveConnectivity(cm)
        val isValidated = activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val vpnNetwork: Network? = classification.vpnNetwork
        val physicalNetwork: Network? = classification.underlyingNetwork

        val vpnDns = vpnNetwork?.let { runCatching { cm.getLinkProperties(it)?.dnsServers }.getOrNull() }
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            ?: emptyList()

        val physicalDns = physicalNetwork?.let { runCatching { cm.getLinkProperties(it)?.dnsServers }.getOrNull() }
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            ?: emptyList()

        val assignedDns = activeLinkProps?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: (vpnDns.ifEmpty { physicalDns })

        val transportLabel = classification.connectionType

        val linkIsPrivateDnsActive: Boolean?
        val linkPrivateDnsServerName: String?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activeLinkProps != null) {
            linkIsPrivateDnsActive = activeLinkProps.isPrivateDnsActive
            linkPrivateDnsServerName = activeLinkProps.privateDnsServerName?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            linkIsPrivateDnsActive = null
            linkPrivateDnsServerName = null
        }

        val privateDnsInfo = resolvePrivateDnsState(
            sdkInt = Build.VERSION.SDK_INT,
            isConnected = true,
            isInternetValidated = isValidated,
            linkIsPrivateDnsActive = linkIsPrivateDnsActive,
            linkPrivateDnsServerName = linkPrivateDnsServerName,
            settingsMode = settingsMode,
            settingsSpecifier = settingsSpecifier
        )

        return ActiveDnsEnvironment(
            isConnected = true,
            isInternetValidated = isValidated,
            activeTransport = transportLabel,
            networkAssignedDnsServers = assignedDns,
            vpnDnsServers = vpnDns,
            physicalDnsServers = physicalDns,
            privateDnsInfo = privateDnsInfo
        )
    }

    /**
     * Pure classifier converting Android LinkProperties + Settings.Global Private DNS signals
     * into an accurate diagnostic state without inventing any provider information.
     */
    fun resolvePrivateDnsState(
        sdkInt: Int,
        isConnected: Boolean,
        isInternetValidated: Boolean,
        linkIsPrivateDnsActive: Boolean?,
        linkPrivateDnsServerName: String?,
        settingsMode: String?,
        settingsSpecifier: String?
    ): PrivateDnsInfo {
        if (sdkInt < Build.VERSION_CODES.P) {
            return PrivateDnsInfo(
                status = PrivateDnsStatus.UNAVAILABLE,
                statusLabel = "Unsupported on Android < 9",
                configuredHostname = null,
                detailMessage = UNAVAILABLE_API_MESSAGE,
                isExposedByOs = false
            )
        }

        val normalizedMode = settingsMode?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val knownHostname = linkPrivateDnsServerName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (normalizedMode == "hostname") settingsSpecifier?.trim()?.takeIf { it.isNotEmpty() } else null

        // Case 1: Strict Private DNS hostname mode
        if (!knownHostname.isNullOrEmpty() || normalizedMode == "hostname") {
            return when {
                !isConnected -> PrivateDnsInfo(
                    status = PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE,
                    statusLabel = "Hostname Configured (Offline)",
                    configuredHostname = knownHostname,
                    detailMessage = if (knownHostname != null) {
                        "Configured Private DNS hostname '$knownHostname', but the device has no active network connection."
                    } else {
                        "Private DNS strict hostname mode is set, but the device is offline."
                    },
                    isExposedByOs = knownHostname != null
                )

                linkIsPrivateDnsActive == true && !isInternetValidated -> PrivateDnsInfo(
                    status = PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE,
                    statusLabel = if (knownHostname != null) "Hostname Configured (Unvalidated Link)" else "Strict Mode (Unvalidated Link)",
                    configuredHostname = knownHostname,
                    detailMessage = if (knownHostname != null) {
                        "Private DNS is configured ('$knownHostname'), but network connectivity is not validated (captive portal or Private DNS reachability issue possible)."
                    } else {
                        "Private DNS strict hostname mode is active, but network connectivity is not validated and the hostname was not exposed by the OS."
                    },
                    isExposedByOs = knownHostname != null
                )

                linkIsPrivateDnsActive == true -> PrivateDnsInfo(
                    status = PrivateDnsStatus.HOSTNAME_ACTIVE,
                    statusLabel = if (knownHostname != null) "Active (Hostname Configured)" else "Active (Hostname Not Exposed)",
                    configuredHostname = knownHostname,
                    detailMessage = if (knownHostname != null) {
                        "DNS-over-TLS (DoT) is active using the configured Private DNS hostname '$knownHostname'. Network-assigned DHCP DNS servers are bypassed when DoT is active."
                    } else {
                        "DNS-over-TLS (DoT) is active in strict hostname mode, but the configured hostname was not exposed through current Android system APIs."
                    },
                    isExposedByOs = knownHostname != null
                )

                linkIsPrivateDnsActive == false -> PrivateDnsInfo(
                    status = PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE,
                    statusLabel = "Validation / Connectivity Failure",
                    configuredHostname = knownHostname,
                    detailMessage = if (knownHostname != null) {
                        "Private DNS hostname '$knownHostname' is configured, but Android LinkProperties reports Private DNS is inactive (DoT port 853 or certificate validation may have failed)."
                    } else {
                        "Private DNS strict mode is configured, but LinkProperties reports Private DNS is inactive."
                    },
                    isExposedByOs = knownHostname != null
                )

                else -> PrivateDnsInfo(
                    status = PrivateDnsStatus.UNAVAILABLE,
                    statusLabel = "Hostname Configured (Link Status Unavailable)",
                    configuredHostname = knownHostname,
                    detailMessage = knownHostname?.let {
                        "Configured Private DNS hostname '$it' (live link validation state unavailable)."
                    } ?: UNAVAILABLE_API_MESSAGE,
                    isExposedByOs = knownHostname != null
                )
            }
        }

        // Case 2: Explicitly Disabled
        if (normalizedMode == "off") {
            return PrivateDnsInfo(
                status = PrivateDnsStatus.DISABLED,
                statusLabel = "Disabled (Off)",
                configuredHostname = null,
                detailMessage = "Android Private DNS is turned off. Queries use the network-assigned DNS servers.",
                isExposedByOs = true
            )
        }

        // Case 3: Automatic / Opportunistic mode
        if (normalizedMode == "opportistic" || linkIsPrivateDnsActive == true) {
            val activeNote = when (linkIsPrivateDnsActive) {
                true -> "Automatic (Opportunistic) Private DNS is active over TLS on this network."
                false -> "Automatic (Opportunistic) Private DNS is enabled, currently falling back to standard network-assigned DNS (DoT not active on this link)."
                null -> "Automatic (Opportunistic) Private DNS is enabled."
            }
            return PrivateDnsInfo(
                status = PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC,
                statusLabel = if (linkIsPrivateDnsActive == true) "Automatic (Active DoT)" else "Automatic (Opportunistic)",
                configuredHostname = linkPrivateDnsServerName,
                detailMessage = activeNote,
                isExposedByOs = true
            )
        }

        // Case 4: LinkProperties reports inactive without a Settings mode
        if (linkIsPrivateDnsActive == false) {
            return PrivateDnsInfo(
                status = PrivateDnsStatus.UNAVAILABLE,
                statusLabel = "Inactive / Mode Unavailable",
                configuredHostname = null,
                detailMessage = "$UNAVAILABLE_API_MESSAGE LinkProperties reports Private DNS is currently inactive.",
                isExposedByOs = false
            )
        }

        // Case 5: Neither LinkProperties nor Settings exposed Private DNS state
        return PrivateDnsInfo(
            status = PrivateDnsStatus.UNAVAILABLE,
            statusLabel = "Status Unavailable",
            configuredHostname = null,
            detailMessage = UNAVAILABLE_API_MESSAGE,
            isExposedByOs = false
        )
    }
}
