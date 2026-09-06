package com.example.llamadroid.ui.distributed

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedMode
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.RpcWorkerStatus
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppInfoRow
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

private const val MAX_VISIBLE_RPC_LOGS = 40
private const val MAX_VISIBLE_COMMAND_CHARS = 12_000

/**
 * Soft Studio topology view for the distributed LLM route.
 *
 * The graph is intentionally a normal, full-width card layout. Cards remain readable at narrow
 * widths and the connector is drawn by Canvas so endpoint and status text never has to fit inside
 * an ASCII box. Logs and command text are both bounded before they enter a scroll owner.
 */
@Composable
fun NetworkVisualizationScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val scrollState = rememberScrollState()
    val workers by DistributedService.workers.collectAsStateWithLifecycle()
    val isRunning by DistributedService.isRunning.collectAsStateWithLifecycle()
    val mode by DistributedService.mode.collectAsStateWithLifecycle()
    val masterRamMB by DistributedService.masterRamMB.collectAsStateWithLifecycle()
    val modelLayerCount by DistributedService.modelLayerCount.collectAsStateWithLifecycle()
    val rpcLayerCount by DistributedService.rpcLayerCount.collectAsStateWithLifecycle()
    val modelSizeMB by DistributedService.modelSizeMB.collectAsStateWithLifecycle()
    val inferenceRunning by DistributedService.inferenceRunning.collectAsStateWithLifecycle()
    val transferProgress by DistributedService.transferProgress.collectAsStateWithLifecycle()
    val rpcLogs by DistributedService.rpcLogs.collectAsStateWithLifecycle()
    val lastCommand by DistributedService.lastCommand.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DistributedMasterRuntimeState.attach(context.applicationContext)
    }
    val serverState by DistributedMasterRuntimeState.state.collectAsStateWithLifecycle()

    val connectedWorkers = remember(workers) { workers.filter { it.isConnected } }
    val totalClusterRam = masterRamMB + connectedWorkers.sumOf { it.availableRamMB.coerceAtLeast(0) }
    val masterShare = if (totalClusterRam > 0) {
        (masterRamMB.toFloat() / totalClusterRam * 100f).toInt().coerceIn(0, 100)
    } else {
        100
    }
    val rpcShare = (100 - masterShare).coerceIn(0, 100)
    val masterLayers = (modelLayerCount - rpcLayerCount).coerceAtLeast(0)

    AppScreenScaffold(
        title = stringResource(R.string.net_title),
        onBack = { navController.popBackStack() }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(AppChromeDefaults.ScreenPadding)
                .walkthroughTarget("distributed.topology"),
            verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
        ) {
            RuntimeStatusCard(serverState, mode, inferenceRunning)
            TopologyGraphCard(
                workers = workers,
                isRunning = isRunning,
                masterRamMB = masterRamMB,
                masterShare = masterShare,
                masterLayers = masterLayers,
                modelSizeMB = modelSizeMB,
                totalClusterRam = totalClusterRam
            )
            if (modelLayerCount > 0) {
                ModelAllocationCard(
                    totalLayers = modelLayerCount,
                    modelSizeMB = modelSizeMB,
                    masterLayers = masterLayers,
                    rpcLayers = rpcLayerCount,
                    masterShare = masterShare,
                    rpcShare = rpcShare
                )
            }
            if (transferProgress in 1..99) TransferCard(transferProgress)
            ClusterMemoryCard(masterRamMB, totalClusterRam, workers)
            RpcLogsCard(rpcLogs)
            if (!lastCommand.isNullOrBlank()) CommandCard(lastCommand.orEmpty())
            ControlsCard(
                context = context,
                mode = mode,
                isRunning = isRunning,
                onAction = { walkthroughTargets?.recordEvent("distributed.topology") }
            )
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    serverState: ServerState,
    mode: DistributedMode,
    inferenceRunning: Boolean
) {
    val (status, tint) = when (serverState) {
        is ServerState.Running -> stringResource(R.string.net_status_online) to MaterialTheme.colorScheme.secondary
        is ServerState.Loading -> stringResource(R.string.net_status_loading) to MaterialTheme.colorScheme.tertiary
        is ServerState.Starting -> stringResource(R.string.net_status_init) to MaterialTheme.colorScheme.tertiary
        is ServerState.Error -> stringResource(R.string.net_status_error) to MaterialTheme.colorScheme.error
        ServerState.Stopped -> stringResource(R.string.net_status_offline) to MaterialTheme.colorScheme.error
    }
    AppSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NodeIcon(androidx.compose.material.icons.Icons.Default.Router, tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.net_inference_server),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tint
                )
                Text(
                    text = stringResource(R.string.net_mode, stringResource(when (mode) {
                        DistributedMode.MASTER -> R.string.dist_master_mode
                        DistributedMode.WORKER -> R.string.dist_worker_mode
                        DistributedMode.NONE -> R.string.sd_dist_disabled
                    })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (inferenceRunning) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.net_processing),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun TopologyGraphCard(
    workers: List<WorkerInfo>,
    isRunning: Boolean,
    masterRamMB: Int,
    masterShare: Int,
    masterLayers: Int,
    modelSizeMB: Long,
    totalClusterRam: Int
) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.worker_topology_graph_title),
            supporting = stringResource(R.string.worker_topology_graph_subtitle)
        )
        TopologyNodeCard(
            icon = androidx.compose.material.icons.Icons.Default.Router,
            title = stringResource(R.string.worker_topology_master_name),
            endpoint = stringResource(R.string.worker_topology_master_endpoint),
            status = if (isRunning) stringResource(R.string.net_status_online)
            else stringResource(R.string.net_status_offline),
            statusColor = if (isRunning) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.error,
            metrics = stringResource(
                R.string.worker_topology_master_metrics,
                masterRamMB,
                masterShare,
                masterLayers
            ),
            detail = null
        )
        if (workers.isEmpty()) {
            AppEmptyTopologyState()
        } else {
            ResponsiveTopologyNodes(workers.withIndex().toList()) { (index, worker) ->
                Column {
                    TopologyConnector(workerStatus(worker))
                    WorkerNodeCard(index, worker, modelSizeMB, totalClusterRam)
                }
            }
        }
    }
}

