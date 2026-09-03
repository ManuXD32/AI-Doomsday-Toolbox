package com.example.llamadroid.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

import java.net.NetworkInterface
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.service.UnifiedNotificationManager.TaskType
import com.example.llamadroid.LlamaApplication

/**
 * Distributed inference modes
 */
enum class DistributedMode {
    NONE,   // Not in distributed mode
    MASTER, // Hosting model and coordinating workers
    WORKER  // Providing compute resources to master
}

enum class DistributedSpeculativePlacement {
    LOCAL,
    /** The master runs only the local MTP drafter; target layers stay on RPC workers. */
    MASTER_DEDICATED,
    WORKER_SHARED,
    WORKER_DEDICATED
}

enum class RpcWorkerStatus { UNKNOWN, CONNECTING, ONLINE, FAILED, NOT_SELECTED }

/**
 * Information about a connected worker
 */
data class WorkerInfo(
    val ip: String,
    val port: Int,
    val deviceName: String,
    val availableRamMB: Int,
    val assignedLayers: IntRange? = null,
    val isConnected: Boolean = false,
    val isEnabled: Boolean = true,
    val savedWorkerId: Long? = null,  // Reference to persisted worker
    val assignedProportion: Float? = null,  // User-set proportion override (0.0-1.0)
    val realRamUsageMB: Float? = null, // Real RAM usage from server logs
    val rpcStatus: RpcWorkerStatus = RpcWorkerStatus.UNKNOWN,
    val lastConfirmedAtMs: Long? = null,
    val statusDetail: String? = null
)

/**
 * Layer distribution across devices
 */
data class LayerDistribution(
    val totalLayers: Int,
    val modelSizeMB: Long,
    val assignments: Map<String, IntRange> // device identifier -> layer range
)

/**
 * Service for distributed LLM inference using llama.cpp RPC
 * 
 * In WORKER mode: Runs rpc-server binary to provide compute resources
 * In MASTER mode: Coordinates with LlamaService to connect to workers
 */
class DistributedService : Service() {

