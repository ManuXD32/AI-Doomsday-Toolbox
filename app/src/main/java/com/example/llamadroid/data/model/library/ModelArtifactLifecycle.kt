package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import java.io.File

/**
 * Pure lifecycle rules shared by the runtime model repositories.
 *
 * A promoted model row is only the primary entry for a multipart or directory
 * artifact.  Companion paths live in provenance and pending rows, so deleting
 * or renaming the primary file must first account for those paths.  The helper
 * deliberately has no Room or Android dependency; repositories provide the
 * rows and the set of paths that belong to their managed storage roots.
 */
internal object ModelArtifactLifecycle {
    private val splitPartPattern = Regex(
        "(?i).*(?:[-_.])\\d{5}-of-\\d{5}(?:\\.[^/]*)?$"
    )

    fun isGroupedArtifact(
        modelPath: String,
        modelKey: String,
        pendingArtifacts: Collection<PendingModelArtifactEntity>,
        provenance: Collection<ModelProvenanceEntity>
    ): Boolean {
        val path = modelPath.trim()
        if (path.isBlank()) return false
        val file = File(path)
        if (file.isDirectory || path.endsWith(File.separator)) return true
        if (splitPartPattern.matches(file.name)) return true

        val associatedPending = pendingArtifacts.filter { it.promotedModelKey == modelKey }
        val associatedProvenancePaths = provenance
            .filter { it.modelKey == modelKey }
            .mapNotNull { it.localPath?.let(::canonicalPath) }
            .toSet()

        // Multiple promoted rows or paths are the durable evidence for a
        // directory/split group, including imported groups whose primary file
        // name does not follow a shard convention.
        return associatedPending.size > 1 ||
            associatedProvenancePaths.size > 1
    }

    /**
     * A removed runtime row must not leave PROMOTED rows pointing at its old
     * model key.  CANCELLED is intentional: startup recovery ignores it and a
     * later user-triggered bundle download reopens it as STAGED.
     */
    fun detachPromotedPendingArtifacts(
        artifacts: Collection<PendingModelArtifactEntity>,
        modelKey: String,
        now: Long
    ): List<PendingModelArtifactEntity> = artifacts.map { artifact ->
        if (artifact.promotedModelKey != modelKey) {
            artifact
        } else {
            artifact.copy(
                status = PendingArtifactStatus.CANCELLED.storedValue,
                validationMessage = null,
                promotedModelKey = null,
                promotedAt = null,
                updatedAt = now
            )
        }
    }

    /**
     * Re-key a standalone artifact without rewriting companion paths.  Grouped
     * artifacts are rejected before this function is called.
     */
    fun rekeyPendingArtifact(
        artifact: PendingModelArtifactEntity,
        oldModelKey: String,
        newModelKey: String,
        oldPath: String,
        newPath: String,
        now: Long
    ): PendingModelArtifactEntity {
        if (artifact.promotedModelKey != oldModelKey) return artifact
        return artifact.copy(
            stagingPath = artifact.stagingPath.replaceExactPath(oldPath, newPath),
            destinationPath = artifact.destinationPath?.replaceExactPath(oldPath, newPath),
            promotedModelKey = newModelKey,
            updatedAt = now
        )
    }

    /**
     * Delete candidate files while preserving any path referenced by another
     * runtime row.  If a candidate is a directory, only unprotected children
     * are removed; this prevents one shared package directory from being
     * deleted when one of its entries is removed.
     */
    fun deleteOwnedPaths(
        candidates: Collection<File>,
        protectedPaths: Collection<String>,
        deleteRecursively: (File) -> Unit = { it.deleteRecursively() }
    ): List<String> {
        val protected = protectedPaths.mapNotNull(::canonicalPath).toSet()
        val roots = candidates
            .mapNotNull { file -> canonicalPath(file.path)?.let(::File) }
            .distinctBy { it.path }
            .sortedBy { it.path.length }
        val deleted = linkedSetOf<String>()

        fun isWithin(root: String, child: String): Boolean =
            child == root || child.startsWith("$root${File.separator}")

        fun hasProtectedDescendant(root: String): Boolean =
            protected.any { isWithin(root, it) && it != root }

        fun removeTree(file: File) {
            val canonical = canonicalPath(file.path) ?: return
            if (protected.any { isWithin(it, canonical) }) return
            if (file.isDirectory && hasProtectedDescendant(canonical)) {
                file.listFiles()?.forEach(::removeTree)
                // An empty unprotected directory can be removed after its
                // protected descendants have been retained.
                if (file.listFiles().isNullOrEmpty() && file.delete()) deleted += canonical
            } else if (file.exists()) {
                if (file.isDirectory) deleteRecursively(file) else file.delete()
                if (!file.exists()) deleted += canonical
            }
        }

        roots.forEach(::removeTree)
        return deleted.toList()
    }

    private fun String.replaceExactPath(oldPath: String, newPath: String): String =
        if (canonicalPath(this) == canonicalPath(oldPath)) newPath else this

    private fun canonicalPath(path: String?): String? = path
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { File(it).canonicalPath }.getOrNull() }
}
