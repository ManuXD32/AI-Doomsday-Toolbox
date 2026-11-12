package com.example.llamadroid.service

/**
 * llama.cpp reasoning controls shared by the server launch settings (`--reasoning`,
 * `--reasoning-effort`) and the native chat's per-request overrides (`chat_template_kwargs.enable_thinking`,
 * `reasoning_effort`). "auto" / "default" always mean "send nothing".
 */
object LlamaReasoning {
    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"
    val SERVER_MODES = listOf(MODE_AUTO, MODE_ON, MODE_OFF)

    const val EFFORT_DEFAULT = "default"
    val EFFORTS = listOf("default", "minimal", "low", "medium", "high", "xhigh", "max")

    /** Chat-level thinking choice: omit the kwarg, or send the bool. */
    const val THINKING_DEFAULT = "default"
    const val THINKING_ON = "on"
    const val THINKING_OFF = "off"
    val CHAT_THINKING_MODES = listOf(THINKING_DEFAULT, THINKING_ON, THINKING_OFF)

    const val PARAM_THINKING_MODE = "thinking_mode"
    const val PARAM_REASONING_EFFORT = "reasoning_effort"
    private const val PARAM_ENABLE_THINKING = "enable_thinking"

    fun normalizeMode(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in SERVER_MODES } ?: MODE_AUTO

    fun normalizeEffort(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in EFFORTS } ?: EFFORT_DEFAULT

    /** Value for `--reasoning`, or null when no flag should be emitted. */
    fun serverModeFlag(mode: String?): String? =
        normalizeMode(mode).takeIf { it != MODE_AUTO }

    /** Value for `--reasoning-effort`, or null when no flag should be emitted. */
    fun serverEffortFlag(effort: String?): String? =
        normalizeEffort(effort).takeIf { it != EFFORT_DEFAULT }

    /**
     * Per-chat thinking choice. Chats saved before the tri-state existed only carry the
     * `enable_thinking` bool, which maps to On/Off (missing means On, the old default).
     */
    fun chatThinkingMode(params: Map<String, Any?>): String {
        val explicit = (params[PARAM_THINKING_MODE] as? String)?.trim()?.lowercase()
        if (explicit != null && explicit in CHAT_THINKING_MODES) return explicit
        return if ((params[PARAM_ENABLE_THINKING] as? Boolean) == false) THINKING_OFF else THINKING_ON
    }

    /** Per-chat `reasoning_effort` to send, or null for Default. */
    fun chatEffort(params: Map<String, Any?>): String? =
        normalizeEffort(params[PARAM_REASONING_EFFORT] as? String).takeIf { it != EFFORT_DEFAULT }

    /** `reasoning_effort` wire value; llama.cpp spells the top level "xhigh" like the CLI. */
    fun requestEffort(effort: String?): String? = effort?.let { normalizeEffort(it) }?.takeIf { it != EFFORT_DEFAULT }
}
