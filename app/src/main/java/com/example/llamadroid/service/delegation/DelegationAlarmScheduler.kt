package com.example.llamadroid.service.delegation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One check alarm per DeepSeek task, persisted on the task row so it survives process death and
 * reboot ([rescheduleAll] runs from the boot receiver). Mirrors `AgentSleepWakeScheduler`.
 */
object DelegationAlarmScheduler {
    const val ACTION_FIRE = "com.example.llamadroid.delegation.CHECK_FIRE"
    const val ACTION_RETRY_WAKES = "com.example.llamadroid.delegation.RETRY_WAKES"
    const val EXTRA_TASK_ID = "task_id"
    private const val RETRY_REQUEST_CODE = 0x5D5FFF

    private val activeStates get() = NativeChatDelegateTaskState.ACTIVE.toList()

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    /** Returns the fire-at millis, or null when cancelled (minutes 0), capped, or no active task. */
    suspend fun schedule(context: Context, chatId: Long, taskId: Long, minutes: Int, note: String?): Long? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val dao = AppDatabase.getDatabase(appContext).nativeChatDelegateTaskDao()
            val task = dao.getById(taskId)
            // The tool resolves the task from its chat; never arm an alarm for another chat's task.
            if (task == null || task.chatId != chatId) return@withContext null
            when (val decision = DelegationAlarmPolicy.decide(minutes, task.alarmCount, canScheduleExact(appContext), System.currentTimeMillis())) {
                DelegationAlarmPolicy.Decision.Cancel -> {
                    cancel(appContext, taskId)
                    null
                }
                DelegationAlarmPolicy.Decision.Refused -> null
                is DelegationAlarmPolicy.Decision.Schedule -> {
                    // One alarm per task: arming replaces the previous PendingIntent.
                    armAlarm(appContext, taskId, decision.fireAt, decision.exact)
                    dao.setScheduledCheck(
                        taskId, decision.fireAt,
                        DelegationToolArgs.boundedText(note, DelegationToolArgs.MAX_NOTE_CHARS),
                        decision.minutes, 1
                    )
                    decision.fireAt
                }
            }
        }

    suspend fun cancel(context: Context, taskId: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        alarmManager(appContext).cancel(pendingIntent(appContext, taskId))
        AppDatabase.getDatabase(appContext).nativeChatDelegateTaskDao().setScheduledCheck(taskId, null, null, null, 0)
    }

    /** Re-arms future alarms and fires past-due ones; used on boot / app start. */
    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val tasks = AppDatabase.getDatabase(appContext).nativeChatDelegateTaskDao().getWithScheduledChecks(activeStates)
        tasks.forEach { task ->
            val at = task.nextCheckAt ?: return@forEach
            if (at <= now) fire(appContext, task.id) else armAlarm(appContext, task.id, at, canScheduleExact(appContext))
        }
    }

    /** Alarm fired (or past due): queue a CHECK_ALARM wake for the task. */
    internal suspend fun fire(context: Context, taskId: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).nativeChatDelegateTaskDao()
        val task = dao.getById(taskId) ?: return@withContext
        if (task.state !in NativeChatDelegateTaskState.ACTIVE) {
            dao.setScheduledCheck(taskId, null, null, null, 0)
            return@withContext
        }
        dao.setScheduledCheck(taskId, null, null, task.checkCadenceMinutes, 0)
        DelegationWakeDispatcher.enqueue(
            appContext, task.chatId, taskId, NativeChatDelegationWakeKind.CHECK_ALARM,
            DelegationNotices.scheduledCheck(task.title, task.checkNote, task.checkCadenceMinutes)
        )
    }

    /** One-shot exact alarm ~1 s ahead that re-runs wake delivery after a foreground-start denial. */
    fun scheduleDeliveryRetry(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!canScheduleExact(appContext)) return false
        val intent = Intent(appContext, DelegationAlarmReceiver::class.java).apply { action = ACTION_RETRY_WAKES }
        val pending = PendingIntent.getBroadcast(
            appContext, RETRY_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return try {
            alarmManager(appContext).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000L, pending)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun armAlarm(context: Context, taskId: Long, fireAt: Long, exact: Boolean) {
        val manager = alarmManager(context)
        val pending = pendingIntent(context, taskId)
        val triggerAt = maxOf(System.currentTimeMillis() + 1_000L, fireAt)
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                return
            } catch (_: SecurityException) {
                // Permission can change between the capability check and scheduling.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, DelegationAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context, DelegationAlarmPolicy.requestCode(taskId), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}

class DelegationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val action = intent?.action
        val taskId = intent?.getLongExtra(DelegationAlarmScheduler.EXTRA_TASK_ID, -1L) ?: -1L
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    DelegationAlarmScheduler.ACTION_FIRE -> if (taskId > 0L) DelegationAlarmScheduler.fire(appContext, taskId)
                    DelegationAlarmScheduler.ACTION_RETRY_WAKES -> DelegationWakeDispatcher.deliverPending(appContext)
                }
            } catch (e: Exception) {
                DebugLog.log("[DelegationAlarm] receiver failed: ${e.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
