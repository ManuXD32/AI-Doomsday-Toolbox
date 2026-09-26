package com.example.llamadroid.ui.agent.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessDraftProviderDiscoveryTest {
    @Test
    fun `keyless draft discovers models without requiring a fake model`() = runBlocking {
        val calls = mutableListOf<String>()
        val request = NativeHarnessCustomProviderRequest(
            route = "",
            api = "openai-completions",
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "",
            models = emptyList(),
        )

        val models = NativeHarnessDraftProviderDiscovery.discover(
            request,
            NativeHarnessDraftDiscoveryTransport { url, captured ->
                calls += url
                assertEquals("", captured.apiKey)
                if (url.endsWith("/props")) {
                    NativeHarnessDraftHttpResponse(404, null)
                } else {
                    NativeHarnessDraftHttpResponse(
                        statusCode = 200,
                        body = """{"data":[{"id":"local-model","context_window":32768}]}""",
                    )
                }
            },
        )

        assertEquals(
            listOf("http://127.0.0.1:8080/v1/models", "http://127.0.0.1:8080/props"),
            calls,
        )
        assertEquals(listOf("local-model"), models.map { it.id })
        assertEquals(32768L, models.single().contextWindow)
    }

    @Test
    fun `props metadata enriches only the matching model in a multi-model catalog`() = runBlocking {
        val calls = mutableListOf<String>()
        val models = NativeHarnessDraftProviderDiscovery.discover(
            NativeHarnessCustomProviderRequest(
                route = "",
                api = "openai-completions",
                baseUrl = "http://localhost:8080/v1",
                models = emptyList(),
            ),
            NativeHarnessDraftDiscoveryTransport { url, _ ->
                calls += url
                if (url.endsWith("/models")) {
                    NativeHarnessDraftHttpResponse(
                        200,
                        """{"data":[{"id":"/models/alpha.gguf"},{"id":"/models/beta.gguf"}]}""",
                    )
                } else {
                    NativeHarnessDraftHttpResponse(
                        200,
                        """{"model_alias":"beta.gguf","n_ctx":8192,"n_predict":512}""",
                    )
                }
            },
        )

        assertEquals(
            listOf("http://localhost:8080/v1/models", "http://localhost:8080/props"),
            calls,
        )
        assertEquals(listOf("/models/alpha.gguf", "/models/beta.gguf"), models.map { it.id })
        assertEquals(
            listOf("alpha.gguf", "beta.gguf"),
            models.map { it.name ?: it.id },
        )
        assertEquals(null, models[0].contextWindow)
        assertEquals(8192L, models[1].contextWindow)
        assertEquals(512L, models[1].maxTokens)
    }

    @Test
    fun `props metadata does not move across ambiguous model basenames`() = runBlocking {
        val models = NativeHarnessDraftProviderDiscovery.discover(
            NativeHarnessCustomProviderRequest(
                route = "",
                api = "openai-completions",
                baseUrl = "http://localhost:8080/v1",
                models = emptyList(),
            ),
            NativeHarnessDraftDiscoveryTransport { url, _ ->
                NativeHarnessDraftHttpResponse(
                    200,
                    if (url.endsWith("/models")) {
                        """{"data":[{"id":"/a/model.gguf"},{"id":"/b/model.gguf"}]}"""
                    } else {
                        """{"model_alias":"model.gguf","n_ctx":8192,"n_predict":512}"""
                    },
                )
            },
        )

        assertEquals(listOf("/a/model.gguf", "/b/model.gguf"), models.map { it.id })
        assertEquals(2, models.map { it.name }.distinct().size)
        assertTrue(models.all { it.name?.matches(Regex("model\\.gguf \\([0-9a-f]{8}\\)")) == true })
        assertEquals(null, models[0].contextWindow)
        assertEquals(null, models[1].contextWindow)
        assertEquals(null, models[0].maxTokens)
        assertEquals(null, models[1].maxTokens)
    }

    @Test
    fun `a unique model can inherit props metadata when the alias is absent`() = runBlocking {
        val models = NativeHarnessDraftProviderDiscovery.discover(
            NativeHarnessCustomProviderRequest(
                route = "",
                api = "openai-completions",
                baseUrl = "http://localhost:8080/v1",
                models = emptyList(),
            ),
            NativeHarnessDraftDiscoveryTransport { url, _ ->
                NativeHarnessDraftHttpResponse(
                    200,
                    if (url.endsWith("/models")) {
                        """{"data":[{"id":"only-model"}]}"""
                    } else {
                        """{"n_ctx":4096,"n_predict":256}"""
                    },
                )
            },
        )

        assertEquals(4096L, models.single().contextWindow)
        assertEquals(256L, models.single().maxTokens)
    }

    @Test
    fun `missing models endpoint falls back to llama props`() = runBlocking {
        val calls = mutableListOf<String>()
        val request = NativeHarnessCustomProviderRequest(
            route = "",
            api = "openai-completions",
            baseUrl = "http://localhost:8080",
            models = emptyList(),
        )

        val models = NativeHarnessDraftProviderDiscovery.discover(
            request,
            NativeHarnessDraftDiscoveryTransport { url, _ ->
                calls += url
                if (url.endsWith("/models")) {
                    NativeHarnessDraftHttpResponse(404, null)
                } else {
                    NativeHarnessDraftHttpResponse(
                        200,
                        """{"model_alias":"llama-local","n_ctx":4096,"n_predict":512}""",
                    )
                }
            },
        )

        assertEquals(
            listOf("http://localhost:8080/v1/models", "http://localhost:8080/props"),
            calls,
        )
        assertEquals("llama-local", models.single().id)
        assertEquals(4096L, models.single().contextWindow)
        assertEquals(512L, models.single().maxTokens)
    }

    @Test
    fun `both missing discovery endpoints report unsupported instead of empty`() = runBlocking {
        val error = runCatching {
            NativeHarnessDraftProviderDiscovery.discover(
                NativeHarnessCustomProviderRequest(
                    route = "",
                    api = "openai-completions",
                    baseUrl = "http://localhost:8080/v1",
                    models = emptyList(),
                ),
                NativeHarnessDraftDiscoveryTransport { _, _ ->
                    NativeHarnessDraftHttpResponse(404, null)
                },
            )
        }.exceptionOrNull()

        assertTrue(error is NativeHarnessDraftDiscoveryException)
        assertEquals("PROVIDER_DISCOVERY_UNSUPPORTED", (error as NativeHarnessDraftDiscoveryException).code)
    }

    @Test
    fun `an explicit empty models inventory remains a valid empty result`() = runBlocking {
        val models = NativeHarnessDraftProviderDiscovery.discover(
            NativeHarnessCustomProviderRequest(
                route = "",
                api = "openai-completions",
                baseUrl = "http://localhost:8080/v1",
                models = emptyList(),
            ),
            NativeHarnessDraftDiscoveryTransport { url, _ ->
                NativeHarnessDraftHttpResponse(
                    if (url.endsWith("/models")) 200 else 404,
                    if (url.endsWith("/models")) """{"data":[]}""" else null,
                )
            },
        )

        assertTrue(models.isEmpty())
    }

    @Test
    fun `auth failures are surfaced instead of becoming an empty model list`() = runBlocking {
        val error = runCatching {
            NativeHarnessDraftProviderDiscovery.discover(
                NativeHarnessCustomProviderRequest(
                    route = "",
                    api = "openai-completions",
                    baseUrl = "https://provider.example/v1",
                    models = emptyList(),
                ),
                NativeHarnessDraftDiscoveryTransport { _, _ ->
                    NativeHarnessDraftHttpResponse(401, "{}")
                },
            )
        }.exceptionOrNull()

        assertTrue(error is NativeHarnessDraftDiscoveryException)
        assertEquals("PROVIDER_DISCOVERY_UNAUTHORIZED", (error as NativeHarnessDraftDiscoveryException).code)
    }

    @Test
    fun `oversized discovery body is rejected before JSON parsing`() = runBlocking {
        val error = runCatching {
            NativeHarnessDraftProviderDiscovery.discover(
                NativeHarnessCustomProviderRequest(
                    route = "",
                    api = "openai-completions",
                    baseUrl = "https://provider.example/v1",
                    models = emptyList(),
                ),
                NativeHarnessDraftDiscoveryTransport { _, _ ->
                    NativeHarnessDraftHttpResponse(200, "{" + "x".repeat(2 * 1024 * 1024) + "}")
                },
            )
        }.exceptionOrNull()

        assertTrue(error is NativeHarnessDraftDiscoveryException)
        assertEquals("PROVIDER_DISCOVERY_TOO_LARGE", (error as NativeHarnessDraftDiscoveryException).code)
    }
}
