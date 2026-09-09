package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoRecognitionTargetSelectorTest {
    @Test
    fun `empty selection chooses recommended local bundle and never remote`() {
        val files = tempModelFiles()
        try {
            val local = listOf(
                localTarget("video-smolvlm2-500m", files),
                localTarget("video-qwen25vl-3b", files)
            )
            val remote = VideoRecognitionTarget.remote(
                endpoint = "http://127.0.0.1:8080",
                model = "remote-video",
                videoEnabled = true,
                serverId = 17L
            )

            val selection = VideoRecognitionTargetSelector.selectConfigured(
                savedTargetId = null,
                localTargets = local,
                remoteTargets = listOf(remote)
            )

            assertEquals("video-qwen25vl-3b", selection.target?.id)
            assertFalse(selection.savedTargetMissing)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun `saved remote target is explicit and missing saved target never switches locally`() {
        val files = tempModelFiles()
        try {
            val local = listOf(localTarget("video-smolvlm2-500m", files))
            val remote = VideoRecognitionTarget.remote(
                endpoint = "http://127.0.0.1:8080",
                model = "remote-video",
                videoEnabled = true,
                serverId = 17L
            )

            val noSavedRemote = VideoRecognitionTargetSelector.selectConfigured(
                savedTargetId = null,
                localTargets = emptyList(),
                remoteTargets = listOf(remote)
            )
            val missingSavedRemote = VideoRecognitionTargetSelector.selectConfigured(
                savedTargetId = "remote-server:99",
                localTargets = local,
                remoteTargets = listOf(remote)
            )

            assertNull(noSavedRemote.target)
            assertFalse(noSavedRemote.savedTargetMissing)
            assertNull(missingSavedRemote.target)
            assertTrue(missingSavedRemote.savedTargetMissing)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun `frame budget leaves room for prompt and output`() {
        assertEquals(6, boundedVideoFrameCount(contextSize = 8_192, maxTokens = 768, requestedFrames = 24))
        assertEquals(1, boundedVideoFrameCount(contextSize = 2_048, maxTokens = 768, requestedFrames = 24))
    }

    private fun localTarget(id: String, files: List<File>) = VideoRecognitionTarget(
        id = id,
        label = id,
        kind = VideoRecognitionTarget.Kind.LOCAL_BUNDLE,
        modelPath = files[0].absolutePath,
        mmprojPath = files[1].absolutePath
    )

    private fun tempModelFiles(): List<File> = listOf(
        File.createTempFile("video-model", ".gguf"),
        File.createTempFile("video-mmproj", ".gguf")
    )
}
