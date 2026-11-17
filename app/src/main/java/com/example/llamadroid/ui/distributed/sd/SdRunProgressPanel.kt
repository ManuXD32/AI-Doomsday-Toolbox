package com.example.llamadroid.ui.distributed.sd

import android.net.TrafficStats
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.service.DistributedTransferMath
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.SdDistributedRunMonitor
import com.example.llamadroid.service.SdDistributedService
import com.example.llamadroid.service.SdRunKind
import com.example.llamadroid.service.SdRunLineParser
import com.example.llamadroid.service.SdRunPhase
import com.example.llamadroid.service.SdRunProgress
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoGenerationState
import com.example.llamadroid.service.VideoGenerationStateHolder
import com.example.llamadroid.service.sdRunView
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.distributed.HelperTransferBar
import com.example.llamadroid.ui.distributed.common.DistributedLogViewerDialog
import com.example.llamadroid.ui.distributed.common.DistributedTechnicalLogSection
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TRANSFER_SAMPLE_MS = 500L
private const val PREVIEW_LOG_LINES = 12
private const val SILENCE_TICK_MS = 15_000L

/** Minutes of generator silence after which the run panel shows its "no output" hint. */
internal const val SD_RUN_SILENCE_HINT_MS = 5 * 60_000L

/** Whole minutes without output when at least [SD_RUN_SILENCE_HINT_MS] has passed, else null. */
internal fun sdRunSilenceMinutes(nowMs: Long, lastOutputAtMs: Long): Int? {
    val silent = nowMs - lastOutputAtMs
    return if (silent >= SD_RUN_SILENCE_HINT_MS) (silent / 60_000L).toInt() else null
}

/** Phases shown for [kind], in execution order. */
internal fun sdRunPhasesFor(kind: SdRunKind): List<SdRunPhase> =
    SdRunPhase.entries.filter { kind == SdRunKind.VIDEO || it != SdRunPhase.VIDEO_FRAMES }

/** Whole-run completion estimate: finished phases plus the sampled share of the diffusion phase. */
internal fun sdRunFraction(progress: SdRunProgress, kind: SdRunKind, complete: Boolean): Float {
    if (complete || progress.finished) return 1f
    val phases = sdRunPhasesFor(kind)
    val phase = progress.phase ?: return 0f
    val index = phases.indexOf(phase).coerceAtLeast(0)
    val within = if (phase == SdRunPhase.DIFFUSION && progress.totalSteps > 0) {
        val perItem = progress.step.toFloat() / progress.totalSteps
        ((progress.batchIndex - 1).coerceAtLeast(0) + perItem) / progress.batchCount.coerceAtLeast(1)
    } else 0f
    return ((index + within) / phases.size).coerceIn(0f, 0.99f)
}

private fun phaseLabelRes(phase: SdRunPhase): Int = when (phase) {
    SdRunPhase.LOADING -> R.string.sd_r4_phase_loading
    SdRunPhase.TEXT_ENCODING -> R.string.sd_r4_phase_text_encoding
    SdRunPhase.DIFFUSION -> R.string.sd_r4_phase_diffusion
    SdRunPhase.VAE_DECODING -> R.string.sd_r4_phase_vae_decoding
    SdRunPhase.VIDEO_FRAMES -> R.string.sd_r4_phase_video_frames
    SdRunPhase.SAVING -> R.string.sd_r4_phase_saving
}

/**
 * Live view of the active Media run for the Red multimedia screen: phase timeline, ETA, per-helper weight transfer,
 * who runs which module, and the generator output (full log in a dialog).
 */
