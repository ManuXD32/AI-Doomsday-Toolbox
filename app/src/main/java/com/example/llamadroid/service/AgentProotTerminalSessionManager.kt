package com.example.llamadroid.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AgentProotRunEntity
import com.example.llamadroid.data.db.AgentProotRunStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotEnvironmentSpec
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import com.example.llamadroid.data.proot.AgentProotNetworkConfig
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val AGENT_PROOT_TERMINALS_PER_PROJECT = 8
internal const val AGENT_PROOT_TERMINALS_PER_PROCESS = 24
internal const val AGENT_PROOT_ACTIVE_PTY_BIND = "__ADT_ACTIVE_PTY_BIND__"
internal const val AGENT_PROOT_PTY_PARENT_BIND = "/dev/pts:/dev/pts"

/** Startup phases are deliberately metadata-only so diagnostics never retain terminal output. */
internal enum class AgentProotTerminalStartupPhase {
    PREPARING_ENVIRONMENT,
    BUILDING_LAUNCH,
    CREATING_PTY,
    WAITING_FOR_PROCESS,
    WAITING_FOR_GUEST_SHELL,
    CONNECTED
}

internal fun canOpenAgentProotTerminal(projectActive: Int, processActive: Int): Boolean =
    projectActive in 0 until AGENT_PROOT_TERMINALS_PER_PROJECT &&
        processActive in 0 until AGENT_PROOT_TERMINALS_PER_PROCESS

/** Immutable launch material consumed by Termux's PTY-backed [TerminalSession]. */
internal data class AgentProotTerminalLaunch(
    val executable: String,
    val hostWorkingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>,
    val guestReadyReceipt: AgentProotGuestReadyReceipt
)

internal data class AgentProotGuestReadyReceipt(
    val hostFile: File,
    val guestFile: String,
    val nonce: String
)

private const val AGENT_PROOT_GUEST_READY_DIRECTORY = "adt-terminal-ready"
private const val AGENT_PROOT_GUEST_READY_MOUNT = "/run/adt-terminal-ready"
private val AGENT_PROOT_SESSION_ID = Regex("[A-Za-z0-9_-]+")

/** Allocates a per-session, host-owned receipt path mounted inside the guest at `/run`. */
internal fun createAgentProotGuestReadyReceipt(
    runRoot: File,
    sessionId: String
): AgentProotGuestReadyReceipt {
    require(AGENT_PROOT_SESSION_ID.matches(sessionId)) {
        "Interactive terminal session identity is invalid."
    }
    val directory = File(runRoot, AGENT_PROOT_GUEST_READY_DIRECTORY).canonicalFile
    require(directory.mkdirs() || directory.isDirectory) {
        "Interactive terminal readiness directory is unavailable."
    }
    val hostFile = File(directory, sessionId).canonicalFile
    require(hostFile.parentFile == directory) {
        "Interactive terminal readiness path escaped its run directory."
    }
    if (hostFile.exists()) require(hostFile.delete()) {
        "Interactive terminal readiness receipt could not be reset."
    }
    val nonce = UUID.randomUUID().toString()
    return AgentProotGuestReadyReceipt(
        hostFile = hostFile,
        guestFile = "$AGENT_PROOT_GUEST_READY_MOUNT/$sessionId",
        nonce = nonce
    )
}

internal fun cleanupAgentProotGuestReadyReceipt(receipt: AgentProotGuestReadyReceipt) {
    runCatching { receipt.hostFile.delete() }
}

internal fun agentProotGuestReadyReceiptMatches(receipt: AgentProotGuestReadyReceipt): Boolean {
    val expected = receipt.nonce.toByteArray(Charsets.UTF_8)
    return runCatching {
        receipt.hostFile.isFile &&
            receipt.hostFile.length() == expected.size.toLong() &&
            receipt.hostFile.readBytes().contentEquals(expected)
    }.getOrDefault(false)
}

/**
 * Starts Bash through a tiny non-interactive handoff that writes the owned receipt, then execs the
 * real interactive shell. This proves that PRoot reached the guest rootfs and Bash, rather than
 * merely proving that the host-side broker has a PID.
 */
