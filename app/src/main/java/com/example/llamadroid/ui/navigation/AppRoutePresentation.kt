package com.example.llamadroid.ui.navigation

/** Presentation ownership is independent of the stable public destination strings. */
enum class AppRootDestination(val route: String) {
    Home("dashboard"), Tools("ai_hub"), Library("library"), Tama("tama")
}

enum class AppNavigationLayout { Bar, Rail, Drawer }

fun appNavigationLayout(widthDp: Int, heightDp: Int, fontScale: Float): AppNavigationLayout = when {
    widthDp < 360 || heightDp < 480 || !(fontScale < 1.3f) -> AppNavigationLayout.Drawer
    widthDp >= 600 -> AppNavigationLayout.Rail
    else -> AppNavigationLayout.Bar
}

data class AppRoutePresentation(val parent: AppRootDestination?, val isRoot: Boolean)

object AppRoutePresentations {
    private val libraryRoutes = setOf(
        "models", "model_hub", "model_sources", "llm_models", "sd_models", "onnx_models", "whisper_models",
        "litert_models", "model_share", "knowledge_base", "knowledge_chunk", "kiwix_hub",
        "zim_manager", "kiwix_viewer", "onnx_tts_gallery", "fastsd_gallery", "sd_distributed_gallery"
    )
    private val tamaRoutes = setOf(
        "tama_chat", "tama_gallery", "arcade", "farm", "farm_barn", "farm_coop", "store",
        "dungeon", "adventure", "adventure_gate", "night_arena"
    )

    fun forRoute(route: String?): AppRoutePresentation {
        if (route == null) return AppRoutePresentation(null, false)
        val base = route.substringBefore('?').substringBefore('/')
        val root = AppRootDestination.entries.firstOrNull { it.route == base }
        if (root != null) return AppRoutePresentation(root, true)
        val parent = when {
            base == "walkthrough" -> AppRootDestination.Home
            base in tamaRoutes -> AppRootDestination.Tama
            base in libraryRoutes -> AppRootDestination.Library
            base == "settings" || base.startsWith("settings_") || base in setOf("about", "logs") -> null
            else -> AppRootDestination.Tools
        }
        return AppRoutePresentation(parent, false)
    }
}
