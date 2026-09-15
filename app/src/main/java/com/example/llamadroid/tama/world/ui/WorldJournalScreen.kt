package com.example.llamadroid.tama.world.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/** Scroll-safe living-world journal and episode history. */
@Composable
fun WorldJournalScreen(
    state: WorldJournalUiState,
    callbacks: WorldJournalCallbacks,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val visibleEpisodes = remember(state.episodes, state.filter) {
        state.episodes
            .asSequence()
            .filter { episode -> episode.source == WorldEventSource.LIVING_WORLD }
            .filter { episode ->
                when (state.filter) {
                    WorldJournalFilter.ALL -> true
                    WorldJournalFilter.NOTABLE -> episode.importance >= WorldEventImportance.NOTABLE
                    WorldJournalFilter.MEMORIES -> episode.memoryEligible
                }
            }
            .toList()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") { WorldJournalHeader(callbacks) }
            item(key = "filters") { WorldJournalFilterRow(state.filter, callbacks) }
            item(key = "memory_policy") { WorldMemoryPolicyRow(state.memoryPolicy, callbacks) }
            if (state.pendingMemories.isNotEmpty()) {
                item(key = "pending_memories") { WorldMemoryCandidates(state.pendingMemories, callbacks) }
            }
            when {
                state.isLoading -> item(key = "loading") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.tama_world_journal_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error != null -> item(key = "error") {
                    WorldJournalStateCard(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.tama_world_journal_error_title),
                        body = state.error
                    )
                }
                visibleEpisodes.isEmpty() -> item(key = "empty") {
                    WorldJournalStateCard(
                        icon = Icons.Default.History,
                        title = stringResource(R.string.tama_world_journal_empty_title),
                        body = stringResource(R.string.tama_world_journal_empty_body)
                    )
                }
                else -> items(visibleEpisodes, key = { "episode:${it.id}" }) { episode ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        WorldEpisodeCard(
                            episode = episode,
                            expanded = state.selectedEpisodeId == episode.id,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldJournalHeader(callbacks: WorldJournalCallbacks) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tama_world_journal_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.tama_world_journal_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WorldJournalFilterRow(
    selected: WorldJournalFilter,
    callbacks: WorldJournalCallbacks
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WorldJournalFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { callbacks.onFilterChanged(filter) },
                label = { Text(stringResource(filter.labelRes), maxLines = 1) },
                leadingIcon = if (selected == filter) {
                    { Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(18.dp)) }
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun WorldMemoryPolicyRow(
    selected: WorldMemoryPolicy,
    callbacks: WorldJournalCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = stringResource(R.string.tama_world_memory_policy_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorldMemoryPolicy.entries.forEach { policy ->
                FilterChip(
                    selected = selected == policy,
                    onClick = { callbacks.onMemoryPolicyChanged(policy) },
                    label = { Text(stringResource(policy.labelRes), maxLines = 1) },
                    leadingIcon = if (selected == policy) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun WorldMemoryCandidates(
    candidates: List<WorldMemoryCandidateUi>,
    callbacks: WorldJournalCallbacks
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = stringResource(R.string.tama_world_memory_candidates_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = stringResource(R.string.tama_world_memory_candidates_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                candidates.take(10).forEach { candidate ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = candidate.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = candidate.summary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = candidate.timeRange,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (candidate.canSave) {
                                    TextButton(
                                        onClick = { callbacks.onSaveMemory(candidate.episodeId) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(stringResource(R.string.tama_world_memory_save), maxLines = 1)
                                    }
                                } else if (candidate.memoryStatus.equals("APPROVED", ignoreCase = true)) {
                                    Text(
                                        text = stringResource(R.string.tama_world_memory_approved),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldEpisodeCard(
    episode: WorldEpisodeUi,
    expanded: Boolean,
    callbacks: WorldJournalCallbacks
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { callbacks.onSelectEpisode(episode.id) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = episode.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = episode.timeRange,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    WorldImportanceChip(episode.importance)
                }
                Text(
                    text = episode.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 8 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = episode.locationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(episode.biome.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    episode.events.forEach { event ->
                        WorldEventRow(event, callbacks)
                    }
                    if (episode.events.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tama_world_journal_episode_no_events),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.memoryEligible) {
                        Text(
                            text = stringResource(R.string.tama_world_journal_memory_eligible),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldEventRow(
    event: WorldEventUi,
    callbacks: WorldJournalCallbacks
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { callbacks.onOpenEvent(event.id) },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = event.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = event.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                WorldImportanceChip(event.importance)
            }
            Text(
                text = event.detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (event.actorName != null || event.results.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    event.actorName?.let { actor ->
                        Text(
                            text = actor,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                    event.results.forEach { result ->
                        Text(
                            text = "${result.label}: ${result.value}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldImportanceChip(importance: WorldEventImportance) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = when (importance) {
            WorldEventImportance.MAJOR -> MaterialTheme.colorScheme.tertiaryContainer
            WorldEventImportance.MEMORABLE -> MaterialTheme.colorScheme.primaryContainer
            WorldEventImportance.NOTABLE -> MaterialTheme.colorScheme.secondaryContainer
            WorldEventImportance.ROUTINE -> MaterialTheme.colorScheme.surfaceVariant
            WorldEventImportance.TRACE -> Color.Transparent
        }
    ) {
        Text(
            text = stringResource(importance.labelRes),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun WorldJournalStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
