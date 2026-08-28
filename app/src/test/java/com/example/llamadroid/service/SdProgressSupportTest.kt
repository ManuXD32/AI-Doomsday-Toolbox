package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdProgressSupportTest {

    @Test
    fun `accepts native ADetailer denominator instead of nominal steps`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val snapshot = tracker.update(
            "|=====> | 1/9 - 51.89s/it",
            nowMs = 51_890L
        )

        requireNotNull(snapshot)
        assertEquals(1, snapshot.currentStep)
        assertEquals(9, snapshot.totalSteps)
        assertEquals(51.89, snapshot.iterationSeconds ?: 0.0, 0.001)
        assertEquals(415.12, snapshot.etaSeconds ?: 0.0, 0.01)
        assertEquals(SdProgressPhase.DIFFUSION, snapshot.phase)
        assertTrue(snapshot.progress in 0f..0.99f)
    }

    @Test
    fun `accepts native img2img denominator instead of nominal steps`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val snapshot = tracker.update(
            "|===> | 1/16 - 61.52s/it",
            nowMs = 61_520L
        )

        requireNotNull(snapshot)
        assertEquals(1, snapshot.currentStep)
        assertEquals(16, snapshot.totalSteps)
        assertEquals(922.8, snapshot.etaSeconds ?: 0.0, 0.01)
    }

    @Test
    fun `tensor loading throughput bars are not diffusion progress`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        assertNull(tracker.update("| 126/126 - 28.57MB/s", nowMs = 1_000L))
        assertNull(tracker.update("| 686/686 - 1.54GB/s", nowMs = 2_000L))
    }

    @Test
    fun `parses sampling progress with iterations per second`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val snapshot = tracker.update("| 4/20 - 2.0 it/s", nowMs = 2_000L)

        requireNotNull(snapshot)
        assertEquals(4, snapshot.currentStep)
        assertEquals(20, snapshot.totalSteps)
        assertEquals(0.5, snapshot.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(8.0, snapshot.etaSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `parses explicit step progress with seconds per iteration`() {
        val tracker = SdProgressTracker(totalStepsHint = 10, startedAtMs = 0L)

        val snapshot = tracker.update("step 3/10 1.25 s/it", nowMs = 4_000L)

        requireNotNull(snapshot)
        assertEquals(3, snapshot.currentStep)
        assertEquals(10, snapshot.totalSteps)
        assertEquals(1.25, snapshot.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(8.75, snapshot.etaSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `falls back to wall clock timing for explicit step lines`() {
        val tracker = SdProgressTracker(totalStepsHint = 8, startedAtMs = 0L)

        val first = tracker.update("step 1/8", nowMs = 1_000L)
        val second = tracker.update("step 2/8", nowMs = 2_600L)

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(1.0, first.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(1.3, second.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(7.8, second.etaSeconds ?: 0.0, 0.01)
    }

    @Test
    fun `eta ticks down between long native sampling lines`() {
        val tracker = SdProgressTracker(totalStepsHint = 6, startedAtMs = 0L)

        val first = tracker.update("| 1/6 - 279.88 s/it", nowMs = 279_880L)
        val ticked = tracker.tick(nowMs = 289_880L)

        requireNotNull(first)
        requireNotNull(ticked)
        assertEquals(1_399.4, first.etaSeconds ?: 0.0, 0.001)
        assertEquals(1_389.4, ticked.etaSeconds ?: 0.0, 0.001)
    }

    @Test
    fun `real VAE lines change stage without fabricated progress or completion`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val encoding = tracker.update("IMG2IMG", nowMs = 1_000L)
        val diffusion = tracker.update("| 16/16 - 77.22s/it", nowMs = 1_000_000L)
        val decoding = tracker.update("decoding 1 latents", nowMs = 1_001_000L)
        val decoded = tracker.update(
            "computing vae decode graph completed, taking 151.05s",
            nowMs = 1_152_050L
        )
        val saving = tracker.update(
            "save result image 0 to '/tmp/out.png' (success)",
            nowMs = 1_153_000L
        )

        requireNotNull(encoding)
        requireNotNull(diffusion)
        requireNotNull(decoding)
        requireNotNull(decoded)
        requireNotNull(saving)
        assertEquals(SdProgressPhase.VAE_ENCODING, encoding.phase)
        assertEquals(SdProgressPhase.DIFFUSION, diffusion.phase)
        assertNull(diffusion.etaSeconds)
        assertTrue(diffusion.progress < 1f)
        assertEquals(SdProgressPhase.VAE_DECODING, decoding.phase)
        assertEquals(SdProgressPhase.VAE_DECODING, decoded.phase)
        assertTrue(decoding.progress > diffusion.progress)
        assertTrue(decoding.progress < 1f)
        assertEquals(SdProgressPhase.SAVING, saving.phase)
        assertTrue(saving.progress < 1f)
    }

    @Test
    fun `multiple ADetailer passes aggregate monotonically`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)
        val snapshots = buildList {
            tracker.update("ADetailer detected 3 object(s), taking 0.78s", 1L)?.let(::add)
            tracker.update("IMG2IMG", 2L)?.let(::add)
            tracker.update("| 1/9 - 60.0s/it", 60_000L)?.let(::add)
            tracker.update("| 9/9 - 60.0s/it", 540_000L)?.let(::add)
            tracker.update("sampling completed, taking 540.0s", 540_001L)
            tracker.update("decoding 1 latents", 540_002L)?.let(::add)
            tracker.update("IMG2IMG", 600_000L)?.let(::add)
            tracker.update("| 1/9 - 60.0s/it", 660_000L)?.let(::add)
        }

        assertEquals(3, snapshots.last().detailPassCount)
        assertEquals(1, snapshots.last().detailPassIndex)
        assertTrue(snapshots.zipWithNext().all { (before, after) -> after.progress >= before.progress })
        assertTrue(snapshots.last().progress < 1f)
    }

    @Test
    fun `healthy CPU iteration and VAE silence are not reported as stalls`() {
        val diffusion = SdProgressSnapshot(
            currentStep = 8,
            totalSteps = 16,
            progress = 0.5f,
            iterationSeconds = 65.2,
            phase = SdProgressPhase.DIFFUSION
        )
        val decoding = diffusion.copy(
            etaSeconds = null,
            phase = SdProgressPhase.VAE_DECODING,
            isIndeterminate = true
        )

        assertFalse(SdNativeLivenessPolicy.shouldReportNoOutput(diffusion, 77_000L))
        assertFalse(SdNativeLivenessPolicy.shouldReportNoOutput(decoding, 151_000L))
        assertTrue(SdNativeLivenessPolicy.shouldReportNoOutput(decoding, 301_000L))
    }

    @Test
    fun `progress percent rounds cleanly`() {
        val snapshot = SdProgressSnapshot(
            currentStep = 7,
            totalSteps = 13,
            progress = 7f / 13f
        )

        assertEquals(54, SdProgressTracker.progressPercent(snapshot))
    }

    @Test
    fun `starting snapshot is preparing and indeterminate`() {
        val snapshot = SdProgressTracker.buildStartingSnapshot(
            totalSteps = 18,
            statusText = "Starting, calculating ETA"
        )

        assertEquals(0, snapshot.currentStep)
        assertEquals(18, snapshot.totalSteps)
        assertEquals(0f, snapshot.progress, 0.0f)
        assertNull(snapshot.etaSeconds)
        assertEquals(SdProgressPhase.PREPARING, snapshot.phase)
        assertTrue(snapshot.isIndeterminate)
        assertTrue(snapshot.statusText.isNotBlank())
    }
}
