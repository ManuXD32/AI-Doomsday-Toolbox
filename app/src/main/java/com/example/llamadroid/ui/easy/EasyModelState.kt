package com.example.llamadroid.ui.easy

import android.content.Context
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_CANCELLED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_COMPLETED
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.EasyModelEntry
import com.example.llamadroid.data.easy.EasyModelSource
import com.example.llamadroid.data.model.BundleProgressEntry
import com.example.llamadroid.data.model.BundleProgressSnapshot
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.data.model.calculateBundleProgressSnapshot
import com.example.llamadroid.data.model.expectedSdCuratedDownloadHandle
import com.example.llamadroid.data.model.isInstalledForBundle
import com.example.llamadroid.data.model.startSdCuratedBundleFileDownload
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.ui.components.requestCuratedBundleDownload
import com.example.llamadroid.ui.components.resolveCuratedFiles
import com.example.llamadroid.ui.components.toProgressEntry
import java.io.File

/** Install / download status of one curated entry, derived from the shared download state. */
data class EasyEntryStatus(
    val installed: ModelEntity?,
    val downloading: Boolean,
    val progress: Float
)

/**
 * Thin adapter over the existing download paths. Install state comes from Room (`ModelEntity`),
 * progress from `DownloadProgressHolder` plus the persisted tasks, and every start call goes to
 * the same function the canonical model managers use.
 */
