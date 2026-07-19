package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class SdDistributedWorkerRuntime(
    val host: String,
    val port: Int,
    val deviceName: String,
    val ramMB: Int,
    val threads: Int,
    val backendDevice: String = "",
    val isConnected: Boolean = false,
    val assignedModules: List<String> = emptyList(),
    val rpcName: String = "",
    val plannedAssignments: List<SdDistributedWorkerAssignment> = emptyList(),
    val lastSeenAt: Long = 0L,
    val isLocalMaster: Boolean = false
)

data class SdDistributedWorkerAssignment(
    val module: String,
    val displayLabel: String,
    val isSplit: Boolean,
    val estimatedLayerShare: Int
)

class SdDistributedService : Service() {

    companion object {
        private const val TAG = "SdDistributedService"
        const val RPC_DEFAULT_PORT = 50062
        const val ACTION_START_WORKER = "com.example.llamadroid.sd.START_WORKER"
        const val ACTION_STOP_WORKER = "com.example.llamadroid.sd.STOP_WORKER"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RAM_MB = "ram_mb"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_BACKEND_DEVICE = "backend_device"
        const val EXTRA_CACHE_ENABLED = "cache_enabled"

        private const val MAX_LOG_LINES = 300
        private const val WORKER_PREFS = "sd_distributed_worker"
        private const val PREF_CACHE_ENABLED = "cache_enabled"

        private val _isWorkerRunning = MutableStateFlow(false)
        val isWorkerRunning: StateFlow<Boolean> = _isWorkerRunning.asStateFlow()

        private val _workerHost = MutableStateFlow("0.0.0.0")
        val workerHost: StateFlow<String> = _workerHost.asStateFlow()

        private val _workerPort = MutableStateFlow(RPC_DEFAULT_PORT)
        val workerPort: StateFlow<Int> = _workerPort.asStateFlow()

        private val _workerRamMB = MutableStateFlow(4096)
        val workerRamMB: StateFlow<Int> = _workerRamMB.asStateFlow()

        private val _workerThreads = MutableStateFlow(4)
        val workerThreads: StateFlow<Int> = _workerThreads.asStateFlow()

        private val _workerDeviceName = MutableStateFlow("Media Worker")
        val workerDeviceName: StateFlow<String> = _workerDeviceName.asStateFlow()

        private val _workerBackendDevice = MutableStateFlow("")
        val workerBackendDevice: StateFlow<String> = _workerBackendDevice.asStateFlow()

        private val _workerCacheEnabled = MutableStateFlow(true)
        val workerCacheEnabled: StateFlow<Boolean> = _workerCacheEnabled.asStateFlow()

        private val _localIp = MutableStateFlow<String?>(null)
        val localIp: StateFlow<String?> = _localIp.asStateFlow()

        private val _connectionCount = MutableStateFlow(0)
        val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        private val _runtimeConfig = MutableStateFlow(SdDistributedRuntimeConfig())
        val runtimeConfig: StateFlow<SdDistributedRuntimeConfig> = _runtimeConfig.asStateFlow()

        private val _activeWorkers = MutableStateFlow<List<SdDistributedWorkerRuntime>>(emptyList())
        val activeWorkers: StateFlow<List<SdDistributedWorkerRuntime>> = _activeWorkers.asStateFlow()

        private val _lastCommandPreview = MutableStateFlow("")
        val lastCommandPreview: StateFlow<String> = _lastCommandPreview.asStateFlow()

        private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var masterMonitorJob: Job? = null

        fun createStartWorkerIntent(
            context: Context,
            host: String,
            port: Int,
            ramMB: Int,
            threads: Int,
            deviceName: String,
            cacheEnabled: Boolean
        ): Intent = Intent(context, SdDistributedService::class.java).apply {
            action = ACTION_START_WORKER
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_RAM_MB, ramMB)
            putExtra(EXTRA_THREADS, threads)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_CACHE_ENABLED, cacheEnabled)
        }

        fun createStopWorkerIntent(context: Context): Intent =
            Intent(context, SdDistributedService::class.java).apply { action = ACTION_STOP_WORKER }

        fun startWorker(
            context: Context,
            host: String,
            port: Int,
            ramMB: Int,
            threads: Int,
            deviceName: String,
            cacheEnabled: Boolean
        ) {
            setWorkerCacheEnabled(context, cacheEnabled)
            ContextCompat.startForegroundService(
                context,
                createStartWorkerIntent(context, host, port, ramMB, threads, deviceName, cacheEnabled)
            )
        }

        fun stopWorker(context: Context) {
            context.startService(createStopWorkerIntent(context))
        }

        fun loadWorkerSettings(context: Context) {
            _workerCacheEnabled.value = context
                .getSharedPreferences(WORKER_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_CACHE_ENABLED, true)
        }

        fun setWorkerCacheEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(WORKER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CACHE_ENABLED, enabled)
                .apply()
            _workerCacheEnabled.value = enabled
        }

        fun clearWorkerCache(context: Context): Boolean {
            if (_isWorkerRunning.value) {
                addLog(context.getString(R.string.sd_dist_worker_cache_clear_running))
                return false
            }
            return runCatching {
                val root = workerCacheRoot(context)
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()
                addLog(context.getString(R.string.sd_dist_worker_cache_cleared))
                true
            }.getOrElse { error ->
                addLog(context.getString(R.string.sd_dist_worker_cache_clear_failed, error.message ?: error.javaClass.simpleName))
                false
            }
        }

        fun setRuntimeConfig(config: SdDistributedRuntimeConfig) {
            _runtimeConfig.value = config
            _lastCommandPreview.value = buildSdDistributedPreviewArgs(config).joinToString(" ")
        }

        fun setActiveWorkers(workers: List<SdDistributedWorkerRuntime>) {
            _activeWorkers.value = workers
            startMasterConnectionMonitor(workers)
        }

        private fun startMasterConnectionMonitor(workers: List<SdDistributedWorkerRuntime>) {
            masterMonitorJob?.cancel()
            if (workers.isEmpty()) return
            masterMonitorJob = monitorScope.launch {
                while (true) {
                    val current = _activeWorkers.value
                    val checked = current.map { worker ->
                        if (worker.isLocalMaster) {
                            return@map worker.copy(
                                isConnected = true,
                                lastSeenAt = System.currentTimeMillis()
                            )
                        }
                        val connected = canReach(worker.host, worker.port)
                        worker.copy(
                            isConnected = connected,
                            lastSeenAt = if (connected) System.currentTimeMillis() else worker.lastSeenAt
                        )
                    }
                    _activeWorkers.value = checked
                    delay(5000)
                }
            }
        }

        private fun canReach(host: String, port: Int): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 750)
                }
                true
            }.getOrDefault(false)

        private fun workerCacheRoot(context: Context): File =
            File(context.filesDir, "sd_rpc_worker_cache").apply { mkdirs() }

        private fun addLog(message: String) {
            val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $message"
            _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
            DebugLog.log("[$TAG] $message")
        }

        private fun detectLocalIp(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                    ?.hostAddress
            }.getOrNull()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rpcProcess: Process? = null
    private var notificationTaskId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WORKER -> {
                startWorkerMode(
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "0.0.0.0" },
                    port = intent.getIntExtra(EXTRA_PORT, RPC_DEFAULT_PORT),
                    ramMB = intent.getIntExtra(EXTRA_RAM_MB, 4096),
                    threads = intent.getIntExtra(EXTRA_THREADS, 4),
                    deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty().ifBlank {
                        getString(R.string.sd_dist_default_worker_name)
                    },
                    cacheEnabled = intent.getBooleanExtra(EXTRA_CACHE_ENABLED, true)
                )
            }
            ACTION_STOP_WORKER -> stopWorkerMode()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWorkerMode()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startWorkerMode(
        host: String,
        port: Int,
        ramMB: Int,
        threads: Int,
        deviceName: String,
        cacheEnabled: Boolean
    ) {
        if (_isWorkerRunning.value) {
            addLog(getString(R.string.sd_dist_log_worker_already_running))
            return
        }

        val bindHost = host.ifBlank { "0.0.0.0" }
        _workerHost.value = bindHost
        _workerPort.value = port
        _workerRamMB.value = ramMB
        _workerThreads.value = threads
        _workerDeviceName.value = deviceName
        _workerBackendDevice.value = ""
        _workerCacheEnabled.value = cacheEnabled
        _localIp.value = detectLocalIp()
        _connectionCount.value = 0

        startForegroundIfNeeded(getString(R.string.sd_dist_worker_notification_title))

        serviceScope.launch {
            try {
                WakeLockManager.acquire(applicationContext, TAG)
                WakeLockManager.acquireWifiLock(applicationContext, TAG)

                val binaryRepo = BinaryRepository(applicationContext)
                val rpcBinary = binaryRepo.getSdRpcServerBinary()
                if (rpcBinary == null || !rpcBinary.exists()) {
                    addLog(getString(R.string.sd_dist_error_rpc_binary_missing))
                    stopWorkerMode()
                    return@launch
                }

                val libDir = File(applicationContext.filesDir, "lib").apply { mkdirs() }
                setupSdLibrarySymlinks(rpcBinary.parentFile, libDir, rpcBinary.absolutePath)
                val envPath = "${libDir.absolutePath}:${binaryRepo.getLibraryDir()}"
                val command = mutableListOf(
                    rpcBinary.absolutePath,
                    "-H", bindHost,
                    "-p", port.toString(),
                    "-t", threads.coerceAtLeast(1).toString()
                )
                if (cacheEnabled) {
                    workerCacheRoot(applicationContext)
                    command += "-c"
                }

                addLog(getString(R.string.sd_dist_log_worker_starting, port, threads, ramMB))
                if (cacheEnabled) {
                    addLog(getString(R.string.sd_dist_worker_cache_enabled_log))
                }
                addLog("${rpcBinary.name} ${command.drop(1).joinToString(" ")}")

                val processBuilder = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .directory(applicationContext.filesDir)
                processBuilder.environment()["LD_LIBRARY_PATH"] = envPath
                processBuilder.environment()["HOME"] = applicationContext.filesDir.absolutePath
                processBuilder.environment()["TMPDIR"] = applicationContext.cacheDir.absolutePath
                processBuilder.environment()["XDG_CACHE_HOME"] = workerCacheRoot(applicationContext).absolutePath
                processBuilder.environment()["GGML_RPC_DEBUG"] = "1"

                rpcProcess = processBuilder.start()
                _isWorkerRunning.value = true
                notificationTaskId?.let {
                    UnifiedNotificationManager.updateProgress(
                        it,
                        -1f,
                        getString(R.string.sd_dist_worker_notification_running, port)
                    )
                }

                readWorkerOutput(rpcProcess!!)
            } catch (error: Exception) {
                addLog(getString(R.string.sd_dist_error_worker_failed, error.message ?: error.javaClass.simpleName))
            } finally {
                _isWorkerRunning.value = false
                rpcProcess = null
                WakeLockManager.release(TAG)
                WakeLockManager.releaseWifiLock(TAG)
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                notificationTaskId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun readWorkerOutput(process: Process) {
        val connectedRegex = "accepted connection|new connection|client connected".toRegex(RegexOption.IGNORE_CASE)
        val disconnectedRegex = "connection closed|closing connection|client disconnected".toRegex(RegexOption.IGNORE_CASE)
        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { line ->
                addLog(line)
                if (connectedRegex.containsMatchIn(line)) {
                    _connectionCount.value += 1
                }
                if (disconnectedRegex.containsMatchIn(line) && _connectionCount.value > 0) {
                    _connectionCount.value -= 1
                }
            }
        }
        val exitCode = process.waitFor()
        addLog(getString(R.string.sd_dist_log_worker_exited, exitCode))
    }

    private fun stopWorkerMode() {
        rpcProcess?.destroy()
        rpcProcess = null
        _isWorkerRunning.value = false
        _connectionCount.value = 0
        WakeLockManager.release(TAG)
        WakeLockManager.releaseWifiLock(TAG)
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun startForegroundIfNeeded(title: String) {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.IMAGE_GEN,
            title
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        UnifiedNotificationManager.updateProgress(taskId, -1f, title)
    }
}
