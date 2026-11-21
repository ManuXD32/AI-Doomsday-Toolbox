package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyHelperStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BranchyPhase { IDLE, TOKENIZER, PREPARE, TRAIN, PAUSING, PAUSED, WAITING_NETWORK, DONE, ERROR }

/** [IDLE] = connected but finished its share and waiting for the others (`idle_end`); not lost. */
enum class BranchyDeviceStatus { CONNECTED, LOST, WAITING, UNREACHABLE, OFFLINE, IDLE }

/** One round of one device, kept for the load chart (the last [BranchyTrainingState.MAX_HISTORY] per device). */
data class BranchyRoundStat(val round: Long, val mb: Long, val seconds: Double, val idleS: Double)

/** `dp_advice` of one helper: [notNeeded] = suggest resting it. */
/** Current DP step mode (`exact` or `local`) with the reason and the measured compute/comm seconds. */
data class BranchyDpStepMode(val mode: String, val reason: String?, val computeS: Double, val commS: Double)

data class BranchyDpAdviceState(val notNeeded: Boolean, val gainPct: Double, val costPct: Double, val rounds: Int)

/** Pending branch-parallel rebalance suggestion (`bp_advice`). */
data class BranchyBpProposal(
    val step: Long,
    val gainPct: Double,
    val currentMs: Map<String, Double>,
    val proposedMs: Map<String, Double>,
    val placement: Map<String, List<Int>>,
    val applicable: Boolean,
    val reason: String?
)

/** Last checkpoint written by the native side; [atMs] is wall-clock. */
data class BranchyCheckpointInfo(val step: Long, val reason: String?, val atMs: Long)

/** Where a "save checkpoint now" request is, from the tap to the native `checkpoint` event. */
enum class BranchyCkptStage { REQUESTED, ACKED, SAVING, SAVED, FAILED }

/**
 * One manual checkpoint request. [step] is set from SAVING/SAVED; [reason] is the native ignore reason for FAILED;
 * [doneBytes]/[totalBytes] follow `consolidate` events while SAVING (BP shard mirror); [atMs] is the last transition.
 */
data class BranchyCheckpointRequest(
    val stage: BranchyCkptStage,
    val atMs: Long,
    val step: Long = 0L,
    val reason: String? = null,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    val pending: Boolean get() = stage == BranchyCkptStage.REQUESTED || stage == BranchyCkptStage.ACKED || stage == BranchyCkptStage.SAVING
}

/** Phase of the latest native connection attempt to a helper (`rejoin` event). */
enum class BranchyRejoinPhase { TRYING, FAILED, JOINED }

/**
 * Latest `rejoin` of one helper. [nextRetryAtMs] is the wall-clock time of the next automatic attempt (0 = none
 * announced); [atMs] is when the event arrived.
 */
data class BranchyRejoin(
    val phase: BranchyRejoinPhase,
    val reason: String?,
    val nextRetryAtMs: Long,
    val manual: Boolean,
    val atMs: Long
)

