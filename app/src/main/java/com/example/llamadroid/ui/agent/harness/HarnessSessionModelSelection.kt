package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement

private const val CANONICAL_MANAGED_LLAMA_PROVIDER = "adt-llama-server"
private val LEGACY_MANAGED_LLAMA_PROVIDERS = setOf("adt", "adt-managed")

/**
 * Older Harness projections used the generic ADT provider for managed llama
 * rows. Keep that durable selection usable after the catalog split puts those
 * rows under the canonical managed-server provider.
 */
internal fun canonicalHarnessProviderId(
    providers: List<HarnessProviderOption>,
    providerId: String,
    modelId: String,
): String = if (
    modelId.startsWith("llama:") &&
    providerId in LEGACY_MANAGED_LLAMA_PROVIDERS &&
    providers.any { it.id == CANONICAL_MANAGED_LLAMA_PROVIDER }
) {
    CANONICAL_MANAGED_LLAMA_PROVIDER
} else {
    providerId
}

/** Restore the session's durable selection rather than another session's last visible picker. */
internal fun HarnessProviderUiState.withSessionSelection(selection: JsonElement?): HarnessProviderUiState {
    val requestedProviderId = selection?.string("provider") ?: return this
    val modelId = selection.string("model") ?: return this
    val providerId = canonicalHarnessProviderId(providers, requestedProviderId, modelId)
    val provider = providers.firstOrNull { it.id == providerId }
    val effort = selection.string("reasoningEffort")
    return copy(selectedProviderId = providerId, selectedModel = modelId,
        selectedReasoningEffort = effort,
        supportsThinking = modelId in provider?.reasoningModels.orEmpty(),
        thinkingEnabled = effort != null && effort != "none")
}
