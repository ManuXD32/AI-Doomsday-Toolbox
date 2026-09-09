package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMediaProcessorTest {
    @Test
    fun `segment planner uses sequential thirty second windows`() {
        val segments = buildVideoSegmentPlan(61_000L)

        assertEquals(3, segments.size)
        assertEquals(VideoSegment(0, 0L, 30_000L), segments[0])
        assertEquals(VideoSegment(1, 30_000L, 60_000L), segments[1])
        assertEquals(VideoSegment(2, 60_000L, 61_000L), segments[2])
    }

    @Test
    fun `segment planner rejects unbounded duration before allocation`() {
        val failure = runCatching {
            buildVideoSegmentPlan(Long.MAX_VALUE)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("bounded"))
    }

    @Test
    fun `ffmpeg extraction command caps fps and frames`() {
        val command = buildVideoFrameExtractionCommand(
            ffmpegPath = "/private/ffmpeg",
            sourcePath = "/private/media/clip.mp4",
            segment = VideoSegment(0, 30_000L, 60_000L),
            fps = 10f,
            maxFrames = 100,
            outputPattern = "/private/frames/raw_%06d.jpg"
        )

        val filter = command[command.indexOf("-vf") + 1]
        assertTrue(filter.startsWith("fps=0.8"))
        assertTrue(filter.contains("scale=768:768"))
        assertTrue(filter.contains("force_divisible_by=2"))
        assertEquals("24", command[command.indexOf("-frames:v") + 1])
        assertTrue(command.contains("-nostdin"))
        assertTrue(command.contains("-ss"))
        assertTrue(command.contains("-t"))
    }

    @Test
    fun `effective fps covers full segment within frame budget`() {
        assertEquals(
            0.8f,
            effectiveVideoFpsForSegment(VideoSegment(0, 0L, 30_000L), 2f, 24),
            0.001f
        )
        assertEquals(
            2f,
            effectiveVideoFpsForSegment(VideoSegment(0, 0L, 1_000L), 2f, 24),
            0.001f
        )
        assertEquals(
            2f / 30f,
            effectiveVideoFpsForSegment(VideoSegment(0, 0L, 30_000L), 2f, 2),
            0.001f
        )
        assertEquals(
            1f / 30f,
            effectiveVideoFpsForSegment(VideoSegment(0, 0L, 30_000L), 2f, 1),
            0.001f
        )
    }

    @Test
    fun `frame extraction preserves sub tenth fps coverage`() {
        val command = buildVideoFrameExtractionCommand(
            ffmpegPath = "/private/ffmpeg",
            sourcePath = "/private/media/clip.mp4",
            segment = VideoSegment(0, 0L, 30_000L),
            fps = 2f,
            maxFrames = 1,
            outputPattern = "/private/frames/raw_%06d.jpg"
        )

        assertTrue(command[command.indexOf("-vf") + 1].startsWith("fps=0.033"))
        assertEquals("1", command[command.indexOf("-frames:v") + 1])
    }
}
