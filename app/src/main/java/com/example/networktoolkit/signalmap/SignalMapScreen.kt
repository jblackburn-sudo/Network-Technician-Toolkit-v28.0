package com.example.networktoolkit.signalmap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import com.example.networktoolkit.rememberShouldPauseNonEssentialAnimations
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.networktoolkit.SignalCeilingFloorGauge
import com.example.networktoolkit.WifiPrivacyPreferences
import com.example.networktoolkit.rememberWifiPrivacySettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalMapScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignalMapViewModel = viewModel()
) {
    val context = LocalContext.current
    val propertyConfig by viewModel.propertyConfig.collectAsState()
    val propertyType by viewModel.propertyType.collectAsState()
    val rooms by viewModel.rooms.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val selectedRoom by viewModel.selectedRoom.collectAsState()
    val confirmedRoomId by viewModel.confirmedRoomId.collectAsState()
    val activeFloorFilter by viewModel.activeFloorFilter.collectAsState()
    val savedSurveys by viewModel.savedSurveys.collectAsState()
    val selectedSampleForDetail by viewModel.selectedSampleForDetail.collectAsState()
    val liveTelemetry by viewModel.liveTelemetry.collectAsState()
    val analysis by viewModel.coverageAnalysis.collectAsState()
    val pauseForBatterySaver = rememberShouldPauseNonEssentialAnimations()

    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDemoOverwriteConfirmDialog by remember { mutableStateOf(false) }
    var showPropertyConfigDialog by remember { mutableStateOf(false) }
    var isEditingPropertyMetadata by remember { mutableStateOf(false) }
    var showRoomEditorDialog by remember { mutableStateOf(false) }
    var editingRoomForDialog by remember { mutableStateOf<RoomZone?>(null) }
    var showRoomManagerDialog by remember { mutableStateOf(false) }
    var showSavedSurveysDialog by remember { mutableStateOf(false) }
    var roomPendingRemoval by remember { mutableStateOf<RoomZone?>(null) }

    val availableFloors = remember(propertyConfig.floorCount, rooms) {
        ((1..propertyConfig.floorCount).toSet() + rooms.map { it.floorNumber }).sorted()
    }
    val visibleRooms = remember(rooms, activeFloorFilter) {
        activeFloorFilter?.let { floor -> rooms.filter { it.floorNumber == floor } } ?: rooms
    }

    val exportController = rememberSafTextExportController()
    val wifiPrivacy = rememberWifiPrivacySettings()

    val saveSignalMapTxt: () -> Unit = {
        val rawReport = viewModel.generateTextReport()
        val identifiers = viewModel.collectSensitiveIdentifiers(context)
        exportController.promptPrivacyAndRun("Signal Map RTF export") { privacyChoice ->
            exportController.launchSaveTxt(
                suggestedFilename = viewModel.getDefaultTxtFilename(),
                rawContent = rawReport,
                identifiers = identifiers,
                privacyChoice = privacyChoice
            )
        }
    }

    val shareSignalMapTxt: () -> Unit = {
        val rawReport = viewModel.generateTextReport()
        val identifiers = viewModel.collectSensitiveIdentifiers(context)
        exportController.promptPrivacyAndRun("Signal Map RTF share") { privacyChoice ->
            exportController.shareReport(
                rawContent = rawReport,
                suggestedFilename = viewModel.getDefaultTxtFilename(),
                subject = "Wi-Fi Signal Map Survey - ${propertyConfig.displayPropertyTitle}",
                chooserTitle = "Share Signal Map Survey",
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
                    Column {
                        Text(
                            text = "Signal Mapper",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val subtitle = buildString {
                            append(propertyConfig.displayPropertyTitle)
                            append(" • ${rooms.size} rooms")
                            if (propertyConfig.floorCount > 1) {
                                append(" • ${propertyConfig.floorCount}F")
                            }
                            if (pauseForBatterySaver) {
                                append(" • Battery Saver")
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Demo Walkthrough Button
                    IconButton(
                        onClick = {
                            if (samples.any { !it.isSimulated }) {
                                showDemoOverwriteConfirmDialog = true
                            } else {
                                viewModel.populateDemoSurvey()
                                Toast.makeText(
                                    context,
                                    "Populated simulated demo points for ${propertyConfig.displayPropertyTitle}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.testTag("demo_survey_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Demo Survey",
                            tint = Color(0xFF00E5FF)
                        )
                    }

                    // Clear Survey Button
                    if (samples.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.testTag("clear_survey_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All Samples",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    ToolContextualHelpAction(toolId = "signal_mapper")

                    // Survey & Export Menu
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("survey_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Survey and Export Options"
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New Custom Survey...") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = {
                                    showMenu = false
                                    isEditingPropertyMetadata = false
                                    showPropertyConfigDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Configure Rooms & Areas...") },
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                onClick = {
                                    showMenu = false
                                    showRoomManagerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Saved Surveys (${savedSurveys.size})") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.refreshSavedSurveysList()
                                    showSavedSurveysDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Save RTF Report (Choose Folder)...") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = {
                                    showMenu = false
                                    saveSignalMapTxt()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share Survey Report (RTF)") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showMenu = false
                                    shareSignalMapTxt()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share Survey Data (JSON)") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showMenu = false
                                    exportController.promptPrivacyAndRun("Signal Map JSON share") { privacyChoice ->
                                        viewModel.createShareIntent(context, isJson = true, privacyChoice = privacyChoice)
                                            .onSuccess { intent ->
                                                try {
                                                    context.startActivity(intent)
                                                } catch (t: Throwable) {
                                                    exportController.reportFailure(t, "Launching Android share chooser")
                                                }
                                            }
                                            .onFailure { err ->
                                                exportController.reportFailure(err, "Preparing JSON survey report for sharing")
                                            }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Report to Clipboard") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = {
                                    showMenu = false
                                    val rawText = viewModel.generateTextReport()
                                    val identifiers = viewModel.collectSensitiveIdentifiers(context)
                                    TextReportExportManager.copyRedactedToClipboard(
                                        context = context,
                                        label = "Wi-Fi Survey",
                                        rawContent = rawText,
                                        identifiers = identifiers,
                                        isJson = false,
                                        markSensitive = true
                                    ).onSuccess {
                                        Toast.makeText(context, "Redacted report copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        exportController.reportFailure(err, "Copying privacy-redacted Wi-Fi survey report")
                                    }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("signal_map_list"),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // 1. CUSTOM PROPERTY & SURVEY CONFIGURATION HEADER
            item {
                PropertySurveySummaryCard(
                    config = propertyConfig,
                    roomCount = rooms.size,
                    sampleCount = samples.size,
                    onOpenNewSurvey = {
                        isEditingPropertyMetadata = false
                        showPropertyConfigDialog = true
                    },
                    onEditPropertyMetadata = {
                        isEditingPropertyMetadata = true
                        showPropertyConfigDialog = true
                    },
                    onOpenRoomManager = { showRoomManagerDialog = true },
                    onOpenSavedSurveys = {
                        viewModel.refreshSavedSurveysList()
                        showSavedSurveysDialog = true
                    }
                )
            }

            // 2. LIVE CONNECTED SIGNAL TELEMETRY BAR
            item {
                LiveSignalTelemetryCard(
                    telemetry = liveTelemetry,
                    showSsid = wifiPrivacy.showSsid,
                    selectedRoom = selectedRoom,
                    onRecordSample = {
                        viewModel.recordSampleInSelectedRoom()
                        Toast.makeText(
                            context,
                            "Recorded sample in ${selectedRoom?.name ?: "room"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            // 2B. TECHNICIAN WALKTHROUGH ROOM-ENTRY WORKFLOW BAR
            item {
                val roomCountForSelected = selectedRoom?.let { r ->
                    samples.count { it.belongsToRoom(r) }
                } ?: 0
                RoomWalkthroughWorkflowCard(
                    selectedRoom = selectedRoom,
                    isRoomConfirmed = selectedRoom != null && confirmedRoomId == selectedRoom?.id,
                    roomSampleCount = roomCountForSelected,
                    onConfirmRoom = {
                        selectedRoom?.let { r ->
                            viewModel.confirmCurrentRoom(r.id)
                            Toast.makeText(context, "Confirmed current room: ${r.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddRoomHere = {
                        editingRoomForDialog = null
                        showRoomEditorDialog = true
                    },
                    onEditCurrentRoom = {
                        selectedRoom?.let { r ->
                            editingRoomForDialog = r
                            showRoomEditorDialog = true
                        }
                    }
                )
            }

            // 2C. MULTI-FLOOR FILTER CHIPS (WHEN PROPERTY HAS >1 FLOOR)
            if (availableFloors.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = activeFloorFilter == null,
                            onClick = { viewModel.setFloorFilter(null) },
                            label = { Text("All Floors (${rooms.size})") },
                            modifier = Modifier.testTag("floor_filter_all")
                        )
                        availableFloors.forEach { fl ->
                            val flRoomCount = rooms.count { it.floorNumber == fl }
                            FilterChip(
                                selected = activeFloorFilter == fl,
                                onClick = { viewModel.setFloorFilter(fl) },
                                label = { Text("Floor $fl ($flRoomCount)") },
                                modifier = Modifier.testTag("floor_filter_$fl")
                            )
                        }
                    }
                }
            }

            // 3. INTERACTIVE BLUEPRINT & HEATMAP CANVAS
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "INTERACTIVE SIGNAL HEATMAP",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pauseForBatterySaver) {
                                Icon(
                                    imageVector = Icons.Default.BatterySaver,
                                    contentDescription = "Battery Saver Pausing Animations",
                                    tint = Color(0xFFFFD600),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = "Tap room or canvas to sample",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SignalMapCanvas(
                        propertyType = propertyType,
                        rooms = visibleRooms,
                        samples = samples,
                        selectedRoom = selectedRoom,
                        selectedSample = selectedSampleForDetail,
                        onRoomSelected = { room -> viewModel.selectRoom(room) },
                        onCanvasTapped = { relX, relY ->
                            viewModel.recordSampleAtCoordinates(relX, relY)
                            Toast.makeText(context, "Recorded measurement at location", Toast.LENGTH_SHORT).show()
                        },
                        onSampleTapped = { sample ->
                            viewModel.selectSampleForDetail(sample)
                        },
                        isBeaconPulseActive = liveTelemetry.isConnected || samples.isNotEmpty()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Heatmap Scale Legend
                    HeatmapLegendBar()
                }
            }

            // 4. ROOM QUICK-SELECT ROW
            item {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "SELECT ROOM OR ADD AREA TO SAMPLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleRooms, key = { it.id }) { room ->
                            val isSelected = selectedRoom?.id == room.id
                            val roomSampleCount = samples.count { it.belongsToRoom(room) }
                            val floorBadge = if (propertyConfig.floorCount > 1) "F${room.floorNumber} " else ""
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectRoom(room) },
                                label = {
                                    Text(
                                        text = "$floorBadge${room.name} ($roomSampleCount)"
                                    )
                                },
                                leadingIcon = when {
                                    room.isPrimaryApLocation -> {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Router,
                                                contentDescription = "Primary Router",
                                                tint = Color(0xFF00E676),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    confirmedRoomId == room.id -> {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Confirmed Room",
                                                tint = Color(0xFF00E676),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    else -> null
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("room_chip_${room.id}")
                            )
                        }

                        item(key = "add_room_chip") {
                            AssistChip(
                                onClick = {
                                    editingRoomForDialog = null
                                    showRoomEditorDialog = true
                                },
                                label = { Text("+ Add Room") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Room",
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.testTag("add_room_chip")
                            )
                        }
                    }
                }
            }

            // 5. COVERAGE ANALYSIS & TECHNICIAN RECOMMENDATIONS CARD
            item {
                CoverageAnalysisCard(
                    analysis = analysis,
                    propertyType = propertyType,
                    onSaveTxtReport = saveSignalMapTxt,
                    onShareTxtReport = shareSignalMapTxt,
                    onClearSimulated = { viewModel.clearSimulatedSamples() },
                    modifier = Modifier.padding(16.dp)
                )
            }

            // 5B. REAL-TIME WI-FI SIGNAL FLUCTUATION GRAPH (-50 dBm to -90 dBm)
            item {
                com.example.networktoolkit.WifiSignalFluctuationGraph(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // 6. RECORDED SAMPLES BREAKDOWN
            if (samples.isNotEmpty()) {
                item {
                    Text(
                        text = "RECORDED MEASUREMENTS (${samples.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(samples, key = { it.id }) { sample ->
                    SampleListItem(
                        sample = sample,
                        onDelete = { viewModel.deleteSample(sample.id) },
                        onSelect = { viewModel.selectSampleForDetail(sample) },
                        isSelected = selectedSampleForDetail?.id == sample.id,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    // Dialog: New Custom Property Survey OR Edit Existing Property Metadata
    if (showPropertyConfigDialog) {
        PropertySurveyConfigDialog(
            initialConfig = propertyConfig,
            isEditingExisting = isEditingPropertyMetadata,
            onDismiss = { showPropertyConfigDialog = false },
            onConfirmNewSurvey = { type, name, roomCount, floorCount, useStarter ->
                viewModel.startNewSurvey(
                    propertyType = type,
                    propertyName = name,
                    roomCount = roomCount,
                    floorCount = floorCount,
                    useStarterRooms = useStarter
                )
                showPropertyConfigDialog = false
                Toast.makeText(context, "Started new ${type.title} survey", Toast.LENGTH_SHORT).show()
            },
            onConfirmUpdateMetadata = { type, name, floorCount ->
                viewModel.updatePropertyMetadata(
                    propertyType = type,
                    propertyName = name,
                    floorCount = floorCount
                )
                showPropertyConfigDialog = false
                Toast.makeText(context, "Updated property settings", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Dialog: Add Room / Area OR Edit / Rename / Retype Existing Room
    if (showRoomEditorDialog) {
        RoomEditorDialog(
            existingRoom = editingRoomForDialog,
            maxFloors = propertyConfig.floorCount,
            onDismiss = {
                showRoomEditorDialog = false
                editingRoomForDialog = null
            },
            onSave = { name, roomType, customTypeLabel, floorNumber, isPrimaryAp ->
                val editing = editingRoomForDialog
                if (editing == null) {
                    val created = viewModel.addRoom(
                        name = name,
                        roomType = roomType,
                        customTypeLabel = customTypeLabel,
                        floorNumber = floorNumber,
                        isPrimaryApLocation = isPrimaryAp,
                        selectAfterAdd = true
                    )
                    Toast.makeText(context, "Added ${created.name}", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.updateRoom(
                        roomId = editing.id,
                        name = name,
                        roomType = roomType,
                        customTypeLabel = customTypeLabel,
                        floorNumber = floorNumber,
                        isPrimaryApLocation = isPrimaryAp
                    )
                    Toast.makeText(context, "Updated $name", Toast.LENGTH_SHORT).show()
                }
                showRoomEditorDialog = false
                editingRoomForDialog = null
            }
        )
    }

    // Dialog: Room Configuration Manager
    if (showRoomManagerDialog) {
        RoomManagerDialog(
            rooms = rooms,
            samples = samples,
            selectedRoomId = selectedRoom?.id,
            onDismiss = { showRoomManagerDialog = false },
            onSelectCurrentRoom = { room ->
                viewModel.confirmCurrentRoom(room.id)
                showRoomManagerDialog = false
            },
            onAddRoom = {
                editingRoomForDialog = null
                showRoomEditorDialog = true
            },
            onEditRoom = { room ->
                editingRoomForDialog = room
                showRoomEditorDialog = true
            },
            onMoveRoom = { roomId, moveUp ->
                viewModel.moveRoomOrder(roomId, moveUp)
            },
            onRequestRemoveRoom = { room ->
                roomPendingRemoval = room
            }
        )
    }

    // Dialog: Saved Surveys (Continue Previous Survey)
    if (showSavedSurveysDialog) {
        SavedSurveysDialog(
            surveys = savedSurveys,
            activeSurveyId = propertyConfig.surveyId,
            onDismiss = { showSavedSurveysDialog = false },
            onContinueSurvey = { surveyId ->
                if (viewModel.continueSurvey(surveyId)) {
                    Toast.makeText(context, "Loaded saved survey", Toast.LENGTH_SHORT).show()
                }
                showSavedSurveysDialog = false
            },
            onDeleteSurvey = { surveyId ->
                viewModel.deleteSavedSurvey(surveyId)
            },
            onStartNewSurvey = {
                showSavedSurveysDialog = false
                isEditingPropertyMetadata = false
                showPropertyConfigDialog = true
            }
        )
    }

    // Dialog: Safe Room Removal Confirmation
    if (roomPendingRemoval != null) {
        val targetRoom = roomPendingRemoval!!
        val countInRoom = samples.count { it.belongsToRoom(targetRoom) }
        RemoveRoomConfirmDialog(
            room = targetRoom,
            sampleCountInRoom = countInRoom,
            onDismiss = { roomPendingRemoval = null },
            onConfirmKeepArchivedSamples = {
                viewModel.removeRoom(targetRoom.id, deleteAssociatedSamples = false)
                roomPendingRemoval = null
                Toast.makeText(context, "Removed ${targetRoom.name}", Toast.LENGTH_SHORT).show()
            },
            onConfirmDeleteSamples = {
                viewModel.removeRoom(targetRoom.id, deleteAssociatedSamples = true)
                roomPendingRemoval = null
                Toast.makeText(context, "Removed ${targetRoom.name} and its samples", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Dialog: Clear All Samples confirmation
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Reset Heatmap Survey?") },
            text = { Text("This will clear all ${samples.size} recorded measurement samples from the current floor plan.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllSamples()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Sample Detail Dialog
    if (selectedSampleForDetail != null) {
        val s = selectedSampleForDetail!!
        AlertDialog(
            onDismissRequest = { viewModel.selectSampleForDetail(null) },
            icon = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(s.rating.color),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = Color.Black
                    )
                }
            },
            title = { Text(s.roomName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Signal Strength: ${s.rssiDbm} dBm • ${s.rating.label}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Floor ${s.floorNumber} • ${s.roomTypeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Signal Kind: ${s.rating.ceilingFloorKind} (${s.rating.rangeLabel})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SignalCeilingFloorGauge(rssiDbm = s.rssiDbm)
                    Text(
                        text = if (s.isSimulated) "Source: SIMULATED / Demo Sample" else "Source: Live Wi-Fi RF Measurement",
                        fontWeight = FontWeight.Bold,
                        color = if (s.isSimulated) Color(0xFFFFD600) else Color(0xFF00E676),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (s.linkSpeedMbps > 0) "Wi-Fi Link Speed: ${s.linkSpeedMbps} Mbps" else "Wi-Fi Link Speed: Unavailable"
                    )
                    if (s.frequencyMhz > 0) {
                        Text("Frequency: ${s.frequencyMhz} MHz (Channel ${s.channel})")
                    }
                    if (s.ssid.isNotBlank()) {
                        Text(
                            text = if (wifiPrivacy.showSsid) {
                                "SSID: ${s.ssid}"
                            } else {
                                "SSID: ${WifiPrivacyPreferences.MASKED_SSID}"
                            }
                        )
                    }
                    if (s.bssid.isNotBlank()) {
                        Text(
                            text = if (wifiPrivacy.showBssid) {
                                "BSSID: ${s.bssid}"
                            } else {
                                "BSSID: ${WifiPrivacyPreferences.MASKED_BSSID}"
                            }
                        )
                    }
                    Text("Recommendation: ${s.rating.recommendation}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.selectSampleForDetail(null) }) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSample(s.id)
                        viewModel.selectSampleForDetail(null)
                    }
                ) {
                    Text("Delete Sample", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    if (showDemoOverwriteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDemoOverwriteConfirmDialog = false },
            icon = { Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFFFFD600)) },
            title = { Text("Replace Real Survey With Demo Data?") },
            text = {
                Text(
                    "Your current survey contains ${analysis.realSamplesCount} real Wi-Fi RF measurement(s). " +
                        "Loading simulated demo samples will replace those measurements with synthetic preview points."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDemoOverwriteConfirmDialog = false
                        viewModel.populateDemoSurvey()
                        Toast.makeText(context, "Loaded simulated demo preview", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Replace With Demo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDemoOverwriteConfirmDialog = false }) {
                    Text("Keep Real Data")
                }
            }
        )
    }
}

/**
 * Live Connected Wi-Fi Telemetry Bar with One-Tap "Record Sample" Action.
 */
@Composable
private fun LiveSignalTelemetryCard(
    telemetry: LiveConnectedTelemetry,
    showSsid: Boolean,
    selectedRoom: RoomZone?,
    onRecordSample: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF132238)
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Live Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (telemetry.isWifiEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = telemetry.rating.color,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val displayedSsid = when {
                        telemetry.ssid.isBlank() -> "Disconnected"
                        showSsid -> telemetry.ssid
                        else -> WifiPrivacyPreferences.MASKED_SSID
                    }
                    Text(
                        text = displayedSsid,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("signal_map_live_ssid")
                    )
                }

                // Rating Pill
                Surface(
                    color = telemetry.rating.color.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, telemetry.rating.color)
                ) {
                    Text(
                        text = "${telemetry.currentRssiDbm} dBm • ${telemetry.rating.label} (${telemetry.rating.rangeLabel})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = telemetry.rating.color,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            SignalCeilingFloorGauge(
                rssiDbm = telemetry.currentRssiDbm,
                darkSurface = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (telemetry.linkSpeedMbps > 0) "Speed: ${telemetry.linkSpeedMbps} Mbps" else "Speed: N/A",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA0B5D0)
                )
                if (telemetry.channel > 0) {
                    Text(
                        text = "Ch ${telemetry.channel} (${telemetry.frequencyMhz} MHz)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA0B5D0)
                    )
                }
                if (telemetry.isSimulatedDemo) {
                    Text(
                        text = "Demo Mode (Simulated)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD600)
                    )
                }
            }

            if (telemetry.isSimulatedDemo || !telemetry.isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0x22FFD600),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "No live Wi-Fi connection detected: recorded points will be tagged [SIMULATED] and kept separate from real RF statistics.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFD600),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Button
            Button(
                onClick = onRecordSample,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("record_sample_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF002233)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AddLocation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val samplePrefix = if (telemetry.isConnected && !telemetry.isSimulatedDemo) "Record Sample" else "Record Simulated Sample"
                Text(
                    text = "$samplePrefix in ${selectedRoom?.name ?: "Selected Room"}",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Legend explaining the signal gradient colors on the floor plan canvas.
 */
@Composable
private fun HeatmapLegendBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SignalRating.entries.forEach { tier ->
            LegendItem(
                color = tier.color,
                label = "${tier.rangeLabel} (${tier.label})"
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Coverage statistics & AP placement recommendations card.
 */
@Composable
private fun CoverageAnalysisCard(
    analysis: CoverageAnalysis,
    propertyType: PropertyType,
    onSaveTxtReport: () -> Unit,
    onShareTxtReport: () -> Unit,
    onClearSimulated: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "COVERAGE ANALYSIS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Surface(
                    color = when {
                        analysis.coverageScorePercent >= 80 -> Color(0xFF00E676).copy(alpha = 0.2f)
                        analysis.coverageScorePercent >= 60 -> Color(0xFF00E5FF).copy(alpha = 0.2f)
                        else -> Color(0xFFFF9100).copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Score: ${analysis.coverageScorePercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            analysis.coverageScorePercent >= 80 -> Color(0xFF00E676)
                            analysis.coverageScorePercent >= 60 -> Color(0xFF00E5FF)
                            else -> Color(0xFFFF9100)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (analysis.hasSimulatedData) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x22FFD600),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (analysis.isSimulatedOnly) {
                                "SIMULATED PREVIEW ONLY (${analysis.simulatedSamplesCount} synthetic points)"
                            } else {
                                "Real stats from ${analysis.realSamplesCount} live sample(s); ${analysis.simulatedSamplesCount} simulated excluded"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFD600),
                            modifier = Modifier.weight(1f)
                        )
                        if (!analysis.isSimulatedOnly) {
                            TextButton(onClick = onClearSimulated) {
                                Text("Remove Sim", fontSize = 11.sp, color = Color(0xFFFFD600))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { (analysis.coverageScorePercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = when {
                    analysis.coverageScorePercent >= 80 -> Color(0xFF00E676)
                    analysis.coverageScorePercent >= 60 -> Color(0xFF00E5FF)
                    else -> Color(0xFFFF9100)
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val avgText = if (analysis.totalSamples > 0) {
                    val avgR = SignalRating.fromDbm(analysis.averageDbm)
                    "${analysis.averageDbm} dBm (${avgR.label})"
                } else "--"
                StatColumn(label = "Average", value = avgText)
                StatColumn(label = "Samples", value = "${analysis.totalSamples}")
                StatColumn(label = "Dead Zones", value = "${analysis.deadZonesCount}", isWarning = analysis.deadZonesCount > 0)
                StatColumn(label = "Best Zone", value = analysis.bestRoom.take(10))
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Technician Recommendation Box
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TECHNICIAN RECOMMENDATION",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = analysis.summaryRecommendation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = analysis.recommendedApPlacement,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveTxtReport,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("signal_map_save_txt_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save RTF Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onShareTxtReport,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("signal_map_share_txt_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Report", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, isWarning: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * List item displaying a single recorded measurement sample.
 */
@Composable
private fun SampleListItem(
    sample: SignalSample,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(sample.rating.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${sample.rssiDbm}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = sample.rating.color
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sample.roomName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (sample.isSimulated) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Color(0x26FFD600),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "SIMULATED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFD600),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    val speedStr = if (sample.linkSpeedMbps > 0) "${sample.linkSpeedMbps} Mbps" else "Speed Unavail."
                    Text(
                        text = "${sample.rating.label} • ${sample.rating.ceilingFloorKind} (${sample.rating.rangeLabel}) • $speedStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete sample",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
