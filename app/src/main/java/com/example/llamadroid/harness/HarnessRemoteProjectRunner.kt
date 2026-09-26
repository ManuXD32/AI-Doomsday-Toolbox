package com.example.llamadroid.harness

import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentProjectRunEntity
import com.example.llamadroid.service.AgentLocalRuntimeType
import com.example.llamadroid.service.AgentLocalRunState
import com.example.llamadroid.service.AgentLocalWorkspaceSupport
import com.example.llamadroid.service.AgentRunConfig
import com.example.llamadroid.service.AgentRunConfigParser
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the version-1 project manifest against one captured REMOTE_SSH session.
 *
 * A remote run owns both the SSH process marker and (for web projects) its local
 * loopback tunnel. The workspace selected in the UI is never consulted after
 * [HarnessSessionScope] has been captured.
 */
internal class HarnessRemoteProjectRunner(private val runtime: HarnessAppRuntime) {
    private class RemoteRun(
        val conversationId: Long,
        val owner: String,
        val session: Session,
        val channel: ChannelExec,
        val output: BoundedOutput,
        val localPort: Int?,
        initial: AgentLocalRunState
    ) {
        @Volatile var current: AgentLocalRunState = initial
        @Volatile var monitor: Job? = null
        @Volatile var stopRequested = false
        val finished = AtomicBoolean(false)
    }

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val active = ConcurrentHashMap<Long, RemoteRun>()
    private val mutableStates = MutableStateFlow<Map<Long, AgentLocalRunState>>(emptyMap())
    val states: StateFlow<Map<Long, AgentLocalRunState>> = mutableStates.asStateFlow()

    suspend fun loadConfig(scope: HarnessSessionScope): AgentRunConfig = withContext(Dispatchers.IO) {
        require(scope.workspace.backend == "REMOTE_SSH") { "REMOTE_PROJECT_REQUIRED" }
        val manifestPath = scope.workspace.guestPath.trimEnd('/') + "/.adt/run.json"
        val raw = runtime.files.readBytes(scope, manifestPath, 64 * 1024).toString(Charsets.UTF_8)
        val config = AgentRunConfigParser.parse(raw)
        val entrypoint = scope.workspace.guestPath.trimEnd('/') + "/" + config.entrypoint
        val stat = runtime.files.invoke(scope, "workspace.stat", JSONObject().put("path", entrypoint)) as JSONObject
        require(!stat.optBoolean("directory")) { "RUN_ENTRYPOINT_MISSING" }
        require(!stat.optBoolean("symbolicLink")) { "RUN_ENTRYPOINT_SYMLINK" }
        if (stat.has("exists")) require(stat.optBoolean("exists")) { "RUN_ENTRYPOINT_MISSING" }
        config
    }

    suspend fun run(
        conversationId: Long,
        scope: HarnessSessionScope,
        config: AgentRunConfig
    ): AgentLocalRunState {
        var published: RemoteRun? = null
        try {
            return withContext(Dispatchers.IO) {
                lifecycle.withLock {
                    stopInternal(conversationId, force = true, terminalStatus = "FORCE_STOPPED")
                    reconcilePersistedRunLocked(conversationId, scope)
                    startInternal(conversationId, scope, config).also { published = active[conversationId] }
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can cancel at its return dispatch after a successful publication.
            // Only retire the exact run this invocation published, never a later replacement.
            withContext(NonCancellable + Dispatchers.IO) {
                lifecycle.withLock {
                    if (published != null && active[conversationId] === published) {
                        try { stopInternal(conversationId, force = true, terminalStatus = "STOPPED") }
                        catch (cleanup: Throwable) { cancelled.addSuppressed(cleanup) }
                    }
                }
            }
            throw cancelled
        }
    }

    /** Removes only this runner's in-memory terminal projection; Room remains authoritative. */
    suspend fun forgetTerminalState(conversationId: Long) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (!active.containsKey(conversationId)) {
                mutableStates.update { it - conversationId }
            }
        }
    }

