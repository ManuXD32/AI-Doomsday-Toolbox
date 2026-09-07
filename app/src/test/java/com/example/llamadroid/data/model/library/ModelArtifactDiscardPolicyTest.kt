package com.example.llamadroid.data.model.library

import android.content.Context
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactDiscardPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun discardableInspectionStatesExposeAnExactFileAndPartialCandidate() {
        val managedRoot = temporary.newFolder("managed")
        val destination = File(managedRoot, "nested/model.gguf")
        val context = managedContext(managedRoot)
        val artifact = PendingModelArtifactEntity(
            id = "unknown-fixture",
            filename = destination.name,
            stagingPath = destination.path,
            destinationPath = destination.path,
            status = PendingArtifactStatus.VALIDATED.storedValue
        )

        val candidates = ModelArtifactDiscardPolicy.deletionCandidates(context, artifact)

        assertEquals(
            listOf(
                destination.canonicalFile.path,
                File(destination.parentFile, "${destination.name}.part").canonicalFile.path
            ),
            candidates.map { it.path }
        )
        assertTrue(ModelArtifactDiscardPolicy.isDiscardableStatus(
            PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue
        ))
        assertTrue(ModelArtifactDiscardPolicy.isDiscardableStatus(
            PendingArtifactStatus.CANCELLED.storedValue
        ))
    }

    @Test
    fun discardRejectsPromotedDirectoriesAndPathsOutsideAppRoots() {
        val managedRoot = temporary.newFolder("managed")
        val context = managedContext(managedRoot)
        val managedDirectory = File(managedRoot, "managed-directory").apply { mkdirs() }
        val outside = Files.createTempFile("outside-artifact", ".gguf").toFile()
        try {
            val promoted = PendingModelArtifactEntity(
                id = "promoted",
                filename = "model.gguf",
                stagingPath = File(managedRoot, "model.gguf").path,
                status = PendingArtifactStatus.PROMOTED.storedValue,
                promotedModelKey = "model.gguf"
            )
            val directory = PendingModelArtifactEntity(
                id = "directory",
                filename = managedDirectory.name,
                stagingPath = managedDirectory.path,
                status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue
            )
            val outsideArtifact = PendingModelArtifactEntity(
                id = "outside",
                filename = outside.name,
                stagingPath = outside.path,
                status = PendingArtifactStatus.NEEDS_MANUAL_PROMOTION.storedValue
            )

            assertEquals(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_PROMOTED,
                runCatching {
                    ModelArtifactDiscardPolicy.deletionCandidates(context, promoted)
                }.exceptionOrNull()?.let { requireNotNull(it as? ModelLibraryException).code }
            )
            assertEquals(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                runCatching {
                    ModelArtifactDiscardPolicy.deletionCandidates(context, directory)
                }.exceptionOrNull()?.let { requireNotNull(it as? ModelLibraryException).code }
            )
            assertEquals(
                ModelLibraryErrorCode.ARTIFACT_DISCARD_UNSAFE_PATH,
                runCatching {
                    ModelArtifactDiscardPolicy.deletionCandidates(context, outsideArtifact)
                }.exceptionOrNull()?.let { requireNotNull(it as? ModelLibraryException).code }
            )
        } finally {
            outside.delete()
        }
    }

    private fun managedContext(root: File): Context = mockk<Context>(relaxed = true).also { context ->
        every { context.filesDir } returns root
        every { context.noBackupFilesDir } returns root.resolve("no-backup")
        every { context.cacheDir } returns root.resolve("cache")
        every { context.getExternalFilesDir(null) } returns null
        every { context.externalCacheDir } returns null
    }
}
