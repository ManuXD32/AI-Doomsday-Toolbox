package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

data class HarnessRuntimeInstallationUi(
    val id: String,
    val name: String,
    val status: HarnessRuntimeStatus = HarnessRuntimeStatus.STOPPED,
    val detail: String? = null,
    val sizeLabel: String? = null,
    val projectCount: Int = 0,
    val sessionCount: Int = 0,
    val settingsCount: Int = 0,
    val canSelect: Boolean = true,
    val canRename: Boolean = true,
    val canRecreate: Boolean = true,
    val canDelete: Boolean = true,
    val canExport: Boolean = true,
    val statusLabel: String? = null,
)

enum class HarnessRuntimeInstallationOperationKind {
    CREATE,
    RENAME,
    RECREATE,
    DELETE,
    EXPORT,
    IMPORT,
    SWITCH,
    RECOVER,
}

data class HarnessRuntimeInstallationOperationUi(
    val kind: HarnessRuntimeInstallationOperationKind,
    val runtimeId: String? = null,
    val phaseLabel: String? = null,
    val progressPercent: Int? = null,
    val errorCode: String? = null,
    val canCancel: Boolean = true,
    val canRetry: Boolean = false,
    val busy: Boolean = true,
)

data class HarnessRuntimeInstallationsUiState(
    val rows: List<HarnessRuntimeInstallationUi> = emptyList(),
    val selectedRuntimeId: String? = null,
    val operation: HarnessRuntimeInstallationOperationUi? = null,
    val transfer: HarnessRuntimeTransferUiState? = null,
    val stoppedRuntimeTargets: List<HarnessRuntimeTransferTargetUi> = emptyList(),
    val exportProjectsByRuntime: Map<String, List<HarnessRuntimeTransferChoiceUi>> = emptyMap(),
    val exportSessionsByRuntime: Map<String, List<HarnessRuntimeTransferChoiceUi>> = emptyMap(),
    val isLoading: Boolean = false,
    val canCreate: Boolean = true,
    val canImport: Boolean = true,
    val errorCode: String? = null,
)

sealed interface HarnessRuntimeInstallationAction {
    data class Create(val name: String) : HarnessRuntimeInstallationAction
    data class Select(val runtimeId: String) : HarnessRuntimeInstallationAction
    data class Rename(val runtimeId: String, val name: String) : HarnessRuntimeInstallationAction
    data class Recreate(val runtimeId: String) : HarnessRuntimeInstallationAction
    data class Delete(val runtimeId: String) : HarnessRuntimeInstallationAction
    data class BeginExport(
        val runtimeId: String,
        val selection: HarnessRuntimeTransferSelectionUi? = null,
    ) : HarnessRuntimeInstallationAction
    data object BeginImport : HarnessRuntimeInstallationAction
    data class InspectImport(
        val sourceUri: android.net.Uri,
        val password: String?,
        val destinationRuntimeId: String? = null,
    ) : HarnessRuntimeInstallationAction
    data class ConsumeTransferRequest(val token: Long) : HarnessRuntimeInstallationAction
    data class Export(val request: HarnessRuntimeExportRequest) : HarnessRuntimeInstallationAction
    data class Import(val request: HarnessRuntimeImportRequest) : HarnessRuntimeInstallationAction
    data object CancelOperation : HarnessRuntimeInstallationAction
    data object RetryOperation : HarnessRuntimeInstallationAction
}

