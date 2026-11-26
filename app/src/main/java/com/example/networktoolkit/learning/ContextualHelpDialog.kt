package com.example.networktoolkit.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable TopAppBar Help action that displays concise, tool-specific guidance tailored
 * to the technician's local proficiency setting and links directly into the full interactive
 * walkthrough or networking glossary.
 */
@Composable
fun ToolContextualHelpAction(
    toolId: String,
    modifier: Modifier = Modifier,
    testTag: String = "${toolId}_help_button"
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showDialog = true },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = "Tool Help & Learning Guide"
        )
    }

    if (showDialog) {
        ToolContextualHelpDialog(
            toolId = toolId,
            onDismiss = { showDialog = false },
            onOpenFullWalkthrough = {
                showDialog = false
                context.startActivity(
                    LearningGuideActivity.createIntent(
                        context = context,
                        toolId = toolId
                    )
                )
            },
            onOpenGlossary = {
                showDialog = false
                context.startActivity(
                    LearningGuideActivity.createTabIntent(
                        context = context,
                        tab = LearningGuideTab.GLOSSARY
                    )
                )
            }
        )
    }
}

@Composable
fun ToolContextualHelpDialog(
    toolId: String,
    onDismiss: () -> Unit,
    onOpenFullWalkthrough: () -> Unit,
    onOpenGlossary: () -> Unit
) {
    val context = LocalContext.current
    val guide = remember(toolId) {
        LearningGuideCatalog.findToolById(toolId) ?: LearningGuideCatalog.tools.first()
    }
    val rating = remember(toolId) {
        LearningGuidePreferences.getToolProficiency(context, guide.id)
    }
    val adaptiveNote = remember(guide, rating) {
        when (rating) {
            ProficiencyLevel.BEGINNER, ProficiencyLevel.NOVICE -> "Beginner Tip: ${guide.beginnerTip}"
            ProficiencyLevel.INTERMEDIATE -> "Interpretation Tip: ${guide.intermediateGuidance}"
            ProficiencyLevel.ADVANCED, ProficiencyLevel.EXPERIENCED -> "Technical Reference: ${guide.advancedReference}"
            null -> "Quick Tip: ${guide.beginnerTip}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = guide.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = rating?.let { "Your familiarity: ${it.score}/5 (${it.title})" }
                            ?: "Quick Field Reference",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = guide.whatItDoes,
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = adaptiveNote,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Text(
                    text = "Key Concepts",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                guide.quickHelpBullets.forEach { bullet ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "•",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onOpenFullWalkthrough,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contextual_help_full_walkthrough_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.School,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Full Walkthrough")
                    }
                    OutlinedButton(
                        onClick = onOpenGlossary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contextual_help_glossary_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Networking Glossary")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("contextual_help_close_button")
            ) {
                Text("Close")
            }
        }
    )
}
