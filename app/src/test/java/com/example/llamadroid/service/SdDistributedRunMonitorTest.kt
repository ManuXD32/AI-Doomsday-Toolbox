package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdDistributedRunMonitorTest {
    // Formats copied from stable-diffusion.cpp: pretty_progress/print_progress_line (util.cpp), image.cpp, video.cpp, cli/main.cpp.
    private fun bar(step: Int, steps: Int, speed: String, eta: String = "") =
        "  |${"=".repeat(step)}>${" ".repeat(steps - step)}| $step/$steps - ${speed.padStart(10)}" +
            (if (eta.isEmpty()) "" else " - ETA $eta") + "\u001B[K"

    private val loading = listOf(
        "SD: [INFO ] model_manager.cpp:100 - loading diffusion model from '/data/m.gguf'",
        "SD: [INFO ] model_loader.cpp:1422 - loading tensors completed, taking 12.40s (read: 1.00s)"
    )

    @Test
    fun loadingThenTextEncodingPhases() {
        assertEquals(SdRunPhase.LOADING, SdRunLineParser.parse(loading.take(1)).phase)
        assertEquals(SdRunPhase.TEXT_ENCODING, SdRunLineParser.parse(loading).phase)
        val encoded = loading + "SD: [INFO ] image.cpp:580 - get_learned_condition completed, taking 3.21s"
        assertEquals(SdRunPhase.TEXT_ENCODING, SdRunLineParser.parse(encoded).phase)
        assertNull(SdRunLineParser.parse(emptyList()).phase)
    }

    @Test
    fun samplingBarGivesStepRateAndEta() {
        val lines = loading + listOf(
            "SD: [INFO ] image.cpp:920 - generating image: 1/1 - seed 42",
            "SD: " + bar(3, 20, "2.00s/it", "00:34"),
            "SD: " + bar(4, 20, "2.00s/it", "00:32")
        )
        val p = SdRunLineParser.parse(lines)
        assertEquals(SdRunPhase.DIFFUSION, p.phase)
        assertEquals(4, p.step)
        assertEquals(20, p.totalSteps)
        assertEquals(2.0, p.secondsPerIt!!, 0.001)
        // 16 remaining steps * 2 s + decode estimate 15% of one 40 s pass = 32 + 6
        assertEquals(38, SdRunLineParser.etaSeconds(p))
    }

    @Test
    fun itPerSecondIsConvertedToSecondsPerStep() {
        val p = SdRunLineParser.parse(listOf(bar(2, 10, "2.50it/s")))
        assertEquals(0.4, p.secondsPerIt!!, 0.001)
    }

    @Test
    fun batchRemainingStepsCountInEta() {
        val lines = listOf(
            "[INFO ] image.cpp:920 - generating image: 1/2 - seed 1",
            bar(10, 10, "1.00s/it"),
            "[INFO ] image.cpp:920 - generating image: 2/2 - seed 2",
            bar(5, 10, "1.00s/it")
        )
        val p = SdRunLineParser.parse(lines)
        assertEquals(2, p.batchIndex)
        assertEquals(2, p.batchCount)
        assertEquals(5, p.step)
        // 5 steps left + 2 decodes at 15% of a 10 s pass
        assertEquals(8, SdRunLineParser.etaSeconds(p))
    }

    @Test
    fun tilingBarAfterDecodeStartDoesNotRewindPhase() {
        val lines = listOf(
            bar(20, 20, "1.00s/it"),
            "[INFO ] image.cpp:965 - generating 1 latent images completed, taking 20.00s",
            "[INFO ] image.cpp:603 - decoding 1 latents",
            bar(2, 12, "0.50s/it")
        )
        val p = SdRunLineParser.parse(lines)
        assertEquals(SdRunPhase.VAE_DECODING, p.phase)
        assertEquals(20, p.step)
        assertNull(SdRunLineParser.etaSeconds(p))
    }

    @Test
    fun tensorLoadingThroughputBarIsNotSampling() {
        val p = SdRunLineParser.parse(listOf("  |####>   | 5/50 -   120.00MB/s"))
        assertNull(p.phase)
        assertEquals(0, p.totalSteps)
    }

    @Test
    fun videoFramesAndSavingPhases() {
        val lines = listOf(
            "[VIDEO-GEN] [INFO ] video.cpp:1676 - generate_video 480x320x33",
            "[VIDEO-GEN] " + bar(1, 4, "5.00s/it"),
            "[VIDEO-GEN] [INFO ] video.cpp:1920 - generating latent video completed, taking 20.00s",
            "[VIDEO-GEN] [INFO ] video.cpp:2008 - generate_video completed in 31.00s"
        )
        val p = SdRunLineParser.parse(lines)
        assertEquals(SdRunPhase.VIDEO_FRAMES, p.phase)
        assertEquals(33, p.frames)
        val saved = SdRunLineParser.parse(lines + "[VIDEO-GEN] [INFO ] main.cpp:545 - save result video to '/x.avi'")
        assertEquals(SdRunPhase.SAVING, saved.phase)
        assertFalse(saved.finished)
    }

    @Test
    fun imagesSavedMarksFinished() {
        val p = SdRunLineParser.parse(listOf("[INFO ] main.cpp:495 - save result image 0 to 'a.png' (success)", "[INFO ] main.cpp:571 - 1/1 images saved"))
        assertTrue(p.finished)
        assertEquals(0, SdRunLineParser.etaSeconds(p))
    }

    @Test
    fun stripPrefixHandlesBothSources() {
        assertEquals("abc", SdRunLineParser.stripPrefix("SD: abc"))
        assertEquals("abc", SdRunLineParser.stripPrefix("[VIDEO-GEN] abc"))
        assertEquals("abc", SdRunLineParser.stripPrefix("abc"))
    }

    private fun worker(host: String, isLocal: Boolean, vararg a: SdDistributedWorkerAssignment) =
        SdDistributedWorkerRuntime(host, 50052, host, 4096, 4, plannedAssignments = a.toList(), isLocalMaster = isLocal)

    @Test
    fun expectedBytesUseSplitSharesAndSkipMaster() {
        val sizes = mapOf("diffusion" to 1000L, "te" to 400L, "vae" to 100L)
        val workers = listOf(
            worker("master", true, SdDistributedWorkerAssignment("diffusion", "Diffusion", true, 20)),
            worker("b", false, SdDistributedWorkerAssignment("vae", "VAE", false, 100), SdDistributedWorkerAssignment("te", "TE", false, 100)),
            worker("a", false, SdDistributedWorkerAssignment("diffusion", "Diffusion", true, 80))
        )
        val expected = SdTransferExpectation.expected(workers, sizes)
        // diffusion host first (load order), then the te/vae host
        assertEquals(listOf("a:50052" to 800L, "b:50052" to 500L), expected)
    }

    @Test
    fun moduleBytesCountsExistingFilesOnly() {
        val file = java.io.File.createTempFile("sdmod", ".bin").apply { writeBytes(ByteArray(123)); deleteOnExit() }
        val sizes = SdTransferExpectation.moduleBytes(mapOf("diffusion" to listOf(file.absolutePath, null, "", "/nope/missing.gguf")))
        assertEquals(123L, sizes["diffusion"])
    }

    @Test
    fun monitorPublishesProgressThenCacheHit() {
        SdDistributedRunMonitor.clear()
        SdDistributedRunMonitor.begin(SdRunKind.IMAGE, "TXT2IMG", 0L, 1_000L, listOf("a:1" to 1_000_000_000L))
        assertNotNull(SdDistributedRunMonitor.session.value)
        SdDistributedRunMonitor.tick(1_000L, 1_000L + 100_000L, weightsLoaded = false)
        val mid = SdDistributedRunMonitor.transfers.value.single()
        assertEquals(100_000L, mid.sentBytes)
        assertFalse(mid.finished)
        SdDistributedRunMonitor.tick(2_000L, 1_000L + 200_000L, weightsLoaded = true)
        val done = SdDistributedRunMonitor.transfers.value.single()
        assertTrue(done.finished)
        assertTrue(done.fromCache)
        SdDistributedRunMonitor.clear()
        assertNull(SdDistributedRunMonitor.session.value)
    }

    @Test
    fun runViewIgnoresStateOfOtherModes() {
        // The panel passes only the run's own holder state; an Idle own mode is not failed/complete.
        val idle = sdRunView(SdRunKind.IMAGE, SDGenerationState.Idle, null)
        assertNull(idle.error)
        assertFalse(idle.complete)
        assertFalse(idle.running)
        val own = sdRunView(SdRunKind.VIDEO, null, VideoGenerationState.Error("boom"))
        assertEquals("boom", own.error)
        assertFalse(own.running)
        assertTrue(sdRunView(SdRunKind.IMAGE, SDGenerationState.Complete("/x.png"), null).complete)
        assertTrue(sdRunView(SdRunKind.VIDEO, null, VideoGenerationState.Converting(0.5f, "")).saving)
        // Image kind never reads a video state and vice versa.
        assertNull(sdRunView(SdRunKind.IMAGE, null, VideoGenerationState.Error("x")).error)
    }

    @Test
    fun beginClearsStaleTerminalStateOfItsOwnMode() {
        val holder = SDModeStateHolder.getDistributedForMode(SDMode.TXT2IMG)
        val other = SDModeStateHolder.getDistributedForMode(SDMode.IMG2IMG)
        holder.updateState(SDGenerationState.Error("old"))
        other.updateState(SDGenerationState.Error("other"))
        SdDistributedRunMonitor.begin(SdRunKind.IMAGE, "TXT2IMG", 0L, 0L, emptyList())
        assertTrue(holder.state.value is SDGenerationState.Idle)
        assertTrue(other.state.value is SDGenerationState.Error)
        SdDistributedRunMonitor.begin(SdRunKind.IMAGE, "NOPE", 0L, 0L, emptyList())
        other.updateState(SDGenerationState.Idle)
        SdDistributedRunMonitor.clear()
    }
}
