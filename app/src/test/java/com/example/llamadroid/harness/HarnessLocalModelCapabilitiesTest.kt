package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.service.resolveAgentLiteRtContextTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HarnessLocalModelCapabilitiesTest {
    private lateinit var app: Context

    @Before
    fun clearCapabilities() {
        app = RuntimeEnvironment.getApplication()
        assertTrue(
            app.getSharedPreferences("harness_local_model_capabilities", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
    }

    @Test
    fun harnessUsesSafeSixteenKDefaultWhileExplicitOverridesAndNativeDefaultRemainDistinct() {
        val gemma = LiteRtModelEntity(
            id = 1,
            displayName = "Gemma 4 E2B",
            path = "/models/gemma-4.task",
            filename = "gemma-4-E2B-it.litertlm",
            maxContextTokens = 32_768,
        )
        val qwen = LiteRtModelEntity(
            id = 2,
            displayName = "Qwen 3.6 14B",
            path = "/models/qwen.litertlm",
            filename = "qwen3.6-14b-ekv65536.litertlm",
        )
        val smallModel = LiteRtModelEntity(
            id = 3,
            displayName = "Small context model",
            path = "/models/small.litertlm",
            filename = "small-8192.litertlm",
            maxContextTokens = 8_192,
        )

        assertEquals(16_384, resolveHarnessLiteRtContextTokens(gemma, null, -1))
        assertEquals(16_384, resolveHarnessLiteRtContextTokens(qwen, null, -1))
        assertEquals(8_192, resolveHarnessLiteRtContextTokens(smallModel, null, -1))
        assertEquals(16_384, resolveHarnessLiteRtContextTokens(gemma, 16_384, 24_576))
        assertEquals(24_576, resolveHarnessLiteRtContextTokens(gemma, null, 24_576))
        assertEquals(8_192, resolveAgentLiteRtContextTokens(-1, gemma))
    }

    @Test
    fun harnessCapsImplicitProviderBudgetButPreservesSavedOverridesAndSmallerRequests() {
        val gemma = LiteRtModelEntity(
            id = 42,
            displayName = "Gemma 4 E2B",
            path = "/models/gemma-4.task",
            filename = "gemma-4-E2B-it.litertlm",
            maxContextTokens = 32_768,
        )

        assertEquals(2_048, resolveHarnessLiteRtOutputTokens(gemma, null, null, -1, 8_192))
        assertEquals(1_024, resolveHarnessLiteRtOutputTokens(gemma, null, 1_024, 4_096, 8_192))
        assertEquals(3_072, resolveHarnessLiteRtOutputTokens(gemma, null, null, 3_072, 8_192))
        assertEquals(1_024, resolveHarnessLiteRtOutputTokens(gemma, 1_024, null, -1, 8_192))
        assertEquals(2_048, resolveHarnessLiteRtOutputTokens(gemma, 4_096, null, -1, 8_192))
        assertEquals(8_096, resolveHarnessLiteRtOutputTokens(gemma, 8_096, 8_096, -1, 32_768))
        assertEquals(2_048, resolveHarnessLiteRtOutputTokens(gemma, 8_096, null, 2_048, 8_192))
    }

    @Test
    fun inferenceOverridesPersistAndCapabilityUpdatesKeepThem() {
        val store = HarnessLocalModelCapabilityStore(app)
        store.set("litert:42", contextTokens = 32_768, maxOutputTokens = 8_192)
        store.setInferenceOptions("litert:42", mtpEnabled = false, thinkingEnabled = true)

        val reopened = HarnessLocalModelCapabilityStore(app)
        val saved = requireNotNull(reopened.get("litert:42"))
        assertEquals(32_768L, saved.contextTokens)
        assertEquals(8_192L, saved.maxOutputTokens)
        assertEquals(false, saved.mtpEnabled)
        assertEquals(true, saved.thinkingEnabled)

        reopened.set("litert:42", contextTokens = null, maxOutputTokens = null)
        val afterClearingLimits = requireNotNull(HarnessLocalModelCapabilityStore(app).get("litert:42"))
        assertNull(afterClearingLimits.contextTokens)
        assertNull(afterClearingLimits.maxOutputTokens)
        assertEquals(false, afterClearingLimits.mtpEnabled)
        assertEquals(true, afterClearingLimits.thinkingEnabled)
    }

    @Test
    fun inferenceOverrideNullUsesGlobalFallbackAndFalseRemainsExplicit() {
        val store = HarnessLocalModelCapabilityStore(app)
        store.setOverrides(
            wireId = "litert:42",
            contextTokens = 24_576,
            maxOutputTokens = 4_096,
            mtpEnabled = null,
            thinkingEnabled = false,
        )

        val saved = requireNotNull(HarnessLocalModelCapabilityStore(app).get("litert:42"))
        assertEquals(24_576L, saved.contextTokens)
        assertEquals(4_096L, saved.maxOutputTokens)
        assertNull(saved.mtpEnabled)
        assertEquals(false, saved.thinkingEnabled)
    }

    @Test
    fun outputOnlyOverrideDoesNotEraseDetectedContextFromCatalog() {
        val store = HarnessLocalModelCapabilityStore(app)
        store.set("litert:42", contextTokens = null, maxOutputTokens = 2_048)
        val catalog = Json.parseToJsonElement(
            """{"data":[{"id":"litert:42","name":"Gemma 4","context_length":32768,"capabilitySource":"detected"}]}"""
        ).jsonObject

        val row = requireNotNull(store.apply(catalog)["data"]).jsonArray.single().jsonObject

        assertEquals(32_768L, row.getValue("context_length").jsonPrimitive.content.toLong())
        assertEquals(2_048L, row.getValue("max_output_tokens").jsonPrimitive.content.toLong())
        assertEquals("detected", row.getValue("capabilitySource").jsonPrimitive.content)
        assertFalse(row.containsKey("mtpEnabled"))
    }

    @Test
    fun savedLimitsDoNotReplaceCurrentBackendEffectiveCatalogLimits() {
        val store = HarnessLocalModelCapabilityStore(app)
        store.setOverrides(
            wireId = "litert:42",
            contextTokens = 32_768,
            maxOutputTokens = 8_192,
            mtpEnabled = false,
            thinkingEnabled = null,
        )
        val catalog = Json.parseToJsonElement(
            """{"data":[{"id":"litert:42","name":"Gemma 4","context_length":4096,"max_output_tokens":1024,"effective_backend_context_length":4096,"advertised_context_length":32768,"capabilitySource":"explicit"}]}"""
        ).jsonObject

        val row = requireNotNull(store.apply(catalog)["data"]).jsonArray.single().jsonObject

        assertEquals(4_096L, row.getValue("context_length").jsonPrimitive.content.toLong())
        assertEquals(1_024L, row.getValue("max_output_tokens").jsonPrimitive.content.toLong())
        assertEquals(32_768L, row.getValue("advertised_context_length").jsonPrimitive.content.toLong())
        assertEquals(4_096L, row.getValue("effective_backend_context_length").jsonPrimitive.content.toLong())
    }
}
