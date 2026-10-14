package com.example.llamadroid.service.compaction

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatContextFallbackTest {
    private fun msg(id: Long, role: String, text: String, prompt: Int = 0, completion: Int = 0) =
        LlamaMessageEntity(id = id, chatId = 1, role = role, content = text, promptTokens = prompt, completionTokens = completion)

    @Test fun emptyHistoryHasNoValue() {
        assertNull(NativeChatContextFallback.compute("sys", emptyList(), 4096))
    }

    @Test fun usesReportedTokensWhenPresent() {
        val usage = NativeChatContextFallback.compute(
            null, listOf(msg(1, "user", "hi"), msg(2, "assistant", "yo", 300, 50)), 4096
        )!!
        assertEquals(350, usage.usedTokens)
        assertTrue(usage.measured)
    }

    @Test fun estimatesWhenNothingReportedAndRespectsBoundary() {
        val history = listOf(msg(1, "user", "a".repeat(4000)), msg(2, "assistant", "ok"), msg(3, "user", "b".repeat(400)))
        val all = NativeChatContextFallback.compute(null, history, 100_000)!!
        val afterBoundary = NativeChatContextFallback.compute(null, history, 100_000, boundaryId = 2L, summary = "short")!!
        assertFalse(all.measured)
        assertTrue(afterBoundary.usedTokens < all.usedTokens)
    }

    @Test fun clampsToWindow() {
        val usage = NativeChatContextFallback.compute(null, listOf(msg(1, "user", "x".repeat(40_000))), 1000)!!
        assertEquals(1000, usage.usedTokens)
    }
}
