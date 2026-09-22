package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonObject

/** Matches the pinned models page's `apiKeyEnv ?? deriveKeyRef(provider)` lookup. */
internal fun nativeHarnessProviderCredentialReference(
    binding: NativeHarnessProviderBinding
): String = binding.apiKeyReference ?: nativeCustomProviderCredentialReference(binding.providerId)

/**
 * A route is removable only when its settings path is user-owned and no
 * composition base supplies that same path. A resolved `value` alone cannot
 * answer this question because it merges base and user layers.
 */
internal fun nativeHarnessProviderIsRemovable(
    binding: NativeHarnessProviderBinding,
    namespace: JsonObject?
): Boolean {
    if (binding.settingsPath.isEmpty()) return false
    val userPath = harnessJsonAtPath(namespace?.objectValue("user"), binding.settingsPath)
    val basePath = harnessJsonAtPath(namespace?.objectValue("base"), binding.settingsPath)
    return userPath != null && basePath == null
}

