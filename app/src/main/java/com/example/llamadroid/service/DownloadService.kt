package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_FAILED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_RESUMABLE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadTaskArtifacts
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelLibraryManager
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.downloadPartFile
import com.example.llamadroid.data.model.partFile
import com.example.llamadroid.data.model.toDownloadTaskEntity
import com.example.llamadroid.data.model.toPendingDownload
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
        const val ACTION_DISCARD_DOWNLOAD = "discard_download"
        
        const val EXTRA_URL = "url"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        
        private val activeDownloads = mutableMapOf<String, Job>()
        
        fun startDownload(
            context: Context,
            url: String,
            destPath: String,
            filename: String,
            downloadId: String = filename
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DEST_PATH, destPath)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun cancelDownload(context: Context, filename: String, downloadId: String = filename) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun discardDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DISCARD_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
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
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH)
                val filename = intent.getStringExtra(EXTRA_FILENAME)
                
                val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.DOWNLOAD,
                    filename ?: downloadId ?: getString(R.string.models_tab_downloading)
                )
                notificationTaskId = taskId
                startForeground(taskId, notification)
                startDownloadInternal(url, destPath, filename, downloadId)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: filename
                cancelDownloadInternal(filename, downloadId)
            }
            ACTION_CANCEL_ALL -> {
                activeDownloads.forEach { (_, job) -> job.cancel() }
                activeDownloads.clear()
                Downloader.cancelAllDownloads()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISCARD_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return START_NOT_STICKY
                discardDownloadInternal(downloadId)
            }
        }
        return START_NOT_STICKY
    }
    
    private fun startDownloadInternal(url: String?, destPath: String?, filename: String?, downloadId: String?) {
        val job = serviceScope.launch {
            val db = AppDatabase.getDatabase(this@DownloadService)
            val taskDao = db.downloadTaskDao()
            val resolvedTaskId = downloadId ?: filename ?: destPath ?: return@launch
            val storedTask = taskDao.getById(resolvedTaskId)
                ?: filename?.let { taskDao.getByFilename(it) }
            val memoryPending = PendingDownloadHolder.getPending(resolvedTaskId)
                ?: filename?.let { PendingDownloadHolder.getPending(it) }
            val pending = memoryPending ?: storedTask?.toPendingDownload()
            val finalUrl = url ?: storedTask?.url ?: return@launch
            val finalDestPath = destPath ?: storedTask?.destPath ?: pending?.destPath ?: return@launch
            val finalFilename = filename ?: storedTask?.filename ?: pending?.filename ?: File(finalDestPath).name
            val destFile = File(finalDestPath)
            val progressKey = pending?.progressKey ?: storedTask?.progressKey ?: resolvedTaskId
            val persistedTask = pending?.toDownloadTaskEntity(resolvedTaskId, finalUrl)
                ?: storedTask?.copy(status = DOWNLOAD_TASK_STATUS_ACTIVE, updatedAt = System.currentTimeMillis())
                ?: DownloadTaskEntity(
                    id = resolvedTaskId,
                    url = finalUrl,
                    destPath = finalDestPath,
                    filename = finalFilename,
                    repoId = finalUrl,
                    progressKey = progressKey,
                    modelType = ModelType.LLM.name
                )
            taskDao.upsert(persistedTask)
            PendingDownloadHolder.addPendingFrom(persistedTask)
            DownloadProgressHolder.updateProgress(progressKey, finalFilename, initialProgressFor(destFile))

            destFile.parentFile?.mkdirs()
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
                            serviceScope.launch {
                                taskDao.updateState(
                                    id = resolvedTaskId,
                                    status = DOWNLOAD_TASK_STATUS_ACTIVE,
                                    bytesDownloaded = persistedTask.partFile().length(),
                                    totalBytes = null,
                                    lastError = null
                                )
                            }
                            if (progressPercent >= lastProgress + 5 || progress >= 1f) {
                                lastProgress = progressPercent
                                updateNotification(label, progressPercent)
                            }
                        }
                    )
                    db.modelDao().insertModel(entity)
                    DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                    DownloadProgressHolder.removeProgress(progressKey)
                    taskDao.updateState(resolvedTaskId, DOWNLOAD_TASK_STATUS_COMPLETED, 0L, null, null)
                    taskDao.deleteById(resolvedTaskId)
                } catch (e: Exception) {
                    DebugLog.log("DownloadService: Failed to download ONNX tree bundle - ${e.message}")
                    completionError = e.message ?: "Failed to finalize download"
                    DownloadProgressHolder.updateProgress(progressKey, -1f)
                    DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_failed))
                    taskDao.updateState(
                        resolvedTaskId,
                        DOWNLOAD_TASK_STATUS_FAILED,
                        persistedTask.partFile().length(),
                        null,
                        completionError
                    )
                    DownloadProgressHolder.removeProgress(progressKey)
                } finally {
                    PendingDownloadHolder.removePending(resolvedTaskId)
                    activeDownloads.remove(resolvedTaskId)
                    if (activeDownloads.isEmpty()) {
                        completionError?.let { error ->
                            notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                        } ?: updateNotification(getString(R.string.downloads_complete), 100)
                        delay(2000)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return@launch
            }
            
            Downloader.download(
                url = finalUrl,
                destFile = destFile,
                context = this@DownloadService,
                bearerToken = pending?.huggingFaceToken,
                downloadId = resolvedTaskId
            )
                .catch { e ->
                    DebugLog.log("DownloadService: Download failed - ${e.message}")
                    val failureStatus = if (pending?.liteRtDisplayName != null && e.isHuggingFaceAccessFailure()) {
                        if (pending.huggingFaceToken.isNullOrBlank()) {
                            getString(R.string.litert_hf_token_required_error, e.huggingFaceStatusCode())
                        } else {
                            getString(R.string.litert_hf_access_denied_error, e.huggingFaceStatusCode())
                        }
                    } else {
                        getString(R.string.onnx_models_download_failed)
                    }
                    DownloadProgressHolder.updateProgress(progressKey, -1f)
                    DownloadProgressHolder.updateStatus(progressKey, failureStatus)
                    val bytes = downloadPartFile(finalDestPath).length()
                    val resumable = bytes > 0L
                    taskDao.updateState(
                        id = resolvedTaskId,
                        status = if (resumable) DOWNLOAD_TASK_STATUS_RESUMABLE else DOWNLOAD_TASK_STATUS_FAILED,
                        bytesDownloaded = bytes,
                        totalBytes = null,
                        lastError = e.message
                    )
                    PendingDownloadHolder.removePending(resolvedTaskId)
                }
                .collect { progress ->
                    val mappedProgress = if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        if (progress >= 0f) progress * 0.9f else progress
                    } else {
                        progress
                    }
                    DownloadProgressHolder.updateProgress(progressKey, mappedProgress)
                    if (pending?.onnxInstallKind == ONNX_INSTALL_KIND_ARCHIVE_BUNDLE) {
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_downloading))
                    }
                    taskDao.updateState(
                        id = resolvedTaskId,
                        status = DOWNLOAD_TASK_STATUS_ACTIVE,
                        bytesDownloaded = downloadPartFile(finalDestPath).length(),
                        totalBytes = null,
                        lastError = null
                    )
                    val progressPercent = if (mappedProgress >= 0f) (mappedProgress * 100).toInt() else lastProgress
                    if (mappedProgress >= 0f && (progressPercent >= lastProgress + 5 || progress == 1f)) {
                        lastProgress = progressPercent
                        updateNotification(finalFilename, progressPercent)
                    }
                    if (progress >= 1f) {
                        downloadSuccess = true
                    }
                }
            
            // Download complete - save to DB if pending
            if (downloadSuccess) {
                if (pending != null) {
                    try {
                        val genericCurated =
                            com.example.llamadroid.data.model.CuratedModelBundleRegistry
                                .fileForInstalledFilename(finalFilename)
                        val sdCurated =
                            com.example.llamadroid.data.model.SdCuratedBundleCatalog
                                .fileForLocalFilename(finalFilename)
                        if (genericCurated != null || sdCurated != null) {
                            val verifyingLabel = getString(R.string.sd_bundle_verifying)
                            DownloadProgressHolder.updateProgress(progressKey, 0.999f)
                            DownloadProgressHolder.updateStatus(progressKey, verifyingLabel)
                            updateNotification(verifyingLabel, 99)
                            if (genericCurated != null) {
                                com.example.llamadroid.data.model.verifyCuratedModelDownload(
                                    localFilename = finalFilename,
                                    downloadedFile = destFile
                                )
                            } else {
                                com.example.llamadroid.data.model.verifySdCuratedDownload(
                                    localFilename = finalFilename,
                                    downloadedFile = destFile
                                )
                            }
                        }
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
                        DownloadProgressHolder.updateProgress(progressKey, 1f)
                        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_phase_completed))
                        taskDao.updateState(
                            id = resolvedTaskId,
                            status = DOWNLOAD_TASK_STATUS_COMPLETED,
                            bytesDownloaded = destFile.length(),
                            totalBytes = destFile.length(),
                            lastError = null
                        )
                        taskDao.deleteById(resolvedTaskId)
                        delay(1200)
                        DownloadProgressHolder.removeProgress(progressKey)
                    } catch (e: Exception) {
                        DebugLog.log("DownloadService: Failed to save to DB - ${e.message}")
                        completionError = e.message ?: "Failed to finalize download"
                        DownloadProgressHolder.updateProgress(progressKey, -1f)
                        taskDao.updateState(
                            id = resolvedTaskId,
                            status = DOWNLOAD_TASK_STATUS_FAILED,
                            bytesDownloaded = downloadPartFile(finalDestPath).length(),
                            totalBytes = null,
                            lastError = completionError
                        )
                    }
                    PendingDownloadHolder.removePending(resolvedTaskId)
                } else {
                    taskDao.updateState(
                        id = resolvedTaskId,
                        status = DOWNLOAD_TASK_STATUS_COMPLETED,
                        bytesDownloaded = destFile.length(),
                        totalBytes = destFile.length(),
                        lastError = null
                    )
                    taskDao.deleteById(resolvedTaskId)
                }
            }
            
            activeDownloads.remove(resolvedTaskId)
            if (activeDownloads.isEmpty()) {
                completionError?.let { error ->
                    notificationTaskId?.let { UnifiedNotificationManager.failTask(it, error) }
                } ?: updateNotification(getString(R.string.downloads_complete), 100)
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        downloadId?.let { activeDownloads[it] = job }
    }
    
    private fun cancelDownloadInternal(filename: String, downloadId: String) {
        activeDownloads[downloadId]?.cancel()
        activeDownloads.remove(downloadId)
        Downloader.cancelDownload(downloadId)
        val pending = PendingDownloadHolder.getPending(downloadId)
            ?: PendingDownloadHolder.getPending(filename)
        val progressKey = pending?.progressKey ?: downloadId
        DownloadProgressHolder.updateProgress(progressKey, -1f)
        DownloadProgressHolder.updateStatus(progressKey, getString(R.string.onnx_models_download_cancelled))
        pending?.destPath?.let { path ->
            runCatching { File(path).delete() }
            runCatching {
                val destFile = File(path)
                File(destFile.parentFile, "${destFile.name}.part").delete()
            }
        }
        PendingDownloadHolder.removePending(downloadId)
        DownloadProgressHolder.removeProgress(progressKey)
        serviceScope.launch {
            AppDatabase.getDatabase(this@DownloadService)
                .downloadTaskDao()
                .updateStatus(downloadId, DOWNLOAD_TASK_STATUS_CANCELLED, null)
            AppDatabase.getDatabase(this@DownloadService)
                .downloadTaskDao()
                .deleteById(downloadId)
        }
        
        if (activeDownloads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun discardDownloadInternal(downloadId: String) {
        serviceScope.launch {
            activeDownloads[downloadId]?.cancel()
            activeDownloads.remove(downloadId)
            Downloader.cancelDownload(downloadId)
            val dao = AppDatabase.getDatabase(this@DownloadService).downloadTaskDao()
            val task = dao.getById(downloadId) ?: return@launch
            DownloadTaskArtifacts.deleteIncompleteArtifacts(task)
            PendingDownloadHolder.removePending(downloadId)
            DownloadProgressHolder.removeProgress(task.progressKey)
            dao.updateStatus(downloadId, DOWNLOAD_TASK_STATUS_CANCELLED, null)
            dao.deleteById(downloadId)
            if (activeDownloads.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun initialProgressFor(destFile: File): Float {
        val part = File(destFile.parentFile ?: File("."), "${destFile.name}.part")
        return if (part.length() > 0L) DownloadProgressHolder.INDETERMINATE else 0f
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
            if (ModelLibraryManager.usesManagedExternalCanonicalStorage(pending.type)) {
                // Single-copy path: the managed external destination is already the canonical runtime file.
            } else if (pending.type == ModelType.ONNX_IMAGE_GEN ||
                pending.type == ModelType.ONNX_BACKGROUND_REMOVAL ||
                pending.type == ModelType.ONNX_IMAGE_UPSCALER ||
                pending.type == ModelType.ONNX_TTS
            ) {
                // ONNX payloads now stay in internal app-managed storage only.
            } else {
                ModelLibraryManager.copyFileToLibrary(
                    context = this@DownloadService,
                    relativeDir = ModelLibraryManager.relativeDirFor(pending.type),
                    filename = pending.filename,
                    sourceFile = downloadedFile
                ).getOrThrow()
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

    private fun Throwable.isHuggingFaceAccessFailure(): Boolean {
        val message = message.orEmpty()
        return "huggingface.co" in message && ("(401)" in message || "(403)" in message)
    }

    private fun Throwable.huggingFaceStatusCode(): Int =
        when {
            "(401)" in message.orEmpty() -> 401
            else -> 403
        }
}
