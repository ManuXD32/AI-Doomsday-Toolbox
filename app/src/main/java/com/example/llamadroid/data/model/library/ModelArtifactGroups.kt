package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelBundleItemEntity
import com.example.llamadroid.data.db.ModelLibraryDao
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.db.ModelProvenanceEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** A draft may omit members; duplicate paths/indices would make downloading destructive. */
internal fun validateBundleLayout(items: List<ModelBundleItemEntity>) {
    val paths = items.map { item ->
        val path = item.relativePath ?: item.localFilename ?: item.itemKey
        require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path &&
            path.split('/').none { it == ".." || it.isBlank() || it == "." }) { "Invalid bundle file path" }
        path
    }
    require(paths.distinct().size == paths.size) { "Bundle file paths must be unique" }
    require(paths.none { path -> paths.any { it != path && it.startsWith("$path/") } }) {
        "A bundle file cannot also be a parent folder"
    }
    items.filter { it.partGroup != null }.groupBy { it.partGroup }.values.forEach { group ->
        require(group.map { it.partIndex }.distinct().size == group.size) { "Duplicate multipart index" }
        require(group.map { it.partCount }.distinct().size == 1) { "Inconsistent multipart count" }
    }
}

internal fun incompleteBundleGroupIds(items: List<ModelBundleItemEntity>): Set<String> =
    items.filter { it.partGroup != null }.groupBy { it.partGroup }.values.filter { group ->
        val count = group.first().partCount ?: 0
        count <= 0 || group.size != count || group.mapNotNull { it.partIndex }.toSet() != (0 until count).toSet()
    }.flatten().map { it.id }.toSet()

internal data class PendingArtifactGroup(
    val entry: File,
    val primary: PendingModelArtifactEntity,
    val members: List<PendingModelArtifactEntity>,
    val complete: Boolean
)

/** Resolve a directory or split GGUF as one runtime unit, retaining every selected relative path. */
internal suspend fun resolvePendingArtifactGroup(
    dao: ModelLibraryDao,
    artifact: PendingModelArtifactEntity,
    file: File
): PendingArtifactGroup {
    val item = artifact.bundleItemId?.let { dao.getBundleItemById(it) }
    if (item?.partGroup == null || artifact.bundleId == null) {
        val split = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE).matchEntire(file.name)
        if (split == null) return PendingArtifactGroup(file, artifact, listOf(artifact), true)
        val count = split.groupValues[3].toIntOrNull() ?: 0
        val parts = if (count in 1..1024) (1..count).map { index ->
            File(file.parentFile, "${split.groupValues[1]}-${index.toString().padStart(5, '0')}-of-${split.groupValues[3]}.gguf")
        } else emptyList()
        // Filename shape is a safety gate only; it is never classification evidence.
        return PendingArtifactGroup(parts.firstOrNull() ?: file, artifact, listOf(artifact),
            split.groupValues[2] == "00001" && parts.isNotEmpty() && parts.all {
                it.isFile && it.length() > 0 && ModelArtifactRecognizer.inspect(it).isStructurallyValid
            })
    }
    val allItems = dao.getWithItems(artifact.bundleId)?.items.orEmpty()
    val group = allItems.filter { it.partGroup == item.partGroup }.sortedBy { it.partIndex }
    if (group.isEmpty() || incompleteBundleGroupIds(group).isNotEmpty())
        return PendingArtifactGroup(file, artifact, listOf(artifact), false)
    val relative = item.relativePath ?: item.localFilename ?: item.itemKey
    var root = File(artifact.destinationPath ?: file.path).canonicalFile
    repeat(relative.split('/').size) { root = root.parentFile ?: root }
    val paths = group.map { File(root, it.relativePath ?: it.localFilename ?: it.itemKey).canonicalFile }
    val rows = dao.getPendingArtifactsForBundle(artifact.bundleId).filter { row -> group.any { it.id == row.bundleItemId } }
    val primary = rows.firstOrNull { it.bundleItemId == group.first().id } ?: artifact
    val complete = paths.zip(group).all { (path, definition) ->
        val row = rows.firstOrNull { it.bundleItemId == definition.id }
        row != null && row.status in materializedArtifactStatuses && path.isFile && path.length() > 0 &&
            runCatching { verifyPendingArtifactEvidence(dao, row, path) }.isSuccess
    }
    val entry = if (ModelFamily.fromStoredValue(item.family) in setOf(ModelFamily.ONNX, ModelFamily.LITERT)) {
        var parent = paths.first().parentFile ?: root
        while (paths.any { !it.toPath().startsWith(parent.toPath()) }) parent = parent.parentFile ?: root
        parent
    } else paths.first()
    return PendingArtifactGroup(entry, primary, rows, complete)
}

