package com.example.llamadroid.ui.ai

import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey

/** The task a person is trying to complete, rather than the engine used underneath it. */
enum class ToolCategory(val titleRes: Int) {
    CREATE(R.string.soft_studio_tools_category_create),
    VOICE(R.string.soft_studio_tools_category_voice),
    DOCUMENTS(R.string.ui_redesign_tools_category_documents),
    SHARE_NETWORK(R.string.ui_redesign_tools_category_share_network),
    ADVANCED(R.string.ui_redesign_tools_category_advanced)
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

/**
 * A searchable tool entry. Icons and semantic tonal surfaces are resolved by the hub; catalog
 * entries intentionally contain no emoji or per-tool gradient so the identity stays consistent
 * across themes and locales.
 */
data class AIToolDefinition(
    val id: String,
    val category: ToolCategory,
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int,
    val route: String,
    val routePatterns: List<String> = listOf(route),
    val settingsAction: ToolSettingsAction = ToolSettingsAction.None,
    val keywords: List<String> = emptyList(),
    /** False for tools that now live in another root tab (Chats, Library, Audio). Routes still resolve. */
    val showInHub: Boolean = true,
    /** Pixel-theme sprite; null falls back to the vector icon. Filled from [ToolCatalog.toolSprites]. */
    val sprite: AppSpriteKey? = null
)

private fun tool(
    id: String,
    category: ToolCategory,
    icon: ImageVector,
    titleRes: Int,
    descriptionRes: Int,
    route: String,
    routePatterns: List<String> = listOf(route),
    settingsAction: ToolSettingsAction = ToolSettingsAction.None,
    keywords: List<String> = emptyList(),
    showInHub: Boolean = true
) = AIToolDefinition(
    id = id,
    category = category,
    icon = icon,
    titleRes = titleRes,
    descriptionRes = descriptionRes,
    route = route,
    routePatterns = routePatterns,
    settingsAction = settingsAction,
    keywords = keywords,
    showInHub = showInHub
)

object ToolCatalog {
    /**
     * Keep this list exhaustive for anything reachable from Tools. Engine-specific entries such
     * as ONNX are grouped by the task they perform and retain the engine name in keywords so a
     * search for "onnx" still finds every relevant action.
     */
    /** Pixel sprite per tool id (see [AIToolDefinition.sprite]). */
    val toolSprites: Map<String, AppSpriteKey> = mapOf(
        "chat" to AppSpriteKey.CHATS,
        "agent" to AppSpriteKey.AGENT_ROBOT,
        "native_llama" to AppSpriteKey.MODEL_LLM,
        "image_generation" to AppSpriteKey.MODEL_IMAGE,
        "video_generation" to AppSpriteKey.TOOL_VIDEO,
        "onnx_image_generation" to AppSpriteKey.MODEL_ONNX,
        "onnx_background_removal" to AppSpriteKey.TOOL_IMAGE,
        "video_upscaler" to AppSpriteKey.TOOL_UPSCALE,
        "video_interpolation" to AppSpriteKey.TOOL_VIDEO,
        "subtitle_burn" to AppSpriteKey.TOOL_VIDEO,
        "onnx_tts" to AppSpriteKey.MODEL_AUDIO,
        "audio_workspace" to AppSpriteKey.MODEL_AUDIO,
        "live_translator" to AppSpriteKey.TOOL_TRANSLATE,
        "transcription" to AppSpriteKey.MODEL_WHISPER,
        "video_summary" to AppSpriteKey.TOOL_VIDEO,
        "pdf_tools" to AppSpriteKey.TOOL_PDF,
        "workflows" to AppSpriteKey.TOOL_NOTES,
        "dataset" to AppSpriteKey.TOOL_DATASET,
        "organizer" to AppSpriteKey.ORGANIZER,
        "knowledge" to AppSpriteKey.TOOL_KNOWLEDGE,
        "offline_library" to AppSpriteKey.TOOL_KIWIX,
        "servers_hub" to AppSpriteKey.MODEL_SERVER,
        "distributed_llm" to AppSpriteKey.TOOL_DISTRIBUTED,
        "distributed_media" to AppSpriteKey.TOOL_DISTRIBUTED,
        "quadtrix" to AppSpriteKey.MODEL_LLM,
        "termux" to AppSpriteKey.TOOL_TERMINAL,
        "ollama" to AppSpriteKey.MODEL_SERVER,
        "file_server" to AppSpriteKey.MODEL_SERVER,
        "model_library" to AppSpriteKey.MODEL_BUNDLE
    )

