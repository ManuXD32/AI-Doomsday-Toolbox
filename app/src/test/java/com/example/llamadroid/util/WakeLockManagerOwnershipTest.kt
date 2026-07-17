package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockManagerOwnershipTest {
    @Test
    fun `two owners keep aggregate lock held when one releases`() {
        val state = OwnerLockState()

        assertEquals(1, state.acquire("LlamaService").totalCount)
        assertEquals(2, state.acquire("KiwixService").totalCount)

        val released = state.release("KiwixService")

        assertTrue(released.ownerHadReference)
        assertEquals(1, released.totalCount)
        assertEquals(1, state.totalCount())
    }

    @Test
    fun `unowned release is ignored and does not consume another owner`() {
        val state = OwnerLockState()
        state.acquire("LlamaService")

        val ignored = state.release("KiwixService")

        assertFalse(ignored.ownerHadReference)
        assertEquals(1, ignored.totalCount)
        assertEquals(1, state.totalCount())
    }

    @Test
    fun `same owner can acquire and release more than once`() {
        val state = OwnerLockState()
        state.acquire("LlamaClientService")
        state.acquire("LlamaClientService")

        val firstRelease = state.release("LlamaClientService")
        val secondRelease = state.release("LlamaClientService")

        assertTrue(firstRelease.ownerHadReference)
        assertEquals(1, firstRelease.totalCount)
        assertTrue(secondRelease.ownerHadReference)
        assertEquals(0, secondRelease.totalCount)
    }

    @Test
    fun `owner state can be reused for wifi lock semantics`() {
        val state = OwnerLockState()
        state.acquire("LlamaService")
        state.acquire("LlamaClientService")

        assertEquals(1, state.release("LlamaService").totalCount)
        assertFalse(state.release("KiwixService").ownerHadReference)
        assertEquals(1, state.totalCount())
        assertEquals(0, state.release("LlamaClientService").totalCount)
    }
}
