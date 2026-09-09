package com.example.llamadroid.service

private const val PROJECT_CONTROL_PACKET_PREFIX = "# Project Control Packet"
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

    val movable = messages.mapIndexedNotNull { index, message ->
        if (!isStandaloneSystemMessage(message)) {
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
    if (movable.isEmpty()) return messages

    val movableIndices = movable.mapTo(hashSetOf()) { it.index }
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
    }
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
