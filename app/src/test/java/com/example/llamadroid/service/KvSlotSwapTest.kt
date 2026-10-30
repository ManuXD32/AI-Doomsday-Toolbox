package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KvSlotSwapTest {
    private val me = KvCacheKey.Chat(1)
    private val other = KvCacheKey.Delegate(2)

    @Test
    fun `exhaustive truth table`() {
        val holders = listOf(null, me, other)
        for (holder in holders) for (dirty in listOf(false, true)) for (persists in listOf(false, true))
            for (valid in listOf(false, true)) for (disturbed in listOf(false, true)) {
                val d = decideSwap(holder, dirty, persists, me, valid, disturbed)
                val holderIsMe = holder == me && !disturbed
                assertEquals(
                    "save holder=$holder dirty=$dirty persists=$persists disturbed=$disturbed",
                    holder == other && dirty && persists && !disturbed, d.saveHolder
                )
                assertEquals("restore holder=$holder valid=$valid disturbed=$disturbed", valid && !holderIsMe, d.restore)
            }
    }

    @Test
    fun `preempting a dirty persisting holder saves it then restores me`() {
        val d = decideSwap(other, true, true, me, true)
        assertTrue(d.saveHolder)
        assertTrue(d.restore)
    }

    @Test
    fun `holder that is me and undisturbed needs nothing`() {
        assertEquals(KvSlotSwapDecision.NONE, decideSwap(me, true, true, me, true))
    }

    @Test
    fun `non persisting or clean holder is not saved`() {
        assertFalse(decideSwap(other, true, false, me, false).saveHolder)
        assertFalse(decideSwap(other, false, true, me, false).saveHolder)
    }

    @Test
    fun `holder state matches only the same task and prompt`() {
        val holder = KvSlotHolderState(me, true, true, null, idTask = 5, nPromptTokens = 100)
        assertTrue(holder.matches(KvSlotFact(0, 4096, 5, 100)))
        assertFalse(holder.matches(KvSlotFact(0, 4096, 6, 100)))
        assertFalse(holder.matches(KvSlotFact(0, 4096, 5, 99)))
        assertFalse(holder.matches(null))
        assertFalse(holder.copy(idTask = null).matches(KvSlotFact(0, 4096, null, 100)))
    }
}

class KvUtilitySlotTest {
    @Test
    fun `failed slots read is unknown not disturbed`() {
        assertFalse(isHolderDisturbed(hasHolder = true, slotsKnown = false, holderMatches = false))
        assertTrue(isHolderDisturbed(hasHolder = true, slotsKnown = true, holderMatches = false))
        assertFalse(isHolderDisturbed(hasHolder = true, slotsKnown = true, holderMatches = true))
        assertFalse(isHolderDisturbed(hasHolder = false, slotsKnown = true, holderMatches = false))
    }

    @Test
    fun `restart is inferred only from a successful slots read`() {
        assertFalse(shouldInferRestart(slotsKnown = false, previousMaxId = 12, maxId = -1))
        assertTrue(shouldInferRestart(slotsKnown = true, previousMaxId = 12, maxId = -1))
        assertFalse(shouldInferRestart(slotsKnown = true, previousMaxId = null, maxId = -1))
        assertFalse(shouldInferRestart(slotsKnown = true, previousMaxId = 3, maxId = 5))
    }

    @Test
    fun `holder with unreadable facts is trusted`() {
        val h = KvSlotHolderState(KvCacheKey.Chat(1), true, true, null, null, 0, factsKnown = false)
        assertTrue(h.matches(null))
        val known = h.copy(factsKnown = true)
        assertFalse(known.matches(null))
    }

    @Test
    fun `utility slot prefers a never-held slot`() {
        assertEquals(KvUtilitySlotChoice(1, false), chooseUtilitySlot(3, mapOf(0 to true), setOf(2)))
    }

    @Test
    fun `utility slot avoids live turns then saves an idle holder`() {
        assertEquals(KvUtilitySlotChoice(1, true), chooseUtilitySlot(2, mapOf(0 to true, 1 to false), emptySet()))
    }

    @Test
    fun `single slot is swap saved and shared`() {
        assertEquals(KvUtilitySlotChoice(0, true), chooseUtilitySlot(1, mapOf(0 to true), emptySet()))
        assertEquals(KvUtilitySlotChoice(0, true), chooseUtilitySlot(1, emptyMap(), setOf(0)))
        assertEquals(null, chooseUtilitySlot(0, emptyMap(), emptySet()))
    }

    @Test
    fun `slots body parsing distinguishes error pages from empty`() {
        assertEquals(null, parseKvSlotsOrNull("<html>"))
        assertEquals(emptyList<KvSlotFact>(), parseKvSlotsOrNull("[]"))
    }
}
