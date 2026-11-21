package com.example.llamadroid.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import java.util.Locale

/** Thread-count sweep of one device: [best] is the smallest count within 5% of the fastest. */
data class BenchCurve(
    val id: String,
    val name: String,
    val points: List<BranchyEvent.BenchPoint>,
    val best: Int,
    val gflops: Double,
    val applied: Boolean = false,
    val error: String? = null
)

/** State of the Devices-step benchmark; [local] fills point by point while [running]. */
data class BranchyBenchState(
    val running: Boolean = false,
    val local: BenchCurve? = null,
    val devices: List<BenchCurve> = emptyList(),
    val error: String? = null,
    /** Helper whose own Benchmark button started the run; null for the all-devices run. */
    val benchingId: String? = null,
    /** Helper whose own run failed as a whole (no per-device result), so its row can show [error]. */
    val errorId: String? = null
) {
    val hasResults: Boolean get() = local != null || devices.isNotEmpty()

    /** Latest sweep result of helper [id], if it was benchmarked in this session. */
    fun curveFor(id: String): BenchCurve? = devices.lastOrNull { it.id == id }
}

object BranchyBenchMath {
    const val TOLERANCE = 0.95

    /** Smallest thread count whose speed is within 5% of the maximum; 0 when [points] is empty. */
    fun best(points: List<BranchyEvent.BenchPoint>): Int {
        val max = points.maxOfOrNull { it.gflops } ?: return 0
        return points.filter { it.gflops >= max * TOLERANCE }.minOfOrNull { it.threads } ?: 0
    }

    /** [current] plus the helper's best thread count when its sweep succeeded; unchanged otherwise. */
    fun withHelperThreads(current: Map<String, Int>, curve: BenchCurve): Map<String, Int> =
        if (curve.error == null && curve.best > 0) current + (curve.id to curve.best) else current

    /** Best thread count and its GFLOP/s from a sweep summary, falling back to the raw points when it reports 0. */
    fun resolve(bestThreads: Int, gflops: Double, points: List<BranchyEvent.BenchPoint>): Pair<Int, Double> {
        val best = bestThreads.takeIf { it > 0 } ?: best(points)
        val speed = gflops.takeIf { it > 0.0 } ?: points.firstOrNull { it.threads == best }?.gflops ?: 0.0
        return best to speed
    }
}

/** Benchmark button, hint and per-device bar charts for the Devices step. */
@Composable
internal fun BranchyBenchCard(
    state: BranchyBenchState,
    hasHelpers: Boolean,
    onBenchmark: () -> Unit,
    onCancel: () -> Unit,
    blockedHint: String? = null
) {
    val res = LocalResources.current
    AppSectionCard {
        AppSectionTitle(
            title = res.getString(R.string.branchy_bench_title),
            supporting = res.getString(
                if (hasHelpers) R.string.branchy_bench_hint_cluster else R.string.branchy_bench_hint
            )
        )
        Text(
            text = res.getString(R.string.branchy_bench_cool_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (blockedHint != null) {
            Text(
                text = blockedHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onBenchmark, enabled = !state.running && blockedHint == null, modifier = Modifier.weight(1f)) {
                Text(
                    res.getString(if (state.running) R.string.branchy_bench_running else R.string.branchy_bench_button),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (state.running) {
                OutlinedButton(onClick = onCancel) {
                    Text(res.getString(R.string.branchy_bench_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (state.error != null) {
            Text(
                text = res.getString(R.string.branchy_bench_error, state.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        state.local?.let { BenchCurveChart(it, res.getString(R.string.branchy_bench_this_phone)) }
        state.devices.forEach { BenchCurveChart(it, it.name) }
    }
}

@Composable
private fun BenchCurveChart(curve: BenchCurve, title: String) {
    val res = LocalResources.current
    val max = curve.points.maxOfOrNull { it.gflops }?.takeIf { it > 0.0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (curve.applied) {
                AssistChip(
                    onClick = {},
                    label = { Text(res.getString(R.string.branchy_bench_applied), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        if (curve.error != null) {
            Text(
                text = res.getString(R.string.branchy_bench_device_error, curve.error),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
        } else {
            curve.points.forEach { p ->
                val isBest = p.threads == curve.best
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = p.threads.toString(), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(24.dp), maxLines = 1
                    )
                    Box(modifier = Modifier.weight(1f).height(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((p.gflops / max).toFloat().coerceIn(0.02f, 1f))
                                .background(
                                    if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "%.1f", p.gflops),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp).padding(end = 2.dp), maxLines = 1
                    )
                }
            }
            if (curve.best > 0) {
                Text(
                    text = res.getQuantityString(R.plurals.branchy_bench_best, curve.best, curve.best, curve.gflops.format1()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)
