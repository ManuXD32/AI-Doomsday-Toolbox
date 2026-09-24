package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.DOWNLOAD_TASK_STATUS_ACTIVE
import com.example.llamadroid.data.db.DownloadTaskEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTrackingTest {

    @Test
    fun `concurrent task rows keep immutable identity and order while progress timestamps change`() {
        val tasks = listOf(
            downloadTask("repo-a-one", "repo-a", "one.gguf", createdAt = 100L),
            downloadTask("repo-a-two", "repo-a", "two.gguf", createdAt = 200L),
            downloadTask("repo-a-three", "repo-a", "three.gguf", createdAt = 300L),
            downloadTask("repo-a-shared", "repo-a", "shared.gguf", createdAt = 400L),
            downloadTask("repo-b-shared", "repo-b", "shared.gguf", createdAt = 500L)
        )
        val initialRows = tasks.stableDownloadTaskOrder().map { Triple(it.id, it.filename, it.repoId) }
        val progressed = tasks.mapIndexed { index, task ->
            task.copy(
                bytesDownloaded = (index + 1L) * 100L,
                updatedAt = 10_000L - index
            )
        }

        assertEquals(initialRows, progressed.stableDownloadTaskOrder().map {
            Triple(it.id, it.filename, it.repoId)
        })
        assertEquals(
            listOf("repo-b-shared", "repo-a-shared", "repo-a-three", "repo-a-two", "repo-a-one"),
            initialRows.map { it.first }
        )
        assertEquals(0.25f, mapOf("repo-a-shared" to 0.25f).progressForDownloadTask(tasks[3]) ?: -1f)
        assertEquals(0.75f, mapOf("repo-b-shared" to 0.75f).progressForDownloadTask(tasks[4]) ?: -1f)
    }

    @Test
    fun `three same repository downloads keep their ids through room recovery`() {
        val filenames = listOf("alpha.gguf", "beta.gguf", "gamma.gguf")
        val tasks = filenames.mapIndexed { index, filename ->
            val id = buildDownloadTaskId("owner/shared-repo", filename, ModelType.LLM)
            PendingDownloadHolder.addPending(
                downloadId = id,
                filename = filename,
                repoId = "owner/shared-repo",
                progressKey = id,
                type = ModelType.LLM,
                destPath = "/tmp/$index/$filename"
            )
            DownloadProgressHolder.updateProgress(id, filename, (index + 1) / 4f)
            requireNotNull(PendingDownloadHolder.getPending(id)).toDownloadTaskEntity(
                downloadId = id,
                url = "https://example.invalid/$filename"
            )
        }

        assertEquals(3, tasks.map { it.id }.distinct().size)
        tasks.forEach { task ->
            PendingDownloadHolder.addPendingFrom(task.copy(updatedAt = task.updatedAt + 10_000L))
            val recovered = requireNotNull(PendingDownloadHolder.getPending(task.id))
            assertEquals(task.id, recovered.downloadId)
            assertEquals(task.id, recovered.progressKey)
            assertEquals(task.filename, recovered.filename)
            assertEquals(task.destPath, recovered.destPath)
            assertEquals(task.filename, DownloadProgressHolder.getFilename(task.progressKey))
        }

        tasks.forEach { task ->
            PendingDownloadHolder.removePending(task.id)
            DownloadProgressHolder.removeProgress(task.progressKey)
        }
    }

    @Test
    fun `duplicate filename aliases never redirect a task identity`() {
        val firstId = buildDownloadTaskId("owner/one", "projector.gguf", ModelType.MMPROJ)
        val secondId = buildDownloadTaskId("owner/two", "projector.gguf", ModelType.MMPROJ)
        PendingDownloadHolder.addPending(
            downloadId = firstId,
            filename = "projector.gguf",
            repoId = "owner/one",
            progressKey = firstId,
            type = ModelType.MMPROJ,
            destPath = "/tmp/one/projector.gguf"
        )
        PendingDownloadHolder.addPending(
            downloadId = secondId,
            filename = "projector.gguf",
            repoId = "owner/two",
            progressKey = secondId,
            type = ModelType.MMPROJ,
            destPath = "/tmp/two/projector.gguf"
        )

        assertNull(PendingDownloadHolder.getPending("projector.gguf"))
        assertEquals("/tmp/one/projector.gguf", PendingDownloadHolder.getPending(firstId)?.destPath)
        assertEquals("/tmp/two/projector.gguf", PendingDownloadHolder.getPending(secondId)?.destPath)

        PendingDownloadHolder.removePending(firstId)
        assertEquals(secondId, PendingDownloadHolder.getPending("projector.gguf")?.downloadId)
        PendingDownloadHolder.removePending(secondId)
        assertNull(PendingDownloadHolder.getPending("projector.gguf"))
    }

    @Test
    fun `legacy filename fallback is unavailable when multiple room rows share the name`() {
        val first = downloadTask("repo-one", "owner/one", "same.gguf", createdAt = 1L)
        val second = downloadTask("repo-two", "owner/two", "same.gguf", createdAt = 2L)

        assertNull(listOf(first, second).filter { it.filename == "same.gguf" }.singleOrNull())
        assertEquals(first, listOf(first).filter { it.filename == "same.gguf" }.singleOrNull())
    }

    @Test
    fun `progress holder tracks exact task filenames independently`() {
        val firstKey = buildDownloadTaskId("repo/one", "mmproj.gguf", ModelType.VISION_PROJECTOR)
        val secondKey = buildDownloadTaskId("repo/two", "mmproj.gguf", ModelType.VISION_PROJECTOR)

        DownloadProgressHolder.updateProgress(firstKey, "mmproj.gguf", 0.25f)
        DownloadProgressHolder.updateProgress(secondKey, "mmproj-123.gguf", 0.5f)

        assertEquals("mmproj.gguf", DownloadProgressHolder.getFilename(firstKey))
        assertEquals("mmproj-123.gguf", DownloadProgressHolder.getFilename(secondKey))
        assertTrue(DownloadProgressHolder.isFilenameTracked("mmproj.gguf"))
        assertTrue(DownloadProgressHolder.isFilenameTracked("mmproj-123.gguf"))

        DownloadProgressHolder.removeProgress(firstKey)

        assertNull(DownloadProgressHolder.getFilename(firstKey))
        assertEquals("mmproj-123.gguf", DownloadProgressHolder.getFilename(secondKey))
        assertFalse(DownloadProgressHolder.isFilenameTracked("mmproj.gguf"))
        assertTrue(DownloadProgressHolder.isFilenameTracked("mmproj-123.gguf"))

        DownloadProgressHolder.removeProgress(secondKey)
    }

    @Test
    fun `pending downloads can be looked up by exact id and legacy filename`() {
        val taskId = buildDownloadTaskId("repo/one", "model.gguf", ModelType.LLM)

        PendingDownloadHolder.addPending(
            downloadId = taskId,
            filename = "model-123.gguf",
            repoId = "repo/one",
            progressKey = taskId,
            type = ModelType.LLM,
            destPath = "/tmp/model-123.gguf"
        )

        assertEquals(taskId, PendingDownloadHolder.getPending(taskId)?.progressKey)
        assertEquals(taskId, PendingDownloadHolder.getPending("model-123.gguf")?.progressKey)

        PendingDownloadHolder.removePending(taskId)

        assertNull(PendingDownloadHolder.getPending(taskId))
        assertNull(PendingDownloadHolder.getPending("model-123.gguf"))
    }

    @Test
    fun `vision capability survives persisted pending task recovery`() {
        val pending = PendingDownload(
            filename = "video-model.gguf",
            repoId = "ggml-org/video-model",
            progressKey = "video-task",
            type = ModelType.LLM,
            destPath = "/tmp/video-model.gguf",
            isVision = true
        )

        val persisted = pending.toDownloadTaskEntity(
            downloadId = "video-task",
            url = "https://example.invalid/video-model.gguf"
        )

        assertTrue(persisted.isVision)
        assertTrue(persisted.toPendingDownload().isVision)

        val audioPending = pending.copy(
            filename = "speech-model.gguf",
            repoId = "ggml-org/speech-model",
            progressKey = "speech-task",
            type = ModelType.LLAMA_TTS,
            destPath = "/tmp/speech-model.gguf",
            isVision = false,
            artifactFamily = AudioModelSupport.FAMILY_POCKET_TTS,
            artifactRole = AudioModelSupport.ROLE_MAIN
        )
        val persistedAudio = audioPending.toDownloadTaskEntity(
            downloadId = "speech-task",
            url = "https://example.invalid/speech-model.gguf"
        )
        assertEquals(AudioModelSupport.FAMILY_POCKET_TTS, persistedAudio.artifactFamily)
        assertEquals(AudioModelSupport.ROLE_MAIN, persistedAudio.toPendingDownload().artifactRole)
    }

    @Test
    fun `litert pending download keeps exact progress key for cancellation`() {
        val progressKey = "litert:live|owner/repo|model.litertlm"

        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = "model.litertlm",
            repoId = "owner/repo",
            progressKey = progressKey,
            type = ModelType.LLM,
            destPath = "/tmp/model.litertlm",
            liteRtDisplayName = "Model",
            liteRtSupportsEmbedding = true
        )

        val pendingByKey = PendingDownloadHolder.getPending(progressKey)
        val pendingByFilename = PendingDownloadHolder.getPending("model.litertlm")

        assertEquals(progressKey, pendingByKey?.progressKey)
        assertEquals(progressKey, pendingByFilename?.progressKey)
        assertTrue(pendingByKey?.liteRtSupportsEmbedding == true)

        PendingDownloadHolder.removePending(progressKey)

        assertNull(PendingDownloadHolder.getPending(progressKey))
        assertNull(PendingDownloadHolder.getPending("model.litertlm"))
    }

    @Test
    fun `pending download preserves hugging face token for gated tasks`() {
        val progressKey = "litert:live|owner/repo|gated-model.tflite"

        PendingDownloadHolder.addPending(
            downloadId = progressKey,
            filename = "gated-model.tflite",
            repoId = "owner/repo",
            progressKey = progressKey,
            type = ModelType.LLM,
            destPath = "/tmp/gated-model.tflite",
            huggingFaceToken = "hf_test_token"
        )

        assertEquals("hf_test_token", PendingDownloadHolder.getPending(progressKey)?.huggingFaceToken)
        assertEquals("hf_test_token", PendingDownloadHolder.getPending("gated-model.tflite")?.huggingFaceToken)

        PendingDownloadHolder.removePending(progressKey)
    }

    private fun downloadTask(
        id: String,
        repoId: String,
        filename: String,
        createdAt: Long,
    ) = DownloadTaskEntity(
        id = id,
        url = "https://example.invalid/$filename",
        destPath = "/tmp/$id/$filename",
        filename = filename,
        repoId = repoId,
        progressKey = id,
        modelType = ModelType.LLM.name,
        status = DOWNLOAD_TASK_STATUS_ACTIVE,
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
