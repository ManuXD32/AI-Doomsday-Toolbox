package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide helpers of the durable delegation mailbox. The rows in Room are the truth
 * (`native_chat_delegate_messages.delivered`); this object only holds the low-latency signals
 * (a version counter per task, a waiter count per chat) and the pure decision helpers.
 *
 * SUB_TO_MAIN rows keep their kind (`progress`, `reply`, `question`, `result`) in `toolName`.
 * Progress rows are inserted delivered; reply/question/result rows are undelivered until the main
 * LLM received them (inside a `messageSubagent` wait or through a chat wake).
 */
object DelegationMailbox {
    const val KIND_PROGRESS = "progress"
    const val KIND_REPLY = "reply"
    const val KIND_QUESTION = "question"
    const val KIND_RESULT = "result"

    /** Kinds a `messageSubagent` waiter may take. */
    val WAITER_KINDS: Set<String> = setOf(KIND_REPLY, KIND_QUESTION, KIND_RESULT)

    /** Kinds that become a chat wake when nobody waits for them (results use TASK_FINISHED instead). */
    val WAKE_KINDS: List<String> = listOf(KIND_REPLY, KIND_QUESTION)

    const val USER_PREFIX = "[User] "
    const val MAX_FOLLOW_UP_CHARS = 15_000

    private val waiters = ConcurrentHashMap<Long, AtomicInteger>()
    private val versions = ConcurrentHashMap<Long, MutableStateFlow<Long>>()

    // ---- signals ------------------------------------------------------------------------------

    fun beginWait(chatId: Long) { waiters.getOrPut(chatId) { AtomicInteger() }.incrementAndGet() }

    fun endWait(chatId: Long) { waiters[chatId]?.decrementAndGet() }

    fun hasWaiter(chatId: Long): Boolean = (waiters[chatId]?.get() ?: 0) > 0

    /** Changes whenever a task's mailbox or state may have changed; waiters re-query on change. */
    fun versionFlow(taskId: Long): MutableStateFlow<Long> = versions.getOrPut(taskId) { MutableStateFlow(0L) }

    fun tick(taskId: Long) { versionFlow(taskId).value += 1 }

    // ---- pure helpers -------------------------------------------------------------------------

    /** Undelivered reply/question/result rows after [afterSeq], in seq order. */
    fun orderedReplies(rows: List<NativeChatDelegateMessageEntity>, afterSeq: Int): List<NativeChatDelegateMessageEntity> =
        rows.filter {
            it.role == NativeChatDelegateMessageRole.SUB_TO_MAIN && !it.delivered &&
                it.seq > afterSeq && it.toolName in WAITER_KINDS
        }.sortedBy { it.seq }

    /** The first row a waiter would take, or null. */
    fun pickReply(rows: List<NativeChatDelegateMessageEntity>, afterSeq: Int): NativeChatDelegateMessageEntity? =
        orderedReplies(rows, afterSeq).firstOrNull()

    /** Wake kind for a sub-to-main row kind, or null when it never wakes (progress, result). */
    fun wakeKindFor(kind: String?): String? = when (kind) {
        KIND_REPLY -> NativeChatDelegationWakeKind.SUBAGENT_REPLY
        KIND_QUESTION -> NativeChatDelegationWakeKind.SUBAGENT_QUESTION
        else -> null
    }

    /** Wake dedupe: only when no waiter holds the chat AND this call claimed at least one undelivered row. */
    fun shouldEnqueueWake(waiterActive: Boolean, claimedRows: Int): Boolean = !waiterActive && claimedRows > 0

    fun isTerminal(state: String): Boolean = state in NativeChatDelegateTaskState.TERMINAL

    /** Whether a waiter should stop waiting because the subagent cannot answer now. */
    fun stalled(state: String): Boolean = state == NativeChatDelegateTaskState.INTERRUPTED ||
        state == NativeChatDelegateTaskState.NEEDS_DIRECTION || state in NativeChatDelegateTaskState.TERMINAL

    /**
     * The task row after a follow-up reopened it: same conversation, project and thread, fresh
     * per-round budgets and a fresh result. Compaction and context counters are kept.
     */
    fun reopened(task: NativeChatDelegateTaskEntity, optionsJson: String?, now: Long): NativeChatDelegateTaskEntity =
        task.copy(
            state = NativeChatDelegateTaskState.STARTING_RUNTIME,
            followUpCount = task.followUpCount + 1,
            finishedAt = null,
            resultStatus = null, resultSummary = null, resultFilesJson = null,
            subagentModelCalls = 0, harnessPrompts = 0, alarmCount = 0, wakeCount = 0,
            nextCheckAt = null, checkNote = null,
            pendingWakeKind = null, pendingWakeAt = null,
            lastErrorCode = null, progressNote = null,
            optionsJson = optionsJson, updatedAt = now
        )

    /** Appends [refs] to the pending ones without duplicates. */
    fun mergeRefs(existing: List<String>, refs: List<String>): List<String> = (existing + refs).distinct()

    /** The text stored in the MAIN_TO_SUB row (and later injected as the USER row). */
    fun followUpContent(message: String, fromUser: Boolean, newInputNames: List<String>, reopened: Boolean): String = buildString {
        append(if (fromUser) USER_PREFIX else DelegateSubagentPrompt.INBOX_PREFIX)
        append(message.trim().take(MAX_FOLLOW_UP_CHARS))
        if (newInputNames.isNotEmpty()) append("\nNew input files: ").append(newInputNames.joinToString(", "))
        if (reopened) {
            append("\n(System note: this is a follow-up on the finished task. Continue in the same project and thread ")
            append("(harness_send), act on the request, and call finish_task again when done.)")
        }
    }
}
