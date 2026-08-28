package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.NativeProcessCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/** Pure port check shared by launch validation and focused unit tests. */
data class LlamaServerPortCheck(val available: Boolean, val error: String? = null)

fun checkLlamaServerPort(host: String, port: Int): LlamaServerPortCheck = runCatching {
    require(port in 1..65535) { "port must be in 1..65535" }
    ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(host, port))
    }
    LlamaServerPortCheck(available = true)
}.getOrElse { error ->
    LlamaServerPortCheck(false, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
}

/**
 * Remove every user/template-supplied port spelling and append exactly one managed --port pair.
 * The returned list retains all other arguments and never mutates model or tool flags.
 */
fun normalizeManagedLlamaServerPortArgs(args: List<String>, port: Int): List<String> {
    require(port in 1..65535) { "port must be in 1..65535" }
    val result = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val token = args[index]
        when {
            token == "--port" || token == "-p" -> {
                // A malformed template may leave the value out. Do not consume the next
                // option in that case; the managed pair below still makes the launch valid.
                index += 1
                if (index < args.size && !args[index].startsWith("-")) index += 1
            }
            token.startsWith("--port=") || token.startsWith("-p=") -> index += 1
            // llama.cpp also accepts the compact short spelling, e.g. -p8080. Restrict this
            // removal to digits so unrelated options such as --prompt remain untouched.
            token.matches(Regex("-p\\d+")) -> index += 1
            else -> {
                result += token
                index += 1
            }
        }
    }
    val insertion = result.indexOfFirst { it == "--host" || it == "-H" }
        .takeIf { it >= 0 } ?: result.size
    result.addAll(insertion, listOf("--port", port.toString()))
    return result
}

/**
 * Owns independent native children keyed by a stable session id. The runtime is deliberately
 * independent of Room: card/preset persistence is supplied by the repository layer, while this
 * class owns only processes, projected status and the durable session log.
 */
class LlamaServerSessionRuntime(private val context: Context) {
    private data class ActiveSession(
        val controller: ProcessController,
        var job: Job? = null,
        @Volatile var stopRequested: Boolean = false
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val active = ConcurrentHashMap<String, ActiveSession>()
    private val removing = mutableSetOf<String>()
    private val stateStore = LlamaServerSessionStateStore(appContext)
    private val ownerStore = LlamaServerSessionOwnerStore(appContext)
    private val _snapshots = MutableStateFlow(
        stateStore.readAll().associate { snapshot ->
            val owner = ownerStore.get(snapshot.sessionId)
            val ownerAlive = owner?.let {
                NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    rootPid = it.pid,
                    expectedStartTimeTicks = it.processStartTimeTicks,
                    expectedPort = it.port
                )
            } == true
            val normalized = if (ownerAlive && owner != null) {
                snapshot.copy(
                    status = LlamaServerSessionStatus.RUNNING,
                    port = owner.port,
                    pid = owner.pid,
                    error = null
                )
            } else if (snapshot.status in setOf(
                    LlamaServerSessionStatus.STARTING,
                    LlamaServerSessionStatus.LOADING,
                    LlamaServerSessionStatus.RUNNING
                )
            ) {
                snapshot.copy(
                    status = LlamaServerSessionStatus.STOPPED,
                    pid = null,
                    error = "Runtime restarted; start this server again."
                )
            } else snapshot
            if (owner != null && !ownerAlive) ownerStore.delete(snapshot.sessionId)
            normalized.sessionId to normalized
        }
    )
    val snapshots: StateFlow<Map<String, LlamaServerSessionSnapshot>> = _snapshots.asStateFlow()
    private val logs = LlamaServerSessionLogStore(appContext)

    init {
        ownerStore.readAll().forEach { owner ->
            if (NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(owner.pid, owner.processStartTimeTicks, owner.port)) {
                val existing = _snapshots.value[owner.sessionId]
                    ?: LlamaServerSessionSnapshot(sessionId = owner.sessionId)
                publish(
                    existing.copy(
                        status = LlamaServerSessionStatus.RUNNING,
                        port = owner.port,
                        pid = owner.pid,
                        error = null
                    )
                )
            } else {
                ownerStore.delete(owner.sessionId)
            }
        }
        _snapshots.value.values
            .filter { it.error == "Runtime restarted; start this server again." }
            .forEach(stateStore::write)
    }

    fun snapshot(sessionId: String): LlamaServerSessionSnapshot? = _snapshots.value[sessionId]

    fun readLogs(sessionId: String): List<String> = logs.read(sessionId)

