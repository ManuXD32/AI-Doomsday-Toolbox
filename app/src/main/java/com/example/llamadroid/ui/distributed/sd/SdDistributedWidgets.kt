@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.SdDistributedTemplateEntity
import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.ui.components.NetInterfacePrefChips
import com.example.llamadroid.sd.SdComponentRole
import com.example.llamadroid.sd.SdModelFamilySpec
import com.example.llamadroid.sd.SdComponentCandidate
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SdDistributedAutoRamScope
import com.example.llamadroid.service.SdDistributedPlacementMode
import com.example.llamadroid.service.SdDistributedPlacementPlan
import com.example.llamadroid.service.SdDistributedRuntimeConfig
import com.example.llamadroid.service.SdDistributedWorkerRuntime
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.settingsFromJson
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.distributed.MediaTopologyNode
import java.util.Date
import java.util.Locale

@Composable
internal fun TemplateManagerCard(
    title: String,
    templates: List<SdDistributedTemplateEntity>,
    templateNameDraft: String,
    onTemplateNameChange: (String) -> Unit,
    onSaveTemplate: () -> Unit,
    onLoadTemplate: (SdDistributedTemplateEntity) -> Unit,
    onDeleteTemplate: (SdDistributedTemplateEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RenderFarmPalette.coral.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.sd_dist_templates_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = templateNameDraft,
                onValueChange = onTemplateNameChange,
                label = { Text(stringResource(R.string.sd_dist_template_name)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = onSaveTemplate, enabled = templateNameDraft.isNotBlank()) {
                Icon(Icons.Default.Save, contentDescription = null)
            }
        }
        if (templates.isEmpty()) {
            Text(stringResource(R.string.sd_dist_no_templates), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            templates.forEach { template ->
                val templateSettings = remember(template.settingsJson) { settingsFromJson(template.settingsJson) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(template.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.sd_dist_template_summary, templateSettings.placementMode, templateSettings.steps, templateSettings.sampler), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onLoadTemplate(template) }) {
                        Text(stringResource(R.string.sd_dist_load_template), maxLines = 1)
                    }
                    IconButton(onClick = { onDeleteTemplate(template) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sd_dist_delete_template))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RamPlanPreview(plan: SdDistributedPlacementPlan) {
    Text(stringResource(R.string.sd_dist_auto_ram_plan), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.sd_dist_auto_ram_budget_preview), style = MaterialTheme.typography.labelMedium)
    CodeBlock(text = plan.maxVramSpec.ifBlank { "-" })
    Text(stringResource(R.string.sd_dist_current_plan), style = MaterialTheme.typography.labelMedium)
    CodeBlock(text = plan.backendSpec.ifBlank { "-" })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.assignments.forEach { assignment ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "${moduleLabel(assignment.module)} ${assignment.devices.joinToString("&")}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
internal fun MediaPipelinePreview(
    runtimeConfig: SdDistributedRuntimeConfig = SdDistributedRuntimeConfig(),
    workers: List<SdDistributedWorkerRuntime> = emptyList()
) {
    MediaTopologyNode(
        title = stringResource(R.string.worker_topology_master_name),
        status = stringResource(if (runtimeConfig.enabled) R.string.sd_dist_enabled else R.string.sd_dist_local_only),
        fields = listOf(
            stringResource(R.string.sd_dist_placement_mode) to placementModeLabel(runtimeConfig.placementMode),
            stringResource(R.string.sd_dist_rpc_servers) to runtimeConfig.rpcServers.ifBlank { stringResource(R.string.sd_dist_local_only) },
            stringResource(R.string.sd_dist_backend_spec) to runtimeConfig.backendSpec.ifBlank { stringResource(R.string.sd_dist_auto_fit) },
            stringResource(R.string.sd_dist_params_backend_spec) to runtimeConfig.paramsBackendSpec.ifBlank { stringResource(R.string.sd_dist_auto_fit) },
            stringResource(R.string.sd_dist_max_vram) to runtimeConfig.maxVramSpec.ifBlank { stringResource(R.string.sd_dist_budget_auto) },
            stringResource(R.string.media_topology_workers) to workers.size.toString()
        )
    )
}

@Composable
internal fun NumberSliderField(
    label: String,
    valueDraft: String,
    onValueDraftChange: (String) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = ""
) {
    val numeric = valueDraft.toFloatOrNull()?.coerceIn(range.start, range.endInclusive) ?: range.start
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = valueDraft,
                onValueChange = { onValueDraftChange(it.filter(Char::isDigit).take(6)) },
                suffix = { if (suffix.isNotBlank()) Text(suffix) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        Slider(
            value = numeric,
            onValueChange = { onValueDraftChange(it.toInt().toString()) },
            valueRange = range
        )
    }
}

@Composable
internal fun WorkerRow(
    worker: SdDistributedWorkerEntity,
    rpcName: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** Reachability of an enabled Helper; null while unknown or when the Helper is off. */
    online: Boolean? = null,
    /** Saves the Auto/VPN/LAN preference; null hides the chips (also hidden while the Helper has one address). */
    onPreferredInterface: ((String) -> Unit)? = null
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = worker.isEnabled, onCheckedChange = { onToggle() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(worker.deviceName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${worker.host}:${worker.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (rpcName.isNotBlank() && rpcName != "-") {
                    AssistChip(onClick = {}, label = { Text(rpcName, maxLines = 1) })
                }
            }
            if (onPreferredInterface != null && !worker.altHost.isNullOrBlank()) {
                NetInterfacePrefChips(
                    pref = worker.preferredInterface,
                    activeHost = worker.host,
                    onChange = onPreferredInterface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.sd_dist_worker_budget_line, worker.ramMB, worker.threads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (online != null) {
                        Text(
                            stringResource(if (online) R.string.sd_r8_w07_helper_online else R.string.sd_r8_w07_helper_offline),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.sd_r8_w07_edit_helper))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sd_r8_w07_remove_helper))
                }
            }
        }
    }
}

@Composable
internal fun PipelineWorkerLane(worker: SdDistributedWorkerRuntime) {
    val assignments = worker.plannedAssignments.map { assignment ->
        if (assignment.isSplit) stringResource(R.string.sd_dist_planned_split_assignment, moduleLabel(assignment.module), assignment.estimatedLayerShare)
        else stringResource(R.string.sd_dist_planned_whole_assignment, moduleLabel(assignment.module))
    }
    MediaTopologyNode(
        title = worker.deviceName,
        status = stringResource(if (worker.isConnected) R.string.sd_dist_live_online else R.string.sd_dist_live_offline),
        fields = listOf(
            stringResource(R.string.worker_topology_endpoint) to "${worker.host}:${worker.port}",
            stringResource(R.string.worker_topology_identifier) to worker.rpcName.ifBlank { "—" },
            stringResource(R.string.media_topology_capacity) to stringResource(R.string.sd_dist_worker_budget_line, worker.ramMB, worker.threads),
            stringResource(R.string.media_topology_estimated_plan) to assignments.joinToString("\n").ifBlank { stringResource(R.string.sd_dist_unassigned) },
            stringResource(R.string.media_topology_actual_modules) to worker.assignedModules.joinToString(", ") { it }.ifBlank { stringResource(R.string.media_topology_not_reported) },
            stringResource(R.string.media_topology_load) to stringResource(R.string.media_topology_not_reported),
            stringResource(R.string.worker_topology_last_confirmed, "") to
                if (worker.lastSeenAt > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(worker.lastSeenAt)) else "—"
        )
    )
}

@Composable
internal fun RuntimeSummary(config: SdDistributedRuntimeConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryLine(stringResource(R.string.sd_dist_enable_distributed), if (config.enabled) stringResource(R.string.sd_dist_enabled) else stringResource(R.string.sd_dist_disabled))
        SummaryLine(stringResource(R.string.sd_dist_rpc_servers), config.rpcServers.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_placement_mode), placementModeLabel(config.placementMode))
        SummaryLine(stringResource(R.string.sd_dist_backend_spec), config.backendSpec.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_params_backend_spec), config.paramsBackendSpec.ifBlank { "-" })
        SummaryLine(stringResource(R.string.sd_dist_split_mode), config.splitMode.cliName)
        SummaryLine(stringResource(R.string.sd_dist_max_vram), config.maxVramSpec.ifBlank { "-" })
    }
}

