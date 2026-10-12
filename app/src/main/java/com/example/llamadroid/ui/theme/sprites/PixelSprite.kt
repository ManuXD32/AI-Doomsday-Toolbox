package com.example.llamadroid.ui.theme.sprites

/** Every sprite the pixel theme can draw. Each key has exactly one [PixelSprite] (see SpritesTest). */
enum class AppSpriteKey {
    // Shell / navigation
    HOME, CHATS, TOOLS, LIBRARY, TAMA, ORGANIZER, SETTINGS, BACK, ADD, SEARCH, MORE,

    // Agents
    AGENT_ROBOT, AGENT_ORCHESTRATOR, AGENT_SCOUT, AGENT_RESEARCHER, AGENT_PLANNER,
    AGENT_CODER, AGENT_REVIEWER, AGENT_EXECUTOR, AGENT_VISUAL_TESTER, AGENT_SUMMARIZER,

    // Models
    MODEL_LLM, MODEL_LITERT, MODEL_IMAGE, MODEL_ONNX, MODEL_WHISPER, MODEL_AUDIO,
    MODEL_BUNDLE, MODEL_LORA, MODEL_SERVER,

    // Tools
    TOOL_IMAGE, TOOL_VIDEO, TOOL_PDF, TOOL_TRANSLATE, TOOL_UPSCALE, TOOL_KIWIX,
    TOOL_NOTES, TOOL_DATASET, TOOL_DISTRIBUTED, TOOL_KNOWLEDGE, TOOL_TERMINAL,

    // States
    STATE_EMPTY, STATE_BLOCKED, STATE_RUNNING, STATE_ERROR, STATE_SUCCESS
}

/**
 * A 16x16 palette-indexed sprite. Each row is 16 characters:
 * `.` transparent, `o` outline, `b` base, `s` shade, `h` highlight, `a` accent,
 * `f` face (eyes and mouth), `w` white sparkle.
 */
data class PixelSprite(val key: AppSpriteKey, val rows: List<String>) {
    companion object {
        const val SIZE = 16
        const val PALETTE_CHARS = ".obshafw"
    }
}

/** Registry of every sprite, grouped across the Sprites*.kt data files. */
object PixelSprites {
    val all: List<PixelSprite> by lazy {
        SHELL_SPRITES + AGENT_SPRITES + MODEL_SPRITES + TOOL_SPRITES + STATE_SPRITES
    }
    private val byKey: Map<AppSpriteKey, PixelSprite> by lazy { all.associateBy { it.key } }

    fun get(key: AppSpriteKey): PixelSprite = byKey.getValue(key)
}
