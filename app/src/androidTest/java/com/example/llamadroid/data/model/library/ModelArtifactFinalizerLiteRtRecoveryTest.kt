package com.example.llamadroid.data.model.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.LiteRtModelEntity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A promoted LiteRT row is recoverable after its temporary download staging
 * file has been removed. The durable LiteRT DAO row is the runtime authority.
 */
@RunWith(AndroidJUnit4::class)
class ModelArtifactFinalizerLiteRtRecoveryTest {
    @Test
    fun promotedLiteRtKeyResolvesInstalledDaoRowWhenStagingIsGone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "litert-finalizer-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val installed = File(root, "installed/model.tflite").apply {
                parentFile!!.mkdirs()
                writeMinimalTflite(this)
            }
            val liteRtId = database.liteRtModelDao().insert(
                LiteRtModelEntity(
                    displayName = "Recovery model",
                    path = installed.absolutePath,
                    filename = installed.name,
                    sizeBytes = installed.length()
                )
            )
            val staleStaging = File(root, "staging/model.tflite")
            val artifact = PendingModelArtifactEntity(
                id = "litert-recovery",
                filename = staleStaging.name,
                stagingPath = staleStaging.absolutePath,
                destinationPath = installed.absolutePath,
                requestedFamily = ModelFamily.LITERT.storedValue,
                detectedFamily = ModelFamily.LITERT.storedValue,
                detectedType = ModelType.LLM.name,
                status = PendingArtifactStatus.PROMOTED.storedValue,
                requiresManualPromotion = false,
                promotedModelKey = "litert:$liteRtId"
            )
            database.modelLibraryDao().upsert(artifact)

            val result = ModelArtifactFinalizer.finalizeIfKnown(
                database,
                artifact,
                staleStaging,
                PendingArtifactRuntimeMetadata("fixture")
            ).getOrThrow()

            assertTrue(result.promoted)
            assertEquals("litert:$liteRtId", result.reference?.modelKey)
            assertEquals(PendingArtifactStatus.PROMOTED.storedValue,
                database.modelLibraryDao().getPendingArtifactById(artifact.id)?.status)
            assertEquals(1, database.liteRtModelDao().getAllOnce().size)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun writeMinimalTflite(file: File) {
        file.writeBytes(
            ByteBuffer.allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16)
                .put("TFL3".toByteArray(Charsets.US_ASCII))
                .putShort(6)
                .putShort(4)
                .putInt(8)
                .putInt(8)
                .array()
        )
    }
}
