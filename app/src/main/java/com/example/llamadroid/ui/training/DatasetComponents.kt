package com.example.llamadroid.ui.training

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.training.DatasetFormat
import com.example.llamadroid.training.DatasetGroup
import com.example.llamadroid.training.DatasetHelpers
import com.example.llamadroid.training.DatasetRepository
import com.example.llamadroid.training.DatasetStatus
import com.example.llamadroid.training.HfFileEntry
import com.example.llamadroid.training.HfParquetFile
import com.example.llamadroid.training.TrainingDatasetEntity
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Pure helpers shared by the dataset screens; no Android dependencies so they are unit-testable. */
object DatasetUiHelpers {
    /** Row-cap choices in MB; 0 means "no cap". */
    val capOptionsMb: List<Int> = listOf(0, 25, 50, 100, 200, 500)

    private val nonCommercialMarkers = listOf("-nc", "nc-", "noncommercial", "non-commercial", "research-only", "cc-by-nc")

    /** True when the licence id names a non-commercial restriction (e.g. `cc-by-nc-4.0`). */
    fun isNonCommercialLicence(licence: String?): Boolean {
        val l = licence?.lowercase(Locale.ROOT) ?: return false
        return nonCommercialMarkers.any { it in l }
    }

    /** "200 MB" for a cap, null for "no cap". Unit text is language-neutral. */
    fun capText(capMb: Int): String? = if (capMb <= 0) null else "$capMb MB"

