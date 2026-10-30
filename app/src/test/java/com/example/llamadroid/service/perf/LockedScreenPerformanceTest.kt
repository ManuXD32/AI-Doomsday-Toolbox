package com.example.llamadroid.service.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockedScreenPerformanceTest {
    @Test
    fun cpuListParsesRangesAndSingles() {
        assertEquals(listOf(0, 1, 2, 3, 6), CpuList.parse("0-3,6"))
        assertEquals(listOf(0, 4), CpuList.parse(" 4 , 0 ,junk, 3-1"))
        assertEquals(emptyList<Int>(), CpuList.parse(null))
        assertEquals("0-3,6", CpuList.format(listOf(6, 3, 2, 1, 0, 0)))
        assertEquals("", CpuList.format(emptyList()))
        assertEquals("0-5", CpuList.parseAllowedListFromStatus("Name:\tx\nCpus_allowed_list:\t0-5\n"))
    }

    @Test
    fun aggregatorKeepsRollingWindowPerMode() {
        val agg = LockedScreenSpeedAggregator(windowSize = 2)
        agg.record(8080, true, 8.0, "0-7")
        agg.record(8080, true, 6.0, "0-7")
        agg.record(8080, true, 10.0, "0-7")
        agg.record(8080, false, 3.0, "0-5")
        agg.record(8080, false, 0.0, "ignored")
        val stats = agg.stats(8080)!!
        assertEquals(8.0, stats.screenOnTps!!, 1e-9)
        assertEquals(2, stats.screenOnSamples)
        assertEquals(3.0, stats.screenOffTps!!, 1e-9)
        assertEquals("0-5", stats.screenOffCpus)
        assertNull(agg.stats(9))
    }

    @Test
    fun topologyClassifiesClusters() {
        val classes = CpuTopology.classify(mapOf(0 to 1800L, 1 to 1800L, 2 to 2400L, 3 to 3000L))
        assertEquals(CoreClass.LITTLE, classes[0])
        assertEquals(CoreClass.BIG, classes[2])
        assertEquals(CoreClass.PRIME, classes[3])
        assertEquals(CoreMix(1, 1, 1), CpuTopology.mix(listOf(0, 2, 3), classes))
    }
}
