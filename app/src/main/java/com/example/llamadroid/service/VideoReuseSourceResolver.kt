package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Source remapping is deliberately conservative: a matching filename is never evidence. */
suspend fun resolveVideoReuseSources(context: Context, payload: VideoReusePayload): Map<String, String> = withContext(Dispatchers.IO) {
    val db = AppDatabase.getDatabase(context)
    val provenance = db.modelLibraryDao().observeProvenance().first()
    val sources = db.modelLibraryDao().observeSources().first()
    val installed = db.modelDao().getAllModels().first().map { it.path }.toSet()
    resolveVideoReuseSourceEvidence(payload.referenceEvidence(), provenance, sources, installed)
}

internal fun resolveVideoReuseSourceEvidence(
    references: List<VideoReuseReferenceEvidence>,
    provenance: List<ModelProvenanceEntity>,
    sources: List<ModelSourceEntity>,
    installedPaths: Set<String>
): Map<String, String> {
    val sourcesById = sources.associateBy { it.id }
    return buildMap {
        references.filter { it.source == VideoReuseReferenceSource.MISSING }.forEach { reference ->
            val oldEdges = provenance.filter { it.localPath == reference.recordedPath }
            val candidates = oldEdges.flatMap { old ->
                val source = sourcesById[old.sourceId] ?: return@flatMap emptyList()
                if (!source.verified) return@flatMap emptyList()
                provenance.filter { current ->
                    current.sourceId == old.sourceId && current.localPath in installedPaths &&
                        current.localPath != reference.recordedPath &&
                        sameArtifactEvidence(old, current, source) &&
                        current.localPath?.let { path ->
                            val file = File(path)
                            file.isFile && file.canRead() && current.sizeBytes?.let { it == file.length() } == true &&
                                file.lastModified() <= current.updatedAt + 2_000L
                        } == true
                }.mapNotNull { it.localPath }
            }.distinct()
            if (candidates.size == 1) put(reference.field, candidates.single())
        }
    }
}

private fun sameArtifactEvidence(old: ModelProvenanceEntity, current: ModelProvenanceEntity, source: ModelSourceEntity): Boolean {
    val oldHash = old.artifactSha256?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
    val newHash = current.artifactSha256?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
    if (oldHash != null && newHash != null) return oldHash.equals(newHash, ignoreCase = true)
    // A pinned HF commit and exact file path identify an immutable remote artifact.
    return source.repositoryId != null && !source.filePath.isNullOrBlank() &&
        source.revision.matches(Regex("[a-fA-F0-9]{40}")) &&
        old.sizeBytes != null && old.sizeBytes == current.sizeBytes && old.sizeBytes == source.expectedSizeBytes
}
