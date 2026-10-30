package com.example.llamadroid.service.compaction

import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.OllamaService.ChatMessage
import com.example.llamadroid.service.OllamaService.ToolCall
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {
    @After fun reset() = TokenEstimator.resetCalibration()

    @Test fun textAndMessage() {
        val e = TokenEstimator()
        assertEquals(0, e.estimateText(""))
        assertEquals(3, e.estimateText("x".repeat(9)))
        assertEquals(10 + 8, e.estimateMessage(ChatMessage(role = "user", content = "x".repeat(40))))
    }

    @Test fun toolCallsAndMedia() {
        val e = TokenEstimator()
        val m = ChatMessage(
            role = "assistant", content = "", images = listOf("a"),
            toolCalls = listOf(ToolCall("ab", emptyMap(), rawArgumentsJson = "x".repeat(18)))
        )
        // chars 20 -> 5, +8 msg +8 call +1024 image
        assertEquals(5 + 8 + 8 + 1024, e.estimateMessage(m))
        assertEquals(e.estimateMessage(m) * 2, e.estimateMessages(listOf(m, m)))
    }

    @Test fun toolsAndCalibration() {
        val t = AgentTool("n", "d", mapOf("p" to "q"), schemaJson = "x".repeat(100))
        assertTrue(TokenEstimator().estimateTools(listOf(t)) >= 25)
        assertEquals(20, TokenEstimator(2.0).estimateText("x".repeat(40)))
    }

    @Test fun recordExactBounded() {
        repeat(50) { TokenEstimator.recordExact("k", 100, 1000) }
        assertEquals(TokenEstimator.MAX_FACTOR, TokenEstimator.factorFor("k"), 1e-6)
        repeat(100) { TokenEstimator.recordExact("j", 1000, 10) }
        assertEquals(TokenEstimator.MIN_FACTOR, TokenEstimator.factorFor("j"), 1e-6)
        assertEquals(1.0, TokenEstimator.factorFor("none"), 0.0)
        TokenEstimator.recordExact("z", 100, 150)
        assertTrue(TokenEstimator.factorFor("z") in 1.0..1.5)
    }
}
