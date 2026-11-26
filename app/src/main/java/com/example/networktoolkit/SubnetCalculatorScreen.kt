package com.example.networktoolkit

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubnetCalculatorScreen(
    viewModel: SubnetCalculatorViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportController = rememberSafTextExportController()

    val saveSubnetTxt: (SubnetResult) -> Unit = { r ->
        val rawReport = buildSubnetReport(r)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("Subnet Calculator RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("Subnet_Calculation_Report", "rtf"),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareSubnetTxt: (SubnetResult) -> Unit = { r ->
        val rawReport = buildSubnetReport(r)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("Subnet Calculator RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = TextReportExportManager.suggestFilename("Subnet_Calculation_Report", "rtf"),
                subject = "Subnet Calculation - ${r.networkAddress}${r.cidrNotation}",
                chooserTitle = "Share Subnet Report",
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
                        text = "Subnet Calculator",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("subnet_calc_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.result != null) {
                        val r = uiState.result!!
                        IconButton(
                            onClick = { saveSubnetTxt(r) },
                            modifier = Modifier.testTag("save_subnet_txt_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Save RTF Report")
                        }
                        IconButton(
                            onClick = {
                                val report = buildSubnetReport(r)
                                copySubnetToClipboard(context, "Subnet Calculation", report)
                            },
                            modifier = Modifier.testTag("copy_subnet_report_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report")
                        }
                    }
                    ToolContextualHelpAction(toolId = "subnet_calculator")
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Input Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "IPv4 Address & CIDR Prefix",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = uiState.ipInput,
                            onValueChange = { viewModel.onIpChanged(it) },
                            label = { Text("IP Address") },
                            placeholder = { Text("192.168.1.1") },
                            singleLine = true,
                            isError = uiState.errorMessage != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("subnet_ip_input")
                        )

                        if (uiState.errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = StatusRed,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // CIDR Prefix Selector Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "CIDR: /${uiState.prefix}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Mask: ${SubnetCalculatorEngine.getSubnetMaskForPrefix(uiState.prefix)}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Slider(
                            value = uiState.prefix.toFloat(),
                            onValueChange = { viewModel.onPrefixChanged(it.toInt()) },
                            valueRange = 0f..32f,
                            steps = 31,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("subnet_prefix_slider")
                        )

                        // Quick Presets
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val presets = listOf(8, 16, 24, 26, 28, 29, 30, 31, 32)
                            items(presets) { p ->
                                val isSelected = uiState.prefix == p
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.clickable { viewModel.onPrefixChanged(p) }
                                ) {
                                    Text(
                                        text = "/$p",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Results Card
            val result = uiState.result
            if (result != null) {
                item {
                    SubnetResultsCard(result = result, onCopy = { label, value ->
                        copySubnetToClipboard(context, label, value)
                    })
                }

                item {
                    BinaryRepresentationCard(result = result)
                }

                // Action Buttons
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { saveSubnetTxt(result) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("subnet_save_txt_bottom_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save RTF Report (Choose Folder)...")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { shareSubnetTxt(result) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("share_subnet_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }

                            OutlinedButton(
                                onClick = {
                                    val report = buildSubnetReport(result)
                                    copySubnetToClipboard(context, "Subnet Calculation", report)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("copy_full_subnet_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Full Report")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SubnetResultsCard(
    result: SubnetResult,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Scope Badge + Class
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Calculate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SUBNET SPECIFICATIONS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StellarTeal.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = result.ipClass,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StellarTeal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = result.addressScope,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            SubnetFieldRow("Network Address", "${result.networkAddress} ${result.cidrNotation}", isPrimary = true, onCopy = onCopy)
            SubnetFieldRow("Broadcast Address", result.broadcastAddress, onCopy = onCopy)
            SubnetFieldRow("Subnet Mask", result.subnetMask, onCopy = onCopy)
            SubnetFieldRow("Wildcard Mask", result.wildcardMask, onCopy = onCopy)
            SubnetFieldRow("Usable Host Range", "${result.firstUsableHost} — ${result.lastUsableHost}", onCopy = onCopy)
            SubnetFieldRow("Usable Hosts", "%,d".format(result.usableHostsCount), onCopy = onCopy)
            SubnetFieldRow("Total Addresses", "%,d".format(result.totalAddressesCount), onCopy = onCopy)
        }
    }
}

@Composable
private fun BinaryRepresentationCard(result: SubnetResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Numbers,
                    contentDescription = null,
                    tint = StatusBlue,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BINARY BITMAP BREAKDOWN",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = StatusBlue
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            BinaryRow("IP Address", result.ipBinary)
            BinaryRow("Subnet Mask", result.maskBinary)
            BinaryRow("Network Bits", result.networkBinary)
            BinaryRow("Broadcast Bits", result.broadcastBinary)
        }
    }
}

@Composable
private fun BinaryRow(label: String, binary: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = binary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SubnetFieldRow(
    label: String,
    value: String,
    isPrimary: Boolean = false,
    onCopy: (String, String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(label, value) }
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = if (isPrimary) 14.sp else 13.sp,
                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
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

private fun buildSubnetReport(r: SubnetResult): String {
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — SUBNET CALCULATION")
        appendLine("==========================================")
        appendLine("Input IP:          ${r.inputIp}")
        appendLine("CIDR Prefix:       ${r.cidrNotation}")
        appendLine("Network Address:   ${r.networkAddress}")
        appendLine("Broadcast Address: ${r.broadcastAddress}")
        appendLine("Subnet Mask:       ${r.subnetMask}")
        appendLine("Wildcard Mask:     ${r.wildcardMask}")
        appendLine("Usable Host Range: ${r.firstUsableHost} — ${r.lastUsableHost}")
        appendLine("Usable Hosts:      %,d".format(r.usableHostsCount))
        appendLine("Total Addresses:   %,d".format(r.totalAddressesCount))
        appendLine("IP Class:          ${r.ipClass}")
        appendLine("Address Scope:     ${r.addressScope}")
        appendLine("------------------------------------------")
        appendLine("BINARY BREAKDOWN:")
        appendLine("IP:        ${r.ipBinary}")
        appendLine("Mask:      ${r.maskBinary}")
        appendLine("Network:   ${r.networkBinary}")
        appendLine("Broadcast: ${r.broadcastBinary}")
        appendLine("==========================================")
    }
}

private fun copySubnetToClipboard(context: Context, label: String, text: String) {
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = label,
        text = text,
        markSensitive = true
    )
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}
