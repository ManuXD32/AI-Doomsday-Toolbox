package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.modeltools.FailReason
import com.example.llamadroid.modeltools.GgufQuantizeSupport
import com.example.llamadroid.modeltools.GgufQuantizer
import com.example.llamadroid.modeltools.QuantEvent
import com.example.llamadroid.modeltools.QuantTarget
import com.example.llamadroid.modeltools.registerLocalGgufModel
import com.example.llamadroid.util.FormatUtils
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface QuantUiState {
    data object Idle : QuantUiState
    data class Running(val done: Int, val total: Int) : QuantUiState
    data class Finished(val message: String, val error: Boolean) : QuantUiState
}

/** Dialog that quantizes a local GGUF [model] and registers the result as another local model. */
@Composable
fun QuantizeModelDialog(model: ModelEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(QuantTarget.Q8_0) }
    var state by remember { mutableStateOf<QuantUiState>(QuantUiState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    val source = File(model.path)
    val requantize = GgufQuantizeSupport.looksQuantized(model.filename)
    val estimate = GgufQuantizeSupport.estimateOutputBytes(model.sizeBytes, model.filename, target)
    val outName = remember(model.path, target) {
        GgufQuantizeSupport.uniqueOutputFile(
            source.parentFile ?: File("."),
            GgufQuantizeSupport.outputName(model.filename, target.ggufName),
            source
        ).name
    }
    val running = state is QuantUiState.Running

    // Resolved up front: stringResource is not available inside the collector coroutine.
    val noBinary = stringResource(R.string.model_quantize_fail_no_binary)
    val cancelled = stringResource(R.string.model_quantize_fail_cancelled)
    val noSpaceFmt = stringResource(R.string.model_quantize_fail_no_space, "%1\$s")
    val exitFmt = stringResource(R.string.model_quantize_fail_exit, "%1\$s")
    val doneFmt = stringResource(R.string.model_quantize_done, "%1\$s")
    val regFmt = stringResource(R.string.model_quantize_register_failed, "%1\$s")

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.model_quantize_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.model_quantize_source, model.filename, FormatUtils.formatFileSize(model.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall
                )
                if (requantize) {
                    Text(
                        stringResource(R.string.model_quantize_requantize_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(stringResource(R.string.model_quantize_type_label), style = MaterialTheme.typography.labelLarge)
                QuantTarget.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == target, enabled = !running, role = Role.RadioButton) { target = option }
                    ) {
                        RadioButton(selected = option == target, onClick = null, enabled = !running)
                        Text(
                            stringResource(
                                when (option) {
                                    QuantTarget.Q8_0 -> R.string.model_quantize_q8_0
                                    QuantTarget.Q4_K_M -> R.string.model_quantize_q4_k_m
                                    QuantTarget.Q4_0 -> R.string.model_quantize_q4_0
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                Text(
                    stringResource(R.string.model_quantize_estimate, FormatUtils.formatFileSize(estimate), outName),
                    style = MaterialTheme.typography.bodySmall
                )
                when (val s = state) {
                    is QuantUiState.Running -> {
                        Text(
                            if (s.total > 0) stringResource(R.string.model_quantize_progress, s.done, s.total)
                            else stringResource(R.string.model_quantize_preparing),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (s.total > 0) {
                            LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is QuantUiState.Finished -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    QuantUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            if (state is QuantUiState.Finished && !(state as QuantUiState.Finished).error) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            } else {
                TextButton(
                    enabled = !running,
                    onClick = {
                        state = QuantUiState.Running(0, 0)
                        job = scope.launch {
                            GgufQuantizer(context).quantize(source, target, requantize).collect { ev ->
                                when (ev) {
                                    is QuantEvent.Progress -> state = QuantUiState.Running(ev.tensor, ev.total)
                                    is QuantEvent.Done -> {
                                        val reg = registerLocalGgufModel(context, ev.output, origin = "quantized", derivedFrom = model.filename)
                                        state = if (reg.isSuccess) {
                                            QuantUiState.Finished(doneFmt.replace("%1\$s", ev.output.name), false)
                                        } else {
                                            QuantUiState.Finished(regFmt.replace("%1\$s", reg.exceptionOrNull()?.message.orEmpty()), true)
                                        }
                                    }
                                    is QuantEvent.Failed -> state = QuantUiState.Finished(
                                        when (ev.reason) {
                                            FailReason.NO_BINARY -> noBinary
                                            FailReason.NO_SPACE -> noSpaceFmt.replace(
                                                "%1\$s", FormatUtils.formatFileSize(ev.detail.toLongOrNull() ?: 0L)
                                            )
                                            FailReason.EXIT_CODE -> exitFmt.replace("%1\$s", ev.detail)
                                            FailReason.CANCELLED -> cancelled
                                        },
                                        true
                                    )
                                }
                            }
                        }
                    }
                ) { Text(stringResource(R.string.model_quantize_start)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }) { Text(stringResource(if (running) R.string.action_cancel else R.string.action_close)) }
        }
    )
}