@Composable
private fun AppEmptyTopologyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                AppChromeDefaults.CompactShape
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.worker_topology_no_workers_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = stringResource(R.string.worker_topology_no_workers_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkerNodeCard(
    index: Int,
    worker: WorkerInfo,
    modelSizeMB: Long,
    totalClusterRam: Int
) {
    val status = workerStatus(worker)
    val statusText = stringResource(status.stringRes)
    val statusColor = when (status) {
        RpcWorkerStatus.ONLINE -> MaterialTheme.colorScheme.secondary
        RpcWorkerStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        RpcWorkerStatus.FAILED -> MaterialTheme.colorScheme.error
        RpcWorkerStatus.UNKNOWN, RpcWorkerStatus.NOT_SELECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val share = if (totalClusterRam > 0 && worker.isConnected) {
        (worker.availableRamMB.toFloat() / totalClusterRam * 100f).toInt().coerceIn(0, 100)
    } else 0
    val estimatedRam = if (worker.realRamUsageMB == null && modelSizeMB > 0L && share > 0) {
        "~${(share / 100f * modelSizeMB).toInt()} MB"
    } else null
    val ramDetail = worker.realRamUsageMB?.let { value ->
        stringResource(R.string.worker_topology_real_ram, formatMegabytes(value))
    } ?: estimatedRam?.let { value ->
        stringResource(R.string.worker_topology_estimated_ram, value)
    } ?: stringResource(R.string.worker_topology_estimated_ram, "—")
    val layers = worker.assignedLayers?.let { range ->
        if (range.first == range.last) range.first.toString() else "${range.first}–${range.last}"
    } ?: "—"
    val identifier = worker.savedWorkerId?.let {
        stringResource(R.string.worker_topology_saved_identifier, it)
    } ?: stringResource(R.string.worker_topology_derived_identifier)
    val lastConfirmed = worker.lastConfirmedAtMs?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    }
    val details = buildList {
        add(stringResource(R.string.worker_topology_layers, layers))
        add(stringResource(R.string.worker_topology_identifier) + ": " + identifier)
        lastConfirmed?.let { add(stringResource(R.string.worker_topology_last_confirmed, it)) }
        worker.statusDetail?.takeIf { it.isNotBlank() }?.let {
            add(stringResource(R.string.worker_topology_status_detail, it.take(160)))
        }
    }.joinToString("\n")
    TopologyNodeCard(
        icon = androidx.compose.material.icons.Icons.Default.Memory,
        title = stringResource(R.string.worker_topology_worker_name, index + 1, worker.deviceName),
        endpoint = "${worker.ip}:${worker.port}",
        status = statusText,
        statusColor = statusColor,
        metrics = stringResource(
            R.string.worker_topology_metrics,
            worker.availableRamMB,
            share,
            ramDetail
        ),
        detail = details
    )
}

@Composable
private fun TopologyNodeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    endpoint: String,
    status: String,
    statusColor: Color,
    metrics: String,
    detail: String?
) {
    MediaTopologyNode(
        title = title,
        status = status,
        fields = buildList {
            add(stringResource(R.string.worker_topology_endpoint) to endpoint)
            add(stringResource(R.string.worker_topology_memory_title) to metrics)
            detail?.let { add(stringResource(R.string.worker_topology_identifier) to it) }
        }
    )
}

