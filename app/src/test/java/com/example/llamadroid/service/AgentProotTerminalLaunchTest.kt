package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentProotTerminalLaunchTest {
    @Test
    fun `interactive shell uses direct bash instead of transcript wrapper`() {
        val arguments = agentProotInteractiveShellArguments()
        assertTrue(arguments.takeLast(4) == listOf("/bin/bash", "--noprofile", "--norc", "-i"))
        assertFalse(arguments.any { it.contains("/usr/bin/script") })
    }

    @Test
    fun `guest readiness launch writes an owned nonce before execing interactive bash`() {
        val arguments = agentProotInteractiveShellArguments(
            guestReadyFile = "/run/adt-terminal-ready/terminal_test",
            guestReadyNonce = "nonce-test"
        )

        assertTrue(arguments.contains("-c"))
        val command = arguments.last()
        assertTrue(command.contains("printf '%s' 'nonce-test'"))
        assertTrue(command.contains("/run/adt-terminal-ready/terminal_test"))
        assertTrue(command.contains("exec /bin/bash --noprofile --norc -i"))
    }

    @Test
    fun `guest readiness receipt accepts only its nonce and cleans up`() {
        val root = Files.createTempDirectory("agent-proot-ready").toFile()
        try {
            val receipt = createAgentProotGuestReadyReceipt(root, "terminal_test")
            assertFalse(agentProotGuestReadyReceiptMatches(receipt))
            receipt.hostFile.writeText("wrong")
            assertFalse(agentProotGuestReadyReceiptMatches(receipt))
            receipt.hostFile.writeText(receipt.nonce)
            assertTrue(agentProotGuestReadyReceiptMatches(receipt))
            cleanupAgentProotGuestReadyReceipt(receipt)
            assertFalse(receipt.hostFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `interactive launch mounts the PTY parent before the JNI resolved active PTY`() {
        val arguments = agentProotInteractivePtyBindArguments()
        assertEquals(
            listOf(
                "-b", AGENT_PROOT_PTY_PARENT_BIND,
                "-b", AGENT_PROOT_ACTIVE_PTY_BIND
            ),
            arguments
        )
        assertTrue(arguments.indexOf(AGENT_PROOT_PTY_PARENT_BIND) < arguments.indexOf(AGENT_PROOT_ACTIVE_PTY_BIND))
    }

    @Test
    fun `interactive environment has terminal locale and no noninteractive apt override`() {
        val environment = agentProotInteractiveEnvironment(
            nativeLibraryDir = "/native",
            tempRoot = "/cache/tmp",
            projectFolder = "pomodoro",
            sessionId = "terminal_1",
            loaderPath = "/native/libproot_loader.so"
        ).toSet()
        assertTrue("TERM=xterm-256color" in environment)
        assertTrue("COLORTERM=truecolor" in environment)
        assertTrue("LANG=C.UTF-8" in environment)
        assertTrue("LC_ALL=C.UTF-8" in environment)
        assertTrue("SHELL=/bin/bash" in environment)
        assertTrue("PROOT_LOADER=/native/libproot_loader.so" in environment)
        assertFalse(environment.any { it.startsWith("DEBIAN_FRONTEND=") })
    }

    @Test
    fun `terminal session limits allow eight per project and twenty four per process`() {
        assertTrue(canOpenAgentProotTerminal(projectActive = 7, processActive = 23))
        assertFalse(canOpenAgentProotTerminal(projectActive = 8, processActive = 8))
        assertFalse(canOpenAgentProotTerminal(projectActive = 1, processActive = 24))
        assertFalse(canOpenAgentProotTerminal(projectActive = -1, processActive = 0))
    }

    @Test
    fun `startup failure codes contain phase metadata without exception text`() {
        assertEquals(
            "TERMINAL_PROCESS_NOT_READY",
            agentProotTerminalFailureCode(
                AgentProotTerminalStartupPhase.WAITING_FOR_PROCESS,
                IllegalStateException("private path and terminal output")
            )
        )
        assertEquals(
            "TERMINAL_GUEST_SHELL_NOT_READY",
            agentProotTerminalFailureCode(
                AgentProotTerminalStartupPhase.WAITING_FOR_GUEST_SHELL,
                IllegalStateException("guest output must not be retained")
            )
        )
    }
}
