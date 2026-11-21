package com.example.llamadroid.branchy

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared lenient JSON codec for the Branchy planner, run config and events. */
object BranchyJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }

    /** Same codec without null keys: the native planner gets no `fixed_layout` key unless one is set. */
    @OptIn(ExperimentalSerializationApi::class)
    private val omitNulls: Json = Json(json) { explicitNulls = false }

    /** The text written to `branchy plan --in` (null fields, such as an unset `fixed_layout`, are omitted). */
    fun encodePlanInput(input: BranchyPlanInput): String =
        omitNulls.encodeToString(BranchyPlanInput.serializer(), input)
}

/** One device the planner may place work on: the local phone (id `local`) and, for a cluster, helper devices. */
@Serializable
data class BranchyDevice(
    val id: String = "local",
    @SerialName("ram_mb") val ramMb: Long = 0L,
    @SerialName("storage_mb") val storageMb: Long = 0L,
    val gflops: Double = 0.0,
    val threads: Int = 1,
    /** Network speed between this device and the coordinator (Mbit/s); the coordinator itself ignores it. */
    @SerialName("link_mbps") val linkMbps: Double = DEFAULT_LINK_MBPS
) {
    companion object {
        const val DEFAULT_LINK_MBPS = 50.0
    }
}

/** Input of `branchy plan --in` (spec "Planner" plus the A1 fields). */
@Serializable
data class BranchyPlanInput(
    @SerialName("target_params") val targetParams: Double = 30e6,
    val context: Int = 1024,
    @SerialName("weight_mode") val weightMode: String = "q8",
    val mtp: Boolean = false,
    val vocab: Int = 16384,
    @SerialName("tokens_target") val tokensTarget: Double = 3e8,
    @SerialName("allow_optimizer_offload") val allowOptimizerOffload: Boolean = false,
    @SerialName("allow_storage_offload") val allowStorageOffload: Boolean = false,
    @SerialName("prefer_branches") val preferBranches: Int = 4,
    /** `auto`, `branch_parallel` or `data_parallel`; only matters with more than one device. */
    val mode: String = "auto",
    val devices: List<BranchyDevice> = emptyList(),
    /**
     * Paused-run edit: skip the layout search and plan placement and memory for this exact layout (the saved
     * plan's `layout`). Null = normal planning; omitted from the JSON when null.
     */
    @SerialName("fixed_layout") val fixedLayout: BranchyModelLayout? = null
)

/** The `model` block of run.json, as chosen by the planner. */
@Serializable
data class BranchyModelLayout(
    val vocab: Int = 16384,
    val context: Int = 1024,
    @SerialName("d_model") val dModel: Int = 512,
    @SerialName("head_dim") val headDim: Int = 64,
    @SerialName("n_head") val nHead: Int = 8,
    @SerialName("n_head_kv") val nHeadKv: Int = 8,
    @SerialName("trunk_blocks") val trunkBlocks: Int = 2,
    val branches: Int = 4,
    @SerialName("branch_blocks") val branchBlocks: Int = 4,
    @SerialName("branch_width") val branchWidth: Int = 256,
    @SerialName("fusion_blocks") val fusionBlocks: Int = 2,
    @SerialName("ffn_mult") val ffnMult: Double = 2.6667,
    @SerialName("qk_norm") val qkNorm: Boolean = true,
    @SerialName("tie_embeddings") val tieEmbeddings: Boolean = true,
    @SerialName("rope_theta") val ropeTheta: Double = 10000.0,
    val delay: Int = 1,
    @SerialName("local_branches") val localBranches: Int = 1,
    @SerialName("drop_p") val dropP: Double = 0.1,
    val mtp: Boolean = false,
    @SerialName("mtp_weight") val mtpWeight: Double = 0.3
)

