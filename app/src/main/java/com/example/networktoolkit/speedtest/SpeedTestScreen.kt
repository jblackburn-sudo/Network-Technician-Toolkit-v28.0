package com.example.networktoolkit.speedtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import com.example.networktoolkit.ConnectivitySnapshot
import com.example.networktoolkit.GatewayStatus
import com.example.networktoolkit.InternetStatus
import com.example.networktoolkit.NetworkConnectivityMonitor
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Native Internet Speed Test Screen displaying real-time download and upload measurements,
 * latency, jitter, gauge visualization, and test report generation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    viewModel: SpeedTestViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connectivitySnapshot by NetworkConnectivityMonitor.getInstance(context).snapshot.collectAsStateWithLifecycle()
    var selectedService by remember {
        mutableStateOf(SpeedTestServicePreferences.getSelectedService(context))
    }
    var showServiceDialog by remember { mutableStateOf(false) }
    val exportController = rememberSafTextExportController()

    val launchWebSpeedTest: (SpeedTestServiceOption) -> Unit = { service ->
        try {
            val intent = Intent(context, com.example.networktoolkit.WebTestActivity::class.java).apply {
                putExtra("target_url", service.url)
                putExtra("target_title", service.name)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.url))
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Unable to open ${service.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Internet Speed Test",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("speed_test_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showServiceDialog = true },
                        modifier = Modifier.testTag("speed_test_top_change_service_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Change Speed Test Service Website"
                        )
                    }
                    if (uiState is SpeedTestUiState.Completed) {
                        val result = (uiState as SpeedTestUiState.Completed).result
                        IconButton(
                            onClick = {
                                val report = buildSpeedTestReport(result)
                                copyToClipboard(context, "Speed Test Report", report)
                            },
                            modifier = Modifier.testTag("speed_test_copy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Report"
                            )
                        }
                    }
                    ToolContextualHelpAction(toolId = "speed_test")
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Real-Time Field Connectivity Monitor Header
            item {
                SpeedTestConnectivityHeader(
                    snapshot = connectivitySnapshot,
                    onRefresh = { NetworkConnectivityMonitor.getInstance(context).refreshNow() }
                )
            }

            // Speedometer Gauge Card
            item {
                SpeedometerCard(uiState)
            }

            // Progress Bar & Phase Status
            item {
                SpeedTestStatusCard(uiState)
            }

            // Cleaner Material 3 Metrics Grid (Download, Upload, Jitter, and Latency)
            item {
                MetricsOverviewGrid(uiState)
            }

            // Connection Performance Assessment Card (when completed)
            if (uiState is SpeedTestUiState.Completed) {
                item {
                    ConnectionAssessmentCard((uiState as SpeedTestUiState.Completed).result)
                }
            }

            // Controls (Start / Stop / Save RTF / Share)
            item {
                ControlsSection(
                    uiState = uiState,
                    onStart = { viewModel.startSpeedTest() },
                    onStop = { viewModel.stopSpeedTest() },
                    onSaveTxt = {
                        if (uiState is SpeedTestUiState.Completed) {
                            val result = (uiState as SpeedTestUiState.Completed).result
                            val rawReport = buildSpeedTestReport(result)
                            val identifiers = TextReportExportManager.collectIdentifiers(context)
                            exportController.promptPrivacyAndRun("Speed Test RTF export") { privacyChoice ->
                                exportController.launchSaveTxt(
                                    suggestedFilename = TextReportExportManager.suggestFilename("Speed_Test_Report", "rtf"),
                                    rawContent = rawReport,
                                    identifiers = identifiers,
                                    privacyChoice = privacyChoice
                                )
                            }
                        }
                    },
                    onShare = {
                        if (uiState is SpeedTestUiState.Completed) {
                            val result = (uiState as SpeedTestUiState.Completed).result
                            val dlLabel = if (result.bytesDownloaded > 0L && result.downloadMbps >= 0.0) {
                                "${String.format(Locale.US, "%.1f", result.downloadMbps)} Mbps Down"
                            } else {
                                "Download Unavailable"
                            }
                            val rawReport = buildSpeedTestReport(result)
                            val identifiers = TextReportExportManager.collectIdentifiers(context)
                            exportController.promptPrivacyAndRun("Speed Test RTF share") { privacyChoice ->
                                exportController.shareReport(
                                    rawContent = rawReport,
                                    suggestedFilename = TextReportExportManager.suggestFilename("Speed_Test_Report", "rtf"),
                                    subject = "Network Speed Test Report: $dlLabel",
                                    chooserTitle = "Share Speed Test Results",
                                    identifiers = identifiers,
                                    isJson = false,
                                    privacyChoice = privacyChoice
                                )
                            }
                        }
                    }
                )
            }

            // Configurable Web Speed Test Service Website
            item {
                WebSpeedTestServiceCard(
                    selectedService = selectedService,
                    onChangeService = { showServiceDialog = true },
                    onLaunchService = { launchWebSpeedTest(selectedService) }
                )
            }
        }
    }

    if (showServiceDialog) {
        SpeedTestServiceSelectorDialog(
            currentService = selectedService,
            initialCustomName = SpeedTestServicePreferences.getSavedCustomName(context),
            initialCustomUrl = SpeedTestServicePreferences.getSavedCustomUrl(context),
            onDismiss = { showServiceDialog = false },
            onSelectService = { updated, launchImmediately ->
                selectedService = updated
                showServiceDialog = false
                Toast.makeText(
                    context,
                    "Speed test website set to ${updated.name}",
                    Toast.LENGTH_SHORT
                ).show()
                if (launchImmediately) {
                    launchWebSpeedTest(updated)
                }
            }
        )
    }
}

