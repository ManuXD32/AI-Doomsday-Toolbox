package com.example.llamadroid.tama.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaArtworkKind
import com.example.llamadroid.tama.data.TamaArtworkStatus
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.game.TamaDailyDreamManager
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.io.File
import java.text.DateFormat
import kotlinx.coroutines.launch

private sealed interface TamaGalleryEntry {
    val key: String
    val createdAt: Long
}

private data class TamaGallerySingleEntry(
    val artwork: TamaArtworkEntity
) : TamaGalleryEntry {
    override val key: String = artwork.id
    override val createdAt: Long = artwork.createdAt
}

private data class TamaGalleryAlbumEntry(
    val albumId: String,
    val artworks: List<TamaArtworkEntity>,
    val cover: TamaArtworkEntity?,
    val story: String?,
    val albumDate: String?
) : TamaGalleryEntry {
    override val key: String = albumId
    override val createdAt: Long = artworks.maxOfOrNull { it.createdAt } ?: 0L
}

private data class TamaGalleryDreamSlide(
    val title: String,
    val body: String,
    val artwork: TamaArtworkEntity? = null
)

private data class TamaGalleryDeleteTarget(
    val title: String,
    val artworks: List<TamaArtworkEntity>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaGalleryScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    pet: TamaPet
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val artworks by gameEngine.observeArtworks(pet.id).collectAsState(initial = emptyList())
    val galleryEntries = remember(artworks) { buildGalleryEntries(artworks) }
    var pendingDelete by remember { mutableStateOf<TamaGalleryDeleteTarget?>(null) }
    var previewArtwork by remember { mutableStateOf<TamaArtworkEntity?>(null) }
    var previewAlbum by remember { mutableStateOf<TamaGalleryAlbumEntry?>(null) }

    fun shareArtwork(artwork: TamaArtworkEntity) {
        val imageFile = artwork.filePath?.let(::File)
        if (imageFile == null || !imageFile.exists()) {
            Toast.makeText(context, resources.getString(R.string.tama_gallery_share_missing), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.action_share)))
        }.onFailure { error ->
            Toast.makeText(
                context,
                resources.getString(R.string.tama_gallery_share_failed, error.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },
                title = { Text(stringResource(R.string.tama_gallery_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.walkthroughTarget("back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        if (galleryEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.tama_gallery_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(galleryEntries, key = { it.key }) { entry ->
                    when (entry) {
                        is TamaGallerySingleEntry -> {
                            TamaGalleryCard(
                                artwork = entry.artwork,
                                onShare = { shareArtwork(entry.artwork) },
                                onDelete = {
                                    pendingDelete = TamaGalleryDeleteTarget(
                                        title = entry.artwork.title,
                                        artworks = listOf(entry.artwork)
                                    )
                                },
                                onOpen = { previewArtwork = entry.artwork }
                            )
                        }
                        is TamaGalleryAlbumEntry -> {
                            TamaGalleryAlbumCard(
                                entry = entry,
                                onShare = { entry.cover?.let(::shareArtwork) },
                                onDelete = {
                                    pendingDelete = TamaGalleryDeleteTarget(
                                        title = dreamAlbumStoryText(entry.story)?.takeIf(String::isNotBlank)
                                            ?: resources.getString(R.string.tama_gallery_kind_daily_dream),
                                        artworks = entry.artworks
                                    )
                                },
                                onOpen = { previewAlbum = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    previewArtwork?.let { artwork ->
        SingleArtworkPreviewDialog(
            artwork = artwork,
            onDismiss = { previewArtwork = null }
        )
    }

    previewAlbum?.let { album ->
        DreamAlbumPreviewDialog(
            entry = album,
            onDismiss = { previewAlbum = null }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.tama_gallery_delete_title)) },
            text = { Text(stringResource(R.string.tama_gallery_delete_desc, target.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            target.artworks.forEach { artwork ->
                                gameEngine.deleteArtwork(artwork)
                            }
                            pendingDelete = null
                        }
                    }
                ) {
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
}

private fun buildGalleryEntries(artworks: List<TamaArtworkEntity>): List<TamaGalleryEntry> {
    val albumEntries = artworks
        .filter { it.kind == TamaArtworkKind.DAILY_DREAM.name && !it.albumId.isNullOrBlank() }
        .groupBy { it.albumId!! }
        .values
        .map { albumItems ->
            val sorted = albumItems.sortedBy { it.albumIndex }
            TamaGalleryAlbumEntry(
                albumId = sorted.first().albumId.orEmpty(),
                artworks = sorted,
                cover = sorted.firstOrNull { it.filePath?.let(::File)?.exists() == true } ?: sorted.firstOrNull(),
                story = sorted.firstNotNullOfOrNull { it.albumSummary?.takeIf(String::isNotBlank) },
                albumDate = sorted.firstNotNullOfOrNull { it.albumDate?.takeIf(String::isNotBlank) }
            )
        }
    val albumIds = albumEntries.map { it.albumId }.toSet()
    val singleEntries = artworks
        .filterNot { it.kind == TamaArtworkKind.DAILY_DREAM.name && it.albumId in albumIds }
        .map(::TamaGallerySingleEntry)
    return (albumEntries + singleEntries).sortedByDescending { it.createdAt }
}

@Composable
private fun TamaGalleryAlbumCard(
    entry: TamaGalleryAlbumEntry,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    val coverFile = remember(entry.cover?.filePath) { entry.cover?.filePath?.let(::File) }
    val createdAt = remember(entry.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(entry.createdAt)
    }
    val status = remember(entry.artworks) { aggregateAlbumStatus(entry.artworks) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (coverFile?.exists() == true) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = null,
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            galleryStatusText(status),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.tama_gallery_kind_daily_dream),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    ElevatedAssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.tama_gallery_album_count, entry.artworks.size + 2)) }
                    )
                    Text(
                        galleryStatusText(status),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = galleryStatusColor(status)
                    )
                    Text(
                        createdAt,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    dreamAlbumStoryText(entry.story)?.takeIf(String::isNotBlank)?.let { story ->
                        Text(
                            story,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onShare,
                    enabled = coverFile?.exists() == true
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_share))
                }
                FilledTonalButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun TamaGalleryCard(
    artwork: TamaArtworkEntity,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    val createdAt = remember(artwork.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(artwork.createdAt)
    }
    val imageFile = remember(artwork.filePath) { artwork.filePath?.let(::File) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (imageFile?.exists() == true) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = artwork.title,
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            galleryStatusText(artwork.status),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        artwork.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    ElevatedAssistChip(
                        onClick = {},
                        label = { Text(galleryKindText(artwork.kind)) }
                    )
                    Text(
                        galleryStatusText(artwork.status),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = galleryStatusColor(artwork.status)
                    )
                    Text(
                        createdAt,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${artwork.modelLabel} • ${artwork.width}x${artwork.height}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    artwork.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                        Text(
                            error,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onShare,
                    enabled = imageFile?.exists() == true
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_share))
                }
                FilledTonalButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun SingleArtworkPreviewDialog(
    artwork: TamaArtworkEntity,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    artwork.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val imageFile = remember(artwork.filePath) { artwork.filePath?.let(::File) }
                if (imageFile?.exists() == true) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = artwork.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(stringResource(R.string.tama_gallery_share_missing))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun DreamAlbumPreviewDialog(
    entry: TamaGalleryAlbumEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val slides = remember(entry.albumId, entry.story, entry.artworks) {
        buildGalleryDreamSlides(context, entry.story, entry.artworks)
    }
    var selectedIndex by rememberSaveable(entry.albumId) { mutableStateOf(0) }
    val selectedSlide = slides.getOrNull(selectedIndex)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.tama_gallery_kind_daily_dream),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.tama_art_reveal_slide_counter, selectedIndex + 1, slides.size),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        selectedSlide?.let { slide ->
                            Text(
                                slide.title,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                slide.body,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            slide.artwork?.filePath?.let(::File)?.takeIf(File::exists)?.let { imageFile ->
                                AsyncImage(
                                    model = imageFile,
                                    contentDescription = slide.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(360.dp)
                                        .clip(RoundedCornerShape(18.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedIndex = (selectedIndex - 1).coerceAtLeast(0) },
                        enabled = selectedIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_previous))
                    }
                    OutlinedButton(
                        onClick = { selectedIndex = (selectedIndex + 1).coerceAtMost(slides.lastIndex) },
                        enabled = selectedIndex < slides.lastIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_next))
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

private fun buildGalleryDreamSlides(
    context: Context,
    albumSummaryRaw: String?,
    artworks: List<TamaArtworkEntity>
): List<TamaGalleryDreamSlide> {
    val summary = TamaDailyDreamManager.decodeAlbumSummary(albumSummaryRaw)
    val orderedArtworks = artworks.sortedBy { it.albumIndex }
    val introBody = summary.story.ifBlank {
        context.getString(R.string.tama_art_reveal_story_fallback)
    }
    val closingBody = summary.closing.ifBlank { introBody }
    return buildList {
        add(
            TamaGalleryDreamSlide(
                title = context.getString(R.string.tama_art_reveal_intro_title),
                body = introBody
            )
        )
        orderedArtworks.forEachIndexed { index, artwork ->
            add(
                TamaGalleryDreamSlide(
                    title = context.getString(R.string.tama_art_reveal_moment_title, index + 1),
                    body = artwork.title.ifBlank {
                        context.getString(R.string.tama_art_reveal_story_fallback)
                    },
                    artwork = artwork
                )
            )
        }
        add(
            TamaGalleryDreamSlide(
                title = context.getString(R.string.tama_art_reveal_closing_title),
                body = closingBody
            )
        )
    }
}

private fun dreamAlbumStoryText(raw: String?): String? {
    return TamaDailyDreamManager.decodeAlbumSummary(raw).story.ifBlank { raw?.trim().orEmpty() }.ifBlank { null }
}

private fun aggregateAlbumStatus(artworks: List<TamaArtworkEntity>): String {
    return when {
        artworks.any { it.status == TamaArtworkStatus.FAILED.name } -> TamaArtworkStatus.FAILED.name
        artworks.all { it.status == TamaArtworkStatus.COMPLETED.name } -> TamaArtworkStatus.COMPLETED.name
        artworks.any { it.status == TamaArtworkStatus.GENERATING.name } -> TamaArtworkStatus.GENERATING.name
        else -> TamaArtworkStatus.QUEUED.name
    }
}

@Composable
private fun galleryKindText(kind: String): String {
    return when (kind) {
        TamaArtworkKind.DREAM.name -> stringResource(R.string.tama_gallery_kind_dream)
        TamaArtworkKind.PAINTING.name -> stringResource(R.string.tama_gallery_kind_painting)
        TamaArtworkKind.DAILY_DREAM.name -> stringResource(R.string.tama_gallery_kind_daily_dream)
        else -> kind
    }
}

@Composable
private fun galleryStatusText(status: String): String {
    return when (status) {
        TamaArtworkStatus.QUEUED.name -> stringResource(R.string.tama_gallery_status_queued)
        TamaArtworkStatus.GENERATING.name -> stringResource(R.string.tama_gallery_status_generating)
        TamaArtworkStatus.COMPLETED.name -> stringResource(R.string.tama_gallery_status_completed)
        TamaArtworkStatus.FAILED.name -> stringResource(R.string.tama_gallery_status_failed)
        else -> status
    }
}

@Composable
private fun galleryStatusColor(status: String): Color {
    return when (status) {
        TamaArtworkStatus.COMPLETED.name -> Color(0xFF2E7D32)
        TamaArtworkStatus.FAILED.name -> MaterialTheme.colorScheme.error
        TamaArtworkStatus.GENERATING.name -> Color(0xFF1565C0)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
