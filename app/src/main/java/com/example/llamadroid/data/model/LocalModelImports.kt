package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.library.ModelClassificationPolicy
import com.example.llamadroid.data.model.library.ModelClassificationSource
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.service.smokeCheckSdADetailerDetector
import java.io.File

/**
 * Shared, non-UI model import backends. The advanced model screens and the Easy mode importer
 * (`ui/easy/EasyModelImporter.kt`) call these same functions so import semantics never diverge.
 */

/** Thrown when an ADetailer detector fails its smoke check during import. */
class SdAdetailerImportException : Exception()

// Helper function to import SD model with progress
suspend fun importSDModel(
    context: android.content.Context,
    repository: ModelRepository,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    capabilities: String?,
    sdFamily: String? = null,
    sdVariant: String? = null,
    sdCompatProfiles: String? = null,
    onProgress: (Float) -> Unit = {}
) : Result<ModelEntity> {
    var targetFile: File? = null
    var previousFile: File? = null
    var replacementStarted = false
    var databaseCommitted = false
    return try {
        val requestedFilename = filename.ifBlank { "imported_sd_model.safetensors" }
        val targetFilename = ModelLibraryManager.canonicalFilename(requestedFilename)
        val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            it.length
        } ?: 0L
        val runtimeDir = repository.getModelDir(type).apply { mkdirs() }
        val finalTargetFile = File(runtimeDir, targetFilename)
        targetFile = finalTargetFile
        val tempFile = File(runtimeDir, "$targetFilename.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                if (fileSize > 0) {
                    val buffer = ByteArray(8192)
                    var bytesCopied = 0L
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        onProgress((bytesCopied.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f))
                        bytes = input.read(buffer)
                    }
                } else {
                    input.copyTo(output)
                }
            }
        } ?: error("Unable to open selected SD model")
        // Inspect the completed temporary file before the final rename.  This
        // is the trust boundary for local imports: malformed payloads and
        // high-confidence role/family contradictions never become model rows.
        onProgress(0.98f)
        val inspection: SdArtifactInspection? = when (type) {
            ModelType.SD_ADETAILER -> {
                if (!smokeCheckSdADetailerDetector(tempFile)) {
                    throw SdAdetailerImportException()
                }
                null
            }
            // These established SD assets are not SafeTensors/GGUF pipeline
            // artifacts. Keep .pth/.pt opaque and leave validation to their
            // consuming runtime instead of attempting to parse or unpickle.
            ModelType.SD_UPSCALER,
            ModelType.SD_TEXTUAL_INVERSION -> null
            else -> repository.inspectSdArtifact(
                file = tempFile,
                configuredType = type,
                configuredFamily = sdFamily,
                force = true
            )
        }
        val finalPath = finalTargetFile.absolutePath
        val existing = AppDatabase.getDatabase(context).modelDao().getModelByFilename(targetFilename)
        val modelEntity = ModelEntity(
            filename = targetFilename,
            path = finalPath,
            sizeBytes = tempFile.length(),
            type = type,
            repoId = "custom-import/$targetFilename",
            isDownloaded = false,
            sdCapabilities = capabilities,
            sdFamily = sdFamily,
            sdVariant = sdVariant,
            sdCompatProfiles = sdCompatProfiles,
            classificationSource = ModelClassificationSource.USER_OVERRIDE.storedValue,
            detectedClassificationJson = inspection?.toJson()
                ?.let(ModelClassificationPolicy::boundedEvidence)
        ).let { entity ->
            inspection?.let { entity.withSdArtifactInspection(it) } ?: entity
        }

        // Keep an existing file recoverable if the database write fails.  The
        // temporary source remains in place until the replacement is complete.
        if (finalTargetFile.exists()) {
            val stagedPrevious = File(runtimeDir, ".$targetFilename.previous-${System.currentTimeMillis()}")
            previousFile = stagedPrevious
            check(finalTargetFile.renameTo(stagedPrevious)) { "Unable to stage the existing model file" }
        }
        replacementStarted = true
        replaceImportedSdFile(tempFile, finalTargetFile)
        repository.insertModel(modelEntity)
        databaseCommitted = true
        if (existing != null && existing.path != finalPath) {
            runCatching { repository.deleteModelArtifacts(existing) }
                .onFailure { error ->
                    com.example.llamadroid.util.DebugLog.log(
                        "[SD-IMPORT] Existing artifact cleanup deferred: ${error.message}"
                    )
                }
        }
        previousFile?.delete()
        onProgress(1f)
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Imported: $targetFilename as ${type.name}")
        Result.success(modelEntity)
    } catch (e: Exception) {
        // Do not delete the temporary artifact.  A failed inspection/import is
        // recoverable and can be retried without selecting the source again.
        if (replacementStarted && !databaseCommitted) {
            previousFile?.takeIf { it.exists() }?.let { backup ->
                targetFile?.let { target ->
                    if (target.exists()) {
                        val failedCopy = File(
                            target.parentFile,
                            "${target.name}.failed-${System.currentTimeMillis()}"
                        )
                        target.renameTo(failedCopy)
                    }
                    backup.renameTo(target)
                }
            }
        }
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Failed: ${e.message}")
        Result.failure(e)
    }
}

private fun replaceImportedSdFile(tempFile: File, targetFile: File) {
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
