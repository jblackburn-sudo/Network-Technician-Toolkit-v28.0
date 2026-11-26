package com.example.networktoolkit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.networktoolkit.diagnostics.IpAddressValidator
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSlate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal

/**
 * Modern Material 3 UI component displaying connection details with:
 * - Standardized Connection Type, Transport, Interface, and VPN Tunnel separation
 * - Structured Connection Information, IP Configuration, and Connection-Specific Sections
 * - Intelligent diagnostic warnings distinguishing actual faults from Android OS visibility limits
 * - Special-use IPv4 recognition (TEST-NET-1/2/3, CGNAT, Loopback, Link-Local)
 * - Accurate MTU, Subnet Mask, and Prefix Length reporting ("Unavailable" when unexposed)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkInfoScreen(
    viewModel: NetworkInfoViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    targetSection: String? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.refresh()
            Toast.makeText(context, "Location permission granted. Updating SSID...", Toast.LENGTH_SHORT).show()
        }
    }

    val exportController = rememberSafTextExportController()
    val wifiPrivacy = rememberWifiPrivacySettings()

    val saveDetailsTxt: (NetworkDetails) -> Unit = { details ->
        val phys = details.physicalInfo
        val identifiers = TextReportExportManager.collectIdentifiers(
            context = context,
            extraSsids = listOfNotNull(phys?.ssid),
            extraBssids = listOfNotNull(phys?.bssid)
        )
        val rawReport = buildFullReport(details)
        exportController.promptPrivacyAndRun("Network Diagnostics RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("Network_Diagnostics_Report", "rtf"),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareDetailsTxt: (NetworkDetails) -> Unit = { details ->
        val phys = details.physicalInfo
        val identifiers = TextReportExportManager.collectIdentifiers(
            context = context,
            extraSsids = listOfNotNull(phys?.ssid),
            extraBssids = listOfNotNull(phys?.bssid)
        )
        val rawReport = buildFullReport(details)
        exportController.promptPrivacyAndRun("Network Diagnostics RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = TextReportExportManager.suggestFilename("Network_Diagnostics_Report", "rtf"),
                subject = "Network Diagnostics Report - ${details.primaryIpv4}",
                chooserTitle = "Share Network Details",
                identifiers = identifiers,
                isJson = false,
                privacyChoice = privacyChoice
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (targetSection == "wifi") "Wi-Fi Tools & Diagnostics" else "Connection Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("network_info_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.testTag("network_info_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    if (uiState is NetworkDetailsUiState.Success) {
                        val details = (uiState as NetworkDetailsUiState.Success).details
                        IconButton(
                            onClick = { saveDetailsTxt(details) },
                            modifier = Modifier.testTag("network_info_save_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Save RTF Report"
                            )
                        }
                        IconButton(
                            onClick = {
                                copyDetailsToClipboard(context, details)
                            },
                            modifier = Modifier.testTag("network_info_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Report"
                            )
                        }
                    }
                    ToolContextualHelpAction(
                        toolId = if (targetSection == "wifi") "wifi_scanner" else "network_info",
                        testTag = "network_info_help_button"
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val state = uiState) {
                is NetworkDetailsUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Analyzing network interfaces & routing...",
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                is NetworkDetailsUiState.Disconnected -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (targetSection == "wifi") {
                            WifiRssiAndSnrSection(
                                rssiDbm = null,
                                snrDb = null,
                                noiseFloorDbm = null,
                                isWifiConnected = false,
                                onCopyMetric = { label, value ->
                                    copyText(context, label, value)
                                }
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Disconnected",
                            tint = StatusRed,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Active Connection",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.refresh() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("disconnected_refresh_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Connection")
                        }
                    }
                }
                is NetworkDetailsUiState.Success -> {
                    NetworkDetailsContent(
                        details = state.details,
                        targetSection = targetSection,
                        showSsid = wifiPrivacy.showSsid,
                        showBssid = wifiPrivacy.showBssid,
                        onCopy = { label, value ->
                            copyText(context, label, value)
                        },
                        onCopyFullReport = {
                            copyDetailsToClipboard(context, state.details)
                        },
                        onRequestPermission = {
                            val permissionsToRequest = buildList {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                }
                            }.toTypedArray()
                            permissionLauncher.launch(permissionsToRequest)
                        },
                        onSaveTxt = {
                            saveDetailsTxt(state.details)
                        },
                        onShare = {
                            shareDetailsTxt(state.details)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkDetailsContent(
    details: NetworkDetails,
    targetSection: String?,
    showSsid: Boolean,
    showBssid: Boolean,
    onCopy: (String, String) -> Unit,
    onCopyFullReport: () -> Unit,
    onRequestPermission: () -> Unit,
    onSaveTxt: () -> Unit,
    onShare: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("network_info_lazy_column")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Connection Status Banner
        item {
            ConnectionStatusBanner(details)
        }

        val physical = details.physicalInfo
        val isWifiActive = physical?.isWifi == true || physical?.ssid != null

        // When launched from Wi-Fi Tools, prioritize live RSSI & SNR RF Telemetry and fluctuation graph
        if (targetSection == "wifi") {
            item {
                WifiRssiAndSnrSection(
                    rssiDbm = if (isWifiActive) physical?.signalStrengthDbm else null,
                    snrDb = if (isWifiActive) physical?.snrDb else null,
                    noiseFloorDbm = if (isWifiActive) physical?.noiseFloorDbm else null,
                    isWifiConnected = isWifiActive,
                    onCopyMetric = onCopy
                )
            }
            item {
                WifiSignalFluctuationGraph()
            }
        }

        // Diagnostic Messages & Warnings Card
        if (details.diagnosticWarnings.isNotEmpty()) {
            item {
                DiagnosticWarningsCard(details.diagnosticWarnings)
            }
        }

        // A. Connection Information Overview Card
        item {
            InfoSectionCard(title = "Connection Information", icon = Icons.Default.NetworkCheck) {
                InfoRow("Connection Type", details.connectionType, isPrimary = true, onCopy = onCopy)
                InfoRow(
                    "Connection Status",
                    if (details.isInternetValidated) "Connected (Internet Validated)" else "Connected (Unvalidated)",
                    badgeColor = if (details.isInternetValidated) StatusGreen else StatusAmber,
                    onCopy = onCopy
                )
                InfoRow("Active Network Interface", details.interfaceName ?: "Unavailable", isMonospace = true, onCopy = onCopy)
                InfoRow("Network Transport", details.networkTransport, onCopy = onCopy)
                InfoRow(
                    "VPN Status",
                    if (details.isVpnActive) "Active (Virtual Tunnel)" else "Inactive (Unencapsulated Route)",
                    badgeColor = if (details.isVpnActive) StatusBlue else StatusGreen,
                    onCopy = onCopy
                )
                if (details.isVpnActive && !details.underlyingConnectionType.isNullOrBlank()) {
                    InfoRow("Underlying Connection", details.underlyingConnectionType, onCopy = onCopy)
                }
                InfoRow(
                    "Network Metering",
                    if (details.isMetered) "Metered" else "Unmetered",
                    onCopy = onCopy
                )
            }
        }

        // B. IP Configuration Overview Card
        item {
            InfoSectionCard(title = "IP Configuration", icon = Icons.Default.Router) {
                val primaryIp = details.primaryIpv4
                val ipClass = IpAddressValidator.classifyIp(primaryIp, isVpnInterface = details.isVpnActive)
                InfoRowWithBadge(
                    label = "IPv4 Address",
                    value = primaryIp,
                    badge = ipClass.rangeName,
                    badgeColor = if (ipClass.isReservedOrDocumentation) StatusRed else StellarTeal,
                    onCopy = onCopy
                )
                val maskStr = details.ipv4SubnetMask ?: "Unavailable"
                val prefixStr = details.ipv4PrefixLength?.let { "/$it" } ?: "Unavailable"
                InfoRow("Subnet Mask", maskStr, isMonospace = maskStr != "Unavailable", onCopy = onCopy)
                InfoRow("Prefix Length", prefixStr, isMonospace = prefixStr != "Unavailable", onCopy = onCopy)
                val ipv6Str = details.ipv6Addresses.firstOrNull() ?: "No IPv6 Assigned"
                InfoRow("IPv6 Address", ipv6Str, isMonospace = true, onCopy = onCopy)
                val gw4 = details.defaultGatewayIpv4 ?: "Unavailable"
                InfoRow("Default Gateway (IPv4)", gw4, isPrimary = true, isMonospace = gw4 != "Unavailable", onCopy = onCopy)
                if (details.defaultGatewayIpv6 != null) {
                    InfoRow("Default Gateway (IPv6)", details.defaultGatewayIpv6, isMonospace = true, onCopy = onCopy)
                }
                val dnsStr = if (details.dnsServers.isNotEmpty()) {
                    details.dnsServers.joinToString(", ")
                } else {
                    "None reported by LinkProperties"
                }
                InfoRow("DNS Servers", dnsStr, isMonospace = details.dnsServers.isNotEmpty(), onCopy = onCopy)
                val mtuStr = details.mtu?.let { "$it bytes" } ?: "Unavailable"
                InfoRow("MTU", mtuStr, onCopy = onCopy)
                if (details.httpProxy != null) {
                    InfoRow("HTTP Proxy", details.httpProxy, isMonospace = true, onCopy = onCopy)
                }
                val pubIp = details.publicIpAddress ?: "Unavailable"
                InfoRow("Public IP Address", pubIp, isMonospace = true, onCopy = onCopy)
            }
        }

        // C. VPN / Virtual Tunnel Information
        if (details.isVpnActive && details.virtualInfo != null) {
            item {
                VirtualNetworkCard(
                    virtualInfo = details.virtualInfo,
                    underlyingConnectionType = details.underlyingConnectionType,
                    onCopy = onCopy
                )
            }
        } else {
            item {
                NoVpnCard(details.physicalInfo)
            }
        }

        // C2. Connection-Specific & Underlying Network Interface Information
        if (physical != null) {
            item {
                PhysicalNetworkCard(
                    physical = physical,
                    showSsid = showSsid,
                    showBssid = showBssid,
                    onCopy = onCopy,
                    onRequestPermission = onRequestPermission
                )
            }
            if (isWifiActive && targetSection != "wifi") {
                item {
                    WifiRssiAndSnrSection(
                        rssiDbm = physical.signalStrengthDbm,
                        snrDb = physical.snrDb,
                        noiseFloorDbm = physical.noiseFloorDbm,
                        isWifiConnected = true,
                        onCopyMetric = onCopy
                    )
                }
                item {
                    WifiSignalFluctuationGraph()
                }
            }
        }

        // D. DNS & Gateway Reachability Diagnostics Card
        item {
            InfoSectionCard(title = "Reachability & DNS Diagnostics", icon = Icons.Default.Security) {
                InfoRow(
                    "Internet Validation",
                    if (details.isInternetValidated) "Validated & Online" else "Connected (Unvalidated / Restricted)",
                    badgeColor = if (details.isInternetValidated) StatusGreen else StatusAmber,
                    onCopy = onCopy
                )
                val dnsStatus = details.dnsConnectivityStatus
                InfoRow(
                    "DNS Resolution Check",
                    dnsStatus,
                    badgeColor = if (dnsStatus.contains("Operational", ignoreCase = true)) StatusGreen else StatusAmber,
                    onCopy = onCopy
                )
                details.privateDnsInfo?.let { pdns ->
                    val pdnsColor = when (pdns.status) {
                        PrivateDnsStatus.HOSTNAME_ACTIVE -> StatusGreen
                        PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC -> StellarTeal
                        PrivateDnsStatus.DISABLED -> StatusAmber
                        PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE -> StatusRed
                        PrivateDnsStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    InfoRow(
                        label = "Private DNS (DoT)",
                        value = pdns.configuredHostname?.let { "${pdns.statusLabel} ($it)" } ?: pdns.statusLabel,
                        isMonospace = !pdns.configuredHostname.isNullOrBlank(),
                        badgeColor = pdnsColor,
                        onCopy = onCopy
                    )
                }
                val gwStatus = details.gatewayConnectivityStatus
                InfoRow(
                    "Gateway Reachability",
                    gwStatus,
                    badgeColor = if (gwStatus.contains("Reachable", ignoreCase = true)) StatusGreen else StatusBlue,
                    onCopy = onCopy
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Scope Note: Gateway reachability checks the local default router only and does not confirm Internet access. DNS resolution confirms hostname lookup, not remote host reachability or throughput.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
        }

        // Action Buttons Row (Save RTF / Share / Copy)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveTxt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_txt_diagnostics_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save RTF Report (Choose Folder)...")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_diagnostics_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share Details")
                    }

                    OutlinedButton(
                        onClick = onCopyFullReport,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("copy_full_report_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Report")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConnectionStatusBanner(details: NetworkDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (details.isInternetValidated) StatusGreen else StatusAmber)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (details.isInternetValidated) "CONNECTED & ONLINE" else "CONNECTED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (details.isInternetValidated) StatusGreen else StatusAmber,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = details.connectionType,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // VPN Badge
            if (details.isVpnActive) {
                Surface(
                    color = StatusBlue.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnLock,
                            contentDescription = null,
                            tint = StatusBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "VPN ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticWarningsCard(warnings: List<DiagnosticWarning>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CONNECTION DIAGNOSTICS & ALERTS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            warnings.forEachIndexed { index, w ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    val (icon, color) = when (w.severity) {
                        DiagnosticSeverity.SUCCESS -> Icons.Default.CheckCircle to StatusGreen
                        DiagnosticSeverity.ALERT -> Icons.Default.Warning to StatusRed
                        DiagnosticSeverity.WARNING -> Icons.Default.Warning to StatusAmber
                        DiagnosticSeverity.INFO -> Icons.Default.Info to StatusBlue
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = w.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (w.isSystemLimitation) {
                                Surface(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "OS Policy",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = w.message,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (index < warnings.lastIndex) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun VirtualNetworkCard(
    virtualInfo: VirtualNetworkInfo,
    underlyingConnectionType: String? = null,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = StatusBlue.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StatusBlue.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnLock,
                    contentDescription = null,
                    tint = StatusBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "VPN / VIRTUAL TUNNEL INFORMATION",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = StatusBlue
                )
            }
            HorizontalDivider(color = StatusBlue.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))

            InfoRow("VPN Status", virtualInfo.status, badgeColor = StatusGreen, onCopy = onCopy)
            InfoRow("VPN Interface", virtualInfo.interfaceName, isMonospace = true, onCopy = onCopy)
            if (!underlyingConnectionType.isNullOrBlank()) {
                InfoRow("Underlying Transport", underlyingConnectionType, onCopy = onCopy)
            }

            val vpnIp = virtualInfo.ipv4Address ?: "Unavailable"
            val classification = IpAddressValidator.classifyIp(vpnIp, isVpnInterface = true)
            InfoRowWithBadge(
                label = "VPN-Assigned IPv4",
                value = vpnIp,
                badge = classification.rangeName,
                badgeColor = if (classification.isReservedOrDocumentation) StatusRed else StatusBlue,
                onCopy = onCopy
            )
            if (virtualInfo.ipv4SubnetMask != null) {
                InfoRow("VPN Subnet Mask", virtualInfo.ipv4SubnetMask, isMonospace = true, onCopy = onCopy)
            }
            if (virtualInfo.ipv4PrefixLength != null) {
                InfoRow("VPN Prefix Length", "/${virtualInfo.ipv4PrefixLength}", isMonospace = true, onCopy = onCopy)
            }

            val dnsStr = if (virtualInfo.dnsServers.isNotEmpty()) {
                virtualInfo.dnsServers.joinToString(", ")
            } else {
                "Inherited from System"
            }
            InfoRow("VPN DNS Servers", dnsStr, isMonospace = virtualInfo.dnsServers.isNotEmpty(), onCopy = onCopy)

            val vpnIp6 = virtualInfo.ipv6Address ?: "No IPv6 Assigned"
            InfoRow("VPN IPv6 Address", vpnIp6, isMonospace = true, onCopy = onCopy)

            val mtuStr = virtualInfo.mtu?.let { "$it bytes" } ?: "Unavailable"
            InfoRow("VPN MTU", mtuStr, onCopy = onCopy)
            InfoRow("Routing Encapsulation", virtualInfo.routingInfo, onCopy = onCopy)
        }
    }
}

@Composable
private fun NoVpnCard(physical: PhysicalNetworkInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VpnLock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "VPN STATUS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusGreen)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Inactive (Unencapsulated Route)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusGreen
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val iface = physical?.interfaceName ?: "Unavailable"
            Text(
                text = "No active VPN tunnel reported by ConnectivityManager. Traffic routes through network interface ($iface) without VPN encapsulation.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PhysicalNetworkCard(
    physical: PhysicalNetworkInfo,
    showSsid: Boolean,
    showBssid: Boolean,
    onCopy: (String, String) -> Unit,
    onRequestPermission: () -> Unit
) {
    val sectionTitle = when {
        physical.isWifi -> "WI-FI CONNECTION DETAILS"
        physical.isCellular -> "MOBILE DATA CONNECTION DETAILS"
        physical.isEthernet -> "ETHERNET CONNECTION DETAILS"
        physical.isBluetooth -> "BLUETOOTH NETWORK DETAILS"
        else -> "NETWORK INTERFACE DETAILS"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = if (physical.isWifi) Icons.Default.Wifi else Icons.Default.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = sectionTitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(8.dp))

            InfoRow("Active Network Interface", physical.interfaceName ?: "Unavailable", isMonospace = true, onCopy = onCopy)
            InfoRow("Network Transport", physical.transportType, onCopy = onCopy)

            val physIp = physical.ipv4Address ?: "Unavailable"
            val classification = IpAddressValidator.classifyIp(physIp, isVpnInterface = false)
            InfoRowWithBadge(
                label = "IPv4 Address",
                value = physIp,
                badge = classification.rangeName,
                badgeColor = if (classification.isReservedOrDocumentation) StatusRed else StellarTeal,
                onCopy = onCopy
            )
            InfoRow("Subnet Mask", physical.ipv4SubnetMask ?: "Unavailable", isMonospace = physical.ipv4SubnetMask != null, onCopy = onCopy)
            InfoRow(
                "Prefix Length",
                physical.ipv4PrefixLength?.let { "/$it" } ?: "Unavailable",
                isMonospace = physical.ipv4PrefixLength != null,
                onCopy = onCopy
            )

            val physIp6 = physical.ipv6Address ?: "No IPv6 Assigned"
            InfoRow("IPv6 Address", physIp6, isMonospace = true, onCopy = onCopy)

            val gwStr = physical.defaultGatewayIpv4 ?: "Unavailable"
            InfoRow("Default Gateway", gwStr, isPrimary = true, isMonospace = gwStr != "Unavailable", onCopy = onCopy)

            InfoRow("Connection Status", physical.connectionStatus, badgeColor = StatusGreen, onCopy = onCopy)

            val dnsStr = if (physical.dnsServers.isNotEmpty()) {
                physical.dnsServers.joinToString(", ")
            } else {
                "None reported by LinkProperties"
            }
            InfoRow("DNS Servers", dnsStr, isMonospace = physical.dnsServers.isNotEmpty(), onCopy = onCopy)

            // Connection-Specific Section: Wi-Fi
            if (physical.isWifi || physical.ssid != null) {
                if (!showSsid) {
                    InfoRow("Wi-Fi Network (SSID)", WifiPrivacyPreferences.MASKED_SSID, onCopy = null)
                } else {
                    when {
                        physical.ssid != null -> InfoRow("Wi-Fi Network (SSID)", physical.ssid, onCopy = onCopy)
                        physical.ssidStatus == WifiIdentifierStatus.PERMISSION_REQUIRED -> LocationPermissionPrompt(onRequestPermission)
                        physical.ssidStatus == WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED ->
                            InfoRow("Wi-Fi Network (SSID)", "Unavailable (OS Restricted / Location Services Disabled)", onCopy = null)
                        else -> InfoRow("Wi-Fi Network (SSID)", "Unavailable", onCopy = null)
                    }
                }

                if (!showBssid) {
                    InfoRow("Access Point (BSSID)", WifiPrivacyPreferences.MASKED_BSSID, isMonospace = false, onCopy = null)
                } else {
                    val bssidDisplay = when {
                        physical.bssid != null -> physical.bssid
                        physical.bssidStatus == WifiIdentifierStatus.PERMISSION_REQUIRED -> "Unavailable (Location Permission Required)"
                        physical.bssidStatus == WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED -> "Unavailable (OS Restricted / Location Services Disabled)"
                        else -> "Unavailable"
                    }
                    InfoRow(
                        label = "Access Point (BSSID)",
                        value = bssidDisplay,
                        isMonospace = physical.bssid != null,
                        onCopy = if (physical.bssid != null) onCopy else null
                    )
                }

                val speedStr = physical.linkSpeedMbps?.let { "$it Mbps" } ?: "Unavailable"
                InfoRow("Link Speed (WLAN PHY)", speedStr, onCopy = onCopy)

                val rssiValueText = physical.signalStrengthDbm?.let { dbm ->
                    val tier = SignalStrengthTier.fromDbm(dbm)
                    "$dbm dBm • ${tier.label} (${tier.ceilingFloorKind})"
                } ?: "Unavailable"
                InfoRow(
                    label = "Signal Strength (RSSI)",
                    value = rssiValueText,
                    onCopy = onCopy
                )

                val snrValueText = physical.snrDb?.let { snr ->
                    val snrTier = SnrQualityTier.fromSnrDb(snr)
                    "$snr dB • ${snrTier.label}"
                } ?: "Unavailable"
                InfoRow(
                    label = "SNR (Signal-to-Noise Ratio)",
                    value = snrValueText,
                    onCopy = onCopy
                )

                val bandText = when {
                    physical.frequencyBand != null && physical.frequencyMhz != null ->
                        "${physical.frequencyBand} (${physical.frequencyMhz} MHz)"
                    physical.frequencyBand != null -> physical.frequencyBand
                    else -> "Unavailable"
                }
                InfoRow("Frequency Band", bandText, onCopy = onCopy)
                InfoRow("Channel", physical.wifiChannel?.let { "Channel $it" } ?: "Unavailable", onCopy = onCopy)
                InfoRow("Wi-Fi Standard", physical.wifiStandard ?: "Unavailable", onCopy = onCopy)
            }

            // Connection-Specific Section: Cellular / Mobile Data
            if (physical.isCellular) {
                InfoRow("Mobile Network Type", physical.cellularNetworkType ?: "Unavailable", onCopy = onCopy)
                InfoRow("Network Operator", physical.cellularOperatorName ?: "Unavailable", onCopy = onCopy)
                val downCap = physical.downstreamBandwidthKbps?.let { String.format(java.util.Locale.US, "%.1f Mbps (OS Cap)", it / 1000.0) } ?: "Unavailable"
                val upCap = physical.upstreamBandwidthKbps?.let { String.format(java.util.Locale.US, "%.1f Mbps (OS Cap)", it / 1000.0) } ?: "Unavailable"
                InfoRow("Downstream Cap Estimate", downCap, onCopy = onCopy)
                InfoRow("Upstream Cap Estimate", upCap, onCopy = onCopy)
            }

            // Connection-Specific Section: Ethernet
            if (physical.isEthernet) {
                InfoRow("Ethernet Interface", physical.interfaceName ?: "Unavailable", isMonospace = true, onCopy = onCopy)
                InfoRow("Link Status", physical.ethernetLinkStatus ?: "Link Up", onCopy = onCopy)
                InfoRow("Interface Link Speed", "Unavailable (Not exposed via standard SDK)", onCopy = onCopy)
            }

            val mtuStr = physical.mtu?.let { "$it bytes" } ?: "Unavailable"
            InfoRow("MTU", mtuStr, onCopy = onCopy)

            if (physical.isWifi) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Wi-Fi Note: Signal strength (RSSI), SNR, and PHY Link Speed reflect local wireless link conditions between this device and the access point—not overall Internet throughput.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun LocationPermissionPrompt(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SSID Masked by Android OS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Location permission is needed to read Wi-Fi network name.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = StatusAmber),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("Enable", color = DarkSlate, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun InfoSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isPrimary: Boolean = false,
    isMonospace: Boolean = false,
    badgeColor: Color? = null,
    onCopy: ((String, String) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onCopy != null) {
                onCopy?.invoke(label, value)
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.2f)
        )

        Row(
            modifier = Modifier.weight(1.8f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                fontSize = if (isMonospace) 12.sp else 13.sp,
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Medium,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color = badgeColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 2
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoRowWithBadge(
    label: String,
    value: String,
    badge: String,
    badgeColor: Color,
    onCopy: ((String, String) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onCopy != null) {
                onCopy?.invoke(label, value)
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.1f)
        )

        Column(
            modifier = Modifier.weight(1.9f),
            horizontalAlignment = Alignment.End
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (onCopy != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Surface(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = badge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun copyText(context: Context, label: String, text: String) {
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = label,
        text = text,
        markSensitive = true
    )
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun copyDetailsToClipboard(context: Context, details: NetworkDetails) {
    val report = buildFullReport(details)
    val phys = details.physicalInfo
    val identifiers = TextReportExportManager.collectIdentifiers(
        context = context,
        extraSsids = listOfNotNull(phys?.ssid),
        extraBssids = listOfNotNull(phys?.bssid)
    )
    TextReportExportManager.copyRedactedToClipboard(
        context = context,
        label = "Network Diagnostics",
        rawContent = report,
        identifiers = identifiers,
        isJson = false,
        markSensitive = true
    ).onSuccess {
        Toast.makeText(context, "Redacted network report copied to clipboard", Toast.LENGTH_SHORT).show()
    }.onFailure { err ->
        Toast.makeText(
            context,
            err.message ?: "Unable to copy report cleanly without leaking sensitive identifiers",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun buildFullReport(d: NetworkDetails): String {
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — CONNECTION REPORT")
        appendLine("==========================================")
        appendLine("--- CONNECTION INFORMATION ---")
        appendLine("Connection Type:          ${d.connectionType}")
        appendLine("Connection Status:        ${if (d.isInternetValidated) "Connected (Internet Validated)" else "Connected (Unvalidated)"}")
        appendLine("Active Network Interface: ${d.interfaceName ?: "Unavailable"}")
        appendLine("Network Transport:        ${d.networkTransport}")
        appendLine("VPN Status:               ${if (d.isVpnActive) "Active (Virtual Tunnel)" else "Inactive (Unencapsulated Route)"}")
        if (d.isVpnActive && !d.underlyingConnectionType.isNullOrBlank()) {
            appendLine("Underlying Connection:    ${d.underlyingConnectionType}")
        }
        appendLine("Network Metering:         ${if (d.isMetered) "Metered" else "Unmetered"}")
        appendLine("------------------------------------------")

        appendLine("--- IP CONFIGURATION ---")
        appendLine("IPv4 Address:             ${d.primaryIpv4}")
        appendLine("Subnet Mask:              ${d.ipv4SubnetMask ?: "Unavailable"}")
        appendLine("Prefix Length:            ${d.ipv4PrefixLength?.let { "/$it" } ?: "Unavailable"}")
        appendLine("IPv6 Address:             ${d.ipv6Addresses.firstOrNull() ?: "No IPv6 Assigned"}")
        appendLine("Default Gateway (IPv4):   ${d.defaultGatewayIpv4 ?: "Unavailable"}")
        if (!d.defaultGatewayIpv6.isNullOrBlank()) {
            appendLine("Default Gateway (IPv6):   ${d.defaultGatewayIpv6}")
        }
        appendLine("DNS Servers:              ${if (d.dnsServers.isNotEmpty()) d.dnsServers.joinToString(", ") else "None reported"}")
        appendLine("MTU:                      ${d.mtu?.let { "$it bytes" } ?: "Unavailable"}")
        if (!d.httpProxy.isNullOrBlank()) {
            appendLine("HTTP Proxy:               ${d.httpProxy}")
        }
        appendLine("Public IP Address:        ${d.publicIpAddress ?: "Unavailable"}")
        appendLine("------------------------------------------")

        if (d.isVpnActive && d.virtualInfo != null) {
            val v = d.virtualInfo
            appendLine("--- VPN / VIRTUAL TUNNEL INFORMATION ---")
            appendLine("VPN Interface:            ${v.interfaceName}")
            appendLine("VPN IPv4 Address:         ${v.ipv4Address ?: "Unavailable"}")
            if (!v.ipv4SubnetMask.isNullOrBlank()) {
                appendLine("VPN Subnet Mask:          ${v.ipv4SubnetMask}")
            }
            if (v.ipv4PrefixLength != null) {
                appendLine("VPN Prefix Length:        /${v.ipv4PrefixLength}")
            }
            appendLine("VPN IPv6 Address:         ${v.ipv6Address ?: "No IPv6 Assigned"}")
            appendLine("VPN DNS Servers:          ${if (v.dnsServers.isNotEmpty()) v.dnsServers.joinToString(", ") else "System/Inherited"}")
            appendLine("VPN MTU:                  ${v.mtu?.let { "$it bytes" } ?: "Unavailable"}")
            appendLine("Routing Encapsulation:    ${v.routingInfo}")
            appendLine("VPN Tunnel Status:        ${v.status}")
            appendLine("------------------------------------------")
        }

        if (d.physicalInfo != null) {
            val p = d.physicalInfo
            appendLine("--- CONNECTION-SPECIFIC INTERFACE DETAILS ---")
            appendLine("Network Interface:        ${p.interfaceName ?: "Unavailable"}")
            appendLine("Network Transport:        ${p.transportType}")
            appendLine("IPv4 Address:             ${p.ipv4Address ?: "Unavailable"}")
            appendLine("Subnet Mask:              ${p.ipv4SubnetMask ?: "Unavailable"}")
            appendLine("Prefix Length:            ${p.ipv4PrefixLength?.let { "/$it" } ?: "Unavailable"}")
            appendLine("IPv6 Address:             ${p.ipv6Address ?: "No IPv6 Assigned"}")
            appendLine("Default Gateway (IPv4):   ${p.defaultGatewayIpv4 ?: "Unavailable"}")
            appendLine("DNS Servers:              ${if (p.dnsServers.isNotEmpty()) p.dnsServers.joinToString(", ") else "None reported"}")
            appendLine("Connection Status:        ${p.connectionStatus}")
            if (p.isWifi || p.ssid != null) {
                val ssidReport = p.ssid ?: when (p.ssidStatus) {
                    WifiIdentifierStatus.PERMISSION_REQUIRED -> "Unavailable (Location Permission Required)"
                    WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED -> "Unavailable (OS Restricted / Location Services Disabled)"
                    else -> "Unavailable"
                }
                val bssidReport = p.bssid ?: when (p.bssidStatus) {
                    WifiIdentifierStatus.PERMISSION_REQUIRED -> "Unavailable (Location Permission Required)"
                    WifiIdentifierStatus.LOCATION_SERVICES_DISABLED_OR_RESTRICTED -> "Unavailable (OS Restricted / Location Services Disabled)"
                    else -> "Unavailable"
                }
                appendLine("SSID:                     $ssidReport")
                appendLine("BSSID:                    $bssidReport")
                appendLine("Link Speed (WLAN PHY):    ${p.linkSpeedMbps?.let { "$it Mbps" } ?: "Unavailable"}")
                val rssiReport = p.signalStrengthDbm?.let { dbm ->
                    "$dbm dBm (${SignalStrengthTier.fromDbm(dbm).label})"
                } ?: "Unavailable"
                appendLine("Signal Strength (RSSI):   $rssiReport")
                val snrReport = p.snrDb?.let { snr ->
                    "$snr dB (${SnrQualityTier.fromSnrDb(snr).label})"
                } ?: "Unavailable"
                appendLine("SNR (Signal-to-Noise):    $snrReport")
                appendLine("Frequency Band:           ${p.frequencyBand ?: "Unavailable"}")
                appendLine("Channel:                  ${p.wifiChannel?.let { "Channel $it" } ?: "Unavailable"}")
                appendLine("Wi-Fi Standard:           ${p.wifiStandard ?: "Unavailable"}")
            }
            if (p.isCellular) {
                appendLine("Mobile Network Type:      ${p.cellularNetworkType ?: "Unavailable"}")
                appendLine("Network Operator:         ${p.cellularOperatorName ?: "Unavailable"}")
            }
            if (p.isEthernet) {
                appendLine("Ethernet Interface:       ${p.interfaceName ?: "Unavailable"}")
                appendLine("Ethernet Link Status:     ${p.ethernetLinkStatus ?: "Link Up"}")
                appendLine("Ethernet Link Speed:      Unavailable (Not exposed via standard SDK)")
            }
            appendLine("Interface MTU:            ${p.mtu?.let { "$it bytes" } ?: "Unavailable"}")
            appendLine("------------------------------------------")
        }

        appendLine("--- REACHABILITY & DNS DIAGNOSTICS ---")
        appendLine("Internet Validation:      ${if (d.isInternetValidated) "Validated & Online" else "Unvalidated / Restricted"}")
        appendLine("DNS Resolution Check:     ${d.dnsConnectivityStatus}")
        d.privateDnsInfo?.let { pdns ->
            appendLine("Private DNS (DoT):        ${pdns.statusLabel}")
            if (!pdns.configuredHostname.isNullOrBlank()) {
                appendLine("Private DNS Hostname:     ${pdns.configuredHostname}")
            }
            appendLine("Private DNS Detail:       ${pdns.detailMessage}")
        }
        appendLine("Gateway Reachability:     ${d.gatewayConnectivityStatus}")
        appendLine("Diagnostic Scope Notes:")
        appendLine("• Wi-Fi RSSI, SNR, and Link Speed measure local wireless link conditions, not Internet throughput.")
        appendLine("• Gateway reachability checks the local default router only, not outbound Internet access.")
        appendLine("• DNS lookup validates hostname resolution, not remote host reachability or throughput.")
        appendLine("------------------------------------------")

        if (d.diagnosticWarnings.isNotEmpty()) {
            appendLine("DIAGNOSTIC MESSAGES & WARNINGS")
            d.diagnosticWarnings.forEach { w ->
                val type = if (w.isSystemLimitation) "[OS Policy]" else "[Network]"
                appendLine("• ${w.title} $type: ${w.message}")
            }
            appendLine("------------------------------------------")
        }
        appendLine("==========================================")
    }
}
