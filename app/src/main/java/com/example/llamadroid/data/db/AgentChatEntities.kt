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
    val projectFolderId: Long? = null,
    val sortOrder: Int = 0,
    val planningModeEnabled: Boolean = true,
    val resumeState: String = "IDLE",
    val lastStopReason: String? = null,
    val lastAgentRole: String? = "ORCHESTRATOR",
    val lastTask: String? = null,
    val knowledgeBaseIds: String = "",
    val workspaceBackend: String = "REMOTE_SSH",
    val runtimeCapabilitiesJson: String = "",
    val runEntrypointPath: String? = null,
    val runUiMode: String = "CONSOLE",
    val lastRunProfileJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Organizational folders for agent projects.
 *
 * These folders only affect the project dashboard and picker. They never move
 * the physical REMOTE_SSH or LOCAL_SANDBOX workspace roots.
 */
@Entity(
    tableName = "agent_project_folders",
    indices = [
        Index("parentId"),
        Index("sortOrder")
    ]
)
data class AgentProjectFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val name: String,
    val sortOrder: Int = 0,
    val isCollapsed: Boolean = false,
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
        Index("invocationId"),
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
    val sequenceNumber: Int = 0,  // Monotonic counter for stable ordering
    val invocationId: String? = null
)

@Entity(
    tableName = "agent_project_runs",
    foreignKeys = [ForeignKey(
        entity = AgentConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("updatedAt")
    ]
)
data class AgentProjectRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val projectFolder: String,
    val backend: String = "LOCAL_SANDBOX",
    val runtime: String = "",
    val entrypoint: String = "",
    val uiMode: String = "CONSOLE",
    val status: String = "STOPPED",
    val logs: String = "",
    val previewUrl: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val stopRequestedAt: Long? = null,
    val forceStopRequestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_project_events",
    foreignKeys = [ForeignKey(
        entity = AgentConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("conversationId"),
        Index("timestamp"),
        Index("sequenceNumber"),
        Index("category"),
        Index("eventType"),
        Index("toolCallId"),
        Index("status"),
        Index("invocationId")
    ]
)
data class AgentProjectEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val projectFolder: String = "default_project",
    val timestamp: Long = System.currentTimeMillis(),
    val sequenceNumber: Int = 0,
    val category: String = "UI",
    val eventType: String = "event",
    val phase: String? = null,
    val agentRole: String? = null,
    val customAgentName: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val status: String? = null,
    val durationMs: Long? = null,
    val contentChars: Int? = null,
    val contentLines: Int? = null,
    val toolOutputChars: Int? = null,
    val toolOutputLines: Int? = null,
    val contextPercent: Int? = null,
    val activeJobCount: Int? = null,
    val foregroundState: String? = null,
    val protectionState: String? = null,
    val connectionState: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val summary: String = "",
    val invocationId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
