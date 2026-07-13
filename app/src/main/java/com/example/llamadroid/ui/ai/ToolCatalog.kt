package com.example.llamadroid.ui.ai

import androidx.compose.ui.graphics.Color
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

enum class ToolCategory(val titleRes: Int) {
    CHAT_AGENTS(R.string.ai_tools_category_chat_agents),
    GENERATION(R.string.ai_tools_category_generation),
    ONNX(R.string.ai_tools_category_onnx),
    VOICE_MEDIA(R.string.ai_tools_category_voice_media),
    DOCUMENTS_WORKFLOWS(R.string.ai_tools_category_documents_workflows),
    SERVERS_UTILITIES(R.string.ai_tools_category_servers_utilities)
}

enum class ToolSettingsSheet {
    IMAGE_GENERATION,
    WHISPER,
    VIDEO_UPSCALER,
    PDF_SUMMARY
}

sealed class ToolSettingsAction {
    data object None : ToolSettingsAction()
    data class Sheet(val sheet: ToolSettingsSheet) : ToolSettingsAction()
    data class Navigate(val route: String) : ToolSettingsAction()
}

data class AIToolDefinition(
    val id: String,
    val category: ToolCategory,
    val emoji: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val gradientColors: List<Color>,
    val route: String,
    val routePatterns: List<String> = listOf(route),
    val settingsAction: ToolSettingsAction = ToolSettingsAction.None,
    val keywords: List<String> = emptyList()
)

