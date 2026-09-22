package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal sealed interface NativeHarnessProviderSavePlan {
    data class Ready(val operations: List<JsonObject>) : NativeHarnessProviderSavePlan

    data class Invalid(
        val code: String,
        val detail: String
    ) : NativeHarnessProviderSavePlan
}

/**
 * Builds the provider editor's path operations from the effective form state.
 * Optional schema fields that are absent from the effective profile stay absent;
 * the editor must not turn them into empty strings, nulls, or zeroes on Save.
 */
internal fun buildNativeHarnessProviderSavePlan(
    binding: NativeHarnessProviderBinding,
    config: HarnessProviderConfigUi
): NativeHarnessProviderSavePlan {
    val bindingPrefix = harnessSchemaKeyPath(listOf(binding.settingsNamespace) + binding.settingsPath)
    return try {
        val operations = buildList {
            config.fields.forEach { field ->
                if (!field.enabled) return@forEach
                val relative = field.path.takeIf { it.isNotEmpty() }
                    ?: harnessProviderFieldPath(bindingPrefix, field.key)
                    ?: return@forEach
                val current = harnessJsonAtPath(binding.value, relative)
                if (current == null && field.value.isBlank()) return@forEach
                when (val decision = parseProviderField(field)) {
                    is NativeHarnessProviderFieldDecision.Invalid -> {
                        throw NativeHarnessProviderFieldInvalid(decision.code, decision.detail)
                    }
                    NativeHarnessProviderFieldDecision.Clear -> {
                        if (current != null && current != JsonNull && current != JsonPrimitive("")) {
                            add(NativeHarnessCapabilities.unsetOperation(binding.settingsPath + relative))
                        }
                    }
                    NativeHarnessProviderFieldDecision.Skip -> Unit
                    is NativeHarnessProviderFieldDecision.Set -> {
                        val isAbsentBlankText = current == null && decision.value == JsonPrimitive("")
                        if (!isAbsentBlankText && (current == null || current != decision.value)) {
                            add(NativeHarnessCapabilities.setOperation(binding.settingsPath + relative, decision.value))
                        }
                    }
                }
            }
        }
        if (
            operations.isEmpty() &&
            binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE &&
            binding.settingsPath.isNotEmpty() &&
            binding.value == null &&
            binding.user == null &&
            binding.base == null
        ) {
            NativeHarnessProviderSavePlan.Ready(
                listOf(
                    NativeHarnessCapabilities.setOperation(
                        binding.settingsPath,
                        buildJsonObject {}
                    )
                )
            )
        } else {
            NativeHarnessProviderSavePlan.Ready(operations)
        }
    } catch (invalid: NativeHarnessProviderFieldInvalid) {
        NativeHarnessProviderSavePlan.Invalid(invalid.code, invalid.detail)
    }
}

private sealed interface NativeHarnessProviderFieldDecision {
    data class Set(val value: JsonElement) : NativeHarnessProviderFieldDecision

    data object Clear : NativeHarnessProviderFieldDecision

    data object Skip : NativeHarnessProviderFieldDecision

    data class Invalid(val code: String, val detail: String) : NativeHarnessProviderFieldDecision
}

private class NativeHarnessProviderFieldInvalid(
    val code: String,
    val detail: String
) : IllegalArgumentException(detail)

private fun parseProviderField(field: HarnessSchemaField): NativeHarnessProviderFieldDecision = when (field.type) {
    HarnessSchemaFieldType.TOGGLE -> when (field.value.trim().lowercase()) {
        "true" -> NativeHarnessProviderFieldDecision.Set(JsonPrimitive(true))
        "false" -> NativeHarnessProviderFieldDecision.Set(JsonPrimitive(false))
        else -> NativeHarnessProviderFieldDecision.Invalid(
            "SETTINGS_FIELD_INVALID",
            "The provider field value is invalid"
        )
    }
    HarnessSchemaFieldType.INTEGER -> {
        val text = field.value.trim()
        if (text.isEmpty()) {
            NativeHarnessProviderFieldDecision.Clear
        } else {
            parseHarnessInteger(text)?.let { NativeHarnessProviderFieldDecision.Set(it) }
                ?: NativeHarnessProviderFieldDecision.Invalid(
                    "SETTINGS_FIELD_INVALID",
                    "The provider field value is invalid"
                )
        }
    }
    HarnessSchemaFieldType.DECIMAL -> {
        val text = field.value.trim()
        if (text.isEmpty()) {
            NativeHarnessProviderFieldDecision.Clear
        } else {
            parseHarnessDecimal(text)?.let { NativeHarnessProviderFieldDecision.Set(it) }
                ?: NativeHarnessProviderFieldDecision.Invalid(
                    "SETTINGS_FIELD_INVALID",
                    "The provider field value is invalid"
                )
        }
    }
    HarnessSchemaFieldType.JSON -> {
        if (field.value.isBlank()) {
            // A blank JSON control is also how a partial provider draft
            // represents an optional field that was never materialized.  Do
            // not unset an inherited/effective value in that case.  Reset
            // remains an explicit operation and is represented by an empty
            // user-owned field, matching the upstream models editor.
            if (field.isOverridden) {
                NativeHarnessProviderFieldDecision.Clear
            } else {
                NativeHarnessProviderFieldDecision.Skip
            }
        } else {
            parseHarnessJsonValue(field.value)?.let { NativeHarnessProviderFieldDecision.Set(it) }
                ?: NativeHarnessProviderFieldDecision.Invalid(
                    "SETTINGS_JSON_INVALID",
                    "Enter valid JSON for this Harness provider setting"
                )
        }
    }
    HarnessSchemaFieldType.CHOICE -> {
        if (field.value.isBlank()) {
            NativeHarnessProviderFieldDecision.Clear
        } else if (field.options.isNotEmpty() && field.value !in field.options) {
            NativeHarnessProviderFieldDecision.Invalid(
                "SETTINGS_FIELD_INVALID",
                "The provider field value is invalid"
            )
        } else {
            NativeHarnessProviderFieldDecision.Set(JsonPrimitive(field.value))
        }
    }
    HarnessSchemaFieldType.TEXT -> {
        if (field.value.isEmpty()) {
            NativeHarnessProviderFieldDecision.Set(JsonPrimitive(""))
        } else {
            NativeHarnessProviderFieldDecision.Set(JsonPrimitive(field.value))
        }
    }
}
