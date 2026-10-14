package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.NativeChatDelegateEventEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.HarnessDelegatedSessions
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.NativeChatKvCacheStore
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Process singleton that owns every DeepSeek delegation task: starts and resumes subagent loops,
 * answers the main-LLM tools and reconciles tasks after a process restart. Durable state lives in
 * Room; the in-memory parts (loops, inboxes, live status) are rebuilt on demand.
 */
class DelegationManager private constructor(context: Context) : NativeChatDelegationApi {
    private val appContext = context.applicationContext
    private val db get() = AppDatabase.getDatabase(appContext)
    private val taskDao get() = db.nativeChatDelegateTaskDao()
    private val msgDao get() = db.nativeChatDelegateMessageDao()
    private val eventDao get() = db.nativeChatDelegateEventDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = MutableStateFlow<Map<Long, DelegationLiveStatus>>(emptyMap())
    private val running = ConcurrentHashMap<Long, RunningTask>()
    private val launchMutex = Mutex()

    private class RunningTask(val loop: DelegateSubagentLoop, val job: Job, val inbox: DelegationInbox)

    override val liveStatus: StateFlow<Map<Long, DelegationLiveStatus>> = live.asStateFlow()

    override fun observeLatestTask(chatId: Long): Flow<NativeChatDelegateTaskEntity?> = taskDao.observeLatestForChat(chatId)
    override fun observeTask(taskId: Long): Flow<NativeChatDelegateTaskEntity?> = taskDao.observeById(taskId)
    override fun observeTaskMessages(taskId: Long): Flow<List<NativeChatDelegateMessageEntity>> = msgDao.observeForTask(taskId)

    fun isLoopRunning(taskId: Long): Boolean = running.containsKey(taskId)

    // ---- start ------------------------------------------------------------------------------

    override suspend fun startTask(request: DelegationStartRequest): DelegationStartResult {
        fun reject(code: String, message: String) = DelegationStartResult.Rejected(code, message)
        val chat = db.llamaChatDao().getChatById(request.chatId)
            ?: return reject("chat_missing", "This chat no longer exists.")
        val config = NativeChatDelegationConfig.fromParams(parseDelegationParams(chat.apiParams))
        if (!config.enabled) return reject("delegation_disabled", "DeepSeek delegation is turned off for this chat.")
        val runtimeId = config.runtimeId
            ?: return reject("runtime_missing", "No DeepSeek runtime is selected for this chat. Ask the user to choose one in the chat's DeepSeek settings.")
        val resolver = DelegateServerResolver(appContext)
        val (chatServer, choiceRow) = resolver.rowFor(chat, config.subagentServer)
        when (val v = DelegationServerValidation.validate(config.subagentServer, chatServer, choiceRow)) {
            is DelegationServerValidation.Result.Rejected -> return reject(v.code, v.message)
            is DelegationServerValidation.Result.Ok -> Unit
        }
        val resolution = DelegationFileBridge(appContext).resolve(request.chatId, request.attachmentRefs)
        if (resolution.unknown.isNotEmpty()) {
            return reject("attachments_unknown",
                "Unknown or unavailable attachment(s): ${resolution.unknown.joinToString(", ")}. Use the ids from the attached-files lines of this chat (for example f12 or img55).")
        }
        val brief = request.task.trim()
        if (brief.isEmpty()) return reject("task_empty", "task is required.")
        val title = request.title?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
            ?: brief.replace(Regex("\\s+"), " ").take(60)
        val id = taskDao.insertIfNoneActive(
            NativeChatDelegateTaskEntity(chatId = request.chatId, title = title, request = brief, runtimeId = runtimeId)
        ) ?: return reject("task_active", "A DeepSeek task is already active in this chat. Use deepseek_task_status, deepseek_task_message or deepseek_task_cancel.")
        msgDao.append(
            NativeChatDelegateMessageEntity(taskId = id, seq = 0, role = NativeChatDelegateMessageRole.MAIN_TO_SUB,
                content = DelegateSubagentMessages.bound(brief, DelegateSubagentMessages.MAX_CONTENT_CHARS))
        )
        journal(id, "task_started", count = resolution.inputs.size)
        launch(id, request.chatId, DelegationLoopStart(resolution.inputs, request.continueProject), DelegationInbox())
        return DelegationStartResult.Started(id)
    }

