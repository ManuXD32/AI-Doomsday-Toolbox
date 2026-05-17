package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmChatServiceTest {
    @Test
    fun `sanitizeLiteRtRenderedText removes turn tokens before markdown rendering`() {
        val raw = """
            <|start_of_turn|>model
            Hay informacion sobre la otitis.
            <|end_of_turn|>
            <|start_of_turn|>thought
            <|end_of_turn|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertEquals("Hay informacion sobre la otitis.", cleaned.trim())
        assertFalse(cleaned.contains("|"))
        assertFalse(cleaned.contains("start_of_turn"))
        assertFalse(cleaned.contains("end_of_turn"))
    }

    @Test
    fun `sanitizeLiteRtRenderedText keeps tool calls and ordinary angle text`() {
        val raw = """
            <|start_header_id|>assistant<|end_header_id|>
            Compare 2 < 3 and emit <tool_call>{"name":"web_search"}</tool_call>.
            <|eot_id|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertTrue(cleaned.contains("2 < 3"))
        assertTrue(cleaned.contains("<tool_call>"))
        assertFalse(cleaned.contains("start_header_id"))
        assertFalse(cleaned.contains("eot_id"))
    }

    @Test
    fun `sanitizeLiteRtRenderedText removes compact and split turn token spill`() {
        val raw = """
            <|start_of_turn|>model
            Hello<turn>
            < | turn>model
            !<|end_of_turn|>
        """.trimIndent()

        val cleaned = sanitizeLiteRtRenderedText(raw)

        assertEquals("Hello\n!", cleaned.trim())
        assertFalse(cleaned.contains("turn>model"))
        assertFalse(cleaned.contains("|"))
    }

    @Test
    fun `sanitizeLiteRtRenderedText removes leaked channel markers`() {
        val cleaned = sanitizeLiteRtRenderedText("<| channel>final\nHello there")

        assertEquals("Hello there", cleaned.trim())
        assertFalse(cleaned.contains("channel"))
    }

    @Test
    fun `sanitizeLiteRtRenderedTextForStreaming withholds dangling control token tails`() {
        assertEquals("", sanitizeLiteRtRenderedTextForStreaming("<"))
        assertEquals("Hello", sanitizeLiteRtRenderedTextForStreaming("Hello<|start_of"))
        assertEquals("2 < 3", sanitizeLiteRtRenderedTextForStreaming("2 < 3"))
    }

    @Test
    fun `liteRtStreamingDelta emits only new suffix for sanitized cumulative snapshots`() {
        assertEquals(" world", liteRtStreamingDelta("Hello world", "Hello"))
        assertEquals("", liteRtStreamingDelta("Hello", "Hello world"))
        assertEquals("there", liteRtStreamingDelta("Hi there", "Hi <"))
    }

    @Test
    fun `liteRtMessageSnapshot extracts text content instead of object string`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello! How can I help you today?"))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
        assertEquals("", snapshot.thought)
    }

    @Test
    fun `liteRtMessageSnapshot prefers Gallery message string when reflected content is compact`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Hello!HowcanIhelpyoutoday?"))
            ),
            renderedString = "Hello! How can I help you today?"
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
    }

    @Test
    fun `liteRtMessageSnapshot joins reflected text parts with word boundaries`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText("Hello"),
                    FakeLiteRtText("!"),
                    FakeLiteRtText("How"),
                    FakeLiteRtText("can"),
                    FakeLiteRtText("I"),
                    FakeLiteRtText("help"),
                    FakeLiteRtText("you"),
                    FakeLiteRtText("today"),
                    FakeLiteRtText("?")
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("Hello! How can I help you today?", snapshot.text)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay restores common compact English response`() {
        val repaired = repairLiteRtCompactTextForDisplay("Hello!HowcanIhelpyoutoday?")

        assertEquals("Hello! How can I help you today?", repaired)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay restores common compact Spanish response`() {
        val repaired = repairLiteRtCompactTextForDisplay("Hola!Comopuedoayudartehoy?")

        assertEquals("Hola! Como puedo ayudarte hoy?", repaired)
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay restores compact thinking labels`() {
        val repaired = repairLiteRtCompactTextForDisplay(
            "ThinkingProcess:1.Analyzetheinput:Theinputs\"Hello\".2.Determinetheintent:Thisisasimplegreeting."
        )

        assertTrue(repaired.contains("Thinking Process"))
        assertTrue(repaired.contains("Analyze the input"))
        assertTrue(repaired.contains("Determine the intent"))
        assertTrue(repaired.contains("This is a simple greeting"))
    }

    @Test
    fun `repairLiteRtCompactTextForDisplay preserves tool calls`() {
        val raw = """<tool_call>{"name":"web_search","arguments":{"query":"HowcanIhelp"}}</tool_call>"""

        assertEquals(raw, repairLiteRtCompactTextForDisplay(raw))
    }

    @Test
    fun `liteRtMessageSnapshot routes leaked thinking channel away from visible text`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("<| channel>ThinkingProcess:1.AnalyzeTheRequest: hidden"))
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertTrue(snapshot.thought.contains("ThinkingProcess"))
    }

    @Test
    fun `liteRtMessageSnapshot routes bare compact thinking away from visible text`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(
                    FakeLiteRtText(
                        "Thinking Process:1.Analyzetheinput:Theinputs\"Hello\".2.Determinetheintent:Thisisasimplegreeting."
                    )
                )
            )
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertTrue(snapshot.thought.contains("Analyzetheinput"))
    }

    @Test
    fun `liteRtMessageSnapshot strips duplicated rendered thinking even when channel exists`() {
        val message = FakeLiteRtMessage(
            contents = FakeLiteRtContents(
                listOf(FakeLiteRtText("Thinking Process:1.Analyze the input: hidden"))
            ),
            channels = mapOf("thought" to "actual thought channel")
        )

        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled = true)

        assertEquals("", snapshot.text)
        assertEquals("actual thought channel", snapshot.thought)
    }

    @Test
    fun `estimateLiteRtCompletionTokens counts compact output beyond one token`() {
        val tokens = estimateLiteRtCompletionTokens("Hello!HowcanIhelpyoutoday?")

        assertTrue(tokens > 1)
    }
}

private class FakeLiteRtMessage(
    private val contents: FakeLiteRtContents,
    private val channels: Map<String, String> = emptyMap(),
    private val renderedString: String = "FakeLiteRtMessage(contents=$contents)"
) {
    fun getContents(): FakeLiteRtContents = contents
    fun getChannels(): Map<String, String> = channels
    override fun toString(): String = renderedString
}

private class FakeLiteRtContents(private val contents: List<Any>) {
    fun getContents(): List<Any> = contents
    override fun toString(): String = "FakeLiteRtContents(contents=$contents)"
}

private class FakeLiteRtText(private val text: String) {
    fun getText(): String = text
    override fun toString(): String = "FakeLiteRtText(text=$text)"
}
