package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard

@Composable
fun HarnessAgentPresetCard(
    state: HarnessAgentPresetUiState,
    selectedSessionId: String?,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var copyFrom by rememberSaveable { mutableStateOf("") }
    var copyId by rememberSaveable { mutableStateOf("") }
    AppSectionCard(
        modifier = Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        HarnessSectionHeading(title = stringResource(R.string.harness_agent_presets_title), icon = Icons.Default.Extension)
        Text(stringResource(R.string.harness_agent_presets_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshAgentPresets) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_agent_presets))
        }
        if (state.authorable) {
            HarnessSwitchRow(
                title = stringResource(R.string.harness_agent_preset_mode_selection),
                description = stringResource(R.string.harness_agent_presets_description),
                checked = state.modeSelectionEnabled,
                onCheckedChange = { onAction(NativeHarnessUiAction.SetAgentPresetModeSelection(it)) }
            )
            OutlinedTextField(
                value = copyId,
                onValueChange = { copyId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_agent_preset_copy_id)) },
                placeholder = { Text(stringResource(R.string.harness_agent_preset_copy_hint)) },
                singleLine = true
            )
            Button(
                onClick = {
                    onAction(NativeHarnessUiAction.DuplicateAgentPreset(copyFrom, copyId.trim()))
                    copyId = ""
                },
                enabled = copyFrom.isNotBlank() && copyId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_agent_preset_duplicate))
            }
        }
        if (state.presets.isEmpty()) {
            Text(stringResource(R.string.harness_agent_presets_empty), style = MaterialTheme.typography.bodySmall)
        }
        state.presets.forEach { preset ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            preset.name ?: preset.id,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            preset.id,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (preset.trust == "system") {
                                stringResource(R.string.harness_agent_preset_system)
                            } else {
                                stringResource(R.string.harness_agent_preset_user)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (preset.isDefault) {
                        Text(stringResource(R.string.harness_agent_preset_default), color = MaterialTheme.colorScheme.primary)
                    }
                }
                preset.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                preset.broken?.let {
                    Text(
                        stringResource(R.string.harness_agent_preset_broken, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ReadAgentPreset(preset.id)) }) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.harness_agent_preset_read))
                    }
                    if (preset.trust == "user") {
                        OutlinedButton(onClick = {
                            onAction(NativeHarnessUiAction.OpenAgentPresetDirectory(preset.id))
                        }) {
                            Text(stringResource(R.string.harness_agent_preset_open_directory))
                        }
                    }
                    if (selectedSessionId != null && preset.broken == null) {
                        OutlinedButton(onClick = {
                            onAction(NativeHarnessUiAction.SelectAgentPreset(selectedSessionId, preset.id))
                        }) {
                            Text(stringResource(R.string.harness_agent_preset_select))
                        }
                    }
                    if (!preset.isDefault) {
                        OutlinedButton(onClick = {
                            onAction(NativeHarnessUiAction.SetAgentPresetDefault(preset.id))
                        }) {
                            Text(stringResource(R.string.harness_agent_preset_set_default))
                        }
                    }
                    if (state.authorable && preset.trust == "user") {
                        OutlinedButton(onClick = {
                            copyFrom = preset.id
                            copyId = "${preset.id}-copy"
                        }) {
                            Text(stringResource(R.string.harness_agent_preset_duplicate))
                        }
                        OutlinedButton(onClick = {
                            onAction(NativeHarnessUiAction.DeleteAgentPreset(preset.id))
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.harness_agent_preset_delete))
                        }
                    }
                }
                if (state.selectedPresetId == preset.id && state.documentPreview != null) {
                    Text(stringResource(R.string.harness_agent_preset_document), style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.documentPreview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun HarnessWorkspaceManagerCard(
    state: HarnessWorkspaceManagerUiState,
    sessions: List<HarnessSessionUiState>,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val sessionTitles = sessions.associate { it.id to it.title }
    AppSectionCard(
        modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        HarnessSectionHeading(title = stringResource(R.string.harness_workspace_groups_title), icon = Icons.Default.Edit)
        Text(stringResource(R.string.harness_workspace_groups_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshWorkspaces) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_workspaces))
        }
        if (state.workspaces.isEmpty()) {
            Text(stringResource(R.string.harness_workspace_empty), style = MaterialTheme.typography.bodySmall)
        }
        state.workspaces.forEachIndexed { index, workspace ->
            HarnessWorkspaceGroupRow(
                workspace = workspace,
                index = index,
                all = state.workspaces,
                sessionTitles = sessionTitles,
                onAction = onAction
            )
        }
    }
}