internal fun agentProotInteractiveShellArguments(
    workingDirectory: String = AgentProotEnvironmentPaths.WORKSPACE_MOUNT,
    guestReadyFile: String? = null,
    guestReadyNonce: String? = null
): List<String> {
    require((guestReadyFile == null) == (guestReadyNonce == null)) {
        "Guest readiness path and nonce must be supplied together."
    }
    val base = mutableListOf(
        "-w", workingDirectory,
        "/bin/bash", "--noprofile", "--norc"
    )
    if (guestReadyFile == null) {
        base += "-i"
    } else {
        base += listOf(
            "-c",
            "set -eu; printf '%s' ${agentProotShellQuote(requireNotNull(guestReadyNonce))} > " +
                "${agentProotShellQuote(guestReadyFile)}; " +
                "exec /bin/bash --noprofile --norc -i"
        )
    }
    return base
}

private fun agentProotShellQuote(value: String): String =
    "'${value.replace("'", "'\\''")}'"

/**
 * The PTY JNI replaces the opaque token with `host-slave:guest-slave` before exec. The parent
 * mount is required because the Debian rootfs only owns an empty `/dev` hierarchy; binding the
 * slave alone leaves the guest without the `/dev/pts` filesystem needed by interactive programs.
 */
internal fun agentProotInteractivePtyBindArguments(): List<String> =
    listOf(
        "-b", AGENT_PROOT_PTY_PARENT_BIND,
        "-b", AGENT_PROOT_ACTIVE_PTY_BIND
    )

/** Waits until Termux has created a live child before the UI advertises a connected shell. */
internal suspend fun awaitAgentProotTerminalProcessReady(
    terminal: TerminalSession,
    timeoutMs: Long = 5_000L,
    pollMs: Long = 50L
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (terminal.isRunning && terminal.pid <= 0 && System.nanoTime() < deadline) {
        delay(pollMs)
    }
    check(terminal.isRunning && terminal.pid > 0) {
        "Interactive terminal process did not become ready."
    }
}

/** Waits until the guest-side Bash handoff has reached the rootfs and execed the shell. */
internal suspend fun awaitAgentProotGuestShellReady(
    terminal: TerminalSession,
    receipt: AgentProotGuestReadyReceipt,
    timeoutMs: Long = 5_000L,
    pollMs: Long = 50L
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (terminal.isRunning &&
        !agentProotGuestReadyReceiptMatches(receipt) &&
        System.nanoTime() < deadline
    ) {
        delay(pollMs)
    }
    check(terminal.isRunning && agentProotGuestReadyReceiptMatches(receipt)) {
        "Guest shell did not report readiness."
    }
}

internal fun agentProotTerminalFailureCode(
    phase: AgentProotTerminalStartupPhase,
    failure: Throwable
): String = when {
    failure is kotlinx.coroutines.CancellationException -> "TERMINAL_START_CANCELLED"
    phase == AgentProotTerminalStartupPhase.PREPARING_ENVIRONMENT ->
        "TERMINAL_ENVIRONMENT_UNAVAILABLE"
    phase == AgentProotTerminalStartupPhase.BUILDING_LAUNCH -> "TERMINAL_LAUNCH_FAILED"
    phase == AgentProotTerminalStartupPhase.CREATING_PTY -> "TERMINAL_PTY_FAILED"
    phase == AgentProotTerminalStartupPhase.WAITING_FOR_PROCESS -> "TERMINAL_PROCESS_NOT_READY"
    phase == AgentProotTerminalStartupPhase.WAITING_FOR_GUEST_SHELL ->
        "TERMINAL_GUEST_SHELL_NOT_READY"
    else -> "TERMINAL_START_FAILED"
}

internal fun agentProotInteractiveEnvironment(
    nativeLibraryDir: String,
    tempRoot: String,
    projectFolder: String,
    sessionId: String,
    loaderPath: String?
): Array<String> = linkedMapOf(
    "PROOT_NO_SECCOMP" to "1",
    "PROOT_TMP_DIR" to tempRoot,
    "LD_LIBRARY_PATH" to nativeLibraryDir,
    "HOME" to "/root",
    "SHELL" to "/bin/bash",
    "USER" to "root",
    "LOGNAME" to "root",
    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "TERM" to "xterm-256color",
    "COLORTERM" to "truecolor",
    "LANG" to "C.UTF-8",
    "LC_ALL" to "C.UTF-8",
    "PS1" to "debian:\\w\\$ ",
    "ADT_PROJECT_ID" to projectFolder,
    "ADT_TERMINAL_SESSION_ID" to sessionId
).apply {
    loaderPath?.let { put("PROOT_LOADER", it) }
}.map { (key, value) -> "$key=$value" }.toTypedArray()

