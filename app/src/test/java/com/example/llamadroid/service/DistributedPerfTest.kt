package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedPerfTest {
    private val master = DistributedDeviceRef(DistributedDeviceKind.MASTER_CPU)
    private fun worker(id: Long) = DistributedDeviceRef(DistributedDeviceKind.WORKER, id, "w$id:50052")

    @Test
    fun defaultsAreCacheOnDebugOffUbatch512() {
        assertTrue(DistributedPerf.DEFAULT_WORKER_CACHE)
        assertFalse(DistributedPerf.DEFAULT_RPC_DEBUG)
        assertNull(DistributedPerf.rpcDebugEnvValue(DistributedPerf.DEFAULT_RPC_DEBUG))
        assertEquals("1", DistributedPerf.rpcDebugEnvValue(true))
        assertEquals(512, DistributedPerf.DEFAULT_UBATCH)
    }

    @Test
    fun plannerFillsMasterFirstThenLargestWorkerOnly() {
        // 10 GiB model, 20 layers; per layer 1.1 * 512 = 563 MiB.
        val plan = DistributedLayerPlanner.plan(
            layers = 20,
            modelSizeMiB = 10240,
            devices = listOf(
                DistributedPlanDevice(master, 4096),
                DistributedPlanDevice(worker(1), 2048),
                DistributedPlanDevice(worker(2), 8192),
                DistributedPlanDevice(worker(3), 3072)
            )
        )
        val byKey = plan.allocations.associate { it.device.stableKey() to it.assignedLayers }
        assertEquals(20, byKey.values.sum())
        assertEquals(7, byKey["master_cpu"])
        // Remaining 13 layers fit on worker 2 alone (14 layers capacity): one hop only.
        assertEquals(13, byKey["worker:2"])
        assertEquals(2, plan.allocations.size)
    }

    @Test
    fun plannerPicksSmallestWorkerThatFitsRemainder() {
        val plan = DistributedLayerPlanner.plan(
            layers = 10,
            modelSizeMiB = 10000,
            devices = listOf(
                DistributedPlanDevice(master, 5600),
                DistributedPlanDevice(worker(1), 12000),
                DistributedPlanDevice(worker(2), 2200)
            )
        )
        // Master holds 5 layers (1100 MiB each); the remaining 5 do not fit worker 2 (2 layers), so worker 1 takes them.
        val byKey = plan.allocations.associate { it.device.stableKey() to it.assignedLayers }
        assertEquals(5, byKey["master_cpu"])
        assertEquals(5, byKey["worker:1"])
        assertFalse(byKey.containsKey("worker:2"))
    }

    @Test
    fun plannerWarnsOnTinyShareAndInsufficientRamAndFit() {
        val tiny = DistributedLayerPlanner.plan(
            layers = 40,
            modelSizeMiB = 4000,
            devices = listOf(
                DistributedPlanDevice(master, 4100),
                DistributedPlanDevice(worker(1), 6000)
            )
        )
        val tinyWarn = tiny.warnings.first { it.type == DistributedPlanWarningType.TINY_SHARE }
        assertTrue(tinyWarn.sharePercent!! < 10)

        val small = DistributedLayerPlanner.plan(
            layers = 10, modelSizeMiB = 1000,
            devices = listOf(DistributedPlanDevice(master, 4000), DistributedPlanDevice(worker(1), 4000))
        )
        assertTrue(small.warnings.any { it.type == DistributedPlanWarningType.MODEL_FITS_ON_MASTER })
        assertEquals(listOf(10), small.allocations.map { it.assignedLayers })

        val big = DistributedLayerPlanner.plan(
            layers = 10, modelSizeMiB = 10000,
            devices = listOf(DistributedPlanDevice(master, 2000), DistributedPlanDevice(worker(1), 2000))
        )
        assertTrue(big.warnings.any { it.type == DistributedPlanWarningType.INSUFFICIENT_RAM })
        assertEquals(10, big.allocations.sumOf { it.assignedLayers })
    }

    @Test
    fun networkBoundWarningUsesRttAndComputeEstimate() {
        // 4096 MiB model: compute ~ 214 ms/token at the assumed 20 GB/s.
        assertEquals(214.7, DistributedPerf.estimateComputeMsPerToken(4096), 0.5)
        val devices = listOf(DistributedPlanDevice(master, 1000), DistributedPlanDevice(worker(1), 8000))
        val slow = DistributedLayerPlanner.plan(20, 4096, devices, mapOf("w1:50052" to 80L))
        assertTrue(slow.warnings.any { it.type == DistributedPlanWarningType.NETWORK_BOUND })
        val fast = DistributedLayerPlanner.plan(20, 4096, devices, mapOf("w1:50052" to 5L))
        assertFalse(fast.warnings.any { it.type == DistributedPlanWarningType.NETWORK_BOUND })
    }

    @Test
    fun medianRttUsesLastFiveSamples() {
        assertNull(DistributedPerf.medianRttMs(emptyList()))
        assertEquals(7L, DistributedPerf.medianRttMs(listOf(500, 9, 7, 5, 8, 6)))
        var samples = emptyList<Long>()
        (1L..8L).forEach { samples = DistributedPerf.appendRttSample(samples, it) }
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), samples)
        assertEquals(6L, DistributedPerf.medianRttMs(samples))
    }

    @Test
    fun nsdTxtParsesAdvertAndFallsBackWhenMissing() {
        val advert = WorkerAdvert.parse(
            mapOf(
                "ram_free_mb" to "6144".toByteArray(),
                "cores" to "8".toByteArray(),
                "app_ver" to "1.00002".toByteArray()
            )
        )
        assertEquals(6144, advert.ramOrDefault())
        assertEquals(8, advert.cores)
        assertEquals("1.00002", advert.appVersion)
        assertEquals(advert, WorkerAdvert.parse(advert.toAttributes().mapValues { it.value.toByteArray() }))

        val missing = WorkerAdvert.parse(null)
        assertEquals(4096, missing.ramOrDefault())
        val garbage = WorkerAdvert.parse(mapOf("ram_free_mb" to "abc".toByteArray(), "cores" to "-3".toByteArray()))
        assertNull(garbage.ramFreeMb)
        assertNull(garbage.cores)
        assertEquals(4096, garbage.ramOrDefault())
    }

    @Test
    fun oldPersistedPlanningNamesAreMigrated() {
        assertEquals(DistributedLayerPlanning.OPTIMAL_NETWORK, DistributedLayerPlanning.fromStored("FEWEST_HOPS"))
        assertEquals(DistributedLayerPlanning.RAM_PROPORTIONAL, DistributedLayerPlanning.fromStored("PROPORTIONAL"))
        assertEquals(DistributedLayerPlanning.PERCENT, DistributedLayerPlanning.fromStored("PERCENT"))
        assertEquals(DistributedLayerPlanning.LAYERS, DistributedLayerPlanning.fromStored("LAYERS"))
        assertEquals(DistributedLayerPlanning.OPTIMAL_NETWORK, DistributedLayerPlanning.fromStored(null))
        assertEquals(DistributedLayerPlanning.OPTIMAL_NETWORK, DistributedLayerPlanning.fromStored("junk"))
    }

    @Test
    fun percentRoundingAlwaysSumsToTheLayerTotal() {
        for (layers in listOf(1, 7, 37, 61, 100)) {
            val result = DistributedManualSplit.percentToLayers(listOf(33, 33, 34), layers)
            assertEquals(layers, result.sum())
        }
        assertEquals(listOf(19, 11, 7), DistributedManualSplit.percentToLayers(listOf(50, 30, 20), 37))
    }

    @Test
    fun manualSplitValidation() {
        val layersMode = DistributedLayerPlanning.LAYERS
        assertEquals(
            DistributedManualSplit.Result.Valid(listOf(20, 17)),
            DistributedManualSplit.evaluate(layersMode, listOf(20, 17), 37)
        )
        val short = DistributedManualSplit.evaluate(layersMode, listOf(20, 10), 37)
        assertEquals(DistributedManualSplit.Result.Invalid(DistributedManualSplit.Reason.TOTAL_MISMATCH, 30), short)
        val missing = DistributedManualSplit.evaluate(layersMode, listOf(20, null), 37)
        assertEquals(DistributedManualSplit.Reason.INVALID_VALUE, (missing as DistributedManualSplit.Result.Invalid).reason)
        val zero = DistributedManualSplit.evaluate(layersMode, listOf(37, 0), 37)
        assertEquals(DistributedManualSplit.Reason.INVALID_VALUE, (zero as DistributedManualSplit.Result.Invalid).reason)
        val percent = DistributedLayerPlanning.PERCENT
        assertTrue(DistributedManualSplit.evaluate(percent, listOf(60, 40), 37) is DistributedManualSplit.Result.Valid)
        assertEquals(
            DistributedManualSplit.Reason.TOTAL_MISMATCH,
            (DistributedManualSplit.evaluate(percent, listOf(60, 30), 37) as DistributedManualSplit.Result.Invalid).reason
        )
        val tiny = DistributedManualSplit.evaluate(percent, listOf(99, 1), 10)
        assertEquals(DistributedManualSplit.Reason.ZERO_LAYERS, (tiny as DistributedManualSplit.Result.Invalid).reason)
        assertEquals(1, tiny.deviceIndex)
    }
}
