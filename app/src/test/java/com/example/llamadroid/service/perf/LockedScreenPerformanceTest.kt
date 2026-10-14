package com.example.llamadroid.service.perf

import com.example.llamadroid.service.LlamaBinaryCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedScreenPerformanceTest {
    private val options = LockedScreenOptions(threadFit = true, threadPriority = true, performanceHints = true)

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
    fun threadFitDecisions() {
        assertEquals(6, LockedScreenLaunchTuning.decideThreadFit(8, 6, true).effective)
        assertEquals(ThreadFitReason.REDUCED, LockedScreenLaunchTuning.decideThreadFit(8, 6, true).reason)
        assertEquals(ThreadFitReason.FITS, LockedScreenLaunchTuning.decideThreadFit(4, 6, true).reason)
        assertEquals(8, LockedScreenLaunchTuning.decideThreadFit(8, null, true).effective)
        assertEquals(ThreadFitReason.UNKNOWN_CPUSET, LockedScreenLaunchTuning.decideThreadFit(8, null, true).reason)
        assertEquals(8, LockedScreenLaunchTuning.decideThreadFit(8, 2, false).effective)
    }

    @Test
    fun tuneReducesThreadsKeepsBatchAndAddsPriority() {
        val caps = LlamaBinaryCapabilities(setOf("--prio", "--prio-batch"))
        val result = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "-t", "8", "-c", "4096"), options, caps, "0-5"
        )
        assertEquals("6", result.args[result.args.indexOf("-t") + 1])
        assertEquals("8", result.args[result.args.indexOf("--threads-batch") + 1])
        assertEquals("2", result.args[result.args.indexOf("--prio") + 1])
        assertEquals("2", result.args[result.args.indexOf("--prio-batch") + 1])
        assertEquals(PriorityState.REQUESTED, result.priorityState)
    }

    @Test
    fun tuneKeepsExplicitBatchAndSkipsUnsupportedPriority() {
        val result = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "--threads", "8", "--threads-batch", "3"),
            options, LlamaBinaryCapabilities(setOf("--threads")), "0-3"
        )
        assertEquals(listOf("llama-server", "--threads", "4", "--threads-batch", "3"), result.args)
        assertEquals(PriorityState.UNSUPPORTED, result.priorityState)
        val off = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "-t", "8"), options.copy(threadFit = false, threadPriority = false),
            LlamaBinaryCapabilities.ALLOW_ALL, "0-1"
        )
        assertEquals(listOf("llama-server", "-t", "8"), off.args)
        assertEquals(PriorityState.OFF, off.priorityState)
        val allowAll = LockedScreenLaunchTuning.tune(listOf("x", "-t", "2"), options, LlamaBinaryCapabilities.ALLOW_ALL, null)
        assertEquals(PriorityState.UNSUPPORTED, allowAll.priorityState)
    }

    @Test
    fun priorityWarningDetection() {
        assertTrue(LockedScreenLaunchTuning.isPriorityWarning("warn: failed to set thread priority 2 : Operation not permitted (1)"))
        assertFalse(LockedScreenLaunchTuning.isPriorityWarning("server is listening on http://127.0.0.1:8080"))
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
    fun hintMathReadsStatAndBusiestThread() {
        val line = "123 (llama (x) srv) S 1 2 3 4 5 6 7 8 9 10 40 2 0 0 20 0"
        assertEquals(42L, HintSessionMath.cpuTicks(line))
        assertEquals(30_000_000L, HintSessionMath.busiestThreadNanos(mapOf(1 to 10L, 2 to 5L), mapOf(1 to 13L, 2 to 6L, 3 to 99L), 10_000_000L))
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
