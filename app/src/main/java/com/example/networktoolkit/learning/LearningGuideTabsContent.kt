package com.example.networktoolkit.learning

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ProficiencyAssessmentTab(
    uiState: LearningGuideUiState,
    onRateTool: (String, ProficiencyLevel?) -> Unit,
    onClearRatings: () -> Unit,
    onFinishAssessment: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("assessment_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Customize Your Familiarity Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Rate how comfortable you feel with any tool (1–5) or leave/skip tools anytime. Tools rated 1 or 2 are prioritized in Recommended Learning, and explanations adapt across all guides and glossary entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    ProficiencyLevel.entries.forEach { lvl ->
                        Text(
                            text = "${lvl.score} — ${lvl.title}: \"${lvl.description}\"",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onFinishAssessment,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("assessment_save_profile_button")
                        ) {
                            Text("Save & View My Guide")
                        }
                        OutlinedButton(
                            onClick = onClearRatings,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("assessment_clear_ratings_button")
                        ) {
                            Text("Skip / Clear All")
                        }
                    }
                }
            }
        }

        items(LearningGuideCatalog.tools, key = { "assess_${it.id}" }) { tool ->
            val current = uiState.ratings[tool.id]
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tool.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tool.category.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (current != null) {
                            TextButton(
                                onClick = { onRateTool(tool.id, null) },
                                modifier = Modifier.testTag("assess_skip_${tool.id}")
                            ) {
                                Text("Skip")
                            }
                        }
                    }

                    ProficiencyRatingRow(
                        toolId = tool.id,
                        currentRating = current,
                        onSelect = { lvl -> onRateTool(tool.id, lvl) }
                    )

                    Text(
                        text = current?.let { "${it.score} (${it.title}): ${it.description}" }
                            ?: "Skipped / No rating set — tap 1 to 5 anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProficiencyRatingRow(
    toolId: String,
    currentRating: ProficiencyLevel?,
    onSelect: (ProficiencyLevel?) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ProficiencyLevel.entries.forEach { lvl ->
            val selected = currentRating == lvl
            FilterChip(
                selected = selected,
                onClick = { onSelect(if (selected) null else lvl) },
                label = { Text("${lvl.score} ${lvl.title}") },
                modifier = Modifier.testTag("rate_${toolId}_${lvl.score}")
            )
        }
    }
}

@Composable
internal fun InteractiveGlossaryTab(
    effectiveTier: ProficiencyLevel,
    tierOverride: ProficiencyLevel?,
    highlightedGlossaryId: String?,
    onSelectTierOverride: (ProficiencyLevel?) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<GlossaryCategory?>(null) }

    val filteredEntries = remember(searchQuery, selectedCategory, highlightedGlossaryId) {
        val q = searchQuery.trim().lowercase()
        LearningGlossaryCatalog.entries.filter { entry ->
            val matchesCat = selectedCategory == null || entry.category == selectedCategory
            val matchesQuery = q.isEmpty() ||
                entry.term.lowercase().contains(q) ||
                (entry.fullName?.lowercase()?.contains(q) == true) ||
                entry.plainEnglishDefinition.lowercase().contains(q) ||
                entry.technicalExplanation.lowercase().contains(q) ||
                entry.appExample.lowercase().contains(q)
            matchesCat && matchesQuery
        }.let { list ->
            if (highlightedGlossaryId != null && q.isEmpty()) {
                list.sortedByDescending { it.id == highlightedGlossaryId }
            } else {
                list
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("glossary_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ${LearningGlossaryCatalog.entries.size} networking terms...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("glossary_search_input")
            )
        }

        // Category Filter Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All (${LearningGlossaryCatalog.entries.size})") },
                    modifier = Modifier.testTag("glossary_cat_all")
                )
                GlossaryCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.label) },
                        modifier = Modifier.testTag("glossary_cat_${cat.name.lowercase()}")
                    )
                }
            }
        }

        // Reading Depth Switcher
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Explanation Depth (${if (tierOverride == null) "Auto: ${effectiveTier.title}" else tierOverride.title})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = tierOverride == null,
                        onClick = { onSelectTierOverride(null) },
                        label = { Text("Auto") },
                        modifier = Modifier.testTag("glossary_depth_auto")
                    )
                    FilterChip(
                        selected = tierOverride == ProficiencyLevel.BEGINNER,
                        onClick = { onSelectTierOverride(ProficiencyLevel.BEGINNER) },
                        label = { Text("Beginner Plain-English") },
                        modifier = Modifier.testTag("glossary_depth_beginner")
                    )
                    FilterChip(
                        selected = tierOverride == ProficiencyLevel.INTERMEDIATE,
                        onClick = { onSelectTierOverride(ProficiencyLevel.INTERMEDIATE) },
                        label = { Text("Technical") },
                        modifier = Modifier.testTag("glossary_depth_technical")
                    )
                    FilterChip(
                        selected = tierOverride == ProficiencyLevel.EXPERIENCED,
                        onClick = { onSelectTierOverride(ProficiencyLevel.EXPERIENCED) },
                        label = { Text("Concise Reference") },
                        modifier = Modifier.testTag("glossary_depth_reference")
                    )
                }
            }
        }

        if (filteredEntries.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "No glossary terms match \"$searchQuery\". Try clearing search or choosing All categories.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(filteredEntries, key = { "glossary_${it.id}" }) { entry ->
                ExpandableGlossaryCard(
                    entry = entry,
                    effectiveTier = effectiveTier,
                    initiallyExpanded = entry.id == highlightedGlossaryId
                )
            }
        }
    }
}

