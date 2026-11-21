package com.example.llamadroid.branchy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BranchyRound6ServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ev(json: String) = BranchyEventParser.parse("BRANCHY_EVENT $json")

    private fun live(vararg ids: String) = BranchyLiveState(
        devices = ids.map { BranchyLiveDevice(it, it) }
    )

    private fun round(round: Long, vararg devs: BranchyEvent.DpRoundDevice) = BranchyEvent.DpRound(round, devs.toList())

    private fun dev(id: String, state: String?, mb: Long = 4L, seconds: Double = 2.0, idle: Double = 0.0) =
        BranchyEvent.DpRoundDevice(id, 1L, 0L, 0.0, 0.0, seconds, state = state, mb = mb, seconds = seconds, idleS = idle)

    // region parsing

    @Test
    fun dpRoundExtended() {
        val e = ev(
            """{"type":"dp_round","round":3,"round_s":12.5,"sync_s":1.5,"global_step":9,"gmb":72,"devices":[
            {"id":"main","state":"active","mb":8,"mb_done":8,"opt_steps":2,"seconds":10.0,"ms_per_mb":1250.0,"idle_s":0.5,"comm_s":0.1,"down_s":0.2,"tokens":1000,"tok_s":50,"rss_mb":300},
            {"id":"a","state":"idle_end","mb":4,"mb_done":4,"opt_steps":1,"seconds":5}]}"""
        ) as BranchyEvent.DpRound
        assertEquals(12.5, e.roundS, 1e-9)
        assertEquals(9L, e.globalStep)
        assertEquals(72L, e.gmb)
        val m = e.devices[0]
        assertEquals("active", m.state)
        assertEquals(8L, m.mb)
        assertEquals(8L, m.mbDone)
        assertEquals(2L, m.optSteps)
        assertEquals(2L, m.steps)
        assertEquals(10.0, m.seconds, 1e-9)
        assertEquals(10.0, m.elapsedS, 1e-9)
        assertEquals(1250.0, m.msPerMb, 1e-9)
        assertEquals(0.5, m.idleS, 1e-9)
        assertEquals(0.1, m.commS, 1e-9)
        assertEquals(0.2, m.downS, 1e-9)
        assertEquals("idle_end", e.devices[1].state)
    }

    @Test
    fun dpRoundOldFieldsStillWork() {
        val e = ev("""{"type":"dp_round","round":1,"devices":[{"id":"a","steps":9,"tokens":100,"tok_s":30,"rss_mb":400,"elapsed_s":10}]}""")
            as BranchyEvent.DpRound
        val d = e.devices.single()
        assertEquals(9L, d.steps)
        assertEquals(10.0, d.elapsedS, 1e-9)
        assertNull(d.state)
    }

    @Test
    fun dpAdvice() {
        assertEquals(
            BranchyEvent.DpAdvice("a", "not_needed", 1.5, 12.0, 3),
            ev("""{"type":"dp_advice","id":"a","verdict":"not_needed","gain_pct":1.5,"cost_pct":12,"rounds":3}""")
        )
    }

    @Test
    fun bpAdvice() {
        val e = ev(
            """{"type":"bp_advice","verdict":"rebalance","step":500,"gain_pct":22.5,"current_ms":{"main":900,"a":400},
            "proposed_ms":{"main":700,"a":600},"placement":[{"id":"main","branches":[0,1]},{"id":"a","branches":[2,3]}],"applicable":true}"""
        ) as BranchyEvent.BpAdvice
        assertEquals("rebalance", e.verdict)
        assertEquals(500L, e.step)
        assertEquals(900.0, e.currentMs.getValue("main"), 1e-9)
        assertEquals(listOf(2, 3), e.placement[1].branches)
        assertTrue(e.applicable)
        assertNull(e.reason)
    }

    @Test
    fun endpointAndControlAck() {
        assertEquals(BranchyEvent.Endpoint("a", "10.0.0.2", "192.168.1.5"), ev("""{"type":"endpoint","id":"a","host":"10.0.0.2","previous":"192.168.1.5"}"""))
        val ack = ev("""{"type":"control_ack","seq":4,"applied":["rest:a","checkpoint"],"ignored":[{"id":"b","reason":"unknown"},{"cmd":"skip","reason":"branch_parallel"}]}""")
            as BranchyEvent.ControlAck
        assertEquals(4L, ack.seq)
        assertEquals(listOf("rest:a", "checkpoint"), ack.applied)
        assertEquals(listOf(BranchyEvent.ControlIgnored("b", "unknown"), BranchyEvent.ControlIgnored("skip", "branch_parallel")), ack.ignored)
    }

    @Test
    fun checkpointPausedAndStep() {
        assertEquals(
            BranchyEvent.Checkpoint(250, "p", "manual", 1234L),
            ev("""{"type":"checkpoint","step":250,"path":"p","reason":"manual","tokens":1234}""")
        )
        assertEquals(
            BranchyEvent.Paused(10, "rebalance", "/r/cluster.rebalance.json"),
            ev("""{"type":"paused","step":10,"reason":"rebalance","cluster":"/r/cluster.rebalance.json"}""")
        )
        val step = ev("""{"type":"step","step":3,"loss":1.0,"compute_ms_by_device":{"main":800,"a":300}}""") as BranchyEvent.Step
        assertEquals(300.0, step.computeMsByDevice.getValue("a"), 1e-9)
    }

    // endregion

    // region state

    @Test
    fun idleIsNotLost() {
        var s = live("a")
        s = BranchyTrainingState.applyClusterEvent(s, round(1, dev("a", "idle_end")))
        assertEquals(BranchyDeviceStatus.IDLE, s.devices[0].status)
        s = BranchyTrainingState.applyClusterEvent(s, round(2, dev("a", "active")))
        assertEquals(BranchyDeviceStatus.CONNECTED, s.devices[0].status)
        s = BranchyTrainingState.applyClusterEvent(s, round(3, dev("a", "lost")))
        assertEquals(BranchyDeviceStatus.LOST, s.devices[0].status)
        s = BranchyTrainingState.applyClusterEvent(s, round(4, dev("a", "active")))
        assertEquals(BranchyDeviceStatus.CONNECTED, s.devices[0].status)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.WorkerLost("a", "x", 1L))
        assertEquals(BranchyDeviceStatus.LOST, s.devices[0].status)
    }

    @Test
    fun restingSetAndAdvice() {
        var s = live("a", "b")
        s = BranchyTrainingState.applyClusterEvent(s, round(1, dev("a", "resting"), dev("b", "active")))
        assertEquals(setOf("a"), s.restingIds)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.DpAdvice("b", "not_needed", 2.0, 9.0, 3))
        assertTrue(s.advice.getValue("b").notNeeded)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.DpAdvice("b", "ok", 8.0, 1.0, 3))
        assertFalse(s.advice.getValue("b").notNeeded)
    }

    @Test
    fun historyKeepsLast20() {
        var s = live("a")
        for (r in 1L..25L) s = BranchyTrainingState.applyClusterEvent(s, round(r, dev("a", "active", mb = r, idle = 0.5)))
        val h = s.roundHistory.getValue("a")
        assertEquals(20, h.size)
        assertEquals(6L, h.first().round)
        assertEquals(25L, h.last().mb)
        assertEquals(0.5, h.last().idleS, 1e-9)
        assertEquals(25L, s.devices[0].mb)
    }

    @Test
    fun endpointCheckpointBpProposalAndRebalancing() {
        var s = live("a")
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.Endpoint("a", "10.0.0.2", null))
        assertEquals("10.0.0.2", s.devices[0].endpointHost)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.Checkpoint(9L, "p", "every"), nowMs = 55L)
        assertEquals(BranchyCheckpointInfo(9L, "every", 55L), s.lastCheckpoint)
        s = BranchyTrainingState.applyClusterEvent(
            s, BranchyEvent.BpAdvice("rebalance", 9L, 20.0, emptyMap(), emptyMap(), listOf(BranchyEvent.BpPlacementAdvice("a", listOf(1))), true, null)
        )
        assertEquals(mapOf("a" to listOf(1)), s.bpProposal?.placement)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.Paused(9L, "rebalance", "/c.json"))
        assertTrue(s.rebalancing)
        assertNull(s.bpProposal)
        assertEquals(BranchyDeviceStatus.OFFLINE, s.devices[0].status)
        s = BranchyTrainingState.applyClusterEvent(live("a"), BranchyEvent.Paused(9L))
        assertFalse(s.rebalancing)
    }

    @Test
    fun stepComputeMsFillsStepMs() {
        val s = BranchyTrainingState.applyClusterEvent(
            live("a"), BranchyEvent.Step(1L, 1.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, computeMsByDevice = mapOf("a" to 42.0))
        )
        assertEquals(42.0, s.devices[0].stepMs, 1e-9)
    }

    // endregion

    // region control file

    private fun readJson(f: File) = BranchyJson.json.parseToJsonElement(f.readText()) as JsonObject

    private fun ids(o: JsonObject, key: String) = (o[key] as? JsonArray)?.map { (it as JsonPrimitive).content }

    @Test
    fun controlSeqIncreasesAndPersists() {
        val f = File(tmp.root, "control.json")
        f.writeText("""{"seq":99,"skip":["old"]}""")
        val w = BranchyControlFile.Writer(f)
        assertFalse("stale control.json deleted", f.exists())
        assertEquals(1L, w.rest("a"))
        assertEquals(2L, w.checkpointNow())
        assertEquals(2L, readJson(f)["seq"]!!.let { (it as JsonPrimitive).doubleOrNull!!.toLong() })
        assertEquals(2L, BranchyControlFile.readSeq(File(tmp.root, "control.seq")))
        // A new launch in the same stage dir continues the sequence.
        val w2 = BranchyControlFile.Writer(f)
        assertEquals(3L, w2.retry("b"))
    }

    @Test
    fun controlMergesPendingAndAckClears() {
        val f = File(tmp.root, "control.json")
        val w = BranchyControlFile.Writer(f)
        w.rest("a"); w.skip("s"); w.checkpointNow()
        var o = readJson(f)
        assertEquals(listOf("a"), ids(o, "rest"))
        assertEquals(listOf("s"), ids(o, "skip"))
        assertEquals(true, (o["checkpoint"] as JsonPrimitive).booleanOrNull)
        w.unrest("a")
        o = readJson(f)
        assertNull(ids(o, "rest"))
        assertEquals(listOf("a"), ids(o, "unrest"))
        w.onAck(1L) // older ack: nothing dropped
        assertTrue(w.pendingCommands().checkpoint)
        w.onAck(w.lastSeq)
        assertFalse(w.pendingCommands().hasOneShot)
        assertEquals(setOf("s"), w.pendingCommands().skip)
        w.forget("x")
        o = readJson(f)
        assertEquals(listOf("x"), ids(o, "forget"))
        assertEquals(listOf("s"), ids(o, "skip"))
        assertNull(o["checkpoint"])
    }

    @Test
    fun legacySkipFileStillWritten() {
        val f = File(tmp.root, "c.json")
        BranchyControlFile.writeSkip(f, listOf("a", "b"))
        assertEquals(listOf("a", "b"), ids(readJson(f), "skip"))
    }

    // endregion

    // region rebalance

    @Test
    fun rebalanceRestartDecision() {
        val p = BranchyEvent.Paused(5L, "rebalance", "/r/c.json")
        assertEquals("/r/c.json", BranchyRebalance.restartCluster(p, false) { true })
        assertNull(BranchyRebalance.restartCluster(p, true) { true })
        assertNull(BranchyRebalance.restartCluster(p, false) { false })
        assertNull(BranchyRebalance.restartCluster(BranchyEvent.Paused(5L), false) { true })
        assertNull(BranchyRebalance.restartCluster(BranchyEvent.Paused(5L, "rebalance", ""), false) { true })
        assertNull(BranchyRebalance.restartCluster(null, false) { true })
    }

    @Test
    fun placementAdoption() {
        val text = """{"devices":[{"id":"a","branches":[2,3]},{"id":"b","branches":[1]}]}"""
        val placement = BranchyRebalance.placementOf(text)
        assertEquals(listOf(2, 3), placement["a"])
        val plan = BranchyPlan(
            placement = listOf(
                BranchyPlacement("local", "coordinator", listOf(0)),
                BranchyPlacement("a", "worker", listOf(1)),
                BranchyPlacement("b", "worker", listOf(2, 3))
            )
        )
        val out = BranchyRebalance.applyPlacement(plan, placement)
        assertEquals(listOf(0), out.placement[0].branches)
        assertEquals(listOf(2, 3), out.placement[1].branches)
        assertEquals(listOf(1), out.placement[2].branches)
    }

    @Test
    fun dpClusterJsonRejoinInterval() {
        val dp = BranchyClusterRun.withNetworkKeys("""{"cluster_id":"x"}""", null, com.example.llamadroid.branchy.cluster.BranchyClusterMode.DATA_PARALLEL)
        assertTrue(dp.contains("\"rejoin_interval_ms\":10000"))
        val bp = BranchyClusterRun.withNetworkKeys("""{"cluster_id":"x"}""", null, com.example.llamadroid.branchy.cluster.BranchyClusterMode.BRANCH_PARALLEL)
        assertFalse(bp.contains("rejoin_interval_ms"))
        assertTrue(bp.contains("join_window_ms"))
    }

    // endregion
}
