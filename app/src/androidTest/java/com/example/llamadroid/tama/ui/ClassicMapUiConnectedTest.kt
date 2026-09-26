package com.example.llamadroid.tama.ui

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.R
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.presentation.projectWorldState
import com.example.llamadroid.tama.world.ui.WORLD_HOME_EXIT_TEST_TAG
import com.example.llamadroid.tama.world.ui.WorldScreen
import com.example.llamadroid.tama.world.ui.WorldUiCallbacks
import com.example.llamadroid.tama.world.ui.WorldUiCommand
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded compact-screen contracts for the classic map and simulation exit. */
@RunWith(AndroidJUnit4::class)
class ClassicMapUiConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun englishDevelopmentTileIsReachableAndOpensSimulation() = map("en")

    @Test fun spanishDevelopmentTileIsReachableAndOpensSimulation() = map("es")

    @Test fun simulationWorldHasVisibleReturnHomeAction() {
        val commands = mutableListOf<WorldUiCommand>()
        var systemBackExits = 0
        val worldUi = projectWorldState(WorldGenerator.generate(404L))
        compose.mainClock.autoAdvance = false
        compact("en") {
            WorldScreen(
                state = worldUi,
                callbacks = WorldUiCallbacks { commands += it },
                loadAssets = false,
                isSimulationActive = true,
                exitShortcutLabelRes = R.string.tama_classic_map_return_home,
                homeActionLabelRes = R.string.tama_classic_map_return_home,
                onSystemBack = { systemBackExits++ }
            )
        }
        compose.mainClock.advanceTimeByFrame()
        scrollClassicWorldExitIntoView(compose)
        compose.onNodeWithTag(WORLD_HOME_EXIT_TEST_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            compose.mainClock.advanceTimeByFrame()
            commands.isNotEmpty()
        }
        assertEquals(listOf(WorldUiCommand.CloseWorld), commands)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertEquals(1, systemBackExits)
    }

    private fun map(language: String) {
        val context = localized(language)
        var launches = 0
        compact(language) {
            val locations = classicMapLocations(context)
            TamaMapView(
                cityName = context.getString(R.string.tama_city_hometown),
                locations = locations,
                currentLocation = locations.firstOrNull(),
                discoveredLocationIds = setOf("fixed_0_0"),
                onLocationClick = {},
                onOpenSimulation = { launches++ }
            )
        }
        compose.onNodeWithTag(CLASSIC_MAP_SIMULATION_TILE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, launches)
        compose.onNodeWithContentDescription(context.getString(R.string.tama_classic_map_simulated_world_title))
            .assertIsDisplayed()
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

/** The world has a continuous frame clock; settle each bounded scroll explicitly. */
internal fun scrollClassicWorldExitIntoView(compose: ComposeContentTestRule) {
    val target = compose.onNodeWithTag(WORLD_HOME_EXIT_TEST_TAG)
    val verticalOwner = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    ).onFirst()
    for (attempt in 0 until 8) {
        val node = target.fetchSemanticsNode()
        if (target.isDisplayed() && node.boundsInRoot.height >= node.size.height - 1f) break
        val viewport = verticalOwner.fetchSemanticsNode().boundsInRoot
        val delta = node.positionInRoot.y + node.size.height / 2f - viewport.center.y
        verticalOwner.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, delta)
        }
        compose.mainClock.advanceTimeBy(400L)
    }
    target.assertIsDisplayed()
}