/** One helper of a cluster run as shown in the live view. */
data class BranchyLiveDevice(
    val id: String,
    val name: String,
    val branches: List<Int> = emptyList(),
    val up: Boolean = true,
    /** True while the run waits for this helper to rejoin (`cluster_waiting`); [up] is false then. */
    val waiting: Boolean = false,
    /** Milliseconds the helper spent on the last step (0 = unknown). */
    val stepMs: Double = 0.0,
    val lostReason: String? = null,
    /** True once the run ended, paused or failed: no row may stay "connected" then. */
    val offline: Boolean = false,
    /** Latest heartbeat probe failed while the native side still thinks the helper is up (UI hint only). */
    val unreachable: Boolean = false,
    /** Latest heartbeat round trip; null when unknown or unreachable. */
    val rttMs: Long? = null,
    /** From the last `dp_round` (0 = unknown). */
    val tokS: Double = 0.0,
    val rssMb: Double = 0.0,
    val steps: Long = 0L,
    /** Wall-clock time of the last `dp_round` that included this helper (0 = never). */
    val lastSyncAt: Long = 0L,
    /** `idle_end` in the last `dp_round`: finished its share, waiting for the others. */
    val idle: Boolean = false,
    /** Resting helper (kept alive, no work). */
    val resting: Boolean = false,
    /** Last `dp_round` figures of this device (microbatches assigned/done, seconds, ms per microbatch, idle/comm/download seconds). */
    val mb: Long = 0L,
    val mbDone: Long = 0L,
    val seconds: Double = 0.0,
    val msPerMb: Double = 0.0,
    val idleS: Double = 0.0,
    val commS: Double = 0.0,
    val downS: Double = 0.0,
    /** Host the helper was last reached on (`endpoint`); null = as configured. */
    val endpointHost: String? = null,
    /** Latest `rejoin` attempt; null when none since the last loss. */
    val rejoin: BranchyRejoin? = null,
    /** Wall-clock time the user tapped Retry now and no native answer arrived yet (0 = none). */
    val retryRequestedAt: Long = 0L
) {
    /** A connection attempt is running, or the user's retry was sent and is not answered yet (bounded by [RETRY_PENDING_MAX_MS]). */
    fun retryPending(nowMs: Long): Boolean =
        rejoin?.phase == BranchyRejoinPhase.TRYING ||
            (retryRequestedAt > 0L && nowMs - retryRequestedAt < RETRY_PENDING_MAX_MS)

    /** The "Reconnected" confirmation is shown for [RECONNECTED_SHOW_MS] after a `rejoin{joined}`. */
    fun justReconnected(nowMs: Long): Boolean {
        val r = rejoin ?: return false
        return r.phase == BranchyRejoinPhase.JOINED && nowMs - r.atMs in 0L..RECONNECTED_SHOW_MS
    }

    companion object {
        const val RETRY_PENDING_MAX_MS = 20_000L
        const val RECONNECTED_SHOW_MS = 4_000L
    }

    val status: BranchyDeviceStatus
        get() = when {
            offline -> BranchyDeviceStatus.OFFLINE
            waiting -> BranchyDeviceStatus.WAITING
            !up -> BranchyDeviceStatus.LOST
            idle -> BranchyDeviceStatus.IDLE
            unreachable -> BranchyDeviceStatus.UNREACHABLE
            else -> BranchyDeviceStatus.CONNECTED
        }
}

/** DP worker setup progress shown until the first step. */
data class BranchyDpSetup(val device: String, val percent: Int)

/** Microbatch progress of the optimizer step in progress. */
data class BranchyMicro(val step: Long, val done: Long, val total: Long, val tokS: Double, val rssMb: Double)

/** Why the run waits on its helpers: [phase] `join` or `run`, [lost] device ids, [skippable] = may continue without them. */
data class BranchyWaiting(val phase: String, val lost: List<String>, val skippable: Boolean)

