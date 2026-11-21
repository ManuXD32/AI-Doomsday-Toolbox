package com.example.llamadroid.ui.ai.llama.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The tools master switch must follow the user's flag even when a chat document keeps document search on. */
class ChatSettingsToolsSwitchTest {
    @Test
    fun toolsSwitchTogglesOffAndOnInChatWithDocumentKnowledgeBase() {
        val state = ChatSettingsState().apply {
            loadFromDefaults(null)
            chatDocumentKnowledgeBaseId = 42L
        }
        assumeTrue(state.serverAllowsTools && state.serverAllowsKnowledgeBases)

        state.toolsEnabled = false
        assertFalse(state.chatToolsEnabledByUser)
        assertTrue(state.chatDocumentForcesKnowledgeTools)
        assertTrue(state.effectiveKnowledgeBaseEnabled)

        state.toolsEnabled = true
        assertTrue(state.chatToolsEnabledByUser)
        state.dateTimeEnabled = true
        assertTrue(state.effectiveDateTimeEnabled == state.serverAllowsDateTime)
        state.dateTimeEnabled = false
        assertFalse(state.effectiveDateTimeEnabled)
    }
}