/** Canonical entry point for the runtime manager; persistence stays in the route/controller. */
@Composable
internal fun HarnessRuntimeInstallationsHost(
    state: HarnessRuntimeInstallationsUiState,
    onAction: (HarnessRuntimeInstallationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RuntimeInstallationsPanel(
        rows = state.rows,
        selectedId = state.selectedRuntimeId,
        operation = state.operation,
        transfer = state.transfer,
        stoppedRuntimeTargets = state.stoppedRuntimeTargets,
        exportProjectsByRuntime = state.exportProjectsByRuntime,
        exportSessionsByRuntime = state.exportSessionsByRuntime,
        isLoading = state.isLoading,
        canCreate = state.canCreate,
        canImport = state.canImport,
        errorCode = state.errorCode,
        onAction = onAction,
        modifier = modifier,
    )
}

/** List/detail manager surface used by the Runtime tab and by contextual shortcuts. */
@Composable
internal fun RuntimeInstallationsPanel(
    rows: List<HarnessRuntimeInstallationUi>,
    selectedId: String?,
    operation: HarnessRuntimeInstallationOperationUi?,
    onAction: (HarnessRuntimeInstallationAction) -> Unit,
    modifier: Modifier = Modifier,
    transfer: HarnessRuntimeTransferUiState? = null,
    stoppedRuntimeTargets: List<HarnessRuntimeTransferTargetUi> = emptyList(),
    exportProjectsByRuntime: Map<String, List<HarnessRuntimeTransferChoiceUi>> = emptyMap(),
    exportSessionsByRuntime: Map<String, List<HarnessRuntimeTransferChoiceUi>> = emptyMap(),
    isLoading: Boolean = false,
    canCreate: Boolean = true,
    canImport: Boolean = true,
    errorCode: String? = null,
) {
    var createDialog by rememberSaveable { mutableStateOf(false) }
    var renameRuntimeId by rememberSaveable { mutableStateOf<String?>(null) }
    var recreateRuntimeId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteRuntimeId by rememberSaveable { mutableStateOf<String?>(null) }
    var transferDirection by rememberSaveable { mutableStateOf<HarnessRuntimeTransferDirection?>(null) }
    var transferRuntimeId by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedTransferRequestToken by rememberSaveable { mutableStateOf(0L) }

    LaunchedEffect(transfer?.openRequestToken) {
        val request = transfer ?: return@LaunchedEffect
        val token = request.openRequestToken
        if (token <= 0L || token == consumedTransferRequestToken) return@LaunchedEffect
        consumedTransferRequestToken = token
        transferDirection = request.direction
        transferRuntimeId = request.runtimeId ?: selectedId
        onAction(HarnessRuntimeInstallationAction.ConsumeTransferRequest(token))
    }

    val selectedRename = rows.firstOrNull { it.id == renameRuntimeId }
    val selectedRecreate = rows.firstOrNull { it.id == recreateRuntimeId }
    val selectedDelete = rows.firstOrNull { it.id == deleteRuntimeId }
    val selectedExport = rows.firstOrNull { it.id == transferRuntimeId }
    val transferState = transferDirection?.let { direction ->
        transfer?.takeIf { it.direction == direction }
            ?: HarnessRuntimeTransferUiState(direction = direction)
    }

    AppSectionCard(
        modifier = modifier.testTag("harness_runtime_installations"),
        shape = AppChromeDefaults.InnerCardShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.harness_installations_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.harness_installations_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { createDialog = true },
                enabled = canCreate && operation?.busy != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.harness_installations_create))
            }
            OutlinedButton(
                onClick = {
                    onAction(HarnessRuntimeInstallationAction.BeginImport)
                },
                enabled = canImport && operation?.busy != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Text(
                    stringResource(R.string.harness_installations_import),
                )
            }
        }

        operation?.let { current ->
            InstallationOperationBanner(current, onAction)
        }
        errorCode?.let { code ->
            Text(
                stringResource(R.string.harness_installations_error, code),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (rows.isEmpty() && !isLoading) {
            Text(
                stringResource(R.string.harness_installations_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    RuntimeInstallationCard(
                        row = row,
                        selected = row.id == selectedId,
                        operation = operation,
                        onSelect = { onAction(HarnessRuntimeInstallationAction.Select(row.id)) },
                        onRename = { renameRuntimeId = row.id },
                        onRecreate = { recreateRuntimeId = row.id },
                        onDelete = { deleteRuntimeId = row.id },
                        onExport = {
                            onAction(HarnessRuntimeInstallationAction.BeginExport(row.id))
                        },
                    )
                }
            }
        }
    }

    if (createDialog) {
        RuntimeInstallationNameDialog(
            title = stringResource(R.string.harness_installations_create_title),
            description = stringResource(R.string.harness_installations_create_description),
            confirmLabel = stringResource(R.string.harness_installations_create),
            initialName = "",
            onDismiss = { createDialog = false },
            onConfirm = { name ->
                createDialog = false
                onAction(HarnessRuntimeInstallationAction.Create(name))
            },
        )
    }
    selectedRename?.let { row ->
        RuntimeInstallationNameDialog(
            title = stringResource(R.string.harness_installations_rename_title),
            description = stringResource(R.string.harness_installations_rename_description),
            confirmLabel = stringResource(R.string.harness_installations_save),
            initialName = row.name,
            onDismiss = { renameRuntimeId = null },
            onConfirm = { name ->
                renameRuntimeId = null
                onAction(HarnessRuntimeInstallationAction.Rename(row.id, name))
            },
        )
    }
    selectedRecreate?.let { row ->
        RuntimeInstallationRecreateDialog(
            row = row,
            onDismiss = { recreateRuntimeId = null },
            onConfirm = {
                recreateRuntimeId = null
                onAction(HarnessRuntimeInstallationAction.Recreate(row.id))
            },
        )
    }
    selectedDelete?.let { row ->
        RuntimeInstallationDeleteDialog(
            row = row,
            onDismiss = { deleteRuntimeId = null },
            onConfirm = {
                deleteRuntimeId = null
                onAction(HarnessRuntimeInstallationAction.Delete(row.id))
            },
        )
    }
    if (transferDirection != null && transferState != null) {
        HarnessRuntimeTransferDialog(
            direction = transferDirection!!,
            state = transferState,
            runtimeId = transferRuntimeId,
            runtimeName = selectedExport?.name,
            projects = selectedExport?.id?.let(exportProjectsByRuntime::get).orEmpty(),
            sessions = selectedExport?.id?.let(exportSessionsByRuntime::get).orEmpty(),
            stoppedRuntimeTargets = stoppedRuntimeTargets,
            onDismiss = {
                transferDirection = null
                transferRuntimeId = null
            },
            callbacks = HarnessRuntimeTransferCallbacks(
                onExport = { request ->
                    onAction(HarnessRuntimeInstallationAction.Export(request))
                },
                onInspectImport = { uri, password, destinationRuntimeId ->
                    onAction(
                        HarnessRuntimeInstallationAction.InspectImport(
                            sourceUri = uri,
                            password = password,
                            destinationRuntimeId = destinationRuntimeId,
                        )
                    )
                },
                onImport = { request ->
                    onAction(HarnessRuntimeInstallationAction.Import(request))
                },
                onCancel = { onAction(HarnessRuntimeInstallationAction.CancelOperation) },
                onRetry = { onAction(HarnessRuntimeInstallationAction.RetryOperation) },
            ),
        )
    }
}

