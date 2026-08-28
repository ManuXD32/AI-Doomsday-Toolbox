package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.SdTensorTypeRules
import com.example.llamadroid.service.SdTensorTypeRulesPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdTensorTypeRulesPicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val detectedPreset = SdTensorTypeRules.presetFor(value)
    var selectedPreset by remember { mutableStateOf(detectedPreset) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        val current = SdTensorTypeRules.presetFor(value)
        if (current != SdTensorTypeRulesPreset.AUTO || selectedPreset != SdTensorTypeRulesPreset.CUSTOM) {
            selectedPreset = current
        }
    }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = tensorTypeRulesPresetLabel(selectedPreset),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                label = { Text(stringResource(R.string.sd_tensor_rules_preset)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.sd_tensor_rules_desc)) }
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SdTensorTypeRulesPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(tensorTypeRulesPresetLabel(preset)) },
                        onClick = {
                            selectedPreset = preset
                            expanded = false
                            when (preset) {
                                SdTensorTypeRulesPreset.AUTO,
                                SdTensorTypeRulesPreset.VAE_F16 -> onValueChange(SdTensorTypeRules.valueFor(preset))
                                SdTensorTypeRulesPreset.CUSTOM -> {
                                    if (detectedPreset != SdTensorTypeRulesPreset.CUSTOM) {
                                        onValueChange("")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        if (selectedPreset == SdTensorTypeRulesPreset.CUSTOM) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.sd_tensor_rules_custom)) },
                placeholder = { Text(stringResource(R.string.sd_tensor_rules_custom_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                supportingText = {
                    Text(
                        text = stringResource(R.string.sd_tensor_rules_custom_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )
        }
    }
}

@Composable
private fun tensorTypeRulesPresetLabel(preset: SdTensorTypeRulesPreset): String = when (preset) {
    SdTensorTypeRulesPreset.AUTO -> stringResource(R.string.sd_tensor_rules_auto)
    SdTensorTypeRulesPreset.VAE_F16 -> stringResource(R.string.sd_tensor_rules_vae_f16)
    SdTensorTypeRulesPreset.CUSTOM -> stringResource(R.string.sd_tensor_rules_custom)
}
