package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessRuntimeReinstallPhase
import com.example.llamadroid.harness.HarnessRuntimeReinstallState

/**
 * Two-stage confirmation for deleting and restoring the managed Debian/Harness environment.
 * The final token is localized so accidental confirmation from pasted English copy is avoided
 * when the app is being used in Spanish.
 */
@Composable
fun HarnessRuntimeReinstallDialog(
    state: HarnessRuntimeReinstallState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf(0) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var typedToken by rememberSaveable { mutableStateOf("") }
    val token = stringResource(R.string.harness_runtime_reinstall_confirmation_token)
    val running = state.phase in setOf(
        HarnessRuntimeReinstallPhase.STOPPING,
        HarnessRuntimeReinstallPhase.RESETTING,
        HarnessRuntimeReinstallPhase.RESTORING,
    )

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                if (running) stringResource(R.string.harness_runtime_reinstall_progress_title)
                else if (stage == 0) stringResource(R.string.harness_runtime_reinstall_title)
                else stringResource(R.string.harness_runtime_reinstall_final_title)
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    running -> {
                        androidx.compose.foundation.layout.Column {
                            Text(stringResource(R.string.harness_runtime_reinstall_progress_message))
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier,
                            )
                        }
                    }
                    state.phase == HarnessRuntimeReinstallPhase.FAILED -> {
                        Text(
                            stringResource(
                                R.string.harness_runtime_reinstall_failed,
                                state.errorCode ?: "HARNESS_REINSTALL_FAILED",
                            )
                        )
                    }
                    stage == 0 -> {
                        androidx.compose.foundation.layout.Column {
                            Text(stringResource(R.string.harness_runtime_reinstall_warning))
                            Text(
                                stringResource(
                                    R.string.harness_runtime_reinstall_counts,
                                    state.localProjectCount,
                                    state.localRecordCount,
                                )
                            )
                            androidx.compose.foundation.layout.Row {
                                Checkbox(
                                    checked = acknowledged,
                                    onCheckedChange = { acknowledged = it },
                                )
                                Text(stringResource(R.string.harness_runtime_reinstall_acknowledge))
                            }
                        }
                    }
                    else -> {
                        androidx.compose.foundation.layout.Column {
                            Text(stringResource(R.string.harness_runtime_reinstall_final_warning))
                            Text(stringResource(R.string.harness_runtime_reinstall_type_token, token))
                            OutlinedTextField(
                                value = typedToken,
                                onValueChange = { typedToken = it },
                                label = { Text(stringResource(R.string.harness_runtime_reinstall_token_label)) },
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (stage == 0) stage = 1 else onConfirm()
                },
                enabled = !running && state.phase != HarnessRuntimeReinstallPhase.FAILED &&
                    if (stage == 0) acknowledged else typedToken == token,
            ) {
                Text(
                    if (stage == 0) stringResource(R.string.harness_runtime_reinstall_continue)
                    else stringResource(R.string.harness_runtime_reinstall_confirm)
                )
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.harness_cancel))
                }
            }
        },
    )
}
