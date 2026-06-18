package com.example.llamadroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity
import com.example.llamadroid.data.model.LlamaScheduledTaskLogStatus
import com.example.llamadroid.data.model.LlamaScheduledTaskScheduleType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

object LlamaScheduledTaskScheduler {
    private const val TAG = "[LlamaScheduler]"
    const val ACTION_FIRE = "com.example.llamadroid.llama_scheduler.FIRE"
    const val ACTION_CATCH_UP_RUN = "com.example.llamadroid.llama_scheduler.CATCH_UP_RUN"
    const val ACTION_CATCH_UP_SKIP = "com.example.llamadroid.llama_scheduler.CATCH_UP_SKIP"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_LOG_ID = "log_id"
    const val EXTRA_SCHEDULED_AT = "scheduled_at"
    private const val REQUEST_CODE_BASE = 620_000

    suspend fun scheduleTask(context: Context, task: LlamaScheduledTaskEntity) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        cancelTask(appContext, task.id)
        val triggerAt = task.nextRunAtMillis ?: return@withContext
        if (!task.enabled || triggerAt <= System.currentTimeMillis()) return@withContext

        val pendingIntent = buildPendingIntent(appContext, task.id, triggerAt)
        val manager = alarmManager(appContext)
        val safeTriggerAt = max(System.currentTimeMillis() + 1_000L, triggerAt)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, safeTriggerAt, pendingIntent)
            }
            DebugLog.log("$TAG scheduled task=${task.id} triggerAt=$safeTriggerAt")
        }.onFailure { error ->
            DebugLog.log("$TAG schedule failed task=${task.id}: ${error.message}")
        }
    }

    fun cancelTask(context: Context, taskId: Long) {
        runCatching {
            alarmManager(context.applicationContext).cancel(buildPendingIntent(context.applicationContext, taskId, 0L))
        }.onFailure { error ->
            DebugLog.log("$TAG cancel failed task=$taskId: ${error.message}")
        }
    }

    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).llamaScheduledTaskDao()
        createPendingCatchUps(appContext)
        dao.getEnabledFutureTasks(System.currentTimeMillis()).forEach { task ->
            scheduleTask(appContext, task)
        }
    }

    internal suspend fun deliverDueTask(context: Context, taskId: Long, scheduledAtMillis: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).llamaScheduledTaskDao()
        val task = dao.getTaskById(taskId) ?: return@withContext
        if (!task.enabled) return@withContext
        if ((task.nextRunAtMillis ?: scheduledAtMillis) > System.currentTimeMillis() + 1_000L) {
            scheduleTask(appContext, task)
            return@withContext
        }
        LlamaScheduledTaskService.enqueue(
            context = appContext,
            taskId = task.id,
            scheduledAtMillis = scheduledAtMillis.takeIf { it > 0L } ?: (task.nextRunAtMillis ?: System.currentTimeMillis())
        )
    }

    internal suspend fun runCatchUp(context: Context, logId: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val log = AppDatabase.getDatabase(appContext).llamaScheduledTaskDao().getLogById(logId) ?: return@withContext
        val taskId = log.taskId ?: return@withContext
        LlamaScheduledTaskService.enqueue(
            context = appContext,
            taskId = taskId,
            scheduledAtMillis = log.scheduledAtMillis,
            logId = log.id,
            force = true
        )
    }

    internal suspend fun skipCatchUp(context: Context, logId: Long) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context.applicationContext).llamaScheduledTaskDao()
        dao.markLogSkipped(logId, LlamaScheduledTaskLogStatus.SKIPPED, System.currentTimeMillis())
    }

    private suspend fun createPendingCatchUps(context: Context) {
        val dao = AppDatabase.getDatabase(context.applicationContext).llamaScheduledTaskDao()
        val now = System.currentTimeMillis()
        dao.getDueTasks(now).forEach { task ->
            val scheduledAt = task.nextRunAtMillis ?: return@forEach
            val existing = dao.findLogByTaskScheduleAndStatus(
                taskId = task.id,
                scheduledAtMillis = scheduledAt,
                status = LlamaScheduledTaskLogStatus.PENDING_CATCH_UP
            )
            val logId = existing?.id ?: dao.insertLog(
                LlamaScheduledTaskLogEntity(
                    taskId = task.id,
                    taskName = task.name,
                    scheduledAtMillis = scheduledAt,
                    status = LlamaScheduledTaskLogStatus.PENDING_CATCH_UP
                )
            )
            UnifiedNotificationManager.showLlamaScheduledTaskCatchUpNotification(
                logId = logId,
                taskName = task.name,
                scheduledAtMillis = scheduledAt
            )
            advanceTaskAfterScheduledTime(context, task, scheduledAt)
        }
    }

    suspend fun advanceTaskAfterScheduledTime(
        context: Context,
        task: LlamaScheduledTaskEntity,
        scheduledAtMillis: Long
    ) {
        val dao = AppDatabase.getDatabase(context.applicationContext).llamaScheduledTaskDao()
        val isOneTime = task.scheduleType == LlamaScheduledTaskScheduleType.ONE_TIME
        val nextRun = if (isOneTime) {
            null
        } else {
            LlamaScheduledTaskSchedule.computeNextRun(task, scheduledAtMillis + 1L)
        }
        val updated = task.copy(
            enabled = if (isOneTime) false else task.enabled,
            nextRunAtMillis = nextRun,
            lastRunAtMillis = scheduledAtMillis,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateTask(updated)
        if (nextRun != null) {
            scheduleTask(context.applicationContext, updated)
        } else {
            cancelTask(context.applicationContext, task.id)
        }
    }

    private fun buildPendingIntent(context: Context, taskId: Long, scheduledAtMillis: Long): PendingIntent {
        val intent = Intent(context, LlamaScheduledTaskReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}

class LlamaScheduledTaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (resolveLlamaScheduledTaskBootRoute(intent?.action) == LlamaScheduledTaskBroadcastRoute.RESCHEDULE_ALL) {
                    LlamaScheduledTaskScheduler.rescheduleAll(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class LlamaScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val taskId = intent?.getLongExtra(LlamaScheduledTaskScheduler.EXTRA_TASK_ID, -1L) ?: -1L
                val logId = intent?.getLongExtra(LlamaScheduledTaskScheduler.EXTRA_LOG_ID, -1L) ?: -1L
                when (resolveLlamaScheduledTaskRuntimeRoute(intent?.action, taskId, logId)) {
                    LlamaScheduledTaskBroadcastRoute.CATCH_UP_RUN -> LlamaScheduledTaskScheduler.runCatchUp(appContext, logId)
                    LlamaScheduledTaskBroadcastRoute.CATCH_UP_SKIP -> LlamaScheduledTaskScheduler.skipCatchUp(appContext, logId)
                    LlamaScheduledTaskBroadcastRoute.DELIVER_DUE_TASK -> {
                        val scheduledAt = intent?.getLongExtra(LlamaScheduledTaskScheduler.EXTRA_SCHEDULED_AT, -1L) ?: -1L
                        LlamaScheduledTaskScheduler.deliverDueTask(appContext, taskId, scheduledAt)
                    }
                    LlamaScheduledTaskBroadcastRoute.RESCHEDULE_ALL,
                    LlamaScheduledTaskBroadcastRoute.IGNORE -> Unit
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal enum class LlamaScheduledTaskBroadcastRoute {
    RESCHEDULE_ALL,
    CATCH_UP_RUN,
    CATCH_UP_SKIP,
    DELIVER_DUE_TASK,
    IGNORE
}

internal fun resolveLlamaScheduledTaskBootRoute(action: String?): LlamaScheduledTaskBroadcastRoute {
    return when (action) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED -> LlamaScheduledTaskBroadcastRoute.RESCHEDULE_ALL
        else -> LlamaScheduledTaskBroadcastRoute.IGNORE
    }
}

internal fun resolveLlamaScheduledTaskRuntimeRoute(
    action: String?,
    taskId: Long,
    logId: Long
): LlamaScheduledTaskBroadcastRoute {
    return when (action) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED -> LlamaScheduledTaskBroadcastRoute.IGNORE
        LlamaScheduledTaskScheduler.ACTION_CATCH_UP_RUN -> {
            if (logId > 0L) LlamaScheduledTaskBroadcastRoute.CATCH_UP_RUN else LlamaScheduledTaskBroadcastRoute.IGNORE
        }
        LlamaScheduledTaskScheduler.ACTION_CATCH_UP_SKIP -> {
            if (logId > 0L) LlamaScheduledTaskBroadcastRoute.CATCH_UP_SKIP else LlamaScheduledTaskBroadcastRoute.IGNORE
        }
        else -> {
            if (taskId > 0L) LlamaScheduledTaskBroadcastRoute.DELIVER_DUE_TASK else LlamaScheduledTaskBroadcastRoute.IGNORE
        }
    }
}
