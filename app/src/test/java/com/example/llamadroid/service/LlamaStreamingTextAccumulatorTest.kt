package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaStreamingTextAccumulatorTest {

    @Test
    fun `thinking tags split across chunks are parsed without rescanning the whole response`() {
        val accumulator = LlamaStreamingTextAccumulator(parseThinkingTags = true)

        accumulator.appendContent("visible <thi")
        accumulator.appendContent("nk>plan")
        accumulator.appendContent("</thi")
        accumulator.appendContent("nk>answer")

        val result = accumulator.finish()
        assertEquals("visible answer", result.content)
        assertEquals("plan", result.thinking)
        assertEquals("visible <think>plan</think>answer", result.rawContent)
    }

    @Test
    fun `dedicated reasoning is retained alongside tagged reasoning`() {
        val accumulator = LlamaStreamingTextAccumulator(parseThinkingTags = true)

        accumulator.appendDedicatedThinking("server reasoning")
        accumulator.appendContent("<think>inline reasoning</think>answer")

        val result = accumulator.finish()
        assertEquals("answer", result.content)
        assertEquals("server reasoning\ninline reasoning", result.thinking)
    }

    @Test
    fun `plain continuation keeps literal tags and existing content`() {
        val accumulator = LlamaStreamingTextAccumulator(
            parseThinkingTags = false,
            initialContent = "previous",
            initialThinking = "existing reasoning"
        )

        accumulator.appendContent(" <think>literal</think>")

        val result = accumulator.finish()
        assertEquals("previous <think>literal</think>", result.content)
        assertEquals("existing reasoning", result.thinking)
        assertTrue(result.rawContent.endsWith("<think>literal</think>"))
    }

    @Test
    fun `reset discards the previous stream and starts a fresh parser state`() {
        val accumulator = LlamaStreamingTextAccumulator(parseThinkingTags = true)
        accumulator.appendContent("old <think>discarded")

        accumulator.reset()
        accumulator.appendContent("new <think>plan</think>answer")

        val result = accumulator.finish()
        assertEquals("new answer", result.content)
        assertEquals("plan", result.thinking)
        assertEquals("new <think>plan</think>answer", result.rawContent)
    }
}
