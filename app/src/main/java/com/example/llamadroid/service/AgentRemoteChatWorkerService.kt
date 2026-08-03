package com.example.llamadroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.data.SettingsRepository
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object AgentRemoteWorkerProtocol {
    const val MSG_START = 1
    const val MSG_STATUS = 2
    const val MSG_DONE = 3
    const val MSG_ERROR = 4
    const val MSG_CANCEL = 5
    const val MSG_OPEN_SESSION = 6
    const val MSG_CLOSE_SESSION = 7
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_CONVERSATION_ID = "conversation_id"
    const val KEY_ROOT_TURN_ID = "root_turn_id"
    const val KEY_RUNTIME_EPOCH = "runtime_epoch"
    const val KEY_INVOCATION_ID = "invocation_id"
    const val KEY_REQUEST_PATH = "request_path"
    const val KEY_RESULT_PATH = "result_path"
    const val KEY_STREAM_PATH = "stream_path"
    const val KEY_TEXT = "text"
}

internal data class AgentRemoteChatRequest(
    val baseUrl: String,
    val messages: List<OllamaService.ChatMessage>,
    val tools: List<AgentTool>,
    val modelLabel: String?,
    val thinkingEnabled: Boolean,
    val maxTokens: Int?,
    val samplingParams: LlamaServerSamplingParams,
    val requestOptions: LlamaServerRequestOptions,
    val slotOwner: LlamaSlotOwnerKey?,
    val slotAffinityMode: LlamaSlotAffinityMode,
    /** Correlation metadata only; it never carries prompt or message content. */
    val conversationId: String = "",
    val rootTurnId: String = "",
    val runtimeEpoch: Long = -1L,
    val invocationId: String = ""
)

internal data class AgentRemoteStreamSnapshot(
    /** Monotonic per-request revision; the client publishes each revision at most once. */
    val revision: Long = 0L,
    val content: String = "",
    val thinking: String = "",
    val promptProcessed: Int = 0,
    val promptTotal: Int = 0,
    val promptCached: Int = 0,
    val promptTimeMs: Long = 0L,
    val truncated: Boolean = false
)

/**
 * Isolates remote HTTP/SSE parsing from the main UI process. Binder messages carry metadata only;
 * bounded app-private files carry the request and final result.
 */
