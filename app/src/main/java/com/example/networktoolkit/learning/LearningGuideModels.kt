package com.example.networktoolkit.learning

enum class ProficiencyLevel(
    val score: Int,
    val title: String,
    val description: String,
    val focusTag: String
) {
    BEGINNER(
        score = 1,
        title = "Beginner",
        description = "I am unfamiliar with this tool and would like a detailed explanation.",
        focusTag = "Step-by-step foundations"
    ),
    NOVICE(
        score = 2,
        title = "Novice",
        description = "I understand the basics but need help using it.",
        focusTag = "Guided operation & basics"
    ),
    INTERMEDIATE(
        score = 3,
        title = "Intermediate",
        description = "I can use this tool but would like help interpreting results.",
        focusTag = "Result interpretation & triage"
    ),
    ADVANCED(
        score = 4,
        title = "Advanced",
        description = "I understand this tool well and would like advanced troubleshooting information.",
        focusTag = "Advanced diagnostics & limits"
    ),
    EXPERIENCED(
        score = 5,
        title = "Experienced",
        description = "I am comfortable using this tool and only need reference material or advanced information.",
        focusTag = "Concise technical reference"
    );

    companion object {
        @JvmStatic
        fun fromScore(score: Int): ProficiencyLevel? =
            entries.firstOrNull { it.score == score }
    }
}

enum class OnboardingStatus {
    NOT_STARTED,
    SKIPPED,
    COMPLETED;

    companion object {
        @JvmStatic
        fun fromStored(raw: String?): OnboardingStatus =
            entries.firstOrNull { it.name == raw } ?: NOT_STARTED
    }
}

enum class ToolGuideCategory(val label: String) {
    CORE_DIAGNOSTICS("Core & Connectivity"),
    WIFI_RF("Wi-Fi & RF Analysis"),
    PATH_THROUGHPUT("Path & Throughput"),
    UTILITIES_RECORDS("Utilities & Reporting")
}

data class ToolGuideContent(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: ToolGuideCategory,
    val whereToFind: String,
    val whatItDoes: String,
    val whenToUse: List<String>,
    val howToUseSteps: List<String>,
    val understandingResults: List<Pair<String, String>>,
    val whatResultsIndicate: List<Pair<String, String>>,
    val troubleshootingScenarios: List<Pair<String, String>>,
    val limitations: List<String>,
    val beginnerTip: String,
    val intermediateGuidance: String,
    val advancedReference: String,
    val quickHelpBullets: List<String>,
    val relatedGlossaryIds: List<String> = emptyList()
)

enum class GlossaryCategory(val label: String) {
    FUNDAMENTALS("Networking Fundamentals"),
    DIAGNOSTICS("Diagnostic Terminology"),
    WIFI("Wi-Fi Terminology"),
    PERFORMANCE("Performance Terminology")
}

data class GlossaryEntry(
    val id: String,
    val term: String,
    val fullName: String? = null,
    val category: GlossaryCategory,
    val plainEnglishDefinition: String,
    val technicalExplanation: String,
    val appExample: String,
    val whyItMatters: String,
    val conciseReference: String
)

data class TroubleshootingPlaybookItem(
    val id: String,
    val symptom: String,
    val recommendedToolIds: List<String>,
    val stepByStepChecks: List<String>,
    val rookiePitfall: String
)

data class AdvancedReferenceItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val bulletPoints: List<String>
)