    /** Compact row/like counts: 950 -> "950", 12_300 -> "12.3k", 4_500_000 -> "4.5M". */
    fun compactCount(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 10_000L -> String.format(Locale.US, "%.0fk", value / 1_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

    /** Column keys the editor shows for a [format]; auto-detect needs none. */
    fun columnKeys(format: DatasetFormat): List<String> = when (format) {
        DatasetFormat.CHAT -> listOf("messages", "role", "content")
        DatasetFormat.INSTRUCTION -> listOf("instruction", "input", "output")
        DatasetFormat.TEXT -> listOf("text")
        DatasetFormat.AUTO -> emptyList()
    }

    /** Default column map for a [format] (identity names); empty for auto-detect. */
    fun defaultColumnMap(format: DatasetFormat): Map<String, String> =
        columnKeys(format).associateWith { it }

    /** Keeps only the keys valid for [format] with non-blank values. */
    fun cleanColumnMap(format: DatasetFormat, values: Map<String, String>): Map<String, String> =
        columnKeys(format).mapNotNull { key ->
            values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { key to it }
        }.toMap()

    /** Hub files `branchy prepare` can read, skipping READMEs and hidden files. */
    fun trainableFiles(tree: List<HfFileEntry>): List<HfFileEntry> = tree.filter { entry ->
        val name = entry.path.substringAfterLast('/')
        !name.startsWith(".") && !name.startsWith("README", ignoreCase = true) &&
            entry.sizeBytes >= 0 && DatasetHelpers.isSupported(name)
    }

    fun parquetConfigs(files: List<HfParquetFile>): List<String> = files.map { it.config }.distinct()

    fun parquetSplits(files: List<HfParquetFile>, config: String): List<String> =
        files.filter { it.config == config }.map { it.split }.distinct()

    fun parquetFor(files: List<HfParquetFile>, config: String, split: String): List<HfParquetFile> =
        files.filter { it.config == config && it.split == split }

    /** Path inside the `refs/convert/parquet` revision of a converted file. */
    fun parquetPath(file: HfParquetFile): String = "${file.config}/${file.split}/${file.filename}"

    /**
     * Mean progress (0..1) of the active `dataset:<id>:<i>` tasks, or null when none is tracked or
     * any file is still indeterminate.
     */
    fun downloadProgress(progress: Map<String, Float>, datasetId: Long): Float? {
        val prefix = "dataset:$datasetId:"
        val values = progress.filterKeys { it.startsWith(prefix) }.values
        if (values.isEmpty() || values.any { it < 0f }) return null
        return (values.sum() / values.size).coerceIn(0f, 1f)
    }

    /** Default dataset name for a repo id: the part after the owner. */
    fun repoDisplayName(repo: String): String = repo.substringAfterLast('/').ifBlank { repo }
}

@Composable
fun DatasetChip(text: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val container = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = container) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Horizontally scrolling chip row for [DatasetUiHelpers.capOptionsMb]. */
@Composable
fun DatasetCapSelector(selectedMb: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.dataset_cap_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = if (selectedMb in DatasetUiHelpers.capOptionsMb) DatasetUiHelpers.capOptionsMb
            else DatasetUiHelpers.capOptionsMb + selectedMb
            options.forEach { mb ->
                FilterChip(
                    selected = mb == selectedMb,
                    onClick = { onSelect(mb) },
                    label = { Text(DatasetUiHelpers.capText(mb) ?: stringResource(R.string.dataset_cap_all), maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun formatLabel(format: DatasetFormat): String = stringResource(
    when (format) {
        DatasetFormat.AUTO -> R.string.dataset_format_auto
        DatasetFormat.CHAT -> R.string.dataset_format_chat
        DatasetFormat.INSTRUCTION -> R.string.dataset_format_instruction
        DatasetFormat.TEXT -> R.string.dataset_format_text
    }
)

@Composable
private fun columnLabel(key: String): String = when (key) {
    "messages" -> stringResource(R.string.dataset_col_messages)
    "role" -> stringResource(R.string.dataset_col_role)
    "content" -> stringResource(R.string.dataset_col_content)
    "instruction" -> stringResource(R.string.dataset_col_instruction)
    "input" -> stringResource(R.string.dataset_col_input)
    "output" -> stringResource(R.string.dataset_col_output)
    else -> stringResource(R.string.dataset_col_text)
}

/**
 * Format picker plus column-mapping fields and the row cap. Auto-detect needs no mapping; picking a
 * concrete format pre-fills the identity column names ([DatasetUiHelpers.defaultColumnMap]).
 */
@Composable
fun DatasetFormatEditor(
    format: DatasetFormat,
    onFormatChange: (DatasetFormat) -> Unit,
    columns: Map<String, String>,
    onColumnsChange: (Map<String, String>) -> Unit,
    capMb: Int,
    onCapChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dataset_format_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DatasetFormat.entries.forEach { option ->
                FilterChip(
                    selected = option == format,
                    onClick = {
                        if (option != format) {
                            onFormatChange(option)
                            onColumnsChange(DatasetUiHelpers.defaultColumnMap(option))
                        }
                    },
                    label = { Text(formatLabel(option), maxLines = 1) }
                )
            }
        }
        val keys = DatasetUiHelpers.columnKeys(format)
        if (keys.isEmpty()) {
            Text(
                text = stringResource(R.string.dataset_columns_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.dataset_columns_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            keys.forEach { key ->
                OutlinedTextField(
                    value = columns[key].orEmpty(),
                    onValueChange = { onColumnsChange(columns + (key to it)) },
                    label = { Text(columnLabel(key), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        DatasetCapSelector(selectedMb = capMb, onSelect = onCapChange)
    }
}

@Composable
fun datasetGroupTitle(group: DatasetGroup): String = stringResource(
    when (group) {
        DatasetGroup.CHAT -> R.string.dataset_group_chat
        DatasetGroup.GENERAL_KNOWLEDGE -> R.string.dataset_group_general
        DatasetGroup.CODING -> R.string.dataset_group_coding
        DatasetGroup.TOOL_USE -> R.string.dataset_group_tool_use
        DatasetGroup.STORIES -> R.string.dataset_group_stories
        DatasetGroup.SPANISH -> R.string.dataset_group_spanish
    }
)

@Composable
fun datasetStatusText(status: DatasetStatus): String = stringResource(
    when (status) {
        DatasetStatus.PENDING -> R.string.dataset_lib_status_pending
        DatasetStatus.DOWNLOADING -> R.string.dataset_status_downloading
        DatasetStatus.READY -> R.string.dataset_status_ready
        DatasetStatus.FAILED -> R.string.dataset_status_failed
    }
)

/**
 * Dialog that lists the data files of ready datasets from the dataset library and returns the picked
 * file's absolute path. Used by trainers that take a dataset file path (Quadtrix).
 */
@Composable
fun DatasetFilePickerDialog(
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { DatasetRepository(context) }
    var items by remember { mutableStateOf<List<Pair<TrainingDatasetEntity, List<File>>>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            repository.list().filter { it.status == DatasetStatus.READY.value }
                .map { it to repository.dataFiles(it.id) }
                .filter { it.second.isNotEmpty() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dataset_pick_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val loaded = items
                if (loaded != null && loaded.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dataset_pick_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                loaded.orEmpty().forEach { (dataset, files) ->
                    Text(
                        text = dataset.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    files.forEach { file ->
                        OutlinedButton(onClick = { onPicked(file.absolutePath) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${file.name} · ${FormatUtils.Display.formatBytes(context, file.length())}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenLibrary) {
                Text(stringResource(R.string.dataset_pick_open_library), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

/** Live download progress map of all tasks (dataset rows use keys `dataset:<id>:<i>`). */
@Composable
fun rememberDatasetProgress(): Map<String, Float> {
    val progress by DownloadProgressHolder.progress.collectAsState()
    return progress
}
