package com.example.llamadroid.ui.distributed.branchy

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyHelperSettings
import com.example.llamadroid.branchy.cluster.BranchyHelperState
import com.example.llamadroid.branchy.cluster.BranchyInferenceStatus
import com.example.llamadroid.branchy.cluster.BranchySliceInfo
import com.example.llamadroid.branchy.cluster.BranchySliceNames
import com.example.llamadroid.branchy.cluster.BranchyTrainingProgress
import com.example.llamadroid.branchy.cluster.BranchyWorkerStorage
import com.example.llamadroid.branchy.cluster.BranchyWorkerStorageEntry
import com.example.llamadroid.service.BranchyHelperService
import com.example.llamadroid.ui.training.BenchCurve
import com.example.llamadroid.ui.training.BranchyBenchMath
import com.example.llamadroid.ui.training.BranchyBenchState
import com.example.llamadroid.util.DeviceAddresses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** State and actions of [BranchyHelperScreen]; the worker itself runs in `BranchyHelperService`. */
class BranchyHelperViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    val running: StateFlow<Boolean> = BranchyHelperState.running
    val port: StateFlow<Int?> = BranchyHelperState.port
    val logs: StateFlow<List<String>> = BranchyHelperState.logs

    val slice: StateFlow<BranchySliceInfo?> = BranchyHelperState.slice
    val inferenceStatus: StateFlow<BranchyInferenceStatus> = BranchyHelperState.inferenceStatus
    val inferenceExitCode: StateFlow<Int?> = BranchyHelperState.inferenceExitCode
    val inferencePort: StateFlow<Int?> = BranchyHelperState.inferencePort
    val inferenceLines: StateFlow<List<String>> = BranchyHelperState.inferenceLines
    val lowBatteryPaused: StateFlow<Boolean> = BranchyHelperState.lowBatteryPaused
    val waitingMaster: StateFlow<Boolean> = BranchyHelperState.waitingMaster
    val workerThreads: StateFlow<Int?> = BranchyHelperState.workerThreads
    val training: StateFlow<BranchyTrainingProgress?> = BranchyHelperState.training

    private val _storage = MutableStateFlow<List<BranchyWorkerStorageEntry>>(emptyList())
    /** Worker root contents (shards, data-parallel data, inference slices), newest first. */
    val storage: StateFlow<List<BranchyWorkerStorageEntry>> = _storage.asStateFlow()

    private val workerRoot get() = File(context.filesDir, "branchy/worker")

    /** Name of the most recently used run on this worker, for the training card; null when unknown. */
    val runName: StateFlow<String?> = MutableStateFlow<String?>(null).also { flow ->
        viewModelScope.launch {
            _storage.collect { list -> flow.value = list.firstOrNull { it.name != null }?.name }
        }
    }.asStateFlow()

    private val _helpTrain = MutableStateFlow(BranchyHelperSettings.helpTrain(application))
    val helpTrain: StateFlow<Boolean> = _helpTrain.asStateFlow()

    private val _helpInfer = MutableStateFlow(BranchyHelperSettings.helpInfer(application))
    val helpInfer: StateFlow<Boolean> = _helpInfer.asStateFlow()

    private val _ctxText = MutableStateFlow(BranchyHelperSettings.ctx(application).toString())
    val ctxText: StateFlow<String> = _ctxText.asStateFlow()

    private val _batteryGuard = MutableStateFlow(BranchyHelperSettings.batteryGuard(application))
    val batteryGuard: StateFlow<Boolean> = _batteryGuard.asStateFlow()

    private val _batteryMinText = MutableStateFlow(BranchyHelperSettings.minBatteryPercent(application).toString())
    val batteryMinText: StateFlow<String> = _batteryMinText.asStateFlow()

    private val _copying = MutableStateFlow(false)
    /** True while a picked slice file is being copied into the worker's files folder. */
    val copying: StateFlow<Boolean> = _copying.asStateFlow()

    private val _tokenPreview = MutableStateFlow(previewOf(BranchyClusterToken.get(application)))
    /** Shortened saved token (first and last characters), or null when none is saved. */
    val tokenPreview: StateFlow<String?> = _tokenPreview.asStateFlow()

    private val _name = MutableStateFlow(BranchyHelperSettings.deviceName(application))
    val name: StateFlow<String> = _name.asStateFlow()

    private val _portText = MutableStateFlow(BranchyHelperSettings.port(application).toString())
    val portText: StateFlow<String> = _portText.asStateFlow()

    private val _threadsText = MutableStateFlow(BranchyHelperSettings.threads(application).toString())
    val threadsText: StateFlow<String> = _threadsText.asStateFlow()

    private val _bench = MutableStateFlow(BranchyBenchState())
    /** Local thread sweep; only runs while the worker is stopped. */
    val bench: StateFlow<BranchyBenchState> = _bench.asStateFlow()
    private var benchJob: Job? = null

    private val _addresses = MutableStateFlow(readAddresses())
    /** LAN and VPN addresses other devices can reach this phone on. */
    val addresses: StateFlow<List<String>> = _addresses.asStateFlow()

    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** String resource ids to show as a short message. */
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    init {
        // The master can retune threads remotely; the service persists it, so mirror it in the field.
        viewModelScope.launch { BranchyHelperState.workerThreads.collect { t -> if (t != null) _threadsText.value = t.toString() } }
        refreshStorage()
        // Rescan when the worker starts/stops or a session begins (meta.json is written at assign time).
        viewModelScope.launch { BranchyHelperState.running.collect { refreshStorage() } }
        viewModelScope.launch { BranchyHelperState.waitingMaster.collect { refreshStorage() } }
        if (BranchyHelperState.slice.value == null) {
            BranchySliceNames.latest(application)?.let { BranchyHelperState.setSlice(BranchySliceNames.info(it)) }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { BranchyWorkerStorage.scan(workerRoot) }
        }
    }

    /** Deletes one entry, or all when [entry] is null. Refused while the worker runs. */
    fun deleteStorage(entry: BranchyWorkerStorageEntry?) {
        if (running.value) {
            _notices.tryEmit(R.string.branchy_r6w_storage_running_reason)
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (entry == null) BranchyWorkerStorage.deleteAll(workerRoot, BranchyWorkerStorage.scan(workerRoot))
                else BranchyWorkerStorage.delete(workerRoot, entry)
            }
            if (!ok) _notices.tryEmit(R.string.branchy_r6w_storage_delete_failed)
            if (BranchySliceNames.latest(context) == null) BranchyHelperState.setSlice(null)
            refreshStorage()
        }
    }

    /** At least one role stays on; switching the last one off is refused with a message. */
    fun setHelpTrain(value: Boolean) = setRoles(value, _helpInfer.value)

    fun setHelpInfer(value: Boolean) = setRoles(_helpTrain.value, value)

    private fun setRoles(train: Boolean, infer: Boolean) {
        if (!train && !infer) {
            _notices.tryEmit(R.string.branchy_cluster_role_need_one)
            return
        }
        _helpTrain.value = train
        _helpInfer.value = infer
        BranchyHelperSettings.saveRoles(context, train, infer)
    }

    fun setCtxText(value: String) {
        _ctxText.value = value.filter { it.isDigit() }.take(6)
    }

    fun setBatteryGuard(value: Boolean) {
        _batteryGuard.value = value
    }

    fun setBatteryMinText(value: String) {
        _batteryMinText.value = value.filter { it.isDigit() }.take(2)
    }

    /** Copies a slice file picked with the system file picker into the worker's files folder and serves it. */
    fun importSlice(uri: Uri) {
        if (_tokenPreview.value == null) {
            _notices.tryEmit(R.string.branchy_cluster_start_needs_token)
            return
        }
        if (!_helpInfer.value) {
            _notices.tryEmit(R.string.branchy_cluster_slice_needs_role)
            return
        }
        if (_copying.value) return
        _copying.value = true
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            if (!BranchySliceNames.isSlice(name)) {
                _copying.value = false
                _notices.tryEmit(R.string.branchy_cluster_slice_bad_name)
                return@launch
            }
            val copied = withContext(Dispatchers.IO) { copySlice(uri, name) }
            _copying.value = false
            if (copied == null) {
                _notices.tryEmit(R.string.branchy_cluster_slice_copy_failed)
                return@launch
            }
            saveInferenceOptions()
            BranchyHelperService.useSlice(context, copied.absolutePath)
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        return BranchySliceNames.safeName(raw.orEmpty())
    }

    /** Copies the picked file to `<worker root>/files/<name>`; null on an I/O error. */
    private fun copySlice(uri: Uri, name: String): File? {
        val dir = BranchySliceNames.filesDir(context).apply { mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            } ?: return null
            if (target.exists()) target.delete()
            if (part.renameTo(target)) target else null
        } catch (_: java.io.IOException) {
            part.delete()
            null
        }
    }

    private fun saveInferenceOptions() {
        val ctx = (_ctxText.value.toIntOrNull() ?: BranchyHelperSettings.DEFAULT_CTX).coerceIn(256, 131072)
        val min = (_batteryMinText.value.toIntOrNull() ?: BranchyHelperSettings.DEFAULT_MIN_BATTERY).coerceIn(1, 90)
        _ctxText.value = ctx.toString()
        _batteryMinText.value = min.toString()
        BranchyHelperSettings.saveInference(context, ctx, _batteryGuard.value, min)
    }

    fun setName(value: String) {
        _name.value = value.take(48)
    }

    fun setPortText(value: String) {
        _portText.value = value.filter { it.isDigit() }.take(5)
    }

    fun setThreadsText(value: String) {
        _threadsText.value = value.filter { it.isDigit() }.take(2)
    }

    /**
     * `bench --sweep` on this phone (refused while the worker runs, since it would steal CPU from training). The
     * best count goes into the threads field and the saved helper settings; the advert GFLOP/s cache is refreshed.
     */
    fun runBenchmark() {
        if (benchJob?.isActive == true || BranchyHelperState.running.value) return
        _bench.value = BranchyBenchState(running = true)
        benchJob = viewModelScope.launch {
            try {
                val live = mutableListOf<com.example.llamadroid.branchy.BranchyEvent.BenchPoint>()
                val sweep = BranchyNative(context).benchSweep { p ->
                    live += p
                    _bench.update { it.copy(local = BenchCurve("local", "", live.toList(), 0, 0.0)) }
                }
                val (best, gflops) = BranchyBenchMath.resolve(sweep.bestThreads, sweep.gflops, sweep.results)
                val applied = best > 0
                if (applied) {
                    val threads = best.coerceIn(1, 64)
                    _threadsText.value = threads.toString()
                    val port = _portText.value.toIntOrNull()?.takeIf { it in 1024..65534 } ?: BranchyHelperSettings.port(context)
                    BranchyHelperSettings.save(
                        context, _name.value.ifBlank { BranchyHelperSettings.deviceName(context) }, port, threads
                    )
                    if (gflops > 0.0) BranchyHelperSettings.cacheGflops(context, threads, gflops)
                }
                _bench.update { it.copy(local = BenchCurve("local", "", sweep.results, best, gflops, applied = applied)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _bench.update { it.copy(error = t.message ?: t.javaClass.simpleName) }
            } finally {
                _bench.update { it.copy(running = false) }
            }
        }
    }

    fun cancelBenchmark() {
        benchJob?.cancel()
    }

    fun refreshAddresses() {
        _addresses.value = readAddresses()
    }

    /** Saves a typed or pasted token; reports the result through [notices]. */
    fun saveToken(input: String): Boolean {
        val ok = BranchyClusterToken.set(context, input)
        if (ok) _tokenPreview.value = previewOf(BranchyClusterToken.get(context))
        _notices.tryEmit(if (ok) R.string.branchy_cluster_token_saved_notice else R.string.branchy_cluster_token_invalid)
        return ok
    }

    fun clearToken() {
        BranchyClusterToken.clear(context)
        _tokenPreview.value = null
        _notices.tryEmit(R.string.branchy_cluster_token_cleared_notice)
    }

    fun clearLogs() = BranchyHelperState.clearLogs()

    /** Validates the options and the token, saves the options and starts the service. */
    fun start() {
        if (_tokenPreview.value == null) {
            _notices.tryEmit(R.string.branchy_cluster_start_needs_token)
            return
        }
        val port = _portText.value.toIntOrNull()
        if (port == null || port !in 1024..65534) {
            _notices.tryEmit(R.string.branchy_cluster_port_invalid)
            return
        }
        val threads = (_threadsText.value.toIntOrNull() ?: BranchyHelperSettings.defaultThreads()).coerceIn(1, 64)
        _threadsText.value = threads.toString()
        BranchyHelperSettings.save(context, _name.value.ifBlank { BranchyHelperSettings.deviceName(context) }, port, threads)
        saveInferenceOptions()
        refreshAddresses()
        BranchyHelperService.start(context)
    }

    fun stop() = BranchyHelperService.stop(context)

    private fun readAddresses(): List<String> {
        val all = DeviceAddresses.current(context)
        return listOfNotNull(DeviceAddresses.lanIp(all), DeviceAddresses.vpnIp(all)).distinct()
    }

    companion object {
        /** `abcd…wxyz` for a token, null for null. */
        fun previewOf(token: String?): String? = token?.let { "${it.take(4)}…${it.takeLast(4)}" }

        /** `ip:port` strings for the status line. */
        fun endpoints(addresses: List<String>, port: Int): List<String> =
            addresses.map { "$it:${port.coerceIn(1, 65535)}" }
    }
}
