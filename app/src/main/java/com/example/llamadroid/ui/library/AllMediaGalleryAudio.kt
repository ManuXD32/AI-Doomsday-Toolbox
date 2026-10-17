package com.example.llamadroid.ui.library

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.audio.AudioWorkspaceRepository
import com.example.llamadroid.media.MediaGalleryItem
import com.example.llamadroid.media.MediaGallerySource
import com.example.llamadroid.onnx.OnnxTtsStorage

/** One-at-a-time MediaPlayer holder for gallery audio rows (same engine as the ONNX TTS gallery). */
@Stable
internal class GalleryAudioPlayer {
    private var player: MediaPlayer? = null
    var playingId: String? by mutableStateOf(null)
        private set

    fun release() {
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    fun toggle(item: MediaGalleryItem, onError: (String) -> Unit) {
        if (playingId == item.identity.value && player?.isPlaying == true) {
            player?.pause()
            playingId = null
            return
        }
        release()
        runCatching {
            MediaPlayer().apply {
                setDataSource(item.file.absolutePath)
                prepare()
                setOnCompletionListener { release() }
                start()
                player = this
                playingId = item.identity.value
            }
        }.onFailure { error ->
            release()
            onError(error.message.orEmpty())
        }
    }
}

@Composable
internal fun rememberGalleryAudioPlayer(): GalleryAudioPlayer {
    val holder = remember { GalleryAudioPlayer() }
    DisposableEffect(holder) { onDispose { holder.release() } }
    return holder
}

/** A full-width audio row: play/pause, title, meta line, share and delete. */
@Composable
internal fun MediaGalleryAudioRow(
    item: MediaGalleryItem,
    playing: Boolean,
    metaLine: String,
    onTogglePlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.action_pause else R.string.media_gallery_audio_play
                    ),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = item.title.ifBlank { item.file.nameWithoutExtension },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun MediaGalleryAudioDeleteDialog(
    item: MediaGalleryItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_gallery_audio_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.media_gallery_audio_delete_text,
                    item.title.ifBlank { item.file.name }
                ),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Deletes an audio item through the owner that knows its sidecars (and Room row for the studio). */
internal suspend fun deleteGalleryAudio(context: Context, item: MediaGalleryItem): Boolean = runCatching {
    when (item.source) {
        MediaGallerySource.ONNX_TTS -> {
            val result = OnnxTtsStorage.deleteGeneratedAudioSet(context, item.file)
            !result.skippedUnsafe && result.failedFiles == 0 && result.deletedAudioFiles > 0
        }
        MediaGallerySource.AUDIO_WORKSPACE -> {
            val jobId = item.file.parentFile?.name ?: return@runCatching false
            AudioWorkspaceRepository(context).deleteHistory(jobId)
        }
        else -> false
    }
}.getOrDefault(false)

internal fun toastGalleryAudioError(context: Context, message: String) {
    Toast.makeText(
        context,
        context.getString(R.string.onnx_tts_gallery_play_failed, message.ifBlank { context.getString(R.string.error_generic) }),
        Toast.LENGTH_LONG
    ).show()
}

internal fun toastGalleryAudioDeleted(context: Context, deleted: Boolean) {
    Toast.makeText(
        context,
        context.getString(
            if (deleted) R.string.media_gallery_audio_deleted else R.string.onnx_tts_gallery_delete_failed
        ),
        Toast.LENGTH_SHORT
    ).show()
}
