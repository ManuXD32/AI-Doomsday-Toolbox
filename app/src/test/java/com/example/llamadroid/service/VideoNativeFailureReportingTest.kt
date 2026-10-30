package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNativeFailureReportingTest {
    private fun tracked(vararg lines: String): VideoNativePhaseTracker =
        VideoNativePhaseTracker().also { t -> lines.forEach(t::update) }

    @Test
    fun phaseMovesFromDiffusionToDecodingAfterSamplingCompleted() {
        val tracker = tracked("  |==>  | 3/12 - 4.20s/it")
        assertEquals(SdProgressPhase.DIFFUSION, tracker.phase)
        tracker.update("[INFO ] sampling completed, taking 50.00s")
        assertEquals(SdProgressPhase.VAE_DECODING, tracker.phase)
        tracker.update("  |==>  | 1/2 - 1.0s/it")
        assertEquals(SdProgressPhase.VAE_DECODING, tracker.phase)
    }

    @Test
    fun latentVideoCompletedStartsDecoding() {
        val tracker = tracked("generating latent video completed, taking 12.00s")
        assertEquals(SdProgressPhase.VAE_DECODING, tracker.phase)
    }

    @Test
    fun abortDuringDecodeWithTaeIncludesHint() {
        val report = classifyVideoNativeFailure(
            134, listOf("GGML_ASSERT(a->ne[2] == b->ne[2]) failed"),
            SdProgressPhase.VAE_DECODING, acceleratorBinary = false
        )
        assertEquals(SdFailureCategory.NATIVE_ABORT, report.category)
        assertTrue(shouldAppendDecoderAbortHint(report, usedTae = true))
        assertFalse(shouldAppendDecoderAbortHint(report, usedTae = false))
    }

    @Test
    fun abortDuringDiffusionHasNoHint() {
        val report = classifyVideoNativeFailure(134, emptyList(), SdProgressPhase.DIFFUSION, false)
        assertFalse(shouldAppendDecoderAbortHint(report, usedTae = true))
    }

    @Test
    fun oomAndKilledClassificationPassThrough() {
        val oom = classifyVideoNativeFailure(1, listOf("ggml_backend_alloc failed: out of memory"), SdProgressPhase.VAE_DECODING, false)
        assertEquals(SdFailureCategory.OUT_OF_MEMORY, oom.category)
        assertFalse(shouldAppendDecoderAbortHint(oom, usedTae = true))
        val killed = classifyVideoNativeFailure(137, emptyList(), SdProgressPhase.DIFFUSION, false)
        assertEquals(SdFailureCategory.PROCESS_KILLED, killed.category)
    }
}
