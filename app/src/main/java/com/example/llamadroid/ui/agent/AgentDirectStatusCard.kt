package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R

/**
 * UI projection of the durable Direct runtime checkpoint.
 *
 * This model intentionally contains metadata only. The runtime can populate it
 * from its persisted checkpoint without putting prompts, tool arguments, or
 * tool output into a diagnostic surface.
 */
data class AgentDirectRuntimeStatus(
    val phase: String,
    val nextAction: String,
    val promptTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cachedTokens: Int? = null,
    val lastCacheInvalidation: String? = null,
    val compactionHistory: List<AgentDirectCompactionStatus> = emptyList()
)

data class AgentDirectCompactionStatus(
    val timestamp: String,
    val inputTokens: Int?,
    val retainedTokens: Int?,
    val reason: String? = null
)

/**
 * Bounded, scrollable status surface for the Direct Agent checkpoint.
 *
 * It is deliberately a standalone composable so the chat screen, project
 * details dialog, and future workspace surfaces can render the same compact
 * state without creating independent summaries.
 */
@Composable
fun AgentDirectRuntimeStatusCard(
    status: AgentDirectRuntimeStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_direct_status_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_phase),
                value = status.phase
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_next_action),
                value = status.nextAction,
                valueMaxLines = Int.MAX_VALUE
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_prompt),
                value = status.promptTokens?.let {
                    stringResource(R.string.agent_direct_status_tokens, it)
                } ?: stringResource(R.string.agent_direct_status_unknown)
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_input),
                value = status.inputTokens?.let {
                    stringResource(R.string.agent_direct_status_tokens, it)
                } ?: stringResource(R.string.agent_direct_status_unknown)
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_output),
                value = status.outputTokens?.let {
                    stringResource(R.string.agent_direct_status_tokens, it)
                } ?: stringResource(R.string.agent_direct_status_unknown)
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_cached),
                value = status.cachedTokens?.let {
                    stringResource(R.string.agent_direct_status_tokens, it)
                } ?: stringResource(R.string.agent_direct_status_unknown)
            )
            AgentDirectStatusLine(
                label = stringResource(R.string.agent_direct_status_invalidation),
                value = status.lastCacheInvalidation
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.agent_direct_status_none)
            )
            if (status.compactionHistory.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.agent_direct_status_compaction_history),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status.compactionHistory.take(4).forEach { compaction ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.agent_direct_status_compaction_item,
                                compaction.timestamp,
                                compaction.inputTokens?.toString()
                                    ?: stringResource(R.string.agent_direct_status_unknown),
                                compaction.retainedTokens?.toString()
                                    ?: stringResource(R.string.agent_direct_status_unknown),
                                compaction.reason
                                    ?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.agent_direct_status_none)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentDirectStatusLine(
    label: String,
    value: String,
    valueMaxLines: Int = 3
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
