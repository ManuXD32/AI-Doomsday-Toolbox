package com.example.llamadroid.service.chat

import com.example.llamadroid.service.OllamaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSpeedTrackerTest {
    @Test
    fun sumsDecodeTokensAndMillisecondsAcrossRounds() {
        val t = GenerationSpeedTracker()
        t.endRound(DecodeTimings(100, 2_000.0, promptTokens = 500))
        t.endRound(DecodeTimings(50, 2_000.0, promptTokens = 40))
        // (100 + 50) / (2.0 + 2.0 s) = 37.5 t/s, regardless of any tool time in between.
        assertEquals(37.5, t.finalTokensPerSecond()!!, 0.0001)
        assertEquals(2, t.timedCalls)
        assertEquals(540, t.processedPromptTokens)
        assertTrue(t.hasProcessedPrompt())
    }

    @Test
    fun liveValueUsesOnlyTheCurrentRoundsStreamingWindow() {
        val t = GenerationSpeedTracker()
        t.endRound(DecodeTimings(100, 10_000.0)) // 10 t/s earlier
        t.beginRound()
        // A long pause before the first token (tools, prompt processing) must not count.
        t.onToken(60_000L)
        repeat(20) { t.onToken(60_000L + (it + 1) * 100L) }
        val live = t.liveTokensPerSecond(62_000L)!!
        // 100 + 20 tokens over 10 s + 2 s
        assertEquals(120 / 12.0, live, 0.01)
    }

    @Test
    fun roundWithoutTimingsContributesItsStreamingEstimate() {
        val t = GenerationSpeedTracker()
        t.beginRound()
        t.onToken(1_000L)
        repeat(10) { t.onToken(1_000L + (it + 1) * 100L) }
        t.endRound(null)
        assertEquals(0, t.timedCalls)
        assertFalse(t.hasProcessedPrompt())
        assertEquals(10.0, t.finalTokensPerSecond()!!, 0.01)
    }

    @Test
    fun noTokensGivesNoSpeed() {
        val t = GenerationSpeedTracker()
        assertNull(t.finalTokensPerSecond())
        t.endRound(null)
        assertNull(t.liveTokensPerSecond(10L))
    }

    @Test
    fun decodeTimingsReadLlamaServerAndOllamaUsage() {
        val llama = OllamaService.ChatUsage(predictedTokens = 80, predictedMs = 4_000.0, promptProcessedTokens = 12, completionTokens = 80)
        assertEquals(DecodeTimings(80, 4_000.0, 12), DecodeTimings.from(llama))
        val ollama = OllamaService.ChatUsage(completionTokens = 60, evalDurationNs = 3_000_000_000L)
        assertEquals(DecodeTimings(60, 3_000.0, null), DecodeTimings.from(ollama))
        assertNull(DecodeTimings.from(OllamaService.ChatUsage(completionTokens = 5)))
        assertNull(DecodeTimings.from(null))
        assertNotNull(DecodeTimings.from(llama)?.takeIf { it.isUsable })
    }
}
