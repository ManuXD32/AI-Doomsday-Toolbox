package com.example.llamadroid.harness

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Reuses the app's terminal parser so cursor movement and alternate screens remain readable. */
internal class HarnessTerminalBuffer(private val reply: (String) -> Unit = {}) {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count in 1..8192) reply(String(data, offset, count, Charsets.UTF_8))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession?) = Unit
        override fun onTitleChanged(session: TerminalSession?) = Unit
        override fun onSessionFinished(session: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) = Unit
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
    private var emulator = TerminalEmulator(output, 80, 24, 1000, client)

    @Synchronized fun snapshot(screen: String, cols: Int, rows: Int) {
        emulator = TerminalEmulator(output, cols.coerceIn(2, 500), rows.coerceIn(1, 200), 1000, client)
        append(screen)
    }

    @Synchronized fun append(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    @Synchronized fun resize(cols: Int, rows: Int) = emulator.resize(cols.coerceIn(2, 500), rows.coerceIn(1, 200))
    @Synchronized fun clear() { emulator.reset(); emulator.screen.clearTranscript() }
    @Synchronized fun text(): String = emulator.screen.transcriptText.takeLast(64 * 1024)
}
