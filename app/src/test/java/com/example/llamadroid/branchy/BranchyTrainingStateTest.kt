package com.example.llamadroid.branchy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyTrainingStateTest {
    @Test
    fun lossPointsStayBoundedAndKeepTheNewestPoint() {
        var points = emptyList<Pair<Int, Float>>()
        for (i in 1..5000) points = BranchyTrainingState.appendPoint(points, i to i.toFloat())
        assertTrue(points.size <= BranchyTrainingState.MAX_LOSS_POINTS)
        assertEquals(5000, points.last().first)
        assertEquals(points.map { it.first }, points.map { it.first }.sorted())
    }

    @Test
    fun smallSeriesAreKeptWhole() {
        val points = (1..10).fold(emptyList<Pair<Int, Float>>()) { acc, i -> BranchyTrainingState.appendPoint(acc, i to 1f) }
        assertEquals(10, points.size)
    }

    @Test
    fun samplesKeepTheLastFive() {
        var samples = emptyList<String>()
        for (i in 1..8) samples = BranchyTrainingState.appendSample(samples, "s$i")
        assertEquals(listOf("s4", "s5", "s6", "s7", "s8"), samples)
    }

    @Test
    fun durationFormatting() {
        assertEquals("45s", BranchyFormat.duration(45.0))
        assertEquals("12m 05s", BranchyFormat.duration(725.0))
        assertEquals("3h 07m", BranchyFormat.duration(3.0 * 3600 + 7 * 60))
        assertEquals("2d 5h", BranchyFormat.duration(2.0 * 86400 + 5 * 3600))
        assertEquals("-", BranchyFormat.duration(Double.NaN))
        assertEquals("-", BranchyFormat.duration(-1.0))
        assertEquals("1.235", BranchyFormat.loss(1.2346))
        assertEquals("-", BranchyFormat.loss(null))
    }

    @Test
    fun liveStateActivity() {
        assertTrue(BranchyLiveState(phase = BranchyPhase.TRAIN).isActive)
        assertTrue(BranchyLiveState(phase = BranchyPhase.PAUSING).isActive)
        assertEquals(false, BranchyLiveState(phase = BranchyPhase.PAUSED).isActive)
        assertEquals(false, BranchyLiveState().isActive)
    }

    @Test
    fun waitingNetworkReducerRoundTrips() {
        val base = BranchyLiveState(
            phase = BranchyPhase.TRAIN,
            devices = listOf(BranchyLiveDevice("a", "A"), BranchyLiveDevice("b", "B"))
        )
        val w = BranchyTrainingState.applyClusterEvent(base, BranchyEvent.ClusterWaiting(listOf("a"), 1000L), nowMs = 5L)
        assertEquals(BranchyPhase.WAITING_NETWORK, w.phase)
        assertTrue(w.isActive)
        assertEquals(5L, w.waitingSinceMs)
        assertTrue(w.devices[0].waiting && !w.devices[0].up)
        assertTrue(!w.devices[1].waiting && w.devices[1].up)
        val w2 = BranchyTrainingState.applyClusterEvent(w, BranchyEvent.ClusterWaiting(listOf("a"), 2000L), nowMs = 9L)
        assertEquals(5L, w2.waitingSinceMs)
        assertEquals(2000L, w2.waitingRetryMs)
        val r = BranchyTrainingState.applyClusterEvent(w2, BranchyEvent.ClusterResumed)
        assertEquals(BranchyPhase.TRAIN, r.phase)
        assertEquals(0L, r.waitingSinceMs)
        assertTrue(r.devices.all { it.up && !it.waiting })
    }

    @Test
    fun waitingDoesNotOverridePausing() {
        val s = BranchyLiveState(phase = BranchyPhase.PAUSING)
        assertEquals(BranchyPhase.PAUSING, BranchyTrainingState.applyClusterEvent(s, BranchyEvent.ClusterWaiting(listOf("a"), 1L)).phase)
    }

    @Test
    fun dpModeEventSetsTheLiveStepMode() {
        val s = BranchyTrainingState.applyClusterEvent(BranchyLiveState(), BranchyEvent.DpMode("exact", "fast link", 2.0, 0.1))
        assertEquals(BranchyDpStepMode("exact", "fast link", 2.0, 0.1), s.dpStepMode)
        val r = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.DpRound(1L, emptyList(), mode = "exact"))
        assertEquals("fast link", r.dpStepMode?.reason)
        val l = BranchyTrainingState.applyClusterEvent(s, BranchyEvent.DpRound(2L, emptyList(), mode = "local"))
        assertEquals("local", l.dpStepMode?.mode)
    }

    private fun apply(state: BranchyLiveState, ev: BranchyEvent, at: Long = 1L) =
        BranchyTrainingState.applyClusterEvent(state, ev, emptyMap(), at)

    @Test
    fun manualCheckpointRequestWalksThroughItsStages() {
        var st = BranchyTrainingState.requestCheckpoint(BranchyLiveState(), 1L)
        assertEquals(BranchyCkptStage.REQUESTED, st.checkpointRequest?.stage)
        // A second tap while pending changes nothing.
        assertEquals(st, BranchyTrainingState.requestCheckpoint(st, 2L))
        // An ack that does not mention the checkpoint keeps it REQUESTED.
        st = apply(st, BranchyEvent.ControlAck(1, listOf("skip:w0"), emptyList()))
        assertEquals(BranchyCkptStage.REQUESTED, st.checkpointRequest?.stage)
        st = apply(st, BranchyEvent.ControlAck(2, listOf("checkpoint"), emptyList()))
        assertEquals(BranchyCkptStage.ACKED, st.checkpointRequest?.stage)
        // A periodic begin does not move a manual request.
        st = apply(st, BranchyEvent.CheckpointBegin(4, "every"))
        assertEquals(BranchyCkptStage.ACKED, st.checkpointRequest?.stage)
        st = apply(st, BranchyEvent.CheckpointBegin(5, "manual"))
        assertEquals(BranchyCkptStage.SAVING, st.checkpointRequest?.stage)
        st = apply(st, BranchyEvent.Consolidate("w0", "u", 50L, 200L))
        assertEquals(50L, st.checkpointRequest?.doneBytes)
        assertEquals(200L, st.checkpointRequest?.totalBytes)
        st = apply(st, BranchyEvent.Checkpoint(5, "/p", "manual", 100L), at = 9L)
        assertEquals(BranchyCkptStage.SAVED, st.checkpointRequest?.stage)
        assertEquals(5L, st.checkpointRequest?.step)
        assertEquals(st, BranchyTrainingState.clearFinishedCheckpoint(st, 1L))
        assertEquals(null, BranchyTrainingState.clearFinishedCheckpoint(st, 9L).checkpointRequest)
    }

    @Test
    fun ignoredCheckpointAckFailsTheRequestAndRunEndKeepsOnlyFinishedOnes() {
        var st = BranchyTrainingState.requestCheckpoint(BranchyLiveState(), 1L)
        st = apply(st, BranchyEvent.ControlAck(1, emptyList(), listOf(BranchyEvent.ControlIgnored("checkpoint", "workers_lost"))))
        assertEquals(BranchyCkptStage.FAILED, st.checkpointRequest?.stage)
        assertEquals("workers_lost", st.checkpointRequest?.reason)
        assertEquals(st.checkpointRequest, BranchyTrainingState.endRun(st).checkpointRequest)
        val pending = BranchyTrainingState.requestCheckpoint(BranchyLiveState(), 1L)
        assertEquals(null, BranchyTrainingState.endRun(pending).checkpointRequest)
    }

    @Test
    fun stepEtaUsesMicroProgressThenLastStepThroughput() {
        val base = BranchyLiveState(tokensPerStep = 1000L, micro = BranchyMicro(3, 1L, 4L, 10.0, 0.0))
        assertEquals(75.0, BranchyTrainingState.stepEtaSeconds(base)!!, 1e-9)
        assertEquals(null, BranchyTrainingState.stepEtaSeconds(BranchyLiveState(tokensPerStep = 1000L)))
    }

    private fun lost(vararg ids: String) =
        BranchyLiveState(devices = ids.map { BranchyLiveDevice(id = it, name = it, up = false) })

    @Test
    fun retryTapShowsPendingUntilTheNativeAnswerArrives() {
        var st = BranchyTrainingState.requestRetry(lost("w0", "w1"), "w0", 1_000L)
        assertTrue(st.devices[0].retryPending(2_000L))
        assertTrue(!st.devices[1].retryPending(2_000L))
        // Safety net: never spins forever when nothing answers.
        assertTrue(!st.devices[0].retryPending(1_000L + BranchyLiveDevice.RETRY_PENDING_MAX_MS))
        // An automatic failure does not answer the tap; a manual one does.
        st = apply(st, BranchyEvent.Rejoin("w0", "failed", "timed out", 8.0, false), 3_000L)
        assertTrue(st.devices[0].retryPending(3_500L))
        st = apply(st, BranchyEvent.Rejoin("w0", "trying", null, null, true), 4_000L)
        assertEquals(BranchyRejoinPhase.TRYING, st.devices[0].rejoin?.phase)
        assertTrue(st.devices[0].retryPending(4_100L))
        st = apply(st, BranchyEvent.Rejoin("w0", "failed", "connection refused", 10.0, true), 5_000L)
        val d = st.devices[0]
        assertTrue(!d.retryPending(5_100L))
        assertEquals(BranchyRejoinPhase.FAILED, d.rejoin?.phase)
        assertEquals("connection refused", d.rejoin?.reason)
        assertEquals(15_000L, d.rejoin?.nextRetryAtMs)
    }

    @Test
    fun joinedShowsReconnectedBrieflyAndALossResetsIt() {
        var st = apply(BranchyTrainingState.requestRetry(lost("w0"), "w0", 1_000L), BranchyEvent.Rejoin("w0", "joined", null, null, true), 2_000L)
        assertTrue(!st.devices[0].retryPending(2_100L))
        assertTrue(st.devices[0].justReconnected(3_000L))
        assertTrue(!st.devices[0].justReconnected(2_000L + BranchyLiveDevice.RECONNECTED_SHOW_MS + 1L))
        st = apply(st, BranchyEvent.WorkerJoined("w0", 5L), 2_050L)
        assertTrue(st.devices[0].up && st.devices[0].justReconnected(2_100L))
        st = apply(st, BranchyEvent.WorkerLost("w0", "round: reset", 6L), 9_000L)
        assertEquals(null, st.devices[0].rejoin)
    }

    @Test
    fun refusedRetryAckClearsThePendingSpinner() {
        var st = BranchyTrainingState.requestRetry(lost("w0"), "w0", 1_000L)
        st = apply(st, BranchyEvent.ControlAck(1, emptyList(), listOf(BranchyEvent.ControlIgnored("w0", "already_connected"))), 1_500L)
        assertTrue(!st.devices[0].retryPending(1_600L))
    }
}
