package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.db.AgentRuntimeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeEndpointModelDiscoveryTest {
    @Test
    fun `parses ordered Ollama names and removes duplicates`() {
        val names = AgentRuntimeEndpointModelDiscovery.parseModelNames(
            AgentRuntimeBackend.OLLAMA,
            """
                {"models":[{"name":"qwen3:8b"},{"name":"llama3"},{"name":"qwen3:8b"}]}
            """.trimIndent()
        )

        assertEquals(listOf("qwen3:8b", "llama3"), names)
    }

    @Test
    fun `parses OpenAI compatible ids and accepts models fallback`() {
        val dataNames = AgentRuntimeEndpointModelDiscovery.parseModelNames(
            AgentRuntimeBackend.LLAMA_SWAP,
            """{"data":[{"id":"model-a"},{"id":"model-b"}]}"""
        )
        val fallbackNames = AgentRuntimeEndpointModelDiscovery.parseModelNames(
            AgentRuntimeBackend.LLAMA_SERVER,
            """{"models":[{"name":"server-model"}]}"""
        )

        assertEquals(listOf("model-a", "model-b"), dataNames)
        assertEquals(listOf("server-model"), fallbackNames)
    }

    @Test
    fun `malformed payload fails instead of presenting stale models`() {
        val result = runCatching {
            AgentRuntimeEndpointModelDiscovery.parseModelNames(
                AgentRuntimeBackend.OLLAMA,
                "not-json"
            )
        }

        assertTrue(result.isFailure)
    }
}
