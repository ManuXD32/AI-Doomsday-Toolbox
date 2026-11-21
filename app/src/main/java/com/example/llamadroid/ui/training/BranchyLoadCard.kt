package com.example.llamadroid.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyRoundStat
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import java.util.Locale

/** Pure helpers of the load card; separate so they can be unit-tested. */
object BranchyLoadMath {
    /** [rounds] oldest first; every [series] entry has one microbatch count per round (0 where the device sat out). */
    data class Stack(val rounds: List<Long>, val series: List<Pair<String, List<Long>>>)

    fun stack(history: Map<String, List<BranchyRoundStat>>): Stack {
        val rounds = history.values.flatten().map { it.round }.distinct().sorted()
        val series = history.entries.map { (id, stats) ->
            val byRound = stats.associateBy { it.round }
            id to rounds.map { byRound[it]?.mb ?: 0L }
        }
        return Stack(rounds, series)
    }

    /** Indices of the rounds visible in [viewport] (at least one when [count] > 0). */
    fun visible(count: Int, viewport: BranchyChartViewport): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val first = kotlin.math.floor(viewport.lo * count).toInt().coerceIn(0, count - 1)
        val last = (kotlin.math.ceil(viewport.hi * count).toInt() - 1).coerceIn(first, count - 1)
        return first..last
    }

    fun fraction(value: Double, max: Double): Float =
        if (max <= 0.0 || !value.isFinite()) 0f else (value / max).toFloat().coerceIn(0f, 1f)
}

private data class LoadRow(
    val id: String,
    val name: String,
    val mb: Long,
    val mbDone: Long,
    val seconds: Double,
    val msPerMb: Double,
    val idleS: Double,
    val resting: Boolean
)

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

@Composable
private fun loadPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.tertiaryContainer
)

/**
 * Per-device load of a cluster run. Data-parallel: bars of the last round plus a zoomable history of microbatches per
 * round, "not needed" suggestions (rest / use again). Branch-parallel: compute ms per step and the rebalance proposal.
 */
