package com.example.llamadroid.data.model.library

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.sd.SdArtifactInspection
import com.example.llamadroid.sd.withSdArtifactInspection
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.db.isAudioTtsComponentType
import com.example.llamadroid.data.db.isStableAudioComponentType
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.data.model.AudioModelSupport
import com.example.llamadroid.data.model.PendingDownload
import com.example.llamadroid.data.model.PortableModelMetadata
import com.example.llamadroid.data.model.StableAudioModelSupport
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
    val portableModelType: String? = null,
    val audioFamily: String? = null,
    val audioComponentRole: String? = null,
    val audioLanguage: String? = null,
    val audioArtifactIdentity: String? = null,
    /** Stable Audio components use appended model types and shared metadata. */
    val stableAudioFamily: String? = null,
    val stableAudioComponentRole: String? = null,
    val stableAudioVersion: String? = null
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
                liteRtMaxContextTokens = pending.liteRtMaxContextTokens,
                audioFamily = pending.artifactFamily.takeIf {
                    pending.type.isAudioTtsComponentType()
                },
                audioComponentRole = pending.artifactRole.takeIf {
                    pending.type.isAudioTtsComponentType()
                },
                stableAudioFamily = pending.artifactFamily.takeIf {
                    pending.type.isStableAudioComponentType() && StableAudioModelSupport.isFamily(it)
                },
                stableAudioComponentRole = pending.artifactRole.takeIf {
                    pending.type.isStableAudioComponentType() && StableAudioModelSupport.isComponentRole(it)
                }
            )
    }
}

data class PendingArtifactFinalization(
    val promoted: Boolean,
    val reference: ModelArtifactReference? = null,
    val recognition: ArtifactRecognitionResult
)

/**
 * Associates the exact companion installed by one curated bundle with each
 * native TTS main row from that same bundle.  The bundle edge is the identity
 * boundary: a similarly named companion from another bundle is never guessed.
 * This is called whenever either member is promoted, so download completion
 * order does not affect the persisted mmprojPath edge.
 */
