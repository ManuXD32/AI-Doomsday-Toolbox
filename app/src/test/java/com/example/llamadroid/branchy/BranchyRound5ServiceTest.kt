package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyHelperStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BranchyRound5ServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ev(json: String) = BranchyEventParser.parse("BRANCHY_EVENT $json")

    // region events

    @Test
    fun microEvent() {
        val e = ev("""{"type":"micro","step":7,"done":3,"total":8,"tok_s":55.5,"rss_mb":900}""") as BranchyEvent.Micro
        assertEquals(BranchyEvent.Micro(7L, 3L, 8L, 55.5, 900.0), e)
    }

    @Test
    fun dpRoundEvent() {
        val e = ev(
            """{"type":"dp_round","round":4,"devices":[{"id":"main","steps":10,"tokens":4000,"tok_s":40,"rss_mb":500,"elapsed_s":100},""" +
                """{"id":"a","steps":9,"tokens":3000,"tok_s":30.5,"rss_mb":400,"elapsed_s":98}]}"""
        ) as BranchyEvent.DpRound
        assertEquals(4L, e.round)
        assertEquals(listOf("main", "a"), e.devices.map { it.id })
        assertEquals(30.5, e.devices[1].tokS, 1e-9)
        assertEquals(400.0, e.devices[1].rssMb, 1e-9)
    }

    @Test
    fun clusterWaitingPhaseAndSkippable() {
        val e = ev("""{"type":"cluster_waiting","lost":["a"],"retry_ms":1000,"step":3,"phase":"join","skippable":true}""") as BranchyEvent.ClusterWaiting
        assertEquals("join", e.phase)
        assertTrue(e.skippable)
        val old = ev("""{"type":"cluster_waiting","lost":["a"],"retry_ms":1000}""") as BranchyEvent.ClusterWaiting
        assertEquals("run", old.phase)
        assertFalse(old.skippable)
    }

    @Test
    fun consolidateEvents() {
        assertEquals(
            BranchyEvent.Consolidate("a", "u3", 10L, 100L),
            ev("""{"type":"consolidate","device":"a","unit":"u3","done_bytes":10,"total_bytes":100}""")
        )
        assertEquals(BranchyEvent.ConsolidateDone(12L), ev("""{"type":"consolidate_done","step":12}"""))
    }

    @Test
    fun startPrefersResumedStep() {
        val a = ev("""{"type":"start","params":1,"tokens_per_step":2,"steps":3,"resumed_step":40,"resumed_from":10}""") as BranchyEvent.Start
        assertEquals(40L, a.resumedFrom)
        val b = ev("""{"type":"start","params":1,"tokens_per_step":2,"steps":3,"resumed_from":10}""") as BranchyEvent.Start
        assertEquals(10L, b.resumedFrom)
    }

    // endregion

    // region state

    private fun live() = BranchyLiveState(
        phase = BranchyPhase.TRAIN,
        devices = listOf(BranchyLiveDevice("a", "A"), BranchyLiveDevice("b", "B"))
    )

    private fun status(s: BranchyLiveState, id: String) = s.devices.first { it.id == id }.status

    @Test
    fun microIsShownAndClearedByStep() {
        var s = BranchyTrainingState.applyClusterEvent(live(), BranchyEvent.Micro(2L, 1L, 4L, 10.0, 1.0))
        assertEquals(BranchyMicro(2L, 1L, 4L, 10.0, 1.0), s.micro)
        s = BranchyTrainingState.applyClusterEvent(s, ev("""{"type":"step","step":2,"loss":1.0}""")!!)
        assertNull(s.micro)
    }

    @Test
    fun deviceRowTransitions() {
        var s = live()
        assertEquals(BranchyDeviceStatus.CONNECTED, status(s, "a"))
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.WorkerLost("a", "timeout", 5L))
        assertEquals(BranchyDeviceStatus.LOST, status(s, "a"))
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.ClusterWaiting(listOf("a"), 1000L, "run", true))
        assertEquals(BranchyDeviceStatus.WAITING, status(s, "a"))
        assertEquals(BranchyWaiting("run", listOf("a"), true), s.waiting)
        s = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.WorkerJoined("a", 6L))
        assertEquals(BranchyDeviceStatus.CONNECTED, status(s, "a"))
        assertNull(s.waiting)
        s = BranchyTrainingState.applyClusterEvent(
            s, BranchyEvent.DpRound(1L, listOf(BranchyEvent.DpRoundDevice("a", 9L, 100L, 30.0, 400.0, 10.0))), nowMs = 77L
        )
        val a = s.devices.first { it.id == "a" }
        assertEquals(30.0, a.tokS, 1e-9)
        assertEquals(77L, a.lastSyncAt)
        assertEquals(9L, a.steps)
    }

    @Test
    fun probeMarksUnreachableAndRtt() {
        val probes = mapOf(
            "a" to BranchyHelperStatus(false, null, null, null, 1L),
            "b" to BranchyHelperStatus(true, 42L, null, "h", 1L)
        )
        val s = BranchyTrainingState.applyProbe(live(), probes)
        assertEquals(BranchyDeviceStatus.UNREACHABLE, status(s, "a"))
        assertEquals(BranchyDeviceStatus.CONNECTED, status(s, "b"))
        assertEquals(42L, s.devices.first { it.id == "b" }.rttMs)
    }

    @Test
    fun noRowStaysConnectedAfterEndPauseOrError() {
        listOf(
            BranchyEvent.Paused(3L), BranchyEvent.Done(3L, ""), BranchyEvent.Error("x")
        ).forEach { ev ->
            val s = BranchyTrainingState.applyClusterEvent(live().copy(micro = BranchyMicro(1, 1, 2, 0.0, 0.0)), ev)
            assertTrue(s.devices.all { it.status == BranchyDeviceStatus.OFFLINE })
            assertNull(s.micro)
        }
        val ended = BranchyTrainingState.endRun(live())
        assertTrue(ended.devices.all { it.status == BranchyDeviceStatus.OFFLINE })
        // A late probe must not revive an offline row.
        val probed = BranchyTrainingState.applyProbe(ended, mapOf("a" to BranchyHelperStatus(true, 5L, null, "h", 1L)))
        assertEquals(BranchyDeviceStatus.OFFLINE, status(probed, "a"))
    }

    @Test
    fun dpSetupClearedByDpStartWaitingPausedDoneError() {
        val setup = BranchyDpSetup("A", 50)
        listOf<BranchyEvent>(
            BranchyEvent.DpStart(2, 8, 64, 1L), BranchyEvent.ClusterWaiting(listOf("a"), 1L),
            BranchyEvent.Paused(1L), BranchyEvent.Done(1L, ""), BranchyEvent.Error("x")
        ).forEach { e ->
            assertNull(BranchyTrainingState.applyClusterEvent(live().copy(dpSetup = setup), e).dpSetup)
        }
    }

    // endregion

    // region stop / epochs / control / cluster.json

    @Test
    fun stopMakesReadFailureExpectedAndEndsPaused() {
        assertTrue(BranchyStopPolicy.isExpectedReadFailure(IOException("Stream closed"), stopRequested = true))
        assertFalse(BranchyStopPolicy.isExpectedReadFailure(IOException("Stream closed"), stopRequested = false))
        assertFalse(BranchyStopPolicy.isExpectedReadFailure(IllegalStateException("x"), stopRequested = true))
        assertTrue(BranchyStopPolicy.endsPaused(true))
        assertFalse(BranchyStopPolicy.endsPaused(false))
    }

    @Test
    fun epochStepMath() {
        // 1 epoch of 1,000,000 tokens at 32,768 tokens per step = ceil(30.5) = 31 steps.
        assertEquals(31L, BranchyEpochs.steps(20_000L, 1.0, 1_000_000L, 32_768L))
        assertEquals(62L, BranchyEpochs.steps(20_000L, 2.0, 1_000_000L, 32_768L))
        // Capped at the plan target.
        assertEquals(20L, BranchyEpochs.steps(20L, 1.0, 1_000_000L, 32_768L))
        // At least one step.
        assertEquals(1L, BranchyEpochs.steps(20_000L, 0.0001, 10L, 32_768L))
        // 0 = no cap; unknown tokens = no cap.
        assertEquals(20_000L, BranchyEpochs.steps(20_000L, 0.0, 1_000_000L, 32_768L))
        assertEquals(20_000L, BranchyEpochs.steps(20_000L, 1.0, 0L, 32_768L))
        assertEquals(20_000L, BranchyEpochs.steps(20_000L, 1.0, 1_000_000L, 0L))
    }

    @Test
    fun resumeKeepsWrittenSteps() {
        assertEquals(31L, BranchyEpochs.writtenSteps("""{"train":{"steps":31,"lr":0.1}}"""))
        assertNull(BranchyEpochs.writtenSteps("not json"))
        assertNull(BranchyEpochs.writtenSteps(null))
        assertEquals(0L, BranchyDataMarker.trainTokens("ok"))
        assertEquals(1234L, BranchyDataMarker.trainTokens(BranchyDataMarker.text(1234L)))
    }

    @Test
    fun controlFileIsWrittenAtomically() {
        val f = File(tmp.root, "control.json")
        BranchyControlFile.writeSkip(f, listOf("a", "b"))
        // Round 6: the writer also stamps an increasing "seq" (A8); the skip list is what matters here.
        assertTrue(f.readText().replace(Regex("\\s"), "").contains(""""skip":["a","b"]"""))
        assertFalse(File(tmp.root, "control.json.tmp").exists())
    }

    @Test
    fun clusterJsonCarriesNetworkKeys() {
        val out = BranchyClusterRun.withNetworkKeys("""{"cluster_id":"x"}""", "/s/control.json")
        assertTrue(out.contains("\"connect_timeout_ms\":15000"))
        assertTrue(out.contains("\"message_timeout_ms\":60000"))
        assertTrue(out.contains("\"join_window_ms\":90000"))
        assertTrue(out.contains("\"control_file\":\"/s/control.json\""))
        assertFalse(BranchyClusterRun.withNetworkKeys("""{"cluster_id":"x"}""", null).contains("control_file"))
    }

    // endregion

    // region run log

    @Test
    fun runLogRedactsSecretsAndKeepsCounts() {
        val hex = "0123456789abcdef0123456789abcdef0123"
        val line = BranchyRunLog.renderMetric(
            """{"type":"start","ts":1700000000,"tokens_per_step":32768,"auth_token":"abc","blob":"$hex","path":"/storage/emulated/0/Android/data"}"""
        )
        assertTrue(line, line.contains(" start "))
        assertTrue(line, line.contains("tokens_per_step=32768"))
        assertTrue(line, line.contains("auth_token=***"))
        assertFalse(line, line.contains(hex))
        assertTrue(line, line.contains("/storage/emulated/0/Android/data"))
        assertTrue(line.length <= BranchyRunLog.MAX_LINE_CHARS)
        assertEquals("Authorization token: ***", BranchyRunLog.redact("Authorization token: s3cr3t"))
        assertEquals("secret=***", BranchyRunLog.redact("secret=hunter2"))
    }

    @Test
    fun runLogUsesLatestStageAndBoundsLines() {
        val run = tmp.newFolder("run")
        File(run, "metrics.jsonl").writeText("""{"type":"old"}""" + "\n")
        val stage2 = File(run, "stage2").apply { mkdirs() }
        File(run, "stage10").mkdirs()
        assertEquals("stage10", BranchyRunLog.latestStageDir(run).name)
        File(run, "stage10").deleteRecursively()
        assertEquals(stage2, BranchyRunLog.latestStageDir(run))
        File(stage2, "metrics.jsonl").writeText((1..500).joinToString("\n") { """{"type":"step","step":$it}""" })
        File(stage2, "stderr.log").writeText("warn token=abc\nplain line\n")
        val text = runBlocking { BranchyRunLog.load(run) }
        assertFalse(text.contains("old"))
        assertTrue(text.contains("step=500"))
        assertFalse(text.contains("step=200 ") || text.contains("step=200\n"))
        assertTrue(text.contains("token=***"))
        assertEquals(BranchyRunLog.METRIC_LINES, text.lines().count { it.contains(" step step=") })
    }

    // endregion
}
