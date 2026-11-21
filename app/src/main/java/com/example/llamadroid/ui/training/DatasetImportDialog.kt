package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.training.DatasetFormat
import com.example.llamadroid.training.DatasetHelpers

/**
 * Confirms a SAF import: shows the file name, lets the user pick the data format and column
 * mapping (auto-detect by default, plain text for .txt) and an optional row cap. The body scrolls.
 */
@Composable
fun DatasetImportDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: (format: DatasetFormat, columns: Map<String, String>, capMb: Int) -> Unit
) {
    val supported = DatasetHelpers.isSupported(DatasetHelpers.safeFileName(fileName))
    var format by rememberSaveable(fileName) { mutableStateOf(DatasetHelpers.detectFormat(fileName)) }
    var columns by remember(fileName) {
        mutableStateOf(DatasetUiHelpers.defaultColumnMap(DatasetHelpers.detectFormat(fileName)))
    }
    var capMb by rememberSaveable(fileName) { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dataset_import_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.dataset_import_file, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (supported) {
                    DatasetFormatEditor(
                        format = format,
                        onFormatChange = { format = it },
                        columns = columns,
                        onColumnsChange = { columns = it },
                        capMb = capMb,
                        onCapChange = { capMb = it }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.dataset_import_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = supported,
                onClick = { onConfirm(format, DatasetUiHelpers.cleanColumnMap(format, columns), capMb) }
            ) { Text(stringResource(R.string.dataset_import_confirm), maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