@Composable
internal fun placementModeLabel(mode: SdDistributedPlacementMode): String = when (mode) {
    SdDistributedPlacementMode.AUTO_RAM -> stringResource(R.string.sd_dist_placement_auto_ram)
    SdDistributedPlacementMode.AUTO_FIT -> stringResource(R.string.sd_dist_placement_auto_fit)
    SdDistributedPlacementMode.COMPONENTS -> stringResource(R.string.sd_dist_placement_components)
    SdDistributedPlacementMode.MANUAL -> stringResource(R.string.sd_dist_placement_manual)
}

@Composable
internal fun moduleLabel(module: String): String = when (module) {
    "diffusion" -> stringResource(R.string.sd_dist_module_diffusion)
    "te" -> stringResource(R.string.sd_dist_module_text_encoder)
    "vae" -> stringResource(R.string.sd_dist_module_vae)
    "upscaler" -> stringResource(R.string.sd_dist_module_upscaler)
    "controlnet" -> stringResource(R.string.sd_dist_module_controlnet)
    else -> module
}

@Composable
internal fun SummaryLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun WarningBand(text: String) {
    AppSectionCard(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f), tonalAccent = Color.Transparent) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun CodeBlock(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f), RoundedCornerShape(8.dp)).padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun LabeledTextField(
    labelRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    placeholderRes: Int,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        placeholder = { Text(stringResource(placeholderRes)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        minLines = 1
    )
}

@Composable
internal fun TwoColumnFields(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) { first() }
        Column(modifier = Modifier.weight(1f)) { second() }
    }
}