    companion object {
        private const val TAG = "DistributedService"
        const val RPC_DEFAULT_PORT = 50052
        
        const val ACTION_START_WORKER = "com.example.llamadroid.START_WORKER"
        const val ACTION_STOP_WORKER = "com.example.llamadroid.STOP_WORKER"
        const val EXTRA_PORT = "port"
        const val EXTRA_RAM_MB = "ram_mb"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CACHE = "cache"
        private const val WORKER_MEMORY_PREFS = "distributed_worker_profile"
        private const val WORKER_MEMORY_KEY = "ram_mb"
        private const val DEFAULT_WORKER_RAM_MB = 4096
        
        // State exposed to UI
        private val _mode = MutableStateFlow(DistributedMode.NONE)
        val mode: StateFlow<DistributedMode> = _mode.asStateFlow()
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        internal val _workers = MutableStateFlow<List<WorkerInfo>>(emptyList())
        val workers: StateFlow<List<WorkerInfo>> = _workers.asStateFlow()
        
        private val _workerPort = MutableStateFlow(RPC_DEFAULT_PORT)
        val workerPort: StateFlow<Int> = _workerPort.asStateFlow()
        
        private val _workerRamMB = MutableStateFlow(loadSavedWorkerRamMiB())
        val workerRamMB: StateFlow<Int> = _workerRamMB.asStateFlow()

        private val _workerMemoryBudget = MutableStateFlow(
            WorkerMemoryBudget.calculate(
                totalMiB = 0L,
                availableMiB = 0L,
                requestedMiB = _workerRamMB.value.toLong()
            )
        )
        /** Latest sanitized worker-memory snapshot, including the actual applied contribution. */
        val workerMemoryBudget: StateFlow<WorkerMemoryBudget> = _workerMemoryBudget.asStateFlow()
        
        private val _masterRamMB = MutableStateFlow(4096)
        val masterRamMB: StateFlow<Int> = _masterRamMB.asStateFlow()
        
        private val _localIp = MutableStateFlow<String?>(null)
        val localIp: StateFlow<String?> = _localIp.asStateFlow()
        
        // Connection count for worker mode - tracks how many masters have connected
        private val _connectionCount = MutableStateFlow(0)
        val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()
        
        // Increment connection count (called when rpc-server logs indicate connection)
        fun incrementConnectionCount() {
            _connectionCount.value++
            DebugLog.log("[DistributedService] Connection count: ${_connectionCount.value}")
        }

        fun updateWorkerRpcStatus(
            address: String,
            status: RpcWorkerStatus,
            detail: String? = null,
            confirmedAtMs: Long = System.currentTimeMillis()
        ) {
            _workers.value = _workers.value.map { worker ->
                if ("${worker.ip}:${worker.port}" != address) worker else worker.copy(
                    isConnected = status == RpcWorkerStatus.ONLINE,
                    rpcStatus = status,
                    lastConfirmedAtMs = if (status == RpcWorkerStatus.ONLINE) confirmedAtMs else worker.lastConfirmedAtMs,
                    statusDetail = detail
                )
            }
        }
        
        // Reset connection count
        fun resetConnectionCount() {
            _connectionCount.value = 0
        }
        
        // ==================== Network Visualization States ====================
        
        // Current model layer count (read from GGUF)
        private val _modelLayerCount = MutableStateFlow(0)
        val modelLayerCount: StateFlow<Int> = _modelLayerCount.asStateFlow()
        
        // Layers assigned to RPC workers
        private val _rpcLayerCount = MutableStateFlow(0)
        val rpcLayerCount: StateFlow<Int> = _rpcLayerCount.asStateFlow()
        
        // Model size in MB
        private val _modelSizeMB = MutableStateFlow(0L)
        val modelSizeMB: StateFlow<Long> = _modelSizeMB.asStateFlow()
        
        // Inference running status
        private val _inferenceRunning = MutableStateFlow(false)
        val inferenceRunning: StateFlow<Boolean> = _inferenceRunning.asStateFlow()
        
        // Transfer progress (0-100) for layer transfer to workers
        private val _transferProgress = MutableStateFlow(0)
        val transferProgress: StateFlow<Int> = _transferProgress.asStateFlow()
        
        // RPC debug logs (captured from rpc-server stdout)
        private const val MAX_RPC_LOGS = 1000
        private val _rpcLogs = MutableStateFlow<List<String>>(emptyList())
        val rpcLogs: StateFlow<List<String>> = _rpcLogs.asStateFlow()
        
        // Command preview state
        private val _lastCommand = MutableStateFlow<String?>(null)
        val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()
        
        // Custom user-edited command for the next run
        private val _customCommand = MutableStateFlow<String?>(null)
        val customCommand: StateFlow<String?> = _customCommand.asStateFlow()
        
        fun setLastCommand(cmd: String) {
            _lastCommand.value = cmd
            // LlamaService runs in :llama_runtime while the screen is in the main
            // process. Persist the preview so Show Command crosses that boundary.
            masterPrefs()?.edit()?.putString("last_command", cmd)?.commit()
        }

        fun clearLastCommand() {
            _lastCommand.value = null
            masterPrefs()?.edit()?.remove("last_command")?.commit()
        }

        fun refreshLastCommand() {
            _lastCommand.value = masterPrefs()?.getString("last_command", null)
        }
        
        fun setCustomCommand(cmd: String?) {
            _customCommand.value = cmd
            if (cmd != null) {
                DebugLog.log("[DistributedService] Custom command override enabled")
            } else {
                DebugLog.log("[DistributedService] Custom command cleared")
            }
        }
        
        // ==================== Remote Master Control ====================
        
        private val _remoteControlEnabled = MutableStateFlow(false)
        val remoteControlEnabled: StateFlow<Boolean> = _remoteControlEnabled.asStateFlow()
        
        private val _remoteControlPassword = MutableStateFlow("")
        val remoteControlPassword: StateFlow<String> = _remoteControlPassword.asStateFlow()
        
        private val _remoteControlPort = MutableStateFlow(8089)
        val remoteControlPort: StateFlow<Int> = _remoteControlPort.asStateFlow()
        
        private val _remoteControlWhitelist = MutableStateFlow<List<String>>(emptyList())
        val remoteControlWhitelist: StateFlow<List<String>> = _remoteControlWhitelist.asStateFlow()
        
        private val _remoteControlRunning = MutableStateFlow(false)
        val remoteControlRunning: StateFlow<Boolean> = _remoteControlRunning.asStateFlow()
        
        // Remote control logs
        private const val MAX_REMOTE_LOGS = 200
        private val _remoteControlLogs = MutableStateFlow<List<String>>(emptyList())
        val remoteControlLogs: StateFlow<List<String>> = _remoteControlLogs.asStateFlow()
        
        // Last run parameters (for restarting with same config after remote switch)
        val _lastRunParams = MutableStateFlow<Map<String, Any?>>(emptyMap())
        val lastRunParams: StateFlow<Map<String, Any?>> = _lastRunParams.asStateFlow()
        
        private var remoteServer: RemoteMasterServer? = null
        
        // Remote Master Client State
        private val _remoteClientIp = MutableStateFlow("")
        val remoteClientIp: StateFlow<String> = _remoteClientIp.asStateFlow()
        
        private val _remoteClientPassword = MutableStateFlow("")
        val remoteClientPassword: StateFlow<String> = _remoteClientPassword.asStateFlow()
        
        private val _remoteClientConnected = MutableStateFlow(false)
        val remoteClientConnected: StateFlow<Boolean> = _remoteClientConnected.asStateFlow()
        
        private val _remoteClientModelsStr = MutableStateFlow<String?>(null)
        val remoteClientModelsStr: StateFlow<String?> = _remoteClientModelsStr.asStateFlow()
        
        private val _remoteClientCurrentModel = MutableStateFlow<String?>(null)
        val remoteClientCurrentModel: StateFlow<String?> = _remoteClientCurrentModel.asStateFlow()
        
        private val _remoteClientStatusStr = MutableStateFlow<String?>(null)
        val remoteClientStatusStr: StateFlow<String?> = _remoteClientStatusStr.asStateFlow()
        
        private val _remoteClientSpecEnabled = MutableStateFlow(false)
        val remoteClientSpecEnabled: StateFlow<Boolean> = _remoteClientSpecEnabled.asStateFlow()

        private val _remoteLogsStr = MutableStateFlow<String?>(null)
        val remoteLogsStr: StateFlow<String?> = _remoteLogsStr.asStateFlow()

        fun setRemoteClientIp(ip: String) { _remoteClientIp.value = ip }
        fun setRemoteClientPassword(pwd: String) { _remoteClientPassword.value = pwd }
        fun setRemoteClientConnected(conn: Boolean) { _remoteClientConnected.value = conn }
        fun setRemoteClientModelsStr(models: String?) { _remoteClientModelsStr.value = models }
        fun setRemoteClientCurrentModel(model: String?) { _remoteClientCurrentModel.value = model }
        fun setRemoteClientStatusStr(status: String?) { _remoteClientStatusStr.value = status }
        fun setRemoteLogsStr(logs: String?) { _remoteLogsStr.value = logs }

        // ==================== Local Master UI Persistence ====================
        private val _masterSelectedModel = MutableStateFlow<ModelEntity?>(null)
        val masterSelectedModel: StateFlow<ModelEntity?> = _masterSelectedModel.asStateFlow()
        fun setMasterSelectedModel(model: ModelEntity?) { _masterSelectedModel.value = model }

        private val _masterContextSize = MutableStateFlow<Int>(4096)
        val masterContextSize: StateFlow<Int> = _masterContextSize.asStateFlow()
        fun setMasterContextSize(size: Int) { _masterContextSize.value = size }

        private val _masterServerPort = MutableStateFlow(
            masterPrefs()?.getInt("server_port", 8080) ?: 8080
        )
        val masterServerPort: StateFlow<Int> = _masterServerPort.asStateFlow()
        fun setMasterServerPort(port: Int) {
            if (port !in 1..65535) return
            _masterServerPort.value = port
            masterPrefs()?.edit()?.putInt("server_port", port)?.apply()
        }

        private val _masterContextSizeText = MutableStateFlow<String>("4096")
        val masterContextSizeText: StateFlow<String> = _masterContextSizeText.asStateFlow()
        fun setMasterContextSizeText(text: String) { _masterContextSizeText.value = text }

        private val _masterTemperature = MutableStateFlow<Float>(0.7f)
        val masterTemperature: StateFlow<Float> = _masterTemperature.asStateFlow()
        fun setMasterTemperature(temp: Float) { _masterTemperature.value = temp }

        private val _masterThreads = MutableStateFlow<Int>(4)
        val masterThreads: StateFlow<Int> = _masterThreads.asStateFlow()
        fun setMasterThreads(threads: Int) { _masterThreads.value = threads }

        private val _masterBatchSize = MutableStateFlow<Int>(512)
        val masterBatchSize: StateFlow<Int> = _masterBatchSize.asStateFlow()
        fun setMasterBatchSize(size: Int) { _masterBatchSize.value = size }

        private val _masterBatchSizeText = MutableStateFlow<String>("512")
        val masterBatchSizeText: StateFlow<String> = _masterBatchSizeText.asStateFlow()
        fun setMasterBatchSizeText(text: String) { _masterBatchSizeText.value = text }

        private val _masterKvCacheEnabled = MutableStateFlow<Boolean>(true)
        val masterKvCacheEnabled: StateFlow<Boolean> = _masterKvCacheEnabled.asStateFlow()
        fun setMasterKvCacheEnabled(enabled: Boolean) { _masterKvCacheEnabled.value = enabled }

        private val _masterKvCacheTypeK = MutableStateFlow<String>("f16")
        val masterKvCacheTypeK: StateFlow<String> = _masterKvCacheTypeK.asStateFlow()
        fun setMasterKvCacheTypeK(type: String) { _masterKvCacheTypeK.value = type }

        private val _masterKvCacheTypeV = MutableStateFlow<String>("f16")
        val masterKvCacheTypeV: StateFlow<String> = _masterKvCacheTypeV.asStateFlow()
        fun setMasterKvCacheTypeV(type: String) { _masterKvCacheTypeV.value = type }

        private val _masterKvCacheReuse = MutableStateFlow<Int>(0)
        val masterKvCacheReuse: StateFlow<Int> = _masterKvCacheReuse.asStateFlow()
        fun setMasterKvCacheReuse(reuse: Int) { _masterKvCacheReuse.value = reuse }

        private val _masterSpeculativeEnabled = MutableStateFlow(
            masterPrefs()?.getBoolean("speculative_enabled", false) ?: false
        )
        val masterSpeculativeEnabled: StateFlow<Boolean> = _masterSpeculativeEnabled.asStateFlow()
        fun setMasterSpeculativeEnabled(enabled: Boolean) {
            _masterSpeculativeEnabled.value = enabled
            masterPrefs()?.edit()?.putBoolean("speculative_enabled", enabled)?.apply()
        }

        private fun masterPrefs() = runCatching {
            LlamaApplication.instance.getSharedPreferences("distributed_llm_profile", Context.MODE_PRIVATE)
        }.getOrNull()

        private val _masterSpeculativeMode = MutableStateFlow(
            LlamaSpeculativeMode.fromFlagValue(masterPrefs()?.getString("speculative_mode", null))
        )
        val masterSpeculativeMode: StateFlow<LlamaSpeculativeMode> = _masterSpeculativeMode.asStateFlow()
        fun setMasterSpeculativeMode(mode: LlamaSpeculativeMode) {
            val previousMode = _masterSpeculativeMode.value
            val crossesMtpBoundary =
                (previousMode == LlamaSpeculativeMode.DRAFT_MTP) !=
                    (mode == LlamaSpeculativeMode.DRAFT_MTP)
            if (crossesMtpBoundary) {
                // Standard draft and MTP selectors use disjoint model families.
                // Clear both in-memory and persisted state before switching.
                setMasterDraftModel(null)
            }
            _masterSpeculativeMode.value = mode
            masterPrefs()?.edit()?.putString("speculative_mode", mode.flagValue)?.apply()
        }

        private val _masterFitEnabled = MutableStateFlow(masterPrefs()?.getBoolean("fit_enabled", false) ?: false)
        val masterFitEnabled: StateFlow<Boolean> = _masterFitEnabled.asStateFlow()
        fun setMasterFitEnabled(enabled: Boolean) {
            _masterFitEnabled.value = enabled
            masterPrefs()?.edit()?.putBoolean("fit_enabled", enabled)?.apply()
        }

        private val _masterFitTargetMiB = MutableStateFlow(masterPrefs()?.getString("fit_target_mib", "7168") ?: "7168")
        val masterFitTargetMiB: StateFlow<String> = _masterFitTargetMiB.asStateFlow()
        fun setMasterFitTargetMiB(value: String) {
            _masterFitTargetMiB.value = value
            masterPrefs()?.edit()?.putString("fit_target_mib", value)?.apply()
        }

        private val _masterNglArgument = MutableStateFlow(masterPrefs()?.getString("ngl_argument", "auto") ?: "auto")
        val masterNglArgument: StateFlow<String> = _masterNglArgument.asStateFlow()
        fun setMasterNglArgument(value: String) {
            _masterNglArgument.value = value
            masterPrefs()?.edit()?.putString("ngl_argument", value)?.apply()
        }

        private val _masterDraftDeviceId = MutableStateFlow(masterPrefs()?.getString("draft_device_id", null))
        val masterDraftDeviceId: StateFlow<String?> = _masterDraftDeviceId.asStateFlow()
        fun setMasterDraftDeviceId(value: String?) {
            _masterDraftDeviceId.value = value
            masterPrefs()?.edit()?.putString("draft_device_id", value)?.apply()
        }

        private val _masterDraftGpuLayers = MutableStateFlow(masterPrefs()?.getString("draft_gpu_layers", "all") ?: "all")
        val masterDraftGpuLayers: StateFlow<String> = _masterDraftGpuLayers.asStateFlow()
        fun setMasterDraftGpuLayers(value: String) {
            _masterDraftGpuLayers.value = value
            masterPrefs()?.edit()?.putString("draft_gpu_layers", value)?.apply()
        }

        private val _masterSpeculativePlacement = MutableStateFlow(
            runCatching {
                DistributedSpeculativePlacement.valueOf(
                    masterPrefs()?.getString("speculative_placement", DistributedSpeculativePlacement.LOCAL.name)
                        ?: DistributedSpeculativePlacement.LOCAL.name
                )
            }.getOrDefault(DistributedSpeculativePlacement.LOCAL)
        )
        val masterSpeculativePlacement: StateFlow<DistributedSpeculativePlacement> = _masterSpeculativePlacement.asStateFlow()
        fun setMasterSpeculativePlacement(value: DistributedSpeculativePlacement) {
            _masterSpeculativePlacement.value = value
            masterPrefs()?.edit()?.putString("speculative_placement", value.name)?.apply()
        }

        private val _masterSpeculativeWorkerIndex = MutableStateFlow(
            masterPrefs()?.getInt("speculative_worker_index", -1)?.takeIf { it >= 0 }
        )
        val masterSpeculativeWorkerIndex: StateFlow<Int?> = _masterSpeculativeWorkerIndex.asStateFlow()
        fun setMasterSpeculativeWorkerIndex(value: Int?) {
            _masterSpeculativeWorkerIndex.value = value
            val editor = masterPrefs()?.edit() ?: return
            if (value == null) editor.remove("speculative_worker_index") else editor.putInt("speculative_worker_index", value)
            editor.apply()
        }

        /** Native llama.cpp names RPC devices in the same order as the --rpc list. */
        fun distributedDeviceSlots(rpcWorkers: List<String>): List<String> =
            rpcWorkers.mapIndexed { index, _ -> "RPC$index" }

        private val _masterDraftModel = MutableStateFlow<ModelEntity?>(null)
        val masterDraftModel: StateFlow<ModelEntity?> = _masterDraftModel.asStateFlow()
        private val _masterDraftModelPath = MutableStateFlow(masterPrefs()?.getString("draft_model_path", null))
        val masterDraftModelPath: StateFlow<String?> = _masterDraftModelPath.asStateFlow()
        fun setMasterDraftModel(model: ModelEntity?) {
            _masterDraftModel.value = model
            _masterDraftModelPath.value = model?.path
            val editor = masterPrefs()?.edit() ?: return
            val path = model?.path
            if (path.isNullOrBlank()) editor.remove("draft_model_path") else editor.putString("draft_model_path", path)
            editor.apply()
        }
        fun setMasterDraftModelPath(path: String?) {
            _masterDraftModelPath.value = path
            val editor = masterPrefs()?.edit() ?: return
            if (path.isNullOrBlank()) editor.remove("draft_model_path") else editor.putString("draft_model_path", path)
            editor.apply()
        }

        private val _masterDraftMax = MutableStateFlow(masterPrefs()?.getInt("draft_max", 3)?.coerceIn(1, 64) ?: 3)
        val masterDraftMax: StateFlow<Int> = _masterDraftMax.asStateFlow()
        fun setMasterDraftMax(max: Int) {
            val normalized = max.coerceIn(1, 64)
            _masterDraftMax.value = normalized
            masterPrefs()?.edit()?.putInt("draft_max", normalized)?.apply()
        }

        private val _masterDraftMaxText = MutableStateFlow(masterPrefs()?.getString("draft_max_text", _masterDraftMax.value.toString()) ?: _masterDraftMax.value.toString())
        val masterDraftMaxText: StateFlow<String> = _masterDraftMaxText.asStateFlow()
        fun setMasterDraftMaxText(text: String) {
            _masterDraftMaxText.value = text
            masterPrefs()?.edit()?.putString("draft_max_text", text)?.apply()
        }

        private val _masterDraftMin = MutableStateFlow(masterPrefs()?.getInt("draft_min", 0)?.coerceIn(0, 16) ?: 0)
        val masterDraftMin: StateFlow<Int> = _masterDraftMin.asStateFlow()
        fun setMasterDraftMin(min: Int) {
            val normalized = min.coerceIn(0, 16)
            _masterDraftMin.value = normalized
            masterPrefs()?.edit()?.putInt("draft_min", normalized)?.apply()
        }

        private val _masterDraftMinText = MutableStateFlow(masterPrefs()?.getString("draft_min_text", _masterDraftMin.value.toString()) ?: _masterDraftMin.value.toString())
        val masterDraftMinText: StateFlow<String> = _masterDraftMinText.asStateFlow()
        fun setMasterDraftMinText(text: String) {
            _masterDraftMinText.value = text
            masterPrefs()?.edit()?.putString("draft_min_text", text)?.apply()
        }

        private val _masterDraftPMin = MutableStateFlow(masterPrefs()?.getFloat("draft_p_min", 0.0f)?.coerceIn(0f, 1f) ?: 0.0f)
        val masterDraftPMin: StateFlow<Float> = _masterDraftPMin.asStateFlow()
        fun setMasterDraftPMin(pmin: Float) {
            val normalized = pmin.coerceIn(0f, 1f)
            _masterDraftPMin.value = normalized
            masterPrefs()?.edit()?.putFloat("draft_p_min", normalized)?.apply()
        }

        private val _masterDraftPMinText = MutableStateFlow(masterPrefs()?.getString("draft_p_min_text", "0.00") ?: "0.00")
        val masterDraftPMinText: StateFlow<String> = _masterDraftPMinText.asStateFlow()
        fun setMasterDraftPMinText(text: String) {
            _masterDraftPMinText.value = text
            masterPrefs()?.edit()?.putString("draft_p_min_text", text)?.apply()
        }

        private val _masterDraftThreads = MutableStateFlow(masterPrefs()?.getInt("draft_threads", 4)?.coerceIn(1, 16) ?: 4)
        val masterDraftThreads: StateFlow<Int> = _masterDraftThreads.asStateFlow()
        fun setMasterDraftThreads(threads: Int) {
            val normalized = threads.coerceIn(1, 16)
            _masterDraftThreads.value = normalized
            masterPrefs()?.edit()?.putInt("draft_threads", normalized)?.apply()
        }

        private val _masterDraftThreadsText = MutableStateFlow(masterPrefs()?.getString("draft_threads_text", _masterDraftThreads.value.toString()) ?: _masterDraftThreads.value.toString())
        val masterDraftThreadsText: StateFlow<String> = _masterDraftThreadsText.asStateFlow()
        fun setMasterDraftThreadsText(text: String) {
            _masterDraftThreadsText.value = text
            masterPrefs()?.edit()?.putString("draft_threads_text", text)?.apply()
        }

        private val _masterDraftThreadsBatch = MutableStateFlow(masterPrefs()?.getInt("draft_threads_batch", 4)?.coerceIn(1, 16) ?: 4)
        val masterDraftThreadsBatch: StateFlow<Int> = _masterDraftThreadsBatch.asStateFlow()
        fun setMasterDraftThreadsBatch(threads: Int) {
            val normalized = threads.coerceIn(1, 16)
            _masterDraftThreadsBatch.value = normalized
            masterPrefs()?.edit()?.putInt("draft_threads_batch", normalized)?.apply()
        }

        private val _masterDraftThreadsBatchText = MutableStateFlow(masterPrefs()?.getString("draft_threads_batch_text", _masterDraftThreadsBatch.value.toString()) ?: _masterDraftThreadsBatch.value.toString())
        val masterDraftThreadsBatchText: StateFlow<String> = _masterDraftThreadsBatchText.asStateFlow()
        fun setMasterDraftThreadsBatchText(text: String) {
            _masterDraftThreadsBatchText.value = text
            masterPrefs()?.edit()?.putString("draft_threads_batch_text", text)?.apply()
        }

        private val _masterDraftDeviceMode = MutableStateFlow(
            masterPrefs()?.getString("draft_device_mode", com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO)
                ?: com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO
        )
        val masterDraftDeviceMode: StateFlow<String> = _masterDraftDeviceMode.asStateFlow()
        fun setMasterDraftDeviceMode(value: String) {
            val normalized = when (value) {
                com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR,
                com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_CPU -> value
                else -> com.example.llamadroid.data.SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO
            }
            _masterDraftDeviceMode.value = normalized
            masterPrefs()?.edit()?.putString("draft_device_mode", normalized)?.apply()
        }

        // Mode-specific speculative settings are intentionally kept in the
        // distributed profile. They must not inherit or mutate general LLM
        // settings when the master launches an RPC server.
        private val _masterMtpUseDraftModel = MutableStateFlow(
            masterPrefs()?.getBoolean("mtp_use_draft_model", false) ?: false
        )
        val masterMtpUseDraftModel: StateFlow<Boolean> = _masterMtpUseDraftModel.asStateFlow()
        fun setMasterMtpUseDraftModel(enabled: Boolean) {
            _masterMtpUseDraftModel.value = enabled
            masterPrefs()?.edit()?.putBoolean("mtp_use_draft_model", enabled)?.apply()
        }

        private val _masterMtpDraftMax = MutableStateFlow(
            masterPrefs()?.getInt("mtp_draft_max", 3)?.coerceIn(1, 16) ?: 3
        )
        val masterMtpDraftMax: StateFlow<Int> = _masterMtpDraftMax.asStateFlow()
        fun setMasterMtpDraftMax(value: Int) {
            val normalized = value.coerceIn(1, 16)
            _masterMtpDraftMax.value = normalized
            masterPrefs()?.edit()?.putInt("mtp_draft_max", normalized)?.apply()
        }

        private val _masterMtpDraftMin = MutableStateFlow(
            masterPrefs()?.getInt("mtp_draft_min", 0)?.coerceAtLeast(0) ?: 0
        )
        val masterMtpDraftMin: StateFlow<Int> = _masterMtpDraftMin.asStateFlow()
        fun setMasterMtpDraftMin(value: Int) {
            val normalized = value.coerceAtLeast(0)
            _masterMtpDraftMin.value = normalized
            masterPrefs()?.edit()?.putInt("mtp_draft_min", normalized)?.apply()
        }

        private val _masterMtpDraftPMin = MutableStateFlow(
            masterPrefs()?.getFloat("mtp_draft_p_min", 0f)?.coerceIn(0f, 1f) ?: 0f
        )
        val masterMtpDraftPMin: StateFlow<Float> = _masterMtpDraftPMin.asStateFlow()
        fun setMasterMtpDraftPMin(value: Float) {
            val normalized = value.coerceIn(0f, 1f)
            _masterMtpDraftPMin.value = normalized
            masterPrefs()?.edit()?.putFloat("mtp_draft_p_min", normalized)?.apply()
        }

        private val _masterNgramModNMatch = MutableStateFlow(
            masterPrefs()?.getInt("ngram_mod_n_match", 24)?.coerceAtLeast(1) ?: 24
        )
        val masterNgramModNMatch: StateFlow<Int> = _masterNgramModNMatch.asStateFlow()
        fun setMasterNgramModNMatch(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramModNMatch.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_mod_n_match", normalized)?.apply()
        }

        private val _masterNgramModNMin = MutableStateFlow(
            masterPrefs()?.getInt("ngram_mod_n_min", 48)?.coerceAtLeast(1) ?: 48
        )
        val masterNgramModNMin: StateFlow<Int> = _masterNgramModNMin.asStateFlow()
        fun setMasterNgramModNMin(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramModNMin.value = normalized
            if (_masterNgramModNMax.value < normalized) setMasterNgramModNMax(normalized)
            masterPrefs()?.edit()?.putInt("ngram_mod_n_min", normalized)?.apply()
        }

        private val _masterNgramModNMax = MutableStateFlow(
            masterPrefs()?.getInt("ngram_mod_n_max", 64)?.coerceAtLeast(1) ?: 64
        )
        val masterNgramModNMax: StateFlow<Int> = _masterNgramModNMax.asStateFlow()
        fun setMasterNgramModNMax(value: Int) {
            val normalized = value.coerceAtLeast(_masterNgramModNMin.value.coerceAtLeast(1))
            _masterNgramModNMax.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_mod_n_max", normalized)?.apply()
        }

        private val _masterNgramSimpleSizeN = MutableStateFlow(
            masterPrefs()?.getInt("ngram_simple_size_n", 12)?.coerceAtLeast(1) ?: 12
        )
        val masterNgramSimpleSizeN: StateFlow<Int> = _masterNgramSimpleSizeN.asStateFlow()
        fun setMasterNgramSimpleSizeN(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramSimpleSizeN.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_simple_size_n", normalized)?.apply()
        }

        private val _masterNgramSimpleSizeM = MutableStateFlow(
            masterPrefs()?.getInt("ngram_simple_size_m", 48)?.coerceAtLeast(1) ?: 48
        )
        val masterNgramSimpleSizeM: StateFlow<Int> = _masterNgramSimpleSizeM.asStateFlow()
        fun setMasterNgramSimpleSizeM(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramSimpleSizeM.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_simple_size_m", normalized)?.apply()
        }

        private val _masterNgramSimpleMinHits = MutableStateFlow(
            masterPrefs()?.getInt("ngram_simple_min_hits", 1)?.coerceAtLeast(1) ?: 1
        )
        val masterNgramSimpleMinHits: StateFlow<Int> = _masterNgramSimpleMinHits.asStateFlow()
        fun setMasterNgramSimpleMinHits(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramSimpleMinHits.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_simple_min_hits", normalized)?.apply()
        }

        private val _masterNgramMapKSizeN = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k_size_n", 12)?.coerceAtLeast(1) ?: 12
        )
        val masterNgramMapKSizeN: StateFlow<Int> = _masterNgramMapKSizeN.asStateFlow()
        fun setMasterNgramMapKSizeN(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapKSizeN.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k_size_n", normalized)?.apply()
        }

