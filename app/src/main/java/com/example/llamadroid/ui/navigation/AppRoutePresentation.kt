package com.example.llamadroid.ui.navigation

/** Presentation ownership is independent of the stable public destination strings. */
enum class AppRootDestination(val route: String) {
    Home("dashboard"), Chats("chats"), Tools("ai_hub"), Tama("tama"), Organizer("notes_manager")
}

enum class AppNavigationLayout { Bar, Rail, Drawer }

fun appNavigationLayout(widthDp: Int, heightDp: Int, fontScale: Float): AppNavigationLayout = when {
    widthDp < 360 || heightDp < 480 || !(fontScale < 1.3f) -> AppNavigationLayout.Drawer
    widthDp >= 600 -> AppNavigationLayout.Rail
    else -> AppNavigationLayout.Bar
}

data class AppRoutePresentation(val parent: AppRootDestination?, val isRoot: Boolean)

object AppRoutePresentations {
    private val chatRoutes = setOf(
        "chat", "llama_chat_list", "llama_chat", "llama_scheduler",
        "server_profile_editor", "native_chat_delegation",
        "agent", "agent_workspace", "agent_proot_terminal", "agent_invocation"
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
        // Library is a tab inside Tools: the legacy route shares the Tools root chrome.
        if (base == "library") return AppRoutePresentation(AppRootDestination.Tools, true)
        val parent = when {
            base == "walkthrough" -> AppRootDestination.Home
            base in tamaRoutes -> AppRootDestination.Tama
            base in chatRoutes || base.startsWith("agent") -> AppRootDestination.Chats
            base == "settings" || base.startsWith("settings_") || base in setOf("about", "logs") -> null
            else -> AppRootDestination.Tools // library, models, galleries, queue, servers and every tool
        }
        return AppRoutePresentation(parent, false)
    }
}
