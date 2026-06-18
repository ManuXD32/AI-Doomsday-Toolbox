package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxBackgroundRemovalConfig
import com.example.llamadroid.onnx.OnnxBackgroundRemovalMetadata
import com.example.llamadroid.onnx.OnnxBackgroundRemovalPipeline
import com.example.llamadroid.onnx.OnnxBackgroundRemovalRuntimeState
import com.example.llamadroid.onnx.OnnxBackgroundRemovalStage
import com.example.llamadroid.onnx.OnnxBackgroundRemovalStorage
import com.example.llamadroid.onnx.toDisplayLines
import com.example.llamadroid.util.DebugLog
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

sealed class OnnxBackgroundRemovalState {
    object Idle : OnnxBackgroundRemovalState()
    data class Running(
        val progress: Float,
        val status: String,
        val completed: Int,
        val total: Int
    ) : OnnxBackgroundRemovalState()
    data class Complete(
        val outputPaths: List<String>,
        val failed: Int,
        val durationMs: Long
    ) : OnnxBackgroundRemovalState()
    data class Error(val message: String) : OnnxBackgroundRemovalState()
}

object OnnxBackgroundRemovalStateStore {
    private val _state = MutableStateFlow<OnnxBackgroundRemovalState>(OnnxBackgroundRemovalState.Idle)
    val state: StateFlow<OnnxBackgroundRemovalState> = _state

    private val _outputs = MutableStateFlow<List<File>>(emptyList())
    val outputs: StateFlow<List<File>> = _outputs

    fun updateState(state: OnnxBackgroundRemovalState) {
        _state.value = state
        if (state is OnnxBackgroundRemovalState.Complete) {
            val files = state.outputPaths.map(::File).filter { it.isFile }
            _outputs.value = (_outputs.value + files).distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        }
    }

    fun setOutputs(files: List<File>) {
        _outputs.value = files.sortedByDescending { it.lastModified() }
    }

    fun removeOutput(file: File) {
        _outputs.value = _outputs.value.filter { it.absolutePath != file.absolutePath }
    }

    fun reset() {
        _state.value = OnnxBackgroundRemovalState.Idle
    }
}

