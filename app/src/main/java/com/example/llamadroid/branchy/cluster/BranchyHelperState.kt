package com.example.llamadroid.branchy.cluster

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state of `BranchyHelperService`, observed by the helper screen. */
object BranchyHelperState {
    const val MAX_LOG_LINES = 300
    const val MAX_INFERENCE_LINES = 12

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow<Int?>(null)
    /** Port the worker listens on while [running]. */
    val port: StateFlow<Int?> = _port.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _slice = MutableStateFlow<BranchySliceInfo?>(null)
    /** Latest model slice this device holds for distributed inference. */
    val slice: StateFlow<BranchySliceInfo?> = _slice.asStateFlow()

    private val _inferenceStatus = MutableStateFlow(BranchyInferenceStatus.OFF)
    val inferenceStatus: StateFlow<BranchyInferenceStatus> = _inferenceStatus.asStateFlow()

    private val _inferenceExitCode = MutableStateFlow<Int?>(null)
    /** Exit code of the last inference helper process when [inferenceStatus] is EXITED. */
    val inferenceExitCode: StateFlow<Int?> = _inferenceExitCode.asStateFlow()

    private val _inferencePort = MutableStateFlow<Int?>(null)
    val inferencePort: StateFlow<Int?> = _inferencePort.asStateFlow()

    private val _inferenceLines = MutableStateFlow<List<String>>(emptyList())
    /** Last output lines of `llama-branchy-helper` (stderr and stdout). */
    val inferenceLines: StateFlow<List<String>> = _inferenceLines.asStateFlow()

    private val _lowBatteryPaused = MutableStateFlow(false)
    /** True while both processes are suspended by the battery guard. */
    val lowBatteryPaused: StateFlow<Boolean> = _lowBatteryPaused.asStateFlow()

    private val _waitingMaster = MutableStateFlow(false)
    /** True while the running worker reports it is waiting for a master to connect. */
    val waitingMaster: StateFlow<Boolean> = _waitingMaster.asStateFlow()

    private val _workerThreads = MutableStateFlow<Int?>(null)
    /** Thread count last reported by the running worker, null until it reports one. */
    val workerThreads: StateFlow<Int?> = _workerThreads.asStateFlow()

    private val _training = MutableStateFlow<BranchyTrainingProgress?>(null)
    /** Latest training progress while a master session is active; null when idle. */
    val training: StateFlow<BranchyTrainingProgress?> = _training.asStateFlow()

    fun setTraining(progress: BranchyTrainingProgress?) {
        _training.value = progress
    }

    fun setRunning(running: Boolean, port: Int?) {
        _running.value = running
        _port.value = if (running) port else null
        // A freshly started worker has no master yet.
        if (running) _waitingMaster.value = true
        if (!running) {
            _lowBatteryPaused.value = false
            _waitingMaster.value = false
            _workerThreads.value = null
            _training.value = null
        }
    }

    fun setWaitingMaster(waiting: Boolean) {
        if (waiting) _training.value = null
        _waitingMaster.value = waiting
    }

    fun setWorkerThreads(threads: Int?) {
        _workerThreads.value = threads
    }

    fun setSlice(info: BranchySliceInfo?) {
        _slice.value = info
    }

    fun setInferenceRunning(port: Int) {
        _inferenceLines.value = emptyList()
        _inferenceExitCode.value = null
        _inferencePort.value = port
        _inferenceStatus.value = BranchyInferenceStatus.RUNNING
    }

    fun setInferenceExited(exitCode: Int?) {
        _inferenceExitCode.value = exitCode
        _inferenceStatus.value = BranchyInferenceStatus.EXITED
    }

    fun setInferenceOff() {
        _inferenceStatus.value = BranchyInferenceStatus.OFF
        _inferencePort.value = null
    }

    fun appendInferenceLine(line: String) {
        _inferenceLines.update { (it + line).takeLast(MAX_INFERENCE_LINES) }
    }

    fun setLowBatteryPaused(paused: Boolean) {
        _lowBatteryPaused.value = paused
    }

