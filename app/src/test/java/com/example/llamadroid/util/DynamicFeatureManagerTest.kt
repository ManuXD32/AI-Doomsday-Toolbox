package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DynamicFeatureManagerTest {
    @Test
    fun getLlmCpuModuleForTier_mapsInstallableCpuSplits() {
        assertEquals("feature_llm_baseline", DynamicFeatureManager.getLlmCpuModuleForTier("baseline"))
        assertEquals("feature_llm_dotprod", DynamicFeatureManager.getLlmCpuModuleForTier("dotprod"))
        assertEquals("feature_llm_armv9", DynamicFeatureManager.getLlmCpuModuleForTier("armv9"))
        assertEquals(DynamicFeatureManager.MODULE_LLM_I8MM, DynamicFeatureManager.getLlmCpuModuleForTier("i8mm"))
        assertNull(DynamicFeatureManager.getLlmCpuModuleForTier("snapdragon_opencl"))
    }
}
