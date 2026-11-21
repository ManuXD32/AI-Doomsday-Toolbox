package com.example.llamadroid.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.branchy.BranchyCommandBuilder
import com.example.llamadroid.branchy.BranchyEvent
import com.example.llamadroid.branchy.BranchyEventParser
import com.example.llamadroid.branchy.BranchyRunEntity
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.modeltools.BranchyExportSupport
import com.example.llamadroid.modeltools.registerLocalGgufModel
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.navigation.Screen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val done: Long, val total: Long) : ExportState
    data class Done(val file: File) : ExportState
    data class Failed(val text: String) : ExportState
}

@Composable
private fun exportTypeLabel(type: String): String = stringResource(
    when (type) {
        "f16" -> R.string.branchy_export_type_f16
        "q8_0" -> R.string.branchy_export_type_q8_0
        "q4_0" -> R.string.branchy_export_type_q4_0
        else -> R.string.branchy_export_type_auto
    }
)

/** "Export to GGUF" card for a run; registers the file as a local model and links to chat and benchmark. */
@Composable
fun BranchyExportSection(run: BranchyRunEntity, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("auto") }
    var state by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    var process by remember { mutableStateOf<Process?>(null) }
    DisposableEffect(Unit) { onDispose { process?.destroyForcibly(); job?.cancel() } }

    val runDir = File(run.runDir)
    val checkpoint = BranchyExportSupport.latestCheckpoint(runDir, run.stage)
    val running = state is ExportState.Running
    val noCheckpoint = stringResource(R.string.branchy_export_no_checkpoint)
    val noBinary = stringResource(R.string.branchy_export_no_binary)
    val failedFmt = stringResource(R.string.branchy_export_failed, "%1\$s")

    fun selectModel(path: String, route: String) {
        SettingsRepository(context).setSelectedModelPath(path)
        navController.navigate(route) { launchSingleTop = true }
    }

    AppSectionCard {
        AppSectionTitle(
            title = stringResource(R.string.branchy_export_title),
            supporting = stringResource(R.string.branchy_export_hint)
        )
        if (checkpoint == null) {
            Text(noCheckpoint, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.branchy_export_type_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BranchyExportSupport.TYPES.forEach { option ->
                    FilterChip(
                        selected = option == type,
                        enabled = !running,
                        onClick = { type = option },
                        label = { Text(exportTypeLabel(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            when (val s = state) {
                is ExportState.Running -> {
                    Text(stringResource(R.string.branchy_export_running), style = MaterialTheme.typography.bodyMedium)
                    if (s.total > 0L) {
                        LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is ExportState.Done -> Text(
                    stringResource(R.string.branchy_export_done, s.file.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                is ExportState.Failed -> Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ExportState.Idle -> Unit
            }
            Button(
                enabled = !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    val exe = BinaryRepository(context).getTieredBinary("branchy_trainer")?.absolutePath
                    if (exe == null) {
                        state = ExportState.Failed(noBinary)
                        return@Button
                    }
                    val out = BranchyExportSupport.outputFile(runDir, run.name, run.lastStep, type)
                    state = ExportState.Running(0L, 0L)
                    job = scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                out.parentFile?.mkdirs()
                                val tmp = File(out.parentFile, out.name + ".part").also { it.delete() }
                                val argv = BranchyCommandBuilder.exportGguf(
                                    exe, checkpoint.absolutePath, File(runDir, "tokenizer.json").absolutePath, tmp.absolutePath, type
                                )
                                val proc = ProcessBuilder(argv).directory(runDir).redirectErrorStream(false)
                                    .redirectError(ProcessBuilder.Redirect.appendTo(File(runDir, "stderr.log"))).start()
                                process = proc
                                var error: String? = null
                                proc.inputStream.bufferedReader().forEachLine { line ->
                                    when (val ev = BranchyEventParser.parse(line)) {
                                        is BranchyEvent.Progress -> state = ExportState.Running(ev.done, ev.total)
                                        is BranchyEvent.Error -> error = ev.message
                                        else -> Unit
                                    }
                                }
                                val exit = proc.waitFor()
                                process = null
                                if (exit != 0 || !tmp.isFile || tmp.length() == 0L) {
                                    tmp.delete()
                                    throw IllegalStateException(error ?: "exit $exit")
                                }
                                out.delete()
                                check(tmp.renameTo(out)) { "rename" }
                                registerLocalGgufModel(context, out, origin = "branchy_export", derivedFrom = null).getOrThrow()
                                out
                            }
                        }
                        state = result.fold(
                            onSuccess = { ExportState.Done(it) },
                            onFailure = { ExportState.Failed(failedFmt.replace("%1\$s", it.message.orEmpty())) }
                        )
                    }
                }
            ) { Text(stringResource(R.string.branchy_export_start), maxLines = 1, overflow = TextOverflow.Ellipsis) }

            val done = state as? ExportState.Done
            if (done != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { selectModel(done.file.absolutePath, Screen.Chats.route) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.branchy_export_open_chat), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { selectModel(done.file.absolutePath, Screen.Benchmark.route) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.branchy_export_benchmark), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}
