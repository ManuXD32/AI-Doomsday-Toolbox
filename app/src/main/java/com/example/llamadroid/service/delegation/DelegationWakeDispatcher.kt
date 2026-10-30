package com.example.llamadroid.service.delegation

import androidx.core.content.edit
import android.content.Context
import android.content.Intent
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind
import com.example.llamadroid.data.model.NativeChatDelegateEventEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.isForegroundServiceStartNotAllowed
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The ONLY way delegation wakes the main LLM. Wakes are persisted on the task row
 * (`pendingWakeKind`) plus their notice text in preferences, then delivered by one iterative
 * consumer: insert a `delegation_notice` user row and start a normal generation with no new user
 * text (the chat screen's retry path). Never recursive; exactly-once through `claimPendingWake`.
 */
object DelegationWakeDispatcher {
    private const val PREFS = "delegation_wake_notices"
    private const val BUSY_RECHECK_MS = 500L
    private const val GENERATION_START_WAIT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lastDelivered = HashMap<Long, Long>()
    @Volatile private var started = false
    /** True after a foreground-service start denial; cleared when the app is foreground again or an alarm retries. */
    @Volatile private var startDenied = false
    private lateinit var appContext: Context

    /** Persists the wake and signals the consumer. */
    fun enqueue(context: Context, chatId: Long, taskId: Long, kind: String, noticeText: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(app).nativeChatDelegateTaskDao()
                val task = dao.getById(taskId)?.takeIf { it.chatId == chatId } ?: return@launch
                // Mailbox wakes (subagent reply/question): skipped while a messageSubagent waiter holds the
                // chat (it takes the row itself and, on timeout, re-enqueues), and only when this call
                // claims the undelivered rows, so a reply already handed to a waiter or woken once never
                // wakes again. The claim marks the rows delivered.
                if (kind == NativeChatDelegationWakeKind.SUBAGENT_REPLY || kind == NativeChatDelegationWakeKind.SUBAGENT_QUESTION) {
                    if (DelegationMailbox.hasWaiter(chatId)) return@launch
                    val claimed = AppDatabase.getDatabase(app).nativeChatDelegateMessageDao()
                        .markSubToMainDelivered(taskId, DelegationMailbox.WAKE_KINDS)
                    if (!DelegationMailbox.shouldEnqueueWake(false, claimed)) return@launch
                }
                val merged = DelegationWakePolicy.mergeKind(task.pendingWakeKind, kind)
                synchronized(this@DelegationWakeDispatcher) {
                    val previous = if (task.pendingWakeKind != null) storedNotices(app, taskId) else emptyList()
                    storeNotices(app, taskId, previous + noticeText.ifBlank { DelegationNotices.fallbackFor(kind) })
                }
                dao.setPendingWake(taskId, merged, System.currentTimeMillis())
                ensureStarted(app)
                signal.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.log("[DelegationWake] enqueue failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Rescans persisted pending wakes; called on app start, from alarms, and when a chat opens.
     * Every pending wake is rescanned, so [chatId] only documents the caller's intent.
     */
    fun deliverPending(context: Context, @Suppress("UNUSED_PARAMETER") chatId: Long? = null) {
        ensureStarted(context.applicationContext)
        startDenied = false
        signal.trySend(Unit)
    }

    @Synchronized
    private fun ensureStarted(context: Context) {
        if (started) return
        appContext = context.applicationContext
        started = true
        scope.launch {
            // Process-start rescan, then a single consumer loop.
            signal.trySend(Unit)
            for (ignored in signal) {
                try {
                    drain()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log("[DelegationWake] drain failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Iterative: deliver what is ready, wait for what is not, until nothing is pending. */
    private suspend fun drain() {
        while (true) {
            val waitMs = deliverReady() ?: return
            delay(waitMs.coerceAtLeast(250L))
        }
    }

    /** Returns null when nothing is pending, otherwise how long to wait before trying again. */
    private suspend fun deliverReady(): Long? {
        val db = AppDatabase.getDatabase(appContext)
        val taskDao = db.nativeChatDelegateTaskDao()
        var pending = taskDao.getWithPendingWakes()
        if (pending.isEmpty()) return null

        // Queue-depth guard: keep the oldest MAX_QUEUE_DEPTH, hand the rest back to the user.
        if (DelegationWakePolicy.queueTooDeep(pending.size)) {
            pending.drop(DelegationWakePolicy.MAX_QUEUE_DEPTH).forEach { escalate(appContext, it) }
            pending = taskDao.getWithPendingWakes()
        }
        // Per-task wake cap.
        pending.filter { DelegationWakePolicy.wakeCapReached(it.wakeCount) }.forEach { escalate(appContext, it) }
        pending = taskDao.getWithPendingWakes()
        if (pending.isEmpty()) return null
        if (startDenied) return null // wait for a foreground trigger (chat opened / retry alarm)

        val now = System.currentTimeMillis()
        val byChat = pending.groupBy { it.chatId }
        var shortestWait: Long? = null
        for ((chatId, tasks) in byChat) {
            // Only this chat's own turn blocks its wake; other chats generating or queued do not.
            // LlamaClientService decides whether the new turn runs now or waits in its queue.
            if (NativeChatGenerationStates.isBusy(chatId)) {
                shortestWait = minOf(shortestWait ?: BUSY_RECHECK_MS, BUSY_RECHECK_MS)
                continue
            }
            val wait = synchronized(this) { DelegationWakePolicy.waitMs(lastDelivered[chatId], now) }
            if (wait > 0L) {
                shortestWait = minOf(shortestWait ?: wait, wait)
                continue
            }
            deliverChat(chatId, tasks)
            return 500L // re-evaluate generation state before the next chat
        }
        return shortestWait
    }

    private suspend fun deliverChat(chatId: Long, tasks: List<NativeChatDelegateTaskEntity>) {
        val db = AppDatabase.getDatabase(appContext)
        val taskDao = db.nativeChatDelegateTaskDao()
        val claimed = tasks.filter { taskDao.claimPendingWake(it.id) == 1 }
        if (claimed.isEmpty()) return
        val notices = claimed.flatMap { task ->
            val stored = synchronized(this) { storedNotices(appContext, task.id).also { clearNotices(appContext, task.id) } }
            stored.ifEmpty { listOf(DelegationNotices.fallbackFor(task.pendingWakeKind ?: NativeChatDelegationWakeKind.CHECK_ALARM)) }
        }
        val kinds = claimed.mapNotNull { it.pendingWakeKind }
        val repo = LlamaRepository(db.llamaServerDao(), db.llamaChatDao(), db.llamaChatFolderDao(), db.llamaMessageDao())
        val chat = repo.getChat(chatId)
        if (chat == null) return
        val serverId = chat.serverId ?: db.llamaServerDao().getLastUsedServer()?.id
        val text = DelegationWakePolicy.coalesce(notices)
        val messageId = repo.addMessage(chatId, role = "user", content = text, kind = LlamaMessageKind.DELEGATION_NOTICE)
        val notifyKind = kinds.firstOrNull { DelegationWakePolicy.notifiesUser(it) }
        val route = serverId ?: -1L
        if (serverId == null) {
            restore(repo, taskDao, claimed, messageId, chatId, notices)
            postTapToContinue(chat.title, chatId, route)
            return
        }
        NativeChatGenerationStates.clearIfIdle(chatId)
        val launched = try {
            val intent = Intent(appContext, LlamaClientService::class.java).apply {
                action = LlamaClientService.ACTION_GENERATE
                putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                putExtra(LlamaClientService.EXTRA_SERVER_ID, serverId)
            }
            appContext.startForegroundService(intent)
            true
        } catch (e: Exception) {
            if (!isForegroundServiceStartNotAllowed(e)) {
                DebugLog.log("[DelegationWake] start failed: ${e.javaClass.simpleName}")
            }
            false
        }
        if (launched) {
            synchronized(this) { lastDelivered[chatId] = System.currentTimeMillis() }
            if (notifyKind != null) postNotice(chat.title, notifyKind, chatId, route)
            // The service flips to Generating asynchronously; wait for it so the next wake (possibly
            // for another chat) is not dispatched while this one is still starting and gets dropped.
            withTimeoutOrNull(GENERATION_START_WAIT_MS) {
                NativeChatGenerationStates.flowFor(chatId).first { NativeChatGenerationStates.isBusy(chatId) }
            }
        } else {
            // Undo and keep the wake pending: it is delivered when the chat opens or an exact alarm retries.
            restore(repo, taskDao, claimed, messageId, chatId, notices)
            startDenied = true
            if (!DelegationAlarmScheduler.scheduleDeliveryRetry(appContext)) postTapToContinue(chat.title, chatId, route)
        }
    }

    private suspend fun restore(
        repo: LlamaRepository,
        taskDao: com.example.llamadroid.data.dao.NativeChatDelegateTaskDao,
        claimed: List<NativeChatDelegateTaskEntity>,
        messageId: Long,
        chatId: Long,
        notices: List<String>
    ) {
        runCatching { repo.deleteMessage(LlamaMessageEntity(id = messageId, chatId = chatId, role = "user", content = "")) }
        val now = System.currentTimeMillis()
        claimed.forEachIndexed { index, task ->
            val kind = task.pendingWakeKind ?: NativeChatDelegationWakeKind.CHECK_ALARM
            if (index == 0) synchronized(this) { storeNotices(appContext, task.id, notices) }
            taskDao.setPendingWake(task.id, kind, now)
        }
    }

    /** Wake cap or queue overflow: stop waking the model and ask the user to direct the task. */
    private suspend fun escalate(context: Context, task: NativeChatDelegateTaskEntity) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.nativeChatDelegateTaskDao()
        dao.setPendingWake(task.id, null, null)
        synchronized(this) { clearNotices(context, task.id) }
        if (task.state in NativeChatDelegateTaskState.ACTIVE) dao.setState(task.id, NativeChatDelegateTaskState.NEEDS_DIRECTION)
        runCatching {
            db.nativeChatDelegateEventDao().insert(
                NativeChatDelegateEventEntity(
                    taskId = task.id,
                    type = "runaway_continuation_suspected",
                    phase = "wake_dispatch",
                    count = task.wakeCount
                )
            )
        }
    }

    private fun postNotice(chatTitle: String, kind: String, chatId: Long, serverId: Long) {
        val body = appContext.getString(
            when (kind) {
                NativeChatDelegationWakeKind.TASK_FINISHED -> R.string.native_chat_delegation_service_notice_finished
                NativeChatDelegationWakeKind.NEEDS_DIRECTION -> R.string.native_chat_delegation_service_notice_direction
                else -> R.string.native_chat_delegation_service_notice_question
            }
        )
        UnifiedNotificationManager.showDeepSeekTaskNotice(chatId, serverId, chatTitle, body)
    }

    private fun postTapToContinue(chatTitle: String, chatId: Long, serverId: Long) {
        UnifiedNotificationManager.showDeepSeekTaskNotice(
            chatId, serverId, chatTitle, appContext.getString(R.string.native_chat_delegation_service_tap_to_continue)
        )
    }

    // ---- notice text persistence (survives process death) ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storedNotices(context: Context, taskId: Long): List<String> {
        val raw = prefs(context).getString("t$taskId", null) ?: return emptyList()
        return runCatching {
            val array = JSONObject(raw).getJSONArray("n")
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun storeNotices(context: Context, taskId: Long, notices: List<String>) {
        val bounded = DelegationWakePolicy.coalesce(notices)
        prefs(context).edit {
            putString(
                "t$taskId",
                JSONObject().put("n", org.json.JSONArray(if (bounded.isEmpty()) emptyList() else listOf(bounded))).toString()
            )
        }
    }

    private fun clearNotices(context: Context, taskId: Long) {
        prefs(context).edit { remove("t$taskId") }
    }
}
