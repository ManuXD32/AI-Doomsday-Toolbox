package com.example.llamadroid.screenshots.render

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import com.example.llamadroid.R
import com.example.llamadroid.screenshots.fixtures.AudioFixtures
import com.example.llamadroid.screenshots.fixtures.DistributedFixtures
import com.example.llamadroid.screenshots.fixtures.FileStationFixtures
import com.example.llamadroid.screenshots.fixtures.KiwixFixtures
import com.example.llamadroid.screenshots.fixtures.OrganizerFixtures
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.fixtures.VideoFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.screenshots.harness.ShotTap
import com.example.llamadroid.ui.navigation.Screen
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

/**
 * Store screenshots batch B (distributed, Kiwix, video, audio, File Station, Tama, Organizer).
 * Opt-in (`-Pscreenshots=true`); profile/locale come from `-Padt.shots.profile/locale` (default phone/en).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StoreShotsBTest {
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
        FileStationFixtures.reset()
        TamaFixtures.reset()
    }

    /** Resource text in [l]; the spec is built before the harness applies the Robolectric locale. */
    private fun str(l: ShotLocale, res: Int): String {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag(l.tag)) }
        return app.createConfigurationContext(config).getString(res)
    }

    /**
     * Ready check that first waits for [contentText], then once invokes the semantics ScrollBy action of
     * the first vertical scroll container so [scrollTarget] (when composed) lands at the top of the
     * viewport, else by [fallbackPx]. The compose-test `performScrollTo` is avoided on purpose: it
     * waits for idle, which never arrives with the paused main clock. The action only launches the
     * scroll; the harness settle loop that keeps calling this lambda advances the clock.
     */
    private fun readyAfterScroll(contentText: String, scrollTarget: String?, fallbackPx: Float = 0f, marginPx: Float = 8f): (ComposeTestRule) -> Boolean {
        var scrolled = false
        var settleSteps = 0
        return { rule ->
            when {
                !ShotHarness.textVisible(rule, contentText) -> false
                scrollTarget == null && fallbackPx == 0f -> true
                scrolled -> ++settleSteps >= 8 // let the scroll animation finish on the manual clock
                else -> {
                    scrolled = true
                    runCatching {
                        // Largest vertical scroller: tablets also have a (short) scrollable navigation rail.
                        val container = rule.onAllNodes(
                            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
                                .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                        ).fetchSemanticsNodes().maxByOrNull { it.size.width.toLong() * it.size.height }!!
                        val target = scrollTarget?.let {
                            rule.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().firstOrNull()
                        }
                        val dy = if (target != null) {
                            target.positionInRoot.y - container.positionInRoot.y - marginPx
                        } else fallbackPx
                        val range = container.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                        val result = container.config[SemanticsActions.ScrollBy].action?.invoke(0f, dy)
                        println("STOREB scroll '$scrollTarget' dy=$dy target=${target?.boundsInRoot} container=${container.boundsInRoot} " +
                            "range=${range?.value?.invoke()}/${range?.maxValue?.invoke()} result=$result")
                    }.onFailure { println("STOREB scroll to '$scrollTarget' failed: ${it.message?.take(160)}") }
                    false
                }
            }
        }
    }

    /** The harness settles once before and once after the taps: [first] gates the first, [second] the second. */
    private fun twoPhase(first: (ComposeTestRule) -> Boolean, second: (ComposeTestRule) -> Boolean): (ComposeTestRule) -> Boolean {
        var phase = 0
        return { rule ->
            if (phase == 0) {
                first(rule).also { if (it) phase = 1 }
            } else second(rule)
        }
    }

    private fun monthTitle(l: ShotLocale): String = java.time.YearMonth.now().format(
        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.forLanguageTag(l.tag))
    )

    private fun shoot(key: String, spec: (ShotProfile, ShotLocale) -> ShotSpec) {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(spec(profile, locale))
        // Unsettled frames are still saved (under a _unsettled suffix) so the failure can be inspected.
        val outKey = if (result.settled) key else "${key}_unsettled"
        val saved = ShotOutput.save(
            ShotHarness.outRoot(), result.spec.profile, result.spec.locale, outKey, result.spec.route,
            result.bitmap, result.spec.substitute, result.captureMethod, result.millis
        )
        println("STOREB saved ${saved.png.absolutePath}")
        assertTrue("$key settled before deadline", result.settled)
    }

    @Test fun distributedLlmTopology() = shoot("store_distributed_llm_topology") { p, l ->
        ShotSpec(
            key = "store_distributed_llm_topology", route = "distributed_network", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlm(app) },
            ready = { ShotHarness.textVisible(it, "Desktop RTX") }
        )
    }

    @Test fun distributedMediaNetwork() = shoot("store_distributed_media_network") { p, l ->
        ShotSpec(
            key = "store_distributed_media_network", route = "sd_distributed_network", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedMedia(app) },
            ready = readyAfterScroll("Desktop RTX", str(l, R.string.sd_dist_live_topology))
        )
    }

    @Test fun distributedLlmMaster() = shoot("store_distributed_llm_master") { p, l ->
        ShotSpec(
            key = "store_distributed_llm_master", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlmMaster(app) },
            ready = readyAfterScroll("Qwen3-32B", null)
        )
    }

    @Test fun distributedLlmMasterReview() = shoot("store_distributed_llm") { p, l ->
        ShotSpec(
            key = "store_distributed_llm", route = "distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlmMaster(app) },
            ready = readyAfterScroll("Qwen3-32B", str(l, R.string.dist_step_review), fallbackPx = 100_000f)
        )
    }

    @Test fun distributedLlmTopologyBottom() = shoot("store_distributed_llm_memory") { p, l ->
        ShotSpec(
            key = "store_distributed_llm_memory", route = "distributed_network", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedLlm(app) },
            ready = readyAfterScroll("Desktop RTX", str(l, R.string.worker_topology_model_title))
        )
    }

    @Test fun distributedMediaConsole() = shoot("store_distributed_media_console") { p, l ->
        ShotSpec(
            key = "store_distributed_media_console", route = "sd_distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedMediaMaster(app, l) },
            ready = readyAfterScroll("Desktop RTX", null)
        )
    }

    @Test fun distributedMedia() = shoot("store_distributed_media") { p, l ->
        ShotSpec(
            key = "store_distributed_media", route = "sd_distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedMediaMaster(app, l) },
            ready = readyAfterScroll("Desktop RTX", "Pixel 8 Pro", marginPx = 420f)
        )
    }

    @Test fun distributedMediaRun() = shoot("store_distributed_media_run") { p, l ->
        ShotSpec(
            key = "store_distributed_media_run", route = "sd_distributed_master", profile = p, locale = l,
            seed = { app -> DistributedFixtures.seedMediaMaster(app, l) },
            ready = readyAfterScroll("Desktop RTX", str(l, R.string.sd_dist_image_progress_title), marginPx = 1000f)
        )
    }

    @Test fun kiwix() = shoot("store_kiwix") { p, l ->
        ShotSpec(
            key = "store_kiwix", route = "zim_manager", profile = p, locale = l,
            seed = { app -> KiwixFixtures.seedZims(app, l); KiwixFixtures.runKiwixServer(app) },
            ready = { ShotHarness.textVisible(it, "Outdoors Stack Exchange") }
        )
    }

    @Test fun kiwixShare() = shoot("store_kiwix_share") { p, l ->
        ShotSpec(
            key = "store_kiwix_share", route = "zim_manager", profile = p, locale = l,
            seed = { app -> KiwixFixtures.seedZims(app, l); KiwixFixtures.runKiwixServer(app); KiwixFixtures.runZimShare(app) },
            taps = listOf(ShotTap.Text(str(l, R.string.zim_tab_share))),
            ready = twoPhase({ ShotHarness.textVisible(it, str(l, R.string.zim_tab_installed)) }, { ShotHarness.textVisible(it, "wlan0") })
        )
    }

    @Test fun video() = shoot("store_video") { p, l ->
        ShotSpec(
            key = "store_video", route = Screen.VideoGen.createRoute("gallery"), profile = p, locale = l,
            seed = { app -> VideoFixtures.seedGallery(app, l) },
            ready = { ShotHarness.textVisible(it, VideoFixtures.firstPromptStart(l)) }
        )
    }

    @Test fun fileShare() = shoot("store_fileshare") { p, l ->
        ShotSpec(
            key = "store_fileshare", route = "file_server", profile = p, locale = l,
            seed = { app -> FileStationFixtures.seedRunning(app, l) },
            taps = listOf(ShotTap.Text(str(l, R.string.fs_qr_show))),
            ready = twoPhase(
                { ShotHarness.textVisible(it, "192.168.1.10:8787") && !ShotHarness.textVisible(it, str(l, R.string.fs_warn_no_root_title)) },
                { it.onAllNodes(androidx.compose.ui.test.hasContentDescription("192.168.1.10", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            )
        )
    }

    @Test fun tama() = shoot("store_tama") { p, l ->
        ShotSpec(
            key = "store_tama", route = "tama", profile = p, locale = l,
            seed = { app -> TamaFixtures.seedHealthyPet(app) },
            ready = { ShotHarness.textVisible(it, "Ember") }
        )
    }

    @Test fun organizer() = shoot("store_organizer") { p, l ->
        ShotSpec(
            key = "store_organizer", route = "notes_manager", profile = p, locale = l,
            seed = { app -> OrganizerFixtures.seed(app, l) },
            ready = { ShotHarness.textVisible(it, if (l == ShotLocale.ES) "Plan de almacenamiento de agua" else "Water storage plan") }
        )
    }

    @Test fun organizerCalendar() = shoot("store_organizer_calendar") { p, l ->
        ShotSpec(
            key = "store_organizer_calendar", route = "notes_manager", profile = p, locale = l,
            seed = { app -> OrganizerFixtures.seed(app, l) },
            taps = listOf(ShotTap.Text(str(l, R.string.organizer_tab_calendar))),
            ready = twoPhase({ ShotHarness.textVisible(it, str(l, R.string.organizer_tab_alarms)) }, { ShotHarness.textVisible(it, monthTitle(l)) })
        )
    }

    @Test fun audio() = shoot("store_audio") { p, l ->
        ShotSpec(
            key = "store_audio", route = Screen.AudioWorkspace.createRoute("history"), profile = p, locale = l,
            seed = { app -> AudioFixtures.seed(app, l) },
            ready = readyAfterScroll(AudioFixtures.titles(l).first(), AudioFixtures.titles(l).first(), marginPx = 200f)
        )
    }
}
