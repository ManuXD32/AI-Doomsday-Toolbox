package com.example.llamadroid.service

import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GenerationQueueRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetDatabase() {
        AppDatabase.closeInstance()
        context.deleteDatabase("llama_droid_db")
        GenerationQueueRuntime.setActive(false)
    }

    @After fun closeDatabase() {
        AppDatabase.closeInstance()
        context.deleteDatabase("llama_droid_db")
    }

    @Test fun `timeout result retains inputs and a later callback cannot overwrite it`() = runBlocking {
        val repository = GenerationQueueRepository(context)
        val id = UUID.randomUUID().toString()
        val staged = File(context.filesDir, "generation_queue_inputs/$id").apply { mkdirs() }
        File(staged, "source.png").writeText("saved input")
        try {
            repository.add(PreparedGenerationQueueItem(id, GenerationQueueSnapshot.IMAGE,
                "IMG2IMG", "preview", "{}"))
            val runId = repository.beginRun(scheduled = false)
            assertTrue(runId != null)
            assertEquals(id, repository.claimNext()?.id)
            repository.requestPause()
            repository.finish(id, QueuedGenerationOutcome.interrupted(
                GenerationQueueService.MEDIA_PROCESSING_TIMEOUT_REASON))
            repository.finish(id, QueuedGenerationOutcome.stopped())

            assertEquals("INTERRUPTED", repository.item(id)?.status)
            assertEquals(GenerationQueueService.MEDIA_PROCESSING_TIMEOUT_REASON,
                repository.item(id)?.errorMessage)
            assertEquals("PAUSED", repository.controlNow().state)
            assertTrue(File(staged, "source.png").isFile)
            assertTrue(repository.discardRetryFiles(id))
            assertFalse(staged.exists())
        } finally {
            staged.deleteRecursively()
        }
    }

    @Test fun `orphan recovery preserves saved inputs`() = runBlocking {
        val repository = GenerationQueueRepository(context)
        val id = UUID.randomUUID().toString()
        val staged = File(context.filesDir, "generation_queue_inputs/$id").apply { mkdirs() }
        File(staged, "source.png").writeText("saved input")
        try {
            repository.add(PreparedGenerationQueueItem(id, GenerationQueueSnapshot.IMAGE,
                "IMG2IMG", "preview", "{}"))
            repository.beginRun(scheduled = false)
            repository.claimNext()
            repository.recoverOrphanedRun()

            assertEquals("INTERRUPTED", repository.item(id)?.status)
            assertEquals("PAUSED", repository.controlNow().state)
            assertTrue(File(staged, "source.png").isFile)
        } finally {
            staged.deleteRecursively()
        }
    }
}
