package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.HarnessStreamPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessProviderDiscoveryTest {
    @Test
    fun parsesOfficialDiscoveryMetadataAndDeduplicatesInEndpointOrder() {
        val models = parseNativeHarnessDiscoveredModels(Json.parseToJsonElement("""
            [
              {"id":"alpha","name":"Alpha","contextWindow":128000,"maxTokens":4096,"inputModalities":["text","image"]},
              {"id":"alpha","name":"duplicate"},
              {"id":"beta"},
              {"name":"missing id"}
            ]
        """))

        assertEquals(listOf("alpha", "beta"), models!!.map { it.id })
        assertEquals(128000L, models[0].contextWindow)
        assertEquals(listOf("text", "image"), models[0].inputModalities)
        assertNull(models[1].contextWindow)
    }

    @Test
    fun mapsCandidatesToPiAiRowsAndPreservesExistingRowsAndProperties() {
        val existing = """[{"id":"old","name":"Edited","custom":true}]"""
        val candidates = listOf(
            HarnessDiscoveredModelUi("old", name = "Server name", maxTokens = 1),
            HarnessDiscoveredModelUi("new", name = "New", contextWindow = 64000, inputModalities = listOf("text")),
            HarnessDiscoveredModelUi("ignored"),
        )

        val merged = mergeNativeHarnessDiscoveredModels(existing, candidates, setOf("old", "new"))!!
            .let { Json.parseToJsonElement(it) }
            .jsonArray
        assertEquals(2, merged.size)
        assertEquals("Edited", merged[0].jsonObject["name"]?.toString()?.trim('"'))
        assertTrue(merged[0].jsonObject["custom"]?.toString() == "true")
        assertEquals("new", merged[1].jsonObject["id"]?.toString()?.trim('"'))
        assertEquals("[\"text\"]", merged[1].jsonObject["input"]?.toString())
    }

    @Test
    fun bulkSelectionAndAdoptionChangeTheDraftOnlyAndPreserveExistingRows() {
        val candidates = parseNativeHarnessDiscoveredModels(Json.parseToJsonElement(
            """[{"id":"existing","name":"Remote"},{"id":"new"},{"id":"other"}]"""
        ))!!
        val initial = HarnessProviderDiscoveryUi(
            candidates = candidates,
            selectedIds = setOf("new"),
            hasRun = true,
        )
        val selected = updateNativeHarnessProviderDiscoverySelection(
            initial,
            modelIds = listOf("existing", "new"),
            selected = true,
        )
        assertEquals(setOf("existing", "new"), selected.selectedIds)
        val cleared = updateNativeHarnessProviderDiscoverySelection(
            selected,
            modelIds = listOf("existing"),
            selected = false,
        )
        assertEquals(setOf("new"), cleared.selectedIds)
        val draft = """[{"id":"existing","name":"User edited","custom":true}]"""
        val adopted = mergeNativeHarnessDiscoveredModels(draft, candidates, cleared.selectedIds)!!
        val rows = Json.parseToJsonElement(adopted).jsonArray
        assertEquals(2, rows.size)
        assertEquals("User edited", rows[0].jsonObject["name"]?.toString()?.trim('"'))
        assertTrue(rows[0].jsonObject["custom"]?.toString() == "true")
        assertEquals("new", rows[1].jsonObject["id"]?.toString()?.trim('"'))
    }

    @Test
    fun discoveryActionUsesOfficialWireAndAdoptsOnlyAfterExplicitSelection() = runBlocking {
        var config = HarnessProviderConfigUi(
            id = "provider",
            name = "Provider",
            fields = listOf(
                HarnessSchemaField(
                    key = "models",
                    label = "Models",
                    type = HarnessSchemaFieldType.JSON,
                    value = """[{"id":"old","custom":true}]""",
                    path = listOf("models"),
                )
            ),
        )
        val client = DiscoveryClient(buildJsonObject {
            put("status", "ok")
            put("models", Json.parseToJsonElement("""
                [{"id":"old","name":"Remote old"},{"id":"new","contextWindow":64000}]
            """))
        })
        val failures = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val actions = NativeHarnessProviderDiscoveryActions(
            clientProvider = { client },
            configProvider = { id -> config.takeIf { it.id == id } },
            settingsNamespaceProvider = { "llm-pi-ai" },
            updateConfig = { _, transform -> config = transform(config) },
            reportFailure = { code, _ -> failures += code },
            reportSuccess = { count -> counts += count },
        )

        actions.discover("provider")
        assertEquals("adt", client.namespace)
        assertEquals("providerDiscovery", client.method)
        val providerDiscoveryArgs = client.args
        assertEquals("llm-pi-ai", providerDiscoveryArgs["settingsNs"]?.toString()?.trim('"'))
        assertEquals(
            "provider",
            providerDiscoveryArgs.objectValue("request")?.get("provider")?.toString()?.trim('"')
        )
        assertEquals(listOf("old", "new"), config.discovery.candidates.map { it.id })
        assertEquals(setOf("new"), config.discovery.selectedIds)
        assertEquals("""[{"id":"old","custom":true}]""", config.fields.single().value)
        actions.setSelection("provider", listOf("old", "new"), selected = true)
        assertTrue(actions.adopt("provider"))

        val rows = Json.parseToJsonElement(config.fields.single().value).jsonArray
        assertEquals(2, rows.size)
        assertEquals("true", rows[0].jsonObject["custom"]?.toString())
        assertEquals("new", rows[1].jsonObject["id"]?.toString()?.trim('"'))
        assertTrue(config.discovery.candidates.isEmpty())
        assertEquals(listOf(2), counts)
        assertTrue(failures.isEmpty())
        assertEquals("Discovery and adoption must not persist provider settings", 1, client.callCount)
    }

    @Test
    fun discoveryRouteMapsEmptyAndStructuredErrorsWithoutLeakingTheRequest() = runBlocking {
        val empty = DiscoveryClient(buildJsonObject {
            put("status", "empty")
            put("models", buildJsonArray { })
        })
        val emptyResult = NativeHarnessCapabilities.discoverModels(
            empty,
            "llm-pi-ai",
            buildJsonObject {
                put("provider", "keyless-local")
                put("api", "openai-completions")
                put("baseURL", "http://10.0.2.2:8080")
            },
        )
        assertEquals(buildJsonArray { }, (emptyResult as HarnessRpcResult.Success).value)

        val failed = DiscoveryClient(buildJsonObject {
            put("status", "error")
            put("models", buildJsonArray { })
            put("errorCode", "PROVIDER_DISCOVERY_UNAUTHORIZED")
        })
        val failure = NativeHarnessCapabilities.discoverModels(
            failed,
            "llm-pi-ai",
            buildJsonObject { put("apiKey", "secret-must-not-be-echoed") },
        ) as HarnessRpcResult.Failure
        assertEquals("PROVIDER_DISCOVERY_UNAUTHORIZED", failure.error.code)
        assertFalse(failure.error.message.contains("secret-must-not-be-echoed"))
        assertFalse(failure.error.details.toString().contains("secret-must-not-be-echoed"))

        val unsupported = DiscoveryClient(buildJsonObject {
            put("status", "unsupported")
            put("models", buildJsonArray { })
        })
        val unsupportedResult = NativeHarnessCapabilities.discoverModels(
            unsupported,
            "llm-pi-ai",
            buildJsonObject {},
        ) as HarnessRpcResult.Failure
        assertEquals("PROVIDER_DISCOVERY_UNSUPPORTED", unsupportedResult.error.code)
    }

    @Test
    fun discoveryRoutePreservesEmptyInventoryFromClientValue() = runBlocking {
        val wrapped = DiscoveryClient(buildJsonObject {
            put("status", "empty")
            putJsonArray("models") {}
        })
        val result = NativeHarnessCapabilities.discoverModels(
            wrapped,
            "llm-pi-ai",
            buildJsonObject { put("provider", "keyless-local") },
        )

        assertTrue(result is HarnessRpcResult.Success)
        assertEquals(buildJsonArray {}, (result as HarnessRpcResult.Success).value)
    }

    @Test
    fun discoveryRouteMapsClientValueFailureWithoutUsingBackendMessage() = runBlocking {
        val wrapped = DiscoveryClient(buildJsonObject {
            put("status", "error")
            putJsonArray("models") {}
            put("errorCode", "PROVIDER_DISCOVERY_NETWORK")
        })
        val result = NativeHarnessCapabilities.discoverModels(
            wrapped,
            "llm-pi-ai",
            buildJsonObject { put("provider", "keyless-local") },
        ) as HarnessRpcResult.Failure

        assertEquals("PROVIDER_DISCOVERY_NETWORK", result.error.code)
        assertFalse(result.error.message.contains("private-backend"))
        assertFalse(result.error.message.contains("secret"))
    }

    @Test
    fun emptyModelFieldStartsAnArrayAndInvalidExistingJsonIsRecoverable() {
        val merged = mergeNativeHarnessDiscoveredModels(
            existingText = "",
            candidates = listOf(HarnessDiscoveredModelUi("new")),
            selectedIds = setOf("new"),
        )!!
        assertTrue(merged.contains("\"new\""))
        assertNull(
            mergeNativeHarnessDiscoveredModels(
                existingText = "{\"id\":\"wrong-shape\"}",
                candidates = listOf(HarnessDiscoveredModelUi("new")),
                selectedIds = setOf("new"),
            )
        )
        assertFalse(isNativeHarnessProviderModelsField(HarnessSchemaField("api", "API", type = HarnessSchemaFieldType.TEXT)))
        assertTrue(isNativeHarnessProviderModelsField(HarnessSchemaField("route.models", "Models", type = HarnessSchemaFieldType.JSON)))
    }

    @Test
    fun oversizedDiscoveryReplyIsRejectedInsteadOfSilentlyTruncated() {
        val oversized = buildJsonArray {
            repeat(NATIVE_HARNESS_MAX_DISCOVERED_MODELS + 1) { index ->
                add(buildJsonObject { put("id", "model-$index") })
            }
        }
        assertNull(parseNativeHarnessDiscoveredModels(oversized))
    }

    private class DiscoveryClient(private val result: JsonElement) : HarnessClient {
        override val state = MutableStateFlow(HarnessConnectionState.READY)
        var namespace: String = ""
        var method: String = ""
        var callCount: Int = 0
        var args: JsonObject = kotlinx.serialization.json.buildJsonObject { }

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String,
        ): HarnessRpcResult {
            callCount++
            this.namespace = namespace
            this.method = method
            this.args = args
            return HarnessRpcResult.Success(result)
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy,
        ): Flow<JsonElement> = emptyFlow()

        override fun close() = Unit
    }
}
