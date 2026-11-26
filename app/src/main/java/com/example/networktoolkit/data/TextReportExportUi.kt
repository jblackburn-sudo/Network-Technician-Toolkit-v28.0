package com.example.networktoolkit.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable, lifecycle-safe controller managing Android Storage Access Framework
 * (ACTION_CREATE_DOCUMENT, MIME "application/rtf") file creation with disk staging &
 * persisted metadata recovery, FileProvider sharing, privacy redaction + verification,
 * explicit one-time export privacy prompts, and granular error diagnostics across Jetpack Compose screens.
 */
@Stable
class SafTextExportController internal constructor(
    private val context: Context,
    private val onRequestSafExport: (
        suggestedFilename: String,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers,
        privacyChoice: WifiReportExportPrivacyChoice
    ) -> Unit,
    private val onRequestPrivacyPrompt: (
        exportDescription: String,
        onConfirmed: (WifiReportExportPrivacyChoice) -> Unit
    ) -> Unit,
    internal val showFailure: (TextExportOutcome.Failure) -> Unit
) {
    /**
     * Prompts the user with an explicit one-time privacy choice:
     * - Option A — Redacted (Recommended, selected by default)
     * - Option B — Include SSID and BSSID (Unredacted), requiring warning acknowledgment + confirmation
     * Applies strictly to this single export operation and never persists as default.
     */
    fun promptPrivacyAndRun(
        exportDescription: String = "report export",
        onConfirmed: (WifiReportExportPrivacyChoice) -> Unit
    ) {
        onRequestPrivacyPrompt(exportDescription, onConfirmed)
    }

    /**
     * Starts an ACTION_CREATE_DOCUMENT ("application/rtf") export flow:
     * 1. Pre-validates & applies selected privacy choice (REDACTED by default).
     * 2. Atomically stages the RTF report in app-private storage + SharedPreferences.
     * 3. Launches native Android file picker with [suggestedFilename] (user may rename/choose folder).
     * 4. Restores the staged report cleanly even across Activity recreation or process restart.
     */
    fun launchSaveTxt(
        suggestedFilename: String,
        rawContent: String,
        identifiers: SensitiveWifiIdentifiers = TextReportExportManager.collectIdentifiers(context),
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ) {
        if (rawContent.isBlank()) {
            showFailure(
                TextReportExportManager.classifyException(
                    IllegalArgumentException("Cannot export an empty report."),
                    operation = "Preparing RTF report for SAF export"
                )
            )
            return
        }

        onRequestSafExport(suggestedFilename, rawContent, identifiers, privacyChoice)
    }

    /**
     * Shares a diagnostic report via FileProvider, applying redacted privacy by default or
     * explicit one-time unredacted export if [privacyChoice] is [WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED].
     */
    fun shareReport(
        rawContent: String,
        suggestedFilename: String,
        subject: String,
        chooserTitle: String = "Share Diagnostic Report",
        identifiers: SensitiveWifiIdentifiers = TextReportExportManager.collectIdentifiers(context),
        isJson: Boolean = false,
        privacyChoice: WifiReportExportPrivacyChoice = WifiReportExportPrivacyChoice.REDACTED
    ) {
        val intentResult = TextReportExportManager.createShareIntentWithPrivacy(
            context = context,
            rawContent = rawContent,
            filename = suggestedFilename,
            subject = subject,
            chooserTitle = chooserTitle,
            identifiers = identifiers,
            isJson = isJson,
            privacyChoice = privacyChoice
        )
        intentResult.onSuccess { chooserIntent ->
            try {
                context.startActivity(chooserIntent)
            } catch (t: Throwable) {
                showFailure(
                    TextReportExportManager.classifyException(
                        t,
                        operation = "Launching Android share chooser"
                    )
                )
            }
        }.onFailure { err ->
            showFailure(
                TextReportExportManager.classifyException(
                    err,
                    operation = if (isJson) "Preparing JSON report for sharing" else "Preparing RTF report for sharing"
                )
            )
        }
    }

    fun reportFailure(throwable: Throwable, operation: String) {
        showFailure(TextReportExportManager.classifyException(throwable, operation))
    }
}

private data class PendingPrivacyPrompt(
    val exportDescription: String,
    val onConfirmed: (WifiReportExportPrivacyChoice) -> Unit
)

