package com.example.networktoolkit.learning

import android.content.Context
import android.content.SharedPreferences

/**
 * Local-only SharedPreferences storage for the Technician Learning Guide.
 * Reuses the existing "ToolkitSettings" SharedPreferences file used elsewhere in the app.
 * No learning data, ratings, or progress are ever transmitted off the device.
 */
object LearningGuidePreferences {

    private const val PREFS_NAME = "ToolkitSettings"
    private const val KEY_ONBOARDING_STATUS = "learning_onboarding_status"
    private const val KEY_RATING_PREFIX = "learning_rating_"
    private const val KEY_COMPLETED_PREFIX = "learning_completed_"
    private const val KEY_GLOSSARY_TIER_OVERRIDE = "learning_glossary_tier_override"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getOnboardingStatus(context: Context): OnboardingStatus {
        val raw = prefs(context).getString(KEY_ONBOARDING_STATUS, OnboardingStatus.NOT_STARTED.name)
        return OnboardingStatus.fromStored(raw)
    }

    @JvmStatic
    fun shouldShowFirstLaunchPrompt(context: Context): Boolean =
        getOnboardingStatus(context) == OnboardingStatus.NOT_STARTED

    @JvmStatic
    fun setOnboardingStatus(context: Context, status: OnboardingStatus) {
        prefs(context).edit().putString(KEY_ONBOARDING_STATUS, status.name).apply()
    }

    @JvmStatic
    fun markOnboardingCompleted(context: Context) {
        setOnboardingStatus(context, OnboardingStatus.COMPLETED)
    }

    @JvmStatic
    fun markOnboardingSkipped(context: Context) {
        setOnboardingStatus(context, OnboardingStatus.SKIPPED)
    }

    @JvmStatic
    fun restartOnboarding(context: Context) {
        setOnboardingStatus(context, OnboardingStatus.NOT_STARTED)
    }

    @JvmStatic
    fun getToolProficiency(context: Context, toolId: String): ProficiencyLevel? {
        val score = prefs(context).getInt("$KEY_RATING_PREFIX$toolId", 0)
        return ProficiencyLevel.fromScore(score)
    }

    @JvmStatic
    fun setToolProficiency(context: Context, toolId: String, level: ProficiencyLevel?) {
        val editor = prefs(context).edit()
        if (level == null) {
            editor.remove("$KEY_RATING_PREFIX$toolId")
        } else {
            editor.putInt("$KEY_RATING_PREFIX$toolId", level.score)
        }
        editor.apply()
    }

    @JvmStatic
    fun getAllRatings(context: Context, toolIds: Collection<String>): Map<String, ProficiencyLevel> {
        val p = prefs(context)
        val result = linkedMapOf<String, ProficiencyLevel>()
        for (id in toolIds) {
            val level = ProficiencyLevel.fromScore(p.getInt("$KEY_RATING_PREFIX$id", 0))
            if (level != null) {
                result[id] = level
            }
        }
        return result
    }

    @JvmStatic
    fun clearAllRatings(context: Context, toolIds: Collection<String>) {
        val editor = prefs(context).edit()
        for (id in toolIds) {
            editor.remove("$KEY_RATING_PREFIX$id")
        }
        editor.apply()
    }

    @JvmStatic
    fun isWalkthroughCompleted(context: Context, toolId: String): Boolean =
        prefs(context).getBoolean("$KEY_COMPLETED_PREFIX$toolId", false)

    @JvmStatic
    fun setWalkthroughCompleted(context: Context, toolId: String, completed: Boolean) {
        val editor = prefs(context).edit()
        if (completed) {
            editor.putBoolean("$KEY_COMPLETED_PREFIX$toolId", true)
        } else {
            editor.remove("$KEY_COMPLETED_PREFIX$toolId")
        }
        editor.apply()
    }

    @JvmStatic
    fun getCompletedToolIds(context: Context, toolIds: Collection<String>): Set<String> {
        val p = prefs(context)
        return toolIds.filterTo(linkedSetOf()) { p.getBoolean("$KEY_COMPLETED_PREFIX$it", false) }
    }

    @JvmStatic
    fun resetLearningProgress(context: Context, toolIds: Collection<String>) {
        val editor = prefs(context).edit()
        for (id in toolIds) {
            editor.remove("$KEY_COMPLETED_PREFIX$id")
        }
        editor.apply()
    }

    @JvmStatic
    fun getGlossaryTierOverride(context: Context): ProficiencyLevel? {
        val score = prefs(context).getInt(KEY_GLOSSARY_TIER_OVERRIDE, 0)
        return ProficiencyLevel.fromScore(score)
    }

    @JvmStatic
    fun setGlossaryTierOverride(context: Context, level: ProficiencyLevel?) {
        val editor = prefs(context).edit()
        if (level == null) {
            editor.remove(KEY_GLOSSARY_TIER_OVERRIDE)
        } else {
            editor.putInt(KEY_GLOSSARY_TIER_OVERRIDE, level.score)
        }
        editor.apply()
    }
}
