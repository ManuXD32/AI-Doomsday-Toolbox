package com.example.llamadroid.service

import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.ModelSourceEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoReuseSourceResolverTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(64)
    private val source = ModelSourceEntity(id = "source", kind = "https", family = "sd", label = "model", url = "https://example.com/model.gguf", normalizedKey = "model", verified = true)
    private val old = ModelProvenanceEntity(id = "old", sourceId = "source", modelKey = "old", family = "sd", localPath = "/missing/model.gguf", artifactSha256 = hash, sizeBytes = 4)
    private val reference = VideoReuseReferenceEvidence("model", old.localPath!!, old.localPath!!, VideoReuseReferenceSource.MISSING)
    private fun current(name: String, digest: String? = hash): ModelProvenanceEntity {
        val file = temp.newFile(name).apply { writeText("GGUF") }
        return old.copy(id = name, modelKey = name, localPath = file.path, artifactSha256 = digest, updatedAt = System.currentTimeMillis())
    }
    @Test fun uniqueVerifiedArtifactResolves() {
        val installed = current("moved.gguf")
        val resolved = resolveVideoReuseSourceEvidence(listOf(reference), listOf(old, installed), listOf(source), setOf(installed.localPath!!))
        assertEquals(installed.localPath, resolved["model"])
    }
    @Test fun filenameOrMutableLinkAloneDoesNotResolve() {
        val installed = current("model.gguf", null)
        assertTrue(resolveVideoReuseSourceEvidence(listOf(reference), listOf(old.copy(artifactSha256 = null), installed), listOf(source), setOf(installed.localPath!!)).isEmpty())
    }
    @Test fun ambiguousVerifiedCopiesRequireUserSelection() {
        val a = current("first.gguf"); val b = current("second.gguf")
        assertTrue(resolveVideoReuseSourceEvidence(listOf(reference), listOf(old, a, b), listOf(source), setOf(a.localPath!!, b.localPath!!)).isEmpty())
    }
    @Test fun mismatchedHashUnverifiedSourceAndTruncationDoNotResolve() {
        val a = current("different.gguf", "b".repeat(64))
        assertTrue(resolveVideoReuseSourceEvidence(listOf(reference), listOf(old, a), listOf(source), setOf(a.localPath!!)).isEmpty())
        val b = current("unverified.gguf")
        assertTrue(resolveVideoReuseSourceEvidence(listOf(reference), listOf(old, b), listOf(source.copy(verified = false)), setOf(b.localPath!!)).isEmpty())
        java.io.File(b.localPath!!).writeText("x")
        assertTrue(resolveVideoReuseSourceEvidence(listOf(reference), listOf(old, b), listOf(source), setOf(b.localPath!!)).isEmpty())
    }
    @Test fun validRecordedPathIsNeverReplaced() {
        val a = current("available.gguf")
        val ref = reference.copy(source = VideoReuseReferenceSource.RECORDED_PATH)
        assertTrue(resolveVideoReuseSourceEvidence(listOf(ref), listOf(old, a), listOf(source), setOf(a.localPath!!)).isEmpty())
    }
}
