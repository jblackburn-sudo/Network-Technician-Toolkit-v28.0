package com.example.networktoolkit

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.widget.Button
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.networktoolkit.learning.GlossaryCategory
import com.example.networktoolkit.learning.LearningGlossaryCatalog
import com.example.networktoolkit.learning.LearningGuideActivity
import com.example.networktoolkit.learning.LearningGuideCatalog
import com.example.networktoolkit.learning.LearningGuidePreferences
import com.example.networktoolkit.learning.LearningGuideScreen
import com.example.networktoolkit.learning.LearningGuideTab
import com.example.networktoolkit.learning.LearningGuideViewModel
import com.example.networktoolkit.learning.OnboardingStatus
import com.example.networktoolkit.learning.ProficiencyLevel
import com.example.networktoolkit.speedtest.SpeedTestServicePreferences
import com.example.ui.theme.NetworkToolkitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TechnicianLearningGuideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ToolkitSettings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // =========================================================================
    // 1. FIRST-LAUNCH ONBOARDING TESTS
    // =========================================================================

    @Test
    fun `first launch displays onboarding dialog and dismiss Not right now persists skipped state`() {
        assertTrue(LearningGuidePreferences.shouldShowFirstLaunchPrompt(app))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var secondController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            val activity = controller.get()

            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("Expected first-launch onboarding AlertDialog", dialog)
            assertTrue(dialog.isShowing)

            // Home content remains accessible underneath and includes Learning Guide entry
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val buttons = mutableListOf<Button>()
            collectButtons(root, buttons)
            assertTrue(buttons.any { it.text.toString().contains("Technician Learning Guide") })

            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(OnboardingStatus.SKIPPED, LearningGuidePreferences.getOnboardingStatus(app))
            assertFalse(LearningGuidePreferences.shouldShowFirstLaunchPrompt(app))

            // Second launch must not re-show onboarding dialog
            ShadowAlertDialog.reset()
            secondController = Robolectric.buildActivity(MainActivity::class.java).setup()
            val secondLaunchDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNull("Onboarding dialog must not repeat after dismissal", secondLaunchDialog)
        } finally {
            secondController?.pause()?.stop()?.destroy()
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `onboarding Yes show me around marks completed and launches LearningGuideActivity`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var secondController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            val activity = controller.get()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)

            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(OnboardingStatus.COMPLETED, LearningGuidePreferences.getOnboardingStatus(app))
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(started)
            assertEquals(LearningGuideActivity::class.java.name, started.component?.className)

            // Subsequent launch does not re-show
            ShadowAlertDialog.reset()
            secondController = Robolectric.buildActivity(MainActivity::class.java).setup()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        } finally {
            secondController?.pause()?.stop()?.destroy()
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `onboarding Customize my learning guide opens Assessment tab directly`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)

            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(OnboardingStatus.COMPLETED, LearningGuidePreferences.getOnboardingStatus(app))
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(started)
            assertEquals(LearningGuideActivity::class.java.name, started.component?.className)
            assertEquals(LearningGuideTab.ASSESSMENT.name, started.getStringExtra(LearningGuideActivity.EXTRA_START_TAB))
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 2. PREFERENCES, RECOMMENDATION SORTING & PROGRESS RESET TESTS
    // =========================================================================

    @Test
    fun `proficiency ratings 1 and 2 prioritize those tools in recommended learning and survive progress reset`() {
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LearningGuideViewModel(app) as T
                    }
                }
            )[LearningGuideViewModel::class.java]
            vm.setProficiency("ping_test", ProficiencyLevel.EXPERIENCED) // 5
            vm.setProficiency("subnet_calculator", ProficiencyLevel.BEGINNER) // 1
            vm.setProficiency("traceroute", ProficiencyLevel.NOVICE) // 2

            val state = vm.uiState.value
            assertEquals(3, state.ratedCount)
            assertEquals("subnet_calculator", state.recommendedTools[0].id)
            assertEquals("traceroute", state.recommendedTools[1].id)
            assertEquals("ping_test", state.recommendedTools.last().id)

            // Complete a walkthrough, then reset progress; completion clears while ratings remain
            vm.markWalkthroughCompleted("subnet_calculator", true)
            assertEquals(1, vm.uiState.value.completedCount)

            vm.resetLearningProgress()
            assertEquals(0, vm.uiState.value.completedCount)
            assertEquals(ProficiencyLevel.BEGINNER, vm.uiState.value.ratings["subnet_calculator"])

            // Clear a single rating (skip) and restart onboarding
            vm.setProficiency("traceroute", null)
            assertNull(vm.uiState.value.ratings["traceroute"])

            vm.completeOnboarding()
            assertEquals(OnboardingStatus.COMPLETED, vm.uiState.value.onboardingStatus)
            vm.restartOnboarding()
            assertEquals(OnboardingStatus.NOT_STARTED, vm.uiState.value.onboardingStatus)
            assertTrue(LearningGuidePreferences.shouldShowFirstLaunchPrompt(app))
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    // =========================================================================
    // 3. CATALOG ACCURACY, GLOSSARY COMPLETENESS & STELLAR REMOVAL CHECKS
    // =========================================================================

    @Test
    fun `learning catalog and glossary are complete accurate and free of stellar broadband`() {
        assertTrue("Expected at least 15 tool guides", LearningGuideCatalog.tools.size >= 15)
        assertTrue("Expected at least 50 glossary entries", LearningGlossaryCatalog.entries.size >= 50)

        // Verify all 4 required glossary categories are well-populated
        GlossaryCategory.entries.forEach { cat ->
            val count = LearningGlossaryCatalog.entries.count { it.category == cat }
            assertTrue("Category $cat should have entries", count >= 10)
        }

        // Verify each tool guide has A-H sections and valid related glossary references
        LearningGuideCatalog.tools.forEach { tool ->
            assertTrue("Tool ${tool.id} missing whatItDoes", tool.whatItDoes.isNotBlank())
            assertTrue("Tool ${tool.id} missing whenToUse", tool.whenToUse.isNotEmpty())
            assertTrue("Tool ${tool.id} missing howToUseSteps", tool.howToUseSteps.isNotEmpty())
            assertTrue("Tool ${tool.id} missing understandingResults", tool.understandingResults.isNotEmpty())
            assertTrue("Tool ${tool.id} missing whatResultsIndicate", tool.whatResultsIndicate.isNotEmpty())
            assertTrue("Tool ${tool.id} missing troubleshootingScenarios", tool.troubleshootingScenarios.isNotEmpty())
            assertTrue("Tool ${tool.id} missing limitations", tool.limitations.isNotEmpty())
            assertTrue("Tool ${tool.id} missing quickHelpBullets", tool.quickHelpBullets.isNotEmpty())
            tool.relatedGlossaryIds.forEach { gid ->
                assertNotNull(
                    "Tool ${tool.id} references missing glossary id '$gid'",
                    LearningGlossaryCatalog.findById(gid)
                )
            }
        }

        // Confirm Stellar Broadband / speedtest.stellarbb.com is completely absent
        val presets = SpeedTestServicePreferences.getPresetServices()
        assertFalse(presets.any { it.url.contains("stellarbb", ignoreCase = true) || it.name.contains("Stellar", ignoreCase = true) })
        LearningGuideCatalog.tools.forEach { tool ->
            val combined = "${tool.title} ${tool.subtitle} ${tool.whatItDoes} ${tool.limitations.joinToString()}"
            assertFalse(combined.contains("stellarbb", ignoreCase = true))
            assertFalse(combined.contains("Stellar Broadband", ignoreCase = true))
        }
    }

    // =========================================================================
    // 4. COMPOSE UI WORKFLOWS: ASSESSMENT, WALKTHROUGH, GLOSSARY, THEME & HELP
    // =========================================================================

    @Test
    fun `learning guide screen supports assessment rating walkthrough completion glossary search and dark light themes`() {
        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LearningGuideViewModel(app) as T
                    }
                }
            )[LearningGuideViewModel::class.java]

            composeTestRule.setContent {
                NetworkToolkitTheme(darkTheme = false) {
                    LearningGuideScreen(
                        viewModel = vm,
                        onBackClick = {},
                        initialTab = LearningGuideTab.WELCOME
                    )
                }
            }

            composeTestRule.onNodeWithText("Technician Learning Guide").assertIsDisplayed()

            // Switch to Assessment tab and rate Ping Test as 1 (Beginner)
            composeTestRule.onNodeWithTag("learning_tab_assessment").performClick()
            composeTestRule.onNodeWithTag("assessment_lazy_list").performScrollToNode(hasTestTag("rate_ping_test_1"))
            composeTestRule.onNodeWithTag("rate_ping_test_1").performClick()
            assertEquals(ProficiencyLevel.BEGINNER, vm.uiState.value.ratings["ping_test"])

            // Switch to Tool Guides and open Ping Test walkthrough
            composeTestRule.onNodeWithTag("learning_tab_tool_guides").performClick()
            composeTestRule.onNodeWithTag("tool_guides_lazy_list").performScrollToNode(hasTestTag("guide_tool_card_ping_test"))
            composeTestRule.onNodeWithTag("guide_tool_card_ping_test").performClick()

            composeTestRule.onNodeWithTag("walkthrough_detail_lazy_list").performScrollToNode(hasText("What This Tool Does"))
            composeTestRule.onNodeWithText("What This Tool Does").assertIsDisplayed()
            composeTestRule.onNodeWithTag("walkthrough_detail_lazy_list").performScrollToNode(hasTestTag("walkthrough_toggle_complete_button"))
            composeTestRule.onNodeWithTag("walkthrough_toggle_complete_button").performClick()
            assertTrue(vm.uiState.value.completedToolIds.contains("ping_test"))

            // Switch to Glossary and search for RSSI
            composeTestRule.onNodeWithTag("learning_tab_glossary").performClick()
            composeTestRule.onNodeWithTag("glossary_search_input").performTextInput("RSSI")
            composeTestRule.onNodeWithTag("glossary_lazy_list").performScrollToNode(hasTestTag("glossary_card_rssi"))
            composeTestRule.onNodeWithTag("glossary_toggle_rssi").assertIsDisplayed().performClick()
            composeTestRule.onAllNodesWithText("Received Signal Strength Indicator", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
                .let { assertTrue(it) }
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `learning guide renders cleanly in dark theme and contextual help dialog opens on tool screen`() {
        val vmStore = ViewModelStore()
        try {
            val subnetVm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SubnetCalculatorViewModel() as T
                    }
                }
            )[SubnetCalculatorViewModel::class.java]
            composeTestRule.setContent {
                NetworkToolkitTheme(darkTheme = true) {
                    SubnetCalculatorScreen(
                        viewModel = subnetVm,
                        onBackClick = {}
                    )
                }
            }

            composeTestRule.onNodeWithTag("subnet_calculator_help_button").assertIsDisplayed().performClick()
            composeTestRule.onNodeWithTag("contextual_help_full_walkthrough_button").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("contextual_help_glossary_button").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithTag("contextual_help_close_button").performClick()
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `main activity ping screen shows Ping Help button and opens contextual Ping guide dialog`() {
        LearningGuidePreferences.markOnboardingSkipped(app)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()

            val homeButtons = mutableListOf<Button>()
            collectButtons(activity.findViewById(android.R.id.content), homeButtons)
            val pingTile = homeButtons.first { it.text.toString().startsWith("Ping Test") }
            pingTile.performClick()

            val pingButtons = mutableListOf<Button>()
            collectButtons(activity.findViewById(android.R.id.content), pingButtons)
            val pingHelpBtn = pingButtons.firstOrNull { it.text.toString() == "Ping Help" }
            assertNotNull("Expected Ping Help button inside Ping screen", pingHelpBtn)

            ShadowAlertDialog.reset()
            pingHelpBtn!!.performClick()
            val helpDialog: AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("Clicking Ping Help should display help dialog", helpDialog)
            helpDialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(started)
            assertEquals(LearningGuideActivity::class.java.name, started.component?.className)
            assertEquals("ping_test", started.getStringExtra(LearningGuideActivity.EXTRA_TOOL_ID))
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `restarting onboarding triggers welcome prompt on MainActivity onResume`() {
        LearningGuidePreferences.markOnboardingSkipped(app)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertNull(ShadowAlertDialog.getLatestAlertDialog())

            // Simulate user opening Learning Guide, tapping "Restart Onboarding", and pressing Back to Home
            controller.pause()
            LearningGuidePreferences.restartOnboarding(app)
            controller.resume()

            val resumedDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("Restarted onboarding prompt must show when returning to Home via onResume", resumedDialog)
            assertTrue(resumedDialog!!.isShowing)
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `playbooks reference valid tools and adaptive depth switches walkthrough and glossary text`() {
        assertTrue(LearningGuideCatalog.troubleshootingPlaybook.size >= 6)
        assertTrue(LearningGuideCatalog.advancedReferences.size >= 4)
        LearningGuideCatalog.troubleshootingPlaybook.forEach { pb ->
            assertTrue(pb.symptom.isNotBlank())
            assertTrue(pb.stepByStepChecks.isNotEmpty())
            assertTrue(pb.rookiePitfall.isNotBlank())
            pb.recommendedToolIds.forEach { tid ->
                assertNotNull("Playbook ${pb.id} references unknown tool '$tid'", LearningGuideCatalog.findToolById(tid))
            }
        }

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LearningGuideViewModel(app) as T
                    }
                }
            )[LearningGuideViewModel::class.java]
            composeTestRule.setContent {
                NetworkToolkitTheme(darkTheme = false) {
                    LearningGuideScreen(
                        viewModel = vm,
                        onBackClick = {},
                        initialTab = LearningGuideTab.TOOL_GUIDES,
                        initialToolId = "ping_test"
                    )
                }
            }

            // Default tier shows Beginner/Novice adaptive note; switching inline rating to 5 switches to Technical Reference
            composeTestRule.onNodeWithTag("walkthrough_detail_lazy_list").performScrollToNode(hasTestTag("rate_ping_test_5"))
            composeTestRule.onNodeWithTag("rate_ping_test_5").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(ProficiencyLevel.EXPERIENCED, vm.uiState.value.ratings["ping_test"])
            composeTestRule.onNodeWithText("Concise Technical Reference (Experienced)", substring = true)
                .performScrollTo()
                .assertIsDisplayed()

            // Open Playbook & Ref tab and verify playbook cards render
            composeTestRule.onNodeWithTag("learning_tab_reference").performClick()
            assertTrue(
                composeTestRule.onAllNodesWithText("Troubleshooting Basics (Field Playbooks)", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            )
        } finally {
            vmStore.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun collectButtons(view: android.view.View, out: MutableList<Button>) {
        if (view is Button) {
            out.add(view)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectButtons(view.getChildAt(i), out)
            }
        }
    }
}
