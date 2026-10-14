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
