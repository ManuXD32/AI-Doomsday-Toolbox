package com.example.llamadroid.screenshots.render

import com.example.llamadroid.screenshots.fixtures.DistributedFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import com.example.llamadroid.screenshots.harness.ShotSpec
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.service.SdDistributedService
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Media (SD) distributed hub, helper, main device and network screens (W21c). Opt-in (`-Pscreenshots=true`); the output goes to
 * `-Padt.shots.out` (review shots: `.agents/dist-shots/`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DistributedSdShotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private lateinit var harness: ShotHarness
    private val profile: ShotProfile get() = ShotRunOptions.profileFilter ?: ShotProfile.PHONE
    private val locale: ShotLocale get() = ShotRunOptions.localeFilter ?: ShotLocale.EN

    @Before fun setUp() {
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        harness.close()
        DistributedFixtures.reset()
        setCompanion("_isWorkerRunning", false)
        setCompanion("_connectionCount", 0)
        setCompanion("_localIp", null)
    }

    private fun setCompanion(name: String, value: Any?) {
        runCatching {
            val field = SdDistributedService::class.java.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as MutableStateFlow<Any?>).value = value
        }
    }

    private fun shoot(key: String, route: String, seed: () -> Unit = {}, ready: String) {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(
            ShotSpec(
                key = key, route = route, profile = profile, locale = locale,
                seed = { seed() },
                ready = { ShotHarness.textVisible(it, ready) }
            )
        )
        val outKey = if (result.settled) key else "${key}_unsettled"
        ShotOutput.save(
            ShotHarness.outRoot(), result.spec.profile, result.spec.locale, outKey, result.spec.route,
            result.bitmap, result.spec.substitute, result.captureMethod, result.millis
        )
        assertTrue("$key settled before deadline", result.settled)
    }

    private fun readyText(en: String, es: String) = if (locale == ShotLocale.ES) es else en

    @Test fun hub() = shoot(
        "sd_hub", "sd_distributed",
        ready = readyText("Images and video across phones", "Imagen y vídeo entre móviles")
    )

    @Test fun helperIdle() = shoot(
        "sd_helper_idle", "sd_distributed_worker",
        ready = readyText("Helper phone (media)", "Móvil ayudante (medios)")
    )

    @Test fun helperRunning() = shoot(
        "sd_helper_running", "sd_distributed_worker",
        seed = {
            setCompanion("_isWorkerRunning", true)
            setCompanion("_localIp", "192.168.1.21")
            setCompanion("_connectionCount", 1)
        },
        ready = readyText("Stop sharing", "Dejar de compartir")
    )

    @Test fun mainEmpty() = shoot(
        "sd_main_empty", "sd_distributed_master",
        ready = readyText("Distributed images", "Imágenes distribuidas")
    )

    @Test fun network() = shoot(
        "sd_network", "sd_distributed_network",
        ready = readyText("Nothing is running", "No se está ejecutando nada")
    )
}
