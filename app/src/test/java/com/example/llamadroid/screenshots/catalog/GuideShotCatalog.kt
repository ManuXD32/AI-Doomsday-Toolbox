package com.example.llamadroid.screenshots.catalog

import android.app.Application
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import com.example.llamadroid.R
import com.example.llamadroid.service.VideoInterpolationService
import com.example.llamadroid.service.VideoUpscalerService
import com.example.llamadroid.service.WhisperService
import com.example.llamadroid.screenshots.fixtures.AgentFixtures
import com.example.llamadroid.screenshots.fixtures.AudioFixtures
import com.example.llamadroid.screenshots.fixtures.ChatFixtures
import com.example.llamadroid.screenshots.fixtures.DemoText
import com.example.llamadroid.screenshots.fixtures.DistributedFixtures
import com.example.llamadroid.screenshots.fixtures.FileStationFixtures
import com.example.llamadroid.screenshots.fixtures.KiwixFixtures
import com.example.llamadroid.screenshots.fixtures.MediaFixtures
import com.example.llamadroid.screenshots.fixtures.ModelFixtures
import com.example.llamadroid.screenshots.fixtures.OrganizerFixtures
import com.example.llamadroid.screenshots.fixtures.ServerFixtures
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.fixtures.VideoFixtures
import com.example.llamadroid.screenshots.fixtures.GuideFixtures
import com.example.llamadroid.screenshots.harness.RunningServerFake
import com.example.llamadroid.screenshots.harness.ShotHarness
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.screenshots.harness.ShotTap
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.UpscalerAssetPackSupport
import org.robolectric.RuntimeEnvironment

/** Per-test shared state of the guide renderer (the fake running llama-server and what it seeded). */
class GuideShotContext(val server: RunningServerFake = RunningServerFake()) {
    var running: ServerFixtures.Running? = null
    var chat: ChatFixtures.Seeded? = null
    var delegation: AgentFixtures.Seeded? = null
}

typealias GuideSeed = (Application, ShotLocale, GuideShotContext) -> Unit

/**
 * One guide screenshot: a real current screen that shows what the lesson/step text describes.
 * [substitute] is true when the described screen cannot render on the JVM (WebView, terminal, video
 * playback) and a neighbouring native screen stands in for it.
 */
class GuideEntry(
    val route: String,
    val seed: GuideSeed = { _, _, _ -> },
    val substitute: Boolean = false,
    val taps: (ShotLocale) -> List<ShotTap> = { emptyList() },
    val ready: (ShotLocale) -> (ComposeTestRule) -> Boolean = { { true } }
)

/**
 * Screenshot spec for every key mapped in `WalkthroughPreviews.kt` plus the `preview_*` fallbacks.
 * Output drawable names: `tour_lesson_<key>`, `tour_<key>` for `preview_*`, `tour_preview_library` for `library`.
 */
