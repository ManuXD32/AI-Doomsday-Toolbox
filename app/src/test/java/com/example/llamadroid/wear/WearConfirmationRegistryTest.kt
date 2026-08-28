package com.example.llamadroid.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WearServerStartConfirmationActivity is exported and BROWSABLE, so these cases
 * are the boundary between "the user confirmed a start we asked about" and "an
 * arbitrary app or web page launched the deep link".
 */
class WearConfirmationRegistryTest {

    private var now = 1_000L
    private fun registry(ttlMs: Long = 10_000L, maxEntries: Int = 16) =
        WearConfirmationRegistry(ttlMs = ttlMs, maxEntries = maxEntries, clock = { now })

    @Test
    fun `unknown request id is rejected`() {
        // The attack this whole class exists to stop: a deep link carrying an id
        // we never minted.
        assertFalse(registry().consume("attacker-supplied-id"))
    }

    @Test
    fun `blank request id is rejected`() {
        val registry = registry()
        assertFalse(registry.consume(""))
        // Blank must not be storable either, or it would become a usable key.
        registry.mark("")
        assertFalse(registry.consume(""))
    }

    @Test
    fun `marked request id is accepted`() {
        val registry = registry()
        registry.mark("req-1")
        assertTrue(registry.consume("req-1"))
    }

    @Test
    fun `request id is single use`() {
        val registry = registry()
        registry.mark("req-1")
        assertTrue(registry.consume("req-1"))
        // A replayed link must not start the server a second time.
        assertFalse(registry.consume("req-1"))
    }

    @Test
    fun `expired request id is rejected`() {
        val registry = registry(ttlMs = 10_000L)
        registry.mark("req-1")
        now += 10_001L
        assertFalse(registry.consume("req-1"))
    }

    @Test
    fun `request id on the ttl boundary is still accepted`() {
        val registry = registry(ttlMs = 10_000L)
        registry.mark("req-1")
        now += 10_000L
        assertTrue(registry.consume("req-1"))
    }

    @Test
    fun `oldest entries are evicted once the registry is full`() {
        val registry = registry(maxEntries = 2)
        registry.mark("req-1")
        registry.mark("req-2")
        registry.mark("req-3")
        assertFalse(registry.consume("req-1"))
        assertTrue(registry.consume("req-2"))
        assertTrue(registry.consume("req-3"))
    }

    @Test
    fun `distinct request ids do not interfere`() {
        val registry = registry()
        registry.mark("req-1")
        registry.mark("req-2")
        assertTrue(registry.consume("req-2"))
        assertTrue(registry.consume("req-1"))
    }
}
