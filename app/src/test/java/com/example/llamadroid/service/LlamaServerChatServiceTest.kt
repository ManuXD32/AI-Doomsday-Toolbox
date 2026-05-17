package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerChatServiceTest {
    @Test
    fun `buildLlamaServerChatRequestPayload disables reasoning when thinking is off`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(role = "system", content = "system"),
                OllamaService.ChatMessage(role = "user", content = "hello")
            ),
            tools = emptyList(),
            thinkingEnabled = false,
            maxTokens = 2048
        )

        assertEquals(true, payload["stream"])
        assertEquals("local-model", payload["model"])
        assertEquals(2048, payload["max_tokens"])
        assertEquals(true, ((payload["stream_options"] as Map<*, *>)["include_usage"]))
        assertEquals(false, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
        assertEquals("none", payload["reasoning_effort"])
        assertEquals("none", ((payload["reasoning"] as Map<*, *>)["effort"]))
    }

    @Test
    fun `buildLlamaServerChatRequestPayload keeps reasoning enabled when requested`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            thinkingEnabled = true,
            maxTokens = 1024
        )

        assertEquals(true, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
        assertFalse(payload.containsKey("reasoning_effort"))
        assertFalse(payload.containsKey("reasoning"))
    }

    @Test
    fun `buildLlamaServerChatRequestPayload drops assistant prefill when thinking is enabled`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(role = "system", content = "system"),
                OllamaService.ChatMessage(role = "user", content = "hello"),
                OllamaService.ChatMessage(role = "assistant", content = "partial assistant prefill")
            ),
            tools = emptyList(),
            thinkingEnabled = true,
            maxTokens = 1024
        )

        val messages = payload["messages"] as List<*>
        assertEquals(2, messages.size)
        assertEquals("user", (messages.last() as Map<*, *>)["role"])
    }

    @Test
    fun `buildLlamaServerChatRequestPayload keeps assistant prefill when thinking is disabled`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(role = "system", content = "system"),
                OllamaService.ChatMessage(role = "user", content = "hello"),
                OllamaService.ChatMessage(role = "assistant", content = "manual prefill")
            ),
            tools = emptyList(),
            thinkingEnabled = false,
            maxTokens = 1024
        )

        val messages = payload["messages"] as List<*>
        assertEquals(3, messages.size)
        assertEquals("assistant", (messages.last() as Map<*, *>)["role"])
    }

    @Test
    fun `buildLlamaServerChatRequestPayload uses provided model label`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            model = "qwen3-coder-30b",
            thinkingEnabled = true,
            maxTokens = 1024
        )

        assertEquals("qwen3-coder-30b", payload["model"])
    }

    @Test
    fun `buildLlamaServerChatRequestPayload moves transient system reminders to the beginning`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(role = "system", content = "Base prompt"),
                OllamaService.ChatMessage(role = "user", content = "hello"),
                OllamaService.ChatMessage(role = "assistant", content = "hi"),
                OllamaService.ChatMessage(role = "system", content = "Tool reminder"),
                OllamaService.ChatMessage(role = "user", content = "use the note tools")
            ),
            tools = emptyList(),
            thinkingEnabled = false,
            maxTokens = 1024
        )

        val messages = payload["messages"] as List<*>
        assertEquals(4, messages.size)
        val first = messages.first() as Map<*, *>
        assertEquals("system", first["role"])
        assertEquals("Base prompt\n\nTool reminder", first["content"])
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            messages.map { (it as Map<*, *>)["role"] }
        )
    }

    @Test
    fun `buildLlamaServerChatRequestPayload includes tools tool result and sampling params`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(OllamaService.ToolCall("web_search", mapOf("query" to "llama.cpp"), "call_1"))
                ),
                OllamaService.ChatMessage(
                    role = "tool",
                    content = "result",
                    toolCallId = "call_1"
                )
            ),
            tools = listOf(
                AgentTool(
                    name = "web_search",
                    description = "Search",
                    parameters = mapOf("query" to "Query"),
                    requiredParams = listOf("query")
                )
            ),
            thinkingEnabled = true,
            maxTokens = 4096,
            samplingParams = LlamaServerSamplingParams(
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                minP = 0.05f,
                repeatPenalty = 1.1f
            )
        )

        assertEquals(0.7f, payload["temperature"])
        assertEquals(0.9f, payload["top_p"])
        assertEquals(40, payload["top_k"])
        assertEquals(0.05f, payload["min_p"])
        assertEquals(1.1f, payload["repeat_penalty"])
        assertEquals("auto", payload["tool_choice"])

        val messages = payload["messages"] as List<*>
        val assistant = messages[0] as Map<*, *>
        val toolCalls = assistant["tool_calls"] as List<*>
        val toolCall = toolCalls.first() as Map<*, *>
        assertEquals("call_1", toolCall["id"])

        val tool = messages[1] as Map<*, *>
        assertEquals("tool", tool["role"])
        assertEquals("call_1", tool["tool_call_id"])
    }

    @Test
    fun `parseLlamaServerUsage extracts token counts from usage block`() {
        val usage = parseLlamaServerUsage(
            JSONObject(
                """{"usage":{"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}}"""
            )
        )

        assertNotNull(usage)
        assertEquals(123, usage?.promptTokens)
        assertEquals(45, usage?.completionTokens)
        assertEquals(168, usage?.totalTokens)
        assertEquals("llama-server", usage?.backend)
    }
}
