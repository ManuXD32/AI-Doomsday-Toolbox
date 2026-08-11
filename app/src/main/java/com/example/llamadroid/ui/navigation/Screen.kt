package com.example.llamadroid.ui.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object ModelManager : Screen("models")       // Now goes to Model Hub
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object Stats : Screen("settings_stats")
    object Logs : Screen("logs")
    // AI screens
    object AIHub : Screen("ai_hub")              // Landing page for AI features
    object ImageGen : Screen("image_gen")        // Stable Diffusion image generation
    object ImageGenUpscale : Screen("image_gen_upscale") // Compatibility route into the unified Enlarge task
    object OnnxImageGen : Screen("onnx_image_gen") // ONNX Runtime image generation
    object OnnxBackgroundRemoval : Screen("onnx_background_removal") // ONNX Runtime background removal
    object OnnxTts : Screen("onnx_tts")       // ONNX Runtime text-to-speech
    object OnnxTtsGallery : Screen("onnx_tts_gallery") // ONNX TTS generated audio gallery
    object LiveTranslator : Screen("live_translator") // Turn-based bilingual voice translator
    object VideoGen : Screen("video_gen")        // Stable Diffusion video generation
    object AudioTranscription : Screen("audio_transcription") // WhisperCPP
    object VideoUpscaler : Screen("video_upscaler")           // Real-ESRGAN video upscaling
    object VideoInterpolation : Screen("video_interpolation") // RIFE video frame interpolation
    object NotesManager : Screen("notes_manager")             // Unified notes manager
    object KnowledgeBase : Screen("knowledge_base")           // User-managed retrieval knowledge bases
    object KnowledgeChunkReader : Screen("knowledge_chunk/{chunkId}") {
        private const val URI_PREFIX = "kb://chunk/"
        fun createRoute(chunkId: Long): String = "knowledge_chunk/$chunkId"
        fun chunkIdFromUri(uri: String): Long? {
            val trimmed = uri.trim()
            if (!trimmed.startsWith(URI_PREFIX, ignoreCase = true)) return null
            return trimmed.substring(URI_PREFIX.length).toLongOrNull()
        }
    }
    object Workflows : Screen("workflows")                     // AI Workflows (Sequential operations)
    object AiServersHub : Screen("ai_servers_hub")             // Local web servers for AI tools
    // Model screens
    object ModelHub : Screen("model_hub")        // Landing page for model management
    object LLMModels : Screen("llm_models")      // LlamaCpp model management
    object SDModels : Screen("sd_models")        // SD model management
    object OnnxModels : Screen("onnx_models")    // ONNX model management
    object WhisperModels : Screen("whisper_models") // Whisper model management
    object LiteRtModels : Screen("litert_models") // LiteRT model management
    // Kiwix screens
    object KiwixHub : Screen("kiwix_hub")        // Landing page for Kiwix
    object ZimManager : Screen("zim_manager")    // ZIM file management
    object KiwixViewer : Screen("kiwix_viewer")  // WebView for Kiwix content
    // Distributed inference screens
    object DistributedHub : Screen("distributed")        // Role selection (master/worker)
    object WorkerMode : Screen("distributed_worker")     // Worker configuration
    object MasterMode : Screen("distributed_master")     // Master with worker list
    object NetworkVisualization : Screen("distributed_network") // Network visualization
    object SdDistributedHub : Screen("sd_distributed")        // Stable Diffusion media distributed hub
    object SdDistributedWorker : Screen("sd_distributed_worker") // Stable Diffusion RPC worker
    object SdDistributedMaster : Screen("sd_distributed_master") // Stable Diffusion RPC master
    object SdDistributedNetwork : Screen("sd_distributed_network") // Stable Diffusion topology
    object SdDistributedRunConfig : Screen("sd_distributed_run_config") // Stable Diffusion distributed runtime config
    object SdDistributedGallery : Screen("sd_distributed_gallery") // Stable Diffusion generated media gallery
    // Settings screens
    object Benchmark : Screen("benchmark")               // Thread benchmark tool
    object BenchmarkHistory : Screen("benchmark_history") // Benchmark run history and model comparison
    object Dataset : Screen("dataset")                   // Dataset creation tool (project list)
    object QuadtrixTrainer : Screen("quadtrix_trainer")  // Native Quadtrix LLM training
    object QuadtrixWebUi : Screen("quadtrix_webui/{url}") {
        fun createRoute(url: String): String {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            return "quadtrix_webui/$encodedUrl"
        }
    }
    object DatasetProject : Screen("dataset_project/{projectId}") {
        fun createRoute(projectId: Long): String = "dataset_project/$projectId"
    }
    object Termux : Screen("termux")                     // Termux SSH integration
    object TermuxWebView : Screen("termux_webview/{url}/{title}/{toolId}") {
        fun createRoute(url: String, title: String, toolId: String = "none"): String {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            return "termux_webview/$encodedUrl/$encodedTitle/$toolId"
        }
    }
    object TermuxFileManager : Screen("termux_file_manager")  // File manager for Termux tools
    object FastsdGallery : Screen("fastsd_gallery")           // FastSD CPU generated images gallery
    // AI Agent screens
    object Agent : Screen("agent")                             // AI coding agent chat
    object AgentWorkspace : Screen("agent_workspace")          // Agent workspace file manager
    object AgentInvocation : Screen("agent_invocation/{invocationId}") {
        fun createRoute(invocationId: String): String = "agent_invocation/$invocationId"
    }
    // Tama virtual pet
    object Tama : Screen("tama")                               // Virtual pet companion
    object TamaChat : Screen("tama_chat")                     // AI Chat with pet
    object TamaGallery : Screen("tama_gallery")               // Tama art gallery
    object Arcade : Screen("arcade")                          // Tama arcade hub and minigames
    object Farm : Screen("farm")                               // Farming grid
    object Barn : Screen("farm_barn")                          // Barn sub-area
    object Coop : Screen("farm_coop")                          // Chicken coop sub-area
    object Store : Screen("store")                             // Farm supply store
    object SubtitleBurn : Screen("subtitle_burn")              // Subtitle burning tool
    object Dungeon : Screen("dungeon")                         // Dungeon selection
    object Adventure : Screen("adventure/{dungeonType}") {     // Text adventure
        fun createRoute(dungeonType: String): String = "adventure/$dungeonType"
    }
    object AdventureGate : Screen("adventure_gate")             // Tama battle RPG
    object NightArena : Screen("night_arena")                   // Tama sleep battle arena
    object OllamaManager : Screen("ollama_manager")            // Ollama server/model manager
    
    // Native Llama Client
    object LlamaServerList : Screen("llama_server_list")       // Server management (entry point)
    object LlamaChatList : Screen("llama_chat_list") {         // Chat history
        const val folderRoute = "llama_chat_list/folder/{folderId}"
        fun createFolderRoute(folderId: Long): String = "llama_chat_list/folder/$folderId"
    }
    object LlamaScheduler : Screen("llama_scheduler")           // Scheduled native Llama tasks
    object LlamaChat : Screen("llama_chat/{chatId}/{serverId}") { // Chat interface
        fun createRoute(chatId: Long, serverId: Long): String = "llama_chat/$chatId/$serverId"
    }
}