/** Live view of the run driven by `BranchyTrainingService`. The runs list itself comes from Room. */
data class BranchyLiveState(
    val runId: Long? = null,
    val phase: BranchyPhase = BranchyPhase.IDLE,
    val stage: Int = 0,
    val stageCount: Int = 1,
    val progressDone: Long = 0L,
    val progressTotal: Long = 0L,
    /** Name of the dataset being prepared, while [phase] is PREPARE. */
    val progressLabel: String? = null,
    val lastStep: BranchyEvent.Step? = null,
    val lossPoints: List<Pair<Int, Float>> = emptyList(),
    val valPoints: List<Pair<Int, Float>> = emptyList(),
    val samples: List<String> = emptyList(),
    val message: String? = null,
    val pauseReason: BranchyPauseReason? = null,
    val totalSteps: Long = 0L,
    val tokensPerStep: Long = 0L,
    val params: Long = 0L,
    /** `branch_parallel` or `data_parallel` while a cluster run trains; null for a single-device run. */
    val clusterMode: String? = null,
    val devices: List<BranchyLiveDevice> = emptyList(),
    val syncRound: Long = 0L,
    val syncBytes: Long = 0L,
    val syncWorkers: Int = 0,
    /** Retry interval reported by the last `cluster_waiting` (0 = unknown). */
    val waitingRetryMs: Long = 0L,
    /** Wall-clock time the current network wait began; 0 when not waiting. */
    val waitingSinceMs: Long = 0L,
    /** Data-parallel setup upload progress; cleared on the first `step`. */
    val dpSetup: BranchyDpSetup? = null,
    /** Progress inside the current step; cleared when the step completes or the run stops. */
    val micro: BranchyMicro? = null,
    /** Set while a `cluster_waiting` is active. */
    val waiting: BranchyWaiting? = null,
    /** Per device (incl. `main`), last [BranchyTrainingState.MAX_HISTORY] DP rounds, oldest first. */
    val roundHistory: Map<String, List<BranchyRoundStat>> = emptyMap(),
    val dpRound: BranchyEvent.DpRound? = null,
    val dpStepMode: BranchyDpStepMode? = null,
    val advice: Map<String, BranchyDpAdviceState> = emptyMap(),
    val bpProposal: BranchyBpProposal? = null,
    val lastCheckpoint: BranchyCheckpointInfo? = null,
    val lastControlAck: BranchyEvent.ControlAck? = null,
    /** The manual "save checkpoint now" request in flight (or just finished); null when none. */
    val checkpointRequest: BranchyCheckpointRequest? = null,
    /** True from a `paused{rebalance}` until the restarted run starts ("Rebalancing..."). */
    val rebalancing: Boolean = false
) {
    /** Ids of the resting helpers. */
    val restingIds: Set<String> get() = devices.filter { it.resting }.map { it.id }.toSet()

    /** A run owns the service: anything but idle/finished states. */
    val isActive: Boolean
        get() = phase == BranchyPhase.TOKENIZER || phase == BranchyPhase.PREPARE ||
            phase == BranchyPhase.TRAIN || phase == BranchyPhase.PAUSING ||
            phase == BranchyPhase.WAITING_NETWORK
}

object BranchyTrainingState {
    const val MAX_LOSS_POINTS = 500
    const val MAX_SAMPLES = 5
    const val MAX_HISTORY = 20

    private val _state = MutableStateFlow(BranchyLiveState())
    val state: StateFlow<BranchyLiveState> = _state.asStateFlow()

    fun update(block: (BranchyLiveState) -> BranchyLiveState) {
        _state.update(block)
    }

    fun reset() {
        _state.value = BranchyLiveState()
    }

    /** Adds a point and keeps at most [max] by dropping every second point once the cap is exceeded. */
    fun appendPoint(points: List<Pair<Int, Float>>, point: Pair<Int, Float>, max: Int = MAX_LOSS_POINTS): List<Pair<Int, Float>> {
        val next = points + point
        if (next.size <= max) return next
        val thinned = next.filterIndexed { index, _ -> index % 2 == 0 || index == next.lastIndex }
        return if (thinned.size <= max) thinned else thinned.takeLast(max)
    }

