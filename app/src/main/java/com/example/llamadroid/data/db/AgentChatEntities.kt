package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent conversation - groups messages together
 */
@Entity(tableName = "agent_conversations")
data class AgentConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New Conversation",
    val projectFolder: String = "default_project", // Per-project folder name
    val lastAgentRole: String? = "ORCHESTRATOR",
    val lastTask: String? = null,
    val knowledgeBaseIds: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Agent chat message - stores individual messages
 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [ForeignKey(
        entity = AgentConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("conversationId"),
        Index(value = ["originalId"], unique = true)  // Prevent duplicate messages
    ]
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalId: String, // UI message UUID
    val conversationId: Long,
    val role: String,  // "user", "assistant", "tool", "system"
    val content: String,
    val imagePath: String? = null,
    val thinking: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolArgs: String? = null,  // JSON string of tool arguments
    val toolOutput: String? = null,
    val terminalOutput: String? = null,
    val isTerminalVisible: Boolean = false,
    val needsApproval: Boolean = false,
    val isApproved: Boolean? = null,
    val isPlan: Boolean = false,
    val isPlanApproved: Boolean? = null,
    val planModifiedContent: String? = null,
    val isStreaming: Boolean = false,
    val agentRole: String? = null,  // ORCHESTRATOR, CODER, REVIEWER, EXECUTOR
    val isDelegation: Boolean = false,
    val customAgentName: String? = null,
    val isSuspicious: Boolean = false,
    val pendingToolCall: String? = null, // Serialized ToolCall JSON
    val isOutputExpanded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Int = 0  // Monotonic counter for stable ordering
)
