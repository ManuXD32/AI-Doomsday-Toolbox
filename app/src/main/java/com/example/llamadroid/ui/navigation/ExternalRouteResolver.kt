package com.example.llamadroid.ui.navigation

import com.example.llamadroid.tama.adventure.DungeonType

sealed interface ExternalRouteResolution {
    data object NoRoute : ExternalRouteResolution
    data class Navigate(val route: String) : ExternalRouteResolution
    data object Rejected : ExternalRouteResolution
}

/**
 * Validates routes received from widgets, notifications and other external intents.
 *
 * Routes created inside the app still use [androidx.navigation.NavController] directly. This
 * resolver is deliberately limited to the boundary where untrusted/stale strings enter the
 * navigation graph, so an old shortcut can never terminate the UI process with an
 * IllegalArgumentException.
 */
object ExternalRouteResolver {
    private const val IMAGE_GEN_UPSCALE_LEGACY_ROUTE = "image_gen_upscale"
    private const val LEGACY_MODELS_ROUTE = "models"
    private const val LEGACY_STATS_ROUTE = "stats"
    private const val LEGACY_KIWIX_HUB_ROUTE = "kiwix_hub"

    private val staticRoutes = setOf(
        Screen.Dashboard.route,
        Screen.Library.route,
        Screen.Settings.route,
        Screen.Stats.route,
        Screen.Logs.route,
        Screen.AIHub.route,
        Screen.AiServersHub.route,
        Screen.FileServer.route,
        Screen.LlamaServers.route,
        Screen.ImageGen.route,
        Screen.ImageGenUpscale.route,
        Screen.OnnxImageGen.route,
        Screen.OnnxBackgroundRemoval.route,
        Screen.OnnxTts.route,
        Screen.OnnxTtsGallery.route,
        Screen.LiveTranslator.route,
        Screen.VideoGen.route,
        Screen.AudioTranscription.route,
        Screen.VideoUpscaler.route,
        Screen.VideoInterpolation.route,
        Screen.SubtitleBurn.route,
        Screen.NotesManager.route,
        Screen.KnowledgeBase.route,
        Screen.Workflows.route,
        Screen.ModelHub.route,
        Screen.LLMModels.route,
        Screen.SDModels.route,
        Screen.OnnxModels.route,
        Screen.WhisperModels.route,
        Screen.LiteRtModels.route,
        Screen.PDFToolbox.route,
        Screen.PDFSummary.route,
        Screen.PDFSettings.route,
        Screen.ZimManager.route,
        Screen.KiwixViewer.route,
        Screen.DistributedHub.route,
        Screen.WorkerMode.route,
        Screen.MasterMode.route,
        Screen.NetworkVisualization.route,
        Screen.SdDistributedHub.route,
        Screen.SdDistributedWorker.route,
        Screen.SdDistributedMaster.route,
        Screen.SdDistributedNetwork.route,
        Screen.SdDistributedRunConfig.route,
        Screen.SdDistributedGallery.route,
        Screen.Benchmark.route,
        Screen.BenchmarkHistory.route,
        Screen.Dataset.route,
        Screen.QuadtrixTrainer.route,
        Screen.Termux.route,
        Screen.TermuxFileManager.route,
        Screen.FastsdGallery.route,
        Screen.Agent.route,
        Screen.AgentWorkspace.route,
        Screen.Tama.route,
        Screen.TamaChat.route,
        Screen.TamaGallery.route,
        Screen.Arcade.route,
        Screen.Farm.route,
        Screen.Barn.route,
        Screen.Coop.route,
        Screen.Store.route,
        Screen.Dungeon.route,
        Screen.AdventureGate.route,
        Screen.NightArena.route,
        Screen.OllamaManager.route,
        Screen.LlamaServerList.route,
        Screen.LlamaChatList.route,
        Screen.LlamaScheduler.route,
        "model_share",
        "settings_general",
        "settings_llm",
        "settings_imagegen",
        "settings_whisper",
        "settings_upscaler",
        "settings_prompts",
        "settings_logs",
        "video_sumup",
        "about"
    )

    private val chatWithPortPattern = Regex("^${Regex.escape(Screen.Chat.route)}\\?port=[1-9][0-9]{0,4}$")
    private val imageGenWithModePattern = Regex(
        "^${Regex.escape(Screen.ImageGen.route)}\\?startMode=[0-4](?:&tab=(?:create|gallery))?$"
    )
    private val adventureRoutes = DungeonType.entries
        .map { Screen.Adventure.createRoute(it.name) }
        .toSet()

    /** Classifies an external value without ever passing unregistered text to Navigation. */
    fun resolve(rawRoute: String?): ExternalRouteResolution {
        if (rawRoute == null) return ExternalRouteResolution.NoRoute
        val trimmed = rawRoute.trim()
        if (trimmed.isEmpty()) return ExternalRouteResolution.Rejected
        val canonical = when (trimmed) {
            LEGACY_MODELS_ROUTE -> Screen.ModelHub.route
            LEGACY_STATS_ROUTE -> Screen.Stats.route
            // KiwixHub was renamed to the existing ZIM manager destination.
            LEGACY_KIWIX_HUB_ROUTE -> Screen.ZimManager.route
            IMAGE_GEN_UPSCALE_LEGACY_ROUTE -> Screen.ImageGen.createRoute(startMode = 2)
            else -> trimmed
        }

        val safeRoute = when {
            canonical in staticRoutes -> canonical
            canonical == Screen.Chat.route ||
                canonical == Screen.ImageGen.route ||
                canonical == Screen.KiwixViewer.route -> canonical
            chatWithPortPattern.matches(canonical) &&
                canonical.substringAfter("port=").toIntOrNull() in 1..65535 -> canonical
            imageGenWithModePattern.matches(canonical) -> canonical
            canonical.startsWith("agent?conversationId=") &&
                canonical.removePrefix("agent?conversationId=").toLongOrNull()?.let { it > 0L } == true -> canonical
            canonical in setOf("image_gen?tab=gallery", "image_gen?tab=create",
                "video_gen?tab=gallery", "video_gen?tab=create") -> canonical
            hasPositiveLongSuffix(canonical, "dataset_project/") -> canonical
            hasPositiveLongSuffix(canonical, "llama_chat_list/folder/") -> canonical
            isValidLlamaChatRoute(canonical) -> canonical
            canonical in adventureRoutes -> canonical
            else -> null
        }
        return safeRoute?.let(ExternalRouteResolution::Navigate)
            ?: ExternalRouteResolution.Rejected
    }

    /** Nullable compatibility helper for non-UI tests and internal validation. */
    fun resolveRoute(rawRoute: String?): String? =
        (resolve(rawRoute) as? ExternalRouteResolution.Navigate)?.route

    private fun hasPositiveLongSuffix(route: String, prefix: String): Boolean {
        if (!route.startsWith(prefix)) return false
        val rawId = route.removePrefix(prefix)
        if (rawId.isEmpty() || '/' in rawId) return false
        return rawId.toLongOrNull()?.let { it > 0L } == true
    }

    private fun isValidLlamaChatRoute(route: String): Boolean {
        val segments = route.split('/')
        if (segments.size != 3 || segments[0] != "llama_chat") return false
        val chatId = segments[1].toLongOrNull() ?: return false
        val serverId = segments[2].toLongOrNull() ?: return false
        return chatId > 0L && (serverId == -1L || serverId > 0L)
    }
}