/** Planner-chosen training knobs (spec `train{}` plus A1 `grad_mode`). Other run.json train keys use defaults here. */
@Serializable
data class BranchyPlanTrain(
    val microbatch: Int = 4,
    @SerialName("grad_accum") val gradAccum: Int = 8,
    val recompute: String = "none",
    val optimizer: String = "adamw",
    @SerialName("weight_storage") val weightStorage: String = "ram",
    @SerialName("master_fp32") val masterFp32: Boolean = true,
    @SerialName("checkpoint_every") val checkpointEvery: Int = 250,
    val lr: Double = 6e-4,
    val warmup: Int = 200,
    val steps: Long = 20000L,
    @SerialName("grad_mode") val gradMode: String = "f32",
    @SerialName("min_lr_ratio") val minLrRatio: Double = 0.1,
    @SerialName("weight_decay") val weightDecay: Double = 0.1,
    @SerialName("grad_clip") val gradClip: Double = 1.0,
    @SerialName("eval_every") val evalEvery: Int = 250,
    @SerialName("eval_batches") val evalBatches: Int = 20,
    @SerialName("keep_checkpoints") val keepCheckpoints: Int = 2,
    @SerialName("sample_every") val sampleEvery: Int = 1000,
    @SerialName("sample_prompt") val samplePrompt: String = "Hello"
)

@Serializable
data class BranchyPlacement(
    val device: String = "local",
    val role: String = "coordinator",
    val branches: List<Int> = emptyList()
)

/** Branch-parallel traffic of one helper (planner `links[]`). */
@Serializable
data class BranchyPlanLink(
    val id: String = "",
    val branches: Int = 0,
    @SerialName("wire_mb_microbatch") val wireMbMicrobatch: Double = 0.0,
    @SerialName("wire_mb_step") val wireMbStep: Double = 0.0,
    @SerialName("need_mbps") val needMbps: Double = 0.0,
    @SerialName("link_mbps") val linkMbps: Double = 0.0,
    val slow: Boolean = false
)

@Serializable
data class BranchyDeviceUsage(
    val id: String = "local",
    @SerialName("peak_mb") val peakMb: Double = 0.0,
    @SerialName("budget_mb") val budgetMb: Double = 0.0
)

/** A device the plan leaves without work; [reason] is `ram`, `dp_not_fit`, `no_branch_left` or `slower`. */
@Serializable
data class BranchyUnusedDevice(
    val id: String = "",
    val reason: String = ""
)

/** Output of `branchy plan` (the `plan` event minus `type`). */
@Serializable
data class BranchyPlan(
    val fits: Boolean = false,
    val reason: String? = null,
    val layout: BranchyModelLayout = BranchyModelLayout(),
    val params: Double = 0.0,
    val placement: List<BranchyPlacement> = emptyList(),
    val train: BranchyPlanTrain = BranchyPlanTrain(),
    @SerialName("per_device") val perDevice: List<BranchyDeviceUsage> = emptyList(),
    @SerialName("tok_s") val tokS: Double = 0.0,
    @SerialName("eta_s") val etaS: Double = 0.0,
    val warnings: List<String> = emptyList(),
    @SerialName("needs_more_ram_mb") val needsMoreRamMb: Double? = null,
    /** `single`, `branch_parallel` (saves memory) or `data_parallel` (more speed). */
    val mode: String = "single",
    @SerialName("pipeline_depth") val pipelineDepth: Int = 1,
    val overlap: Boolean = false,
    /** Estimated speed-up over the coordinator alone (1 for a single device). */
    val speedup: Double = 1.0,
    /** Branch-parallel bottleneck: `coordinator`, `worker` or `link`. */
    val limit: String? = null,
    val links: List<BranchyPlanLink> = emptyList(),
    /** Data-parallel local-SGD interval and delta bits when the planner chose them (null = defaults). */
    @SerialName("sync_every") val syncEvery: Int? = null,
    @SerialName("delta_bits") val deltaBits: Int? = null,
    /** Devices that get no work and why (always present in new planners, empty otherwise). */
    val unused: List<BranchyUnusedDevice> = emptyList()
) {
    /** True when the plan spreads work over helper devices. */
    val isCluster: Boolean get() = mode == "branch_parallel" || mode == "data_parallel"

    /** Why [deviceId] gets no work, or null when it is used. */
    fun unusedReason(deviceId: String): String? = unused.firstOrNull { it.id == deviceId }?.reason

    /** The planner's "Data-parallel was requested but the plan uses branch-parallel" warning, if present. */
    val dpFallbackWarning: String? get() = warnings.firstOrNull { it.startsWith(DP_FALLBACK_PREFIX) }

    companion object {
        const val DP_FALLBACK_PREFIX = "Data-parallel was requested but"
    }
}
