package com.example.llamadroid.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * llama-server re-parses `tool_calls[].function.arguments` of every history message with a strict JSON parser and rejects the whole
 * request when one is invalid. Android's lenient `org.json` accepts raw newlines in strings, trailing garbage, single quotes and
 * unquoted keys, so model-produced argument strings must be canonicalised before they are sent back.
 */
object ToolArgumentsJson {
    private val strict = Json

    /** Always strictly valid JSON object text. Valid compact input is returned unchanged (stable KV prefix). */
    fun canonical(raw: String?, fallback: Map<String, String> = emptyMap()): String {
        val text = raw?.trim().orEmpty()
        if (text.isNotEmpty()) {
            strictObject(text)?.let { obj ->
                return if (text.none { it < ' ' }) text else obj.toString()
            }
            recover(text)?.let { return it.toString() }
        }
        if (fallback.isNotEmpty()) {
            val obj = JsonObject(fallback.mapValues { JsonPrimitive(it.value) })
            return obj.toString().takeIf { strictObject(it) != null } ?: "{}"
        }
        return "{}"
    }

    private fun strictObject(text: String): JsonObject? =
        runCatching { strict.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun recover(text: String): JsonObject? {
        // Trailing garbage: cut at an earlier closing brace.
        var end = text.lastIndexOf('}')
        var tries = 0
        while (end > 0 && tries < 8) {
            strictObject(text.substring(0, end + 1))?.let { return it }
            end = text.lastIndexOf('}', end - 1)
            tries++
        }
        return runCatching { toElement(JSONObject(text)) as JsonObject }.getOrNull()
    }

    private fun toElement(value: Any?): JsonElement = when (value) {
        null, JSONObject.NULL -> JsonNull
        is JSONObject -> JsonObject(LinkedHashMap<String, JsonElement>().also { map ->
            value.keys().forEach { key -> map[key] = toElement(value.opt(key)) }
        })
        is JSONArray -> JsonArray((0 until value.length()).map { toElement(value.opt(it)) })
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
