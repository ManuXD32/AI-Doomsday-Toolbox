package com.example.llamadroid.ui.training

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyFormat
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.branchy.BranchyMicro
import com.example.llamadroid.branchy.BranchyPhase
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.branchy.BranchyRunStatus
import com.example.llamadroid.ui.components.AppSectionCard
import java.util.Locale

/** Values the hero card shows, already resolved between the live state and the stored run. */
internal data class BranchyHeroData(
    val isLive: Boolean,
    val step: Long,
    val total: Long,
    val loss: Double?,
    /** Loss minus the previous recorded loss; null when there is no previous point. */
    val lossDelta: Float?,
    val last: BranchyEvent.Step?,
    val stageIndex: Int,
    val stageCount: Int
)

/** Ring + big loss + speed/ETA, phase chip, stage timeline and the waiting / paused banners. */
@Composable
internal fun BranchyHeroCard(run: BranchyRunEntity, live: BranchyLiveState, data: BranchyHeroData) {
    val phase = if (data.isLive) live.phase else BranchyPhase.IDLE
    AppSectionCard {
        Text(text = run.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatasetChip(
                text = if (data.isLive) phaseLabel(live.phase) else runStatusLabel(BranchyRunStatus.fromValue(run.status)),
                warning = phase == BranchyPhase.ERROR || run.status == BranchyRunStatus.FAILED.value
            )
            DatasetChip(text = stringResource(R.string.branchy_ui_stage_of, data.stageIndex + 1, data.stageCount.coerceAtLeast(1)))
        }
        BranchyStageTimeline(data.stageIndex, data.stageCount)
        if (phase == BranchyPhase.WAITING_NETWORK) BranchyWaitingBanner()
        if (phase == BranchyPhase.PAUSED && live.pauseReason != null) {
            Text(
                text = stringResource(R.string.branchy_ui_paused_because, pauseReasonLabel(live.pauseReason)),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val failedNow = run.status == BranchyRunStatus.FAILED.value || phase == BranchyPhase.ERROR
        val errorText = (if (phase == BranchyPhase.ERROR) live.message else null) ?: run.error
        if (failedNow && !errorText.isNullOrBlank()) {
            Text(text = errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (phase == BranchyPhase.TOKENIZER) {
            Text(text = stringResource(R.string.branchy_ui_phase_tokenizer), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (phase == BranchyPhase.PREPARE) {
            Text(
                text = stringResource(R.string.branchy_ui_preparing, live.progressLabel ?: ""),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (live.progressTotal > 0L) {
                LinearProgressIndicator(
                    progress = { BranchyWizardHelpers.runFraction(live.progressDone, live.progressTotal) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            BranchyProgressRow(data, if (data.isLive) live.micro else null)
            val micro = live.micro
            if (data.isLive && micro != null && micro.total > 0L && phase != BranchyPhase.DONE && phase != BranchyPhase.ERROR) {
                BranchyMicroProgress(micro)
            }
        }
        val valLoss = run.lastValLoss ?: live.valPoints.takeIf { data.isLive }?.lastOrNull()?.second?.toDouble()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BranchyMetric(stringResource(R.string.branchy_ui_metric_val_loss), BranchyFormat.loss(valLoss), Modifier.weight(1f))
            BranchyMetric(
                stringResource(R.string.branchy_ui_metric_ram),
                (data.last?.rssMb ?: live.micro?.rssMb?.takeIf { data.isLive && it > 0.0 })?.let { BranchyWizardHelpers.formatMb(it) } ?: "-",
                Modifier.weight(1f)
            )
            BranchyMetric(
                stringResource(R.string.branchy_ui_metric_tokens),
                BranchyWizardHelpers.formatTokens(run.tokensSeen.toDouble()),
                Modifier.weight(1f)
            )
        }
    }
}

/** In-step progress ("Microbatch 3/8") with live speed and RAM; updates between full steps. */
@Composable
private fun BranchyMicroProgress(micro: BranchyMicro) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.branchy_r5d_micro_title, micro.done.toString(), micro.total.toString()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { BranchyWizardHelpers.runFraction(micro.done, micro.total) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(
                R.string.branchy_r5d_micro_stats,
                String.format(Locale.US, "%.1f", micro.tokS),
                BranchyWizardHelpers.formatMb(micro.rssMb)
            ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BranchyProgressRow(data: BranchyHeroData, micro: BranchyMicro?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BranchyProgressRing(step = data.step, total = data.total)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.branchy_ui_step_of, data.step, data.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = BranchyFormat.loss(data.loss),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                val delta = data.lossDelta
                if (delta != null && delta != 0f) {
                    val down = delta < 0f
                    Icon(
                        imageVector = if (down) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = stringResource(if (down) R.string.branchy_dash_loss_down else R.string.branchy_dash_loss_up),
                        tint = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BranchyMetric(
                    stringResource(R.string.branchy_ui_metric_speed),
                    (data.last?.tokS ?: micro?.tokS?.takeIf { it > 0.0 })?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "-",
                    Modifier.weight(1f)
                )
                BranchyMetric(
                    stringResource(R.string.branchy_ui_metric_eta),
                    data.last?.let { BranchyFormat.duration(it.etaS) } ?: "-",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

/** Circular progress; the sweep animates to each new value (a finite animation, so it stops by itself). */
@Composable
private fun BranchyProgressRing(step: Long, total: Long) {
    val fraction by animateFloatAsState(
        targetValue = BranchyWizardHelpers.runFraction(step, total).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "branchyRing"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = MaterialTheme.colorScheme.primary
    val percent = (fraction * 100f).toInt()
    val ringDesc = stringResource(R.string.branchy_dash_ring_desc, percent)
    Box(
        modifier = Modifier
            .size(96.dp)
            .semantics { contentDescription = ringDesc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(96.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(arc, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(text = "$percent%", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun stageLabel(index: Int, count: Int): String = when {
    count == 2 && index == 0 -> stringResource(R.string.branchy_dash_stage_pretrain)
    count == 2 && index == 1 -> stringResource(R.string.branchy_dash_stage_sft)
    else -> stringResource(R.string.branchy_dash_stage_n, index + 1)
}

/** Pretrain -> SFT (or Stage n) chips; finished and current stages are tinted. */
@Composable
private fun BranchyStageTimeline(stageIndex: Int, stageCount: Int) {
    val count = stageCount.coerceIn(1, 6)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            val reached = i <= stageIndex
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (i == stageIndex) MaterialTheme.colorScheme.primaryContainer
                else if (reached) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    text = stageLabel(i, count),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (i == stageIndex) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (i < count - 1) {
                Text(text = "→", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Pulsing banner; only composed while the run waits for the network, so it animates only then. */
@Composable
private fun BranchyWaitingBanner() {
    val pulse by rememberInfiniteTransition(label = "branchyWait").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "branchyWaitAlpha"
    )
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().alpha(pulse)
    ) {
        Text(
            text = stringResource(R.string.branchy_dash_waiting_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun BranchyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
