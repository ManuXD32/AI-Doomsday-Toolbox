package com.example.llamadroid.service.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatStepsCodecTest {
    @Test
    fun roundTripKeepsEveryField() {
        val steps = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.TEXT, 0, "Reading the file first."),
                NativeChatStep(
                    NativeChatStepType.TOOL, 0, "result", toolCallId = "call_1", toolName = "read_file",
                    arguments = "{\"path\":\"a.txt\"}", status = NativeChatStepStatus.OK, durationMs = 12L
                )
            ),
            complete = true
        )
        assertEquals(steps, NativeChatStepsCodec.decode(NativeChatStepsCodec.encode(steps)))
    }

    @Test
    fun decodeIsLenient() {
        assertNull(NativeChatStepsCodec.decode(null))
        assertNull(NativeChatStepsCodec.decode(" "))
        assertNull(NativeChatStepsCodec.decode("{not json"))
        assertEquals(0, NativeChatStepsCodec.decode("{\"steps\":[],\"future\":1}")?.steps?.size)
    }

    @Test
    fun encodingIsDeterministic() {
        val steps = NativeChatSteps(steps = listOf(NativeChatStep(NativeChatStepType.TEXT, 1, "x")))
        assertEquals(NativeChatStepsCodec.encode(steps), NativeChatStepsCodec.encode(steps.copy()))
    }

    @Test
    fun longResultsKeepHeadAndTail() {
        val text = "a".repeat(NativeChatStepsCodec.RESULT_HEAD_CHARS) + "b".repeat(5_000) +
            "c".repeat(NativeChatStepsCodec.RESULT_TAIL_CHARS)
        val (bounded, original) = NativeChatStepsCodec.boundResult(text)
        assertEquals(text.length, original)
        assertTrue(bounded.startsWith("a".repeat(NativeChatStepsCodec.RESULT_HEAD_CHARS)))
        assertTrue(bounded.endsWith("c".repeat(NativeChatStepsCodec.RESULT_TAIL_CHARS)))
        assertTrue(bounded.contains("5000 characters omitted"))
        assertEquals("short" to null, NativeChatStepsCodec.boundResult("short"))
    }
}
