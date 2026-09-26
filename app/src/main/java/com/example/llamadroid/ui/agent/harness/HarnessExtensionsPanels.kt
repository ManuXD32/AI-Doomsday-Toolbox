package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle

@Composable
private fun harnessPluginReadOnlyLabel(reason: String): String = when (reason) {
    HARNESS_PLUGIN_UNADDRESSABLE_REASON -> stringResource(R.string.harness_plugin_read_only_unaddressable)
    HARNESS_PLUGIN_PRESET_REASON -> stringResource(R.string.harness_plugin_read_only_preset)
    else -> stringResource(R.string.harness_plugin_read_only, reason)
}

@Composable
private fun harnessPluginPhaseLabel(phase: String?, enabled: Boolean? = null): String? = when (phase) {
    null -> enabled?.let {
        stringResource(if (it) R.string.harness_plugin_inventory_enabled else R.string.harness_plugin_inventory_disabled)
    }
    "conditional" -> stringResource(R.string.harness_plugin_phase_conditional)
    "pending" -> stringResource(R.string.harness_plugin_phase_pending)
    "loading" -> stringResource(R.string.harness_plugin_phase_loading)
    "active" -> stringResource(R.string.harness_plugin_phase_active)
    "failed" -> stringResource(R.string.harness_plugin_phase_failed)
    "unloading" -> stringResource(R.string.harness_plugin_phase_unloading)
    "disposed" -> stringResource(R.string.harness_plugin_phase_disposed)
    else -> stringResource(R.string.harness_plugin_phase_unknown)
}

internal fun filterHarnessPlugins(
    plugins: List<HarnessPluginUi>,
    query: String,
): List<HarnessPluginUi> {
    val needle = query.trim()
    if (needle.isEmpty()) return plugins
    return plugins.filter { plugin ->
        listOf(
            plugin.id,
            plugin.name,
            plugin.summary,
            plugin.versionLabel,
            plugin.bundleName,
            plugin.errorCode,
            plugin.errorDiagnostic,
            plugin.readOnlyReason,
            plugin.overrides.joinToString(" "),
        ).filterNotNull().any { it.contains(needle, ignoreCase = true) }
    }
}

internal fun filterHarnessPluginInventory(
    inventory: List<HarnessPluginInventoryUi>,
    query: String,
): List<HarnessPluginInventoryUi> {
    val needle = query.trim()
    if (needle.isEmpty()) return inventory
    return inventory.filter { entry ->
        listOf(entry.entryId, entry.moduleName, entry.phase, entry.patchId, entry.readOnlyReason)
            .filterNotNull().any { it.contains(needle, ignoreCase = true) }
    }
}

@Composable
fun HarnessExtensionsTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = listOf(
        "plugins" to R.string.harness_plugins_title, "cordis" to R.string.harness_cordis_title,
        "skills" to R.string.harness_skills_title, "commands" to R.string.harness_commands_title,
        "jobs" to R.string.harness_jobs_title, "subagents" to R.string.harness_subagents_title,
        "trajectory" to R.string.harness_trajectory_title,
    )
    LazyColumn(
        modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.notice?.let { notice -> item { HarnessNoticeBanner(notice, onAction) } }
        val load = state.managementLoads[HarnessManagementArea.EXTENSIONS]
        if (load?.isLoading == true) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        load?.errorCode?.let { code -> item {
            Text(stringResource(R.string.harness_management_load_failed, code), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onAction(NativeHarnessUiAction.LoadManagement(HarnessManagementArea.EXTENSIONS, true)) }) {
                Text(stringResource(R.string.harness_settings_retry))
            }
        } }
        if (section == null) {
            sections.forEach { (id, label) -> item(key = id) {
                AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                    TextButton(onClick = { section = id }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(label))
                    }
                }
            } }
        } else {
            item { TextButton(onClick = { section = null }) { Text(stringResource(R.string.harness_extensions_back)) } }
            item(key = section) { when (section) {
                "plugins" -> HarnessPluginsCard(state.extensions.plugins, state.extensions.pluginInventory,
                    state.extensions.pluginManagementAvailable, state.extensions.pluginInstall, state.extensions.pluginInspection, onAction)
                "cordis" -> HarnessCordisCard(state.extensions.cordis, onAction)
                "skills" -> HarnessSkillsCard(state.extensions.skills, onAction)
                "commands" -> HarnessCommandsCard(state, onAction)
                "jobs" -> HarnessJobsCard(state.extensions.jobs, onAction)
                "subagents" -> HarnessSubagentsCard(state.extensions.subagents, onAction)
                "trajectory" -> HarnessTrajectoryCard(state.extensions.trajectory, onAction)
            } }
        }
    }
}

