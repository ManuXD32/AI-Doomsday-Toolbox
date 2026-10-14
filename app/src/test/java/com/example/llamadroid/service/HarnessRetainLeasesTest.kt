package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRetainLeasesTest {
    @Test fun releasingOneRuntimeKeepsTheLeaseWhileAnotherIsRetained() {
        val leases = HarnessRetainLeases()
        assertTrue(leases.acquire("a"))
        assertTrue(leases.acquire("b"))
        assertFalse(leases.acquire("a"))
        assertTrue(leases.release("a"))
        assertTrue(leases.isNotEmpty())
        assertFalse(leases.release("a"))
        assertTrue(leases.release("b"))
        assertTrue(leases.isEmpty())
        assertEquals(emptySet<String>(), leases.snapshot())
    }
}
