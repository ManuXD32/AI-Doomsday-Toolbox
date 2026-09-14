package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProotTerminalLaunchTest {
    @Test
    fun `interactive shell uses direct bash instead of transcript wrapper`() {
        val arguments = agentProotInteractiveShellArguments()
        assertTrue(arguments.takeLast(4) == listOf("/bin/bash", "--noprofile", "--norc", "-i"))
        assertFalse(arguments.any { it.contains("/usr/bin/script") })
    }

    @Test
    fun `interactive launch exposes only the JNI resolved active PTY`() {
        val arguments = agentProotInteractivePtyBindArguments()
        assertTrue(arguments == listOf("-b", AGENT_PROOT_ACTIVE_PTY_BIND))
        assertFalse(arguments.any { it == "/dev/pts:/dev/pts" })
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
}
