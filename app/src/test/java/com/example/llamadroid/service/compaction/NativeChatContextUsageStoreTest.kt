package com.example.llamadroid.service.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeChatContextUsageStoreTest {
    @Test fun percentMath() {
        assertEquals(0, NativeChatContextUsage(1, 500, 0, false, updatedAt = 0).percent)
        assertEquals(50, NativeChatContextUsage(1, 500, 1000, true, updatedAt = 0).percent)
        assertEquals(100, NativeChatContextUsage(1, 5000, 1000, true, updatedAt = 0).percent)
        assertEquals(0, NativeChatContextUsage(1, -5, 1000, true, updatedAt = 0).percent)
    }

    @Test fun recordKeepsLastSaving() {
        val c = NativeChatContextUsageStore.applyCompaction(null, 7, 1200, false, 1L)
        assertEquals(1200, c.lastSavedTokens)
        val r = NativeChatContextUsageStore.applyRecord(c, 7, 300, 4000, true, 2L)
        assertEquals(1200, r.lastSavedTokens)
        assertEquals(300, r.usedTokens)
        assertEquals(7, NativeChatContextUsageStore.applyRecord(null, 7, -1, 10, false, 0).let { it.usedTokens + 7 })
        assertNull(NativeChatContextUsageStore.current(987654L))
    }
}
