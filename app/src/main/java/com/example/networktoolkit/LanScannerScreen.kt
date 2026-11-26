package com.example.networktoolkit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Web
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSlate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal

/**
 * Screen displaying the LAN Scanner with subnet sweep and active devices list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScannerScreen(
    viewModel: LanScannerViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exportController = rememberSafTextExportController()

    val saveLanTxt: (List<DiscoveredDevice>) -> Unit = { devices ->
        val rawReport = buildLanReport(devices)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("LAN Scan RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("LAN_Scan_Report", "rtf"),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareLanTxt: (List<DiscoveredDevice>) -> Unit = { devices ->
        val rawReport = buildLanReport(devices)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("LAN Scan RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = TextReportExportManager.suggestFilename("LAN_Scan_Report", "rtf"),
                subject = "LAN Network Scan Report",
                chooserTitle = "Share Discovered Devices",
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
                        text = "LAN Subnet Scanner",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("lan_scan_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val devices = when (val s = uiState) {
                        is LanScannerUiState.Scanning -> s.devices
                        is LanScannerUiState.Completed -> s.devices
                        else -> emptyList()
                    }
                    if (devices.isNotEmpty()) {
                        IconButton(
                            onClick = { saveLanTxt(devices) },
                            modifier = Modifier.testTag("lan_scan_save_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Save RTF Report"
                            )
                        }
                        IconButton(
                            onClick = {
                                val report = buildLanReport(devices)
                                copyReportToClipboard(context, "Discovered Devices", report)
                            },
                            modifier = Modifier.testTag("lan_scan_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy List"
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refreshSubnet() },
                        modifier = Modifier.testTag("lan_scan_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Subnet"
                        )
                    }
                    ToolContextualHelpAction(toolId = "lan_scanner")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val subnet = when (val s = uiState) {
                is LanScannerUiState.Idle -> s.detectedSubnet
                is LanScannerUiState.Scanning -> s.subnetInfo
                is LanScannerUiState.Completed -> s.subnetInfo
                else -> null
            }

            // Subnet Information Card & Scan Trigger
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LOCAL NETWORK SUBNET",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (subnet != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "/${subnet.prefixLength}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (subnet != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Local IPv4 Address", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(subnet.localIp, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Default Gateway", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(subnet.gatewayIp ?: "Unknown", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sweep Range", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${subnet.firstHost} - ${subnet.lastHost}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Total Hosts", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${subnet.totalHosts} devices", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Text(
                            text = "No Wi-Fi/Ethernet subnet detected. Connect to a local network to scan.",
                            fontSize = 13.sp,
                            color = StatusAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Controls
                    val isScanning = uiState is LanScannerUiState.Scanning
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isScanning) {
                            Button(
                                onClick = { viewModel.stopScan() },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("lan_scan_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop Sweep", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startScan() },
                                enabled = subnet != null,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("lan_scan_start_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Start Subnet Sweep", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Real-time Progress & Status Bar
            when (val s = uiState) {
                is LanScannerUiState.Scanning -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Sweeping subnet: ${s.scannedCount} of ${s.totalHosts} probed",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${s.devices.size} active found",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val progress = if (s.totalHosts > 0) s.scannedCount.toFloat() / s.totalHosts.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is LanScannerUiState.Completed -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = StatusGreen.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Scan Complete: ${s.devices.size} active devices in ${s.durationMs / 1000}s",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusGreen
                                )
                            }
                            Row {
                                IconButton(
                                    onClick = { saveLanTxt(s.devices) },
                                    modifier = Modifier.testTag("lan_card_save_txt_button")
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = "Save RTF", tint = StatusGreen)
                                }
                                IconButton(
                                    onClick = { shareLanTxt(s.devices) }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = StatusGreen)
                                }
                            }
                        }
                    }
                }
                is LanScannerUiState.Error -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        color = StatusRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = s.message,
                            fontSize = 12.sp,
                            color = StatusRed,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                else -> {}
            }

            // Discovered Devices Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DISCOVERED DEVICES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val deviceCount = when (val s = uiState) {
                    is LanScannerUiState.Scanning -> s.devices.size
                    is LanScannerUiState.Completed -> s.devices.size
                    else -> 0
                }
                Text(
                    text = "$deviceCount active",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Devices List in LazyColumn
            val devices = when (val s = uiState) {
                is LanScannerUiState.Scanning -> s.devices
                is LanScannerUiState.Completed -> s.devices
                else -> emptyList()
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Devices Discovered",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Start Subnet Sweep' to probe for connected devices on this network.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(devices, key = { it.ip }) { device ->
                        DeviceListItem(
                            device = device,
                            onCopy = { label, value ->
                                copyToClipboard(context, label, value)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceListItem(
    device: DiscoveredDevice,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onCopy("Device IP", device.ip)
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                device.isCurrentDevice -> StellarTeal.copy(alpha = 0.10f)
                device.isGateway -> StatusAmber.copy(alpha = 0.10f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (device.isCurrentDevice) {
            androidx.compose.foundation.BorderStroke(1.dp, StellarTeal.copy(alpha = 0.4f))
        } else if (device.isGateway) {
            androidx.compose.foundation.BorderStroke(1.dp, StatusAmber.copy(alpha = 0.4f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Device Icon & Details
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device Type Icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                device.isCurrentDevice -> StellarTeal.copy(alpha = 0.2f)
                                device.isGateway -> StatusAmber.copy(alpha = 0.2f)
                                device.deviceType == DeviceType.WEB_SERVER -> StatusBlue.copy(alpha = 0.2f)
                                device.deviceType == DeviceType.SSH_SERVER -> StatusGreen.copy(alpha = 0.2f)
                                device.deviceType == DeviceType.NETWORK_PRINTER -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getDeviceIcon(device.deviceType),
                        contentDescription = null,
                        tint = when {
                            device.isCurrentDevice -> StellarTeal
                            device.isGateway -> StatusAmber
                            device.deviceType == DeviceType.WEB_SERVER -> StatusBlue
                            device.deviceType == DeviceType.SSH_SERVER -> StatusGreen
                            device.deviceType == DeviceType.NETWORK_PRINTER -> Color(0xFF8B5CF6)
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.ip,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy IP",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(12.dp)
                        )
                    }

                    if (device.hostname != null) {
                        Text(
                            text = device.hostname,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (device.isCurrentDevice) {
                            RoleBadge("This Android Device", StellarTeal)
                        } else if (device.isGateway) {
                            RoleBadge("Default Gateway / Router", StatusAmber)
                        } else if (device.serviceName != null) {
                            Text(
                                text = device.serviceName,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Right: Response Time Badge
            Surface(
                color = if (device.responseTimeMs < 20) StatusGreen.copy(alpha = 0.15f) else StatusBlue.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (device.isCurrentDevice) "Local" else "${device.responseTimeMs} ms",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (device.responseTimeMs < 20) StatusGreen else StatusBlue,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RoleBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

private fun getDeviceIcon(type: DeviceType): ImageVector {
    return when (type) {
        DeviceType.GATEWAY -> Icons.Default.Router
        DeviceType.CURRENT_DEVICE -> Icons.Default.Smartphone
        DeviceType.WEB_SERVER -> Icons.Default.Web
        DeviceType.SSH_SERVER -> Icons.Default.Terminal
        DeviceType.FILE_SERVER -> Icons.Default.FolderShared
        DeviceType.NETWORK_PRINTER -> Icons.Default.Print
        DeviceType.DNS_SERVER -> Icons.Default.Dns
        DeviceType.GENERIC_HOST -> Icons.Default.Computer
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = label,
        text = text,
        markSensitive = true
    )
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private fun copyReportToClipboard(context: Context, label: String, rawReport: String) {
    TextReportExportManager.copyRedactedToClipboard(
        context = context,
        label = label,
        rawContent = rawReport,
        markSensitive = true
    ).onSuccess {
        Toast.makeText(context, "Redacted $label copied", Toast.LENGTH_SHORT).show()
    }.onFailure { err ->
        Toast.makeText(context, err.message ?: "Unable to copy report safely", Toast.LENGTH_LONG).show()
    }
}

private fun buildLanReport(devices: List<DiscoveredDevice>): String {
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — LAN SCAN REPORT")
        appendLine("==========================================")
        appendLine("Total Active Devices Found: ${devices.size}")
        appendLine("------------------------------------------")
        appendLine(String.format("%-18s  %-8s  %s", "IP ADDRESS", "RTT", "SERVICE / HOST"))
        appendLine("------------------------------------------")
        devices.forEach { dev ->
            val hostStr = dev.hostname ?: dev.serviceName ?: "Active Host"
            appendLine(String.format("%-18s  %-8s  %s", dev.ip, "${dev.responseTimeMs}ms", hostStr))
        }
        appendLine("==========================================")
    }
}
