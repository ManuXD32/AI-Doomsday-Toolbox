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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val AGENT_PROOT_TERMINALS_PER_PROJECT = 8
internal const val AGENT_PROOT_TERMINALS_PER_PROCESS = 24
internal const val AGENT_PROOT_ACTIVE_PTY_BIND = "__ADT_ACTIVE_PTY_BIND__"

internal fun canOpenAgentProotTerminal(projectActive: Int, processActive: Int): Boolean =
    projectActive in 0 until AGENT_PROOT_TERMINALS_PER_PROJECT &&
        processActive in 0 until AGENT_PROOT_TERMINALS_PER_PROCESS

/** Immutable launch material consumed by Termux's PTY-backed [TerminalSession]. */
internal data class AgentProotTerminalLaunch(
    val executable: String,
    val hostWorkingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>
)

internal fun agentProotInteractiveShellArguments(): List<String> = listOf(
    "-w", AgentProotEnvironmentPaths.WORKSPACE_MOUNT,
    "/bin/bash", "--noprofile", "--norc", "-i"
)

/** The PTY JNI replaces this opaque token with `host-slave:guest-slave` before exec. */
internal fun agentProotInteractivePtyBindArguments(): List<String> =
    listOf("-b", AGENT_PROOT_ACTIVE_PTY_BIND)

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
            val error = exitStatus.takeIf { it != 0 && !session.closing }
                ?.let { "Terminal exited with status $it." }
            scope.launch { finish(session, status, error) }
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
            runCatching {
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
                    failQueued(id, error)
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
                val launch = buildLaunch(id, environmentId, projectFolder, rootfs)
                val ordinal = activeForConversation(conversationId).size + 1
                val displayName = "${environment.displayName} · $ordinal"
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
                try {
                    withContext(Dispatchers.Main.immediate) {
                        terminal.initializeEmulator(DEFAULT_COLUMNS, DEFAULT_ROWS)
                    }
                } catch (error: Throwable) {
                    sessions.remove(id)
                    failQueued(id, error)
                    throw error
                }

                database.agentProotRunDao().insert(
                    queuedRow.copy(
                        status = AgentProotRunStatus.RUNNING,
                        processId = terminal.pid.takeIf { it > 0 },
                        startedAt = now,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                AgentForegroundService.retainRuntime(
                    appContext,
                    appContext.getString(R.string.agent_status_workspace_terminal_running, "/workspace")
                )
                publish(conversationId)
                snapshot(session)
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
        arguments += agentProotInteractiveShellArguments()
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
            )
        )
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

    private suspend fun finish(session: Session, status: String, error: String?) {
        val shouldFinish = synchronized(session.stateLock) {
            if (session.finished) {
                false
            } else {
                session.finished = true
                session.connected = false
                if (!error.isNullOrBlank()) session.error = error
                true
            }
        }
        if (!shouldFinish) return
        database.agentProotRunDao().finish(
            id = session.id,
            status = status,
            errorClass = error?.let { "TERMINAL_ERROR" },
            errorMessage = error?.take(1_000)
        )
        publish(session.conversationId)
        notifyScreen(session)
        AgentForegroundService.releaseRuntime(appContext)
    }

    private suspend fun failQueued(id: String, error: Throwable) {
        database.agentProotRunDao().finish(
            id = id,
            status = AgentProotRunStatus.FAILED,
            errorClass = "TERMINAL_START_ERROR",
            errorMessage = error.message?.take(1_000)
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
