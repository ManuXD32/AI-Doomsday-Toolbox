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
import java.io.InputStreamReader
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration

data class ProcessRunResult(
    val exitCode: Int,
    val becameReady: Boolean,
    val stoppedIntentionally: Boolean,
    val acceleratorBackendUnavailable: Boolean = false,
    val acceleratorBackendDegraded: Boolean = false
)

class ProcessController {
    
    private var process: Process? = null
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
    

    fun getCommand(binaryPath: String, config: LlamaConfig): List<String> {
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
        }
        
        if (config.isEmbedding) {
            args.add("--embedding")
        } else {
             // Chat specific params
             args.add("--temp")
             args.add(config.temperature.toString())
        }
        
        // Add KV cache quantization flags if enabled
        if (config.kvCacheEnabled) {
            args.add("--cache-type-k")
            args.add(config.kvCacheTypeK)
            args.add("--cache-type-v")
            args.add(config.kvCacheTypeV)
            if (config.kvCacheReuse > 0) {
                args.add("--cache-reuse")
                args.add(config.kvCacheReuse.toString())
            }
        }
        
        // Add RPC workers for distributed inference
        if (config.rpcWorkers.isNotEmpty()) {
            val rpcArg = config.rpcWorkers.joinToString(",")
            args.add("--rpc")
            args.add(rpcArg)
            // Disable automatic memory fitting for distributed inference - it can cause SIGSEGV
            args.add("--fit")
            args.add("off")
            
            // Use -ngl to specify how many layers to offload to RPC workers
            // MUST always be sent in RPC mode - without it, llama-server defaults to 'auto'
            // which offloads ALL layers, potentially crashing low-RAM workers
            args.add("-ngl")
            args.add(config.nGpuLayers.toString())
            
            // Use -ts to split the offloaded layers among multiple workers
            // Only needed when there are 2+ workers
            if (!config.tensorSplit.isNullOrEmpty() && config.rpcWorkers.size > 1) {
                args.add("-ts")
                args.add(config.tensorSplit)
            }
        }

        val customFlagsText = config.customFlags.orEmpty()
        if (DeviceAcceleration.isAcceleratorBinary(File(binaryPath)) &&
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
        
        args.addAll(buildSpeculativeArgs(config))

        // Advanced Settings
        if (config.parallel != null) {
            args.add("--parallel")
            args.add(config.parallel.toString())
        } else if (config.mtpDecodingEnabled && !hasAnyCommandFlag(customFlagsText, setOf("--parallel", "-np"))) {
            args.add("--parallel")
            args.add("1")
        }
        if (config.cacheRam != null) {
            args.add("--cache-ram")
            args.add(config.cacheRam.toString())
        }
        
        args.add("--flash-attn")
        args.add(if (config.flashAttention) "on" else "off")

        if (!config.customFlags.isNullOrBlank()) {
            // Split custom flags by space, ignoring excessive spaces.
            val flags = config.customFlags.trim().split("\\s+".toRegex())
            args.addAll(flags)
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

        if (current.isNotEmpty()) {
            tokens += current.toString()
        }

        return tokens
    }

    fun buildCommandString(args: List<String>): String =
        args.joinToString(" ") { shellEscape(it) }

    fun renderCommandTemplate(
        template: String,
        binaryPath: String,
        config: LlamaConfig
    ): List<String> {
        if (template.isBlank()) return getCommand(binaryPath, config)

        val defaultArgs = getCommand(binaryPath, config)
        val substituted = substituteTemplateValues(template, binaryPath, config, defaultArgs)
        val renderedArgs = splitCommandLine(substituted).filter { it.isNotBlank() }
        if (renderedArgs.isEmpty()) return defaultArgs

        val hasExplicitBinary = template.contains("{binary}") ||
            renderedArgs.firstOrNull() == binaryPath ||
            renderedArgs.firstOrNull()?.startsWith("-") == false

        return if (hasExplicitBinary) renderedArgs else listOf(binaryPath) + renderedArgs
    }

    private fun substituteTemplateValues(
        template: String,
        binaryPath: String,
        config: LlamaConfig,
        defaultArgs: List<String>
    ): String {
        val customFlagsArgs = splitCommandLine(config.customFlags.orEmpty())
        val speculativeArgs = buildSpeculativeArgs(config)
        val mtpArgs = if (config.mtpDecodingEnabled) {
            buildMtpSpeculativeArgs(config)
        } else {
            emptyList()
        }
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
            "{tensor_split}" to (config.tensorSplit ?: ""),
            "{custom_flags}" to buildCommandString(customFlagsArgs),
            "{default_args}" to buildCommandString(defaultArgs.drop(1)),
            "{speculative_args}" to buildCommandString(speculativeArgs),
            "{mtp_args}" to buildCommandString(mtpArgs),
            "{kv_cache_args}" to buildCommandString(kvCacheArgs)
        )

        var rendered = template
        values.forEach { (placeholder, value) ->
            rendered = rendered.replace(placeholder, value)
        }
        return rendered.trim()
    }

