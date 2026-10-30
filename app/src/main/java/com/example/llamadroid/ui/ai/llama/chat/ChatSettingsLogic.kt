package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.ui.ai.llama.LlamaChatSamplerValidation
import com.example.llamadroid.ui.ai.llama.LlamaSamplerField

/** "N of M on" summary for the Tools section header. */
internal data class ChatToolsSummary(val on: Int, val total: Int) {
    companion object {
        fun of(vararg flags: Boolean): ChatToolsSummary = ChatToolsSummary(flags.count { it }, flags.size)
    }
}

/**
 * Effective server for a chat: its own binding wins, then the route argument (when a real row id),
 * otherwise null which means "fall back to the last-used server".
 */
internal fun resolveEffectiveServerId(chatServerId: Long?, routeServerId: Long): Long? =
    chatServerId?.takeIf { it > 0L } ?: routeServerId.takeIf { it > 0L }

/** What the banner above the composer should show, derived from server and generation state. */
internal enum class ChatServerBannerKind { NONE, NO_SERVER, SERVER_REMOVED, STOPPED, STARTING, ERROR }

/**
 * Pure banner decision. Remote servers never show a start/stop banner. [generationStatusText] is
 * the live status of an in-flight send (e.g. "Starting local server…"); it only counts while the
 * server is not yet running.
 */
internal fun resolveChatServerBanner(
    boundServerMissing: Boolean,
    hasActiveServer: Boolean,
    isLocal: Boolean,
    status: UnifiedServerStatus?,
    actionBusy: Boolean,
    actionHasError: Boolean,
    generationStatusText: String?
): ChatServerBannerKind {
    if (boundServerMissing) return ChatServerBannerKind.SERVER_REMOVED
    if (!hasActiveServer) return ChatServerBannerKind.NO_SERVER
    if (!isLocal || status == null) return ChatServerBannerKind.NONE
    if (actionBusy || status == UnifiedServerStatus.STARTING) return ChatServerBannerKind.STARTING
    if (actionHasError || status == UnifiedServerStatus.ERROR) return ChatServerBannerKind.ERROR
    if (status != UnifiedServerStatus.RUNNING && !generationStatusText.isNullOrBlank()) {
        return ChatServerBannerKind.STARTING
    }
    if (status == UnifiedServerStatus.STOPPED) return ChatServerBannerKind.STOPPED
    return ChatServerBannerKind.NONE
}

/**
 * First sampler text field that does not validate, or null when every field may be saved.
 * [texts] maps each field to its text, or null when the field is Auto (nothing to validate).
 * Fields LiteRT does not support are skipped.
 */
internal fun firstInvalidSamplerField(
    liteRt: Boolean,
    texts: Map<LlamaSamplerField, String?>
): LlamaSamplerField? = LlamaSamplerField.visible(liteRt).firstOrNull { field ->
    val text = texts[field] ?: return@firstOrNull false
    LlamaChatSamplerValidation.parse(text, field, liteRt) == null
}

/**
 * Request entries for the committed sampler [values] (missing = Auto, left out). LiteRT only takes
 * temperature, top_p and top_k, coerced into its ranges; llama.cpp gets every non-Auto key typed
 * Int or Float per [LlamaSamplerField.isIntegerKey].
 */
internal fun samplerParamEntries(
    liteRt: Boolean,
    values: Map<LlamaSamplerField, Float>
): LinkedHashMap<String, Any> {
    val out = linkedMapOf<String, Any>()
    LlamaSamplerField.visible(liteRt).forEach { field ->
        val raw = values[field] ?: return@forEach
        val value = LlamaChatSamplerValidation.range(field, liteRt)
            ?.let { raw.coerceIn(it.minimum, it.maximum) } ?: raw
        out[field.key] = if (field.isIntegerKey) value.toInt() else value
    }
    return out
}

/**
 * Auto-save gate: only after the first load, and only when the payload differs from what was last
 * persisted (or loaded). [lastSaved] null means "not baselined yet".
 */
internal fun shouldAutoSave(loaded: Boolean, lastSaved: String?, current: String): Boolean =
    loaded && lastSaved != null && lastSaved != current
