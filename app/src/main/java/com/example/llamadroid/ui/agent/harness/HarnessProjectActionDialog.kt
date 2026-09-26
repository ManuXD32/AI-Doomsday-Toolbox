package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private const val HARNESS_PROJECT_TITLE_LIMIT = 120

private enum class HarnessProjectAction {
    RENAME,
    REMOVE,
}

private enum class HarnessProjectActionFailure(val resourceId: Int) {
    RUNTIME_STOP_REQUIRED(R.string.harness_project_action_runtime_stop_required),
    REMOTE_CLEANUP_PENDING(R.string.harness_project_action_remote_cleanup_pending),
    CLEANUP_PENDING(R.string.harness_project_action_cleanup_pending),
    REMOTE_IDENTITY_AMBIGUOUS(R.string.harness_project_action_remote_identity_ambiguous),
    GENERIC(R.string.harness_project_action_generic_failure),
}

private fun projectActionFailure(error: Throwable): HarnessProjectActionFailure {
    var current: Throwable? = error
    repeat(8) {
        val cause = current ?: return HarnessProjectActionFailure.GENERIC
        val message = cause.message?.uppercase(Locale.ROOT).orEmpty()
        when {
            "PROJECT_RUNTIME_STOP_REQUIRED" in message ->
                return HarnessProjectActionFailure.RUNTIME_STOP_REQUIRED
            "PROJECT_REMOTE_CLEANUP_PENDING" in message ->
                return HarnessProjectActionFailure.REMOTE_CLEANUP_PENDING
            "PROJECT_CLEANUP_PENDING" in message ->
                return HarnessProjectActionFailure.CLEANUP_PENDING
            "REMOTE_PROJECT_IDENTITY_AMBIGUOUS" in message ->
                return HarnessProjectActionFailure.REMOTE_IDENTITY_AMBIGUOUS
        }
        current = cause.cause
    }
    return HarnessProjectActionFailure.GENERIC
}

private fun normalizedProjectTitle(value: String): String = value.trim()

private fun projectTitleIsValid(value: String): Boolean {
    val title = normalizedProjectTitle(value)
    return title.isNotEmpty() && title.length <= HARNESS_PROJECT_TITLE_LIMIT && '\u0000' !in title
}