    suspend fun start(
        sessionId: String,
        profile: LlamaServerLaunchProfile,
        portOverride: Int? = null
    ): Result<Unit> {
        return try {
            require(sessionId.isNotBlank()) { "sessionId is required" }
            val port = portOverride ?: profile.serverPort
            require(port in 1..65535) { "port must be in 1..65535" }
            require(profile.hasModel()) { "A model is required for this server session." }

            mutex.withLock {
                check(sessionId !in removing) { "The server card is being removed." }
                active.remove(sessionId)?.let { old ->
                    old.stopRequested = true
                    old.controller.stop()
                    old.job?.cancel()
                }
                ownerStore.get(sessionId)?.let { owner ->
                    if (NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(owner.pid, owner.processStartTimeTicks, owner.port)) {
                        val cleaned = NativeProcessCleanup.cleanupRecordedLlamaProcessTreeSync(
                            reason = "Replacing llama session $sessionId",
                            rootPid = owner.pid,
                            expectedStartTimeTicks = owner.processStartTimeTicks,
                            expectedPort = owner.port
                        )
                        if (cleaned == 0 || !checkLlamaServerPort(profile.host, port).available) {
                            throw IllegalStateException("The previous process for session $sessionId is still running.")
                        }
                    }
                    ownerStore.delete(sessionId)
                }
                val busy = _snapshots.value.values.firstOrNull {
                    it.sessionId != sessionId && it.port == port && it.status in setOf(
                        LlamaServerSessionStatus.STARTING,
                        LlamaServerSessionStatus.LOADING,
                        LlamaServerSessionStatus.RUNNING
                    )
                }
                if (busy != null || !checkLlamaServerPort(profile.host, port).available) {
                    throw IllegalStateException("Port $port is already in use by another server session.")
                }
                publish(
                    LlamaServerSessionSnapshot(
                        sessionId = sessionId,
                        status = LlamaServerSessionStatus.STARTING,
                        port = port,
                        command = null
                    )
                )
                val runtime = ActiveSession(ProcessController())
                active[sessionId] = runtime
                runtime.job = scope.launch {
                    launchProcess(sessionId, runtime, profile.copy(serverPort = port))
                }
            }
            Result.success(Unit)
        } catch (error: Throwable) {
            val existing = snapshot(sessionId) ?: LlamaServerSessionSnapshot(sessionId = sessionId)
            publish(
                existing.copy(
                    status = LlamaServerSessionStatus.ERROR,
                    pid = null,
                    error = error.message ?: error.javaClass.simpleName
                )
            )
            Result.failure(error)
        }
    }

