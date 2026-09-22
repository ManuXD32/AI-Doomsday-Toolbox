package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

private fun relativeHarnessSecretPaths(
    paths: List<List<String>>,
    profilePath: List<String>
): List<List<String>> = paths.mapNotNull { path ->
    if (path.isEmpty()) {
        // A root descriptor redacts the whole namespace, including every
        // provider profile reached through its settings path.
        emptyList()
    } else if (path.size < profilePath.size || path.take(profilePath.size) != profilePath) {
        null
    } else {
        path.drop(profilePath.size)
    }
}

internal fun harnessNamespaceSecretPaths(namespace: JsonObject?): List<List<String>> {
    val secrets = namespace?.get("secrets") ?: return emptyList()
    return when (secrets) {
        is JsonArray -> secrets.mapNotNull { descriptor ->
            when (descriptor) {
                is JsonArray -> descriptor.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                is JsonObject -> descriptor["path"]?.jsonArrayOrNull()
                    ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                else -> null
            }
        }
        else -> emptyList()
    }
}

internal fun nativeHarnessProviderCredentialOptional(binding: NativeHarnessProviderBinding): Boolean =
    binding.settingsNamespace == NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE &&
        binding.apiKeyReference == null

/**
 * Projects the credential-store write capability into the provider card.
 *
 * Keyless custom routes have no declared `apiKeyEnv`, so the settings reader
 * deliberately does not call `credentials/describe` for them. The Android
 * credential store is still writable when the settings namespace is writable;
 * an explicit credential descriptor remains authoritative when one exists.
 */
internal fun nativeHarnessProviderCredentialWritable(
    binding: NativeHarnessProviderBinding,
    credential: JsonObject?
): Boolean = binding.writable && credential?.boolean("writable").orDefault(
    nativeHarnessProviderCredentialOptional(binding)
)

/** Joins the release provider directory with its redacted settings snapshot. */
internal fun buildHarnessProviderBindings(
    directory: List<JsonObject>,
    namespaces: List<JsonObject>,
    writable: Boolean = true
): Map<String, NativeHarnessProviderBinding> = directory.mapNotNull { entry ->
    val providerId = entry.string("provider") ?: return@mapNotNull null
    val settingsNamespace = entry.string("settingsNs") ?: return@mapNotNull null
    val settingsPath = entry.stringArray("settingsPath")
    val namespace = namespaces.firstOrNull { it.string("ns") == settingsNamespace }
    val value = harnessJsonAtPath(namespace?.get("value"), settingsPath)?.jsonObjectOrNull()
    val user = harnessJsonAtPath(namespace?.get("user"), settingsPath)?.jsonObjectOrNull()
    val base = harnessJsonAtPath(namespace?.get("base"), settingsPath)?.jsonObjectOrNull()
    val declaredReference = value?.string("apiKeyEnv")?.trim()?.takeIf(String::isNotEmpty)
    val relativeSecrets = relativeHarnessSecretPaths(
        harnessNamespaceSecretPaths(namespace),
        settingsPath
    )
    providerId to NativeHarnessProviderBinding(
        providerId = providerId,
        displayName = entry.string("displayName") ?: providerId,
        settingsNamespace = settingsNamespace,
        settingsPath = settingsPath,
        revision = namespace?.int("revision") ?: 0,
        value = value,
        // A missing apiKeyEnv is a valid keyless provider, especially for
        // localhost llama.cpp/llama-swap routes. Derive the conventional
        // reference only when the user explicitly chooses to add a key.
        apiKeyReference = declaredReference,
        user = user,
        base = base,
        secretPaths = relativeSecrets,
        canDelete = writable && value != null && settingsPath.isNotEmpty() && user != null && base == null,
        credentialReferenceDerived = declaredReference == null,
        writable = writable
    )
}.toMap()

internal fun harnessProviderFields(
    binding: NativeHarnessProviderBinding,
    namespaces: List<JsonObject>,
    writable: Boolean = binding.writable
): List<HarnessSchemaField> {
    val namespace = namespaces.firstOrNull { it.string("ns") == binding.settingsNamespace }
        ?: return emptyList()
    val schema = harnessSettingsSchemaAtPath(namespace.objectValue("schema"), binding.settingsPath)
    val value = harnessJsonAtPath(namespace.get("value"), binding.settingsPath)
    val prefix = harnessSchemaKeyPath(listOf(binding.settingsNamespace) + binding.settingsPath)
    return parseHarnessSchemaFields(
        namespace = prefix,
        schema = schema,
        value = value,
        user = binding.user,
        writable = writable,
        hiddenPaths = binding.secretPaths,
        hideCredentialReferences = true,
        keyPrefix = prefix
    )
}
