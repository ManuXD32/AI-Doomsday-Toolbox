package com.example.llamadroid.branchy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/** A dataset (Room `training_dataset.id`) and its sampling weight inside one stage. */
@Serializable
data class MixEntry(val datasetId: Long, val weight: Double)

/** Battery guards polled every 30 s while a run is active. A value <= 0 disables that guard. */
@Serializable
data class TrainingGuards(
    val pauseBelowBatteryPct: Int = 20,
    val pauseWhenUnplugged: Boolean = false,
    val maxBatteryTempC: Int = 42
)

/**
 * One training stage of a run, resolved to Room dataset ids. A2 names this `RecipeStage`; that name is
 * taken by the recipe catalog type (which holds string resource ids and curated ids and cannot be
 * persisted), so the stored form is `RunStage`.
 */
@Serializable
data class RunStage(
    val name: String = "",
    val mix: List<MixEntry> = emptyList(),
    val tokensTarget: Double = 0.0,
    val lrScale: Double = 1.0
)

/** Stored in `branchy_run.configJson`. */
@Serializable
data class BranchyRunConfig(
    val mix: List<MixEntry> = emptyList(),
    val vocab: Int = 16384,
    val planInput: BranchyPlanInput = BranchyPlanInput(),
    /** The planner result the user accepted; computed by the service from [planInput] when null. */
    val plan: BranchyPlan? = null,
    /** The resolved run.json of the current stage, written before training. */
    val runJson: String? = null,
    val guards: TrainingGuards = TrainingGuards(),
    val autoResume: Boolean = false,
    val recipeId: String? = null,
    val stages: List<RunStage> = emptyList(),
    val currentStage: Int = 0,
    val allowStorageOffload: Boolean = false,
    /** Fields unlocked in the Auto plan, keyed `section.key` (for example `train.lr`), values as text. */
    val overrides: Map<String, String> = emptyMap(),
    val seed: Int = 1337,
    val threads: Int = 0,
    /** Helper devices chosen in the Devices step; null or empty = single-device run. */
    val cluster: ClusterSelection? = null,
    /** Passes over the selected data that cap the run's steps; 0 = no cap (old runs, recipes). */
    @SerialName("epochs") val epochs: Double = 0.0,
    /** Thread counts the user explicitly chose per helper (paused-run editor); unset helpers keep their own setting. */
    @SerialName("helper_threads") val helperThreads: Map<String, Int> = emptyMap()
) {
    fun encode(): String = BranchyJson.json.encodeToString(serializer(), this)

    /** Stages to run: the configured ones, or a single stage built from [mix] for custom runs. */
    fun effectiveStages(): List<RunStage> =
        stages.ifEmpty { listOf(RunStage(name = "", mix = mix, tokensTarget = 0.0, lrScale = 1.0)) }

    companion object {
        fun decode(json: String): BranchyRunConfig? =
            try { BranchyJson.json.decodeFromString(serializer(), json) } catch (_: Exception) { null }
    }
}

/** One `data.sources[]` entry of run.json. [path] is relative to the stage directory. */
data class RunSource(val path: String, val weight: Double)

/** Builds the run.json the native trainer reads (spec "File formats"). */
object BranchyRunJson {
    /** Optimizer steps needed to see [tokens] tokens, at least 1. */
    fun stepsForTokens(tokens: Double, microbatch: Int, gradAccum: Int, context: Int): Long {
        val perStep = microbatch.toDouble() * gradAccum * context
        if (perStep <= 0.0 || tokens <= 0.0) return 1L
        return (tokens / perStep).roundToLong().coerceAtLeast(1L)
    }

