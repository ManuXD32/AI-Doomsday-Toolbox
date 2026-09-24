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
import kotlinx.serialization.json.booleanOrNull
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
        val mtpEnabled: Boolean? = null,
        val thinkingEnabled: Boolean? = null,
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun set(wireId: String, contextTokens: Long?, maxOutputTokens: Long?) {
        val key = wireId.trim()
        require(key.isNotEmpty()) { "MODEL_WIRE_ID_REQUIRED" }
        val existing = read()[key]
        setOverrides(
            wireId = key,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
            mtpEnabled = existing?.mtpEnabled,
            thinkingEnabled = existing?.thinkingEnabled,
        )
    }

    fun setInferenceOptions(wireId: String, mtpEnabled: Boolean?, thinkingEnabled: Boolean?) {
        val key = wireId.trim()
        require(key.isNotEmpty()) { "MODEL_WIRE_ID_REQUIRED" }
        val existing = read()[key]
        setOverrides(
            wireId = key,
            contextTokens = existing?.contextTokens,
            maxOutputTokens = existing?.maxOutputTokens,
            mtpEnabled = mtpEnabled,
            thinkingEnabled = thinkingEnabled,
        )
    }

    /** Commits all per-model fields with one preference write. */
    fun setOverrides(
        wireId: String,
        contextTokens: Long?,
        maxOutputTokens: Long?,
        mtpEnabled: Boolean?,
        thinkingEnabled: Boolean?,
    ) {
        val key = wireId.trim()
        require(key.isNotEmpty()) { "MODEL_WIRE_ID_REQUIRED" }
        write(
            key,
            Override(
                contextTokens = contextTokens?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
                maxOutputTokens = maxOutputTokens?.takeIf { it in 1L..MAX_CAPABILITY_TOKENS },
                mtpEnabled = mtpEnabled,
                thinkingEnabled = thinkingEnabled,
            ),
        )
    }

    fun get(wireId: String): Override? = read()[wireId.trim()]

    private fun write(key: String, sanitized: Override) {
        val values = read().toMutableMap()
        if (
            sanitized.contextTokens == null &&
            sanitized.maxOutputTokens == null &&
            sanitized.mtpEnabled == null &&
            sanitized.thinkingEnabled == null
        ) {
            values.remove(key)
        } else {
            values[key] = sanitized
        }
        val json = buildJsonObject {
            values.toSortedMap().forEach { (id, value) ->
                put(id, buildJsonObject {
                    value.contextTokens?.let { put(CONTEXT_KEY, it) }
                    value.maxOutputTokens?.let { put(OUTPUT_KEY, it) }
                    value.mtpEnabled?.let { put(MTP_KEY, it) }
                    value.thinkingEnabled?.let { put(THINKING_KEY, it) }
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
                    mtpEnabled = row[MTP_KEY]?.jsonPrimitive?.booleanOrNull,
                    thinkingEnabled = row[THINKING_KEY]?.jsonPrimitive?.booleanOrNull,
                )
                value.takeIf {
                    it.contextTokens != null || it.maxOutputTokens != null ||
                        it.mtpEnabled != null || it.thinkingEnabled != null
                }
                    ?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Applies user overrides to the Android bridge model list without changing wire IDs. */
    fun apply(catalog: JsonObject): JsonObject {
        val overrides = read()
        if (overrides.values.none { it.contextTokens != null || it.maxOutputTokens != null }) return catalog
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
                    if (
                        row == null || override == null ||
                        (override.contextTokens == null && override.maxOutputTokens == null)
                    ) {
                        add(element)
                    } else {
                        add(buildJsonObject {
                            row.forEach { (key, value) ->
                                if (
                                    !(key == "context_length" && override.contextTokens != null &&
                                        !row.containsKey("effective_backend_context_length")) &&
                                    !(key == "max_output_tokens" && override.maxOutputTokens != null &&
                                        !row.containsKey("effective_backend_context_length")) &&
                                    !(key == "capabilitySource" && override.contextTokens != null &&
                                        !row.containsKey("effective_backend_context_length"))
                                ) {
                                    put(key, value)
                                }
                            }
                            if (!row.containsKey("effective_backend_context_length")) {
                                override.contextTokens?.let { put("context_length", it) }
                                override.maxOutputTokens?.let { put("max_output_tokens", it) }
                            }
                            if (override.contextTokens != null) put("capabilitySource", "explicit")
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
        private const val MTP_KEY = "mtpEnabled"
        private const val THINKING_KEY = "thinkingEnabled"
        private const val MAX_CAPABILITY_TOKENS = 16_777_216L
    }
}