@Composable
private fun RuntimeInstallationCard(
    row: HarnessRuntimeInstallationUi,
    selected: Boolean,
    operation: HarnessRuntimeInstallationOperationUi?,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRecreate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val statusColor = runtimeInstallationStatusColor(row.status)
    val rowBusy = operation?.busy == true && operation.runtimeId == row.id
    AppSectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("harness_runtime_installation_${row.id}"),
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.padding(top = 2.dp),
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.15f),
            ) {
                Icon(
                    imageVector = if (row.status == HarnessRuntimeStatus.RUNNING) {
                        Icons.Default.CheckCircle
                    } else if (row.status == HarnessRuntimeStatus.ERROR ||
                        row.status == HarnessRuntimeStatus.INTERRUPTED
                    ) {
                        Icons.Default.Warning
                    } else {
                        Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(
                            stringResource(R.string.harness_installations_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    row.statusLabel ?: runtimeInstallationStatusLabel(row.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            stringResource(
                R.string.harness_installations_counts,
                row.projectCount,
                row.sessionCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        row.sizeLabel?.takeIf { it.isNotBlank() }?.let { size ->
            Text(
                stringResource(R.string.harness_installations_size, size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rowBusy) {
            operation?.progressPercent?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            operation?.phaseLabel?.let { phase ->
                Text(
                    phase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.harness_installations_active))
                }
            } else {
                Button(
                    onClick = onSelect,
                    enabled = row.canSelect && !rowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.harness_installations_select))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRename,
                    enabled = row.canRename && !rowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(stringResource(R.string.harness_installations_rename))
                }
                OutlinedButton(
                    onClick = onExport,
                    enabled = row.canExport && !rowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Text(stringResource(R.string.harness_installations_export))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRecreate,
                    enabled = row.canRecreate && !rowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.harness_installations_recreate))
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = row.canDelete && !rowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(stringResource(R.string.harness_installations_delete))
                }
            }
        }
    }
}

@Composable
private fun InstallationOperationBanner(
    operation: HarnessRuntimeInstallationOperationUi,
    onAction: (HarnessRuntimeInstallationAction) -> Unit,
) {
    AppSectionCard(
        modifier = Modifier.padding(top = 12.dp),
        shape = AppChromeDefaults.InnerCardShape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Text(
            operation.phaseLabel ?: stringResource(R.string.harness_installations_operation_in_progress),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        operation.progressPercent?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        operation.errorCode?.let { code ->
            Text(
                stringResource(R.string.harness_installations_error, code),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (operation.canRetry) {
                TextButton(onClick = { onAction(HarnessRuntimeInstallationAction.RetryOperation) }) {
                    Text(stringResource(R.string.harness_installations_retry))
                }
            }
            if (operation.canCancel) {
                TextButton(onClick = { onAction(HarnessRuntimeInstallationAction.CancelOperation) }) {
                    Text(stringResource(R.string.harness_installations_cancel))
                }
            }
        }
    }
}

@Composable
private fun RuntimeInstallationNameDialog(
    title: String,
    description: String,
    confirmLabel: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 80
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(description)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_installations_name_label)) },
                    supportingText = {
                        if (!valid) Text(stringResource(R.string.harness_installations_name_required))
                    },
                    isError = !valid,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = valid) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.harness_installations_cancel))
            }
        },
    )
}

