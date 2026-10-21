package com.example.llamadroid.tama.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TamaArcadeSessionRulesTest {
    @Test
    fun beginIdentityIsAcceptedButIncompleteTerminalMetricsAreRejected() {
        val begin = ArcadeSessionRequest("pet-1", "session-1", "catch", score = 0)
        assertNull(TamaArcadeSessionRules.validate(begin))
        val invalid = begin.copy(score = 5, totalObjects = 10, catches = 5, misses = 4)
        assertEquals("arcade_metrics_invalid", TamaArcadeSessionRules.validate(invalid))
    }

    @Test
    fun rewardMatchesCanonicalCatchAndMemoryRules() {
        val catch = ArcadeSessionRequest("pet-1", "catch-1", "catch", 18, 18, 0, 18)
        assertEquals(50L, TamaArcadeSessionRules.rewardFor(catch).coins)
        assertEquals(12f, TamaArcadeSessionRules.rewardFor(catch).happiness, 0.001f)

        val memory = ArcadeSessionRequest("pet-1", "memory-1", "memory", 80,
            totalObjects = 8, pairsMatched = 8, turnsUsed = 11)
        assertEquals(50L, TamaArcadeSessionRules.rewardFor(memory).coins)
        assertEquals(12f, TamaArcadeSessionRules.rewardFor(memory).happiness, 0.001f)
    }

    @Test
    fun receiptRequestRoundTripsAndNewRowsUseTheClassicWorldId() {
        val request = ArcadeSessionRequest("pet-1", "catch-2", "catch", 5, 5, 3, 8)
        val receipt = TamaArcadeSessionRules.receiptRequest(request, now = 100L)
        assertEquals(TamaArcadeSessionRules.CLASSIC_RECEIPT_WORLD_ID, receipt.worldId)
        val decoded = TamaArcadeSessionRules.decodeRequest(TamaArcadeSessionRules.encodeRequest(receipt))
        assertNotNull(decoded)
        assertEquals(request, TamaArcadeSessionRules.requestFromReceipt(decoded!!))
        assertEquals(true, TamaArcadeSessionRules.sameSession(decoded, request.copy(score = 0)))
    }
}
