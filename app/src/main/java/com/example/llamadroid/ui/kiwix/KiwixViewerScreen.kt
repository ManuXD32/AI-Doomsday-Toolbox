package com.example.llamadroid.ui.kiwix

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
import com.example.llamadroid.service.KiwixService
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R

/**
 * WebView-based viewer for Kiwix content served by kiwix-serve.
 * 
 * @param zimPath Optional path to auto-load a specific ZIM file
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiwixViewerScreen(navController: NavController, zimPath: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Service connection
    var kiwixService by remember { mutableStateOf<KiwixService?>(null) }
    var serviceBound by remember { mutableStateOf(false) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                kiwixService = (binder as? KiwixService.LocalBinder)?.getService()
                serviceBound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                kiwixService = null
                serviceBound = false
            }
        }
    }

    fun bindServiceIfNeeded() {
        if (serviceBound) return
        val intent = Intent(context, KiwixService::class.java)
        serviceBound = runCatching {
            // The viewer owns the explicit server startup below, so AUTO_CREATE
            // remains intentional here; unlike dashboard observation this route
            // is an active Kiwix consumer. Binding is still visible-lifecycle gated.
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun unbindService() {
        if (serviceBound) {
            runCatching { context.unbindService(serviceConnection) }
        }
        serviceBound = false
        kiwixService = null
    }

    // Bind only while the viewer is visible. A failed bind is tolerated; the
    // state collectors fall back to an inactive viewer until the next start.
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bindServiceIfNeeded()
                Lifecycle.Event.ON_STOP -> unbindService()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            bindServiceIfNeeded()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unbindService()
        }
    }
    
    // State
    val isRunning by kiwixService?.isRunning?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    val serverUrl by kiwixService?.serverUrl?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<String?>(null) }
    val installedZims by db.zimDao().getAllZims()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    
    var selectedZim by remember { mutableStateOf<ZimEntity?>(null) }
    var showZimPicker by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Kiwix") }
    
    // Auto-select ZIM if path provided
    LaunchedEffect(zimPath, installedZims) {
        if (zimPath != null && selectedZim == null) {
            // Decode path and find matching ZIM
            val decodedPath = java.net.URLDecoder.decode(zimPath, "UTF-8")
            val matchingZim = installedZims.find { it.path == decodedPath }
            if (matchingZim != null) {
                selectedZim = matchingZim
                com.example.llamadroid.util.DebugLog.log("[KIWIX] Auto-selected ZIM: ${matchingZim.title}")
            }
        }
    }
    
    // Start server with ALL installed ZIMs using library mode
    LaunchedEffect(installedZims, kiwixService, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (installedZims.isNotEmpty() && kiwixService != null && !isRunning) {
                // Start the service only while the viewer is visible.
                val intent = Intent(context, KiwixService::class.java)
                context.startForegroundService(intent)

                // Wait a bit for service to start then start server with ALL ZIMs
                kotlinx.coroutines.delay(500)
                val allZimPaths = installedZims.map { it.path }
                kiwixService?.startServer(allZimPaths)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { 
                        // Stop server before leaving
                        kiwixService?.stopServer()
                        navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.kiwix_back))
                    }
                },
                actions = {
                    // Stop server and exit button
                    if (isRunning) {
                        IconButton(onClick = { 
                            kiwixService?.stopServer()
                            // Navigate to Dashboard Home
                            navController.navigate("dashboard") {
                                popUpTo(navController.graph.startDestinationId) { inclusive = false }
                            }
                        }) {
                            Icon(Icons.Default.Close, stringResource(R.string.agent_action_stop))
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Navigation controls
            if (isRunning && serverUrl != null) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.kiwix_back)) },
                        label = { Text(stringResource(R.string.kiwix_back)) },
                        selected = false,
                        enabled = canGoBack,
                        onClick = { webView?.goBack() }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.kiwix_forward)) },
                        label = { Text(stringResource(R.string.kiwix_forward)) },
                        selected = false,
                        enabled = canGoForward,
                        onClick = { webView?.goForward() }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, stringResource(R.string.kiwix_home_btn)) },
                        label = { Text(stringResource(R.string.kiwix_home_btn)) },
                        selected = false,
                        onClick = { webView?.loadUrl(serverUrl!!) }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Refresh, stringResource(R.string.kiwix_reload_btn)) },
                        label = { Text(stringResource(R.string.kiwix_reload_btn)) },
                        selected = false,
                        onClick = { webView?.reload() }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                installedZims.isEmpty() -> {
                    // No ZIMs installed
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.kiwix_no_files))
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { navController.navigate("zim_manager") }) {
                            Text(stringResource(R.string.kiwix_browse_catalog))
                        }
                    }
                }
                !isRunning -> {
                    // Starting server
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.kiwix_starting_server))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.kiwix_loading_count, installedZims.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                serverUrl != null -> {
                    // WebView
                    KiwixWebView(
                        url = serverUrl!!,
                        onWebViewCreated = { webView = it },
                        onPageTitleChanged = { currentTitle = it },
                        onNavigationChanged = { back, forward ->
                            canGoBack = back
                            canGoForward = forward
                        }
                    )
                }
            }
        }
    }
    
    // ZIM picker dialog
    if (showZimPicker) {
        AlertDialog(
            onDismissRequest = { showZimPicker = false },
            title = { Text(stringResource(R.string.kiwix_select_zim)) },
            text = {
                Column {
                    installedZims.forEach { zim ->
                        TextButton(
                            onClick = {
                                selectedZim = zim
                                showZimPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(zim.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showZimPicker = false }) {
                    Text(stringResource(R.string.agent_action_cancel))
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KiwixWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onPageTitleChanged: (String) -> Unit,
    onNavigationChanged: (canBack: Boolean, canForward: Boolean) -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.title?.let { onPageTitleChanged(it) }
                        onNavigationChanged(view?.canGoBack() == true, view?.canGoForward() == true)
                    }
                    
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Keep all navigation within the WebView
                        return false
                    }
                }
                
                loadUrl(url)
                onWebViewCreated(this)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
