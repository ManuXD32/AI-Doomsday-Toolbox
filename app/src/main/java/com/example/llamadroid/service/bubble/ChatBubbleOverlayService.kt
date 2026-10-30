package com.example.llamadroid.service.bubble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.service.ForegroundServiceTypes
import com.example.llamadroid.service.ForegroundStart
import com.example.llamadroid.service.LlamaClientService.GenerationState
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.service.isForegroundServiceStartNotAllowed
import com.example.llamadroid.ui.bubble.ChatBubbleActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating chat bubble drawn over other apps (main process, `specialUse` foreground service).
 *
 * One bubble at a time: starting it for another chat replaces the target. Tapping the bubble opens
 * [ChatBubbleActivity]; dragging moves it and it snaps to the nearest horizontal edge on release.
 * A red dot appears when the chat finishes (or fails) while the window is closed. The bubble is
 * hidden while the window is open ([ChatBubbleState.windowOpen]).
 */
class ChatBubbleOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackJob: Job? = null
    private var windowJob: Job? = null

    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var bubble: ChatBubbleView
    private lateinit var gesture: ChatBubbleGesture
    private var params: WindowManager.LayoutParams? = null
    private var windowAdded = false
    private var target: ChatBubbleTarget? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowContext = createOverlayWindowContext()
        windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        gesture = ChatBubbleGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        bubble = ChatBubbleView(windowContext).apply {
            accentColor = ACCENT_COLOR
            contentDescription = getString(R.string.chat_bubble_content_desc)
        }
        configureTouch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            shutdown()
            return START_NOT_STICKY
        }
        val chatId = intent?.getLongExtra(EXTRA_CHAT_ID, -1L) ?: -1L
        val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1L)?.takeIf { it >= 0L }
        if (chatId > 0L) setTarget(ChatBubbleTarget(chatId, serverId))
        // Always satisfy startForegroundService() first; shutdown() then removes the notification.
        if (!startForegroundSafely() || target == null) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        addWindow()
        observeWindowOpen()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the screen size: keep the bubble on screen, on the nearest edge.
        placeAtEdge()
    }

    override fun onDestroy() {
        trackJob?.cancel()
        windowJob?.cancel()
        scope.cancel()
        removeWindow()
        ChatBubbleState.setActive(null)
        super.onDestroy()
    }

    // ---- target / red dot ----

    private fun setTarget(newTarget: ChatBubbleTarget) {
        target = newTarget
        ChatBubbleState.setActive(newTarget)
        bubble.showDot = false
        trackJob?.cancel()
        trackJob = scope.launch {
            var previous: GenerationState? = null
            NativeChatGenerationStates.flowFor(newTarget.chatId).collect { state ->
                val finished = state is GenerationState.Completed || state is GenerationState.Error
                val wasFinished = previous is GenerationState.Completed || previous is GenerationState.Error
                // The first emission is the current state, not a transition.
                if (previous != null && finished && !wasFinished && !ChatBubbleState.windowOpen.value) {
                    bubble.showDot = true
                }
                previous = state
            }
        }
    }

    private fun observeWindowOpen() {
        if (windowJob?.isActive == true) return
        windowJob = scope.launch {
            ChatBubbleState.windowOpen.collect { open ->
                if (open) bubble.showDot = false
                bubble.visibility = if (open) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openChat() {
        val current = target ?: return
        val intent = ChatBubbleActivity.intent(this, current.chatId, current.serverId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            // Background activity start refused; the notification action still works.
        }
    }

    private fun shutdown() {
        removeWindow()
        ChatBubbleState.setActive(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- window ----

    private fun createOverlayWindowContext(): Context {
        if (Build.VERSION.SDK_INT >= 30) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(this)
            }
        }
        return this
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = windowContext.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun addWindow() {
        if (windowAdded) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val (w, h) = screenSize()
        val size = bubble.sizePx
        lp.x = ChatBubbleGesture.snapX(w, size, w, margin())
        lp.y = ChatBubbleGesture.clampY(h / 3, size, h)
        params = lp
        try {
            windowManager.addView(bubble, lp)
            windowAdded = true
        } catch (_: RuntimeException) {
            // Permission revoked between the check and the add.
            windowAdded = false
            shutdown()
        }
    }

    private fun removeWindow() {
        if (!windowAdded) return
        windowAdded = false
        runCatching { windowManager.removeViewImmediate(bubble) }
    }

    private fun margin(): Int = (4 * resources.displayMetrics.density).toInt()

    private fun placeAtEdge() {
        val lp = params ?: return
        if (!windowAdded) return
        val (w, h) = screenSize()
        val size = bubble.sizePx
        lp.x = ChatBubbleGesture.snapX(ChatBubbleGesture.clampX(lp.x, size, w), size, w, margin())
        lp.y = ChatBubbleGesture.clampY(lp.y, size, h)
        runCatching { windowManager.updateViewLayout(bubble, lp) }
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    private fun configureTouch() {
        bubble.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.onDown(event.rawX, event.rawY, lp.x, lp.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesture.onMove(event.rawX, event.rawY)) {
                        val (w, h) = screenSize()
                        val size = bubble.sizePx
                        lp.x = ChatBubbleGesture.clampX(gesture.windowX, size, w)
                        lp.y = ChatBubbleGesture.clampY(gesture.windowY, size, h)
                        runCatching { windowManager.updateViewLayout(bubble, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when (gesture.onUp()) {
                        ChatBubbleGesture.Result.TAP -> openChat()
                        ChatBubbleGesture.Result.DRAG_END -> placeAtEdge()
                        ChatBubbleGesture.Result.NONE -> Unit
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (gesture.dragging) placeAtEdge()
                    gesture.cancel()
                    true
                }
                else -> false
            }
        }
    }

    // ---- foreground / notification ----

    private fun startForegroundSafely(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ForegroundStart.start(this, NOTIFICATION_ID, notification, ForegroundServiceTypes.SPECIAL_USE) is ForegroundStart.Result.Started
        } else {
            // specialUse does not exist below API 34, so the 2-argument form is the only valid call.
            startForeground(NOTIFICATION_ID, notification)
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.chat_bubble_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.chat_bubble_notif_channel_desc)
            }
        )
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val current = target
        val openIntent = if (current != null) {
            PendingIntent.getActivity(
                this, 1,
                ChatBubbleActivity.intent(this, current.chatId, current.serverId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                immutable
            )
        } else {
            null
        }
        val closeIntent = PendingIntent.getService(
            this, 2, Intent(this, ChatBubbleOverlayService::class.java).setAction(ACTION_CLOSE), immutable
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.chat_bubble_notif_title))
            .setContentText(getString(R.string.chat_bubble_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (openIntent != null) {
                    setContentIntent(openIntent)
                    addAction(0, getString(R.string.chat_bubble_notif_open), openIntent)
                }
            }
            .addAction(0, getString(R.string.chat_bubble_notif_close), closeIntent)
            .build()
    }

    companion object {
        private const val ACTION_CLOSE = "com.example.llamadroid.service.bubble.CLOSE"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "chat_bubble_overlay"
        private const val NOTIFICATION_ID = 490_310
        private const val ACCENT_COLOR = 0xFF65458F.toInt()

        /** Starts (or retargets) the bubble. Returns false without overlay permission or when the start was refused. */
        fun start(context: Context, chatId: Long, serverId: Long?): Boolean {
            if (chatId <= 0L || !Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, ChatBubbleOverlayService::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_SERVER_ID, serverId ?: -1L)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: RuntimeException) {
                if (!isForegroundServiceStartNotAllowed(error)) throw error
                false
            }
        }

        // Lint mistakes the explicit service Intent for a SAM instance.
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, ChatBubbleOverlayService::class.java))
        }
    }
}
