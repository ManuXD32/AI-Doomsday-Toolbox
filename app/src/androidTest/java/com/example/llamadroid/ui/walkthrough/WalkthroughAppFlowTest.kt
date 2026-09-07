package com.example.llamadroid.ui.walkthrough

import android.view.ContextThemeWrapper
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the user-facing walkthrough against the real app navigation graph. */
class WalkthroughAppFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var agentPrefs: SharedPreferences
    private lateinit var savedSettings: Map<String, Any?>
    private lateinit var savedAgent: Map<String, Any?>

    @Before
    fun preparePreferences() {
        settingsPrefs = rule.activity.getSharedPreferences(
            SETTINGS_PREFS,
            Context.MODE_PRIVATE
        )
        agentPrefs = rule.activity.getSharedPreferences(
            AGENT_PREFS,
            Context.MODE_PRIVATE
        )
        savedSettings = capture(settingsPrefs, SETTINGS_KEYS)
        savedAgent = capture(agentPrefs, AGENT_KEYS)

        val settingsEditor = settingsPrefs.edit()
            .putBoolean(HAS_COMPLETED_WELCOME, true)
            .putBoolean(AUTOMATIC_ELIGIBLE, false)
        CHAPTER_IDS.forEach { chapterId ->
            settingsEditor
                .remove(progressKey(chapterId))
                .remove(completedKey(chapterId))
        }
        settingsEditor.commit()
        agentPrefs.edit()
            .putBoolean(FIRST_RUN_SHOWN, false)
            .commit()
    }

    @After
    fun restorePreferences() {
        restore(settingsPrefs, savedSettings)
        restore(agentPrefs, savedAgent)
    }

    @Test
    fun coreTourFollowsRealRootsToolsCreateBackLibraryTamaSettingsAndHome() {
        composeApp()

        assertRoute("dashboard")
        rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
        rule.onNodeWithTag("tour_guide").assertIsDisplayed()
        clickChapterStart(CoreTour.ID)

        assertRoute("dashboard")
        assertStep("home")
        next()
        assertStep("tools")
        clickRoot("ai_hub")
        assertStep("tools")
        next()
        assertStep("create")
        clickTool("image_generation")
        assertRoute("image_gen")
        assertStep("create")
        next()
        assertStep("back")
        clickBack()
        assertRoute("ai_hub")
        next()
        assertStep("library")
        clickRoot("library")
        assertStep("library")
        next()
        assertStep("tama")
        clickRoot("tama")
        dismissTamaCreationDialogIfPresent()
        assertStep("tama")
        next()
        assertStep("settings")
        rule.onNodeWithTag("soft_studio_settings").assertIsDisplayed().performClick()
        assertRoute("settings")
        assertStep("settings")
        next()
        assertStep("replay")
        clickBack()
        assertRoute("tama")
        clickRoot("dashboard")
        assertStep("replay")
        next()

        assertRoute("dashboard")
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(WalkthroughPreferences(settingsPrefs).isCompleted(CoreTour.ID))
        }
    }

    @Test
    fun agentTourDefersFirstRunPopupAndBackKeepsTourActiveUntilClose() {
        composeApp()

        rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
        clickChapterStart(AGENT_CHAPTER)
        assertRoute("ai_hub")
        assertStep("agent")

        clickTool("agent")
        assertRoute("agent")
        rule.onNodeWithText(englishString(R.string.agent_welcome_title))
            .assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(agentPrefs.getBoolean(FIRST_RUN_SHOWN, true))
        }

        clickBack()
        assertRoute("ai_hub")
        assertStep("agent")
        rule.onNodeWithTag("tour_coach").assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(agentPrefs.getBoolean(FIRST_RUN_SHOWN, true))
        }

        rule.onNodeWithTag("tour_close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(WalkthroughPreferences(settingsPrefs).isCompleted(AGENT_CHAPTER))
        }
    }

    @Test
    fun everyChapterReachesEveryLessonRouteThroughFocusedNativeTargets() {
        composeApp()
        assertEquals(10, WalkthroughCatalog.chapters.size)
        assertEquals(39, WalkthroughCatalog.chapters.sumOf { it.lessons.size })

        WalkthroughCatalog.chapters.forEach { chapter ->
            returnToDashboard()
            rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
            clickChapterStart(chapter.id)

            chapter.lessons.forEachIndexed { index, lesson ->
                assertStep(lesson.id)
                reachLessonRoute(lesson)
                rule.onNodeWithTag("tour_close").assertIsDisplayed()
                assertRoute(expectedLessonRoute(lesson))

                next()
                if (index < chapter.lessons.lastIndex) {
                    assertStep(chapter.lessons[index + 1].id)
                }
            }

            rule.onNodeWithTag("tour_coach").assertDoesNotExist()
            rule.runOnIdle {
                assertTrue(WalkthroughPreferences(settingsPrefs).isCompleted(chapter.id))
            }
        }
    }

    private fun composeApp() {
        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp()
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertRoute(route: String) {
        rule.onNodeWithTag("studio_route_$route").assertIsDisplayed()
    }

    private fun assertStep(step: String) {
        rule.onNodeWithTag("tour_step_$step").assertIsDisplayed()
    }

    private fun next() {
        rule.onNodeWithTag("tour_next").assertIsDisplayed().performClick()
    }

    private fun clickChapterStart(chapterId: String) {
        val startTag = "tour_start_$chapterId"
        rule.onNodeWithTag("tour_guide").performScrollToNode(hasTestTag(startTag))
        rule.onNodeWithTag(startTag).assertIsDisplayed().performClick()
    }

    private fun clickRoot(route: String) {
        rule.onNodeWithTag("studio_bar_$route").assertIsDisplayed().performClick()
        assertRoute(route)
    }

    private fun clickTool(id: String) {
        rule.onNodeWithTag("studio_tool_$id")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    private fun clickBack() {
        rule.onNodeWithContentDescription(
            englishString(R.string.action_back),
            useUnmergedTree = true
        ).assertIsDisplayed().performClick()
    }

    private fun reachLessonRoute(lesson: WalkthroughLesson) {
        val expectedRoute = expectedLessonRoute(lesson)
        repeat(MAX_LESSON_NAVIGATION_ATTEMPTS) {
            dismissTamaCreationDialogIfPresent()
            if (hasTag("studio_route_$expectedRoute")) return

            val focused = rule.onAllNodes(
                SemanticsMatcher.expectValue(WalkthroughFocusedTarget, true),
                useUnmergedTree = true
            )
            if (focused.fetchSemanticsNodes().isNotEmpty()) {
                focused.onFirst().performClick()
            } else if (hasTag("tour_open_tool")) {
                rule.onNodeWithTag("tour_open_tool").performScrollTo().performClick()
            } else {
                // A missing target becomes an explicit fallback after the coach's bounded
                // lookup delay. Advance only that delay; never press Skip to claim arrival.
                rule.mainClock.advanceTimeBy(TARGET_LOOKUP_DELAY_MS)
                rule.waitForIdle()
            }
        }
        dismissTamaCreationDialogIfPresent()
        assertRoute(expectedRoute)
    }

    private fun expectedLessonRoute(lesson: WalkthroughLesson): String =
        if (lesson.id == "chat") "llama_servers" else routeBase(lesson.route)

    private fun returnToDashboard() {
        dismissTamaCreationDialogIfPresent()
        repeat(MAX_RETURN_NAVIGATION_ATTEMPTS) {
            if (hasTag("studio_bar_dashboard")) {
                if (!hasTag("studio_route_dashboard")) clickRoot("dashboard")
                return
            }
            dismissTamaCreationDialogIfPresent()
            val back = rule.onAllNodesWithContentDescription(
                englishString(R.string.action_back),
                useUnmergedTree = true
            )
            if (back.fetchSemanticsNodes().isEmpty()) {
                error("No native Back target while returning from walkthrough route")
            }
            back.onFirst().performClick()
        }
        error("Could not return to Dashboard before starting the next chapter")
    }

    private fun dismissTamaCreationDialogIfPresent() {
        val title = rule.onAllNodesWithText(
            englishString(R.string.tama_new_pet_title),
            useUnmergedTree = true
        )
        if (title.fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText(
                englishString(R.string.action_cancel),
                useUnmergedTree = true
            ).performClick()
        }
    }

    private fun englishString(resId: Int): String {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.US)
        }
        return rule.activity.createConfigurationContext(configuration).getString(resId)
    }

    private fun hasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun StandardEnglish411(content: @Composable () -> Unit) {
        val baseDensity = LocalDensity.current
        val physicalDensity = baseDensity.density
        val configuration = Configuration(LocalConfiguration.current).apply {
            screenWidthDp = VIEWPORT_WIDTH_DP
            screenHeightDp = VIEWPORT_HEIGHT_DP
            fontScale = 1f
            setLocale(Locale.US)
        }
        val localizedContext = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
        val window = fixtureWindow(VIEWPORT_WIDTH_DP, VIEWPORT_HEIGHT_DP, physicalDensity)
        CompositionLocalProvider(
            // Keep both the localized resources and the real host owners available to feature launchers.
            LocalActivity provides rule.activity,
            LocalActivityResultRegistryOwner provides rule.activity,
            LocalFullyDrawnReporterOwner provides rule.activity,
            LocalOnBackPressedDispatcherOwner provides rule.activity,
            LocalConfiguration provides configuration,
            LocalContext provides localizedContext,
            LocalResources provides localizedContext.resources,
            LocalWindowInfo provides window,
            LocalDensity provides Density(physicalDensity, 1f)
        ) {
            content()
        }
    }

    private fun capture(prefs: SharedPreferences, keys: List<String>): Map<String, Any?> {
        val values = prefs.all
        return keys.associateWith { key ->
            if (prefs.contains(key)) values[key] else MissingPreference
        }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                MissingPreference -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value for $key: ${value::class}")
            }
        }
        check(editor.commit()) { "Could not restore walkthrough test preferences" }
    }

    private fun fixtureWindow(widthDp: Int, heightDp: Int, density: Float) = object : WindowInfo {
        override val isWindowFocused = true
        override val containerSize = IntSize(
            (widthDp * density).roundToInt(),
            (heightDp * density).roundToInt()
        )
    }

    private object MissingPreference

    private companion object {
        const val SETTINGS_PREFS = "llamadroid_settings"
        const val AGENT_PREFS = "agent_prefs"
        const val HAS_COMPLETED_WELCOME = "has_completed_welcome"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val FIRST_RUN_SHOWN = "first_run_shown"
        const val AGENT_CHAPTER = "agent"
        val CHAPTER_IDS = listOf(CoreTour.ID) + WalkthroughCatalog.chapters.map { it.id }
        val SETTINGS_KEYS = listOf(HAS_COMPLETED_WELCOME, AUTOMATIC_ELIGIBLE) +
            CHAPTER_IDS.flatMap { chapterId ->
                listOf(progressKey(chapterId), completedKey(chapterId))
            }
        val AGENT_KEYS = listOf(FIRST_RUN_SHOWN)
        const val VIEWPORT_WIDTH_DP = 411
        const val VIEWPORT_HEIGHT_DP = 800
        const val MAX_LESSON_NAVIGATION_ATTEMPTS = 12
        const val MAX_RETURN_NAVIGATION_ATTEMPTS = 16
        const val TARGET_LOOKUP_DELAY_MS = 2_100L

        private fun progressKey(chapterId: String) = "walkthrough_progress:$chapterId"

        private fun completedKey(chapterId: String) = "walkthrough_completed:$chapterId"
    }
}
