package com.example.llamadroid.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPreviewPromptSupportTest {
    @Test
    fun `tight preview projection preserves complete controls and recovery hint`() {
        val controls = JSONArray().apply {
            repeat(16) { index ->
                put(JSONObject().put("label", "Control $index " + "x".repeat(70))
                    .put("x", index * 20).put("y", 120).put("enabled", true))
            }
        }
        val observation = JSONObject().put("url", "http://127.0.0.1:1234/")
            .put("controls", controls).put("body_text", "large page ".repeat(200))
        val hint = "\nnext_hint: Scroll and observe again; keep action-id-opaque intact."
        val envelope = "status: ok\ntool: observe_preview\nsummary: observed\nimportant_output:\n" + observation + hint
        val projected = requireNotNull(projectAgentPreviewPromptContent(envelope, 850))
        val json = JSONObject(projected.substringAfter("important_output:\n").substringBefore("\nnext_hint:"))
        assertTrue(projected.length <= 850)
        assertTrue(projected.endsWith(hint))
        assertTrue(json.getBoolean("dom_truncated"))
        val retained = json.getJSONArray("controls")
        assertTrue(retained.length() in 1..15)
        repeat(retained.length()) { index ->
            assertEquals(controls.getJSONObject(index).toString(), retained.getJSONObject(index).toString())
        }
    }
}
