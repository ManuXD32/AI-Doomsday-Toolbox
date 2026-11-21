package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyClusterRun
import com.example.llamadroid.branchy.BranchyDevice
import com.example.llamadroid.branchy.BranchyRunEdit
import com.example.llamadroid.branchy.cluster.BranchyClusterDeviceEntity
import com.example.llamadroid.branchy.cluster.BranchyHelperStatus
import com.example.llamadroid.ui.components.NetInterfacePrefChips
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.distributed.branchy.BranchyTokenCard
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun clusterModeLabel(mode: String): String = stringResource(
    when (mode) {
        "branch_parallel" -> R.string.branchy_main_mode_branch
        "data_parallel" -> R.string.branchy_main_mode_data
        else -> R.string.branchy_main_mode_auto
    }
)

// region Step 3: devices

/** Display names for the planner's device ids (helpers by name, this phone as "this phone"). */
internal fun helperNames(helpers: List<BranchyClusterDeviceEntity>): Map<String, String> =
    helpers.associate { it.id to it.name }

internal fun LazyListScope.branchyDevicesStepItems(
    state: BranchyWizardState,
    helpers: List<BranchyClusterDeviceEntity>,
    helperStatus: Map<String, BranchyHelperStatus>,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEditDevice: (String, (ClusterDeviceEdit) -> ClusterDeviceEdit) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onComputePlan: () -> Unit,
    cores: Int = 1,
    bench: BranchyBenchState = BranchyBenchState(),
    onBenchmark: () -> Unit = {},
    onCancelBenchmark: () -> Unit = {},
    /** The paused-run editor has no all-devices benchmark card (it reuses cached local figures); per-helper buttons stay. */
    showBench: Boolean = true,
    /** (device id, auto|vpn|lan); null hides the Auto/VPN/LAN chips. */
    onPreferredInterface: ((String, String) -> Unit)? = null,
    /** Per-helper thread sliders (selected helpers) and the per-helper Benchmark button (every helper); null hides them. */
    onHelperThreads: ((String, Int?) -> Unit)? = null,
    onBenchHelper: ((String) -> Unit)? = null,
    /** Per-helper threads when they live outside [BranchyWizardState.helperThreads] (the paused-run editor). */
    helperThreads: Map<String, Int> = state.helperThreads
) {
    val plan = (state.plan as? BranchyPlanUi.Ready)?.plan
    val trainers = helpers.filter { it.roleEnum.canTrain }
    item(key = "devices_title") {
        AppSectionTitle(
            title = stringResource(R.string.branchy_main_devices_title),
            supporting = stringResource(R.string.branchy_main_devices_hint)
        )
    }
    item(key = "devices_threads") { ThreadsCard(state, cores, edit) }
    if (showBench) {
        item(key = "devices_bench") {
            BranchyBenchCard(bench, state.clusterDeviceIds.isNotEmpty(), onBenchmark, onCancelBenchmark)
        }
    }
    item(key = "devices_token") { BranchyTokenCard() }
    item(key = "devices_add") { ManualDeviceCard(onAdd) }
    if (trainers.isEmpty()) {
        item(key = "devices_empty") {
            Text(
                text = stringResource(R.string.branchy_main_devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    items(trainers.size, key = { "device_${trainers[it].id}" }) { index ->
        val device = trainers[index]
        val selected = device.id in state.clusterDeviceIds
        AppSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = selected, onCheckedChange = { onToggle(device.id, it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HelperStatusLabel(helperStatus[device.id])
            }
            if (onPreferredInterface != null && !device.altHost.isNullOrBlank()) {
                NetInterfacePrefChips(
                    pref = device.preferredInterface,
                    activeHost = helperStatus[device.id]?.answeredHost ?: device.host,
                    onChange = { onPreferredInterface(device.id, it) }
                )
            }
            if (selected) {
                val reason = plan?.unusedReason(device.id)
                if (reason != null && plan != null && !state.planStale) {
                    Text(
                        text = stringResource(R.string.branchy_r6_unused_prefix, unusedReasonText(reason, plan.layout.branches)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (onHelperThreads != null) {
                    HelperThreadsRow(
                        threads = helperThreads[device.id],
                        onThreads = { onHelperThreads(device.id, it) }
                    )
                }
                DeviceFields(
                    device, helperStatus[device.id], state.deviceEdits[device.id] ?: ClusterDeviceEdit()
                ) { block -> onEditDevice(device.id, block) }
            }
            if (onBenchHelper != null) {
                HelperBenchResult(bench, device.id)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (onBenchHelper != null) {
                    OutlinedButton(
                        onClick = { onBenchHelper(device.id) },
                        enabled = !bench.running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.branchy_r7_helper_bench), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(onClick = { onRemove(device.id) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.branchy_main_device_forget), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (state.clusterDeviceIds.isNotEmpty()) {
        item(key = "devices_mode") {
            AppSectionCard {
                AppSectionTitle(
                    title = stringResource(R.string.branchy_main_mode_title),
                    supporting = stringResource(R.string.branchy_main_mode_hint)
                )
                ChipRow {
                    BranchyWizardHelpers.clusterModes.forEach { m ->
                        FilterChip(
                            selected = state.clusterMode == m,
                            onClick = { edit { it.copy(clusterMode = m) } },
                            label = { Text(clusterModeLabel(m), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                plan?.dpFallbackWarning?.let { w ->
                    Text(
                        text = w,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    item(key = "devices_plan") { AutoPlanCard(state, onComputePlan, helperNames(helpers)) }
}

/** Localized reason a helper gets no work; [branches] is the model's branch count. */
@Composable
internal fun unusedReasonText(reason: String, branches: Int): String = when (reason) {
    "no_branch_left" -> stringResource(R.string.branchy_r6_unused_no_branch, branches)
    "ram" -> stringResource(R.string.branchy_r6_unused_ram)
    "slower" -> stringResource(R.string.branchy_r6_unused_slower)
    "dp_not_fit" -> stringResource(R.string.branchy_r6_unused_dp_not_fit)
    else -> stringResource(R.string.branchy_r6_unused_other)
}

/** Progress, error or "Best: N threads · X GFLOP/s" of helper [id]'s own benchmark. */
@Composable
private fun HelperBenchResult(bench: BranchyBenchState, id: String) {
    val curve = bench.curveFor(id)
    if (bench.running && bench.benchingId == id) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.branchy_r7_helper_benching),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else if (bench.errorId == id && bench.error != null) {
        Text(
            text = stringResource(R.string.branchy_bench_error, bench.error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    } else if (curve != null && curve.error != null) {
        Text(
            text = stringResource(R.string.branchy_bench_device_error, curve.error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    } else if (curve != null && curve.best > 0) {
        Text(
            text = pluralStringResource(
                R.plurals.branchy_r7_helper_best, curve.best, curve.best, String.format(Locale.US, "%.1f", curve.gflops)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Thread slider of one selected helper (unset = its own setting). */
@Composable
private fun HelperThreadsRow(
    threads: Int?,
    onThreads: (Int?) -> Unit
) {
    val value = threads ?: BranchyClusterRun.DEFAULT_HELPER_THREADS
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (threads != null) pluralStringResource(R.plurals.branchy_ui_threads_value, value, value)
                else stringResource(R.string.branchy_r6_helper_threads_own),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (threads != null) {
                TextButton(onClick = { onThreads(null) }) {
                    Text(stringResource(R.string.branchy_r6_helper_threads_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onThreads(v.roundToInt()) },
            valueRange = BranchyRunEdit.MIN_HELPER_THREADS.toFloat()..HELPER_THREADS_MAX
        )
    }
}

private const val HELPER_THREADS_MAX = 32f

/** Master CPU thread count (moved here from the Train step so single-device runs set it too). */
@Composable
private fun ThreadsCard(
    state: BranchyWizardState,
    cores: Int,
    edit: ((BranchyWizardState) -> BranchyWizardState) -> Unit
) {
    AppSectionCard {
        val threads = BranchyWizardHelpers.effectiveThreads(state, cores)
        AppSectionTitle(
            title = stringResource(R.string.branchy_ui_threads_title),
            supporting = stringResource(R.string.branchy_ui_threads_hint)
        )
        Text(text = pluralStringResource(R.plurals.branchy_ui_threads_value, threads, threads), style = MaterialTheme.typography.bodyMedium)
        if (cores > 1) {
            Slider(
                value = threads.toFloat(),
                onValueChange = { v -> edit { it.copy(threads = v.roundToInt().coerceIn(1, cores)) } },
                valueRange = 1f..cores.toFloat()
            )
        }
    }
}

/** Online + round trip / unreachable / checking (not probed yet), from the heartbeat probe. */
@Composable
private fun HelperStatusLabel(status: BranchyHelperStatus?) {
    val text = when {
        status == null -> stringResource(R.string.branchy_r5_helper_checking)
        !status.online -> stringResource(R.string.branchy_r5_helper_unreachable)
        status.rttMs != null -> stringResource(R.string.branchy_r5_helper_online_rtt, status.rttMs.toInt())
        else -> stringResource(R.string.branchy_r5_helper_online)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            status == null -> MaterialTheme.colorScheme.onSurfaceVariant
            status.online -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Remembers which texts this field already sent to the view model, so the (asynchronously delivered) committed
 * value coming back never overwrites what the user typed since. Only a genuinely different value (bench result,
 * preset) is adopted.
 */
internal class FieldSyncTracker {
    private val inFlight = HashSet<String>()

    fun sent(text: String) {
        inFlight.add(text)
    }

    /** The text to show instead of [local] after the committed value became [committed], or null to keep [local]. */
    fun adopt(committed: String, local: String): String? {
        if (committed == local) {
            inFlight.clear()
            return null
        }
        if (inFlight.contains(committed)) return null
        inFlight.clear()
        return committed
    }
}

/**
 * Text field with its own saved state (keyed by [key], e.g. the device id) that is committed to the view model
 * on every edit. The local text is the source of truth while typing, which avoids IME/state desync.
 */
@Composable
internal fun CommittedTextField(
    key: String,
    committed: String,
    onCommit: (String) -> Unit,
    filter: (String) -> String,
    label: @Composable () -> Unit,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null
) {
    var text by rememberSaveable(key) { mutableStateOf(committed) }
    val tracker = remember(key) { FieldSyncTracker() }
    LaunchedEffect(committed) { tracker.adopt(committed, text)?.let { text = it } }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val filtered = filter(v)
            text = filtered
            tracker.sent(filtered)
            onCommit(filtered)
        },
        label = label,
        placeholder = placeholder,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = modifier
    )
}

@Composable
private fun DeviceFields(
    device: BranchyClusterDeviceEntity,
    status: BranchyHelperStatus?,
    edit: ClusterDeviceEdit,
    onChange: ((ClusterDeviceEdit) -> ClusterDeviceEdit) -> Unit
) {
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    // Untyped fields show what the helper reported (probe) or what was saved; the planner uses the same values.
    val ram = status?.specs?.ramFreeMb?.toLong() ?: device.ramMb?.toLong()
    val gflops = status?.specs?.gflops ?: device.gflops
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CommittedTextField(
            key = "ram_${device.id}",
            committed = edit.ramMb,
            onCommit = { v -> onChange { it.copy(ramMb = v) } },
            filter = { v -> v.filter { c -> c.isDigit() }.take(7) },
            label = { Text(stringResource(R.string.branchy_main_device_ram), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            placeholder = { Text(ram?.toString() ?: "?") }
        )
        CommittedTextField(
            key = "gflops_${device.id}",
            committed = edit.gflops,
            onCommit = { v -> onChange { it.copy(gflops = v) } },
            filter = { v -> v.filter { c -> c.isDigit() || c == '.' }.take(8) },
            label = { Text(stringResource(R.string.branchy_main_device_gflops), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = numbers,
            modifier = Modifier.weight(1f),
            placeholder = { Text(gflops?.let { String.format(Locale.US, "%.1f", it) } ?: "?") }
        )
        CommittedTextField(
            key = "link_${device.id}",
            committed = edit.linkMbps,
            onCommit = { v -> onChange { it.copy(linkMbps = v) } },
            filter = { v -> v.filter { c -> c.isDigit() || c == '.' }.take(8) },
            label = { Text(stringResource(R.string.branchy_main_device_link), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = numbers,
            modifier = Modifier.weight(1f),
            placeholder = { Text(String.format(Locale.US, "%.0f", BranchyDevice.DEFAULT_LINK_MBPS)) }
        )
    }
}

@Composable
private fun ManualDeviceCard(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_main_manual_title),
            supporting = stringResource(R.string.branchy_main_manual_hint)
        )
        Text(
            text = stringResource(R.string.branchy_r5_vpn_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(80) },
            label = { Text(stringResource(R.string.branchy_main_manual_label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onAdd(text); text = "" },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.branchy_main_manual_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// endregion
