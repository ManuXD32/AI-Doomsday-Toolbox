package com.example.llamadroid.data.model.library

import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PortableModelMetadata
import org.json.JSONObject
import java.io.File

/** Runtime metadata needed when a staged artifact is promoted automatically. */
data class PendingArtifactRuntimeMetadata(
    val repoId: String,
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
    val liteRtDisplayName: String? = null,
    val liteRtSourceUri: String? = null,
    val liteRtBackendPreference: String? = null,
    val liteRtProfile: String? = null,
    val liteRtSupportsCpu: Boolean? = null,
    val liteRtSupportsGpu: Boolean? = null,
    val liteRtSupportsNpu: Boolean? = null,
    val liteRtSupportsVision: Boolean? = null,
    val liteRtSupportsAudio: Boolean? = null,
    val liteRtSupportsEmbedding: Boolean? = null,
    val liteRtMaxContextTokens: Int? = null,
    val whisperVariant: String? = null,
    val portableModelType: String? = null
) {
    companion object {
        fun fromPending(pending: PendingDownload): PendingArtifactRuntimeMetadata =
            PendingArtifactRuntimeMetadata(
                repoId = pending.repoId,
                isVision = pending.isVision,
                sdCapabilities = pending.sdCapabilities,
                sdFamily = pending.sdFamily,
                sdVariant = pending.sdVariant,
                sdCompatProfiles = pending.sdCompatProfiles,
                onnxCapabilities = pending.onnxCapabilities,
                onnxAssetKind = pending.onnxAssetKind,
                onnxPipelineFamily = pending.onnxPipelineFamily,
                onnxReferenceUri = pending.onnxReferenceUri,
                onnxReferencePath = pending.onnxReferencePath,
                liteRtDisplayName = pending.liteRtDisplayName,
                liteRtSourceUri = pending.liteRtSourceUri,
                liteRtBackendPreference = pending.liteRtBackendPreference,
                liteRtSupportsCpu = pending.liteRtSupportsCpu,
                liteRtSupportsGpu = pending.liteRtSupportsGpu,
                liteRtSupportsVision = pending.liteRtSupportsVision,
                liteRtSupportsAudio = pending.liteRtSupportsAudio,
                liteRtSupportsEmbedding = pending.liteRtSupportsEmbedding,
                liteRtMaxContextTokens = pending.liteRtMaxContextTokens
            )
    }
}

data class PendingArtifactFinalization(
    val promoted: Boolean,
    val reference: ModelArtifactReference? = null,
    val recognition: ArtifactRecognitionResult
)

/**
 * Finalizes only artifacts with a known structural family and runtime type.
 * This keeps arbitrary downloads out of the runtime model table while allowing
 * the durable download service to finish known GGUF/SD files without a visible
 * screen remaining open.
 */
object ModelArtifactFinalizer {
    suspend fun finalizeIfKnown(
        database: AppDatabase,
        artifact: PendingModelArtifactEntity,
        downloadedFile: File,
        metadata: PendingArtifactRuntimeMetadata
    ): Result<PendingArtifactFinalization> = runCatching {
        val dao = database.modelLibraryDao()
        ensurePendingArtifactActive(dao, artifact.id)
        val previous = dao.getPendingArtifactById(artifact.id)
        // Model rows use their filename as the runtime key, while LiteRT rows use
        // the stable "litert:<id>" key. Resolve both before touching the staging
        // path: after a successful promotion the staging payload may already have
        // been removed, but the installed runtime row is still the authoritative
        // recovery source.
        val promotedKey = previous?.promotedModelKey
        val installedPath = promotedKey?.let { key ->
            if (key.startsWith("litert:")) {
                key.removePrefix("litert:")
                    .toLongOrNull()
                    ?.let { database.liteRtModelDao().getById(it)?.path }
            } else {
                database.modelDao().getModelByFilename(key)?.path
            }
        }
        val installed = installedPath?.let(::File)
        if (previous?.status == PendingArtifactStatus.PROMOTED.storedValue && installed?.exists() == true) {
            return@runCatching finalizePrepared(database, previous, installed, metadata,
                installed).getOrThrow()
        }
        verifyPendingArtifactEvidence(dao, artifact, downloadedFile)
        val definition = artifact.bundleItemId?.let { dao.getBundleItemById(it) }
        if (definition?.partGroup == null) {
            val group = resolvePendingArtifactGroup(dao, artifact, downloadedFile)
            if (!group.complete) return@runCatching keepGroupPending(dao, artifact)
            return@runCatching finalizePrepared(database, artifact, group.entry, metadata).getOrThrow()
        }
        // Materialize all members before considering any one of them runnable. Unknown
        // tokenizer/config files are companions and remain part of this same group.
        val destination = File(artifact.destinationPath ?: downloadedFile.path).canonicalFile
        copyArtifactWithoutOverwrite(downloadedFile, destination, acceptIdentical = true)
        val staged = artifact.copy(stagingPath = destination.path,
            status = PendingArtifactStatus.INSPECTING.storedValue, updatedAt = System.currentTimeMillis())
        dao.upsertActiveArtifact(staged)
        // The pending record now owns this verified destination, so its private staging
        // copy is redundant even while the complete group awaits classification.
        if (downloadedFile.canonicalPath != destination.path) {
            if (downloadedFile.isDirectory) downloadedFile.deleteRecursively() else downloadedFile.delete()
        }
        val group = resolvePendingArtifactGroup(dao, staged, destination)
        if (!group.complete) return@runCatching keepGroupPending(dao, staged)
        val result = finalizePrepared(database, group.primary, group.entry, metadata, group.entry,
            group.members.map { it.id }).getOrThrow()
        if (result.promoted) markGroupInstalled(dao, group, requireNotNull(result.reference))
        result
    }

