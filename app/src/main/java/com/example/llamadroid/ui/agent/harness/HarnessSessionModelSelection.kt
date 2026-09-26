package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonElement

/** Restore the session's durable selection rather than another session's last visible picker. */
internal fun HarnessProviderUiState.withSessionSelection(selection: JsonElement?): HarnessProviderUiState {
    val providerId = selection?.string("provider") ?: return this
    val modelId = selection.string("model") ?: return this
    val provider = providers.firstOrNull { it.id == providerId }
    val effort = selection.string("reasoningEffort")
    return copy(selectedProviderId = providerId, selectedModel = modelId,
        selectedReasoningEffort = effort,
        supportsThinking = modelId in provider?.reasoningModels.orEmpty(),
        thinkingEnabled = effort != null && effort != "none")
}
