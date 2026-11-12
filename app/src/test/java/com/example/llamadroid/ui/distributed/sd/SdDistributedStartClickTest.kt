package com.example.llamadroid.ui.distributed.sd

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdRunKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * Regression gate for "tapping Start work does nothing": hosts the real distributed master screen
 * and clicks the real button through pointer input, so anything covering it fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SdDistributedStartClickTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private var navController: NavHostController? = null
    private var hosted by mutableStateOf(true)

    private fun resetDatabaseSingleton() {
        // AppDatabase caches its instance statically and Robolectric gives each test a fresh data directory, so
        // drop the stale instance. It is not closed: a Room flow of the previous composition may still be finishing
        // a query on the IO executor, and closing under it fails the next test with "connection pool has been closed".
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        SdDistributedRunMonitor.clear()
        resetDatabaseSingleton()
    }

    @After
    fun tearDown() {
        // Dispose the composition (stops Room flow collection) before dropping the database instance.
        rule.runOnUiThread { hosted = false }
        rule.waitForIdle()
        SdDistributedRunMonitor.clear()
        resetDatabaseSingleton()
    }

    private fun host(mode: SdMasterMode) {
        rule.setContent {
            if (hosted) {
                val nav = rememberNavController()
                navController = nav
                NavHost(nav, startDestination = Screen.SdDistributedImages.route) {
                    composable(Screen.SdDistributedImages.route) { SdDistributedMasterContent(nav, mode) }
                    composable(Screen.SdDistributedNetwork.route) { Box {} }
                }
            }
        }
    }

    private fun currentRoute(): String? = rule.runOnUiThread { navController?.currentBackStackEntry?.destination?.route }

    private fun seedImageSetup(withHelper: Boolean) {
        val model = tmp.newFile("sd15-test.safetensors").apply { writeText("x") }
        val db = AppDatabase.getDatabase(app)
        runBlocking(Dispatchers.IO) {
            db.modelDao().insertModel(
                ModelEntity(
                    filename = model.name,
                    path = model.absolutePath,
                    sizeBytes = model.length(),
                    type = ModelType.SD_CHECKPOINT,
                    repoId = "local/sd15-test",
                    isDownloaded = true,
                    sdCapabilities = "txt2img,img2img",
                    sdFamily = "checkpoint",
                    sdVariant = "sd1"
                )
            )
            if (withHelper) {
                db.sdDistributedDao().upsertWorker(
                    SdDistributedWorkerEntity(host = "10.2.0.3", port = 50062, ramMB = 4096, isEnabled = true)
                )
            }
            db.sdDistributedDao().upsertMasterSettings(
                SdDistributedMasterSettingsEntity(
                    id = 1,
                    imageModelPath = model.absolutePath,
                    imagePrompt = "a cat",
                    imageExpanded = true
                )
            )
        }
    }

    private fun awaitStart(): String {
        val start = rule.activity.getString(R.string.sd_dist_start_work)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty() }
        return start
    }

    private fun clickImageStartAndAssertRun() {
        val start = awaitStart()
        // Wait for the settings flow to be applied to the draft state (prompt field shows the seeded value).
        rule.waitUntil(10_000) { rule.onAllNodesWithText("a cat").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(start).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        val intent = shadowOf(app).nextStartedService
        assertNotNull("Start did not start a service; toast=${ShadowToast.getTextOfLatestToast()}", intent)
        assertEquals("com.example.llamadroid.action.START_SD_GENERATION", intent.action)
        assertTrue(intent.getBooleanExtra("extra_sd_use_distributed_holder", false))
        rule.waitUntil(5_000) { currentRoute() == Screen.SdDistributedNetwork.route }
        assertEquals(Screen.SdDistributedNetwork.route, currentRoute())
    }

    @Test
    fun imagesEmptyDatabaseShowsToastAndBand() {
        host(SdMasterMode.IMAGES)
        val message = rule.activity.getString(R.string.sd_dist_error_select_image_model)
        val start = awaitStart()
        rule.onNodeWithText(start).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        assertEquals(message, ShadowToast.getTextOfLatestToast())
        rule.waitUntil(5_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        // Shown twice: the status panel at the top and the band directly above the Start button.
        assertTrue(rule.onAllNodesWithText(message).fetchSemanticsNodes().size >= 2)
        assertEquals(Screen.SdDistributedImages.route, currentRoute())
    }

    @Test
    fun imagesValidSetupStartsServiceAndNavigates() {
        seedImageSetup(withHelper = false)
        host(SdMasterMode.IMAGES)
        clickImageStartAndAssertRun()
    }

    @Test
    fun imagesValidSetupWithRemoteHelperStartsServiceAndNavigates() {
        seedImageSetup(withHelper = true)
        host(SdMasterMode.IMAGES)
        clickImageStartAndAssertRun()
        val session = SdDistributedRunMonitor.session.value
        assertNotNull("Run monitor session was not begun", session)
        assertEquals(SdRunKind.IMAGE, session!!.kind)
        assertEquals("TXT2IMG", session.mode)
    }

    @Test
    fun videoEmptyDatabaseStartIsEnabledAndGivesToast() {
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(app).sdDistributedDao()
                .upsertMasterSettings(SdDistributedMasterSettingsEntity(id = 1, videoExpanded = true))
        }
        host(SdMasterMode.VIDEO)
        val start = awaitStart()
        rule.onNodeWithText(start).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        val toast = ShadowToast.getTextOfLatestToast()
        assertNotNull("Video Start click produced no toast", toast)
        assertTrue(toast.isNotBlank())
        assertEquals(Screen.SdDistributedImages.route, currentRoute())
    }

    /** Robolectric has no SD binary, so a valid video setup is blocked by the binary probe with a visible toast. */
    @Test
    fun videoValidSetupWithoutBinaryShowsBinaryToastAndStartsNoService() {
        val model = tmp.newFile("video-diffusion.gguf").apply { writeText("x") }
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(app).sdDistributedDao().upsertMasterSettings(
                SdDistributedMasterSettingsEntity(
                    id = 1,
                    videoExpanded = true,
                    videoPrompt = "a cat running",
                    videoModelPath = model.absolutePath
                )
            )
        }
        host(SdMasterMode.VIDEO)
        val start = awaitStart()
        rule.onNodeWithText(start).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()

        val toast = ShadowToast.getTextOfLatestToast()
        val allowed = listOf(
            rule.activity.getString(R.string.video_controls_binary_unavailable_selection),
            rule.activity.getString(R.string.video_controls_binary_checking)
        )
        assertTrue("Unexpected toast: $toast", toast in allowed)
        assertEquals(null, shadowOf(app).nextStartedService)
        assertEquals(Screen.SdDistributedImages.route, currentRoute())
    }
}