    private suspend fun keepGroupPending(
        dao: com.example.llamadroid.data.db.ModelLibraryDao,
        artifact: PendingModelArtifactEntity
    ): PendingArtifactFinalization {
        val recognition = ArtifactRecognitionResult(requiresManualPromotion = true,
            validationMessage = "Required multipart files are missing", errorCode = ModelLibraryErrorCode.MANUAL_PROMOTION_REQUIRED)
        dao.upsertActiveArtifact(artifact.copy(status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
            requiresManualPromotion = true, validationMessage = recognition.validationMessage,
            updatedAt = System.currentTimeMillis()))
        return PendingArtifactFinalization(false, recognition = recognition)
    }

    private suspend fun finalizePrepared(
        database: AppDatabase,
        artifact: PendingModelArtifactEntity,
        downloadedFile: File,
        metadata: PendingArtifactRuntimeMetadata,
        destinationOverride: File? = null,
        requiredArtifactIds: List<String> = listOf(artifact.id)
    ): Result<PendingArtifactFinalization> = runCatching {
        val dao = database.modelLibraryDao()
        val persisted = ensurePendingArtifactActive(dao, artifact.id)
        if (persisted?.status == PendingArtifactStatus.PROMOTED.storedValue && downloadedFile.exists()) {
            val existing = database.modelDao().getModelByPath(downloadedFile.absolutePath)
            val liteRt = database.liteRtModelDao().getByPath(downloadedFile.absolutePath)
            val family = ModelFamily.fromStoredValue(persisted.detectedFamily ?: persisted.requestedFamily)
            if (family != null && (existing != null || liteRt != null)) {
                // A manually classified row is authoritative after recovery. Reinspection must
                // never demote it or overwrite its edited runtime compatibility settings.
                return@runCatching PendingArtifactFinalization(
                    promoted = true,
                    reference = ModelArtifactReference(family, downloadedFile.absolutePath,
                        existing?.filename ?: liteRt!!.displayName, existing?.filename ?: "litert:${liteRt!!.id}"),
                    recognition = ArtifactRecognitionResult(family = family,
                        detectedType = existing?.type?.name ?: persisted.detectedType,
                        role = persisted.detectedRole, isStructurallyValid = true, requiresManualPromotion = false)
                )
            }
        }
        val bundleMetadataJson = artifact.bundleItemId
            ?.let { dao.getBundleItemById(it)?.modelMetadataJson }
        val effectiveMetadata = metadata.withPortableBundleMetadata(bundleMetadataJson)
        val requestedFamily = ModelFamily.fromStoredValue(artifact.requestedFamily)
        val requestedRole = artifact.requestedRole
        val observedRecognition = ModelArtifactRecognizer.inspect(downloadedFile)
        // An explicit LLM bundle role is part of the structural contract. This
        // matters for adapters, embeddings, drafts, and vision projectors:
        // the generic inspector may correctly see their container as an SD
        // component or a base LLM, while the selected role determines the
        // existing runtime ModelType after a second validation pass.
        val roleValidatedRecognition = if (
            artifact.bundleId != null &&
            requestedFamily == ModelFamily.LLM &&
            !requestedRole.isNullOrBlank()
        ) {
            ModelArtifactRecognizer.validateForPromotion(downloadedFile, ModelFamily.LLM, requestedRole)
        } else {
            observedRecognition
        }
        val recognition = restoreSavedRuntimeRecognition(
            downloadedFile = downloadedFile,
            observed = roleValidatedRecognition,
            requestedFamily = requestedFamily,
            requestedRole = requestedRole,
            metadataJson = bundleMetadataJson
        )
        if (!recognition.isStructurallyValid || recognition.requiresManualPromotion) {
            dao.upsertActiveArtifact(
                artifact.copy(
                    // Keep the user's selected family/role/type alongside
                    // the recognition result so manual promotion can resume
                    // with the original intent after a process restart.
                    detectedFamily = recognition.family?.storedValue ?: requestedFamily?.storedValue,
                    detectedRole = recognition.role ?: requestedRole,
                    detectedType = recognition.detectedType ?: requestedFamily?.let {
                        ModelSourceRepository.runtimeModelTypeFor(it, requestedRole).name
                    },
                    status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                    validationJson = recognition.validationJson,
                    validationMessage = recognition.validationMessage,
                    requiresManualPromotion = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@runCatching PendingArtifactFinalization(promoted = false, recognition = recognition)
        }
        val family = recognition.family
            ?: return@runCatching PendingArtifactFinalization(promoted = false, recognition = recognition)
        val type = recognition.detectedType?.let { runCatching { ModelType.valueOf(it) }.getOrNull() }
            ?: return@runCatching PendingArtifactFinalization(promoted = false, recognition = recognition)
        val runtimeFamily = if (artifact.bundleId == null) family else requestedFamily ?: family
        val familyCompatible = artifact.bundleId == null || requestedFamily == null || requestedFamily == family ||
            isCompatibleSourceFamily(family, requestedFamily, requestedRole)
        val requestedType = requestedRole?.let { ModelSourceRepository.runtimeModelTypeFor(runtimeFamily, it) }
        val roleCompatible = requestedType == null || requestedType == type ||
            runtimeFamily == ModelFamily.LLM && type == ModelType.LLM &&
            normalizedModelLibraryRole(requestedRole) in setOf("embedding", "embeddings", "draft", "llm_draft")
        // Auto-promotion already requires high structural confidence. The selected
        // bundle role must map to that observed runtime type, including video companions.
        if (!familyCompatible || !roleCompatible) {
            val mismatch = recognition.copy(
                family = runtimeFamily,
                detectedType = requestedType?.name ?: type.name,
                role = requestedRole ?: recognition.role,
                requiresManualPromotion = true,
                validationMessage = when {
                    !familyCompatible -> "Detected family does not match the requested bundle role"
                    !roleCompatible -> "Detected runtime type does not match the requested bundle role"
                    else -> "Container is valid; confirm the requested companion role before promotion"
                },
                errorCode = ModelLibraryErrorCode.RECOGNITION_FAILED
            )
            dao.upsertActiveArtifact(
                artifact.copy(
                    detectedFamily = runtimeFamily.storedValue,
                    detectedRole = requestedRole ?: recognition.role,
                    detectedType = requestedType?.name ?: type.name,
                    status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                    validationJson = recognition.validationJson,
                    validationMessage = mismatch.validationMessage,
                    requiresManualPromotion = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@runCatching PendingArtifactFinalization(promoted = false, recognition = mismatch)
        }

        val destination = destinationOverride ?: artifact.destinationPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.canonicalFile
            ?: downloadedFile.canonicalFile
        val sourceFile = downloadedFile.canonicalFile
        if (sourceFile.absolutePath != destination.absolutePath) {
            copyArtifactWithoutOverwrite(sourceFile, destination, acceptIdentical = true)
        }
        val installedSize = com.example.llamadroid.data.model.physicalFiles(destination.absolutePath).values.sum()
        val installedHash = artifactFileSha256(destination)
        val sourceEntity = artifact.sourceId?.let { dao.getSourceById(it) }
        val liteRtDisplayName = effectiveMetadata.liteRtDisplayName ?: destination.nameWithoutExtension
        val result = database.withTransaction {
        requiredArtifactIds.forEach { ensurePendingArtifactActive(dao, it) }
        val inspection = SdArtifactInspection.fromJson(recognition.validationJson)
        val registeredKey = if (runtimeFamily == ModelFamily.LITERT) {
            val previous = database.liteRtModelDao().getByPath(destination.absolutePath)
            val liteRtId = database.liteRtModelDao().insert(
                LiteRtModelEntity(
                    id = previous?.id ?: 0L,
                    displayName = liteRtDisplayName,
                    path = destination.absolutePath,
                    sourceUri = effectiveMetadata.liteRtSourceUri ?: sourceEntity?.url,
                    repoId = sourceEntity?.repositoryId ?: effectiveMetadata.repoId,
                    filename = destination.name,
                    sizeBytes = installedSize,
                    backendPreference = normalizeLiteRtBackend(
                        effectiveMetadata.liteRtBackendPreference ?: effectiveMetadata.liteRtProfile
                    ).ifBlank { LITERT_BACKEND_AUTO },
                    supportsCpu = effectiveMetadata.liteRtSupportsCpu ?: true,
                    supportsGpu = effectiveMetadata.liteRtSupportsGpu ?: true,
                    supportsNpu = effectiveMetadata.liteRtSupportsNpu ?: false,
                    supportsVision = effectiveMetadata.liteRtSupportsVision ?: false,
                    supportsAudio = effectiveMetadata.liteRtSupportsAudio ?: false,
                    supportsEmbedding = effectiveMetadata.liteRtSupportsEmbedding ?: false,
                    maxContextTokens = effectiveMetadata.liteRtMaxContextTokens
                )
            )
            "litert:$liteRtId"
        } else {
            val filename = availableModelRecordKey(database, destination, artifact.id)
            val model = ModelEntity(
                filename = filename,
                path = destination.absolutePath,
                sizeBytes = installedSize,
                type = type,
                repoId = effectiveMetadata.repoId,
                isDownloaded = true,
                isVision = effectiveMetadata.isVision || recognition.role?.contains("vision", ignoreCase = true) == true,
                sdCapabilities = effectiveMetadata.sdCapabilities ?: "vid_gen".takeIf {
                    type in setOf(ModelType.SD_DIFFUSION, ModelType.SD_CHECKPOINT) &&
                        com.example.llamadroid.sd.SdVideoFamily.fromStoredValue(inspection?.detectedFamily?.storedValue) != null
                },
                sdFamily = effectiveMetadata.sdFamily ?: inspection?.detectedFamily?.storedValue,
                sdVariant = effectiveMetadata.sdVariant,
                sdCompatProfiles = effectiveMetadata.sdCompatProfiles,
                onnxCapabilities = effectiveMetadata.onnxCapabilities,
                onnxAssetKind = effectiveMetadata.onnxAssetKind,
                onnxPipelineFamily = effectiveMetadata.onnxPipelineFamily,
                onnxReferenceUri = effectiveMetadata.onnxReferenceUri,
                onnxReferencePath = effectiveMetadata.onnxReferencePath
            )
            database.modelDao().insertModel(inspection?.let(model::withSdArtifactInspection) ?: model)
            filename
        }
        dao.upsert(
            artifact.copy(
                stagingPath = destination.absolutePath,
                detectedFamily = runtimeFamily.storedValue,
                detectedRole = requestedRole ?: recognition.role,
                detectedType = requestedType?.name ?: type.name,
                status = PendingArtifactStatus.PROMOTED.storedValue,
                validationJson = recognition.validationJson,
                validationMessage = recognition.validationMessage,
                requiresManualPromotion = false,
                promotedModelKey = registeredKey,
                promotedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        artifact.sourceId?.let { sourceId ->
            dao.upsert(
                ModelProvenanceEntity(
                    id = "pending:${artifact.id}",
                    sourceId = sourceId,
                    modelKey = registeredKey,
                    family = runtimeFamily.storedValue,
                    role = requestedRole ?: recognition.role,
                    localPath = destination.absolutePath,
                    artifactSha256 = installedHash,
                    sizeBytes = installedSize,
                    importedAt = artifact.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        PendingArtifactFinalization(
            promoted = true,
            reference = ModelArtifactReference(
                runtimeFamily,
                destination.absolutePath,
                if (runtimeFamily == ModelFamily.LITERT) liteRtDisplayName else registeredKey,
                registeredKey
            ),
            recognition = recognition
        )
        }
        // The durable runtime and provenance transaction owns the new copy before the
        // temporary staging payload is removed. A failed transaction is safely retryable.
        if (sourceFile.absolutePath != destination.absolutePath) {
            if (sourceFile.isDirectory) sourceFile.deleteRecursively() else sourceFile.delete()
        }
        result
    }

    private fun PendingArtifactRuntimeMetadata.withPortableBundleMetadata(
        raw: String?
    ): PendingArtifactRuntimeMetadata {
        val json = runCatching {
            JSONObject(PortableModelMetadata.sanitize(raw))
        }.getOrNull() ?: return this
        fun text(key: String, current: String?): String? =
            json.optString(key, "").trim().takeIf { it.isNotEmpty() } ?: current
        fun bool(key: String, current: Boolean?): Boolean? =
            if (json.has(key)) json.optBoolean(key, current ?: false) else current
        return copy(
            isVision = if (json.has("isVision")) json.optBoolean("isVision", isVision) else isVision,
            portableModelType = text("modelType", portableModelType),
            sdCapabilities = text("sdCapabilities", sdCapabilities),
            sdFamily = text("sdFamily", sdFamily),
            sdVariant = text("sdVariant", sdVariant),
            sdCompatProfiles = text("sdCompatProfiles", sdCompatProfiles),
            onnxCapabilities = text("onnxCapabilities", onnxCapabilities),
            onnxAssetKind = text("onnxAssetKind", onnxAssetKind),
            onnxPipelineFamily = text("onnxPipelineFamily", onnxPipelineFamily),
            liteRtBackendPreference = text("liteRtBackend", liteRtBackendPreference),
            liteRtProfile = text("liteRtProfile", liteRtProfile),
            liteRtSupportsCpu = bool("supportsCpu", liteRtSupportsCpu),
            liteRtSupportsGpu = bool("supportsGpu", liteRtSupportsGpu),
            liteRtSupportsNpu = bool("supportsNpu", liteRtSupportsNpu),
            liteRtSupportsVision = bool("supportsVision", liteRtSupportsVision),
            liteRtSupportsAudio = bool("supportsAudio", liteRtSupportsAudio),
            liteRtSupportsEmbedding = bool("supportsEmbedding", liteRtSupportsEmbedding),
            liteRtMaxContextTokens = if (json.has("maxContextTokens")) {
                json.optInt("maxContextTokens", liteRtMaxContextTokens ?: 0).takeIf { it > 0 }
            } else liteRtMaxContextTokens,
            whisperVariant = text("whisperVariant", whisperVariant)
        )
    }

    /**
     * Saved bundles may carry an explicit runtime profile for formats whose
     * bytes do not encode the app's runtime choice. A validated file is still
     * required; metadata only permits the dedicated row to be restored after
     * a redownload and never classifies an arbitrary payload.
     */
    private fun restoreSavedRuntimeRecognition(
        downloadedFile: File,
        observed: ArtifactRecognitionResult,
        requestedFamily: ModelFamily?,
        requestedRole: String?,
        metadataJson: String?
    ): ArtifactRecognitionResult {
        val family = requestedFamily ?: return observed
        if (family !in setOf(ModelFamily.LITERT, ModelFamily.WHISPER) ||
            requestedRole.isNullOrBlank() ||
            !hasExplicitRuntimeProfile(family, metadataJson)
        ) return observed
        val validated = ModelArtifactRecognizer.validateForPromotion(
            downloadedFile,
            family,
            requestedRole
        )
        if (!validated.isStructurallyValid || validated.family != family) return observed
        return validated.copy(
            family = family,
            detectedType = ModelSourceRepository.runtimeModelTypeFor(family, requestedRole).name,
            role = requestedRole,
            confidence = ArtifactConfidence.HIGH,
            requiresManualPromotion = false,
            validationMessage = "Validated ${family.storedValue} runtime restored from the saved bundle profile",
            errorCode = null
        )
    }

    private fun hasExplicitRuntimeProfile(family: ModelFamily, raw: String?): Boolean {
        val json = runCatching { JSONObject(PortableModelMetadata.sanitize(raw)) }.getOrNull() ?: return false
        return when (family) {
            ModelFamily.LITERT -> listOf(
                "liteRtBackend", "liteRtProfile", "supportsCpu", "supportsGpu",
                "supportsNpu", "supportsVision", "supportsAudio", "supportsEmbedding",
                "maxContextTokens"
            ).any(json::has)
            ModelFamily.WHISPER -> json.optString("whisperVariant", "").isNotBlank() ||
                json.optString("modelType", "").equals(ModelType.WHISPER.name, ignoreCase = true)
            else -> false
        }
    }
}
