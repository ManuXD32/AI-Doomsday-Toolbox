package com.example.llamadroid.service

import com.example.llamadroid.service.LlamaClientService.ToolActivityEvent
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
    }

    fun markFirstTokenReceived(nowMs: Long = System.currentTimeMillis()) {
        if (firstTokenReceivedAtMs == null) {
            firstTokenReceivedAtMs = nowMs
        }
        lastTokenAtMs = nowMs
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
