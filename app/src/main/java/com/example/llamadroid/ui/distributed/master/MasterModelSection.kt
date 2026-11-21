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

/** Step 2: pick the model (with the layer check) and the optional image-support file. */
@Composable
internal fun MasterModelSection(s: MasterScreenState) {

    with(s) {
        AppSectionCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppSectionTitle(
                    title = stringResource(R.string.dist_ui_step_model),
                    supporting = stringResource(R.string.dist_ui_step_model_desc)
                )
                OutlinedCard(
                    onClick = {
                        walkthroughTargets?.recordEvent("distributed.master")
                        showModelPicker = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedModel?.filename ?: stringResource(R.string.dist_tap_to_select_model),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (selectedModel != null) {
                                    formatFileSize(selectedModel!!.sizeBytes)
                                } else {
                                    stringResource(R.string.dist_models_available, llmModels.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.dist_select_model)
                        )
                    }
                }
                when (val probeState = modelProbeState) {
                    ModelProbeUiState.Checking -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.dist_probe_checking),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            stringResource(R.string.dist_probe_bounded_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showManualLayerInput = true },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.dist_probe_enter_manually))
                        }
                    }
                    is ModelProbeUiState.Ready -> {
                        Text(
                            stringResource(
                                R.string.dist_probe_ready,
                                probeState.result.transformerBlocks,
                                probeState.result.offloadableLayers
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (probeState.parserLayers != null &&
                            probeState.parserLayers != probeState.result.offloadableLayers
                        ) {
                            Text(
                                stringResource(
                                    R.string.dist_probe_mismatch,
                                    probeState.parserLayers,
                                    probeState.result.offloadableLayers
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    is ModelProbeUiState.Manual -> Text(
                        stringResource(
                            R.string.dist_probe_manual_active,
                            probeState.result.transformerBlocks,
                            probeState.result.offloadableLayers
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    is ModelProbeUiState.Failed -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.dist_probe_error, probeState.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(
                            onClick = { modelProbeAttempt++ },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dist_probe_retry))
                        }
                        TextButton(
                            onClick = { showManualLayerInput = true },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dist_probe_enter_manually))
                        }
                    }
                    ModelProbeUiState.Missing -> Unit
                }
                if (showManualLayerInput || manualTransformerBlocks != null) {
                    val parsedManualBlocks = manualLayerText.toIntOrNull()
                    val manualValueValid = parsedManualBlocks != null && parsedManualBlocks in 1..2048
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = manualLayerText,
                            onValueChange = { input ->
                                manualLayerText = input.filter(Char::isDigit).take(4)
                                if (manualTransformerBlocks != null) {
                                    manualLayerText.toIntOrNull()
                                        ?.takeIf { it in 1..2048 }
                                        ?.let { manualTransformerBlocks = it }
                                }
                            },
                            label = { Text(stringResource(R.string.dist_probe_manual_label)) },
                            supportingText = {
                                Text(
                                    if (manualTransformerBlocks != null && !manualValueValid) {
                                        stringResource(
                                            R.string.dist_probe_manual_last_valid,
                                            manualTransformerBlocks ?: 0
                                        )
                                    } else {
                                        stringResource(R.string.dist_probe_manual_helper)
                                    }
                                )
                            },
                            isError = manualLayerText.isNotBlank() && !manualValueValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (manualTransformerBlocks == null) {
                            Button(
                                onClick = { manualTransformerBlocks = parsedManualBlocks },
                                enabled = manualValueValid,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.dist_probe_use_manual))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    manualTransformerBlocks = null
                                    manualLayerText = ""
                                    showManualLayerInput = false
                                    modelProbeAttempt++
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.dist_probe_use_binary_again))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.dist_ui_mmproj_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.dist_ui_mmproj_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { showMmprojPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        selectedMmprojPath?.substringAfterLast('/')
                            ?: stringResource(R.string.llm_vision_not_selected),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectedMmprojPath != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mmprojPlacementName == MmprojPlacement.MASTER_ACCELERATOR.name,
                            onClick = { mmprojPlacementName = MmprojPlacement.MASTER_ACCELERATOR.name },
                            label = { Text(stringResource(R.string.llm_draft_device_accelerator)) }
                        )
                        FilterChip(
                            selected = mmprojPlacementName == MmprojPlacement.MASTER_CPU.name,
                            onClick = { mmprojPlacementName = MmprojPlacement.MASTER_CPU.name },
                            label = { Text(stringResource(R.string.llm_draft_device_cpu)) }
                        )
                    }
                    Text(
                        stringResource(R.string.dist_ui_mmproj_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
