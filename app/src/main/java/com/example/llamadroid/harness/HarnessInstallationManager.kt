package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessInstallationDataLayout
import com.example.llamadroid.data.db.HarnessInstallationPurpose
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.HarnessRecoveryTerminalSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class HarnessInstallationOperation(
    val kind: String = "",
    val runtimeId: String? = null,
    val phase: String = "IDLE",
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val errorCode: String? = null,
    val cancellable: Boolean = false,
) {
    val busy: Boolean get() = phase !in setOf("IDLE", "COMPLETE", "FAILED", "CANCELLED")
}

/** Single catalog and maintenance authority shared by every native/contextual entry point. */
class HarnessInstallationManager internal constructor(
    private val context: Context,
    val database: AppDatabase = AppDatabase.getDatabase(context),
    private val files: HarnessInstallationFiles = HarnessInstallationFiles(context),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalog = database.harnessInstallationDao()
    private val preferences = context.getSharedPreferences(HarnessRuntimeScope.CONTROL_PREFERENCES, Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(
        if (preferences.contains("selected")) preferences.getString("selected", null)
        else HarnessRuntimeScope.LEGACY_RUNTIME_ID
    )
    val selectedId = selected.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val contentRevision = mutableRevision.asStateFlow()
    val installations = catalog.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val mutableOperation = MutableStateFlow(HarnessInstallationOperation())
    val operation = mutableOperation.asStateFlow()
    private val operations = Mutex()
    @Volatile private var currentJob: Deferred<*>? = null
    // KTX edit discards commit's result; adoption must confirm the marker reached storage.
    @SuppressLint("UseKtx")
    private val initialization = scope.async {
        if (catalog.getAll().isEmpty() && !preferences.contains("initialized")) {
            catalog.insert(AgentProotEnvironmentEntity(
                id = HarnessRuntimeScope.LEGACY_RUNTIME_ID,
                displayName = context.getString(R.string.harness_installation_default_name),
                purpose = HarnessInstallationPurpose.HARNESS,
                dataLayout = HarnessInstallationDataLayout.LEGACY,
            ))
        }
        check(preferences.edit().putBoolean("initialized", true).commit())
        if (selected.value?.let { catalog.getById(it) } == null) persistSelection(null)
        // Recovery remains visible and is retried explicitly. Never hide a partially installed
        // tree behind a normal Start or replay previously queued agent work on startup.
        val pending = try { files.journal.pending() } catch (_: Exception) {
            mutableOperation.value = HarnessInstallationOperation("RECOVER", phase = "FAILED", errorCode = "HARNESS_OPERATION_INVALID")
            return@async
        }
        catalog.getAll().filter { it.status in setOf("INSTALLING", "UPDATING", "DELETING") && pending.none { receipt -> receipt.runtimeId == it.id } }
            .forEach { catalog.update(it.copy(status = AgentProotEnvironmentStatus.BROKEN)) }
        pending.firstOrNull()?.let { row ->
            mutableOperation.value = HarnessInstallationOperation(row.kind, row.runtimeId, "FAILED",
                errorCode = row.failureCode ?: "HARNESS_OPERATION_INTERRUPTED")
        }
    }

    suspend fun ready() { initialization.await() }

    internal suspend fun <T> withFiles(runtimeId: String, action: suspend () -> T): T = lifecycle.runMaintenance {
        ready()
        check(!operation.value.busy) { "HARNESS_OPERATION_BUSY" }
        val row = requireNotNull(catalog.getById(runtimeId)) { "HARNESS_RUNTIME_REQUIRED" }
        check(row.status !in setOf("INSTALLING", "UPDATING", "DELETING", "BROKEN")) { "HARNESS_RUNTIME_UNAVAILABLE" }
        check(files.journal.pending().none { it.runtimeId == runtimeId }) { "HARNESS_OPERATION_INTERRUPTED" }
        action()
    }

    suspend fun requireSelected(runtimeId: String) {
        ready()
        check(selected.value == runtimeId) { "HARNESS_RUNTIME_NOT_SELECTED" }
        val row = requireNotNull(catalog.getById(runtimeId)) { "HARNESS_RUNTIME_REQUIRED" }
        check(row.status !in setOf("INSTALLING", "UPDATING", "DELETING", "BROKEN")) { "HARNESS_RUNTIME_UNAVAILABLE" }
        check(files.journal.pending().none { it.runtimeId == runtimeId }) { "HARNESS_OPERATION_INTERRUPTED" }
    }

    suspend fun create(name: String): String = execute("CREATE", null) {
        val row = newInstallation(name)
        mutableOperation.value = mutableOperation.value.copy(runtimeId = row.id)
        install(row, files.journal.new(row.id, "CREATE"))
        persistSelection(row.id)
        row.id
    }

    internal suspend fun newInstallation(name: String): AgentProotEnvironmentEntity {
        val row = AgentProotEnvironmentEntity(
            id = UUID.randomUUID().toString(), displayName = normalizeName(name),
            purpose = HarnessInstallationPurpose.HARNESS,
            dataLayout = HarnessInstallationDataLayout.SCOPED,
            status = AgentProotEnvironmentStatus.INSTALLING,
        )
        catalog.insert(row)
        return row
    }

    suspend fun rename(id: String, name: String) {
        ready()
        operations.withLock {
            val row = requireNotNull(catalog.getById(id)) { "HARNESS_RUNTIME_REQUIRED" }
            catalog.update(row.copy(displayName = normalizeName(name), updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun select(id: String) = execute("SWITCH", id) {
        val row = requireNotNull(catalog.getById(id)) { "HARNESS_RUNTIME_REQUIRED" }
        check(row.status !in setOf("INSTALLING", "UPDATING", "DELETING")) { "HARNESS_RUNTIME_UNAVAILABLE" }
        persistSelection(id)
    }

    suspend fun recreate(id: String) = execute("RECREATE", id) {
        val row = requireNotNull(catalog.getById(id)) { "HARNESS_RUNTIME_REQUIRED" }
        check(files.journal.pending().none { it.runtimeId == id }) { "HARNESS_OPERATION_INTERRUPTED" }
        catalog.update(row.copy(status = AgentProotEnvironmentStatus.UPDATING, updatedAt = System.currentTimeMillis()))
        install(row, files.journal.new(id, "RECREATE"))
        HarnessAppRuntime.discard(id)
    }

    suspend fun delete(id: String) = execute("DELETE", id) {
        val row = requireNotNull(catalog.getById(id)) { "HARNESS_RUNTIME_REQUIRED" }
        check(files.journal.pending().none { it.runtimeId == id }) { "HARNESS_OPERATION_INTERRUPTED" }
        catalog.ensureRuntimeDeletionAllowed(id)
        catalog.update(row.copy(status = AgentProotEnvironmentStatus.DELETING, updatedAt = System.currentTimeMillis()))
        finishDeletion(files.journal.new(id, "DELETE"))
    }

    suspend fun retry() = execute("RECOVER", operation.value.runtimeId) {
        files.journal.pending().forEach { receipt ->
            when (receipt.kind) {
                "DELETE" -> finishDeletion(receipt)
                "CREATE", "RECREATE" -> {
                    val row = requireNotNull(catalog.getById(receipt.runtimeId)) { "HARNESS_RUNTIME_REQUIRED" }
                    mutableOperation.value = mutableOperation.value.copy(runtimeId = row.id)
                    install(row, receipt)
                    if (receipt.kind == "CREATE") persistSelection(row.id)
                }
                "IMPORT" -> HarnessInstallationTransfer.get(context).recover(receipt)
            }
        }
    }

    fun cancel() { if (operation.value.cancellable) currentJob?.cancel() }
    fun clearResult() { if (!operation.value.busy) mutableOperation.value = HarnessInstallationOperation() }

    /** Used by the transfer coordinator, including callers outside Compose. */
    internal suspend fun <T> execute(kind: String, runtimeId: String?, action: suspend () -> T): T {
        ready()
        check(operations.tryLock()) { "HARNESS_OPERATION_BUSY" }
        val work = scope.async {
            try {
                AgentForegroundService.retainHarnessMaintenance(context, context.getString(R.string.harness_installation_working))
                mutableOperation.value = HarnessInstallationOperation(kind, runtimeId, "STOPPING")
                try {
                    lifecycle.runExclusiveDestructive(
                        terminate = { quiesce() },
                        action = {
                            mutableOperation.value = mutableOperation.value.copy(phase = "PREPARING", cancellable = true)
                            action()
                        },
                    ).also { mutableOperation.value = mutableOperation.value.copy(phase = "COMPLETE", cancellable = false) }
                } catch (cancelled: CancellationException) {
                    mutableOperation.value = mutableOperation.value.copy(phase = "CANCELLED", cancellable = false)
                    throw cancelled
                } catch (failure: Exception) {
                    // Only reviewed error categories and fixed phases enter durable receipts.
                    // A full disk must not replace the original failure with a journal error.
                    runCatching { files.journal.recordFailure(mutableOperation.value.runtimeId, failure, mutableOperation.value.phase) }
                    val code = failure.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,96}")) }
                        ?: harnessLifecycleErrorCode(failure).takeUnless { it == "HARNESS_START_FAILED" }
                        ?: "HARNESS_INSTALLATION_FAILED"
                    mutableOperation.value = mutableOperation.value.copy(phase = "FAILED", errorCode = code, cancellable = false)
                    throw failure
                } finally {
                    AgentForegroundService.releaseRuntime(context)
                    mutableRevision.value += 1
                }
            } finally { operations.unlock() }
        }
        currentJob = work
        try { return work.await() } finally { if (currentJob === work && work.isCompleted) currentJob = null }
    }

    internal fun progress(phase: String, completed: Long = 0, total: Long = 0, cancellable: Boolean = true) {
        mutableOperation.value = mutableOperation.value.copy(phase = phase, completedBytes = completed, totalBytes = total, cancellable = cancellable)
    }

    private suspend fun quiesce() {
        // Recover the real durable owner, even when its installation is no longer selected.
        val owner = database.harnessDao().runtime()
        if (owner != null && owner.state in setOf("STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING")) {
            val runtime = HarnessAppRuntime.forRuntime(context, owner.environmentId)
            runtime.stop()
            check(database.harnessDao().runtime()?.state == "STOPPED") { "HARNESS_STOP_REQUIRED" }
        }
        HarnessLanAccessManager.stopAllForMaintenance()
        HarnessRecoveryTerminalSessionManager.closeAllForMaintenance()
        HarnessRecoveryFileServer.stopAllForMaintenance()
        HarnessAppRuntime.pauseMaintenanceWorkers()
        catalog.getAll().forEach { row ->
            check(database.agentProotEnvironmentDao().countActiveRuns(row.id) == 0) { "HARNESS_STOP_REQUIRED" }
        }
    }

    private suspend fun install(row: AgentProotEnvironmentEntity, receipt: HarnessInstallationReceipt) {
        val prepared = files.prepareRootfs(receipt) { progress(it) }
        // Commit must finish despite UI cancellation after the atomic replacement begins.
        withContext(NonCancellable) {
            progress("ACTIVATING", cancellable = false)
            val activated = files.activateRootfs(prepared)
            markReady(row.id, select = false)
            files.finish(activated)
            refreshSize(row.id)
        }
    }

    // Deletion recovery requires checking each synchronous preference commit result.
    @SuppressLint("UseKtx")
    private suspend fun finishDeletion(receipt: HarnessInstallationReceipt) = withContext(NonCancellable) {
        progress("DELETING", cancellable = false)
        catalog.ensureRuntimeDeletionAllowed(receipt.runtimeId)
        val staged = files.quarantineDeletion(receipt)
        catalog.deleteRuntimeRecords(receipt.runtimeId)
        check(catalog.getById(receipt.runtimeId) == null) { "HARNESS_RUNTIME_REFERENCED" }
        val scoped = HarnessRuntimeScope.context(context, receipt.runtimeId)
        HarnessRuntimeScope.ownedPreferences.forEach { name ->
            check(HarnessRuntimeScope.preferences(scoped, name).edit().clear().commit())
        }
        if (receipt.runtimeId == HarnessRuntimeScope.LEGACY_RUNTIME_ID) {
            val settings = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
            val editor = settings.edit()
            settings.all.keys.filter { it.startsWith("agent_") }.forEach(editor::remove)
            check(editor.commit())
        }
        HarnessAppRuntime.discard(receipt.runtimeId)
        if (selected.value == receipt.runtimeId) persistSelection(null)
        files.finish(staged)
    }

    internal suspend fun markReady(id: String, select: Boolean = true) {
        check(catalog.markReady(id, System.currentTimeMillis()) == 1) { "HARNESS_RUNTIME_REQUIRED" }
        if (select) persistSelection(id)
    }

    /** Storage presentation must never block or roll back authenticated startup. */
    internal fun refreshSize(id: String) = scope.launch {
        try {
            catalog.updateSize(id, safeRuntimeSize(context, id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the last estimate if ownership checks, enumeration or the optional write fail.
        }
    }

    // Publish selection only after its synchronous commit succeeds; KTX edit returns Unit.
    @SuppressLint("UseKtx")
    internal fun persistSelection(id: String?) {
        check(preferences.edit().putString("selected", id).commit()) { "HARNESS_SELECTION_FAILED" }
        selected.value = id
    }

    companion object {
        internal val lifecycle = HarnessLifecycleGate()
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: HarnessInstallationManager? = null
        fun get(context: Context): HarnessInstallationManager = instance ?: synchronized(this) {
            instance ?: HarnessInstallationManager(HarnessRuntimeScope.host(context)).also { instance = it }
        }
        fun capture(context: Context): Context = if (HarnessRuntimeScope.isCaptured(context)) context
            else HarnessRuntimeScope.context(context, get(context).selectedId.value ?: error("HARNESS_RUNTIME_REQUIRED"))
        fun normalizeName(value: String): String = value.trim().also {
            require(it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl)) { "HARNESS_RUNTIME_NAME_INVALID" }
        }
        fun safeRuntimeSize(context: Context, id: String): Long {
            val scoped = HarnessRuntimeScope.context(context, id)
            val roots = HarnessInstallationFiles.ownedRoots(scoped) + AgentProotEnvironmentPaths.environmentRoot(context, id)
            return estimateHarnessStorage(roots)
        }
    }
}
