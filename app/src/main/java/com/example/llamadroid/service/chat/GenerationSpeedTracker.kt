package com.example.llamadroid.service.chat

import com.example.llamadroid.service.OllamaService

/** Decode counters of one model call, from llama-server `timings` or Ollama eval counts. */
data class DecodeTimings(
    val predictedTokens: Int,
    val predictedMs: Double,
    /** Prompt tokens actually processed (cache hits excluded); null when the server did not say. */
    val promptTokens: Int? = null
) {
    val isUsable: Boolean get() = predictedTokens > 0 && predictedMs > 0.0

    companion object {
        /** llama-server `timings` first, then Ollama's `eval_count` / `eval_duration`. */
        fun from(usage: OllamaService.ChatUsage?): DecodeTimings? {
            usage ?: return null
            val tokens = usage.predictedTokens
            val ms = usage.predictedMs
            if (tokens != null && ms != null) {
                return DecodeTimings(tokens, ms, usage.promptProcessedTokens).takeIf { it.isUsable }
            }
            val evalNs = usage.evalDurationNs
            val completion = usage.completionTokens
            if (evalNs != null && completion != null) {
                return DecodeTimings(completion, evalNs / 1_000_000.0).takeIf { it.isUsable }
            }
            return null
        }
    }
}

/**
 * Honest tokens-per-second for a reply that spans several model calls (tool rounds).
 *
 * Decode tokens and decode milliseconds are summed over the calls, so tool execution, prompt
 * processing and queueing never count: speed = sum(predicted_n) / sum(predicted_ms) * 1000. A call
 * without timings contributes an estimate from its own streaming window (first to last token).
 * The live value adds the call in progress, measured from its own first token only. Not thread-safe by
 * design: written by the single streaming coroutine, read for display.
 */
class GenerationSpeedTracker {
    var decodeTokens: Int = 0
        private set
    var decodeMs: Double = 0.0
        private set
    /** Sum of the prompt tokens actually processed over the calls that reported it. */
    var processedPromptTokens: Int = 0
        private set
    /** Calls that finished with real server timings. */
    var timedCalls: Int = 0
        private set
    /** Calls that reported a processed-prompt count. */
    var promptReportedCalls: Int = 0
        private set

    private var roundTokens = 0
    private var roundFirstTokenMs: Long? = null
    private var roundLastTokenMs: Long = 0L

    /** Starts a model call: its streaming clock begins at its own first token. */
    fun beginRound() {
        roundTokens = 0
        roundFirstTokenMs = null
    }

    fun onToken(nowMs: Long) {
        if (roundFirstTokenMs == null) roundFirstTokenMs = nowMs
        roundTokens++
        roundLastTokenMs = nowMs
    }

    /** Ends the current call, with the server's [timings] when it sent them. */
    fun endRound(timings: DecodeTimings?) {
        if (timings != null && timings.isUsable) {
            decodeTokens += timings.predictedTokens
            decodeMs += timings.predictedMs
            timedCalls++
            timings.promptTokens?.let {
                processedPromptTokens += it
                promptReportedCalls++
            }
        } else {
            estimate(roundLastTokenMs)?.let { (tokens, ms) ->
                decodeTokens += tokens
                decodeMs += ms
            }
        }
        beginRound()
    }

    /** Speed including the call in progress (up to [nowMs]); null before any token timing exists. */
    fun liveTokensPerSecond(nowMs: Long): Double? = speed(nowMs)

    /** Speed of everything streamed so far; null when no call produced a usable window. */
    fun finalTokensPerSecond(): Double? = speed(roundLastTokenMs)

    /** Prompt tokens to show: processed (cache hits excluded) when the server reported them. */
    fun hasProcessedPrompt(): Boolean = promptReportedCalls > 0

    private fun speed(nowMs: Long): Double? {
        var tokens = decodeTokens
        var ms = decodeMs
        estimate(nowMs)?.let { (t, m) ->
            tokens += t
            ms += m
        }
        return if (tokens > 0 && ms > 0.0) tokens / ms * 1000.0 else null
    }

    /** Tokens after the first and the time they took, for the open call; needs a stable window. */
    private fun estimate(untilMs: Long): Pair<Int, Double>? {
        val first = roundFirstTokenMs ?: return null
        val windowMs = (untilMs - first).toDouble()
        if (roundTokens < 2 || windowMs < MIN_WINDOW_MS) return null
        return (roundTokens - 1) to windowMs
    }

    private companion object {
        const val MIN_WINDOW_MS = 200.0
    }
}
