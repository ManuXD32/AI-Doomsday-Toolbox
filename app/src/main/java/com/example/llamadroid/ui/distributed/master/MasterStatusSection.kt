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
import com.example.llamadroid.service.DistributedTransferTracker
import com.example.llamadroid.ui.distributed.HelperTransferBar
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
import com.example.llamadroid.ui.distributed.common.DistributedLogViewerDialog
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

/** The three-step "how it works" strip (shared layout from DistributedCommonUi). */
@Composable
internal fun MasterHowItWorks() {
    DistributedHowItWorksStrip(
        steps = listOf(
            stringResource(R.string.dist_ui_how_1),
            stringResource(R.string.dist_ui_how_2),
            stringResource(R.string.dist_ui_how_3)
        )
    )
}

/**
 * Status panel: idle, searching, ready (helpers and round-trip time), loading, running or error,
 * plus the planner warnings in plain words.
 */
@Composable
internal fun MasterStatusSection(s: MasterScreenState) {
    val liveWorkers by DistributedService.workers.collectAsState()
    val logs by DistributedMasterRuntimeState.logs.collectAsState()
    val transfers by DistributedTransferTracker.transfers.collectAsState()
    var showLogViewer by remember { mutableStateOf(false) }
    if (showLogViewer) {
        DistributedLogViewerDialog(
            title = stringResource(R.string.dist_ui_common_log_title),
            lines = logs,
            onDismiss = { showLogViewer = false }
        )
    }
    with(s) {
        val activeHelpers = enabledWorkers
        val rtts = activeHelpers.mapNotNull { saved ->
            liveWorkers.firstOrNull { it.ip == saved.ip && it.port == saved.port }?.rttMs
        }
        val rttText = if (rtts.isEmpty()) null else DistributedPerfText.rttLabel(context, rtts.max())
        val state = distributedRuntimeState
        val panelKind: AppStateKind
        val title: String
        var message: String?
        var actionLabel: String? = null
        var onAction: (() -> Unit)? = null
        when {
            isStarting || state is ServerState.Starting -> {
                panelKind = AppStateKind.Running
                title = stringResource(R.string.dist_ui_status_starting)
                message = stringResource(R.string.dist_ui_status_starting_desc)
            }
            state is ServerState.Loading -> {
                panelKind = AppStateKind.Running
                title = stringResource(R.string.dist_ui_status_loading, (state.progress * 100).toInt().coerceIn(0, 100))
                message = state.status.ifBlank { stringResource(R.string.dist_ui_status_loading_desc) }
            }
            state is ServerState.Running -> {
                panelKind = AppStateKind.Success
                title = stringResource(R.string.dist_ui_status_running, activeHelpers.size)
                message = listOfNotNull(
                    stringResource(R.string.dist_ui_status_running_desc, state.port),
                    rttText?.let { stringResource(R.string.dist_ui_status_rtt, it) }
                ).joinToString(" ")
            }
            state is ServerState.Error -> {
                panelKind = AppStateKind.Error
                title = stringResource(R.string.dist_ui_status_error)
                message = state.message
                actionLabel = stringResource(R.string.dist_ui_status_show_log)
                onAction = { showLogViewer = true }
            }
            activeHelpers.isEmpty() -> {
                panelKind = AppStateKind.Empty
                title = if (newDiscoveredWorkers.isNotEmpty()) {
                    stringResource(R.string.dist_ui_status_found_nearby, newDiscoveredWorkers.size)
                } else {
                    stringResource(R.string.dist_ui_status_searching)
                }
                message = stringResource(R.string.dist_ui_need_helper_first)
            }
            selectedModel == null -> {
                panelKind = AppStateKind.Blocked
                title = stringResource(R.string.dist_ui_status_helpers_ready, activeHelpers.size)
                message = stringResource(R.string.dist_ui_need_model_first)
            }
            modelProbeState is ModelProbeUiState.Checking -> {
                panelKind = AppStateKind.Running
                title = stringResource(R.string.dist_ui_status_checking)
                message = stringResource(R.string.dist_probe_checking)
            }
            modelProbeState is ModelProbeUiState.Failed -> {
                panelKind = AppStateKind.Interrupted
                title = stringResource(R.string.dist_ui_status_check_failed)
                message = stringResource(R.string.dist_ui_need_probe)
            }
            else -> {
                panelKind = AppStateKind.Success
                title = stringResource(R.string.dist_ui_status_ready, activeHelpers.size)
                message = listOfNotNull(
                    stringResource(R.string.dist_ui_status_ready_desc),
                    rttText?.let { stringResource(R.string.dist_ui_status_rtt, it) }
                ).joinToString(" ")
            }
        }
        DistributedStatusPanel(
            kind = panelKind,
            title = title,
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
            extra = {
                if (state is ServerState.Loading) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    transfers.forEach { transfer ->
                        val name = liveWorkers.firstOrNull { "${it.ip}:${it.port}" == transfer.address }
                            ?.deviceName ?: transfer.address
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        HelperTransferBar(transfer)
                    }
                }
                if (!isServerRunning) {
                    planProfile?.planWarnings.orEmpty().forEach { warning ->
                        val deviceName = warning.deviceKey?.let { key ->
                            enabledWorkers.firstOrNull { worker ->
                                DistributedDeviceRef(
                                    DistributedDeviceKind.WORKER, worker.id, "${worker.ip}:${worker.port}"
                                ).stableKey() == key
                            }?.deviceName
                        }
                        val fitsOnThisPhone = warning.type == DistributedPlanWarningType.MODEL_FITS_ON_MASTER
                        DistributedWarningRow(
                            message = DistributedPerfText.warningMessage(context, warning, deviceName),
                            actionLabel = if (fitsOnThisPhone && layerPlanning == DistributedLayerPlanning.OPTIMAL_NETWORK) {
                                stringResource(R.string.dist_ui_warn_spread_action)
                            } else null,
                            onAction = if (fitsOnThisPhone && layerPlanning == DistributedLayerPlanning.OPTIMAL_NETWORK) {
                                { updateLayerPlanning(DistributedLayerPlanning.RAM_PROPORTIONAL) }
                            } else null
                        )
                    }
                }
            }
        )
    }
}

/** Technical log: raw runtime output, collapsed and height-bounded. */
@Composable
internal fun MasterTechnicalLogSection(s: MasterScreenState) {
    val logs by DistributedMasterRuntimeState.logs.collectAsState()
    var showLogViewer by remember { mutableStateOf(false) }
    if (showLogViewer) {
        DistributedLogViewerDialog(
            title = stringResource(R.string.dist_ui_common_log_title),
            lines = logs,
            onDismiss = { showLogViewer = false }
        )
    }
    DistributedTechnicalLogSection(
        lines = logs,
        onOpenViewer = { showLogViewer = true }
    )
}