@Composable
private fun SpeedometerCard(uiState: SpeedTestUiState) {
    val (speedMbps, phaseText, activeColor) = when (uiState) {
        is SpeedTestUiState.Idle -> Triple(0.0, "Ready to test connection", MaterialTheme.colorScheme.primary)
        is SpeedTestUiState.Running -> {
            val label = when (uiState.phase) {
                SpeedTestEngine.TestPhase.LATENCY_JITTER -> "Pinging servers for latency..."
                SpeedTestEngine.TestPhase.DOWNLOAD -> "Measuring Download..."
                SpeedTestEngine.TestPhase.UPLOAD -> "Measuring Upload..."
                else -> "Testing..."
            }
            val color = when (uiState.phase) {
                SpeedTestEngine.TestPhase.DOWNLOAD -> StatusGreen
                SpeedTestEngine.TestPhase.UPLOAD -> StatusBlue
                else -> StellarTeal
            }
            Triple(uiState.currentMbps, label, color)
        }
        is SpeedTestUiState.Completed -> {
            val r = uiState.result
            val hasDownload = r.bytesDownloaded > 0L && r.downloadMbps >= 0.0
            val hasPartialFailure = !hasDownload || r.bytesUploaded <= 0L || r.latencyMs <= 0.0
            Triple(
                if (hasDownload) r.downloadMbps else 0.0,
                if (hasPartialFailure) "Test Completed (Partial Metrics)" else "Test Completed Successfully",
                if (hasPartialFailure) StatusAmber else StatusGreen
            )
        }
        is SpeedTestUiState.Error -> Triple(0.0, "Test Failed", StatusRed)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Radial Gauge Circle
            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background Track
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = Color(0xFF334155),
                        startAngle = 140f,
                        sweepAngle = 260f,
                        useCenter = false,
                        style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Dynamic Animated Progress Arc
                val maxGaugeMbps = 250f
                val sweepFraction = if (speedMbps > 0.0) {
                    (speedMbps.toFloat() / maxGaugeMbps).coerceIn(0.03f, 1f)
                } else {
                    0f
                }
                val animatedSweep by animateFloatAsState(
                    targetValue = sweepFraction * 260f,
                    label = "gaugeSweep"
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = activeColor,
                        startAngle = 140f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                val readoutText = when {
                    uiState is SpeedTestUiState.Completed && (uiState.result.bytesDownloaded <= 0L || uiState.result.downloadMbps < 0.0) -> "--"
                    uiState is SpeedTestUiState.Running && uiState.phase == SpeedTestEngine.TestPhase.LATENCY_JITTER -> "--"
                    else -> String.format(Locale.US, "%.1f", speedMbps)
                }

                // Center Numerical Readout
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = readoutText,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Mbps",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = activeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = activeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = phaseText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = activeColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Built-in Engine: Cloudflare Global Edge CDN (Single-Stream HTTP)",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedTestStatusCard(uiState: SpeedTestUiState) {
    if (uiState is SpeedTestUiState.Running) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = uiState.phase.name.replace("_", " "),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${(uiState.progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else if (uiState is SpeedTestUiState.Error) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusRed)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = uiState.message,
                    fontSize = 12.sp,
                    color = StatusRed
                )
            }
        }
    }
}

