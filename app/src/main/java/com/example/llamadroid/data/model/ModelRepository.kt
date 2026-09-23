package com.example.llamadroid.data.model

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction

import com.example.llamadroid.R
import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelBackupPolicy
import com.example.llamadroid.data.db.ModelDao
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.db.parseOnnxCapabilities
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TXT2IMG
import com.example.llamadroid.sd.defaultCapabilitiesForFamily
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.sd.resolveSdCompatProfiles
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.SdArtifactInspector
import com.example.llamadroid.sd.SdArtifactRole
import com.example.llamadroid.sd.SdModelFamily
import com.example.llamadroid.sd.SdInspectionCache
import com.example.llamadroid.sd.needsSdArtifactInspection
import com.example.llamadroid.sd.sdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.sd.normalizeSdParamsBackendSpec
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_SDAI_CATALOG_BUNDLE
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_FILE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_HF_TREE_BUNDLE
import com.example.llamadroid.onnx.ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION
import com.example.llamadroid.onnx.OnnxCatalogEntry
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.buildOnnxCatalogStableId
import com.example.llamadroid.onnx.buildOnnxImageGenModelEntity
import com.example.llamadroid.data.model.library.ModelArtifactLifecycle
import com.example.llamadroid.data.model.library.ModelDeletionDependency
import com.example.llamadroid.data.model.library.ModelDeletionFile
import com.example.llamadroid.data.model.library.ModelDeletionPathFailure
import com.example.llamadroid.data.model.library.ModelDeletionPreview
import com.example.llamadroid.data.model.library.ModelDeletionResult
import com.example.llamadroid.data.model.library.ModelDeletionStatus
import com.example.llamadroid.data.model.library.ModelLibraryErrorCode
import com.example.llamadroid.data.model.library.ModelLibraryException
import com.example.llamadroid.data.model.library.ModelClassificationPolicy
import com.example.llamadroid.data.model.library.ModelClassificationSource
import com.example.llamadroid.data.model.library.RoomModelDeletionJournal
import com.example.llamadroid.data.model.library.activeDownloadDependencies
import com.example.llamadroid.data.model.library.activeAudioJobDependencies
import com.example.llamadroid.data.model.library.modelDeletionOperationMutex
import com.example.llamadroid.data.model.library.StableAudioModelLease
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import com.example.llamadroid.data.db.buildOnnxCapabilities
import com.example.llamadroid.data.db.isAudioTtsComponentType
import com.example.llamadroid.data.db.isStableAudioComponentType
import java.util.Locale

/** Repository instances are short-lived across model-manager screens. */
private val catalogDownloadMutex = Mutex()

