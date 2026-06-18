package com.example.llamadroid.ui.ai

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.onnx.OnnxTtsMetadata
import com.example.llamadroid.onnx.OnnxTtsStorage
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.util.FormatUtils
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class OnnxTtsGalleryItem(
    val file: File,
    val metadata: OnnxTtsMetadata?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnnxTtsGalleryScreen(navController: NavController) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val items = remember(refreshKey) {
        OnnxTtsStorage.listGeneratedAudio(context).map { file ->
            OnnxTtsGalleryItem(file = file, metadata = OnnxTtsStorage.readMetadata(file))
        }
    }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<OnnxTtsGalleryItem?>(null) }
    var pendingDeleteSelected by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    val selectionMode = selectedPaths.isNotEmpty()

    fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        playingPath = null
    }

    fun toggleSelection(path: String) {
        selectedPaths = if (path in selectedPaths) {
            selectedPaths - path
        } else {
            selectedPaths + path
        }
    }

    fun deleteFiles(files: List<File>) {
        if (files.any { it.absolutePath == playingPath }) {
            releasePlayer()
        }
        val result = OnnxTtsStorage.deleteGeneratedAudioSets(context, files)
        selectedPaths = emptySet()
        pendingDelete = null
        pendingDeleteSelected = false
        refreshKey++
        val success = !result.skippedUnsafe && result.failedFiles == 0 && result.deletedAudioFiles > 0
        Toast.makeText(
            context,
            if (success) {
                context.getString(R.string.onnx_tts_gallery_deleted_count, files.size)
            } else {
                context.getString(R.string.onnx_tts_gallery_delete_failed)
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    fun togglePlayback(file: File) {
        val current = player
        if (playingPath == file.absolutePath && current?.isPlaying == true) {
            current.pause()
            playingPath = null
            return
        }
        releasePlayer()
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    releasePlayer()
                }
                start()
                player = this
                playingPath = file.absolutePath
            }
        }.onFailure { error ->
            Toast.makeText(
                context,
                context.getString(R.string.onnx_tts_gallery_play_failed, error.message ?: context.getString(R.string.error_generic)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(items) {
        val currentPaths = items.map { it.file.absolutePath }.toSet()
        selectedPaths = selectedPaths.intersect(currentPaths)
    }

    DisposableEffect(Unit) {
        onDispose {
            releasePlayer()
        }
    }

    AppPageBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selectionMode) {
                                stringResource(R.string.onnx_tts_gallery_selected_count, selectedPaths.size)
                            } else {
                                stringResource(R.string.onnx_tts_gallery_title)
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selectionMode) {
                                    selectedPaths = emptySet()
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        ) {
                            Icon(
                                if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(
                                    if (selectionMode) R.string.onnx_tts_gallery_clear_selection else R.string.action_back
                                )
                            )
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = { selectedPaths = items.map { it.file.absolutePath }.toSet() }) {
                                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.onnx_tts_gallery_select_all))
                            }
                            IconButton(onClick = { pendingDeleteSelected = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.onnx_tts_gallery_delete_selected),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.onnx_tts_gallery_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (items.isEmpty()) {
                    item {
                        OnnxTtsGalleryEmptyCard()
                    }
                } else {
                    items(items, key = { it.file.absolutePath }) { item ->
                        OnnxTtsGalleryRow(
                            item = item,
                            selected = item.file.absolutePath in selectedPaths,
                            selectionMode = selectionMode,
                            isPlaying = playingPath == item.file.absolutePath,
                            onPlay = { togglePlayback(item.file) },
                            onToggleSelection = { toggleSelection(item.file.absolutePath) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.onnx_tts_gallery_delete_title)) },
            text = { Text(stringResource(R.string.onnx_tts_gallery_delete_desc, item.file.name)) },
            confirmButton = {
                TextButton(onClick = { deleteFiles(listOf(item.file)) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (pendingDeleteSelected) {
        val selectedFiles = items.filter { it.file.absolutePath in selectedPaths }.map { it.file }
        AlertDialog(
            onDismissRequest = { pendingDeleteSelected = false },
            title = { Text(stringResource(R.string.onnx_tts_gallery_delete_selected_title, selectedFiles.size)) },
            text = { Text(stringResource(R.string.onnx_tts_gallery_delete_selected_desc)) },
            confirmButton = {
                TextButton(
                    onClick = { deleteFiles(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty()
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelected = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun OnnxTtsGalleryEmptyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.onnx_tts_gallery_empty),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.onnx_tts_gallery_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnnxTtsGalleryRow(
    item: OnnxTtsGalleryItem,
    selected: Boolean,
    selectionMode: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit
) {
    val metadata = item.metadata
    val createdAt = metadata?.createdAtEpochMs?.takeIf { it > 0L } ?: item.file.lastModified()
    val primaryLine = stringResource(
        R.string.onnx_tts_gallery_primary_meta,
        item.file.extension.uppercase(Locale.getDefault()),
        formatTtsGalleryDate(createdAt)
    )
    val secondaryLine = listOfNotNull(
        metadata?.sourceName?.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.onnx_tts_gallery_source, it)
        },
        metadata?.voiceName?.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.onnx_tts_gallery_voice, it)
        },
        metadata?.durationSeconds?.takeIf { it > 0f }?.let {
            stringResource(R.string.onnx_tts_gallery_duration, FormatUtils.Display.formatDuration(it.toDouble()))
        }
    ).joinToString(" • ")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() },
                    onLongClick = onToggleSelection
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() }
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    primaryLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (secondaryLine.isNotBlank()) {
                    Text(
                        secondaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!selectionMode) {
                IconButton(onClick = onToggleSelection) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.onnx_tts_gallery_select),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatTtsGalleryDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
