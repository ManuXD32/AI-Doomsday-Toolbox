package com.example.llamadroid.service.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionStreamingLogicTest {
    @Test
    fun `delta is parsed from data lines only`() {
        val line = """data: {"choices":[{"delta":{"content":"Hel"}}]}"""
        assertEquals("Hel", ImageFallbackLogic.parseStreamDelta(line))
        assertNull(ImageFallbackLogic.parseStreamDelta("data: [DONE]"))
        assertNull(ImageFallbackLogic.parseStreamDelta(""))
        assertNull(ImageFallbackLogic.parseStreamDelta(": keep-alive"))
        assertNull(ImageFallbackLogic.parseStreamDelta("data: {broken"))
        assertNull(ImageFallbackLogic.parseStreamDelta("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}"""))
        assertNull(ImageFallbackLogic.parseStreamDelta("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertNull(ImageFallbackLogic.parseStreamDelta("""data: {"choices":[]}"""))
    }

    @Test
    fun `streamed text drops reasoning and blanks`() {
        assertEquals("a cat", ImageFallbackLogic.finishStreamed("<think>x</think> a cat "))
        assertNull(ImageFallbackLogic.finishStreamed("   "))
    }

    @Test
    fun `status tail is one line and truncated from the front`() {
        assertEquals("a b c", ImageFallbackLogic.statusTail("a\n b\t c "))
        val tail = ImageFallbackLogic.statusTail("x".repeat(200))
        assertTrue(tail.startsWith("…"))
        assertEquals(ImageFallbackLogic.STATUS_TAIL_CHARS + 1, tail.length)
    }

    @Test
    fun `throttle passes the first call then waits`() {
        var now = 1000L
        val throttle = VisionStatusThrottle(300L) { now }
        assertTrue(throttle.shouldEmit())
        now += 100
        assertFalse(throttle.shouldEmit())
        now += 200
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `stream flag lands in the request body`() {
        assertTrue(JSONObject(ImageFallbackLogic.buildRequestBody("data:image/png;base64,AA", false, stream = true)).getBoolean("stream"))
        assertFalse(JSONObject(ImageFallbackLogic.buildRequestBody("data:image/png;base64,AA", false)).getBoolean("stream"))
    }
}
