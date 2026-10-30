package com.example.llamadroid.screenshots.render

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.screenshots.catalog.GuideShotContext
import com.example.llamadroid.screenshots.catalog.ReadmeShotCatalog
import com.example.llamadroid.screenshots.fixtures.DistributedFixtures
import com.example.llamadroid.screenshots.fixtures.FileStationFixtures
import com.example.llamadroid.screenshots.fixtures.MediaFixtures
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * README renders (opt-in, `-Pscreenshots=true`): full-size `<out>/<profile>/<locale>/<key>.png` plus the
 * guide-style scaled copy `<out>/guide/<profile>/<locale>/<key>.webp`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReadmeShotTest(private val key: String) {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private val context = GuideShotContext()
    private lateinit var harness: ShotHarness
    private val profile: ShotProfile get() = ShotRunOptions.profileFilter ?: ShotProfile.PHONE
    private val locale: ShotLocale get() = ShotRunOptions.localeFilter ?: ShotLocale.EN

    @Before fun setUp() {
        if (System.getenv("ADT_SHOTS_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        ReadmeShotCatalog.reset(RuntimeEnvironment.getApplication())
        harness.close()
        context.server.stop(RuntimeEnvironment.getApplication())
        MediaFixtures.reset()
        DistributedFixtures.reset()
        FileStationFixtures.reset()
        TamaFixtures.reset()
    }

    @Test fun render() {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(ReadmeShotCatalog.spec(key, profile, locale, context))
        val outKey = if (result.settled) key else "${key}_unsettled"
        val spec = result.spec
        val full = ShotOutput.save(
            ShotHarness.outRoot(), spec.profile, spec.locale, outKey, spec.route,
            result.bitmap, spec.substitute, result.captureMethod, result.millis
        )
        val scaled = ShotOutput.scaledToLongSide(result.bitmap, profile.guideLongSide)
        val guide = ShotOutput.save(
            File(ShotHarness.outRoot(), "guide"), spec.profile, spec.locale, outKey, spec.route,
            scaled, spec.substitute, result.captureMethod, result.millis
        )
        println("README saved ${full.png.absolutePath} / ${guide.webp.absolutePath} settled=${result.settled} ${result.millis} ms")
        assertTrue("$key settled before deadline (image saved anyway)", result.settled)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun keys(): List<String> = ReadmeShotCatalog.keys
    }
}
