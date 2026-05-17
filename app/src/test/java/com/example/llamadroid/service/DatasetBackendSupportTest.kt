package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.DatasetProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetBackendSupportTest {
    @Test
    fun `normalizeDatasetBackend defaults unknown values to llama-server`() {
        assertEquals(
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
            normalizeDatasetBackend("something-else")
        )
        assertEquals(
            SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
            normalizeDatasetBackend(null)
        )
        assertEquals(
            SettingsRepository.PDF_BACKEND_OLLAMA,
            normalizeDatasetBackend(SettingsRepository.PDF_BACKEND_OLLAMA)
        )
        assertEquals(
            SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
            normalizeDatasetBackend("llama_swap")
        )
    }

    @Test
    fun `buildDatasetOllamaRequestPayload maps project runtime options`() {
        val project = DatasetProjectEntity(
            name = "Dataset",
            backend = SettingsRepository.PDF_BACKEND_OLLAMA,
            ollamaModel = "qwen3:8b",
            ollamaNumCtx = 8192,
            ollamaThreads = 6,
            ollamaMmap = true
        )

        val payload = buildDatasetOllamaRequestPayload(
            project = project,
            prompt = "Clean this chunk",
            maxTokens = 768,
            temperature = 0.35f,
            stop = listOf("\n")
        )

        assertEquals("qwen3:8b", payload["model"])
        assertEquals(false, payload["stream"])
        assertEquals(false, payload["think"])
        assertEquals(
            listOf(mapOf("role" to "user", "content" to "Clean this chunk")),
            payload["messages"]
        )

        val options = payload["options"] as Map<*, *>
        assertEquals(8192, options["num_ctx"])
        assertEquals(6, options["num_thread"])
        assertEquals(true, options["use_mmap"])
        assertEquals(768, options["num_predict"])
        val temperature = (options["temperature"] as Number).toDouble()
        assertTrue(kotlin.math.abs(temperature - 0.35) < 0.0001)
        assertEquals(listOf("\n"), options["stop"])
    }

    @Test
    fun `extractDatasetQuestionLine keeps only first non-empty line`() {
        val extracted = extractDatasetQuestionLine("\n\nWhat is the main idea?\nA second line")

        assertEquals("What is the main idea?", extracted)
    }

    @Test
    fun `buildDatasetLlamaSwapRequestPayload sends selected model through chat completions`() {
        val project = DatasetProjectEntity(
            name = "Dataset",
            backend = SettingsRepository.PDF_BACKEND_LLAMA_SWAP,
            serverUrl = "http://localhost:9292",
            ollamaModel = "swap-qwen"
        )

        val payload = buildDatasetLlamaSwapRequestPayload(
            project = project,
            prompt = "Answer this",
            maxTokens = 320,
            temperature = 0.45f,
            stop = listOf("</s>")
        )

        assertEquals("swap-qwen", payload["model"])
        assertEquals(false, payload["stream"])
        assertEquals(320, payload["max_tokens"])
        val messages = payload["messages"] as List<*>
        assertEquals(mapOf("role" to "user", "content" to "Answer this"), messages.first())
        assertEquals(listOf("</s>"), payload["stop"])
    }
}
