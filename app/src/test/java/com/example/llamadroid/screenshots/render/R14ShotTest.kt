package com.example.llamadroid.screenshots.render

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.screenshots.catalog.R14ShotCatalog
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
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
 * Round 14 review renders (opt-in, `-Pscreenshots=true`): Gate battle arena, farm plot area and livestock
 * cards at 320 dp and 411 dp wide (800 dp tall), English and Spanish. Output
 * `<adt.shots.out>/r14/<locale>-<widthdp>/<key>.png`. Filters: `-Padt.shots.locale`, `-Padt.shots.keys`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class R14ShotTest(
    private val key: String,
    private val widthDp: Int,
    private val locale: ShotLocale
) {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private lateinit var harness: ShotHarness

    @Before fun setUp() {
        if (System.getenv("ADT_SHOTS_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        harness.close()
        TamaFixtures.reset()
    }

    @Test fun render() {
        assumeTrue("not requested", (ShotRunOptions.localeFilter ?: locale) == locale)
        assumeTrue("key $key not requested", ShotRunOptions.keyFilter?.contains(key) != false)
        val result = harness.shoot(R14ShotCatalog.spec(key, widthDp, locale))
        val dir = File(ShotHarness.outRoot(), "r14/${locale.tag}-$widthDp").apply { mkdirs() }
        val name = if (result.settled) key else "${key}_unsettled"
        File(dir, "$name.png").writeBytes(ShotOutput.pngBytes(result.bitmap))
        println("R14 saved ${File(dir, "$name.png").absolutePath} settled=${result.settled} ${result.millis} ms")
        assertTrue("$key settled before deadline (image saved anyway)", result.settled)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{2}-{1}")
        fun cases(): List<Array<Any>> = R14ShotCatalog.keys.flatMap { key ->
            R14ShotCatalog.widthsDp.flatMap { width ->
                ShotLocale.entries.map { locale -> arrayOf<Any>(key, width, locale) }
            }
        }
    }
}
