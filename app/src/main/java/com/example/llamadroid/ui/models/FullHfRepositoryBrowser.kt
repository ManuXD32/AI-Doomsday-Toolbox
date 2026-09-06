package com.example.llamadroid.ui.models

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.data.model.library.*
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Adjacent to the existing filtered '+' action; browsing never selects files implicitly. */
@Composable
internal fun HfRepositoryBrowseButton(repositoryId: String, family: ModelFamily) {
    var open by rememberSaveable(repositoryId) { mutableStateOf(false) }
    val targets = LocalWalkthroughTargets.current
    FilledTonalIconButton(onClick = { open = true; targets?.recordEvent("models.browser") },
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("hf_browse_$repositoryId")) {
        Icon(Icons.Default.FolderOpen, stringResource(R.string.hf_full_browse))
    }
    if (open) FullHfRepositoryBrowser(repositoryId, family) { open = false }
}

internal fun hfSelectedFileUrl(repositoryId: String, revision: String, relativePath: String): String {
    val normalizedPath = relativePath.trim().trim('/')
    require(normalizedPath.isNotBlank()) { "Hugging Face file path is blank" }
    require(normalizedPath.split('/').none { it == "." || it == ".." }) {
        "Hugging Face file path contains an unsafe segment"
    }
    return "https://huggingface.co".toHttpUrl().newBuilder().addPathSegments(repositoryId)
        .addPathSegment("resolve").addPathSegment(revision).addPathSegments(normalizedPath).build().toString()
}

