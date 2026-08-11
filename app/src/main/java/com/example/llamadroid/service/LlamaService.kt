package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import com.example.llamadroid.R
import com.example.llamadroid.util.AccelerationWorkload
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.NativeProcessCleanup
import com.example.llamadroid.util.WakeLockManager
import android.net.wifi.WifiManager
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.binary.EffectiveLlamaBinary
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.util.GGUFParser

class LlamaService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processController = ProcessController()
    private val lifecycleLock = Any()
    @Volatile private var lifecycleGeneration = 0L
    private var lifecycleCommand: Job? = null
    private var notificationTaskId: Int? = null
    @Volatile private var currentServerPort: Int? = null
    override fun onCreate() {
        super.onCreate()
        Companion.attachRuntimeProcess(applicationContext)
        serviceScope.launch {
            while (isActive) {
                LlamaRuntimeStateProjection.publishHeartbeat(applicationContext, Companion.runtimeGenerationId())
                delay(10_000L)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The service receives commands from the dashboard, chat, OCR, remote control and Wear.
     * Keep exactly one launch/stop transition in flight so an old child can never tear down a
     * replacement server after it has been superseded.
     */
    private fun scheduleLaunch(
        reason: String,
        restartDelayMs: Long = 0L,
        launch: (Long) -> Job
    ) {
        var generation = 0L
        var previous: Job? = null
        val command = serviceScope.launch(start = CoroutineStart.LAZY) {
            // Closing the native child unblocks its line reader before waiting for the
            // superseded coroutine. Cancelling alone cannot interrupt BufferedReader.readLine().
            processController.stop()
            previous?.cancelAndJoin()
            if (!isCurrentGeneration(generation)) return@launch
            if (restartDelayMs > 0L) delay(restartDelayMs)
            if (!isCurrentGeneration(generation)) return@launch
            launch(generation).join()
        }
        synchronized(lifecycleLock) {
            generation = ++lifecycleGeneration
            previous = lifecycleCommand
            lifecycleCommand = command
        }
        DebugLog.log("LlamaService: queued $reason generation=$generation")
        previous?.cancel()
        command.start()
    }

    private fun scheduleStop(startId: Int) {
        var generation = 0L
        var previous: Job? = null
        val command = serviceScope.launch(start = CoroutineStart.LAZY) {
            processController.stop()
            previous?.cancelAndJoin()
            if (!isCurrentGeneration(generation)) return@launch
            stopServer(startId = startId, generation = generation)
        }
        synchronized(lifecycleLock) {
            generation = ++lifecycleGeneration
            previous = lifecycleCommand
            lifecycleCommand = command
        }
        DebugLog.log("LlamaService: queued stop generation=$generation")
        previous?.cancel()
        command.start()
    }

    private fun scheduleRecovery(startId: Int) {
        var generation = 0L
        var previous: Job? = null
        val command = serviceScope.launch(start = CoroutineStart.LAZY) {
            val recovery = Companion.recoverRecordedOwner(applicationContext)
            if (recovery.matchedRecordedOwner) {
                // The recorded root/tree was verified and terminated before we release local
                // handles, which unblocks its reader without broad same-UID cleanup.
                processController.releaseExternallyStoppedProcess()
                previous?.cancelAndJoin()
            }
            if (!isCurrentGeneration(generation)) return@launch
            processController.clearTransientLogs()
            clearServerLogsForGeneration(generation)
            Companion.clearRecentStartupFailure()
            DistributedService.setInferenceRunning(false)
            DeviceAcceleration.reportActiveBinary(AccelerationWorkload.LLM, null)
            val recoveredPortReleased = recovery.recordedPort?.let { recordedPort ->
                checkServerPortBind("127.0.0.1", recordedPort).available
            } ?: true
            if (recoveredPortReleased) {
                Companion.clearRecordedOwner(applicationContext)
                updateStateForGeneration(generation, ServerState.Stopped)
            } else {
                updateStateForGeneration(
                    generation,
                    ServerState.Error(
                        getString(R.string.llama_server_cleanup_port_busy, recovery.recordedPort)
                    )
                )
            }
            DebugLog.log(
                "LlamaService: recovery completed recordedPid=${recovery.recordedPid} " +
                    "cleaned=${recovery.cleanedProcessCount} matched=${recovery.matchedRecordedOwner}"
            )
            WakeLockManager.release("LlamaService")
            WakeLockManager.releaseWifiLock("LlamaService")
            notificationTaskId?.let { taskId -> UnifiedNotificationManager.dismissTask(taskId) }
            notificationTaskId = null
            currentServerPort = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        synchronized(lifecycleLock) {
            generation = ++lifecycleGeneration
            previous = lifecycleCommand
            lifecycleCommand = command
        }
        DebugLog.log("LlamaService: queued recorded-owner recovery generation=$generation")
        previous?.cancel()
        command.start()
    }

    private fun isCurrentGeneration(generation: Long?): Boolean =
        generation == null || generation == lifecycleGeneration

    private fun updateStateForGeneration(generation: Long?, state: ServerState) {
        if (isCurrentGeneration(generation)) Companion.updateState(state)
    }

    private fun appendServerLogForGeneration(generation: Long?, message: String) {
        if (isCurrentGeneration(generation)) Companion.addServerLog(message, lifecycleGeneration = generation)
    }

    private fun clearServerLogsForGeneration(generation: Long?) {
        if (isCurrentGeneration(generation)) Companion.clearServerLogs(lifecycleGeneration = generation)
    }

    private fun recordOwnedRuntimeForGeneration(
        generation: Long?,
        pid: Int,
        port: Int,
        processStartTimeTicks: Long?
    ) {
        if (!isCurrentGeneration(generation) || processStartTimeTicks == null) return
        Companion.recordOwnedRuntime(
            applicationContext,
            LlamaRuntimeOwnerRecord(
                pid = pid,
                port = port,
                lifecycleGeneration = generation ?: lifecycleGeneration,
                processStartTimeTicks = processStartTimeTicks
            )
        )
    }

    private fun completedJob(): Job = Job().apply { complete() }

    private fun Intent.rpcWorkersOverride(): List<String>? =
        takeIf { hasExtra(EXTRA_RPC_WORKERS) }
            ?.getStringArrayExtra(EXTRA_RPC_WORKERS)
            ?.filter { it.isNotBlank() }

    private fun Intent.rpcWorkerRamOverride(): IntArray? =
        takeIf { hasExtra(EXTRA_RPC_WORKER_RAM_MB) }
            ?.getIntArrayExtra(EXTRA_RPC_WORKER_RAM_MB)

    private fun ensureForegroundNotification() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SERVER,
            "LLM Server"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
    }

    private fun effectiveBinaryForPath(binaryPath: String): EffectiveLlamaBinary {
        val name = File(binaryPath).name.lowercase()
        return when {
            "snapdragon_opencl" in name || "opencl" in name -> EffectiveLlamaBinary.OPENCL
            "snapdragon_vulkan" in name || "vulkan" in name -> EffectiveLlamaBinary.VULKAN
            "_i8mm" in name -> EffectiveLlamaBinary.CPU_I8MM
            "_armv9" in name -> EffectiveLlamaBinary.CPU_ARMV9
            "_dotprod" in name -> EffectiveLlamaBinary.CPU_DOTPROD
            else -> EffectiveLlamaBinary.CPU_BASELINE
        }
    }

    private fun effectiveKvOffloadModeForBinary(binaryPath: String, requestedMode: String): String {
        val effectiveBinary = effectiveBinaryForPath(binaryPath)
        val requestedBackend = BinaryRepository.requestedKvBackendFromPreference(requestedMode)
        val effectiveBackend = BinaryRepository.resolveKvBackend(requestedBackend, effectiveBinary)
        DebugLog.log(
            "LlamaService: KV cache backend requested=$requestedBackend effective=$effectiveBackend " +
                "reason=\"effective binary is ${effectiveBinary.name.lowercase()}\""
        )
        return BinaryRepository.kvOffloadPreferenceForEffectiveBackend(effectiveBackend)
    }

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
                    Companion.clearRecentStartupFailure()
                    ensureForegroundNotification()
                    val distributedLaunchProfile = DistributedLlamaLaunchProfile.decode(
                        intent.getStringExtra(EXTRA_DISTRIBUTED_PROFILE_JSON)
                    )
                    val modelPath = distributedLaunchProfile?.config?.modelPath
                        ?: intent.getStringExtra(EXTRA_MODEL_PATH)
                    val isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false)
                    val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                    val allowSettingsMmproj = intent.getBooleanExtra(EXTRA_ALLOW_SETTINGS_MMPROJ, true)
                    val settingsProfile = intent.getStringExtra(EXTRA_SETTINGS_PROFILE) ?: SETTINGS_PROFILE_GENERAL
                    
                    // Get optional settings overrides (used by distributed mode to avoid changing global settings)
                    val threadsOverride = if (intent.hasExtra(EXTRA_THREADS)) intent.getIntExtra(EXTRA_THREADS, -1) else null
                    val batchSizeOverride = if (intent.hasExtra(EXTRA_BATCH_SIZE)) intent.getIntExtra(EXTRA_BATCH_SIZE, 512) else null
                    val physicalBatchSizeOverride = if (intent.hasExtra(EXTRA_PHYSICAL_BATCH_SIZE)) intent.getIntExtra(EXTRA_PHYSICAL_BATCH_SIZE, 512) else null
                    val contextSizeOverride = if (intent.hasExtra(EXTRA_CONTEXT_SIZE)) intent.getIntExtra(EXTRA_CONTEXT_SIZE, -1) else null
                    val temperatureOverride = if (intent.hasExtra(EXTRA_TEMPERATURE)) intent.getFloatExtra(EXTRA_TEMPERATURE, -1f) else null
                    val hostOverride = intent.getStringExtra(EXTRA_HOST)
                    val portOverride = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, -1) else null
                    
                    // Speculative decoding extras
                    val draftModelPath = intent.getStringExtra(EXTRA_DRAFT_MODEL_PATH)
                    val draftMax = if (intent.hasExtra(EXTRA_DRAFT_MAX)) intent.getIntExtra(EXTRA_DRAFT_MAX, 3) else null
                    val draftMin = if (intent.hasExtra(EXTRA_DRAFT_MIN)) intent.getIntExtra(EXTRA_DRAFT_MIN, 0) else null
                    val draftPMin = if (intent.hasExtra(EXTRA_DRAFT_P_MIN)) intent.getFloatExtra(EXTRA_DRAFT_P_MIN, 0.0f) else null
                    val draftThreads = if (intent.hasExtra(EXTRA_DRAFT_THREADS)) intent.getIntExtra(EXTRA_DRAFT_THREADS, 4) else null
                    val draftThreadsBatch = if (intent.hasExtra(EXTRA_DRAFT_THREADS_BATCH)) intent.getIntExtra(EXTRA_DRAFT_THREADS_BATCH, 4) else null
                    
                    val parallelOverride = if (intent.hasExtra(EXTRA_PARALLEL)) intent.getIntExtra(EXTRA_PARALLEL, 1) else null
                    val cacheRamOverride = if (intent.hasExtra(EXTRA_CACHE_RAM)) intent.getIntExtra(EXTRA_CACHE_RAM, 0) else null
                    val customFlagsOverride = intent.getStringExtra(EXTRA_CUSTOM_FLAGS)
                    val flashAttentionOverride = if (intent.hasExtra(EXTRA_FLASH_ATTENTION)) intent.getBooleanExtra(EXTRA_FLASH_ATTENTION, false) else null
                    val kvCacheEnabledOverride = if (intent.hasExtra(EXTRA_KV_CACHE_ENABLED)) intent.getBooleanExtra(EXTRA_KV_CACHE_ENABLED, false) else null
                    val kvCacheTypeKOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_K)
                    val kvCacheTypeVOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_V)
                    val kvCacheReuseOverride = if (intent.hasExtra(EXTRA_KV_CACHE_REUSE)) intent.getIntExtra(EXTRA_KV_CACHE_REUSE, 0) else null
                    val commandTemplateOverride = intent.getStringExtra(EXTRA_COMMAND_TEMPLATE)
                    val localLaunchProfile = LlamaServerLaunchProfile.decode(
                        intent.getStringExtra(EXTRA_LAUNCH_PROFILE_JSON)
                    )
                    
                    DebugLog.log("LlamaService: MODEL_PATH=$modelPath")
                    if (mmprojPath != null) {
                        DebugLog.log("LlamaService: MMPROJ_PATH=$mmprojPath")
                    }
                    if (modelPath.isNullOrEmpty()) {
                        DebugLog.log("LlamaService: ERROR - No model path provided!")
                        Companion.updateState(ServerState.Error("No model selected"))
                        stopSelf()
                    } else {
                        scheduleLaunch("start") { generation ->
                        startServer(modelPath, isEmbedding, mmprojPath,
                            threadsOverride, contextSizeOverride, temperatureOverride, hostOverride, portOverride,
                            draftModelPath = draftModelPath, draftMax = draftMax, draftMin = draftMin, draftPMin = draftPMin,
                            draftThreads = draftThreads, draftThreadsBatch = draftThreadsBatch,
                            kvCacheEnabledOverride = kvCacheEnabledOverride,
                            kvCacheTypeKOverride = kvCacheTypeKOverride,
                            kvCacheTypeVOverride = kvCacheTypeVOverride,
                            kvCacheReuseOverride = kvCacheReuseOverride,
                            customCommandOverride = intent.getStringExtra(EXTRA_CUSTOM_COMMAND),
                            commandTemplateOverride = commandTemplateOverride,
                            batchSizeOverride = batchSizeOverride,
                            physicalBatchSizeOverride = physicalBatchSizeOverride,
                            parallelOverride = parallelOverride, cacheRamOverride = cacheRamOverride, customFlagsOverride = customFlagsOverride, flashAttentionOverride = flashAttentionOverride,
                            settingsProfile = settingsProfile,
                            allowSettingsMmproj = allowSettingsMmproj,
                            localLaunchProfile = localLaunchProfile,
                            distributedLaunchProfile = distributedLaunchProfile,
                            rpcWorkersOverride = intent.rpcWorkersOverride(),
                            rpcWorkerRamOverride = intent.rpcWorkerRamOverride(),
                            masterRamOverride = intent.takeIf { it.hasExtra(EXTRA_MASTER_RAM_MB) }
                                ?.getIntExtra(EXTRA_MASTER_RAM_MB, 0),
                            generation = generation)
                        }
                    }
                }
                ACTION_STOP -> {
                    Companion.clearRecentStartupFailure()
                    scheduleStop(startId)
                }
                ACTION_RECOVER -> {
                    ensureForegroundNotification()
                    scheduleRecovery(startId)
                }
                ACTION_SWITCH_MODEL -> {
                    val newModelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                    if (newModelPath.isNullOrEmpty()) {
                        DebugLog.log("LlamaService: SWITCH_MODEL - No model path provided!")
                    } else {
                        DebugLog.log("LlamaService: SWITCH_MODEL to $newModelPath")
                        scheduleLaunch("switch_model", restartDelayMs = 1_000L) { generation ->
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
                                draftThreads = params["draftThreads"] as? Int,
                                draftThreadsBatch = params["draftThreadsBatch"] as? Int,
                                kvCacheEnabledOverride = params["kvCacheEnabled"] as? Boolean,
                                kvCacheTypeKOverride = params["kvCacheTypeK"] as? String,
                                kvCacheTypeVOverride = params["kvCacheTypeV"] as? String,
                                kvCacheReuseOverride = params["kvCacheReuse"] as? Int,
                                batchSizeOverride = params["batchSize"] as? Int,
                                physicalBatchSizeOverride = params["physicalBatchSize"] as? Int,
                                parallelOverride = params["parallel"] as? Int,
                                cacheRamOverride = params["cacheRam"] as? Int,
                                customFlagsOverride = params["customFlags"] as? String,
                                flashAttentionOverride = params["flashAttention"] as? Boolean,
                                commandTemplateOverride = params["commandTemplate"] as? String,
                                settingsProfile = params["settingsProfile"] as? String ?: SETTINGS_PROFILE_GENERAL,
                                generation = generation
                            )
                        }
                    }
                }
                ACTION_RECONFIGURE -> {
                    // Acknowledge foreground execution before process cleanup or model parsing.
                    ensureForegroundNotification()
                    val newModelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                    if (newModelPath.isNullOrBlank()) {
                        DebugLog.log("LlamaService: RECONFIGURE - No model path provided")
                    } else {
                        val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                        val allowSettingsMmproj = intent.getBooleanExtra(EXTRA_ALLOW_SETTINGS_MMPROJ, true)
                        scheduleLaunch("reconfigure", restartDelayMs = 750L) { generation ->
                            startServer(
                                modelPath = newModelPath,
                                isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false),
                                mmprojPath = mmprojPath,
                                threadsOverride = intent.takeIf { it.hasExtra(EXTRA_THREADS) }
                                    ?.getIntExtra(EXTRA_THREADS, -1),
                                contextSizeOverride = intent.takeIf { it.hasExtra(EXTRA_CONTEXT_SIZE) }
                                    ?.getIntExtra(EXTRA_CONTEXT_SIZE, -1),
                                temperatureOverride = intent.takeIf { it.hasExtra(EXTRA_TEMPERATURE) }
                                    ?.getFloatExtra(EXTRA_TEMPERATURE, -1f),
                                hostOverride = intent.getStringExtra(EXTRA_HOST),
                                portOverride = intent.takeIf { it.hasExtra(EXTRA_PORT) }
                                    ?.getIntExtra(EXTRA_PORT, -1),
                                batchSizeOverride = intent.takeIf { it.hasExtra(EXTRA_BATCH_SIZE) }
                                    ?.getIntExtra(EXTRA_BATCH_SIZE, 512),
                                physicalBatchSizeOverride = intent.takeIf { it.hasExtra(EXTRA_PHYSICAL_BATCH_SIZE) }
                                    ?.getIntExtra(EXTRA_PHYSICAL_BATCH_SIZE, 512),
                                kvCacheEnabledOverride = intent.takeIf { it.hasExtra(EXTRA_KV_CACHE_ENABLED) }
                                    ?.getBooleanExtra(EXTRA_KV_CACHE_ENABLED, false),
                                kvCacheTypeKOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_K),
                                kvCacheTypeVOverride = intent.getStringExtra(EXTRA_KV_CACHE_TYPE_V),
                                kvCacheReuseOverride = intent.takeIf { it.hasExtra(EXTRA_KV_CACHE_REUSE) }
                                    ?.getIntExtra(EXTRA_KV_CACHE_REUSE, 0),
                                parallelOverride = intent.takeIf { it.hasExtra(EXTRA_PARALLEL) }
                                    ?.getIntExtra(EXTRA_PARALLEL, 1),
                                cacheRamOverride = intent.takeIf { it.hasExtra(EXTRA_CACHE_RAM) }
                                    ?.getIntExtra(EXTRA_CACHE_RAM, 0),
                                customFlagsOverride = intent.getStringExtra(EXTRA_CUSTOM_FLAGS),
                                flashAttentionOverride = intent.takeIf { it.hasExtra(EXTRA_FLASH_ATTENTION) }
                                    ?.getBooleanExtra(EXTRA_FLASH_ATTENTION, false),
                                commandTemplateOverride = intent.getStringExtra(EXTRA_COMMAND_TEMPLATE),
                                settingsProfile = intent.getStringExtra(EXTRA_SETTINGS_PROFILE)
                                    ?: SETTINGS_PROFILE_GENERAL,
                                allowSettingsMmproj = allowSettingsMmproj,
                                generation = generation
                            )
                        }
                    }
                }
                ACTION_PREVIEW_COMMAND -> {
                    restartMode = START_NOT_STICKY
                     val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                     val isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false)
                     val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                     val allowSettingsMmproj = intent.getBooleanExtra(EXTRA_ALLOW_SETTINGS_MMPROJ, true)
                     val settingsProfile = intent.getStringExtra(EXTRA_SETTINGS_PROFILE) ?: SETTINGS_PROFILE_GENERAL
                     
                     // Get optional settings overrides
                     val threadsOverride = if (intent.hasExtra(EXTRA_THREADS)) intent.getIntExtra(EXTRA_THREADS, -1) else null
                     val batchSizeOverride = if (intent.hasExtra(EXTRA_BATCH_SIZE)) intent.getIntExtra(EXTRA_BATCH_SIZE, 512) else null
                     val physicalBatchSizeOverride = if (intent.hasExtra(EXTRA_PHYSICAL_BATCH_SIZE)) intent.getIntExtra(EXTRA_PHYSICAL_BATCH_SIZE, 512) else null
                     val contextSizeOverride = if (intent.hasExtra(EXTRA_CONTEXT_SIZE)) intent.getIntExtra(EXTRA_CONTEXT_SIZE, -1) else null
                     val temperatureOverride = if (intent.hasExtra(EXTRA_TEMPERATURE)) intent.getFloatExtra(EXTRA_TEMPERATURE, -1f) else null
                     val hostOverride = intent.getStringExtra(EXTRA_HOST)
                     val portOverride = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, -1) else null
                     
                     // Speculative decoding extras
                     val draftModelPath = intent.getStringExtra(EXTRA_DRAFT_MODEL_PATH)
                     val draftMax = if (intent.hasExtra(EXTRA_DRAFT_MAX)) intent.getIntExtra(EXTRA_DRAFT_MAX, 3) else null
                     val draftMin = if (intent.hasExtra(EXTRA_DRAFT_MIN)) intent.getIntExtra(EXTRA_DRAFT_MIN, 0) else null
                     val draftPMin = if (intent.hasExtra(EXTRA_DRAFT_P_MIN)) intent.getFloatExtra(EXTRA_DRAFT_P_MIN, 0.0f) else null
                     val draftThreads = if (intent.hasExtra(EXTRA_DRAFT_THREADS)) intent.getIntExtra(EXTRA_DRAFT_THREADS, 4) else null
                     val draftThreadsBatch = if (intent.hasExtra(EXTRA_DRAFT_THREADS_BATCH)) intent.getIntExtra(EXTRA_DRAFT_THREADS_BATCH, 4) else null
                     
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
                             draftThreads = draftThreads, draftThreadsBatch = draftThreadsBatch,
                             kvCacheEnabledOverride = kvCacheEnabledOverride,
                             kvCacheTypeKOverride = kvCacheTypeKOverride,
                             kvCacheTypeVOverride = kvCacheTypeVOverride,
                             kvCacheReuseOverride = kvCacheReuseOverride,
                             customCommandOverride = intent.getStringExtra(EXTRA_CUSTOM_COMMAND),
                             commandTemplateOverride = commandTemplateOverride,
                             batchSizeOverride = batchSizeOverride,
                             physicalBatchSizeOverride = physicalBatchSizeOverride,
                             parallelOverride = parallelOverride, cacheRamOverride = cacheRamOverride, customFlagsOverride = customFlagsOverride, flashAttentionOverride = flashAttentionOverride,
                             settingsProfile = settingsProfile,
                             allowSettingsMmproj = allowSettingsMmproj,
                             rpcWorkersOverride = intent.rpcWorkersOverride(),
                             rpcWorkerRamOverride = intent.rpcWorkerRamOverride(),
                             masterRamOverride = intent.takeIf { it.hasExtra(EXTRA_MASTER_RAM_MB) }
                                 ?.getIntExtra(EXTRA_MASTER_RAM_MB, 0))
                     }
                }
            }
        } catch (e: Exception) {
            val message = e.message ?: "Unknown error"
            DebugLog.log("LlamaService: CRASH in onStartCommand: $message")
            if (intent?.action == ACTION_START || intent?.action == ACTION_SWITCH_MODEL) {
                handlePreLaunchStartFailure(message, previewMode = false)
            }
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
        draftThreads: Int? = null,
        draftThreadsBatch: Int? = null,
        kvCacheEnabledOverride: Boolean? = null,
        kvCacheTypeKOverride: String? = null,
        kvCacheTypeVOverride: String? = null,
        kvCacheReuseOverride: Int? = null,
        customCommandOverride: String? = null,
        commandTemplateOverride: String? = null,
        batchSizeOverride: Int? = null,
        physicalBatchSizeOverride: Int? = null,
        parallelOverride: Int? = null,
        cacheRamOverride: Int? = null,
        customFlagsOverride: String? = null,
        flashAttentionOverride: Boolean? = null,
        settingsProfile: String = SETTINGS_PROFILE_GENERAL,
        allowSettingsMmproj: Boolean = true,
        localLaunchProfile: LlamaServerLaunchProfile? = null,
        distributedLaunchProfile: DistributedLlamaLaunchProfile? = null,
        rpcWorkersOverride: List<String>? = null,
        rpcWorkerRamOverride: IntArray? = null,
        masterRamOverride: Int? = null,
        generation: Long? = null
    ): Job {
        if (!previewMode) {
            ensureForegroundNotification()
            
            // Acquire CPU + Wi-Fi locks to keep the local server responsive while the screen is off.
            WakeLockManager.acquire(applicationContext, "LlamaService")
            WakeLockManager.acquireWifiLock(applicationContext, "LlamaService")
            
            updateStateForGeneration(generation, ServerState.Starting)
            DebugLog.log("LlamaService: Starting server for model: $modelPath")
        } else {
            DebugLog.log("LlamaService: Generating PREVIEW command for model: $modelPath")
        }

        val distributedConfig = distributedLaunchProfile?.config
        val isMasterProfile = settingsProfile == SETTINGS_PROFILE_MASTER || distributedConfig != null
        val isOcrProfile = settingsProfile == SETTINGS_PROFILE_OCR

        // Read settings from repository, but use overrides if provided.
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(applicationContext)
        val threads = distributedConfig?.threads ?: localLaunchProfile?.threads ?: threadsOverride ?: when {
            isMasterProfile -> DistributedService.masterThreads.value
            isOcrProfile -> 4
            else -> settingsRepo.threads.value
        }
        val batchSize = distributedConfig?.batchSize ?: localLaunchProfile?.batchSize ?: batchSizeOverride ?: when {
            isMasterProfile -> DistributedService.masterBatchSize.value
            isOcrProfile -> 512
            else -> settingsRepo.serverBatchSize.value
        }
        val physicalBatchSize = distributedConfig?.physicalBatchSize ?: localLaunchProfile?.physicalBatchSize ?: physicalBatchSizeOverride ?: when {
            isMasterProfile -> null
            isOcrProfile -> 512
            else -> settingsRepo.serverPhysicalBatchSize.value
        }
        val contextSize = distributedConfig?.contextSize ?: localLaunchProfile?.contextSize ?: contextSizeOverride ?: when {
            isMasterProfile -> DistributedService.masterContextSize.value
            isOcrProfile -> 4096
            else -> settingsRepo.contextSize.value
        }
        val temperature = distributedConfig?.temperature ?: localLaunchProfile?.temperature ?: temperatureOverride ?: when {
            isMasterProfile -> DistributedService.masterTemperature.value
            isOcrProfile -> 0.0f
            else -> settingsRepo.temperature.value
        }
        val host = distributedConfig?.host ?: hostOverride ?: when {
            isMasterProfile || isOcrProfile -> "127.0.0.1"
            settingsRepo.remoteAccess.value -> "0.0.0.0"
            else -> "127.0.0.1"
        }
        val port = distributedConfig?.port ?: portOverride ?: if (isMasterProfile) 8080 else settingsRepo.serverPort.value
        val enableVision = localLaunchProfile?.visionEnabled ?: if (isMasterProfile || isOcrProfile) mmprojPath != null else settingsRepo.enableVision.value
        val selectedMmprojPath = localLaunchProfile?.mmprojPath ?: if (isMasterProfile || isOcrProfile) null else settingsRepo.selectedMmprojPath.value
        val selectedLoraPath = localLaunchProfile?.loraPath ?: if (isMasterProfile || isOcrProfile) null else settingsRepo.selectedLlmLoraPath.value
        
        // KV Cache settings for server
        val kvCacheEnabled = distributedConfig?.kvCacheEnabled ?: localLaunchProfile?.kvCacheEnabled ?: kvCacheEnabledOverride ?: when {
            isMasterProfile -> DistributedService.masterKvCacheEnabled.value
            isOcrProfile -> false
            else -> settingsRepo.serverKvCacheEnabled.value
        }
        val kvCacheTypeK = distributedConfig?.kvCacheTypeK ?: localLaunchProfile?.kvCacheTypeK ?: kvCacheTypeKOverride ?: when {
            isMasterProfile -> DistributedService.masterKvCacheTypeK.value
            isOcrProfile -> "f16"
            else -> settingsRepo.serverKvCacheTypeK.value
        }
        val kvCacheTypeV = distributedConfig?.kvCacheTypeV ?: localLaunchProfile?.kvCacheTypeV ?: kvCacheTypeVOverride ?: when {
            isMasterProfile -> DistributedService.masterKvCacheTypeV.value
            isOcrProfile -> "f16"
            else -> settingsRepo.serverKvCacheTypeV.value
        }
        val kvCacheReuse = distributedConfig?.kvCacheReuse ?: localLaunchProfile?.kvCacheReuse ?: kvCacheReuseOverride ?: when {
            isMasterProfile -> DistributedService.masterKvCacheReuse.value
            isOcrProfile -> 0
            else -> settingsRepo.serverKvCacheReuse.value
        }
        val kvOffloadMode = localLaunchProfile?.kvOffloadMode ?: when {
            isMasterProfile -> com.example.llamadroid.data.SettingsRepository.LLAMA_KV_OFFLOAD_AUTO
            isOcrProfile -> com.example.llamadroid.data.SettingsRepository.LLAMA_KV_OFFLOAD_CPU
            else -> settingsRepo.llamaKvOffloadMode.value
        }
        val noMmap = localLaunchProfile?.noMmap ?: when {
            isMasterProfile || isOcrProfile -> false
            else -> settingsRepo.lowMemoryMode.value
        }
        val parallel = distributedConfig?.parallel ?: localLaunchProfile?.parallel ?: parallelOverride ?: when {
            isMasterProfile -> DistributedService.masterParallel.value
            isOcrProfile -> 1
            else -> settingsRepo.serverParallel.value
        }
        val cacheRam = distributedConfig?.cacheRam ?: localLaunchProfile?.cacheRam ?: cacheRamOverride ?: when {
            isMasterProfile -> DistributedService.masterCacheRam.value
            isOcrProfile -> null
            else -> settingsRepo.serverCacheRam.value
        }
        val contextCheckpoints = localLaunchProfile?.contextCheckpoints ?: if (isMasterProfile || isOcrProfile) null else settingsRepo.serverContextCheckpoints.value
        val checkpointMinStep = localLaunchProfile?.checkpointMinStep ?: if (isMasterProfile || isOcrProfile) null else settingsRepo.serverCheckpointMinStep.value
        val cachePrompt = localLaunchProfile?.cachePrompt ?: if (isMasterProfile || isOcrProfile) true else settingsRepo.serverCachePrompt.value
        val cacheIdleSlots = localLaunchProfile?.cacheIdleSlots ?: if (isMasterProfile || isOcrProfile) true else settingsRepo.serverCacheIdleSlots.value
        val kvUnifiedMode = localLaunchProfile?.kvUnifiedMode ?: if (isMasterProfile || isOcrProfile) {
            LlamaKvUnifiedMode.AUTO.value
        } else {
            settingsRepo.serverKvUnifiedMode.value
        }
        val swaFull = localLaunchProfile?.swaFull ?: (!isMasterProfile && !isOcrProfile && settingsRepo.serverSwaFull.value)
        val sleepIdleSeconds = localLaunchProfile?.sleepIdleSeconds ?: if (isMasterProfile || isOcrProfile) null else settingsRepo.serverSleepIdleSeconds.value
        val customFlags = distributedConfig?.customFlags ?: localLaunchProfile?.customFlags ?: customFlagsOverride ?: when {
            isMasterProfile -> DistributedService.masterCustomFlags.value
            isOcrProfile -> null
            else -> settingsRepo.customFlags.value
        }
        val flashAttention = distributedConfig?.flashAttention ?: localLaunchProfile?.flashAttention ?: flashAttentionOverride ?: when {
            isMasterProfile -> DistributedService.masterFlashAttention.value
            isOcrProfile -> false
            else -> settingsRepo.flashAttentionEnabled.value
        }
        val requestedMasterSpeculativeMode = distributedConfig?.speculativeMode
            ?: DistributedService.masterSpeculativeMode.value
        val masterMtpUsesDraftModel = isMasterProfile &&
            (requestedMasterSpeculativeMode != LlamaSpeculativeMode.DRAFT_MTP ||
                DistributedService.masterMtpUseDraftModel.value)
        val effectiveDraftModelPath = distributedConfig?.draftModelPath
            ?: localLaunchProfile?.draftModelPath
            ?: draftModelPath
            ?: if (masterMtpUsesDraftModel) {
                DistributedService.masterDraftModel.value?.path ?: DistributedService.masterDraftModelPath.value
            } else null
        val effectiveDraftMax = distributedConfig?.draftMax ?: localLaunchProfile?.draftMax ?: draftMax ?: 3
        val effectiveDraftMin = distributedConfig?.draftMin ?: localLaunchProfile?.draftMin ?: draftMin ?: 0
        val effectiveDraftPMin = distributedConfig?.draftPMin ?: localLaunchProfile?.draftPMin ?: draftPMin ?: 0f
        val masterSpeculativeNeedsDraftModel = requestedMasterSpeculativeMode.requiresDraftModel ||
            (requestedMasterSpeculativeMode == LlamaSpeculativeMode.DRAFT_MTP &&
                DistributedService.masterMtpUseDraftModel.value)
        val speculativeEnabled = if (distributedConfig != null) {
            distributedConfig.speculativeMode != null
        } else localLaunchProfile?.speculativeEnabled ?: if (isMasterProfile) {
            DistributedService.masterSpeculativeEnabled.value &&
                (!masterSpeculativeNeedsDraftModel || !effectiveDraftModelPath.isNullOrBlank())
        } else if (isOcrProfile) {
            false
        } else {
            settingsRepo.speculativeEnabled.value
        }
        val speculativeMode = if (speculativeEnabled) {
            localLaunchProfile?.speculativeMode?.let(LlamaSpeculativeMode::fromFlagValue)
                ?: if (isMasterProfile) requestedMasterSpeculativeMode else settingsRepo.speculativeMode.value
        } else {
            null
        }
        if (speculativeMode?.requiresDraftModel == true && effectiveDraftModelPath.isNullOrBlank()) {
            handlePreLaunchStartFailure(
                getString(R.string.dist_speculative_missing_required_draft),
                previewMode = previewMode,
                generation = generation
            )
            return completedJob()
        }
        if (speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH && !effectiveDraftModelPath.isNullOrBlank()) {
            val dflashDraftError = validateDflashDraftArchitecture(effectiveDraftModelPath, generation)
            if (dflashDraftError != null) {
                handlePreLaunchStartFailure(dflashDraftError, previewMode = previewMode, generation = generation)
                return completedJob()
            }
        }
        val mtpDraftMax = distributedConfig?.mtpDraftMax ?: localLaunchProfile?.mtpDraftMax ?: if (isMasterProfile) DistributedService.masterMtpDraftMax.value else if (isOcrProfile) 3 else settingsRepo.mtpDraftMaxTokens.value
        val mtpDraftMin = distributedConfig?.mtpDraftMin ?: localLaunchProfile?.mtpDraftMin ?: if (isMasterProfile) DistributedService.masterMtpDraftMin.value else if (isOcrProfile) 0 else settingsRepo.mtpDraftMinTokens.value
        val mtpDraftPMin = distributedConfig?.mtpDraftPMin ?: localLaunchProfile?.mtpDraftPMin ?: if (isMasterProfile) DistributedService.masterMtpDraftPMin.value else if (isOcrProfile) 0.0f else settingsRepo.mtpDraftPMin.value
        val draftDeviceMode = distributedConfig?.draftDeviceMode ?: localLaunchProfile?.draftDeviceMode ?: if (isMasterProfile) {
            DistributedService.masterDraftDeviceMode.value
        } else if (isOcrProfile) {
            com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_CPU
        } else {
            settingsRepo.llamaDraftDeviceMode.value
        }
        // This is intentionally a general local-profile preference. Distributed and OCR launches
        // never inherit it, even though they share LlamaConfig with local launches.
        val openClCpuTargetGpuDraft = !isMasterProfile &&
            !isOcrProfile &&
            settingsRepo.llamaOpenClCpuTargetGpuDraft.value
        val effectiveDraftThreads = distributedConfig?.draftThreads ?: localLaunchProfile?.draftThreads ?: draftThreads ?: if (isMasterProfile) DistributedService.masterDraftThreads.value else settingsRepo.draftThreads.value
        val effectiveDraftThreadsBatch = distributedConfig?.draftThreadsBatch ?: localLaunchProfile?.draftThreadsBatch ?: draftThreadsBatch ?: if (isMasterProfile) DistributedService.masterDraftThreadsBatch.value else settingsRepo.draftThreadsBatch.value
        val distributedDraftDeviceId = distributedConfig?.draftDeviceId ?: if (isMasterProfile &&
            DistributedService.masterSpeculativePlacement.value != DistributedSpeculativePlacement.LOCAL &&
            DistributedService.masterSpeculativePlacement.value != DistributedSpeculativePlacement.MASTER_DEDICATED
        ) {
            val index = DistributedService.masterSpeculativeWorkerIndex.value
            index?.let { DistributedService.distributedDeviceSlots(DistributedService.getConfiguredWorkerAddresses()).getOrNull(it) }
        } else null
        val distributedDraftGpuLayers = distributedConfig?.draftGpuLayers ?: if (distributedDraftDeviceId != null) {
            DistributedService.masterDraftGpuLayers.value
        } else null
        val ngramModNMatch = localLaunchProfile?.ngramModNMatch ?: if (isMasterProfile) DistributedService.masterNgramModNMatch.value else if (isOcrProfile) 24 else settingsRepo.ngramModNMatch.value
        val ngramModNMin = localLaunchProfile?.ngramModNMin ?: if (isMasterProfile) DistributedService.masterNgramModNMin.value else if (isOcrProfile) 48 else settingsRepo.ngramModNMin.value
        val ngramModNMax = localLaunchProfile?.ngramModNMax ?: if (isMasterProfile) DistributedService.masterNgramModNMax.value else if (isOcrProfile) 64 else settingsRepo.ngramModNMax.value
        val ngramSimpleSizeN = localLaunchProfile?.ngramSimpleSizeN ?: if (isMasterProfile) DistributedService.masterNgramSimpleSizeN.value else if (isOcrProfile) 12 else settingsRepo.ngramSimpleSizeN.value
        val ngramSimpleSizeM = localLaunchProfile?.ngramSimpleSizeM ?: if (isMasterProfile) DistributedService.masterNgramSimpleSizeM.value else if (isOcrProfile) 48 else settingsRepo.ngramSimpleSizeM.value
        val ngramSimpleMinHits = localLaunchProfile?.ngramSimpleMinHits ?: if (isMasterProfile) DistributedService.masterNgramSimpleMinHits.value else if (isOcrProfile) 1 else settingsRepo.ngramSimpleMinHits.value
        val ngramMapKSizeN = localLaunchProfile?.ngramMapKSizeN ?: if (isMasterProfile) DistributedService.masterNgramMapKSizeN.value else if (isOcrProfile) 12 else settingsRepo.ngramMapKSizeN.value
        val ngramMapKSizeM = localLaunchProfile?.ngramMapKSizeM ?: if (isMasterProfile) DistributedService.masterNgramMapKSizeM.value else if (isOcrProfile) 48 else settingsRepo.ngramMapKSizeM.value
        val ngramMapKMinHits = localLaunchProfile?.ngramMapKMinHits ?: if (isMasterProfile) DistributedService.masterNgramMapKMinHits.value else if (isOcrProfile) 1 else settingsRepo.ngramMapKMinHits.value
        val ngramMapK4VSizeN = localLaunchProfile?.ngramMapK4VSizeN ?: if (isMasterProfile) DistributedService.masterNgramMapK4VSizeN.value else if (isOcrProfile) 12 else settingsRepo.ngramMapK4VSizeN.value
        val ngramMapK4VSizeM = localLaunchProfile?.ngramMapK4VSizeM ?: if (isMasterProfile) DistributedService.masterNgramMapK4VSizeM.value else if (isOcrProfile) 48 else settingsRepo.ngramMapK4VSizeM.value
        val ngramMapK4VMinHits = localLaunchProfile?.ngramMapK4VMinHits ?: if (isMasterProfile) DistributedService.masterNgramMapK4VMinHits.value else if (isOcrProfile) 1 else settingsRepo.ngramMapK4VMinHits.value
        val nativeToolsEnabled = !isMasterProfile && !isOcrProfile && settingsRepo.llamaNativeToolsEnabled.value
        val nativeToolsWorkspaceDir = File(filesDir, "llama_native_tools_workspace")
        val commandTemplate = distributedLaunchProfile?.commandTemplate
            ?.takeIf { it.isNotBlank() }
            ?: localLaunchProfile?.commandTemplate
            ?.takeIf { it.isNotBlank() }
            ?: commandTemplateOverride
            ?.takeIf { it.isNotBlank() }
            ?: if (isMasterProfile) {
                DistributedService.masterCommandTemplate.value.takeIf { it.isNotBlank() }
            } else if (isOcrProfile) {
                null
            } else {
                settingsRepo.customCommandTemplate.value.takeIf { it.isNotBlank() }
            }

        // Check for a fully overridden command (used by the master preview editor).
        val finalCustomCommand = distributedLaunchProfile?.customCommand
            ?: customCommandOverride
            ?: if (isMasterProfile && distributedLaunchProfile == null) DistributedService.customCommand.value else null
        
        // Use mmproj if vision is enabled AND we have a mmproj path (either from intent or settings)
        val effectiveMmprojPath = if (enableVision) {
            mmprojPath ?: selectedMmprojPath.takeIf { allowSettingsMmproj }
        } else null
        
        DebugLog.log("LlamaService: Settings - threads=$threads, batch=$batchSize, ubatch=${physicalBatchSize ?: "auto"}, ctx=$contextSize, temp=$temperature, host=$host, port=$port, parallel=${parallel ?: "auto"}, cacheRam=${cacheRam ?: "auto"}")
        DebugLog.log("LlamaService: Vision enabled=$enableVision, mmproj=$effectiveMmprojPath")
        
        // Save last run params for remote switch support
        if (!previewMode) {
            DistributedService.setLastRunParams(mapOf(
                "modelPath" to modelPath,
                "isEmbedding" to isEmbedding,
                "mmprojPath" to effectiveMmprojPath,
                "loraPath" to selectedLoraPath,
                "threads" to threads,
                "batchSize" to batchSize,
                "physicalBatchSize" to physicalBatchSize,
                "contextSize" to contextSize,
                "temperature" to temperature,
                "host" to host,
                "port" to port,
                "speculativeEnabled" to speculativeEnabled,
                "speculativeMode" to speculativeMode?.flagValue,
                "draftModelPath" to effectiveDraftModelPath,
                "draftMax" to effectiveDraftMax,
                "draftMin" to effectiveDraftMin,
                "draftPMin" to effectiveDraftPMin,
                "draftThreads" to effectiveDraftThreads,
                "draftThreadsBatch" to effectiveDraftThreadsBatch,
                "mtpDraftMax" to mtpDraftMax,
                "mtpDraftMin" to mtpDraftMin,
                "mtpDraftPMin" to mtpDraftPMin,
                "ngramModNMatch" to ngramModNMatch,
                "ngramModNMin" to ngramModNMin,
                "ngramModNMax" to ngramModNMax,
                "ngramSimpleSizeN" to ngramSimpleSizeN,
                "ngramSimpleSizeM" to ngramSimpleSizeM,
                "ngramSimpleMinHits" to ngramSimpleMinHits,
                "ngramMapKSizeN" to ngramMapKSizeN,
                "ngramMapKSizeM" to ngramMapKSizeM,
                "ngramMapKMinHits" to ngramMapKMinHits,
                "ngramMapK4VSizeN" to ngramMapK4VSizeN,
                "ngramMapK4VSizeM" to ngramMapK4VSizeM,
                "ngramMapK4VMinHits" to ngramMapK4VMinHits,
                "nativeToolsEnabled" to nativeToolsEnabled,
                "kvCacheEnabled" to kvCacheEnabled,
                "kvCacheTypeK" to kvCacheTypeK,
                "kvCacheTypeV" to kvCacheTypeV,
                "kvCacheReuse" to kvCacheReuse,
                "kvOffloadMode" to kvOffloadMode,
                "draftDeviceMode" to draftDeviceMode,
                "parallel" to parallel,
                "cacheRam" to cacheRam,
                "contextCheckpoints" to contextCheckpoints,
                "checkpointMinStep" to checkpointMinStep,
                "cachePrompt" to cachePrompt,
                "cacheIdleSlots" to cacheIdleSlots,
                "kvUnifiedMode" to kvUnifiedMode,
                "swaFull" to swaFull,
                "sleepIdleSeconds" to sleepIdleSeconds,
                "customFlags" to customFlags,
                "flashAttention" to flashAttention,
                "commandTemplate" to commandTemplate,
                "settingsProfile" to settingsProfile
            ))
        }
        if (kvCacheEnabled) {
            DebugLog.log("LlamaService: KV cache enabled - K=$kvCacheTypeK, V=$kvCacheTypeV, reuse=$kvCacheReuse")
        }
        DebugLog.log("LlamaService: KV offload mode=$kvOffloadMode, draft device mode=$draftDeviceMode")
        
        // Get distributed inference workers from DistributedService (if master mode is active)
        val rpcWorkers = distributedConfig?.rpcWorkers ?: rpcWorkersOverride ?: if (DistributedService.mode.value == DistributedMode.MASTER) {
            DistributedService.getConfiguredWorkerAddresses().also {
                if (it.isNotEmpty()) {
                    DebugLog.log("LlamaService: Distributed mode - workers: ${it.joinToString(",")}")
                }
            }
        } else {
            emptyList()
        }

        val distributedFitEnabled = distributedConfig?.fitEnabled
            ?: (isMasterProfile && rpcWorkers.isNotEmpty() && DistributedService.masterFitEnabled.value)
        val distributedFitTargetMiB = distributedConfig?.fitTargetMiB
            ?: if (distributedFitEnabled) DistributedService.masterFitTargetMiB.value else null
        val distributedNglArgument = distributedConfig?.nGpuLayersArgument ?: if (distributedFitEnabled) {
            DistributedService.masterNglArgument.value.trim().takeIf { it.isNotEmpty() } ?: "auto"
        } else null
        if (rpcWorkers.isNotEmpty()) {
            runCatching {
                DistributedLlamaArguments.validate(
                    deviceCount = rpcWorkers.size,
                    fitEnabled = distributedFitEnabled,
                    fitTargetMiB = distributedFitTargetMiB,
                    tensorSplit = null
                )
            }.onFailure { error ->
                handlePreLaunchStartFailure(error.message ?: getString(R.string.dist_fit_invalid), previewMode, generation)
                return completedJob()
            }
        }
        
        return serviceScope.launch {
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
                    if (!isMasterProfile && !isOcrProfile && processController.containsDistributedOnlyArgument(args)) {
                        handlePreLaunchStartFailure(
                            getString(R.string.llama_local_distributed_args_rejected),
                            previewMode,
                            generation
                        )
                        return@launch
                    }
                    val commandString = processController.buildCommandString(args)
                    DistributedService.setLastCommand(commandString)

                    if (previewMode) {
                        DebugLog.log("LlamaService: Preview Custom Command: $commandString")
                        return@launch
                    }

                    DebugLog.log("LlamaService: Using CUSTOM command override")
                    updateStateForGeneration(generation, ServerState.Starting)
                    updateNotification("Starting custom command...")
                    currentServerPort = port
                    val customResult = processController.start(
                        binary,
                        LlamaConfig(modelPath = modelPath),
                        filesDir,
                        customArgs = args,
                        runtimeGenerationId = Companion.runtimeGenerationId(),
                        onState = { updateStateForGeneration(generation, it) },
                        onClearServerLogs = { clearServerLogsForGeneration(generation) },
                        onServerLog = { appendServerLogForGeneration(generation, it) },
                        onOwnedProcessStarted = { pid, startTimeTicks ->
                            recordOwnedRuntimeForGeneration(generation, pid, port, startTimeTicks)
                        }
                    )
                    stopServer(
                        finalError = customResult.startupFailureMessage?.takeIf { !customResult.becameReady },
                        generation = generation
                    )
                    return@launch
                }
                
                // Calculate layer distribution for RPC workers
                var nGpuLayers = distributedConfig?.nGpuLayers ?: 0
                var tensorSplit: String? = distributedConfig?.tensorSplit
                
                if (rpcWorkers.isNotEmpty() && distributedConfig == null) {
                    // CRITICAL FIX: Use ENABLED workers for calculation, not just connected ones.
                    // This aligns with getConfiguredWorkerAddresses() and fixes the race condition.
                    val connectedWorkers = DistributedService.workers.value.filter { it.isEnabled }
                        .ifEmpty {
                            rpcWorkers.mapIndexed { index, address ->
                                val (ip, portText) = address.split(":", limit = 2).let {
                                    it.firstOrNull().orEmpty() to it.getOrNull(1)
                                }
                                WorkerInfo(
                                    ip = ip,
                                    port = portText?.toIntOrNull() ?: DistributedService.RPC_DEFAULT_PORT,
                                    deviceName = "RPC${index}",
                                    availableRamMB = rpcWorkerRamOverride?.getOrNull(index) ?: 0,
                                    isEnabled = true
                                )
                            }
                        }
                    
                    if (connectedWorkers.isNotEmpty()) {
                        val speculativePlacement = DistributedService.masterSpeculativePlacement.value
                        val draftWorkerIndex = DistributedService.masterSpeculativeWorkerIndex.value
                            ?.takeIf { speculativePlacement != DistributedSpeculativePlacement.LOCAL && it in connectedWorkers.indices }
                        val draftReservationMB = effectiveDraftModelPath?.let { path ->
                            java.io.File(path).takeIf { it.isFile }?.let { (it.length() / (1024 * 1024)).toInt() }
                        } ?: 0

                        // Get master RAM (from settings) and each worker's RAM
                        val masterRamMB = masterRamOverride ?: DistributedService.masterRamMB.value
                        
                        // Get model info
                        val modelFile = java.io.File(modelPath)
                        val modelSizeMB = (modelFile.length() / (1024 * 1024)).toInt()
                        val ggufMetadata = GGUFParser.parse(modelPath)
                        val totalLayers = (ggufMetadata?.layerCount ?: 40).coerceAtLeast(1)

                        // A worker hosting the complete speculative model must have that
                        // model's memory reserved before target-model capacity or splits
                        // are calculated. Dedicated placement additionally receives no
                        // target-model share at all.
                        val workerRams = connectedWorkers.mapIndexed { index, worker ->
                            val reserve = if (index == draftWorkerIndex) draftReservationMB else 0
                            val remaining = (worker.availableRamMB - reserve).coerceAtLeast(0)
                            if (speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED &&
                                index == draftWorkerIndex
                            ) 0 else remaining
                        }
                        val totalWorkerRamMB = workerRams.sum()
                        val safeMBPerLayer = modelSizeMB.toFloat()
                            .takeIf { it > 0f }
                            ?.div(totalLayers.toFloat())
                            ?.times(1.5f)
                        val avgMBPerLayer = modelSizeMB.toFloat() / totalLayers.toFloat()
                        val maxLayersForWorkers = safeMBPerLayer
                            ?.takeIf { it > 0f }
                            ?.let { (totalWorkerRamMB.toFloat() / it).toInt() }
                            ?: totalLayers
                        
                        // Check if any worker has assignedProportion set - if so, use proportions instead of RAM
                        val workerProportions = connectedWorkers.mapIndexed { index, worker ->
                            if (workerRams[index] > 0) worker.assignedProportion ?: 0f else 0f
                        }
                        val totalWorkerProportion = workerProportions.sum()
                        
                        if (totalWorkerProportion > 0f) {
                            // Use proportion-based calculation
                            // Sum of all worker proportions = layers to RPC
                            // E.g., if worker has 80% assigned, give 80% of layers to RPC
                            val workerProportion = totalWorkerProportion.coerceIn(0f, 1f)
                            nGpuLayers = minOf(
                                (totalLayers * workerProportion).toInt(),
                                maxLayersForWorkers
                            )
                                .coerceIn(0, totalLayers) // Allow full offload when proportion is 1.0
                            DebugLog.log("LlamaService: Using proportion-based split - workers get ${(workerProportion*100).toInt()}% = $nGpuLayers/$totalLayers layers")
                            
                            // Calculate tensor split based on PROPORTIONS
                            if (connectedWorkers.size > 1) {
                                // Normalize proportions so they sum to 1.0 (relative to the total worker share)
                                val totalProp = workerProportions.sum()
                                if (totalProp > 0f) {
                                    val workerFractions = workerProportions.map { prop -> prop / totalProp }
                                    tensorSplit = workerFractions.joinToString(",") {
                                        String.format(java.util.Locale.US, "%.2f", it)
                                    }
                                }
                            }
                        } else {
                            // RAM-based calculation with bytes-per-layer estimation
                            // Since rpc-server reports full device RAM (not user-configured limit),
                            // we must calculate -ngl precisely to fit within user-configured worker RAM
                            
                            val totalRamMB = masterRamMB + totalWorkerRamMB
                            
                            if (masterRamMB == 0) {
                                // Master contributes 0MB = offload ALL layers to workers
                                nGpuLayers = minOf(totalLayers, maxLayersForWorkers)
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
                                // Calculate max layers that fit in total worker RAM
                                // Also calculate proportion-based layers
                                val workerProportion = totalWorkerRamMB.toFloat() / totalRamMB.toFloat()
                                val proportionLayers = (totalLayers * workerProportion).toInt()
                                
                                // Use the MINIMUM of proportion-based and capacity-based
                                nGpuLayers = minOf(proportionLayers, maxLayersForWorkers)
                                    .coerceIn(0, totalLayers)
                                
                                DebugLog.log("LlamaService: RAM-based split - workers: ${totalWorkerRamMB}MB, master: ${masterRamMB}MB")
                                DebugLog.log("LlamaService: Model ~${avgMBPerLayer.toInt()}MB/layer (safe: ${safeMBPerLayer?.toInt() ?: 0}MB/layer)")
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
                        if (tensorSplit != null &&
                            speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED &&
                            draftWorkerIndex != null && draftWorkerIndex in connectedWorkers.indices
                        ) {
                            val values = tensorSplit.split(',').map { it.toFloatOrNull() ?: 0f }.toMutableList()
                            if (values.size == connectedWorkers.size) {
                                values[draftWorkerIndex] = 0f
                                val sum = values.sum()
                                tensorSplit = if (sum > 0f) values.joinToString(",") {
                                    String.format(java.util.Locale.US, "%.2f", it / sum)
                                } else null
                                DebugLog.log("LlamaService: Dedicated speculative worker index=$draftWorkerIndex; target split=$tensorSplit; reservedDraftMB=$draftReservationMB")
                            }
                        }
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
                
                val config = distributedConfig ?: LlamaConfig(
                    modelPath = modelPath, 
                    isEmbedding = isEmbedding,
                    threads = threads,
                    batchSize = batchSize,
                    physicalBatchSize = physicalBatchSize,
                    contextSize = contextSize,
                    temperature = temperature,
                    port = port,
                    host = host,
                    mmprojPath = effectiveMmprojPath,
                    loraPath = selectedLoraPath,
                    kvCacheEnabled = kvCacheEnabled,
                    kvCacheTypeK = kvCacheTypeK,
                    kvCacheTypeV = kvCacheTypeV,
                    kvCacheReuse = kvCacheReuse,
                    kvOffloadMode = kvOffloadMode,
                    rpcWorkers = rpcWorkers,
                    nGpuLayers = nGpuLayers,
                    nGpuLayersArgument = distributedNglArgument,
                    tensorSplit = tensorSplit,
                    fitEnabled = distributedFitEnabled,
                    fitTargetMiB = distributedFitTargetMiB,
                    noMmap = noMmap,
                    speculativeMode = speculativeMode,
                    draftModelPath = effectiveDraftModelPath,
                    draftMax = effectiveDraftMax,
                    draftMin = effectiveDraftMin,
                    draftPMin = effectiveDraftPMin,
                    draftThreads = effectiveDraftThreads,
                    draftThreadsBatch = effectiveDraftThreadsBatch,
                    draftDeviceMode = draftDeviceMode,
                    draftDeviceId = distributedDraftDeviceId,
                    draftGpuLayers = distributedDraftGpuLayers,
                    openClCpuTargetGpuDraft = openClCpuTargetGpuDraft,
                    mtpDraftMax = mtpDraftMax,
                    mtpDraftMin = mtpDraftMin,
                    mtpDraftPMin = mtpDraftPMin,
                    ngramModNMatch = ngramModNMatch,
                    ngramModNMin = ngramModNMin,
                    ngramModNMax = ngramModNMax,
                    ngramSimpleSizeN = ngramSimpleSizeN,
                    ngramSimpleSizeM = ngramSimpleSizeM,
                    ngramSimpleMinHits = ngramSimpleMinHits,
                    ngramMapKSizeN = ngramMapKSizeN,
                    ngramMapKSizeM = ngramMapKSizeM,
                    ngramMapKMinHits = ngramMapKMinHits,
                    ngramMapK4VSizeN = ngramMapK4VSizeN,
                    ngramMapK4VSizeM = ngramMapK4VSizeM,
                    ngramMapK4VMinHits = ngramMapK4VMinHits,
                    nativeToolsEnabled = nativeToolsEnabled,
                    parallel = parallel,
                    cacheRam = cacheRam,
                    contextCheckpoints = contextCheckpoints,
                    checkpointMinStep = checkpointMinStep,
                    cachePrompt = cachePrompt,
                    cacheIdleSlots = cacheIdleSlots,
                    kvUnifiedMode = kvUnifiedMode,
                    swaFull = swaFull,
                    sleepIdleSeconds = sleepIdleSeconds,
                    customFlags = customFlags,
                    flashAttention = flashAttention
                )
                currentServerPort = config.port
                
                
                DebugLog.log("LlamaService: Binary found at $binary")

                var launchConfig = config
                var primaryBinaryFile = binaryFile
                if (config.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP &&
                    !processController.binarySupportsMtpSpeculative(binaryFile)
                ) {
                    val cpuBinaryFile = binaryRepo.getCpuExecutable()
                    if (cpuBinaryFile != null &&
                        cpuBinaryFile.absolutePath != binaryFile.absolutePath &&
                        processController.binarySupportsMtpSpeculative(cpuBinaryFile)
                    ) {
                        val warning = getString(
                            R.string.llama_server_mtp_cpu_fallback,
                            binaryFile.name,
                            cpuBinaryFile.name
                        )
                        DebugLog.log("LlamaService: $warning")
                        appendServerLogForGeneration(generation, warning)
                        primaryBinaryFile = cpuBinaryFile
                    } else {
                        val warning = getString(R.string.llama_server_mtp_disabled, binaryFile.name)
                        DebugLog.log("LlamaService: $warning")
                        appendServerLogForGeneration(generation, warning)
                        launchConfig = config.copy(speculativeMode = null)
                    }
                }
                if (launchConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH &&
                    !processController.binarySupportsDflashSpeculative(primaryBinaryFile)
                ) {
                    val cpuBinaryFile = binaryRepo.getCpuExecutable()
                    if (cpuBinaryFile != null &&
                        cpuBinaryFile.absolutePath != primaryBinaryFile.absolutePath &&
                        processController.binarySupportsDflashSpeculative(cpuBinaryFile)
                    ) {
                        val warning = getString(
                            R.string.llama_server_dflash_cpu_fallback,
                            primaryBinaryFile.name,
                            cpuBinaryFile.name
                        )
                        DebugLog.log("LlamaService: $warning")
                        appendServerLogForGeneration(generation, warning)
                        primaryBinaryFile = cpuBinaryFile
                    } else {
                        throw IllegalStateException(
                            getString(R.string.llama_server_dflash_unsupported, primaryBinaryFile.name)
                        )
                    }
                }
                if (launchConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK &&
                    !processController.binarySupportsDsparkSpeculative(primaryBinaryFile)
                ) {
                    throw IllegalStateException(
                        getString(R.string.llama_server_dspark_unsupported, primaryBinaryFile.name)
                    )
                }
                if (launchConfig.rpcWorkers.isNotEmpty() &&
                    !processController.binarySupportsDistributedFit(primaryBinaryFile)
                ) {
                    throw IllegalStateException(
                        getString(R.string.llama_server_fit_unsupported, primaryBinaryFile.name)
                    )
                }

                fun buildCommandArgsFor(candidateBinary: String, candidateConfig: LlamaConfig = launchConfig): List<String> {
                    val effectiveConfig = candidateConfig.copy(
                        kvOffloadMode = effectiveKvOffloadModeForBinary(candidateBinary, candidateConfig.kvOffloadMode)
                    )
                    return if (commandTemplate.isNullOrBlank()) {
                        processController.getCommand(candidateBinary, effectiveConfig)
                    } else {
                        DebugLog.log("LlamaService: Rendering command template for ${if (isMasterProfile) "master" else "general"} profile")
                        processController.renderCommandTemplate(commandTemplate, candidateBinary, effectiveConfig)
                    }
                }

                val commandArgs = buildCommandArgsFor(primaryBinaryFile.absolutePath)
                val commandString = processController.buildCommandString(commandArgs)
                DistributedService.setLastCommand(commandString)

                if (previewMode) {
                    DebugLog.log("LlamaService: Preview Command: $commandString")
                    return@launch
                }

                val speculativeRunDao = AppDatabase.getDatabase(applicationContext).llamaSpeculativeRunDao()
                val speculativeRunId = launchConfig.speculativeMode?.let { mode ->
                    LlamaSpeculativeRunStore.createRunAndPrune(
                        dao = speculativeRunDao,
                        modelPath = launchConfig.modelPath,
                        speculativeMode = mode,
                        draftModelPath = launchConfig.draftModelPath
                    )
                }
                
                DebugLog.log("LlamaService: Starting on port ${config.port}")
                
                // Show loading state while model loads
                updateStateForGeneration(generation, ServerState.Loading(0f, "Loading model..."))
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

                fun openClMtpDiagnosticLog(line: String): Int? {
                    val lower = line.lowercase()
                    return when {
                        lower.contains("does not have support for op top_k") ->
                            R.string.llama_server_opencl_mtp_top_k_warning
                        lower.contains("failed to measure draft model memory") ||
                            lower.contains("failed to create llama_context from model") ->
                            R.string.llama_server_opencl_mtp_memory_probe_warning
                        lower.contains("failed to load") &&
                            lower.contains("is a directory") ->
                            R.string.llama_server_opencl_backend_path_warning
                        else -> null
                    }
                }

                suspend fun startCandidate(candidateFile: File, candidateConfig: LlamaConfig, args: List<String>): ProcessRunResult {
                    var backendUnavailable = false
                    var stoppedForAcceleratorBackendIssue = false
                    val reportedOpenClMtpDiagnostics = mutableSetOf<Int>()
                    val metricsCollector = candidateConfig.speculativeMode?.let { LlamaSpeculativeMetricsCollector() }
                    val result = processController.start(
                        candidateFile.absolutePath,
                        candidateConfig,
                        filesDir,
                        nativeToolsWorkspaceDir = nativeToolsWorkspaceDir,
                        customArgs = args,
                        runtimeGenerationId = Companion.runtimeGenerationId(),
                        onState = { updateStateForGeneration(generation, it) },
                        onClearServerLogs = { clearServerLogsForGeneration(generation) },
                        onServerLog = { appendServerLogForGeneration(generation, it) },
                        onOwnedProcessStarted = { pid, startTimeTicks ->
                            recordOwnedRuntimeForGeneration(generation, pid, candidateConfig.port, startTimeTicks)
                        },
                        onLog = { line ->
                            handleServerLog(line)
                            val speculativeModeForMetrics = candidateConfig.speculativeMode
                            val metrics = metricsCollector?.onLogLine(line)
                            if (speculativeModeForMetrics != null && metrics != null) {
                                val runId = speculativeRunId
                                serviceScope.launch {
                                    if (runId != null) {
                                        LlamaSpeculativeRunStore.recordPromptMetricsAndPrune(
                                            dao = speculativeRunDao,
                                            runId = runId,
                                            metrics = metrics
                                        )
                                    } else {
                                        DebugLog.log("LlamaService: Skipping speculative metrics without active run row for ${speculativeModeForMetrics.flagValue}")
                                    }
                                }
                            }
                            if (candidateConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH &&
                                line.contains("unknown model architecture: 'dflash-draft'")
                            ) {
                                DebugLog.log("LlamaService: DFlash draft GGUF architecture rejected by ${candidateFile.name}; stopping process")
                                appendServerLogForGeneration(generation, getString(R.string.llama_server_dflash_runtime_rejected, candidateFile.name))
                                processController.stop()
                            }
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
                            if (DeviceAcceleration.isAcceleratorBinary(candidateFile) &&
                                candidateConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP
                            ) {
                                openClMtpDiagnosticLog(line)?.let { warningRes ->
                                    if (reportedOpenClMtpDiagnostics.add(warningRes)) {
                                        val warning = getString(warningRes)
                                        DebugLog.log("LlamaService: $warning")
                                        appendServerLogForGeneration(generation, warning)
                                    }
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
                    val cpuBinaryFiles = binaryRepo.getCpuFallbackExecutables(
                        name = "llama_server",
                        excludingPath = primaryBinaryFile.absolutePath
                    )
                    if (cpuBinaryFiles.isEmpty()) return null
                    val fallbackStatus = getString(R.string.llama_server_cpu_fallback_retry)
                    updateStateForGeneration(generation, ServerState.Loading(0f, fallbackStatus))
                    updateNotification(fallbackStatus)
                    for (cpuBinaryFile in cpuBinaryFiles) {
                        DebugLog.log("LlamaService: $reason; retrying CPU fallback at ${cpuBinaryFile.absolutePath}")
                        val fallbackConfig = if (launchConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_MTP &&
                            !processController.binarySupportsMtpSpeculative(cpuBinaryFile)
                        ) {
                            val warning = getString(R.string.llama_server_mtp_disabled, cpuBinaryFile.name)
                            DebugLog.log("LlamaService: $warning")
                            appendServerLogForGeneration(generation, warning)
                            launchConfig.copy(speculativeMode = null)
                        } else if (launchConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH &&
                            !processController.binarySupportsDflashSpeculative(cpuBinaryFile)
                        ) {
                            val warning = getString(R.string.llama_server_dflash_unsupported, cpuBinaryFile.name)
                            DebugLog.log("LlamaService: $warning")
                            appendServerLogForGeneration(generation, warning)
                            continue
                        } else if (launchConfig.speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK &&
                            !processController.binarySupportsDsparkSpeculative(cpuBinaryFile)
                        ) {
                            val warning = getString(R.string.llama_server_dspark_unsupported, cpuBinaryFile.name)
                            DebugLog.log("LlamaService: $warning")
                            appendServerLogForGeneration(generation, warning)
                            continue
                        } else {
                            launchConfig
                        }
                        ensureServerPortAvailableOrThrow(fallbackConfig.host, fallbackConfig.port, "before CPU fallback")
                        val fallbackArgs = buildCommandArgsFor(cpuBinaryFile.absolutePath, fallbackConfig)
                        DistributedService.setLastCommand(processController.buildCommandString(fallbackArgs))
                        val fallbackResult = startCandidate(cpuBinaryFile, fallbackConfig, fallbackArgs)
                        if (fallbackResult.becameReady || fallbackResult.stoppedIntentionally) {
                            return fallbackResult
                        }
                    }
                    return null
                }

                val candidateFiles = if (DeviceAcceleration.isAcceleratorBinary(primaryBinaryFile)) {
                    (listOf(primaryBinaryFile) + binaryRepo.getAcceleratorBinaries("llama_server"))
                        .distinctBy { it.absolutePath }
                        .filter {
                            when (launchConfig.speculativeMode) {
                                LlamaSpeculativeMode.DRAFT_MTP -> processController.binarySupportsMtpSpeculative(it)
                                LlamaSpeculativeMode.DRAFT_DFLASH -> processController.binarySupportsDflashSpeculative(it)
                                LlamaSpeculativeMode.DRAFT_DSPARK -> processController.binarySupportsDsparkSpeculative(it)
                                else -> true
                            }
                        }
                } else {
                    listOf(primaryBinaryFile)
                }

                var attemptedAccelerator = false
                var attemptedI8mm = false
                var lastCandidateError: Throwable? = null
                var runResult: ProcessRunResult? = null

                for (candidateFile in candidateFiles) {
                    val isAccelerator = DeviceAcceleration.isAcceleratorBinary(candidateFile)
                    val isI8mm = candidateFile.name.contains("_i8mm")
                    attemptedAccelerator = attemptedAccelerator || isAccelerator
                    attemptedI8mm = attemptedI8mm || isI8mm
                    val candidateArgs = buildCommandArgsFor(candidateFile.absolutePath)
                    DistributedService.setLastCommand(processController.buildCommandString(candidateArgs))
                    if (candidateFile.absolutePath != binaryFile.absolutePath) {
                        DebugLog.log("LlamaService: Trying alternate llama-server candidate: ${candidateFile.absolutePath}")
                    }
                    ensureServerPortAvailableOrThrow(launchConfig.host, launchConfig.port, "before starting ${candidateFile.name}")

                    val candidateResult = try {
                        startCandidate(candidateFile, launchConfig, candidateArgs)
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
                        if (isAccelerator || isI8mm) {
                            DebugLog.log("LlamaService: Native candidate ${candidateFile.name} failed: ${e.message}")
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
                    attemptedI8mm &&
                    !processController.stoppedIntentionally
                ) {
                    val reason = when {
                        lastCandidateError?.message != null -> "i8mm failed: ${lastCandidateError?.message}"
                        currentRunResult?.nativeLinkerStartupFailure == true -> "i8mm native linker startup failure"
                        else -> "i8mm exited before the server became ready"
                    }
                    binaryRepo.quarantineI8mmForCurrentVersion(reason)
                    startCpuFallback(reason)?.let {
                        runResult = it
                    }
                }
                val runResultAfterI8mmFallback = runResult
                if ((runResultAfterI8mmFallback == null ||
                        (!runResultAfterI8mmFallback.stoppedIntentionally &&
                            !runResultAfterI8mmFallback.becameReady)) &&
                    attemptedAccelerator
                ) {
                    val reason = when {
                        lastCandidateError?.message != null -> "Snapdragon accelerator failed: ${lastCandidateError?.message}"
                        else -> "Snapdragon accelerator exited before the server became ready"
                    }
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
                    Companion.recordRecentStartupFailure()
                }
                stopServer(
                    finalError = finalRunResult.startupFailureMessage?.takeIf { !finalRunResult.becameReady },
                    generation = generation
                )
            } catch (e: Exception) {
                if (!isCurrentGeneration(generation)) return@launch
                // Only show error if not intentionally stopped
                if (!processController.stoppedIntentionally) {
                    DebugLog.log("LlamaService ERROR: ${e.message}")
                    updateStateForGeneration(generation, ServerState.Error(e.message ?: "Unknown error"))
                    Companion.recordRecentStartupFailure()
                } else {
                    DebugLog.log("LlamaService: Stopped by user")
                    updateStateForGeneration(generation, ServerState.Stopped)
                }
                stopServer(generation = generation)
            }
        }
    }
    
    private fun stopServer(
        finalError: String? = null,
        startId: Int? = null,
        generation: Long? = null
    ) {
        if (!isCurrentGeneration(generation)) {
            DebugLog.log("LlamaService: ignored stale cleanup generation=$generation")
            return
        }
        val portToClean = currentServerPort ?: 8080
        val openClWasActive = processController.activeProcessWasOpenCl()
        val ownedChildPid = processController.ownedChildPid()
        processController.stop()
        DebugLog.log(
            "LlamaService: owned native shutdown complete openCl=$openClWasActive " +
                "childPid=$ownedChildPid port=$portToClean"
        )
        val portReleased = checkServerPortBind("127.0.0.1", portToClean).available
        if (portReleased) {
            Companion.clearRecordedOwner(applicationContext)
        } else {
            DebugLog.log(
                "LlamaService: port $portToClean is still occupied after owned shutdown; " +
                    "preserving the exact owner record for Recover"
            )
        }
        Companion.clearServerLogs(lifecycleGeneration = generation)
        DistributedService.setInferenceRunning(false)
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.LLM, null)
        val effectiveError = finalError ?: if (!portReleased) {
            getString(R.string.llama_server_cleanup_port_busy, portToClean)
        } else {
            null
        }
        if (effectiveError != null) {
            updateStateForGeneration(generation, ServerState.Error(effectiveError))
        } else {
            updateStateForGeneration(generation, ServerState.Stopped)
        }
        WakeLockManager.release("LlamaService")
        WakeLockManager.releaseWifiLock("LlamaService")
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        currentServerPort = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) {
            stopSelfResult(startId)
        } else {
            stopSelf()
        }
        if (openClWasActive) {
            // OpenCL vendor runtimes can retain process-local state after the
            // child server exits. Restart only this isolated service process;
            // the main UI process and unrelated services remain untouched.
            DebugLog.log("LlamaService: OpenCL runtime shutdown completed; scheduling isolated runtime restart")
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    DebugLog.log("LlamaService: restarting isolated :llama_runtime process after OpenCL cleanup")
                    Process.killProcess(Process.myPid())
                }.onFailure { DebugLog.log("LlamaService: isolated runtime restart failed: ${it.message}") }
            }, OPENCL_RUNTIME_RESTART_DELAY_MS)
            processController.clearActiveBinaryMarker()
        }
    }

    override fun onDestroy() {
        val recordedOwner = LlamaRuntimeOwnerStore.load(applicationContext)
        val portToClean = currentServerPort ?: recordedOwner?.port ?: 8080
        val ownedChildPid = processController.ownedChildPid()
        processController.stop()
        DebugLog.log(
            "LlamaService: destroy cleaned only owned native tree childPid=$ownedChildPid port=$portToClean"
        )
        if (checkServerPortBind("127.0.0.1", portToClean).available) {
            Companion.clearRecordedOwner(applicationContext)
        } else {
            DebugLog.log(
                "LlamaService: destroy left port $portToClean occupied; preserving the exact owner record"
            )
        }
        Companion.clearServerLogs(lifecycleGeneration = lifecycleGeneration)
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
        var lastFailure: String? = null
        while (System.currentTimeMillis() < deadline) {
            val check = checkServerPortBind(host, port)
            if (check.available) {
                if (loggedWait) DebugLog.log("LlamaService: Port $port is free after $reason")
                return true
            }
            lastFailure = check.error
            if (!loggedWait) {
                loggedWait = true
                DebugLog.log("LlamaService: Waiting for port $port to be released $reason")
            }
            delay(150L)
        }
        DebugLog.log("LlamaService: Port $port is still busy after waiting $reason; bindFailure=${lastFailure ?: "unknown"}")
        return false
    }

    private suspend fun ensureServerPortAvailableOrThrow(host: String, port: Int, reason: String) {
        if (checkServerPortBind(host, port).available) return
        if (NativeProcessCleanup.hasSameUidPortListenerSync(port)) {
            val visibleOwner = NativeProcessCleanup.describeSameUidPortOccupationSync(port)
            val message = getString(
                R.string.llama_server_port_busy_with_owner,
                port,
                host,
                visibleOwner.ifBlank { "another app runtime" }
            )
            DebugLog.log("LlamaService: refusing to clean a live app-owned listener: $message")
            throw IllegalStateException(message)
        }
        DebugLog.log("LlamaService: Port $port busy $reason; checking for stale app-owned llama-server processes")
        val cleaned = NativeProcessCleanup.cleanupSameUidLlamaServers(reason, port = port)
        if (cleaned > 0) {
            DebugLog.log("LlamaService: Requested cleanup for $cleaned stale llama-server process(es)")
        }
        if (!waitForServerPortAvailable(host, port, reason, timeoutMs = 8_000L)) {
            val bindFailure = checkServerPortBind(host, port).error ?: "unknown"
            val visibleOwner = NativeProcessCleanup.describeSameUidPortOccupationSync(port)
            val message = if (visibleOwner.isNotBlank()) {
                getString(R.string.llama_server_port_busy_with_owner, port, host, visibleOwner)
            } else {
                getString(R.string.llama_server_port_busy_no_owner, port, host, bindFailure)
            }
            DebugLog.log("LlamaService: $message")
            throw IllegalStateException(message)
        }
    }

    private fun canBindServerPort(host: String, port: Int): Boolean =
        checkServerPortBind(host, port).available

    private data class PortBindCheck(
        val available: Boolean,
        val error: String? = null
    )

    private fun checkServerPortBind(host: String, port: Int): PortBindCheck =
        runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
            }
            PortBindCheck(available = true)
        }.getOrElse { error ->
            PortBindCheck(
                available = false,
                error = "${error.javaClass.simpleName}: ${error.message ?: "bind failed"}"
            )
        }
    
    private fun handlePreLaunchStartFailure(
        message: String,
        previewMode: Boolean,
        generation: Long? = null
    ) {
        if (!isCurrentGeneration(generation)) {
            DebugLog.log("LlamaService: ignored stale pre-launch failure generation=$generation")
            return
        }
        DebugLog.log("LlamaService ERROR: $message")
        appendServerLogForGeneration(generation, message)
        if (previewMode) return

        DistributedService.setInferenceRunning(false)
        DeviceAcceleration.reportActiveBinary(AccelerationWorkload.LLM, null)
        updateStateForGeneration(generation, ServerState.Error(message))
        Companion.recordRecentStartupFailure()
        WakeLockManager.release("LlamaService")
        WakeLockManager.releaseWifiLock("LlamaService")
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        currentServerPort = null
        stopSelf()
    }

    private fun validateDflashDraftArchitecture(draftModelPath: String, generation: Long? = null): String? {
        val architecture = GGUFParser.parse(draftModelPath)?.architecture?.trim()?.lowercase()
        return when {
            architecture.isNullOrBlank() || architecture == "unknown" -> {
                DebugLog.log("LlamaService: Could not read DFlash draft GGUF architecture for $draftModelPath; launch will let llama-server validate it")
                null
            }
            architecture == "dflash-draft" -> {
                getString(
                    R.string.llama_server_dflash_draft_arch_unsupported,
                    File(draftModelPath).name,
                    architecture
                )
            }
            architecture != "dflash" -> {
                val warning = getString(
                    R.string.llama_server_dflash_draft_arch_warning,
                    File(draftModelPath).name,
                    architecture
                )
                DebugLog.log("LlamaService: $warning")
                appendServerLogForGeneration(generation, warning)
                null
            }
            else -> null
        }
    }

    private fun updateNotification(content: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, content)
        }
    }

    companion object {
        private const val OPENCL_RUNTIME_RESTART_DELAY_MS = 250L
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_SWITCH_MODEL = "SWITCH_MODEL"
        const val ACTION_RECONFIGURE = "RECONFIGURE"
        const val ACTION_RECOVER = "RECOVER"
        const val ACTION_PREVIEW_COMMAND = "PREVIEW_COMMAND"
        const val EXTRA_MODEL_PATH = "MODEL_PATH"
        const val EXTRA_IS_EMBEDDING = "IS_EMBEDDING"
        const val EXTRA_MMPROJ_PATH = "MMPROJ_PATH"
        const val EXTRA_ALLOW_SETTINGS_MMPROJ = "ALLOW_SETTINGS_MMPROJ"
        // Optional settings overrides for distributed mode (to avoid modifying global settings)
        const val EXTRA_THREADS = "THREADS"
        const val EXTRA_BATCH_SIZE = "BATCH_SIZE"
        const val EXTRA_PHYSICAL_BATCH_SIZE = "PHYSICAL_BATCH_SIZE"
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
        const val EXTRA_DRAFT_THREADS = "DRAFT_THREADS"
        const val EXTRA_DRAFT_THREADS_BATCH = "DRAFT_THREADS_BATCH"
        const val EXTRA_CUSTOM_COMMAND = "CUSTOM_COMMAND"
        const val EXTRA_COMMAND_TEMPLATE = "COMMAND_TEMPLATE"
        const val EXTRA_LAUNCH_PROFILE_JSON = "LOCAL_LAUNCH_PROFILE_JSON"
        const val EXTRA_DISTRIBUTED_PROFILE_JSON = "DISTRIBUTED_LAUNCH_PROFILE_JSON"
        /** Explicit distributed state passed from the UI process into :llama_runtime. */
        const val EXTRA_RPC_WORKERS = "RPC_WORKERS"
        const val EXTRA_RPC_WORKER_RAM_MB = "RPC_WORKER_RAM_MB"
        const val EXTRA_MASTER_RAM_MB = "MASTER_RAM_MB"
        
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
        const val SETTINGS_PROFILE_OCR = "OCR"
        
        // Global state for simple observation
        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
        val state = _state.asStateFlow()
        @Volatile private var runtimeContext: android.content.Context? = null
        @Volatile private var runtimeGeneration: Long = 0L
        @Volatile private var recentStartupFailureAtMs: Long = 0L
        private const val RECENT_STARTUP_FAILURE_TTL_MS = 5L * 60L * 1000L
        
        fun updateState(newState: ServerState) {
            _state.value = newState
            runtimeContext?.let { context ->
                LlamaRuntimeStateProjection.publishState(context, runtimeGeneration, newState)
            }
        }

        internal fun mutableStateForProjection(): MutableStateFlow<ServerState> = _state

        internal fun attachRuntimeProcess(context: android.content.Context) {
            runtimeContext = context.applicationContext
            runtimeGeneration = LlamaRuntimeStateProjection.beginRuntimeGeneration(context)
            clearServerLogs()
        }

        internal fun runtimeGenerationId(): Long = runtimeGeneration

        internal fun recordOwnedRuntime(context: android.content.Context, record: LlamaRuntimeOwnerRecord) {
            if (!llamaRuntimeOwnerRecordIsValid(record)) return
            LlamaRuntimeOwnerStore.save(context, record)
            DebugLog.log(
                "LlamaService: recorded owned native child pid=${record.pid} " +
                    "port=${record.port} lifecycleGeneration=${record.lifecycleGeneration}"
            )
        }

        internal fun clearRecordedOwner(context: android.content.Context) {
            LlamaRuntimeOwnerStore.clear(context)
        }

        internal fun recoverRecordedOwner(context: android.content.Context): LlamaRuntimeOwnerRecovery {
            val record = LlamaRuntimeOwnerStore.load(context)
            if (record == null) {
                return LlamaRuntimeOwnerRecovery(
                    recordedPid = null,
                    recordedPort = null,
                    matchedRecordedOwner = false,
                    cleanedProcessCount = 0
                )
            }
            val cleaned = NativeProcessCleanup.cleanupRecordedLlamaProcessTreeSync(
                reason = "LlamaService recorded-owner recovery",
                rootPid = record.pid,
                expectedStartTimeTicks = record.processStartTimeTicks,
                expectedPort = record.port
            )
            return LlamaRuntimeOwnerRecovery(
                recordedPid = record.pid,
                recordedPort = record.port,
                matchedRecordedOwner = cleaned > 0,
                cleanedProcessCount = cleaned
            )
        }

        fun recordRecentStartupFailure(nowMs: Long = System.currentTimeMillis()) {
            recentStartupFailureAtMs = nowMs
            startupFailurePreferences()?.edit()?.putLong(KEY_RECENT_STARTUP_FAILURE_AT, nowMs)?.commit()
            runtimeContext?.let { LlamaRuntimeStateProjection.publishStartupFailure(it, runtimeGeneration, nowMs) }
        }

        fun clearRecentStartupFailure() {
            recentStartupFailureAtMs = 0L
            startupFailurePreferences()?.edit()?.remove(KEY_RECENT_STARTUP_FAILURE_AT)?.commit()
            runtimeContext?.let { LlamaRuntimeStateProjection.publishStartupFailure(it, runtimeGeneration, null) }
        }

        fun hasRecentStartupFailure(nowMs: Long = System.currentTimeMillis()): Boolean {
            val failedAt = startupFailurePreferences()
                ?.getLong(KEY_RECENT_STARTUP_FAILURE_AT, recentStartupFailureAtMs)
                ?: recentStartupFailureAtMs
            return failedAt > 0L && nowMs - failedAt <= RECENT_STARTUP_FAILURE_TTL_MS
        }

        private fun startupFailurePreferences(): android.content.SharedPreferences? {
            val context = runtimeContext ?: runCatching {
                com.example.llamadroid.LlamaApplication.instance.applicationContext
            }.getOrNull()
            return context?.getSharedPreferences(
                STARTUP_FAILURE_PREFS,
                android.content.Context.MODE_PRIVATE
            )
        }

        internal fun applyProjectedStartupFailure(timestampMs: Long?) {
            recentStartupFailureAtMs = timestampMs ?: 0L
            startupFailurePreferences()?.edit()?.apply {
                if (timestampMs == null) remove(KEY_RECENT_STARTUP_FAILURE_AT) else putLong(KEY_RECENT_STARTUP_FAILURE_AT, timestampMs)
            }?.commit()
        }

        private const val SERVER_LOG_FLUSH_INTERVAL_MS = 100L
        private const val STARTUP_FAILURE_PREFS = "llama_runtime_startup_failure"
        private const val KEY_RECENT_STARTUP_FAILURE_AT = "recent_startup_failure_at"
        private val _serverLogs = MutableStateFlow<List<com.example.llamadroid.util.LogEntry>>(emptyList())
        val serverLogs = _serverLogs.asStateFlow()
        private val serverLogBufferLock = Any()
        private val serverLogBuffer = LlamaServerLogBuffer()
        private val serverLogFlushScheduled = AtomicBoolean(false)
        private val serverLogFlushHandler by lazy { Handler(Looper.getMainLooper()) }
        private val serverLogFlushRunnable = Runnable { flushServerLogs() }

        /**
         * Native output can arrive a line at a time from a background reader. Stage it first so
         * neither Compose nor the cross-process projection receives an unbounded write stream.
         */
        fun addServerLog(message: String, lifecycleGeneration: Long? = null) {
            val accepted = synchronized(serverLogBufferLock) {
                if (lifecycleGeneration == null) {
                    serverLogBuffer.appendCurrent(message)
                } else {
                    serverLogBuffer.append(lifecycleGeneration, message)
                }
            }
            if (accepted) scheduleServerLogFlush()
        }

        fun clearServerLogs(lifecycleGeneration: Long? = null) {
            val generation = lifecycleGeneration ?: runtimeGeneration
            synchronized(serverLogBufferLock) {
                serverLogBuffer.reset(generation)
            }
            serverLogFlushHandler.removeCallbacks(serverLogFlushRunnable)
            serverLogFlushScheduled.set(false)
            _serverLogs.value = emptyList()
            runtimeContext?.let { context ->
                LlamaRuntimeStateProjection.publishClearLogs(context, runtimeGeneration)
            }
        }

        private fun scheduleServerLogFlush() {
            if (serverLogFlushScheduled.compareAndSet(false, true)) {
                serverLogFlushHandler.postDelayed(serverLogFlushRunnable, SERVER_LOG_FLUSH_INTERVAL_MS)
            }
        }

        private fun flushServerLogs() {
            val flush = synchronized(serverLogBufferLock) { serverLogBuffer.drain() }
            if (flush != null && synchronized(serverLogBufferLock) { serverLogBuffer.isCurrent(flush) }) {
                _serverLogs.value = flush.tail.map { line ->
                    com.example.llamadroid.util.LogEntry(System.currentTimeMillis(), line)
                }
                runtimeContext?.let { context ->
                    LlamaRuntimeStateProjection.publishLogBatch(context, runtimeGeneration, flush.lines)
                }
            }

            val hasMore = synchronized(serverLogBufferLock) { serverLogBuffer.hasPending() }
            if (hasMore) {
                serverLogFlushHandler.postDelayed(serverLogFlushRunnable, SERVER_LOG_FLUSH_INTERVAL_MS)
            } else {
                // Clear the lease before checking again so an append racing this drain cannot
                // leave buffered diagnostics without a future projection callback.
                serverLogFlushScheduled.set(false)
                if (synchronized(serverLogBufferLock) { serverLogBuffer.hasPending() }) {
                    scheduleServerLogFlush()
                }
            }
        }

        internal fun mutableServerLogsForProjection(): MutableStateFlow<List<com.example.llamadroid.util.LogEntry>> = _serverLogs
    }
}
