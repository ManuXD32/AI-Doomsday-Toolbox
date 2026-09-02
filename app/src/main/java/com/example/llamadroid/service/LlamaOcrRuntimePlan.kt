package com.example.llamadroid.service

import java.net.URI
import java.util.Locale

/**
 * The small, deterministic part of the OCR runtime policy that can be tested without Android.
 *
 * OCR has two modes:
 *
 *  * coexistence (the default), where no already-running server is a candidate for pausing; and
 *  * opt-in replacement, where the selected translation endpoint is preferred and the server
 *    occupying the OCR port is added when it is a different runtime.
 *
 * Returning at most the runtimes that are actually needed keeps an unrelated card/server out of
 * the durable lease and therefore out of the stop/restore cycle.
 */
internal data class LlamaOcrRuntimePausePlan(
    val runtimesToPause: List<LlamaOcrCapturedRuntime>,
    val preferredTranslationPort: Int?
)

internal fun planLlamaOcrRuntimePause(
    capturedRuntimes: List<LlamaOcrCapturedRuntime>,
    ocrPort: Int,
    temporarilyReplaceRunningServer: Boolean,
    preferredTranslationPort: Int?
): LlamaOcrRuntimePausePlan {
    if (!temporarilyReplaceRunningServer) {
        return LlamaOcrRuntimePausePlan(
            runtimesToPause = emptyList(),
            preferredTranslationPort = preferredTranslationPort
        )
    }

    val selected = linkedMapOf<String, LlamaOcrCapturedRuntime>()
    val deterministicRuntimes = capturedRuntimes.sortedWith(
        compareBy<LlamaOcrCapturedRuntime>({ it.sessionId }, { it.port ?: Int.MAX_VALUE })
    )
    // The selected manga translation endpoint is the first choice. If translation uses another
    // provider, or that endpoint is not currently running, the first deterministically detected
    // app-owned server is the fallback requested by the opt-in replacement policy.
    val primary = preferredTranslationPort?.let { preferredPort ->
        deterministicRuntimes.firstOrNull { it.port == preferredPort }
    } ?: deterministicRuntimes.firstOrNull()
    primary?.let { selected[it.sessionId] = it }

    // A different local server can still own the OCR port. It must be paused for OCR to bind,
    // but only that occupant is added; other running cards remain untouched.
    deterministicRuntimes.firstOrNull { it.port == ocrPort }
        ?.let { selected[it.sessionId] = it }

    return LlamaOcrRuntimePausePlan(
        runtimesToPause = selected.values.toList(),
        preferredTranslationPort = preferredTranslationPort
    )
}

/**
 * Returns the port only for a loopback llama-server URL. Remote endpoints must never cause the
 * app to stop a local server merely because their port happens to match.
 */
internal fun localLlamaServerPort(
    rawUrl: String?,
    defaultPort: Int = DEFAULT_LLAMA_SERVER_PORT
): Int? {
    val value = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = if ("://" in value) value else "http://$value"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
    if (scheme !in setOf("http", "https")) return null
    val host = uri.host
        ?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase(Locale.US)
        ?: return null
    if (host !in LOOPBACK_HOSTS) return null
    val port = if (uri.port > 0) uri.port else defaultPort
    return port.takeIf { it in 1..65535 }
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")
private const val DEFAULT_LLAMA_SERVER_PORT = 8080
