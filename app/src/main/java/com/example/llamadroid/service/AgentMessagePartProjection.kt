package com.example.llamadroid.service

import com.example.llamadroid.data.db.AgentMessagePartEntity
import org.json.JSONObject

/**
 * Compatibility projection for histories that are still written through
 * agent_messages. This deliberately never parses rendered Compose text to
 * reconstruct a tool call; it uses the structured fields on ChatMessage.
 */
fun projectAgentMessageParts(
    conversationId: Long,
    message: AgentService.Companion.ChatMessage
): List<AgentMessagePartEntity> {
    val parts = mutableListOf<AgentMessagePartEntity>()
    var position = 0

    fun add(
        type: AgentMessagePartType,
        status: AgentPartStatus = if (message.isStreaming) AgentPartStatus.RUNNING else AgentPartStatus.COMPLETED,
        preview: String? = null,
        canonicalJson: String? = null,
        contentRef: String? = null,
        toolName: String? = null,
        toolCallId: String? = null,
        safeTarget: String? = null
    ) {
        val partPosition = position++
        parts += AgentMessagePartEntity(
            id = "${message.id}:$partPosition:${type.name.lowercase()}",
            conversationId = conversationId,
            messageOriginalId = message.id,
            position = partPosition,
            type = type.name,
            status = status.name,
            textPreview = preview?.take(2_000),
            canonicalJson = canonicalJson,
            contentRef = contentRef,
            toolName = toolName,
            toolCallId = toolCallId,
            safeTarget = safeTarget,
            invocationId = message.invocationId,
            metadataJson = stableJson(
                mapOf(
                    "role" to message.role,
                    "agent_role" to message.agentRole,
                    "custom_agent" to message.customAgentName,
                    "sequence" to message.sequenceNumber
                )
            ),
            createdAt = message.timestamp,
            updatedAt = System.currentTimeMillis()
        )
    }

    message.thinking?.takeIf { it.isNotBlank() }?.let {
        add(AgentMessagePartType.REASONING, preview = it)
    }

    val primaryType = when {
        message.needsApproval -> AgentMessagePartType.APPROVAL
        message.isPlan -> AgentMessagePartType.PLAN
        message.isDelegation -> AgentMessagePartType.DELEGATION
        message.isTerminalVisible || message.terminalOutput != null -> AgentMessagePartType.TERMINAL
        message.role == "tool" || message.pendingToolCall != null || message.toolName != null -> AgentMessagePartType.TOOL
        else -> AgentMessagePartType.TEXT
    }

    val toolCall = message.pendingToolCall
    val canonical = when {
        toolCall != null -> JSONObject().apply {
            put("role", "assistant")
            put(
                "tool_calls",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("id", toolCall.id)
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", toolCall.name)
                                // OpenAI-compatible requests expect this to be a
                                // JSON string. Keep the endpoint-produced bytes.
                                put(
                                    "arguments",
                                    toolCall.rawArgumentsJson
                                        ?: JSONObject(toolCall.arguments).toString()
                                )
                            }
                        )
                    }
                )
            )
        }.toString()
        message.role == "tool" -> JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", message.toolCallId)
            put("content", message.content)
        }.toString()
        else -> null
    }

    add(
        type = primaryType,
        status = when {
            message.needsApproval && message.isApproved == null -> AgentPartStatus.PENDING
            message.isStreaming -> AgentPartStatus.RUNNING
            else -> AgentPartStatus.COMPLETED
        },
        preview = message.content.ifBlank {
            message.toolName?.let { "Tool: $it" }
        },
        canonicalJson = canonical,
        toolName = message.toolName ?: toolCall?.name,
        toolCallId = message.toolCallId ?: toolCall?.id,
        safeTarget = safeToolTarget(message.toolArgs ?: toolCall?.arguments)
    )
    return parts
}

private fun safeToolTarget(arguments: Map<String, String>?): String? {
    val args = arguments ?: return null
    val key = listOf(
        "path",
        "working_directory",
        "command_id",
        "agent",
        "filename",
        "skill",
        "name",
        "action"
    ).firstOrNull(args::containsKey) ?: return null
    return "$key=${args[key].orEmpty().replace('\n', ' ').take(180)}"
}
