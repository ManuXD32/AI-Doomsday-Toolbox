package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.ui.agent.harness.HarnessProviderOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DelegateHarnessRoutingTest {
    private fun server(
        id: Long = 7, engine: String = LlamaServerEntity.ENGINE_LLAMA_SERVER, host: String = "192.168.1.5",
        port: Int = 8080, modelName: String? = null, liteRt: Long? = null, card: Long? = null,
    ) = LlamaServerEntity(id = id, name = "s", host = host, port = port, engine = engine,
        modelName = modelName, liteRtModelId = liteRt, managedServerCardId = card)

    @Test fun routeNamesMatchTheProviderRoutePattern() {
        val pattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
        assertEquals("adt-chat-srv-12", DelegateHarnessRouting.customRouteName(12))
        assertTrue(pattern.matches(DelegateHarnessRouting.customRouteName(12)))
        assertTrue(pattern.matches(DelegateHarnessRouting.customRouteName(-5)))
    }

    @Test fun baseUrlEndsWithV1() {
        assertEquals("http://192.168.1.5:8080/v1", DelegateHarnessRouting.customBaseUrl("192.168.1.5", 8080))
    }

    @Test fun planPicksRouteKind() {
        assertEquals(HarnessRouteKind.MANAGED_CARD, DelegateHarnessRouting.plan(server(card = 3)).kind)
        assertEquals("llama:3", DelegateHarnessRouting.plan(server(card = 3)).wireId)
        assertEquals("litert:9", DelegateHarnessRouting.plan(server(engine = LlamaServerEntity.ENGINE_LITERT_LM, liteRt = 9)).wireId)
        val swap = DelegateHarnessRouting.plan(server(engine = LlamaServerEntity.ENGINE_LLAMA_SWAP, modelName = " qwen "))
        assertEquals(HarnessRouteKind.LLAMA_SWAP, swap.kind)
        assertEquals("llama-swap:qwen", swap.wireId)
        val legacy = DelegateHarnessRouting.plan(server())
        assertEquals(HarnessRouteKind.CUSTOM_ROUTE, legacy.kind)
        assertNull(legacy.wireId)
        assertEquals("default", legacy.modelId)
        assertEquals("adt-chat-srv-7", legacy.customRoute)
    }

    @Test fun unsupportedEnginesAreRejected() {
        try {
            DelegateHarnessRouting.plan(server(engine = LlamaServerEntity.ENGINE_OLLAMA))
            fail()
        } catch (e: DelegationHarnessException) {
            assertEquals("SERVER_ENGINE_UNSUPPORTED", e.code)
        }
        try {
            DelegateHarnessRouting.plan(server(engine = LlamaServerEntity.ENGINE_LITERT_LM))
            fail()
        } catch (e: DelegationHarnessException) {
            assertEquals("SERVER_MODEL_MISSING", e.code)
        }
    }

    @Test fun limitsAreClamped() {
        assertEquals(8192, DelegateHarnessRouting.clampContext(null))
        assertEquals(2048, DelegateHarnessRouting.clampContext(10))
        assertEquals(4096, DelegateHarnessRouting.clampMaxOutput(100_000, 8192))
        assertEquals(2048, DelegateHarnessRouting.clampMaxOutput(null, 8192))
    }

    @Test fun providerResolutionPrefersRequestedAndCanonicalizes() {
        val providers = listOf(
            HarnessProviderOption("a", "A", models = listOf("m1")),
            HarnessProviderOption("b", "B", models = listOf("m1", "m2")),
        )
        assertEquals("a", DelegateHarnessRouting.resolveProvider(providers, "m1"))
        assertEquals("b", DelegateHarnessRouting.resolveProvider(providers, "m1", preferred = "b"))
        assertNull(DelegateHarnessRouting.resolveProvider(providers, "zzz"))
    }

    @Test fun profileMatchDetectsChanges() {
        fun profile(url: String, ctx: Int) = Json.parseToJsonElement(
            """{"api":"openai-completions","baseURL":"$url","models":[{"id":"default","contextWindow":$ctx,"maxTokens":2048}]}""").jsonObject
        assertTrue(DelegateHarnessRouting.profileMatches(profile("http://h/v1", 8192), profile("http://h/v1", 8192)))
        assertFalse(DelegateHarnessRouting.profileMatches(profile("http://h/v1", 4096), profile("http://h/v1", 8192)))
        assertFalse(DelegateHarnessRouting.profileMatches(null, profile("http://h/v1", 8192)))
    }
}
