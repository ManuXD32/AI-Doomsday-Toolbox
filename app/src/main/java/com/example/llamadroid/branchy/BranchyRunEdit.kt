package com.example.llamadroid.branchy

import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import java.io.File

/** Where a paused run keeps the OLD cluster layout that `branchy consolidate` needs. */
data class ConsolidateTarget(val stageDir: File, val clusterJson: File)

/**
 * Pure logic of the paused-run device editor: placement-change detection, the fixed-layout plan input, the
 * consolidate decision and the config update. No Android or UI types, so it is unit-tested on the host.
 */
object BranchyRunEdit {
    const val MIN_HELPER_THREADS = 1
    const val MAX_HELPER_THREADS = 64

    /** Run-config override key of the checkpoint frequency (steps). */
    const val CHECKPOINT_KEY = "train.checkpoint_every"

    /** [overrides] with the checkpoint frequency set to [text] (digits, > 0), or removed when blank/invalid. */
    fun withCheckpointEvery(overrides: Map<String, String>, text: String): Map<String, String> {
        val value = text.trim().toLongOrNull()?.takeIf { it > 0L }
        return if (value == null) overrides - CHECKPOINT_KEY else overrides + (CHECKPOINT_KEY to value.toString())
    }

    /** Device id -> sorted branches, for devices that hold at least one branch. */
    fun placementKey(plan: BranchyPlan): Map<String, List<Int>> =
        plan.placement.filter { it.branches.isNotEmpty() }
            .associate { it.device to it.branches.sorted() }

    /** True when some branch moved to another device, or the plan's mode changed between branch/data parallel. */
    fun placementChanged(old: BranchyPlan?, new: BranchyPlan): Boolean {
        if (old == null) return true
        return old.mode != new.mode || placementKey(old) != placementKey(new)
    }

    /**
     * Whether the latest checkpoint must be made fully local before resuming: only a branch-parallel run keeps
     * checkpoint units on helpers, and only a changed placement (or a switch away from it) can strand them.
     * Data-parallel resume handles any peer set natively.
     */
    fun needsConsolidate(old: BranchyPlan?, new: BranchyPlan): Boolean =
        old != null && old.mode == "branch_parallel" &&
            (new.mode != "branch_parallel" || placementChanged(old, new))

    /** An edit may not change the model: the checkpoint only fits the layout it was trained with. */
    fun layoutUnchanged(old: BranchyPlan, new: BranchyPlan): Boolean = old.layout == new.layout

    /** Directory of the stage [stage] of a run (stage 0 is the run directory itself). */
    fun stageDir(runDir: File, stage: Int): File = if (stage == 0) runDir else File(runDir, "stage${stage + 1}")

    /**
     * The stage directory and its `cluster.json` when there is an old cluster layout and a checkpoint to
     * consolidate; null when the run never started a cluster (nothing lives on helpers).
     */
    fun consolidateTarget(runDir: File, stage: Int): ConsolidateTarget? {
        val dir = stageDir(runDir, stage)
        val cluster = File(dir, "cluster.json")
        val latest = File(dir, "checkpoints/latest")
        return if (cluster.isFile && latest.isFile) ConsolidateTarget(dir, cluster) else null
    }

    /** Planner devices for the edit: [BranchyClusterRun.planDevices] with the per-helper thread counts applied. */
    fun planDevices(
        local: BranchyDevice,
        helpers: List<BranchyClusterDeviceEntity>,
        helperThreads: Map<String, Int>,
        ramMb: Map<String, Long> = emptyMap(),
        gflops: Map<String, Double> = emptyMap(),
        linkMbps: Map<String, Double> = emptyMap()
    ): List<BranchyDevice> =
        BranchyClusterRun.planDevices(local, helpers, ramMb, gflops, linkMbps).map { d ->
            val t = helperThreads[d.id]
            if (d.id == local.id || t == null) d else d.copy(threads = t.coerceIn(MIN_HELPER_THREADS, MAX_HELPER_THREADS))
        }

    /**
     * Planner input of the edit: the run's saved input with the new [devices] and [mode], planning for the saved
     * plan's own layout ([BranchyPlanInput.fixedLayout]). A single device plans in `auto` mode.
     */
    fun planInput(base: BranchyPlanInput, fixedLayout: BranchyModelLayout, mode: String, devices: List<BranchyDevice>): BranchyPlanInput =
        base.copy(mode = if (devices.size <= 1) "auto" else mode, devices = devices, fixedLayout = fixedLayout)

    /** Threads each saved helper device was planned with (the master is `config.threads`). */
    fun savedHelperThreads(config: BranchyRunConfig): Map<String, Int> = config.helperThreads

    /**
     * The config to persist after a successful edit: new helper selection, plan, planner input (without the
     * one-shot fixed layout) and master threads. Everything else (mix, stages, guards, overrides) is kept.
     */
    fun applyEdit(
        config: BranchyRunConfig,
        helperIds: List<String>,
        mode: String,
        plan: BranchyPlan,
        planInput: BranchyPlanInput,
        masterThreads: Int,
        helperThreads: Map<String, Int> = emptyMap(),
        /** Checkpoint frequency field of the editor; blank = the plan's default. Defaults to the saved override. */
        checkpointEvery: String = config.overrides[CHECKPOINT_KEY].orEmpty()
    ): BranchyRunConfig = config.copy(
        overrides = withCheckpointEvery(config.overrides, checkpointEvery),
        planInput = planInput.copy(fixedLayout = null),
        // Keep the run's training knobs (microbatch, accumulation, steps, LR schedule): a resumed run must keep
        // the same tokens per step; only placement and memory come from the new plan.
        plan = config.plan?.let { old -> plan.copy(train = old.train) } ?: plan,
        threads = masterThreads,
        helperThreads = helperThreads.filterKeys { it in helperIds },
        cluster = if (helperIds.isEmpty()) null else ClusterSelection(helperIds, mode)
    )
}
