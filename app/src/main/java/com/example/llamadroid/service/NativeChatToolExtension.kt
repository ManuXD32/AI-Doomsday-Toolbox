package com.example.llamadroid.service

/**
 * A tool family plugged into [NativeChatToolRuntime] from outside `NativeChatTools.kt`, so new
 * families do not grow that file. Extensions are consulted after the built-in tools.
 *
 * The tool list must be a pure function of [NativeChatToolConfig]: the same config always yields
 * the same definitions in the same order, so cached llama-server prompt prefixes stay valid
 * between turns. Per-chat runtime state belongs in tool results, never in the definitions.
 */
interface NativeChatToolExtension {
    /** Stable identifier used in diagnostics only. */
    val id: String

    /** Whether this family contributes tools for [config]. */
    fun isEnabled(config: NativeChatToolConfig): Boolean

    /** Tool definitions; only called when [isEnabled] is true. */
    fun tools(config: NativeChatToolConfig): List<AgentTool>

    fun handles(toolName: String): Boolean

    suspend fun execute(
        toolCall: OllamaService.ToolCall,
        config: NativeChatToolConfig,
        chatId: Long?,
        onProgress: (NativeChatToolProgress) -> Unit
    ): Result<NativeChatToolResult>

    /** Guidance appended to the transient tool-awareness system text, or null for none. */
    fun awareness(config: NativeChatToolConfig): String? = null

    /**
     * One-turn reminder based on the user's latest message, or null for none. It is sent after
     * the conversation (never in the stable system prefix), so prompt caches stay valid.
     */
    fun turnHint(config: NativeChatToolConfig, latestUserText: String): String? = null

    /** Localized status line shown while [toolName] runs, or null for the generic text. */
    fun statusText(toolName: String): String? = null

    /** True when [toolName] changes state and counts against the per-turn mutating limit. */
    fun isMutating(toolName: String): Boolean = false
}
