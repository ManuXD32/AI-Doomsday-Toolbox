package com.example.llamadroid.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotNetworkConfig
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import com.example.llamadroid.harness.HarnessTerminalModifierState
import com.example.llamadroid.harness.runtime.AndroidHarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.HarnessEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.ui.agent.AgentProotTerminalHost
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Lifecycle state for the standalone Debian recovery shell. */
enum class HarnessRecoveryTerminalStatus {
    CLOSED,
    STARTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

/** Startup metadata retained for recovery diagnostics without retaining shell output. */
enum class HarnessRecoveryTerminalPhase {
    IDLE,
    PREPARING_ENVIRONMENT,
    BUILDING_LAUNCH,
    CREATING_PTY,
    WAITING_FOR_PROCESS,
    WAITING_FOR_GUEST_SHELL,
    CONNECTED,
    STOPPING,
    FAILED
}

internal fun harnessRecoveryTerminalFailureCode(phase: HarnessRecoveryTerminalPhase): String = when (phase) {
    HarnessRecoveryTerminalPhase.PREPARING_ENVIRONMENT -> "RECOVERY_TERMINAL_ENVIRONMENT_UNAVAILABLE"
    HarnessRecoveryTerminalPhase.BUILDING_LAUNCH -> "RECOVERY_TERMINAL_LAUNCH_FAILED"
    HarnessRecoveryTerminalPhase.CREATING_PTY -> "RECOVERY_TERMINAL_PTY_FAILED"
    HarnessRecoveryTerminalPhase.WAITING_FOR_PROCESS -> "RECOVERY_TERMINAL_PROCESS_NOT_READY"
    HarnessRecoveryTerminalPhase.WAITING_FOR_GUEST_SHELL ->
        "RECOVERY_TERMINAL_GUEST_SHELL_NOT_READY"
    else -> "RECOVERY_TERMINAL_START_FAILED"
}

/**
 * Builds the broker/PRoot prefix for the recovery terminal. Options before `--` belong to the
 * native broker; options after it are passed to PRoot. Keeping this contract pure prevents a
 * PRoot option from being rejected by the broker before the guest shell can start.
 */
internal fun harnessRecoveryTerminalProcessArguments(
    broker: File,
    proot: File
): MutableList<String> = mutableListOf(
    broker.absolutePath,
    "--max-processes", "128",
    "--max-open-files", "512",
    "--max-file-bytes", (512L * 1024L * 1024L).toString(),
    "--term-grace-ms", "3000",
    "--",
    proot.absolutePath,
    "--kill-on-exit"
)

/** UI-safe metadata for a recovery shell. No command output is copied into this state. */
data class HarnessRecoveryTerminalState(
    val status: HarnessRecoveryTerminalStatus = HarnessRecoveryTerminalStatus.CLOSED,
    val phase: HarnessRecoveryTerminalPhase = HarnessRecoveryTerminalPhase.IDLE,
    val sessionId: String? = null,
    val openedAt: Long? = null,
    val lastActivityAt: Long? = null,
    val processId: Int? = null,
    val exitStatus: Int? = null,
    val failurePhase: HarnessRecoveryTerminalPhase? = null,
    val errorCode: String? = null
) {
    val isConnected: Boolean
        get() = status == HarnessRecoveryTerminalStatus.CONNECTED

    val isOpening: Boolean
        get() = status == HarnessRecoveryTerminalStatus.STARTING
}

/**
 * Starts a plain interactive Debian PRoot shell without starting DSH, the Harness payload, or
 * the agent foreground service. It reuses the app's verified PRoot binaries and Termux PTY view.
 *
 * This manager intentionally has no command approval path: it is a user-operated local terminal
 * reached from the recovery menu. The Harness agent still uses its existing sandbox and approval
 * policy independently.
 */
class HarnessRecoveryTerminalSessionManager private constructor(context: Context) {
    companion object {
        private const val TAG = "HarnessRecoveryTerminal"
        private const val DEFAULT_COLUMNS = 120
        private const val DEFAULT_ROWS = 40
        private const val TERM_GRACE_STEPS = 10
        private const val TERM_GRACE_STEP_MS = 100L
        private const val MAX_LOG_CHARS = 512

        @Volatile
        private var instance: HarnessRecoveryTerminalSessionManager? = null

        fun get(context: Context): HarnessRecoveryTerminalSessionManager =
            instance ?: synchronized(this) {
                instance ?: HarnessRecoveryTerminalSessionManager(context.applicationContext)
                    .also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val environmentProvider = AndroidHarnessEnvironmentProvider(appContext)
    private val lifecycleLock = Mutex()
    private val _state = MutableStateFlow(HarnessRecoveryTerminalState())
    private val _modifierState = MutableStateFlow(HarnessTerminalModifierState())
    @Volatile
    private var terminal: TerminalSession? = null
    @Volatile
    private var terminalHost: AgentProotTerminalHost? = null
    @Volatile
    private var screenListener: (() -> Unit)? = null
    @Volatile
    private var closing = false

    val state: StateFlow<HarnessRecoveryTerminalState> = _state.asStateFlow()
    val modifierState: StateFlow<HarnessTerminalModifierState> = _modifierState.asStateFlow()

    private val terminalClient = object : TerminalSessionClient {
        override fun onTextChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)
        override fun onTitleChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)
        override fun onColorsChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)