internal suspend fun associateAudioCompanionForBundle(
    database: AppDatabase,
    dao: com.example.llamadroid.data.db.ModelLibraryDao,
    bundleId: String?
) {
    val id = bundleId?.trim()?.takeIf { it.isNotBlank() } ?: return
    val artifacts = dao.getPendingArtifactsForBundle(id)
        .filter { it.status == PendingArtifactStatus.PROMOTED.storedValue }
    val promoted = artifacts.mapNotNull { artifact ->
        val key = artifact.promotedModelKey?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        database.modelDao().getModelByFilename(key)?.let { artifact to it }
    }
    val mains = promoted.filter { (_, model) -> model.type == ModelType.LLAMA_TTS }
    val companions = promoted.filter { (_, model) -> model.type == ModelType.LLAMA_TTS_COMPANION }
    // A bundle with multiple companions is intentionally left unresolved. The
    // caller must select one explicitly rather than silently binding the first.
    val companion = companions.singleOrNull()?.second ?: return
    mains.forEach { (_, main) ->
        database.modelDao().updateAudioCompanionPath(main.path, companion.path)
    }
}

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
        metadata: PendingArtifactRuntimeMetadata,
        context: Context? = null
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
            return@runCatching finalizePrepared(
                database = database,
                artifact = previous,
                downloadedFile = installed,
                metadata = metadata,
                destinationOverride = installed,
                context = context
            ).getOrThrow()
        }
        verifyPendingArtifactEvidence(dao, artifact, downloadedFile)
        val definition = artifact.bundleItemId?.let { dao.getBundleItemById(it) }
        if (definition?.partGroup == null) {
            val group = resolvePendingArtifactGroup(dao, artifact, downloadedFile)
            if (!group.complete) return@runCatching keepGroupPending(dao, artifact)
            return@runCatching finalizePrepared(
                database = database,
                artifact = artifact,
                downloadedFile = group.entry,
                metadata = metadata,
                context = context
            ).getOrThrow()
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
        val result = finalizePrepared(
            database = database,
            artifact = group.primary,
            downloadedFile = group.entry,
            metadata = metadata,
            destinationOverride = group.entry,
            requiredArtifactIds = group.members.map { it.id },
            context = context
        ).getOrThrow()
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
        requiredArtifactIds: List<String> = listOf(artifact.id),
        context: Context? = null
    ): Result<PendingArtifactFinalization> = runCatching {
        val dao = database.modelLibraryDao()
        val persisted = ensurePendingArtifactActive(dao, artifact.id)
        if (persisted.status == PendingArtifactStatus.PROMOTED.storedValue && downloadedFile.exists()) {
            val existing = database.modelDao().getModelByPath(downloadedFile.absolutePath)
            val liteRt = database.liteRtModelDao().getByPath(downloadedFile.absolutePath)
            val family = ModelFamily.fromStoredValue(persisted.requestedFamily ?: persisted.detectedFamily)
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
            ?: effectiveMetadata.stableAudioComponentRole
                ?.takeIf(::isStableAudioComponentRole)
        val stableAudioRole = StableAudioModelSupport.canonicalRole(
            effectiveMetadata.stableAudioComponentRole ?: requestedRole
        )
        val observedRecognition = ModelArtifactRecognizer.inspect(downloadedFile)
        val detectedEvidence = artifact.detectedClassificationJson
            ?: observedRecognition.classificationEvidenceJson()
        val classificationSource = effectiveClassificationSource(artifact)
        val selectionConfirmed = classificationSource in setOf(
            ModelClassificationSource.CATALOG.storedValue,
            ModelClassificationSource.USER_OVERRIDE.storedValue
        )
        // An explicit LLM bundle role is part of the structural contract. This
        // matters for adapters, embeddings, drafts, and vision projectors:
        // the generic inspector may correctly see their container as an SD
        // component or a base LLM, while the selected role determines the
        // existing runtime ModelType after a second validation pass.
        val roleValidatedRecognition = if (
            selectionConfirmed && requestedFamily != null && !requestedRole.isNullOrBlank()
        ) {
            ModelArtifactRecognizer.validateForPromotion(
                downloadedFile,
                requestedFamily,
                requestedRole,
                allowClassificationMismatch = true
            )
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
                    detectedFamily = observedRecognition.family?.storedValue ?: artifact.detectedFamily,
                    detectedRole = observedRecognition.role ?: artifact.detectedRole,
                    detectedType = observedRecognition.detectedType ?: artifact.detectedType,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence,
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
        val detectedType = recognition.detectedType?.let { runCatching { ModelType.valueOf(it) }.getOrNull() }
            ?: return@runCatching PendingArtifactFinalization(promoted = false, recognition = recognition)
        val runtimeFamily = if (selectionConfirmed) requestedFamily ?: family else family
        val observedFamily = observedRecognition.family
        val observedType = observedRecognition.detectedType
            ?.let { runCatching { ModelType.valueOf(it) }.getOrNull() }
        val familyCompatible = requestedFamily == null || observedFamily == null ||
            requestedFamily == observedFamily ||
            isCompatibleSourceFamily(observedFamily, requestedFamily, requestedRole)
        val requestedType = effectiveMetadata.portableModelType
            ?.let { runCatching { ModelType.valueOf(it) }.getOrNull() }
            ?: requestedRole?.let { ModelSourceRepository.runtimeModelTypeFor(runtimeFamily, it) }
        val roleCompatible = requestedType == null || observedType == null || requestedType == observedType ||
            (runtimeFamily == ModelFamily.LITERT && stableAudioRole != null &&
                observedType == ModelType.LLM) ||
            runtimeFamily == ModelFamily.LLM && observedType == ModelType.LLM &&
            normalizedModelLibraryRole(requestedRole) in setOf("embedding", "embeddings", "draft", "llm_draft")
        val semanticMismatch = !familyCompatible || !roleCompatible
        // A confirmed catalog/manual selection is authoritative. Keep the
        // disagreement as a warning, while automatic classification remains
        // conservative and stays pending for explicit confirmation.
        val effectiveRecognition = if (semanticMismatch && selectionConfirmed) {
            recognition.copy(
                family = runtimeFamily,
                detectedType = requestedType?.name ?: detectedType.name,
                role = requestedRole ?: recognition.role,
                requiresManualPromotion = false,
                validationMessage = ModelClassificationPolicy.warningFor(
                    detectedFamily = observedRecognition.family?.storedValue,
                    selectedFamily = runtimeFamily.storedValue,
                    detectedType = observedRecognition.detectedType,
                    selectedType = requestedType?.name,
                    detectedRole = observedRecognition.role,
                    selectedRole = requestedRole
                ) ?: "Selected classification differs from detected artifact evidence",
                errorCode = null
            )
        } else {
            recognition
        }
        if (semanticMismatch && !selectionConfirmed) {
            val mismatch = recognition.copy(
                family = runtimeFamily,
                detectedType = requestedType?.name ?: detectedType.name,
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
                    detectedFamily = observedRecognition.family?.storedValue ?: artifact.detectedFamily,
                    detectedRole = observedRecognition.role ?: artifact.detectedRole,
                    detectedType = observedRecognition.detectedType ?: artifact.detectedType,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence,
                    status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
                    validationJson = recognition.validationJson,
                    validationMessage = mismatch.validationMessage,
                    requiresManualPromotion = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@runCatching PendingArtifactFinalization(promoted = false, recognition = mismatch)
        }

        val type = effectiveRecognition.detectedType
            ?.let { runCatching { ModelType.valueOf(it) }.getOrNull() }
            ?: return@runCatching PendingArtifactFinalization(promoted = false, recognition = effectiveRecognition)
        val selectedRole = requestedRole ?: effectiveRecognition.role

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
        val inspectedAudio = if (type.isAudioTtsComponentType()) {
            SdArtifactInspection.fromJson(effectiveRecognition.validationJson)
                ?.let { AudioModelSupport.recognize(it, destination.name) }
        } else {
            null
        }
        val audioDescriptor = AudioModelSupport.descriptorForPayload(
            type = type,
            digest = installedHash,
            repoId = effectiveMetadata.repoId,
            filename = destination.name,
            familyHint = inspectedAudio?.family ?: effectiveMetadata.audioFamily,
            roleHint = inspectedAudio?.role ?: effectiveMetadata.audioComponentRole ?: selectedRole,
            sourceUrl = sourceEntity?.url,
            context = context
        )
        // A portable identity is advisory metadata only. The durable marker
        // must be the digest of the bytes that were actually installed, after
        // curated verification has completed in the download worker.
        val audioIdentity = if (type.isAudioTtsComponentType()) {
            installedHash?.let { "sha256:$it" }
        } else {
            null
        }
        val stableAudioType = StableAudioModelSupport.typeForRole(stableAudioRole)
        val stableAudioComponent = runtimeFamily == ModelFamily.LITERT && stableAudioType != null
        val stableAudioFamily = effectiveMetadata.stableAudioFamily
            ?.takeIf { StableAudioModelSupport.isFamily(it) }
            ?: StableAudioModelSupport.FAMILY_SHARED
        val stableAudioIdentity = if (stableAudioComponent) {
            installedHash?.let { "sha256:$it" }
        } else {
            null
        }
        val result = database.withTransaction {
        requiredArtifactIds.forEach { ensurePendingArtifactActive(dao, it) }
        val inspection = SdArtifactInspection.fromJson(effectiveRecognition.validationJson)
        val registeredKey = if (stableAudioComponent) {
            val filename = availableModelRecordKey(database, destination, artifact.id)
            database.modelDao().insertModel(
                ModelEntity(
                    filename = filename,
                    path = destination.absolutePath,
                    sizeBytes = installedSize,
                    type = stableAudioType!!,
                    repoId = sourceEntity?.repositoryId ?: effectiveMetadata.repoId,
                    isDownloaded = true,
                    audioFamily = stableAudioFamily,
                    audioComponentRole = StableAudioModelSupport.canonicalRole(stableAudioRole),
                    audioArtifactIdentity = stableAudioIdentity,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence
                )
            )
            filename
        } else if (runtimeFamily == ModelFamily.LITERT) {
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
                    maxContextTokens = effectiveMetadata.liteRtMaxContextTokens,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence
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
                isVision = effectiveMetadata.isVision || effectiveRecognition.role?.contains("vision", ignoreCase = true) == true,
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
                onnxReferencePath = effectiveMetadata.onnxReferencePath,
                audioFamily = audioDescriptor?.family ?: effectiveMetadata.audioFamily,
                    audioLanguage = audioDescriptor?.language ?: inspectedAudio?.language ?: effectiveMetadata.audioLanguage,
                    audioComponentRole = audioDescriptor?.role ?: effectiveMetadata.audioComponentRole,
                    audioArtifactIdentity = audioIdentity,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence
            )
            database.modelDao().insertModel(inspection?.let(model::withSdArtifactInspection) ?: model)
            filename
        }
        dao.upsert(
            artifact.copy(
                stagingPath = destination.absolutePath,
                detectedFamily = observedRecognition.family?.storedValue ?: artifact.detectedFamily,
                detectedRole = observedRecognition.role ?: artifact.detectedRole,
                detectedType = observedRecognition.detectedType ?: artifact.detectedType,
                classificationSource = classificationSource,
                detectedClassificationJson = detectedEvidence,
                status = PendingArtifactStatus.PROMOTED.storedValue,
                validationJson = effectiveRecognition.validationJson,
                validationMessage = effectiveRecognition.validationMessage,
                requiresManualPromotion = false,
                promotedModelKey = registeredKey,
                promotedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        if (runtimeFamily == ModelFamily.AUDIO) {
            associateAudioCompanionForBundle(database, dao, artifact.bundleId)
        }
        artifact.sourceId?.let { sourceId ->
            dao.upsert(
                ModelProvenanceEntity(
                    id = "pending:${artifact.id}",
                    sourceId = sourceId,
                    modelKey = registeredKey,
                    family = runtimeFamily.storedValue,
                    role = selectedRole,
                    localPath = destination.absolutePath,
                    artifactSha256 = installedHash,
                    sizeBytes = installedSize,
                    classificationSource = classificationSource,
                    detectedClassificationJson = detectedEvidence,
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
                if (runtimeFamily == ModelFamily.LITERT && !stableAudioComponent) liteRtDisplayName else registeredKey,
                registeredKey
            ),
            recognition = effectiveRecognition
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
            whisperVariant = text("whisperVariant", whisperVariant),
            audioFamily = text("audioFamily", audioFamily),
            audioComponentRole = text("audioComponentRole", audioComponentRole),
            audioLanguage = text("audioLanguage", audioLanguage),
            audioArtifactIdentity = text("audioArtifactIdentity", audioArtifactIdentity),
            stableAudioFamily = text("stableAudioFamily", stableAudioFamily),
            stableAudioComponentRole = text(
                "stableAudioComponentRole",
                text("stableAudioRole", stableAudioComponentRole)
            ),
            stableAudioVersion = text("stableAudioVersion", stableAudioVersion)
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
        if (family !in setOf(ModelFamily.LITERT, ModelFamily.WHISPER, ModelFamily.AUDIO) ||
            requestedRole.isNullOrBlank() ||
            (!hasExplicitRuntimeProfile(family, metadataJson) &&
                !(family == ModelFamily.LITERT && isStableAudioComponentRole(requestedRole)))
        ) return observed
        val validated = ModelArtifactRecognizer.validateForPromotion(
            downloadedFile,
            family,
            requestedRole,
            allowClassificationMismatch = true
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
                "maxContextTokens", "stableAudioFamily", "stableAudioRole",
                "stableAudioComponentRole", "stableAudioVersion"
            ).any(json::has)
            ModelFamily.WHISPER -> json.optString("whisperVariant", "").isNotBlank() ||
                json.optString("modelType", "").equals(ModelType.WHISPER.name, ignoreCase = true)
            ModelFamily.AUDIO -> json.optString("audioFamily", "").isNotBlank() ||
                json.optString("audioComponentRole", "").isNotBlank() ||
                json.optString("modelType", "").equals(ModelType.LLAMA_TTS.name, ignoreCase = true) ||
                json.optString("modelType", "").equals(ModelType.LLAMA_TTS_COMPANION.name, ignoreCase = true)
            else -> false
        }
    }

    /**
     * Normalizes rows written before classification provenance existed while
     * preserving an explicit catalog/manual choice made by the current flow.
     */
    private fun effectiveClassificationSource(artifact: PendingModelArtifactEntity): String {
        val stored = ModelClassificationSource.fromStoredValue(artifact.classificationSource)
        if (stored != null && stored != ModelClassificationSource.LEGACY) {
            return stored.storedValue
        }
        return when {
            artifact.bundleId != null -> ModelClassificationSource.CATALOG.storedValue
            !artifact.requestedFamily.isNullOrBlank() || !artifact.requestedRole.isNullOrBlank() ->
                ModelClassificationSource.USER_OVERRIDE.storedValue
            !artifact.detectedClassificationJson.isNullOrBlank() -> ModelClassificationSource.AUTO.storedValue
            else -> ModelClassificationSource.LEGACY.storedValue
        }
    }
}
