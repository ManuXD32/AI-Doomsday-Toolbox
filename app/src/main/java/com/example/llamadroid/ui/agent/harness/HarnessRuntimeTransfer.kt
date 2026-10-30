package com.example.llamadroid.ui.agent.harness

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

enum class HarnessRuntimeTransferDirection {
    EXPORT,
    IMPORT,
}

enum class HarnessRuntimeTransferFormat {
    PORTABLE,
    FULL_SNAPSHOT,
}

enum class HarnessRuntimeTransferScope {
    SELECTIONS_AND_CONFIG,
    CONFIG_ONLY,
    WHOLE_ROOTFS,
}

enum class HarnessRuntimeImportDestination {
    NEW_RUNTIME,
    COPY_STOPPED_RUNTIME,
}

enum class HarnessRuntimeTransferPhase {
    IDLE,
    PICKING_FILE,
    RUNNING,
    COMPLETED,
    FAILED,
}

/** Optional project/session context supplied by a workspace shortcut. */
data class HarnessRuntimeTransferSelectionUi(
    val projectIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
)

data class HarnessRuntimeTransferChoiceUi(
    val id: String,
    val label: String,
    val detail: String? = null,
    val selectedByDefault: Boolean = true,
)

data class HarnessRuntimeImportPreviewUi(
    val archiveName: String,
    val runtimeName: String? = null,
    val format: HarnessRuntimeTransferFormat,
    val scope: HarnessRuntimeTransferScope,
    val projectCount: Int = 0,
    val sessionCount: Int = 0,
    val includesConfiguration: Boolean = false,
    val includesCredentials: Boolean = false,
    val requiresPassword: Boolean = false,
    val missingMappings: List<String> = emptyList(),
    val mappingChoices: List<HarnessRuntimeImportMappingChoiceUi> = emptyList(),
    val canImport: Boolean = true,
    val errorCode: String? = null,
)

data class HarnessRuntimeImportMappingOptionUi(
    val id: String,
    val label: String,
    val detail: String? = null,
)

data class HarnessRuntimeImportMappingChoiceUi(
    val reference: String,
    val label: String,
    val options: List<HarnessRuntimeImportMappingOptionUi> = emptyList(),
)

data class HarnessRuntimeTransferUiState(
    val direction: HarnessRuntimeTransferDirection,
    val phase: HarnessRuntimeTransferPhase = HarnessRuntimeTransferPhase.IDLE,
    val progressPercent: Int? = null,
    val phaseLabel: String? = null,
    val errorCode: String? = null,
    val importPreview: HarnessRuntimeImportPreviewUi? = null,
    val selection: HarnessRuntimeTransferSelectionUi? = null,
    val canCancel: Boolean = true,
    val canRetry: Boolean = false,
    /** Runtime targeted by a newly requested export, when the request came from another tab. */
    val runtimeId: String? = null,
    /** Monotonic token for opening the transfer dialog; unchanged state must not reopen it. */
    val openRequestToken: Long = 0L,
)

data class HarnessRuntimeTransferTargetUi(
    val id: String,
    val name: String,
    val detail: String? = null,
)

data class HarnessRuntimeExportRequest(
    val runtimeId: String,
    val format: HarnessRuntimeTransferFormat,
    val scope: HarnessRuntimeTransferScope,
    val selectedProjectIds: Set<String> = emptySet(),
    val selectedSessionIds: Set<String> = emptySet(),
    val includeConfiguration: Boolean = true,
    val includeCredentials: Boolean,
    val protectArchive: Boolean = false,
    val password: String?,
    val destinationUri: Uri,
)

data class HarnessRuntimeImportRequest(
    val format: HarnessRuntimeTransferFormat,
    val scope: HarnessRuntimeTransferScope,
    val destination: HarnessRuntimeImportDestination,
    val destinationRuntimeId: String?,
    val runtimeName: String? = null,
    val mappingOverrides: Map<String, String> = emptyMap(),
    val password: String?,
    val sourceUri: Uri,
)

