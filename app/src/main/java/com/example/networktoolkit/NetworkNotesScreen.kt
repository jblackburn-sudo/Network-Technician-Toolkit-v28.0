package com.example.networktoolkit

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.networktoolkit.data.NetworkNoteEntity
import com.example.networktoolkit.data.TextReportExportManager
import com.example.networktoolkit.data.rememberSafTextExportController
import com.example.networktoolkit.learning.ToolContextualHelpAction
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusBlue
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StellarTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PendingNotesExportMode {
    SAVE_RTF,
    SHARE_RTF
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkNotesScreen(
    viewModel: NetworkNotesViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportController = rememberSafTextExportController()

    var showEditDialog by remember { mutableStateOf(false) }
    var noteToEdit by remember { mutableStateOf<NetworkNoteEntity?>(null) }
    var noteToDelete by remember { mutableStateOf<NetworkNoteEntity?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    var revealedNoteIds by remember { mutableStateOf(setOf<Long>()) }
    var pendingExportMode by remember { mutableStateOf<PendingNotesExportMode?>(null) }

    val executeExport: (PendingNotesExportMode, Boolean) -> Unit = { mode, redactCredentials ->
        val report = buildNetworkNotesTextReport(
            notes = uiState.notes,
            redactCredentials = redactCredentials,
            decryptionErrorNoteIds = uiState.decryptionErrorNoteIds
        )
        val identifiers = TextReportExportManager.collectIdentifiers(context)
        val label = if (mode == PendingNotesExportMode.SAVE_RTF) "Notes RTF export" else "Notes RTF share"
        exportController.promptPrivacyAndRun(label) { wifiPrivacyChoice ->
            when (mode) {
                PendingNotesExportMode.SAVE_RTF -> {
                    exportController.launchSaveTxt(
                        suggestedFilename = TextReportExportManager.suggestFilename("Network_Notes_Report"),
                        rawContent = report,
                        identifiers = identifiers,
                        privacyChoice = wifiPrivacyChoice
                    )
                }
                PendingNotesExportMode.SHARE_RTF -> {
                    exportController.shareReport(
                        rawContent = report,
                        suggestedFilename = TextReportExportManager.suggestFilename("Network_Notes_Report"),
                        subject = "Network Technician Toolkit - Field Notes",
                        chooserTitle = "Share Network Notes Report",
                        identifiers = identifiers,
                        privacyChoice = wifiPrivacyChoice
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            placeholder = { Text("Search notes...", fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                                .testTag("notes_search_input"),
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    } else {
                        Text(
                            text = "Network Notes",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("network_notes_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.notes.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val hasSensitive = uiState.notes.any { isSensitiveCredentialNote(it) }
                                if (hasSensitive) {
                                    pendingExportMode = PendingNotesExportMode.SAVE_RTF
                                } else {
                                    executeExport(PendingNotesExportMode.SAVE_RTF, true)
                                }
                            },
                            modifier = Modifier.testTag("notes_save_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SaveAlt,
                                contentDescription = "Save Notes RTF Report"
                            )
                        }
                        IconButton(
                            onClick = {
                                val hasSensitive = uiState.notes.any { isSensitiveCredentialNote(it) }
                                if (hasSensitive) {
                                    pendingExportMode = PendingNotesExportMode.SHARE_RTF
                                } else {
                                    executeExport(PendingNotesExportMode.SHARE_RTF, true)
                                }
                            },
                            modifier = Modifier.testTag("notes_share_txt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Notes RTF Report"
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) viewModel.onSearchQueryChanged("")
                        },
                        modifier = Modifier.testTag("toggle_notes_search_button")
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = "Search Notes"
                        )
                    }
                    ToolContextualHelpAction(toolId = "network_notes")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    noteToEdit = null
                    showEditDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_note_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Network Note")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!uiState.statusMessage.isNullOrBlank()) {
                Surface(
                    color = StatusRed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("notes_status_error_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.statusMessage.orEmpty(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = { viewModel.clearStatusMessage() }) {
                            Text("Dismiss", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Category Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.availableCategories) { cat ->
                    val isSelected = cat.equals(uiState.selectedCategory, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) else null,
                        modifier = Modifier
                            .clickable { viewModel.onCategorySelected(cat) }
                            .testTag("category_chip_$cat")
                    ) {
                        Text(
                            text = cat,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Notes List
            if (uiState.notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.NoteAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank() || uiState.selectedCategory != "All") "No matching notes found" else "No network notes yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the + button to create a field technician note for rack IP, VLAN, or encrypted credentials.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.notes, key = { it.id }) { note ->
                        val isDecryptionError = note.id in uiState.decryptionErrorNoteIds
                        val isRevealed = note.id in revealedNoteIds
                        NoteCard(
                            note = note,
                            isRevealed = isRevealed,
                            isDecryptionError = isDecryptionError,
                            onToggleReveal = {
                                revealedNoteIds = if (isRevealed) {
                                    revealedNoteIds - note.id
                                } else {
                                    revealedNoteIds + note.id
                                }
                            },
                            onEdit = {
                                if (isDecryptionError) {
                                    Toast.makeText(
                                        context,
                                        "Encrypted payload cannot be unlocked on this device; editing is disabled to preserve stored data.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    noteToEdit = note
                                    showEditDialog = true
                                }
                            },
                            onDelete = {
                                noteToDelete = note
                            },
                            onTogglePin = {
                                viewModel.togglePin(note)
                            },
                            onCopy = {
                                if (isDecryptionError) {
                                    Toast.makeText(
                                        context,
                                        "Encrypted content unavailable to copy.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    copyNoteToClipboard(context, note)
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    // Export Redaction Confirmation Dialog for Credential Notes
    pendingExportMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingExportMode = null },
            title = { Text("Credential Privacy in Export", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your notes include protected Credentials entries. Export with redacted credentials by default so passwords and secrets are not saved or shared outside the app.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            pendingExportMode = null
                            executeExport(mode, true)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_notes_redacted_button")
                    ) {
                        Text("Export Redacted (Recommended)")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingExportMode = null
                            executeExport(mode, false)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_notes_unredacted_button")
                    ) {
                        Text("Include Credentials (Unredacted)")
                    }
                    TextButton(
                        onClick = { pendingExportMode = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Cancel")
                    }
                }
            },
            modifier = Modifier.testTag("notes_export_redaction_dialog")
        )
    }

    // Add / Edit Note Dialog
    if (showEditDialog) {
        NoteEditorDialog(
            note = noteToEdit,
            availableCategories = uiState.availableCategories.filter { it != "All" },
            onDismiss = { showEditDialog = false },
            onSave = { title, content, category, ipHost, isPinned ->
                viewModel.saveNote(
                    id = noteToEdit?.id ?: 0L,
                    title = title,
                    content = content,
                    category = category,
                    ipOrHost = ipHost,
                    isPinned = isPinned
                )
                showEditDialog = false
                Toast.makeText(context, if (noteToEdit == null) "Note saved" else "Note updated", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Delete Confirmation Dialog
    if (noteToDelete != null) {
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Note?") },
            text = { Text("Are you sure you want to delete \"${noteToDelete?.title}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        noteToDelete?.let { viewModel.deleteNote(it) }
                        noteToDelete = null
                        Toast.makeText(context, "Note deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: NetworkNoteEntity,
    isRevealed: Boolean,
    isDecryptionError: Boolean,
    onToggleReveal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit
) {
    val categoryColor = when (note.category.lowercase()) {
        "router / gateway" -> StatusGreen
        "switch" -> StatusBlue
        "vlan" -> StellarTeal
        "credentials" -> StatusAmber
        "rack / patch" -> Color(0xFF8B5CF6)
        else -> MaterialTheme.colorScheme.primary
    }

    val isSensitive = isSensitiveCredentialNote(note)
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("note_card_${note.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        border = if (note.isPinned) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Category Badge + Encrypted Badge + IP + Pin Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = categoryColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = note.category,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = categoryColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (note.isEncrypted || isSensitive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = (if (isDecryptionError) StatusRed else StatusAmber).copy(alpha = 0.16f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isDecryptionError) Icons.Default.Warning else Icons.Default.Lock,
                                    contentDescription = if (isDecryptionError) "Decryption Error" else "AES-256 Encrypted",
                                    tint = if (isDecryptionError) StatusRed else StatusAmber,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isDecryptionError) "Keystore Error" else "Encrypted",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDecryptionError) StatusRed else StatusAmber
                                )
                            }
                        }
                    }
                    if (note.ipOrHost != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = note.ipOrHost,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.testTag("pin_note_button_${note.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (note.isPinned) "Unpin Note" else "Pin Note",
                        tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = note.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            val displayedContent = when {
                isDecryptionError -> NetworkNotesViewModel.DECRYPTION_ERROR_PLACEHOLDER
                isSensitive && !isRevealed -> "••••••••••••  (Hidden — tap eye icon to reveal)"
                else -> note.content
            }

            Text(
                text = displayedContent,
                fontSize = 13.sp,
                color = if (isDecryptionError) StatusRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("note_content_text_${note.id}")
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(4.dp))

            // Footer Row: Timestamp + Action Buttons (full >=48dp Material touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormatter.format(Date(note.modifiedTimestamp)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSensitive && !isDecryptionError) {
                        IconButton(
                            onClick = onToggleReveal,
                            modifier = Modifier.testTag("toggle_reveal_note_${note.id}")
                        ) {
                            Icon(
                                imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isRevealed) "Hide Credentials" else "Reveal Credentials",
                                tint = StatusAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.testTag("copy_note_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Note",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_note_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Note",
                            tint = if (isDecryptionError) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_note_button_${note.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Note",
                            tint = StatusRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorDialog(
    note: NetworkNoteEntity?,
    availableCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, category: String, ipHost: String?, isPinned: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.content ?: "") }
    var category by remember { mutableStateOf(note?.category ?: "General") }
    var ipHost by remember { mutableStateOf(note?.ipOrHost ?: "") }
    val isPinned by remember { mutableStateOf(note?.isPinned ?: false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    val isCredentialsCategory = NetworkNotesViewModel.shouldEncryptCategory(category)
    var revealEditorSecret by remember { mutableStateOf(note == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "New Network Note" else "Edit Network Note", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title *") },
                    placeholder = { Text("e.g. Core Switch VLAN 20") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("note_title_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            availableCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        category = cat
                                        categoryExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = ipHost,
                        onValueChange = { ipHost = it },
                        label = { Text("IP / Host") },
                        placeholder = { Text("192.168.1.1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("note_ip_input")
                    )
                }

                if (isCredentialsCategory) {
                    Surface(
                        color = StatusAmber.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = StatusAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Encrypted on-device with Android Keystore AES-256-GCM and redacted by default on export.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Note Content *") },
                    placeholder = { Text("Port assignments, credentials, gateway details, or field observations...") },
                    minLines = 4,
                    maxLines = 8,
                    visualTransformation = if (isCredentialsCategory && !revealEditorSecret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = if (isCredentialsCategory) {
                        {
                            IconButton(onClick = { revealEditorSecret = !revealEditorSecret }) {
                                Icon(
                                    imageVector = if (revealEditorSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (revealEditorSecret) "Mask Secret" else "Show Secret"
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth().testTag("note_content_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && content.isNotBlank()) {
                        onSave(title, content, category, ipHost, isPinned)
                    }
                },
                enabled = title.isNotBlank() && content.isNotBlank(),
                modifier = Modifier.testTag("save_note_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

internal fun isSensitiveCredentialNote(note: NetworkNoteEntity): Boolean {
    return note.isEncrypted || NetworkNotesViewModel.shouldEncryptCategory(note.category)
}

private fun copyNoteToClipboard(context: Context, note: NetworkNoteEntity) {
    val text = buildString {
        appendLine(note.title)
        if (!note.ipOrHost.isNullOrBlank()) appendLine("IP/Host: ${note.ipOrHost}")
        appendLine("Category: ${note.category}")
        appendLine("---")
        appendLine(note.content)
    }
    TextReportExportManager.copySensitiveToClipboard(
        context = context,
        label = "Network Note - ${note.title}",
        text = text,
        markSensitive = true
    )
    Toast.makeText(
        context,
        if (isSensitiveCredentialNote(note)) "Credential note copied (masked in OS clipboard)" else "Note copied to clipboard",
        Toast.LENGTH_SHORT
    ).show()
}

internal fun buildNetworkNotesTextReport(
    notes: List<NetworkNoteEntity>,
    redactCredentials: Boolean = true,
    decryptionErrorNoteIds: Set<Long> = emptySet()
): String = buildString {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    appendLine("============================================================")
    appendLine("          NETWORK TOOLKIT - FIELD TECHNICIAN NOTES")
    appendLine("============================================================")
    appendLine("Exported        : ${dateFmt.format(Date())}")
    appendLine("Total Notes     : ${notes.size}")
    appendLine("Credential Mode : ${if (redactCredentials) "Redacted (Recommended)" else "Unredacted (User Confirmed)"}")
    appendLine("Device          : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
    appendLine()
    notes.forEachIndexed { idx, note ->
        val isSensitive = isSensitiveCredentialNote(note)
        val isDecryptionError = note.id in decryptionErrorNoteIds
        appendLine("------------------------------------------------------------")
        appendLine("${idx + 1}. ${note.title}${if (note.isPinned) " [PINNED]" else ""}")
        appendLine("   Category     : ${note.category}${if (note.isEncrypted) " [ENCRYPTED AT REST]" else ""}")
        if (!note.ipOrHost.isNullOrBlank()) {
            appendLine("   IP / Host    : ${note.ipOrHost}")
        }
        appendLine("   Updated      : ${dateFmt.format(Date(note.modifiedTimestamp))}")
        appendLine("   Notes        :")
        when {
            isDecryptionError -> appendLine("     [ENCRYPTED CONTENT UNAVAILABLE]")
            redactCredentials && isSensitive -> appendLine("     [CREDENTIAL CONTENT REDACTED]")
            else -> note.content.lines().forEach { line ->
                appendLine("     $line")
            }
        }
        appendLine()
    }
    appendLine("============================================================")
}
