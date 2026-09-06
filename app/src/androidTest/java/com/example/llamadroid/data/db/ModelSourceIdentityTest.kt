package com.example.llamadroid.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.model.library.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.ResponseBody.Companion.toResponseBody

class ModelSourceIdentityTest {
    @Test fun replacingOneFileSourcePreservesCompanionsBundlesAndInstalledBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.cacheDir, "source-attachment-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dao = db.modelLibraryDao()
            val main = File(directory, "model.onnx").apply { writeText("installed fixture") }
            val companion = File(directory, "tokenizer.json").apply { writeText("companion fixture") }
            val oldSource = ModelSourceUrlValidator.toEntity(ModelSourceDraft(ModelFamily.ONNX,
                "https://example.com/old.onnx")).getOrThrow()
            val newSource = ModelSourceUrlValidator.toEntity(ModelSourceDraft(ModelFamily.ONNX,
                "https://example.com/new.onnx")).getOrThrow()
            dao.upsert(oldSource)
            dao.upsert(newSource)
            dao.upsert(ModelProvenanceEntity(id = "old-main", sourceId = oldSource.id,
                modelKey = "directory-model", family = "ONNX", localPath = main.canonicalPath))
            dao.upsert(ModelProvenanceEntity(id = "companion", sourceId = oldSource.id,
                modelKey = "directory-model", family = "ONNX", localPath = companion.canonicalPath))
            dao.upsert(ModelBundleEntity(id = "definition", name = "Saved definition", family = "ONNX"))
            dao.upsert(ModelBundleItemEntity(id = "item", bundleId = "definition", itemKey = "main",
                family = "ONNX", sourceId = oldSource.id, relativePath = "model.onnx"))
            val candidate = ModelProvenanceEntity(sourceId = newSource.id, modelKey = "directory-model",
                family = "ONNX", localPath = main.canonicalPath, role = "model")
            val first = dao.replaceProvenanceForArtifact(candidate)
            val second = dao.replaceProvenanceForArtifact(candidate.copy(id = "new-random-id"))
            assertEquals(first.id, second.id)
            assertEquals(2, dao.getByModelKey("directory-model").size)
            assertEquals("companion", dao.getProvenanceBySource(oldSource.id).single().id)
            assertEquals(oldSource.id, dao.getBundleItemById("item")!!.sourceId)
            assertNotNull(dao.getSourceById(oldSource.id))
            assertEquals("installed fixture", main.readText())
            assertEquals("companion fixture", companion.readText())
        } finally {
            db.close()
            directory.deleteRecursively() // Only this test's uniquely named temporary fixture.
        }
    }

    @Test fun customDownloadsStayVisibleInTheirOwningFamilyBeforeRecognition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadTaskDao()
            fun task(id: String, type: String, family: String? = null) = DownloadTaskEntity(
                id = id, url = "https://example.com/$id", destPath = "/fixture/$id", filename = id,
                repoId = "fixture", progressKey = id, modelType = type,
                artifactFamily = family, stageOnly = family != null)
            dao.upsert(task("custom-video-encoder", "LLM", "SD"))
            dao.upsert(task("custom-litert", "LLM", "LITERT"))
            dao.upsert(task("legacy-sd", "SD_DIFFUSION"))
            dao.upsert(task("legacy-llm", "LLM"))
            assertEquals(setOf("custom-video-encoder", "legacy-sd"),
                dao.observeByLibraryFamily(listOf("SD_DIFFUSION"), "SD").first().map { it.id }.toSet())
            assertEquals(setOf("legacy-llm"),
                dao.observeByLibraryFamily(listOf("LLM"), "LLM").first().map { it.id }.toSet())
        } finally { db.close() }
    }

    @Test fun queuedSourceDedupSurvivesRepositoryRecreationAndCancellationCannotBeUndone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.modelLibraryDao()
            val service = java.lang.reflect.Proxy.newProxyInstance(
                com.example.llamadroid.data.api.HuggingFaceService::class.java.classLoader,
                arrayOf(com.example.llamadroid.data.api.HuggingFaceService::class.java)
            ) { _, _, _ -> error("Network browsing is forbidden in this fixture") } as com.example.llamadroid.data.api.HuggingFaceService
            val client = okhttp3.OkHttpClient.Builder().addInterceptor {
                okhttp3.Response.Builder().request(it.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(404).message("Fixture missing file").body("missing".toResponseBody()).build()
            }.build()
            fun repository() = ModelSourceRepository(dao, HuggingFaceFolderBrowser(service), client)
            val source = repository().saveSource(ModelSourceDraft(ModelFamily.LLM,
                "https://example.com/model.gguf")).getOrThrow()
            val row = PendingModelArtifactEntity(id = "durable", sourceId = source.id,
                filename = "model.gguf", stagingPath = File(context.cacheDir, "missing-${System.nanoTime()}").path)
            dao.upsert(row)
            assertEquals(row.id, repository().startCustomDownload(context, source.id, ModelFamily.LLM, null).getOrThrow().id)
            assertEquals(ModelLibraryErrorCode.SOURCE_HAS_PENDING_DOWNLOAD,
                (repository().saveSource(ModelSourceDraft(ModelFamily.LLM, "https://example.com/other.gguf", id = source.id))
                    .exceptionOrNull() as ModelLibraryException).code)
            dao.upsert(row.copy(status = "CANCELLED"))
            assertTrue(runCatching { dao.upsertActiveArtifact(row) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("CANCELLED", dao.getPendingArtifactById(row.id)!!.status)
            dao.upsert(row.copy(status = "PROMOTED"))
            // Deleted weights must attempt validation again, never falsely return old success.
            assertEquals(ModelLibraryErrorCode.SOURCE_NOT_FOUND,
                (repository().startCustomDownload(context, source.id, ModelFamily.LLM, null)
                    .exceptionOrNull() as ModelLibraryException).code)
        } finally { db.close() }
    }

    @Test fun changedUrlRetainsAssociationsButNeverReusesOldInstalledPayload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.cacheDir, "source-identity-${System.nanoTime()}").apply { mkdirs() }
        try {
            val dao = db.modelLibraryDao()
            val service = java.lang.reflect.Proxy.newProxyInstance(
                com.example.llamadroid.data.api.HuggingFaceService::class.java.classLoader,
                arrayOf(com.example.llamadroid.data.api.HuggingFaceService::class.java)
            ) { _, _, _ -> error("This fixture must not browse the network") } as com.example.llamadroid.data.api.HuggingFaceService
            val repository = ModelSourceRepository(dao, HuggingFaceFolderBrowser(service))
            val installed = File(directory, "model.gguf").apply { writeText("old payload") }
            val original = repository.saveSource(ModelSourceDraft(ModelFamily.LLM,
                "https://example.com/old.gguf")).getOrThrow()
            dao.upsert(original.copy(verified = true))
            dao.upsert(ModelProvenanceEntity(id = "edge", sourceId = original.id, modelKey = "kept-model",
                family = "LLM", localPath = installed.path, sizeBytes = installed.length(),
                artifactSha256 = "0".repeat(64)))
            dao.upsert(ModelBundleEntity(id = "bundle", name = "Fixture", family = "LLM"))
            dao.upsert(ModelBundleItemEntity(id = "item", bundleId = "bundle", sourceId = original.id,
                family = "LLM", itemKey = "main", relativePath = "model.gguf",
                expectedSha256 = "0".repeat(64), expectedSizeBytes = installed.length()))
            dao.upsert(PendingModelArtifactEntity(id = "cancelled-old", sourceId = original.id,
                filename = "old.gguf", stagingPath = File(directory, "old.gguf").path,
                status = PendingArtifactStatus.CANCELLED.storedValue))
            val changed = repository.saveSource(ModelSourceDraft(ModelFamily.LLM,
                "https://example.com/new.gguf", id = original.id)).getOrThrow()
            assertEquals(SOURCE_IDENTITY_INVALIDATED_MARKER,
                dao.getPendingArtifactById("cancelled-old")!!.validationJson)
            assertFalse(changed.verified)
            val edge = dao.getProvenanceBySource(original.id).single()
            assertEquals("kept-model", edge.modelKey)
            assertNull(edge.localPath)
            assertNull(edge.artifactSha256)
            assertEquals("old payload", installed.readText())
            assertNull(dao.getBundleItemById("item")!!.expectedSha256)
            dao.upsert(changed.copy(verified = true))
            val plan = repository.planMissingDownloads("bundle", directory).getOrThrow()
            assertEquals(listOf("item"), plan.requests.map { it.item.id })
            assertTrue(plan.verifiedExistingItemIds.isEmpty())
            val second = repository.saveSource(ModelSourceDraft(ModelFamily.LLM,
                "https://example.com/another.gguf")).getOrThrow()
            assertEquals(ModelLibraryErrorCode.SOURCE_ALREADY_SAVED,
                (repository.saveSource(ModelSourceDraft(ModelFamily.LLM, changed.url, id = second.id))
                    .exceptionOrNull() as ModelLibraryException).code)
            assertNotNull(dao.getSourceById(second.id))
        } finally {
            db.close()
            directory.deleteRecursively() // Only this test's uniquely named temporary fixture.
        }
    }
}
