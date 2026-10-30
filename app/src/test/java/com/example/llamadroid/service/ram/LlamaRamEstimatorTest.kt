package com.example.llamadroid.service.ram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaRamEstimatorTest {
    private val gib = 1024L * 1024L * 1024L
    private val mib = 1024L * 1024L

    private val denseShape = LlamaModelShape(layers = 32, kvHeads = 8.0, headDimK = 128, headDimV = 128)

    private fun inputs(
        fileBytes: Long = 10 * gib,
        context: Int = 8192,
        slots: Int = 1,
        k: String = "f16",
        v: String = "f16",
        resident: Boolean = false,
        swaFull: Boolean = false,
        shape: LlamaModelShape? = denseShape,
        mmproj: Long = 0L
    ) = LlamaRamInputs(
        modelFileBytes = fileBytes, mmprojBytes = mmproj, totalContext = context, slots = slots,
        kvTypeK = k, kvTypeV = v, fullyResident = resident, swaFull = swaFull, shape = shape
    )

    @Test fun mmapCountsTheWholeFile() {
        val estimate = LlamaRamEstimator.estimate(inputs())
        assertEquals(10 * gib, estimate.weightsBytes)
        assertEquals(0L, estimate.pageableWeightBytes)
    }

    @Test fun mlockOrNoMmapCountsTheWholeFile() {
        val estimate = LlamaRamEstimator.estimate(inputs(resident = true))
        assertEquals(10 * gib, estimate.weightsBytes)
        assertEquals(0L, estimate.pageableWeightBytes)
    }

    @Test fun kvCacheFollowsContextLayersHeadsAndType() {
        // 32 layers x 8 heads x (128 + 128) x 2 bytes = 128 KiB per token; 8192 tokens = 1 GiB.
        assertEquals(1 * gib, LlamaRamEstimator.estimate(inputs()).kvBytes)
        assertEquals(2 * gib, LlamaRamEstimator.estimate(inputs(context = 16384)).kvBytes)
        // q8_0 = 34/32 bytes per element on both K and V.
        assertEquals(570_425_344L, LlamaRamEstimator.estimate(inputs(k = "q8_0", v = "q8_0")).kvBytes)
        // Mixed: K q4_0 (18/32), V f16.
        val mixed = LlamaRamEstimator.estimate(inputs(k = "q4_0", v = "f16")).kvBytes
        assertEquals((8192L * 32 * 8 * (128 * 18.0 / 32.0 + 128 * 2.0)).toLong(), mixed)
    }

    @Test fun slotsDoNotMultiplyTheUnifiedContext() {
        val one = LlamaRamEstimator.estimate(inputs(slots = 1)).kvBytes
        val four = LlamaRamEstimator.estimate(inputs(slots = 4)).kvBytes
        assertEquals(one, four)
    }

    @Test fun slidingWindowLayersOnlyNeedTheWindowUnlessSwaFull() {
        val swa = LlamaModelShape(
            layers = 10, kvHeads = 1.0, headDimK = 256, headDimV = 256,
            slidingWindow = 512, swaLayerFraction = 0.5
        )
        // per token per layer = 1 x (256 + 256) x 2 = 1024 bytes
        val windowed = LlamaRamEstimator.estimate(inputs(shape = swa)).kvBytes
        assertEquals(1024L * 10 * 4608, windowed) // 0.5 x 8192 + 0.5 x (512 x 1 + 512)
        val full = LlamaRamEstimator.estimate(inputs(shape = swa, swaFull = true)).kvBytes
        assertEquals(1024L * 10 * 8192, full)
        assertTrue(windowed < full)
        // More slots widen the window cache: 0.5 x 8192 + 0.5 x (512 x 4 + 512).
        val fourSlots = LlamaRamEstimator.estimate(inputs(shape = swa, slots = 4)).kvBytes
        assertEquals(1024L * 10 * 5376, fourSlots)
    }

    @Test fun unknownShapeUsesAConservativeHeuristic() {
        val estimate = LlamaRamEstimator.estimate(inputs(fileBytes = 4 * gib, context = 4096, shape = null))
        assertFalse(estimate.usedShape)
        // 40 KiB per GB of file = 160 KiB per token at f16.
        assertEquals(160L * 1024L * 4096L, estimate.kvBytes)
        val quantized = LlamaRamEstimator.estimate(
            inputs(fileBytes = 4 * gib, context = 4096, shape = null, k = "q4_0", v = "q4_0")
        )
        assertTrue(quantized.kvBytes < estimate.kvBytes / 3)
    }

    @Test fun totalAddsMmprojAndComputeBuffer() {
        val estimate = LlamaRamEstimator.estimate(inputs(mmproj = 600 * mib))
        assertEquals(600 * mib, estimate.mmprojBytes)
        assertEquals(
            estimate.weightsBytes + estimate.kvBytes + 600 * mib + LlamaRamEstimator.COMPUTE_BUFFER_BYTES,
            estimate.totalBytes
        )
    }

    @Test fun decisionNeedsMarginAndRejectsLowMemory() {
        val estimate = LlamaRamEstimator.estimate(inputs())
        val needed = estimate.totalBytes + LlamaRamEstimator.SAFETY_MARGIN_BYTES
        assertTrue(LlamaRamEstimator.decide(estimate, needed, lowMemory = false).ok)
        assertFalse(LlamaRamEstimator.decide(estimate, needed - 1, lowMemory = false).ok)
        assertFalse(LlamaRamEstimator.decide(estimate, needed * 2, lowMemory = true).ok)
    }

    @Test fun reclaimableCacheGivesNoCredit() {
        val mmap = LlamaRamEstimator.estimate(inputs())
        val avail = mmap.totalBytes + LlamaRamEstimator.SAFETY_MARGIN_BYTES - 500 * mib
        assertFalse(LlamaRamEstimator.decide(mmap, avail, false, reclaimableCacheBytes = 4 * gib).ok)
    }

    @Test fun kvTypeSizesMatchGgmlBlocks() {
        assertEquals(2.0, LlamaRamEstimator.kvBytesPerElement("f16"), 0.0)
        assertEquals(4.0, LlamaRamEstimator.kvBytesPerElement("F32"), 0.0)
        assertEquals(34.0 / 32.0, LlamaRamEstimator.kvBytesPerElement("q8_0"), 1e-9)
        assertEquals(18.0 / 32.0, LlamaRamEstimator.kvBytesPerElement("q4_0"), 1e-9)
        assertEquals(2.0, LlamaRamEstimator.kvBytesPerElement("unknown"), 0.0)
    }
}
