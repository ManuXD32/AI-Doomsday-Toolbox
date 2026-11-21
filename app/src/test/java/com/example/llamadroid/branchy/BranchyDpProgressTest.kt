package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Data-parallel progress visibility (A6): dp_setup parsing, dp settings in cluster.json, setup state reducer. */
class BranchyDpProgressTest {
    private fun ev(json: String) = BranchyEventParser.parse("BRANCHY_EVENT $json")

    private val helper = BranchyClusterDeviceEntity(
        id = "a-50210", name = "Phone A", host = "10.0.0.5", port = 50210, role = "train",
        gflops = 20.0, ramMb = 4000, lastSeen = 0L
    )

    private fun dpPlan(syncEvery: Int? = null, deltaBits: Int? = null) = BranchyPlan(
        fits = true, mode = "data_parallel", syncEvery = syncEvery, deltaBits = deltaBits,
        placement = listOf(
            BranchyPlacement("local", "coordinator", listOf(0, 1)),
            BranchyPlacement("a-50210", "worker", listOf(0, 1))
        )
    )

    @Test
    fun parsesDpSetup() {
        val e = ev("""{"type":"dp_setup","phase":"shards","device":"a-50210","done_bytes":512,"total_bytes":2048}""")
        assertEquals(BranchyEvent.DpSetup("shards", "a-50210", 512L, 2048L), e)
    }

    @Test
    fun clusterJsonCarriesDpSettingsFromPlan() {
        val ready = BranchyClusterRun.clusterJson("r", dpPlan(32, 4), listOf(helper)) as ClusterJsonResult.Ready
        val dp = Json.parseToJsonElement(ready.json).jsonObject["dp"]!!.jsonObject
        assertEquals(32, dp["sync_every"]!!.jsonPrimitive.int)
        assertEquals(4, dp["bits"]!!.jsonPrimitive.int)
        assertEquals(60, dp["sync_seconds"]!!.jsonPrimitive.int)
        assertNull(dp["round_timeout_ms"])
    }

    @Test
    fun clusterJsonDpFallsBackTo64And8() {
        val ready = BranchyClusterRun.clusterJson("r", dpPlan(), listOf(helper)) as ClusterJsonResult.Ready
        val dp = Json.parseToJsonElement(ready.json).jsonObject["dp"]!!.jsonObject
        assertEquals(64, dp["sync_every"]!!.jsonPrimitive.int)
        assertEquals(8, dp["bits"]!!.jsonPrimitive.int)
    }

    @Test
    fun setupProgressIsShownThenClearedOnFirstStep() {
        val base = BranchyLiveState(devices = listOf(BranchyLiveDevice("a-50210", "Phone A")))
        val s1 = BranchyTrainingState.applyClusterEvent(base, BranchyEvent.DpSetup("shards", "a-50210", 512L, 2048L))
        assertEquals(BranchyDpSetup("Phone A", 25), s1.dpSetup)
        val zero = BranchyTrainingState.applyClusterEvent(s1, BranchyEvent.DpSetup("start", "b", 5L, 0L))
        assertEquals(BranchyDpSetup("b", 0), zero.dpSetup)
        val step = BranchyEvent.Step(1, 2.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertNull(BranchyTrainingState.applyClusterEvent(s1, step).dpSetup)
        assertTrue(BranchyTrainingState.applyClusterEvent(base, step).dpSetup == null)
    }
}