private val materializedArtifactStatuses = setOf(PendingArtifactStatus.INSPECTING.storedValue,
    PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue, PendingArtifactStatus.VALIDATED.storedValue,
    PendingArtifactStatus.PROMOTED.storedValue)

/** Call inside the same Room transaction as each state or runtime registration write. */
internal suspend fun ensurePendingArtifactActive(dao: ModelLibraryDao, id: String): PendingModelArtifactEntity {
    val current = dao.getPendingArtifactById(id)
    if (current == null || current.status == PendingArtifactStatus.CANCELLED.storedValue)
        throw CancellationException("Artifact download was cancelled")
    return current
}

/** Companion records refer to the primary runtime row; they never become duplicate models. */
internal suspend fun markGroupInstalled(dao: ModelLibraryDao, group: PendingArtifactGroup, reference: ModelArtifactReference) {
    group.members.forEach { member ->
        val current = ensurePendingArtifactActive(dao, member.id)
        val file = File(member.destinationPath ?: member.stagingPath)
        dao.upsertActiveArtifact(current.copy(status = PendingArtifactStatus.PROMOTED.storedValue,
            requiresManualPromotion = false, promotedModelKey = reference.modelKey,
            promotedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        member.sourceId?.let { sourceId ->
            dao.upsert(ModelProvenanceEntity(id = "pending:${member.id}", sourceId = sourceId,
                modelKey = requireNotNull(reference.modelKey), family = reference.family.storedValue,
                role = member.requestedRole, localPath = file.path, sizeBytes = file.length(),
                artifactSha256 = artifactFileSha256(file),
                importedAt = member.createdAt, updatedAt = System.currentTimeMillis()))
        }
    }
}

internal suspend fun verifyPendingArtifactEvidence(dao: ModelLibraryDao, artifact: PendingModelArtifactEntity, file: File) {
    val item = artifact.bundleItemId?.let { dao.getBundleItemById(it) }
    val source = artifact.sourceId?.let { dao.getSourceById(it) }
    verifyArtifactFile(file, item?.expectedSizeBytes ?: source?.expectedSizeBytes,
        item?.expectedSha256 ?: source?.expectedSha256)
}

/** Hash checking is streamed by the IO download/finalization worker, never during composition. */
internal fun verifyArtifactFile(file: File, size: Long?, sha256: String?) {
    if (file.isDirectory) return // Directory entries are checked individually as their downloads complete.
    if (!file.isFile || size != null && file.length() != size) throw ModelLibraryException(
        ModelLibraryErrorCode.DOWNLOAD_FAILED, "Downloaded file size does not match the saved source")
    if (sha256 == null) return
    if (!sha256.matches(Regex("[a-fA-F0-9]{64}"))) throw ModelLibraryException(
        ModelLibraryErrorCode.BUNDLE_INVALID, "Invalid saved SHA-256 checksum")
    val actual = artifactFileSha256(file)
    if (!actual.equals(sha256, ignoreCase = true)) throw ModelLibraryException(
        ModelLibraryErrorCode.DOWNLOAD_FAILED, "Downloaded file checksum does not match the saved source")
}

internal fun artifactFileSha256(file: File): String? {
    if (!file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
