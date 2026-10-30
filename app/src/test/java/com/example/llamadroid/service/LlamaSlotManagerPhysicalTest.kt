package com.example.llamadroid.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaSlotManagerPhysicalTest {
    @After
    fun reset() = LlamaSlotManager.invalidateAll()

    private fun owner(
        generation: String = "gen-1",
        conversation: String = "c",
        session: String = "",
        physical: String? = "http://127.0.0.1:8080"
    ) = LlamaSlotOwnerKey(generation, "model", conversation, session, physical)

    private fun reserve(owner: LlamaSlotOwnerKey, slots: Int = 2) =
        LlamaSlotManager.reserve(owner, slots, LlamaSlotAffinityMode.ENABLED)

    @Test
    fun `physical endpoint groups owners across generation strings`() {
        val a = reserve(owner(generation = "kv:a", conversation = "x"))!!
        val b = reserve(owner(generation = "managed:b", conversation = "y"))!!
        assertNotEquals(a.slotId, b.slotId)
    }

    @Test
    fun `without physical endpoint generations stay separate pools`() {
        val a = reserve(owner(generation = "g1", conversation = "x", physical = null), 1)!!
        val b = reserve(owner(generation = "g2", conversation = "y", physical = null), 1)!!
        assertEquals(0, a.slotId)
        assertEquals(0, b.slotId)
    }

    @Test
    fun `null physical endpoint with all slots reserved returns null like before`() {
        reserve(owner(conversation = "x", physical = null), 1)!!
        assertNull(reserve(owner(conversation = "y", physical = null), 1))
    }

    @Test
    fun `same conversation keeps its slot across attempt ids`() {
        val first = reserve(owner(conversation = "harness:s1", session = "attempt-1"))!!
        LlamaSlotManager.release(first)
        val other = reserve(owner(conversation = "other"))!!
        LlamaSlotManager.release(other)
        val second = reserve(owner(conversation = "harness:s1", session = "attempt-2"))!!
        assertEquals(first.slotId, second.slotId)
        // The previous attempt key no longer owns the slot.
        assertEquals(2, LlamaSlotManager.snapshotAssignments().size)
    }

    @Test
    fun `all slots reserved shares the least recently used slot`() {
        val a = reserve(owner(conversation = "a"), 2)!!
        Thread.sleep(5)
        val b = reserve(owner(conversation = "b"), 2)!!
        Thread.sleep(5)
        val c = reserve(owner(conversation = "c"), 2)
        assertNotNull(c)
        assertEquals(a.slotId, c!!.slotId)
        assertNotEquals(b.slotId, c.slotId)
    }

    @Test
    fun `release lets the slot be evicted by a later owner`() {
        val a = reserve(owner(conversation = "a"), 1)!!
        LlamaSlotManager.release(a)
        val b = reserve(owner(conversation = "b"), 1)!!
        assertEquals(0, b.slotId)
        assertEquals("b", LlamaSlotManager.snapshotAssignments().single().owner.conversationId)
    }

    @Test
    fun `requests on the same allocation key and slot are serialized`() = runBlocking {
        val first = reserve(owner(generation = "g1", conversation = "a"), 1)!!
        val second = reserve(owner(generation = "g2", conversation = "b"), 1)!!
        assertEquals(first.slotId, second.slotId)
        val order = mutableListOf<String>()
        val job1 = async {
            LlamaSlotManager.withSlotRequest(first) { order += "a-start"; delay(50); order += "a-end" }
        }
        delay(10)
        val job2 = async {
            LlamaSlotManager.withSlotRequest(second) { order += "b-start"; order += "b-end" }
        }
        job1.await(); job2.await()
        assertEquals(listOf("a-start", "a-end", "b-start", "b-end"), order)
    }

    @Test
    fun `different slots do not block each other`() = runBlocking {
        val a = reserve(owner(conversation = "a"), 2)!!
        val b = reserve(owner(conversation = "b"), 2)!!
        assertNotEquals(a.slotId, b.slotId)
        var bRanInsideA = false
        LlamaSlotManager.withSlotRequest(a) {
            LlamaSlotManager.withSlotRequest(b) { bRanInsideA = true }
        }
        assertTrue(bRanInsideA)
    }

    @Test
    fun `withAssignedSlot still pins and releases`() = runBlocking {
        val slot = LlamaSlotManager.withAssignedSlot(owner(), 2, LlamaSlotAffinityMode.ENABLED) { it }
        assertEquals(0, slot)
        // Reservation released: the single slot can be taken over by another conversation.
        val again = LlamaSlotManager.withAssignedSlot(owner(conversation = "z"), 1, LlamaSlotAffinityMode.ENABLED) { it }
        assertNotNull(again)
    }

    @Test
    fun `invalidate by physical endpoint and by generation both clear assignments`() {
        reserve(owner(generation = "g1", conversation = "a", physical = "http://h:1"))
        reserve(owner(generation = "g2", conversation = "b", physical = null))
        LlamaSlotManager.invalidateEndpointGeneration("http://h:1")
        assertEquals(listOf("b"), LlamaSlotManager.snapshotAssignments().map { it.owner.conversationId })
        LlamaSlotManager.invalidateEndpointGeneration("g2")
        assertTrue(LlamaSlotManager.snapshotAssignments().isEmpty())
    }

    @Test
    fun `endpoint normalization unifies spelling`() {
        assertEquals("http://127.0.0.1:8080", normalizePhysicalEndpoint("HTTP://LocalHost:8080/"))
        assertEquals("http://127.0.0.1:8080", normalizePhysicalEndpoint("http://127.0.0.1:8080/v1"))
        assertEquals("https://example.com", normalizePhysicalEndpoint("https://Example.com/"))
        assertEquals("http://[fe80::1]:9", normalizePhysicalEndpoint("http://[fe80::1]:9"))
    }
}
