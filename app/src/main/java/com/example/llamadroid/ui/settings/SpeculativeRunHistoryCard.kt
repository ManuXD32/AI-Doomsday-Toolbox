package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaSpeculativeRunEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpeculativeRunHistoryCard(
    runs: List<LlamaSpeculativeRunEntity>,
    onRename: (LlamaSpeculativeRunEntity, String) -> Unit,
    onToggleSaved: (LlamaSpeculativeRunEntity) -> Unit,
    onDelete: (LlamaSpeculativeRunEntity) -> Unit
) {
    var renameRun by remember { mutableStateOf<LlamaSpeculativeRunEntity?>(null) }
    var renameText by remember(renameRun?.id) { mutableStateOf(renameRun?.name.orEmpty()) }
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.llm_speculative_runs_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.llm_speculative_runs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (runs.isEmpty()) {
                Text(
                    text = stringResource(R.string.llm_speculative_runs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                runs.forEach { run ->
                    SpeculativeRunRow(
                        run = run,
                        timestamp = dateFormat.format(Date(run.createdAt)),
                        onRename = { renameRun = run },
                        onToggleSaved = { onToggleSaved(run) },
                        onDelete = { onDelete(run) }
                    )
                }
            }
        }
    }

    renameRun?.let { run ->
        AlertDialog(
            onDismissRequest = { renameRun = null },
            title = { Text(stringResource(R.string.llm_speculative_run_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.llm_speculative_run_name_label)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(run, renameText.trim())
                        renameRun = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameRun = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SpeculativeRunRow(
    run: LlamaSpeculativeRunEntity,
    timestamp: String,
    onRename: () -> Unit,
    onToggleSaved: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.llm_speculative_run_unnamed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (run.savedForever) {
                    AssistChip(
                        onClick = onToggleSaved,
                        label = { Text(stringResource(R.string.llm_speculative_run_saved)) },
                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            Text(
                text = run.modelName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(run.speculativeMode)
                    run.draftModelName?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (run.sampleCount > 0) {
                    stringResource(
                        R.string.llm_speculative_run_metrics,
                        formatPercent(run.acceptanceRate),
                        formatSpeed(run.promptTokensPerSecond),
                        formatSpeed(run.generationTokensPerSecond),
                        run.sampleCount
                    )
                } else {
                    stringResource(R.string.llm_speculative_run_waiting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRename) {
                    Text(stringResource(R.string.llm_speculative_run_rename))
                }
                TextButton(onClick = onToggleSaved) {
                    Text(
                        stringResource(
                            if (run.savedForever) {
                                R.string.llm_speculative_run_unsave
                            } else {
                                R.string.llm_speculative_run_save_forever
                            }
                        )
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatPercent(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-"

private fun formatSpeed(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
