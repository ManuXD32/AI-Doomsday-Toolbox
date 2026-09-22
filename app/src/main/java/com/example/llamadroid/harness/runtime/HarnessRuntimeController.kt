package com.example.llamadroid.harness.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import com.example.llamadroid.data.proot.AgentProotNativeBinaryProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

private const val GRACEFUL_STOP_DEADLINE_MS = 10_000L
private const val UNIDENTIFIED_FORCE_DEADLINE_MS = 1_000L
private const val FORCE_STOP_HOOK_DEADLINE_MS = 1_000L
private const val LISTENER_RELEASE_DEADLINE_MS = 500L
private const val LISTENER_PROBE_TIMEOUT_MS = 100

private val ACTIVE_OWNER_STATES = setOf(
    HarnessRuntimeState.STARTING,
    HarnessRuntimeState.RUNNING,
    HarnessRuntimeState.STOP_REQUESTED,
    HarnessRuntimeState.FORCE_STOPPING,
    HarnessRuntimeState.INTERRUPTED
)

private data class StopHookResult(
    val completed: Boolean,
    val errorCode: String? = null
)

private class StartSession(
    val environmentId: String,
    val generation: String,
    val startJob: Job?,
    val completion: CompletableDeferred<Unit> = CompletableDeferred()
) {
    val cleanupMutex = Mutex()
    /** Serializes launch preparation with the matching beforeStop hook. */
    val hookMutex = Mutex()
    val stopHookCompletion = CompletableDeferred<StopHookResult>()
    var cancelMode: HarnessStopMode? = null
    var hookPrepared = false
    var stopHookInvoked = false
    var cleanupDone = false
}

private sealed interface StartDecision {
    data class Return(val outcome: HarnessRuntimeOutcome) : StartDecision

    data class Join(val session: StartSession) : StartDecision

    data class Begin(
        val request: HarnessStartRequest,
        val starting: HarnessRuntimeRecord,
        val session: StartSession
    ) : StartDecision
}

private data class StopContext(
    val current: HarnessRuntimeRecord,
    val requested: HarnessRuntimeRecord,
    val session: StartSession?,
    val handle: HarnessProcessHandle?,
    val identity: HarnessProcessIdentity?,
    val mode: HarnessStopMode,
    val startJob: Job?,
    val deadlineNanos: Long
)

/**
 * Serializes lifecycle state transitions for the one shared DeepSeek Harness instance.
 * Environment extraction and authenticated readiness run outside the short state mutex so a
 * concurrent Stop or Force operation can cancel startup and own the process cleanup.
 */
