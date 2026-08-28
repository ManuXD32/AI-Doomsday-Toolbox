package com.example.llamadroid.service

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerChatServiceTest {
    @Test
    fun `SSE failure classification never labels cancellation as malformed JSON`() {
        assertEquals(
            SseProcessingFailureKind.CANCELLATION,
            classifySseProcessingFailure(CancellationException("cancelled"))
        )
        assertEquals(
            SseProcessingFailureKind.MALFORMED_JSON,
            classifySseProcessingFailure(org.json.JSONException("bad chunk"))
        )
        assertEquals(
            SseProcessingFailureKind.PROCESSING,
            classifySseProcessingFailure(IllegalStateException("callback failed"))
        )
    }

    @Test
    fun `generationElapsedMs starts from first token when present`() {
        assertEquals(
            1_500L,
            generationElapsedMs(
                requestStartedAtMs = 1_000L,
                firstTokenReceivedAtMs = 3_500L,
                nowMs = 5_000L
            )
        )
    }

    @Test
    fun `generationElapsedMs falls back to zero before first token`() {
        assertEquals(
            0L,
            generationElapsedMs(
                requestStartedAtMs = 1_000L,
                firstTokenReceivedAtMs = null,
                nowMs = 5_000L
            )
        )
    }

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
    fun `buildLlamaServerChatRequestPayload omits max tokens when unset`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            thinkingEnabled = true,
            maxTokens = null
        )

        assertFalse(payload.containsKey("max_tokens"))
    }

    @Test
    fun `buildLlamaServerChatRequestPayload includes max tokens when provided`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            thinkingEnabled = true,
            maxTokens = 777
        )

        assertEquals(777, payload["max_tokens"])
    }

    @Test
    fun `buildLlamaServerChatRequestPayload preserves nonempty assistant history when thinking is enabled`() {
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
        assertEquals(3, messages.size)
        assertEquals("assistant", (messages.last() as Map<*, *>)["role"])
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
    fun `buildLlamaServerChatRequestPayload keeps runtime system reminders in place`() {
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
        assertEquals(5, messages.size)
        val first = messages.first() as Map<*, *>
        assertEquals("system", first["role"])
        assertEquals("Base prompt", first["content"])
        assertEquals(
            listOf("system", "user", "assistant", "user", "user"),
            messages.map { (it as Map<*, *>)["role"] }
        )
        assertEquals(
            "[Runtime context]\nTool reminder",
            (messages[3] as Map<*, *>)["content"]
        )
    }

    @Test
    fun `request carries cache prompt and optional slot id`() {
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            thinkingEnabled = false,
            maxTokens = 8096,
            requestOptions = LlamaServerRequestOptions(cachePrompt = true, slotId = 2)
        )
        assertEquals(true, payload["cache_prompt"])
        assertEquals(2, payload["id_slot"])
        assertEquals(true, payload["return_progress"])
        assertEquals(2, payload["sse_ping_interval"])
    }

    @Test
    fun `prompt progress is parsed and clamped`() {
        val progress = parseLlamaPromptProcessingProgress(
            JSONObject(
                """{"prompt_progress":{"total":4096,"cache":2048,"processed":5000,"time_ms":1234}}"""
            )
        )

        assertNotNull(progress)
        assertEquals(4096, progress?.total)
        assertEquals(2048, progress?.cached)
        assertEquals(4096, progress?.processed)
        assertEquals(1f, progress?.fraction)
    }

    @Test
    fun `missing prompt progress is ignored`() {
        assertEquals(null, parseLlamaPromptProcessingProgress(JSONObject("""{"choices":[]}""")))
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
