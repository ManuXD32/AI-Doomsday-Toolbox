package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager

/**
 * Keeps resource-heavy media workflow jobs in Android foreground-service priority.
 *
 * The actual orchestration still lives in MediaTranslationWorkflowService so the existing
 * screen state holder keeps working; this service is the lifecycle anchor that prevents long
 * ffmpeg/Whisper/TTS work from being treated like ordinary UI-owned background work.
 */
class MediaTranslationForegroundService : Service() {
    private var taskId: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundSession()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundSession(
                intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
                    getString(R.string.workflow_media_foreground_title)
                }
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForegroundSession()
        super.onDestroy()
    }

    private fun startForegroundSession(title: String) {
        if (taskId != null) return
        val (id, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            title
        )
        taskId = id
        startForeground(id, notification)
        WakeLockManager.acquire(applicationContext, "MediaTranslationForegroundService")
        WakeLockManager.acquireWifiLock(applicationContext, "MediaTranslationForegroundService")
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "media_translation_foreground_service",
            event = "foreground_started",
            details = "taskId=$id"
        )
    }

    private fun stopForegroundSession() {
        taskId?.let { id ->
            UnifiedNotificationManager.dismissTask(id)
            @Suppress("DEPRECATION")
            stopForeground(true)
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "media_translation_foreground_service",
                event = "foreground_stopped",
                details = "taskId=$id"
            )
        }
        taskId = null
        WakeLockManager.release("MediaTranslationForegroundService")
        WakeLockManager.releaseWifiLock("MediaTranslationForegroundService")
    }

    companion object {
        private const val ACTION_START = "com.example.llamadroid.action.MEDIA_TRANSLATION_FOREGROUND_START"
        private const val ACTION_STOP = "com.example.llamadroid.action.MEDIA_TRANSLATION_FOREGROUND_STOP"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context.applicationContext, MediaTranslationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { error ->
                DebugLog.log("[MediaTranslationForegroundService] start failed: ${error.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, MediaTranslationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.applicationContext.stopService(intent)
        }
    }
}
