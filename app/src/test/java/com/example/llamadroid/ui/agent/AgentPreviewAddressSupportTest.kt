package com.example.llamadroid.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPreviewAddressSupportTest {
    @Test
    fun `normalizes loopback LAN and HTTPS preview addresses`() {
        assertEquals("http://127.0.0.1:8080/", normalizeAgentPreviewAddress(" http://127.0.0.1:8080 "))
        assertEquals(
            "http://192.168.18.57:9000/pomodoro?theme=dark",
            normalizeAgentPreviewAddress("http://192.168.18.57:9000/pomodoro?theme=dark")
        )
        assertEquals("https://example.com/app/", normalizeAgentPreviewAddress("HTTPS://Example.COM/app/"))
    }

    @Test
    fun `rejects credentials fragments non web schemes and malformed ports`() {
        listOf(
            "file:///data/data/private",
            "javascript:alert(1)",
            "http://user:secret@example.com/",
            "http://example.com/#fragment",
            "http://example.com:0/",
            "http://example.com:99999/"
        ).forEach { assertNull(it, normalizeAgentPreviewAddress(it)) }
    }

    @Test
    fun `saved override wins while automatic mode follows the run`() {
        assertEquals(
            "https://preview.example/app",
            resolveAgentPreviewAddress("http://127.0.0.1:4111/", "https://preview.example/app")
        )
        assertEquals(
            "http://127.0.0.1:4111/",
            resolveAgentPreviewAddress("http://127.0.0.1:4111/", null)
        )
    }
}
