package com.example.llamadroid.tama.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.service.ForegroundStart
import com.example.llamadroid.service.ForegroundServiceTypes
import com.example.llamadroid.service.isForegroundServiceStartNotAllowed
import com.example.llamadroid.tama.ui.transfer.TamaTransferHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the transfer listener reachable while a pet is visiting a paired device (this device is the
 * origin with the pet away, or hosts the guest), so the origin can reclaim remotely and the host can
 * send the guest home while the other app is closed.
 *
 * It runs only while a visit row exists and the peer is paired, and stops itself when the row clears
 * or the user taps Stop (that visit is then not restarted). `START_STICKY` re-derives everything
 * from the database after process death, so the intent may be null; `startForeground` always runs first.
 *
 * No Wi-Fi or multicast lock is held: the link needs no multicast (peers are addressed directly) and
 * a Wi-Fi lock would drain the battery for the whole visit. A sleeping radio still answers inbound
 * TCP at the next beacon; deep Doze can delay the answer, and the sender simply retries.
 */
class PetLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var announcer: Job? = null
    private var eventJob: Job? = null
    private var lastText: String? = null
    private var announcedFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: a null intent (sticky restart) must not crash.
        if (!enterForeground(buildOngoing(getString(R.string.tama_transfer_link_starting)))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                val link = TamaTransferHolder.get(applicationContext).coordinator.link
                link?.linkVisit()?.let { setSuppressed(applicationContext, it.transferId) }
                link?.stopListening()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (watcher == null) startWatching()
        return START_STICKY
    }

    private fun startWatching() {
        val handle = TamaTransferHolder.get(applicationContext)
        val link = handle.coordinator.link
        if (link == null) {
            stopSelf()
            return
        }
        handle.recoverOnceAsync()
        eventJob = scope.launch { link.events.collect { notifyEvent(it) } }
        watcher = scope.launch {
            // Re-evaluate on every state change and at least once a minute (a clearing row emits no event).
            val ticker = launch { while (true) { delay(RECHECK_MS); evaluate(link) } }
            try {
                evaluate(link)
                handle.coordinator.state.collect { evaluate(link) }
            } finally {
                ticker.cancel()
            }
        }
    }

    private suspend fun evaluate(link: PetLinkController) {
        val visit = link.linkVisit()
        if (visit == null || isSuppressed(applicationContext, visit.transferId) || !isEnabled(applicationContext)) {
            link.stopListening()
            announcer?.cancel()
            stopSelf()
            return
        }
        val prefs = prefs(applicationContext)
        val port = link.startListening(prefs.getInt(KEY_PORT, PetLinkConfig.DEFAULT_PORT))
        if (port == 0) {
            stopSelf()
            return
        }
        if (port != prefs.getInt(KEY_PORT, 0)) prefs.edit { putInt(KEY_PORT, port) }
        updateNotification(visit)
        if (announcedFor != visit.transferId) {
            announcedFor = visit.transferId
            announcer?.cancel()
            announcer = scope.launch {
                // The other side may start its listener a moment later: retry with a growing pause.
                for (wait in ANNOUNCE_DELAYS_MS) {
                    if (link.announce()) return@launch
                    delay(wait)
                }
            }
        }
    }

    private fun updateNotification(visit: LinkVisit) {
        val text = if (visit.awayAtPeer) {
            getString(R.string.tama_transfer_link_ongoing_away, visit.petName, visit.peerName)
        } else {
            getString(R.string.tama_transfer_link_ongoing_hosting, visit.petName, visit.peerName)
        }
        if (text == lastText) return
        lastText = text
        runCatching { notificationManager().notify(ONGOING_ID, buildOngoing(text)) }
    }

    private fun notifyEvent(event: PetLinkEvent) {
        val text = when (event) {
            is PetLinkEvent.WentHome -> getString(R.string.tama_transfer_link_went_home, event.petName, event.deviceName)
            is PetLinkEvent.PetBack -> getString(R.string.tama_transfer_link_back, event.petName)
            is PetLinkEvent.Unreachable -> getString(R.string.tama_transfer_link_unreachable, event.deviceName)
        }
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.tama_transfer_link_channel))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        ensureChannels()
        runCatching { notificationManager().notify(EVENT_ID, notification) }
    }

    private fun enterForeground(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            return ForegroundStart.start(this, ONGOING_ID, notification, ForegroundServiceTypes.SPECIAL_USE) is ForegroundStart.Result.Started
        }
        // specialUse does not exist below API 34, so the 2-argument form is the only valid call.
        return try {
            startForeground(ONGOING_ID, notification)
            true
        } catch (e: RuntimeException) {
            if (!isForegroundServiceStartNotAllowed(e) && e !is SecurityException) throw e
            false
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.tama_transfer_link_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.tama_transfer_link_channel_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL, getString(R.string.tama_transfer_link_events_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildOngoing(text: String): Notification {
        ensureChannels()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PetLinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.tama_transfer_link_channel))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent())
            .addAction(0, getString(R.string.tama_transfer_link_stop), stop)
            .build()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        runCatching { TamaTransferHolder.get(applicationContext).coordinator.link?.stopListening() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tama_pet_link"
        private const val EVENT_CHANNEL = "tama_pet_link_events"
        private const val ONGOING_ID = 490_310
        private const val EVENT_ID = 490_311
        private const val ACTION_STOP = "com.example.llamadroid.tama.transfer.LINK_STOP"
        private const val PREFS = "tama_transfer_prefs"
        private const val KEY_PORT = "tama_link_port"
        private const val KEY_ENABLED = "tama_link_enabled"
        private const val KEY_SUPPRESSED = "tama_link_suppressed_transfer"
        private const val RECHECK_MS = 60_000L
        private val ANNOUNCE_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L, 90_000L)

        private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** "Stay reachable during visits" setting (default on). Turning it off stops the service at its next check. */
        fun isEnabled(context: Context): Boolean = prefs(context.applicationContext).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            val app = context.applicationContext
            prefs(app).edit {
                putBoolean(KEY_ENABLED, enabled)
                if (enabled) remove(KEY_SUPPRESSED) // a Stop tapped earlier must not outlive the re-enable
            }
            if (!enabled) {
                runCatching { app.startService(Intent(app, PetLinkService::class.java).setAction(ACTION_STOP)) }
            }
        }

        internal fun isSuppressed(context: Context, transferId: String): Boolean =
            prefs(context).getString(KEY_SUPPRESSED, null) == transferId

        private fun setSuppressed(context: Context, transferId: String) {
            prefs(context).edit { putString(KEY_SUPPRESSED, transferId) }
        }

        /** Starts the link when a paired visit is active and the user did not stop it. Returns false if nothing was started. */
        suspend fun startIfNeeded(context: Context): Boolean {
            val app = context.applicationContext
            val visit = TamaTransferHolder.get(app).coordinator.link?.linkVisit() ?: return false
            if (isSuppressed(app, visit.transferId) || !isEnabled(app)) return false
            return try {
                ContextCompat.startForegroundService(app, Intent(app, PetLinkService::class.java))
                true
            } catch (error: RuntimeException) {
                if (!isForegroundServiceStartNotAllowed(error)) throw error
                false
            }
        }
    }
}
