package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessSettingsSchemaTest {
    private fun pinnedProviderSchema(): JsonObject = requireNotNull(
        javaClass.getResourceAsStream("/harness/llm-pi-ai-alpha2-schema.json")
    ).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    @Test
    fun pinnedProviderEnvelopeExposesTypedEditableFieldsThroughDictionaryPath() {
        val profile = buildJsonObject {
            put("baseURL", "http://127.0.0.1:8123/v1")
            put("api", "openai-completions")
            put("apiKeyEnv", "QA_API_KEY")
        }
        val namespace = buildJsonObject {
            put("ns", "llm-pi-ai")
            put("schema", pinnedProviderSchema())
            putJsonObject("value") { putJsonObject("providers") { put("qa", profile) } }
        }
        val binding = NativeHarnessProviderBinding("qa", "QA", "llm-pi-ai", listOf("providers", "qa"), 0, profile, "QA_API_KEY")
        val fields = harnessProviderFields(binding, listOf(namespace)).associateBy { it.key.substringAfterLast('.') }
        assertEquals(HarnessSchemaFieldType.TEXT, fields.getValue("baseURL").type)
        assertEquals("http://127.0.0.1:8123/v1", fields.getValue("baseURL").value)
        assertEquals(HarnessSchemaFieldType.CHOICE, fields.getValue("api").type)
        assertTrue("openai-completions" in fields.getValue("api").options)
        assertEquals(HarnessSchemaFieldType.INTEGER, fields.getValue("defaultContextWindow").type)
        assertTrue(fields.getValue("defaultContextWindow").value.toInt() > 0)
        assertEquals(HarnessSchemaFieldType.JSON, fields.getValue("models").type)
        assertEquals(HarnessSchemaFieldType.JSON, fields.getValue("headers").type)
        // The official provider editor keeps apiKeyEnv internal and renders a
        // separate write-only credential control.
        assertFalse(fields.containsKey("apiKeyEnv"))
        assertFalse(fields.keys.any { it == "uid" || it == "refs" })
    }

    @Test
    fun normalizationIsIdempotentForStructuralJsonSchemaFields() {
        val schema = Json.parseToJsonElement("""{
          "type":"object","properties":{
            "literal.key":{"type":"array","items":{"type":"string"}},
            "choice":{"type":"any","anyOf":[{"const":"a"},{"const":"b"}]}
          }
        }""").jsonObject
        val first = normalizeHarnessSettingsSchema(schema)
        assertEquals(first, normalizeHarnessSettingsSchema(first))
        val choice = first?.objectValue("properties")?.get("choice")?.jsonObject
        assertTrue(choice?.containsKey("anyOf") == true)
        assertTrue(first?.objectValue("properties")?.get("literal.key")?.jsonObject?.containsKey("items") == true)
    }

    @Test
    fun literalDotsUseLosslessPathMetadataAndHiddenDescriptorsWin() {
        val schema = Json.parseToJsonElement("""{
          "type":"object","properties":{
            "api.key":{"type":"string"},
            "token":{"type":"string"}
          }
        }""").jsonObject
        val value = buildJsonObject { put("api.key", "url"); put("token", "redacted") }
        val fields = parseHarnessSchemaFields(
            namespace = "qa",
            schema = schema,
            value = value,
            user = value,
            hiddenPaths = listOf(listOf("token"))
        )
        assertEquals(listOf("api.key"), fields.single().path)
        assertEquals("qa.api\\.key", fields.single().key)
        assertTrue(fields.single().isOverridden)
    }

    @Test
    fun literalNestedEmptyAndNamespacePathsHaveDistinctFieldIdentities() {
        val schema = Json.parseToJsonElement("""{
          "type":"object","properties":{
            "api.key":{"type":"string"},
            "api":{"type":"object","properties":{"key":{"type":"string"}}},
            "api\\key":{"type":"string"},
            "":{"type":"string"}
          }
        }""").jsonObject
        val fields = parseHarnessSchemaFields("qa", schema, buildJsonObject {})
        assertEquals(4, fields.map { it.key }.toSet().size)
        fields.forEach { field ->
            assertEquals(field.path, harnessSchemaPathFromKey(field.key.removePrefix("qa.")))
        }
        val namespaced = parseHarnessSchemaFields(
            "qa.api",
            Json.parseToJsonElement("""{"type":"object","properties":{"key":{"type":"string"}}}""").jsonObject,
            buildJsonObject {}
        ).single()
        assertFalse(namespaced.key in fields.map { it.key })
        assertEquals("qa\\.api.key", namespaced.key)
        assertTrue(harnessSchemaPathFromKey("api..key") == null)
        assertTrue(harnessSchemaPathFromKey("api\\x") == null)
    }

    @Test
    fun strictIntegerParserKeepsSafeLongsAndRejectsLossyValues() {
        assertEquals("9007199254740991", parseHarnessInteger("9007199254740991")?.contentOrNull)
        assertTrue(parseHarnessInteger("9007199254740992") == null)
        assertTrue(parseHarnessInteger(Long.MIN_VALUE.toString()) == null)
        assertTrue(parseHarnessInteger("not-a-number") == null)
    }

    @Test
    fun readOnlyObjectKeepsItsNestedControlsReadOnly() {
        val schema = Json.parseToJsonElement("""{
          "type":"object","readOnly":true,"properties":{
            "options":{"type":"object","properties":{"enabled":{"type":"boolean"}}}
          }
        }""").jsonObject
        val fields = parseHarnessSchemaFields("qa", schema, buildJsonObject {})
        assertEquals(listOf("options", "enabled"), fields.single().path)
        assertFalse(fields.single().enabled)
    }

    @Test
    fun rootSecretDescriptorHidesScalarNamespaceWithoutDroppingEmptyPath() {
        val namespace = buildJsonObject {
            putJsonArray("secrets") {
                add(buildJsonObject { putJsonArray("path") {} })
            }
        }
        assertEquals(listOf(emptyList<String>()), harnessNamespaceSecretPaths(namespace))
        val fields = parseHarnessSchemaFields(
            namespace = "root-secret",
            schema = Json.parseToJsonElement("{\"type\":\"string\"}").jsonObject,
            value = JsonPrimitive("secret"),
            hiddenPaths = harnessNamespaceSecretPaths(namespace)
        )
        assertTrue(fields.isEmpty())
    }

    @Test
    fun providerMappingDerivesCredentialAndHonorsUserBaseAndSecretLayers() {
        val directory = listOf(buildJsonObject {
            put("provider", "qa-gateway")
            put("displayName", "QA gateway")
            put("settingsNs", "llm-pi-ai")
            putJsonArray("settingsPath") {
                add("providers")
                add("qa-gateway")
            }
        })
        val profile = buildJsonObject {
            put("api", "openai-completions")
            put("token", "redacted")
        }
        val namespace = buildJsonObject {
            put("ns", "llm-pi-ai")
            put("schema", Json.parseToJsonElement("""{
              "type":"object","properties":{
                "providers":{"type":"object","additionalProperties":{
                  "type":"object","properties":{
                    "api":{"type":"string"},"token":{"type":"string"}
                  }
                }}
              }
            }""").jsonObject)
            putJsonObject("value") { putJsonObject("providers") { put("qa-gateway", profile) } }
            putJsonObject("user") { putJsonObject("providers") { put("qa-gateway", profile) } }
            putJsonObject("base") {}
            putJsonArray("secrets") {
                add(buildJsonObject {
                    putJsonArray("path") { add("providers"); add("qa-gateway"); add("token") }
                })
            }
        }
        val binding = buildHarnessProviderBindings(directory, listOf(namespace)).getValue("qa-gateway")
        assertEquals(null, binding.apiKeyReference)
        assertTrue(binding.credentialReferenceDerived)
        assertTrue(binding.canDelete)
        assertEquals(listOf(listOf("token")), binding.secretPaths)
        val fields = harnessProviderFields(binding, listOf(namespace)).map { it.path }
        assertEquals(listOf(listOf("api")), fields)
        assertTrue(nativeHarnessProviderCredentialOptional(binding))
        assertTrue(nativeHarnessProviderCredentialWritable(binding, null))
        assertFalse(nativeHarnessProviderCredentialWritable(binding.copy(writable = false), null))

        val configuredBinding = binding.copy(apiKeyReference = "QA_GATEWAY_API_KEY")
        assertFalse(nativeHarnessProviderCredentialOptional(configuredBinding))
        assertTrue(
            nativeHarnessProviderCredentialWritable(
                configuredBinding,
                buildJsonObject { put("writable", true) }
            )
        )
        assertFalse(nativeHarnessProviderCredentialWritable(configuredBinding, null))
        assertFalse(
            nativeHarnessProviderCredentialWritable(
                configuredBinding,
                buildJsonObject { put("writable", false) }
            )
        )
    }

    @Test
    fun scalarAndArrayNamespacesKeepRootPathForServerValidation() {
        val scalar = parseHarnessSchemaFields(
            namespace = "scalar",
            schema = Json.parseToJsonElement("{\"type\":\"integer\"}").jsonObject,
            value = JsonPrimitive(7L),
            writable = true
        ).single()
        assertEquals("scalar.value", scalar.key)
        assertEquals(emptyList<String>(), scalar.path)
        assertTrue(scalar.enabled)

        val array = parseHarnessSchemaFields(
            namespace = "array",
            schema = Json.parseToJsonElement("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}").jsonObject,
            value = Json.parseToJsonElement("[\"one\"]"),
            writable = true
        ).single()
        assertEquals(HarnessSchemaFieldType.JSON, array.type)
        assertEquals(emptyList<String>(), array.path)
    }

    @Test
    fun wholePinnedProviderNamespaceUsesOneDictionaryEditor() {
        val fields = parseHarnessSchemaFields("llm-pi-ai", pinnedProviderSchema(), buildJsonObject {})
        assertEquals("llm-pi-ai.providers", fields.single().key)
        assertEquals(HarnessSchemaFieldType.JSON, fields.single().type)
        assertEquals("{}", fields.single().value)
    }

    @Test
    fun hiddenAndSecretOnlySchemasDoNotReappearThroughValueFallback() {
        val schema = Json.parseToJsonElement("""{
          "uid":1,"refs":{
            "1":{"type":"object","dict":{"private":2,"hidden":3}},
            "2":{"type":"string","meta":{"role":"secret"}},
            "3":{"type":"string","meta":{"hidden":true}}
          }
        }""").jsonObject
        val value = buildJsonObject { put("private", "redacted"); put("hidden", "internal") }
        assertTrue(parseHarnessSchemaFields("qa", schema, value).isEmpty())
        val normalized = normalizeHarnessSettingsSchema(schema)
        assertTrue(parseHarnessSchemaFields("qa", normalized, value).isEmpty())
    }

    @Test
    fun recursiveReferencesAreBoundedAndRemainJsonEditable() {
        val schema = Json.parseToJsonElement("""{
          "uid":1,"refs":{"1":{"type":"object","dict":{"child":1}}}
        }""").jsonObject
        val fields = parseHarnessSchemaFields("qa", schema, buildJsonObject {})
        assertEquals("qa.child", fields.single().key)
        assertEquals(HarnessSchemaFieldType.JSON, fields.single().type)
    }

    @Test
    fun intersectedObjectsAndNullableValuesKeepTheirSettingsPaths() {
        val schema = Json.parseToJsonElement("""{
          "uid":1,"refs":{
            "1":{"type":"intersect","list":[2,3]},
            "2":{"type":"object","dict":{"enabled":4}},
            "3":{"type":"object","dict":{"optional":5}},
            "4":{"type":"boolean","meta":{"default":true,"disabled":true}},
            "5":{"type":"union","list":[6,7]},
            "6":{"type":"const","value":null},"7":{"type":"string"}
          }
        }""").jsonObject
        val value = buildJsonObject { put("optional", "hello") }
        val fields = parseHarnessSchemaFields("qa", schema, value).associateBy { it.key }
        assertFalse(fields.getValue("qa.enabled").enabled)
        assertEquals("true", fields.getValue("qa.enabled").value)
        assertEquals(HarnessSchemaFieldType.JSON, fields.getValue("qa.optional").type)
        assertEquals("\"hello\"", fields.getValue("qa.optional").value)
    }
}
