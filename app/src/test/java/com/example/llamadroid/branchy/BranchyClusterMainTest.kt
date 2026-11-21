package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterMode
import com.example.llamadroid.ui.training.BranchyWizardHelpers
import com.example.llamadroid.ui.training.BranchyWizardState
import com.example.llamadroid.ui.training.ClusterDeviceEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Main-device side of the Branchy training cluster: argv, plan input, events, live state, cluster.json. */
class BranchyClusterMainTest {
    private val exe = "/bin/branchy"

    private fun helper(id: String, host: String, ram: Int? = 4000, gflops: Double? = 20.0) = BranchyClusterDeviceEntity(
        id = id, name = "Phone $host", host = host, port = 50210, role = "train", gflops = gflops, ramMb = ram, lastSeen = 0L
    )

    private fun ev(json: String) = BranchyEventParser.parse("BRANCHY_EVENT $json")

    @Test
    fun trainClusterArgvKeepsTheTokenOutOfArgv() {
        val argv = BranchyCommandBuilder.trainCluster(exe, "/r", "/r/cluster.json", "/f/token", true, null, 4)
        assertEquals(
            listOf(exe, "train", "--run", "/r", "--cluster", "/r/cluster.json", "--resume", "--threads", "4", "--token-file", "/f/token"),
            argv
        )
        val init = BranchyCommandBuilder.trainCluster(exe, "/r", "/r/c.json", "/f/t", false, "/r/ck", 0)
        assertEquals(listOf(exe, "train", "--run", "/r", "--cluster", "/r/c.json", "--init-from", "/r/ck", "--token-file", "/f/t"), init)
    }

    @Test
    fun planInputListsThisPhoneFirstThenHelpers() {
        val state = BranchyWizardState(
            clusterDeviceIds = listOf("a-50210"), clusterMode = "data_parallel",
            deviceEdits = mapOf("a-50210" to ClusterDeviceEdit(ramMb = "6000", gflops = "", linkMbps = "200"))
        )
        val input = BranchyWizardHelpers.buildPlanInput(state, BranchyDevice(ramMb = 5000, gflops = 40.0), listOf(helper("a-50210", "a")))
        assertEquals("data_parallel", input.mode)
        assertEquals(listOf("local", "a-50210"), input.devices.map { it.id })
        val h = input.devices[1]
        assertEquals(6000L, h.ramMb)
        assertEquals(20.0, h.gflops, 1e-9)
        assertEquals(200.0, h.linkMbps, 1e-9)
        val json = BranchyJson.json.encodeToString(BranchyPlanInput.serializer(), input)
        assertTrue(json.contains("\"link_mbps\":200.0"))
        assertTrue(json.contains("\"mode\":\"data_parallel\""))
    }

    @Test
    fun singleDevicePathIsUnchangedWithoutHelpers() {
        val input = BranchyWizardHelpers.buildPlanInput(BranchyWizardState(clusterMode = "data_parallel"), BranchyDevice())
        assertEquals("auto", input.mode)
        assertEquals(1, input.devices.size)
        val cfg = BranchyWizardHelpers.customConfig(
            BranchyWizardState(weights = mapOf(1L to 1f), plan = com.example.llamadroid.ui.training.BranchyPlanUi.Ready(BranchyPlan(fits = true))),
            BranchyDevice(), 2
        )
        assertNull(cfg?.cluster)
    }

    @Test
    fun unknownSpeedGetsTheFallbackAndDefaultLinkIs50() {
        val devices = BranchyClusterRun.planDevices(BranchyDevice(), listOf(helper("b-50210", "b", ram = null, gflops = null)))
        assertEquals(BranchyClusterRun.FALLBACK_GFLOPS, devices[1].gflops, 1e-9)
        assertEquals(50.0, devices[1].linkMbps, 1e-9)
        assertEquals(listOf("b-50210"), BranchyClusterRun.helpersWithoutRam(listOf(helper("b-50210", "b", ram = null)), emptyMap()).map { it.id })
        assertTrue(BranchyClusterRun.helpersWithoutRam(listOf(helper("b-50210", "b", ram = null)), mapOf("b-50210" to 3000L)).isEmpty())
    }

    @Test
    fun plannerClusterFieldsAreDecoded() {
        val e = ev(
            """{"type":"plan","fits":true,"mode":"branch_parallel","pipeline_depth":2,"overlap":false,"speedup":1.5,"limit":"link",""" +
                """"links":[{"id":"a-50210","branches":2,"need_mbps":120.5,"link_mbps":50,"slow":true}],""" +
                """"placement":[{"device":"local","role":"coordinator","branches":[0,1]},{"device":"a-50210","role":"worker","branches":[2,3]}]}"""
        ) as BranchyEvent.Plan
        val plan = e.plan!!
        assertTrue(plan.isCluster)
        assertEquals(1.5, plan.speedup, 1e-9)
        assertEquals("link", plan.limit)
        assertTrue(plan.links.single().slow)
        assertFalse(BranchyPlan().isCluster)
        assertEquals(listOf("zz"), BranchyClusterRun.missingEndpoints(plan.copy(placement = plan.placement + BranchyPlacement("zz", "worker", listOf(4))), listOf("a-50210")))
    }

