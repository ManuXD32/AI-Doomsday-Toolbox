package com.example.llamadroid.ui.training

import android.app.ActivityManager
import android.app.Application
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyNative
import com.example.llamadroid.branchy.BranchyRecipe
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunRepository
import com.example.llamadroid.branchy.BranchyTrainingState
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterRepository
import com.example.llamadroid.branchy.cluster.BranchyClusterToken
import com.example.llamadroid.branchy.cluster.BranchyDiscovery
import com.example.llamadroid.branchy.cluster.BranchyHelperProbe
import com.example.llamadroid.branchy.cluster.BranchyHelperStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.service.BranchyTrainingService
import com.example.llamadroid.training.CuratedDatasets
import com.example.llamadroid.training.DatasetRepository
import com.example.llamadroid.training.DatasetSource
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.TrainingDatasetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the three screens of the Branchy trainer is showing. */
sealed interface BranchyView {
    data object Home : BranchyView
    data object Wizard : BranchyView
    data class Live(val runId: Long) : BranchyView
}

enum class RecipePhase { ADDING, DOWNLOADING, PLANNING, ERROR }

/** Progress of the recipe being started; only one recipe starts at a time. */
data class RecipeProgress(
    val recipeId: String,
    val phase: RecipePhase,
    val fraction: Float = 0f,
    @StringRes val errorRes: Int? = null,
    val errorArg: String? = null
)

/**
 * State for the Branchy trainer: runs (Room), the dataset library (shared [DatasetRepository]), the
 * three-step wizard, the recipe start flow and the live view. Training itself runs in
 * [BranchyTrainingService]; this class only creates runs and forwards Pause/Resume/Stop.
 */
class BranchyTrainerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val runRepo = BranchyRunRepository(application)
    private val datasetRepo = DatasetRepository(application, db)
    private val native = BranchyNative(application)
    private val clusterRepo = BranchyClusterRepository(application)
    private val discovery = BranchyDiscovery(application, clusterRepo)
    private val probe = BranchyHelperProbe(application)
    private val prefs = application.getSharedPreferences("branchy_ui", android.content.Context.MODE_PRIVATE)

    val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    val runs: StateFlow<List<BranchyRunEntity>> = runRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val datasets: StateFlow<List<TrainingDatasetEntity>> = datasetRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val live: StateFlow<BranchyLiveState> = BranchyTrainingState.state

    /** Helper devices the main device knows (discovered, manual, pinned). */
    val helperDevices: StateFlow<List<BranchyClusterDeviceEntity>> = clusterRepo.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ids of the helpers visible on the network right now (only while discovery runs). */
    val visibleHelperIds: StateFlow<Set<String>> = discovery.discovered
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Latest heartbeat probe result per helper id; absent = not probed yet. */
    val helperStatus: StateFlow<Map<String, BranchyHelperStatus>> = probe.status

    private val _view = MutableStateFlow<BranchyView>(BranchyView.Home)
    val view: StateFlow<BranchyView> = _view.asStateFlow()

    private val _wizard = MutableStateFlow(BranchyWizardState())
    val wizard: StateFlow<BranchyWizardState> = _wizard.asStateFlow()

    private val _recipe = MutableStateFlow<RecipeProgress?>(null)
    val recipe: StateFlow<RecipeProgress?> = _recipe.asStateFlow()

    private val _notices = MutableSharedFlow<DatasetNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<DatasetNotice> = _notices.asSharedFlow()

    private val _bench = MutableStateFlow(BranchyBenchState())
    val bench: StateFlow<BranchyBenchState> = _bench.asStateFlow()

    private var benchJob: Job? = null
    private var recipeJob: Job? = null
    private var planJob: Job? = null
    private var probeJob: Job? = null

    init {
        viewModelScope.launch { runRepo.restoreInterrupted() }
        // Keep dataset rows that are still downloading up to date (the library screen is not open).
        viewModelScope.launch {
            while (true) {
                delay(DATASET_POLL_MS)
                datasets.value.filter { it.status == DatasetStatus.DOWNLOADING.value }
                    .forEach { runCatching { datasetRepo.refreshStatus(it.id) } }
            }
        }
    }

    // region navigation

    fun openRun(id: Long) { _view.value = BranchyView.Live(id) }

    fun goHome() { _view.value = BranchyView.Home }

    fun openWizard() {
        // Keep the helper selection and typed device values so a manually added helper is still chosen.
        val old = _wizard.value
        _wizard.value = BranchyWizardState(clusterDeviceIds = old.clusterDeviceIds, deviceEdits = old.deviceEdits)
        _view.value = BranchyView.Wizard
    }

    fun goToStep(step: BranchyWizardStep) = _wizard.update { it.copy(step = step) }

    fun liveRun(id: Long): Flow<BranchyRunEntity?> = runRepo.observe(id)

    // endregion

    // region wizard

    /**
     * Applies [block]. A change to a structural planning input resets the plan and its overrides; a change to a
     * device field or the epochs only marks a computed plan stale (the user recalculates when done typing).
     */
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
    }

    private fun dataTokens(s: BranchyWizardState): Double =
        BranchyWizardHelpers.estimatedDataTokens(s.weights.keys, datasets.value).tokens

    fun toggleDataset(id: Long, selected: Boolean) = updateWizard {
        it.copy(weights = if (selected) it.weights + (id to 1f) else it.weights - id)
    }

    fun setWeight(id: Long, weight: Float) = updateWizard { it.copy(weights = it.weights + (id to weight)) }

    fun computePlan() {
        val s = _wizard.value
        if (s.plan is BranchyPlanUi.Running) return
        val key = s.planningKey()
        val resetKey = s.resetKey()
        _wizard.update { it.copy(plan = BranchyPlanUi.Running) }
        planJob?.cancel()
        planJob = viewModelScope.launch {
            val result: BranchyPlanUi = try {
                val threads = BranchyWizardHelpers.effectiveThreads(s, cores)
                val device = localDevice(ensureBench(threads), threads)
                val helpers = selectedHelpers(s)
                val noRam = BranchyClusterRun.helpersWithoutRam(helpers, BranchyWizardHelpers.typedRam(s))
                if (noRam.isNotEmpty()) {
                    BranchyPlanUi.Failed(
                        getApplication<Application>().getString(
                            R.string.branchy_main_error_need_ram, noRam.joinToString(", ") { it.name }
                        )
                    )
                } else {
                    val plan = native.plan(BranchyWizardHelpers.buildPlanInput(s, device, helpers, dataTokens(s)))
                    val missing = BranchyClusterRun.missingEndpoints(plan, helpers.map { it.id })
                    if (missing.isEmpty()) BranchyPlanUi.Ready(plan) else BranchyPlanUi.Failed(
                        getApplication<Application>().getString(
                            R.string.branchy_main_error_missing_endpoints, missing.joinToString(", ")
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                BranchyPlanUi.Failed(t.message ?: t.javaClass.simpleName)
            }
            // A planning input changed meanwhile: the wizard already reset to Idle, drop this result.
            _wizard.update { cur ->
                when {
                    cur.planningKey() == key -> cur.copy(plan = result, overrides = emptyMap(), planStale = false)
                    // Only a device field/epochs changed while computing: keep the result but ask for a recalculation.
                    cur.resetKey() == resetKey -> cur.copy(plan = result, overrides = emptyMap(), planStale = true)
                    else -> cur
                }
            }
        }
    }

    /** Returns the reason Start is blocked, or null when the run was created and started. */
    fun startCustomRun(): BranchyStartIssue? {
        val s = _wizard.value
        BranchyWizardHelpers.validateStart(s)?.let { return it }
        if (isBusy()) {
            _notices.tryEmit(DatasetNotice(R.string.branchy_ui_busy))
            return null
        }
        viewModelScope.launch {
            try {
                val threads = BranchyWizardHelpers.effectiveThreads(s, cores)
                val device = localDevice(ensureBench(threads), threads)
                val config = BranchyWizardHelpers.customConfig(s, device, threads, selectedHelpers(s), dataTokens(s))
                    ?: return@launch
                val name = s.runName.trim().ifBlank { BranchyWizardHelpers.defaultRunName(s.effectiveTarget()) }
                val id = runRepo.create(name, config)
                BranchyTrainingService.start(getApplication(), id)
                _view.value = BranchyView.Live(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _notices.tryEmit(DatasetNotice(R.string.branchy_ui_start_failed, t.message ?: t.javaClass.simpleName))
            }
        }
        return null
    }

    // endregion

    // region cluster devices

    /** The helper entities of [state]'s selection, in selection order; devices removed meanwhile are skipped. */
    private suspend fun selectedHelpers(state: BranchyWizardState): List<BranchyClusterDeviceEntity> {
        if (state.clusterDeviceIds.isEmpty()) return emptyList()
        val byId = clusterRepo.getDevices().associateBy { it.id }
        return state.clusterDeviceIds.mapNotNull { byId[it] }
    }

    /** Starts browsing for helpers while the Devices step is open. */
    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun toggleHelper(id: String, selected: Boolean) = updateWizard {
        it.copy(clusterDeviceIds = if (selected) (it.clusterDeviceIds - id) + id else it.clusterDeviceIds - id)
    }

    fun editHelper(id: String, block: (ClusterDeviceEdit) -> ClusterDeviceEdit) = updateWizard {
        it.copy(deviceEdits = it.deviceEdits + (id to block(it.deviceEdits[id] ?: ClusterDeviceEdit())))
    }

    /** Adds a helper typed as `ip:port` and selects it. */
    fun addHelperManually(text: String) {
        viewModelScope.launch {
            val device = clusterRepo.addManual(text)
            if (device == null) {
                _notices.tryEmit(DatasetNotice(R.string.branchy_main_manual_invalid))
            } else {
                toggleHelper(device.id, true)
                probe.probe(listOf(device))
            }
        }
    }

    /** Probes every saved helper now and every [BranchyHelperProbe.INTERVAL_MS] until [stopHelperProbing]. */
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

    /** Saves the helper's preferred network interface (`auto`, `vpn`, `lan`). */
    fun setHelperInterface(id: String, pref: String) {
        viewModelScope.launch { clusterRepo.setPreferredInterface(id, pref) }
    }

    fun removeHelper(id: String) {
        probe.forget(id)
        toggleHelper(id, false)
        // A re-added helper must start with empty fields, not the removed one's typed values.
        updateWizard { it.copy(deviceEdits = it.deviceEdits - id) }
        viewModelScope.launch { clusterRepo.remove(id) }
    }

    override fun onCleared() {
        stopHelperProbing()
        discovery.close()
        super.onCleared()
    }

    // endregion

    // region recipes

    /** Adds and downloads the recipe's datasets, then opens the wizard prefilled from it (see [prepareRecipe]). */
    fun startRecipe(recipe: BranchyRecipe, stageNames: List<String>) {
        if (recipeJob?.isActive == true) return
        recipeJob = viewModelScope.launch {
            try {
                prepareRecipe(recipe, stageNames)
            } catch (e: CancellationException) {
                _recipe.value = null
                throw e
            } catch (t: Throwable) {
                recipeError(recipe, R.string.branchy_ui_recipe_error_generic, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancelRecipe() {
        recipeJob?.cancel()
        _recipe.value = null
    }

    private fun recipeError(recipe: BranchyRecipe, @StringRes res: Int, arg: String?) {
        _recipe.value = RecipeProgress(recipe.id, RecipePhase.ERROR, errorRes = res, errorArg = arg)
    }

    private fun setRecipe(recipe: BranchyRecipe, phase: RecipePhase, fraction: Float = 0f) {
        _recipe.value = RecipeProgress(recipe.id, phase, fraction)
    }

    /** Ensures the recipe's datasets are in the library and ready, then opens the wizard prefilled from the recipe. */
    private suspend fun prepareRecipe(recipe: BranchyRecipe, stageNames: List<String>) {
        setRecipe(recipe, RecipePhase.ADDING)
        val curatedIds = recipe.datasetIds()
        val titles = curatedIds.mapNotNull { CuratedDatasets.byId(it)?.title }.toSet()
        // Failed downloads of these datasets are removed so a fresh attempt starts.
        datasetRepo.list().filter {
            it.source == DatasetSource.CURATED.value && it.name in titles && it.status == DatasetStatus.FAILED.value
        }.forEach { datasetRepo.delete(it.id) }
        val idMap = BranchyWizardHelpers.findCuratedRows(curatedIds, datasetRepo.list())
            .mapValues { it.value.id }.toMutableMap()
        for (curated in curatedIds) {
            if (idMap.containsKey(curated)) continue
            val entry = CuratedDatasets.byId(curated)
                ?: return recipeError(recipe, R.string.branchy_ui_recipe_error_generic, curated)
            val newId = datasetRepo.addCurated(entry).getOrElse {
                return recipeError(recipe, R.string.branchy_ui_recipe_error_add, "${entry.title}: ${it.message}")
            }
            idMap[curated] = newId
        }

        setRecipe(recipe, RecipePhase.DOWNLOADING)
        while (true) {
            val rows = HashMap<Long, TrainingDatasetEntity>()
            for (id in idMap.values) {
                val row = datasetRepo.refreshStatus(id)
                    ?: return recipeError(recipe, R.string.branchy_ui_recipe_error_removed, null)
                if (row.status == DatasetStatus.FAILED.value) {
                    return recipeError(recipe, R.string.branchy_ui_recipe_error_download, row.name)
                }
                rows[id] = row
            }
            if (rows.values.all { it.status == DatasetStatus.READY.value }) break
            setRecipe(
                recipe, RecipePhase.DOWNLOADING,
                BranchyWizardHelpers.downloadFraction(idMap.values, rows, DownloadProgressHolder.progress.value)
            )
            delay(RECIPE_POLL_MS)
        }

        setRecipe(recipe, RecipePhase.PLANNING, 1f)
        val stages = BranchyWizardHelpers.recipeStages(recipe, idMap, stageNames)
            ?: return recipeError(recipe, R.string.branchy_ui_recipe_error_removed, null)
        // The recipe then goes through the same wizard as a custom run (devices, threads, plan, Start).
        val name = getApplication<Application>().getString(recipe.titleRes)
        _wizard.value = BranchyWizardHelpers.recipeWizardState(recipe, stages, name, _wizard.value)
        _recipe.value = null
        _view.value = BranchyView.Wizard
    }

    /** Leaves the recipe and keeps its values as the starting point of a custom run. */
    fun customizeRecipe() = updateWizard { BranchyWizardHelpers.customizeRecipe(it) }

    // endregion

    // region run actions

    private fun isBusy(): Boolean = BranchyTrainingState.state.value.isActive

    fun pauseRun(id: Long) = BranchyTrainingService.pause(getApplication(), id)

    fun resumeRun(id: Long) {
        val state = BranchyTrainingState.state.value
        if (state.isActive && state.runId != id) {
            _notices.tryEmit(DatasetNotice(R.string.branchy_ui_busy))
            return
        }
        BranchyTrainingService.resume(getApplication(), id)
    }

    fun stopRun(id: Long) = BranchyTrainingService.stop(getApplication(), id)

    /** Deleting a run the service is working on is refused; Stop it first. */
    fun deleteRun(id: Long) {
        val state = BranchyTrainingState.state.value
        if (state.isActive && state.runId == id) {
            _notices.tryEmit(DatasetNotice(R.string.branchy_ui_delete_active))
            return
        }
        viewModelScope.launch {
            runRepo.deleteKeepingExports(id).onFailure {
                _notices.tryEmit(DatasetNotice(R.string.branchy_r6_storage_delete_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    /** "Free space": drops tokenized data, mirror and DP shards of an idle run; refused while the service owns it. */
    fun freeSpace(id: Long) {
        val state = BranchyTrainingState.state.value
        if (state.isActive && state.runId == id) {
            _notices.tryEmit(DatasetNotice(R.string.branchy_ui_delete_active))
            return
        }
        viewModelScope.launch {
            val freed = runCatching { runRepo.freeSpace(id) }
            freed.onSuccess {
                _notices.tryEmit(
                    DatasetNotice(
                        R.string.branchy_r6_storage_freed,
                        com.example.llamadroid.util.FormatUtils.Display.formatBytes(getApplication(), it)
                    )
                )
            }.onFailure {
                _notices.tryEmit(DatasetNotice(R.string.branchy_r6_storage_free_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }

    // endregion

    // region benchmark

    /**
     * Local thread sweep, then (with helpers selected) `cluster-bench --apply`. The local best becomes the wizard's
     * thread count; helper results update their GFLOP/s (Room and the plan's typed value) and the plan is recomputed.
     */
    fun runBenchmark() {
        if (benchJob?.isActive == true) return
        val s = _wizard.value
        _bench.value = BranchyBenchState(running = true)
        benchJob = viewModelScope.launch {
            try {
                val live = mutableListOf<com.example.llamadroid.branchy.BranchyEvent.BenchPoint>()
                val sweep = native.benchSweep { p ->
                    live += p
                    _bench.update { it.copy(local = BenchCurve("local", "", live.toList(), 0, 0.0)) }
                }
                val localBest = sweep.bestThreads.takeIf { it > 0 } ?: BranchyBenchMath.best(sweep.results)
                val localGflops = sweep.gflops.takeIf { it > 0.0 }
                    ?: sweep.results.firstOrNull { it.threads == localBest }?.gflops ?: 0.0
                _bench.update {
                    it.copy(local = BenchCurve("local", "", sweep.results, localBest, localGflops, applied = true))
                }
                prefs.edit {
                    sweep.results.filter { it.gflops > 0.0 }.forEach { p -> putFloat("bench_gflops_${p.threads}", p.gflops.toFloat()) }
                }
                if (localBest > 0) updateWizard { it.copy(threads = localBest.coerceAtMost(cores)) }
                val helpers = selectedHelpers(s)
                if (helpers.isNotEmpty()) benchHelpers(helpers)
                computePlan()
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

    /** Sets (or clears, with null) the thread count of one helper; null = the helper keeps its own setting. */
    fun setHelperThreads(id: String, threads: Int?) = updateWizard {
        it.copy(helperThreads = if (threads == null) it.helperThreads - id else it.helperThreads + (id to threads.coerceIn(com.example.llamadroid.branchy.BranchyRunEdit.MIN_HELPER_THREADS, com.example.llamadroid.branchy.BranchyRunEdit.MAX_HELPER_THREADS)))
    }

    /** Benchmarks only helper [id] (selected or not; a cluster.json with just that device), then recomputes the plan. */
    fun benchHelper(id: String) {
        if (benchJob?.isActive == true) return
        _bench.update { it.copy(running = true, error = null, errorId = null, benchingId = id) }
        benchJob = viewModelScope.launch {
            try {
                val helper = clusterRepo.getDevices().firstOrNull { it.id == id }
                if (helper != null) {
                    benchHelpers(listOf(helper))
                    computePlan()
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

    private suspend fun benchHelpers(selected: List<BranchyClusterDeviceEntity>) {
        BranchyHelperBench.run(getApplication<Application>(), native, selected) { curve ->
            _bench.update { st -> st.copy(devices = st.devices.filter { it.id != curve.id } + curve) }
            updateWizard { w -> w.copy(helperThreads = BranchyBenchMath.withHelperThreads(w.helperThreads, curve)) }
            if (curve.applied) {
                viewModelScope.launch { clusterRepo.updateCaps(curve.id, curve.gflops) }
                editHelper(curve.id) { it.copy(gflops = String.format(java.util.Locale.US, "%.1f", curve.gflops)) }
            }
        }
    }

    // endregion

    // region device

    /** Local matmul GFLOP/s for [threads], measured once and cached in preferences. */
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
            id = "local", ramMb = info.availMem / BYTES_PER_MB, storageMb = storage, gflops = gflops, threads = threads
        )
    }

    // endregion

    private companion object {
        const val DATASET_POLL_MS = 2_000L
        const val RECIPE_POLL_MS = 1_500L
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
