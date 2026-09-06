package com.example.llamadroid.ui.knowledge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.KnowledgeBaseRepository
import com.example.llamadroid.data.repository.KnowledgeChunkWindow
import com.example.llamadroid.data.repository.KnowledgeChunkWindowItem
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets

@Composable
fun KnowledgeChunkReaderScreen(
    navController: NavController,
    chunkId: Long
) {
    val context = LocalContext.current
    val repository = remember {
        KnowledgeBaseRepository(context, AppDatabase.getDatabase(context))
    }
    var window by remember(chunkId) { mutableStateOf<KnowledgeChunkWindow?>(null) }
    var error by remember(chunkId) { mutableStateOf<String?>(null) }
    var loading by remember(chunkId) { mutableStateOf(true) }

    LaunchedEffect(chunkId) {
        loading = true
        error = null
        window = runCatching { repository.getChunkWindow(chunkId, radius = 1) }
            .onFailure { error = it.message ?: it::class.java.simpleName }
            .getOrNull()
        loading = false
    }

    AppScreenScaffold(
        title = stringResource(R.string.kb_chunk_reader_title),
        subtitle = window?.sourceTitle ?: stringResource(R.string.kb_chunk_reader_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                ReaderStateMessage(error.orEmpty())
            }
            window == null -> {
                ReaderStateMessage(stringResource(R.string.kb_chunk_reader_not_found))
            }
            else -> {
                val chunkWindow = requireNotNull(window)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .walkthroughTarget("knowledge.chunk"),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        ChunkReaderHeader(chunkWindow)
                    }
                    items(chunkWindow.chunks, key = { it.chunkId }) { chunk ->
                        ChunkCard(
                            sourceTitle = chunkWindow.sourceTitle,
                            chunk = chunk
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderStateMessage(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChunkReaderHeader(window: KnowledgeChunkWindow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = window.knowledgeBaseName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = window.sourceTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.kb_chunk_reader_target,
                    window.targetChunkIndex + 1,
                    window.targetChunkId
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChunkCard(
    sourceTitle: String,
    chunk: KnowledgeChunkWindowItem
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    var expanded by remember(chunk.chunkId, chunk.isTarget) { mutableStateOf(chunk.isTarget) }
    val citation = remember(sourceTitle, chunk.chunkIndex, chunk.chunkId) {
        KnowledgeBaseRepository.chunkCitationMarkdown(sourceTitle, chunk.chunkIndex, chunk.chunkId)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (chunk.isTarget) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (chunk.isTarget) {
                            stringResource(R.string.kb_chunk_reader_cited_chunk, chunk.chunkIndex + 1)
                        } else {
                            stringResource(R.string.kb_chunk_reader_adjacent_chunk, chunk.chunkIndex + 1)
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(R.string.kb_chunk_reader_chunk_id, chunk.chunkId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        walkthroughTargets?.recordEvent("knowledge.chunk")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                resources.getString(R.string.kb_chunk_reader_clip_label),
                                "${citation}\n\n${chunk.text.trim()}"
                            )
                        )
                        Toast.makeText(context, resources.getString(R.string.kb_chunk_reader_copied), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy)
                    )
                }
                if (!chunk.isTarget) {
                    IconButton(onClick = {
                        expanded = !expanded
                        walkthroughTargets?.recordEvent("knowledge.chunk")
                    }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.kb_chunk_reader_collapse_adjacent
                                else R.string.kb_chunk_reader_expand_adjacent
                            )
                        )
                    }
                }
            }
            if (expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = chunk.text.trim(),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Default
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.kb_chunk_reader_adjacent_collapsed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = citation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
