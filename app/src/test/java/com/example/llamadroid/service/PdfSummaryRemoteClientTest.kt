package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSummaryRemoteClientTest {
    @Test
    fun `buildOllamaSummaryRequestJson maps remote settings to native ollama fields`() {
        val config = RemoteSummaryBackendConfig(
            backend = "ollama",
            baseUrl = "http://localhost:11434",
            model = "qwen3:8b",
            timeoutMinutes = 0
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 8192,
            maxTokens = 1024,
            temperature = 0.2f,
            thinkingEnabled = false
        )

        val payload = buildOllamaSummaryRequestPayload(config, request)

        assertEquals("qwen3:8b", payload["model"])
        assertEquals(false, payload["think"])
        assertEquals(2, (payload["messages"] as List<*>).size)
        val options = payload["options"] as Map<*, *>
        assertEquals(8192, options["num_ctx"])
        assertEquals(1024, options["num_predict"])
    }

    @Test
    fun `buildLlamaServerSummaryRequestJson disables reasoning by default`() {
        val config = RemoteSummaryBackendConfig(
            backend = "llama-server",
            baseUrl = "http://localhost:8080",
            model = null,
            timeoutMinutes = 10
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 4096,
            maxTokens = 768,
            temperature = 0.4f,
            thinkingEnabled = false
        )

        val payload = buildLlamaServerSummaryRequestPayload(config, request)

        assertEquals("local-model", payload["model"])
        assertEquals(768, payload["max_tokens"])
        assertEquals("none", payload["reasoning_effort"])
        assertEquals(false, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
    }

    @Test
    fun `ollama payload adds image attachments to user message`() {
        val payload = buildOllamaSummaryRequestPayload(
            RemoteSummaryBackendConfig("ollama", "http://localhost:11434", "vision", 0),
            RemoteSummaryRequest(
                systemPrompt = "system",
                userPrompt = "user",
                contextSize = 4096,
                maxTokens = 256,
                temperature = 0.1f,
                thinkingEnabled = false,
                imageAttachments = listOf(RemoteSummaryImageAttachment("abc123"))
            )
        )

        val userMessage = (payload["messages"] as List<*>)[1] as Map<*, *>
        assertEquals(listOf("abc123"), userMessage["images"])
    }

    @Test
    fun `openai compatible payload adds image url content parts`() {
        val payload = buildLlamaServerSummaryRequestPayload(
            RemoteSummaryBackendConfig("llama-server", "http://localhost:8080", "vision", 0),
            RemoteSummaryRequest(
                systemPrompt = "system",
                userPrompt = "user",
                contextSize = 4096,
                maxTokens = 256,
                temperature = 0.1f,
                thinkingEnabled = false,
                imageAttachments = listOf(RemoteSummaryImageAttachment("abc123", "image/png"))
            )
        )

        val userMessage = (payload["messages"] as List<*>)[1] as Map<*, *>
        val content = userMessage["content"] as List<*>
        assertEquals("text", (content[0] as Map<*, *>)["type"])
        assertEquals("image_url", (content[1] as Map<*, *>)["type"])
        val imageUrl = ((content[1] as Map<*, *>)["image_url"] as Map<*, *>)["url"] as String
        assertTrue(imageUrl.startsWith("data:image/png;base64,abc123"))
    }

    @Test
    fun `llama multimodal completion pairs one marker with each bitmap`() {
        val payload = buildLlamaServerMultimodalCompletionRequestPayload(
            RemoteSummaryRequest(
                systemPrompt = "OCR only",
                userPrompt = "document parsing.",
                contextSize = 4096,
                maxTokens = 512,
                temperature = 0f,
                thinkingEnabled = false,
                imageAttachments = listOf(
                    RemoteSummaryImageAttachment("first"),
                    RemoteSummaryImageAttachment("second")
                ),
                preferLlamaMultimodalCompletion = true
            )
        )

        val prompt = payload["prompt"] as Map<*, *>
        val promptString = prompt["prompt_string"] as String
        assertEquals(2, Regex("<__media__>").findAll(promptString).count())
        assertEquals(listOf("first", "second"), prompt["multimodal_data"])
        assertEquals(512, payload["n_predict"])
    }

    @Test
    fun `llama multimodal completion uses resolved server marker`() {
        val payload = buildLlamaServerMultimodalCompletionRequestPayload(
            RemoteSummaryRequest(
                systemPrompt = "",
                userPrompt = "document parsing.",
                contextSize = 4096,
                maxTokens = 128,
                temperature = 0f,
                thinkingEnabled = false,
                imageAttachments = listOf(RemoteSummaryImageAttachment("image")),
                preferLlamaMultimodalCompletion = true,
                allowBlankOutput = true
            ),
            mediaMarker = "<server-media>"
        )

        val prompt = payload["prompt"] as Map<*, *>
        val promptString = prompt["prompt_string"] as String
        assertEquals(1, Regex("<server-media>").findAll(promptString).count())
        assertEquals(listOf("image"), prompt["multimodal_data"])
    }

    @Test
    fun `remote summary requests remain strict on blank output by default`() {
        val request = RemoteSummaryRequest(
            systemPrompt = "",
            userPrompt = "translate",
            contextSize = 4096,
            maxTokens = 128,
            temperature = 0f,
            thinkingEnabled = false
        )

        assertFalse(request.allowBlankOutput)
    }


    @Test
    fun `parseLlamaServerContextTokens reads props response`() {
        val body = """
            {
              "default_generation_settings": {
                "n_ctx": 16384
              }
            }
        """.trimIndent()

        assertEquals(16384, parseLlamaServerContextTokens(body))
    }

    @Test
    fun `parseLlamaServerMediaMarker reads props marker`() {
        val body = """{"media_marker":"<server-media>"}"""

        assertEquals("<server-media>", parseLlamaServerMediaMarker(body))
    }

    @Test
    fun `parseLlamaServerBuildInfo reads props build info`() {
        val body = """{"build_info":"b1234-deadbeef"}"""

        assertEquals("b1234-deadbeef", parseLlamaServerBuildInfo(body))
    }

    @Test
    fun `parseLlamaServerMediaMarker returns null when absent`() {
        assertEquals(null, parseLlamaServerMediaMarker("""{"ok":true}"""))
    }

    @Test
    fun `parseLlamaServerVisionSupport reads modalities flag`() {
        assertEquals(true, parseLlamaServerVisionSupport("""{"modalities":{"vision":true}}"""))
        assertEquals(false, parseLlamaServerVisionSupport("""{"modalities":{"vision":false}}"""))
    }

    @Test
    fun `parseLlamaServerVisionSupport reads capability arrays conservatively`() {
        assertEquals(true, parseLlamaServerVisionSupport("""{"capabilities":["completion","multimodal"]}"""))
        assertEquals(null, parseLlamaServerVisionSupport("""{"capabilities":["completion"]}"""))
        assertEquals(false, parseLlamaServerVisionSupport("""{"multimodal":false}"""))
        assertEquals(null, parseLlamaServerVisionSupport("""{"ok":true}"""))
    }

    @Test
    fun `parseLlamaServerModelVisionSupport reads model endpoint capability fallback`() {
        assertEquals(
            true,
            parseLlamaServerModelVisionSupport(
                """{"data":[{"id":"ocr","capabilities":["completion","multimodal"]}]}"""
            )
        )
        assertEquals(
            false,
            parseLlamaServerModelVisionSupport(
                """{"data":[{"id":"text","modalities":{"vision":false}}]}"""
            )
        )
        assertEquals(
            null,
            parseLlamaServerModelVisionSupport(
                """{"data":[{"id":"unknown","capabilities":["completion"]}]}"""
            )
        )
    }

    @Test
    fun `parseLlamaServerContextTokens returns null for invalid props`() {
        assertEquals(null, parseLlamaServerContextTokens("""{"ok":true}"""))
    }

    @Test
    fun `buildLlamaServerSummaryRequestJson keeps thinking enabled when requested`() {
        val config = RemoteSummaryBackendConfig(
            backend = "llama-server",
            baseUrl = "http://localhost:8080",
            model = "served-model",
            timeoutMinutes = 0
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 4096,
            maxTokens = 512,
            temperature = 0.7f,
            thinkingEnabled = true
        )

        val payload = buildLlamaServerSummaryRequestPayload(config, request)

        assertNotNull(payload["chat_template_kwargs"])
        assertEquals(true, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
        assertNotNull(payload["messages"])
        assertFalse(payload.containsKey("reasoning_effort"))
    }

    @Test
    fun `buildLlamaSwapSummaryRequestJson uses selected swap model`() {
        val config = RemoteSummaryBackendConfig(
            backend = "llama-swap",
            baseUrl = "http://localhost:9292",
            model = "swap-qwen",
            timeoutMinutes = 0
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 4096,
            maxTokens = 256,
            temperature = 0.3f,
            thinkingEnabled = false
        )

        val payload = buildLlamaSwapSummaryRequestPayload(config, request)

        assertEquals("swap-qwen", payload["model"])
        assertEquals(false, payload["stream"])
        assertEquals(256, payload["max_tokens"])
        assertEquals("none", payload["reasoning_effort"])
    }

    @Test
    fun `parseOpenAiModelIds reads model list`() {
        val body = """{"data":[{"id":"qwen"},{"id":"mistral"}]}"""

        assertEquals(listOf("qwen", "mistral"), parseOpenAiModelIds(body))
    }
}
