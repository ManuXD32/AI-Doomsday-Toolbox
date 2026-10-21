package com.example.llamadroid.screenshots.render

import androidx.compose.ui.test.junit4.ComposeTestRule
import com.example.llamadroid.R
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.llamadroid.screenshots.fixtures.AgentFixtures
import com.example.llamadroid.screenshots.fixtures.AgentTexts
import com.example.llamadroid.screenshots.fixtures.ChatFixtures
import com.example.llamadroid.screenshots.fixtures.DemoText
import com.example.llamadroid.screenshots.fixtures.MediaFixtures
import com.example.llamadroid.screenshots.fixtures.ModelFixtures
import com.example.llamadroid.screenshots.fixtures.ServerFixtures
import com.example.llamadroid.screenshots.harness.FakeEnvironment
import com.example.llamadroid.screenshots.harness.RunningServerFake
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotOutput
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotRunOptions
import com.example.llamadroid.screenshots.harness.ShotSpec
import kotlinx.coroutines.flow.first
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
 * Store screenshots batch A (native chat with tools, chat list, AI agent, images, models).
 * Opt-in (`-Pscreenshots=true`); profile/locale come from `-Padt.shots.profile/locale` (default phone/en).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StoreShotsATest {
    @get:Rule val compose = createEmptyComposeRule()
    private val environment = FakeEnvironment()
    private val server = RunningServerFake()
    private lateinit var harness: ShotHarness
    private val profile: ShotProfile get() = ShotRunOptions.profileFilter ?: ShotProfile.PHONE
    private val locale: ShotLocale get() = ShotRunOptions.localeFilter ?: ShotLocale.EN

    @Before fun setUp() {
        // Static-cache resets (Main dispatcher, view model factory, host facts) live in FakeEnvironment.
        if (System.getenv("ADT_SHOTS_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        harness = ShotHarness(compose, environment)
    }

    @After fun tearDown() {
        harness.close()
        server.stop(RuntimeEnvironment.getApplication())
        MediaFixtures.reset()
    }

    private fun visible(text: String): (ComposeTestRule) -> Boolean = { ShotHarness.textVisible(it, text) }

    private fun shoot(key: String, spec: (ShotProfile, ShotLocale) -> ShotSpec) {
        assumeTrue("key $key not requested", ShotRunOptions.wants(key, profile, locale))
        val result = harness.shoot(spec(profile, locale))
        val saved = ShotOutput.save(
            ShotHarness.outRoot(), result.spec.profile, result.spec.locale, key, result.spec.route,
            result.bitmap, result.spec.substitute, result.captureMethod, result.millis
        )
        println("STOREA saved ${saved.png.absolutePath} settled=${result.settled}")
        assertTrue("$key settled before deadline (image saved anyway)", result.settled)
    }

    /** True once the server banner ("Server stopped") is gone, i.e. the registry saw the running session. */
    private fun serverUp(rule: ComposeTestRule): Boolean =
        !ShotHarness.textVisible(rule, RuntimeEnvironment.getApplication().getString(R.string.native_chat_banner_stopped))

    private var scrolled = false
    private var scrollWait = 0

    /**
     * Scrolls the biggest lazy list to item [index], once, after [text] is visible. Calls the semantics
     * action directly: the compose-test performScroll* helpers wait for idle and hang with the manual
     * clock. Returns false until the scroll was issued and had a few frames to apply.
     */
    private fun scrollToIndex(rule: ComposeTestRule, text: String, index: Int): Boolean {
        if (scrolled) return ++scrollWait > 4
        if (!ShotHarness.textVisible(rule, text)) return false
        scrolled = true
        runCatching {
            val node = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex
            )).fetchSemanticsNodes().maxByOrNull { it.size.height }!!
            val ok = node.config[androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex].action?.invoke(index)
            println("STOREA scrollToIndex($index) node h=${node.size.height} result=$ok")
        }.onFailure { println("STOREA scroll failed: $it") }
        return false
    }

    /**
     * The native chat screen always offers "Continue" under the last assistant reply (LlamaChatScreen
     * `canContinueChat`: not busy and last message from the assistant), whatever the seeded finish state,
     * and short chats cannot scroll it out of frame. The harness paints its (empty) strip with the
     * background colour for the store shots.
     */
    private fun continueLabel(l: ShotLocale): String {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag(l.tag)) }
        return app.createConfigurationContext(config).getString(R.string.llama_continue)
    }

    private fun runningServer(app: android.app.Application) =
        ServerFixtures.seedRunningServer(app, server, "Qwen3 4B", ModelFixtures.llms.first())

    /** Water-plan chat with kiwix_search + calculator; tablets also show the poster turn. */
    @Test fun chat() = shoot("store_chat") { p, l ->
        val t = DemoText.ChatTexts(l)
        val tablet = p != ShotProfile.PHONE
        ShotSpec(
            key = "store_chat", route = "llama_chat/1/1", profile = p, locale = l,
            seed = { app ->
                val srv = runningServer(app)
                ChatFixtures.seedChat(app, l, srv, withWaterPlan = true, withImageTurn = tablet)
            },
            ready = { ShotHarness.textVisible(it, t.userWater) && serverUp(it) },
            blankTexts = listOf(continueLabel(l))
        )
    }

    /** Image turn: generate_image tool card and the generated image inline. */
    @Test fun chatImage() = shoot("store_chat_image") { p, l ->
        val t = DemoText.ChatTexts(l)
        ShotSpec(
            key = "store_chat_image", route = "llama_chat/1/1", profile = p, locale = l,
            seed = { app ->
                val srv = runningServer(app)
                ChatFixtures.seedChat(app, l, srv, title = t.imageTitle, withWaterPlan = false, withImageTurn = true)
            },
            ready = { ShotHarness.textVisible(it, t.imageAnswer.take(24)) && serverUp(it) },
            blankTexts = listOf(continueLabel(l))
        )
    }

    @Test fun chatList() = shoot("store_chat_list") { p, l ->
        val t = DemoText.ChatTexts(l)
        ShotSpec(
            key = "store_chat_list", route = "chats", profile = p, locale = l,
            seed = { app ->
                val srv = runningServer(app)
                ChatFixtures.seedChat(app, l, srv, withWaterPlan = true, withImageTurn = false, ageHours = 1)
                ChatFixtures.seedOtherChats(app, l, srv)
            },
            ready = {
                ShotHarness.textVisible(it, t.title) &&
                    !ShotHarness.textVisible(it, RuntimeEnvironment.getApplication().getString(R.string.llama_card_status_stopped))
            }
        )
    }

    @Test fun agent() = shoot("store_agent") { p, l ->
        val t = AgentTexts(l)
        ShotSpec(
            key = "store_agent", route = "native_chat_delegation/1", profile = p, locale = l,
            seed = { app ->
                val srv = runningServer(app)
                AgentFixtures.seedDelegation(app, l, srv)
            },
            ready = visible(t.progress2.take(30))
        )
    }

    @Test fun models() = shoot("store_models") { p, l ->
        ShotSpec(
            key = "store_models", route = "llm_models", profile = p, locale = l,
            seed = { app ->
                ModelFixtures.insert(app, ModelFixtures.llms)
                ModelFixtures.registerDeviceStorage()
            },
            ready = { scrollToIndex(it, "LLM (", 2) }
        )
    }

    @Test fun images() = shoot("store_images") { p, l ->
        ShotSpec(
            key = "store_images", route = "image_gen?startMode=0&tab=gallery", profile = p, locale = l,
            seed = { app ->
                ModelFixtures.insert(app, ModelFixtures.diffusion)
                MediaFixtures.seedSdGallery(app)
                MediaFixtures.showLatestResult(app, l)
            },
            ready = { true }
        )
    }

    @Test fun gallery() = shoot("store_gallery") { p, l ->
        ShotSpec(
            key = "store_gallery", route = "all_media_gallery", profile = p, locale = l,
            seed = { app -> MediaFixtures.seedSdGallery(app) },
            ready = { scrollToIndex(it, "txt2img_1000", 2) }
        )
    }
}
