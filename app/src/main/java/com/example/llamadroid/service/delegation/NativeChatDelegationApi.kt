package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the main LLM asked for when it called `deepseek_task_start`. */
data class DelegationStartRequest(
    val chatId: Long,
    val task: String,
    val title: String?,
    /** Chat file references such as `f12` (attachment row) or `img55` (image of message 55). */
    val attachmentRefs: List<String> = emptyList(),
    /** Reuse the chat's previous project instead of creating a new one. */
    val continueProject: Boolean = false
)

sealed class DelegationStartResult {
    data class Started(val taskId: Long) : DelegationStartResult()
    /** [code] is a stable token (e.g. `task_active`, `runtime_missing`); [message] is model-facing text. */
    data class Rejected(val code: String, val message: String) : DelegationStartResult()
}

/** The subagent's answer to `deepseek_task_message`. */
data class DelegationReply(
    val text: String?,
    val timedOut: Boolean,
    val state: String
)

/** In-memory live view of a running task for the UI; durable state lives in Room. */
data class DelegationLiveStatus(
    val taskId: Long,
    val chatId: Long,
    val state: String,
    /** Short machine phase, e.g. `starting_runtime`, `model_call`, `harness_wait`, `compacting`. */
    val phase: String,
    val subagentContextUsed: Int = 0,
    val subagentContextWindow: Int = 0,
    /** True while the harness agent is generating (prompt processing or decoding). */
    val harnessBusy: Boolean = false,
    /** Pending harness approvals/questions the subagent has not answered yet. */
    val pendingAttention: Int = 0,
    val progressNote: String? = null,
    /** Subagent model call: prompt tokens processed so far / total (0 when no call is processing a prompt). */
    val subagentPromptProcessed: Int = 0,
    val subagentPromptTotal: Int = 0,
    /** True once the subagent model call streams tokens. */
    val subagentGenerating: Boolean = false,
    /** Harness agent prompt processing for this task's session (0 when idle or unknown). */
    val harnessPromptProcessed: Int = 0,
    val harnessPromptTotal: Int = 0,
    /** Every harness thread the subagent created, with live context and prompt progress. */
    val threads: List<DelegationThreadLive> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Live view of one harness thread (session) created by the subagent. */
data class DelegationThreadLive(
    val sessionId: String,
    val title: String,
    val running: Boolean,
    val promptProcessed: Int = 0,
    val promptTotal: Int = 0,
    val contextUsed: Int = 0,
    val contextWindow: Int = 0,
    /** Newest session event sequence seen, or -1 when unknown (used as `throughSeq` for message previews). */
    val lastSeq: Long = -1L
)

/** Detail level of [NativeChatDelegationApi.statusText]. */
enum class DelegationStatusDetail { BRIEF, FULL }

/**
 * Facade over the DeepSeek delegation subsystem. Used by the main-LLM tools, the wake
 * dispatcher, the chat screen and the delegation viewer. Implemented by `DelegationManager`.
 */
interface NativeChatDelegationApi {
    fun observeLatestTask(chatId: Long): Flow<NativeChatDelegateTaskEntity?>
    fun observeTask(taskId: Long): Flow<NativeChatDelegateTaskEntity?>
    fun observeTaskMessages(taskId: Long): Flow<List<NativeChatDelegateMessageEntity>>
    /** Live status of every task currently held in memory, keyed by task id. */
    val liveStatus: StateFlow<Map<Long, DelegationLiveStatus>>

    suspend fun startTask(request: DelegationStartRequest): DelegationStartResult

    /** Model-facing status text, bounded (brief ≤ 1,500 chars, full ≤ 2,500), with the re-arm footer. */
    suspend fun statusText(chatId: Long, detail: DelegationStatusDetail): String

    /** Sends [message] to the subagent and waits up to [waitSeconds] for its reply; resumes stopped tasks. */
    suspend fun messageSubagent(chatId: Long, message: String, waitSeconds: Int): DelegationReply

    suspend fun cancelTask(chatId: Long, reason: String?, stopRuntime: Boolean): Boolean

    /** User-initiated Continue for an INTERRUPTED or NEEDS_DIRECTION task. */
    suspend fun continueTask(chatId: Long): Boolean

    /** Marks the result files of the chat's finished task as delivered to the main LLM's next reply. */
    suspend fun markResultDelivered(chatId: Long)

    fun subagentCacheBytes(taskId: Long): Long
    suspend fun clearSubagentCache(taskId: Long)

    /** Re-reads durable state after process start: live tasks become INTERRUPTED, alarms rescheduled. */
    suspend fun reconcileOnStartup()
}

/** Process-wide accessor. `DelegationManager` (a process singleton) implements the facade. */
object NativeChatDelegation {
    fun get(context: Context): NativeChatDelegationApi = DelegationManager.get(context.applicationContext)
}
