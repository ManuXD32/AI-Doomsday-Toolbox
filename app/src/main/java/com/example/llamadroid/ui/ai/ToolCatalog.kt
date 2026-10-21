package com.example.llamadroid.ui.ai

import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
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
import com.example.llamadroid.ui.servers.SERVERS_HUB_ROUTE
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey

/** The task a person is trying to complete, rather than the engine used underneath it. */
enum class ToolCategory(val titleRes: Int) {
    ASSISTANTS(R.string.tool_catalog_category_assistants),
    CREATE(R.string.soft_studio_tools_category_create),
    VOICE(R.string.soft_studio_tools_category_voice),
    DOCUMENTS(R.string.ui_redesign_tools_category_documents),
    SHARE_NETWORK(R.string.ui_redesign_tools_category_share_network),
    ADVANCED(R.string.ui_redesign_tools_category_advanced)
}

/**
 * Where an entry is discoverable. [GRID] entries are tiles on the Tools tab (and appear in
 * search); [SEARCH] entries (shortcuts, other tabs, settings pages) are only found through search;
 * [HIDDEN] entries only own their routes.
 */
enum class ToolVisibility { GRID, SEARCH, HIDDEN }

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
    /** Spanish search words; global search matches English and Spanish whatever the app language. */
    val keywordsEs: List<String> = emptyList(),
    val visibility: ToolVisibility = ToolVisibility.GRID,
    /** App version name (for example "0.9999") that introduced the entry; drives the "New" badge. */
    val addedIn: String? = null,
    /** True for Settings pages: listed under Settings in global search instead of Tools. */
    val isSettingsPage: Boolean = false,
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
    keywordsEs: List<String> = emptyList(),
    visibility: ToolVisibility = ToolVisibility.GRID,
    addedIn: String? = null,
    isSettingsPage: Boolean = false
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
    keywordsEs = keywordsEs,
    visibility = visibility,
    addedIn = addedIn,
    isSettingsPage = isSettingsPage
)

/** A Settings page that is only reachable through search (the Settings hub owns the real list). */
private fun settingsPage(
    id: String,
    icon: ImageVector,
    titleRes: Int,
    descriptionRes: Int,
    route: String,
    keywords: List<String>,
    keywordsEs: List<String>
) = tool(
    id, ToolCategory.ADVANCED, icon, titleRes, descriptionRes, route,
    keywords = keywords, keywordsEs = keywordsEs,
    visibility = ToolVisibility.SEARCH, isSettingsPage = true
)

/** A Workflows recipe shortcut. Workflows has no preset argument yet, so it opens the Workflows page. */
private fun recipe(
    id: String,
    category: ToolCategory,
    icon: ImageVector,
    titleRes: Int,
    descriptionRes: Int,
    keywords: List<String>,
    keywordsEs: List<String>
) = tool(
    id, category, icon, titleRes, descriptionRes, Screen.Workflows.route,
    keywords = keywords + "workflow", keywordsEs = keywordsEs + "flujo de trabajo",
    visibility = ToolVisibility.SEARCH
)

object ToolCatalog {
    private const val VERSION_CHAT_STEPS = "0.9998"
    private const val VERSION_ROUND7 = "0.9999"

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

