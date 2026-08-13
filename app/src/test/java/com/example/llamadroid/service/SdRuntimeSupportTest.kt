package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SdRuntimeSupportTest {
    @Test
    fun `i8mm stable diffusion binary maps to i8mm companion tier`() {
        assertEquals("_i8mm", inferSdRuntimeTierSuffix("libsd_i8mm.so"))
    }

    @Test
    fun `all stable diffusion binary tiers retain their suffix`() {
        assertEquals("_baseline", inferSdRuntimeTierSuffix("libsd_baseline.so"))
        assertEquals("_dotprod", inferSdRuntimeTierSuffix("libsd_dotprod.so"))
        assertEquals("_armv9", inferSdRuntimeTierSuffix("libsd_armv9.so"))
        assertEquals("_snapdragon_vulkan", inferSdRuntimeTierSuffix("libsd_snapdragon_vulkan.so"))
        assertEquals("_snapdragon_opencl", inferSdRuntimeTierSuffix("libsd_snapdragon_opencl.so"))
    }
}
