package com.example.llamadroid.ui.walkthrough

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.WalkthroughPreferences
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlin.math.roundToInt
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class WalkthroughTestViewport(
    val widthDp: Int,
    val heightDp: Int,
    val language: String,
    val fontScale: Float,
    val darkTheme: Boolean = false
)

private val representativeWalkthroughViewports = buildList {
    // Keep the coach fixture bounded while covering every requested combination:
    // five widths, portrait and landscape heights, both locales/themes, and three scales.
    val dimensions = listOf(
        // Portrait profiles.
        320 to 640,
        360 to 720,
        411 to 820,
        600 to 960,
        840 to 1200,
        // Landscape profiles.
        320 to 240,
        360 to 240,
        411 to 300,
        600 to 360,
        840 to 480
    )
    for ((width, height) in dimensions) {
        for (language in listOf("en", "es")) {
            for (darkTheme in listOf(false, true)) {
                for (fontScale in listOf(1f, 1.3f, 2f)) {
                    add(WalkthroughTestViewport(width, height, language, fontScale, darkTheme))
                }
            }
        }
    }
}

class WalkthroughUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun newState(): WalkthroughState {
        val prefs = rule.activity.getSharedPreferences(
            "walkthrough_ui_test_${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        return WalkthroughState(WalkthroughPreferences(prefs))
    }

    @Test
    fun guideShowsCoreAndAllTenChapterCards() {
        val state = newState()
        var startRequest: Pair<String, Boolean>? = null

        rule.setContent {
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                WalkthroughGuide(
                    state = state,
                    onBack = {},
                    onStart = { chapterId, resume ->
                        startRequest = chapterId to resume
                        state.start(chapterId, resume)
                    }
                )
            }
        }

        rule.onNodeWithTag("tour_chapter_core").assertIsDisplayed()
        rule.onNodeWithTag("tour_start_core").performClick()
        rule.runOnIdle {
            assertEquals(CoreTour.ID to false, startRequest)
            assertEquals(TourSession(CoreTour.ID, 0), state.session)
        }

