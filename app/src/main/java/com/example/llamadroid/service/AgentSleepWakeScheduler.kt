package com.example.llamadroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.llamadroid.data.db.AgentSleepWakeEntity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max

/** Durable one-shot scheduling for the Agent's sleep_until tool. */
object AgentSleepWakeScheduler {
    const val ACTION_FIRE = "com.example.llamadroid.agent_sleep_wake.FIRE"
    const val EXTRA_WAKE_ID = "wake_id"
    const val EXTRA_RUN_EPOCH = "run_epoch"
    private const val REQUEST_CODE_BASE = 734_000
    private const val BUSY_RETRY_MS = 30_000L

    data class ScheduledWake(val entity: AgentSleepWakeEntity, val exact: Boolean)

    suspend fun schedule(
        context: Context,
        conversationId: Long,
        rootTurnId: String?,
        runEpoch: Long,
        wakeAtEpochMs: Long,
        reason: String
    ): ScheduledWake = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).agentWorkflowDao()
        dao.getSleepWakeForConversation(conversationId)?.let { previous ->
            cancelPendingIntent(appContext, previous.id)
            dao.cancelSleepWakeForConversation(conversationId)
        }
        val manager = alarmManager(appContext)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        val entity = AgentSleepWakeEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            rootTurnId = rootTurnId,
            runEpoch = runEpoch,
            wakeAtEpochMs = wakeAtEpochMs,
            reason = reason.take(500),
            approximate = !exact
        )
        dao.upsertSleepWake(entity)
        val scheduledExactly = schedulePendingIntent(appContext, entity, exact)
        val storedEntity = if (scheduledExactly == exact) entity else entity.copy(approximate = true)
        if (storedEntity != entity) dao.upsertSleepWake(storedEntity)
        ScheduledWake(storedEntity, scheduledExactly)
    }

    suspend fun cancelConversation(context: Context, conversationId: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).agentWorkflowDao()
        dao.getSleepWakeForConversation(conversationId)?.let { cancelPendingIntent(appContext, it.id) }
        dao.cancelSleepWakeForConversation(conversationId)
    }

    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        AppDatabase.getDatabase(appContext).agentWorkflowDao().getPendingSleepWakes().forEach { wake ->
            if (wake.wakeAtEpochMs <= now) {
                deliver(appContext, wake.id, wake.runEpoch)
            } else {
                schedulePendingIntent(appContext, wake, exactSchedulingAvailable(appContext))
            }
        }
    }

    internal suspend fun deliver(context: Context, wakeId: String, runEpoch: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).agentWorkflowDao()
        val wake = dao.getSleepWake(wakeId) ?: return@withContext
        if (wake.status != "PENDING" || wake.runEpoch != runEpoch) return@withContext
        val now = System.currentTimeMillis()
        if (wake.wakeAtEpochMs > now + 1_000L) {
            schedulePendingIntent(appContext, wake, exactSchedulingAvailable(appContext))
            return@withContext
        }

        // A wake never steals the singleton runtime from another live turn.
        val liveConversation = AgentService.activeConversationId.value
        if (AgentService.isLoading.value || (liveConversation != null && liveConversation != wake.conversationId)) {
            schedulePendingIntent(
                appContext,
                wake.copy(wakeAtEpochMs = now + BUSY_RETRY_MS),
                exactSchedulingAvailable(appContext)
            )
            return@withContext
        }
        if (dao.claimSleepWake(wake.id, wake.runEpoch, now) != 1) return@withContext
        runCatching {
            AgentService.resumeConversationFromSleepWake(appContext, wake).join()
            dao.completeSleepWake(wake.id)
        }.onFailure { error ->
            DebugLog.log("[AgentSleepWake] resume failed: ${error.javaClass.simpleName}")
            AppDatabase.getDatabase(appContext).agentChatDao().updateResumeState(
                wake.conversationId,
                AgentService.RESUME_STATE_INTERRUPTED,
                "Alarm resume failed: ${error.javaClass.simpleName}"
            )
            // Keep the fired row visible for recovery diagnostics and explicit Continue.
        }
    }

    private fun schedulePendingIntent(context: Context, wake: AgentSleepWakeEntity, exact: Boolean): Boolean {
        val manager = alarmManager(context)
        val triggerAt = max(System.currentTimeMillis() + 1_000L, wake.wakeAtEpochMs)
        val pendingIntent = buildPendingIntent(context, wake.id, wake.runEpoch)
        if (exact) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                return true
            } catch (_: SecurityException) {
                // Permission can change between the capability check and scheduling.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        return false
    }

    private fun cancelPendingIntent(context: Context, wakeId: String) {
        alarmManager(context).cancel(buildPendingIntent(context, wakeId, 0L))
    }

    private fun buildPendingIntent(context: Context, wakeId: String, runEpoch: Long): PendingIntent {
        val intent = Intent(context, AgentSleepWakeReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_WAKE_ID, wakeId)
            putExtra(EXTRA_RUN_EPOCH, runEpoch)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + wakeId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun exactSchedulingAvailable(context: Context): Boolean {
        val manager = alarmManager(context)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}

class AgentSleepWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val wakeId = intent?.getStringExtra(AgentSleepWakeScheduler.EXTRA_WAKE_ID).orEmpty()
                val epoch = intent?.getLongExtra(AgentSleepWakeScheduler.EXTRA_RUN_EPOCH, -1L) ?: -1L
                if (wakeId.isNotBlank() && epoch >= 0L) {
                    AgentSleepWakeScheduler.deliver(context.applicationContext, wakeId, epoch)
                }
            } finally {
                result.finish()
            }
        }
    }
}
