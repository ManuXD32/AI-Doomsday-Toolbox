package com.example.llamadroid.ui.training

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.training.CuratedDataset
import com.example.llamadroid.training.CuratedDatasets
import com.example.llamadroid.training.DatasetGroup
import com.example.llamadroid.training.DatasetSource
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.TrainingDatasetEntity
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.util.FormatUtils

private val IMPORT_MIME_TYPES = arrayOf(
    "text/*", "application/json", "application/x-ndjson", "application/jsonl",
    "text/csv", "application/vnd.apache.parquet", "application/octet-stream"
)

/** Dataset library: my datasets, the curated catalog and Hugging Face search, one shared registry. */
@Composable
fun DatasetLibraryScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: DatasetLibraryViewModel = viewModel()
    val datasets by viewModel.datasets.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var pendingImport by remember { mutableStateOf<Pair<Uri, String>?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            val text = if (notice.arg != null) resources.getString(notice.res, notice.arg) else resources.getString(notice.res)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri to displayNameOf(context, uri)
    }

    AppScreenScaffold(
        title = stringResource(R.string.dataset_library_title),
        subtitle = stringResource(R.string.dataset_library_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppScrollableTabRow(selectedTabIndex = tab) {
                listOf(R.string.dataset_tab_mine, R.string.dataset_tab_catalog, R.string.dataset_tab_hf)
                    .forEachIndexed { index, res ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(stringResource(res), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
            }
            when (tab) {
                0 -> MyDatasetsTab(viewModel, datasets, onImport = { importPicker.launch(IMPORT_MIME_TYPES) })
                1 -> CatalogTab(viewModel, datasets)
                else -> HuggingFaceTab(viewModel)
            }
        }
    }

    pendingImport?.let { (uri, name) ->
        DatasetImportDialog(
            fileName = name,
            onDismiss = { pendingImport = null },
            onConfirm = { format, columns, capMb ->
                viewModel.importFile(uri, name, format, columns, capMb)
                pendingImport = null
                tab = 0
            }
        )
    }
}

private fun displayNameOf(context: android.content.Context, uri: Uri): String {
    var name = uri.lastPathSegment.orEmpty()
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0).orEmpty().ifBlank { name }
        }
    }
    return name
}

