package com.example.llamadroid.data.binary

import com.example.llamadroid.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryRepositoryTest {

    @Test
    fun buildBinarySearchTiers_prefersBaselineButFallsBackToInstalledDeviceTier() {
        assertEquals(
            listOf("baseline", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "dotprod")
        )
    }

    @Test
    fun buildBinarySearchTiers_keepsArmv9FallbackOrderWhenBaselineMissing() {
        assertEquals(
            listOf("baseline", "armv9", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "armv9")
        )
    }

    @Test
    fun buildBinarySearchTiers_preservesNormalDeviceTierFallbackChain() {
        assertEquals(
            listOf("armv9", "dotprod", "baseline"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "armv9", deviceTier = "armv9")
        )
    }

    @Test
    fun acceleratorLibNames_mapsOnlySupportedSnapdragonLlamaPayloads() {
        assertEquals(
            listOf("libllama_server_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama_server")
        )
        assertEquals(
            listOf("libllama-bench_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama-bench")
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("sd")
        )
    }

    @Test
    fun acceleratorLibNames_honorsUserAccelerationMode() {
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_CPU)
        )
        assertEquals(
            listOf("libllama_server_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_GPU)
        )
        assertEquals(
            listOf("libllama_server_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama_server", SettingsRepository.ACCELERATION_NPU)
        )
        assertEquals(
            listOf("libllama-bench_snapdragon_opencl.so"),
            BinaryRepository.acceleratorLibNames("llama-bench", SettingsRepository.ACCELERATION_NPU)
        )
        assertEquals(
            emptyList<String>(),
            BinaryRepository.acceleratorLibNames("sd", SettingsRepository.ACCELERATION_CPU)
        )
    }
}
