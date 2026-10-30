package com.example.llamadroid.service.chat

/**
 * Which failed native tool calls are worth running a second time. Only transient failures are:
 * timeouts, network/IO errors, a busy or briefly unavailable server. Fixed rejections (validation
 * errors, blocked URLs, unknown ids, permission denials) give the same answer again, so retrying them
 * only burns time and invites the model to loop. Pure.
 */
object NativeToolRetryPolicy {
    private val FIXED_REJECTION_MARKERS = listOf(
        "missing", "invalid", "required", "must be", "must not", "not allowed", "not permitted", "blocked",
        "denied", "permission", "forbidden", "unauthorized", "disabled", "unsupported", "unknown",
        "not found", "no such", "does not exist", "doesn't exist", "already exists", "malformed",
        "cannot be empty", "is empty", "not enabled", "refused by", "rejected", "out of range", "too large",
        "not supported", "not a valid", "illegal"
    )

    /** Network / server conditions that decide on their own, even if the text also says "unknown host". */
    private val STRONG_TRANSIENT_MARKERS = listOf(
        "timeout", "timed out", "sockettimeout", "unknownhost", "unable to resolve host", "connectexception",
        "failed to connect", "connection reset", "connection refused", "connection closed", "connection aborted",
        "broken pipe", "unexpected end of stream", "eofexception", "server busy", "is busy", "too many requests",
        "http 429", "http 502", "http 503", "http 504", "service unavailable"
    )

    private val WEAK_TRANSIENT_MARKERS = listOf(
        "ioexception", "sslexception", "network", "temporarily", "try again", "overloaded", "http 500"
    )

    /** True when [result] is a `tool_error:` that looks transient and is not a fixed rejection. */
    fun shouldRetry(result: String): Boolean {
        if (!result.startsWith("tool_error:", ignoreCase = true)) return false
        val text = result.lowercase()
        if (STRONG_TRANSIENT_MARKERS.any { text.contains(it) }) return true
        if (FIXED_REJECTION_MARKERS.any { text.contains(it) }) return false
        return WEAK_TRANSIENT_MARKERS.any { text.contains(it) }
    }
}
