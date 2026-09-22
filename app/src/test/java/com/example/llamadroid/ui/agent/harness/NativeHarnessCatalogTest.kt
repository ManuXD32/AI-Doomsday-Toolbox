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
