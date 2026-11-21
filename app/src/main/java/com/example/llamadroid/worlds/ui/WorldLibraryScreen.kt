package com.example.llamadroid.worlds.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppHubRowStyle
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.worlds.WorldExport
import com.example.llamadroid.worlds.WorldMeta
import com.example.llamadroid.worlds.WorldRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Saved 3D worlds: open, rename (dialog), delete (confirm). Scrolls through a LazyColumn. */
@Composable
fun WorldLibraryScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { WorldRepository(context) }
    var worlds by remember { mutableStateOf<List<WorldMeta>?>(null) }
    var renameTarget by remember { mutableStateOf<WorldMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<WorldMeta?>(null) }
    var exportTarget by remember { mutableStateOf<WorldMeta?>(null) }
    val exportDone = stringResource(R.string.worlds_export_done)
    val exportFailed = stringResource(R.string.worlds_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(WorldExport.MIME_TYPE)
    ) { uri: Uri? ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            scope.launch {
                val ok = WorldExport.writeSavedWorld(context, repo, target.id, uri)
                Toast.makeText(context, if (ok) exportDone else exportFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    suspend fun reload() { worlds = repo.list() }
    LaunchedEffect(Unit) { reload() }

    AppScreenScaffold(
        title = stringResource(R.string.worlds_ui_library_title),
        subtitle = stringResource(R.string.worlds_ui_library_subtitle),
        onBack = { navController.popBackStack() }
    ) { padding ->
        val items = worlds
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(AppChromeDefaults.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (items != null && items.isEmpty()) {
                item {
                    AppEmptyState(
                        title = stringResource(R.string.worlds_ui_empty_title),
                        subtitle = stringResource(R.string.worlds_ui_empty_body),
                        icon = Icons.Default.ViewInAr
                    )
                }
            }
            items(items.orEmpty(), key = { it.id }) { world ->
                AppHubRow(
                    style = AppHubRowStyle.Plain,
                    icon = Icons.Default.ViewInAr,
                    title = world.title,
                    titleMaxLines = 1,
                    subtitle = dateFormat.format(Date(world.updatedAt)),
                    showChevron = false,
                    onClick = { navController.navigate(Screen.WorldViewer.createRoute(world.id)) },
                    trailing = {
                        IconButton(onClick = {
                            exportTarget = world
                            exportLauncher.launch(WorldExport.suggestedFileName(world.title))
                        }) {
                            Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.worlds_export_action))
                        }
                        IconButton(onClick = { renameTarget = world }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.worlds_ui_rename))
                        }
                        IconButton(onClick = { deleteTarget = world }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.worlds_ui_delete))
                        }
                    }
                )
            }
        }
    }

    renameTarget?.let { target ->
        WorldRenameDialog(
            initial = target.title,
            onConfirm = { newTitle ->
                renameTarget = null
                scope.launch { repo.rename(target.id, newTitle); reload() }
            },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.worlds_ui_delete_title)) },
            text = { Text(stringResource(R.string.worlds_ui_delete_message, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { repo.delete(target.id); reload() }
                }) { Text(stringResource(R.string.worlds_ui_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.worlds_ui_cancel)) }
            }
        )
    }
}
