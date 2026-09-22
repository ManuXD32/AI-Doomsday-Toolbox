package com.example.llamadroid.harness

import com.example.llamadroid.service.AgentWorkspaceBackendType
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.service.WorkspaceTerminalUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One repository for native and original-interface terminals in the shared Harness environment. */
class HarnessTerminals(
    private val scope: CoroutineScope,
    private val client: () -> HarnessClient?,
    private val diagnostics: HarnessDiagnostics,
    private val resolveBackend: suspend (String) -> AgentWorkspaceBackendType = { AgentWorkspaceBackendType.LOCAL_PROOT }
) {
    private data class Owner(
        val sessionId: String,
        val attachmentId: String,
        val screen: HarnessTerminalBuffer = HarnessTerminalBuffer(),
        val attached: CompletableDeferred<Unit> = CompletableDeferred(),
        var transport: HarnessTermuxTransportAdapter? = null,
    )
    private val owners = ConcurrentHashMap<String, Owner>()
    private val followers = ConcurrentHashMap<String, Job>()
    private val backends = ConcurrentHashMap<String, AgentWorkspaceBackendType>()
    private val mutations = Mutex()
    private data class Reply(val sessionId: String, val id: String, val attachmentId: String, val data: String)
    private val replies = Channel<Reply>(64)
    private val mutableStates = MutableStateFlow<Map<String, List<WorkspaceTerminalUiState>>>(emptyMap())
    val states = mutableStates.asStateFlow()

    init { scope.launch {
        for (reply in replies) {
            val owner = owners[key(reply.sessionId, reply.id)] ?: continue
            if (owner.attachmentId != reply.attachmentId) continue
            try {
                if (!owner.attached.isCompleted || owner.attached.isCancelled) continue
                rpc("write", args(owner, reply.id) { put("data", reply.data) })
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
                diagnostics.event(reply.sessionId, "terminal_reply", "INTERRUPTED", errorCode = error.javaClass.simpleName)
            }
        }
    } }

    suspend fun refresh(sessionId: String) {
        backends[sessionId] = resolveBackend(sessionId)
        val result = rpc("list", buildJsonObject { put("sessionId", sessionId) }) as JsonArray
        val infos = result.take(32).map { it.jsonObject }
        val ids = infos.map { it.required("id") }.toSet()
        mutableStates.update { current -> current + (sessionId to infos.map { info ->
            val old = current[sessionId]?.firstOrNull { it.sessionId == info.required("id") }
            info.toState(old, backends.getValue(sessionId))
        }) }
        owners.filterValues { it.sessionId == sessionId }.keys.filter { it.substringAfter('\u0000') !in ids }.forEach { key ->
            followers.remove(key)?.cancel(); owners.remove(key)
        }
    }

    suspend fun shells(sessionId: String): List<JsonObject> =
        (rpc("shells", buildJsonObject { put("agentId", sessionId) }) as JsonArray).take(32).map { it.jsonObject }

    suspend fun create(sessionId: String, shellPath: String? = null, id: String = UUID.randomUUID().toString()): String = mutations.withLock {
        require(id.isNotBlank() && id.length <= 128)
        rpc("create", buildJsonObject {
            put("agentId", sessionId)
            put("request", buildJsonObject { put("id", id); put("cols", 80); put("rows", 24); shellPath?.let { put("shellPath", it) } })
        })
        refresh(sessionId)
        attach(sessionId, id)
        id
    }

    fun attach(
        sessionId: String,
        id: String,
        reconnect: Boolean = false,
        transport: HarnessTermuxTransportAdapter? = null,
    ) {
        val key = key(sessionId, id)
        if (!reconnect && followers[key]?.isActive == true) {
            val owner = owners[key]
            if (transport != null && owner?.transport !== transport) {
                // A recreated Compose surface cannot recover ANSI/alternate-screen state from
                // the flattened compatibility transcript. Re-follow once so DSH sends a snapshot.
                attach(sessionId, id, reconnect = true, transport = transport)
            } else {
                owner?.let { if (transport != null) it.transport = transport }
            }
            return
        }
        followers.remove(key)?.cancel()
        val attachmentId = UUID.randomUUID().toString()
        lateinit var owner: Owner
        owner = Owner(sessionId, attachmentId, HarnessTerminalBuffer { data ->
            // The native emulator owns parser responses when present. Keeping the legacy buffer
            // reply path active as well would write every cursor response to DSH twice.
            if (owner.transport == null) {
                check(replies.trySend(Reply(sessionId, id, attachmentId, data)).isSuccess) {
                    "TERMINAL_REPLY_BUFFER_EXHAUSTED"
                }
            }
        }, transport = transport)
        owners[key] = owner
        followers[key] = scope.launch {
            try {
                val connection = requireNotNull(client()) { "HARNESS_NOT_RUNNING" }
                connection.stream("terminal", "follow", args(owner, id)).collect { frame ->
                    val value = frame.jsonObject
                    when (value.required("type")) {
                        "snapshot" -> {
                            val info = value.getValue("info").jsonObject
                            owner.screen.snapshot(value.required("screen"), info["cols"]?.jsonPrimitive?.intOrNull ?: 80, info["rows"]?.jsonPrimitive?.intOrNull ?: 24)
                            owner.transport?.snapshot(
                                value.required("screen"),
                                info["cols"]?.jsonPrimitive?.intOrNull ?: 80,
                                info["rows"]?.jsonPrimitive?.intOrNull ?: 24,
                            )
                            updateInfo(sessionId, id, info); owner.attached.complete(Unit)
                        }
                        "output" -> {
                            val data = value.required("data")
                            owner.screen.append(data)
                            owner.transport?.append(data)
                        }
                        "state" -> {
                            val info = value.getValue("info").jsonObject
                            val columns = info["cols"]?.jsonPrimitive?.intOrNull ?: 80
                            val rows = info["rows"]?.jsonPrimitive?.intOrNull ?: 24
                            owner.screen.resize(columns, rows)
                            owner.transport?.resize(columns, rows)
                            updateInfo(sessionId, id, info)
                        }
                    }
                    update(sessionId, id) { it.copy(transcript = owner.screen.text()) }
                }
            } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
                owner.attached.completeExceptionally(failure)
                update(sessionId, id) { it.copy(isConnected = false, isConnecting = false) }
                diagnostics.event(sessionId, "terminal_follow", "INTERRUPTED", errorCode = failure.javaClass.simpleName)
            } finally {
                if (owners[key] === owner) {
                    owner.attached.completeExceptionally(IllegalStateException("TERMINAL_STREAM_ENDED"))
                    update(sessionId, id) { it.copy(isConnected = false, isConnecting = false) }
                }
            }
        }
    }

    suspend fun send(sessionId: String, id: String, input: String, newline: Boolean = true) {
        require(input.toByteArray().size <= 64 * 1024)
        val owner = requireNotNull(owners[key(sessionId, id)]) { "TERMINAL_NOT_ATTACHED" }
        withTimeout(10_000) { owner.attached.await() }
        rpc("write", args(owner, id) { put("data", input + if (newline) "\r" else "") })
        if (newline && input.isNotBlank()) update(owner.sessionId, id) {
            it.copy(commandHistory = (it.commandHistory + input.take(4096)).takeLast(100))
        }
    }

    suspend fun awaitAttached(sessionId: String, id: String) = withTimeout(10_000) { requireNotNull(owners[key(sessionId, id)]).attached.await() }

    suspend fun rename(sessionId: String, id: String, title: String) {
        require(title.isNotBlank() && title.length <= 120)
        rpc("rename", buildJsonObject { put("agentId", sessionId); put("id", id); put("title", title) })
        refresh(sessionId)
    }

    suspend fun resize(sessionId: String, id: String, cols: Int, rows: Int) {
        val owner = requireNotNull(owners[key(sessionId, id)]) { "TERMINAL_NOT_ATTACHED" }
        rpc("resize", args(owner, id) { put("cols", cols.coerceIn(2, 500)); put("rows", rows.coerceIn(1, 200)) })
    }

    suspend fun close(sessionId: String, id: String) {
        rpc("close", buildJsonObject { put("agentId", sessionId); put("id", id) })
        followers.remove(key(sessionId, id))?.cancel(); owners.remove(key(sessionId, id))
        refresh(sessionId)
    }

    suspend fun closeAll(sessionId: String): Int {
        refresh(sessionId)
        val ids = states.value[sessionId].orEmpty().map { it.sessionId }
        ids.forEach { close(sessionId, it) }
        return ids.size
    }

    fun clear(sessionId: String, id: String) {
        owners[key(sessionId, id)]?.let { owner ->
            owner.screen.clear()
            owner.transport?.clear()
            update(owner.sessionId, id) { it.copy(transcript = "") }
        }
    }

    fun interrupted() {
        followers.values.forEach { it.cancel() }; followers.clear()
        owners.values.forEach { it.attached.cancel() }; owners.clear()
        mutableStates.update { current -> current.mapValues { (_, values) -> values.map { it.copy(isConnected = false, isConnecting = false) } } }
    }

    private suspend fun rpc(method: String, args: JsonObject): JsonElement = when (val result = requireNotNull(client()).call("terminal", method, args)) {
        is HarnessRpcResult.Success -> result.value
        is HarnessRpcResult.Failure -> error(result.error.code)
    }

    private fun args(owner: Owner, id: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("agentId", owner.sessionId); put("id", id); put("attachmentId", owner.attachmentId); extra()
    }

    private fun updateInfo(sessionId: String, id: String, info: JsonObject) = update(sessionId, id) {
        info.toState(it, backends[sessionId] ?: it.backend)
    }

    private fun update(sessionId: String, id: String, transform: (WorkspaceTerminalUiState) -> WorkspaceTerminalUiState) {
        mutableStates.update { current -> current + (sessionId to current[sessionId].orEmpty().map { if (it.sessionId == id) transform(it) else it }) }
    }

    private fun JsonObject.toState(old: WorkspaceTerminalUiState?, backend: AgentWorkspaceBackendType) = WorkspaceTerminalUiState(
        workspaceRoot = required("cwd"), sessionId = required("id"), displayName = required("title"),
        backend = backend, transcript = old?.transcript.orEmpty(),
        commandHistory = old?.commandHistory.orEmpty(), isConnected = required("state") == "running",
        openedAt = old?.openedAt ?: System.currentTimeMillis(), lastActivityAt = System.currentTimeMillis(),
        exitCode = get("exitCode")?.jsonPrimitive?.intOrNull ?: if (required("state") == "failed") -1 else null
    )

    private fun key(sessionId: String, id: String) = sessionId + '\u0000' + id

    private fun JsonObject.required(key: String): String = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull)
}
