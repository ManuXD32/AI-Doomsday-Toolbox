package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** JavaScript's lossless integer range used by Schemastery/JSON settings. */
internal const val HARNESS_SAFE_INTEGER_MAX: Long = 9_007_199_254_740_991L

internal data class NativeHarnessProviderBinding(
    val providerId: String,
    val displayName: String,
    val settingsNamespace: String,
    val settingsPath: List<String>,
    val revision: Int,
    val value: JsonObject?,
    val apiKeyReference: String?,
    /** Raw user-layer profile, relative to [settingsPath], when present. */
    val user: JsonObject? = null,
    /** Raw base-layer profile, relative to [settingsPath], when present. */
    val base: JsonObject? = null,
    /** Secret descriptor paths relative to the provider profile. */
    val secretPaths: List<List<String>> = emptyList(),
    /** Official provider removal is valid only for a user-only profile path. */
    val canDelete: Boolean = false,
    /** True when [apiKeyReference] was derived rather than declared in the profile. */
    val credentialReferenceDerived: Boolean = false,
    val writable: Boolean = true
)

/** Strict integer parser shared by provider and generic settings editors. */
internal fun parseHarnessInteger(value: String): JsonPrimitive? = value.trim()
    .toLongOrNull()
    ?.takeIf { it >= -HARNESS_SAFE_INTEGER_MAX && it <= HARNESS_SAFE_INTEGER_MAX }
    ?.let(::JsonPrimitive)

internal fun parseHarnessDecimal(value: String): JsonPrimitive? = value.trim()
    .toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let(::JsonPrimitive)

internal fun harnessValueForField(field: HarnessSchemaField?): JsonElement? = when (field?.type) {
    HarnessSchemaFieldType.TOGGLE -> JsonPrimitive(field.value.equals("true", ignoreCase = true))
    HarnessSchemaFieldType.INTEGER -> parseHarnessInteger(field.value)
    HarnessSchemaFieldType.DECIMAL -> parseHarnessDecimal(field.value)
    HarnessSchemaFieldType.JSON -> parseHarnessJsonValue(field.value)
    null -> null
    else -> JsonPrimitive(field.value)
}

internal fun buildHarnessNestedPatch(path: List<String>, value: JsonElement): JsonObject {
    require(path.isNotEmpty()) { "A settings patch path must contain at least one segment" }
    if (path.size == 1) return kotlinx.serialization.json.buildJsonObject { put(path.first(), value) }
    val tail = buildHarnessNestedPatch(path.drop(1), value)
    return kotlinx.serialization.json.buildJsonObject { put(path.first(), tail) }
}

/** True when [path] is a descendant of a redacted descriptor path. */
private fun isCoveredByHiddenPath(path: List<String>, hiddenPaths: List<List<String>>): Boolean =
    hiddenPaths.any { hidden ->
        hidden.isEmpty() || (path.size >= hidden.size && path.take(hidden.size) == hidden)
    }

private fun hasHiddenDescendant(path: List<String>, hiddenPaths: List<List<String>>): Boolean =
    hiddenPaths.any { hidden -> hidden.size > path.size && hidden.take(path.size) == path }

private fun userPathIsSet(user: JsonElement?, path: List<String>): Boolean {
    if (user == null) return false
    if (path.isEmpty()) {
        // Root scalar/array namespaces are represented directly by the user
        // layer. For object layers an empty object is the normal "no root
        // override" representation.
        return user !is JsonObject || user.isNotEmpty()
    }
    var current: JsonElement = user
    for (segment in path) {
        val objectNode = current as? JsonObject ?: return false
        if (!objectNode.containsKey(segment)) return false
        current = objectNode[segment] ?: return false
    }
    return true
}

private fun inferredHarnessType(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> when {
        value.booleanOrNull != null -> "boolean"
        value.longOrNull != null -> "integer"
        value.doubleOrNull != null -> "number"
        else -> "string"
    }
    is JsonObject, is JsonArray -> "object"
    else -> "string"
}

/**
 * Projects a normalized schema and redacted value layer into bounded native
 * fields. Paths are retained separately from dotted display keys so literal
 * keys such as `api.key` remain addressable without ambiguity.
 */