/**
 * User-owned interactive Debian terminals. These sessions are deliberately separate from the
 * Direct Agent command queue: opening a terminal never gives the model a path around approvals.
 * A real PTY owns each broker/PRoot/Bash chain, so terminal control sequences are never flattened
 * into a Compose transcript and full-screen programs retain their controlling terminal.
 */
internal class AgentProotTerminalSessionManager(private val context: Context) {
    private data class Session(
        val id: String,
        val conversationId: Long,
        val environmentId: String,
        val projectFolder: String,
        val displayName: String,
        val terminal: TerminalSession,
        val openedAt: Long,
        val stateLock: Any = Any(),
        @Volatile var lastActivityAt: Long,
        @Volatile var connected: Boolean = true,
        @Volatile var closing: Boolean = false,
        @Volatile var error: String? = null,
        @Volatile var finished: Boolean = false,
        @Volatile var screenListener: (() -> Unit)? = null
    )

    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val environmentManager = AgentProotEnvironmentManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val _states = MutableStateFlow<Map<Long, List<WorkspaceTerminalUiState>>>(emptyMap())
    val states: StateFlow<Map<Long, List<WorkspaceTerminalUiState>>> = _states.asStateFlow()

    private val startup = scope.async {
        database.agentProotRunDao().interruptStaleActiveRuns(
            currentGeneration = AgentProcessGeneration.id,
            reason = "The app process ended while this interactive Debian terminal was active."
        )
    }

