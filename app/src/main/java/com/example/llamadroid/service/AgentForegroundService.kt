package com.example.llamadroid.service

import android.app.Service
import android.os.Binder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.OllamaRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service for AI Agent operations.
 * Keeps the agent running when the app is backgrounded or screen is locked.
 * 
 * Uses a persistent notification to satisfy Android's requirements for
 * long-running background work.
 */
class AgentForegroundService : Service() {
    
    companion object {
        private const val TAG = "AgentForegroundService"
        
        const val ACTION_START_AGENT = "start_agent"
        const val ACTION_STOP_AGENT = "stop_agent"
        const val ACTION_STOP_ALL_RUNTIME = "stop_all_runtime"
        const val ACTION_UPDATE_STATUS = "update_status"
        const val ACTION_RESUME_RUNTIME = "resume_runtime"
        const val EXTRA_RECOVERY_ONLY = "recovery_only"
        
        const val EXTRA_STATUS = "status"
        const val EXTRA_STATUS_DETAILS = "status_details"
        const val EXTRA_FOREGROUND_TASK_ID = "foreground_task_id"
        const val EXTRA_START_SOURCE = "start_source"
        private const val IMMEDIATE_FOREGROUND_ID = 96
        private const val DIRECT_START_IDLE_COOLDOWN_MS = 750L
        
        @Volatile
        private var isRunning = false
        @Volatile
        private var instance: AgentForegroundService? = null

        private val startInFlight = AtomicBoolean(false)
        private val delayedDirectStartInFlight = AtomicBoolean(false)
        private val recoveryCheckInFlight = AtomicBoolean(false)
        private val runtimeRetainCount = AtomicInteger(0)
        private val resumeTriggered = AtomicBoolean(false)
        private val serviceInstanceIds = AtomicInteger(0)
        private val lastIdleReconciledAtMs = AtomicLong(0L)
        private var runtimeAgentService: AgentService? = null
        private var runtimeOllamaService: OllamaService? = null
        private var runtimeSettingsRepository: SettingsRepository? = null
        private var runtimeOllamaManager: OllamaRuntimeManager? = null

        private fun ensureRuntime(context: Context) {
            val appContext = context.applicationContext
            if (runtimeAgentService == null) {
                runtimeAgentService = AgentService(appContext, isRuntimeOwner = true)
            }
            if (runtimeOllamaService == null) {
                runtimeOllamaService = OllamaService(appContext).also { it.initFromSettings() }
            }
            if (runtimeSettingsRepository == null) {
                runtimeSettingsRepository = SettingsRepository(appContext)
            }
            if (runtimeOllamaManager == null) {
                val database = AppDatabase.getDatabase(appContext)
                runtimeOllamaManager = OllamaRuntimeManager(
                    appContext = appContext,
                    repository = OllamaRepository(database.ollamaServerDao()),
                    runtimeScope = AgentService.agentScope,
                    sshService = SSHService(appContext)
                )
            }
        }

        fun getAgentService(context: Context): AgentService {
            ensureRuntime(context)
            return runtimeAgentService!!
        }

        fun getOllamaService(context: Context): OllamaService {
            ensureRuntime(context)
            return runtimeOllamaService!!
        }

        fun getSettingsRepository(context: Context): SettingsRepository {
            ensureRuntime(context)
            return runtimeSettingsRepository!!
        }

        fun getOllamaRuntimeManager(context: Context): OllamaRuntimeManager {
            ensureRuntime(context)
            return runtimeOllamaManager!!
        }
        
        /**
         * Start the foreground service for agent work.
         * Call this when the agent starts processing a task.
         */
        fun start(
            context: Context,
            status: String = "AI Agent running...",
            foregroundTaskId: Int? = null,
            recoveryOnly: Boolean = false,
            startSource: String = "direct"
        ) {
            recordBreadcrumb(
                event = "start_requested",
                phase = ACTION_START_AGENT,
                details = "source=$startSource recoveryOnly=$recoveryOnly running=$isRunning liveInstance=${instance != null} " +
                    "retain=${runtimeRetainCount.get()} loading=${AgentService.isLoading.value} pid=${android.os.Process.myPid()}"
            )
            val sinceIdle = System.currentTimeMillis() - lastIdleReconciledAtMs.get()
            if (!recoveryOnly && startSource == "direct" && sinceIdle in 0 until DIRECT_START_IDLE_COOLDOWN_MS) {
                if (delayedDirectStartInFlight.compareAndSet(false, true)) {
                    val appContext = context.applicationContext
                    val delayMs = DIRECT_START_IDLE_COOLDOWN_MS - sinceIdle
                    recordBreadcrumb(
                        event = "start_delayed_after_idle_reconcile",
                        phase = ACTION_START_AGENT,
                        details = "source=$startSource delayMs=$delayMs"
                    )
                    AgentService.agentScope.launch {
                        delay(delayMs)
                        delayedDirectStartInFlight.set(false)
                        start(appContext, status, foregroundTaskId, recoveryOnly, startSource)
                    }
                } else {
                    recordBreadcrumb(
                        event = "start_debounced_after_idle_reconcile",
                        phase = ACTION_START_AGENT,
                        details = "source=$startSource"
                    )
                }
                return
            }
            if (isRunning && instance == null) {
                DebugLog.log("[$TAG] Resetting stale running state before foreground service start")
                recordBreadcrumb(
                    event = "stale_running_state_reset",
                    phase = ACTION_START_AGENT,
                    details = "source=$startSource"
                )
                isRunning = false
            }
            if (isRunning) {
                updateStatus(context, status)
                if (recoveryOnly) {
                    requestResume(context)
                }
                return
            }
            if (!startInFlight.compareAndSet(false, true)) {
                recordBreadcrumb(
                    event = "start_debounced",
                    phase = ACTION_START_AGENT,
                    details = "source=$startSource recoveryOnly=$recoveryOnly"
                )
                return
            }
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START_AGENT
                putExtra(EXTRA_STATUS, status)
                foregroundTaskId?.let { putExtra(EXTRA_FOREGROUND_TASK_ID, it) }
                putExtra(EXTRA_RECOVERY_ONLY, recoveryOnly)
                putExtra(EXTRA_START_SOURCE, startSource)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                startInFlight.set(false)
                DebugLog.log("[$TAG] Failed to start foreground service: ${e.message}")
                recordBreadcrumb(
                    event = "start_request_failed",
                    phase = ACTION_START_AGENT,
                    details = "source=$startSource error=${e.message}"
                )
            }
        }

