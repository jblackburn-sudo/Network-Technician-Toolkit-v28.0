package com.example.networktoolkit.learning

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningGuideScreen(
    viewModel: LearningGuideViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: LearningGuideTab = LearningGuideTab.WELCOME,
    initialToolId: String? = null,
    initialGlossaryId: String? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var selectedToolId by rememberSaveable { mutableStateOf(initialToolId) }
    var highlightedGlossaryId by rememberSaveable { mutableStateOf(initialGlossaryId) }
    var selectedGuideCategory by rememberSaveable { mutableStateOf<ToolGuideCategory?>(null) }

    val openWalkthrough: (String) -> Unit = { toolId ->
        selectedToolId = toolId
        selectedTab = LearningGuideTab.TOOL_GUIDES
    }

    val openGlossaryTerm: (String) -> Unit = { glossaryId ->
        highlightedGlossaryId = glossaryId
        selectedTab = LearningGuideTab.GLOSSARY
    }

    // Ensure hardware Back leaves tool walkthrough back to guides list before closing screen
    BackHandler(enabled = selectedToolId != null) {
        selectedToolId = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            val activeTool = selectedToolId?.let { LearningGuideCatalog.findToolById(it) }
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedTab == LearningGuideTab.TOOL_GUIDES && activeTool != null) {
                                activeTool.title
                            } else {
                                "Technician Learning Guide"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${uiState.completedCount}/${uiState.totalToolCount} guides completed • Profile: ${uiState.overallTier.title}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedTab == LearningGuideTab.TOOL_GUIDES && selectedToolId != null) {
                                selectedToolId = null
                            } else {
                                onBackClick()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("learning_guide_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScrollableTabRow(
                selectedTabIndex = LearningGuideTab.entries.indexOf(selectedTab),
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                LearningGuideTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            if (tab != LearningGuideTab.TOOL_GUIDES) {
                                selectedToolId = null
                            }
                        },
                        text = {
                            Text(
                                text = tab.label,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        modifier = Modifier.testTag("learning_tab_${tab.name.lowercase()}")
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                when (selectedTab) {
                    LearningGuideTab.WELCOME -> {
                        LearningProfileAndWelcomeTab(
                            uiState = uiState,
                            onStartAssessment = { selectedTab = LearningGuideTab.ASSESSMENT },
                            onOpenToolWalkthrough = openWalkthrough,
                            onOpenGlossary = {
                                highlightedGlossaryId = null
                                selectedTab = LearningGuideTab.GLOSSARY
                            },
                            onOpenReference = { selectedTab = LearningGuideTab.REFERENCE },
                            onResetProgress = {
                                viewModel.resetLearningProgress()
                                Toast.makeText(context, "Learning completion progress cleared.", Toast.LENGTH_SHORT).show()
                            },
                            onRestartOnboarding = {
                                viewModel.restartOnboarding()
                                Toast.makeText(
                                    context,
                                    "First-launch welcome prompt will show next time Home opens.",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onSkipGuide = {
                                viewModel.skipOnboarding()
                                onBackClick()
                            }
                        )
                    }

                    LearningGuideTab.ASSESSMENT -> {
                        ProficiencyAssessmentTab(
                            uiState = uiState,
                            onRateTool = viewModel::setProficiency,
                            onClearRatings = {
                                viewModel.clearAllRatings()
                                Toast.makeText(context, "All familiarity ratings cleared.", Toast.LENGTH_SHORT).show()
                            },
                            onFinishAssessment = {
                                viewModel.completeOnboarding()
                                selectedTab = LearningGuideTab.WELCOME
                            }
                        )
                    }

                    LearningGuideTab.TOOL_GUIDES -> {
                        val tool = selectedToolId?.let { LearningGuideCatalog.findToolById(it) }
                        if (tool != null) {
                            ToolWalkthroughDetailView(
                                tool = tool,
                                rating = uiState.ratings[tool.id],
                                overallTier = uiState.overallTier,
                                isCompleted = uiState.completedToolIds.contains(tool.id),
                                onBackToGuidesList = { selectedToolId = null },
                                onToggleCompleted = { viewModel.toggleWalkthroughCompleted(tool.id) },
                                onRateTool = { level -> viewModel.setProficiency(tool.id, level) },
                                onOpenGlossaryTerm = openGlossaryTerm
                            )
                        } else {
                            ToolGuidesListView(
                                tools = uiState.recommendedTools,
                                ratings = uiState.ratings,
                                completedToolIds = uiState.completedToolIds,
                                selectedCategory = selectedGuideCategory,
                                onSelectCategory = { selectedGuideCategory = it },
                                onOpenWalkthrough = openWalkthrough
                            )
                        }
                    }

                    LearningGuideTab.GLOSSARY -> {
                        InteractiveGlossaryTab(
                            effectiveTier = uiState.effectiveGlossaryTier,
                            tierOverride = uiState.glossaryTierOverride,
                            highlightedGlossaryId = highlightedGlossaryId,
                            onSelectTierOverride = viewModel::setGlossaryTierOverride
                        )
                    }

                    LearningGuideTab.REFERENCE -> {
                        TroubleshootingAndReferenceTab(
                            onOpenToolWalkthrough = openWalkthrough
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningProfileAndWelcomeTab(
    uiState: LearningGuideUiState,
    onStartAssessment: () -> Unit,
    onOpenToolWalkthrough: (String) -> Unit,
    onOpenGlossary: () -> Unit,
    onOpenReference: () -> Unit,
    onResetProgress: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onSkipGuide: () -> Unit
) {
    val priorityTools = remember(uiState.ratings) {
        LearningGuideViewModel.priorityToolsToLearn(LearningGuideCatalog.tools, uiState.ratings)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Welcome Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.School,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Welcome to the Technician Learning Guide",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Learn every networking tool, metric, and troubleshooting workflow built into Network Technician Toolkit—from plain-English fundamentals to advanced RF and packet analysis.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartAssessment,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("profile_customize_ratings_button")
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.ratedCount == 0) "Rate My Skills" else "Change Ratings")
                        }
                        OutlinedButton(
                            onClick = onOpenGlossary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("profile_open_glossary_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Glossary")
                        }
                    }
                }
            }
        }

        // My Learning Profile & Learning Progress Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "My Learning Profile & Progress",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Adaptive Reading Depth: ${uiState.overallTier.score}/5 — ${uiState.overallTier.title} (${uiState.overallTier.focusTag})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Rated Tools: ${uiState.ratedCount} of ${uiState.totalToolCount} • Walkthroughs Completed: ${uiState.completedCount} of ${uiState.totalToolCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { uiState.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onResetProgress,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_learning_progress_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset Progress")
                        }
                        OutlinedButton(
                            onClick = onRestartOnboarding,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("restart_onboarding_button")
                        ) {
                            Text("Restart Onboarding")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onOpenReference,
                            modifier = Modifier.testTag("open_playbook_shortcut_button")
                        ) {
                            Text("Troubleshooting Playbook & Ref")
                        }
                        TextButton(
                            onClick = onSkipGuide,
                            modifier = Modifier.testTag("skip_learning_guide_button")
                        ) {
                            Text("Skip Guide & Return")
                        }
                    }
                }
            }
        }

        // Recommended Learning Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (priorityTools.isNotEmpty()) {
                        "Recommended Learning (Prioritized for Your Ratings)"
                    } else {
                        "Recommended Learning Path"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (priorityTools.isNotEmpty()) {
                        "Tools rated 1 (Beginner) or 2 (Novice) appear first with step-by-step explanations."
                    } else {
                        "Rate any tools as 1 or 2 in Assessment to build a tailored priority queue, or pick any guide below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val displayRecommended = if (priorityTools.isNotEmpty()) {
            priorityTools
        } else {
            uiState.recommendedTools.take(6)
        }

        items(displayRecommended, key = { "rec_${it.id}" }) { tool ->
            ToolSummaryCard(
                tool = tool,
                rating = uiState.ratings[tool.id],
                isCompleted = uiState.completedToolIds.contains(tool.id),
                onClick = { onOpenToolWalkthrough(tool.id) },
                testTag = "recommended_tool_card_${tool.id}"
            )
        }
    }
}

