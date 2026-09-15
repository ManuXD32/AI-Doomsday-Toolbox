package com.example.llamadroid.tama.world.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlin.math.roundToInt

/** Resource limits belong to the synthetic trainer, independently of living pet permissions. */
@Composable
internal fun BrainResourceSettingsCard(settings: BrainResourceSettingsUi, onChanged: (BrainResourceSettingsUi) -> Unit) {
    BrainCard {
        Text(stringResource(R.string.tama_world_brain_resources_title), style = MaterialTheme.typography.titleMedium)
        SafeSwitchRow(stringResource(R.string.tama_world_brain_charging_only), settings.chargingOnly) {
            onChanged(settings.copy(chargingOnly = it))
        }
        ResourceLimitSlider(settings.minimumBatteryPercent, 10..50,
            stringResource(R.string.tama_world_brain_battery_limit, settings.minimumBatteryPercent)) {
            onChanged(settings.copy(minimumBatteryPercent = it))
        }
        ResourceLimitSlider(settings.maxThreads, 1..8,
            stringResource(R.string.tama_world_brain_thread_limit, settings.maxThreads)) {
            onChanged(settings.copy(maxThreads = it))
        }
        ResourceLimitSlider(settings.environmentCount, 1..16,
            stringResource(R.string.tama_world_brain_environment_limit, settings.environmentCount)) {
            onChanged(settings.copy(environmentCount = it))
        }
        Text(stringResource(R.string.tama_world_brain_resources_body), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResourceLimitSlider(value: Int, range: IntRange, label: String, onCommit: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(value = draft.coerceIn(range.first.toFloat(), range.last.toFloat()),
        onValueChange = { draft = it },
        onValueChangeFinished = { onCommit(draft.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0))
}