class HarnessRuntimeController(
    private val store: HarnessRuntimeStore,
    private val environmentProvider: HarnessEnvironmentProvider,
    private val payloadProvider: HarnessPayloadProvider,
    private val binaries: HarnessNativeBinaries,
    private val processLauncher: HarnessProcessLauncher,
    private val readinessProbe: HarnessReadinessProbe,
    private val processSupervisor: HarnessProcessSupervisor,
    private val hooks: HarnessRuntimeHooks,
    private val portAllocator: HarnessPortAllocator,
    private val logger: HarnessRuntimeMetadataLogger = NoopHarnessRuntimeMetadataLogger,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val listenerProbe: suspend (Int) -> Boolean = ::isLoopbackListenerOpen
) {
    private val lifecycleLock = Mutex()
    private var activeStart: StartSession? = null
    private var liveHandle: HarnessProcessHandle? = null
    private var liveIdentity: HarnessProcessIdentity? = null
    private var liveEndpoint: HarnessEndpoint? = null

    suspend fun start(request: HarnessStartRequest = HarnessStartRequest()): HarnessRuntimeOutcome {
        val decision = lifecycleLock.withLock { prepareStartLocked(request) }
        return when (decision) {
            is StartDecision.Return -> decision.outcome
            is StartDecision.Join -> {
                decision.session.completion.await()
                lifecycleLock.withLock {
                    val current = store.current() ?: stoppedRecord()
                    HarnessRuntimeOutcome(
                        current,
                        liveEndpoint.takeIf { current.state == HarnessRuntimeState.RUNNING }
                    )
                }
            }
            is StartDecision.Begin -> runStart(decision)
        }
    }

    /** Requests a graceful stop and waits at most ten seconds; it never escalates to Force. */
    suspend fun stop(): HarnessRuntimeOutcome {
        val deadlineNanos = stopDeadlineNanos()
        val context = lifecycleLock.withLock {
            prepareStopLocked(HarnessStopMode.GRACEFUL, deadlineNanos)
        }
        cancelStart(context, HarnessStopMode.GRACEFUL)
        invokeStopHook(context)
        val remainingAfterHook = remainingMillis(context.deadlineNanos)
        val processStopped = if (ownerIdentityMissing(context)) {
            false
        } else {
            try {
                when {
                    remainingAfterHook <= 0L -> false
                    context.identity != null -> processSupervisor.gracefulStop(
                        context.handle,
                        context.identity,
                        remainingAfterHook
                    )
                    context.handle != null -> gracefulStopWithoutIdentity(
                        context.handle,
                        remainingAfterHook
                    )
                    else -> true
                }
            } catch (_: Throwable) {
                false
            }
        }
        val stopped = processStopped && awaitListenerClosed(
            context.requested.port,
            remainingMillis(context.deadlineNanos)
        )
        awaitStartCompletion(context)
        val result = finishStop(context, stopped)
        return result
    }

    /** Force stops the shared environment independently of any previous graceful-stop request. */
    suspend fun forceStop(): HarnessRuntimeOutcome {
        val context = lifecycleLock.withLock {
            prepareStopLocked(HarnessStopMode.FORCE, Long.MAX_VALUE)
        }
        cancelStart(context, HarnessStopMode.FORCE)
        val processStopped = try {
            forceStopOwner(context)
        } catch (_: Throwable) {
            false
        }
        val stopped = processStopped && awaitListenerClosed(context.requested.port)
        context.session?.let { session ->
            lifecycleLock.withLock { session.cleanupDone = stopped }
        }
        // The native owner is already gone before Android cleanup is awaited. The hook is
        // bounded so a frozen bridge cannot turn Force into an unbounded lifecycle wait.
        val hook = invokeStopHook(context, FORCE_STOP_HOOK_DEADLINE_MS)
        awaitStartCompletion(context)
        val result = finishForceStop(context, stopped, hook)
        return result
    }

    /**
     * Revalidates the active generation before stale-owner cleanup.  The returned observation is
     * deliberately metadata-only; callers may use it to persist a stable exit class while the
     * live process handle still exposes its bounded exit status.
     */
    suspend fun observeOwner(expectedGeneration: String): HarnessProcessObservation? {
        val target = lifecycleLock.withLock {
            val current = store.current() ?: return@withLock null
            if (current.generation != expectedGeneration || current.state != HarnessRuntimeState.RUNNING) {
                return@withLock null
            }
            val identity = liveIdentity ?: identityFrom(current) ?: return@withLock null
            identity to liveHandle
        } ?: return null
        val (identity, handle) = target
        val observed = processSupervisor.observe(identity)
        val diagnostics = handle?.let { runCatching { it.diagnostics() }.getOrNull() }
        val code = sanitizeHarnessProcessCode(diagnostics?.stderrCode)
            ?: sanitizeHarnessProcessCode(diagnostics?.stdoutCode)
            ?: classifyHarnessProcessExit(diagnostics?.exitCode)
        return observed.copy(
            exitCode = diagnostics?.exitCode,
            errorCode = code,
        )
    }

    /** Reconciles a persisted owner after app/process recreation. It never auto-restarts. */
    suspend fun recover(observed: HarnessProcessObservation? = null): HarnessRuntimeOutcome? = lifecycleLock.withLock {
        val current = store.current() ?: return@withLock null
        if (current.state == HarnessRuntimeState.FAILED) {
            return@withLock HarnessRuntimeOutcome(current)
        }
        activeStart?.let { session ->
            session.cancelMode = HarnessStopMode.FORCE
            session.startJob?.cancel(CancellationException("Harness recovery requested"))
        }
        val persistedIdentity = identityFrom(current)
        val identity = persistedIdentity ?: processSupervisor.recoverIdentity(
            ownerMarker = current.generation,
            expectedUid = AndroidProcess.myUid()
        )
        val identityRecoveredFromLedger = persistedIdentity == null && identity != null
        val ownerAlive = identity?.let(processSupervisor::isOwnerAlive) == true
        val listenerAlive = current.port?.let { probeListener(it) } == true
        if (current.state == HarnessRuntimeState.STOPPED && !ownerAlive && !listenerAlive) {
            return@withLock HarnessRuntimeOutcome(current)
        }
        val identityMissing = identity == null && current.state in ACTIVE_OWNER_STATES
        var cleanupSucceeded = !identityMissing
        if (identity != null && ownerAlive) {
            cleanupSucceeded = try {
                processSupervisor.forceStop(null, identity)
            } catch (_: Throwable) {
                false
            }
        }
        cleanupSucceeded = cleanupSucceeded && awaitListenerClosed(current.port)
        val observedCode = sanitizeHarnessProcessCode(observed?.errorCode)
            ?: classifyHarnessProcessExit(observed?.exitCode)
        val interrupted = current.copy(
            state = HarnessRuntimeState.INTERRUPTED,
            endedAt = now(),
            updatedAt = now(),
            errorCode = observedCode ?: if (identityMissing) {
                "HARNESS_OWNER_IDENTITY_MISSING"
            } else if (identityRecoveredFromLedger && cleanupSucceeded) {
                "HARNESS_PROCESS_RECOVERED_FROM_LEDGER"
            } else if (cleanupSucceeded) {
                "HARNESS_PROCESS_RECOVERED"
            } else {
                "HARNESS_RECOVERY_CLEANUP_INCOMPLETE"
            },
            errorMessage = null
        )
        store.replace(interrupted)
        if (observedCode != null) {
            logger.record(
                HarnessRuntimeLogEvent(
                    "process_exited",
                    current.environmentId,
                    current.generation,
                    current.state,
                    errorCode = observedCode,
                    exitCode = observed?.exitCode,
                )
            )
        }
        logger.record(
            HarnessRuntimeLogEvent(
                "recovered",
                current.environmentId,
                current.generation,
                interrupted.state,
                errorCode = interrupted.errorCode
            )
        )
        HarnessRuntimeOutcome(interrupted, timedOut = !cleanupSucceeded)
    }

    suspend fun snapshot(): HarnessRuntimeRecord? = lifecycleLock.withLock { store.current() }

    private suspend fun prepareStartLocked(request: HarnessStartRequest): StartDecision {
        var current = store.current()
        if (current?.state == HarnessRuntimeState.RUNNING &&
            liveHandle?.isAlive() == true &&
            liveEndpoint != null
        ) {
            return StartDecision.Return(HarnessRuntimeOutcome(current, liveEndpoint))
        }
        activeStart?.let { session ->
            return StartDecision.Join(session)
        }
        if (current?.state == HarnessRuntimeState.STOP_REQUESTED ||
            current?.state == HarnessRuntimeState.FORCE_STOPPING
        ) {
            // A stop owns the current generation until its terminal state is persisted. A new
            // start must wait for that operation rather than allowing a late stop result to
            // overwrite the next generation.
            return StartDecision.Return(HarnessRuntimeOutcome(current, liveEndpoint))
        }
        reconcileStaleLocked(current)
        current = store.current()
        if (current?.state == HarnessRuntimeState.RUNNING &&
            liveHandle?.isAlive() == true &&
            liveEndpoint != null
        ) {
            return StartDecision.Return(HarnessRuntimeOutcome(current, liveEndpoint))
        }

        val generation = UUID.randomUUID().toString()
        val port = portAllocator.allocate(request.preferredPort)
        val starting = HarnessRuntimeRecord(
            environmentId = request.environmentId,
            state = HarnessRuntimeState.STARTING,
            generation = generation,
            port = port,
            startedAt = now(),
            updatedAt = now()
        )
        if (!store.beginStart(starting)) {
            val busy = store.current()
                ?: throw HarnessRuntimeException("HARNESS_START_BUSY", "Harness start is already in progress")
            return StartDecision.Return(HarnessRuntimeOutcome(busy, liveEndpoint))
        }
        val session = StartSession(
            environmentId = request.environmentId,
            generation = generation,
            startJob = currentCoroutineContext()[Job]
        )
        activeStart = session
        logger.record(
            HarnessRuntimeLogEvent("start_requested", request.environmentId, generation, starting.state)
        )
        return StartDecision.Begin(request, starting, session)
    }

    private suspend fun runStart(decision: StartDecision.Begin): HarnessRuntimeOutcome {
        val request = decision.request
        val starting = decision.starting
        val session = decision.session
        val port = requireNotNull(starting.port)
        var handle: HarnessProcessHandle? = null
        var identity: HarnessProcessIdentity? = null
        var startupPhase = "environment_preparing"
        fun recordPhase(name: String) {
            startupPhase = name
            logger.record(
                HarnessRuntimeLogEvent(
                    event = "phase",
                    environmentId = request.environmentId,
                    generation = session.generation,
                    state = HarnessRuntimeState.STARTING,
                    phase = name
                )
            )
        }
        try {
            ensureStartActive(session)
            recordPhase("environment_preparing")
            val paths = environmentProvider.prepare(request.environmentId)
            ensureStartActive(session)
            recordPhase("environment_ready")
            recordPhase("payload_preparing")
            val payload = payloadProvider.prepare(paths)
            updateStarting(starting) { it.copy(runtimeVersion = payload.version, updatedAt = now()) }
            ensureStartActive(session)
            recordPhase("payload_ready")

            lifecycleLock.withLock {
                ensureStartActiveLocked(session)
                session.hookPrepared = true
            }
            recordPhase("bridge_preparing")
            val preparation = session.hookMutex.withLock {
                // Stop may cancel startup while this hook is preparing the bridge. Holding the
                // same mutex in beforeStop prevents teardown from racing a still-running setup.
                ensureStartActive(session)
                hooks.prepareLaunch(
                    HarnessHookRequest(
                        environmentId = request.environmentId,
                        generation = session.generation,
                        port = port,
                        payload = payload,
                        paths = paths
                    )
                )
            }
            ensureStartActive(session)
            recordPhase("bridge_ready")
            val spec = HarnessLaunchSpec(
                payload = payload,
                paths = paths,
                port = port,
                ownerMarker = session.generation,
                preparation = preparation,
                brokerPath = binaries.brokerPath,
                prootPath = binaries.prootPath,
                loaderPath = binaries.loaderPath
            )

            // Launch is the only potentially blocking operation performed under the mutex. This
            // closes the race where Force observes no handle immediately before ProcessBuilder.start.
            recordPhase("process_launching")
            handle = lifecycleLock.withLock {
                ensureStartActiveLocked(session)
                processLauncher.launch(spec).also { liveHandle = it }
            }
            val launchedHandle = requireNotNull(handle) {
                "Harness process handle was lost"
            }
            recordPhase("process_launched")
            val capturedIdentity = processSupervisor.capture(launchedHandle, spec)
            identity = capturedIdentity
            lifecycleLock.withLock {
                ensureStartActiveLocked(session)
                if (!session.cleanupDone) liveIdentity = capturedIdentity
            }
            updateStarting(starting) {
                it.copy(
                    brokerPid = capturedIdentity.brokerPid,
                    processGroupId = capturedIdentity.processGroupId,
                    processStartTicks = capturedIdentity.brokerStartTimeTicks,
                    childPid = capturedIdentity.childPid,
                    childStartTicks = capturedIdentity.childStartTimeTicks,
                    nodePid = capturedIdentity.nodePid,
                    nodeStartTicks = capturedIdentity.nodeStartTimeTicks,
                    updatedAt = now()
                )
            }
            recordPhase("process_identity_ready")
            recordPhase("readiness_waiting")
            val endpoint = awaitReadinessWhileProcessAlive(
                handle = launchedHandle,
                origin = "http://127.0.0.1:$port",
                webToken = preparation.webToken,
                readinessPath = payload.healthPath,
                timeoutMs = request.readinessTimeoutMs
            )
            ensureStartActive(session)
            check(launchedHandle.isAlive()) { "Harness exited after readiness" }
            recordPhase("readiness_ready")
            val running = lifecycleLock.withLock {
                ensureStartActiveLocked(session)
                liveEndpoint = endpoint
                val current = store.current() ?: starting
                val next = current.copy(
                    state = HarnessRuntimeState.RUNNING,
                    startedAt = now(),
                    updatedAt = now(),
                    errorCode = null,
                    errorMessage = null
                )
                require(store.compareAndSet(setOf(HarnessRuntimeState.STARTING), next)) {
                    "Harness state changed while it was becoming ready"
                }
                next
            }
            logger.record(
                HarnessRuntimeLogEvent(
                    "started",
                    request.environmentId,
                    session.generation,
                    running.state,
                    durationMs = now() - (starting.startedAt ?: now())
                )
            )
            return HarnessRuntimeOutcome(running, endpoint)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val processDiagnostics = handle?.let { candidate ->
                    runCatching { candidate.diagnosticsAfterExit() }.getOrNull()
                } ?: generateSequence(error) { it.cause }.take(8)
                    .filterIsInstance<HarnessRuntimeException>()
                    .mapNotNull { it.processDiagnostics }.firstOrNull()
                val processErrorCode = processDiagnostics?.let { diagnostics ->
                    sanitizeHarnessProcessCode(diagnostics.stderrCode) ?:
                        sanitizeHarnessProcessCode(diagnostics.stdoutCode) ?:
                        classifyHarnessProcessExit(diagnostics.exitCode)
                }
                val processExitCode = processDiagnostics?.exitCode
                if (processErrorCode != null) {
                    logger.record(
                        HarnessRuntimeLogEvent(
                            event = "startup_output",
                            environmentId = request.environmentId,
                            generation = session.generation,
                            state = HarnessRuntimeState.STARTING,
                            errorCode = processErrorCode,
                            phase = startupPhase,
                            exitCode = processExitCode
                        )
                    )
                }
                if (processExitCode != null) {
                    logger.record(
                        HarnessRuntimeLogEvent(
                            event = "process_exited",
                            environmentId = request.environmentId,
                            generation = session.generation,
                            state = HarnessRuntimeState.STARTING,
                            errorCode = processErrorCode,
                            phase = startupPhase,
                            exitCode = processExitCode
                        )
                    )
                }
                val failureCode = processErrorCode ?: errorCode(error)
                val cancelMode = lifecycleLock.withLock { session.cancelMode }
                // A graceful stop owns its own ten-second deadline and must never be silently
                // escalated by a startup failure. A normal startup failure and Force both clean now.
                val cleaned = if (cancelMode != HarnessStopMode.GRACEFUL) {
                    forceStartOwner(session, handle, identity, starting.port)
                } else {
                    lifecycleLock.withLock { session.cleanupDone }
                }
                val stopMode = cancelMode ?: HarnessStopMode.FORCE
                // Graceful Stop owns the hook and its ten-second deadline. Do not let this
                // cancellation handler start a second ten-second wait after that deadline.
                val hook = if (stopMode == HarnessStopMode.GRACEFUL) {
                    StopHookResult(completed = true)
                } else {
                    invokeStartStopHook(session, stopMode, FORCE_STOP_HOOK_DEADLINE_MS)
                }

                val cancelled = lifecycleLock.withLock {
                    cancelMode != null || store.current()?.state in setOf(
                        HarnessRuntimeState.STOP_REQUESTED,
                        HarnessRuntimeState.FORCE_STOPPING,
                        HarnessRuntimeState.INTERRUPTED,
                        HarnessRuntimeState.STOPPED
                    )
                }
                val finalRecord = lifecycleLock.withLock {
                    val current = store.current() ?: starting
                    if (current.generation != starting.generation) {
                        current
                    } else if (!cancelled) {
                        val failed = current.copy(
                            state = HarnessRuntimeState.FAILED,
                            endedAt = now(),
                            updatedAt = now(),
                            errorCode = failureCode,
                            errorMessage = safeMessage(error)
                        )
                        if (store.compareAndSet(setOf(current.state), failed)) failed else store.current() ?: current
                    } else if (cleaned && current.state == HarnessRuntimeState.FORCE_STOPPING) {
                        val finished = current.copy(
                            state = if (hook.completed) HarnessRuntimeState.STOPPED else HarnessRuntimeState.FORCE_STOPPING,
                            endedAt = if (hook.completed) now() else current.endedAt,
                            updatedAt = now(),
                            errorCode = hook.errorCode
                                ?: if (hook.completed) null else "HARNESS_STOP_HOOK_FAILED",
                            errorMessage = null
                        )
                        if (store.compareAndSet(setOf(HarnessRuntimeState.FORCE_STOPPING), finished)) {
                            finished
                        } else {
                            store.current() ?: current
                        }
                    } else {
                        current
                    }
                }
                logger.record(
                    HarnessRuntimeLogEvent(
                        "start_failed",
                        request.environmentId,
                        session.generation,
                        finalRecord.state,
                        errorCode = if (cancelled) "HARNESS_START_CANCELLED" else finalRecord.errorCode,
                        phase = startupPhase,
                        exitCode = processExitCode
                    )
                )
                if (cancelled) {
                    if (error is CancellationException) throw error
                    throw HarnessRuntimeException("HARNESS_START_CANCELLED", "Harness start was cancelled")
                }
                if (error is CancellationException) throw error
                throw if (error is HarnessRuntimeException && error.code == failureCode) {
                    error
                } else {
                    HarnessRuntimeException(failureCode, "Harness start failed", error)
                }
            }
        } finally {
            withContext(NonCancellable) {
                lifecycleLock.withLock {
                    if (activeStart === session) activeStart = null
                    if (!session.completion.isCompleted) session.completion.complete(Unit)
                }
            }
        }
    }

    /**
     * Readiness must not consume the whole 60-second budget after the broker/PRoot process has
     * already exited. The probe owns HTTP retry semantics; this wrapper owns the independent
     * process-liveness race and returns a classified early-exit error as soon as it is observable.
     */
    private suspend fun awaitReadinessWhileProcessAlive(
        handle: HarnessProcessHandle,
        origin: String,
        webToken: String,
        readinessPath: String,
        timeoutMs: Long
    ): HarnessEndpoint = coroutineScope {
        val readiness = async(Dispatchers.IO) {
            readinessProbe.awaitReady(origin, webToken, readinessPath, timeoutMs)
        }
        try {
            var endpoint: HarnessEndpoint? = null
            while (endpoint == null) {
                if (!handle.isAlive()) {
                    val diagnostics = runCatching { handle.diagnosticsAfterExit() }.getOrNull()
                    val code = sanitizeHarnessProcessCode(diagnostics?.stderrCode) ?:
                        sanitizeHarnessProcessCode(diagnostics?.stdoutCode) ?:
                        classifyHarnessProcessExit(diagnostics?.exitCode) ?: "HARNESS_PROCESS_EXITED"
                    throw HarnessRuntimeException(
                        code,
                        "The Harness process exited before authenticated readiness"
                    )
                }
                endpoint = withTimeoutOrNull(100L) { readiness.await() }
            }
            endpoint
        } finally {
            readiness.cancel()
        }
    }

    private suspend fun prepareStopLocked(
        mode: HarnessStopMode,
        deadlineNanos: Long
    ): StopContext {
        val current = store.current() ?: stoppedRecord()
        val session = activeStart
        val persistedIdentity = identityFrom(current)
        val recoveredIdentity = if (persistedIdentity == null) {
            processSupervisor.recoverIdentity(
                ownerMarker = current.generation,
                expectedUid = AndroidProcess.myUid()
            )
        } else {
            null
        }
        val resolvedIdentity = persistedIdentity ?: recoveredIdentity
        val ownerAlive = resolvedIdentity?.let(processSupervisor::isOwnerAlive) == true
        val listenerAlive = current.port?.let { probeListener(it) } == true
        if (current.state == HarnessRuntimeState.STOPPED &&
            session == null &&
            !ownerAlive &&
            !listenerAlive
        ) {
            return StopContext(current, current, null, null, null, mode, null, deadlineNanos)
        }
        session?.cancelMode = mode
        val requested = when (mode) {
            HarnessStopMode.GRACEFUL -> current.copy(
                state = HarnessRuntimeState.STOP_REQUESTED,
                stopRequestedAt = current.stopRequestedAt ?: now(),
                updatedAt = now()
            )
            HarnessStopMode.FORCE -> current.copy(
                state = HarnessRuntimeState.FORCE_STOPPING,
                forceStopRequestedAt = current.forceStopRequestedAt ?: now(),
                updatedAt = now()
            )
        }
        store.replace(requested)
        val handle = liveHandle
        val identity = liveIdentity ?: resolvedIdentity
        if (session != null && handle == null && identity == null) session.cleanupDone = true
        return StopContext(
            current,
            requested,
            session,
            handle,
            identity,
            mode,
            session?.startJob,
            deadlineNanos
        )
    }

    private suspend fun invokeStopHook(
        context: StopContext,
        timeoutMs: Long? = context.remainingMillisOrNull()
    ): StopHookResult {
        if (context.session != null) {
            return invokeStartStopHook(
                context.session,
                context.mode,
                timeoutMs
            )
        }
        return runStopHook(context.current, context.mode, timeoutMs)
    }

    private fun cancelStart(context: StopContext, mode: HarnessStopMode) {
        context.session?.let { session ->
            session.cancelMode = mode
            session.startJob?.cancel(CancellationException("Harness ${mode.name.lowercase()} requested"))
        }
    }

    private suspend fun awaitStartCompletion(context: StopContext) {
        val job = context.startJob ?: return
        if (job === currentCoroutineContext()[Job]) return
        val timeoutMs = if (context.deadlineNanos == Long.MAX_VALUE) {
            UNIDENTIFIED_FORCE_DEADLINE_MS
        } else {
            context.remainingMillisOrNull() ?: return
        }
        if (timeoutMs <= 0L) return
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            context.session?.completion?.await()
        }
    }

    private suspend fun finishStop(
        context: StopContext,
        stopped: Boolean
    ): HarnessRuntimeOutcome {
        val result = lifecycleLock.withLock {
            val current = store.current() ?: context.requested
            if (current.generation != context.requested.generation) {
                return@withLock HarnessRuntimeOutcome(
                    current,
                    liveEndpoint.takeIf { current.state == HarnessRuntimeState.RUNNING },
                    timedOut = true
                )
            }
            // A concurrent Force operation owns the terminal transition and must win over this
            // graceful operation's late result.
            if (current.state == HarnessRuntimeState.FORCE_STOPPING || current.state == HarnessRuntimeState.STOPPED) {
                return@withLock HarnessRuntimeOutcome(current, liveEndpoint, timedOut = !stopped)
            }
            if (!stopped) {
                val timeout = current.copy(
                    state = HarnessRuntimeState.STOP_REQUESTED,
                    errorCode = if (ownerIdentityMissing(context)) {
                        "HARNESS_OWNER_IDENTITY_MISSING"
                    } else {
                        "HARNESS_GRACEFUL_STOP_TIMEOUT"
                    },
                    errorMessage = null,
                    updatedAt = now()
                )
                if (!store.compareAndSet(setOf(HarnessRuntimeState.STOP_REQUESTED), timeout)) {
                    val latest = store.current() ?: current
                    return@withLock HarnessRuntimeOutcome(
                        latest,
                        liveEndpoint.takeIf { latest.state == HarnessRuntimeState.RUNNING },
                        timedOut = true
                    )
                }
                return@withLock HarnessRuntimeOutcome(timeout, liveEndpoint, timedOut = true)
            }
            val finished = current.copy(
                state = HarnessRuntimeState.STOPPED,
                endedAt = now(),
                updatedAt = now(),
                errorCode = null,
                errorMessage = null
            )
            context.session?.cleanupDone = true
            if (!store.compareAndSet(setOf(HarnessRuntimeState.STOP_REQUESTED), finished)) {
                val latest = store.current() ?: current
                return@withLock HarnessRuntimeOutcome(
                    latest,
                    liveEndpoint.takeIf { latest.state == HarnessRuntimeState.RUNNING },
                    timedOut = true
                )
            }
            clearLive()
            HarnessRuntimeOutcome(finished)
        }
        logger.record(
            HarnessRuntimeLogEvent(
                if (result.timedOut) "stop_timed_out" else "stopped",
                result.record.environmentId,
                result.record.generation,
                result.record.state,
                errorCode = result.record.errorCode
            )
        )
        return result
    }

    private suspend fun finishForceStop(
        context: StopContext,
        stopped: Boolean,
        hook: StopHookResult
    ): HarnessRuntimeOutcome {
        val result = lifecycleLock.withLock {
            val current = store.current() ?: context.requested
            if (current.generation != context.requested.generation) {
                return@withLock HarnessRuntimeOutcome(
                    current,
                    liveEndpoint.takeIf { current.state == HarnessRuntimeState.RUNNING },
                    timedOut = true
                )
            }
            if (current.state != HarnessRuntimeState.FORCE_STOPPING) {
                return@withLock HarnessRuntimeOutcome(
                    current,
                    liveEndpoint.takeIf { current.state == HarnessRuntimeState.RUNNING },
                    timedOut = !stopped || !hook.completed
                )
            }
            if (stopped && hook.completed) {
                val finished = current.copy(
                    state = HarnessRuntimeState.STOPPED,
                    endedAt = now(),
                    updatedAt = now(),
                    errorCode = hook.errorCode,
                    errorMessage = null
                )
                if (!store.compareAndSet(setOf(HarnessRuntimeState.FORCE_STOPPING), finished)) {
                    val latest = store.current() ?: current
                    return@withLock HarnessRuntimeOutcome(
                        latest,
                        liveEndpoint.takeIf { latest.state == HarnessRuntimeState.RUNNING },
                        timedOut = true
                    )
                }
                clearLive()
                HarnessRuntimeOutcome(finished)
            } else if (stopped) {
                val incomplete = current.copy(
                    state = HarnessRuntimeState.FORCE_STOPPING,
                    errorCode = hook.errorCode
                        ?: if (ownerIdentityMissing(context)) {
                            "HARNESS_OWNER_IDENTITY_MISSING"
                        } else {
                            "HARNESS_STOP_HOOK_FAILED"
                        },
                    errorMessage = null,
                    updatedAt = now()
                )
                if (!store.compareAndSet(setOf(HarnessRuntimeState.FORCE_STOPPING), incomplete)) {
                    val latest = store.current() ?: current
                    return@withLock HarnessRuntimeOutcome(
                        latest,
                        liveEndpoint.takeIf { latest.state == HarnessRuntimeState.RUNNING },
                        timedOut = true
                    )
                }
                // Native ownership is gone, but the Android bridge hook did not finish. Keep the
                // persisted state retryable and clear only the in-memory handle/endpoint.
                clearLive()
                HarnessRuntimeOutcome(incomplete, endpoint = null, timedOut = true)
            } else {
                val failed = current.copy(
                    state = HarnessRuntimeState.FORCE_STOPPING,
                    errorCode = if (ownerIdentityMissing(context)) {
                        "HARNESS_OWNER_IDENTITY_MISSING"
                    } else {
                        "HARNESS_FORCE_STOP_INCOMPLETE"
                    },
                    errorMessage = null,
                    updatedAt = now()
                )
                if (!store.compareAndSet(setOf(HarnessRuntimeState.FORCE_STOPPING), failed)) {
                    val latest = store.current() ?: current
                    return@withLock HarnessRuntimeOutcome(
                        latest,
                        liveEndpoint.takeIf { latest.state == HarnessRuntimeState.RUNNING },
                        timedOut = true
                    )
                }
                HarnessRuntimeOutcome(failed, liveEndpoint, timedOut = true)
            }
        }
        logger.record(
            HarnessRuntimeLogEvent(
                if (stopped && hook.completed) "force_stopped" else "force_stop_incomplete",
                result.record.environmentId,
                result.record.generation,
                result.record.state,
                errorCode = result.record.errorCode
            )
        )
        return result
    }

    private suspend fun gracefulStopWithoutIdentity(
        handle: HarnessProcessHandle,
        timeoutMs: Long
    ): Boolean {
        handle.requestGracefulStop()
        return handle.awaitExit(timeoutMs)
    }

    private suspend fun forceStopWithoutIdentity(handle: HarnessProcessHandle): Boolean {
        handle.requestForceStop()
        return handle.awaitExit(UNIDENTIFIED_FORCE_DEADLINE_MS) || !handle.isAlive()
    }

    /** Process ownership and listener ownership must both be gone before reporting STOPPED. */
    private suspend fun awaitListenerClosed(port: Int?, timeoutMs: Long = LISTENER_RELEASE_DEADLINE_MS): Boolean {
        if (port == null) return true
        if (timeoutMs <= 0L) return false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!probeListener(port)) return true
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            delay(minOf(25L, remainingMs))
        }
        return !probeListener(port)
    }

    private fun stopDeadlineNanos(): Long =
        System.nanoTime() + GRACEFUL_STOP_DEADLINE_MS * 1_000_000L

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    private fun StopContext.remainingMillisOrNull(): Long? {
        if (deadlineNanos == Long.MAX_VALUE) return null
        // A finite deadline must remain distinguishable from the unbounded FORCE path.
        // Returning null after expiry would make the hook helpers treat an exhausted
        // graceful budget as unbounded and could let shutdown work past the ten-second
        // contract. Zero is intentionally passed through; callers coerce it to the
        // smallest bounded timeout or skip waiting where appropriate.
        return remainingMillis(deadlineNanos)
    }

    private suspend fun probeListener(port: Int): Boolean = try {
        listenerProbe(port)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // An unavailable probe is treated as live so a cleanup race is never reported as safe.
        true
    }

    private suspend fun forceStopOwner(context: StopContext): Boolean {
        if (ownerIdentityMissing(context)) return false
        context.session?.let { session ->
            return forceStartOwner(session, context.handle, context.identity, context.requested.port)
        }
        return when {
            context.identity != null -> processSupervisor.forceStop(context.handle, context.identity) &&
                awaitListenerClosed(context.requested.port)
            context.handle != null -> forceStopWithoutIdentity(context.handle) &&
                awaitListenerClosed(context.requested.port)
            else -> true
        }
    }

    private fun ownerIdentityMissing(context: StopContext): Boolean =
        context.session == null &&
            context.handle == null &&
            context.identity == null &&
            context.current.state in ACTIVE_OWNER_STATES &&
            !isPersistedStoppedListenerRetry(context)

    /**
     * A STOPPED row can outlive its process owner while its listener is still draining. The first
     * Force retry records HARNESS_FORCE_STOP_INCOMPLETE; retain that narrow retry identity so a
     * later listener-close probe can finish the already-stopped row without inventing an owner.
     * Active rows with missing identity keep the stricter OWNER_IDENTITY_MISSING outcome.
     */
    private fun isPersistedStoppedListenerRetry(context: StopContext): Boolean =
        context.session == null &&
            context.handle == null &&
            context.identity == null &&
            context.current.state == HarnessRuntimeState.FORCE_STOPPING &&
            context.current.errorCode == "HARNESS_FORCE_STOP_INCOMPLETE" &&
            context.current.endedAt != null

    private suspend fun forceStartOwner(
        session: StartSession,
        handle: HarnessProcessHandle?,
        identity: HarnessProcessIdentity?,
        port: Int?
    ): Boolean = session.cleanupMutex.withLock {
        val alreadyClean = lifecycleLock.withLock { session.cleanupDone }
        if (alreadyClean) return@withLock awaitListenerClosed(port)
        val processCleaned = try {
            when {
                identity != null -> processSupervisor.forceStop(handle, identity)
                handle != null -> forceStopWithoutIdentity(handle)
                else -> true
            }
        } catch (_: Throwable) {
            false
        }
        val cleaned = processCleaned && awaitListenerClosed(port)
        lifecycleLock.withLock {
            session.cleanupDone = cleaned
            if (cleaned && liveHandle === handle) clearLive()
        }
        cleaned
    }

    private suspend fun ensureStartActive(session: StartSession) {
        lifecycleLock.lock()
        try {
            ensureStartActiveLocked(session)
            if (store.current()?.state != HarnessRuntimeState.STARTING) {
                throw HarnessRuntimeException("HARNESS_START_CANCELLED", "Harness state changed during Start")
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun ensureStartActiveLocked(session: StartSession) {
        if (activeStart !== session || session.cancelMode != null) {
            throw HarnessRuntimeException("HARNESS_START_CANCELLED", "Harness start was cancelled")
        }
    }

    private suspend fun invokeStartStopHook(
        session: StartSession,
        mode: HarnessStopMode,
        timeoutMs: Long? = null
    ): StopHookResult {
        val decision = lifecycleLock.withLock {
            when {
                !session.hookPrepared -> 0
                session.stopHookInvoked -> 1
                else -> {
                    session.stopHookInvoked = true
                    2
                }
            }
        }
        if (decision == 0) return StopHookResult(completed = true)
        if (decision == 1) return awaitStopHookCompletion(session, timeoutMs)

        val result = try {
            if (timeoutMs != null && timeoutMs <= 0L) {
                logger.record(
                    HarnessRuntimeLogEvent(
                        "stop_hook_timed_out",
                        session.environmentId,
                        session.generation,
                        errorCode = "HARNESS_STOP_HOOK_TIMEOUT"
                    )
                )
                StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
            } else {
                val request = HarnessStopRequest(session.environmentId, session.generation, mode)
                suspend fun invokeHook() {
                    session.hookMutex.withLock {
                        hooks.beforeStop(request)
                    }
                }
                val completed = if (timeoutMs == null) {
                    invokeHook()
                    true
                } else {
                    withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                        invokeHook()
                        true
                    } ?: false
                }
                if (!completed) {
                    logger.record(
                        HarnessRuntimeLogEvent(
                            "stop_hook_timed_out",
                            session.environmentId,
                            session.generation,
                            errorCode = "HARNESS_STOP_HOOK_TIMEOUT"
                        )
                    )
                    StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
                } else {
                    StopHookResult(completed = true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val code = errorCode(error)
            logger.record(
                HarnessRuntimeLogEvent(
                    "stop_hook_failed",
                    session.environmentId,
                    session.generation,
                    errorCode = code
                )
            )
            StopHookResult(false, code)
        }
        session.stopHookCompletion.complete(result)
        return result
    }

    private suspend fun awaitStopHookCompletion(
        session: StartSession,
        timeoutMs: Long?
    ): StopHookResult {
        if (timeoutMs != null && timeoutMs <= 0L) {
            return StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
        }
        return if (timeoutMs == null) {
            session.stopHookCompletion.await()
        } else {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                session.stopHookCompletion.await()
            } ?: StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
        }
    }

    private suspend fun reconcileStaleLocked(current: HarnessRuntimeRecord?) {
        if (current == null || current.state == HarnessRuntimeState.FAILED) return
        if (current.state == HarnessRuntimeState.STOP_REQUESTED ||
            current.state == HarnessRuntimeState.FORCE_STOPPING
        ) return
        if (activeStart != null || liveHandle?.isAlive() == true) return
        val persistedIdentity = identityFrom(current)
        val identity = persistedIdentity ?: processSupervisor.recoverIdentity(
            ownerMarker = current.generation,
            expectedUid = AndroidProcess.myUid()
        )
        val identityRecoveredFromLedger = persistedIdentity == null && identity != null
        val ownerAlive = identity?.let(processSupervisor::isOwnerAlive) == true
        val listenerAlive = current.port?.let { probeListener(it) } == true
        if (current.state == HarnessRuntimeState.STOPPED && !ownerAlive && !listenerAlive) return
        val identityMissing = identity == null && current.state in ACTIVE_OWNER_STATES
        var cleaned = !identityMissing
        if (identity != null && ownerAlive) {
            cleaned = try {
                processSupervisor.forceStop(null, identity)
            } catch (_: Throwable) {
                false
            }
        }
        cleaned = cleaned && awaitListenerClosed(current.port)
        if (!cleaned || (identity != null && processSupervisor.isOwnerAlive(identity))) {
            val errorCode = if (identityMissing) {
                "HARNESS_OWNER_IDENTITY_MISSING"
            } else {
                "HARNESS_STALE_OWNER_LIVE"
            }
            val interrupted = current.copy(
                state = HarnessRuntimeState.INTERRUPTED,
                endedAt = now(),
                updatedAt = now(),
                errorCode = errorCode,
                errorMessage = null
            )
            store.replace(interrupted)
            throw HarnessRuntimeException(errorCode, "Previous Harness owner could not be cleaned")
        }
        store.replace(
            current.copy(
                state = HarnessRuntimeState.INTERRUPTED,
                endedAt = now(),
                updatedAt = now(),
                errorCode = if (identityRecoveredFromLedger) {
                    "HARNESS_PROCESS_RECOVERED_FROM_LEDGER"
                } else {
                    "HARNESS_STALE_OWNER"
                },
                errorMessage = null
            )
        )
    }

    private suspend fun updateStarting(
        starting: HarnessRuntimeRecord,
        transform: (HarnessRuntimeRecord) -> HarnessRuntimeRecord
    ) {
        val current = store.current() ?: starting
        store.compareAndSet(setOf(HarnessRuntimeState.STARTING), transform(current))
    }

    private fun identityFrom(record: HarnessRuntimeRecord): HarnessProcessIdentity? {
        val pid = record.brokerPid ?: return null
        val start = record.processStartTicks ?: return null
        val group = record.processGroupId ?: return null
        return HarnessProcessIdentity(
            brokerPid = pid,
            brokerStartTimeTicks = start,
            brokerUid = AndroidProcess.myUid(),
            processGroupId = group,
            childPid = record.childPid,
            childStartTimeTicks = record.childStartTicks,
            ownerMarker = record.generation,
            nodePid = record.nodePid,
            nodeStartTimeTicks = record.nodeStartTicks
        )
    }

    private suspend fun runStopHook(
        record: HarnessRuntimeRecord,
        mode: HarnessStopMode,
        timeoutMs: Long? = null
    ): StopHookResult {
        if (timeoutMs != null && timeoutMs <= 0L) {
            logger.record(
                HarnessRuntimeLogEvent(
                    "stop_hook_timed_out",
                    record.environmentId,
                    record.generation,
                    errorCode = "HARNESS_STOP_HOOK_TIMEOUT"
                )
            )
            return StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
        }
        try {
            val request = HarnessStopRequest(record.environmentId, record.generation, mode)
            val completed = if (timeoutMs == null) {
                hooks.beforeStop(request)
                true
            } else {
                withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                    hooks.beforeStop(request)
                    true
                } ?: false
            }
            if (!completed) {
                logger.record(
                    HarnessRuntimeLogEvent(
                        "stop_hook_timed_out",
                        record.environmentId,
                        record.generation,
                        errorCode = "HARNESS_STOP_HOOK_TIMEOUT"
                    )
                )
                return StopHookResult(false, "HARNESS_STOP_HOOK_TIMEOUT")
            }
            return StopHookResult(completed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.record(
                HarnessRuntimeLogEvent(
                    "stop_hook_failed",
                    record.environmentId,
                    record.generation,
                    errorCode = errorCode(error)
                )
            )
            return StopHookResult(false, errorCode(error))
        }
    }

    private fun clearLive() {
        liveHandle = null
        liveIdentity = null
        liveEndpoint = null
    }

    private fun stoppedRecord(): HarnessRuntimeRecord = HarnessRuntimeRecord(
        environmentId = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID,
        state = HarnessRuntimeState.STOPPED,
        generation = UUID.randomUUID().toString(),
        endedAt = now(),
        updatedAt = now()
    )

    private fun errorCode(error: Throwable): String {
        var current: Throwable? = error
        repeat(16) {
            val cause = current ?: return@repeat
            if (cause is HarnessRuntimeException) return cause.code
            current = cause.cause
        }
        return "HARNESS_${error::class.java.simpleName.uppercase(java.util.Locale.ROOT)}"
    }

    /** Durable messages contain only a stable failure class; request/content text never persists. */
    private fun safeMessage(error: Throwable): String =
        "Harness runtime failure (${error::class.java.simpleName})"

    companion object {
        fun forAndroid(
            context: Context,
            store: HarnessRuntimeStore,
            hooks: HarnessRuntimeHooks,
            logger: HarnessRuntimeMetadataLogger = AndroidHarnessRuntimeMetadataLogger()
        ): HarnessRuntimeController {
            val binaries = HarnessNativeBinaries(
                brokerPath = AgentProotNativeBinaryProvider.requireBroker(context),
                prootPath = AgentProotNativeBinaryProvider.requireProot(context),
                loaderPath = AgentProotNativeBinaryProvider.locateLoader(context)
            )
            return HarnessRuntimeController(
                store = store,
                environmentProvider = AndroidHarnessEnvironmentProvider(context),
                payloadProvider = AssetHarnessPayloadProvider(context),
                binaries = binaries,
                processLauncher = AndroidHarnessProcessLauncher(context),
                readinessProbe = HttpHarnessReadinessProbe(readinessReceipt = File(
                    HarnessRuntimePaths.runtimeRoot(context, HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID),
                    "run/harness-ready.json"
                )),
                processSupervisor = AndroidHarnessProcessSupervisor(
                    ledgerFile = File(
                        HarnessRuntimePaths.runtimeRoot(
                            context,
                            HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID
                        ),
                        "run/harness-processes.ledger"
                    ).canonicalFile
                ),
                hooks = hooks,
                portAllocator = AndroidHarnessPortAllocator(),
                logger = logger
            )
        }
    }
}

class AndroidHarnessPortAllocator : HarnessPortAllocator {
    override fun allocate(preferredPort: Int?): Int {
        val socket = if (preferredPort == null) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        } else {
            ServerSocket(preferredPort, 1, InetAddress.getByName("127.0.0.1"))
        }
        return socket.use { it.localPort }
    }
}

class AndroidHarnessRuntimeMetadataLogger : HarnessRuntimeMetadataLogger {
    override fun record(event: HarnessRuntimeLogEvent) {
        Log.i(
            TAG,
            "event=${event.event} env=${event.environmentId} generation=${event.generation} " +
                "state=${event.state} phase=${event.phase} durationMs=${event.durationMs} " +
                "error=${event.errorCode} exitCode=${event.exitCode}"
        )
    }

    private companion object {
        const val TAG = "HarnessRuntime"
    }
}

private suspend fun isLoopbackListenerOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), LISTENER_PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)
}
