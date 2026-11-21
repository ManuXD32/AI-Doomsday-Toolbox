package com.example.llamadroid.chat.cards.omni

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Lenient JSON accessors shared by [OmniParser] and [OmniParserExtras]. */
internal const val OMNI_MAX_TEXT = 400

internal fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

internal fun omniStrings(el: JsonElement?, max: Int): List<String> =
    (el as? JsonArray).orEmpty().mapNotNull { e ->
        if (e is JsonObject) e.first("label", "text", "title", "name", "value").str() else e.str()
    }.take(max)

internal fun JsonObject.first(vararg keys: String): JsonElement? =
    keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it !is JsonNull } }

internal fun JsonElement?.str(max: Int = OMNI_MAX_TEXT): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().take(max).ifEmpty { null }
}

internal fun JsonElement?.num(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return (p.doubleOrNull ?: p.content.trim().toDoubleOrNull())?.takeIf { it.isFinite() }
}

internal fun JsonElement?.flag(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return p.booleanOrNull ?: (p.doubleOrNull?.let { it != 0.0 }
        ?: (p.content.trim().lowercase() in setOf("yes", "si", "on", "true", "1")))
}
