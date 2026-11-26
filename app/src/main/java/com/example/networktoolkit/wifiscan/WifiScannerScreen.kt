package com.example.networktoolkit.wifiscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiChannel
import androidx.compose.material.icons.filled.WifiOff
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.example.networktoolkit.SignalCeilingFloorGauge
import com.example.networktoolkit.SignalStrengthTier
import com.example.networktoolkit.WifiPrivacyPreferences
import com.example.networktoolkit.WifiSignalFluctuationGraph
import com.example.networktoolkit.rememberWifiPrivacySettings
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSlate
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScannerScreen(
    viewModel: WifiScannerViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.rawState.collectAsStateWithLifecycle()
    val networks by viewModel.filteredNetworks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedBand by viewModel.selectedBandFilter.collectAsStateWithLifecycle()
    val selectedSecurity by viewModel.selectedSecurityFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.selectedSortOrder.collectAsStateWithLifecycle()
    val isAutoScan by viewModel.isAutoScanEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showCongestionSheet by remember { mutableStateOf(false) }
    var showSignalGraph by remember { mutableStateOf(false) }

    // Launcher for requesting Location & Nearby Wi-Fi permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshHardwareStatus()
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.startScan()
        } else {
            Toast.makeText(context, "Location permission is required for Wi-Fi scanning", Toast.LENGTH_LONG).show()
        }
    }

    val exportController = rememberSafTextExportController()
    val wifiPrivacy = rememberWifiPrivacySettings()

    LaunchedEffect(Unit) {
        viewModel.refreshHardwareStatus()
    }

    if (showReportDialog) {
        WifiScanReportDialog(
            networkCount = state.networks.size,
            onDismiss = { showReportDialog = false },
            onSaveTxt = {
                showReportDialog = false
                exportController.promptPrivacyAndRun("Wi-Fi Scan RTF export") { privacyChoice ->
                    exportController.launchSaveTxt(
                        suggestedFilename = viewModel.getDefaultTxtFilename(),
                        rawContent = viewModel.generateTextReport(),
                        identifiers = viewModel.collectSensitiveIdentifiers(context),
                        privacyChoice = privacyChoice
                    )
                }
            },
            onShareText = {
                showReportDialog = false
                exportController.promptPrivacyAndRun("Wi-Fi Scan RTF share") { privacyChoice ->
                    viewModel.createShareIntent(context, isJson = false, privacyChoice = privacyChoice)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (t: Throwable) {
                                exportController.reportFailure(t, "Launching Android share chooser")
                            }
                        }
                        .onFailure { err ->
                            exportController.reportFailure(err, "Preparing Wi-Fi scan RTF report")
                        }
                }
            },
            onShareJson = {
                showReportDialog = false
                exportController.promptPrivacyAndRun("Wi-Fi Scan JSON share") { privacyChoice ->
                    viewModel.createShareIntent(context, isJson = true, privacyChoice = privacyChoice)
                        .onSuccess { intent ->
                            try {
                                context.startActivity(intent)
                            } catch (t: Throwable) {
                                exportController.reportFailure(t, "Launching Android share chooser")
                            }
                        }
                        .onFailure { err ->
                            exportController.reportFailure(err, "Preparing Wi-Fi scan JSON report")
                        }
                }
            },
            onCopyText = {
                showReportDialog = false
                viewModel.copyReportToClipboard(context, isJson = false)
            },
            onCopyJson = {
                showReportDialog = false
                viewModel.copyReportToClipboard(context, isJson = true)
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Wi-Fi Scanner",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = if (state.isScanning) "Scanning 802.11 APs..." else "${state.networks.size} networks found",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("wifi_scanner_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!state.isLocationPermissionGranted) {
                                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                }
                                permissionLauncher.launch(perms.toTypedArray())
                            } else {
                                viewModel.startScan()
                            }
                        },
                        modifier = Modifier.testTag("wifi_scanner_scan_button")
                    ) {
                        ScanningRefreshIcon(
                            isScanning = state.isScanning,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    ToolContextualHelpAction(toolId = "wifi_scanner")

                    // Sort menu button
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.testTag("wifi_scanner_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Networks"
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            WifiSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = order.label,
                                            fontWeight = if (sortOrder == order) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sortOrder == order) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        viewModel.onSortOrderSelected(order)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Overflow Menu
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("wifi_scanner_more_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options"
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save RTF Report (Choose Folder)...") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    exportController.promptPrivacyAndRun("Wi-Fi Scan RTF export") { privacyChoice ->
                                        exportController.launchSaveTxt(
                                            suggestedFilename = viewModel.getDefaultTxtFilename(),
                                            rawContent = viewModel.generateTextReport(),
                                            identifiers = viewModel.collectSensitiveIdentifiers(context),
                                            privacyChoice = privacyChoice
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share / Export Scan Report") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    showReportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isAutoScan) "Stop Auto-Scan" else "Start Auto-Scan (8s)") },
                                leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null, tint = if (isAutoScan) StatusAmber else StatusGreen) },
                                onClick = {
                                    showMenu = false
                                    viewModel.toggleAutoScan()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showCongestionSheet) "Hide Channel Graph" else "View Channel Graph") },
                                leadingIcon = { Icon(Icons.Default.WifiChannel, contentDescription = null, tint = StatusBlue) },
                                onClick = {
                                    showMenu = false
                                    showCongestionSheet = !showCongestionSheet
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showSignalGraph) "Hide Live Signal Graph" else "Live Signal Fluctuation Graph") },
                                leadingIcon = { Icon(Icons.Default.ShowChart, contentDescription = null, tint = StellarTeal) },
                                onClick = {
                                    showMenu = false
                                    showSignalGraph = !showSignalGraph
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Open Wi-Fi Settings") },
                                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot open Wi-Fi settings", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Open Location Settings") },
                                leadingIcon = { Icon(Icons.Default.LocationOff, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot open Location settings", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
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
            // Hardware Warning Banners (if Wi-Fi is off or permission missing)
            if (!state.isWifiEnabled) {
                HardwareBanner(
                    title = "Wi-Fi is Disabled",
                    message = "Enable Wi-Fi on device to discover nearby wireless access points.",
                    actionText = "Turn on Wi-Fi",
                    onAction = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Open Settings to turn on Wi-Fi", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else if (!state.isLocationPermissionGranted) {
                HardwareBanner(
                    title = "Location Permission Required",
                    message = "Android requires Location permission to retrieve SSID and BSSID scan results.",
                    actionText = "Grant Permission",
                    onAction = {
                        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                )
            } else if (!state.isLocationEnabled) {
                HardwareBanner(
                    title = "Location Services Disabled",
                    message = "Android GPS/Location Services must be turned on to scan Wi-Fi networks.",
                    actionText = "Enable Location",
                    onAction = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Open Settings to enable Location", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("wifi_scanner_search_input"),
                placeholder = {
                    val hint = when {
                        wifiPrivacy.showSsid && wifiPrivacy.showBssid -> "Filter by SSID, BSSID (MAC), or Channel..."
                        wifiPrivacy.showSsid -> "Filter by SSID or Channel..."
                        wifiPrivacy.showBssid -> "Filter by BSSID (MAC) or Channel..."
                        else -> "Filter by Channel..."
                    }
                    Text(hint)
                },
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

            // Band and Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val bands = listOf("ALL", "2.4 GHz", "5 GHz", "6 GHz", "Hidden")
                bands.forEach { band ->
                    val isSelected = selectedBand == band
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onBandFilterSelected(band) },
                        label = {
                            Text(
                                text = when (band) {
                                    "ALL" -> "All Bands (${state.networks.size})"
                                    "2.4 GHz" -> "2.4 GHz (${state.networks.count { it.band == WifiBand.BAND_2_4_GHZ }})"
                                    "5 GHz" -> "5 GHz (${state.networks.count { it.band == WifiBand.BAND_5_GHZ }})"
                                    "6 GHz" -> "6 GHz (${state.networks.count { it.band == WifiBand.BAND_6_GHZ }})"
                                    "Hidden" -> "Hidden (${state.networks.count { it.isHidden }})"
                                    else -> band
                                },
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                val secOptions = listOf("Open", "Secure")
                secOptions.forEach { sec ->
                    val isSelected = selectedSecurity == sec
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            viewModel.onSecurityFilterSelected(if (isSelected) "ALL" else sec)
                        },
                        label = { Text(sec, fontSize = 12.sp) }
                    )
                }
            }

            // Channel Congestion Breakdown Bar (Toggleable)
            AnimatedVisibility(visible = showCongestionSheet && state.networks.isNotEmpty()) {
                ChannelDistributionCard(
                    networks = state.networks,
                    viewModel = viewModel,
                    onClose = { showCongestionSheet = false }
                )
            }

            // Real-Time Wi-Fi Signal Fluctuation Graph (Toggleable)
            AnimatedVisibility(visible = showSignalGraph) {
                WifiSignalFluctuationGraph(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Quick Telemetry Ribbon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Showing ${networks.size} of ${state.networks.size} APs",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isAutoScan) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = StatusGreen.copy(alpha = 0.15f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = "AUTO-SCAN ON",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "Sort: ${sortOrder.label}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showSortMenu = true }
                    )
                }
            }

            // Network Cards List
            if (networks.isEmpty()) {
                EmptyWifiScanView(
                    hasQuery = searchQuery.isNotEmpty(),
                    isScanning = state.isScanning,
                    onClearSearch = { viewModel.onSearchQueryChanged("") },
                    onScanNow = { viewModel.startScan() }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(networks, key = { it.bssid + "_" + it.frequencyMhz }) { net ->
                        WifiNetworkCard(
                            network = net,
                            showSsid = wifiPrivacy.showSsid,
                            showBssid = wifiPrivacy.showBssid,
                            onCopyBssid = {
                                if (wifiPrivacy.showBssid) {
                                    com.example.networktoolkit.data.TextReportExportManager.copySensitiveToClipboard(
                                        context = context,
                                        label = "BSSID",
                                        text = net.bssid,
                                        markSensitive = true
                                    )
                                    Toast.makeText(context, "Copied BSSID: ${net.bssid}", Toast.LENGTH_SHORT).show()
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
 * Individual Wi-Fi Network Card displaying SSID, Hidden status, RSSI dBm, Frequency, Channel, Security, and Standard.
 */
@Composable
private fun WifiNetworkCard(
    network: WifiNetworkInfo,
    showSsid: Boolean,
    showBssid: Boolean,
    onCopyBssid: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val signalTier = network.signalTier
    val signalColor = Color(signalTier.hexColor)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
            .testTag("wifi_network_card_${network.bssid.replace(":", "")}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Row 1: Badges (Connected, Hidden, Security Lock) + Signal Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connected Indicator
                if (network.isConnected) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StatusGreen.copy(alpha = 0.2f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "CONNECTED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusGreen,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Hidden SSID Badge
                if (network.isHidden) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StatusAmber.copy(alpha = 0.2f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = StatusAmber,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "HIDDEN SSID",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusAmber
                            )
                        }
                    }
                }

                // Band Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (network.band) {
                        WifiBand.BAND_2_4_GHZ -> StatusBlue.copy(alpha = 0.15f)
                        WifiBand.BAND_5_GHZ -> StellarTeal.copy(alpha = 0.15f)
                        WifiBand.BAND_6_GHZ -> Color(0xFF9C27B0).copy(alpha = 0.15f)
                        WifiBand.UNKNOWN -> Color.Gray.copy(alpha = 0.15f)
                    },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = network.band.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (network.band) {
                            WifiBand.BAND_2_4_GHZ -> StatusBlue
                            WifiBand.BAND_5_GHZ -> StellarTeal
                            WifiBand.BAND_6_GHZ -> Color(0xFFBA68C8)
                            WifiBand.UNKNOWN -> Color.Gray
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Signal Strength Pill with dBm Kind
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = signalColor.copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = signalColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${network.signalDbm} dBm • ${signalTier.label}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = signalColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: SSID & Lock Status + Ceiling/Floor Kind Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (network.isSecure) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (network.isSecure) MaterialTheme.colorScheme.onSurfaceVariant else StatusAmber,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))

                Text(
                    text = if (showSsid) network.displaySsid else WifiPrivacyPreferences.MASKED_SSID,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = when {
                        !showSsid -> MaterialTheme.colorScheme.onSurfaceVariant
                        network.isHidden -> StatusAmber
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("wifi_card_ssid_${network.bssid.replace(":", "")}")
                )

                Text(
                    text = "${signalTier.ceilingFloorKind} (${network.signalQualityPercent}%)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = signalColor
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "Tier Floor/Ceiling: ${signalTier.tierFloorDbm} to ${signalTier.tierCeilingDbm} dBm • RF Floor ${SignalStrengthTier.OVERALL_FLOOR_DBM} / Ceiling ${SignalStrengthTier.OVERALL_CEILING_DBM} dBm",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            // Row 3: Channel, Frequency, Width & Standard
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ch ${network.channelNumber} (${network.frequencyMhz} MHz) • ${network.channelWidth}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = network.wifiStandard,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(4.dp))

            // Row 4: BSSID (MAC) & Security
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (showBssid) network.bssid else WifiPrivacyPreferences.MASKED_BSSID,
                        fontSize = 11.sp,
                        fontFamily = if (showBssid) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("wifi_card_bssid_${network.bssid.replace(":", "")}")
                    )
                    if (showBssid) {
                        IconButton(
                            onClick = onCopyBssid,
                            modifier = Modifier
                                .testTag("wifi_card_copy_bssid_${network.bssid.replace(":", "")}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy MAC",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = network.securitySummary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expandable Technical Specs
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "FIELD TECHNICIAN TELEMETRY:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))

                    DetailItem(label = "Signal Kind", value = "${signalTier.label} (${signalTier.ceilingFloorKind})")
                    DetailItem(label = "Tier Floor / Ceiling", value = "${signalTier.tierFloorDbm} to ${signalTier.tierCeilingDbm} dBm")
                    DetailItem(label = "Signal Quality", value = "${network.signalQualityLabel} (${network.signalQualityPercent}%)")
                    DetailItem(label = "Estimated Distance", value = "~${network.estimatedDistanceMeters} meters (Free-Space)")
                    DetailItem(label = "WPS Support", value = if (network.hasWps) "Enabled" else "No")
                    DetailItem(label = "Raw Capabilities", value = network.rawCapabilities)

                    Spacer(Modifier.height(8.dp))
                    SignalCeilingFloorGauge(rssiDbm = network.signalDbm)
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Visual channel distribution breakdown helping technicians identify frequency interference.
 */
@Composable
private fun ChannelDistributionCard(
    networks: List<WifiNetworkInfo>,
    viewModel: WifiScannerViewModel,
    onClose: () -> Unit
) {
    val occupancy = remember(networks) { viewModel.getChannelOccupancy(networks) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WifiChannel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Channel Congestion Distribution",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Close", modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Channel pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                occupancy.forEach { ch ->
                    val color = when {
                        ch.count >= 4 -> StatusRed
                        ch.count >= 2 -> StatusAmber
                        else -> StatusGreen
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Ch ${ch.channel}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${ch.count} AP${if (ch.count > 1) "s" else ""}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareBanner(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StatusAmber.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = StatusAmber)
                Text(text = message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = StatusAmber),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(actionText, fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyWifiScanView(
    hasQuery: Boolean,
    isScanning: Boolean,
    onClearSearch: () -> Unit,
    onScanNow: () -> Unit
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
                        imageVector = if (hasQuery) Icons.Default.Search else Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (hasQuery) "No Matching Networks" else if (isScanning) "Discovering Access Points..." else "No Wi-Fi Networks Found",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (hasQuery) {
                    "No access points match your filter criteria."
                } else if (isScanning) {
                    "Listening for 802.11 beacon frames and probe responses..."
                } else {
                    "Tap Scan Now or pull to discover nearby Wi-Fi access points and analyze signal strength."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(16.dp))

            if (hasQuery) {
                OutlinedButton(onClick = onClearSearch) {
                    Text("Clear Filter")
                }
            } else if (!isScanning) {
                Button(
                    onClick = onScanNow,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Now")
                }
            }
        }
    }
}

@Composable
private fun WifiScanReportDialog(
    networkCount: Int,
    onDismiss: () -> Unit,
    onSaveTxt: () -> Unit,
    onShareText: () -> Unit,
    onShareJson: () -> Unit,
    onCopyText: () -> Unit,
    onCopyJson: () -> Unit
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
                Text("Export Wi-Fi Scan Report")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Export survey data for $networkCount discovered access points (exported RTF files redact SSID & BSSID):",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Button(
                    onClick = onSaveTxt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wifi_scan_save_txt_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save RTF Report (Choose Folder)...")
                }

                OutlinedButton(
                    onClick = onShareText,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Formatted RTF (Email / Apps)")
                }

                OutlinedButton(
                    onClick = onShareJson,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share JSON Survey Data")
                }

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

@Composable
private fun ScanningRefreshIcon(
    isScanning: Boolean,
    tint: Color
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val pauseForBatterySaver = com.example.networktoolkit.rememberShouldPauseNonEssentialAnimations()
    val shouldAnimate = isScanning && !pauseForBatterySaver && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val angle = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "scan_spin")
        val animatedAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spin_angle"
        )
        animatedAngle
    } else {
        0f
    }

    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = "Scan Wi-Fi",
        tint = tint,
        modifier = if (shouldAnimate) Modifier.rotate(angle) else Modifier
    )
}
