package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable, ordered Agent message parts.
 *
 * Legacy agent_messages remain the compatibility/display projection. New
 * workflow state is written here so tool state can be updated in place without
 * rebuilding inference history from Compose models.
 */
@Entity(
    tableName = "agent_message_parts",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("messageOriginalId"),
        Index(value = ["messageOriginalId", "position"], unique = true),
        Index("type"),
        Index("toolCallId"),
        Index("invocationId")
    ]
)
data class AgentMessagePartEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val messageOriginalId: String,
    val position: Int,
    val type: String,
    val status: String = "COMPLETED",
    val textPreview: String? = null,
    val canonicalJson: String? = null,
    val contentRef: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val safeTarget: String? = null,
    val durationMs: Long? = null,
    val metadataJson: String = "{}",
    val invocationId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Frozen model-facing root turn configuration. It stores hashes and stable
 * configuration metadata, never the prompt text itself.
 */
@Entity(
    tableName = "agent_turn_contexts",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("createdAt"),
        Index("invocationId")
    ]
)
data class AgentTurnContextEntity(
    @PrimaryKey val rootTurnId: String,
    val conversationId: Long,
    val agentKey: String,
    val status: String = "ACTIVE",
    val backend: String,
    val modelLabel: String,
    val endpointGeneration: String,
    val contextTokens: Int,
    val configuredOutputTokens: Int,
    val effectiveOutputTokens: Int,
    val systemPromptHash: String,
    val toolDefinitionsHash: String,
    val stablePrefixHash: String,
    val parametersHash: String,
    val messageCount: Int,
    val messagesHash: String,
    val previousPrefixCompatible: Boolean? = null,
    val cacheMissReason: String? = null,
    val skillIdsJson: String = "[]",
    val slotId: Int? = null,
    val cacheMode: String = "AUTOMATIC",
    val messageStartSequence: Int,
    val invocationId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(
    tableName = "agent_skills",
    indices = [
        Index("name"),
        Index("sourceType"),
        Index("enabled")
    ]
)
data class AgentSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String? = null,
    val license: String? = null,
    val sourceType: String,
    val sourceUri: String? = null,
    val installPath: String,
    val manifestJson: String = "{}",
    val contentHash: String,
    val enabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_skill_assignments",
    foreignKeys = [
        ForeignKey(
            entity = AgentSkillEntity::class,
            parentColumns = ["id"],
            childColumns = ["skillId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("skillId"),
        Index("conversationId"),
        Index(value = ["skillId", "conversationId", "agentKey"], unique = true)
    ]
)
data class AgentSkillAssignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String,
    val conversationId: Long? = null,
    val agentKey: String = "*",
    val permission: String = "ASK",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_pending_questions",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("rootTurnId")
    ]
)
data class AgentPendingQuestionEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val rootTurnId: String,
    val agentSessionId: String,
    val toolCallId: String,
    val specificationJson: String,
    val answerJson: String? = null,
    /** Draft UI state is durable so a partially answered wizard survives recreation. */
    val draftAnswerJson: String = "{}",
    val currentPage: Int = 0,
    val isCollapsed: Boolean = false,
    val status: String = "PENDING",
    val continuationEnqueued: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null
)

/**
 * Durable plan-approval protocol state.
 *
 * The assistant's proposal remains an unresolved tool call until this row is
 * approved or superseded. Keeping the checkpoints here makes approval
 * idempotent across double taps and process death instead of inferring it from
 * a localized chat projection.
 */
@Entity(
    tableName = "agent_pending_plans",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("state"),
        Index("rootTurnId"),
        Index(value = ["planMessageId"], unique = true)
    ]
)
data class AgentPendingPlanEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val rootTurnId: String,
    val agentSessionId: String,
    val planMessageId: String,
    val toolCallId: String,
    val originalPlan: String,
    val editedPlan: String? = null,
    val summary: String,
    val state: String = "AWAITING_APPROVAL",
    val approvalOperationId: String? = null,
    val approvedAt: Long? = null,
    val planFileWritten: Boolean = false,
    val buildModeActivated: Boolean = false,
    val continuationEnqueued: Boolean = false,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_todos",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("planVersionId"),
        Index("assignedInvocationId"),
        Index(value = ["conversationId", "position"])
    ]
)
data class AgentTodoEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val text: String,
    val status: String = "PENDING",
    val priority: String = "NORMAL",
    val position: Int,
    val source: String = "AGENT",
    val planVersionId: String? = null,
    val planStepId: String? = null,
    val phaseId: String? = null,
    val ownerRole: String? = null,
    val assignedInvocationId: String? = null,
    val dependenciesJson: String = "[]",
    val acceptanceCriteriaJson: String = "[]",
    val evidenceJson: String = "[]",
    val attemptCount: Int = 0,
    val blockReason: String? = null,
    val resultSummary: String? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_compactions",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("createdAt"),
        Index("rootTurnId"),
        Index("invocationId")
    ]
)
data class AgentCompactionEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val rootTurnId: String?,
    val summaryText: String,
    val focus: String? = null,
    val previousCompactionId: String? = null,
    val sourceStartSequence: Int,
    val sourceEndSequence: Int,
    val tailStartSequence: Int?,
    val summarizedMessageCount: Int,
    val retainedTailTokens: Int,
    val targetTailTokens: Int,
    val modelLabel: String,
    val invocationId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One durable invocation for each successful call_agent handoff.
 *
 * The requested name is model supplied. resolvedName is allocated transactionally
 * so repeated small-model names remain distinct without rejecting the delegation.
 */
