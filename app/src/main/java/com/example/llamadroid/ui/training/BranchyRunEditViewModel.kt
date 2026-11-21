package com.example.llamadroid.ui.training

import android.app.ActivityManager
import android.app.Application
import android.os.StatFs
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunEdit
import com.example.llamadroid.branchy.BranchyRunRepository
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.branchy.BranchyTrainingState
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterRepository
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyDiscovery
import com.example.llamadroid.branchy.cluster.BranchyHelperProbe
import com.example.llamadroid.branchy.cluster.BranchyHelperStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the Save button is doing. */
sealed interface BranchyEditOp {
    data object Idle : BranchyEditOp

    /** `branchy consolidate` is pulling helper units to this phone; [progress] is the latest unit event. */
    data class Consolidating(val progress: BranchyEvent.Consolidate?) : BranchyEditOp
    data object Saving : BranchyEditOp
    data object Saved : BranchyEditOp
    data class Failed(val message: String) : BranchyEditOp
}

/** Initial editor state derived from a run's saved config; also used again after a save. */
internal class BranchyEditSeed(
    val state: BranchyWizardState,
    val helperThreads: Map<String, Int>,
    val key: List<Any>,
    /** Saved `train.checkpoint_every` override text; blank = planner default. */
    val checkpointEvery: String = ""
) {
    companion object {
        /** Null when the run has no saved plan to edit. */
        fun from(config: BranchyRunConfig): BranchyEditSeed? {
            val plan = config.plan ?: return null
            val threads = BranchyRunEdit.savedHelperThreads(config)
            val state = BranchyWizardState(
                threads = config.threads,
                clusterDeviceIds = config.cluster?.deviceIds.orEmpty(),
                clusterMode = config.cluster?.mode ?: "auto",
                deviceEdits = seedEdits(config),
                plan = BranchyPlanUi.Ready(plan)
            )
            val checkpoint = config.overrides[BranchyRunEdit.CHECKPOINT_KEY].orEmpty()
            return BranchyEditSeed(state, threads, state.planningKey() + threads.toSortedMap().toString() + checkpoint, checkpoint)
        }

        /** Saved RAM / speed / link of each planned helper, shown as typed values so the recalculation reproduces them. */
        private fun seedEdits(config: BranchyRunConfig): Map<String, ClusterDeviceEdit> =
            config.planInput.devices.filter { it.id != BranchyClusterRun.LOCAL_ID }.associate { d ->
                d.id to ClusterDeviceEdit(
                    ramMb = d.ramMb.takeIf { it > 0L }?.toString().orEmpty(),
                    gflops = d.gflops.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                    linkMbps = d.linkMbps.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.0f", it) }.orEmpty()
                )
            }

        /** ViewModel key of one edit session; each "Edit devices" open gets its own instance, starting Idle. */
        fun sessionKey(runId: Long, session: Int): String = "branchy_run_edit_${runId}_$session"
    }
}

/**
 * State of the paused-run device editor. The Devices-step UI is driven by a [BranchyWizardState] seeded from the
 * run's saved cluster selection and planner devices; "Recalculate" plans for the saved layout (fixed layout) and
 * Save consolidates first when a branch-parallel placement changed, then writes the config.
 */
class BranchyRunEditViewModel(application: Application, private val runId: Long) : AndroidViewModel(application) {
    private val runRepo = BranchyRunRepository(application)
    private val native = BranchyNative(application)
    private val clusterRepo = BranchyClusterRepository(application)
    private val discovery = BranchyDiscovery(application, clusterRepo)
    private val probe = BranchyHelperProbe(application)
    private val prefs = application.getSharedPreferences("branchy_ui", android.content.Context.MODE_PRIVATE)

    val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    val helperDevices: StateFlow<List<BranchyClusterDeviceEntity>> = clusterRepo.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val helperStatus: StateFlow<Map<String, BranchyHelperStatus>> = probe.status

    private val _wizard = MutableStateFlow(BranchyWizardState())
    val wizard: StateFlow<BranchyWizardState> = _wizard.asStateFlow()

    /** Planner threads per helper (the master's live in [BranchyWizardState.threads]). */
    private val _helperThreads = MutableStateFlow<Map<String, Int>>(emptyMap())
    val helperThreads: StateFlow<Map<String, Int>> = _helperThreads.asStateFlow()

