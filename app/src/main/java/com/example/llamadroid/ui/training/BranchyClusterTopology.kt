package com.example.llamadroid.ui.training

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyDeviceStatus
import com.example.llamadroid.branchy.BranchyLiveDevice
import com.example.llamadroid.branchy.BranchyLiveState
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import java.util.Locale

/** Link state of one helper as drawn on the topology. */
internal enum class BranchyLinkState { UP, WAITING, LOST }

internal fun BranchyLiveDevice.linkState(): BranchyLinkState = when (status) {
    BranchyDeviceStatus.CONNECTED, BranchyDeviceStatus.IDLE -> BranchyLinkState.UP
    BranchyDeviceStatus.WAITING, BranchyDeviceStatus.UNREACHABLE -> BranchyLinkState.WAITING
    BranchyDeviceStatus.LOST, BranchyDeviceStatus.OFFLINE -> BranchyLinkState.LOST
}

/** Vertical layout of the topology canvas, in dp: the master label sits right under the master node. */
internal object BranchyTopologyLayout {
    const val CANVAS_HEIGHT = 130f
    const val MASTER_Y = 16f
    const val MASTER_RADIUS = 13f
    const val LABEL_TOP = 32f
    const val LABEL_HEIGHT = 18f
    /** Links start below the label so no line crosses it. */
    const val LINK_START_Y = LABEL_TOP + LABEL_HEIGHT + 2f
    const val HELPER_BOTTOM_INSET = 18f
}

/**
 * Cluster card: master on top, helpers below, one link per helper (up = primary, waiting = tertiary dashed,
 * lost = error). [animate] (true only while training) lets a pulse travel along the up links.
 */
@Composable
internal fun BranchyClusterCard(live: BranchyLiveState, animate: Boolean) {
    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_main_live_title),
            supporting = stringResource(
                if (live.clusterMode == "data_parallel") R.string.branchy_main_mode_data else R.string.branchy_main_mode_branch
            )
        )
        val devices = live.devices
        val pulse = if (animate) {
            rememberInfiniteTransition(label = "branchyPulse").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                label = "branchyPulseT"
            ).value
        } else {
            -1f
        }
        val upColor = MaterialTheme.colorScheme.primary
        val waitColor = MaterialTheme.colorScheme.tertiary
        val lostColor = MaterialTheme.colorScheme.error
        val nodeColor = MaterialTheme.colorScheme.secondary
        Box(modifier = Modifier.fillMaxWidth().height(BranchyTopologyLayout.CANVAS_HEIGHT.dp)) {
            Canvas(modifier = Modifier.fillMaxSize().testTag("branchy_topology_canvas")) {
                val w = size.width
                val master = Offset(w / 2f, BranchyTopologyLayout.MASTER_Y.dp.toPx())
                val linkStart = Offset(w / 2f, BranchyTopologyLayout.LINK_START_Y.dp.toPx())
                val n = devices.size.coerceAtLeast(1)
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                devices.forEachIndexed { i, d ->
                    val node = Offset(w * (i + 0.5f) / n, size.height - BranchyTopologyLayout.HELPER_BOTTOM_INSET.dp.toPx())
                    val state = d.linkState()
                    val color = linkColor(state, upColor, waitColor, lostColor)
                    drawLine(
                        color, linkStart, node, strokeWidth = 4f,
                        pathEffect = if (state == BranchyLinkState.WAITING) dash else null
                    )
                    if (state == BranchyLinkState.UP && pulse >= 0f) {
                        val t = (pulse + i * 0.17f) % 1f
                        drawCircle(color, radius = 6.dp.toPx(), center = Offset(linkStart.x + (node.x - linkStart.x) * t, linkStart.y + (node.y - linkStart.y) * t))
                    }
                    drawCircle(color, radius = 9.dp.toPx(), center = node, style = Stroke(width = 4f))
                    drawCircle(color.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = node)
                }
                drawCircle(nodeColor, radius = BranchyTopologyLayout.MASTER_RADIUS.dp.toPx(), center = master)
            }
            Text(
                text = stringResource(R.string.branchy_dash_master),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = BranchyTopologyLayout.LABEL_TOP.dp)
                    .testTag("branchy_master_label")
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            devices.forEach { d ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = d.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = linkColor(d.linkState(), upColor, waitColor, lostColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.branchy_main_live_branches, d.branches.joinToString(",")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = branchyStatusLabel(d.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = linkColor(d.linkState(), upColor, waitColor, lostColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        devices.filter { it.linkState() == BranchyLinkState.LOST && !it.lostReason.isNullOrBlank() }.forEach { d ->
            Text(
                text = stringResource(R.string.branchy_main_live_lost, d.lostReason ?: "-"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        BranchyStepBars(devices)
        live.dpSetup?.let { setup ->
            Text(
                text = stringResource(R.string.branchy_r4_dp_setup, setup.device, setup.percent),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (live.syncRound > 0L) {
            Text(
                text = stringResource(
                    R.string.branchy_main_live_sync, live.syncRound.toString(),
                    BranchyWizardHelpers.formatMb(live.syncBytes / 1_048_576.0), live.syncWorkers.toString()
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun linkColor(state: BranchyLinkState, up: Color, waiting: Color, lost: Color): Color = when (state) {
    BranchyLinkState.UP -> up
    BranchyLinkState.WAITING -> waiting
    BranchyLinkState.LOST -> lost
}

/** One bar per helper scaled to the slowest one, so stragglers stand out. */
@Composable
private fun BranchyStepBars(devices: List<BranchyLiveDevice>) {
    val maxMs = devices.maxOfOrNull { it.stepMs } ?: 0.0
    if (maxMs > 0.0) {
        Text(
            text = stringResource(R.string.branchy_dash_step_ms_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        devices.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = d.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(84.dp)
                )
                LinearProgressIndicator(
                    progress = { (d.stepMs / maxMs).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                    color = if (d.up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (d.stepMs > 0.0) String.format(Locale.US, "%.0f ms", d.stepMs) else "-",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.width(64.dp).padding(start = 2.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
internal fun branchyStatusLabel(status: BranchyDeviceStatus): String = stringResource(
    when (status) {
        BranchyDeviceStatus.CONNECTED -> R.string.branchy_main_live_up
        BranchyDeviceStatus.LOST -> R.string.branchy_main_live_down
        BranchyDeviceStatus.WAITING -> R.string.branchy_dash_link_waiting
        BranchyDeviceStatus.UNREACHABLE -> R.string.branchy_r5d_status_unreachable
        BranchyDeviceStatus.OFFLINE -> R.string.branchy_r5d_status_offline
        BranchyDeviceStatus.IDLE -> R.string.branchy_r6d_status_idle
    }
)
