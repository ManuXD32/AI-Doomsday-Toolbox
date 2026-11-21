package com.example.llamadroid.ui.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.training.DatasetDownloadFile
import com.example.llamadroid.training.DatasetFormat
import com.example.llamadroid.training.DatasetHelpers
import com.example.llamadroid.util.FormatUtils

private const val REVISION_MAIN = "main"
private const val REVISION_PARQUET = "refs/convert/parquet"

/**
 * Details sheet of a Hugging Face dataset: the repository's data files or its converted parquet
 * files, a format/column-mapping editor, a row cap and the Add action. The whole sheet scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfDatasetSearchSheet(
    state: HfDetailState,
    repoId: String,
    onDismiss: () -> Unit,
    onAdd: (HfAddRequest) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = repoId,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            when (state) {
                HfDetailState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Text(stringResource(R.string.dataset_hf_loading), style = MaterialTheme.typography.bodyMedium)
                }
                is HfDetailState.Error -> Text(
                    text = stringResource(R.string.dataset_hf_error, state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                is HfDetailState.Loaded -> HfDetailBody(state, onAdd)
            }
        }
    }
}

/** What the sheet asks the screen to add. */
data class HfAddRequest(
    val name: String,
    val repo: String,
    val revision: String,
    val licence: String,
    val gated: Boolean,
    val format: DatasetFormat,
    val columns: Map<String, String>,
    val files: List<DatasetDownloadFile>,
    val config: String?,
    val split: String?,
    val capMb: Int
)

@Composable
private fun HfDetailBody(state: HfDetailState.Loaded, onAdd: (HfAddRequest) -> Unit) {
    val context = LocalContext.current
    val licence = state.info?.licence.orEmpty()
    val gated = state.info?.gated == true
    val hasParquet = state.parquet.isNotEmpty()
    val hasFiles = state.files.isNotEmpty()

    var useParquet by rememberSaveable(state.repo) { mutableStateOf(!hasFiles && hasParquet) }
    val configs = remember(state.parquet) { DatasetUiHelpers.parquetConfigs(state.parquet) }
    var config by rememberSaveable(state.repo) { mutableStateOf(configs.firstOrNull().orEmpty()) }
    val splits = remember(state.parquet, config) { DatasetUiHelpers.parquetSplits(state.parquet, config) }
    var split by rememberSaveable(state.repo, config) {
        mutableStateOf(splits.firstOrNull { it == "train" } ?: splits.firstOrNull().orEmpty())
    }
    val parquetFiles = remember(state.parquet, config, split) { DatasetUiHelpers.parquetFor(state.parquet, config, split) }

    // Selected repository paths; parquet files are all selected for the chosen config/split.
    var selected by remember(state.repo) {
        mutableStateOf(state.files.firstOrNull()?.let { setOf(it.path) } ?: emptySet<String>())
    }
    var name by rememberSaveable(state.repo) { mutableStateOf(DatasetUiHelpers.repoDisplayName(state.repo)) }
    var format by rememberSaveable(state.repo) { mutableStateOf(DatasetFormat.AUTO) }
    var columns by remember(state.repo) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var capMb by rememberSaveable(state.repo) { mutableStateOf(0) }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        DatasetChip(text = licence.ifBlank { stringResource(R.string.dataset_licence_unknown) })
        if (DatasetUiHelpers.isNonCommercialLicence(licence)) {
            DatasetChip(text = stringResource(R.string.dataset_chip_nc), warning = true)
        }
        if (gated) DatasetChip(text = stringResource(R.string.dataset_chip_gated), warning = true)
    }
    if (DatasetUiHelpers.isNonCommercialLicence(licence)) {
        Text(
            text = stringResource(R.string.dataset_nc_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (gated) {
        Text(
            text = stringResource(R.string.dataset_gated_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!hasFiles && !hasParquet) {
        Text(
            text = stringResource(R.string.dataset_hf_no_files),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    if (hasFiles && hasParquet) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = !useParquet,
                onClick = { useParquet = false },
                label = { Text(stringResource(R.string.dataset_hf_source_repo), maxLines = 1) }
            )
            FilterChip(
                selected = useParquet,
                onClick = { useParquet = true },
                label = { Text(stringResource(R.string.dataset_hf_source_parquet), maxLines = 1) }
            )
        }
    }

    if (useParquet) {
        PickerRow(stringResource(R.string.dataset_hf_config), configs, config) { config = it }
        PickerRow(stringResource(R.string.dataset_hf_split), splits, split) { split = it }
        parquetFiles.forEach { file ->
            Text(
                text = "${file.filename} · ${FormatUtils.Display.formatBytes(context, file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Text(
            text = stringResource(R.string.dataset_hf_files_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.files.forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Checkbox(
                    checked = file.path in selected,
                    onCheckedChange = { on -> selected = if (on) selected + file.path else selected - file.path }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = FormatUtils.Display.formatBytes(context, file.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.dataset_lib_name_label), maxLines = 1) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    DatasetFormatEditor(
        format = format,
        onFormatChange = { format = it },
        columns = columns,
        onColumnsChange = { columns = it },
        capMb = capMb,
        onCapChange = { capMb = it }
    )

    val chosen: List<DatasetDownloadFile> = if (useParquet) {
        parquetFiles.map { DatasetDownloadFile(DatasetUiHelpers.parquetPath(it), it.sizeBytes) }
    } else {
        state.files.filter { it.path in selected }.map { DatasetDownloadFile(it.path, it.sizeBytes) }
    }
    val total = chosen.sumOf { it.sizeBytes }
    val available = if (useParquet) parquetFiles.size else state.files.size
    Text(
        text = stringResource(
            R.string.dataset_hf_selected_summary,
            chosen.size.toString(), available.toString(), FormatUtils.Display.formatBytes(context, total)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = {
            onAdd(
                HfAddRequest(
                    name = name.trim().ifEmpty { DatasetUiHelpers.repoDisplayName(state.repo) },
                    repo = state.repo,
                    revision = if (useParquet) REVISION_PARQUET else (state.info?.sha ?: REVISION_MAIN),
                    licence = licence.ifBlank { "unknown" },
                    gated = gated,
                    format = format,
                    columns = DatasetUiHelpers.cleanColumnMap(format, columns),
                    files = chosen,
                    config = if (useParquet) config else null,
                    split = if (useParquet) split else null,
                    capMb = capMb
                )
            )
        },
        enabled = chosen.isNotEmpty() && DatasetHelpers.isSupported(chosen.first().path.substringAfterLast('/')),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.dataset_hf_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickerRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}