        fun startForRecovery(
            context: Context,
            status: String = "Recovering AI runtime..."
        ) {
            start(context, status = status, recoveryOnly = true, startSource = "recovery")
        }
        
        /**
         * Stop the foreground service.
         * Call this when the agent becomes idle or user cancels.
         */
        fun stop(context: Context) {
            if (!isRunning) return
            if (runtimeRetainCount.get() > 0 || AgentService.isLoading.value) return
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP_AGENT
            }
            context.startService(intent)
        }

        fun stopAllRuntime(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP_ALL_RUNTIME
            }
            context.startService(intent)
        }

        fun retainRuntime(context: Context, status: String = "AI task running…") {
            runtimeRetainCount.incrementAndGet()
            start(context, status, startSource = "retain_runtime")
        }

        fun releaseRuntime(context: Context) {
            val remaining = runtimeRetainCount.decrementAndGet().coerceAtLeast(0)
            runtimeRetainCount.set(remaining)
            if (remaining == 0 && !AgentService.isLoading.value) {
                stop(context)
            }
        }

        fun requestResume(context: Context) {
            val dispatch = resolveAgentResumeDispatch(isRunning)
            recordBreadcrumb(
                event = "resume_requested",
                phase = dispatch.action,
                details = "running=$isRunning mode=${dispatch.startSource}"
            )
            if (dispatch.useForegroundStart) {
                requestRecoveryStartIfNeeded(context, dispatch)
                return
            }

            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = dispatch.action
            }
            context.startService(intent)
            recordBreadcrumb(
                event = "resume_dispatched",
                phase = dispatch.action,
                details = "running=$isRunning mode=${dispatch.startSource}"
            )
        }

        /**
         * Avoid promoting a foreground service merely to discover that there is no work to recover.
         * AgentScreen calls this on both ON_START and ON_RESUME, so this check must be coalesced.
         */
        private fun requestRecoveryStartIfNeeded(context: Context, dispatch: AgentResumeDispatch) {
            if (!recoveryCheckInFlight.compareAndSet(false, true)) {
                recordBreadcrumb(
                    event = "resume_recovery_check_debounced",
                    phase = dispatch.action,
                    details = "source=${dispatch.startSource}"
                )
                return
            }
            val appContext = context.applicationContext
            AgentService.agentScope.launch {
                try {
                    val recoverableJobs = AiRuntimeJobStore.getRecoverableJobs(appContext)
                    if (!shouldStartAgentRecoveryForeground(
                            isServiceRunning = isRunning,
                            hasRecoverableJobs = recoverableJobs.isNotEmpty()
                        )
                    ) {
                        DebugLog.log("[$TAG] Skipping recovery foreground start; no recoverable runtime jobs")
                        recordBreadcrumb(
                            event = "resume_recovery_skipped",
                            phase = dispatch.action,
                            details = "source=${dispatch.startSource} recoverable=0"
                        )
                        return@launch
                    }
                    start(
                        context = appContext,
                        status = appContext.getString(com.example.llamadroid.R.string.agent_runtime_recovering_jobs),
                        recoveryOnly = dispatch.recoveryOnly,
                        startSource = dispatch.startSource
                    )
                } catch (error: Exception) {
                    DebugLog.log("[$TAG] Failed to inspect recoverable runtime jobs: ${error.message}")
                    recordBreadcrumb(
                        event = "resume_recovery_check_failed",
                        phase = dispatch.action,
                        details = "source=${dispatch.startSource} error=${error.message}"
                    )
                } finally {
                    recoveryCheckInFlight.set(false)
                }
            }
        }
        
        /**
         * Update the notification status text.
         */
        fun updateStatus(context: Context, status: String, details: List<String> = emptyList()) {
            if (!isRunning || instance == null) return
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
                if (details.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_STATUS_DETAILS, java.util.ArrayList(details))
                }
            }
            context.startService(intent)
        }
        
        /**
         * Check if the service is currently running.
         */
        fun isServiceRunning(): Boolean = isRunning

        fun activeRuntimeCount(): Int = runtimeRetainCount.get()

        /**
         * Clears notification/retain state that survived a completed or crashed
         * runtime. This is safe to call whenever the project dashboard changes.
         */
        fun reconcileIdleState(context: Context) {
            val appContext = context.applicationContext
            AgentService.agentScope.launch {
                val activeJobs = runCatching {
                    AiRuntimeJobStore.getRecoverableJobs(appContext)
                        .filter { !AiRuntimeJobStore.isJobStale(it) }
                }.getOrDefault(emptyList())
                if (!AgentService.isLoading.value && activeJobs.isEmpty()) {
                    runtimeRetainCount.set(0)
                    lastIdleReconciledAtMs.set(System.currentTimeMillis())
                    recordBreadcrumb(
                        event = "idle_state_reconciled",
                        details = "serviceRunning=$isRunning activeJobs=0 pid=${android.os.Process.myPid()}"
                    )
                    if (isRunning) {
                        appContext.startService(
                            Intent(appContext, AgentForegroundService::class.java).apply {
                                action = ACTION_STOP_AGENT
                            }
                        )
                    }
                }
            }
        }

        private fun recordBreadcrumb(
            event: String,
            phase: String? = null,
            details: String? = null
        ) {
            runCatching {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_foreground_service",
                    event = event,
                    phase = phase,
                    details = details
                )
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AgentForegroundService = this@AgentForegroundService
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationTaskId: Int? = null
    private var immediateForegroundActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var recoveryOnlyStart = false
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        val instanceId = serviceInstanceIds.incrementAndGet()
        DebugLog.log("[$TAG] Service created")
        recordBreadcrumb(
            event = "service_created",
            details = "instanceId=$instanceId pid=${android.os.Process.myPid()}"
        )
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_AGENT) {
            startImmediateForeground(
                status = intent.getStringExtra(EXTRA_STATUS) ?: "AI Agent running...",
                startSource = intent.getStringExtra(EXTRA_START_SOURCE) ?: "direct"
            )
        }
        when (intent?.action) {
            ACTION_START_AGENT -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "AI Agent running..."
                val foregroundTaskId = intent.getIntExtra(EXTRA_FOREGROUND_TASK_ID, -1)
                    .takeIf { it >= 0 }
                val recoveryOnly = intent.getBooleanExtra(EXTRA_RECOVERY_ONLY, false)
                val startSource = intent.getStringExtra(EXTRA_START_SOURCE) ?: "direct"
                recoveryOnlyStart = recoveryOnlyStart || recoveryOnly
                startAgentForeground(
                    status = status,
                    existingTaskId = foregroundTaskId,
                    startSource = startSource,
                    requestedAction = ACTION_START_AGENT
                )
                startInFlight.set(false)
                ensureRuntime(applicationContext)
                if (recoveryOnly) {
                    scheduleResumeIfNeeded()
                }
            }
            ACTION_STOP_AGENT -> {
                startInFlight.set(false)
                stopAgentForeground()
            }
            ACTION_STOP_ALL_RUNTIME -> {
                startInFlight.set(false)
                runtimeOllamaManager?.cancelAll()
                AgentService.stopAllJobs()
                runtimeRetainCount.set(0)
                stopAgentForeground()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Working..."
                val details = intent.getStringArrayListExtra(EXTRA_STATUS_DETAILS).orEmpty()
                updateNotificationStatus(status, details)
            }
            ACTION_RESUME_RUNTIME -> {
                ensureRuntime(applicationContext)
                recordBreadcrumb(
                    event = "resume_command_received",
                    phase = ACTION_RESUME_RUNTIME,
                    details = "running=$isRunning"
                )
                scheduleResumeIfNeeded()
            }
        }
        // Runtime recovery is explicit when the app is reopened. A sticky service can
        // resurrect an old continuation after the user has closed the app.
        return START_NOT_STICKY
    }

    private fun scheduleResumeIfNeeded() {
        if (!resumeTriggered.compareAndSet(false, true)) {
            recordBreadcrumb(
                event = "runtime_recovery_debounced",
                details = "reason=already_scheduled"
            )
            return
        }
        serviceScope.launch {
            runCatching {
                resumePersistedJobs()
            }.onFailure {
                DebugLog.log("[$TAG] Runtime resume failed: ${it.message}")
            }
        }
    }

    private suspend fun resumePersistedJobs() {
        val staleJobs = AiRuntimeJobStore.markStaleActiveJobsTerminal(applicationContext)
        val activeJobs = AiRuntimeJobStore.getRecoverableJobs(applicationContext)
        DebugLog.log("[$TAG] Recoverable runtime jobs=${activeJobs.size}, stalePruned=${staleJobs.size}")
        recordBreadcrumb(
            event = "runtime_recovery_scan",
            details = "recoverable=${activeJobs.size} stalePruned=${staleJobs.size}"
        )
        if (activeJobs.isEmpty()) {
            if (recoveryOnlyStart && runtimeRetainCount.get() == 0 && !AgentService.isLoading.value) {
                DebugLog.log("[$TAG] Recovery found no valid jobs; stopping foreground service")
                recordBreadcrumb(
                    event = "runtime_recovery_empty_stop",
                    details = "recoverable=0 stalePruned=${staleJobs.size}"
                )
                withContext(Dispatchers.Main.immediate) {
                    stopAgentForeground()
                }
            }
            return
        }

        val agentJobs = activeJobs.filter { it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT }
        val interruptedDecision = interruptedAgentRecoveryDecision()
        agentJobs.forEach { job ->
            job.conversationId?.let { conversationId ->
                AppDatabase.getDatabase(applicationContext)
                    .agentChatDao()
                    .updateResumeState(
                        conversationId,
                        interruptedDecision.resumeState,
                        getString(R.string.agent_recovery_interrupted_reason)
                    )
            }
            AiRuntimeJobStore.markState(
                applicationContext,
                jobId = job.jobId,
                status = interruptedDecision.runtimeJobStatus,
                checkpointJson = job.checkpointJson,
                progressText = job.progressText,
                errorMessage = getString(R.string.agent_recovery_waiting_continue)
            )
        }

        val resumableJobs = activeJobs.filterNot { it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT }
        resumableJobs.forEach { job ->
            AiRuntimeJobStore.markState(
                applicationContext,
                jobId = job.jobId,
                status = AiRuntimeJobStore.STATUS_RECOVERING,
                checkpointJson = job.checkpointJson,
                progressText = job.progressText ?: "recovering"
            )
        }

        runtimeOllamaManager?.resumePersistedJobs()

        if (agentJobs.isNotEmpty()) {
            AgentService.addDebugLog("⏸️ Agent job recovery is waiting for explicit Continue (${agentJobs.size}).")
        }

        if (recoveryOnlyStart && runtimeRetainCount.get() == 0 && !AgentService.isLoading.value) {
            DebugLog.log("[$TAG] No active runtime retained after recovery; stopping foreground service")
            recordBreadcrumb(
                event = "runtime_recovery_empty_stop",
                details = "recoverable=${activeJobs.size} retained=0"
            )
            withContext(Dispatchers.Main.immediate) {
                stopAgentForeground()
            }
        }
    }
    
    private fun startAgentForeground(
        status: String,
        existingTaskId: Int? = null,
        startSource: String = "direct",
        requestedAction: String = ACTION_START_AGENT
    ) {
        if (isRunning && notificationTaskId != null) {
            updateNotificationStatus(status)
            return
        }
        if (isRunning) {
            recordBreadcrumb(
                event = "foreground_state_repair",
                phase = requestedAction,
                details = "source=$startSource notificationTaskId=$notificationTaskId immediate=$immediateForegroundActive"
            )
            isRunning = false
        }
        
        isRunning = true
        
        // Acquire wake lock to keep CPU running
        acquireWakeLock()
        
        // Start foreground with notification
        val (taskId, notification) = if (existingTaskId != null) {
            val existingNotification = UnifiedNotificationManager.getForegroundNotification(existingTaskId)
            if (existingNotification != null) {
                existingTaskId to existingNotification
            } else {
                UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.AGENT,
                    "AI Agent"
                )
            }
        } else {
            UnifiedNotificationManager.startTaskForForeground(
                UnifiedNotificationManager.TaskType.AGENT,
                "AI Agent"
            )
        }
        notificationTaskId = taskId
        
        try {
            startForeground(taskId, notification)
            if (immediateForegroundActive && taskId != IMMEDIATE_FOREGROUND_ID) {
                runCatching {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                        ?.cancel(IMMEDIATE_FOREGROUND_ID)
                }
                immediateForegroundActive = false
            }
            DebugLog.log("[$TAG] Foreground service started with notification ID: $taskId")
            recordBreadcrumb(
                event = "foreground_started",
                phase = requestedAction,
                details = "source=$startSource taskId=$taskId recoveryOnly=$recoveryOnlyStart " +
                    "retain=${runtimeRetainCount.get()} pid=${android.os.Process.myPid()}"
            )
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to start foreground: ${e.message}")
            isRunning = false
            startInFlight.set(false)
            recordBreadcrumb(
                event = "foreground_start_failed",
                phase = requestedAction,
                details = "source=$startSource error=${e.message}"
            )
        }
        
        // Update with initial status
        updateNotificationStatus(status)
    }

    private fun startImmediateForeground(status: String, startSource: String) {
        if (immediateForegroundActive || notificationTaskId != null) return
        try {
            val notification = UnifiedNotificationManager.createBasicForegroundNotification(status)
            startForeground(IMMEDIATE_FOREGROUND_ID, notification)
            immediateForegroundActive = true
            recordBreadcrumb(
                event = "foreground_immediate_started",
                phase = ACTION_START_AGENT,
                details = "source=$startSource"
            )
        } catch (e: Throwable) {
            DebugLog.log("[$TAG] Immediate foreground start failed: ${e.message}")
            recordBreadcrumb(
                event = "foreground_immediate_failed",
                phase = ACTION_START_AGENT,
                details = "source=$startSource error=${e.message}"
            )
        }
    }
    
    private fun stopAgentForeground() {
        DebugLog.log("[$TAG] Stopping foreground service")
        
        isRunning = false
        startInFlight.set(false)
        resumeTriggered.set(false)
        recoveryOnlyStart = false
        immediateForegroundActive = false
        
        // Release wake lock
        releaseWakeLock()
        
        // Dismiss notification
        notificationTaskId?.let { 
            UnifiedNotificationManager.dismissTask(it) 
        }
        notificationTaskId = null
        
        // Stop foreground and service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun updateNotificationStatus(status: String, details: List<String> = emptyList()) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgressWithDetails(taskId, 0.5f, status, details)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            // Acquire CPU WakeLock
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AI-Doomsday:AgentForegroundService"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)  // 1 hour max
                DebugLog.log("[$TAG] WakeLock acquired")
            }
            
            // Acquire WifiLock to keep network alive when screen is off
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AI-Doomsday:AgentWifiLock")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                DebugLog.log("[$TAG] WifiLock acquired")
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to acquire locks: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[$TAG] WakeLock released")
            }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                DebugLog.log("[$TAG] WifiLock released")
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        immediateForegroundActive = false
        releaseWakeLock()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        notificationTaskId = null
        serviceScope.cancel()
        DebugLog.log("[$TAG] Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        recordBreadcrumb(
            event = "app_task_removed",
            details = "loading=${AgentService.isLoading.value} retain=${runtimeRetainCount.get()}"
        )
        runtimeOllamaManager?.cancelAll()
        AgentService.stopAllJobs()
        runtimeRetainCount.set(0)
        stopAgentForeground()
        super.onTaskRemoved(rootIntent)
    }
}

