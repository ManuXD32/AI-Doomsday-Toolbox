package com.example.llamadroid.service

import java.io.Reader

/**
 * Limits for the llama-server SSE transport. The wire limit is intentionally
 * larger than the semantic output limit because some compatible servers send
 * cumulative `message` snapshots instead of small `delta` fragments. Only
 * one bounded line is retained while reading; parsed assistant text remains
 * bounded by the semantic budget.
 */
internal object LlamaServerSseLimits {
    const val MAX_LINE_CHARS = 262_144
    const val MIN_WIRE_RESPONSE_CHARS = 2_097_152L
    const val DEFAULT_WIRE_RESPONSE_CHARS = 8L * 1024L * 1024L
    const val MAX_WIRE_RESPONSE_CHARS = 32L * 1024L * 1024L
    const val MIN_SEMANTIC_OUTPUT_CHARS = 64L * 1024L
    const val MAX_SEMANTIC_OUTPUT_CHARS = 2L * 1024L * 1024L

    /**
     * Give cumulative-snapshot servers room on the wire without allowing the
     * parsed result to grow without bound. 8 KiB per requested token is a
     * transport allowance, not an output-size promise.
     */
    fun wireResponseLimit(maxTokens: Int?): Long {
        val requested = maxTokens?.takeIf { it > 0 }?.toLong()
        return if (requested == null) {
            DEFAULT_WIRE_RESPONSE_CHARS
        } else {
            (512L * 1024L + requested * 8L * 1024L)
                .coerceIn(MIN_WIRE_RESPONSE_CHARS, MAX_WIRE_RESPONSE_CHARS)
        }
    }

    /**
     * Bound content, reasoning, and tool-argument text by the configured
     * generation allowance. The multiplier covers UTF-8/JSON overhead and
     * verbose reasoning while remaining far below an unbounded StringBuilder.
     */
    fun semanticOutputLimit(maxTokens: Int?): Long {
        val requested = maxTokens?.takeIf { it > 0 }?.toLong() ?: 4_096L
        return (requested * 64L)
            .coerceIn(MIN_SEMANTIC_OUTPUT_CHARS, MAX_SEMANTIC_OUTPUT_CHARS)
    }
}

internal enum class LlamaServerSseLimitKind(val code: String) {
    LINE("SSE_LINE_LIMIT"),
    RESPONSE("SSE_RESPONSE_LIMIT"),
    OUTPUT("SSE_OUTPUT_LIMIT")
}

/** Stable, bounded transport failure. It never contains model response text. */
internal class LlamaServerSseLimitException(
    val kind: LlamaServerSseLimitKind,
    val limitCharacters: Long,
    val receivedCharacters: Long
) : IllegalStateException(
    "${kind.code}: received=$receivedCharacters limit=$limitCharacters"
)

internal class LlamaServerSseResponseBudget(maxTokens: Int?) {
    val wireLimitCharacters: Long = LlamaServerSseLimits.wireResponseLimit(maxTokens)
    val semanticLimitCharacters: Long = LlamaServerSseLimits.semanticOutputLimit(maxTokens)

    var wireCharacters: Long = 0L
        private set
    var semanticCharacters: Long = 0L
        private set

    /** Returns whether the next line can be read without crossing the wire bound. */
    fun canReadWireCharacter(lineCharacters: Int): Boolean =
        wireCharacters + lineCharacters.toLong() < wireLimitCharacters

    fun accountWireLine(lineCharacters: Int) {
        val next = wireCharacters + lineCharacters.toLong()
        if (next > wireLimitCharacters) {
            throw LlamaServerSseLimitException(
                kind = LlamaServerSseLimitKind.RESPONSE,
                limitCharacters = wireLimitCharacters,
                receivedCharacters = next
            )
        }
        wireCharacters = next
    }

    fun accountSemanticCharacters(characters: Int) {
        if (characters <= 0) return
        val next = semanticCharacters + characters.toLong()
        if (next > semanticLimitCharacters) {
            throw LlamaServerSseLimitException(
                kind = LlamaServerSseLimitKind.OUTPUT,
                limitCharacters = semanticLimitCharacters,
                receivedCharacters = next
            )
        }
        semanticCharacters = next
    }
}

