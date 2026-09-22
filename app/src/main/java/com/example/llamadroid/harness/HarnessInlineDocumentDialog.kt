package com.example.llamadroid.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.ui.agent.harness.NativeHarnessWorkspaceFilePage
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session resource preview; project editing remains in the existing workspace explorer. */
@Composable
internal fun HarnessInlineDocumentDialog(
    request: HarnessInlineDocumentRequest,
    client: HarnessClient,
    onDownload: (HarnessDownloadedFile) -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember(client, request.sessionId) { HarnessInlineDocumentReader(client, request.sessionId) }
    var line by remember(request) { mutableIntStateOf(request.line) }
    var retry by remember(request) { mutableIntStateOf(0) }
    var page by remember(request) { mutableStateOf<NativeHarnessWorkspaceFilePage?>(null) }
    var loading by remember(request) { mutableStateOf(false) }
    var downloading by remember(request) { mutableStateOf(false) }
    var failed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(reader, request, line, retry) {
        loading = true
        failed = false
        page = null
        try { page = reader.page(request.path, line) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; onFailure() }
        finally { loading = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppScreenScaffold(title = stringResource(R.string.harness_inline_file_title), onBack = onDismiss) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(request.path, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.harness_inline_file_scope), style = MaterialTheme.typography.bodySmall)
                    ResponsiveActionGroup(actions = listOf(
                        ResponsiveAction(stringResource(R.string.action_close), onDismiss, style = ResponsiveActionStyle.Text),
                        ResponsiveAction(stringResource(R.string.harness_inline_open_document), {
                            downloading = true
                            scope.launch {
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        val bytes = reader.document(request.path)
                                        cacheHarnessDownload(context, bytes, request.path.substringAfterLast('/'),
                                            java.net.URLConnection.guessContentTypeFromName(request.path) ?: "application/octet-stream")
                                    }
                                    onDownload(file)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true; onFailure() }
                                finally { downloading = false }
                            }
                        }, enabled = !downloading, style = ResponsiveActionStyle.Secondary),
                    ))
                }
                if (loading || downloading) item { CircularProgressIndicator() }
                if (failed) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.harness_inline_preview_failed), color = MaterialTheme.colorScheme.error)
                        ResponsiveActionGroup(actions = listOf(ResponsiveAction(
                            stringResource(R.string.harness_continue), { retry++ }, enabled = !loading, style = ResponsiveActionStyle.Secondary,
                        )))
                    }
                }
                page?.let { loaded ->
                    item {
                        Text(stringResource(R.string.harness_inline_lines, loaded.offset, loaded.offset + (loaded.lines - 1).coerceAtLeast(0)))
                        ResponsiveActionGroup(actions = listOf(
                            ResponsiveAction(stringResource(R.string.harness_structured_previous_page),
                                { line = (line - HarnessInlineDocumentReader.PAGE_LINES).coerceAtLeast(1) },
                                enabled = !loading && line > 1, style = ResponsiveActionStyle.Secondary),
                            ResponsiveAction(stringResource(R.string.harness_structured_next_page),
                                { line += loaded.lines.coerceAtLeast(1) },
                                enabled = !loading && !loaded.eof && line <= Int.MAX_VALUE - loaded.lines.coerceAtLeast(1),
                                style = ResponsiveActionStyle.Secondary),
                        ))
                    }
                    item {
                        AppSectionCard {
                            SelectionContainer {
                                Text(loaded.text.ifEmpty { stringResource(R.string.harness_inline_empty) }, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
