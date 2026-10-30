package com.example.llamadroid.tama.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.example.llamadroid.tama.data.LegacyLocationAliases
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One real TamaScreen room/map/menu walk, kept bounded. */
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
    fun roomMapRoomAndMenuStayClassic() {
        val roomMapLabel = compose.activity.getString(R.string.soft_studio_tama_map)
        val roomLabel = compose.activity.getString(R.string.soft_studio_tama_room)
        val menuLabel = compose.activity.getString(R.string.tama_btn_menu)
        val statusLabel = compose.activity.getString(R.string.tama_menu_status)

        compose.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    TamaScreen(
                        navController = rememberNavController(),
                        gameEngine = engine,
                        settingsRepo = settings,
                        agentService = agentService
                    )
                }
            }
        }

        // The authored room is the landing surface; the toggle shows the classic map.
        // The room animates continuously, so keep a controlled clock.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(roomMapLabel).performClick()
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithContentDescription(
            classicMapLocations(compose.activity).first().name
        ).assertIsDisplayed()
        captureQaScreenshot("classic-map.png")

        compose.onNodeWithContentDescription(roomLabel).performClick()
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithTag("soft_studio_tama_room_viewport").assertIsDisplayed()
        captureQaScreenshot("returned-room.png")
        assertEquals(
            LegacyLocationAliases.HOME,
            runBlocking { database.tamaDao().getActivePet()?.currentLocationId }
        )

        // The menu lists only classic entries.
        compose.onAllNodesWithContentDescription(menuLabel).onFirst().performClick()
        compose.mainClock.advanceTimeBy(400L)
        compose.onNodeWithText(statusLabel).assertIsDisplayed()
        assertEquals(
            LegacyLocationAliases.HOME,
            runBlocking { database.tamaDao().getActivePet()?.currentLocationId }
        )
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
}
