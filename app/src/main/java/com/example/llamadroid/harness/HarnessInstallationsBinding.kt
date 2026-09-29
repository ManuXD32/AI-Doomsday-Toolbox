package com.example.llamadroid.harness

import android.text.format.Formatter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.ui.agent.harness.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class HarnessInstallationsBinding(
    val state: HarnessRuntimeInstallationsUiState,
    val onAction: (HarnessRuntimeInstallationAction) -> Unit,
)

private data class InstallationProjection(
    val projects: List<HarnessRuntimeTransferChoiceUi> = emptyList(),
    val sessions: List<HarnessRuntimeTransferChoiceUi> = emptyList(),
    val settingsCount: Int = 0,
)

@Composable
internal fun rememberHarnessInstallations(manager: HarnessInstallationManager): HarnessInstallationsBinding {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfer = remember(manager) { HarnessInstallationTransfer.get(context) }
    val rows by manager.installations.collectAsState()
    val selected by manager.selectedId.collectAsState()
    val operation by manager.operation.collectAsState()
    val revision by manager.contentRevision.collectAsState()
    val transferUi by transfer.ui.collectAsState()
    val owner by remember(manager) { manager.database.harnessDao().observeRuntime() }.collectAsState(null)
    var error by remember { mutableStateOf<String?>(null) }
    val projections by produceState(emptyMap<String, InstallationProjection>(), rows.map { it.id }, revision) {
        coroutineScope {
            rows.forEach { row -> launch {
                val dao = manager.database.harnessDao(row.id)
                combine(dao.observeWorkspaces(), dao.observeSessions(), dao.observeLegacyConversations()) { workspaces, sessions, legacy -> Triple(workspaces, sessions, legacy) }
                    .collect { (workspaces, sessions, legacy) ->
                        val conversations = dao.conversations().associateBy { it.id }
                        val scoped = HarnessRuntimeScope.context(context, row.id)
                        value = value + (row.id to InstallationProjection(
                            projects = workspaces.map { HarnessRuntimeTransferChoiceUi(it.id, it.title, it.guestPath) },
                            sessions = sessions.map { session -> HarnessRuntimeTransferChoiceUi(session.harnessSessionId,
                                conversations[session.conversationId]?.title ?: session.harnessSessionId,
                                workspaces.firstOrNull { it.id == session.workspaceId }?.title) } +
                                legacy.map { HarnessRuntimeTransferChoiceUi("legacy:${it.id}", it.title, it.projectFolder) },
                            settingsCount = scoped.getSharedPreferences("llamadroid_settings", android.content.Context.MODE_PRIVATE)
                                .all.keys.count { it.startsWith("agent_") },
                        ))
                    }
            } }
        }
    }
    val activeOwner = owner?.takeIf { it.state in setOf("STARTING", "RUNNING", "STOP_REQUESTED", "FORCE_STOPPING") }
    val busy = operation.busy || transferUi?.phase == HarnessRuntimeTransferPhase.RUNNING
    fun ready(row: AgentProotEnvironmentEntity) = row.status !in setOf("INSTALLING", "UPDATING", "DELETING")
    val phaseLabel = stringResource(when (operation.phase) {
        "STOPPING" -> R.string.harness_installation_phase_stopping
        "SCAN", "PREPARING", "READ", "STAGE", "PREPARED" -> R.string.harness_installation_phase_preparing
        "ROOTFS_COPY", "ROOTFS_CHECKSUM" -> R.string.harness_installation_phase_rootfs_check
        "ROOTFS_EXTRACT", "ROOTFS_ACTIVATE" -> R.string.harness_installation_phase_rootfs_extract
        "PAYLOAD_COPY", "PAYLOAD_CHECKSUM" -> R.string.harness_installation_phase_payload_check
        "PAYLOAD_EXTRACT", "PAYLOAD_ACTIVATE" -> R.string.harness_installation_phase_payload_extract
        "VALIDATE" -> R.string.harness_installation_phase_validating
        "ACTIVATING", "DELETING", "WRITE", "FILES_READY", "COMMITTED" -> R.string.harness_installation_phase_saving
        "COMPLETE" -> R.string.harness_installation_phase_complete
        "CANCELLED" -> R.string.harness_installation_phase_cancelled
        "FAILED" -> R.string.harness_installation_phase_failed
        else -> R.string.harness_installation_working
    })
    val state = HarnessRuntimeInstallationsUiState(
        rows = rows.map { row ->
            val projection = projections[row.id] ?: InstallationProjection()
            val preparing = operation.busy && operation.runtimeId == row.id
            HarnessRuntimeInstallationUi(row.id, row.displayName,
                status = if (preparing) HarnessRuntimeStatus.STARTING else if (activeOwner?.environmentId == row.id) when (activeOwner.state) {
                    "RUNNING" -> HarnessRuntimeStatus.RUNNING
                    "STARTING" -> HarnessRuntimeStatus.STARTING
                    else -> HarnessRuntimeStatus.STOPPING
                } else if (!ready(row) || row.status == "BROKEN") HarnessRuntimeStatus.INTERRUPTED else HarnessRuntimeStatus.STOPPED,
                detail = if (row.status == "NOT_INSTALLED") stringResource(R.string.harness_installation_not_installed) else null,
                sizeLabel = Formatter.formatFileSize(context, row.sizeBytes),
                projectCount = projection.projects.size, sessionCount = projection.sessions.size, settingsCount = projection.settingsCount,
                canSelect = !busy && ready(row) && row.status != "BROKEN", canRename = !busy,
                canRecreate = !busy && ready(row), canDelete = !busy && ready(row), canExport = !busy && ready(row),
                statusLabel = phaseLabel.takeIf { preparing },
            )
        },
        selectedRuntimeId = selected,
        operation = operation.toInstallationOperationUi(phaseLabel),
        transfer = transferUi,
        stoppedRuntimeTargets = rows.filter { ready(it) && it.status != "BROKEN" && activeOwner?.environmentId != it.id }.map {
            HarnessRuntimeTransferTargetUi(it.id, it.displayName)
        },
        exportProjectsByRuntime = projections.mapValues { it.value.projects },
        exportSessionsByRuntime = projections.mapValues { it.value.sessions },
        canCreate = !busy, canImport = !busy, errorCode = error,
    )
    val onAction: (HarnessRuntimeInstallationAction) -> Unit = { action ->
        error = null
        scope.launch {
            try {
                when (action) {
                    is HarnessRuntimeInstallationAction.Create -> manager.create(action.name)
                    is HarnessRuntimeInstallationAction.Select -> manager.select(action.runtimeId)
                    is HarnessRuntimeInstallationAction.Rename -> manager.rename(action.runtimeId, action.name)
                    is HarnessRuntimeInstallationAction.Recreate -> manager.recreate(action.runtimeId)
                    is HarnessRuntimeInstallationAction.Delete -> manager.delete(action.runtimeId)
                    is HarnessRuntimeInstallationAction.BeginExport -> transfer.begin(HarnessRuntimeTransferDirection.EXPORT, action.runtimeId, action.selection)
                    HarnessRuntimeInstallationAction.BeginImport -> transfer.begin(HarnessRuntimeTransferDirection.IMPORT)
                    is HarnessRuntimeInstallationAction.ConsumeTransferRequest -> transfer.consumeOpenRequest(action.token)
                    is HarnessRuntimeInstallationAction.InspectImport -> transfer.inspect(action.sourceUri, action.password, action.destinationRuntimeId)
                    is HarnessRuntimeInstallationAction.Export -> transfer.export(action.request)
                    is HarnessRuntimeInstallationAction.Import -> transfer.importArchive(action.request)
                    HarnessRuntimeInstallationAction.CancelOperation -> transfer.cancel()
                    HarnessRuntimeInstallationAction.RetryOperation -> manager.retry()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,96}")) } ?: "HARNESS_INSTALLATION_FAILED"
            }
        }
    }
    return HarnessInstallationsBinding(state, onAction)
}

/** A visible terminal result is not an active operation and must not lock its row. */
internal fun HarnessInstallationOperation.toInstallationOperationUi(phaseLabel: String): HarnessRuntimeInstallationOperationUi? =
    takeIf { phase != "IDLE" }?.let {
        HarnessRuntimeInstallationOperationUi(
            kind = runCatching { HarnessRuntimeInstallationOperationKind.valueOf(kind) }
                .getOrDefault(HarnessRuntimeInstallationOperationKind.RECOVER),
            runtimeId = runtimeId, phaseLabel = phaseLabel,
            progressPercent = if (totalBytes > 0) ((completedBytes.toDouble() / totalBytes * 100).coerceIn(0.0, 100.0)).toInt() else null,
            errorCode = errorCode, canCancel = busy && cancellable,
            canRetry = phase in setOf("FAILED", "CANCELLED") && kind in setOf("CREATE", "RECREATE", "DELETE", "IMPORT", "RECOVER"),
            busy = busy,
        )
    }