class AgentRemoteChatWorkerService : Service() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val chatServices = ConcurrentHashMap<String, LlamaServerChatService>()
    /** A session deliberately outlives an individual model request. */
    private val openSessions = ConcurrentHashMap<String, WorkerSessionMetadata>()
    private val clientDeathRecipients = ConcurrentHashMap<String, Pair<IBinder, IBinder.DeathRecipient>>()
    private val sessionCleanupMutex = Mutex()
    private val inbound = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            AgentRemoteWorkerProtocol.MSG_OPEN_SESSION -> {
                openSession(message)
                true
            }
            AgentRemoteWorkerProtocol.MSG_START -> {
                startRequest(message)
                true
            }
            AgentRemoteWorkerProtocol.MSG_CLOSE_SESSION -> {
                closeSession(message)
                true
            }
            AgentRemoteWorkerProtocol.MSG_CANCEL -> {
                val id = message.data.getString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID).orEmpty()
                chatServices[id]?.stopGeneration()
                jobs[id]?.cancel()
                recordLifecycle(id, "cancelling", requestSessions[id])
                true
            }
            else -> false
        }
    })

    override fun onCreate() {
        super.onCreate()
        DebugLog.log("[AgentRemoteWorker] service_created pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = inbound.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DebugLog.log("[AgentRemoteWorker] service_destroyed pid=${android.os.Process.myPid()} jobs=${jobs.size} sessions=${openSessions.size}")
        chatServices.values.forEach(LlamaServerChatService::stopGeneration)
        clientDeathRecipients.values.forEach { (binder, recipient) ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        clientDeathRecipients.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun startRequest(message: Message) {
        val reply = message.replyTo ?: return
        val id = message.data.getString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID).orEmpty()
        val sessionId = message.data.getString(AgentRemoteWorkerProtocol.KEY_SESSION_ID).orEmpty()
        val requestPath = message.data.getString(AgentRemoteWorkerProtocol.KEY_REQUEST_PATH).orEmpty()
        val resultPath = message.data.getString(AgentRemoteWorkerProtocol.KEY_RESULT_PATH).orEmpty()
        val streamPath = message.data.getString(AgentRemoteWorkerProtocol.KEY_STREAM_PATH).orEmpty()
        if (id.isBlank() || sessionId.isBlank() || requestPath.isBlank() || resultPath.isBlank() || streamPath.isBlank()) {
            reply.sendRemoteEvent(AgentRemoteWorkerProtocol.MSG_ERROR, id, "Invalid remote Agent request")
            return
        }
        if (openSessions[sessionId] == null) {
            reply.sendRemoteEvent(AgentRemoteWorkerProtocol.MSG_ERROR, id, "Remote Agent session is not open")
            recordLifecycle(id, "rejected", sessionId = sessionId)
            return
        }
        jobs[id]?.cancel()
        requestSessions[id] = sessionId
        val chatService = LlamaServerChatService()
        chatServices[id] = chatService
        recordLifecycle(id, "started", sessionId = sessionId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val requestFile = requirePrivateBoundedFile(requestPath, ".request.json")
                val resultFile = requirePrivateBoundedFile(resultPath, ".result.json", allowMissing = true)
                val streamFile = requirePrivateBoundedFile(streamPath, ".stream.json", allowMissing = true)
                val request = gson.fromJson(requestFile.readText(), AgentRemoteChatRequest::class.java)
                val contentBuffer = StringBuilder()
                val thinkingBuffer = StringBuilder()
                var contentChars = 0L
                var thinkingChars = 0L
                var promptProgress: LlamaPromptProcessingProgress? = null
                var lastStatusAt = 0L
                var snapshotRevision = 0L
                fun publishSnapshot(force: Boolean = false) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!force && now - lastStatusAt < STATUS_INTERVAL_MS) return
                    lastStatusAt = now
                    val contentText = contentBuffer.toString()
                    val thinkingText = thinkingBuffer.toString()
                    val snapshot = AgentRemoteStreamSnapshot(
                        revision = ++snapshotRevision,
                        content = contentText.take(MAX_STREAM_SECTION_CHARS),
                        thinking = thinkingText.take(MAX_STREAM_SECTION_CHARS),
                        promptProcessed = promptProgress?.processed ?: 0,
                        promptTotal = promptProgress?.total ?: 0,
                        promptCached = promptProgress?.cached ?: 0,
                        promptTimeMs = promptProgress?.timeMs ?: 0L,
                        truncated = contentChars > contentBuffer.length.toLong() ||
                            thinkingChars > thinkingBuffer.length.toLong()
                    )
                    writeAtomically(streamFile, gson.toJson(snapshot))
                }
                val result = chatService.chatWithToolsStreaming(
                    baseUrl = request.baseUrl,
                    messages = request.messages,
                    tools = request.tools,
                    modelLabel = request.modelLabel,
                    thinkingEnabled = request.thinkingEnabled,
                    maxTokens = request.maxTokens,
                    samplingParams = request.samplingParams,
                    requestOptions = request.requestOptions,
                    slotOwner = request.slotOwner,
                    slotAffinityMode = request.slotAffinityMode,
                    onPromptProgress = { progress ->
                        coroutineContext.ensureActive()
                        promptProgress = progress
                        publishSnapshot()
                    },
                    onChunk = { contentDelta, thinkingDelta ->
                        coroutineContext.ensureActive()
                        contentDelta?.let(this@AgentRemoteChatWorkerService::checkStreamCapacity)
                        thinkingDelta?.let(this@AgentRemoteChatWorkerService::checkStreamCapacity)
                        contentDelta?.let { delta ->
                            contentChars += delta.length
                            val remaining = MAX_STREAM_SECTION_CHARS - contentBuffer.length
                            if (remaining > 0) contentBuffer.append(delta.take(remaining))
                        }
                        thinkingDelta?.let { delta ->
                            thinkingChars += delta.length
                            val remaining = MAX_STREAM_SECTION_CHARS - thinkingBuffer.length
                            if (remaining > 0) thinkingBuffer.append(delta.take(remaining))
                        }
                        publishSnapshot()
                    }
                ).getOrThrow()
                publishSnapshot(force = true)
                val json = gson.toJson(result)
                require(json.length <= MAX_RESULT_CHARS) { "Remote Agent result exceeded the bounded limit" }
                resultFile.parentFile?.mkdirs()
                resultFile.writeText(json)
                reply.sendRemoteEvent(
                    AgentRemoteWorkerProtocol.MSG_DONE,
                    id,
                    "complete contentChars=${contentBuffer.length} thinkingChars=${thinkingBuffer.length}"
                )
                recordLifecycle(id, "completed", sessionId = sessionId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                recordLifecycle(id, "cancelled", sessionId = sessionId)
                throw cancelled
            } catch (error: Throwable) {
                DebugLog.log("AgentRemoteChatWorkerService: ${error.javaClass.simpleName}: ${error.message}")
                reply.sendRemoteEvent(
                    AgentRemoteWorkerProtocol.MSG_ERROR,
                    id,
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}"
                )
                recordLifecycle(id, "failed", sessionId, error.javaClass.simpleName)
            } finally {
                jobs.remove(id)
                chatServices.remove(id)
                requestSessions.remove(id)
                maybeStopAfterCleanup()
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun openSession(message: Message) {
        val reply = message.replyTo ?: return
        val sessionId = message.data.getString(AgentRemoteWorkerProtocol.KEY_SESSION_ID).orEmpty()
        if (sessionId.isBlank()) {
            reply.sendRemoteEvent(AgentRemoteWorkerProtocol.MSG_ERROR, "", "Invalid remote Agent session")
            return
        }
        val metadata = WorkerSessionMetadata(
            conversationId = message.data.getString(AgentRemoteWorkerProtocol.KEY_CONVERSATION_ID).orEmpty(),
            rootTurnId = message.data.getString(AgentRemoteWorkerProtocol.KEY_ROOT_TURN_ID).orEmpty(),
            runtimeEpoch = message.data.getLong(AgentRemoteWorkerProtocol.KEY_RUNTIME_EPOCH, -1L),
            invocationId = message.data.getString(AgentRemoteWorkerProtocol.KEY_INVOCATION_ID).orEmpty()
        )
        openSessions.putIfAbsent(sessionId, metadata)
        clientDeathRecipients.computeIfAbsent(sessionId) {
            val clientBinder = reply.binder
            val recipient = IBinder.DeathRecipient {
                scope.launch { closeSessionById(sessionId, "client_binder_died") }
            }
            runCatching { clientBinder.linkToDeath(recipient, 0) }
                .onFailure { scope.launch { closeSessionById(sessionId, "client_binder_already_dead") } }
            clientBinder to recipient
        }
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_remote_worker",
            event = "session_opened",
            details = metadata.details(sessionId, jobs.size, openSessions.size)
        )
        reply.sendRemoteEvent(AgentRemoteWorkerProtocol.MSG_STATUS, sessionId, "session_open")
    }

    private fun closeSession(message: Message) {
        val reply = message.replyTo
        val sessionId = message.data.getString(AgentRemoteWorkerProtocol.KEY_SESSION_ID).orEmpty()
        scope.launch {
            closeSessionById(sessionId, "close_session")
            reply?.sendRemoteEvent(AgentRemoteWorkerProtocol.MSG_STATUS, sessionId, "session_closed")
        }
    }

    private suspend fun closeSessionById(sessionId: String, reason: String) = sessionCleanupMutex.withLock {
        // Removing the owner inside one mutex makes explicit close and Binder death idempotent.
        val metadata = openSessions.remove(sessionId) ?: return@withLock
        clientDeathRecipients.remove(sessionId)?.let { (binder, recipient) ->
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        val requestIds = jobs.keys.filter { requestId -> requestSessions[requestId] == sessionId }
        requestIds.forEach { requestId ->
            chatServices[requestId]?.stopGeneration()
            jobs[requestId]?.cancel()
        }
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_remote_worker",
            event = "session_closed",
            details = metadata.details(sessionId, jobs.size, openSessions.size) + " reason=$reason"
        )
        maybeStopAfterCleanup()
    }

    private val requestSessions = ConcurrentHashMap<String, String>()

    private fun maybeStopAfterCleanup() {
        if (shouldStopAgentRemoteWorker(openSessions.size, jobs.size)) {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_worker",
                event = "worker_stop_after_cleanup",
                details = "activeJobs=${jobs.size} openSessions=${openSessions.size}"
            )
            stopSelf()
        }
    }

    private fun recordLifecycle(
        requestId: String,
        state: String,
        sessionId: String? = null,
        errorClass: String? = null
    ) {
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_remote_worker",
            event = "request_$state",
            details = "request=${requestId.take(8)} session=${sessionId?.take(8).orEmpty()} activeJobs=${jobs.size} openSessions=${openSessions.size}" +
                errorClass?.let { " errorClass=$it" }.orEmpty()
        )
    }

    private fun requirePrivateBoundedFile(
        path: String,
        suffix: String,
        allowMissing: Boolean = false
    ): File {
        val root = File(cacheDir, DIRECTORY).canonicalFile
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Worker file is outside private cache" }
        require(file.name.endsWith(suffix)) { "Unexpected worker file type" }
        if (!allowMissing) {
            require(file.isFile && file.length() <= MAX_REQUEST_BYTES) { "Invalid or oversized worker request" }
        }
        return file
    }

    private fun checkStreamCapacity(delta: String) {
        require(delta.length <= MAX_RESULT_CHARS) { "Remote Agent stream chunk exceeded the bounded limit" }
    }

    private fun writeAtomically(file: File, content: String) {
        require(content.length <= MAX_STREAM_SNAPSHOT_CHARS) { "Remote Agent stream snapshot exceeded the bounded limit" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) {
            file.writeText(content)
            temporary.delete()
        }
    }

    private companion object {
        const val DIRECTORY = "agent_remote"
        const val MAX_REQUEST_BYTES = 8L * 1024L * 1024L
        const val MAX_RESULT_CHARS = 8 * 1024 * 1024
        const val STATUS_INTERVAL_MS = 250L
        // The snapshot is a live UI preview, not the terminal response transport.
        // Keeping it small prevents repeated megabyte JSON/file allocations during
        // long runs; the full response still arrives through the result file.
        const val MAX_STREAM_SECTION_CHARS = 48 * 1024
        const val MAX_STREAM_SNAPSHOT_CHARS = MAX_STREAM_SECTION_CHARS * 2 + 4_096
    }
}