/** Rename/remove actions for a project are kept at the landing boundary; storage stays with the route. */
@Composable
internal fun HarnessProjectActionDialog(
    project: HarnessProjectUi,
    removing: Boolean,
    canRemove: Boolean,
    onRename: suspend (String) -> Unit,
    onRemove: suspend (Boolean) -> Unit,
    onDismiss: () -> Unit,
    canDeleteFiles: Boolean = true,
    pendingDeleteFiles: Boolean? = null,
) {
    val scope = rememberCoroutineScope()
    val bodyScrollState = rememberScrollState()
    var title by rememberSaveable(project.id, removing) { mutableStateOf(project.title) }
    var deleteFiles by rememberSaveable(project.id, pendingDeleteFiles) {
        mutableStateOf(pendingDeleteFiles ?: false)
    }
    var confirmationTitle by rememberSaveable(project.id, pendingDeleteFiles) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<HarnessProjectActionFailure?>(null) }
    var failedAction by remember { mutableStateOf<HarnessProjectAction?>(null) }

    LaunchedEffect(canDeleteFiles, pendingDeleteFiles) {
        deleteFiles = pendingDeleteFiles ?: canDeleteFiles && deleteFiles
    }

    val currentAction = if (removing) HarnessProjectAction.REMOVE else HarnessProjectAction.RENAME
    val titleIsValid = projectTitleIsValid(title)
    val filesConfirmed = deleteFiles &&
        normalizedProjectTitle(confirmationTitle) == project.title
    val actionEnabled = !busy && if (removing) {
        canRemove && (!deleteFiles || filesConfirmed)
    } else {
        titleIsValid
    }

    fun submit(action: HarnessProjectAction) {
        if (busy) return
        val submittedTitle = normalizedProjectTitle(title)
        val confirmedDeleteFiles = filesConfirmed
        failure = null
        failedAction = null
        busy = true
        scope.launch {
            var completed = false
            try {
                when (action) {
                    HarnessProjectAction.RENAME -> onRename(submittedTitle)
                    HarnessProjectAction.REMOVE -> onRemove(confirmedDeleteFiles)
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = projectActionFailure(error)
                failedAction = action
            } finally {
                busy = false
            }
            if (completed) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (removing) R.string.harness_project_action_remove_title
                    else R.string.harness_project_action_rename_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(bodyScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (removing) {
                    Text(
                        stringResource(R.string.harness_project_action_remove_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.harness_project_action_folder_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            project.projectFolder,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.harness_project_action_thread_count,
                                project.threadIds.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!canRemove) {
                        Text(
                            stringResource(R.string.harness_project_action_cannot_remove),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = deleteFiles,
                            onCheckedChange = {
                                if (!busy && canDeleteFiles && pendingDeleteFiles == null) {
                                    deleteFiles = it
                                    confirmationTitle = ""
                                    failure = null
                                    failedAction = null
                                }
                            },
                            enabled = !busy && canDeleteFiles && pendingDeleteFiles == null,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.harness_project_action_delete_files),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(
                                    when {
                                        pendingDeleteFiles != null ->
                                            R.string.harness_project_action_pending_removal
                                        canDeleteFiles ->
                                            R.string.harness_project_action_delete_files_warning
                                        else -> R.string.harness_project_action_remote_files_unavailable
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (deleteFiles) {
                        Text(
                            stringResource(
                                R.string.harness_project_action_type_title_prompt,
                                project.title,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = confirmationTitle,
                            onValueChange = {
                                confirmationTitle = it
                                failure = null
                                failedAction = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(stringResource(R.string.harness_project_action_type_title_label))
                            },
                            singleLine = true,
                            enabled = !busy,
                            isError = confirmationTitle.isNotBlank() && !filesConfirmed,
                            supportingText = {
                                if (confirmationTitle.isNotBlank() && !filesConfirmed) {
                                    Text(stringResource(R.string.harness_project_action_type_title_mismatch))
                                }
                            },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            failure = null
                            failedAction = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.harness_project_action_name_label)) },
                        singleLine = true,
                        enabled = !busy,
                        isError = title.isNotBlank() && !titleIsValid,
                        supportingText = {
                            val message = when {
                                title.isBlank() -> R.string.harness_project_action_name_required
                                title.trim().length > HARNESS_PROJECT_TITLE_LIMIT ->
                                    R.string.harness_project_action_name_too_long
                                else -> R.string.harness_project_action_name_supporting
                            }
                            Text(stringResource(message))
                        },
                    )
                    Text(
                        stringResource(R.string.harness_project_action_name_disclosure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure?.let { actionFailure ->
                    Text(
                        stringResource(actionFailure.resourceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Button(
                    onClick = { submit(failedAction ?: currentAction) },
                    enabled = actionEnabled,
                ) {
                    Text(
                        stringResource(
                            if (failedAction != null) {
                                if (removing && pendingDeleteFiles != null) {
                                    R.string.harness_project_action_retry_removal
                                } else {
                                    R.string.harness_project_action_retry
                                }
                            } else if (removing && pendingDeleteFiles != null) {
                                R.string.harness_project_action_retry_removal
                            } else if (removing) R.string.harness_project_action_remove
                            else R.string.harness_project_action_save,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.harness_project_action_cancel))
            }
        },
    )
}

/** One confirmation for removing several offline projects through the canonical manager. */
@Composable
internal fun HarnessProjectBatchRemovalDialog(
    previews: List<com.example.llamadroid.harness.HarnessProjectRemovalPreview>,
    canRemove: Boolean,
    onRemove: suspend (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bodyScrollState = rememberScrollState()
    val previewIds = remember(previews) { previews.map { it.id } }
    val pendingChoices = remember(previews) {
        previews.filter { it.pendingRemoval }.map { it.pendingDeleteFiles }.distinct()
    }
    val pendingChoiceConflict = pendingChoices.size > 1
    val pendingDeleteFiles = pendingChoices.singleOrNull()
    val canDeleteFiles = previews.isNotEmpty() && previews.all { it.fileDeletionSupported }
    var deleteFiles by rememberSaveable(previewIds) {
        mutableStateOf(pendingDeleteFiles ?: false)
    }
    var confirmation by rememberSaveable(previewIds) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<HarnessProjectActionFailure?>(null) }

    LaunchedEffect(pendingDeleteFiles, pendingChoiceConflict, canDeleteFiles) {
        if (pendingChoiceConflict) {
            deleteFiles = false
        } else if (pendingDeleteFiles != null) {
            deleteFiles = pendingDeleteFiles
        } else if (!canDeleteFiles) {
            deleteFiles = false
        }
    }

    val filesConfirmed = !deleteFiles && pendingDeleteFiles != true ||
        confirmation.trim() == BATCH_REMOVE_CONFIRMATION
    val actionEnabled = !busy && canRemove && !pendingChoiceConflict &&
        (!deleteFiles || filesConfirmed)

    fun submit() {
        if (!actionEnabled) return
        busy = true
        failure = null
        scope.launch {
            try {
                onRemove(deleteFiles)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = projectActionFailure(error)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(stringResource(R.string.harness_project_action_batch_remove_title, previews.size))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(bodyScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.harness_project_action_batch_remove_description))
                Text(
                    stringResource(
                        R.string.harness_project_action_batch_data_summary,
                        previews.size,
                        previews.sumOf { it.legacyConversationCount },
                        previews.sumOf { it.retainedCanonicalSessions },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                previews.forEach { preview ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            preview.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            preview.projectFolder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pendingChoiceConflict) {
                    Text(
                        stringResource(R.string.harness_project_action_batch_pending_conflict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!canRemove) {
                    Text(
                        stringResource(R.string.harness_project_action_cannot_remove),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = {
                            if (!busy && canDeleteFiles && pendingChoices.isEmpty()) {
                                deleteFiles = it
                                confirmation = ""
                                failure = null
                            }
                        },
                        enabled = !busy && canDeleteFiles && pendingChoices.isEmpty(),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.harness_project_action_batch_delete_files))
                        Text(
                            stringResource(
                                when {
                                    pendingChoices.isNotEmpty() ->
                                        R.string.harness_project_action_pending_removal
                                    canDeleteFiles -> R.string.harness_project_action_batch_delete_files_warning
                                    else -> R.string.harness_project_action_remote_files_unavailable
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (deleteFiles) {
                    Text(stringResource(R.string.harness_project_action_batch_type_remove_prompt))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            failure = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.harness_project_action_batch_type_remove_label)) },
                        singleLine = true,
                        enabled = !busy,
                        isError = confirmation.isNotBlank() && !filesConfirmed,
                        supportingText = {
                            if (confirmation.isNotBlank() && !filesConfirmed) {
                                Text(stringResource(R.string.harness_project_action_batch_type_remove_mismatch))
                            }
                        },
                    )
                }
                if (deleteFiles && previews.any { it.filesPresent }) {
                    Text(
                        stringResource(
                            R.string.harness_project_action_batch_files_present,
                            previews.count { it.filesPresent },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.harness_project_action_batch_files_retained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure?.let { actionFailure ->
                    Text(
                        stringResource(actionFailure.resourceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Button(onClick = ::submit, enabled = actionEnabled) {
                    Text(stringResource(R.string.harness_project_batch_remove))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.harness_project_action_cancel))
            }
        },
    )
}

private const val BATCH_REMOVE_CONFIRMATION = "REMOVE"
