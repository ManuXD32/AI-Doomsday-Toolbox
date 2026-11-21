package com.example.llamadroid.ui.knowledge

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.EmbeddingCuratedBundleCatalog
import com.example.llamadroid.ui.components.requestCuratedBundleDownload
import com.example.llamadroid.ui.components.resolveCuratedFiles

/**
 * Inline embedding model chooser, shared by the "no embedding model" dialog, the Knowledge Base screen and the Chat
 * settings screen: installed embedding models ("Use this"), then the curated embedding bundles with download state
 * (the same download pipeline and install checks as the model manager). [onConfigured] fires when the user picks an
 * installed model, or when a download started from here finishes and becomes the default.
 */
@Composable
fun EmbeddingModelChooser(
    modifier: Modifier = Modifier,
    onConfigured: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val db = remember { AppDatabase.getDatabase(appContext) }
    val installed by db.modelDao().getModelsByType(ModelType.EMBEDDING).collectAsState(initial = emptyList())
    val persistedTasks by db.downloadTaskDao().observeAll().collectAsState(initial = emptyList())
    val progressMap by DownloadProgressHolder.progress.collectAsState()
    // SettingsRepository instances do not share state, so re-read the preferences whenever the model list changes.
    var settingsTick by remember { mutableIntStateOf(0) }
    val settings = remember(installed, settingsTick) { SettingsRepository(appContext) }
    val selectedPath by settings.selectedEmbeddingModelPath.collectAsState()
    val configured = remember(installed, settingsTick) { isKnowledgeEmbeddingConfigured(settings) }
    var startedHere by remember { mutableStateOf(false) }

    // The download pipeline makes a finished embedding model the default when none is configured; continue from here.
    LaunchedEffect(configured, startedHere) {
        if (startedHere && configured) {
            startedHere = false
            onConfigured()
        }
    }
    // Pick up the pipeline's preference write shortly after the Room row appears.
    LaunchedEffect(installed.size) {
        kotlinx.coroutines.delay(600L)
        settingsTick++
    }

    val rows = remember(installed, selectedPath) {
        buildEmbeddingChooserRows(installed, EmbeddingCuratedBundleCatalog.bundles, selectedPath)
    }
    val tasksByKey = remember(persistedTasks) {
        persistedTasks.flatMap { sequenceOf(it.id to it, it.progressKey to it) }.toMap()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            when (row) {
                is EmbeddingChooserRow.Installed -> OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                row.model.filename,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                Formatter.formatShortFileSize(context, row.model.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (row.selected && configured) {
                            Text(
                                stringResource(R.string.embchooser_in_use),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Button(onClick = {
                                applyKnowledgeEmbeddingModel(SettingsRepository(appContext), row.model)
                                settingsTick++
                                onConfigured()
                            }) { Text(stringResource(R.string.embchooser_use), maxLines = 1) }
                        }
                    }
                }
                is EmbeddingChooserRow.Catalog -> {
                    val bundle = row.bundle
                    val resolved = resolveCuratedFiles(bundle, bundle.defaultPrefix, installed, tasksByKey, progressMap)
                    val downloading = resolved.any { it.active }
                    val progress = resolved.map { r ->
                        when {
                            r.installed || r.completed -> 1f
                            r.liveValue != null && r.liveValue in 0f..1f -> r.liveValue
                            else -> 0f
                        }
                    }.average().toFloat()
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(bundle.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (row.recommended) {
                                    Text(
                                        stringResource(R.string.embchooser_recommended),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                stringResource(bundle.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                Formatter.formatShortFileSize(context, bundle.totalBytes),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (downloading) {
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    stringResource(R.string.embchooser_downloading, (progress.coerceIn(0f, 1f) * 100).toInt()),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                Button(
                                    onClick = {
                                        val result = requestCuratedBundleDownload(
                                            context = appContext,
                                            bundle = bundle,
                                            installedModels = installed,
                                            persistedTasks = persistedTasks,
                                            progressMap = progressMap
                                        )
                                        if (result.failed > 0) {
                                            Toast.makeText(context, R.string.audio_bundle_download_start_error, Toast.LENGTH_LONG).show()
                                        } else {
                                            startedHere = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.embchooser_download), maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.embchooser_reindex_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Shown instead of a toast when a document upload needs an embedding model that is not configured. Scrollable, with a
 * 360 dp cap on the list so the buttons stay reachable on small phones. [onOpenModelManager] adds the shortcut to the
 * canonical model manager when the caller can navigate.
 */
@Composable
fun NoEmbeddingModelDialog(
    onDismiss: () -> Unit,
    onConfigured: () -> Unit,
    onOpenModelManager: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.embchooser_dialog_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { NoEmbeddingModelDialogContent(onConfigured, onOpenModelManager) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.embchooser_close)) } }
    )
}

/** Body of [NoEmbeddingModelDialog]; internal so screenshot tests can render it without a platform dialog window. */
@Composable
internal fun NoEmbeddingModelDialogContent(onConfigured: () -> Unit, onOpenModelManager: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.embchooser_explainer), style = MaterialTheme.typography.bodyMedium)
        EmbeddingModelChooser(onConfigured = onConfigured)
        if (onOpenModelManager != null) {
            OutlinedButton(onClick = onOpenModelManager, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.embchooser_open_models), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
