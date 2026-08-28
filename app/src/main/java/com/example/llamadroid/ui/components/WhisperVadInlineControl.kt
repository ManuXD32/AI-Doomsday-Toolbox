package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.WhisperVadAssetStore

/** Compact shared VAD control for workflow cards; advanced tuning remains in Whisper settings. */
@Composable
fun WhisperVadInlineControl(
    settingsRepo: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by settingsRepo.whisperVadConfig.collectAsState()
    val resolvedModelPath = remember(config.modelPath) {
        WhisperVadAssetStore.resolvePath(context, config.modelPath)
    }
    val canToggle = resolvedModelPath != null || config.enabled
    val supportingText = when {
        config.enabled && resolvedModelPath == null ->
            stringResource(R.string.whisper_vad_install_required)
        config.enabled -> stringResource(R.string.whisper_vad_enabled_for_batch)
        else -> stringResource(R.string.whisper_vad_workflow_toggle_desc)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.whisper_vad_workflow_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.enabled && resolvedModelPath == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = config.enabled,
                enabled = canToggle,
                onCheckedChange = { enabled ->
                    settingsRepo.setWhisperVadConfig(
                        config.copy(
                            enabled = enabled,
                            modelPath = if (enabled) resolvedModelPath else config.modelPath
                        )
                    )
                }
            )
        }
    }
}