class EasyModelState(
    private val context: Context,
    private val repository: ModelRepository,
    val installedModels: List<ModelEntity>,
    private val tasks: List<DownloadTaskEntity>,
    private val progressMap: Map<String, Float>,
    /** Last snapshot per entry id, owned by the composable so a bar never moves backwards. */
    private val previousSnapshots: MutableMap<String, BundleProgressSnapshot> = HashMap(),
    private val previousFractions: MutableMap<String, Float> = HashMap()
) {
    private val taskByKey: Map<String, DownloadTaskEntity> = tasks
        .asSequence()
        .flatMap { sequenceOf(it.id to it, it.progressKey to it) }
        .toMap()

    private fun isLive(value: Float?): Boolean =
        value != null && (value == DownloadProgressHolder.INDETERMINATE || value in 0f..0.999f)

    fun status(entry: EasyModelEntry): EasyEntryStatus {
        val installed = EasyModelCatalog.installedModel(entry, installedModels)
        if (installed != null) return EasyEntryStatus(installed, false, 1f)
        return when (val source = entry.source) {
            is EasyModelSource.LlamaBundle -> {
                val bundle = EasyModelCatalog.llamaBundle(entry)
                    ?: return EasyEntryStatus(null, false, 0f)
                val snapshot = calculateBundleProgressSnapshot(
                    resolveCuratedFiles(bundle, bundle.defaultPrefix, installedModels, taskByKey, progressMap)
                        .map { it.toProgressEntry() },
                    previousSnapshots[entry.id]
                )
                track(entry.id, snapshot)
            }
            is EasyModelSource.SdBundle -> {
                val bundle = EasyModelCatalog.sdBundle(entry)
                    ?: return EasyEntryStatus(null, false, 0f)
                val snapshot = calculateBundleProgressSnapshot(entries = bundle.files.map { file ->
                    val handle = expectedSdCuratedDownloadHandle(bundle, file)
                    val task = taskByKey[handle.progressKey]
                    val live = progressMap[handle.progressKey]
                    BundleProgressEntry(
                        key = handle.progressKey,
                        declaredBytes = file.sizeBytes,
                        installed = file.isInstalledForBundle(bundle, installedModels),
                        completed = task?.status == DOWNLOAD_TASK_STATUS_COMPLETED || live == 1f,
                        cancelled = task?.status == DOWNLOAD_TASK_STATUS_CANCELLED ||
                            live != null && live < 0f && live != DownloadProgressHolder.INDETERMINATE,
                        active = task?.status == DOWNLOAD_TASK_STATUS_ACTIVE || isLive(live),
                        persistedTaskBytes = task?.bytesDownloaded,
                        // No File.length() here: status() runs in composition; persisted bytes are used instead.
                        partBytes = null,
                        liveFraction = live
                    )
                }, previousSnapshot = previousSnapshots[entry.id])
                track(entry.id, snapshot)
            }
            is EasyModelSource.Onnx -> {
                val onnx = EasyModelCatalog.onnxEntry(entry) ?: return EasyEntryStatus(null, false, 0f)
                val live = progressMap["onnx:${onnx.stableId}"]
                monotonic(entry.id, isLive(live), live?.coerceIn(0f, 1f) ?: 0f)
            }
            is EasyModelSource.Whisper -> {
                val live = progressMap["whisper_${source.model.filename}"]
                monotonic(entry.id, isLive(live), live?.coerceIn(0f, 1f) ?: 0f)
            }
        }
    }

    /** Stores the snapshot while a download is live (forgets it afterwards) and maps it to a status. */
    private fun track(id: String, snapshot: BundleProgressSnapshot): EasyEntryStatus {
        if (snapshot.hasActiveDownloads) previousSnapshots[id] = snapshot else previousSnapshots.remove(id)
        return EasyEntryStatus(null, snapshot.hasActiveDownloads, snapshot.progress)
    }

    private fun monotonic(id: String, active: Boolean, fraction: Float): EasyEntryStatus {
        if (!active) {
            previousFractions.remove(id)
            return EasyEntryStatus(null, false, fraction)
        }
        val shown = maxOf(fraction, previousFractions[id] ?: 0f)
        previousFractions[id] = shown
        return EasyEntryStatus(null, true, shown)
    }

    /**
     * Starts the download through the same function the canonical managers use. Returns false when
     * the request failed (the caller shows the error) instead of silently dropping the result.
     */
    fun start(entry: EasyModelEntry): Boolean = runCatching {
        when (val source = entry.source) {
            is EasyModelSource.LlamaBundle -> EasyModelCatalog.llamaBundle(entry)?.let { bundle ->
                requestCuratedBundleDownload(
                    context = context,
                    bundle = bundle,
                    installedModels = installedModels,
                    persistedTasks = tasks,
                    progressMap = progressMap
                ).failed == 0
            } ?: false
            is EasyModelSource.SdBundle -> EasyModelCatalog.sdBundle(entry)?.let { bundle ->
                bundle.files
                    .filterNot { it.isInstalledForBundle(bundle, installedModels) }
                    .forEach { startSdCuratedBundleFileDownload(context, repository, bundle, it) }
                true
            } ?: false
            is EasyModelSource.Onnx -> EasyModelCatalog.onnxEntry(entry)?.let {
                repository.startOnnxCatalogDownload(it)
                true
            } ?: false
            is EasyModelSource.Whisper -> { startWhisper(source); true }
        }
    }.getOrDefault(false)

    fun cancel(entry: EasyModelEntry) {
        when (val source = entry.source) {
            is EasyModelSource.LlamaBundle -> EasyModelCatalog.llamaBundle(entry)?.let { bundle ->
                resolveCuratedFiles(bundle, bundle.defaultPrefix, installedModels, taskByKey, progressMap)
                    .filter { it.active }
                    .forEach { DownloadService.cancelDownload(context, it.expectedName, it.taskId) }
            }
            is EasyModelSource.SdBundle -> EasyModelCatalog.sdBundle(entry)?.let { bundle ->
                bundle.files.map { expectedSdCuratedDownloadHandle(bundle, it) }
                    .filter { isLive(progressMap[it.progressKey]) }
                    .forEach { DownloadService.cancelDownload(context, it.filename, it.progressKey) }
            }
            is EasyModelSource.Onnx -> EasyModelCatalog.onnxEntry(entry)?.let {
                DownloadService.cancelDownload(context, it.stableId, "onnx:${it.stableId}")
            }
            is EasyModelSource.Whisper ->
                DownloadService.cancelDownload(context, source.model.filename, "whisper_${source.model.filename}")
        }
    }

    // Same registration the Whisper manager performs (WhisperModelsScreen.startModelDownload).
    private fun startWhisper(source: EasyModelSource.Whisper) {
        val model = source.model
        val progressKey = "whisper_${model.filename}"
        val destPath = File(repository.getModelDir(ModelType.WHISPER).apply { mkdirs() }, model.filename).absolutePath
        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = model.filename,
            repoId = "ggerganov/whisper.cpp",
            progressKey = progressKey,
            type = ModelType.WHISPER,
            destPath = destPath,
            classificationSource = com.example.llamadroid.data.model.library.ModelClassificationSource.CATALOG.storedValue
        )
        DownloadProgressHolder.updateProgress(progressKey, model.filename, 0f)
        DownloadService.startDownload(context, model.downloadUrl, destPath, model.filename, progressKey)
    }
}