class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao
) {
    private data class InstalledModelReferenceKey(
        val path: String,
        val key: String,
        val relation: String
    )

    private data class LifecycleProtectionSnapshot(
        val pendingArtifacts: List<PendingModelArtifactEntity>,
        val allProvenance: List<ModelProvenanceEntity>,
        val otherRuntimePaths: List<String>,
        val otherLiteRtPaths: List<String>,
        val activeAudioJobPaths: List<String>,
        val activeDownloadPaths: List<String>
    )

    // Use kotlinx.serialization for API responses to avoid reflection issues with R8
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://huggingface.co/api/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        
    private val hfService = retrofit.create(HuggingFaceService::class.java)
    private val reconciliationMutex = Mutex()

    private data class CatalogDownloadHandle(
        val taskId: String,
        val progressKey: String,
        val url: String,
        val localFilename: String,
        val destFile: File,
        val alreadyInstalled: Boolean = false
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                pruneLegacyPortableModelRows()
                reconcileManagedModelCopiesIfNeeded()
            }.onFailure { error ->
                Log.w("ModelRepository", "Managed model reconciliation skipped: ${error.message}", error)
                DebugLog.log("ModelRepository: Managed model reconciliation skipped: ${error.message}")
            }
        }
    }

    // Use singleton progress to persist across navigation
    val downloadProgress = DownloadProgressHolder.progress

    fun getDownloadedModels(): Flow<List<ModelEntity>> =
        modelDao.getAllModels().onStart {
            pruneLegacyPortableModelRows()
            reconcileManagedModelCopiesIfNeeded()
        }
    
    fun getLLMModels(): Flow<List<ModelEntity>> = modelDao.getModelsByTypes(
        listOf(ModelType.LLM, ModelType.VISION, ModelType.VISION_PROJECTOR, ModelType.EMBEDDING)
    ).onStart {
        pruneLegacyPortableModelRows()
        reconcileManagedModelCopiesIfNeeded()
    }

    fun getModelManagerModels(): Flow<List<ModelEntity>> = modelDao.getModelsByTypes(
        ModelManagerModelTypes.llama
    ).onStart {
        pruneLegacyPortableModelRows()
        reconcileManagedModelCopiesIfNeeded()
    }

    fun getLiteRtAudioModels(): Flow<List<ModelEntity>> = modelDao.getModelsByTypes(
        ModelManagerModelTypes.liteRtAudio
    ).onStart {
        pruneLegacyPortableModelRows()
        reconcileManagedModelCopiesIfNeeded()
    }
    
    /** Result-preserving variant used by model management UI error recovery. */
    suspend fun searchModelsResult(query: String, filter: String? = null): Result<List<HfModelDto>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Enhance query with filter keyword for better results without
                // turning an HTTP/auth failure into a misleading empty catalog.
                val enhancedQuery = if (filter != null && !query.contains(filter, ignoreCase = true)) {
                    "$query $filter"
                } else {
                    query
                }
                hfService.searchModels(enhancedQuery, filter = filter, limit = 40)
            }.onFailure { error ->
                DebugLog.log("[HF-SEARCH] ${error::class.simpleName}")
            }
        }

    /** Legacy callers retain the old empty-list behavior; new UI uses the Result API. */
    suspend fun searchModels(query: String, filter: String? = null): List<HfModelDto> =
        searchModelsResult(query, filter).getOrElse { emptyList() }
    
    suspend fun getGgufFiles(repoId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val info = hfService.getRepoInfo(repoId)
            info.siblings
                ?.filter { isSupportedMediaModelFile(it.rfilename) }
                ?.map { it.rfilename }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get GGUF files with their sizes using the /tree/main endpoint
     */
    suspend fun getGgufFilesWithSize(repoId: String): List<FileInfo> = withContext(Dispatchers.IO) {
        try {
            // Use the /tree/main endpoint which returns actual file sizes
            val treeItems = hfService.getRepoTree(repoId)
            treeItems
                .filter { it.type == "file" && isSupportedMediaModelFile(it.path) }
                .map { FileInfo(it.path, it.size) }
                .sortedByDescending { it.sizeBytes } // Show largest first
        } catch (e: Exception) {
            DebugLog.log("ModelRepository: Error fetching files: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get all files with vision support detection
     * Returns RepoFiles with GGUF models and any associated mmproj (vision projection) files
     */
    suspend fun getFilesWithVisionSupport(repoId: String): RepoFiles = withContext(Dispatchers.IO) {
        try {
            val treeItems = hfService.getRepoTree(repoId)

            // Find model files (GGUF files only for LLM - llama.cpp doesn't support safetensors)
            val modelFiles = treeItems
                .filter { it.type == "file" && it.path.endsWith(".gguf") && !it.path.contains("mmproj") }
                .map { FileInfo(it.path, it.size, FileType.MODEL) }
                .sortedByDescending { it.sizeBytes }

            // Find vision projection files (mmproj files)
            val visionFiles = treeItems
                .filter { it.type == "file" && it.path.contains("mmproj") && it.path.endsWith(".gguf") }
                .map { FileInfo(it.path, it.size, FileType.VISION_PROJECTOR) }
                .sortedByDescending { it.sizeBytes }
            
            RepoFiles(
                modelFiles = modelFiles,
                visionFiles = visionFiles,
                hasVisionSupport = visionFiles.isNotEmpty()
            )
        } catch (e: Exception) {
            DebugLog.log("ModelRepository: Error fetching files with vision support: ${e.message}")
            RepoFiles(emptyList(), emptyList(), false)
        }
    }

    /**
     * Returns the managed runtime directory for models that need a normal filesystem path.
     * This is distinct from the user-picked model library folder.
     */
    fun getModelDir(type: ModelType): File {
        val useExternalStorage =
            ModelLibraryManager.usesManagedExternalCanonicalStorage(type)
        
        // Get subfolder based on type
        val subfolder = when (type) {
            ModelType.LLM, ModelType.LORA, ModelType.EMBEDDING, ModelType.VISION -> "llm"
            ModelType.LLM_DRAFT -> "llm/drafts"
            ModelType.SD_LLM -> "sd/llm"
            ModelType.VISION_PROJECTOR, ModelType.MMPROJ -> "mmproj"
            ModelType.QUADTRIX -> "quadtrix"
            ModelType.SD_CHECKPOINT, ModelType.SD_UPSCALER -> "sd/checkpoints"
            ModelType.SD_DIFFUSION -> "sd/flux"
            ModelType.SD_CLIP_L -> "sd/clip_l"
            ModelType.SD_CLIP_G -> "sd/clip_g"
            ModelType.SD_T5XXL -> "sd/t5xxl"
            ModelType.SD_TAE -> "sd/tae"
            ModelType.SD_VAE -> "sd/vae"
            ModelType.SD_LORA -> "sd/lora"
            ModelType.SD_TEXTUAL_INVERSION -> "sd/embeddings"
            ModelType.SD_CONTROLNET -> "sd/controlnet"
            ModelType.SD_PHOTOMAKER -> "sd/photomaker"
            ModelType.SD_CLIP_VISION -> "sd/clip_vision"
            ModelType.SD_IP_ADAPTER -> "sd/ip_adapter"
            ModelType.SD_ADETAILER -> "sd/adetailer"
            ModelType.SD_AUDIO_VAE -> "sd/audio_vae"
            ModelType.SD_EMBEDDINGS_CONNECTORS -> "sd/connectors"
            ModelType.SD_MOTION_MODULE -> "sd/motion_module"
            ModelType.LLAMA_TTS,
            ModelType.LLAMA_TTS_COMPANION -> "audio/tts"
            ModelType.LITERT_AUDIO_DIT,
            ModelType.LITERT_AUDIO_COMPONENT -> "audio/stable"
            ModelType.ONNX_IMAGE_GEN,
            ModelType.ONNX_TTS,
            ModelType.ONNX_BACKGROUND_REMOVAL,
            ModelType.ONNX_IMAGE_UPSCALER -> return OnnxStorage.managedModelsRoot(context).apply {
                OnnxStorage.ensureManagedRootsReady(context)
            }
            ModelType.WHISPER -> "whisper"
        }
        
        if (useExternalStorage) {
            // Use app's external files directory - accessible to native binaries
            // Path: /storage/emulated/0/Android/data/com.example.llamadroid/files/models/...
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val folder = File(externalDir, "models/$subfolder")
                if (folder.exists() || folder.mkdirs()) {
                    DebugLog.log("ModelRepository: Using external: ${folder.absolutePath}")
                    return folder
                }
            }
        }
        
        // Fallback: internal storage
        val internalSubfolder = when (type) {
            ModelType.WHISPER -> "whisper_models"
            else -> "models"
        }
        return File(context.filesDir, internalSubfolder).apply { mkdirs() }
    }
    
    // We will assume a standard URL structure for GGUF files for the sake of the MVP
    // User would select a specific quantization
    suspend fun downloadModel(
        repoId: String,
        filename: String,
        type: ModelType,
        isVision: Boolean = false,
        sdCapabilities: String? = null,
        sdFamily: String? = null,
        sdVariant: String? = null,
        sdCompatProfiles: String? = null,
        onnxCapabilities: String? = null,
        onnxAssetKind: String? = null,
        onnxPipelineFamily: String? = null,
        onnxReferenceUri: String? = null,
        onnxReferencePath: String? = null,
        artifactFamily: String? = null,
        artifactRole: String? = null
    ) {
        val modelUrl = "https://huggingface.co/$repoId/resolve/main/$filename"
        // The short identity -> Room task -> service handoff must finish even
        // when the picker ViewModel is leaving the screen. The transfer itself
        // remains owned by DownloadService and is not kept non-cancellable.
        val handle = handoffModelDownload {
            val prepared = withContext(Dispatchers.IO) {
                catalogDownloadMutex.withLock {
                    // A previous service completion may have left the payload on
                    // disk before the old picker coroutine was cancelled. Recover
                    // that canonical file before allocating a timestamped name.
                    if (type == ModelType.LLM) recoverUnindexedLlmFiles()

                    val modelDir = getModelDir(type)
                    val requestedFilename = ModelLibraryManager.canonicalFilename(filename)
                    val installed = modelDao.getAllModels().first().firstOrNull { model ->
                        matchesCatalogModel(model, repoId, filename, type)
                    }
                    if (installed != null) {
                        return@withLock CatalogDownloadHandle(
                            taskId = "installed:${installed.filename}",
                            progressKey = "installed:${installed.filename}",
                            url = modelUrl,
                            localFilename = installed.filename,
                            destFile = File(installed.path),
                            alreadyInstalled = true
                        )
                    }

                    val stableTaskId = buildDownloadTaskId(repoId, requestedFilename, type)
                    val database = AppDatabase.getDatabase(context)
                    val activeTask = database.downloadTaskDao().getActiveByUrlAndModelType(
                        url = modelUrl,
                        modelType = type.name
                    )
                    if (activeTask != null) {
                        PendingDownloadHolder.addPendingFrom(activeTask)
                        DownloadProgressHolder.updateProgress(
                            activeTask.progressKey,
                            activeTask.filename,
                            DownloadProgressHolder.progress.value[activeTask.progressKey] ?: 0f
                        )
                        return@withLock CatalogDownloadHandle(
                            taskId = activeTask.id,
                            progressKey = activeTask.progressKey,
                            url = activeTask.url,
                            localFilename = activeTask.filename,
                            destFile = File(activeTask.destPath)
                        )
                    }

                    val pending = PendingDownloadHolder.getPending(stableTaskId)
                    if (pending != null) {
                        return@withLock CatalogDownloadHandle(
                            taskId = stableTaskId,
                            progressKey = pending.progressKey,
                            url = modelUrl,
                            localFilename = pending.filename,
                            destFile = File(pending.destPath)
                        )
                    }

                    // The process-local progress holder covers the small window
                    // between registering the request and Room/service arming it.
                    val trackedProgress = DownloadProgressHolder.progress.value[stableTaskId]
                    if (trackedProgress != null && trackedProgress in 0f..<1f) {
                        return@withLock CatalogDownloadHandle(
                            taskId = stableTaskId,
                            progressKey = stableTaskId,
                            url = modelUrl,
                            localFilename = DownloadProgressHolder.getFilename(stableTaskId)
                                ?: requestedFilename,
                            destFile = File(modelDir, DownloadProgressHolder.getFilename(stableTaskId)
                                ?: requestedFilename)
                        )
                    }

                    val localFilename = chooseUniqueDownloadFilename(
                        requestedFilename = filename,
                        type = type,
                        modelDir = modelDir
                    )
                    val destFile = File(modelDir, localFilename)
                    val inferredFamily = inferSdFamily(type, repoId, filename)
                    val resolvedFamily = sdFamily ?: inferredFamily.first?.storedValue
                    val resolvedVariant = sdVariant ?: inferredFamily.second
                    val resolvedFamilyEnum = SdModelFamily.fromStoredValue(resolvedFamily)
                    val resolvedCapabilities = sdCapabilities
                        ?: defaultCapabilitiesForFamily(resolvedFamilyEnum, type, resolvedVariant)
                    val resolvedCompatProfiles = resolveSdCompatProfiles(
                        type = type,
                        explicitProfiles = sdCompatProfiles,
                        family = resolvedFamilyEnum,
                        variant = resolvedVariant
                    )
                    val progressKey = buildDownloadTaskId(repoId, localFilename, type)

                    // Register the complete runtime metadata before launching the
                    // foreground service. The service owns final model insertion,
                    // so a cancelled picker cannot orphan a completed payload.
                    PendingDownloadHolder.addPending(
                        downloadId = progressKey,
                        filename = localFilename,
                        repoId = repoId,
                        progressKey = progressKey,
                        type = type,
                        destPath = destFile.absolutePath,
                        isVision = isVision,
                        sdCapabilities = resolvedCapabilities,
                        sdFamily = resolvedFamily,
                        sdVariant = resolvedVariant,
                        sdCompatProfiles = resolvedCompatProfiles,
                        onnxCapabilities = onnxCapabilities,
                        onnxAssetKind = onnxAssetKind,
                        onnxPipelineFamily = onnxPipelineFamily,
                        onnxReferenceUri = onnxReferenceUri,
                        onnxReferencePath = onnxReferencePath,
                        artifactFamily = artifactFamily,
                        artifactRole = artifactRole,
                        classificationSource = ModelClassificationSource.CATALOG.storedValue
                    )
                    // Room is the ownership boundary across process death. Keep
                    // the same metadata in the durable task before yielding to
                    // Main to enqueue the foreground service.
                    val persistedPending = requireNotNull(PendingDownloadHolder.getPending(progressKey))
                    database.downloadTaskDao().upsert(
                        persistedPending.toDownloadTaskEntity(progressKey, modelUrl)
                    )
                    DownloadProgressHolder.updateProgress(progressKey, localFilename, 0f)
                    CatalogDownloadHandle(
                        taskId = progressKey,
                        progressKey = progressKey,
                        url = modelUrl,
                        localFilename = localFilename,
                        destFile = destFile
                    )
                }
            }
            if (!prepared.alreadyInstalled) {
                try {
                    withContext(Dispatchers.Main) {
                        com.example.llamadroid.service.DownloadService.startDownload(
                            context = context,
                            url = prepared.url,
                            destPath = prepared.destFile.absolutePath,
                            filename = prepared.localFilename,
                            downloadId = prepared.taskId
                        )
                    }
                } catch (failure: Throwable) {
                    // Do not leave a Room task ACTIVE when Android rejects the
                    // foreground-service handoff before a worker exists.
                    withContext(Dispatchers.IO) {
                        val status = if (downloadPartFile(prepared.destFile.path).length() > 0L) {
                            DOWNLOAD_TASK_STATUS_RESUMABLE
                        } else {
                            DOWNLOAD_TASK_STATUS_FAILED
                        }
                        AppDatabase.getDatabase(context).downloadTaskDao().updateStatus(
                            id = prepared.taskId,
                            status = status,
                            lastError = failure.message
                        )
                    }
                    PendingDownloadHolder.removePending(prepared.taskId)
                    DownloadProgressHolder.updateProgress(prepared.progressKey, -1f)
                    throw failure
                }
            }
            prepared
        }

        if (handle.alreadyInstalled) return

        // Monitor progress from DownloadProgressHolder. Registration and model
        // insertion are service-owned, so this wait is only a UI compatibility
        // bridge for the legacy catalog ViewModel.
        var lastProgress = 0f
        while (true) {
            kotlinx.coroutines.delay(500)
            val progress = DownloadProgressHolder.progress.value[handle.progressKey] ?: 0f

            if (progress != lastProgress && progress >= 0f) {
                lastProgress = progress
                DownloadProgressHolder.updateProgress(handle.progressKey, progress)
            }

            if (progress >= 1f) {
                break
            } else if (progress < 0f && progress != DownloadProgressHolder.INDETERMINATE) {
                // Download failed
                DownloadProgressHolder.removeProgress(handle.progressKey)
                DebugLog.log("ModelRepository: Download failed for ${handle.localFilename}")
                break
            }
        }
    }
    
    /**
     * Start a download without waiting for completion.
     * Use this for SD models where the dialog closes immediately.
     * The download service will handle saving to DB via DownloadCompletionReceiver.
     */
    fun startDownloadAsync(
        repoId: String,
        filename: String,
        type: ModelType,
        isVision: Boolean = false,
        sdCapabilities: String? = null,
        sdFamily: String? = null,
        sdVariant: String? = null,
        sdCompatProfiles: String? = null,
        onnxCapabilities: String? = null,
        onnxAssetKind: String? = null,
        onnxPipelineFamily: String? = null,
        onnxReferenceUri: String? = null,
        onnxReferencePath: String? = null,
        downloadUrlOverride: String? = null,
        localFilenameOverride: String? = null,
        artifactFamily: String? = null,
        artifactRole: String? = null,
        classificationSource: ModelClassificationSource = ModelClassificationSource.USER_OVERRIDE
    ) {
        val modelDir = getModelDir(type)
        val localFilename = localFilenameOverride?.let { requested ->
            val clean = ModelLibraryManager.canonicalFilename(requested)
            require(clean == requested && clean.isNotBlank()) { "Unsafe local filename override" }
            require(!File(modelDir, clean).exists()) { "A model named $clean is already installed" }
            clean
        } ?: chooseUniqueDownloadFilename(
            requestedFilename = filename,
            type = type,
            modelDir = modelDir
        )
        val modelUrl = downloadUrlOverride ?: "https://huggingface.co/$repoId/resolve/main/$filename"
        val destFile = File(modelDir, localFilename)
        val inferredFamily = inferSdFamily(type, repoId, filename)
        val resolvedFamily = sdFamily ?: inferredFamily.first?.storedValue
        val resolvedVariant = sdVariant ?: inferredFamily.second
        val resolvedFamilyEnum = SdModelFamily.fromStoredValue(resolvedFamily)
        val resolvedCapabilities = sdCapabilities
            ?: defaultCapabilitiesForFamily(resolvedFamilyEnum, type, resolvedVariant)
        val resolvedCompatProfiles = resolveSdCompatProfiles(
            type = type,
            explicitProfiles = sdCompatProfiles,
            family = resolvedFamilyEnum,
            variant = resolvedVariant
        )
        
        // Use unique progress key
        val progressKey = buildDownloadTaskId(repoId, localFilename, type)
        
        // Track progress under unique key for UI display
        DownloadProgressHolder.updateProgress(progressKey, localFilename, 0f)
        
        // Store pending download info so DownloadService can save to DB on completion
        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = localFilename,
            repoId = repoId,
            progressKey = progressKey,
            type = type,
            destPath = destFile.absolutePath,
            isVision = isVision,
            sdCapabilities = resolvedCapabilities,
            sdFamily = resolvedFamily,
            sdVariant = resolvedVariant,
            sdCompatProfiles = resolvedCompatProfiles,
            onnxCapabilities = onnxCapabilities,
            onnxAssetKind = onnxAssetKind,
            onnxPipelineFamily = onnxPipelineFamily,
            onnxReferenceUri = onnxReferenceUri,
            onnxReferencePath = onnxReferencePath,
            artifactFamily = artifactFamily,
            artifactRole = artifactRole,
            classificationSource = classificationSource.storedValue
        )
        
        // Start foreground service (this is called from main thread via onClick)
        com.example.llamadroid.service.DownloadService.startDownload(
            context = context,
            url = modelUrl,
            destPath = destFile.absolutePath,
            filename = localFilename,
            downloadId = progressKey
        )
        
        DebugLog.log("ModelRepository: Started async download for $localFilename")
    }

    private fun chooseUniqueDownloadFilename(
        requestedFilename: String,
        type: ModelType,
        modelDir: File
    ): String {
        val firstChoice = ModelLibraryManager.chooseUniqueFilename(
            context = context,
            relativeDir = ModelLibraryManager.relativeDirFor(type),
            requestedFilename = requestedFilename,
            runtimeDir = modelDir
        )
        if (!DownloadProgressHolder.isFilenameTracked(firstChoice)) return firstChoice

        val clean = ModelLibraryManager.canonicalFilename(requestedFilename)
        val base = clean.substringBeforeLast('.', clean)
        val extensionSuffix = clean.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        return ModelLibraryManager.chooseUniqueFilename(
            context = context,
            relativeDir = ModelLibraryManager.relativeDirFor(type),
            requestedFilename = "$base-${System.currentTimeMillis()}$extensionSuffix",
            runtimeDir = modelDir
        )
    }

    fun startOnnxCatalogDownload(entry: OnnxCatalogEntry) {
        OnnxStorage.ensureManagedRootsReady(context)
        val modelId = buildOnnxCatalogStableId(entry.provider, entry.bundleId)
        val progressKey = "onnx:$modelId"
        val installKind = when (entry.assetKind) {
            ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE -> ONNX_INSTALL_KIND_HF_TREE_BUNDLE
            ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE -> ONNX_INSTALL_KIND_FILE
            else -> ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
        }
        val tempDownload = if (installKind == ONNX_INSTALL_KIND_FILE) {
            File(OnnxStorage.managedBundleDir(context, modelId).apply { mkdirs() }, File(entry.assetName).name)
        } else {
            File(
                OnnxStorage.tempDownloadDir(context).apply { mkdirs() },
                if (installKind == ONNX_INSTALL_KIND_HF_TREE_BUNDLE) "$modelId.download" else "$modelId.zip"
            )
        }

        DownloadProgressHolder.updateProgress(progressKey, modelId, 0f)
        DownloadProgressHolder.updateStatus(progressKey, "Downloading")
        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = modelId,
            repoId = entry.repoId,
            progressKey = progressKey,
            type = entry.modelType,
            destPath = tempDownload.absolutePath,
            onnxCapabilities = entry.capabilities,
            onnxAssetKind = entry.assetKind,
            onnxPipelineFamily = entry.pipelineFamily,
            onnxReferenceUri = entry.downloadUrl,
            onnxReferencePath = null,
            onnxInstallKind = installKind,
            onnxInstallDirPath = if (installKind == ONNX_INSTALL_KIND_FILE) null else OnnxStorage.managedBundleDir(context, modelId).absolutePath,
            huggingFaceToken = if (entry.gated) huggingFaceToken() else null,
            classificationSource = ModelClassificationSource.CATALOG.storedValue
        )

        com.example.llamadroid.service.DownloadService.startDownload(
            context = context,
            url = entry.downloadUrl,
            destPath = tempDownload.absolutePath,
            filename = modelId,
            downloadId = progressKey
        )
    }
    
    private suspend fun activeAudioJobReferences(
        database: AppDatabase
    ): List<InstalledModelReferenceKey> = activeAudioJobDependencies(context, database).map { dependency ->
        InstalledModelReferenceKey(
            path = dependency.path,
            key = dependency.targetKey,
            relation = dependency.relation
        )
    }

    /**
     * Download rows and in-process pending entries are file leases too. Keep
     * them in the same preflight set as runtime rows so a model removal cannot
     * race a downloader that is about to materialize or resume its payload.
     */
    private suspend fun activeDownloadReferences(
        database: AppDatabase
    ): List<InstalledModelReferenceKey> = activeDownloadDependencies(database).map { dependency ->
        InstalledModelReferenceKey(
            path = dependency.path,
            key = dependency.targetKey,
            relation = dependency.relation
        )
    }

    /**
     * Records each candidate path after a deletion attempt. The journal is a
     * Room-backed recovery boundary; callers intentionally do not replace it
     * with an in-memory fallback when persistence fails.
     */
    private suspend fun recordDeletionPaths(
        journal: RoomModelDeletionJournal,
        preview: ModelDeletionPreview,
        result: ModelDeletionResult
    ) {
        val deleted = result.deletedPaths.map(::canonicalPathOrSelf).toSet()
        val failures = result.failedPaths.associateBy { canonicalPathOrSelf(it.path) }
        for (file in preview.files) {
            val path = canonicalPathOrSelf(file.path)
            journal.recordPath(
                operationId = result.operationId,
                path = file.path,
                deleted = path in deleted,
                failure = failures[path]
            )
        }
    }

    private fun canonicalPathOrSelf(path: String): String =
        if ("://" in path) path else runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** Computes dependency-aware deletion details without changing durable state. */
    suspend fun previewDeleteModel(model: ModelEntity): ModelDeletionPreview = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val libraryDao = database.modelLibraryDao()
        val otherRuntimeRows = modelDao.getAllModels().first()
            .filter { it.filename != model.filename }
        val provenance = libraryDao.observeProvenance().first()
        val pendingArtifacts = libraryDao.observePendingArtifacts().first()
        val protectedReferences = mutableListOf<InstalledModelReferenceKey>()
        otherRuntimeRows.forEach { row ->
            protectedReferences += InstalledModelReferenceKey(row.path, row.filename, "runtime")
            row.mmprojPath?.let { protectedReferences += InstalledModelReferenceKey(it, row.filename, "companion") }
        }
        database.liteRtModelDao().getAllOnce().forEach { row ->
            protectedReferences += InstalledModelReferenceKey(row.path, row.displayName, "litert")
        }
        provenance.filter { it.modelKey != model.filename }.forEach { edge ->
            edge.localPath?.let { protectedReferences += InstalledModelReferenceKey(it, edge.modelKey, "provenance") }
        }
        protectedReferences += activeAudioJobReferences(database)
        protectedReferences += activeDownloadReferences(database)
        val candidates = linkedSetOf<File>().apply {
            val primary = File(model.path)
            if (primary.exists() && (
                    isManagedModelPath(primary) ||
                        ModelLibraryManager.usesManagedExternalCanonicalStorage(model.type) ||
                        model.repoId == ModelBackupPolicy.LOCAL_IMPORT_REPO_ID ||
                        model.repoId.startsWith("custom-import/")
                    )) {
                add(primary)
            }
            if (ModelLibraryManager.requiresRuntimeMirror(model.type)) {
                add(File(getModelDir(model.type), ModelLibraryManager.canonicalFilename(model.filename)))
            }
            pendingArtifacts
                .filter { it.promotedModelKey == model.filename }
                .flatMap { listOfNotNull(it.stagingPath, it.destinationPath) }
                .map(::File)
                .filter { it.exists() && isManagedModelPath(it) }
                .forEach(::add)
            provenance
                .filter { it.modelKey == model.filename }
                .mapNotNull { it.localPath?.let(::File) }
                .filter { it.exists() && isManagedModelPath(it) }
                .forEach(::add)
        }
        val targetCanonicals = candidates.map { canonicalPathOrSelf(it.absolutePath) }.toSet()
        val targetDirectories = candidates
            .filter { it.isDirectory }
            .map { canonicalPathOrSelf(it.absolutePath) }
        val dependencies = protectedReferences.mapNotNull { reference ->
            val path = reference.path
            val canonical = runCatching { File(path).canonicalPath }.getOrNull()
            if (canonical != null && (
                    canonical in targetCanonicals ||
                        targetDirectories.any { directory ->
                            canonical.startsWith("$directory${File.separator}")
                        }
                )) {
                ModelDeletionDependency(reference.key, reference.relation, path)
            } else {
                null
            }
        }
        val protectedByPath = dependencies.groupBy { runCatching { File(it.path).canonicalPath }.getOrDefault(it.path) }
        val files = candidates.filter { it.exists() }.map { file ->
            val canonical = canonicalPathOrSelf(file.absolutePath)
            ModelDeletionFile(
                path = canonical,
                sizeBytes = if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                kind = if (canonical == canonicalPathOrSelf(model.path)) "primary" else "managed-copy",
                protectedBy = protectedByPath[canonical].orEmpty().map { it.targetKey }
            )
        }.toMutableList().apply {
            if (model.type != ModelType.ONNX_IMAGE_GEN &&
                model.type != ModelType.ONNX_TTS &&
                model.type != ModelType.ONNX_BACKGROUND_REMOVAL &&
                model.type != ModelType.ONNX_IMAGE_UPSCALER &&
                dependencies.isEmpty()
            ) {
                ModelLibraryManager.libraryFile(
                    context = context,
                    relativeDir = ModelLibraryManager.relativeDirFor(model.type),
                    filename = model.filename
                )?.takeIf { it.exists() }?.let { document ->
                    add(
                        ModelDeletionFile(
                            path = document.uri.toString(),
                            sizeBytes = document.length().coerceAtLeast(0L),
                            kind = "shared-library"
                        )
                    )
                }
            }
        }
        val blockedCode = if (dependencies.isNotEmpty()) ModelLibraryErrorCode.DELETION_BLOCKED else null
        ModelDeletionPreview(
            targetKey = model.filename,
            targetLabel = model.filename,
            files = files,
            dependencies = dependencies,
            protectedPaths = dependencies.map { it.path }.distinct(),
            canDelete = dependencies.isEmpty(),
            blockingCode = blockedCode
        )
    }

    /** Performs a deletion and returns a recoverable typed result for UI callers. */
    suspend fun deleteModelWithResult(model: ModelEntity): ModelDeletionResult =
        modelDeletionOperationMutex.withLock {
            if (model.type.isStableAudioComponentType()) {
                StableAudioModelLease.withLease(listOf(model.path)) {
                    deleteModelWithResultLocked(model, removeRuntimeRow = true)
                }
            } else {
                deleteModelWithResultLocked(model, removeRuntimeRow = true)
            }
        }

    /** Lists interrupted or recoverable model deletions for a Retry surface. */
    suspend fun recoverableDeletionOperations(limit: Int = 50) =
        RoomModelDeletionJournal(AppDatabase.getDatabase(context)).recoverableOperations(limit)

    /** Retries a journal entry while the target runtime row is still present. */
    suspend fun retryDeletion(operationId: String): ModelDeletionResult? {
        val journal = RoomModelDeletionJournal(AppDatabase.getDatabase(context))
        val snapshot = journal.operation(operationId) ?: return null
        if (snapshot.preview.targetKind != "model") return null
        val model = modelDao.getModelByFilename(snapshot.preview.targetKey)
        if (model == null) {
            // A process can be interrupted after the runtime row transaction
            // succeeds but before the journal is finalized. Reconcile that
            // durable state instead of leaving a Retry action that can never
            // find its model row again.
            val preserved = snapshot.preview.files
                .map { it.path }
                .filter(::deletionPathExists)
            if (preserved.isEmpty()) {
                val result = ModelDeletionResult(
                    operationId = snapshot.operationId,
                    targetKey = snapshot.preview.targetKey,
                    targetKind = "model",
                    status = ModelDeletionStatus.COMPLETED,
                    deletedPaths = snapshot.preview.files.map { it.path },
                    reclaimedBytes = 0L // No bytes were removed by this recovery pass.
                )
                journal.complete(result)
                return result
            }
            return ModelDeletionResult(
                operationId = snapshot.operationId,
                targetKey = snapshot.preview.targetKey,
                targetKind = "model",
                status = ModelDeletionStatus.RECOVERABLE,
                preservedPaths = preserved,
                errorCode = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                errorMessage = context.getString(R.string.model_library_error_deletion_failed)
            )
        }
        val retried = deleteModelWithResult(model)
        val reconciled = retried.copy(operationId = snapshot.operationId)
        if (retried.status == ModelDeletionStatus.COMPLETED) {
            journal.complete(reconciled)
        } else {
            journal.fail(reconciled.copy(status = ModelDeletionStatus.RECOVERABLE))
        }
        return reconciled
    }

    private fun deletionPathExists(path: String): Boolean = if ("://" in path) {
        DocumentFile.fromSingleUri(context, Uri.parse(path))?.exists() == true
    } else {
        File(path).exists()
    }

    private suspend fun deleteModelWithResultLocked(
        model: ModelEntity,
        removeRuntimeRow: Boolean
    ): ModelDeletionResult {
        val preview = previewDeleteModel(model)
        if (!preview.canDelete) {
            return ModelDeletionResult(
                operationId = preview.operationId,
                targetKey = model.filename,
                status = ModelDeletionStatus.BLOCKED,
                preservedPaths = preview.files.map { it.path },
                errorCode = preview.blockingCode ?: ModelLibraryErrorCode.DELETION_BLOCKED,
                errorMessage = context.getString(R.string.model_audio_companion_in_use)
            )
        }
        val journal = RoomModelDeletionJournal(AppDatabase.getDatabase(context))
        var journalStarted = false
        val attemptedPaths = linkedSetOf<String>()
        val attemptedDeletedPaths = linkedSetOf<String>()
        val attemptedPreservedPaths = linkedSetOf<String>()
        val attemptedFailures = linkedMapOf<String, ModelDeletionPathFailure>()
        return try {
            journal.begin(preview)
            journalStarted = true
            reconcileManagedModelCopiesIfNeeded()
            val deleted = deleteModelArtifactsInternal(
                model = model,
                removeRuntimeRow = removeRuntimeRow,
                onPathResult = { path, wasDeleted, failure ->
                    val canonical = canonicalPathOrSelf(path)
                    attemptedPaths += canonical
                    if (wasDeleted) {
                        attemptedDeletedPaths += canonical
                    } else if (failure != null) {
                        attemptedFailures[canonical] = failure
                    } else {
                        attemptedPreservedPaths += canonical
                    }
                    journal.recordPath(
                        operationId = preview.operationId,
                        path = path,
                        deleted = wasDeleted,
                        failure = failure
                    )
                }
            )
            val preserved = preview.files.map { it.path }.filterNot { it in deleted }
            val result = ModelDeletionResult(
                operationId = preview.operationId,
                targetKey = model.filename,
                status = ModelDeletionStatus.COMPLETED,
                deletedPaths = deleted,
                preservedPaths = preserved,
                reclaimedBytes = preview.files.filter { it.path in deleted }.sumOf { it.sizeBytes }
            )
            recordDeletionPaths(journal, preview, result)
            journal.complete(result)
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val unobservedFailures = preview.files
                .filter { canonicalPathOrSelf(it.path) !in attemptedPaths && File(it.path).exists() }
                .map { file ->
                    ModelDeletionPathFailure(
                        path = file.path,
                        code = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                        message = error.message
                    )
                }
            val failedPaths = (attemptedFailures.values + unobservedFailures)
                .distinctBy { canonicalPathOrSelf(it.path) }
            val result = ModelDeletionResult(
                operationId = preview.operationId,
                targetKey = model.filename,
                status = ModelDeletionStatus.RECOVERABLE,
                deletedPaths = attemptedDeletedPaths.toList(),
                preservedPaths = (attemptedPreservedPaths + preview.files
                    .map { canonicalPathOrSelf(it.path) }
                    .filter { path ->
                        path !in attemptedPaths &&
                            path !in failedPaths.map { failure -> canonicalPathOrSelf(failure.path) }
                    })
                    .toList(),
                failedPaths = failedPaths,
                errorCode = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                errorMessage = error.message
            )
            if (journalStarted) {
                try {
                    recordDeletionPaths(journal, preview, result)
                    journal.fail(result)
                } catch (_: Throwable) {
                    // Keep the typed recoverable result when journal storage
                    // itself is temporarily unavailable.
                }
            }
            result
        }
    }

    suspend fun deleteModel(model: ModelEntity) {
        val result = deleteModelWithResult(model)
        if (result.status != ModelDeletionStatus.COMPLETED) {
            throw ModelLibraryException(
                code = result.errorCode ?: ModelLibraryErrorCode.DELETION_FAILED,
                message = result.errorMessage ?: context.getString(R.string.error_generic)
            )
        }
    }

    suspend fun deleteModelArtifacts(model: ModelEntity) {
        val result = modelDeletionOperationMutex.withLock {
            if (model.type.isStableAudioComponentType()) {
                StableAudioModelLease.withLease(listOf(model.path)) {
                    deleteModelWithResultLocked(model, removeRuntimeRow = false)
                }
            } else {
                deleteModelWithResultLocked(model, removeRuntimeRow = false)
            }
        }
        if (result.status != ModelDeletionStatus.COMPLETED) {
            throw ModelLibraryException(
                code = result.errorCode ?: ModelLibraryErrorCode.DELETION_FAILED,
                message = result.errorMessage ?: context.getString(R.string.error_generic)
            )
        }
    }

    private suspend fun deleteModelArtifactsInternal(
        model: ModelEntity,
        removeRuntimeRow: Boolean,
        onPathResult: suspend (path: String, deleted: Boolean, failure: ModelDeletionPathFailure?) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val managedPaths = linkedSetOf<File>()
        val directPaths = linkedSetOf<File>()
        val currentPath = File(model.path)
        if (currentPath.exists()) {
            if (isManagedModelPath(currentPath)) {
                managedPaths += currentPath
            } else if (
                ModelLibraryManager.usesManagedExternalCanonicalStorage(model.type) ||
                model.repoId == ModelBackupPolicy.LOCAL_IMPORT_REPO_ID ||
                model.repoId.startsWith("custom-import/")
            ) {
                directPaths += currentPath
            }
        }
        if (ModelLibraryManager.requiresRuntimeMirror(model.type)) {
            managedPaths += File(getModelDir(model.type), ModelLibraryManager.canonicalFilename(model.filename))
        }

        val database = AppDatabase.getDatabase(context)
        val libraryDao = database.modelLibraryDao()
        val lifecycle = try {
            val otherRuntimeRows = modelDao.getAllModels().first()
                .filter { it.filename != model.filename }
            LifecycleProtectionSnapshot(
                pendingArtifacts = libraryDao.observePendingArtifacts().first(),
                allProvenance = libraryDao.observeProvenance().first(),
                // A native TTS main row persists its selected companion in
                // mmprojPath. Protect those references as well as the rows'
                // own paths so removing a shared companion cannot strand a
                // still-runnable model. This uses actual installed-row
                // references rather than the curated catalog, which may be
                // present even when no bundle is installed.
                otherRuntimePaths = otherRuntimeRows.flatMap { row ->
                    listOfNotNull(row.path, row.mmprojPath)
                },
                otherLiteRtPaths = database.liteRtModelDao().getAllOnce().map { it.path },
                activeAudioJobPaths = activeAudioJobReferences(database).map { it.path },
                activeDownloadPaths = activeDownloadReferences(database).map { it.path }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Protection data is a safety boundary. Never continue with an
            // empty set after a Room read failure because that could remove a
            // companion still referenced by another runtime row.
            throw IllegalStateException(context.getString(R.string.error_generic), error)
        }
        val pendingArtifacts = lifecycle.pendingArtifacts
        val allProvenance = lifecycle.allProvenance
        val otherRuntimePaths = lifecycle.otherRuntimePaths
        val otherLiteRtPaths = lifecycle.otherLiteRtPaths
        val activeAudioJobPaths = lifecycle.activeAudioJobPaths
        val activeDownloadPaths = lifecycle.activeDownloadPaths
        val protectedPaths = buildList {
            addAll(otherRuntimePaths)
            addAll(otherLiteRtPaths)
            addAll(activeAudioJobPaths)
            addAll(activeDownloadPaths)
            addAll(allProvenance.filter { it.modelKey != model.filename }.mapNotNull { it.localPath })
        }

        val ownedCandidates = linkedSetOf<File>()
        managedPaths.filter(::isManagedModelPath).forEach(ownedCandidates::add)
        directPaths.forEach(ownedCandidates::add)
        // Promoted companion rows keep their own paths. Include only paths in
        // app-managed storage; a source file outside the app remains user-owned.
        pendingArtifacts
            .filter { it.promotedModelKey == model.filename }
            .flatMap { listOfNotNull(it.stagingPath, it.destinationPath) }
            .map(::File)
            .filter { it.exists() && isManagedModelPath(it) }
            .forEach(ownedCandidates::add)
        allProvenance
            .filter { it.modelKey == model.filename }
            .mapNotNull { it.localPath?.let(::File) }
            .filter { it.exists() && isManagedModelPath(it) }
            .forEach(ownedCandidates::add)
        var firstPathFailure: ModelDeletionPathFailure? = null
        var firstPreservedPath: String? = null
        val deletedPaths = ModelArtifactLifecycle.deleteOwnedPathsWithProgress(
            candidates = ownedCandidates,
            protectedPaths = protectedPaths,
            onPathResult = { path, deleted, failure ->
                if (!deleted && failure != null && firstPathFailure == null) {
                    firstPathFailure = failure
                } else if (!deleted && failure == null && firstPreservedPath == null) {
                    firstPreservedPath = path
                }
                onPathResult(path, deleted, failure)
            }
        ).toMutableList()
        val currentPathIsShared = protectedPaths.any { protected ->
            // A failed canonicalization is treated as shared.  Deletion must
            // fail closed when a protection path cannot be resolved.
            runCatching {
                val protectedPath = File(protected).canonicalFile
                protectedPath == currentPath.canonicalFile ||
                    (currentPath.isDirectory && protectedPath.path.startsWith(
                        "${currentPath.canonicalPath}${File.separator}"
                    ))
            }.getOrDefault(true)
        }
        if (model.type == ModelType.LLAMA_TTS_COMPANION && currentPathIsShared) {
            // Keep the companion row and bytes together while a native TTS
            // main row still points at them. The caller can surface this
            // localized error and ask the user to remove that association
            // first; deleting only the row would leave a dangling mmprojPath.
            throw IllegalStateException(context.getString(R.string.model_audio_companion_in_use))
        }

        val libraryTarget = if (
            model.type == ModelType.ONNX_IMAGE_GEN ||
            model.type == ModelType.ONNX_TTS ||
            model.type == ModelType.ONNX_BACKGROUND_REMOVAL ||
            model.type == ModelType.ONNX_IMAGE_UPSCALER
        ) {
            null
        } else {
            ModelLibraryManager.libraryFile(
                context = context,
                relativeDir = ModelLibraryManager.relativeDirFor(model.type),
                filename = model.filename
            )
        }
        if (libraryTarget != null && !currentPathIsShared && protectedPaths.none {
                File(it).name == ModelLibraryManager.canonicalFilename(model.filename)
            }) {
            val libraryPath = libraryTarget.uri.toString()
            try {
                val removed = libraryTarget.delete() && !libraryTarget.exists()
                if (removed) {
                    deletedPaths += libraryPath
                    onPathResult(libraryPath, true, null)
                } else if (libraryTarget.exists()) {
                    val failure = ModelDeletionPathFailure(
                        path = libraryPath,
                        code = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                        message = null
                    )
                    if (firstPathFailure == null) firstPathFailure = failure
                    onPathResult(
                        libraryPath,
                        false,
                        failure
                    )
                }
            } catch (error: Throwable) {
                val failure = ModelDeletionPathFailure(
                    path = libraryPath,
                    code = ModelLibraryErrorCode.DELETION_RECOVERABLE,
                    message = error.message
                )
                if (firstPathFailure == null) firstPathFailure = failure
                onPathResult(
                    libraryPath,
                    false,
                    failure
                )
            }
        }

        if (firstPathFailure != null || firstPreservedPath != null) {
            throw IllegalStateException(
                firstPathFailure?.message
                    ?: "Managed model files could not be removed: ${firstPreservedPath.orEmpty()}"
            )
        }

        // Source and bundle definitions are reusable records. Only the
        // installed provenance edge is removed; promoted pending rows become
        // CANCELLED so startup recovery cannot resurrect a deleted model and
        // an explicit bundle retry can recreate it.
        val detached = ModelArtifactLifecycle.detachPromotedPendingArtifacts(
            artifacts = pendingArtifacts,
            modelKey = model.filename,
            now = System.currentTimeMillis()
        )
        val changed = detached.filterIndexed { index, artifact -> artifact != pendingArtifacts[index] }
        database.withTransaction {
            libraryDao.deleteByModelKey(model.filename)
            libraryDao.upsertPendingArtifactsAtomically(changed)
            if (removeRuntimeRow) database.modelDao().deleteModel(model)
        }
        deletedPaths
    }
    
    suspend fun insertModel(model: ModelEntity) {
        modelDao.insertModel(enrichSdModelForStorage(model))
    }

    /**
     * Inspect a selected SD artifact before callers move it into a managed
     * directory or create a trusted model row.  The inspector only reads
     * bounded headers/descriptors and never unpickles legacy checkpoints.
     */
    suspend fun inspectSdArtifact(
        file: File,
        configuredType: ModelType,
        configuredFamily: String? = null,
        force: Boolean = false
    ): SdArtifactInspection = withContext(Dispatchers.IO) {
        val inspection = SdInspectionCache.inspect(
            file = file,
            configuredRole = configuredType.sdArtifactRole(),
            force = force
        )
        validateSdArtifactInspection(configuredType, inspection, configuredFamily).getOrThrow()
        inspection
    }

    /**
     * Inspect a selected Storage Access Framework document before import.
     * Only a bounded header prefix is read; the source model is never copied
     * or handed to a native backend by this method.
     */
    suspend fun inspectSdArtifact(
        uri: Uri,
        displayName: String?,
        configuredType: ModelType,
        configuredFamily: String? = null
    ): SdArtifactInspection = withContext(Dispatchers.IO) {
        val inspection = SdArtifactInspector().inspect(
            contentResolver = context.contentResolver,
            uri = uri,
            displayName = displayName,
            configuredRole = configuredType.sdArtifactRole(),
            temporaryDirectory = context.cacheDir
        )
        validateSdArtifactInspection(configuredType, inspection, configuredFamily).getOrThrow()
        inspection
    }

    /** Re-inspect a stale/legacy row lazily before it is shown or launched. */
    suspend fun ensureSdArtifactInspection(
        model: ModelEntity,
        force: Boolean = false
    ): ModelEntity = withContext(Dispatchers.IO) {
        if (!model.type.isStableDiffusionArtifact()) return@withContext model
        val file = File(model.path)
        if (!file.exists() || !file.isFile) return@withContext model

        val cached = model.sdArtifactInspection()
        if (!force && cached != null && !model.needsSdArtifactInspection(file)) {
            return@withContext model
        }

        // Lazy detection must still persist high-confidence contradictions so
        // the model-management UI can show the detected evidence and explain
        // why launch is blocked. Import/download trust validation remains in
        // insertModel/enrichSdModelForStorage and still throws on blockers.
        val inspection = SdInspectionCache.inspect(
            file = file,
            configuredRole = model.type.sdArtifactRole(),
            force = force
        )
        val enriched = model.withSdArtifactInspection(inspection)
        if (enriched != model) modelDao.insertModel(enriched)
        enriched
    }

    /**
     * Sequential bulk upgrade used by an explicit “detect all” action. Header
     * inspection is bounded and serialized so a large model library cannot
     * allocate one parser buffer per artifact or start a native model load.
     */
    suspend fun ensureSdArtifactInspections(
        models: Iterable<ModelEntity>,
        force: Boolean = false,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ModelEntity> = withContext(Dispatchers.IO) {
        val candidates = models.filter { it.type.isStableDiffusionArtifact() }.toList()
        val total = candidates.size
        candidates.mapIndexed { index, model ->
            val result = runCatching { ensureSdArtifactInspection(model, force) }
                .getOrDefault(model)
            onProgress(index + 1, total)
            result
        }
    }

    private suspend fun enrichSdModelForStorage(model: ModelEntity): ModelEntity {
        if (!model.type.isStableDiffusionArtifact()) return model
        val file = File(model.path)
        if (!file.exists() || !file.isFile) return model

        val cached = model.sdArtifactInspection()
        if (cached != null && !model.needsSdArtifactInspection(file)) {
            // Re-validate persisted summaries when an artifact is inserted or
            // updated through a trust boundary. Lazy UI detection deliberately
            // stores contradictions; trusted insertion must not.
            validateSdArtifactInspection(model.type, cached, model.sdFamily).getOrThrow()
            return model
        }

        val inspection = SdInspectionCache.inspect(
            file = file,
            configuredRole = model.type.sdArtifactRole()
        )
        validateSdArtifactInspection(model.type, inspection, model.sdFamily).getOrThrow()
        return model.withSdArtifactInspection(inspection)
    }

    suspend fun updateVisionSupport(filename: String, isVision: Boolean) {
        modelDao.updateVisionSupport(filename, isVision)
    }

    /**
     * Remember local SD parameter residency for a model/component. The value is
     * normalized before persistence and never affects distributed launches.
     */
    suspend fun updateSdParamsBackendSpec(
        model: ModelEntity,
        spec: String
    ): ModelEntity = withContext(Dispatchers.IO) {
        val normalized = normalizeSdParamsBackendSpec(spec, model.sdParamsBackendMode)
        modelDao.updateSdParamsBackendSpec(model.filename, normalized)
        model.copy(sdParamsBackendSpec = normalized)
    }

    fun huggingFaceToken(): String =
        context.applicationContext
            .getSharedPreferences(HF_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(HF_TOKEN_KEY, "")
            .orEmpty()

    fun saveHuggingFaceToken(token: String) {
        context.applicationContext
            .getSharedPreferences(HF_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(HF_TOKEN_KEY, token.trim())
            .apply()
    }

    suspend fun updateModel(
        original: ModelEntity,
        newFilename: String,
        newType: ModelType,
        sdCapabilities: String? = original.sdCapabilities,
        sdFamily: String? = original.sdFamily,
        sdVariant: String? = original.sdVariant,
        sdCompatProfiles: String? = original.sdCompatProfiles,
        sdParamsBackendMode: String = original.sdParamsBackendMode,
        sdParamsBackendSpec: String = original.sdParamsBackendSpec,
        sdRuntimeBackendMode: String = original.sdRuntimeBackendMode,
        onnxCapabilities: String? = original.onnxCapabilities,
        onnxAssetKind: String? = original.onnxAssetKind,
        onnxPipelineFamily: String? = original.onnxPipelineFamily,
        onnxReferenceUri: String? = original.onnxReferenceUri,
        onnxReferencePath: String? = original.onnxReferencePath
    ): Result<ModelEntity> = withContext(Dispatchers.IO) {
        try {
            val normalizedFilename = newFilename.trim()
            if (normalizedFilename.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Filename cannot be blank"))
            }

            val sourceFile = File(original.path)
            if (!sourceFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Model file not found"))
            }
            // Validate the payload before any rename or library synchronization.
            // This keeps a failed edit recoverable and prevents a contradictory
            // role/family from becoming trusted metadata.
            val preflightInspection = if (newType.isStableDiffusionArtifact()) {
                runCatching {
                    inspectSdArtifact(sourceFile, newType, sdFamily)
                }.getOrElse { error ->
                    return@withContext Result.failure(error)
                }
            } else {
                null
            }
            val isManagedSource = isManagedModelPath(sourceFile)
            val changesFilesystemIdentity = normalizedFilename != original.filename ||
                isManagedSource && newType != original.type
            if (changesFilesystemIdentity) {
                val libraryDao = AppDatabase.getDatabase(context).modelLibraryDao()
                val pendingArtifacts = runCatching { libraryDao.observePendingArtifacts().first() }
                    .getOrElse { return@withContext Result.failure(it) }
                val provenance = runCatching { libraryDao.getByModelKey(original.filename) }
                    .getOrElse { return@withContext Result.failure(it) }
                if (ModelArtifactLifecycle.isGroupedArtifact(
                        modelPath = original.path,
                        modelKey = original.filename,
                        pendingArtifacts = pendingArtifacts,
                        provenance = provenance
                    )
                ) {
                    return@withContext Result.failure(
                        ModelLibraryException(
                            code = ModelLibraryErrorCode.GROUPED_ARTIFACT_RENAME_UNSUPPORTED,
                            message = context.getString(R.string.model_library_error_grouped_rename)
                        )
                    )
                }
            }

            val finalFile = if (isManagedSource) {
                val targetDir = if (newType == original.type) {
                    sourceFile.parentFile ?: getModelDir(newType)
                } else {
                    getModelDir(newType)
                }.apply { mkdirs() }

                val targetFile = File(targetDir, normalizedFilename)
                if (targetFile.absolutePath != sourceFile.absolutePath) {
                    if (targetFile.exists()) {
                        return@withContext Result.failure(
                            IllegalStateException("A model with that name already exists in the target location")
                        )
                    }

                    val renamed = sourceFile.renameTo(targetFile)
                    if (!renamed) {
                        sourceFile.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        sourceFile.delete()
                    }
                }
                if (targetFile.absolutePath == sourceFile.absolutePath) sourceFile else targetFile
            } else {
                sourceFile
            }
            val inferredFamily = inferSdFamily(newType, original.repoId, normalizedFilename)
            val resolvedFamily = sdFamily ?: inferredFamily.first?.storedValue
            val resolvedVariant = sdVariant ?: inferredFamily.second
            val resolvedFamilyEnum = SdModelFamily.fromStoredValue(resolvedFamily)
            val classificationChanged = newType != original.type ||
                resolvedFamily != original.sdFamily ||
                resolvedVariant != original.sdVariant ||
                sdCapabilities != original.sdCapabilities ||
                sdCompatProfiles != original.sdCompatProfiles ||
                onnxCapabilities != original.onnxCapabilities ||
                onnxAssetKind != original.onnxAssetKind ||
                onnxPipelineFamily != original.onnxPipelineFamily
            val updated = original.copy(
                filename = normalizedFilename,
                path = if (isManagedSource) finalFile.absolutePath else original.path,
                sizeBytes = if (finalFile.exists()) finalFile.length() else original.sizeBytes,
                type = newType,
                sdCapabilities = sdCapabilities
                    ?: defaultCapabilitiesForFamily(resolvedFamilyEnum, newType, resolvedVariant),
                sdFamily = resolvedFamily,
                sdVariant = resolvedVariant,
                sdCompatProfiles = resolveSdCompatProfiles(
                    type = newType,
                    explicitProfiles = sdCompatProfiles,
                    family = resolvedFamilyEnum,
                    variant = resolvedVariant
                ),
                sdParamsBackendMode = sdParamsBackendMode,
                sdParamsBackendSpec = normalizeSdParamsBackendSpec(
                    sdParamsBackendSpec,
                    sdParamsBackendMode
                ),
                sdRuntimeBackendMode = sdRuntimeBackendMode,
                onnxCapabilities = onnxCapabilities,
                onnxAssetKind = onnxAssetKind,
                onnxPipelineFamily = onnxPipelineFamily,
                onnxReferenceUri = onnxReferenceUri,
                onnxReferencePath = onnxReferencePath ?: original.onnxReferencePath,
                classificationSource = if (classificationChanged) {
                    ModelClassificationSource.USER_OVERRIDE.storedValue
                } else {
                    original.classificationSource
                },
                detectedClassificationJson = original.detectedClassificationJson
                    ?: preflightInspection?.toJson()?.let(ModelClassificationPolicy::boundedEvidence)
            ).let { candidate ->
                preflightInspection?.let(candidate::withSdArtifactInspection) ?: candidate
            }

            modelDao.insertModel(updated)
            if (original.filename != updated.filename) {
                modelDao.deleteByFilename(original.filename)
            }
            if (original.filename != updated.filename || original.path != updated.path) {
                val database = AppDatabase.getDatabase(context)
                try {
                    database.withTransaction {
                        val libraryDao = database.modelLibraryDao()
                        libraryDao.updateProvenanceReference(
                            oldModelKey = original.filename,
                            oldPath = original.path,
                            newModelKey = updated.filename,
                            newPath = updated.path
                        )
                        val pendingArtifacts = libraryDao.observePendingArtifacts().first()
                        val rekeyed = pendingArtifacts.map { artifact ->
                            ModelArtifactLifecycle.rekeyPendingArtifact(
                                artifact = artifact,
                                oldModelKey = original.filename,
                                newModelKey = updated.filename,
                                oldPath = original.path,
                                newPath = updated.path,
                                now = System.currentTimeMillis()
                            )
                        }
                        val changed = rekeyed.filterIndexed { index, artifact -> artifact != pendingArtifacts[index] }
                        libraryDao.upsertPendingArtifactsAtomically(changed)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    return@withContext Result.failure(error)
                }
            }

            val syncLibrary =
                ModelLibraryManager.supportsCanonicalLibrary(original.type) ||
                    ModelLibraryManager.supportsCanonicalLibrary(updated.type)
            if (syncLibrary) {
                if (sourceFile.isDirectory) {
                    ModelLibraryManager.renameDirectoryInLibrary(
                        context = context,
                        oldRelativeDir = ModelLibraryManager.relativeDirFor(original.type),
                        oldName = original.filename,
                        newRelativeDir = ModelLibraryManager.relativeDirFor(updated.type),
                        newName = updated.filename,
                        sourceDir = finalFile
                    ).getOrThrow()
                } else {
                    ModelLibraryManager.renameInLibrary(
                        context = context,
                        oldRelativeDir = ModelLibraryManager.relativeDirFor(original.type),
                        oldFilename = original.filename,
                        newRelativeDir = ModelLibraryManager.relativeDirFor(updated.type),
                        newFilename = updated.filename,
                        sourceFile = finalFile
                    ).getOrThrow()
                }
            }

            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isManagedModelPath(file: File): Boolean {
        val internalRoot = context.filesDir
        val externalRoot = context.getExternalFilesDir(null)
        val onnxRoot = OnnxStorage.managedModelsRoot(context)
        val legacyOnnxRoot = OnnxStorage.legacyManagedModelsRoot()
        return isWithinRoot(file, internalRoot) ||
            (externalRoot != null && isWithinRoot(file, externalRoot)) ||
            isWithinRoot(file, onnxRoot) ||
            isWithinRoot(file, legacyOnnxRoot)
    }

    private fun isWithinRoot(file: File, root: File): Boolean {
        val filePath = file.canonicalFile.absolutePath
        val rootPath = root.canonicalFile.absolutePath
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }

    companion object {
        private const val HF_PREFS_NAME = "litert_model_repository"
        private const val HF_TOKEN_KEY = "hugging_face_token"
        private const val MANAGED_MODEL_STORAGE_RECONCILED_KEY = "managed_model_storage_reconciled_v2"

        /** Synchronous, payload-free inspection for foreground services. */
        fun inspectSdArtifact(
            file: File,
            configuredType: ModelType,
            force: Boolean = false
        ): SdArtifactInspection = SdInspectionCache.inspect(
            file = file,
            configuredRole = configuredType.sdArtifactRole(),
            force = force
        )

        /**
         * Validate payload integrity only. Role/family disagreements are
         * semantic warnings surfaced by the resolver and model UI; an explicit
         * user classification remains authoritative at runtime.
         */
        @Suppress("UNUSED_PARAMETER")
        fun validateSdArtifactInspection(
            configuredType: ModelType,
            inspection: SdArtifactInspection,
            configuredFamily: String? = null
        ): Result<SdArtifactInspection> {
            if (!configuredType.isStableDiffusionArtifact()) return Result.success(inspection)

            val warnings = inspection.warnings.map { it.lowercase(Locale.US) }
            val structuralFailure = inspection.format == com.example.llamadroid.sd.SdArtifactFormat.UNKNOWN ||
                (inspection.format != com.example.llamadroid.sd.SdArtifactFormat.CKPT &&
                    (inspection.tensorCount == 0L || warnings.any { warning ->
                        warning.contains("truncated") ||
                            warning.contains("invalid") ||
                            warning.contains("failed") ||
                            warning.contains("not valid") ||
                            warning.contains("exceeds") ||
                            warning.contains("no tensor")
                    }))
            // CKPT is intentionally never deserialized.  It is accepted only
            // as an explicitly configured full model at low confidence.
            if (structuralFailure ||
                (inspection.format == com.example.llamadroid.sd.SdArtifactFormat.CKPT &&
                    configuredType != ModelType.SD_CHECKPOINT)
            ) {
                return Result.failure(
                    SdArtifactValidationException(
                        code = SdArtifactValidationCode.INVALID_ARTIFACT,
                        detail = "Stable Diffusion artifact headers are malformed, unsupported, or incomplete"
                    )
                )
            }

            return Result.success(inspection)
        }

        fun resolveOnnxCapabilities(
            explicitCapabilities: String?,
            detectedCapabilities: Set<String>
        ): String? {
            val explicit = explicitCapabilities.parseOnnxCapabilities()
            val resolved = if (detectedCapabilities.isNotEmpty()) {
                if (explicit.isEmpty()) detectedCapabilities else explicit + detectedCapabilities
            } else {
                explicit
            }
            return buildOnnxCapabilities(*resolved.toTypedArray())
        }

        fun buildImportedOnnxModelEntity(
            filename: String,
            path: String,
            sizeBytes: Long,
            repoId: String,
            installSource: com.example.llamadroid.onnx.OnnxInstallSource,
            detectedCapabilities: Set<String>,
            referenceUri: String?,
            referencePath: String?
        ): ModelEntity = buildOnnxImageGenModelEntity(
            filename = filename,
            path = path,
            sizeBytes = sizeBytes,
            repoId = repoId,
            installSource = installSource,
            supportedCapabilities = detectedCapabilities.ifEmpty { setOf(ONNX_CAPABILITY_TXT2IMG) },
            referenceUri = referenceUri,
            referencePath = referencePath
        ).copy(
            classificationSource = ModelClassificationSource.AUTO.storedValue,
            detectedClassificationJson = ModelClassificationPolicy.evidenceJson(
                com.example.llamadroid.data.model.library.ModelClassification(
                    family = com.example.llamadroid.data.model.library.ModelFamily.ONNX,
                    type = ModelType.ONNX_IMAGE_GEN,
                    role = "image_gen",
                    capabilities = detectedCapabilities
                )
            )
        )

        fun isSupportedMediaModelFile(path: String): Boolean {
            val normalized = path.lowercase()
            return normalized.endsWith(".gguf") ||
                normalized.endsWith(".safetensors") ||
                normalized.endsWith(".onnx") ||
                normalized.endsWith(".ort")
        }
    }

    private suspend fun pruneLegacyPortableModelRows() = withContext(Dispatchers.IO) {
        val onnxManagedRoot = OnnxStorage.managedModelsRoot(context)
        val legacyPortableTypes = listOf(
            ModelType.ONNX_IMAGE_GEN,
            ModelType.ONNX_TTS,
            ModelType.ONNX_BACKGROUND_REMOVAL,
            ModelType.ONNX_IMAGE_UPSCALER
        )
        modelDao.getModelsByTypesSync(legacyPortableTypes).forEach { model ->
            val modelFile = File(model.path)
            if (!isWithinRoot(modelFile, onnxManagedRoot)) {
                modelDao.deleteModel(model)
                DebugLog.log(
                    "ModelRepository: Removed legacy external ONNX row for ${model.filename}; please re-import or re-download it."
                )
            }
        }
    }

    private suspend fun reconcileManagedModelCopiesIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(HF_PREFS_NAME, Context.MODE_PRIVATE)
        reconciliationMutex.withLock {
            recoverUnindexedLlmFiles()
            if (prefs.getBoolean(MANAGED_MODEL_STORAGE_RECONCILED_KEY, false)) {
                return@withLock
            }

            val relevantTypes = listOf(
                ModelType.LLM,
                ModelType.LLM_DRAFT,
                ModelType.LORA,
                ModelType.EMBEDDING,
                ModelType.VISION,
                ModelType.VISION_PROJECTOR,
                ModelType.MMPROJ,
                ModelType.LLAMA_TTS,
                ModelType.LLAMA_TTS_COMPANION,
                ModelType.LITERT_AUDIO_DIT,
                ModelType.LITERT_AUDIO_COMPONENT,
                ModelType.WHISPER,
                ModelType.SD_CHECKPOINT,
                ModelType.SD_UPSCALER,
                ModelType.SD_DIFFUSION,
                ModelType.SD_CLIP_L,
                ModelType.SD_CLIP_G,
                ModelType.SD_T5XXL,
                ModelType.SD_TAE,
                ModelType.SD_VAE,
                ModelType.SD_LORA,
                ModelType.SD_CONTROLNET,
                ModelType.SD_PHOTOMAKER,
                ModelType.SD_CLIP_VISION,
                ModelType.SD_IP_ADAPTER,
                ModelType.SD_ADETAILER,
                ModelType.SD_AUDIO_VAE,
                ModelType.SD_EMBEDDINGS_CONNECTORS,
                ModelType.SD_MOTION_MODULE,
                ModelType.SD_LLM
            )
            modelDao.getModelsByTypesSync(relevantTypes).forEach { model ->
                runCatching {
                    reconcileModelCopy(model)
                }.onFailure { error ->
                    Log.w(
                        "ModelRepository",
                        "Skipping managed model reconciliation for ${model.filename}: ${error.message}",
                        error
                    )
                    DebugLog.log(
                        "ModelRepository: Skipping managed model reconciliation for ${model.filename}: ${error.message}"
                    )
                }
            }
            prefs.edit().putBoolean(MANAGED_MODEL_STORAGE_RECONCILED_KEY, true).apply()
        }
    }

    private suspend fun recoverUnindexedLlmFiles() =
        recoverManagedGgufModels(context, modelDao, getModelDir(ModelType.LLM))

    private suspend fun reconcileModelCopy(model: ModelEntity) {
        if (!ModelLibraryManager.usesManagedExternalCanonicalStorage(model.type)) return

        val relativeDir = ModelLibraryManager.relativeDirFor(model.type)
        val canonicalFilename = ModelLibraryManager.canonicalFilename(model.filename)
        val targetFile = File(getModelDir(model.type), canonicalFilename)
        val runtimeFile = File(model.path)
        val runtimeExists = runtimeFile.exists() && runtimeFile.isFile
        val runtimeReadable = runtimeExists && isReadableModelFile(runtimeFile)
        val runtimeManaged = runtimeExists && isManagedModelPath(runtimeFile)
        val targetExists = targetFile.exists() && targetFile.isFile
        val libraryExists = ModelLibraryManager.hasLibraryFile(context, relativeDir, canonicalFilename)
        val librarySize = ModelLibraryManager.libraryFileSize(context, relativeDir, canonicalFilename)

        when {
            runtimeReadable && samePhysicalPath(runtimeFile, targetFile) -> {
                updateModelPathIfNeeded(model, targetFile)
            }

            runtimeReadable && runtimeManaged -> {
                migrateManagedRuntimeCopy(model, runtimeFile, targetFile)
            }

            runtimeReadable -> {
                copyFileIntoManagedRuntime(runtimeFile, targetFile)
                updateModelPathIfNeeded(model, targetFile)
            }

            targetExists -> {
                updateModelPathIfNeeded(model, targetFile)
            }

            libraryExists -> {
                ModelLibraryManager.copyLibraryFileToManagedFile(
                    context = context,
                    relativeDir = relativeDir,
                    filename = canonicalFilename,
                    targetFile = targetFile
                )
                updateModelPathIfNeeded(model, targetFile)
            }

            runtimeExists && !runtimeReadable -> {
                DebugLog.log(
                    "ModelRepository: Skipping unreadable legacy model path for ${model.filename}: ${runtimeFile.absolutePath}"
                )
            }
        }

        deleteLegacyLibraryDuplicate(relativeDir, canonicalFilename, targetFile, librarySize)
    }

    private suspend fun migrateManagedRuntimeCopy(model: ModelEntity, sourceFile: File, targetFile: File) {
        if (!samePhysicalPath(sourceFile, targetFile)) {
            if (!targetFile.exists() || targetFile.length() != sourceFile.length()) {
                targetFile.parentFile?.mkdirs()
                val renamed = runCatching { sourceFile.renameTo(targetFile) }.getOrDefault(false)
                if (!renamed) {
                    sourceFile.inputStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (sourceFile.exists()) {
                        sourceFile.delete()
                    }
                }
            } else if (sourceFile.exists()) {
                sourceFile.delete()
            }
        }
        updateModelPathIfNeeded(model, targetFile)
    }

    private fun copyFileIntoManagedRuntime(sourceFile: File, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.migrating")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        sourceFile.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        replaceFileAtomically(tempFile, targetFile)
    }

    private suspend fun updateModelPathIfNeeded(model: ModelEntity, targetFile: File) {
        if (!targetFile.exists()) return
        val targetPath = targetFile.absolutePath
        if (model.path == targetPath && model.sizeBytes == targetFile.length()) return
        runCatching {
            modelDao.insertModel(
                model.copy(
                    path = targetPath,
                    sizeBytes = targetFile.length()
                )
            )
        }.onFailure {
            Log.w("ModelRepository", "Failed to update managed model path for ${model.filename}: ${it.message}")
        }
    }

    private fun isReadableModelFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching {
            file.inputStream().use { true }
        }.getOrElse { false }
    }

    private fun deleteLegacyLibraryDuplicate(
        relativeDir: String,
        filename: String,
        managedFile: File,
        librarySize: Long?
    ) {
        if (!managedFile.exists()) return
        if (!ModelLibraryManager.hasLibraryFile(context, relativeDir, filename)) return
        if (librarySize == null || librarySize <= 0L || librarySize == managedFile.length()) {
            ModelLibraryManager.deleteFromLibrary(context, relativeDir, filename)
        }
    }

    private fun replaceFileAtomically(tempFile: File, targetFile: File) {
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
        }
    }

    private fun samePhysicalPath(first: File, second: File): Boolean {
        return runCatching {
            first.canonicalFile == second.canonicalFile
        }.getOrDefault(first.absolutePath == second.absolutePath)
    }
}

/** Stable Diffusion file types that are structurally inspectable. */
fun ModelType.isStableDiffusionArtifact(): Boolean = when (this) {
    ModelType.SD_CHECKPOINT,
    ModelType.SD_VAE,
    ModelType.SD_LORA,
    ModelType.SD_DIFFUSION,
    ModelType.SD_CLIP_L,
    ModelType.SD_CLIP_G,
    ModelType.SD_T5XXL,
    ModelType.SD_TAE,
    ModelType.SD_CONTROLNET,
    ModelType.SD_PHOTOMAKER,
    ModelType.SD_CLIP_VISION,
    ModelType.SD_IP_ADAPTER,
    ModelType.SD_AUDIO_VAE,
    ModelType.SD_EMBEDDINGS_CONNECTORS,
    ModelType.SD_MOTION_MODULE -> true
    // Textual-inversion .pt files, native upscalers, and ADetailer ONNX
    // detectors are SD features but not SafeTensors/GGUF pipeline artifacts.
    // Keep their established validators instead of treating their formats as
    // corrupt model headers.
    ModelType.SD_TEXTUAL_INVERSION,
    ModelType.SD_UPSCALER,
    ModelType.SD_ADETAILER -> false
    // Video companion artifacts are structurally inspectable native model
    // files and use the appended SdArtifactRole values below.
    else -> false
}

private fun ModelType.sdArtifactRole(): SdArtifactRole? = when (this) {
    ModelType.SD_CHECKPOINT -> SdArtifactRole.FULL_MODEL
    ModelType.SD_DIFFUSION -> SdArtifactRole.STANDALONE_DIFFUSION
    ModelType.SD_VAE -> SdArtifactRole.VAE
    ModelType.SD_TAE -> SdArtifactRole.TAE
    ModelType.SD_CLIP_L -> SdArtifactRole.CLIP_L
    ModelType.SD_CLIP_G -> SdArtifactRole.CLIP_G
    ModelType.SD_T5XXL -> SdArtifactRole.T5XXL
    ModelType.SD_LORA -> SdArtifactRole.LORA
    ModelType.SD_CONTROLNET -> SdArtifactRole.CONTROLNET
    ModelType.SD_AUDIO_VAE -> SdArtifactRole.AUDIO_VAE
    ModelType.SD_EMBEDDINGS_CONNECTORS -> SdArtifactRole.EMBEDDINGS_CONNECTORS
    ModelType.SD_MOTION_MODULE -> SdArtifactRole.MOTION_MODULE
    else -> null
}

enum class SdArtifactValidationCode {
    INVALID_ARTIFACT,
    ROLE_CONTRADICTION,
    FAMILY_CONTRADICTION
}

class SdArtifactValidationException(
    val code: SdArtifactValidationCode,
    detail: String
) : IllegalStateException(detail)

fun buildDownloadTaskId(repoId: String, filename: String, type: ModelType): String {
    val repo = repoId.trim().ifBlank { "local" }
    val normalizedFilename = filename.trim().ifBlank { "model" }
    return listOf(
        type.name.lowercase(Locale.US),
        repo,
        normalizedFilename
    ).joinToString("|")
}

// Singleton to persist download progress across navigation
object DownloadProgressHolder {
    const val INDETERMINATE = -2f

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = _status.asStateFlow()
    
    // Track filename for each exact download task for cancellation and display.
    private val filenameMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    fun updateProgress(repoId: String, filename: String, value: Float) {
        filenameMap[repoId] = filename
        _progress.update { current -> current + (repoId to value) }
    }
    
    /** Update by repoId only (when filename already tracked) */
    fun updateProgress(repoId: String, value: Float) {
        _progress.update { current -> current + (repoId to value) }
    }

    fun updateStatus(repoId: String, value: String) {
        _status.update { current -> current + (repoId to value) }
    }

    fun getStatus(repoId: String): String? = _status.value[repoId]
    
    /** Find repoId by filename (for service callback) */
    fun findRepoIdByFilename(filename: String): String? {
        return filenameMap.entries.find { it.value == filename }?.key
    }
    
    fun removeProgress(repoId: String) {
        filenameMap.remove(repoId)
        _progress.update { current -> current - repoId }
        _status.update { current -> current - repoId }
    }
    
    fun getFilename(repoId: String): String? = filenameMap[repoId]

    fun isFilenameTracked(filename: String): Boolean = filenameMap.values.contains(filename)

    fun trackedFilenames(): Set<String> = filenameMap.values.toSet()

    fun getTrackedFilenames(): Set<String> = trackedFilenames()
}

/**
 * Type of file in the repository
 */
enum class FileType {
    MODEL,           // Main GGUF model file
    VISION_PROJECTOR // mmproj file for vision support
}

/**
 * Simple data class for file info with size
 */
data class FileInfo(
    val filename: String,
    val sizeBytes: Long,
    val type: FileType = FileType.MODEL
) {
    fun formattedSize(): String {
        return when {
            sizeBytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f KB", sizeBytes / 1_000.0)
            else -> "$sizeBytes B"
        }
    }
    
    val isVisionProjector: Boolean get() = type == FileType.VISION_PROJECTOR
}

/**
 * Contains files from a repository with vision support information
 */
data class RepoFiles(
    val modelFiles: List<FileInfo>,
    val visionFiles: List<FileInfo>,
    val hasVisionSupport: Boolean
)

/**
 * Holds pending download info for async downloads
 */
data class PendingDownload(
    val filename: String,
    val repoId: String,
    val progressKey: String,
    val type: com.example.llamadroid.data.db.ModelType,
    val destPath: String,
    val isVision: Boolean = false,
    val sdCapabilities: String? = null,
    val sdFamily: String? = null,
    val sdVariant: String? = null,
    val sdCompatProfiles: String? = null,
    val onnxCapabilities: String? = null,
    val onnxAssetKind: String? = null,
    val onnxPipelineFamily: String? = null,
    val onnxReferenceUri: String? = null,
    val onnxReferencePath: String? = null,
    val onnxInstallKind: String? = null,
    val onnxInstallDirPath: String? = null,
    val huggingFaceToken: String? = null,
    val liteRtDisplayName: String? = null,
    val liteRtSourceUri: String? = null,
    val liteRtBackendPreference: String? = null,
    val liteRtSupportsCpu: Boolean? = null,
    val liteRtSupportsGpu: Boolean? = null,
    val liteRtSupportsVision: Boolean? = null,
    val liteRtSupportsAudio: Boolean? = null,
    val liteRtSupportsEmbedding: Boolean? = null,
    val liteRtMaxContextTokens: Int? = null,
    /** Durable source/provenance identity; nullable for legacy downloads. */
    val sourceId: String? = null,
    /** Bounded library family hint; does not replace the existing ModelType. */
    val artifactFamily: String? = null,
    val artifactRole: String? = null,
    /** Pending staged-artifact row used when a custom file needs inspection. */
    val pendingArtifactId: String? = null,
    /** AUTO/CATALOG/USER_OVERRIDE/LEGACY for the effective task selection. */
    val classificationSource: String = "LEGACY",
    /** Immutable bounded inspector evidence, if already available. */
    val detectedClassificationJson: String? = null,
    val stageOnly: Boolean = false
)

object PendingDownloadHolder {
    private val pendingDownloads = java.util.concurrent.ConcurrentHashMap<String, PendingDownload>()

    /** Re-registers a recovered task after a catalog has refreshed its metadata. */
    internal fun putPending(downloadId: String, pending: PendingDownload) {
        pendingDownloads[downloadId] = pending
        if (downloadId != pending.filename) {
            pendingDownloads[pending.filename] = pending
        }
    }
    
    fun addPending(
        downloadId: String? = null,
        filename: String,
        repoId: String,
        progressKey: String = repoId,
        type: com.example.llamadroid.data.db.ModelType,
        destPath: String,
        isVision: Boolean = false,
        sdCapabilities: String? = null,
        sdFamily: String? = null,
        sdVariant: String? = null,
        sdCompatProfiles: String? = null,
        onnxCapabilities: String? = null,
        onnxAssetKind: String? = null,
        onnxPipelineFamily: String? = null,
        onnxReferenceUri: String? = null,
        onnxReferencePath: String? = null,
        onnxInstallKind: String? = null,
        onnxInstallDirPath: String? = null,
        huggingFaceToken: String? = null,
        liteRtDisplayName: String? = null,
        liteRtSourceUri: String? = null,
        liteRtBackendPreference: String? = null,
        liteRtSupportsCpu: Boolean? = null,
        liteRtSupportsGpu: Boolean? = null,
        liteRtSupportsVision: Boolean? = null,
        liteRtSupportsAudio: Boolean? = null,
        liteRtSupportsEmbedding: Boolean? = null,
        liteRtMaxContextTokens: Int? = null,
        sourceId: String? = null,
        artifactFamily: String? = null,
        artifactRole: String? = null,
        pendingArtifactId: String? = null,
        classificationSource: String = "LEGACY",
        detectedClassificationJson: String? = null,
        stageOnly: Boolean = false
    ) {
        val taskId = downloadId ?: progressKey
        val pending = PendingDownload(
            filename = filename,
            repoId = repoId,
            progressKey = progressKey,
            type = type,
            destPath = destPath,
            isVision = isVision,
            sdCapabilities = sdCapabilities,
            sdFamily = sdFamily,
            sdVariant = sdVariant,
            sdCompatProfiles = sdCompatProfiles,
            onnxCapabilities = onnxCapabilities,
            onnxAssetKind = onnxAssetKind,
            onnxPipelineFamily = onnxPipelineFamily,
            onnxReferenceUri = onnxReferenceUri,
            onnxReferencePath = onnxReferencePath,
            onnxInstallKind = onnxInstallKind,
            onnxInstallDirPath = onnxInstallDirPath,
            huggingFaceToken = huggingFaceToken,
            liteRtDisplayName = liteRtDisplayName,
            liteRtSourceUri = liteRtSourceUri,
            liteRtBackendPreference = liteRtBackendPreference,
            liteRtSupportsCpu = liteRtSupportsCpu,
            liteRtSupportsGpu = liteRtSupportsGpu,
            liteRtSupportsVision = liteRtSupportsVision,
            liteRtSupportsAudio = liteRtSupportsAudio,
            liteRtSupportsEmbedding = liteRtSupportsEmbedding,
            liteRtMaxContextTokens = liteRtMaxContextTokens,
            sourceId = sourceId,
            artifactFamily = artifactFamily,
            artifactRole = artifactRole,
            pendingArtifactId = pendingArtifactId,
            classificationSource = classificationSource,
            detectedClassificationJson = detectedClassificationJson,
            stageOnly = stageOnly
        )
        putPending(taskId, pending)
    }
    
    fun getPending(downloadId: String): PendingDownload? = pendingDownloads[downloadId]

    /** Snapshot process-local registrations before their Room task is visible. */
    fun allPending(): List<PendingDownload> = synchronized(pendingDownloads) {
        pendingDownloads.values.distinctBy { it.progressKey to it.destPath }
    }

    fun getAllPending(): List<PendingDownload> = allPending()

    fun addPendingFrom(task: com.example.llamadroid.data.db.DownloadTaskEntity) {
        val pending = task.toPendingDownload()
        pendingDownloads[task.id] = pending
        if (task.id != task.filename) {
            pendingDownloads[task.filename] = pending
        }
    }
    
    fun removePending(downloadId: String) {
        val removed = pendingDownloads.remove(downloadId)
        if (removed != null) {
            pendingDownloads.entries.removeAll { (_, value) ->
                value.progressKey == removed.progressKey && value.filename == removed.filename
            }
        }
    }
}
