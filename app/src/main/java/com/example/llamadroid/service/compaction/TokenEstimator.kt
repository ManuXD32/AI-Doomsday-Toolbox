package com.example.llamadroid.service.compaction

import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.OllamaService.ChatMessage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Cheap, deterministic prompt-size estimator (chars/4 plus fixed overheads, the same shape as
 * the legacy `AgentPromptBudget`). [calibration] scales every estimate; it is learned per
 * endpoint from exact server token counts via [recordExact] so the estimate converges on the
 * real tokenizer without a tokenizer dependency.
 */
class TokenEstimator(private val calibration: Double = 1.0) {

    fun estimateText(text: String): Int = scale(ceil(text.length / CHARS_PER_TOKEN))

    fun estimateMessage(m: ChatMessage): Int {
        var chars = m.content.length + (m.thinking?.length ?: 0)
        var extra = MESSAGE_OVERHEAD
        m.toolCalls?.forEach { call ->
            extra += TOOL_CALL_OVERHEAD
            chars += call.name.length
            chars += call.rawArgumentsJson?.length
                ?: call.arguments.entries.sumOf { it.key.length + it.value.length + 4 }
        }
        val media = (m.images?.size ?: 0) + (if (m.audioPath != null) 1 else 0)
        extra += media * MEDIA_TOKENS
        return scale(ceil(chars / CHARS_PER_TOKEN)) + extra
    }

    fun estimateMessages(ms: List<ChatMessage>): Int = ms.sumOf { estimateMessage(it) }

    fun estimateTools(tools: List<AgentTool>): Int {
        var chars = 0
        for (t in tools) {
            chars += t.name.length + t.description.length
            chars += t.parameters.entries.sumOf { it.key.length + it.value.length + 4 }
            chars += t.requiredParams.sumOf { it.length + 3 }
            chars += t.schemaJson?.length ?: 0
        }
        return scale(ceil(chars / CHARS_PER_TOKEN))
    }

    private fun scale(raw: Double): Int = ceil(raw * calibration).toInt()

    companion object {
        private const val CHARS_PER_TOKEN = 4.0
        private const val MESSAGE_OVERHEAD = 8
        private const val TOOL_CALL_OVERHEAD = 8
        private const val MEDIA_TOKENS = 1024
        const val MIN_FACTOR = 0.5
        const val MAX_FACTOR = 2.0
        private const val EMA_ALPHA = 0.3

        // Process-lifetime registry: endpoint key -> calibration factor.
        private val factors = ConcurrentHashMap<String, Double>()

        fun calibrated(endpointKey: String): TokenEstimator = TokenEstimator(factorFor(endpointKey))

        fun factorFor(endpointKey: String): Double = factors[endpointKey] ?: 1.0

        /**
         * [estimated] must come from an estimator obtained via [calibrated] for the same key;
         * the raw (uncalibrated) estimate is recovered by dividing out the current factor, so
         * the new target factor is `current * exact / estimated`, blended by an EMA and clamped.
         */
        fun recordExact(endpointKey: String, estimated: Int, exact: Int) {
            if (estimated <= 0 || exact <= 0) return
            val current = factorFor(endpointKey)
            val target = (current * exact / estimated).coerceIn(MIN_FACTOR, MAX_FACTOR)
            val next = (current * (1 - EMA_ALPHA) + target * EMA_ALPHA).coerceIn(MIN_FACTOR, MAX_FACTOR)
            factors[endpointKey] = next
        }

        internal fun resetCalibration() = factors.clear()
    }
}
