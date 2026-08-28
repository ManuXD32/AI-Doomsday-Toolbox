package com.example.llamadroid.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Per-component local placement is meaningful only for the selected accelerator binary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdBackendPlacementControls(
    accelerator: String,
    textEncoder: String,
    diffusion: String,
    vae: String,
    onTextEncoderChange: (String) -> Unit,
    onDiffusionChange: (String) -> Unit,
    onVaeChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sd_component_placement_title))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SdPlacementPicker(stringResource(R.string.sd_component_placement_te), textEncoder, accelerator, onTextEncoderChange, Modifier.weight(1f))
            SdPlacementPicker(stringResource(R.string.sd_component_placement_diffusion), diffusion, accelerator, onDiffusionChange, Modifier.weight(1f))
            SdPlacementPicker(stringResource(R.string.sd_component_placement_vae), vae, accelerator, onVaeChange, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdPlacementPicker(label: String, value: String, accelerator: String, onChange: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("cpu", accelerator).forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}
