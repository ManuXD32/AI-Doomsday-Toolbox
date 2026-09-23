package com.example.llamadroid.harness

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Stores the small amount of Android-owned model metadata that Harness cannot
 * persist in its provider settings.  The wire id is the key, so two providers
 * that happen to display the same basename never overwrite one another.
 */
class HarnessLocalModelCapabilityStore(context: Context) {
    data class Override(
        val contextTokens: Long? = null,
        val maxOutputTokens: Long? = null,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun set(wireId: String, contextTokens: Long?, maxOutputTokens: Long?) {
        val key = wireId.trim()
        require(key.isNotEmpty()) { "MODEL_WIRE_ID_REQUIRED" }
        val sanitized = Override(
            contextTokens = contextTokens?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
            maxOutputTokens = maxOutputTokens?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
        )
        val values = read().toMutableMap()
        if (sanitized.contextTokens == null && sanitized.maxOutputTokens == null) {
            values.remove(key)
        } else {
            values[key] = sanitized
        }
        val json = buildJsonObject {
            values.toSortedMap().forEach { (id, value) ->
                put(id, buildJsonObject {
                    value.contextTokens?.let { put(CONTEXT_KEY, it) }
                    value.maxOutputTokens?.let { put(OUTPUT_KEY, it) }
                })
            }
        }
        check(preferences.edit().putString(PREFERENCES_KEY, json.toString()).commit()) {
            "MODEL_CAPABILITY_OVERRIDE_WRITE_FAILED"
        }
    }

    fun read(): Map<String, Override> {
        val raw = preferences.getString(PREFERENCES_KEY, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (id, element) ->
                val row = element.jsonObject
                val value = Override(
                    contextTokens = row[CONTEXT_KEY]?.jsonPrimitive?.longOrNull
                        ?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
                    maxOutputTokens = row[OUTPUT_KEY]?.jsonPrimitive?.longOrNull
                        ?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
                )
                value.takeIf { it.contextTokens != null || it.maxOutputTokens != null }
                    ?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Applies user overrides to the Android bridge model list without changing wire IDs. */
    fun apply(catalog: JsonObject): JsonObject {
        val overrides = read()
        if (overrides.isEmpty()) return catalog
        val rows = catalog["data"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return catalog
        return buildJsonObject {
            catalog.forEach { (key, value) ->
                if (key != "data") put(key, value)
            }
            put("data", buildJsonArray {
                rows.forEach { element ->
                    val row = runCatching { element.jsonObject }.getOrNull()
                    val id = row?.get("id")?.jsonPrimitive?.contentOrNull
                    val override = id?.let(overrides::get)
                    if (row == null || override == null) {
                        add(element)
                    } else {
                        add(buildJsonObject {
                            row.forEach { (key, value) ->
                                if (key !in setOf("context_length", "max_output_tokens", "capabilitySource")) {
                                    put(key, value)
                                }
                            }
                            override.contextTokens?.let { put("context_length", it) }
                            override.maxOutputTokens?.let { put("max_output_tokens", it) }
                            put("capabilitySource", "explicit")
                        })
                    }
                }
            })
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "harness_local_model_capabilities"
        private const val PREFERENCES_KEY = "overrides"
        private const val CONTEXT_KEY = "contextTokens"
        private const val OUTPUT_KEY = "maxOutputTokens"
        private const val MAX_CAPABILITY_TOKENS = 16_777_216L
    }
}
