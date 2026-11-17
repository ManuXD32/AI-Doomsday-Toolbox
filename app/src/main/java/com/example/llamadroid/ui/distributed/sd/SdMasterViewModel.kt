package com.example.llamadroid.ui.distributed.sd

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SdDistributedDao
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.sd.SdVideoComponentPaths
import com.example.llamadroid.sd.SdVideoInputs
import com.example.llamadroid.sd.SdVideoWorkflow
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SdDiscoveredWorker
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.VideoReuseDraftAdapter
import com.example.llamadroid.service.VideoReuseHandoffStore
import com.example.llamadroid.service.VideoReuseTarget
import com.example.llamadroid.service.VideoReuseWarning
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.service.normalizedForSave
import com.example.llamadroid.service.resolveVideoReuseSources
import com.example.llamadroid.service.settingsFromJson
import com.example.llamadroid.service.settingsToJson
import com.example.llamadroid.service.toVideoReuseWorkers
import com.example.llamadroid.service.videoRuntimeOptionsOrNull
import com.example.llamadroid.service.withDerivedEnabled
import com.example.llamadroid.service.withVideoOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The three stops of the main-device flow. */
internal enum class SdMasterStep { MODEL, DEVICES, RUN }

internal data class SdMasterUiState(
    val loaded: Boolean,
    val mode: SdMasterMode,
    val step: SdMasterStep,
    val settings: SdDistributedMasterSettingsEntity,
    val workers: List<SdDistributedWorkerEntity>,
    val videoOptions: VideoRuntimeOptions,
    val launchIssue: SdDistributedLaunchIssue?
)

/** DAO seam; [RoomSdMasterData] is the production implementation, tests supply a fake. */
internal interface SdMasterData {
    fun observeSettings(): Flow<SdDistributedMasterSettingsEntity?>
    fun observeWorkers(): Flow<List<SdDistributedWorkerEntity>>
    fun observeTemplates(): Flow<List<SdDistributedTemplateEntity>>
    suspend fun getSettings(): SdDistributedMasterSettingsEntity?
    suspend fun getEnabledWorkers(): List<SdDistributedWorkerEntity>
    suspend fun upsertSettings(settings: SdDistributedMasterSettingsEntity): Long
    suspend fun maxWorkerSortOrder(): Int
    suspend fun upsertWorker(worker: SdDistributedWorkerEntity): Long
    suspend fun setWorkerEnabled(id: Long, enabled: Boolean)
    suspend fun deleteWorker(id: Long)
    suspend fun upsertTemplate(template: SdDistributedTemplateEntity): Long
    suspend fun deleteTemplate(id: Long)
}

internal class RoomSdMasterData(private val dao: SdDistributedDao) : SdMasterData {
    override fun observeSettings() = dao.observeMasterSettings()
    override fun observeWorkers() = dao.observeWorkersOrdered()
    override fun observeTemplates() = dao.observeTemplates()
    override suspend fun getSettings() = dao.getMasterSettings()
    override suspend fun getEnabledWorkers() = dao.getEnabledWorkersOnce()
    override suspend fun upsertSettings(settings: SdDistributedMasterSettingsEntity) = dao.upsertMasterSettings(settings)
    override suspend fun maxWorkerSortOrder() = dao.getMaxWorkerSortOrder()
    override suspend fun upsertWorker(worker: SdDistributedWorkerEntity) = dao.upsertWorker(worker)
    override suspend fun setWorkerEnabled(id: Long, enabled: Boolean) = dao.setWorkerEnabled(id, enabled)
    override suspend fun deleteWorker(id: Long) = dao.deleteWorker(id)
    override suspend fun upsertTemplate(template: SdDistributedTemplateEntity) = dao.upsertTemplate(template)
    override suspend fun deleteTemplate(id: Long) = dao.deleteTemplate(id)
}

