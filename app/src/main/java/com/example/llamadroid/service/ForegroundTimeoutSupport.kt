package com.example.llamadroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog

/**
 * Shared handler for `Service.onTimeout` (Android 15+ dataSync / mediaProcessing limits).
 * Stopping the service runs its normal `onDestroy`, which cancels jobs and releases locks;
 * if it did not stop within seconds Android would crash the app.
 */
object ForegroundTimeoutSupport {
    private const val CHANNEL_ID = "adt_background_limit"
    private const val NOTIFICATION_BASE_ID = 7300

    fun handle(service: Service, fgsType: Int) {
        val name = service.javaClass.simpleName
        DebugLog.log("[$name] foreground service timeout (type=$fgsType); stopping cleanly")
        postNotice(service, name)
        try {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        service.stopSelf()
    }

    private fun postNotice(context: Context, serviceName: String) {
        try {
            val manager = NotificationManagerCompat.from(context)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.background_limit_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.background_limit_title))
                .setContentText(context.getString(R.string.background_limit_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.background_limit_text)))
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_BASE_ID + (serviceName.hashCode() and 0x3FF), notification)
        } catch (_: SecurityException) {
            // Notification permission denied; the stop still happens.
        } catch (_: Exception) {
        }
    }
}
