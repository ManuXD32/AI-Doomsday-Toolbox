package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeLlamaVideoSupportTest {
    @Test
    fun `video command is bounded and keeps runtime paths optional for previews`() {
        val args = NativeLlamaVideoSupport.commandArgs(
            LlamaConfig(
                modelPath = "/models/vision.gguf",
                videoEnabled = true,
                videoFps = 9f,
                videoTimestampIntervalMs = 1,
                mediaPath = "/private/media",
                videoFfmpegDir = "/private/ffmpeg"
            )
        )

        assertEquals(
            listOf(
                "--video-fps", "2",
                "--video-timestamp-interval", "250",
                "--video-ffmpeg-dir", "/private/ffmpeg",
                "--media-path", "/private/media"
            ),
            args
        )
    }

    @Test
    fun `relative media urls reject traversal`() {
        assertEquals("file://clip_01.mp4", NativeLlamaVideoSupport.relativeFileUrl("clip_01.mp4"))
        assertTrue(runCatching { NativeLlamaVideoSupport.relativeFileUrl("../clip.mp4") }.isFailure)
        assertTrue(runCatching { NativeLlamaVideoSupport.relativeFileUrl("dir/clip.mp4") }.isFailure)
    }

    @Test
    fun `active runtime registry exposes only the live private media root`() {
        val root = File.createTempFile("llama-video-runtime", "")
        root.delete()
        val media = File(root, "media").apply { mkdirs() }
        val ffmpegDirectory = File(root, "ffmpeg").apply { mkdirs() }
        val runtime = LlamaVideoRuntimeDirectories(
            root = root,
            mediaDirectory = media,
            ffmpegDirectory = ffmpegDirectory,
            ffmpeg = File(ffmpegDirectory, "ffmpeg"),
            ffprobe = File(ffmpegDirectory, "ffprobe")
        )
        try {
            NativeLlamaVideoSupport.registerActiveRuntime(runtime)
            assertSame(runtime, NativeLlamaVideoSupport.activeRuntimeDirectories())
            assertEquals(media, NativeLlamaVideoSupport.activeMediaDirectory())
            NativeLlamaVideoSupport.clearActiveRuntime(runtime)
            assertNull(NativeLlamaVideoSupport.activeRuntimeDirectories())
        } finally {
            NativeLlamaVideoSupport.clearActiveRuntime(runtime)
            root.deleteRecursively()
        }
    }
}
