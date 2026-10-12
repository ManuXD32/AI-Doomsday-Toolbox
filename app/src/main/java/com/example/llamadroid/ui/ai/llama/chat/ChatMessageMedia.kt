package com.example.llamadroid.ui.ai.llama.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.core.content.FileProvider
import com.example.llamadroid.R
import com.example.llamadroid.data.db.KnowledgeBaseSourceStatus
import com.example.llamadroid.data.db.KnowledgeSourceEntity
import com.example.llamadroid.data.model.EmbeddedDocumentText
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PendingImageAttachment(
    imagePath: String,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = stringResource(R.string.llama_image_attached),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPreview)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.llama_image_attached), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = File(imagePath).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.llama_remove_attachment)
                )
            }
        }
    }
}

@Composable
internal fun PendingAudioAttachment(
    audioPath: String,
    onRemove: () -> Unit
) {
    AudioPlaybackRow(
        audioFile = File(audioPath),
        onRemove = onRemove
    )
}

@Composable
internal fun PendingVideoAttachment(
    videoPath: String,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    VideoAttachmentRow(
        videoFile = File(videoPath),
        onPreview = onPreview,
        onRemove = onRemove
    )
}

@Composable
internal fun VideoAttachmentRow(
    videoFile: File,
    onPreview: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, videoFile.absolutePath) {
        value = withContext(Dispatchers.IO) { extractVideoThumbnail(videoFile) }
    }
    val durationMs by produceState<Long?>(initialValue = null, videoFile.absolutePath) {
        value = withContext(Dispatchers.IO) { extractVideoDurationMs(videoFile) }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 88.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.llama_video_attached),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.llama_video_play),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.llama_video_attached),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = videoFile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                formatVideoDuration(durationMs)?.let { duration ->
                    Text(
                        text = stringResource(R.string.llama_video_duration, duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            onRemove?.let { remove ->
                IconButton(onClick = remove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.llama_remove_attachment)
                    )
                }
            }
        }
    }
}

