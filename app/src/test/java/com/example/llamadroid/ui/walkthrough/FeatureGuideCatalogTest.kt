package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGuideCatalogTest {
    @Test
    fun `guide recipe and step IDs are unique and recipes are multi step`() {
        val guides = FeatureGuideCatalog.guides
        val guideIds = guides.map { it.id }
        val recipeIds = guides.flatMap { it.recipes }.map { it.id }
        val stepIds = guides.flatMap { it.recipes }.flatMap { it.steps }.map { it.id }

        assertEquals(guideIds.size, guideIds.toSet().size)
        assertEquals(recipeIds.size, recipeIds.toSet().size)
        assertEquals(stepIds.size, stepIds.toSet().size)
        assertTrue(guides.all { it.recipes.isNotEmpty() })
        assertTrue(guides.flatMap { it.recipes }.all { it.steps.size >= 3 })
        assertTrue(guides.all { '{' !in it.route && '}' !in it.route })
        assertTrue(guides.flatMap { it.recipes }.flatMap { it.steps }.all { step ->
            step.route?.let { '{' !in it && '}' !in it } != false
        })
    }

    @Test
    fun `expansion covers model media and document surfaces`() {
        val ids = FeatureGuideCatalog.guides.flatMap { it.recipes }.map { it.id }.toSet()
        val expected = setOf(
            "models.manager", "models.custom_url", "models.saved_links", "models.unknown",
            "models.hf_folder", "models.bundles", "models.import", "models.share",
            "models.llm", "models.sd_components", "models.onnx_bundle", "models.litert_backend", "models.whisper_vad",
            "image.quickstart", "image.inpaint", "image.onnx", "image.background_remove",
            "video.quickstart", "video.families", "video.lingbot", "video.lora", "video.upscale", "video.interpolation", "video.subtitles", "video.summary",
            "voice.tts", "voice.translator", "voice.transcription",
            "documents.pdf", "documents.workflow", "documents.video_summary", "documents.dataset"
        )
        assertTrue(expected.all { it in ids })
        val customDownload = requireNotNull(FeatureGuideCatalog.recipe("models.custom_url")).steps.first()
        assertEquals("models.download", customDownload.targetId)
        assertEquals("models.download", customDownload.eventId)
        FeatureGuideCatalog.guides.flatMap { it.recipes }.flatMap { it.steps }.forEach { step ->
            assertTrue("Missing preview for ${step.id}",
                step.previewKey in setOf("home", "tools") || lessonPreviewResource(step.previewKey.orEmpty()) != null)
        }
        assertTrue(
            setOf(
                "tama.room", "tama.care", "tama.chat_gallery", "tama.farm", "tama.livestock", "tama.store",
                "tama.arcade", "tama.dungeon", "tama.adventure", "tama.gate", "tama.arena",
                "fastsd.gallery"
            ).all { it in ids }
        )
        assertEquals("fastsd_gallery", FeatureGuideCatalog.recipe("fastsd.gallery")?.steps?.first()?.previewKey)
    }

    @Test
    fun `family model recipes keep their real manager routes and manual first action`() {
        val expected = mapOf(
            "models.llm" to "llm_models",
            "models.sd_components" to "sd_models",
            "models.onnx_bundle" to "onnx_models",
            "models.litert_backend" to "litert_models",
            "models.whisper_vad" to "whisper_models"
        )
        expected.forEach { (id, route) ->
            val recipe = requireNotNull(FeatureGuideCatalog.recipe(id))
            assertEquals(route, recipe.steps.first().route)
            assertEquals("models.download", recipe.steps.first().targetId)
            assertEquals("models.download", recipe.steps.first().eventId)
            assertTrue(recipe.steps.all { it.bodyRes != 0 })
        }
    }

    @Test
    fun `route aliases query strings and parameterized routes resolve`() {
        assertEquals("models", FeatureGuideCatalog.forRoute("model_sources?tab=bundles")?.id)
        assertEquals("image", FeatureGuideCatalog.forRoute("image_gen?startMode=2&tab=gallery")?.id)
        assertEquals("documents", FeatureGuideCatalog.forRoute("dataset_project/42")?.id)
        assertEquals("video", FeatureGuideCatalog.forRoute("video_gen?tab=gallery")?.id)
        assertEquals("documents", FeatureGuideCatalog.forRoute("video_sumup")?.id)
        assertEquals("tama", FeatureGuideCatalog.forRoute("tama_gallery")?.id)
        assertEquals("tama_adventure", FeatureGuideCatalog.forRoute("adventure/forest")?.id)
        assertEquals("fastsd", FeatureGuideCatalog.forRoute("fastsd_gallery")?.id)
        assertNull(FeatureGuideCatalog.forRoute(null))
        assertNull(FeatureGuideCatalog.forRoute("unregistered_route"))
    }

    @Test
    fun `expansion aliases are backed by registered screen routes`() {
        fun base(route: String) = route.substringBefore("/{")
        fun routes(vararg values: String) = values.map(::base).toSet()

        val expected = mapOf(
            "models" to routes(
                Screen.ModelHub.route, Screen.ModelSources.route, Screen.ModelManager.route,
                Screen.LLMModels.route, Screen.SDModels.route, Screen.OnnxModels.route,
                Screen.WhisperModels.route, Screen.LiteRtModels.route, "model_share" // LlamaApp registration
            ),
            "image" to routes(Screen.ImageGen.route, Screen.ImageGenUpscale.route, Screen.OnnxImageGen.route, Screen.OnnxBackgroundRemoval.route),
            "video" to routes(Screen.VideoGen.route, Screen.VideoUpscaler.route, Screen.VideoInterpolation.route, Screen.SubtitleBurn.route),
            "voice" to routes(Screen.OnnxTts.route, Screen.OnnxTtsGallery.route, Screen.LiveTranslator.route, Screen.AudioTranscription.route),
            "documents" to routes(Screen.PDFToolbox.route, Screen.PDFSummary.route, Screen.PDFSettings.route, Screen.Workflows.route, "video_sumup", Screen.Dataset.route, Screen.DatasetProject.route)
        )

        expected.forEach { (guideId, registeredRoutes) ->
            assertEquals("Unexpected aliases for $guideId", registeredRoutes, FeatureGuideCatalog.guides.first { it.id == guideId }.routeBases)
        }
    }

    @Test
    fun `feature session IDs round trip without double prefix`() {
        val recipe = FeatureGuideCatalog.recipe("models.saved_links")
        assertNotNull(recipe)
        assertEquals(recipe, FeatureGuideCatalog.recipe("feature:models.saved_links"))
        assertEquals("feature:models.saved_links", FeatureGuideCatalog.sessionId("models.saved_links"))
        assertEquals("feature:models.saved_links", FeatureGuideCatalog.sessionId("feature:models.saved_links"))
        assertNull(FeatureGuideCatalog.recipe("feature:missing"))
    }

    @Test
    fun `non Tama expansion covers registered screen routes`() {
        fun base(route: String) = route.substringBefore("/{")
        fun routes(vararg values: String) = values.map(::base).toSet()

        val expected = mapOf(
            "conversations" to routes(
                Screen.Chat.route, Screen.LlamaServers.route, Screen.LlamaServerList.route,
                Screen.LlamaChatList.route, Screen.LlamaChat.route, Screen.LlamaScheduler.route
            ),
            "agent" to routes(Screen.Agent.route, Screen.AgentWorkspace.route, Screen.AgentInvocation.route),
            "organizer" to routes(Screen.NotesManager.route),
            "library" to routes(Screen.Library.route),
            "knowledge" to routes(Screen.KnowledgeBase.route, Screen.KnowledgeChunkReader.route),
            "offline" to routes(Screen.KiwixHub.route, Screen.ZimManager.route, Screen.KiwixViewer.route),
            "servers" to routes(Screen.AiServersHub.route, Screen.FileServer.route, Screen.OllamaManager.route),
            "distributed" to routes(Screen.DistributedHub.route, Screen.WorkerMode.route, Screen.MasterMode.route, Screen.NetworkVisualization.route),
            "media_runtime" to routes(Screen.SdDistributedHub.route, Screen.SdDistributedWorker.route, Screen.SdDistributedMaster.route, Screen.SdDistributedNetwork.route, Screen.SdDistributedRunConfig.route, Screen.SdDistributedGallery.route),
            "benchmark" to routes(Screen.Benchmark.route, Screen.BenchmarkHistory.route),
            "training" to routes(Screen.QuadtrixTrainer.route, Screen.QuadtrixWebUi.route),
            "termux" to routes(Screen.Termux.route, Screen.TermuxWebView.route, Screen.TermuxFileManager.route),
            "settings" to routes(
                Screen.Settings.route, Screen.Stats.route, Screen.Logs.route,
                "settings_general", "settings_llm", "settings_imagegen", "settings_whisper",
                "settings_upscaler", "settings_prompts", "settings_logs", "about"
            )
        )

        expected.forEach { (guideId, registeredRoutes) ->
            assertEquals("Unexpected aliases for $guideId", registeredRoutes, FeatureGuideCatalog.guides.first { it.id == guideId }.routeBases)
        }
        val tamaExpected = mapOf(
            "tama" to routes(Screen.Tama.route, Screen.TamaChat.route, Screen.TamaGallery.route),
            "tama_farm" to routes(Screen.Farm.route, Screen.Barn.route, Screen.Coop.route),
            "tama_store" to routes(Screen.Store.route),
            "tama_arcade" to routes(Screen.Arcade.route),
            "tama_adventure" to routes(
                Screen.Dungeon.route, Screen.Adventure.route, Screen.AdventureGate.route, Screen.NightArena.route
            ),
            "fastsd" to routes(Screen.FastsdGallery.route)
        )
        tamaExpected.forEach { (guideId, registeredRoutes) ->
            assertEquals("Unexpected aliases for $guideId", registeredRoutes, FeatureGuideCatalog.guides.first { it.id == guideId }.routeBases)
        }
    }

    @Test
    fun `non Tama recipes stay focused by surface`() {
        val ids = FeatureGuideCatalog.guides.flatMap { it.recipes }.map { it.id }.toSet()
        val expected = setOf(
            "conversations.chat", "conversations.native_management", "conversations.history",
            "conversations.scheduler", "conversations.server_selection",
            "agent.quickstart", "agent.projects", "agent.plans", "agent.approvals", "agent.recovery",
            "organizer.notes", "organizer.calendar", "organizer.alarms", "organizer.editors",
            "library.resources", "knowledge.index", "knowledge.search", "knowledge.chunk",
            "offline.manage", "offline.read",
            "servers.hub", "servers.files", "servers.ollama",
            "distributed.roles", "distributed.master", "distributed.worker", "distributed.topology",
            "media_runtime.roles", "media_runtime.topology", "media_runtime.gallery",
            "benchmark.run", "benchmark.history", "training.quadtrix", "training.progress",
            "termux.shell", "termux.webview", "termux.files",
            "settings.appearance", "settings.language", "settings.backups", "settings.prompts",
            "settings.diagnostics", "settings.about_support"
        )
        assertTrue(expected.all { it in ids })
        assertTrue(expected.all { FeatureGuideCatalog.recipe(it)?.steps?.size == 3 })
    }

    @Test
    fun `tama and FastSD recipes stay focused`() {
        val ids = FeatureGuideCatalog.guides.flatMap { it.recipes }.map { it.id }.toSet()
        val expected = setOf(
            "tama.room", "tama.care", "tama.chat_gallery", "tama.farm", "tama.livestock", "tama.store",
            "tama.arcade", "tama.dungeon", "tama.adventure", "tama.gate", "tama.arena",
            "fastsd.gallery"
        )
        assertEquals(expected, ids.filter { it.startsWith("tama.") || it.startsWith("fastsd.") }.toSet())
        assertTrue(expected.all { FeatureGuideCatalog.recipe(it)?.steps?.size == 3 })
    }
}
