package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AgentRuntimeBackend

/** Stable values shown by the Direct Agent settings surface. */
object AgentDirectUiDefaults {
    const val CONTEXT_TOKENS = 16_384
    const val PLAN_OUTPUT_TOKENS = 2_048
    const val BUILD_OUTPUT_TOKENS = 4_096
    const val SAFETY_RESERVE_TOKENS = 512
    const val THINKING_ENABLED = false
}

/**
 * The UI uses the same admission rule as the runtime when explaining whether
 * a request can fit. Keeping this pure makes the settings contract testable
 * without starting Compose or a provider.
 */
fun directAgentBudgetFits(
    inputTokens: Int,
    reservedOutputTokens: Int,
    contextTokens: Int = AgentDirectUiDefaults.CONTEXT_TOKENS
): Boolean = inputTokens >= 0 &&
    reservedOutputTokens >= 0 &&
    contextTokens > 0 &&
    inputTokens + reservedOutputTokens + AgentDirectUiDefaults.SAFETY_RESERVE_TOKENS <= contextTokens

/**
 * Selects models from the backend that will receive the Direct Agent request.
 * llama-server and llama-swap both expose OpenAI-compatible `/v1/models`;
 * showing the unrelated Ollama catalog here can silently route a run to the
 * wrong model on a multi-model endpoint.
 */
internal fun directAgentModelOptions(
    backend: AgentRuntimeBackend,
    ollamaModels: List<String>,
    openAiModels: List<String>,
    selectedModel: String?
): List<String> = buildList {
    addAll(
        when (backend) {
            AgentRuntimeBackend.OLLAMA -> ollamaModels
            AgentRuntimeBackend.LLAMA_SERVER,
            AgentRuntimeBackend.LLAMA_SWAP -> openAiModels
            AgentRuntimeBackend.LITERT -> emptyList()
        }
    )
    selectedModel?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
}.distinct()
