package com.example.networktoolkit.signalmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Property & Survey Overview Card allowing technicians to launch a New Survey wizard,
 * edit current property settings/room count/floors, open saved surveys, or manage custom rooms.
 */
@Composable
fun PropertySurveySummaryCard(
    config: PropertySurveyConfig,
    roomCount: Int,
    sampleCount: Int,
    onOpenNewSurvey: () -> Unit,
    onEditPropertyMetadata: () -> Unit,
    onOpenRoomManager: () -> Unit,
    onOpenSavedSurveys: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.HomeWork,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = config.displayPropertyTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$roomCount rooms/areas • ${config.floorCount} floor${if (config.floorCount > 1) "s" else ""} • $sampleCount samples",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onEditPropertyMetadata,
                    modifier = Modifier.testTag("edit_property_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Property Configuration"
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenNewSurvey,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_survey_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Survey", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenRoomManager,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("configure_rooms_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rooms ($roomCount)", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenSavedSurveys,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("saved_surveys_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Surveys", fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Field Technician Room-Entry Confirmation Bar.
 * Shows the currently active room, its custom type, floor, and sample count, plus fast
 * controls to Confirm Current Room, Add Room / Area, or Edit / Rename the current room.
 */
@Composable
fun RoomWalkthroughWorkflowCard(
    selectedRoom: RoomZone?,
    isRoomConfirmed: Boolean,
    roomSampleCount: Int,
    onConfirmRoom: () -> Unit,
    onAddRoomHere: () -> Unit,
    onEditCurrentRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isRoomConfirmed) Color(0xFF00E676).copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = null,
                        tint = if (isRoomConfirmed) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = selectedRoom?.name ?: "No Room Selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = if (selectedRoom != null) {
                            "${selectedRoom.effectiveRoomType} • Floor ${selectedRoom.floorNumber} • $roomSampleCount samples"
                        } else {
                            "Select or add a room before recording measurements"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selectedRoom != null) {
                    FilterChip(
                        selected = isRoomConfirmed,
                        onClick = onConfirmRoom,
                        label = {
                            Text(
                                text = if (isRoomConfirmed) "Confirmed" else "Confirm Room",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E676).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF00E676)
                        ),
                        modifier = Modifier.testTag("confirm_room_button")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAddRoomHere,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_room_quick_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Room / Area", fontSize = 12.sp)
                }

                if (selectedRoom != null) {
                    OutlinedButton(
                        onClick = onEditCurrentRoom,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_current_room_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit / Rename", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Dialog for starting a New Custom Property Survey OR editing current property metadata.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropertySurveyConfigDialog(
    initialConfig: PropertySurveyConfig,
    isEditingExisting: Boolean,
    onDismiss: () -> Unit,
    onConfirmNewSurvey: (
        propertyType: PropertyType,
        propertyName: String,
        roomCount: Int,
        floorCount: Int,
        useStarterRooms: Boolean
    ) -> Unit,
    onConfirmUpdateMetadata: (
        propertyType: PropertyType,
        propertyName: String,
        floorCount: Int
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialConfig.propertyType) }
    var propertyName by remember { mutableStateOf(initialConfig.propertyName) }
    var floorCountText by remember { mutableStateOf(initialConfig.floorCount.toString()) }
    var roomCountText by remember {
        mutableStateOf(
            if (isEditingExisting) initialConfig.configuredRoomCount.toString()
            else initialConfig.propertyType.defaultRoomCount.toString()
        )
    }
    var useStarterRooms by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditingExisting) "Edit Property Configuration" else "New Signal Map Survey")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Property Type",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PropertyType.entries.forEach { pType ->
                        FilterChip(
                            selected = selectedType == pType,
                            onClick = {
                                selectedType = pType
                                if (!isEditingExisting) {
                                    roomCountText = pType.defaultRoomCount.toString()
                                }
                            },
                            label = { Text(pType.title) }
                        )
                    }
                }

                OutlinedTextField(
                    value = propertyName,
                    onValueChange = { propertyName = it },
                    label = { Text("Property Name / ID (Optional)") },
                    placeholder = { Text("e.g., Smith Residence or Suite 402") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("property_name_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isEditingExisting) {
                        OutlinedTextField(
                            value = roomCountText,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() } && input.length <= 3) {
                                    roomCountText = input
                                }
                            },
                            label = { Text("Rooms / Areas") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("property_room_count_input")
                        )
                    }

                    OutlinedTextField(
                        value = floorCountText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 2) {
                                floorCountText = input
                            }
                        },
                        label = { Text("Floors (Opt.)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("property_floor_count_input")
                    )
                }

                if (!isEditingExisting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useStarterRooms = !useStarterRooms }
                    ) {
                        Checkbox(
                            checked = useStarterRooms,
                            onCheckedChange = { useStarterRooms = it }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Generate initial editable rooms for count (you can rename, add, or remove any room anytime)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(
                        text = "Editing property details preserves all existing rooms and recorded measurements.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val floors = (floorCountText.toIntOrNull() ?: 1).coerceAtLeast(1)
                    if (isEditingExisting) {
                        onConfirmUpdateMetadata(selectedType, propertyName, floors)
                    } else {
                        val rCount = (roomCountText.toIntOrNull() ?: selectedType.defaultRoomCount).coerceAtLeast(1)
                        onConfirmNewSurvey(selectedType, propertyName, rCount, floors, useStarterRooms)
                    }
                },
                modifier = Modifier.testTag("confirm_property_setup_button")
            ) {
                Text(if (isEditingExisting) "Save Changes" else "Start Survey")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for Adding a Room/Area OR Editing / Renaming / Changing Type of an existing Room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomEditorDialog(
    existingRoom: RoomZone?,
    maxFloors: Int,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        roomType: RoomType,
        customTypeLabel: String?,
        floorNumber: Int,
        isPrimaryApLocation: Boolean
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf(existingRoom?.roomType ?: RoomType.BEDROOM) }
    var customTypeText by remember { mutableStateOf(existingRoom?.customTypeLabel ?: "") }
    var roomName by remember { mutableStateOf(existingRoom?.name ?: "") }
    var floorNumber by remember { mutableIntStateOf(existingRoom?.floorNumber ?: 1) }
    var isPrimaryAp by remember { mutableStateOf(existingRoom?.isPrimaryApLocation ?: false) }

    val quickNameExamples = listOf(
        "Bedroom 1", "Bedroom 2", "Master Bedroom", "Main Hallway", "Upstairs Hallway", "Home Office", "Back Room"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existingRoom == null) "Add Room / Survey Area" else "Edit Room: ${existingRoom.name}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Custom Room / Area Name") },
                    placeholder = { Text("e.g., Bedroom 2 or Upstairs Hallway") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("room_name_input")
                )

                Text(
                    text = "Quick Name Presets",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickNameExamples.forEach { suggestion ->
                        FilterChip(
                            selected = roomName == suggestion,
                            onClick = {
                                roomName = suggestion
                                selectedType = RoomType.inferFromName(suggestion)
                            },
                            label = { Text(suggestion, fontSize = 11.sp) }
                        )
                    }
                }

                Text(
                    text = "Room Type",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RoomType.entries.forEach { rType ->
                        FilterChip(
                            selected = selectedType == rType,
                            onClick = {
                                selectedType = rType
                                if (roomName.isBlank() && rType != RoomType.OTHER) {
                                    roomName = rType.displayName
                                }
                            },
                            label = { Text(rType.displayName, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = customTypeText,
                    onValueChange = { customTypeText = it },
                    label = { Text("Custom Room Type (Optional)") },
                    placeholder = { Text("e.g., Server Closet, Sunroom, Lab Bay") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_room_type_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Floor: $floorNumber", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (floorNumber > 1) floorNumber-- }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease Floor")
                        }
                        IconButton(
                            onClick = { floorNumber++ }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase Floor")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Router,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Primary Router / AP Room", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = isPrimaryAp,
                        onCheckedChange = { isPrimaryAp = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveName = roomName.trim().ifEmpty {
                        customTypeText.trim().ifEmpty { selectedType.displayName }
                    }
                    onSave(
                        effectiveName,
                        selectedType,
                        customTypeText.trim().ifEmpty { null },
                        floorNumber.coerceAtLeast(1),
                        isPrimaryAp
                    )
                },
                modifier = Modifier.testTag("save_room_button")
            ) {
                Text(if (existingRoom == null) "Add Room" else "Save Room")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Full Room Configuration Manager Dialog supporting reviewing rooms, selecting current room,
 * adding rooms, renaming/retyping, reordering (rearranging floor plan), and safe removal.
 */
@Composable
fun RoomManagerDialog(
    rooms: List<RoomZone>,
    samples: List<SignalSample>,
    selectedRoomId: String?,
    onDismiss: () -> Unit,
    onSelectCurrentRoom: (RoomZone) -> Unit,
    onAddRoom: () -> Unit,
    onEditRoom: (RoomZone) -> Unit,
    onMoveRoom: (roomId: String, moveUp: Boolean) -> Unit,
    onRequestRemoveRoom: (RoomZone) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Configured Rooms & Areas (${rooms.size})")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddRoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manager_add_room_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add Room or Survey Area")
                }

                rooms.forEachIndexed { index, room ->
                    val isSelected = room.id == selectedRoomId
                    val roomSamples = samples.count { it.belongsToRoom(room) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSelectCurrentRoom(room) }
                                ) {
                                    Text(
                                        text = "${index + 1}. ${room.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${room.effectiveRoomType} • Floor ${room.floorNumber} • $roomSamples samples${if (room.isPrimaryApLocation) " • Primary AP" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = { onMoveRoom(room.id, true) },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onMoveRoom(room.id, false) },
                                        enabled = index < rooms.lastIndex
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onEditRoom(room) }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Room", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onRequestRemoveRoom(room) },
                                        enabled = rooms.size > 1
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove Room",
                                            tint = if (rooms.size > 1) MaterialTheme.colorScheme.error else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

/**
 * Dialog to review and continue previously saved Signal Mapper surveys.
 */
@Composable
fun SavedSurveysDialog(
    surveys: List<SavedSurveySummary>,
    activeSurveyId: String,
    onDismiss: () -> Unit,
    onContinueSurvey: (String) -> Unit,
    onDeleteSurvey: (String) -> Unit,
    onStartNewSurvey: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved Signal Surveys (${surveys.size})") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (surveys.isEmpty()) {
                    Text(
                        text = "No saved surveys yet. Measurements and room layouts are automatically saved as you work.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    surveys.forEach { summary ->
                        val isActive = summary.surveyId == activeSurveyId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onContinueSurvey(summary.surveyId) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = summary.displayTitle + if (isActive) " (Active)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${summary.propertyType.title} • ${summary.roomCount} rooms • ${summary.sampleCount} samples • ${sdf.format(Date(summary.updatedAtMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!isActive) {
                                    IconButton(onClick = { onDeleteSurvey(summary.surveyId) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Survey",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                OutlinedButton(
                    onClick = onStartNewSurvey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start New Custom Survey")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Confirmation dialog for safely removing a room without harming unrelated rooms.
 */
@Composable
fun RemoveRoomConfirmDialog(
    room: RoomZone,
    sampleCountInRoom: Int,
    onDismiss: () -> Unit,
    onConfirmKeepArchivedSamples: () -> Unit,
    onConfirmDeleteSamples: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"${room.name}\"?") },
        text = {
            Text(
                if (sampleCountInRoom > 0) {
                    "This room currently has $sampleCountInRoom recorded signal measurement(s). Unrelated rooms and their measurements will remain untouched. Would you like to archive those measurements or remove them?"
                } else {
                    "Remove \"${room.name}\" from the custom floor plan? Unrelated rooms and measurements will not be affected."
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirmKeepArchivedSamples) {
                Text(if (sampleCountInRoom > 0) "Remove & Archive Data" else "Remove Room")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sampleCountInRoom > 0) {
                    TextButton(
                        onClick = onConfirmDeleteSamples,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Remove & Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
