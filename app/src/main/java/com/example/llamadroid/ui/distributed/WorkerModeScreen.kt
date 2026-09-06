package com.example.llamadroid.ui.distributed

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.zIndex
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.service.DistributedService
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import com.example.llamadroid.R
import com.example.llamadroid.util.MemoryTelemetry
import com.example.llamadroid.util.MemoryTelemetrySnapshot
import android.webkit.WebView
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import com.example.llamadroid.service.WorkerMemoryBudget
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * Worker mode screen - run rpc-server to contribute compute resources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerModeScreen(navController: NavController) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    
    val isRunning by DistributedService.isRunning.collectAsStateWithLifecycle()
    val localIp by DistributedService.localIp.collectAsStateWithLifecycle()
    val workerPort by DistributedService.workerPort.collectAsStateWithLifecycle()
    val connectionCount by DistributedService.connectionCount.collectAsStateWithLifecycle()

    // One shared, IO-only stream serves both worker cards and the foreground notification.
    // The screen keeps the flow reference stable and lets each card collect independently.
    val memoryTelemetry = remember(context) { MemoryTelemetry.observe(context) }
    val workerControls = remember {
        WorkerControlState(DistributedService.workerRamMB.value.toLong().coerceAtLeast(0L))
    }
    
    // Get device name (try user-set name first, fallback to model)
    val deviceName = remember { 
        try {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) { null } ?: android.os.Build.MODEL ?: "Unknown Device"
    }
    
    
    // QR Code generation
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(qrBitmap) {
        onDispose {
            qrBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
    
    // Web Monitor State
    var masterIp by remember { mutableStateOf("") }
    var masterPort by remember { mutableStateOf("8080") }
    var showWebMonitor by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }

    // WebView owns native resources and a renderer process. Keep it reusable while this
    // route is alive, but pause it while the route is backgrounded and destroy it on route
    // disposal. The updated-state reference ensures disposal sees the latest instance.
    val currentWebView by rememberUpdatedState(webViewInstance)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    currentWebView?.onPause()
                    currentWebView?.pauseTimers()
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    currentWebView?.resumeTimers()
                    currentWebView?.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentWebView?.let { view ->
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.webViewClient = WebViewClient()
                    view.webChromeClient = null
                    view.removeAllViews()
                    view.destroy()
                }
            }
            webViewRef = null
            webViewInstance = null
        }
    }
    
    LaunchedEffect(isRunning, localIp, workerPort) {
        if (isRunning && localIp != null) {
            withContext(Dispatchers.Default) {
                val connectionString = "$localIp:$workerPort"
                qrBitmap = generateQrCode(connectionString, 200)
            }
        } else {
            qrBitmap = null
        }
    }
    
    AppScreenScaffold(
        title = stringResource(R.string.dist_worker_mode),
        onBack = { navController.popBackStack() }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
                .walkthroughTarget("distributed.worker"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status indicator with connection count
            StatusCard(isRunning = isRunning, connectionCount = connectionCount, ip = localIp, port = workerPort, deviceName = deviceName)
            
            WorkerMemoryCard(
                memoryTelemetry = memoryTelemetry,
                isRunning = isRunning,
                appliedBudgetFlow = DistributedService.workerMemoryBudget,
                controls = workerControls
            )
            WorkerRamConfigurationCard(
                context = context,
                memoryTelemetry = memoryTelemetry,
                isRunning = isRunning,
                workerRamFlow = DistributedService.workerRamMB,
                controls = workerControls
            )
            
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
                                    walkthroughTargets?.recordEvent("distributed.worker")
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
                                            val monitorUrl = "http://$masterIp:$masterPort"
                                            val view = webViewInstance ?: WebView(appContext).apply {
                                                layoutParams = android.view.ViewGroup.LayoutParams(
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                configureWorkerMonitor(monitorUrl)
                                                loadUrl(monitorUrl)
                                                webViewRef = this
                                                webViewInstance = this // Save instance
                                            }
                                        
                                        // Detach before Compose reuses the same WebView for the full-screen
                                        // overlay. Route-level lifecycle handling pauses it when hidden and
                                        // destroys it once the route is disposed.
                                        
                                        if (view.parent != null) {
                                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                                        }
                                        
                                        // Ensure a resumed route can render after a previous pause.
                                        view.resumeTimers()
                                        
                                        view
                                        },
                                        update = { view ->
                                            if (view.url == null || view.url == "about:blank") {
                                                view.loadUrl("http://$masterIp:$masterPort")
                                            }
                                        },
                                        // The route-level lifecycle effect owns pause/resume and final cleanup.
                                        onRelease = { 
                                            // Keep the instance for the full-screen reparenting path.
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
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
            
            WorkerStartStopButton(
                context = context,
                memoryTelemetry = memoryTelemetry,
                isRunning = isRunning,
                controls = workerControls,
                onAction = { walkthroughTargets?.recordEvent("distributed.worker") }
            )
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
                        // The route-level lifecycle effect owns pause/resume and final cleanup.
                        onRelease = { 
                            // Keep the instance for the inline reparenting path.
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

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureWorkerMonitor(allowedUrl: String) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
            request?.url?.toString()?.let { !hasSameWebOrigin(allowedUrl, it) } ?: true

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            url?.let { !hasSameWebOrigin(allowedUrl, it) } ?: true
    }
}

private fun hasSameWebOrigin(allowedUrl: String, candidateUrl: String): Boolean {
    val allowed = runCatching { java.net.URI(allowedUrl) }.getOrNull() ?: return false
    val candidate = runCatching { java.net.URI(candidateUrl) }.getOrNull() ?: return false
    val allowedScheme = allowed.scheme?.lowercase(java.util.Locale.US) ?: return false
    val candidateScheme = candidate.scheme?.lowercase(java.util.Locale.US) ?: return false
    if (allowedScheme !in setOf("http", "https") || candidateScheme !in setOf("http", "https")) {
        return false
    }
    val allowedHost = allowed.host ?: return false
    val candidateHost = candidate.host ?: return false
    fun effectivePort(uri: java.net.URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
    return allowed.userInfo == null &&
        candidate.userInfo == null &&
        allowedScheme == candidateScheme &&
        allowedHost.equals(candidateHost, ignoreCase = true) &&
        effectivePort(allowed) == effectivePort(candidate)
}

@Composable
private fun WorkerMemoryCard(
    memoryTelemetry: StateFlow<MemoryTelemetrySnapshot>,
    isRunning: Boolean,
    appliedBudgetFlow: StateFlow<WorkerMemoryBudget>,
    controls: WorkerControlState
) {
    val memory by memoryTelemetry.collectAsStateWithLifecycle()
    val appliedBudget by appliedBudgetFlow.collectAsStateWithLifecycle()
    val budget = remember(memory.totalBytes, memory.availableBytes, controls.requestedRamMiB) {
        WorkerMemoryBudget.fromBytes(
            totalBytes = memory.totalBytes,
            availableBytes = memory.availableBytes,
            requestedMiB = controls.requestedRamMiB
        )
    }
    // Device usage stays live; the allocation already applied to a running worker stays fixed.
    val displayedBudget = budget
    val unavailableFraction = if (displayedBudget.totalMiB > 0L) {
        ((displayedBudget.totalMiB - displayedBudget.availableMiB).toFloat() /
            displayedBudget.totalMiB.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppChromeDefaults.CardShape,
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
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.dashboard_memory),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            LinearProgressIndicator(
                progress = { unavailableFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    unavailableFraction > 0.8f -> MaterialTheme.colorScheme.error
                    unavailableFraction > 0.6f -> Color(0xFFFFA726)
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            WorkerMemoryMetric(
                label = stringResource(R.string.dist_worker_memory_total),
                valueMiB = displayedBudget.totalMiB
            )
            WorkerMemoryMetric(
                label = stringResource(R.string.dist_worker_memory_available),
                valueMiB = displayedBudget.availableMiB,
                valueColor = MaterialTheme.colorScheme.primary
            )
            WorkerMemoryMetric(
                label = stringResource(R.string.dist_worker_memory_reserved),
                valueMiB = displayedBudget.reservedMiB
            )
            WorkerMemoryMetric(
                label = stringResource(R.string.dist_worker_memory_contribution),
                valueMiB = if (isRunning) appliedBudget.contributionMiB else displayedBudget.contributionMiB,
                valueColor = MaterialTheme.colorScheme.primary
            )
            if (memory.sampledAtEpochMs > 0L) {
                Text(
                    text = stringResource(R.string.worker_topology_memory_live),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WorkerRamConfigurationCard(
    context: Context,
    memoryTelemetry: StateFlow<MemoryTelemetrySnapshot>,
    isRunning: Boolean,
    workerRamFlow: StateFlow<Int>,
    controls: WorkerControlState
) {
    if (isRunning) return

    val memory by memoryTelemetry.collectAsStateWithLifecycle()
    val workerRamMB by workerRamFlow.collectAsStateWithLifecycle()
    var ramFieldFocused by remember { mutableStateOf(false) }
    val budget = remember(memory.totalBytes, memory.availableBytes, controls.requestedRamMiB) {
        WorkerMemoryBudget.fromBytes(
            totalBytes = memory.totalBytes,
            availableBytes = memory.availableBytes,
            requestedMiB = controls.requestedRamMiB
        )
    }
    val sanitizedRamMiB = budget.contributionMiB
    val sliderMaximumMiB = budget.maximumMiB.coerceAtLeast(1L)

    // A service-applied value is authoritative whenever a route is recreated or a worker stops.
    LaunchedEffect(workerRamMB, isRunning) {
        if (!isRunning && controls.requestedRamMiB != workerRamMB.toLong()) {
            controls.requestedRamMiB = workerRamMB.toLong().coerceAtLeast(0L)
            controls.ramTextValue = controls.requestedRamMiB.toString()
        }
    }

    // Available memory can shrink while stopped. Clamp the editable contribution, but leave a
    // focused draft alone until focus leaves the field. The write itself is debounced below.
    LaunchedEffect(memory, isRunning, ramFieldFocused) {
        if (!isRunning && !ramFieldFocused && memory.totalBytes > 0L) {
            controls.requestedRamMiB = sanitizedRamMiB
            controls.ramTextValue = sanitizedRamMiB.toString()
        }
    }
    LaunchedEffect(context, isRunning) {
        if (!isRunning) {
            snapshotFlow { controls.requestedRamMiB }
                .distinctUntilChanged()
                .debounce(250L)
                .collect { requestedMiB ->
                    if (!DistributedService.isRunning.value) {
                        DistributedService.setWorkerRam(
                            context = context,
                            ramMB = requestedMiB.coerceAtLeast(0L)
                                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        )
                    }
                }
        }
    }
    val latestRequestedRamMiB by rememberUpdatedState(controls.requestedRamMiB)
    DisposableEffect(Unit) {
        onDispose {
            if (!DistributedService.isRunning.value) {
                DistributedService.setWorkerRam(
                    context = context,
                    ramMB = latestRequestedRamMiB.coerceAtLeast(0L)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dist_ram_to_share),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$sanitizedRamMiB ${stringResource(R.string.agent_unit_mb)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = controls.ramTextValue,
                    onValueChange = { newValue ->
                        controls.ramTextValue = newValue
                        newValue.toLongOrNull()?.let { requested ->
                            controls.requestedRamMiB = requested.coerceAtLeast(0L)
                        }
                    },
                    label = { Text(stringResource(R.string.agent_unit_mb)) },
                    singleLine = true,
                    modifier = Modifier
                        .width(100.dp)
                        .onFocusChanged { ramFieldFocused = it.isFocused },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Slider(
                value = sanitizedRamMiB.coerceIn(0L, sliderMaximumMiB).toFloat(),
                onValueChange = { value ->
                    val requested = value.toLong().coerceIn(0L, sliderMaximumMiB)
                    controls.requestedRamMiB = requested
                    controls.ramTextValue = requested.toString()
                },
                valueRange = 0f..sliderMaximumMiB.toFloat(),
                steps = ((sliderMaximumMiB / WorkerMemoryBudget.MINIMUM_VIABLE_MIB) - 1L)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                enabled = budget.maximumMiB > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.dist_total_avail_ram,
                    memory.availableMiB.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    memory.totalMiB.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.dist_worker_memory_budget_summary,
                    budget.reservedMiB.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    budget.maximumMiB.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!budget.canLaunch) {
                Text(
                    text = stringResource(
                        R.string.dist_worker_memory_unavailable,
                        WorkerMemoryBudget.MINIMUM_VIABLE_MIB
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    text = stringResource(R.string.dist_worker_ram_budget_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = stringResource(R.string.dist_threads_count, controls.threadsValue),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = controls.threadsValue.toFloat(),
                onValueChange = { controls.threadsValue = it.toInt() },
                valueRange = 1f..8f,
                steps = 6,
                modifier = Modifier.fillMaxWidth()
            )
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
                    checked = controls.enableCache,
                    onCheckedChange = { controls.enableCache = it }
                )
            }
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

@Composable
private fun WorkerStartStopButton(
    context: Context,
    memoryTelemetry: StateFlow<MemoryTelemetrySnapshot>,
    isRunning: Boolean,
    controls: WorkerControlState,
    onAction: () -> Unit = {}
) {
    val memory by memoryTelemetry.collectAsStateWithLifecycle()
    val budget = remember(memory.totalBytes, memory.availableBytes, controls.requestedRamMiB) {
        WorkerMemoryBudget.fromBytes(
            totalBytes = memory.totalBytes,
            availableBytes = memory.availableBytes,
            requestedMiB = controls.requestedRamMiB
        )
    }
    Button(
        onClick = {
            onAction()
            if (isRunning) {
                DistributedService.stopWorker(context)
            } else {
                // Save this stopped snapshot first. The service validates the same contribution
                // again immediately before launching the native worker.
                val savedBudget = DistributedService.setWorkerRam(
                    context = context,
                    ramMB = controls.requestedRamMiB.coerceAtLeast(0L)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
                if (savedBudget.canLaunch) {
                    DistributedService.startWorker(
                        context = context,
                        port = DistributedService.RPC_DEFAULT_PORT,
                        ramMB = savedBudget.contributionMiB
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        threads = controls.threadsValue,
                        enableCache = controls.enableCache
                    )
                }
            }
        },
        enabled = isRunning || budget.canLaunch,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isRunning) stringResource(R.string.dist_stop_worker)
            else stringResource(R.string.dist_start_worker),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private class WorkerControlState(initialRamMiB: Long) {
    var requestedRamMiB by mutableLongStateOf(initialRamMiB.coerceAtLeast(0L))
    var ramTextValue by mutableStateOf(initialRamMiB.coerceAtLeast(0L).toString())
    var threadsValue by mutableIntStateOf(4)
    var enableCache by mutableStateOf(false)
}

@Composable
private fun WorkerMemoryMetric(
    label: String,
    valueMiB: Long,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(
                R.string.dist_worker_memory_value,
                valueMiB.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
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
                        isRunning -> stringResource(
                            R.string.worker_topology_worker_listening,
                            ip ?: "—",
                            port,
                            deviceName
                        )
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
