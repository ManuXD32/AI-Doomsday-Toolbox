package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AgentProjectRunEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AgentProotRunEntity
import com.example.llamadroid.data.db.AgentProotRunStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentSpec
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable lifecycle wrapper for the native PRoot executor.
 *
 * The command identity and QUEUED receipt are committed before process creation. A process-local
 * generation marks unfinished rows as interrupted after process death, while completed receipts
 * remain replayable and are never executed again by recovery code.
 */
internal class AgentProotRunCoordinator private constructor(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val environmentManager = AgentProotEnvironmentManager(context)
    private val executor = AgentProotCommandExecutor(context, environmentManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processGeneration = AgentProcessGeneration.id
    private val admissionLocks = ConcurrentHashMap<String, Mutex>()
    private val activeRunsByConversation = ConcurrentHashMap<Long, String>()
    private val _projectStates = MutableStateFlow<Map<Long, AgentLocalRunState>>(emptyMap())
    val projectStates: StateFlow<Map<Long, AgentLocalRunState>> = _projectStates.asStateFlow()

    private val startup = scope.async {
        database.agentProotRunDao().interruptStaleActiveRuns(
            currentGeneration = processGeneration,
            reason = "The app process ended while this Debian command was active. Press Continue to inspect state; the command will not be replayed."
        )
    }

    suspend fun execute(
        conversationId: Long,
        request: AgentProotCommandRequest
    ): AgentProotCommandResult {
        startup.await()
        require(conversationId > 0L) { "A saved project conversation is required." }
        val conversation = database.agentChatDao().getConversation(conversationId)
            ?: error("The active project conversation no longer exists.")
        require(conversation.prootEnvironmentId == request.environmentId) {
            "The selected Debian environment changed before the command started."
        }

        ensureEnvironmentReady(request.environmentId)
        return admissionLocks.getOrPut(request.environmentId) { Mutex() }.withLock {
            val active = database.agentProotRunDao().getActiveForEnvironment(request.environmentId)
            require(active == null) {
                "DEBIAN_ENVIRONMENT_BUSY: command ${active?.id} is already active in this environment."
            }
            val now = System.currentTimeMillis()
            database.agentProotRunDao().insert(
                AgentProotRunEntity(
                    id = request.runId,
                    conversationId = conversationId,
                    environmentId = request.environmentId,
                    projectFolder = request.projectFolder,
                    commandDigest = sha256(request.command),
                    status = AgentProotRunStatus.QUEUED,
                    processGeneration = processGeneration,
                    previewUrl = request.previewPort?.let { "http://127.0.0.1:$it/" },
                    createdAt = now,
                    updatedAt = now
                )
            )
            activeRunsByConversation[conversationId] = request.runId
            val result = try {
                executor.execute(request)
            } catch (error: Throwable) {
                val failed = AgentProotCommandResult(
                    runId = request.runId,
                    environmentId = request.environmentId,
                    projectFolder = request.projectFolder,
                    status = AgentProotCommandStatus.FAILED,
                    startedAt = now,
                    endedAt = System.currentTimeMillis(),
                    error = error.message ?: error::class.java.simpleName
                )
                persistResult(conversationId, failed)
                throw error
            }
            persistResult(conversationId, result)
            if (request.background && !result.status.isTerminal()) {
                watchBackground(conversationId, request.runId)
                if (request.previewPort != null && !awaitPreview(request.previewPort)) {
                    val cancelled = executor.cancel(request.runId) ?: result.copy(
                        status = AgentProotCommandStatus.FAILED,
                        endedAt = System.currentTimeMillis(),
                        error = "Preview did not become healthy on the reserved loopback port."
                    )
                    persistResult(conversationId, cancelled)
                    error("Preview did not become healthy on the reserved loopback port.")
                }
            }
            result
        }
    }

    suspend fun status(runId: String): AgentProotCommandResult? {
        startup.await()
        executor.status(runId)?.let { current ->
            val row = database.agentProotRunDao().getById(runId) ?: return current
            persistResult(row.conversationId, current)
            return current
        }
        return database.agentProotRunDao().getById(runId)?.toResult()
    }

    suspend fun cancel(runId: String): AgentProotCommandResult? {
        startup.await()
        val row = database.agentProotRunDao().getById(runId) ?: return null
        val current = executor.cancel(runId) ?: row.toResult()
        persistResult(row.conversationId, current)
        return current
    }

    suspend fun cancelForConversation(conversationId: Long) {
        val runId = activeRunsByConversation[conversationId]
            ?: database.agentProotRunDao().getForConversation(conversationId)
                .firstOrNull { it.status in setOf(AgentProotRunStatus.QUEUED, AgentProotRunStatus.RUNNING, AgentProotRunStatus.STOP_REQUESTED) }
                ?.id
            ?: return
        cancel(runId)
    }

    fun activeRunId(conversationId: Long): String? = activeRunsByConversation[conversationId]

    suspend fun list(conversationId: Long): List<AgentProotCommandResult> {
        startup.await()
        return database.agentProotRunDao().getForConversation(conversationId).map { row ->
            executor.status(row.id)?.also { persistResult(conversationId, it) } ?: row.toResult()
        }
    }

    suspend fun latestProjectState(conversationId: Long): AgentLocalRunState? {
        startup.await()
        return _projectStates.value[conversationId]
            ?: database.agentChatDao().getLatestProjectRun(conversationId)?.toLocalState()
    }

    suspend fun publishStaticProjectState(state: AgentLocalRunState, environmentId: String) {
        _projectStates.update { it + (state.conversationId to state) }
        persistProjectState(state, environmentId)
    }

    private suspend fun ensureEnvironmentReady(environmentId: String) {
        val dao = database.agentProotEnvironmentDao()
        val environment = dao.getById(environmentId) ?: error("The selected Debian environment no longer exists.")
        if (environmentManager.isReady(environmentId) && environment.status == AgentProotEnvironmentStatus.READY) return
        val now = System.currentTimeMillis()
        dao.update(environment.copy(status = AgentProotEnvironmentStatus.INSTALLING, updatedAt = now))
        try {
            environmentManager.prepare(
                AgentProotEnvironmentSpec(
                    id = environment.id,
                    displayName = environment.displayName,
                    imageId = environment.imageId,
                    imageSha256 = environment.imageDigest.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                )
            ).getOrThrow()
            dao.update(
                environment.copy(
                    status = AgentProotEnvironmentStatus.READY,
                    sizeBytes = environmentManager.usageBytes(environmentId),
                    lastUsedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (error: Throwable) {
            dao.update(environment.copy(status = AgentProotEnvironmentStatus.BROKEN, updatedAt = System.currentTimeMillis()))
            throw error
        }
    }

    private fun watchBackground(conversationId: Long, runId: String) {
        scope.launch {
            while (true) {
                delay(250)
                val result = executor.status(runId) ?: break
                if (result.status.isTerminal()) {
                    persistResult(conversationId, result)
                    break
                }
            }
        }
    }

    private suspend fun persistResult(conversationId: Long, result: AgentProotCommandResult) {
        val dao = database.agentProotRunDao()
        val existing = dao.getById(result.runId) ?: return
        val outputReference = if (result.output.isNotBlank()) {
            AgentToolOutputStore.persist(
                context = context,
                conversationId = conversationId,
                toolName = "run_command",
                toolCallId = result.runId,
                output = result.output
            ).getOrNull()
        } else {
            existing.outputReference
        }
        dao.insert(
            existing.copy(
                status = result.status.toStoredStatus(),
                previewUrl = result.previewUrl,
                outputReference = outputReference,
                outputChars = result.output.length.coerceAtLeast(existing.outputChars),
                errorClass = result.error?.let { "COMMAND_ERROR" },
                errorMessage = result.error?.take(1_000),
                exitCode = result.exitCode,
                startedAt = result.startedAt ?: existing.startedAt,
                endedAt = result.endedAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        val state = AgentLocalRunState(
            conversationId = conversationId,
            projectFolder = result.projectFolder,
            runtime = "debian",
            entrypoint = "shell",
            uiMode = if (result.previewUrl != null) "WEB" else "CONSOLE",
            status = result.status.toUiStatus(),
            logs = buildString {
                if (result.output.isNotBlank()) append(result.output.takeLast(32_000))
                result.error?.let {
                    if (isNotEmpty()) append('\n')
                    append(it)
                }
            },
            previewUrl = result.previewUrl,
            startedAt = result.startedAt,
            endedAt = result.endedAt,
            exitCode = result.exitCode
        )
        _projectStates.update { it + (conversationId to state) }
        if (result.status.isTerminal()) activeRunsByConversation.remove(conversationId, result.runId)
        persistProjectState(state, result.environmentId)
    }

    private suspend fun persistProjectState(state: AgentLocalRunState, environmentId: String) {
        val dao = database.agentChatDao()
        val now = System.currentTimeMillis()
        val existing = dao.getLatestProjectRun(state.conversationId)
        val value = existing?.copy(
            projectFolder = state.projectFolder,
            backend = AgentWorkspaceBackendType.LOCAL_PROOT.name,
            prootEnvironmentId = environmentId,
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(32_000),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode,
            updatedAt = now
        ) ?: AgentProjectRunEntity(
            conversationId = state.conversationId,
            projectFolder = state.projectFolder,
            backend = AgentWorkspaceBackendType.LOCAL_PROOT.name,
            prootEnvironmentId = environmentId,
            runtime = state.runtime,
            entrypoint = state.entrypoint,
            uiMode = state.uiMode,
            status = state.status,
            logs = state.logs.takeLast(32_000),
            previewUrl = state.previewUrl,
            startedAt = state.startedAt,
            endedAt = state.endedAt,
            exitCode = state.exitCode,
            createdAt = now,
            updatedAt = now
        )
        if (existing == null) dao.insertProjectRun(value) else dao.updateProjectRun(value)
    }

    private suspend fun awaitPreview(port: Int): Boolean {
        repeat(60) {
            val healthy = runCatching {
                (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).run {
                    connectTimeout = 500
                    readTimeout = 500
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    try {
                        responseCode in 100..599
                    } finally {
                        disconnect()
                    }
                }
            }.getOrDefault(false)
            if (healthy) return true
            delay(500)
        }
        return false
    }

    private fun AgentProotRunEntity.toResult(): AgentProotCommandResult = AgentProotCommandResult(
        runId = id,
        environmentId = environmentId,
        projectFolder = projectFolder,
        status = status.toCommandStatus(),
        exitCode = exitCode,
        startedAt = startedAt,
        endedAt = endedAt,
        previewUrl = previewUrl,
        error = errorMessage
    )

    private fun AgentProjectRunEntity.toLocalState() = AgentLocalRunState(
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

    private fun AgentProotCommandStatus.isTerminal(): Boolean = when (this) {
        AgentProotCommandStatus.STARTING, AgentProotCommandStatus.RUNNING -> false
        else -> true
    }

    private fun AgentProotCommandStatus.toStoredStatus(): String = when (this) {
        AgentProotCommandStatus.STARTING -> AgentProotRunStatus.QUEUED
        AgentProotCommandStatus.RUNNING -> AgentProotRunStatus.RUNNING
        AgentProotCommandStatus.SUCCEEDED -> AgentProotRunStatus.SUCCEEDED
        AgentProotCommandStatus.CANCELLED -> AgentProotRunStatus.STOPPED
        AgentProotCommandStatus.INTERRUPTED -> AgentProotRunStatus.INTERRUPTED
        AgentProotCommandStatus.FAILED, AgentProotCommandStatus.TIMED_OUT -> AgentProotRunStatus.FAILED
    }

    private fun AgentProotCommandStatus.toUiStatus(): String = when (this) {
        AgentProotCommandStatus.STARTING, AgentProotCommandStatus.RUNNING -> "RUNNING"
        AgentProotCommandStatus.SUCCEEDED -> "SUCCEEDED"
        AgentProotCommandStatus.CANCELLED -> "STOPPED"
        AgentProotCommandStatus.INTERRUPTED -> "INTERRUPTED"
        AgentProotCommandStatus.FAILED, AgentProotCommandStatus.TIMED_OUT -> "FAILED"
    }

    private fun String.toCommandStatus(): AgentProotCommandStatus = when (this) {
        AgentProotRunStatus.QUEUED -> AgentProotCommandStatus.STARTING
        AgentProotRunStatus.RUNNING, AgentProotRunStatus.STOP_REQUESTED -> AgentProotCommandStatus.RUNNING
        AgentProotRunStatus.SUCCEEDED -> AgentProotCommandStatus.SUCCEEDED
        AgentProotRunStatus.STOPPED -> AgentProotCommandStatus.CANCELLED
        AgentProotRunStatus.INTERRUPTED -> AgentProotCommandStatus.INTERRUPTED
        else -> AgentProotCommandStatus.FAILED
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        // The private constructor is reached only through get(applicationContext); no Activity
        // or other lifecycle-bound Context is retained by this process-wide coordinator.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AgentProotRunCoordinator? = null

        fun get(context: Context): AgentProotRunCoordinator = instance ?: synchronized(this) {
            instance ?: AgentProotRunCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
