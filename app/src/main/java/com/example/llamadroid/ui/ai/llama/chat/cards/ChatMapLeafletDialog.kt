package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.core.net.toUri
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.map.ChatMapServices
import com.example.llamadroid.chat.cards.map.LeafletPageBuilder
import com.example.llamadroid.chat.cards.map.ResolvedPlace
import com.example.llamadroid.chat.cards.map.TileKey
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import android.widget.Toast

/** Full-screen Leaflet map. Page and Leaflet assets are served offline; only OSM tile requests use the network. */
@Composable
internal fun ChatMapLeafletDialog(
    title: String,
    places: List<ResolvedPlace>,
    route: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val html = remember(places, route) { LeafletPageBuilder.build(places, route) }
    var crashed by remember { mutableStateOf(false) }
    var state by remember(html) { mutableStateOf<MapDialogState>(MapDialogState.Loading) }
    LaunchedEffect(html) {
        delay(MapDialogReducer.TIMEOUT_MS)
        state = MapDialogReducer.onTimeout(state)
    }
    val noMapsApp = stringResource(R.string.chat_card_map_no_maps_app)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_card_map_close))
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (crashed) {
                        Text(
                            stringResource(R.string.chat_card_map_webview_crashed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        LeafletWebView(
                            html = html,
                            onCrashed = { crashed = true },
                            onTitle = { state = MapDialogReducer.onTitle(state, it) },
                            onLoadError = { path, code -> state = MapDialogReducer.onLoadError(state, path, code) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        when (val s = state) {
                            MapDialogState.Loading -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.chat_card_map_loading), modifier = Modifier.padding(top = 8.dp))
                            }
                            is MapDialogState.Error -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                                Box(Modifier.fillMaxSize()) {
                                    Text(
                                        if (s.detail == MapDialogReducer.TIMEOUT_DETAIL) stringResource(R.string.chat_card_map_timeout)
                                        else stringResource(R.string.chat_card_map_load_error, s.detail),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                    )
                                }
                            }
                            MapDialogState.Ready -> Unit
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_card_map_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val located = places.filter { it.point != null }
                if (located.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chat_card_map_open_maps_app),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        located.forEach { p ->
                            AssistChip(
                                onClick = { if (!openInMapsApp(context, p)) Toast.makeText(context, noMapsApp, Toast.LENGTH_SHORT).show() },
                                label = { Text("${p.index + 1}. ${p.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openInMapsApp(context: android.content.Context, place: ResolvedPlace): Boolean {
    val p = place.point ?: return false
    val label = Uri.encode(place.name)
    val uri = "geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}($label)".toUri()
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LeafletWebView(
    html: String,
    onCrashed: () -> Unit,
    onTitle: (String?) -> Unit,
    onLoadError: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val latestTitle by rememberUpdatedState(onTitle)
    val latestError by rememberUpdatedState(onLoadError)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).also { web ->
                web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                web.setBackgroundColor(background)
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                web.webViewClient = LeafletClient(appContext, html, onCrashed) { path, code -> latestError(path, code) }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) = latestTitle(title)
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.w("ChatMapLeaflet", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                        return true
                    }
                }
                web.loadUrl(LeafletPageBuilder.PAGE_URL)
            }
        },
        onRelease = { web ->
            runCatching { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() }
        },
    )
}

private class LeafletClient(
    private val appContext: android.content.Context,
    private val html: String,
    private val onCrashed: () -> Unit,
    private val onLoadError: (String, Int) -> Unit,
) : WebViewClient() {
    private fun report(request: WebResourceRequest, code: Int) {
        val url = request.url
        if (!url.host.orEmpty().equals(LeafletPageBuilder.PAGE_HOST, ignoreCase = true)) return
        val path = url.path.orEmpty()
        if (!MapDialogReducer.isCriticalPath(path)) return
        Log.w("ChatMapLeaflet", "load failed $path code=$code")
        onLoadError(path, code)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
        report(request, error.errorCode)

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
        report(request, errorResponse.statusCode)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !(request.isForMainFrame && request.url.toString() == LeafletPageBuilder.PAGE_URL)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onCrashed()
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()
        if (host.equals(LeafletPageBuilder.PAGE_HOST, ignoreCase = true)) {
            if (path == "/index.html") {
                return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
            }
            if (path.startsWith("/leaflet/")) return serveAsset(path.removePrefix("/leaflet/"))
            return notFound()
        }
        if (host.equals("tile.openstreetmap.org", ignoreCase = true)) {
            val key = TILE_PATH.matchEntire(path)?.destructured?.let { (z, x, y) -> TileKey(z.toInt(), x.toInt(), y.toInt()) }
                ?: return notFound()
            val bytes = ChatMapServices.tiles(appContext).get(key) ?: return notFound()
            return WebResourceResponse("image/png", null, 200, "OK", emptyMap(), ByteArrayInputStream(bytes))
        }
        return notFound()
    }

    private fun serveAsset(relative: String): WebResourceResponse {
        if (relative.isEmpty() || relative.contains("..") || relative.contains('\\')) return notFound()
        return try {
            val stream = appContext.assets.open("${LeafletPageBuilder.ASSET_ROOT}/$relative")
            val mime = when {
                relative.endsWith(".js") -> "text/javascript"
                relative.endsWith(".css") -> "text/css"
                relative.endsWith(".png") -> "image/png"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, if (mime.startsWith("image")) null else "utf-8", 200, "OK", emptyMap(), stream)
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val TILE_PATH = Regex("/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})\\.png")
    }
}