@Composable
private fun HarnessWorkspaceGroupRow(
    workspace: HarnessWorkspaceGroupUi,
    index: Int,
    all: List<HarnessWorkspaceGroupUi>,
    sessionTitles: Map<String, String>,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var editing by rememberSaveable(workspace.workspaceId, workspace.title) { mutableStateOf(false) }
    var draft by rememberSaveable(workspace.workspaceId, workspace.title) { mutableStateOf(workspace.title) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_workspace_rename)) },
                singleLine = true
            )
            Button(
                onClick = {
                    onAction(NativeHarnessUiAction.RenameWorkspace(workspace.workspaceId, draft.trim()))
                    editing = false
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_save))
            }
        } else {
            Text(workspace.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(workspace.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.harness_workspace_sessions, workspace.sessionIds.size), style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.harness_workspace_rename))
            }
            OutlinedButton(onClick = {
                onAction(NativeHarnessUiAction.DeleteWorkspace(workspace.workspaceId))
            }) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.harness_workspace_delete))
            }
            if (index > 0) {
                OutlinedButton(onClick = {
                    onAction(NativeHarnessUiAction.MoveWorkspace(workspace.workspaceId, all[index - 1].workspaceId))
                }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null)
                    Text(stringResource(R.string.harness_workspace_move_up))
                }
            }
            if (index < all.lastIndex) {
                OutlinedButton(onClick = {
                    val before = all.getOrNull(index + 2)?.workspaceId
                    onAction(NativeHarnessUiAction.MoveWorkspace(workspace.workspaceId, before))
                }) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    Text(stringResource(R.string.harness_workspace_move_down))
                }
            }
        }
        workspace.sessionIds.forEachIndexed { sessionIndex, sessionId ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    sessionTitles[sessionId] ?: sessionId,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                if (sessionIndex > 0) {
                    IconButtonLike(
                        label = stringResource(R.string.harness_session_move_up),
                        icon = Icons.Default.ArrowUpward,
                        onClick = {
                            onAction(NativeHarnessUiAction.MoveSessionInWorkspace(
                                workspace.workspaceId,
                                sessionId,
                                workspace.sessionIds[sessionIndex - 1]
                            ))
                        }
                    )
                }
                if (sessionIndex < workspace.sessionIds.lastIndex) {
                    IconButtonLike(
                        label = stringResource(R.string.harness_session_move_down),
                        icon = Icons.Default.ArrowDownward,
                        onClick = {
                            onAction(NativeHarnessUiAction.MoveSessionInWorkspace(
                                workspace.workspaceId,
                                sessionId,
                                workspace.sessionIds.getOrNull(sessionIndex + 2)
                            ))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IconButtonLike(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
fun HarnessCordisCard(
    state: HarnessCordisUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(
        modifier = Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        HarnessSectionHeading(title = stringResource(R.string.harness_cordis_title), icon = Icons.Default.Extension)
        Text(stringResource(R.string.harness_cordis_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshCordis) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_cordis))
        }
        if (state.plugins.isEmpty()) {
            Text(stringResource(R.string.harness_cordis_empty), style = MaterialTheme.typography.bodySmall)
        }
        state.plugins.forEach { plugin ->
            val latest = plugin.latestRun
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plugin.pluginId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (state.inspectedPluginId == plugin.pluginId) {
                    Text(
                        stringResource(R.string.harness_cordis_inspected),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                plugin.currentPackageId?.let {
                    Text(stringResource(R.string.harness_cordis_current) + ": " + it, style = MaterialTheme.typography.labelSmall)
                }
                plugin.activePackageId?.let {
                    Text(stringResource(R.string.harness_cordis_active) + ": " + it, style = MaterialTheme.typography.labelSmall)
                }
                latest?.let {
                    Text(
                        stringResource(R.string.harness_cordis_status, cordisStatusLabel(it.status)),
                        style = MaterialTheme.typography.labelMedium
                    )
                    it.error?.let { error -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (it.requiresApproval && it.approvalRequestId != null) {
                        Text(stringResource(R.string.harness_cordis_approval), style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = {
                                onAction(NativeHarnessUiAction.ApproveCordis(
                                    plugin.pluginId,
                                    it.packageId,
                                    it.approvalRequestId,
                                    it.mode == "update",
                                    false
                                ))
                            }) { Text(stringResource(R.string.harness_cordis_approve_once)) }
                            OutlinedButton(onClick = {
                                onAction(NativeHarnessUiAction.ApproveCordis(
                                    plugin.pluginId,
                                    it.packageId,
                                    it.approvalRequestId,
                                    it.mode == "update",
                                    true
                                ))
                            }) { Text(stringResource(R.string.harness_cordis_approve_future)) }
                            OutlinedButton(onClick = {
                                onAction(NativeHarnessUiAction.RejectCordis(it.approvalRequestId, it.pluginRunId))
                            }) { Text(stringResource(R.string.harness_cordis_reject)) }
                        }
                    }
                }
                plugin.packages.forEach { pkg ->
                    val isCurrent = pkg.packageId == plugin.currentPackageId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.harness_cordis_package, pkg.name), style = MaterialTheme.typography.bodySmall)
                            Text(pkg.packageId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            if (pkg.purpose.isNotBlank()) {
                                Text(pkg.purpose, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                stringResource(
                                    R.string.harness_cordis_host_client,
                                    if (pkg.hasHostHalf) stringResource(R.string.harness_cordis_present) else stringResource(R.string.harness_cordis_absent),
                                    if (pkg.hasClientHalf) stringResource(R.string.harness_cordis_present) else stringResource(R.string.harness_cordis_absent)
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            OutlinedButton(onClick = {
                                onAction(NativeHarnessUiAction.RunCordis(plugin.pluginId, pkg.packageId, update = !isCurrent))
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text(stringResource(if (isCurrent) R.string.harness_cordis_run else R.string.harness_cordis_rollback))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.InspectCordis(plugin.pluginId)) }) {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                        Text(stringResource(R.string.harness_cordis_inspect))
                    }
                    if (plugin.activePackageId != null || latest?.status == "awaiting-approval") {
                        OutlinedButton(onClick = { onAction(NativeHarnessUiAction.StopCordis(plugin.pluginId)) }) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text(stringResource(R.string.harness_cordis_stop))
                        }
                    }
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.RemoveCordis(plugin.pluginId)) }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(stringResource(R.string.harness_cordis_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun cordisStatusLabel(status: String): String = stringResource(
    when (status) {
        "awaiting-approval" -> R.string.harness_cordis_status_awaiting_approval
        "starting-host" -> R.string.harness_cordis_status_starting_host
        "client-pending" -> R.string.harness_cordis_status_client_pending
        "running" -> R.string.harness_cordis_status_running
        "waiting" -> R.string.harness_cordis_status_waiting
        "rejected" -> R.string.harness_cordis_status_rejected
        "failed" -> R.string.harness_cordis_status_failed
        "cancelled" -> R.string.harness_cordis_status_cancelled
        "stopped" -> R.string.harness_cordis_status_stopped
        else -> R.string.harness_cordis_status_unknown
    }
)
