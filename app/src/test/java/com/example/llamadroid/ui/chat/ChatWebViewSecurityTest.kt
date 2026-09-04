package com.example.llamadroid.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWebViewSecurityTest {
    private val origin = llamaChatWebViewUrl(8080)

    @Test
    fun `allows only the configured loopback origin`() {
        assertTrue(isAllowedChatWebViewUrl("http://127.0.0.1:8080/chat/thread-1", origin))
        assertTrue(isAllowedChatWebViewUrl("http://127.0.0.1:8080/?q=test#reply", origin))

        assertFalse(isAllowedChatWebViewUrl("https://127.0.0.1:8080/", origin))
        assertFalse(isAllowedChatWebViewUrl("http://localhost:8080/", origin))
        assertFalse(isAllowedChatWebViewUrl("http://127.0.0.1:8081/", origin))
        assertFalse(isAllowedChatWebViewUrl("http://user@127.0.0.1:8080/", origin))
        assertFalse(isAllowedChatWebViewUrl("https://example.com/", origin))
        assertFalse(isAllowedChatWebViewUrl(null, origin))
    }
}