object GuideShotCatalog {
    private fun str(l: ShotLocale, res: Int): String {
        val app = RuntimeEnvironment.getApplication()
        val config = android.content.res.Configuration(app.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag(l.tag)) }
        return app.createConfigurationContext(config).getString(res)
    }

    /** Profile of the spec being built: Robolectric applies the qualifiers only inside the harness, after this runs. */
    private var currentProfile: ShotProfile = ShotProfile.PHONE

    /** Phone: scroll 600 px past the status header. Tablet: wait for the share URL, then scroll to the page top. */
    private fun fileStationReady(l: ShotLocale): (ComposeTestRule) -> Boolean =
        if (currentProfile != ShotProfile.PHONE) {
            readyAfterScroll("http://192.168", null, fallbackPx = -4000f)
        } else {
            readyAfterScroll(str(l, R.string.fs_qr_show), null, fallbackPx = 600f)
        }

    /**
     * Waits for [contentText], then once rewrites the SD checkpoint rows to phone-style paths (the app's reconcile
     * step would undo this if done in the seed) and gives the list a few frames to show them.
     */
    private fun readyWithDevicePaths(contentText: String): (ComposeTestRule) -> Boolean {
        var rewritten = false
        var wait = 0
        return { rule ->
            when {
                rewritten -> ++wait >= 8
                !ShotHarness.textVisible(rule, contentText) -> false
                else -> {
                    rewritten = true
                    ModelFixtures.showDeviceStylePaths(RuntimeEnvironment.getApplication())
                    false
                }
            }
        }
    }

    private fun visible(text: String): (ComposeTestRule) -> Boolean = { ShotHarness.textVisible(it, text) }

    /**
     * Waits for [contentText], then once scrolls the largest vertical scroller so [scrollTarget] lands near
     * the top (semantics ScrollBy; the compose-test scroll helpers hang with the paused clock).
     */
    private fun readyAfterScroll(contentText: String, scrollTarget: String?, marginPx: Float = 8f, fallbackPx: Float = 0f): (ComposeTestRule) -> Boolean {
        var scrolled = false
        var settleSteps = 0
        return { rule ->
            when {
                scrolled -> ++settleSteps >= 8
                !ShotHarness.textVisible(rule, contentText) -> false
                scrollTarget == null && fallbackPx == 0f -> true
                else -> {
                    scrolled = true
                    runCatching {
                        val container = rule.onAllNodes(
                            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
                                .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                        ).fetchSemanticsNodes().maxByOrNull { it.size.width.toLong() * it.size.height }!!
                        val target = scrollTarget?.let { rule.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().firstOrNull() }
                        val dy = if (target != null) target.positionInRoot.y - container.positionInRoot.y - marginPx else fallbackPx
                        container.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, dy)
                    }.onFailure { println("GUIDE scroll to '$scrollTarget' failed: ${it.message?.take(160)}") }
                    false
                }
            }
        }
    }

    // ---- seed building blocks -------------------------------------------------------------------------

    private val server: GuideSeed = { app, _, ctx ->
        if (ctx.running == null) {
            ctx.running = ServerFixtures.seedRunningServer(app, ctx.server, "Qwen3 4B", ModelFixtures.llms.first())
        }
    }
    private val chatWithTools: GuideSeed = { app, l, ctx ->
        server(app, l, ctx)
        ctx.chat = ChatFixtures.seedChat(app, l, ctx.running!!, withWaterPlan = true, withImageTurn = false)
    }
    private val chatList: GuideSeed = { app, l, ctx ->
        chatWithTools(app, l, ctx)
        ChatFixtures.seedOtherChats(app, l, ctx.running!!)
    }
    private val delegation: GuideSeed = { app, l, ctx ->
        server(app, l, ctx)
        ctx.delegation = AgentFixtures.seedDelegation(app, l, ctx.running!!)
    }
    private val delegationRunning: GuideSeed = { app, l, ctx ->
        delegation(app, l, ctx)
        GuideFixtures.delegationRunning(app, l, ctx)
        GuideFixtures.makeDelegationRunning(app, ctx)
    }
    private val llmModels: GuideSeed = { app, _, _ -> ModelFixtures.insert(app, ModelFixtures.llms) }
    private val allModels: GuideSeed = { app, _, _ ->
        ModelFixtures.insert(app, ModelFixtures.llms + ModelFixtures.diffusion + ModelFixtures.speech)
    }
    private val sdModels: GuideSeed = { app, _, _ -> ModelFixtures.insert(app, ModelFixtures.diffusion) }
    private val sdGallery: GuideSeed = { app, _, _ -> MediaFixtures.seedSdGallery(app) }
    private val sdResult: GuideSeed = { app, l, _ ->
        ModelFixtures.insert(app, ModelFixtures.diffusion)
        GuideFixtures.diffusionComponent(app)
        MediaFixtures.seedSdGallery(app)
        MediaFixtures.showLatestResult(app, l)
    }
    private val videos: GuideSeed = { app, l, _ -> VideoFixtures.seedGallery(app, l) }
    private val audio: GuideSeed = { app, l, _ -> AudioFixtures.seed(app, l) }
    private val zims: GuideSeed = { app, l, _ -> KiwixFixtures.seedZims(app, l); KiwixFixtures.runKiwixServer(app) }
    private val organizer: GuideSeed = { app, l, _ -> OrganizerFixtures.seed(app, l) }
    private val tama: GuideSeed = { app, _, _ -> TamaFixtures.seedHealthyPet(app) }
    private val fileStation: GuideSeed = { app, l, _ -> FileStationFixtures.seedRunning(app, l) }
    private val onnx: GuideSeed = { app, _, _ -> GuideFixtures.onnxModels(app) }
    private val home: GuideSeed = { app, l, ctx ->
        chatWithTools(app, l, ctx)
        GuideFixtures.pinnedChat(app, l, ctx.running!!)
        GuideFixtures.agentProjects(app, l)
        allModels(app, l, ctx)
        MediaFixtures.seedSdGallery(app)
    }

    private fun all(vararg seeds: GuideSeed): GuideSeed = { app, l, ctx -> seeds.forEach { it(app, l, ctx) } }

    // ---- entries --------------------------------------------------------------------------------------

    val entries: Map<String, GuideEntry> = linkedMapOf(
        // Fallback previews (WalkthroughUi.previewResource)
        "preview_home" to GuideEntry("dashboard", home, ready = { visible("Qwen3 4B") }),
        "preview_tools" to GuideEntry("ai_hub", all(chatWithTools, allModels)),
        "library" to GuideEntry("all_media_gallery", sdGallery),
        "preview_create" to GuideEntry(Screen.ImageGen.createRoute(0, "gallery"), sdResult),
        "preview_tama" to GuideEntry("tama", tama, ready = { visible("Ember") }),
        "preview_farm" to GuideEntry("farm", all(tama, { app, _, _ -> GuideFixtures.farm(app) })),

        // Conversations and agent
        "chat" to GuideEntry("chats", chatList, substitute = true),
        "agent" to GuideEntry("agent", all(server, { app, l, _ -> GuideFixtures.agentProjects(app, l) })),
        "conversations_native_management" to GuideEntry("chats", chatList),
        "conversations_scheduler" to GuideEntry("llama_scheduler", all(server, { app, l, ctx -> GuideFixtures.scheduledTasks(app, l, ctx.running!!) })),
        "conversations_server_selection" to GuideEntry(
            "llama_chat/1/1", chatWithTools, ready = { l -> visible(DemoText.ChatTexts(l).userWater) }
        ),
        "deepseek_delegation" to GuideEntry("native_chat_delegation/1", delegation),
        "native_chat_delegation" to GuideEntry("llama_chat/1/1", delegationRunning),

        // Create
        "image_generation" to GuideEntry(Screen.ImageGen.createRoute(0), sdResult, taps = { l -> listOf(ShotTap.Text(str(l, R.string.imagegen_select_model)), ShotTap.Text("sd-v1-5")) }),
        "video_generation" to GuideEntry(Screen.VideoGen.createRoute("gallery"), videos),
        "onnx_image_generation" to GuideEntry("onnx_image_gen", all(allModels, onnx)),
        "onnx_background_removal" to GuideEntry("onnx_background_removal", all(allModels, onnx)),
        "video_upscaler" to GuideEntry("video_upscaler", all(videos, allModels, { _, _, _ ->
            io.mockk.mockkObject(UpscalerAssetPackSupport)
            io.mockk.every { UpscalerAssetPackSupport.areModelsReady(any()) } returns true
        }, { app, _, _ -> GuideFixtures.bindable(app, VideoUpscalerService::class.java) })),
        "video_interpolation" to GuideEntry("video_interpolation", all(videos, allModels, { app, _, _ -> GuideFixtures.bindable(app, VideoInterpolationService::class.java) })),
        "subtitle_burn" to GuideEntry("subtitle_burn", videos),
        "fastsd_gallery" to GuideEntry("all_media_gallery", sdGallery, substitute = true),

        // Voice and documents
        "onnx_tts" to GuideEntry("onnx_tts", all(allModels, onnx)),
        "audio_workspace" to GuideEntry(Screen.AudioWorkspace.createRoute("history"), audio),
        "live_translator" to GuideEntry("live_translator", all(allModels, onnx)),
        "transcription" to GuideEntry("audio_transcription", all(allModels, { app, _, _ -> GuideFixtures.bindable(app, WhisperService::class.java) })),
        "video_summary" to GuideEntry("video_sumup", all(videos, allModels)),
        "pdf_tools" to GuideEntry("pdf_toolbox"),
        "workflows" to GuideEntry("workflows"),
        "dataset" to GuideEntry("dataset", { app, l, _ -> GuideFixtures.datasetProjects(app, l) }),
        "organizer" to GuideEntry("notes_manager", organizer),
        "knowledge" to GuideEntry(
            "knowledge_base", all(allModels, { app, l, _ -> GuideFixtures.knowledgeBases(app, l) }),
            ready = { l -> readyAfterScroll(str(l, R.string.kb_embedding_settings_title), null, fallbackPx = 3600f) }
        ),

        // Library, servers, infrastructure
        "offline_library" to GuideEntry("zim_manager", zims),
        "servers_hub" to GuideEntry("servers_hub", all(chatWithTools, zims, fileStation)),
        "web_servers" to GuideEntry("ai_servers_hub", chatWithTools),
        "distributed_roles" to GuideEntry("distributed"),
        "distributed_llm" to GuideEntry("distributed_network", { app, _, _ -> DistributedFixtures.seedLlm(app) }),
        "distributed_media" to GuideEntry("sd_distributed_network", { app, _, _ -> DistributedFixtures.seedMedia(app) }),
        "benchmark" to GuideEntry("benchmark", all(allModels, { app, _, _ -> GuideFixtures.benchmark(app) })),
        "quadtrix" to GuideEntry("quadtrix_trainer", substitute = true),
        "termux" to GuideEntry("termux", substitute = true),
        "ollama" to GuideEntry("ollama_manager", { app, _, _ -> GuideFixtures.ollamaServer(app) }),
        "file_server" to GuideEntry("file_server", fileStation),
        "file_station" to GuideEntry("file_server", fileStation, ready = { l -> fileStationReady(l) }),

        // Models
        "model_library" to GuideEntry("model_hub", all(allModels, onnx)),
        "models_llm" to GuideEntry("llm_models", llmModels),
        "models_sd_components" to GuideEntry("sd_models", sdModels, ready = { readyWithDevicePaths("Checkpoints") }),
        "models_onnx_bundle" to GuideEntry("onnx_models", all(allModels, onnx), ready = { visible("sd15-lcm-onnx-fp16") }),
        "models_litert_backend" to GuideEntry("litert_models", all(allModels, { app, _, _ -> GuideFixtures.liteRtModels(app) })),
        "models_whisper_vad" to GuideEntry("whisper_models", allModels),
        "models_custom_url" to GuideEntry(
            "model_sources", all(allModels, { app, l, _ -> GuideFixtures.modelSources(app, l) }),
            taps = { l -> listOf(ShotTap.Text(str(l, R.string.model_library_custom_download_heading))) }
        ),
        "models_saved_links" to GuideEntry("model_sources", all(allModels, { app, l, _ -> GuideFixtures.modelSources(app, l) })),
        "models_unknown" to GuideEntry(
            "model_sources", all(allModels, { app, l, _ -> GuideFixtures.modelSources(app, l) }),
            taps = { l -> listOf(ShotTap.Text(str(l, R.string.model_library_pending_tab))) }
        ),
        "models_hf_folder" to GuideEntry(
            "model_sources", all(allModels, { app, l, _ -> GuideFixtures.modelSources(app, l) }),
            ready = { l -> readyAfterScroll(str(l, R.string.model_library_browse), null, fallbackPx = 300f) }
        ),
        "models_bundles" to GuideEntry(
            "model_sources", all(allModels, { app, l, _ -> GuideFixtures.modelSources(app, l) }),
            taps = { l -> listOf(ShotTap.Text(str(l, R.string.model_library_bundles_tab))) }
        ),

        // Tama
        "tama_room" to GuideEntry("tama", tama, ready = { visible("Ember") }),
        "tama_care" to GuideEntry("tama", tama, ready = { readyAfterScroll("Ember", null, fallbackPx = 700f) }),
        "tama_shop" to GuideEntry("store", tama),
        "tama_farm" to GuideEntry("farm", all(tama, { app, _, _ -> GuideFixtures.farm(app) })),
        "tama_arcade" to GuideEntry("arcade", tama),
        "tama_adventures" to GuideEntry("adventure_gate", tama),
        "tama_gallery" to GuideEntry("tama_gallery", all(tama, { app, l, _ -> GuideFixtures.tamaGallery(app, l = l) })),

        // Settings
        "settings" to GuideEntry("settings_general"),
        "about" to GuideEntry("about"),
        "diagnostics" to GuideEntry("logs", { _, l, _ -> GuideFixtures.logs(l) })
    )

    val keys: List<String> get() = entries.keys.toList()

    /** Drawable resource name (without extension) for a catalog key. */
    fun drawableName(key: String): String = when {
        key.startsWith("preview_") -> "tour_$key"
        key == "library" -> "tour_preview_library"
        else -> "tour_lesson_$key"
    }

    fun spec(key: String, profile: ShotProfile, locale: ShotLocale, ctx: GuideShotContext): ShotSpec {
        val entry = checkNotNull(entries[key]) { "No guide shot for '$key'" }
        currentProfile = profile
        return ShotSpec(
            key = key, route = entry.route, profile = profile, locale = locale,
            seed = { app -> entry.seed(app, locale, ctx) },
            ready = entry.ready(locale), taps = entry.taps(locale), substitute = entry.substitute
        )
    }
}
