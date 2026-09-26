package com.example.llamadroid.ui.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.llamadroid.audio.AudioAdapterIds
import com.example.llamadroid.audio.AudioGenerationRequest
import com.example.llamadroid.audio.AudioHistoryItem
import com.example.llamadroid.audio.AudioJobSnapshot
import com.example.llamadroid.audio.AudioJobStatuses
import com.example.llamadroid.audio.AudioModelDescriptor
import com.example.llamadroid.audio.AudioModelFamilies
import com.example.llamadroid.audio.AudioVoiceImportOptions as RuntimeVoiceImportOptions
import com.example.llamadroid.audio.AudioWorkspaceRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.AudioModelSupport
import com.example.llamadroid.data.model.CuratedModelBundleRegistry
import com.example.llamadroid.onnx.resolveSupertonicVoices
import com.example.llamadroid.onnx.supertonicLanguageCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Production bridge from Compose to the repository-owned audio job/voice/history state. */
class AudioWorkspaceRuntimeController(context: Context) : AudioWorkspaceController {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val repository = AudioWorkspaceRepository(appContext, database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AudioWorkspaceUiState())
    private val descriptors = linkedMapOf<String, AudioModelDescriptor>()
    private var activeJobId: String? = null
    private var modelJob: Job? = null
    private var voiceJob: Job? = null
    private var runtimeJob: Job? = null
    private var recordingJob: Job? = null
    private var voicePreviewJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var previewRequestToken = 0L
    private var playerGeneration = 0L
    private val historyTitles = linkedMapOf<String, String>()
    private val historyTitlePreferences = appContext.getSharedPreferences("audio_history_titles", Context.MODE_PRIVATE)

    override val state: StateFlow<AudioWorkspaceUiState> = mutableState.asStateFlow()

    init {
        historyTitlePreferences.all.forEach { (id, value) ->
            if (value is String && value.isNotBlank()) historyTitles[id] = value
        }
        modelJob = scope.launch {
            database.modelDao().getModelsByTypes(
                listOf(
                    ModelType.ONNX_TTS,
                    ModelType.LLAMA_TTS,
                    ModelType.LLAMA_TTS_COMPANION
                )
            )
                .catch { error ->
                    if (error is CancellationException) throw error
                    setLoadError(AudioWorkspaceLoadError.MODELS)
                    emit(emptyList())
                }
                .collect { rows -> publishModels(rows) }
        }
        voiceJob = scope.launch {
            repository.observeVoiceProfiles()
                .catch { error ->
                    if (error is CancellationException) throw error
                    setLoadError(AudioWorkspaceLoadError.VOICES)
                    emit(emptyList())
                }
                .collect { profiles ->
                    mutableState.value = mutableState.value.copy(
                        voiceProfiles = profiles.map { profile ->
                            AudioVoiceProfileUi(
                                id = profile.id,
                                name = profile.name,
                                language = profile.language,
                                durationSeconds = profile.durationMs / 1000f,
                                sourceLabel = profile.sourceUri ?: profile.originalPath,
                                referenceAudioUri = profile.preferredAudioPath
                            )
                        }
                    )
                }
        }
        runtimeJob = scope.launch {
            repository.observeJobs(speechOnly = true)
                .catch { error ->
                    if (error is CancellationException) throw error
                    setLoadError(AudioWorkspaceLoadError.JOBS)
                    emit(emptyList())
                }
                .collect { jobs -> publishJob(jobs) }
        }
    }

