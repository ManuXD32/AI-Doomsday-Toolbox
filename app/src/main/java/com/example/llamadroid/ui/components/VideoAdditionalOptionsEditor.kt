package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.SamplingMethod
import com.example.llamadroid.service.VideoRuntimeOptions

/**
 * Edits the optional native video flags that are shared by local and distributed runs.
 *
 * The parent editor owns disclosure and scrolling. This component deliberately uses a
 * full-width vertical layout so long localized labels remain readable on narrow phones.
 */
@Composable
fun VideoAdditionalOptionsEditor(
    options: VideoRuntimeOptions,
    onOptionsChange: (VideoRuntimeOptions) -> Unit,
    modifier: Modifier = Modifier,
    availability: VideoUiAvailability = VideoUiAvailability()
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.video_controls_additional_options),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(R.string.video_controls_additional_options_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (availability.unavailableControls.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.video_controls_additional_unavailable,
                    availability.unavailableControls.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = stringResource(R.string.video_controls_additional_sampler),
            style = MaterialTheme.typography.labelLarge
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_img_cfg_scale),
            value = options.imgCfgScale,
            enabled = availability.isEnabled(VideoAdditionalControl.IMG_CFG_SCALE),
            onValueChange = { onOptionsChange(options.copy(imgCfgScale = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_guidance),
            value = options.guidance,
            enabled = availability.isEnabled(VideoAdditionalControl.GUIDANCE),
            onValueChange = { onOptionsChange(options.copy(guidance = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_slg_scale),
            value = options.slgScale,
            enabled = availability.isEnabled(VideoAdditionalControl.SLG_SCALE),
            onValueChange = { onOptionsChange(options.copy(slgScale = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_slg_start),
            value = options.skipLayerStart,
            enabled = availability.isEnabled(VideoAdditionalControl.SLG_START),
            onValueChange = { onOptionsChange(options.copy(skipLayerStart = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_slg_end),
            value = options.skipLayerEnd,
            enabled = availability.isEnabled(VideoAdditionalControl.SLG_END),
            onValueChange = { onOptionsChange(options.copy(skipLayerEnd = it)) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_skip_layers),
            value = options.skipLayers,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.SKIP_LAYERS),
            onValueChange = { onOptionsChange(options.copy(skipLayers = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_eta),
            value = options.eta,
            enabled = availability.isEnabled(VideoAdditionalControl.ETA),
            onValueChange = { onOptionsChange(options.copy(eta = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_strength),
            value = options.strength,
            enabled = availability.isEnabled(VideoAdditionalControl.STRENGTH),
            onValueChange = { onOptionsChange(options.copy(strength = it)) }
        )

        Text(
            text = stringResource(R.string.video_controls_high_noise_additional),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.video_controls_high_noise_sampling_method),
            style = MaterialTheme.typography.bodyMedium
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                FilterChip(
                    selected = options.highNoiseSamplingMethod == null,
                    enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SAMPLING_METHOD),
                    onClick = { onOptionsChange(options.copy(highNoiseSamplingMethod = null)) },
                    label = { Text(stringResource(R.string.video_controls_high_noise_sampling_default)) }
                )
            }
            items(SamplingMethod.entries, key = { it.name }) { method ->
                FilterChip(
                    selected = options.highNoiseSamplingMethod == method,
                    enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SAMPLING_METHOD),
                    onClick = { onOptionsChange(options.copy(highNoiseSamplingMethod = method)) },
                    label = { Text(method.cliName, maxLines = 1) }
                )
            }
        }
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_img_cfg_scale),
            value = options.highNoiseImgCfgScale,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_IMG_CFG_SCALE),
            onValueChange = { onOptionsChange(options.copy(highNoiseImgCfgScale = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_guidance),
            value = options.highNoiseGuidance,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_GUIDANCE),
            onValueChange = { onOptionsChange(options.copy(highNoiseGuidance = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_slg_scale),
            value = options.highNoiseSlgScale,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SLG_SCALE),
            onValueChange = { onOptionsChange(options.copy(highNoiseSlgScale = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_slg_start),
            value = options.highNoiseSkipLayerStart,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SLG_START),
            onValueChange = { onOptionsChange(options.copy(highNoiseSkipLayerStart = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_slg_end),
            value = options.highNoiseSkipLayerEnd,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SLG_END),
            onValueChange = { onOptionsChange(options.copy(highNoiseSkipLayerEnd = it)) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_high_noise_skip_layers),
            value = options.highNoiseSkipLayers,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_SKIP_LAYERS),
            onValueChange = { onOptionsChange(options.copy(highNoiseSkipLayers = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_high_noise_eta),
            value = options.highNoiseEta,
            enabled = availability.isEnabled(VideoAdditionalControl.HIGH_NOISE_ETA),
            onValueChange = { onOptionsChange(options.copy(highNoiseEta = it)) }
        )

        Text(
            text = stringResource(R.string.video_controls_additional_model_controls),
            style = MaterialTheme.typography.labelLarge
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_moe_boundary),
            value = options.moeBoundary,
            enabled = availability.isEnabled(VideoAdditionalControl.MOE_BOUNDARY),
            onValueChange = { onOptionsChange(options.copy(moeBoundary = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_vace_strength),
            value = options.vaceStrength,
            enabled = availability.isEnabled(VideoAdditionalControl.VACE_STRENGTH),
            onValueChange = { onOptionsChange(options.copy(vaceStrength = it)) }
        )
        OptionalFloatField(
            label = stringResource(R.string.video_controls_ip_adapter_strength),
            value = options.ipAdapterStrength,
            enabled = availability.isEnabled(VideoAdditionalControl.IP_ADAPTER_STRENGTH),
            onValueChange = { onOptionsChange(options.copy(ipAdapterStrength = it)) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_vae_format),
            value = options.vaeFormat.orEmpty(),
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.VAE_FORMAT),
            onValueChange = { onOptionsChange(options.copy(vaeFormat = it.trim().ifBlank { null })) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_sigmas),
            value = options.sigmas,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.SIGMAS),
            onValueChange = { onOptionsChange(options.copy(sigmas = it)) }
        )

        Text(
            text = stringResource(R.string.video_controls_reference_controls),
            style = MaterialTheme.typography.labelLarge
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_ref_image_args),
            value = options.refImageArgs,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.REF_IMAGE_ARGS),
            onValueChange = { onOptionsChange(options.copy(refImageArgs = it)) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_extra_sample_args),
            value = options.extraSampleArgs,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.EXTRA_SAMPLE_ARGS),
            onValueChange = { onOptionsChange(options.copy(extraSampleArgs = it)) }
        )
        OptionalStringField(
            label = stringResource(R.string.video_controls_extra_tiling_args),
            value = options.extraTilingArgs,
            keyboardType = KeyboardType.Text,
            enabled = availability.isEnabled(VideoAdditionalControl.EXTRA_TILING_ARGS),
            onValueChange = { onOptionsChange(options.copy(extraTilingArgs = it)) }
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_increase_ref_index),
            checked = options.increaseRefIndex,
            enabled = availability.isEnabled(VideoAdditionalControl.INCREASE_REF_INDEX),
            onCheckedChange = { onOptionsChange(options.copy(increaseRefIndex = it)) }
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_disable_auto_resize_ref_image),
            checked = options.disableAutoResizeRefImage,
            enabled = availability.isEnabled(VideoAdditionalControl.DISABLE_AUTO_RESIZE_REF_IMAGE),
            onCheckedChange = { onOptionsChange(options.copy(disableAutoResizeRefImage = it)) }
        )

        Text(
            text = stringResource(R.string.video_controls_tiling_controls),
            style = MaterialTheme.typography.labelLarge
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_circular),
            checked = options.circular,
            enabled = availability.isEnabled(VideoAdditionalControl.CIRCULAR),
            onCheckedChange = { onOptionsChange(options.copy(circular = it)) }
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_circular_x),
            checked = options.circularX,
            enabled = availability.isEnabled(VideoAdditionalControl.CIRCULAR_X),
            onCheckedChange = { onOptionsChange(options.copy(circularX = it)) }
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_circular_y),
            checked = options.circularY,
            enabled = availability.isEnabled(VideoAdditionalControl.CIRCULAR_Y),
            onCheckedChange = { onOptionsChange(options.copy(circularY = it)) }
        )
        VideoOptionSwitch(
            label = stringResource(R.string.video_controls_temporal_tiling),
            checked = options.temporalTiling,
            enabled = availability.isEnabled(VideoAdditionalControl.TEMPORAL_TILING),
            onCheckedChange = { onOptionsChange(options.copy(temporalTiling = it)) }
        )
    }
}

@Composable
private fun OptionalFloatField(
    label: String,
    value: Float?,
    enabled: Boolean,
    onValueChange: (Float?) -> Unit
) {
    OptionalStringField(
        label = label,
        value = value?.toString().orEmpty(),
        keyboardType = KeyboardType.Decimal,
        enabled = enabled,
        onValueChange = { onValueChange(it.toFloatOrNull()) }
    )
}

@Composable
private fun OptionalStringField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    var draft by rememberSaveable(label) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused && draft != value) draft = value
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { next ->
            draft = next
            onValueChange(next)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun VideoOptionSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, modifier = Modifier.weight(1f))
    }
}