class OnnxBackgroundRemovalService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var notificationTaskId: Int? = null
    private var currentRuntimeState: OnnxBackgroundRemovalRuntimeState? = null
    private var runStartedAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getParcelableExtraCompat<OnnxBackgroundRemovalConfig>(EXTRA_CONFIG)
                if (config == null) {
                    OnnxBackgroundRemovalStateStore.updateState(
                        OnnxBackgroundRemovalState.Error(getString(R.string.bgr_error_missing_config))
                    )
                } else if (activeJob?.isCompleted == false) {
                    OnnxBackgroundRemovalStateStore.updateState(
                        OnnxBackgroundRemovalState.Error(getString(R.string.bgr_error_already_running))
                    )
                } else {
                    ensureForeground(config.modelName)
                    startRemoval(config)
                }
            }
            ACTION_CANCEL -> cancelRemoval()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }

    private fun ensureForeground(modelName: String) {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.BACKGROUND_REMOVAL,
            modelName
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
    }

    private fun startRemoval(config: OnnxBackgroundRemovalConfig) {
        val total = config.inputPaths.size.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        runStartedAt = startedAt
        DebugLog.log("[ONNX-BGR] Start ${buildSessionDetails(config)}")
        publishState(
            OnnxBackgroundRemovalState.Running(0f, getString(R.string.bgr_status_starting), 0, total),
            runtimeState = "RUNNING"
        )
        startHeartbeat()
        activeJob = serviceScope.launch {
            val pipeline = OnnxBackgroundRemovalPipeline()
            val outputs = mutableListOf<String>()
            var failed = 0
            try {
                config.inputPaths.forEachIndexed { index, path ->
                    if (!isActive) throw CancellationException(getString(R.string.action_cancelled))
                    val sourceName = config.inputNames.getOrNull(index).orEmpty().ifBlank { File(path).name }
                    val label = getString(R.string.bgr_status_processing_item, index + 1, total, sourceName)
                    val progressBase = index.toFloat() / total.toFloat()
                    updateProgress(progressBase, label, index, total)
                    DebugLog.log("[ONNX-BGR] Item ${index + 1}/$total started source=$sourceName model=${config.modelName}")
                    runCatching {
                        pipeline.removeBackground(
                            context = this@OnnxBackgroundRemovalService,
                            config = config,
                            inputFile = File(path),
                            sourceName = sourceName,
                            onDiagnostic = {
                                DebugLog.log("[ONNX-BGR] $it")
                            },
                            onProgress = { stage, itemProgress ->
                                val stageText = localizeStage(stage)
                                val status = "$label - $stageText"
                                updateProgress(
                                    progress = progressBase + (itemProgress.coerceIn(0f, 1f) / total.toFloat()),
                                    status = status,
                                    completed = index,
                                    total = total
                                )
                                DebugLog.log("[ONNX-BGR] $sourceName ${stage.name.lowercase()} ${(itemProgress * 100f).toInt()}%")
                            }
                        )
                    }.onSuccess { result ->
                        val export = mirrorToOutputFolder(result.outputFile, result.maskFile)
                        val metadata = result.metadata.copy(
                            sharedOutputRelativePath = export.imageRelativePath,
                            sharedMetadataRelativePath = export.metadataRelativePath,
                            sharedMaskRelativePath = export.maskRelativePath,
                            warningMessage = export.warningMessage
                        )
                        OnnxBackgroundRemovalStorage.writeMetadata(result.outputFile, metadata)
                        export.refreshMetadata?.invoke(metadata)
                        outputs += result.outputFile.absolutePath
                        DebugLog.log(
                            "[ONNX-BGR] Item ${index + 1}/$total completed " +
                                "output=${result.outputFile.name} export=${export.imageRelativePath.orEmpty()}"
                        )
                    }.onFailure { error ->
                        failed++
                        DebugLog.log("[ONNX-BGR] Failed ${File(path).name}: ${error.message}\n${error.stackTraceToString()}")
                    }
                    updateProgress((index + 1).toFloat() / total.toFloat(), label, index + 1, total)
                }
                val durationMs = System.currentTimeMillis() - startedAt
                publishState(
                    OnnxBackgroundRemovalState.Complete(outputs, failed, durationMs),
                    runtimeState = "COMPLETE",
                    outputPaths = outputs,
                    failed = failed,
                    durationMs = durationMs
                )
                DebugLog.log("[ONNX-BGR] Complete outputs=${outputs.size} failed=$failed durationMs=$durationMs")
                notificationTaskId?.let { taskId ->
                    UnifiedNotificationManager.completeTask(
                        taskId,
                        getString(R.string.bgr_notification_complete, outputs.size, failed)
                    )
                }
            } catch (cancelled: CancellationException) {
                val message = cancelled.message ?: getString(R.string.action_cancelled)
                publishState(
                    OnnxBackgroundRemovalState.Error(message),
                    runtimeState = "ERROR",
                    message = message
                )
                DebugLog.log("[ONNX-BGR] Cancelled: $message")
                notificationTaskId?.let { UnifiedNotificationManager.failTask(it, getString(R.string.action_cancelled)) }
            } catch (error: Exception) {
                val message = error.message ?: getString(R.string.error_generic)
                publishState(
                    OnnxBackgroundRemovalState.Error(message),
                    runtimeState = "ERROR",
                    message = message
                )
                DebugLog.log("[ONNX-BGR] Failed: $message\n${error.stackTraceToString()}")
                notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateProgress(progress: Float, status: String, completed: Int, total: Int) {
        publishState(
            OnnxBackgroundRemovalState.Running(progress.coerceIn(0f, 1f), status, completed, total),
            runtimeState = "RUNNING"
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, progress.coerceIn(0f, 1f), status)
        }
    }

    private fun publishState(
        state: OnnxBackgroundRemovalState,
        runtimeState: String,
        outputPaths: List<String> = emptyList(),
        failed: Int = 0,
        durationMs: Long = 0L,
        message: String? = null
    ) {
        OnnxBackgroundRemovalStateStore.updateState(state)
        val snapshot = when (state) {
            is OnnxBackgroundRemovalState.Running -> OnnxBackgroundRemovalRuntimeState(
                state = runtimeState,
                progress = state.progress,
                status = state.status,
                completed = state.completed,
                total = state.total,
                startedAtEpochMs = runStartedAt
            )
            is OnnxBackgroundRemovalState.Complete -> OnnxBackgroundRemovalRuntimeState(
                state = runtimeState,
                progress = 1f,
                status = getString(R.string.bgr_complete, outputPaths.size, failed),
                completed = outputPaths.size + failed,
                total = outputPaths.size + failed,
                outputPaths = outputPaths,
                failed = failed,
                durationMs = durationMs,
                startedAtEpochMs = runStartedAt
            )
            is OnnxBackgroundRemovalState.Error -> OnnxBackgroundRemovalRuntimeState(
                state = runtimeState,
                progress = currentRuntimeState?.progress ?: 0f,
                status = message ?: state.message,
                completed = currentRuntimeState?.completed ?: 0,
                total = currentRuntimeState?.total ?: 0,
                failed = failed,
                durationMs = durationMs,
                message = message ?: state.message,
                startedAtEpochMs = runStartedAt
            )
            OnnxBackgroundRemovalState.Idle -> OnnxBackgroundRemovalRuntimeState(
                state = "IDLE",
                startedAtEpochMs = runStartedAt
            )
        }
        currentRuntimeState = snapshot
        OnnxBackgroundRemovalStorage.writeRuntimeState(this, snapshot)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                currentRuntimeState?.takeIf { it.state == "RUNNING" }?.let { snapshot ->
                    OnnxBackgroundRemovalStorage.writeRuntimeState(this@OnnxBackgroundRemovalService, snapshot)
                }
            }
        }
    }

    private fun buildSessionDetails(config: OnnxBackgroundRemovalConfig): String {
        return "model=${config.modelName} inputs=${config.inputPaths.size} backend=${config.backend.name} " +
            "resize=${config.resizeBeforeProcessing} resizeMaxEdge=${config.resizeMaxEdge} " +
            "runtime=${config.runtimeOptions.toDisplayLines().joinToString(";")}"
    }

    private fun localizeStage(stage: OnnxBackgroundRemovalStage): String = when (stage) {
        OnnxBackgroundRemovalStage.DECODING_IMAGE -> getString(R.string.bgr_phase_decoding_image)
        OnnxBackgroundRemovalStage.LOADING_MODEL -> getString(R.string.bgr_phase_loading_model)
        OnnxBackgroundRemovalStage.PREPARING_TENSOR -> getString(R.string.bgr_phase_preparing_tensor)
        OnnxBackgroundRemovalStage.RUNNING_MODEL -> getString(R.string.bgr_phase_running_model)
        OnnxBackgroundRemovalStage.READING_MASK -> getString(R.string.bgr_phase_reading_mask)
        OnnxBackgroundRemovalStage.POSTPROCESSING_MASK -> getString(R.string.bgr_phase_postprocessing_mask)
        OnnxBackgroundRemovalStage.SAVING_OUTPUT -> getString(R.string.bgr_phase_saving_output)
        OnnxBackgroundRemovalStage.COMPLETE -> getString(R.string.bgr_phase_complete)
    }

    private data class MirrorResult(
        val imageRelativePath: String? = null,
        val metadataRelativePath: String? = null,
        val maskRelativePath: String? = null,
        val warningMessage: String? = null,
        val refreshMetadata: ((OnnxBackgroundRemovalMetadata) -> Unit)? = null
    )

    private fun mirrorToOutputFolder(outputFile: File, maskFile: File?): MirrorResult {
        val outputFolderUri = SettingsRepository(this).outputFolderUri.value ?: return MirrorResult()
        return runCatching {
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(outputFolderUri))
                ?: return MirrorResult(warningMessage = getString(R.string.bgr_export_warning_unavailable))
            val bgrDir = rootDoc.findFile("BgR") ?: rootDoc.createDirectory("BgR")
                ?: return MirrorResult(warningMessage = getString(R.string.bgr_export_warning_unavailable))
            copyFileIntoDocument(outputFile, bgrDir, "image/png")
            maskFile?.let { copyFileIntoDocument(it, bgrDir, "image/png") }
            val metadataName = "${outputFile.name}.json"
            val refresh: (OnnxBackgroundRemovalMetadata) -> Unit = { metadata ->
                val tempMetadata = File(cacheDir, metadataName)
                tempMetadata.writeText(metadata.toJsonString())
                copyFileIntoDocument(tempMetadata, bgrDir, "application/json")
                tempMetadata.delete()
            }
            MirrorResult(
                imageRelativePath = "BgR/${outputFile.name}",
                metadataRelativePath = "BgR/$metadataName",
                maskRelativePath = maskFile?.let { "BgR/${it.name}" },
                refreshMetadata = refresh
            )
        }.getOrElse { error ->
            DebugLog.log("[ONNX-BGR] Failed to mirror output: ${error.message}")
            MirrorResult(
                warningMessage = getString(
                    R.string.bgr_export_warning_failed,
                    error.message ?: getString(R.string.error_generic)
                )
            )
        }
    }

    private fun copyFileIntoDocument(sourceFile: File, targetDir: DocumentFile, mimeType: String) {
        val existing = targetDir.findFile(sourceFile.name)
        val targetFile = existing ?: targetDir.createFile(mimeType, sourceFile.name)
        requireNotNull(targetFile) { "Could not create ${sourceFile.name}" }
        contentResolver.openOutputStream(targetFile.uri, "wt")?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open output stream for ${sourceFile.name}")
    }

    private fun cancelRemoval() {
        DebugLog.log("[ONNX-BGR] Cancel requested")
        val message = getString(R.string.action_cancelled)
        activeJob?.cancel(CancellationException(message))
        publishState(
            OnnxBackgroundRemovalState.Error(message),
            runtimeState = "ERROR",
            message = message
        )
        notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
        if (activeJob?.isCompleted != false) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        private const val ACTION_START = "com.example.llamadroid.action.START_ONNX_BGR"
        private const val ACTION_CANCEL = "com.example.llamadroid.action.CANCEL_ONNX_BGR"
        private const val EXTRA_CONFIG = "config"

        fun start(context: Context, config: OnnxBackgroundRemovalConfig) {
            val queuedState = OnnxBackgroundRemovalRuntimeState(
                state = "RUNNING",
                progress = 0f,
                status = context.getString(R.string.bgr_status_starting),
                total = config.inputPaths.size,
                startedAtEpochMs = System.currentTimeMillis()
            )
            OnnxBackgroundRemovalStateStore.updateState(
                OnnxBackgroundRemovalState.Running(
                    progress = queuedState.progress,
                    status = queuedState.status,
                    completed = queuedState.completed,
                    total = queuedState.total
                )
            )
            OnnxBackgroundRemovalStorage.writeRuntimeState(context, queuedState)
            val intent = Intent(context, OnnxBackgroundRemovalService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val message = context.getString(R.string.action_cancelled)
            OnnxBackgroundRemovalStateStore.updateState(OnnxBackgroundRemovalState.Error(message))
            OnnxBackgroundRemovalStorage.clearRuntimeState(context)
            val intent = cancelIntent(context)
            runCatching {
                context.startService(intent)
            }.onFailure {
                DebugLog.log("[ONNX-BGR] Cancel service dispatch failed: ${it.message}")
            }
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, OnnxBackgroundRemovalService::class.java).apply { action = ACTION_CANCEL }
    }
}