        override fun onSessionFinished(terminalSession: TerminalSession) {
            scope.launch {
                lifecycleLock.withLock {
                    val current = terminal ?: return@withLock
                    if (current !== terminalSession) return@withLock
                    val exitStatus = terminalSession.exitStatus
                    val wasClosing = closing
                    terminal = null
                    terminalHost = null
                    _modifierState.value = HarnessTerminalModifierState()
                    val status = when {
                        wasClosing -> HarnessRecoveryTerminalStatus.CLOSED
                        exitStatus == 0 -> HarnessRecoveryTerminalStatus.DISCONNECTED
                        else -> HarnessRecoveryTerminalStatus.FAILED
                    }
                    publish(
                        _state.value.copy(
                            status = status,
                            phase = if (status == HarnessRecoveryTerminalStatus.FAILED) {
                                HarnessRecoveryTerminalPhase.FAILED
                            } else {
                                HarnessRecoveryTerminalPhase.IDLE
                            },
                            processId = null,
                            exitStatus = exitStatus,
                            failurePhase = if (status == HarnessRecoveryTerminalStatus.FAILED) {
                                HarnessRecoveryTerminalPhase.CONNECTED
                            } else {
                                null
                            },
                            errorCode = if (status == HarnessRecoveryTerminalStatus.FAILED) {
                                "RECOVERY_TERMINAL_EXITED"
                            } else {
                                null
                            }
                        )
                    )
                }
            }
        }

