package com.example.llamadroid.ui.distributed

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.service.DistributedMode
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.RpcWorkerStatus
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo

// Matrix-style color palette
private val MatrixGreen = Color(0xFF00FF41)
private val MatrixDarkGreen = Color(0xFF008F11)
private val MatrixBg = Color(0xFF0D0D0D)
private val MatrixBgSecondary = Color(0xFF1A1A1A)
private val MatrixBorder = Color(0xFF003B00)
private val MatrixRed = Color(0xFFFF3333)
private val MatrixCyan = Color(0xFF00FFFF)
private val MatrixYellow = Color(0xFFFFFF00)

/**
 * Network Visualization Screen - Hacker/Matrix style
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkVisualizationScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Collect distributed service states
    val workers by DistributedService.workers.collectAsStateWithLifecycle()
    val isRunning by DistributedService.isRunning.collectAsStateWithLifecycle()
    val mode by DistributedService.mode.collectAsStateWithLifecycle()
    val masterRamMB by DistributedService.masterRamMB.collectAsStateWithLifecycle()
    val modelLayerCount by DistributedService.modelLayerCount.collectAsStateWithLifecycle()
    val rpcLayerCount by DistributedService.rpcLayerCount.collectAsStateWithLifecycle()
    val modelSizeMB by DistributedService.modelSizeMB.collectAsStateWithLifecycle()
    val inferenceRunning by DistributedService.inferenceRunning.collectAsStateWithLifecycle()
    val transferProgress by DistributedService.transferProgress.collectAsStateWithLifecycle()
    val lastCommand by DistributedService.lastCommand.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) { DistributedMasterRuntimeState.attach(context.applicationContext) }
    val serverState by DistributedMasterRuntimeState.state.collectAsStateWithLifecycle()
    
    val masterLayers = modelLayerCount - rpcLayerCount
    val totalConnectedRam = masterRamMB + workers.filter { it.isConnected }.sumOf { it.availableRamMB }
    
    // Calculate proportions for display
    // If calculating based on RAM
    val masterProportion = if (totalConnectedRam > 0) {
        (masterRamMB.toFloat() / totalConnectedRam * 100).toInt()
    } else {
        100
    }
    
    // Blinking cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val cursorVisible by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )
    
    Scaffold(
        containerColor = MatrixBg,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "> " + stringResource(R.string.net_title),
                        fontFamily = FontFamily.Monospace,
                        color = MatrixGreen
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, 
                            contentDescription = stringResource(R.string.net_back),
                            tint = MatrixGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MatrixBgSecondary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MatrixBg)
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Terminal Header
            TerminalBox(title = stringResource(R.string.net_system_status)) {
                val (statusText, statusColor) = when (serverState) {
                    is ServerState.Running -> stringResource(R.string.net_status_online) to MatrixGreen
                    is ServerState.Loading -> stringResource(R.string.net_status_loading) to MatrixYellow
                    is ServerState.Starting -> stringResource(R.string.net_status_init) to MatrixYellow
                    is ServerState.Error -> stringResource(R.string.net_status_error) to MatrixRed
                    ServerState.Stopped -> stringResource(R.string.net_status_offline) to MatrixRed
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.net_inference_server) + " ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MatrixDarkGreen
                    )
                    Text(
                        text = statusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                
                Text(
                    text = stringResource(R.string.net_mode, mode.name),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MatrixDarkGreen
                )
                
                if (inferenceRunning) {
                    Text(
                        text = stringResource(R.string.net_processing) + if (cursorVisible > 0.5f) "_" else " ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                }
            }
            
            // Network Topology
            TerminalBox(title = stringResource(R.string.net_topology)) {
                // Master node
                Text(
                    text = "┌─────────────────────────────────────┐",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixBorder
                )
                Row {
                    Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                    Text(
                        text = stringResource(R.string.net_master_node),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MatrixCyan,
                        modifier = Modifier.weight(1f)
                    )
                    Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                }
                Row {
                    Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                    
                    val masterEstMb = if (modelSizeMB > 0 && totalConnectedRam > 0) {
                        (masterProportion / 100f * modelSizeMB).toInt()
                    } else 0
                    
                    val ramText = if (masterEstMb > 0) " | EST: ~${masterEstMb}MB" else ""
                    
                    Text(
                        text = "RAM: ${masterRamMB}MB | LOAD: $masterProportion%$ramText",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixGreen,
                        modifier = Modifier.weight(1f)
                    )
                    Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                }
                Text(
                    text = "└─────────────────────────────────────┘",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixBorder
                )
                
                if (workers.isNotEmpty()) {
                    // Connection lines
                    Text(
                        text = "         │",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (inferenceRunning) MatrixGreen else MatrixDarkGreen
                    )
                    Text(
                        text = "    ─────┼─────" + "─────┬─────".repeat((workers.size - 1).coerceAtLeast(0)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (inferenceRunning) MatrixGreen else MatrixDarkGreen
                    )
                    
                    // Worker nodes
                    workers.forEachIndexed { index, worker ->
                        val layersPerWorker = if (workers.isNotEmpty()) rpcLayerCount / workers.size else 0
                        
                        Text(
                            text = "┌───────────────────────────────┐",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MatrixBorder
                        )
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            Text(
                                text = "[WORKER_${index}] 📱 ${worker.deviceName}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MatrixYellow,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            Text(
                                text = "${worker.ip}:${worker.port}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = MatrixDarkGreen,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            val workerProp = if (totalConnectedRam > 0 && worker.isConnected) {
                                (worker.availableRamMB.toFloat() / totalConnectedRam * 100).toInt()
                            } else {
                                0
                            }
                            
                            // Determine RAM display (Real > Est)
                            val ramUsageText = if (worker.realRamUsageMB != null) {
                                " | REAL: ${worker.realRamUsageMB}MB"
                            } else {
                                val workerEstMb = if (modelSizeMB > 0 && worker.isConnected) {
                                    (workerProp / 100f * modelSizeMB).toInt()
                                } else 0
                                if (workerEstMb > 0) " | EST: ~${workerEstMb}MB" else ""
                            }
                            
                            val statusColor = when (worker.rpcStatus) {
                                RpcWorkerStatus.ONLINE -> MatrixGreen
                                RpcWorkerStatus.FAILED -> MatrixRed
                                RpcWorkerStatus.CONNECTING -> MatrixCyan
                                RpcWorkerStatus.UNKNOWN, RpcWorkerStatus.NOT_SELECTED -> MatrixDarkGreen
                            }
                            val statusText = when (worker.rpcStatus) {
                                RpcWorkerStatus.ONLINE -> stringResource(R.string.net_status_online)
                                RpcWorkerStatus.FAILED -> stringResource(R.string.net_status_failed)
                                RpcWorkerStatus.CONNECTING -> stringResource(R.string.net_status_connecting)
                                RpcWorkerStatus.NOT_SELECTED -> stringResource(R.string.net_status_not_selected)
                                RpcWorkerStatus.UNKNOWN -> stringResource(R.string.net_status_unknown)
                            }
                            
                            // Use Cyan for Real RAM usage to distinguish from Estimate
                            val usageColor = if (worker.realRamUsageMB != null) MatrixCyan else statusColor
                            
                            Text(
                                text = "RAM: ${worker.availableRamMB}MB | STATUS: $statusText | LOAD: $workerProp%$ramUsageText",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = usageColor,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Text(
                            text = "└───────────────────────────────┘",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MatrixBorder
                        )
                    }
                } else {
                    Text(
                        text = "\n" + stringResource(R.string.net_no_workers),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixRed
                    )
                }
            }
            
            // Model Info
            if (modelLayerCount > 0) {
                TerminalBox(title = stringResource(R.string.net_model_metrics)) {
                    Text(
                        text = "TOTAL_LAYERS: $modelLayerCount",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Text(
                        text = "MODEL_SIZE:   ${modelSizeMB}MB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Text(
                        text = "LOCAL_LAYERS: $masterLayers",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixCyan
                    )
                    Text(
                        text = "RPC_LAYERS:   $rpcLayerCount",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixYellow
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // ASCII progress bar
                    val masterRatio = if (modelLayerCount > 0) masterLayers.toFloat() / modelLayerCount else 0f
                    val barWidth = 30
                    val masterBlocks = (masterRatio * barWidth).toInt()
                    val workerBlocks = barWidth - masterBlocks
                    
                    Text(
                        text = stringResource(R.string.net_layer_distribution),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixDarkGreen
                    )
                    Text(
                        text = "[" + "█".repeat(masterBlocks) + "░".repeat(workerBlocks) + "]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Row {
                        Text(
                            text = " " + stringResource(R.string.net_local_label),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MatrixCyan
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.net_rpc_label) + " ",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MatrixYellow
                        )
                    }
                }
            }
            
            // Transfer progress
            if (transferProgress > 0 && transferProgress < 100) {
                TerminalBox(title = stringResource(R.string.net_transfer)) {
                    val progressBlocks = (transferProgress / 100f * 30).toInt()
                    Text(
                        text = "SYNCING: [${"▓".repeat(progressBlocks)}${"░".repeat(30 - progressBlocks)}] $transferProgress%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixYellow
                    )
                }
            }
            
            // Memory stats
            TerminalBox(title = stringResource(R.string.net_memory_allocation)) {
                Text(
                    text = "TOTAL_CLUSTER_RAM: ${totalConnectedRam}MB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MatrixGreen
                )
                Text(
                    text = "MASTER_ALLOCATION: ${masterRamMB}MB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixDarkGreen
                )
                workers.forEachIndexed { i, w ->
                    Text(
                        text = "WORKER_${i}_ALLOC:   ${w.availableRamMB}MB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixDarkGreen
                    )
                }
            }
            
            // Server Launch Command
            if (lastCommand != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TerminalBox(title = "LAUNCH_COMMAND") {
                    Text(
                        text = lastCommand!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixCyan
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Control buttons
            TerminalBox(title = stringResource(R.string.net_controls)) {
                Button(
                    onClick = {
                        val intent = Intent(context, DistributedMasterLlamaService::class.java).apply {
                            action = DistributedMasterLlamaService.ACTION_STOP
                        }
                        context.startService(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MatrixRed.copy(alpha = 0.3f),
                        contentColor = MatrixRed
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.net_terminate_server),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (mode == DistributedMode.WORKER && isRunning) {
                    Button(
                        onClick = {
                            DistributedService.stopWorker(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MatrixYellow.copy(alpha = 0.3f),
                            contentColor = MatrixYellow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.net_stop_rpc),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
                
                OutlinedButton(
                    onClick = {
                        DistributedService.clearWorkers()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MatrixDarkGreen
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MatrixDarkGreen)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.net_clear_workers),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TerminalBox(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MatrixBorder, RoundedCornerShape(4.dp))
            .background(MatrixBgSecondary)
    ) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MatrixBorder.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fake terminal buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixRed.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixYellow.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixGreen.copy(alpha = 0.7f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "// $title",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MatrixDarkGreen
            )
        }
        
        // Content
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}
