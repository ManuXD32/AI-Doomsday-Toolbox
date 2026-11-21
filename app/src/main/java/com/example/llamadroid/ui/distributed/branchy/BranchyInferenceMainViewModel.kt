package com.example.llamadroid.ui.distributed.branchy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunRepository
import com.example.llamadroid.branchy.BranchyTrainingState
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyClusterRepository
import com.example.llamadroid.branchy.cluster.BranchyDiscovery
import com.example.llamadroid.branchy.infer.BranchyInferArgs
import com.example.llamadroid.branchy.infer.BranchyInferException
import com.example.llamadroid.branchy.infer.BranchyInferPipeline
import com.example.llamadroid.branchy.infer.BranchyInferStatusParser
import com.example.llamadroid.branchy.infer.BranchyPlacement
import com.example.llamadroid.branchy.infer.InferHelper
import com.example.llamadroid.branchy.infer.InferLayout
import com.example.llamadroid.branchy.infer.InferLiveStatus
import com.example.llamadroid.branchy.infer.InferRequest
import com.example.llamadroid.branchy.infer.InferStarted
import com.example.llamadroid.branchy.infer.InferStep
import com.example.llamadroid.branchy.infer.InferStepState
import com.example.llamadroid.modeltools.BranchyExportSupport
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class InferPhase { SETUP, RUNNING, READY }

/** A device that can take slices, as shown in the picker. */
data class InferHelperRow(val helper: InferHelper, val name: String, val online: Boolean, val rttMs: Int?)

/** Message to show: string [res] with optional [arg]. */
data class InferMessage(val res: Int, val arg: String? = null)

data class BranchyInferUiState(
    val runs: List<BranchyRunEntity> = emptyList(),
    val runId: Long? = null,
    val layout: InferLayout? = null,
    val type: String = "q8_0",
    val helpers: List<InferHelperRow> = emptyList(),
    val selected: Set<String> = emptySet(),
    val placement: Map<Int, String> = emptyMap(),
    val phase: InferPhase = InferPhase.SETUP,
    val steps: List<InferStep> = emptyList(),
    val stepStates: Map<String, InferStepState> = emptyMap(),
    val message: InferMessage? = null,
    val started: InferStarted? = null,
    val live: InferLiveStatus = InferLiveStatus()
)