@Composable
private fun ToolGuidesListView(
    tools: List<ToolGuideContent>,
    ratings: Map<String, ProficiencyLevel>,
    completedToolIds: Set<String>,
    selectedCategory: ToolGuideCategory?,
    onSelectCategory: (ToolGuideCategory?) -> Unit,
    onOpenWalkthrough: (String) -> Unit
) {
    val filtered = remember(tools, selectedCategory) {
        if (selectedCategory == null) tools else tools.filter { it.category == selectedCategory }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("tool_guides_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("All Tools (${tools.size})") },
                    modifier = Modifier.testTag("guide_filter_all")
                )
                ToolGuideCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onSelectCategory(cat) },
                        label = { Text(cat.label) },
                        modifier = Modifier.testTag("guide_filter_${cat.name.lowercase()}")
                    )
                }
            }
        }

        items(filtered, key = { "guide_${it.id}" }) { tool ->
            ToolSummaryCard(
                tool = tool,
                rating = ratings[tool.id],
                isCompleted = completedToolIds.contains(tool.id),
                onClick = { onOpenWalkthrough(tool.id) },
                testTag = "guide_tool_card_${tool.id}"
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolWalkthroughDetailView(
    tool: ToolGuideContent,
    rating: ProficiencyLevel?,
    overallTier: ProficiencyLevel,
    isCompleted: Boolean,
    onBackToGuidesList: () -> Unit,
    onToggleCompleted: () -> Unit,
    onRateTool: (ProficiencyLevel?) -> Unit,
    onOpenGlossaryTerm: (String) -> Unit
) {
    val effectiveLevel = rating ?: overallTier
    val adaptiveCalloutTitle = when (effectiveLevel) {
        ProficiencyLevel.BEGINNER, ProficiencyLevel.NOVICE -> "Guided Beginner Focus (${effectiveLevel.title})"
        ProficiencyLevel.INTERMEDIATE -> "Result Interpretation Focus (${effectiveLevel.title})"
        ProficiencyLevel.ADVANCED, ProficiencyLevel.EXPERIENCED -> "Concise Technical Reference (${effectiveLevel.title})"
    }
    val adaptiveCalloutBody = when (effectiveLevel) {
        ProficiencyLevel.BEGINNER, ProficiencyLevel.NOVICE -> tool.beginnerTip
        ProficiencyLevel.INTERMEDIATE -> tool.intermediateGuidance
        ProficiencyLevel.ADVANCED, ProficiencyLevel.EXPERIENCED -> tool.advancedReference
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("walkthrough_detail_lazy_list"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBackToGuidesList,
                    modifier = Modifier.testTag("walkthrough_all_guides_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("All Tool Guides")
                }

                AssistChip(
                    onClick = onToggleCompleted,
                    label = { Text(if (isCompleted) "Completed ✓" else "Mark Complete") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = if (isCompleted) "Walkthrough completed" else "Mark walkthrough completed",
                            tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.testTag("walkthrough_toggle_complete_button")
                )
            }
        }

        // Header & Where to find
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = tool.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tool.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ) {
                        Text(
                            text = "Where to find: ${tool.whereToFind}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = adaptiveCalloutTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = adaptiveCalloutBody,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Quick inline familiarity adjustment
                    Text(
                        text = "Your familiarity with this tool:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    ProficiencyRatingRow(
                        toolId = tool.id,
                        currentRating = rating,
                        onSelect = onRateTool
                    )
                }
            }
        }

        val sectionResetKey = "${tool.id}_${effectiveLevel.score}"

        // A. What This Tool Does
        item {
            ExpandableWalkthroughSection(
                letter = "A",
                title = "What This Tool Does",
                initiallyExpanded = true,
                resetKey = sectionResetKey
            ) {
                Text(text = tool.whatItDoes, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // B. When To Use It
        item {
            ExpandableWalkthroughSection(
                letter = "B",
                title = "When To Use It",
                initiallyExpanded = effectiveLevel.score <= 3,
                resetKey = sectionResetKey
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tool.whenToUse.forEach { bullet ->
                        BulletLine(bullet)
                    }
                }
            }
        }

        // C. How To Use It
        item {
            ExpandableWalkthroughSection(
                letter = "C",
                title = "How To Use It (Step-by-Step)",
                initiallyExpanded = effectiveLevel.score <= 2,
                resetKey = sectionResetKey
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tool.howToUseSteps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. $step",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // D. Understanding The Results
        item {
            ExpandableWalkthroughSection(
                letter = "D",
                title = "Understanding The Results",
                initiallyExpanded = true,
                resetKey = sectionResetKey
            ) {
                KeyValueBullets(tool.understandingResults)
            }
        }

        // E. What The Results May Indicate
        item {
            ExpandableWalkthroughSection(
                letter = "E",
                title = "What The Results May Indicate",
                initiallyExpanded = effectiveLevel.score >= 2,
                resetKey = sectionResetKey
            ) {
                KeyValueBullets(tool.whatResultsIndicate)
            }
        }

        // F. Common Troubleshooting Scenarios
        item {
            ExpandableWalkthroughSection(
                letter = "F",
                title = "Common Troubleshooting Scenarios",
                initiallyExpanded = effectiveLevel.score >= 3,
                resetKey = sectionResetKey
            ) {
                KeyValueBullets(tool.troubleshootingScenarios)
            }
        }

        // G. Limitations & Android Restrictions
        item {
            ExpandableWalkthroughSection(
                letter = "G",
                title = "Limitations & Platform Notes",
                initiallyExpanded = effectiveLevel.score >= 4,
                resetKey = sectionResetKey
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tool.limitations.forEach { limit ->
                        BulletLine(limit)
                    }
                }
            }
        }

        // H. Additional Information & Related Glossary Terms
        item {
            ExpandableWalkthroughSection(
                letter = "H",
                title = "Additional Information & Glossary Links",
                initiallyExpanded = true,
                resetKey = sectionResetKey
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Guidance by tier:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("• Beginner Tip: ${tool.beginnerTip}", style = MaterialTheme.typography.bodySmall)
                    Text("• Intermediate Triage: ${tool.intermediateGuidance}", style = MaterialTheme.typography.bodySmall)
                    Text("• Advanced Reference: ${tool.advancedReference}", style = MaterialTheme.typography.bodySmall)

                    if (tool.relatedGlossaryIds.isNotEmpty()) {
                        Text(
                            text = "Tap a term to inspect its Glossary entry:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tool.relatedGlossaryIds.forEach { termId ->
                                val entry = LearningGlossaryCatalog.findById(termId)
                                if (entry != null) {
                                    AssistChip(
                                        onClick = { onOpenGlossaryTerm(entry.id) },
                                        label = { Text(entry.term) },
                                        modifier = Modifier.testTag("related_glossary_${entry.id}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onToggleCompleted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("walkthrough_bottom_complete_button")
            ) {
                Text(if (isCompleted) "Completed — Revisit Later or Unmark" else "Mark This Walkthrough Completed")
            }
        }
    }
}

@Composable
private fun ExpandableWalkthroughSection(
    letter: String,
    title: String,
    initiallyExpanded: Boolean,
    resetKey: String,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(resetKey, letter, title) { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = letter,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse section" else "Expand section"
                )
            }
            if (expanded) {
                HorizontalDivider()
                Box(modifier = Modifier.padding(14.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun KeyValueBullets(pairs: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pairs.forEach { (key, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ToolSummaryCard(
    tool: ToolGuideContent,
    rating: ProficiencyLevel?,
    isCompleted: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val isPriority = rating == ProficiencyLevel.BEGINNER || rating == ProficiencyLevel.NOVICE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (isPriority) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = tool.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = tool.category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isPriority) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = rating?.let { "Level ${it.score}/5 (${it.title})" } ?: "Unrated",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isPriority) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
