package com.example.llamadroid.service

import android.util.Log
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.NativeModuleCatalog
import com.example.llamadroid.util.NativeProcessCleanup
import java.security.MessageDigest
import kotlin.math.min

data class ProcessRunResult(
    val exitCode: Int,
    val becameReady: Boolean,
    val stoppedIntentionally: Boolean,
    val acceleratorBackendUnavailable: Boolean = false,
    val acceleratorBackendDegraded: Boolean = false,
    val recentOutput: List<String> = emptyList(),
    val startupFailureMessage: String? = null,
    val nativeLinkerStartupFailure: Boolean = false
)

class ProcessController {
    
    private var process: Process? = null
    private var launchGeneration = 0L
    @Volatile private var stopRequestedGeneration = 0L
    @Volatile private var activeChildPid: Int = -1
    @Volatile private var activeBinaryWasOpenCl: Boolean = false
    private val _logs = MutableStateFlow<String>("")
    val logs = _logs.asStateFlow()
    
    // Flag to distinguish user-initiated stop from error
    @Volatile
    var stoppedIntentionally = false
        private set

    internal fun resolveExitState(exitCode: Int, errorMessage: String): ServerState {
        return if (stoppedIntentionally) {
            ServerState.Stopped
        } else {
            ServerState.Error(errorMessage)
        }
    }

    internal fun classifyNativeLinkerStartupFailure(lines: List<String>): String? {
        val joined = lines.joinToString("\n")
        val lower = joined.lowercase()
        val linkerFailure = lower.contains("cannot link executable") ||
            lower.contains("can't enable gnu relro protection") ||
            lower.contains("gnu relro")
        if (!linkerFailure) return null
        return lines.lastOrNull { line ->
            val candidate = line.lowercase()
            candidate.contains("cannot link executable") ||
                candidate.contains("can't enable gnu relro protection") ||
                candidate.contains("out of memory") ||
                candidate.contains("gnu relro")
        } ?: joined.takeLast(240)
    }
    

    fun getCommand(binaryPath: String, config: LlamaConfig): List<String> {
        val forceOpenClCpuTargetGpuDraft = shouldForceOpenClCpuTargetGpuDraft(binaryPath, config)
        val rawCustomFlagsArgs = splitCommandLine(config.customFlags.orEmpty())
        val baseCustomFlagsArgs = filterDistributedLlamaCustomFlags(
            filterManagedLlamaCustomFlags(rawCustomFlagsArgs, config),
            config
        )
        val customFlagsArgs = if (forceOpenClCpuTargetGpuDraft) {
            filterOpenClCpuTargetGpuDraftConflicts(baseCustomFlagsArgs)
        } else {
            baseCustomFlagsArgs
        }
        val customFlagsText = buildCommandString(customFlagsArgs)
        val args = mutableListOf(
            binaryPath,
            "-m", config.modelPath,
            "-c", config.contextSize.toString(),
            "-t", config.threads.toString(),
            "-b", config.batchSize.toString(),
            "--port", config.port.toString(),
            "--host", config.host
        )
        config.physicalBatchSize?.let { physicalBatchSize ->
            args.add("--ubatch-size")
            args.add(physicalBatchSize.toString())
        }
        
        // Add vision model projector if available
        if (config.mmprojPath != null) {
            args.add("--mmproj")
            args.add(config.mmprojPath)
            config.mmprojOffload?.let { offload ->
                args.add(if (offload) "--mmproj-offload" else "--no-mmproj-offload")
            }
        }

        if (!config.loraPath.isNullOrBlank() &&
            !hasAnyCommandFlag(customFlagsText, setOf("--lora"))
        ) {
            args.add("--lora")
            args.add(config.loraPath)
        }
        
        if (config.isEmbedding) {
            args.add("--embedding")
        } else {
             // Chat specific params
             args.add("--temp")
             args.add(config.temperature.toString())
        }
        
        if (config.kvCacheEnabled) {
            // KV cache quantization remains user-controlled, including when the OpenCL placement
            // switch is enabled.
            args.add("--cache-type-k")
            args.add(config.kvCacheTypeK)
            args.add("--cache-type-v")
            args.add(config.kvCacheTypeV)
            if (config.kvCacheReuse > 0) {
                args.add("--cache-reuse")
                args.add(config.kvCacheReuse.toString())
            }
        }
        appendKvOffloadArgs(args, config, customFlagsText)
        if (forceOpenClCpuTargetGpuDraft) {
            // The OpenCL switch fixes only target/drafter placement and backend sampling. KV
            // offload and quantization remain controlled by the existing general settings.
            args.add("--device")
            args.add("none")
            args.add("-ngl")
            args.add("0")
            args.add("--no-spec-draft-backend-sampling")
        }
        
        // Add RPC workers for distributed inference
        if (config.rpcWorkers.isNotEmpty()) {
            val rpcArg = config.rpcWorkers.joinToString(",")
            val fitTarget = if (config.fitEnabled) {
                DistributedLlamaArguments.normalizeFitTarget(config.fitTargetMiB, config.rpcWorkers.size)
            } else {
                null
            }
            val distributedDeviceCount = config.targetDevices.size.coerceAtLeast(config.rpcWorkers.size)
            DistributedLlamaArguments.validate(
                deviceCount = distributedDeviceCount,
                fitEnabled = config.fitEnabled,
                fitTargetMiB = config.fitTargetMiB,
                tensorSplit = config.tensorSplit.takeIf { distributedDeviceCount > 1 }
            )
            args.add("--rpc")
            args.add(rpcArg)
            if (config.targetDevices.isNotEmpty()) {
                args.add("--device")
                args.add(config.targetDevices.joinToString(","))
            }
            config.splitMode?.takeIf(String::isNotBlank)?.let {
                args.add("--split-mode")
                args.add(it)
            }
            config.mainGpu?.let {
                args.add("--main-gpu")
                args.add(it.toString())
            }
            // Explicit distributed placement never falls back to llama.cpp's automatic
            // offload amount. CPU-only target placement intentionally emits zero.
            config.nGpuLayersArgument?.trim()?.takeIf { it.isNotEmpty() }?.let {
                args.add("-ngl")
                args.add(it)
            }
            if (config.fitEnabled) {
                args.add("--fit")
                args.add("on")
            }
            fitTarget?.let {
                args.add("--fit-target")
                args.add(it)
            }
            
            // Use -ts to split the offloaded layers among multiple workers
            // Only needed when there are 2+ workers
            if (!config.tensorSplit.isNullOrEmpty() && distributedDeviceCount > 1) {
                args.add("-ts")
                args.add(DistributedLlamaArguments.normalizeTensorSplit(config.tensorSplit, distributedDeviceCount)!!)
            }
        }

        if (!forceOpenClCpuTargetGpuDraft &&
            DeviceAcceleration.isAcceleratorBinary(File(binaryPath)) &&
            config.rpcWorkers.isEmpty() &&
            "-ngl" !in customFlagsText &&
            "--n-gpu-layers" !in customFlagsText
        ) {
            args.add("-ngl")
            args.add("999")
        }
        
        // Add --no-mmap flag if memory mapping is disabled
        if (config.noMmap) {
            args.add("--no-mmap")
        }
        
        val speculativeConfig = if (forceOpenClCpuTargetGpuDraft) {
            config.copy(
                customFlags = null,
                draftDeviceMode = LlamaDraftDeviceMode.ACCELERATOR.value,
                draftDeviceId = "GPUOpenCL",
                draftGpuLayers = "all"
            )
        } else {
            config
        }
        val speculativeArgs = buildSpeculativeArgs(speculativeConfig)
        args.addAll(speculativeArgs)
        if (forceOpenClCpuTargetGpuDraft &&
            !hasAnyCommandFlag(
                speculativeArgs,
                setOf("--device-draft", "--spec-draft-device", "-devd")
            )
        ) {
            args.add("--spec-draft-device")
            args.add("GPUOpenCL")
            args.add("--spec-draft-ngl")
            args.add("all")
        }

        // Advanced Settings
        if (config.parallel != null) {
            args.add("--parallel")
            args.add(config.parallel.toString())
        } else if (config.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP &&
            !hasAnyCommandFlag(customFlagsText, setOf("--parallel", "-np"))
        ) {
            args.add("--parallel")
            args.add("1")
        }
        if (config.cacheRam != null) {
            args.add("--cache-ram")
            args.add(config.cacheRam.toString())
        }
        config.contextCheckpoints?.let {
            args.add("--ctx-checkpoints")
            args.add(it.toString())
        }
        config.checkpointMinStep?.let {
            args.add("--checkpoint-min-step")
            args.add(it.toString())
        }
        if (config.emitDefaultCachePolicyArgs) {
            args.add(if (config.cachePrompt) "--cache-prompt" else "--no-cache-prompt")
            args.add(if (config.cacheIdleSlots) "--cache-idle-slots" else "--no-cache-idle-slots")
            when (LlamaKvUnifiedMode.fromValue(config.kvUnifiedMode)) {
                LlamaKvUnifiedMode.ENABLED -> args.add("--kv-unified")
                LlamaKvUnifiedMode.DISABLED -> args.add("--no-kv-unified")
                LlamaKvUnifiedMode.AUTO -> Unit
            }
            if (config.swaFull) args.add("--swa-full")
            config.sleepIdleSeconds?.let {
                args.add("--sleep-idle-seconds")
                args.add(it.toString())
            }
        }
        
        args.add("--flash-attn")
        args.add(if (config.flashAttention) "on" else "off")

        args.addAll(customFlagsArgs)

        if (config.nativeToolsEnabled && !hasAnyCommandFlag(customFlagsText, setOf("--tools"))) {
            args.add("--tools")
            args.add("all")
        }

        return args
    }

