package com.example.networktoolkit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsLookupScreen(
    viewModel: DnsLookupViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dnsEnv by viewModel.dnsEnvironment.collectAsStateWithLifecycle()
    val targetInput by viewModel.targetInput.collectAsStateWithLifecycle()
    val selectedQueryType by viewModel.selectedQueryType.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val allowPublicDohFallback by viewModel.allowPublicDohFallback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportController = rememberSafTextExportController()

    val saveDnsTxt: (DnsLookupResult) -> Unit = { res ->
        exportController.promptPrivacyAndRun("DNS lookup RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("DNS_Lookup_Report", "rtf"),
                rawContent = buildDnsReport(res, dnsEnv),
                identifiers = TextReportExportManager.collectIdentifiers(context),
                privacyChoice = privacyChoice
            )
        }
    }

    val shareDnsTxt: (DnsLookupResult) -> Unit = { res ->
        exportController.promptPrivacyAndRun("DNS lookup report share") { privacyChoice ->
            exportController.shareReport(
                rawContent = buildDnsReport(res, dnsEnv),
                suggestedFilename = TextReportExportManager.suggestFilename("DNS_Lookup_Report", "rtf"),
                subject = "DNS Lookup Report - ${res.query}",
                chooserTitle = "Share DNS Report",
                identifiers = TextReportExportManager.collectIdentifiers(context),
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
                        text = "DNS Lookup & Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("dns_lookup_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshDnsEnvironment() },
                        modifier = Modifier.testTag("refresh_dns_env_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh DNS Config")
                    }
                    if (uiState is DnsLookupUiState.Success) {
                        val result = (uiState as DnsLookupUiState.Success).result
                        IconButton(
                            onClick = { saveDnsTxt(result) },
                            modifier = Modifier.testTag("save_dns_txt_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Save RTF Report")
                        }
                        IconButton(
                            onClick = {
                                val report = buildDnsReport(result, dnsEnv)
                                copyDnsToClipboard(context, "DNS Records", report)
                            },
                            modifier = Modifier.testTag("copy_dns_report_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report")
                        }
                    }
                    ToolContextualHelpAction(toolId = "dns_lookup")
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
            // Active System DNS vs Private DNS Diagnostics Card
            item {
                ActiveDnsConfigurationCard(
                    env = dnsEnv,
                    onCopy = { label, value -> copyDnsToClipboard(context, label, value) }
                )
            }

            // Input Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Domain or Hostname Lookup",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = targetInput,
                                onValueChange = { viewModel.onTargetChanged(it) },
                                label = { Text("Hostname / IP") },
                                placeholder = { Text("example.com or 8.8.8.8") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("dns_host_input")
                            )

                            Button(
                                onClick = { viewModel.performLookup(targetInput, selectedQueryType) },
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("dns_lookup_submit_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lookup")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Query Type:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val qTypes = listOf("ALL", "A", "AAAA", "MX", "TXT", "NS", "CNAME", "PTR")
                            items(qTypes) { qt ->
                                val isSelected = selectedQueryType == qt
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier.clickable {
                                        viewModel.onQueryTypeSelected(qt)
                                        if (targetInput.isNotBlank()) {
                                            viewModel.performLookup(targetInput, qt)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = qt,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Quick Target Presets
                        Text(
                            text = "Quick Targets:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val targets = listOf("google.com", "cloudflare.com", "github.com", "8.8.8.8", "1.1.1.1")
                            items(targets) { t ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                    modifier = Modifier.clickable {
                                        viewModel.onTargetChanged(t)
                                        viewModel.performLookup(t, selectedQueryType)
                                    }
                                ) {
                                    Text(
                                        text = t,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(
                                    text = "Public DoH Opt-In (MX / TXT / NS / CNAME & Fallback)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (allowPublicDohFallback) {
                                        "Enabled for public domains (dns.google / cloudflare-dns.com). Internal/local (.local, .lan, .internal, single-label) names are never sent."
                                    } else {
                                        "Disabled (System DNS Only). Respects Private DNS and keeps all lookups on your device resolver."
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp
                                )
                            }
                            Switch(
                                checked = allowPublicDohFallback,
                                onCheckedChange = { viewModel.onPublicDohFallbackChanged(it) },
                                modifier = Modifier.testTag("dns_public_doh_switch")
                            )
                        }
                    }
                }
            }

            // State Display
            when (val state = uiState) {
                is DnsLookupUiState.Loading -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = "Resolving ${state.queryType} records for ${state.target}...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                is DnsLookupUiState.Error -> {
                    val err = state.details
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns_error_card"),
                            colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusRed, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = err.category.displayTitle.uppercase(Locale.US),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = err.message,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Query: ${err.query} (${err.queryType}) • ${err.technicalDetails}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (err.troubleshootingSteps.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    err.troubleshootingSteps.forEach { step ->
                                        Text(
                                            text = "• $step",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is DnsLookupUiState.Success -> {
                    val res = state.result
                    val filterTypes = listOf("ALL", "A", "AAAA", "MX", "TXT", "NS", "CNAME", "PTR")

                    // Performance & Resolver Path Summary Banner
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = null,
                                            tint = StatusGreen,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${res.responseTimeMs} ms Resolution",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusGreen
                                        )
                                    }

                                    Text(
                                        text = "${res.queryType} • ${res.records.size} Record(s)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Connection Type: ${res.activeTransport}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Resolver Path: ${res.resolverSummary}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (res.privacyNote.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Privacy: ${res.privacyNote}",
                                        fontSize = 10.sp,
                                        color = if (res.usedPublicDoh) StatusAmber else StatusGreen
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Scope: DNS lookup verifies hostname-to-record resolution only, not host reachability or network throughput.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    // Record Type Filter Chips
                    if (res.records.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(filterTypes) { f ->
                                    val isSelected = selectedFilter.equals(f, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) else null,
                                        modifier = Modifier.clickable { viewModel.onFilterSelected(f) }
                                    ) {
                                        Text(
                                            text = f,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Records List
                    val filteredRecords = res.records.filter { r ->
                        selectedFilter == "ALL" || r.type.equals(selectedFilter, ignoreCase = true)
                    }

                    if (filteredRecords.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = if (res.records.isEmpty()) {
                                        "Domain '${res.query}' exists (NOERROR), but publishes 0 ${res.queryType} records."
                                    } else {
                                        "No $selectedFilter records returned for ${res.query}."
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(filteredRecords) { record ->
                            DnsRecordCard(record = record, onCopy = {
                                copyDnsToClipboard(context, "${record.type} Record", record.value)
                            })
                        }
                    }

                    // Bottom Action Row
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { saveDnsTxt(res) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("dns_save_txt_bottom_button"),
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
                                    onClick = { shareDnsTxt(res) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .testTag("share_dns_button")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val report = buildDnsReport(res, dnsEnv)
                                        copyDnsToClipboard(context, "DNS Report", report)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .testTag("copy_full_dns_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy All Records")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                DnsLookupUiState.Idle -> {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dns_idle_card"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Ready for DNS Diagnostics",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "No automatic lookup runs on open. Tap Lookup or a quick target to query your active resolver.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDnsConfigurationCard(
    env: ActiveDnsEnvironment,
    onCopy: (String, String) -> Unit
) {
    val pdns = env.privateDnsInfo
    val pdnsColor = when (pdns.status) {
        PrivateDnsStatus.HOSTNAME_ACTIVE -> StatusGreen
        PrivateDnsStatus.AUTOMATIC_OPPORTUNISTIC -> StellarTeal
        PrivateDnsStatus.DISABLED -> StatusAmber
        PrivateDnsStatus.VALIDATION_OR_CONNECTIVITY_FAILURE -> StatusRed
        PrivateDnsStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_dns_config_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, pdnsColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ACTIVE DNS CONFIGURATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (env.isConnected && env.isInternetValidated) {
                        StatusGreen.copy(alpha = 0.16f)
                    } else if (env.isConnected) {
                        StatusAmber.copy(alpha = 0.16f)
                    } else {
                        StatusRed.copy(alpha = 0.16f)
                    }
                ) {
                    Text(
                        text = env.activeTransport,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (env.isConnected && env.isInternetValidated) {
                            StatusGreen
                        } else if (env.isConnected) {
                            StatusAmber
                        } else {
                            StatusRed
                        },
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // 1. Network-Assigned DNS Servers (DHCP / Carrier / VPN LinkProperties)
            Text(
                text = "Network-Assigned DNS Servers (LinkProperties):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            val assignedText = if (env.networkAssignedDnsServers.isNotEmpty()) {
                env.networkAssignedDnsServers.joinToString(", ")
            } else if (env.isConnected) {
                "None reported by active LinkProperties"
            } else {
                "Unavailable (Device offline)"
            }
            Text(
                text = assignedText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable(enabled = env.networkAssignedDnsServers.isNotEmpty()) {
                        onCopy("Network-Assigned DNS", assignedText)
                    }
                    .testTag("network_assigned_dns_text")
            )
            if (env.vpnDnsServers.isNotEmpty() && env.physicalDnsServers.isNotEmpty()) {
                Text(
                    text = "Underlying Network DNS Servers: ${env.physicalDnsServers.joinToString(", ")}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Android Private DNS (DNS-over-TLS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = pdnsColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Android Private DNS (DoT):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = pdnsColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = pdns.statusLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = pdnsColor,
                        modifier = Modifier
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .testTag("private_dns_status_badge")
                    )
                }
            }

            if (!pdns.configuredHostname.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Hostname: ${pdns.configuredHostname}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = pdnsColor,
                    modifier = Modifier
                        .clickable { onCopy("Private DNS Hostname", pdns.configuredHostname) }
                        .testTag("private_dns_hostname_text")
                )
            }

            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = pdns.detailMessage,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
                modifier = Modifier.testTag("private_dns_detail_text")
            )
        }
    }
}

@Composable
private fun DnsRecordCard(
    record: DnsRecord,
    onCopy: () -> Unit
) {
    val typeColor = when (record.type.uppercase(Locale.US)) {
        "A" -> StatusGreen
        "AAAA" -> StatusBlue
        "MX" -> StellarTeal
        "TXT" -> StatusAmber
        "NS" -> Color(0xFF8B5CF6)
        "PTR" -> Color(0xFFEC4899)
        "CNAME" -> Color(0xFF06B6D4)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() }
            .testTag("dns_record_${record.type}_${record.value}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, typeColor.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = typeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = record.type,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.ttl?.let { "TTL: ${it}s" } ?: "TTL: N/A (System DNS)",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Record",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = record.value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (record.name.isNotBlank() && record.name != record.value) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Name: ${record.name}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun buildDnsReport(res: DnsLookupResult, env: ActiveDnsEnvironment): String {
    val pdns = res.privateDnsInfo ?: env.privateDnsInfo
    val assigned = (res.networkAssignedDnsServers.ifEmpty { env.networkAssignedDnsServers })
        .takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None reported"
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — DNS LOOKUP REPORT")
        appendLine("==========================================")
        appendLine("Target Query:        ${res.query}")
        appendLine("Query Type:          ${res.queryType}")
        appendLine("Response Time:       ${res.responseTimeMs} ms")
        appendLine("Connection Type:     ${res.activeTransport}")
        appendLine("Network DNS Servers: $assigned")
        appendLine("Private DNS Status:  ${pdns.statusLabel}")
        if (!pdns.configuredHostname.isNullOrBlank()) {
            appendLine("Private DNS Host:    ${pdns.configuredHostname}")
        }
        appendLine("Private DNS Detail:  ${pdns.detailMessage}")
        appendLine("Resolver Path:       ${res.resolverSummary}")
        if (res.privacyNote.isNotBlank()) {
            appendLine("Privacy Mode:        ${res.privacyNote}")
        }
        appendLine("Total Records:       ${res.records.size}")
        appendLine("Scope Note:          Verifies hostname resolution only, not host reachability or throughput.")
        appendLine("------------------------------------------")
        res.records.forEach { r ->
            val ttlLabel = r.ttl?.let { "${it}s" } ?: "N/A (System DNS)"
            appendLine("[${r.type.padEnd(5)}] ${r.value} (TTL: $ttlLabel)")
        }
        appendLine("==========================================")
    }
}

private fun copyDnsToClipboard(context: Context, label: String, text: String) {
    val identifiers = TextReportExportManager.collectIdentifiers(context)
    TextReportExportManager.copyRedactedToClipboard(
        context = context,
        label = label,
        rawContent = text,
        identifiers = identifiers,
        isJson = false,
        markSensitive = true
    ).onSuccess {
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }.onFailure { err ->
        Toast.makeText(
            context,
            err.message ?: "Unable to copy cleanly",
            Toast.LENGTH_LONG
        ).show()
    }
}
