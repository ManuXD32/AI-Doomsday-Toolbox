package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.llamadroid.R
import java.util.Locale

/** The small set of session-wide numbers shown beside the composer. */
internal data class HarnessUsageSummaryUi(
    val usage: HarnessTokenUsageUi?,
    val contextPressure: HarnessContextPressureUi?,
    val contextBreakdown: HarnessContextBreakdownUi?,
    val sessionStats: HarnessSessionStatsUi?,
) {
    val contextPercent: Int? get() = contextPressure?.occupancyPercent
    val cacheHitPercent: Int? get() = usage?.cacheHitPercent
    val tokensPerSecond: Double? get() = usage?.tokensPerSecond
    val totalTokens: Long? get() = usage?.totalTokens ?: usage?.total
    val hasMetrics: Boolean
        get() = contextPercent != null || cacheHitPercent != null || tokensPerSecond != null ||
            totalTokens != null || usage != null || contextBreakdown != null
}

internal fun harnessUsageSummary(state: NativeHarnessUiState): HarnessUsageSummaryUi {
    val usage = state.tokenUsage?.copy(
        contextPressure = state.contextPressure ?: state.tokenUsage.contextPressure,
        contextBreakdown = state.contextBreakdown ?: state.tokenUsage.contextBreakdown,
        sessionStats = state.sessionStats ?: state.tokenUsage.sessionStats,
    )
    return HarnessUsageSummaryUi(
        usage = usage,
        contextPressure = state.contextPressure ?: usage?.contextPressure,
        contextBreakdown = state.contextBreakdown ?: usage?.contextBreakdown,
        sessionStats = state.sessionStats ?: usage?.sessionStats,
    )
}

/** Compact DSH-style metrics strip. The detailed projection opens only on demand. */
@Composable
internal fun HarnessUsageSummaryStrip(
    state: NativeHarnessUiState,
    modifier: Modifier = Modifier,
) {
    val summary = harnessUsageSummary(state)
    if (!summary.hasMetrics) return
    var detailsOpen by rememberSaveable(state.selectedSessionId) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("harness_usage_summary"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            summary.contextPercent?.let { percent ->
                TextButton(
                    onClick = { detailsOpen = true },
                    modifier = Modifier.testTag("harness_usage_context"),
                ) {
                    Icon(Icons.Default.DataUsage, contentDescription = null)
                    Text(stringResource(R.string.harness_usage_context_compact, percent))
                }
            }
            summary.cacheHitPercent?.let { percent ->
                Text(
                    stringResource(R.string.harness_usage_cache_compact, percent),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            summary.tokensPerSecond?.let { speed ->
                Text(
                    stringResource(R.string.harness_usage_speed_compact, speed),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
            }
            summary.totalTokens?.let { total ->
                Text(
                    stringResource(R.string.harness_usage_total_compact, formatHarnessTokenCount(total)),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.contextPercent == null) {
                TextButton(onClick = { detailsOpen = true }) {
                    Text(stringResource(R.string.harness_usage_details_open))
                }
            }
        }
    }
    if (detailsOpen) {
        HarnessUsageDetailsDialog(summary = summary, onDismiss = { detailsOpen = false })
    }
}

@Composable
private fun HarnessUsageDetailsDialog(
    summary: HarnessUsageSummaryUi,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DataUsage, contentDescription = null)
                    Text(
                        stringResource(R.string.harness_usage_details_title),
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("harness_usage_close")) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.harness_usage_close))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.contextPressure?.let { pressure ->
                        Text(stringResource(R.string.harness_usage_context_title), style = MaterialTheme.typography.titleMedium)
                        pressure.occupancyPercent?.let { percent ->
                            HarnessUsageDetailRow(
                                stringResource(R.string.harness_usage_context_occupancy),
                                stringResource(
                                    R.string.harness_usage_context_used_window,
                                    pressure.projectedTokens ?: pressure.pressureTokens ?: 0L,
                                    pressure.contextWindow ?: 0L,
                                ) + " ($percent%)",
                            )
                        }
                        pressure.pressureTokens?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_pressure), formatHarnessTokenCount(it))
                        }
                        pressure.projectedTokens?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_projected), formatHarnessTokenCount(it))
                        }
                        pressure.contextWindow?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_window), formatHarnessTokenCount(it))
                        }
                    }
                    summary.contextBreakdown?.let { breakdown ->
                        Text(stringResource(R.string.harness_usage_breakdown_title), style = MaterialTheme.typography.titleMedium)
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_system), formatHarnessTokenCount(breakdown.systemTokens))
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_tools), formatHarnessTokenCount(breakdown.toolsTokens))
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_context_messages), formatHarnessTokenCount(breakdown.messageTokens))
                    }
                    summary.usage?.let { usage ->
                        Text(stringResource(R.string.harness_usage_title), style = MaterialTheme.typography.titleMedium)
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_input), formatHarnessTokenCount(usage.inputTokens))
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_output), formatHarnessTokenCount(usage.outputTokens))
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_cache_read), formatHarnessTokenCount(usage.cacheReadTokens))
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_cache_write), formatHarnessTokenCount(usage.cacheWriteTokens))
                        usage.cacheHitPercent?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_cache_hit), "$it%")
                        }
                        usage.reasoningTokens?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_reasoning), formatHarnessTokenCount(it))
                        }
                        HarnessUsageDetailRow(stringResource(R.string.harness_usage_total), formatHarnessTokenCount(usage.total))
                    }
                    summary.sessionStats?.let { stats ->
                        Text(stringResource(R.string.harness_usage_stats_title), style = MaterialTheme.typography.titleMedium)
                        stats.decodeTokens.takeIf { it > 0L }?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_stats_decode_tokens), formatHarnessTokenCount(it))
                        }
                        stats.decodeMs.takeIf { it > 0L }?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_stats_decode_ms), "$it ms")
                        }
                        summary.tokensPerSecond?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_speed), stringResource(R.string.harness_usage_speed_compact, it))
                        }
                        stats.ttftMs.takeIf { it > 0L }?.let {
                            HarnessUsageDetailRow(stringResource(R.string.harness_usage_stats_ttft_ms), "$it ms")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessUsageDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal fun formatHarnessTokenCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
    else -> value.toString()
}

@Composable
internal fun HarnessTurnUsageRow(usage: HarnessTokenUsageUi) {
    Text(
        stringResource(R.string.harness_usage_turn, usage.total),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
