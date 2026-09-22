package com.example.llamadroid.harness

import com.example.llamadroid.service.AgentLocalProjectRunner
import com.example.llamadroid.service.AgentLocalRunState
import com.example.llamadroid.service.AgentLocalRuntimeCapabilities
import com.example.llamadroid.service.AgentLocalRuntimeType
import com.example.llamadroid.service.AgentRunConfigParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Retains the canonical Run/Preview controls across local and captured SSH workspaces. */
class HarnessProjectRuns(private val runtime: HarnessAppRuntime) {
    private data class PythonRun(val sessionId: String, val terminalId: String)
    private val python = ConcurrentHashMap<Long, PythonRun>()
    private val remote = HarnessRemoteProjectRunner(runtime)
    private val staticPreviews = AgentLocalProjectRunner(runtime.applicationContext)
    private val lock = Mutex()
    private val starters = ConcurrentHashMap<Long, Job>()
    private val mutableStates = MutableStateFlow<Map<Long, AgentLocalRunState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init {
        var staticStateIds = emptySet<Long>()
        runtime.scope.launch { staticPreviews.states.collect { previews ->
            val previousStaticStateIds = staticStateIds
            staticStateIds = previews.keys
            val remoteSnapshot = remote.states.value
            mutableStates.update { current ->
                HarnessProjectRunStatePolicy.mergeOwnedStates(
                    current = current,
                    previousOwnedIds = previousStaticStateIds,
                    nextOwned = previews,
                    competingOwned = remoteSnapshot,
                    hiddenIds = python.keys
                )
            }
        } }
        var remoteStateIds = emptySet<Long>()
        runtime.scope.launch { remote.states.collect { previews ->
            val previousRemoteStateIds = remoteStateIds
            remoteStateIds = previews.keys
            val staticSnapshot = staticPreviews.states.value
            mutableStates.update { current ->
                HarnessProjectRunStatePolicy.mergeOwnedStates(
                    current = current,
                    previousOwnedIds = previousRemoteStateIds,
                    nextOwned = previews,
                    competingOwned = staticSnapshot,
                    hiddenIds = python.keys
                )
            }
        } }
        runtime.scope.launch { runtime.terminals.states.collect { terminals ->
            python.forEach { (conversationId, owner) ->
                terminals[owner.sessionId]?.firstOrNull { it.sessionId == owner.terminalId }?.let { terminal ->
                    mutableStates.update { current -> current[conversationId]?.let { old -> current + (conversationId to old.copy(
                        logs = terminal.transcript.takeLast(64 * 1024),
                        status = when { terminal.isConnected -> "RUNNING"; terminal.exitCode == 0 -> "SUCCEEDED"; terminal.exitCode != null -> "FAILED"; else -> "INTERRUPTED" },
                        exitCode = terminal.exitCode, endedAt = if (terminal.isConnected) null else old.endedAt ?: System.currentTimeMillis()
                    )) } ?: current }
                }
            }
        } }
    }

    suspend fun run(conversationId: Long): AgentLocalRunState = coroutineScope {
        val job = requireNotNull(currentCoroutineContext()[Job])
        check(starters.putIfAbsent(conversationId, job) == null) { "PROJECT_START_IN_PROGRESS" }
        try { lock.withLock { runOwned(conversationId) } }
        finally { starters.remove(conversationId, job) }
    }

