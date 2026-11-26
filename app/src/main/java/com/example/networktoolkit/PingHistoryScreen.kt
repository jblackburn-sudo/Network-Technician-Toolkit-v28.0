package com.example.networktoolkit

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.networktoolkit.data.DiagnosticHistoryRepository
import com.example.networktoolkit.data.DiagnosticReportExporter
import com.example.networktoolkit.data.PingResultEntity
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.TracerouteResultEntity
import com.example.networktoolkit.data.WifiReportExportPrivacyChoice
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import com.example.ui.theme.DarkSlate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic History screen displaying past Ping and Traceroute sessions.
 * Allows technicians to search, review, delete, and export reports as Text or JSON
 * for sharing via email or messaging apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingHistoryScreen(
    viewModel: PingHistoryViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pings by viewModel.pingItems.collectAsStateWithLifecycle()
    val traceroutes by viewModel.tracerouteItems.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClearDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    val exportController = rememberSafTextExportController()
    var pendingCsvPrivacyChoice by remember { mutableStateOf(WifiReportExportPrivacyChoice.REDACTED) }

    // Storage Access Framework launcher for CSV export
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val activeChoice = pendingCsvPrivacyChoice
        pendingCsvPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
        if (uri == null) {
            Toast.makeText(context, "CSV export cancelled", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.exportToUri(context, uri, privacyChoice = activeChoice) { result ->
                result.onSuccess { count ->
                    Toast.makeText(context, "Exported $count records to CSV!", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    exportController.reportFailure(err, "Writing CSV records to selected SAF URI")
                }
            }
        }
    }

    // Confirmation dialog for clearing database
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Diagnostic History") },
            text = { Text("Are you sure you want to delete all stored ping and traceroute test records? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearDialog = false
                        Toast.makeText(context, "Diagnostic history cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear All", color = StatusRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Comprehensive Export & Sharing Dialog
    if (showExportDialog) {
        ExportReportDialog(
            pingsCount = pings.size,
            traceroutesCount = traceroutes.size,
            onDismiss = { showExportDialog = false },
            onSaveTxt = {
                showExportDialog = false
                exportController.promptPrivacyAndRun("Diagnostic History RTF export") { privacyChoice ->
                    exportController.launchSaveTxt(
                        suggestedFilename = DiagnosticReportExporter.getDefaultFilename(isJson = false),
                        rawContent = viewModel.generateTextReport(),
                        identifiers = TextReportExportManager.collectIdentifiers(context),
                        privacyChoice = privacyChoice
                    )
                }
            },
            onShareText = {
                showExportDialog = false
                exportController.promptPrivacyAndRun("Diagnostic History RTF share") { privacyChoice ->
                    viewModel.createShareReportIntent(context, isJson = false, privacyChoice = privacyChoice)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (t: Throwable) {
                                exportController.reportFailure(t, "Launching Android share chooser")
                            }
                        }.onFailure { err ->
                            exportController.reportFailure(err, "Preparing RTF history report")
                        }
                }
            },
            onShareJson = {
                showExportDialog = false
                exportController.promptPrivacyAndRun("Diagnostic History JSON share") { privacyChoice ->
                    viewModel.createShareReportIntent(context, isJson = true, privacyChoice = privacyChoice)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (t: Throwable) {
                                exportController.reportFailure(t, "Launching Android share chooser")
                            }
                        }.onFailure { err ->
                            exportController.reportFailure(err, "Preparing JSON diagnostic report")
                        }
                }
            },
            onCopyText = {
                showExportDialog = false
                viewModel.copyReportToClipboard(context, isJson = false)
            },
            onCopyJson = {
                showExportDialog = false
                viewModel.copyReportToClipboard(context, isJson = true)
            },
            onExportCsv = {
                showExportDialog = false
                exportController.promptPrivacyAndRun("Diagnostic History CSV export") { privacyChoice ->
                    pendingCsvPrivacyChoice = privacyChoice
                    try {
                        exportCsvLauncher.launch(viewModel.getDefaultFilename())
                    } catch (t: Throwable) {
                        pendingCsvPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
                        exportController.reportFailure(t, "Opening Android file picker for CSV export")
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Diagnostic Test History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("ping_history_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val totalRecords = pings.size + traceroutes.size
                    if (totalRecords > 0) {
                        // Quick Share Report Button
                        IconButton(
                            onClick = { showExportDialog = true },
                            modifier = Modifier.testTag("diagnostic_history_export_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export & Share Diagnostic Report",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showExportMenu = true },
                                modifier = Modifier.testTag("ping_history_more_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options"
                                )
                            }

                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Save RTF Report (Choose Folder)...") },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        exportController.promptPrivacyAndRun("Diagnostic History RTF export") { privacyChoice ->
                                            exportController.launchSaveTxt(
                                                suggestedFilename = DiagnosticReportExporter.getDefaultFilename(isJson = false),
                                                rawContent = viewModel.generateTextReport(),
                                                identifiers = TextReportExportManager.collectIdentifiers(context),
                                                privacyChoice = privacyChoice
                                            )
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export RTF / JSON Report") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        showExportDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Ping CSV to Storage...") },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = StatusBlue)
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        exportController.promptPrivacyAndRun("Ping CSV export") { privacyChoice ->
                                            pendingCsvPrivacyChoice = privacyChoice
                                            try {
                                                exportCsvLauncher.launch(viewModel.getDefaultFilename())
                                            } catch (t: Throwable) {
                                                pendingCsvPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
                                                exportController.reportFailure(t, "Opening Android file picker for CSV export")
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Save Ping CSV to Downloads") },
                                    leadingIcon = {
                                        Icon(Icons.Default.TableChart, contentDescription = null, tint = StatusGreen)
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        exportController.promptPrivacyAndRun("Ping CSV Downloads save") { privacyChoice ->
                                            viewModel.exportToDownloads(context, privacyChoice = privacyChoice) { result ->
                                                result.onSuccess {
                                                    Toast.makeText(context, "Saved CSV to Downloads!", Toast.LENGTH_LONG).show()
                                                }.onFailure { err ->
                                                    exportController.reportFailure(err, "Saving Ping CSV to MediaStore Downloads")
                                                }
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Clear All History", color = StatusRed) },
                                    leadingIcon = {
                                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = StatusRed)
                                    },
                                    onClick = {
                                        showExportMenu = false
                                        showClearDialog = true
                                    }
                                )
                            }
                        }
                    }
                    ToolContextualHelpAction(toolId = "test_history")
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
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("ping_history_search_input"),
                placeholder = { Text("Search by target host or IP...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Diagnostic Filter Tabs
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == DiagnosticTab.ALL,
                    onClick = { viewModel.setSelectedTab(DiagnosticTab.ALL) },
                    text = {
                        Text(
                            text = "All (${pings.size + traceroutes.size})",
                            fontWeight = if (selectedTab == DiagnosticTab.ALL) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTab == DiagnosticTab.PING,
                    onClick = { viewModel.setSelectedTab(DiagnosticTab.PING) },
                    text = {
                        Text(
                            text = "Ping (${pings.size})",
                            fontWeight = if (selectedTab == DiagnosticTab.PING) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTab == DiagnosticTab.TRACEROUTE,
                    onClick = { viewModel.setSelectedTab(DiagnosticTab.TRACEROUTE) },
                    text = {
                        Text(
                            text = "Traceroute (${traceroutes.size})",
                            fontWeight = if (selectedTab == DiagnosticTab.TRACEROUTE) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            // Summary Bar
            val totalPings = pings.size
            val totalTraces = traceroutes.size
            if (totalPings + totalTraces > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recorded Sessions: $totalPings ping, $totalTraces traceroute",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Tap cards for details",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Diagnostic Items List
            val displayPings = if (selectedTab != DiagnosticTab.TRACEROUTE) pings else emptyList()
            val displayTraces = if (selectedTab != DiagnosticTab.PING) traceroutes else emptyList()
            val hasItems = displayPings.isNotEmpty() || displayTraces.isNotEmpty()

            if (!hasItems) {
                EmptyDiagnosticHistoryState(
                    hasSearchQuery = searchQuery.isNotEmpty(),
                    onClearSearch = { viewModel.onSearchQueryChanged("") }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Ping items
                    items(displayPings, key = { "ping_${it.id}" }) { ping ->
                        PingHistoryItemCard(
                            item = ping,
                            onDelete = { viewModel.deletePingItem(ping.id) },
                            onShareText = {
                                exportController.promptPrivacyAndRun("Ping session RTF share") { privacyChoice ->
                                    viewModel.createShareSinglePingIntent(context, ping, isJson = false, privacyChoice = privacyChoice)
                                        .onSuccess { intent ->
                                            try {
                                                context.startActivity(intent)
                                            } catch (t: Throwable) {
                                                exportController.reportFailure(t, "Launching Android share chooser")
                                            }
                                        }
                                        .onFailure { err ->
                                            exportController.reportFailure(err, "Preparing ping RTF report")
                                        }
                                }
                            },
                            onShareJson = {
                                exportController.promptPrivacyAndRun("Ping session JSON share") { privacyChoice ->
                                    viewModel.createShareSinglePingIntent(context, ping, isJson = true, privacyChoice = privacyChoice)
                                        .onSuccess { intent ->
                                            try {
                                                context.startActivity(intent)
                                            } catch (t: Throwable) {
                                                exportController.reportFailure(t, "Launching Android share chooser")
                                            }
                                        }
                                        .onFailure { err ->
                                            exportController.reportFailure(err, "Preparing ping JSON report")
                                        }
                                }
                            }
                        )
                    }

                    // Traceroute items
                    items(displayTraces, key = { "trace_${it.id}" }) { trace ->
                        TracerouteHistoryItemCard(
                            item = trace,
                            onDelete = { viewModel.deleteTracerouteItem(trace.id) },
                            onShareText = {
                                exportController.promptPrivacyAndRun("Traceroute session RTF share") { privacyChoice ->
                                    viewModel.createShareSingleTracerouteIntent(context, trace, isJson = false, privacyChoice = privacyChoice)
                                        .onSuccess { intent ->
                                            try {
                                                context.startActivity(intent)
                                            } catch (t: Throwable) {
                                                exportController.reportFailure(t, "Launching Android share chooser")
                                            }
                                        }
                                        .onFailure { err ->
                                            exportController.reportFailure(err, "Preparing traceroute RTF report")
                                        }
                                }
                            },
                            onShareJson = {
                                exportController.promptPrivacyAndRun("Traceroute session JSON share") { privacyChoice ->
                                    viewModel.createShareSingleTracerouteIntent(context, trace, isJson = true, privacyChoice = privacyChoice)
                                        .onSuccess { intent ->
                                            try {
                                                context.startActivity(intent)
                                            } catch (t: Throwable) {
                                                exportController.reportFailure(t, "Launching Android share chooser")
                                            }
                                        }
                                        .onFailure { err ->
                                            exportController.reportFailure(err, "Preparing traceroute JSON report")
                                        }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog presenting Text, JSON, and CSV diagnostic export and sharing options.
 */
@Composable
private fun ExportReportDialog(
    pingsCount: Int,
    traceroutesCount: Int,
    onDismiss: () -> Unit,
    onSaveTxt: () -> Unit,
    onShareText: () -> Unit,
    onShareJson: () -> Unit,
    onCopyText: () -> Unit,
    onCopyJson: () -> Unit,
    onExportCsv: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Export Diagnostic Report")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Export $pingsCount ping and $traceroutesCount traceroute session(s). Saved RTF files automatically redact SSID & BSSID.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Option 0: Save RTF via Storage Access Framework
                Button(
                    onClick = onSaveTxt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_save_txt_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save RTF Report (Choose Folder)...")
                }

                // Option 1: Share Formatted RTF Report
                OutlinedButton(
                    onClick = onShareText,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Formatted RTF (Email / Apps)")
                }

                // Option 2: Share JSON Report
                OutlinedButton(
                    onClick = onShareJson,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Structured JSON Report")
                }

                // Option 3: Copy Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyText,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Copy Text", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onCopyJson,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Copy JSON", fontSize = 12.sp)
                    }
                }

                // Option 4: Ping CSV
                TextButton(
                    onClick = onExportCsv,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export Ping Data to CSV File", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Card representing a past ping test session.
 */
@Composable
private fun PingHistoryItemCard(
    item: PingResultEntity,
    onDelete: () -> Unit,
    onShareText: () -> Unit,
    onShareJson: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val sdf = remember { SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.US) }
    val dateStr = remember(item.timestamp) { sdf.format(Date(item.timestamp)) }

    val lossColor = when {
        item.packetLossPercentage == 0.0 -> StatusGreen
        item.packetLossPercentage < 20.0 -> StatusAmber
        else -> StatusRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("ping_history_item_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Type Badge + Host + Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ping Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StatusBlue.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = "PING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StatusBlue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.targetHost,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.resolvedIp,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Loss Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = lossColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.0f%% Loss", item.packetLossPercentage),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = lossColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Card Overflow Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share as Text Report") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showMenu = false
                                onShareText()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share as JSON") },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = StatusBlue) },
                            onClick = {
                                showMenu = false
                                onShareJson()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy Summary") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                val text = DiagnosticReportExporter.generateSinglePingText(item)
                                DiagnosticReportExporter.copyToClipboard(context, text, "Ping Summary")
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Record", color = StatusRed) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusRed) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Latency Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(title = "MIN", value = String.format(Locale.US, "%.1f ms", item.minRttMs))
                MetricColumn(title = "AVG", value = String.format(Locale.US, "%.1f ms", item.avgRttMs), highlight = true)
                MetricColumn(title = "MAX", value = String.format(Locale.US, "%.1f ms", item.maxRttMs))
                MetricColumn(title = "PACKETS", value = "${item.packetsReceived}/${item.packetsTransmitted}")
            }

            Spacer(Modifier.height(6.dp))

            // Timestamp and Duration Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$dateStr • ${item.protocolMode}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "Hide Console Log" else "View Details",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Expandable Console Log
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "CONSOLE LOG:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DarkSlate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.logOutput.ifBlank { "No console log captured." },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF81C784),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card representing a past traceroute test session.
 */
@Composable
private fun TracerouteHistoryItemCard(
    item: TracerouteResultEntity,
    onDelete: () -> Unit,
    onShareText: () -> Unit,
    onShareJson: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val sdf = remember { SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.US) }
    val dateStr = remember(item.timestamp) { sdf.format(Date(item.timestamp)) }
    val parsedHops = remember(item.hopsJson) { DiagnosticHistoryRepository.parseHopsJson(item.hopsJson) }

    val statusColor = if (item.isDestinationReached) StatusGreen else StatusAmber
    val statusText = if (item.isDestinationReached) "REACHED" else "MAX HOPS"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("traceroute_history_item_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Type Badge + Host + Status Pill + Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Traceroute Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StellarTeal.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = "TRACE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = StellarTeal,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.targetHost,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.targetIp,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Destination Status Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Card Overflow Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share as Text Report") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showMenu = false
                                onShareText()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share as JSON") },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = StatusBlue) },
                            onClick = {
                                showMenu = false
                                onShareJson()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy Summary") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                val text = DiagnosticReportExporter.generateSingleTracerouteText(item)
                                DiagnosticReportExporter.copyToClipboard(context, text, "Traceroute Summary")
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Record", color = StatusRed) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusRed) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Traceroute Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(title = "TOTAL HOPS", value = "${item.totalHops}")
                MetricColumn(
                    title = "AVG RTT",
                    value = if (item.avgRttMs != null && item.avgRttMs > 0) String.format(Locale.US, "%.1f ms", item.avgRttMs) else "N/A",
                    highlight = true
                )
                MetricColumn(title = "DURATION", value = String.format(Locale.US, "%.1f s", item.durationMs / 1000.0))
                MetricColumn(title = "HOPS RECORDED", value = "${parsedHops.size}")
            }

            Spacer(Modifier.height(6.dp))

            // Timestamp and Hop Table Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "Hide Hops Table" else "View All Hops (${parsedHops.size})",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Expandable Hops Breakdown Table
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "INTERMEDIATE HOPS TRACE:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))

                    if (parsedHops.isEmpty()) {
                        Text(
                            text = "No hop breakdown stored for this trace.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        parsedHops.forEach { hop ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${hop.hopNumber}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hop.ip,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!hop.hostname.isNullOrBlank()) {
                                        Text(
                                            text = hop.hostname,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Text(
                                    text = if (hop.rttMs != null) String.format(Locale.US, "%.1f ms", hop.rttMs) else "* ms",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hop.rttMs != null) StatusGreen else StatusAmber
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
private fun MetricColumn(
    title: String,
    value: String,
    highlight: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Placeholder displayed when no diagnostic test history matches the current query or filter.
 */
@Composable
private fun EmptyDiagnosticHistoryState(
    hasSearchQuery: Boolean,
    onClearSearch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (hasSearchQuery) Icons.Default.Search else Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (hasSearchQuery) "No Matching Test Sessions" else "No Diagnostic History Yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (hasSearchQuery) {
                    "No diagnostic results match your search query."
                } else {
                    "Run a Ping Test or Traceroute to automatically record diagnostic sessions. Past results can be exported as Text or JSON reports."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (hasSearchQuery) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onClearSearch) {
                    Text("Clear Search Query")
                }
            }
        }
    }
}
