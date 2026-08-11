package com.example.llamadroid.data.model

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.db.ModelBackupPolicy
import com.example.llamadroid.data.db.ModelDao
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.parseOnnxCapabilities
import com.example.llamadroid.data.db.ONNX_CAPABILITY_TXT2IMG
import com.example.llamadroid.sd.buildSdCompatProfiles
import com.example.llamadroid.sd.defaultCompatProfilesFor
import com.example.llamadroid.sd.defaultCapabilitiesForFamily
import com.example.llamadroid.sd.inferSdFamily
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_BACKGROUND_REMOVAL_FILE
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_SDAI_CATALOG_BUNDLE
import com.example.llamadroid.onnx.ONNX_ASSET_KIND_SUPERTONIC_CATALOG_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_FILE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_HF_TREE_BUNDLE
import com.example.llamadroid.onnx.ONNX_PIPELINE_FAMILY_SDAI_LOCAL_DIFFUSION
import com.example.llamadroid.onnx.OnnxCatalogEntry
import com.example.llamadroid.onnx.OnnxImportSupport
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.buildOnnxCatalogStableId
import com.example.llamadroid.onnx.buildOnnxImageGenModelEntity
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.Locale

class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao
) {
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
        listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.EMBEDDING)
    ).onStart {
        pruneLegacyPortableModelRows()
        reconcileManagedModelCopiesIfNeeded()
    }

    fun getModelManagerModels(): Flow<List<ModelEntity>> = modelDao.getModelsByTypes(
        listOf(ModelType.LLM, ModelType.LLM_DRAFT, ModelType.LORA, ModelType.VISION_PROJECTOR, ModelType.EMBEDDING, ModelType.QUADTRIX)
    ).onStart {
        pruneLegacyPortableModelRows()
        reconcileManagedModelCopiesIfNeeded()
    }
    
    suspend fun searchModels(query: String, filter: String? = null): List<HfModelDto> = withContext(Dispatchers.IO) {
        try {
            // Enhance query with filter keyword for better search results
            val enhancedQuery = if (filter != null && !query.contains(filter, ignoreCase = true)) {
                "$query $filter"
            } else {
                query
            }
            hfService.searchModels(enhancedQuery, filter = filter, limit = 40)
        } catch (e: Exception) {
            DebugLog.log("[HF-SEARCH] Error: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
    
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
        onnxReferencePath: String? = null
    ) {
        val modelDir = getModelDir(type)
        val localFilename = chooseUniqueDownloadFilename(
            requestedFilename = filename,
            type = type,
            modelDir = modelDir
        )
        val modelUrl = "https://huggingface.co/$repoId/resolve/main/$filename"
        val destFile = File(modelDir, localFilename)
        val inferredFamily = inferSdFamily(type, repoId, filename)
        val resolvedFamily = sdFamily ?: inferredFamily.first?.storedValue
        val resolvedVariant = sdVariant ?: inferredFamily.second
        val resolvedCapabilities = sdCapabilities ?: defaultCapabilitiesForFamily(inferredFamily.first, type)
        val resolvedCompatProfiles = sdCompatProfiles ?: buildSdCompatProfiles(
            *defaultCompatProfilesFor(type).toTypedArray()
        )
        
        val progressKey = buildDownloadTaskId(repoId, localFilename, type)
        
        // Track progress under unique key for UI display
        DownloadProgressHolder.updateProgress(progressKey, localFilename, 0f)
        
        // Start foreground service for background downloads with notification
        // Must be called on main thread for foreground service
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            com.example.llamadroid.service.DownloadService.startDownload(
                context = context,
                url = modelUrl,
                destPath = destFile.absolutePath,
                filename = localFilename,
                downloadId = progressKey
            )
        }
        
        // Monitor progress from DownloadProgressHolder (updated by DownloadService)
        // Wait for completion (progress reaches 1.0 or -1.0 for error)
        var lastProgress = 0f
        while (true) {
                    kotlinx.coroutines.delay(500) // Check every 500ms
                    val progressMap = DownloadProgressHolder.progress.value
                    // Check by progressKey (set by us)
            val progress = progressMap[progressKey] ?: 0f
            
            if (progress != lastProgress && progress >= 0f) {
                lastProgress = progress
                DownloadProgressHolder.updateProgress(progressKey, progress)
            }
            
            if (progress >= 1f) {
                // Download complete - save to DB
                val entity = ModelEntity(
                    filename = localFilename,
                    path = destFile.absolutePath,
                    sizeBytes = destFile.length(),
                    type = type,
                    repoId = repoId,
                    isVision = isVision,
                    isDownloaded = true,
                    sdCapabilities = resolvedCapabilities,
                    sdFamily = resolvedFamily,
                    sdVariant = resolvedVariant,
                    sdCompatProfiles = resolvedCompatProfiles,
                    onnxCapabilities = onnxCapabilities,
                    onnxAssetKind = onnxAssetKind,
                    onnxPipelineFamily = onnxPipelineFamily,
                    onnxReferenceUri = onnxReferenceUri,
                    onnxReferencePath = onnxReferencePath
                )
                modelDao.insertModel(entity)
                DownloadProgressHolder.removeProgress(progressKey)
                DebugLog.log("ModelRepository: Saved $localFilename to DB as $type")
                break
            } else if (progress < 0f && progress != DownloadProgressHolder.INDETERMINATE) {
                // Download failed
                DownloadProgressHolder.removeProgress(progressKey)
                DebugLog.log("ModelRepository: Download failed for $localFilename")
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
        localFilenameOverride: String? = null
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
        val resolvedCapabilities = sdCapabilities ?: defaultCapabilitiesForFamily(inferredFamily.first, type)
        val resolvedCompatProfiles = sdCompatProfiles ?: buildSdCompatProfiles(
            *defaultCompatProfilesFor(type).toTypedArray()
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
            onnxReferencePath = onnxReferencePath
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
            huggingFaceToken = if (entry.gated) huggingFaceToken() else null
        )

        com.example.llamadroid.service.DownloadService.startDownload(
            context = context,
            url = entry.downloadUrl,
            destPath = tempDownload.absolutePath,
            filename = modelId,
            downloadId = progressKey
        )
    }
    
    suspend fun deleteModel(model: ModelEntity) {
        reconcileManagedModelCopiesIfNeeded()
        deleteModelArtifacts(model)
        modelDao.deleteModel(model)
    }

    suspend fun deleteModelArtifacts(model: ModelEntity) {
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
        managedPaths.forEach { file ->
            if (file.exists() && isManagedModelPath(file)) {
                if (file.isDirectory) {
                    OnnxImportSupport.deleteRecursively(file)
                } else {
                    file.delete()
                }
            }
        }
        directPaths.forEach { file ->
            if (file.exists()) {
                if (file.isDirectory) {
                    OnnxImportSupport.deleteRecursively(file)
                } else {
                    file.delete()
                }
            }
        }
        if (
            model.type == ModelType.ONNX_IMAGE_GEN ||
            model.type == ModelType.ONNX_TTS ||
            model.type == ModelType.ONNX_BACKGROUND_REMOVAL ||
            model.type == ModelType.ONNX_IMAGE_UPSCALER
        ) {
            // ONNX payloads are now internal-only and no longer mirrored to a shared model library folder.
        } else {
            ModelLibraryManager.deleteFromLibrary(
                context = context,
                relativeDir = ModelLibraryManager.relativeDirFor(model.type),
                filename = model.filename
            )
        }
    }
    
    suspend fun insertModel(model: ModelEntity) {
        modelDao.insertModel(model)
    }

    suspend fun updateVisionSupport(filename: String, isVision: Boolean) {
        modelDao.updateVisionSupport(filename, isVision)
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
            val isManagedSource = isManagedModelPath(sourceFile)

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
            val updated = original.copy(
                filename = normalizedFilename,
                path = if (isManagedSource) finalFile.absolutePath else original.path,
                sizeBytes = if (finalFile.exists()) finalFile.length() else original.sizeBytes,
                type = newType,
                sdCapabilities = sdCapabilities ?: defaultCapabilitiesForFamily(inferredFamily.first, newType),
                sdFamily = sdFamily ?: inferredFamily.first?.storedValue,
                sdVariant = sdVariant ?: inferredFamily.second,
                sdCompatProfiles = sdCompatProfiles ?: buildSdCompatProfiles(
                    *defaultCompatProfilesFor(newType).toTypedArray()
                ),
                sdParamsBackendMode = sdParamsBackendMode,
                sdRuntimeBackendMode = sdRuntimeBackendMode,
                onnxCapabilities = onnxCapabilities,
                onnxAssetKind = onnxAssetKind,
                onnxPipelineFamily = onnxPipelineFamily,
                onnxReferenceUri = onnxReferenceUri,
                onnxReferencePath = onnxReferencePath ?: original.onnxReferencePath
            )

            modelDao.insertModel(updated)
            if (original.filename != updated.filename) {
                modelDao.deleteByFilename(original.filename)
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
        if (prefs.getBoolean(MANAGED_MODEL_STORAGE_RECONCILED_KEY, false)) {
            return@withContext
        }

        reconciliationMutex.withLock {
            if (prefs.getBoolean(MANAGED_MODEL_STORAGE_RECONCILED_KEY, false)) {
                return@withLock
            }

            val relevantTypes = listOf(
                ModelType.LLM,
                ModelType.LORA,
                ModelType.EMBEDDING,
                ModelType.VISION,
                ModelType.VISION_PROJECTOR,
                ModelType.MMPROJ,
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
                ModelType.SD_IP_ADAPTER
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
    private val filenameMap = mutableMapOf<String, String>()
    
    fun updateProgress(repoId: String, filename: String, value: Float) {
        filenameMap[repoId] = filename
        _progress.value = _progress.value.toMutableMap().apply { put(repoId, value) }
    }
    
    /** Update by repoId only (when filename already tracked) */
    fun updateProgress(repoId: String, value: Float) {
        _progress.value = _progress.value.toMutableMap().apply { put(repoId, value) }
    }

    fun updateStatus(repoId: String, value: String) {
        _status.value = _status.value.toMutableMap().apply { put(repoId, value) }
    }

    fun getStatus(repoId: String): String? = _status.value[repoId]
    
    /** Find repoId by filename (for service callback) */
    fun findRepoIdByFilename(filename: String): String? {
        return filenameMap.entries.find { it.value == filename }?.key
    }
    
    fun removeProgress(repoId: String) {
        filenameMap.remove(repoId)
        _progress.value = _progress.value.toMutableMap().apply { remove(repoId) }
        _status.value = _status.value.toMutableMap().apply { remove(repoId) }
    }
    
    fun getFilename(repoId: String): String? = filenameMap[repoId]

    fun isFilenameTracked(filename: String): Boolean = filenameMap.values.contains(filename)
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
            sizeBytes >= 1_000_000_000 -> String.format("%.2f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format("%.0f KB", sizeBytes / 1_000.0)
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
    val liteRtMaxContextTokens: Int? = null
)

object PendingDownloadHolder {
    private val pendingDownloads = mutableMapOf<String, PendingDownload>()
    
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
        liteRtMaxContextTokens: Int? = null
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
            liteRtMaxContextTokens = liteRtMaxContextTokens
        )
        pendingDownloads[taskId] = pending
        if (taskId != filename) {
            pendingDownloads[filename] = pending
        }
    }
    
    fun getPending(downloadId: String): PendingDownload? = pendingDownloads[downloadId]

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
