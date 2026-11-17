@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.SdDistributedMasterSettingsEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.service.SdDiscoveredWorker
import com.example.llamadroid.service.SdDistributedAutoRamScope
import com.example.llamadroid.service.SdDistributedModules
import com.example.llamadroid.service.SdDistributedPlacementMode
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.distributed.common.DistributedNoHelpersState

/** Save-a-Helper callback: (editingId, host, port, name, ramMb, threads, backend, onDone(ok)). */
internal typealias SdSaveHelper = (Long?, String, String, String, String, String, String, (Boolean) -> Unit) -> Unit

/**
 * Step 2 of the main-device flow: choose which devices help. `enabled` is derived from the
 * Helper list, so there is no separate on/off switch here.
 */
internal fun LazyListScope.sdMasterDevicesStepItems(
    state: SdMasterUiState,
    vm: SdMasterViewModel,
    env: SdMasterEnv
) {
    val runtime = buildSdMasterRuntime(state.settings, state.workers, video = state.mode == SdMasterMode.VIDEO)
    item(key = "devices_summary") {
        SdMasterSplitSummaryCard(runtime)
    }
    item(key = "devices_nearby") {
        SdMasterNearbyCard(state.workers, vm::addDiscoveredWorker)
    }
    item(key = "devices_helpers") {
        val active by SdDistributedService.activeWorkers.collectAsState()
        SdMasterHelpersCard(
            workers = state.workers,
            online = { worker ->
                if (!worker.isEnabled) null
                else active.firstOrNull { it.host == worker.host && it.port == worker.port }?.isConnected
            },
            onToggle = vm::toggleWorker,
            onDelete = { vm.deleteWorker(it.id) },
            onSave = { id, host, port, name, ram, threads, backend, done ->
                vm.saveWorker(id, host, port, name, ram, threads, backend, done)
            },
            onOpenHelperGuide = env.onOpenHelperGuide
        )
    }
    item(key = "devices_transfer") {
        SdMasterTransferCompressionCard(state.settings, vm::update)
    }
    item(key = "devices_this_phone") {
        SdMasterThisPhoneCard(state.settings, vm::update)
    }
    item(key = "devices_advanced") {
        SdMasterPlacementAdvanced(state.settings, runtime, vm::update)
    }
}

// ---- Split summary ----

