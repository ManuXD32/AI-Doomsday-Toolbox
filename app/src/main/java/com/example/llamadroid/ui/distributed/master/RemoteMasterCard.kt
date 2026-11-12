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
import com.example.llamadroid.service.DistributedLlamaArguments
import com.example.llamadroid.service.DistributedLlamaLaunchProfile
import com.example.llamadroid.service.DistributedLlamaLaunchResolver
import com.example.llamadroid.service.DistributedLlamaResolveRequest
import com.example.llamadroid.service.DistributedMasterLlamaService
import com.example.llamadroid.service.DistributedMasterRuntimeState
import com.example.llamadroid.service.DistributedPerfSettings
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
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import com.example.llamadroid.ui.distributed.common.DistributedTechnicalLogSection
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

// === Shared HTTP client helper with platform TLS validation ===
private fun createRemoteHttpClient(timeoutSec: Long = 5): okhttp3.OkHttpClient {
    return okhttp3.OkHttpClient.Builder()
        .connectTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteMasterCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    
    // ===== Server-side state =====
    val remoteEnabled by DistributedService.remoteControlEnabled.collectAsState()
    val remotePassword by DistributedService.remoteControlPassword.collectAsState()
    val remotePort by DistributedService.remoteControlPort.collectAsState()
    val remoteWhitelist by DistributedService.remoteControlWhitelist.collectAsState()
    
    // ===== Client-side state =====
    val clientConnected by DistributedService.remoteClientConnected.collectAsState()
    val clientModelsStr by DistributedService.remoteClientModelsStr.collectAsState()
    val clientStatusStr by DistributedService.remoteClientStatusStr.collectAsState()
    val currentModelFile by DistributedService.remoteClientCurrentModel.collectAsState()
    
    // Remote UI settings
    val remoteContextSize by DistributedService.remoteUIContextSize.collectAsState()
    val remoteTemperature by DistributedService.remoteUITemperature.collectAsState()
    val remoteBatchSize by DistributedService.remoteUIBatchSize.collectAsState()
    val remoteKvEnabled by DistributedService.remoteUIKvEnabled.collectAsState()
    val remoteKvK by DistributedService.remoteUIKvTypeK.collectAsState()
    val remoteKvV by DistributedService.remoteUIKvTypeV.collectAsState()
    val remoteKvReuse by DistributedService.remoteUIKvReuse.collectAsState()
    val remoteUIFlashAttention by DistributedService.remoteUIFlashAttention.collectAsState()
    val remoteParallel by DistributedService.remoteUIParallel.collectAsState()
    val remoteCacheRam by DistributedService.remoteUICacheRam.collectAsState()
    val remoteCustomFlags by DistributedService.remoteUICustomFlags.collectAsState()
    val remoteSpecEnabled by DistributedService.remoteUISpecEnabled.collectAsState()
    val remoteDraftModel by DistributedService.remoteUIDraftModel.collectAsState()
    val remoteDraftMin by DistributedService.remoteUIDraftMin.collectAsState()
    val remoteDraftMax by DistributedService.remoteUIDraftMax.collectAsState()
    val remoteDraftPMin by DistributedService.remoteUIDraftPMin.collectAsState()
    
    // Parse models from JSON - server returns {"models": [...]}
    val clientModels = remember(clientModelsStr) {
        if (clientModelsStr == null) emptyList()
        else try {
            val wrapper = JSONObject(clientModelsStr)
            val jsonArray = wrapper.getJSONArray("models")
            val list = mutableListOf<JSONObject>()
            for (i in 0 until jsonArray.length()) list.add(jsonArray.getJSONObject(i))
            list
        } catch (_: Exception) { emptyList() }
    }
    
    // Parse status from JSON
    val clientStatus = remember(clientStatusStr) {
        try { JSONObject(clientStatusStr ?: "{}") } catch (_: Exception) { null }
    }
    
    val selectedRemoteModel by DistributedService.remoteUISelectedModel.collectAsState()
    
    // Client IP and password from service state flows
    val clientIp by DistributedService.remoteClientIp.collectAsState()
    val clientPassword by DistributedService.remoteClientPassword.collectAsState()
    var clientConnecting by remember { mutableStateOf(false) }
    var clientError by remember { mutableStateOf<String?>(null) }
    var clientSwitching by remember { mutableStateOf(false) }
    var clientRestarting by remember { mutableStateOf(false) }
    var clientSpecUpdating by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showSpeculativeSettings by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf<RemoteSwitchParams?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Direct llamacpp status
    var isLlamaCppRunning by remember { mutableStateOf(false) }
    var llamaCppLoadedModel by remember { mutableStateOf<String?>(null) }
    
    val clientScope = rememberCoroutineScope()
    
    // ===== Auto-polling when connected =====
    var remoteDownloads by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var remoteWorkersList by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var remoteLogsList by remember { mutableStateOf<List<String>>(emptyList()) }
    val remoteLogsState = DistributedService.remoteLogsStr.collectAsState()
    
    // Parse logs
    LaunchedEffect(remoteLogsState.value) {
        try {
            val json = JSONObject(remoteLogsState.value ?: "{}")
            val arr = json.optJSONArray("logs") ?: org.json.JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.getLong("timestamp")))
                list.add("[$time] ${entry.getString("message")}")
            }
            remoteLogsList = list
        } catch (_: Exception) {}
    }
    
    // Auto-poll status, downloads, logs every 5s while connected
    LaunchedEffect(clientConnected) {
        if (clientConnected) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(5000)
                    try {
                        val httpClient = createRemoteHttpClient()
                        val authToken = RemoteMasterServer.hashPassword(clientPassword)
                        
                        // Refresh status
                        val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                        val sRes = httpClient.newCall(sReq).execute()
                        if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                        
                        // Check llamacpp server directly on port 8080
                        try {
                            val host = clientIp.substringBefore(":")
                            val llReq = okhttp3.Request.Builder().url("http://$host:8080/v1/models").build()
                            // Use a newly built client with short timeout for this call
                            val fastClient = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val llRes = fastClient.newCall(llReq).execute()
                            if (llRes.isSuccessful) {
                                isLlamaCppRunning = true
                                val body = llRes.body?.string()
                                val json = org.json.JSONObject(body ?: "{}")
                                val data = json.optJSONArray("data")
                                if (data != null && data.length() > 0) {
                                    val modelId = data.getJSONObject(0).optString("id")
                                    val shortName = modelId.substringAfterLast("/")
                                    llamaCppLoadedModel = shortName.ifEmpty { "Unknown" }
                                } else {
                                    llamaCppLoadedModel = "Unknown"
                                }
                            } else {
                                isLlamaCppRunning = false
                                llamaCppLoadedModel = null
                            }
                        } catch (_: Exception) {
                            isLlamaCppRunning = false
                            llamaCppLoadedModel = null
                        }
                        
                        // Refresh downloads
                        val dReq = okhttp3.Request.Builder().url("https://$clientIp/download-progress").header("X-Auth-Token", authToken).build()
                        val dRes = httpClient.newCall(dReq).execute()
                        if (dRes.isSuccessful) {
                            val progJson = JSONObject(dRes.body?.string() ?: "{}")
                            val arr = progJson.optJSONArray("downloads") ?: org.json.JSONArray()
                            val list = mutableListOf<JSONObject>()
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                            remoteDownloads = list
                            
                            if (list.any { it.optString("status") == "complete" }) {
                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                val mRes = httpClient.newCall(mReq).execute()
                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                            }
                        }
                        
                        // Refresh logs
                        val lReq = okhttp3.Request.Builder().url("https://$clientIp/logs").header("X-Auth-Token", authToken).build()
                        val lRes = httpClient.newCall(lReq).execute()
                        if (lRes.isSuccessful) lRes.body?.string()?.let { DistributedService.setRemoteLogsStr(it) }
                    } catch (_: Exception) {}
                }
            }
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // ===== Card Title =====
            Text(
                stringResource(R.string.dist_remote_master),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            // ========== SERVER SECTION (always visible) ==========
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "🖥️ " + stringResource(R.string.dist_remote_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = remoteEnabled,
                            onCheckedChange = { enabled ->
                                DistributedService.setRemoteControlEnabled(enabled)
                                if (!enabled) DistributedService.stopRemoteServer()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dist_remote_enable))
                    }
                    
                    if (remoteEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = remotePassword,
                            onValueChange = { DistributedService.setRemoteControlPassword(it) },
                            label = { Text(stringResource(R.string.dist_remote_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DraftIntTextField(
                                value = remotePort,
                                onValueChange = DistributedService::setRemoteControlPort,
                                valueRange = 1..65535,
                                label = { Text(stringResource(R.string.dist_remote_port)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = remoteWhitelist.joinToString(", "),
                            onValueChange = { newVal -> DistributedService.setRemoteControlWhitelist(newVal.split(",").map { it.trim() }.filter { it.isNotEmpty() }) },
                            label = { Text(stringResource(R.string.dist_remote_whitelist)) },
                            placeholder = { Text(stringResource(R.string.dist_remote_whitelist_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            singleLine = true
                        )
                        if (remoteWhitelist.isEmpty()) {
                            Text(
                                stringResource(R.string.dist_remote_warning_all_ips),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Start / Stop Server button
                        val remoteRunning by DistributedService.remoteControlRunning.collectAsState()
                        Button(
                            onClick = {
                                if (remoteRunning) {
                                    DistributedService.stopRemoteServer()
                                } else {
                                    DistributedService.startRemoteServer(context)
                                }
                            },
                            enabled = !remoteRunning || remotePassword.isNotBlank(),
                            colors = if (remoteRunning)
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else
                                ButtonDefaults.buttonColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (remoteRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (remoteRunning) stringResource(R.string.dist_stop_server)
                                else stringResource(R.string.dist_start_server)
                            )
                        }
                        
                        // Server-side log: shared collapsed technical-log section
                        val serverLogs by DistributedService.remoteControlLogs.collectAsState()
                        Spacer(modifier = Modifier.height(8.dp))
                        DistributedTechnicalLogSection(lines = serverLogs)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            
            // ========== CLIENT SECTION ==========
            Text(
                "📱 " + stringResource(R.string.dist_remote_client_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            if (!clientConnected) {
                // ===== Login Form =====
                OutlinedTextField(
                    value = clientIp,
                    onValueChange = { DistributedService.setRemoteClientIp(it); clientError = null },
                    label = { Text(stringResource(R.string.dist_remote_ip)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("192.168.1.xxx:8089") }
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = clientPassword,
                    onValueChange = { DistributedService.setRemoteClientPassword(it); clientError = null },
                    label = { Text(stringResource(R.string.dist_remote_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                
                if (clientError != null) {
                    Text(
                        clientError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Button(
                    onClick = {
                        if (clientIp.isBlank()) { clientError = resources.getString(R.string.dist_remote_error_empty_ip); return@Button }
                        if (clientPassword.isBlank()) { clientError = resources.getString(R.string.dist_remote_error_empty_password); return@Button }
                        clientConnecting = true
                        clientError = null
                        
                        clientScope.launch(Dispatchers.IO) {
                            try {
                                val httpClient = createRemoteHttpClient()
                                val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                
                                // Fetch status
                                val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                val sRes = httpClient.newCall(sReq).execute()
                                if (!sRes.isSuccessful) { clientError = resources.getString(R.string.dist_remote_error_connect); return@launch }
                                sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                
                                // Fetch models
                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                val mRes = httpClient.newCall(mReq).execute()
                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                                
                                DistributedService.setRemoteClientConnected(true)
                            } catch (e: Exception) {
                                clientError = resources.getString(R.string.dist_remote_error_connect)
                            } finally {
                                clientConnecting = false
                            }
                        }
                    },
                    enabled = !clientConnecting,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    if (clientConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.dist_remote_connect))
                }
            } else {
                // ========== CONNECTED STATE - TABBED LAYOUT ==========
                
                // --- Status Header ---
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Pulsing green dot
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f, targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ), label = "pulseAlpha"
                                )
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawCircle(color = Color(0xFF4CAF50).copy(alpha = alpha))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.dist_remote_connected_to, clientIp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            // Disconnect button
                            IconButton(
                                onClick = {
                                    DistributedService.setRemoteClientConnected(false)
                                    DistributedService.setRemoteClientStatusStr(null)
                                    DistributedService.setRemoteClientModelsStr(null)
                                    DistributedService.setRemoteLogsStr(null)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dist_remote_disconnect), modifier = Modifier.size(18.dp))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Current model name + status chip
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val displayModel = llamaCppLoadedModel ?: currentModelFile
                            Text(
                                displayModel ?: stringResource(R.string.dist_remote_no_model_loaded),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val isRunning = isLlamaCppRunning || clientStatus?.optBoolean("running", false) == true
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRunning) Color(0xFF4CAF50).copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    if (isRunning) stringResource(R.string.dist_remote_status_running) else stringResource(R.string.dist_remote_status_stopped),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // RAM bar
                        val ramUsed = clientStatus?.optLong("ramUsedMB", 0L) ?: 0L
                        val ramTotal = clientStatus?.optLong("ramTotalMB", 1L) ?: 1L
                        val ramFraction = if (ramTotal > 0) (ramUsed.toFloat() / ramTotal.toFloat()) else 0f
                        Text(stringResource(R.string.dist_remote_ram_usage, "${ramUsed}MB", "${ramTotal}MB"), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = { ramFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (ramFraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        
                        // Storage bar
                        val availableSpace = clientStatus?.optString("availableSpaceFormatted", "?") ?: "?"
                        val totalSpace = clientStatus?.optString("totalSpaceFormatted", "?") ?: "?"
                        val availableBytes = clientStatus?.optLong("availableSpaceBytes", 0L) ?: 0L
                        val totalBytesSpace = clientStatus?.optLong("totalSpaceBytes", 1L) ?: 1L
                        val storageUsedFraction = if (totalBytesSpace > 0) 1f - (availableBytes.toFloat() / totalBytesSpace.toFloat()) else 0f
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.dist_remote_storage_info, availableSpace, totalSpace), style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(
                            progress = { storageUsedFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (storageUsedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // --- Tab Row ---
                val tabTitles = listOf(
                    stringResource(R.string.dist_remote_tab_control),
                    stringResource(R.string.dist_remote_tab_settings),
                    stringResource(R.string.dist_remote_tab_models),
                    stringResource(R.string.dist_remote_tab_system)
                )
                val tabIcons = listOf(
                    Icons.Default.PlayArrow,
                    Icons.Default.Settings,
                    Icons.Default.List,
                    Icons.Default.Build
                )
                
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1) },
                            icon = {
                                if (index == 2 && remoteDownloads.any { it.optString("status") == "downloading" }) {
                                    BadgedBox(badge = {
                                        Badge { Text("${remoteDownloads.count { it.optString("status") == "downloading" }}") }
                                    }) {
                                        Icon(tabIcons[index], contentDescription = null)
                                    }
                                } else {
                                    Icon(tabIcons[index], contentDescription = null)
                                }
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // ========== TAB 0: CONTROL ==========
                when (selectedTab) {
                    0 -> {
                        // Model selector dropdown
                        var modelExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedRemoteModel ?: stringResource(R.string.dist_remote_select_model),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.dist_remote_available_models)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                clientModels.forEach { modelJson ->
                                    val filename = modelJson.optString("filename", "")
                                    val sizeFormatted = modelJson.optString("sizeFormatted", "")
                                    val isCurrent = filename == currentModelFile
                                    if (filename.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isCurrent) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Column {
                                                        Text(filename, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                                        if (sizeFormatted.isNotEmpty()) Text(sizeFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }
                                            },
                                            onClick = { DistributedService.setRemoteUISelectedModel(filename); modelExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Switch Model button
                        Button(
                            onClick = {
                                val ctx = remoteContextSize.toIntOrNull() ?: 4096
                                val batch = remoteBatchSize.toIntOrNull() ?: 512
                                val tmp = remoteTemperature.toFloatOrNull() ?: 0.7f
                                val reuse = remoteKvReuse.toIntOrNull() ?: 0
                                showSwitchConfirm = RemoteSwitchParams(selectedRemoteModel!!, ctx, batch, tmp, remoteKvEnabled, remoteKvK, remoteKvV, reuse)
                            },
                            enabled = selectedRemoteModel != null && selectedRemoteModel != currentModelFile && !clientSwitching,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (clientSwitching) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dist_remote_switching))
                            } else {
                                Text(stringResource(R.string.dist_remote_switch))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Restart + Stop buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    clientRestarting = true
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                            val jsonBody = JSONObject().apply {
                                                put("model", currentModelFile ?: "")
                                                put("contextSize", remoteContextSize.toIntOrNull() ?: 2048)
                                                put("batchSize", remoteBatchSize.toIntOrNull() ?: 512)
                                                put("temperature", remoteTemperature.toFloatOrNull() ?: 0.5f)
                                                put("kvCacheEnabled", remoteKvEnabled)
                                                put("kvCacheTypeK", remoteKvK)
                                                put("kvCacheTypeV", remoteKvV)
                                                put("kvCacheReuse", remoteKvReuse.toIntOrNull() ?: 0)
                                                put("parallel", remoteParallel.toIntOrNull() ?: 1)
                                                put("cacheRam", remoteCacheRam.toIntOrNull() ?: 0)
                                                put("customFlags", remoteCustomFlags)
                                                put("flashAttention", remoteUIFlashAttention)
                                            }
                                            val req = okhttp3.Request.Builder()
                                                .url("https://$clientIp/restart")
                                                .header("X-Auth-Token", authToken)
                                                .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()
                                            httpClient.newCall(req).execute()
                                            kotlinx.coroutines.delay(2000)
                                            val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                            val sRes = httpClient.newCall(sReq).execute()
                                            if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                        } catch (e: Exception) {
                                            com.example.llamadroid.util.DebugLog.log("Remote restart error: ${e.message}")
                                        } finally { clientRestarting = false }
                                    }
                                },
                                enabled = !clientSwitching && !clientRestarting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (clientRestarting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                                Text(stringResource(R.string.dist_remote_restart_server))
                            }
                            
                            Button(
                                onClick = {
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                            val req = okhttp3.Request.Builder()
                                                .url("https://$clientIp/stop")
                                                .header("X-Auth-Token", authToken)
                                                .post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                                .build()
                                            httpClient.newCall(req).execute()
                                        } catch (e: Exception) {
                                            com.example.llamadroid.util.DebugLog.log("Remote stop error: ${e.message}")
                                        }
                                    }
                                },
                                enabled = !clientSwitching,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.dist_remote_stop_server), color = MaterialTheme.colorScheme.onError)
                            }
                        }
                        
                        // Launch command viewer
                        val launchCmd = clientStatus?.optString("lastCommand", "") ?: ""
                        if (launchCmd.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            var showCmd by remember { mutableStateOf(true) }
                            val cmdClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showCmd = !showCmd },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.dist_remote_command_title), style = MaterialTheme.typography.titleSmall)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (showCmd) {
                                                IconButton(onClick = {
                                                    cmdClipboard.setText(androidx.compose.ui.text.AnnotatedString(launchCmd))
                                                    android.widget.Toast.makeText(context, resources.getString(R.string.dist_command_copied), android.widget.Toast.LENGTH_SHORT).show()
                                                }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Icon(if (showCmd) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                                        }
                                    }
                                    if (showCmd) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            color = Color.Black,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 200.dp)
                                        ) {
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                Text(
                                                    launchCmd,
                                                    color = Color.Green,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(8.dp)
                                                        .verticalScroll(rememberScrollState())
                                                        .horizontalScroll(rememberScrollState())
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // ========== TAB 1: SETTINGS ==========
                    1 -> {
                        // Context/Batch/Temp
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = remoteContextSize,
                                onValueChange = { DistributedService.setRemoteUIContextSize(it.filter { char -> char.isDigit() }) },
                                label = { Text(stringResource(R.string.dist_remote_context_size), overflow = TextOverflow.Ellipsis, maxLines = 1) },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                            OutlinedTextField(
                                value = remoteTemperature,
                                onValueChange = { DistributedService.setRemoteUITemperature(it) },
                                label = { Text(stringResource(R.string.dist_remote_temperature), overflow = TextOverflow.Ellipsis, maxLines = 1) },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = remoteBatchSize,
                            onValueChange = { DistributedService.setRemoteUIBatchSize(it.filter { char -> char.isDigit() }) },
                            label = { Text(stringResource(R.string.dist_batch_size)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // KV Cache
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = remoteKvEnabled, onCheckedChange = { DistributedService.setRemoteUIKvEnabled(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_kv_enable))
                        }
                        if (remoteKvEnabled) {
                            DistributedKvTypeSelector(
                                title = stringResource(R.string.dist_cache_type_k),
                                selected = remoteKvK,
                                onSelected = DistributedService::setRemoteUIKvTypeK
                            )
                            DistributedKvTypeSelector(
                                title = stringResource(R.string.dist_cache_type_v),
                                selected = remoteKvV,
                                onSelected = DistributedService::setRemoteUIKvTypeV
                            )
                            OutlinedTextField(value = remoteKvReuse, onValueChange = { DistributedService.setRemoteUIKvReuse(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_remote_kv_reuse)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        FlashAttentionRow(
                            checked = remoteUIFlashAttention,
                            onCheckedChange = { DistributedService.setRemoteUIFlashAttention(it) }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        ParallelCacheRamRow(
                            parallel = remoteParallel,
                            cacheRam = remoteCacheRam,
                            onParallelChange = { DistributedService.setRemoteUIParallel(it.filter { char -> char.isDigit() }) },
                            onCacheRamChange = { DistributedService.setRemoteUICacheRam(it.filter { char -> char.isDigit() }) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CustomFlagsField(
                            value = remoteCustomFlags,
                            onValueChange = { DistributedService.setRemoteUICustomFlags(it) }
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Speculative Decoding
                        Text(stringResource(R.string.dist_remote_speculative), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = remoteSpecEnabled, onCheckedChange = { DistributedService.setRemoteUISpecEnabled(it) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_spec_enable))
                        }
                        if (remoteSpecEnabled) {
                            var draftExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = draftExpanded, onExpandedChange = { draftExpanded = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                OutlinedTextField(value = remoteDraftModel ?: stringResource(R.string.dist_speculative_select_draft), onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.dist_speculative_draft_model)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = draftExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(expanded = draftExpanded, onDismissRequest = { draftExpanded = false }) {
                                    clientModels.forEach { modelJson ->
                                        val filename = modelJson.optString("filename", "")
                                        if (filename.isNotEmpty() && filename != selectedRemoteModel) {
                                            DropdownMenuItem(text = { Text(filename) }, onClick = { DistributedService.setRemoteUIDraftModel(filename); draftExpanded = false })
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = remoteDraftMin, onValueChange = { DistributedService.setRemoteUIDraftMin(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_speculative_draft_min)) }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = remoteDraftMax, onValueChange = { DistributedService.setRemoteUIDraftMax(it.filter { char -> char.isDigit() }) }, label = { Text(stringResource(R.string.dist_speculative_draft_max)) }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                        }
                        Button(
                            onClick = {
                                clientSpecUpdating = true
                                clientScope.launch(Dispatchers.IO) {
                                    try {
                                        val body = org.json.JSONObject().apply {
                                            put("enabled", remoteSpecEnabled)
                                            if (remoteSpecEnabled) {
                                                put("draftModel", remoteDraftModel ?: "")
                                                put("draftMin", remoteDraftMin.toIntOrNull() ?: 0)
                                                put("draftMax", remoteDraftMax.toIntOrNull() ?: 3)
                                                put("draftPMin", remoteDraftPMin.toFloatOrNull() ?: 0.0f)
                                            }
                                        }
                                        val httpClient = createRemoteHttpClient()
                                        val req = okhttp3.Request.Builder()
                                            .url("https://$clientIp/speculative")
                                            .header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword))
                                            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                            .build()
                                        httpClient.newCall(req).execute()
                                    } catch (e: Exception) {
                                        com.example.llamadroid.util.DebugLog.log("Remote speculative error: ${e.message}")
                                    } finally { clientSpecUpdating = false }
                                }
                            },
                            enabled = !clientSpecUpdating && (!remoteSpecEnabled || remoteDraftModel != null),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            if (clientSpecUpdating) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)); Text(stringResource(R.string.dist_remote_spec_updating)) }
                            else Text(stringResource(R.string.dist_remote_apply_speculative))
                        }
                    }

                    // ========== TAB 2: MODELS ==========
                    2 -> {
                        // Model list with sizes and delete
                        Text(stringResource(R.string.dist_remote_available_models), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        
                        var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
                        var deleteInProgress by remember { mutableStateOf(false) }
                        
                        clientModels.forEach { modelJson ->
                            val filename = modelJson.optString("filename", "")
                            val sizeFormatted = modelJson.optString("sizeFormatted", "")
                            val isCurrentModel = filename == currentModelFile
                            if (filename.isNotEmpty()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(filename, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isCurrentModel) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(sizeFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isCurrentModel) Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_active), tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                    IconButton(onClick = { showDeleteConfirm = filename }, enabled = !isCurrentModel && !deleteInProgress, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dist_remote_delete_model), tint = if (isCurrentModel) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        
                        // Delete confirmation dialog
                        if (showDeleteConfirm != null) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = null },
                                title = { Text(stringResource(R.string.dist_remote_delete_model)) },
                                text = { Text(stringResource(R.string.dist_remote_delete_confirm, showDeleteConfirm!!)) },
                                confirmButton = {
                                    Button(onClick = {
                                        val filenameToDelete = showDeleteConfirm!!
                                        showDeleteConfirm = null
                                        deleteInProgress = true
                                        clientScope.launch(Dispatchers.IO) {
                                            try {
                                                val httpClient = createRemoteHttpClient()
                                                val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                                val delBody = JSONObject().put("filename", filenameToDelete).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                                val delReq = okhttp3.Request.Builder().url("https://$clientIp/delete-model").header("X-Auth-Token", authToken).post(delBody).build()
                                                httpClient.newCall(delReq).execute()
                                                kotlinx.coroutines.delay(500)
                                                val mReq = okhttp3.Request.Builder().url("https://$clientIp/models").header("X-Auth-Token", authToken).build()
                                                val mRes = httpClient.newCall(mReq).execute()
                                                if (mRes.isSuccessful) mRes.body?.string()?.let { DistributedService.setRemoteClientModelsStr(it) }
                                                val sReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                                val sRes = httpClient.newCall(sReq).execute()
                                                if (sRes.isSuccessful) sRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                                            } catch (e: Exception) { com.example.llamadroid.util.DebugLog.log("Remote delete error: ${e.message}") }
                                            finally { deleteInProgress = false }
                                        }
                                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.onError) }
                                },
                                dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text(stringResource(R.string.action_cancel)) } }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Download from HuggingFace
                        Text(stringResource(R.string.dist_remote_download_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        var downloadUrl by remember { mutableStateOf("") }
                        var downloadStarting by remember { mutableStateOf(false) }
                        var downloadError by remember { mutableStateOf<String?>(null) }
                        
                        OutlinedTextField(value = downloadUrl, onValueChange = { downloadUrl = it; downloadError = null }, label = { Text(stringResource(R.string.dist_remote_download_url)) }, placeholder = { Text(stringResource(R.string.dist_remote_download_url_hint), style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (downloadError != null) Text(downloadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        
                        Button(
                            onClick = {
                                downloadStarting = true; downloadError = null
                                clientScope.launch(Dispatchers.IO) {
                                    try {
                                        val httpClient = createRemoteHttpClient(10)
                                        val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                        val dlBody = JSONObject().put("url", downloadUrl).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                        val dlReq = okhttp3.Request.Builder().url("https://$clientIp/download").header("X-Auth-Token", authToken).post(dlBody).build()
                                        val dlRes = httpClient.newCall(dlReq).execute()
                                        val resBody = dlRes.body?.string()
                                        if (dlRes.isSuccessful) downloadUrl = ""
                                        else downloadError = try { JSONObject(resBody ?: "").optString("error", "Download failed") } catch (_: Exception) { "Download failed (${dlRes.code})" }
                                    } catch (e: Exception) { downloadError = e.message ?: "Connection error" }
                                    finally { downloadStarting = false }
                                }
                            },
                            enabled = downloadUrl.isNotBlank() && !downloadStarting,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            if (downloadStarting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                            Text(stringResource(R.string.dist_remote_download_start))
                        }
                        
                        // Active downloads
                        if (remoteDownloads.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            remoteDownloads.forEach { dl ->
                                val dlFilename = dl.optString("filename", "")
                                val dlProgress = dl.optDouble("progress", 0.0).toFloat()
                                val dlStatus = dl.optString("status", "downloading")
                                val dlSpeed = dl.optString("speedFormatted", "")
                                val dlPercent = (dlProgress * 100).toInt()
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (dlStatus) { "complete" -> MaterialTheme.colorScheme.primaryContainer; "error" -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(dlFilename, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(when (dlStatus) { "complete" -> stringResource(R.string.dist_remote_download_complete); "error" -> stringResource(R.string.dist_remote_download_error); "cancelled" -> stringResource(R.string.dist_remote_download_cancel); else -> "$dlPercent% • $dlSpeed" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (dlStatus == "downloading") {
                                                IconButton(onClick = {
                                                    clientScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val httpClient = createRemoteHttpClient()
                                                            val cancelBody = JSONObject().put("filename", dlFilename).toString().toRequestBody("application/json".toMediaTypeOrNull())
                                                            val cancelReq = okhttp3.Request.Builder().url("https://$clientIp/cancel-download").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(cancelBody).build()
                                                            httpClient.newCall(cancelReq).execute()
                                                        } catch (_: Exception) {}
                                                    }
                                                }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dist_remote_download_cancel), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                        if (dlStatus == "downloading" && dlProgress > 0f) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(progress = { dlProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // ========== TAB 3: SYSTEM ==========
                    3 -> {
                        // Worker Management
                        Text(stringResource(R.string.dist_remote_workers_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        
                        var workerIpInput by remember { mutableStateOf("") }
                        var workerPortInput by remember { mutableStateOf("50052") }
                        var workerRamInput by remember { mutableStateOf("4096") }
                        var workerLoading by remember { mutableStateOf(false) }
                        
                        // Fetch workers on first open of System tab
                        LaunchedEffect(Unit) {
                            if (remoteWorkersList.isEmpty()) {
                                kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    try {
                                        val httpClient = createRemoteHttpClient()
                                        val req = okhttp3.Request.Builder().url("https://$clientIp/workers").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).build()
                                        val res = httpClient.newCall(req).execute()
                                        if (res.isSuccessful) {
                                            val wrapper = JSONObject(res.body?.string() ?: "{}")
                                            val arr = wrapper.optJSONArray("workers") ?: JSONArray()
                                            val list = mutableListOf<JSONObject>()
                                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                                            remoteWorkersList = list
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        
                        remoteWorkersList.forEach { workerJson ->
                            val wIp = workerJson.optString("ip"); val wPort = workerJson.optInt("port", 50052)
                            val wName = workerJson.optString("deviceName", "Worker"); val wEnabled = workerJson.optBoolean("isEnabled", true)
                            val wRam = workerJson.optInt("availableRamMB", 0)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$wIp:$wPort", style = MaterialTheme.typography.bodyMedium)
                                    Text("$wName · ${wRam}MB RAM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = wEnabled, onCheckedChange = { newEnabled ->
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", wIp); put("port", wPort); put("enabled", newEnabled) }.toString()
                                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/toggle-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(body).build()
                                            httpClient.newCall(req).execute()
                                            remoteWorkersList = remoteWorkersList.map { w -> if (w.optString("ip") == wIp && w.optInt("port") == wPort) JSONObject(w.toString()).apply { put("isEnabled", newEnabled) } else w }
                                        } catch (_: Exception) {}
                                    }
                                })
                                IconButton(onClick = {
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", wIp); put("port", wPort) }.toString()
                                            val bdy = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/remove-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(bdy).build()
                                            httpClient.newCall(req).execute()
                                            remoteWorkersList = remoteWorkersList.filter { w -> !(w.optString("ip") == wIp && w.optInt("port") == wPort) }
                                        } catch (_: Exception) {}
                                    }
                                }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = workerIpInput, onValueChange = { workerIpInput = it }, label = { Text(stringResource(R.string.label_ip)) }, modifier = Modifier.weight(2f), singleLine = true)
                            OutlinedTextField(value = workerPortInput, onValueChange = { workerPortInput = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.port_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(value = workerRamInput, onValueChange = { workerRamInput = it.filter { c -> c.isDigit() } }, label = { Text(stringResource(R.string.label_ram_mb)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true)
                        Button(
                            onClick = {
                                if (workerIpInput.isNotBlank()) {
                                    workerLoading = true
                                    clientScope.launch(Dispatchers.IO) {
                                        try {
                                            val httpClient = createRemoteHttpClient()
                                            val jsonBody = JSONObject().apply { put("ip", workerIpInput.trim()); put("port", workerPortInput.toIntOrNull() ?: 50052); put("ramMB", workerRamInput.toIntOrNull() ?: 4096) }.toString()
                                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                                            val req = okhttp3.Request.Builder().url("https://$clientIp/add-worker").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).post(body).build()
                                            val addRes = httpClient.newCall(req).execute()
                                            if (addRes.isSuccessful) {
                                                val wReq = okhttp3.Request.Builder().url("https://$clientIp/workers").header("X-Auth-Token", RemoteMasterServer.hashPassword(clientPassword)).build()
                                                val wRes = httpClient.newCall(wReq).execute()
                                                if (wRes.isSuccessful) {
                                                    val wrapper = JSONObject(wRes.body?.string() ?: "{}")
                                                    val arr = wrapper.optJSONArray("workers") ?: JSONArray()
                                                    val list = mutableListOf<JSONObject>()
                                                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                                                    remoteWorkersList = list
                                                }
                                                workerIpInput = ""
                                            }
                                        } catch (_: Exception) {}
                                        finally { workerLoading = false }
                                    }
                                }
                            },
                            enabled = workerIpInput.isNotBlank() && !workerLoading,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            if (workerLoading) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(8.dp)) }
                            Text(stringResource(R.string.dist_remote_add_worker))
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Server Logs
                        var showLogs by remember { mutableStateOf(false) }
                        Button(
                            onClick = { showLogs = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_remote_view_logs))
                        }
                        
                        if (showLogs) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { showLogs = false },
                                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                            ) {
                                Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(stringResource(R.string.dist_remote_server_logs_title), style = MaterialTheme.typography.titleLarge)
                                            Row {
                                                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                                val logsCopiedStr = stringResource(R.string.dist_remote_logs_copied)
                                                IconButton(onClick = {
                                                    clientScope.launch(Dispatchers.IO) {
                                                        try {
                                                            val httpClient = createRemoteHttpClient()
                                                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                                                            val lReq = okhttp3.Request.Builder().url("https://$clientIp/clear-logs").header("X-Auth-Token", authToken).post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                                                            val lRes = httpClient.newCall(lReq).execute()
                                                            if (lRes.isSuccessful) kotlinx.coroutines.withContext(Dispatchers.Main) { DistributedService.setRemoteLogsStr(null) }
                                                        } catch (_: Exception) {}
                                                    }
                                                }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dist_remote_clear_logs)) }
                                                IconButton(onClick = {
                                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(remoteLogsList.joinToString("\n")))
                                                    android.widget.Toast.makeText(ctx, logsCopiedStr, android.widget.Toast.LENGTH_SHORT).show()
                                                }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                                                IconButton(onClick = { showLogs = false }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                                            }
                                        }
                                        HorizontalDivider()
                                        Surface(color = Color.Black, modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp), shape = RoundedCornerShape(8.dp)) {
                                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                                            LaunchedEffect(remoteLogsList.size) {
                                                if (remoteLogsList.isNotEmpty()) {
                                                    val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1
                                                    if (isAtBottom || listState.layoutInfo.totalItemsCount == 0) listState.animateScrollToItem(remoteLogsList.size - 1)
                                                }
                                            }
                                            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                                items(remoteLogsList.size) { i ->
                                                    Text(remoteLogsList[i], color = Color.Green, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // end when(selectedTab)
            }
        }
    }
    
    // Switch confirmation dialog
    if (showSwitchConfirm != null) {
        val params = showSwitchConfirm!!
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = null },
            title = { Text(stringResource(R.string.dist_remote_switch)) },
            text = { Text(resources.getString(R.string.dist_remote_switch_confirm, params.model)) },
            confirmButton = {
                Button(onClick = {
                    showSwitchConfirm = null
                    clientSwitching = true
                    clientScope.launch(Dispatchers.IO) {
                        try {
                            val httpClient = createRemoteHttpClient()
                            val authToken = RemoteMasterServer.hashPassword(clientPassword)
                            
                            // First, stop the current server
                            val stopReq = okhttp3.Request.Builder()
                                .url("https://$clientIp/stop")
                                .header("X-Auth-Token", authToken)
                                .post(JSONObject().toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            try { httpClient.newCall(stopReq).execute() } catch (_: Exception) { }
                            kotlinx.coroutines.delay(1000)
                            
                            // Then switch to the new model
                            val jsonBody = JSONObject().apply {
                                put("model", params.model)
                                put("contextSize", params.contextSize)
                                put("batchSize", params.batchSize)
                                put("temperature", params.temperature.toDouble())
                                put("kvCacheEnabled", params.kvEnabled)
                                put("kvCacheTypeK", params.kvK)
                                put("kvCacheTypeV", params.kvV)
                            }.toString()
                            val body = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
                            val req = okhttp3.Request.Builder()
                                .url("https://$clientIp/switch")
                                .header("X-Auth-Token", authToken)
                                .post(body)
                                .build()
                            val res = httpClient.newCall(req).execute()
                            if (res.isSuccessful) {
                                kotlinx.coroutines.delay(2000)
                                val statusReq = okhttp3.Request.Builder().url("https://$clientIp/status").header("X-Auth-Token", authToken).build()
                                val statusRes = httpClient.newCall(statusReq).execute()
                                if (statusRes.isSuccessful) statusRes.body?.string()?.let { DistributedService.setRemoteClientStatusStr(it) }
                            }
                        } catch (e: Exception) { com.example.llamadroid.util.DebugLog.log("Remote switch error: ${e.message}") }
                        finally { clientSwitching = false }
                    }
                }) { Text(stringResource(R.string.action_yes)) }
            },
            dismissButton = { TextButton(onClick = { showSwitchConfirm = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}


data class RemoteSwitchParams(
    val model: String,
    val contextSize: Int,
    val batchSize: Int,
    val temperature: Float,
    val kvEnabled: Boolean,
    val kvK: String,
    val kvV: String,
    val kvReuse: Int
)
