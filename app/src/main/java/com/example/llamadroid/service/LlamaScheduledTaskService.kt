package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogStatus
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

class LlamaScheduledTaskService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ArrayDeque<RunRequest>()
    private val queueLock = Any()
    private var processor: Job? = null
    private var activeRun: ActiveRun? = null
    private var activeRunJob: Job? = null
    private var notificationTaskId: Int? = null
    private var ownsLocalLlamaServer = false

    private lateinit var database: AppDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var ollamaService: OllamaService
    private lateinit var llamaServerChatService: LlamaServerChatService
    private lateinit var nativeChatToolRuntime: NativeChatToolRuntime
    private lateinit var runner: LlamaScheduledTaskRunner

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        ollamaService = OllamaService(applicationContext)
        llamaServerChatService = LlamaServerChatService()
        nativeChatToolRuntime = NativeChatToolRuntime(
            noteDao = database.noteDao(),
            organizerDao = database.organizerDao(),
            alarmScheduler = { alarm -> OrganizerAlarmScheduler.scheduleAlarm(applicationContext, alarm) },
            alarmCanceler = { alarmId -> OrganizerAlarmScheduler.cancelAlarm(applicationContext, alarmId) },
            organizerChanged = { OrganizerCalendarWidgetProvider.refreshAll(applicationContext) },
            notesChanged = { NoteDisplayWidgetProvider.refreshAll(applicationContext) },
            imageGenerator = NativeChatOnnxImageGenerator(applicationContext, database),
            backgroundRemover = NativeChatOnnxBackgroundRemover(applicationContext, database),
            knowledgeBaseRepository = com.example.llamadroid.data.repository.KnowledgeBaseRepository(applicationContext, database),
            pdfTextExtractor = { pdfBytes, maxChars ->
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                extractNativePdfTextFromBytes(pdfBytes, maxChars)
            }
        )
        runner = LlamaScheduledTaskRunner(
            context = applicationContext,
            settingsRepo = settingsRepo,
            ollamaService = ollamaService,
            llamaServerChatService = llamaServerChatService,
            nativeChatToolRuntime = nativeChatToolRuntime
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_RUN_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (taskId > 0L) {
                    synchronized(queueLock) {
                        queue.addLast(
                            RunRequest(
                                taskId = taskId,
                                scheduledAtMillis = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis()),
                                logId = intent.getLongExtra(EXTRA_LOG_ID, -1L).takeIf { it > 0L },
                                force = intent.getBooleanExtra(EXTRA_FORCE, false)
                            )
                        )
                    }
                    startProcessorIfNeeded()
                }
            }
            ACTION_CANCEL_RUNNING_TASK -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0L }
                val logId = intent.getLongExtra(EXTRA_LOG_ID, -1L).takeIf { it > 0L }
                cancelActiveRun(taskId = taskId, logId = logId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        processor?.cancel()
        releaseLocks()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SCHEDULED_TASK,
            getString(R.string.llama_scheduler_notification_running_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        WakeLockManager.acquire(applicationContext, "LlamaScheduledTaskService")
        WakeLockManager.acquireWifiLock(applicationContext, "LlamaScheduledTaskService")
    }

    private fun startProcessorIfNeeded() {
        if (processor?.isActive == true) return
        processor = serviceScope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        try {
            while (true) {
                val request = synchronized(queueLock) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                supervisorScope {
                    val runJob = launch { processRequest(request) }
                    synchronized(queueLock) {
                        activeRunJob = runJob
                    }
                    runJob.join()
                    synchronized(queueLock) {
                        if (activeRunJob === runJob) {
                            activeRunJob = null
                        }
                        activeRun = null
                    }
                }
            }
        } finally {
            releaseOwnedLocalServerIfIdle()
            releaseLocks()
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.llama_scheduler_notification_idle))
                notificationTaskId = null
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private suspend fun processRequest(request: RunRequest) = withContext(Dispatchers.IO) {
        val taskDao = database.llamaScheduledTaskDao()
        val serverDao = database.llamaServerDao()
        val task = taskDao.getTaskById(request.taskId)
        if (task == null) {
            request.logId?.let { logId ->
                taskDao.markLogFailure(
                    id = logId,
                    status = LlamaScheduledTaskLogStatus.FAILED,
                    finishedAtMillis = System.currentTimeMillis(),
                    durationMs = null,
                    error = getString(R.string.llama_scheduler_error_task_missing),
                    toolActivity = ""
                )
            }
            return@withContext
        }

        val logId = request.logId?.also { existingLogId ->
            taskDao.getLogById(existingLogId)?.let { existing ->
                taskDao.updateLog(existing.copy(status = LlamaScheduledTaskLogStatus.QUEUED))
            }
        } ?: taskDao.insertLog(
            LlamaScheduledTaskLogEntity(
                taskId = task.id,
                taskName = task.name,
                scheduledAtMillis = request.scheduledAtMillis,
                status = LlamaScheduledTaskLogStatus.QUEUED
            )
        )

        if (!task.enabled && !request.force) {
            taskDao.markLogSkipped(logId, LlamaScheduledTaskLogStatus.SKIPPED, System.currentTimeMillis())
            return@withContext
        }

        val server = task.serverId?.let { serverDao.getServerById(it) }
            ?: serverDao.getLastUsedServer()
        if (server == null) {
            failLog(taskDao = taskDao, logId = logId, start = null, error = getString(R.string.llama_scheduler_error_no_server))
            if (!request.force) {
                LlamaScheduledTaskScheduler.advanceTaskAfterScheduledTime(applicationContext, task, request.scheduledAtMillis)
            }
            return@withContext
        }

        val start = System.currentTimeMillis()
        taskDao.markLogRunning(
            id = logId,
            status = LlamaScheduledTaskLogStatus.RUNNING,
            startedAtMillis = start,
            serverId = server.id,
            serverName = server.name,
            serverBaseUrl = server.baseUrl()
        )
        synchronized(queueLock) {
            activeRun = ActiveRun(taskId = task.id, logId = logId, startedAtMillis = start)
        }
        updateProgress(getString(R.string.llama_scheduler_status_preparing, task.name))

        try {
            val result = RemoteAgentProtection.withExistingForeground("LlamaScheduledTaskService") {
                ensureLocalLlamaServerIfNeeded(taskName = task.name, server = server)
                runner.runTask(task, server) { status ->
                    updateProgress(status)
                }
            }
            val fallbackNoteId = maybeSaveScheduledTaskOutputNote(task, result)
            val toolActivity = buildString {
                append(result.toolActivity)
                fallbackNoteId?.let { noteId ->
                    if (isNotEmpty()) append('\n')
                    append("scheduler_note_fallback note_id=")
                    append(noteId)
                }
            }
            val finish = System.currentTimeMillis()
            taskDao.markLogSuccess(
                id = logId,
                status = LlamaScheduledTaskLogStatus.SUCCESS,
                finishedAtMillis = finish,
                durationMs = finish - start,
                finalOutput = result.output.take(LOG_OUTPUT_MAX_CHARS),
                toolActivity = toolActivity.take(LOG_TOOL_ACTIVITY_MAX_CHARS)
            )
            UnifiedNotificationManager.showLlamaScheduledTaskCompletionNotification(
                taskName = task.name,
                success = true,
                durationMs = finish - start,
                logId = logId
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                val message = getString(R.string.llama_scheduler_cancelled_by_user)
                taskDao.markLogFailure(
                    id = logId,
                    status = LlamaScheduledTaskLogStatus.CANCELLED,
                    finishedAtMillis = now,
                    durationMs = now - start,
                    error = message,
                    toolActivity = "cancelled_by_user"
                )
                UnifiedNotificationManager.showLlamaScheduledTaskCompletionNotification(
                    taskName = task.name,
                    success = false,
                    durationMs = now - start,
                    logId = logId,
                    error = message,
                    cancelled = true
                )
            }
        } catch (error: Throwable) {
            failLog(
                taskDao = taskDao,
                logId = logId,
                start = start,
                error = error.message ?: error::class.java.simpleName
            )
            UnifiedNotificationManager.showLlamaScheduledTaskCompletionNotification(
                taskName = task.name,
                success = false,
                durationMs = System.currentTimeMillis() - start,
                logId = logId,
                error = error.message ?: error::class.java.simpleName
            )
        } finally {
            if (!request.force) {
                withContext(NonCancellable) {
                    LlamaScheduledTaskScheduler.advanceTaskAfterScheduledTime(applicationContext, task, request.scheduledAtMillis)
                }
            }
            synchronized(queueLock) {
                if (activeRun?.logId == logId) {
                    activeRun = null
                }
            }
        }
    }

    private fun cancelActiveRun(taskId: Long?, logId: Long?) {
        val active = synchronized(queueLock) { activeRun }
        if (active == null) {
            DebugLog.log("[LlamaScheduler] stop requested but no task is running")
            return
        }
        if (taskId != null && active.taskId != taskId) {
            DebugLog.log("[LlamaScheduler] stop ignored for task=$taskId active=${active.taskId}")
            return
        }
        if (logId != null && active.logId != logId) {
            DebugLog.log("[LlamaScheduler] stop ignored for log=$logId active=${active.logId}")
            return
        }
        DebugLog.log("[LlamaScheduler] cancelling running task=${active.taskId} log=${active.logId}")
        synchronized(queueLock) {
            activeRunJob?.cancel(CancellationException(getString(R.string.llama_scheduler_cancelled_by_user)))
        }
        updateProgress(getString(R.string.llama_scheduler_cancelled_by_user))
    }

    private suspend fun ensureLocalLlamaServerIfNeeded(taskName: String, server: LlamaServerEntity) {
        if (!server.isLlamaServerEngine() || !isLoopbackHost(server.host)) return
        val baseUrl = server.baseUrl()
        if (llamaServerChatService.checkConnection(baseUrl)) return

        val modelPath = settingsRepo.selectedModelPath.value
            ?: throw IllegalStateException(getString(R.string.llama_scheduler_error_no_selected_model))
        val wasAlreadyRunning = LlamaService.state.value !is ServerState.Stopped
        val intent = Intent(applicationContext, LlamaService::class.java).apply {
            action = LlamaService.ACTION_START
            putExtra(LlamaService.EXTRA_MODEL_PATH, modelPath)
            putExtra(LlamaService.EXTRA_SETTINGS_PROFILE, LlamaService.SETTINGS_PROFILE_GENERAL)
            putExtra(LlamaService.EXTRA_HOST, localHostForServer(server.host))
            putExtra(LlamaService.EXTRA_PORT, server.port)
            if (settingsRepo.speculativeEnabled.value) {
                val speculativeMode = settingsRepo.speculativeMode.value
                val shouldPassDraftModel =
                    speculativeMode.requiresDraftModel ||
                        (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && settingsRepo.mtpUseDraftModel.value)
                if (shouldPassDraftModel) {
                    putExtra(LlamaService.EXTRA_DRAFT_MODEL_PATH, settingsRepo.draftModelPath.value)
                }
                putExtra(LlamaService.EXTRA_DRAFT_MAX, settingsRepo.draftMaxTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_MIN, settingsRepo.draftMinTokens.value)
                putExtra(LlamaService.EXTRA_DRAFT_P_MIN, settingsRepo.draftPMin.value)
            }
            putExtra(LlamaService.EXTRA_FLASH_ATTENTION, settingsRepo.flashAttentionEnabled.value)
            putExtra(LlamaService.EXTRA_CUSTOM_FLAGS, settingsRepo.customFlags.value)
            putExtra(LlamaService.EXTRA_COMMAND_TEMPLATE, settingsRepo.customCommandTemplate.value)
        }
        updateProgress(getString(R.string.llama_scheduler_status_starting_local_server, taskName))
        applicationContext.startForegroundService(intent)
        ownsLocalLlamaServer = ownsLocalLlamaServer || !wasAlreadyRunning

        repeat(LOCAL_SERVER_READY_ATTEMPTS) { attempt ->
            delay(LOCAL_SERVER_READY_DELAY_MS)
            if (llamaServerChatService.checkConnection(baseUrl)) {
                DebugLog.log("[LlamaScheduler] local llama-server ready after attempt=${attempt + 1}")
                return
            }
        }
        throw IllegalStateException(getString(R.string.llama_scheduler_error_local_server_timeout))
    }

    private fun updateProgress(text: String) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, 0.2f, text)
        }
    }

    private suspend fun maybeSaveScheduledTaskOutputNote(
        task: LlamaScheduledTaskEntity,
        result: LlamaScheduledTaskRunResult
    ): Long? {
        if (result.noteToolMutatedNote) return null
        val toolConfig = NativeChatToolConfig.fromApiParams(task.apiParams)
        if (!toolConfig.toolsEnabled || !toolConfig.noteToolsEnabled) return null
        if (!scheduledTaskPromptRequestsNote(task.taskPrompt)) return null
        val output = result.output.trim()
        if (output.isBlank()) return null

        val now = System.currentTimeMillis()
        return database.noteDao().insert(
            NoteEntity(
                title = task.name.trim().ifBlank { getString(R.string.llama_scheduler_title) },
                content = output.take(SCHEDULER_FALLBACK_NOTE_MAX_CHARS),
                type = NoteType.MANUAL,
                sourceFile = getString(R.string.llama_scheduler_note_source),
                createdAt = now,
                updatedAt = now,
                isLlmWhitelisted = true
            )
        )
    }

    private suspend fun failLog(
        taskDao: com.example.llamadroid.data.dao.LlamaScheduledTaskDao,
        logId: Long,
        start: Long?,
        error: String
    ) {
        val now = System.currentTimeMillis()
        taskDao.markLogFailure(
            id = logId,
            status = LlamaScheduledTaskLogStatus.FAILED,
            finishedAtMillis = now,
            durationMs = start?.let { now - it },
            error = error,
            toolActivity = ""
        )
    }

    private fun releaseOwnedLocalServerIfIdle() {
        if (!ownsLocalLlamaServer) return
        ownsLocalLlamaServer = false
        runCatching {
            applicationContext.startService(
                Intent(applicationContext, LlamaService::class.java).apply {
                    action = LlamaService.ACTION_STOP
                }
            )
        }
    }

    private fun releaseLocks() {
        WakeLockManager.release("LlamaScheduledTaskService")
        WakeLockManager.releaseWifiLock("LlamaScheduledTaskService")
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized == "[::1]"
    }

    private fun localHostForServer(host: String): String =
        if (normalizeHost(host).contains(":")) "::1" else "127.0.0.1"

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim()
        return runCatching {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                Uri.parse(trimmed).host.orEmpty()
            } else {
                trimmed.trim('[', ']')
            }
        }.getOrDefault(trimmed).lowercase()
    }

    private data class RunRequest(
        val taskId: Long,
        val scheduledAtMillis: Long,
        val logId: Long?,
        val force: Boolean
    )

    private data class ActiveRun(
        val taskId: Long,
        val logId: Long,
        val startedAtMillis: Long
    )

    companion object {
        const val ACTION_RUN_TASK = "com.example.llamadroid.llama_scheduler.RUN_TASK"
        const val ACTION_CANCEL_RUNNING_TASK = "com.example.llamadroid.llama_scheduler.CANCEL_RUNNING_TASK"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"
        private const val EXTRA_LOG_ID = "log_id"
        private const val EXTRA_FORCE = "force"
        private const val LOCAL_SERVER_READY_ATTEMPTS = 36
        private const val LOCAL_SERVER_READY_DELAY_MS = 5_000L
        private const val LOG_OUTPUT_MAX_CHARS = 80_000
        private const val LOG_TOOL_ACTIVITY_MAX_CHARS = 40_000
        private const val SCHEDULER_FALLBACK_NOTE_MAX_CHARS = 80_000

        fun enqueue(
            context: Context,
            taskId: Long,
            scheduledAtMillis: Long,
            logId: Long? = null,
            force: Boolean = false
        ) {
            val intent = Intent(context.applicationContext, LlamaScheduledTaskService::class.java).apply {
                action = ACTION_RUN_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)
                logId?.let { putExtra(EXTRA_LOG_ID, it) }
                putExtra(EXTRA_FORCE, force)
            }
            context.applicationContext.startForegroundService(intent)
        }

        fun cancelRunning(
            context: Context,
            taskId: Long? = null,
            logId: Long? = null
        ) {
            val intent = Intent(context.applicationContext, LlamaScheduledTaskService::class.java).apply {
                action = ACTION_CANCEL_RUNNING_TASK
                taskId?.let { putExtra(EXTRA_TASK_ID, it) }
                logId?.let { putExtra(EXTRA_LOG_ID, it) }
            }
            context.applicationContext.startService(intent)
        }
    }
}

internal fun scheduledTaskPromptRequestsNote(prompt: String): Boolean {
    val lower = prompt.lowercase()
    return listOf(
        "create a note",
        "write a note",
        "save a note",
        "in a note",
        "into a note",
        "to a note",
        "as a note",
        "save to notes",
        "write in notes",
        "write into notes",
        "organizer note",
        "research note",
        "source-cited note",
        "crear una nota",
        "crea una nota",
        "escribe una nota",
        "guardar una nota",
        "guarda una nota",
        "en una nota",
        "a una nota",
        "guardar en notas",
        "guarda en notas",
        "escribe en notas",
        "notas del organizador",
        "nota de investigacion",
        "nota de investigación"
    ).any { marker -> lower.contains(marker) }
}
