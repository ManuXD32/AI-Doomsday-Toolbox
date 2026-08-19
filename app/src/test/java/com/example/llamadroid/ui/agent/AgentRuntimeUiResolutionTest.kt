package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.runtime.ManagedLlamaServerDescriptor
import com.example.llamadroid.data.runtime.ManagedLlamaServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeUiResolutionTest {
    private fun managedServer(id: Long = 8L) = ManagedLlamaServerDescriptor(
        id = id,
        displayName = "Managed $id",
        host = "127.0.0.1",
        port = 8080 + id.toInt(),
        backend = AgentRuntimeBackend.LLAMA_SERVER.id,
        modelName = "managed-$id.gguf",
        state = ManagedLlamaServerState.RUNNING
    )

    @Test
    fun `custom agent uses collision safe profile key`() {
        assertEquals(
            "CUSTOM:DEBUGGER",
            agentRuntimeProfileKeyForUi("CODER", "  Debugger ")
        )
        assertEquals(
            "CODER",
            agentRuntimeProfileKeyForUi("CODER", " ")
        )
    }

    @Test
    fun `named endpoint is authoritative and supplies model fallback`() {
        val endpoint = AgentRuntimeEndpointConfig(
            id = 4L,
            name = "Studio endpoint",
            backend = AgentRuntimeBackend.OLLAMA.id,
            baseUrl = "https://studio.example:11434",
            defaultModel = "studio-model"
        )
        val resolved = resolveAgentRuntimeUi(
            profile = AgentRuntimeProfile(
                agentKey = "CUSTOM:DEBUGGER",
                backend = AgentRuntimeBackend.LLAMA_SERVER.id,
                endpointConfigId = endpoint.id,
                managedLlamaServerId = 8L
            ),
            endpointConfigs = listOf(endpoint),
            managedServers = listOf(managedServer())
        )

        assertTrue(resolved.hasNamedEndpoint)
        assertFalse(resolved.hasManagedServer)
        assertEquals(AgentRuntimeBackend.OLLAMA.id, resolved.backendId)
        assertEquals("studio-model", resolved.model)
        assertEquals("Studio endpoint", resolved.targetLabel)
    }

    @Test
    fun `managed server is used only when no endpoint is assigned`() {
        val resolved = resolveAgentRuntimeUi(
            profile = AgentRuntimeProfile(
                agentKey = "CODER",
                backend = AgentRuntimeBackend.LLAMA_SERVER.id,
                managedLlamaServerId = 8L,
                model = "profile-model"
            ),
            endpointConfigs = emptyList(),
            managedServers = listOf(managedServer())
        )

        assertFalse(resolved.hasNamedEndpoint)
        assertTrue(resolved.hasManagedServer)
        assertEquals(8L, resolved.managedServer?.id)
        assertEquals("managed-8.gguf", resolved.model)
    }

    @Test
    fun `missing endpoint does not fall back to an unrelated managed server`() {
        val resolved = resolveAgentRuntimeUi(
            profile = AgentRuntimeProfile(
                agentKey = "CUSTOM:DEBUGGER",
                backend = AgentRuntimeBackend.OLLAMA.id,
                endpointConfigId = 99L,
                managedLlamaServerId = 8L
            ),
            endpointConfigs = emptyList(),
            managedServers = listOf(managedServer())
        )

        assertTrue(resolved.endpointReferenceMissing)
        assertNull(resolved.endpointConfig)
        assertNull(resolved.managedServer)
        assertFalse(resolved.hasNamedEndpoint)
    }

    @Test
    fun `missing managed server does not fall back to global runtime health`() {
        val resolved = resolveAgentRuntimeUi(
            profile = AgentRuntimeProfile(
                agentKey = "RESEARCHER",
                backend = AgentRuntimeBackend.LLAMA_SWAP.id,
                managedLlamaServerId = 77L,
                model = "research-model"
            ),
            endpointConfigs = emptyList(),
            managedServers = emptyList()
        )

        assertTrue(resolved.hasManagedServerAssignment)
        assertFalse(resolved.hasManagedServer)
        assertNull(resolved.managedServer)
        assertEquals("research-model", resolved.model)
    }
}
