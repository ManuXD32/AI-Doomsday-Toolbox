package com.example.llamadroid.ui.agent

import android.content.Context
import android.graphics.Typeface
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.example.llamadroid.harness.HarnessTerminalModifier
import com.example.llamadroid.harness.HarnessTerminalModifierMode
import com.example.llamadroid.harness.HarnessTerminalModifierState
import com.example.llamadroid.harness.composeHarnessTermuxCodePoint
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/** Small app-owned bridge between the pinned Termux view and a durable PRoot PTY session. */
/**
 * Termux terminal view bridge shared by the project terminal and Harness recovery terminal.
 *
 * The host only owns the Android view and input modifiers. The caller still owns the
 * [TerminalSession] lifecycle, so a standalone recovery shell does not need to start the agent
 * or the Harness runtime.
 */
class AgentProotTerminalHost(
    context: Context,
    private val terminalSession: TerminalSession,
    private val onInputActivity: () -> Unit,
    private val onModifierStateChanged: (control: Boolean, alt: Boolean) -> Unit,
    private val onFullModifierStateChanged: (HarnessTerminalModifierState) -> Unit = {},
) : TerminalViewClient {
    private val appContext = context.applicationContext
    private val minTextPx = 8f * context.resources.displayMetrics.scaledDensity
    private val maxTextPx = 32f * context.resources.displayMetrics.scaledDensity
    private var textSizePx = 14f * context.resources.displayMetrics.scaledDensity
    @Volatile
    private var modifierState = HarnessTerminalModifierState()
    private var altModifierForCodePoint = false
    private var shiftModifierForCodePoint = false

    val currentModifierState: HarnessTerminalModifierState
        get() = modifierState

    val view: TerminalView = TerminalView(context, null).apply {
        setTerminalViewClient(this@AgentProotTerminalHost)
        setIsTerminalViewKeyLoggingEnabled(false)
        // TerminalView.setTypeface()/setTextSize() assume that Termux has already installed a
        // renderer.  That assumption is false for a view created from a recovery destination
        // and was the source of the v0.977 NPE.  Install the renderer explicitly, as the
        // Harness transport surface does, before attaching the live session.
        mRenderer = TerminalRenderer(textSizePx.roundToInt(), Typeface.MONOSPACE)
        isFocusable = true
        isFocusableInTouchMode = true
        attachSession(terminalSession)
    }

    fun toggleControl(): Boolean {
        val enabled = modifierState.ctrl == HarnessTerminalModifierMode.OFF
        setModifierState(
            modifierState.copy(
                ctrl = if (enabled) HarnessTerminalModifierMode.STICKY
                else HarnessTerminalModifierMode.OFF,
            )
        )
        return enabled
    }

    fun toggleAlt(): Boolean {
        val enabled = modifierState.alt == HarnessTerminalModifierMode.OFF
        setModifierState(
            modifierState.copy(
                alt = if (enabled) HarnessTerminalModifierMode.STICKY
                else HarnessTerminalModifierMode.OFF,
            )
        )
        return enabled
    }

    /** Cycles a virtual modifier using the same OFF/STICKY/LOCKED contract as the project bar. */
    fun cycleModifier(modifier: HarnessTerminalModifier): HarnessTerminalModifierMode {
        val current = when (modifier) {
            HarnessTerminalModifier.CTRL -> modifierState.ctrl
            HarnessTerminalModifier.ALT -> modifierState.alt
            HarnessTerminalModifier.SHIFT -> modifierState.shift
        }
        val next = when (current) {
            HarnessTerminalModifierMode.OFF -> HarnessTerminalModifierMode.STICKY
            HarnessTerminalModifierMode.STICKY -> HarnessTerminalModifierMode.LOCKED
            HarnessTerminalModifierMode.LOCKED -> HarnessTerminalModifierMode.OFF
        }
        setModifierState(
            when (modifier) {
                HarnessTerminalModifier.CTRL -> modifierState.copy(ctrl = next)
                HarnessTerminalModifier.ALT -> modifierState.copy(alt = next)
                HarnessTerminalModifier.SHIFT -> modifierState.copy(shift = next)
            }
        )
        return next
    }

    fun setModifierState(state: HarnessTerminalModifierState) {
        if (modifierState == state) return
        modifierState = state
        onModifierStateChanged(
            state.ctrl != HarnessTerminalModifierMode.OFF,
            state.alt != HarnessTerminalModifierMode.OFF,
        )
        onFullModifierStateChanged(state)
    }

    /** Clears one-shot modifiers after a virtual key-bar sequence has been sent. */
    fun consumeStickyModifiers() {
        var next = modifierState
        if (next.ctrl == HarnessTerminalModifierMode.STICKY) {
            next = next.copy(ctrl = HarnessTerminalModifierMode.OFF)
        }
        if (next.alt == HarnessTerminalModifierMode.STICKY) {
            next = next.copy(alt = HarnessTerminalModifierMode.OFF)
        }
        if (next.shift == HarnessTerminalModifierMode.STICKY) {
            next = next.copy(shift = HarnessTerminalModifierMode.OFF)
        }
        setModifierState(next)
    }

    fun showKeyboard() {
        view.requestFocus()
        val input = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun onScreenUpdated() {
        runOnViewThread {
            view.onScreenUpdated()
            view.invalidate()
        }
    }

    /**
     * Refreshes a view after Compose attaches it to a new AndroidView node.
     *
     * A recovery retry creates a new PTY while the route itself stays alive. The Android view
     * can therefore be attached with the same bounds as the previous session and skip a size
     * callback. Requesting layout here lets Termux recompute the PTY grid, while the explicit
     * redraw makes output received before attachment visible immediately.
     */
    fun refreshAfterAttachment() {
        runOnViewThread {
            view.requestLayout()
            view.onScreenUpdated()
            view.invalidate()
        }
    }

    fun setVisible(visible: Boolean) {
        view.setTerminalCursorBlinkerRate(CURSOR_BLINK_MS)
        view.setTerminalCursorBlinkerState(visible, true)
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val step = if (scale > 1f) TEXT_SIZE_STEP_PX else -TEXT_SIZE_STEP_PX
            textSizePx = (textSizePx + step * view.resources.displayMetrics.scaledDensity)
                .coerceIn(minTextPx, maxTextPx)
            view.setTextSize(textSizePx.roundToInt())
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(event: MotionEvent) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = view.hasFocus()
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
        onInputActivity()
        return !session.isRunning
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean {
        val active = modifierState.ctrl != HarnessTerminalModifierMode.OFF
        if (modifierState.ctrl == HarnessTerminalModifierMode.STICKY) {
            setModifierState(modifierState.copy(ctrl = HarnessTerminalModifierMode.OFF))
        }
        return active
    }

    override fun readAltKey(): Boolean {
        val active = modifierState.alt != HarnessTerminalModifierMode.OFF
        altModifierForCodePoint = active
        if (modifierState.alt == HarnessTerminalModifierMode.STICKY) {
            setModifierState(modifierState.copy(alt = HarnessTerminalModifierMode.OFF))
        }
        return active
    }

    override fun readShiftKey(): Boolean {
        val active = modifierState.shift != HarnessTerminalModifierMode.OFF
        // Termux asks for Shift before delivering an IME code point, then clears a STICKY
        // modifier here. Keep the sampled value for onCodePoint(), which has no shift argument.
        shiftModifierForCodePoint = active
        if (modifierState.shift == HarnessTerminalModifierMode.STICKY) {
            setModifierState(modifierState.copy(shift = HarnessTerminalModifierMode.OFF))
        }
        return active
    }
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        onInputActivity()
        val altDown = altModifierForCodePoint.also { altModifierForCodePoint = false }
        val shiftDown = shiftModifierForCodePoint ||
            modifierState.shift != HarnessTerminalModifierMode.OFF
        shiftModifierForCodePoint = false
        if (!ctrlDown && !altDown && !shiftDown) return false
        val shifted = if (shiftDown) Character.toUpperCase(codePoint) else codePoint
        if (modifierState.shift == HarnessTerminalModifierMode.STICKY) {
            setModifierState(modifierState.copy(shift = HarnessTerminalModifierMode.OFF))
        }
        val sequence = composeHarnessTermuxCodePoint(shifted, ctrlDown, altDown)
        if (sequence.isNotEmpty()) session.write(sequence)
        return true
    }

    override fun onEmulatorSet() {
        setVisible(true)
    }

    override fun logError(tag: String, message: String) {
        Log.e(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}")
    }
    override fun logWarn(tag: String, message: String) {
        Log.w(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}")
    }
    override fun logInfo(tag: String, message: String) {
        Log.i(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}")
    }
    override fun logDebug(tag: String, message: String) {
        Log.d(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}")
    }
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) {
        Log.e(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}", error)
    }
    override fun logStackTrace(tag: String, error: Exception) {
        Log.e(TAG, tag, error)
    }

    private fun runOnViewThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            view.post(block)
        }
    }

    private companion object {
        const val TAG = "AgentProotTerminalView"
        const val CURSOR_BLINK_MS = 600
        const val TEXT_SIZE_STEP_PX = 1f
        const val MAX_LOG_CHARS = 512
    }
}