@Composable
internal fun MetricsOverviewGrid(uiState: SpeedTestUiState) {
    val isRunning = uiState is SpeedTestUiState.Running

    val downVal: String
    val upVal: String
    val latVal: String
    val jitVal: String
    val downPeak: String?
    val upPeak: String?
    val latUnavailableTag: String?
    val jitUnavailableTag: String?

    when (uiState) {
        is SpeedTestUiState.Running -> {
            downVal = if (uiState.downloadMbps >= 0.0) String.format(Locale.US, "%.1f", uiState.downloadMbps) else "--"
            upVal = if (uiState.uploadMbps >= 0.0) String.format(Locale.US, "%.1f", uiState.uploadMbps) else "--"
            latVal = if (uiState.latencyMs > 0.0) String.format(Locale.US, "%.0f", uiState.latencyMs) else "--"
            jitVal = if (uiState.jitterMs >= 0.0 && uiState.latencyMs > 0.0) String.format(Locale.US, "%.1f", uiState.jitterMs) else "--"
            downPeak = when {
                uiState.phase == SpeedTestEngine.TestPhase.DOWNLOAD && uiState.downloadMbps > 0 -> "Active Stream"
                uiState.phase.ordinal > SpeedTestEngine.TestPhase.DOWNLOAD.ordinal && uiState.downloadMbps < 0.0 -> "Unavailable"
                else -> null
            }
            upPeak = if (uiState.phase == SpeedTestEngine.TestPhase.UPLOAD && uiState.uploadMbps > 0) {
                "Active Stream"
            } else null
            latUnavailableTag = if (uiState.phase.ordinal > SpeedTestEngine.TestPhase.LATENCY_JITTER.ordinal && uiState.latencyMs <= 0.0) {
                "Unavailable"
            } else null
            jitUnavailableTag = if (uiState.phase.ordinal > SpeedTestEngine.TestPhase.LATENCY_JITTER.ordinal && uiState.jitterMs < 0.0) {
                "Unavailable"
            } else null
        }
        is SpeedTestUiState.Completed -> {
            val r = uiState.result
            val hasDown = r.bytesDownloaded > 0L && r.downloadMbps >= 0.0
            val hasUp = r.bytesUploaded > 0L && r.uploadMbps >= 0.0
            val hasLat = r.latencyMs > 0.0
            val hasJit = hasLat && r.jitterMs >= 0.0
            downVal = if (hasDown) String.format(Locale.US, "%.1f", r.downloadMbps) else "--"
            upVal = if (hasUp) String.format(Locale.US, "%.1f", r.uploadMbps) else "--"
            latVal = if (hasLat) String.format(Locale.US, "%.0f", r.latencyMs) else "--"
            jitVal = if (hasJit) String.format(Locale.US, "%.1f", r.jitterMs) else "--"
            downPeak = if (hasDown && r.peakDownloadMbps >= 0.0) {
                "Peak: ${String.format(Locale.US, "%.1f", r.peakDownloadMbps)} Mbps"
            } else {
                "Unavailable"
            }
            upPeak = if (hasUp && r.peakUploadMbps >= 0.0) {
                "Peak: ${String.format(Locale.US, "%.1f", r.peakUploadMbps)} Mbps"
            } else {
                "Unavailable"
            }
            latUnavailableTag = if (!hasLat) "Unavailable" else null
            jitUnavailableTag = if (!hasJit) "Unavailable" else null
        }
        else -> {
            downVal = "--"
            upVal = "--"
            latVal = "--"
            jitVal = "--"
            downPeak = null
            upPeak = null
            latUnavailableTag = null
            jitUnavailableTag = null
        }
    }

    val latNumber = latVal.toDoubleOrNull()?.takeIf { it > 0.0 }
    val latBadge = latUnavailableTag ?: when {
        latNumber == null -> null
        latNumber < 25 -> "Optimal"
        latNumber < 60 -> "Good"
        latNumber < 100 -> "Fair"
        else -> "Elevated"
    }

    val jitNumber = jitVal.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val jitBadge = jitUnavailableTag ?: when {
        jitNumber == null -> null
        jitNumber < 5 -> "Stable"
        jitNumber < 15 -> "Moderate"
        else -> "Variable"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Row 1: Throughput (Download & Upload)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EnhancedMetricCard(
                title = "DOWNLOAD",
                value = downVal,
                unit = "Mbps",
                tag = downPeak ?: if (isRunning) "Measuring" else "Ready",
                icon = Icons.Default.Download,
                accentColor = StatusGreen,
                modifier = Modifier.weight(1f)
            )
            EnhancedMetricCard(
                title = "UPLOAD",
                value = upVal,
                unit = "Mbps",
                tag = upPeak ?: if (isRunning) "Measuring" else "Ready",
                icon = Icons.Default.Upload,
                accentColor = StatusBlue,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Stability & Latency
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EnhancedMetricCard(
                title = "PING / LATENCY",
                value = latVal,
                unit = "ms",
                tag = latBadge ?: if (isRunning) "Measuring" else "Round-Trip",
                icon = Icons.Default.Timer,
                accentColor = StellarTeal,
                modifier = Modifier.weight(1f)
            )
            EnhancedMetricCard(
                title = "JITTER",
                value = jitVal,
                unit = "ms",
                tag = jitBadge ?: if (isRunning) "Measuring" else "RFC 3550",
                icon = Icons.Default.Speed,
                accentColor = StatusAmber,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EnhancedMetricCard(
    title: String,
    value: String,
    unit: String,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = tag,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeedTestConnectivityHeader(
    snapshot: ConnectivitySnapshot,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FIELD CONNECTIVITY MONITOR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = snapshot.transportName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Diagnostic Telemetry Rows:
            // 1. Internet Status
            val (internetColor, internetLabel) = when (snapshot.internetStatus) {
                InternetStatus.ONLINE -> Pair(StatusGreen, "Internet: Online (Validated)")
                InternetStatus.CAPTIVE_OR_LIMITED -> Pair(StatusAmber, "Internet: Captive / Unvalidated")
                InternetStatus.OFFLINE -> Pair(StatusRed, "Internet: Offline")
                InternetStatus.CHECKING -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "Internet: Checking...")
            }
            ConnectivityStatusRow(
                dotColor = internetColor,
                title = internetLabel,
                subtitle = snapshot.localIp?.let { ip ->
                    val ifaceText = snapshot.physicalInterfaceName ?: "Interface Unavailable"
                    "Local IP: $ip ($ifaceText)"
                } ?: "No local address"
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 2. VPN Detection
            val (vpnColor, vpnLabel) = if (snapshot.isVpnActive) {
                Pair(StatusBlue, "VPN: Active (${snapshot.vpnInterfaceName ?: "Virtual Tunnel"})")
            } else {
                Pair(StatusGreen, "VPN: Inactive (Unencapsulated Route)")
            }
            ConnectivityStatusRow(
                dotColor = vpnColor,
                title = vpnLabel,
                subtitle = if (snapshot.isVpnActive) "Traffic routed through virtual VPN tunnel" else "No active VPN tunnel detected"
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 3. Gateway Responsiveness
            val (gwColor, gwLabel) = when (snapshot.gatewayStatus) {
                GatewayStatus.RESPONSIVE -> {
                    val rtt = snapshot.gatewayResponsivenessMs ?: 1L
                    Pair(StatusGreen, "Gateway: ${snapshot.gatewayIp ?: "Default"} ($rtt ms • Responsive)")
                }
                GatewayStatus.SUPPRESSED_BY_VPN -> Pair(StatusBlue, "Gateway: Suppressed by VPN (0.0.0.0/0)")
                GatewayStatus.UNRESPONSIVE -> Pair(StatusRed, "Gateway: Unresponsive (Packet Loss)")
                GatewayStatus.NO_GATEWAY -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "Gateway: None Assigned")
                GatewayStatus.CHECKING -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "Gateway: Probing...")
            }
            ConnectivityStatusRow(
                dotColor = gwColor,
                title = gwLabel,
                subtitle = "Subnet router responsiveness telemetry"
            )
        }
    }
}

@Composable
private fun ConnectivityStatusRow(
    dotColor: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectionAssessmentCard(result: SpeedTestEngine.SpeedTestResult) {
    val hasDownload = result.bytesDownloaded > 0L && result.downloadMbps >= 0.0
    val hasUpload = result.bytesUploaded > 0L && result.uploadMbps >= 0.0
    val hasLatency = result.latencyMs > 0.0
    val hasJitter = hasLatency && result.jitterMs >= 0.0
    val down = result.downloadMbps
    val lat = result.latencyMs
    val jit = result.jitterMs

    val (grade, gradeDesc, gradeColor) = when {
        !hasDownload -> Triple("Partial", "Download Measurement Unavailable • Partial Result", StatusAmber)
        hasLatency && down >= 100 && lat <= 30 -> Triple("Grade A+", "Gigabit / Ultra-Broadband Performance", StatusGreen)
        hasLatency && down >= 50 && lat <= 50 -> Triple("Grade A", "High-Speed Broadband • Enterprise Grade", StatusGreen)
        hasLatency && down >= 25 && lat <= 80 -> Triple("Grade B", "Standard Broadband • Reliable Field Connectivity", StatusBlue)
        down >= 10 -> Triple("Grade C", "Basic Broadband • Suitable for Essential Work", StatusAmber)
        else -> Triple("Grade D", "Constrained Connection • Low Throughput / High Latency", StatusRed)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = gradeColor.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = gradeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FIELD PERFORMANCE ASSESSMENT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = gradeColor
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = gradeColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = grade,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = gradeDesc,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(
                color = gradeColor.copy(alpha = 0.2f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            // Service Checklist
            ServiceCapabilityRow(
                service = "4K / HD Video Streaming",
                status = when {
                    !hasDownload -> "Unavailable"
                    down >= 25 -> "Supported"
                    else -> "Constrained"
                },
                isPassed = hasDownload && down >= 25
            )
            ServiceCapabilityRow(
                service = "VoIP Calls & Video Conferencing",
                status = when {
                    !hasLatency -> "Unavailable"
                    lat <= 60 && (!hasJitter || jit <= 15) -> "Optimal & Clear"
                    else -> "Moderate Latency / Jitter"
                },
                isPassed = hasLatency && lat <= 60 && (!hasJitter || jit <= 15)
            )
            ServiceCapabilityRow(
                service = "Field Telemetry & Cloud Backups",
                status = when {
                    !hasUpload -> "Unavailable"
                    result.uploadMbps >= 10 -> "Fast Throughput"
                    else -> "Standard"
                },
                isPassed = hasUpload && result.uploadMbps >= 10
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Endpoint: ${result.serverName} (Latency: ${result.latencyEndpointUsed}) • Data: ${formatTransferredBytes(result.bytesDownloaded)} Down / ${formatTransferredBytes(result.bytesUploaded)} Up",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Scope (${result.methodologyNote}): Measures single-stream HTTP transfer rate to the built-in Cloudflare Edge endpoint; may differ from multi-stream web portals (e.g., Speedtest.net) and local Wi-Fi PHY rate.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun ServiceCapabilityRow(
    service: String,
    status: String,
    isPassed: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = service,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = status,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPassed) StatusGreen else StatusAmber
        )
    }
}

@Composable
private fun ControlsSection(
    uiState: SpeedTestUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSaveTxt: () -> Unit,
    onShare: () -> Unit
) {
    val isRunning = uiState is SpeedTestUiState.Running

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isRunning) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("speed_test_stop_button")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Speed Test", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        } else {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("speed_test_start_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState is SpeedTestUiState.Completed) "Run Test Again" else "Start Speed Test",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        if (uiState is SpeedTestUiState.Completed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveTxt,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("speed_test_save_txt_button")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save RTF", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("speed_test_share_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Results", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun WebSpeedTestServiceCard(
    selectedService: SpeedTestServiceOption,
    onChangeService: () -> Unit,
    onLaunchService: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "External Web Speed Test Portal",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (selectedService.isCustom) "CUSTOM" else "WEBVIEW",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Runs inside the in-app WebView separately from the built-in Cloudflare HTTP engine above. Multi-stream browser portals like Speedtest.net and Fast.com require an interactive browser session.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = selectedService.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = selectedService.url,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = selectedService.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onChangeService,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("speed_test_change_service_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change Website", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onLaunchService,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("speed_test_open_website_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Website", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SpeedTestServiceSelectorDialog(
    currentService: SpeedTestServiceOption,
    initialCustomName: String,
    initialCustomUrl: String,
    onDismiss: () -> Unit,
    onSelectService: (service: SpeedTestServiceOption, launchImmediately: Boolean) -> Unit
) {
    val context = LocalContext.current
    val presets = remember { SpeedTestServicePreferences.getPresetServices() }
    var selectedId by remember { mutableStateOf(currentService.id) }
    var customName by remember {
        mutableStateOf(
            if (currentService.isCustom) currentService.name else initialCustomName
        )
    }
    var customUrl by remember {
        mutableStateOf(
            if (currentService.isCustom) currentService.url else initialCustomUrl
        )
    }
    var urlError by remember { mutableStateOf<String?>(null) }

    fun resolveAndPersist(): SpeedTestServiceOption? {
        return if (selectedId == SpeedTestServicePreferences.CUSTOM_SERVICE_ID) {
            val saved = SpeedTestServicePreferences.saveCustomService(context, customName, customUrl)
            if (saved == null) {
                urlError = "Enter a valid HTTPS URL (e.g., https://speed.cloudflare.com) or local LAN http:// address"
                null
            } else {
                saved
            }
        } else {
            SpeedTestServicePreferences.saveSelectedPreset(context, selectedId)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Choose Speed Test Service",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select which speed test website opens when launching a web speed test:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                presets.forEach { preset ->
                    val isSelected = selectedId == preset.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedId = preset.id
                                urlError = null
                            }
                            .testTag("speed_service_option_${preset.id}"),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedId = preset.id
                                    urlError = null
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = preset.url,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = preset.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Custom Website Option
                val isCustomSelected = selectedId == SpeedTestServicePreferences.CUSTOM_SERVICE_ID
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedId = SpeedTestServicePreferences.CUSTOM_SERVICE_ID }
                        .testTag("speed_service_option_custom"),
                    color = if (isCustomSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isCustomSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        }
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = isCustomSelected,
                                onClick = { selectedId = SpeedTestServicePreferences.CUSTOM_SERVICE_ID }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom Website URL",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Use any ISP, enterprise, or self-hosted test page",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isCustomSelected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("Service Name (Optional)") },
                                placeholder = { Text("e.g., ISP Speed Portal") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("speed_service_custom_name_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customUrl,
                                onValueChange = {
                                    customUrl = it
                                    urlError = null
                                },
                                label = { Text("Website URL") },
                                placeholder = { Text("https://speed.example.com") },
                                singleLine = true,
                                isError = urlError != null,
                                supportingText = urlError?.let { err -> { Text(err) } },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("speed_service_custom_url_input")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        resolveAndPersist()?.let { onSelectService(it, false) }
                    },
                    modifier = Modifier.testTag("speed_service_save_button")
                ) {
                    Text("Save")
                }
                Button(
                    onClick = {
                        resolveAndPersist()?.let { onSelectService(it, true) }
                    },
                    modifier = Modifier.testTag("speed_service_save_and_open_button")
                ) {
                    Text("Save & Open")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("speed_service_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private fun formatTransferredBytes(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 B"
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

internal fun buildSpeedTestReport(r: SpeedTestEngine.SpeedTestResult): String {
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — SPEED TEST REPORT")
        appendLine("==========================================")
        val downloadStr = if (r.bytesDownloaded > 0L && r.downloadMbps >= 0.0) {
            val peakStr = if (r.peakDownloadMbps >= 0.0) {
                " (Peak: ${String.format(Locale.US, "%.2f", r.peakDownloadMbps)} Mbps)"
            } else ""
            "${String.format(Locale.US, "%.2f", r.downloadMbps)} Mbps$peakStr"
        } else {
            "Unavailable (Download test blocked or no bytes transferred)"
        }
        appendLine("Download Speed: $downloadStr")
        val uploadStr = if (r.bytesUploaded > 0L && r.uploadMbps >= 0.0) {
            val peakStr = if (r.peakUploadMbps >= 0.0) {
                " (Peak: ${String.format(Locale.US, "%.2f", r.peakUploadMbps)} Mbps)"
            } else ""
            "${String.format(Locale.US, "%.2f", r.uploadMbps)} Mbps$peakStr"
        } else {
            "Unavailable (Upload test blocked or no bytes transferred)"
        }
        appendLine("Upload Speed:   $uploadStr")
        appendLine("Latency / Ping: ${if (r.latencyMs > 0.0) "${String.format(Locale.US, "%.1f", r.latencyMs)} ms" else "Unavailable"}")
        appendLine("Jitter:         ${if (r.latencyMs > 0.0 && r.jitterMs >= 0.0) "${String.format(Locale.US, "%.2f", r.jitterMs)} ms" else "Unavailable"}")
        appendLine("Test Server:    ${r.serverName}")
        appendLine("Latency Probe:  ${r.latencyEndpointUsed}")
        appendLine("Methodology:    ${r.methodologyNote}")
        appendLine("Data Downloaded: ${formatTransferredBytes(r.bytesDownloaded)}")
        appendLine("Data Uploaded:   ${formatTransferredBytes(r.bytesUploaded)}")
        appendLine("Scope Note:     Measures single-stream HTTP transfer rate to Cloudflare Edge CDN; may differ from multi-stream web portals (e.g., Speedtest.net) and local Wi-Fi PHY link speed.")
        appendLine("==========================================")
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = label,
        text = text,
        markSensitive = true
    )
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}
