package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.repository.LiteRtModelRepository
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
import com.example.llamadroid.onnx.ONNX_INSTALL_KIND_HF_TREE_BUNDLE
import com.example.llamadroid.onnx.OnnxCatalog
import com.example.llamadroid.onnx.OnnxBundleValidator
import com.example.llamadroid.onnx.OnnxImportSupport
import com.example.llamadroid.onnx.OnnxTtsBundleValidator
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import java.io.File

/**
 * Foreground service for downloading models.
 * Keeps downloads running when app is backgrounded.
 */
class DownloadService : Service() {
    
    companion object {
        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_CANCEL_ALL = "cancel_all"
        
        const val EXTRA_URL = "url"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_FILENAME = "filename"
        
        private val activeDownloads = mutableMapOf<String, Job>()
        
        fun startDownload(context: Context, url: String, destPath: String, filename: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DEST_PATH, destPath)
                putExtra(EXTRA_FILENAME, filename)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun cancelDownload(context: Context, filename: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILENAME, filename)
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationTaskId: Int? = null
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                
                val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.DOWNLOAD,
                    filename
                )
                notificationTaskId = taskId
                startForeground(taskId, notification)
                startDownloadInternal(url, destPath, filename)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                cancelDownloadInternal(filename)
            }
            ACTION_CANCEL_ALL -> {
                activeDownloads.forEach { (_, job) -> job.cancel() }
                activeDownloads.clear()
                Downloader.cancelAllDownloads()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun startDownloadInternal(url: String, destPath: String, filename: String) {
        val destFile = File(destPath)
        val pending = PendingDownloadHolder.getPending(filename)
        val progressKey = pending?.progressKey ?: DownloadProgressHolder.findRepoIdByFilename(filename) ?: filename
        
        // Ensure parent directory exists
        destFile.parentFile?.mkdirs()
        
        val job = serviceScope.launch {
            var lastProgress = 0
            var downloadSuccess = false
            var completionError: String? = null

            if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_HF_TREE_BUNDLE) {
                try {
                    val db = AppDatabase.getDatabase(this@DownloadService)
                    val entity = downloadPendingHfTreeBundle(
                        pending = pending,
                        onProgress = { progress, label ->
                            val progressPercent = (progress * 100).toInt()
                            DownloadProgressHolder.updateProgress(progressKey, progress)
                            DownloadProgressHolder.updateStatus(progressKey, label)
                            if (progressPercent >= lastProgress + 5 || progress >= 1f) {
                                lastProgress = progressPercent
                                updateNotification(label, progressPercent)
                            }
                        }
                    )
                    db.modelDao().insertModel(entity)
                    DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                    DownloadProgressHolder.removeProgress(progressKey)
                } catch (e: Exception) {
                    DebugLog.log("DownloadService: Failed to download ONNX tree bundle - ${e.message}")
                    completionError = e.message ?: "Failed to finalize download"
                    DownloadProgressHolder.updateProgress(progressKey, -1f)
                    DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_failed))
                    DownloadProgressHolder.removeProgress(progressKey)
                } finally {
                    PendingDownloadHolder.removePending(filename)
                    activeDownloads.remove(filename)
                    if (activeDownloads.isEmpty()) {
                        completionError?.let { error ->
                            notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                        } ?: updateNotification("Downloads complete", 100)
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return@launch
            }
            
            Downloader.download(url, destFile, this@DownloadService, pending?.huggingFaceToken)
                .catch { e ->
                    DebugLog.log("DownloadService: Download failed - ${e.message}")
                    DownloadProgressHolder.updateProgress(progressKey, -1f)
                    DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_failed))
                    PendingDownloadHolder.removePending(filename)
                    DownloadProgressHolder.removeProgress(progressKey)
                }
                .collect { progress ->
                    val mappedProgress = if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        progress * 0.9f
                    } else {
                        progress
                    }
                    DownloadProgressHolder.updateProgress(progressKey, mappedProgress)
                    if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_downloading))
                    }
                    val progressPercent = (mappedProgress * 100).toInt()
                    if (progressPercent >= lastProgress + 5 || progress == 1f) {
                        lastProgress = progressPercent
                        updateNotification(filename, progressPercent)
                    }
                    if (progress >= 1f) {
                        downloadSuccess = true
                    }
                }
            
            // Download complete - save to DB if pending
            if (downloadSuccess) {
                if (pending != null) {
                    try {
                        val db = AppDatabase.getDatabase(this@DownloadService)
                        var lastFinalizePercent = -1
                        var lastFinalizeLabel: String? = null
                        val progressReporter: (Float, String) -> Unit = { progress, label ->
                            val progressPercent = (progress * 100).toInt()
                            val shouldReport =
                                label != lastFinalizeLabel ||
                                    progressPercent >= lastFinalizePercent + 2 ||
                                    progress >= 1f
                            if (shouldReport) {
                                lastFinalizePercent = progressPercent
                                lastFinalizeLabel = label
                                DownloadProgressHolder.updateProgress(progressKey, progress)
                                DownloadProgressHolder.updateStatus(progressKey, label)
                                updateNotification(label, progressPercent)
                            }
                        }
                        if (pending.liteRtDisplayName != null) {
                            LiteRtModelRepository(this@DownloadService, db.liteRtModelDao()).finalizeServiceDownload(
                                pending = pending,
                                downloadedFile = destFile,
                                onProgress = progressReporter
                            )
                            DebugLog.log("DownloadService: Saved $filename to LiteRT model DB")
                        } else {
                            val entity = finalizePendingDownload(
                                pending = pending,
                                downloadedFile = destFile,
                                onProgress = progressReporter
                            )
                            db.modelDao().insertModel(entity)
                            DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                        }
                        DownloadProgressHolder.removeProgress(progressKey)
                    } catch (e: Exception) {
                        DebugLog.log("DownloadService: Failed to save to DB - ${e.message}")
                        completionError = e.message ?: "Failed to finalize download"
                        DownloadProgressHolder.updateProgress(progressKey, -1f)
                        DownloadProgressHolder.removeProgress(progressKey)
                    }
                    PendingDownloadHolder.removePending(filename)
                }
            }
            
                activeDownloads.remove(filename)
                if (activeDownloads.isEmpty()) {
                completionError?.let { error ->
                    notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                } ?: updateNotification("Downloads complete", 100)
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        activeDownloads[filename] = job
    }
    
    private fun cancelDownloadInternal(filename: String) {
        activeDownloads[filename]?.cancel()
        activeDownloads.remove(filename)
        Downloader.cancelDownload(filename)
        val pending = PendingDownloadHolder.getPending(filename)
        val progressKey = pending?.progressKey ?: DownloadProgressHolder.findRepoIdByFilename(filename) ?: filename
        DownloadProgressHolder.updateProgress(progressKey, -1f)
        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_cancelled))
        pending?.destPath?.let { runCatching { File(it).delete() } }
        PendingDownloadHolder.removePending(filename)
        DownloadProgressHolder.removeProgress(progressKey)
        
        if (activeDownloads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    
    private fun updateNotification(text: String, progress: Int) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, progress / 100f, text)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }

    private suspend fun finalizePendingDownload(
        pending: PendingDownload,
        downloadedFile: File,
        onProgress: (Float, String) -> Unit
    ): ModelEntity {
        return if (
            (pending.type == ModelType.ONNX_IMAGE_GEN || pending.type == ModelType.ONNX_TTS) &&
            pending.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE
        ) {
            val installDirPath = pending.onnxInstallDirPath
                ?: error("Missing ONNX install directory for ${pending.filename}")
            val installDir = File(installDirPath)
            try {
                val coroutineContext = currentCoroutineContext()
                onProgress(0.92f, getString(R.string.onnx_models_phase_extracting))
                val extractedSizeBytes = OnnxImportSupport.extractBundleArchive(
                    archiveFile = downloadedFile,
                    installDir = installDir,
                    onPhase = { phase ->
                        val label = when (phase) {
                            "extracting" -> getString(R.string.onnx_models_phase_extracting)
                            "validating" -> getString(R.string.onnx_models_phase_validating)
                            "completed" -> getString(R.string.onnx_models_phase_completed)
                            else -> pending.filename
                        }
                        onProgress(0.92f, label)
                    },
                    ensureActive = { coroutineContext.ensureActive() },
                    onProgress = { extractProgress ->
                        coroutineContext.ensureActive()
                        onProgress(0.92f + (extractProgress * 0.07f), getString(R.string.onnx_models_phase_extracting))
                    }
                )
                onProgress(1f, getString(R.string.onnx_models_phase_completed))
                val validation = if (pending.type == ModelType.ONNX_TTS) {
                    OnnxTtsBundleValidator.validateDirectory(installDir)
                } else {
                    OnnxBundleValidator.validateDirectory(installDir)
                }
                val resolvedOnnxCapabilities = ModelRepository.resolveOnnxCapabilities(
                    explicitCapabilities = pending.onnxCapabilities,
                    detectedCapabilities = validation.supportedCapabilities
                )
                ModelEntity(
                    filename = pending.filename,
                    path = installDir.absolutePath,
                    sizeBytes = extractedSizeBytes,
                    type = pending.type,
                    repoId = pending.repoId,
                    isDownloaded = true,
                    isVision = pending.isVision,
                    sdCapabilities = pending.sdCapabilities,
                    sdFamily = pending.sdFamily,
                    sdVariant = pending.sdVariant,
                    sdCompatProfiles = pending.sdCompatProfiles,
                    onnxCapabilities = resolvedOnnxCapabilities,
                    onnxAssetKind = pending.onnxAssetKind,
                    onnxPipelineFamily = pending.onnxPipelineFamily,
                    onnxReferenceUri = pending.onnxReferenceUri,
                    onnxReferencePath = pending.onnxReferencePath
                )
            } catch (e: Exception) {
                OnnxImportSupport.deleteRecursively(installDir)
                throw e
            } finally {
                downloadedFile.delete()
            }
        } else {
            val resolvedOnnxCapabilities = if (pending.type == ModelType.ONNX_IMAGE_GEN) {
                ModelRepository.resolveOnnxCapabilities(
                    explicitCapabilities = pending.onnxCapabilities,
                    detectedCapabilities = emptySet()
                )
            } else {
                pending.onnxCapabilities
            }
            ModelEntity(
                filename = pending.filename,
                path = downloadedFile.absolutePath,
                sizeBytes = downloadedFile.length(),
                type = pending.type,
                repoId = pending.repoId,
                isDownloaded = true,
                isVision = pending.isVision,
                sdCapabilities = pending.sdCapabilities,
                sdFamily = pending.sdFamily,
                sdVariant = pending.sdVariant,
                sdCompatProfiles = pending.sdCompatProfiles,
                onnxCapabilities = resolvedOnnxCapabilities,
                onnxAssetKind = pending.onnxAssetKind,
                onnxPipelineFamily = pending.onnxPipelineFamily,
                onnxReferenceUri = pending.onnxReferenceUri,
                onnxReferencePath = pending.onnxReferencePath
            )
        }
    }

    private suspend fun downloadPendingHfTreeBundle(
        pending: PendingDownload,
        onProgress: (Float, String) -> Unit
    ): ModelEntity {
        require(pending.type == ModelType.ONNX_TTS) {
            "Hugging Face tree bundles are only supported for ONNX TTS."
        }
        val installDirPath = pending.onnxInstallDirPath
            ?: error("Missing ONNX install directory for ${pending.filename}")
        val installDir = File(installDirPath)
        OnnxImportSupport.deleteRecursively(installDir)
        installDir.mkdirs()
        val files = OnnxCatalog.supertonicRequiredFiles
        val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var completedBytes = 0L
        try {
            files.forEach { fileEntry ->
                currentCoroutineContext().ensureActive()
                val output = File(installDir, fileEntry.relativePath)
                output.parentFile?.mkdirs()
                val url = OnnxCatalog.supertonicResolveUrl(fileEntry.relativePath)
                Downloader.download(url, output, this@DownloadService)
                    .collect { fileProgress ->
                        currentCoroutineContext().ensureActive()
                        val weighted = (completedBytes + (fileEntry.sizeBytes * fileProgress).toLong())
                            .toFloat() / totalBytes.toFloat()
                        onProgress(weighted.coerceIn(0f, 0.96f), getString(R.string.onnx_models_phase_downloading))
                    }
                completedBytes += fileEntry.sizeBytes
            }
            onProgress(0.98f, getString(R.string.onnx_models_phase_validating))
            val validation = OnnxTtsBundleValidator.validateDirectory(installDir)
            require(validation.isValid) {
                "Missing Supertonic bundle files: ${validation.missingPaths.joinToString(", ")}"
            }
            val resolvedOnnxCapabilities = ModelRepository.resolveOnnxCapabilities(
                explicitCapabilities = pending.onnxCapabilities,
                detectedCapabilities = validation.supportedCapabilities
            )
            val sizeBytes = installDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            onProgress(1f, getString(R.string.onnx_models_phase_completed))
            return ModelEntity(
                filename = pending.filename,
                path = installDir.absolutePath,
                sizeBytes = sizeBytes,
                type = pending.type,
                repoId = pending.repoId,
                isDownloaded = true,
                isVision = pending.isVision,
                sdCapabilities = pending.sdCapabilities,
                sdFamily = pending.sdFamily,
                sdVariant = pending.sdVariant,
                sdCompatProfiles = pending.sdCompatProfiles,
                onnxCapabilities = resolvedOnnxCapabilities,
                onnxAssetKind = pending.onnxAssetKind,
                onnxPipelineFamily = pending.onnxPipelineFamily,
                onnxReferenceUri = pending.onnxReferenceUri,
                onnxReferencePath = pending.onnxReferencePath
            )
        } catch (e: Exception) {
            OnnxImportSupport.deleteRecursively(installDir)
            throw e
        } finally {
            runCatching { File(pending.destPath).delete() }
        }
    }
}
