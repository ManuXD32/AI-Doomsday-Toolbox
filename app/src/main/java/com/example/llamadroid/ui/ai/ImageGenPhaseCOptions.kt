package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.ui.components.IntSliderWithInput
import com.example.llamadroid.ui.components.SliderWithInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageGenInpaintOptionsCard(
    maskPath: String?,
    hasSourceImage: Boolean,
    onDrawMask: () -> Unit,
    onImportMask: () -> Unit,
    automaticSelectionModels: List<ModelEntity>,
    automaticSelectionModelPath: String?,
    onAutomaticSelectionModelPathChange: (String) -> Unit,
    automaticSelectionRunning: Boolean,
    onAutoSelectSubject: () -> Unit,
    onAutoSelectBackground: () -> Unit,
    onInstallAutomaticModel: () -> Unit,
    strength: Float,
    onStrengthChange: (Float) -> Unit,
    imgCfgScale: Float,
    onImgCfgScaleChange: (Float) -> Unit
) {
    var importExpanded by rememberSaveable { mutableStateOf(false) }
    var automaticModelExpanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.imagegen_inpaint_step_mask), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.imagegen_inpaint_description), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDrawMask, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imagegen_inpaint_draw_mask))
            }
            if (maskPath != null) {
                Text(
                    stringResource(R.string.imagegen_inpaint_mask_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                stringResource(R.string.imagegen_inpaint_auto_selection),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.imagegen_inpaint_auto_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (automaticSelectionModels.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = automaticModelExpanded,
                    onExpandedChange = { automaticModelExpanded = !automaticModelExpanded }
                ) {
                    OutlinedTextField(
                        value = automaticSelectionModels
                            .firstOrNull { it.path == automaticSelectionModelPath }
                            ?.filename
                            ?: stringResource(R.string.imagegen_inpaint_auto_choose_model),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text(stringResource(R.string.imagegen_inpaint_auto_model_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = automaticModelExpanded)
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = automaticModelExpanded,
                        onDismissRequest = { automaticModelExpanded = false }
                    ) {
                        automaticSelectionModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = {
                                    onAutomaticSelectionModelPathChange(model.path)
                                    automaticModelExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onAutoSelectSubject,
                    enabled = hasSourceImage && automaticSelectionModelPath != null && !automaticSelectionRunning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.imagegen_inpaint_auto_subject)) }
                OutlinedButton(
                    onClick = onAutoSelectBackground,
                    enabled = hasSourceImage && automaticSelectionModelPath != null && !automaticSelectionRunning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.imagegen_inpaint_auto_background)) }
                if (automaticSelectionRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.imagegen_inpaint_auto_running),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (!hasSourceImage) {
                    Text(
                        stringResource(R.string.imagegen_inpaint_auto_choose_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(stringResource(R.string.imagegen_inpaint_auto_model_missing), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onInstallAutomaticModel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imagegen_inpaint_install_auto_model))
                }
            }
            TextButton(onClick = { importExpanded = !importExpanded }) {
                Text(stringResource(if (importExpanded) R.string.imagegen_hide_import else R.string.imagegen_show_import))
            }
            if (importExpanded) OutlinedButton(onClick = onImportMask, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imagegen_inpaint_select_mask))
            }
            SliderWithInput(
                value = strength,
                onValueChange = onStrengthChange,
                valueRange = 0f..1f,
                label = stringResource(R.string.imagegen_inpaint_denoising),
                decimalPlaces = 2
            )
            Text(
                stringResource(R.string.imagegen_inpaint_step_adjust),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            SliderWithInput(
                value = imgCfgScale,
                onValueChange = onImgCfgScaleChange,
                valueRange = 0f..20f,
                label = stringResource(R.string.imagegen_inpaint_img_cfg),
                decimalPlaces = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageGenADetailerOptionsCard(
    inputMode: ADetailerInputMode,
    onInputModeChange: (ADetailerInputMode) -> Unit,
    supportsExistingImage: Boolean,
    supportsGeneratedImage: Boolean,
    detectors: List<ModelEntity>,
    detectorPath: String?,
    onDetectorPathChange: (String?) -> Unit,
    onInstallDetector: () -> Unit,
    detailPrompt: String,
    onDetailPromptChange: (String) -> Unit,
    detailNegativePrompt: String,
    onDetailNegativePromptChange: (String) -> Unit,
    confidence: Float,
    onConfidenceChange: (Float) -> Unit,
    denoising: Float,
    onDenoisingChange: (Float) -> Unit,
    maskBlur: Int,
    onMaskBlurChange: (Int) -> Unit,
    padding: Int,
    onPaddingChange: (Int) -> Unit,
    maxDetections: Int,
    onMaxDetectionsChange: (Int) -> Unit,
    advancedArgs: String,
    onAdvancedArgsChange: (String) -> Unit
) {
    var detectorExpanded by rememberSaveable { mutableStateOf(false) }
    var expertExpanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.imagegen_adetailer_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.imagegen_adetailer_description), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.imagegen_adetailer_step_input), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = inputMode == ADetailerInputMode.EXISTING_IMAGE,
                    onClick = { onInputModeChange(ADetailerInputMode.EXISTING_IMAGE) },
                    enabled = supportsExistingImage,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text(stringResource(R.string.imagegen_adetailer_existing)) }
                SegmentedButton(
                    selected = inputMode == ADetailerInputMode.GENERATED_IMAGE,
                    onClick = { onInputModeChange(ADetailerInputMode.GENERATED_IMAGE) },
                    enabled = supportsGeneratedImage,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text(stringResource(R.string.imagegen_adetailer_generated)) }
            }
            Text(stringResource(R.string.imagegen_adetailer_input_description), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.imagegen_adetailer_step_detector), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            ExposedDropdownMenuBox(expanded = detectorExpanded, onExpandedChange = { detectorExpanded = !detectorExpanded }) {
                OutlinedTextField(
                    value = detectors.firstOrNull { it.path == detectorPath }?.filename
                        ?: stringResource(R.string.imagegen_adetailer_select_detector),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(detectorExpanded) }
                )
                ExposedDropdownMenu(expanded = detectorExpanded, onDismissRequest = { detectorExpanded = false }) {
                    detectors.forEach { model ->
                        DropdownMenuItem(text = { Text(model.filename) }, onClick = {
                            onDetectorPathChange(model.path)
                            detectorExpanded = false
                        })
                    }
                }
            }
            if (detectors.isEmpty()) {
                OutlinedButton(onClick = onInstallDetector, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imagegen_adetailer_install_detector))
                }
            }
            Text(stringResource(R.string.imagegen_adetailer_step_prompt), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = detailPrompt,
                onValueChange = onDetailPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.imagegen_adetailer_prompt)) },
                supportingText = {
                    if (inputMode == ADetailerInputMode.GENERATED_IMAGE) {
                        Text(stringResource(R.string.imagegen_adetailer_prompt_inherits))
                    }
                }
            )
            OutlinedTextField(value = detailNegativePrompt, onValueChange = onDetailNegativePromptChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.imagegen_adetailer_negative_prompt)) })
            TextButton(onClick = { expertExpanded = !expertExpanded }) {
                Text(stringResource(if (expertExpanded) R.string.imagegen_hide_expert else R.string.imagegen_show_expert))
            }
            if (expertExpanded) {
                SliderWithInput(value = confidence, onValueChange = onConfidenceChange, valueRange = 0f..1f, label = stringResource(R.string.imagegen_adetailer_confidence), decimalPlaces = 2)
                SliderWithInput(value = denoising, onValueChange = onDenoisingChange, valueRange = 0f..1f, label = stringResource(R.string.imagegen_adetailer_denoising), decimalPlaces = 2)
                IntSliderWithInput(value = maskBlur, onValueChange = onMaskBlurChange, valueRange = 0..64, label = stringResource(R.string.imagegen_adetailer_mask_blur))
                IntSliderWithInput(value = padding, onValueChange = onPaddingChange, valueRange = 0..256, label = stringResource(R.string.imagegen_adetailer_padding))
                IntSliderWithInput(value = maxDetections, onValueChange = onMaxDetectionsChange, valueRange = 1..32, label = stringResource(R.string.imagegen_adetailer_max_detections))
                OutlinedTextField(
                    value = advancedArgs,
                    onValueChange = onAdvancedArgsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.imagegen_adetailer_advanced_args)) },
                    supportingText = { Text(stringResource(R.string.imagegen_adetailer_advanced_args_description)) }
                )
            }
        }
    }
}
