package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.LlamaOcrSettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.model.LlamaServerSessionIds
import com.example.llamadroid.data.repository.launchProfileForCardPort
import com.example.llamadroid.util.NativeProcessCleanup
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal enum class LlamaOcrRuntimeStage {
    CAPTURING_SERVERS,
    PAUSING_SERVERS,
    STARTING_OCR,
    STOPPING_OCR,
    RESTORING_SERVERS
}

internal class LlamaOcrRuntimeBlockedException(message: String) : IllegalStateException(message)

/**
 * Coordinates the exclusive local llama runtime used by GGUF OCR.
 *
 * The card service and legacy service live in different Android processes, so the coordinator does
 * not use a process-local state flag as ownership.  The durable lease and the session command
 * token are the ownership boundary; all profile capture happens before the first stop command.
 */
internal class LlamaOcrRuntimeCoordinator(private val context: Context) {
    private val appContext = context.applicationContext
    private val stateStore = LlamaServerSessionStateStore(appContext)
    private val settings = SettingsRepository(appContext)

    suspend fun <T> withExclusiveOcrSession(
        ocrSettings: LlamaOcrSettingsSnapshot,
        onStage: (LlamaOcrRuntimeStage) -> Unit = {},
        block: suspend () -> T
    ): T = processMutex.withLock {
        withContext(Dispatchers.IO) {
            execute(ocrSettings, onStage, block)
        }
    }

    private suspend fun <T> execute(
        ocrSettings: LlamaOcrSettingsSnapshot,
        onStage: (LlamaOcrRuntimeStage) -> Unit,
        block: suspend () -> T
    ): T {
        if (distributedRuntimeIsActive()) {
            throw LlamaOcrRuntimeBlockedException(
                appContext.getString(R.string.pdf_ocr_runtime_distributed_active)
            )
        }
        if (LlamaOcrExclusiveLeaseStore.read(appContext) != null) {
            throw LlamaOcrRuntimeBlockedException(
                appContext.getString(R.string.pdf_ocr_runtime_recovery_pending)
            )
        }

        val ocrProfile = LlamaServerLauncher.buildLlamaOcrLaunchProfile(ocrSettings)
        require(ocrProfile.hasModel()) {
            appContext.getString(R.string.pdf_ocr_llama_error_missing_model)
        }
        require(ocrProfile.mmprojPath?.isNotBlank() == true) {
            appContext.getString(R.string.pdf_ocr_llama_error_missing_mmproj)
        }

        emitStage(onStage, LlamaOcrRuntimeStage.CAPTURING_SERVERS)
        val captured = captureActiveRuntimes()
        if (!ocrSettings.temporarilyReplaceRunningServer && captured.isNotEmpty()) {
            throw LlamaOcrRuntimeBlockedException(
                appContext.getString(R.string.pdf_ocr_runtime_local_active)
            )
        }
        val duplicatePort = captured
            .mapNotNull(LlamaOcrCapturedRuntime::port)
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicatePort == null) {
            "Multiple local llama runtimes claim port ${duplicatePort?.key}; stop the conflicting server before OCR."
        }

        // A standalone OCR launch from an earlier app version may have left internal:ocr alive.
        // Validate every restorable card first, so a missing preset always aborts before any stop.
        stopOrphanedOcrSession(ocrSettings.port)

