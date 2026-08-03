package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdProgressSupportTest {

    @Test
    fun `parses step progress with it per second`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val snapshot = tracker.update("| 4/20 - 2.0 it/s", nowMs = 2000L)

        requireNotNull(snapshot)
        assertEquals(4, snapshot.currentStep)
        assertEquals(20, snapshot.totalSteps)
        assertEquals(0.2f, snapshot.progress, 0.0001f)
        assertEquals(0.5, snapshot.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(8.0, snapshot.etaSeconds ?: 0.0, 0.0001)
        assertEquals(SdProgressPhase.DIFFUSION, snapshot.phase)
    }

    @Test
    fun `parses step progress with seconds per iteration`() {
        val tracker = SdProgressTracker(totalStepsHint = 10, startedAtMs = 0L)

        val snapshot = tracker.update("step 3/10 1.25 s/it", nowMs = 4000L)

        requireNotNull(snapshot)
        assertEquals(3, snapshot.currentStep)
        assertEquals(10, snapshot.totalSteps)
        assertEquals(1.25, snapshot.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(8.75, snapshot.etaSeconds ?: 0.0, 0.0001)
    }

    @Test
    fun `falls back to wall clock timing when no rate token exists`() {
        val tracker = SdProgressTracker(totalStepsHint = 8, startedAtMs = 0L)

        val first = tracker.update("step 1/8", nowMs = 1000L)
        val second = tracker.update("step 2/8", nowMs = 2600L)

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(1.0, first.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(1.3, second.iterationSeconds ?: 0.0, 0.0001)
        assertEquals(7.8, second.etaSeconds ?: 0.0, 0.01)
    }

    @Test
    fun `returns null for non progress lines`() {
        val tracker = SdProgressTracker(totalStepsHint = 20, startedAtMs = 0L)

        val snapshot = tracker.update("loading vae weights", nowMs = 500L)

        assertNull(snapshot)
    }

    @Test
    fun `ignores progress from non configured step totals`() {
        val tracker = SdProgressTracker(totalStepsHint = 6, startedAtMs = 0L)

        val snapshot = tracker.update("| 20/1000 - 4.0 s/it", nowMs = 4000L)

        assertNull(snapshot)
    }

    @Test
    fun `eta ticks down between denoising steps`() {
        val tracker = SdProgressTracker(totalStepsHint = 6, startedAtMs = 0L)

        val first = tracker.update("| 1/6 - 279.88 s/it", nowMs = 279_880L)
        val ticked = tracker.tick(nowMs = 289_880L)

        requireNotNull(first)
        requireNotNull(ticked)
        assertEquals(1_399.4, first.etaSeconds ?: 0.0, 0.001)
        assertEquals(1_389.4, ticked.etaSeconds ?: 0.0, 0.001)
    }

    @Test
    fun `vae progress uses its own ETA phase when emitted`() {
        val tracker = SdProgressTracker(totalStepsHint = 6, startedAtMs = 0L)

        val diffusion = tracker.update("| 6/6 - 10.0 s/it", nowMs = 60_000L)
        val vae = tracker.update("VAE decoding | 1/4 - 2.0 s/it", nowMs = 62_000L)
        val ticked = tracker.tick(nowMs = 63_000L)

        requireNotNull(diffusion)
        requireNotNull(vae)
        requireNotNull(ticked)
        assertEquals(SdProgressPhase.DIFFUSION, diffusion.phase)
        assertEquals(SdProgressPhase.VAE, vae.phase)
        assertEquals(6.0, vae.etaSeconds ?: 0.0, 0.0001)
        assertEquals(5.0, ticked.etaSeconds ?: 0.0, 0.0001)
        assertEquals(SdProgressPhase.VAE, ticked.phase)
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
    fun `starting snapshot carries zero progress and status`() {
        val snapshot = SdProgressTracker.buildStartingSnapshot(
            totalSteps = 18,
            statusText = "Starting, calculating ETA"
        )

        assertEquals(0, snapshot.currentStep)
        assertEquals(18, snapshot.totalSteps)
        assertEquals(0f, snapshot.progress, 0.0f)
        assertNull(snapshot.etaSeconds)
        assertTrue(snapshot.statusText.isNotBlank())
    }
}
