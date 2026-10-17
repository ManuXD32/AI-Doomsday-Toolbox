package com.example.llamadroid.service

import com.example.llamadroid.service.LlamaClientService.ToolActivityEvent
import com.example.llamadroid.service.chat.DecodeTimings
import com.example.llamadroid.service.chat.GenerationSpeedTracker
import com.example.llamadroid.service.chat.NativeChatStepRecorder
import kotlinx.coroutines.*

internal data class StreamingProgress(
    var content: String = "",
    var thinking: String = "",
    var promptTokens: Int = 0,
    var completionTokens: Int = 0,
    var tokenCount: Int = 0,
    var streamRequestStartTimeMs: Long = System.currentTimeMillis(),
    var firstTokenReceivedAtMs: Long? = null,
    var isTruncated: Boolean = false,
    var reportedTokensPerSecond: Double? = null,
    var statusText: String? = null,
    var promptProgress: Float? = null,
    var promptProcessed: Int = 0,
    var promptTotal: Int = 0,
    var promptCached: Int = 0,
    /** Bounded Native Chat video preparation progress exposed to the chat UI. */
    var videoProgress: Float? = null,
    var videoVisualProgress: Float = 0f,
    var videoAudioProgress: Float = 0f,
    var videoVisualPhase: String? = null,
    var videoAudioPhase: String? = null,
    val toolEvents: MutableList<ToolActivityEvent> = mutableListOf(),
    val generatedImagePaths: MutableList<String> = mutableListOf(),
    var lastPowerDiagnosticMs: Long = 0L,
    var lastStateUpdateMs: Long = 0L,
    var lastTokenAtMs: Long = System.currentTimeMillis()
) {
    /** Visible steps of the reply being generated (tool turns and resumed replies); null for plain replies. */
    var stepRecorder: NativeChatStepRecorder? = null

    /** Decode tokens and time summed over the reply's model calls (tool execution excluded). */
    val speed = GenerationSpeedTracker()

    fun resetForNewStream(nowMs: Long = System.currentTimeMillis()) {
        streamRequestStartTimeMs = nowMs
        firstTokenReceivedAtMs = null
        tokenCount = 0
        promptTokens = 0
        completionTokens = 0
        isTruncated = false
        promptProgress = null
        promptProcessed = 0
        promptTotal = 0
        promptCached = 0
        lastStateUpdateMs = 0L
        lastTokenAtMs = nowMs
        speed.beginRound()
    }

    fun markFirstTokenReceived(nowMs: Long = System.currentTimeMillis()) {
        if (firstTokenReceivedAtMs == null) {
            firstTokenReceivedAtMs = nowMs
        }
        lastTokenAtMs = nowMs
        speed.onToken(nowMs)
    }

    /** Streaming speed shown while generating: decode only, the call in progress counted from its first token. */
    fun liveTokensPerSecond(nowMs: Long = System.currentTimeMillis()): Double =
        speed.liveTokensPerSecond(nowMs) ?: legacyTokensPerSecond(nowMs)

    /**
     * Speed stored on the reply: server timings summed over the calls, else the engine's own figure, else
     * the streaming window of the calls, else the old whole-reply estimate.
     */
    fun finalTokensPerSecond(): Double =
        if (speed.timedCalls > 0) speed.finalTokensPerSecond() ?: legacyTokensPerSecond()
        else reportedTokensPerSecond ?: speed.finalTokensPerSecond() ?: legacyTokensPerSecond()

    /** Prompt tokens stored on the reply: processed ones (cache hits excluded) when reported, else the summed usage. */
    fun displayPromptTokens(): Int = if (speed.hasProcessedPrompt()) speed.processedPromptTokens else promptTokens

    /** Folds one finished call's usage into the totals. */
    fun absorbCallUsage(usage: OllamaService.ChatUsage?) {
        usage?.promptTokens?.let { promptTokens += it }
        usage?.completionTokens?.let { completed ->
            completionTokens += completed
            tokenCount = completionTokens
        }
        speed.endRound(DecodeTimings.from(usage))
    }

    private fun legacyTokensPerSecond(nowMs: Long = System.currentTimeMillis()): Double {
        val elapsed = generationElapsedSeconds(nowMs)
        return if (elapsed > 0.0) tokenCount / elapsed else 0.0
    }

    fun generationElapsedMs(nowMs: Long = System.currentTimeMillis()): Long =
        generationElapsedMs(
            requestStartedAtMs = streamRequestStartTimeMs,
            firstTokenReceivedAtMs = firstTokenReceivedAtMs,
            nowMs = nowMs
        )

    fun generationElapsedSeconds(nowMs: Long = System.currentTimeMillis()): Double =
        generationElapsedMs(nowMs) / 1000.0
}