internal data class WorkerSessionMetadata(
    val conversationId: String,
    val rootTurnId: String,
    val runtimeEpoch: Long,
    val invocationId: String
) {
    fun details(sessionId: String, activeJobs: Int, openSessions: Int): String =
        "session=${sessionId.take(8)} conversation=${conversationId.take(8)} root=${rootTurnId.take(8)} " +
            "epoch=$runtimeEpoch invocation=${invocationId.take(8)} activeJobs=$activeJobs openSessions=$openSessions"
}

internal fun shouldStopAgentRemoteWorker(openSessionCount: Int, activeJobs: Int): Boolean =
    openSessionCount <= 0 && activeJobs <= 0

/** Snapshot polling deliberately ignores equal/stale atomic-file revisions. */
internal fun shouldPublishRemoteSnapshot(previousRevision: Long, candidateRevision: Long): Boolean =
    candidateRevision > previousRevision

internal class AgentRemoteWorkerCrashedException(message: String) : IllegalStateException(message)

internal class AgentRemoteChatClient(private val context: Context) {
    private val gson = Gson()

    suspend fun chat(
        request: AgentRemoteChatRequest,
        /** Supply a root-turn session id to reuse a worker lease across sequential requests. */
        sessionId: String? = null,
        onStatus: (String) -> Unit = {},
        onStreamSnapshot: (AgentRemoteStreamSnapshot) -> Unit = {}
    ): Result<OllamaService.ChatResponse> {
        return if (sessionId != null) {
            chatWithSharedSession(request, sessionId, onStatus, onStreamSnapshot)
        } else {
            chatWithOwnedSession(request, onStatus, onStreamSnapshot)
        }
    }

