package com.example.llamadroid.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.util.AIConstants
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.service.ServerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.llamadroid.service.FileServerService
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch


@Composable
fun DashboardScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val systemMonitor = remember { SystemMonitor(context) }
    val viewModel = remember { DashboardViewModel(systemMonitor) }
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val appDatabase = remember { AppDatabase.getDatabase(context) }
    val knowledgeBaseRepository = remember { KnowledgeBaseRepository(context, appDatabase) }
    
    val stats by viewModel.stats.collectAsState()
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val contextSize by settingsRepo.contextSize.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val knowledgeBases by knowledgeBaseRepository.observeKnowledgeBases().collectAsState(initial = emptyList())
    val knowledgeSourceCount by knowledgeBaseRepository.observeSourceCount().collectAsState(initial = 0)
    val knowledgeChunkCount by knowledgeBaseRepository.observeChunkCount().collectAsState(initial = 0)
    val knowledgeErrorCount by knowledgeBaseRepository.observeErrorSourceCount().collectAsState(initial = 0)
    val knowledgePendingCount by knowledgeBaseRepository.observePendingSourceCount().collectAsState(initial = 0)
    val embeddingModelPath by settingsRepo.selectedEmbeddingModelPath.collectAsState()
    
    val isRunning = serverState is ServerState.Running
    val isStarting = serverState is ServerState.Starting
    val isLoading = serverState is ServerState.Loading
    
    
    val scrollState = rememberScrollState()
    
    AppPageBackground {
        AppContentColumn(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        AppPageHeader(
            eyebrow = "HOME",
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.ai_hub_subtitle)
        )
        
        // Server Status Card (Hero)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRunning -> Color(0xFF4CAF50)
                                    isLoading -> Color(0xFF2196F3)
                                    isStarting -> Color(0xFFFFC107)
                                    serverState is ServerState.Error -> Color(0xFFF44336)
                                    else -> Color(0xFF9E9E9E)
                                }
                            )
                    )
                    Text(
                        when (serverState) {
                            is ServerState.Stopped -> stringResource(R.string.status_stopped)
                            is ServerState.Starting -> stringResource(R.string.dashboard_starting)
                            is ServerState.Loading -> stringResource(R.string.status_loading)
                            is ServerState.Running -> stringResource(R.string.status_running)
                            is ServerState.Error -> stringResource(R.string.status_error)
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                if (isRunning) {
                    val remoteAccess by settingsRepo.remoteAccess.collectAsState()
                    val ips = remember { getDeviceIPs(context) }
                    val port = (serverState as ServerState.Running).port
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.dashboard_running_port, port),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        if (remoteAccess && ips.isNotEmpty()) {
                            Text(
                                stringResource(R.string.dashboard_connect_from),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            ips.forEach { ip ->
                                Text(
                                    "http://$ip:$port",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        } else if (!remoteAccess) {
                            Text(
                                stringResource(R.string.dashboard_local_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // Loading progress bar
                if (isLoading) {
                    val loadingState = serverState as ServerState.Loading
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            loadingState.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (loadingState.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { loadingState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF2196F3),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF2196F3),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
                
                if (serverState is ServerState.Error) {
                    Text(
                        (serverState as ServerState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                // Model info
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.llm_model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.dashboard_no_model),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1
                            )
                            // RAM Estimation
                            selectedModelPath?.let { modelPath ->
                                val modelFile = java.io.File(modelPath)
                                if (modelFile.exists()) {
                                    val modelSizeGb = modelFile.length() / (1024.0 * 1024.0 * 1024.0)
                                    // Context memory: ~2 bytes per token per layer (rough estimate)
                                    // Model weights + 20% overhead + context buffer
                                    val estimatedRam = modelSizeGb * 1.2 + (contextSize / 1024.0) * 0.1
                                    Text(
                                        stringResource(R.string.dashboard_ram_needed, String.format("%.1f", estimatedRam)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (estimatedRam > stats.freeRamGb) 
                                            MaterialTheme.colorScheme.error 
                                        else 
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (dashboardCanStartServer(serverState)) {
                        Button(
                            onClick = { viewModel.startServer(context, selectedModelPath) },
                            enabled = selectedModelPath != null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.dashboard_start_llamacpp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (dashboardCanRecoverServer(serverState)) {
                            Button(
                                onClick = { viewModel.recoverServer(context) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.dashboard_recover_server),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else if (dashboardCanStopServer(serverState)) {
                        Button(
                            onClick = { viewModel.stopServer(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_stop_server))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stopServer(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_stop_server))
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            ),
            onClick = { navController.navigate(Screen.KnowledgeBase.route) }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.kb_dashboard_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            stringResource(
                                R.string.kb_dashboard_summary,
                                knowledgeBases.size,
                                knowledgeSourceCount,
                                knowledgeChunkCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AssistChip(
                        onClick = { navController.navigate(Screen.KnowledgeBase.route) },
                        label = { Text(stringResource(R.string.kb_pending_count, knowledgePendingCount)) }
                    )
                    AssistChip(
                        onClick = { navController.navigate(Screen.KnowledgeBase.route) },
                        label = {
                            Text(
                                if (knowledgeErrorCount > 0) {
                                    stringResource(R.string.kb_errors_count, knowledgeErrorCount)
                                } else {
                                    stringResource(R.string.kb_no_errors)
                                }
                            )
                        }
                    )
                    AssistChip(
                        onClick = { navController.navigate(Screen.KnowledgeBase.route) },
                        label = {
                            Text(
                                embeddingModelPath?.substringAfterLast("/")
                                    ?: stringResource(R.string.kb_embedding_model_none),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        // QR Code Section (when server is running with LAN access enabled)
        val remoteAccess by settingsRepo.remoteAccess.collectAsState()
        if (isRunning && remoteAccess) {
            val interfaces = remember { getDeviceIPs(context) }
            if (interfaces.isNotEmpty()) {
                val port = (serverState as ServerState.Running).port
                var expanded by remember { mutableStateOf(true) }  // Show QR codes by default
                var qrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
                
                LaunchedEffect(interfaces, port) {
                    withContext(Dispatchers.Default) {
                        val bitmaps = interfaces.associate { (ifName, ip) ->
                            val url = "http://$ip:$port"
                            ip to generateQrCode(url, 200)
                        }
                        qrBitmaps = bitmaps
                    }
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.dashboard_qr_connect), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (expanded) stringResource(R.string.dashboard_qr_collapse) else stringResource(R.string.dashboard_qr_expand)
                                )
                            }
                        }
                        
                        AnimatedVisibility(visible = expanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                interfaces.forEach { (ifName, ip) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(140.dp)
                                    ) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            qrBitmaps[ip]?.let { bitmap ->
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = stringResource(R.string.dashboard_qr_for, ip),
                                                    modifier = Modifier.size(120.dp).padding(8.dp)
                                                )
                                            } ?: Box(
                                                modifier = Modifier.size(120.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                        Text(ip, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // File Server Card
        val scope = rememberCoroutineScope()
        var fileServerRunning by remember { mutableStateOf(false) }
        var fileServerUrls by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var fileServerFolderUri by remember { mutableStateOf<android.net.Uri?>(null) }
        var fileServerService by remember { mutableStateOf<FileServerService?>(null) }
        var fileServerBound by remember { mutableStateOf(false) }
        var fileServerQrExpanded by remember { mutableStateOf(false) }
        var fileServerQrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
        
        val fileServerConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as FileServerService.LocalBinder
                    fileServerService = binder.getService()
                    fileServerBound = true
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    fileServerService = null
                    fileServerBound = false
                }
            }
        }
        
        LaunchedEffect(Unit) {
            val intent = Intent(context, FileServerService::class.java)
            context.bindService(intent, fileServerConnection, Context.BIND_AUTO_CREATE)
        }
        
        LaunchedEffect(fileServerService) {
            fileServerService?.let { service ->
                launch {
                    service.isRunning.collect { fileServerRunning = it }
                }
                launch {
                    service.serverUrls.collect { urls ->
                        fileServerUrls = urls
                        // Generate QR codes
                        withContext(Dispatchers.Default) {
                            val bitmaps = urls.associate { (ifName, url) ->
                                val ip = url.substringAfter("://").substringBefore(":")
                                ip to generateQrCode(url, 200)
                            }
                            fileServerQrBitmaps = bitmaps
                        }
                    }
                }
            }
        }
        
        val folderPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                fileServerFolderUri = it
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fileServerRunning) 
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                else 
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.dashboard_file_server), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (fileServerRunning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
                
                // Folder selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dashboard_shared_folder), style = MaterialTheme.typography.labelMedium)
                        Text(
                            fileServerFolderUri?.lastPathSegment ?: stringResource(R.string.file_server_not_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        enabled = !fileServerRunning
                    ) {
                        Text(stringResource(R.string.file_server_browse))
                    }
                }
                
                // Start/Stop Button
                Button(
                    onClick = {
                        if (fileServerRunning) {
                            fileServerService?.stopServer()
                        } else {
                            fileServerFolderUri?.let { uri ->
                                context.startForegroundService(Intent(context, FileServerService::class.java))
                                fileServerService?.startServer(uri, FileServerService.DEFAULT_PORT)
                            }
                        }
                    },
                    enabled = fileServerFolderUri != null || fileServerRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (fileServerRunning) 
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else 
                        ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        if (fileServerRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (fileServerRunning) stringResource(R.string.dashboard_stop_server_btn) else stringResource(R.string.dashboard_start_server_port, AIConstants.Ports.FILE_SERVER))
                }
                
                // QR codes when running
                if (fileServerRunning && fileServerUrls.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.dashboard_scan_access_files), fontWeight = FontWeight.Medium)
                            Text(
                                fileServerUrls.firstOrNull()?.second ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { fileServerQrExpanded = !fileServerQrExpanded }) {
                            Icon(
                                if (fileServerQrExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    }
                    
                    AnimatedVisibility(visible = fileServerQrExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            fileServerUrls.forEach { (ifName, url) ->
                                val ip = url.substringAfter("://").substringBefore(":")
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(140.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        fileServerQrBitmaps[ip]?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = stringResource(R.string.dashboard_qr_for, ip),
                                                modifier = Modifier.size(120.dp).padding(8.dp)
                                            )
                                        } ?: Box(
                                            modifier = Modifier.size(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text(ip, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Kiwix Server Card
        var kiwixService by remember { mutableStateOf<com.example.llamadroid.service.KiwixService?>(null) }
        val kiwixConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    kiwixService = (binder as? com.example.llamadroid.service.KiwixService.LocalBinder)?.getService()
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    kiwixService = null
                }
            }
        }
        
        DisposableEffect(context) {
            val intent = Intent(context, com.example.llamadroid.service.KiwixService::class.java)
            context.bindService(intent, kiwixConnection, Context.BIND_AUTO_CREATE)
            onDispose {
                context.unbindService(kiwixConnection)
            }
        }
        
        val kiwixRunning by kiwixService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
        val installedZims by appDatabase.zimDao().getAllZims().collectAsState(initial = emptyList())
        var kiwixQrExpanded by remember { mutableStateOf(false) }
        var kiwixQrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
        val kiwixInterfaces = remember { getDeviceIPs(context) }
        
        LaunchedEffect(kiwixRunning) {
            if (kiwixRunning) {
                withContext(Dispatchers.Default) {
                    val bitmaps = kiwixInterfaces.associate { (ifName, ip) ->
                        val url = "http://$ip:${AIConstants.Ports.KIWIX}"
                        ip to generateQrCode(url, 200)
                    }
                    kiwixQrBitmaps = bitmaps
                }
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        stringResource(R.string.kiwix_server),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (kiwixRunning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
                
                Text(
                    stringResource(R.string.dashboard_zim_installed, installedZims.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Start/Stop Button
                Button(
                    onClick = {
                        if (kiwixRunning) {
                            kiwixService?.stopServer()
                        } else {
                            context.startForegroundService(Intent(context, com.example.llamadroid.service.KiwixService::class.java))
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                kiwixService?.startServer(installedZims.map { it.path })
                            }
                        }
                    },
                    enabled = installedZims.isNotEmpty() || kiwixRunning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (kiwixRunning) 
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else 
                        ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        if (kiwixRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (kiwixRunning) stringResource(R.string.kiwix_stop) else stringResource(R.string.kiwix_start))
                }
                
                // QR codes when running (always show - LAN always enabled)
                if (kiwixRunning && kiwixInterfaces.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.dashboard_connect_qr), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { kiwixQrExpanded = !kiwixQrExpanded }) {
                            Icon(
                                if (kiwixQrExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (kiwixQrExpanded) stringResource(R.string.dashboard_qr_collapse) else stringResource(R.string.dashboard_qr_expand)
                            )
                        }
                    }
                    
                    AnimatedVisibility(visible = kiwixQrExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            kiwixInterfaces.forEach { (ifName, ip) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(140.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        kiwixQrBitmaps[ip]?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = stringResource(R.string.dashboard_qr_for, ip),
                                                modifier = Modifier.size(120.dp).padding(8.dp)
                                            )
                                        } ?: Box(
                                            modifier = Modifier.size(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text("$ip:${AIConstants.Ports.KIWIX}", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Distributed Inference Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.dashboard_distributed_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                Text(
                    stringResource(R.string.dashboard_setup_distributed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Button(
                    onClick = { navController.navigate(com.example.llamadroid.ui.navigation.Screen.DistributedHub.route) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dashboard_setup_distributed))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        stringResource(R.string.dashboard_sd_distributed_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                Text(
                    stringResource(R.string.dashboard_sd_distributed_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { navController.navigate(Screen.SdDistributedHub.route) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dashboard_sd_distributed_action))
                }
            }
        }
        
        // Memory Stats Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.dashboard_memory),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                LinearProgressIndicator(
                    progress = { stats.ramUsagePercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        stats.ramUsagePercent > 80 -> MaterialTheme.colorScheme.error
                        stats.ramUsagePercent > 60 -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            stringResource(R.string.dashboard_ram_unit, String.format("%.1f", stats.totalRamGb - stats.freeRamGb)),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            stringResource(R.string.dashboard_ram_used),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.dashboard_ram_unit, String.format("%.1f", stats.freeRamGb)),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.dashboard_ram_free),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.dashboard_ram_unit, String.format("%.1f", stats.totalRamGb)),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            stringResource(R.string.dashboard_ram_total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Kiwix Offline Library Card
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            onClick = { navController.navigate("zim_manager") }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📚", style = MaterialTheme.typography.headlineSmall)
                }
                
                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dashboard_kiwix_library),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        if (installedZims.isEmpty()) stringResource(R.string.dashboard_offline_wikipedia)
                        else stringResource(R.string.dashboard_zim_installed, installedZims.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                
                // Action icons
                Row {
                    if (installedZims.isNotEmpty()) {
                        IconButton(onClick = { navController.navigate("kiwix_viewer") }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.action_view),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick tip
        if (selectedModelPath == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Column {
                        Text(
                            stringResource(R.string.dashboard_no_model),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stringResource(R.string.dashboard_select_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Get all IPv4 addresses from all network interfaces.
 * Returns list of (friendlyName, ipAddress) pairs.
 */
private fun getDeviceIPs(context: android.content.Context): List<Pair<String, String>> {
    val ips = mutableListOf<Pair<String, String>>()
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { ip ->
                        // Skip link-local addresses (169.254.x.x)
                        if (!ip.startsWith("169.254")) {
                            val friendlyName = when {
                                iface.name.startsWith("wlan") -> context.getString(R.string.net_type_wifi)
                                iface.name.startsWith("eth") -> context.getString(R.string.net_type_ethernet)
                                iface.name.startsWith("tun") -> context.getString(R.string.net_type_vpn)
                                iface.name.startsWith("rmnet") -> context.getString(R.string.net_type_mobile)
                                else -> iface.name
                            }
                            ips.add(Pair(friendlyName, ip))
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Ignore network enumeration errors
    }
    return ips
}

/**
 * Generate a QR code bitmap from a URL string.
 */
private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
