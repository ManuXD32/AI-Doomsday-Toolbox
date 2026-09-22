package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent conversation - groups messages together
 */
@Entity(
    tableName = "agent_conversations",
    indices = [Index("prootEnvironmentId")]
)
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
    val workspaceBackend: String = "LOCAL_SANDBOX",
    /** Stable reference to the app-managed Debian/PRoot environment, when selected. */
    val prootEnvironmentId: String? = null,
    val runtimeCapabilitiesJson: String = "",
    val runEntrypointPath: String? = null,
    val runUiMode: String = "CONSOLE",
    val lastRunProfileJson: String = "",
    /** Null follows the active run; otherwise this project uses the saved HTTP(S) preview URL. */
    val previewUrlOverride: String? = null,
    /** Legacy content stays readable; new session history is owned by DeepSeek Harness. */
    @ColumnInfo(defaultValue = "'LEGACY_ARCHIVE'")
    val runtimeSource: String = AgentRuntimeSource.LEGACY_ARCHIVE,
    /** Historical execution policy retained for old records; Harness owns new session policy. */
    val executionProfile: String = AgentExecutionProfile.DIRECT,
    /** Direct runtime schema/version that has been durably re-anchored for this conversation. */
    val directRuntimeVersion: Int = 0,
    /** One-time migration/re-anchor marker; it is intentionally persisted with the conversation. */
    val directReanchorState: String = AgentDirectReanchorState.PENDING,
    val directReanchorReason: String? = null,
    val directReanchoredAt: Long? = null,
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
        Index("prootEnvironmentId"),
        Index("status"),
        Index("updatedAt")
    ]
)
data class AgentProjectRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val projectFolder: String,
    val backend: String = "LOCAL_SANDBOX",
    /** Optional app-managed Debian/PRoot environment associated with this run. */
    val prootEnvironmentId: String? = null,
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

/**
 * App-managed Debian environments. The rootfs itself is derived from [id] under
 * app-private storage; no arbitrary filesystem path is persisted here.
 *
 * Values are strings deliberately: the database must remain forward-compatible
 * with a newer packaged image or lifecycle state without crashing old clients.
 */
@Entity(
    tableName = "agent_proot_environments",
    indices = [
        Index(value = ["storageKey"], unique = true),
        Index("status"),
        Index("sharingMode"),
        Index("updatedAt")
    ]
)
data class AgentProotEnvironmentEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String,
    /** Safe storage token; the runner derives the actual rootfs directory from this row. */
    val storageKey: String = id,
    val imageId: String = "debian-trixie-arm64-20260824",
    val imageVersion: String = "13.6 (Trixie)",
    val imageDigest: String = "4fca9c419bf46e2a639a635edd5f6c16da619a49ebb3b0d152a7378ea7cffeea",
    val sharingMode: String = AgentProotEnvironmentSharingMode.ISOLATED,
    val status: String = AgentProotEnvironmentStatus.NOT_INSTALLED,
    val sizeBytes: Long = 0L,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Environment sharing semantics shown by the Agent environment manager. */
object AgentProotEnvironmentSharingMode {
    const val ISOLATED = "ISOLATED"
    const val SHARED = "SHARED"
}

/** Durable bootstrap/runtime lifecycle values for a Debian environment. */
object AgentProotEnvironmentStatus {
    const val NOT_INSTALLED = "NOT_INSTALLED"
    const val INSTALLING = "INSTALLING"
    const val READY = "READY"
    const val UPDATING = "UPDATING"
    const val BROKEN = "BROKEN"
    const val DELETING = "DELETING"
}

/**
 * Metadata for one PRoot command/preview lease. Command text is intentionally
 * not duplicated here; the associated tool receipt remains the source of truth.
 */
@Entity(
    tableName = "agent_proot_runs",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentProotEnvironmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["environmentId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("conversationId"),
        Index("environmentId"),
        Index("status"),
        Index("updatedAt")
    ]
)
data class AgentProotRunEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: Long,
    val environmentId: String,
    val projectFolder: String,
    val commandDigest: String = "",
    val status: String = AgentProotRunStatus.QUEUED,
    val processGeneration: String? = null,
    val processId: Int? = null,
    val previewUrl: String? = null,
    val outputReference: String? = null,
    val outputChars: Int = 0,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val stopRequestedAt: Long? = null,
    val forceStopRequestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object AgentProotRunStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val STOP_REQUESTED = "STOP_REQUESTED"
    const val STOPPED = "STOPPED"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val INTERRUPTED = "INTERRUPTED"
}

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
