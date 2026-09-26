package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Field
import kotlin.math.roundToInt

internal const val HARNESS_TERMINAL_DEFAULT_FONT_SIZE_SP = 13f

internal sealed interface HarnessTermuxTransportViewUpdate {
    data class Snapshot(val screen: String, val columns: Int, val rows: Int) : HarnessTermuxTransportViewUpdate
    data class Output(val data: String) : HarnessTermuxTransportViewUpdate
    data class Resize(val columns: Int, val rows: Int) : HarnessTermuxTransportViewUpdate
    data object Clear : HarnessTermuxTransportViewUpdate
}

/**
 * Transport callbacks for a DSH terminal stream.
 *
 * The adapter owns no process and never calls [TerminalSession.initializeEmulator]. The
 * repository remains the only owner of terminal.create/follow/write/resize; this class only
 * parses the bytes that arrive from follow and returns keyboard bytes to the repository.
 */
class HarnessTermuxTransportAdapter(
    val initialScreen: String = "",
    val initialColumns: Int = DEFAULT_COLUMNS,
    val initialRows: Int = DEFAULT_ROWS,
    private val onWrite: suspend (String) -> Unit,
    private val onResize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    private val onInputActivity: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
    onResnapshotRequested: () -> Unit = {},
) {
    private val lock = Any()
    private val pending = ArrayDeque<HarnessTermuxTransportViewUpdate>()
    private var needsFreshSnapshot = false
    private var attached: HarnessTermuxTransportView? = null
    private var resnapshotRequester = onResnapshotRequested
    private val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputQueue = Channel<String>(MAX_PENDING_INPUTS)
    private val _imeVisible = MutableStateFlow(false)
    @Volatile private var closed = false

    /** Platform IME visibility observed by the embedded TerminalView on edge-to-edge windows. */
    internal val imeVisible: StateFlow<Boolean> = _imeVisible.asStateFlow()

    init {
        inputScope.launch {
            for (data in inputQueue) {
                try {
                    onWrite(data)
                } catch (cancelled: CancellationException) {
                    if (!isActive) throw cancelled
                    reportFailure(cancelled)
                } catch (failure: Throwable) {
                    reportFailure(failure)
                }
            }
        }
    }

    /** Feed a `terminal.follow` snapshot into the native emulator. */
    fun snapshot(screen: String, columns: Int = initialColumns, rows: Int = initialRows) {
        dispatch(HarnessTermuxTransportViewUpdate.Snapshot(screen, columns, rows), replacePending = true)
    }

    /** Feed raw `terminal.follow` output, including ANSI and alternate-screen sequences. */
    fun append(data: String) {
        if (data.isNotEmpty()) dispatch(HarnessTermuxTransportViewUpdate.Output(data))
    }

    /** Apply an authoritative remote resize without echoing it back to DSH. */
    fun resize(columns: Int, rows: Int) {
        dispatch(HarnessTermuxTransportViewUpdate.Resize(columns, rows))
    }

    /** Clear the visible emulator and compatibility transcript together. */
    fun clear() {
        dispatch(HarnessTermuxTransportViewUpdate.Clear)
    }

    /** Request focus and the system IME for the terminal surface. */
    fun requestFocus() {
        synchronized(lock) { attached }?.requestTerminalFocus()
    }

    /**
     * Install the repository callback after construction, once the route knows this adapter's
     * terminal identity. It is called when a view attaches after an output queue overflow.
     */
    internal fun setOnResnapshotRequested(callback: () -> Unit) {
        synchronized(lock) { resnapshotRequester = callback }
    }

    private fun dispatch(update: HarnessTermuxTransportViewUpdate, replacePending: Boolean = false) {
        val view = synchronized(lock) {
            if (replacePending) needsFreshSnapshot = false
            attached ?: run {
                if (replacePending) {
                    pending.clear()
                    needsFreshSnapshot = false
                }
                // Once a prefix has been dropped, no later output can make the parser state
                // trustworthy. Wait for the next authoritative snapshot instead of replaying
                // a suffix that may begin inside an ANSI sequence.
                if (needsFreshSnapshot) return
                if (pending.size >= MAX_PENDING_UPDATES) {
                    pending.clear()
                    needsFreshSnapshot = true
                    return
                }
                pending.addLast(update)
                return
            }
        }
        view.post { view.apply(update) }
    }

    private fun attach(view: HarnessTermuxTransportView) {
        val (updates, requestSnapshot) = synchronized(lock) {
            attached = view
            if (needsFreshSnapshot) {
                pending.clear()
                emptyList<HarnessTermuxTransportViewUpdate>() to true
            } else {
                pending.toList().also { pending.clear() } to false
            }
        }
        updates.forEach(view::apply)
        if (requestSnapshot) {
            val callback = synchronized(lock) { resnapshotRequester }
            runCatching { callback() }.onFailure(::reportFailure)
        }
    }

    private fun detach(view: HarnessTermuxTransportView) {
        synchronized(lock) {
            if (attached === view) attached = null
        }
    }

    internal fun createView(
        context: Context,
        initialFontSizeSp: Float = HARNESS_TERMINAL_DEFAULT_FONT_SIZE_SP,
    ): HarnessTermuxTransportView = HarnessTermuxTransportView(context, this, initialFontSizeSp)

    internal fun attachView(view: HarnessTermuxTransportView) = attach(view)
    internal fun detachView(view: HarnessTermuxTransportView) = detach(view)

    internal fun send(data: String) {
        if (data.isEmpty()) return
        if (closed) {
            reportFailure(IllegalStateException("TERMINAL_TRANSPORT_CLOSED"))
            return
        }
        onInputActivity()
        if (inputQueue.trySend(data).isFailure) {
            reportFailure(IllegalStateException("TERMINAL_INPUT_QUEUE_EXHAUSTED"))
        }
    }

    /** Stop the input worker when the Compose surface leaves the composition. */
    internal fun close() {
        if (closed) return
        closed = true
        _imeVisible.value = false
        inputQueue.close()
        inputScope.cancel()
        synchronized(lock) {
            pending.clear()
            needsFreshSnapshot = false
            attached = null
            resnapshotRequester = {}
        }
    }

    internal fun resizedByView(columns: Int, rows: Int) {
        onResize(columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS), rows.coerceIn(MIN_ROWS, MAX_ROWS))
    }

    internal fun setImeVisible(visible: Boolean) {
        _imeVisible.value = visible
    }

    private fun reportFailure(failure: Throwable) {
        runCatching { onFailure(failure) }
    }

    private companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 500
        const val MIN_ROWS = 1
        const val MAX_ROWS = 200
        const val MAX_PENDING_UPDATES = 128
        const val MAX_PENDING_INPUTS = 64
    }
}

