package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessProviderCreationTest {
    @Test
    fun profileAndOperationMatchOfficialCustomProviderContract() {
        val request = NativeHarnessCustomProviderRequest(
            route = "qa-gateway",
            displayName = "QA gateway",
            api = "openai-responses",
            baseUrl = "https://127.0.0.1:9443/v1 ",
            apiKey = "sk-qa-secret",
            models = listOf(
                NativeHarnessCustomProviderModel(
                    id = "qa-model",
                    name = "QA model",
                    contextWindow = 131_072,
                    maxTokens = 8_192,
                    inputModalities = listOf("text", "image")
                )
            )
        )

        val operation = buildNativeCustomProviderOperation(request)
        assertEquals("set", operation["op"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("providers", "qa-gateway"),
            operation["path"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        val profile = operation["value"]?.jsonObject ?: error("profile missing")
        assertEquals("QA gateway", profile["displayName"]?.jsonPrimitive?.content)
        assertEquals("openai-responses", profile["api"]?.jsonPrimitive?.content)
        assertEquals("https://127.0.0.1:9443/v1", profile["baseURL"]?.jsonPrimitive?.content)
        assertEquals("QA_GATEWAY_API_KEY", profile["apiKeyEnv"]?.jsonPrimitive?.content)
        assertEquals("qa-model", profile["models"]?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals(
            listOf("text", "image"),
            profile["models"]?.jsonArray?.single()?.jsonObject?.get("input")?.jsonArray
                ?.map { it.jsonPrimitive.content }
        )
        assertFalse(profile.toString().contains("sk-qa-secret"))
    }

    @Test
    fun validationMatchesRouteEndpointAndKeyConstraints() {
        val valid = NativeHarnessCustomProviderRequest(
            route = "custom-route",
            api = "openai-completions",
            baseUrl = "http://localhost:8080/v1",
            models = listOf(NativeHarnessCustomProviderModel("model"))
        )
        assertNull(validateNativeCustomProviderRequest(valid, setOf("openai")))
        assertEquals(
            "CUSTOM_PROVIDER_ROUTE_TAKEN",
            validateNativeCustomProviderRequest(valid, setOf("custom-route"))?.code
        )
        assertEquals(
            "CUSTOM_PROVIDER_ROUTE_INVALID",
            validateNativeCustomProviderRequest(valid.copy(route = "9route"))?.code
        )
        assertEquals(
            "CUSTOM_PROVIDER_BASE_URL_INVALID",
            validateNativeCustomProviderRequest(valid.copy(baseUrl = "ftp://localhost"))?.code
        )
        assertNull(validateNativeCustomProviderRequest(valid.copy(models = emptyList())))
        assertEquals(
            "CUSTOM_PROVIDER_KEY_INVALID",
            validateNativeCustomProviderRequest(valid.copy(apiKey = "OPENAI_KEY=value"))?.code
        )
        assertEquals(
            "CUSTOM_PROVIDER_BASE_URL_INVALID",
            validateNativeCustomProviderRequest(valid.copy(baseUrl = "https://user:secret@localhost:8080"))?.code
        )
    }

    @Test
    fun keylessProviderCanBeSavedWithZeroModels() {
        val profile = buildNativeCustomProviderProfile(
            NativeHarnessCustomProviderRequest(
                route = "local-llama",
                api = "openai-completions",
                baseUrl = "http://127.0.0.1:8080/v1",
                apiKey = "",
                models = emptyList(),
            )
        )

        assertEquals("openai-completions", profile["api"]?.toString()?.trim('"'))
        assertEquals("http://127.0.0.1:8080/v1", profile["baseURL"]?.toString()?.trim('"'))
        assertEquals("[]", profile["models"]?.toString())
        assertFalse(profile.containsKey("apiKeyEnv"))
    }

    @Test
    fun draftDiscoveryNeedsOnlyProtocolAndEndpoint() {
        val draft = NativeHarnessCustomProviderRequest(
            route = "",
            api = "openai-completions",
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "",
            models = emptyList(),
        )
        assertNull(validateNativeCustomProviderDiscoveryRequest(draft))
        assertEquals(
            "CUSTOM_PROVIDER_BASE_URL_INVALID",
            validateNativeCustomProviderDiscoveryRequest(draft.copy(baseUrl = "ftp://localhost"))?.code
        )
        assertEquals(
            "CUSTOM_PROVIDER_PROTOCOL_INVALID",
            validateNativeCustomProviderDiscoveryRequest(draft.copy(api = "unknown"))?.code
        )
    }

    @Test
    fun endpointRootIsCanonicalizedForOpenAiAndKeptRootForAnthropic() {
        val openAi = buildNativeCustomProviderProfile(
            NativeHarnessCustomProviderRequest(
                route = "root-openai",
                api = "openai-completions",
                baseUrl = "https://gateway.test/proxy",
                models = listOf(NativeHarnessCustomProviderModel("model")),
            )
        )
        assertEquals("https://gateway.test/proxy/v1", openAi["baseURL"]?.jsonPrimitive?.content)

        val anthropic = buildNativeCustomProviderProfile(
            NativeHarnessCustomProviderRequest(
                route = "root-anthropic",
                api = "anthropic-messages",
                baseUrl = "https://gateway.test/proxy/v1/",
                models = listOf(NativeHarnessCustomProviderModel("model")),
            )
        )
        assertEquals("https://gateway.test/proxy", anthropic["baseURL"]?.jsonPrimitive?.content)
    }

    @Test
    fun credentialReferenceUsesOfficialRouteDerivation() {
        assertEquals("ACME_GATEWAY_API_KEY", nativeCustomProviderCredentialReference("acme-gateway"))
        assertEquals("LOCAL_API_KEY", nativeCustomProviderCredentialReference("local"))
        assertNotNull(NATIVE_CUSTOM_PROVIDER_PROTOCOLS.singleOrNull { it == "openai-responses" })
    }

    @Test
    fun generatedPresetRouteIsReadableAndCollisionSafe() {
        assertEquals(
            listOf(
                com.example.llamadroid.harness.HarnessProviderPreset.REMOTE_LLAMA_CPP,
                com.example.llamadroid.harness.HarnessProviderPreset.LLAMA_SWAP,
                com.example.llamadroid.harness.HarnessProviderPreset.GENERIC_OPENAI,
            ),
            NATIVE_CUSTOM_PROVIDER_PRESETS
        )
        assertEquals(
            "remote-llama-cpp-2",
            nextNativeHarnessProviderRoute(
                com.example.llamadroid.harness.HarnessProviderPreset.REMOTE_LLAMA_CPP,
                "",
                setOf("remote-llama-cpp")
            )
        )
        assertEquals(
            "provider-7b-server",
            nativeHarnessProviderRouteSlug("7B server")
        )
    }

    @Test
    fun savePlanSendsOnlyRealDiffsAndLeavesAbsentOptionalFieldsAbsent() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 7,
            value = buildJsonObject {
                put("displayName", "")
                put("api", "openai-completions")
                put("baseURL", "http://localhost/v1")
                putJsonArray("models") { add(buildJsonObject { put("id", "qa-model") }) }
            },
            apiKeyReference = null
        )
        val prefix = "llm-pi-ai.providers.qa-gateway"
        val config = HarnessProviderConfigUi(
            id = "qa-gateway",
            name = "QA gateway",
            fields = listOf(
                HarnessSchemaField("$prefix.displayName", "Display name", type = HarnessSchemaFieldType.TEXT),
                HarnessSchemaField("$prefix.api", "API", type = HarnessSchemaFieldType.TEXT, value = "openai-completions"),
                HarnessSchemaField(
                    "$prefix.baseURL",
                    "Base URL",
                    type = HarnessSchemaFieldType.TEXT,
                    value = "http://127.0.0.1/v1"
                ),
                HarnessSchemaField("$prefix.models", "Models", type = HarnessSchemaFieldType.JSON, value = ""),
                HarnessSchemaField("$prefix.apiKeyEnv", "Credential", type = HarnessSchemaFieldType.TEXT, value = ""),
                HarnessSchemaField("$prefix.timeout", "Timeout", type = HarnessSchemaFieldType.INTEGER, value = "")
            )
        )

        val plan = buildNativeHarnessProviderSavePlan(binding, config)
        val operations = (plan as NativeHarnessProviderSavePlan.Ready).operations
        assertEquals(1, operations.size)
        assertEquals(listOf("providers", "qa-gateway", "baseURL"), operations.single()["path"]?.jsonArray?.map {
            it.jsonPrimitive.content
        })
        assertEquals("http://127.0.0.1/v1", operations.single()["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun blankJsonClearsOnlyAnExplicitUserOverride() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 7,
            value = buildJsonObject {
                putJsonArray("models") { add(buildJsonObject { put("id", "qa-model") }) }
            },
            apiKeyReference = null
        )
        val field = HarnessSchemaField(
            key = "llm-pi-ai.providers.qa-gateway.models",
            label = "Models",
            type = HarnessSchemaFieldType.JSON,
            value = "",
            isOverridden = true
        )

        val plan = buildNativeHarnessProviderSavePlan(
            binding,
            HarnessProviderConfigUi("qa-gateway", "QA gateway", fields = listOf(field))
        ) as NativeHarnessProviderSavePlan.Ready

        assertEquals(1, plan.operations.size)
        assertEquals("unset", plan.operations.single()["op"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("providers", "qa-gateway", "models"),
            plan.operations.single()["path"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun savePlanRejectsInvalidTypedProviderFields() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 7,
            value = buildJsonObject {},
            apiKeyReference = null
        )
        val field = HarnessSchemaField(
            key = "llm-pi-ai.providers.qa-gateway.timeout",
            label = "Timeout",
            type = HarnessSchemaFieldType.INTEGER,
            value = "not-a-number"
        )
        val plan = buildNativeHarnessProviderSavePlan(
            binding,
            HarnessProviderConfigUi("qa-gateway", "QA gateway", fields = listOf(field))
        )
        assertEquals("SETTINGS_FIELD_INVALID", (plan as NativeHarnessProviderSavePlan.Invalid).code)
    }

    @Test
    fun pinnedPiAiOptionalFieldsStayAbsentAndLongIntegerIsPreserved() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 7,
            value = buildJsonObject {
                put("api", "openai-completions")
                put("baseURL", "http://localhost/v1")
                putJsonArray("models") { add(buildJsonObject { put("id", "qa-model") }) }
            },
            apiKeyReference = null
        )
        val prefix = "llm-pi-ai.providers.qa-gateway"
        val optionalFields = listOf(
            HarnessSchemaField("$prefix.displayName", "Display name", type = HarnessSchemaFieldType.TEXT),
            HarnessSchemaField("$prefix.apiKeyEnv", "Credential", type = HarnessSchemaFieldType.TEXT),
            HarnessSchemaField("$prefix.modelOverrides", "Model overrides", type = HarnessSchemaFieldType.JSON),
            HarnessSchemaField("$prefix.compat", "Compatibility", type = HarnessSchemaFieldType.JSON),
            HarnessSchemaField("$prefix.defaultContextWindow", "Default context", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.defaultMaxTokens", "Default output", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.defaultInput", "Default input", type = HarnessSchemaFieldType.JSON),
            HarnessSchemaField("$prefix.headers", "Headers", type = HarnessSchemaFieldType.JSON),
            HarnessSchemaField("$prefix.reasoning", "Reasoning", type = HarnessSchemaFieldType.CHOICE),
            HarnessSchemaField("$prefix.thinkingBudgets", "Thinking budgets", type = HarnessSchemaFieldType.JSON),
            HarnessSchemaField("$prefix.cacheRetention", "Cache retention", type = HarnessSchemaFieldType.CHOICE),
            HarnessSchemaField("$prefix.transport", "Transport", type = HarnessSchemaFieldType.CHOICE),
            HarnessSchemaField("$prefix.timeoutMs", "Timeout", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.streamIdleTimeoutMs", "Idle timeout", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.maxRequestImageBytes", "Image bytes", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.requestImagePixelBudget", "Image pixels", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.requestImageMaxBytes", "Image target", type = HarnessSchemaFieldType.INTEGER),
            HarnessSchemaField("$prefix.retryPolicy", "Retry policy", type = HarnessSchemaFieldType.JSON)
        )
        val config = HarnessProviderConfigUi(
            id = "qa-gateway",
            name = "QA gateway",
            fields = listOf(
                HarnessSchemaField("$prefix.api", "API", type = HarnessSchemaFieldType.TEXT, value = "openai-completions"),
                HarnessSchemaField("$prefix.baseURL", "Base URL", type = HarnessSchemaFieldType.TEXT, value = "http://localhost/v1"),
                HarnessSchemaField(
                    "$prefix.models",
                    "Models",
                    type = HarnessSchemaFieldType.JSON,
                    value = "[{\"id\":\"qa-model\"}]"
                ),
                HarnessSchemaField("$prefix.optionalEnabled", "Optional enabled", type = HarnessSchemaFieldType.TOGGLE),
                *optionalFields.toTypedArray()
            )
        )

        val plan = buildNativeHarnessProviderSavePlan(binding, config)
        assertTrue("a profile with unchanged pinned fields should be a valid save", plan is NativeHarnessProviderSavePlan.Ready)
        assertEquals(0, (plan as NativeHarnessProviderSavePlan.Ready).operations.size)

        val longConfig = HarnessProviderConfigUi(
            id = "qa-gateway",
            name = "QA gateway",
            fields = listOf(
                HarnessSchemaField(
                    "$prefix.defaultContextWindow",
                    "Default context",
                    type = HarnessSchemaFieldType.INTEGER,
                    value = "9000000000"
                )
            )
        )
        val longPlan = buildNativeHarnessProviderSavePlan(binding, longConfig)
        val longValue = (longPlan as NativeHarnessProviderSavePlan.Ready).operations.single()["value"]
            ?.jsonPrimitive?.longOrNull
        assertEquals(9_000_000_000L, longValue)
    }

    @Test
    fun providerRemovalAndCredentialFallbackUseSeparateOfficialLayers() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = "llm-pi-ai",
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 1,
            value = buildJsonObject {},
            apiKeyReference = null
        )
        val userOnly = buildJsonObject {
            putJsonObject("user") {
                putJsonObject("providers") { putJsonObject("qa-gateway") { put("api", "openai-completions") } }
            }
        }
        val baseOwned = buildJsonObject {
            putJsonObject("base") {
                putJsonObject("providers") { putJsonObject("qa-gateway") { put("api", "openai-completions") } }
            }
            putJsonObject("user") {
                putJsonObject("providers") { putJsonObject("qa-gateway") {} }
            }
        }
        assertTrue(nativeHarnessProviderIsRemovable(binding, userOnly))
        assertFalse(nativeHarnessProviderIsRemovable(binding, baseOwned))
        assertFalse(nativeHarnessProviderIsRemovable(binding.copy(settingsPath = emptyList()), userOnly))
        assertEquals("QA_GATEWAY_API_KEY", nativeHarnessProviderCredentialReference(binding))
    }

    @Test
    fun missingPiAiProfileCanBeMaterializedBeforeCredentialWrite() {
        val binding = NativeHarnessProviderBinding(
            providerId = "qa-gateway",
            displayName = "QA gateway",
            settingsNamespace = NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE,
            settingsPath = listOf("providers", "qa-gateway"),
            revision = 4,
            value = null,
            apiKeyReference = "QA_GATEWAY_API_KEY",
            user = null,
            base = null,
            credentialReferenceDerived = true
        )
        val plan = buildNativeHarnessProviderSavePlan(
            binding,
            HarnessProviderConfigUi("qa-gateway", "QA gateway")
        ) as NativeHarnessProviderSavePlan.Ready
        assertEquals(1, plan.operations.size)
        assertEquals(
            listOf("providers", "qa-gateway"),
            plan.operations.single()["path"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals("{}", plan.operations.single()["value"].toString())
    }
}
