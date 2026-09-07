package com.example.llamadroid.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.NativeProcessCleanup
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object DistributedLaunchResolution {
    suspend fun resolve(context: Context, profile: DistributedLlamaLaunchProfile): ResolvedDistributedLaunch {
        val controller = ProcessController()
        val binary = selectBinary(context, profile.config.speculativeMode, profile.config.fitEnabled)
        val effectiveConfig = profile.config
        val effectiveProfile = profile
        val customArgs = effectiveProfile.customCommand?.let(controller::splitCommandLine)
        val rawArgs = customArgs
            ?: effectiveProfile.commandTemplate?.let {
                controller.renderCommandTemplate(it, binary.absolutePath, effectiveConfig)
            }
            ?: controller.getCommand(binary.absolutePath, effectiveConfig)
        require(rawArgs.isNotEmpty()) { "The distributed command is empty" }
        if (customArgs != null) {
            require(runCatching { File(customArgs.first()).canonicalPath == binary.canonicalPath }.getOrDefault(false)) {
                context.getString(R.string.dist_custom_command_binary_error)
            }
        }
        val argv = if (customArgs != null) rawArgs else {
            rawArgs.toMutableList().also { it[0] = binary.absolutePath }.toList()
        }
        return ResolvedDistributedLaunch(
            profile = effectiveProfile,
            binaryPath = binary.absolutePath,
            argv = argv,
            endpointHost = effectiveConfig.host,
            endpointPort = effectiveConfig.port,
            workerDeviceOrder = effectiveConfig.rpcWorkers.mapIndexed { index, address ->
                address to "RPC$index"
            }.toMap()
        )
    }

    suspend fun selectBinary(
        context: Context,
        speculativeMode: LlamaSpeculativeMode?,
        fitEnabled: Boolean
    ): File {
        val binaryRepository = BinaryRepository(context.applicationContext)
        val controller = ProcessController()
        var binary = requireNotNull(binaryRepository.getExecutable()) { "llama-server binary not found" }
        if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP &&
            !controller.binarySupportsMtpSpeculative(binary)
        ) {
            binary = binaryRepository.getCpuExecutable()?.takeIf(controller::binarySupportsMtpSpeculative)
                ?: error(context.getString(R.string.llama_server_mtp_disabled, binary.name))
        }
        if (speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH &&
            !controller.binarySupportsDflashSpeculative(binary)
        ) {
            binary = binaryRepository.getCpuExecutable()?.takeIf(controller::binarySupportsDflashSpeculative)
                ?: error(context.getString(R.string.llama_server_dflash_unsupported, binary.name))
        }
        require(speculativeMode != LlamaSpeculativeMode.DRAFT_DSPARK ||
            controller.binarySupportsDsparkSpeculative(binary)
        ) { context.getString(R.string.llama_server_dspark_unsupported, binary.name) }
        require(!fitEnabled || controller.binarySupportsDistributedFit(binary)) {
            context.getString(R.string.llama_server_fit_unsupported, binary.name)
        }
        return binary
    }

}

data class RpcWorkerActivity(val address: String, val status: RpcWorkerStatus, val detail: String)

