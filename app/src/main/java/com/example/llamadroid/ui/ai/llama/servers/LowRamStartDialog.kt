package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

/** Confirmation shown when a manual server start was refused for lack of free RAM. */
@Composable
fun LowRamStartDialog(viewModel: ServerRegistryViewModel) {
    val prompt by viewModel.lowRamPrompt.collectAsState()
    val current = prompt ?: return
    AlertDialog(
        onDismissRequest = viewModel::dismissLowRamPrompt,
        title = { Text(stringResource(R.string.local_model_ram_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(current.message)
                Text(
                    stringResource(R.string.local_model_ram_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmLowRamStart) {
                Text(stringResource(R.string.local_model_ram_start_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissLowRamPrompt) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
