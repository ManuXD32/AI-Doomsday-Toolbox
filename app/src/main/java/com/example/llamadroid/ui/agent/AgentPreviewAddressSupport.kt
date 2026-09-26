package com.example.llamadroid.ui.agent

import java.net.URI
import java.util.Locale

internal const val AGENT_PREVIEW_MAX_URL_CHARS = 2_048

/** Returns a canonical safe HTTP(S) address, or null when the user value is invalid. */
internal fun normalizeAgentPreviewAddress(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > AGENT_PREVIEW_MAX_URL_CHARS || trimmed.any { it.isISOControl() }) return null
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase(Locale.US)
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
    if (parsed.rawUserInfo != null || parsed.rawFragment != null) return null
    if (parsed.port !in -1..65_535 || parsed.port == 0) return null
    val path = parsed.rawPath?.takeIf { it.isNotBlank() } ?: "/"
    return runCatching {
        URI(scheme, null, host, parsed.port, path, parsed.rawQuery, null).toASCIIString()
    }.getOrNull()?.takeIf { it.length <= AGENT_PREVIEW_MAX_URL_CHARS }
}

internal fun resolveAgentPreviewAddress(activeRunUrl: String?, savedOverride: String?): String? =
    normalizeAgentPreviewAddress(savedOverride) ?: normalizeAgentPreviewAddress(activeRunUrl)
