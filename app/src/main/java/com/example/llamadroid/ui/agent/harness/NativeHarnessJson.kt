package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

internal fun JsonElement.string(key: String): String? = jsonObjectOrNull()?.string(key)

internal fun JsonElement.int(key: String): Int? = jsonObjectOrNull()?.int(key)

internal fun JsonElement.long(key: String): Long? = jsonObjectOrNull()?.long(key)

internal fun JsonElement.boolean(key: String): Boolean? = jsonObjectOrNull()?.boolean(key)

internal fun JsonElement.objectValue(key: String): JsonObject? = jsonObjectOrNull()?.objectValue(key)

internal fun JsonElement.objectArray(key: String): List<JsonObject> = jsonObjectOrNull()?.objectArray(key).orEmpty()

internal fun JsonElement.objectArrayOrSelf(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it.jsonObjectOrNull() }
    is JsonObject -> objectArray("entries").ifEmpty {
        objectArray("plugins").ifEmpty { objectArray("bundles") }
    }
    else -> emptyList()
}

internal fun JsonElement.stringArray(key: String): List<String> = jsonObjectOrNull()?.stringArray(key).orEmpty()

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull

internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitiveOrNull()?.intOrNull

internal fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitiveOrNull()?.longOrNull

internal fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitiveOrNull()?.booleanOrNull

internal fun JsonObject.objectValue(key: String): JsonObject? = this[key]?.jsonObjectOrNull()

internal fun JsonObject.objectArray(key: String): List<JsonObject> = this[key]?.jsonArrayOrNull()
    ?.mapNotNull { it.jsonObjectOrNull() }.orEmpty()

internal fun JsonObject.stringArray(key: String): List<String> = this[key]?.jsonArrayOrNull()
    ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }.orEmpty()

internal fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

internal fun Boolean?.orDefault(default: Boolean): Boolean = this ?: default

internal fun parseHarnessJsonValue(value: String): JsonElement? = runCatching {
    Json.parseToJsonElement(value)
}.getOrNull()
