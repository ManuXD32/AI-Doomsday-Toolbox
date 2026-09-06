package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for the media/document guide surfaces.
 *
 * The feature catalog is data, so a route or focus ID can otherwise drift away from the
 * composable that owns it while the catalog tests still pass. This checker deliberately reads
 * the source files and verifies the actual target/event registrations used by these recipes.
 */
class FeatureGuideCoverageTest {
    @Test
    fun `media and document guide routes match registered screen aliases`() {
        fun base(route: String) = route.substringBefore("/{")
        val expected = mapOf(
            "image" to setOf(
                base(Screen.ImageGen.route),
                base(Screen.ImageGenUpscale.route),
                base(Screen.OnnxImageGen.route),
                base(Screen.OnnxBackgroundRemoval.route)
            ),
            "video" to setOf(
                base(Screen.VideoGen.route),
                base(Screen.VideoUpscaler.route),
                base(Screen.VideoInterpolation.route),
                base(Screen.SubtitleBurn.route)
            ),
            "voice" to setOf(
                base(Screen.OnnxTts.route),
                base(Screen.OnnxTtsGallery.route),
                base(Screen.LiveTranslator.route),
                base(Screen.AudioTranscription.route)
            ),
            "documents" to setOf(
                base(Screen.PDFToolbox.route),
                base(Screen.PDFSummary.route),
                base(Screen.PDFSettings.route),
                base(Screen.Workflows.route),
                "video_sumup",
                base(Screen.Dataset.route),
                base(Screen.DatasetProject.route)
            ),
            "media_runtime" to setOf(
                base(Screen.SdDistributedHub.route),
                base(Screen.SdDistributedWorker.route),
                base(Screen.SdDistributedMaster.route),
                base(Screen.SdDistributedNetwork.route),
                base(Screen.SdDistributedRunConfig.route),
                base(Screen.SdDistributedGallery.route)
            )
        )

        expected.forEach { (guideId, routes) ->
            val guide = FeatureGuideCatalog.guides.first { it.id == guideId }
            assertEquals("Unexpected route aliases for $guideId", routes, guide.routeBases)
            assertTrue("$guideId has no canonical route", guide.route in routes)
            routes.forEach { route ->
                assertEquals("Route $route did not resolve to $guideId", guideId, FeatureGuideCatalog.forRoute(route)?.id)
            }
        }
    }

    @Test
    fun `family model recipes point at the actual family managers`() {
        val expected = mapOf(
            "models.llm" to Screen.LLMModels.route,
            "models.sd_components" to Screen.SDModels.route,
            "models.onnx_bundle" to Screen.OnnxModels.route,
            "models.litert_backend" to Screen.LiteRtModels.route,
            "models.whisper_vad" to Screen.WhisperModels.route
        )
        val modelSources = mapOf(
            "llm_models" to source("app/src/main/java/com/example/llamadroid/ui/models/ModelManagerScreen.kt"),
            "sd_models" to source("app/src/main/java/com/example/llamadroid/ui/ai/SDModelsScreen.kt"),
            "onnx_models" to source("app/src/main/java/com/example/llamadroid/ui/models/OnnxModelsScreen.kt"),
            "litert_models" to source("app/src/main/java/com/example/llamadroid/ui/models/LiteRtModelsScreen.kt"),
            "whisper_models" to source("app/src/main/java/com/example/llamadroid/ui/models/WhisperModelsScreen.kt")
        )
        expected.forEach { (recipeId, route) ->
            val recipe = requireNotNull(FeatureGuideCatalog.recipe(recipeId))
            assertEquals(route.substringBefore("/{"), recipe.steps.first().route)
            assertTrue(recipe.steps.any { it.targetId == "models.download" && it.eventId == "models.download" })
            val sourceText = modelSources.getValue(recipe.steps.first().route.orEmpty())
            assertTrue("${recipe.steps.first().route} lacks models.download target", sourceText.contains("\"models.download\""))
        }
    }

    @Test
    fun `owned route surfaces retain their real tabs dialogs and primary controls`() {
        val anchors = mapOf(
            "app/src/main/java/com/example/llamadroid/ui/ai/ImageGenScreen.kt" to listOf(
                "fun ImageGenScreen(", "AppScrollableTabRow(", "GenerationOptionsInfoDialog", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/OnnxImageGenScreen.kt" to listOf(
                "fun OnnxImageGenScreen(", "AppScrollableTabRow(", "OnnxFullscreenImageDialog", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/OnnxBackgroundRemovalScreen.kt" to listOf(
                "fun OnnxBackgroundRemovalScreen(", "AppScrollableTabRow(", "Dialog(", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/OnnxTtsScreen.kt" to listOf(
                "fun OnnxTtsScreen(", "LazyColumn(", "OnnxTtsModelPicker", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/LiveTranslatorScreen.kt" to listOf(
                "fun LiveTranslatorScreen(", "LazyColumn(", "LiveTranslatorActiveDialog", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/AudioTranscriptionScreen.kt" to listOf(
                "fun AudioTranscriptionScreen(", "showRecordingDialog", "showModelPicker"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/VideoUpscalerScreen.kt" to listOf(
                "fun VideoUpscalerScreen(", "verticalScroll(", "startUpscale"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/VideoInterpolationScreen.kt" to listOf(
                "fun VideoInterpolationScreen(", "TabRow(", "MediaModelDownloadDialog", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/SubtitleBurnScreen.kt" to listOf(
                "fun SubtitleBurnScreen(", "LazyColumn(", "showInfoCard", "WalkthroughScrollOwner"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/VideoSumupScreen.kt" to listOf(
                "fun VideoSumupScreen(", "verticalScroll(", "startSummary"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/WorkflowsScreen.kt" to listOf(
                "fun WorkflowsScreen(", "WorkflowCard", "AlertDialog"
            ),
            "app/src/main/java/com/example/llamadroid/ui/pdf/PDFToolboxScreen.kt" to listOf(
                "fun PDFToolboxScreen(", "PDFToolCard", "showExpert"
            ),
            "app/src/main/java/com/example/llamadroid/ui/pdf/PDFSummaryScreen.kt" to listOf(
                "fun PDFSummaryScreen(", "verticalScroll(", "startSummary"
            ),
            "app/src/main/java/com/example/llamadroid/ui/ai/DatasetScreen.kt" to listOf(
                "fun DatasetScreen(", "LazyColumn(", "showCreateDialog"
            ),
            "app/src/main/java/com/example/llamadroid/ui/dataset/DatasetProjectScreen.kt" to listOf(
                "fun DatasetProjectScreen(", "ScrollableTabRow(", "DatasetRunSequenceDialog"
            ),
            "app/src/main/java/com/example/llamadroid/ui/distributed/SdDistributedScreens.kt" to listOf(
                "fun SdDistributedHubScreen(", "fun SdDistributedGalleryScreen(",
                "fun SdDistributedNetworkScreen(", "SdGeneratedMediaDetailDialog"
            )
        )

        anchors.forEach { (relativePath, requiredTokens) ->
            val text = source(relativePath)
            requiredTokens.forEach { token ->
                assertTrue("$relativePath is missing route/tab/dialog anchor $token", text.contains(token))
            }
        }
    }

