package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.ui.ai.llama.LlamaSamplerField
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sampler Auto: fresh state, JSON omission, old explicit values, reset. */
class ChatSettingsSamplerStateTest {
    private fun json(state: ChatSettingsState) = JSONObject(state.buildApiParamsJson())

    @Test
    fun freshStateIsAllAutoAndOmitsSamplerKeys() {
        val state = ChatSettingsState()
        assertTrue(LlamaSamplerField.entries.all { state.isSamplerAuto(it) })
        val obj = json(state)
        LlamaSamplerField.entries.forEach { assertFalse(it.key, obj.has(it.key)) }
        assertEquals("Auto · Auto · Auto", state.samplerSummary("Auto"))
    }

    @Test
    fun oldJsonWithExplicitValuesRoundTrips() {
        val old = """{"temperature":0.7,"top_p":0.9,"top_k":50,"min_p":0.05,"repeat_penalty":1.1}"""
        val state = ChatSettingsState().apply { loadFromDefaults(old) }
        assertEquals(0.7f, state.samplerValues[LlamaSamplerField.TEMPERATURE])
        assertEquals("50", state.samplerTexts[LlamaSamplerField.TOP_K])
        assertEquals("0.7 · 0.9 · 50", state.samplerSummary("Auto"))
        val obj = json(state)
        assertEquals(50, obj.getInt("top_k"))
        assertEquals(0.7, obj.getDouble("temperature"), 1e-6)
        assertEquals(1.1, obj.getDouble("repeat_penalty"), 1e-6)
        assertFalse(obj.has("dry_base"))
        assertFalse(obj.has("mirostat"))
    }

    @Test
    fun absentKeysLoadAsAutoAndExtrasAreTyped() {
        val state = ChatSettingsState().apply {
            loadFromDefaults("""{"temperature":0.5,"mirostat":2,"dry_allowed_length":3,"dry_base":1.5}""")
        }
        assertTrue(state.isSamplerAuto(LlamaSamplerField.TOP_P))
        val obj = json(state)
        assertFalse(obj.has("top_p"))
        assertEquals(2, obj.getInt("mirostat"))
        assertEquals(3, obj.getInt("dry_allowed_length"))
        assertEquals(1.5, obj.getDouble("dry_base"), 1e-6)
    }

    @Test
    fun togglingAutoOffCommitsStartValueAndOnOmitsAgain() {
        val state = ChatSettingsState()
        state.setSamplerAuto(LlamaSamplerField.TEMPERATURE, auto = false, liteRt = false)
        assertEquals(0.8, json(state).getDouble("temperature"), 1e-6)
        state.setSamplerAuto(LlamaSamplerField.TEMPERATURE, auto = true, liteRt = false)
        assertFalse(json(state).has("temperature"))
    }

    @Test
    fun resetReturnsEveryFieldToAuto() {
        val state = ChatSettingsState().apply {
            loadFromDefaults("""{"temperature":0.5,"top_k":10,"mirostat":1,"xtc_probability":0.3}""")
        }
        state.resetSamplersToAuto()
        assertTrue(LlamaSamplerField.entries.all { state.isSamplerAuto(it) })
        assertFalse(json(state).has("temperature"))
        assertFalse(json(state).has("xtc_probability"))
    }

    @Test
    fun liteRtOnlyEmitsTheThreeBasicsCoerced() {
        val state = ChatSettingsState().apply {
            loadFromDefaults("""{"temperature":1.5,"top_k":3,"min_p":0.1,"mirostat":1}""")
            liteRtOverride = true
        }
        val obj = json(state)
        assertEquals(1.0, obj.getDouble("temperature"), 1e-6)
        assertEquals(5, obj.getInt("top_k"))
        assertFalse(obj.has("top_p"))
        assertFalse(obj.has("min_p"))
        assertFalse(obj.has("mirostat"))
    }
}