@Composable
internal fun AutoRamScopePicker(
    value: SdDistributedAutoRamScope,
    onChange: (SdDistributedAutoRamScope) -> Unit
) {
    Text(stringResource(R.string.sd_dist_auto_ram_scope), style = MaterialTheme.typography.labelLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SdDistributedAutoRamScope.entries.forEach { scope ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .clickable { onChange(scope) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = value == scope,
                    onClick = { onChange(scope) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(autoRamScopeLabel(scope), fontWeight = FontWeight.SemiBold)
                    Text(
                        autoRamScopeDescription(scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun autoRamScopeLabel(scope: SdDistributedAutoRamScope): String = when (scope) {
    SdDistributedAutoRamScope.DIFFUSION_ONLY -> stringResource(R.string.sd_dist_auto_ram_scope_diffusion)
    SdDistributedAutoRamScope.FULL_PIPELINE -> stringResource(R.string.sd_dist_auto_ram_scope_full)
}

@Composable
internal fun autoRamScopeDescription(scope: SdDistributedAutoRamScope): String = when (scope) {
    SdDistributedAutoRamScope.DIFFUSION_ONLY -> stringResource(R.string.sd_dist_auto_ram_scope_diffusion_desc)
    SdDistributedAutoRamScope.FULL_PIPELINE -> stringResource(R.string.sd_dist_auto_ram_scope_full_desc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacementModeRow(value: SdDistributedPlacementMode, onChange: (SdDistributedPlacementMode) -> Unit) {
    Text(stringResource(R.string.sd_dist_placement_mode), style = MaterialTheme.typography.labelLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SdDistributedPlacementMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .clickable { onChange(mode) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                selected = value == mode,
                    onClick = { onChange(mode) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(placementModeLabel(mode), fontWeight = FontWeight.SemiBold)
                    Text(placementModeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun modulesForWorker(
    worker: SdDistributedWorkerEntity,
    runtimeConfig: SdDistributedRuntimeConfig
): List<String> {
    val name = worker.backendDevice.ifBlank { worker.deviceName }
    if (runtimeConfig.backendSpec.isBlank()) return emptyList()
    return runtimeConfig.backendSpec.split(",")
        .mapNotNull { assignment ->
            val parts = assignment.split("=")
            if (parts.size != 2) return@mapNotNull null
            val devices = parts[1].split("&")
            parts[0].takeIf { devices.any { device -> device.contains(name, ignoreCase = true) || device.contains(worker.host) } }
        }
}

internal fun sdDistributedComponentRoleLabelRes(role: SdComponentRole): Int = when (role) {
    SdComponentRole.MAIN_MODEL -> R.string.imagegen_component_main_model
    SdComponentRole.VAE -> R.string.imagegen_component_vae
    SdComponentRole.TAE -> R.string.imagegen_component_tae
    SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
    SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
    SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
    SdComponentRole.LLM -> R.string.imagegen_component_llm
    SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
    SdComponentRole.CONTROLNET -> R.string.imagegen_component_controlnet
    SdComponentRole.LORA -> R.string.imagegen_component_lora
    SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
    SdComponentRole.CLIP_VISION -> R.string.sd_type_clip_vision
    SdComponentRole.IP_ADAPTER -> R.string.sd_type_ip_adapter
    SdComponentRole.UPSCALER -> R.string.imagegen_component_upscaler
}

internal fun sdDistributedComponentRoleLabel(context: Context, role: SdComponentRole): String =
    context.getString(sdDistributedComponentRoleLabelRes(role))

@Composable
internal fun sdDistributedComponentRoleLabel(role: SdComponentRole): String =
    stringResource(sdDistributedComponentRoleLabelRes(role))

internal fun String.toSdPlacementMode(): SdDistributedPlacementMode =
    runCatching { SdDistributedPlacementMode.valueOf(this) }.getOrDefault(SdDistributedPlacementMode.AUTO_RAM)

internal fun String.toSdMasterImageMode(): SDMode = when (uppercase(Locale.US)) {
    "IMG2IMG" -> SDMode.IMG2IMG
    "UPSCALE" -> SDMode.UPSCALE
    else -> SDMode.TXT2IMG
}

internal fun String.toSdMasterVideoMode(): VideoGenerationMode = when (uppercase(Locale.US)) {
    "IMG2VID" -> VideoGenerationMode.IMG2VID
    else -> VideoGenerationMode.TXT2VID
}

internal fun parseSdDistributedDimension(value: String): Int? =
    value.toIntOrNull()?.takeIf { it >= 64 }

@Composable
internal fun placementModeDescription(mode: SdDistributedPlacementMode): String = when (mode) {
    SdDistributedPlacementMode.AUTO_RAM -> stringResource(R.string.sd_dist_placement_auto_ram_desc)
    SdDistributedPlacementMode.AUTO_FIT -> stringResource(R.string.sd_dist_placement_auto_fit_desc)
    SdDistributedPlacementMode.COMPONENTS -> stringResource(R.string.sd_dist_placement_components_desc)
    SdDistributedPlacementMode.MANUAL -> stringResource(R.string.sd_dist_placement_manual_desc)
}

internal enum class SdDistributedTemplateWorkflow { IMAGE, VIDEO }

/** Installed candidates (MATCH first, nothing hidden) and current picks for the image component pickers. */
internal data class SdDistributedImageComponentChoices(
    val familyLabel: String,
    val spec: SdModelFamilySpec?,
    val roles: List<SdComponentRole>,
    val requiredRoles: Set<SdComponentRole>,
    val embeddedRoles: Set<SdComponentRole>,
    val candidates: Map<SdComponentRole, List<SdComponentCandidate>>,
    val selectedPaths: Map<SdComponentRole, String>,
    val controlNetEnabled: Boolean,
    val loraEnabled: Boolean,
    val loraApplyMode: String,
    val mainFamily: String? = null,
    val mainVariant: String? = null
)

/** Compact label + value chip in the render-farm dark look (the generated-media gallery counters). */
@Composable
internal fun FarmStatusChip(label: String, value: String, color: Color) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = RenderFarmPalette.graphiteSoft)
    ) {
        Column(modifier = Modifier.widthIn(min = 150.dp, max = 260.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
