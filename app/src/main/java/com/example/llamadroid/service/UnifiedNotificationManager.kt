package com.example.llamadroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.llamadroid.MainActivity
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified notification manager for all AI tasks.
 * Shows grouped notifications with progress tracking.
 */
object UnifiedNotificationManager {
    
    private const val CHANNEL_ID = "doomsday_ai_tasks"
    private const val CHANNEL_NAME = "AI Tasks"
    private const val AGENT_ATTENTION_CHANNEL_ID = "doomsday_agent_attention"
    private const val COMPLETION_CHANNEL_ID = "doomsday_completion"
    private const val COMPLETION_CHANNEL_NAME = "Task Completions"
    private const val AGENT_ATTENTION_ID = 98
    private const val AI_RUNTIME_RECOVERY_ID = 490_000
    private const val TAMA_NEEDS_CHANNEL_ID = "tama_pet_needs"
    private const val TAMA_FARM_CHANNEL_ID = "tama_crop_ready"
    private const val TAMA_POOP_CHANNEL_ID = "tama_poop_alerts"
    private const val TAMA_SLEEP_CHANNEL_ID = "tama_sleep_status"
    private const val TAMA_CHAT_REPLY_CHANNEL_ID = "tama_chat_replies"
    private const val TAMA_STUDY_CHANNEL_ID = "tama_study_timers"
    private const val ORGANIZER_ALARM_CHANNEL_ID = "organizer_alarms"
    private const val GROUP_KEY = "com.example.llamadroid.AI_TASKS"
    private const val SUMMARY_ID = 0
    private const val COMPLETION_ID = 99
    private const val TAMA_SLEEP_NOTIFICATION_BASE = 420_000
    private const val TAMA_CHAT_REPLY_NOTIFICATION_BASE = 430_000
    private const val TAMA_DEEP_DREAM_RETRY_BASE = 440_000
    private const val TAMA_STUDY_NOTIFICATION_BASE = 450_000
    private const val ORGANIZER_ALARM_NOTIFICATION_BASE = 460_000
    private const val LLAMA_SCHEDULED_TASK_NOTIFICATION_BASE = 470_000
    private const val LLAMA_SCHEDULED_TASK_CATCH_UP_BASE = 480_000

    enum class CompletionAlertPolicy {
        NEVER,
        SUCCESS_ONLY
    }

    enum class AgentAttentionReason {
        APPROVAL_REQUIRED,
        PLAN_APPROVAL_REQUIRED,
        USER_INPUT_REQUIRED
    }
    
    /**
     * Represents a running task
     */
    data class TaskInfo(
        val id: Int,
        val type: TaskType,
        val title: String,
        val progress: Float = 0f,  // 0.0 to 1.0
        val progressText: String = "",
        val progressDetails: List<String> = emptyList(),
        val completionAlertPolicy: CompletionAlertPolicy = type.defaultCompletionAlertPolicy,
        val isComplete: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null
    )
    
    enum class TaskType(
        val emoji: String,
        val label: String,
        val defaultCompletionAlertPolicy: CompletionAlertPolicy = CompletionAlertPolicy.NEVER
    ) {
        IMAGE_GEN("🎨", "Image Generation"),
        VIDEO_GEN("🎥", "Video Generation"),
        VIDEO_UPSCALE("🎬", "Video Upscaling"),
        VIDEO_INTERPOLATION("🎞️", "Video Interpolation"),
        TRANSCRIPTION("🎤", "Transcription"),
        LLM_CHAT("💬", "Chat"),
        DOWNLOAD("📥", "Download"),
        PDF_SUMMARY("📄", "PDF Summary"),
        PDF_TRANSLATION("🌐", "PDF Translation"),
        FILE_SERVER("📂", "File Server"),
        MODEL_SHARE("🔁", "Model Share"),
        LLAMA_SERVER("🦙", "LLM Server"),
        LLAMA_CLIENT("💭", "Llama Chat"),
        LLAMA_CALL("☎", "Llama Call"),
        LIVE_TRANSLATOR("🌐", "Live Translator"),
        KNOWLEDGE_BASE("📚", "Knowledge Base"),
        ONNX_TTS("🔊", "Text to Speech"),
        BACKGROUND_REMOVAL("🪄", "Background Removal"),
        AI_SERVERS("🌐", "AI Servers"),
        LLAMA_SCHEDULED_TASK("🗓", "Scheduled Llama Task", CompletionAlertPolicy.SUCCESS_ONLY),
        ZIM_SHARE("📚", "ZIM File Share"),
        BENCHMARK("⚡", "Benchmark"),
        ADVENTURE("⚔️", "Adventure"),
        AGENT("🤖", "AI Agent", CompletionAlertPolicy.SUCCESS_ONLY),
        QUADTRIX("🧬", "Quadtrix Training", CompletionAlertPolicy.SUCCESS_ONLY)
    }
    
