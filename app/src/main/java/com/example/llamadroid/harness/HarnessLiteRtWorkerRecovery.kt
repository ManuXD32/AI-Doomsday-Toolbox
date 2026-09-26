package com.example.llamadroid.harness

import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.service.LITERT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.LITERT_PARAM_MTP_ENABLED
import com.example.llamadroid.service.LiteRtLmChatRequest
import com.example.llamadroid.service.LiteRtLmWorkerCrashedException
import com.example.llamadroid.service.LiteRtConversationOverride
import com.example.llamadroid.service.estimateLiteRtPromptTokens
import com.example.llamadroid.service.fitLiteRtConversationOverrideForContextWithSummary
import com.example.llamadroid.service.liteRtPromptContextBudget
import com.example.llamadroid.service.compactLiteRtToolDefinitionsForCapacity
import com.example.llamadroid.service.renderLiteRtPromptForEstimate
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

internal const val HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS = 16_384
internal const val HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS = 2_048

/** Runtime-only identity used to clear a degraded latch when the model settings change. */
internal data class HarnessLiteRtRequestConfiguration(
    val modelPath: String,
    val modelFilename: String,
    val contextTokens: Int,
    /** Saved/model output ceiling; per-request max_completion_tokens is excluded. */
    val stableOutputTokens: Int?,
    val backendMode: String,
    val mtpEnabled: Boolean,
    val thinkingEnabled: Boolean,
)

internal fun harnessLiteRtRequestConfiguration(
    request: LiteRtLmChatRequest,
    stableOutputTokens: Int? = null,
): HarnessLiteRtRequestConfiguration = HarnessLiteRtRequestConfiguration(
    modelPath = request.model.path,
    modelFilename = request.model.filename,
    contextTokens = request.chat.contextSize,
    stableOutputTokens = stableOutputTokens?.takeIf { it > 0 },
    backendMode = request.backendMode,
    mtpEnabled = request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean == true,
    thinkingEnabled = request.params["enable_thinking"] as? Boolean ?: true,
)

/**
 * Keeps a worker-kill downgrade for this Harness runtime without persisting
 * prompts, tool payloads, or generated content. A changed configuration is
 * deliberately treated as a new experiment and removes the old latch.
 */
internal class HarnessLiteRtDegradedModeLatch {
    private val profiles = ConcurrentHashMap<Long, HarnessLiteRtRequestConfiguration>()

    fun clear() {
        profiles.clear()
    }

    fun requestFor(
        modelId: Long,
        configuredRequest: LiteRtLmChatRequest,
        stableOutputTokens: Int? = null,
    ): LiteRtLmChatRequest {
        val configuredProfile = harnessLiteRtRequestConfiguration(configuredRequest, stableOutputTokens)
        val latchedProfile = profiles[modelId]
        return if (latchedProfile == configuredProfile) {
            boundedLiteRtWorkerRecoveryRequest(configuredRequest) ?: configuredRequest
        } else {
            latchedProfile?.let { profiles.remove(modelId, it) }
            configuredRequest
        }
    }

    fun recordWorkerCrash(modelId: Long, configuredProfile: HarnessLiteRtRequestConfiguration) {
        profiles[modelId] = configuredProfile
    }
}

private fun LiteRtLmChatRequest.estimatedInputTokensForRecovery(): Int? = when {
    conversationOverride != null -> estimateLiteRtPromptTokens(
        renderLiteRtPromptForEstimate(conversationOverride)
    )
    promptOverride != null -> estimateLiteRtPromptTokens(promptOverride)
    else -> null
}

private fun LiteRtLmChatRequest.recoveryContextCeiling(): Int {
    val requested = chat.contextSize.takeIf { it > 0 }
    val advertised = model.defaultLiteRtEngineMaxTokens()?.takeIf { it > 0 }
    return listOfNotNull(requested, advertised).minOrNull()
        ?: HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS
}

private fun LiteRtLmChatRequest.withCapacityCompactedTools(
    preferredContextTokens: Int,
    outputTokens: Int,
): LiteRtLmChatRequest {
    val conversation = conversationOverride ?: return this
    val requiredInputTokens = estimatedInputTokensForRecovery() ?: return this
    if (conversation.tools.isEmpty() ||
        requiredInputTokens <= liteRtPromptContextBudget(preferredContextTokens, outputTokens)
    ) {
        return this
    }
    val compactedTools = compactLiteRtToolDefinitionsForCapacity(conversation.tools)
    if (compactedTools == conversation.tools) return this
    val compacted = copy(conversationOverride = conversation.copy(tools = compactedTools))
    return if (
        compacted.estimatedInputTokensForRecovery()?.let { it < requiredInputTokens } == true
    ) {
        compacted
    } else {
        this
    }
}