    /** "Save a checkpoint every N steps" text; blank = the plan's default. Saved into config.overrides. */
    private val _checkpointEvery = MutableStateFlow("")
    val checkpointEvery: StateFlow<String> = _checkpointEvery.asStateFlow()

    private val _bench = MutableStateFlow(BranchyBenchState())

    /** Per-helper benchmark state (running flag, last result per helper). */
    val bench: StateFlow<BranchyBenchState> = _bench.asStateFlow()

    private val _op = MutableStateFlow<BranchyEditOp>(BranchyEditOp.Idle)
    val op: StateFlow<BranchyEditOp> = _op.asStateFlow()

    /** The run was found and has a saved plan to edit. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _changed = MutableStateFlow(false)

    /** The inputs differ from what the run was saved with (so there is something to recalculate and save). */
    val changed: StateFlow<Boolean> = _changed.asStateFlow()

    private var base: BranchyRunConfig? = null
    private var seedKey: List<Any> = emptyList()
    private var planJob: Job? = null
    private var saveJob: Job? = null
    private var probeJob: Job? = null
    private var benchJob: Job? = null

    init {
        viewModelScope.launch {
            val config = runRepo.config(runId)
            if (config != null) applySeed(config)
        }
    }

    /** Makes [config] the edit baseline: wizard, helper threads and change detection all describe it. */
    private fun applySeed(config: BranchyRunConfig) {
        val seed = BranchyEditSeed.from(config) ?: return
        base = config
        _helperThreads.value = seed.helperThreads
        _checkpointEvery.value = seed.checkpointEvery
        _wizard.value = seed.state
        seedKey = seed.key
        _changed.value = false
        _ready.value = true
    }

    private fun recomputeChanged() {
        _changed.value = _wizard.value.planningKey() + _helperThreads.value.toSortedMap().toString() +
            _checkpointEvery.value.trim() != seedKey
    }

    fun setCheckpointEvery(text: String) {
        _checkpointEvery.value = text.filter { it.isDigit() }.take(7)
        recomputeChanged()
    }

    /** Same rules as the wizard: a structural change drops the plan, a device field only marks it stale. */
    fun updateWizard(block: (BranchyWizardState) -> BranchyWizardState) {
        _wizard.update { old ->
            val new = block(old)
            when {
                new.resetKey() != old.resetKey() ->
                    new.copy(plan = BranchyPlanUi.Idle, overrides = emptyMap(), planStale = false)
                new.planningKey() != old.planningKey() && new.plan is BranchyPlanUi.Ready -> new.copy(planStale = true)
                else -> new
            }
        }
        recomputeChanged()
    }

    fun toggleHelper(id: String, selected: Boolean) = updateWizard {
        it.copy(clusterDeviceIds = if (selected) (it.clusterDeviceIds - id) + id else it.clusterDeviceIds - id)
    }

    fun editHelper(id: String, block: (ClusterDeviceEdit) -> ClusterDeviceEdit) = updateWizard {
        it.copy(deviceEdits = it.deviceEdits + (id to block(it.deviceEdits[id] ?: ClusterDeviceEdit())))
    }

    /** Sets (or clears, with null) the planner threads of one helper; null = the helper keeps its own setting. */
    fun setHelperThreads(id: String, threads: Int?) {
        _helperThreads.update {
            if (threads == null) it - id
            else it + (id to threads.coerceIn(BranchyRunEdit.MIN_HELPER_THREADS, BranchyRunEdit.MAX_HELPER_THREADS))
        }
        _wizard.update { if (it.plan is BranchyPlanUi.Ready) it.copy(planStale = true) else it }
        recomputeChanged()
    }

    fun addHelperManually(text: String) {
        viewModelScope.launch {
            val device = clusterRepo.addManual(text)
            if (device == null) {
                _op.value = BranchyEditOp.Failed(getApplication<Application>().getString(R.string.branchy_main_manual_invalid))
            } else {
                toggleHelper(device.id, true)
                probe.probe(listOf(device))
            }
        }
    }

    /** Saves the helper's preferred network interface (`auto`, `vpn`, `lan`). */
    fun setHelperInterface(id: String, pref: String) {
        viewModelScope.launch { clusterRepo.setPreferredInterface(id, pref) }
    }

    fun removeHelper(id: String) {
        probe.forget(id)
        toggleHelper(id, false)
        updateWizard { it.copy(deviceEdits = it.deviceEdits - id) }
        viewModelScope.launch { clusterRepo.remove(id) }
    }

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun startHelperProbing() {
        if (probeJob?.isActive == true) return
        probeJob = viewModelScope.launch {
            while (true) {
                val saved = runCatching { clusterRepo.getDevices() }.getOrDefault(emptyList())
                if (saved.isNotEmpty()) runCatching { probe.probe(saved) }
                delay(BranchyHelperProbe.INTERVAL_MS)
            }
        }
    }

