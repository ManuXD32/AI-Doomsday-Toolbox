package com.example.llamadroid.service.compaction

/**
 * Per-chat compaction settings stored in `LlamaChatEntity.apiParams`. Auto-compaction is on
 * unless the user turned it off; `ChatSettingsState` must carry the field (its auto-save
 * rebuilds the whole JSON map).
 */
object NativeChatCompactionParams {
    const val KEY_AUTO_COMPACT = "auto_compact_enabled"

    fun autoCompactEnabled(params: Map<String, Any?>): Boolean = when (val value = params[KEY_AUTO_COMPACT]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: true
        else -> true
    }
}