        var lease = LlamaOcrExclusiveLeaseStore.create(appContext, ocrProfile, captured)
        record(lease, "capture", ocrSettings)
        var primaryFailure: Throwable? = null
        try {
            lease = phase(lease, LlamaOcrLeasePhase.PAUSING, ocrSettings)
            emitStage(onStage, LlamaOcrRuntimeStage.PAUSING_SERVERS)
            pause(captured, lease.token)

            lease = phase(lease, LlamaOcrLeasePhase.OCR_STARTING, ocrSettings)
            emitStage(onStage, LlamaOcrRuntimeStage.STARTING_OCR)
            LlamaServerLauncher.startForOcr(appContext, ocrSettings, lease.token).getOrThrow()
            waitForSessionRunning(
                sessionId = LlamaServerSessionIds.OCR,
                port = ocrProfile.serverPort,
                timeoutMs = OCR_START_TIMEOUT_MS
            )
            lease = phase(lease, LlamaOcrLeasePhase.OCR_RUNNING, ocrSettings)
            record(lease, "healthy", ocrSettings)
            return block()
        } catch (error: Throwable) {
            primaryFailure = error
            recordFailure(lease, "ocr_failed", error, ocrSettings)
            throw error
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                cleanupAndRestore(lease, ocrSettings, onStage)
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private suspend fun stopOrphanedOcrSession(port: Int) {
        val snapshot = stateStore.readAll().firstOrNull { it.sessionId == LlamaServerSessionIds.OCR }
        val active = snapshot?.status in ACTIVE_SESSION_STATUSES
        if (!active) return
        recordSimple(
            event = "orphan_ocr_stop",
            details = "session=${LlamaServerSessionIds.OCR} port=${snapshot?.port ?: port}"
        )
        LlamaServerLauncher.stopForOcr(appContext).getOrThrow()
        waitForSessionStopped(LlamaServerSessionIds.OCR, snapshot?.port ?: port)
    }

    /** Capture profiles before any stop so a missing live-linked preset cannot cause data loss. */
    private suspend fun captureActiveRuntimes(): List<LlamaOcrCapturedRuntime> {
        val database = AppDatabase.getDatabase(appContext)
        val cards = database.llamaServerCardDao().observeCards().first()
        val cardBySession = cards.associateBy { it.sessionId }
        val snapshots = stateStore.readAll().associateBy { it.sessionId }
        val owners = LlamaServerSessionOwnerStore(appContext).readAll().associateBy { it.sessionId }
        val active = mutableListOf<LlamaOcrCapturedRuntime>()

        cards.forEach { card ->
            val snapshot = snapshots[card.sessionId]
            val owner = owners[card.sessionId]
            val ownerAlive = owner?.let {
                NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    rootPid = it.pid,
                    expectedStartTimeTicks = it.processStartTimeTicks,
                    expectedPort = it.port
                )
            } == true
            val status = snapshot?.status
            if (!sessionSnapshotIsCapturable(snapshot, ownerAlive)) return@forEach

            val preset = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
                ?: throw IllegalStateException(
                    "Cannot pause server card ${card.id}: its saved llama preset is missing."
                )
            val port = snapshot?.port ?: owner?.port ?: card.port
            val profile = owner?.launchProfileJson
                ?.let(LlamaServerLaunchProfile::decode)
                ?.copy(serverPort = port)
                ?: preset.launchProfileForCardPort(port)
            require(profile.hasModel()) {
                "Cannot pause server card ${card.id}: its saved llama preset has no model."
            }
            active += LlamaOcrCapturedRuntime(
                sessionId = card.sessionId,
                kind = LlamaOcrCapturedRuntimeKind.CARD,
                port = port,
                launchProfileJson = LlamaServerLaunchProfile.encode(profile),
                status = status,
                ownerPid = owner?.pid,
                ownerStartTimeTicks = owner?.processStartTimeTicks
            )
        }

        // Internal general is not represented by a card but can be used by a few legacy flows.
        // Persisting its complete profile lets recovery restart it without changing preferences.
        snapshots.values
            .filter { it.sessionId == LlamaServerSessionIds.GENERAL }
            .filter { snapshot ->
                val owner = owners[snapshot.sessionId]
                sessionSnapshotIsCapturable(
                    snapshot,
                    ownerAlive = owner?.let {
                        NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                            it.pid,
                            it.processStartTimeTicks,
                            it.port
                        )
                    } == true
                )
            }
            .forEach { snapshot ->
                val owner = owners[snapshot.sessionId]
                val port = snapshot.port ?: owner?.port ?: settings.serverPort.value
                val profile = owner?.launchProfileJson
                    ?.let(LlamaServerLaunchProfile::decode)
                    ?.copy(serverPort = port)
                    ?: LlamaServerLaunchProfile.capture(settings).copy(serverPort = port)
                require(profile.hasModel()) {
                    "Cannot pause the general llama session because no model is configured."
                }
                active += LlamaOcrCapturedRuntime(
                    sessionId = snapshot.sessionId,
                    kind = LlamaOcrCapturedRuntimeKind.RESERVED,
                    port = port,
                    launchProfileJson = LlamaServerLaunchProfile.encode(profile),
                    status = snapshot.status,
                    ownerPid = snapshot.pid,
                    ownerStartTimeTicks = null
                )
            }

