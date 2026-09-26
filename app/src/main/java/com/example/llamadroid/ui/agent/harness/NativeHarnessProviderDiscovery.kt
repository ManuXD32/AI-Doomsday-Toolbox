package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The client already bounds the response body; this keeps large catalogs searchable without dropping rows. */
internal const val NATIVE_HARNESS_MAX_DISCOVERED_MODELS = 8192

internal fun parseNativeHarnessDiscoveredModels(value: JsonElement): List<HarnessDiscoveredModelUi>? {
    val array = value.jsonArrayOrNull() ?: return null
    if (array.size > NATIVE_HARNESS_MAX_DISCOVERED_MODELS) return null
    val seen = linkedSetOf<String>()
    val models = mutableListOf<HarnessDiscoveredModelUi>()
    for (element in array) {
        val row = element.jsonObjectOrNull() ?: continue
        val id = row.string("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: continue
        if (!seen.add(id)) continue
        models += HarnessDiscoveredModelUi(
            id = id,
            name = row.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 },
            contextWindow = row.long("contextWindow")?.takeIf { it > 0L },
            maxTokens = row.long("maxTokens")?.takeIf { it > 0L },
            inputModalities = row["inputModalities"]?.jsonArrayOrNull()
                ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                ?.map(String::trim)
                ?.filter { it.isNotEmpty() && it.length <= 32 }
                ?.distinct()
                ?.take(8)
                .orEmpty(),
        )
    }
    return models
}

/** Maps upstream discovery metadata to the persisted pi-ai model-row keys. */
internal fun nativeHarnessDiscoveredModelJson(model: HarnessDiscoveredModelUi) = buildJsonObject {
    put("id", model.id)
    model.name?.let { put("name", it) }
    model.contextWindow?.let { put("contextWindow", it) }
    model.maxTokens?.let { put("maxTokens", it) }
    if (model.inputModalities.isNotEmpty()) {
        putJsonArray("input") { model.inputModalities.forEach { add(JsonPrimitive(it)) } }
    }
}

internal fun isNativeHarnessProviderModelsField(field: HarnessSchemaField): Boolean =
    field.path.lastOrNull() == "models" || field.key.substringAfterLast('.') == "models"

/** Applies one visible-list selection change without enqueueing one action per row. */
internal fun updateNativeHarnessProviderDiscoverySelection(
    discovery: HarnessProviderDiscoveryUi,
    modelIds: Iterable<String>,
    selected: Boolean,
): HarnessProviderDiscoveryUi {
    val candidateIds = discovery.candidates.asSequence().map { it.id }.toSet()
    val requested = modelIds.asSequence()
        .filter(candidateIds::contains)
        .toSet()
    val next = discovery.selectedIds.toMutableSet()
    if (selected) next.addAll(requested) else next.removeAll(requested)
    return discovery.copy(selectedIds = next)
}

/** Adds only explicitly selected IDs and leaves every existing row/property untouched. */
internal fun mergeNativeHarnessDiscoveredModels(
    existingText: String,
    candidates: List<HarnessDiscoveredModelUi>,
    selectedIds: Set<String>,
): String? {
    val existing = if (existingText.isBlank()) {
        buildJsonArray { }
    } else {
        parseHarnessJsonValue(existingText) as? JsonArray ?: return null
    }
    val rows = existing.map { it.jsonObjectOrNull() ?: return null }
    val existingIds = rows.mapNotNull { it.string("id") }.toHashSet()
    return buildJsonArray {
        rows.forEach(::add)
        candidates.asSequence()
            .filter { it.id in selectedIds && it.id !in existingIds }
            .forEach { add(nativeHarnessDiscoveredModelJson(it)) }
    }.toString()
}

internal fun clearNativeHarnessProviderDiscovery(): HarnessProviderDiscoveryUi =
    HarnessProviderDiscoveryUi()