@Composable
private fun TopologyConnector(status: RpcWorkerStatus) {
    val color = when (status) {
        RpcWorkerStatus.ONLINE -> MaterialTheme.colorScheme.secondary
        RpcWorkerStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        RpcWorkerStatus.FAILED -> MaterialTheme.colorScheme.error
        RpcWorkerStatus.UNKNOWN, RpcWorkerStatus.NOT_SELECTED -> MaterialTheme.colorScheme.outlineVariant
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val x = size.width / 2f
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(x, size.height / 2f))
    }
}

@Composable
private fun ModelAllocationCard(
    totalLayers: Int,
    modelSizeMB: Long,
    masterLayers: Int,
    rpcLayers: Int,
    masterShare: Int,
    rpcShare: Int
) {
    val masterRatio = if (totalLayers > 0) masterLayers.toFloat() / totalLayers else 0f
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_model_title))
        Text(
            text = stringResource(
                R.string.worker_topology_model_metrics,
                totalLayers,
                modelSizeMB,
                masterLayers,
                rpcLayers
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { masterRatio.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.tertiaryContainer
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.worker_topology_allocation_master, masterShare),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.worker_topology_allocation_workers, rpcShare),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun TransferCard(progress: Int) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_transfer_title))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary
        )
        Text(
            text = stringResource(R.string.worker_topology_transfer_progress, progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClusterMemoryCard(
    masterRamMB: Int,
    totalClusterRam: Int,
    workers: List<WorkerInfo>
) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_memory_title))
        AppInfoRow(
            label = stringResource(R.string.worker_topology_cluster_memory),
            value = "$totalClusterRam MB",
            highlight = true
        )
        AppInfoRow(
            label = stringResource(R.string.worker_topology_master_memory),
            value = "$masterRamMB MB"
        )
        workers.forEachIndexed { index, worker ->
            AppInfoRow(
                label = stringResource(R.string.worker_topology_worker_memory, index + 1),
                value = "${worker.availableRamMB} MB"
            )
        }
    }
}

@Composable
private fun RpcLogsCard(rpcLogs: List<String>) {
    val visibleLogs = rpcLogs.takeLast(MAX_VISIBLE_RPC_LOGS)
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_logs_title))
        if (visibleLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.worker_topology_logs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.worker_topology_logs_bound, visibleLogs.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .clip(AppChromeDefaults.CompactShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, AppChromeDefaults.CompactShape)
                        .padding(12.dp)
                ) {
                    Text(
                        text = visibleLogs.joinToString("\n").take(20_000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandCard(command: String) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_command_title))
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(AppChromeDefaults.CompactShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(12.dp)
            ) {
                Text(
                    text = command.take(MAX_VISIBLE_COMMAND_CHARS),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ControlsCard(
    context: Context,
    mode: DistributedMode,
    isRunning: Boolean,
    onAction: () -> Unit = {}
) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.worker_topology_controls_title))
        Button(
            onClick = {
                onAction()
                context.startService(
                    Intent(context, DistributedMasterLlamaService::class.java).apply {
                        action = DistributedMasterLlamaService.ACTION_STOP
                    }
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.net_terminate_server))
        }
        if (mode == DistributedMode.WORKER && isRunning) {
            OutlinedButton(
                onClick = {
                    onAction()
                    DistributedService.stopWorker(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.net_stop_rpc))
            }
        }
        OutlinedButton(
            onClick = {
                onAction()
                DistributedService.clearWorkers()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.net_clear_workers))
        }
    }
}

@Composable
private fun NodeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = tint.copy(alpha = 0.14f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(9.dp),
            tint = tint
        )
    }
}

private fun workerStatus(worker: WorkerInfo): RpcWorkerStatus = when {
    worker.isConnected -> RpcWorkerStatus.ONLINE
    worker.rpcStatus != RpcWorkerStatus.UNKNOWN -> worker.rpcStatus
    else -> RpcWorkerStatus.UNKNOWN
}

private val RpcWorkerStatus.stringRes: Int
    get() = when (this) {
        RpcWorkerStatus.ONLINE -> R.string.net_status_online
        RpcWorkerStatus.FAILED -> R.string.net_status_failed
        RpcWorkerStatus.CONNECTING -> R.string.net_status_connecting
        RpcWorkerStatus.NOT_SELECTED -> R.string.net_status_not_selected
        RpcWorkerStatus.UNKNOWN -> R.string.net_status_unknown
    }

private fun formatMegabytes(value: Float): String =
    String.format(java.util.Locale.getDefault(), "%.1f MB", value)