    private fun launch(taskId: Long, chatId: Long, start: DelegationLoopStart?, inbox: DelegationInbox): RunningTask {
        val loop = DelegateSubagentLoop(appContext, taskId, chatId, inbox, live, scope, start)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                loop.run()
            } finally {
                withContext(NonCancellable) { onLoopEnded(taskId) }
            }
        }
        val task = RunningTask(loop, job, inbox)
        running[taskId] = task
        job.start()
        return task
    }

    private suspend fun onLoopEnded(taskId: Long) {
        running.remove(taskId)
        live.update { it - taskId }
        // A loop that ends while the row still says "live" died unexpectedly: make it resumable.
        runCatching {
            val task = taskDao.getById(taskId)
            if (task != null && task.state in NativeChatDelegateTaskState.LIVE) {
                taskDao.setStateWithError(taskId, NativeChatDelegateTaskState.INTERRUPTED, "model_error", null)
                journal(taskId, "interrupted", errorClass = "loop_ended")
            }
        }
    }

    /** Returns the running task, starting a loop from the durable rows when none runs. */
    private suspend fun ensureRunning(task: NativeChatDelegateTaskEntity): RunningTask = launchMutex.withLock {
        running[task.id]?.let { return@withLock it }
        if (task.state == NativeChatDelegateTaskState.INTERRUPTED || task.state == NativeChatDelegateTaskState.NEEDS_DIRECTION) {
            taskDao.setStateWithError(task.id, NativeChatDelegateTaskState.STARTING_RUNTIME, null, null)
        }
        launch(task.id, task.chatId, null, DelegationInbox())
    }

    // ---- model-facing operations ---------------------------------------------------------------

    override suspend fun statusText(chatId: Long, detail: DelegationStatusDetail): String {
        val task = taskDao.getLatestForChat(chatId) ?: return "No DeepSeek task has been started in this chat."
        return DelegationStatusFormatter.statusText(task, live.value[task.id], detail)
    }

    override suspend fun messageSubagent(chatId: Long, message: String, waitSeconds: Int): DelegationReply {
        val active = taskDao.getForChatInStates(chatId, NativeChatDelegateTaskState.ACTIVE.toList())
        if (active == null) {
            val latest = taskDao.getLatestForChat(chatId)
                ?: return DelegationReply("There is no DeepSeek task in this chat.", false, "NONE")
            return DelegationReply(
                "The task is not active (${latest.state}). " + DelegationStatusFormatter.statusText(latest, null, DelegationStatusDetail.BRIEF),
                false, latest.state
            )
        }
        msgDao.append(
            NativeChatDelegateMessageEntity(taskId = active.id, seq = 0, role = NativeChatDelegateMessageRole.MAIN_TO_SUB,
                content = DelegateSubagentMessages.bound(message, DelegateSubagentMessages.MAX_CONTENT_CHARS))
        )
        val runningTask = ensureRunning(active)
        val waitMs = waitSeconds.coerceIn(0, DelegationToolArgs.MAX_WAIT_SECONDS) * 1000L
        val reply: DelegationSubReply? = coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                if (waitMs > 0) withTimeoutOrNull(waitMs) { runningTask.inbox.replies.first() } else null
            }
            runningTask.inbox.post(message)
            deferred.await()
        }
        val state = taskDao.getById(active.id)?.state ?: active.state
        return DelegationReply(reply?.text, reply == null, state)
    }

    override suspend fun cancelTask(chatId: Long, reason: String?, stopRuntime: Boolean): Boolean {
        val task = taskDao.getForChatInStates(chatId, NativeChatDelegateTaskState.ACTIVE.toList()) ?: return false
        val runningTask = running[task.id]
        runningTask?.loop?.stopModelRequest()
        val now = System.currentTimeMillis()
        taskDao.getById(task.id)?.let { latest ->
            taskDao.update(
                latest.copy(
                    state = NativeChatDelegateTaskState.CANCELLED, resultStatus = "cancelled",
                    progressNote = reason?.trim()?.take(200) ?: latest.progressNote,
                    finishedAt = now, nextCheckAt = null, checkNote = null, updatedAt = now
                )
            )
        }
        runningTask?.job?.cancel()
        runCatching { runningTask?.job?.join() }
        running.remove(task.id)
        live.update { it - task.id }
        cancelHarnessThreads(task)
        runCatching { DelegationAlarmScheduler.cancel(appContext, task.id) }
        journal(task.id, "cancelled")
        if (stopRuntime) {
            runCatching { HarnessAppRuntime.forRuntime(appContext, task.runtimeId).stop() }
                .onFailure { DebugLog.log("[Delegation] runtime stop failed: ${it.javaClass.simpleName}") }
        }
        return true
    }

    /**
     * Cancels the running turn of EVERY harness thread this task created (never threads the chat did
     * not start) and unregisters their delegated-session suppression. A temporary driver is used so
     * this works whether or not the loop is still alive; a stopped runtime simply has nothing to cancel.
     */
    private suspend fun cancelHarnessThreads(task: NativeChatDelegateTaskEntity) {
        val latest = taskDao.getById(task.id) ?: task
        val sessions = DelegationThreadIds.parse(latest.harnessThreadsJson, latest.harnessSessionId)
        if (sessions.isEmpty()) return
        withContext(NonCancellable) {
            val driver = DelegateHarnessDriver(appContext, task.runtimeId)
            try {
                withTimeoutOrNull(HARNESS_CANCEL_TIMEOUT_MS) {
                    sessions.forEach { session ->
                        runCatching { driver.cancelTurn(session) }
                            .onFailure { DebugLog.log("[Delegation] thread cancel failed: ${it.javaClass.simpleName}") }
                    }
                }
            } finally {
                sessions.forEach { HarnessDelegatedSessions.unregister(task.runtimeId, it) }
                runCatching { driver.close() }
            }
        }
    }

    override suspend fun continueTask(chatId: Long): Boolean {
        val task = taskDao.getForChatInStates(chatId, NativeChatDelegateTaskState.ACTIVE.toList()) ?: return false
        if (running.containsKey(task.id)) return false
        ensureRunning(task)
        return true
    }

    override suspend fun markResultDelivered(chatId: Long) {
        val task = taskDao.getLatestForChat(chatId) ?: return
        if (task.state in NativeChatDelegateTaskState.TERMINAL) journal(task.id, "result_delivered")
    }

    override fun subagentCacheBytes(taskId: Long): Long =
        runCatching { NativeChatKvCacheStore(appContext).sizeBytes(KvCacheKey.Delegate(taskId)) }.getOrDefault(0L)

    override suspend fun clearSubagentCache(taskId: Long) {
        withContext(Dispatchers.IO) { runCatching { NativeChatKvCacheStore(appContext).delete(KvCacheKey.Delegate(taskId)) } }
    }

    // ---- startup ----------------------------------------------------------------------------

    override suspend fun reconcileOnStartup() {
        val stale = taskDao.getInStates(NativeChatDelegateTaskState.LIVE.toList()).filter { !running.containsKey(it.id) }
        for (task in stale) {
            taskDao.setStateWithError(task.id, NativeChatDelegateTaskState.INTERRUPTED, null, null)
            journal(task.id, "interrupted", errorClass = "process_restart")
            runCatching {
                val chat = db.llamaChatDao().getChatById(task.chatId)
                UnifiedNotificationManager.showDeepSeekTaskNotice(
                    task.chatId, chat?.serverId ?: 0L, chat?.title ?: task.title,
                    appContext.getString(R.string.native_chat_delegation_service_task_interrupted)
                )
            }
        }
        runCatching { DelegationAlarmScheduler.rescheduleAll(appContext) }
        DelegationWakeDispatcher.deliverPending(appContext)
    }

    private suspend fun journal(taskId: Long, type: String, count: Int? = null, errorClass: String? = null) {
        try {
            eventDao.insert(NativeChatDelegateEventEntity(taskId = taskId, type = type, count = count, errorClass = errorClass))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val HARNESS_CANCEL_TIMEOUT_MS = 10_000L
        @Volatile private var instance: DelegationManager? = null

        fun get(context: Context): DelegationManager =
            instance ?: synchronized(this) {
                instance ?: DelegationManager(context.applicationContext).also { instance = it }
            }
    }
}
