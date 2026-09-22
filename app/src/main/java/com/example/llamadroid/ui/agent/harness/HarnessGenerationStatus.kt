package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessGenerationActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Small metadata-only status line for the active request. Counts are shown only when the local
 * provider supplied a known prefill total; elapsed time is shown only for that request's exact
 * local start timestamp. No percentage is inferred from partial or cloud/tool activity.
 */
@Composable
internal fun HarnessGenerationStatus(
    activity: HarnessGenerationActivity?,
    canCancelTurn: Boolean,
    isStoppingTurn: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!canCancelTurn && !isStoppingTurn) return

    val requestId = activity?.requestId
    val startedAtMs = activity?.startedAtMs
    var nowMs by remember(requestId, startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(requestId, startedAtMs) {
        if (startedAtMs == null) return@LaunchedEffect
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val title = when {
        isStoppingTurn -> stringResource(R.string.harness_stopping_turn)
        activity == null -> stringResource(R.string.harness_generation_working)
        activity.phase.equals("waiting", ignoreCase = true) ->
            stringResource(R.string.harness_generation_waiting)
        activity.phase.equals("prefill", ignoreCase = true) &&
            activity.known && activity.total != null && activity.processed != null && activity.total > 0 ->
            stringResource(
                R.string.harness_generation_prefill_count,
                activity.processed.coerceAtLeast(0),
                activity.total,
            )
        activity.phase.equals("prefill", ignoreCase = true) ->
            stringResource(R.string.harness_generation_prefill)
        activity.phase.equals("generating", ignoreCase = true) ->
            stringResource(R.string.harness_generation_generating)
        else -> stringResource(R.string.harness_generation_working)
    }
    val elapsed = startedAtMs
        ?.takeIf { it > 0L && activity.sessionId != null }
        ?.let { harnessElapsedParts((nowMs - it).coerceAtLeast(0L)) }
    val elapsedLabel = elapsed?.let {
        if (it.minutes > 0L) {
            stringResource(R.string.harness_generation_minutes, it.minutes, it.seconds)
        } else {
            stringResource(R.string.harness_generation_seconds, it.seconds)
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        elapsedLabel?.let {
            Text(
                stringResource(R.string.harness_generation_elapsed, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

internal data class HarnessElapsedParts(val minutes: Long, val seconds: Long)

internal fun harnessElapsedParts(elapsedMs: Long): HarnessElapsedParts {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
    return HarnessElapsedParts(totalSeconds / 60L, totalSeconds % 60L)
}
