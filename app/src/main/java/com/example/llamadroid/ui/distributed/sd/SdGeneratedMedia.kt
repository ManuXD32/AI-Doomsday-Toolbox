@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.llamadroid.ui.distributed.sd

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.llamadroid.R
import com.example.llamadroid.media.MediaGalleryItem
import com.example.llamadroid.media.MediaGalleryType
import com.example.llamadroid.media.buildGeneratedMediaDetailLines
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SdGeneratedImageMetadata
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SdGeneratedMediaKind { IMAGE, VIDEO }

internal enum class SdGeneratedMediaFilter { ALL, IMAGES, VIDEOS }

internal data class SdGeneratedMediaItem(
    val file: File,
    val kind: SdGeneratedMediaKind,
    val mode: String,
    val prompt: String = "",
    val mimeType: String,
    val createdAt: Long,
    val imageMetadata: SdGeneratedImageMetadata? = null,
    val videoMetadata: GeneratedVideoMetadata? = null
)

internal fun toSdGeneratedMediaItem(item: MediaGalleryItem): SdGeneratedMediaItem =
    SdGeneratedMediaItem(
        file = item.file,
        kind = when (item.type) {
            MediaGalleryType.IMAGE -> SdGeneratedMediaKind.IMAGE
            // AUDIO never carries distributed proof; it is mapped only to keep the when exhaustive.
            MediaGalleryType.VIDEO, MediaGalleryType.AUDIO -> SdGeneratedMediaKind.VIDEO
        },
        mode = item.mode,
        prompt = item.prompt,
        mimeType = item.mimeType,
        createdAt = item.createdAt,
        imageMetadata = item.imageMetadata,
        videoMetadata = item.videoMetadata
    )

@Composable
internal fun SdGeneratedMediaCard(
    item: SdGeneratedMediaItem,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, item.file.absolutePath, item.mimeType) {
        value = withContext(Dispatchers.IO) { loadSdGalleryThumbnail(item) }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.12f)
                .background(RenderFarmPalette.graphiteSoft),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                ComposeImage(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (item.kind == SdGeneratedMediaKind.VIDEO) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        if (item.kind == SdGeneratedMediaKind.VIDEO) stringResource(R.string.sd_dist_gallery_video) else stringResource(R.string.sd_dist_gallery_image),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.prompt.ifBlank { item.file.nameWithoutExtension },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.sd_dist_gallery_item_meta,
                    sdGeneratedModeLabel(context, item),
                    formatSdGalleryDate(item.createdAt)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.sd_dist_gallery_open_in_app), maxLines = 1)
                }
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun SdGeneratedMediaDetailDialog(
    item: SdGeneratedMediaItem,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.sd_dist_gallery_details_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SdGeneratedMediaPreview(item)
                SdGeneratedMediaDetails(item)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenExternal) {
                Text(stringResource(R.string.action_open))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.action_share))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
