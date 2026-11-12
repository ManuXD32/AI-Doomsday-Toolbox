package com.example.llamadroid.sd

import com.example.llamadroid.service.ram.RamSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdRamGateTest {
    private fun snap(availGb: Double, cacheGb: Double = 0.0) =
        RamSnapshot((availGb * 1e9).toLong(), false, (cacheGb * 1e9).toLong())

    @Test
    fun sd15Q4FitsAt512OnFourGbDevice() {
        // ~0.55 GB Q4 weights + 0.6 GB working set against ~1.8 GB free.
        val decision = SdRamGate.decide(550_000_000L, SdRamClass.SD15, 512, 512, snap(1.8))
        assertEquals(SdRamDecision.Fits, decision)
    }

    @Test
    fun sdxlIsRefusedOnFourGbDevice() {
        val decision = SdRamGate.decide(3_500_000_000L, SdRamClass.SDXL, 512, 512, snap(1.8))
        assertTrue(decision is SdRamDecision.Refuse)
        decision as SdRamDecision.Refuse
        assertEquals(4_500_000_000L, decision.neededBytes)
    }

    @Test
    fun largeCanvasDownscalesTo512WithTiling() {
        // 1.0 GB weights: 768 needs 2.8 GB (> 2.0 free), 512 needs 2.0 GB.
        val decision = SdRamGate.decide(1_000_000_000L, SdRamClass.SDXL, 768, 768, snap(2.0))
        assertEquals(SdRamDecision.Downscale(512, 512, vaeTiling = true), decision)
    }

    @Test
    fun reclaimableCacheCountsAsAvailable() {
        val decision = SdRamGate.decide(1_000_000_000L, SdRamClass.SD15, 512, 512, snap(0.8, cacheGb = 1.0))
        assertEquals(SdRamDecision.Fits, decision)
    }

    @Test
    fun alreadySmallCanvasIsRefusedNotDownscaled() {
        val decision = SdRamGate.decide(5_000_000_000L, SdRamClass.FLUX, 512, 512, snap(2.0))
        assertTrue(decision is SdRamDecision.Refuse)
    }

    @Test
    fun workingSetTableMatchesSpec() {
        assertEquals(600_000_000L, SdRamGate.workingSetBytes(SdRamClass.SD15, 512, 512))
        assertEquals(1_100_000_000L, SdRamGate.workingSetBytes(SdRamClass.SD15, 512, 768))
        assertEquals(1_000_000_000L, SdRamGate.workingSetBytes(SdRamClass.SDXL, 512, 512))
        assertEquals(1_800_000_000L, SdRamGate.workingSetBytes(SdRamClass.SDXL, 1024, 1024))
        assertEquals(1_500_000_000L, SdRamGate.workingSetBytes(SdRamClass.FLUX, 512, 512))
        assertEquals(3_000_000_000L, SdRamGate.workingSetBytes(SdRamClass.FLUX, 1024, 1024))
    }
}
