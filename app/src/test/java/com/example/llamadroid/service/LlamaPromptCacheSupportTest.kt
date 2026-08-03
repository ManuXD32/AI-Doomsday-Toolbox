package com.example.llamadroid.service

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaPromptCacheSupportTest {
    @After
    fun reset() = LlamaSlotManager.invalidateAll()

    @Test
    fun `conversation keeps its slot and separate conversations get separate slots`() = runBlocking {
        val first = owner("conversation-a")
        val second = owner("conversation-b")
        val a1 = LlamaSlotManager.withAssignedSlot(first, 2, LlamaSlotAffinityMode.AUTOMATIC) { it }
        val b = LlamaSlotManager.withAssignedSlot(second, 2, LlamaSlotAffinityMode.AUTOMATIC) { it }
        val a2 = LlamaSlotManager.withAssignedSlot(first, 2, LlamaSlotAffinityMode.AUTOMATIC) { it }

        assertEquals(a1, a2)
        assertNotEquals(a1, b)
    }

    @Test
    fun `disabled affinity omits id slot`() = runBlocking {
        val assigned = LlamaSlotManager.withAssignedSlot(
            owner("conversation-a"),
            4,
            LlamaSlotAffinityMode.DISABLED
        ) { it }
        assertNull(assigned)
    }

    @Test
    fun `LRU eviction remains correct with one slot`() = runBlocking {
        LlamaSlotManager.withAssignedSlot(owner("old"), 1, LlamaSlotAffinityMode.ENABLED) { }
        LlamaSlotManager.withAssignedSlot(owner("new"), 1, LlamaSlotAffinityMode.ENABLED) { }
        assertEquals("new", LlamaSlotManager.snapshotAssignments().single().owner.conversationId)
    }

    @Test
    fun `recognized slot failure is narrowly detected`() {
        assertTrue(isRecognizedSlotSelectionError(IllegalStateException("id_slot is out of range")))
    }

    private fun owner(conversation: String) = LlamaSlotOwnerKey(
        endpointGeneration = "server-generation-1",
        modelConfiguration = "model-config",
        conversationId = conversation,
        agentSessionId = "orchestrator"
    )
}
