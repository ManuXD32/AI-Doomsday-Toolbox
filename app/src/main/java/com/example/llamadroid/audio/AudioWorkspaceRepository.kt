package com.example.llamadroid.audio

import android.content.Context
import android.net.Uri
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.AudioModelSupport
import com.example.llamadroid.audio.library.AudioLibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import com.example.llamadroid.data.model.library.modelDeletionOperationMutex
import java.util.UUID

/**
 * Persistence and lifecycle facade used by the Audio workspace.
 *
 * Model files remain owned by the model-library layer. This repository owns
 * only voice derivatives, generation jobs, output files, and their metadata.
 */
class AudioWorkspaceRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val appContext = context.applicationContext
    private val dao: AudioDao = database.audioDao()
    private val libraryRepository = AudioLibraryRepository(appContext, database)

    fun adapterInfos(): List<AudioAdapterInfo> =
        AudioAdapterRegistry.forContext(appContext).descriptors()

    fun adapterInfo(model: AudioModelDescriptor): AudioAdapterInfo? =
        AudioAdapterRegistry.forContext(appContext).describe(model)

    fun observeVoiceProfiles(): Flow<List<AudioVoiceProfile>> =
        dao.observeVoiceProfiles()
            .onStart { initializeRuntime() }
            .map { rows -> rows.map(AudioVoiceProfileEntity::toProfile) }

    suspend fun getVoiceProfile(id: String): AudioVoiceProfile? =
        dao.getVoiceProfile(id)?.toProfile()

    /**
     * Retain an advanced companion choice on the main model row. This keeps
     * shared-file deletion protection and future drafts aligned with the
     * explicit component selected by the user.
     */
    suspend fun persistSelectedCompanion(modelPath: String, companionPath: String): Boolean {
        val main = database.modelDao().getModelByPath(modelPath) ?: return false
        val companion = database.modelDao().getModelByPath(companionPath) ?: return false
        if (main.type != ModelType.LLAMA_TTS || companion.type != ModelType.LLAMA_TTS_COMPANION) {
            return false
        }
        val mainDescriptor = AudioModelSupport.descriptorForModel(main, appContext) ?: return false
        val companionDescriptor = AudioModelSupport.descriptorForModel(companion, appContext) ?: return false
        if (companionDescriptor.role != AudioModelSupport.ROLE_MMProj) return false
        if (mainDescriptor.family != AudioModelSupport.FAMILY_CUSTOM_TTS &&
            companionDescriptor.family != mainDescriptor.family
        ) return false
        if (mainDescriptor.family == AudioModelSupport.FAMILY_POCKET_TTS &&
            mainDescriptor.language != companionDescriptor.language
        ) return false
        return database.modelDao().updateAudioCompanionPath(main.path, companion.path) > 0
    }

    suspend fun importVoice(uri: Uri, options: AudioVoiceImportOptions): AudioVoiceProfile {
        val profile = try {
            AudioVoiceAssetStore.import(appContext, uri, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IllegalArgumentException(localizedError(error), error)
        }
        dao.insertVoiceProfile(AudioVoiceProfileEntity.fromProfile(profile))
        return profile
    }

    suspend fun renameVoice(id: String, name: String): Boolean {
        val existing = dao.getVoiceProfile(id) ?: return false
        val clean = name.trim()
        require(clean.isNotBlank()) { "Voice name is required" }
        dao.updateVoiceProfile(existing.copy(name = clean, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun deleteVoice(id: String): Boolean = withContext(Dispatchers.IO) {
        modelDeletionOperationMutex.withLock {
            val profile = dao.getVoiceProfile(id) ?: return@withLock false
            val activeReference = dao.getJobsForVoice(id).firstOrNull {
                it.status !in AudioJobStatuses.terminal
            }
            require(activeReference == null) { "Voice is used by an active audio job" }
            val root = AudioWorkspaceStorage.voiceRoot(appContext)
            deleteIfOwned(root, profile.originalPath)
            deleteIfOwned(root, profile.normalizedPath)
            deleteIfOwned(root, profile.denoisedPath)
            dao.deleteVoiceProfile(id)
            true
        }
    }

    suspend fun enqueueGeneration(request: AudioGenerationRequest, startService: Boolean = true): String = withContext(Dispatchers.IO) {
        initializeRuntime()
        modelDeletionOperationMutex.withLock {
            val normalized = try {
                request.validate()
            } catch (error: Throwable) {
                throw IllegalArgumentException(localizedError(error), error)
            }
            normalized.requireAvailableAssets()
            normalized.voiceProfileId?.let { voiceId ->
                val voice = dao.getVoiceProfile(voiceId) ?: error("Voice reference profile is missing")
                require(java.io.File(voice.originalPath).isFile) { "Voice reference file is missing" }
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val job = AudioGenerationJobEntity(
                id = id,
                adapterId = normalized.model.adapterId,
                family = normalized.model.family,
                modelId = normalized.model.id,
                modelPath = normalized.model.modelPath,
                companionPath = normalized.model.companionPath,
                modelDisplayName = normalized.model.displayName,
                modelLanguage = normalized.model.language,
                language = normalized.language,
                voiceStyle = normalized.voiceStyle,
                voiceProfileId = normalized.voiceProfileId,
                referenceAudioPath = normalized.referenceAudioPath,
                text = normalized.text,
                sourceUri = normalized.sourceUri,
                sourceName = normalized.sourceName,
                speed = normalized.speed,
                totalSteps = normalized.totalSteps,
                temperature = normalized.temperature,
                topP = normalized.topP,
                topK = normalized.topK,
                seed = normalized.seed,
                maxFrames = normalized.maxFrames,
                runtimeThreads = normalized.runtimeThreads,
                batchSize = normalized.batchSize,
                microBatchSize = normalized.microBatchSize,
                outputFormat = normalized.outputFormat,
                outputSampleRate = normalized.outputSampleRate,
                chunkSize = normalized.chunkSize,
                normalizeReference = normalized.normalizeReference,
                denoiseReference = normalized.denoiseReference,
                trimStartMs = normalized.trimStartMs,
                trimEndMs = normalized.trimEndMs,
                includeMetadata = normalized.includeMetadata,
                status = AudioJobStatuses.QUEUED,
                stageMessage = appContext.getString(R.string.audio_runtime_status_queued),
                createdAt = now,
                updatedAt = now,
                metadataJson = normalized.metadataJson
            )
            dao.insertJob(job)
            if (startService) AudioGenerationService.start(appContext)
            id
        }
    }

    fun observeJobs(speechOnly: Boolean = false): Flow<List<AudioJobSnapshot>> =
        dao.observeUiJobs(speechOnly = speechOnly)
            .onStart { initializeRuntime() }
            .map { rows -> rows.map(AudioGenerationJobEntity::snapshot) }

    suspend fun getJob(id: String): AudioJobSnapshot? = dao.getJob(id)?.snapshot()

    suspend fun cancelGeneration(id: String? = null) {
        initializeRuntime()
        if (id == null) {
            if (dao.getActiveJob() != null) AudioGenerationService.cancel(appContext)
        } else {
            val job = dao.getJob(id) ?: return
            if (job.status == AudioJobStatuses.QUEUED) {
                val cancelled = dao.cancelQueuedJob(id, appContext.getString(R.string.audio_runtime_status_cancelled))
                // The foreground coordinator may have claimed the row since
                // the read above. Deliver cancellation to that active claim.
                if (cancelled == 0) AudioGenerationService.cancel(appContext, id)
            } else if (job.status in setOf(AudioJobStatuses.PREPARING, AudioJobStatuses.RUNNING, AudioJobStatuses.CANCELLING)) {
                AudioGenerationService.cancel(appContext, id)
            }
        }
    }

    suspend fun retryJob(id: String): Boolean = withContext(Dispatchers.IO) {
        initializeRuntime()
        val changed = modelDeletionOperationMutex.withLock {
            val job = dao.getJob(id) ?: return@withLock false
            job.toRequest().requireAvailableAssets()
            dao.requeueJob(id, appContext.getString(R.string.audio_runtime_status_queued)) > 0
        }
        if (changed) AudioGenerationService.start(appContext)
        changed
    }

    suspend fun recoverStaleJobs(): Int = initializeRuntime()

    fun observeHistory(): Flow<List<AudioHistoryItem>> =
        libraryRepository.observeHistory()
            .onStart { initializeRuntime() }

    suspend fun listHistory(): List<AudioHistoryItem> {
        initializeRuntime()
        return libraryRepository.listHistory()
    }

    suspend fun deleteHistory(id: String): Boolean {
        initializeRuntime()
        val result = libraryRepository.deleteItem(id)
        if (result.failures.isNotEmpty()) {
            throw IllegalStateException(result.failures.first().code.name)
        }
        return result.succeededIds.contains(id)
    }

    private fun localizedError(error: Throwable): String {
        val raw = error.message.orEmpty().lowercase()
        return when {
            "text or a source" in raw || "text is required" in raw ->
                appContext.getString(R.string.audio_runtime_error_empty_text)
            "unsupported voice audio format" in raw || "voice reference" in raw ->
                appContext.getString(R.string.audio_runtime_error_voice_invalid)
            "ffmpeg" in raw -> appContext.getString(R.string.audio_runtime_error_ffmpeg_missing)
            "model path" in raw || "model file" in raw ->
                appContext.getString(R.string.audio_runtime_error_model_missing)
            "companion" in raw || "projector" in raw ->
                appContext.getString(R.string.audio_runtime_error_companion_missing)
            else -> appContext.getString(R.string.audio_runtime_error_generic)
        }
    }

    private suspend fun initializeRuntime(): Int =
        AudioRuntimeCoordinator.initialize(appContext, dao)

    companion object {
        fun start(context: Context) = AudioGenerationService.start(context.applicationContext)
    }
}
