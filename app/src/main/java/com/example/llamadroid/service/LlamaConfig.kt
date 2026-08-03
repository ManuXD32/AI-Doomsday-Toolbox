package com.example.llamadroid.service

data class LlamaConfig(
    val modelPath: String,
    val isEmbedding: Boolean = false,
    val contextSize: Int = 8192,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val physicalBatchSize: Int? = null,
    val port: Int = 8080,
    val temperature: Float = 0.8f,
    val host: String = "0.0.0.0",
    val mmprojPath: String? = null, // Vision model projector path
    /** null keeps llama.cpp's default; false emits --no-mmproj-offload. */
    val mmprojOffload: Boolean? = null,
    val loraPath: String? = null,
    // KV Cache quantization settings
    val kvCacheEnabled: Boolean = false,
    val kvCacheTypeK: String = "f16",  // f16, q8_0, q4_0
    val kvCacheTypeV: String = "f16",
    val kvCacheReuse: Int = 0,  // 0 = disabled, >0 = number of tokens to reuse
    val kvOffloadMode: String = LlamaKvOffloadMode.AUTO.value,
    // Distributed inference - RPC workers
    val rpcWorkers: List<String> = emptyList(), // List of worker addresses "ip:port"
    /** Ordered llama.cpp device names used by the target model, for example RPC0,RPC1. */
    val targetDevices: List<String> = emptyList(),
    val splitMode: String? = null,
    val mainGpu: Int? = null,
    // Number of layers to offload to RPC (calculated based on worker RAM vs master RAM)
    val nGpuLayers: Int = 0,
    /** Optional llama.cpp spelling for distributed fitting, normally `auto` or `all`. */
    val nGpuLayersArgument: String? = null,
    // Tensor split: proportion for EACH WORKER (not master): "worker1_prop,worker2_prop,..."
    // e.g., "0.60,0.40" for worker1 gets 60% of nGpuLayers, worker2 gets 40%
    val tensorSplit: String? = null,
    /** Distributed-only memory fitting controls. */
    val fitEnabled: Boolean = false,
    val fitTargetMiB: String? = null,
    // Disable memory mapping - loads entire model into RAM
    val noMmap: Boolean = false,
    // Speculative decoding (draft model) - runs draft locally on master
    val speculativeMode: LlamaSpeculativeMode? = null,
    val draftModelPath: String? = null,   // Path to draft GGUF model
    val draftMax: Int = 3,                // Max tokens to draft per step
    val draftMin: Int = 0,                // Min tokens to draft
    val draftPMin: Float = 0.0f,          // Min probability threshold for acceptance
    val draftThreads: Int = 4,            // CPU threads for draft model generation
    val draftThreadsBatch: Int = 4,       // CPU threads for draft model prompt/batch processing
    val draftDeviceMode: String = LlamaDraftDeviceMode.AUTO.value,
    /** Explicit llama.cpp device id, including RPC devices such as RPC0 when supported. */
    val draftDeviceId: String? = null,
    /** Exact, `auto`, or `all`; kept separate from the target-model -ngl value. */
    val draftGpuLayers: String? = null,
    /**
     * OpenCL-only placement override for the general local LLM profile. When enabled,
     * ProcessController places the target model on CPU and the speculative drafter on
     * GPU, while leaving the existing KV cache controls user-configurable.
     */
    val openClCpuTargetGpuDraft: Boolean = false,
    val mtpDraftMax: Int = 3,
    val mtpDraftMin: Int = 0,
    val mtpDraftPMin: Float = 0.0f,
    val ngramModNMatch: Int = 24,
    val ngramModNMin: Int = 48,
    val ngramModNMax: Int = 64,
    val ngramSimpleSizeN: Int = 12,
    val ngramSimpleSizeM: Int = 48,
    val ngramSimpleMinHits: Int = 1,
    val ngramMapKSizeN: Int = 12,
    val ngramMapKSizeM: Int = 48,
    val ngramMapKMinHits: Int = 1,
    val ngramMapK4VSizeN: Int = 12,
    val ngramMapK4VSizeM: Int = 48,
    val ngramMapK4VMinHits: Int = 1,
    val nativeToolsEnabled: Boolean = false,
    // Advanced overrides
    val parallel: Int? = null,
    val cacheRam: Int? = null,
    val contextCheckpoints: Int? = null,
    val checkpointMinStep: Int? = null,
    val cachePrompt: Boolean = true,
    val cacheIdleSlots: Boolean = true,
    val kvUnifiedMode: String = LlamaKvUnifiedMode.AUTO.value,
    val swaFull: Boolean = false,
    val sleepIdleSeconds: Int? = 1800,
    /** Distributed profiles disable implicit cache-policy flags that are not exposed in their UI. */
    val emitDefaultCachePolicyArgs: Boolean = true,
    val customFlags: String? = null,
    val flashAttention: Boolean = false
)

enum class LlamaKvUnifiedMode(val value: String) {
    AUTO("auto"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromValue(value: String?): LlamaKvUnifiedMode =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: AUTO
    }
}

enum class LlamaKvOffloadMode(val value: String) {
    AUTO("auto"),
    ACCELERATOR("accelerator"),
    CPU("cpu");

    companion object {
        fun fromValue(value: String?): LlamaKvOffloadMode =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: AUTO
    }
}

enum class LlamaDraftDeviceMode(val value: String) {
    AUTO("auto"),
    ACCELERATOR("accelerator"),
    CPU("cpu");

    companion object {
        fun fromValue(value: String?): LlamaDraftDeviceMode =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: AUTO
    }
}

sealed class ServerState {
    object Stopped : ServerState()
    object Starting : ServerState()
    data class Loading(val progress: Float, val status: String) : ServerState() // Model loading progress
    data class Running(val port: Int) : ServerState()
    data class Error(val message: String) : ServerState()
}
