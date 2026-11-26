package com.example.networktoolkit.learning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LearningGuideTab(val label: String) {
    WELCOME("Profile"),
    ASSESSMENT("Assessment"),
    TOOL_GUIDES("Tool Guides"),
    GLOSSARY("Glossary"),
    REFERENCE("Playbook & Ref")
}

data class LearningGuideUiState(
    val onboardingStatus: OnboardingStatus = OnboardingStatus.NOT_STARTED,
    val ratings: Map<String, ProficiencyLevel> = emptyMap(),
    val completedToolIds: Set<String> = emptySet(),
    val recommendedTools: List<ToolGuideContent> = LearningGuideCatalog.tools,
    val overallTier: ProficiencyLevel = ProficiencyLevel.INTERMEDIATE,
    val glossaryTierOverride: ProficiencyLevel? = null,
    val ratedCount: Int = 0,
    val totalToolCount: Int = LearningGuideCatalog.tools.size,
    val completedCount: Int = 0
) {
    val progressFraction: Float
        get() = if (totalToolCount == 0) 0f else completedCount.toFloat() / totalToolCount.toFloat()

    val effectiveGlossaryTier: ProficiencyLevel
        get() = glossaryTierOverride ?: overallTier
}

class LearningGuideViewModel(application: Application) : AndroidViewModel(application) {

    private val toolIds: List<String> = LearningGuideCatalog.tools.map { it.id }
    private val _uiState = MutableStateFlow(buildSnapshot())
    val uiState: StateFlow<LearningGuideUiState> = _uiState.asStateFlow()

    private fun buildSnapshot(): LearningGuideUiState {
        val ctx = getApplication<Application>()
        val onboarding = LearningGuidePreferences.getOnboardingStatus(ctx)
        val ratings = LearningGuidePreferences.getAllRatings(ctx, toolIds)
        val completed = LearningGuidePreferences.getCompletedToolIds(ctx, toolIds)
        val overrideTier = LearningGuidePreferences.getGlossaryTierOverride(ctx)

        val recommended = sortRecommendedTools(LearningGuideCatalog.tools, ratings)
        val avgScore = if (ratings.isEmpty()) {
            2 // Friendly default intermediate/novice-balanced starting point if user skipped assessment
        } else {
            kotlin.math.round(ratings.values.map { it.score }.average()).toInt().coerceIn(1, 5)
        }
        val overallTier = ProficiencyLevel.fromScore(avgScore) ?: ProficiencyLevel.INTERMEDIATE

        return LearningGuideUiState(
            onboardingStatus = onboarding,
            ratings = ratings,
            completedToolIds = completed,
            recommendedTools = recommended,
            overallTier = overallTier,
            glossaryTierOverride = overrideTier,
            ratedCount = ratings.size,
            totalToolCount = toolIds.size,
            completedCount = completed.size
        )
    }

    fun refreshFromPreferences() {
        _uiState.value = buildSnapshot()
    }

    fun setProficiency(toolId: String, level: ProficiencyLevel?) {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.setToolProficiency(ctx, toolId, level)
        _uiState.value = buildSnapshot()
    }

    fun toggleWalkthroughCompleted(toolId: String) {
        val ctx = getApplication<Application>()
        val current = LearningGuidePreferences.isWalkthroughCompleted(ctx, toolId)
        LearningGuidePreferences.setWalkthroughCompleted(ctx, toolId, !current)
        _uiState.value = buildSnapshot()
    }

    fun markWalkthroughCompleted(toolId: String, completed: Boolean) {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.setWalkthroughCompleted(ctx, toolId, completed)
        _uiState.value = buildSnapshot()
    }

    fun completeOnboarding() {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.markOnboardingCompleted(ctx)
        _uiState.update { it.copy(onboardingStatus = OnboardingStatus.COMPLETED) }
    }

    fun skipOnboarding() {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.markOnboardingSkipped(ctx)
        _uiState.update { it.copy(onboardingStatus = OnboardingStatus.SKIPPED) }
    }

    fun restartOnboarding() {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.restartOnboarding(ctx)
        _uiState.update { it.copy(onboardingStatus = OnboardingStatus.NOT_STARTED) }
    }

    fun resetLearningProgress() {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.resetLearningProgress(ctx, toolIds)
        _uiState.value = buildSnapshot()
    }

    fun clearAllRatings() {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.clearAllRatings(ctx, toolIds)
        _uiState.value = buildSnapshot()
    }

    fun setGlossaryTierOverride(level: ProficiencyLevel?) {
        val ctx = getApplication<Application>()
        LearningGuidePreferences.setGlossaryTierOverride(ctx, level)
        _uiState.value = buildSnapshot()
    }

    companion object {
        /**
         * Sorts tools for Personalized Recommended Learning:
         * 1. Priority tools rated 1 (Beginner) or 2 (Novice) appear first (1 before 2).
         * 2. Tools skipped/unrated come next so nothing is hidden.
         * 3. Intermediate (3), Advanced (4), and Experienced (5) follow in order.
         */
        fun sortRecommendedTools(
            tools: List<ToolGuideContent>,
            ratings: Map<String, ProficiencyLevel>
        ): List<ToolGuideContent> {
            return tools.sortedWith(compareBy<ToolGuideContent> { tool ->
                when (val level = ratings[tool.id]) {
                    ProficiencyLevel.BEGINNER -> 0
                    ProficiencyLevel.NOVICE -> 1
                    null -> 2
                    ProficiencyLevel.INTERMEDIATE -> 3
                    ProficiencyLevel.ADVANCED -> 4
                    ProficiencyLevel.EXPERIENCED -> 5
                }
            }.thenBy { tools.indexOf(it) })
        }

        fun priorityToolsToLearn(
            tools: List<ToolGuideContent>,
            ratings: Map<String, ProficiencyLevel>
        ): List<ToolGuideContent> {
            return sortRecommendedTools(tools, ratings).filter { tool ->
                val score = ratings[tool.id]?.score
                score == 1 || score == 2
            }
        }
    }
}
