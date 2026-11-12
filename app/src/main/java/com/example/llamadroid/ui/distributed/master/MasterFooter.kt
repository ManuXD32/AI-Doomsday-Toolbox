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

/** Step 5, always visible: only the primary Start/Stop action (secondary actions are [MasterToolsRow]). */
@Composable
internal fun MasterFooter(s: MasterScreenState) {

    with(s) {
        val enabledWorkersList = enabledWorkers
        val probeReady = effectiveLayerResult != null
        val canStart = selectedModel != null && enabledWorkersList.isNotEmpty() && probeReady && !manualSplitBlocksLaunch
        val enabledWorkersListPreview = enabledWorkersList
        AppTaskActionFooter {
            if (!canStart && !isStarting && !isServerRunning) {
                Text(
                    text = when {
                        enabledWorkersList.isEmpty() -> stringResource(R.string.dist_ui_need_helper_first)
                        selectedModel == null -> stringResource(R.string.dist_ui_need_model_first)
                        modelProbeState is ModelProbeUiState.Checking -> stringResource(R.string.dist_probe_checking)
                        probeReady && manualSplitBlocksLaunch -> stringResource(R.string.dist_r3_split_fix_first)
                        else -> stringResource(R.string.dist_ui_need_probe)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = {
                    walkthroughTargets?.recordEvent("distributed.master")
                    if (isServerRunning) {
                        context.startService(Intent(context, DistributedMasterLlamaService::class.java).apply {
                            action = DistributedMasterLlamaService.ACTION_STOP
                        })
                    } else if (selectedModel != null && enabledWorkersList.isNotEmpty()) {
                        isStarting = true
                        coroutineScope.launch {
                            runCatching { resolveCurrentDistributedLaunch() }
                                .onSuccess { launch ->
                                    val profile = launch.profile
                                    DistributedService.setMasterMode(profile.config.rpcWorkers)
                                    DistributedMasterRuntimeState.prepare(launch)
                                    val intent = Intent(context, DistributedMasterLlamaService::class.java).apply {
                                        action = DistributedMasterLlamaService.ACTION_START
                                        putExtra(
                                            DistributedMasterLlamaService.EXTRA_RESOLVED_LAUNCH,
                                            ResolvedDistributedLaunch.encode(launch)
                                        )
                                    }
                                    context.startForegroundService(intent)
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.dist_started_msg, profile.workers.size),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: resources.getString(R.string.dist_error_start_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            isStarting = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = (canStart || isServerRunning) && !isStarting
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(if (isServerRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                     text = when {
                         isStarting -> stringResource(R.string.dist_starting)
                         isServerRunning -> stringResource(R.string.dist_stop_server)
                         else -> stringResource(R.string.dist_start_btn)
                     },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Secondary actions (show command, network status) kept in the scrollable content, not the footer. */
@Composable
internal fun MasterToolsRow(s: MasterScreenState) {
    with(s) {
        val enabledWorkersListPreview = enabledWorkers
        ResponsiveActionGroup {
            if (!isStarting && !isServerRunning) {
                OutlinedButton(
                    onClick = {
                        if (selectedModel != null) {
                            isPreviewing = true
                            coroutineScope.launch {
                                runCatching { buildCurrentDistributedCommand() }
                                    .onSuccess(DistributedService::setLastCommand)
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: resources.getString(R.string.dist_command_preview_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                isPreviewing = false
                            }
                        } else {
                            Toast.makeText(context, resources.getString(R.string.dist_error_no_model), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = selectedModel != null && enabledWorkersListPreview.isNotEmpty() &&
                        effectiveLayerResult != null && !isPreviewing
                ) {
                    if (isPreviewing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Info, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dist_show_command))
                }
            }
            OutlinedButton(
                onClick = {
                    walkthroughTargets?.recordEvent("distributed.master")
                    navController.navigate(Screen.NetworkVisualization.route)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.dist_view_network_status),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
