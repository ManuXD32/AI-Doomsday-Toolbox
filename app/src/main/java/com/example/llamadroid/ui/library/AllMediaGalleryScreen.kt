package com.example.llamadroid.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.media.FAST_SD_LIST_COMMAND
import com.example.llamadroid.media.MediaGalleryIndex
import com.example.llamadroid.media.MediaGalleryItem
import com.example.llamadroid.media.MediaGalleryLocation
import com.example.llamadroid.media.MediaGalleryLocationFilter
import com.example.llamadroid.media.MediaGallerySource
import com.example.llamadroid.media.MediaGallerySourceFilter
import com.example.llamadroid.media.MediaGalleryType
import com.example.llamadroid.media.MediaGalleryTypeFilter
import com.example.llamadroid.ui.ai.VideoGalleryDetail
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AllMediaGalleryScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val fastSdConnected by SSHService.isConnected.collectAsState()
    val sshConfig by SSHService.config.collectAsState()
    val fastSdEndpoint = remember(sshConfig.host, sshConfig.port) {
        sshConfig.host.trim().takeIf { it.isNotEmpty() }?.let { "$it:${sshConfig.port}" }
    }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaGalleryItem>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<MediaGalleryItem?>(null) }
    var typeFilterName by rememberSaveable { mutableStateOf(MediaGalleryTypeFilter.ALL.name) }
    var sourceFilterName by rememberSaveable { mutableStateOf(MediaGallerySourceFilter.ALL.name) }
    var locationFilterName by rememberSaveable { mutableStateOf(MediaGalleryLocationFilter.ALL.name) }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val typeFilter = remember(typeFilterName) {
        runCatching { MediaGalleryTypeFilter.valueOf(typeFilterName) }.getOrDefault(MediaGalleryTypeFilter.ALL)
    }
    val sourceFilter = remember(sourceFilterName) {
        runCatching { MediaGallerySourceFilter.valueOf(sourceFilterName) }.getOrDefault(MediaGallerySourceFilter.ALL)
    }
    val locationFilter = remember(locationFilterName) {
        runCatching { MediaGalleryLocationFilter.valueOf(locationFilterName) }.getOrDefault(MediaGalleryLocationFilter.ALL)
    }
    val gridMinSize = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) 280.dp else 180.dp

    LaunchedEffect(refreshNonce, fastSdConnected, fastSdEndpoint) {
        val localItems = withContext(Dispatchers.IO) { MediaGalleryIndex.scan(context) }
        // Publish local durable outputs first. A connected FastSD host is queried separately so
        // its SSH round trip cannot block the rest of Library from becoming useful.
        items = localItems
        if (!fastSdConnected) return@LaunchedEffect

        val remoteItems = withContext(Dispatchers.IO) {
            SSHService.executeQuietBounded(
                command = FAST_SD_LIST_COMMAND,
                timeoutMillis = 10_000L,
                maxOutputBytes = 2 * 1024 * 1024
            )?.let { output -> MediaGalleryIndex.parseFastSdListing(output, fastSdEndpoint) }.orEmpty()
        }
        if (isActive) {
            items = withContext(Dispatchers.IO) {
                MediaGalleryIndex.scan(context, remoteFastSdItems = remoteItems)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val observer = MediaGalleryIndex.observe(context) { refreshNonce++ }
        onDispose { observer.close() }
    }

    val filteredItems = remember(items, typeFilter, sourceFilter, locationFilter) {
        MediaGalleryIndex.filter(items, typeFilter, sourceFilter, locationFilter)
    }

    AppScreenScaffold(
        title = stringResource(R.string.media_gallery_title),
        subtitle = stringResource(R.string.media_gallery_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = {
                walkthroughTargets?.recordEvent("media.all_gallery")
                refreshNonce++
            }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = gridMinSize),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                AppSectionCard(modifier = Modifier.walkthroughTarget("media.all_gallery")) {
                Text(
                    text = stringResource(R.string.media_gallery_filter_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                FilterChipRow(
                    labels = MediaGalleryTypeFilter.entries.map { filter ->
                        filter to when (filter) {
                            MediaGalleryTypeFilter.ALL -> stringResource(R.string.media_gallery_type_all)
                            MediaGalleryTypeFilter.IMAGES -> stringResource(R.string.media_gallery_type_images)
                            MediaGalleryTypeFilter.VIDEOS -> stringResource(R.string.media_gallery_type_videos)
                        }
                    },
                    selected = typeFilter,
                    onSelected = { typeFilterName = it.name }
                )
                FilterChipRow(
                    labels = MediaGallerySourceFilter.entries.map { filter ->
                        filter to sourceFilterLabel(filter)
                    },
                    selected = sourceFilter,
                    onSelected = { sourceFilterName = it.name }
                )
                FilterChipRow(
                    labels = MediaGalleryLocationFilter.entries.map { filter ->
                        filter to locationFilterLabel(filter)
                    },
                    selected = locationFilter,
                    onSelected = { locationFilterName = it.name }
                )
                Text(
                    text = stringResource(R.string.media_gallery_count, filteredItems.size, items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { navController.navigate(Screen.FastsdGallery.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.media_gallery_fastsd_link_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.media_gallery_fastsd_link_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                }
            }

            if (filteredItems.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Collections,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.media_gallery_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredItems, key = { it.identity.value }) { item ->
                    MediaGalleryCard(
                        item = item,
                        onOpen = {
                            if (item.remotePath != null) {
                                navController.navigate(Screen.FastsdGallery.route)
                            } else {
                                selectedItem = item
                            }
                        },
                        onShare = {
                            if (item.remotePath != null) {
                                navController.navigate(Screen.FastsdGallery.route)
                            } else {
                                shareMedia(context, item)
                            }
                        }
                    )
                }
            }
        }
    }

    selectedItem?.let { item ->
        val videoMetadata = item.videoMetadata
        if (item.type == MediaGalleryType.VIDEO && videoMetadata != null) {
            VideoGalleryDetail(
                metadata = videoMetadata,
                navController = navController,
                onDismiss = { selectedItem = null },
                onDeleted = { refreshNonce++ }
            )
        } else {
            MediaGalleryDetailDialog(
                item = item,
                onDismiss = { selectedItem = null },
                onOpenExternal = { openMedia(context, item) },
                onShare = { shareMedia(context, item) }
            )
        }
    }
}

@Composable
private fun <T> FilterChipRow(
    labels: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun sourceFilterLabel(filter: MediaGallerySourceFilter): String = when (filter) {
    MediaGallerySourceFilter.ALL -> stringResource(R.string.media_gallery_source_all)
    MediaGallerySourceFilter.SD -> stringResource(R.string.media_gallery_source_sd)
    MediaGallerySourceFilter.FAST_SD -> stringResource(R.string.media_gallery_source_fast_sd)
    MediaGallerySourceFilter.ONNX -> stringResource(R.string.media_gallery_source_onnx)
    MediaGallerySourceFilter.TAMA -> stringResource(R.string.media_gallery_source_tama)
    MediaGallerySourceFilter.WORKFLOW -> stringResource(R.string.media_gallery_source_workflow)
    MediaGallerySourceFilter.PROCESSING -> stringResource(R.string.media_gallery_source_processing)
}

@Composable
private fun locationFilterLabel(filter: MediaGalleryLocationFilter): String = when (filter) {
    MediaGalleryLocationFilter.ALL -> stringResource(R.string.media_gallery_location_all)
    MediaGalleryLocationFilter.LOCAL -> stringResource(R.string.media_gallery_location_local)
    MediaGalleryLocationFilter.DISTRIBUTED -> stringResource(R.string.media_gallery_location_distributed)
    MediaGalleryLocationFilter.UNKNOWN -> stringResource(R.string.media_gallery_location_unknown)
}

@Composable
private fun MediaGalleryCard(
    item: MediaGalleryItem,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, item.identity.value, item.file.lastModified()) {
        value = withContext(Dispatchers.IO) { loadMediaGalleryThumbnail(item) }
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (item.type == MediaGalleryType.VIDEO) Icons.Default.Movie else Icons.Default.Image,
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
                        if (item.type == MediaGalleryType.VIDEO) {
                            stringResource(R.string.media_gallery_type_video)
                        } else {
                            stringResource(R.string.media_gallery_type_image)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.title.ifBlank { item.file.nameWithoutExtension },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.media_gallery_item_meta,
                    sourceLabel(item.source),
                    formatMediaGalleryDate(item.createdAt)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.media_gallery_open), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = onShare,
                    enabled = item.remotePath == null,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                }
            }
        }
    }
}

@Composable
private fun MediaGalleryDetailDialog(
    item: MediaGalleryItem,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit
) {
    com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.media_gallery_details_title),
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
                MediaGalleryPreview(item)
                Text(
                    text = item.title.ifBlank { item.file.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                MediaGalleryDetailLine(
                    stringResource(R.string.media_gallery_source_label),
                    sourceLabel(item.source)
                )
                MediaGalleryDetailLine(
                    stringResource(R.string.media_gallery_location_label),
                    locationLabel(item.location)
                )
                MediaGalleryDetailLine(
                    stringResource(R.string.media_gallery_created_label),
                    formatMediaGalleryDate(item.createdAt)
                )
                if (item.prompt.isNotBlank()) {
                    MediaGalleryDetailLine(stringResource(R.string.media_gallery_prompt_label), item.prompt)
                }
                if (item.type == MediaGalleryType.VIDEO && item.videoMetadata == null) {
                    MediaGalleryDetailLine(
                        stringResource(R.string.media_gallery_generation_settings_label),
                        stringResource(R.string.media_gallery_generation_settings_unavailable)
                    )
                }
                MediaGalleryDetailLine(stringResource(R.string.media_gallery_file_label), item.file.name)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenExternal) { Text(stringResource(R.string.action_open)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShare, enabled = item.remotePath == null) {
                    Text(stringResource(R.string.action_share))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    )
}

@Composable
private fun MediaGalleryPreview(item: MediaGalleryItem) {
    val bitmap by produceState<Bitmap?>(initialValue = null, item.identity.value, item.file.lastModified()) {
        value = withContext(Dispatchers.IO) { loadMediaGalleryThumbnail(item) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 360.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            item.type == MediaGalleryType.VIDEO && item.file.isFile -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    android.widget.VideoView(context).apply {
                        setVideoURI(Uri.fromFile(item.file))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                        }
                    }
                }
            )
            else -> Icon(
                imageVector = if (item.type == MediaGalleryType.VIDEO) Icons.Default.Movie else Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MediaGalleryDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun loadMediaGalleryThumbnail(item: MediaGalleryItem): Bitmap? = runCatching {
    val preview = item.previewFile?.takeIf { it.isFile } ?: item.file.takeIf { it.isFile } ?: return@runCatching null
    when (item.type) {
        MediaGalleryType.IMAGE -> decodeMediaGalleryImage(preview)
        MediaGalleryType.VIDEO -> when {
            preview.extension.equals("webp", ignoreCase = true) -> decodeMediaGalleryImage(preview)
            android.os.Build.VERSION.SDK_INT >= 27 -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(preview.absolutePath)
                    retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768)
                } finally {
                    retriever.release()
                }
            }
            else -> android.media.ThumbnailUtils.createVideoThumbnail(preview.absolutePath,
                android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
        }
    }
}.getOrNull()

private fun decodeMediaGalleryImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    val sampleSize = generateSequence(1) { it * 2 }.first { maxDimension / it <= 768 }
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

@Composable
private fun sourceLabel(source: MediaGallerySource): String = when (source) {
    MediaGallerySource.SD -> stringResource(R.string.media_gallery_source_sd)
    MediaGallerySource.FAST_SD -> stringResource(R.string.media_gallery_source_fast_sd)
    MediaGallerySource.ONNX -> stringResource(R.string.media_gallery_source_onnx)
    MediaGallerySource.TAMA -> stringResource(R.string.media_gallery_source_tama)
    MediaGallerySource.WORKFLOW -> stringResource(R.string.media_gallery_source_workflow)
    MediaGallerySource.IMAGE_PROCESSING,
    MediaGallerySource.VIDEO_PROCESSING -> stringResource(R.string.media_gallery_source_processing)
    MediaGallerySource.UNKNOWN -> stringResource(R.string.media_gallery_source_unknown)
}

@Composable
private fun locationLabel(location: MediaGalleryLocation): String = when (location) {
    MediaGalleryLocation.LOCAL -> stringResource(R.string.media_gallery_location_local)
    MediaGalleryLocation.DISTRIBUTED -> stringResource(R.string.media_gallery_location_distributed)
    MediaGalleryLocation.UNKNOWN -> stringResource(R.string.media_gallery_location_unknown)
}

private fun formatMediaGalleryDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun openMedia(context: Context, item: MediaGalleryItem) {
    if (item.remotePath != null || !item.file.isFile) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.media_gallery_open_with)))
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.media_gallery_open_failed, error.message.orEmpty()),
            Toast.LENGTH_SHORT
        ).show()
    }
}

private fun shareMedia(context: Context, item: MediaGalleryItem) {
    if (item.remotePath != null || !item.file.isFile) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", item.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.onFailure { error ->
        Toast.makeText(
            context,
            context.getString(R.string.media_gallery_open_failed, error.message.orEmpty()),
            Toast.LENGTH_SHORT
        ).show()
    }
}
