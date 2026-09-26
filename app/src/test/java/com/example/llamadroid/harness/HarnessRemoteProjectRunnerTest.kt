package com.example.llamadroid.harness

import com.example.llamadroid.service.AgentLocalRuntimeType
import com.example.llamadroid.service.AgentLocalRunState
import com.example.llamadroid.service.AgentRunConfig
import com.example.llamadroid.service.AgentRunUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HarnessRemoteProjectRunnerTest {
    @Test
    fun `web command binds loopback and preserves captured remote root`() {
        val command = HarnessRemoteRunCommand.web("/workspace/ssh project", 23117, "owner-1")
        assertTrue(command.contains("ADT_HARNESS_REMOTE_OWNER='owner-1'"))
        assertTrue(command.contains("-m http.server 23117 --bind 127.0.0.1"))
        assertTrue(command.contains("--directory '/workspace/ssh project'"))
        assertTrue(!command.contains("--bind 0.0.0.0"))
    }

    @Test
    fun `python command quotes entrypoint and every argument`() {
        val fixture = Files.createTempDirectory("adt-remote-run-quoting-").toFile()
        var process: Process? = null
        try {
            val directory = File(fixture, "project's workspace").apply { mkdirs() }
            val bin = File(fixture, "bin").apply { mkdirs() }
            File(bin, "python3").apply {
                writeText("#!/bin/sh\nprintf '%s\\0' \"\$PWD\" \"\$ADT_HARNESS_REMOTE_OWNER\" \"\$@\"\n")
                assertTrue(setExecutable(true, true))
            }
            val args = listOf("--name", "it's safe", "\$(printf injected); *")
            val command = HarnessRemoteRunCommand.python(
                directory.absolutePath,
                "scripts/run script.py",
                args,
                "owner-2"
            )
            process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator +
                    System.getenv("PATH").orEmpty()
                redirectErrorStream(true)
            }.start()
            assertTrue("Remote command fixture did not exit", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.exitValue())
            assertEquals(
                listOf(directory.absolutePath, "owner-2", directory.absolutePath + "/scripts/run script.py") + args,
                output.split('\u0000').dropLast(1)
            )
        } finally {
            process?.destroyForcibly()
            fixture.deleteRecursively()
        }
    }

    @Test
    fun `preview path encodes each segment without changing separators`() {
        assertEquals("/site%20files/index.html", HarnessRemoteRunCommand.previewPath("site files/index.html"))
    }

    @Test
    fun `run manifest keeps the only supported remote runtimes explicit`() {
        val web = AgentRunConfig(1, AgentLocalRuntimeType.WEB, "index.html", AgentRunUiMode.WEB)
        val python = AgentRunConfig(1, AgentLocalRuntimeType.PYTHON, "main.py", AgentRunUiMode.CONSOLE)
        assertEquals("web", web.runtime.name.lowercase())
        assertEquals("python", python.runtime.name.lowercase())
    }

    @Test
    fun `state merge removes stale remote owner and preserves replacement local owner`() {
        val staleRemote = state(11L, "SUCCEEDED").copy(startedAt = 10, endedAt = 15)
        val local = state(11L, "RUNNING").copy(startedAt = 20)
        val unrelated = state(12L, "FAILED")

        val merged = HarnessProjectRunStatePolicy.mergeOwnedStates(
            current = mapOf(11L to staleRemote, 12L to unrelated),
            previousOwnedIds = setOf(11L),
            nextOwned = emptyMap(),
            competingOwned = mapOf(11L to local)
        )

        assertEquals(local, merged[11L])
        assertEquals(unrelated, merged[12L])
    }

    @Test
    fun `delayed old static snapshot cannot hide a newer remote run`() {
        val oldStatic = state(11L, "STOPPED").copy(startedAt = 10, endedAt = 15)
        val remote = state(11L, "RUNNING").copy(startedAt = 20)
        val merged = HarnessProjectRunStatePolicy.mergeOwnedStates(
            current = mapOf(11L to remote), previousOwnedIds = setOf(11L),
            nextOwned = mapOf(11L to oldStatic), competingOwned = mapOf(11L to remote)
        )
        assertEquals(remote, merged[11L])
    }

    @Test
    fun `older running snapshot cannot regress completed run status`() {
        val active = state(11L, "RUNNING").copy(startedAt = 10)
        val completed = active.copy(status = "SUCCEEDED", endedAt = 20)
        val merged = HarnessProjectRunStatePolicy.mergeOwnedStates(
            current = mapOf(11L to completed), previousOwnedIds = setOf(11L),
            nextOwned = mapOf(11L to active), competingOwned = emptyMap()
        )
        assertEquals(completed, merged[11L])
    }

    @Test
    fun `preview snapshots preserve the independently owned python state`() {
        val python = state(11L, "RUNNING").copy(runtime = "python", startedAt = 20)
        val merged = HarnessProjectRunStatePolicy.mergeOwnedStates(
            current = mapOf(11L to python), previousOwnedIds = setOf(11L),
            nextOwned = emptyMap(), competingOwned = emptyMap(), hiddenIds = setOf(11L)
        )
        assertEquals(python, merged[11L])
    }

    @Test
    fun `terminal state is not rewritten by a later stop`() {
        val completed = state(13L, "SUCCEEDED")
        val active = state(14L, "RUNNING")

        assertNull(HarnessProjectRunStatePolicy.stopIfActive(completed, endedAt = 100L))
        assertEquals("STOPPED", HarnessProjectRunStatePolicy.stopIfActive(active, endedAt = 100L)?.status)
    }

    @Test
    fun `failed start state distinguishes cancellation from failure`() {
        val started = state(15L, "RUNNING")

        assertEquals("STOPPED", HarnessProjectRunStatePolicy.failedStartState(started, cancelled = true, endedAt = 100L).status)
        assertEquals("FAILED", HarnessProjectRunStatePolicy.failedStartState(started, cancelled = false, endedAt = 100L).status)
    }

    private fun state(conversationId: Long, status: String) = AgentLocalRunState(
        conversationId = conversationId,
        projectFolder = "project",
        runtime = "web",
        entrypoint = "index.html",
        uiMode = "WEB",
        status = status,
        logs = ""
    )
}
