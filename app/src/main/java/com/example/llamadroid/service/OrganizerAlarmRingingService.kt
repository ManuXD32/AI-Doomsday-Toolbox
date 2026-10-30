package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.llamadroid.data.db.OrganizerAlarmEntity
import com.example.llamadroid.util.DebugLog

class OrganizerAlarmRingingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> startRinging(intent)
            ACTION_DISMISS -> {
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                stopRinging(alarmId)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OrganizerAlarmRinger.stop()
        super.onDestroy()
    }

    private fun startRinging(intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) {
            stopSelf()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty().ifBlank { title }
        val triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER_AT, System.currentTimeMillis())
        val soundEnabled = intent.getBooleanExtra(EXTRA_SOUND_ENABLED, true)
        val notification = UnifiedNotificationManager.buildOrganizerAlarmNotification(
            alarmId = alarmId,
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            soundEnabled = soundEnabled
        )
        val notificationId = UnifiedNotificationManager.organizerAlarmNotificationId(alarmId)
        val started = ForegroundStart.start(this, notificationId, notification, ForegroundServiceTypes.MEDIA_PLAYBACK)
        if (started is ForegroundStart.Result.Failed) {
            // Keep ringing: show the alarm as a plain notification and still play the sound/UI.
            DebugLog.log("[OrganizerAlarm] foreground start failed: ${started.reason}")
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(notificationId, notification)
            }
        }
        OrganizerAlarmRinger.start(applicationContext, alarmId, soundEnabled)
        OrganizerAlarmRingActivity.start(applicationContext, alarmId)
        DebugLog.log("[OrganizerAlarm] ringing service started id=$alarmId")
    }

    private fun stopRinging(alarmId: Long) {
        OrganizerAlarmRinger.stop(alarmId.takeIf { it > 0L })
        if (alarmId > 0L) {
            UnifiedNotificationManager.cancelOrganizerAlarmNotification(alarmId)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        DebugLog.log("[OrganizerAlarm] ringing service stopped id=$alarmId")
    }

    companion object {
        private const val ACTION_RING = "com.example.llamadroid.organizer.ALARM_RING_SERVICE"
        private const val ACTION_DISMISS = "com.example.llamadroid.organizer.ALARM_DISMISS"
        private const val EXTRA_ALARM_ID = "extra_alarm_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_BODY = "extra_body"
        private const val EXTRA_TRIGGER_AT = "extra_trigger_at"
        private const val EXTRA_SOUND_ENABLED = "extra_sound_enabled"
        private const val REQUEST_CODE_BASE = 560_000

        fun start(context: Context, alarm: OrganizerAlarmEntity): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, OrganizerAlarmRingingService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_ALARM_ID, alarm.id)
                putExtra(EXTRA_TITLE, alarm.title)
                putExtra(EXTRA_BODY, alarm.message.ifBlank { alarm.title })
                putExtra(EXTRA_TRIGGER_AT, alarm.triggerAtMillis)
                putExtra(EXTRA_SOUND_ENABLED, alarm.soundEnabled)
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.onFailure { error ->
                DebugLog.log("[OrganizerAlarm] ringing service start failed id=${alarm.id}: ${error.message}")
            }.isSuccess
        }

        fun dismiss(context: Context, alarmId: Long) {
            if (alarmId <= 0L) return
            runCatching {
                context.applicationContext.startService(dismissIntent(context.applicationContext, alarmId))
            }.onFailure { error ->
                DebugLog.log("[OrganizerAlarm] ringing service dismiss failed id=$alarmId: ${error.message}")
            }
        }

        fun dismissPendingIntent(context: Context, alarmId: Long): android.app.PendingIntent =
            android.app.PendingIntent.getService(
                context,
                REQUEST_CODE_BASE + alarmId.hashCode(),
                dismissIntent(context, alarmId),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

        private fun dismissIntent(context: Context, alarmId: Long): Intent =
            Intent(context.applicationContext, OrganizerAlarmRingingService::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
    }
}
