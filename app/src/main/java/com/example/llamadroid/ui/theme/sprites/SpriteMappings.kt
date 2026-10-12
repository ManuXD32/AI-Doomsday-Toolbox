package com.example.llamadroid.ui.theme.sprites

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.llamadroid.service.AgentService.Companion.AgentRole

/** Central lookups from Material icons, agent roles and emoji to pixel sprites. */
object SpriteMappings {
    /**
     * Material vector base names (the part of `ImageVector.name` after the last dot, so
     * "Filled.Home" and "Rounded.Home" both resolve through "Home") to sprites.
     */
    val vectorSprites: Map<String, AppSpriteKey> = mapOf(
        "Home" to AppSpriteKey.HOME,
        "Chat" to AppSpriteKey.CHATS,
        "ChatBubble" to AppSpriteKey.CHATS,
        "Forum" to AppSpriteKey.CHATS,
        "GridView" to AppSpriteKey.TOOLS,
        "FolderOpen" to AppSpriteKey.LIBRARY,
        "Build" to AppSpriteKey.TOOLS,
        "Construction" to AppSpriteKey.TOOLS,
        "LocalLibrary" to AppSpriteKey.LIBRARY,
        "LibraryBooks" to AppSpriteKey.LIBRARY,
        "MenuBook" to AppSpriteKey.LIBRARY,
        "FavoriteBorder" to AppSpriteKey.TAMA,
        "Favorite" to AppSpriteKey.TAMA,
        "Pets" to AppSpriteKey.TAMA,
        "CalendarMonth" to AppSpriteKey.ORGANIZER,
        "Event" to AppSpriteKey.ORGANIZER,
        "Settings" to AppSpriteKey.SETTINGS,
        "ArrowBack" to AppSpriteKey.BACK,
        "Add" to AppSpriteKey.ADD,
        "Search" to AppSpriteKey.SEARCH,
        "MoreVert" to AppSpriteKey.MORE,
        "MoreHoriz" to AppSpriteKey.MORE,
        "SmartToy" to AppSpriteKey.AGENT_ROBOT,
        "Memory" to AppSpriteKey.MODEL_LITERT,
        "Image" to AppSpriteKey.TOOL_IMAGE,
        "VideoLibrary" to AppSpriteKey.TOOL_VIDEO,
        "Movie" to AppSpriteKey.TOOL_VIDEO,
        "PictureAsPdf" to AppSpriteKey.TOOL_PDF,
        "Translate" to AppSpriteKey.TOOL_TRANSLATE,
        "HighQuality" to AppSpriteKey.TOOL_UPSCALE,
        "Mic" to AppSpriteKey.MODEL_WHISPER,
        "GraphicEq" to AppSpriteKey.MODEL_AUDIO,
        "MusicNote" to AppSpriteKey.MODEL_AUDIO,
        "Storage" to AppSpriteKey.MODEL_SERVER,
        "Dns" to AppSpriteKey.MODEL_SERVER,
        "Terminal" to AppSpriteKey.TOOL_TERMINAL,
        "Lightbulb" to AppSpriteKey.TOOL_KNOWLEDGE,
        "Lan" to AppSpriteKey.TOOL_DISTRIBUTED,
        "StickyNote2" to AppSpriteKey.TOOL_NOTES,
        "EditNote" to AppSpriteKey.TOOL_NOTES,
        "CheckCircle" to AppSpriteKey.STATE_SUCCESS,
        "Error" to AppSpriteKey.STATE_ERROR,
        "Lock" to AppSpriteKey.STATE_BLOCKED,
        "HourglassEmpty" to AppSpriteKey.STATE_RUNNING
    )

    fun forVector(icon: ImageVector): AppSpriteKey? = forVectorName(icon.name)

    fun forVectorName(name: String): AppSpriteKey? = vectorSprites[name.substringAfterLast('.')]

    fun forAgentRole(role: AgentRole): AppSpriteKey = when (role) {
        AgentRole.ORCHESTRATOR -> AppSpriteKey.AGENT_ORCHESTRATOR
        AgentRole.CODEBASE_SCOUT -> AppSpriteKey.AGENT_SCOUT
        AgentRole.RESEARCHER -> AppSpriteKey.AGENT_RESEARCHER
        AgentRole.PLANNER -> AppSpriteKey.AGENT_PLANNER
        AgentRole.CODER -> AppSpriteKey.AGENT_CODER
        AgentRole.REVIEWER -> AppSpriteKey.AGENT_REVIEWER
        AgentRole.EXECUTOR -> AppSpriteKey.AGENT_EXECUTOR
        AgentRole.VISUAL_TESTER -> AppSpriteKey.AGENT_VISUAL_TESTER
        AgentRole.SUMMARIZER -> AppSpriteKey.AGENT_SUMMARIZER
    }

    /**
     * Sprite for the emoji shown on an agent row. Built-in role emoji map to their role
     * sprite; custom agents (any other emoji) use the base robot.
     */
    fun forAgentEmoji(emoji: String): AppSpriteKey {
        val wanted = emoji.replace("️", "")
        val role = AgentRole.entries.firstOrNull { it.emoji.replace("️", "") == wanted }
        return role?.let(::forAgentRole) ?: AppSpriteKey.AGENT_ROBOT
    }
}