/**
 * Compose host around Termux TerminalView. The synthetic session only supplies the parser's
 * output/client bridge; it is deliberately never initialized as a subprocess.
 */
@Composable
internal fun HarnessTermuxTransportSurface(
    transport: HarnessTermuxTransportAdapter,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = HARNESS_TERMINAL_DEFAULT_FONT_SIZE_SP,
    modifierState: HarnessTerminalModifierState = HarnessTerminalModifierState(),
    onStickyModifierConsumed: (HarnessTerminalModifier) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(transport, context) { transport.createView(context, fontSizeSp) }
    DisposableEffect(transport, view) {
        transport.attachView(view)
        onDispose {
            transport.detachView(view)
            transport.close()
        }
    }
    key(view) {
        AndroidView(
            factory = { view },
            modifier = modifier,
            update = {
                it.isFocusable = true
                it.setFontSizeSp(fontSizeSp)
                it.setVirtualModifierState(modifierState, onStickyModifierConsumed)
            },
        )
    }
}

// This view is created only by the Compose transport adapter, never inflated from XML.
@SuppressLint("ViewConstructor")
internal class HarnessTermuxTransportView(
    context: Context,
    private val transport: HarnessTermuxTransportAdapter,
    initialFontSizeSp: Float,
) : FrameLayout(context) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val sessionClient = SessionClient()
    private val terminalOutput = Output()
    private val session = TerminalSession(
        "harness-dsh",
        "",
        emptyArray(),
        emptyArray(),
        TRANSCRIPT_ROWS,
        sessionClient,
    )
    private var emulator = newEmulator(
        transport.initialColumns,
        transport.initialRows,
    )
    private val terminalView = TerminalView(context, null)
    private var textSizePx = fontSizeToPx(initialFontSizeSp, resources.displayMetrics)
    private var lastReportedColumns = 0
    private var lastReportedRows = 0
    private var modifierState = HarnessTerminalModifierState()
    private var onStickyModifierConsumed: (HarnessTerminalModifier) -> Unit = {}
    private var altModifierForCodePoint = false
    private var shiftModifierForCodePoint = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFF101114.toInt())
        terminalView.setTerminalViewClient(ViewClient())
        terminalView.setIsTerminalViewKeyLoggingEnabled(false)
        terminalView.mRenderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.mTermSession = session
        terminalView.mEmulator = emulator
        setSessionEmulator(emulator)
        setSessionFileDescriptor(-1)
        addView(
            terminalView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        if (transport.initialScreen.isNotEmpty()) {
            val bytes = transport.initialScreen.toByteArray(Charsets.UTF_8)
            emulator.append(bytes, bytes.size)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { androidx.core.view.ViewCompat.requestApplyInsets(this) }
        // Give hardware keys and the first IME request a deterministic target without opening
        // the keyboard merely because the terminal tab was composed.
        post { if (!terminalView.hasFocus()) terminalView.requestFocus() }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        transport.setImeVisible(
            WindowInsetsCompat.toWindowInsetsCompat(insets, this)
                .isVisible(WindowInsetsCompat.Type.ime())
        )
        return super.onApplyWindowInsets(insets)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // TerminalView's final onSizeChanged calls TerminalSession.updateSize(), which in turn
        // invokes JNI.setPtyWindowSize. This session has no local PTY; the parent computes the
        // grid and sends the authoritative resize through the DSH transport instead.
        val previousSession = terminalView.mTermSession
        terminalView.mTermSession = null
        try {
            super.onLayout(changed, left, top, right, bottom)
        } finally {
            terminalView.mTermSession = previousSession ?: session
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateGridForBounds(width, height)
    }

    internal fun setFontSizeSp(fontSizeSp: Float) {
        val nextTextSizePx = fontSizeToPx(fontSizeSp, resources.displayMetrics)
        if (nextTextSizePx == textSizePx) return
        textSizePx = nextTextSizePx
        // TerminalView.setTextSize() calls updateSize(), which would invoke the synthetic
        // session's PTY/JNI resize path. Replacing the renderer directly keeps this surface
        // process-free; updateGridForBounds() owns the emulator and DSH resize instead.
        terminalView.mRenderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        updateGridForBounds(width, height)
        terminalView.onScreenUpdated()
        terminalView.invalidate()
    }

    internal fun setVirtualModifierState(
        state: HarnessTerminalModifierState,
        onStickyModifierConsumed: (HarnessTerminalModifier) -> Unit,
    ) {
        modifierState = state
        this.onStickyModifierConsumed = onStickyModifierConsumed
    }

    private fun updateGridForBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val renderer = terminalView.mRenderer ?: return
        val columns = (width / renderer.fontWidth).roundToInt().coerceIn(2, 500)
        val rows = (height / renderer.getFontLineSpacing().toFloat()).roundToInt().coerceIn(1, 200)
        if (columns == lastReportedColumns && rows == lastReportedRows) return
        lastReportedColumns = columns
        lastReportedRows = rows
        if (emulator.mColumns != columns || emulator.mRows != rows) {
            emulator.resize(columns, rows)
            terminalView.onScreenUpdated()
        }
        transport.resizedByView(columns, rows)
    }

    internal fun apply(update: HarnessTermuxTransportViewUpdate) {
        when (update) {
            is HarnessTermuxTransportViewUpdate.Snapshot -> applySnapshot(update.screen, update.columns, update.rows)
            is HarnessTermuxTransportViewUpdate.Output -> append(update.data)
            is HarnessTermuxTransportViewUpdate.Resize -> resize(update.columns, update.rows)
            HarnessTermuxTransportViewUpdate.Clear -> {
                emulator.reset()
                emulator.screen.clearTranscript()
                terminalView.onScreenUpdated()
            }
        }
    }

    private fun append(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        terminalView.onScreenUpdated()
    }

    private fun applySnapshot(screen: String, columns: Int, rows: Int) {
        emulator = newEmulator(columns, rows)
        setSessionEmulator(emulator)
        terminalView.mEmulator = emulator
        append(screen)
    }

    private fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceIn(2, 500)
        val safeRows = rows.coerceIn(1, 200)
        if (emulator.mColumns != safeColumns || emulator.mRows != safeRows) {
            emulator.resize(safeColumns, safeRows)
            terminalView.onScreenUpdated()
        }
    }

    internal fun requestTerminalFocus() {
        terminalView.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun newEmulator(columns: Int, rows: Int): TerminalEmulator = TerminalEmulator(
        terminalOutput,
        columns.coerceIn(2, 500),
        rows.coerceIn(1, 200),
        TRANSCRIPT_ROWS,
        sessionClient,
    )

    private fun consumeStickyModifier(modifier: HarnessTerminalModifier) {
        val state = modifierState
        modifierState = when (modifier) {
            HarnessTerminalModifier.CTRL -> state.copy(
                ctrl = state.ctrl.takeUnless { it == HarnessTerminalModifierMode.STICKY }
                    ?: HarnessTerminalModifierMode.OFF,
            )
            HarnessTerminalModifier.ALT -> state.copy(
                alt = state.alt.takeUnless { it == HarnessTerminalModifierMode.STICKY }
                    ?: HarnessTerminalModifierMode.OFF,
            )
            HarnessTerminalModifier.SHIFT -> state.copy(
                shift = state.shift.takeUnless { it == HarnessTerminalModifierMode.STICKY }
                    ?: HarnessTerminalModifierMode.OFF,
            )
        }
        if (when (modifier) {
                HarnessTerminalModifier.CTRL -> state.ctrl
                HarnessTerminalModifier.ALT -> state.alt
                HarnessTerminalModifier.SHIFT -> state.shift
            } == HarnessTerminalModifierMode.STICKY) {
            onStickyModifierConsumed(modifier)
        }
    }

    private fun consumeHardwareStickyModifiers(state: HarnessTerminalModifierState) {
        if (state.ctrl == HarnessTerminalModifierMode.STICKY) {
            consumeStickyModifier(HarnessTerminalModifier.CTRL)
        }
        if (state.alt == HarnessTerminalModifierMode.STICKY) {
            consumeStickyModifier(HarnessTerminalModifier.ALT)
        }
        if (state.shift == HarnessTerminalModifierMode.STICKY) {
            consumeStickyModifier(HarnessTerminalModifier.SHIFT)
        }
    }

    private fun setSessionEmulator(value: TerminalEmulator) {
        runCatching {
            SESSION_EMULATOR_FIELD.isAccessible = true
            SESSION_EMULATOR_FIELD.set(session, value)
        }.getOrElse { throw IllegalStateException("Unable to attach the Termux emulator", it) }
    }

    private fun setSessionFileDescriptor(value: Int) {
        runCatching {
            SESSION_FD_FIELD.isAccessible = true
            SESSION_FD_FIELD.setInt(session, value)
        }.getOrElse { throw IllegalStateException("Unable to disable the synthetic Termux PTY", it) }
    }

    private inner class Output : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count > 0) transport.send(String(data, offset, count, Charsets.UTF_8))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private inner class SessionClient : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession?) = terminalView.onScreenUpdated()
        override fun onTitleChanged(session: TerminalSession?) = Unit
        override fun onSessionFinished(session: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
            text?.let { clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", it)) }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            // The selection toolbar reaches this callback instead of TerminalView's gesture
            // path; keep the same normalization and bracketed-paste handling through Output.
            if (text.isNotEmpty()) emulator.paste(text)
        }
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) = terminalView.onScreenUpdated()
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
        override fun logStackTrace(tag: String?, error: Exception?) = Unit
    }

    private inner class ViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(event: MotionEvent) = requestTerminalFocus()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
        override fun isTerminalViewSelected(): Boolean = terminalView.hasFocus()
        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
            if (event.isSystem) return keyCode != KeyEvent.KEYCODE_BACK
            val virtual = modifierState
            val sequence = harnessTermuxKeySequence(
                keyCode = keyCode,
                event = event,
                applicationCursorKeys = emulator.isCursorKeysApplicationMode,
                virtualCtrl = virtual.ctrl != HarnessTerminalModifierMode.OFF,
                virtualAlt = virtual.alt != HarnessTerminalModifierMode.OFF,
                virtualShift = virtual.shift != HarnessTerminalModifierMode.OFF,
            )
            if (sequence != null) {
                consumeHardwareStickyModifiers(virtual)
                transport.send(sequence)
            }
            // Returning true for unknown non-system keys is intentional: the synthetic session
            // has no process queue, so TerminalView's default session.write path would drop the
            // event silently. System navigation keys remain available to the surrounding route.
            return true
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            !(event.isSystem && keyCode == KeyEvent.KEYCODE_BACK)
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean {
            val active = modifierState.ctrl != HarnessTerminalModifierMode.OFF
            consumeStickyModifier(HarnessTerminalModifier.CTRL)
            return active
        }

        override fun readAltKey(): Boolean {
            val active = modifierState.alt != HarnessTerminalModifierMode.OFF
            altModifierForCodePoint = active
            consumeStickyModifier(HarnessTerminalModifier.ALT)
            return active
        }

        override fun readShiftKey(): Boolean {
            val active = when (modifierState.shift) {
                HarnessTerminalModifierMode.OFF -> false
                HarnessTerminalModifierMode.STICKY,
                HarnessTerminalModifierMode.LOCKED -> true
            }
            // Termux asks for Shift before delivering an IME code point, then clears a STICKY
            // modifier here. Keep the sampled value for onCodePoint(), which has no shift arg.
            shiftModifierForCodePoint = active
            consumeStickyModifier(HarnessTerminalModifier.SHIFT)
            return active
        }
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            val altDown = altModifierForCodePoint.also { altModifierForCodePoint = false }
            // Termux inputCodePoint reads Ctrl/Alt, but not Shift, for IME commits.
            val shiftDown = shiftModifierForCodePoint ||
                modifierState.shift != HarnessTerminalModifierMode.OFF
            shiftModifierForCodePoint = false
            val shifted = if (shiftDown) {
                Character.toUpperCase(codePoint)
            } else codePoint
            consumeStickyModifier(HarnessTerminalModifier.SHIFT)
            // Returning true prevents Termux from falling back to session.writeCodePoint(),
            // which would drop synthetic-session bytes. Add the Alt escape exactly once here.
            val sequence = composeHarnessTermuxCodePoint(shifted, ctrlDown, altDown)
            if (sequence.isEmpty()) return true
            transport.send(sequence)
            return true
        }

        override fun onEmulatorSet() = terminalView.onScreenUpdated()
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) = Unit
        override fun logStackTrace(tag: String, error: Exception) = Unit
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 2_000
        val SESSION_EMULATOR_FIELD: Field = TerminalSession::class.java.getDeclaredField("mEmulator")
        val SESSION_FD_FIELD: Field = TerminalSession::class.java.getDeclaredField("mTerminalFileDescriptor")

        fun fontSizeToPx(fontSizeSp: Float, metrics: DisplayMetrics): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.coerceIn(10f, 24f), metrics)
                .roundToInt().coerceAtLeast(1)
    }
}