    private suspend fun runOwned(conversationId: Long): AgentLocalRunState = kotlin.run {
        check(runtime.client != null) { "HARNESS_NOT_RUNNING" }
        check(runtime.database.harnessDao().runtime()?.state == "RUNNING") { "HARNESS_NOT_RUNNING" }
        val sessionId = requireNotNull(runtime.database.harnessDao().sessionForConversation(conversationId)).harnessSessionId
        val captured = runtime.workspaces.scope(sessionId)
        val config = if (captured.localRoot != null) {
            val configFile = captured.localFile(".adt/run.json")
            require(configFile.isFile && configFile.length() <= 64 * 1024) { "RUN_CONFIGURATION_REQUIRED" }
            AgentRunConfigParser.parse(configFile.readText())
                .also { require(captured.localFile(it.entrypoint).isFile) { "RUN_ENTRYPOINT_MISSING" } }
        } else {
            remote.loadConfig(captured)
        }
        if (captured.localRoot == null) {
            // The remote runner must see a persisted RUNNING row before any check()
            // converts it to INTERRUPTED and skips its session-scoped cleanup.
            stopOwned(conversationId, reconcileRemoteState = false)
        } else {
            stopOwned(conversationId)
        }
        if (captured.localRoot != null) {
            remote.forgetTerminalState(conversationId)
            if (config.runtime == AgentLocalRuntimeType.WEB) {
                staticPreviews.runProject(conversationId, captured.workspace.projectFolder, AgentLocalRuntimeCapabilities()).getOrThrow()
            } else {
                val id = java.util.UUID.randomUUID().toString()
                python[conversationId] = PythonRun(sessionId, id)
                val state = AgentLocalRunState(conversationId, captured.workspace.projectFolder, "python", config.entrypoint,
                    "console", "RUNNING", "", startedAt = System.currentTimeMillis())
                mutableStates.update { it + (conversationId to state) }
                try {
                    runtime.terminals.create(sessionId, "/bin/bash", id)
                    // Follow first acquires input ownership. The write waits for that snapshot below.
                    runtime.terminals.awaitAttached(sessionId, id)
                    val command = "exec python3 " + quote(captured.workspace.guestPath + "/" + config.entrypoint) +
                        config.args.joinToString("") { " " + quote(it) }
                    runtime.terminals.send(sessionId, id, command)
                } catch (failure: Exception) {
                    withContext(NonCancellable) { withTimeoutOrNull(2_000) {
                        try { runtime.terminals.close(sessionId, id) }
                        catch (_: Exception) { /* The terminal remains discoverable for explicit cleanup. */ }
                    } }
                    python.remove(conversationId)
                    mutableStates.update { it + (conversationId to state.copy(
                        status = if (failure is CancellationException) "STOPPED" else "FAILED", endedAt = System.currentTimeMillis())) }
                    throw failure
                }
                state
            }
        } else {
            remote.run(conversationId, captured, config)
        }
    }

    suspend fun check(conversationId: Long): AgentLocalRunState {
        val backend = backendForConversation(conversationId)
        if (backend == "REMOTE_SSH") {
            remote.check(conversationId)?.let { return it }
            error("NO_REMOTE_PROJECT_RUN")
        }
        if (backend == null) {
            remote.check(conversationId)?.let { return it }
        }
        python[conversationId]?.let { runtime.terminals.refresh(it.sessionId) }
        if (python.containsKey(conversationId)) return requireNotNull(states.value[conversationId])
        val persisted = runtime.database.agentChatDao().getLatestProjectRun(conversationId)
        if (persisted?.backend == "REMOTE_SSH") {
            remote.forgetTerminalState(conversationId)
            error("NO_LOCAL_PROJECT_RUN")
        }
        return staticPreviews.checkProject(conversationId).getOrThrow()
    }

    suspend fun stop(conversationId: Long): AgentLocalRunState? {
        starters[conversationId]?.cancel(CancellationException("PROJECT_STOPPED"))
        return lock.withLock {
            stopOwned(conversationId) ?: if (backendForConversation(conversationId) !in setOf("LOCAL_PROOT", "LOCAL_SANDBOX")) {
                remote.check(conversationId)
            } else {
                null
            }
        }
    }

    private suspend fun stopOwned(
        conversationId: Long,
        reconcileRemoteState: Boolean = true
    ): AgentLocalRunState? {
        val backend = backendForConversation(conversationId)
        remote.stop(conversationId)?.let { return it }
        if ((backend == "REMOTE_SSH" || backend == null) && reconcileRemoteState) {
            return remote.check(conversationId)
        }
        python[conversationId]?.let {
            runtime.terminals.close(it.sessionId, it.terminalId)
            python.remove(conversationId, it)
        }
        if (staticPreviews.states.value.containsKey(conversationId)) staticPreviews.stopProject(conversationId, true).getOrThrow()
        val old = mutableStates.value[conversationId] ?: return null
        return HarnessProjectRunStatePolicy.stopIfActive(old, System.currentTimeMillis())
            ?.also { state -> mutableStates.update { it + (conversationId to state) } }
            ?: old
    }

