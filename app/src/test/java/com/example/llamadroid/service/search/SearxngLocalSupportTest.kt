package com.example.llamadroid.service.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class SearxngLocalSupportTest {
    private val secret = "a".repeat(64)

    @Test
    fun happyPathStateMachine() {
        var s = SearxngLocalState.NOT_INSTALLED
        fun go(e: SearxngLocalEvent) { s = SearxngLocalStateMachine.next(s, e) }
        go(SearxngLocalEvent.INSTALL_REQUESTED); assertEquals(SearxngLocalState.INSTALLING, s)
        go(SearxngLocalEvent.INSTALL_OK); assertEquals(SearxngLocalState.STOPPED, s)
        go(SearxngLocalEvent.START_REQUESTED); assertEquals(SearxngLocalState.STARTING, s)
        go(SearxngLocalEvent.HEALTHY); assertEquals(SearxngLocalState.RUNNING, s)
        go(SearxngLocalEvent.STOP_REQUESTED); assertEquals(SearxngLocalState.STOPPED, s)
        go(SearxngLocalEvent.UNINSTALLED); assertEquals(SearxngLocalState.NOT_INSTALLED, s)
    }

    @Test
    fun failuresAndGuards() {
        val n = SearxngLocalStateMachine
        assertEquals(SearxngLocalState.FAILED, n.next(SearxngLocalState.INSTALLING, SearxngLocalEvent.INSTALL_FAILED))
        assertEquals(SearxngLocalState.INSTALLING, n.next(SearxngLocalState.FAILED, SearxngLocalEvent.INSTALL_REQUESTED))
        assertEquals(SearxngLocalState.FAILED, n.next(SearxngLocalState.STARTING, SearxngLocalEvent.START_FAILED))
        assertEquals(SearxngLocalState.STOPPED, n.next(SearxngLocalState.RUNNING, SearxngLocalEvent.PROCESS_EXITED))
        // Cannot start while installing; marker sync never interrupts an install.
        assertEquals(SearxngLocalState.INSTALLING, n.next(SearxngLocalState.INSTALLING, SearxngLocalEvent.START_REQUESTED))
        assertEquals(SearxngLocalState.INSTALLING, n.next(SearxngLocalState.INSTALLING, SearxngLocalEvent.MARKER_MISSING))
        assertEquals(SearxngLocalState.RUNNING, n.next(SearxngLocalState.RUNNING, SearxngLocalEvent.MARKER_PRESENT))
        assertEquals(SearxngLocalState.STOPPED, n.next(SearxngLocalState.NOT_INSTALLED, SearxngLocalEvent.MARKER_PRESENT))
        assertEquals(SearxngLocalState.NOT_INSTALLED, n.next(SearxngLocalState.STOPPED, SearxngLocalEvent.MARKER_MISSING))
    }

    @Test
    fun commandValidatesInputs() {
        assertEquals(
            "SEARXNG_HOME=/workspace/searxng SEARXNG_PORT=18888 SEARXNG_SECRET=$secret sh /workspace/searxng/searxng_local.sh install",
            SearxngLocalConfig.command("install", 18888, secret)
        )
        assertEquals(
            "SEARXNG_HOME=/workspace/searxng SEARXNG_PORT=18888 sh /workspace/searxng/searxng_local.sh serve",
            SearxngLocalConfig.command("serve", 18888, null)
        )
        listOf(
            { SearxngLocalConfig.command("rm -rf /", 18888, null) },
            { SearxngLocalConfig.command("serve", 80, null) },
            { SearxngLocalConfig.command("install", 18888, "x; reboot") }
        ).forEach { call -> try { call(); fail("expected rejection") } catch (_: IllegalArgumentException) {} }
    }

    @Test
    fun secretAndTailAndIdle() {
        assertTrue(SearxngLocalConfig.newSecret(SecureRandom()).matches(Regex("[0-9a-f]{64}")))
        assertEquals("c\nd", SearxngLocalConfig.tail("a\nb\nc\nd", 4))
        assertEquals("short", SearxngLocalConfig.tail("short", 100))
        assertFalse(SearxngLocalConfig.isIdle(1_000, 0, 5_000))
        assertTrue(SearxngLocalConfig.isIdle(5_000, 0, 5_000))
        assertEquals("http://127.0.0.1:18888/healthz", SearxngLocalConfig.healthUrl(18888))
    }

    /** The app and the host test run this one asset; guard the settings the app relies on. */
    @Test
    fun scriptGeneratesLoopbackJsonSettings() {
        val script = listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, "searxng_local/searxng_local.sh") }.first { it.isFile }.readText()
        listOf(
            "bind_address: \"127.0.0.1\"", "- json", "limiter: false", "public_instance: false",
            "secret_key: \"\$SEARXNG_SECRET\"", "keep_only:", "request_timeout: 10.0", "max_request_timeout: 15.0",
            "disabled: false", "- name: mojeek", "inactive: false", "- mwmbl", "a7128d5d1d5af2837a68fbeaaee9c3e8391aa9cf"
        ).forEach { assertTrue("missing: $it", script.contains(it)) }
        listOf("google", "qwant", "duckduckgo", "brave", "startpage").forEach { engine ->
            assertTrue("engine must stay out: $engine", !Regex("^\\s+(- |- name: )$engine\\s*$", RegexOption.MULTILINE).containsMatchIn(script))
        }
    }
}
