package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdDiffusionGreedyFillTest {
    private val gib = 1024L * 1024L * 1024L

    private fun workers() = listOf(
        SdDistributedPlanningWorker(host = "10.0.0.2", port = 50062, displayName = "Large", ramMB = 8192, threads = 8, rpcIndex = 0),
        SdDistributedPlanningWorker(host = "10.0.0.3", port = 50062, displayName = "Small", ramMB = 4096, threads = 4, rpcIndex = 1)
    )

    private fun share(plan: SdDistributedPlacementPlan) =
        plan.assignments.first { it.module == SdDistributedModules.DIFFUSION }.estimatedLayerShares

    @Test
    fun `model fitting on first device gives 100 and 0 but keeps both devices`() {
        val plan = buildRamWeightedSdPlacementPlan(workers(), SdRamPlannerOptions(diffusionModelBytes = 2 * gib))
        assertEquals(mapOf("RPC0" to 100, "RPC1" to 0), share(plan))
        assertEquals("10.0.0.2:50062,10.0.0.3:50062", plan.rpcServers)
        assertTrue(plan.backendSpec.contains("diffusion=RPC0&RPC1"))
    }

    @Test
    fun `overflow spills to the next device by bytes`() {
        // RPC0 capacity = 8192 - 512 overhead = 7680 MiB (7.5 GiB); model 10 GiB -> 75 / 25.
        val plan = buildRamWeightedSdPlacementPlan(workers(), SdRamPlannerOptions(diffusionModelBytes = 10 * gib))
        assertEquals(mapOf("RPC0" to 75, "RPC1" to 25), share(plan))
    }

    @Test
    fun `unknown model size is proportional to capacity and sums to 100`() {
        val plan = buildRamWeightedSdPlacementPlan(workers())
        val s = share(plan)
        assertEquals(100, s.values.sum())
        assertEquals(mapOf("RPC0" to 65, "RPC1" to 35), s) // 7680 : 4096
    }

    @Test
    fun `flags are identical regardless of model size`() {
        val a = buildRamWeightedSdPlacementPlan(workers())
        val b = buildRamWeightedSdPlacementPlan(workers(), SdRamPlannerOptions(diffusionModelBytes = 1 * gib))
        assertEquals(a.rpcServers, b.rpcServers)
        assertEquals(a.backendSpec, b.backendSpec)
        assertEquals(a.maxVramSpec, b.maxVramSpec)
    }

    @Test
    fun `transfer compression env only when enabled with rpc servers`() {
        val base = SdDistributedRuntimeConfig(enabled = true, rpcServers = "a:1")
        assertFalse(sdRpcClientEnvironment(base).containsKey(SD_LAYER_SPLIT_TRANSFER_TYPE_ENV))
        assertEquals("bf16", sdRpcClientEnvironment(base.copy(compressLayerTransfers = true))[SD_LAYER_SPLIT_TRANSFER_TYPE_ENV])
        assertFalse(sdRpcClientEnvironment(base.copy(rpcServers = "", compressLayerTransfers = true)).containsKey(SD_LAYER_SPLIT_TRANSFER_TYPE_ENV))
        assertFalse(sdRpcClientEnvironment(base.copy(enabled = false, compressLayerTransfers = true)).containsKey(SD_LAYER_SPLIT_TRANSFER_TYPE_ENV))
    }
}