@Composable
internal fun BranchyLoadCard(
    live: BranchyLiveState,
    resetKey: Any?,
    onRest: (String) -> Unit,
    onUnrest: (String) -> Unit,
    onRebalance: () -> Unit
) {
    val dataParallel = live.clusterMode == "data_parallel"
    AppSectionCard {
        AppSectionTitle(title = stringResource(if (dataParallel) R.string.branchy_r6d_load_title else R.string.branchy_r6d_bp_title))
        if (live.rebalancing) {
            Text(
                text = stringResource(R.string.branchy_r6_rebalancing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (dataParallel) DpLoad(live, resetKey, onRest, onUnrest) else BpLoad(live, onRebalance)
    }
}

@Composable
private fun DpLoad(live: BranchyLiveState, resetKey: Any?, onRest: (String) -> Unit, onUnrest: (String) -> Unit) {
    val mainName = stringResource(R.string.branchy_r6d_main)
    fun nameOf(id: String) = if (id == "main") mainName else live.devices.firstOrNull { it.id == id }?.name ?: id
    val resting = live.restingIds
    val round = live.dpRound
    val rows = round?.devices.orEmpty().map { d ->
        LoadRow(d.id, nameOf(d.id), d.mb, d.mbDone, d.seconds, d.msPerMb, d.idleS, d.id in resting || d.state == "resting")
    }
    var confirmRest by remember { mutableStateOf<String?>(null) }
    if (round != null) {
        Text(
            text = stringResource(R.string.branchy_r6d_load_round, round.round.toString()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val stepMode = live.dpStepMode
    if (stepMode != null) {
        val modeText = stringResource(
            if (stepMode.mode == "exact") R.string.branchy_r7_step_mode_exact else R.string.branchy_r7_step_mode_local
        )
        // The native reason text is English; show a localized figure built from the measured times instead.
        val total = stepMode.computeS + stepMode.commS
        val commPct = if (total > 0.0 && stepMode.commS > 0.0) Math.round(100.0 * stepMode.commS / total).toInt() else null
        Text(
            text = if (commPct != null) {
                stringResource(R.string.branchy_r7_step_mode_reason, modeText, stringResource(R.string.branchy_r7_step_mode_comm, commPct))
            } else modeText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val maxMb = rows.maxOfOrNull { maxOf(it.mb, it.mbDone) }?.toDouble() ?: 0.0
    val maxSec = rows.maxOfOrNull { maxOf(it.seconds, it.idleS) } ?: 0.0
    val maxMs = rows.maxOfOrNull { it.msPerMb } ?: 0.0
    rows.forEach { r ->
        val advice = live.advice[r.id]
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = r.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (r.resting) DatasetChip(text = stringResource(R.string.branchy_r6d_resting))
            }
            LoadBar(
                stringResource(R.string.branchy_r6d_bar_mb, r.mbDone.toString(), r.mb.toString()),
                BranchyLoadMath.fraction(r.mbDone.toDouble(), maxMb), MaterialTheme.colorScheme.primary
            )
            LoadBar(
                stringResource(R.string.branchy_r6d_bar_seconds, one(r.seconds)),
                BranchyLoadMath.fraction(r.seconds, maxSec), MaterialTheme.colorScheme.secondary
            )
            LoadBar(
                stringResource(R.string.branchy_r6d_bar_ms_per_mb, one(r.msPerMb)),
                BranchyLoadMath.fraction(r.msPerMb, maxMs), MaterialTheme.colorScheme.tertiary
            )
            LoadBar(
                stringResource(R.string.branchy_r6d_bar_idle, one(r.idleS)),
                BranchyLoadMath.fraction(r.idleS, maxSec), MaterialTheme.colorScheme.error, highlight = r.idleS > 0.0
            )
            if (r.resting) {
                OutlinedButton(
                    onClick = { onUnrest(r.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.branchy_r6d_use_again), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (advice != null && advice.notNeeded && r.id != "main") {
                DatasetChip(text = stringResource(R.string.branchy_r6d_not_needed), warning = true)
                Text(
                    text = stringResource(
                        R.string.branchy_r6d_not_needed_detail,
                        String.format(Locale.US, "%.0f", advice.gainPct),
                        String.format(Locale.US, "%.0f", advice.costPct)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { confirmRest = r.id },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.branchy_r6d_rest_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    BranchyLoadHistory(live, resetKey)
    val pending = confirmRest
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmRest = null },
            title = { Text(stringResource(R.string.branchy_r6d_rest_confirm_title, nameOf(pending)), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.branchy_r6d_rest_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRest = null; onRest(pending) }) {
                    Text(stringResource(R.string.branchy_r6d_rest_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRest = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun LoadBar(label: String, fraction: Float, color: Color, highlight: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/** Stacked bars: microbatches per device for each of the last rounds. Zoomable on x. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchyLoadHistory(live: BranchyLiveState, resetKey: Any?) {
    val stack = BranchyLoadMath.stack(live.roundHistory)
    var viewport by remember(resetKey) { mutableStateOf(BranchyChartViewport.Full) }
    val palette = loadPalette()
    val mainName = stringResource(R.string.branchy_r6d_main)
    if (stack.rounds.isNotEmpty()) {
        val range = BranchyLoadMath.visible(stack.rounds.size, viewport)
        val totals = range.map { i -> stack.series.sumOf { it.second[i] } }
        val maxTotal = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        Text(
            text = stringResource(R.string.branchy_r6d_history_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp)
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clipToBounds()
                .branchyChartGestures(resetKey, { viewport }, { viewport = it })
        ) {
            val slot = size.width / range.count().coerceAtLeast(1)
            val barW = slot * 0.8f
            range.forEachIndexed { slotIndex, i ->
                var top = size.height
                stack.series.forEachIndexed { s, (_, counts) ->
                    val h = counts[i] / maxTotal * size.height
                    if (h > 0f) {
                        top -= h
                        drawRect(palette[s % palette.size], Offset(slotIndex * slot + (slot - barW) / 2f, top), Size(barW, h))
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            stack.series.forEachIndexed { s, (id, _) ->
                val name = if (id == "main") mainName else live.devices.firstOrNull { it.id == id }?.name ?: id
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(palette[s % palette.size]))
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = stringResource(R.string.branchy_r6d_history_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BpLoad(live: BranchyLiveState, onRebalance: () -> Unit) {
    val rows = live.devices.filter { it.stepMs > 0.0 }
    val max = rows.maxOfOrNull { it.stepMs } ?: 0.0
    rows.forEach { d -> BpRow(d, max) }
    val p = live.bpProposal
    var confirm by remember { mutableStateOf(false) }
    if (p != null) {
        val cur = p.currentMs.values.maxOrNull() ?: 0.0
        val prop = p.proposedMs.values.maxOrNull() ?: 0.0
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.branchy_r6d_rebalance_title, String.format(Locale.US, "%.0f", p.gainPct)),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.branchy_r6d_rebalance_times, String.format(Locale.US, "%.0f", cur), String.format(Locale.US, "%.0f", prop)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!p.applicable) {
                Text(
                    text = p.reason?.let { stringResource(R.string.branchy_r6d_rebalance_blocked, it) }
                        ?: stringResource(R.string.branchy_r6d_rebalance_blocked_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedButton(
                onClick = { confirm = true },
                enabled = p.applicable && !live.rebalancing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.branchy_r6d_rebalance_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.branchy_r6d_rebalance_confirm_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(stringResource(R.string.branchy_r6d_rebalance_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRebalance() }) {
                    Text(stringResource(R.string.branchy_r6d_rebalance_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun BpRow(d: BranchyLiveDevice, max: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LoadBar(
            stringResource(R.string.branchy_r6d_bp_ms, String.format(Locale.US, "%.0f", d.stepMs)),
            BranchyLoadMath.fraction(d.stepMs, max), MaterialTheme.colorScheme.primary
        )
    }
}