    /**
     * Folds a cluster event (and the per-device timings of a `step`) into [state]. [names] maps device ids to the
     * names shown in the UI; an unknown id is shown as it is.
     */
    fun applyClusterEvent(
        state: BranchyLiveState,
        ev: BranchyEvent,
        names: Map<String, String> = emptyMap(),
        nowMs: Long = System.currentTimeMillis()
    ): BranchyLiveState {
        fun mapDevice(id: String, block: (BranchyLiveDevice) -> BranchyLiveDevice) =
            state.devices.map { if (it.id == id) block(it) else it }
        return when (ev) {
            is BranchyEvent.Cluster -> state.copy(
                devices = ev.devices.map { d ->
                    val old = state.devices.firstOrNull { it.id == d.id }
                    (old ?: BranchyLiveDevice(id = d.id, name = names[d.id] ?: d.id)).copy(
                        name = names[d.id] ?: old?.name ?: d.id, branches = d.branches, up = d.up,
                        lostReason = if (d.up) null else old?.lostReason, offline = false, waiting = false,
                        idle = if (d.up) old?.idle ?: false else false
                    )
                }
            )
            is BranchyEvent.WorkerLost -> state.copy(
                devices = mapDevice(ev.device) {
                    it.copy(
                        up = false, waiting = false, offline = false, idle = false, lostReason = ev.reason,
                        rejoin = null, retryRequestedAt = 0L
                    )
                }
            )
            is BranchyEvent.Rejoin -> state.copy(
                devices = mapDevice(ev.device) { d ->
                    when (ev.phase) {
                        "trying" -> d.copy(rejoin = BranchyRejoin(BranchyRejoinPhase.TRYING, null, 0L, ev.manual, nowMs))
                        "failed" -> d.copy(
                            rejoin = BranchyRejoin(
                                BranchyRejoinPhase.FAILED, ev.reason,
                                ev.nextRetrySeconds?.let { nowMs + (it * 1000.0).toLong() } ?: 0L, ev.manual, nowMs
                            ),
                            // An automatic failure does not answer the user's pending tap.
                            retryRequestedAt = if (ev.manual) 0L else d.retryRequestedAt
                        )
                        "joined" -> d.copy(
                            rejoin = BranchyRejoin(BranchyRejoinPhase.JOINED, null, 0L, ev.manual, nowMs), retryRequestedAt = 0L
                        )
                        else -> d
                    }
                }
            )
            is BranchyEvent.WorkerJoined -> {
                val left = state.waiting?.lost?.filter { it != ev.device }
                state.copy(
                    devices = mapDevice(ev.device) {
                        it.copy(up = true, waiting = false, offline = false, unreachable = false, lostReason = null, retryRequestedAt = 0L)
                    },
                    waiting = state.waiting?.let { w -> if (left.isNullOrEmpty()) null else w.copy(lost = left) }
                )
            }
            is BranchyEvent.ClusterWaiting -> state.copy(
                phase = if (state.phase == BranchyPhase.TRAIN || state.phase == BranchyPhase.WAITING_NETWORK) BranchyPhase.WAITING_NETWORK else state.phase,
                waitingRetryMs = ev.retryMs,
                waitingSinceMs = if (state.waitingSinceMs > 0L) state.waitingSinceMs else nowMs,
                dpSetup = null, micro = null,
                waiting = BranchyWaiting(ev.phase, ev.lost, ev.skippable),
                devices = state.devices.map { d ->
                    if (d.id in ev.lost) d.copy(up = false, waiting = true, offline = false) else d.copy(waiting = false)
                }
            )
            is BranchyEvent.ClusterResumed -> state.copy(
                phase = if (state.phase == BranchyPhase.WAITING_NETWORK) BranchyPhase.TRAIN else state.phase,
                waitingRetryMs = 0L, waitingSinceMs = 0L, waiting = null,
                devices = state.devices.map { d -> if (d.waiting) d.copy(up = true, waiting = false, lostReason = null) else d }
            )
            is BranchyEvent.Sync -> state.copy(syncRound = ev.round, syncBytes = ev.bytes, syncWorkers = ev.workers)
            is BranchyEvent.DpStart -> state.copy(clusterMode = "data_parallel", syncWorkers = ev.replicas, dpSetup = null)
            is BranchyEvent.DpRound -> {
                val history = state.roundHistory.toMutableMap()
                for (r in ev.devices) {
                    val prev = history[r.id].orEmpty()
                    history[r.id] = (prev + BranchyRoundStat(ev.round, r.mb, r.seconds, r.idleS))
                        .takeLast(MAX_HISTORY)
                }
                state.copy(
                    dpRound = ev, roundHistory = history,
                    dpStepMode = ev.mode?.let { m -> if (state.dpStepMode?.mode == m) state.dpStepMode else BranchyDpStepMode(m, null, 0.0, 0.0) } ?: state.dpStepMode,
                    devices = state.devices.map { d ->
                        val r = ev.devices.firstOrNull { it.id == d.id }
                        if (r == null) d else {
                            val lost = r.state == "lost"
                            d.copy(
                                tokS = r.tokS, rssMb = r.rssMb, steps = r.steps, lastSyncAt = nowMs,
                                mb = r.mb, mbDone = r.mbDone, seconds = r.seconds, msPerMb = r.msPerMb,
                                idleS = r.idleS, commS = r.commS, downS = r.downS,
                                idle = r.state == "idle_end", resting = r.state == "resting",
                                up = if (lost) false else if (r.state != null) true else d.up,
                                waiting = if (lost) d.waiting else if (r.state != null) false else d.waiting
                            )
                        }
                    }
                )
            }
            is BranchyEvent.DpMode -> state.copy(
                dpStepMode = BranchyDpStepMode(ev.mode, ev.reason, ev.computeS, ev.commS)
            )
            is BranchyEvent.DpAdvice -> state.copy(
                advice = state.advice + (ev.id to BranchyDpAdviceState(ev.verdict == "not_needed", ev.gainPct, ev.costPct, ev.rounds))
            )
            is BranchyEvent.BpAdvice -> state.copy(
                bpProposal = if (ev.verdict == "rebalance") {
                    BranchyBpProposal(
                        ev.step, ev.gainPct, ev.currentMs, ev.proposedMs,
                        ev.placement.associate { it.id to it.branches }, ev.applicable, ev.reason
                    )
                } else null
            )
            is BranchyEvent.Endpoint -> state.copy(devices = mapDevice(ev.id) { it.copy(endpointHost = ev.host) })
            is BranchyEvent.ControlAck -> state.copy(
                lastControlAck = ev,
                // A retry the native side refused (already connected, skipped, unknown) will never produce a rejoin event.
                devices = state.devices.map { d ->
                    if (d.retryRequestedAt > 0L && ev.ignored.any { it.target == d.id }) d.copy(retryRequestedAt = 0L) else d
                },
                checkpointRequest = state.checkpointRequest?.takeIf { it.stage == BranchyCkptStage.REQUESTED }?.let { r ->
                    val ignored = ev.ignored.firstOrNull { it.target == "checkpoint" }
                    when {
                        ignored != null -> r.copy(stage = BranchyCkptStage.FAILED, reason = ignored.reason, atMs = nowMs)
                        "checkpoint" in ev.applied -> r.copy(stage = BranchyCkptStage.ACKED, atMs = nowMs)
                        else -> r
                    }
                } ?: state.checkpointRequest
            )
            is BranchyEvent.CheckpointBegin -> state.copy(
                checkpointRequest = state.checkpointRequest?.let { r ->
                    if (ev.reason == "manual" && r.pending) r.copy(stage = BranchyCkptStage.SAVING, step = ev.step, atMs = nowMs) else r
                }
            )
            is BranchyEvent.Consolidate -> state.copy(
                checkpointRequest = state.checkpointRequest?.let { r ->
                    if (r.stage == BranchyCkptStage.SAVING && ev.totalBytes > 0L) r.copy(doneBytes = ev.doneBytes, totalBytes = ev.totalBytes) else r
                }
            )
            is BranchyEvent.Checkpoint -> state.copy(
                lastCheckpoint = BranchyCheckpointInfo(ev.step, ev.reason, nowMs),
                checkpointRequest = state.checkpointRequest?.let { r ->
                    if (ev.reason == "manual" && r.pending) r.copy(stage = BranchyCkptStage.SAVED, step = ev.step, atMs = nowMs) else r
                }
            )
            is BranchyEvent.Micro -> state.copy(micro = BranchyMicro(ev.step, ev.done, ev.total, ev.tokS, ev.rssMb))
            is BranchyEvent.DpSetup -> {
                val pct = if (ev.totalBytes > 0L) (ev.doneBytes * 100L / ev.totalBytes).toInt().coerceIn(0, 100) else 0
                val name = state.devices.firstOrNull { it.id == ev.device }?.name ?: names[ev.device] ?: ev.device
                state.copy(dpSetup = BranchyDpSetup(name, pct))
            }
            is BranchyEvent.Step -> {
                val base = state.copy(dpSetup = null, micro = null)
                val ms = ev.stepMsByDevice + ev.computeMsByDevice
                if (ms.isEmpty()) base
                else base.copy(devices = base.devices.map { d -> ms[d.id]?.let { d.copy(stepMs = it) } ?: d })
            }
            is BranchyEvent.Paused -> endRun(state).copy(rebalancing = ev.reason == "rebalance" && !ev.cluster.isNullOrBlank())
            is BranchyEvent.Done, is BranchyEvent.Error -> endRun(state)
            else -> state
        }
    }