    private val terminalClient = object : TerminalSessionClient {
        override fun onTextChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)
        override fun onTitleChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)

        override fun onSessionFinished(terminalSession: TerminalSession) {
            val session = sessionFor(terminalSession) ?: return
            val exitStatus = terminalSession.exitStatus
            val status = when {
                session.closing -> AgentProotRunStatus.STOPPED
                exitStatus == 0 -> AgentProotRunStatus.SUCCEEDED
                else -> AgentProotRunStatus.FAILED
            }
            val errorCode = exitStatus.takeIf { it != 0 && !session.closing }
                ?.let { "TERMINAL_PROCESS_EXITED" }
            scope.launch {
                lifecycleLock.withLock { finish(session, status, errorCode) }
            }
        }

        override fun onCopyTextToClipboard(terminalSession: TerminalSession, text: String) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("Debian terminal", text))
        }

        override fun onPasteTextFromClipboard(terminalSession: TerminalSession) {
            val text = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
            if (!text.isNullOrEmpty() && terminalSession.isRunning) terminalSession.emulator?.paste(text)
        }

        override fun onBell(terminalSession: TerminalSession) = Unit
        override fun onColorsChanged(terminalSession: TerminalSession) = terminalChanged(terminalSession)
        override fun onTerminalCursorStateChange(state: Boolean) = sessions.values.forEach(::notifyScreen)
        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
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
        override fun logVerbose(tag: String, message: String) {
            Log.v(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}")
        }
        override fun logStackTraceWithMessage(tag: String, message: String, error: Exception) {
            Log.e(TAG, "$tag: ${message.take(MAX_LOG_CHARS)}", error)
        }
        override fun logStackTrace(tag: String, error: Exception) {
            Log.e(TAG, tag, error)
        }
    }

    suspend fun open(conversationId: Long, projectFolder: String): Result<WorkspaceTerminalUiState> =
        withContext(Dispatchers.IO) {
            lifecycleLock.withLock {
                var startupPhase = AgentProotTerminalStartupPhase.PREPARING_ENVIRONMENT
                var queuedId: String? = null
                runCatching {
                    try {
                        startup.await()
                        require(conversationId > 0L) { "A saved project conversation is required." }
                        val projectActive = activeForConversation(conversationId).size
                        val processActive = sessions.values.count { it.connected }
                        require(projectActive < AGENT_PROOT_TERMINALS_PER_PROJECT) {
                            "A project can keep at most $AGENT_PROOT_TERMINALS_PER_PROJECT Debian terminal sessions open."
                        }
                        require(canOpenAgentProotTerminal(projectActive, processActive)) {
                            "Too many Debian terminal sessions are already open."
                        }
                        val conversation = database.agentChatDao().getConversation(conversationId)
                            ?: error("The active project conversation no longer exists.")
                        val environmentId = conversation.prootEnvironmentId
                            ?: error("Select a Debian environment before opening its terminal.")
                        val environment = database.agentProotEnvironmentDao().getById(environmentId)
                            ?: error("The selected Debian environment no longer exists.")
                        val id = "terminal_${UUID.randomUUID()}"
                        queuedId = id
                        val now = System.currentTimeMillis()
                        val queuedRow = AgentProotRunEntity(
                            id = id,
                            conversationId = conversationId,
                            environmentId = environmentId,
                            projectFolder = projectFolder,
                            commandDigest = "interactive_terminal:${sha256(id)}",
                            status = AgentProotRunStatus.QUEUED,
                            processGeneration = AgentProcessGeneration.id,
                            createdAt = now,
                            updatedAt = now
                        )
                        database.agentProotRunDao().insert(queuedRow)
                        setEnvironmentInstalling(environment)
                        val rootfs = try {
                            environmentManager.prepare(
                                AgentProotEnvironmentSpec(
                                    id = environment.id,
                                    displayName = environment.displayName,
                                    imageId = environment.imageId,
                                    imageSha256 = environment.imageDigest.takeIf { it.matches(SHA256) }
                                )
                            ).getOrThrow()
                        } catch (error: Throwable) {
                            database.agentProotEnvironmentDao().update(
                                environment.copy(
                                    status = AgentProotEnvironmentStatus.BROKEN,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            throw error
                        }

                        database.agentProotEnvironmentDao().update(
                            environment.copy(
                                status = AgentProotEnvironmentStatus.READY,
                                sizeBytes = environmentManager.usageBytes(environmentId),
                                lastUsedAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        startupPhase = AgentProotTerminalStartupPhase.BUILDING_LAUNCH
                        val launch = buildLaunch(id, environmentId, projectFolder, rootfs)
                        var createdTerminal: TerminalSession? = null
                        try {
                            val ordinal = activeForConversation(conversationId).size + 1
                            val displayName = "${environment.displayName} · $ordinal"
                            startupPhase = AgentProotTerminalStartupPhase.CREATING_PTY
                            val terminal = withContext(Dispatchers.Main.immediate) {
                                TerminalSession(
                                    launch.executable,
                                    launch.hostWorkingDirectory,
                                    launch.arguments,
                                    launch.environment,
                                    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                                    terminalClient
                                ).apply { mSessionName = displayName }
                            }
                            createdTerminal = terminal
                            val session = Session(
                                id = id,
                                conversationId = conversationId,
                                environmentId = environmentId,
                                projectFolder = projectFolder,
                                displayName = displayName,
                                terminal = terminal,
                                openedAt = now,
                                lastActivityAt = now
                            )
                            sessions[id] = session
                            withContext(Dispatchers.Main.immediate) {
                                terminal.initializeEmulator(DEFAULT_COLUMNS, DEFAULT_ROWS)
                            }
                            startupPhase = AgentProotTerminalStartupPhase.WAITING_FOR_PROCESS
                            awaitAgentProotTerminalProcessReady(terminal)
                            startupPhase = AgentProotTerminalStartupPhase.WAITING_FOR_GUEST_SHELL
                            awaitAgentProotGuestShellReady(terminal, launch.guestReadyReceipt)
                            database.agentProotRunDao().insert(
                                queuedRow.copy(
                                    status = AgentProotRunStatus.RUNNING,
                                    processId = terminal.pid.takeIf { it > 0 },
                                    startedAt = now,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            startupPhase = AgentProotTerminalStartupPhase.CONNECTED
                            AgentForegroundService.retainRuntime(
                                appContext,
                                appContext.getString(R.string.agent_status_workspace_terminal_running, "/workspace")
                            )
                            publish(conversationId)
                            queuedId = null
                            snapshot(session)
                        } catch (failure: Throwable) {
                            val current = createdTerminal ?: sessions[id]?.terminal
                            sessions.remove(id)
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (current?.isRunning == true) current.finishIfRunning()
                            }
                            throw failure
                        } finally {
                            cleanupAgentProotGuestReadyReceipt(launch.guestReadyReceipt)
                        }
                    } catch (error: Throwable) {
                        queuedId?.let { failQueued(it, error, startupPhase) }
                        throw error
                    }
                }
            }
        }

    fun current(conversationId: Long): List<WorkspaceTerminalUiState> =
        states.value[conversationId].orEmpty()

    fun terminalSession(sessionId: String): TerminalSession? =
        sessions[sessionId]?.terminal?.takeIf { sessions[sessionId]?.connected == true }

    fun setScreenListener(sessionId: String, listener: (() -> Unit)?) {
        val session = sessions[sessionId] ?: return
        session.screenListener = listener
        if (listener != null) notifyScreen(session)
    }

    fun touch(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.lastActivityAt = System.currentTimeMillis()
        publish(session.conversationId)
    }

    suspend fun send(sessionId: String, input: String, appendNewline: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = requireConnected(sessionId)
                val payload = if (appendNewline) "$input\r" else input
                val bytes = payload.toByteArray(Charsets.UTF_8)
                session.terminal.write(bytes, 0, bytes.size)
                session.lastActivityAt = System.currentTimeMillis()
                publish(session.conversationId)
            }
        }

    suspend fun close(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            runCatching {
            val session = sessions[sessionId] ?: return@runCatching
            session.closing = true
            if (session.terminal.isRunning) {
                runCatching { Os.kill(session.terminal.pid, OsConstants.SIGTERM) }
                repeat(TERM_GRACE_STEPS) {
                    if (session.terminal.isRunning) delay(TERM_GRACE_STEP_MS)
                }
                if (session.terminal.isRunning) session.terminal.finishIfRunning()
            }
            finish(session, AgentProotRunStatus.STOPPED, null)
            sessions.remove(sessionId, session)
            publish(session.conversationId)
            }
        }
    }

    suspend fun closeForConversation(conversationId: Long): Int = withContext(Dispatchers.IO) {
        val ids = activeForConversation(conversationId).map { it.id }
        ids.count { close(it).isSuccess }
    }

    fun clear(sessionId: String) {
        val session = sessions[sessionId] ?: return
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { session.terminal.reset() }
            session.lastActivityAt = System.currentTimeMillis()
            notifyScreen(session)
            publish(session.conversationId)
        }
    }

    fun closeAll() {
        sessions.keys.toList().forEach { id ->
            scope.launch {
                close(id)
                database.agentProotRunDao().finish(
                    id = id,
                    status = AgentProotRunStatus.INTERRUPTED,
                    errorClass = "RUNTIME_STOPPED",
                    errorMessage = "Agent runtime stopped."
                )
            }
        }
    }

    private suspend fun setEnvironmentInstalling(
        environment: com.example.llamadroid.data.db.AgentProotEnvironmentEntity
    ) {
        if (!environmentManager.isReady(environment.id)) {
            database.agentProotEnvironmentDao().update(
                environment.copy(
                    status = AgentProotEnvironmentStatus.INSTALLING,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun activeForConversation(conversationId: Long): List<Session> = sessions.values
        .filter { it.conversationId == conversationId && it.connected && it.terminal.isRunning }
        .sortedBy { it.openedAt }

    private fun buildLaunch(
        sessionId: String,
        environmentId: String,
        projectFolder: String,
        rootfs: File
    ): AgentProotTerminalLaunch {
        val projectRoot = AgentLocalWorkspaceSupport.rootForProject(appContext, projectFolder).canonicalFile
        val tempRoot = File(appContext.cacheDir, "agent-proot/$environmentId/tmp").canonicalFile
        val runRoot = File(appContext.cacheDir, "agent-proot/$environmentId/run").canonicalFile
        tempRoot.mkdirs()
        runRoot.mkdirs()
        val resolver = AgentProotNetworkConfig.write(appContext, environmentId)
        val broker = AgentProotNativeBinaryProvider.requireBroker(appContext)
        val proot = AgentProotNativeBinaryProvider.requireProot(appContext)
        val arguments = mutableListOf(
            broker.absolutePath,
            "--max-processes", "128",
            "--max-open-files", "512",
            "--max-file-bytes", (512L * 1024L * 1024L).toString(),
            "--term-grace-ms", "3000", "--",
            proot.absolutePath
        )
        arguments += AgentProotLaunchOptions.forRootfs(rootfs)
        arguments += listOf(
            "-b", "${projectRoot.absolutePath}:${AgentProotEnvironmentPaths.WORKSPACE_MOUNT}",
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
                guestReadyFile = guestReadyReceipt.guestFile,
                guestReadyNonce = guestReadyReceipt.nonce
            )
            return AgentProotTerminalLaunch(
                executable = broker.absolutePath,
                hostWorkingDirectory = projectRoot.absolutePath,
                arguments = arguments.toTypedArray(),
                environment = agentProotInteractiveEnvironment(
                    nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
                    tempRoot = tempRoot.absolutePath,
                    projectFolder = projectFolder,
                    sessionId = sessionId,
                    loaderPath = AgentProotNativeBinaryProvider.locateLoader(appContext)?.absolutePath
                ),
                guestReadyReceipt = guestReadyReceipt
            )
        } catch (failure: Throwable) {
            cleanupAgentProotGuestReadyReceipt(guestReadyReceipt)
            throw failure
        }
    }

    private fun requireConnected(sessionId: String): Session {
        val session = sessions[sessionId]
            ?: error("This Debian terminal session is no longer open.")
        require(session.connected && session.terminal.isRunning) {
            "This Debian terminal session is disconnected."
        }
        return session
    }

    private fun terminalChanged(terminal: TerminalSession) {
        val session = sessionFor(terminal) ?: return
        session.lastActivityAt = System.currentTimeMillis()
        notifyScreen(session)
        publish(session.conversationId)
    }

    private fun sessionFor(terminal: TerminalSession): Session? =
        sessions.values.firstOrNull { it.terminal === terminal }

    private fun notifyScreen(session: Session) {
        runCatching { session.screenListener?.invoke() }
    }

    private suspend fun finish(session: Session, status: String, errorCode: String?) {
        val shouldFinish = synchronized(session.stateLock) {
            if (session.finished) {
                false
            } else {
                session.finished = true
                session.connected = false
                if (!errorCode.isNullOrBlank()) {
                    session.error = appContext.getString(R.string.agent_proot_terminal_process_exited)
                }
                true
            }
        }
        if (!shouldFinish) return
        database.agentProotRunDao().finish(
            id = session.id,
            status = status,
            errorClass = errorCode,
            errorMessage = null
        )
        publish(session.conversationId)
        notifyScreen(session)
        AgentForegroundService.releaseRuntime(appContext)
    }

    private suspend fun failQueued(
        id: String,
        error: Throwable,
        phase: AgentProotTerminalStartupPhase
    ) {
        database.agentProotRunDao().finish(
            id = id,
            status = AgentProotRunStatus.FAILED,
            errorClass = agentProotTerminalFailureCode(phase, error),
            errorMessage = null
        )
    }

    private fun publish(conversationId: Long) {
        val snapshots = sessions.values
            .filter { it.conversationId == conversationId }
            .sortedBy { it.openedAt }
            .map(::snapshot)
        _states.update { current ->
            if (snapshots.isEmpty()) current - conversationId else current + (conversationId to snapshots)
        }
    }

    private fun snapshot(session: Session): WorkspaceTerminalUiState = WorkspaceTerminalUiState(
        workspaceRoot = AgentProotEnvironmentPaths.WORKSPACE_MOUNT,
        sessionId = session.id,
        displayName = session.displayName,
        backend = AgentWorkspaceBackendType.LOCAL_PROOT,
        isConnected = session.connected && session.terminal.isRunning,
        openedAt = session.openedAt,
        lastActivityAt = session.lastActivityAt,
        errorMessage = session.error
    )

    private fun clipboard(): ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AgentProotTerminal"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val TERM_GRACE_STEP_MS = 100L
        const val TERM_GRACE_STEPS = 30
        const val MAX_LOG_CHARS = 512
        val SHA256 = Regex("[a-fA-F0-9]{64}")
    }
}
