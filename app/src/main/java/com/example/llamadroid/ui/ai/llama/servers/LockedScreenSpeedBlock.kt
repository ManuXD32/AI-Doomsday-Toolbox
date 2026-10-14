package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.perf.CoreMix
import com.example.llamadroid.service.perf.HintState
import com.example.llamadroid.service.perf.LockedScreenSpeedRecorder
import com.example.llamadroid.service.perf.LockedScreenView
import com.example.llamadroid.service.perf.PriorityState
import com.example.llamadroid.service.perf.ThreadFitReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val REFRESH_MS = 3_000L

/** Compact "Locked-screen speed" block of the server detail sheet (local llama-servers only). */
@Composable
fun LockedScreenSpeedBlock(port: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view by produceState<LockedScreenView?>(null, port) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching { LockedScreenSpeedRecorder.viewFor(context, port) }.getOrNull()
            }
            delay(REFRESH_MS)
        }
    }
    val current = view ?: return
    if (current.stats == null && current.report == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.locked_screen_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val stats = current.stats
            Line(
                stringResource(
                    R.string.locked_screen_speed_off,
                    speedText(stats?.screenOffTps, stats?.screenOffCpus, current.coreMixOff)
                )
            )
            Line(
                stringResource(
                    R.string.locked_screen_speed_on,
                    speedText(stats?.screenOnTps, stats?.screenOnCpus, current.coreMixOn)
                )
            )
            current.report?.let { report ->
                if (report.configuredThreads > 0) {
                    Line(
                        stringResource(
                            R.string.locked_screen_threads,
                            report.configuredThreads,
                            report.effectiveThreads,
                            stringResource(threadReasonRes(report.threadFitReason))
                        )
                    )
                }
                if (report.foregroundCpus != null) {
                    Line(
                        stringResource(
                            R.string.locked_screen_granted,
                            report.foregroundCpus,
                            report.topAppCpus ?: "?"
                        )
                    )
                }
                Line(stringResource(R.string.locked_screen_priority, stringResource(priorityRes(report.priorityState))))
                val hint = if (report.hintState == HintState.REFUSED.name && !report.hintDetail.isNullOrBlank()) {
                    stringResource(R.string.locked_screen_hints_refused, report.hintDetail)
                } else {
                    stringResource(hintRes(report.hintState))
                }
                Line(stringResource(R.string.locked_screen_hints, hint))
            }
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun speedText(tps: Double?, cpus: String?, mix: CoreMix?): String {
    if (tps == null) return stringResource(R.string.locked_screen_no_data)
    if (cpus.isNullOrBlank()) return stringResource(R.string.locked_screen_speed_value_no_cpus, tps.toFloat())
    val base = stringResource(R.string.locked_screen_speed_value, tps.toFloat(), cpus)
    if (mix == null) return base
    val parts = buildList {
        if (mix.prime > 0) add(stringResource(R.string.locked_screen_core_prime, mix.prime))
        if (mix.big > 0) add(stringResource(R.string.locked_screen_core_big, mix.big))
        if (mix.little > 0) add(stringResource(R.string.locked_screen_core_little, mix.little))
    }
    return "$base ${stringResource(R.string.locked_screen_core_mix, parts.joinToString(", "))}"
}

private fun threadReasonRes(reason: String): Int = when (reason) {
    ThreadFitReason.FITS.name -> R.string.locked_screen_reason_fits
    ThreadFitReason.REDUCED.name -> R.string.locked_screen_reason_reduced
    ThreadFitReason.UNKNOWN_CPUSET.name -> R.string.locked_screen_reason_unknown
    ThreadFitReason.NOT_IN_COMMAND.name -> R.string.locked_screen_reason_not_in_command
    else -> R.string.locked_screen_reason_disabled
}

private fun priorityRes(state: String): Int = when (state) {
    PriorityState.UNSUPPORTED.name -> R.string.locked_screen_priority_unsupported
    PriorityState.REQUESTED.name -> R.string.locked_screen_priority_requested
    PriorityState.APPLIED.name -> R.string.locked_screen_priority_applied
    PriorityState.REFUSED.name -> R.string.locked_screen_priority_refused
    else -> R.string.locked_screen_priority_off
}

private fun hintRes(state: String): Int = when (state) {
    HintState.UNSUPPORTED.name -> R.string.locked_screen_hints_unsupported
    HintState.PENDING.name -> R.string.locked_screen_hints_pending
    HintState.ACCEPTED.name -> R.string.locked_screen_hints_accepted
    HintState.REFUSED.name -> R.string.locked_screen_hints_refused_plain
    else -> R.string.locked_screen_hints_off
}
