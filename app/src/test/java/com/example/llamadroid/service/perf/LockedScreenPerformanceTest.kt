package com.example.llamadroid.service.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun tuneReducesThreadsKeepsBatchAndNeverAddsPrio() {
        val result = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "-t", "8", "-c", "4096"), options, "0-5"
        )
        assertEquals("6", result.args[result.args.indexOf("-t") + 1])
        assertEquals("8", result.args[result.args.indexOf("--threads-batch") + 1])
        assertFalse(result.args.contains("--prio"))
        assertFalse(result.args.contains("--prio-batch"))
        assertEquals(PriorityState.REQUESTED, result.priorityState)
    }

    @Test
    fun tuneKeepsExplicitBatchAndUserTypedPrio() {
        val result = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "--threads", "8", "--threads-batch", "3", "--prio", "1"),
            options, "0-3"
        )
        assertEquals(
            listOf("llama-server", "--threads", "4", "--threads-batch", "3", "--prio", "1"),
            result.args
        )
        val off = LockedScreenLaunchTuning.tune(
            listOf("llama-server", "-t", "8"), options.copy(threadFit = false, threadPriority = false), "0-1"
        )
        assertEquals(listOf("llama-server", "-t", "8"), off.args)
        assertEquals(PriorityState.OFF, off.priorityState)
    }

    @Test
    fun niceParserHandlesOddCommandNames() {
        val tail = "S 1 100 100 0 -1 4194560 100 0 0 0 5 6 0 0 20 -8 12 0 12345 1 2"
        assertEquals(-8, ThreadPriorityMath.niceOf("4242 (llama-server) $tail"))
        assertEquals(-8, ThreadPriorityMath.niceOf("4243 (a b) (c) $tail"))
        assertEquals(0, ThreadPriorityMath.niceOf("4244 (x) S 1 100 100 0 -1 4194560 100 0 0 0 5 6 0 0 20 0 12 0 1"))
        assertNull(ThreadPriorityMath.niceOf("garbage"))
        assertNull(ThreadPriorityMath.niceOf("1 (x) S 1 2"))
    }

    @Test
    fun priorityClassification() {
        assertEquals(PriorityState.REQUESTED, ThreadPriorityMath.classify(emptyList()).state)
        assertEquals(PriorityState.APPLIED, ThreadPriorityMath.classify(listOf(-8, -8, -8, -8, 0)).state)
        assertEquals(PriorityState.APPLIED, ThreadPriorityMath.classify(listOf(-10, -8)).state)
        assertEquals(PriorityState.PARTIAL, ThreadPriorityMath.classify(listOf(-8, 0, 0)).state)
        assertEquals(PriorityOutcome(PriorityState.PARTIAL, 1, 3), ThreadPriorityMath.classify(listOf(-8, 0, 10)))
        assertEquals(PriorityState.REFUSED, ThreadPriorityMath.classify(listOf(0, 0, 10)).state)
    }

    @Test
    fun scanRaisesEachThreadOnceAndReadsNiceBack() {
        val root = java.nio.file.Files.createTempDirectory("prio-proc").toFile()
        try {
            fun task(tid: Int, nice: Int) {
                val dir = java.io.File(root, "77/task/$tid").apply { mkdirs() }
                java.io.File(dir, "stat").writeText("$tid (llama srv) S 1 1 1 0 -1 0 0 0 0 0 1 1 0 0 20 $nice 1 0 1")
            }
            task(10, -8); task(11, -8); task(12, 0)
            val calls = mutableListOf<Int>()
            val raiser = LockedScreenThreadPriority(77, {}, root, setPriority = { calls += it; true })
            assertEquals(PriorityOutcome(PriorityState.PARTIAL, 2, 3), raiser.scanOnce())
            assertEquals(3, calls.size)
            raiser.scanOnce()
            assertEquals(3, calls.size)
            task(13, -8)
            raiser.scanOnce()
            assertEquals(listOf(13), calls.drop(3))
        } finally {
            root.deleteRecursively()
        }
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
