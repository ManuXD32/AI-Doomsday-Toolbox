package com.example.llamadroid.ui.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.ui.ai.applyKeyboardAwareInsetsFix
import com.example.llamadroid.ui.ai.injectKeyboardViewportFix
import androidx.lifecycle.LifecycleEventObserver

// Singleton WebView holder to persist across navigation
object ChatWebViewHolder {
    var webView: WebView? = null
    var isLoaded: Boolean = false
    var loadedUrl: String? = null
    @Volatile var shouldReload: Boolean = false
}

internal fun llamaChatWebViewUrl(port: Int): String =
    "http://127.0.0.1:${port.coerceIn(1, 65535)}/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(
    navController: NavController,
    serverPortOverride: Int? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsRepository = remember(context) { SettingsRepository(context.applicationContext) }
    val configuredServerPort by settingsRepository.serverPort.collectAsState()
    val serverPort = serverPortOverride ?: configuredServerPort
    val chatUrl = remember(serverPort) { llamaChatWebViewUrl(serverPort) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var isLoading by remember(chatUrl) { mutableStateOf(!ChatWebViewHolder.isLoaded || ChatWebViewHolder.loadedUrl != chatUrl) }
    var hasError by remember { mutableStateOf(false) }
    
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
        ChatWebViewHolder.webView ?: WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            applyKeyboardAwareInsetsFix()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            
            ChatWebViewHolder.webView = this
        }
    }

    fun loadChatUrl() {
        ChatWebViewHolder.loadedUrl = chatUrl
        ChatWebViewHolder.isLoaded = false
        isLoading = true
        hasError = false
        webView.loadUrl(chatUrl)
    }

    // Check if reload was requested from navigation bar long press, or if the configured server port changed.
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
                return false
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.injectKeyboardViewportFix()
                isLoading = false
                ChatWebViewHolder.isLoaded = true
                ChatWebViewHolder.loadedUrl = url ?: chatUrl
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
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
            
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(context)
                newWebView.settings.javaScriptEnabled = true
                newWebView.settings.domStorageEnabled = true
                
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
            
            override fun onCloseWindow(window: WebView?) {}
            
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
    
    Box(modifier = Modifier.fillMaxSize()) {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        
        // Dropdown menu button at top center (small arrow that expands to reload option)
        var showDropdown by remember { mutableStateOf(false) }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
        ) {
            // Small arrow button
            IconButton(
                onClick = { showDropdown = !showDropdown },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (showDropdown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            // Dropdown menu
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { showDropdown = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_clear)) },
                    onClick = {
                        showDropdown = false
                        loadChatUrl()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                )
            }
        }
    }
}