@Composable
fun rememberSafTextExportController(
    screenKey: String = "global",
    onSuccess: ((TextExportOutcome.Success) -> Unit)? = null,
    onCancelled: (() -> Unit)? = null
): SafTextExportController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnCancelled by rememberUpdatedState(onCancelled)

    // Persisted across Activity configuration changes and process death alongside disk staging metadata
    var pendingExportId by rememberSaveable(screenKey) { mutableStateOf<String?>(null) }
    var pendingSuggestedFilename by rememberSaveable(screenKey) { mutableStateOf("Network_Toolkit_Report.rtf") }
    var activeFailure by remember { mutableStateOf<TextExportOutcome.Failure?>(null) }
    var activePrivacyPrompt by remember { mutableStateOf<PendingPrivacyPrompt?>(null) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(TextReportExportManager.MIME_RTF)
    ) { uri ->
        val restoredExportId = pendingExportId
        val fallbackName = pendingSuggestedFilename
        pendingExportId = null

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (uri == null) {
                    TextReportExportManager.handleSafPickerCancelled(
                        context = context,
                        explicitExportId = restoredExportId,
                        screenKey = screenKey
                    )
                } else {
                    TextReportExportManager.exportStagedReportToUri(
                        context = context,
                        explicitExportId = restoredExportId,
                        uri = uri,
                        fallbackFilename = fallbackName,
                        screenKey = screenKey
                    )
                }
            }

            when (outcome) {
                is TextExportOutcome.Success -> {
                    val privacyTag = if (outcome.isWifiUnredacted) {
                        "unredacted SSID/BSSID for this export"
                    } else {
                        "SSID/BSSID redacted"
                    }
                    showMainToast(
                        context,
                        "Saved ${outcome.displayName} (${outcome.bytesWritten} bytes, $privacyTag)",
                        Toast.LENGTH_LONG
                    )
                    currentOnSuccess?.invoke(outcome)
                }
                is TextExportOutcome.Cancelled -> {
                    showMainToast(context, "Export cancelled — no file saved", Toast.LENGTH_SHORT)
                    currentOnCancelled?.invoke()
                }
                is TextExportOutcome.AlreadyProcessed -> {
                    // Duplicate ActivityResult delivery safely ignored without rewriting or erroring
                }
                is TextExportOutcome.Failure -> {
                    activeFailure = outcome
                }
            }
        }
    }

    val controller = remember(context, screenKey) {
        SafTextExportController(
            context = context,
            onRequestSafExport = { suggestedFilename, rawContent, identifiers, privacyChoice ->
                scope.launch {
                    val stageResult = withContext(Dispatchers.IO) {
                        TextReportExportManager.stageTextReportForExport(
                            context = context,
                            rawContent = rawContent,
                            identifiers = identifiers,
                            suggestedFilename = suggestedFilename,
                            screenKey = screenKey,
                            privacyChoice = privacyChoice
                        )
                    }

                    when (stageResult) {
                        is StageExportResult.Failed -> {
                            activeFailure = stageResult.failure
                        }
                        is StageExportResult.Ready -> {
                            val staged = stageResult.staged
                            pendingExportId = staged.exportId
                            pendingSuggestedFilename = staged.suggestedFilename
                            try {
                                createDocLauncher.launch(staged.suggestedFilename)
                            } catch (e: ActivityNotFoundException) {
                                pendingExportId = null
                                scope.launch(Dispatchers.IO) {
                                    TextReportExportManager.handleSafPickerCancelled(
                                        context = context,
                                        explicitExportId = staged.exportId,
                                        screenKey = screenKey
                                    )
                                }
                                activeFailure = TextReportExportManager.classifyException(
                                    e,
                                    operation = "Opening Android Storage Access Framework file picker"
                                )
                            } catch (t: Throwable) {
                                pendingExportId = null
                                scope.launch(Dispatchers.IO) {
                                    TextReportExportManager.handleSafPickerCancelled(
                                        context = context,
                                        explicitExportId = staged.exportId,
                                        screenKey = screenKey
                                    )
                                }
                                activeFailure = TextReportExportManager.classifyException(
                                    t,
                                    operation = "Opening Android Storage Access Framework file picker"
                                )
                            }
                        }
                    }
                }
            },
            onRequestPrivacyPrompt = { exportDescription, onConfirmed ->
                activePrivacyPrompt = PendingPrivacyPrompt(exportDescription, onConfirmed)
            },
            showFailure = { failure ->
                activeFailure = failure
            }
        )
    }

    activePrivacyPrompt?.let { prompt ->
        ExportPrivacySelectionFlow(
            exportDescription = prompt.exportDescription,
            onDismiss = { activePrivacyPrompt = null },
            onConfirmed = { choice ->
                activePrivacyPrompt = null
                prompt.onConfirmed(choice)
            }
        )
    }

    activeFailure?.let { failure ->
        ExportDiagnosticErrorDialog(
            failure = failure,
            onDismiss = { activeFailure = null }
        )
    }

    return controller
}