    fun appendLog(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _logs.update { (it + "$stamp  $line").takeLast(MAX_LOG_LINES) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

/** Persisted helper options (`branchy_helper` preferences). */
object BranchyHelperSettings {
    private const val PREFS = "branchy_helper"
    private const val KEY_NAME = "device_name"
    private const val KEY_PORT = "port"
    private const val KEY_THREADS = "threads"
    private const val KEY_GFLOPS = "gflops"
    private const val KEY_GFLOPS_THREADS = "gflops_threads"
    private const val KEY_HELP_TRAIN = "help_train"
    private const val KEY_HELP_INFER = "help_infer"
    private const val KEY_CTX = "infer_ctx"
    private const val KEY_BATTERY_GUARD = "battery_guard"
    private const val KEY_BATTERY_MIN = "battery_min_percent"

    const val DEFAULT_CTX = 4096
    const val DEFAULT_MIN_BATTERY = 15
    /** The inference helper listens this far above the worker port (port + 1 is the heartbeat). */
    const val INFER_PORT_OFFSET = 3

    fun helpTrain(context: Context): Boolean = prefs(context).getBoolean(KEY_HELP_TRAIN, true)
    fun helpInfer(context: Context): Boolean = prefs(context).getBoolean(KEY_HELP_INFER, false)

    fun saveRoles(context: Context, train: Boolean, infer: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_HELP_TRAIN, train)
            putBoolean(KEY_HELP_INFER, infer)
        }
    }

    /** Advertised role; at least one toggle is always on, so no toggle on means training. */
    fun role(context: Context): BranchyClusterRole = BranchyClusterRoles.fromToggles(helpTrain(context), helpInfer(context))

    fun inferPort(context: Context): Int = (port(context) + INFER_PORT_OFFSET).coerceAtMost(65535)

    fun ctx(context: Context): Int = prefs(context).getInt(KEY_CTX, DEFAULT_CTX).coerceIn(256, 131072)

    fun batteryGuard(context: Context): Boolean = prefs(context).getBoolean(KEY_BATTERY_GUARD, true)

    fun minBatteryPercent(context: Context): Int = prefs(context).getInt(KEY_BATTERY_MIN, DEFAULT_MIN_BATTERY).coerceIn(1, 90)

    fun saveInference(context: Context, ctx: Int, batteryGuard: Boolean, minBatteryPercent: Int) {
        prefs(context).edit {
            putInt(KEY_CTX, ctx.coerceIn(256, 131072))
            putBoolean(KEY_BATTERY_GUARD, batteryGuard)
            putInt(KEY_BATTERY_MIN, minBatteryPercent.coerceIn(1, 90))
        }
    }

    fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 16)

    fun deviceName(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty().ifBlank { "Android" }

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, BranchyClusterIds.DEFAULT_WORKER_PORT).takeIf { it in 1024..65534 }
            ?: BranchyClusterIds.DEFAULT_WORKER_PORT

    fun threads(context: Context): Int = prefs(context).getInt(KEY_THREADS, defaultThreads()).coerceIn(1, 64)

    fun save(context: Context, name: String, port: Int, threads: Int) {
        prefs(context).edit {
            putString(KEY_NAME, name.trim())
            putInt(KEY_PORT, port)
            putInt(KEY_THREADS, threads)
        }
    }

    /** Last measured GFLOP/s, only when it was measured with [threads] threads. */
    fun cachedGflops(context: Context, threads: Int): Double? {
        val p = prefs(context)
        if (p.getInt(KEY_GFLOPS_THREADS, -1) != threads) return null
        return p.getFloat(KEY_GFLOPS, 0f).toDouble().takeIf { it > 0.0 }
    }

    fun cacheGflops(context: Context, threads: Int, gflops: Double) {
        prefs(context).edit {
            putFloat(KEY_GFLOPS, gflops.toFloat())
            putInt(KEY_GFLOPS_THREADS, threads)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Role from the two "Help ..." toggles. Neither toggle on falls back to training. */
object BranchyClusterRoles {
    fun fromToggles(train: Boolean, infer: Boolean): BranchyClusterRole = when {
        train && infer -> BranchyClusterRole.BOTH
        infer -> BranchyClusterRole.INFER
        else -> BranchyClusterRole.TRAIN
    }
}
