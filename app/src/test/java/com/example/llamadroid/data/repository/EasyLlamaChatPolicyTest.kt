package com.example.llamadroid.data.repository

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.*
import org.junit.Test

class EasyLlamaChatPolicyTest {
    private val model = ModelEntity("model.gguf", "/model.gguf", 123, ModelType.LLM, "test", true)

    @Test fun `easy settings are isolated conservative and bounded by model context`() {
        val profile = easyLlamaProfile(model, 55000, null, 16)
        assertEquals(8192, profile.contextSize)
        assertEquals(4, profile.threads)
        assertEquals(1, profile.parallel)
        assertEquals("127.0.0.1", profile.host)
        assertEquals(55000, profile.serverPort)
        assertEquals(SettingsRepository.NATIVE_BINARY_CPU_AUTO, profile.nativeBinarySelection)
        assertEquals(600, profile.idleStopSeconds)
        assertFalse(profile.nativeToolsEnabled)
        assertFalse(profile.speculativeEnabled)
        assertEquals(2048, easyLlamaProfile(model, 55000, 2048, 2).contextSize)
        assertEquals(2, easyLlamaProfile(model, 55000, 32768, 2).threads)
    }

    @Test fun `context override beats the 8K baseline`() {
        assertEquals(16384, easyLlamaProfile(model, 55000, 4096, 4, 16384).contextSize)
        assertEquals(8192, easyLlamaProfile(model, 55000, null, 4, 0).contextSize)
    }

    @Test fun `easy chat tools allow only web search`() {
        val off = easyChatTools()
        assertFalse(off.hasEnabledTools())
        assertFalse(off.toolsEnabled)
        val on = easyChatTools(webSearch = true)
        assertTrue(on.toolsEnabled)
        assertTrue(on.webSearchEnabled)
        assertFalse(on.dateTimeEnabled)
        assertFalse(on.calculatorEnabled)
        assertFalse(on.fileToolsEnabled)
        assertFalse(on.customToolsEnabled)
        assertFalse(on.deepSeekDelegationEnabled)
        assertFalse(on.imageGenerationEnabled)
        assertFalse(on.knowledgeBaseEnabled)
        assertFalse(on.fetchUrlEnabled)
        assertFalse(on.deepResearchEnabled)
    }

    @Test fun `easy api params force tools off but keep unrelated seeded keys`() {
        val params = easyChatApiParams("""{"tools_enabled":true,"tool_date_time_enabled":true,"audio_input_mode":"whisper"}""")
        val parsed = com.example.llamadroid.service.NativeChatToolConfig.fromApiParams(params)
        assertFalse(parsed.toolsEnabled)
        assertFalse(parsed.dateTimeEnabled)
        assertTrue(params.contains("audio_input_mode"))
        assertTrue(com.example.llamadroid.service.NativeChatToolConfig.fromApiParams(easyChatApiParams(null, true)).webSearchEnabled)
    }

    @Test fun `new chats disable tools but every user-selected tool passes server permissions`() {
        val disabled = easyChatDisabledTools()
        val allowed = easyChatAllowedTools()
        assertFalse(disabled.toolsEnabled)
        assertFalse(disabled.dateTimeEnabled)
        assertFalse(disabled.calculatorEnabled)
        assertFalse(disabled.effectiveWithServerDefaults(allowed).hasEnabledTools())
        val configured = allowed.effectiveWithServerDefaults(allowed)
        assertTrue(configured.webSearchEnabled)
        assertTrue(configured.kiwixSearchEnabled)
        assertTrue(configured.deepResearchEnabled)
        assertTrue(configured.fetchUrlEnabled)
        assertTrue(configured.fileToolsEnabled)
        assertTrue(configured.customToolsEnabled)
        assertTrue(configured.knowledgeBaseEnabled)
        assertTrue(configured.imageGenerationEnabled)
        assertTrue(configured.noteToolsEnabled)
        assertTrue(configured.todoToolsEnabled)
        assertTrue(configured.calendarToolsEnabled)
        assertTrue(configured.alarmToolsEnabled)
    }
}