    val tools: List<AIToolDefinition> = listOf(
        tool("chat", ToolCategory.ADVANCED, Icons.AutoMirrored.Filled.Chat, R.string.ai_chat, R.string.ai_chat_desc, Screen.Chat.route,
            routePatterns = listOf(Screen.Chat.route, "${Screen.Chat.route}?port={serverPort}"),
            settingsAction = ToolSettingsAction.Navigate("settings_llm"), keywords = listOf("chat", "conversation", "llm"), showInHub = false),
        tool("agent", ToolCategory.ADVANCED, Icons.Default.SmartToy, R.string.hub_agent, R.string.hub_agent_desc, Screen.Agent.route,
            routePatterns = listOf(Screen.Agent.route, "${Screen.Agent.route}?conversationId={conversationId}&harnessTab={harnessTab}&returnToCaller={returnToCaller}", Screen.AgentWorkspace.route, Screen.AgentProotTerminal.route, Screen.AgentInvocation.route),
            keywords = listOf("agent", "coding", "workspace", "project"), showInHub = false),
        tool("native_llama", ToolCategory.ADVANCED, Icons.Default.Memory, R.string.llama_client_title, R.string.llama_client_desc, Screen.Chats.route,
            routePatterns = listOf(Screen.Chats.route, Screen.LlamaServerList.route, Screen.LlamaServers.route, Screen.LlamaChatList.route, Screen.LlamaChatList.folderRoute, Screen.LlamaScheduler.route, Screen.LlamaChat.route),
            settingsAction = ToolSettingsAction.Navigate("settings_llm"), keywords = listOf("llama", "native", "server", "scheduler"), showInHub = false),
        tool("image_generation", ToolCategory.CREATE, Icons.Default.Image, R.string.ai_image_gen, R.string.ai_image_gen_desc, Screen.ImageGen.route,
            routePatterns = listOf(
                Screen.ImageGen.route,
                "${Screen.ImageGen.route}?startMode={startMode}",
                "${Screen.ImageGen.route}?startMode={startMode}&tab={tab}"
            ),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION), keywords = listOf("stable diffusion", "sd", "txt2img", "img2img", "image")),
        tool("video_generation", ToolCategory.CREATE, Icons.Default.VideoLibrary, R.string.ai_video_gen, R.string.ai_video_gen_desc, Screen.VideoGen.route,
            routePatterns = listOf(Screen.VideoGen.route, "${Screen.VideoGen.route}?tab={tab}"),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION), keywords = listOf("video", "stable diffusion", "txt2vid", "img2vid")),
        tool("onnx_image_generation", ToolCategory.CREATE, Icons.Default.Brush, R.string.ai_onnx_image_gen, R.string.ai_onnx_image_gen_desc, Screen.OnnxImageGen.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route), keywords = listOf("onnx", "ort", "image", "offline")),
        tool("onnx_background_removal", ToolCategory.CREATE, Icons.Default.AutoFixHigh, R.string.ai_onnx_bgr, R.string.ai_onnx_bgr_desc, Screen.OnnxBackgroundRemoval.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route), keywords = listOf("onnx", "background", "cutout", "bgr")),
        tool("video_upscaler", ToolCategory.CREATE, Icons.Default.HighQuality, R.string.ai_video_upscaler, R.string.ai_video_upscaler_desc, Screen.VideoUpscaler.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER), keywords = listOf("upscale", "video", "realesrgan")),
        tool("video_interpolation", ToolCategory.CREATE, Icons.Default.SlowMotionVideo, R.string.ai_video_interpolation, R.string.ai_video_interpolation_desc, Screen.VideoInterpolation.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER), keywords = listOf("interpolate", "rife", "fps", "smooth", "video")),
        tool("subtitle_burn", ToolCategory.CREATE, Icons.Default.Subtitles, R.string.subtitle_burn_title, R.string.subtitle_burn_desc, Screen.SubtitleBurn.route,
            keywords = listOf("subtitle", "video", "burn")),
        tool("onnx_tts", ToolCategory.VOICE, Icons.Default.GraphicEq, R.string.ai_onnx_tts, R.string.ai_onnx_tts_desc, Screen.OnnxTts.route,
            routePatterns = listOf(Screen.OnnxTts.route, Screen.OnnxTtsGallery.route),
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route), keywords = listOf("onnx", "tts", "voice", "audio"), showInHub = false),
        tool("audio_workspace", ToolCategory.VOICE, Icons.Default.GraphicEq, R.string.ui_redesign_tools_audio_studio, R.string.audio_workspace_subtitle, Screen.AudioWorkspace.route,
            routePatterns = listOf(
                Screen.AudioWorkspace.route,
                "${Screen.AudioWorkspace.route}?section={section}"
            ),
            settingsAction = ToolSettingsAction.Navigate(Screen.AudioModels.route), keywords = listOf("audio", "tts", "speech", "voice", "clone", "qwen", "pocket", "supertonic")),
        tool("live_translator", ToolCategory.VOICE, Icons.Default.Translate, R.string.live_translator_title, R.string.live_translator_hub_desc, Screen.LiveTranslator.route,
            keywords = listOf("translate", "voice", "call", "bilingual")),
        tool("transcription", ToolCategory.VOICE, Icons.Default.Mic, R.string.ai_transcription, R.string.ai_transcription_desc, Screen.AudioTranscription.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.WHISPER), keywords = listOf("whisper", "transcribe", "audio", "voice")),
        tool("video_summary", ToolCategory.DOCUMENTS, Icons.Default.Summarize, R.string.ai_video_sumup, R.string.ai_video_sumup_desc, "video_sumup",
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY), keywords = listOf("summary", "video", "transcript", "notes")),
        tool("pdf_tools", ToolCategory.DOCUMENTS, Icons.Default.PictureAsPdf, R.string.ai_pdf_tools, R.string.ai_pdf_tools_desc, Screen.PDFToolbox.route,
            routePatterns = listOf(Screen.PDFToolbox.route, Screen.PDFSummary.route),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY), keywords = listOf("pdf", "ocr", "summary", "translation", "documents")),
        tool("workflows", ToolCategory.DOCUMENTS, Icons.Default.AccountTree, R.string.hub_workflows, R.string.hub_workflows_desc, Screen.Workflows.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY), keywords = listOf("workflow", "batch", "summary", "translation")),
        tool("dataset", ToolCategory.DOCUMENTS, Icons.Default.Folder, R.string.hub_dataset, R.string.hub_dataset_desc, Screen.Dataset.route,
            routePatterns = listOf(Screen.Dataset.route, Screen.DatasetProject.route), keywords = listOf("dataset", "training", "fine tuning", "documents")),
        tool("organizer", ToolCategory.DOCUMENTS, Icons.Default.CalendarMonth, R.string.organizer_title, R.string.organizer_calendar_subtitle, Screen.NotesManager.route,
            keywords = listOf("organizer", "calendar", "alarms", "notes", "todo"), showInHub = false),
        tool("knowledge", ToolCategory.DOCUMENTS, Icons.Default.Search, R.string.kb_title, R.string.kb_folder_subtitle, Screen.KnowledgeBase.route,
            keywords = listOf("knowledge", "rag", "embedding", "search", "offline"), showInHub = false),
        tool("offline_library", ToolCategory.SHARE_NETWORK, Icons.Default.Storage, R.string.ui_redesign_tools_offline_wiki, R.string.zim_browse_catalog, Screen.ZimManager.route,
            keywords = listOf("zim", "kiwix", "offline", "wikipedia", "library", "wiki")),
        tool("servers_hub", ToolCategory.SHARE_NETWORK, Icons.Default.Web, R.string.ui_redesign_tools_share_to_browser, R.string.ai_servers_hub_card_desc, Screen.AiServersHub.route,
            keywords = listOf("server", "web", "lan", "infrastructure")),
        tool("distributed_llm", ToolCategory.SHARE_NETWORK, Icons.Default.Share, R.string.dashboard_distributed_title, R.string.dashboard_setup_distributed_desc, Screen.DistributedHub.route,
            keywords = listOf("distributed", "llama", "rpc", "network", "inference")),
        tool("distributed_media", ToolCategory.SHARE_NETWORK, Icons.Default.Lan, R.string.dashboard_sd_distributed_title, R.string.dashboard_sd_distributed_desc, Screen.SdDistributedHub.route,
            keywords = listOf("distributed", "stable diffusion", "media", "rpc", "video")),
        tool("benchmark", ToolCategory.ADVANCED, Icons.Default.Speed, R.string.hub_benchmark, R.string.hub_benchmark_desc, Screen.Benchmark.route,
            routePatterns = listOf(Screen.Benchmark.route, Screen.BenchmarkHistory.route), keywords = listOf("benchmark", "threads", "performance")),
        tool("quadtrix", ToolCategory.ADVANCED, Icons.Default.ModelTraining, R.string.quadtrix_title, R.string.quadtrix_hub_desc, Screen.QuadtrixTrainer.route,
            routePatterns = listOf(Screen.QuadtrixTrainer.route, Screen.QuadtrixWebUi.route), keywords = listOf("quadtrix", "training", "webui", "distributed")),
        tool("termux", ToolCategory.ADVANCED, Icons.Default.Terminal, R.string.hub_termux, R.string.hub_termux_desc, Screen.Termux.route,
            routePatterns = listOf(Screen.Termux.route, Screen.TermuxWebView.route, Screen.TermuxFileManager.route), keywords = listOf("termux", "ssh", "files", "infrastructure")),
        tool("ollama", ToolCategory.SHARE_NETWORK, Icons.Default.Cloud, R.string.ollama_title, R.string.ollama_desc, Screen.OllamaManager.route,
            keywords = listOf("ollama", "server", "model", "infrastructure")),
        tool("file_server", ToolCategory.SHARE_NETWORK, Icons.Default.Public, R.string.dashboard_file_server, R.string.soft_studio_home_file_server_desc, Screen.FileServer.route,
            keywords = listOf("file server", "share", "lan", "infrastructure")),
        tool("model_library", ToolCategory.ADVANCED, Icons.Default.Tune, R.string.models_hub, R.string.models_hub_subtitle, Screen.ModelHub.route,
            keywords = listOf("models", "download", "import", "engine"), showInHub = false)
    ).map { it.copy(sprite = toolSprites[it.id]) }

    val aiRoutePatterns: Set<String> = tools
        .flatMap { it.routePatterns }
        .toSet()

    fun matchesRoute(route: String?): Boolean {
        if (route.isNullOrBlank()) return false
        return route in aiRoutePatterns
    }
}
