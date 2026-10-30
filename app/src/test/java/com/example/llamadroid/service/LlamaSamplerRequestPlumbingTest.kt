package com.example.llamadroid.service

import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatRequest
import com.example.llamadroid.service.chat.LlamaSamplerKeys
import com.example.llamadroid.service.chat.withExtendedSamplers
import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaSamplerRequestPlumbingTest {
    private fun request() = LlamaChatRequest(
        model = "m",
        messages = listOf(LlamaChatMessage("user", "hi"))
    )

    @Test
    fun `fromParams types extended keys and ignores absent ones`() {
        val params: Map<String, Any> = mapOf(
            "mirostat" to 2.0, "mirostat_tau" to 5, "dry_allowed_length" to 3f, "typical_p" to 0.9
        )
        val ext = LlamaServerSamplingParams.fromParams(params).extended
        assertEquals(setOf("mirostat", "mirostat_tau", "dry_allowed_length", "typical_p"), ext.keys)
        assertTrue(ext["mirostat"] is Int)
        assertTrue(ext["dry_allowed_length"] is Int)
        assertTrue(ext["mirostat_tau"] is Float)
        assertTrue(ext["typical_p"] is Float)
    }

    @Test
    fun `payload omits Auto and writes extended entries`() {
        val auto = buildLlamaServerChatRequestPayload(
            messages = emptyList(), tools = emptyList(), thinkingEnabled = false,
            samplingParams = LlamaServerSamplingParams.fromParams(emptyMap())
        )
        LlamaSamplerKeys.EXTENDED_KEYS.forEach { assertFalse(it, auto.containsKey(it)) }
        assertFalse(auto.containsKey("temperature"))

        val set = buildLlamaServerChatRequestPayload(
            messages = emptyList(), tools = emptyList(), thinkingEnabled = false,
            samplingParams = LlamaServerSamplingParams.fromParams(mapOf("top_n_sigma" to 1.5, "repeat_last_n" to 64))
        )
        assertEquals(1.5f, set["top_n_sigma"])
        assertEquals(64, set["repeat_last_n"])
    }

    @Test
    fun `request json omits null extended fields and keeps set ones`() {
        val gson = Gson()
        val auto = JSONObject(gson.toJson(request().withExtendedSamplers(emptyMap())))
        LlamaSamplerKeys.EXTENDED_KEYS.forEach { assertFalse(it, auto.has(it)) }

        val set = JSONObject(gson.toJson(request().withExtendedSamplers(mapOf("mirostat" to 1, "xtc_probability" to 0.5))))
        assertEquals(1, set.getInt("mirostat"))
        assertEquals(0.5, set.getDouble("xtc_probability"), 1e-6)
        assertFalse(set.has("dry_base"))
    }
}