        assertEquals(10, WalkthroughCatalog.chapters.size)
        WalkthroughCatalog.chapters.forEachIndexed { index, chapter ->
            rule.onNodeWithTag("tour_guide").performScrollToIndex(index + 3)
            rule.onNodeWithTag("tour_chapter_${chapter.id}").assertIsDisplayed()
        }
    }

    @Test
    fun coachCloseDismissesWithoutChangingTheCurrentRoute() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)
        var currentRoute by mutableStateOf("dashboard")
        var openedRoute: String? by mutableStateOf(null)
        val registry = WalkthroughTargets()

        rule.setContent {
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                WalkthroughCoach(
                    state = state,
                    registry = registry,
                    currentRoute = currentRoute,
                    onOpen = { openedRoute = it }
                )
            }
        }

        rule.onNodeWithTag("tour_close").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertNull(state.session)
            assertEquals("dashboard", currentRoute)
            assertNull(openedRoute)
        }
    }

    @Test
    fun coachPreviousNextAndSkipControlsMoveTheSession() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)
        val registry = WalkthroughTargets()

        rule.setContent {
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                WalkthroughCoach(
                    state = state,
                    registry = registry,
                    currentRoute = "dashboard",
                    onOpen = {}
                )
            }
        }

        rule.onNodeWithTag("tour_next").performClick()
        rule.runOnIdle { assertEquals(TourSession(CoreTour.ID, 1), state.session) }
        rule.onNodeWithTag("tour_previous").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(TourSession(CoreTour.ID, 0), state.session) }

        rule.onNodeWithTag("tour_next").performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.tour_skip_step)).assertIsDisplayed()
        rule.onNodeWithTag("tour_next").performClick()
        rule.runOnIdle { assertEquals(TourSession(CoreTour.ID, 2), state.session) }
    }

    @Test
    fun coachPreviewCanBeOpenedAndDismissed() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)

        rule.setContent {
            LlamaDroidTheme(darkTheme = false, dynamicColor = false) {
                WalkthroughCoach(
                    state = state,
                    registry = WalkthroughTargets(),
                    currentRoute = "dashboard",
                    onOpen = {}
                )
            }
        }

        rule.onNodeWithTag("tour_preview").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("tour_preview_close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("tour_preview_close").assertDoesNotExist()
        rule.onNodeWithTag("tour_close").assertIsDisplayed()
    }

    @Test
    fun compactPreviewDialogKeepsCloseAndBodyReachable() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)
        val viewport = WalkthroughTestViewport(320, 320, "es", 2f, darkTheme = true)

        rule.setContent {
            TestViewport(viewport) {
                LlamaDroidTheme(darkTheme = viewport.darkTheme, dynamicColor = false) {
                    WalkthroughCoach(
                        state = state,
                        registry = WalkthroughTargets(),
                        currentRoute = "dashboard",
                        onOpen = {}
                    )
                }
            }
        }

        rule.onNodeWithTag("tour_preview").assertIsDisplayed().performClick()
        rule.onNodeWithTag("tour_preview_close").assertIsDisplayed()
        val resources = localizedResources(viewport)
        rule.onNodeWithText(resources.getString(R.string.tour_preview_caption))
            .performScrollTo().assertIsDisplayed()
        val body = resources.getString(CoreTour.steps.first().bodyRes)
        rule.onAllNodesWithText(body, useUnmergedTree = true).onLast()
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("tour_preview_close").performClick()
        rule.onNodeWithTag("tour_preview_close").assertDoesNotExist()
    }

    @Test
    fun coachControlsRemainReachableAcrossLocalesThemesWidthsScalesAndOrientations() {
        val state = newState()
        var viewport by mutableStateOf(representativeWalkthroughViewports.first())
        var currentRoute by mutableStateOf("dashboard")
        val registry = WalkthroughTargets()

        rule.setContent {
            TestViewport(viewport) {
                LlamaDroidTheme(darkTheme = viewport.darkTheme, dynamicColor = false) {
                    WalkthroughCoach(
                        state = state,
                        registry = registry,
                        currentRoute = currentRoute,
                        onOpen = {}
                    )
                }
            }
        }

        assertEquals(120, representativeWalkthroughViewports.size)
        representativeWalkthroughViewports.forEach { profile ->
            rule.runOnUiThread {
                viewport = profile
                currentRoute = "dashboard"
                state.start(CoreTour.ID, resume = false)
            }
            rule.waitForIdle()
            rule.onNodeWithTag("tour_close").assertIsDisplayed()
            listOf("tour_close", "tour_next").forEach { tag ->
                val control = rule.onNodeWithTag(tag).fetchSemanticsNode()
                val minimumPx = with(control.layoutInfo.density) { 48.dp.toPx() }
                // Material icons may draw at 40dp while exposing a 48dp hit area.
                val touchBounds = control.touchBoundsInRoot
                assertTrue("$profile: $tag must keep a 48dp touch target",
                    touchBounds.width >= minimumPx - 1f &&
                        touchBounds.height >= minimumPx - 1f)
            }
            assertPreviewReachableAcrossCoachModes()
            val localizedNext = localizedResources(profile).getString(R.string.tour_next)
            if (profile.language == "es") assertEquals("Siguiente", localizedNext)
            val textNext = rule.onAllNodesWithText(localizedNext).fetchSemanticsNodes().isNotEmpty()
            val iconNext = rule.onAllNodesWithContentDescription(localizedNext)
                .fetchSemanticsNodes().isNotEmpty()
            assertTrue("localized next decision must be visible as text or an icon label", textNext || iconNext)
            rule.onNodeWithTag("tour_next").assertIsDisplayed().performClick()
            // Previous/Skip can wrap, so recheck the preview in the remaining viewport.
            rule.onNodeWithTag("tour_previous").assertIsDisplayed()
            assertPreviewReachableAcrossCoachModes()
            listOf("tour_previous", "tour_preview", "tour_next").forEach { tag ->
                val control = rule.onNodeWithTag(tag).fetchSemanticsNode()
                val minimumPx = with(control.layoutInfo.density) { 48.dp.toPx() }
                val touchBounds = control.touchBoundsInRoot
                assertTrue("$profile second step: $tag must keep a 48dp touch target",
                    touchBounds.width >= minimumPx - 1f &&
                        touchBounds.height >= minimumPx - 1f)
            }
        }

        rule.runOnIdle { assertTrue(state.session != null) }
    }

    /**
     * The production coach chooses its compact/full branch from LocalWindowInfo and density.
     * Those values are supplied by TestViewport, but the host window can still make the
     * effective branch differ from the requested profile. Inspect the semantics tree instead
     * of duplicating that branch condition in the fixture: scroll only when the rendered
     * preview actually has a parent with the action that performScrollTo requires.
     */
    private fun assertPreviewReachableAcrossCoachModes() {
        val preview = rule.onNodeWithTag("tour_preview")
        var parent = preview.fetchSemanticsNode().parent
        while (parent != null && !parent.config.contains(SemanticsActions.ScrollBy)) {
            parent = parent.parent
        }
        if (parent != null) preview.performScrollTo()
        preview.assertIsDisplayed()
    }

    @Test
    fun compactCoachIsBoundedAndPreviewKeepsMissingTargetRecoveryReachable() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)
        val viewport = WalkthroughTestViewport(320, 320, "es", 2f, darkTheme = true)
        val registry = WalkthroughTargets()
        var openedRoute: String? by mutableStateOf(null)

        rule.setContent {
            TestViewport(viewport) {
                LlamaDroidTheme(darkTheme = viewport.darkTheme, dynamicColor = false) {
                    WalkthroughCoach(
                        state = state,
                        registry = registry,
                        currentRoute = "unrelated_route",
                        onOpen = { openedRoute = it }
                    )
                }
            }
        }

        val coach = rule.onNodeWithTag("tour_coach").assertIsDisplayed().fetchSemanticsNode()
        val maxHeightPx = with(coach.layoutInfo.density) { 56.dp.toPx() }
        assertTrue("compact coach must stay at or below 56dp", coach.boundsInRoot.height <= maxHeightPx + 1f)
        rule.onNodeWithContentDescription(
            localizedResources(viewport).getString(R.string.tour_show_guide)
        ).assertIsDisplayed()
        listOf("tour_next", "tour_preview", "tour_close").forEach { tag ->
            val control = rule.onNodeWithTag(tag).fetchSemanticsNode()
            val minSizePx = with(control.layoutInfo.density) { 48.dp.toPx() }
            val touchBounds = control.touchBoundsInRoot
            assertTrue("$tag must keep a 48dp touch target height", touchBounds.height >= minSizePx - 1f)
            assertTrue("$tag must keep a 48dp touch target width", touchBounds.width >= minSizePx - 1f)
        }

        rule.onNodeWithTag("tour_preview").performClick()
        rule.onNodeWithTag("tour_open_tool").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("tour_retry").performScrollTo().assertIsDisplayed()
        val retryBefore = registry.retryKey
        rule.onNodeWithTag("tour_retry").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(registry.retryKey > retryBefore) }
        rule.onNodeWithTag("tour_preview_close").assertDoesNotExist()
        rule.onNodeWithTag("tour_next").assertIsDisplayed()
        rule.onNodeWithTag("tour_preview").performClick()
        rule.onNodeWithTag("tour_open_tool").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(CoreTour.steps.first().route, openedRoute) }
        rule.onNodeWithTag("tour_preview_close").assertDoesNotExist()
        rule.onNodeWithTag("tour_next").assertIsDisplayed()
    }

    @Test
    fun keyboardVisibleCoachKeepsDismissAndGuideControlsReachable() {
        val state = newState()
        state.start(CoreTour.ID, resume = false)
        val viewport = WalkthroughTestViewport(360, 640, "es", 1.3f, darkTheme = true)

        rule.setContent {
            TestViewport(viewport) {
                LlamaDroidTheme(darkTheme = viewport.darkTheme, dynamicColor = false) {
                    WalkthroughCoach(
                        state = state,
                        registry = WalkthroughTargets(),
                        currentRoute = "dashboard",
                        onOpen = {}
                    )
                }
            }
        }

        rule.waitForIdle()
        dispatchImeInsets(bottomPx = 320)
        rule.waitForIdle()
        rule.onNodeWithTag("tour_close").assertIsDisplayed()
        rule.onNodeWithTag("tour_show_guide").assertIsDisplayed()
        rule.onNodeWithTag("tour_next").assertDoesNotExist()

        // Returning to the full coach keeps the decision control available after the IME closes.
        rule.onNodeWithTag("tour_show_guide").performClick()
        dispatchImeInsets(bottomPx = 0)
        rule.waitForIdle()
        rule.onNodeWithTag("tour_close").assertIsDisplayed()
        rule.onNodeWithTag("tour_next").assertIsDisplayed()
    }

    private fun dispatchImeInsets(bottomPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.ime(),
                Insets.of(0, 0, 0, bottomPx)
            )
            .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
            .build()
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
    }
}