        // A state row whose card was deleted cannot be restored safely.  Fail before pausing any
        // server instead of silently losing the user's running session.
        snapshots.values
            .filter {
                it.sessionId != LlamaServerSessionIds.OCR &&
                    sessionSnapshotIsCapturable(it, owners[it.sessionId]?.let { owner ->
                        NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                            owner.pid,
                            owner.processStartTimeTicks,
                            owner.port
                        )
                    } == true)
            }
            .filterNot { it.sessionId in cardBySession || it.sessionId == LlamaServerSessionIds.GENERAL }
            .firstOrNull()
            ?.let { snapshot ->
                throw IllegalStateException(
                    "Cannot pause local llama session ${snapshot.sessionId}: no restorable launch profile is available."
                )
            }

        val legacyState = LlamaService.state.value
        val legacyOwner = LlamaRuntimeOwnerStore.load(appContext)
        val legacyOwnerAlive = legacyOwner?.let {
            NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                rootPid = it.pid,
                expectedStartTimeTicks = it.processStartTimeTicks,
                expectedPort = it.port
            )
        } == true
        if (legacyState.isActiveLlamaState() || legacyOwnerAlive) {
            val profile = LlamaServerLaunchProfile.capture(settings)
            val port = (legacyState as? ServerState.Running)?.port
                ?: legacyOwner?.port
                ?: profile.serverPort
            val exactProfile = profile.copy(serverPort = port)
            require(exactProfile.hasModel()) {
                "Cannot pause the legacy llama server because no model is configured."
            }
            active += LlamaOcrCapturedRuntime(
                sessionId = LEGACY_SESSION_ID,
                kind = LlamaOcrCapturedRuntimeKind.LEGACY,
                port = port,
                launchProfileJson = LlamaServerLaunchProfile.encode(exactProfile),
                status = legacyState.toLlamaServerSessionStatus(),
                ownerPid = legacyOwner?.pid,
                ownerStartTimeTicks = legacyOwner?.processStartTimeTicks
            )
        }

        recordSimple(
            event = "capture_complete",
            details = "count=${active.size} sessions=${active.joinToString(",") { it.sessionId }}"
        )
        return active
    }

    private suspend fun pause(
        captured: List<LlamaOcrCapturedRuntime>,
        leaseToken: String
    ) {
        captured.forEach { runtime ->
            when (runtime.kind) {
                LlamaOcrCapturedRuntimeKind.LEGACY -> {
                    LlamaServerLauncher.stopLegacy(appContext, leaseToken).getOrThrow()
                    waitForLegacyStopped(runtime.port ?: settings.serverPort.value)
                }
                LlamaOcrCapturedRuntimeKind.CARD,
                LlamaOcrCapturedRuntimeKind.RESERVED -> {
                    LlamaServerLauncher.stopSession(appContext, runtime.sessionId, leaseToken).getOrThrow()
                    runtime.port?.let { waitForSessionStopped(runtime.sessionId, it) }
                }
            }
            recordSimple(
                event = "paused",
                details = "session=${runtime.sessionId} kind=${runtime.kind.name.lowercase()} port=${runtime.port ?: "unknown"}"
            )
        }
        recordSimple(
            event = "ports_released",
            details = "count=${captured.size} ports=${captured.mapNotNull { it.port }.joinToString(",")}"
        )
    }

    private suspend fun cleanupAndRestore(
        originalLease: LlamaOcrExclusiveLease,
        ocrSettings: LlamaOcrSettingsSnapshot,
        onStage: (LlamaOcrRuntimeStage) -> Unit = {}
    ): Throwable? {
        var lease = originalLease
        val stopFailure = runCatching {
            emitStage(onStage, LlamaOcrRuntimeStage.STOPPING_OCR)
            lease = phase(lease, LlamaOcrLeasePhase.STOPPING_OCR, ocrSettings)
            LlamaServerLauncher.stopForOcr(appContext, lease.token).getOrThrow()
            waitForSessionStopped(
                sessionId = LlamaServerSessionIds.OCR,
                port = lease.ocrPort,
                requirePortFree = originalLease.phase in setOf(
                    LlamaOcrLeasePhase.OCR_STARTING,
                    LlamaOcrLeasePhase.OCR_RUNNING,
                    LlamaOcrLeasePhase.STOPPING_OCR
                )
            )
            record(lease, "stopped", ocrSettings)
        }.exceptionOrNull()
        if (stopFailure != null) {
            recordFailure(lease, "ocr_stop_failed", stopFailure, ocrSettings)
            return stopFailure
        }

        val restoreFailures = mutableListOf<Throwable>()
        emitStage(onStage, LlamaOcrRuntimeStage.RESTORING_SERVERS)
        runCatching { lease = phase(lease, LlamaOcrLeasePhase.RESTORING, ocrSettings) }
            .onFailure(restoreFailures::add)
        val translationPort = settings.serverPort.value
        val ordered = orderLlamaOcrRestoration(lease.capturedRuntimes, translationPort)
        ordered.forEach { captured ->
            val failure = runCatching {
                val profile = requireNotNull(captured.launchProfile()) {
                    "Captured launch profile for ${captured.sessionId} is invalid."
                }
                if (!runtimeIsAlreadyHealthy(captured)) {
                    when (captured.kind) {
                        LlamaOcrCapturedRuntimeKind.LEGACY -> {
                            LlamaServerLauncher.startLegacyProfile(appContext, profile, lease.token).getOrThrow()
                            waitForLegacyRunning(captured.port ?: profile.serverPort)
                        }
                        LlamaOcrCapturedRuntimeKind.CARD,
                        LlamaOcrCapturedRuntimeKind.RESERVED -> {
                            LlamaServerLauncher.startSession(
                                context = appContext,
                                sessionId = captured.sessionId,
                                profile = profile,
                                portOverride = captured.port ?: profile.serverPort,
                                leaseToken = lease.token
                            ).getOrThrow()
                            waitForSessionRunning(
                                captured.sessionId,
                                captured.port ?: profile.serverPort,
                                RESTORE_START_TIMEOUT_MS
                            )
                        }
                    }
                }
                lease = LlamaOcrExclusiveLeaseStore.markRestored(appContext, lease, captured.sessionId)
                recordSimple(
                    event = "restored",
                    details = "session=${captured.sessionId} kind=${captured.kind.name.lowercase()} port=${captured.port ?: profile.serverPort}"
                )
            }.exceptionOrNull()
            if (failure != null) {
                restoreFailures += failure
                recordFailure(lease, "restore_failed", failure, ocrSettings)
            }
        }

        if (restoreFailures.isEmpty()) {
            LlamaOcrExclusiveLeaseStore.clear(appContext, lease.token)
            recordSimple(
                event = "restore_complete",
                details = "count=${ordered.size} translationFirst=true"
            )
            return null
        }

        val combined = IllegalStateException(
            "${restoreFailures.size} local llama runtime(s) could not be restored after GGUF OCR."
        )
        restoreFailures.drop(1).forEach(combined::addSuppressed)
        runCatching {
            lease = phase(lease, LlamaOcrLeasePhase.RESTORE_FAILED, ocrSettings)
        }
        return combined
    }

    private suspend fun waitForSessionRunning(sessionId: String, port: Int, timeoutMs: Long) {
        withTimeout(timeoutMs) {
            while (true) {
                val snapshot = stateStore.readAll().firstOrNull { it.sessionId == sessionId }
                when {
                    snapshot?.status == LlamaServerSessionStatus.RUNNING &&
                        (snapshot.port == null || snapshot.port == port) -> return@withTimeout
                    snapshot?.status == LlamaServerSessionStatus.ERROR -> {
                        throw IllegalStateException(snapshot.error ?: "Llama session $sessionId failed to start.")
                    }
                }
                delay(250L)
            }
        }
    }

    private suspend fun waitForSessionStopped(
        sessionId: String,
        port: Int,
        timeoutMs: Long = STOP_TIMEOUT_MS,
        requirePortFree: Boolean = true
    ) {
        withTimeout(timeoutMs) {
            while (true) {
                val snapshot = stateStore.readAll().firstOrNull { it.sessionId == sessionId }
                val statusActive = snapshot?.status in ACTIVE_SESSION_STATUSES
                val portReleased = !requirePortFree || checkLlamaServerPort("127.0.0.1", port).available
                if (!statusActive && portReleased) return@withTimeout
                delay(250L)
            }
        }
    }

    private fun runtimeIsAlreadyHealthy(runtime: LlamaOcrCapturedRuntime): Boolean {
        val expectedPort = runtime.port ?: return false
        return when (runtime.kind) {
            LlamaOcrCapturedRuntimeKind.LEGACY -> {
                val owner = LlamaRuntimeOwnerStore.load(appContext) ?: return false
                owner.port == expectedPort && NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    owner.pid,
                    owner.processStartTimeTicks,
                    owner.port
                )
            }
            LlamaOcrCapturedRuntimeKind.CARD,
            LlamaOcrCapturedRuntimeKind.RESERVED -> {
                val owner = LlamaServerSessionOwnerStore(appContext).get(runtime.sessionId) ?: return false
                owner.port == expectedPort && NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(
                    owner.pid,
                    owner.processStartTimeTicks,
                    owner.port
                )
            }
        }
    }

    private suspend fun waitForLegacyRunning(port: Int) {
        withTimeout(OCR_START_TIMEOUT_MS) {
            while (true) {
                when (val state = LlamaService.state.value) {
                    is ServerState.Running -> if (state.port == port || port <= 0) return@withTimeout
                    is ServerState.Error -> throw IllegalStateException(state.message)
                    else -> Unit
                }
                delay(250L)
            }
        }
    }

    private suspend fun waitForLegacyStopped(port: Int, timeoutMs: Long = STOP_TIMEOUT_MS) {
        withTimeout(timeoutMs) {
            while (true) {
                val active = LlamaService.state.value.isActiveLlamaState()
                if (!active && checkLlamaServerPort("127.0.0.1", port).available) return@withTimeout
                delay(250L)
            }
        }
    }

    private fun distributedRuntimeIsActive(): Boolean =
        DistributedService.mode.value != DistributedMode.NONE ||
            DistributedService.isRunning.value ||
            DistributedService.inferenceRunning.value ||
            DistributedMasterRuntimeState.state.value.isActiveLlamaState()

    private fun phase(
        lease: LlamaOcrExclusiveLease,
        phase: LlamaOcrLeasePhase,
        ocrSettings: LlamaOcrSettingsSnapshot
    ): LlamaOcrExclusiveLease = LlamaOcrExclusiveLeaseStore.updatePhase(appContext, lease, phase).also {
        record(it, phase.name.lowercase(), ocrSettings)
    }

    private fun record(
        lease: LlamaOcrExclusiveLease,
        event: String,
        ocrSettings: LlamaOcrSettingsSnapshot
    ) {
        recordSimple(
            event = event,
            details = "phase=${lease.phase.name.lowercase()} captured=${lease.capturedRuntimes.size} " +
                "ocrSession=${lease.ocrSessionId} ocrPort=${lease.ocrPort} " +
                "preset=${ocrSettings.promptPreset.name} context=${ocrSettings.contextSize}"
        )
    }

    private fun recordFailure(
        lease: LlamaOcrExclusiveLease,
        event: String,
        error: Throwable,
        ocrSettings: LlamaOcrSettingsSnapshot
    ) {
        val sanitized = error.message.orEmpty()
            .replace(Regex("/[^\\s]+"), "<path>")
            .replace(Regex("\\s+"), " ")
            .take(180)
        recordSimple(
            event = event,
            details = "phase=${lease.phase.name.lowercase()} preset=${ocrSettings.promptPreset.name} " +
                "context=${ocrSettings.contextSize} error=${error.javaClass.simpleName}:$sanitized"
        )
    }

    private fun recordSimple(event: String, details: String) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "llama_ocr_runtime",
                mode = "LLAMA_CPP_GGUF",
                event = event,
                details = details.take(1024)
            )
        }.onFailure { DebugLog.log("LlamaOcrRuntimeCoordinator diagnostics failed: ${it.message}") }
    }

    private fun emitStage(callback: (LlamaOcrRuntimeStage) -> Unit, stage: LlamaOcrRuntimeStage) {
        runCatching { callback(stage) }
            .onFailure { DebugLog.log("LlamaOcrRuntimeCoordinator stage callback failed: ${it.message}") }
    }

    companion object {
        private const val LEGACY_SESSION_ID = "legacy:llama"
        private const val OCR_START_TIMEOUT_MS = 180_000L
        private const val RESTORE_START_TIMEOUT_MS = 180_000L
        private const val STOP_TIMEOUT_MS = 45_000L
        private const val DEFAULT_CONTEXT_SIZE = 8_192
        private val processMutex = Mutex()
        private val recoveryInProgress = AtomicBoolean(false)
        private val ACTIVE_SESSION_STATUSES = setOf(
            LlamaServerSessionStatus.STARTING,
            LlamaServerSessionStatus.LOADING,
            LlamaServerSessionStatus.RUNNING
        )

        /** Called from the main-process Application startup before a new OCR job is accepted. */
        suspend fun recoverStaleLease(context: Context): Result<Unit> = processMutex.withLock {
            if (!recoveryInProgress.compareAndSet(false, true)) return@withLock Result.success(Unit)
            try {
                withContext(Dispatchers.IO) {
                    val coordinator = LlamaOcrRuntimeCoordinator(context.applicationContext)
                    val lease = LlamaOcrExclusiveLeaseStore.read(context) ?: return@withContext Result.success(Unit)
                    coordinator.recordSimple(
                        event = "stale_lease_recovery",
                        details = "phase=${lease.phase.name.lowercase()} captured=${lease.capturedRuntimes.size} " +
                            "ocrSession=${lease.ocrSessionId} ocrPort=${lease.ocrPort}"
                    )
                    if (coordinator.distributedRuntimeIsActive()) {
                        return@withContext Result.failure(
                            IllegalStateException(
                                "A distributed llama runtime is active; GGUF OCR lease restoration is paused."
                            )
                        )
                    }
                    val failure = coordinator.cleanupAndRestore(
                        lease,
                        LlamaOcrSettingsSnapshot(
                            modelPath = null,
                            mmprojPath = null,
                            promptPreset = com.example.llamadroid.data.LlamaOcrPromptPreset.GENERIC_OCR,
                            customPrompt = null,
                            contextSize = DEFAULT_CONTEXT_SIZE,
                            maxTokens = 128,
                            port = lease.ocrPort,
                            flashAttention = false,
                            cacheRam = 0,
                            parallel = 1,
                            customFlags = null,
                            commandTemplate = null,
                            temporarilyReplaceRunningServer = true
                        )
                    )
                    failure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
                }
            } finally {
                recoveryInProgress.set(false)
            }
        }
    }
}

internal fun sessionSnapshotIsCapturable(
    snapshot: LlamaServerSessionSnapshot?,
    ownerAlive: Boolean,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (ownerAlive) return true
    if (snapshot?.status !in setOf(
            LlamaServerSessionStatus.STARTING,
            LlamaServerSessionStatus.LOADING,
            LlamaServerSessionStatus.RUNNING
        )
    ) return false
    val updatedAt = snapshot?.updatedAt ?: return false
    return now - updatedAt in 0L..60_000L
}

internal fun orderLlamaOcrRestoration(
    runtimes: List<LlamaOcrCapturedRuntime>,
    translationPort: Int
): List<LlamaOcrCapturedRuntime> = runtimes.sortedWith(
    compareBy<LlamaOcrCapturedRuntime> {
        when {
            it.port == translationPort -> 0
            it.kind == LlamaOcrCapturedRuntimeKind.LEGACY -> 1
            it.sessionId == LlamaServerSessionIds.GENERAL -> 1
            else -> 2
        }
    }.thenBy { it.sessionId }
)

private fun ServerState.isActiveLlamaState(): Boolean =
    this is ServerState.Starting || this is ServerState.Loading || this is ServerState.Running
