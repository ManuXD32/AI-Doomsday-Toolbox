package com.example.llamadroid.harness

/**
 * Pure mapping from a model-provider failure to the stable code the Harness plugin understands.
 * The plugin passes any `[A-Z][A-Z0-9_]{1,80}` code through unchanged, and `dsh-compaction-basic`
 * compacts and retries on exactly [CONTEXT_WINDOW_EXCEEDED].
 */
object HarnessProviderErrorCodes {
    const val CONTEXT_WINDOW_EXCEEDED = "CONTEXT_WINDOW_EXCEEDED"
    const val LLAMA_SERVER_REJECTED = "LLAMA_SERVER_REJECTED"
    const val LLAMA_SERVER_UNREACHABLE = "LLAMA_SERVER_UNREACHABLE"
    const val PROVIDER_INTERRUPTED = "PROVIDER_INTERRUPTED"

    /** The connection to a running llama-server dropped mid-request (reset, early EOF). */
    const val LLAMA_SERVER_CONNECTION_LOST = "LLAMA_SERVER_CONNECTION_LOST"

    /** The adapter could not parse the model's response stream (protocol or JSON failure). */
    const val ADAPTER_STREAM_ERROR = "ADAPTER_STREAM_ERROR"

    /** Fail-fast code: the managed server died repeatedly, see [HarnessModelServerStability]. */
    const val MODEL_SERVER_UNSTABLE = "MODEL_SERVER_UNSTABLE"

    private const val MAX_SNIPPET = 200

    /** True when [message] (a llama-server error text) reports a context-size overflow. */
    fun isContextOverflow(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "exceed_context_size" in text ||
            "exceeds the available context" in text ||
            ("context" in text && "exceed" in text)
    }

    /**
     * Classify one throwable chain. [cause chain] is walked (cycle-safe); the first match wins:
     * context overflow, other `llama-server error:` bodies, then connection failures.
     * Returns null when nothing matches so the caller keeps its own fallback.
     */
    fun classify(error: Throwable): String? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var rejected = false
        var unreachable = false
        var connectionLost = false
        var parseFailure = false
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            val message = cause.message
            if (isContextOverflow(message)) return CONTEXT_WINDOW_EXCEEDED
            if (message?.contains("llama-server error", ignoreCase = true) == true) rejected = true
            if (cause is java.net.ConnectException || cause is java.net.SocketTimeoutException ||
                message?.contains("connection refused", ignoreCase = true) == true
            ) unreachable = true
            if (cause is java.net.SocketException || cause is java.io.EOFException ||
                message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                message?.contains("connection reset", ignoreCase = true) == true
            ) connectionLost = true
            if (cause is org.json.JSONException || cause is kotlinx.serialization.SerializationException) parseFailure = true
            cause = cause.cause
        }
        return when {
            rejected -> LLAMA_SERVER_REJECTED
            unreachable -> LLAMA_SERVER_UNREACHABLE
            connectionLost -> LLAMA_SERVER_CONNECTION_LOST
            parseFailure -> ADAPTER_STREAM_ERROR
            else -> null
        }
    }

    /** Bounded (200 chars) llama-server error body of the first `llama-server error` in the chain, if any. */
    fun bodySnippet(error: Throwable): String? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            val message = cause.message
            if (message?.contains("llama-server error", ignoreCase = true) == true) return sanitize(message)
            cause = cause.cause
        }
        return null
    }

    /** Metadata-only diagnostic line: code, exception class and a bounded llama-server body snippet. */
    fun diagnostic(code: String, error: Throwable): String {
        val snippet = bodySnippet(error)
        return "provider failure code=$code class=${error.javaClass.simpleName}" +
            (snippet?.let { " body=\"$it\"" } ?: "")
    }

    internal fun sanitize(text: String): String =
        text.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_SNIPPET)
}