    // Active tasks
    private val _activeTasks = ConcurrentHashMap<Int, TaskInfo>()
    private val _activeTasksFlow = MutableStateFlow<List<TaskInfo>>(emptyList())
    val activeTasks = _activeTasksFlow.asStateFlow()
    
    // Notification IDs
    private val nextId = AtomicInteger(100)
    
    private lateinit var appContext: Context
    private var notificationManager: NotificationManager? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Clean up any stale notifications from previous app sessions
        // This fixes the bug where multiple notifications appear after force-close
        cleanupStaleNotifications()
    }
    
    /**
     * Clean up stale notifications from previous sessions.
     * Called on init to prevent duplicate notification ghosts.
     */
    private fun cleanupStaleNotifications() {
        try {
            // Clear any existing progress notifications from our channel
            // Only keep active ones that match current _activeTasks
            val nm = notificationManager ?: return
            
            // Cancel summary and all task notifications from previous sessions
            nm.activeNotifications.forEach { notification ->
                // Only cancel our app's notifications (IDs >= 100 are task IDs, SUMMARY_ID is 0)
                if (notification.id >= 100 || notification.id == SUMMARY_ID || notification.id == AGENT_ATTENTION_ID) {
                    // Check if this notification is from a currently active task
                    if (notification.id == AGENT_ATTENTION_ID || !_activeTasks.containsKey(notification.id)) {
                        nm.cancel(notification.id)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    /**
     * Cancel all active notifications.
     * Call this when the app is completely shutting down.
     */
    fun cancelAllNotifications() {
        try {
            val nm = notificationManager ?: return

            // Cancel all task notifications
            _activeTasks.keys.forEach { taskId ->
                nm.cancel(taskId)
            }
            
            // Clear the active tasks
            _activeTasks.clear()
            updateTasksFlow()
            
            // Cancel summary
            nm.cancel(SUMMARY_ID)

            // Cancel completion notification  
            nm.cancel(COMPLETION_ID)
            nm.cancel(AGENT_ATTENTION_ID)
        } catch (e: Exception) {
            // Ignore errors
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Progress channel (silent, ongoing updates)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI task progress notifications"
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(progressChannel)
            
            // Completion channel (with sound and vibration)
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                COMPLETION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when AI tasks complete"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)  // Short double vibrate
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(completionChannel)

            val attentionChannel = NotificationChannel(
                AGENT_ATTENTION_CHANNEL_ID,
                appContext.getString(R.string.agent_attention_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.agent_attention_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(attentionChannel)

            val tamaNeedsChannel = NotificationChannel(
                TAMA_NEEDS_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_needs_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_needs_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(tamaNeedsChannel)

            val tamaFarmChannel = NotificationChannel(
                TAMA_FARM_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_farm_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_farm_desc)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(tamaFarmChannel)

            val tamaPoopChannel = NotificationChannel(
                TAMA_POOP_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_poop_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_poop_desc)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(tamaPoopChannel)

            val tamaSleepChannel = NotificationChannel(
                TAMA_SLEEP_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_sleep_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_sleep_desc)
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(tamaSleepChannel)

            val tamaChatReplyChannel = NotificationChannel(
                TAMA_CHAT_REPLY_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_chat_reply_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_chat_reply_desc)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(tamaChatReplyChannel)

            val tamaStudyChannel = NotificationChannel(
                TAMA_STUDY_CHANNEL_ID,
                appContext.getString(R.string.tama_notification_channel_study_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.tama_notification_channel_study_desc)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(tamaStudyChannel)

            val organizerAlarmChannel = NotificationChannel(
                ORGANIZER_ALARM_CHANNEL_ID,
                appContext.getString(R.string.organizer_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.organizer_alarm_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(organizerAlarmChannel)
        }
    }
    
    /**
     * Start a new task and show notification
     */
    fun startTask(
        type: TaskType,
        title: String,
        completionAlertPolicy: CompletionAlertPolicy = type.defaultCompletionAlertPolicy
    ): Int {
        val id = nextId.getAndIncrement()
        val task = TaskInfo(
            id = id,
            type = type,
            title = title,
            progress = 0f,
            progressText = if (::appContext.isInitialized) appContext.getString(R.string.dist_starting) else "Starting...",
            completionAlertPolicy = completionAlertPolicy
        )
        _activeTasks[id] = task
        updateTasksFlow()
        showTaskNotification(task)
        updateSummaryNotification()
        return id
    }
    
    /**
     * Start a new task for a foreground service.
     * Returns Pair(taskId, notification) for use with startForeground().
     */
    fun startTaskForForeground(
        type: TaskType,
        title: String,
        completionAlertPolicy: CompletionAlertPolicy = type.defaultCompletionAlertPolicy
    ): Pair<Int, android.app.Notification> {
        val id = startTask(type, title, completionAlertPolicy)
        val notification = getForegroundNotification(id) 
            ?: createBasicForegroundNotification(title)
        return Pair(id, notification)
    }
    
    /**
     * Update task progress
     */
    fun updateProgress(taskId: Int, progress: Float, progressText: String) {
        updateProgressWithDetails(taskId, progress, progressText, emptyList())
    }

    fun updateProgressWithDetails(taskId: Int, progress: Float, progressText: String, details: List<String>) {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progress = progress,
                progressText = progressText,
                progressDetails = details.takeLast(5)
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            showTaskNotification(updated)
        }
    }
    
    /**
     * Complete a task successfully
     */
    fun completeTask(taskId: Int, resultText: String = "Complete") {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progress = 1f,
                progressText = resultText,
                isComplete = true
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            
            if (updated.completionAlertPolicy == CompletionAlertPolicy.SUCCESS_ONLY) {
                showCompletionNotification(updated)
            }
            
            // Remove from active tasks immediately
            _activeTasks.remove(taskId)
            updateTasksFlow()
            
            // Cancel the progress notification
            try {
                NotificationManagerCompat.from(appContext).cancel(taskId)
            } catch (e: Exception) {}
            
            updateSummaryNotification()
            
            // Auto-dismiss completion notification after 10 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    NotificationManagerCompat.from(appContext).cancel(COMPLETION_ID)
                } catch (e: Exception) {}
            }, 10000)
        }
    }
    
    /**
     * Show completion notification with sound and vibration
     */
    private fun showCompletionNotification(task: TaskInfo) {
        if (!::appContext.isInitialized) return
        
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.Agent.route)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, COMPLETION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ ${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        try {
            NotificationManagerCompat.from(appContext).notify(COMPLETION_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    /**
     * Fail a task with error
     */
    fun failTask(taskId: Int, errorMessage: String) {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progressText = "Error: $errorMessage",
                isError = true,
                errorMessage = errorMessage
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            showTaskNotification(updated)
            updateSummaryNotification()
        }
    }
    
    /**
     * Dismiss a task notification
     */
    fun dismissTask(taskId: Int) {
        _activeTasks.remove(taskId)
        updateTasksFlow()
        try {
            NotificationManagerCompat.from(appContext).cancel(taskId)
        } catch (e: Exception) {
            // Ignore
        }
        updateSummaryNotification()
    }

    fun showAgentAttention(
        reason: AgentAttentionReason,
        previewTitle: String,
        previewBody: String
    ) {
        if (!::appContext.isInitialized) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.Agent.route)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            AGENT_ATTENTION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (contentTitle, contentText) = when (reason) {
            AgentAttentionReason.APPROVAL_REQUIRED -> {
                appContext.getString(R.string.agent_attention_approval_title) to
                    appContext.getString(R.string.agent_attention_approval_body)
            }
            AgentAttentionReason.PLAN_APPROVAL_REQUIRED -> {
                appContext.getString(R.string.agent_attention_plan_title) to
                    appContext.getString(R.string.agent_attention_plan_body)
            }
            AgentAttentionReason.USER_INPUT_REQUIRED -> {
                appContext.getString(R.string.agent_attention_user_input_title) to
                    appContext.getString(R.string.agent_attention_user_input_body)
            }
        }

        val builder = NotificationCompat.Builder(appContext, AGENT_ATTENTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        appendLine(contentText)
                        if (previewTitle.isNotBlank()) {
                            appendLine()
                            appendLine(previewTitle)
                        }
                        if (previewBody.isNotBlank()) {
                            append(previewBody)
                        }
                    }.trim()
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        try {
            NotificationManagerCompat.from(appContext).notify(AGENT_ATTENTION_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }

    fun showAiRuntimeRecoveryNotification(
        recoverableCount: Int,
        route: String
    ) {
        if (!::appContext.isInitialized || recoverableCount <= 0) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            AI_RUNTIME_RECOVERY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appContext.getString(R.string.ai_runtime_recovery_notification_title))
            .setContentText(appContext.getString(R.string.ai_runtime_recovery_notification_body, recoverableCount))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    appContext.getString(R.string.ai_runtime_recovery_notification_body, recoverableCount)
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(AI_RUNTIME_RECOVERY_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission not granted
        }
    }

    fun dismissAgentAttention() {
        if (!::appContext.isInitialized) return
        try {
            NotificationManagerCompat.from(appContext).cancel(AGENT_ATTENTION_ID)
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun updateTasksFlow() {
        _activeTasksFlow.value = _activeTasks.values.toList().sortedByDescending { it.id }
    }
    
    private fun showTaskNotification(task: TaskInfo) {
        if (!::appContext.isInitialized) return
        
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, task.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val icon = when (task.type) {
            TaskType.IMAGE_GEN -> android.R.drawable.ic_menu_gallery
            TaskType.VIDEO_GEN -> android.R.drawable.ic_media_play
            TaskType.VIDEO_UPSCALE -> android.R.drawable.ic_media_play
            TaskType.VIDEO_INTERPOLATION -> android.R.drawable.ic_media_play
            TaskType.TRANSCRIPTION -> android.R.drawable.ic_btn_speak_now
            TaskType.LLM_CHAT -> android.R.drawable.ic_menu_send
            TaskType.DOWNLOAD -> android.R.drawable.stat_sys_download
            TaskType.PDF_SUMMARY -> android.R.drawable.ic_menu_agenda
            TaskType.PDF_TRANSLATION -> android.R.drawable.ic_menu_upload
            TaskType.FILE_SERVER -> android.R.drawable.ic_menu_share
            TaskType.MODEL_SHARE -> android.R.drawable.ic_menu_upload
            TaskType.LLAMA_SERVER -> android.R.drawable.ic_menu_manage
            TaskType.LLAMA_CLIENT -> android.R.drawable.ic_menu_send
            TaskType.LLAMA_CALL -> android.R.drawable.ic_btn_speak_now
            TaskType.LIVE_TRANSLATOR -> android.R.drawable.ic_menu_upload
            TaskType.KNOWLEDGE_BASE -> android.R.drawable.ic_menu_search
            TaskType.ONNX_TTS -> android.R.drawable.ic_btn_speak_now
            TaskType.BACKGROUND_REMOVAL -> android.R.drawable.ic_menu_crop
            TaskType.LLAMA_SCHEDULED_TASK -> android.R.drawable.ic_menu_today
            TaskType.ZIM_SHARE -> android.R.drawable.ic_menu_share
            TaskType.BENCHMARK -> android.R.drawable.ic_menu_compass
            TaskType.ADVENTURE -> android.R.drawable.ic_menu_compass
            TaskType.AGENT -> android.R.drawable.ic_menu_manage
            TaskType.QUADTRIX -> android.R.drawable.ic_menu_upload
            TaskType.AI_SERVERS -> android.R.drawable.ic_menu_share
        }
        
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setOngoing(!task.isComplete && !task.isError)
            .setAutoCancel(task.isComplete || task.isError)

        if (task.progressDetails.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        appendLine(task.progressText)
                        task.progressDetails.forEach { appendLine(it) }
                    }.trim()
                )
            )
        }
        
        // Add progress bar for incomplete tasks
        if (!task.isComplete && !task.isError) {
            if (task.progress < 0f) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, (task.progress.coerceIn(0f, 1f) * 100).toInt(), false)
            }
        }
        
        // Set appropriate priority/icon for completion state
        when {
            task.isComplete -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                builder.setProgress(0, 0, false)
            }
            task.isError -> {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                builder.setProgress(0, 0, false)
            }
        }

        if (task.type == TaskType.DOWNLOAD && !task.isComplete && !task.isError) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelDownloadPendingIntent(task.title, task.id)
            )
        }
        if (task.type == TaskType.ONNX_TTS && !task.isComplete && !task.isError) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelOnnxTtsPendingIntent(task.id)
            )
        }
        if (task.type == TaskType.LLAMA_CALL && !task.isComplete && !task.isError) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.llama_call_hang_up),
                createHangUpLlamaCallPendingIntent(task.id)
            )
        }
        if (task.type == TaskType.BACKGROUND_REMOVAL && !task.isComplete && !task.isError) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelOnnxBgrPendingIntent(task.id)
            )
        }
        if (task.type == TaskType.VIDEO_INTERPOLATION && !task.isComplete && !task.isError) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelVideoInterpolationPendingIntent(task.id)
            )
        }
        
        try {
            NotificationManagerCompat.from(appContext).notify(task.id, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    private fun updateSummaryNotification() {
        if (!::appContext.isInitialized) return
        
        val tasks = _activeTasks.values.toList()
        
        if (tasks.isEmpty()) {
            try {
                NotificationManagerCompat.from(appContext).cancel(SUMMARY_ID)
            } catch (e: Exception) {
                // Ignore
            }
            return
        }
        
        val runningCount = tasks.count { !it.isComplete && !it.isError }
        val completedCount = tasks.count { it.isComplete }
        val errorCount = tasks.count { it.isError }
        
        val summaryText = buildString {
            if (runningCount > 0) append(appContext.getString(R.string.notification_summary_running_count, runningCount))
            if (completedCount > 0) {
                if (isNotEmpty()) append(" • ")
                append(appContext.getString(R.string.notification_summary_done_count, completedCount))
            }
            if (errorCount > 0) {
                if (isNotEmpty()) append(" • ")
                append(appContext.getString(R.string.notification_summary_failed_count, errorCount))
            }
        }
        
        // Build expanded style
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle(appContext.getString(R.string.app_name))
            .setSummaryText(summaryText)
        
        tasks.take(5).forEach { task ->
            val statusIcon = when {
                task.isComplete -> "✅"
                task.isError -> "❌"
                else -> "⏳"
            }
            inbox.addLine("$statusIcon ${task.type.emoji} ${task.title}: ${task.progressText}")
        }
        
        if (tasks.size > 5) {
            inbox.addLine(appContext.getString(R.string.notification_summary_more, tasks.size - 5))
        }
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(summaryText)
            .setStyle(inbox)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(false)
            .setOngoing(runningCount > 0)
        
        try {
            NotificationManagerCompat.from(appContext).notify(SUMMARY_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    /**
     * Get a notification builder for foreground services
     */
    fun getForegroundNotification(taskId: Int): android.app.Notification? {
        val task = _activeTasks[taskId] ?: return null
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, taskId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setProgress(100, (task.progress * 100).toInt(), false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (task.type == TaskType.AGENT) {
            val stopIntent = Intent(appContext, AgentForegroundService::class.java).apply {
                action = AgentForegroundService.ACTION_STOP_ALL_RUNTIME
            }
            val stopPendingIntent = PendingIntent.getService(
                appContext,
                taskId + 10_000,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                appContext.getString(R.string.action_stop),
                stopPendingIntent
            )
        }
        if (task.type == TaskType.DOWNLOAD) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelDownloadPendingIntent(task.title, taskId)
            )
        }
        if (task.type == TaskType.ONNX_TTS) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelOnnxTtsPendingIntent(taskId)
            )
        }
        if (task.type == TaskType.LLAMA_CALL) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.llama_call_hang_up),
                createHangUpLlamaCallPendingIntent(taskId)
            )
        }
        if (task.type == TaskType.BACKGROUND_REMOVAL) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelOnnxBgrPendingIntent(taskId)
            )
        }
        if (task.type == TaskType.VIDEO_INTERPOLATION) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_cancel),
                createCancelVideoInterpolationPendingIntent(taskId)
            )
        }
        return builder.build()
    }

    private fun createCancelVideoInterpolationPendingIntent(requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            appContext,
            requestCode + 90_000,
            VideoInterpolationService.createCancelIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createHangUpLlamaCallPendingIntent(requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            appContext,
            requestCode + 80_000,
            LlamaCallService.hangUpIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCancelOnnxBgrPendingIntent(requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            appContext,
            requestCode + 70_000,
            OnnxBackgroundRemovalService.cancelIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCancelOnnxTtsPendingIntent(requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            appContext,
            requestCode + 60_000,
            OnnxTtsGenerationService.cancelIntent(appContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCancelDownloadPendingIntent(filename: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_FILENAME, filename)
        }
        return PendingIntent.getService(
            appContext,
            requestCode + 50_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create a basic foreground notification for services that need to start immediately
     */
    fun createBasicForegroundNotification(title: String): android.app.Notification {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("UnifiedNotificationManager not initialized")
        }
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, 999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(appContext.getString(R.string.notification_initializing))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun showTamaPetNeedNotification(
        pet: TamaPet,
        statKey: String,
        offsetPx: Int = 0
    ) {
        if (!::appContext.isInitialized) return

        val notificationId = 400_000 + pet.id.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_NEEDS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(appContext.getString(R.string.tama_notification_pet_needs_title, pet.name))
            .setContentText(petNeedSummary(statKey))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        createPetNotificationRemoteViews(pet, statKey, offsetPx)?.let { remoteViews ->
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
        }

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaCropReadyNotification(
        pet: TamaPet,
        readyCount: Int
    ) {
        if (!::appContext.isInitialized) return

        val notificationId = 410_000 + pet.id.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_FARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(appContext.getString(R.string.tama_notification_crops_ready_title, pet.name))
            .setContentText(appContext.getString(R.string.tama_notification_crops_ready_body, readyCount))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaLivestockFullNotification(
        pet: TamaPet,
        type: FarmLivestockType
    ) {
        if (!::appContext.isInitialized) return

        val notificationId = 411_000 + (pet.id.hashCode() * 31) + type.id.hashCode()
        val structureName = appContext.getString(
            if (type == FarmLivestockType.BARN) R.string.tama_farm_barn_title else R.string.tama_farm_coop_title
        )
        val productName = appContext.getString(
            if (type == FarmLivestockType.BARN) R.string.tama_item_milk_bottle else R.string.tama_item_egg
        )
        val builder = NotificationCompat.Builder(appContext, TAMA_FARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(appContext.getString(R.string.tama_notification_livestock_full_title, pet.name, structureName))
            .setContentText(appContext.getString(R.string.tama_notification_livestock_full_body, productName))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaLivestockHungryNotification(
        pet: TamaPet,
        type: FarmLivestockType,
        hungryCount: Int
    ) {
        if (!::appContext.isInitialized) return

        val notificationId = 412_000 + (pet.id.hashCode() * 31) + type.id.hashCode()
        val structureName = appContext.getString(
            if (type == FarmLivestockType.BARN) R.string.tama_farm_barn_title else R.string.tama_farm_coop_title
        )
        val builder = NotificationCompat.Builder(appContext, TAMA_FARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.tama_notification_livestock_hungry_title, pet.name, structureName))
            .setContentText(appContext.getString(R.string.tama_notification_livestock_hungry_body, hungryCount))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaPoopNotification(pet: TamaPet) {
        if (!::appContext.isInitialized) return
        val notificationId = 415_000 + pet.id.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_POOP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.tama_notification_poop_title, pet.name))
            .setContentText(appContext.getString(R.string.tama_notification_poop_body))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaPoopNeglectNotification(pet: TamaPet) {
        if (!::appContext.isInitialized) return
        val notificationId = 416_000 + pet.id.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_POOP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.tama_notification_poop_neglect_title, pet.name))
            .setContentText(appContext.getString(R.string.tama_notification_poop_neglect_body))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun dismissTamaPoopNotifications(petId: String) {
        if (!::appContext.isInitialized) return
        try {
            val manager = NotificationManagerCompat.from(appContext)
            manager.cancel(415_000 + petId.hashCode())
            manager.cancel(416_000 + petId.hashCode())
        } catch (_: Exception) {
        }
    }

    fun showTamaSleepNotification(pet: TamaPet) {
        if (!::appContext.isInitialized) return
        val sleepStart = pet.sleepStartTime ?: return
        updateTamaSleepNotification(pet.id, pet.name, sleepStart)
    }

    fun refreshTamaSleepNotification(pet: TamaPet) {
        if (!::appContext.isInitialized) return
        val sleepStart = pet.sleepStartTime ?: return
        if (isNotificationVisible(TAMA_SLEEP_NOTIFICATION_BASE + pet.id.hashCode())) return
        updateTamaSleepNotification(pet.id, pet.name, sleepStart)
    }

    fun cancelTamaSleepNotification(petId: String) {
        if (!::appContext.isInitialized) return
        try {
            NotificationManagerCompat.from(appContext).cancel(TAMA_SLEEP_NOTIFICATION_BASE + petId.hashCode())
        } catch (_: Exception) {
        }
    }

    fun showTamaChatReplyNotification(pet: TamaPet, reply: String) {
        if (!::appContext.isInitialized) return
        val notificationId = TAMA_CHAT_REPLY_NOTIFICATION_BASE + pet.id.hashCode()
        val preview = previewReply(reply)
        val builder = NotificationCompat.Builder(appContext, TAMA_CHAT_REPLY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(appContext.getString(R.string.tama_notification_chat_reply_title, pet.name))
            .setContentText(appContext.getString(R.string.tama_notification_chat_reply_body, preview))
            .setStyle(NotificationCompat.BigTextStyle().bigText(appContext.getString(R.string.tama_notification_chat_reply_body, preview)))
            .setContentIntent(createOpenTamaChatPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaStudyNotification(
        pet: TamaPet,
        title: String,
        body: String
    ) {
        if (!::appContext.isInitialized) return
        val notificationId = TAMA_STUDY_NOTIFICATION_BASE + pet.id.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_STUDY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showOrganizerAlarmNotification(
        alarmId: Long,
        title: String,
        body: String,
        triggerAtMillis: Long,
        soundEnabled: Boolean
    ) {
        if (!::appContext.isInitialized) return
        val notificationId = organizerAlarmNotificationId(alarmId)
        val notification = buildOrganizerAlarmNotification(
            alarmId = alarmId,
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            soundEnabled = soundEnabled
        )

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }

    fun organizerAlarmNotificationId(alarmId: Long): Int =
        ORGANIZER_ALARM_NOTIFICATION_BASE + alarmId.hashCode()

    fun buildOrganizerAlarmNotification(
        alarmId: Long,
        title: String,
        body: String,
        triggerAtMillis: Long,
        soundEnabled: Boolean
    ): Notification {
        val ringPendingIntent = OrganizerAlarmRingActivity.pendingIntent(appContext, alarmId)
        val dismissPendingIntent = OrganizerAlarmRingingService.dismissPendingIntent(appContext, alarmId)
        return NotificationCompat.Builder(appContext, ORGANIZER_ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title.ifBlank { appContext.getString(R.string.organizer_alarm_notification_title) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(ringPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(triggerAtMillis)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(ringPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.organizer_alarm_dismiss),
                dismissPendingIntent
            )
            .setDefaults(
                if (soundEnabled) {
                    NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE
                } else {
                    NotificationCompat.DEFAULT_VIBRATE
                }
            )
            .setSilent(!soundEnabled)
            .build()
    }

    fun cancelOrganizerAlarmNotification(alarmId: Long) {
        if (!::appContext.isInitialized) return
        runCatching {
            NotificationManagerCompat.from(appContext)
                .cancel(organizerAlarmNotificationId(alarmId))
        }
    }

    fun showLlamaScheduledTaskCatchUpNotification(
        logId: Long,
        taskName: String,
        scheduledAtMillis: Long
    ) {
        if (!::appContext.isInitialized) return
        val notificationId = LLAMA_SCHEDULED_TASK_CATCH_UP_BASE + logId.hashCode()
        val runIntent = Intent(appContext, LlamaScheduledTaskReceiver::class.java).apply {
            action = LlamaScheduledTaskScheduler.ACTION_CATCH_UP_RUN
            putExtra(LlamaScheduledTaskScheduler.EXTRA_LOG_ID, logId)
        }
        val skipIntent = Intent(appContext, LlamaScheduledTaskReceiver::class.java).apply {
            action = LlamaScheduledTaskScheduler.ACTION_CATCH_UP_SKIP
            putExtra(LlamaScheduledTaskScheduler.EXTRA_LOG_ID, logId)
        }
        val runPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId + 1,
            runIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val date = java.util.Date(scheduledAtMillis)
        val bodyText = appContext.getString(
            R.string.llama_scheduler_notification_catch_up_body,
            taskName,
            android.text.format.DateFormat.getDateFormat(appContext).format(date),
            android.text.format.DateFormat.getTimeFormat(appContext).format(date)
        )
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(appContext.getString(R.string.llama_scheduler_notification_catch_up_title))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(createOpenLlamaSchedulerPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(
                android.R.drawable.ic_media_play,
                appContext.getString(R.string.llama_scheduler_action_run_now),
                runPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.llama_scheduler_action_skip),
                skipPendingIntent
            )

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showLlamaScheduledTaskCompletionNotification(
        taskName: String,
        success: Boolean,
        durationMs: Long,
        logId: Long,
        error: String? = null,
        cancelled: Boolean = false
    ) {
        if (!::appContext.isInitialized) return
        val notificationId = LLAMA_SCHEDULED_TASK_NOTIFICATION_BASE + logId.hashCode()
        val title = when {
            success -> appContext.getString(R.string.llama_scheduler_notification_success_title, taskName)
            cancelled -> appContext.getString(R.string.llama_scheduler_notification_cancelled_title, taskName)
            else -> appContext.getString(R.string.llama_scheduler_notification_failed_title, taskName)
        }
        val body = when {
            success -> appContext.getString(R.string.llama_scheduler_notification_success_body, formatDurationShort(durationMs))
            cancelled -> appContext.getString(R.string.llama_scheduler_notification_cancelled_body, formatDurationShort(durationMs))
            else -> appContext.getString(
                R.string.llama_scheduler_notification_failed_body,
                formatDurationShort(durationMs),
                error.orEmpty()
            )
        }
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(
                when {
                    success -> android.R.drawable.stat_sys_download_done
                    cancelled -> android.R.drawable.ic_menu_close_clear_cancel
                    else -> android.R.drawable.stat_notify_error
                }
            )
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(createOpenLlamaSchedulerPendingIntent(notificationId))
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showAdventureReadyNotification(
        title: String,
        body: String,
        dungeonTypeName: String
    ) {
        if (!::appContext.isInitialized) return
        val notificationId = 417_000 + dungeonTypeName.hashCode()
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.Adventure.createRoute(dungeonTypeName))
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    fun showTamaDeepDreamRetryOnOpenNotification(petId: String, petName: String) {
        if (!::appContext.isInitialized) return
        val notificationId = TAMA_DEEP_DREAM_RETRY_BASE + petId.hashCode()
        val body = appContext.getString(R.string.tama_notification_deep_dream_retry_on_open_body, petName)
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appContext.getString(R.string.tama_notification_deep_dream_retry_on_open_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun createOpenTamaPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, com.example.llamadroid.ui.navigation.Screen.Tama.route)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenTamaChatPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, com.example.llamadroid.ui.navigation.Screen.TamaChat.route)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenOrganizerPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.NotesManager.route)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenLlamaSchedulerPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.LlamaScheduler.route)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateTamaSleepNotification(petId: String, petName: String, sleepStart: Long) {
        val notificationId = TAMA_SLEEP_NOTIFICATION_BASE + petId.hashCode()
        val builder = NotificationCompat.Builder(appContext, TAMA_SLEEP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(appContext.getString(R.string.tama_notification_sleep_title, petName))
            .setContentText(appContext.getString(R.string.tama_notification_sleep_body_quiet))
            .setContentIntent(createOpenTamaPendingIntent(notificationId))
            .setWhen(sleepStart)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun isNotificationVisible(notificationId: Int): Boolean {
        val nm = notificationManager ?: return false
        return try {
            nm.activeNotifications.any { it.id == notificationId }
        } catch (_: Exception) {
            false
        }
    }

    private fun previewReply(reply: String): String {
        val words = reply.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return appContext.getString(R.string.tama_notification_chat_reply_empty)
        val preview = words.take(10).joinToString(" ")
        return if (words.size > 10) "$preview…" else preview
    }

    private fun formatDurationShort(durationMs: Long): String {
        val seconds = (durationMs / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            "${minutes}m ${remainingSeconds}s"
        } else {
            "${remainingSeconds}s"
        }
    }

    private fun petNeedSummary(statKey: String): String {
        val resId = when (statKey) {
            "hunger" -> R.string.tama_notification_need_hunger
            "happiness" -> R.string.tama_notification_need_happiness
            "hygiene" -> R.string.tama_notification_need_hygiene
            else -> R.string.tama_notification_need_attention
        }
        return appContext.getString(resId)
    }

    private fun createPetNotificationRemoteViews(
        pet: TamaPet,
        statKey: String,
        offsetPx: Int
    ): RemoteViews? {
        val bitmap = loadPetSpriteBitmap(pet, offsetPx) ?: return null
        return RemoteViews(appContext.packageName, R.layout.notification_tama_pet).apply {
            setImageViewBitmap(R.id.petSprite, bitmap)
            setTextViewText(R.id.petTitle, appContext.getString(R.string.tama_notification_pet_needs_title, pet.name))
            setTextViewText(R.id.petBody, petNeedSummary(statKey))
        }
    }

    private fun loadPetSpriteBitmap(pet: TamaPet, offsetPx: Int): Bitmap? {
        val speciesLine = PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
        val assetPath = resolvePetSpriteAssetPath(
            speciesLine = speciesLine,
            stage = pet.stage,
            state = PetSpriteState.IDLE,
            frameIndex = 0
        )
        return try {
            appContext.assets.open(assetPath).use { stream ->
                val base = BitmapFactory.decodeStream(stream) ?: return null
                val width = base.width + 32
                val height = base.height
                val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBitmap)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val left = ((width - base.width) / 2f) + offsetPx
                canvas.drawBitmap(base, left, 0f, paint)
                canvasBitmap
            }
        } catch (_: Exception) {
            null
        }
    }
}
