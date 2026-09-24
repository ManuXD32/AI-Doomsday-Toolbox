package com.example.llamadroid.harness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessLiveCommandOutputTest {
    @Test
    fun `accepts bounded authenticated stdout with exact offset`() {
        val event = parseHarnessLiveCommandOutput(
            "session-1",
            JSONObject().put("callId", "call-1").put("stream", "stdout")
                .put("offset", 4).put("text", "ready\n"),
        )
        assertEquals("session-1", event.sessionId)
        assertEquals("call-1", event.callId)
        assertEquals(4L, event.offset)
        assertEquals("ready\n", event.text)
    }

    @Test
    fun `rejects missing scope and oversized multibyte output`() {
        val args = JSONObject().put("callId", "call-1").put("stream", "stderr")
            .put("offset", 0).put("text", "ñ".repeat(1_025))
        assertThrows(IllegalArgumentException::class.java) {
            parseHarnessLiveCommandOutput("session-1", args)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseHarnessLiveCommandOutput(null, JSONObject(args.toString()).put("text", "short"))
        }
    }

    @Test
    fun `rejects a stream outside stdout and stderr`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHarnessLiveCommandOutput(
                "session-1",
                JSONObject().put("callId", "call-1").put("stream", "combined")
                    .put("offset", 0).put("text", "text"),
            )
        }
    }
}