@Composable
private fun HarnessCommandsCard(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_commands_title), icon = Icons.Default.Terminal)
        Text(stringResource(R.string.harness_commands_description), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.commandLine,
            onValueChange = { onAction(NativeHarnessUiAction.UpdateCommandLine(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_command_line_label)) },
            placeholder = { Text(stringResource(R.string.harness_command_line_hint)) },
            singleLine = true
        )
        Button(
            onClick = { onAction(NativeHarnessUiAction.ExecuteCommand) },
            enabled = state.commandLine.trim().startsWith("/"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.harness_execute_command))
        }
        state.commands.forEach { command ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(command.name, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                command.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun HarnessPluginsCard(
    plugins: List<HarnessPluginUi>,
    inventory: List<HarnessPluginInventoryUi>,
    managementAvailable: Boolean?,
    install: HarnessPluginInstallUiState,
    inspection: HarnessPluginInspectionUi?,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    var installSpec by rememberSaveable { mutableStateOf("") }
    var pluginQuery by rememberSaveable { mutableStateOf("") }
    var pendingUninstall by rememberSaveable { mutableStateOf<String?>(null) }
    val managerReady = managementAvailable == true
    val filteredPlugins = remember(plugins, pluginQuery) { filterHarnessPlugins(plugins, pluginQuery) }
    val filteredInventory = remember(inventory, pluginQuery) {
        filterHarnessPluginInventory(inventory, pluginQuery)
    }
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_plugins_title), icon = Icons.Default.Extension)
        Text(stringResource(R.string.harness_plugins_description), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = pluginQuery,
            onValueChange = { pluginQuery = it },
            modifier = Modifier.fillMaxWidth().testTag("harness_plugin_search"),
            label = { Text(stringResource(R.string.harness_plugins_search_label)) },
            placeholder = { Text(stringResource(R.string.harness_plugins_search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (pluginQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { pluginQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.harness_plugins_clear_search))
                    }
                }
            } else null,
        )
        if (managementAvailable == false) {
            Text(
                stringResource(R.string.harness_plugin_management_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshPlugins) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_plugins))
        }
        AppAdvancedSection(
            title = stringResource(R.string.harness_plugins_install_advanced),
            initiallyExpanded = false,
        ) {
            Text(
                stringResource(R.string.harness_plugins_install_advanced_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = installSpec,
                    onValueChange = { installSpec = it },
                    modifier = Modifier.weight(1f),
                    enabled = managerReady,
                    label = { Text(stringResource(R.string.harness_plugin_spec_label)) },
                    singleLine = true
                )
                Button(
                    onClick = {
                        onAction(NativeHarnessUiAction.InstallPlugin(installSpec.trim()))
                        installSpec = ""
                    },
                    enabled = managerReady && installSpec.isNotBlank()
                ) {
                    Text(stringResource(R.string.harness_install))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onAction(NativeHarnessUiAction.InspectPlugin(installSpec.trim())) },
                    enabled = managerReady && installSpec.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.harness_plugin_inspect))
                }
                val requestId = install.requestId
                if (requestId != null) {
                    OutlinedButton(
                        onClick = { onAction(NativeHarnessUiAction.CancelPluginInstall(requestId)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.harness_plugin_cancel_install))
                    }
                }
            }
            install.phase?.let { phase ->
                val phaseLabel = when (phase) {
                    "checking" -> stringResource(R.string.harness_plugin_phase_checking)
                    "installing" -> stringResource(R.string.harness_plugin_phase_installing)
                    "cancelling" -> stringResource(R.string.harness_plugin_phase_cancelling)
                    "applying" -> stringResource(R.string.harness_plugin_phase_applying)
                    "done" -> stringResource(R.string.harness_plugin_phase_done)
                    "failed" -> stringResource(R.string.harness_plugin_phase_failed)
                    "cancelled" -> stringResource(R.string.harness_plugin_phase_cancelled)
                    "restart-required" -> stringResource(R.string.harness_plugin_phase_restart_required)
                    else -> stringResource(R.string.harness_plugin_phase_unknown)
                }
                Text(
                    stringResource(R.string.harness_plugin_install_phase, phaseLabel),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (install.logs.isNotEmpty()) {
                Text(stringResource(R.string.harness_plugin_install_log), style = MaterialTheme.typography.labelMedium)
                Text(
                    install.logs.takeLast(8).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (install.pendingBuilds.isNotEmpty() && !install.packageSpec.isNullOrBlank()) {
                Text(
                    stringResource(R.string.harness_plugin_build_approval_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    install.pendingBuilds.take(12).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    onClick = {
                        onAction(
                            NativeHarnessUiAction.ApprovePluginBuilds(
                                install.packageSpec,
                                install.pendingBuilds
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.harness_approve_plugin_builds))
                }
            }
            inspection?.let { result ->
                AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                    Text(stringResource(R.string.harness_plugin_inspection_title), style = MaterialTheme.typography.titleSmall)
                    val displayName = result.name ?: result.spec
                    if (result.accepted) {
                        Text(stringResource(R.string.harness_plugin_inspection_accepted, displayName))
                        result.version?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        result.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val bundleLabel = when (result.isBundle) {
                            true -> R.string.harness_plugin_inspection_bundle
                            false -> R.string.harness_plugin_inspection_not_bundle
                            null -> R.string.harness_plugin_inspection_bundle_unknown
                        }
                        Text(stringResource(bundleLabel), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val problemLabel = when (result.problem) {
                            "already-installed" -> stringResource(R.string.harness_plugin_inspection_already_installed)
                            "invalid-spec" -> stringResource(R.string.harness_plugin_inspection_invalid_spec)
                            "not-a-package" -> stringResource(R.string.harness_plugin_inspection_not_package)
                            "not-a-bundle" -> stringResource(R.string.harness_plugin_inspection_not_bundle)
                            "not-found" -> stringResource(R.string.harness_plugin_inspection_not_found)
                            "network" -> stringResource(R.string.harness_plugin_inspection_network)
                            "unknown" -> stringResource(R.string.harness_plugin_inspection_unknown)
                            "invalid-result" -> stringResource(R.string.harness_plugin_inspection_invalid_result)
                            else -> result.problem ?: result.reason.orEmpty()
                        }
                        Text(stringResource(R.string.harness_plugin_inspection_refused, problemLabel))
                        result.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (plugins.isEmpty()) {
            Text(stringResource(R.string.harness_plugins_empty), style = MaterialTheme.typography.bodySmall)
        }
        if (plugins.isNotEmpty() && filteredPlugins.isEmpty()) {
            Text(stringResource(R.string.harness_plugins_no_matches), style = MaterialTheme.typography.bodySmall)
        }
        val filteredManagedPlugins = filteredPlugins.filter(::isHarnessManagedPlugin)
        val filteredOptionalPlugins = filteredPlugins.filterNot(::isHarnessManagedPlugin)
        if (filteredManagedPlugins.isNotEmpty()) {
            Text(stringResource(R.string.harness_plugins_managed_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.harness_plugins_managed_description), style = MaterialTheme.typography.bodySmall)
            filteredManagedPlugins.forEach { plugin ->
                HarnessPluginRow(plugin, onAction) { pendingUninstall = it }
            }
        }
        if (filteredOptionalPlugins.isNotEmpty()) {
            Text(stringResource(R.string.harness_plugins_optional_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.harness_plugins_optional_description), style = MaterialTheme.typography.bodySmall)
            filteredOptionalPlugins.forEach { plugin ->
                HarnessPluginRow(plugin, onAction) { pendingUninstall = it }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.harness_plugin_inventory_title), style = MaterialTheme.typography.titleSmall)
        if (inventory.isEmpty()) {
            Text(stringResource(R.string.harness_plugin_inventory_empty), style = MaterialTheme.typography.bodySmall)
        } else if (filteredInventory.isEmpty()) {
            Text(stringResource(R.string.harness_plugins_no_matches), style = MaterialTheme.typography.bodySmall)
        } else {
            filteredInventory.take(100).forEach { entry ->
                val stateLabel = harnessPluginPhaseLabel(entry.phase, entry.enabled).orEmpty()
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.harness_plugin_inventory_entry, entry.moduleName, stateLabel),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    entry.readOnlyReason?.let {
                        Text(
                            harnessPluginReadOnlyLabel(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        pendingUninstall?.let { bundleName ->
            AlertDialog(
                onDismissRequest = { pendingUninstall = null },
                title = { Text(stringResource(R.string.harness_plugin_uninstall_confirm_title)) },
                text = {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            stringResource(
                                R.string.harness_plugin_uninstall_confirm_message,
                                bundleName.take(160)
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingUninstall = null
                        onAction(NativeHarnessUiAction.UninstallPlugin(bundleName))
                    }) {
                        Text(stringResource(R.string.harness_uninstall))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUninstall = null }) {
                        Text(stringResource(R.string.harness_cancel))
                    }
                }
            )
        }
    }
}

internal fun isHarnessManagedPlugin(plugin: HarnessPluginUi): Boolean =
    plugin.id.startsWith("@deepseek-ai/dsh-") ||
        plugin.id.startsWith("@manuxd32/adt-dsh-") ||
        plugin.bundleName?.startsWith("@deepseek-ai/dsh-") == true ||
        plugin.bundleName?.startsWith("@manuxd32/adt-dsh-") == true

@Composable
private fun HarnessPluginRow(
    plugin: HarnessPluginUi,
    onAction: (NativeHarnessUiAction) -> Unit,
    onRequestUninstall: (String) -> Unit,
) {
    val managed = isHarnessManagedPlugin(plugin)
    // App-managed system bundles are presented for diagnostics and status. Their lifecycle is
    // controlled by the Runtime screen, so an optional plugin action must never uninstall one.
    val showBundleSwitch = !managed && plugin.bundleName != null && (plugin.installed || plugin.optional)
    val actionLabel = when {
        !managed && plugin.installed && plugin.canUninstall -> stringResource(R.string.harness_uninstall)
        !managed && !plugin.installed && !plugin.optional && plugin.canInstall -> stringResource(R.string.harness_install)
        else -> null
    }
    HarnessExtensionRow(
        title = plugin.name,
        summary = plugin.summary,
        version = plugin.versionLabel,
        enabled = plugin.enabled,
        showSwitch = showBundleSwitch,
        switchEnabled = plugin.canToggle,
        onEnabledChange = {
            onAction(
                if (plugin.bundleName != null) {
                    NativeHarnessUiAction.SetPluginBundleEnabled(plugin.bundleName, it)
                } else {
                    NativeHarnessUiAction.SetPluginEnabled(plugin.id, it)
                }
            )
        },
        actionLabel = actionLabel,
        actionEnabled = actionLabel != null,
        onAction = {
            when {
                !managed && plugin.installed && plugin.canUninstall -> onRequestUninstall(plugin.bundleName ?: plugin.id)
                !managed && !plugin.installed && !plugin.optional && plugin.canInstall ->
                    onAction(NativeHarnessUiAction.InstallPlugin(plugin.bundleName ?: plugin.id))
            }
        }
    )
    if (plugin.optional && !plugin.installed) {
        Text(
            stringResource(R.string.harness_plugin_optional_disabled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    plugin.readOnlyReason?.let {
        Text(
            harnessPluginReadOnlyLabel(it),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    plugin.errorCode?.let { code ->
        Text(
            stringResource(R.string.harness_plugin_problem, code),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    plugin.errorDiagnostic?.let { diagnostic ->
        Text(
            diagnostic.take(500),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (plugin.overrides.isNotEmpty()) {
        Text(
            stringResource(R.string.harness_plugin_overrides, plugin.overrides.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    plugin.rows.forEach { row ->
        HarnessSwitchRow(
            title = row.label,
            description = row.rowId,
            checked = row.enabled,
            enabled = row.canChange,
            onCheckedChange = { enabled ->
                row.entryId?.let { entryId ->
                    onAction(NativeHarnessUiAction.SetPluginRowEnabled(entryId, enabled))
                }
            }
        )
        harnessPluginPhaseLabel(row.phase)?.let {
            Text(
                stringResource(R.string.harness_plugin_row_phase, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        row.readOnlyReason?.let {
            Text(
                harnessPluginReadOnlyLabel(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (plugin.pendingBuildApproval && plugin.pendingBuilds.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                onAction(
                    NativeHarnessUiAction.ApprovePluginBuilds(
                        plugin.bundleName ?: plugin.id,
                        plugin.pendingBuilds
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.harness_approve_plugin_builds))
        }
    }
}

@Composable
private fun HarnessSkillsCard(
    skills: List<HarnessSkillUi>,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val openInline = LocalHarnessInlineAction.current
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_skills_title), icon = Icons.Default.Code)
        Text(stringResource(R.string.harness_skills_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshSkills) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_skills))
        }
        if (skills.isEmpty()) {
            Text(stringResource(R.string.harness_skills_empty), style = MaterialTheme.typography.bodySmall)
        }
        skills.forEach { skill ->
            HarnessExtensionRow(
                title = skill.name,
                summary = skill.summary,
                enabled = skill.enabled,
                showSwitch = skill.installed && skill.canToggle,
                onEnabledChange = { onAction(NativeHarnessUiAction.SetSkillEnabled(skill.id, it)) },
                actionLabel = if (skill.canRemove) stringResource(R.string.harness_remove) else null,
                actionEnabled = skill.canRemove,
                onAction = { onAction(NativeHarnessUiAction.RemoveSkill(skill.id)) }
            )
            ResponsiveActionGroup(
                modifier = Modifier.fillMaxWidth(),
                actions = listOf(
                    ResponsiveAction(
                        label = stringResource(R.string.harness_inline_open_skill),
                        onClick = { openInline(HarnessInlineTarget.Skill(skill.id)) },
                        enabled = skill.id.isNotBlank(),
                        style = ResponsiveActionStyle.Text,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun HarnessJobsCard(
    jobs: List<HarnessJobUi>,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_jobs_title), icon = Icons.Default.Terminal)
        Text(stringResource(R.string.harness_jobs_description), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshJobs) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_jobs))
        }
        if (jobs.isEmpty()) {
            Text(stringResource(R.string.harness_jobs_empty), style = MaterialTheme.typography.bodySmall)
        }
        jobs.forEach { job ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(job.statusLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (job.canRead) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.ReadJob(job.id)) }) {
                        Text(stringResource(R.string.harness_read_job))
                    }
                }
                if (job.canKill) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.KillJob(job.id)) }) {
                        Text(stringResource(R.string.harness_kill_job))
                    }
                }
            }
            if (job != jobs.last()) HorizontalDivider()
        }
    }
}

@Composable
private fun HarnessSubagentsCard(
    state: HarnessSubagentUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val maximum = state.maximumAllowed.coerceIn(1, 8)
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_subagents_title), icon = Icons.Default.Code)
        OutlinedButton(
            onClick = { onAction(NativeHarnessUiAction.RefreshSubagents) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.harness_refresh_subagents))
        }
        if (state.canConfigure) {
            HarnessSwitchRow(
                title = stringResource(R.string.harness_subagents_enabled),
                description = stringResource(R.string.harness_subagents_description),
                checked = state.enabled,
                onCheckedChange = { onAction(NativeHarnessUiAction.SetSubagentsEnabled(it)) }
            )
            Text(
                stringResource(R.string.harness_subagents_concurrency, state.maximumConcurrent, state.activeCount),
                style = MaterialTheme.typography.labelMedium
            )
            if (maximum > 1) {
                Slider(
                    value = state.maximumConcurrent.coerceIn(1, maximum).toFloat(),
                    onValueChange = {
                        onAction(
                            NativeHarnessUiAction.SetSubagentConcurrency(
                                it.toInt().coerceIn(1, maximum)
                            )
                        )
                    },
                    valueRange = 1f..maximum.toFloat(),
                    steps = (maximum - 2).coerceAtLeast(0),
                    enabled = state.enabled
                )
            }
        }
        if (state.agentNames.isNotEmpty()) {
            Text(
                stringResource(R.string.harness_subagents_active, state.agentNames.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        state.children.forEach { child ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(child.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(child.statusLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    child.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (child.canPrompt) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.PromptSubagent(child.id)) }) {
                        Text(stringResource(R.string.harness_prompt_subagent))
                    }
                }
                if (child.canInterrupt) {
                    OutlinedButton(onClick = { onAction(NativeHarnessUiAction.InterruptSubagent(child.id)) }) {
                        Text(stringResource(R.string.harness_interrupt_subagent))
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessTrajectoryCard(
    state: HarnessTrajectoryUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
        HarnessSectionHeading(title = stringResource(R.string.harness_trajectory_title), icon = Icons.Default.History)
        HarnessSwitchRow(
            title = stringResource(R.string.harness_trajectory_duration),
            description = stringResource(R.string.harness_trajectory_duration_description),
            checked = state.actualDuration,
            onCheckedChange = { onAction(NativeHarnessUiAction.SetActualDuration(it)) }
        )
        HarnessSwitchRow(
            title = stringResource(R.string.harness_trajectory_time),
            description = stringResource(R.string.harness_trajectory_time_description),
            checked = state.actualTime,
            onCheckedChange = { onAction(NativeHarnessUiAction.SetActualTime(it)) }
        )
        HarnessSwitchRow(
            title = stringResource(R.string.harness_trajectory_turns),
            description = stringResource(R.string.harness_trajectory_turns_description),
            checked = state.allTurnsCollapsed,
            onCheckedChange = { onAction(NativeHarnessUiAction.ToggleAllTrajectoryTurns) }
        )
        HarnessSwitchRow(
            title = stringResource(R.string.harness_trajectory_calls),
            description = stringResource(R.string.harness_trajectory_calls_description),
            checked = state.allAssistantsCollapsed,
            onCheckedChange = { onAction(NativeHarnessUiAction.ToggleAllTrajectoryCalls) }
        )
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onAction(NativeHarnessUiAction.UpdateTrajectorySearch(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_trajectory_search)) },
            singleLine = true
        )
        if (state.canLoadOlder) {
            OutlinedButton(
                onClick = { onAction(NativeHarnessUiAction.LoadOlderTrajectory) },
                enabled = !state.isLoadingOlder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (state.isLoadingOlder) R.string.harness_loading_older
                        else R.string.harness_load_older
                    )
                )
            }
        }
    }
}

@Composable
fun HarnessWebUiTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    originalWebUiContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                HarnessSectionHeading(title = state.originalWebUi.title, icon = Icons.Default.Web)
                Text(stringResource(R.string.harness_web_ui_description))
                HarnessValueLine(
                    stringResource(R.string.harness_web_ui_endpoint),
                    state.originalWebUi.endpointLabel ?: stringResource(R.string.harness_not_configured)
                )
                HarnessValueLine(
                    stringResource(R.string.harness_web_ui_authentication),
                    if (state.originalWebUi.authenticated) {
                        stringResource(R.string.harness_authenticated)
                    } else {
                        stringResource(R.string.harness_authentication_required)
                    }
                )
                OutlinedButton(
                    onClick = { onAction(NativeHarnessUiAction.OpenOriginalWebUi) },
                    enabled = state.originalWebUi.available,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Web, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.harness_open_web_ui))
                }
            }
        }
        if (originalWebUiContent != null && state.originalWebUi.available) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 420.dp, max = 720.dp),
                    shape = AppChromeDefaults.InnerCardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    originalWebUiContent()
                }
            }
        }
        item {
            Text(
                stringResource(R.string.harness_web_ui_preview_separation),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HarnessExtensionRow(
    title: String,
    summary: String,
    version: String? = null,
    enabled: Boolean,
    showSwitch: Boolean,
    switchEnabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                version?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (showSwitch) {
                Switch(checked = enabled, enabled = switchEnabled, onCheckedChange = onEnabledChange)
            }
        }
        actionLabel?.let {
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(it)
            }
        }
    }
}

@Composable
internal fun HarnessSectionHeading(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun HarnessValueLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun HarnessSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