    private fun buildSpeculativeArgs(config: LlamaConfig): List<String> {
        if (config.mtpDecodingEnabled) {
            return buildMtpSpeculativeArgs(config)
        }
        val draftModel = config.draftModelPath ?: return emptyList()
        return listOf(
            "--model-draft", draftModel,
            "--spec-draft-n-max", config.draftMax.toString(),
            "--spec-draft-n-min", config.draftMin.toString(),
            "--draft-p-min", String.format(java.util.Locale.US, "%.2f", config.draftPMin)
        )
    }

    private fun buildMtpSpeculativeArgs(config: LlamaConfig): List<String> =
        listOf(
            "--spec-type", "draft-mtp",
            "--spec-draft-n-max", config.mtpDraftMax.coerceAtLeast(1).toString()
        )

    private fun hasAnyCommandFlag(command: String, flags: Set<String>): Boolean =
        splitCommandLine(command).any { token ->
            flags.any { flag -> token == flag || token.startsWith("$flag=") }
        }

    private fun shellEscape(arg: String): String {
        if (arg.isEmpty()) return "''"
        val safeChars = "-_./:=,@+%".toSet()
        if (arg.all { it.isLetterOrDigit() || it in safeChars }) return arg
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    suspend fun start(
        binaryPath: String, 
        config: LlamaConfig, 
        filesDir: File, 
        customArgs: List<String>? = null,
        onLog: ((String) -> Unit)? = null,
        onReady: (() -> Unit)? = null,
        onState: ((ServerState) -> Unit)? = LlamaService.Companion::updateState,
        onClearServerLogs: (() -> Unit)? = LlamaService.Companion::clearServerLogs,
        onServerLog: ((String) -> Unit)? = LlamaService.Companion::addServerLog
    ): ProcessRunResult = withContext(Dispatchers.IO) {
        stoppedIntentionally = false
        if (process?.isAlive == true) stop()
        
        val args = customArgs ?: getCommand(binaryPath, config)
        
        try {
            DebugLog.log("ProcessController: Starting binary: $binaryPath")
            DebugLog.log("ProcessController: Args: ${buildCommandString(args)}")
            
            // Create a lib directory with symlinks for versioned libraries
            val libDir = File(filesDir, "lib")
            libDir.mkdirs()
            
            val nativeLibDir = File(binaryPath).parentFile
            setupLibrarySymlinks(nativeLibDir, libDir, binaryPath)
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            
            // Set working directory to app's files dir (like Termux does)
            pb.directory(filesDir)
            
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
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["PWD"] = filesDir.absolutePath
            pb.environment()["TMPDIR"] = filesDir.absolutePath
            pb.environment()["PREFIX"] = filesDir.absolutePath
            if (DeviceAcceleration.isAcceleratorBinary(File(binaryPath))) {
                pb.environment()["GGML_BACKEND_PATH"] = nativeLibDir?.absolutePath.orEmpty()
                pb.environment()["AIDOOM_OPENCL_DEBUG"] = "1"
                pb.environment()["ADSP_LIBRARY_PATH"] = buildList {
                    nativeLibDir?.absolutePath?.takeIf { it.isNotBlank() }?.let(::add)
                    add(filesDir.absolutePath)
                    DeviceAcceleration.acceleratorLibrarySearchDirs()
                        .map { it.absolutePath }
                        .forEach(::add)
                }.distinct().joinToString(";")
            } else {
                pb.environment()["GGML_BACKEND_PATH"] = ""
                pb.environment().remove("ADSP_LIBRARY_PATH")
            }
            DebugLog.log("ProcessController: Working dir=${filesDir.absolutePath}")
            
            process = pb.start()
            
            // Start log consumer
            onClearServerLogs?.invoke()
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            var modelLoaded = false
            while (reader.readLine().also { line = it } != null) {
                _logs.value = line ?: ""
                Log.d("LlamaServer", line ?: "")
                DebugLog.log("Server: ${line ?: ""}")
                
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
            val exitCode = process?.waitFor() ?: -1
            DebugLog.log("ProcessController: Process exited with code $exitCode")
            process = null
            val appContext = LlamaApplication.instance
            val exitMessage = appContext.getString(R.string.llama_server_process_exited_unexpectedly, exitCode)
            onState?.invoke(resolveExitState(exitCode, exitMessage))
            return@withContext ProcessRunResult(
                exitCode = exitCode,
                becameReady = modelLoaded,
                stoppedIntentionally = stoppedIntentionally
            )
        } catch (e: Exception) {
            if (stoppedIntentionally) {
                DebugLog.log("ProcessController: stopped while reading process output: ${e.message}")
                process = null
                onState?.invoke(ServerState.Stopped)
                return@withContext ProcessRunResult(
                    exitCode = -1,
                    becameReady = false,
                    stoppedIntentionally = true
                )
            }
            DebugLog.log("ProcessController: FAILED - ${e.message}")
            Log.e("ProcessController", "Failed to start", e)
            throw e
        }
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
            binaryName.contains("_armv9") -> "_armv9"
            binaryName.contains("_dotprod") -> "_dotprod"
            binaryName.contains("_baseline") -> "_baseline"
            else -> ""
        }
        
        DebugLog.log("ProcessController: Inferred tier '$tier' from $binaryName")
        
        // Map of Link Name -> Source Candidate Names
        val librariesToLink = listOf(
            // Tiered libraries
            "libmtmd.so" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            "libmtmd.so.0" to listOf("libmtmd${tier}.so", "libmtmd.so"),
            
            // Standard shared libraries (usually renaming .so.0.so -> .so.0)
            "libllama.so" to listOf("libllama.so", "libllama.so.0.so"),
            "libllama.so.0" to listOf("libllama.so.0", "libllama.so", "libllama.so.0.so"),
            
            "libggml.so" to listOf("libggml.so", "libggml.so.0.so"),
            "libggml.so.0" to listOf("libggml.so.0", "libggml.so", "libggml.so.0.so"),
            
            "libggml-cpu.so" to listOf("libggml-cpu.so", "libggml-cpu.so.0.so"),
            "libggml-cpu.so.0" to listOf("libggml-cpu.so.0", "libggml-cpu.so", "libggml-cpu.so.0.so"),
            
            "libggml-base.so" to listOf("libggml-base.so", "libggml-base.so.0.so"),
            "libggml-base.so.0" to listOf("libggml-base.so.0", "libggml-base.so", "libggml-base.so.0.so")
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
    
    fun stop() {
        stoppedIntentionally = true
        com.example.llamadroid.util.ProcessUtils.stopProcessSync(process)
        process = null
    }
    
    fun isAlive(): Boolean = process?.isAlive == true
}