    /** The run ended, paused or failed: nothing is connected, in progress or being waited for any more. */
    fun endRun(state: BranchyLiveState): BranchyLiveState = state.copy(
        dpSetup = null, micro = null, waiting = null, waitingRetryMs = 0L, waitingSinceMs = 0L,
        devices = state.devices.map { it.copy(offline = true, waiting = false) },
        bpProposal = null,
        checkpointRequest = state.checkpointRequest?.takeUnless { it.pending }
    )

    /** A new request when none is pending (the tap); null = ignore the tap. */
    fun requestCheckpoint(state: BranchyLiveState, nowMs: Long = System.currentTimeMillis()): BranchyLiveState =
        if (state.checkpointRequest?.pending == true) state
        else state.copy(checkpointRequest = BranchyCheckpointRequest(BranchyCkptStage.REQUESTED, nowMs))

    /** The user tapped Retry now for [id]: the row shows "trying" at once, before the native `rejoin{trying}` arrives. */
    fun requestRetry(state: BranchyLiveState, id: String, nowMs: Long = System.currentTimeMillis()): BranchyLiveState =
        state.copy(devices = state.devices.map { if (it.id == id) it.copy(retryRequestedAt = nowMs) else it })

    /** Drops a finished (SAVED/FAILED) request once it was shown long enough. */
    fun clearFinishedCheckpoint(state: BranchyLiveState, atMs: Long): BranchyLiveState =
        if (state.checkpointRequest?.let { !it.pending && it.atMs == atMs } == true) state.copy(checkpointRequest = null) else state