    /** Called after native environment cleanup; no request to the potentially frozen API is required. */
    suspend fun interrupted() = withContext(NonCancellable) {
        cancelAndJoinStarters("HARNESS_STOPPED")
        lock.withLock {
            var firstFailure: Throwable? = null
            try {
                remote.interrupted()
            } catch (failure: Throwable) {
                firstFailure = failure
            }
            staticPreviews.states.value.keys.forEach { id ->
                try { staticPreviews.stopProject(id, true).getOrThrow() }
                catch (cancel: CancellationException) { if (firstFailure == null) firstFailure = cancel }
                catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure
                    runtime.diagnostics.eventForConversation(id, "preview_cleanup", "FAILED", errorCode = failure.javaClass.simpleName)
                }
            }
            python.clear()
            val remoteFinal = remote.states.value
            val localFinal = staticPreviews.states.value
            mutableStates.update { current ->
                current.mapValues { (conversationId, value) ->
                    remoteFinal[conversationId] ?: localFinal[conversationId] ?:
                        value.takeIf { it.status in HarnessProjectRunStatePolicy.TERMINAL_STATUSES }
                            ?: value.copy(status = "STOPPED", endedAt = System.currentTimeMillis())
                }
            }
            firstFailure?.let { throw it }
        }
    }

    /** Closes app-owned local preview listeners before the runtime reports STOPPED. */
    suspend fun stopLocalPreviewsForEnvironment() {
        cancelAndJoinStarters("HARNESS_STOPPED")
        lock.withLock {
            var firstFailure: Throwable? = null
            staticPreviews.states.value
                .filterValues { it.status == "RUNNING" }
                .keys
                .forEach { conversationId ->
                    try {
                        staticPreviews.stopProject(conversationId, force = true).getOrThrow()
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                        if (failure !is CancellationException) {
                            runtime.diagnostics.eventForConversation(
                                conversationId,
                                "preview_cleanup",
                                "FAILED",
                                errorCode = failure.javaClass.simpleName
                            )
                        }
                    }
                }
            firstFailure?.let { throw it }
        }
    }

    private suspend fun cancelAndJoinStarters(reason: String) {
        val jobs = starters.values.toList().distinct()
        jobs.forEach { it.cancel(CancellationException(reason)) }
        jobs.forEach { it.join() }
    }

    private suspend fun backendForConversation(conversationId: Long): String? {
        val session = runtime.database.harnessDao().sessionForConversation(conversationId) ?: return null
        return runtime.workspaces.scope(session.harnessSessionId).workspace.backend
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

internal object HarnessProjectRunStatePolicy {
    val TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED", "STOPPED", "FORCE_STOPPED", "INTERRUPTED")

    fun stopIfActive(state: AgentLocalRunState, endedAt: Long): AgentLocalRunState? =
        state.takeIf { it.status !in TERMINAL_STATUSES }
            ?.copy(status = "STOPPED", endedAt = endedAt)

    fun failedStartState(state: AgentLocalRunState, cancelled: Boolean, endedAt: Long): AgentLocalRunState =
        state.copy(status = if (cancelled) "STOPPED" else "FAILED", endedAt = endedAt, exitCode = null)

    fun mergeOwnedStates(
        current: Map<Long, AgentLocalRunState>,
        previousOwnedIds: Set<Long>,
        nextOwned: Map<Long, AgentLocalRunState>,
        competingOwned: Map<Long, AgentLocalRunState>,
        hiddenIds: Set<Long> = emptySet()
    ): Map<Long, AgentLocalRunState> {
        val merged = current.filterKeys { it !in previousOwnedIds || it in hiddenIds }.toMutableMap()
        (competingOwned.keys + nextOwned.keys).filterNot { it in hiddenIds }.forEach { id ->
            // Collectors can resume out of order. Publication order must not let an
            // older static-preview row replace a newer remote run (or vice versa).
            merged[id] = listOfNotNull(current[id], competingOwned[id], nextOwned[id]).maxWith(
                compareBy<AgentLocalRunState> { it.startedAt ?: Long.MIN_VALUE }
                    .thenBy { it.endedAt ?: Long.MIN_VALUE }
                    .thenBy { if (it.status in TERMINAL_STATUSES) 1 else 0 }
            )
        }
        return merged
    }
}