@Entity(
    tableName = "agent_project_states",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("mode"),
        Index("updatedAt"),
        Index("activePlanVersionId"),
        Index("currentTodoId")
    ]
)
data class AgentProjectStateEntity(
    @PrimaryKey val conversationId: Long,
    val revision: Long = 0L,
    val mode: String = "PLAN",
    val currentGoal: String = "",
    val activePlanVersionId: String? = null,
    val currentPhaseId: String? = null,
    val currentTodoId: String? = null,
    val semanticEventCount: Long = 0L,
    val lastSemanticEvent: String? = null,
    val lastCompactedRevision: Long? = null,
    val lastCompactionSemanticEventCount: Long = 0L,
    val lastCompactionKey: String? = null,
    val lastCompactionStatus: String? = null,
    val lastCompactionPreTokens: Int? = null,
    val lastCompactionPostTokens: Int? = null,
    val lastCompactionSavedTokens: Int? = null,
    val lastCompactionSaturationReason: String? = null,
    val lastCompactionAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_plan_versions",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("approvedAt"),
        Index(value = ["conversationId", "versionNumber"], unique = true),
        Index(value = ["conversationId", "planHash"], unique = true)
    ]
)
data class AgentPlanVersionEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val sourcePendingPlanId: String? = null,
    val versionNumber: Int,
    val summary: String,
    val planMarkdown: String,
    val structuredJson: String,
    val planHash: String,
    val status: String = "APPROVED",
    val createdAt: Long = System.currentTimeMillis(),
    val approvedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_work_reports",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["invocationId"], unique = true),
        Index("todoId"),
        Index("agentRole"),
        Index("status"),
        Index("createdAt")
    ]
)
data class AgentWorkReportEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val invocationId: String,
    val todoId: String? = null,
    val agentRole: String,
    val status: String,
    val summary: String,
    val structuredJson: String,
    val evidenceJson: String = "{}",
    val changedFilesJson: String = "[]",
    val risksJson: String = "[]",
    val recommendationsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One durable invocation for each successful call_agent handoff.
 *
 * todoId binds Build work to the authoritative TODO state machine.
 * workReportId points at the complete structured specialist result.
 */
@Entity(
    tableName = "agent_invocations",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("status"),
        Index("startedAt"),
        Index("parentToolCallId"),
        Index("todoId"),
        Index("workReportId"),
        Index(value = ["conversationId", "resolvedNameKey"], unique = true)
    ]
)
data class AgentInvocationEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val rootTurnId: String,
    val runtimeEpoch: Long,
    val parentToolCallId: String,
    val agentClass: String,
    val agentKey: String,
    val requestedName: String,
    val baseNameKey: String,
    val occurrence: Int,
    val resolvedName: String,
    val resolvedNameKey: String,
    val sessionId: String? = null,
    val task: String,
    val context: String? = null,
    val todoId: String? = null,
    val workReportId: String? = null,
    val status: String = "RUNNING",
    val resultSummary: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val backend: String? = null,
    val modelLabel: String? = null,
    val serverPhase: String? = null,
    val contextSize: Int? = null,
    val rawEstimatedTokens: Int? = null,
    val packedEstimatedTokens: Int? = null,
    val actualPromptTokens: Int? = null,
    val actualCompletionTokens: Int? = null,
    val contextPercent: Int? = null,
    val compactionCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "agent_pending_inputs",
    foreignKeys = [
        ForeignKey(
            entity = AgentConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("targetInvocationId"),
        Index("status"),
        Index(value = ["conversationId", "sequenceNumber"], unique = true)
    ]
)
data class AgentPendingInputEntity(
    @PrimaryKey val id: String,
    val conversationId: Long,
    val targetInvocationId: String? = null,
    val batchId: String? = null,
    val kind: String = "USER_MESSAGE",
    val content: String = "",
    val imagePath: String? = null,
    val status: String = "QUEUED",
    val sequenceNumber: Long,
    val boundaryToolCallId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null,
    val cancelledAt: Long? = null
)
