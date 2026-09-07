package com.example.llamadroid.data.model

import android.content.Context
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_DISCARDED
import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_STALE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxStorage
import java.io.File

fun PendingDownload.toDownloadTaskEntity(
    downloadId: String,
    url: String,
    status: String = DOWNLOAD_TASK_STATUS_ACTIVE
): DownloadTaskEntity {
    val now = System.currentTimeMillis()
    val partFile = downloadPartFile(destPath)
    return DownloadTaskEntity(
        id = downloadId,
        url = url,
        destPath = destPath,
        filename = filename,
        repoId = repoId,
        progressKey = progressKey,
        modelType = type.name,
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
        stageOnly = stageOnly,
        status = status,
        bytesDownloaded = partFile.length().coerceAtLeast(0L),
        totalBytes = null,
        createdAt = now,
        updatedAt = now
    )
}

fun DownloadTaskEntity.toPendingDownload(): PendingDownload {
    val type = runCatching { ModelType.valueOf(modelType) }.getOrDefault(ModelType.LLM)
    return PendingDownload(
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
        stageOnly = stageOnly
    )
}

fun downloadPartFile(destPath: String): File {
    val destFile = File(destPath)
    return File(destFile.parentFile ?: File("."), "${destFile.name}.part")
}

fun DownloadTaskEntity.partFile(): File = downloadPartFile(destPath)

fun DownloadTaskEntity.hasPartialArtifact(): Boolean = partFile().isFile && partFile().length() > 0L

fun DownloadTaskEntity.visibleStatus(): String =
    if (status == DOWNLOAD_TASK_STATUS_ACTIVE && hasPartialArtifact()) status else status

object DownloadTaskArtifacts {
    fun discoverStalePartFiles(
        context: Context,
        modelTypes: List<ModelType>,
        knownTasks: List<DownloadTaskEntity>,
        installedModels: List<ModelEntity>,
        rootsOverride: List<File>? = null
    ): List<DownloadTaskEntity> {
        val knownPartPaths = knownTasks.map { it.partFile().absolutePath }.toSet()
        val installedPaths = installedModels.map { File(it.path).absolutePath }.toSet()
        val roots = (rootsOverride ?: modelTypes.flatMap { rootsForType(context, it) })
            .distinctBy { it.absolutePath }
        val now = System.currentTimeMillis()
        return roots
            .filter { it.exists() && it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .maxDepth(3)
                    .filter { it.isFile && it.name.endsWith(".part") }
                    .mapNotNull { part ->
                        if (part.absolutePath in knownPartPaths) return@mapNotNull null
                        val dest = File(part.parentFile, part.name.removeSuffix(".part"))
                        if (dest.absolutePath in installedPaths) return@mapNotNull null
                        val type = modelTypes.firstOrNull() ?: ModelType.LLM
                        DownloadTaskEntity(
                            id = "stale:${part.absolutePath}",
                            url = "",
                            destPath = dest.absolutePath,
                            filename = dest.name,
                            repoId = "",
                            progressKey = "stale:${part.absolutePath}",
                            modelType = type.name,
                            status = DOWNLOAD_TASK_STATUS_STALE,
                            bytesDownloaded = part.length(),
                            createdAt = part.lastModified().takeIf { it > 0L } ?: now,
                            updatedAt = part.lastModified().takeIf { it > 0L } ?: now
                        )
                    }
                    .toList()
            }
            .sortedByDescending { it.updatedAt }
    }

    fun deletePartialArtifact(task: DownloadTaskEntity): Boolean =
        task.partFile().takeIf { it.exists() }?.delete() ?: true

    fun deleteIncompleteArtifacts(task: DownloadTaskEntity) {
        deletePartialArtifact(task)
        val destFile = File(task.destPath)
        if (destFile.exists() && task.status != DOWNLOAD_TASK_STATUS_DISCARDED) {
            destFile.delete()
        }
    }

    private fun rootsForType(context: Context, type: ModelType): List<File> {
        if (type in setOf(
                ModelType.ONNX_IMAGE_GEN,
                ModelType.ONNX_BACKGROUND_REMOVAL,
                ModelType.ONNX_IMAGE_UPSCALER,
                ModelType.ONNX_TTS
            )
        ) {
            return listOf(
                OnnxStorage.managedModelsRoot(context),
                OnnxStorage.tempDownloadDir(context)
            )
        }
        if (type == ModelType.LLM && context.noBackupFilesDir != null) {
            val liteRtRoot = File(context.noBackupFilesDir, "litert_models")
            return listOf(modelDirForType(context, type), liteRtRoot)
        }
        return listOf(modelDirForType(context, type))
    }

    private fun modelDirForType(context: Context, type: ModelType): File {
        val subfolder = ModelLibraryManager.relativeDirFor(type)
        context.getExternalFilesDir(null)?.let { externalDir ->
            val external = File(externalDir, "models/$subfolder")
            if (external.exists()) return external
        }
        return File(context.filesDir, if (type == ModelType.WHISPER) "whisper_models" else "models")
    }
}
