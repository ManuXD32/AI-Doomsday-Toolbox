package com.example.llamadroid.worlds

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mutable per-WebView state shared by the Compose layer and the WebView callbacks. */
private class WorldHostState {
    val holder = WorldPageHolder()
    val diag = WorldDiagnostics()
    var web: WebView? = null
    var desiredHtml: String? = null
    var loadedHtml: String? = null
    var pageUrl: String? = null
    var pageLoaded by mutableStateOf(false)
    var crashed by mutableStateOf(false)
    var viewWidth by mutableIntStateOf(0)
    var viewHeight by mutableIntStateOf(0)
    var revision by mutableIntStateOf(0)
    var firstErrorLogged = false
    var pendingFile by mutableStateOf<WorldPendingFile?>(null)
    var showPanel by mutableStateOf(false)

    /** Loads the desired page once the view has a real size. */
    fun maybeLoad() {
        val view = web ?: return
        val html = desiredHtml ?: return
        if (crashed || html == loadedHtml || view.width <= 0 || view.height <= 0) return
        loadedHtml = html
        pageLoaded = false
        val url = holder.publish(html)
        pageUrl = url
        view.loadUrl(url)
    }
}

/**
 * Hosts a generated three.js page offline. The page itself is served from the interceptor at an
 * unguessable `https://worlds.adt.local/__page/<token>/index.html` URL; `worlds.adt.local` assets
 * come from `assets/worlds/`, known CDN URLs are rewritten to bundled files, everything else gets
 * an empty 404 so no request ever reaches the real network.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun WorldWebView(
    pageHtml: String,
    modifier: Modifier = Modifier,
    onReady: (() -> Unit)? = null,
    onChromeHidden: ((Boolean) -> Unit)? = null,
) {
    val state = remember { WorldHostState() }
    // Diagnostics change on WebView IO threads; bump the Compose state on the main thread.
    state.diag.onChange = { state.web?.post { state.revision++ } }
    val hostContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedMsg = stringResource(R.string.worlds_ui_file_saved)
    val saveFailedMsg = stringResource(R.string.worlds_ui_file_save_failed)
    val currentOnChromeHidden by rememberUpdatedState(onChromeHidden)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val file = state.pendingFile
        state.pendingFile = null
        if (uri != null && file != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        hostContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.bytes) } != null
                    }.getOrDefault(false)
                }
                Toast.makeText(hostContext, if (ok) savedMsg else saveFailedMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(state.pendingFile) {
        state.pendingFile?.let { saveLauncher.launch(it.name) }
    }
    Box(modifier = modifier) {
        if (state.crashed) {
            Text(
                text = stringResource(R.string.worlds_diag_crashed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { context ->
                    WebView(context).also { web ->
                        state.web = web
                        configure(web)
                        web.webViewClient = WorldWebViewClient(context.applicationContext, state, onReady)
                        web.addJavascriptInterface(
                            WorldHostBridge(
                                onSave = { file -> if (state.pendingFile == null) state.pendingFile = file },
                                onDiagnostics = { state.showPanel = true },
                                onChromeHidden = { hidden -> currentOnChromeHidden?.invoke(hidden) },
                            ),
                            "ADTHost",
                        )
                        web.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                state.diag.recordConsole(
                                    message.messageLevel().name, message.message().orEmpty(),
                                    message.sourceId(), message.lineNumber(),
                                )
                                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && !state.firstErrorLogged) {
                                    state.firstErrorLogged = true
                                    DebugLog.log("World page console error: " + message.message().orEmpty().take(160))
                                }
                                return true
                            }
                        }
                        web.setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        web.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            state.viewWidth = v.width
                            state.viewHeight = v.height
                            state.maybeLoad()
                        }
                        state.desiredHtml = pageHtml
                        web.post { state.maybeLoad() }
                    }
                },
                onRelease = { web -> releaseWeb(state, web) },
                update = { web ->
                    if (state.crashed) return@AndroidView
                    state.web = web
                    if (state.desiredHtml != pageHtml) {
                        state.desiredHtml = pageHtml
                        state.maybeLoad()
                    }
                },
            )
        }
    }
    if (state.showPanel) {
        WorldDiagnosticsDialog(state = state, onDismiss = { state.showPanel = false })
    }
    DisposableEffect(Unit) {
        onDispose {
            state.diag.onChange = null
        }
    }
}

/**
 * The `ADTHost` object injected into the page. Calls arrive on a WebView binder thread; they are
 * validated there and results are posted to the main thread. The page is untrusted model output, so
 * the surface is exactly three methods and [WorldDownloads] restricts what `saveFile` accepts.
 */
private class WorldHostBridge(
    private val onSave: (WorldPendingFile) -> Unit,
    private val onDiagnostics: () -> Unit,
    private val onChromeHidden: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun saveFile(name: String?, mime: String?, base64: String?) {
        val file = WorldDownloads.accept(name.orEmpty(), mime.orEmpty(), base64.orEmpty()) ?: return
        main.post { onSave(file) }
    }

    @JavascriptInterface
    fun openDiagnostics() {
        main.post { onDiagnostics() }
    }

    @JavascriptInterface
    fun setChromeHidden(hidden: Boolean) {
        main.post { onChromeHidden(hidden) }
    }
}

