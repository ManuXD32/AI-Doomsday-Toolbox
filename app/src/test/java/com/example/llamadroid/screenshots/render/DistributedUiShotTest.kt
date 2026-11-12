package com.example.llamadroid.screenshots.render

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.R
import com.example.llamadroid.screenshots.fixtures.DistributedFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the redesigned distributed screens for root review (W21). Opt-in
 * (`-Pscreenshots=true -Padt.shots.keys=<key>`); PNGs go to `.agents/dist-shots/<profile>/<locale>/`.
 * W21b/W21c add their own shots in this class.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DistributedUiShotTest {
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
    }

    private fun outRoot(): File = File(ShotRunOptions.repoRoot, ".agents/dist-shots")

    private fun shoot(key: String, p: ShotProfile = profile, l: ShotLocale = locale, spec: (ShotProfile, ShotLocale) -> ShotSpec) {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, p, l))
        val result = harness.shoot(spec(p, l))
        val outKey = if (result.settled) key else "${key}_unsettled"
        val saved = ShotOutput.save(
            outRoot(), result.spec.profile, result.spec.locale, outKey, result.spec.route,
            result.bitmap, result.spec.substitute, result.captureMethod, result.millis
        )
        println("DISTUI saved ${saved.png.absolutePath}")
        assertTrue("$key settled before deadline", result.settled)
    }

    private fun stopRuntime() {
        DistributedMasterRuntimeState::class.java.getDeclaredField("_state").apply { isAccessible = true }.let { f ->
            @Suppress("UNCHECKED_CAST")
            (f.get(DistributedMasterRuntimeState) as MutableStateFlow<ServerState>).value = ServerState.Stopped
        }
    }

    /** One launch of the first vertical scroller so the section titled [target] reaches the top. */
    private fun readyAfterScroll(contentText: String, target: String): (ComposeTestRule) -> Boolean {
        var scrolled = false
        var steps = 0
        return { rule ->
            when {
                !ShotHarness.textVisible(rule, contentText) -> false
                scrolled -> ++steps >= 8
                else -> {
                    scrolled = true
                    runCatching {
                        val container = rule.onAllNodes(
                            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
                                .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                        ).fetchSemanticsNodes().maxByOrNull { it.size.width.toLong() * it.size.height }!!
                        val node = rule.onAllNodes(
                            androidx.compose.ui.test.hasText(target, substring = true)
                        ).fetchSemanticsNodes().firstOrNull()
                        val dy = if (node != null) node.positionInRoot.y - container.positionInRoot.y - 8f else 1200f
                        container.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy)
                    }
                    false
                }
            }
        }
    }

    private fun str(l: ShotLocale, res: Int, vararg args: Any): String {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag(l.tag)) }
        return app.createConfigurationContext(config).getString(res, *args)
    }

    // The model card sits below the helper list (a lazy list), so wait on the status panel instead:
    // it only names the helper count once the saved helpers and the model probe have loaded.
    private fun stoppedReady(l: ShotLocale): String = str(l, R.string.dist_ui_status_ready, DistributedFixtures.llmDevices.size)
    private fun runningReady(l: ShotLocale): String = str(l, R.string.dist_ui_status_running, DistributedFixtures.llmDevices.size)

    @Test fun masterEmpty() = shoot("dist_llm_master_empty") { p, l ->
        ShotSpec(
            key = "dist_llm_master_empty", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlm(app); stopRuntime() },
            ready = { ShotHarness.textVisible(it, str(l, R.string.dist_ui_common_how_title)) }
        )
    }

    @Test fun masterWithHelpers() = shoot("dist_llm_master_helpers") { p, l ->
        ShotSpec(
            key = "dist_llm_master_helpers", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlmMaster(app); stopRuntime() },
            ready = { ShotHarness.textVisible(it, stoppedReady(l)) }
        )
    }

    @Test fun masterAdvanced() = shoot("dist_llm_master_advanced") { p, l ->
        ShotSpec(
            key = "dist_llm_master_advanced", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlmMaster(app); stopRuntime() },
            ready = readyAfterScroll(stoppedReady(l), str(l, R.string.dist_ui_step_memory))
        )
    }

    @Test fun masterRunning() = shoot("dist_llm_master_running") { p, l ->
        ShotSpec(
            key = "dist_llm_master_running", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlmMaster(app) },
            ready = { ShotHarness.textVisible(it, runningReady(l)) }
        )
    }

    /** 320 dp wide, Spanish, font scale 2.0: nothing may clip and the page must scroll. */
    @Test fun masterNarrowSpanishLargeFont() {
        val narrow = ShotProfile("narrow", "w320dp-h640dp-port-notnight-xhdpi", 640, 1280, 1280)
        shoot("dist_llm_master_narrow_es_fs2", narrow, ShotLocale.ES) { p, l ->
            ShotSpec(
                key = "dist_llm_master_narrow_es_fs2", route = "distributed_master", profile = p, locale = l,
                seed = { app ->
                    DistributedFixtures.seedLlmMaster(app); stopRuntime()
                    RuntimeEnvironment.setFontScale(2.0f)
                },
                // At font 2.0 the status panel is a lazy item below the fold; wait on the first card instead.
                ready = { ShotHarness.textVisible(it, str(l, R.string.dist_ui_common_how_title)) }
            )
        }
    }
}
