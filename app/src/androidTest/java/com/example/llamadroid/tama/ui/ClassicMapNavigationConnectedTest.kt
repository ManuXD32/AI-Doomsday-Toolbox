package com.example.llamadroid.tama.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.ui.WORLD_HOME_EXIT_TEST_TAG
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One real TamaScreen route walk, kept bounded and free of physical-arrival polling. */
@RunWith(AndroidJUnit4::class)
class ClassicMapNavigationConnectedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: TamaDatabase
    private lateinit var farmRepository: FarmRepository
    private lateinit var settings: SettingsRepository
    private lateinit var engine: TamaGameEngine
    private lateinit var agentScope: CoroutineScope
    private lateinit var agentService: TamaAgentService

    @Before
    fun setUp() {
        val context = compose.activity
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        farmRepository = FarmRepository(database.farmDao(), context)
        settings = SettingsRepository(context)
        engine = TamaGameEngine(
            context = context,
            dao = database.tamaDao(),
            farmEngine = FarmEngine(farmRepository),
            farmRepository = farmRepository,
            settingsRepo = settings,
            petDatabase = database,
            observeAndTick = false
        )
        agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        agentService = TamaAgentService(
            context = context,
            dao = database.tamaDao(),
            settingsRepo = settings,
            ollamaService = OllamaService(context),
            scope = agentScope
        )
        runBlocking {
            database.tamaDao().savePet(
                PetMapper.toEntity(
                    TamaPet(
                        id = "classic-map-navigation",
                        name = "Pixel",
                        species = "dragon",
                        stage = GrowthStage.BABY,
                        currentLocationId = LegacyLocationAliases.HOME
                    )
                )
            )
            engine.loadPet()
        }
    }

    @After
    fun tearDown() {
        runBlocking { engine.close().join() }
        agentScope.cancel()
        database.close()
    }

    @Test
    fun roomClassicMapDevelopmentExitAndBrainBackStayClassic() {
        val roomMapLabel = compose.activity.getString(R.string.soft_studio_tama_map)
        val menuLabel = compose.activity.getString(R.string.tama_btn_menu)
        val brainLabel = compose.activity.getString(R.string.tama_world_shortcut_brain)

        compose.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    TamaScreen(
                        navController = rememberNavController(),
                        gameEngine = engine,
                        settingsRepo = settings,
                        agentService = agentService,
                        farmRepository = farmRepository
                    )
                }
            }
        }

        // Normal room -> classic map -> development tile.
        compose.onNodeWithContentDescription(roomMapLabel).performClick()
        compose.onNodeWithTag(CLASSIC_MAP_SIMULATION_TILE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        captureQaScreenshot("classic-map.png")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(CLASSIC_MAP_SIMULATION_TILE_TAG)
            .performClick()
        compose.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MS) {
            compose.mainClock.advanceTimeByFrame()
            engine.isSimulatedWorldActive &&
                compose.onAllNodesWithTag(WORLD_HOME_EXIT_TEST_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        scrollClassicWorldExitIntoView(compose)
        captureQaScreenshot("world-loaded.png")
        assertTrue("Entering through the tile must not be a no-op", engine.isSimulatedWorldActive)

        // Hardware Back is the immediate route-level return-home escape; it
        // does not wait for a physical WORLD arrival or autonomous command.
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MS) {
            compose.mainClock.advanceTimeByFrame()
            !engine.isSimulatedWorldActive &&
                compose.onAllNodesWithTag("soft_studio_tama_room_viewport")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithTag("soft_studio_tama_room_viewport").assertIsDisplayed()
        // The authored room also animates continuously. Keep a controlled
        // clock throughout the journey instead of waiting for animation idle.
        compose.mainClock.advanceTimeByFrame()
        captureQaScreenshot("returned-room.png")
        assertFalse(engine.isSimulatedWorldActive)
        assertEquals(
            LegacyLocationAliases.HOME,
            runBlocking { database.tamaDao().getActivePet()?.currentLocationId }
        )

        // Brain opened from the classic room stays outside the simulation and
        // its Back action closes directly to that room.
        compose.onAllNodesWithContentDescription(menuLabel).onFirst().performClick()
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithText(brainLabel).performScrollTo()
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithText(brainLabel).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MS) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("brain_active_policy")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("brain_header").assertIsDisplayed()
        compose.onNodeWithTag("brain_active_policy").assertIsDisplayed()
        captureQaScreenshot("brain-loaded.png")
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(timeoutMillis = ROUTE_TIMEOUT_MS) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("soft_studio_tama_room_viewport")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("soft_studio_tama_room_viewport").assertIsDisplayed()
        assertFalse(engine.isSimulatedWorldActive)
        assertEquals(
            LegacyLocationAliases.HOME,
            runBlocking { database.tamaDao().getActivePet()?.currentLocationId }
        )
        // The room is visible again; this assertion also guards against Back
        // reopening the map or remounting the world route.
        compose.onNodeWithContentDescription(roomMapLabel).assertIsDisplayed()
    }

    private fun captureQaScreenshot(fileName: String) {
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        )
        try {
            val directory = requireNotNull(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir("classic-map-qa")
            )
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create bounded classic-map QA screenshot directory"
            }
            File(directory, fileName).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write bounded classic-map QA screenshot $fileName"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROUTE_TIMEOUT_MS = 15_000L
    }
}