    @Test
    fun `owned tab dialog and option targets have real registrations and connected events`() {
        val sourceFiles = mapOf(
            "ImageGenScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/ImageGenScreen.kt"),
            "OnnxImageGenScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/OnnxImageGenScreen.kt"),
            "OnnxBackgroundRemovalScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/OnnxBackgroundRemovalScreen.kt"),
            "OnnxTtsScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/OnnxTtsScreen.kt"),
            "LiveTranslatorScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/LiveTranslatorScreen.kt"),
            "AudioTranscriptionScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/AudioTranscriptionScreen.kt"),
            "VideoUpscalerScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/VideoUpscalerScreen.kt"),
            "VideoInterpolationScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/VideoInterpolationScreen.kt"),
            "SubtitleBurnScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/SubtitleBurnScreen.kt"),
            "VideoSumupScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/VideoSumupScreen.kt"),
            "WorkflowsScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/WorkflowsScreen.kt"),
            "PDFToolboxScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/pdf/PDFToolboxScreen.kt"),
            "PDFSummaryScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/pdf/PDFSummaryScreen.kt"),
            "DatasetScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/ai/DatasetScreen.kt"),
            "DatasetProjectScreen.kt" to source("app/src/main/java/com/example/llamadroid/ui/dataset/DatasetProjectScreen.kt"),
            "SdDistributedScreens.kt" to source("app/src/main/java/com/example/llamadroid/ui/distributed/SdDistributedScreens.kt")
        )
        val ownership = mapOf(
            "image.options" to setOf("ImageGenScreen.kt"),
            "image.onnx.input" to setOf("OnnxImageGenScreen.kt"),
            "image.background.input" to setOf("OnnxBackgroundRemovalScreen.kt"),
            "video.upscale.options" to setOf("VideoUpscalerScreen.kt"),
            "video.interpolation.options" to setOf("VideoInterpolationScreen.kt"),
            "video.subtitle.options" to setOf("SubtitleBurnScreen.kt"),
            "voice.tts.input" to setOf("OnnxTtsScreen.kt"),
            "voice.translator.input" to setOf("LiveTranslatorScreen.kt"),
            "voice.transcription.input" to setOf("AudioTranscriptionScreen.kt"),
            "documents.pdf.input" to setOf("PDFToolboxScreen.kt", "PDFSummaryScreen.kt"),
            "documents.summary.input" to setOf("VideoSumupScreen.kt"),
            "documents.workflow.canvas" to setOf("WorkflowsScreen.kt"),
            "documents.dataset.tabs" to setOf("DatasetScreen.kt", "DatasetProjectScreen.kt"),
            "media.roles" to setOf("SdDistributedScreens.kt"),
            "media.workers" to setOf("SdDistributedScreens.kt"),
            "media.gallery" to setOf("SdDistributedScreens.kt")
        )

        val ownedRecipes = FeatureGuideCatalog.guides
            .filter { it.id in setOf("image", "video", "voice", "documents", "media_runtime") }
            .flatMap { it.recipes }
        val catalogIds = ownedRecipes.flatMap { it.steps }
            .flatMap { listOfNotNull(it.targetId, it.eventId) }
            .toSet()

        ownership.forEach { (id, expectedFiles) ->
            assertTrue("$id is absent from the feature catalog", id in catalogIds)
            val actualFiles = sourceFiles.filterValues { text ->
                text.contains("\"$id\"")
            }.keys
            assertEquals("Unexpected owner files for $id", expectedFiles, actualFiles)
            expectedFiles.forEach { file ->
                val text = sourceFiles.getValue(file)
                assertTrue("$file does not register target/event $id", text.contains("\"$id\""))
            }
            val steps = ownedRecipes.flatMap { it.steps }.filter { id == it.targetId || id == it.eventId }
            if (id == "media.workers") {
                assertTrue("Passive topology must use Next", steps.any { it.targetId == id && it.eventId == null })
            } else {
                assertTrue("$id has no target and event pair", steps.any { it.targetId == id && it.eventId == id })
            }
        }
    }

    private fun source(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            listOf(
                File(directory, relativePath),
                File(directory, "app/$relativePath")
            ).firstOrNull { it.isFile }?.let { return it.readText() }
            directory = directory.parentFile ?: break
        }
        error("Could not find source file $relativePath")
    }
}
