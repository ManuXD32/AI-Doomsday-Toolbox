package com.example.llamadroid.data.model

import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTrackingTest {

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
}
