package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.ModelProvenanceEntity
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelArtifactLifecycleTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun groupedEvidenceCoversDirectoriesShardsAndImportedCompanions() {
        val directory = temporary.newFolder("package")
        assertTrue(
            ModelArtifactLifecycle.isGroupedArtifact(
                modelPath = directory.path,
                modelKey = "package/model",
                pendingArtifacts = emptyList(),
                provenance = emptyList()
            )
        )
        assertTrue(
            ModelArtifactLifecycle.isGroupedArtifact(
                modelPath = File(temporary.root, "weights-00001-of-00002.safetensors").path,
                modelKey = "weights",
                pendingArtifacts = emptyList(),
                provenance = emptyList()
            )
        )

        val first = File(temporary.root, "primary.gguf")
        val second = File(temporary.root, "tokenizer.json")
        val provenance = listOf(
            ModelProvenanceEntity(id = "p1", sourceId = "source", modelKey = "group", family = "SD", localPath = first.path),
            ModelProvenanceEntity(id = "p2", sourceId = "source", modelKey = "group", family = "SD", localPath = second.path)
        )
        assertTrue(
            ModelArtifactLifecycle.isGroupedArtifact(
                modelPath = first.path,
                modelKey = "group",
                pendingArtifacts = emptyList(),
                provenance = provenance
            )
        )
    }

    @Test
    fun onePathProvenanceDoesNotBlockAStandaloneRename() {
        val file = temporary.newFile("model.gguf")
        val provenance = listOf(
            ModelProvenanceEntity(id = "p1", sourceId = "source-a", modelKey = "model.gguf", family = "LLM", localPath = file.path),
            ModelProvenanceEntity(id = "p2", sourceId = "source-b", modelKey = "model.gguf", family = "LLM", localPath = file.path)
        )
        assertFalse(
            ModelArtifactLifecycle.isGroupedArtifact(
                modelPath = file.path,
                modelKey = "model.gguf",
                pendingArtifacts = emptyList(),
                provenance = provenance
            )
        )
    }

    @Test
    fun onePendingRowWithStagingAndDestinationPathsIsStillStandalone() {
        val file = temporary.newFile("model.gguf")
        val pending = PendingModelArtifactEntity(
            filename = file.name,
            stagingPath = File(temporary.root, "staging/model.gguf").path,
            destinationPath = file.path,
            promotedModelKey = file.name,
            status = PendingArtifactStatus.PROMOTED.storedValue
        )
        assertFalse(
            ModelArtifactLifecycle.isGroupedArtifact(
                modelPath = file.path,
                modelKey = file.name,
                pendingArtifacts = listOf(pending),
                provenance = emptyList()
            )
        )
    }

    @Test
    fun detachingPromotedRowsCancelsOnlyRowsOwnedByTheRemovedModel() {
        val rows = listOf(
            PendingModelArtifactEntity(
                id = "owned", filename = "model.gguf", stagingPath = "/stage/model.gguf",
                destinationPath = "/models/model.gguf", status = PendingArtifactStatus.PROMOTED.storedValue,
                promotedModelKey = "model.gguf", promotedAt = 10L
            ),
            PendingModelArtifactEntity(
                id = "other", filename = "other.gguf", stagingPath = "/stage/other.gguf",
                status = PendingArtifactStatus.PROMOTED.storedValue, promotedModelKey = "other.gguf"
            )
        )

        val detached = ModelArtifactLifecycle.detachPromotedPendingArtifacts(rows, "model.gguf", 99L)
        assertEquals(PendingArtifactStatus.CANCELLED.storedValue, detached[0].status)
        assertNull(detached[0].promotedModelKey)
        assertNull(detached[0].promotedAt)
        assertEquals("/models/model.gguf", detached[0].destinationPath)
        assertEquals(rows[1], detached[1])
    }

    @Test
    fun deletionPreservesSharedSiblingAndRemovesUnreferencedFiles() {
        val root = temporary.newFolder("bundle")
        val owned = File(root, "owned.gguf").apply { writeText("owned") }
        val shared = File(root, "shared.json").apply { writeText("shared") }
        val deleted = ModelArtifactLifecycle.deleteOwnedPaths(
            candidates = listOf(root),
            protectedPaths = listOf(shared.path)
        )

        assertFalse(owned.exists())
        assertTrue(shared.exists())
        assertTrue(deleted.any { it == owned.canonicalPath })
    }

    @Test
    fun standalonePendingRekeyChangesExactPrimaryPathOnly() {
        val row = PendingModelArtifactEntity(
            filename = "model.gguf",
            stagingPath = "/stage/model.gguf",
            destinationPath = "/models/model.gguf",
            promotedModelKey = "model.gguf"
        )
        val rekeyed = ModelArtifactLifecycle.rekeyPendingArtifact(
            artifact = row,
            oldModelKey = "model.gguf",
            newModelKey = "renamed.gguf",
            oldPath = "/models/model.gguf",
            newPath = "/models/renamed.gguf",
            now = 42L
        )
        assertEquals("renamed.gguf", rekeyed.promotedModelKey)
        assertEquals("/stage/model.gguf", rekeyed.stagingPath)
        assertEquals("/models/renamed.gguf", rekeyed.destinationPath)
        assertEquals(42L, rekeyed.updatedAt)
    }
}
