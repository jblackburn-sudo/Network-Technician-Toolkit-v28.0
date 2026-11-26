package com.example.networktoolkit.learning

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.theme.NetworkToolkitTheme

class LearningGuideActivity : ComponentActivity() {

    private val viewModel: LearningGuideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialTabName = intent.getStringExtra(EXTRA_START_TAB)
        val initialTab = LearningGuideTab.entries.firstOrNull { it.name == initialTabName }
        val initialToolId = intent.getStringExtra(EXTRA_TOOL_ID)
        val initialGlossaryId = intent.getStringExtra(EXTRA_GLOSSARY_ID)

        setContent {
            NetworkToolkitTheme {
                LearningGuideScreen(
                    viewModel = viewModel,
                    initialTab = initialTab ?: if (initialToolId != null) {
                        LearningGuideTab.TOOL_GUIDES
                    } else if (initialGlossaryId != null) {
                        LearningGuideTab.GLOSSARY
                    } else {
                        LearningGuideTab.WELCOME
                    },
                    initialToolId = initialToolId,
                    initialGlossaryId = initialGlossaryId,
                    onBackClick = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshFromPreferences()
    }

    companion object {
        const val EXTRA_START_TAB = "extra_start_tab"
        const val EXTRA_TOOL_ID = "extra_tool_id"
        const val EXTRA_GLOSSARY_ID = "extra_glossary_id"

        @JvmStatic
        @JvmOverloads
        fun createIntent(
            context: Context,
            toolId: String? = null,
            glossaryId: String? = null
        ): Intent = Intent(context, LearningGuideActivity::class.java).apply {
            if (toolId != null) putExtra(EXTRA_TOOL_ID, toolId)
            if (glossaryId != null) putExtra(EXTRA_GLOSSARY_ID, glossaryId)
        }

        @JvmStatic
        fun createTabIntent(context: Context, tab: LearningGuideTab): Intent =
            Intent(context, LearningGuideActivity::class.java).apply {
                putExtra(EXTRA_START_TAB, tab.name)
            }

        @JvmStatic
        fun createAssessmentIntent(context: Context): Intent =
            createTabIntent(context, LearningGuideTab.ASSESSMENT)

        @JvmStatic
        fun createGlossaryIntent(context: Context): Intent =
            createTabIntent(context, LearningGuideTab.GLOSSARY)
    }
}
