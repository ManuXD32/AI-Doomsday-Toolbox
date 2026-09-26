package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPreviewBridgeTest {
    @Test
    fun `text observation remains bounded after JSON escaping and excludes password values`() {
        val observation = AgentPreviewObservation(
            url = "\u0001".repeat(1000),
            viewportWidth = 320, viewportHeight = 240, progress = 100,
            title = "\u0001".repeat(1000), screenshotPath = "\u0001".repeat(1000),
            screenshotBytes = 0,
            bodyText = "\u0001".repeat(2000),
            controls = List(20) { index -> AgentPreviewControl(
                type = if (index == 0) "password" else "button",
                label = "\u0001".repeat(80), value = "secret", x = 10, y = 20, enabled = true
            ) }
        )
        val serialized = observation.toJson()
        assertTrue(serialized.length <= 3000)
        val payload = JSONObject(serialized)
        assertTrue(payload.getBoolean("dom_truncated"))
        val controls = payload.getJSONArray("controls")
        assertTrue(controls.length() <= 16)
        if (controls.length() > 0) assertEquals("", controls.getJSONObject(0).getString("value"))
    }

    @Test
    fun `text only observation never claims a captured screenshot`() {
        val payload = JSONObject(AgentPreviewObservation(
            url = "http://127.0.0.1:1234/", viewportWidth = 320, viewportHeight = 240,
            progress = 100, title = "Fixture", screenshotPath = null, screenshotBytes = 0,
            bodyText = "Ready", controls = listOf(AgentPreviewControl("button", "Start", "", 100, 120, true))
        ).toJson())
        assertEquals("", payload.getString("screenshot_path"))
        assertEquals(0L, payload.getLong("screenshot_bytes"))
        assertEquals("Start", payload.getJSONArray("controls").getJSONObject(0).getString("label"))
        assertTrue(payload.getString("visual_context").startsWith("No screenshot"))
    }
}
