package com.example.networktoolkit.iperf3

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IPerf3Screen(
    viewModel: IPerf3ViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val targetHost by viewModel.targetHost.collectAsStateWithLifecycle()
    val portInput by viewModel.portInput.collectAsStateWithLifecycle()
    val durationSeconds by viewModel.durationSeconds.collectAsStateWithLifecycle()
    val connectTimeoutMs by viewModel.connectTimeoutMs.collectAsStateWithLifecycle()
    val parallelStreams by viewModel.parallelStreams.collectAsStateWithLifecycle()
    val reverseMode by viewModel.reverseMode.collectAsStateWithLifecycle()
    val serverNoticeMessage by viewModel.serverNoticeMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportController = rememberSafTextExportController()

    BackHandler(enabled = uiState is IPerfUiState.Running && mode == IPerfMode.SERVER) {
        viewModel.stopServerIfRunning()
        onBackClick()
    }

    val saveIperfTxt: (IPerfSummary) -> Unit = { s ->
        val rawReport = buildIPerfReport(s)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("iPerf3 RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = TextReportExportManager.suggestFilename("iPerf3_Benchmark_Report", "rtf"),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareIperfTxt: (IPerfSummary) -> Unit = { s ->
        val rawReport = buildIPerfReport(s)
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        exportController.promptPrivacyAndRun("iPerf3 RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = TextReportExportManager.suggestFilename("iPerf3_Benchmark_Report", "rtf"),
                subject = "iPerf3 Benchmark - ${s.targetOrClient}",
                chooserTitle = "Share iPerf3 Report",
                identifiers = identifiers,
                isJson = false,
                privacyChoice = privacyChoice
            )
        }
    }

    val isRunning = uiState is IPerfUiState.Running
    val gatewayIp = viewModel.activeGatewayIp

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "iPerf3 Benchmark",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("iperf3_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState is IPerfUiState.Completed) {
                        val summary = (uiState as IPerfUiState.Completed).summary
                        IconButton(
                            onClick = { saveIperfTxt(summary) },
                            modifier = Modifier.testTag("save_iperf_txt_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Save RTF Report")
                        }
                        IconButton(
                            onClick = {
                                val report = buildIPerfReport(summary)
                                copyIPerfReportToClipboard(context, "iPerf3 Report", report)
                            },
                            modifier = Modifier.testTag("copy_iperf_report_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Report")
                        }
                    }
                    ToolContextualHelpAction(
                        toolId = if (mode == IPerfMode.SERVER) "iperf3_server" else "iperf3_client",
                        testTag = "iperf3_help_button"
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Mode Selector Segmented Tabs
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    val clientSelected = mode == IPerfMode.CLIENT
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (clientSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isRunning) { viewModel.setMode(IPerfMode.CLIENT) }
                            .testTag("iperf_mode_client")
                    ) {
                        Text(
                            text = "CLIENT (ACTIVE)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (clientSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    val serverSelected = mode == IPerfMode.SERVER
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (serverSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isRunning) { viewModel.setMode(IPerfMode.SERVER) }
                            .testTag("iperf_mode_server")
                    ) {
                        Text(
                            text = "SERVER (LISTEN)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (serverSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Client-Server Explanatory Banner
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusBlue.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, StatusBlue.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = StatusBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (mode == IPerfMode.CLIENT) {
                                "iPerf3 requires a matching iPerf3 server ('iperf3 -s') listening on the target device. Standard websites (google.com, Cloudflare, speed-test sites) do not accept iPerf3 connections."
                            } else {
                                "Listens for incoming iPerf3 TCP client connections on this device. Reachable only on your local network (Wi-Fi/LAN) unless port forwarding or VPN routing is configured."
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Configuration Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (mode == IPerfMode.CLIENT) "Client Test Configuration" else "Server Listen Configuration",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (mode == IPerfMode.CLIENT) {
                            OutlinedTextField(
                                value = targetHost,
                                onValueChange = { viewModel.onTargetHostChanged(it) },
                                label = { Text("Target Server Host / IP") },
                                placeholder = { Text("Enter iPerf3 server IP or hostname") },
                                singleLine = true,
                                enabled = !isRunning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("iperf_host_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = portInput,
                                    onValueChange = { viewModel.onPortInputChanged(it) },
                                    label = { Text("Port (1–65535)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    enabled = !isRunning,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("iperf_port_input")
                                )

                                // Interactive Parallel Streams Selector
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Parallel Streams",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.onParallelStreamsChanged(parallelStreams - 1) },
                                                enabled = !isRunning && parallelStreams > 1,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Decrease streams", modifier = Modifier.size(16.dp))
                                            }
                                            Text(
                                                text = "$parallelStreams",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            IconButton(
                                                onClick = { viewModel.onParallelStreamsChanged(parallelStreams + 1) },
                                                enabled = !isRunning && parallelStreams < 8,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Increase streams", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Direction Toggle (Upload vs Download -R)
                            Text("Transfer Direction", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(false to "Upload (Client → Server)", true to "Download (-R Server → Client)").forEach { (isRev, label) ->
                                    val selected = reverseMode == isRev
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = !isRunning) { viewModel.onReverseModeChanged(isRev) }
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Test Duration Slider (clearly separate from Connection Timeout)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Benchmark Duration", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${durationSeconds}s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = durationSeconds.toFloat(),
                                onValueChange = { viewModel.onDurationChanged(it.toInt()) },
                                valueRange = 3f..60f,
                                steps = 56,
                                enabled = !isRunning,
                                modifier = Modifier.testTag("iperf_duration_slider")
                            )

                            // Connection Timeout Selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Connection Timeout", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${String.format(Locale.US, "%,d", connectTimeoutMs)} ms",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StellarTeal
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(2000, 4000, 5000, 10000, 15000).forEach { timeoutOpt ->
                                    val selected = connectTimeoutMs == timeoutOpt
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) StellarTeal.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) StellarTeal else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                        ),
                                        modifier = Modifier.clickable(enabled = !isRunning) {
                                            viewModel.onConnectTimeoutChanged(timeoutOpt)
                                        }
                                    ) {
                                        Text(
                                            text = "${timeoutOpt / 1000}s",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) StellarTeal else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Section 1.2: Separated Local Network Server Examples vs Public iPerf3 Servers
                            Text(
                                text = "Local Network Server Examples",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Populates host field without starting. Target device must run 'iperf3 -s'.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!gatewayIp.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = StellarTeal.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, StellarTeal.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .clickable(enabled = !isRunning) {
                                                viewModel.applyPresetHost(gatewayIp, 5201)
                                            }
                                            .testTag("iperf_preset_gateway")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Router, contentDescription = null, tint = StellarTeal, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Gateway: $gatewayIp",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = StellarTeal
                                            )
                                        }
                                    }
                                }
                                IPerf3Presets.localExamples.forEach { preset ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                        modifier = Modifier.clickable(enabled = !isRunning) {
                                            viewModel.applyPresetHost(preset.host, preset.port)
                                        }
                                    ) {
                                        Text(
                                            text = "${preset.label} (LAN Example)",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Public iPerf3 Servers (External — No Uptime Guarantee)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusAmber
                            )
                            Text(
                                text = "Third-party endpoints (port 5201). May queue or reject concurrent clients.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IPerf3Presets.publicServers.forEach { preset ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = StatusAmber.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f)),
                                        modifier = Modifier.clickable(enabled = !isRunning) {
                                            viewModel.applyPresetHost(preset.host, preset.port)
                                        }
                                    ) {
                                        Text(
                                            text = "${preset.label}:${preset.port}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Server Mode
                            val localIp = viewModel.localIpAddress
                            val displayIp = localIp ?: "No active LAN / Wi-Fi IP"
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = StatusBlue.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Local Server IP Address:", fontSize = 11.sp, color = StatusBlue, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = displayIp,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val cmd = if (localIp != null) {
                                        "iperf3 -c $localIp -p ${portInput.ifEmpty { "5201" }}"
                                    } else {
                                        "Connect Wi-Fi first, then run: iperf3 -c <device-ip> -p ${portInput.ifEmpty { "5201" }}"
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Run from a client on the same LAN:",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                cmd,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (localIp != null) {
                                            IconButton(
                                                onClick = { copyIPerfToClipboard(context, "iPerf3 Command", cmd) }
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Client Command", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { viewModel.onPortInputChanged(it) },
                                label = { Text("Listening Port (1024–65535)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !isRunning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("iperf_server_port_input")
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = StatusAmber.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = StatusAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Server Security Notice: While active, local LAN devices can connect to port ${portInput.ifEmpty { "5201" }}. The listener automatically stops after 2 minutes of inactivity or when you leave this screen.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            if (!serverNoticeMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = StatusBlue.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, StatusBlue.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("iperf_server_notice_banner")
                                ) {
                                    Text(
                                        text = serverNoticeMessage.orEmpty(),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Start / Stop Button
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.stopTest() },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("iperf_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (mode == IPerfMode.CLIENT) "Stop Benchmark" else "Stop Listening")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startTest() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("iperf_start_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (mode == IPerfMode.CLIENT) "Start iPerf3 Benchmark" else "Start iPerf3 Server")
                            }
                        }
                    }
                }
            }

            // Live State / Running Telemetry / Completion / Structured Error Diagnostics
            when (val state = uiState) {
                is IPerfUiState.Running -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = state.statusMessage,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f", state.liveBandwidthMbps),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Mbps",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                }

                                if (state.currentIntervals.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    IntervalsTable(intervals = state.currentIntervals.takeLast(5))
                                }
                            }
                        }
                    }
                }

                is IPerfUiState.Completed -> {
                    val s = state.summary
                    val statusTint = if (s.isInterrupted || s.isStalled) StatusAmber else StatusGreen
                    val statusTitle = when {
                        s.isInterrupted -> "BENCHMARK INTERRUPTED"
                        s.isStalled -> "TRANSFER STALLED"
                        else -> "BENCHMARK COMPLETED"
                    }
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("iperf_completed_card"),
                            colors = CardDefaults.cardColors(containerColor = statusTint.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, statusTint.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (s.isInterrupted || s.isStalled) Icons.Default.Warning else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = statusTint,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = statusTitle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = statusTint
                                        )
                                    }
                                    Surface(shape = RoundedCornerShape(6.dp), color = statusTint.copy(alpha = 0.2f)) {
                                        Text(
                                            text = "${s.protocol} • ${s.mode}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = statusTint,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = String.format(Locale.US, "%.1f", if (s.isStalled) 0.0 else s.avgBandwidthMbps),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (s.isStalled) "Mbps (Stalled)" else "Mbps Average",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = statusTint,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                }

                                HorizontalDivider(color = statusTint.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 10.dp))

                                if (!s.diagnosticNote.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = StatusAmber.copy(alpha = 0.14f),
                                        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp)
                                    ) {
                                        Text(
                                            text = s.diagnosticNote,
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                BenchmarkMetricRow("Status", when {
                                    s.isInterrupted -> "Interrupted"
                                    s.isStalled -> "Completed with Upload Stall"
                                    else -> "Completed"
                                })
                                BenchmarkMetricRow("Termination Reason", s.terminationReason.displayTitle)
                                BenchmarkMetricRow("Target / Peer", s.targetOrClient)
                                if (!s.resolvedIp.isNullOrBlank()) {
                                    BenchmarkMetricRow("Resolved IP", s.resolvedIp)
                                }
                                BenchmarkMetricRow("Direction", s.direction)
                                BenchmarkMetricRow("Parallel Streams", "${s.parallelStreams}")
                                BenchmarkMetricRow("Total Transferred", String.format(Locale.US, "%.2f MB", s.totalBytes / (1024.0 * 1024.0)))
                                BenchmarkMetricRow(
                                    "Peak Bandwidth",
                                    if (s.isStalled) "Unavailable (Stalled)" else String.format(Locale.US, "%.1f Mbps", s.peakBandwidthMbps)
                                )
                                BenchmarkMetricRow("Benchmark Duration", "${s.durationSeconds} seconds")
                                if (s.connectTimeoutMs > 0) {
                                    BenchmarkMetricRow("Connection Timeout", "${String.format(Locale.US, "%,d", s.connectTimeoutMs)} ms")
                                }

                                if (s.intervals.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Interval Telemetry:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    IntervalsTable(intervals = s.intervals)
                                }
                            }
                        }
                    }

                    // Save RTF, Share & Copy Buttons
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { saveIperfTxt(s) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("iperf3_save_txt_bottom_button")
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
                                    onClick = { shareIperfTxt(s) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val report = buildIPerfReport(s)
                                        copyIPerfReportToClipboard(context, "iPerf3 Report", report)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Report")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                is IPerfUiState.Error -> {
                    val err = state.details
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("iperf_error_card"),
                            colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
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
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = err.summaryMessage,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                HorizontalDivider(color = StatusRed.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 10.dp))

                                BenchmarkMetricRow("Termination Reason", err.terminationReason.displayTitle)
                                BenchmarkMetricRow("Target Host", err.targetHost)
                                BenchmarkMetricRow("Resolved IP", err.resolvedIp ?: "Unresolved")
                                BenchmarkMetricRow("Port", err.port?.toString() ?: "Invalid / Unspecified")
                                if (err.connectTimeoutMs > 0) {
                                    BenchmarkMetricRow(
                                        "Connection Timeout",
                                        "${String.format(Locale.US, "%,d", err.connectTimeoutMs)} ms"
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = err.technicalDetails,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }

                                if (err.troubleshootingSteps.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Troubleshooting:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    err.troubleshootingSteps.forEach { step ->
                                        Text(
                                            text = "• $step",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                IPerfUiState.Idle -> {}
            }
        }
    }
}

@Composable
private fun IntervalsTable(intervals: List<IPerfInterval>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Interval", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Transfer", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Bandwidth", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
            intervals.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(item.timeRange, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        String.format(Locale.US, "%.2f MB", item.bytesTransferred / (1024.0 * 1024.0)),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        String.format(Locale.US, "%.1f Mbps", item.bandwidthMbps),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = StatusGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun buildIPerfReport(s: IPerfSummary): String {
    return buildString {
        appendLine("==========================================")
        appendLine("NETWORK TECHNICIAN TOOLKIT — iPerf3 REPORT")
        appendLine("==========================================")
        appendLine("Benchmark Status:   ${when {
            s.isInterrupted -> "Interrupted"
            s.isStalled -> "Completed with Upload Stall"
            else -> "Completed"
        }}")
        appendLine("Termination Reason: ${s.terminationReason.displayTitle}")
        appendLine("Benchmark Mode:     ${s.mode} (${s.protocol})")
        appendLine("Direction:          ${s.direction}")
        appendLine("Target / Peer:      ${s.targetOrClient}")
        if (!s.resolvedIp.isNullOrBlank()) {
            appendLine("Resolved IP:        ${s.resolvedIp}")
        }
        appendLine("Port:               ${s.port}")
        appendLine("Parallel Streams:   ${s.parallelStreams}")
        appendLine("Benchmark Duration: ${s.durationSeconds} seconds")
        if (s.connectTimeoutMs > 0) {
            appendLine("Connection Timeout: ${String.format(Locale.US, "%,d ms", s.connectTimeoutMs)}")
        }
        appendLine("Data Transferred:   ${String.format(Locale.US, "%.2f MB", s.totalBytes / (1024.0 * 1024.0))}")
        appendLine("Average Bandwidth:  ${if (s.isStalled) "0.00 Mbps (Stalled)" else String.format(Locale.US, "%.2f Mbps", s.avgBandwidthMbps)}")
        appendLine("Peak Bandwidth:     ${if (s.isStalled) "Unavailable (Stalled)" else String.format(Locale.US, "%.2f Mbps", s.peakBandwidthMbps)}")
        if (!s.diagnosticNote.isNullOrBlank()) {
            appendLine("Diagnostic Note:    ${s.diagnosticNote}")
        }
        appendLine("------------------------------------------")
        appendLine("Interval Details:")
        s.intervals.forEach { i ->
            appendLine(
                String.format(
                    Locale.US,
                    "  [%s] %.2f MB  %.1f Mbps",
                    i.timeRange,
                    i.bytesTransferred / (1024.0 * 1024.0),
                    i.bandwidthMbps
                )
            )
        }
        appendLine("==========================================")
    }
}

private fun copyIPerfToClipboard(context: Context, label: String, text: String) {
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = label,
        text = text,
        markSensitive = true
    )
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun copyIPerfReportToClipboard(context: Context, label: String, rawReport: String) {
    TextReportExportManager.copyRedactedToClipboard(
        context = context,
        label = label,
        rawContent = rawReport,
        markSensitive = true
    ).onSuccess {
        Toast.makeText(context, "Redacted $label copied to clipboard", Toast.LENGTH_SHORT).show()
    }.onFailure { err ->
        Toast.makeText(context, err.message ?: "Unable to copy report safely", Toast.LENGTH_LONG).show()
    }
}