    override fun generate(draft: AudioWorkspaceDraft) {
        val baseDescriptor = descriptors[draft.modelId]
        val modelOption = mutableState.value.models.firstOrNull { it.id == draft.modelId }
        val selectedCompanion = draft.companionPath
            ?.takeIf { path -> modelOption?.companionOptions?.contains(path) == true }
            ?: modelOption?.companionOptions?.singleOrNull()
            ?: baseDescriptor?.companionPath
        val descriptor = baseDescriptor?.copy(companionPath = selectedCompanion)
        if (descriptor == null) {
            reportControllerFailure(IllegalStateException())
            return
        }
        if (descriptor.family != AudioModelFamilies.SUPERTONIC && descriptor.companionPath.isNullOrBlank()) {
            reportControllerFailure(IllegalStateException())
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(
                job = AudioJobUiState(status = AudioJobStatus.PREPARING)
            )
            try {
                val voicePath = if (descriptor.family == AudioModelFamilies.SUPERTONIC) {
                    null
                } else {
                    draft.voiceProfileId?.let { id ->
                        repository.getVoiceProfile(id)?.preferredAudioPath
                            ?: throw IllegalStateException()
                    }
                }
                val request = AudioGenerationRequest(
                    model = descriptor,
                    text = draft.text.takeIf { it.isNotBlank() },
                    sourceUri = draft.sourceUri,
                    sourceName = draft.sourceName,
                    voiceProfileId = draft.voiceProfileId,
                    referenceAudioPath = voicePath,
                    language = draft.language,
                    voiceStyle = draft.voiceStyle,
                    speed = draft.speed,
                    totalSteps = draft.totalSteps,
                    temperature = draft.temperature,
                    topP = draft.topP,
                    topK = draft.topK,
                    seed = draft.seed.toLongOrNull(),
                    maxFrames = draft.maxTokens,
                    runtimeThreads = draft.runtimeThreads,
                    batchSize = draft.batchSize,
                    microBatchSize = draft.microBatchSize,
                    outputFormat = draft.outputFormat,
                    outputSampleRate = draft.outputSampleRate,
                    chunkSize = draft.chunkSize,
                    normalizeReference = draft.normalizeReference,
                    denoiseReference = draft.denoiseReference,
                    trimStartMs = draft.trimStartMs.toLong(),
                    trimEndMs = draft.trimEndMs.toLong().takeIf { it > 0 },
                    includeMetadata = draft.includeMetadata,
                    metadataJson = buildGenerationMetadata(descriptor, draft)
                )
                // Keep the verified choice on the model row as well as in the
                // persisted generation request. Model-library deletion can
                // then protect an inferred/shared companion after the user
                // leaves this workspace.
                selectedCompanion?.let {
                    repository.persistSelectedCompanion(descriptor.modelPath, it)
                }
                activeJobId = repository.enqueueGeneration(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun retryGeneration() {
        val id = activeJobId
        if (id.isNullOrBlank()) {
            reportControllerFailure(IllegalStateException())
            return
        }
        scope.launch {
            try {
                if (!repository.retryJob(id)) reportControllerFailure(IllegalStateException())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun cancelGeneration() {
        scope.launch {
            try {
                repository.cancelGeneration(activeJobId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun importTextDocument(uri: Uri) = Unit

    override fun startVoiceRecording() {
        if (recorder != null) return
        val output = File(appContext.cacheDir, "audio_voice_${System.currentTimeMillis()}.m4a")
        var instance: MediaRecorder? = null
        try {
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
            instance = created
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            recorder = created
            recordingFile = output
            mutableState.value = mutableState.value.copy(isRecording = true, recordingSeconds = 0)
            recordingJob = scope.launch {
                while (true) {
                    delay(1_000)
                    mutableState.value = mutableState.value.copy(recordingSeconds = mutableState.value.recordingSeconds + 1)
                }
            }
        } catch (error: CancellationException) {
            runCatching { instance?.release() }
            throw error
        } catch (error: Throwable) {
            runCatching { instance?.reset() }
            runCatching { instance?.release() }
            reportControllerFailure(error)
        }
    }

    override fun stopVoiceRecording() {
        val current = recorder ?: return
        recordingJob?.cancel()
        recordingJob = null
        var stopError: Throwable? = null
        try {
            current.stop()
        } catch (error: Throwable) {
            stopError = error
        } finally {
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        recorder = null
        val output = recordingFile
        recordingFile = null
        val uri = output?.takeIf { it.isFile && it.length() > 0L }?.let {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", it)
        }
        mutableState.value = mutableState.value.copy(
            isRecording = false,
            recordingSeconds = 0,
            pendingRecordedUri = uri?.toString()
        )
        when {
            stopError != null -> reportControllerFailure(stopError)
            uri == null -> reportControllerFailure(IllegalStateException())
        }
    }

    override fun importVoice(uri: Uri, options: AudioVoiceImportOptions) {
        scope.launch {
            try {
                repository.importVoice(
                    uri,
                    RuntimeVoiceImportOptions(
                        name = options.name,
                        language = options.language,
                        trimStartMs = options.trimStartMs.toLong(),
                        trimEndMs = options.trimEndMs.toLong().takeIf { it > 0 },
                        normalize = options.normalize,
                        denoise = options.denoise
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
            mutableState.value = mutableState.value.copy(pendingRecordedUri = null)
        }
    }

    override fun deleteVoice(profileId: String) {
        // A profile can be removed while its preview is still playing or while
        // its path is being resolved. Cancel both cases before the asset is
        // deleted so no stale MediaPlayer callback can repopulate the state.
        stopVoicePreview()
        scope.launch {
            try {
                repository.deleteVoice(profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun renameVoice(profileId: String, name: String) {
        scope.launch {
            try {
                repository.renameVoice(profileId, name)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun previewVoice(profileId: String) {
        // Voice previews use the same local MediaPlayer path as history once the profile has a
        // derived asset. The profile stream is the source of truth; no raw audio is logged.
        voicePreviewJob?.cancel()
        voicePreviewJob = null
        stopCurrentPlayback(clearVoiceState = true)
        val requestToken = ++previewRequestToken
        voicePreviewJob = scope.launch {
            try {
                val profile = repository.getVoiceProfile(profileId)
                if (requestToken != previewRequestToken) return@launch
                if (profile == null) {
                    reportControllerFailure(IllegalArgumentException())
                    return@launch
                }
                playPath(profile.preferredAudioPath, profile.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestToken == previewRequestToken) {
                    clearVoicePlayback()
                    reportControllerFailure(error)
                }
            }
        }
    }

    override fun pauseVoicePreview(profileId: String) {
        if (mutableState.value.activeVoiceProfileId != profileId ||
            mutableState.value.voicePlaybackState != AudioVoicePlaybackState.PLAYING
        ) return
        scope.launch {
            try {
                check(player != null) { "Voice preview player is unavailable" }
                player?.pause()
                mutableState.value = mutableState.value.copy(
                    voicePlaybackState = AudioVoicePlaybackState.PAUSED
                )
            } catch (error: Throwable) {
                stopCurrentPlayback(clearVoiceState = true)
                reportControllerFailure(error)
            }
        }
    }

    override fun resumeVoicePreview(profileId: String) {
        if (mutableState.value.activeVoiceProfileId != profileId ||
            mutableState.value.voicePlaybackState != AudioVoicePlaybackState.PAUSED
        ) return
        scope.launch {
            try {
                check(player != null) { "Voice preview player is unavailable" }
                player?.start()
                mutableState.value = mutableState.value.copy(
                    voicePlaybackState = AudioVoicePlaybackState.PLAYING
                )
            } catch (error: Throwable) {
                stopCurrentPlayback(clearVoiceState = true)
                reportControllerFailure(error)
            }
        }
    }

    override fun stopVoicePreview() {
        previewRequestToken++
        voicePreviewJob?.cancel()
        voicePreviewJob = null
        stopCurrentPlayback(clearVoiceState = true)
    }

    override fun playHistory(itemId: String) {
        stopVoicePreview()
        val item = mutableState.value.history.firstOrNull { it.id == itemId }
        if (item == null) {
            reportControllerFailure(IllegalArgumentException())
            return
        }
        item.audioPath?.let { playPath(it, profileId = null) }
            ?: reportControllerFailure(IllegalStateException())
    }

    override fun pauseHistory(itemId: String) {
        runCatching { player?.pause() }
    }

    override fun renameHistory(itemId: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank()) {
            reportControllerFailure(IllegalArgumentException())
            return
        }
        if (mutableState.value.history.none { it.id == itemId }) {
            reportControllerFailure(IllegalArgumentException())
            return
        }
        historyTitles[itemId] = clean
        historyTitlePreferences.edit().putString(itemId, clean).apply()
        mutableState.value = mutableState.value.copy(
            history = mutableState.value.history.map { item ->
                if (item.id == itemId) item.copy(title = clean) else item
            }
        )
    }

    override fun shareHistory(itemId: String) {
        val file = mutableState.value.history.firstOrNull { it.id == itemId }?.audioPath?.let(::File)
        if (file == null || !file.isFile) {
            reportControllerFailure(IllegalStateException())
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error -> reportControllerFailure(error) }
    }

    override fun exportHistory(itemId: String, destination: Uri) {
        scope.launch {
            try {
                val path = mutableState.value.history.firstOrNull { it.id == itemId }?.audioPath
                    ?: error("")
                val source = File(path)
                require(source.isFile)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destination)?.use { output ->
                        source.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    override fun deleteHistory(itemId: String) {
        scope.launch {
            try {
                repository.deleteHistory(itemId)
                historyTitles.remove(itemId)
                historyTitlePreferences.edit().remove(itemId).apply()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportControllerFailure(error)
            }
        }
    }

    fun close() {
        stopVoicePreview()
        if (recorder != null) stopVoiceRecording()
        recordingJob?.cancel()
        runCatching { recorder?.release() }
        modelJob?.cancel()
        voiceJob?.cancel()
        runtimeJob?.cancel()
        voicePreviewJob?.cancel()
        scope.cancel()
    }

    private fun publishModels(rows: List<ModelEntity>) {
        descriptors.clear()
        val models = rows.mapNotNull { row ->
            val descriptor = descriptorFor(row) ?: return@mapNotNull null
            val companionOptions = compatibleCompanionPaths(row, rows)
            val effectiveDescriptor = descriptor.copy(
                companionPath = defaultCompanionPath(row, descriptor, rows, companionOptions)
            )
            descriptors[effectiveDescriptor.id] = effectiveDescriptor
            toUiModel(effectiveDescriptor, companionOptions)
        }
        mutableState.value = mutableState.value.copy(models = models)
    }

    private fun descriptorFor(row: ModelEntity): AudioModelDescriptor? {
        if (row.type == ModelType.LLAMA_TTS_COMPANION) return null
        // Installed rows carry their family/language/component metadata. Do
        // not infer identity from a renamed filename: unresolved legacy rows
        // remain custom TTS until the user explicitly classifies them.
        val persisted = AudioModelSupport.descriptorForModel(row, appContext)
        return when {
            row.type == ModelType.ONNX_TTS -> AudioModelDescriptor(
                id = "onnx:${row.filename}",
                family = AudioModelFamilies.SUPERTONIC,
                modelPath = row.path,
                displayName = row.filename,
                adapterId = AudioAdapterIds.SUPERTONIC
            )
            row.type == ModelType.LLAMA_TTS -> AudioModelDescriptor(
                id = "audio:${row.filename}",
                family = persisted?.family ?: AudioModelSupport.FAMILY_CUSTOM_TTS,
                modelPath = row.path,
                companionPath = row.mmprojPath,
                displayName = row.filename,
                adapterId = AudioAdapterIds.LLAMA_CLI,
                language = persisted?.language,
                metadataJson = AudioModelSupport.portableMetadata(
                    AudioModelSupport.Descriptor(
                        family = persisted?.family ?: AudioModelSupport.FAMILY_CUSTOM_TTS,
                        role = persisted?.role ?: AudioModelSupport.ROLE_MAIN,
                        language = persisted?.language,
                        modelType = row.type
                    ),
                    artifactIdentity = row.audioArtifactIdentity
                )
            )
            else -> null
        }
    }

    private fun compatibleCompanionPaths(row: ModelEntity, allRows: List<ModelEntity>): List<String> {
        val main = AudioModelSupport.descriptorForModel(row, appContext) ?: return emptyList()
        val companionRows = allRows.asSequence()
            .filter { it.type == ModelType.LLAMA_TTS_COMPANION }
            .mapNotNull { candidate ->
                AudioModelSupport.descriptorForModel(candidate, appContext)?.let { candidate to it }
            }
            .filter { (_, companion) -> companion.role == AudioModelSupport.ROLE_MMProj }
            .toList()

        // A persisted association is an explicit advanced choice. It is still
        // checked against an installed companion row and its durable metadata;
        // stale paths and filename-only guesses are never surfaced.
        val explicit = row.mmprojPath
            ?.takeIf { it.isNotBlank() }
            ?.let { associatedPath ->
                companionRows.firstOrNull { (candidate, _) -> samePath(candidate.path, associatedPath) }
                    ?.takeIf { (_, companion) -> explicitCompanionMatches(main, companion) }
                    ?.first
                    ?.path
            }

        // Unknown/custom components cannot be paired merely because both rows
        // have the fallback custom family. They must carry the explicit stored
        // association above (or be reclassified by the model editor).
        if (main.family == AudioModelSupport.FAMILY_CUSTOM_TTS) return listOfNotNull(explicit)

        // Every structurally compatible installed component remains available
        // in the advanced picker. Curated identities only affect the default
        // choice below; they must not hide a valid imported/renamed model.
        val compatible = companionRows.asSequence()
            .filter { (candidate, companion) ->
                candidate.path != explicit &&
                    structurallyCompatible(main, companion)
            }
            .map { (candidate, _) -> candidate.path }
            .distinct()
            .toList()
        return (listOfNotNull(explicit) + compatible).distinct()
    }

    private fun defaultCompanionPath(
        row: ModelEntity,
        descriptor: AudioModelDescriptor,
        allRows: List<ModelEntity>,
        options: List<String>
    ): String? {
        descriptor.companionPath?.let { stored ->
            options.firstOrNull { candidate -> samePath(candidate, stored) }?.let { return it }
        }
        options.singleOrNull()?.let { return it }
        val curatedIdentities = curatedCompanionIdentities(row)
        if (curatedIdentities.isEmpty()) return null
        return allRows.asSequence()
            .filter { it.type == ModelType.LLAMA_TTS_COMPANION }
            .filter { row -> row.audioArtifactIdentity?.let { it in curatedIdentities } == true }
            .map { it.path }
            .firstOrNull { candidate -> options.any { option -> samePath(option, candidate) } }
    }

    private fun explicitCompanionMatches(
        main: AudioModelSupport.Descriptor,
        companion: AudioModelSupport.Descriptor
    ): Boolean {
        if (companion.role != AudioModelSupport.ROLE_MMProj) return false
        if (main.family == AudioModelSupport.FAMILY_CUSTOM_TTS) return true
        if (companion.family != main.family || companion.family == AudioModelSupport.FAMILY_CUSTOM_TTS) return false
        return languagesCompatible(main, companion)
    }

    private fun structurallyCompatible(
        main: AudioModelSupport.Descriptor,
        companion: AudioModelSupport.Descriptor
    ): Boolean {
        if (companion.family == AudioModelSupport.FAMILY_CUSTOM_TTS || companion.family != main.family) return false
        return languagesCompatible(main, companion)
    }

    private fun languagesCompatible(
        main: AudioModelSupport.Descriptor,
        companion: AudioModelSupport.Descriptor
    ): Boolean = if (main.family == AudioModelSupport.FAMILY_POCKET_TTS) {
        main.language != null && companion.language != null && main.language == companion.language
    } else {
        main.language == null || companion.language == null || main.language == companion.language
    }

    private fun curatedCompanionIdentities(row: ModelEntity): Set<String> {
        val identity = row.audioArtifactIdentity?.takeIf { it.isNotBlank() } ?: return emptySet()
        return CuratedModelBundleRegistry.bundles
            .asSequence()
            .filter { bundle -> bundle.files.any { it.type == ModelType.LLAMA_TTS && it.artifactIdentity == identity } }
            .flatMap { bundle ->
                bundle.files.asSequence()
                    .filter { it.type == ModelType.LLAMA_TTS_COMPANION }
                    .map { it.artifactIdentity }
            }
            .toSet()
    }

    private fun samePath(first: String, second: String): Boolean =
        first == second || runCatching { File(first).canonicalPath == File(second).canonicalPath }.getOrDefault(false)

    private fun toUiModel(
        descriptor: AudioModelDescriptor,
        companionOptions: List<String>
    ): AudioModelOption {
        val isSupertonic = descriptor.family == AudioModelFamilies.SUPERTONIC
        val isPocket = descriptor.family == AudioModelFamilies.POCKET_TTS
        val adapterInfo = repository.adapterInfo(descriptor)
        val unresolvedReason = when {
            isPocket && descriptor.language.isNullOrBlank() ->
                appContext.getString(com.example.llamadroid.R.string.audio_workspace_model_language_missing)
            descriptor.family == AudioModelFamilies.CUSTOM && companionOptions.isEmpty() ->
                appContext.getString(com.example.llamadroid.R.string.audio_workspace_custom_model_unresolved)
            else -> null
        }
        val capabilities = AudioModelCapabilities(
            supportsLanguageSelection = if (isPocket) false else adapterInfo?.supportsLanguageSelection ?: true,
            supportsReferenceAudio = adapterInfo?.supportsReferenceAudio ?: !isSupertonic,
            referenceAudioRequired = isPocket || adapterInfo?.referenceAudioRequired == true,
            supportsTemperature = if (isPocket) false else adapterInfo?.supportsTemperature ?: !isSupertonic,
            supportsTopP = if (isPocket) false else adapterInfo?.supportsTopP ?: !isSupertonic,
            supportsTopK = if (isPocket) false else adapterInfo?.supportsTopK ?: !isSupertonic,
            supportsSeed = if (isPocket) true else adapterInfo?.supportsSeed ?: !isSupertonic,
            supportsSpeed = adapterInfo?.supportsSpeed ?: true,
            supportsMaxTokens = adapterInfo?.supportsMaxFrames ?: !isSupertonic,
            supportsOutputSampleRate = adapterInfo?.supportsOutputSampleRate ?: !isSupertonic,
            supportedLanguages = when {
                isSupertonic -> supertonicLanguageCodes
                isPocket -> listOfNotNull(descriptor.language)
                else -> adapterInfo?.supportedLanguages.orEmpty()
            }
        )
        return AudioModelOption(
            id = descriptor.id,
            displayName = descriptor.displayName,
            family = when (descriptor.family) {
                AudioModelFamilies.SUPERTONIC -> AudioModelFamily.SUPERTONIC
                AudioModelFamilies.POCKET_TTS -> AudioModelFamily.POCKET_TTS
                AudioModelFamilies.QWEN3_TTS -> AudioModelFamily.QWEN3_TTS
                else -> AudioModelFamily.CUSTOM
            },
            backendLabel = descriptor.adapterId,
            installState = AudioModelInstallState.INSTALLED,
            description = "",
            componentSummary = descriptor.companionPath ?: descriptor.modelPath,
            capabilities = capabilities,
            components = listOfNotNull(descriptor.modelPath, descriptor.companionPath),
            companionOptions = companionOptions,
            bundledVoiceStyles = if (isSupertonic) runCatching { resolveSupertonicVoices(File(descriptor.modelPath)) }.getOrDefault(emptyList()) else emptyList(),
            unresolvedReason = unresolvedReason
        )
    }

    private fun publishJob(jobs: List<AudioJobSnapshot>) {
        val current = activeJobId?.let { id -> jobs.firstOrNull { it.id == id } }
            ?: jobs.firstOrNull { it.status in setOf(AudioJobStatuses.PREPARING, AudioJobStatuses.RUNNING, AudioJobStatuses.CANCELLING) }
            ?: jobs.firstOrNull { it.status == AudioJobStatuses.QUEUED }
            ?: jobs.filter { it.status in AudioJobStatuses.terminal }
                .maxByOrNull { it.updatedAt }
        if (current == null) return
        activeJobId = current.id
        mutableState.value = mutableState.value.copy(job = AudioJobUiState(
            status = current.status.toUiStatus(),
            stageLabel = current.stageMessage,
            progress = current.progress,
            message = current.errorMessage,
            resultId = current.outputPath,
            completedChunks = current.completedChunks,
            totalChunks = current.totalChunks
        ))
    }

    private fun playPath(path: String, profileId: String?) {
        stopCurrentPlayback(clearVoiceState = true)
        val file = File(path)
        if (!file.isFile) {
            reportControllerFailure(IllegalStateException())
            return
        }
        val next = MediaPlayer()
        val generation = ++playerGeneration
        try {
            next.setDataSource(file.absolutePath)
            next.prepare()
            next.setOnCompletionListener {
                val isCurrent = player === it && playerGeneration == generation
                if (isCurrent) {
                    player = null
                    clearVoicePlayback()
                }
                it.release()
            }
            next.setOnErrorListener { failed, _, _ ->
                val isCurrent = player === failed && playerGeneration == generation
                if (isCurrent) {
                    player = null
                    clearVoicePlayback()
                    reportControllerFailure(IllegalStateException())
                }
                failed.release()
                true
            }
            player = next
            next.start()
            if (profileId == null) {
                clearVoicePlayback()
            } else {
                mutableState.value = mutableState.value.copy(
                    activeVoiceProfileId = profileId,
                    voicePlaybackState = AudioVoicePlaybackState.PLAYING
                )
            }
        } catch (error: Throwable) {
            if (player === next) {
                player = null
                clearVoicePlayback()
            }
            runCatching { next.release() }
            reportControllerFailure(error)
        }
    }

    private fun stopCurrentPlayback(clearVoiceState: Boolean) {
        playerGeneration++
        val current = player
        player = null
        runCatching { current?.release() }
        if (clearVoiceState) clearVoicePlayback()
    }

    private fun clearVoicePlayback() {
        mutableState.value = mutableState.value.copy(
            activeVoiceProfileId = null,
            voicePlaybackState = AudioVoicePlaybackState.IDLE
        )
    }

    private fun toUiHistory(item: AudioHistoryItem): AudioHistoryItemUi = AudioHistoryItemUi(
        id = item.id,
        title = historyTitles[item.id] ?: item.title,
        modelName = item.modelName,
        voiceName = item.voiceName,
        language = item.language,
        durationSeconds = item.durationMs / 1000f,
        createdAtLabel = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(item.createdAt)),
        audioPath = item.audioPath,
        status = when (item.status) {
            AudioJobStatuses.INTERRUPTED -> AudioHistoryStatus.INTERRUPTED
            AudioJobStatuses.ERROR -> AudioHistoryStatus.FAILED
            else -> AudioHistoryStatus.COMPLETE
        }
    )

    private fun buildGenerationMetadata(
        descriptor: AudioModelDescriptor,
        draft: AudioWorkspaceDraft
    ): String = runCatching {
        JSONObject(descriptor.metadataJson)
            .put("selectedComponents", JSONArray(draft.componentIds))
            .put("effectiveTotalSteps", draft.totalSteps)
            .put("effectiveBatchSize", draft.batchSize)
            .put("effectiveMicroBatchSize", draft.microBatchSize)
            .toString()
    }.getOrElse {
        JSONObject()
            .put("selectedComponents", JSONArray(draft.componentIds))
            .toString()
    }

    private fun reportControllerFailure(error: Throwable) {
        if (error is CancellationException) throw error
        mutableState.value = mutableState.value.copy(
            job = AudioJobUiState(status = AudioJobStatus.ERROR),
            operationError = true
        )
    }

    private fun setLoadError(error: AudioWorkspaceLoadError) {
        mutableState.value = mutableState.value.copy(loadError = error)
    }
}

private fun String.toUiStatus(): AudioJobStatus = when (this) {
    AudioJobStatuses.PREPARING, AudioJobStatuses.QUEUED -> AudioJobStatus.PREPARING
    AudioJobStatuses.RUNNING -> AudioJobStatus.RUNNING
    AudioJobStatuses.CANCELLING -> AudioJobStatus.CANCELLING
    AudioJobStatuses.COMPLETE -> AudioJobStatus.COMPLETE
    AudioJobStatuses.INTERRUPTED, AudioJobStatuses.CANCELLED -> AudioJobStatus.INTERRUPTED
    AudioJobStatuses.ERROR -> AudioJobStatus.ERROR
    else -> AudioJobStatus.IDLE
}