/** Service seam: receives the planner output after every save / Helper change. */
internal fun interface SdMasterRuntimeSink {
    fun apply(runtime: SdMasterRuntime)
}

internal val SdServiceRuntimeSink = SdMasterRuntimeSink { runtime ->
    SdDistributedService.setRuntimeConfig(runtime.runtimeConfig)
    SdDistributedService.setActiveWorkers(runtime.workerRuntimes)
}

/** First incomplete step: no model -> MODEL, no enabled Helper -> DEVICES, else RUN. */
internal fun initialSdMasterStep(
    mode: SdMasterMode,
    settings: SdDistributedMasterSettingsEntity,
    workers: List<SdDistributedWorkerEntity>,
    videoOptions: VideoRuntimeOptions
): SdMasterStep {
    val hasModel = when (mode) {
        SdMasterMode.IMAGES ->
            if (settings.imageWorkflowMode.toSdMasterImageMode() == SDMode.UPSCALE) {
                settings.imageUpscalerModelPath.isNotBlank()
            } else {
                settings.imageModelPath.isNotBlank()
            }
        SdMasterMode.VIDEO ->
            !(videoOptions.videoComponents.diffusionModelPath ?: videoOptions.videoComponents.fullModelPath).isNullOrBlank() ||
                settings.videoModelPath.isNotBlank()
    }
    return when {
        !hasModel -> SdMasterStep.MODEL
        workers.none { it.isEnabled } -> SdMasterStep.DEVICES
        else -> SdMasterStep.RUN
    }
}

/** Video options for a row: the versioned JSON, else rebuilt from the legacy scalar columns. */
internal fun videoOptionsOf(settings: SdDistributedMasterSettingsEntity): VideoRuntimeOptions =
    settings.videoRuntimeOptionsOrNull() ?: VideoRuntimeOptions(
        workflow = if (settings.videoWorkflowMode == "IMG2VID") {
            SdVideoWorkflow.IMAGE_TO_VIDEO
        } else {
            SdVideoWorkflow.TEXT_TO_VIDEO
        },
        videoComponents = SdVideoComponentPaths(
            diffusionModelPath = settings.videoModelPath.ifBlank { null },
            vaePath = settings.videoVaePath.ifBlank { null },
            t5xxlPath = settings.videoT5xxlPath.ifBlank { null }
        ),
        videoInputs = SdVideoInputs(settings.videoInputPath.ifBlank { null }),
        seed = settings.videoSeed.toLongOrNull() ?: -1L,
        useTae = false
    )

/** Old rows kept only the legacy prompt columns; the per-workflow ones fall back to them on load. */
private fun SdDistributedMasterSettingsEntity.withLegacyFallbacks() = copy(
    imagePrompt = imagePrompt.ifBlank { prompt },
    imageNegativePrompt = imageNegativePrompt.ifBlank { negativePrompt },
    videoPrompt = videoPrompt.ifBlank { prompt },
    videoNegativePrompt = videoNegativePrompt.ifBlank { negativePrompt }
)

/**
 * Android-free core of [SdMasterViewModel]: the settings entity is the form state, autosaved after
 * [debounceMs]; Helper changes re-derive `enabled` and re-plan. All seams are injectable.
 */
