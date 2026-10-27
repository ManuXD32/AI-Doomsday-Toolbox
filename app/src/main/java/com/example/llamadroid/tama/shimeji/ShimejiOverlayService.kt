package com.example.llamadroid.tama.shimeji

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.llamadroid.MainActivity
import com.example.llamadroid.R
import com.example.llamadroid.service.ForegroundStart
import com.example.llamadroid.service.ForegroundServiceTypes
import com.example.llamadroid.service.isForegroundServiceStartNotAllowed
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaStudySessionSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Foreground service hosting the desktop-pet overlay: one TYPE_APPLICATION_OVERLAY window that
 * follows [ShimejiMotion], driven at about 30 Hz while the screen is on.
 */
class ShimejiOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rng = Random(System.nanoTime())

    // Overlay windows live in a display-wide window context (API 30+) so large screens report the whole display.
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var view: ShimejiView
    private lateinit var panel: ShimejiActionPanel
    private lateinit var classifier: ShimejiTapClassifier
    private var params: WindowManager.LayoutParams? = null
    private var windowAdded = false

    private var bridge: ShimejiPetBridge? = null
    private var snapshot: ShimejiPetSnapshot? = null
    private var sheet: ShimejiSpriteSheet? = null
    private var sheetKey: Pair<PetSpeciesLine, GrowthStage>? = null
    private var motion: ShimejiMotion? = null
    private var refreshJob: Job? = null

    // Screen geometry in px. Motion coordinates are relative to (originX, originY).
    private var fullW = 0
    private var fullH = 0
    private var originX = 0
    private var originY = 0
    private var boundsW = 0
    private var boundsH = 0
    private var spriteSizePx = 0
    private var geometry: ShimejiDisplayGeometry? = null
    /** Geometry the motion bounds currently reflect. */
    private var appliedGeometry: ShimejiDisplayGeometry? = null
    private var panelAnchorTop = Int.MIN_VALUE
    private var panelAnchorCenterX = Int.MIN_VALUE

    private var screenOn = true
    private var loopRunning = false
    private var lastTickMs = 0L
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var bubbleClear: Runnable? = null

    // Timer badge (sleep / study / pomodoro / work / relax), refreshed once per second while visible.
    private var timer: ShimejiTimerState? = null
    private var lastBadgeMs = 0L
    private var lastNotificationText: String? = null
    private val timerLabels by lazy {
        ShimejiTimerLabels(
            focus = TamaStudySessionSupport.localizedPhase(this, "FOCUS"),
            shortRest = TamaStudySessionSupport.localizedPhase(this, "SHORT_REST"),
            longRest = TamaStudySessionSupport.localizedPhase(this, "LONG_REST"),
            study = getString(R.string.tama_shimeji_timer_study),
            work = getString(R.string.tama_shimeji_timer_work),
            relax = getString(R.string.tama_shimeji_timer_relax),
            training = getString(R.string.tama_shimeji_timer_training)
        )
    }

    private var pendingPresenceMs = 0L
    private var presenceFlushing = false
    private var lastFlushAttemptMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!loopRunning) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastTickMs).coerceIn(1L, MAX_DT_MS)
            lastTickMs = now
            classifier.flush(now)?.let(::dispatchTap)
            // The care menu holds the pet still; once the menu closes (any way) the pet resumes.
            motion?.setHold(panel.isShowing)
            motion?.step(dt, rng)
            followPanelAnchor()
            if (timer != null && !dragging && now - lastBadgeMs >= BADGE_MS) refreshBadge()
            if (windowAdded && !isKeyguardLocked()) {
                pendingPresenceMs += dt
                if (pendingPresenceMs >= PRESENCE_FLUSH_MS && now - lastFlushAttemptMs >= PRESENCE_FLUSH_MS) flushPresence()
            }
            updateVisual()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    panel.dismiss()
                    stopLoop()
                    flushPresence()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (!checkOverlayPermission()) return
                    startLoop()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDisplayGeometryChanged()
        }
    }

    private val windowCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onDisplayGeometryChanged()

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    /** Display callbacks also fire for refresh-rate changes; only a real size/rotation/inset change reacts. */
    private fun onDisplayGeometryChanged() {
        computeGeometry()
        if (!applyBoundsIfChanged()) return
        panel.dismiss()
        updateVisual()
    }

    /** Pushes new bounds to the motion only when the geometry differs from the last applied one. */
    private fun applyBoundsIfChanged(): Boolean {
        val current = geometry ?: return false
        if (!ShimejiDisplayGeometry.hasChanged(appliedGeometry, current)) return false
        appliedGeometry = current
        motion?.updateBounds(currentBounds())
        return true
    }

    private fun createOverlayWindowContext(): Context {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }.getOrDefault(this)
            }
        }
        return this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        windowContext = createOverlayWindowContext()
        windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        classifier = ShimejiTapClassifier(touchSlopPx = 8f * resources.displayMetrics.density)
        view = ShimejiView(windowContext).apply { contentDescription = getString(R.string.tama_shimeji_content_desc) }
        panel = ShimejiActionPanel(windowContext, windowManager, panelListener)
        configureTouch()
        displayManager.registerDisplayListener(displayListener, handler)
        windowContext.registerComponentCallbacks(windowCallbacks)
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Shared by the notification Stop action and the care menu "Hide pet" button. */
    private fun disableAndStop() {
        ShimejiSettings.setEnabled(this, false)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disableAndStop()
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!checkOverlayPermission()) return START_NOT_STICKY
        if (refreshJob == null) refreshJob = scope.launch { runRefreshLoop() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onDisplayGeometryChanged()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        windowContext.unregisterComponentCallbacks(windowCallbacks)
        stopLoop()
        handler.removeCallbacksAndMessages(null)
        panel.dismiss()
        removeWindow()
        runCatching { unregisterReceiver(screenReceiver) }
        refreshJob = null
        scope.cancel()
        bridge?.close()
        bridge = null
        velocity?.recycle()
        velocity = null
        super.onDestroy()
    }

    // ---- foreground / permission ----

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
        // Start not allowed (background restriction) or missing permission: give up quietly.
        false
    }

    private fun buildNotification(text: String? = null): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.tama_shimeji_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.tama_shimeji_notif_channel_desc)
            }
        )
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ShimejiOverlayService::class.java).setAction(ACTION_STOP), immutable
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.tama_shimeji_notif_title))
            .setContentText(text ?: getString(R.string.tama_shimeji_notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.tama_shimeji_notif_open), openIntent)
            .addAction(0, getString(R.string.tama_shimeji_notif_stop), stopIntent)
            .build()
    }

    /** Stops the service (and disables the setting) when the overlay permission disappears. */
    private fun checkOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        ShimejiSettings.setEnabled(this, false)
        stopSelf()
        return false
    }

    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private fun isKeyguardLocked(): Boolean = keyguard.isKeyguardLocked

    // ---- pet state ----

    private suspend fun runRefreshLoop() {
        val created = withContext(Dispatchers.IO) { runCatching { ShimejiPetBridge(this@ShimejiOverlayService) }.getOrNull() }
        if (created == null) {
            stopSelf()
            return
        }
        bridge = created
        while (scope.isActive) {
            if (!checkOverlayPermission()) return
            val latest = runCatching { created.snapshot() }.getOrNull()
            applySnapshot(latest)
            delay(REFRESH_MS)
        }
    }

    private suspend fun applySnapshot(latest: ShimejiPetSnapshot?) {
        snapshot = latest
        timer = if (latest == null || latest.away) null else {
            ShimejiTimerFormat.timerState(
                isSleeping = latest.isSleeping,
                sleepStartTime = latest.sleepStartTime,
                activity = latest.currentActivity,
                activityStartTime = latest.activityStartTime,
                study = latest.study,
                energy = latest.energy,
                now = System.currentTimeMillis()
            )
        }
        updateNotification()
        if (latest == null || latest.away) {
            removeWindow()
            stopLoop()
            return
        }
        val key = latest.species to latest.stage
        if (key != sheetKey) {
            sheet = withContext(Dispatchers.IO) { ShimejiSpriteSheet.load(this@ShimejiOverlayService, latest.species, latest.stage) }
            sheetKey = key
        }
        val occupied = !latest.isEgg && ShimejiTimerFormat.isOccupied(latest.isSleeping, latest.currentActivity)
        val profile = ShimejiProfile(latest.isEgg, latest.isSleeping, ShimejiSettings.activity(this), occupied)
        val wantedPx = (ShimejiSettings.size(this).dp * resources.displayMetrics.density).roundToInt()
        val sizeChanged = wantedPx != spriteSizePx
        spriteSizePx = wantedPx
        view.setSpriteSizePx(wantedPx)
        computeGeometry()
        val existing = motion
        if (existing == null) {
            motion = ShimejiMotion(currentBounds(), profile, startX = rng.nextFloat() * currentBounds().maxX, startY = 0f)
            appliedGeometry = geometry
        } else {
            existing.updateProfile(profile)
            if (sizeChanged) existing.updateBounds(currentBounds())
            applyBoundsIfChanged()
        }
        addWindow()
        refreshBadge()
        if (screenOn) startLoop()
    }

    private fun updateNotification() {
        val t = timer
        val text = when {
            t == null -> null
            t.kind == ShimejiTimerKind.SLEEP -> getString(R.string.tama_shimeji_notif_sleeping, "${t.energyPercent}%")
            else -> ShimejiTimerFormat.badgeText(t, System.currentTimeMillis(), timerLabels)
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun refreshBadge() {
        lastBadgeMs = SystemClock.uptimeMillis()
        val t = timer
        val text = if (t == null || dragging || !windowAdded) null else {
            ShimejiTimerFormat.badgeText(t, System.currentTimeMillis(), timerLabels)
        }
        if (view.setBadge(text)) relayoutWindow()
    }

    // ---- geometry ----

    private fun currentBounds(): ShimejiBounds = ShimejiBounds(
        width = boundsW.coerceAtLeast(spriteSizePx).toFloat(),
        height = boundsH.coerceAtLeast(spriteSizePx).toFloat(),
        // Frames keep 32/256 transparent rows under the foot anchor (y=224): sink them so the feet touch the floor.
        floorInset = -spriteSizePx * FOOT_PADDING_FRACTION,
        spriteSize = spriteSizePx.toFloat()
    )

    /** Recomputes the usable screen area: full display minus system bars and cutouts. */
    @Suppress("DEPRECATION")
    private fun computeGeometry() {
        var left = 0
        val top: Int
        var right = 0
        var bottom = 0
        if (Build.VERSION.SDK_INT >= 30) {
            // Whole display (not the app/split/freeform window area), so the pet can use the entire tablet screen.
            val metrics = windowManager.maximumWindowMetrics
            fullW = metrics.bounds.width()
            fullH = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = insets.left
            top = insets.top
            right = insets.right
            bottom = insets.bottom
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            fullW = dm.widthPixels
            fullH = dm.heightPixels
            top = (24 * resources.displayMetrics.density).roundToInt()
            // Approximation: the navigation bar sits at the bottom in portrait and on the right in landscape.
            val navPx = (48 * resources.displayMetrics.density).roundToInt()
            if (fullH >= fullW) bottom = navPx else right = navPx
        }
        val rotation = runCatching { windowManager.defaultDisplay.rotation }.getOrDefault(0)
        geometry = ShimejiDisplayGeometry(fullW, fullH, left, top, right, bottom, rotation)
        originX = left
        originY = top
        boundsW = (fullW - left - right).coerceAtLeast(1)
        boundsH = (fullH - top - bottom).coerceAtLeast(1)
    }

    // ---- window ----

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
        params = lp
        positionWindow(lp)
        try {
            windowManager.addView(view, lp)
            windowAdded = true
        } catch (_: RuntimeException) {
            // Permission revoked between the check and the add; the next refresh stops the service.
            windowAdded = false
        }
    }

    private fun removeWindow() {
        if (!windowAdded) return
        windowAdded = false
        panel.dismiss()
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun positionWindow(lp: WindowManager.LayoutParams): Boolean {
        val m = motion ?: return false
        val nx = originX + m.x.roundToInt() - view.spriteOffsetX
        val ny = originY + m.y.roundToInt() - view.spriteOffsetY
        if (nx == lp.x && ny == lp.y) return false
        lp.x = nx
        lp.y = ny
        return true
    }

    private fun updateVisual() {
        val m = motion ?: return
        val art = sheet?.clip(m.clip)
        val index = if (art == null) 0 else m.currentFrameIndex(art.frameCount, art.frameMs.toLong())
        view.setFrame(art, index, !m.facingRight)
        val lp = params ?: return
        if (windowAdded && positionWindow(lp)) {
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    private fun startLoop() {
        if (loopRunning || !windowAdded) return
        loopRunning = true
        lastTickMs = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    private fun stopLoop() {
        loopRunning = false
        motion?.setHold(false)
        handler.removeCallbacks(tick)
        classifier.reset()
    }

    // ---- bubble ----

    private fun showBubble(text: String) {
        bubbleClear?.let { handler.removeCallbacks(it) }
        applyBubble(text)
        val clear = Runnable { applyBubble(null) }
        bubbleClear = clear
        handler.postDelayed(clear, BUBBLE_MS)
    }

    private fun applyBubble(text: String?) {
        view.setBubble(text)
        updateVisual()
        relayoutWindow()
    }

    /** Size changed: force a layout pass at the (possibly new) offset. */
    private fun relayoutWindow() {
        val lp = params
        if (lp != null && windowAdded) {
            positionWindow(lp)
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    private fun configureTouch() {
        view.setOnTouchListener { _, event ->
            val m = motion
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (m == null || !view.hitsSprite(event.x, event.y)) return@setOnTouchListener false
                    panel.dismiss()
                    classifier.onDown(event.rawX, event.rawY, event.eventTime)?.let(::dispatchTap)
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (m == null) return@setOnTouchListener false
                    velocity?.addMovement(event)
                    if (classifier.onMove(event.rawX, event.rawY, event.eventTime)) {
                        if (!dragging) {
                            dragging = true
                            if (view.hasBadge) refreshBadge()
                            m.startDrag(event.rawX - originX, event.rawY - originY)
                        }
                        m.dragTo(event.rawX - originX, event.rawY - originY)
                        updateVisual()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    velocity?.addMovement(event)
                    val tap = classifier.onUp(event.rawX, event.rawY, event.eventTime)
                    if (dragging && m != null) {
                        velocity?.computeCurrentVelocity(1000)
                        m.release(velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f)
                    }
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) refreshBadge()
                    tap?.let(::dispatchTap)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    classifier.reset()
                    if (dragging) motion?.release(0f, 0f)
                    val wasDragging = dragging
                    dragging = false
                    if (wasDragging) refreshBadge()
                    true
                }
                else -> false
            }
        }
    }

    private fun dispatchTap(event: ShimejiTapEvent) {
        when (event) {
            ShimejiTapEvent.SINGLE ->
                // A sleeping pet is not woken by taps (only by the panel); it just murmurs.
                if (snapshot?.isSleeping == true) showBubble(getString(R.string.tama_shimeji_sleep_bubble))
                else motion?.playOneShot(ShimejiMode.REACT, REACT_MS)
            ShimejiTapEvent.DOUBLE -> showPanel()
            ShimejiTapEvent.TRIPLE -> openChat()
        }
    }

    private fun showPanel() {
        if (!windowAdded) return
        val top = originY + (motion?.y?.roundToInt() ?: 0)
        val centerX = originX + (motion?.x?.roundToInt() ?: 0) + spriteSizePx / 2
        panelAnchorTop = top
        panelAnchorCenterX = centerX
        motion?.setHold(true)
        panel.show(
            anchorCenterX = centerX,
            anchorTop = top,
            anchorBottom = top + spriteSizePx,
            screenW = fullW,
            screenH = fullH,
            state = ShimejiActionPanel.State(
                foods = snapshot?.foods.orEmpty(),
                isSleeping = snapshot?.isSleeping == true,
                isEgg = snapshot?.isEgg == true,
                hasActivity = snapshot?.currentActivity?.let { it != ActivityType.NONE } == true
            )
        )
    }

    /** Re-anchors the open menu to the pet once it has settled (for example after a held fall landed). */
    private fun followPanelAnchor() {
        val m = motion ?: return
        if (!panel.isShowing || !m.isGrounded || m.mode == ShimejiMode.DRAGGED) return
        val top = originY + m.y.roundToInt()
        val centerX = originX + m.x.roundToInt() + spriteSizePx / 2
        if (top == panelAnchorTop && centerX == panelAnchorCenterX) return
        panelAnchorTop = top
        panelAnchorCenterX = centerX
        panel.reposition(centerX, top, top + spriteSizePx, fullW, fullH)
    }

    private fun openChat() {
        // Resolved by name so this service does not depend on the chat activity at compile time.
        val intent = Intent()
            .setClassName(this, CHAT_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            // Overlay services may be blocked from background activity starts; nothing else to do.
        }
    }

    // ---- care actions ----

    private val panelListener = object : ShimejiActionPanel.Listener {
        override fun onFeedPlain() = runAction(ShimejiMode.EAT) { it.feed() }
        override fun onFeedFood(food: ShimejiFood) = runAction(ShimejiMode.EAT) { it.feedWithFood(food) }
        override fun onBath() = runAction(ShimejiMode.BATH) { it.clean() }
        override fun onSleep() = runAction(null) { it.goToBed() }
        override fun onWake() = runAction(null) { it.wakeUp() }
        override fun onStudy() = runAction(null) { it.startStudy() }
        override fun onPomodoro() = runAction(null) { it.startPomodoro() }
        override fun onRelax() = runAction(null) { it.startRelax() }
        override fun onStopActivity() = runAction(null) { it.stopActivity() }
        override fun onHidePet() = disableAndStop()
    }

    private fun runAction(mode: ShimejiMode?, block: suspend (ShimejiPetBridge) -> TamaGameEngine.ActionResult) {
        val b = bridge ?: return
        scope.launch {
            val result = try {
                block(b)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (result.success) {
                if (mode != null) motion?.playOneShot(mode, ACTION_ANIM_MS)
                applySnapshot(runCatching { b.snapshot() }.getOrNull())
            } else {
                showBubble(result.message)
            }
        }
    }

    // ---- presence ----

    private fun flushPresence() {
        val b = bridge ?: return
        if (presenceFlushing || pendingPresenceMs <= 0L) return
        presenceFlushing = true
        lastFlushAttemptMs = SystemClock.uptimeMillis()
        val ms = pendingPresenceMs
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val day = (now + TimeZone.getDefault().getOffset(now)) / DAY_MS
                val result = ShimejiPresence.accrue(ms, true, day, ShimejiSettings.loadPresence(this@ShimejiOverlayService))
                val applied = result.happinessDelta <= 0 || runCatching { b.applyPresence(result.happinessDelta) }.getOrDefault(false)
                if (applied) {
                    ShimejiSettings.savePresence(this@ShimejiOverlayService, result.newState)
                    pendingPresenceMs = (pendingPresenceMs - ms).coerceAtLeast(0L)
                }
            } finally {
                presenceFlushing = false
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "com.example.llamadroid.tama.shimeji.STOP"
        private const val CHANNEL_ID = "tama_shimeji_overlay"
        private const val NOTIFICATION_ID = 490_300
        private const val CHAT_ACTIVITY = "com.example.llamadroid.tama.shimeji.ShimejiChatActivity"
        private const val TICK_MS = 33L
        private const val MAX_DT_MS = 100L
        private const val REFRESH_MS = 5_000L
        private const val FOOT_PADDING_FRACTION = 32f / 256f
        private const val REACT_MS = 900L
        private const val ACTION_ANIM_MS = 2_000L
        private const val BUBBLE_MS = 3_500L
        private const val BADGE_MS = 1_000L
        private const val PRESENCE_FLUSH_MS = 60_000L
        private const val DAY_MS = 86_400_000L

        /** Starts the overlay. Returns false when the overlay permission is missing or the start was refused. */
        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, ShimejiOverlayService::class.java)
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
            context.stopService(Intent(context, ShimejiOverlayService::class.java))
        }
    }
}