@Composable
private fun RuntimeInstallationRecreateDialog(
    row: HarnessRuntimeInstallationUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var acknowledged by rememberSaveable(row.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_installations_recreate_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(
                        R.string.harness_installations_recreate_description,
                        row.name,
                    )
                )
                Text(stringResource(R.string.harness_installations_recreate_retains_data))
                Row(verticalAlignment = Alignment.Top) {
                    androidx.compose.material3.Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Text(
                        stringResource(R.string.harness_installations_recreate_acknowledge),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = acknowledged) {
                Text(stringResource(R.string.harness_installations_recreate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.harness_installations_cancel))
            }
        },
    )
}

@Composable
private fun RuntimeInstallationDeleteDialog(
    row: HarnessRuntimeInstallationUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var typedName by rememberSaveable(row.id) { mutableStateOf("") }
    val confirmed = typedName.trim() == row.name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_installations_delete_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(
                        R.string.harness_installations_delete_description,
                        row.name,
                    )
                )
                Text(stringResource(R.string.harness_installations_delete_scope))
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_installations_delete_name_label)) },
                    supportingText = {
                        if (!confirmed) Text(stringResource(R.string.harness_installations_delete_name_hint, row.name))
                    },
                    isError = typedName.isNotEmpty() && !confirmed,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmed) {
                Text(stringResource(R.string.harness_installations_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.harness_installations_cancel))
            }
        },
    )
}

@Composable
private fun runtimeInstallationStatusLabel(status: HarnessRuntimeStatus): String = when (status) {
    HarnessRuntimeStatus.STOPPED -> stringResource(R.string.harness_status_stopped)
    HarnessRuntimeStatus.STARTING -> stringResource(R.string.harness_status_starting)
    HarnessRuntimeStatus.RUNNING -> stringResource(R.string.harness_status_running)
    HarnessRuntimeStatus.STOPPING -> stringResource(R.string.harness_status_stopping)
    HarnessRuntimeStatus.INTERRUPTED -> stringResource(R.string.harness_status_interrupted)
    HarnessRuntimeStatus.ERROR -> stringResource(R.string.harness_status_error)
}

@Composable
private fun runtimeInstallationStatusColor(status: HarnessRuntimeStatus): Color = when (status) {
    HarnessRuntimeStatus.RUNNING -> MaterialTheme.colorScheme.primary
    HarnessRuntimeStatus.STARTING,
    HarnessRuntimeStatus.STOPPING -> MaterialTheme.colorScheme.tertiary
    HarnessRuntimeStatus.ERROR,
    HarnessRuntimeStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
    HarnessRuntimeStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}
