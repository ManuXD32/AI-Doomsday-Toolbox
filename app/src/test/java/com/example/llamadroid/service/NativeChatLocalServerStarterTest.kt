package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatLocalServerStarterTest {
    private val port = 8080

    @Test
    fun healthyRowIsAlwaysReady() {
        listOf(ServerState.Stopped, ServerState.Error("x"), ServerState.Running(9999), ServerState.Starting).forEach {
            assertEquals(LocalServerAction.Ready, decideLocalServerAction(true, it, port))
        }
    }

    @Test
    fun startingAndLoadingWait() {
        assertEquals(LocalServerAction.Wait, decideLocalServerAction(false, ServerState.Starting, port))
        assertEquals(LocalServerAction.Wait, decideLocalServerAction(false, ServerState.Loading(0.5f, "x"), port))
    }

    @Test
    fun stoppedAndErrorStart() {
        assertEquals(LocalServerAction.Start, decideLocalServerAction(false, ServerState.Stopped, port))
        assertEquals(LocalServerAction.Start, decideLocalServerAction(false, ServerState.Error("boom"), port))
    }

    @Test
    fun staleRunningOnSamePortWaitsThenStarts() {
        val running = ServerState.Running(port)
        assertEquals(LocalServerAction.Wait, decideLocalServerAction(false, running, port))
        assertEquals(
            LocalServerAction.Start,
            decideLocalServerAction(false, running, port, staleRunningExpired = true)
        )
    }

    @Test
    fun healthyServerOnOtherPortIsBlocked() {
        val action = decideLocalServerAction(false, ServerState.Running(9090), port, statePortHealthy = true)
        assertTrue(action is LocalServerAction.Blocked)
        assertEquals(9090, (action as LocalServerAction.Blocked).otherPort)
    }

    @Test
    fun deadServerOnOtherPortCanBeReplaced() {
        assertEquals(
            LocalServerAction.Start,
            decideLocalServerAction(false, ServerState.Running(9090), port, statePortHealthy = false)
        )
    }

    @Test
    fun portNormalizationKeepsExactlyOnePortPair() {
        val args = listOf(
            "llama-server", "-m", "model.gguf", "--port", "9999", "-p=1", "--port=7", "-p8000",
            "--prompt", "hi", "--host", "127.0.0.1", "--port", "--threads", "4"
        )
        val normalized = normalizeManagedLlamaServerPortArgs(args, 8080)
        assertEquals(1, normalized.count { it == "--port" })
        assertEquals("8080", normalized[normalized.indexOf("--port") + 1])
        assertTrue("--prompt" in normalized)
        assertTrue("--threads" in normalized)
        assertTrue(normalized.none { it == "-p" || it.startsWith("--port=") || it.startsWith("-p=") || it == "-p8000" })
    }
}
