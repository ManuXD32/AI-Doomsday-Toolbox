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
import com.example.llamadroid.service.perf.LockedScreenSpeedRecorder
import com.example.llamadroid.service.perf.LockedScreenView
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
