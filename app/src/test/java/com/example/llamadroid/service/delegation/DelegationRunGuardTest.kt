package com.example.llamadroid.service.delegation

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationRunGuardTest {
    private class Run

    @Test
    fun currentLoopUnregistersItself() {
        val map = ConcurrentHashMap<Long, Run>()
        val a = Run().also { map[1L] = it }
        assertTrue(DelegationRunGuard.endIfCurrent(map, 1L, a))
        assertFalse(map.containsKey(1L))
    }

    @Test
    fun replacedLoopLeavesTheNewRegistrationAlone() {
        val map = ConcurrentHashMap<Long, Run>()
        val old = Run()
        val fresh = Run().also { map[1L] = it }
        assertFalse(DelegationRunGuard.endIfCurrent(map, 1L, old))
        assertSame(fresh, map[1L])
        assertFalse(DelegationRunGuard.endIfCurrent(map, 2L, old))
        assertFalse(DelegationRunGuard.endIfCurrent(map, 1L, null))
        assertEquals(1, map.size)
    }
}