internal data class AgentResumeDispatch(
    val action: String,
    val useForegroundStart: Boolean,
    val recoveryOnly: Boolean,
    val startSource: String
)

internal fun resolveAgentResumeDispatch(isServiceRunning: Boolean): AgentResumeDispatch {
    return if (isServiceRunning) {
        AgentResumeDispatch(
            action = AgentForegroundService.ACTION_RESUME_RUNTIME,
            useForegroundStart = false,
            recoveryOnly = false,
            startSource = "resume_running"
        )
    } else {
        AgentResumeDispatch(
            action = AgentForegroundService.ACTION_START_AGENT,
            useForegroundStart = true,
            recoveryOnly = true,
            startSource = "resume_cold"
        )
    }
}

internal fun shouldStartAgentRecoveryForeground(
    isServiceRunning: Boolean,
    hasRecoverableJobs: Boolean
): Boolean = !isServiceRunning && hasRecoverableJobs

internal data class InterruptedAgentRecoveryDecision(
    val resumeState: String,
    val runtimeJobStatus: String,
    val requiresExplicitContinue: Boolean
)

internal fun interruptedAgentRecoveryDecision(): InterruptedAgentRecoveryDecision =
    InterruptedAgentRecoveryDecision(
        resumeState = AgentService.RESUME_STATE_INTERRUPTED,
        runtimeJobStatus = AiRuntimeJobStore.STATUS_CANCELLED,
        requiresExplicitContinue = true
    )