    @Test
    fun clusterJsonFromPlan() {
        val plan = BranchyPlan(
            fits = true, mode = "branch_parallel", pipelineDepth = 2,
            placement = listOf(
                BranchyPlacement("local", "coordinator", listOf(0, 1)),
                BranchyPlacement("a-50210", "worker", listOf(2, 3))
            )
        )
        val ready = BranchyClusterRun.clusterJson("My Run", plan, listOf(helper("a-50210", "10.0.0.5"))) as ClusterJsonResult.Ready
        assertEquals(BranchyClusterMode.BRANCH_PARALLEL, ready.mode)
        assertTrue(ready.json.contains("\"10.0.0.5\""))
        assertTrue(ready.json.contains("\"branch_parallel\""))
        assertFalse(ready.json.contains("\"threads\""))
        val withThreads = BranchyClusterRun.clusterJson("r", plan, listOf(helper("a-50210", "10.0.0.5")), null, mapOf("a-50210" to 6)) as ClusterJsonResult.Ready
        assertTrue(withThreads.json.replace(" ", "").contains("\"threads\":6"))
        assertEquals(ClusterJsonResult.MissingEndpoints(listOf("a-50210")), BranchyClusterRun.clusterJson("r", plan, emptyList()))
        assertEquals(ClusterJsonResult.NotNeeded, BranchyClusterRun.clusterJson("r", BranchyPlan(fits = true), emptyList()))
        assertEquals(BranchyClusterMode.DATA_PARALLEL, BranchyClusterRun.mode(plan.copy(mode = "data_parallel")))
    }

    @Test
    fun clusterEventsAreParsed() {
        val c = ev(
            """{"type":"cluster","devices":[{"id":"a","host":"h","port":50210,"branches":[2,3],"state":"up"},""" +
                """{"id":"b","host":"i","port":50211,"branches":[1],"state":"lost"}],"wire":"bf16","pipeline_depth":2}"""
        ) as BranchyEvent.Cluster
        assertEquals(listOf(true, false), c.devices.map { it.up })
        assertEquals(listOf(2, 3), c.devices[0].branches)
        assertEquals(BranchyEvent.WorkerLost("a", "timeout", 12L), ev("""{"type":"worker_lost","id":"a","reason":"timeout","step":12}"""))
        assertEquals(BranchyEvent.WorkerJoined("a", 20L), ev("""{"type":"worker_joined","id":"a","step":20}"""))
        val sync = ev("""{"type":"sync","round":3,"bytes":2048,"workers":2,"steps":{"a":64,"coordinator":60}}""") as BranchyEvent.Sync
        assertEquals(3L, sync.round)
        assertEquals(64L, sync.stepsByDevice["a"])
        val dp = ev("""{"type":"dp_start","mode":"dp","replicas":3,"bits":8,"sync_every":64,"params":1000}""") as BranchyEvent.DpStart
        assertEquals(3, dp.replicas)
        val step = ev("""{"type":"step","step":5,"loss":2.5,"step_ms_by_device":{"a":120.0,"b":80}}""") as BranchyEvent.Step
        assertEquals(120.0, step.stepMsByDevice["a"]!!, 1e-9)
        assertTrue((ev("""{"type":"step","step":1}""") as BranchyEvent.Step).stepMsByDevice.isEmpty())
    }

    @Test
    fun liveStateFollowsClusterEvents() {
        val names = mapOf("a" to "Kitchen phone")
        var s = BranchyLiveState(clusterMode = "branch_parallel")
        s = BranchyTrainingState.applyClusterEvent(
            s, BranchyEvent.Cluster(listOf(BranchyEvent.ClusterDevice("a", "h", 1, listOf(2), true), BranchyEvent.ClusterDevice("b", "i", 2, listOf(3), true)), "f32", 1),
            names
        )
        assertEquals(listOf("Kitchen phone", "b"), s.devices.map { it.name })
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.WorkerLost("a", "timeout", 5L))
        assertFalse(s.devices[0].up)
        assertEquals("timeout", s.devices[0].lostReason)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.WorkerJoined("a", 9L))
        assertTrue(s.devices[0].up)
        assertNull(s.devices[0].lostReason)
        val step = BranchyEvent.Step(1, 2.0, null, 0.1, 1.0, 5.0, 1.0, 2.0, 100.0, mapOf("b" to 77.0))
        s = BranchyTrainingState.applyClusterEvent(s, step)
        assertEquals(77.0, s.devices[1].stepMs, 1e-9)
        assertEquals(0.0, s.devices[0].stepMs, 1e-9)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.Sync(4L, 4096L, 2, null, null, emptyMap()))
        assertEquals(4L, s.syncRound)
        assertEquals(2, s.syncWorkers)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.DpStart(3, 8, 64, 1000L))
        assertEquals("data_parallel", s.clusterMode)
        assertNotNull(s.devices)
    }

    @Test
    fun runConfigKeepsTheClusterSelection() {
        val cfg = BranchyRunConfig(cluster = ClusterSelection(listOf("a-50210"), "branch_parallel"))
        val back = BranchyRunConfig.decode(cfg.encode())!!
        assertEquals(listOf("a-50210"), back.cluster?.deviceIds)
        assertNull(BranchyRunConfig.decode(BranchyRunConfig().encode())!!.cluster)
    }
}
