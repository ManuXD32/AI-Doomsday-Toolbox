package com.example.llamadroid.ui.ai.llama.chat.cards

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.map.ChatMapResolver
import com.example.llamadroid.chat.cards.map.ChatMapServices
import com.example.llamadroid.chat.cards.map.GeoBounds
import com.example.llamadroid.chat.cards.map.MapTileMath
import com.example.llamadroid.chat.cards.map.MapViewport
import com.example.llamadroid.chat.cards.map.ResolvedPlace
import com.example.llamadroid.chat.cards.map.TileKey
import com.example.llamadroid.ui.components.AppChromeDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal const val MAP_PREVIEW_W = 768
internal const val MAP_PREVIEW_H = 480 // 16:10
// Fit insets in preview pixels (~2 px per dp), from the real marker footprint: pin radius 28 + margin at the sides/top
// (the Expand pill may overlap a pin), bottom = pin radius + label (~32) + gap + attribution strip (~40).
private const val INSET_SIDE = 44
private const val INSET_TOP = 44
private const val INSET_BOTTOM = 100

/** Viewport centred on [bounds] inside the inset-reduced area of the preview, at the largest zoom that fits. */
private fun previewViewport(bounds: GeoBounds): MapViewport {
    val availH = MAP_PREVIEW_H - INSET_TOP - INSET_BOTTOM
    val zoom = MapTileMath.fitZoom(bounds, MAP_PREVIEW_W, availH + 2 * INSET_SIDE, INSET_SIDE, 16)
    val c = bounds.center
    return MapViewport(
        zoom = zoom,
        originX = MapTileMath.worldX(c.lng, zoom) - MAP_PREVIEW_W / 2.0,
        originY = MapTileMath.worldY(c.lat, zoom) - (INSET_TOP + availH / 2.0),
        width = MAP_PREVIEW_W,
        height = MAP_PREVIEW_H,
    )
}

private enum class TileStatus { LOADING, READY, OFFLINE }

private data class Resolution(val places: List<ResolvedPlace>, val done: Boolean)

/**
 * Map card: static OSM tile preview fitted to the pins (offline -> pin list only), a pin list underneath,
 * and a full-screen Leaflet dialog on tap. Places are geocoded by name with Photon; the model's lat/lng is the fallback.
 */
@Composable
internal fun ChatMapCardContent(card: ChatCard.MapCard, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resolution by produceState(Resolution(ChatMapResolver.modelOnly(card), false), card) {
        val geocoder = ChatMapServices.geocoder(context)
        val resolved = withContext(Dispatchers.IO) { ChatMapResolver.resolve(card, geocoder) }
        value = Resolution(resolved, true)
    }
    val places = resolution.places
    val located = remember(places) { places.mapNotNull { it.point } }
    val viewport = remember(located, resolution.done) {
        if (!resolution.done) null else GeoBounds.of(located)?.let(::previewViewport)
    }
    val tiles = remember(viewport) { mutableStateMapOf<TileKey, ImageBitmap>() }
    var status by remember(viewport) { mutableStateOf(TileStatus.LOADING) }
    LaunchedEffect(viewport) {
        val vp = viewport ?: return@LaunchedEffect
        loadTiles(context, vp, tiles)
        status = if (tiles.isEmpty()) TileStatus.OFFLINE else TileStatus.READY
    }
    var showDialog by remember { mutableStateOf(false) }
    val title = card.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_card_map_default_title)

    val icons = remember(card) { card.places.map { cardEmoji(it.icon) } }
    CardShell(title = title, modifier = modifier) {
        when {
            !resolution.done || (viewport != null && status == TileStatus.LOADING) ->
                Box(
                    Modifier.fillMaxWidth().aspectRatio(MAP_PREVIEW_W.toFloat() / MAP_PREVIEW_H)
                        .clip(AppChromeDefaults.CompactShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.chat_card_map_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            viewport != null && status == TileStatus.READY ->
                ChatMapPreview(
                    viewport = viewport,
                    tiles = tiles,
                    places = places,
                    route = card.route,
                    onOpen = { showDialog = true },
                    icons = icons,
                )
            viewport != null ->
                Text(stringResource(R.string.chat_card_map_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Place list: hairline-separated rows (badge, name, note), never boxes inside the card.
        Column(Modifier.fillMaxWidth()) {
            places.forEachIndexed { i, place ->
                if (i > 0) CardHairline()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardEmojiBadge(icons.getOrNull(place.index) ?: (place.index + 1).toString())
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        place.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (resolution.done && place.point == null) {
                            Text(stringResource(R.string.chat_card_map_unlocated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (card.route) {
            Text(stringResource(R.string.chat_card_map_route_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDialog) {
        ChatMapLeafletDialog(title = title, places = places, route = card.route, onDismiss = { showDialog = false })
    }
}

private suspend fun loadTiles(context: android.content.Context, vp: MapViewport, out: SnapshotStateMap<TileKey, ImageBitmap>) {
    val cache = ChatMapServices.tiles(context)
    coroutineScope {
        MapTileMath.visibleTiles(vp).map { slot ->
            async(Dispatchers.IO) {
                val bytes = cache.get(slot.key) ?: return@async
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@async
                withContext(Dispatchers.Main) { out[slot.key] = bmp.asImageBitmap() }
            }
        }.awaitAll()
    }
}