    private suspend fun chatWithOwnedSession(
        request: AgentRemoteChatRequest,
        onStatus: (String) -> Unit,
        onStreamSnapshot: (AgentRemoteStreamSnapshot) -> Unit
    ): Result<OllamaService.ChatResponse> = coroutineScope {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString()
        val resolvedSessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val directory = File(appContext.cacheDir, "agent_remote").apply { mkdirs() }
        prune(directory)
        val requestFile = File(directory, "$requestId.request.json")
        val resultFile = File(directory, "$requestId.result.json")
        val streamFile = File(directory, "$requestId.stream.json")
        val requestJson = gson.toJson(request)
        require(requestJson.length <= MAX_FILE_CHARS) { "Remote Agent request exceeded the bounded limit" }
        GenerationDiagnosticsStore.recordBreadcrumb(
            source = "agent_remote_client",
            event = "bind_started",
            details = "request=${requestId.take(8)} requestChars=${requestJson.length} pid=${android.os.Process.myPid()}"
        )
        AgentPromptComparisonStore.sync(
            context = appContext,
            enabled = SettingsRepository(appContext).agentDeveloperPromptComparison.value,
            requestId = requestId,
            fullPromptRequest = requestJson
        )
        requestFile.writeText(requestJson)

        val service = CompletableDeferred<Messenger>()
        val events = Channel<RemoteEvent>(capacity = TERMINAL_EVENT_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID) != requestId) {
                return@Handler true
            }
            val text = message.data.getString(AgentRemoteWorkerProtocol.KEY_TEXT).orEmpty()
            when (message.what) {
                AgentRemoteWorkerProtocol.MSG_STATUS -> events.trySend(RemoteEvent.Status(text))
                AgentRemoteWorkerProtocol.MSG_DONE -> events.trySend(RemoteEvent.Done)
                AgentRemoteWorkerProtocol.MSG_ERROR -> events.trySend(RemoteEvent.Error(text))
            }
            true
        })
        fun crash(): AgentRemoteWorkerCrashedException {
            val exit = GenerationDiagnosticsStore.describeRecentProcessExit(
                processNameSuffix = ":agent_remote",
                sinceTimestamp = startedAt - 2_000L
            )
            return AgentRemoteWorkerCrashedException(
                buildString {
                    append("Remote Agent worker stopped unexpectedly")
                    exit?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(300)) }
                }
            )
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    service.completeExceptionally(crash())
                } else {
                    GenerationDiagnosticsStore.recordBreadcrumb(
                        source = "agent_remote_client",
                        event = "worker_connected",
                        details = "request=${requestId.take(8)} pid=${android.os.Process.myPid()}"
                    )
                    service.complete(Messenger(binder))
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "worker_disconnected",
                    details = "request=${requestId.take(8)}"
                )
                events.trySend(RemoteEvent.Crashed(crash()))
            }
            override fun onBindingDied(name: ComponentName?) {
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "worker_binding_died",
                    details = "request=${requestId.take(8)}"
                )
                events.trySend(RemoteEvent.Crashed(crash()))
            }
            override fun onNullBinding(name: ComponentName?) {
                events.trySend(RemoteEvent.Error("Remote Agent worker did not bind"))
            }
        }
        var bound = false
        var remoteMessenger: Messenger? = null
        var terminalEventReceived = false
        var snapshotFollower: SnapshotFollower? = null
        val workerIntent = Intent(appContext, AgentRemoteChatWorkerService::class.java)
        val startedForWork = runCatching {
            appContext.startService(workerIntent) != null
        }.onFailure { error ->
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "worker_start_failed",
                details = "request=${requestId.take(8)} error=${error.javaClass.simpleName}"
            )
        }.getOrDefault(false)
        try {
            bound = appContext.bindService(
                workerIntent,
                connection,
                Context.BIND_AUTO_CREATE
            )
            check(bound) { "Unable to bind remote Agent worker" }
            val messenger = service.await()
            remoteMessenger = messenger
            messenger.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_OPEN_SESSION).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, resolvedSessionId)
                    putString(AgentRemoteWorkerProtocol.KEY_CONVERSATION_ID, request.conversationId)
                    putString(AgentRemoteWorkerProtocol.KEY_ROOT_TURN_ID, request.rootTurnId)
                    putLong(AgentRemoteWorkerProtocol.KEY_RUNTIME_EPOCH, request.runtimeEpoch)
                    putString(AgentRemoteWorkerProtocol.KEY_INVOCATION_ID, request.invocationId)
                }
            })
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "session_open_dispatched",
                details = "session=${resolvedSessionId.take(8)} request=${requestId.take(8)} owned=true"
            )
            messenger.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_START).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID, requestId)
                    putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, resolvedSessionId)
                    putString(AgentRemoteWorkerProtocol.KEY_REQUEST_PATH, requestFile.absolutePath)
                    putString(AgentRemoteWorkerProtocol.KEY_RESULT_PATH, resultFile.absolutePath)
                    putString(AgentRemoteWorkerProtocol.KEY_STREAM_PATH, streamFile.absolutePath)
                }
            })
            snapshotFollower = startSnapshotFollower(this@coroutineScope, streamFile, onStatus, onStreamSnapshot)
            while (true) {
                when (val event = events.receive()) {
                    is RemoteEvent.Status -> {
                        readStreamSnapshot(streamFile)?.let(onStreamSnapshot)
                        onStatus(event.text)
                    }
                    RemoteEvent.Done -> {
                        snapshotFollower?.pollOnce()
                        terminalEventReceived = true
                        check(resultFile.isFile && resultFile.length() <= MAX_FILE_CHARS) {
                            "Remote Agent worker returned an invalid result"
                        }
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = "agent_remote_client",
                            event = "result_ready",
                            details = "request=${requestId.take(8)} resultBytes=${resultFile.length()}"
                        )
                        return@coroutineScope Result.success(
                            gson.fromJson(resultFile.readText(), OllamaService.ChatResponse::class.java)
                        )
                    }
                    is RemoteEvent.Error -> {
                        terminalEventReceived = true
                        GenerationDiagnosticsStore.recordBreadcrumb(
                            source = "agent_remote_client",
                            event = "worker_error",
                            details = "request=${requestId.take(8)} error=${event.text.take(160)}"
                        )
                        return@coroutineScope Result.failure(
                            IllegalStateException(event.text.ifBlank { "Remote Agent request failed" })
                        )
                    }
                    is RemoteEvent.Crashed -> {
                        terminalEventReceived = true
                        return@coroutineScope Result.failure(event.error)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("Remote Agent worker stopped"))
        } finally {
            snapshotFollower?.stop()
            if (!terminalEventReceived) {
                runCatching {
                    remoteMessenger?.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_CANCEL).apply {
                        data = Bundle().apply {
                            putString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID, requestId)
                        }
                    })
                }
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "request_cancel_dispatched",
                    details = "request=${requestId.take(8)} bound=$bound"
                )
            }
            runCatching {
                remoteMessenger?.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_CLOSE_SESSION).apply {
                    data = Bundle().apply {
                        putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, resolvedSessionId)
                    }
                })
            }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "session_close_dispatched",
                details = "session=${resolvedSessionId.take(8)} request=${requestId.take(8)} terminal=$terminalEventReceived"
            )
            if (bound) runCatching { appContext.unbindService(connection) }
            if (startedForWork && !bound) {
                runCatching { appContext.stopService(workerIntent) }
            }
            if (terminalEventReceived) {
                requestFile.delete()
                resultFile.delete()
                streamFile.delete()
                File(directory, "${streamFile.name}.tmp").delete()
            }
            events.close()
        }
    }

    private suspend fun chatWithSharedSession(
        request: AgentRemoteChatRequest,
        sessionId: String,
        onStatus: (String) -> Unit,
        onStreamSnapshot: (AgentRemoteStreamSnapshot) -> Unit
    ): Result<OllamaService.ChatResponse> = coroutineScope {
        val appContext = context.applicationContext
        val requestId = UUID.randomUUID().toString()
        val directory = File(appContext.cacheDir, "agent_remote").apply { mkdirs() }
        prune(directory)
        val requestFile = File(directory, "$requestId.request.json")
        val resultFile = File(directory, "$requestId.result.json")
        val streamFile = File(directory, "$requestId.stream.json")
        val requestJson = gson.toJson(request)
        require(requestJson.length <= MAX_FILE_CHARS) { "Remote Agent request exceeded the bounded limit" }
        AgentPromptComparisonStore.sync(
            context = appContext,
            enabled = SettingsRepository(appContext).agentDeveloperPromptComparison.value,
            requestId = requestId,
            fullPromptRequest = requestJson
        )
        requestFile.writeText(requestJson)
        val lease = sharedSession(appContext, sessionId)
        val events = lease.register(requestId)
        var terminal = false
        var messenger: Messenger? = null
        var snapshotFollower: SnapshotFollower? = null
        try {
            messenger = lease.open(request)
            messenger.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_START).apply {
                replyTo = lease.reply
                data = Bundle().apply {
                    putString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID, requestId)
                    putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, sessionId)
                    putString(AgentRemoteWorkerProtocol.KEY_REQUEST_PATH, requestFile.absolutePath)
                    putString(AgentRemoteWorkerProtocol.KEY_RESULT_PATH, resultFile.absolutePath)
                    putString(AgentRemoteWorkerProtocol.KEY_STREAM_PATH, streamFile.absolutePath)
                }
            })
            snapshotFollower = startSnapshotFollower(this@coroutineScope, streamFile, onStatus, onStreamSnapshot)
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "shared_request_started",
                details = "session=${sessionId.take(8)} request=${requestId.take(8)} activeRequests=${lease.requestCount()}"
            )
            while (true) {
                when (val event = events.receive()) {
                    is RemoteEvent.Status -> {
                        readStreamSnapshot(streamFile)?.let(onStreamSnapshot)
                        onStatus(event.text)
                    }
                    RemoteEvent.Done -> {
                        snapshotFollower?.pollOnce()
                        terminal = true
                        check(resultFile.isFile && resultFile.length() <= MAX_FILE_CHARS) {
                            "Remote Agent worker returned an invalid result"
                        }
                        return@coroutineScope Result.success(
                            gson.fromJson(resultFile.readText(), OllamaService.ChatResponse::class.java)
                        )
                    }
                    is RemoteEvent.Error -> {
                        terminal = true
                        return@coroutineScope Result.failure(
                            IllegalStateException(event.text.ifBlank { "Remote Agent request failed" })
                        )
                    }
                    is RemoteEvent.Crashed -> {
                        terminal = true
                        return@coroutineScope Result.failure(event.error)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("Remote Agent worker stopped"))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            terminal = true
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "shared_request_failed",
                details = "session=${sessionId.take(8)} request=${requestId.take(8)} error=${error.javaClass.simpleName}"
            )
            Result.failure(error)
        } finally {
            snapshotFollower?.stop()
            if (!terminal) {
                runCatching {
                    messenger?.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_CANCEL).apply {
                        data = Bundle().apply {
                            putString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID, requestId)
                            putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, sessionId)
                        }
                    })
                }
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "shared_request_cancel_dispatched",
                    details = "session=${sessionId.take(8)} request=${requestId.take(8)}"
                )
            }
            lease.unregister(requestId)
            if (terminal) {
                requestFile.delete()
                resultFile.delete()
                streamFile.delete()
                File(directory, "${streamFile.name}.tmp").delete()
            }
            events.close()
        }
    }

    /**
     * Ends an externally-owned root-turn lease. This is intentionally explicit so an empty
     * request set cannot tear down a session between tool continuations.
     */
    suspend fun closeSession(sessionId: String): Boolean {
        val lease = synchronized(sharedSessionLock) { sharedSessions.remove(sessionId) } ?: return false
        return lease.close()
    }

    private fun readStreamSnapshot(file: File): AgentRemoteStreamSnapshot? =
        runCatching {
            if (!file.isFile || file.length() > MAX_FILE_CHARS) return@runCatching null
            gson.fromJson(file.readText(), AgentRemoteStreamSnapshot::class.java)
        }.getOrNull()

    /**
     * Keeps high-frequency stream observation off Binder. The worker atomically replaces the
     * file; this request-owned IO follower reads it at a bounded cadence and forwards only new
     * revisions to the active output consumer.
     */
    private fun startSnapshotFollower(
        requestScope: CoroutineScope,
        file: File,
        onStatus: (String) -> Unit,
        onStreamSnapshot: (AgentRemoteStreamSnapshot) -> Unit
    ): SnapshotFollower = SnapshotFollower(file, onStatus, onStreamSnapshot).also { follower ->
        follower.start(requestScope)
    }

    private inner class SnapshotFollower(
        private val file: File,
        private val onStatus: (String) -> Unit,
        private val onStreamSnapshot: (AgentRemoteStreamSnapshot) -> Unit
    ) {
        private val revisionLock = Any()
        private var latestRevision = -1L
        private var latestStatusBucket: String? = null
        private var job: Job? = null

        fun start(requestScope: CoroutineScope) {
            job = requestScope.launch(Dispatchers.IO) {
                while (isActive) {
                    pollOnce()
                    delay(SNAPSHOT_POLL_INTERVAL_MS)
                }
            }
        }

        fun pollOnce() {
            val snapshot = readStreamSnapshot(file) ?: return
            val changed = synchronized(revisionLock) {
                if (!shouldPublishRemoteSnapshot(latestRevision, snapshot.revision)) false else {
                    latestRevision = snapshot.revision
                    true
                }
            }
            if (!changed) return
            onStreamSnapshot(snapshot)
            val statusBucket = when {
                snapshot.promptTotal > 0 && snapshot.promptProcessed < snapshot.promptTotal -> {
                    val fivePercentBucket = (snapshot.promptProcessed * 20L / snapshot.promptTotal).coerceIn(0L, 20L)
                    "prompt:$fivePercentBucket"
                }
                snapshot.content.isNotBlank() || snapshot.thinking.isNotBlank() -> "streaming"
                else -> "waiting"
            }
            if (statusBucket != latestStatusBucket) {
                latestStatusBucket = statusBucket
                onStatus(
                    "streaming contentChars=${snapshot.content.length} thinkingChars=${snapshot.thinking.length} " +
                        "prompt=${snapshot.promptProcessed}/${snapshot.promptTotal}"
                )
            }
        }

        fun stop() {
            job?.cancel()
            job = null
        }
    }

    private fun prune(directory: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private sealed interface RemoteEvent {
        data class Status(val text: String) : RemoteEvent
        data object Done : RemoteEvent
        data class Error(val text: String) : RemoteEvent
        data class Crashed(val error: Throwable) : RemoteEvent
    }

    private fun sharedSession(context: Context, sessionId: String): SharedSessionLease =
        synchronized(sharedSessionLock) {
            sharedSessions.getOrPut(sessionId) { SharedSessionLease(context.applicationContext, sessionId) }
        }

    /** One root-turn lease owns one binding and multiplexes its request callback channels. */
    private class SharedSessionLease(
        private val appContext: Context,
        private val sessionId: String
    ) {
        private val lock = Any()
        private val service = CompletableDeferred<Messenger>()
        private val requestEvents = ConcurrentHashMap<String, Channel<RemoteEvent>>()
        private var bound = false
        private var openingStarted = false
        private var openDispatched = false
        private var closed = false
        private var closeAck: CompletableDeferred<Unit>? = null

        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            val eventRequestId = message.data.getString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID).orEmpty()
            if (message.what == AgentRemoteWorkerProtocol.MSG_STATUS &&
                eventRequestId == sessionId &&
                message.data.getString(AgentRemoteWorkerProtocol.KEY_TEXT) == "session_closed"
            ) {
                closeAck?.complete(Unit)
                return@Handler true
            }
            val event = when (message.what) {
                AgentRemoteWorkerProtocol.MSG_STATUS -> RemoteEvent.Status(
                    message.data.getString(AgentRemoteWorkerProtocol.KEY_TEXT).orEmpty()
                )
                AgentRemoteWorkerProtocol.MSG_DONE -> RemoteEvent.Done
                AgentRemoteWorkerProtocol.MSG_ERROR -> RemoteEvent.Error(
                    message.data.getString(AgentRemoteWorkerProtocol.KEY_TEXT).orEmpty()
                )
                else -> null
            }
            if (event != null) requestEvents[eventRequestId]?.trySend(event)
            true
        })

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) service.completeExceptionally(AgentRemoteWorkerCrashedException("Remote Agent worker did not bind"))
                else service.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                failAll("worker_disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                failAll("worker_binding_died")
            }

            override fun onNullBinding(name: ComponentName?) {
                service.completeExceptionally(AgentRemoteWorkerCrashedException("Remote Agent worker returned a null binding"))
                failAll("worker_null_binding")
            }
        }

        fun register(requestId: String): Channel<RemoteEvent> = Channel<RemoteEvent>(
            capacity = TERMINAL_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        ).also {
            requestEvents[requestId] = it
        }

        fun unregister(requestId: String) {
            requestEvents.remove(requestId)
        }

        fun requestCount(): Int = requestEvents.size

        suspend fun open(request: AgentRemoteChatRequest): Messenger {
            val shouldStart = synchronized(lock) {
                check(!closed) { "Remote Agent session is already closed" }
                if (openingStarted) false else {
                    openingStarted = true
                    true
                }
            }
            if (shouldStart) {
                val intent = Intent(appContext, AgentRemoteChatWorkerService::class.java)
                runCatching { appContext.startService(intent) }
                val didBind = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                synchronized(lock) { bound = didBind }
                if (!didBind) service.completeExceptionally(IllegalStateException("Unable to bind remote Agent worker"))
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "shared_session_bind_started",
                    details = "session=${sessionId.take(8)} bound=$didBind"
                )
            }
            val messenger = service.await()
            val sendOpen = synchronized(lock) {
                if (openDispatched) false else {
                    openDispatched = true
                    true
                }
            }
            if (sendOpen) {
                messenger.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_OPEN_SESSION).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, sessionId)
                        putString(AgentRemoteWorkerProtocol.KEY_CONVERSATION_ID, request.conversationId)
                        putString(AgentRemoteWorkerProtocol.KEY_ROOT_TURN_ID, request.rootTurnId)
                        putLong(AgentRemoteWorkerProtocol.KEY_RUNTIME_EPOCH, request.runtimeEpoch)
                        putString(AgentRemoteWorkerProtocol.KEY_INVOCATION_ID, request.invocationId)
                    }
                })
                GenerationDiagnosticsStore.recordBreadcrumb(
                    source = "agent_remote_client",
                    event = "shared_session_open_dispatched",
                    details = "session=${sessionId.take(8)}"
                )
            }
            return messenger
        }

        suspend fun close(): Boolean {
            val hadStarted = synchronized(lock) { openingStarted }
            val shouldClose = synchronized(lock) {
                if (closed) false else {
                    closed = true
                    closeAck = CompletableDeferred()
                    true
                }
            }
            if (!shouldClose) return false
            val messenger = if (hadStarted) runCatching { service.await() }.getOrNull() else null
            val sent = runCatching {
                messenger?.send(Message.obtain(null, AgentRemoteWorkerProtocol.MSG_CLOSE_SESSION).apply {
                    replyTo = reply
                    data = Bundle().apply { putString(AgentRemoteWorkerProtocol.KEY_SESSION_ID, sessionId) }
                })
                messenger != null
            }.getOrDefault(false)
            if (sent) withTimeoutOrNull(1_500L) { closeAck?.await() }
            failAll("session_closed")
            val shouldUnbind = synchronized(lock) {
                if (bound) {
                    bound = false
                    true
                } else false
            }
            if (shouldUnbind) runCatching { appContext.unbindService(connection) }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = "shared_session_closed",
                details = "session=${sessionId.take(8)} ack=${closeAck?.isCompleted == true} unbound=$shouldUnbind"
            )
            return sent
        }

        private fun failAll(reason: String) {
            val error = AgentRemoteWorkerCrashedException("Remote Agent worker $reason")
            requestEvents.values.forEach { it.trySend(RemoteEvent.Crashed(error)) }
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "agent_remote_client",
                event = reason,
                details = "session=${sessionId.take(8)} activeRequests=${requestEvents.size}"
            )
        }
    }

    companion object {
        const val MAX_FILE_CHARS = 8 * 1024 * 1024L
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
        // Match the worker's publication cadence: faster polling adds file-system and UI churn
        // without exposing any newer snapshot.
        const val SNAPSHOT_POLL_INTERVAL_MS = 250L
        const val TERMINAL_EVENT_BUFFER_CAPACITY = 4
        private val sharedSessionLock = Any()
        private val sharedSessions = mutableMapOf<String, SharedSessionLease>()
        /** Root coordinators may allocate once and pass this to every sequential request. */
        fun newSessionId(): String = UUID.randomUUID().toString()
    }
}

internal object AgentPromptComparisonStore {
    private const val DIRECTORY = "agent_prompt_comparison_sensitive"
    private const val MAX_CHARS = 2 * 1024 * 1024
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun sync(context: Context, enabled: Boolean, requestId: String, fullPromptRequest: String) {
        val directory = File(context.cacheDir, DIRECTORY)
        if (!enabled) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
            return
        }
        directory.mkdirs()
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        File(directory, "$requestId.prompt.json").writeText(fullPromptRequest.take(MAX_CHARS))
    }
}

private fun Messenger.sendRemoteEvent(what: Int, requestId: String, text: String) {
    runCatching {
        send(Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(AgentRemoteWorkerProtocol.KEY_REQUEST_ID, requestId)
                putString(AgentRemoteWorkerProtocol.KEY_TEXT, text.take(512))
            }
        })
    }
}
