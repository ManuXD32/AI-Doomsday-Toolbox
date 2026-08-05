package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.WhisperVadAssetStore
import com.example.llamadroid.service.WhisperVadConfig
import com.example.llamadroid.service.WhisperVadModelCatalog
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.navigation.Screen
import java.io.File

/** Shared Whisper defaults. Model management remains on the existing Whisper Models screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    AppScreenScaffold(
        title = stringResource(R.string.whisper_settings_title),
        subtitle = stringResource(R.string.settings_whisper_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        WhisperSettingsContent(
            settingsRepo = settingsRepo,
            onOpenModels = { navController.navigate(Screen.WhisperModels.route) },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperSettingsContent(
    settingsRepo: SettingsRepository,
    onOpenModels: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val whisperThreads by settingsRepo.whisperThreads.collectAsState()
    val vadConfig by settingsRepo.whisperVadConfig.collectAsState()
    val installedVadModels = remember(vadConfig.modelPath) {
        WhisperVadAssetStore.installedModels(context)
    }
    val effectiveVadPath = WhisperVadAssetStore.resolvePath(context, vadConfig.modelPath)
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    fun updateVad(transform: (WhisperVadConfig) -> WhisperVadConfig) {
        settingsRepo.setWhisperVadConfig(transform(vadConfig).normalized())
    }

    LaunchedEffect(installedVadModels, vadConfig.modelPath) {
        if (vadConfig.modelPath != null && effectiveVadPath != vadConfig.modelPath) {
            settingsRepo.setWhisperVadConfig(
                vadConfig.copy(modelPath = effectiveVadPath).normalized()
            )
        }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.whisper_threads_title),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.whisper_threads_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            whisperThreads.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = whisperThreads.toFloat(),
                        onValueChange = { settingsRepo.setWhisperThreads(it.toInt()) },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                    Text(
                        stringResource(R.string.whisper_last_used_defaults_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.whisper_vad_title),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.whisper_vad_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vadConfig.enabled,
                            enabled = effectiveVadPath != null || vadConfig.enabled,
                            onCheckedChange = { enabled ->
                                updateVad {
                                    it.copy(
                                        enabled = enabled,
                                        modelPath = if (enabled) effectiveVadPath else it.modelPath
                                    )
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelMenuExpanded,
                        onExpandedChange = { expanded ->
                            if (installedVadModels.isNotEmpty()) modelMenuExpanded = expanded
                        }
                    ) {
                        OutlinedTextField(
                            value = effectiveVadPath?.let { path ->
                                val file = File(path)
                                WhisperVadModelCatalog.byFilename(file.name)?.displayName ?: file.name
                            } ?: stringResource(R.string.whisper_vad_no_model),
                            onValueChange = {},
                            readOnly = true,
                            enabled = installedVadModels.isNotEmpty(),
                            label = { Text(stringResource(R.string.whisper_vad_model_label)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = modelMenuExpanded
                                )
                            },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            installedVadModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            WhisperVadModelCatalog.byFilename(model.name)
                                                ?.displayName
                                                ?: model.name
                                        )
                                    },
                                    onClick = {
                                        updateVad { it.copy(modelPath = model.absolutePath) }
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                !vadConfig.enabled -> R.string.whisper_vad_status_disabled
                                effectiveVadPath == null -> R.string.whisper_vad_status_enabled_missing
                                else -> R.string.whisper_vad_status_enabled
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vadConfig.enabled && effectiveVadPath == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.whisper_vad_batch_only_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.whisper_vad_manage_models))
                    }

                    TextButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (advancedExpanded) {
                                    R.string.action_hide_advanced
                                } else {
                                    R.string.action_show_advanced
                                }
                            )
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (advancedExpanded) {
                        SliderWithInput(
                            value = vadConfig.threshold,
                            onValueChange = { value -> updateVad { it.copy(threshold = value) } },
                            valueRange = 0f..1f,
                            label = stringResource(R.string.whisper_vad_threshold),
                            decimalPlaces = 2,
                            enabled = vadConfig.enabled
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        IntSliderWithInput(
                            value = vadConfig.minSpeechDurationMs,
                            onValueChange = { value ->
                                updateVad { it.copy(minSpeechDurationMs = value) }
                            },
                            valueRange = 0..2_000,
                            label = stringResource(R.string.whisper_vad_min_speech),
                            suffix = stringResource(R.string.whisper_vad_milliseconds_suffix),
                            enabled = vadConfig.enabled
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        IntSliderWithInput(
                            value = vadConfig.minSilenceDurationMs,
                            onValueChange = { value ->
                                updateVad { it.copy(minSilenceDurationMs = value) }
                            },
                            valueRange = 0..2_000,
                            label = stringResource(R.string.whisper_vad_min_silence),
                            suffix = stringResource(R.string.whisper_vad_milliseconds_suffix),
                            enabled = vadConfig.enabled
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        IntSliderWithInput(
                            value = vadConfig.speechPaddingMs,
                            onValueChange = { value -> updateVad { it.copy(speechPaddingMs = value) } },
                            valueRange = 0..1_000,
                            label = stringResource(R.string.whisper_vad_padding),
                            suffix = stringResource(R.string.whisper_vad_milliseconds_suffix),
                            enabled = vadConfig.enabled
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SliderWithInput(
                            value = vadConfig.samplesOverlap,
                            onValueChange = { value -> updateVad { it.copy(samplesOverlap = value) } },
                            valueRange = 0f..1f,
                            label = stringResource(R.string.whisper_vad_overlap),
                            decimalPlaces = 2,
                            enabled = vadConfig.enabled
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        IntSliderWithInput(
                            value = vadConfig.maxSpeechDurationSeconds?.toInt() ?: 0,
                            onValueChange = { value ->
                                updateVad {
                                    it.copy(
                                        maxSpeechDurationSeconds = value
                                            .takeIf { seconds -> seconds > 0 }
                                            ?.toFloat()
                                    )
                                }
                            },
                            valueRange = 0..600,
                            label = stringResource(R.string.whisper_vad_max_speech),
                            suffix = stringResource(R.string.whisper_vad_seconds_suffix),
                            enabled = vadConfig.enabled
                        )
                        Text(
                            stringResource(R.string.whisper_vad_max_speech_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                settingsRepo.resetWhisperVadConfig(
                                    keepEnabled = vadConfig.enabled,
                                    keepModelPath = effectiveVadPath
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.whisper_vad_reset_defaults))
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.whisper_tips_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.whisper_tip_1) + "\n" +
                            stringResource(R.string.whisper_tip_2) + "\n" +
                            stringResource(R.string.whisper_tip_3),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