    // Existing ids are kept on purpose: pins, walkthrough previews and feature guides key on them.
    // native_llama is the Chats entry, knowledge the Knowledge base, organizer the Organizer
    // (notes_manager route) and model_library the Models hub (model_hub route).
    val tools: List<AIToolDefinition> = listOf(
        // Assistants
        tool("native_llama", ToolCategory.ASSISTANTS, Icons.Default.Forum, R.string.llama_client_title, R.string.llama_client_desc, Screen.Chats.route,
            routePatterns = listOf(Screen.Chats.route, Screen.LlamaChatList.route, Screen.LlamaChatList.folderRoute, Screen.LlamaChat.route, Screen.NativeChatDelegation.route, Screen.ServerProfileEditor.route),
            settingsAction = ToolSettingsAction.Navigate("settings_llm"),
            keywords = listOf("chats", "chat", "llama", "native", "conversation", "visible steps"),
            keywordsEs = listOf("chats", "chat", "conversacion", "charla", "pasos visibles"),
            addedIn = VERSION_CHAT_STEPS),
        tool("agent", ToolCategory.ASSISTANTS, Icons.Default.SmartToy, R.string.hub_agent, R.string.hub_agent_desc, Screen.Agent.route,
            routePatterns = listOf(Screen.Agent.route, "${Screen.Agent.route}?conversationId={conversationId}&harnessTab={harnessTab}&returnToCaller={returnToCaller}", Screen.AgentWorkspace.route, Screen.AgentProotTerminal.route, Screen.AgentInvocation.route),
            keywords = listOf("agent", "coding", "workspace", "project", "deepseek", "harness"),
            keywordsEs = listOf("agente", "programar", "codigo", "espacio de trabajo", "proyecto")),
        tool("knowledge", ToolCategory.ASSISTANTS, Icons.Default.Search, R.string.kb_title, R.string.kb_folder_subtitle, Screen.KnowledgeBase.route,
            routePatterns = listOf(Screen.KnowledgeBase.route, Screen.KnowledgeChunkReader.route),
            keywords = listOf("knowledge base", "knowledge", "rag", "embedding", "search", "offline"),
            keywordsEs = listOf("base de conocimiento", "conocimiento", "busqueda", "documentos", "sin conexion")),
        tool("llama_scheduler", ToolCategory.ASSISTANTS, Icons.Default.Schedule, R.string.llama_scheduler_title, R.string.llama_scheduler_subtitle, Screen.LlamaScheduler.route,
            keywords = listOf("scheduled prompts", "scheduler", "schedule", "timer", "autonomous", "tasks"),
            keywordsEs = listOf("prompts programados", "programador", "programar", "tareas", "temporizador", "autonomo"),
            visibility = ToolVisibility.SEARCH),
        // Create
        tool("image_generation", ToolCategory.CREATE, Icons.Default.Image, R.string.ai_image_gen, R.string.ai_image_gen_desc, Screen.ImageGen.route,
            routePatterns = listOf(
                Screen.ImageGen.route,
                "${Screen.ImageGen.route}?startMode={startMode}",
                "${Screen.ImageGen.route}?startMode={startMode}&tab={tab}"
            ),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION),
            keywords = listOf("stable diffusion", "sd", "txt2img", "img2img", "image"),
            keywordsEs = listOf("imagen", "imagenes", "generar imagen", "dibujar", "texto a imagen", "difusion estable")),
        tool("video_generation", ToolCategory.CREATE, Icons.Default.VideoLibrary, R.string.ai_video_gen, R.string.ai_video_gen_desc, Screen.VideoGen.route,
            routePatterns = listOf(Screen.VideoGen.route, "${Screen.VideoGen.route}?tab={tab}"),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION),
            keywords = listOf("video", "stable diffusion", "txt2vid", "img2vid"),
            keywordsEs = listOf("video", "generar video", "texto a video", "imagen a video", "difusion estable")),
        tool("onnx_image_generation", ToolCategory.CREATE, Icons.Default.Brush, R.string.ai_onnx_image_gen, R.string.ai_onnx_image_gen_desc, Screen.OnnxImageGen.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx", "ort", "image", "offline"),
            keywordsEs = listOf("onnx", "imagen", "sin conexion", "generar imagen")),
        tool("onnx_background_removal", ToolCategory.CREATE, Icons.Default.AutoFixHigh, R.string.ai_onnx_bgr, R.string.ai_onnx_bgr_desc, Screen.OnnxBackgroundRemoval.route,
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx", "background", "cutout", "bgr"),
            keywordsEs = listOf("onnx", "fondo", "quitar fondo", "recorte", "borrar fondo")),
        tool("video_upscaler", ToolCategory.CREATE, Icons.Default.HighQuality, R.string.ai_video_upscaler, R.string.ai_video_upscaler_desc, Screen.VideoUpscaler.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER),
            keywords = listOf("upscale", "video", "realesrgan", "enlarge"),
            keywordsEs = listOf("ampliar", "escalar", "mejorar video", "agrandar", "video")),
        tool("video_interpolation", ToolCategory.CREATE, Icons.Default.SlowMotionVideo, R.string.ai_video_interpolation, R.string.ai_video_interpolation_desc, Screen.VideoInterpolation.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER),
            keywords = listOf("interpolate", "rife", "fps", "smooth", "video"),
            keywordsEs = listOf("interpolar", "fotogramas", "fluidez", "camara lenta", "video")),
        tool("subtitle_burn", ToolCategory.CREATE, Icons.Default.Subtitles, R.string.subtitle_burn_title, R.string.subtitle_burn_desc, Screen.SubtitleBurn.route,
            keywords = listOf("subtitle", "video", "burn"),
            keywordsEs = listOf("subtitulos", "video", "incrustar", "quemar")),
        // Voice
        tool("onnx_tts", ToolCategory.VOICE, Icons.Default.RecordVoiceOver, R.string.ai_onnx_tts, R.string.ai_onnx_tts_desc, Screen.OnnxTts.route,
            routePatterns = listOf(Screen.OnnxTts.route, Screen.OnnxTtsGallery.route),
            settingsAction = ToolSettingsAction.Navigate(Screen.OnnxModels.route),
            keywords = listOf("onnx speech", "onnx", "tts", "voice", "audio"),
            keywordsEs = listOf("voz onnx", "onnx", "texto a voz", "voz", "audio", "hablar")),
        tool("audio_workspace", ToolCategory.VOICE, Icons.Default.GraphicEq, R.string.ui_redesign_tools_audio_studio, R.string.audio_workspace_subtitle, Screen.AudioWorkspace.route,
            routePatterns = listOf(
                Screen.AudioWorkspace.route,
                "${Screen.AudioWorkspace.route}?section={section}"
            ),
            settingsAction = ToolSettingsAction.Navigate(Screen.AudioModels.route),
            keywords = listOf("audio", "tts", "speech", "voice", "clone", "qwen", "pocket", "supertonic", "music"),
            keywordsEs = listOf("audio", "estudio de audio", "voz", "habla", "clonar voz", "musica", "texto a voz")),
        tool("live_translator", ToolCategory.VOICE, Icons.Default.Translate, R.string.live_translator_title, R.string.live_translator_hub_desc, Screen.LiveTranslator.route,
            keywords = listOf("translate", "voice", "call", "bilingual"),
            keywordsEs = listOf("traducir", "traductor", "voz", "llamada", "bilingue", "en vivo")),
        tool("transcription", ToolCategory.VOICE, Icons.Default.Mic, R.string.ai_transcription, R.string.ai_transcription_desc, Screen.AudioTranscription.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.WHISPER),
            keywords = listOf("whisper", "transcribe", "audio", "voice"),
            keywordsEs = listOf("whisper", "transcribir", "transcripcion", "audio", "voz", "dictado")),
        // Documents
        tool("video_summary", ToolCategory.DOCUMENTS, Icons.Default.Summarize, R.string.ai_video_sumup, R.string.ai_video_sumup_desc, "video_sumup",
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("summary", "video", "transcript", "notes"),
            keywordsEs = listOf("resumen", "resumir", "video", "transcripcion", "notas")),
        tool("pdf_tools", ToolCategory.DOCUMENTS, Icons.Default.PictureAsPdf, R.string.ai_pdf_tools, R.string.ai_pdf_tools_desc, Screen.PDFToolbox.route,
            routePatterns = listOf(Screen.PDFToolbox.route, Screen.PDFSummary.route),
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("pdf", "ocr", "summary", "translation", "documents"),
            keywordsEs = listOf("pdf", "ocr", "resumen", "traduccion", "documentos")),
        tool("workflows", ToolCategory.DOCUMENTS, Icons.Default.AccountTree, R.string.hub_workflows, R.string.hub_workflows_desc, Screen.Workflows.route,
            settingsAction = ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            keywords = listOf("workflow", "batch", "summary", "translation", "recipes"),
            keywordsEs = listOf("flujo de trabajo", "flujos", "lote", "resumen", "traduccion", "recetas")),
        tool("dataset", ToolCategory.DOCUMENTS, Icons.Default.Folder, R.string.hub_dataset, R.string.hub_dataset_desc, Screen.Dataset.route,
            routePatterns = listOf(Screen.Dataset.route, Screen.DatasetProject.route),
            keywords = listOf("dataset", "training", "fine tuning", "documents"),
            keywordsEs = listOf("dataset", "conjunto de datos", "entrenamiento", "ajuste fino", "documentos")),
        // Share and network
        tool("offline_library", ToolCategory.SHARE_NETWORK, Icons.Default.Storage, R.string.ui_redesign_tools_offline_wiki, R.string.zim_browse_catalog, Screen.ZimManager.route,
            keywords = listOf("zim", "kiwix", "offline", "wikipedia", "library", "wiki"),
            keywordsEs = listOf("zim", "kiwix", "sin conexion", "wikipedia", "biblioteca", "wiki")),
        tool("servers_hub", ToolCategory.SHARE_NETWORK, Icons.Default.Dns, R.string.tool_catalog_servers_title, R.string.tool_catalog_servers_desc, SERVERS_HUB_ROUTE,
            keywords = listOf("servers", "server", "web", "lan", "ollama", "termux", "infrastructure"),
            keywordsEs = listOf("servidores", "servidor", "red local", "infraestructura", "compartir"),
            addedIn = VERSION_ROUND7),
        tool("distributed_llm", ToolCategory.SHARE_NETWORK, Icons.Default.Share, R.string.dashboard_distributed_title, R.string.dashboard_setup_distributed_desc, Screen.DistributedHub.route,
            keywords = listOf("distributed", "llama", "rpc", "network", "inference"),
            keywordsEs = listOf("distribuido", "llama", "rpc", "red", "inferencia")),
        tool("distributed_media", ToolCategory.SHARE_NETWORK, Icons.Default.Lan, R.string.dashboard_sd_distributed_title, R.string.dashboard_sd_distributed_desc, Screen.SdDistributedHub.route,
            keywords = listOf("distributed", "stable diffusion", "media", "rpc", "video"),
            keywordsEs = listOf("distribuido", "difusion estable", "multimedia", "rpc", "video")),
        tool("ollama", ToolCategory.SHARE_NETWORK, Icons.Default.Cloud, R.string.ollama_title, R.string.ollama_desc, Screen.OllamaManager.route,
            keywords = listOf("ollama", "server", "model", "infrastructure"),
            keywordsEs = listOf("ollama", "servidor", "modelo", "infraestructura")),
        tool("file_server", ToolCategory.SHARE_NETWORK, Icons.Default.Public, R.string.dashboard_file_server, R.string.soft_studio_home_file_server_desc, Screen.FileServer.route,
            keywords = listOf("file server", "file station", "share", "lan", "infrastructure", "files", "editor", "pdf", "calculator"),
            keywordsEs = listOf("servidor de archivos", "estacion de archivos", "compartir", "red local", "archivos", "editor", "pdf", "calculadora")),
        // Advanced
        tool("benchmark", ToolCategory.ADVANCED, Icons.Default.Speed, R.string.hub_benchmark, R.string.hub_benchmark_desc, Screen.Benchmark.route,
            routePatterns = listOf(Screen.Benchmark.route, Screen.BenchmarkHistory.route),
            keywords = listOf("benchmark", "threads", "performance"),
            keywordsEs = listOf("rendimiento", "hilos", "prueba de velocidad", "benchmark")),
        tool("quadtrix", ToolCategory.ADVANCED, Icons.Default.ModelTraining, R.string.quadtrix_title, R.string.quadtrix_hub_desc, Screen.QuadtrixTrainer.route,
            routePatterns = listOf(Screen.QuadtrixTrainer.route, Screen.QuadtrixWebUi.route),
            keywords = listOf("quadtrix", "training", "webui", "distributed"),
            keywordsEs = listOf("quadtrix", "entrenamiento", "entrenar", "distribuido")),
        tool("termux", ToolCategory.ADVANCED, Icons.Default.Terminal, R.string.hub_termux, R.string.hub_termux_desc, Screen.Termux.route,
            routePatterns = listOf(Screen.Termux.route, Screen.TermuxWebView.route, Screen.TermuxFileManager.route),
            keywords = listOf("termux", "ssh", "files", "infrastructure"),
            keywordsEs = listOf("termux", "ssh", "archivos", "terminal", "infraestructura")),
        // Search-only entries: tools that live on other tabs, shortcuts and settings pages
        tool("model_library", ToolCategory.ADVANCED, Icons.Default.Tune, R.string.models_hub, R.string.models_hub_subtitle, Screen.ModelHub.route,
            keywords = listOf("models", "download", "import", "engine", "gguf"),
            keywordsEs = listOf("modelos", "descargar", "importar", "motor", "gguf"),
            visibility = ToolVisibility.SEARCH),
        tool("all_media_gallery", ToolCategory.CREATE, Icons.Default.Collections, R.string.soft_studio_library_open_all_media, R.string.media_gallery_subtitle, Screen.AllMediaGallery.route,
            routePatterns = listOf(Screen.AllMediaGallery.route, "${Screen.AllMediaGallery.route}?type={type}&source={source}", Screen.FastsdGallery.route, Screen.SdDistributedGallery.route),
            keywords = listOf("gallery", "media", "images", "videos", "audio", "creations"),
            keywordsEs = listOf("galeria", "multimedia", "imagenes", "videos", "audio", "creaciones"),
            visibility = ToolVisibility.SEARCH, addedIn = VERSION_ROUND7),
        tool("generation_queue", ToolCategory.CREATE, Icons.Default.Queue, R.string.ui_sweepb_generation_queue, R.string.ui_sweepb_generation_queue_desc, Screen.GenerationQueue.route,
            routePatterns = listOf(Screen.GenerationQueue.route, Screen.GenerationQueueHistory.route),
            keywords = listOf("queue", "jobs", "generation", "pending", "history"),
            keywordsEs = listOf("cola", "trabajos", "generacion", "pendientes", "historial"),
            visibility = ToolVisibility.SEARCH),
        tool("organizer", ToolCategory.DOCUMENTS, Icons.Default.CalendarMonth, R.string.organizer_title, R.string.organizer_calendar_subtitle, Screen.NotesManager.route,
            keywords = listOf("organizer", "calendar", "alarms", "notes", "todo"),
            keywordsEs = listOf("organizador", "calendario", "alarmas", "notas", "tareas pendientes"),
            visibility = ToolVisibility.SEARCH),
        tool("web_servers", ToolCategory.SHARE_NETWORK, Icons.Default.Web, R.string.tool_catalog_web_servers_title, R.string.tool_catalog_web_servers_desc, Screen.AiServersHub.route,
            keywords = listOf("web ui", "browser", "server", "chat", "image", "workflow", "comfyui", "open webui"),
            keywordsEs = listOf("web ui", "navegador", "servidor", "chat", "imagen", "flujo", "comfyui", "open webui")),
        tool("global_search", ToolCategory.ADVANCED, Icons.Default.QueryStats, R.string.tool_catalog_global_search_title, R.string.tool_catalog_global_search_desc, "global_search",
            keywords = listOf("search", "find", "everything", "global"),
            keywordsEs = listOf("buscar", "encontrar", "todo", "busqueda global"),
            visibility = ToolVisibility.SEARCH, addedIn = VERSION_ROUND7),
        // The web-UI chat route; the Chats entry above is what people see.
        tool("chat", ToolCategory.ADVANCED, Icons.AutoMirrored.Filled.Chat, R.string.ai_chat, R.string.ai_chat_desc, Screen.Chat.route,
            routePatterns = listOf(Screen.Chat.route, "${Screen.Chat.route}?port={serverPort}"),
            settingsAction = ToolSettingsAction.Navigate("settings_llm"),
            keywords = listOf("chat", "conversation", "llm", "web ui"),
            keywordsEs = listOf("chat", "conversacion", "interfaz web"),
            visibility = ToolVisibility.HIDDEN),
        // Workflows recipes (shortcuts to the Workflows page)
        recipe("workflow_manga_translation", ToolCategory.DOCUMENTS, Icons.Default.Translate, R.string.tool_catalog_recipe_manga_title, R.string.tool_catalog_recipe_manga_desc,
            listOf("manga", "comic", "translate", "ocr"), listOf("manga", "comic", "traducir", "traduccion")),
        recipe("workflow_subtitle_translation", ToolCategory.DOCUMENTS, Icons.Default.Subtitles, R.string.tool_catalog_recipe_subtitles_title, R.string.tool_catalog_recipe_subtitles_desc,
            listOf("subtitle", "translate", "srt"), listOf("subtitulos", "traducir", "traduccion", "srt")),
        recipe("workflow_dubbing", ToolCategory.VOICE, Icons.Default.RecordVoiceOver, R.string.tool_catalog_recipe_dubbing_title, R.string.tool_catalog_recipe_dubbing_desc,
            listOf("dubbing", "dub", "voice", "translate", "video"), listOf("doblaje", "doblar", "voz", "traducir", "video")),
        recipe("workflow_transcribe_summary", ToolCategory.DOCUMENTS, Icons.Default.Description, R.string.tool_catalog_recipe_transcribe_title, R.string.tool_catalog_recipe_transcribe_desc,
            listOf("transcribe", "summary", "audio", "meeting"), listOf("transcribir", "resumen", "audio", "reunion")),
        recipe("workflow_txt2img_upscale", ToolCategory.CREATE, Icons.Default.AutoAwesome, R.string.tool_catalog_recipe_upscale_title, R.string.tool_catalog_recipe_upscale_desc,
            listOf("txt2img", "upscale", "image", "enlarge"), listOf("texto a imagen", "ampliar", "imagen", "agrandar")),
        // Settings pages
        settingsPage("settings_general", Icons.Default.Tune, R.string.settings_general, R.string.settings_general_desc, "settings_general",
            listOf("settings", "general", "theme", "output", "storage", "cache"), listOf("ajustes", "configuracion", "general", "tema", "salida", "almacenamiento")),
        settingsPage("settings_llm", Icons.Default.ChatBubbleOutline, R.string.settings_llm, R.string.settings_llm_desc, "settings_llm",
            listOf("settings", "llm", "chat", "models", "threads", "context"), listOf("ajustes", "configuracion", "llm", "chat", "modelos", "hilos", "contexto")),
        settingsPage("settings_imagegen", Icons.Default.Image, R.string.settings_hub_imagegen, R.string.settings_imagegen_desc, "settings_imagegen",
            listOf("settings", "image", "video", "stable diffusion", "generation"), listOf("ajustes", "configuracion", "imagen", "video", "generacion")),
        settingsPage("settings_whisper", Icons.Default.MicNone, R.string.settings_hub_whisper, R.string.settings_whisper_desc, "settings_whisper",
            listOf("settings", "whisper", "speech", "transcription"), listOf("ajustes", "configuracion", "whisper", "voz", "transcripcion")),
        settingsPage("settings_upscaler", Icons.Default.AutoAwesome, R.string.settings_upscaler, R.string.settings_upscaler_desc, "settings_upscaler",
            listOf("settings", "upscaler", "video", "threads"), listOf("ajustes", "configuracion", "ampliador", "video", "hilos")),
        settingsPage("settings_pdf", Icons.Default.PictureAsPdf, R.string.settings_hub_pdf, R.string.settings_pdf_desc, Screen.PDFSettings.route,
            listOf("settings", "pdf", "summary", "ocr"), listOf("ajustes", "configuracion", "pdf", "resumen", "ocr")),
        settingsPage("settings_agent", Icons.Default.Terminal, R.string.settings_hub_agent, R.string.settings_hub_agent_desc, "${Screen.Agent.route}?harnessTab=settings",
            listOf("settings", "agent", "harness", "projects"), listOf("ajustes", "configuracion", "agente", "proyectos")),
        settingsPage("settings_agent_runtimes", Icons.Default.Terminal, R.string.settings_hub_agent_runtimes, R.string.settings_hub_agent_runtimes_desc, "${Screen.Agent.route}?harnessTab=runtime",
            listOf("settings", "agent", "runtime", "environments", "debian", "proot"), listOf("ajustes", "agente", "entornos", "ejecucion", "debian", "proot")),
        settingsPage("settings_prompts", Icons.Default.EditNote, R.string.settings_prompts, R.string.settings_prompts_desc, "settings_prompts",
            listOf("settings", "prompts", "system prompt"), listOf("ajustes", "configuracion", "prompts", "instrucciones del sistema")),
        settingsPage("settings_stats", Icons.Default.QueryStats, R.string.settings_stats, R.string.settings_stats_desc, Screen.Stats.route,
            listOf("settings", "stats", "statistics", "memory", "charts", "device"), listOf("ajustes", "estadisticas", "memoria", "graficos", "dispositivo")),
        settingsPage("settings_logs", Icons.Default.BugReport, R.string.settings_hub_logs, R.string.settings_debug_desc, Screen.Logs.route,
            listOf("settings", "logs", "debug", "binaries", "diagnostics"), listOf("ajustes", "registros", "depuracion", "binarios", "diagnostico")),
        settingsPage("settings_about", Icons.Default.Info, R.string.settings_about, R.string.settings_about_desc, "about",
            listOf("settings", "about", "credits", "version", "donate"), listOf("ajustes", "acerca de", "creditos", "version", "donar"))
    ).map { it.copy(sprite = toolSprites[it.id]) }

    val aiRoutePatterns: Set<String> = tools
        .flatMap { it.routePatterns }
        .toSet()

    /** Tiles on the Tools tab, in catalog order. */
    val gridTools: List<AIToolDefinition> = tools.filter { it.visibility == ToolVisibility.GRID }

    /** Tools shown under "Tools" in global search (grid and search-only entries, no Settings pages). */
    val searchableTools: List<AIToolDefinition> = tools.filter {
        it.visibility != ToolVisibility.HIDDEN && !it.isSettingsPage
    }

    /** Settings pages shown under "Settings" in global search. */
    val settingsPages: List<AIToolDefinition> = tools.filter { it.isSettingsPage }

    /** Visible non-settings entries introduced in [versionName], used by Home and the "New" badge. */
    fun addedIn(versionName: String): List<AIToolDefinition> = tools.filter {
        it.addedIn == versionName && it.visibility != ToolVisibility.HIDDEN && !it.isSettingsPage
    }

    fun matchesRoute(route: String?): Boolean = toolForRoute(route) != null

    /**
     * Maps a route to its tool. [route] may be a destination pattern (`image_gen?startMode={startMode}`),
     * a concrete route with arguments (`llama_chat/5/3`, `agent?conversationId=7`) or a bare route.
     * The query string is ignored and `{arg}` path segments match any value. First catalog match wins.
     */
    fun toolForRoute(route: String?): AIToolDefinition? {
        if (route.isNullOrBlank()) return null
        val base = route.substringBefore('?')
        if (base.isBlank()) return null
        return tools.firstOrNull { tool ->
            tool.routePatterns.any { pattern -> routeBaseMatches(pattern.substringBefore('?'), base) }
        }
    }

    internal fun routeBaseMatches(patternBase: String, routeBase: String): Boolean {
        if (patternBase == routeBase) return true
        val patternParts = patternBase.split('/')
        val routeParts = routeBase.split('/')
        if (patternParts.size != routeParts.size) return false
        return patternParts.indices.all { i ->
            val p = patternParts[i]
            p == routeParts[i] || (p.startsWith("{") && p.endsWith("}"))
        }
    }
}
