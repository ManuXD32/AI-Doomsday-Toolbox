package com.example.llamadroid.service

private const val PROJECT_CONTROL_PACKET_PREFIX = "# Project Control Packet"
private const val DIRECT_CONTROL_CAPSULE_PREFIX =
    "# Project Control Packet — Direct Control Capsule"
private const val LEGACY_DIRECT_CONTROL_CAPSULE_PREFIX = "CONTROL_CAPSULE v="
private const val CONTEXT_DIGEST_PREFIX = "CONTEXT DIGEST:"
private const val CURRENT_MODE_PREFIX = "CURRENT MODE:"
private const val RECOVERY_MODE_PREFIX = "RECOVERY MODE:"
private const val RECOVERY_TOOL_HELP_PREFIX = "RECOVERY TOOL HELP:"

/**
 * Reorders only standalone generated control messages at the final prompt
 * stage. The stable system prefix and every user/assistant/tool message keep
 * their original order and object identity. A digest is emitted before the
 * authoritative Project Control Packet, and both are placed before the
 * trailing mode/recovery instructions when those instructions exist.
 *
 * [isStandaloneSystemMessage] is supplied by the caller so messages linked
 * to a tool/delegation exchange can remain atomic with that exchange.
 */
internal fun <T> reorderOptimizedPromptMessages(
    messages: List<T>,
    roleOf: (T) -> String,
    contentOf: (T) -> String,
    isStandaloneSystemMessage: (T) -> Boolean
): List<T> {
    if (messages.size < 2) return messages

    // A Direct conversation is append-only between compactions. Historical
    // capsules must therefore remain exactly where they were sent on their
    // original request; moving all of them to the end destroys llama.cpp's
    // reusable prefix. Only the newest capsule is authoritative and belongs at
    // the absolute request tail.
    val newestDirectCapsuleIndex = messages.indices.lastOrNull { index ->
        val message = messages[index]
        isStandaloneSystemMessage(message) && isDirectControlCapsule(
            role = roleOf(message),
            content = contentOf(message)
        )
    }
    val movable = messages.mapIndexedNotNull { index, message ->
        if (!isStandaloneSystemMessage(message)) {
            null
        } else if (isDirectControlCapsule(roleOf(message), contentOf(message))) {
            null
        } else {
            generatedControlMessageRank(
                role = roleOf(message),
                content = contentOf(message)
            )?.let { rank ->
                PromptMessageMove(index = index, rank = rank)
            }
        }
    }
    if (movable.isEmpty() && newestDirectCapsuleIndex == null) return messages

    val movableIndices = movable.mapTo(hashSetOf()) { it.index }.apply {
        newestDirectCapsuleIndex?.let(::add)
    }
    val remaining = messages.filterIndexed { index, _ -> index !in movableIndices }
    val movedMessages = movable
        .sortedWith(compareBy<PromptMessageMove> { it.rank }.thenBy { it.index })
        .map { messages[it.index] }
    val tailAnchor = remaining.indexOfFirst { message ->
        isCurrentModeOrRecovery(
            role = roleOf(message),
            content = contentOf(message)
        )
    }
    val insertionIndex = if (tailAnchor >= 0) tailAnchor else remaining.size

    return buildList(messages.size) {
        addAll(remaining.take(insertionIndex))
        addAll(movedMessages)
        addAll(remaining.drop(insertionIndex))
        newestDirectCapsuleIndex?.let { add(messages[it]) }
    }
}

/** True only for request-tail Direct capsules, not legacy control packets. */
internal fun isDirectControlCapsule(role: String, content: String): Boolean {
    if (!role.equals("system", ignoreCase = true)) return false
    val normalized = content.trimStart()
    return normalized.startsWith(DIRECT_CONTROL_CAPSULE_PREFIX, ignoreCase = true) ||
        normalized.startsWith(LEGACY_DIRECT_CONTROL_CAPSULE_PREFIX, ignoreCase = true)
}

/**
 * Builds a deterministic, persist-before-dispatch request tail. The latest
 * non-control message id makes retries idempotent while allowing a new capsule
 * after every committed assistant/tool boundary, including process recovery.
 */
internal fun buildDirectRequestControlTail(
    conversationId: Long,
    boundaryMessageId: String,
    recoveryInstruction: String?,
    checkpoint: String,
    capsule: String
): List<AgentService.Companion.ChatMessage> = buildList {
    fun message(kind: String, content: String): AgentService.Companion.ChatMessage {
        val digest = agentPromptSha256(
            "$conversationId|$boundaryMessageId|$kind|$content"
        ).take(24)
        return AgentService.Companion.ChatMessage(
            id = "direct-request-tail:$conversationId:$kind:$digest",
            role = "system",
            content = content
        )
    }
    recoveryInstruction?.trim()?.takeIf { it.isNotBlank() }?.let {
        add(message("recovery", "RECOVERY MODE: $it"))
    }
    add(message("checkpoint", checkpoint))
    add(message("capsule", capsule))
}

/**
 * Direct adapter for the ChatMessage type used by AgentService. The helper
 * deliberately checks linkage metadata before moving a generated marker.
 */
fun reorderOptimizedAgentPromptMessages(
    messages: List<AgentService.Companion.ChatMessage>
): List<AgentService.Companion.ChatMessage> =
    reorderOptimizedPromptMessages(
        messages = messages,
        roleOf = { it.role },
        contentOf = { it.content },
        isStandaloneSystemMessage = { message ->
            message.role.equals("system", ignoreCase = true) &&
                !message.isStreaming &&
                !message.isDelegation &&
                !message.isPlan &&
                message.pendingToolCall == null &&
                message.toolCallId == null &&
                message.toolName == null &&
                message.invocationId == null
        }
    )

private data class PromptMessageMove(
    val index: Int,
    val rank: Int
)

private fun generatedControlMessageRank(role: String, content: String): Int? {
    if (!role.equals("system", ignoreCase = true)) return null
    val normalized = content.trimStart()
    return when {
        normalized.startsWith(CONTEXT_DIGEST_PREFIX, ignoreCase = true) -> 0
        normalized.startsWith(PROJECT_CONTROL_PACKET_PREFIX, ignoreCase = true) -> 1
        else -> null
    }
}

private fun isCurrentModeOrRecovery(role: String, content: String): Boolean {
    if (!role.equals("system", ignoreCase = true)) return false
    val normalized = content.trimStart()
    return normalized.startsWith(CURRENT_MODE_PREFIX, ignoreCase = true) ||
        normalized.startsWith(RECOVERY_MODE_PREFIX, ignoreCase = true) ||
        normalized.startsWith(RECOVERY_TOOL_HELP_PREFIX, ignoreCase = true)
}
