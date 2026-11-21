package com.example.llamadroid.service

import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatRequest
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaReasoningTest {
    private fun payload(thinking: Boolean, omit: Boolean, effort: String?) =
        buildLlamaServerChatRequestPayload(
            messages = emptyList(), tools = emptyList(), thinkingEnabled = thinking,
            requestOptions = LlamaServerRequestOptions(omitThinkingKwarg = omit, reasoningEffort = effort)
        )

    @Test fun `request json for every thinking and effort combination`() {
        for (omit in listOf(false, true)) for (thinking in listOf(true, false)) for (effort in listOf(null, "default", "high")) {
            val p = payload(thinking, omit, effort)
            val kwargs = p["chat_template_kwargs"] as? Map<*, *>
            if (omit) assertNull(kwargs) else assertEquals(thinking, kwargs?.get("enable_thinking"))
            when {
                !thinking -> assertEquals("none", p["reasoning_effort"])
                effort == "high" -> assertEquals("high", p["reasoning_effort"])
                else -> assertFalse(p.containsKey("reasoning_effort"))
            }
        }
    }

    @Test fun `old chats map the bool to on or off and new mode wins`() {
        assertEquals("on", LlamaReasoning.chatThinkingMode(emptyMap()))
        assertEquals("on", LlamaReasoning.chatThinkingMode(mapOf("enable_thinking" to true)))
        assertEquals("off", LlamaReasoning.chatThinkingMode(mapOf("enable_thinking" to false)))
        assertEquals("default", LlamaReasoning.chatThinkingMode(mapOf("enable_thinking" to true, "thinking_mode" to "default")))
        assertEquals("on", LlamaReasoning.chatThinkingMode(mapOf("thinking_mode" to "bogus")))
        assertNull(LlamaReasoning.chatEffort(mapOf("reasoning_effort" to "default")))
        assertEquals("xhigh", LlamaReasoning.chatEffort(mapOf("reasoning_effort" to "XHigh")))
    }

    @Test fun `plain chat request serializes effort only when set`() {
        val base = LlamaChatRequest(model = "m", messages = listOf(LlamaChatMessage("user", "hi")))
        assertFalse(Gson().toJson(base).contains("reasoning_effort"))
        assertTrue(Gson().toJson(base.copy(reasoning_effort = "low")).contains("\"reasoning_effort\":\"low\""))
    }

    @Test fun `server flags are omitted for auto and default`() {
        assertNull(LlamaReasoning.serverModeFlag("auto"))
        assertNull(LlamaReasoning.serverModeFlag("garbage"))
        assertEquals("off", LlamaReasoning.serverModeFlag("off"))
        assertNull(LlamaReasoning.serverEffortFlag("default"))
        assertEquals("max", LlamaReasoning.serverEffortFlag("max"))
    }

    private val caps = LlamaBinaryCapabilities(setOf("--reasoning", "--reasoning-effort"))
    private fun cmd(c: LlamaConfig, k: LlamaBinaryCapabilities? = caps) =
        ProcessController().getCommand("/bin/llama-server", c, k)

    @Test fun `cli emits reasoning flags only when set supported and not overridden`() {
        val base = LlamaConfig(modelPath = "/m/a.gguf")
        assertFalse(cmd(base).contains("--reasoning"))
        assertFalse(cmd(base.copy(reasoningMode = "auto", reasoningEffort = "default")).contains("--reasoning-effort"))
        val on = cmd(base.copy(reasoningMode = "off", reasoningEffort = "high"))
        assertEquals("off", on[on.indexOf("--reasoning") + 1])
        assertEquals("high", on[on.indexOf("--reasoning-effort") + 1])
        assertFalse(cmd(base.copy(reasoningMode = "on"), LlamaBinaryCapabilities(emptySet())).contains("--reasoning"))
        // The custom flag itself stays in the command; the app must not add a second one.
        val custom = cmd(base.copy(reasoningMode = "on", customFlags = "--reasoning off"))
        assertEquals(1, custom.count { it == "--reasoning" })
        assertEquals("off", custom[custom.indexOf("--reasoning") + 1])
    }

    @Test fun `launch profile defaults and json migration keep auto`() {
        val p = LlamaServerLaunchProfile()
        assertEquals("auto", p.reasoningMode)
        assertEquals("default", p.reasoningEffort)
        val cfg = p.copy(reasoningMode = "on", reasoningEffort = "low").toLlamaConfig()
        assertEquals("on", cfg.reasoningMode)
        assertEquals("low", cfg.reasoningEffort)
    }
}
