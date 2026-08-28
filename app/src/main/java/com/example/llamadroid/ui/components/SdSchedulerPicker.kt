package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.service.SdScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdSchedulerPicker(
    value: SdScheduler?,
    onValueChange: (SdScheduler?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = value?.cliName ?: stringResource(R.string.sd_scheduler_auto)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text(stringResource(R.string.sd_scheduler_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sd_scheduler_auto)) },
                onClick = {
                    onValueChange(null)
                    expanded = false
                }
            )
            SdScheduler.entries.forEach { scheduler ->
                DropdownMenuItem(
                    text = { Text(scheduler.cliName) },
                    onClick = {
                        onValueChange(scheduler)
                        expanded = false
                    }
                )
            }
        }
    }
}
