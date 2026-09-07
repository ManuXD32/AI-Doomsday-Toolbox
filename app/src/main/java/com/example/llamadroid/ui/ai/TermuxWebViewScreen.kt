package com.example.llamadroid.ui.ai

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.example.llamadroid.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.launch

/**
 * Generic WebView screen for Termux server UIs.
 * Displays a web interface with reload button and error handling.
 * 
 * IMPORTANT: WebViews persist across navigation to keep long-running tasks alive.
 * Use the X button to close and destroy the WebView, or back to keep it running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TermuxWebViewScreen(
    navController: NavController,
    url: String,
    title: String,
    toolId: String = "none"
) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val scope = rememberCoroutineScope()
    val sshService = remember { SSHService(context) }
    val decodedUrl = remember { URLDecoder.decode(url, StandardCharsets.UTF_8.toString()) }
    val decodedTitle = remember { URLDecoder.decode(title, StandardCharsets.UTF_8.toString()) }
    
    var isLoading by remember { mutableStateOf(!WebViewHolder.exists(decodedUrl)) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    
    // Get or create persistent WebView
    val webView = remember {
        WebViewHolder.getOrCreate(
            context = context,
            url = decodedUrl,
            onLoading = { loading -> isLoading = loading },
            onError = { error ->
                hasError = error != null
                errorMessage = error ?: ""
            }
        )
    }
    
    // Update canGoBack state when webView loads
    LaunchedEffect(isLoading) {
        canGoBack = webView.canGoBack()
    }
    
    AppScreenScaffold(
        title = decodedTitle,
        subtitle = decodedUrl,
        actions = {
            IconButton(
                onClick = {
                    walkthroughTargets?.recordEvent("termux.webview")
                    if (webView.canGoBack()) {
                        webView.goBack()
                        canGoBack = webView.canGoBack()
                    }
                },
                enabled = canGoBack
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.webview_back_page),
                    tint = if (canGoBack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = {
                walkthroughTargets?.recordEvent("termux.webview")
                isLoading = true
                hasError = false
                webView.reload()
            }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.action_reload))
            }
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.Home, stringResource(R.string.webview_exit_status))
            }
            IconButton(onClick = {
                walkthroughTargets?.recordEvent("termux.webview")
                WebViewHolder.destroy(decodedUrl)
                if (toolId != "none") {
                    TermuxTools.getTool(toolId)?.let { tool ->
                        scope.launch {
                            sshService.executeCommand(tool.stopCommand)
                        }
                    }
                }
                navController.popBackStack()
            }) {
                Icon(Icons.Default.Close, stringResource(R.string.webview_close_stop), tint = MaterialTheme.colorScheme.error)
            }
        }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .walkthroughTarget("termux.webview")
        ) {
            // Persistent WebView
            AndroidView(
                factory = { _ ->
                    // Detach from any previous parent
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
                update = { _ ->
                    canGoBack = webView.canGoBack()
                }
            )
            
            // Loading indicator
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.webview_loading_title, decodedTitle))
                    }
                }
            }
            
            // Error state
            if (hasError && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(32.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.webview_connection_failed),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                errorMessage.ifBlank { stringResource(R.string.webview_connect_error) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.webview_connect_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    isLoading = true
                                    hasError = false
                                    webView.loadUrl(decodedUrl)
                                }
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
