package com.example.llamadroid.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.branchy.BranchyRunStorage
import com.example.llamadroid.branchy.BranchyRunStorageScan
import com.example.llamadroid.branchy.BranchyStoragePart
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyRunConfig
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunStatus

@Composable
internal fun runStatusLabel(status: BranchyRunStatus): String = stringResource(
    when (status) {
        BranchyRunStatus.DRAFT -> R.string.branchy_ui_status_draft
        BranchyRunStatus.PREPARING -> R.string.branchy_ui_status_preparing
        BranchyRunStatus.TRAINING -> R.string.branchy_ui_status_training
        BranchyRunStatus.PAUSED -> R.string.branchy_ui_status_paused
        BranchyRunStatus.DONE -> R.string.branchy_ui_status_done
        BranchyRunStatus.FAILED -> R.string.branchy_ui_status_failed
    }
)

/** The runs list: one card per run; tap opens the live view. */
internal fun LazyListScope.branchyRunsItems(
    runs: List<BranchyRunEntity>,
    live: BranchyLiveState,
    onOpen: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onFreeSpace: (Long) -> Unit = {}
) {
    if (runs.isEmpty()) {
        item(key = "runs_empty") {
            Text(
                text = stringResource(R.string.branchy_ui_runs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    items(runs, key = { "run_${it.id}" }) { run ->
        BranchyRunCard(run, live, onOpen, onPause, onResume, onDelete, onFreeSpace)
    }
}

@Composable
private fun BranchyRunCard(
    run: BranchyRunEntity,
    live: BranchyLiveState,
    onOpen: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onFreeSpace: (Long) -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmFree by remember { mutableStateOf(false) }
    val status = BranchyRunStatus.fromValue(run.status)
    val isLive = isLiveRun(run, live)
    val canFreeSpace = status == BranchyRunStatus.DONE || status == BranchyRunStatus.FAILED ||
        status == BranchyRunStatus.PAUSED
    val active = isLive && live.isActive
    val stageCount = remember(run.configJson) {
        BranchyRunConfig.decode(run.configJson)?.effectiveStages()?.size ?: 1
    }
    val step = if (isLive) live.lastStep?.step ?: run.lastStep else run.lastStep
    val total = if (isLive && live.totalSteps > 0L) live.totalSteps else run.totalSteps
    val loss = if (isLive) live.lastStep?.loss ?: run.lastLoss else run.lastLoss
    val eta = if (isLive) live.lastStep?.etaS else null

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(run.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = run.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DatasetChip(
                    text = if (active) runStatusLabel(BranchyRunStatus.TRAINING) else runStatusLabel(status),
                    warning = status == BranchyRunStatus.FAILED
                )
            }
            Text(
                text = stringResource(
                    R.string.branchy_ui_run_line,
                    (if (isLive) live.stage else run.stage) + 1, stageCount, step, total,
                    BranchyFormat.loss(loss), BranchyFormat.duration(eta ?: Double.NaN)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { BranchyWizardHelpers.runFraction(step, total) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != BranchyRunStatus.DONE) {
                    OutlinedButton(
                        onClick = { if (active) onPause(run.id) else onResume(run.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    ) {
                        Text(
                            stringResource(
                                when {
                                    active -> R.string.action_pause
                                    status == BranchyRunStatus.DRAFT -> R.string.action_start
                                    else -> R.string.action_resume
                                }
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!active) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    ) {
                        Text(stringResource(R.string.action_delete), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!active && canFreeSpace) {
                OutlinedButton(
                    onClick = { confirmFree = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) {
                    Text(
                        stringResource(R.string.branchy_r6_storage_free_action),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        val storage = rememberRunStorage(run)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.branchy_ui_delete_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.branchy_r6_storage_delete_body, run.name))
                    StoragePartRows(storage)
                    Text(
                        stringResource(R.string.branchy_r6_storage_exports_kept),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(run.id) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    if (confirmFree) {
        val storage = rememberRunStorage(run)
        AlertDialog(
            onDismissRequest = { confirmFree = false },
            title = { Text(stringResource(R.string.branchy_r6_storage_free_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.branchy_r6_storage_free_body,
                            FormatUtils.Display.formatBytes(LocalContext.current, storage?.freeable(status == BranchyRunStatus.PAUSED) ?: 0L)
                        )
                    )
                    if (status == BranchyRunStatus.PAUSED) {
                        Text(
                            stringResource(R.string.branchy_r6_storage_free_paused_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = storage != null && storage.freeable(status == BranchyRunStatus.PAUSED) > 0L,
                    onClick = { confirmFree = false; onFreeSpace(run.id) }
                ) { Text(stringResource(R.string.branchy_r6_storage_free_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFree = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Scans a run's directory once (off the main thread); null while loading. */
@Composable
private fun rememberRunStorage(run: BranchyRunEntity): BranchyRunStorage? {
    val state = produceState<BranchyRunStorage?>(null, run.id, run.runDir) {
        value = withContext(Dispatchers.IO) { BranchyRunStorageScan.scan(File(run.runDir)) }
    }
    return state.value
}

@Composable
private fun StoragePartRows(storage: BranchyRunStorage?) {
    val context = LocalContext.current
    if (storage == null) {
        Text(
            stringResource(R.string.model_storage_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val rows = listOf(
            BranchyStoragePart.TOKENIZED to R.string.branchy_r6_storage_part_tokenized,
            BranchyStoragePart.TOKENIZER to R.string.branchy_r6_storage_part_tokenizer,
            BranchyStoragePart.CHECKPOINTS to R.string.branchy_r6_storage_part_checkpoints,
            BranchyStoragePart.MIRROR to R.string.branchy_r6_storage_part_mirror,
            BranchyStoragePart.DP to R.string.branchy_r6_storage_part_dp,
            BranchyStoragePart.EXPORTS to R.string.branchy_r6_storage_part_exports,
            BranchyStoragePart.OTHER to R.string.branchy_r6_storage_part_other
        )
        rows.forEach { (part, label) ->
            if (storage.of(part) > 0L || part == BranchyStoragePart.EXPORTS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        FormatUtils.Display.formatBytes(context, storage.of(part)),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
