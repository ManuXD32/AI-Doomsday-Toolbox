package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyCheckpointInfo
import com.example.llamadroid.branchy.BranchyCheckpointRequest
import com.example.llamadroid.branchy.BranchyCkptStage
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyTrainingState
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyPauseReason
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.service.BranchyTrainingService
import com.example.llamadroid.ui.components.AppSectionTitle
import java.io.File

/** True when [live] describes [run] (the service owns it or just finished it). */
internal fun isLiveRun(run: BranchyRunEntity?, live: BranchyLiveState): Boolean =
    run != null && live.runId == run.id && live.phase != BranchyPhase.IDLE

@Composable
internal fun phaseLabel(phase: BranchyPhase): String = stringResource(
    when (phase) {
        BranchyPhase.IDLE -> R.string.branchy_ui_phase_idle
        BranchyPhase.TOKENIZER -> R.string.branchy_ui_phase_tokenizer
        BranchyPhase.PREPARE -> R.string.branchy_ui_phase_prepare
        BranchyPhase.TRAIN -> R.string.branchy_ui_phase_train
        BranchyPhase.PAUSING -> R.string.branchy_ui_phase_pausing
        BranchyPhase.PAUSED -> R.string.branchy_ui_phase_paused
        BranchyPhase.WAITING_NETWORK -> R.string.branchy_r4_phase_waiting
        BranchyPhase.DONE -> R.string.branchy_ui_phase_done
        BranchyPhase.ERROR -> R.string.branchy_ui_phase_error
    }
)

@Composable
internal fun pauseReasonLabel(reason: BranchyPauseReason): String = stringResource(
    when (reason) {
        BranchyPauseReason.USER -> R.string.branchy_pause_reason_user
        BranchyPauseReason.BATTERY_LOW -> R.string.branchy_pause_reason_battery_low
        BranchyPauseReason.UNPLUGGED -> R.string.branchy_pause_reason_unplugged
        BranchyPauseReason.BATTERY_HOT -> R.string.branchy_pause_reason_hot
        BranchyPauseReason.RESTORED -> R.string.branchy_pause_reason_restored
    }
)