internal class SdMasterStore(
    private val scope: CoroutineScope,
    private val data: SdMasterData,
    private val sink: SdMasterRuntimeSink,
    private val defaultMasterName: String,
    initialMode: SdMasterMode,
    private val savedStep: SdMasterStep?,
    private val onNavigationChanged: (SdMasterMode, SdMasterStep) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = AUTOSAVE_DEBOUNCE_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    private val defaults = SdDistributedMasterSettingsEntity()
    private val _state = MutableStateFlow(
        SdMasterUiState(
            loaded = false,
            mode = initialMode,
            step = savedStep ?: SdMasterStep.MODEL,
            settings = defaults,
            workers = emptyList(),
            videoOptions = videoOptionsOf(defaults),
            launchIssue = null
        )
    )
    val state: StateFlow<SdMasterUiState> = _state.asStateFlow()

    private val _templates = MutableStateFlow<List<SdDistributedTemplateEntity>>(emptyList())
    val templates: StateFlow<List<SdDistributedTemplateEntity>> = _templates.asStateFlow()

    private val saveMutex = Mutex()
    private var debounceJob: Job? = null
    @Volatile private var dirty = false
    @Volatile private var settingsSeen = false
    @Volatile private var workersSeen = false
    /** Highest `updatedAt` written by us or applied from the DAO; emissions at or below it are echoes. */
    @Volatile private var lastSeenAt = 0L
    private var videoJsonCache: String? = null
    private var videoOptionsCache: VideoRuntimeOptions? = null

    fun start() {
        scope.launch { data.observeSettings().collect(::onSettingsRow) }
        scope.launch { data.observeWorkers().collect(::onWorkers) }
        scope.launch { data.observeTemplates().collect { _templates.value = it } }
    }

    private fun onSettingsRow(row: SdDistributedMasterSettingsEntity?) {
        if (!settingsSeen) {
            settingsSeen = true
            val first = (row ?: defaults).withLegacyFallbacks()
            if (row != null) lastSeenAt = row.updatedAt
            _state.update { it.copy(settings = first, videoOptions = videoOptionsFor(first)) }
            onDataChanged()
            return
        }
        if (row == null || row.updatedAt <= lastSeenAt || dirty) return
        lastSeenAt = row.updatedAt
        _state.update { it.copy(settings = row, videoOptions = videoOptionsFor(row)) }
        onDataChanged()
    }

    private fun onWorkers(rows: List<SdDistributedWorkerEntity>) {
        workersSeen = true
        _state.update { it.copy(workers = rows) }
        onDataChanged()
    }

    private fun onDataChanged() {
        if (!settingsSeen || !workersSeen) return
        val before = _state.value
        if (!before.loaded) {
            val step = savedStep ?: initialSdMasterStep(before.mode, before.settings, before.workers, before.videoOptions)
            _state.update { it.copy(loaded = true, step = step) }
            onNavigationChanged(before.mode, step)
        }
        val current = _state.value
        val derived = current.settings.withDerivedEnabled(current.workers)
        if (derived !== current.settings) {
            _state.update { it.copy(settings = it.settings.withDerivedEnabled(it.workers)) }
            scheduleSave()
        }
        sink.apply(buildSdMasterRuntime(_state.value.settings, _state.value.workers, video = _state.value.mode == SdMasterMode.VIDEO))
    }

    private fun videoOptionsFor(settings: SdDistributedMasterSettingsEntity): VideoRuntimeOptions {
        val json = settings.videoAdvancedJson.trim()
        val cached = videoOptionsCache
        if (cached != null && json.isNotBlank() && json == videoJsonCache) return cached
        return videoOptionsOf(settings).also {
            videoJsonCache = json.takeIf { value -> value.isNotBlank() && value != "{}" }
            videoOptionsCache = it
        }
    }

    fun setMode(mode: SdMasterMode) {
        _state.update { it.copy(mode = mode) }
        onNavigationChanged(mode, _state.value.step)
    }

    fun goTo(step: SdMasterStep) {
        _state.update { it.copy(step = step) }
        onNavigationChanged(_state.value.mode, step)
    }

    fun setLaunchIssue(issue: SdDistributedLaunchIssue?) {
        _state.update { it.copy(launchIssue = issue) }
    }

    fun update(transform: (SdDistributedMasterSettingsEntity) -> SdDistributedMasterSettingsEntity) {
        _state.update {
            val next = transform(it.settings)
            it.copy(settings = next, videoOptions = videoOptionsFor(next), launchIssue = null)
        }
        scheduleSave()
    }

    fun updateVideoOptions(options: VideoRuntimeOptions) {
        _state.update {
            val next = it.settings.withVideoOptions(options)
            videoJsonCache = next.videoAdvancedJson
            videoOptionsCache = options
            it.copy(settings = next, videoOptions = options, launchIssue = null)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        dirty = true
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delayFn(debounceMs)
            flush()
        }
    }

    /**
     * Writes the pending form state. Returns the persisted row id, or 0 when nothing was pending.
     * [applyRuntime] = false persists only (used when restoring a reuse draft).
     */
    private suspend fun flush(applyRuntime: Boolean = true): Long = saveMutex.withLock {
        if (!dirty) return@withLock 0L
        dirty = false
        val snapshot = _state.value
        val toWrite = snapshot.settings
            .withDerivedEnabled(snapshot.workers)
            .normalizedForSave(defaultMasterName)
            .copy(updatedAt = maxOf(now(), lastSeenAt + 1))
        // Advance before writing: Room may echo the row back before upsert returns.
        lastSeenAt = toWrite.updatedAt
        val id = try {
            withContext(NonCancellable) { data.upsertSettings(toWrite) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            dirty = true
            return@withLock 0L
        }
        if (applyRuntime) sink.apply(buildSdMasterRuntime(toWrite, snapshot.workers, video = snapshot.mode == SdMasterMode.VIDEO))
        id
    }

    suspend fun saveNow() {
        debounceJob?.cancel()
        flush()
    }

    /** Non-suspending flush for `onCleared`: runs on [detached] because the owner scope is already cancelled. */
    fun flushDetached(detached: CoroutineScope) {
        debounceJob?.cancel()
        if (dirty) detached.launch { flush() }
    }

    /** Runtime config for the current form state (what the launch builders consume). */
    fun currentRuntime(): SdMasterRuntime = _state.value.let { buildSdMasterRuntime(it.settings, it.workers, video = it.mode == SdMasterMode.VIDEO) }

    // ---- Helpers (workers) ----

    suspend fun addDiscoveredWorker(found: SdDiscoveredWorker) {
        data.upsertWorker(found.toEntity(data.maxWorkerSortOrder() + 1))
    }

    suspend fun setWorkerEnabled(worker: SdDistributedWorkerEntity) {
        data.setWorkerEnabled(worker.id, !worker.isEnabled)
    }

    suspend fun deleteWorker(id: Long) {
        data.deleteWorker(id)
    }

    /** Adds ([editingId] null) or edits a Helper; false when the host is blank. */
    suspend fun saveWorker(
        editingId: Long?,
        host: String,
        port: String,
        name: String,
        ram: String,
        threads: String,
        backend: String
    ): Boolean {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) return false
        val existing = _state.value.workers.firstOrNull { it.id == editingId }
        val sortOrder = if (editingId == null) data.maxWorkerSortOrder() + 1 else {
            existing?.sortOrder ?: (data.maxWorkerSortOrder() + 1)
        }
        data.upsertWorker(
            SdDistributedWorkerEntity(
                id = editingId ?: 0L,
                host = cleanHost,
                port = port.toIntOrNull() ?: SdDistributedService.RPC_DEFAULT_PORT,
                deviceName = name.ifBlank { cleanHost },
                ramMB = ram.toIntOrNull() ?: 4096,
                threads = threads.toIntOrNull() ?: 4,
                backendDevice = backend,
                isEnabled = existing?.isEnabled ?: true,
                sortOrder = sortOrder
            )
        )
        return true
    }

    // ---- Templates ----

    /** Saves the current form as a template for the active workflow; false when [name] is blank. */
    suspend fun saveTemplate(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val snapshot = _state.value
        val draft = snapshot.settings.withDerivedEnabled(snapshot.workers).normalizedForSave(defaultMasterName)
        data.upsertTemplate(
            SdDistributedTemplateEntity(
                name = clean,
                workflowType = (if (snapshot.mode == SdMasterMode.VIDEO) SdDistributedTemplateWorkflow.VIDEO
                else SdDistributedTemplateWorkflow.IMAGE).name,
                settingsJson = settingsToJson(draft),
                updatedAt = now()
            )
        )
        return true
    }

    fun loadTemplate(template: SdDistributedTemplateEntity) {
        update { settingsFromJson(template.settingsJson, it).copy(id = 1) }
    }

    suspend fun deleteTemplate(id: Long) {
        data.deleteTemplate(id)
    }

    // ---- Reuse handoff ----

    /** Replaces the whole form with [restored] and persists it at once, without touching the live runtime. */
    suspend fun replaceAndPersist(restored: SdDistributedMasterSettingsEntity): Long {
        debounceJob?.cancel()
        _state.update { it.copy(settings = restored, videoOptions = videoOptionsFor(restored), launchIssue = null) }
        dirty = true
        return flush(applyRuntime = false)
    }

    suspend fun currentSavedSettings(): SdDistributedMasterSettingsEntity = data.getSettings() ?: SdDistributedMasterSettingsEntity()

    suspend fun enabledWorkers(): List<SdDistributedWorkerEntity> = data.getEnabledWorkers()

    companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 600L
    }
}

