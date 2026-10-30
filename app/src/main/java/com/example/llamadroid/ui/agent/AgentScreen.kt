package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.KnowledgeBaseEntity

@Composable
fun AgentKnowledgeBaseSelector(
    knowledgeBases: List<KnowledgeBaseEntity>,
    selectedIds: List<Long>,
    onSelectionChange: (List<Long>) -> Unit,
    onManage: () -> Unit
) {
    val stackHeader = shouldStackAgentKnowledgeHeader(LocalDensity.current.fontScale)
    val titleBlock: @Composable () -> Unit = {
        Column {
            Text(
                text = stringResource(R.string.agent_kb_selector_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (selectedIds.isEmpty()) {
                    stringResource(R.string.agent_kb_selector_none)
                } else {
                    stringResource(R.string.agent_kb_selector_count, selectedIds.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (stackHeader) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    titleBlock()
                    TextButton(
                        onClick = onManage,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.kb_manage_action))
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        titleBlock()
                    }
                    TextButton(onClick = onManage) {
                        Text(stringResource(R.string.kb_manage_action))
                    }
                }
            }
            if (knowledgeBases.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    knowledgeBases.forEach { kb ->
                        val selected = kb.id in selectedIds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onSelectionChange(
                                    if (selected) {
                                        selectedIds - kb.id
                                    } else {
                                        (selectedIds + kb.id).distinct()
                                    }
                                )
                            },
                            label = {
                                Text(kb.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        )
                    }
                }
            }
        }
    }
}

