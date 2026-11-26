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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import java.util.Locale

enum class TracerouteViewMode {
    DIRECTED_GRAPH,
    LIST
}

/**
 * Screen displaying the traceroute utility and presenting the path hops as an interactive directed graph or list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracerouteScreen(
    viewModel: TracerouteViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var targetInput by rememberSaveable { mutableStateOf("8.8.8.8") }
    var maxHops by rememberSaveable { mutableIntStateOf(20) }
    var viewMode by rememberSaveable { mutableStateOf(TracerouteViewMode.DIRECTED_GRAPH) }

    val exportController = rememberSafTextExportController()

    val saveTracerouteTxt: (List<TracerouteHop>) -> Unit = { hops ->
        val rawReport = buildTracerouteReport(targetInput, hops)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("Traceroute RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("Traceroute_Report", "rtf"),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareTracerouteTxt: (List<TracerouteHop>) -> Unit = { hops ->
        val rawReport = buildTracerouteReport(targetInput, hops)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("Traceroute RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = TextReportExportManager.suggestFilename("Traceroute_Report", "rtf"),
                subject = "Traceroute Path to $targetInput",
                chooserTitle = "Share Traceroute Path",
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
                        text = "Traceroute Path Hops",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("traceroute_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val hops = when (val s = uiState) {
                        is TracerouteUiState.Running -> s.hops
                        is TracerouteUiState.Completed -> s.summary.hops
                        else -> emptyList()
                    }
                    if (hops.isNotEmpty()) {
                        IconButton(
                            onClick = { saveTracerouteTxt(hops) },
                            modifier = Modifier.testTag("traceroute_save_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Save RTF Report"
                            )
                        }
                        IconButton(
                            onClick = {
                                val report = buildTracerouteReport(targetInput, hops)
                                copyReportToClipboard(context, "Traceroute Hops", report)
                            },
                            modifier = Modifier.testTag("traceroute_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Report"
                            )
                        }
                    }
                    ToolContextualHelpAction(toolId = "traceroute")
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
            // Configuration & Input Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    Text(
                        text = "DESTINATION HOST OR IP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        singleLine = true,
                        placeholder = { Text("Enter domain or IP (e.g. 1.1.1.1, google.com)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("traceroute_target_input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetChip("8.8.8.8", onClick = { targetInput = "8.8.8.8" })
                        PresetChip("1.1.1.1", onClick = { targetInput = "1.1.1.1" })
                        PresetChip("google.com", onClick = { targetInput = "google.com" })
                        PresetChip("192.168.1.1", onClick = { targetInput = "192.168.1.1" })
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Max Hops and Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Max Hops: ",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(15, 20, 30).forEach { limit ->
                                Surface(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .clickable { maxHops = limit },
                                    color = if (maxHops == limit) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (maxHops == limit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                ) {
                                    Text(
                                        text = "$limit",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (maxHops == limit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        val isRunning = uiState is TracerouteUiState.Running
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.stopTraceroute() },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("traceroute_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (targetInput.isNotBlank()) {
                                        viewModel.startTraceroute(targetInput.trim(), maxHops, context)
                                    } else {
                                        Toast.makeText(context, "Enter a valid host or IP", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("traceroute_start_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Trace Route", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Status Progress Bar
            when (val s = uiState) {
                is TracerouteUiState.Running -> {
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
                                text = "Probing Hop ${s.currentHop} of ${s.maxHops}...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Target: ${s.targetIp}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val progress = if (s.maxHops > 0) s.currentHop.toFloat() / s.maxHops.toFloat() else 0f
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
                is TracerouteUiState.Completed -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (s.summary.isDestinationReached) StatusGreen.copy(alpha = 0.12f) else StatusAmber.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (s.summary.isDestinationReached) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (s.summary.isDestinationReached) StatusGreen else StatusAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (s.summary.isDestinationReached) "DESTINATION REACHED" else "TRACEROUTE FINISHED",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (s.summary.isDestinationReached) StatusGreen else StatusAmber
                                    )
                                    val avgText = s.summary.avgRttMs?.let { String.format(Locale.US, "Avg RTT: %.2f ms", it) } ?: "Avg RTT: --"
                                    Text(
                                        text = "${s.summary.totalHops} hops discovered • $avgText",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Row {
                                IconButton(
                                    onClick = { saveTracerouteTxt(s.summary.hops) },
                                    modifier = Modifier.testTag("traceroute_card_save_txt_button")
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = "Save RTF", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = { shareTracerouteTxt(s.summary.hops) }
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                is TracerouteUiState.Error -> {
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

            // Path Hops List & Topology Header with View Mode Toggle
            val hopsList = when (val s = uiState) {
                is TracerouteUiState.Running -> s.hops
                is TracerouteUiState.Completed -> s.summary.hops
                else -> emptyList()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PATH TOPOLOGY (${hopsList.size} HOPS)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // View Mode Toggle (D3 Graph vs Hop List)
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(2.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .clickable { viewMode = TracerouteViewMode.DIRECTED_GRAPH }
                            .testTag("traceroute_view_graph_button"),
                        color = if (viewMode == TracerouteViewMode.DIRECTED_GRAPH) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = null,
                                tint = if (viewMode == TracerouteViewMode.DIRECTED_GRAPH) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "D3 Graph",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == TracerouteViewMode.DIRECTED_GRAPH) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clickable { viewMode = TracerouteViewMode.LIST }
                            .testTag("traceroute_view_list_button"),
                        color = if (viewMode == TracerouteViewMode.LIST) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                tint = if (viewMode == TracerouteViewMode.LIST) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Hop List",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (viewMode == TracerouteViewMode.LIST) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (hopsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Hops Probed Yet",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap 'Trace Route' above to discover each router along the path.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                val isCompleted = uiState is TracerouteUiState.Completed
                val destReached = (uiState as? TracerouteUiState.Completed)?.summary?.isDestinationReached
                    ?: hopsList.any { it.isDestinationReached }
                when (viewMode) {
                    TracerouteViewMode.DIRECTED_GRAPH -> {
                        TracerouteD3GraphView(
                            hops = hopsList,
                            target = targetInput,
                            isCompleted = isCompleted,
                            destinationReached = destReached,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    TracerouteViewMode.LIST -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(hopsList, key = { it.hopNumber }) { hop ->
                                HopListItem(
                                    hop = hop,
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
    }
}

@Composable
private fun HopListItem(
    hop: TracerouteHop,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!hop.isTimedOut && hop.ip != "*") {
                    onCopy("Hop ${hop.hopNumber} IP", hop.ip)
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                hop.isDestinationReached -> StatusGreen.copy(alpha = 0.10f)
                hop.isTimedOut -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (hop.isDestinationReached) {
            androidx.compose.foundation.BorderStroke(1.dp, StatusGreen.copy(alpha = 0.5f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Hop Badge and IP/Hostname details
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hop Number Badge
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                hop.isDestinationReached -> StatusGreen
                                hop.isTimedOut -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${hop.hopNumber}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hop.isDestinationReached) DarkSlate else Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (hop.isTimedOut) "* (No response)" else hop.ip,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (hop.isTimedOut) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (!hop.isTimedOut && hop.ip != "*") {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy IP",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    if (!hop.hostname.isNullOrBlank()) {
                        Text(
                            text = hop.hostname,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }

                    if (hop.alternateIps.isNotEmpty()) {
                        Text(
                            text = "Alternate IPs: ${hop.alternateIps.joinToString(", ")}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = StatusBlue
                        )
                    }

                    Text(
                        text = hop.statusText,
                        fontSize = 11.sp,
                        color = if (hop.isDestinationReached) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right: Latency Pill
            Surface(
                color = when {
                    hop.isTimedOut -> StatusRed.copy(alpha = 0.12f)
                    (hop.rttMs ?: 0.0) < 50 -> StatusGreen.copy(alpha = 0.15f)
                    (hop.rttMs ?: 0.0) < 100 -> StatusBlue.copy(alpha = 0.15f)
                    else -> StatusAmber.copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = when {
                        hop.isTimedOut -> "* * *"
                        hop.rttMs != null -> String.format(Locale.US, "%.1f ms", hop.rttMs)
                        else -> "--"
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        hop.isTimedOut -> StatusRed
                        (hop.rttMs ?: 0.0) < 50 -> StatusGreen
                        (hop.rttMs ?: 0.0) < 100 -> StatusBlue
                        else -> StatusAmber
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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

internal fun buildTracerouteReport(target: String, hops: List<TracerouteHop>): String {
    val hasReservedHops = hops.any {
        !it.isTimedOut &&
            com.example.networktoolkit.diagnostics.IpAddressValidator.classifyIp(it.ip).rangeName.startsWith("Reserved (240.0.0.0/4)")
    }
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — TRACEROUTE REPORT")
        appendLine("==========================================")
        appendLine("Target: $target")
        appendLine("Total Hops Discovered: ${hops.size}")
        appendLine("Scope Note: Measures Layer-3 routing path hops and probe RTT to each responsive hop.")
        if (hasReservedHops) {
            appendLine("Carrier Note: Hops in 240.0.0.0/4 (e.g., 255.0.0.x) reflect reserved synthetic addresses returned by cellular/464XLAT infrastructure in ICMP Time Exceeded replies.")
        }
        appendLine("------------------------------------------")
        appendLine(String.format(Locale.US, "%-4s  %-39s  %-12s  %s", "HOP", "IP ADDRESS", "RTT", "STATUS"))
        appendLine("------------------------------------------")
        hops.forEach { hop ->
            val rtt = if (hop.isTimedOut) "* * *" else hop.rttMs?.let { String.format(Locale.US, "%.2f ms", it) } ?: "--"
            appendLine(String.format(Locale.US, "#%-3d  %-39s  %-12s  %s", hop.hopNumber, hop.ip, rtt, hop.statusText))
            if (!hop.hostname.isNullOrBlank()) {
                appendLine("      Host: ${hop.hostname}")
            }
            if (hop.alternateIps.isNotEmpty()) {
                appendLine("      Alternate IPs: ${hop.alternateIps.joinToString(", ")}")
            }
        }
        appendLine("==========================================")
    }
}
