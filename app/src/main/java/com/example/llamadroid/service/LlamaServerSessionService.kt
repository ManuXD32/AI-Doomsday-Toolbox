package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service boundary for keyed llama.cpp sessions. A single service process can own
 * several independent children because [LlamaServerSessionRuntime] keeps one controller/job per
 * session id. Legacy callers continue using [LlamaService].
 */
class LlamaServerSessionService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandMutex = Mutex()
    private lateinit var runtime: LlamaServerSessionRuntime
    private var notificationTaskId: Int? = null
    @Volatile private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        runtime = LlamaServerSessionRuntime(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        when (intent?.action) {
            ACTION_START -> {
                val profile = LlamaServerLaunchProfile.decode(intent.getStringExtra(EXTRA_PROFILE_JSON))
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN)
                if (sessionId.isBlank() || profile == null) {
                    DebugLog.log("LlamaServerSessionService: rejected start with missing session/profile")
                } else if (LlamaOcrExclusiveLeaseStore.rejectsSessionCommand(
                        applicationContext,
                        sessionId,
                        leaseToken
                    )
                ) {
                    DebugLog.log("LlamaServerSessionService[$sessionId]: rejected start while OCR lease is active")
                } else {
                    ensureForeground()
                    val portOverride = intent.takeIf { it.hasExtra(EXTRA_PORT) }
                        ?.getIntExtra(EXTRA_PORT, profile.serverPort)
                    scope.launch {
                        commandMutex.withLock {
                            runtime.start(sessionId, profile, portOverride)
                                .onFailure { DebugLog.log("LlamaServerSessionService[$sessionId]: ${it.message}") }
                            recycleWhenIdle(startId)
                        }
                    }
                }
            }
            ACTION_STOP -> if (sessionId.isNotBlank()) {
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN)
                if (LlamaOcrExclusiveLeaseStore.rejectsSessionCommand(applicationContext, sessionId, leaseToken)) {
                    DebugLog.log("LlamaServerSessionService[$sessionId]: rejected stop while OCR lease is active")
                } else {
                    scope.launch {
                        commandMutex.withLock {
                            runtime.stop(sessionId)
                            recycleWhenIdle(startId)
                        }
                    }
                }
            }
            ACTION_CLEAR_LOGS -> if (sessionId.isNotBlank()) {
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN)
                if (LlamaOcrExclusiveLeaseStore.rejectsSessionCommand(applicationContext, sessionId, leaseToken)) {
                    DebugLog.log("LlamaServerSessionService[$sessionId]: rejected clear while OCR lease is active")
                } else {
                    scope.launch {
                        commandMutex.withLock { runtime.clearLogs(sessionId) }
                    }
                }
            }
            ACTION_REMOVE -> if (sessionId.isNotBlank()) {
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN)
                if (LlamaOcrExclusiveLeaseStore.rejectsSessionCommand(applicationContext, sessionId, leaseToken)) {
                    DebugLog.log("LlamaServerSessionService[$sessionId]: rejected remove while OCR lease is active")
                } else {
                    scope.launch {
                        commandMutex.withLock {
                            runtime.remove(sessionId)
                            recycleWhenIdle(startId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime.close()
        scope.coroutineContext.cancelChildren()
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        notificationTaskId = null
        super.onDestroy()
    }

    private fun ensureForeground() {
        if (notificationTaskId != null) return
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SERVER,
            getString(R.string.llama_cards_title)
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
    }

    /**
     * Release the isolated runtime after its last exact session has stopped. The start-id guard
     * prevents an older STOP/REMOVE completion from stopping a service that has already received a
     * newer START command.
     */
    private fun recycleWhenIdle(commandStartId: Int) {
        if (latestStartId != commandStartId || !runtime.isIdle()) return
        runtime.close()
        notificationTaskId?.let(UnifiedNotificationManager::dismissTask)
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(commandStartId)
    }

    companion object {
        const val ACTION_START = "com.example.llamadroid.action.LLAMA_SESSION_START"
        const val ACTION_STOP = "com.example.llamadroid.action.LLAMA_SESSION_STOP"
        const val ACTION_CLEAR_LOGS = "com.example.llamadroid.action.LLAMA_SESSION_CLEAR_LOGS"
        const val ACTION_REMOVE = "com.example.llamadroid.action.LLAMA_SESSION_REMOVE"
        const val EXTRA_SESSION_ID = "LLAMA_SESSION_ID"
        const val EXTRA_PROFILE_JSON = "LLAMA_SESSION_PROFILE_JSON"
        const val EXTRA_PORT = "LLAMA_SESSION_PORT"
        const val EXTRA_LEASE_TOKEN = LlamaOcrExclusiveLeaseStore.TOKEN_EXTRA
    }
}
