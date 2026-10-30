package com.example.llamadroid.screenshots.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.screenshots.catalog.TamaDialogShotCatalog
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Tama dialog/popup bodies inline (opt-in, `-Pscreenshots=true`) for contrast review under any
 * theme: `-Padt.shots.theme=light|dark -Padt.shots.style=soft|pixel -Padt.shots.pixelVariant=...`.
 * Output `<adt.shots.out>/dialogs/<theme>-<style>[-variant]/<key>.png` plus `contact-sheet.png`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DialogShotTest(private val key: String) {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private lateinit var harness: ShotHarness
    private val profile: ShotProfile get() = ShotRunOptions.profileFilter ?: ShotProfile.PHONE
    private val locale: ShotLocale get() = ShotRunOptions.localeFilter ?: ShotLocale.EN

    @Before fun setUp() {
        if (System.getenv("ADT_SHOTS_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        harness.close()
        TamaFixtures.reset()
        // The transfer handle is a process-wide singleton bound to the closed database: drop it.
        runCatching {
            Class.forName("com.example.llamadroid.tama.ui.transfer.TamaTransferHolder")
                .getDeclaredField("current").apply { isAccessible = true }
                .set(com.example.llamadroid.tama.ui.transfer.TamaTransferHolder, null)
        }
    }

    @Test fun render() {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(TamaDialogShotCatalog.spec(key, profile, locale))
        val dir = File(ShotHarness.outRoot(), "dialogs/${ShotRunOptions.themeStyleLabel}").apply { mkdirs() }
        val name = if (result.settled) key else "${key}_unsettled"
        File(dir, "$name.png").writeBytes(ShotOutput.pngBytes(result.bitmap))
        println("DIALOG saved ${File(dir, "$name.png").absolutePath} settled=${result.settled} ${result.millis} ms")
        writeContactSheet(dir)
        assertTrue("$key settled before deadline (image saved anyway)", result.settled)
    }

    /** Rebuilds `contact-sheet.png` from every key PNG present, so partial runs accumulate. */
    private fun writeContactSheet(dir: File) {
        val tileW = 360
        val tileH = 640
        val label = 28
        val files = TamaDialogShotCatalog.keys.flatMap { listOf(File(dir, "$it.png"), File(dir, "${it}_unsettled.png")) }
            .filter { it.isFile }
        if (files.isEmpty()) return
        val columns = 4
        val rows = (files.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * tileW, rows * (tileH + label), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply { color = Color.WHITE; textSize = 20f; isAntiAlias = true }
        files.forEachIndexed { index, file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
            val scale = minOf(tileW.toFloat() / bitmap.width, tileH.toFloat() / bitmap.height)
            val scaled = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
            val x = (index % columns) * tileW
            val y = (index / columns) * (tileH + label)
            canvas.drawText(file.nameWithoutExtension, x + 6f, y + 21f, paint)
            canvas.drawBitmap(scaled, x.toFloat(), (y + label).toFloat(), null)
        }
        File(dir, "contact-sheet.png").writeBytes(ShotOutput.pngBytes(sheet))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun keys(): List<String> = TamaDialogShotCatalog.keys
    }
}
