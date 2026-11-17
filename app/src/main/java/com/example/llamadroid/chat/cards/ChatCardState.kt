package com.example.llamadroid.chat.cards

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `stateJson` stored per answered card (table llama_message_card_state). */
object ChatCardState {
    data class ChoicesState(val selected: List<String>, val other: String?)

    fun encodeChoices(selected: List<String>, other: String?): String {
        val map = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("selected", JsonArray(selected.map { JsonPrimitive(it) }))
            if (!other.isNullOrBlank()) put("other", JsonPrimitive(other))
        }
        return JsonObject(map).toString()
    }

    fun encodeForm(values: Map<String, String>): String =
        JsonObject(mapOf("values" to JsonObject(values.mapValues { JsonPrimitive(it.value) }))).toString()

    fun encodeInput(answer: String): String =
        JsonObject(mapOf("text" to JsonPrimitive(answer))).toString()

    /** Persisted omni card state: variable values, checklist ticks per id, and the last text sent (if any). */
    data class OmniState(
        val values: Map<String, Double> = emptyMap(),
        val checks: Map<String, List<Boolean>> = emptyMap(),
        val sent: String? = null,
        /** Text-input contents and function-block formulas by block id. */
        val texts: Map<String, String> = emptyMap(),
    )

    fun encodeOmniValues(values: Map<String, Double>, checks: Map<String, List<Boolean>>, sent: String?,
        texts: Map<String, String> = emptyMap(),
    ): String {
        val map = buildMap<String, kotlinx.serialization.json.JsonElement> {
            put("values", JsonObject(values.filterValues { it.isFinite() }.mapValues { JsonPrimitive(it.value) }))
            put("checks", JsonObject(checks.mapValues { e -> JsonArray(e.value.map { JsonPrimitive(it) }) }))
            if (sent != null) put("sent", JsonPrimitive(sent))
            if (texts.isNotEmpty()) put("texts", JsonObject(texts.mapValues { JsonPrimitive(it.value) }))
        }
        return JsonObject(map).toString()
    }

    fun decodeOmni(stateJson: String): OmniState? = runCatching {
        val obj = Json.parseToJsonElement(stateJson).jsonObject
        OmniState(
            values = obj["values"]?.jsonObject.orEmpty().mapNotNull { (k, v) ->
                v.jsonPrimitive.doubleOrNull?.takeIf { it.isFinite() }?.let { k to it }
            }.toMap(),
            checks = obj["checks"]?.jsonObject.orEmpty().mapValues { (_, v) ->
                v.jsonArray.map { it.jsonPrimitive.booleanOrNull ?: false }
            },
            sent = obj["sent"]?.jsonPrimitive?.contentOrNull,
            texts = obj["texts"]?.jsonObject.orEmpty().mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }.toMap(),
        )
    }.getOrNull()

    fun decodeInput(stateJson: String): String? = runCatching {
        Json.parseToJsonElement(stateJson).jsonObject["text"]!!.jsonPrimitive.content
    }.getOrNull()

    fun decodeChoices(stateJson: String): ChoicesState? = runCatching {
        val obj = Json.parseToJsonElement(stateJson).jsonObject
        ChoicesState(
            selected = obj["selected"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            other = obj["other"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    fun decodeForm(stateJson: String): Map<String, String>? = runCatching {
        Json.parseToJsonElement(stateJson).jsonObject["values"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content }
    }.getOrNull()
}