/** True while any distributed image or video job is running. */
internal fun isSdDistributedRunActive(): Boolean {
    val image = SDMode.entries.any {
        SDModeStateHolder.getDistributedForMode(it).state.value is SDGenerationState.Generating
    }
    val video = VideoGenerationMode.entries.any {
        when (VideoGenerationStateHolder.getForMode(it, useDistributedStateHolder = true).state.value) {
            is VideoGenerationState.Generating,
            is VideoGenerationState.Converting,
            is VideoGenerationState.Copying -> true
            else -> false
        }
    }
    return image || video
}

internal class SdMasterViewModel(
    app: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(app) {
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SdMasterStore(
        scope = viewModelScope,
        data = RoomSdMasterData(AppDatabase.getDatabase(app).sdDistributedDao()),
        sink = SdServiceRuntimeSink,
        defaultMasterName = app.getString(R.string.sd_dist_this_device),
        initialMode = savedState.get<String>(KEY_MODE)
            ?.let { name -> SdMasterMode.entries.firstOrNull { it.name == name } }
            ?: SdMasterMode.IMAGES,
        savedStep = savedState.get<String>(KEY_STEP)
            ?.let { name -> SdMasterStep.entries.firstOrNull { it.name == name } },
        onNavigationChanged = { mode, step ->
            savedState[KEY_MODE] = mode.name
            savedState[KEY_STEP] = step.name
        }
    )

    val state: StateFlow<SdMasterUiState> = store.state
    val templates: StateFlow<List<SdDistributedTemplateEntity>> = store.templates

    private val _reuseWarning = MutableStateFlow<String?>(null)
    /** Localized warning/failure text of the last video-reuse handoff, or null. */
    val reuseWarning: StateFlow<String?> = _reuseWarning.asStateFlow()

    init {
        store.start()
    }

    fun setMode(mode: SdMasterMode) = store.setMode(mode)
    fun update(transform: (SdDistributedMasterSettingsEntity) -> SdDistributedMasterSettingsEntity) = store.update(transform)
    fun updateVideoOptions(options: VideoRuntimeOptions) = store.updateVideoOptions(options)
    fun goTo(step: SdMasterStep) = store.goTo(step)
    suspend fun saveNow() = store.saveNow()

    /** Records (or clears with null) the blocking issue the last Start attempt hit. */
    fun reportLaunchIssue(issue: SdDistributedLaunchIssue?) = store.setLaunchIssue(issue)
    fun dismissReuseWarning() { _reuseWarning.value = null }

    /** Planner output for the current form state; feed `.runtimeConfig` to the launch builders. */
    fun currentRuntime(): SdMasterRuntime = store.currentRuntime()
    fun runtimeConfig(): SdDistributedRuntimeConfig = store.currentRuntime().runtimeConfig

    // Helpers
    fun addDiscoveredWorker(found: SdDiscoveredWorker) { viewModelScope.launch(Dispatchers.IO) { store.addDiscoveredWorker(found) } }
    fun toggleWorker(worker: SdDistributedWorkerEntity) { viewModelScope.launch(Dispatchers.IO) { store.setWorkerEnabled(worker) } }
    fun deleteWorker(id: Long) { viewModelScope.launch(Dispatchers.IO) { store.deleteWorker(id) } }

    /** Add/edit a Helper; [onDone] runs on the main thread with false when the host was blank. */
    fun saveWorker(
        editingId: Long?,
        host: String,
        port: String,
        name: String,
        ram: String,
        threads: String,
        backend: String,
        onDone: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { store.saveWorker(editingId, host, port, name, ram, threads, backend) }
            onDone(ok)
        }
    }

    // Templates
    fun saveTemplate(name: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onDone(withContext(Dispatchers.IO) { store.saveTemplate(name) })
        }
    }

    fun loadTemplate(template: SdDistributedTemplateEntity) = store.loadTemplate(template)
    fun deleteTemplate(id: Long) { viewModelScope.launch(Dispatchers.IO) { store.deleteTemplate(id) } }

    /**
     * Applies a pending `VideoReuseTarget.DISTRIBUTED` handoff: persists the restored draft first
     * and clears the handoff only afterwards, so a transient failure retries on the next visit.
     */
    fun consumeVideoReuseHandoff() {
        if (state.value.mode != SdMasterMode.VIDEO) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val handoff = withContext(Dispatchers.IO) { VideoReuseHandoffStore.peek(app) }
            if (handoff?.target != VideoReuseTarget.DISTRIBUTED) return@launch
            try {
                val base = withContext(Dispatchers.IO) { store.currentSavedSettings() }
                val workers = withContext(Dispatchers.IO) { store.enabledWorkers() }
                val read = withContext(Dispatchers.IO) { VideoReuseHandoffStore.readPayload(app, handoff) }
                val payload = read?.payload
                if (payload == null) {
                    _reuseWarning.value = localizedWarnings(read?.warnings.orEmpty())
                        ?: app.getString(R.string.video_detail_reuse_failed)
                    return@launch
                }
                val sources = resolveVideoReuseSources(app, payload)
                val restored = VideoReuseDraftAdapter.toDistributedSettings(
                    payload = payload,
                    baseSettings = base,
                    currentWorkers = workers.toVideoReuseWorkers(),
                    verifiedSources = sources
                )
                val id = withContext(Dispatchers.IO) { store.replaceAndPersist(restored.settings) }
                check(id > 0L) { "distributed video reuse draft was not persisted" }
                _reuseWarning.value = localizedWarnings(restored.warnings)
                withContext(Dispatchers.IO) { VideoReuseHandoffStore.complete(app, handoff) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _reuseWarning.value = app.getString(R.string.video_detail_reuse_failed)
            }
        }
    }

    private fun localizedWarnings(warnings: List<VideoReuseWarning>): String? {
        if (warnings.isEmpty()) return null
        val spanish = getApplication<Application>().resources.configuration.locales[0]?.language
            .equals("es", ignoreCase = true)
        return warnings.joinToString(separator = "\n") { it.message(spanish) }
    }

    override fun onCleared() {
        store.flushDetached(detachedScope)
        // Keep the service's monitor/runtime alive while a run is using it.
        if (!isSdDistributedRunActive()) SdDistributedService.stopMasterConnectionMonitor()
        super.onCleared()
    }

    private companion object {
        const val KEY_MODE = "sd_master_mode"
        const val KEY_STEP = "sd_master_step"
    }
}