    suspend fun stop(sessionId: String) {
        val runtime = mutex.withLock {
            active[sessionId]?.also { it.stopRequested = true }
        }
        runtime?.controller?.stop()
        runtime?.job?.join()
        if (runtime == null) {
            ownerStore.get(sessionId)?.let { owner ->
                val ownerAlive = NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    owner.pid,
                    owner.processStartTimeTicks,
                    owner.port
                )
                val cleaned = if (ownerAlive) NativeProcessCleanup.cleanupRecordedLlamaProcessTreeSync(
                        reason = "Stopping llama session $sessionId after service restart",
                        rootPid = owner.pid,
                        expectedStartTimeTicks = owner.processStartTimeTicks,
                        expectedPort = owner.port
                    ) else 0
                val stillOwned = NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    owner.pid,
                    owner.processStartTimeTicks,
                    owner.port
                )
                if (stillOwned || (ownerAlive && cleaned == 0)) {
                    publish(
                        (snapshot(sessionId) ?: LlamaServerSessionSnapshot(sessionId = sessionId)).copy(
                            status = LlamaServerSessionStatus.ERROR,
                            error = "The recorded llama.cpp process could not be stopped safely."
                        )
                    )
                    return
                }
                ownerStore.delete(sessionId)
            }
            val existing = snapshot(sessionId)
            if (existing != null && existing.status != LlamaServerSessionStatus.STOPPED) {
                publish(existing.copy(status = LlamaServerSessionStatus.STOPPED, pid = null))
            }
        }
    }

    suspend fun clearLogs(sessionId: String) {
        logs.clear(sessionId)
        snapshot(sessionId)?.let { publish(it.copy(logLineCount = 0)) }
    }

    /** Stop the exact child and delete its durable log only when the card/session is removed. */
    suspend fun remove(sessionId: String) {
        mutex.withLock { removing += sessionId }
        try {
            stop(sessionId)
            mutex.withLock { active.remove(sessionId) }
            val owner = ownerStore.get(sessionId)
            val ownerStillAlive = owner?.let {
                NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(it.pid, it.processStartTimeTicks, it.port)
            } == true
            if (!ownerStillAlive) {
                ownerStore.delete(sessionId)
                logs.delete(sessionId)
                stateStore.delete(sessionId)
                _snapshots.update { it - sessionId }
            }
        } finally {
            mutex.withLock { removing -= sessionId }
        }
    }

    fun close() {
        scope.coroutineContext.cancelChildren()
        runCatching {
            active.values.forEach { it.controller.stop() }
            active.clear()
        }
    }

    private suspend fun launchProcess(
        sessionId: String,
        runtime: ActiveSession,
        profile: LlamaServerLaunchProfile
    ) {
        try {
            val binary = BinaryRepository(appContext).getExecutable(profile.nativeBinarySelection)
                ?: throw IllegalStateException("Binary not found. Please ensure binaries are extracted.")
            val config = profile.toLlamaConfig().copy(
                port = profile.serverPort,
                host = profile.host,
                customFlags = profile.customFlags
            )
            val rawArgs = if (profile.commandTemplate.isNullOrBlank()) {
                runtime.controller.getCommand(binary.absolutePath, config)
            } else {
                runtime.controller.renderCommandTemplate(profile.commandTemplate, binary.absolutePath, config)
            }
            val args = normalizeManagedLlamaServerPortArgs(rawArgs, profile.serverPort)
            val command = runtime.controller.buildCommandString(args)
            if (isCurrent(sessionId, runtime)) {
                publish(snapshot(sessionId)?.copy(command = command) ?: LlamaServerSessionSnapshot(
                    sessionId = sessionId,
                    status = LlamaServerSessionStatus.STARTING,
                    port = profile.serverPort,
                    command = command
                ))
            }
            val result = runtime.controller.start(
                binaryPath = binary.absolutePath,
                config = config,
                filesDir = appContext.filesDir,
                runtimeWorkingDir = File(
                    appContext.filesDir,
                    "llama_server_sessions/${sessionId.replace(Regex("[^A-Za-z0-9._:-]"), "_")}"
                ).apply { mkdirs() },
                customArgs = args,
                onState = { state ->
                    if (isCurrent(sessionId, runtime)) {
                        publishFromState(sessionId, state, profile.serverPort, runtime.controller.ownedChildPid())
                    }
                },
                onClearServerLogs = {},
                onServerLog = { line ->
                    if (isCurrent(sessionId, runtime)) logs.append(sessionId, line)
                },
                logNativeOutputToDebug = false,
                shouldStop = { runtime.stopRequested },
                onOwnedProcessStarted = { pid, startTicks ->
                    if (isCurrent(sessionId, runtime) && startTicks != null) {
                        ownerStore.write(
                            LlamaServerSessionOwner(
                                sessionId = sessionId,
                                pid = pid,
                                processStartTimeTicks = startTicks,
                                port = profile.serverPort
                            )
                        )
                    }
                    if (isCurrent(sessionId, runtime)) {
                        snapshot(sessionId)?.let { publish(it.copy(pid = pid)) }
                    }
                }
            )
            if (!isCurrent(sessionId, runtime)) return
            val finalSnapshot = snapshot(sessionId)
            if (finalSnapshot?.status != LlamaServerSessionStatus.ERROR) {
                publish(
                    (finalSnapshot ?: LlamaServerSessionSnapshot(sessionId = sessionId))
                        .copy(
                            status = if (result.stoppedIntentionally) LlamaServerSessionStatus.STOPPED
                            else if (result.becameReady) LlamaServerSessionStatus.RUNNING
                            else LlamaServerSessionStatus.ERROR,
                            pid = null,
                            error = result.startupFailureMessage
                        )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DebugLog.log("LlamaServerSessionRuntime[$sessionId] failed: ${error.javaClass.simpleName}")
            if (isCurrent(sessionId, runtime)) {
                publish(
                    (snapshot(sessionId) ?: LlamaServerSessionSnapshot(sessionId = sessionId)).copy(
                        status = LlamaServerSessionStatus.ERROR,
                        pid = null,
                        error = error.message ?: error.javaClass.simpleName
                    )
                )
            }
        } finally {
            mutex.withLock {
                // A quick restart can replace this runtime before its cancelled job reaches
                // finally. Only the still-current controller may clear ownership/state; an old
                // job must never erase the replacement session's PID record.
                if (active[sessionId]?.controller === runtime.controller) {
                    ownerStore.delete(sessionId)
                    active.remove(sessionId)
                }
            }
        }
    }

    private fun publishFromState(sessionId: String, state: ServerState, port: Int, pid: Int) {
        val current = snapshot(sessionId) ?: LlamaServerSessionSnapshot(sessionId = sessionId)
        publish(
            current.copy(
                status = state.toLlamaServerSessionStatus(),
                port = (state as? ServerState.Running)?.port ?: current.port ?: port,
                pid = pid.takeIf { it > 0 },
                progress = (state as? ServerState.Loading)?.progress,
                statusText = (state as? ServerState.Loading)?.status,
                error = (state as? ServerState.Error)?.message
            )
        )
    }

    private fun isCurrent(sessionId: String, runtime: ActiveSession): Boolean = active[sessionId] === runtime

    private fun publish(snapshot: LlamaServerSessionSnapshot) {
        val updated = snapshot.copy(updatedAt = System.currentTimeMillis())
        _snapshots.update { it + (updated.sessionId to updated) }
        stateStore.write(updated)
    }
}
