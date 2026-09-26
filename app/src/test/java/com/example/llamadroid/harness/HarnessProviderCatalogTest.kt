package com.example.llamadroid.harness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProviderCatalogTest {
    @Test
    fun `endpoint accepts root or v1 base and preserves proxy prefix`() {
        val root = normalizeHarnessProviderEndpoint("https://example.test/proxy")
        val v1 = normalizeHarnessProviderEndpoint("https://example.test/proxy/v1/")
        assertEquals(root, v1)
        assertEquals("https://example.test/proxy", root?.rootUrl)
        assertEquals("https://example.test/proxy/v1/models", root?.modelsUrl)
        assertEquals("https://example.test/proxy/v1/chat/completions", root?.chatCompletionsUrl)
    }

    @Test
    fun `endpoint rejects embedded credentials`() {
        assertNull(normalizeHarnessProviderEndpoint("https://user:secret@example.test"))
    }

    @Test
    fun `model ids remain exact while labels use basename`() {
        val models = parseHarnessOpenAiModels(JSONObject("""
            {"data":[{"id":"/models/DeepSeek-R1.gguf"},{"id":"/models/DeepSeek-R1.gguf"}]}
        """))
        assertEquals(1, models.size)
        assertEquals("/models/DeepSeek-R1.gguf", models.single().wireId)
        assertEquals("DeepSeek-R1.gguf", models.single().displayName)
    }

    @Test
    fun `duplicate basename labels keep short distinct labels and exact wire ids`() {
        val models = parseHarnessOpenAiModels(JSONObject("""
            {"data":[{"id":"/a/model.gguf"},{"id":"/b/model.gguf"}]}
        """))

        assertEquals(listOf("/a/model.gguf", "/b/model.gguf"), models.map { it.wireId })
        assertEquals(2, models.map { it.displayName }.distinct().size)
        models.forEach { model ->
            org.junit.Assert.assertTrue(model.displayName.matches(Regex("model\\.gguf \\([0-9a-f]{8}\\)")))
        }
    }

    @Test
    fun `discovery projects context and output limits without changing wire ids`() {
        val models = parseHarnessOpenAiModels(JSONObject("""
            {"data":[{"id":"/models/local.gguf","name":"/models/local.gguf",
              "context_length":32768,"max_output_tokens":4096}]}
        """))
        assertEquals("/models/local.gguf", models.single().wireId)
        assertEquals("local.gguf", models.single().displayName)
        assertEquals(32768, models.single().contextLength)
        assertEquals(4096, models.single().maxTokens)
    }

    @Test
    fun `llama props include output limit when advertised`() {
        val capabilities = parseHarnessLlamaCapabilities(
            JSONObject("""{"model_alias":"local","n_ctx":16384,"n_predict":2048}"""),
            null,
        )
        assertEquals(16384, capabilities.contextLength)
        assertEquals(2048, capabilities.maxTokens)
    }

    @Test
    fun `swap discovery keeps unloaded configured models and marks running metadata`() {
        val models = parseHarnessLlamaSwapModels(
            JSONObject("""{"data":[{"id":"loaded"},{"id":"selected"},{"id":"cold"}]}"""),
            JSONObject("""{"models":[{"id":"loaded"}]}""")
        )
        assertEquals(listOf("loaded", "selected", "cold"), models.map { it.wireId })
        assertTrue(models.single { it.wireId == "loaded" }.running)
        assertTrue(models.any { it.wireId == "cold" })
    }

    @Test
    fun `swap parser does not treat upstream proxy payload as a registry`() {
        val models = parseHarnessLlamaSwapModels(
            JSONObject("""{"upstream":[{"id":"should-not-be-enumerated"}]}"""),
            null
        )
        assertTrue(models.isEmpty())
    }

    @Test
    fun `generation activity keeps unknown waiting honest`() {
        val waiting = HarnessGenerationActivity(
            requestId = "request",
            sessionId = "session",
            attemptId = "attempt",
            model = "local",
            phase = "waiting",
            startedAtMs = 1L,
            startedAtElapsedMs = 1L,
            updatedAtMs = 1L,
        )
        assertNull(waiting.fraction)
        assertEquals(
            0.5f,
            waiting.copy(phase = "prefill", known = true, total = 20, processed = 10).fraction ?: -1f,
            0.0001f,
        )
    }
}