@Composable
internal fun FullHfRepositoryBrowser(repositoryId: String, family: ModelFamily, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val browser = remember { ModelLibraryRepositoryFactory.createBrowser() }
    val repository = remember { ModelLibraryRepositoryFactory.create(context) }
    val preferences = remember { context.getSharedPreferences("litert_model_repository", Context.MODE_PRIVATE) }
    val targets = LocalWalkthroughTargets.current
    var revision by rememberSaveable(repositoryId) { mutableStateOf<String?>(null) }
    var folder by rememberSaveable(repositoryId) { mutableStateOf("") }
    var cursor by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<HfFolderListing?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var queueing by remember { mutableStateOf(false) }
    var queued by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<ModelLibraryErrorCode?>(null) }
    var selectedPaths by rememberSaveable(repositoryId) { mutableStateOf<List<String>>(emptyList()) }
    val storageGroup = rememberSaveable(repositoryId) { UUID.randomUUID().toString() }
    val requestEpoch = remember { AtomicLong(0L) }

    LaunchedEffect(repositoryId, folder, cursor, retry) {
        val epoch = requestEpoch.incrementAndGet()
        val requestedFolder = folder
        val requestedCursor = cursor
        val requestedRetry = retry
        val requestedRevision = revision
        loading = true
        failure = null
        try {
            val page = withContext(Dispatchers.IO) {
                val token = preferences.getString("hugging_face_token", "").orEmpty().takeIf { it.isNotBlank() }
                val pinned = requestedRevision ?: browser.resolveRevision(repositoryId, bearerToken = token)
                browser.listFolder(repositoryId, pinned, requestedFolder, token, maxPages = 1, cursor = requestedCursor)
            }
            currentCoroutineContext().ensureActive()
            if (epoch != requestEpoch.get() || folder != requestedFolder || cursor != requestedCursor || retry != requestedRetry) {
                return@LaunchedEffect
            }
            revision = page.revision
            listing = if (requestedCursor != null && listing?.folderPath == requestedFolder) {
                listing?.appendPage(page) ?: page
            } else {
                page
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (epoch == requestEpoch.get()) failure = hfBrowserError(error)
        } finally {
            if (epoch == requestEpoch.get()) loading = false
        }
    }
    fun openFolder(path: String) {
        folder = path
        cursor = null
        listing = null
    }

    WalkthroughDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(.88f).padding(12.dp),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.model_library_hf_browser_title, repositoryId),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("hf_browser_close")) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                    }
                }
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(onClick = { openFolder("") }) {
                            Text(stringResource(R.string.hf_full_root), maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    val parts = folder.split('/').filter { it.isNotBlank() }
                    items(parts.indices.toList()) { index ->
                        TextButton(onClick = { openFolder(parts.take(index + 1).joinToString("/")) }) {
                            Text(parts[index], maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                if (loading || queueing) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth().walkthroughTarget("models.browser"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.hf_full_explanation), style = MaterialTheme.typography.bodySmall) }
                    failure?.let { code -> item {
                        Text(modelLibraryErrorText(code), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }, enabled = !loading) { Text(stringResource(R.string.tour_retry)) }
                    } }
                    if (queued > 0) item { Text(stringResource(R.string.hf_full_queued, queued)) }
                    if (!loading && listing?.items?.isEmpty() == true) item {
                        Text(stringResource(R.string.model_library_browser_empty))
                    }
                    items(listing?.items.orEmpty(), key = { it.path }) { item ->
                        val directory = item.type == "directory"
                        val checked = item.path in selectedPaths
                        val action = if (directory) Modifier.clickable(role = Role.Button) { openFolder(item.path) }
                            else Modifier.toggleable(checked, enabled = !queueing, role = Role.Checkbox) {
                                selectedPaths = if (it) {
                                    (selectedPaths + item.path).distinct()
                                } else {
                                    selectedPaths - item.path
                                }
                            }
                        Card(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (directory) Icon(Icons.Default.FolderOpen, null)
                                else Checkbox(checked, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    SelectionContainer { Text(item.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge) }
                                    if (!directory) Text(Formatter.formatFileSize(context, item.size),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (listing?.nextCursor != null) item {
                        OutlinedButton(onClick = { cursor = listing?.nextCursor }, enabled = !loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.model_library_browser_load_more))
                        }
                    }
                    if (selectedPaths.isNotEmpty()) item {
                        Text(stringResource(R.string.hf_full_selection), style = MaterialTheme.typography.titleSmall)
                        selectedPaths.forEach { path ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) { Text(path, style = MaterialTheme.typography.bodySmall) }
                                IconButton(onClick = { selectedPaths = selectedPaths - path }, enabled = !queueing) {
                                    Icon(Icons.Default.Close, stringResource(R.string.hf_full_remove_selection, path))
                                }
                            }
                        }
                    }
                }
                Button(onClick = {
                    val files = selectedPaths.toList()
                    val pinned = revision ?: return@Button
                    queueing = true
                    failure = null
                    targets?.recordEvent("models.download")
                    val job = ModelLibraryQueueScope.launch("hf-selection:$storageGroup") {
                        try {
                            val token = preferences.getString("hugging_face_token", "").orEmpty().takeIf { it.isNotBlank() }
                            for (path in files) {
                                val source = repository.saveSource(ModelSourceDraft(family,
                                    hfSelectedFileUrl(repositoryId, pinned, path), label = path)).getOrThrow()
                                repository.startCustomDownload(context, source.id, family, role = null,
                                    bearerToken = token, storageGroup = storageGroup).getOrThrow()
                                withContext(Dispatchers.Main) {
                                    queued++
                                    selectedPaths = selectedPaths - path
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Throwable) {
                            withContext(Dispatchers.Main) { failure = hfBrowserError(error) }
                        } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { queueing = false } }
                    }
                    if (job == null) {
                        queueing = false
                        failure = ModelLibraryErrorCode.SOURCE_HAS_PENDING_DOWNLOAD
                    }
                }, enabled = selectedPaths.isNotEmpty() && !queueing && !loading && revision != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hf_download_selected")) {
                    Text(stringResource(R.string.hf_full_download_selected, selectedPaths.size))
                }
            }
        }
    }
}

private fun hfBrowserError(error: Throwable): ModelLibraryErrorCode = when (error) {
    is ModelLibraryException -> error.code
    is HuggingFaceHttpException -> error.errorCode
    is java.net.SocketTimeoutException -> ModelLibraryErrorCode.REQUEST_TIMEOUT
    is java.io.IOException -> ModelLibraryErrorCode.NETWORK_FAILURE
    else -> ModelLibraryErrorCode.INVALID_URL
}