/**
 * Creates a one-off bounded retry request without mutating saved settings or
 * dropping conversation content, media, history, or tools. Long tool prose may
 * be compacted while retaining every tool name, parameter type, and required
 * field. The retry never returns to a previously failing large context: if the
 * complete compacted prompt cannot fit the bounded context, preflight returns
 * an actionable over-limit error instead of silently removing a tool.
 */
internal fun boundedLiteRtWorkerRecoveryRequest(
    request: LiteRtLmChatRequest,
): LiteRtLmChatRequest? {
    if (!request.model.supportsCpu) return null

    val configuredOutput = (request.params[LITERT_PARAM_MAX_OUTPUT_TOKENS] as? Number)
        ?.toInt()
        ?.takeIf { it > 0 }
        ?: HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS
    val boundedOutput = configuredOutput.coerceAtMost(HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS)
    val boundedContext = request.recoveryContextCeiling()
        .coerceAtMost(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS)
    val compactedRequest = request.withCapacityCompactedTools(
        preferredContextTokens = boundedContext,
        outputTokens = boundedOutput,
    )
    val recoveryContext = boundedContext
    val mtpEnabled = request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean == true
    val switchesBackend = request.backendMode != LITERT_BACKEND_CPU

    if (recoveryContext == request.chat.contextSize &&
        boundedOutput == configuredOutput &&
        !mtpEnabled &&
        !switchesBackend &&
        compactedRequest.conversationOverride == request.conversationOverride
    ) {
        return null
    }

    return compactedRequest.copy(
        chat = compactedRequest.chat.copy(contextSize = recoveryContext),
        backendMode = LITERT_BACKEND_CPU,
        params = compactedRequest.params + mapOf(
            LITERT_PARAM_MTP_ENABLED to false,
            LITERT_PARAM_MAX_OUTPUT_TOKENS to boundedOutput,
        ),
    )
}

/**
 * Runs the same content/tool budget check used by the worker without starting
 * a worker. The complete tool list remains in the request; an over-limit result
 * is surfaced to the existing provider error mapping instead of becoming a
 * worker death followed by a silent partial-tool retry.
 */
internal fun preflightLiteRtHarnessRequest(request: LiteRtLmChatRequest) {
    val conversation = request.conversationOverride ?: request.promptOverride?.let {
        LiteRtConversationOverride(
            systemInstruction = "",
            initialMessages = emptyList(),
            userMessage = it,
        )
    } ?: return
    fitLiteRtConversationOverrideForContextWithSummary(
        conversation = conversation,
        model = request.model,
        contextSize = request.chat.contextSize,
        maxOutputTokens = (request.params[LITERT_PARAM_MAX_OUTPUT_TOKENS] as? Number)
            ?.toInt()
            ?.takeIf { it > 0 },
        backendMode = request.backendMode,
    )
}

internal fun Throwable.findLiteRtWorkerCrash(): LiteRtLmWorkerCrashedException? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is LiteRtLmWorkerCrashedException) return current
        current = current.cause
    }
    return null
}

/** Retries once only when a worker crash happened before visible model output. */
internal suspend fun <T> runLiteRtWorkerWithOneBoundedRecovery(
    request: LiteRtLmChatRequest,
    hasVisibleOutput: () -> Boolean,
    onRetry: suspend (LiteRtLmChatRequest) -> Unit,
    execute: suspend (LiteRtLmChatRequest) -> T,
    onWorkerCrash: (LiteRtLmWorkerCrashedException) -> Unit = {},
): T {
    try {
        return execute(request)
    } catch (failure: Throwable) {
        if (failure is CancellationException) {
            throw failure
        }
        val workerCrash = failure.findLiteRtWorkerCrash()
        if (workerCrash == null) throw failure
        // A visible partial answer still proves this runtime shape is unsafe for
        // the next turn; latch it even though this invocation cannot be replayed.
        onWorkerCrash(workerCrash)
        if (hasVisibleOutput()) throw failure
        val retryRequest = boundedLiteRtWorkerRecoveryRequest(request) ?: throw failure
        onRetry(retryRequest)
        // Deliberately do not catch this invocation: a second failure is returned
        // to Harness as a recoverable provider error rather than restarting again.
        return execute(retryRequest)
    }
}