    /**
     * @param steps total optimizer steps of this stage, or null to use the plan's value
     * @param lrScale multiplier on the plan's learning rate (stage 2 fine-tuning uses less)
     * @param overrides `section.key` -> text, applied last and coerced to the existing value's type
     */
    fun build(
        name: String,
        seed: Int,
        threads: Int,
        plan: BranchyPlan,
        weightMode: String,
        tokenizerPath: String,
        sources: List<RunSource>,
        steps: Long? = null,
        lrScale: Double = 1.0,
        overrides: Map<String, String> = emptyMap()
    ): JsonObject {
        val m = plan.layout
        val t = plan.train
        val totalSteps = steps ?: t.steps
        val root = buildJsonObject {
            put("name", name)
            put("seed", seed)
            put("threads", threads)
            put("model", buildJsonObject {
                put("vocab", m.vocab)
                put("context", m.context)
                put("d_model", m.dModel)
                put("head_dim", m.headDim)
                put("n_head", m.nHead)
                put("n_head_kv", m.nHeadKv)
                put("trunk_blocks", m.trunkBlocks)
                put("branches", m.branches)
                put("branch_blocks", m.branchBlocks)
                put("branch_width", m.branchWidth)
                put("fusion_blocks", m.fusionBlocks)
                put("ffn_mult", m.ffnMult)
                put("qk_norm", m.qkNorm)
                put("tie_embeddings", m.tieEmbeddings)
                put("rope_theta", m.ropeTheta)
                put("delay", m.delay)
                put("local_branches", m.localBranches)
                put("drop_p", m.dropP)
                put("mtp", m.mtp)
                put("mtp_weight", m.mtpWeight)
            })
            put("train", buildJsonObject {
                put("weight_mode", weightMode)
                put("master_fp32", t.masterFp32)
                put("optimizer", t.optimizer)
                put("lr", t.lr * lrScale)
                put("min_lr_ratio", t.minLrRatio)
                put("warmup", t.warmup.toLong().coerceAtMost(totalSteps / 2))
                put("steps", totalSteps)
                put("microbatch", t.microbatch)
                put("grad_accum", t.gradAccum)
                put("grad_mode", t.gradMode)
                put("weight_decay", t.weightDecay)
                put("grad_clip", t.gradClip)
                put("recompute", t.recompute)
                put("eval_every", t.evalEvery.toLong().coerceAtMost(totalSteps).coerceAtLeast(1L))
                put("eval_batches", t.evalBatches)
                put("checkpoint_every", t.checkpointEvery.toLong().coerceAtMost(totalSteps).coerceAtLeast(1L))
                put("keep_checkpoints", t.keepCheckpoints)
                put("sample_every", t.sampleEvery)
                put("sample_prompt", t.samplePrompt)
                put("weight_storage", t.weightStorage)
            })
            put("data", buildJsonObject {
                put("tokenizer", tokenizerPath)
                put("sources", buildJsonArray {
                    sources.forEach { s ->
                        add(buildJsonObject { put("path", s.path); put("weight", s.weight) })
                    }
                })
            })
        }
        return applyOverrides(root, overrides)
    }

    fun toJsonString(root: JsonObject): String = BranchyJson.json.encodeToString(JsonObject.serializer(), root)

    fun applyOverrides(root: JsonObject, overrides: Map<String, String>): JsonObject {
        var out = root
        for ((path, raw) in overrides) {
            val parts = path.split('.')
            if (parts.size != 2) continue
            val section = out[parts[0]] as? JsonObject ?: continue
            val value = coerce(raw.trim(), section[parts[1]]) ?: continue
            out = JsonObject(out + (parts[0] to JsonObject(section + (parts[1] to value))))
        }
        return out
    }

    private fun coerce(raw: String, existing: JsonElement?): JsonPrimitive? {
        val prim = existing as? JsonPrimitive
        return when {
            prim != null && prim.isString -> JsonPrimitive(raw)
            prim != null && prim.booleanOrNull != null -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
            else -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                ?: raw.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
                ?: if (prim == null) JsonPrimitive(raw) else null
        }
    }
}

/** Why a run was paused. [USER] is the only reason that never auto-resumes. */
enum class BranchyPauseReason { USER, BATTERY_LOW, UNPLUGGED, BATTERY_HOT, RESTORED }

data class BatterySnapshot(val levelPct: Int, val pluggedIn: Boolean, val tempC: Double)

sealed class GuardAction {
    data object None : GuardAction()
    data class Pause(val reason: BranchyPauseReason) : GuardAction()
    data object Resume : GuardAction()
}

/** Pure guard logic; the service feeds it a [BatterySnapshot] every 30 s. */
object BranchyGuards {
    private const val LEVEL_HYSTERESIS_PCT = 5
    private const val TEMP_HYSTERESIS_C = 3.0

    /** The first tripped guard, or null. With [recovering] the resume thresholds (with hysteresis) apply. */
    fun violation(guards: TrainingGuards, snap: BatterySnapshot, recovering: Boolean): BranchyPauseReason? {
        if (guards.maxBatteryTempC > 0) {
            val limit = guards.maxBatteryTempC - if (recovering) TEMP_HYSTERESIS_C else 0.0
            if (snap.tempC > limit) return BranchyPauseReason.BATTERY_HOT
        }
        if (guards.pauseWhenUnplugged && !snap.pluggedIn) return BranchyPauseReason.UNPLUGGED
        if (guards.pauseBelowBatteryPct > 0 && !snap.pluggedIn) {
            val limit = guards.pauseBelowBatteryPct + if (recovering) LEVEL_HYSTERESIS_PCT else 0
            if (snap.levelPct < limit) return BranchyPauseReason.BATTERY_LOW
        }
        return null
    }

    /**
     * @param running a stage process is active
     * @param pausedByGuard the run is currently paused because of a guard (not by the user)
     */
    fun decide(
        guards: TrainingGuards,
        snap: BatterySnapshot,
        running: Boolean,
        pausedByGuard: Boolean,
        autoResume: Boolean
    ): GuardAction {
        if (running) {
            return violation(guards, snap, recovering = false)?.let { GuardAction.Pause(it) } ?: GuardAction.None
        }
        if (pausedByGuard && autoResume && violation(guards, snap, recovering = true) == null) return GuardAction.Resume
        return GuardAction.None
    }
}
