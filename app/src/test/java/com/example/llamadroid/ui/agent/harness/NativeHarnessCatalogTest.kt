package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessCatalogTest {
    @Test
    fun managedDshBundlesAreSeparatedFromOptionalPlugins() {
        assertTrue(isHarnessManagedPlugin(HarnessPluginUi(
            id = "@deepseek-ai/dsh-client-ui-chat",
            name = "Harness chat",
            summary = "Built-in UI",
        )))
        assertTrue(isHarnessManagedPlugin(HarnessPluginUi(
            id = "adt-dsh-client",
            name = "Harness bridge",
            summary = "Built-in bridge",
            bundleName = "@manuxd32/adt-dsh-bridge",
        )))
        assertFalse(isHarnessManagedPlugin(HarnessPluginUi(
            id = "@example/community-plugin",
            name = "Community plugin",
            summary = "Optional extension",
            bundleName = "@example/community-plugin",
        )))
    }

    @Test
    fun modelCatalogKeepsReasoningEffortsAndProviderFailures() {
        val value = Json.parseToJsonElement(
            """
            {
              "default":{"provider":"deepseek","model":"reasoner","reasoningEffort":"high"},
              "routableProviders":["deepseek","broken"],
              "groups":[{"id":"deepseek","name":"DeepSeek","models":[
                {"id":"reasoner","name":"Reasoner","reasoning":{"defaultEffort":"high","efforts":[
                  {"id":"low","name":"Low","description":"Short reasoning"},
                  {"id":"high","name":"High"}
                ]}}
              ]}],
              "failures":[{"id":"broken","name":"Broken","message":"No credentials"}]
            }
            """.trimIndent()
        ).jsonObject

        val result = parseHarnessModelCatalog(value)
        val provider = result.providers.single()
        assertEquals("high", provider.reasoningDefaults["reasoner"])
        assertEquals(listOf("low", "high"), provider.reasoningEfforts["reasoner"]?.map { it.id })
        assertEquals("broken", result.failures.single().providerId)
        assertEquals("reasoner", result.defaultModel)
    }

    @Test
    fun modelCatalogKeepsWireIdsSeparateFromCanonicalDisplayNames() {
        val value = Json.parseToJsonElement(
            """
            {"groups":[{"id":"litert","name":"LiteRT","models":[
              {"id":"litert:43","name":"Gemma 3 4B"},
              {"id":"litert:42","name":"Gemma 3 2B"},
              {"id":"litert:41","name":"Gemma 3 2B"}
            ]}]}
            """.trimIndent()
        ).jsonObject

        val provider = parseHarnessModelCatalog(value).providers.single()
        assertEquals(listOf("litert:43", "litert:42", "litert:41"), provider.models)
        assertEquals("Gemma 3 4B", provider.modelNames["litert:43"])
        val first = harnessModelOptionLabel(provider, "litert:42")
        val second = harnessModelOptionLabel(provider, "litert:41")
        assertTrue(first.startsWith("Gemma 3 2B · "))
        assertTrue(second.startsWith("Gemma 3 2B · "))
        assertFalse(first.contains("litert:42"))
        assertFalse(second.contains("litert:41"))
        assertFalse(first == second)
    }

    @Test
    fun modelCatalogProjectsContextAndOutputLimitsAndMarksUnknownModels() {
        val value = Json.parseToJsonElement(
            """
            {"groups":[{"id":"llama","models":[
              {"id":"/models/known.gguf","name":"known.gguf","context_length":32768,"max_output_tokens":4096},
              {"id":"unknown","name":"unknown"}
            ]}]}
            """.trimIndent()
        ).jsonObject

        val provider = parseHarnessModelCatalog(value).providers.single()
        assertEquals(32768L, provider.modelContextWindows["/models/known.gguf"])
        assertEquals(4096L, provider.modelMaxOutputTokens["/models/known.gguf"])
        assertEquals("detected", provider.modelCapabilitySources["/models/known.gguf"])
        assertTrue(harnessModelContextKnown(provider, "/models/known.gguf"))
        assertFalse(harnessModelContextKnown(provider, "unknown"))
        assertEquals("known.gguf", harnessModelOptionLabel(provider, "/models/known.gguf"))
    }

    @Test
    fun explicitProviderModelContextOverridesDetectedCapability() {
        val provider = parseHarnessModelCatalog(Json.parseToJsonElement(
            """{"groups":[{"id":"local","models":[{"id":"model","context_length":8192}]}]}"""
        ).jsonObject).providers.single()
        val config = HarnessProviderConfigUi(
            id = "local",
            name = "Local",
            fields = listOf(
                HarnessSchemaField(
                    key = "llm-pi-ai.providers.local.models",
                    label = "Models",
                    type = HarnessSchemaFieldType.JSON,
                    value = "[{\"id\":\"model\",\"contextWindow\":16384,\"maxTokens\":2048}]",
                    path = listOf("providers", "local", "models"),
                )
            )
        )
        val merged = mergeHarnessSavedModelCapabilities(listOf(provider), listOf(config)).single()
        assertEquals(16384L, merged.modelContextWindows["model"])
        assertEquals(2048L, merged.modelMaxOutputTokens["model"])
        assertEquals("explicit", merged.modelCapabilitySources["model"])
    }

    @Test
    fun androidManagedCatalogKeepsLiteRtProviderBoundaryAndWireIds() {
        val value = Json.parseToJsonElement(
            """{"data":[
                {"id":"litert:43","owned_by":"adt-litert","name":"Gemma 4 E2B","context_length":32768},
                {"id":"llama:7","owned_by":"adt-llama-server","name":"/models/llama/Q4.gguf"}
            ]}"""
        ).jsonObject
        val providers = parseHarnessLocalModelCatalog(value)
        assertEquals(2, providers.size)
        val litert = providers.single { it.id == "adt-managed" }
        assertEquals("litert:43", litert.models.single())
        assertEquals("Gemma 4 E2B", litert.modelNames["litert:43"])
        assertEquals(32768L, litert.modelContextWindows["litert:43"])
        val llama = providers.single { it.id == "adt-llama-server" }
        assertEquals("Q4.gguf", llama.modelNames["llama:7"])
        assertEquals("unknown", llama.modelCapabilitySources["llama:7"])
    }

    @Test
    fun liteRtCatalogSeparatesEffectiveBackendContextFromAdvertisedContext() {
        val value = Json.parseToJsonElement(
            """{"data":[{"id":"litert:43","owned_by":"adt-litert","name":"Gemma 4 E2B","context_length":4096,"effective_backend_context_length":4096,"effective_backend_max_output_tokens":1024,"effective_backend":"gpu","requested_backend":"gpu","gpu_safety_limit_applied":true,"advertised_context_length":32768,"capabilitySource":"explicit"}]}"""
        ).jsonObject

        val model = parseHarnessLocalModelCatalog(value).single().let { provider ->
            provider.models.single() to provider
        }

        assertEquals(4_096L, model.second.modelContextWindows[model.first])
        assertEquals(32_768L, model.second.modelAdvertisedContextWindows[model.first])
        assertEquals("explicit", model.second.modelCapabilitySources[model.first])
        assertEquals(
            HarnessModelBackendLimitsUi(
                backend = "gpu",
                requestedBackend = "gpu",
                contextTokens = 4_096L,
                outputTokens = 1_024L,
                gpuSafetyLimitApplied = true,
            ),
            model.second.modelBackendLimits[model.first],
        )
    }

    @Test
    fun androidManagedCatalogOmitsSyntheticLlamaSwapWithoutDroppingNativeProviders() {
        val value = Json.parseToJsonElement(
            """{"data":[
                {"id":"llama-swap:/models/Qwen.gguf","owned_by":"adt-llama-swap","name":"Qwen.gguf","context_length":32768},
                {"id":"litert:43","owned_by":"adt-litert","name":"Gemma 4 E2B","context_length":32768},
                {"id":"llama:7","owned_by":"adt-llama-server","name":"/models/llama/Q4.gguf"}
            ]}"""
        ).jsonObject

        val providers = parseHarnessLocalModelCatalog(value)

        assertTrue(providers.none { it.id == "adt-llama-swap" })
        assertEquals(setOf("adt-managed", "adt-llama-server"), providers.map { it.id }.toSet())
        assertEquals(listOf("litert:43"), providers.single { it.id == "adt-managed" }.models)
        assertEquals(listOf("llama:7"), providers.single { it.id == "adt-llama-server" }.models)
    }

    @Test
    fun userConfiguredLlamaSwapProviderKeepsExactWireIdAlongsideNativeRows() {
        val dsh = parseHarnessModelCatalog(Json.parseToJsonElement(
            """{"groups":[{"id":"llama-swap","name":"My llama-swap","models":[
                {"id":"/models/Qwen.gguf","name":"Qwen.gguf","context_length":65536}
            ]}]}"""
        ).jsonObject).providers
        val local = parseHarnessLocalModelCatalog(Json.parseToJsonElement(
            """{"data":[
                {"id":"llama-swap:/models/Qwen.gguf","owned_by":"adt-llama-swap","name":"Qwen.gguf"},
                {"id":"litert:43","owned_by":"adt-litert","name":"Gemma 4 E2B","context_length":32768}
            ]}"""
        ).jsonObject)

        val merged = mergeHarnessLocalModelProviders(dsh, local)
        val configured = merged.single { it.id == "llama-swap" }

        assertEquals(listOf("/models/Qwen.gguf"), configured.models)
        assertEquals("Qwen.gguf", configured.modelNames["/models/Qwen.gguf"])
        assertTrue(merged.none { it.id == "adt-llama-swap" })
        assertTrue(merged.any { it.id == "adt-managed" && it.models == listOf("litert:43") })
    }

    @Test
    fun localProviderMergeDoesNotFlattenModelsIntoUnrelatedProviders() {
        val dsh = HarnessProviderOption(id = "deepseek", name = "DeepSeek", models = listOf("reasoner"))
        val local = HarnessProviderOption(id = "adt-managed", name = "ADT LiteRT", models = listOf("litert:1"))
        val merged = mergeHarnessLocalModelProviders(listOf(dsh), listOf(local))
        assertEquals(listOf("deepseek", "adt-managed"), merged.map { it.id })
        assertEquals(listOf("reasoner"), merged.first().models)
        assertEquals(listOf("litert:1"), merged.last().models)
    }

    @Test
    fun pluginInstallMapsBuildApprovalEnvelopeAndInventoryEntries() {
        val value = Json.parseToJsonElement(
            """
            {
              "application":"failed",
              "error":{"code":"operation-error"},
              "pendingBuilds":["native-addon"],
              "packageResult":{"kind":"build-blocked","output":"blocked"}
            }
            """
        ).jsonObject
        val outcome = parseHarnessPluginInstallOutcome(value)
        assertEquals("failed", outcome.application)
        assertEquals(listOf("native-addon"), outcome.pendingBuilds)
        assertEquals("operation-error", outcome.errorCode)
        assertEquals("build-blocked", outcome.packageKind)

        val inventory = parseHarnessPluginInventory(Json.parseToJsonElement(
            """[{"entryId":"include:tool","moduleName":"tool","enabled":true,"fiberPhase":"active","patchId":"tool","readOnlyReason":"unaddressable"}]"""
        ))
        assertEquals(1, inventory.size)
        assertTrue(inventory.single().enabled)
        assertEquals("tool", inventory.single().patchId)
        assertEquals("unaddressable", inventory.single().readOnlyReason)
    }

    @Test
    fun pluginInventoryManagementFlagAndBundleRowsKeepPinnedMetadata() {
        val managed = Json.parseToJsonElement(
            """{"managementAvailable":true,"entries":[]}"""
        )
        val unmanaged = Json.parseToJsonElement(
            """{"entries":[{"entryId":"include:tool","moduleName":"tool","enabled":true,"fiberPhase":"active"}]}"""
        )
        assertTrue(parseHarnessPluginManagementAvailable(managed))
        assertFalse(parseHarnessPluginManagementAvailable(unmanaged))

        val row = parseHarnessPluginRows(
            rows = listOf(Json.parseToJsonElement(
                """{"rowId":"managed","moduleName":"plugin.mjs","entryId":"include:managed"}"""
            ).jsonObject),
            pluginsByEntry = mapOf(
                "include:managed" to Json.parseToJsonElement(
                    """{"entryId":"include:managed","enabled":true,"fiberPhase":"failed","readOnlyReason":"unaddressable"}"""
                ).jsonObject
            )
        ).single()
        assertEquals("failed", row.phase)
        assertEquals("unaddressable", row.readOnlyReason)
        assertFalse(row.canChange)
    }

    @Test
    fun pluginInventoryKeepsPresetRowsWithoutInventingMutationIds() {
        val value = Json.parseToJsonElement(
            """
            {
              "managementAvailable":true,
              "entries":[{"entryId":"include:live","moduleName":"live","enabled":true,"fiberPhase":"active"}],
              "agentPresets":[{
                "id":"standard",
                "name":"Standard",
                "rows":[
                  {"entryId":null,"moduleName":"preset-tool","enabled":"conditional"},
                  {"entryId":"include:preset-live","moduleName":"preset-live","enabled":true,"fiberPhase":"active"}
                ]
              }]
            }
            """.trimIndent()
        )

        val inventory = parseHarnessPluginInventory(value)
        assertEquals(3, inventory.size)
        val conditional = inventory.first { it.moduleName == "preset-tool" }
        assertEquals("", conditional.entryId)
        assertEquals(HARNESS_PLUGIN_PRESET_REASON, conditional.readOnlyReason)
        assertEquals("conditional", conditional.phase)
        assertFalse(conditional.enabled)

        val presetLive = inventory.first { it.moduleName == "preset-live" }
        assertEquals("", presetLive.entryId)
        assertEquals(HARNESS_PLUGIN_PRESET_REASON, presetLive.readOnlyReason)
        assertNull(pluginRowToggleArgs(presetLive.entryId, enabled = false))
    }

    @Test
    fun pluginInventoryMergesManagerMetadataWithoutDroppingReadOnlyRows() {
        val snapshot = parseHarnessPluginInventory(Json.parseToJsonElement(
            """{"entries":[
                {"entryId":"include:managed","moduleName":"managed","enabled":true,"fiberPhase":"active"},
                {"moduleName":"preset-only","enabled":true,"fiberPhase":"active"}
            ]}"""
        ))
        val managed = parseHarnessPluginInventory(Json.parseToJsonElement(
            """[{"entryId":"include:managed","moduleName":"managed","enabled":false,
                "fiberPhase":"failed","patchId":"managed","readOnlyReason":"unaddressable"}]"""
        ))

        val merged = mergeHarnessPluginInventory(snapshot, managed)
        assertEquals(2, merged.size)
        val enriched = merged.first { it.entryId == "include:managed" }
        assertFalse(enriched.enabled)
        assertEquals("failed", enriched.phase)
        assertEquals("unaddressable", enriched.readOnlyReason)
        assertEquals("preset-only", merged.single { it.entryId.isBlank() }.moduleName)
    }

    @Test
    fun disabledBundleRowKeepsPatchIdAndCannotSendInvalidEntryMutation() {
        val inactive = parseHarnessPluginRows(
            rows = listOf(Json.parseToJsonElement(
                """{"rowId":"managed","moduleName":"plugin.mjs"}"""
            ).jsonObject),
            pluginsByEntry = emptyMap()
        ).single()

        assertEquals("managed", inactive.rowId)
        assertNull(inactive.entryId)
        assertFalse(inactive.enabled)
        assertFalse(inactive.canChange)
        assertEquals(HARNESS_PLUGIN_UNADDRESSABLE_REASON, inactive.readOnlyReason)
        assertNull(pluginRowToggleArgs(inactive.entryId, enabled = true))

        val active = parseHarnessPluginRows(
            rows = listOf(Json.parseToJsonElement(
                """{"rowId":"managed","moduleName":"plugin.mjs","entryId":"include:managed"}"""
            ).jsonObject),
            pluginsByEntry = mapOf(
                "include:managed" to Json.parseToJsonElement(
                    """{"entryId":"include:managed","enabled":true}"""
                ).jsonObject
            )
        ).single()
        assertEquals("include:managed", active.entryId)
        assertTrue(active.canChange)
        assertEquals(
            "\"include:managed\"",
            pluginRowToggleArgs(active.entryId, enabled = false)?.get("id")?.toString()
        )
    }

    @Test
    fun pluginCancellationKeepsTooLateInstallsApplying() {
        assertEquals("cancelled", harnessPluginInstallCancellationPhase("cancelled"))
        assertEquals("applying", harnessPluginInstallCancellationPhase("too-late"))
        assertEquals("failed", harnessPluginInstallCancellationPhase("not-running"))
        assertEquals("failed", harnessPluginInstallCancellationPhase("unexpected"))
    }
}
