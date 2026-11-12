@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.llamadroid.ui.distributed.master

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle as collectAsState
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandEntity
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedDeviceKind
import com.example.llamadroid.service.DistributedDeviceRef
import com.example.llamadroid.service.DistributedKvPlacement
import com.example.llamadroid.service.DistributedKvPlacementMode
import com.example.llamadroid.service.DistributedLaunchResolution
import com.example.llamadroid.service.DistributedLayerAllocation
import com.example.llamadroid.service.DistributedLayerPlanning
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedPerfSettings
import com.example.llamadroid.service.DistributedPerfText
import com.example.llamadroid.service.DistributedPlanWarningType
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.DistributedSpeculativePlacement
import com.example.llamadroid.service.DistributedWorkerLaunchSpec
import com.example.llamadroid.service.DraftModelPlacement
import com.example.llamadroid.service.DraftModelPlacementMode
import com.example.llamadroid.service.LlamaModelMetadataProbe
import com.example.llamadroid.service.LlamaModelProbeNoLayersException
import com.example.llamadroid.service.LlamaModelProbeResult
import com.example.llamadroid.service.LlamaModelProbeTimeoutException
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.MainModelPlacement
import com.example.llamadroid.service.MainModelPlacementMode
import com.example.llamadroid.service.MmprojPlacement
import com.example.llamadroid.service.ProcessController
import com.example.llamadroid.service.RemoteMasterServer
import com.example.llamadroid.service.ResolvedDistributedLaunch
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.service.stableKey
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppChoiceRow
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.distributed.common.DistributedHelperListItem
import com.example.llamadroid.ui.distributed.common.DistributedHowItWorksStrip
import com.example.llamadroid.ui.distributed.common.DistributedNoHelpersState
import com.example.llamadroid.ui.distributed.common.DistributedSearchingState
import com.example.llamadroid.ui.distributed.common.DistributedStatusPanel
import com.example.llamadroid.ui.distributed.common.DistributedTechnicalLogSection
import com.example.llamadroid.ui.distributed.common.DistributedWarningRow
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Small helper: one-line plain explanation at the top of an advanced group. */
@Composable
internal fun AdvancedHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Step 4: every expert control, grouped in collapsed sections. Nothing is removed from the old screen. */
@Composable
internal fun MasterAdvancedSection(s: MasterScreenState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionTitle(
            title = stringResource(R.string.dist_ui_step_advanced),
            supporting = stringResource(R.string.dist_ui_step_advanced_desc)
        )
        MasterPlanningGroup(s)
        MasterFitGroup(s)
        MasterSpeculativeGroup(s)
        MasterRuntimeGroup(s)
        MasterKvGroup(s)
        MasterNetworkGroup(s)
        MasterCommandGroup(s)
        MasterLayerMemoryGroup(s)
    }
}

@Composable
private fun MasterPlanningGroup(s: MasterScreenState) {
    AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_planning_title)) {
        AdvancedHint(stringResource(R.string.dist_ui_adv_planning_desc))
        listOf(
            Triple(DistributedLayerPlanning.OPTIMAL_NETWORK, R.string.dist_r3_plan_optimal, R.string.dist_r3_plan_optimal_desc),
            Triple(DistributedLayerPlanning.RAM_PROPORTIONAL, R.string.dist_r3_plan_ram, R.string.dist_r3_plan_ram_desc),
            Triple(DistributedLayerPlanning.PERCENT, R.string.dist_r3_plan_percent, R.string.dist_r3_plan_percent_desc),
            Triple(DistributedLayerPlanning.LAYERS, R.string.dist_r3_plan_layers, R.string.dist_r3_plan_layers_desc)
        ).forEach { (mode, title, desc) ->
            AppChoiceRow(
                title = stringResource(title),
                supportingText = stringResource(desc),
                selected = s.layerPlanning == mode,
                onClick = { s.updateLayerPlanning(mode) }
            )
        }
        if (s.layerPlanning.isManual) ManualSplitInputs(s)
    }
}

