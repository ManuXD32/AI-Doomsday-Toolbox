package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.onnx.OnnxTtsRequest
import com.example.llamadroid.onnx.OnnxTtsResult
import com.example.llamadroid.onnx.OnnxTtsStorage
import com.example.llamadroid.onnx.SupertonicTtsPipeline
import com.example.llamadroid.onnx.extractReadableTextFromUri
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.util.WakeLockManager
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class OnnxTtsGenerationState {
    object Idle : OnnxTtsGenerationState()
    data class Running(
        val progress: Float,
        val status: String,
        val percent: Int,
        val etaMs: Long? = null,
        val sourceName: String? = null
    ) : OnnxTtsGenerationState()
    data class Complete(
        val audioPath: String,
        val durationSeconds: Float
    ) : OnnxTtsGenerationState()
    data class Error(val message: String) : OnnxTtsGenerationState()
}

data class OnnxTtsGenerationJobSpec(
    val modelPath: String,
    val modelName: String,
    val text: String? = null,
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val language: String,
    val voiceName: String?,
    val totalSteps: Int,
    val speed: Float
)

object OnnxTtsGenerationStateStore {
    private val _state = MutableStateFlow<OnnxTtsGenerationState>(OnnxTtsGenerationState.Idle)
    val state: StateFlow<OnnxTtsGenerationState> = _state

    private val pendingJobs = ConcurrentHashMap<String, OnnxTtsGenerationJobSpec>()

    fun enqueue(spec: OnnxTtsGenerationJobSpec): String {
        val id = UUID.randomUUID().toString()
        pendingJobs[id] = spec
        return id
    }

    fun take(id: String): OnnxTtsGenerationJobSpec? = pendingJobs.remove(id)

    fun update(state: OnnxTtsGenerationState) {
        _state.value = state
    }
}

class OnnxTtsGenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private var notificationTaskId: Int? = null
    @Volatile private var cancelRequested: Boolean = false
    private var startedAtMs: Long = 0L
    private var progressTickerJob: Job? = null
    private var lastProgress: Float = 0f
    private var lastStatus: String = ""
    private var lastSourceName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
                val spec = OnnxTtsGenerationStateStore.take(jobId)
                if (spec == null) {
                    OnnxTtsGenerationStateStore.update(
                        OnnxTtsGenerationState.Error(getString(R.string.onnx_tts_error_missing_job))
                    )
                    stopSelf(startId)
                } else if (activeJob?.isActive == true) {
                    OnnxTtsGenerationStateStore.update(
                        OnnxTtsGenerationState.Error(getString(R.string.onnx_tts_error_already_running))
                    )
                } else {
                    ensureForeground(spec.sourceName ?: spec.modelName)
                    startGeneration(spec, startId)
                }
            }
            ACTION_CANCEL -> cancelGeneration()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelGeneration()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }

    private fun ensureForeground(title: String) {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ONNX_TTS,
            title.ifBlank { getString(R.string.onnx_tts_title) }
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
    }

    private fun startGeneration(spec: OnnxTtsGenerationJobSpec, startId: Int) {
        cancelRequested = false
        startedAtMs = System.currentTimeMillis()
        lastProgress = 0f
        lastStatus = ""
        lastSourceName = spec.sourceName
        WakeLockManager.acquire(applicationContext, "OnnxTtsGenerationService")
        progressTickerJob?.cancel()
        progressTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (activeJob?.isActive == true && lastProgress > 0f && lastStatus.isNotBlank()) {
                    updateProgress(lastProgress, lastStatus, lastSourceName)
                }
            }
        }
        activeJob = serviceScope.launch {
            try {
                updateProgress(0.01f, getString(R.string.onnx_tts_status_starting), spec.sourceName)
                val sourceText = resolveSourceText(spec)
                ensureActiveOrCancelled()
                val result = SupertonicTtsPipeline(applicationContext).generate(
                    OnnxTtsRequest(
                        modelPath = spec.modelPath,
                        modelName = spec.modelName,
                        text = sourceText,
                        language = spec.language,
                        voiceName = spec.voiceName,
                        totalSteps = spec.totalSteps,
                        speed = spec.speed,
                        sourceName = spec.sourceName
                    )
                ) { value, label ->
                    if (cancelRequested || activeJob?.isActive == false) {
                        throw CancellationException(getString(R.string.action_cancelled))
                    }
                    val scaled = (0.08f + value.coerceIn(0f, 1f) * 0.9f).coerceIn(0.08f, 0.98f)
                    updateProgress(scaled, label, spec.sourceName)
                }
                mirrorToSharedOutputFolder(result)
                complete(result)
            } catch (cancelled: CancellationException) {
                DebugLog.log("[ONNX-TTS] Manual generation cancelled")
                OnnxTtsGenerationStateStore.update(OnnxTtsGenerationState.Idle)
                notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
            } catch (error: Throwable) {
                val message = error.message ?: getString(R.string.error_generic)
                DebugLog.log("[ONNX-TTS] Manual generation failed: $message\n${error.stackTraceToString()}")
                OnnxTtsGenerationStateStore.update(OnnxTtsGenerationState.Error(message))
                notificationTaskId?.let { UnifiedNotificationManager.failTask(it, message) }
            } finally {
                WakeLockManager.release("OnnxTtsGenerationService")
                activeJob = null
                cancelRequested = false
                startedAtMs = 0L
                progressTickerJob?.cancel()
                progressTickerJob = null
                lastProgress = 0f
                lastStatus = ""
                lastSourceName = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun resolveSourceText(spec: OnnxTtsGenerationJobSpec): String {
        spec.sourceUri?.takeIf { it.isNotBlank() }?.let { rawUri ->
            ensureActiveOrCancelled()
            updateProgress(0.03f, getString(R.string.onnx_tts_status_extracting), spec.sourceName)
            val uri = Uri.parse(rawUri)
            val name = spec.sourceName ?: queryDisplayName(applicationContext, uri)
            val extracted = extractReadableTextFromUri(applicationContext, uri, name)
            ensureActiveOrCancelled()
            updateProgress(0.08f, getString(R.string.onnx_tts_status_extracted), spec.sourceName)
            return extracted
        }
        return spec.text?.trim().orEmpty().also {
            require(it.isNotBlank()) { getString(R.string.onnx_tts_error_empty_text) }
        }
    }

    private fun complete(result: OnnxTtsResult) {
        val taskId = notificationTaskId
        OnnxTtsGenerationStateStore.update(
            OnnxTtsGenerationState.Complete(
                audioPath = result.playableFile.absolutePath,
                durationSeconds = result.durationSeconds
            )
        )
        if (taskId != null) {
            UnifiedNotificationManager.completeTask(taskId, getString(R.string.onnx_tts_complete))
        }
    }

    private fun mirrorToSharedOutputFolder(result: OnnxTtsResult) {
        val outputFolderUri = SettingsRepository(this).outputFolderUri.value ?: run {
            DebugLog.log("[ONNX-TTS] No shared output folder configured; local output=${result.playableFile.absolutePath}")
            return
        }
        runCatching {
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(outputFolderUri))
                ?: error("Could not open configured output folder")
            val ttsDir = rootDoc.findFile(SHARED_OUTPUT_DIR) ?: rootDoc.createDirectory(SHARED_OUTPUT_DIR)
                ?: error("Could not create $SHARED_OUTPUT_DIR folder")
            copyFileIntoDocument(result.playableFile, ttsDir, mimeTypeForAudio(result.playableFile))
            val metadataFile = OnnxTtsStorage.metadataFileFor(result.playableFile)
            if (metadataFile.isFile) {
                copyFileIntoDocument(metadataFile, ttsDir, "application/json")
            }
            DebugLog.log("[ONNX-TTS] Mirrored output to shared folder: $SHARED_OUTPUT_DIR/${result.playableFile.name}")
        }.onFailure { error ->
            DebugLog.log("[ONNX-TTS] Failed to mirror output folder copy: ${error.message}\n${error.stackTraceToString()}")
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

    private fun mimeTypeForAudio(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private fun updateProgress(progress: Float, status: String, sourceName: String?) {
        val safeProgress = progress.coerceIn(0f, 1f)
        lastProgress = safeProgress
        lastStatus = status
        lastSourceName = sourceName
        val percent = (safeProgress * 100f).toInt().coerceIn(0, 100)
        val etaMs = estimateEtaMs(safeProgress)
        val notificationText = buildNotificationText(percent, etaMs, status)
        OnnxTtsGenerationStateStore.update(
            OnnxTtsGenerationState.Running(
                progress = safeProgress,
                status = notificationText,
                percent = percent,
                etaMs = etaMs,
                sourceName = sourceName
            )
        )
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, safeProgress, notificationText)
        }
    }

    private fun estimateEtaMs(progress: Float): Long? {
        if (startedAtMs <= 0L || progress <= 0.05f || progress >= 1f) return null
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val totalMs = (elapsedMs / progress).toLong()
        return (totalMs - elapsedMs).coerceAtLeast(0L)
    }

    private fun buildNotificationText(percent: Int, etaMs: Long?, status: String): String {
        return if (etaMs != null && etaMs > 0L) {
            getString(
                R.string.onnx_tts_notification_progress_eta,
                percent,
                FormatUtils.Display.formatDuration(etaMs / 1000.0),
                status
            )
        } else {
            getString(R.string.onnx_tts_notification_progress, percent, status)
        }
    }

    private fun ensureActiveOrCancelled() {
        if (cancelRequested || activeJob?.isActive == false) {
            throw CancellationException(getString(R.string.action_cancelled))
        }
    }

    private fun cancelGeneration() {
        cancelRequested = true
        activeJob?.cancel(CancellationException(getString(R.string.action_cancelled)))
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.action.START_ONNX_TTS"
        const val ACTION_CANCEL = "com.example.llamadroid.action.CANCEL_ONNX_TTS"
        private const val EXTRA_JOB_ID = "job_id"
        private const val SHARED_OUTPUT_DIR = "tts"

        fun start(context: Context, spec: OnnxTtsGenerationJobSpec) {
            val jobId = OnnxTtsGenerationStateStore.enqueue(spec)
            val intent = Intent(context, OnnxTtsGenerationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB_ID, jobId)
                spec.sourceUri?.let { data = Uri.parse(it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }

        fun cancelIntent(context: Context): Intent =
            Intent(context, OnnxTtsGenerationService::class.java).apply { action = ACTION_CANCEL }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: File(uri.path.orEmpty()).name.ifBlank { "document" }
}
