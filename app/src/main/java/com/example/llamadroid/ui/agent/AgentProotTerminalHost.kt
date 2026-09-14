package com.example.llamadroid.ui.agent

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/** Small app-owned bridge between the pinned Termux view and a durable PRoot PTY session. */
internal class AgentProotTerminalHost(
    context: Context,
    private val terminalSession: TerminalSession,
    private val onInputActivity: () -> Unit,
    private val onModifierStateChanged: (control: Boolean, alt: Boolean) -> Unit
) : TerminalViewClient {
    private val appContext = context.applicationContext
    private val minTextPx = 8f * context.resources.displayMetrics.scaledDensity
    private val maxTextPx = 32f * context.resources.displayMetrics.scaledDensity
    private var textSizePx = 14f * context.resources.displayMetrics.scaledDensity
    private var controlPending = false
    private var altPending = false

    val view: TerminalView = TerminalView(context, null).apply {
        setTerminalViewClient(this@AgentProotTerminalHost)
        setIsTerminalViewKeyLoggingEnabled(false)
        setTypeface(Typeface.MONOSPACE)
        setTextSize(textSizePx.roundToInt())
        isFocusable = true
        isFocusableInTouchMode = true
        attachSession(terminalSession)
    }

    fun toggleControl(): Boolean {
        controlPending = !controlPending
        onModifierStateChanged(controlPending, altPending)
        return controlPending
    }

    fun toggleAlt(): Boolean {
        altPending = !altPending
        onModifierStateChanged(controlPending, altPending)
        return altPending
    }

    fun showKeyboard() {
        view.requestFocus()
        val input = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        input?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun onScreenUpdated() {
        view.onScreenUpdated()
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

    override fun readControlKey(): Boolean = controlPending.also {
        if (it) {
            controlPending = false
            onModifierStateChanged(controlPending, altPending)
        }
    }
    override fun readAltKey(): Boolean = altPending.also {
        if (it) {
            altPending = false
            onModifierStateChanged(controlPending, altPending)
        }
    }
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        onInputActivity()
        return false
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

    private companion object {
        const val TAG = "AgentProotTerminalView"
        const val CURSOR_BLINK_MS = 600
        const val TEXT_SIZE_STEP_PX = 1f
        const val MAX_LOG_CHARS = 512
    }
}
