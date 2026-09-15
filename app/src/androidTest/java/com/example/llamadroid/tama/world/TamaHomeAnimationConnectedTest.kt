package com.example.llamadroid.tama.world

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.ui.TamaPetSprite
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Connected proof that the home renderer uses the imported authored frame sheet. */
@RunWith(AndroidJUnit4::class)
class TamaHomeAnimationConnectedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nonEggHomeIdleAdvancesBetweenAuthoredFrames() {
        compose.mainClock.autoAdvance = false
        val context = compose.activity
        val pack = readProductionIdlePack()
        val frame0 = loadBitmap(pack.framePaths[0])
        val frame1 = loadBitmap(pack.framePaths[1])

        compose.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                Box(Modifier.size(260.dp)) {
                    TamaPetSprite(
                        pet = fixturePet(cycleFrozen = false),
                        action = "idle",
                        size = 256.dp,
                        modifier = Modifier.testTag(SPRITE_TAG)
                    )
                }
            }
        }
        compose.onNodeWithTag(SPRITE_TAG).assertExists()

        val first = awaitRenderedFrame(frame0)
        compose.mainClock.advanceTimeBy(pack.frameMs)
        val second = awaitRenderedFrame(frame1)
        assertTrue("The authored idle frames were not distinct", !first.sameAs(second))
        saveCapture(second, "home-dragon-baby-idle-frame1.png")

        frame0.recycle()
        frame1.recycle()
        first.recycle()
        second.recycle()
        check(File(context.filesDir, QA_DIRECTORY).isDirectory)
    }

    @Test
    fun frozenCycleKeepsTheAuthoredHomeFrameStable() {
        compose.mainClock.autoAdvance = false
        val pack = readProductionIdlePack()
        val frame0 = loadBitmap(pack.framePaths[0])

        compose.setContent {
            LlamaDroidTheme(dynamicColor = false) {
                Box(Modifier.size(260.dp)) {
                    TamaPetSprite(
                        pet = fixturePet(cycleFrozen = true),
                        action = "idle",
                        size = 256.dp,
                        modifier = Modifier.testTag(SPRITE_TAG)
                    )
                }
            }
        }
        compose.onNodeWithTag(SPRITE_TAG).assertExists()

        val first = awaitRenderedFrame(frame0)
        compose.mainClock.advanceTimeBy(pack.frameMs * 3L)
        val afterClockAdvance = captureSprite()
        assertTrue("A frozen cycle changed its rendered frame", first.sameAs(afterClockAdvance))
        saveCapture(afterClockAdvance, "home-dragon-baby-frozen.png")

        frame0.recycle()
        first.recycle()
        afterClockAdvance.recycle()
    }

    private fun fixturePet(cycleFrozen: Boolean): TamaPet = TamaPet(
        id = "connected-home-animation-pet",
        name = "Pixel",
        species = "dragon",
        stage = GrowthStage.BABY,
        cycleFrozen = cycleFrozen
    )

    private fun readProductionIdlePack(): ProductionIdlePack {
        val manifest = compose.activity.assets.open(MANIFEST_PATH).bufferedReader().use {
            JSONObject(it.readText())
        }
        val assets = manifest.getJSONArray("assets")
        var home: JSONObject? = null
        for (index in 0 until assets.length()) {
            val candidate = assets.getJSONObject(index)
            if (candidate.optString("id") == HOME_ASSET_ID) {
                home = candidate
                break
            }
        }
        val definition = requireNotNull(home) { "Missing $HOME_ASSET_ID in the production manifest" }
        assertTrue("Home sheet is not production-ready", definition.optBoolean("productionReady", false))
        assertEquals(256, definition.getInt("frameWidth"))
        assertEquals(256, definition.getInt("frameHeight"))
        assertEquals(4, definition.getInt("columns"))

        val clips = definition.getJSONArray("clips")
        var idle: JSONObject? = null
        for (index in 0 until clips.length()) {
            val candidate = clips.getJSONObject(index)
            if (candidate.optString("action").equals("IDLE", ignoreCase = true)) {
                idle = candidate
                break
            }
        }
        val idleClip = requireNotNull(idle) { "Missing IDLE clip for $HOME_ASSET_ID" }
        val framePathsJson = idleClip.getJSONArray("frameExportPaths")
        assertEquals(2, framePathsJson.length())
        assertEquals(640L, idleClip.getLong("frameMs"))
        return ProductionIdlePack(
            frameMs = idleClip.getLong("frameMs"),
            framePaths = List(framePathsJson.length()) { framePathsJson.getString(it) }
        )
    }

    private fun loadBitmap(path: String): Bitmap = compose.activity.assets.open(path).use { stream ->
        requireNotNull(BitmapFactory.decodeStream(stream)) { "Could not decode $path" }
    }

    private fun captureSprite(): Bitmap = compose.onNodeWithTag(SPRITE_TAG)
        .captureToImage()
        .asAndroidBitmap()
        .copy(Bitmap.Config.ARGB_8888, false)

    private fun awaitRenderedFrame(expected: Bitmap): Bitmap {
        var rendered: Bitmap? = null
        compose.waitUntil(timeoutMillis = FRAME_LOAD_TIMEOUT_MS) {
            val candidate = captureSprite()
            if (matchesRenderedFrame(candidate, expected)) {
                rendered = candidate
                true
            } else {
                candidate.recycle()
                // The sheet is loaded off the main thread and the frame player waits on
                // withFrameNanos. Progress the disabled test clock only when another probe
                // is needed, so a successful capture is never pushed across a second frame.
                compose.mainClock.advanceTimeByFrame()
                false
            }
        }
        return requireNotNull(rendered) { "The production home frame was not rendered" }
    }

    private fun matchesRenderedFrame(rendered: Bitmap, expected: Bitmap): Boolean {
        if (rendered.width <= 0 || rendered.height <= 0) return false
        var compared = 0
        var matched = 0
        val sampleStep = 8
        for (sourceY in sampleStep until expected.height - sampleStep step sampleStep) {
            for (sourceX in sampleStep until expected.width - sampleStep step sampleStep) {
                val expectedPixel = expected.getPixel(sourceX, sourceY)
                if (Color.alpha(expectedPixel) < 32) continue
                compared++
                val renderedX = (((sourceX + 0.5f) * rendered.width) / expected.width)
                    .toInt().coerceIn(0, rendered.width - 1)
                val renderedY = (((sourceY + 0.5f) * rendered.height) / expected.height)
                    .toInt().coerceIn(0, rendered.height - 1)
                val actualPixel = rendered.getPixel(renderedX, renderedY)
                val close = maxOf(
                    kotlin.math.abs(Color.red(actualPixel) - Color.red(expectedPixel)),
                    kotlin.math.abs(Color.green(actualPixel) - Color.green(expectedPixel)),
                    kotlin.math.abs(Color.blue(actualPixel) - Color.blue(expectedPixel))
                ) <= COLOR_TOLERANCE
                if (close) matched++
            }
        }
        return compared >= MIN_SAMPLES && matched.toFloat() / compared >= MATCH_RATIO
    }

    private fun saveCapture(bitmap: Bitmap, name: String) {
        val directory = File(compose.activity.filesDir, QA_DIRECTORY).apply { mkdirs() }
        File(directory, name).outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write $name"
            }
        }
    }

    private data class ProductionIdlePack(
        val frameMs: Long,
        val framePaths: List<String>
    )

    private companion object {
        const val MANIFEST_PATH = "tama/animations/manifest.json"
        const val HOME_ASSET_ID = "home_dragon_baby"
        const val SPRITE_TAG = "connected_home_sprite"
        const val QA_DIRECTORY = "world-render-qa"
        const val FRAME_LOAD_TIMEOUT_MS = 5_000L
        const val COLOR_TOLERANCE = 24
        const val MIN_SAMPLES = 100
        // A same-frame nearest-neighbour capture is >= 0.91 even at 1x density; the
        // opposite authored idle frame is <= 0.75 and the legacy fallback is <= 0.46.
        const val MATCH_RATIO = 0.90f
    }
}
