package com.example.llamadroid.ui.agent.harness

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun HarnessCommandsTab(
    history: HarnessCommandHistoryUiState,
    hasSession: Boolean,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("harness_commands_tab"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "commands_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.harness_tab_commands),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.harness_command_history_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onAction(NativeHarnessUiAction.RefreshCommandHistory) },
                    enabled = hasSession && !history.isLoading,
                    modifier = Modifier.testTag("harness_commands_refresh"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.harness_commands_refresh),
                    )
                }
            }
        }
        if (!hasSession) {
            item(key = "commands_no_session") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_tab_commands),
                    message = stringResource(R.string.harness_commands_no_session),
                )
            }
        } else if (history.isLoading && !history.hasSnapshot) {
            item(key = "commands_loading") {
                AppStatePanel(
                    kind = AppStateKind.Running,
                    title = stringResource(R.string.harness_tab_commands),
                    message = stringResource(R.string.harness_commands_loading),
                )
            }
        } else if (history.runs.isEmpty()) {
            item(key = "commands_empty") {
                AppStatePanel(
                    kind = if (history.loadFailed) AppStateKind.Error else AppStateKind.Empty,
                    title = stringResource(R.string.harness_tab_commands),
                    message = stringResource(
                        if (history.loadFailed) R.string.harness_commands_load_failed
                        else R.string.harness_commands_empty,
                    ),
                )
            }
        } else if (history.loadFailed) {
            item(key = "commands_load_error") {
                Text(
                    text = stringResource(R.string.harness_commands_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(history.runs.asReversed(), key = { it.id }) { run ->
            HarnessCommandRunCard(run)
        }

        if (history.canLoadOlder) {
            item(key = "commands_load_older") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (history.isLoadingOlder) {
                        Text(
                            text = stringResource(R.string.harness_commands_loading_older),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        TextButton(
                            onClick = { onAction(NativeHarnessUiAction.LoadOlderCommandHistory) },
                            modifier = Modifier.testTag("harness_commands_load_older"),
                        ) {
                            Text(stringResource(R.string.harness_commands_load_older), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessCommandRunCard(run: HarnessCommandRunUi) {
    val isDarkTheme = isSystemInDarkTheme()
    val colors = when (run.status) {
        HarnessCommandRunStatus.RUNNING -> if (isDarkTheme) {
            Color(0xFF214D33) to Color(0xFFC6F6D5)
        } else {
            Color(0xFFD8F3DC) to Color(0xFF14532D)
        }
        HarnessCommandRunStatus.BACKGROUND -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        HarnessCommandRunStatus.COMPLETED -> if (isDarkTheme) {
            Color(0xFF1E3A5F) to Color(0xFFD6E8FF)
        } else {
            Color(0xFFDBEAFE) to Color(0xFF1E3A8A)
        }
        HarnessCommandRunStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val durationMs = rememberHarnessCommandDuration(run)
    val statusLabel = when (run.status) {
        HarnessCommandRunStatus.RUNNING -> stringResource(R.string.harness_command_status_running)
        HarnessCommandRunStatus.BACKGROUND -> stringResource(R.string.harness_command_status_background)
        HarnessCommandRunStatus.COMPLETED -> stringResource(R.string.harness_command_status_completed)
        HarnessCommandRunStatus.FAILED -> stringResource(R.string.harness_command_status_failed)
    }
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().testTag("harness_command_run_${run.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.harness_command_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(color = colors.first, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = statusLabel,
                        color = colors.second,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            run.timestampMs?.let { timestamp ->
                val date = Date(timestamp)
                Text(
                    text = "${DateFormat.getDateFormat(context).format(date)} · ${DateFormat.getTimeFormat(context).format(date)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            durationMs?.let { duration ->
                Text(
                    text = stringResource(R.string.harness_command_duration_label, formatHarnessCommandDuration(duration)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CommandBoundedText(
                value = run.command,
                maxHeight = 104.dp,
                tag = "harness_command_source_${run.id}",
            )
            if (run.output.isNotEmpty() || run.status == HarnessCommandRunStatus.RUNNING ||
                run.status == HarnessCommandRunStatus.BACKGROUND
            ) {
                val outputText = if (run.output.isEmpty()) {
                    stringResource(R.string.harness_commands_waiting_output)
                } else run.output
                Text(
                    text = stringResource(R.string.harness_command_output_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                CommandBoundedText(
                    value = outputText,
                    maxHeight = 220.dp,
                    tag = "harness_command_output_${run.id}",
                )
                if (!run.jobId.isNullOrBlank() && run.status == HarnessCommandRunStatus.BACKGROUND) {
                    Text(
                        text = stringResource(R.string.harness_commands_background_status_unobserved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (run.outputTruncated) {
                    Text(
                        text = stringResource(R.string.harness_command_output_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberHarnessCommandDuration(run: HarnessCommandRunUi): Long? {
    val observedAtMs = remember(run.id) { SystemClock.elapsedRealtime() }
    val elapsedNowMs = produceState(
        initialValue = observedAtMs,
        key1 = run.id,
        key2 = run.status,
        key3 = run.startedAtMs,
    ) {
        while (run.status == HarnessCommandRunStatus.RUNNING) {
            value = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }.value
    if (run.status != HarnessCommandRunStatus.RUNNING) return run.durationMs

    val startEpochMs = run.startedAtMs?.takeIf { it >= COMMAND_EPOCH_START_MS }
    return if (startEpochMs != null) {
        (System.currentTimeMillis() - startEpochMs).coerceAtLeast(run.durationMs ?: 0L)
    } else {
        (run.durationMs ?: 0L) + (elapsedNowMs - observedAtMs).coerceAtLeast(0L)
    }
}

@Composable
private fun CommandBoundedText(value: String, maxHeight: androidx.compose.ui.unit.Dp, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp, max = maxHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
            .testTag(tag),
    ) {
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatHarnessCommandDuration(durationMs: Long): String {
    val millis = durationMs.coerceAtLeast(0L)
    val seconds = millis / 1_000L
    return when {
        seconds >= 3_600L -> stringResource(
            R.string.harness_command_duration_hours,
            (seconds / 3_600L).toInt(),
            ((seconds % 3_600L) / 60L).toInt(),
        )
        seconds >= 60L -> stringResource(
            R.string.harness_command_duration_minutes,
            (seconds / 60L).toInt(),
            (seconds % 60L).toInt(),
        )
        seconds >= 1L -> stringResource(R.string.harness_command_duration_seconds, seconds.toInt())
        else -> stringResource(R.string.harness_command_duration_milliseconds, millis.toInt())
    }
}

private const val COMMAND_EPOCH_START_MS = 946_684_800_000L
