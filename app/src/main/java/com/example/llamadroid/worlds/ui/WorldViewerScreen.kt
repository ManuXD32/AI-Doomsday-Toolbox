package com.example.llamadroid.worlds.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.worlds.WorldExport
import com.example.llamadroid.worlds.WorldPageBuilder
import com.example.llamadroid.worlds.WorldRepository
import com.example.llamadroid.worlds.WorldRuntimeStrings
import com.example.llamadroid.worlds.WorldWebView
import kotlinx.coroutines.launch

/** Shared fullscreen body: top bar (back, title, actions) over the world. The WebView owns its own touches. */
@Composable
private fun WorldViewerBody(
    title: String,
    pageHtml: String?,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var chromeHidden by remember { mutableStateOf(false) }
    // movableContentOf keeps one WebView alive while the top bar appears and disappears around it.
    val world = remember {
        movableContentOf { html: String?, hidden: (Boolean) -> Unit, mod: Modifier ->
            Box(modifier = mod) {
                if (html == null) {
                    Text(
                        text = stringResource(R.string.worlds_ui_not_found),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (html.isNotEmpty()) {
                    WorldWebView(pageHtml = html, modifier = Modifier.fillMaxSize(), onChromeHidden = hidden)
                }
            }
        }
    }
    val onHidden: (Boolean) -> Unit = { chromeHidden = it }
    if (chromeHidden && !pageHtml.isNullOrEmpty()) {
        world(pageHtml, onHidden, Modifier.fillMaxSize())
    } else {
        AppScreenScaffold(title = title, onBack = onBack, actions = actions) { padding ->
            world(pageHtml, onHidden, Modifier.fillMaxSize().padding(padding))
        }
    }
}

/** Route screen for a saved world (opened from the 3D worlds library). Args: only the world id. */
@Composable
fun WorldViewerScreen(navController: NavController, worldId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { WorldRepository(context) }
    var title by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val exportDone = stringResource(R.string.worlds_export_done)
    val exportFailed = stringResource(R.string.worlds_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(WorldExport.MIME_TYPE)
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = WorldExport.writeSavedWorld(context, repo, worldId, uri)
                Toast.makeText(context, if (ok) exportDone else exportFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(worldId) {
        title = repo.list().firstOrNull { it.id == worldId }?.title.orEmpty()
        page = repo.page(worldId)
        loaded = true
    }

    WorldViewerBody(
        title = title.ifBlank { stringResource(R.string.worlds_ui_library_title) },
        pageHtml = if (loaded) page else "",
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = { exportLauncher.launch(WorldExport.suggestedFileName(title)) }) {
                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.worlds_export_action))
            }
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.worlds_ui_rename))
            }
        }
    )
    if (renaming) {
        WorldRenameDialog(
            initial = title,
            onConfirm = { newTitle ->
                renaming = false
                scope.launch { repo.rename(worldId, newTitle)?.let { title = it.title } }
            },
            onDismiss = { renaming = false }
        )
    }
}

/**
 * Fullscreen viewer for a transient (unsaved) or just-saved world, shown as a dialog so chat
 * and Markdown code blocks can open it without a NavController or large nav arguments.
 */
@Composable
fun WorldSourceViewerDialog(
    source: String,
    savedId: String?,
    chatId: Long?,
    messageId: Long?,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(R.string.worlds_ui_default_title)
    val savedText = stringResource(R.string.worlds_ui_saved)
    val page = remember(source) {
        WorldPageBuilder.detect(source)?.let { WorldRuntimeStrings.buildPage(context, it) }
    }
    var title by remember(source) { mutableStateOf(worldTitleFrom(source, defaultTitle)) }
    var renaming by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WorldViewerBody(
                title = title,
                pageHtml = page,
                onBack = onDismiss,
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.worlds_ui_rename))
                    }
                    if (savedId == null) {
                        IconButton(onClick = {
                            scope.launch {
                                val meta = runCatching {
                                    WorldRepository(context).save(title, source, chatId, messageId)
                                }.getOrNull()
                                if (meta != null) {
                                    onSaved(meta.id)
                                    Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.worlds_ui_save))
                        }
                    }
                }
            )
        }
    }
    if (renaming) {
        WorldRenameDialog(
            initial = title,
            onConfirm = { newTitle ->
                renaming = false
                if (newTitle.isNotBlank()) title = newTitle.trim().take(60)
                if (savedId != null) scope.launch { WorldRepository(context).rename(savedId, title) }
            },
            onDismiss = { renaming = false }
        )
    }
}

/** Title editor shared by the viewer and the library. */
@Composable
fun WorldRenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.worlds_ui_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                label = { Text(stringResource(R.string.worlds_ui_title_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.worlds_ui_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.worlds_ui_cancel)) }
        }
    )
}