internal data class LlamaServerSseLine(
    val text: String,
    val reachedEof: Boolean
)

/**
 * Read one SSE line with a hard bound. `BufferedReader.readLine()` is avoided
 * because it allocates until a newline arrives; this reader checks the limit
 * before each append and retains at most MAX_LINE_CHARS characters.
 */
internal fun readBoundedLlamaServerSseLine(
    reader: Reader,
    budget: LlamaServerSseResponseBudget,
    maxLineCharacters: Int = LlamaServerSseLimits.MAX_LINE_CHARS
): LlamaServerSseLine {
    require(maxLineCharacters > 0) { "maxLineCharacters must be positive" }
    val line = StringBuilder(minOf(maxLineCharacters, 4_096))
    var lineCharacters = 0
    while (true) {
        val next = reader.read()
        if (next == -1) {
            budget.accountWireLine(lineCharacters)
            return LlamaServerSseLine(line.toString(), reachedEof = true)
        }
        if (next == '\n'.code) {
            budget.accountWireLine(lineCharacters)
            return LlamaServerSseLine(line.toString(), reachedEof = false)
        }
        if (lineCharacters >= maxLineCharacters) {
            throw LlamaServerSseLimitException(
                kind = LlamaServerSseLimitKind.LINE,
                limitCharacters = maxLineCharacters.toLong(),
                receivedCharacters = lineCharacters.toLong() + 1L
            )
        }
        if (!budget.canReadWireCharacter(lineCharacters)) {
            throw LlamaServerSseLimitException(
                kind = LlamaServerSseLimitKind.RESPONSE,
                limitCharacters = budget.wireLimitCharacters,
                receivedCharacters = budget.wireCharacters + lineCharacters.toLong() + 1L
            )
        }
        line.append(next.toChar())
        lineCharacters++
    }
}

/**
 * Convert a cumulative fallback snapshot into only the newly observed suffix.
 * If a server sends an older/shorter snapshot again, it contributes nothing.
 */
internal fun llamaServerFallbackSnapshotDelta(previous: String, current: String): String {
    if (current.isEmpty() || current == previous) return ""
    if (previous.isEmpty()) return current
    if (current.startsWith(previous)) return current.substring(previous.length)
    if (previous.startsWith(current)) return ""
    // The server changed representation or sent an independent message. Keep
    // it rather than silently losing content that cannot be proven duplicate.
    return current
}

internal fun isTerminalLlamaServerFinishReason(reason: String?): Boolean =
    when (reason?.trim()?.lowercase()) {
        "stop", "tool_calls", "function_call", "length", "max_tokens", "content_filter" -> true
        else -> false
    }

internal enum class LlamaServerTerminalLineAction {
    SKIP_BLANK,
    PARSE_USAGE,
    STOP
}

/** Keep post-terminal SSE handling bounded even when a server emits comments or blanks. */
internal fun classifyLlamaServerTerminalLine(
    data: String,
    blankLinesSeen: Int
): LlamaServerTerminalLineAction {
    if (data.isBlank()) {
        return if (blankLinesSeen < 32) {
            LlamaServerTerminalLineAction.SKIP_BLANK
        } else {
            LlamaServerTerminalLineAction.STOP
        }
    }
    if (!data.startsWith("data: ")) return LlamaServerTerminalLineAction.STOP
    val json = data.removePrefix("data: ").trim()
    return if (json.isEmpty() || json == "[DONE]") {
        LlamaServerTerminalLineAction.STOP
    } else {
        LlamaServerTerminalLineAction.PARSE_USAGE
    }
}

internal fun formatLlamaServerSseFailure(error: Throwable): String =
    if (error is LlamaServerSseLimitException) {
        // Keep this message bounded and machine-readable. The caller's
        // localized Continue surface owns natural-language presentation.
        "${error.kind.code};received=${error.receivedCharacters};limit=${error.limitCharacters}"
    } else {
        "${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}"
    }