@Composable
internal fun SdRunProgressSection(navController: NavController) {
    val session by SdDistributedRunMonitor.session.collectAsState()
    val transfers by SdDistributedRunMonitor.transfers.collectAsState()
    val workers by SdDistributedService.activeWorkers.collectAsState()
    val debugLogs by DebugLog.logs.collectAsState()
    var showViewer by remember { mutableStateOf(false) }

    val current = session
    val kind = current?.kind ?: SdRunKind.IMAGE
    val sdMode = current?.takeIf { kind == SdRunKind.IMAGE }
        ?.let { run -> SDMode.entries.firstOrNull { it.name == run.mode } }
    val videoMode = current?.takeIf { kind == SdRunKind.VIDEO }
        ?.let { run -> VideoGenerationMode.entries.firstOrNull { it.name == run.mode } }
    val imageState = sdMode?.let { SDModeStateHolder.getDistributedForMode(it).state }
        ?.collectAsState()?.value
    val videoState = videoMode?.let { VideoGenerationStateHolder.getForMode(it, useDistributedStateHolder = true).state }
        ?.collectAsState()?.value
    val runView = sdRunView(kind, imageState, videoState)
    val error = runView.error
    val complete = runView.complete
    val saving = runView.saving
    val running = runView.running

    val runEntries = remember(debugLogs, current) {
        if (current == null) emptyList() else debugLogs
            .filter { it.timestamp >= current.startedAtMs - 1_000L }
            .filter { it.message.startsWith("SD: ") || it.message.startsWith("[VIDEO-GEN] ") }
    }
    val runLines = remember(runEntries) { runEntries.map { it.message.removePrefix("SD: ") } }
    val parsed = remember(runLines) { SdRunLineParser.parse(runLines) }
    // The video service only exposes step counters; use them when the log tail no longer holds the sampling bar.
    val progress = if (parsed.totalSteps == 0) {
        val state = (videoState as? VideoGenerationState.Generating)?.takeIf { it.totalSteps > 0 }
        if (state != null && kind == SdRunKind.VIDEO) {
            parsed.copy(
                phase = parsed.phase ?: SdRunPhase.DIFFUSION,
                step = state.currentStep,
                totalSteps = state.totalSteps
            )
        } else parsed
    } else parsed
    val effective = if (saving && (progress.phase?.ordinal ?: -1) < SdRunPhase.SAVING.ordinal) {
        progress.copy(phase = SdRunPhase.SAVING)
    } else progress

    // Hint only (never a kill): how long the generator has printed nothing during a running run.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running && isActive) {
            nowMs = System.currentTimeMillis()
            delay(SILENCE_TICK_MS)
        }
    }
    val silentMinutes = if (running && current != null) {
        sdRunSilenceMinutes(nowMs, runEntries.maxOfOrNull { it.timestamp } ?: current.startedAtMs)
    } else null

    val latest = rememberUpdatedState(effective)
    val latestComplete = rememberUpdatedState(complete)
    LaunchedEffect(current, error) {
        if (current == null || error != null) return@LaunchedEffect
        while (isActive) {
            val loaded = latestComplete.value ||
                (latest.value.phase?.ordinal ?: -1) >= SdRunPhase.DIFFUSION.ordinal
            SdDistributedRunMonitor.tick(
                nowMs = System.currentTimeMillis(),
                txBytes = TrafficStats.getUidTxBytes(Process.myUid()),
                weightsLoaded = loaded
            )
            delay(TRANSFER_SAMPLE_MS)
        }
    }

    AppSectionCard(tonalAccent = RenderFarmPalette.violet.copy(alpha = 0.10f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.sd_r4_run_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (current != null) {
                Text(
                    text = stringResource(
                        when {
                            error != null -> R.string.sd_r4_run_failed
                            complete -> R.string.sd_r4_run_complete
                            else -> R.string.sd_r4_run_running
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        if (current == null) {
            Text(
                text = stringResource(R.string.sd_r4_run_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { navController.navigate(Screen.SdDistributedImages.route) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sd_r4_open_images), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { navController.navigate(Screen.SdDistributedVideo.route) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sd_r4_open_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            error?.let { WarningBand(text = it) }
            LinearProgressIndicator(
                progress = { sdRunFraction(effective, kind, complete) },
                modifier = Modifier.fillMaxWidth()
            )
            SdRunTimeline(effective, kind, complete, running)
            val eta = SdRunLineParser.etaSeconds(effective)
            if (running && eta != null) {
                Text(
                    text = stringResource(R.string.sd_r4_run_eta, DistributedTransferMath.formatEta(eta)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (silentMinutes != null) {
                Text(
                    text = pluralStringResource(R.plurals.sd_r8_w08_silence_hint, silentMinutes, silentMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Text(
                text = stringResource(R.string.sd_r4_transfer_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (transfers.isEmpty()) {
                Text(
                    text = stringResource(R.string.sd_r4_transfer_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                transfers.forEach { transfer ->
                    val name = workers.firstOrNull { "${it.host}:${it.port}" == transfer.address }
                        ?.deviceName?.takeIf(String::isNotBlank) ?: transfer.address
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        HelperTransferBar(transfer)
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.sd_r4_placement_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (workers.isEmpty()) {
            Text(
                text = stringResource(R.string.sd_dist_no_active_workers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        workers.forEach { worker ->
            val modules = worker.plannedAssignments.map {
                if (it.isSplit) stringResource(R.string.sd_r4_placement_share, it.displayLabel, it.estimatedLayerShare)
                else it.displayLabel
            }.joinToString(", ").ifBlank { stringResource(R.string.sd_r4_placement_none) }
            val device = if (worker.isLocalMaster) stringResource(R.string.sd_r4_placement_master)
            else worker.deviceName.ifBlank { "${worker.host}:${worker.port}" }
            Text(
                text = stringResource(R.string.sd_r4_placement_row, device, modules),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    DistributedTechnicalLogSection(
        lines = runLines.takeLast(PREVIEW_LOG_LINES),
        title = stringResource(R.string.sd_r4_log_title),
        reveal = running,
        onOpenViewer = { showViewer = true }
    )
    if (showViewer) {
        DistributedLogViewerDialog(
            title = stringResource(R.string.sd_r4_log_title),
            lines = runLines,
            onDismiss = { showViewer = false }
        )
    }
}

@Composable
private fun SdRunTimeline(progress: SdRunProgress, kind: SdRunKind, complete: Boolean, running: Boolean) {
    val reached = progress.phase?.ordinal ?: -1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        sdRunPhasesFor(kind).forEach { phase ->
            val done = complete || phase.ordinal < reached
            val active = !complete && phase.ordinal == reached && running
            val detail = when {
                !active && !(phase == SdRunPhase.DIFFUSION && progress.totalSteps > 0 && !done) -> null
                phase == SdRunPhase.DIFFUSION && progress.totalSteps > 0 -> {
                    val step = progress.secondsPerIt?.let {
                        stringResource(
                            R.string.sd_r4_phase_step,
                            progress.step,
                            progress.totalSteps,
                            String.format(java.util.Locale.ROOT, "%.2f", it)
                        )
                    } ?: stringResource(R.string.sd_r4_phase_step_plain, progress.step, progress.totalSteps)
                    if (progress.batchCount > 1) {
                        stringResource(R.string.sd_r4_phase_batch, progress.batchIndex, progress.batchCount) + " · " + step
                    } else step
                }
                phase == SdRunPhase.VIDEO_FRAMES && progress.frames != null ->
                    stringResource(R.string.sd_r4_phase_frames, progress.frames)
                else -> null
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when {
                        done -> Icons.Default.Check
                        active -> Icons.Default.PlayArrow
                        else -> Icons.Default.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (done || active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(phaseLabelRes(phase)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp).weight(1f)
                    )
                }
            }
        }
    }
}
