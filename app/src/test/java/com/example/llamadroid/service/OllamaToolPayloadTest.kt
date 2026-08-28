package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OllamaToolPayloadTest {
    @Test
    fun `buildChatRequestJson includes tool call ids and tool result ids`() {
        val payload = OllamaService.buildChatRequestJson(
            model = "qwen",
            messages = listOf(
                OllamaService.ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(OllamaService.ToolCall("calculator", mapOf("expression" to "2+2"), "call_1"))
                ),
                OllamaService.ChatMessage(
                    role = "tool",
                    content = "4",
                    toolCallId = "call_1"
                )
            ),
            tools = listOf(
                AgentTool(
                    name = "calculator",
                    description = "Calculate",
                    parameters = mapOf("expression" to "Expression"),
                    requiredParams = listOf("expression")
                )
            ),
            thinkingEnabled = true,
            useMmap = false,
            numThreads = 4,
            numCtx = 4096,
            maxOutputTokens = 8096
        )

        val messages = payload.getJSONArray("messages")
        assertEquals("30m", payload.getString("keep_alive"))
        val assistant = messages.getJSONObject(0)
        val toolCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", toolCall.getString("id"))
        assertEquals("function", toolCall.getString("type"))

        val tool = messages.getJSONObject(1)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals(8096, payload.getJSONObject("options").getInt("num_predict"))
        assertNotNull(payload.getJSONArray("tools"))
    }

    @Test
    fun `raw tool arguments and stable fallback id are deterministic`() {
        val call = OllamaService.ToolCall(
            name = "edit",
            arguments = mapOf("b" to "2", "a" to "1"),
            rawArgumentsJson = """{"b":"2","a":"1"}"""
        )
        val first = OllamaService.buildChatRequestJson(
            model = "qwen",
            messages = listOf(OllamaService.ChatMessage("assistant", "", toolCalls = listOf(call))),
            tools = emptyList(),
            thinkingEnabled = false,
            useMmap = false,
            numThreads = 4,
            numCtx = 4096
        )
        val second = OllamaService.buildChatRequestJson(
            model = "qwen",
            messages = listOf(OllamaService.ChatMessage("assistant", "", toolCalls = listOf(call))),
            tools = emptyList(),
            thinkingEnabled = false,
            useMmap = false,
            numThreads = 4,
            numCtx = 4096
        )
        assertEquals(first.toString(), second.toString())
        val serialized = first.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("2", serialized.getJSONObject("function").getJSONObject("arguments").getString("b"))
    }
}
