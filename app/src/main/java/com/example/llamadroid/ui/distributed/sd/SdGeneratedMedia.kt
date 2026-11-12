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
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SdDistributedRuntimeConfig
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
): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    lines += context.getString(R.string.sd_dist_gallery_file_label) to item.file.name
    lines += context.getString(R.string.sd_dist_gallery_mode_label) to sdGeneratedModeLabel(context, item)
    lines += context.getString(R.string.sd_dist_gallery_created_label) to formatSdGalleryDate(item.createdAt)
    readSdGeneratedMediaResolution(item)?.let { resolution ->
        lines += context.getString(R.string.sd_dist_gallery_resolution_label) to resolution
    }

    item.imageMetadata?.let { metadata ->
        metadata.generationDurationMs?.let {
            lines += context.getString(R.string.sd_dist_gallery_total_time_label) to formatSdGenerationDuration(it)
        }
        addIfNotBlank(lines, context.getString(R.string.imagegen_prompt_label), metadata.prompt)
        addIfNotBlank(lines, context.getString(R.string.imagegen_negative_prompt_label), metadata.negativePrompt)
        addIfNotBlank(lines, context.getString(R.string.imagegen_sd_model), metadata.modelName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_vae), metadata.vaeName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_lora), metadata.loraName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_controlnet), metadata.controlNetName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_tae), metadata.taeName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_clip_l), metadata.clipLName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_clip_g), metadata.clipGName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_t5xxl), metadata.t5xxlName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_llm), metadata.llmName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_llm_vision), metadata.llmVisionName)
        addIfNotBlank(lines, context.getString(R.string.imagegen_component_photomaker), metadata.photoMakerName)
        if (metadata.width > 0) lines += context.getString(R.string.imagegen_width_label) to metadata.width.toString()
        if (metadata.height > 0) lines += context.getString(R.string.imagegen_height_label) to metadata.height.toString()
        lines += context.getString(R.string.imagegen_steps_label) to metadata.steps.toString()
        if (metadata.cfgScale > 0f) lines += context.getString(R.string.imagegen_cfg_label) to metadata.cfgScale.toString()
        lines += context.getString(R.string.imagegen_seed_label) to metadata.seed.toString()
        lines += context.getString(R.string.imagegen_sampler_label) to metadata.samplingMethod.cliName
        metadata.scheduler?.let { lines += context.getString(R.string.imagegen_scheduler) to it.cliName }
        metadata.flowShift?.let { lines += context.getString(R.string.video_gen_flow_shift_label) to it.toString() }
        metadata.cacheMode?.let { lines += context.getString(R.string.gen_cache_mode_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_option_label), metadata.cacheOption)
        metadata.scmPolicy?.let { lines += context.getString(R.string.gen_cache_scm_policy_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_scm_mask_label), metadata.scmMask)
        addIfNotBlank(lines, context.getString(R.string.imagegen_upscale_repeats), metadata.upscaleRepeats.takeIf { it > 0 }?.toString())
        addIfNotBlank(lines, context.getString(R.string.video_gen_input_image_title), metadata.initImagePath?.let { File(it).name })
        if (metadata.modeEnum == SDMode.IMG2IMG) {
            lines += context.getString(R.string.imagegen_strength_label) to metadata.strength.toString()
        }
        if (metadata.loraStrength != 0f) {
            lines += context.getString(R.string.imagegen_lora_strength_label) to metadata.loraStrength.toString()
        }
        if (metadata.controlStrength != 0f) {
            lines += context.getString(R.string.imagegen_control_strength_label) to metadata.controlStrength.toString()
        }
        lines += context.getString(R.string.imagegen_threads) to metadata.threads.toString()
        lines += context.getString(R.string.video_gen_vae_tiling_label) to enabledLabel(context, metadata.vaeTiling)
        lines += context.getString(R.string.video_gen_diffusion_fa_label) to enabledLabel(context, metadata.diffusionFa)
        lines += context.getString(R.string.video_gen_mmap_label) to enabledLabel(context, metadata.mmap)
        addIfNotBlank(lines, context.getString(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
        addIfNotBlank(lines, context.getString(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
        addIfNotBlank(lines, context.getString(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
        addDistributedRuntimeLines(context, lines, metadata.distributedRuntime)
        addIfNotBlank(lines, context.getString(R.string.sd_dist_custom_flags), metadata.customFlags)
    }

    item.videoMetadata?.let { metadata ->
        metadata.generationDurationMs?.let {
            lines += context.getString(R.string.sd_dist_gallery_total_time_label) to formatSdGenerationDuration(it)
        }
        addIfNotBlank(lines, context.getString(R.string.video_gen_prompt_label), metadata.prompt)
        addIfNotBlank(lines, context.getString(R.string.video_gen_negative_prompt_label), metadata.negativePrompt)
        addIfNotBlank(lines, context.getString(R.string.video_gen_model_label), metadata.diffusionModelName)
        lines += context.getString(R.string.video_gen_frames_label) to metadata.videoFrames.toString()
        lines += context.getString(R.string.video_gen_fps_label) to metadata.fps.toString()
        lines += context.getString(R.string.video_gen_width_label) to metadata.width.toString()
        lines += context.getString(R.string.video_gen_height_label) to metadata.height.toString()
        lines += context.getString(R.string.video_gen_steps_label) to metadata.steps.toString()
        lines += context.getString(R.string.video_gen_cfg_scale_label) to metadata.cfgScale.toString()
        metadata.flowShift?.let { lines += context.getString(R.string.video_gen_flow_shift_label) to it.toString() }
        lines += context.getString(R.string.video_gen_sampler_label) to metadata.samplingMethod.cliName
        metadata.scheduler?.let { lines += context.getString(R.string.imagegen_scheduler) to it.cliName }
        metadata.cacheMode?.let { lines += context.getString(R.string.gen_cache_mode_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_option_label), metadata.cacheOption)
        metadata.scmPolicy?.let { lines += context.getString(R.string.gen_cache_scm_policy_label) to it.cliName }
        addIfNotBlank(lines, context.getString(R.string.gen_cache_scm_mask_label), metadata.scmMask)
        lines += context.getString(R.string.video_gen_threads_label) to metadata.threads.toString()
        lines += context.getString(R.string.video_gen_vae_toggle_label) to if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else context.getString(R.string.video_gen_disabled)
        lines += context.getString(R.string.video_gen_t5_toggle_label) to if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else context.getString(R.string.video_gen_disabled)
        lines += context.getString(R.string.video_gen_vae_tiling_label) to enabledLabel(context, metadata.vaeTiling)
        addIfNotBlank(lines, context.getString(R.string.video_gen_vae_tile_size_label), metadata.vaeTileSize)
        lines += context.getString(R.string.video_gen_diffusion_fa_label) to enabledLabel(context, metadata.diffusionFa)
        lines += context.getString(R.string.video_gen_mmap_label) to enabledLabel(context, metadata.mmap)
        addIfNotBlank(lines, context.getString(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
        addIfNotBlank(lines, context.getString(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
        addIfNotBlank(lines, context.getString(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
        addIfNotBlank(lines, context.getString(R.string.video_gen_input_image_title), metadata.initImagePath?.let { File(it).name })
        addDistributedRuntimeLines(context, lines, metadata.distributedRuntime)
    }

    return lines.distinctBy { it.first to it.second }
}

internal fun addDistributedRuntimeLines(
    context: Context,
    lines: MutableList<Pair<String, String>>,
    runtime: SdDistributedRuntimeConfig
) {
    if (!runtime.enabled) return
    lines += context.getString(R.string.sd_dist_enabled) to context.getString(R.string.sd_dist_live)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_rpc_servers), runtime.rpcServers)
    lines += context.getString(R.string.sd_dist_placement_mode) to runtime.placementMode.name
    addIfNotBlank(lines, context.getString(R.string.sd_dist_backend_spec), runtime.backendSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_params_backend_spec), runtime.paramsBackendSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_max_vram), runtime.maxVramSpec)
    addIfNotBlank(lines, context.getString(R.string.sd_dist_custom_flags), runtime.customFlags)
}

internal fun addIfNotBlank(
    lines: MutableList<Pair<String, String>>,
    label: String,
    value: String?
) {
    if (!value.isNullOrBlank()) {
        lines += label to value
    }
}

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

internal fun enabledLabel(context: Context, enabled: Boolean): String =
    context.getString(if (enabled) R.string.video_gen_enabled else R.string.video_gen_disabled)

internal fun formatSdGenerationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
