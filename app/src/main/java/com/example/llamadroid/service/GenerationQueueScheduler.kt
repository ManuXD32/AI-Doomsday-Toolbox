package com.example.llamadroid.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.data.db.RestoreCoordinator
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object GenerationQueueScheduler {
    private const val ALARM_REQUEST_CODE = 491_101
    const val ACTION_FIRE = "com.example.llamadroid.action.RUN_GENERATION_QUEUE_ALARM"

    fun hasExactAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun schedule(context: Context, atMillis: Long) {
        require(hasExactAccess(context)) { "Alarms & reminders access is required" }
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent(context))
    }

    fun cancel(context: Context) {
        cancelAlarmOnly(context)
        GenerationQueueNotifications.dismiss(context)
    }

    fun cancelAlarmOnly(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(context))
    }

    suspend fun restoreFutureSchedule(context: Context) {
        val repository = GenerationQueueRepository(context)
        val control = repository.controlNow()
        if (control.state != "SCHEDULED") return
        val atMillis = control.scheduledAtMillis ?: return
        if (atMillis <= System.currentTimeMillis() || !hasExactAccess(context)) {
            repository.markMissedSchedule()
            GenerationQueueNotifications.showMissed(context)
            return
        }
        runCatching {
            schedule(context, atMillis)
            GenerationQueueNotifications.showScheduled(context, repository.pendingCount(), atMillis)
        }.onFailure {
            repository.markMissedSchedule()
            GenerationQueueNotifications.showMissed(context)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GenerationQueueAlarmReceiver::class.java).apply { action = ACTION_FIRE }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class GenerationQueueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GenerationQueueScheduler.ACTION_FIRE) return
        if (RestoreCoordinator.isMaintenance(context)) return
        runCatching {
            ContextCompat.startForegroundService(context,
                GenerationQueueService.runIntent(context, scheduled = true))
        }.onFailure {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    GenerationQueueRepository(context).markMissedSchedule()
                    GenerationQueueNotifications.showStartBlocked(context)
                } finally { pendingResult.finish() }
            }
        }
    }
}

/** The queue owns one stable notification; generated prompts stay off the lock screen. */
object GenerationQueueNotifications {
    const val ID = 491_000
    private const val CHANNEL_ID = "doomsday_generation_queue"

    private fun localized(context: Context): Context = LlamaApplication.updateLocale(context)

    fun running(context: Context, progress: GenerationQueueProgress, currentKind: String?): Notification {
        val local = localized(context)
        ensureChannel(local)
        val current = when (currentKind) {
            GenerationQueueSnapshot.IMAGE, GenerationQueueSnapshot.UPSCALE ->
                local.getString(R.string.generation_queue_current_image)
            GenerationQueueSnapshot.VIDEO -> local.getString(R.string.generation_queue_current_video)
            else -> local.getString(R.string.generation_queue_waiting)
        }
        return base(local)
            .setContentTitle(local.getString(R.string.generation_queue_notification_title))
            .setContentText(local.getString(R.string.generation_queue_notification_progress,
                progress.finished, progress.total, progress.waiting))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                local.getString(R.string.generation_queue_notification_detail,
                    progress.finished, progress.total, progress.succeeded, progress.failed, current)))
            .setProgress(progress.total.coerceAtLeast(1), progress.finished, false)
            .setOngoing(true)
            .build()
    }

    fun showPending(context: Context, count: Int) {
        val local = localized(context)
        ensureChannel(local)
        notify(local, base(local)
            .setContentTitle(local.getString(R.string.generation_queue_title))
            .setContentText(local.getString(R.string.generation_queue_run_count, count))
            .build())
    }

    fun showScheduled(context: Context, count: Int, atMillis: Long) {
        val local = localized(context)
        ensureChannel(local)
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(atMillis))
        notify(local, base(local)
            .setContentTitle(local.getString(R.string.generation_queue_scheduled_title))
            .setContentText(local.getString(R.string.generation_queue_scheduled_notification, count, time))
            .build())
    }

    fun showMissed(context: Context) {
        val local = localized(context)
        ensureChannel(local)
        notify(local, base(local)
            .setContentTitle(local.getString(R.string.generation_queue_missed_title))
            .setContentText(local.getString(R.string.generation_queue_missed_message))
            .build())
    }

    fun showStartBlocked(context: Context) {
        val local = localized(context)
        ensureChannel(local)
        notify(local, base(local)
            .setContentTitle(local.getString(R.string.generation_queue_start_blocked_title))
            .setContentText(local.getString(R.string.generation_queue_start_blocked_message))
            .build())
    }

    fun showStopped(context: Context, progress: GenerationQueueProgress, paused: Boolean,
                    mediaLimitReached: Boolean = false) {
        val local = localized(context)
        ensureChannel(local)
        notify(local, base(local)
            .setContentTitle(local.getString(if (paused) R.string.generation_queue_paused else R.string.generation_queue_finished))
            .setContentText(if (mediaLimitReached) local.getString(R.string.generation_queue_media_limit_message)
                else local.getString(R.string.generation_queue_notification_detail,
                    progress.finished, progress.total, progress.succeeded, progress.failed,
                    local.getString(if (paused) R.string.generation_queue_paused else R.string.generation_queue_finished)))
            .build())
    }

    fun dismiss(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }

    private fun base(context: Context): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setContentIntent(PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.GenerationQueue.route)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.generation_queue_view),
            PendingIntent.getActivity(context, ID + 1,
                Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_ROUTE, Screen.GenerationQueue.route)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAutoCancel(false)

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.generation_queue_notification_channel),
            NotificationManager.IMPORTANCE_LOW))
    }

    private fun notify(context: Context, notification: Notification) {
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ID, notification) }
    }
}
