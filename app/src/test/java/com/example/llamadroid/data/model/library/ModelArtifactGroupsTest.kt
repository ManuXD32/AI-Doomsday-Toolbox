package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelArtifactGroupsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bundle = ModelBundleEntity(id = "fixture", name = "Fixture", family = "ONNX")
    private fun item(index: Int, path: String) = ModelBundleItemEntity(id = "item-$index", bundleId = bundle.id,
        itemKey = "key-$index", family = bundle.family, partGroup = "encoder", partIndex = index,
        partCount = 2, relativePath = path)

    @Test fun incompleteGroupsStayDraftAndCollidingLayoutsAreRejected() {
        val first = item(0, "encoder/model.onnx")
        validateBundleLayout(listOf(first))
        assertEquals(setOf(first.id), incompleteBundleGroupIds(listOf(first)))
        assertTrue(runCatching { validateBundleLayout(listOf(first, item(1, first.relativePath!!))) }.isFailure)
        assertTrue(runCatching { validateBundleLayout(listOf(first, item(1, "encoder/model.onnx/config"))) }.isFailure)
        assertTrue(runCatching { validateBundleLayout(listOf(item(0, "../model.onnx"))) }.isFailure)
    }

    @Test fun directoryPromotionWaitsForEveryMemberAndUsesOneRoot() = runBlocking {
        val first = item(0, "encoder/model.onnx")
        val second = item(1, "encoder/tokenizer/config.json")
        val main = File(temporary.root, first.relativePath!!).apply { parentFile.mkdirs(); writeText("model") }
        val companion = File(temporary.root, second.relativePath!!)
        val rows = listOf(first to main, second to companion).map { (item, file) ->
            PendingModelArtifactEntity(id = item.id, filename = file.name, stagingPath = file.path,
                destinationPath = file.path, bundleId = bundle.id, bundleItemId = item.id,
                status = PendingArtifactStatus.INSPECTING.storedValue)
        }
        val dao = mockk<ModelLibraryDao>()
        coEvery { dao.getBundleItemById(first.id) } returns first
        coEvery { dao.getBundleItemById(second.id) } returns second
        coEvery { dao.getWithItems(bundle.id) } returns ModelBundleWithItems(bundle, listOf(first, second))
        coEvery { dao.getPendingArtifactsForBundle(bundle.id) } returns rows
        assertFalse(resolvePendingArtifactGroup(dao, rows[0], main).complete)
        companion.parentFile.mkdirs()
        companion.writeText("config")
        val ready = resolvePendingArtifactGroup(dao, rows[1], companion)
        assertTrue(ready.complete)
        assertEquals(main.parentFile.canonicalPath, ready.entry.path)
        assertEquals(rows[0].id, ready.primary.id)
        coEvery { dao.getPendingArtifactsForBundle(bundle.id) } returns listOf(rows[0], rows[1].copy(status = "CANCELLED"))
        assertFalse(resolvePendingArtifactGroup(dao, rows[0], main).complete)
        coEvery { dao.getPendingArtifactsForBundle(bundle.id) } returns rows
        coEvery { dao.getBundleItemById(second.id) } returns second.copy(expectedSha256 = "0".repeat(64))
        assertFalse(resolvePendingArtifactGroup(dao, rows[0], main).complete)
    }

    @Test fun missingSplitGgufIsNeverACompleteModel() = runBlocking {
        val file = temporary.newFile("model-00002-of-00003.gguf").apply { writeText("fixture") }
        val row = PendingModelArtifactEntity(filename = file.name, stagingPath = file.path)
        val result = resolvePendingArtifactGroup(mockk(), row, file)
        assertFalse(result.complete)
        assertEquals("model-00001-of-00003.gguf", result.entry.name)
    }

    @Test fun liteRtPackageKeepsEngineAndTokenizerUnderOneRuntimeDirectory() = runBlocking {
        val definitions = listOf(item(0, "package/model.litertlm"), item(1, "package/tokenizer.json"))
            .map { it.copy(family = "LITERT") }
        val rows = definitions.map { definition ->
            val file = File(temporary.root, definition.relativePath!!).apply { parentFile.mkdirs(); writeText("fixture") }
            PendingModelArtifactEntity(id = definition.id, filename = file.name, stagingPath = file.path,
                destinationPath = file.path, bundleId = bundle.id, bundleItemId = definition.id,
                status = PendingArtifactStatus.INSPECTING.storedValue)
        }
        val dao = mockk<ModelLibraryDao>()
        definitions.forEach { coEvery { dao.getBundleItemById(it.id) } returns it }
        coEvery { dao.getWithItems(bundle.id) } returns ModelBundleWithItems(bundle.copy(family = "LITERT"), definitions)
        coEvery { dao.getPendingArtifactsForBundle(bundle.id) } returns rows
        val group = resolvePendingArtifactGroup(dao, rows[1], File(rows[1].stagingPath))
        assertTrue(group.complete)
        assertEquals(File(temporary.root, "package").canonicalFile, group.entry)
    }

    @Test fun evidenceRejectsSameSizeButDifferentPayload() {
        val file = temporary.newFile("payload").apply { writeText("abc") }
        verifyArtifactFile(file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        file.writeText("xyz")
        assertTrue(runCatching { verifyArtifactFile(file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") }.isFailure)
    }

    @Test fun cancelledAndRemovedArtifactsCannotBeRevivedByStaleCompletion() = runBlocking {
        val dao = mockk<ModelLibraryDao>()
        coEvery { dao.getPendingArtifactById("cancelled") } returns PendingModelArtifactEntity(
            id = "cancelled", filename = "model", stagingPath = "/fixture", status = "CANCELLED")
        coEvery { dao.getPendingArtifactById("removed") } returns null
        listOf("cancelled", "removed").forEach {
            assertTrue(runCatching { ensurePendingArtifactActive(dao, it) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        }
    }
}
