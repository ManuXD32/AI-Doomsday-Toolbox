package com.example.llamadroid.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the generated decoder command against FFmpeg, without building an Android APK. */
class VideoFrameExtractionIntegrationTest {
    @Test fun longSilentClipProducesBoundedFramesForEverySegment() = runBlocking {
        val ffmpeg = File("/usr/bin/ffmpeg")
        assumeTrue(ffmpeg.canExecute())
        val directory = Files.createTempDirectory("video-frames-test").toFile()
        try {
            val source = File(directory, "silent.mp4")
            val runner = BoundedVideoProcessRunner()
            val creation = runner.run(
                listOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10", "-t", "61",
                    "-an", "-c:v", "mpeg4", "-threads", "1", source.path),
                directory, 15_000
            )
            assertEquals(creation.output, 0, creation.exitCode)
            val frameCounts = buildVideoSegmentPlan(61_000).map { segment ->
                val frames = File(directory, "segment_${segment.index}").apply { mkdirs() }
                val result = runner.run(
                    buildVideoFrameExtractionCommand(
                        ffmpeg.path, source.path, segment, fps = 2f, maxFrames = 24,
                        outputPattern = File(frames, "raw_%06d.jpg").path
                    ), frames, 15_000
                )
                assertEquals(result.output, 0, result.exitCode)
                frames.listFiles().orEmpty().count { it.extension == "jpg" }
            }
            assertEquals(listOf(24, 24, 2), frameCounts)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedMediaTerminatesWithDecoderError() = runBlocking {
        val ffmpeg = File("/usr/bin/ffmpeg")
        assumeTrue(ffmpeg.canExecute())
        val directory = Files.createTempDirectory("video-malformed-test").toFile()
        try {
            val source = File(directory, "bad.mp4").apply { writeText("not a video") }
            val result = BoundedVideoProcessRunner().run(
                buildVideoFrameExtractionCommand(
                    ffmpeg.path, source.path, VideoSegment(0, 0, 30_000), fps = 2f,
                    maxFrames = 24, outputPattern = File(directory, "raw_%06d.jpg").path
                ), directory, 5_000
            )
            assertTrue(result.exitCode != 0)
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "jpg" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