    /**
     * Seconds until the current optimizer step ends (when the pending checkpoint is written): remaining microbatches
     * from the latest `micro`, else one step at the last measured throughput; null when unknown.
     */
    fun stepEtaSeconds(state: BranchyLiveState): Double? {
        val perStepTokens = state.tokensPerStep.toDouble()
        val m = state.micro
        if (m != null && m.total > 0L && m.tokS > 0.0 && perStepTokens > 0.0) {
            return (m.total - m.done).coerceAtLeast(0L) * (perStepTokens / m.total) / m.tokS
        }
        val tokS = state.lastStep?.tokS ?: return null
        return if (tokS > 0.0 && perStepTokens > 0.0) perStepTokens / tokS else null
    }

    /** Folds a heartbeat probe into the helper rows (UI only; the native side decides about loss). */
    fun applyProbe(state: BranchyLiveState, probes: Map<String, BranchyHelperStatus>): BranchyLiveState {
        if (probes.isEmpty() || state.devices.isEmpty()) return state
        return state.copy(
            devices = state.devices.map { d ->
                val p = probes[d.id] ?: return@map d
                if (d.offline) d else d.copy(unreachable = !p.online, rttMs = if (p.online) p.rttMs else null)
            }
        )
    }

    fun appendSample(samples: List<String>, text: String, max: Int = MAX_SAMPLES): List<String> =
        (samples + text).takeLast(max)
}

/** Small formatting helpers shared by the notification and the UI. */
object BranchyFormat {
    /** `45s`, `12m 05s`, `3h 07m`, `2d 5h`; "-" for unknown or non-finite values. */
    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0.0) return "-"
        val s = seconds.toLong()
        return when {
            s < 60L -> "${s}s"
            s < 3600L -> "${s / 60}m ${pad2(s % 60)}s"
            s < 86_400L -> "${s / 3600}h ${pad2((s % 3600) / 60)}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }

    private fun pad2(v: Long): String = if (v < 10L) "0$v" else v.toString()

    fun loss(value: Double?): String =
        if (value == null || !value.isFinite()) "-" else "%.3f".format(java.util.Locale.US, value)
}
