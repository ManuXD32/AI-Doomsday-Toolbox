package com.example.llamadroid.ui.walkthrough

import android.view.ContextThemeWrapper
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.example.llamadroid.SharedFileData
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.LlamaApp
import com.example.llamadroid.ui.navigation.ExternalRouteResolution
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Covers launch gating and activity-lifetime walkthrough behavior against the real host. */
class WalkthroughLifecycleTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var settingsPrefs: SharedPreferences
    private lateinit var savedSettings: Map<String, Any?>

    @Before
    fun preparePreferences() {
        settingsPrefs = rule.activity.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        savedSettings = capture(settingsPrefs, PREFERENCE_KEYS)
        check(
            settingsPrefs.edit()
                .putBoolean(HAS_COMPLETED_WELCOME, true)
                .remove(AUTOMATIC_ELIGIBLE)
                .remove(CORE_PROGRESS)
                .remove(CORE_COMPLETED)
                .remove(SUPPORT_LAST_SHOWN)
                .commit()
        )
    }

    @After
    fun restorePreferences() {
        restore(settingsPrefs, savedSettings)
    }

    @Test
    fun existingWelcomeUserIsManualOnlyUntilOpeningGuide() {
        composeApp(allowAutomaticWalkthrough = true)

        assertRoute(Screen.Dashboard.route)
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(WalkthroughPreferences(settingsPrefs).automaticEligible)
        }

        openGuide()
        rule.onNodeWithTag("tour_guide").assertIsDisplayed()
    }

    @Test
    fun newEligibleUserStartsCoreTourAfterWelcome() {
        settingsPrefs.edit().putBoolean(AUTOMATIC_ELIGIBLE, true).commit()

        composeApp(allowAutomaticWalkthrough = true, normalLaunchId = 1)

        assertRoute(Screen.Dashboard.route)
        waitForTag("tour_step_home")
        rule.onNodeWithTag("tour_coach").assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(WalkthroughPreferences(settingsPrefs).automaticEligible)
            assertTrue(WalkthroughPreferences(settingsPrefs).progress(CoreTour.ID) == "home")
        }
    }

    @Test
    fun externalNavigationDoesNotConsumeEligibilityUntilNormalHomeLaunch() {
        settingsPrefs.edit().putBoolean(AUTOMATIC_ELIGIBLE, true).commit()

        val pendingRoute = mutableStateOf<ExternalRouteResolution>(
            ExternalRouteResolution.Navigate(Screen.Library.route)
        )
        val pendingNavigateDashboard = mutableStateOf(false)
        val allowAutomatic = mutableStateOf(false)
        val normalLaunchId = mutableStateOf(7)

        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp(
                        pendingNavigationRoute = if (pendingNavigateDashboard.value) {
                            ExternalRouteResolution.Navigate(Screen.Dashboard.route)
                        } else {
                            pendingRoute.value
                        },
                        onNavigationHandled = {
                            if (pendingNavigateDashboard.value) {
                                pendingNavigateDashboard.value = false
                            } else {
                                pendingRoute.value = ExternalRouteResolution.NoRoute
                            }
                        },
                        allowDailySupportPrompt = allowAutomatic.value,
                        allowAutomaticWalkthrough = allowAutomatic.value,
                        normalLaunchId = normalLaunchId.value
                    )
                }
            }
        }
        rule.waitForIdle()

        assertRoute(Screen.Library.route)
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(WalkthroughPreferences(settingsPrefs).automaticEligible)
        }

        // A normal onNewIntent returns to Home and enables automatic-tour/support gating. The
        // route remains pending until LlamaApp reports it handled, just as MainActivity does.
        rule.runOnIdle {
            allowAutomatic.value = true
            normalLaunchId.value = 8
            pendingNavigateDashboard.value = true
        }

        assertRoute(Screen.Dashboard.route)
        waitForTag("tour_step_home")
        rule.runOnIdle {
            assertFalse(WalkthroughPreferences(settingsPrefs).automaticEligible)
        }
    }

    @Test
    fun newNavigationSupersedesPendingShareChooserAndReachesLibrary() {
        val sharedFile = mutableStateOf<SharedFileData?>(
            SharedFileData(
                uri = Uri.parse("content://walkthrough.test/demo.png"),
                mimeType = "image/png"
            )
        )
        val pendingRoute = mutableStateOf<ExternalRouteResolution>(
            ExternalRouteResolution.NoRoute
        )

        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp(
                        sharedFileData = sharedFile.value,
                        onSharedFileHandled = { sharedFile.value = null },
                        pendingNavigationRoute = pendingRoute.value,
                        onNavigationHandled = {
                            pendingRoute.value = ExternalRouteResolution.NoRoute
                        },
                        allowDailySupportPrompt = false,
                        allowAutomaticWalkthrough = false
                    )
                }
            }
        }
        rule.waitForIdle()

        val chooserTitle = englishString(R.string.action_open_with)
        waitForText(R.string.action_open_with)

        // Model MainActivity receiving a new intent: clear stale share input and publish the
        // validated Library route in the same composition. No provider is opened or read.
        rule.runOnIdle {
            sharedFile.value = null
            pendingRoute.value = ExternalRouteResolution.Navigate(Screen.Library.route)
        }

        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            pendingRoute.value == ExternalRouteResolution.NoRoute &&
                hasTag("studio_route_${Screen.Library.route}") &&
                rule.onAllNodesWithText(chooserTitle, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        rule.onNodeWithText(chooserTitle, useUnmergedTree = true).assertDoesNotExist()
        assertRoute(Screen.Library.route)
    }

    @Test
    fun closingAutomaticTourSuppressesSupportForTheRestOfTheLaunch() {
        settingsPrefs.edit().putBoolean(AUTOMATIC_ELIGIBLE, true).commit()

        composeApp(allowDailySupportPrompt = true, allowAutomaticWalkthrough = true, normalLaunchId = 2)

        waitForTag("tour_step_home")
        rule.onNodeWithTag("tour_close").assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { !hasTag("tour_coach") }
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.onNodeWithText(englishString(R.string.support_daily_title)).assertDoesNotExist()
        rule.runOnIdle {
            assertFalse(settingsPrefs.contains(SUPPORT_LAST_SHOWN))
        }
    }

    @Test
    fun guideOnlyLaunchDoesNotShowSupportAfterPromptIsDismissed() {
        composeApp(allowDailySupportPrompt = true, allowAutomaticWalkthrough = false)

        waitForText(R.string.support_daily_title)
        rule.onNodeWithText(englishString(R.string.support_not_now)).performClick()
        rule.onNodeWithText(englishString(R.string.support_daily_title)).assertDoesNotExist()

        openGuide()
        rule.onNodeWithTag("tour_guide").assertIsDisplayed()
        rule.onNodeWithText(englishString(R.string.support_daily_title)).assertDoesNotExist()
    }

    @Test
    fun interruptedCoreTourResumesSavedStepAfterReopeningGuide() {
        composeApp()

        openGuide()
        rule.onNodeWithTag("tour_start_core").assertIsDisplayed().performClick()
        waitForTag("tour_step_home")
        rule.onNodeWithTag("tour_next").assertIsDisplayed().performClick()
        waitForTag("tour_step_tools")
        rule.onNodeWithTag("tour_close").assertIsDisplayed().performClick()
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { !hasTag("tour_coach") }
        rule.runOnIdle {
            assertTrue(WalkthroughPreferences(settingsPrefs).progress(CoreTour.ID) == "tools")
            assertFalse(WalkthroughPreferences(settingsPrefs).isCompleted(CoreTour.ID))
        }

        openGuide()
        rule.onNodeWithTag("tour_resume_core").assertIsDisplayed().performClick()
        waitForTag("tour_step_tools")
    }

    @Test
    fun externalLaunchDismissesManualTourAndSameTokenAllowsReopen() {
        val externalLaunchId = mutableStateOf(0)

        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp(
                        pendingNavigationRoute = ExternalRouteResolution.NoRoute,
                        allowDailySupportPrompt = false,
                        allowAutomaticWalkthrough = false,
                        externalLaunchId = externalLaunchId.value
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
        waitForTag("tour_guide")
        rule.onNodeWithTag("tour_start_core").assertIsDisplayed().performClick()
        waitForTag("tour_step_home")
        rule.onNodeWithTag("tour_next").assertIsDisplayed().performClick()
        waitForTag("tour_step_tools")

        // A new external launch has no share payload or pending route in this case. It should
        // dismiss only the active coach and retain the stable chapter step for Resume.
        rule.runOnIdle { externalLaunchId.value = 1 }
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { !hasTag("tour_coach") }
        rule.onNodeWithTag("tour_coach").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(WalkthroughPreferences(settingsPrefs).progress(CoreTour.ID) == "tools")
            assertFalse(WalkthroughPreferences(settingsPrefs).isCompleted(CoreTour.ID))
        }

        // Reopen the manual guide under the same external token. The token effect must be
        // idempotent; it must not dismiss the newly resumed session on every recomposition.
        rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
        waitForTag("tour_guide")
        rule.onNodeWithTag("tour_resume_core").assertIsDisplayed().performClick()
        waitForTag("tour_step_tools")
        rule.onNodeWithTag("tour_coach").assertIsDisplayed()
        rule.waitForIdle()
        rule.onNodeWithTag("tour_coach").assertIsDisplayed()
    }


    private fun composeApp(
        pendingNavigationRoute: ExternalRouteResolution = ExternalRouteResolution.NoRoute,
        allowDailySupportPrompt: Boolean = false,
        allowAutomaticWalkthrough: Boolean = allowDailySupportPrompt,
        normalLaunchId: Int = 0
    ) {
        rule.setContent {
            StandardEnglish411 {
                LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                    LlamaApp(
                        pendingNavigationRoute = pendingNavigationRoute,
                        allowDailySupportPrompt = allowDailySupportPrompt,
                        allowAutomaticWalkthrough = allowAutomaticWalkthrough,
                        normalLaunchId = normalLaunchId
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openGuide() {
        rule.onNodeWithTag("soft_studio_tour").assertIsDisplayed().performClick()
        waitForTag("tour_guide")
    }

    private fun assertRoute(route: String) {
        waitForTag("studio_route_$route")
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) { hasTag(tag) }
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForText(resId: Int) {
        val text = englishString(resId)
        rule.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            rule.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun hasTag(tag: String): Boolean =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun englishString(resId: Int): String {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.US)
        }
        return rule.activity.createConfigurationContext(configuration).getString(resId)
    }

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
        CompositionLocalProvider(
            // Keep both the localized resources and the real host owners available to feature launchers.
            LocalActivity provides rule.activity,
            LocalActivityResultRegistryOwner provides rule.activity,
            LocalFullyDrawnReporterOwner provides rule.activity,
            LocalOnBackPressedDispatcherOwner provides rule.activity,
            LocalConfiguration provides configuration,
            LocalContext provides localizedContext,
            LocalResources provides localizedContext.resources,
            LocalWindowInfo provides fixtureWindow(
                VIEWPORT_WIDTH_DP,
                VIEWPORT_HEIGHT_DP,
                physicalDensity
            ),
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
                null, MissingPreference -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value for $key: ${value::class}")
            }
        }
        check(editor.commit()) { "Could not restore walkthrough lifecycle preferences" }
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
        const val HAS_COMPLETED_WELCOME = "has_completed_welcome"
        const val AUTOMATIC_ELIGIBLE = "walkthrough_automatic_eligible"
        const val CORE_PROGRESS = "walkthrough_progress:core"
        const val CORE_COMPLETED = "walkthrough_completed:core"
        const val SUPPORT_LAST_SHOWN = "support_last_shown_epoch_day"
        const val VIEWPORT_WIDTH_DP = 411
        const val VIEWPORT_HEIGHT_DP = 800
        const val WAIT_TIMEOUT_MS = 5_000L
        val PREFERENCE_KEYS = listOf(
            HAS_COMPLETED_WELCOME,
            AUTOMATIC_ELIGIBLE,
            CORE_PROGRESS,
            CORE_COMPLETED,
            SUPPORT_LAST_SHOWN
        )
    }
}
