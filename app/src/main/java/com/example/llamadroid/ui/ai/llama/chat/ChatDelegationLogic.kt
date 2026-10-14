package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.delegation.DelegationServerChoice
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Pure helpers of the DeepSeek runtime settings section (kept free of Compose for unit tests). */

/** Defaults offered when the user picks a remote (or LiteRT) server for a delegation participant. */
internal const val DELEGATION_DEFAULT_REMOTE_CONTEXT = 32_768
internal const val DELEGATION_DEFAULT_REMOTE_MAX_OUTPUT = 4_096

/** Parses the chat `apiParams` JSON into a plain map; malformed or empty JSON gives an empty map. */
internal fun parseParamMap(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        Gson().fromJson<Map<String, Any?>>(json, type) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/** Parses a user-typed token count; null while empty, non-numeric or outside [min]..[max]. */
internal fun validateDelegationNumber(text: String, min: Int, max: Int): Int? =
    text.trim().toIntOrNull()?.takeIf { it in min..max }

internal fun validateDelegationContext(text: String): Int? = validateDelegationNumber(
    text, NativeChatDelegationConfig.MIN_CONTEXT_TOKENS, NativeChatDelegationConfig.MAX_CONTEXT_TOKENS
)

internal fun validateDelegationMaxOutput(text: String): Int? = validateDelegationNumber(
    text, NativeChatDelegationConfig.MIN_OUTPUT_TOKENS, NativeChatDelegationConfig.MAX_OUTPUT_TOKENS
)

/** Whether a server of [engine] may host the subagent (OpenAI-compatible tool calls) or the harness agent. */
internal fun isDelegationServerEligible(engine: String, forSubagent: Boolean): Boolean {
    val normalized = com.example.llamadroid.data.model.normalizeLlamaServerEngine(engine)
    val openAi = normalized == LlamaServerEntity.ENGINE_LLAMA_SERVER || normalized == LlamaServerEntity.ENGINE_LLAMA_SWAP
    return if (forSubagent) openAi else true
}

/** Remote and LiteRT servers need the user's context/output limits; phone-local cards carry their own. */
internal fun delegationNeedsLimitFields(engine: String, isRemote: Boolean): Boolean =
    isRemote || com.example.llamadroid.data.model.normalizeLlamaServerEngine(engine) == LlamaServerEntity.ENGINE_LITERT_LM

internal enum class DelegationEnableBlock { NONE, NO_RUNTIME, LITERT_SAME_AS_CHAT }

/**
 * Why the DeepSeek switch cannot be turned on, if at all. A LiteRT main chat cannot host the
 * subagent, so it needs another subagent server.
 */
internal fun delegationEnableBlock(
    hasReadyRuntime: Boolean,
    mainIsLiteRt: Boolean,
    subagent: DelegationServerChoice
): DelegationEnableBlock = when {
    !hasReadyRuntime -> DelegationEnableBlock.NO_RUNTIME
    mainIsLiteRt && subagent is DelegationServerChoice.SameAsChat -> DelegationEnableBlock.LITERT_SAME_AS_CHAT
    else -> DelegationEnableBlock.NONE
}

internal enum class DelegationParticipant { MAIN_CHAT, SUBAGENT, HARNESS_AGENT }

/**
 * Groups the participants by the server row they use. [mainRowId] is the chat's bound server;
 * "Same as chat" follows it. Participants whose row is unknown are skipped.
 */
internal fun delegationParticipantsByServer(
    mainRowId: Long?,
    config: NativeChatDelegationConfig
): Map<Long, Set<DelegationParticipant>> {
    val result = linkedMapOf<Long, MutableSet<DelegationParticipant>>()
    fun add(row: Long?, who: DelegationParticipant) {
        if (row != null && row > 0) result.getOrPut(row) { linkedSetOf() }.add(who)
    }
    add(mainRowId, DelegationParticipant.MAIN_CHAT)
    add(rowOf(config.subagentServer, mainRowId), DelegationParticipant.SUBAGENT)
    add(rowOf(config.harnessServer, mainRowId), DelegationParticipant.HARNESS_AGENT)
    return result
}

private fun rowOf(choice: DelegationServerChoice, mainRowId: Long?): Long? = when (choice) {
    is DelegationServerChoice.SameAsChat -> mainRowId
    is DelegationServerChoice.Server -> choice.rowId
}

/**
 * True when more participants share a phone-local card than it has slots, so requests would take
 * turns and the KV caches are swapped through disk. [slots] null means unknown (no advice).
 */
internal fun delegationNeedsMoreSlots(participants: Int, slots: Int?, parallelRequests: Boolean): Boolean =
    parallelRequests && slots != null && participants > 1 && slots < participants

/** Slots to recommend for [participants] sharing one server (capped at the three-way recommendation). */
internal fun delegationRecommendedSlots(participants: Int): Int =
    participants.coerceIn(1, NativeChatDelegationConfig.RECOMMENDED_PARALLEL_SLOTS)

/** The harness agent's prompts need about this much context per slot. */
internal const val DELEGATION_HARNESS_MIN_SLOT_CONTEXT = 16_384

/** Context available to one request: the full context with unified KV, else the context split across slots. */
internal fun delegationContextPerSlot(totalContext: Int, slots: Int?, kvUnified: Boolean): Int =
    if (kvUnified) totalContext.coerceAtLeast(0) else totalContext.coerceAtLeast(0) / (slots ?: 1).coerceAtLeast(1)

/**
 * Per-slot context of a phone-local server used by the harness agent when it is below
 * [DELEGATION_HARNESS_MIN_SLOT_CONTEXT] (advice needed), else null. Unknown values give no advice.
 */
internal fun delegationHarnessSlotShortfall(totalContext: Int?, slots: Int?, kvUnified: Boolean): Int? {
    if (totalContext == null || totalContext <= 0 || slots == null) return null
    val perSlot = delegationContextPerSlot(totalContext, slots, kvUnified)
    return perSlot.takeIf { it < DELEGATION_HARNESS_MIN_SLOT_CONTEXT }
}