@Composable
private fun MasterFitGroup(s: MasterScreenState) {
    val fitEnabled by DistributedService.masterFitEnabled.collectAsState()
    val fitTargetMiB by DistributedService.masterFitTargetMiB.collectAsState()
    val nglArgument by DistributedService.masterNglArgument.collectAsState()
    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_fit_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_fit_desc))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dist_fit_enable), modifier = Modifier.weight(1f))
                Switch(
                    checked = fitEnabled,
                    onCheckedChange = { DistributedService.setMasterFitEnabled(it) },
                    enabled = !isServerRunning
                )
            }
            if (deviceSlots.isEmpty()) {
                OutlinedTextField(
                    value = fitTargetMiB,
                    onValueChange = { DistributedService.setMasterFitTargetMiB(it.filter { ch -> ch.isDigit() || ch == ',' }) },
                    label = { Text(stringResource(R.string.dist_fit_target)) },
                    placeholder = { Text(stringResource(R.string.dist_fit_target_hint)) },
                    supportingText = { Text(stringResource(R.string.dist_fit_target_hint)) },
                    isError = fitValidationError != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            } else {
                val rawTargets = fitTargetMiB.split(',').map(String::trim)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    deviceSlots.forEachIndexed { index, slot ->
                        val displayedTarget = rawTargets.getOrNull(index)
                            ?: rawTargets.singleOrNull()
                            ?: ""
                        OutlinedTextField(
                            value = displayedTarget,
                            onValueChange = { newValue ->
                                val sanitized = newValue.filter(Char::isDigit)
                                val values = if (rawTargets.size == deviceSlots.size) {
                                    rawTargets.toMutableList()
                                } else {
                                    MutableList(deviceSlots.size) { rawTargets.singleOrNull().orEmpty() }
                                }
                                values[index] = sanitized
                                DistributedService.setMasterFitTargetMiB(values.joinToString(","))
                            },
                            label = { Text("${stringResource(R.string.dist_fit_target)} · $slot") },
                            supportingText = { Text(stringResource(R.string.dist_fit_target_hint)) },
                            isError = fitValidationError != null,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = nglArgument,
                onValueChange = { DistributedService.setMasterNglArgument(it) },
                label = { Text(stringResource(R.string.dist_ngl_argument)) },
                supportingText = { Text(stringResource(R.string.dist_ngl_argument_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isServerRunning
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.dist_fit_device_order,
                    deviceSlots.ifEmpty { listOf("RPC0…") }.joinToString(", ")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            fitValidationError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MasterSpeculativeGroup(s: MasterScreenState) {
    val speculativeEnabled by DistributedService.masterSpeculativeEnabled.collectAsState()
    val draftMax by DistributedService.masterDraftMax.collectAsState()
    val draftMin by DistributedService.masterDraftMin.collectAsState()
    val draftPMin by DistributedService.masterDraftPMin.collectAsState()
    val draftThreads by DistributedService.masterDraftThreads.collectAsState()
    val draftThreadsBatch by DistributedService.masterDraftThreadsBatch.collectAsState()
    val speculativeMode by DistributedService.masterSpeculativeMode.collectAsState()
    val speculativePlacement by DistributedService.masterSpeculativePlacement.collectAsState()
    val speculativeWorkerIndex by DistributedService.masterSpeculativeWorkerIndex.collectAsState()
    val draftGpuLayers by DistributedService.masterDraftGpuLayers.collectAsState()
    val draftDeviceMode by DistributedService.masterDraftDeviceMode.collectAsState()
    val mtpUseDraftModel by DistributedService.masterMtpUseDraftModel.collectAsState()
    val mtpDraftMax by DistributedService.masterMtpDraftMax.collectAsState()
    val mtpDraftMin by DistributedService.masterMtpDraftMin.collectAsState()
    val mtpDraftPMin by DistributedService.masterMtpDraftPMin.collectAsState()
    val ngramModNMatch by DistributedService.masterNgramModNMatch.collectAsState()
    val ngramModNMin by DistributedService.masterNgramModNMin.collectAsState()
    val ngramModNMax by DistributedService.masterNgramModNMax.collectAsState()
    val ngramSimpleSizeN by DistributedService.masterNgramSimpleSizeN.collectAsState()
    val ngramSimpleSizeM by DistributedService.masterNgramSimpleSizeM.collectAsState()
    val ngramSimpleMinHits by DistributedService.masterNgramSimpleMinHits.collectAsState()
    val ngramMapKSizeN by DistributedService.masterNgramMapKSizeN.collectAsState()
    val ngramMapKSizeM by DistributedService.masterNgramMapKSizeM.collectAsState()
    val ngramMapKMinHits by DistributedService.masterNgramMapKMinHits.collectAsState()
    val ngramMapK4VSizeN by DistributedService.masterNgramMapK4VSizeN.collectAsState()
    val ngramMapK4VSizeM by DistributedService.masterNgramMapK4VSizeM.collectAsState()
    val ngramMapK4VMinHits by DistributedService.masterNgramMapK4VMinHits.collectAsState()
    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_speculative_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_speculative_desc))
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dist_speculative_enable), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.dist_speculative_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = speculativeEnabled,
                    onCheckedChange = DistributedService::setMasterSpeculativeEnabled,
                    enabled = !isServerRunning
                )
            }
            if (speculativeEnabled) {
            Spacer(modifier = Modifier.height(12.dp))
            val modeLabel = when (speculativeMode) {
                LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_mode_simple)
                LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_mode_ngram_cache)
            }
            ExposedDropdownMenuBox(
                expanded = speculativeModeMenuExpanded,
                onExpandedChange = { speculativeModeMenuExpanded = !speculativeModeMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = modeLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.dist_speculative_mode_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(speculativeModeMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = speculativeModeMenuExpanded,
                    onDismissRequest = { speculativeModeMenuExpanded = false }
                ) {
                    listOf(
                        LlamaSpeculativeMode.DRAFT_SIMPLE,
                        LlamaSpeculativeMode.DRAFT_MTP,
                        LlamaSpeculativeMode.DRAFT_DFLASH,
                        LlamaSpeculativeMode.DRAFT_DSPARK,
                        LlamaSpeculativeMode.NGRAM_MOD,
                        LlamaSpeculativeMode.NGRAM_SIMPLE,
                        LlamaSpeculativeMode.NGRAM_MAP_K,
                        LlamaSpeculativeMode.NGRAM_MAP_K4V,
                        LlamaSpeculativeMode.NGRAM_CACHE
                    ).forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (mode) {
                                        LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                                        LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                                        LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                                        LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_mode_simple)
                                        LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                                        LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                                        LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                                        LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                                        LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_mode_ngram_cache)
                                    }
                                )
                            },
                            onClick = {
                                DistributedService.setMasterSpeculativeMode(mode)
                                speculativeModeMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = draftDeviceMenuExpanded,
                onExpandedChange = { draftDeviceMenuExpanded = !draftDeviceMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                val draftDeviceLabel = when (draftDeviceMode) {
                    SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR -> stringResource(R.string.llm_draft_device_accelerator)
                    SettingsRepository.LLAMA_DRAFT_DEVICE_CPU -> stringResource(R.string.llm_draft_device_cpu)
                    else -> stringResource(R.string.llm_backend_mode_auto)
                }
                OutlinedTextField(
                    value = draftDeviceLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.llm_draft_device_mode)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(draftDeviceMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    enabled = speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                        speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED,
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = draftDeviceMenuExpanded &&
                        (speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                            speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED),
                    onDismissRequest = { draftDeviceMenuExpanded = false }
                ) {
                    listOf(
                        SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO to stringResource(R.string.llm_backend_mode_auto),
                        SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR to stringResource(R.string.llm_draft_device_accelerator),
                        SettingsRepository.LLAMA_DRAFT_DEVICE_CPU to stringResource(R.string.llm_draft_device_cpu)
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                DistributedService.setMasterDraftDeviceMode(value)
                                draftDeviceMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Text(
                text = if (speculativePlacement == DistributedSpeculativePlacement.LOCAL ||
                    speculativePlacement == DistributedSpeculativePlacement.MASTER_DEDICATED
                ) {
                    stringResource(R.string.llm_draft_device_hint)
                } else {
                    stringResource(R.string.dist_speculative_worker_device_hint)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.dist_speculative_placement), fontWeight = FontWeight.Medium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    DistributedSpeculativePlacement.LOCAL to stringResource(R.string.dist_speculative_placement_local),
                    DistributedSpeculativePlacement.MASTER_DEDICATED to stringResource(R.string.dist_speculative_placement_master_dedicated),
                    DistributedSpeculativePlacement.WORKER_SHARED to stringResource(R.string.dist_speculative_placement_shared),
                    DistributedSpeculativePlacement.WORKER_DEDICATED to stringResource(R.string.dist_speculative_placement_dedicated)
                ).filter { (placement, _) ->
                    placement != DistributedSpeculativePlacement.MASTER_DEDICATED ||
                        speculativeMode == LlamaSpeculativeMode.DRAFT_MTP
                }.forEach { (placement, label) ->
                    FilterChip(
                        selected = speculativePlacement == placement,
                        onClick = {
                            DistributedService.setMasterSpeculativePlacement(placement)
                            if (placement == DistributedSpeculativePlacement.LOCAL) {
                                DistributedService.setMasterSpeculativeWorkerIndex(null)
                            }
                        },
                        label = { Text(label) },
                        enabled = placement == DistributedSpeculativePlacement.LOCAL ||
                            placement == DistributedSpeculativePlacement.MASTER_DEDICATED ||
                            deviceSlots.isNotEmpty()
                    )
                }
            }
            if (speculativePlacement != DistributedSpeculativePlacement.LOCAL &&
                speculativePlacement != DistributedSpeculativePlacement.MASTER_DEDICATED
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (speculativePlacement == DistributedSpeculativePlacement.WORKER_SHARED)
                        stringResource(R.string.dist_draft_choose_workers)
                    else stringResource(R.string.dist_choose_resident_device),
                    style = MaterialTheme.typography.labelLarge
                )
                enabledWorkers.forEachIndexed { index, worker ->
                    val selected = worker.id in selectedDraftWorkerIds ||
                        (selectedDraftWorkerIds.isEmpty() && speculativeWorkerIndex == index)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedDraftWorkerIds = if (speculativePlacement == DistributedSpeculativePlacement.WORKER_DEDICATED) {
                                listOf(worker.id)
                            } else if (selected) {
                                selectedDraftWorkerIds - worker.id
                            } else {
                                selectedDraftWorkerIds + worker.id
                            }
                            DistributedService.setMasterSpeculativeWorkerIndex(
                                enabledWorkers.indexOfFirst { it.id == selectedDraftWorkerIds.firstOrNull() }
                                    .takeIf { it >= 0 }
                            )
                        },
                        label = { Text(worker.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = draftGpuLayers,
                    onValueChange = { DistributedService.setMasterDraftGpuLayers(it) },
                    label = { Text(stringResource(R.string.dist_speculative_gpu_layers)) },
                    supportingText = { Text(stringResource(R.string.dist_speculative_gpu_layers_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (speculativeMode.requiresDraftModel ||
                (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && mtpUseDraftModel)
            ) {
                Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                OutlinedButton(
                    onClick = { showDraftModelPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        draftModel?.filename ?: stringResource(R.string.dist_speculative_select_draft),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (draftModel != null) {
                    TextButton(onClick = { DistributedService.setMasterDraftModel(null) }) {
                        Text(stringResource(R.string.dist_speculative_clear_draft))
                    }
                }
            }
            if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.general_mtp_use_draft_model_title))
                        Text(
                            stringResource(R.string.general_mtp_use_draft_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = mtpUseDraftModel,
                        onCheckedChange = DistributedService::setMasterMtpUseDraftModel
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (speculativeMode) {
                    LlamaSpeculativeMode.DRAFT_SIMPLE,
                    LlamaSpeculativeMode.DRAFT_DFLASH,
                    LlamaSpeculativeMode.DRAFT_DSPARK -> {
                        DraftIntTextField(
                            value = draftMax,
                            onValueChange = DistributedService::setMasterDraftMax,
                            valueRange = 1..64,
                            label = { Text(stringResource(if (speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE) R.string.dist_speculative_draft_max else R.string.dist_speculative_block_size)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE) {
                            DraftIntTextField(value = draftMin, onValueChange = DistributedService::setMasterDraftMin, valueRange = 0..16, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.fillMaxWidth())
                            DraftFloatTextField(value = draftPMin, onValueChange = DistributedService::setMasterDraftPMin, valueRange = 0f..1f, label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) }, modifier = Modifier.fillMaxWidth())
                        }
                        DraftIntTextField(value = draftThreads, onValueChange = DistributedService::setMasterDraftThreads, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = draftThreadsBatch, onValueChange = DistributedService::setMasterDraftThreadsBatch, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) }, modifier = Modifier.fillMaxWidth())
                    }
                    LlamaSpeculativeMode.DRAFT_MTP -> {
                        DraftIntTextField(value = mtpDraftMax, onValueChange = DistributedService::setMasterMtpDraftMax, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_mtp_block_size)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = mtpDraftMin, onValueChange = DistributedService::setMasterMtpDraftMin, valueRange = 0..64, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.fillMaxWidth())
                        DraftFloatTextField(value = mtpDraftPMin, onValueChange = DistributedService::setMasterMtpDraftPMin, valueRange = 0f..1f, label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) }, modifier = Modifier.fillMaxWidth())
                        if (mtpUseDraftModel) {
                            DraftIntTextField(value = draftThreads, onValueChange = DistributedService::setMasterDraftThreads, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads)) }, modifier = Modifier.fillMaxWidth())
                            DraftIntTextField(value = draftThreadsBatch, onValueChange = DistributedService::setMasterDraftThreadsBatch, valueRange = 1..16, label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    LlamaSpeculativeMode.NGRAM_MOD -> {
                        DraftIntTextField(value = ngramModNMatch, onValueChange = DistributedService::setMasterNgramModNMatch, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_match)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramModNMin, onValueChange = DistributedService::setMasterNgramModNMin, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_min)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramModNMax, onValueChange = DistributedService::setMasterNgramModNMax, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_mod_max)) }, modifier = Modifier.fillMaxWidth())
                    }
                    LlamaSpeculativeMode.NGRAM_SIMPLE -> {
                        DraftIntTextField(value = ngramSimpleSizeN, onValueChange = DistributedService::setMasterNgramSimpleSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_n)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramSimpleSizeM, onValueChange = DistributedService::setMasterNgramSimpleSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_m)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramSimpleMinHits, onValueChange = DistributedService::setMasterNgramSimpleMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_simple_min_hits)) }, modifier = Modifier.fillMaxWidth())
                    }
                    LlamaSpeculativeMode.NGRAM_MAP_K -> {
                        DraftIntTextField(value = ngramMapKSizeN, onValueChange = DistributedService::setMasterNgramMapKSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_n)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramMapKSizeM, onValueChange = DistributedService::setMasterNgramMapKSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_m)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramMapKMinHits, onValueChange = DistributedService::setMasterNgramMapKMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_min_hits)) }, modifier = Modifier.fillMaxWidth())
                    }
                    LlamaSpeculativeMode.NGRAM_MAP_K4V -> {
                        DraftIntTextField(value = ngramMapK4VSizeN, onValueChange = DistributedService::setMasterNgramMapK4VSizeN, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_n)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramMapK4VSizeM, onValueChange = DistributedService::setMasterNgramMapK4VSizeM, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_m)) }, modifier = Modifier.fillMaxWidth())
                        DraftIntTextField(value = ngramMapK4VMinHits, onValueChange = DistributedService::setMasterNgramMapK4VMinHits, valueRange = 1..4096, label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_min_hits)) }, modifier = Modifier.fillMaxWidth())
                    }
                    LlamaSpeculativeMode.NGRAM_CACHE -> Text(
                        stringResource(R.string.dist_speculative_ngram_cache_params_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun MasterRuntimeGroup(s: MasterScreenState) {
    val contextSize by DistributedService.masterContextSize.collectAsState()
    val contextSizeText by DistributedService.masterContextSizeText.collectAsState()
    val temperature by DistributedService.masterTemperature.collectAsState()
    val threads by DistributedService.masterThreads.collectAsState()
    val batchSize by DistributedService.masterBatchSize.collectAsState()
    val batchSizeText by DistributedService.masterBatchSizeText.collectAsState()
    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_runtime_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_runtime_desc))
            // Context Size with text input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(stringResource(R.string.dist_context_size), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = contextSizeText,
                    onValueChange = { text ->
                        DistributedService.setMasterContextSizeText(text)
                        text.toIntOrNull()?.let { value ->
                            if (value in 512..131072) {
                                DistributedService.setMasterContextSize(value)
                            }
                        }
                    },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                     suffix = { Text(stringResource(R.string.dist_tokens), style = MaterialTheme.typography.labelSmall) }
                )
            }
            Slider(
                value = contextSize.toFloat(),
                onValueChange = { 
                    DistributedService.setMasterContextSize(it.toInt())
                    DistributedService.setMasterContextSizeText(it.toInt().toString())
                },
                valueRange = 512f..16384f,
                steps = 14
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Temperature
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(stringResource(R.string.dist_temperature), style = MaterialTheme.typography.bodyMedium)
                Text("%.1f".format(temperature), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = temperature,
                onValueChange = { DistributedService.setMasterTemperature(it) },
                valueRange = 0f..2f,
                steps = 19
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Threads
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                 Text(stringResource(R.string.dist_threads), style = MaterialTheme.typography.bodyMedium)
                Text("$threads", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = threads.toFloat(),
                onValueChange = { DistributedService.setMasterThreads(it.toInt()) },
                valueRange = 1f..8f,
                steps = 6
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Batch Size
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dist_batch_size), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = batchSizeText,
                    onValueChange = { newText ->
                        DistributedService.setMasterBatchSizeText(newText)
                        newText.toIntOrNull()?.let { v -> if (v in 1..4096) DistributedService.setMasterBatchSize(v) }
                    },
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = batchSize.toFloat(),
                onValueChange = { 
                    DistributedService.setMasterBatchSize(it.toInt())
                    DistributedService.setMasterBatchSizeText(it.toInt().toString())
                },
                valueRange = 32f..4096f,
                steps = 0
            )

            // Physical batch (--ubatch-size), always sent to the distributed llama-server
            var ubatchText by remember { mutableStateOf(DistributedPerfSettings.ubatchSize(context).toString()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.dist_perf_ubatch),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = ubatchText,
                    onValueChange = { newText ->
                        ubatchText = newText.filter(Char::isDigit).take(4)
                        ubatchText.toIntOrNull()?.let { DistributedPerfSettings.setUbatchSize(context, it) }
                    },
                    modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MasterKvGroup(s: MasterScreenState) {
    val kvCacheEnabled by DistributedService.masterKvCacheEnabled.collectAsState()
    val kvCacheTypeK by DistributedService.masterKvCacheTypeK.collectAsState()
    val kvCacheTypeV by DistributedService.masterKvCacheTypeV.collectAsState()
    val kvCacheReuse by DistributedService.masterKvCacheReuse.collectAsState()
    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_kv_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_kv_desc))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                     Text(stringResource(R.string.dist_enable_kv_cache), style = MaterialTheme.typography.bodyMedium)
                    Text(
                         text = stringResource(R.string.dist_kv_cache_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = kvCacheEnabled,
                    onCheckedChange = { DistributedService.setMasterKvCacheEnabled(it) }
                )
            }

            AnimatedVisibility(visible = kvCacheEnabled) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.dist_kv_placement_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                    DistributedKvChoice(
                        selected = kvPlacementModeName ==
                            DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name,
                        onClick = {
                            kvPlacementModeName =
                                DistributedKvPlacementMode.DISTRIBUTED_WITH_LAYERS.name
                        },
                        title = stringResource(R.string.dist_kv_placement_distributed),
                        description = stringResource(R.string.dist_kv_placement_distributed_desc)
                    )
                    DistributedKvChoice(
                        selected = kvPlacementModeName == DistributedKvPlacementMode.MASTER_CPU.name,
                        onClick = {
                            kvPlacementModeName = DistributedKvPlacementMode.MASTER_CPU.name
                        },
                        title = stringResource(R.string.dist_kv_placement_cpu),
                        description = stringResource(R.string.dist_kv_placement_cpu_desc)
                    )
                    DistributedKvChoice(
                        selected = kvPlacementModeName ==
                            DistributedKvPlacementMode.ACCELERATOR_DEVICE.name,
                        onClick = {
                            kvPlacementModeName =
                                DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                        },
                        title = stringResource(R.string.dist_kv_placement_device),
                        description = stringResource(R.string.dist_kv_placement_device_desc)
                    )

                    if (kvPlacementModeName ==
                        DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                    ) {
                        val eligibleKvWorkers = when (mainPlacementMode) {
                            MainModelPlacementMode.RESIDENT -> enabledWorkers.filter {
                                it.id == mainResidentWorkerId
                            }
                            MainModelPlacementMode.DISTRIBUTED -> enabledWorkers.filter {
                                it.id in selectedMainWorkerIds
                            }
                        }
                        Text(
                            stringResource(R.string.dist_kv_row_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (eligibleKvWorkers.isNotEmpty()) {
                            Text(
                                stringResource(R.string.dist_kv_host_title),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        eligibleKvWorkers.forEach { worker ->
                            val key = "worker:${worker.id}"
                            DistributedKvChoice(
                                selected = kvHostKey == key,
                                onClick = { kvHostKey = key },
                                title = worker.deviceName,
                                description = "${worker.ip}:${worker.port}"
                            )
                        }
                        if (eligibleKvWorkers.isEmpty()) {
                            Text(
                                stringResource(R.string.dist_kv_no_accelerator),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider()

                    DistributedKvTypeSelector(
                        title = stringResource(R.string.dist_cache_type_k),
                        selected = kvCacheTypeK,
                        onSelected = DistributedService::setMasterKvCacheTypeK
                    )
                    DistributedKvTypeSelector(
                        title = stringResource(R.string.dist_cache_type_v),
                        selected = kvCacheTypeV,
                        onSelected = DistributedService::setMasterKvCacheTypeV
                    )

                Spacer(modifier = Modifier.height(12.dp))

                // Cache Reuse Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.kv_cache_reuse), fontWeight = FontWeight.Medium)
                    Text(
                        if (kvCacheReuse == 0) stringResource(R.string.llm_kv_cache_disabled) else "$kvCacheReuse",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = kvCacheReuse.toFloat(),
                    onValueChange = { DistributedService.setMasterKvCacheReuse(it.toInt()) },
                    valueRange = 0f..512f,
                    steps = 7
                )
                }
            }
        }
    }
}

@Composable
private fun MasterNetworkGroup(s: MasterScreenState) {

    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_network_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_network_desc))
            OutlinedTextField(
                value = serverPortText,
                onValueChange = { text ->
                    serverPortText = text.filter(Char::isDigit).take(5)
                    serverPortText.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?.let(DistributedService::setMasterServerPort)
                },
                label = { Text(stringResource(R.string.dist_master_server_port)) },
                supportingText = { Text(stringResource(R.string.dist_master_server_port_hint)) },
                isError = serverPortText.toIntOrNull()?.let { it in 1..65535 } != true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isServerRunning
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                     Text(stringResource(R.string.dist_network_visibility), style = MaterialTheme.typography.bodyMedium)
                    Text(
                         text = if (enableNetworkAccess) stringResource(R.string.dist_host_public) else stringResource(R.string.dist_host_local),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableNetworkAccess,
                    onCheckedChange = { enableNetworkAccess = it }
                )
            }
        }
    }
}

