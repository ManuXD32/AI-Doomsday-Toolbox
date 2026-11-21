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

/** Step 3: where the model runs, how much memory this phone gives, and the allocation strip. */
@Composable
internal fun MasterMemorySection(s: MasterScreenState) {

    with(s) {
        AppSectionCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppSectionTitle(
                    title = stringResource(R.string.dist_ui_step_memory),
                    supporting = stringResource(R.string.dist_ui_step_memory_desc)
                )
                if (selectedModel == null) {
                    Text(
                        stringResource(R.string.dist_ui_need_model_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (enabledWorkers.isEmpty()) {
                    Text(
                        stringResource(R.string.dist_ui_need_helper_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    stringResource(R.string.dist_ui_placement_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dist_ui_placement_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mainPlacementMode == MainModelPlacementMode.DISTRIBUTED,
                        onClick = { mainPlacementModeName = MainModelPlacementMode.DISTRIBUTED.name },
                        label = { Text(stringResource(R.string.dist_placement_distributed)) }
                    )
                    FilterChip(
                        selected = mainPlacementMode == MainModelPlacementMode.RESIDENT,
                        onClick = { mainPlacementModeName = MainModelPlacementMode.RESIDENT.name },
                        label = { Text(stringResource(R.string.dist_placement_resident)) }
                    )
                }
                if (mainPlacementMode == MainModelPlacementMode.RESIDENT) {
                    Text(stringResource(R.string.dist_choose_resident_device), style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID,
                        onClick = { mainResidentWorkerId = MASTER_CPU_PLACEMENT_ID },
                        label = { Text(stringResource(R.string.dist_master_cpu)) }
                    )
                    enabledWorkers.forEach { worker ->
                        FilterChip(
                            selected = mainResidentWorkerId == worker.id ||
                                (mainResidentWorkerId == null && worker == enabledWorkers.firstOrNull()),
                            onClick = { mainResidentWorkerId = worker.id },
                            label = { Text(worker.deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeMasterInMain,
                            onCheckedChange = { includeMasterInMain = it }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dist_include_master_main))
                            Text(
                                stringResource(R.string.dist_include_master_main_desc, masterRamSlider.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    enabledWorkers.forEach { worker ->
                        FilterChip(
                            selected = worker.id in selectedMainWorkerIds,
                            onClick = {
                                selectedMainWorkerIds = if (worker.id in selectedMainWorkerIds) {
                                    selectedMainWorkerIds - worker.id
                                } else {
                                    selectedMainWorkerIds + worker.id
                                }
                            },
                            label = {
                                Text(
                                    "${worker.deviceName} · ${worker.ramMB} MB",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
                DistributedAllocationStrip(
                    workers = enabledWorkers,
                    residentWorkerId = mainResidentWorkerId.takeIf { mainPlacementMode == MainModelPlacementMode.RESIDENT },
                    includeMaster = includeMasterInMain && mainPlacementMode == MainModelPlacementMode.DISTRIBUTED ||
                        mainPlacementMode == MainModelPlacementMode.RESIDENT && mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID,
                    masterRamMiB = masterRamSlider.toInt(),
                    selectedWorkerIds = if (mainPlacementMode == MainModelPlacementMode.DISTRIBUTED) {
                        selectedMainWorkerIds.toSet()
                    } else null,
                    offloadableLayers = effectiveLayerResult
                        ?.offloadableLayers
                        ?.takeUnless {
                            kvPlacementModeName == DistributedKvPlacementMode.ACCELERATOR_DEVICE.name
                        }
                )
                PlannedLayersLine(s)
                HorizontalDivider()
                Text(
                    stringResource(R.string.dist_ui_your_ram_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dist_ui_your_ram_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // RAM with text input (synced with slider)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${masterRamSlider.toInt()} ${stringResource(R.string.agent_unit_mb)}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (masterRamSlider.toInt() == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = masterRamText,
                        onValueChange = { newValue ->
                            masterRamText = newValue
                            newValue.toIntOrNull()?.let { ram ->
                                val clamped = ram.coerceIn(0, totalRamMB)
                                masterRamSlider = clamped.toFloat()
                                DistributedService.setMasterRam(clamped)
                            }
                        },
                        label = { Text(stringResource(R.string.agent_unit_mb)) },
                        singleLine = true,
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isServerRunning
                    )
                }

                // Show warning if RAM exceeds currently available
                if (masterRamSlider > availableRamMB) {
                    Text(
                        text = stringResource(R.string.dist_ram_exceeds_available, availableRamMB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Slider(
                    value = masterRamSlider,
                    onValueChange = { 
                        masterRamSlider = it
                        masterRamText = it.toInt().toString()
                        DistributedService.setMasterRam(it.toInt())
                    },
                    valueRange = 0f..totalRamMB.toFloat().coerceAtLeast(1f),
                    steps = (totalRamMB / 512).coerceAtLeast(1),
                    enabled = !isServerRunning
                )

                // Offload All button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = masterRamSlider.toInt() == 0,
                        onClick = {
                            if (masterRamSlider.toInt() == 0) {
                                // Toggle back to a reasonable default
                                val defaultRam = (totalRamMB / 2).coerceAtLeast(1024)
                                masterRamSlider = defaultRam.toFloat()
                                masterRamText = defaultRam.toString()
                                DistributedService.setMasterRam(defaultRam)
                            } else {
                                masterRamSlider = 0f
                                masterRamText = "0"
                                DistributedService.setMasterRam(0)
                            }
                        },
                        label = { Text(stringResource(R.string.dist_offload_all)) },
                        leadingIcon = if (masterRamSlider.toInt() == 0) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        enabled = !isServerRunning
                    )
                }

                if (masterRamSlider.toInt() == 0) {
                    Text(
                        text = stringResource(R.string.dist_offload_all_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.dist_device_total_ram, totalRamMB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.dist_device_free_ram, availableRamMB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                TotalRamSummary(s)
            }
        }
    }
}

/** What the planner decided, in plain words (needs a resolved plan, so it appears after the model check). */
@Composable
private fun PlannedLayersLine(s: MasterScreenState) {
    val plan = s.planProfile ?: return
    if (plan.mainLayerAllocations.isEmpty()) return
    val parts = plan.mainLayerAllocations.filter { it.assignedLayers > 0 }.map { allocation ->
        val name = if (allocation.device.kind == DistributedDeviceKind.WORKER) {
            s.enabledWorkers.firstOrNull { it.id == allocation.device.workerId }?.deviceName
                ?: allocation.device.workerAddress.orEmpty()
        } else {
            stringResource(R.string.dist_ui_this_phone)
        }
        stringResource(R.string.dist_ui_plan_part, name, allocation.assignedLayers)
    }
    Text(
        stringResource(R.string.dist_ui_plan_line, parts.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Total memory of the chosen devices against the model size. */
@Composable
private fun TotalRamSummary(s: MasterScreenState) {
    with(s) {
        val selectedRamWorkers = when (mainPlacementMode) {
            MainModelPlacementMode.RESIDENT -> enabledWorkers.filter { it.id == mainResidentWorkerId }
            MainModelPlacementMode.DISTRIBUTED -> enabledWorkers.filter { it.id in selectedMainWorkerIds }
        }
        val masterContribution = when (mainPlacementMode) {
            MainModelPlacementMode.RESIDENT -> if (mainResidentWorkerId == MASTER_CPU_PLACEMENT_ID) masterRamSlider.toInt() else 0
            MainModelPlacementMode.DISTRIBUTED -> if (includeMasterInMain) masterRamSlider.toInt() else 0
        }
        val totalRam = masterContribution + selectedRamWorkers.sumOf { it.ramMB }
        val modelSizeMB = selectedModel?.sizeBytes?.div(1024 * 1024)?.toInt() ?: 0
        val hasEnoughRam = totalRam >= modelSizeMB || modelSizeMB == 0
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AppChromeDefaults.InnerCardShape,
            colors = CardDefaults.cardColors(
                containerColor = if (hasEnoughRam) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (hasEnoughRam) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dist_total_ram, totalRam),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (modelSizeMB > 0) {
                        Text(
                            text = stringResource(R.string.dist_model_ram_requirement, modelSizeMB),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
