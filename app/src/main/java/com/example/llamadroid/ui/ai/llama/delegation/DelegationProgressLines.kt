package com.example.llamadroid.ui.ai.llama.delegation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.llamadroid.R
import com.example.llamadroid.service.delegation.DelegationLiveStatus
import java.util.Locale

/** Percent (0..100) of prompt tokens processed, or null when the total is unknown. */
fun delegationPromptPercent(processed: Int, total: Int): Int? =
    if (total <= 0) null else ((processed.coerceIn(0, total) * 100L) / total).toInt()

private fun count(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

/** Prompt-processing / generating lines for the subagent and the harness agent. */
@Composable
fun DelegationProgressLines(live: DelegationLiveStatus?, modifier: Modifier = Modifier) {
    if (live == null) return
    val subPercent = delegationPromptPercent(live.subagentPromptProcessed, live.subagentPromptTotal)
    val harnessPercent = delegationPromptPercent(live.harnessPromptProcessed, live.harnessPromptTotal)
    if (subPercent == null && !live.subagentGenerating && harnessPercent == null) return
    Column(modifier) {
        if (subPercent != null && !live.subagentGenerating) {
            ProgressLine(
                stringResource(
                    R.string.native_chat_delegation_viewer_subagent_processing, subPercent,
                    count(live.subagentPromptProcessed), count(live.subagentPromptTotal)
                ),
                subPercent
            )
        } else if (live.subagentGenerating) {
            Text(
                stringResource(R.string.native_chat_delegation_viewer_subagent_generating),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (harnessPercent != null) {
            ProgressLine(
                stringResource(
                    R.string.native_chat_delegation_viewer_harness_processing, harnessPercent,
                    count(live.harnessPromptProcessed), count(live.harnessPromptTotal)
                ),
                harnessPercent
            )
        }
    }
}

@Composable
private fun ProgressLine(text: String, percent: Int) {
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
}