@Composable
internal fun VideoAttachmentMissingContent() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.llama_video_file_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun LlamaVideoPreviewDialog(
    videoFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                AndroidView(
                    factory = { viewContext ->
                        android.widget.VideoView(viewContext).apply {
                            setVideoPath(videoFile.absolutePath)
                            setMediaController(android.widget.MediaController(viewContext))
                            setOnPreparedListener { player ->
                                player.isLooping = false
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 480.dp),
                    update = { view ->
                        if (view.tag != videoFile.absolutePath) {
                            view.tag = videoFile.absolutePath
                            view.setVideoPath(videoFile.absolutePath)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

internal fun extractVideoThumbnail(file: File): Bitmap? {
    if (!file.isFile) return null
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                480,
                270
            )
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
}

internal fun extractVideoDurationMs(file: File): Long? {
    if (!file.isFile) return null
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
}

internal fun formatVideoDuration(durationMs: Long?): String? {
    val totalSeconds = durationMs?.div(1000L)?.takeIf { it >= 0L } ?: return null
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.US, minutes, seconds)
    }
}

@Composable
internal fun DocumentExtractionPending() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.llama_document_extracting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
internal fun ChatDocumentKnowledgeCard(sources: List<KnowledgeSourceEntity>) {
    val pendingStatuses = setOf(
        KnowledgeBaseSourceStatus.QUEUED,
        KnowledgeBaseSourceStatus.EXTRACTING,
        KnowledgeBaseSourceStatus.CHUNKING,
        KnowledgeBaseSourceStatus.EMBEDDING,
        KnowledgeBaseSourceStatus.INDEXING
    )
    val pending = sources.any { it.status in pendingStatuses }
    val errors = sources.count { it.status == KnowledgeBaseSourceStatus.ERROR }
    val totalChunks = sources.sumOf { maxOf(it.progressTotal, it.chunkCount) }
    val embeddedChunks = sources.sumOf { source ->
        maxOf(source.embeddedChunkCount, if (source.progressTotal > 0) source.progressDone else 0)
    }
    val progress = if (totalChunks > 0) {
        (embeddedChunks.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llama_chat_documents_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = when {
                            errors > 0 -> stringResource(R.string.llama_chat_documents_error, errors)
                            pending -> stringResource(
                                R.string.llama_chat_documents_preparing,
                                sources.size,
                                embeddedChunks,
                                totalChunks
                            )
                            else -> stringResource(R.string.llama_chat_documents_ready, embeddedChunks)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (pending && totalChunks > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            sources.take(3).forEach { source ->
                Text(
                    text = stringResource(
                        R.string.llama_chat_document_status,
                        source.title,
                        chatDocumentSourceStatusLabel(source.status)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.status == KnowledgeBaseSourceStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sources.size > 3) {
                Text(
                    text = stringResource(R.string.llama_chat_documents_more, sources.size - 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
internal fun chatDocumentSourceStatusLabel(status: String): String = when (status) {
    KnowledgeBaseSourceStatus.QUEUED -> stringResource(R.string.kb_status_queued)
    KnowledgeBaseSourceStatus.EXTRACTING -> stringResource(R.string.kb_status_extracting)
    KnowledgeBaseSourceStatus.CHUNKING -> stringResource(R.string.kb_status_chunking)
    KnowledgeBaseSourceStatus.EMBEDDING,
    KnowledgeBaseSourceStatus.INDEXING -> stringResource(R.string.kb_status_embedding)
    KnowledgeBaseSourceStatus.INDEXED -> stringResource(R.string.kb_status_indexed)
    KnowledgeBaseSourceStatus.STALE -> stringResource(R.string.kb_status_stale)
    KnowledgeBaseSourceStatus.ERROR -> stringResource(R.string.kb_status_error)
    else -> status
}

@Composable
internal fun EmbeddedDocumentAttachment(document: EmbeddedDocumentText) {
    DocumentAttachmentSurface(
        name = document.name,
        text = document.text,
        onRemove = null
    )
}

@Composable
internal fun DocumentAttachmentSurface(
    name: String,
    text: String,
    onRemove: (() -> Unit)?
) {
    var isExpanded by remember(name, text) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.llama_document_attached),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llama_document_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(
                            R.string.llama_document_summary,
                            name,
                            estimateNativeChatTextTokens(text)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                onRemove?.let { remove ->
                    IconButton(
                        onClick = remove,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.llama_remove_attachment)
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.llama_document_content),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.86f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun LlamaImagePreviewDialog(
    imageFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                AsyncImage(
                    model = imageFile,
                    contentDescription = stringResource(R.string.llama_image_attached),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 480.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { saveLlamaChatImageToGallery(context, imageFile) }) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.llama_image_save_to_device))
                    }
                    TextButton(onClick = { shareLlamaChatImage(context, imageFile) }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

internal fun shareLlamaChatImage(context: Context, imageFile: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                context.getString(R.string.imagegen_share_chooser)
            )
        )
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(
                R.string.onnx_image_gen_share_failed,
                error.message ?: context.getString(R.string.error_generic)
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}

internal fun saveLlamaChatImageToGallery(context: Context, imageFile: File) {
    runCatching {
        require(imageFile.exists() && imageFile.isFile) { "Image file not found." }
        val extension = imageFile.extension.lowercase(Locale.US)
        val mimeType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
        val safeExtension = extension.ifBlank {
            when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
        }
        val displayNameBase = imageFile.nameWithoutExtension
            .ifBlank { "llama_image" }
            .replace(Regex("""\s+"""), "_")
        val displayName = "${displayNameBase}_${System.currentTimeMillis()}.$safeExtension"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI-Doomsday-Toolbox")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery entry.")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                imageFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open gallery output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, completeValues, null, null)
            }
            Toast.makeText(
                context,
                context.getString(R.string.llama_image_save_to_device_success),
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(
                R.string.llama_image_save_to_device_failed,
                error.message ?: context.getString(R.string.error_generic)
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
internal fun AudioPlaybackRow(
    audioFile: File,
    onRemove: (() -> Unit)? = null,
    onPlaybackChanged: ((Boolean, MediaPlayer?) -> Unit)? = null,
    autoPlay: Boolean = false,
    onAutoPlayConsumed: (() -> Unit)? = null
) {
    var mediaPlayer by remember(audioFile.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(audioFile.absolutePath) { mutableStateOf(false) }

    fun startPlayback() {
        mediaPlayer?.release()
        val player = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            setOnCompletionListener {
                isPlaying = false
                onPlaybackChanged?.invoke(false, it)
                runCatching { it.release() }
                mediaPlayer = null
            }
            start()
        }
        mediaPlayer = player
        isPlaying = true
        onPlaybackChanged?.invoke(true, player)
    }

    DisposableEffect(audioFile.absolutePath) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            isPlaying = false
            onPlaybackChanged?.invoke(false, null)
        }
    }

    LaunchedEffect(autoPlay, audioFile.absolutePath) {
        if (autoPlay && !isPlaying) {
            runCatching { startPlayback() }
            onAutoPlayConsumed?.invoke()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val current = mediaPlayer
                    if (current?.isPlaying == true) {
                        current.pause()
                        isPlaying = false
                        onPlaybackChanged?.invoke(false, current)
                    } else {
                        startPlayback()
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.llama_audio_attached),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.llama_audio_attached), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = audioFile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            onRemove?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.llama_remove_attachment)
                    )
                }
            }
        }
    }
}