        override fun onCopyTextToClipboard(terminalSession: TerminalSession, text: String) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("Debian recovery terminal", text))
        }

        override fun onPasteTextFromClipboard(terminalSession: TerminalSession) {
            val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            if (!text.isNullOrEmpty() && terminalSession.isRunning) {
                terminalSession.emulator?.paste(text)
            }
        }

        override fun onBell(terminalSession: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) { notifyScreen() }
        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String, message: String) { Log.e(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}") }
        override fun logWarn(tag: String, message: String) { Log.w(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}") }
        override fun logInfo(tag: String, message: String) { Log.i(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}") }
        override fun logDebug(tag: String, message: String) { Log.d(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}") }
        override fun logVerbose(tag: String, message: String) { Log.v(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}") }

        override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) {
            Log.e(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}", error)
        }

        override fun logStackTrace(tag: String, error: Exception) { Log.e(TAG, tag, error) }
    }

    /** Opens the shell, preparing only the verified Debian rootfs and its persistent mounts. */
    suspend fun open(): Result<HarnessRecoveryTerminalState> = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            var startupPhase = HarnessRecoveryTerminalPhase.PREPARING_ENVIRONMENT
            var guestReadyReceipt: AgentProotGuestReadyReceipt? = null
            try {
                val existing = terminal
                if (existing?.isRunning == true) {
                    // A renderer failure must not tear down a healthy PTY. Reset only the
                    // view-failure marker so the composed screen recreates its Android host on
                    // the next state change; startup failures still create a new PTY below.
                    if (_state.value.errorCode == "RECOVERY_TERMINAL_VIEW_FAILED") {
                        terminalHost = null
                        publish(
                            _state.value.copy(
                                status = HarnessRecoveryTerminalStatus.CONNECTED,
                                phase = HarnessRecoveryTerminalPhase.CONNECTED,
                                failurePhase = null,
                                errorCode = null,
                                processId = existing.pid.takeIf { it > 0 }
                            )
                        )
                    }
                    return@withLock Result.success(_state.value)
                }
                require(!(_state.value.isOpening)) { "The recovery terminal is already starting." }
                closing = false
                val openingState = HarnessRecoveryTerminalState(
                    status = HarnessRecoveryTerminalStatus.STARTING,
                    phase = startupPhase,
                    openedAt = System.currentTimeMillis(),
                )
                publish(openingState)
                val paths = environmentProvider.prepare(HarnessRuntimePaths.SHARED_ENVIRONMENT_ID)
                startupPhase = HarnessRecoveryTerminalPhase.BUILDING_LAUNCH
                publish(_state.value.copy(phase = startupPhase))
                val sessionId = "recovery_${UUID.randomUUID()}"
                val launch = buildLaunch(sessionId, paths)
                guestReadyReceipt = launch.guestReadyReceipt
                startupPhase = HarnessRecoveryTerminalPhase.CREATING_PTY
                publish(_state.value.copy(phase = startupPhase))
                val created = withContext(Dispatchers.Main.immediate) {
                    TerminalSession(
                        launch.executable,
                        launch.hostWorkingDirectory,
                        launch.arguments,
                        launch.environment,
                        TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                        terminalClient
                    ).apply { mSessionName = "Debian recovery shell" }
                }
                terminal = created
                withContext(Dispatchers.Main.immediate) {
                    created.initializeEmulator(DEFAULT_COLUMNS, DEFAULT_ROWS)
                }
                startupPhase = HarnessRecoveryTerminalPhase.WAITING_FOR_PROCESS
                publish(_state.value.copy(phase = startupPhase))
                awaitAgentProotTerminalProcessReady(created)
                startupPhase = HarnessRecoveryTerminalPhase.WAITING_FOR_GUEST_SHELL
                publish(_state.value.copy(phase = startupPhase))
                awaitAgentProotGuestShellReady(created, launch.guestReadyReceipt)
                val now = System.currentTimeMillis()
                startupPhase = HarnessRecoveryTerminalPhase.CONNECTED
                val connected = HarnessRecoveryTerminalState(
                    status = HarnessRecoveryTerminalStatus.CONNECTED,
                    phase = startupPhase,
                    sessionId = sessionId,
                    openedAt = now,
                    lastActivityAt = now,
                    processId = created.pid.takeIf { it > 0 }
                )
                publish(connected)
                Result.success(connected)
            } catch (failure: Throwable) {
                val failedTerminal = terminal
                if (failedTerminal != null) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (failedTerminal.isRunning) failedTerminal.finishIfRunning()
                    }
                }
                terminal = null
                terminalHost = null
                _modifierState.value = HarnessTerminalModifierState()
                closing = false
                Log.w(
                    TAG,
                    "start phase=${startupPhase.name} error=${failure.javaClass.simpleName}"
                )
                publish(
                    _state.value.copy(
                        status = HarnessRecoveryTerminalStatus.FAILED,
                        phase = HarnessRecoveryTerminalPhase.FAILED,
                        processId = null,
                        exitStatus = failedTerminal?.exitStatus,
                        failurePhase = startupPhase,
                        errorCode = harnessRecoveryTerminalFailureCode(startupPhase)
                    )
                )
                Result.failure(failure)
            } finally {
                guestReadyReceipt?.let(::cleanupAgentProotGuestReadyReceipt)
            }
        }
    }

    fun terminalSession(): TerminalSession? = terminal?.takeIf { it.isRunning }

    /** Creates the Android view bridge used by the full-screen recovery terminal UI. */
    fun terminalHost(): AgentProotTerminalHost? {
        val current = terminalSession() ?: return null
        val existing = terminalHost
        if (existing != null) return existing
        return runCatching {
            AgentProotTerminalHost(
                context = appContext,
                terminalSession = current,
                onInputActivity = { touch() },
                onModifierStateChanged = { _, _ -> touch() },
                onFullModifierStateChanged = { _modifierState.value = it },
            )
        }.onFailure {
            // A renderer/host initialization problem must remain recoverable. In particular,
            // Termux can fail while the Android view is being recreated after a process restore;
            // keep the PTY alive and let the screen expose Retry instead of crashing the app.
            publish(_state.value.copy(
                status = HarnessRecoveryTerminalStatus.FAILED,
                errorCode = "RECOVERY_TERMINAL_VIEW_FAILED"
            ))
        }.getOrNull().also { created ->
            if (created != null) terminalHost = created
        }
    }

    fun setScreenListener(listener: (() -> Unit)?) {
        screenListener = listener
        if (listener != null) notifyScreen()
    }

    fun touch() {
        if (!_state.value.isConnected) return
        publish(_state.value.copy(lastActivityAt = System.currentTimeMillis()))
    }

    suspend fun send(input: String, appendNewline: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = requireConnected()
                val payload = if (appendNewline) "$input\r" else input
                val bytes = payload.toByteArray(Charsets.UTF_8)
                current.write(bytes, 0, bytes.size)
                touch()
            }
        }

    fun clear() {
        val current = terminal ?: return
        scope.launchMain {
            runCatching { current.reset() }
            touch()
            notifyScreen()
        }
    }

    suspend fun close(): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            runCatching {
                closing = true
                publish(_state.value.copy(phase = HarnessRecoveryTerminalPhase.STOPPING))
                val current = terminal
                if (current != null && current.isRunning) {
                    runCatching { Os.kill(current.pid, OsConstants.SIGTERM) }
                    repeat(TERM_GRACE_STEPS) {
                        if (current.isRunning) delay(TERM_GRACE_STEP_MS)
                    }
                    if (current.isRunning) {
                        withContext(Dispatchers.Main.immediate) { current.finishIfRunning() }
                    }
                }
                terminal = null
                terminalHost = null
                _modifierState.value = HarnessTerminalModifierState()
                publish(HarnessRecoveryTerminalState())
            }
        }
    }

    /**
     * Immediately kills only the recovery PRoot process.  This deliberately does not call the
     * Harness runtime controller: the recovery screen is also the escape hatch used when DSH is
     * unhealthy or cannot be started.
     */
    suspend fun forceStop(): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            runCatching {
                closing = true
                publish(_state.value.copy(phase = HarnessRecoveryTerminalPhase.STOPPING))
                val current = terminal
                if (current != null) {
                    if (current.isRunning && current.pid > 0) {
                        runCatching { Os.kill(current.pid, OsConstants.SIGKILL) }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (current.isRunning) current.finishIfRunning()
                    }
                }
                terminal = null
                terminalHost = null
                _modifierState.value = HarnessTerminalModifierState()
                publish(HarnessRecoveryTerminalState())
            }
        }
    }

    /** Stops a shell when the recovery screen is removed, without touching the Harness runtime. */
    fun closeAsync() {
        scope.launch { close() }
    }

    private fun buildLaunch(sessionId: String, paths: HarnessEnvironmentPaths): AgentProotTerminalLaunch {
        val recoveryRoot = File(appContext.cacheDir, "agent_harness/recovery/${paths.environmentId}")
            .canonicalFile
        val tempRoot = File(recoveryRoot, "tmp").apply { mkdirs() }.canonicalFile
        val runRoot = File(recoveryRoot, "run").apply { mkdirs() }.canonicalFile
        val resolver = AgentProotNetworkConfig.write(appContext, paths.environmentId)
        val broker = AgentProotNativeBinaryProvider.requireBroker(appContext)
        val proot = AgentProotNativeBinaryProvider.requireProot(appContext)
        val arguments = harnessRecoveryTerminalProcessArguments(broker, proot)
        arguments += AgentProotLaunchOptions.forRootfs(paths.rootfs)
        arguments += listOf(
            "-b", "${paths.projectsHost.absolutePath}:${paths.projectsGuest}",
            "-b", "${paths.dshHomeHost.absolutePath}:${paths.dshHomeGuest}",
            "-b", "${tempRoot.absolutePath}:${AgentProotEnvironmentPaths.TMP_MOUNT}",
            "-b", "${runRoot.absolutePath}:${AgentProotEnvironmentPaths.RUN_MOUNT}",
            "-b", "${resolver.absolutePath}:/etc/resolv.conf",
            "-b", "/proc:/proc"
        )
        listOf("null", "zero", "random", "urandom", "tty", "ptmx").forEach { device ->
            File("/dev", device).takeIf { it.exists() }?.let {
                arguments += listOf("-b", "${it.absolutePath}:/dev/$device")
            }
        }
        arguments += agentProotInteractivePtyBindArguments()
        val guestReadyReceipt = createAgentProotGuestReadyReceipt(runRoot, sessionId)
        try {
            arguments += agentProotInteractiveShellArguments(
                workingDirectory = paths.projectsGuest,
                guestReadyFile = guestReadyReceipt.guestFile,
                guestReadyNonce = guestReadyReceipt.nonce
            )
            val environment = linkedMapOf(
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_TMP_DIR" to tempRoot.absolutePath,
                "LD_LIBRARY_PATH" to appContext.applicationInfo.nativeLibraryDir,
                "PROOT_LOADER" to (AgentProotNativeBinaryProvider.locateLoader(appContext)?.absolutePath),
                // Keep the interactive shell independent from DSH's profile/configuration. The bound
                // /root/.dsh tree is still available through an explicit path when recovery requires it.
                "HOME" to "/root",
                "TMPDIR" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "TMP" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "TEMP" to AgentProotEnvironmentPaths.TMP_MOUNT,
                "SHELL" to "/bin/bash",
                "USER" to "root",
                "LOGNAME" to "root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "xterm-256color",
                "COLORTERM" to "truecolor",
                "LANG" to "C.UTF-8",
                "LC_ALL" to "C.UTF-8",
                "PS1" to "debian-recovery:\\w\\$ ",
                "ADT_RECOVERY_TERMINAL" to "1",
                "ADT_TERMINAL_SESSION_ID" to sessionId
            ).filterValues { it != null }.map { (key, value) -> "$key=$value" }.toTypedArray()
            return AgentProotTerminalLaunch(
                executable = broker.absolutePath,
                hostWorkingDirectory = paths.projectsHost.absolutePath,
                arguments = arguments.toTypedArray(),
                environment = environment,
                guestReadyReceipt = guestReadyReceipt
            )
        } catch (failure: Throwable) {
            cleanupAgentProotGuestReadyReceipt(guestReadyReceipt)
            throw failure
        }
    }

    private fun requireConnected(): TerminalSession {
        val current = terminal ?: error("The recovery terminal is not open.")
        require(current.isRunning && _state.value.isConnected) {
            "The recovery terminal is disconnected."
        }
        return current
    }

    private fun terminalChanged(current: TerminalSession) {
        if (current !== terminal) return
        touch()
        notifyScreen()
    }

    private fun publish(next: HarnessRecoveryTerminalState) {
        _state.value = next
        notifyScreen()
    }

    private fun notifyScreen() = runCatching { screenListener?.invoke() }

    private fun clipboard(): ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun CoroutineScope.launchMain(block: suspend () -> Unit) {
        launch(Dispatchers.Main.immediate) { block() }
    }

}
