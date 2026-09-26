package com.example.llamadroid.ui.agent.harness

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private const val HARNESS_SCHEMA_MAX_DEPTH = 32
private const val HARNESS_SCHEMA_MAX_NODES = 4_096

/**
 * Decodes the pinned Schemastery `{uid, refs}` envelope without evaluating
 * schema code. The result is a plain, JSON-schema-like tree consumed by the
 * native editor. It is deliberately safe to call on an already-normalized
 * tree: all structural fields emitted here are preserved on a second pass.
 */
internal fun normalizeHarnessSettingsSchema(envelope: JsonObject?): JsonObject? {
    if (envelope == null) return null
    val references = envelope.objectValue("refs")
    var remaining = HARNESS_SCHEMA_MAX_NODES

    fun isReference(raw: JsonElement?): String? {
        val content = (raw as? JsonPrimitive)?.contentOrNull ?: return null
        return content.takeIf { references?.containsKey(it) == true }
    }

    fun decode(raw: JsonElement?, depth: Int, visiting: Set<String>): JsonObject {
        if (depth > HARNESS_SCHEMA_MAX_DEPTH || remaining-- <= 0) {
            return JsonObject(mapOf("type" to JsonPrimitive("any")))
        }
        val reference = isReference(raw)
        if (reference != null && references != null) {
            if (reference in visiting) return JsonObject(mapOf("type" to JsonPrimitive("any")))
            return decode(references[reference], depth + 1, visiting + reference)
        }
        val node = raw as? JsonObject ?: return JsonObject(mapOf("type" to JsonPrimitive("any")))
        val meta = node.objectValue("meta")
        val fields = linkedMapOf<String, JsonElement>()
        val declaredType = node.string("type") ?: when {
            node.objectValue("properties") != null || node.objectValue("dict") != null -> "object"
            node.get("enum")?.jsonArrayOrNull()?.all { it is JsonPrimitive && it.isString } == true -> "string"
            else -> "any"
        }
        var type = declaredType

        fun decodeSchemaValue(child: JsonElement?): JsonElement? {
            if (child == null) return null
            return if (child is JsonObject || isReference(child) != null) {
                decode(child, depth + 1, visiting)
            } else {
                // JSON-schema-style flags such as additionalProperties:false
                // are values, not schema nodes.
                child
            }
        }

        fun decodeSchemaArray(child: JsonElement?): JsonArray? = child?.jsonArrayOrNull()?.let { array ->
            JsonArray(array.map { item -> decodeSchemaValue(item) ?: JsonNull })
        }

        when (declaredType) {
            "object" -> {
                val properties = node.objectValue("dict") ?: node.objectValue("properties")
                properties?.let { source ->
                    fields["properties"] = JsonObject(
                        source.mapValues { (_, child) -> decode(child, depth + 1, visiting) }
                    )
                }
                decodeSchemaValue(node["additionalProperties"])?.let { fields["additionalProperties"] = it }
            }
            "dict" -> {
                type = "object"
                decodeSchemaValue(node["inner"] ?: node["items"])?.let {
                    fields["additionalProperties"] = it
                }
            }
            "array", "tuple" -> {
                type = "array"
                decodeSchemaValue(node["inner"])?.let { fields["items"] = it }
                    ?: decodeSchemaValue(node["items"])?.let { fields["items"] = it }
                if (node["prefixItems"] != null) {
                    fields["prefixItems"] = decodeSchemaArray(node["prefixItems"]) ?: JsonArray(emptyList())
                }
            }
            "union", "intersect" -> {
                val branchArray = node["list"]?.jsonArrayOrNull()
                    ?: node["anyOf"]?.jsonArrayOrNull()
                    ?: node["allOf"]?.jsonArrayOrNull()
                val branches = branchArray?.map { decode(it, depth + 1, visiting) }.orEmpty()
                val constants = branches.mapNotNull { it.get("const") as? JsonPrimitive }
                val isSchemasteryUnion = node["list"] != null || declaredType in setOf("union", "intersect")
                if (isSchemasteryUnion && constants.size == branches.size && constants.isNotEmpty() &&
                    constants.all { it.isString }
                ) {
                    type = "string"
                    fields["enum"] = JsonArray(constants)
                } else if (declaredType == "intersect" && branches.isNotEmpty() && branches.all {
                        it.string("type") == "object"
                    }) {
                    type = "object"
                    fields["properties"] = JsonObject(
                        branches.flatMap { it.objectValue("properties").orEmpty().entries }
                            .associate { it.toPair() }
                    )
                } else {
                    type = "any"
                    val key = if (declaredType == "intersect" || node["allOf"] != null) "allOf" else "anyOf"
                    fields[key] = JsonArray(branches)
                }
            }
            "const" -> {
                val value = node["value"] ?: node["const"] ?: JsonNull
                fields["const"] = value
                type = when {
                    value is JsonPrimitive && value.isString -> "string"
                    (value as? JsonPrimitive)?.booleanOrNull != null -> "boolean"
                    (value as? JsonPrimitive)?.doubleOrNull != null -> "number"
                    else -> "any"
                }
            }
            "lazy", "transform" -> {
                // Schemastery wrappers have no native editing behavior. Keep
                // their resolved inner shape while retaining its metadata.
                fields.putAll(decode(node["inner"], depth + 1, visiting))
            }
            "natural" -> type = "integer"
            "date", "regexp", "regExp" -> type = "string"
            "number" -> if (meta?.get("step")?.jsonPrimitiveOrNull()?.doubleOrNull == 1.0) {
                type = "integer"
            }
        }

        // Wrappers are flattened, but the resolved type must still be
        // explicit so a second normalization pass cannot reinterpret the
        // flattened node as an unrelated `any` schema.
        fields["type"] = fields["type"] ?: JsonPrimitive(type.ifBlank { "any" })

        // Preserve normalized structural fields, including fields that may be
        // emitted by a future release or by a plain JSON schema fixture.
        listOf(
            "title", "description", "enum", "default", "readOnly", "hidden",
            "items", "prefixItems", "anyOf", "allOf", "oneOf", "const",
            "required", "min", "max", "step", "pattern", "role", "collapse",
            "link", "comment", "badges", "additionalProperties"
        ).forEach { key ->
            val value = node[key] ?: return@forEach
            if (key == "items" || key == "anyOf" || key == "allOf" || key == "oneOf" ||
                key == "prefixItems" || key == "additionalProperties"
            ) {
                // Do not replace a canonical conversion (for example union
                // constants -> enum) with the source wrapper on the second
                // normalization pass.
                if (!fields.containsKey(key)) {
                    fields[key] = if (key == "items" || key == "additionalProperties") {
                        decodeSchemaValue(value) ?: value
                    } else {
                        decodeSchemaArray(value) ?: value
                    }
                }
            } else if (!fields.containsKey(key)) {
                fields[key] = value
            }
        }

        meta?.get("default")?.let { fields["default"] = it }
        meta?.get("description")?.let { description ->
            val localized = description as? JsonObject
            val text = localized?.get(Locale.getDefault().language)
                ?: localized?.get("en")
                ?: localized?.get("")
                ?: description
            if (text is JsonPrimitive) fields["description"] = text
        }
        // Schemastery stores field constraints and roles in meta. Keep them
        // in the normalized tree so the server remains authoritative while
        // native controls can display/forward the declared metadata.
        listOf(
            "required", "disabled", "collapse", "badges", "link", "comment",
            "pattern", "max", "min", "step", "role", "extra"
        ).forEach { key ->
            val value = meta?.get(key) ?: return@forEach
            if (!fields.containsKey(key)) fields[key] = value
        }
        if (meta?.boolean("hidden") == true || meta?.string("role") == "secret") {
            fields["hidden"] = JsonPrimitive(true)
        }
        if (meta?.boolean("disabled") == true || declaredType in setOf("function", "never", "const")) {
            fields["readOnly"] = JsonPrimitive(true)
        }
        return JsonObject(fields)
    }

    return decode(if (references != null) envelope["uid"] else envelope, 0, emptySet())
}

/** Schema paths traverse declared object fields and dictionary values. */
internal fun harnessSettingsSchemaAtPath(schema: JsonObject?, path: List<String>): JsonObject? {
    var current = normalizeHarnessSettingsSchema(schema)
    for (segment in path) {
        current = current?.objectValue("properties")?.get(segment)?.jsonObjectOrNull()
            ?: current?.objectValue("additionalProperties")
            ?: return null
    }
    return current
}
