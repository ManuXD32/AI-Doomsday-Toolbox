package com.example.llamadroid.tama.game

import com.example.llamadroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaPetCachePolicyTest {
    private val card = SettingsRepository.TAMA_SERVER_SOURCE_CARD
    private val custom = SettingsRepository.TAMA_SERVER_SOURCE_CUSTOM

    @Test
    fun localLlamaServerCardWithToggleOnUsesDiskCache() {
        val d = decideTamaPetCache(card, true, "llama-server", "127.0.0.1", true)
        assertTrue(d.usesDiskCache)
        assertFalse(d.showWarning)
        assertNull(d.reason)
    }

    @Test
    fun customUrlNeverUsesDiskCache() {
        val d = decideTamaPetCache(custom, true, "llama-server", "127.0.0.1", true)
        assertEquals(TamaPetNoCacheReason.CUSTOM_URL, d.reason)
        assertTrue(d.showWarning)
    }

    @Test
    fun missingCardWarns() {
        assertEquals(TamaPetNoCacheReason.NO_CARD, decideTamaPetCache(card, false, null, null, true).reason)
    }

    @Test
    fun toggleOffWarns() {
        assertEquals(
            TamaPetNoCacheReason.TOGGLE_OFF,
            decideTamaPetCache(card, true, "llama-server", "localhost", false).reason
        )
    }

    @Test
    fun otherEnginesWarn() {
        for (engine in listOf("ollama", "llama-swap", "litert-lm")) {
            assertEquals(
                engine,
                TamaPetNoCacheReason.ENGINE,
                decideTamaPetCache(card, true, engine, "127.0.0.1", true).reason
            )
        }
    }

    @Test
    fun remoteHostWarns() {
        assertEquals(
            TamaPetNoCacheReason.REMOTE,
            decideTamaPetCache(card, true, "llama-server", "192.168.1.20", true).reason
        )
    }

    @Test
    fun sourceNormalizerDefaultsToCustom() {
        assertEquals(custom, SettingsRepository.normalizeTamaServerSource(null))
        assertEquals(custom, SettingsRepository.normalizeTamaServerSource("bogus"))
        assertEquals(card, SettingsRepository.normalizeTamaServerSource("CARD"))
    }
}