/** Plain-language split: "Runs on this phone only" or the memory each enabled device shares. */
@Composable
internal fun SdMasterSplitSummaryCard(runtime: SdMasterRuntime) {
    val remote = runtime.planningWorkers.filterNot { it.isLocalMaster }
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_split_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (remote.isEmpty()) {
            Text(
                text = stringResource(R.string.sd_r8_w07_split_local_only),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = pluralStringResource(R.plurals.sd_r8_w07_helpers_on, remote.size, remote.size),
                style = MaterialTheme.typography.bodyMedium
            )
            val thisPhone = stringResource(R.string.sd_r8_w07_this_phone)
            val parts = runtime.planningWorkers.map { worker ->
                stringResource(
                    R.string.sd_r8_w07_split_part,
                    stringResource(R.string.sd_r8_w07_ram_gb, worker.ramMB / 1024f),
                    if (worker.isLocalMaster) thisPhone else worker.displayName
                )
            }
            Text(
                text = stringResource(R.string.sd_r8_w07_split_budget, parts.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- Helpers ----

@Composable
private fun SdMasterNearbyCard(
    saved: List<SdDistributedWorkerEntity>,
    onAdd: (SdDiscoveredWorker) -> Unit
) {
    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_nearby_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SdDiscoveredWorkersSection(saved = saved, onAdd = onAdd)
    }
}

/** Saved Helpers with toggle/edit/delete, a manual-add disclosure and the setup guide link. */
@Composable
internal fun SdMasterHelpersCard(
    workers: List<SdDistributedWorkerEntity>,
    online: (SdDistributedWorkerEntity) -> Boolean?,
    onToggle: (SdDistributedWorkerEntity) -> Unit,
    onDelete: (SdDistributedWorkerEntity) -> Unit,
    onSave: SdSaveHelper,
    onOpenHelperGuide: () -> Unit
) {
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(SdDistributedService.RPC_DEFAULT_PORT.toString()) }
    var name by rememberSaveable { mutableStateOf("") }
    var ram by rememberSaveable { mutableStateOf("4096") }
    var threads by rememberSaveable { mutableStateOf("4") }
    var backend by rememberSaveable { mutableStateOf("") }

    fun resetForm() {
        showForm = false
        editingId = null
        host = ""
        port = SdDistributedService.RPC_DEFAULT_PORT.toString()
        name = ""
        ram = "4096"
        threads = "4"
        backend = ""
    }

    AppSectionCard {
        Text(
            text = stringResource(R.string.sd_r8_w07_helpers_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.sd_r8_w07_helpers_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (workers.isEmpty()) {
            DistributedNoHelpersState(
                onAdd = { showForm = true },
                title = stringResource(R.string.sd_ui2_no_helpers_title),
                message = stringResource(R.string.sd_ui2_no_helpers_message)
            )
        } else {
            workers.forEach { worker ->
                WorkerRow(
                    worker = worker,
                    rpcName = "",
                    online = online(worker),
                    onToggle = { onToggle(worker) },
                    onEdit = {
                        editingId = worker.id
                        host = worker.host
                        port = worker.port.toString()
                        name = worker.deviceName
                        ram = worker.ramMB.toString()
                        threads = worker.threads.toString()
                        backend = worker.backendDevice
                        showForm = true
                    },
                    onDelete = { onDelete(worker) }
                )
            }
            if (!showForm) {
                OutlinedButton(
                    onClick = { showForm = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.sd_r8_w07_add_manually), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (showForm) {
            Text(
                text = stringResource(
                    if (editingId == null) R.string.sd_r8_w07_add_manually else R.string.sd_r8_w07_edit_helper
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.sd_r8_w07_form_host), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.sd_r8_w07_form_port), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = threads,
                    onValueChange = { threads = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.sd_r8_w07_form_threads), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sd_r8_w07_form_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = ram,
                    onValueChange = { ram = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.sd_r8_w07_form_ram), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            BackendDevicePicker(
                label = stringResource(R.string.sd_dist_backend_device_optional),
                selected = backend,
                onSelected = { backend = it },
                includeRemoteChoices = false
            )
            ResponsiveActionGroup {
                Button(
                    onClick = {
                        if (host.isNotBlank()) {
                            onSave(editingId, host, port, name, ram, threads, backend) { ok -> if (ok) resetForm() }
                        }
                    },
                    enabled = host.isNotBlank()
                ) {
                    Text(
                        stringResource(if (editingId == null) R.string.sd_r8_w07_form_add else R.string.sd_r8_w07_form_save),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { resetForm() }) {
                    Text(stringResource(R.string.sd_r8_w07_form_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TextButton(onClick = onOpenHelperGuide, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sd_r8_w07_guide_link), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Transfer compression ----

/** BF16 split-boundary transfers: switch with an always-visible implication and an expandable detail list. */
@Composable
private fun SdMasterTransferCompressionCard(
    settings: SdDistributedMasterSettingsEntity,
    onUpdate: SdMasterSettingsUpdate
) {
    AppSectionCard {
        ToggleRow(
            title = stringResource(R.string.sd_transfer_title),
            subtitle = stringResource(R.string.sd_transfer_desc),
            checked = settings.compressLayerTransfers,
            onCheckedChange = { checked -> onUpdate { it.copy(compressLayerTransfers = checked) } }
        )
        AppAdvancedSection(title = stringResource(R.string.sd_transfer_details_title)) {
            SdMasterHelpText(R.string.sd_transfer_detail_bandwidth)
            SdMasterHelpText(R.string.sd_transfer_detail_precision)
            SdMasterHelpText(R.string.sd_transfer_detail_scope)
            SdMasterHelpText(R.string.sd_transfer_detail_version)
        }
    }
}

// ---- This phone ----

@Composable
private fun SdMasterThisPhoneCard(settings: SdDistributedMasterSettingsEntity, onUpdate: SdMasterSettingsUpdate) {
    AppSectionCard {
        ToggleRow(
            title = stringResource(R.string.sd_r8_w07_this_phone_title),
            subtitle = stringResource(R.string.sd_r8_w07_this_phone_desc),
            checked = settings.masterContributes,
            onCheckedChange = { checked -> onUpdate { it.copy(masterContributes = checked) } }
        )
        if (settings.masterContributes) {
            var ramDraft by remember(settings.masterRamMB) { mutableStateOf(settings.masterRamMB.toString()) }
            NumberSliderField(
                label = stringResource(R.string.sd_dist_ram_to_share),
                valueDraft = ramDraft,
                onValueDraftChange = { draft ->
                    ramDraft = draft
                    draft.toIntOrNull()?.takeIf { it > 0 }?.let { value -> onUpdate { it.copy(masterRamMB = value) } }
                },
                range = 512f..65536f,
                suffix = stringResource(R.string.sd_dist_mb_suffix)
            )
            var threadsDraft by remember(settings.masterThreads) { mutableStateOf(settings.masterThreads.toString()) }
            NumberSliderField(
                label = stringResource(R.string.sd_dist_threads_to_share),
                valueDraft = threadsDraft,
                onValueDraftChange = { draft ->
                    threadsDraft = draft
                    draft.toIntOrNull()?.takeIf { it > 0 }?.let { value -> onUpdate { it.copy(masterThreads = value) } }
                },
                range = 1f..32f
            )
        }
    }
}

// ---- Advanced placement ----

@Composable
private fun SdMasterPlacementAdvanced(
    settings: SdDistributedMasterSettingsEntity,
    runtime: SdMasterRuntime,
    onUpdate: SdMasterSettingsUpdate
) {
    val placement = settings.placementMode.toSdPlacementMode()
    AppAdvancedSection(title = stringResource(R.string.sd_r8_w07_advanced_title)) {
        PlacementModeRow(placement) { mode -> onUpdate { it.copy(placementMode = mode.name) } }
        when (placement) {
            SdDistributedPlacementMode.AUTO_RAM -> {
                AutoRamScopePicker(SdDistributedAutoRamScope.fromStoredValue(settings.autoRamScope)) { scope ->
                    onUpdate { it.copy(autoRamScope = scope.name) }
                }
                RamPlanPreview(plan = runtime.plan)
                SdMasterHelpText(R.string.sd_r8_w07_auto_ram_help)
            }
            SdDistributedPlacementMode.AUTO_FIT -> SdMasterHelpText(R.string.sd_r8_w07_auto_fit_help)
            SdDistributedPlacementMode.COMPONENTS -> {
                LabeledTextField(
                    R.string.sd_dist_backend_spec,
                    settings.backendSpec,
                    { value -> onUpdate { it.copy(backendSpec = value) } },
                    R.string.sd_dist_backend_spec_hint
                )
                LabeledTextField(
                    R.string.sd_dist_params_backend_spec,
                    settings.paramsBackendSpec,
                    { value -> onUpdate { it.copy(paramsBackendSpec = value) } },
                    R.string.sd_dist_params_backend_hint
                )
            }
            SdDistributedPlacementMode.MANUAL -> SdMasterHelpText(R.string.sd_r8_w07_manual_help)
        }
        ToggleRow(
            title = stringResource(R.string.sd_r8_w07_max_vram_title),
            subtitle = stringResource(R.string.sd_r8_w07_max_vram_desc),
            checked = settings.maxVramEnabled,
            onCheckedChange = { checked -> onUpdate { it.copy(maxVramEnabled = checked) } }
        )
        if (settings.maxVramEnabled) {
            WarningBand(text = stringResource(R.string.sd_r8_w07_max_vram_warning))
            LabeledTextField(
                R.string.sd_dist_max_vram,
                settings.maxVramSpec,
                { value -> onUpdate { it.copy(maxVramSpec = value) } },
                R.string.sd_dist_max_vram_hint
            )
        }
        if (settings.masterContributes) SdMasterThisPhoneDetails(settings, onUpdate)
    }
}

@Composable
private fun SdMasterHelpText(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SdMasterThisPhoneDetails(settings: SdDistributedMasterSettingsEntity, onUpdate: SdMasterSettingsUpdate) {
    Text(
        text = stringResource(R.string.sd_r8_w07_this_phone_details),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    OutlinedTextField(
        value = settings.masterDisplayName,
        onValueChange = { value -> onUpdate { it.copy(masterDisplayName = value) } },
        label = { Text(stringResource(R.string.sd_r8_w07_phone_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    BackendDevicePicker(
        label = stringResource(R.string.sd_dist_backend_device_optional),
        selected = settings.masterBackendDevice,
        onSelected = { value -> onUpdate { it.copy(masterBackendDevice = value) } },
        includeRemoteChoices = false
    )
    Text(
        text = stringResource(R.string.sd_dist_master_allowed_modules),
        style = MaterialTheme.typography.labelLarge
    )
    val allowed = SdDistributedModules.normalizeCsv(settings.masterAllowedModules)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SdDistributedModules.defaultModuleSet.forEach { module ->
            val selected = module in allowed
            FilterChip(
                selected = selected,
                onClick = {
                    val next = if (selected) allowed - module else allowed + module
                    onUpdate {
                        it.copy(
                            masterAllowedModules = SdDistributedModules.toCsv(
                                next.ifEmpty { setOf(SdDistributedModules.DIFFUSION) }
                            )
                        )
                    }
                },
                label = { Text(moduleLabel(module), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
    LabeledTextField(
        R.string.sd_dist_master_diffusion_share,
        settings.masterDiffusionSharePercent,
        { value -> onUpdate { it.copy(masterDiffusionSharePercent = value.filter(Char::isDigit).take(2)) } },
        R.string.sd_dist_master_diffusion_share_hint,
        KeyboardType.Number
    )
    SdMasterHelpText(R.string.sd_dist_master_diffusion_share_desc)
}
