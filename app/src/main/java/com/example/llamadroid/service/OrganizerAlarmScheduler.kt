package com.example.llamadroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.llamadroid.MainActivity
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

object OrganizerAlarmScheduler {
    private const val TAG = "[OrganizerAlarm]"
    private const val ACTION_FIRE = "com.example.llamadroid.organizer.ALARM_FIRE"
    private const val EXTRA_ALARM_ID = "alarm_id"
    private const val REQUEST_CODE_BASE = 510_000

    suspend fun scheduleAlarm(context: Context, alarm: OrganizerAlarmEntity) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        cancelAlarm(appContext, alarm.id)
        if (!alarm.enabled || alarm.triggerAtMillis <= System.currentTimeMillis()) return@withContext

        val pendingIntent = buildPendingIntent(appContext, alarm.id)
        val triggerAt = max(System.currentTimeMillis() + 1_000L, alarm.triggerAtMillis)
        val manager = alarmManager(appContext)
        runCatching {
            manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, buildShowPendingIntent(appContext, alarm.id)),
                pendingIntent
            )
            DebugLog.log("$TAG scheduled id=${alarm.id} triggerAt=$triggerAt")
        }.onFailure { error ->
            DebugLog.log("$TAG schedule failed id=${alarm.id}: ${error.message}")
            scheduleFallback(manager, triggerAt, pendingIntent)
        }
    }

    fun cancelAlarm(context: Context, alarmId: Long) {
        runCatching {
            alarmManager(context.applicationContext).cancel(buildPendingIntent(context.applicationContext, alarmId))
        }.onFailure { error ->
            DebugLog.log("$TAG cancel failed id=$alarmId: ${error.message}")
        }
    }

    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        dao.getEnabledFutureAlarms(System.currentTimeMillis()).forEach { alarm ->
            scheduleAlarm(appContext, alarm)
        }
    }

    internal suspend fun deliverAlarm(context: Context, alarmId: Long) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext).organizerDao()
        val alarm = dao.getAlarmById(alarmId) ?: return@withContext
        val now = System.currentTimeMillis()
        if (!alarm.enabled) return@withContext
        if (alarm.triggerAtMillis > now + 1_000L) {
            scheduleAlarm(appContext, alarm)
            return@withContext
        }
        if (!OrganizerAlarmRingingService.start(appContext, alarm)) {
            UnifiedNotificationManager.showOrganizerAlarmNotification(
                alarmId = alarm.id,
                title = alarm.title,
                body = alarm.message.ifBlank { alarm.title },
                triggerAtMillis = alarm.triggerAtMillis,
                soundEnabled = alarm.soundEnabled
            )
            OrganizerAlarmRinger.start(appContext, alarm.id, alarm.soundEnabled)
            OrganizerAlarmRingActivity.start(appContext, alarm.id)
        }
        dao.markAlarmDelivered(alarm.id, now)
        DebugLog.log("$TAG delivered id=${alarm.id}")
    }

    internal fun alarmIdFromIntent(intent: Intent): Long =
        intent.getLongExtra(EXTRA_ALARM_ID, -1L)

    private fun buildPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, OrganizerAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildShowPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.NotesManager.route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE + alarmId.hashCode() + 20_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleFallback(
        manager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure { error ->
            DebugLog.log("$TAG fallback schedule failed: ${error.message}")
        }
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}

class OrganizerAlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (resolveOrganizerAlarmBootRoute(intent?.action) == OrganizerAlarmBroadcastRoute.RESCHEDULE_ALL) {
                    OrganizerAlarmScheduler.rescheduleAll(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class OrganizerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarmId = intent?.let { OrganizerAlarmScheduler.alarmIdFromIntent(it) } ?: -1L
                when (resolveOrganizerAlarmRuntimeRoute(intent?.action, alarmId)) {
                    OrganizerAlarmBroadcastRoute.DELIVER_ALARM -> OrganizerAlarmScheduler.deliverAlarm(appContext, alarmId)
                    OrganizerAlarmBroadcastRoute.RESCHEDULE_ALL,
                    OrganizerAlarmBroadcastRoute.IGNORE -> Unit
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal enum class OrganizerAlarmBroadcastRoute {
    RESCHEDULE_ALL,
    DELIVER_ALARM,
    IGNORE
}

internal fun resolveOrganizerAlarmBootRoute(action: String?): OrganizerAlarmBroadcastRoute {
    return when (action) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED -> OrganizerAlarmBroadcastRoute.RESCHEDULE_ALL
        else -> OrganizerAlarmBroadcastRoute.IGNORE
    }
}

internal fun resolveOrganizerAlarmRuntimeRoute(
    action: String?,
    alarmId: Long
): OrganizerAlarmBroadcastRoute {
    return when (action) {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED -> OrganizerAlarmBroadcastRoute.IGNORE
        else -> if (alarmId > 0L) OrganizerAlarmBroadcastRoute.DELIVER_ALARM else OrganizerAlarmBroadcastRoute.IGNORE
    }
}
