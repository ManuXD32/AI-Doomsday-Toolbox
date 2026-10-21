package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.NativeChatDelegateEventEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
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
            NativeChatDelegateTaskEntity(
                chatId = request.chatId, title = title, request = brief, runtimeId = runtimeId,
                optionsJson = DelegationTaskOptions(
                    projectId = request.projectId, continueProject = request.continueProject,
                    threadId = request.threadId, pendingAttachmentRefs = request.attachmentRefs,
                    newProject = request.newProject, freshThread = request.freshThread
                ).encode()
            )
        ) ?: return reject("task_active", "A DeepSeek task is already active in this chat. Use deepseek_task_status, deepseek_task_message or deepseek_task_cancel.")
        msgDao.append(
            NativeChatDelegateMessageEntity(taskId = id, seq = 0, role = NativeChatDelegateMessageRole.MAIN_TO_SUB,
                content = DelegateSubagentMessages.bound(brief, DelegateSubagentMessages.MAX_CONTENT_CHARS))
        )
        journal(id, "task_started", count = resolution.inputs.size)
        launch(id, request.chatId, DelegationLoopStart(resolution.inputs))
        return DelegationStartResult.Started(id)
    }

    private fun newInbox(taskId: Long, chatId: Long) = DelegationInbox { kind, text ->
        // The subagent posted a reply/question/result row. A waiter re-queries on the tick; a reply with
        // no waiter becomes a chat wake right away. Questions are woken by the subagent tool (the
        // dispatcher skips them while a waiter holds the chat) and results by TASK_FINISHED.
        DelegationMailbox.tick(taskId)
        if (kind == DelegationMailbox.KIND_REPLY && !DelegationMailbox.hasWaiter(chatId)) {
            scope.launch {
                val lastAsk = runCatching { msgDao.latestMainToSubContent(taskId) }.getOrNull()
                if (lastAsk?.startsWith(DelegationMailbox.USER_PREFIX) == true) {
                    // A reply to a message the user typed in the task viewer: it is shown there and
                    // does not start a main-chat turn.
                    runCatching { msgDao.markSubToMainDelivered(taskId, listOf(DelegationMailbox.KIND_REPLY)) }
                } else {
                    DelegationWakeDispatcher.enqueue(
                        appContext, chatId, taskId, NativeChatDelegationWakeKind.SUBAGENT_REPLY, DelegationResultNotice.reply(text)
                    )
                }
            }
        }
    }

    private fun launch(taskId: Long, chatId: Long, start: DelegationLoopStart?): RunningTask {
        val inbox = newInbox(taskId, chatId)
        val loop = DelegateSubagentLoop(appContext, taskId, chatId, inbox, live, scope, start)
        var self: RunningTask? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                loop.run()
            } finally {
                withContext(NonCancellable) { onLoopEnded(taskId, self) }
            }
        }
        val task = RunningTask(loop, job, inbox)
        self = task
        running[taskId] = task
        job.start()
        return task
    }

    private suspend fun onLoopEnded(taskId: Long, self: RunningTask?) {
        // A replaced loop (reopen after a join timeout) must not touch the new loop's registration or state.
        if (!DelegationRunGuard.endIfCurrent(running, taskId, self)) return
        live.update { it - taskId }
        DelegationMailbox.tick(taskId)
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
        launch(task.id, task.chatId, null)
    }

    // ---- model-facing operations ---------------------------------------------------------------

    override suspend fun statusText(chatId: Long, detail: DelegationStatusDetail): String {
        val task = taskDao.getLatestForChat(chatId) ?: return "No DeepSeek task has been started in this chat."
        // The newest undelivered (else latest) subagent reply/question is shown and counts as delivered.
        val sub = msgDao.getStatusSubMessage(task.id)
        if (sub != null && !sub.delivered) msgDao.markDelivered(sub.id)
        return DelegationStatusFormatter.statusText(task, live.value[task.id], detail, subMessage = sub)
    }

    override suspend fun listProjects(chatId: Long, query: String?): String =
        DelegationProjectCatalog.listText(appContext, chatId, query)

    override suspend fun messageSubagent(
        chatId: Long,
        message: String,
        waitSeconds: Int,
        attachmentRefs: List<String>,
        fromUser: Boolean
    ): DelegationReply {
        val latest = taskDao.getLatestForChat(chatId)
            ?: return DelegationReply(
                "There is no DeepSeek task in this chat yet. Use deepseek_task_start to start one.", false, "NONE", posted = false
            )
        if (message.isBlank()) return DelegationReply("message is required.", false, latest.state, posted = false)
        val refs = attachmentRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val resolution = if (refs.isEmpty()) null else DelegationFileBridge(appContext).resolve(chatId, refs)
        if (resolution != null && resolution.unknown.isNotEmpty()) {
            return DelegationReply(
                "Unknown or unavailable attachment(s): ${resolution.unknown.joinToString(", ")}. Use the ids from the attached-files lines of this chat (for example f12 or img55).",
                false, latest.state, posted = false
            )
        }
        val posted = launchMutex.withLock {
            // Re-read under the lock: the loop may have changed the state meanwhile.
            val task = taskDao.getById(latest.id) ?: return@withLock null
            val reopen = DelegationMailbox.isTerminal(task.state)
            val options = DelegationTaskOptions.decode(task.optionsJson)
            val merged = if (refs.isEmpty()) options
            else options.copy(pendingAttachmentRefs = DelegationMailbox.mergeRefs(options.pendingAttachmentRefs, refs))
            val optionsJson = if (refs.isEmpty() && !reopen) null else merged.encode()
            if (reopen) {
                // A finishing loop may still be winding down; wait for it, and cancel it when it does not end,
                // so the new loop is the only one.
                running[task.id]?.let { old ->
                    val ended = withTimeoutOrNull(REOPEN_JOIN_TIMEOUT_MS) { old.job.join(); true } == true
                    if (!ended) {
                        old.loop.stopModelRequest()
                        withContext(NonCancellable) { old.job.cancelAndJoin() }
                    }
                }
                taskDao.update(DelegationMailbox.reopened(task, optionsJson, System.currentTimeMillis()))
                journal(task.id, "task_reopened")
            } else if (optionsJson != null) {
                taskDao.setOptions(task.id, optionsJson)
            }
            val content = DelegationMailbox.followUpContent(
                DelegateSubagentMessages.bound(message, DelegateSubagentMessages.MAX_CONTENT_CHARS - 800),
                fromUser, resolution?.inputs?.map { it.displayName }.orEmpty(), reopen
            )
            val rowId = msgDao.append(
                NativeChatDelegateMessageEntity(
                    taskId = task.id, seq = 0, role = NativeChatDelegateMessageRole.MAIN_TO_SUB,
                    content = DelegateSubagentMessages.bound(content, DelegateSubagentMessages.MAX_CONTENT_CHARS), delivered = false
                )
            )
            val fresh = taskDao.getById(task.id) ?: task
            val runningTask = running[task.id] ?: run {
                if (fresh.state == NativeChatDelegateTaskState.INTERRUPTED || fresh.state == NativeChatDelegateTaskState.NEEDS_DIRECTION) {
                    taskDao.setStateWithError(fresh.id, NativeChatDelegateTaskState.STARTING_RUNTIME, null, null)
                }
                launch(fresh.id, fresh.chatId, null)
            }
            runningTask.inbox.signal()
            Triple(fresh.id, msgDao.seqOf(rowId), reopen)
        } ?: return DelegationReply("There is no DeepSeek task in this chat.", false, "NONE", posted = false)
        val (taskId, postedSeq, reopened) = posted
        val waitMs = if (fromUser) 0L else waitSeconds.coerceIn(0, DelegationToolArgs.MAX_WAIT_SECONDS) * 1000L
        if (waitMs <= 0L) {
            return DelegationReply(null, true, taskDao.getById(taskId)?.state ?: latest.state, reopened)
        }
        val reply = awaitSubReply(chatId, taskId, postedSeq, waitMs)
        val state = taskDao.getById(taskId)?.state ?: latest.state
        return DelegationReply(reply?.content, reply == null, state, reopened)
    }

    /**
     * DB-backed wait for the first undelivered reply/question/result after [postedSeq]: re-queries on
     * every mailbox tick (and at least every [WAIT_RECHECK_MS]), claims the row atomically, and on
     * timeout hands anything that arrived late to a chat wake.
     */
    private suspend fun awaitSubReply(chatId: Long, taskId: Long, postedSeq: Int, waitMs: Long): NativeChatDelegateMessageEntity? {
        DelegationMailbox.beginWait(chatId)
        var timedOut = false
        try {
            val flow = DelegationMailbox.versionFlow(taskId)
            val deadline = System.currentTimeMillis() + waitMs
            while (true) {
                val seen = flow.value
                val rows = msgDao.getUndeliveredSubToMain(taskId, postedSeq)
                for (row in DelegationMailbox.orderedReplies(rows, postedSeq)) {
                    if (msgDao.markDelivered(row.id) == 1) return row
                }
                val state = taskDao.getById(taskId)?.state ?: return null
                val left = deadline - System.currentTimeMillis()
                if (DelegationMailbox.stalled(state) && !running.containsKey(taskId)) return null
                if (left <= 0L) { timedOut = true; return null }
                withTimeoutOrNull(minOf(left, WAIT_RECHECK_MS)) { flow.first { it != seen } }
            }
        } finally {
            DelegationMailbox.endWait(chatId)
            if (timedOut) wakeLateRows(chatId, taskId, postedSeq)
        }
    }

    /** After a waiter gave up: replies/questions that are still undelivered become chat wakes. */
    private suspend fun wakeLateRows(chatId: Long, taskId: Long, postedSeq: Int) {
        val rows = msgDao.getUndeliveredSubToMain(taskId, postedSeq).filter { it.toolName in DelegationMailbox.WAKE_KINDS }
        wakeRows(chatId, taskId, rows)
    }

    /** Enqueues one wake per kind present in [rows] (newest text); the dispatcher claims the rows. */
    private fun wakeRows(chatId: Long, taskId: Long, rows: List<NativeChatDelegateMessageEntity>) {
        for (kind in DelegationMailbox.WAKE_KINDS) {
            val newest = rows.filter { it.toolName == kind }.maxByOrNull { it.seq } ?: continue
            val wakeKind = DelegationMailbox.wakeKindFor(kind) ?: continue
            val notice = if (kind == DelegationMailbox.KIND_QUESTION) DelegationResultNotice.question(newest.content)
            else DelegationResultNotice.reply(newest.content)
            DelegationWakeDispatcher.enqueue(appContext, chatId, taskId, wakeKind, notice)
        }
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
        DelegationMailbox.tick(task.id)
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
        reconcileMailbox()
        DelegationWakeDispatcher.deliverPending(appContext)
    }

    /**
     * Undelivered subagent replies/questions of non-terminal tasks become wakes; rows of finished
     * tasks are marked delivered (the TASK_FINISHED wake already carries the result).
     */
    private suspend fun reconcileMailbox() {
        for (taskId in msgDao.taskIdsWithUndeliveredSubToMain()) {
            val task = taskDao.getById(taskId) ?: continue
            if (DelegationMailbox.isTerminal(task.state)) {
                msgDao.markSubToMainDelivered(taskId, listOf("reply", "question", "result"))
                continue
            }
            val rows = msgDao.getUndeliveredSubToMain(taskId, 0).filter { it.toolName in DelegationMailbox.WAKE_KINDS }
            wakeRows(task.chatId, taskId, rows)
        }
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
        private const val REOPEN_JOIN_TIMEOUT_MS = 5_000L
        private const val WAIT_RECHECK_MS = 2_000L
        @Volatile private var instance: DelegationManager? = null

        fun get(context: Context): DelegationManager =
            instance ?: synchronized(this) {
                instance ?: DelegationManager(context.applicationContext).also { instance = it }
            }
    }
}
