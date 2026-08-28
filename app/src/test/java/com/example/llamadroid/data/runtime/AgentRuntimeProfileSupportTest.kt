package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.AgentRuntimeProfileKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeProfileSupportTest {
    private fun server(
        id: Long,
        port: Int,
        host: String = "127.0.0.1",
        state: ManagedLlamaServerState = ManagedLlamaServerState.RUNNING
    ) = ManagedLlamaServerDescriptor(
        id = id,
        displayName = "Server $id",
        host = host,
        port = port,
        backend = AgentRuntimeBackend.LLAMA_SERVER.id,
        modelName = "models/model-$id.gguf",
        state = state
    )

    @Test
    fun `legacy llama port binds only to one unambiguous managed server`() {
        val result = AgentRuntimeProfileMigration.migrate(
            builtInAgentKeys = listOf("CODER"),
            legacy = LegacyAgentRuntimeSettings(
                globalBackend = "llama-server",
                llamaServerUrl = "http://127.0.0.1:8080"
            ),
            managedServers = listOf(server(id = 7, port = 8080)),
            now = 123L
        )

        assertEquals(7L, result.profiles.single().managedLlamaServerId)
        assertEquals(setOf("CODER"), result.createdAgentKeys)
        assertTrue(result.requiresServerSelection.isEmpty())
    }

    @Test
    fun `legacy llama port remains unassigned when candidates are ambiguous`() {
        val result = AgentRuntimeProfileMigration.migrate(
            builtInAgentKeys = listOf("CODER", "SUMMARIZER"),
            legacy = LegacyAgentRuntimeSettings(
                globalBackend = "llama_server",
                llamaServerUrl = "http://localhost:8080"
            ),
            managedServers = listOf(
                server(id = 7, port = 8080),
                server(id = 8, port = 8080)
            ),
            now = 456L
        )

        assertTrue(result.profiles.all { it.managedLlamaServerId == null })
        assertEquals(setOf("CODER", "SUMMARIZER"), result.requiresServerSelection)
    }

    @Test
    fun `existing profile is authoritative and is not overwritten by migration`() {
        val existing = AgentRuntimeProfile(
            agentKey = "CODER",
            backend = AgentRuntimeBackend.LITERT.id,
            liteRtModelId = 99L,
            updatedAt = 77L
        )
        val result = AgentRuntimeProfileMigration.migrate(
            builtInAgentKeys = listOf("CODER"),
            legacy = LegacyAgentRuntimeSettings(
                globalBackend = AgentRuntimeBackend.OLLAMA.id,
                roleModels = mapOf("CODER" to "old-model")
            ),
            managedServers = emptyList(),
            existingProfiles = listOf(existing),
            now = 999L
        )

        assertEquals(existing, result.profiles.single())
        assertTrue(result.createdAgentKeys.isEmpty())
        assertEquals(setOf("CODER"), result.alreadyHadProfiles)
    }

    @Test
    fun `custom legacy model migrates under collision safe custom key`() {
        val key = AgentRuntimeProfileKeys.custom("Researcher")
        val result = AgentRuntimeProfileMigration.migrate(
            builtInAgentKeys = listOf("RESEARCHER"),
            customAgentNames = listOf("Researcher"),
            legacy = LegacyAgentRuntimeSettings(
                globalBackend = "ollama",
                customModels = mapOf("RESEARCHER" to "custom-model")
            ),
            managedServers = emptyList(),
            now = 1L
        )

        assertEquals(key, result.profiles.last().agentKey)
        assertEquals("custom-model", result.profiles.last().model)
    }

    @Test
    fun `stopped or missing server produces needs direction with continue shortcut`() = kotlinx.coroutines.runBlocking {
        val profile = AgentRuntimeProfile(
            agentKey = "CODER",
            backend = AgentRuntimeBackend.LLAMA_SERVER.id,
            managedLlamaServerId = 7L
        )
        val stopped = AgentRuntimeDispatchResolver.resolve(
            profile,
            server(7L, 8080, state = ManagedLlamaServerState.STOPPED)
        )
        assertTrue(stopped is AgentRuntimeDispatch.NeedsDirection)
        assertEquals(AgentRuntimeNeedsDirectionReason.SERVER_STOPPED, (stopped as AgentRuntimeDispatch.NeedsDirection).reason)
        assertEquals("managed_llama_server", stopped.continueAction.destination)

        val missing = AgentRuntimeDispatchResolver.resolve(profile, null)
        assertTrue(missing is AgentRuntimeDispatch.NeedsDirection)
        assertEquals(AgentRuntimeNeedsDirectionReason.SERVER_MISSING, (missing as AgentRuntimeDispatch.NeedsDirection).reason)
    }

    @Test
    fun `assigned llama swap server also refuses a stopped managed server`() = kotlinx.coroutines.runBlocking {
        val profile = AgentRuntimeProfile(
            agentKey = "RESEARCHER",
            backend = AgentRuntimeBackend.LLAMA_SWAP.id,
            model = "research-model",
            managedLlamaServerId = 9L
        )
        val result = AgentRuntimeDispatchResolver.resolve(
            profile,
            server(9L, 8081, state = ManagedLlamaServerState.STOPPED).copy(
                backend = AgentRuntimeBackend.LLAMA_SWAP.id
            )
        )
        assertTrue(result is AgentRuntimeDispatch.NeedsDirection)
        assertEquals(
            AgentRuntimeNeedsDirectionReason.SERVER_STOPPED,
            (result as AgentRuntimeDispatch.NeedsDirection).reason
        )
        assertEquals("managed_llama_server", result.continueAction.destination)
    }

    @Test
    fun `ready dispatch carries an immutable profile snapshot`() = kotlinx.coroutines.runBlocking {
        val profile = AgentRuntimeProfile(
            agentKey = "RESEARCHER",
            backend = AgentRuntimeBackend.OLLAMA.id,
            model = "research-model",
            updatedAt = 42L
        )
        val dispatch = AgentRuntimeDispatchResolver.resolve(profile, null)
        assertTrue(dispatch is AgentRuntimeDispatch.Ready)
        val ready = dispatch as AgentRuntimeDispatch.Ready
        assertEquals(profile, ready.profile)
        assertNull(ready.managedServer)
    }

    @Test
    fun `litert dispatch refuses an unavailable model`() = kotlinx.coroutines.runBlocking {
        val profile = AgentRuntimeProfile(
            agentKey = "SUMMARIZER",
            backend = AgentRuntimeBackend.LITERT.id,
            liteRtModelId = 12L
        )
        val result = AgentRuntimeDispatchResolver.resolve(
            profile,
            null,
            object : AgentLiteRtModelCatalog {
                override suspend fun containsModel(id: Long): Boolean = false
            }
        )
        assertTrue(result is AgentRuntimeDispatch.NeedsDirection)
        assertEquals(AgentRuntimeNeedsDirectionReason.LITERT_MODEL_MISSING, (result as AgentRuntimeDispatch.NeedsDirection).reason)
    }
}
