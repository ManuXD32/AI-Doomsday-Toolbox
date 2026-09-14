package com.example.llamadroid.service

import java.util.Locale

data class AgentDirectSlotIdentity(
    val endpointGeneration: String,
    val modelConfiguration: String,
    val conversationId: String,
    val coreSchemaHash: String = "",
    val projectContextHash: String = "",
    val chatTemplate: String = "provider_default"
)

/**
 * Pure coordination policy extracted from AgentService. Android lifecycle and UI
 * remain in the service façade; ordering, state projection, cache ownership, and
 * bounded recovery decisions live here so they can be tested without a Service.
 */
object AgentDirectRuntimeCoordinator {
    const val VERSION = 1
    const val MAX_IDENTICAL_FAILURES = 2
    const val MAX_NO_PROGRESS_TURNS = 3
    const val MAX_MALFORMED_REPAIRS = 1

    fun resolveState(
        projectMode: String?,
        pendingPlanApproval: Boolean,
        pendingQuestion: Boolean,
        pendingToolApproval: Boolean,
        paused: Boolean,
        complete: Boolean
    ): DirectAgentState = when {
        complete || projectMode?.trim()?.uppercase(Locale.ROOT) == "COMPLETE" ->
            DirectAgentState.COMPLETE
        paused -> DirectAgentState.PAUSED
        pendingToolApproval -> DirectAgentState.AWAITING_TOOL_APPROVAL
        pendingPlanApproval -> DirectAgentState.AWAITING_PLAN_APPROVAL
        pendingQuestion -> DirectAgentState.AWAITING_INPUT
        projectMode?.trim()?.uppercase(Locale.ROOT) == "VERIFY" -> DirectAgentState.VERIFY
        projectMode?.trim()?.uppercase(Locale.ROOT) == "BUILD" -> DirectAgentState.BUILD
        else -> DirectAgentState.PLAN
    }

    /** A phase or role is intentionally absent from Direct slot ownership. */
    fun slotOwner(identity: AgentDirectSlotIdentity): LlamaSlotOwnerKey =
        LlamaSlotOwnerKey(
            endpointGeneration = identity.endpointGeneration,
            modelConfiguration = listOf(
                identity.modelConfiguration,
                "template=${identity.chatTemplate}",
                "core=${identity.coreSchemaHash}",
                "project=${identity.projectContextHash}"
            ).joinToString("|"),
            conversationId = identity.conversationId,
            agentSessionId = "direct"
        )

    fun mayRepairMalformedCall(previousRepairCount: Int): Boolean =
        previousRepairCount < MAX_MALFORMED_REPAIRS

    fun shouldPauseForLoop(
        identicalFailureCount: Int,
        consecutiveNoProgressTurns: Int
    ): Boolean = identicalFailureCount >= MAX_IDENTICAL_FAILURES ||
        consecutiveNoProgressTurns >= MAX_NO_PROGRESS_TURNS

    /**
     * Compaction and strict recovery are deliberate cache misses. Ordinary phase
     * transitions are not invalidation reasons because the core prefix is frozen.
     */
    fun cacheInvalidationReason(
        endpointChanged: Boolean = false,
        modelChanged: Boolean = false,
        contextChanged: Boolean = false,
        templateChanged: Boolean = false,
        thinkingChanged: Boolean = false,
        coreSchemaChanged: Boolean = false,
        projectContextChanged: Boolean = false,
        compacted: Boolean = false,
        strictRecovery: Boolean = false
    ): String? = when {
        endpointChanged -> "endpoint_generation"
        modelChanged -> "model_configuration"
        contextChanged -> "context_size"
        templateChanged -> "chat_template"
        thinkingChanged -> "thinking_mode"
        coreSchemaChanged -> "core_schema"
        projectContextChanged -> "project_context"
        compacted -> "compaction"
        strictRecovery -> "strict_recovery"
        else -> null
    }
}
