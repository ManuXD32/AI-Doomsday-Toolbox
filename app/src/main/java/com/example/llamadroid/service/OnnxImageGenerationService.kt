package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.onnx.OnnxGeneratedImageMetadata
import com.example.llamadroid.onnx.OnnxImg2ImgPipeline
import com.example.llamadroid.onnx.OnnxImg2ImgPreprocessInfo
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.OnnxTxt2ImgPipeline
import com.example.llamadroid.onnx.computeOnnxImg2ImgEffectiveSteps
import com.example.llamadroid.onnx.estimateOnnxRamProfile
import com.example.llamadroid.onnx.toDisplayLines
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.util.getParcelableExtraCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class OnnxImageGenerationState {
    object Idle : OnnxImageGenerationState()
    data class Preparing(
        val status: String,
        val elapsedMs: Long = 0L,
        val etaMs: Long? = null
    ) : OnnxImageGenerationState()
    data class Generating(
        val progress: Float,
        val status: String,
        val elapsedMs: Long,
        val etaMs: Long? = null
    ) : OnnxImageGenerationState()
    data class Complete(
        val outputPath: String,
        val warningMessage: String? = null,
        val durationMs: Long? = null
    ) : OnnxImageGenerationState()
    data class Error(val message: String) : OnnxImageGenerationState()
}

private data class CompletedOnnxGeneration(
    val outputFile: File,
    val seedUsed: Long,
    val warningMessage: String?,
    val runtimeSummary: com.example.llamadroid.onnx.OnnxRuntimeExecutionSummary,
    val preprocessInfo: OnnxImg2ImgPreprocessInfo? = null
)

private data class OnnxSharedExportResult(
    val imageRelativePath: String? = null,
    val metadataRelativePath: String? = null,
    val warningMessage: String? = null
)

class OnnxImageGenerationStateHolder {
    private val _state = MutableStateFlow<OnnxImageGenerationState>(OnnxImageGenerationState.Idle)
    val state: StateFlow<OnnxImageGenerationState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _currentPrompt = MutableStateFlow("")
    val currentPrompt: StateFlow<String> = _currentPrompt

    private val _generatedImages = MutableStateFlow<List<File>>(emptyList())
    val generatedImages: StateFlow<List<File>> = _generatedImages

    fun updatePrompt(prompt: String) {
        _currentPrompt.value = prompt
    }

    fun updateState(newState: OnnxImageGenerationState) {
        _state.value = newState
        when (newState) {
            is OnnxImageGenerationState.Preparing -> {
                _progress.value = 0f
                _status.value = newState.status
            }
            is OnnxImageGenerationState.Generating -> {
                _progress.value = newState.progress
                _status.value = newState.status
            }
            is OnnxImageGenerationState.Complete -> {
                _progress.value = 1f
                _status.value = ""
                val file = File(newState.outputPath)
                if (file.exists()) {
                    _generatedImages.value = (_generatedImages.value + file)
                        .distinctBy { it.absolutePath }
                        .sortedByDescending { it.lastModified() }
                }
            }
            is OnnxImageGenerationState.Error -> {
                _progress.value = 0f
                _status.value = newState.message
            }
            is OnnxImageGenerationState.Idle -> {
                _progress.value = 0f
                _status.value = ""
            }
        }
    }

    fun setImages(images: List<File>) {
        _generatedImages.value = images
    }

    fun removeImage(file: File) {
        _generatedImages.value = _generatedImages.value.filter { it.absolutePath != file.absolutePath }
    }

    fun reset() {
        _state.value = OnnxImageGenerationState.Idle
        _progress.value = 0f
        _status.value = ""
    }
}

object OnnxImageGenerationStateStore {
    val imageGen = OnnxImageGenerationStateHolder()
    val txt2img: OnnxImageGenerationStateHolder
        get() = imageGen
}