object ToolCatalog {
    val tools: List<AIToolDefinition> = listOf(
        AIToolDefinition(
            id = "chat",
            category = ToolCategory.CHAT_AGENTS,
            emoji = "\uD83D\uDCAC",
            titleRes = R.string.ai_chat,
            descriptionRes = R.string.ai_chat_desc,
            gradientColors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.15f),
                Color(0xFF388E3C).copy(alpha = 0.3f)
            ),
            route = Screen.Chat.route,
            settingsAction = ToolSettingsAction.Navigate("settings_llm"),
            keywords = listOf("chat", "conversation", "llm")
        ),
        AIToolDefinition(
            id = "agent",
            category = ToolCategory.CHAT_AGENTS,
            emoji = "\uD83E\uDD16",
            titleRes = R.string.hub_agent,
            descriptionRes = R.string.hub_agent_desc,
            gradientColors = listOf(
                Color(0xFF673AB7).copy(alpha = 0.15f),
                Color(0xFF512DA8).copy(alpha = 0.3f)
            ),
            route = Screen.Agent.route,
            routePatterns = listOf(Screen.Agent.route, Screen.AgentWorkspace.route),
            keywords = listOf("agent", "coding", "workspace")
        ),
        AIToolDefinition(
            id = "native_llama",
            category = ToolCategory.CHAT_AGENTS,
            emoji = "\uD83D\uDC0D",
            titleRes = R.string.llama_client_title,
            descriptionRes = R.string.llama_client_desc,
            gradientColors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFFF8A65).copy(alpha = 0.3f)
            ),
            route = Screen.LlamaServerList.route,
            routePatterns = listOf(
                Screen.LlamaServerList.route,
                Screen.LlamaChatList.route,
                Screen.LlamaChatList.folderRoute,
                Screen.LlamaScheduler.route,
                Screen.LlamaChat.route
            ),
            settingsAction = ToolSettingsAction.Navigate("settings_llm"),
            keywords = listOf("llama", "native", "server", "scheduler")
        ),
        AIToolDefinition(
            id = "image_generation",
            category = ToolCategory.GENERATION,
            emoji = "\uD83C\uDFA8",
            titleRes = R.string.ai_image_gen,
            descriptionRes = R.string.ai_image_gen_desc,
            gradientColors = listOf(
                Color(0xFF2196F3).copy(alpha = 0.15f),
                Color(0xFF1976D2).copy(alpha = 0.3f)
            ),
            route = Screen.ImageGen.route,
            routePatterns = listOf(Screen.ImageGen.route, "${Screen.ImageGen.route}?startMode={startMode}"),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION),
            keywords = listOf("stable diffusion", "sd", "txt2img", "img2img")
        ),
        AIToolDefinition(
            id = "video_generation",
            category = ToolCategory.GENERATION,
            emoji = "\uD83C\uDFA5",
            titleRes = R.string.ai_video_gen,
            descriptionRes = R.string.ai_video_gen_desc,
            gradientColors = listOf(
                Color(0xFFE53935).copy(alpha = 0.15f),
                Color(0xFFC62828).copy(alpha = 0.3f)
            ),
            route = Screen.VideoGen.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION),
            keywords = listOf("video", "stable diffusion", "txt2vid", "img2vid")
        ),
        AIToolDefinition(
            id = "onnx_image_generation",
            category = ToolCategory.ONNX,
            emoji = "\uD83E\uDDE0",
            titleRes = R.string.ai_onnx_image_gen,
            descriptionRes = R.string.ai_onnx_image_gen_desc,
            gradientColors = listOf(
                Color(0xFFFFA726).copy(alpha = 0.15f),
                Color(0xFFFB8C00).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxImageGen.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx", "ort", "image")
        ),
        AIToolDefinition(
            id = "onnx_tts",
            category = ToolCategory.ONNX,
            emoji = "\uD83D\uDD0A",
            titleRes = R.string.ai_onnx_tts,
            descriptionRes = R.string.ai_onnx_tts_desc,
            gradientColors = listOf(
                Color(0xFF7E57C2).copy(alpha = 0.15f),
                Color(0xFF5E35B1).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxTts.route,
            routePatterns = listOf(Screen.OnnxTts.route, Screen.OnnxTtsGallery.route),
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx", "tts", "voice", "audio")
        ),
        AIToolDefinition(
            id = "onnx_background_removal",
            category = ToolCategory.ONNX,
            emoji = "\u2702\uFE0F",
            titleRes = R.string.ai_onnx_bgr,
            descriptionRes = R.string.ai_onnx_bgr_desc,
            gradientColors = listOf(
                Color(0xFF26A69A).copy(alpha = 0.15f),
                Color(0xFF00897B).copy(alpha = 0.3f)
            ),
            route = Screen.OnnxBackgroundRemoval.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx", "background", "cutout", "bgr")
        ),
        AIToolDefinition(
            id = "live_translator",
            category = ToolCategory.VOICE_MEDIA,
            emoji = "\uD83C\uDF10",
            titleRes = R.string.live_translator_title,
            descriptionRes = R.string.live_translator_hub_desc,
            gradientColors = listOf(
                Color(0xFF00897B).copy(alpha = 0.15f),
                Color(0xFF3949AB).copy(alpha = 0.3f)
            ),
            route = Screen.LiveTranslator.route,
            keywords = listOf("translate", "voice", "call")
        ),
        AIToolDefinition(
            id = "transcription",
            category = ToolCategory.VOICE_MEDIA,
            emoji = "\uD83C\uDF99\uFE0F",
            titleRes = R.string.ai_transcription,
            descriptionRes = R.string.ai_transcription_desc,
            gradientColors = listOf(
                Color(0xFF00BCD4).copy(alpha = 0.15f),
                Color(0xFF00ACC1).copy(alpha = 0.3f)
            ),
            route = Screen.AudioTranscription.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.WHISPER),
            keywords = listOf("whisper", "transcribe", "audio")
        ),
        AIToolDefinition(
            id = "video_upscaler",
            category = ToolCategory.VOICE_MEDIA,
            emoji = "\uD83C\uDFAC",
            titleRes = R.string.ai_video_upscaler,
            descriptionRes = R.string.ai_video_upscaler_desc,
            gradientColors = listOf(
                Color(0xFF9C27B0).copy(alpha = 0.15f),
                Color(0xFF7B1FA2).copy(alpha = 0.3f)
            ),
            route = Screen.VideoUpscaler.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER),
            keywords = listOf("upscale", "video", "realesrgan")
        ),
        AIToolDefinition(
            id = "subtitle_burn",
            category = ToolCategory.VOICE_MEDIA,
            emoji = "\uD83D\uDD24",
            titleRes = R.string.subtitle_burn_title,
            descriptionRes = R.string.subtitle_burn_desc,
            gradientColors = listOf(
                Color(0xFFFF9800).copy(alpha = 0.15f),
                Color(0xFFF57C00).copy(alpha = 0.3f)
            ),
            route = Screen.SubtitleBurn.route,
            keywords = listOf("subtitle", "video", "burn")
        ),
        AIToolDefinition(
            id = "video_summary",
            category = ToolCategory.VOICE_MEDIA,
            emoji = "\uD83C\uDFA5",
            titleRes = R.string.ai_video_sumup,
            descriptionRes = R.string.ai_video_sumup_desc,
            gradientColors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFE64A19).copy(alpha = 0.3f)
            ),
            route = "video_sumup",
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("summary", "video", "transcript")
        ),
        AIToolDefinition(
            id = "pdf_tools",
            category = ToolCategory.DOCUMENTS_WORKFLOWS,
            emoji = "\uD83D\uDCC4",
            titleRes = R.string.ai_pdf_tools,
            descriptionRes = R.string.ai_pdf_tools_desc,
            gradientColors = listOf(
                Color(0xFFE91E63).copy(alpha = 0.15f),
                Color(0xFFC2185B).copy(alpha = 0.3f)
            ),
            route = "pdf_toolbox",
            routePatterns = listOf("pdf_toolbox", "pdf_summary", "settings_pdf_translation"),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("pdf", "ocr", "summary", "translation")
        ),
        AIToolDefinition(
            id = "workflows",
            category = ToolCategory.DOCUMENTS_WORKFLOWS,
            emoji = "\u2699\uFE0F",
            titleRes = R.string.hub_workflows,
            descriptionRes = R.string.hub_workflows_desc,
            gradientColors = listOf(
                Color(0xFF607D8B).copy(alpha = 0.15f),
                Color(0xFF455A64).copy(alpha = 0.3f)
            ),
            route = Screen.Workflows.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("workflow", "batch", "summary", "translation")
        ),
        AIToolDefinition(
            id = "dataset",
            category = ToolCategory.DOCUMENTS_WORKFLOWS,
            emoji = "\uD83D\uDCCA",
            titleRes = R.string.hub_dataset,
            descriptionRes = R.string.hub_dataset_desc,
            gradientColors = listOf(
                Color(0xFF00BCD4).copy(alpha = 0.15f),
                Color(0xFF0097A7).copy(alpha = 0.3f)
            ),
            route = Screen.Dataset.route,
            routePatterns = listOf(Screen.Dataset.route, Screen.DatasetProject.route),
            keywords = listOf("dataset", "training", "fine tuning")
        ),
        AIToolDefinition(
            id = "servers_hub",
            category = ToolCategory.SERVERS_UTILITIES,
            emoji = "\uD83C\uDF10",
            titleRes = R.string.ai_servers_hub_title,
            descriptionRes = R.string.ai_servers_hub_card_desc,
            gradientColors = listOf(
                Color(0xFF00A896).copy(alpha = 0.15f),
                Color(0xFF3A86FF).copy(alpha = 0.28f)
            ),
            route = Screen.AiServersHub.route,
            keywords = listOf("server", "web", "lan")
        ),
        AIToolDefinition(
            id = "benchmark",
            category = ToolCategory.SERVERS_UTILITIES,
            emoji = "\u26A1",
            titleRes = R.string.hub_benchmark,
            descriptionRes = R.string.hub_benchmark_desc,
            gradientColors = listOf(
                Color(0xFFFFEB3B).copy(alpha = 0.15f),
                Color(0xFFFBC02D).copy(alpha = 0.3f)
            ),
            route = Screen.Benchmark.route,
            routePatterns = listOf(Screen.Benchmark.route, Screen.BenchmarkHistory.route),
            keywords = listOf("benchmark", "threads", "performance")
        ),
        AIToolDefinition(
            id = "quadtrix",
            category = ToolCategory.SERVERS_UTILITIES,
            emoji = "\uD83E\uDDEC",
            titleRes = R.string.quadtrix_title,
            descriptionRes = R.string.quadtrix_hub_desc,
            gradientColors = listOf(
                Color(0xFF26A69A).copy(alpha = 0.15f),
                Color(0xFF00897B).copy(alpha = 0.3f)
            ),
            route = Screen.QuadtrixTrainer.route,
            routePatterns = listOf(Screen.QuadtrixTrainer.route, Screen.QuadtrixWebUi.route),
            keywords = listOf("quadtrix", "training", "webui")
        ),
        AIToolDefinition(
            id = "termux",
            category = ToolCategory.SERVERS_UTILITIES,
            emoji = "\uD83D\uDDA5\uFE0F",
            titleRes = R.string.hub_termux,
            descriptionRes = R.string.hub_termux_desc,
            gradientColors = listOf(
                Color(0xFF37474F).copy(alpha = 0.15f),
                Color(0xFF263238).copy(alpha = 0.3f)
            ),
            route = Screen.Termux.route,
            routePatterns = listOf(Screen.Termux.route, Screen.TermuxWebView.route, Screen.TermuxFileManager.route),
            keywords = listOf("termux", "ssh", "files")
        ),
        AIToolDefinition(
            id = "ollama",
            category = ToolCategory.SERVERS_UTILITIES,
            emoji = "\uD83E\uDD99",
            titleRes = R.string.ollama_title,
            descriptionRes = R.string.ollama_desc,
            gradientColors = listOf(
                Color(0xFF000000).copy(alpha = 0.15f),
                Color(0xFF333333).copy(alpha = 0.3f)
            ),
            route = Screen.OllamaManager.route,
            keywords = listOf("ollama", "server", "model")
        )
    )

    val aiRoutePatterns: Set<String> = tools
        .flatMap { it.routePatterns }
        .toSet()

    fun matchesRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        return route in aiRoutePatterns
    }
}