internal fun SdGeneratedMediaPreview(item: SdGeneratedMediaItem) {
    when (item.kind) {
        SdGeneratedMediaKind.IMAGE -> {
            val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, item.file.absolutePath) {
                value = withContext(Dispatchers.IO) { decodeSdGalleryImage(item.file.absolutePath) }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .background(RenderFarmPalette.graphiteSoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    ComposeImage(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        SdGeneratedMediaKind.VIDEO -> {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .background(RenderFarmPalette.graphiteSoft, RoundedCornerShape(8.dp)),
                factory = { context ->
                    VideoView(context).apply {
                        tag = item.file.absolutePath
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setVideoURI(Uri.fromFile(item.file))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                        }
                    }
                },
                update = { view ->
                    if (view.tag != item.file.absolutePath) {
                        view.tag = item.file.absolutePath
                        view.setVideoURI(Uri.fromFile(item.file))
                        view.setOnPreparedListener { player ->
                            player.isLooping = true
                            view.start()
                        }
                    }
                }
            )
        }
    }
}

@Composable
internal fun SdGeneratedMediaDetails(item: SdGeneratedMediaItem) {
    val context = LocalContext.current
    val lines by produceState(initialValue = emptyList<Pair<String, String>>(), item.file.absolutePath) {
        value = withContext(Dispatchers.IO) { buildSdGeneratedMediaDetailLines(context, item) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lines.forEach { (label, value) ->
            SdGeneratedMediaParameterLine(label = label, value = value)
        }
        if (item.imageMetadata == null && item.videoMetadata == null) {
            Text(
                text = stringResource(R.string.sd_dist_gallery_no_metadata),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SdGeneratedMediaParameterLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(126.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 4
        )
    }
}

internal fun loadSdGalleryThumbnail(item: SdGeneratedMediaItem): android.graphics.Bitmap? =
    runCatching {
        when (item.kind) {
            SdGeneratedMediaKind.IMAGE -> decodeSdGalleryImage(item.file.absolutePath)
            SdGeneratedMediaKind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(item.file.absolutePath)
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }
        }
    }.getOrNull()

internal fun decodeSdGalleryImage(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    val sampleSize = generateSequence(1) { it * 2 }
        .first { maxDimension / it <= 768 }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(path, options)
}

internal fun openSdGeneratedMedia(context: Context, item: SdGeneratedMediaItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.sd_dist_gallery_open_with)))
    }.onFailure { error ->
        Toast.makeText(context, context.getString(R.string.sd_dist_gallery_open_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

internal fun shareSdGeneratedMedia(context: Context, item: SdGeneratedMediaItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.onFailure { error ->
        Toast.makeText(context, context.getString(R.string.sd_dist_gallery_open_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

internal fun formatSdGalleryDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

internal fun buildSdGeneratedMediaDetailLines(
    context: Context,
    item: SdGeneratedMediaItem
): List<Pair<String, String>> = buildGeneratedMediaDetailLines(
    context = context,
    file = item.file,
    modeLabel = sdGeneratedModeLabel(context, item),
    createdAt = item.createdAt,
    resolution = readSdGeneratedMediaResolution(item),
    imageMetadata = item.imageMetadata,
    videoMetadata = item.videoMetadata
)


internal fun readSdGeneratedMediaResolution(item: SdGeneratedMediaItem): String? {
    item.imageMetadata?.takeIf { it.width > 0 && it.height > 0 }?.let {
        return "${it.width} x ${it.height}"
    }
    item.videoMetadata?.let {
        return "${it.width} x ${it.height}"
    }
    return when (item.kind) {
        SdGeneratedMediaKind.IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.file.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                "${bounds.outWidth} x ${bounds.outHeight}"
            } else {
                null
            }
        }
        SdGeneratedMediaKind.VIDEO -> runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(item.file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (width != null && height != null && width > 0 && height > 0) "$width x $height" else null
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}

internal fun sdGeneratedModeLabel(context: Context, item: SdGeneratedMediaItem): String =
    when {
        item.kind == SdGeneratedMediaKind.VIDEO && item.mode == VideoGenerationMode.TXT2VID.folderName ->
            context.getString(R.string.video_gen_mode_txt2vid)
        item.kind == SdGeneratedMediaKind.VIDEO && item.mode == VideoGenerationMode.IMG2VID.folderName ->
            context.getString(R.string.video_gen_mode_img2vid)
        item.kind == SdGeneratedMediaKind.IMAGE && item.mode.equals(SDMode.TXT2IMG.name, ignoreCase = true) ->
            context.getString(R.string.imagegen_mode_txt2img)
        item.kind == SdGeneratedMediaKind.IMAGE && item.mode.equals(SDMode.IMG2IMG.name, ignoreCase = true) ->
            context.getString(R.string.imagegen_mode_img2img)
        item.kind == SdGeneratedMediaKind.IMAGE && item.mode.equals(SDMode.UPSCALE.name, ignoreCase = true) ->
            context.getString(R.string.imagegen_upscale_title)
        else -> item.mode
    }
