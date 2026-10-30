package com.example.llamadroid.service.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotCapacityCacheTest {
    @Test fun parsesTotalSlotsFromProps() {
        assertEquals(4, SlotCapacityCache.parseTotalSlots("""{"total_slots":4,"model":"x"}"""))
        assertNull(SlotCapacityCache.parseTotalSlots("""{"model":"x"}"""))
        assertNull(SlotCapacityCache.parseTotalSlots("""{"total_slots":0}"""))
        assertNull(SlotCapacityCache.parseTotalSlots("not json"))
    }

    @Test fun cachesForTenMinutesAndRefetchesAfterwards() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = SlotCapacityCache(fetch = { calls++; 3 }, nowMs = { now })
        assertEquals(3, cache.capacity("k", "http://x"))
        now = SlotCapacityCache.TTL_MS - 1
        assertEquals(3, cache.capacity("k", "http://x"))
        assertEquals(1, calls)
        now = SlotCapacityCache.TTL_MS + 1
        cache.capacity("k", "http://x")
        assertEquals(2, calls)
    }

    @Test fun failuresAreCachedBrieflyAndReportedAsUnknown() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = SlotCapacityCache(fetch = { calls++; error("offline") }, nowMs = { now })
        assertNull(cache.capacity("k", "http://x"))
        assertNull(cache.capacity("k", "http://x"))
        assertEquals(1, calls)
        now = SlotCapacityCache.FAILURE_TTL_MS + 1
        assertNull(cache.capacity("k", "http://x"))
        assertEquals(2, calls)
    }
}
