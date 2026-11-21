package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyDeviceStatus
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyRejoinPhase
import com.example.llamadroid.branchy.BranchyWaiting
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import kotlinx.coroutines.delay
import java.util.Locale

/** "12s", "3m 05s" or "1h 02m" for an elapsed time; negative values count as 0. */
internal fun branchyAgo(elapsedMs: Long): String {
    val s = (elapsedMs.coerceAtLeast(0L) / 1000L)
    return when {
        s >= 3600L -> String.format(Locale.US, "%dh %02dm", s / 3600L, (s % 3600L) / 60L)
        s >= 60L -> String.format(Locale.US, "%dm %02ds", s / 60L, s % 60L)
        else -> "${s}s"
    }
}

/** One row per helper: status chip, RTT, speed, RAM, steps and a relative "last sync" that ticks once a second. */
@Composable
internal fun BranchyDeviceStatsCard(
    devices: List<BranchyLiveDevice>,
    dataParallel: Boolean = false,
    onRetry: (String) -> Unit = {},
    onForget: (String) -> Unit = {}
) {
    var confirmForget by remember { mutableStateOf<String?>(null) }
    val anySync = devices.any { it.lastSyncAt > 0L }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    // Ticks only while the screen is at least STARTED (visible); stops by itself in the background.
    // Also ticks while a reconnect attempt is shown (countdown to the next try, brief "Reconnected").
    val nowWall = System.currentTimeMillis()
    val needsTick = anySync || devices.any {
        it.retryRequestedAt > 0L || it.rejoin?.phase == BranchyRejoinPhase.TRYING ||
            it.rejoin?.phase == BranchyRejoinPhase.FAILED || it.justReconnected(nowWall)
    }
    LaunchedEffect(owner, needsTick) {
        if (needsTick) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000L)
                }
            }
        }
    }
    AppSectionCard {
        AppSectionTitle(title = stringResource(R.string.branchy_r5d_devices_title))
        devices.forEach { d -> BranchyDeviceRow(d, now, dataParallel, onRetry) { confirmForget = it } }
    }
    val pending = confirmForget
    if (pending != null) {
        val name = devices.firstOrNull { it.id == pending }?.name ?: pending
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text(stringResource(R.string.branchy_r6d_forget_confirm_title, name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.branchy_r6d_forget_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = null; onForget(pending) }) {
                    Text(stringResource(R.string.branchy_r6d_stop_trying), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

/** Status text of one row: resting and idle (DP waiting for the others) get their own labels, distinct from LOST. */
@Composable
internal fun branchyDeviceStatusLabel(d: BranchyLiveDevice): String = when {
    d.resting -> stringResource(R.string.branchy_r6d_status_resting)
    d.status == BranchyDeviceStatus.IDLE -> stringResource(R.string.branchy_r6d_status_idle)
    else -> branchyStatusLabel(d.status)
}

@Composable
private fun BranchyDeviceRow(
    d: BranchyLiveDevice,
    now: Long,
    dataParallel: Boolean,
    onRetry: (String) -> Unit,
    onForgetRequest: (String) -> Unit
) {
    val status = d.status
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = d.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            DatasetChip(
                text = branchyDeviceStatusLabel(d),
                warning = status == BranchyDeviceStatus.LOST || status == BranchyDeviceStatus.UNREACHABLE
            )
        }
        val parts = buildList {
            d.rttMs?.let { add(stringResource(R.string.branchy_r5d_dev_rtt, it.toString())) }
            if (d.tokS > 0.0) add(stringResource(R.string.branchy_r5d_dev_speed, String.format(Locale.US, "%.1f", d.tokS)))
            if (d.rssMb > 0.0) add(stringResource(R.string.branchy_r5d_dev_ram, BranchyWizardHelpers.formatMb(d.rssMb)))
            if (d.steps > 0L) add(stringResource(R.string.branchy_r5d_dev_steps, d.steps.toString()))
        }
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (d.lastSyncAt > 0L) {
                stringResource(R.string.branchy_r5d_dev_sync_ago, branchyAgo(now - d.lastSyncAt))
            } else {
                stringResource(R.string.branchy_r5d_dev_sync_never)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        d.endpointHost?.takeIf { it.isNotBlank() }?.let { host ->
            Text(
                text = stringResource(R.string.branchy_r6d_address, host),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (d.justReconnected(now) && status != BranchyDeviceStatus.LOST && status != BranchyDeviceStatus.WAITING) {
            Text(
                text = stringResource(R.string.branchy_r7_rejoin_joined),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (status == BranchyDeviceStatus.LOST || status == BranchyDeviceStatus.WAITING) {
            val retrying = d.retryPending(now)
            val rejoin = d.rejoin
            val failedReason = rejoin?.reason?.takeIf { it.isNotBlank() }
            val failedIn = rejoin?.nextRetryAtMs?.takeIf { it > 0L }?.let { branchyAgo(it - now) }
            val rejoinLine: String? = when {
                retrying -> stringResource(R.string.branchy_r7_rejoin_trying)
                rejoin?.phase != BranchyRejoinPhase.FAILED -> null
                failedReason != null && failedIn != null -> stringResource(R.string.branchy_r7_rejoin_failed, failedReason, failedIn)
                failedReason != null -> stringResource(R.string.branchy_r7_rejoin_failed_no_retry, failedReason)
                failedIn != null -> stringResource(R.string.branchy_r7_rejoin_failed_noreason, failedIn)
                else -> stringResource(R.string.branchy_r7_rejoin_failed_bare)
            }
            if (rejoinLine != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (retrying) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = rejoinLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.weight(1f, fill = false),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onRetry(d.id) },
                    enabled = !retrying,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    if (retrying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.branchy_r6d_retry_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (dataParallel) {
                    OutlinedButton(
                        onClick = { onForgetRequest(d.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.branchy_r6d_stop_trying), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!dataParallel) {
                Text(
                    text = stringResource(R.string.branchy_r5d_not_skippable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Shown while the coordinator waits on helpers. Skippable (data-parallel): one "Continue without X" button per lost
 * device, each behind a confirm dialog. Not skippable: a hint to pause and edit the devices.
 */
@Composable
internal fun BranchyWaitingCard(
    waiting: BranchyWaiting,
    devices: List<BranchyLiveDevice>,
    onSkip: (String) -> Unit
) {
    val names = waiting.lost.map { id -> devices.firstOrNull { it.id == id }?.name ?: id }
    var confirmId by remember { mutableStateOf<String?>(null) }
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_r5d_waiting_title, names.joinToString(", ")),
            supporting = stringResource(
                if (waiting.phase == "join") R.string.branchy_r5d_waiting_join else R.string.branchy_r5d_waiting_run
            )
        )
        if (waiting.skippable) {
            waiting.lost.forEachIndexed { i, id ->
                OutlinedButton(
                    onClick = { confirmId = id },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.branchy_r5d_skip_button, names[i]),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.branchy_r5d_not_skippable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val pending = confirmId
    if (pending != null) {
        val name = devices.firstOrNull { it.id == pending }?.name ?: pending
        AlertDialog(
            onDismissRequest = { confirmId = null },
            title = { Text(stringResource(R.string.branchy_r5d_skip_confirm_title, name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.branchy_r5d_skip_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmId = null; onSkip(pending) }) {
                    Text(stringResource(R.string.branchy_r5d_skip_button, name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmId = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