/** Raw control-byte encoder shared by IME and hardware-key paths. */
internal fun composeHarnessTermuxCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean): String {
    if (codePoint !in 0..0x10FFFF) return ""
    val codePointString = String(Character.toChars(codePoint))
    if (!ctrl && !alt) return codePointString
    val char = codePointString.singleOrNull() ?: return if (alt) "\u001b$codePointString" else codePointString
    val upper = char.uppercaseChar()
    val control = when {
        upper in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar()
        char == ' ' || char == '@' -> '\u0000'
        char == '[' -> '\u001b'
        char == '\\' -> '\u001c'
        char == ']' -> '\u001d'
        char == '^' -> '\u001e'
        char == '_' -> '\u001f'
        char == '?' -> '\u007f'
        else -> char
    }
    return buildString {
        if (alt) append('\u001b')
        append(if (ctrl) control else char)
    }
}

@Suppress("DEPRECATION")
private fun harnessTermuxKeySequence(
    keyCode: Int,
    event: KeyEvent,
    applicationCursorKeys: Boolean,
    virtualCtrl: Boolean = false,
    virtualAlt: Boolean = false,
    virtualShift: Boolean = false,
): String? {
    val ctrl = event.isCtrlPressed || virtualCtrl
    val alt = event.isAltPressed || virtualAlt
    val shift = event.isShiftPressed || virtualShift
    val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    val arrow = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "D"
        else -> null
    }
    if (arrow != null) {
        val prefix = if (applicationCursorKeys) "\u001bO" else "\u001b["
        return if (modifier == 1) "$prefix$arrow" else "\u001b[1;${modifier}${arrow}"
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            composeHarnessTermuxCodePoint('\r'.code, ctrl, alt)
        KeyEvent.KEYCODE_DEL -> "\u007f"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
        KeyEvent.KEYCODE_TAB -> when {
            shift && !ctrl && !alt -> "\u001b[Z"
            !ctrl && !alt -> "\t"
            else -> "\u001b[27;${modifier};9~"
        }
        KeyEvent.KEYCODE_ESCAPE -> "\u001b"
        KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
        KeyEvent.KEYCODE_MOVE_HOME -> if (applicationCursorKeys) "\u001bOH" else "\u001b[H"
        KeyEvent.KEYCODE_MOVE_END -> if (applicationCursorKeys) "\u001bOF" else "\u001b[F"
        KeyEvent.KEYCODE_F1 -> "\u001bOP"
        KeyEvent.KEYCODE_F2 -> "\u001bOQ"
        KeyEvent.KEYCODE_F3 -> "\u001bOR"
        KeyEvent.KEYCODE_F4 -> "\u001bOS"
        KeyEvent.KEYCODE_F5 -> "\u001b[15~"
        KeyEvent.KEYCODE_F6 -> "\u001b[17~"
        KeyEvent.KEYCODE_F7 -> "\u001b[18~"
        KeyEvent.KEYCODE_F8 -> "\u001b[19~"
        KeyEvent.KEYCODE_F9 -> "\u001b[20~"
        KeyEvent.KEYCODE_F10 -> "\u001b[21~"
        KeyEvent.KEYCODE_F11 -> "\u001b[23~"
        KeyEvent.KEYCODE_F12 -> "\u001b[24~"
        else -> {
            if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return null
            val unicode = event.getUnicodeChar(
                event.metaState or (if (virtualShift) KeyEvent.META_SHIFT_ON else 0)
            ).takeIf { it != 0 } ?: when {
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    'a'.code + (keyCode - KeyEvent.KEYCODE_A)
                else -> 0
            }
            if (unicode != 0) {
                composeHarnessTermuxCodePoint(unicode, ctrl, alt)
            } else {
                event.characters?.let { characters ->
                    buildString {
                        var index = 0
                        while (index < characters.length) {
                            val point = Character.codePointAt(characters, index)
                            append(composeHarnessTermuxCodePoint(point, ctrl, alt))
                            index += Character.charCount(point)
                        }
                    }.takeIf { it.isNotEmpty() }
                }
            }
        }
    }
}
