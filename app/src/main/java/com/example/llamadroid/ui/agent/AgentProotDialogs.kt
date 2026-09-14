package com.example.llamadroid.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentSharingMode
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AgentProotRunEntity
import com.example.llamadroid.data.db.AgentProotRunStatus

/**
 * Scrollable picker used by project creation and details. A null selection
 * means “create a fresh isolated environment” and is never an existing shared
 * environment reference.
 */
@Composable
fun AgentProotEnvironmentPicker(
    environments: List<AgentProotEnvironmentEntity>,
    selectedEnvironmentId: String?,
    onSelectionChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowCreateIsolated: Boolean = true
) {
    val choices = environments
        .filter { it.sharingMode == AgentProotEnvironmentSharingMode.SHARED || it.id == selectedEnvironmentId }
        .sortedWith(compareBy<AgentProotEnvironmentEntity> { it.sharingMode }.thenBy { it.displayName.lowercase() })
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.agent_proot_environment_choice_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.agent_proot_environment_choice_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (allowCreateIsolated) {
            ProotRadioChoice(
                selected = selectedEnvironmentId == null,
                title = stringResource(R.string.agent_proot_create_isolated),
                detail = stringResource(R.string.agent_proot_create_isolated_desc),
                onClick = { onSelectionChange(null) }
            )
        }
        choices.forEach { environment ->
            ProotRadioChoice(
                selected = selectedEnvironmentId == environment.id,
                title = environment.displayName,
                detail = stringResource(
                    if (environment.sharingMode == AgentProotEnvironmentSharingMode.SHARED) {
                        R.string.agent_proot_shared_environment_detail
                    } else {
                        R.string.agent_proot_isolated_environment_detail
                    },
                    prootEnvironmentStatusLabel(environment.status)
                ),
                onClick = { onSelectionChange(environment.id) }
            )
        }
        if (choices.isEmpty() && !allowCreateIsolated) {
            Text(
                text = stringResource(R.string.agent_proot_no_shared_environments),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProotRadioChoice(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Canonical, user-driven manager for app-owned Debian environments. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProotEnvironmentManagerDialog(
    environments: List<AgentProotEnvironmentEntity>,
    projects: List<AgentConversationEntity>,
    runs: List<AgentProotRunEntity>,
    onCreate: (displayName: String, sharingMode: String) -> Unit,
    onDelete: (AgentProotEnvironmentEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var newMode by rememberSaveable { mutableStateOf(AgentProotEnvironmentSharingMode.ISOLATED) }
    var pendingCreate by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<AgentProotEnvironmentEntity?>(null) }
    val defaultEnvironmentName = stringResource(R.string.agent_proot_default_name)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.agent_proot_manager_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                            }
                        }
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            stringResource(R.string.agent_proot_manager_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .38f))) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.agent_proot_create_title), fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it.take(80) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.agent_proot_name_label)) }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = newMode == AgentProotEnvironmentSharingMode.ISOLATED,
                                        onClick = { newMode = AgentProotEnvironmentSharingMode.ISOLATED },
                                        label = { Text(stringResource(R.string.agent_proot_isolated_label)) }
                                    )
                                    FilterChip(
                                        selected = newMode == AgentProotEnvironmentSharingMode.SHARED,
                                        onClick = { newMode = AgentProotEnvironmentSharingMode.SHARED },
                                        label = { Text(stringResource(R.string.agent_proot_shared_label)) }
                                    )
                                }
                                Text(
                                    stringResource(
                                        if (newMode == AgentProotEnvironmentSharingMode.SHARED) {
                                            R.string.agent_proot_shared_warning
                                        } else {
                                            R.string.agent_proot_isolated_warning
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        pendingCreate = newName.trim().ifBlank { defaultEnvironmentName } to newMode
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.agent_proot_create_action))
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.agent_proot_existing_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (environments.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.agent_proot_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(environments, key = { it.id }) { environment ->
                        val attachedProjects = projects.filter { it.prootEnvironmentId == environment.id }
                        val activeRun = runs.firstOrNull {
                            it.environmentId == environment.id &&
                                it.status in setOf(
                                    AgentProotRunStatus.QUEUED,
                                    AgentProotRunStatus.RUNNING,
                                    AgentProotRunStatus.STOP_REQUESTED
                                )
                        }
                        val canDelete = attachedProjects.isEmpty() && activeRun == null
                        Card {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(environment.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            stringResource(
                                                R.string.agent_proot_environment_summary,
                                                prootEnvironmentModeLabel(environment.sharingMode),
                                                prootEnvironmentStatusLabel(environment.status),
                                                environment.imageVersion
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { pendingDelete = environment },
                                        enabled = canDelete
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.agent_proot_delete_action))
                                    }
                                }
                                Text(
                                    stringResource(R.string.agent_proot_storage_size, environment.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (attachedProjects.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.agent_proot_attached_projects, attachedProjects.size),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    attachedProjects.take(4).forEach { project ->
                                        Text("• ${project.title}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                activeRun?.let {
                                    Text(
                                        stringResource(R.string.agent_proot_active_run),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (!canDelete) {
                                    Text(
                                        stringResource(R.string.agent_proot_delete_blocked),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.agent_proot_security_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        val environment = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.agent_proot_delete_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.agent_proot_delete_message, environment.displayName))
                    Text(stringResource(R.string.agent_proot_delete_warning), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = { pendingDelete = null; onDelete(environment) }) {
                    Text(stringResource(R.string.agent_proot_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (pendingCreate != null) {
        val (displayName, sharingMode) = pendingCreate!!
        AlertDialog(
            onDismissRequest = { pendingCreate = null },
            title = { Text(stringResource(R.string.agent_proot_create_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.agent_proot_create_confirm_message,
                        displayName,
                        prootEnvironmentModeLabel(sharingMode)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCreate = null
                        newName = ""
                        onCreate(displayName, sharingMode)
                    }
                ) {
                    Text(stringResource(R.string.agent_proot_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCreate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun prootEnvironmentModeLabel(mode: String): String = stringResource(
    when (mode) {
        AgentProotEnvironmentSharingMode.SHARED -> R.string.agent_proot_mode_shared
        AgentProotEnvironmentSharingMode.ISOLATED -> R.string.agent_proot_mode_isolated
        else -> R.string.agent_proot_mode_unknown
    }
)

@Composable
internal fun prootEnvironmentStatusLabel(status: String): String = stringResource(
    when (status) {
        AgentProotEnvironmentStatus.NOT_INSTALLED -> R.string.agent_proot_status_not_installed
        AgentProotEnvironmentStatus.INSTALLING -> R.string.agent_proot_status_installing
        AgentProotEnvironmentStatus.READY -> R.string.agent_proot_status_ready
        AgentProotEnvironmentStatus.UPDATING -> R.string.agent_proot_status_updating
        AgentProotEnvironmentStatus.BROKEN -> R.string.agent_proot_status_broken
        AgentProotEnvironmentStatus.DELETING -> R.string.agent_proot_status_deleting
        else -> R.string.agent_proot_status_unknown
    }
)
