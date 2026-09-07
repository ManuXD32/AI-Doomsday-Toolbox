package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.clearEndpointConfigReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeEndpointConfigTest {
    @Test
    fun `validation normalizes a reusable endpoint without changing its model`() {
        val config = AgentRuntimeEndpointConfig(
            id = 4L,
            name = "  Studio Ollama  ",
            backend = "OLLAMA",
            baseUrl = " https://studio.example:11434/// ",
            defaultModel = "  qwen3:8b  "
        ).validate()

        assertEquals("Studio Ollama", config.name)
        assertEquals(AgentRuntimeBackend.OLLAMA.id, config.backend)
        assertEquals("https://studio.example:11434", config.baseUrl)
        assertEquals("qwen3:8b", config.defaultModel)
    }

    @Test
    fun `validation upgrades a legacy scheme-less endpoint to http`() {
        val config = AgentRuntimeEndpointConfig(
            name = "Local llama",
            backend = AgentRuntimeBackend.LLAMA_SERVER.id,
            baseUrl = "127.0.0.1:8084/v1"
        ).validate()

        assertEquals("http://127.0.0.1:8084/v1", config.baseUrl)
    }

    @Test
    fun `validation rejects unsupported backend and non HTTP URL`() {
        val unsupported = runCatching {
            AgentRuntimeEndpointConfig(
                name = "LiteRT",
                backend = AgentRuntimeBackend.LITERT.id,
                baseUrl = "https://device.example"
            ).validate()
        }
        val invalidUrl = runCatching {
            AgentRuntimeEndpointConfig(
                name = "Local",
                backend = AgentRuntimeBackend.OLLAMA.id,
                baseUrl = "/tmp/ollama"
            ).validate()
        }

        assertTrue(unsupported.isFailure)
        assertTrue(invalidUrl.isFailure)
    }

    @Test
    fun `deleting endpoint reference clears model but preserves managed server`() {
        val profiles = listOf(
            AgentRuntimeProfile(
                agentKey = "CODER",
                backend = AgentRuntimeBackend.OLLAMA.id,
                model = "qwen3:8b",
                endpointConfigId = 7L,
                managedLlamaServerId = 12L
            ),
            AgentRuntimeProfile(
                agentKey = "REVIEWER",
                backend = AgentRuntimeBackend.OLLAMA.id,
                model = "other",
                endpointConfigId = 8L
            )
        )

        val repaired = profiles.clearEndpointConfigReference(7L)
        assertNull(repaired.first().endpointConfigId)
        assertNull(repaired.first().model)
        assertEquals(12L, repaired.first().managedLlamaServerId)
        assertEquals(profiles[1], repaired[1])
    }

    @Test
    fun `named endpoint makes remote dispatch ready and carries endpoint snapshot`() = kotlinx.coroutines.runBlocking {
        val profile = AgentRuntimeProfile(
            agentKey = "RESEARCHER",
            backend = AgentRuntimeBackend.OLLAMA.id,
            model = "research-model",
            endpointConfigId = 19L
        )
        val endpoint = AgentRuntimeEndpointConfig(
            id = 19L,
            name = "Research device",
            backend = AgentRuntimeBackend.OLLAMA.id,
            baseUrl = "http://192.0.2.19:11434"
        )

        val result = AgentRuntimeDispatchResolver.resolve(profile, null, endpointConfig = endpoint)
        assertTrue(result is AgentRuntimeDispatch.Ready)
        val ready = result as AgentRuntimeDispatch.Ready
        assertEquals(endpoint, ready.endpointConfig)
        assertEquals(profile, ready.profile)
    }

    @Test
    fun `missing named endpoint URL produces needs direction`() = kotlinx.coroutines.runBlocking {
        val result = AgentRuntimeDispatchResolver.resolve(
            AgentRuntimeProfile(
                agentKey = "CODER",
                backend = AgentRuntimeBackend.OLLAMA.id,
                model = "qwen3:8b",
                endpointConfigId = 2L
            ),
            managedServer = null,
            endpointConfig = AgentRuntimeEndpointConfig(
                id = 2L,
                name = "Broken",
                backend = AgentRuntimeBackend.OLLAMA.id,
                baseUrl = ""
            )
        )

        assertTrue(result is AgentRuntimeDispatch.NeedsDirection)
        assertEquals(
            AgentRuntimeNeedsDirectionReason.ENDPOINT_MISSING,
            (result as AgentRuntimeDispatch.NeedsDirection).reason
        )
    }
}
