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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessSettingsTest {
    @Test
    fun resetUsesOfficialUnsetOperationAndRevision() = runBlocking {
        val client = RecordingClient()
        val errors = mutableListOf<String>()
        val actions = settingsActions(client, errors)

        actions.reset("general.timeout")

        val call = client.calls.single()
        assertEquals("settings", call.namespace)
        assertEquals("mutate", call.method)
        assertEquals("general", call.args["ns"]?.jsonPrimitive?.content)
        assertEquals(7, call.args["expectedRevision"]?.jsonPrimitive?.int)
        val operation = call.args["ops"]!!.jsonArray.single().jsonObject
        assertEquals("unset", operation["op"]?.jsonPrimitive?.content)
        assertEquals("timeout", operation["path"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun openDocumentReadsDescribeInsteadOfClaimingDesktopOpenSuccess() = runBlocking {
        val client = RecordingClient()
        client.describeResult = HarnessRpcResult.Success(describeValue())
        val box = StateBox(defaultState())
        val actions = settingsActions(client, mutableListOf(), box)

        actions.openDocument()

        val call = client.calls.single()
        assertEquals("settings", call.namespace)
        assertEquals("describe", call.method)
        assertTrue(call.args.isEmpty())
        assertEquals("{\n    \"general\": {\n        \"timeout\": 12\n    }\n}", box.value.settingsDocument?.text)
    }

    @Test
    fun openDocumentRejectsNonObjectUserLayersAndClearsEditor(): Unit = runBlocking {
        listOf<JsonElement>(
            JsonPrimitive("malformed"),
            buildJsonArray { add("malformed") }
        ).forEach { malformedUser ->
            val client = RecordingClient().also {
                it.describeResult = HarnessRpcResult.Success(describeValueWithUser(malformedUser))
            }
            val errors = mutableListOf<String>()
            val box = StateBox(defaultState())
            val actions = settingsActions(client, errors, box)

            actions.openDocument()

            assertEquals(listOf("SETTINGS_DOCUMENT_INVALID"), errors)
            assertEquals(null, box.value.settingsDocument)
            assertEquals(1, client.calls.count { it.method == "describe" })
        }
    }

    @Test
    fun openDocumentAcceptsRedactedRootSecretWithoutValueOrUser(): Unit = runBlocking {
        val client = RecordingClient().also {
            it.describeResult = HarnessRpcResult.Success(buildJsonObject {
                put("writable", true)
                put("hasDocument", true)
                putJsonArray("namespaces") {
                    add(buildJsonObject {
                        put("ns", "root-secret")
                        put("revision", 4)
                        putJsonArray("secrets") {
                            add(buildJsonObject {
                                putJsonArray("path") {}
                                put("set", true)
                            })
                        }
                    })
                }
            })
        }
        val errors = mutableListOf<String>()
        val box = StateBox(defaultState())
        val actions = settingsActions(client, errors, box)

        actions.openDocument()

        assertTrue(errors.isEmpty())
        assertEquals("{\n    \"root-secret\": {}\n}", box.value.settingsDocument?.text)
    }

    @Test
    fun saveDocumentUsesRevisionCheckedMutationsAndClosesEditor() = runBlocking {
        val client = RecordingClient().also { it.describeResult = HarnessRpcResult.Success(describeValue()) }
        client.mutateResult = HarnessRpcResult.Success(settingsView(revision = 8, timeout = 18))
        val box = StateBox(defaultState())
        val actions = settingsActions(client, mutableListOf(), box)

        actions.openDocument()
        actions.saveDocument("{\"general\":{\"timeout\":18}}")

        val call = client.calls.last()
        assertEquals("mutate", call.method)
        assertEquals(7, call.args["expectedRevision"]?.jsonPrimitive?.int)
        val operation = call.args["ops"]!!.jsonArray.single().jsonObject
        assertEquals("set", operation["op"]?.jsonPrimitive?.content)
        assertEquals("timeout", operation["path"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("18", operation["value"]?.jsonPrimitive?.content)
        assertEquals(null, box.value.settingsDocument)
    }

    @Test
    fun saveDocumentRejectsMalformedReturnedUserSectionAndKeepsEditorRecoverable(): Unit = runBlocking {
        val client = RecordingClient().also {
            it.describeResult = HarnessRpcResult.Success(describeValue())
            it.mutateResult = HarnessRpcResult.Success(buildJsonObject {
                put("ns", "general")
                put("revision", 8)
                putJsonArray("user") { add("malformed") }
            })
        }
        val errors = mutableListOf<String>()
        val box = StateBox(defaultState())
        val actions = settingsActions(client, errors, box)

        actions.openDocument()
        val originalText = box.value.settingsDocument?.text
        actions.saveDocument("{\"general\":{\"timeout\":18}}")

        assertEquals(listOf("SETTINGS_DOCUMENT_INVALID"), errors)
        assertEquals(false, box.value.settingsDocument?.isSaving)
        assertEquals(originalText, box.value.settingsDocument?.text)
        assertEquals(1, client.calls.count { it.method == "mutate" })
    }

    @Test
    fun saveKeepsOpeningSnapshotAndDoesNotReDescribeOverConcurrentFields() = runBlocking {
        val client = RecordingClient().also {
            it.describeResult = HarnessRpcResult.Success(describeValueWithTheme())
            it.mutateResult = HarnessRpcResult.Success(settingsView(revision = 8, timeout = 18, theme = "dark"))
        }
        val box = StateBox(defaultState())
        val actions = settingsActions(client, mutableListOf(), box)

        actions.openDocument()
        actions.saveDocument("{\"general\":{\"theme\":\"dark\",\"timeout\":18}}")

        assertEquals(1, client.calls.count { it.method == "describe" })
        val operations = client.calls.last().args["ops"]!!.jsonArray
        assertEquals(1, operations.size)
        assertEquals("timeout", operations.single().jsonObject["path"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun updateUsesOfficialSetOperation() = runBlocking {
        val client = RecordingClient()
        val actions = settingsActions(client, mutableListOf())

        actions.update("general.timeout", "18")

        val operation = client.calls.single().args["ops"]!!.jsonArray.single().jsonObject
        assertEquals("set", operation["op"]?.jsonPrimitive?.content)
        assertEquals("18", operation["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun creatingCustomProviderRefreshesSettingsAndModelCatalog() = runBlocking {
        val client = RecordingClient()
        val refreshes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var latest = defaultState().copy(
            provider = defaultState().provider.copy(
                customProviderDiscovery = HarnessProviderDiscoveryUi(
                    candidates = listOf(HarnessDiscoveredModelUi("draft-model")),
                    selectedIds = setOf("draft-model"),
                    hasRun = true,
                )
            )
        )
        val actions = NativeHarnessSettingsActions(
            clientProvider = { client },
            stateProvider = { latest },
            revisionProvider = { 7 },
            mutate = { transform -> latest = transform(latest) },
            refresh = { refreshes += "settings" },
            reportFailure = { code, _ -> errors += code },
            refreshModelCatalog = { refreshes += "catalog" },
        )

        actions.createCustomProvider(
            NativeHarnessCustomProviderRequest(
                route = "qa-provider",
                displayName = "QA provider",
                api = "openai-completions",
                baseUrl = "http://127.0.0.1:42156/v1",
                models = emptyList(),
            )
        )

        assertEquals(listOf("settings", "catalog"), refreshes)
        assertTrue(errors.isEmpty())
        assertEquals(
            listOf("adt/providerCreate"),
            client.calls.filter { it.namespace == "adt" }.map { "${it.namespace}/${it.method}" },
        )
        assertEquals(0, client.calls.count { it.namespace == "settings" && it.method == "mutate" })
        assertTrue(latest.provider.customProviderDiscovery.candidates.isEmpty())
        assertTrue(latest.provider.customProviderDiscovery.selectedIds.isEmpty())
    }

    @Test
    fun updateUsesLosslessFieldPathForLiteralDots() = runBlocking {
        val client = RecordingClient()
        val box = StateBox(
            NativeHarnessUiState(
                settingsWritable = true,
                schemaSections = listOf(
                    HarnessSchemaSection(
                        title = "general",
                        fields = listOf(
                            HarnessSchemaField(
                                key = "general.api.key",
                                label = "API key",
                                type = HarnessSchemaFieldType.TEXT,
                                value = "old",
                                path = listOf("api.key")
                            )
                        )
                    )
                )
            )
        )
        NativeHarnessSettingsActions(
            clientProvider = { client },
            stateProvider = { box.value },
            revisionProvider = { 7 },
            mutate = { transform -> box.value = transform(box.value) },
            refresh = {},
            reportFailure = { code, _ -> error(code) },
            refreshModelCatalog = {},
        ).update("general.api.key", "new")

        val path = client.calls.single().args["ops"]!!.jsonArray.single().jsonObject["path"]!!.jsonArray
        assertEquals(listOf("api.key"), path.map { it.jsonPrimitive.content })
    }

    @Test
    fun updateKeepsEmptyRootPathForScalarNamespace() = runBlocking {
        val client = RecordingClient()
        val box = StateBox(
            NativeHarnessUiState(
                settingsWritable = true,
                schemaSections = listOf(
                    HarnessSchemaSection(
                        title = "scalar",
                        fields = listOf(
                            HarnessSchemaField(
                                key = "scalar.value",
                                label = "Scalar",
                                type = HarnessSchemaFieldType.INTEGER,
                                value = "7",
                                path = emptyList()
                            )
                        )
                    )
                )
            )
        )
        NativeHarnessSettingsActions(
            clientProvider = { client },
            stateProvider = { box.value },
            revisionProvider = { 7 },
            mutate = { transform -> box.value = transform(box.value) },
            refresh = {},
            reportFailure = { code, _ -> error(code) },
            refreshModelCatalog = {},
        ).update("scalar.value", "8")

        val path = client.calls.single().args["ops"]!!.jsonArray.single().jsonObject["path"]!!.jsonArray
        assertTrue(path.isEmpty())
    }

    @Test
    fun collidingDottedNamesMutateTheirOwnWirePaths(): Unit = runBlocking {
        val schema = kotlinx.serialization.json.Json.parseToJsonElement("""{
          "type":"object","properties":{
            "api.key":{"type":"string"},
            "api":{"type":"object","properties":{"key":{"type":"string"}}}
          }
        }""").jsonObject
        val fields = parseHarnessSchemaFields("general", schema, buildJsonObject {})
        val client = RecordingClient()
        val errors = mutableListOf<String>()
        val box = StateBox(defaultState().copy(
            schemaSections = listOf(HarnessSchemaSection(title = "general", fields = fields))
        ))
        val actions = settingsActions(client, errors, box)
        actions.update(fields.single { it.path == listOf("api.key") }.key, "literal")
        actions.update(fields.single { it.path == listOf("api", "key") }.key, "nested")
        assertTrue(errors.isEmpty())
        assertEquals(
            listOf(listOf("api.key"), listOf("api", "key")),
            client.calls.map { call ->
                call.args["ops"]!!.jsonArray.single().jsonObject["path"]!!.jsonArray.map {
                    it.jsonPrimitive.content
                }
            }
        )
    }

    @Test
    fun invalidIntegerDoesNotSilentlyWriteZero() = runBlocking {
        val client = RecordingClient()
        val errors = mutableListOf<String>()
        val actions = settingsActions(client, errors)

        actions.update("general.timeout", "not-a-number")

        assertTrue(client.calls.isEmpty())
        assertEquals(listOf("SETTINGS_JSON_INVALID"), errors)
    }

    @Test
    fun schemaMarksUserLayerOverridesAndHonorsReadOnlyDocument() {
        val fields = parseHarnessSchemaFields(
            namespace = "general",
            schema = buildJsonObject {
                putJsonObject("properties") {
                    putJsonObject("timeout") { put("type", "integer") }
                }
            },
            value = buildJsonObject { put("timeout", 12) },
            user = buildJsonObject { put("timeout", 12) },
            writable = false
        )

        assertTrue(fields.single().isOverridden)
        assertFalse(fields.single().enabled)
    }

    private fun settingsActions(
        client: RecordingClient,
        errors: MutableList<String>,
        box: StateBox = StateBox(defaultState())
    ) = NativeHarnessSettingsActions(
        clientProvider = { client },
        stateProvider = { box.value },
        revisionProvider = { 7 },
        mutate = { transform -> box.value = transform(box.value) },
        refresh = {},
        reportFailure = { code, _ -> errors += code },
        refreshModelCatalog = {}
    )

    private fun defaultState() = NativeHarnessUiState(
        settingsWritable = true,
        settingsHasDocument = true,
        schemaSections = listOf(
            HarnessSchemaSection(
                title = "general",
                fields = listOf(
                    HarnessSchemaField(
                        key = "general.timeout",
                        label = "Timeout",
                        type = HarnessSchemaFieldType.INTEGER,
                        value = "12",
                        isOverridden = true
                    )
                )
            )
        )
    )

    private fun describeValue() = buildJsonObject {
        put("writable", true)
        put("hasDocument", true)
        putJsonArray("namespaces") {
            add(buildJsonObject {
                put("ns", "general")
                put("revision", 7)
                putJsonObject("schema") {}
                putJsonObject("value") { put("timeout", 12) }
                putJsonObject("user") { put("timeout", 12) }
            })
        }
    }

    private fun describeValueWithUser(user: JsonElement) = buildJsonObject {
        put("writable", true)
        put("hasDocument", true)
        putJsonArray("namespaces") {
            add(buildJsonObject {
                put("ns", "general")
                put("revision", 7)
                putJsonObject("schema") {}
                putJsonObject("value") { put("timeout", 12) }
                put("user", user)
            })
        }
    }

    private fun describeValueWithTheme() = buildJsonObject {
        put("writable", true)
        put("hasDocument", true)
        putJsonArray("namespaces") {
            add(buildJsonObject {
                put("ns", "general")
                put("revision", 7)
                putJsonObject("schema") {}
                putJsonObject("value") {
                    put("timeout", 12)
                    put("theme", "dark")
                }
                putJsonObject("user") {
                    put("timeout", 12)
                    put("theme", "dark")
                }
            })
        }
    }

    private fun settingsView(revision: Int, timeout: Int, theme: String = "dark") = buildJsonObject {
        put("ns", "general")
        put("revision", revision)
        putJsonObject("user") {
            put("timeout", timeout)
            put("theme", theme)
        }
    }

    private data class StateBox(var value: NativeHarnessUiState)

    private class RecordingClient : HarnessClient {
        data class Call(val namespace: String, val method: String, val args: JsonObject)

        val calls = mutableListOf<Call>()
        var describeResult: HarnessRpcResult = HarnessRpcResult.Success(buildJsonObject {
            put("writable", true)
            put("hasDocument", true)
            putJsonArray("namespaces") {}
        })
        var mutateResult: HarnessRpcResult = HarnessRpcResult.Success(JsonNull)
        override val state = MutableStateFlow(HarnessConnectionState.READY)

        override suspend fun call(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessCallPolicy,
            requestId: String
        ): HarnessRpcResult {
            calls += Call(namespace, method, args)
            if (namespace == "settings" && method == "describe") return describeResult
            if (namespace == "settings" && method == "mutate") return mutateResult
            if (namespace == "adt" && method == "providerCreate") {
                return HarnessRpcResult.Success(buildJsonObject { put("saved", true) })
            }
            return HarnessRpcResult.Success(JsonNull)
        }

        override fun stream(
            namespace: String,
            method: String,
            args: JsonObject,
            policy: HarnessStreamPolicy
        ): Flow<JsonElement> = emptyFlow()

        override suspend fun authenticate(launchUrl: String): HarnessAuthResult =
            HarnessAuthResult.Success("http://127.0.0.1")

        override fun adoptAuthenticatedEndpoint(origin: String, cookieHeader: String): HarnessAuthResult =
            HarnessAuthResult.Success(origin)

        override fun close() = Unit
    }
}
