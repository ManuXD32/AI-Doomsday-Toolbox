package com.example.llamadroid.tama.world

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.presentation.projectBrainRuntimeState
import com.example.llamadroid.tama.world.presentation.projectWorldState
import com.example.llamadroid.tama.world.training.BrainRuntimeState
import com.example.llamadroid.tama.world.ui.*
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose layout at a short 320dp viewport and 1.6x font size, using isolated facts. */
@RunWith(AndroidJUnit4::class)
class WorldCompactUiConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val WORLD_SCROLL_STEP_PX = 480f
        const val WORLD_SCROLL_SETTLE_MS = 400L
        const val WORLD_SCROLL_ATTEMPTS = 8
    }

    @Test fun englishJournalKeepsEpisodesReachableBelowMemorySettings() = journal("en")
    @Test fun spanishJournalKeepsEpisodesReachableBelowMemorySettings() = journal("es")
    @Test fun englishBrainResourceControlsRemainReachable() = brain("en")
    @Test fun spanishBrainResourceControlsRemainReachable() = brain("es")
    @Test fun englishWorldNeedsRemainReachable() = world("en")
    @Test fun spanishWorldNeedsRemainReachable() = world("es")

    private fun journal(language: String) {
        val state = WorldJournalUiState(
            episodes = (0..29).map { index -> WorldEpisodeUi(
                id = "episode-$index", title = "Adventure $index", timeRange = "09:00–09:20",
                locationLabel = "Meadow", biome = WorldBiome.MEADOW,
                importance = WorldEventImportance.MAJOR, summary = "Verified adventure. ".repeat(20),
                events = emptyList()
            ) },
            pendingMemories = (0..9).map { index -> WorldMemoryCandidateUi(
                episodeId = "pending-$index", title = "Pending adventure $index",
                summary = "Evidence waiting for a summary. ".repeat(20), timeRange = "09:00",
                importance = WorldEventImportance.MAJOR, memoryStatus = "PENDING"
            ) }
        )
        compact(language) {
            WorldJournalScreen(state, WorldJournalCallbacks(onBack = {}, onSelectEpisode = {}, onFilterChanged = {}))
        }
        scrollLazyTo("Adventure 29")
    }

    private fun brain(language: String) {
        val context = localized(language)
        val target = context.getString(R.string.tama_world_brain_resources_title)
        val callbacks = BrainTrainingCallbacks(
            onBack = {}, onStart = {}, onPause = {}, onResume = {}, onSpeedChanged = {},
            onProfileSelected = {}, onCheckpointSaved = {}, onEvaluate = {}, onAdoptCandidate = {},
            onKeepCurrent = {}, onRestoreCheckpoint = {}, onSafeAutonomyChanged = {}, onOpenJournal = {}
        )
        compact(language) { BrainTrainingScreen(projectBrainRuntimeState(BrainRuntimeState()), callbacks) }
        scrollLazyTo(target)
        compose.onNodeWithText(target).performClick()
        compose.onNodeWithText(context.getString(R.string.tama_world_brain_resources_body))
            .performScrollTo().assertIsDisplayed()
    }

    private fun world(language: String) {
        val context = localized(language)
        val target = context.getString(R.string.tama_world_stat_curiosity)
        val ui = projectWorldState(WorldGenerator.generate(404L))
        // No animation clock is needed for layout assertions; renderer performance has its own probe.
        compose.mainClock.autoAdvance = false
        compact(language) { WorldScreen(ui, WorldUiCallbacks {}, loadAssets = false) }
        compose.mainClock.advanceTimeByFrame()
        scrollWorldTo(target)
    }

    /**
     * [performScrollTo] repeatedly starts an animated ScrollBy action. With the
     * test clock frozen, its internal retry loop cannot observe progress. Send
     * bounded real semantics actions instead and advance each animation before
     * checking visibility, so an invalid layout fails rather than hanging.
     */
    private fun scrollWorldTo(text: String) {
        val target = compose.onNodeWithText(text)
        if (!target.isDisplayed()) {
            val scrollContainer = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
            ).onFirst()
            var attempts = 0
            while (attempts < WORLD_SCROLL_ATTEMPTS && !target.isDisplayed()) {
                attempts += 1
                scrollContainer.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                    scrollBy(0f, WORLD_SCROLL_STEP_PX)
                }
                compose.mainClock.advanceTimeBy(WORLD_SCROLL_SETTLE_MS)
            }
        }
        target.assertIsDisplayed()
    }

    private fun scrollLazyTo(text: String) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .onFirst().performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun localized(language: String): Context {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            fontScale = 1.6f
        }
        return compose.activity.createConfigurationContext(configuration)
    }

    private fun compact(language: String, content: @Composable () -> Unit) {
        val context = localized(language)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(context.resources.displayMetrics.density, 1.6f)
            ) {
                LlamaDroidTheme(dynamicColor = false) {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) { content() }
                }
            }
        }
    }
}
