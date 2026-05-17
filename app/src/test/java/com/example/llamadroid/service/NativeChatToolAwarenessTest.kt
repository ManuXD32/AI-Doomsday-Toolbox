package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatToolAwarenessTest {
    @Test
    fun `note tools add reminder to list and read notes before asking for ids`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("list_notes"))
        assertTrue(messages.first().content.contains("read_note"))
        assertTrue(messages.first().content.contains("create_note"))
        assertTrue(messages.first().content.contains("update_note"))
        assertTrue(messages.first().content.contains("replace_note_text"))
        assertTrue(messages.first().content.contains("recover previous research"))
        assertTrue(messages.first().content.contains("Do not ask the user to provide note IDs"))
        assertTrue(messages.first().content.contains("todo-list tools"))
    }

    @Test
    fun `image and note tool reminders can be combined`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                imageGenerationEnabled = true
            )
        )

        assertEquals(2, messages.size)
        assertTrue(messages.any { it.content.contains("list_notes") })
        assertTrue(messages.any { it.content.contains("generate_image") })
    }

    @Test
    fun `note-only reminders do not mention todo mutation tools`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = false
            )
        )

        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("create_note"))
        assertFalse(messages.first().content.contains("todo-list tools"))
    }

    @Test
    fun `organizer tools remind model to list and read ids before editing`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("list_calendar_events"))
        assertTrue(messages.first().content.contains("read_calendar_event"))
        assertTrue(messages.first().content.contains("list_alarms"))
        assertTrue(messages.first().content.contains("read_alarm"))
        assertTrue(messages.first().content.contains("Do not ask the user for event or alarm IDs"))
    }

    @Test
    fun `web tools remind model to navigate pages before summarizing site sections`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                fetchUrlEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("web_search"))
        assertTrue(messages.first().content.contains("search_page"))
        assertTrue(messages.first().content.contains("fetch_url"))
        assertTrue(messages.first().content.contains("latest commits"))
    }

    @Test
    fun `deep research reminders mention generated reusable knowledge base`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                deepResearchEnabled = true,
                knowledgeBaseEnabled = true
            )
        )

        assertTrue(messages.any { it.content.contains("deep_research") })
        assertTrue(messages.any { it.content.contains("normal visible knowledge base") })
        assertTrue(messages.any { it.content.contains("kb_search") })
    }

    @Test
    fun `fetch-only web reminders do not mention web search`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = false,
                fetchUrlEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertFalse(messages.first().content.contains("web_search"))
        assertTrue(messages.first().content.contains("search_page"))
        assertTrue(messages.first().content.contains("fetch_url"))
    }

    @Test
    fun `assistant tts setting does not expose a model visible tool`() {
        val config = NativeChatToolConfig(
            toolsEnabled = true,
            dateTimeEnabled = false,
            calculatorEnabled = false,
            assistantTtsEnabled = true
        )
        val runtime = NativeChatToolRuntime()

        assertFalse(config.hasEnabledTools())
        assertTrue(runtime.availableTools(config).none { it.name.contains("voice", ignoreCase = true) })
        assertTrue(runtime.availableTools(config).none { it.name.contains("tts", ignoreCase = true) })
        assertTrue(nativeChatToolAwarenessMessages(config).isEmpty())
    }

    @Test
    fun `knowledge reminders tell model to keep kb citation links`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                knowledgeBaseEnabled = true,
                chatDocumentKnowledgeBaseId = 7L
            )
        )

        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("[AL.pdf chunk 9](kb://chunk/123)"))
        assertTrue(messages.first().content.contains("not bare labels"))
    }

    @Test
    fun `source citation extraction accepts kb chunk links`() {
        val citations = extractNativeChatSourceCitations(
            "Citation: [AL.pdf chunk 9](kb://chunk/123)"
        )

        assertEquals(1, citations.size)
        assertEquals("AL.pdf chunk 9", citations.first().label)
        assertEquals("kb://chunk/123", citations.first().url)
        assertEquals("[AL.pdf chunk 9](kb://chunk/123)", citations.first().markdown)
    }

    @Test
    fun `source citation fallback links bare kb chunk labels`() {
        val content = "Las reacciones IgE son frecuentes [AL.pdf chunk 9]."
        val updated = applyNativeChatSourceCitationFallback(
            content = content,
            citations = listOf(
                NativeChatSourceCitation(
                    label = "AL.pdf chunk 9",
                    url = "kb://chunk/123",
                    markdown = "[AL.pdf chunk 9](kb://chunk/123)"
                )
            )
        )

        assertEquals(
            "Las reacciones IgE son frecuentes [AL.pdf chunk 9](kb://chunk/123).",
            updated
        )
    }

    @Test
    fun `calendar-only organizer reminders do not mention alarm tools`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = false
            )
        )

        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("list_calendar_events"))
        assertFalse(messages.first().content.contains("list_alarms"))
        assertFalse(messages.first().content.contains("alarm IDs"))
    }

    @Test
    fun `disabled tools do not add reminder messages`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = false,
                noteToolsEnabled = true,
                imageGenerationEnabled = true
            )
        )

        assertTrue(messages.isEmpty())
    }
}