private val listPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun MyDatasetsTab(
    viewModel: DatasetLibraryViewModel,
    datasets: List<TrainingDatasetEntity>,
    onImport: () -> Unit
) {
    val progress = rememberDatasetProgress()
    val importing by viewModel.importProgress.collectAsState()
    val importName by viewModel.importName.collectAsState()
    var toDelete by remember { mutableStateOf<TrainingDatasetEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedButton(onClick = onImport, enabled = importing == null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Text(
                    text = stringResource(R.string.dataset_import_button),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        importing?.let { (copied, total) ->
            item {
                AppSectionCard {
                    Text(
                        text = stringResource(R.string.dataset_import_progress, importName),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (total > 0L) {
                        LinearProgressIndicator(
                            progress = { (copied.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (datasets.isEmpty()) {
            item {
                AppEmptyState(
                    title = stringResource(R.string.dataset_empty_title),
                    subtitle = stringResource(R.string.dataset_empty_body)
                )
            }
        }
        items(datasets, key = { it.id }) { dataset ->
            DatasetRow(dataset, DatasetUiHelpers.downloadProgress(progress, dataset.id), onDelete = { toDelete = dataset })
        }
    }

    toDelete?.let { dataset ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.dataset_lib_delete_title)) },
            text = { Text(stringResource(R.string.dataset_delete_body, dataset.name), maxLines = 6, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(dataset.id); toDelete = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun DatasetRow(dataset: TrainingDatasetEntity, progress: Float?, onDelete: () -> Unit) {
    val context = LocalContext.current
    val status = DatasetStatus.fromValue(dataset.status)
    val source = DatasetSource.fromValue(dataset.source)
    val nonCommercial = DatasetUiHelpers.isNonCommercialLicence(dataset.licence) ||
        (source == DatasetSource.CURATED && CuratedDatasets.all.any { it.repo == dataset.hfRepo && it.nonCommercial })
    AppSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = dataset.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val rows = dataset.rowCount?.let { DatasetUiHelpers.compactCount(it) }
                val size = FormatUtils.Display.formatBytes(context, dataset.sizeBytes)
                Text(
                    text = if (rows != null) stringResource(R.string.dataset_meta_format, size, rows) else size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            DatasetChip(
                text = datasetStatusText(status),
                warning = status == DatasetStatus.FAILED
            )
            DatasetChip(
                text = stringResource(
                    when (source) {
                        DatasetSource.CURATED -> R.string.dataset_source_curated
                        DatasetSource.HF -> R.string.dataset_source_hf
                        DatasetSource.FILE -> R.string.dataset_source_file
                    }
                )
            )
            DatasetChip(text = dataset.licence)
            if (nonCommercial) DatasetChip(text = stringResource(R.string.dataset_chip_nc), warning = true)
        }
        if (status == DatasetStatus.DOWNLOADING) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (status == DatasetStatus.FAILED && !dataset.error.isNullOrBlank()) {
            Text(
                text = dataset.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CatalogTab(viewModel: DatasetLibraryViewModel, datasets: List<TrainingDatasetEntity>) {
    val groups = remember { CuratedDatasets.byGroup().filterValues { it.isNotEmpty() } }
    var expanded by remember { mutableStateOf(setOf(DatasetGroup.CHAT)) }
    val caps = remember { mutableStateMapOf<String, Int>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        groups.forEach { (group, entries) ->
            val open = group in expanded
            item(key = "group-${group.name}") {
                AppSectionCard(modifier = Modifier.clickable { expanded = if (open) expanded - group else expanded + group }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = datasetGroupTitle(group),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge)
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                }
            }
            if (open) {
                items(entries, key = { "entry-${it.id}" }) { entry ->
                    val existing = datasets.firstOrNull {
                        it.source == DatasetSource.CURATED.value && it.hfRepo == entry.repo && it.status != DatasetStatus.FAILED.value
                    }
                    CatalogEntryCard(
                        entry = entry,
                        capMb = caps[entry.id] ?: entry.defaultCapMb,
                        onCapChange = { caps[entry.id] = it },
                        added = existing != null,
                        onAdd = { viewModel.addCurated(entry, caps[entry.id] ?: entry.defaultCapMb) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogEntryCard(
    entry: CuratedDataset,
    capMb: Int,
    onCapChange: (Int) -> Unit,
    added: Boolean,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    AppSectionCard {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            DatasetChip(text = entry.licence)
            DatasetChip(text = FormatUtils.Display.formatBytes(context, entry.totalBytes))
            if (entry.nonCommercial) DatasetChip(text = stringResource(R.string.dataset_chip_nc), warning = true)
            if (entry.gated) DatasetChip(text = stringResource(R.string.dataset_chip_gated), warning = true)
        }
        if (entry.nonCommercial) {
            Text(
                text = stringResource(R.string.dataset_nc_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.gated) {
            Text(
                text = stringResource(R.string.dataset_gated_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DatasetCapSelector(selectedMb = capMb, onSelect = onCapChange)
        Button(onClick = onAdd, enabled = !added, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(if (added) R.string.dataset_catalog_added else R.string.dataset_catalog_add),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HuggingFaceTab(viewModel: DatasetLibraryViewModel) {
    val query by viewModel.query.collectAsState()
    val state by viewModel.searchState.collectAsState()
    val detail by viewModel.detail.collectAsState()
    var detailRepo by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.dataset_hf_search_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = { Text(stringResource(R.string.dataset_hf_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        when (val s = state) {
            HfSearchState.Idle -> Unit
            HfSearchState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
            is HfSearchState.Error -> item {
                Text(
                    text = stringResource(R.string.dataset_hf_error, s.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is HfSearchState.Results -> {
                if (s.items.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.dataset_hf_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(s.items, key = { it.id }) { hit ->
                    AppSectionCard(
                        modifier = Modifier.clickable {
                            detailRepo = hit.id
                            viewModel.openDetail(hit.id)
                        }
                    ) {
                        Text(
                            text = hit.id,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.dataset_hf_stats,
                                DatasetUiHelpers.compactCount(hit.downloads), DatasetUiHelpers.compactCount(hit.likes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            hit.licence?.takeIf { it.isNotBlank() }?.let { DatasetChip(text = it) }
                            if (DatasetUiHelpers.isNonCommercialLicence(hit.licence)) {
                                DatasetChip(text = stringResource(R.string.dataset_chip_nc), warning = true)
                            }
                            if (hit.gated) DatasetChip(text = stringResource(R.string.dataset_chip_gated), warning = true)
                        }
                    }
                }
            }
        }
    }

    val repo = detailRepo
    val current = detail
    if (repo != null && current != null) {
        HfDatasetSearchSheet(
            state = current,
            repoId = repo,
            onDismiss = {
                detailRepo = null
                viewModel.closeDetail()
            },
            onAdd = { request ->
                viewModel.addHf(
                    name = request.name, repo = request.repo, revision = request.revision,
                    licence = request.licence, gated = request.gated, format = request.format,
                    columns = request.columns, files = request.files, config = request.config,
                    split = request.split, capMb = request.capMb
                )
            }
        )
    }
}
