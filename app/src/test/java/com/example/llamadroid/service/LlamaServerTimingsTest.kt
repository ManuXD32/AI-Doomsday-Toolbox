package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaServerTimingsTest {
    @Test
    fun timingsAreParsedAndMergedWithTheUsageFrame() {
        val finalChunk = parseLlamaServerUsage(
            JSONObject("""{"timings":{"prompt_n":30,"prompt_ms":900.5,"predicted_n":120,"predicted_ms":6000.0}}""")
        )
        assertNotNull(finalChunk)
        assertEquals(120, finalChunk?.predictedTokens)
        assertEquals(6000.0, finalChunk?.predictedMs ?: 0.0, 0.0)
        assertEquals(30, finalChunk?.promptProcessedTokens)
        assertNull(finalChunk?.promptTokens)
        val usageFrame = parseLlamaServerUsage(JSONObject("""{"usage":{"prompt_tokens":900,"completion_tokens":120,"total_tokens":1020}}"""))!!
        val merged = mergeLlamaServerUsage(finalChunk, usageFrame)
        assertEquals(900, merged.promptTokens)
        assertEquals(120, merged.completionTokens)
        assertEquals(120, merged.predictedTokens)
        assertEquals(30, merged.promptProcessedTokens)
    }

    @Test
    fun emptyChunkStillParsesToNull() {
        assertNull(parseLlamaServerUsage(JSONObject("""{"choices":[]}""")))
    }

    @Test
    fun reasoningContentIsSentOnlyWhenRequested() {
        val messages = listOf(
            OllamaService.ChatMessage(role = "user", content = "q"),
            OllamaService.ChatMessage(role = "assistant", content = "a", thinking = "my reasoning")
        )
        fun assistantOf(options: LlamaServerRequestOptions): Map<*, *> {
            val payload = buildLlamaServerChatRequestPayload(messages, emptyList(), thinkingEnabled = true, requestOptions = options)
            @Suppress("UNCHECKED_CAST")
            return (payload["messages"] as List<Map<*, *>>).last()
        }
        assertEquals("my reasoning", assistantOf(LlamaServerRequestOptions(sendReasoningContent = true))["reasoning_content"])
        assertNull(assistantOf(LlamaServerRequestOptions())["reasoning_content"])
    }
}