@Composable
private fun MasterCommandGroup(s: MasterScreenState) {
    val customCommand by DistributedService.customCommand.collectAsState()
    val masterParallelText by DistributedService.masterParallelText.collectAsState()
    val masterCacheRamText by DistributedService.masterCacheRamText.collectAsState()
    val masterCustomFlags by DistributedService.masterCustomFlags.collectAsState()
    val masterCommandTemplate by DistributedService.masterCommandTemplate.collectAsState()
    val masterFlashAttention by DistributedService.masterFlashAttention.collectAsState()
    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_command_title)) {
            AdvancedHint(stringResource(R.string.dist_ui_adv_command_desc))
            FlashAttentionRow(
                checked = masterFlashAttention,
                onCheckedChange = { DistributedService.setMasterFlashAttention(it) }
            )
            ParallelCacheRamRow(
                parallel = masterParallelText,
                cacheRam = masterCacheRamText,
                onParallelChange = { text ->
                    DistributedService.setMasterParallelText(text)
                    DistributedService.setMasterParallel(text.toIntOrNull())
                },
                onCacheRamChange = { text ->
                    DistributedService.setMasterCacheRamText(text)
                    DistributedService.setMasterCacheRam(text.toIntOrNull())
                }
            )
            OutlinedTextField(
                value = masterCommandTemplate,
                onValueChange = { DistributedService.setMasterCommandTemplate(it) },
                label = { Text(stringResource(R.string.command_template_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                placeholder = { Text(stringResource(R.string.command_template_placeholder)) },
                supportingText = {
                    Text(stringResource(R.string.command_template_placeholders))
                }
            )
            CustomFlagsField(
                value = masterCustomFlags,
                onValueChange = { DistributedService.setMasterCustomFlags(it) }
            )
            if (customCommand != null) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.dist_custom_command_active)) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp)) }
                )
            }
            if (!isStarting && !isServerRunning) {
                // Save and Load Command Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showLoadCommandDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.List, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dist_load_command))
                    }

                    OutlinedButton(
                        onClick = { 
                            commandNameToSave = ""
                            showSaveCommandDialog = true 
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedModel != null
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dist_save_command))
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterLayerMemoryGroup(s: MasterScreenState) {

    with(s) {
        AppAdvancedSection(title = stringResource(R.string.dist_ui_adv_layer_memory_title)) {
            val lastDistribution by settingsRepo.lastLayerDistribution.collectAsState()
            val lastModelPath by settingsRepo.lastDistributionModelPath.collectAsState()
            var rememberDistribution by remember { mutableStateOf(lastDistribution != null) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dist_remember_layer_split), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.dist_layer_split_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rememberDistribution,
                    onCheckedChange = { 
                        rememberDistribution = it
                        if (!it) {
                            // Clear saved distribution when disabled
                            settingsRepo.clearLayerDistributionMemory()
                        }
                    }
                )
            }

            if (rememberDistribution && lastDistribution != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                             text = stringResource(R.string.dist_saved_distribution),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = lastModelPath?.substringAfterLast("/") ?: "Unknown model",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { settingsRepo.clearLayerDistributionMemory() }
                        ) {
                             Text(stringResource(R.string.dist_clear_recalculate))
                        }
                    }
                }
            }
        }
    }
}
