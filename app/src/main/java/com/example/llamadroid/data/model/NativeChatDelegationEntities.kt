package com.example.llamadroid.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Values of [LlamaMessageEntity.kind]; null is an ordinary chat message. */
object LlamaMessageKind {
    /** App-written user-side notice that wakes the main LLM (task finished, scheduled check…). */
    const val DELEGATION_NOTICE = "delegation_notice"
}

/** Lifecycle of a DeepSeek delegation task. Stored as text in [NativeChatDelegateTaskEntity.state]. */
object NativeChatDelegateTaskState {
    const val QUEUED = "QUEUED"
    const val STARTING_RUNTIME = "STARTING_RUNTIME"
    const val RUNNING = "RUNNING"
    const val WAITING_HARNESS = "WAITING_HARNESS"
    const val WAITING_MAIN = "WAITING_MAIN"
    const val NEEDS_DIRECTION = "NEEDS_DIRECTION"
    const val INTERRUPTED = "INTERRUPTED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    /** States that block starting another task. */
    val ACTIVE: Set<String> = setOf(
        QUEUED, STARTING_RUNTIME, RUNNING, WAITING_HARNESS, WAITING_MAIN, NEEDS_DIRECTION, INTERRUPTED
    )

    /** States in which the subagent loop is (or should be) executing. */
    val LIVE: Set<String> = setOf(QUEUED, STARTING_RUNTIME, RUNNING, WAITING_HARNESS, WAITING_MAIN)

    val TERMINAL: Set<String> = setOf(COMPLETED, FAILED, CANCELLED)
}

/** Roles of [NativeChatDelegateMessageEntity.role]. */
object NativeChatDelegateMessageRole {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    /** A message from the main LLM to the subagent (brief, question, follow-up). */
    const val MAIN_TO_SUB = "main_to_sub"
    /** A message from the subagent to the main LLM (progress, reply, question, result). */
    const val SUB_TO_MAIN = "sub_to_main"
    /** App-written marker, e.g. a compaction line; never sent to a model. */
    const val NOTICE = "notice"
}

/** Origins of [LlamaMessageAttachmentEntity.source]. */
object LlamaMessageAttachmentSource {
    const val USER = "USER"
    const val DEEPSEEK = "DEEPSEEK"
}

/** Kinds of wake recorded in [NativeChatDelegateTaskEntity.pendingWakeKind]. */
object NativeChatDelegationWakeKind {
    const val TASK_FINISHED = "TASK_FINISHED"
    const val CHECK_ALARM = "CHECK_ALARM"
    const val SUBAGENT_QUESTION = "SUBAGENT_QUESTION"
    /** A subagent reply the main LLM did not receive inside its `deepseek_task_message` wait. */
    const val SUBAGENT_REPLY = "SUBAGENT_REPLY"
    const val NEEDS_DIRECTION = "NEEDS_DIRECTION"
}

/**
 * One DeepSeek task delegated by a native chat. At most one row per chat is in an
 * [NativeChatDelegateTaskState.ACTIVE] state; the DAO enforces that in a transaction.
 */