/** Live view of one run: progress, metrics, loss chart and samples. The whole page scrolls. */
@Composable
fun BranchyLiveView(
    run: BranchyRunEntity?,
    live: BranchyLiveState,
    modifier: Modifier = Modifier,
    exportSection: (@Composable () -> Unit)? = null
) {
    if (run == null) {
        Column(modifier = modifier.padding(AppChromeDefaults.ScreenPadding)) {
            Text(text = stringResource(R.string.branchy_ui_run_missing), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        BranchyLiveBody(run, live, modifier, exportSection)
    }
}

@Composable
private fun BranchyLiveBody(
    run: BranchyRunEntity,
    live: BranchyLiveState,
    modifier: Modifier,
    exportSection: (@Composable () -> Unit)?
) {
    val context = LocalContext.current
    val isLive = isLiveRun(run, live)
    val lossSeries = if (isLive) live.lossPoints else emptyList()
    val data = BranchyHeroData(
        isLive = isLive,
        step = if (isLive) live.lastStep?.step ?: run.lastStep else run.lastStep,
        total = if (isLive && live.totalSteps > 0L) live.totalSteps else run.totalSteps,
        loss = if (isLive) live.lastStep?.loss ?: run.lastLoss else run.lastLoss,
        lossDelta = if (lossSeries.size >= 2) lossSeries[lossSeries.size - 1].second - lossSeries[lossSeries.size - 2].second else null,
        last = if (isLive) live.lastStep else null,
        stageCount = if (isLive) live.stageCount else 1,
        stageIndex = if (isLive) live.stage else run.stage
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppChromeDefaults.ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AppChromeDefaults.SectionSpacing)
    ) {
        BranchyHeroCard(run, live, data)

        // The pulse runs only while training; WAITING_NETWORK / PAUSED / DONE / ERROR draw static links.
        if (isLive && live.devices.isNotEmpty()) BranchyClusterCard(live, animate = live.phase == BranchyPhase.TRAIN)
        val waiting = live.waiting
        if (isLive && waiting != null) {
            BranchyWaitingCard(waiting, live.devices) { helperId -> BranchyTrainingService.skipHelper(context, run.id, helperId) }
        }
        if (isLive && live.devices.isNotEmpty()) {
            val dataParallel = live.clusterMode == "data_parallel"
            BranchyDeviceStatsCard(
                devices = live.devices,
                dataParallel = dataParallel,
                onRetry = { id -> BranchyTrainingService.retryHelper(context, run.id, id) },
                onForget = { id -> BranchyTrainingService.forgetHelper(context, run.id, id) }
            )
            val hasLoad = if (dataParallel) live.dpRound != null || live.roundHistory.isNotEmpty()
            else live.bpProposal != null || live.devices.any { it.stepMs > 0.0 }
            if (hasLoad || live.rebalancing) {
                BranchyLoadCard(
                    live = live,
                    resetKey = run.id,
                    onRest = { id -> BranchyTrainingService.rest(context, run.id, id) },
                    onUnrest = { id -> BranchyTrainingService.unrest(context, run.id, id) },
                    onRebalance = { BranchyTrainingService.rebalance(context, run.id) }
                )
            }
        }
        val checkpoint = if (isLive) live.lastCheckpoint else null
        if (checkpoint != null) BranchyLastCheckpointText(checkpoint)

        AppSectionCard {
            AppSectionTitle(title = stringResource(R.string.branchy_ui_chart_title))
            BranchyLossChart(
                lossPoints = lossSeries,
                valPoints = if (isLive) live.valPoints else emptyList(),
                resetKey = run.id
            )
        }

        val samples = if (isLive) live.samples else emptyList()
        if (samples.isNotEmpty()) BranchySamplesCard(samples)
        // Exporting while the trainer is still writing checkpoints would read a moving target.
        if (exportSection != null && !(isLive && live.isActive)) exportSection()
        BranchyRunLogCard(File(run.runDir))
    }
}

/** Pause / Resume / Stop for the run, depending on what the service is doing with it. */
@Composable
fun BranchyLiveControls(
    run: BranchyRunEntity?,
    live: BranchyLiveState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onEditDevices: (() -> Unit)? = null
) {
    if (run == null) return
    val context = LocalContext.current
    val isLive = isLiveRun(run, live)
    val active = isLive && live.isActive
    val pausing = isLive && live.phase == BranchyPhase.PAUSING
    val finished = run.status == BranchyRunStatus.DONE.value || (isLive && live.phase == BranchyPhase.DONE)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (active && live.phase == BranchyPhase.TRAIN) {
        val request = live.checkpointRequest
        if (request != null) {
            BranchyCheckpointRequestRow(request, BranchyTrainingState.stepEtaSeconds(live))
        } else {
            OutlinedButton(
                onClick = { BranchyTrainingService.checkpointNow(context, run.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.branchy_r6d_checkpoint_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            finished -> Text(
                text = stringResource(R.string.branchy_ui_finished),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            active -> {
                OutlinedButton(
                    onClick = onPause, enabled = !pausing,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pause), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> {
                if (onEditDevices != null) {
                    OutlinedButton(onClick = onEditDevices, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.branchy_r5d_edit_devices), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(onClick = onResume, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_resume), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    }
}

/** Feedback for a manual checkpoint request: progress bar while pending, then the saved / failed line. */
@Composable
private fun BranchyCheckpointRequestRow(request: BranchyCheckpointRequest, etaSeconds: Double?) {
    val text = when (request.stage) {
        BranchyCkptStage.REQUESTED, BranchyCkptStage.ACKED ->
            if (etaSeconds != null && etaSeconds.isFinite()) {
                stringResource(R.string.branchy_r7ck_requested, BranchyFormat.duration(etaSeconds))
            } else stringResource(R.string.branchy_r7ck_requested_noeta)
        BranchyCkptStage.SAVING ->
            if (request.totalBytes > 0L) {
                val pct = (request.doneBytes * 100L / request.totalBytes).toInt().coerceIn(0, 100)
                stringResource(R.string.branchy_r7ck_saving_pct, pct)
            } else stringResource(R.string.branchy_r7ck_saving)
        BranchyCkptStage.SAVED -> stringResource(R.string.branchy_r7ck_saved, request.step.toInt())
        BranchyCkptStage.FAILED -> when (request.reason) {
            "workers_lost" -> stringResource(R.string.branchy_r7ck_failed_workers_lost)
            "write_failed" -> stringResource(R.string.branchy_r7ck_failed_write)
            else -> stringResource(R.string.branchy_r7ck_failed_other, request.reason.orEmpty())
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (request.pending) {
            if (request.stage == BranchyCkptStage.SAVING && request.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { (request.doneBytes.toFloat() / request.totalBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (request.stage == BranchyCkptStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** "Last checkpoint: step N (reason) Xm ago"; ticks with recomposition only (no timer of its own). */
@Composable
private fun BranchyLastCheckpointText(info: BranchyCheckpointInfo) {
    val reason = when (info.reason) {
        "every" -> stringResource(R.string.branchy_r6d_ckpt_every)
        "manual" -> stringResource(R.string.branchy_r6d_ckpt_manual)
        "wait" -> stringResource(R.string.branchy_r6d_ckpt_wait)
        "pause" -> stringResource(R.string.branchy_r6d_ckpt_pause)
        "end" -> stringResource(R.string.branchy_r6d_ckpt_end)
        else -> null
    }
    val ago = branchyAgo(System.currentTimeMillis() - info.atMs)
    Text(
        text = if (reason != null) {
            stringResource(R.string.branchy_r6d_last_checkpoint, info.step.toString(), reason, ago)
        } else {
            stringResource(R.string.branchy_r6d_last_checkpoint_plain, info.step.toString(), ago)
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
