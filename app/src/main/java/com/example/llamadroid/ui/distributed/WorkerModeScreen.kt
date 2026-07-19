package com.example.llamadroid.ui.distributed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.zIndex
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.DistributedMode
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Info
import com.example.llamadroid.R
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.util.SystemStats
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import android.view.WindowManager
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown

/**
 * Worker mode screen - run rpc-server to contribute compute resources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerModeScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    val isRunning by DistributedService.isRunning.collectAsState()
    val localIp by DistributedService.localIp.collectAsState()
    val workerPort by DistributedService.workerPort.collectAsState()
    val workerRamMB by DistributedService.workerRamMB.collectAsState()
    val connectionCount by DistributedService.connectionCount.collectAsState()
    
    // System Monitor for RAM Card
    val systemMonitor = remember { SystemMonitor(context) }
    val stats by systemMonitor.observeStats().collectAsState(initial = SystemStats(0, 0, 0f, 0f))
    
    // Get device name (try user-set name first, fallback to model)
    val deviceName = remember { 
        try {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) { null } ?: android.os.Build.MODEL ?: "Unknown Device"
    }
    
    // Get device memory info
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val availableRamMB = (memInfo.availMem / (1024 * 1024)).toInt()
    val totalRamMB = (memInfo.totalMem / (1024 * 1024)).toInt()
    
    // Safety buffer: Leave at least 512MB or 10% of total RAM for the OS
    val maxSafeRamMB = (totalRamMB - 512).coerceAtLeast((totalRamMB * 0.9).toInt())
    
    // Initialize slider with current value or safe default
    var ramSliderValue by remember { mutableFloatStateOf(workerRamMB.toFloat().coerceIn(256f, maxSafeRamMB.toFloat())) }
    var ramTextValue by remember { mutableStateOf(workerRamMB.toString()) }  // For text input
    var threadsValue by remember { mutableIntStateOf(4) }
    var enableCache by remember { mutableStateOf(false) }
    
    // QR Code generation
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Web Monitor State
    var masterIp by remember { mutableStateOf("") }
    var masterPort by remember { mutableStateOf("8080") }
    var showWebMonitor by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }
    
    LaunchedEffect(isRunning, localIp, workerPort) {
        if (isRunning && localIp != null) {
            withContext(Dispatchers.Default) {
                val connectionString = "$localIp:$workerPort"
                qrBitmap = generateQrCode(connectionString, 200)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dist_worker_mode)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiwix_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status indicator with connection count
            StatusCard(isRunning = isRunning, connectionCount = connectionCount, ip = localIp, port = workerPort, deviceName = deviceName)
            
            // Memory Stats Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
            
            // RAM Configuration
            if (!isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dist_ram_to_share),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // RAM with text input (synced with slider)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${ramSliderValue.toInt()} ${stringResource(R.string.agent_unit_mb)}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ramTextValue,
                                onValueChange = { newValue ->
                                    ramTextValue = newValue
                                    newValue.toIntOrNull()?.let { ram ->
                                        val clamped = ram.coerceIn(256, maxSafeRamMB)
                                        ramSliderValue = clamped.toFloat()
                                    }
                                },
                                label = { Text(stringResource(R.string.agent_unit_mb)) },
                                singleLine = true,
                                modifier = Modifier.width(100.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        
                        Slider(
                            value = ramSliderValue,
                            onValueChange = { 
                                ramSliderValue = it
                                ramTextValue = it.toInt().toString()
                            },
                            valueRange = 256f..maxSafeRamMB.toFloat().coerceAtLeast(256f),
                            steps = ((maxSafeRamMB - 256) / 256).coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Text(
                            text = stringResource(R.string.dist_total_avail_ram, availableRamMB, totalRamMB),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = stringResource(R.string.dist_worker_ram_budget_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Threads setting
                        Text(
                            text = stringResource(R.string.dist_threads_count, threadsValue),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Slider(
                            value = threadsValue.toFloat(),
                            onValueChange = { threadsValue = it.toInt() },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Cache toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dist_enable_local_cache),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.dist_local_cache_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableCache,
                                onCheckedChange = { enableCache = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { DistributedService.clearWorkerCache(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dist_clear_worker_cache))
                        }
                    }
                }
            }
            
            // Connection Info (Merged into StatusCard conceptually check below)
            // Keeping separated QR card for now but simplified logic
            
            // QR Code for Master to scan
            if (isRunning && localIp != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.dist_scan_to_connect),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // QR Code
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            qrBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.dashboard_qr_for, "$localIp:$workerPort"),
                                    modifier = Modifier
                                        .size(180.dp)
                                        .padding(12.dp)
                                )
                            } ?: Box(
                                modifier = Modifier.size(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = stringResource(R.string.dist_worker_tip_master),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        
                        Text(
                            text = "$localIp:$workerPort",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                             modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Master WebUI Monitor (Always active foreground view)
            // if (isRunning) {  <-- Removed check, now always visible
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.dist_web_monitor),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        if (!showWebMonitor) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.dist_web_monitor_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = masterIp,
                                    onValueChange = { masterIp = it },
                                    label = { Text(stringResource(R.string.dist_master_ip)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = masterPort,
                                    onValueChange = { masterPort = it },
                                    label = { Text(stringResource(R.string.port_label)) },
                                    modifier = Modifier.width(90.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { 
                                    showWebMonitor = true 
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = masterIp.isNotBlank()
                            ) {
                                Text(stringResource(R.string.dist_open_monitor))
                            }
                        } else {
                            // Active Monitor View (Inline Mode)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Keep Screen On Logic (Inline)
                            DisposableEffect(Unit) {
                                fun Context.findActivity(): Activity? = when (this) {
                                    is Activity -> this
                                    is ContextWrapper -> baseContext.findActivity()
                                    else -> null
                                }
                                val activity = context.findActivity()
                                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                onDispose {
                                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            }
                            
                            // Web Controls (Inline)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$masterIp:$masterPort",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                Row {
                                    // Expand Button
                                    IconButton(onClick = { isFullScreen = true }) {
                                        Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.action_full_screen))
                                    }
                                    IconButton(onClick = { webViewRef?.reload() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
                                    }
                                    IconButton(onClick = { 
                                        showWebMonitor = false 
                                        webViewRef?.loadUrl("about:blank") // Optional: clear to stop resources?
                                    }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                                    }
                                }
                            }
                            
                            // Inline WebView Container
                            // Only show if NOT in full screen (or keep it but empty? No, reparenting requires removing from here)
                            if (!isFullScreen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(400.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                ) {
                                    AndroidView(
                                        factory = { 
                                            // CRITICAL: Use applicationContext to avoid leaking Activity context
                                            // and to ensure WebView survives Activity recreation/backgrounding
                                            val appContext = context.applicationContext
                                            val view = webViewInstance ?: WebView(appContext).apply {
                                                layoutParams = android.view.ViewGroup.LayoutParams(
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.useWideViewPort = true
                                                settings.loadWithOverviewMode = true
                                                settings.builtInZoomControls = true
                                                settings.displayZoomControls = false
                                                
                                                // Enable mixed content if strictly needed (usually not for local IP)
                                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                
                                                webViewClient = WebViewClient()
                                                
                                                loadUrl("http://$masterIp:$masterPort")
                                                webViewRef = this
                                                webViewInstance = this // Save instance
                                            }
                                        
                                        // CRITICAL: Manual Lifecycle Management
                                        // We do NOT want Compose to pause the WebView when the Activity pauses/stops
                                        // because we want it to keep running in the background (Service is valid).
                                        // So we detach it from the parent but DO NOT call onPause() or destroy().
                                        
                                        if (view.parent != null) {
                                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                                        }
                                        
                                        // Force resume timers just in case
                                        view.resumeTimers()
                                        
                                        view
                                        },
                                        update = { view ->
                                            if (view.url == null || view.url == "about:blank") {
                                                view.loadUrl("http://$masterIp:$masterPort")
                                            }
                                        },
                                        // Disable automatic lifecycle handling to prevent pausing
                                        onRelease = { 
                                            // Do nothing here to keep it alive
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    // Hack to keep WebView alive when Activity behaves like a background process
                                    DisposableEffect(Unit) {
                                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || 
                                                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                                                // Activity is pausing, but we want WebView to keep running
                                                // We must explicitly tell it to resume timers/JS if the system paused it
                                                webViewInstance?.resumeTimers()
                                                webViewInstance?.onResume()
                                            }
                                        }
                                        val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
                                        lifecycle?.addObserver(observer)
                                        
                                        onDispose {
                                            lifecycle?.removeObserver(observer)
                                        }
                                    }
                                }
                            } else {
                                // Placeholder when full screen
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(stringResource(R.string.action_full_screen), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Start/Stop Button
            Button(
                onClick = {
                    if (isRunning) {
                        DistributedService.stopWorker(context)
                    } else {
                        DistributedService.setWorkerRam(ramSliderValue.toInt())
                        DistributedService.startWorker(
                            context = context,
                            port = 50052,
                            ramMB = ramSliderValue.toInt(),
                            threads = threadsValue,
                            enableCache = enableCache
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) stringResource(R.string.dist_stop_worker) else stringResource(R.string.dist_start_worker),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }


    // Full Screen Overlay
    if (isFullScreen && showWebMonitor) {
        var showControls by remember { mutableStateOf(false) } // Default hidden
        
        BackHandler {
            if (webViewRef?.canGoBack() == true) {
                webViewRef?.goBack()
            } else {
                isFullScreen = false
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { 
                            // Reuse existing instance if available
                             // CRITICAL: Use applicationContext here as well
                             val appContext = context.applicationContext
                             val view = webViewInstance ?: WebView(appContext)
                             
                             // CRITICAL FIX: Detach from previous parent if exists
                             // Manual Lifecycle Management for Full Screen as well
                             if (view.parent != null) {
                                 (view.parent as? android.view.ViewGroup)?.removeView(view)
                             }
                             
                             // Force resume timers just in case
                             view.resumeTimers()
                             
                             view
                        },
                        update = { view ->
                             // Ensure proper sizing
                             view.layoutParams = android.view.ViewGroup.LayoutParams(
                                 android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                 android.view.ViewGroup.LayoutParams.MATCH_PARENT
                             )
                        },
                        // Disable automatic lifecycle handling
                        onRelease = { 
                            // Do nothing here to keep it alive
                        },
                        modifier = Modifier.fillMaxSize()
                )
                
                // Overlay Controls (Collapsible)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Controls Row (Visible only when expanded)
                    if (showControls) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                    RoundedCornerShape(24.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { 
                                if (webViewRef?.canGoBack() == true) webViewRef?.goBack() 
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                            
                            IconButton(onClick = { webViewRef?.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
                            }
                            
                            IconButton(onClick = { isFullScreen = false }) {
                                Icon(Icons.Default.CloseFullscreen, contentDescription = stringResource(R.string.action_minimize))
                            }
                            
                            IconButton(onClick = { 
                                if (webViewRef?.canGoForward() == true) webViewRef?.goForward() 
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_forward))
                            }
                        }
                    }
                    
                    // Toggle Arrow
                    IconButton(
                        onClick = { showControls = !showControls },
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showControls) 
                                Icons.Filled.ExpandMore 
                            else 
                                Icons.Filled.ExpandLess,
                            contentDescription = stringResource(R.string.action_toggle_controls),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean, connectionCount: Int, ip: String?, port: Int, deviceName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                connectionCount > 0 -> MaterialTheme.colorScheme.primaryContainer // Connected
                isRunning -> MaterialTheme.colorScheme.tertiaryContainer // Listening
                else -> MaterialTheme.colorScheme.surfaceVariant // Idle
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        connectionCount > 0 -> "🔗"
                        isRunning -> "📡"
                        else -> "💤"
                    },
                    style = MaterialTheme.typography.headlineMedium
                )
                 if (isRunning && connectionCount == 0) {
                     // Add breathing animation or indicator here if desired
                 }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = when {
                        connectionCount > 0 -> stringResource(R.string.dist_master_connected)
                        isRunning -> stringResource(R.string.dist_worker_active) // "Listening..."
                        else -> stringResource(R.string.dist_ready_to_start)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        connectionCount > 0 -> stringResource(R.string.dist_receiving_layers)
                        isRunning -> "Listening on $ip:$port\nBroadcasting as: $deviceName" // Explicit feedback
                        else -> stringResource(R.string.dist_configure_worker_ram)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Generate a QR code bitmap from a string.
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