/** State and actions of [BranchyInferenceMainScreen]. */
class BranchyInferenceMainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val runRepo = BranchyRunRepository(application)
    private val clusterRepo = BranchyClusterRepository(application)
    private val discovery = BranchyDiscovery(application, clusterRepo)
    private val pipeline = BranchyInferPipeline(application)

    private val _state = MutableStateFlow(BranchyInferUiState())
    val state: StateFlow<BranchyInferUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var statusJob: Job? = null
    private var rtt: Map<String, Int?> = emptyMap()
    private var devices: List<BranchyClusterDeviceEntity> = emptyList()

    init {
        viewModelScope.launch {
            runRepo.observeAll().collect { all ->
                // Only runs that already wrote a checkpoint can be exported.
                val usable = withContext(Dispatchers.IO) {
                    all.filter { BranchyExportSupport.latestCheckpoint(File(it.runDir), it.stage) != null }
                }
                _state.update { it.copy(runs = usable) }
            }
        }
        viewModelScope.launch {
            clusterRepo.observeDevices().collect { refreshHelpers(it) }
        }
        viewModelScope.launch {
            discovery.discovered.collect { refreshHelpers(null) }
        }
        discovery.start()
    }

    private fun refreshHelpers(latest: List<BranchyClusterDeviceEntity>?) {
        if (latest != null) devices = latest
        val seen = discovery.discovered.value.associateBy { it.id }
        val now = System.currentTimeMillis()
        val rows = devices.filter { it.roleEnum.canInfer }.map { d ->
            val found = seen[d.id]
            InferHelperRow(
                helper = InferHelper(
                    id = d.id, name = d.name, host = d.host, pushPort = d.port,
                    inferPort = (found?.inferPort ?: (d.port + INFER_PORT_OFFSET)).coerceAtMost(65535),
                    gflops = d.gflops
                ),
                name = d.name,
                online = found != null || now - d.lastSeen < ONLINE_WINDOW_MS,
                rttMs = rtt[d.id]
            )
        }
        _state.update { s ->
            val ids = rows.map { it.helper.id }.toSet()
            val selected = s.selected.filter { it in ids }.toSet()
            s.copy(helpers = rows, selected = selected, placement = replan(s, selected, rows, keepManual = true))
        }
    }

    private fun replan(
        s: BranchyInferUiState,
        selected: Set<String>,
        rows: List<InferHelperRow>,
        keepManual: Boolean
    ): Map<Int, String> {
        val layout = s.layout ?: return emptyMap()
        val chosen = rows.map { it.helper }.filter { it.id in selected }
        val kept = if (keepManual) BranchyPlacement.prune(layout, s.placement, selected) else emptyMap()
        return if (kept.isNotEmpty()) kept else BranchyPlacement.auto(layout, chosen)
    }

    fun selectRun(run: BranchyRunEntity) {
        viewModelScope.launch {
            val layout = withContext(Dispatchers.IO) { layoutOf(run) }
            _state.update { s ->
                val next = s.copy(runId = run.id, layout = layout, placement = emptyMap(), message = null)
                next.copy(placement = replan(next, next.selected, next.helpers, keepManual = false))
            }
        }
    }

    private fun layoutOf(run: BranchyRunEntity): InferLayout? {
        val stageDir = BranchyExportSupport.stageDir(File(run.runDir), run.stage)
        BranchyPlacement.parseLayout(runCatching { File(stageDir, "run.json").readText() }.getOrNull())?.let { return it }
        val l = BranchyRunConfig.decode(run.configJson)?.plan?.layout ?: return null
        return InferLayout(l.branches, l.localBranches, l.context).takeIf { it.branches in 1..BranchyPlacement.MAX_BRANCHES }
    }

    fun setType(type: String) = _state.update { it.copy(type = type) }

    fun toggleHelper(id: String) {
        _state.update { s ->
            val selected = if (id in s.selected) s.selected - id else s.selected + id
            s.copy(selected = selected, placement = replan(s, selected, s.helpers, keepManual = false))
        }
        if (id in _state.value.selected && id !in rtt) measureRtt(id)
    }

    private fun measureRtt(id: String) {
        val row = _state.value.helpers.firstOrNull { it.helper.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ms = BranchyInferPipeline.measureRttMs(row.helper.host, row.helper.pushPort)
            rtt = rtt + (id to ms)
            withContext(Dispatchers.Main) { refreshHelpers(null) }
        }
    }

    fun autoPlace() = _state.update { s -> s.copy(placement = replan(s, s.selected, s.helpers, keepManual = false)) }

    /** Moves [branch] to the next selected helper, then back to this phone, then around again. */
    fun cycleBranch(branch: Int) {
        _state.update { s ->
            val order = s.helpers.map { it.helper.id }.filter { it in s.selected }
            val current = s.placement[branch]
            val next = when {
                order.isEmpty() -> null
                current == null -> order.first()
                order.indexOf(current) < 0 || order.indexOf(current) == order.lastIndex -> null
                else -> order[order.indexOf(current) + 1]
            }
            s.copy(placement = BranchyPlacement.assign(s.placement, branch, next))
        }
    }

    /** Pure precheck used by the UI to enable the Start button. */
    fun canStart(s: BranchyInferUiState): Boolean =
        s.runId != null && s.layout != null &&
            BranchyPlacement.validate(s.layout, s.placement, s.selected.size) == null

    fun start() {
        val s = _state.value
        val run = s.runs.firstOrNull { it.id == s.runId } ?: return
        val layout = s.layout ?: return
        if (BranchyPlacement.validate(layout, s.placement, s.selected.size) != null) return
        val slices = BranchyPlacement.slices(s.placement)
        val helpers = s.helpers.map { it.helper }.filter { it.id in slices }
        val live = BranchyTrainingState.state.value
        val blocked = BranchyInferArgs.pushBlockedHelpers(slices.keys, live.phase, live.devices.map { it.id })
        if (blocked.isNotEmpty()) {
            val names = helpers.filter { it.id in blocked }.joinToString { it.name }
            _state.update { it.copy(message = InferMessage(R.string.branchy_infer_err_training_helper, names)) }
            return
        }
        val request = InferRequest(run, s.type, helpers, slices, layout)
        val steps = pipeline.steps(request)
        _state.update {
            it.copy(
                phase = InferPhase.RUNNING, steps = steps, message = null, started = null, live = InferLiveStatus(),
                stepStates = steps.associate { st -> st.key to InferStepState.Pending }
            )
        }
        job = viewModelScope.launch {
            try {
                // Each helper's preferred address (VPN first) falls back to its other address when only that one answers.
                val checked = com.example.llamadroid.branchy.cluster.BranchyEndpointPreflight
                    .preflight(context, devices.filter { it.id in slices })
                    .associateBy { it.id }
                val reachable = request.copy(helpers = helpers.map { h -> checked[h.id]?.let { h.copy(host = it.host) } ?: h })
                val started = pipeline.run(reachable) { step, st -> _state.update { it.copy(stepStates = it.stepStates + (step.key to st)) } }
                _state.update {
                    it.copy(phase = InferPhase.READY, started = started, live = BranchyInferStatusParser.connected(started.helperTargets))
                }
                watchServer(started)
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = InferPhase.SETUP, message = InferMessage(R.string.branchy_infer_cancelled)) }
                throw e
            } catch (e: Exception) {
                val res = when ((e as? BranchyInferException)?.code) {
                    "no_binary" -> R.string.branchy_infer_err_no_binary
                    "no_checkpoint" -> R.string.branchy_infer_err_no_checkpoint
                    else -> R.string.branchy_infer_err_failed
                }
                _state.update { it.copy(phase = InferPhase.SETUP, message = InferMessage(res, e.message)) }
            }
        }
    }

    /** Reads the server log every 2 s and folds helper link state and tokens per second. */
    private fun watchServer(started: InferStarted) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch(Dispatchers.IO) {
            val store = LlamaServerSessionLogStore(context)
            while (true) {
                val status = BranchyInferStatusParser.fold(
                    BranchyInferStatusParser.connected(started.helperTargets), store.read(started.sessionId)
                )
                _state.update { it.copy(live = status) }
                delay(2000)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Stops the server card's session and returns to the setup view. */
    fun stopServer() {
        val started = _state.value.started ?: return
        statusJob?.cancel()
        LlamaServerLauncher.stopSession(context, started.sessionId)
        _state.update { it.copy(phase = InferPhase.SETUP, started = null, live = InferLiveStatus()) }
    }

    override fun onCleared() {
        discovery.close()
        statusJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val INFER_PORT_OFFSET = 3
        private const val ONLINE_WINDOW_MS = 60_000L
    }
}