        private val _masterNgramMapKSizeM = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k_size_m", 48)?.coerceAtLeast(1) ?: 48
        )
        val masterNgramMapKSizeM: StateFlow<Int> = _masterNgramMapKSizeM.asStateFlow()
        fun setMasterNgramMapKSizeM(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapKSizeM.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k_size_m", normalized)?.apply()
        }

        private val _masterNgramMapKMinHits = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k_min_hits", 1)?.coerceAtLeast(1) ?: 1
        )
        val masterNgramMapKMinHits: StateFlow<Int> = _masterNgramMapKMinHits.asStateFlow()
        fun setMasterNgramMapKMinHits(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapKMinHits.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k_min_hits", normalized)?.apply()
        }

        private val _masterNgramMapK4VSizeN = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k4v_size_n", 12)?.coerceAtLeast(1) ?: 12
        )
        val masterNgramMapK4VSizeN: StateFlow<Int> = _masterNgramMapK4VSizeN.asStateFlow()
        fun setMasterNgramMapK4VSizeN(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapK4VSizeN.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k4v_size_n", normalized)?.apply()
        }

        private val _masterNgramMapK4VSizeM = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k4v_size_m", 48)?.coerceAtLeast(1) ?: 48
        )
        val masterNgramMapK4VSizeM: StateFlow<Int> = _masterNgramMapK4VSizeM.asStateFlow()
        fun setMasterNgramMapK4VSizeM(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapK4VSizeM.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k4v_size_m", normalized)?.apply()
        }

        private val _masterNgramMapK4VMinHits = MutableStateFlow(
            masterPrefs()?.getInt("ngram_map_k4v_min_hits", 1)?.coerceAtLeast(1) ?: 1
        )
        val masterNgramMapK4VMinHits: StateFlow<Int> = _masterNgramMapK4VMinHits.asStateFlow()
        fun setMasterNgramMapK4VMinHits(value: Int) {
            val normalized = value.coerceAtLeast(1)
            _masterNgramMapK4VMinHits.value = normalized
            masterPrefs()?.edit()?.putInt("ngram_map_k4v_min_hits", normalized)?.apply()
        }
        
        // --- Advanced settings (Master) ---

        private val _masterParallel = MutableStateFlow<Int?>(null)
        val masterParallel: StateFlow<Int?> = _masterParallel.asStateFlow()
        fun setMasterParallel(p: Int?) { _masterParallel.value = p }

        private val _masterParallelText = MutableStateFlow<String>("")
        val masterParallelText: StateFlow<String> = _masterParallelText.asStateFlow()
        fun setMasterParallelText(t: String) { _masterParallelText.value = t }

        private val _masterCacheRam = MutableStateFlow<Int?>(null)
        val masterCacheRam: StateFlow<Int?> = _masterCacheRam.asStateFlow()
        fun setMasterCacheRam(r: Int?) { _masterCacheRam.value = r }

        private val _masterCacheRamText = MutableStateFlow<String>("")
        val masterCacheRamText: StateFlow<String> = _masterCacheRamText.asStateFlow()
        fun setMasterCacheRamText(t: String) { _masterCacheRamText.value = t }

        private val _masterCustomFlags = MutableStateFlow<String>("")
        val masterCustomFlags: StateFlow<String> = _masterCustomFlags.asStateFlow()
        fun setMasterCustomFlags(f: String) { _masterCustomFlags.value = f }

        private val _masterCommandTemplate = MutableStateFlow<String>("")
        val masterCommandTemplate: StateFlow<String> = _masterCommandTemplate.asStateFlow()
        fun setMasterCommandTemplate(template: String) { _masterCommandTemplate.value = template }

        private val _masterFlashAttention = MutableStateFlow<Boolean>(false)
        val masterFlashAttention: StateFlow<Boolean> = _masterFlashAttention.asStateFlow()
        fun setMasterFlashAttention(enabled: Boolean) { _masterFlashAttention.value = enabled }

        // ==================== Remote Master UI Persistence ====================
        
        private val _remoteUISelectedModel = MutableStateFlow<String?>(null)
        val remoteUISelectedModel: StateFlow<String?> = _remoteUISelectedModel.asStateFlow()
        fun setRemoteUISelectedModel(model: String?) { _remoteUISelectedModel.value = model }

        private val _remoteUIContextSize = MutableStateFlow<String>("4096")
        val remoteUIContextSize: StateFlow<String> = _remoteUIContextSize.asStateFlow()
        fun setRemoteUIContextSize(size: String) { _remoteUIContextSize.value = size }

        private val _remoteUITemperature = MutableStateFlow<String>("0.7")
        val remoteUITemperature: StateFlow<String> = _remoteUITemperature.asStateFlow()
        fun setRemoteUITemperature(temp: String) { _remoteUITemperature.value = temp }

        private val _remoteUIBatchSize = MutableStateFlow<String>("512")
        val remoteUIBatchSize: StateFlow<String> = _remoteUIBatchSize.asStateFlow()
        fun setRemoteUIBatchSize(size: String) { _remoteUIBatchSize.value = size }

        private val _remoteUIKvEnabled = MutableStateFlow<Boolean>(true)
        val remoteUIKvEnabled: StateFlow<Boolean> = _remoteUIKvEnabled.asStateFlow()
        fun setRemoteUIKvEnabled(enabled: Boolean) { _remoteUIKvEnabled.value = enabled }

        private val _remoteUIKvTypeK = MutableStateFlow<String>("f16")
        val remoteUIKvTypeK: StateFlow<String> = _remoteUIKvTypeK.asStateFlow()
        fun setRemoteUIKvTypeK(type: String) { _remoteUIKvTypeK.value = type }

        private val _remoteUIKvTypeV = MutableStateFlow<String>("f16")
        val remoteUIKvTypeV: StateFlow<String> = _remoteUIKvTypeV.asStateFlow()
        fun setRemoteUIKvTypeV(type: String) { _remoteUIKvTypeV.value = type }

        private val _remoteUIKvReuse = MutableStateFlow<String>("0")
        val remoteUIKvReuse: StateFlow<String> = _remoteUIKvReuse.asStateFlow()
        fun setRemoteUIKvReuse(reuse: String) { _remoteUIKvReuse.value = reuse }

        private val _remoteUISpecEnabled = MutableStateFlow<Boolean>(false)
        val remoteUISpecEnabled: StateFlow<Boolean> = _remoteUISpecEnabled.asStateFlow()
        fun setRemoteUISpecEnabled(enabled: Boolean) { _remoteUISpecEnabled.value = enabled }

        private val _remoteUIDraftModel = MutableStateFlow<String?>(null)
        val remoteUIDraftModel: StateFlow<String?> = _remoteUIDraftModel.asStateFlow()
        fun setRemoteUIDraftModel(model: String?) { _remoteUIDraftModel.value = model }

        private val _remoteUIDraftMin = MutableStateFlow<String>("0")
        val remoteUIDraftMin: StateFlow<String> = _remoteUIDraftMin.asStateFlow()
        fun setRemoteUIDraftMin(min: String) { _remoteUIDraftMin.value = min }

        private val _remoteUIDraftMax = MutableStateFlow<String>("3")
        val remoteUIDraftMax: StateFlow<String> = _remoteUIDraftMax.asStateFlow()
        fun setRemoteUIDraftMax(max: String) { _remoteUIDraftMax.value = max }

        private val _remoteUIDraftPMin = MutableStateFlow<String>("0.00")
        val remoteUIDraftPMin: StateFlow<String> = _remoteUIDraftPMin.asStateFlow()
        fun setRemoteUIDraftPMin(pmin: String) { _remoteUIDraftPMin.value = pmin }
        
        // --- Advanced settings (Remote) ---

        private val _remoteUIParallel = MutableStateFlow<String>("")
        val remoteUIParallel: StateFlow<String> = _remoteUIParallel.asStateFlow()
        fun setRemoteUIParallel(p: String) { _remoteUIParallel.value = p }

        private val _remoteUICacheRam = MutableStateFlow<String>("")
        val remoteUICacheRam: StateFlow<String> = _remoteUICacheRam.asStateFlow()
        fun setRemoteUICacheRam(r: String) { _remoteUICacheRam.value = r }

        private val _remoteUICustomFlags = MutableStateFlow<String>("")
        val remoteUICustomFlags: StateFlow<String> = _remoteUICustomFlags.asStateFlow()
        fun setRemoteUICustomFlags(f: String) { _remoteUICustomFlags.value = f }

        private val _remoteUIFlashAttention = MutableStateFlow<Boolean>(false)
        val remoteUIFlashAttention: StateFlow<Boolean> = _remoteUIFlashAttention.asStateFlow()
        fun setRemoteUIFlashAttention(enabled: Boolean) { _remoteUIFlashAttention.value = enabled }

        // Remote Download Tracker
        data class RemoteDownloadInfo(
            val filename: String,
            val progress: Float = 0f,
            val bytesDownloaded: Long = 0L,
            val totalBytes: Long = 0L,
            val speedBps: Long = 0L,
            val status: String = "downloading" // downloading, complete, error, cancelled
        )
        
        private val _remoteDownloads = MutableStateFlow<Map<String, RemoteDownloadInfo>>(emptyMap())
        val remoteDownloads: StateFlow<Map<String, RemoteDownloadInfo>> = _remoteDownloads.asStateFlow()
        
        fun updateRemoteDownload(filename: String, info: RemoteDownloadInfo) {
            _remoteDownloads.value = _remoteDownloads.value + (filename to info)
        }
        
        fun removeRemoteDownload(filename: String) {
            _remoteDownloads.value = _remoteDownloads.value - filename
        }
        
        fun getRemoteDownload(filename: String): RemoteDownloadInfo? {
            return _remoteDownloads.value[filename]
        }
        
        fun setRemoteControlEnabled(enabled: Boolean) {
            _remoteControlEnabled.value = enabled
        }
        
        fun setRemoteControlPassword(password: String) {
            _remoteControlPassword.value = password
        }
        
        fun setRemoteControlPort(port: Int) {
            _remoteControlPort.value = port
        }
        
        fun setRemoteControlWhitelist(ips: List<String>) {
            _remoteControlWhitelist.value = ips
        }
        
        fun startRemoteServer(context: Context) {
            val password = _remoteControlPassword.value
            if (password.isBlank()) {
                DebugLog.log("[DistributedService] Cannot start remote server: no password set")
                return
            }
            
            stopRemoteServer()
            
            remoteServer = RemoteMasterServer(
                context = context,
                port = _remoteControlPort.value,
                passwordHash = RemoteMasterServer.hashPassword(password),
                whitelist = _remoteControlWhitelist.value
            )
            remoteServer?.start()
            _remoteControlRunning.value = true
        }
        
        fun stopRemoteServer() {
            remoteServer?.stop()
            remoteServer = null
            _remoteControlRunning.value = false
        }
        
        fun addRemoteLog(line: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val newLine = "[$timestamp] $line"
            _remoteControlLogs.value = (_remoteControlLogs.value + newLine).takeLast(MAX_REMOTE_LOGS)
        }
        
        fun clearRemoteLogs() {
            _remoteControlLogs.value = emptyList()
        }
        
        fun setLastRunParams(params: Map<String, Any?>) {
            _lastRunParams.value = params
            DebugLog.log("[DistributedService] Last run params saved: ${params.keys}")
        }
        
        fun addRpcLog(line: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val newLine = "[$timestamp] $line"
            // Use thread-safe update or ensure main thread for UI state
            val current = _rpcLogs.value
            _rpcLogs.value = (current + newLine).takeLast(MAX_RPC_LOGS)
        }
        
        fun clearRpcLogs() {
            _rpcLogs.value = emptyList()
        }
        
        fun setModelInfo(layers: Int, sizeMB: Long, rpcLayers: Int) {
            _modelLayerCount.value = layers
            _modelSizeMB.value = sizeMB
            _rpcLayerCount.value = rpcLayers
            DebugLog.log("[DistributedService] Model info set: $layers layers, ${sizeMB}MB, $rpcLayers to RPC")
        }
        
        fun setInferenceRunning(running: Boolean) {
            _inferenceRunning.value = running
        }
        
        fun setTransferProgress(progress: Int) {
            _transferProgress.value = progress.coerceIn(0, 100)
        }
        
        // Helper to get worker RPC addresses for master
        fun getWorkerAddresses(): List<String> {
            return _workers.value
                .filter { it.isConnected }
                .map { "${it.ip}:${it.port}" }
        }
        
        // Helper to get ALL configured (enabled) worker RPC addresses for master launch
        // Used to avoid race condition where connection monitor hasn't verified workers yet
        fun getConfiguredWorkerAddresses(): List<String> {
            return _workers.value
                .filter { it.isEnabled }
                .map { "${it.ip}:${it.port}" }
        }
        
        // Add or update worker manually
        fun addWorkerManually(ip: String, port: Int = RPC_DEFAULT_PORT, deviceName: String = "Manual", ramMB: Int = 4096, proportion: Float? = null) {
            val current = _workers.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.ip == ip && it.port == port }
            
            if (existingIndex != -1) {
                // Update existing
                DebugLog.log("[$TAG] Updating worker: $deviceName at $ip:$port with proportion=${proportion?.let { "${(it*100).toInt()}%" } ?: "auto"}")
                current[existingIndex] = current[existingIndex].copy(
                    deviceName = deviceName,
                    availableRamMB = ramMB,
                    assignedProportion = proportion,
                    isEnabled = true
                )
            } else {
                // Add new - default isConnected to FALSE to prevent false positives until verified
                DebugLog.log("[$TAG] Manually added worker: $deviceName at $ip:$port with ${ramMB}MB RAM, proportion=${proportion?.let { "${(it*100).toInt()}%" } ?: "auto"}")
                current.add(WorkerInfo(ip, port, deviceName, ramMB, null, false, true, null, proportion))
            }
            _workers.value = current
        }
        
        // Remove worker
        fun removeWorker(ip: String, port: Int) {
            _workers.value = _workers.value.filterNot { it.ip == ip && it.port == port }
            DebugLog.log("[$TAG] Removed worker at $ip:$port")
        }
        
        // Clear all workers
        fun clearWorkers() {
            _workers.value = emptyList()
        }
        
        // Update worker real RAM usage from logs
        fun updateWorkerRealRam(ip: String, ramMB: Float) {
            val current = _workers.value.toMutableList()
            val index = current.indexOfFirst { it.ip == ip }
            if (index != -1) {
                if (current[index].realRamUsageMB != ramMB) {
                    DebugLog.log("[$TAG] Updating real RAM for $ip: ${ramMB}MB")
                    current[index] = current[index].copy(realRamUsageMB = ramMB)
                    _workers.value = current
                }
            }
        }
        
        // Set master mode with worker addresses (for use by LlamaService)
        fun setMasterMode(workerAddresses: List<String>) {
            if (_mode.value == DistributedMode.MASTER) return
            _mode.value = DistributedMode.MASTER
            DebugLog.log("[$TAG] Master mode set with ${workerAddresses.size} workers: $workerAddresses")
            
            // Acquire locks for Master as well (needs to keep connection open)
            WakeLockManager.acquire(LlamaApplication.instance, "DistributedServiceMaster")
            WakeLockManager.acquireWifiLock(LlamaApplication.instance, "DistributedServiceMaster")
            
            startMasterConnectionMonitor()
        }

        fun stopMasterMode() {
            if (_mode.value != DistributedMode.MASTER) return
            stopMasterConnectionMonitor()
            _mode.value = DistributedMode.NONE
            _inferenceRunning.value = false
            WakeLockManager.release("DistributedServiceMaster")
            WakeLockManager.releaseWifiLock("DistributedServiceMaster")
        }
        
        // Set master RAM allocation
        fun setMasterRam(ramMB: Int) {
            _masterRamMB.value = ramMB
        }
        
        /**
         * Returns a current device snapshot for the requested worker contribution.
         *
         * This method is intentionally additive so existing callers can keep using the legacy
         * worker setter/launcher signatures while the Worker screen can pass the same snapshot
         * through its controls.
         */
        fun calculateWorkerMemoryBudget(
            context: Context,
            requestedMiB: Long = _workerRamMB.value.toLong()
        ): WorkerMemoryBudget {
            val memoryManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (memoryManager == null) {
                return WorkerMemoryBudget.calculate(0L, 0L, requestedMiB)
            }
            val memoryInfo = ActivityManager.MemoryInfo()
            memoryManager.getMemoryInfo(memoryInfo)
            return WorkerMemoryBudget.fromBytes(
                totalBytes = memoryInfo.totalMem,
                availableBytes = memoryInfo.availMem,
                requestedMiB = requestedMiB
            )
        }

        /** Set worker RAM allocation using the latest known snapshot when no Context is available. */
        fun setWorkerRam(ramMB: Int) {
            if (_isRunning.value || _mode.value == DistributedMode.WORKER) return
            val budget = runCatching { LlamaApplication.instance }
                .getOrNull()
                ?.let { calculateWorkerMemoryBudget(it, ramMB.toLong()) }
                ?: _workerMemoryBudget.value.withRequested(ramMB.toLong())
            publishWorkerMemoryBudget(budget)
        }

        /**
         * Sanitizes and saves a stopped worker contribution against the supplied device snapshot.
         * Changes are ignored while the worker is active so an applied process budget cannot be
         * changed by a later UI recomposition.
         */
        fun setWorkerRam(context: Context, ramMB: Int): WorkerMemoryBudget {
            if (_isRunning.value || _mode.value == DistributedMode.WORKER) {
                return _workerMemoryBudget.value
            }
            val budget = calculateWorkerMemoryBudget(context, ramMB.toLong())
            publishWorkerMemoryBudget(budget)
            return budget
        }

        private fun publishWorkerMemoryBudget(budget: WorkerMemoryBudget) {
            val contributionMiB = budget.contributionMiB.coerceIn(0L, Int.MAX_VALUE.toLong())
            _workerMemoryBudget.value = budget.copy(contributionMiB = contributionMiB)
            _workerRamMB.value = contributionMiB.toInt()
            if (budget.totalMiB > 0L) {
                workerPrefs()?.edit()?.putInt(WORKER_MEMORY_KEY, contributionMiB.toInt())?.apply()
            }
        }

        private fun workerPrefs() = runCatching {
            LlamaApplication.instance.getSharedPreferences(WORKER_MEMORY_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()

        private fun loadSavedWorkerRamMiB(): Int =
            workerPrefs()?.getInt(WORKER_MEMORY_KEY, DEFAULT_WORKER_RAM_MB)?.coerceAtLeast(0)
                ?: DEFAULT_WORKER_RAM_MB
        
        // Static helper methods to start/stop via intents
        fun startWorker(context: Context, port: Int = RPC_DEFAULT_PORT, ramMB: Int = _workerRamMB.value, threads: Int = 4, enableCache: Boolean = false) {
            if (_isRunning.value || _mode.value == DistributedMode.WORKER) return
            val budget = calculateWorkerMemoryBudget(context, ramMB.toLong())
            publishWorkerMemoryBudget(budget)
            if (!budget.canLaunch) {
                addRpcLog(
                    context.getString(
                        com.example.llamadroid.R.string.dist_worker_memory_unavailable,
                        WorkerMemoryBudget.MINIMUM_VIABLE_MIB
                    )
                )
                return
            }
            val intent = Intent(context, DistributedService::class.java).apply {
                action = ACTION_START_WORKER
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_RAM_MB, budget.contributionMiB.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                putExtra(EXTRA_THREADS, threads)
                putExtra(EXTRA_CACHE, enableCache)
            }
            context.startService(intent)
        }

        fun clearWorkerCache(context: Context): Boolean {
            if (_isRunning.value) {
                addRpcLog(context.getString(com.example.llamadroid.R.string.dist_worker_cache_clear_running))
                return false
            }
            return runCatching {
                val root = workerCacheRoot(context)
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()
                addRpcLog(context.getString(com.example.llamadroid.R.string.dist_worker_cache_cleared))
                true
            }.getOrElse { error ->
                addRpcLog(
                    context.getString(
                        com.example.llamadroid.R.string.dist_worker_cache_clear_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
                false
            }
        }

        private fun workerCacheRoot(context: Context): File =
            File(context.filesDir, "llm_rpc_worker_cache").apply { mkdirs() }
        
        fun stopWorker(context: Context) {
            val intent = Intent(context, DistributedService::class.java).apply {
                action = ACTION_STOP_WORKER
            }
            context.startService(intent)
        }
        // Helper for NSD
        private var nsdHelper: com.example.llamadroid.util.NsdHelper? = null
        
        // Discovered services from NSD
        private val _discoveredServices = MutableStateFlow<List<android.net.nsd.NsdServiceInfo>>(emptyList())
        val discoveredServices: StateFlow<List<android.net.nsd.NsdServiceInfo>> = _discoveredServices.asStateFlow()
        
        private var nsdCollectionJob: kotlinx.coroutines.Job? = null
        
        private fun ensureNsdHelper(context: Context) {
            if (nsdHelper == null) {
                nsdHelper = com.example.llamadroid.util.NsdHelper(context.applicationContext)
                // Forward discovery events with a managed job
                nsdCollectionJob = CoroutineScope(Dispatchers.IO).launch {
                    nsdHelper!!.discoveredServices.collect { services ->
                        _discoveredServices.value = services
                    }
                }
            }
        }
        
        fun startDiscovery(context: Context) {
            ensureNsdHelper(context)
            nsdHelper?.startDiscovery()
        }
        
        fun stopDiscovery() {
            nsdHelper?.stopDiscovery()
            nsdCollectionJob?.cancel()
            nsdCollectionJob = null
        }
        
        // Connection Monitoring
        private var connectionMonitorJob: Job? = null

        private fun startMasterConnectionMonitor() {
            stopMasterConnectionMonitor()
            connectionMonitorJob = CoroutineScope(Dispatchers.IO).launch {
                DebugLog.log("[$TAG] Starting master connection monitor")
                while (_mode.value == DistributedMode.MASTER) {
                    val currentWorkersSnapshot = _workers.value
                    if (currentWorkersSnapshot.isEmpty()) {
                        delay(2000)
                        continue
                    }
                    
                    // Perform network checks (blocking, takes time)
                    // Map IP:Port -> IsOnline
                    val results = currentWorkersSnapshot.associate { worker ->
                        val isOnline = try {
                            // Primary Check: Heartbeat on Port + 1
                            val socket = Socket()
                            // Explicitly verify accessibility with short timeout
                            socket.connect(InetSocketAddress(worker.ip, worker.port + 1), 1500)
                            socket.close()
                            true
                        } catch (e: Exception) {
                            // DebugLog.log("[$TAG] Heartbeat failed for ${worker.ip}: ${e.message}")
                            // Fallback Check: RPC Port
                            try {
                                val socket = Socket()
                                socket.connect(InetSocketAddress(worker.ip, worker.port), 1500)
                                socket.close()
                                true
                            } catch (e2: Exception) {
                                // DebugLog.log("[$TAG] RPC fallback failed for ${worker.ip}: ${e2.message}")
                                false
                            }
                        }
                        "${worker.ip}:${worker.port}" to isOnline
                    }
                    
                    // Safe update: merge results into FRESH state to avoid race conditions
                    _workers.value = _workers.value.map { worker ->
                        val key = "${worker.ip}:${worker.port}"
                        if (results.containsKey(key)) {
                            val probedOnline = results[key]!!
                            // rpc-server can stop accepting auxiliary probe connections while it
                            // is loading or serving. A failed probe is absence of evidence, not an
                            // offline signal; explicit master RPC errors own FAILED transitions.
                            val newStatus = probedOnline || worker.rpcStatus == RpcWorkerStatus.ONLINE
                            if (worker.isConnected != newStatus) {
                                DebugLog.log("[$TAG] Worker ${worker.deviceName} (${worker.ip}) changed status to: ${if(newStatus) "ONLINE" else "OFFLINE"}")
                            }
                            if (probedOnline) worker.copy(
                                isConnected = true,
                                rpcStatus = RpcWorkerStatus.ONLINE,
                                lastConfirmedAtMs = System.currentTimeMillis(),
                                statusDetail = "Network probe confirmed"
                            ) else worker
                        } else {
                            worker // Worker was added while we were checking, keep as is
                        }
                    }
                    
                    delay(3000)
                }
                DebugLog.log("[$TAG] Master connection monitor stopped (mode changed)")
            }
        }

        fun stopMasterConnectionMonitor() {
            connectionMonitorJob?.cancel()
            connectionMonitorJob = null
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rpcProcess: Process? = null
    private var notificationId: Int? = null
    private var notificationJob: Job? = null
    private var heartbeatServerSocket: java.net.ServerSocket? = null
    private var heartbeatJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        updateLocalIp()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        
        when (action) {
            ACTION_START_WORKER -> {
                val port = intent.getIntExtra(EXTRA_PORT, RPC_DEFAULT_PORT)
                val ramMB = intent.getIntExtra(EXTRA_RAM_MB, _workerRamMB.value)
                val threads = intent.getIntExtra(EXTRA_THREADS, 4)
                val enableCache = intent.getBooleanExtra(EXTRA_CACHE, false)
                startWorkerMode(port, ramMB, threads, enableCache)
            }
            ACTION_STOP_WORKER -> {
                stopWorkerMode()
            }
            // Discovery actions are handled by static methods now, but we keep intent support just in case
            "START_DISCOVERY" -> startDiscovery(applicationContext)
            "STOP_DISCOVERY" -> stopDiscovery()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopWorkerMode()
        
        // Ensure locks are released
        WakeLockManager.release("DistributedService")
        WakeLockManager.releaseWifiLock("DistributedService")
        WakeLockManager.release("DistributedServiceMaster")
        WakeLockManager.releaseWifiLock("DistributedServiceMaster")
        
        stopDiscovery() // Ensure discovery is stopped
        serviceScope.cancel()
    }
    
    /**
     * Start worker mode - run rpc-server binary
     */
    private fun startWorkerMode(port: Int, ramMB: Int, threads: Int = 4, enableCache: Boolean = false) {
        // The watchdog restarts a crashed process while keeping WORKER mode set, so the
        // process-running flag is the launch guard here. Public setters still reject changes
        // whenever WORKER mode is active.
        if (_isRunning.value) {
            DebugLog.log("[$TAG] Worker already running")
            return
        }

        // Revalidate at the service boundary because available memory can change after the UI
        // snapshot was taken. The applied contribution is the value published to the UI and used
        // by the watchdog for any restart, while an active worker is never changed in place.
        val launchBudget = calculateWorkerMemoryBudget(applicationContext, ramMB.toLong())
        publishWorkerMemoryBudget(launchBudget)
        if (!launchBudget.canLaunch) {
            addRpcLog(
                getString(
                    com.example.llamadroid.R.string.dist_worker_memory_unavailable,
                    WorkerMemoryBudget.MINIMUM_VIABLE_MIB
                )
            )
            DebugLog.log(
                "[$TAG] Worker launch rejected: contribution=${launchBudget.contributionMiB}MiB " +
                    "maximum=${launchBudget.maximumMiB}MiB"
            )
            // A watchdog restart can arrive after available memory has dropped. Tear down the
            // stale worker state so the service does not remain stuck in WORKER mode without a
            // process; a normal stopped launch has nothing to tear down here.
            if (_mode.value == DistributedMode.WORKER) {
                stopWorkerMode()
            }
            return
        }
        val appliedRamMB = launchBudget.contributionMiB
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        
        // Register service via NSD
        val deviceName = try {
            android.provider.Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) { null } ?: android.os.Build.MODEL ?: "Unknown Device"
        
        // Use companion helper
        ensureNsdHelper(applicationContext)
        nsdHelper?.registerService(port, deviceName)
        
        ensureNsdHelper(applicationContext)
        nsdHelper?.registerService(port, deviceName)

        // Start foreground service with notification
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            TaskType.LLAMA_SERVER,
            "Distributed Worker"
        )
        notificationId = taskId
        startForeground(taskId, notification)

        // Start notification update loop
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            val systemMonitor = SystemMonitor(applicationContext)
            systemMonitor.observeStats().collect { stats ->
                val statusText = "RAM: ${String.format("%.1f", stats.freeRamGb)}GB Free / ${String.format("%.1f", stats.totalRamGb)}GB Total"
                UnifiedNotificationManager.updateProgress(taskId, -1f, statusText) // Indeterminate progress
            }
        }
        
        // Start Heartbeat Server (Sidecar)
        startHeartbeatServer(port + 1)
        
        serviceScope.launch {
            try {
                _mode.value = DistributedMode.WORKER
                _workerPort.value = port
                _workerRamMB.value = appliedRamMB
                
                // Acquire WakeLock to keep CPU running while serving as RPC worker
                WakeLockManager.acquire(applicationContext, "DistributedService")
                WakeLockManager.acquireWifiLock(applicationContext, "DistributedService")
                DebugLog.log("[$TAG] WakeLock & WifiLock acquired for RPC worker")
                
                // Extract correct tier binary
                val tier = CpuFeatures.getTier()
                val binaryName = "librpc-server_$tier.so"
                val binaryFile = extractBinary(binaryName)
                
                if (binaryFile == null || !binaryFile.exists()) {
                    DebugLog.log("[$TAG] Failed to extract rpc-server binary")
                    _mode.value = DistributedMode.NONE
                    return@launch
                }
                
                // Build command: rpc-server -H 0.0.0.0 -p <port> -t <threads> [-c]
                val command = mutableListOf(
                    binaryFile.absolutePath,
                    "-H", "0.0.0.0",
                    "-p", port.toString(),
                    "-t", threads.toString()
                )
                
                // Add cache flag if enabled
                if (enableCache) {
                    command.add("-c")
                }
                
                DebugLog.log("[$TAG] Starting RPC server: ${command.joinToString(" ")}")
                
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                processBuilder.environment()["LD_LIBRARY_PATH"] = binaryFile.parentFile?.absolutePath ?: ""
                
                // Set XDG_CACHE_HOME to a dedicated folder for RPC tensor caching with -c flag.
                val cacheDir = workerCacheRoot(applicationContext).absolutePath
                val filesDir = applicationContext.filesDir.absolutePath
                processBuilder.environment()["XDG_CACHE_HOME"] = cacheDir
                
                // Workaround for some devices
                processBuilder.environment()["HOME"] = filesDir
                processBuilder.environment()["TMPDIR"] = cacheDir
                processBuilder.environment()["PWD"] = filesDir
                processBuilder.environment()["XDG_DATA_HOME"] = filesDir
                processBuilder.environment()["XDG_CONFIG_HOME"] = filesDir
                
                processBuilder.environment()["GGML_RPC_DEBUG"] = "1"  // Enable debug logging
                
                // Set working directory
                processBuilder.directory(java.io.File(filesDir))
                
                rpcProcess = processBuilder.start()
                _isRunning.value = true
                
                // Reset connection count and clear old logs
                resetConnectionCount()
                clearRpcLogs()
                addRpcLog("=== RPC Server Started ===")
                addRpcLog("Command: ${command.joinToString(" ")}")
                
                // Start Watchdog
                launchProcessWatchdog(port, appliedRamMB, threads, enableCache)
                
                // Read output/detect connections
                val reader = BufferedReader(InputStreamReader(rpcProcess!!.inputStream))
                var line: String?
                // Improved regex for connection detection
                val connectedRegex = "accepted connection|new connection|client connected".toRegex(RegexOption.IGNORE_CASE)
                val disconnectedRegex = "connection closed|closing connection|client disconnected".toRegex(RegexOption.IGNORE_CASE)
                
                while (reader.readLine().also { line = it } != null) {
                    line?.let { output ->
                        addRpcLog(output)
                        
                        if (connectedRegex.containsMatchIn(output)) {
                            incrementConnectionCount()
                        }
                        if (disconnectedRegex.containsMatchIn(output) && _connectionCount.value > 0) {
                            _connectionCount.value--
                            DebugLog.log("[$TAG] Connection count decreased: ${_connectionCount.value}")
                        }
                    }
                }
                
                // Process ended naturally (or crashed)
                // Watchdog will handle unauthorized stops
                _isRunning.value = false
                
                
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Error starting worker: ${e.message}")
                _isRunning.value = false
                _mode.value = DistributedMode.NONE
            }
        }
    }
    
    /**
     * Monitor process and restart if needed
     */
    private fun launchProcessWatchdog(port: Int, ramMB: Int, threads: Int, enableCache: Boolean) {
        serviceScope.launch {
            var restartCount = 0
            var lastRestartTime = System.currentTimeMillis()
            
            while (_mode.value == DistributedMode.WORKER) {
                delay(5000)
                if (_mode.value == DistributedMode.WORKER && (rpcProcess == null || !rpcProcess!!.isAlive)) {
                    val now = System.currentTimeMillis()
                    // Reset counter if enough time has passed (30s)
                    if (now - lastRestartTime > 30_000) {
                        restartCount = 0
                    }
                    
                    restartCount++
                    if (restartCount > 3) {
                        DebugLog.log("[$TAG] Watchdog: Too many restarts ($restartCount), stopping")
                        addRpcLog("=== Too many crashes, stopping ===")
                        _isRunning.value = false
                        _mode.value = DistributedMode.NONE
                        break
                    }
                    
                    DebugLog.log("[$TAG] Watchdog: Process died! Restart attempt $restartCount/3")
                    addRpcLog("=== Process died, restart attempt $restartCount/3 ===")
                    _isRunning.value = false
                    delay(1000)
                    startWorkerMode(port, ramMB, threads, enableCache)
                    break
                }
            }
        }
    }
    
    /**
     * Stop worker mode
     */
    private fun stopWorkerMode() {
        _mode.value = DistributedMode.NONE // This signals watchdog to stop
        nsdHelper?.unregisterService()
        
        rpcProcess?.destroyForcibly()
        rpcProcess = null
        _isRunning.value = false
        
        // Release locks held while the RPC worker process is alive.
        WakeLockManager.release("DistributedService")
        WakeLockManager.releaseWifiLock("DistributedService")
        DebugLog.log("[$TAG] Worker stopped, WakeLock & WifiLock released")
        
        // Stop notification updates
        notificationJob?.cancel()
        notificationJob = null
        
        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationId?.let { 
            UnifiedNotificationManager.dismissTask(it) 
            notificationId = null
        }
        
        stopHeartbeatServer()
    }
    
    /**
     * Start lightweight heartbeat server for connection checks
     */
    private fun startHeartbeatServer(port: Int) {
        stopHeartbeatServer()
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            try {
                DebugLog.log("[$TAG] Starting Heartbeat Server on port $port")
                // Explicitly bind to 0.0.0.0 to ensure external visibility
                heartbeatServerSocket = java.net.ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"))
                while (_mode.value == DistributedMode.WORKER && heartbeatServerSocket != null && !heartbeatServerSocket!!.isClosed) {
                    try {
                        // Accept connection and immediately close it
                        // This serves as a "ping" confirmation
                        val client = heartbeatServerSocket!!.accept()
                        client.close()
                    } catch (e: Exception) {
                        if (_mode.value == DistributedMode.WORKER) {
                            DebugLog.log("[$TAG] Heartbeat accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Failed to start Heartbeat Server: ${e.message}")
            }
        }
    }
    
    private fun stopHeartbeatServer() {
        try {
            heartbeatServerSocket?.close()
        } catch (e: Exception) { }
        heartbeatServerSocket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Extract binary from jniLibs
     */
    private fun extractBinary(binaryName: String): File? {
        return try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val file = File(nativeLibDir, binaryName)
            if (file.exists() && file.canExecute()) {
                file
            } else {
                DebugLog.log("[$TAG] Binary not found or not executable: ${file.absolutePath}")
                null
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Error getting binary: ${e.message}")
            null
        }
    }
    
    private fun updateLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        _localIp.value = addr.hostAddress
                        return
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Error getting local IP: ${e.message}")
        }
    }
}
