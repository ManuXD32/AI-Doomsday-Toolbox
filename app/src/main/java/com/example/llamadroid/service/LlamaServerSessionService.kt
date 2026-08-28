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

/**
 * Foreground service boundary for keyed llama.cpp sessions. A single service process can own
 * several independent children because [LlamaServerSessionRuntime] keeps one controller/job per
 * session id. Legacy callers continue using [LlamaService].
 */
class LlamaServerSessionService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var runtime: LlamaServerSessionRuntime
    private var notificationTaskId: Int? = null

    override fun onCreate() {
        super.onCreate()
        runtime = LlamaServerSessionRuntime(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        when (intent?.action) {
            ACTION_START -> {
                val profile = LlamaServerLaunchProfile.decode(intent.getStringExtra(EXTRA_PROFILE_JSON))
                if (sessionId.isBlank() || profile == null) {
                    DebugLog.log("LlamaServerSessionService: rejected start with missing session/profile")
                } else {
                    ensureForeground()
                    val portOverride = intent.takeIf { it.hasExtra(EXTRA_PORT) }
                        ?.getIntExtra(EXTRA_PORT, profile.serverPort)
                    scope.launch {
                        runtime.start(sessionId, profile, portOverride)
                            .onFailure { DebugLog.log("LlamaServerSessionService[$sessionId]: ${it.message}") }
                    }
                }
            }
            ACTION_STOP -> if (sessionId.isNotBlank()) {
                scope.launch { runtime.stop(sessionId) }
            }
            ACTION_CLEAR_LOGS -> if (sessionId.isNotBlank()) {
                scope.launch { runtime.clearLogs(sessionId) }
            }
            ACTION_REMOVE -> if (sessionId.isNotBlank()) {
                scope.launch { runtime.remove(sessionId) }
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

    companion object {
        const val ACTION_START = "com.example.llamadroid.action.LLAMA_SESSION_START"
        const val ACTION_STOP = "com.example.llamadroid.action.LLAMA_SESSION_STOP"
        const val ACTION_CLEAR_LOGS = "com.example.llamadroid.action.LLAMA_SESSION_CLEAR_LOGS"
        const val ACTION_REMOVE = "com.example.llamadroid.action.LLAMA_SESSION_REMOVE"
        const val EXTRA_SESSION_ID = "LLAMA_SESSION_ID"
        const val EXTRA_PROFILE_JSON = "LLAMA_SESSION_PROFILE_JSON"
        const val EXTRA_PORT = "LLAMA_SESSION_PORT"
    }
}