    fun splitCommandLine(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuotes = false
        var inDoubleQuotes = false
        var escaping = false

        command.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' && !inSingleQuotes -> escaping = true
                ch == '\'' && !inDoubleQuotes -> inSingleQuotes = !inSingleQuotes
                ch == '"' && !inSingleQuotes -> inDoubleQuotes = !inDoubleQuotes
                ch.isWhitespace() && !inSingleQuotes && !inDoubleQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }

        require(!inSingleQuotes && !inDoubleQuotes && !escaping) {
            "Command contains incomplete quoting or escaping"
        }

        if (current.isNotEmpty()) {
            tokens += current.toString()
        }

        return tokens
    }

    fun buildCommandString(args: List<String>): String =
        args.joinToString(" ") { shellEscape(it) }

    fun containsDistributedOnlyArgument(args: List<String>): Boolean =
        args.any { token ->
            val flag = token.substringBefore('=')
            flag in setOf("--rpc", "--fit", "--fit-target", "--tensor-split", "-ts")
        }

    fun renderCommandTemplate(
        template: String,
        binaryPath: String,
        config: LlamaConfig
    ): List<String> {
        if (template.isBlank()) return getCommand(binaryPath, config)

        val defaultArgs = getCommand(binaryPath, config)
        val templateContainsNativeToolsPlaceholder = template.contains("{native_tools_args}")
        val templateContainsLoraPlaceholder =
            template.contains("{lora}") || template.contains("{lora_args}")
        val substituted = substituteTemplateValues(template, binaryPath, config, defaultArgs)
        val renderedArgs = splitCommandLine(substituted).filter { it.isNotBlank() }.let { args ->
            val scopedArgs = if (config.rpcWorkers.isEmpty()) {
                filterDistributedLlamaCustomFlags(args, config)
            } else args
            val withTools = appendNativeToolsArgsIfNeeded(
                args = scopedArgs,
                enabled = config.nativeToolsEnabled && !templateContainsNativeToolsPlaceholder
            )
            appendLoraArgsIfNeeded(
                args = withTools,
                loraPath = config.loraPath.takeUnless {
                    templateContainsLoraPlaceholder
                }
            )
        }
        if (renderedArgs.isEmpty()) return defaultArgs

        val effectiveRenderedArgs = enforceOpenClCpuTargetGpuDraftArgs(
            args = renderedArgs,
            binaryPath = binaryPath,
            config = config
        )

        val hasExplicitBinary = template.contains("{binary}") ||
            effectiveRenderedArgs.firstOrNull() == binaryPath ||
            effectiveRenderedArgs.firstOrNull()?.startsWith("-") == false

        return if (hasExplicitBinary) effectiveRenderedArgs else listOf(binaryPath) + effectiveRenderedArgs
    }

    private fun substituteTemplateValues(
        template: String,
        binaryPath: String,
        config: LlamaConfig,
        defaultArgs: List<String>
    ): String {
        val forceOpenClCpuTargetGpuDraft = shouldForceOpenClCpuTargetGpuDraft(binaryPath, config)
        val customFlagsArgs = filterDistributedLlamaCustomFlags(
            filterManagedLlamaCustomFlags(splitCommandLine(config.customFlags.orEmpty()), config),
            config
        )
        val speculativeConfig = if (forceOpenClCpuTargetGpuDraft) {
            config.copy(
                customFlags = null,
                draftDeviceMode = LlamaDraftDeviceMode.ACCELERATOR.value,
                draftDeviceId = "GPUOpenCL",
                draftGpuLayers = "all"
            )
        } else {
            config
        }
        val speculativeArgs = buildSpeculativeArgs(speculativeConfig)
        val mtpArgs = if (config.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) speculativeArgs else emptyList()
        val nativeToolsArgs = buildNativeToolsArgs(config.nativeToolsEnabled)
        val loraArgs = config.loraPath
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf("--lora", it) }
            .orEmpty()
        val kvOffloadArgs = buildKvOffloadArgs(config, customFlagsArgs)
        val kvCacheArgs = if (config.kvCacheEnabled) {
            buildList {
                add("--cache-type-k")
                add(config.kvCacheTypeK)
                add("--cache-type-v")
                add(config.kvCacheTypeV)
                if (config.kvCacheReuse > 0) {
                    add("--cache-reuse")
                    add(config.kvCacheReuse.toString())
                }
            }
        } else {
            emptyList()
        }

        val values = linkedMapOf(
            "{binary}" to binaryPath,
            "{model}" to config.modelPath,
            "{draft_model}" to (config.draftModelPath ?: ""),
            "{mmproj}" to (config.mmprojPath ?: ""),
            "{lora}" to (config.loraPath ?: ""),
            "{threads}" to config.threads.toString(),
            "{batch_size}" to config.batchSize.toString(),
            "{physical_batch_size}" to (config.physicalBatchSize ?: config.batchSize).toString(),
            "{context_size}" to config.contextSize.toString(),
            "{temperature}" to String.format(java.util.Locale.US, "%.2f", config.temperature),
            "{host}" to config.host,
            "{port}" to config.port.toString(),
            "{flash_attention}" to if (config.flashAttention) "on" else "off",
            "{parallel}" to (config.parallel?.toString() ?: ""),
            "{cache_ram}" to (config.cacheRam?.toString() ?: ""),
            "{kv_cache_type_k}" to config.kvCacheTypeK,
            "{kv_cache_type_v}" to config.kvCacheTypeV,
            "{kv_cache_reuse}" to config.kvCacheReuse.toString(),
            "{rpc_workers}" to config.rpcWorkers.joinToString(","),
            "{n_gpu_layers}" to config.nGpuLayers.toString(),
            "{n_gpu_layers_argument}" to (config.nGpuLayersArgument ?: config.nGpuLayers.toString()),
            "{tensor_split}" to (config.tensorSplit ?: ""),
            "{fit}" to if (config.fitEnabled) "on" else "off",
            "{fit_target}" to (config.fitTargetMiB ?: ""),
            "{custom_flags}" to buildCommandString(customFlagsArgs),
            "{default_args}" to buildCommandString(defaultArgs.drop(1)),
            "{speculative_args}" to buildCommandString(speculativeArgs),
            "{mtp_args}" to buildCommandString(mtpArgs),
            "{native_tools_args}" to buildCommandString(nativeToolsArgs),
            "{lora_args}" to buildCommandString(loraArgs),
            "{kv_cache_args}" to buildCommandString(kvCacheArgs + kvOffloadArgs),
            "{kv_offload_args}" to buildCommandString(kvOffloadArgs),
            "{draft_device_args}" to buildCommandString(
                buildDraftDeviceArgs(
                    speculativeConfig,
                    if (forceOpenClCpuTargetGpuDraft) emptyList() else customFlagsArgs
                )
            ),
            "{opencl_cpu_target_gpu_draft_args}" to buildCommandString(
                if (forceOpenClCpuTargetGpuDraft) {
                    listOf(
                        "--device", "none", "-ngl", "0",
                        "--no-spec-draft-backend-sampling"
                    )
                } else {
                    emptyList()
                }
            )
        )

        var rendered = template
        values.forEach { (placeholder, value) ->
            rendered = rendered.replace(placeholder, value)
        }
        return rendered.trim()
    }

    private fun appendLoraArgsIfNeeded(
        args: List<String>,
        loraPath: String?
    ): List<String> {
        if (loraPath.isNullOrBlank() ||
            hasAnyCommandFlag(args, setOf("--lora"))
        ) return args
        return args + listOf("--lora", loraPath)
    }

    private fun buildSpeculativeArgs(config: LlamaConfig): List<String> {
        return when (config.speculativeMode) {
            null -> emptyList()
            LlamaSpeculativeMode.DRAFT_SIMPLE -> {
                val draftModel = config.draftModelPath ?: return emptyList()
                listOf(
                    "--spec-type", config.speculativeMode.flagValue,
                    "--spec-draft-model", draftModel,
                    "--spec-draft-n-max", config.draftMax.coerceAtLeast(1).toString(),
                    "--spec-draft-n-min", config.draftMin.coerceAtLeast(0).toString(),
                    "--spec-draft-p-min", String.format(java.util.Locale.US, "%.2f", config.draftPMin.coerceIn(0f, 1f))
                ) + buildDraftThreadArgs(config) + buildDraftDeviceArgs(config, config.customFlags.orEmpty())
            }
            LlamaSpeculativeMode.DRAFT_MTP -> buildList {
                add("--spec-type")
                add(config.speculativeMode.flagValue)
                config.draftModelPath?.let { draftModel ->
                    add("--spec-draft-model")
                    add(draftModel)
                    addAll(buildDraftThreadArgs(config))
                }
                add("--spec-draft-n-max")
                add(config.mtpDraftMax.coerceAtLeast(1).toString())
                add("--spec-draft-n-min")
                add(config.mtpDraftMin.coerceAtLeast(0).toString())
                add("--spec-draft-p-min")
                add(String.format(java.util.Locale.US, "%.2f", config.mtpDraftPMin.coerceIn(0f, 1f)))
                addAll(buildDraftDeviceArgs(config, config.customFlags.orEmpty()))
            }
            LlamaSpeculativeMode.DRAFT_DFLASH -> {
                val draftModel = config.draftModelPath ?: return emptyList()
                listOf(
                    "--spec-type", config.speculativeMode.flagValue,
                    "-md", draftModel,
                    "--spec-draft-n-max", config.draftMax.coerceAtLeast(1).toString()
                ) + buildDraftThreadArgs(config) + buildDraftDeviceArgs(config, config.customFlags.orEmpty())
            }
            LlamaSpeculativeMode.DRAFT_DSPARK -> {
                val draftModel = config.draftModelPath ?: return emptyList()
                listOf(
                    "--spec-type", config.speculativeMode.flagValue,
                    "--spec-draft-model", draftModel,
                    "--spec-draft-n-max", config.draftMax.coerceAtLeast(1).toString(),
                    "--spec-draft-n-min", config.draftMin.coerceAtLeast(0).toString(),
                    "--spec-draft-p-min", String.format(java.util.Locale.US, "%.2f", config.draftPMin.coerceIn(0f, 1f))
                ) + buildDraftThreadArgs(config) + buildDraftDeviceArgs(config, config.customFlags.orEmpty())
            }
            LlamaSpeculativeMode.NGRAM_MOD -> listOf(
                "--spec-type", config.speculativeMode.flagValue,
                "--spec-ngram-mod-n-min", config.ngramModNMin.coerceAtLeast(1).toString(),
                "--spec-ngram-mod-n-max", config.ngramModNMax.coerceAtLeast(config.ngramModNMin.coerceAtLeast(1)).toString(),
                "--spec-ngram-mod-n-match", config.ngramModNMatch.coerceAtLeast(1).toString()
            )
            LlamaSpeculativeMode.NGRAM_SIMPLE -> listOf(
                "--spec-type", config.speculativeMode.flagValue,
                "--spec-ngram-simple-size-n", config.ngramSimpleSizeN.coerceAtLeast(1).toString(),
                "--spec-ngram-simple-size-m", config.ngramSimpleSizeM.coerceAtLeast(1).toString(),
                "--spec-ngram-simple-min-hits", config.ngramSimpleMinHits.coerceAtLeast(1).toString()
            )
            LlamaSpeculativeMode.NGRAM_MAP_K -> listOf(
                "--spec-type", config.speculativeMode.flagValue,
                "--spec-ngram-map-k-size-n", config.ngramMapKSizeN.coerceAtLeast(1).toString(),
                "--spec-ngram-map-k-size-m", config.ngramMapKSizeM.coerceAtLeast(1).toString(),
                "--spec-ngram-map-k-min-hits", config.ngramMapKMinHits.coerceAtLeast(1).toString()
            )
            LlamaSpeculativeMode.NGRAM_MAP_K4V -> listOf(
                "--spec-type", config.speculativeMode.flagValue,
                "--spec-ngram-map-k4v-size-n", config.ngramMapK4VSizeN.coerceAtLeast(1).toString(),
                "--spec-ngram-map-k4v-size-m", config.ngramMapK4VSizeM.coerceAtLeast(1).toString(),
                "--spec-ngram-map-k4v-min-hits", config.ngramMapK4VMinHits.coerceAtLeast(1).toString()
            )
            LlamaSpeculativeMode.NGRAM_CACHE -> listOf(
                "--spec-type", config.speculativeMode.flagValue
            )
        }
    }

    private fun buildDraftThreadArgs(config: LlamaConfig): List<String> = listOf(
        "--spec-draft-threads", config.draftThreads.coerceIn(1, 16).toString(),
        "--spec-draft-threads-batch", config.draftThreadsBatch.coerceIn(1, 16).toString()
    )

    private fun appendKvOffloadArgs(args: MutableList<String>, config: LlamaConfig, customFlagsText: String) {
        args.addAll(buildKvOffloadArgs(config, customFlagsText))
    }

    private fun buildKvOffloadArgs(config: LlamaConfig, customFlagsText: String): List<String> =
        buildKvOffloadArgs(config, splitCommandLine(customFlagsText))

    private fun buildKvOffloadArgs(config: LlamaConfig, customFlagsArgs: List<String>): List<String> {
        if (hasAnyCommandFlag(customFlagsArgs, setOf("--kv-offload", "-kvo", "--no-kv-offload", "-nkvo"))) {
            return emptyList()
        }
        return when (LlamaKvOffloadMode.fromValue(config.kvOffloadMode)) {
            LlamaKvOffloadMode.AUTO -> emptyList()
            LlamaKvOffloadMode.ACCELERATOR -> listOf("--kv-offload")
            LlamaKvOffloadMode.CPU -> listOf("--no-kv-offload")
        }
    }

    private fun buildDraftDeviceArgs(config: LlamaConfig, customFlagsText: String): List<String> =
        buildDraftDeviceArgs(config, splitCommandLine(customFlagsText))

    private fun buildDraftDeviceArgs(config: LlamaConfig, customFlagsArgs: List<String>): List<String> {
        if (hasAnyCommandFlag(customFlagsArgs, setOf(
                "--device-draft", "--spec-draft-device", "-devd",
                "--gpu-layers-draft", "--spec-draft-ngl", "-ngld"
            ))) {
            return emptyList()
        }
        if (!config.draftDeviceId.isNullOrBlank() || !config.draftGpuLayers.isNullOrBlank()) {
            return buildList {
                config.draftDeviceId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("--spec-draft-device")
                    add(it)
                }
                config.draftGpuLayers?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add("--spec-draft-ngl")
                    add(it)
                }
            }
        }
        return when (LlamaDraftDeviceMode.fromValue(config.draftDeviceMode)) {
            LlamaDraftDeviceMode.AUTO -> emptyList()
            LlamaDraftDeviceMode.CPU -> listOf("--device-draft", "none", "--gpu-layers-draft", "0")
            LlamaDraftDeviceMode.ACCELERATOR -> listOf("--device-draft", "GPUOpenCL", "--gpu-layers-draft", "all")
        }
    }

    private fun buildNativeToolsArgs(enabled: Boolean): List<String> =
        if (enabled) listOf("--tools", "all") else emptyList()

    private fun appendNativeToolsArgsIfNeeded(args: List<String>, enabled: Boolean): List<String> {
        if (!enabled || hasAnyCommandFlag(args, setOf("--tools"))) return args
        return args + buildNativeToolsArgs(enabled = true)
    }

    fun binarySupportsMtpSpeculative(binaryFile: File): Boolean {
        if (!binaryFile.isFile || !binaryFile.canRead()) return false
        return binaryContainsMarker(binaryFile, MTP_SPEC_TYPE_MARKER)
    }

    fun binarySupportsDflashSpeculative(binaryFile: File): Boolean {
        if (!binaryFile.isFile || !binaryFile.canRead()) return false
        return DFLASH_SPEC_MARKERS.any { marker -> binaryContainsMarker(binaryFile, marker) }
    }

    fun binarySupportsDsparkSpeculative(binaryFile: File): Boolean {
        if (!binaryFile.isFile || !binaryFile.canRead()) return false
        return DSPARK_SPEC_MARKERS.any { marker -> binaryContainsMarker(binaryFile, marker) }
    }

    fun binarySupportsDistributedFit(binaryFile: File): Boolean {
        if (!binaryFile.isFile || !binaryFile.canRead()) return false
        return DISTRIBUTED_FIT_MARKERS.any { marker -> binaryContainsMarker(binaryFile, marker) }
    }

    private fun binaryContainsMarker(binaryFile: File, marker: ByteArray): Boolean {
        if (marker.isEmpty()) return true
        val buffer = ByteArray(DEFAULT_BINARY_SCAN_BUFFER_SIZE + marker.size)
        var carry = 0
        FileInputStream(binaryFile).use { input ->
            while (true) {
                val read = input.read(buffer, carry, DEFAULT_BINARY_SCAN_BUFFER_SIZE)
                if (read <= 0) return false
                val length = carry + read
                if (indexOf(buffer, length, marker) >= 0) return true
                carry = min(marker.size - 1, length)
                if (carry > 0) {
                    System.arraycopy(buffer, length - carry, buffer, 0, carry)
                }
            }
        }
    }

    private fun indexOf(buffer: ByteArray, length: Int, marker: ByteArray): Int {
        val lastStart = length - marker.size
        for (start in 0..lastStart) {
            var matched = true
            for (offset in marker.indices) {
                if (buffer[start + offset] != marker[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return -1
    }

    private fun hasAnyCommandFlag(command: String, flags: Set<String>): Boolean =
        hasAnyCommandFlag(splitCommandLine(command), flags)

    private fun hasAnyCommandFlag(args: List<String>, flags: Set<String>): Boolean =
        args.any { token ->
            flags.any { flag -> token == flag || token.startsWith("$flag=") }
        }

    private fun shouldForceOpenClCpuTargetGpuDraft(
        binaryPath: String,
        config: LlamaConfig
    ): Boolean = config.openClCpuTargetGpuDraft &&
        config.rpcWorkers.isEmpty() &&
        File(binaryPath).name.contains("opencl", ignoreCase = true)

    /**
     * The OpenCL placement switch is an explicit override for model/drafter placement and draft
     * backend sampling. KV cache flags remain available to the existing general settings.
     */
    private fun filterOpenClCpuTargetGpuDraftConflicts(args: List<String>): List<String> {
        val valueFlags = setOf(
            "--device", "-dev",
            "--gpu-layers", "-ngl", "--n-gpu-layers",
            "--device-draft", "--spec-draft-device", "-devd",
            "--gpu-layers-draft", "--spec-draft-ngl", "-ngld"
        )
        val toggleFlags = setOf(
            "--spec-draft-backend-sampling", "--no-spec-draft-backend-sampling"
        )
        val blocked = valueFlags + toggleFlags
        val filtered = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            val flagName = argument.substringBefore('=')
            if (flagName in blocked) {
                val consumesFollowingValue = flagName in valueFlags && '=' !in argument
                index += if (consumesFollowingValue && index + 1 < args.size) 2 else 1
            } else {
                filtered += argument
                index += 1
            }
        }
        return filtered
    }

    private fun enforceOpenClCpuTargetGpuDraftArgs(
        args: List<String>,
        binaryPath: String,
        config: LlamaConfig
    ): List<String> {
        if (!shouldForceOpenClCpuTargetGpuDraft(binaryPath, config)) return args
        val result = filterOpenClCpuTargetGpuDraftConflicts(args).toMutableList()
        result += listOf(
            "--device", "none", "-ngl", "0",
            "--no-spec-draft-backend-sampling"
        )
        result += listOf("--spec-draft-device", "GPUOpenCL", "--spec-draft-ngl", "all")
        return result
    }

    private fun shellEscape(arg: String): String {
        if (arg.isEmpty()) return "''"
        val safeChars = "-_./:=,@+%".toSet()
        if (arg.all { it.isLetterOrDigit() || it in safeChars }) return arg
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    private companion object {
        private const val DEFAULT_BINARY_SCAN_BUFFER_SIZE = 8192
        private const val RECENT_OUTPUT_LIMIT = 24
        private val MTP_SPEC_TYPE_MARKER = "draft-mtp".toByteArray(Charsets.US_ASCII)
        private val DFLASH_SPEC_MARKERS = listOf(
            "draft-dflash",
            "common_speculative_impl_draft_dflash",
            "llama_model_dflash",
            "dflash"
        ).map { it.toByteArray(Charsets.US_ASCII) }
        private val DSPARK_SPEC_MARKERS = listOf(
            "draft-dspark",
            "common_speculative_impl_draft_dspark",
            "dspark"
        ).map { it.toByteArray(Charsets.US_ASCII) }
        private val DISTRIBUTED_FIT_MARKERS = listOf(
            "--fit",
            "--fit-target",
            "fit_target"
        ).map { it.toByteArray(Charsets.US_ASCII) }
    }

    suspend fun start(
        binaryPath: String, 
        config: LlamaConfig, 
        filesDir: File, 
        nativeToolsWorkspaceDir: File? = null,
        runtimeWorkingDir: File? = null,
        customArgs: List<String>? = null,
        runtimeGenerationId: Long = 0L,
        onLog: ((String) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        onState: ((ServerState) -> Unit)? = LlamaService.Companion::updateState,
        onClearServerLogs: (() -> Unit)? = LlamaService.Companion::clearServerLogs,
        onServerLog: ((String) -> Unit)? = LlamaService.Companion::addServerLog
    ): ProcessRunResult = withContext(Dispatchers.IO) {
        stoppedIntentionally = false
        if (process?.isAlive == true) stop()
        // Stop the previous generation before allocating the new one. This
        // keeps stopRequestedGeneration associated with the child that was
        // actually stopped when callers restart a server quickly.
        val thisGeneration = synchronized(this@ProcessController) {
            launchGeneration += 1L
            launchGeneration
        }
        
        val args = customArgs ?: getCommand(binaryPath, config)
        val ownershipWorkingDir = runtimeWorkingDir
            ?: nativeToolsWorkspaceDir?.takeIf { config.nativeToolsEnabled }
            ?: filesDir
        
        try {
            if (binaryPath.contains("opencl", ignoreCase = true)) {
                // Local and distributed runtimes may intentionally coexist. Only remove a
                // packaged server that belongs to the port this generation is about to own.
                NativeProcessCleanup.cleanupSameUidLlamaServersSync(
                    reason = "OpenCL pre-launch sweep",
                    port = config.port
                )
                NativeProcessCleanup.cleanupSameUidLlamaServersOwnedByDirectorySync(
                    reason = "OpenCL owner-directory sweep",
                    ownerDirectory = ownershipWorkingDir
                )
            }
            DebugLog.log("ProcessController: Starting binary: $binaryPath")
            DebugLog.log("ProcessController: Args: ${buildCommandString(args)}")
            
            // Imported custom builds may require their own dynamic library
            // companions. Built-in tier payloads are self-contained static
            // executables and must never borrow libllama/GGML files from a
            // different feature module.
            val libDir = File(filesDir, "lib")
            libDir.mkdirs()
            
            val nativeLibDir = File(binaryPath).parentFile
            val isBuiltInStaticPayload = NativeModuleCatalog.isBuiltInStaticPayload(File(binaryPath).name)
            if (!isBuiltInStaticPayload) {
                setupLibrarySymlinks(nativeLibDir, libDir, binaryPath)
            } else {
                DebugLog.log("ProcessController: Using self-contained native module payload; skipping shared-library staging.")
            }
            
            val workingDir = ownershipWorkingDir
            workingDir.mkdirs()

            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            
            // Set working directory to app's files dir (like Termux does)
            pb.directory(workingDir)
            
            // Set LD_LIBRARY_PATH to include both native lib dir and our symlink dir
            val ldPath = buildList {
                add(libDir.absolutePath)
                nativeLibDir?.absolutePath?.takeIf { it.isNotBlank() }?.let(::add)
                if (DeviceAcceleration.isAcceleratorBinary(File(binaryPath))) {
                    DeviceAcceleration.acceleratorLibrarySearchDirs()
                        .map { it.absolutePath }
                        .forEach(::add)
                }
            }.distinct().joinToString(":")
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            DebugLog.log("ProcessController: LD_LIBRARY_PATH=$ldPath")
            
            // Set environment variables like Termux does
            pb.environment()["HOME"] = workingDir.absolutePath
            pb.environment()["PWD"] = workingDir.absolutePath
            pb.environment()["TMPDIR"] = workingDir.absolutePath
            pb.environment()["PREFIX"] = filesDir.absolutePath
            if (DeviceAcceleration.isAcceleratorBinary(File(binaryPath))) {
                val ggmlBackendPath = resolveGgmlBackendPathForAccelerator(nativeLibDir, libDir)
                if (ggmlBackendPath != null) {
                    pb.environment()["GGML_BACKEND_PATH"] = ggmlBackendPath
                    DebugLog.log("ProcessController: GGML_BACKEND_PATH=$ggmlBackendPath")
                } else {
                    pb.environment().remove("GGML_BACKEND_PATH")
                    DebugLog.log("ProcessController: GGML_BACKEND_PATH unset; no standalone GGML accelerator backend library was found.")
                }
                pb.environment()["AIDOOM_OPENCL_DEBUG"] = "1"
                pb.environment()["ADSP_LIBRARY_PATH"] = buildList {
                    nativeLibDir?.absolutePath?.takeIf { it.isNotBlank() }?.let(::add)
                    add(filesDir.absolutePath)
                    DeviceAcceleration.acceleratorLibrarySearchDirs()
                        .map { it.absolutePath }
                        .forEach(::add)
                }.distinct().joinToString(";")
            } else {
                pb.environment().remove("GGML_BACKEND_PATH")
                pb.environment().remove("ADSP_LIBRARY_PATH")
            }
            DebugLog.log("ProcessController: Working dir=${workingDir.absolutePath}")
            
            process = pb.start()
            activeBinaryWasOpenCl = binaryPath.contains("opencl", ignoreCase = true)
            val reflectedChildPid = runCatching {
                process?.let { child ->
                    (java.lang.Process::class.java.getMethod("pid").invoke(child) as? Number)?.toLong()
                }
            }.getOrNull() ?: -1L
            val childPid = if (reflectedChildPid > 0L) reflectedChildPid else {
                resolveNativeChildPid(binaryPath)?.toLong() ?: -1L
            }
            activeChildPid = childPid.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val binaryTier = nativeBinaryTier(binaryPath)
            val binaryPathHash = MessageDigest.getInstance("SHA-256")
                .digest(binaryPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            DebugLog.log(
                "ProcessController: Native child started pid=$childPid generation=$runtimeGenerationId " +
                    "tier=$binaryTier binaryPathHash=$binaryPathHash"
            )
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "llama_process",
                event = "native_child_started",
                details = "pid=$childPid generation=$runtimeGenerationId tier=$binaryTier binaryPathHash=$binaryPathHash"
            )
            
            // Start log consumer
            onClearServerLogs?.invoke()
            val childProcess = process ?: error("Native process disappeared immediately after start")
            val reader = BufferedReader(InputStreamReader(childProcess.inputStream))
            var line: String?
            var modelLoaded = false
            val recentOutput = ArrayDeque<String>()
            while (reader.readLine().also { line = it } != null) {
                _logs.value = line ?: ""
                Log.d("LlamaServer", line ?: "")
                DebugLog.log("Server: ${line ?: ""}")
                line?.let {
                    recentOutput.addLast(it)
                    while (recentOutput.size > RECENT_OUTPUT_LIMIT) {
                        recentOutput.removeFirst()
                    }
                }
                
                // Invoke callback
                line?.let { 
                    onLog?.invoke(it) 
                    onServerLog?.invoke(it)
                }
                
                // Parse loading progress from server output
                val currentLine = line ?: ""
                
                // Detect model loading (llama.cpp outputs loading progress)
                if (currentLine.contains("loading model")) {
                    onState?.invoke(ServerState.Loading(-1f, "Loading model..."))
                }
                
                // Detect tensor loading progress (e.g., "llm_load_tensors: tensor")
                if (currentLine.contains("llm_load_tensors") && !modelLoaded) {
                    onState?.invoke(ServerState.Loading(-1f, "Loading tensors..."))
                }
                
                // Detect warming up
                if (currentLine.contains("warming up")) {
                    onState?.invoke(ServerState.Loading(-1f, "Warming up model..."))
                }
                
                // Detect server ready (listening)
                val serverReady = currentLine.contains("server is listening") ||
                    currentLine.contains("listening on http://") ||
                    currentLine.contains("server listening")
                if (serverReady) {
                    if (!modelLoaded) {
                        modelLoaded = true
                        onState?.invoke(ServerState.Running(config.port))
                        onReady?.invoke()
                        DebugLog.log("ProcessController: Server is ready and listening on port ${config.port}")
                    }
                }
            }
            
            // Process exited
            runCatching { childProcess.inputStream.close() }
            runCatching { childProcess.errorStream.close() }
            val exitCode = childProcess.waitFor()
            DebugLog.log("ProcessController: Process exited with code $exitCode")
            if (process === childProcess) process = null
            activeChildPid = -1
            val appContext = LlamaApplication.instance
            val startupFailureDetail = if (!modelLoaded && !stoppedIntentionally) {
                classifyNativeLinkerStartupFailure(recentOutput.toList())
            } else {
                null
            }
            val exitMessage = startupFailureDetail?.let {
                appContext.getString(R.string.llama_server_native_linker_failure, it.take(220))
            } ?: appContext.getString(R.string.llama_server_process_exited_unexpectedly, exitCode)
            val intentionallyStopped = stopRequestedGeneration == thisGeneration
            onState?.invoke(if (intentionallyStopped) ServerState.Stopped else ServerState.Error(exitMessage))
            return@withContext ProcessRunResult(
                exitCode = exitCode,
                becameReady = modelLoaded,
                stoppedIntentionally = intentionallyStopped,
                recentOutput = recentOutput.toList(),
                startupFailureMessage = if (intentionallyStopped) null else exitMessage,
                nativeLinkerStartupFailure = startupFailureDetail != null
            )
        } catch (e: Exception) {
            if (stopRequestedGeneration == thisGeneration || stoppedIntentionally) {
                DebugLog.log("ProcessController: stopped while reading process output: ${e.message}")
                runCatching { process?.inputStream?.close() }
                runCatching { process?.errorStream?.close() }
                process = null
                activeChildPid = -1
                onState?.invoke(ServerState.Stopped)
                return@withContext ProcessRunResult(
                    exitCode = -1,
                    becameReady = false,
                    stoppedIntentionally = true
                )
            }
            DebugLog.log("ProcessController: FAILED - ${e.message}")
            Log.e("ProcessController", "Failed to start", e)
            activeChildPid = -1
            activeBinaryWasOpenCl = false
            throw e
        }
    }

    internal fun resolveGgmlBackendPathForAccelerator(nativeLibDir: File?, libDir: File): String? {
        val candidateNames = listOf(
            "libggml-opencl.so",
            "libggml-opencl.so.0",
            "libggml-opencl.so.0.so",
            "libggml-vulkan.so",
            "libggml-vulkan.so.0",
            "libggml-vulkan.so.0.so"
        )
        val dirs = listOfNotNull(libDir, nativeLibDir).distinctBy { it.absolutePath }
        return candidateNames
            .asSequence()
            .flatMap { name -> dirs.asSequence().map { dir -> File(dir, name) } }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }
    
    /**
     * Create symlinks for versioned library names (.so.0 -> .so)
     */
    /**
     * Create symlinks for versioned library names (.so.0 -> .so)
     * Uses Java NIO Files.createSymbolicLink where possible, falls back to copy.
     */
    private fun setupLibrarySymlinks(sourceDir: File?, targetDir: File, binaryPath: String) {
        if (sourceDir == null) return
        
        // Infer tier from binary path (e.g. libllama_server_dotprod.so -> dotprod)
        val binaryName = File(binaryPath).name
        val tier = when {
            binaryName.contains("_i8mm") -> "_i8mm"
            binaryName.contains("_armv9") -> "_armv9"
            binaryName.contains("_dotprod") -> "_dotprod"
            binaryName.contains("_baseline") -> "_baseline"
            DeviceAcceleration.isAcceleratorBinary(File(binaryPath)) -> "_${CpuFeatures.getTier()}"
            else -> ""
        }
        
        DebugLog.log("ProcessController: Inferred tier '$tier' from $binaryName")
        
        // Map of Link Name -> Source Candidate Names
        val librariesToLink = listOf(
            // Tiered libraries
            "libmtmd.so" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            "libmtmd.so.0" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            
            // Standard shared libraries (usually renaming .so.0.so -> .so.0)
            "libllama.so" to listOf("libllama${tier}.so", "libllama.so", "libllama.so.0.so"),
            "libllama.so.0" to listOf("libllama${tier}.so", "libllama.so.0", "libllama.so", "libllama.so.0.so"),
            
            "libggml.so" to listOf("libggml${tier}.so", "libggml.so", "libggml.so.0.so"),
            "libggml.so.0" to listOf("libggml${tier}.so", "libggml.so.0", "libggml.so", "libggml.so.0.so"),
            
            "libggml-cpu.so" to listOf("libggml-cpu${tier}.so", "libggml-cpu.so", "libggml-cpu.so.0.so"),
            "libggml-cpu.so.0" to listOf("libggml-cpu${tier}.so", "libggml-cpu.so.0", "libggml-cpu.so", "libggml-cpu.so.0.so"),

            "libggml-opencl.so" to listOf("libggml-opencl.so", "libggml-opencl.so.0.so"),
            "libggml-opencl.so.0" to listOf("libggml-opencl.so.0", "libggml-opencl.so", "libggml-opencl.so.0.so"),
            
            "libggml-base.so" to listOf("libggml-base${tier}.so", "libggml-base.so", "libggml-base.so.0.so"),
            "libggml-base.so.0" to listOf("libggml-base${tier}.so", "libggml-base.so.0", "libggml-base.so", "libggml-base.so.0.so")
        )
        
        for ((linkName, sourceCandidates) in librariesToLink) {
            var sourceFile: File? = null
            
            // Find first existing source candidate
            for (candidateName in sourceCandidates) {
                val candidate = File(sourceDir, candidateName)
                if (candidate.exists()) {
                    sourceFile = candidate
                    break
                }
            }
            
            val linkFile = File(targetDir, linkName)
            
            if (sourceFile != null) {
                try {
                    // Delete existing files and dangling symlinks before recreating the link/copy.
                    linkFile.delete()
                    
                    // Try Java NIO symlink first
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            java.nio.file.Files.createSymbolicLink(
                                linkFile.toPath(),
                                sourceFile.toPath()
                            )
                            DebugLog.log("ProcessController: Created symlink ${linkFile.name} -> ${sourceFile.name}")
                        } else {
                           throw UnsupportedOperationException("Symlinks require Android O+")
                        }
                    } catch (e: Exception) {
                        // symlink failed (likely permission denied or OS too old), fallback to copy
                        // DebugLog.log("ProcessController: Symlink failed (${e.message}), falling back to copy")
                        sourceFile.copyTo(linkFile, overwrite = true)
                        DebugLog.log("ProcessController: Copied ${sourceFile.name} to ${linkName}")
                    }
                } catch (e: Exception) {
                    DebugLog.log("ProcessController: Optional library link unavailable for $linkName: ${e.message}")
                }
            } else {
                 DebugLog.log("ProcessController: Source library not found for $linkName (tried: $sourceCandidates)")
            }
        }
    }
    
    @Synchronized
    fun stop() {
        val current = process
        val pid = activeChildPid
        if (current == null && pid <= 0) {
            stoppedIntentionally = true
            return
        }
        stoppedIntentionally = true
        stopRequestedGeneration = launchGeneration
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        if (pid > 0) {
            // Capture and terminate the complete tree before accelerator helpers
            // are reparented and their ownership becomes invisible.
            NativeProcessCleanup.cleanupProcessTreeSync(
                reason = "ProcessController stop",
                rootPid = pid,
                includeRoot = true
            )
        }
        com.example.llamadroid.util.ProcessUtils.stopProcessSync(current)
        process = null
        activeChildPid = -1
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun activeProcessWasOpenCl(): Boolean = activeBinaryWasOpenCl

    fun clearActiveBinaryMarker() {
        activeBinaryWasOpenCl = false
    }

    internal fun resolveNativeChildPid(
        binaryPath: String,
        procRoot: File = File("/proc"),
        selfPid: Int = android.os.Process.myPid()
    ): Int? {
        val childIds = File(procRoot, "$selfPid/task").listFiles().orEmpty()
            .asSequence()
            .map { File(it, "children") }
            .filter(File::isFile)
            .flatMap { file ->
                runCatching { file.readText() }.getOrDefault("")
                    .trim().split(Regex("\\s+"))
                    .asSequence()
            }
            .mapNotNull(String::toIntOrNull)
            .distinct()
            .toList()
        val expectedName = File(binaryPath).name
        return childIds.filter { pid ->
            val cmdline = runCatching {
                File(procRoot, "$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            cmdline.contains(binaryPath) || cmdline.contains(expectedName)
        }.maxOrNull()
    }

    private fun nativeBinaryTier(binaryPath: String): String {
        val name = File(binaryPath).name.lowercase()
        return when {
            "opencl" in name -> "opencl"
            "vulkan" in name -> "vulkan"
            "i8mm" in name -> "i8mm"
            "armv9" in name -> "armv9"
            "dotprod" in name -> "dotprod"
            "baseline" in name -> "baseline"
            else -> "custom"
        }
    }
}

/**
 * Removes custom arguments that are already owned by typed settings.
 *
 * Keeping this filtering in command construction makes saved commands deterministic and prevents
 * a custom flag later in the command line from silently overriding the value shown in the UI.
 */
internal fun filterManagedLlamaCustomFlags(
    args: List<String>,
    config: LlamaConfig
): List<String> {
    val valueFlags = buildSet {
        add("--sleep-idle-seconds")
        if (config.parallel != null) {
            add("--parallel")
            add("-np")
        }
        if (config.cacheRam != null) {
            add("--cache-ram")
            add("-cram")
        }
        if (config.contextCheckpoints != null) {
            add("--ctx-checkpoints")
            add("-ctxcp")
            add("--swa-checkpoints")
        }
        if (config.checkpointMinStep != null) {
            add("--checkpoint-min-step")
            add("-cms")
        }
    }
    val toggleFlags = setOf(
        "--cache-prompt",
        "--no-cache-prompt",
        "--cache-idle-slots",
        "--no-cache-idle-slots",
        "--kv-unified",
        "-kvu",
        "--no-kv-unified",
        "-no-kvu",
        "--swa-full",
        "--no-swa-full"
    )
    val managed = valueFlags + toggleFlags
    val filtered = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        val flagName = argument.substringBefore('=')
        if (flagName in managed) {
            val consumesFollowingValue = flagName in valueFlags && '=' !in argument
            index += if (consumesFollowingValue && index + 1 < args.size) 2 else 1
        } else {
            filtered += argument
            index += 1
        }
    }
    return filtered
}

/**
 * RPC/fitting arguments belong exclusively to the distributed launch scope. Strip them from
 * typed custom flags so a local profile cannot inherit distributed arguments and a distributed
 * profile cannot silently override its validated UI values.
 */
internal fun filterDistributedLlamaCustomFlags(
    args: List<String>,
    @Suppress("UNUSED_PARAMETER") config: LlamaConfig
): List<String> {
    val valueFlags = setOf("--rpc", "--fit", "--fit-target", "--tensor-split", "-ts")
    val filtered = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        val flagName = argument.substringBefore('=')
        if (flagName in valueFlags) {
            val consumesFollowingValue = '=' !in argument
            index += if (consumesFollowingValue && index + 1 < args.size) 2 else 1
        } else {
            filtered += argument
            index += 1
        }
    }
    return filtered
}
