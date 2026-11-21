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

/** Every dialog of the main-device screen (model, vision file, draft model, helpers, command tools). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MasterDialogs(s: MasterScreenState) {
    val lastCommand by DistributedService.lastCommand.collectAsState()
    val customCommand by DistributedService.customCommand.collectAsState()
    val contextSize by DistributedService.masterContextSize.collectAsState()
    val temperature by DistributedService.masterTemperature.collectAsState()
    val threads by DistributedService.masterThreads.collectAsState()
    val batchSize by DistributedService.masterBatchSize.collectAsState()
    val kvCacheEnabled by DistributedService.masterKvCacheEnabled.collectAsState()
    val kvCacheTypeK by DistributedService.masterKvCacheTypeK.collectAsState()
    val kvCacheTypeV by DistributedService.masterKvCacheTypeV.collectAsState()
    val kvCacheReuse by DistributedService.masterKvCacheReuse.collectAsState()
    val masterCustomFlags by DistributedService.masterCustomFlags.collectAsState()
    val masterCommandTemplate by DistributedService.masterCommandTemplate.collectAsState()
    val masterParallel by DistributedService.masterParallel.collectAsState()
    val masterCacheRam by DistributedService.masterCacheRam.collectAsState()
    val masterFlashAttention by DistributedService.masterFlashAttention.collectAsState()
    val speculativeEnabled by DistributedService.masterSpeculativeEnabled.collectAsState()
    val draftMax by DistributedService.masterDraftMax.collectAsState()
    val draftMin by DistributedService.masterDraftMin.collectAsState()
    val draftPMin by DistributedService.masterDraftPMin.collectAsState()
    val draftThreads by DistributedService.masterDraftThreads.collectAsState()
    val draftThreadsBatch by DistributedService.masterDraftThreadsBatch.collectAsState()
    val speculativeMode by DistributedService.masterSpeculativeMode.collectAsState()
    with(s) {
        // Model Picker Dialog
        if (showModelPicker) {
            AlertDialog(
                onDismissRequest = { showModelPicker = false },
                 title = { Text(stringResource(R.string.dist_select_model)) },
                text = {
                    if (llmModels.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("📦", style = MaterialTheme.typography.displayMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                             Text(stringResource(R.string.dist_no_models))
                             Text(
                                 stringResource(R.string.dist_no_models_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                showModelPicker = false
                                navController.navigate(Screen.LLMModels.route)
                            }) { Text(stringResource(R.string.common_get_models_link)) }
                        }
                    } else {
                        LazyColumn {
                            items(llmModels) { model ->
                                Surface(
                                    onClick = {
                                        DistributedService.setMasterSelectedModel(model)
                                        showModelPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (model == selectedModel)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = model == selectedModel,
                                            onClick = {
                                                DistributedService.setMasterSelectedModel(model)
                                                showModelPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.filename,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${formatFileSize(model.sizeBytes)} • ${model.type}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModelPicker = false }) {
                         Text(stringResource(R.string.action_close))
                    }
                },
                dismissButton = if (llmModels.isNotEmpty()) {
                    {
                        TextButton(onClick = {
                            showModelPicker = false
                            navController.navigate(Screen.LLMModels.route)
                        }) { Text(stringResource(R.string.common_get_models_link), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                } else null
            )
        }

        if (showMmprojPicker) {
            AlertDialog(
                onDismissRequest = { showMmprojPicker = false },
                title = { Text(stringResource(R.string.dist_step_mmproj)) },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        item {
                            TextButton(onClick = {
                                showMmprojPicker = false
                                navController.navigate(Screen.LLMModels.route)
                            }) { Text(stringResource(R.string.common_get_models_link)) }
                        }
                        item {
                            Surface(
                                onClick = { selectedMmprojPath = null; showMmprojPicker = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.llm_vision_not_selected), modifier = Modifier.padding(16.dp)) }
                        }
                        items(mmprojModels, key = { it.path }) { model ->
                            Surface(
                                onClick = { selectedMmprojPath = model.path; showMmprojPicker = false },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (selectedMmprojPath == model.path) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    model.filename,
                                    modifier = Modifier.padding(16.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMmprojPicker = false }) { Text(stringResource(R.string.action_close)) }
                }
            )
        }

        // Draft Model Picker Dialog (for speculative decoding)
        if (showDraftModelPicker) {
            AlertDialog(
                onDismissRequest = { showDraftModelPicker = false },
                title = {
                    Text(
                        stringResource(
                            if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
                                R.string.models_type_mtp
                            } else {
                                R.string.dist_speculative_draft_model
                            }
                        )
                    )
                },
                text = {
                    if (draftModels.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text("📦", style = MaterialTheme.typography.displayMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.dist_no_models))
                            TextButton(onClick = {
                                showDraftModelPicker = false
                                navController.navigate(Screen.LLMModels.route)
                            }) { Text(stringResource(R.string.common_get_models_link)) }
                        }
                    } else {
                        LazyColumn {
                            items(draftModels) { model ->
                                Surface(
                                    onClick = {
                                        DistributedService.setMasterDraftModel(model)
                                        showDraftModelPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (model == draftModel)
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = model == draftModel,
                                            onClick = {
                                                DistributedService.setMasterDraftModel(model)
                                                showDraftModelPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.filename,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${formatFileSize(model.sizeBytes)} • ${model.type}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDraftModelPicker = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
                dismissButton = {
                    if (draftModel != null) {
                        TextButton(onClick = { 
                            DistributedService.setMasterDraftModel(null)
                            showDraftModelPicker = false
                        }) {
                            Text(stringResource(R.string.dist_speculative_clear_draft))
                        }
                    }
                }
            )
        }

        // Add Worker Dialog - manual IP entry
        if (showAddWorkerDialog) {
            var workerName by remember { mutableStateOf("") }
            var ipAddress by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("50052") }
            var ramMB by remember { mutableStateOf("4096") }
            var layers by remember { mutableStateOf("") }  // blank = auto

            AlertDialog(
                onDismissRequest = { showAddWorkerDialog = false },
                 title = { Text(stringResource(R.string.dist_add_worker_btn)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                         Text(
                             stringResource(R.string.dist_add_worker_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        OutlinedTextField(
                            value = workerName,
                            onValueChange = { workerName = it },
                             label = { Text(stringResource(R.string.dist_device_name_optional)) },
                             placeholder = { Text(stringResource(R.string.dist_device_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = ipAddress,
                            onValueChange = { ipAddress = it },
                             label = { Text(stringResource(R.string.dist_ip_address_label)) },
                             placeholder = { Text(stringResource(R.string.dist_ip_address_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                             label = { Text(stringResource(R.string.dist_port_label)) },
                             placeholder = { Text(stringResource(R.string.dist_port_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = ramMB,
                            onValueChange = { ramMB = it },
                             label = { Text(stringResource(R.string.dist_worker_ram_label)) },
                             placeholder = { Text(stringResource(R.string.dist_ram_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = layers,
                            onValueChange = { layers = it },
                             label = { Text(stringResource(R.string.dist_load_proportion)) },
                             placeholder = { Text(stringResource(R.string.dist_load_proportion_hint)) },
                             singleLine = true,
                             modifier = Modifier.fillMaxWidth(),
                             supportingText = { Text(stringResource(R.string.dist_auto_load_desc)) }
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💡", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                     stringResource(R.string.dist_add_worker_tip),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val portNum = port.toIntOrNull() ?: 50052
                            val ram = ramMB.toIntOrNull() ?: 4096
                            // Parse proportion: user enters 0-100, we store as 0.0-1.0
                            val proportion = layers.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null if blank/invalid = auto
                            val name = workerName.ifBlank { "Worker ${savedWorkers.size + 1}" }
                            if (ipAddress.isNotBlank()) {
                                // Save to database
                                coroutineScope.launch {
                                    db.savedWorkerDao().insertWorker(
                                        SavedWorker(
                                            ip = ipAddress.trim(),
                                            port = portNum,
                                            deviceName = name,
                                            ramMB = ram,
                                            isEnabled = true,
                                            assignedProportion = proportion
                                        )
                                    )
                                }
                                 Toast.makeText(context, resources.getString(R.string.dist_worker_added_toast, name, ipAddress, portNum.toString()), Toast.LENGTH_SHORT).show()
                            }
                            showAddWorkerDialog = false
                        }
                    ) {
                                         Text(stringResource(R.string.dist_add_worker_btn))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddWorkerDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        // Edit Worker Dialog - capture worker to local val to prevent null issues during composition
        workerToEdit?.let { editingWorker ->
            if (showEditWorkerDialog) {
                key(editingWorker.id) {
                    var editIp by remember { mutableStateOf(editingWorker.ip) }
                    var editPort by remember { mutableStateOf(editingWorker.port.toString()) }
                    var editName by remember { mutableStateOf(editingWorker.deviceName) }
                    var editRam by remember { mutableStateOf(editingWorker.ramMB.toString()) }
                    // Display as percentage (0-100), store as 0.0-1.0
                    var editProportion by remember { mutableStateOf(editingWorker.assignedProportion?.let { "${(it * 100).toInt()}" } ?: "") }

                    AlertDialog(
                        onDismissRequest = { 
                            showEditWorkerDialog = false
                            workerToEdit = null
                        },
                         title = { Text(stringResource(R.string.dist_edit_worker)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                     label = { Text(stringResource(R.string.dist_device_name)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = editIp,
                                    onValueChange = { editIp = it },
                                     label = { Text(stringResource(R.string.dist_ip_address_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = editPort,
                                    onValueChange = { editPort = it },
                                     label = { Text(stringResource(R.string.dist_port_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = editRam,
                                    onValueChange = { editRam = it },
                                     label = { Text(stringResource(R.string.dist_worker_ram_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = editProportion,
                                    onValueChange = { editProportion = it },
                                     label = { Text(stringResource(R.string.dist_load_proportion)) },
                                     placeholder = { Text(stringResource(R.string.dist_load_proportion_hint)) },
                                     singleLine = true,
                                     modifier = Modifier.fillMaxWidth(),
                                     supportingText = { Text(stringResource(R.string.dist_auto_load_desc)) }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    // Parse proportion: user enters 0-100, we store as 0.0-1.0
                                    val proportion = editProportion.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null = auto
                                    coroutineScope.launch {
                                        db.savedWorkerDao().updateWorker(
                                            editingWorker.copy(
                                                ip = editIp.trim(),
                                                port = editPort.toIntOrNull() ?: 50052,
                                                deviceName = editName,
                                                ramMB = editRam.toIntOrNull() ?: 4096,
                                                assignedProportion = proportion
                                            )
                                        )
                                    }
                                    showEditWorkerDialog = false
                                    workerToEdit = null
                                     Toast.makeText(context, resources.getString(R.string.dist_worker_updated_toast), Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                 Text(resources.getString(R.string.action_save))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { 
                                showEditWorkerDialog = false
                                workerToEdit = null
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }  // End of key() block
            }
        }

        // Command Preview Dialog
        if (lastCommand != null) {
            val originalCmd = lastCommand!!

            AlertDialog(
                onDismissRequest = { 
                    DistributedService.clearLastCommand()
                    isEditingCommand = false
                },
                title = {
                    Text(
                        if (isEditingCommand) {
                            stringResource(R.string.dist_edit_command)
                        } else {
                            stringResource(R.string.dist_command_preview_title)
                        }
                    )
                },
                text = {
                    Column {
                        Text(
                            text = if (isEditingCommand) 
                                stringResource(R.string.dist_command_customize_desc)
                                else stringResource(R.string.dist_command_preview_desc),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isEditingCommand) {
                            OutlinedTextField(
                                value = editedCommand,
                                onValueChange = { editedCommand = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                label = { Text(stringResource(R.string.dist_command_label)) }
                            )
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = customCommand ?: originalCmd,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (isEditingCommand) {
                            TextButton(
                                onClick = { 
                                    runCatching {
                                        require(ProcessController().splitCommandLine(editedCommand).isNotEmpty())
                                        DistributedService.setCustomCommand(editedCommand)
                                    }.onSuccess {
                                        isEditingCommand = false
                                    }.onFailure {
                                        Toast.makeText(context, resources.getString(R.string.dist_custom_command_invalid), Toast.LENGTH_LONG).show()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.dist_save_command))
                            }
                        } else {
                            TextButton(
                                onClick = { 
                                    editedCommand = customCommand ?: originalCmd
                                    isEditingCommand = true
                                }
                            ) {
                                Text(stringResource(R.string.dist_edit_command))
                            }

                            TextButton(
                                onClick = {
                                    val currentCmd = customCommand ?: originalCmd
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(currentCmd))
                                    Toast.makeText(context, resources.getString(R.string.dist_command_copied), Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(stringResource(R.string.action_copy))
                            }
                        }
                    }
                },
                dismissButton = {
                    if (isEditingCommand) {
                        TextButton(onClick = { isEditingCommand = false }) {
                            Text(stringResource(R.string.dist_discard_command))
                        }
                    } else {
                        TextButton(onClick = { 
                            DistributedService.clearLastCommand()
                            isEditingCommand = false
                        }) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            )
        }

        // Save Command Dialog
        if (showSaveCommandDialog) {
            val enabledWorkersList = savedWorkers.filter { it.isEnabled }
            AlertDialog(
                onDismissRequest = { showSaveCommandDialog = false },
                title = { Text(stringResource(R.string.dist_save_command_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.dist_save_command_desc), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = commandNameToSave,
                            onValueChange = { commandNameToSave = it },
                            label = { Text(stringResource(R.string.dist_command_preset_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (commandNameToSave.isNotBlank() && selectedModel != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val host = if (enableNetworkAccess) "0.0.0.0" else "127.0.0.1"
                                    val newCommand = SavedCommandEntity(
                                        name = commandNameToSave.trim(),
                                        commandTemplate = masterCommandTemplate,
                                        scope = SavedCommandScopes.MASTER,
                                        modelPath = selectedModel?.path ?: "",
                                        contextSize = contextSize,
                                        batchSize = batchSize,
                                        temperature = temperature,
                                        threads = threads,
                                        host = host,
                                        speculativeEnabled = speculativeEnabled,
                                        speculativeMode = speculativeMode.flagValue,
                                        draftModelPath = draftModel?.path,
                                        draftMax = draftMax,
                                        draftMin = draftMin,
                                        draftPMin = draftPMin,
                                        draftThreads = draftThreads,
                                        draftThreadsBatch = draftThreadsBatch,
                                        parallel = masterParallel,
                                        cacheRam = masterCacheRam,
                                        customFlags = masterCustomFlags,
                                        flashAttention = masterFlashAttention,
                                        kvCacheEnabled = kvCacheEnabled,
                                        kvCacheTypeK = kvCacheTypeK,
                                        kvCacheTypeV = kvCacheTypeV,
                                        kvCacheReuse = kvCacheReuse,
                                        masterRamMB = masterRamSlider.toInt(),
                                        workersListStr = enabledWorkersList.joinToString(",") { "${it.ip}:${it.port}" }
                                    )
                                    db.savedCommandDao().insertCommand(newCommand)
                                }
                                Toast.makeText(context, resources.getString(R.string.dist_command_saved), Toast.LENGTH_SHORT).show()
                                showSaveCommandDialog = false
                            }
                        },
                        enabled = commandNameToSave.isNotBlank()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveCommandDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        // Load Command Dialog
        if (showLoadCommandDialog) {
            AlertDialog(
                onDismissRequest = { showLoadCommandDialog = false },
                title = { Text(stringResource(R.string.dist_load_command_title)) },
                text = {
                    if (savedCommands.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp).fillMaxWidth()
                        ) {
                            Text("📋", style = MaterialTheme.typography.displayMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.dist_no_commands_saved))
                        }
                    } else {
                        LazyColumn {
                            items(savedCommands) { cmd ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).clickable {
                                            // Load settings
                                            coroutineScope.launch {
                                                // Model
                                                val model = allModels.find { it.path == cmd.modelPath }
                                                if (model != null) DistributedService.setMasterSelectedModel(model)

                                                // Base settings
                                                DistributedService.setMasterContextSize(cmd.contextSize)
                                                DistributedService.setMasterContextSizeText(cmd.contextSize.toString())
                                                DistributedService.setMasterBatchSize(cmd.batchSize)
                                                DistributedService.setMasterBatchSizeText(cmd.batchSize.toString())
                                                DistributedService.setMasterTemperature(cmd.temperature)
                                                DistributedService.setMasterThreads(cmd.threads)
                                                enableNetworkAccess = (cmd.host == "0.0.0.0")

                                                // Speculative
                                                DistributedService.setMasterSpeculativeEnabled(cmd.speculativeEnabled)
                                                val commandSpeculativeMode =
                                                    LlamaSpeculativeMode.fromFlagValue(cmd.speculativeMode)
                                                DistributedService.setMasterSpeculativeMode(commandSpeculativeMode)
                                                val commandDraftModels = speculativeDraftModelsFor(
                                                    availableModels,
                                                    commandSpeculativeMode
                                                )
                                                DistributedService.setMasterDraftModel(
                                                    commandDraftModels.firstOrNull { it.path == cmd.draftModelPath }
                                                )
                                                DistributedService.setMasterDraftMax(cmd.draftMax)
                                                DistributedService.setMasterDraftMaxText(cmd.draftMax.toString())
                                                DistributedService.setMasterDraftMin(cmd.draftMin)
                                                DistributedService.setMasterDraftMinText(cmd.draftMin.toString())
                                                DistributedService.setMasterDraftPMin(cmd.draftPMin)
                                                DistributedService.setMasterDraftPMinText(cmd.draftPMin.toString())
                                                DistributedService.setMasterDraftThreads(cmd.draftThreads)
                                                DistributedService.setMasterDraftThreadsText(cmd.draftThreads.toString())
                                                DistributedService.setMasterDraftThreadsBatch(cmd.draftThreadsBatch)
                                                DistributedService.setMasterDraftThreadsBatchText(cmd.draftThreadsBatch.toString())

                                                // Advanced
                                                DistributedService.setMasterParallel(cmd.parallel)
                                                DistributedService.setMasterParallelText(cmd.parallel?.toString() ?: "")
                                                DistributedService.setMasterCacheRam(cmd.cacheRam)
                                                DistributedService.setMasterCacheRamText(cmd.cacheRam?.toString() ?: "")
                                                DistributedService.setMasterCommandTemplate(cmd.commandTemplate)
                                                DistributedService.setMasterCustomFlags(cmd.customFlags)
                                                DistributedService.setMasterFlashAttention(cmd.flashAttention)

                                                // KV Cache
                                                DistributedService.setMasterKvCacheEnabled(cmd.kvCacheEnabled)
                                                if (cmd.kvCacheTypeK.isNotEmpty()) {
                                                    DistributedService.setMasterKvCacheTypeK(cmd.kvCacheTypeK)
                                                }
                                                if (cmd.kvCacheTypeV.isNotEmpty()) {
                                                    DistributedService.setMasterKvCacheTypeV(cmd.kvCacheTypeV)
                                                }
                                                DistributedService.setMasterKvCacheReuse(cmd.kvCacheReuse)

                                                // Master RAM
                                                DistributedService.setMasterRam(cmd.masterRamMB)
                                                masterRamSlider = cmd.masterRamMB.toFloat()
                                                masterRamText = cmd.masterRamMB.toString()

                                                // Enable specified workers
                                                val workerKeys = cmd.workersListStr.split(",").filter { it.isNotEmpty() }
                                                savedWorkers.forEach { w ->
                                                    val isListed = workerKeys.contains("${w.ip}:${w.port}")
                                                    if (w.isEnabled != isListed) {
                                                        db.savedWorkerDao().setWorkerEnabled(w.id, isListed)
                                                    }
                                                }
                                            }
                                            Toast.makeText(context, resources.getString(R.string.dist_command_loaded), Toast.LENGTH_SHORT).show()
                                            showLoadCommandDialog = false
                                        }) {
                                            Text(cmd.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Text(stringResource(R.string.model_filename_label, cmd.modelPath.substringAfterLast("/")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    db.savedCommandDao().deleteCommand(cmd)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLoadCommandDialog = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }
    }
}
