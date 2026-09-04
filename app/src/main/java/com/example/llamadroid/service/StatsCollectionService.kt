package com.example.llamadroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StatsCollectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectionJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (collectionJob?.isActive != true) {
            collectionJob = serviceScope.launch {
                val repository = SystemStatsRepository(applicationContext)
                // CPU and swap usage are cumulative-counter deltas. Establish a
                // baseline and let the kernel counters advance before persisting
                // the first sample, otherwise the first chart point is empty.
                repository.warmUp()
                delay(COLLECTOR_WARMUP_MS)
                while (isActive && SystemStatsCollectionManager.isEnabled(applicationContext)) {
                    runCatching { repository.sampleAndPersist() }
                        .onSuccess {
                            SystemStatsCollectionManager.recordSampleSuccess(applicationContext)
                        }
                        .onFailure { error ->
                            SystemStatsCollectionManager.recordSampleFailure(applicationContext, error)
                            DebugLog.log("[StatsCollection] sample failed: ${error.javaClass.simpleName}: ${error.message}")
                        }
                    delay(SAMPLE_INTERVAL_MS)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.stats_collection_notification_title))
            .setContentText(getString(R.string.stats_collection_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.stats_collection_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "system_stats_collection"
        private const val NOTIFICATION_ID = 490_100
        private const val SAMPLE_INTERVAL_MS = 60_000L
        private const val COLLECTOR_WARMUP_MS = 1_000L
        private const val ACTION_START = "com.example.llamadroid.stats.START"
        private const val ACTION_STOP = "com.example.llamadroid.stats.STOP"

        fun start(context: Context) {
            val intent = Intent(context, StatsCollectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, StatsCollectionService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
