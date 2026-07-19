package com.example.llamadroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryBackendTest {
    @Test
    fun `agent remote backend aliases normalize to llama-server`() {
        val aliases = listOf(
            "llama-server",
            "llama_server",
            "llamacpp",
            "llama.cpp",
            "llama-cpp",
            " LLAMA_SERVER "
        )

        aliases.forEach { backend ->
            assertEquals(
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
            )
            assertTrue(SettingsRepository.isLlamaServerBackend(backend))
        }
    }

    @Test
    fun `unknown remote backend values fall back to ollama`() {
        assertEquals(
            SettingsRepository.PDF_BACKEND_OLLAMA,
            SettingsRepository.normalizeOllamaOrLlamaBackend(null)
        )
        assertEquals(
            SettingsRepository.PDF_BACKEND_OLLAMA,
            SettingsRepository.normalizeOllamaOrLlamaBackend("something-else")
        )
        assertFalse(SettingsRepository.isLlamaServerBackend("something-else"))
    }

    @Test
    fun `llama swap aliases normalize to llama-swap`() {
        val aliases = listOf("llama-swap", "llama_swap", "llamaswap", " LLAMA_SWAP ")

        aliases.forEach { backend ->
            assertEquals(
                SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
                SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
            )
            assertTrue(SettingsRepository.isLlamaSwapBackend(backend))
            assertTrue(SettingsRepository.usesOpenAiChatBackend(backend))
            assertTrue(SettingsRepository.requiresSelectedRemoteModel(backend))
        }
    }

    @Test
    fun `litert aliases normalize to litert backend`() {
        val aliases = listOf("litert-lm", "litert", "litertlm", "lite-rt", "lite-rt-lm", " LITERT ")

        aliases.forEach { backend ->
            assertEquals(
                SettingsRepository.PDF_BACKEND_LITERT,
                SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
            )
            assertTrue(SettingsRepository.isLiteRtBackend(backend))
            assertFalse(SettingsRepository.usesOpenAiChatBackend(backend))
            assertFalse(SettingsRepository.requiresSelectedRemoteModel(backend))
        }
    }

    @Test
    fun `acceleration mode aliases normalize to runtime choices`() {
        assertEquals(SettingsRepository.ACCELERATION_AUTO, SettingsRepository.normalizeAccelerationMode(null))
        assertEquals(SettingsRepository.ACCELERATION_CPU, SettingsRepository.normalizeAccelerationMode("cpu_only"))
        assertEquals(SettingsRepository.ACCELERATION_GPU, SettingsRepository.normalizeAccelerationMode("OpenCL"))
        assertEquals(SettingsRepository.ACCELERATION_AUTO, SettingsRepository.normalizeAccelerationMode("vulkan"))
        assertEquals(SettingsRepository.ACCELERATION_AUTO, SettingsRepository.normalizeAccelerationMode("hexagon"))
        assertEquals(SettingsRepository.ACCELERATION_AUTO, SettingsRepository.normalizeAccelerationMode("unknown"))
    }

    @Test
    fun `stable diffusion acceleration mode does not expose npu`() {
        assertEquals(
            SettingsRepository.ACCELERATION_CPU,
            SettingsRepository.normalizeStableDiffusionAccelerationMode(SettingsRepository.ACCELERATION_NPU)
        )
        assertEquals(
            SettingsRepository.ACCELERATION_CPU,
            SettingsRepository.normalizeStableDiffusionAccelerationMode(SettingsRepository.ACCELERATION_CPU)
        )
    }

    @Test
    fun `native binary aliases normalize to explicit selections`() {
        assertEquals(
            SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL,
            SettingsRepository.normalizeLlmNativeBinarySelection("libllama_server_snapdragon_opencl.so")
        )
        assertEquals(
            SettingsRepository.NATIVE_BINARY_CPU_DOTPROD,
            SettingsRepository.normalizeLlmNativeBinarySelection("libllama_server_dotprod.so")
        )
        assertEquals(
            SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN,
            SettingsRepository.normalizeStableDiffusionNativeBinarySelection("vulkan")
        )
        assertEquals(
            SettingsRepository.NATIVE_BINARY_CPU_ARMV9,
            SettingsRepository.normalizeStableDiffusionNativeBinarySelection("libsd_armv9.so")
        )
        assertEquals(
            SettingsRepository.NATIVE_BINARY_AUTO,
            SettingsRepository.normalizeStableDiffusionNativeBinarySelection("opencl")
        )
    }
}
