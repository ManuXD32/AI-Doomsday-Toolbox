package com.example.llamadroid.harness

import com.example.llamadroid.ui.agent.harness.HarnessDiscoveredModelUi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HarnessProviderDiscoveryBridgeTest {
    @Test
    fun nestedWebUiRequestExtractsEphemeralProviderFieldsWithoutModels() {
        val request = parseHarnessProviderDiscoveryRequest(
            JSONObject().put("settingsNs", "llm-pi-ai").put(
                "request",
                JSONObject()
                    .put("provider", "local-route")
                    .put("api", "openai-completions")
                    .put("baseURL", "http://127.0.0.1:8080/v1")
                    .put("apiKey", "temporary-secret")
            )
        )

        assertEquals("openai-completions", request.api)
        assertEquals("http://127.0.0.1:8080/v1", request.baseUrl)
        assertEquals("temporary-secret", request.apiKey)
        assertEquals(0, request.models.size)
    }

    @Test
    fun discoveryResponseContainsMetadataButNeverTheCredential() {
        val response = harnessProviderDiscoveryResponse(
            listOf(
                HarnessDiscoveredModelUi(
                    id = "/models/local.gguf",
                    name = "Local",
                    contextWindow = 32768,
                    maxTokens = 4096,
                    inputModalities = listOf("text"),
                )
            )
        )

        assertEquals("ok", response.getString("status"))
        assertEquals("/models/local.gguf", response.getJSONArray("models").getJSONObject(0).getString("id"))
        assertFalse(response.toString().contains("temporary-secret"))
    }
}
