package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val HARNESS_MAX_SETTINGS_DOCUMENT_CHARS = 48_000
private const val MAX_SETTINGS_DOCUMENT_OPERATIONS = 256
private const val MAX_SETTINGS_DOCUMENT_DEPTH = 16

internal data class NativeHarnessSettingsDocumentSnapshot(
    val text: String,
    val baseline: Map<String, JsonObject>,
    val revisions: Map<String, Int>
)

internal sealed interface NativeHarnessSettingsDocumentEdit {
    data class Success(val operations: Map<String, List<JsonObject>>) : NativeHarnessSettingsDocumentEdit
    data class Failure(val code: String) : NativeHarnessSettingsDocumentEdit
}

/**
 * The upstream settings document omits a user section when it has no user
 * values, but a present user section must always be a plain JSON object.
 * Keeping this distinction explicit prevents malformed wire data from being
 * turned into an apparently empty, writable document.
 */
internal class NativeHarnessSettingsDocumentMalformedException : IllegalArgumentException(
    "Harness settings user section must be a JSON object"
)

internal fun harnessSettingsUserSection(namespace: JsonObject): JsonObject {
    val raw = namespace["user"] ?: return buildJsonObject {}
    return raw as? JsonObject ?: throw NativeHarnessSettingsDocumentMalformedException()
}

/**
 * Projects only the redacted user layer. The composed `value` contains defaults
 * and would turn a document save into a noisy rewrite of every default.
 */
internal fun parseHarnessSettingsDocumentSnapshot(
    describe: JsonObject
): NativeHarnessSettingsDocumentSnapshot {
    val namespaces = describe.objectArray("namespaces")
    val baseline = linkedMapOf<String, JsonObject>()
    val revisions = linkedMapOf<String, Int>()
    namespaces.forEach { namespace ->
        val name = namespace.string("ns") ?: return@forEach
        baseline[name] = harnessSettingsUserSection(namespace)
        revisions[name] = namespace.int("revision") ?: 0
    }
    val document = buildJsonObject {
        baseline.forEach { (name, user) -> put(name, user) }
    }
    val text = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), document)
    return NativeHarnessSettingsDocumentSnapshot(text, baseline, revisions)
}

internal fun diffHarnessSettingsDocument(
    snapshot: NativeHarnessSettingsDocumentSnapshot,
    editedText: String
): NativeHarnessSettingsDocumentEdit {
    if (editedText.length > HARNESS_MAX_SETTINGS_DOCUMENT_CHARS) {
        return NativeHarnessSettingsDocumentEdit.Failure("SETTINGS_DOCUMENT_TOO_LARGE")
    }
    val edited = runCatching { Json.parseToJsonElement(editedText) as? JsonObject }.getOrNull()
        ?: return NativeHarnessSettingsDocumentEdit.Failure("SETTINGS_DOCUMENT_INVALID")
    if (edited.keys.any { it !in snapshot.baseline }) {
        return NativeHarnessSettingsDocumentEdit.Failure("SETTINGS_DOCUMENT_INVALID")
    }
    val operations = linkedMapOf<String, List<JsonObject>>()
    var operationCount = 0
    snapshot.baseline.forEach { (namespace, previous) ->
        val next = when (val value = edited[namespace]) {
            null -> buildJsonObject {}
            is JsonObject -> value
            else -> return NativeHarnessSettingsDocumentEdit.Failure("SETTINGS_DOCUMENT_INVALID")
        }
        val namespaceOperations = mutableListOf<JsonObject>()
        if (!diffObject(previous, next, emptyList(), namespaceOperations, 0) ||
            operationCount + namespaceOperations.size > MAX_SETTINGS_DOCUMENT_OPERATIONS
        ) {
            return NativeHarnessSettingsDocumentEdit.Failure("SETTINGS_DOCUMENT_TOO_LARGE")
        }
        operationCount += namespaceOperations.size
        if (namespaceOperations.isNotEmpty()) operations[namespace] = namespaceOperations
    }
    return NativeHarnessSettingsDocumentEdit.Success(operations)
}

private fun diffObject(
    previous: JsonObject,
    next: JsonObject,
    prefix: List<String>,
    output: MutableList<JsonObject>,
    depth: Int
): Boolean {
    if (depth > MAX_SETTINGS_DOCUMENT_DEPTH) return false
    val keys = (previous.keys + next.keys).toSortedSet()
    keys.forEach { key ->
        val oldValue = previous[key]
        val newValue = next[key]
        val path = prefix + key
        when {
            oldValue == null && newValue != null -> output += setHarnessSettingsOperation(path, newValue)
            oldValue != null && newValue == null -> output += unsetHarnessSettingsOperation(path)
            oldValue is JsonObject && newValue is JsonObject -> {
                if (!diffObject(oldValue, newValue, path, output, depth + 1)) return false
            }
            oldValue != newValue && newValue != null -> output += setHarnessSettingsOperation(path, newValue)
        }
        if (output.size > MAX_SETTINGS_DOCUMENT_OPERATIONS) return false
    }
    return true
}

private fun setHarnessSettingsOperation(path: List<String>, value: JsonElement): JsonObject = buildJsonObject {
    put("op", JsonPrimitive("set"))
    put("path", kotlinx.serialization.json.buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
    put("value", value)
}

private fun unsetHarnessSettingsOperation(path: List<String>): JsonObject = buildJsonObject {
    put("op", JsonPrimitive("unset"))
    put("path", kotlinx.serialization.json.buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
}
