package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import com.example.llamadroid.R
import com.example.llamadroid.util.AccelerationWorkload
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.NativeProcessCleanup
import com.example.llamadroid.util.WakeLockManager
import android.net.wifi.WifiManager
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.GGUFParser

class LlamaService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processController = ProcessController()
    private var notificationTaskId: Int? = null
    @Volatile private var currentServerPort: Int? = null
    override fun onBind(intent: Intent?): IBinder? = null

    // Helper for updating global state
    // private fun updateState(newState: ServerState) {
    //    Companion.updateState(newState)
    // }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var restartMode = START_NOT_STICKY
        try {
            DebugLog.log("LlamaService: onStartCommand action=${intent?.action}")
            when (intent?.action) {
                ACTION_START -> {
                    val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                    val isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false)
                    val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                    val settingsProfile = intent.getStringExtra(EXTRA_SETTINGS_PROFILE) ?: SETTINGS_PROFILE_GENERAL
                    
                    // Get optional settings overrides (used by distributed mode to avoid changing global settings)
                    val threadsOverride = if (intent.hasExtra(EXTRA_THREADS)) intent.getIntExtra(EXTRA_THREADS, -1) else null
                    val batchSizeOverride = if (intent.hasExtra(EXTRA_BATCH_SIZE)) intent.getIntExtra(EXTRA_BATCH_SIZE, 512) else null
                    val contextSizeOverride = if (intent.hasExtra(EXTRA_CONTEXT_SIZE)) intent.getIntExtra(EXTRA_CONTEXT_SIZE, -1) else null
                    val temperatureOverride = if (intent.hasExtra(EXTRA_TEMPERATURE)) intent.getFloatExtra(EXTRA_TEMPERATURE, -1f) else null
                    val hostOverride = intent.getStringExtra(EXTRA_HOST)
                    val portOverride = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, -1) else null
                    
                    // Speculative decoding extras
                    val draftModelPath = intent.getStringExtra(EXTRA_DRAFT_MODEL_PATH)
                    val draftMax = if (intent.hasExtra(EXTRA_DRAFT_MAX)) intent.getIntExtra(EXTRA_DRAFT_MAX, 16) else null
                    val draftMin = if (intent.hasExtra(EXTRA_DRAFT_MIN)) intent.getIntExtra(EXTRA_DRAFT_MIN, 0) else null
                    val draftPMin = if (intent.hasExtra(EXTRA_DRAFT_P_MIN)) intent.getFloatExtra(EXTRA_DRAFT_P_MIN, 0.75f) else null
                    
                    val parallelOverride = if (intent.hasExtra(EXTRA_PARALLEL)) intent.getIntExtra(EXTRA_PARALLEL, 1) else null
                    val cacheRamOverride = if (intent.hasExtra(EXTRA_CACHE_RAM)) intent.getIntExtra(EXTRA_CACHE_RAM, 0) else null
                    val customFlagsOverride = intent.getStringExtra(EXTRA_CUSTOM_FLAGS)
                    val flashAttentionOverride = if (intent.hasExtra(EXTRA_FLASH_ATTENTION)) intent.getBooleanExtra(EXTRA_FLASH_ATTENTION, false) else null
                    val kvCacheEnabledOverride = if (intent.hasExtra(EXTRA_KV_CACHE_ENABLED)) intent.getBooleanExtra(EXTRA_KV_CACHE_ENABLED, false) else null
                    val kvCacheTypeKOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_K)
                    val kvCacheTypeVOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_V)
                    val kvCacheReuseOverride = if (intent.hasExtra(EXTRA_KV_CACHE_REUSE)) intent.getIntExtra(EXTRA_KV_CACHE_REUSE, 0) else null
                    val commandTemplateOverride = intent.getStringExtra(EXTRA_COMMAND_TEMPLATE)
                    
                    DebugLog.log("LlamaService: MODEL_PATH=$modelPath")
                    if (mmprojPath != null) {
                        DebugLog.log("LlamaService: MMPROJ_PATH=$mmprojPath")
                    }
                    if (modelPath.isNullOrEmpty()) {
                        DebugLog.log("LlamaService: ERROR - No model path provided!")
                        Companion.updateState(ServerState.Error("No model selected"))
                        stopSelf()
                    } else {
                        restartMode = START_REDELIVER_INTENT
                        startServer(modelPath, isEmbedding, mmprojPath, 
                            threadsOverride, contextSizeOverride, temperatureOverride, hostOverride, portOverride,
                            draftModelPath = draftModelPath, draftMax = draftMax, draftMin = draftMin, draftPMin = draftPMin,
                            kvCacheEnabledOverride = kvCacheEnabledOverride,
                            kvCacheTypeKOverride = kvCacheTypeKOverride,
                            kvCacheTypeVOverride = kvCacheTypeVOverride,
                            kvCacheReuseOverride = kvCacheReuseOverride,
                            customCommandOverride = intent.getStringExtra(EXTRA_CUSTOM_COMMAND),
                            commandTemplateOverride = commandTemplateOverride,
                            batchSizeOverride = batchSizeOverride,
                            parallelOverride = parallelOverride, cacheRamOverride = cacheRamOverride, customFlagsOverride = customFlagsOverride, flashAttentionOverride = flashAttentionOverride,
                            settingsProfile = settingsProfile)
                    }
                }
                ACTION_STOP -> stopServer()
                ACTION_SWITCH_MODEL -> {
                    val newModelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                    if (newModelPath.isNullOrEmpty()) {
                        DebugLog.log("LlamaService: SWITCH_MODEL - No model path provided!")
                    } else {
                        restartMode = START_REDELIVER_INTENT
                        DebugLog.log("LlamaService: SWITCH_MODEL to $newModelPath")
                        // Stop current server process without stopping the foreground service
                        processController.stop()
                        DistributedService.setInferenceRunning(false)
                        
                        serviceScope.launch(Dispatchers.IO) {
                            // Wait briefly to ensure the port is freed
                            kotlinx.coroutines.delay(1000)
                            
                            val params = DistributedService.lastRunParams.value
                            startServer(
                                modelPath = newModelPath,
                                isEmbedding = params["isEmbedding"] as? Boolean ?: false,
                                mmprojPath = params["mmprojPath"] as? String,
                                threadsOverride = params["threads"] as? Int,
                                contextSizeOverride = params["contextSize"] as? Int,
                                temperatureOverride = params["temperature"] as? Float,
                                hostOverride = params["host"] as? String,
                                portOverride = params["port"] as? Int,
                                draftModelPath = params["draftModelPath"] as? String,
                                draftMax = params["draftMax"] as? Int,
                                draftMin = params["draftMin"] as? Int,
                                draftPMin = params["draftPMin"] as? Float,
                                kvCacheEnabledOverride = params["kvCacheEnabled"] as? Boolean,
                                kvCacheTypeKOverride = params["kvCacheTypeK"] as? String,
                                kvCacheTypeVOverride = params["kvCacheTypeV"] as? String,
                                kvCacheReuseOverride = params["kvCacheReuse"] as? Int,
                                batchSizeOverride = params["batchSize"] as? Int,
                                parallelOverride = params["parallel"] as? Int,
                                cacheRamOverride = params["cacheRam"] as? Int,
                                customFlagsOverride = params["customFlags"] as? String,
                                flashAttentionOverride = params["flashAttention"] as? Boolean,
                                commandTemplateOverride = params["commandTemplate"] as? String,
                                settingsProfile = params["settingsProfile"] as? String ?: SETTINGS_PROFILE_GENERAL
                            )
                        }
                    }
                }
                ACTION_PREVIEW_COMMAND -> {
                    restartMode = START_NOT_STICKY
                     val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                     val isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false)
                     val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                     val settingsProfile = intent.getStringExtra(EXTRA_SETTINGS_PROFILE) ?: SETTINGS_PROFILE_GENERAL
                     
                     // Get optional settings overrides
                     val threadsOverride = if (intent.hasExtra(EXTRA_THREADS)) intent.getIntExtra(EXTRA_THREADS, -1) else null
                     val batchSizeOverride = if (intent.hasExtra(EXTRA_BATCH_SIZE)) intent.getIntExtra(EXTRA_BATCH_SIZE, 512) else null
                     val contextSizeOverride = if (intent.hasExtra(EXTRA_CONTEXT_SIZE)) intent.getIntExtra(EXTRA_CONTEXT_SIZE, -1) else null
                     val temperatureOverride = if (intent.hasExtra(EXTRA_TEMPERATURE)) intent.getFloatExtra(EXTRA_TEMPERATURE, -1f) else null
                     val hostOverride = intent.getStringExtra(EXTRA_HOST)
                     val portOverride = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, -1) else null
                     
                     // Speculative decoding extras
                     val draftModelPath = intent.getStringExtra(EXTRA_DRAFT_MODEL_PATH)
                     val draftMax = if (intent.hasExtra(EXTRA_DRAFT_MAX)) intent.getIntExtra(EXTRA_DRAFT_MAX, 16) else null
                     val draftMin = if (intent.hasExtra(EXTRA_DRAFT_MIN)) intent.getIntExtra(EXTRA_DRAFT_MIN, 0) else null
                     val draftPMin = if (intent.hasExtra(EXTRA_DRAFT_P_MIN)) intent.getFloatExtra(EXTRA_DRAFT_P_MIN, 0.75f) else null
                     
                     val parallelOverride = if (intent.hasExtra(EXTRA_PARALLEL)) intent.getIntExtra(EXTRA_PARALLEL, 1) else null
                     val cacheRamOverride = if (intent.hasExtra(EXTRA_CACHE_RAM)) intent.getIntExtra(EXTRA_CACHE_RAM, 0) else null
                     val customFlagsOverride = intent.getStringExtra(EXTRA_CUSTOM_FLAGS)
                     val flashAttentionOverride = if (intent.hasExtra(EXTRA_FLASH_ATTENTION)) intent.getBooleanExtra(EXTRA_FLASH_ATTENTION, false) else null
                     val kvCacheEnabledOverride = if (intent.hasExtra(EXTRA_KV_CACHE_ENABLED)) intent.getBooleanExtra(EXTRA_KV_CACHE_ENABLED, false) else null
                     val kvCacheTypeKOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_K)
                     val kvCacheTypeVOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_V)
                     val kvCacheReuseOverride = if (intent.hasExtra(EXTRA_KV_CACHE_REUSE)) intent.getIntExtra(EXTRA_KV_CACHE_REUSE, 0) else null
                     val commandTemplateOverride = intent.getStringExtra(EXTRA_COMMAND_TEMPLATE)
                     
                     if (!modelPath.isNullOrEmpty()) {
                         startServer(modelPath, isEmbedding, mmprojPath, 
                             threadsOverride, contextSizeOverride, temperatureOverride, hostOverride, portOverride, 
                             previewMode = true,
                             draftModelPath = draftModelPath, draftMax = draftMax, draftMin = draftMin, draftPMin = draftPMin,
                             kvCacheEnabledOverride = kvCacheEnabledOverride,
                             kvCacheTypeKOverride = kvCacheTypeKOverride,
                             kvCacheTypeVOverride = kvCacheTypeVOverride,
                             kvCacheReuseOverride = kvCacheReuseOverride,
                             customCommandOverride = intent.getStringExtra(EXTRA_CUSTOM_COMMAND),
                             commandTemplateOverride = commandTemplateOverride,
                             batchSizeOverride = batchSizeOverride,
                             parallelOverride = parallelOverride, cacheRamOverride = cacheRamOverride, customFlagsOverride = customFlagsOverride, flashAttentionOverride = flashAttentionOverride,
                             settingsProfile = settingsProfile)
                     }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("LlamaService: CRASH in onStartCommand: ${e.message}")
            e.printStackTrace()
        }
        DebugLog.log(
            "LlamaService: onStartCommand returning ${if (restartMode == START_REDELIVER_INTENT) "START_REDELIVER_INTENT" else "START_NOT_STICKY"}"
        )
        return restartMode
    }
    
    private fun startServer(
        modelPath: String, 
        isEmbedding: Boolean, 
        mmprojPath: String? = null,
        threadsOverride: Int? = null,
        contextSizeOverride: Int? = null,
        temperatureOverride: Float? = null,
        hostOverride: String? = null,
        portOverride: Int? = null,
        previewMode: Boolean = false,
        draftModelPath: String? = null,
        draftMax: Int? = null,
        draftMin: Int? = null,
        draftPMin: Float? = null,
        kvCacheEnabledOverride: Boolean? = null,
        kvCacheTypeKOverride: String? = null,
        kvCacheTypeVOverride: String? = null,
        kvCacheReuseOverride: Int? = null,
        customCommandOverride: String? = null,
        commandTemplateOverride: String? = null,
        batchSizeOverride: Int? = null,
        parallelOverride: Int? = null,
        cacheRamOverride: Int? = null,
        customFlagsOverride: String? = null,
        flashAttentionOverride: Boolean? = null,
        settingsProfile: String = SETTINGS_PROFILE_GENERAL
    ) {
        if (!previewMode) {
            val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
                UnifiedNotificationManager.TaskType.LLAMA_SERVER,
                "LLM Server"
            )
            notificationTaskId = taskId
            startForeground(taskId, notification)
            
            // Acquire CPU + Wi-Fi locks to keep the local server responsive while the screen is off.
            WakeLockManager.acquire(applicationContext, "LlamaService")
            WakeLockManager.acquireWifiLock(applicationContext, "LlamaService")
            
            Companion.updateState(ServerState.Starting)
            DebugLog.log("LlamaService: Starting server for model: $modelPath")
        } else {
            DebugLog.log("LlamaService: Generating PREVIEW command for model: $modelPath")
        }

        val isMasterProfile = settingsProfile == SETTINGS_PROFILE_MASTER

        // Read settings from repository, but use overrides if provided.
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(applicationContext)
        val threads = threadsOverride ?: if (isMasterProfile) DistributedService.masterThreads.value else settingsRepo.threads.value
        val batchSize = batchSizeOverride ?: if (isMasterProfile) DistributedService.masterBatchSize.value else 512
        val contextSize = contextSizeOverride ?: if (isMasterProfile) DistributedService.masterContextSize.value else settingsRepo.contextSize.value
        val temperature = temperatureOverride ?: if (isMasterProfile) DistributedService.masterTemperature.value else settingsRepo.temperature.value
        val host = hostOverride ?: if (isMasterProfile) "127.0.0.1" else if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1"
        val enableVision = if (isMasterProfile) mmprojPath != null else settingsRepo.enableVision.value
        val selectedMmprojPath = if (isMasterProfile) null else settingsRepo.selectedMmprojPath.value
        
        // KV Cache settings for server
        val kvCacheEnabled = kvCacheEnabledOverride ?: if (isMasterProfile) DistributedService.masterKvCacheEnabled.value else settingsRepo.serverKvCacheEnabled.value
        val kvCacheTypeK = kvCacheTypeKOverride ?: if (isMasterProfile) DistributedService.masterKvCacheTypeK.value else settingsRepo.serverKvCacheTypeK.value
        val kvCacheTypeV = kvCacheTypeVOverride ?: if (isMasterProfile) DistributedService.masterKvCacheTypeV.value else settingsRepo.serverKvCacheTypeV.value
        val kvCacheReuse = kvCacheReuseOverride ?: if (isMasterProfile) DistributedService.masterKvCacheReuse.value else settingsRepo.serverKvCacheReuse.value
        val noMmap = if (isMasterProfile) false else settingsRepo.lowMemoryMode.value
        val parallel = parallelOverride ?: if (isMasterProfile) DistributedService.masterParallel.value else null
        val cacheRam = cacheRamOverride ?: if (isMasterProfile) DistributedService.masterCacheRam.value else null
        val customFlags = customFlagsOverride ?: if (isMasterProfile) DistributedService.masterCustomFlags.value else settingsRepo.customFlags.value
        val flashAttention = flashAttentionOverride ?: if (isMasterProfile) DistributedService.masterFlashAttention.value else settingsRepo.flashAttentionEnabled.value
        val mtpDecodingEnabled = !isMasterProfile && settingsRepo.mtpDecodingEnabled.value
        val mtpDraftMax = if (isMasterProfile) 3 else settingsRepo.mtpDraftMaxTokens.value
        val commandTemplate = commandTemplateOverride
            ?.takeIf { it.isNotBlank() }
            ?: if (isMasterProfile) {
                DistributedService.masterCommandTemplate.value.takeIf { it.isNotBlank() }
            } else {
                settingsRepo.customCommandTemplate.value.takeIf { it.isNotBlank() }
            }

        // Check for a fully overridden command (used by the master preview editor).
        val finalCustomCommand = customCommandOverride ?: if (isMasterProfile) DistributedService.customCommand.value else null
        
        // Use mmproj if vision is enabled AND we have a mmproj path (either from intent or settings)
        val effectiveMmprojPath = if (enableVision) {
            mmprojPath ?: selectedMmprojPath
        } else null
        
        DebugLog.log("LlamaService: Settings - threads=$threads, ctx=$contextSize, temp=$temperature, host=$host")
        DebugLog.log("LlamaService: Vision enabled=$enableVision, mmproj=$effectiveMmprojPath")
        
        // Save last run params for remote switch support
        if (!previewMode) {
            DistributedService.setLastRunParams(mapOf(
                "modelPath" to modelPath,
                "isEmbedding" to isEmbedding,
                "mmprojPath" to effectiveMmprojPath,
                "threads" to threads,
                "batchSize" to batchSize,
                "contextSize" to contextSize,
                "temperature" to temperature,
                "host" to host,
                "port" to (portOverride ?: 8080),
                "draftModelPath" to draftModelPath,
                "draftMax" to draftMax,
                "draftMin" to draftMin,
                "draftPMin" to draftPMin,
                "kvCacheEnabled" to kvCacheEnabled,
                "kvCacheTypeK" to kvCacheTypeK,
                "kvCacheTypeV" to kvCacheTypeV,
                "kvCacheReuse" to kvCacheReuse,
                "parallel" to parallel,
                "cacheRam" to cacheRam,
                "customFlags" to customFlags,
                "flashAttention" to flashAttention,
                "mtpDecodingEnabled" to mtpDecodingEnabled,
                "mtpDraftMax" to mtpDraftMax,
                "commandTemplate" to commandTemplate,
                "settingsProfile" to settingsProfile
            ))
        }
        if (kvCacheEnabled) {
            DebugLog.log("LlamaService: KV cache enabled - K=$kvCacheTypeK, V=$kvCacheTypeV, reuse=$kvCacheReuse")
        }
        
        // Get distributed inference workers from DistributedService (if master mode is active)
        val rpcWorkers = if (DistributedService.mode.value == DistributedMode.MASTER) {
            DistributedService.getConfiguredWorkerAddresses().also {
                if (it.isNotEmpty()) {
                    DebugLog.log("LlamaService: Distributed mode - workers: ${it.joinToString(",")}")
                }
            }
        } else {
            emptyList()
        }
        
        serviceScope.launch {
            try {
                // Get binary from BinaryRepository
                val binaryRepo = BinaryRepository(applicationContext)
                val binaryFile = binaryRepo.getExecutable()
                
                if (binaryFile == null || !binaryFile.exists()) {
                    throw Exception("Binary not found. Please ensure binaries are extracted.")
                }
                val binary = binaryFile.absolutePath

                if (!finalCustomCommand.isNullOrBlank()) {
                    val args = processController.splitCommandLine(finalCustomCommand)
                    val commandString = processController.buildCommandString(args)
                    DistributedService.setLastCommand(commandString)

                    if (previewMode) {
                        DebugLog.log("LlamaService: Preview Custom Command: $commandString")
                        return@launch
                    }

                    DebugLog.log("LlamaService: Using CUSTOM command override")
                    Companion.updateState(ServerState.Starting)
                    updateNotification("Starting custom command...")
                    currentServerPort = portOverride ?: 8080
                    processController.start(binary, LlamaConfig(modelPath = modelPath), filesDir, customArgs = args)
                    return@launch
                }
                
                // Calculate layer distribution for RPC workers
                var nGpuLayers = 0
                var tensorSplit: String? = null
                
                if (rpcWorkers.isNotEmpty()) {
                    // CRITICAL FIX: Use ENABLED workers for calculation, not just connected ones.
                    // This aligns with getConfiguredWorkerAddresses() and fixes the race condition.
                    val connectedWorkers = DistributedService.workers.value.filter { it.isEnabled }
                    
                    if (connectedWorkers.isNotEmpty()) {
                        // Get master RAM (from settings) and each worker's RAM
                        val masterRamMB = DistributedService.masterRamMB.value
                        
                        // Get model info
                        val modelFile = java.io.File(modelPath)
                        val modelSizeMB = (modelFile.length() / (1024 * 1024)).toInt()
                        val ggufMetadata = GGUFParser.parse(modelPath)
                        val totalLayers = ggufMetadata?.layerCount ?: 40
                        
                        // Check if any worker has assignedProportion set - if so, use proportions instead of RAM
                        val totalWorkerProportion = connectedWorkers.mapNotNull { it.assignedProportion }.sum()
                        
                        if (totalWorkerProportion > 0f) {
                            // Use proportion-based calculation
                            // Sum of all worker proportions = layers to RPC
                            // E.g., if worker has 80% assigned, give 80% of layers to RPC
                            val workerProportion = totalWorkerProportion.coerceIn(0f, 1f)
                            nGpuLayers = (totalLayers * workerProportion).toInt()
                                .coerceIn(0, totalLayers) // Allow full offload when proportion is 1.0
                            DebugLog.log("LlamaService: Using proportion-based split - workers get ${(workerProportion*100).toInt()}% = $nGpuLayers/$totalLayers layers")
                            
                            // Calculate tensor split based on PROPORTIONS
                            if (connectedWorkers.size > 1) {
                                val workerProportions = connectedWorkers.map { it.assignedProportion ?: 0f }
                                // Normalize proportions so they sum to 1.0 (relative to the total worker share)
                                val totalProp = workerProportions.sum()
                                val workerFractions = workerProportions.map { prop ->
                                    if (totalProp > 0) prop / totalProp else 1f / connectedWorkers.size
                                }
                                tensorSplit = workerFractions.joinToString(",") { 
                                    String.format(java.util.Locale.US, "%.2f", it) 
                                }
                            }
                        } else {
                            // RAM-based calculation with bytes-per-layer estimation
                            // Since rpc-server reports full device RAM (not user-configured limit),
                            // we must calculate -ngl precisely to fit within user-configured worker RAM
                            
                            val workerRams = connectedWorkers.map { it.availableRamMB }
                            val totalWorkerRamMB = workerRams.sum()
                            val totalRamMB = masterRamMB + totalWorkerRamMB
                            
                            if (masterRamMB == 0) {
                                // Master contributes 0MB = offload ALL layers to workers
                                nGpuLayers = totalLayers
                                DebugLog.log("LlamaService: Master RAM is 0 - full offload to workers ($totalLayers layers)")
                                
                                // Calculate tensor split for worker distribution
                                if (connectedWorkers.size > 1 && totalWorkerRamMB > 0) {
                                    val totalWorkerRam = workerRams.sum().toFloat()
                                    val workerFractions = workerRams.map { ram ->
                                        if (totalWorkerRam > 0) ram.toFloat() / totalWorkerRam else 1f / connectedWorkers.size
                                    }
                                    tensorSplit = workerFractions.joinToString(",") { 
                                        String.format(java.util.Locale.US, "%.2f", it) 
                                    }
                                }
                            } else if (totalRamMB > 0 && totalWorkerRamMB > 0) {
                                // Estimate bytes per layer from model file size
                                // Use 1.5x safety factor: the output layer (offloaded first by llama.cpp)
                                // is typically 2-3x larger than repeating layers, plus KV cache overhead
                                val avgMBPerLayer = modelSizeMB.toFloat() / totalLayers.toFloat()
                                val safeMBPerLayer = avgMBPerLayer * 1.5f
                                
                                // Calculate max layers that fit in total worker RAM
                                val maxLayersForWorkers = (totalWorkerRamMB.toFloat() / safeMBPerLayer).toInt()
                                
                                // Also calculate proportion-based layers
                                val workerProportion = totalWorkerRamMB.toFloat() / totalRamMB.toFloat()
                                val proportionLayers = (totalLayers * workerProportion).toInt()
                                
                                // Use the MINIMUM of proportion-based and capacity-based
                                nGpuLayers = minOf(proportionLayers, maxLayersForWorkers)
                                    .coerceIn(0, totalLayers)
                                
                                DebugLog.log("LlamaService: RAM-based split - workers: ${totalWorkerRamMB}MB, master: ${masterRamMB}MB")
                                DebugLog.log("LlamaService: Model ~${avgMBPerLayer.toInt()}MB/layer (safe: ${safeMBPerLayer.toInt()}MB/layer)")
                                DebugLog.log("LlamaService: Max layers for workers: $maxLayersForWorkers (by capacity), $proportionLayers (by proportion)")
                                DebugLog.log("LlamaService: Workers get $nGpuLayers/$totalLayers layers")
                                
                                // Calculate tensor split based on configured RAM
                                if (connectedWorkers.size > 1) {
                                    val totalWorkerRam = workerRams.sum().toFloat()
                                    val workerFractions = workerRams.map { ram ->
                                        if (totalWorkerRam > 0) ram.toFloat() / totalWorkerRam else 1f / connectedWorkers.size
                                    }
                                    tensorSplit = workerFractions.joinToString(",") { 
                                        String.format(java.util.Locale.US, "%.2f", it) 
                                    }
                                }
                            } else {
                                nGpuLayers = 0
                            }
                        }
                        
                        val masterLayers = totalLayers - nGpuLayers
                        
                        DebugLog.log("LlamaService: RAM - master: ${masterRamMB}MB, workers: ${connectedWorkers.map { "${it.deviceName}:${it.availableRamMB}MB (${it.assignedProportion?.let { p -> "${(p*100).toInt()}%" } ?: "auto"})" }}")
                        DebugLog.log("LlamaService: Layers - master: $masterLayers, RPC: $nGpuLayers/$totalLayers (model: ${modelSizeMB}MB)")
                        if (tensorSplit != null) {
                            DebugLog.log("LlamaService: Tensor split among workers: $tensorSplit")
                        }
                        
                        // Update DistributedService for visualization
                        DistributedService.setModelInfo(
                            layers = totalLayers,
                            sizeMB = modelSizeMB.toLong(),
                            rpcLayers = nGpuLayers
                        )
                        DistributedService.setInferenceRunning(true)
                    }
                }
                
                val config = LlamaConfig(
                    modelPath = modelPath, 
                    isEmbedding = isEmbedding,
                    threads = threads,
                    batchSize = batchSize,
                    contextSize = contextSize,
                    temperature = temperature,
                    port = portOverride ?: 8080,
                    host = host,
                    mmprojPath = effectiveMmprojPath,
                    kvCacheEnabled = kvCacheEnabled,
                    kvCacheTypeK = kvCacheTypeK,
                    kvCacheTypeV = kvCacheTypeV,
                    kvCacheReuse = kvCacheReuse,
                    rpcWorkers = rpcWorkers,
                    nGpuLayers = nGpuLayers,
                    tensorSplit = tensorSplit,
                    noMmap = noMmap,
                    draftModelPath = draftModelPath,
                    draftMax = draftMax ?: 16,
                    draftMin = draftMin ?: 0,
                    draftPMin = draftPMin ?: 0.75f,
                    parallel = parallel,
                    cacheRam = cacheRam,
                    customFlags = customFlags,
                    flashAttention = flashAttention,
                    mtpDecodingEnabled = mtpDecodingEnabled,
                    mtpDraftMax = mtpDraftMax
                )
                currentServerPort = config.port
                
                
                DebugLog.log("LlamaService: Binary found at $binary")

                fun buildCommandArgsFor(candidateBinary: String): List<String> {
                    return if (commandTemplate.isNullOrBlank()) {
                        processController.getCommand(candidateBinary, config)
                    } else {
                        DebugLog.log("LlamaService: Rendering command template for ${if (isMasterProfile) "master" else "general"} profile")
                        processController.renderCommandTemplate(commandTemplate, candidateBinary, config)
                    }
                }

                val commandArgs = buildCommandArgsFor(binary)
                val commandString = processController.buildCommandString(commandArgs)
                DistributedService.setLastCommand(commandString)

                if (previewMode) {
                    DebugLog.log("LlamaService: Preview Command: $commandString")
                    return@launch
                }
                
                DebugLog.log("LlamaService: Starting on port ${config.port}")
                
                // Show loading state while model loads
                Companion.updateState(ServerState.Loading(0f, "Loading model..."))
                updateNotification("Loading model...")
                updateNotification("Llama Server Running on port ${config.port}")

                // Regex for parsing real RAM usage from logs
                // [22:58:39] Server: load_tensors: RPC0[10.2.0.2:50052] model buffer size = 8439.82 MiB
                val ramUsageRegex = "load_tensors: RPC\\d+\\[([\\d.]+):\\d+\\] model buffer size = ([\\d.]+) MiB".toRegex()

                fun handleServerLog(line: String) {
                    val match = ramUsageRegex.find(line)
                    if (match != null) {
                        val (ip, sizeMiB) = match.destructured
                        try {
                            val sizeFloat = sizeMiB.toFloat()
                            DebugLog.log("LlamaService: Parsed real RAM usage for $ip: ${sizeFloat}MB")
                            DistributedService.updateWorkerRealRam(ip, sizeFloat)
                        } catch (e: Exception) {
                            DebugLog.log("LlamaService: Error parsing RAM float: $sizeMiB")
                        }
                    }
                }

                fun isAcceleratorBackendUnavailableLog(line: String): Boolean {
                    val lower = line.lowercase()
                    return lower.contains("ggml_opencl: platform ids not available") ||
                        lower.contains("no usable gpu found") ||
                        lower.contains("--gpu-layers option will be ignored")
                }

                fun acceleratorBackendFailureReason(line: String): String =
                    "GPU backend unavailable: ${line.take(180)}"

                suspend fun startCandidate(candidateFile: File, args: List<String>): ProcessRunResult {
                    var backendUnavailable = false
                    var stoppedForAcceleratorBackendIssue = false
                    val result = processController.start(
                        candidateFile.absolutePath,
                        config,
                        filesDir,
                        customArgs = args,
                        onLog = { line ->
                            handleServerLog(line)
                            if (DeviceAcceleration.isAcceleratorBinary(candidateFile) &&
                                !stoppedForAcceleratorBackendIssue
                            ) {
                                val backendFailureReason = when {
                                    isAcceleratorBackendUnavailableLog(line) -> {
                                        backendUnavailable = true
                                        acceleratorBackendFailureReason(line)
                                    }
                                    else -> null
                                }
                                if (backendFailureReason != null) {
                                    stoppedForAcceleratorBackendIssue = true
                                    DeviceAcceleration.reportRuntimeFailure(AccelerationWorkload.LLM, backendFailureReason)
                                    DebugLog.log("LlamaService: ${candidateFile.name} is not a usable fast accelerator; stopping it for fallback: $backendFailureReason")
                                    processController.stop()
                                }
                            }
                        },
                        onReady = {
                            DeviceAcceleration.reportActiveBinary(
                                AccelerationWorkload.LLM,
                                if (backendUnavailable) null else candidateFile
                            )
                        }
                    )
                    return result.copy(
                        stoppedIntentionally = result.stoppedIntentionally && !stoppedForAcceleratorBackendIssue,
                        acceleratorBackendUnavailable = backendUnavailable,
                        acceleratorBackendDegraded = false
                    )
                }

                suspend fun startCpuFallback(reason: String): ProcessRunResult? {
                    val cpuBinaryFile = binaryRepo.getCpuExecutable()
                        ?.takeIf { it.absolutePath != binaryFile.absolutePath }
                        ?: return null
                    val fallbackStatus = getString(R.string.llama_server_cpu_fallback_retry)
                    DebugLog.log("LlamaService: $reason; retrying CPU fallback at ${cpuBinaryFile.absolutePath}")
                    Companion.updateState(ServerState.Loading(0f, fallbackStatus))
                    updateNotification(fallbackStatus)
                    ensureServerPortAvailableOrThrow(config.host, config.port, "before CPU fallback")
                    val fallbackArgs = buildCommandArgsFor(cpuBinaryFile.absolutePath)
                    DistributedService.setLastCommand(processController.buildCommandString(fallbackArgs))
                    return startCandidate(cpuBinaryFile, fallbackArgs)
                }

                val candidateFiles = if (DeviceAcceleration.isAcceleratorBinary(binaryFile)) {
                    (listOf(binaryFile) + binaryRepo.getAcceleratorBinaries("llama_server"))
                        .distinctBy { it.absolutePath }
                } else {
                    listOf(binaryFile)
                }

                var attemptedAccelerator = false
                var lastCandidateError: Throwable? = null
                var runResult: ProcessRunResult? = null

                for (candidateFile in candidateFiles) {
                    val isAccelerator = DeviceAcceleration.isAcceleratorBinary(candidateFile)
                    attemptedAccelerator = attemptedAccelerator || isAccelerator
                    val candidateArgs = buildCommandArgsFor(candidateFile.absolutePath)
                    DistributedService.setLastCommand(processController.buildCommandString(candidateArgs))
                    if (candidateFile.absolutePath != binaryFile.absolutePath) {
                        DebugLog.log("LlamaService: Trying alternate accelerator candidate: ${candidateFile.absolutePath}")
                    }
                    ensureServerPortAvailableOrThrow(config.host, config.port, "before starting ${candidateFile.name}")

                    val candidateResult = try {
                        startCandidate(candidateFile, candidateArgs)
                    } catch (e: Exception) {
                        if (processController.stoppedIntentionally) {
                            runResult = ProcessRunResult(
                                exitCode = -1,
                                becameReady = false,
                                stoppedIntentionally = true
                            )
                            break
                        }
                        lastCandidateError = e
                        if (isAccelerator) {
                            DebugLog.log("LlamaService: Accelerator candidate ${candidateFile.name} failed: ${e.message}")
                            continue
                        } else {
                            throw e
                        }
                    }

                    runResult = candidateResult
                    when {
                        candidateResult.acceleratorBackendUnavailable && isAccelerator -> {
                            DebugLog.log("LlamaService: Accelerator candidate ${candidateFile.name} has no usable backend; trying next fallback")
                            runResult = null
                        }
                        candidateResult.acceleratorBackendDegraded && isAccelerator -> {
                            DebugLog.log("LlamaService: Accelerator candidate ${candidateFile.name} is degraded for this model; trying next fallback")
                            runResult = null
                        }
                        candidateResult.stoppedIntentionally -> break
                        !candidateResult.becameReady && isAccelerator -> {
                            DebugLog.log("LlamaService: Accelerator candidate ${candidateFile.name} exited before readiness; trying next fallback")
                            DeviceAcceleration.reportRuntimeFailure(
                                AccelerationWorkload.LLM,
                                "Accelerator ${candidateFile.name} exited before the server became ready"
                            )
                            runResult = null
                        }
                        else -> break
                    }
                }

                val currentRunResult = runResult
                if ((currentRunResult == null ||
                        (!currentRunResult.stoppedIntentionally && !currentRunResult.becameReady)) &&
                    attemptedAccelerator
                ) {
                    val reason = lastCandidateError?.message?.let { "Snapdragon accelerator failed: $it" }
                        ?: "Snapdragon accelerator exited before the server became ready"
                    startCpuFallback(reason)?.let {
                        runResult = it
                    }
                }
                val finalRunResult = runResult ?: lastCandidateError?.let { throw it }
                    ?: error("Llama server did not return a process result.")
                
                // If process exits
                DebugLog.log("LlamaService: Process exited")
                if (!finalRunResult.stoppedIntentionally) {
                    // Process exited unexpectedly
                    DebugLog.log("LlamaService: Process terminated unexpectedly")
                }
                stopServer()
            } catch (e: Exception) {
                // Only show error if not intentionally stopped
                if (!processController.stoppedIntentionally) {
                    DebugLog.log("LlamaService ERROR: ${e.message}")
                    Companion.updateState(ServerState.Error(e.message ?: "Unknown error"))
                } else {
                    DebugLog.log("LlamaService: Stopped by user")
                    Companion.updateState(ServerState.Stopped)
                }
                stopSelf()
            }
        }
    }
    
    private fun stopServer() {
        val portToClean = currentServerPort ?: 8080
        processController.stop()
        NativeProcessCleanup.cleanupSameUidLlamaServersSync("LlamaService stop", port = portToClean)
        serviceScope.coroutineContext.cancelChildren()
        DistributedService.setInferenceRunning(false)
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.LLM, null)
        Companion.updateState(ServerState.Stopped)
        WakeLockManager.release("LlamaService")
        WakeLockManager.releaseWifiLock("LlamaService")
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        currentServerPort = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val portToClean = currentServerPort ?: 8080
        processController.stop()
        NativeProcessCleanup.cleanupSameUidLlamaServersSync("LlamaService destroy", graceMs = 500L, forceMs = 500L, port = portToClean)
        serviceScope.coroutineContext.cancelChildren()
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        currentServerPort = null
        WakeLockManager.release("LlamaService")
        WakeLockManager.releaseWifiLock("LlamaService")
        super.onDestroy()
    }

    private suspend fun waitForServerPortAvailable(
        host: String,
        port: Int,
        reason: String,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var loggedWait = false
        while (System.currentTimeMillis() < deadline) {
            if (canBindServerPort(host, port)) {
                if (loggedWait) DebugLog.log("LlamaService: Port $port is free after $reason")
                return true
            }
            if (!loggedWait) {
                loggedWait = true
                DebugLog.log("LlamaService: Waiting for port $port to be released $reason")
            }
            delay(150L)
        }
        DebugLog.log("LlamaService: Port $port is still busy after waiting $reason")
        return false
    }

    private suspend fun ensureServerPortAvailableOrThrow(host: String, port: Int, reason: String) {
        if (canBindServerPort(host, port)) return
        DebugLog.log("LlamaService: Port $port busy $reason; checking for stale app-owned llama-server processes")
        val cleaned = NativeProcessCleanup.cleanupSameUidLlamaServers(reason, port = port)
        if (cleaned > 0) {
            DebugLog.log("LlamaService: Requested cleanup for $cleaned stale llama-server process(es)")
        }
        if (!waitForServerPortAvailable(host, port, reason, timeoutMs = 8_000L)) {
            val message = getString(R.string.llama_server_port_busy, port)
            DebugLog.log("LlamaService: $message")
            throw IllegalStateException(message)
        }
    }

    private fun canBindServerPort(host: String, port: Int): Boolean =
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
            }
            true
        }.getOrDefault(false)
    
    private fun updateNotification(content: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, content)
        }
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_SWITCH_MODEL = "SWITCH_MODEL"
        const val ACTION_PREVIEW_COMMAND = "PREVIEW_COMMAND"
        const val EXTRA_MODEL_PATH = "MODEL_PATH"
        const val EXTRA_IS_EMBEDDING = "IS_EMBEDDING"
        const val EXTRA_MMPROJ_PATH = "MMPROJ_PATH"
        // Optional settings overrides for distributed mode (to avoid modifying global settings)
        const val EXTRA_THREADS = "THREADS"
        const val EXTRA_BATCH_SIZE = "BATCH_SIZE"
        const val EXTRA_CONTEXT_SIZE = "CONTEXT_SIZE"
        const val EXTRA_TEMPERATURE = "TEMPERATURE"
        const val EXTRA_HOST = "HOST"
        const val EXTRA_PORT = "PORT"
        const val EXTRA_SETTINGS_PROFILE = "SETTINGS_PROFILE"
        // Speculative decoding extras
        const val EXTRA_DRAFT_MODEL_PATH = "DRAFT_MODEL_PATH"
        const val EXTRA_DRAFT_MAX = "DRAFT_MAX"
        const val EXTRA_DRAFT_MIN = "DRAFT_MIN"
        const val EXTRA_DRAFT_P_MIN = "DRAFT_P_MIN"
        const val EXTRA_CUSTOM_COMMAND = "CUSTOM_COMMAND"
        const val EXTRA_COMMAND_TEMPLATE = "COMMAND_TEMPLATE"
        
        // Advanced settings
        const val EXTRA_PARALLEL = "PARALLEL"
        const val EXTRA_CACHE_RAM = "CACHE_RAM"
        const val EXTRA_CUSTOM_FLAGS = "CUSTOM_FLAGS"
        const val EXTRA_FLASH_ATTENTION = "FLASH_ATTENTION"
        const val EXTRA_KV_CACHE_ENABLED = "KV_CACHE_ENABLED"
        const val EXTRA_KV_CACHE_TYPE_K = "KV_CACHE_TYPE_K"
        const val EXTRA_KV_CACHE_TYPE_V = "KV_CACHE_TYPE_V"
        const val EXTRA_KV_CACHE_REUSE = "KV_CACHE_REUSE"

        const val SETTINGS_PROFILE_GENERAL = "GENERAL"
        const val SETTINGS_PROFILE_MASTER = "MASTER"
        
        // Global state for simple observation
        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
        val state = _state.asStateFlow()
        
        fun updateState(newState: ServerState) {
            _state.value = newState
        }

        private const val MAX_SERVER_LOGS = 1000
        private val _serverLogs = MutableStateFlow<List<com.example.llamadroid.util.LogEntry>>(emptyList())
        val serverLogs = _serverLogs.asStateFlow()

        fun addServerLog(message: String) {
            val entry = com.example.llamadroid.util.LogEntry(System.currentTimeMillis(), message)
            val current = _serverLogs.value
            _serverLogs.value = (current + entry).takeLast(MAX_SERVER_LOGS)
        }

        fun clearServerLogs() {
            _serverLogs.value = emptyList()
        }
    }
}
