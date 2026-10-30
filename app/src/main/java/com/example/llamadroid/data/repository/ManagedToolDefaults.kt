package com.example.llamadroid.data.repository

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.NativeChatToolConfig
import org.json.JSONObject

/**
 * Marks tool defaults written by the server editors. Managed rows created before tool defaults
 * existed stored the old all-true tool ceiling without it, so an unmarked all-true value is legacy.
 */
internal const val TOOL_DEFAULTS_MARKER_KEY = "adt_tool_defaults_v2"

/** Tool defaults JSON as stored on a server row (explicit user choice, never read as the legacy ceiling). */
internal fun markedToolDefaultsJson(config: NativeChatToolConfig): String =
    JSONObject(config.toParamMap()).put(TOOL_DEFAULTS_MARKER_KEY, true).toString()

/** Marks chat-shaped defaults JSON (the keys a chat stores) as an explicit choice of the server editor. */
internal fun markedDefaultsJson(paramsJson: String): String =
    JSONObject(paramsJson).put(TOOL_DEFAULTS_MARKER_KEY, true).toString()

private fun plainDisabledToolsJson(): String = JSONObject(easyChatDisabledTools().toParamMap()).toString()

/** Default for new managed servers: every tool off, matching how managed chats have always started. */
internal fun managedDefaultToolsJson(): String = markedToolDefaultsJson(easyChatDisabledTools())

/**
 * Tool defaults a managed row contributes to new chats. Blank values and the legacy all-true ceiling
 * (the old behaviour: chats started with every tool off) read as [easyChatDisabledTools]; the stored
 * value is replaced the next time the server is saved. The editor marker never reaches chat settings.
 */
internal fun normalizedManagedToolDefaults(raw: String?): String {
    val json = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: return plainDisabledToolsJson()
    if (json.has(TOOL_DEFAULTS_MARKER_KEY)) {
        json.remove(TOOL_DEFAULTS_MARKER_KEY)
        return json.toString()
    }
    val isLegacyCeiling = NativeChatToolConfig.fromApiParams(raw).toParamMap() == easyChatAllowedTools().toParamMap()
    return if (isLegacyCeiling) plainDisabledToolsJson() else json.toString()
}

/** Value to write back on save: the normalized defaults, marked so an all-true choice is never re-read as legacy. */
internal fun persistedManagedToolDefaults(raw: String?): String {
    val alreadyMarked = raw?.let { runCatching { JSONObject(it).has(TOOL_DEFAULTS_MARKER_KEY) }.getOrNull() } == true
    return if (alreadyMarked) raw.orEmpty()
    else JSONObject(normalizedManagedToolDefaults(raw)).put(TOOL_DEFAULTS_MARKER_KEY, true).toString()
}

/** The server's own tool defaults for new chats; managed rows additionally normalize legacy values. */
internal fun effectiveToolDefaultsParams(server: LlamaServerEntity?): String? =
    if (server?.managedServerCardId != null) normalizedManagedToolDefaults(server.defaultApiParams)
    else server?.defaultApiParams?.takeIf { it.isNotBlank() }