internal fun parseHarnessSchemaFields(
    namespace: String,
    schema: JsonObject?,
    value: JsonElement?,
    user: JsonElement? = null,
    writable: Boolean = true,
    hiddenPaths: List<List<String>> = emptyList(),
    hideCredentialReferences: Boolean = false,
    keyPrefix: String = harnessSchemaNamespaceKey(namespace)
): List<HarnessSchemaField> {
    val fields = mutableListOf<HarnessSchemaField>()
    val normalizedSchema = normalizeHarnessSettingsSchema(schema)

    fun visit(
        path: List<String>,
        schemaNode: JsonObject?,
        valueNode: JsonElement?,
        parentReadOnly: Boolean = false
    ) {
        if (schemaNode?.boolean("hidden") == true ||
            schemaNode?.string("role") == "secret" ||
            (hideCredentialReferences && schemaNode?.string("role") == "credential-ref") ||
            isCoveredByHiddenPath(path, hiddenPaths)
        ) return

        val readOnly = parentReadOnly || schemaNode?.boolean("readOnly") == true
        val properties = schemaNode?.objectValue("properties")
        if (!properties.isNullOrEmpty()) {
            properties.forEach { (key, child) ->
                val childSchema = child as? JsonObject
                val childValue = valueNode?.jsonObjectOrNull()?.get(key)
                visit(path + key, childSchema, childValue, readOnly)
            }
            return
        }
        // A dynamic JSON object cannot safely be rendered as one editable
        // value when a redaction descriptor points into it; fail closed so a
        // blind spot in the upstream redactor cannot expose a nested secret.
        if (hasHiddenDescendant(path, hiddenPaths)) return

        val key = "$keyPrefix.${if (path.isEmpty()) "value" else harnessSchemaKeyPath(path)}"
        val effectiveValue = valueNode ?: schemaNode?.get("default")
        val rawType = schemaNode?.string("type") ?: inferredHarnessType(effectiveValue)
        val type = when (rawType) {
            "boolean" -> HarnessSchemaFieldType.TOGGLE
            "integer", "natural" -> HarnessSchemaFieldType.INTEGER
            "number" -> HarnessSchemaFieldType.DECIMAL
            "array", "object", "any", "tuple", "union", "intersect" -> HarnessSchemaFieldType.JSON
            else -> if (schemaNode?.get("enum")?.jsonArrayOrNull()?.isNotEmpty() == true) {
                HarnessSchemaFieldType.CHOICE
            } else HarnessSchemaFieldType.TEXT
        }
        // Empty paths are valid settings mutations for a namespace root. The
        // Harness server owns whether the namespace accepts a scalar/array at
        // that root, so keep the control writable when the schema permits it.
        val enabled = writable && !readOnly
        fields += HarnessSchemaField(
            key = key,
            label = schemaNode?.string("title") ?: path.lastOrNull() ?: namespace,
            description = schemaNode?.string("description"),
            type = type,
            value = effectiveValue?.let {
                if (type != HarnessSchemaFieldType.JSON && it is JsonPrimitive) {
                    it.contentOrNull.orEmpty()
                } else {
                    it.toString()
                }
            }.orEmpty(),
            options = schemaNode?.stringArray("enum").orEmpty(),
            enabled = enabled,
            isOverridden = userPathIsSet(user, path),
            path = path
        )
    }

    visit(emptyList(), normalizedSchema, value)
    if (fields.isEmpty() && !isCoveredByHiddenPath(emptyList(), hiddenPaths) &&
        value != null && value !is JsonNull && schema == null
    ) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (isCoveredByHiddenPath(listOf(key), hiddenPaths) ||
                    hasHiddenDescendant(listOf(key), hiddenPaths)
                ) return@forEach
                val primitive = child as? JsonPrimitive
                fields += HarnessSchemaField(
                    key = "$keyPrefix.${harnessSchemaKeyPath(listOf(key))}",
                    label = key,
                    type = when {
                        primitive?.booleanOrNull != null -> HarnessSchemaFieldType.TOGGLE
                        primitive?.longOrNull != null -> HarnessSchemaFieldType.INTEGER
                        primitive?.doubleOrNull != null -> HarnessSchemaFieldType.DECIMAL
                        child is JsonArray || child is JsonObject -> HarnessSchemaFieldType.JSON
                        else -> HarnessSchemaFieldType.TEXT
                    },
                    value = if (child is JsonPrimitive) child.contentOrNull.orEmpty() else child.toString(),
                    enabled = writable,
                    isOverridden = userPathIsSet(user, listOf(key)),
                    path = listOf(key)
                )
            }
            is JsonArray, is JsonPrimitive -> fields += HarnessSchemaField(
                key = "$keyPrefix.value",
                label = namespace,
                type = when (value) {
                    is JsonPrimitive -> when {
                        value.booleanOrNull != null -> HarnessSchemaFieldType.TOGGLE
                        value.longOrNull != null -> HarnessSchemaFieldType.INTEGER
                        value.doubleOrNull != null -> HarnessSchemaFieldType.DECIMAL
                        else -> HarnessSchemaFieldType.TEXT
                    }
                    else -> HarnessSchemaFieldType.JSON
                },
                value = if (value is JsonPrimitive) value.contentOrNull.orEmpty() else value.toString(),
                enabled = writable,
                path = emptyList()
            )
            else -> Unit
        }
    }
    return fields
}