@Composable
private fun TestViewport(viewport: WalkthroughTestViewport, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val baseDensity = LocalDensity.current
        val physicalDensity = baseDensity.density
        val scaledDensity = minOf(
            maxWidth.value * physicalDensity / viewport.widthDp,
            maxHeight.value * physicalDensity / viewport.heightDp
        )
        val configuration = Configuration(LocalConfiguration.current).apply {
            screenWidthDp = viewport.widthDp
            screenHeightDp = viewport.heightDp
            fontScale = viewport.fontScale
            orientation = if (viewport.widthDp > viewport.heightDp) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            setLocale(Locale.forLanguageTag(viewport.language))
        }
        val localizedContext = LocalContext.current.createConfigurationContext(configuration)
        val window = fixtureWindow(viewport.widthDp, viewport.heightDp, scaledDensity)
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalContext provides localizedContext,
            LocalResources provides localizedContext.resources,
            LocalWindowInfo provides window,
            LocalDensity provides Density(scaledDensity, viewport.fontScale)
        ) {
            Box(Modifier.requiredSize(viewport.widthDp.dp, viewport.heightDp.dp)) {
                content()
            }
        }
    }
}

private fun localizedResources(viewport: WalkthroughTestViewport) =
    Configuration().apply { setLocale(Locale.forLanguageTag(viewport.language)) }.let { configuration ->
        // Read from the same localized context supplied to Compose so assertions cover the
        // actual resource source used by stringResource, including Spanish variants.
        androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(configuration).resources
    }

private fun fixtureWindow(widthDp: Int, heightDp: Int, density: Float) = object : WindowInfo {
    override val isWindowFocused = true
    override val containerSize = IntSize(
        (widthDp * density).roundToInt(),
        (heightDp * density).roundToInt()
    )
}