/** Teardown runs from AndroidView.onRelease, once per instance, after the view is detached. */
private fun releaseWeb(state: WorldHostState, web: WebView) {
    if (state.web === web) state.web = null
    try {
        web.removeJavascriptInterface("ADTHost")
        web.stopLoading()
        web.onPause()
        web.loadUrl("about:blank")
        web.destroy()
    } catch (_: Exception) {
        // already torn down
    }
}

@Composable
private fun WorldDiagnosticsDialog(state: WorldHostState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val refresh = state.revision // recompose when diagnostics change
    val yes = stringResource(R.string.worlds_diag_yes)
    val no = stringResource(R.string.worlds_diag_no)
    val none = stringResource(R.string.worlds_diag_none)
    val loadedLine = stringResource(R.string.worlds_diag_page_loaded, if (state.pageLoaded) yes else no)
    val sizeLine = stringResource(R.string.worlds_diag_view_size, state.viewWidth, state.viewHeight)
    val servedLine = stringResource(R.string.worlds_diag_served, state.diag.servedCount)
    val problemsTitle = stringResource(R.string.worlds_diag_problems)
    val consoleTitle = stringResource(R.string.worlds_diag_console)
    val copiedMsg = stringResource(R.string.worlds_diag_copied)
    val problems = state.diag.problemList()
    val console = state.diag.consoleList()
    val report = buildString {
        appendLine(loadedLine); appendLine(sizeLine); appendLine(servedLine)
        appendLine(); appendLine(problemsTitle)
        if (problems.isEmpty()) appendLine(none) else problems.forEach { appendLine(it) }
        appendLine(); appendLine(consoleTitle)
        if (console.isEmpty()) appendLine(none) else console.forEach { appendLine(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.worlds_diag_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(loadedLine, style = MaterialTheme.typography.bodyMedium)
                Text(sizeLine, style = MaterialTheme.typography.bodyMedium)
                Text(servedLine, style = MaterialTheme.typography.bodyMedium)
                Text(problemsTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                if (problems.isEmpty()) Text(none, style = MaterialTheme.typography.bodySmall)
                problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(consoleTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                if (console.isEmpty()) Text(none, style = MaterialTheme.typography.bodySmall)
                console.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("world diagnostics", report))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.worlds_diag_copy), maxLines = 1) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.worlds_diag_close), maxLines = 1) }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(web: WebView) {
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportZoom(false)
        builtInZoomControls = false
        mediaPlaybackRequiresUserGesture = true
    }
    web.overScrollMode = WebView.OVER_SCROLL_NEVER
    web.setBackgroundColor(0xFF000000.toInt())
}

private class WorldWebViewClient(
    private val appContext: Context,
    private val state: WorldHostState,
    private val onReady: (() -> Unit)?,
) : WebViewClient() {
    // Only the main-frame navigation to the exact current page URL is allowed.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val allowed = request.isForMainFrame && request.url.toString() == state.holder.currentUrl()
        if (!allowed) state.diag.recordProblem("blocked-nav", request.url.toString())
        return !allowed
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && url == state.pageUrl) {
            state.pageLoaded = true
            onReady?.invoke()
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        DebugLog.log("World WebView renderer gone (crash=${detail.didCrash()})")
        state.crashed = true
        state.pageLoaded = false
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val urlString = url.toString()
        state.holder.htmlFor(urlString)?.let { html ->
            state.diag.recordServed()
            return WebResourceResponse(
                "text/html", "utf-8", 200, "OK", CORS_HEADERS, ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
        val path = if (url.host.equals(WORLDS_HOST, ignoreCase = true)) {
            url.path.orEmpty()
        } else {
            WorldPageBuilder.rewriteUrl(urlString).also { if (it == null) state.diag.recordProblem("blocked", urlString) }
        }
        val served = path?.let { serveAsset(it) }
        if (served != null) {
            state.diag.recordServed()
            return served
        }
        if (path != null) state.diag.recordProblem("404", urlString)
        return notFound()
    }

    private fun serveAsset(path: String): WebResourceResponse? {
        val clean = path.trimStart('/')
        // Defense in depth: only plain relative paths below assets/worlds/.
        if (clean.isEmpty() || clean.contains("..") || clean.contains('\\')) return null
        return try {
            val stream = appContext.assets.open("worlds/$clean")
            WebResourceResponse(mimeFor(clean), "utf-8", 200, "OK", CORS_HEADERS, stream)
        } catch (_: Exception) {
            null
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", CORS_HEADERS, ByteArrayInputStream(ByteArray(0)),
    )

    private fun mimeFor(name: String): String = when {
        name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".html") -> "text/html"
        else -> "application/octet-stream"
    }

    private companion object {
        // Rewritten CDN module imports are cross-origin from the page's point of view.
        val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")
    }
}