    fun stopHelperProbing() {
        probeJob?.cancel()
        probeJob = null
    }

    fun clearError() {
        if (_op.value is BranchyEditOp.Failed) _op.value = BranchyEditOp.Idle
    }

    private suspend fun selectedHelpers(state: BranchyWizardState): List<BranchyClusterDeviceEntity> {
        if (state.clusterDeviceIds.isEmpty()) return emptyList()
        val byId = clusterRepo.getDevices().associateBy { it.id }
        return state.clusterDeviceIds.mapNotNull { byId[it] }
    }

    /**
     * Benchmarks helper [id] (selected or not) through the shared cluster-bench path; the best thread count and the
     * measured GFLOP/s become that helper's editor values (the plan is then marked stale, not recomputed).
     */
    fun benchHelper(id: String) {
        if (benchJob?.isActive == true) return
        _bench.update { it.copy(running = true, error = null, errorId = null, benchingId = id) }
        benchJob = viewModelScope.launch {
            try {
                val helper = clusterRepo.getDevices().firstOrNull { it.id == id }
                if (helper != null) {
                    BranchyHelperBench.run(getApplication<Application>(), native, listOf(helper)) { curve ->
                        _bench.update { st -> st.copy(devices = st.devices.filter { it.id != curve.id } + curve) }
                        if (curve.error == null && curve.best > 0) setHelperThreads(curve.id, curve.best)
                        if (curve.applied) {
                            viewModelScope.launch { clusterRepo.updateCaps(curve.id, curve.gflops) }
                            editHelper(curve.id) { it.copy(gflops = String.format(Locale.US, "%.1f", curve.gflops)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _bench.update { it.copy(error = t.message ?: t.javaClass.simpleName, errorId = id) }
            } finally {
                _bench.update { it.copy(running = false, benchingId = null) }
            }
        }
    }

    /** Plans the saved layout on the edited devices; the result is shown by the shared plan card. */
    fun recalculate() {
        val saved = base?.plan ?: return
        val config = base ?: return
        val s = _wizard.value
        if (s.plan is BranchyPlanUi.Running) return
        val key = s.planningKey()
        val resetKey = s.resetKey()
        val helperThreadsAtStart = _helperThreads.value
        _wizard.update { it.copy(plan = BranchyPlanUi.Running) }
        planJob?.cancel()
        planJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val result: BranchyPlanUi = try {
                val threads = BranchyWizardHelpers.effectiveThreads(s, cores)
                val device = localDevice(ensureBench(threads), threads)
                val helpers = selectedHelpers(s)
                val noRam = BranchyClusterRun.helpersWithoutRam(helpers, BranchyWizardHelpers.typedRam(s))
                if (noRam.isNotEmpty()) {
                    BranchyPlanUi.Failed(app.getString(R.string.branchy_main_error_need_ram, noRam.joinToString(", ") { it.name }))
                } else {
                    val devices = if (helpers.isEmpty()) listOf(device) else BranchyRunEdit.planDevices(
                        device, helpers, helperThreadsAtStart,
                        BranchyWizardHelpers.typedRam(s), BranchyWizardHelpers.typedGflops(s), BranchyWizardHelpers.typedLinks(s)
                    )
                    val input = BranchyRunEdit.planInput(config.planInput, saved.layout, s.clusterMode, devices)
                    val plan = native.plan(input)
                    val missing = BranchyClusterRun.missingEndpoints(plan, helpers.map { it.id })
                    when {
                        missing.isNotEmpty() ->
                            BranchyPlanUi.Failed(app.getString(R.string.branchy_main_error_missing_endpoints, missing.joinToString(", ")))
                        plan.fits && !BranchyRunEdit.layoutUnchanged(saved, plan) ->
                            BranchyPlanUi.Failed(app.getString(R.string.branchy_r5_edit_layout_changed))
                        else -> BranchyPlanUi.Ready(plan)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                BranchyPlanUi.Failed(t.message ?: t.javaClass.simpleName)
            }
            _wizard.update { cur ->
                when {
                    cur.planningKey() == key && _helperThreads.value == helperThreadsAtStart ->
                        cur.copy(plan = result, overrides = emptyMap(), planStale = false)
                    cur.resetKey() == resetKey -> cur.copy(plan = result, overrides = emptyMap(), planStale = true)
                    else -> cur
                }
            }
        }
    }

    /** Whether Save may run now: a fitting, fresh plan for the saved layout, nothing busy, and a real change. */
    fun canSave(state: BranchyWizardState, changed: Boolean, op: BranchyEditOp): Boolean {
        val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
        val saved = base?.plan
        return plan != null && saved != null && plan.fits && !state.planStale && changed &&
            BranchyRunEdit.layoutUnchanged(saved, plan) &&
            (op is BranchyEditOp.Idle || op is BranchyEditOp.Failed)
    }

    /**
     * Consolidates when needed (old cluster.json, branch-parallel placement change), then persists the new
     * selection, plan and threads. Any failure keeps the old config and shows the message.
     */
    fun save() {
        val s = _wizard.value
        val plan = (s.plan as? BranchyPlanUi.Ready)?.plan ?: return
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                if (BranchyTrainingState.state.value.isActive) error(app.getString(R.string.branchy_r5_edit_active))
                val run = runRepo.get(runId) ?: error(app.getString(R.string.branchy_r5_edit_missing))
                if (run.status != BranchyRunStatus.PAUSED.value) error(app.getString(R.string.branchy_r5_edit_not_paused))
                val config = BranchyRunConfig.decode(run.configJson) ?: error(app.getString(R.string.branchy_r5_edit_missing))
                val oldPlan = config.plan
                if (BranchyRunEdit.needsConsolidate(oldPlan, plan)) {
                    val target = BranchyRunEdit.consolidateTarget(java.io.File(run.runDir), config.currentStage)
                    if (target != null) {
                        _op.value = BranchyEditOp.Consolidating(null)
                        val token = withContext(Dispatchers.IO) { BranchyClusterToken.writeTokenFile(app) }
                        native.consolidate(target.stageDir, target.clusterJson, token) { p ->
                            _op.value = BranchyEditOp.Consolidating(p)
                        }
                    }
                }
                _op.value = BranchyEditOp.Saving
                if (BranchyTrainingState.state.value.isActive) error(app.getString(R.string.branchy_r5_edit_active))
                val threads = BranchyWizardHelpers.effectiveThreads(s, cores)
                val helpers = selectedHelpers(s)
                val devices = if (helpers.isEmpty()) {
                    listOf(localDevice(ensureBench(threads), threads))
                } else {
                    BranchyRunEdit.planDevices(
                        localDevice(ensureBench(threads), threads), helpers, _helperThreads.value,
                        BranchyWizardHelpers.typedRam(s), BranchyWizardHelpers.typedGflops(s), BranchyWizardHelpers.typedLinks(s)
                    )
                }
                val input = BranchyRunEdit.planInput(config.planInput, plan.layout, s.clusterMode, devices)
                val updated = BranchyRunEdit.applyEdit(config, helpers.map { it.id }, s.clusterMode, plan, input, threads, _helperThreads.value, _checkpointEvery.value)
                runRepo.saveConfig(runId, updated)
                // The saved config is the new baseline, so nothing still reads as changed or stale.
                applySeed(updated)
                _op.value = BranchyEditOp.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _op.value = BranchyEditOp.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    override fun onCleared() {
        stopHelperProbing()
        benchJob?.cancel()
        discovery.close()
        super.onCleared()
    }

    /** Local matmul GFLOP/s for [threads], from the same preference cache the trainer view model fills. */
    private suspend fun ensureBench(threads: Int): Double {
        val key = "bench_gflops_$threads"
        val cached = prefs.getFloat(key, 0f)
        if (cached > 0f) return cached.toDouble()
        val gflops = native.bench(threads)
        if (gflops.isFinite() && gflops > 0.0) prefs.edit { putFloat(key, gflops.toFloat()) }
        return gflops
    }

    private fun localDevice(gflops: Double, threads: Int): BranchyDevice {
        val app = getApplication<Application>()
        val info = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val root = app.getExternalFilesDir(null) ?: app.filesDir
        val storage = runCatching { StatFs(root.absolutePath).availableBytes / BYTES_PER_MB }.getOrDefault(0L)
        return BranchyDevice(
            id = BranchyClusterRun.LOCAL_ID, ramMb = info.availMem / BYTES_PER_MB, storageMb = storage,
            gflops = gflops, threads = threads
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
