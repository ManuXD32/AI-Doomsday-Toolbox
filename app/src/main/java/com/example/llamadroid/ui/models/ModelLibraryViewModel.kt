package com.example.llamadroid.ui.models

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import com.example.llamadroid.data.db.ModelBundleEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.library.HfFolderListing
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelArtifactReference
import com.example.llamadroid.data.model.library.ModelLibraryErrorCode
import com.example.llamadroid.data.model.library.ModelLibraryException
import com.example.llamadroid.data.model.library.ModelSourceDraft
import com.example.llamadroid.data.model.library.ModelSourceRepository
import com.example.llamadroid.data.model.library.ModelSourceUrlValidator
import com.example.llamadroid.data.model.library.HuggingFaceHttpException
import com.example.llamadroid.data.model.library.InstalledModelAsset
import com.example.llamadroid.data.model.library.ModelLibraryQueueScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ModelLibraryMessage(
    val code: ModelLibraryErrorCode,
    val success: Boolean = false
)

/**
 * UI boundary for the persistent model source library. The view model keeps
 * bearer credentials request-scoped by reading the existing HF preference only
 * when an operation starts; it never places the token in UI state or rows.
 */
class ModelLibraryViewModel(
    private val repository: ModelSourceRepository,
    private val appContext: Context,
    private val huggingFaceToken: () -> String
) : ViewModel() {
    val sources: StateFlow<List<ModelSourceEntity>> = repository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bundles = repository.bundles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingArtifacts: StateFlow<List<PendingModelArtifactEntity>> = repository.pendingArtifacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val installedModels: StateFlow<List<ModelEntity>> = AppDatabase.getDatabase(appContext).modelDao()
        .getAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** LiteRT has a dedicated runtime table and must remain separate from ModelEntity. */
    val installedLiteRtModels: StateFlow<List<LiteRtModelEntity>> =
        AppDatabase.getDatabase(appContext).liteRtModelDao()
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val provenance: StateFlow<List<ModelProvenanceEntity>> = repository.provenance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val operationLock = Any()
    private var operationCount = 0

    private fun beginOperation(allowWhileBusy: Boolean = false): Boolean = synchronized(operationLock) {
        if (operationCount > 0 && !allowWhileBusy) return@synchronized false
        operationCount++
        _busy.value = true
        true
    }

    private fun endOperation() = synchronized(operationLock) {
        operationCount = (operationCount - 1).coerceAtLeast(0)
        _busy.value = operationCount > 0
    }

    private val _message = MutableStateFlow<ModelLibraryMessage?>(null)
    val message: StateFlow<ModelLibraryMessage?> = _message.asStateFlow()

    private val _folderListing = MutableStateFlow<HfFolderListing?>(null)
    val folderListing: StateFlow<HfFolderListing?> = _folderListing.asStateFlow()

    private val _browsingSourceId = MutableStateFlow<String?>(null)
    val browsingSourceId: StateFlow<String?> = _browsingSourceId.asStateFlow()
    private var folderRequestGeneration = 0L

    fun observeBundleItems(bundleId: String): Flow<List<com.example.llamadroid.data.db.ModelBundleItemEntity>> =
        repository.observeBundleItems(bundleId)

    fun clearMessage() {
        _message.value = null
    }

    fun closeFolderListing() {
        folderRequestGeneration++
        _folderListing.value = null
        _browsingSourceId.value = null
    }

    fun saveSource(family: ModelFamily, url: String, label: String?, id: String? = null) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.INVALID_URL, success = true)) {
            repository.saveSource(
                ModelSourceDraft(
                    family = family,
                    url = url,
                    label = label?.trim()?.takeIf { it.isNotBlank() },
                    id = id
                )
            ).getOrThrow()
        }
    }

    fun verifySource(sourceId: String) {
        verifySources(listOf(sourceId))
    }

    /** One busy operation checks every selected link, including after backup restoration. */
    fun verifySources(sourceIds: List<String>) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.HTTP_FAILURE, success = true)) {
            val token = huggingFaceToken().trim().takeIf { it.isNotBlank() }
            var firstFailure: Throwable? = null
            sourceIds.distinct().forEach { sourceId ->
                val failure = repository.verifySource(sourceId, token).exceptionOrNull()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (firstFailure == null) firstFailure = failure
            }
            firstFailure?.let { throw it }
            Unit
        }
    }

    internal fun saveBundleSource(draft: ModelSourceDraft, onSaved: (ModelSourceEntity) -> Unit) {
        runOperation(success = null, onSuccess = onSaved) {
            val saved = repository.saveSource(draft).getOrThrow()
            // A network/authentication failure keeps a useful draft link and its explicit status.
            repository.verifySource(saved.id, huggingFaceToken().trim().takeIf(String::isNotBlank))
                .getOrElse { repository.getSource(saved.id) ?: saved }
        }
    }

    fun browseSource(source: ModelSourceEntity) {
        browseFolder(source.id, source.filePath)
    }

    fun browseFolder(sourceId: String, folderPath: String?) {
        if (_busy.value) return
        val requestGeneration = ++folderRequestGeneration
        _browsingSourceId.value = sourceId
        runOperation(success = null, onSuccess = { result ->
            if (requestGeneration == folderRequestGeneration && _browsingSourceId.value == sourceId) {
                _folderListing.value = result
            }
        }) {
            repository.browseSourceFolder(
                sourceId = sourceId,
                folderPath = folderPath,
                bearerToken = huggingFaceToken().trim().takeIf { it.isNotBlank() },
                maxPages = 1
            ).getOrThrow()
        }
    }

    /** Fetches exactly the next HF cursor and appends it to the visible folder. */
    fun loadMoreFolder() {
        val current = _folderListing.value ?: return
        val sourceId = _browsingSourceId.value ?: return
        val cursor = current.nextCursor?.takeIf { it.isNotBlank() } ?: return
        val requestGeneration = folderRequestGeneration
        runOperation(success = null, onSuccess = { nextPage ->
            val visible = _folderListing.value
            if (requestGeneration == folderRequestGeneration && _browsingSourceId.value == sourceId &&
                visible != null &&
                visible.repositoryId == nextPage.repositoryId &&
                visible.revision == nextPage.revision &&
                visible.folderPath == nextPage.folderPath
            ) {
                _folderListing.value = visible.appendPage(nextPage)
            }
        }) {
            repository.browseSourceFolder(
                sourceId = sourceId,
                folderPath = current.folderPath,
                bearerToken = huggingFaceToken().trim().takeIf { it.isNotBlank() },
                cursor = cursor,
                maxPages = 1
            ).getOrThrow()
        }
    }

    fun deleteSource(sourceId: String) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.SOURCE_NOT_FOUND, success = true)) {
            repository.deleteSource(sourceId)
        }
    }

    fun inspectPendingArtifact(artifactId: String) {
        runOperation(success = null) {
            repository.inspectPendingArtifact(artifactId).getOrThrow()
        }
    }

    fun promotePendingArtifact(
        artifact: PendingModelArtifactEntity,
        family: ModelFamily,
        displayName: String,
        role: String?,
        metadataJson: String? = null
    ) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED, success = true)) {
            repository.promotePendingArtifact(
                context = appContext,
                id = artifact.id,
                family = family,
                displayName = displayName,
                role = role?.trim()?.takeIf { it.isNotBlank() },
                metadataJson = metadataJson
            ).getOrThrow()
        }
    }

    /** Persists a direct source and starts its durable stage-and-inspect queue. */
    fun queueCustomDownload(
        family: ModelFamily,
        url: String,
        label: String?,
        role: String?
    ) {
        runPersistentOperation(
            operationKey = customDownloadOperationKey(url),
            success = ModelLibraryMessage(ModelLibraryErrorCode.DOWNLOAD_FAILED, success = true)
        ) {
            val source = repository.saveSource(
                ModelSourceDraft(
                    family = family,
                    url = url,
                    label = label?.trim()?.takeIf { it.isNotBlank() }
                )
            ).getOrThrow()
            repository.startCustomDownload(
                context = appContext,
                sourceId = source.id,
                family = family,
                role = role,
                bearerToken = huggingFaceToken().trim().takeIf { it.isNotBlank() }
            ).getOrThrow()
        }
    }

    fun saveBundle(bundle: ModelBundleEntity, items: List<ModelBundleItemEntity>) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.BUNDLE_INVALID, success = true)) {
            repository.saveBundle(bundle, items).getOrThrow()
        }
    }

    fun deleteBundle(bundleId: String) {
        // Stop the application-owned sequential coordinator before the
        // repository detaches its durable rows; otherwise it could enqueue a
        // later item while the definition is being removed.
        ModelLibraryQueueScope.cancel("bundle:$bundleId")
        runPersistentOperation(operationKey = "delete-bundle:$bundleId",
            success = ModelLibraryMessage(ModelLibraryErrorCode.BUNDLE_INVALID, success = true),
            allowWhileBusy = true) {
            repository.deleteBundle(appContext, bundleId)
        }
    }

    fun linkSourceToModel(model: ModelEntity, sourceId: String, role: String?) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.SOURCE_NOT_FOUND, success = true)) {
            val source = repository.getSource(sourceId)
                ?: throw ModelLibraryException(ModelLibraryErrorCode.SOURCE_NOT_FOUND, "Saved source was not found")
            repository.recordProvenance(
                sourceId = source.id,
                reference = ModelArtifactReference(
                    family = familyForModel(model),
                    localPath = model.path,
                    displayName = model.filename,
                    modelKey = model.filename
                ),
                role = role?.trim()?.takeIf { it.isNotBlank() },
                sizeBytes = model.sizeBytes
            ).getOrThrow()
        }
    }

    internal fun attachSource(request: ModelSourceAttachmentRequest) {
        runPersistentOperation(
            operationKey = "attach-source:${request.asset.stableId}:${request.asset.path}",
            success = ModelLibraryMessage(ModelLibraryErrorCode.SOURCE_NOT_FOUND, success = true)
        ) {
            attachModelSource(repository, request).getOrThrow()
            val linked = repository.provenance.first().firstOrNull { edge ->
                edge.modelKey == request.asset.stableId &&
                    edge.localPath == File(request.asset.path).canonicalPath
            }
            linked?.let { repository.verifySource(it.sourceId,
                huggingFaceToken().trim().takeIf(String::isNotBlank)) }
        }
    }

    /** Keeps LiteRT provenance in the library table without mirroring it into ModelEntity. */
    fun linkSourceToLiteRtModel(model: LiteRtModelEntity, sourceId: String, role: String?) {
        runOperation(success = ModelLibraryMessage(ModelLibraryErrorCode.SOURCE_NOT_FOUND, success = true)) {
            val source = repository.getSource(sourceId)
                ?: throw ModelLibraryException(ModelLibraryErrorCode.SOURCE_NOT_FOUND, "Saved source was not found")
            repository.recordProvenance(
                sourceId = source.id,
                reference = ModelArtifactReference(
                    family = ModelFamily.LITERT,
                    localPath = model.path,
                    displayName = model.displayName.ifBlank { model.filename },
                    modelKey = InstalledModelAsset.fromLiteRt(model).stableId
                ),
                role = role?.trim()?.takeIf { it.isNotBlank() } ?: "litert",
                sizeBytes = model.sizeBytes
            ).getOrThrow()
        }
    }

    fun familyForModel(model: ModelEntity): ModelFamily = when (model.type) {
        ModelType.SD_CHECKPOINT,
        ModelType.SD_UPSCALER,
        ModelType.SD_DIFFUSION,
        ModelType.SD_CLIP_L,
        ModelType.SD_CLIP_G,
        ModelType.SD_T5XXL,
        ModelType.SD_TAE,
        ModelType.SD_VAE,
        ModelType.SD_LORA,
        ModelType.SD_TEXTUAL_INVERSION,
        ModelType.SD_CONTROLNET,
        ModelType.SD_PHOTOMAKER,
        ModelType.SD_CLIP_VISION,
        ModelType.SD_IP_ADAPTER,
        ModelType.SD_ADETAILER,
        ModelType.SD_AUDIO_VAE,
        ModelType.SD_EMBEDDINGS_CONNECTORS,
        ModelType.SD_MOTION_MODULE -> ModelFamily.SD
        ModelType.ONNX_IMAGE_GEN,
        ModelType.ONNX_BACKGROUND_REMOVAL,
        ModelType.ONNX_IMAGE_UPSCALER,
        ModelType.ONNX_TTS -> ModelFamily.ONNX
        ModelType.WHISPER -> ModelFamily.WHISPER
        else -> ModelFamily.LLM
    }

    fun roleForModel(model: ModelEntity): String? = when (model.type) {
        ModelType.LLM -> "llm"
        ModelType.VISION -> "llm_vision"
        ModelType.SD_CHECKPOINT -> "checkpoint"
        ModelType.SD_UPSCALER -> "upscaler"
        ModelType.SD_DIFFUSION -> "diffusion"
        ModelType.SD_CLIP_L -> "clip_l"
        ModelType.SD_CLIP_G -> "clip_g"
        ModelType.SD_T5XXL -> "t5xxl"
        ModelType.SD_TAE -> "tae"
        ModelType.SD_VAE -> "vae"
        ModelType.SD_LORA -> "lora"
        ModelType.SD_TEXTUAL_INVERSION -> "textual_inversion"
        ModelType.SD_CONTROLNET -> "controlnet"
        ModelType.SD_PHOTOMAKER -> "photomaker"
        ModelType.SD_CLIP_VISION -> "clip_vision"
        ModelType.SD_IP_ADAPTER -> "ip_adapter"
        ModelType.SD_ADETAILER -> "adetailer"
        ModelType.SD_AUDIO_VAE -> "audioVAE"
        ModelType.SD_EMBEDDINGS_CONNECTORS -> "connectors"
        ModelType.SD_MOTION_MODULE -> "motionmodule"
        ModelType.VISION_PROJECTOR -> "llm_vision"
        ModelType.MMPROJ -> "mmproj"
        ModelType.EMBEDDING -> "embedding"
        ModelType.LORA -> "lora"
        ModelType.ONNX_TTS -> "tts"
        ModelType.ONNX_BACKGROUND_REMOVAL -> "background_removal"
        ModelType.ONNX_IMAGE_UPSCALER -> "upscaler"
        ModelType.ONNX_IMAGE_GEN -> "image_generation"
        ModelType.WHISPER -> "whisper"
        else -> null
    }

    fun startBundle(bundle: ModelBundleEntity) {
        runPersistentOperation(
            operationKey = "bundle:${bundle.id}",
            success = ModelLibraryMessage(ModelLibraryErrorCode.DOWNLOAD_FAILED, success = true)
        ) {
            val family = com.example.llamadroid.data.model.library.ModelFamily.fromStoredValue(bundle.family)
                ?: throw ModelLibraryException(ModelLibraryErrorCode.BUNDLE_INVALID, "Bundle family is invalid")
            val root = bundleRoot(family, bundle.id)
            repository.startBundleDownloadQueue(
                context = appContext,
                bundleId = bundle.id,
                localRoot = root,
                bearerToken = huggingFaceToken().trim().takeIf { it.isNotBlank() }
            ).getOrThrow()
        }
    }

    fun cancelBundle(bundleId: String) {
        ModelLibraryQueueScope.cancel("bundle:$bundleId")
        runPersistentOperation(operationKey = "cancel-bundle:$bundleId",
            success = ModelLibraryMessage(ModelLibraryErrorCode.DOWNLOAD_FAILED, success = true),
            allowWhileBusy = true) {
            repository.cancelBundleDownload(appContext, bundleId).getOrThrow()
        }
    }

    fun cancelArtifact(artifactId: String) {
        runPersistentOperation(operationKey = "cancel-artifact:$artifactId", success = null,
            allowWhileBusy = true) {
            repository.cancelPendingArtifact(appContext, artifactId).getOrThrow()
        }
    }

    /** Removes a completed Unknown payload after the card's explicit confirmation. */
    fun discardArtifact(artifactId: String) {
        runPersistentOperation(operationKey = "discard-artifact:$artifactId", success = null,
            allowWhileBusy = true) {
            repository.discardPendingArtifact(appContext, artifactId).getOrThrow()
        }
    }

    fun retryArtifact(artifactId: String) {
        runPersistentOperation(operationKey = "retry-artifact:$artifactId", success = null) {
            repository.retryPendingArtifact(appContext, artifactId,
                huggingFaceToken().trim().takeIf { it.isNotBlank() }).getOrThrow()
        }
    }

    private fun bundleRoot(family: ModelFamily, bundleId: String): File =
        File(appContext.filesDir, "model-library/${family.storageValue}/bundles/$bundleId").apply { mkdirs() }

    private fun <T> runOperation(
        success: ModelLibraryMessage?,
        onSuccess: (T) -> Unit = {},
        operation: suspend () -> T
    ) {
        if (!beginOperation()) return
        viewModelScope.launch {
            _message.value = null
            try {
                onSuccess(operation())
                if (success != null) _message.value = success
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = ModelLibraryMessage(errorCode(error))
            } finally { endOperation() }
        }
    }

    /**
     * Bundle coordination belongs to the application lifecycle. The durable
     * pending rows are persisted before the first task, so a process death is
     * recovered by LlamaApplication; leaving this screen must not cancel the
     * remaining sequential queue.
     */
    private fun <T> runPersistentOperation(
        operationKey: String,
        success: ModelLibraryMessage?,
        onSuccess: (T) -> Unit = {},
        allowWhileBusy: Boolean = false,
        operation: suspend () -> T
    ) {
        if (!beginOperation(allowWhileBusy)) return
        val job = ModelLibraryQueueScope.launch(operationKey) {
            _message.value = null
            try {
                onSuccess(operation())
                if (success != null) _message.value = success
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = ModelLibraryMessage(errorCode(error))
            } finally { endOperation() }
        }
        if (job == null) endOperation()
    }

    private fun errorCode(error: Throwable): ModelLibraryErrorCode = when (error) {
        is ModelLibraryException -> error.code
        is HuggingFaceHttpException -> error.errorCode
        is java.net.SocketTimeoutException -> ModelLibraryErrorCode.REQUEST_TIMEOUT
        is java.io.IOException -> ModelLibraryErrorCode.NETWORK_FAILURE
        else -> ModelLibraryErrorCode.INVALID_URL
    }
}

/** Canonical in-process key used to collapse duplicate custom-download taps. */
internal fun customDownloadOperationKey(url: String): String {
    val normalized = ModelSourceUrlValidator.validate(
        ModelSourceDraft(family = ModelFamily.LLM, url = url)
    ).source?.normalizedKey ?: url.trim()
    return "custom:$normalized"
}

class ModelLibraryViewModelFactory(
    private val repository: ModelSourceRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ModelLibraryViewModel::class.java)) {
            throw IllegalArgumentException("Unknown model library view model")
        }
        val prefs = context.applicationContext.getSharedPreferences(
            "litert_model_repository",
            Context.MODE_PRIVATE
        )
        return ModelLibraryViewModel(
            repository = repository,
            appContext = context.applicationContext,
            huggingFaceToken = { prefs.getString("hugging_face_token", "").orEmpty() }
        ) as T
    }
}
