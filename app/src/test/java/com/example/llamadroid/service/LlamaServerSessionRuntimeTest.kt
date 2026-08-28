package com.example.llamadroid.service

import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.repository.launchProfileForCardPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaServerSessionRuntimeTest {
    @Test
    fun `managed port normalization leaves exactly one port pair`() {
        val normalized = normalizeManagedLlamaServerPortArgs(
            listOf(
                "/data/app/libllama-server.so",
                "--model", "model.gguf",
                "--port", "1111",
                "--port=2222",
                "-p", "3333",
                "--host", "127.0.0.1"
            ),
            port = 8088
        )

        assertEquals(1, normalized.count { it == "--port" })
        assertEquals(0, normalized.count { it.startsWith("--port=") })
        assertEquals(listOf("--port", "8088"), normalized.slice(normalized.indexOf("--port")..normalized.indexOf("--port") + 1))
        assertTrue(normalized.containsAll(listOf("--model", "model.gguf", "--host", "127.0.0.1")))
    }

    @Test
    fun `managed port normalization preserves host after malformed port option`() {
        val normalized = normalizeManagedLlamaServerPortArgs(
            listOf("binary", "--port", "--host", "127.0.0.1"),
            port = 8088
        )

        assertEquals(listOf("--port", "8088"), normalized.slice(normalized.indexOf("--port")..normalized.indexOf("--port") + 1))
        assertTrue(normalized.containsAll(listOf("--host", "127.0.0.1")))
    }

    @Test
    fun `managed port normalization removes compact short port spelling`() {
        val normalized = normalizeManagedLlamaServerPortArgs(
            listOf("binary", "--model", "model.gguf", "-p8080", "--host", "127.0.0.1"),
            port = 9090
        )

        assertFalse(normalized.any { it.matches(Regex("-p\\d+")) })
        assertEquals(listOf("--port", "9090"), normalized.slice(normalized.indexOf("--port")..normalized.indexOf("--port") + 1))
        assertTrue(normalized.containsAll(listOf("--model", "model.gguf", "--host", "127.0.0.1")))
    }

    @Test
    fun `card session id is stable and profile follows preset while overriding only port`() {
        val card = LlamaServerCardEntity(
            id = 42L,
            name = "Main",
            savedCommandId = 7L,
            presetNameSnapshot = "Original",
            port = 9090
        )
        val preset = SavedCommand(
            id = 7L,
            name = "Updated",
            modelPath = "/models/updated.gguf",
            contextSize = 16384,
            threads = 8,
            host = "127.0.0.1"
        )

        val profile = preset.launchProfileForCardPort(card.port)
        assertEquals("card:42", card.sessionId)
        assertEquals("/models/updated.gguf", profile.modelPath)
        assertEquals(16384, profile.contextSize)
        assertEquals(8, profile.threads)
        assertEquals(9090, profile.serverPort)
    }

    @Test
    fun `card port override preserves every canonical profile field`() {
        val preset = savedCommandFromLaunchProfile(
            name = "Canonical",
            profile = LlamaServerLaunchProfile(
                modelPath = "/models/canonical.gguf",
                serverPort = 8081,
                threads = 12,
                customFlags = "--no-warmup",
                nativeBinarySelection = SettingsRepository.NATIVE_BINARY_CPU_DOTPROD,
                nativeToolsEnabled = true
            ),
            id = 7L
        )

        val profile = preset.launchProfileForCardPort(9090)

        assertEquals("/models/canonical.gguf", profile.modelPath)
        assertEquals(12, profile.threads)
        assertEquals("--no-warmup", profile.customFlags)
        assertEquals(SettingsRepository.NATIVE_BINARY_CPU_DOTPROD, profile.nativeBinarySelection)
        assertTrue(profile.nativeToolsEnabled)
        assertEquals(9090, profile.serverPort)
    }

    @Test
    fun `server states project to independent session statuses`() {
        assertEquals(LlamaServerSessionStatus.STOPPED, ServerState.Stopped.toLlamaServerSessionStatus())
        assertEquals(LlamaServerSessionStatus.STARTING, ServerState.Starting.toLlamaServerSessionStatus())
        assertEquals(LlamaServerSessionStatus.LOADING, ServerState.Loading(-1f, "load").toLlamaServerSessionStatus())
        assertEquals(LlamaServerSessionStatus.RUNNING, ServerState.Running(8080).toLlamaServerSessionStatus())
        assertEquals(LlamaServerSessionStatus.ERROR, ServerState.Error("bad").toLlamaServerSessionStatus())
    }
}
