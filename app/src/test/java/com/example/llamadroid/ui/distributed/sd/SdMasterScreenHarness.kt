package com.example.llamadroid.ui.distributed.sd

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.llamadroid.R
import com.example.llamadroid.data.AppVisualStyle
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.service.SDConfig
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDUpscaleConfig
import com.example.llamadroid.service.VideoGenerationConfig
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder

/** Records what the screen asked the generation services to do. */
internal class FakeSdMasterLauncher : SdMasterLauncher {
    val images = mutableListOf<SDConfig>()
    val upscales = mutableListOf<SDUpscaleConfig>()
    val videos = mutableListOf<VideoGenerationConfig>()
    val cancelledImage = mutableListOf<SDMode>()
    val cancelledVideo = mutableListOf<VideoGenerationMode>()

    override fun startImage(config: SDConfig) { images += config }
    override fun startUpscale(config: SDUpscaleConfig) { upscales += config }
    override fun startVideo(config: VideoGenerationConfig) { videos += config }
    override fun cancelImage(mode: SDMode) { cancelledImage += mode }
    override fun cancelVideo(mode: VideoGenerationMode) { cancelledVideo += mode }
}

/**
 * Hosts the real [SdMasterScreen] at 360 dp with the user's pixel theme and a fake service seam.
 * The Room database is the real one (Robolectric); the singleton is dropped around every test.
 */
internal class SdMasterScreenHarness(
    private val rule: AndroidComposeTestRule<*, ComponentActivity>,
    private val tmp: TemporaryFolder
) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val launcher = FakeSdMasterLauncher()
    var navController: NavHostController? = null
    private var hosted by mutableStateOf(true)

    private fun resetDatabaseSingleton() {
        // AppDatabase caches its instance statically and Robolectric gives each test a fresh data directory, so
        // drop the stale instance. It is not closed: a Room flow of the previous composition may still be finishing
        // a query on the IO executor, and closing under it fails the next test with "connection pool has been closed".
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)
    }

    fun setUp() {
        com.example.llamadroid.service.SdDistributedRunMonitor.clear()
        resetDatabaseSingleton()
    }

    fun tearDown() {
        // Dispose the composition (stops Room flow collection) before dropping the database instance.
        rule.runOnUiThread { hosted = false }
        rule.waitForIdle()
        com.example.llamadroid.service.SdDistributedRunMonitor.clear()
        resetDatabaseSingleton()
    }

    fun host(mode: SdMasterMode) {
        rule.setContent {
            if (hosted) {
                val nav = rememberNavController()
                navController = nav
                LlamaDroidTheme(darkTheme = false, visualStyle = AppVisualStyle.PIXEL) {
                    CompositionLocalProvider(LocalSdMasterLauncher provides launcher) {
                        NavHost(nav, startDestination = Screen.SdDistributedImages.route) {
                            composable(Screen.SdDistributedImages.route) { SdMasterScreen(nav, mode) }
                            composable(Screen.SdDistributedNetwork.route) { Box {} }
                            composable(Screen.SdDistributedGallery.route) { Box {} }
                        }
                    }
                }
            }
        }
    }

    fun currentRoute(): String? = rule.runOnUiThread { navController?.currentBackStackEntry?.destination?.route }

    fun string(id: Int): String = rule.activity.getString(id)

    fun hasText(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Waits until the ViewModel finished its first load (the loading line is gone). */
    fun awaitLoaded() {
        val loading = string(R.string.sd_r8_w08_loading)
        rule.waitUntil(10_000) { !hasText(loading) }
        rule.waitForIdle()
    }

    /** An image model on disk + optionally one enabled Helper, prompt "a cat". */
    fun seedImageSetup(withHelper: Boolean) {
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
                    imagePrompt = "a cat"
                )
            )
        }
    }
}
