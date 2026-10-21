package com.example.llamadroid.screenshots.render

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.screenshots.catalog.GuideShotCatalog
import com.example.llamadroid.screenshots.catalog.GuideShotContext
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

/**
 * Renders one lesson/step screenshot per catalog key (opt-in, `-Pscreenshots=true`). Output under
 * `<adt.shots.out>/<profile>/<locale>/guide/`; the guide copy is scaled to the profile's guide long side.
 * One parameterised test per key so every key starts with a fresh Robolectric app (databases, files).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GuideShotTest(private val key: String) {
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
        harness.close()
        context.server.stop(RuntimeEnvironment.getApplication())
        MediaFixtures.reset()
        DistributedFixtures.reset()
        FileStationFixtures.reset()
        TamaFixtures.reset()
    }

    @Test fun render() {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(GuideShotCatalog.spec(key, profile, locale, context))
        val outKey = if (result.settled) key else "${key}_unsettled"
        val scaled = ShotOutput.scaledToLongSide(result.bitmap, profile.guideLongSide)
        val root = java.io.File(ShotHarness.outRoot(), "guide")
        val saved = ShotOutput.save(
            root, result.spec.profile, result.spec.locale, outKey, result.spec.route,
            scaled, result.spec.substitute, result.captureMethod, result.millis
        )
        println("GUIDE saved ${saved.webp.absolutePath} ${scaled.width}x${scaled.height} settled=${result.settled} ${result.millis} ms")
        assertTrue("$key settled before deadline (image saved anyway)", result.settled)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun keys(): List<String> = GuideShotCatalog.keys
    }
}