class OnnxImageGenerationService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): OnnxImageGenerationService = this@OnnxImageGenerationService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var generationJob: Job? = null
    private var notificationTaskId: Int? = null
    private var lastProgressStatus: String? = null
    private var generationStartedAtMs: Long = 0L
    private var firstDenoiseStepAtMs: Long? = null
    private var etaTickerJob: Job? = null
    private var lastUiProgress: Float = 0f
    private var lastUiStatus: String = ""
    private var etaTargetAtMs: Long? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GENERATION -> {
                val config = intent.getParcelableExtraCompat<OnnxImageGenConfig>(EXTRA_CONFIG)
                if (config == null) {
                    OnnxImageGenerationStateStore.imageGen.updateState(
                        OnnxImageGenerationState.Error(getString(R.string.onnx_image_gen_error_missing_config))
                    )
                } else if (generationJob?.isActive == true) {
                    DebugLog.log("[OnnxImageGenerationService] Ignoring duplicate start while generation is active")
                    OnnxImageGenerationStateStore.imageGen.updateState(
                        OnnxImageGenerationState.Error(getString(R.string.onnx_image_gen_error_already_running))
                    )
                } else {
                    ensureForeground(config.modelName)
                    startGeneration(config)
                }
            }
            ACTION_CANCEL_GENERATION -> cancelGeneration()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }

    private fun ensureForeground(title: String) {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.IMAGE_GEN,
            title
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
    }

    private fun startGeneration(config: OnnxImageGenConfig) {
        val holder = OnnxImageGenerationStateStore.imageGen
        holder.updatePrompt(config.prompt)
        holder.updateState(OnnxImageGenerationState.Preparing(getString(R.string.onnx_image_gen_status_preparing)))
        lastProgressStatus = null
        generationStartedAtMs = System.currentTimeMillis()
        firstDenoiseStepAtMs = null
        lastUiProgress = 0f
        lastUiStatus = getString(R.string.onnx_image_gen_status_preparing)
        etaTargetAtMs = null
        etaTickerJob?.cancel()
        DebugLog.log(
            "[OnnxImageGenerationService] Starting ${config.mode.storageToken} model=${config.modelName} " +
                "requested=${config.requestedWidth}x${config.requestedHeight} normalized=${config.width}x${config.height} " +
                "steps=${config.steps} backend=${config.backend.name} runtime=${config.runtimeOptions.toDisplayLines().joinToString(";")}"
        )
        generationJob = serviceScope.launch {
            etaTickerJob = launch {
                while (isActive) {
                    delay(1000)
                    val targetAtMs = etaTargetAtMs ?: continue
                    val nowMs = System.currentTimeMillis()
                    val elapsedMs = (nowMs - generationStartedAtMs).coerceAtLeast(0L)
                    val remainingMs = (targetAtMs - nowMs).coerceAtLeast(0L)
                    val activeState = holder.state.value
                    val statusText = lastUiStatus.ifBlank {
                        getString(R.string.onnx_image_gen_status_preparing)
                    }
                    when (activeState) {
                        is OnnxImageGenerationState.Preparing -> {
                            holder.updateState(
                                activeState.copy(
                                    elapsedMs = elapsedMs,
                                    etaMs = remainingMs
                                )
                            )
                        }
                        is OnnxImageGenerationState.Generating -> {
                            holder.updateState(
                                activeState.copy(
                                    elapsedMs = elapsedMs,
                                    etaMs = remainingMs
                                )
                            )
                            notificationTaskId?.let { taskId ->
                                UnifiedNotificationManager.updateProgress(
                                    taskId,
                                    lastUiProgress,
                                    buildNotificationProgressText(statusText, remainingMs)
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
            try {
                val result = when (config.mode) {
                    OnnxImageGenMode.TXT2IMG -> {
                        val pipeline = OnnxTxt2ImgPipeline()
                        pipeline.generate(
                            config = config,
                            onProgress = { progress, status ->
                                handleProgress(
                                    holder = holder,
                                    config = config,
                                    progress = progress,
                                    status = status
                                )
                            },
                            onDiagnostic = { message ->
                                recordRuntimeDiagnostic(config, message)
                            }
                        ).let {
                            CompletedOnnxGeneration(
                                outputFile = it.outputFile,
                                seedUsed = it.seedUsed,
                                warningMessage = it.warningMessage,
                                runtimeSummary = it.runtimeSummary
                            )
                        }
                    }
                    OnnxImageGenMode.IMG2IMG -> {
                        val pipeline = OnnxImg2ImgPipeline()
                        pipeline.generate(
                            config = config,
                            onProgress = { progress, status ->
                                handleProgress(
                                    holder = holder,
                                    config = config,
                                    progress = progress,
                                    status = status
                                )
                            },
                            onDiagnostic = { message ->
                                recordRuntimeDiagnostic(config, message)
                            }
                        ).let {
                            CompletedOnnxGeneration(
                                outputFile = it.outputFile,
                                seedUsed = it.seedUsed,
                                warningMessage = it.warningMessage,
                                runtimeSummary = it.runtimeSummary,
                                preprocessInfo = it.preprocessInfo
                            )
                        }
                    }
                }
                val durationMs = (System.currentTimeMillis() - generationStartedAtMs).coerceAtLeast(0L)
                etaTargetAtMs = null
                val localMetadataFile = OnnxStorage.metadataFileFor(result.outputFile)
                var metadata = OnnxGeneratedImageMetadata(
                    imagePath = result.outputFile.absolutePath,
                    modelName = config.modelName,
                    mode = config.mode.name,
                    prompt = config.prompt,
                    negativePrompt = config.negativePrompt,
                    requestedWidth = config.requestedWidth,
                    requestedHeight = config.requestedHeight,
                    width = config.width,
                    height = config.height,
                    steps = config.steps,
                    cfgScale = config.cfgScale,
                    seed = result.seedUsed,
                    initImagePath = config.initImagePath,
                    initImageOriginalWidth = result.preprocessInfo?.originalWidth,
                    initImageOriginalHeight = result.preprocessInfo?.originalHeight,
                    initImageCanvasWidth = result.preprocessInfo?.canvasWidth,
                    initImageCanvasHeight = result.preprocessInfo?.canvasHeight,
                    initImageFittedWidth = result.preprocessInfo?.fittedWidth,
                    initImageFittedHeight = result.preprocessInfo?.fittedHeight,
                    initImagePaddingLeft = result.preprocessInfo?.paddingLeft,
                    initImagePaddingTop = result.preprocessInfo?.paddingTop,
                    strength = config.strength,
                    effectiveSteps = effectiveStepCount(config),
                    backend = config.backend.name,
                    resolvedBackendSummary = result.runtimeSummary,
                    runtimeOptions = config.runtimeOptions,
                    createdAtEpochMs = System.currentTimeMillis(),
                    warningMessage = result.warningMessage,
                    totalTimeMs = durationMs
                )
                OnnxStorage.writeMetadata(
                    imageFile = result.outputFile,
                    metadata = metadata
                )
                val exportResult = exportImageIfConfigured(
                    outputFile = result.outputFile,
                    metadataFile = localMetadataFile,
                    mode = config.mode
                )
                val combinedWarning = mergeWarningMessages(
                    result.warningMessage,
                    exportResult.warningMessage
                )
                metadata = metadata.copy(
                    sharedOutputRelativePath = exportResult.imageRelativePath,
                    sharedMetadataRelativePath = exportResult.metadataRelativePath,
                    warningMessage = combinedWarning
                )
                OnnxStorage.writeMetadata(
                    imageFile = result.outputFile,
                    metadata = metadata
                )
                if (exportResult.imageRelativePath != null || exportResult.metadataRelativePath != null) {
                    mirrorMetadataIfConfigured(
                        metadataFile = localMetadataFile,
                        mode = config.mode
                    )
                }
                holder.updateState(
                    OnnxImageGenerationState.Complete(
                        outputPath = result.outputFile.absolutePath,
                        warningMessage = combinedWarning,
                        durationMs = durationMs
                    )
                )
                DebugLog.log("[OnnxImageGenerationService] Completed ${result.outputFile.name}")
                notificationTaskId?.let { taskId ->
                    UnifiedNotificationManager.completeTask(
                        taskId,
                        getString(R.string.onnx_image_gen_notification_complete)
                    )
                }
            } catch (cancelled: CancellationException) {
                DebugLog.log("[OnnxImageGenerationService] Cancelled")
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
                holder.reset()
            } catch (e: Exception) {
                val message = e.message ?: getString(R.string.error_generic)
                DebugLog.log("[OnnxImageGenerationService] Failed: $message")
                holder.updateState(OnnxImageGenerationState.Error(message))
                notificationTaskId?.let { taskId -> UnifiedNotificationManager.failTask(taskId, message) }
            } finally {
                etaTickerJob?.cancel()
                etaTickerJob = null
                generationJob = null
                lastProgressStatus = null
                generationStartedAtMs = 0L
                firstDenoiseStepAtMs = null
                lastUiProgress = 0f
                lastUiStatus = ""
                etaTargetAtMs = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleProgress(
        holder: OnnxImageGenerationStateHolder,
        config: OnnxImageGenConfig,
        progress: Float,
        status: String
    ) {
        val elapsedMs = (System.currentTimeMillis() - generationStartedAtMs).coerceAtLeast(0L)
        val etaMs = estimateRemainingMs(
            rawStatus = status,
            progress = progress,
            elapsedMs = elapsedMs,
            totalSteps = effectiveStepCount(config)
        )
        val localizedStatus = localizeProgressStatus(status)
        val notificationText = buildNotificationProgressText(localizedStatus, etaMs)
        lastUiProgress = progress
        lastUiStatus = localizedStatus
        etaTargetAtMs = etaMs?.takeIf { it >= 0L }?.let { System.currentTimeMillis() + it }
        if (status != lastProgressStatus) {
            lastProgressStatus = status
            DebugLog.log("[OnnxImageGenerationService] $notificationText (${(progress * 100f).toInt()}%)")
        }
        holder.updateState(
            OnnxImageGenerationState.Generating(
                progress = progress,
                status = localizedStatus,
                elapsedMs = elapsedMs,
                etaMs = etaMs
            )
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, progress, notificationText)
        }
    }

    private fun cancelGeneration() {
        val activeJob = generationJob
        if (activeJob?.isActive == true) {
            val elapsedMs = (System.currentTimeMillis() - generationStartedAtMs).coerceAtLeast(0L)
            val remainingMs = etaTargetAtMs?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) }
            lastUiStatus = getString(R.string.onnx_image_gen_status_canceling)
            OnnxImageGenerationStateStore.imageGen.updateState(
                OnnxImageGenerationState.Preparing(
                    status = lastUiStatus,
                    elapsedMs = elapsedMs,
                    etaMs = remainingMs
                )
            )
            notificationTaskId?.let { taskId ->
                UnifiedNotificationManager.updateProgress(
                    taskId,
                    lastUiProgress,
                    lastUiStatus
                )
            }
            activeJob.cancel(CancellationException(getString(R.string.action_cancelled)))
            return
        }

        generationStartedAtMs = 0L
        firstDenoiseStepAtMs = null
        lastUiProgress = 0f
        lastUiStatus = ""
        etaTargetAtMs = null
        OnnxImageGenerationStateStore.imageGen.reset()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun exportImageIfConfigured(
        outputFile: File,
        metadataFile: File,
        mode: OnnxImageGenMode
    ): OnnxSharedExportResult {
        if (!outputFile.exists() || !metadataFile.exists()) return OnnxSharedExportResult()
        val outputFolderUri = SettingsRepository(this).outputFolderUri.value ?: return OnnxSharedExportResult()
        return try {
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(outputFolderUri))
                ?: return OnnxSharedExportResult(
                    warningMessage = getString(R.string.onnx_image_gen_export_warning_unavailable)
                )
            val onnxModeDir = ensureOnnxExportDir(rootDoc, mode)
                ?: return OnnxSharedExportResult(
                    warningMessage = getString(R.string.onnx_image_gen_export_warning_unavailable)
                )
            copyFileIntoDocument(outputFile, onnxModeDir, "image/png")
            copyFileIntoDocument(metadataFile, onnxModeDir, "application/json")
            val imageRelativePath = OnnxStorage.sharedExportRelativePath(mode, outputFile.name)
            val metadataRelativePath = OnnxStorage.sharedExportRelativePath(mode, metadataFile.name)
            DebugLog.log("[OnnxImageGenerationService] Mirrored ONNX output to $imageRelativePath")
            OnnxSharedExportResult(
                imageRelativePath = imageRelativePath,
                metadataRelativePath = metadataRelativePath
            )
        } catch (error: Exception) {
            DebugLog.log("[OnnxImageGenerationService] Failed to mirror ONNX output: ${error.message}")
            OnnxSharedExportResult(
                warningMessage = getString(
                    R.string.onnx_image_gen_export_warning_failed,
                    error.message ?: getString(R.string.error_generic)
                )
            )
        }
    }

    private fun mirrorMetadataIfConfigured(
        metadataFile: File,
        mode: OnnxImageGenMode
    ) {
        val outputFolderUri = SettingsRepository(this).outputFolderUri.value ?: return
        runCatching {
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(outputFolderUri)) ?: return
            val onnxModeDir = ensureOnnxExportDir(rootDoc, mode) ?: return
            copyFileIntoDocument(metadataFile, onnxModeDir, "application/json")
        }.onFailure { error ->
            DebugLog.log("[OnnxImageGenerationService] Failed to refresh mirrored metadata: ${error.message}")
        }
    }

    private fun ensureOnnxExportDir(
        rootDoc: DocumentFile,
        mode: OnnxImageGenMode
    ): DocumentFile? {
        val imagesDir = rootDoc.findFile("images") ?: rootDoc.createDirectory("images")
        val onnxDir = imagesDir?.findFile("onnx") ?: imagesDir?.createDirectory("onnx")
        return onnxDir?.findFile(mode.storageToken) ?: onnxDir?.createDirectory(mode.storageToken)
    }

    private fun copyFileIntoDocument(
        sourceFile: File,
        targetDir: DocumentFile,
        mimeType: String
    ) {
        val existing = targetDir.findFile(sourceFile.name)
        val targetFile = existing ?: targetDir.createFile(mimeType, sourceFile.name)
        requireNotNull(targetFile) { "Could not create ${sourceFile.name} in shared output folder" }
        contentResolver.openOutputStream(targetFile.uri, "wt")?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Could not open output stream for ${sourceFile.name}")
    }

    private fun mergeWarningMessages(vararg warnings: String?): String? {
        return warnings
            .filterNot { it.isNullOrBlank() }
            .joinToString("\n")
            .ifBlank { null }
    }

    companion object {
        private const val ACTION_START_GENERATION = "onnx_start_generation"
        private const val ACTION_CANCEL_GENERATION = "onnx_cancel_generation"
        private const val EXTRA_CONFIG = "config"

        fun createStartIntent(context: Context, config: OnnxImageGenConfig): Intent =
            Intent(context, OnnxImageGenerationService::class.java).apply {
                action = ACTION_START_GENERATION
                putExtra(EXTRA_CONFIG, config)
            }

        fun createCancelIntent(context: Context): Intent =
            Intent(context, OnnxImageGenerationService::class.java).apply {
                action = ACTION_CANCEL_GENERATION
            }
    }

    private fun buildSessionDetails(config: OnnxImageGenConfig): String {
        return buildList {
            add("mode=${config.mode.storageToken}")
            add("model=${config.modelName}")
            add("requested=${config.requestedWidth}x${config.requestedHeight}")
            add("size=${config.width}x${config.height}")
            add("steps=${config.steps}")
            if (config.mode == OnnxImageGenMode.IMG2IMG) {
                add("effective_steps=${effectiveStepCount(config)}")
            }
            add("cfg=${config.cfgScale}")
            config.initImagePath?.let { add("init=${File(it).name}") }
            config.strength?.let { add("strength=$it") }
            add("backend=${config.backend.name}")
            add("ram_profile=${estimateOnnxRamProfile(config.width, config.height).name}")
            addAll(config.runtimeOptions.toDisplayLines())
        }.joinToString(" ")
    }

    private fun effectiveStepCount(config: OnnxImageGenConfig): Int {
        return if (config.mode == OnnxImageGenMode.IMG2IMG) {
            computeOnnxImg2ImgEffectiveSteps(
                totalSteps = config.steps,
                strength = config.strength ?: 0.35f
            )
        } else {
            config.steps
        }
    }

    private fun buildNotificationProgressText(status: String, etaMs: Long?): String {
        return if (etaMs != null && etaMs > 0L) {
            getString(
                R.string.onnx_image_gen_progress_with_eta,
                status,
                FormatUtils.Display.formatDuration(etaMs / 1000.0)
            )
        } else {
            status
        }
    }

    private fun estimateRemainingMs(
        rawStatus: String,
        progress: Float,
        elapsedMs: Long,
        totalSteps: Int
    ): Long? {
        val unetPrefix = "UNet step "
        val currentStep = if (rawStatus.startsWith(unetPrefix)) {
            rawStatus.removePrefix(unetPrefix)
                .substringBefore('/')
                .toIntOrNull()
        } else {
            null
        }

        if (currentStep != null && currentStep > 0) {
            val denoiseStart = firstDenoiseStepAtMs ?: System.currentTimeMillis().also { firstDenoiseStepAtMs = it }
            val denoiseElapsedMs = (System.currentTimeMillis() - denoiseStart).coerceAtLeast(1L)
            val averageStepMs = denoiseElapsedMs / currentStep.toLong()
            val remainingSteps = (totalSteps - currentStep).coerceAtLeast(0)
            val postProcessBufferMs = if (remainingSteps == 0) 3_000L else 5_000L
            return (averageStepMs * remainingSteps) + postProcessBufferMs
        }

        val normalizedProgress = progress.coerceIn(0.01f, 0.99f)
        return if (elapsedMs > 0L) {
            ((elapsedMs / normalizedProgress) * (1f - normalizedProgress)).toLong().coerceAtLeast(0L)
        } else {
            null
        }
    }

    private fun localizeProgressStatus(status: String): String {
        val unetPrefix = "UNet step "
        return when {
            status.equals("Loading tokenizer", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_loading_tokenizer)
            status.equals("Loading text encoder", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_loading_text_encoder)
            status.equals("Loading init image", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_loading_init_image)
            status.equals("Loading VAE encoder", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_loading_vae_encoder)
            status.equals("Loading UNet and VAE", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_loading_unet_vae)
            status.equals("Encoding prompts", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_encoding_prompts)
            status.equals("Encoding init image", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_encoding_init_image)
            status.equals("Preparing latents", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_preparing_latents)
            status.startsWith(unetPrefix) -> {
                val progress = status.removePrefix(unetPrefix)
                getString(R.string.onnx_image_gen_phase_unet_step, progress)
            }
            status.equals("Decoding VAE", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_decoding_vae)
            status.equals("Saving PNG", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_saving_png)
            status.equals("Complete", ignoreCase = true) ->
                getString(R.string.onnx_image_gen_phase_complete)
            else -> status
        }
    }

    private fun recordRuntimeDiagnostic(config: OnnxImageGenConfig, message: String) {
        DebugLog.log("[OnnxImageGenerationService] ${config.mode.storageToken} $message")
    }
}