@Composable
fun ExportPrivacySelectionFlow(
    exportDescription: String,
    onDismiss: () -> Unit,
    onConfirmed: (WifiReportExportPrivacyChoice) -> Unit
) {
    // Always defaults to REDACTED for every single export invocation and never persists.
    var selectedChoice by remember(exportDescription) {
        mutableStateOf(WifiReportExportPrivacyChoice.REDACTED)
    }
    var showUnredactedConfirmation by remember(exportDescription) {
        mutableStateOf(false)
    }

    if (!showUnredactedConfirmation) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("export_privacy_choice_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.PrivacyTip,
                    contentDescription = "Export Privacy Settings",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Export Privacy Options",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose how Wi-Fi SSID and BSSID are handled for this $exportDescription. On-screen display toggles never expose identifiers in exports automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val redactedSelected = selectedChoice == WifiReportExportPrivacyChoice.REDACTED
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .clickable { selectedChoice = WifiReportExportPrivacyChoice.REDACTED }
                            .testTag("export_privacy_option_redacted"),
                        shape = RoundedCornerShape(10.dp),
                        color = if (redactedSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            width = if (redactedSelected) 1.5.dp else 1.dp,
                            color = if (redactedSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = redactedSelected,
                                onClick = { selectedChoice = WifiReportExportPrivacyChoice.REDACTED }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Option A — Redacted (Recommended)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Redacts SSID and BSSID from the exported report.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    val unredactedSelected = selectedChoice == WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .clickable { selectedChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED }
                            .testTag("export_privacy_option_unredacted"),
                        shape = RoundedCornerShape(10.dp),
                        color = if (unredactedSelected) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            width = if (unredactedSelected) 1.5.dp else 1.dp,
                            color = if (unredactedSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = unredactedSelected,
                                onClick = { selectedChoice = WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Option B — Include SSID and BSSID (Unredacted)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Includes readable SSID and BSSID for this single export only.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (unredactedSelected) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export_privacy_unredacted_warning"),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Unredacted Export Warning",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "The exported report will contain readable, unencrypted Wi-Fi network identification (SSID and BSSID). Next step requires confirmation.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedChoice == WifiReportExportPrivacyChoice.REDACTED) {
                            onConfirmed(WifiReportExportPrivacyChoice.REDACTED)
                        } else {
                            showUnredactedConfirmation = true
                        }
                    },
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("export_privacy_continue_button")
                ) {
                    Text(
                        if (selectedChoice == WifiReportExportPrivacyChoice.REDACTED) {
                            "Export Redacted"
                        } else {
                            "Review Warning"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("export_privacy_cancel_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("export_unredacted_confirm_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Confirm Unredacted Export",
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Confirm Unredacted Export",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Warning: This exported report will contain readable, unencrypted Wi-Fi network identification information (SSID and BSSID).",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "This applies only to the current export operation and will reset to Redacted (Recommended) afterward. Existing credential encryption and note safeguards remain unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmed(WifiReportExportPrivacyChoice.UNREDACTED_CONFIRMED)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("export_unredacted_confirm_button")
                ) {
                    Text("Confirm & Export Unredacted")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("export_unredacted_cancel_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ExportDiagnosticErrorDialog(
    failure: TextExportOutcome.Failure,
    onDismiss: () -> Unit
) {
    val isPrivacy = failure.category == ExportErrorCategory.PRIVACY_VERIFICATION_FAILED
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isPrivacy) Icons.Default.PrivacyTip else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = failure.category.title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = failure.userMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Operation: ${failure.operation}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Exception: ${failure.exceptionName}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (failure.exceptionMessage.isNotBlank()) {
                            Text(
                                text = "Details: ${failure.exceptionMessage}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("export_error_dialog_dismiss_button")
            ) {
                Text("Dismiss")
            }
        }
    )
}

private fun showMainToast(context: Context, message: String, duration: Int) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, message, duration).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, duration).show()
        }
    }
}
