package com.example.llamadroid.ui.chat

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.ai.applyKeyboardAwareInsetsFix
import com.example.llamadroid.ui.ai.injectKeyboardViewportFix
import com.example.llamadroid.ui.ai.llama.RunningLlamaChatServerUi
import com.example.llamadroid.ui.ai.llama.rememberRunningLlamaChatServers
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import androidx.lifecycle.LifecycleEventObserver
import java.net.URI

// This process-wide cache intentionally uses a WebView created from the application context,
// so retaining it across navigation cannot retain an Activity.
@SuppressLint("StaticFieldLeak")
object ChatWebViewHolder {
    var webView: WebView? = null
    var isLoaded: Boolean = false
    var loadedUrl: String? = null
    @Volatile var activeOrigin: String? = null
    @Volatile var shouldReload: Boolean = false
}

internal fun llamaChatWebViewUrl(port: Int): String =
    "http://127.0.0.1:${port.coerceIn(1, 65535)}/"

internal fun isAllowedChatWebViewUrl(candidate: String?, allowedOrigin: String): Boolean = runCatching {
    val candidateUri = URI(candidate ?: return false)
    val allowedUri = URI(allowedOrigin)
    candidateUri.userInfo == null &&
        candidateUri.scheme.equals(allowedUri.scheme, ignoreCase = true) &&
        candidateUri.host.equals(allowedUri.host, ignoreCase = true) &&
        candidateUri.port == allowedUri.port
}.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(
    navController: NavController,
    serverPortOverride: Int? = null
) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsRepository = remember(context) { SettingsRepository(context.applicationContext) }
    val configuredServerPort by settingsRepository.serverPort.collectAsState()
    val runningChatServers = rememberRunningLlamaChatServers()
    var selectedServerPort by rememberSaveable(serverPortOverride) {
        mutableIntStateOf(
            resolveChatServerPort(
                routePortOverride = serverPortOverride,
                savedSelectedPort = null,
                configuredPort = configuredServerPort
            )
        )
    }
    var showServerPicker by rememberSaveable { mutableStateOf(false) }
    val selectedServer = remember(runningChatServers, selectedServerPort) {
        runningChatServerForPort(selectedServerPort, runningChatServers)
    }
    val chatUrl = remember(selectedServerPort) { llamaChatWebViewUrl(selectedServerPort) }
    val currentChatUrl by rememberUpdatedState(chatUrl)
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var isLoading by remember(chatUrl) { mutableStateOf(!ChatWebViewHolder.isLoaded || ChatWebViewHolder.loadedUrl != chatUrl) }
    var hasError by remember(chatUrl) { mutableStateOf(false) }

    fun openServerManager() {
        showServerPicker = false
        navController.navigate(Screen.LlamaServers.route) {
            launchSingleTop = true
        }
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                intent.clipData?.let { clipData ->
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } ?: intent.data?.let { arrayOf(it) }
            }
        } else null
        
        fileUploadCallback?.onReceiveValue(uris ?: arrayOf())
        fileUploadCallback = null
    }
    
    // Create or reuse WebView
    val webView = remember {
        ChatWebViewHolder.webView ?: WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            applyKeyboardAwareInsetsFix()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                safeBrowsingEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            
            ChatWebViewHolder.webView = this
        }
    }

    fun loadChatUrl() {
        ChatWebViewHolder.activeOrigin = chatUrl
        webView.stopLoading()
        ChatWebViewHolder.loadedUrl = chatUrl
        ChatWebViewHolder.isLoaded = false
        isLoading = true
        hasError = false
        webView.loadUrl(chatUrl)
    }

    LaunchedEffect(chatUrl) {
        // An attachment selected for the previous origin must never be delivered to a newly
        // selected server when its system picker returns.
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
    }

    // Check if reload was requested from navigation bar long press, or if the selected server port changed.
    LaunchedEffect(chatUrl) {
        if (ChatWebViewHolder.shouldReload || ChatWebViewHolder.loadedUrl != chatUrl) {
            ChatWebViewHolder.shouldReload = false
            loadChatUrl()
        }
    }
    
    // Set up callbacks (needs to be done each recomposition since lambdas may change)
    DisposableEffect(webView, lifecycleOwner, chatUrl) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return !isAllowedChatWebViewUrl(request?.url?.toString(), currentChatUrl)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                !isAllowedChatWebViewUrl(url, currentChatUrl)
            
            override fun onPageFinished(view: WebView?, url: String?) {
                // A retained WebView can finish an older navigation after the user selects a
                // different server. Only the current loopback origin may settle this screen.
                if (ChatWebViewHolder.activeOrigin != currentChatUrl ||
                    !isAllowedChatWebViewUrl(url, currentChatUrl)
                ) return
                view?.injectKeyboardViewportFix()
                isLoading = false
                ChatWebViewHolder.isLoaded = true
                ChatWebViewHolder.loadedUrl = url
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (ChatWebViewHolder.activeOrigin == currentChatUrl &&
                    request?.isForMainFrame == true &&
                    isAllowedChatWebViewUrl(request.url?.toString(), currentChatUrl)
                ) {
                    hasError = true
                    isLoading = false
                }
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                
                filePickerLauncher.launch(intent)
                return true
            }
            
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.confirm()
                return true
            }
        }
        
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
            ) {
                // Keep the retained WebView alive while the phone is locked or the activity pauses.
                webView.resumeTimers()
                webView.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        webView.resumeTimers()
        webView.onResume()

        // Load URL only if not already loaded
        if (!ChatWebViewHolder.isLoaded || ChatWebViewHolder.loadedUrl != chatUrl) {
            loadChatUrl()
        }
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedButton(
                        onClick = {
                            showServerPicker = true
                            walkthroughTargets?.recordEvent("chat.server_selection")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .walkthroughTarget("chat.server_selection"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(
                                text = stringResource(
                                    R.string.chat_servers_selected_summary,
                                    selectedServer?.name?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.chat_servers_current_endpoint),
                                    stringResource(R.string.chat_servers_port, selectedServerPort)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.chat_servers_choose_cd)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(stringResource(R.string.chat_servers_manage))
                            }
                        },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = ::openServerManager,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = stringResource(R.string.chat_servers_manage_cd)
                            )
                        }
                    }
                    IconButton(onClick = { loadChatUrl() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.chat_clear))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                update = { /* WebView is already configured */ }
            )

            // Loading indicator
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.status_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Error state
            if (hasError && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.chat_no_model),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.chat_load_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            loadChatUrl()
                        }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }

    if (showServerPicker) {
        ChatServerPickerDialog(
            servers = runningChatServers,
            selectedPort = selectedServerPort,
            onDismiss = { showServerPicker = false },
            onManageServers = ::openServerManager,
            onServerSelected = { server ->
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
                selectedServerPort = server.port
                showServerPicker = false
            }
        )
    }
}

@Composable
internal fun ChatServerPickerDialog(
    servers: List<RunningLlamaChatServerUi>,
    selectedPort: Int,
    onDismiss: () -> Unit,
    onManageServers: () -> Unit,
    onServerSelected: (RunningLlamaChatServerUi) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.chat_servers_picker_title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_servers_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.chat_servers_picker_current_port, selectedPort),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (servers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_servers_no_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    servers.forEach { server ->
                        val isSelected = server.port == selectedPort
                        OutlinedButton(
                            onClick = { onServerSelected(server) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_servers_port, server.port),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.chat_servers_selected)
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onManageServers,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.chat_servers_manage),
                        maxLines = 2
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = stringResource(R.string.action_cancel)
                )
            }
        }
    )
}
