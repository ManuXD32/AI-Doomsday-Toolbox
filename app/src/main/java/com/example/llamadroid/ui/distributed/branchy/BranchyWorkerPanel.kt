package com.example.llamadroid.ui.distributed.branchy

import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.training.copyToClipboard
import com.example.llamadroid.branchy.cluster.BranchyTrainingProgress
import com.example.llamadroid.branchy.cluster.BranchyWorkerStorageEntry
import com.example.llamadroid.branchy.cluster.BranchyWorkerStorageKind
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.branchy.cluster.BranchyInferenceStatus
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.components.AppTaskActionFooter
import com.example.llamadroid.ui.training.BranchyBenchCard

/**
 * Worker side of a Branchy training cluster (token, roles, settings, status, log). Scrolls on its own, so hosts
 * must not wrap it in another vertical scroller. Shared by [BranchyHelperScreen] and the trainer's worker tab.
 */
@Composable
fun BranchyWorkerPanel(vm: BranchyHelperViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val running by vm.running.collectAsState()
    val waitingMaster by vm.waitingMaster.collectAsState()
    val workerThreads by vm.workerThreads.collectAsState()
    val port by vm.port.collectAsState()
    val logs by vm.logs.collectAsState()
    val tokenPreview: String? by vm.tokenPreview.collectAsState()
    val name by vm.name.collectAsState()
    val portText by vm.portText.collectAsState()
    val threadsText by vm.threadsText.collectAsState()
    val addresses by vm.addresses.collectAsState()
    val helpTrain by vm.helpTrain.collectAsState()
    val helpInfer by vm.helpInfer.collectAsState()
    val ctxText by vm.ctxText.collectAsState()
    val batteryGuard by vm.batteryGuard.collectAsState()
    val batteryMinText by vm.batteryMinText.collectAsState()
    val slice by vm.slice.collectAsState()
    val inferStatus by vm.inferenceStatus.collectAsState()
    val inferExit by vm.inferenceExitCode.collectAsState()
    val inferPort by vm.inferencePort.collectAsState()
    val inferLines by vm.inferenceLines.collectAsState()
    val lowBatteryPaused by vm.lowBatteryPaused.collectAsState()
    val copying by vm.copying.collectAsState()
    val bench by vm.bench.collectAsState()
    val training by vm.training.collectAsState()
    val storage by vm.storage.collectAsState()
    val runName by vm.runName.collectAsState()
    var tokenInput by remember { mutableStateOf("") }
    val slicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSlice(uri)
    }

    LaunchedEffect(vm) {
        vm.refreshAddresses()
        vm.notices.collect { res -> Toast.makeText(context, resources.getString(res), Toast.LENGTH_LONG).show() }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppChromeDefaults.ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
    ) {
        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_cluster_status_title),
                supporting = stringResource(
                    if (running) R.string.branchy_cluster_status_running else R.string.branchy_cluster_status_stopped
                )
            )
            val shownPort = port
            if (running && shownPort != null) {
                val endpoints = BranchyHelperViewModel.endpoints(addresses, shownPort)
                if (endpoints.isEmpty()) {
                    Text(
                        stringResource(R.string.branchy_cluster_address_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    endpoints.forEach { endpoint ->
                        Text(
                            stringResource(R.string.branchy_cluster_address, endpoint),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (running) {
                Text(
                    stringResource(
                        if (waitingMaster) R.string.branchy_r4_worker_status_waiting else R.string.branchy_r4_worker_status_connected
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (waitingMaster) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
                val shownThreads = workerThreads
                if (shownThreads != null) {
                    Text(stringResource(R.string.branchy_r4_worker_threads, shownThreads), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.branchy_cluster_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Button(onClick = vm::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.branchy_cluster_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                stringResource(R.string.branchy_cluster_keep_awake_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        BranchyBenchCard(
            state = bench,
            hasHelpers = false,
            onBenchmark = vm::runBenchmark,
            onCancel = vm::cancelBenchmark,
            blockedHint = if (running) stringResource(R.string.branchy_bench_worker_stop_hint) else null
        )

        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_cluster_roles_title),
                supporting = stringResource(
                    if (running) R.string.branchy_cluster_roles_locked else R.string.branchy_cluster_roles_hint
                )
            )
            RoleSwitchRow(stringResource(R.string.branchy_cluster_role_train), helpTrain, !running, vm::setHelpTrain)
            RoleSwitchRow(stringResource(R.string.branchy_cluster_role_infer), helpInfer, !running, vm::setHelpInfer)
            if (lowBatteryPaused) {
                Text(
                    stringResource(R.string.branchy_cluster_battery_paused_status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (helpInfer) {
            AppSectionCard {
                AppSectionTitle(
                    title = stringResource(R.string.branchy_cluster_infer_title),
                    supporting = stringResource(R.string.branchy_cluster_infer_hint)
                )
                val shownSlice = slice
                if (shownSlice == null) {
                    Text(
                        stringResource(R.string.branchy_cluster_slice_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(R.string.branchy_cluster_slice_name, shownSlice.name),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        stringResource(
                            R.string.branchy_cluster_slice_size,
                            Formatter.formatFileSize(context, shownSlice.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (shownSlice.branches.isEmpty()) stringResource(R.string.branchy_cluster_slice_branches_unknown)
                        else stringResource(R.string.branchy_cluster_slice_branches, shownSlice.branches.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    when (inferStatus) {
                        BranchyInferenceStatus.RUNNING -> stringResource(R.string.branchy_cluster_infer_running, inferPort ?: 0)
                        BranchyInferenceStatus.EXITED ->
                            stringResource(R.string.branchy_cluster_infer_exited, inferExit ?: -1)
                        BranchyInferenceStatus.OFF -> stringResource(R.string.branchy_cluster_infer_off)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                if (inferLines.isNotEmpty()) {
                    Text(
                        stringResource(R.string.branchy_cluster_infer_output_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            inferLines.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                OutlinedButton(
                    onClick = { slicePicker.launch(arrayOf("*/*")) },
                    enabled = !copying,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (copying) R.string.branchy_cluster_slice_copying else R.string.branchy_cluster_slice_pick
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_cluster_token_title),
                supporting = stringResource(R.string.branchy_cluster_token_hint)
            )
            Text(
                text = tokenPreview?.let { stringResource(R.string.branchy_cluster_token_set, it) }
                    ?: stringResource(R.string.branchy_cluster_token_missing),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text(stringResource(R.string.branchy_cluster_token_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { tokenInput = readClipboard(context) }) {
                    Text(stringResource(R.string.branchy_cluster_token_paste), maxLines = 1)
                }
                Button(
                    onClick = { if (vm.saveToken(tokenInput)) tokenInput = "" },
                    enabled = tokenInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.branchy_cluster_token_save), maxLines = 1)
                }
                if (tokenPreview != null) {
                    TextButton(onClick = vm::clearToken) {
                        Text(stringResource(R.string.branchy_cluster_token_clear), maxLines = 1)
                    }
                }
            }
        }

        AppSectionCard {
            AppSectionTitle(
                title = stringResource(R.string.branchy_cluster_settings_title),
                supporting = if (running) stringResource(R.string.branchy_cluster_settings_locked) else null
            )
            OutlinedTextField(
                value = name,
                onValueChange = vm::setName,
                label = { Text(stringResource(R.string.branchy_cluster_setting_name)) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = vm::setPortText,
                    label = { Text(stringResource(R.string.branchy_cluster_setting_port), maxLines = 1) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = threadsText,
                    onValueChange = vm::setThreadsText,
                    label = { Text(stringResource(R.string.branchy_cluster_setting_threads), maxLines = 1) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            if (helpInfer) {
                OutlinedTextField(
                    value = ctxText,
                    onValueChange = vm::setCtxText,
                    label = { Text(stringResource(R.string.branchy_cluster_setting_ctx), maxLines = 1) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            RoleSwitchRow(stringResource(R.string.branchy_cluster_setting_battery_guard), batteryGuard, !running, vm::setBatteryGuard)
            if (batteryGuard) {
                OutlinedTextField(
                    value = batteryMinText,
                    onValueChange = vm::setBatteryMinText,
                    label = { Text(stringResource(R.string.branchy_cluster_setting_battery_min), maxLines = 1) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        val shownTraining = training
        if (running && shownTraining != null) {
            BranchyTrainingCard(shownTraining, shownTraining.run ?: runName)
        }

        if (storage.isNotEmpty()) {
            BranchyStorageCard(storage, running, vm::deleteStorage)
        }

        AppSectionCard {
            AppSectionTitle(title = stringResource(R.string.branchy_cluster_log_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 260.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (logs.isEmpty()) {
                    Text(
                        stringResource(R.string.branchy_cluster_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        logs.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (logs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::clearLogs) {
                        Text(stringResource(R.string.branchy_cluster_log_clear), maxLines = 1)
                    }
                    TextButton(onClick = { copyToClipboard(context, "Branchy worker log", logs.joinToString("\n")) }) {
                        Text(stringResource(R.string.action_copy), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleSwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
}

private fun etaText(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    return if (total >= 3600) {
        java.lang.String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, total % 3600 / 60, total % 60)
    } else {
        java.lang.String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }
}

@Composable
private fun BranchyTrainingCard(p: BranchyTrainingProgress, runName: String?) {
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.branchy_r6w_training_title))
        val small = MaterialTheme.typography.bodyMedium
        Text(
            stringResource(if (p.dataParallel) R.string.branchy_r6w_role_dp else R.string.branchy_r6w_role_bp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (runName != null) {
            Text(stringResource(R.string.branchy_r6w_run, runName), style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (p.dataParallel) {
            val phase = when (p.phase) {
                "compute" -> R.string.branchy_r6w_phase_compute
                "upload" -> R.string.branchy_r6w_phase_upload
                "wait" -> R.string.branchy_r6w_phase_wait
                "resting" -> R.string.branchy_r6w_phase_resting
                else -> null
            }
            Text(stringResource(R.string.branchy_r6w_round, p.round), style = small)
            if (phase != null) Text(stringResource(R.string.branchy_r6w_phase, stringResource(phase)), style = small)
            Text(stringResource(R.string.branchy_r6w_micro_dp, p.done, p.total), style = small)
            Text(stringResource(R.string.branchy_r6w_left, p.left), style = small)
            p.msPerMicro?.let { Text(stringResource(R.string.branchy_r6w_ms_per_micro, it.toInt()), style = small) }
            p.etaSeconds?.let { Text(stringResource(R.string.branchy_r6w_eta, etaText(it)), style = small) }
        } else {
            Text(stringResource(R.string.branchy_r6w_micro_bp, p.round, p.done, p.total), style = small)
            if (p.msFwd != null || p.msBwd != null) {
                Text(
                    stringResource(R.string.branchy_r6w_fwd_bwd, (p.msFwd ?: 0.0).toInt(), (p.msBwd ?: 0.0).toInt()),
                    style = small
                )
            }
        }
    }
}

@Composable
private fun BranchyStorageCard(
    entries: List<BranchyWorkerStorageEntry>,
    running: Boolean,
    onDelete: (BranchyWorkerStorageEntry?) -> Unit
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<BranchyWorkerStorageEntry?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val total = entries.sumOf { it.bytes }
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r6w_storage_title),
            supporting = stringResource(
                R.string.branchy_r6w_storage_total,
                FormatUtils.Display.formatBytes(context, total)
            )
        )
        entries.forEach { entry ->
            val label = entry.name ?: when (entry.kind) {
                BranchyWorkerStorageKind.DP_DATA -> stringResource(R.string.branchy_r6w_storage_dp_data)
                else -> entry.id
            }
            val kind = stringResource(
                when (entry.kind) {
                    BranchyWorkerStorageKind.SHARDS -> R.string.branchy_r6w_storage_kind_shards
                    BranchyWorkerStorageKind.DP_RUN, BranchyWorkerStorageKind.DP_DATA -> R.string.branchy_r6w_storage_kind_dp
                    BranchyWorkerStorageKind.SLICE -> R.string.branchy_r6w_storage_kind_slice
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val used = if (entry.lastUsedMs > 0) dateFormat.format(java.util.Date(entry.lastUsedMs)) else "-"
                    Text(
                        stringResource(
                            R.string.branchy_r6w_storage_row, kind,
                            FormatUtils.Display.formatBytes(context, entry.bytes), used
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { pending = entry }, enabled = !running) {
                    Text(stringResource(R.string.branchy_r6w_storage_delete), maxLines = 1)
                }
            }
        }
        if (running) {
            Text(
                stringResource(R.string.branchy_r6w_storage_running_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = { confirmAll = true }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.branchy_r6w_storage_delete_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val target = pending
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.branchy_r6w_storage_confirm_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.branchy_r6w_storage_confirm_one, target.name ?: target.id))
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target); pending = null }) { Text(stringResource(R.string.branchy_r6w_storage_delete)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.branchy_r6w_storage_delete_all), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.branchy_r6w_storage_confirm_all, FormatUtils.Display.formatBytes(context, total)))
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(null); confirmAll = false }) { Text(stringResource(R.string.branchy_r6w_storage_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}