@Entity(
    tableName = "native_chat_delegate_tasks",
    foreignKeys = [
        ForeignKey(
            entity = LlamaChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId"), Index(value = ["chatId", "state"]), Index("state")]
)
data class NativeChatDelegateTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val state: String = NativeChatDelegateTaskState.QUEUED,
    val title: String,
    /** The self-contained brief written by the main LLM. */
    val request: String,
    val runtimeId: String,
    /** Android-side harness workspace id; null until the project exists. */
    val workspaceId: String? = null,
    /** The thread the subagent is currently driving. */
    val harnessSessionId: String? = null,
    /** JSON array of `{id, title}` for every thread created by this task. */
    val harnessThreadsJson: String = "[]",
    /** Agent conversation row created by `importSession`, used for the "open live" deep link. */
    val harnessConversationId: Long? = null,
    /** JSON snapshot of the resolved harness agent model (provider, model, context, max output). */
    val harnessModelJson: String? = null,
    /** JSON snapshot of the resolved subagent server (row id, base URL, context, max output). */
    val subagentServerJson: String? = null,
    val resultStatus: String? = null,
    val resultSummary: String? = null,
    /** JSON array of `{path, name, attachmentId}` for result files copied into the chat. */
    val resultFilesJson: String? = null,
    /** Latest bounded harness digest (JSON); never raw harness output. */
    val digestJson: String? = null,
    /** Latest one-line progress note reported by the subagent. */
    val progressNote: String? = null,
    val nextCheckAt: Long? = null,
    val checkNote: String? = null,
    val checkCadenceMinutes: Int? = null,
    @ColumnInfo(defaultValue = "0") val alarmCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val wakeCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val subagentModelCalls: Int = 0,
    @ColumnInfo(defaultValue = "0") val harnessPrompts: Int = 0,
    @ColumnInfo(defaultValue = "0") val compactionCount: Int = 0,
    val pendingWakeKind: String? = null,
    val pendingWakeAt: Long? = null,
    val lastErrorCode: String? = null,
    @ColumnInfo(defaultValue = "0") val ctxUsedTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val ctxWindowTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** `DelegationTaskOptions` JSON: project/thread choice and attachments still to copy (survives Continue). */
    val optionsJson: String? = null,
    /** How many times a finished task was reopened by a follow-up message (0 = first round). */
    @ColumnInfo(defaultValue = "0") val followUpCount: Int = 0
)

/**
 * The subagent's durable conversation: its continuation state after a process death and the
 * source of the delegation viewer. Content is bounded (16k chars, thinking 4k) before insert.
 */
@Entity(
    tableName = "native_chat_delegate_messages",
    foreignKeys = [
        ForeignKey(
            entity = NativeChatDelegateTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index(value = ["taskId", "seq"], unique = true)]
)
data class NativeChatDelegateMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val seq: Int,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
    /** JSON array of OpenAI-style tool calls made by an assistant message. */
    val toolCallsJson: String? = null,
    val thinking: String? = null,
    @ColumnInfo(defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val completionTokens: Int = 0,
    /** Replaced by a later summary; kept for the viewer, never sent to the model again. */
    @ColumnInfo(defaultValue = "0") val compactedOut: Boolean = false,
    /** A `<compacted-summary>` message produced by compaction. */
    @ColumnInfo(defaultValue = "0") val isSummary: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Mailbox flag. A [NativeChatDelegateMessageRole.MAIN_TO_SUB] row is false until it is injected
     * into the subagent conversation; a [NativeChatDelegateMessageRole.SUB_TO_MAIN] reply/question is
     * false until the main LLM received it (inside its wait or through a chat wake). Other rows: true.
     */
    @ColumnInfo(defaultValue = "1") val delivered: Boolean = true
)

/**
 * Content-free diagnostics journal for a task (AGENTS.md: metadata only — never prompts, message
 * text, tool output, file contents or private tool arguments).
 */
@Entity(
    tableName = "native_chat_delegate_events",
    foreignKeys = [
        ForeignKey(
            entity = NativeChatDelegateTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class NativeChatDelegateEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val at: Long = System.currentTimeMillis(),
    val type: String,
    val phase: String? = null,
    val toolName: String? = null,
    val status: String? = null,
    val count: Int? = null,
    val durationMs: Long? = null,
    val errorClass: String? = null
)

/**
 * A file attached to a native chat message: sent by the user or delivered by a DeepSeek task.
 * [messageId] is null while the file is pending (picked but not yet sent, or a result waiting
 * for the main LLM's next reply). [path] is absolute inside `files/llama_chat_files/<chatId>/`.
 */
@Entity(
    tableName = "llama_message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = LlamaMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LlamaChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId"), Index("chatId"), Index("taskId")]
)
data class LlamaMessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long? = null,
    val chatId: Long,
    val path: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val source: String = LlamaMessageAttachmentSource.USER,
    val taskId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** Id the main LLM uses to reference an attachment in tool arguments. */
val LlamaMessageAttachmentEntity.modelReference: String get() = "f$id"

/**
 * One main-chat compaction. Messages with id <= [boundaryMessageId] are replaced by [summary]
 * when history is sent to the model; they stay visible in the chat.
 */
@Entity(
    tableName = "native_chat_compactions",
    foreignKeys = [
        ForeignKey(
            entity = LlamaChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class NativeChatCompactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val boundaryMessageId: Long,
    val summary: String,
    val tokensBefore: Int,
    val tokensAfter: Int,
    /** False while [tokensAfter] is an estimate; true once a request measured it. */
    @ColumnInfo(defaultValue = "0") val measured: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

val NativeChatCompactionEntity.tokensSaved: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)