class RpcWorkerActivityTracker(private val deviceOrder: Map<String, String>) {
    fun consume(line: String): List<RpcWorkerActivity> {
        val lower = line.lowercase()
        val explicitFailure = listOf(
            "failed to connect", "connection refused", "connection reset", "rpc server crashed",
            "malformed response", "recv failed", "send failed", "rpc error"
        ).any(lower::contains)
        val success = listOf(
            "connected", "register", "alloc_buffer", "set_tensor", "copy tensor",
            "rpc backend", "model loaded", "listening on http://"
        ).any(lower::contains)
        if (!explicitFailure && !success) return emptyList()

        val matched = deviceOrder.filter { (address, device) ->
            line.contains(address, ignoreCase = true) ||
                Regex("\\b${Regex.escape(device)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }.keys
        val addresses = when {
            matched.isNotEmpty() -> matched
            lower.contains("model loaded") || lower.contains("listening on http://") -> deviceOrder.keys
            deviceOrder.size == 1 && lower.contains("rpc") -> deviceOrder.keys
            else -> emptySet()
        }
        val status = if (explicitFailure) RpcWorkerStatus.FAILED else RpcWorkerStatus.ONLINE
        return addresses.map { RpcWorkerActivity(it, status, line.take(180)) }
    }
}

object DistributedMasterRuntimeState {
    const val ACTION_EVENT = "com.manuxd32.aidoomsdaytoolbox.DISTRIBUTED_LLAMA_RUNTIME_EVENT"
    const val EXTRA_KIND = "kind"
    const val EXTRA_VALUE = "value"
    const val KIND_STATE = "state"
    const val KIND_LOG = "log"

    private val attached = AtomicBoolean(false)
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()
    @Volatile private var tracker = RpcWorkerActivityTracker(emptyMap())

    fun attach(context: Context) {
        if (!attached.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val value = intent?.getStringExtra(EXTRA_VALUE).orEmpty()
                when (intent?.getStringExtra(EXTRA_KIND)) {
                    KIND_STATE -> {
                        _state.value = decodeState(value)
                        if (_state.value is ServerState.Stopped || _state.value is ServerState.Error) {
                            DistributedService.stopMasterMode()
                        }
                    }
                    KIND_LOG -> {
                        _logs.value = (_logs.value + value).takeLast(500)
                        tracker.consume(value).forEach { activity ->
                            DistributedService.updateWorkerRpcStatus(
                                activity.address, activity.status, activity.detail
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_EVENT)
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun prepare(launch: ResolvedDistributedLaunch) {
        tracker = RpcWorkerActivityTracker(launch.workerDeviceOrder)
        launch.profile.workers.forEach { worker ->
            DistributedService.updateWorkerRpcStatus(worker.address, RpcWorkerStatus.CONNECTING)
        }
        _logs.value = emptyList()
    }

    private fun decodeState(value: String): ServerState = when {
        value == "stopped" -> ServerState.Stopped
        value == "starting" -> ServerState.Starting
        value.startsWith("loading:") -> ServerState.Loading(-1f, value.removePrefix("loading:"))
        value.startsWith("running:") -> ServerState.Running(value.substringAfter(':').toIntOrNull() ?: 8080)
        value.startsWith("error:") -> ServerState.Error(value.removePrefix("error:"))
        else -> ServerState.Error(value)
    }
}

class DistributedMasterLlamaService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = ProcessController()
    private var taskId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRuntime()
            ACTION_START -> {
                val launch = ResolvedDistributedLaunch.decode(intent.getStringExtra(EXTRA_RESOLVED_LAUNCH))
                if (launch == null) {
                    publishState("error:Invalid distributed launch profile")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                ensureForeground()
                scope.launch { runLaunch(launch, startId) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runLaunch(launch: ResolvedDistributedLaunch, startId: Int) {
        publishState("starting")
        WakeLockManager.acquire(applicationContext, "DistributedMasterLlamaService")
        WakeLockManager.acquireWifiLock(applicationContext, "DistributedMasterLlamaService")
        val runtimeDir = File(filesDir, "distributed_llama_runtime").apply { mkdirs() }
        runCatching {
            require(!NativeProcessCleanup.hasSameUidPortListenerSync(launch.endpointPort)) {
                getString(R.string.dist_port_conflicts_live_runtime, launch.endpointPort)
            }
            controller.start(
                binaryPath = launch.binaryPath,
                config = launch.profile.config,
                filesDir = filesDir,
                runtimeWorkingDir = runtimeDir,
                customArgs = launch.argv,
                runtimeGenerationId = System.currentTimeMillis(),
                onLog = { publishLog(it) },
                onState = { state -> publishState(encodeState(state)) },
                onReady = {
                    scope.launch {
                        while (isActive && controller.isAlive()) {
                            publishState("running:${launch.endpointPort}")
                            delay(5_000L)
                        }
                    }
                },
                onClearServerLogs = null,
                onServerLog = null
            )
        }.onFailure { error ->
            DebugLog.log("DistributedMasterLlamaService: ${error.message}")
            publishState("error:${error.message ?: "Distributed server failed"}")
        }
        releaseRuntime()
        stopSelf(startId)
    }

    private fun stopRuntime() {
        controller.stop()
        publishState("stopped")
        releaseRuntime()
        stopSelf()
    }

    private fun ensureForeground() {
        if (taskId != null) return
        val (id, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SERVER,
            getString(R.string.dist_master_mode)
        )
        taskId = id
        startForeground(id, notification)
    }

    private fun publishState(value: String) = publish(DistributedMasterRuntimeState.KIND_STATE, value)
    private fun publishLog(value: String) = publish(DistributedMasterRuntimeState.KIND_LOG, value)
    private fun publish(kind: String, value: String) {
        sendBroadcast(Intent(DistributedMasterRuntimeState.ACTION_EVENT).apply {
            setPackage(packageName)
            putExtra(DistributedMasterRuntimeState.EXTRA_KIND, kind)
            putExtra(DistributedMasterRuntimeState.EXTRA_VALUE, value)
        })
    }

    private fun encodeState(state: ServerState): String = when (state) {
        ServerState.Stopped -> "stopped"
        ServerState.Starting -> "starting"
        is ServerState.Loading -> "loading:${state.status}"
        is ServerState.Running -> "running:${state.port}"
        is ServerState.Error -> "error:${state.message}"
    }

    private fun releaseRuntime() {
        WakeLockManager.release("DistributedMasterLlamaService")
        WakeLockManager.releaseWifiLock("DistributedMasterLlamaService")
        taskId?.let(UnifiedNotificationManager::dismissTask)
        taskId = null
    }

    override fun onDestroy() {
        controller.stop()
        releaseRuntime()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.DISTRIBUTED_MASTER_START"
        const val ACTION_STOP = "com.example.llamadroid.DISTRIBUTED_MASTER_STOP"
        const val EXTRA_RESOLVED_LAUNCH = "resolved_distributed_launch"
    }
}
