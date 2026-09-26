package com.example.llamadroid.service

import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtBackendPolicyTest {
    @Test
    fun autoChoosesCpuWhenGpuWouldReduceAdvertisedGemmaContext() {
        val result = resolveLiteRtBackend(
            model = gemma4(),
            requestedBackend = "auto",
            requestedContextTokens = 32_768,
            requestedOutputTokens = 8_096,
        )

        assertEquals(LITERT_BACKEND_CPU, result.effectiveBackend)
        assertEquals(32_768, result.contextTokens)
        assertEquals(8_096, result.outputTokens)
        assertTrue(result.autoChoseCpuForCapacity)
    }

    @Test
    fun autoKeepsGpuWhenBothRequestedLimitsFitItsSafeCeilings() {
        val result = resolveLiteRtBackend(
            model = gemma4(),
            requestedBackend = "auto",
            requestedContextTokens = 4_096,
            requestedOutputTokens = 1_024,
        )

        assertEquals(LITERT_BACKEND_GPU, result.effectiveBackend)
        assertEquals(4_096, result.contextTokens)
        assertEquals(1_024, result.outputTokens)
        assertFalse(result.gpuWouldReduceRequest)
    }

    @Test
    fun autoKeepsGpuWhenSmallerModelLimitAlreadyFitsGpuSafetyCeilings() {
        val result = resolveLiteRtBackend(
            model = gemma4().copy(maxContextTokens = 2_048),
            requestedBackend = "auto",
            requestedContextTokens = 32_768,
            requestedOutputTokens = 8_096,
        )

        assertEquals(LITERT_BACKEND_GPU, result.effectiveBackend)
        assertEquals(2_048, result.contextTokens)
        assertEquals(1_024, result.outputTokens)
        assertFalse(result.gpuWouldReduceRequest)
    }

    @Test
    fun forcedGpuReportsItsEffectiveSafetyLimits() {
        val result = resolveLiteRtBackend(
            model = gemma4(),
            requestedBackend = "gpu",
            requestedContextTokens = 32_768,
            requestedOutputTokens = 8_096,
        )

        assertEquals(LITERT_BACKEND_GPU, result.effectiveBackend)
        assertEquals(4_096, result.contextTokens)
        assertEquals(1_024, result.outputTokens)
        assertTrue(result.gpuWouldReduceRequest)
    }

    @Test
    fun perModelOutputMaximumCapsProviderRequestWhileSmallerRequestRemainsRespected() {
        assertEquals(2_048, resolveLiteRtRequestedOutputTokens(4_096, 2_048, 8_096))
        assertEquals(256, resolveLiteRtRequestedOutputTokens(256, 2_048, 8_096))
        assertEquals(1_024, resolveLiteRtRequestedOutputTokens(null, null, 1_024))
        assertEquals(null, resolveLiteRtRequestedOutputTokens(null, null, -1))
    }

    @Test
    fun cpuHonorsSmallerAdvertisedModelLimit() {
        val model = gemma4().copy(maxContextTokens = 16_384)

        val result = resolveLiteRtBackend(
            model = model,
            requestedBackend = "cpu",
            requestedContextTokens = 32_768,
            requestedOutputTokens = 8_096,
        )

        assertEquals(LITERT_BACKEND_CPU, result.effectiveBackend)
        assertEquals(16_384, result.contextTokens)
        assertEquals(8_096, result.outputTokens)
    }

    private fun gemma4() = LiteRtModelEntity(
        id = 7L,
        displayName = "Gemma 4 E2B",
        path = "/models/gemma-4.task",
        filename = "gemma-4.task",
        maxContextTokens = 32_768,
    )
}