/** The UI owns choice presentation; the runtime/controller owns ZIP I/O and validation. */
data class HarnessRuntimeTransferCallbacks(
    val onExport: (HarnessRuntimeExportRequest) -> Unit = {},
    val onInspectImport: (Uri, String?, String?) -> Unit = { _, _, _ -> },
    val onImport: (HarnessRuntimeImportRequest) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
internal fun HarnessRuntimeTransferDialog(
    direction: HarnessRuntimeTransferDirection,
    state: HarnessRuntimeTransferUiState = HarnessRuntimeTransferUiState(direction = direction),
    runtimeId: String? = null,
    runtimeName: String? = null,
    projects: List<HarnessRuntimeTransferChoiceUi> = emptyList(),
    sessions: List<HarnessRuntimeTransferChoiceUi> = emptyList(),
    stoppedRuntimeTargets: List<HarnessRuntimeTransferTargetUi> = emptyList(),
    onDismiss: () -> Unit,
    callbacks: HarnessRuntimeTransferCallbacks,
) {
    require(direction == state.direction) { "Transfer dialog direction must match transfer state" }

    var format by rememberSaveable(direction, runtimeId) {
        mutableStateOf(HarnessRuntimeTransferFormat.PORTABLE)
    }
    var destination by rememberSaveable(direction, runtimeId) {
        mutableStateOf(HarnessRuntimeImportDestination.NEW_RUNTIME)
    }
    var destinationRuntimeId by rememberSaveable(direction, runtimeId) {
        mutableStateOf<String?>(null)
    }
    var includeConfiguration by rememberSaveable(direction, runtimeId) { mutableStateOf(true) }
    var includeCredentials by rememberSaveable(direction, runtimeId) { mutableStateOf(false) }
    var protectArchive by rememberSaveable(direction, runtimeId) { mutableStateOf(false) }
    var selectedProjectIds by remember(runtimeId, projects, state.selection) {
        mutableStateOf(
            state.selection?.projectIds
                ?: projects.filter { it.selectedByDefault }.map { it.id }.toSet()
        )
    }
    var selectedSessionIds by remember(runtimeId, sessions, state.selection) {
        mutableStateOf(
            state.selection?.sessionIds
                ?: sessions.filter { it.selectedByDefault }.map { it.id }.toSet()
        )
    }
    // Passwords intentionally do not use rememberSaveable; saved-instance state may persist secrets.
    var password by remember(direction, runtimeId) { mutableStateOf("") }
    var passwordConfirmation by remember(direction, runtimeId) { mutableStateOf("") }
    var sourceUri by remember(direction) { mutableStateOf<Uri?>(null) }
    var inspectionPending by remember(direction) { mutableStateOf(false) }
    var importRuntimeName by remember(direction) { mutableStateOf("") }
    var mappingOverrides by remember(direction) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val preview = state.importPreview
    // A new inspected source must restore the manifest defaults and clear mappings from the
    // previous archive. Keep edits to the current manifest name while its preview is refreshed.
    LaunchedEffect(sourceUri, preview?.archiveName, preview?.format, preview?.scope) {
        if (direction == HarnessRuntimeTransferDirection.IMPORT && preview != null) {
            importRuntimeName = preview.runtimeName.orEmpty()
            mappingOverrides = emptyMap()
        }
    }
    LaunchedEffect(state.phase, preview?.archiveName, state.errorCode) {
        if (state.phase != HarnessRuntimeTransferPhase.RUNNING &&
            state.phase != HarnessRuntimeTransferPhase.PICKING_FILE
        ) {
            inspectionPending = false
        }
    }
    LaunchedEffect(preview?.format) {
        if (preview?.format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT) {
            destination = HarnessRuntimeImportDestination.NEW_RUNTIME
            destinationRuntimeId = null
        }
    }
    var waitingForPicker by rememberSaveable(direction, runtimeId) { mutableStateOf(false) }

    fun requestImportInspection(uri: Uri, targetRuntimeId: String? = null) {
        inspectionPending = true
        callbacks.onInspectImport(uri, password.takeIf { it.isNotBlank() }, targetRuntimeId)
    }

    val busy = state.phase == HarnessRuntimeTransferPhase.RUNNING ||
        state.phase == HarnessRuntimeTransferPhase.PICKING_FILE ||
        waitingForPicker || inspectionPending
    val passwordValid = !protectArchive ||
        (password.isNotBlank() && password == passwordConfirmation)
    val credentialsValid = !includeCredentials || protectArchive
    val portableContentValid = includeConfiguration ||
        selectedProjectIds.isNotEmpty() || selectedSessionIds.isNotEmpty()
    val exportValid = passwordValid && credentialsValid &&
        (format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT || portableContentValid)
    val importNameValid = destination != HarnessRuntimeImportDestination.NEW_RUNTIME ||
        importRuntimeName.trim().isNotEmpty()
    val destinationValid = direction == HarnessRuntimeTransferDirection.EXPORT ||
        preview?.format != HarnessRuntimeTransferFormat.PORTABLE ||
        destination == HarnessRuntimeImportDestination.NEW_RUNTIME ||
        destinationRuntimeId != null
    val mappingChoices = preview?.mappingChoices.orEmpty()
    val mappingReviewRequired = preview?.missingMappings.orEmpty().isNotEmpty() ||
        mappingChoices.isNotEmpty()
    // An existing-runtime copy must stop when the controller reports an unavailable reference
    // without offering a concrete choice. This prevents an implicit provider/model substitution.
    val mappingsValid = destination != HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME ||
        (!mappingReviewRequired && preview?.canImport == true) ||
        (mappingChoices.isNotEmpty() && mappingChoices.all { mappingOverrides[it.reference] != null })
    val importPasswordValid = preview?.requiresPassword != true || password.isNotBlank()
    val submitEnabled = !busy && state.phase != HarnessRuntimeTransferPhase.COMPLETED &&
        if (direction == HarnessRuntimeTransferDirection.EXPORT) {
            exportValid
        } else {
            preview?.canImport == true && importNameValid && destinationValid && mappingsValid &&
                importPasswordValid
        }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        waitingForPicker = false
        if (uri != null && runtimeId != null) {
            val scope = when {
                format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT ->
                    HarnessRuntimeTransferScope.WHOLE_ROOTFS
                selectedProjectIds.isEmpty() && selectedSessionIds.isEmpty() && includeConfiguration ->
                    HarnessRuntimeTransferScope.CONFIG_ONLY
                else -> HarnessRuntimeTransferScope.SELECTIONS_AND_CONFIG
            }
            callbacks.onExport(
                HarnessRuntimeExportRequest(
                    runtimeId = runtimeId,
                    format = format,
                    scope = scope,
                    selectedProjectIds = selectedProjectIds,
                    selectedSessionIds = selectedSessionIds,
                    includeConfiguration = format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT ||
                        includeConfiguration,
                    includeCredentials = includeCredentials,
                    protectArchive = protectArchive,
                    password = password.takeIf { protectArchive },
                    destinationUri = uri,
                )
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        waitingForPicker = false
        if (uri != null) {
            sourceUri = uri
            requestImportInspection(
                uri,
                destinationRuntimeId.takeIf {
                    destination == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME
                },
            )
        }
    }

    fun inspectSelectedArchive() {
        val uri = sourceUri ?: return
        requestImportInspection(
            uri,
            destinationRuntimeId.takeIf {
                destination == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy && state.phase != HarnessRuntimeTransferPhase.RUNNING) onDismiss() },
        title = {
            Text(
                when {
                    state.phase == HarnessRuntimeTransferPhase.COMPLETED ->
                        stringResource(R.string.harness_installations_transfer_complete_title)
                    direction == HarnessRuntimeTransferDirection.EXPORT ->
                        stringResource(R.string.harness_installations_export_title)
                    else -> stringResource(R.string.harness_installations_import_title)
                }
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state.phase) {
                        HarnessRuntimeTransferPhase.RUNNING,
                        HarnessRuntimeTransferPhase.PICKING_FILE -> {
                            Text(
                                text = state.phaseLabel
                                    ?: stringResource(R.string.harness_installations_transfer_in_progress),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            state.progressPercent?.let { progress ->
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0, 100) / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(
                                        R.string.harness_installations_transfer_progress,
                                        progress.coerceIn(0, 100),
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.errorCode != null) {
                                Text(
                                    text = stringResource(
                                        R.string.harness_installations_transfer_error,
                                        state.errorCode,
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HarnessRuntimeTransferPhase.COMPLETED -> {
                            Text(stringResource(R.string.harness_installations_transfer_complete_message))
                        }

                        else -> {
                            if (direction == HarnessRuntimeTransferDirection.IMPORT) {
                                ImportTransferBody(
                                    sourceUri = sourceUri,
                                    preview = preview,
                                    password = password,
                                    onPasswordChange = { password = it },
                                    runtimeName = importRuntimeName,
                                    onRuntimeNameChange = { importRuntimeName = it },
                                    destination = destination,
                                    onDestinationChange = {
                                        destination = it
                                        if (it == HarnessRuntimeImportDestination.NEW_RUNTIME) {
                                            destinationRuntimeId = null
                                            mappingOverrides = emptyMap()
                                            sourceUri?.let { uri -> requestImportInspection(uri) }
                                        } else if (
                                            stoppedRuntimeTargets.isNotEmpty() &&
                                            (destinationRuntimeId == null ||
                                                stoppedRuntimeTargets.none { it.id == destinationRuntimeId })
                                        ) {
                                            destinationRuntimeId = stoppedRuntimeTargets.first().id
                                        }
                                        if (it == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME) {
                                            mappingOverrides = emptyMap()
                                            sourceUri?.let { uri -> requestImportInspection(uri, destinationRuntimeId) }
                                        }
                                    },
                                    destinationRuntimeId = destinationRuntimeId,
                                    onDestinationRuntimeIdChange = {
                                        destinationRuntimeId = it
                                        mappingOverrides = emptyMap()
                                        sourceUri?.let { uri -> requestImportInspection(uri, it) }
                                    },
                                    stoppedRuntimeTargets = stoppedRuntimeTargets,
                                    mappingChoices = preview?.mappingChoices.orEmpty(),
                                    mappingOverrides = mappingOverrides,
                                    onMappingSelected = { reference, optionId ->
                                        mappingOverrides = mappingOverrides + (reference to optionId)
                                    },
                                    errorCode = state.errorCode ?: preview?.errorCode,
                                )
                            } else {
                                ExportTransferBody(
                                    runtimeName = runtimeName,
                                    format = format,
                                    onFormatChange = {
                                        format = it
                                        if (it == HarnessRuntimeTransferFormat.FULL_SNAPSHOT) {
                                            includeConfiguration = true
                                        }
                                    },
                                    projects = projects,
                                    sessions = sessions,
                                    selectedProjectIds = selectedProjectIds,
                                    onProjectToggle = { id ->
                                        selectedProjectIds = if (id in selectedProjectIds) {
                                            selectedProjectIds - id
                                        } else {
                                            selectedProjectIds + id
                                        }
                                    },
                                    selectedSessionIds = selectedSessionIds,
                                    onSessionToggle = { id ->
                                        selectedSessionIds = if (id in selectedSessionIds) {
                                            selectedSessionIds - id
                                        } else {
                                            selectedSessionIds + id
                                        }
                                    },
                                    includeConfiguration = includeConfiguration,
                                    onIncludeConfigurationChange = { includeConfiguration = it },
                                    includeCredentials = includeCredentials,
                                    onIncludeCredentialsChange = {
                                        includeCredentials = it
                                        if (it) protectArchive = true
                                    },
                                    protectArchive = protectArchive,
                                    onProtectArchiveChange = { protectArchive = it },
                                    password = password,
                                    onPasswordChange = { password = it },
                                    passwordConfirmation = passwordConfirmation,
                                    onPasswordConfirmationChange = { passwordConfirmation = it },
                                    passwordValid = passwordValid,
                                    credentialsValid = credentialsValid,
                                )
                                if (!portableContentValid && format == HarnessRuntimeTransferFormat.PORTABLE) {
                                    Text(
                                        stringResource(R.string.harness_installations_transfer_content_required),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (state.phase == HarnessRuntimeTransferPhase.FAILED) {
                                Text(
                                    stringResource(
                                        R.string.harness_installations_transfer_error,
                                        state.errorCode ?: "TRANSFER_FAILED",
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.phase == HarnessRuntimeTransferPhase.COMPLETED -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.harness_installations_done))
                    }
                }
                state.phase == HarnessRuntimeTransferPhase.FAILED && state.canRetry -> {
                    TextButton(onClick = callbacks.onRetry) {
                        Text(stringResource(R.string.harness_installations_retry))
                    }
                }
                direction == HarnessRuntimeTransferDirection.IMPORT && preview == null -> {
                    Button(
                        onClick = {
                            if (sourceUri == null) {
                                waitingForPicker = true
                                importPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                            } else {
                                inspectSelectedArchive()
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            if (sourceUri == null) {
                                stringResource(R.string.harness_installations_choose_zip)
                            } else {
                                stringResource(R.string.harness_installations_inspect_zip)
                            }
                        )
                    }
                }
                direction == HarnessRuntimeTransferDirection.IMPORT -> {
                    Button(
                        onClick = {
                            val imported = preview ?: return@Button
                            val uri = sourceUri ?: return@Button
                            callbacks.onImport(
                                HarnessRuntimeImportRequest(
                                    format = imported.format,
                                    scope = imported.scope,
                                    destination = destination,
                                    destinationRuntimeId = destinationRuntimeId,
                                    runtimeName = importRuntimeName.trim()
                                        .takeIf { destination == HarnessRuntimeImportDestination.NEW_RUNTIME },
                                    mappingOverrides = mappingOverrides,
                                    password = password.takeIf { it.isNotBlank() },
                                    sourceUri = uri,
                                )
                            )
                        },
                        enabled = submitEnabled,
                    ) {
                        Text(stringResource(R.string.harness_installations_import))
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            waitingForPicker = true
                            if (direction == HarnessRuntimeTransferDirection.EXPORT) {
                                val baseName = runtimeName
                                    ?.trim()
                                    ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                                    ?.trim('_')
                                    ?.ifBlank { null }
                                    ?: "harness-runtime"
                                exportPicker.launch("$baseName.zip")
                            }
                        },
                        enabled = submitEnabled,
                    ) {
                        Text(stringResource(R.string.harness_installations_export))
                    }
                }
            }
        },
        dismissButton = {
            if (state.phase == HarnessRuntimeTransferPhase.RUNNING && state.canCancel) {
                TextButton(onClick = callbacks.onCancel) {
                    Text(stringResource(R.string.harness_installations_cancel_transfer))
                }
            } else if (state.phase != HarnessRuntimeTransferPhase.COMPLETED && !busy) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.harness_installations_cancel))
                }
            }
        },
    )
}

@Composable
private fun ImportTransferBody(
    sourceUri: Uri?,
    preview: HarnessRuntimeImportPreviewUi?,
    password: String,
    onPasswordChange: (String) -> Unit,
    runtimeName: String,
    onRuntimeNameChange: (String) -> Unit,
    destination: HarnessRuntimeImportDestination,
    onDestinationChange: (HarnessRuntimeImportDestination) -> Unit,
    destinationRuntimeId: String?,
    onDestinationRuntimeIdChange: (String) -> Unit,
    stoppedRuntimeTargets: List<HarnessRuntimeTransferTargetUi>,
    mappingChoices: List<HarnessRuntimeImportMappingChoiceUi>,
    mappingOverrides: Map<String, String>,
    onMappingSelected: (String, String) -> Unit,
    errorCode: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.harness_installations_import_description))
        sourceUri?.let { uri ->
            Text(
                stringResource(
                    R.string.harness_installations_import_selected_file,
                    uri.lastPathSegment ?: stringResource(R.string.harness_installations_import_zip_fallback),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_installations_import_password_label)) },
            supportingText = {
                Text(
                    if (preview?.requiresPassword == true && password.isBlank()) {
                        stringResource(R.string.harness_installations_import_password_required)
                    } else {
                        stringResource(R.string.harness_installations_import_password_description)
                    }
                )
            },
            singleLine = true,
            isError = preview?.requiresPassword == true && password.isBlank(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (preview == null) {
            Text(stringResource(R.string.harness_installations_import_inspect_description))
            errorCode?.let { code ->
                Text(
                    stringResource(R.string.harness_installations_transfer_error, code),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }
        Text(
            stringResource(R.string.harness_installations_import_preview_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(
                R.string.harness_installations_import_preview_summary,
                preview.archiveName,
                preview.runtimeName ?: stringResource(R.string.harness_installations_runtime_fallback_name),
                preview.projectCount,
                preview.sessionCount,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                if (preview.format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT) {
                    R.string.harness_installations_import_preview_full
                } else if (preview.scope == HarnessRuntimeTransferScope.CONFIG_ONLY) {
                    R.string.harness_installations_import_preview_config_only
                } else {
                    R.string.harness_installations_import_preview_portable
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (preview.includesConfiguration) {
            Text(stringResource(R.string.harness_installations_import_preview_configuration))
        }
        if (preview.includesCredentials) {
            Text(stringResource(R.string.harness_installations_import_preview_credentials))
        }
        if (preview.missingMappings.isNotEmpty()) {
            Text(
                stringResource(
                    if (destination == HarnessRuntimeImportDestination.NEW_RUNTIME) {
                        R.string.harness_installations_import_missing_mappings_new_runtime
                    } else {
                        R.string.harness_installations_import_missing_mappings
                    }
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
            preview.missingMappings.forEach { mapping ->
                Text(
                    "• $mapping",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (mappingChoices.isNotEmpty()) {
            Text(
                stringResource(R.string.harness_installations_import_mapping_title),
                style = MaterialTheme.typography.titleSmall,
            )
            mappingChoices.forEach { choice ->
                Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                choice.options.forEach { option ->
                    TransferFormatOption(
                        selected = mappingOverrides[choice.reference] == option.id,
                        title = option.label,
                        description = option.detail.orEmpty(),
                        onClick = { onMappingSelected(choice.reference, option.id) },
                    )
                }
            }
        }
        if (preview.format == HarnessRuntimeTransferFormat.PORTABLE) {
            Text(
                stringResource(R.string.harness_installations_import_destination_label),
                style = MaterialTheme.typography.titleSmall,
            )
            TransferFormatOption(
                selected = destination == HarnessRuntimeImportDestination.NEW_RUNTIME,
                title = stringResource(R.string.harness_installations_import_new_runtime),
                description = stringResource(R.string.harness_installations_import_new_runtime_description),
                onClick = { onDestinationChange(HarnessRuntimeImportDestination.NEW_RUNTIME) },
            )
            TransferFormatOption(
                selected = destination == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME,
                enabled = stoppedRuntimeTargets.isNotEmpty(),
                title = stringResource(R.string.harness_installations_import_copy_runtime),
                description = stringResource(
                    if (stoppedRuntimeTargets.isEmpty()) {
                        R.string.harness_installations_import_copy_runtime_unavailable
                    } else {
                        R.string.harness_installations_import_copy_runtime_description
                    }
                ),
                onClick = { onDestinationChange(HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME) },
            )
            if (destination == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME) {
                stoppedRuntimeTargets.forEach { target ->
                    TransferTargetOption(
                        target = target,
                        selected = target.id == destinationRuntimeId,
                        onClick = { onDestinationRuntimeIdChange(target.id) },
                    )
                }
            }
        }
        if (destination == HarnessRuntimeImportDestination.NEW_RUNTIME) {
            OutlinedTextField(
                value = runtimeName,
                onValueChange = onRuntimeNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_installations_import_runtime_name_label)) },
                supportingText = {
                    Text(stringResource(R.string.harness_installations_import_runtime_name_description))
                },
                singleLine = true,
                isError = runtimeName.trim().isEmpty(),
            )
        }
        if (!preview.canImport) {
            Text(
                stringResource(R.string.harness_installations_import_preview_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ExportTransferBody(
    runtimeName: String?,
    format: HarnessRuntimeTransferFormat,
    onFormatChange: (HarnessRuntimeTransferFormat) -> Unit,
    projects: List<HarnessRuntimeTransferChoiceUi>,
    sessions: List<HarnessRuntimeTransferChoiceUi>,
    selectedProjectIds: Set<String>,
    onProjectToggle: (String) -> Unit,
    selectedSessionIds: Set<String>,
    onSessionToggle: (String) -> Unit,
    includeConfiguration: Boolean,
    onIncludeConfigurationChange: (Boolean) -> Unit,
    includeCredentials: Boolean,
    onIncludeCredentialsChange: (Boolean) -> Unit,
    protectArchive: Boolean,
    onProtectArchiveChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordConfirmation: String,
    onPasswordConfirmationChange: (String) -> Unit,
    passwordValid: Boolean,
    credentialsValid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(
                R.string.harness_installations_export_description,
                runtimeName ?: stringResource(R.string.harness_installations_runtime_fallback_name),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.harness_installations_transfer_format_label), style = MaterialTheme.typography.titleSmall)
        TransferFormatOption(
            selected = format == HarnessRuntimeTransferFormat.PORTABLE,
            title = stringResource(R.string.harness_installations_transfer_portable),
            description = stringResource(R.string.harness_installations_transfer_portable_description),
            onClick = { onFormatChange(HarnessRuntimeTransferFormat.PORTABLE) },
        )
        TransferFormatOption(
            selected = format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT,
            title = stringResource(R.string.harness_installations_transfer_full_snapshot),
            description = stringResource(R.string.harness_installations_transfer_full_snapshot_description),
            onClick = { onFormatChange(HarnessRuntimeTransferFormat.FULL_SNAPSHOT) },
        )
        if (format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT) {
            Text(
                stringResource(R.string.harness_installations_transfer_full_snapshot_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (format == HarnessRuntimeTransferFormat.PORTABLE) {
            Text(stringResource(R.string.harness_installations_transfer_projects_label), style = MaterialTheme.typography.titleSmall)
            if (projects.isEmpty()) {
                Text(stringResource(R.string.harness_installations_transfer_no_projects), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                projects.forEach { project ->
                    TransferChoiceRow(project, project.id in selectedProjectIds) { onProjectToggle(project.id) }
                }
            }
            Text(stringResource(R.string.harness_installations_transfer_sessions_label), style = MaterialTheme.typography.titleSmall)
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.harness_installations_transfer_no_sessions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sessions.forEach { session ->
                    TransferChoiceRow(session, session.id in selectedSessionIds) { onSessionToggle(session.id) }
                }
            }
            TransferCheckRow(
                checked = includeConfiguration,
                onCheckedChange = onIncludeConfigurationChange,
                title = stringResource(R.string.harness_installations_transfer_include_configuration),
                description = stringResource(R.string.harness_installations_transfer_include_configuration_description),
            )
        }
        TransferCheckRow(
            checked = includeCredentials,
            onCheckedChange = onIncludeCredentialsChange,
            title = stringResource(R.string.harness_installations_transfer_credentials),
            description = stringResource(R.string.harness_installations_transfer_credentials_description),
        )
        TransferCheckRow(
            checked = protectArchive,
            onCheckedChange = onProtectArchiveChange,
            title = stringResource(R.string.harness_installations_transfer_protect_archive),
            description = stringResource(R.string.harness_installations_transfer_protect_archive_description),
        )
        if (includeCredentials && !protectArchive) {
            Text(stringResource(R.string.harness_installations_transfer_credentials_requires_password), color = MaterialTheme.colorScheme.error)
        }
        if (protectArchive) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_installations_transfer_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = password.isBlank() || !passwordValid,
            )
            OutlinedTextField(
                value = passwordConfirmation,
                onValueChange = onPasswordConfirmationChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_installations_transfer_password_confirmation_label)) },
                supportingText = {
                    if (!passwordValid) Text(stringResource(R.string.harness_installations_transfer_password_mismatch))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = !passwordValid,
            )
        }
        if (!credentialsValid) {
            Text(stringResource(R.string.harness_installations_transfer_credentials_requires_password), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TransferFormatOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransferTargetOption(
    target: HarnessRuntimeTransferTargetUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
            Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            target.detail?.takeIf { it.isNotBlank() }?.let { detail ->
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
}

@Composable
private fun TransferChoiceRow(
    choice: HarnessRuntimeTransferChoiceUi,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            choice.detail?.takeIf { it.isNotBlank() }?.let { detail ->
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
}

@Composable
private fun TransferCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
