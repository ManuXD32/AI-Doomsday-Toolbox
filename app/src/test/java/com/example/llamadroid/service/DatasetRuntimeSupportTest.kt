package com.example.llamadroid.service

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetRuntimeSupportTest {
    @Test
    fun `persisted dataset snapshot round trips and preserves queue order`() {
        val snapshot = PersistedDatasetRuntimeSnapshot(
            activeJob = PersistedDatasetJob(
                type = "clean",
                name = "Clean job",
                projectId = 7L,
                prompt = "Clean prompt"
            ),
            queuedJobs = listOf(
                PersistedDatasetJob(
                    type = "questions",
                    name = "Questions job",
                    projectId = 7L,
                    prompt = "Question prompt"
                ),
                PersistedDatasetJob(
                    type = "regen_questions",
                    name = "Regen questions",
                    projectId = 7L,
                    prompt = "Question prompt",
                    chunkIds = listOf(4L, 9L)
                ),
                PersistedDatasetJob(
                    type = "import_pdf",
                    name = "Import PDF",
                    projectId = 7L,
                    sourceUri = "content://dataset/example.pdf",
                    sourceName = "example.pdf"
                )
            )
        )

        val restored = PersistedDatasetRuntimeSnapshot.fromJson(snapshot.toJson())
        val queue = restored.toRuntimeQueue()

        assertEquals(snapshot, restored)
        assertEquals(4, queue.size)
        assertTrue(queue[0] is DatasetProcessor.Job.Clean)
        assertTrue(queue[1] is DatasetProcessor.Job.Questions)
        assertTrue(queue[2] is DatasetProcessor.Job.RegenQuestions)
        val regen = queue[2] as DatasetProcessor.Job.RegenQuestions
        assertEquals(setOf(4L, 9L), regen.chunkIds)
        assertTrue(queue[3] is DatasetProcessor.Job.ImportPdf)
        val import = queue[3] as DatasetProcessor.Job.ImportPdf
        assertEquals("content://dataset/example.pdf", import.sourceUri)
        assertEquals("example.pdf", import.sourceName)
    }

    @Test
    fun `resolve dataset resume action is no-op when no recoverable runtime exists`() {
        val noRuntime = resolveDatasetResumeAction(null)
        val wrongType = resolveDatasetResumeAction(
            runtimeJob(
                type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
                payloadJson = PersistedDatasetRuntimeSnapshot().toJson().toString()
            )
        )
        val emptySnapshot = resolveDatasetResumeAction(
            runtimeJob(
                type = AiRuntimeJobStore.TYPE_DATASET_PIPELINE,
                payloadJson = PersistedDatasetRuntimeSnapshot().toJson().toString()
            )
        )

        assertFalse(noRuntime.shouldRecover)
        assertFalse(wrongType.shouldRecover)
        assertFalse(emptySnapshot.shouldRecover)
    }

    @Test
    fun `resolve dataset resume action restores active job and queued jobs`() {
        val snapshot = PersistedDatasetRuntimeSnapshot(
            activeJob = PersistedDatasetJob(
                type = "answers",
                name = "Answers",
                projectId = 21L,
                prompt = "Answer prompt"
            ),
            queuedJobs = listOf(
                PersistedDatasetJob(
                    type = "rating",
                    name = "Rating",
                    projectId = 21L,
                    prompt = "Review prompt"
                )
            )
        )

        val action = resolveDatasetResumeAction(
            runtimeJob(
                type = AiRuntimeJobStore.TYPE_DATASET_PIPELINE,
                payloadJson = snapshot.toJson().toString()
            )
        )

        assertTrue(action.shouldRecover)
        assertNotNull(action.snapshot)
        assertEquals(21L, action.snapshot?.firstProjectId())

        val restoredJobs = action.snapshot?.toRuntimeQueue().orEmpty()
        assertEquals(2, restoredJobs.size)
        assertTrue(restoredJobs[0] is DatasetProcessor.Job.Answers)
        assertTrue(restoredJobs[1] is DatasetProcessor.Job.Rating)
    }

    @Test
    fun `dataset runtime uses six hour stale timeout`() {
        val now = 30_000_000L
        val freshJob = runtimeJob(
            type = AiRuntimeJobStore.TYPE_DATASET_PIPELINE,
            payloadJson = PersistedDatasetRuntimeSnapshot(
                activeJob = PersistedDatasetJob(
                    type = "clean",
                    name = "Clean job",
                    projectId = 2L,
                    prompt = "Clean prompt"
                )
            ).toJson().toString(),
            updatedAt = now - (6L * 60L * 60L * 1000L) + 1L
        )
        val staleJob = freshJob.copy(updatedAt = now - (6L * 60L * 60L * 1000L) - 1L)

        assertFalse(AiRuntimeJobStore.isJobStale(freshJob, now))
        assertTrue(AiRuntimeJobStore.isJobStale(staleJob, now))
    }

    @Test
    fun `agent runtime uses six hour stale timeout`() {
        val now = 30_000_000L
        val freshJob = runtimeJob(
            type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
            payloadJson = "{}",
            updatedAt = now - AiRuntimeJobStore.staleWindowMsForType(AiRuntimeJobStore.TYPE_AGENT_CHAT) + 1L
        )
        val staleJob = freshJob.copy(updatedAt = now - AiRuntimeJobStore.staleWindowMsForType(AiRuntimeJobStore.TYPE_AGENT_CHAT) - 1L)

        assertEquals(6L * 60L * 60L * 1000L, AiRuntimeJobStore.staleWindowMsForType(AiRuntimeJobStore.TYPE_AGENT_CHAT))
        assertFalse(AiRuntimeJobStore.isJobStale(freshJob, now))
        assertTrue(AiRuntimeJobStore.isJobStale(staleJob, now))
    }

    private fun runtimeJob(
        type: String,
        payloadJson: String,
        updatedAt: Long = 200L
    ) = AiRuntimeJobEntity(
        jobId = "dataset-job",
        jobKey = "dataset-key",
        type = type,
        status = AiRuntimeJobStore.STATUS_RUNNING,
        payloadJson = payloadJson,
        checkpointJson = null,
        progressText = null,
        errorMessage = null,
        resumable = true,
        createdAt = 100L,
        updatedAt = updatedAt
    )
}