@Composable
private fun ExpandableGlossaryCard(
    entry: GlossaryEntry,
    effectiveTier: ProficiencyLevel,
    initiallyExpanded: Boolean
) {
    var expanded by rememberSaveable(entry.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val primarySummary = when (effectiveTier) {
        ProficiencyLevel.BEGINNER, ProficiencyLevel.NOVICE -> entry.plainEnglishDefinition
        ProficiencyLevel.INTERMEDIATE -> entry.technicalExplanation
        ProficiencyLevel.ADVANCED, ProficiencyLevel.EXPERIENCED -> entry.conciseReference
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("glossary_card_${entry.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (initiallyExpanded) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(14.dp)
                    .testTag("glossary_toggle_${entry.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.term,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = entry.category.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (!entry.fullName.isNullOrBlank()) {
                        Text(
                            text = entry.fullName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = primarySummary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse glossary term" else "Expand glossary term"
                )
            }

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlossaryDetailBlock("Plain-English Explanation", entry.plainEnglishDefinition)
                    GlossaryDetailBlock("Technical Details", entry.technicalExplanation)
                    GlossaryDetailBlock("Where You See It in This App", entry.appExample)
                    GlossaryDetailBlock("Why It Matters in the Field", entry.whyItMatters)
                    GlossaryDetailBlock("Concise Reference", entry.conciseReference)
                }
            }
        }
    }
}

@Composable
private fun GlossaryDetailBlock(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TroubleshootingAndReferenceTab(
    onOpenToolWalkthrough: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Troubleshooting Basics (Field Playbooks)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Structured diagnostic workflows pairing common customer symptoms with the exact tools to run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(LearningGuideCatalog.troubleshootingPlaybook, key = { it.id }) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = item.symptom,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    item.stepByStepChecks.forEach { step ->
                        Text(text = step, style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Watch Out: ${item.rookiePitfall}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item.recommendedToolIds.forEach { toolId ->
                            val guide = LearningGuideCatalog.findToolById(toolId)
                            if (guide != null) {
                                AssistChip(
                                    onClick = { onOpenToolWalkthrough(guide.id) },
                                    label = { Text("Guide: ${guide.title}") },
                                    modifier = Modifier.testTag("playbook_tool_${item.id}_${guide.id}")
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Advanced Reference & RFC Benchmarks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Quick field lookup tables for experienced technicians and complex escalations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(LearningGuideCatalog.advancedReferences, key = { it.id }) { ref ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = ref.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = ref.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    ref.bulletPoints.forEach { point ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(text = point, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
