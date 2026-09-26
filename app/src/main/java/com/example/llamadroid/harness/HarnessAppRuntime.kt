package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessRuntimeEntity
import com.example.llamadroid.harness.client.HarnessAuthResult
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.client.OkHttpHarnessClient
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import com.example.llamadroid.harness.runtime.HarnessHookRequest
import com.example.llamadroid.harness.runtime.HarnessLaunchPreparation
import com.example.llamadroid.harness.runtime.HarnessRuntimeController
import com.example.llamadroid.harness.runtime.HarnessRuntimeHooks
import com.example.llamadroid.harness.runtime.HarnessRuntimeOutcome
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import com.example.llamadroid.harness.runtime.HarnessRuntimeRecord
import com.example.llamadroid.harness.runtime.HarnessProcessObservation
import com.example.llamadroid.harness.runtime.HarnessStopMode
import com.example.llamadroid.harness.runtime.HarnessStopRequest
import com.example.llamadroid.service.AgentForegroundService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** App-process singleton. Navigation owns presentation; this object owns the runtime lifetime. */
class HarnessAppRuntime private constructor(private val context: Context) {
    val applicationContext: Context get() = context
    val database = AppDatabase.getDatabase(context)
    private val mutableError = MutableStateFlow<String?>(null)
    val errorCode: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableStarting = MutableStateFlow(false)
    val startupInProgress: StateFlow<Boolean> = mutableStarting.asStateFlow()
    private val mutableReinstall = MutableStateFlow(HarnessRuntimeReinstallState())
    val reinstallState: StateFlow<HarnessRuntimeReinstallState> = mutableReinstall.asStateFlow()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        mutableError.value = harnessLifecycleErrorCode(error)
    })
    private val credentials = HarnessCredentialStore(context)
    val workspaces = HarnessWorkspaceRepository(context, database, credentials)
    val files = HarnessWorkspaceAccess(context, credentials)
    val models = HarnessLocalModels(context, database)
    /** Android-owned context/output overrides used by the Harness model editor. */
    val localModelCapabilities = HarnessLocalModelCapabilityStore(context)
    val diagnostics = HarnessDiagnostics(database, scope, HarnessRuntimeJournal(
        File(context.filesDir, "agent_harness/runtime-diagnostics.json")
    ))
    // Command text is presentation data: bounded, transient, and never journaled.
    private val mutableLiveCommandOutput = MutableSharedFlow<HarnessLiveCommandOutput>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveCommandOutput = mutableLiveCommandOutput.asSharedFlow()
    private val attentionNotifications by lazy { HarnessAttentionNotifications(context, database) }
    val attention = com.example.llamadroid.ui.agent.harness.NativeHarnessAttentionStore(
        scope, { client },
        onNotice = {
            try {
                ensureAttentionSession(it.sessionId)
                attentionNotifications.publish(it)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diagnostics.event(it.sessionId, "attention_notification", "failure", errorCode = "HARNESS_NOTIFICATION_FAILED") }
        },
        onResolved = { com.example.llamadroid.service.UnifiedNotificationManager.dismissHarnessAttention(attentionNotificationKey(it.sessionId, it.key, it.kind)) },
        onFailure = { diagnostics.event(null, "attention_stream", "failure", errorCode = "HARNESS_ATTENTION_STREAM_FAILED") },
    )
    val workspaceActions = HarnessWorkspaceActions()
    val terminals = HarnessTerminals(scope, { client }, diagnostics) { sessionId ->
        if (workspaces.scope(sessionId).workspace.backend == "REMOTE_SSH") com.example.llamadroid.service.AgentWorkspaceBackendType.REMOTE_SSH
        else com.example.llamadroid.service.AgentWorkspaceBackendType.LOCAL_PROOT
    }
    val projectRuns by lazy { HarnessProjectRuns(this) }
    private val store = RoomHarnessRuntimeStore(database)
    val status: StateFlow<HarnessRuntimeEntity?> = database.harnessDao().observeRuntime()
        .stateIn(scope, SharingStarted.Eagerly, null)
    private val mutableEndpoint = MutableStateFlow<HarnessEndpoint?>(null)
    val endpoint: StateFlow<HarnessEndpoint?> = mutableEndpoint.asStateFlow()
    @Volatile var client: HarnessClient? = null
        private set
    @Volatile private var bridge: HarnessAndroidBridge? = null

    suspend fun updateLocalModelCapability(
        wireId: String,
        contextTokens: Long?,
        maxOutputTokens: Long?,
        mtpEnabled: Boolean?,
        thinkingEnabled: Boolean?,
    ) {
        localModelCapabilities.setOverrides(
            wireId = wireId,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutputTokens,
            mtpEnabled = mtpEnabled,
            thinkingEnabled = thinkingEnabled,
        )
    }
    private var healthMonitor: Job? = null
    private val lifecycle = HarnessLifecycleGate()
    private val stopIntent = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var maintenanceStopping = false
    val sessionDeletion by lazy { HarnessSessionDeletion(context, database, scope, ::deleteIdleSession) { id, failure ->
        val code = failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,96}")) } ?: "SESSION_DELETE_FAILED"
        diagnostics.event(id, "session_delete_failed", "failure", errorCode = code)
    } }
    private val operations = HarnessBridgeOperations(
        context, database, credentials, workspaces, files, models, diagnostics, workspaceActions,
        emitCommandOutput = { chunk -> mutableLiveCommandOutput.tryEmit(chunk); Unit },
    ) { session, command, cwd ->
        val active = requireNotNull(client) { "HARNESS_NOT_RUNNING" }
        val args = buildJsonObject {
            put("sessionId", session.session.harnessSessionId)
            put("command", command)
            put("cwd", cwd)
        }
        when (val result = active.call("adt", "execute", args)) {
            is HarnessRpcResult.Success -> JSONObject(result.value.toString())
            is HarnessRpcResult.Failure -> error(result.error.code)
        }
    }
    private val runtime by lazy {
        HarnessRuntimeController.forAndroid(context, store, object : HarnessRuntimeHooks {
            override suspend fun prepareLaunch(request: HarnessHookRequest): HarnessLaunchPreparation {
                models.beginRuntime(request.generation)
                bridge?.stopBridge()
                val newBridge = HarnessAndroidBridge(request.generation, scope, operations::invoke, models::stream)
                val address = newBridge.startBridge()
                bridge = newBridge
                return HarnessLaunchPreparation(
                    webToken = randomToken(),
                    environment = mapOf(
                        "ADT_BRIDGE_URL" to address.origin,
                        "ADT_BRIDGE_TOKEN" to address.token,
                        "ADT_BRIDGE_GENERATION" to address.generation,
                        "ADT_LOCALE" to if (context.resources.configuration.locales[0].language == "es") "es" else "en"
                    )
                )
            }

            override suspend fun beforeStop(request: HarnessStopRequest) {
                if (request.mode == HarnessStopMode.GRACEFUL) {
                    if (!maintenanceStopping) cancelActiveTurns()
                    // The authenticated DSH route quiesces the agent loop and starts its
                    // persistence/root-fiber drain. Keep the Android bridge alive until that
                    // acknowledgement has returned; the runtime supervisor then gives Node
                    // its verified graceful signal. A transport failure still falls through
                    // to the process-level cleanup path, while coroutine cancellation must
                    // propagate so the controller's ten-second budget remains authoritative.
                    try {
                        client?.call("adt", "shutdown")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The native supervisor remains the cleanup authority when the DSH
                        // endpoint is unavailable or the event loop is already unhealthy.
                    }
                }
                operations.cancelOwnedRequests()
                projectRuns.stopLocalPreviewsForEnvironment()
                bridge?.stopBridge()
                bridge = null
            }
        }, diagnostics)
    }

    private val initialization = scope.async {
        diagnostics.loadRuntimeJournal()
        val pendingReset = HarnessRuntimeResetJournalStore(context).read()
        if (pendingReset != null) {
            // Resume before legacy discovery can observe a quarantined or partially reset tree.
            if (store.current() != null) runtime.forceStop()
            val result = HarnessRuntimeReinstaller(context, logger = diagnostics).reinstall { state ->
                mutableReinstall.value = state
            }
            if (!result.success) mutableError.value = result.errorCode
        } else {
            workspaces.importLegacyWorkspaces()
            // Finish reconciling the previous process before an explicit Start may claim ownership.
            if (store.current() != null) runtime.recover()
        }
        cleanupInference()
    }

    init { scope.launch { initialization.await(); sessionDeletion.wake() } }

    suspend fun start() = performLifecycle { startOwned() }

    /** Loads the destructive reset counts before either confirmation step is shown. */
    suspend fun prepareRuntimeReinstallConfirmation() {
        initialization.await()
        mutableReinstall.value = withContext(Dispatchers.IO) {
            HarnessRuntimeReinstaller(context = context).confirmationState()
        }
    }

    /**
     * Reinstalls the managed environment after callers have quiesced processes that do not
     * belong to the Harness runtime itself (for example the LAN proxy, a general PRoot shell,
     * or the managed file server).  Keeping this hook here makes the destructive operation
     * share the same lifecycle gate as Start, Stop, and recovery instead of allowing a UI route
     * to invoke the filesystem reset directly.
     */
    suspend fun reinstallManagedRuntime(
        quiesceExternalProcesses: suspend () -> Unit = {},
    ): HarnessRuntimeReinstallResult {
        initialization.await()
        return try {
            lifecycle.runExclusiveDestructive(
                terminate = {
                    mutableReinstall.value = HarnessRuntimeReinstallState(
                        HarnessRuntimeReinstallPhase.STOPPING,
                        progressPercent = 2,
                    )
                    maintenanceStopping = true
                    forceStop()
                    check(store.current()?.state == HarnessRuntimeState.STOPPED) {
                        "HARNESS_REINSTALL_STOP_INCOMPLETE"
                    }
                    // The callback runs while the destructive stopping lease is held and after
                    // the Harness owner is stopped. External process owners can therefore stop
                    // their children before the managed rootfs is removed, while a concurrent
                    // Harness Start remains blocked.
                    quiesceExternalProcesses()
                },
                action = {
                    try {
                        val result = HarnessRuntimeReinstaller(
                            context = context,
                            logger = diagnostics,
                        ).reinstall { state -> mutableReinstall.value = state }
                        mutableError.value = result.errorCode
                        result
                    } finally {
                        maintenanceStopping = false
                        mutableStarting.value = false
                    }
                }
            )
        } finally {
            // Also clear the guard when the external quiesce hook or the native stop fails before
            // the action block gets a chance to run.
            maintenanceStopping = false
            mutableStarting.value = false
        }
    }

    private suspend fun startOwned() = lifecycle.runStart {
        mutableStarting.value = true
        try {
            mutableError.value = null
            diagnostics.record(HarnessRuntimeLogEvent(
                "app_start_requested", HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID, "app",
                HarnessRuntimeState.STARTING
            ))
            initialization.await()
            AgentForegroundService.retainHarness(context, context.getString(R.string.harness_runtime_starting_notification))
            try {
                val outcome = runtime.start()
                if (outcome.record.state != HarnessRuntimeState.RUNNING) {
                    if (outcome.record.brokerPid == null && outcome.record.state in setOf(HarnessRuntimeState.STOPPED, HarnessRuntimeState.FAILED, HarnessRuntimeState.INTERRUPTED)) {
                        AgentForegroundService.releaseHarness(context)
                    }
                    return@runStart
                }
                val target = requireNotNull(outcome.endpoint)
                if (mutableEndpoint.value == target && client?.state?.value == HarnessConnectionState.READY) {
                    AgentForegroundService.updateStatus(context, context.getString(R.string.harness_runtime_notification))
                    return@runStart
                }
                monitorOwnership(outcome.record.generation)
                val connection = authenticatedClient(target)
                attention.attach(null)
                client?.close()
                client = connection
                attention.attach(connection)
                mutableEndpoint.value = target
                AgentForegroundService.updateStatus(context, context.getString(R.string.harness_runtime_notification))
            } catch (error: Exception) {
                if (error !is CancellationException) mutableError.value = harnessLifecycleErrorCode(error)
                if (error !is CancellationException && client == null && store.current()?.state == HarnessRuntimeState.RUNNING) {
                    withContext(NonCancellable) {
                        val cleanup = runtime.forceStop()
                        if (cleanup.record.state == HarnessRuntimeState.STOPPED) finishStopped()
                        cleanupInference()
                        cleanupRemote()
                    }
                }
                if (store.current()?.state !in setOf(HarnessRuntimeState.STARTING, HarnessRuntimeState.RUNNING, HarnessRuntimeState.STOP_REQUESTED, HarnessRuntimeState.FORCE_STOPPING)) {
                    AgentForegroundService.releaseHarness(context)
                }
                throw error
            }
        } finally {
            mutableStarting.value = false
        }
    }

    suspend fun stop() = performLifecycle {
        stopIntent.incrementAndGet()
        lifecycle.runStop<HarnessRuntimeOutcome>({ runtime.stop() }) { result ->
            closeAndroidOperations()
            try {
                if (result.record.state == HarnessRuntimeState.STOPPED) { mutableError.value = null; finishStopped() }
                if (result.timedOut) mutableError.value = "HARNESS_STOP_TIMEOUT"
            } finally {
                cleanupInference()
                cleanupRemote()
            }
        }
    }

    suspend fun forceStop() = performLifecycle {
        stopIntent.incrementAndGet()
        // The controller terminates the native owner before its bounded Android cleanup hook.
        // No network/bridge operation may prevent reaching the independent supervisor.
        lifecycle.runStop<HarnessRuntimeOutcome>({ runtime.forceStop() }) { result ->
            closeAndroidOperations()
            try {
                if (result.record.state == HarnessRuntimeState.STOPPED) { mutableError.value = null; finishStopped() }
                else mutableError.value = result.record.errorCode ?: "HARNESS_CLEANUP_PENDING"
            } finally {
                cleanupInference()
                cleanupRemote()
            }
        }
    }

    suspend fun reconnect() {
        // Start is idempotent at the controller and includes generation/cleanup serialization.
        start()
    }

    private fun authenticatedClient(target: HarnessEndpoint): HarnessClient = OkHttpHarnessClient(
        onTransportEvent = diagnostics::transport
    ).also { connection ->
        if (connection.adoptAuthenticatedEndpoint(target.origin, target.cookieHeader) !is HarnessAuthResult.Success) {
            connection.close()
            error("HARNESS_AUTH_FAILED")
        }
    }

    private suspend fun ensureAttentionSession(sessionId: String) {
        if (database.harnessDao().session(sessionId) != null) return
        val current = client ?: return
        val result = current.listSessions() as? HarnessRpcResult.Success ?: return
        val rows = JSONObject(result.value.toString()).optJSONArray("items") ?: return
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("sessionId") != sessionId) continue
            val cwd = row.optString("cwd").takeIf { it.isNotEmpty() } ?: return
            workspaces.importSession(sessionId, row.optString("title", sessionId), cwd, archived = row.optBoolean("archived"))
            return
        }
    }

    /** Navigation can stop observing an operation without cancelling its foreground owner. */
    private suspend fun performLifecycle(action: suspend () -> Unit) {
        scope.async {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                mutableError.value = harnessLifecycleErrorCode(failure)
                diagnostics.record(HarnessRuntimeLogEvent(
                    "app_operation_failed", HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID, "app",
                    HarnessRuntimeState.FAILED, errorCode = mutableError.value
                ))
                throw failure
            }
        }.await()
    }

    suspend fun retryRemoteCleanup() = lifecycle.runMaintenance { cleanupRemote(includeActive = false) }

    /** Reservation uses the live host's authoritative store path, followed by offline cleanup. */
    private suspend fun deleteIdleSession(receipt: HarnessSessionDeletionReceipt): Boolean {
        initialization.await()
        val intent = stopIntent.get()
        var restart = false
        val completed = lifecycle.runMaintenance {
            var prepared = receipt
            val connection = client
            if (connection != null) {
                if (attention.hasPending() || projectRuns.states.value.values.any { it.status in setOf("RUNNING", "STARTING") }) return@runMaintenance false
                val result = connection.call("adt", "sessionMaintenance", buildJsonObject {
                    put("operation", "prepare"); put("sessionId", receipt.id)
                })
                val response = when (result) {
                    is HarnessRpcResult.Success -> JSONObject(result.value.toString())
                    is HarnessRpcResult.Failure -> error(result.error.code)
                }
                if (!response.optBoolean("ready")) return@runMaintenance false
                try {
                    check(response.getString("sessionId") == receipt.id)
                    prepared = sessionDeletion.prepared(receipt, response.getString("directory"), response.optString("index").takeIf { it.isNotEmpty() })
                    maintenanceStopping = true
                    val outcome = runtime.stop()
                    check(outcome.record.state == HarnessRuntimeState.STOPPED) { "SESSION_DELETE_STOP_PENDING" }
                    closeAndroidOperations()
                    finishStopped()
                    cleanupInference()
                    cleanupRemote(includeActive = false)
                    restart = true
                } finally {
                    maintenanceStopping = false
                    if (client === connection) runCatching {
                        connection.call("adt", "sessionMaintenance", buildJsonObject { put("operation", "release") })
                    }
                }
            }
            if (prepared.directory == null) return@runMaintenance false // explicit Start resolves an offline queued request
            if (!canRemoveHarnessProject(database.harnessDao().runtime(), mutableStarting.value, client != null)) return@runMaintenance false
            if (files.pendingCleanupSessions().isNotEmpty()) return@runMaintenance false
            sessionDeletion.removeStopped(prepared)
            diagnostics.event(receipt.id, "session_deleted", "success")
            true
        }
        if (restart && stopIntent.get() == intent) startOwned()
        return completed
    }

    /** File removal holds the same publication lock as Start, so a new guest cannot race it. */
    suspend fun <T> withStoppedProjectMutation(action: suspend () -> T): T {
        initialization.await()
        return lifecycle.runMaintenance {
            check(canRemoveHarnessProject(database.harnessDao().runtime(), mutableStarting.value, client != null)) {
                "PROJECT_RUNTIME_STOP_REQUIRED"
            }
            check(files.pendingCleanupSessions().isEmpty()) { "PROJECT_REMOTE_CLEANUP_PENDING" }
            action()
        }
    }

    suspend fun retryCleanup() = lifecycle.runMaintenance {
        mutableError.value = null
        val running = store.current()?.state in setOf(HarnessRuntimeState.STARTING, HarnessRuntimeState.RUNNING)
        cleanupInference(retainActiveRuntime = running)
        cleanupRemote(includeActive = false)
    }

    private suspend fun cleanupInference(retainActiveRuntime: Boolean = false) {
        try {
            if (withTimeoutOrNull(5_000) { models.releaseOwnedResources(retainActiveRuntime); true } != true) {
                mutableError.value = "INFERENCE_CLEANUP_PENDING"
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "INFERENCE_CLEANUP_PENDING" }
    }

    /** Also runs after a timed-out hook, once native termination has had its independent path. */
    private fun closeAndroidOperations() {
        operations.cancelOwnedRequests()
        bridge?.stopBridge()
        bridge = null
    }

    private suspend fun cancelActiveTurns() {
        val connection = client ?: return
        withTimeoutOrNull(2_000) {
            database.harnessDao().conversations().forEach { conversation ->
                val mapping = database.harnessDao().sessionForConversation(conversation.id) ?: return@forEach
                connection.cancelSession(buildJsonObject { put("sessionId", mapping.harnessSessionId) })
            }
        }
    }

    private suspend fun finishStopped() {
        attention.attach(null)
        try {
            terminals.interrupted()
            projectRuns.interrupted()
        } finally {
            // A presentation or run-history write failure must not retain an authenticated
            // endpoint or the foreground lease after native termination was confirmed.
            healthMonitor?.cancel()
            healthMonitor = null
            try { client?.close() } finally {
                client = null
                mutableEndpoint.value = null
                AgentForegroundService.releaseHarness(context)
            }
        }
    }

    private suspend fun cleanupRemote(includeActive: Boolean = true) {
        val pending = withTimeoutOrNull(10_000) { files.retryCleanup(includeActive) }
            ?: files.pendingCleanupSessions(includeActive)
        if (pending.isNotEmpty()) mutableError.value = "SSH_CLEANUP_PENDING"
    }

    private fun monitorOwnership(generation: String) {
        healthMonitor?.cancel()
        healthMonitor = scope.launch {
            while (isActive) {
                delay(1_000)
                val owner = store.current() ?: continue
                if (owner.generation != generation) break
                if (owner.state != HarnessRuntimeState.RUNNING) continue
                val observation = runtime.observeOwner(generation) ?: continue
                if (observation.knownOwnerLost) {
                    val observedCode = processObservationCode(observation)
                    diagnostics.record(HarnessRuntimeLogEvent(
                        event = "process_exited",
                        environmentId = owner.environmentId,
                        generation = generation,
                        state = owner.state,
                        errorCode = observedCode,
                        exitCode = observation.exitCode,
                    ))
                    lifecycle.runMaintenance {
                        if (store.current()?.generation != generation) return@runMaintenance
                        // Reconcile the process owner before presentation writes: an invalid
                        // history row must never keep orphaned descendants running.
                        val recovery = runtime.recover(observation)
                        mutableError.value = if (recovery?.timedOut == true) {
                            recovery.record.errorCode ?: "HARNESS_CLEANUP_PENDING"
                        } else recovery?.record?.errorCode ?: observedCode
                        try {
                            terminals.interrupted()
                        } finally {
                            try {
                                projectRuns.interrupted()
                            } finally {
                                try {
                                    closeAndroidOperations()
                                    attention.attach(null)
                                    client?.close()
                                } finally {
                                    client = null
                                    mutableEndpoint.value = null
                                    try {
                                        cleanupInference()
                                        cleanupRemote()
                                    } finally {
                                        if (recovery?.timedOut != true) AgentForegroundService.releaseHarness(context)
                                        else AgentForegroundService.updateStatus(context, context.getString(R.string.harness_runtime_cleanup_pending))
                                    }
                                }
                            }
                        }
                    }
                    break
                }
            }
        }
    }

    private fun processObservationCode(observation: HarnessProcessObservation): String =
        observation.errorCode ?: when {
            !observation.brokerAlive -> "HARNESS_BROKER_EXITED"
            observation.nodeAlive == false -> "HARNESS_NODE_EXITED"
            observation.childAlive == false -> "HARNESS_PROOT_EXITED"
            else -> "HARNESS_PROCESS_LOST"
        }

    private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: HarnessAppRuntime? = null
        fun get(context: Context): HarnessAppRuntime = instance ?: synchronized(this) {
            instance ?: HarnessAppRuntime(context.applicationContext).also { instance = it }
        }
        fun existing(): HarnessAppRuntime? = instance
    }
}
