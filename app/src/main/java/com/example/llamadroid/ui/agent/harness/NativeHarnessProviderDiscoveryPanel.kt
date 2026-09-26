package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Native equivalent of the pinned ModelListEditor candidate picker. */
@Composable
internal fun NativeHarnessProviderDiscoveryPanel(
    providerId: String,
    discovery: HarnessProviderDiscoveryUi,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    if (!discovery.isLoading && !discovery.hasRun) return
    val query = remember(providerId, discovery.candidates) { mutableStateOf("") }
    val normalizedQuery = query.value.trim().lowercase()
    val visibleCandidates = if (normalizedQuery.isEmpty()) {
        discovery.candidates
    } else {
        discovery.candidates.filter { candidate ->
            candidate.id.lowercase().contains(normalizedQuery) ||
                candidate.name?.lowercase()?.contains(normalizedQuery) == true
        }
    }
    val discoveryErrorResource = discovery.errorCode?.let(::localizedHarnessNoticeMessage)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.harness_provider_discovery_title),
            style = MaterialTheme.typography.titleSmall,
        )
        when {
            discovery.isLoading -> Text(
                stringResource(R.string.harness_provider_discovery_loading),
                style = MaterialTheme.typography.bodySmall,
            )
            discovery.errorCode == "PROVIDER_DISCOVERY_EMPTY" -> Text(
                stringResource(R.string.harness_provider_discovery_empty),
                style = MaterialTheme.typography.bodySmall,
            )
            discovery.errorCode != null -> Text(
                stringResource(
                    discoveryErrorResource ?: R.string.harness_provider_discovery_error,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            discovery.candidates.isEmpty() -> Text(
                stringResource(R.string.harness_provider_discovery_empty),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                OutlinedTextField(
                    value = query.value,
                    onValueChange = { query.value = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_provider_discovery_search)) },
                    singleLine = true,
                )
                Text(
                    stringResource(
                        R.string.harness_provider_discovery_selected,
                        discovery.selectedIds.size,
                        discovery.candidates.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                val allVisibleSelected = visibleCandidates.isNotEmpty() &&
                    visibleCandidates.all { it.id in discovery.selectedIds }
                TextButton(
                    onClick = {
                        onAction(
                            NativeHarnessUiAction.SetDiscoveredProviderModelsSelection(
                                providerId = providerId,
                                modelIds = visibleCandidates.map { it.id },
                                selected = !allVisibleSelected,
                            )
                        )
                    },
                    enabled = visibleCandidates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (allVisibleSelected) {
                                R.string.harness_provider_discovery_clear_matching
                            } else {
                                R.string.harness_provider_discovery_select_matching
                            }
                        )
                    )
                }
                if (visibleCandidates.isEmpty()) {
                    Text(
                        stringResource(R.string.harness_provider_discovery_no_matches),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleCandidates, key = { it.id }) { candidate ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = candidate.id in discovery.selectedIds,
                                onCheckedChange = {
                                    onAction(
                                        NativeHarnessUiAction.ToggleDiscoveredProviderModel(
                                            providerId,
                                            candidate.id,
                                        )
                                    )
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    candidate.name?.takeIf(String::isNotBlank) ?: candidate.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val context = candidate.contextWindow?.toString()
                                    ?: stringResource(R.string.harness_provider_discovery_unknown)
                                val output = candidate.maxTokens?.toString()
                                    ?: stringResource(R.string.harness_provider_discovery_unknown)
                                Text(
                                    stringResource(
                                        R.string.harness_provider_discovery_metadata,
                                        context,
                                        output,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onAction(NativeHarnessUiAction.AdoptDiscoveredProviderModels(providerId))
                        },
                        enabled = discovery.selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.harness_provider_discovery_add))
                    }
                }
            }
        }
        if (!discovery.isLoading) {
            OutlinedButton(
                onClick = {
                    onAction(NativeHarnessUiAction.DismissDiscoveredProviderModels(providerId))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.harness_provider_discovery_close))
            }
        }
    }
}
