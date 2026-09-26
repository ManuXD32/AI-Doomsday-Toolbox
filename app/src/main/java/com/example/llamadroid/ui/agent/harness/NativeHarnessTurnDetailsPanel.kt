package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle

/** Tail actions mirror the pinned Chat row while leaving transport to the host. */
@Composable
internal fun NativeHarnessTurnTailActions(
    target: NativeHarnessTurnActionTarget,
    tail: NativeHarnessTurnTailUi,
    hooks: NativeHarnessTranscriptActionHooks,
    modifier: Modifier = Modifier,
) {
    var usageOpen by rememberSaveable(target.turn, target.tailSequence) { mutableStateOf(false) }
    var timeOpen by rememberSaveable(target.turn, target.tailSequence) { mutableStateOf(false) }
    val actions = buildList {
        add(ResponsiveAction(
            label = stringResource(
                if (hooks.isCopying) R.string.harness_turn_cancel_copy
                else R.string.harness_turn_copy,
            ),
            onClick = {
                if (hooks.isCopying) hooks.onCancelCopy() else hooks.onCopyTurn(target)
            },
            style = ResponsiveActionStyle.Secondary,
        ))
        add(ResponsiveAction(
            label = stringResource(
                if (tail.branchUnavailable || tail.branchSequence == null) {
                    R.string.harness_turn_branch_unavailable
                } else {
                    R.string.harness_turn_branch
                },
            ),
            onClick = { hooks.onBranchTurn(target) },
            enabled = !hooks.isCopying && !tail.branchUnavailable && tail.branchSequence != null,
            style = ResponsiveActionStyle.Secondary,
        ))
        if (tail.usage != null) {
            add(ResponsiveAction(
                label = stringResource(R.string.harness_turn_usage),
                onClick = { usageOpen = true },
                style = ResponsiveActionStyle.Text,
            ))
        }
        if (tail.timing != null) {
            add(ResponsiveAction(
                label = stringResource(R.string.harness_turn_time),
                onClick = { timeOpen = true },
                style = ResponsiveActionStyle.Text,
            ))
        }
    }
    ResponsiveActionGroup(modifier = modifier, actions = actions)
    if (usageOpen) {
        NativeHarnessTurnUsageDialog(
            usage = tail.usage,
            onDismiss = { usageOpen = false },
        )
    }
    if (timeOpen) {
        NativeHarnessTurnTimeDialog(
            timing = tail.timing,
            onDismiss = { timeOpen = false },
        )
    }
}

/** Copy one user message through the host's paginated durable loader. */
@Composable
internal fun NativeHarnessMessageCopyAction(
    target: NativeHarnessMessageActionTarget,
    hooks: NativeHarnessTranscriptActionHooks,
    modifier: Modifier = Modifier,
) {
    ResponsiveActionGroup(
        modifier = modifier,
        actions = listOf(
            ResponsiveAction(
                label = stringResource(
                    if (hooks.isCopying) R.string.harness_turn_cancel_copy
                    else R.string.harness_message_copy,
                ),
                onClick = {
                    if (hooks.isCopying) hooks.onCancelCopy() else hooks.onCopyMessage(target)
                },
                style = ResponsiveActionStyle.Secondary,
            ),
        ),
    )
}

@Composable
private fun NativeHarnessTurnUsageDialog(
    usage: HarnessTokenUsageUi?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_turn_usage)) },
        text = {
            if (usage == null) {
                Text(stringResource(R.string.harness_turn_no_usage))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NativeHarnessTurnMetric(R.string.harness_turn_input, usage.inputTokens)
                    NativeHarnessTurnMetric(R.string.harness_turn_output, usage.outputTokens)
                    usage.cacheReadTokens.takeIf { it > 0L }?.let {
                        NativeHarnessTurnMetric(R.string.harness_turn_cache_read, it)
                    }
                    usage.cacheWriteTokens.takeIf { it > 0L }?.let {
                        NativeHarnessTurnMetric(R.string.harness_turn_cache_write, it)
                    }
                    usage.reasoningTokens?.let {
                        NativeHarnessTurnMetric(R.string.harness_turn_reasoning, it)
                    }
                    NativeHarnessTurnMetric(R.string.harness_turn_total, usage.total)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.harness_continue)) }
        },
    )
}

@Composable
private fun NativeHarnessTurnTimeDialog(
    timing: NativeHarnessTurnTimingUi?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_turn_time)) },
        text = {
            if (timing == null) {
                Text(stringResource(R.string.harness_turn_no_time))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    timing.runMs?.let { Text(stringResource(R.string.harness_turn_duration, it)) }
                    timing.tokensPerSecond?.let { Text(stringResource(R.string.harness_turn_speed, it)) }
                    timing.ttftMs?.let { Text(stringResource(R.string.harness_turn_ttft, it)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.harness_continue)) }
        },
    )
}

@Composable
private fun NativeHarnessTurnMetric(label: Int, value: Long) {
    Text(stringResource(label, value), style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun NativeHarnessRetryContent(
    retry: NativeHarnessModelRetryUi,
    modifier: Modifier = Modifier,
) {
    val current = retry.current
    val stateLabel = when (current.state) {
        NativeHarnessRetryState.SCHEDULED -> stringResource(R.string.harness_turn_retry_scheduled)
        NativeHarnessRetryState.STARTED -> stringResource(R.string.harness_turn_retry_started)
        NativeHarnessRetryState.CANCELLED -> stringResource(R.string.harness_turn_retry_cancelled)
    }
    AppSectionCard(modifier = modifier, shape = AppChromeDefaults.InnerCardShape) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.harness_turn_retry_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.harness_turn_retry_status,
                    current.retry,
                    current.maxRetries?.let { " / $it" }.orEmpty(),
                    stateLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.harness_turn_retry_delay, current.delayMs))
            current.failureMessage.takeIf { it.isNotBlank() }?.let {
                HarnessInlineText(stringResource(R.string.harness_turn_retry_failure, it))
            }
        }
    }
}

@Composable
internal fun NativeHarnessCompactionContent(
    compaction: NativeHarnessCompactionUi,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(compaction.sequence) { mutableStateOf(false) }
    val summary = if (compaction.shadowedItemCount != null && compaction.shadowedTokenCount != null) {
        stringResource(
            R.string.harness_turn_compaction_summary,
            compaction.shadowedItemCount,
            compaction.shadowedTokenCount,
        )
    } else {
        when (compaction.state) {
            NativeHarnessCompactionState.STARTED,
            NativeHarnessCompactionState.SUMMARIZED -> stringResource(R.string.harness_turn_compaction_running)
            NativeHarnessCompactionState.COMPLETED -> stringResource(R.string.harness_turn_compaction_done)
            NativeHarnessCompactionState.FAILED -> stringResource(R.string.harness_turn_compaction_failed)
        }
    }
    AppSectionCard(modifier = modifier, shape = AppChromeDefaults.InnerCardShape) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.harness_turn_compaction_title), style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall)
            if (compaction.summary != null) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.harness_structured_hide_details
                            else R.string.harness_structured_show_details,
                        ),
                    )
                }
                if (expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        HarnessInlineText(compaction.summary)
                    }
                }
            }
        }
    }
}