    suspend fun check(conversationId: Long): AgentLocalRunState? = lifecycle.withLock {
        active[conversationId]?.let { run ->
            publishRunningLocked(run)
            return@withLock run.current
        }
        states.value[conversationId]?.let { return@withLock it }
        val saved = runtime.database.agentChatDao().getLatestProjectRun(conversationId)
            ?.takeIf { it.backend == "REMOTE_SSH" }
            ?: return@withLock null
        val state = saved.toState()
        if (state.status == "RUNNING") {
            val interrupted = state.copy(
                status = "INTERRUPTED",
                logs = appendLog(
                    state.logs,
                    runtime.applicationContext.getString(R.string.harness_remote_run_recovered)
                ),
                endedAt = System.currentTimeMillis()
            )
            publish(interrupted, persist = true)
            return@withLock interrupted
        }
        publish(state)
        state
    }

    suspend fun stop(conversationId: Long, force: Boolean = false): AgentLocalRunState? = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            stopInternal(conversationId, force, terminalStatus = if (force) "FORCE_STOPPED" else "STOPPED")
        }
    }

    suspend fun interrupted() = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            var firstFailure: Throwable? = null
            active.keys.toList().forEach { conversationId ->
                try {
                    stopInternal(conversationId, force = false, terminalStatus = "STOPPED")
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                }
            }
            firstFailure?.let { throw it }
        }
    }

    private suspend fun startInternal(
        conversationId: Long,
        scope: HarnessSessionScope,
        config: AgentRunConfig
    ): AgentLocalRunState {
        currentCoroutineContext().ensureActive()
        val owner = UUID.randomUUID().toString()
        val (session, directory) = runtime.files.openOwnedProcess(scope, owner, ".")
        var channel: ChannelExec? = null
        var localPort: Int? = null
        var registeredRun: RemoteRun? = null
        var publishedState: AgentLocalRunState? = null
        try {
            currentCoroutineContext().ensureActive()
            val selectedRemotePort = if (config.runtime == AgentLocalRuntimeType.WEB) {
                reserveRemotePort(scope)
            } else null
            val opened = session.openChannel("exec") as ChannelExec
            channel = opened
            val output = BoundedOutput()
            val stdout = opened.inputStream
            val stderr = opened.errStream
            opened.setCommand(
                when (config.runtime) {
                    AgentLocalRuntimeType.WEB -> HarnessRemoteRunCommand.web(directory, requireNotNull(selectedRemotePort), owner)
                    AgentLocalRuntimeType.PYTHON -> HarnessRemoteRunCommand.python(directory, config.entrypoint, config.args, owner)
                }
            )
            currentCoroutineContext().ensureActive()
            opened.connect(10_000)
            currentCoroutineContext().ensureActive()
            if (config.runtime == AgentLocalRuntimeType.WEB) {
                val forwardedPort = AgentLocalWorkspaceSupport.acquireLoopbackPort()
                localPort = forwardedPort
                session.setPortForwardingL(forwardedPort, "127.0.0.1", requireNotNull(selectedRemotePort))
                val previewPath = HarnessRemoteRunCommand.previewPath(config.entrypoint)
                check(awaitPreview(forwardedPort, previewPath)) { "REMOTE_PREVIEW_UNHEALTHY" }
            }
            val state = AgentLocalRunState(
                conversationId = conversationId,
                projectFolder = scope.workspace.projectFolder,
                runtime = config.runtime.name.lowercase(),
                entrypoint = config.entrypoint,
                uiMode = config.uiMode.name,
                status = "RUNNING",
                logs = runtime.applicationContext.getString(
                    R.string.harness_remote_run_started,
                    config.runtime.name.lowercase(Locale.US),
                    scope.workspace.guestPath
                ),
                previewUrl = localPort?.let { "http://127.0.0.1:$it${HarnessRemoteRunCommand.previewPath(config.entrypoint)}" },
                startedAt = System.currentTimeMillis()
            )
            val run = RemoteRun(
                conversationId = conversationId,
                owner = owner,
                session = session,
                channel = opened,
                output = output,
                localPort = localPort,
                initial = state
            )
            check(active.putIfAbsent(conversationId, run) == null) { "PROJECT_START_IN_PROGRESS" }
            registeredRun = run
            publishedState = state
            publish(state, persist = true)
            currentCoroutineContext().ensureActive()
            run.monitor = worker.launch { monitor(run, stdout, stderr) }
            currentCoroutineContext().ensureActive()
            return state
        } catch (cancelled: CancellationException) {
            registeredRun?.let { abortStart(conversationId, it) }
            withContext(NonCancellable) { closeResources(owner, session, channel, localPort) }
            publishedState?.let { rollbackStartState(it, cancelled = true) }
            throw cancelled
        } catch (failure: Throwable) {
            registeredRun?.let { abortStart(conversationId, it) }
            withContext(NonCancellable) { closeResources(owner, session, channel, localPort) }
            publishedState?.let { rollbackStartState(it, cancelled = false) }
            runtime.diagnostics.eventForConversation(
                conversationId, "remote_project_start", "FAILED", errorCode = failure.javaClass.simpleName
            )
            throw failure
        }
    }

    private fun abortStart(conversationId: Long, run: RemoteRun) {
        run.stopRequested = true
        run.finished.set(true)
        run.monitor?.cancel()
        active.remove(conversationId, run)
    }

    private suspend fun rollbackStartState(state: AgentLocalRunState, cancelled: Boolean) {
        val terminal = HarnessProjectRunStatePolicy.failedStartState(
            state,
            cancelled = cancelled,
            endedAt = System.currentTimeMillis()
        )
        mutableStates.update { current ->
            if (current[state.conversationId]?.startedAt == state.startedAt) {
                current + (state.conversationId to terminal)
            } else {
                current
            }
        }
        withContext(NonCancellable + Dispatchers.IO) {
            try { persist(terminal) } catch (_: Throwable) { /* Keep diagnostics and cleanup authoritative. */ }
        }
    }

    private suspend fun reconcilePersistedRunLocked(
        conversationId: Long,
        scope: HarnessSessionScope
    ) {
        val saved = runtime.database.agentChatDao().getLatestProjectRun(conversationId)
            ?.takeIf { it.backend == "REMOTE_SSH" }
        if (saved != null) {
            require(saved.projectFolder == scope.workspace.projectFolder) { "REMOTE_RUN_RECONCILIATION_REQUIRED" }
            if (saved.status == "RUNNING") {
                val interrupted = saved.toState().copy(
                    status = "INTERRUPTED",
                    logs = appendLog(saved.logs, runtime.applicationContext.getString(R.string.harness_remote_run_recovered)),
                    endedAt = System.currentTimeMillis(),
                    exitCode = null
                )
                publish(interrupted, persist = true)
            }
        }
        // A previous Check may already have marked the run interrupted. Its durable
        // inactive receipts still need reconciliation before another process starts.
        val pending = runtime.files.retryCleanup(
            includeActive = false,
            sessionId = scope.session.harnessSessionId
        )
        check(scope.session.harnessSessionId !in pending) { "SSH_CLEANUP_PENDING" }
    }

    private suspend fun monitor(run: RemoteRun, stdout: InputStream, stderr: InputStream) {
        try {
            coroutineScope {
                val output = async(Dispatchers.IO) { pump(stdout, run.output) }
                val errors = async(Dispatchers.IO) { pump(stderr, run.output) }
                while (!run.channel.isClosed) {
                    publishRunning(run)
                    delay(100)
                }
                output.await()
                errors.await()
            }
            val exitCode = run.channel.exitStatus.takeIf { it >= 0 }
            finish(
                run,
                status = when {
                    run.stopRequested -> "STOPPED"
                    exitCode == 0 -> "SUCCEEDED"
                    else -> "FAILED"
                },
                exitCode = exitCode
            )
        } catch (cancelled: CancellationException) {
            if (!run.stopRequested) finish(run, "INTERRUPTED", null)
            throw cancelled
        } catch (failure: Throwable) {
            runtime.diagnostics.eventForConversation(
                run.conversationId, "remote_project_monitor", "FAILED", errorCode = failure.javaClass.simpleName
            )
            finish(run, if (run.stopRequested) "STOPPED" else "FAILED", null, failure.message)
        }
    }

    private suspend fun finish(
        run: RemoteRun,
        status: String,
        exitCode: Int?,
        detail: String? = null
    ): Unit = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            if (!run.finished.compareAndSet(false, true)) return@withLock
            active.remove(run.conversationId, run)
            val logs = appendLog(run.output.text(), detail)
            val finished = run.current.copy(
                status = status,
                logs = logs,
                endedAt = System.currentTimeMillis(),
                exitCode = exitCode
            )
            run.current = finished
            var published = false
            try {
                publish(finished, persist = true)
                published = true
            } finally {
                val cleaned = closeResources(run.owner, run.session, run.channel, run.localPort)
                if (published && !cleaned) {
                    runCatching {
                        publish(
                            finished.copy(logs = appendLog(finished.logs, cleanupPendingMessage())),
                            persist = true
                        )
                    }
                }
            }
        }
    }

    private suspend fun stopInternal(
        conversationId: Long,
        force: Boolean,
        terminalStatus: String
    ): AgentLocalRunState? {
        val run = active.remove(conversationId) ?: return null
        if (!run.finished.compareAndSet(false, true)) return run.current
        run.stopRequested = true
        run.monitor?.cancel()
        val current = run.current
        val stopped = current.copy(
            status = terminalStatus,
            logs = appendLog(run.output.text(), if (force) {
                runtime.applicationContext.getString(R.string.harness_remote_run_force_stopped)
            } else {
                runtime.applicationContext.getString(R.string.harness_remote_run_stopped)
            }),
            endedAt = System.currentTimeMillis(),
            exitCode = null
        )
        run.current = stopped
        var result = stopped
        var published = false
        try {
            publish(stopped, persist = true)
            published = true
        } finally {
            val cleaned = closeResources(run.owner, run.session, run.channel, run.localPort)
            if (published && !cleaned) {
                val pending = stopped.copy(logs = appendLog(stopped.logs, cleanupPendingMessage()))
                runCatching {
                    publish(pending, persist = true)
                    result = pending
                }
            }
        }
        return result
    }

    private suspend fun closeResources(owner: String, session: Session, channel: ChannelExec?, localPort: Int?): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            if (localPort != null) runCatching { session.delPortForwardingL(localPort) }
            runCatching { channel?.disconnect() }
            val cleaned = withTimeoutOrNull(7_000) {
                runCatching { runtime.files.closeOwnedProcess(owner, session) }.isSuccess
            } ?: false
            if (!cleaned) runCatching { session.disconnect() }
            cleaned
        }

    private fun cleanupPendingMessage(): String =
        runtime.applicationContext.getString(R.string.harness_remote_run_cleanup_pending)

    private suspend fun reserveRemotePort(scope: HarnessSessionScope): Int {
        val result = runtime.files.execute(
            scope,
            HarnessRemoteRunCommand.portProbe(),
            timeoutMs = 10_000
        )
        check(result.optInt("exitCode", -1) == 0) { "REMOTE_PYTHON_REQUIRED" }
        return result.optString("output").trim().toIntOrNull()?.takeIf { it in 1024..65_535 }
            ?: error("REMOTE_PREVIEW_PORT_UNAVAILABLE")
    }

    private suspend fun awaitPreview(localPort: Int, path: String): Boolean = withContext(Dispatchers.IO) {
        repeat(40) {
            val ready = runCatching {
                (URL("http://127.0.0.1:$localPort$path").openConnection() as HttpURLConnection).run {
                    connectTimeout = 500
                    readTimeout = 500
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    try { responseCode in 200..399 } finally { disconnect() }
                }
            }.getOrDefault(false)
            if (ready) return@withContext true
            delay(250)
        }
        false
    }

    private suspend fun publishRunning(run: RemoteRun) = lifecycle.withLock {
        publishRunningLocked(run)
    }

    private fun publishRunningLocked(run: RemoteRun) {
        if (run.finished.get() || active[run.conversationId] !== run) return
        val state = run.current.copy(logs = run.output.text().takeLast(MAX_LOG_CHARS))
        if (state.logs == run.current.logs) return
        run.current = state
        mutableStates.update { it + (run.conversationId to state) }
    }

    private suspend fun publish(state: AgentLocalRunState, persist: Boolean = false) {
        active[state.conversationId]?.current = state
        mutableStates.update { it + (state.conversationId to state) }
        if (persist) persist(state)
    }

    private suspend fun persist(state: AgentLocalRunState) = withContext(Dispatchers.IO) {
        val dao = runtime.database.agentChatDao()
        val existing = dao.getLatestProjectRun(state.conversationId)
        val row = existing?.copy(
            projectFolder = state.projectFolder,
            backend = "REMOTE_SSH",
            prootEnvironmentId = null,
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(MAX_LOG_CHARS),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode,
            stopRequestedAt = if (state.status == "STOPPED" || state.status == "FORCE_STOPPED") System.currentTimeMillis() else existing.stopRequestedAt,
            forceStopRequestedAt = if (state.status == "FORCE_STOPPED") System.currentTimeMillis() else existing.forceStopRequestedAt,
            updatedAt = System.currentTimeMillis()
        ) ?: AgentProjectRunEntity(
            conversationId = state.conversationId,
            projectFolder = state.projectFolder,
            backend = "REMOTE_SSH",
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(MAX_LOG_CHARS),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode
        )
        if (existing == null) dao.insertProjectRun(row) else dao.updateProjectRun(row)
    }

    private fun pump(input: InputStream, output: BoundedOutput) {
        input.use {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) return
                output.write(buffer, 0, count)
            }
        }
    }

    private fun appendLog(base: String, detail: String?): String {
        val extra = detail?.trim().orEmpty()
        return if (extra.isBlank()) base.takeLast(MAX_LOG_CHARS)
        else (base.trimEnd() + "\n" + extra).takeLast(MAX_LOG_CHARS)
    }

    private fun AgentProjectRunEntity.toState() = AgentLocalRunState(
        conversationId = conversationId,
        projectFolder = projectFolder,
        runtime = runtime,
        entrypoint = entrypoint,
        uiMode = uiMode,
        status = status,
        logs = logs,
        previewUrl = previewUrl,
        startedAt = startedAt,
        endedAt = endedAt,
        exitCode = exitCode
    )

    private class BoundedOutput(private val maximum: Int = MAX_OUTPUT_CHARS) : java.io.OutputStream() {
        private val bytes = ByteArrayOutputStream(maximum)
        @Synchronized override fun write(value: Int) {
            if (bytes.size() < maximum) bytes.write(value)
        }
        @Synchronized override fun write(value: ByteArray, offset: Int, length: Int) {
            if (bytes.size() >= maximum) return
            bytes.write(value, offset, minOf(length, maximum - bytes.size()))
        }
        @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 1024 * 1024
        const val MAX_LOG_CHARS = 32 * 1024
    }
}

/** Shell-safe command construction for the scoped remote project runner. */
internal object HarnessRemoteRunCommand {
    fun portProbe(): String = "python3 -c 'import socket;s=socket.socket();s.bind((\"127.0.0.1\",0));print(s.getsockname()[1]);s.close()'"

    fun web(directory: String, remotePort: Int, owner: String): String =
        "cd ${quote(directory)} && ADT_HARNESS_REMOTE_OWNER=${quote(owner)} exec python3 -u -m http.server $remotePort --bind 127.0.0.1 --directory ${quote(directory)}"

    fun python(directory: String, entrypoint: String, args: List<String>, owner: String): String = buildString {
        append("cd ").append(quote(directory))
        append(" && ADT_HARNESS_REMOTE_OWNER=").append(quote(owner))
        append(" exec python3 ").append(quote(directory.trimEnd('/') + "/" + entrypoint))
        args.forEach { append(' ').append(quote(it)) }
    }

    fun previewPath(entrypoint: String): String = "/" + entrypoint.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
