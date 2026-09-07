package com.example.llamadroid.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.model.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** All records and files belong to an isolated fixture database, never the installed user library. */
class ModelArtifactRecoveryTest {
    @Test fun recoveryRetainsManualRoleAndSameBasenameModelsRemainDistinct() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val fixture = File(context.cacheDir, "artifact-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val first = File(fixture, "one/model.safetensors").apply { parentFile.mkdirs(); writeText("fixture one") }
            val second = File(fixture, "two/model.safetensors").apply { parentFile.mkdirs(); writeText("fixture two") }
            val manual = ModelEntity(first.name, first.path, first.length(), ModelType.SD_AUDIO_VAE,
                "fixture", isDownloaded = true, sdFamily = "minimax_h3", sdVariant = "manual-variant")
            database.modelDao().insertModel(manual)
            val key = database.withTransaction {
                availableModelRecordKey(database, second, "second").also {
                    database.modelDao().insertModel(manual.copy(filename = it, path = second.path))
                }
            }
            assertNotEquals(manual.filename, key)
            assertEquals(first.path, database.modelDao().getModelByFilename(manual.filename)?.path)
            assertEquals(second.path, database.modelDao().getModelByFilename(key)?.path)
            val artifact = PendingModelArtifactEntity(id = "manual", filename = first.name,
                stagingPath = first.path, requestedFamily = "SD", requestedRole = "audio_vae",
                detectedFamily = "SD", detectedRole = "audio_vae", detectedType = "SD_AUDIO_VAE",
                status = PendingArtifactStatus.PROMOTED.storedValue, requiresManualPromotion = false,
                promotedModelKey = manual.filename)
            database.modelLibraryDao().upsert(artifact)
            val result = ModelArtifactFinalizer.finalizeIfKnown(database, artifact, first,
                PendingArtifactRuntimeMetadata("fixture")).getOrThrow()
            assertTrue(result.promoted)
            assertEquals(manual, database.modelDao().getModelByFilename(manual.filename))
            assertEquals(PendingArtifactStatus.PROMOTED.storedValue,
                database.modelLibraryDao().getPendingArtifactById(artifact.id)?.status)
        } finally {
            database.close()
            fixture.deleteRecursively()
        }
    }
}
