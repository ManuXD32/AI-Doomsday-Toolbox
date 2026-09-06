package com.example.llamadroid.data.model.library

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files

/**
 * Safety boundary for removing a staged Unknown artifact.
 *
 * Pending rows can contain paths restored from a backup or written by an older
 * queue implementation. Deletion therefore never trusts the row alone: every
 * path is canonicalized below an app-owned root, directories are refused, and
 * runtime/provenance records are checked before bytes are removed.
 */
internal object ModelArtifactDiscardPolicy {
    private val discardableStatuses = setOf(
        PendingArtifactStatus.STAGED.storedValue,
        PendingArtifactStatus.INSPECTING.storedValue,
        PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue,
        PendingArtifactStatus.VALIDATED.storedValue,
        PendingArtifactStatus.REJECTED.storedValue,
        PendingArtifactStatus.FAILED.storedValue,
        PendingArtifactStatus.CANCELLED.storedValue
    )

    fun isDiscardableStatus(status: String): Boolean = status in discardableStatuses

    /**
     * Returns exact files recorded by the pending row plus their downloader
     * `.part` companions. The returned paths are canonical and deduplicated.
     */
    fun deletionCandidates(
        context: Context,
        artifact: PendingModelArtifactEntity
    ): List<File> {
        if (artifact.status == PendingArtifactStatus.PROMOTED.storedValue ||
            artifact.promotedModelKey != null
        ) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_PROMOTED,
                "Promoted model files cannot be discarded from the Unknown library"
            )
        }
        if (!isDiscardableStatus(artifact.status)) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_FAILED,
                "The pending artifact is not in a discardable state"
            )
        }
        val roots = managedRoots(context)
        val recordedPaths = listOfNotNull(
            artifact.stagingPath.trim().takeIf { it.isNotEmpty() },
            artifact.destinationPath?.trim()?.takeIf { it.isNotEmpty() }
        ).distinct()
        if (recordedPaths.isEmpty()) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                "The pending artifact has no managed file path"
            )
        }
        return recordedPaths.flatMap { rawPath ->
            val canonical = canonicalManagedFile(rawPath, roots)
                ?: throw ModelLibraryException(
                    ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                    "The pending artifact path is outside managed model storage"
                )
            if (canonical.exists() && canonical.isDirectory) {
                throw ModelLibraryException(
                    ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                    "Directory artifacts must be removed through their bundle definition"
                )
            }
            buildList {
                add(canonical)
                if (!canonical.name.endsWith(".part", ignoreCase = true)) {
                    val partPath = File(canonical.parentFile ?: canonical, "${canonical.name}.part")
                    add(
                        canonicalManagedFile(partPath.path, roots)
                            ?: throw ModelLibraryException(
                                ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                                "The pending partial path is outside managed model storage"
                            )
                    )
                }
            }
        }.distinctBy { it.absolutePath }
    }

    /**
     * Refuses a deletion if a candidate overlaps a runtime/provenance path or
     * another download task. This is intentionally checked by the caller
     * inside its Room transaction before it marks the pending row.
     */
    suspend fun requireNotRegistered(
        database: AppDatabase,
        candidates: List<File>,
        allowedPendingArtifactId: String? = null,
        allowedTaskId: String? = null
    ) {
        val protectedPaths = buildSet {
            database.modelDao().getAllModels().first().forEach { model ->
                canonicalPath(model.path)?.let(::add)
            }
            database.liteRtModelDao().getAllOnce().forEach { model ->
                canonicalPath(model.path)?.let { path ->
                    add(path)
                    // LiteRT installs are package directories. The runtime
                    // row points at the engine entry, while sibling manifests,
                    // tokenizers, and delegates remain part of that package.
                    path.parentFile?.let { parent ->
                        canonicalPath(parent.path)?.let(::add)
                    }
                }
            }
            database.modelLibraryDao().observeProvenance().first().forEach { provenance ->
                provenance.localPath?.let(::canonicalPath)?.let(::add)
            }
        }
        val candidatePaths = candidates.mapNotNull { canonicalPath(it.path) }
        if (candidatePaths.any { candidate ->
                protectedPaths.any { protected -> candidate.overlaps(protected) }
            }
        ) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_PROTECTED,
                "The pending path is already used by an installed model or provenance record"
            )
        }
        val occupiedByAnotherTask = database.downloadTaskDao().observeAll().first().any { task ->
            if (task.id == allowedTaskId ||
                (allowedPendingArtifactId != null && task.pendingArtifactId == allowedPendingArtifactId)
            ) {
                false
            } else {
                val taskPath = canonicalPath(task.destPath) ?: return@any false
                candidatePaths.any { candidate -> candidate.overlaps(taskPath) }
            }
        }
        if (occupiedByAnotherTask) {
            throw ModelLibraryException(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_PROTECTED,
                "The pending path is used by another download task"
            )
        }
    }

    /** Deletes only canonical regular files; never recursively removes a directory. */
    fun deleteFiles(candidates: List<File>) {
        candidates.forEach { candidate ->
            if (!candidate.exists()) return@forEach
            if (candidate.isDirectory || !candidate.delete()) {
                throw ModelLibraryException(
                    ModelLibraryErrorCode.ARTIFACT_DISCARD_FAILED,
                    "The managed pending file could not be removed"
                )
            }
        }
    }

    private fun managedRoots(context: Context): List<File> = listOfNotNull(
        context.filesDir,
        context.noBackupFilesDir,
        context.cacheDir,
        context.getExternalFilesDir(null),
        context.externalCacheDir
    ).mapNotNull { canonicalPath(it.path) }.distinctBy { it.absolutePath }

    private fun canonicalManagedFile(rawPath: String, roots: List<File>): File? {
        val rawFile = File(rawPath)
        if (Files.isSymbolicLink(rawFile.toPath())) return null
        val canonical = canonicalPath(rawPath) ?: return null
        if (roots.any { root -> canonical.isDescendantOf(root) }) return canonical
        return null
    }

    private fun canonicalPath(path: String): File? =
        runCatching { File(path).canonicalFile }.getOrNull()

    private fun File.isDescendantOf(root: File): Boolean =
        path.startsWith(root.path + File.separator) && path != root.path

    private fun File.overlaps(other: File): Boolean =
        path == other.path || isDescendantOf(other) || other.isDescendantOf(this)
}
