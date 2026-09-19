package com.example.llamadroid.tama.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.TamaAmbientNpcCatalog
import com.example.llamadroid.tama.data.TamaAmbientNpcState
import com.example.llamadroid.tama.data.TamaLocation
import com.example.llamadroid.tama.data.localizedDescription
import com.example.llamadroid.tama.data.localizedName
import kotlin.math.abs
import kotlin.math.ceil

private const val UNKNOWN_LOCATION_ICON_ASSET = "tama/map/unknown.png"
private const val CLASSIC_MAP_GRID_SIZE = 5
private val CLASSIC_MAP_MIN_WIDTH = 280.dp
const val CLASSIC_MAP_SIMULATION_TILE_TAG = "tama_classic_map_development_tile"

/**
 * The authored, fixed town used by the classic Tama experience.
 *
 * The list intentionally stays deterministic. The living world is a separate
 * opt-in route and must not replace this map or silently reopen itself from a
 * persisted world snapshot.
 */
internal fun classicMapLocations(context: Context): List<TamaLocation> {
    val coreLocations = listOf(
        Triple(0, 0, LocationType.HOME),
        Triple(1, 0, LocationType.SHOP),
        Triple(2, 0, LocationType.PARK),
        Triple(3, 0, LocationType.HOSPITAL),
        Triple(4, 0, LocationType.ARCADE),
        Triple(0, 1, LocationType.ALCHEMIST),
        Triple(1, 1, LocationType.SCHOOL),
        Triple(2, 1, LocationType.WORKPLACE),
        Triple(3, 1, LocationType.FARM),
        Triple(4, 1, LocationType.BOXING_RING),
        Triple(0, 2, LocationType.DUNGEON),
        Triple(2, 2, LocationType.ADVENTURE_GATE),
        Triple(4, 2, LocationType.DUNGEON)
    )
    return coreLocations.map { (x, y, type) ->
        TamaLocation(
            id = "fixed_${x}_${y}",
            name = type.localizedName(context),
            type = type,
            description = type.localizedDescription(context),
            cityId = "hometown",
            x = x,
            y = y,
            isDiscovered = type == LocationType.HOME
        )
    }
}

/**
 * Keeps the old five-by-five map bounded inside the square Tama viewport.
 * The map body owns the vertical scroll; its fixed-width grid gets a separate
 * horizontal scroll on very narrow/large-font windows so every tile keeps a
 * usable touch target. The legend also scrolls horizontally for long labels.
 */
@Composable
fun TamaMapView(
    cityName: String,
    locations: List<TamaLocation>,
    currentLocation: TamaLocation?,
    discoveredLocationIds: Set<String>,
    onLocationClick: (TamaLocation) -> Unit,
    onOpenSimulation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TamaLight)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = cityName,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = TamaDark,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Measure the viewport before entering the horizontal scroller. Its
        // child is intentionally wider on compact windows, but the grid rows
        // still need a finite width for weighted tiles to receive space.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val mapWidth = maxOf(maxWidth, CLASSIC_MAP_MIN_WIDTH)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.width(mapWidth),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (y in 0 until CLASSIC_MAP_GRID_SIZE) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (x in 0 until CLASSIC_MAP_GRID_SIZE) {
                                if (x == 2 && y == 3) {
                                    DevelopmentWorldTile(
                                        onClick = onOpenSimulation,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    val location = locations.firstOrNull { it.x == x && it.y == y }
                                    val isDiscovered = location != null && (
                                        location.type == LocationType.HOME ||
                                            location.isDiscovered ||
                                            discoveredLocationIds.contains(location.id)
                                        )
                                    LocationTile(
                                        location = location,
                                        isCurrentLocation = location?.id == currentLocation?.id,
                                        isDiscovered = isDiscovered,
                                        onClick = { location?.let(onLocationClick) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegendItem(LocationType.HOME.mapIconAssetPath, stringResource(R.string.tama_location_home))
            LegendItem(LocationType.SHOP.mapIconAssetPath, stringResource(R.string.tama_location_shop))
            LegendItem(LocationType.ARCADE.mapIconAssetPath, stringResource(R.string.tama_location_arcade))
            LegendItem(LocationType.PARK.mapIconAssetPath, stringResource(R.string.tama_location_park))
            LegendItem(UNKNOWN_LOCATION_ICON_ASSET, stringResource(R.string.tama_location_unknown))
            DevelopmentLegendItem()
        }
    }
}

@Composable
private fun DevelopmentWorldTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.tama_classic_map_simulated_world_title)
    Surface(
        modifier = Modifier
            .then(modifier)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag(CLASSIC_MAP_SIMULATION_TILE_TAG)
            .semantics { contentDescription = title },
        shape = RoundedCornerShape(6.dp),
        color = TamaDark,
        contentColor = TamaLight,
        border = BorderStroke(1.dp, TamaLight.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TamaUiIcon("🗺", fontSize = 22.sp)
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                color = TamaAccent,
                contentColor = TamaDark,
                shape = RoundedCornerShape(3.dp)
            ) {
                Text(
                    text = stringResource(R.string.tama_classic_map_development_badge),
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DevelopmentLegendItem() {
    Column(
        modifier = Modifier
            .width(220.dp)
            .heightIn(min = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.tama_classic_map_development_badge),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = TamaAccent,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.tama_classic_map_simulated_world_title),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TamaAccent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.tama_classic_map_simulated_world_description),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = TamaAccent,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LocationTile(
    location: TamaLocation?,
    isCurrentLocation: Boolean,
    isDiscovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isCurrentLocation -> Color(0xFF8BC34A)
        isDiscovered -> TamaLight
        location != null -> Color(0xFFC0C0C0)
        else -> TamaBackground
    }
    val borderColor = if (isCurrentLocation) TamaDark else Color.Transparent
    val tileModifier = modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(4.dp))
        .background(bgColor)
        .border(2.dp, borderColor, RoundedCornerShape(4.dp))
        .then(if (location != null) Modifier.clickable(onClick = onClick) else Modifier)
    Box(
        modifier = tileModifier.semantics {
            location?.let { contentDescription = it.name }
        },
        contentAlignment = Alignment.Center
    ) {
        if (location != null) {
            TamaMapIcon(
                assetPath = if (isDiscovered) location.type.mapIconAssetPath else UNKNOWN_LOCATION_ICON_ASSET,
                size = 44.dp
            )
        }
    }
}

@Composable
fun LegendItem(assetPath: String, label: String) {
    Row(
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TamaMapIcon(assetPath = assetPath, size = 40.dp)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TamaAccent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

enum class TamaClassicLocationAction {
    SHOP,
    SCHOOL,
    WORK,
    TRAIN,
    ARCADE,
    FARM,
    DUNGEON,
    QUESTS,
    CHANGE,
    HEAL,
    ADVENTURE_GATE
}

private fun LocationType.classicAction(): TamaClassicLocationAction? = when (this) {
    LocationType.SHOP -> TamaClassicLocationAction.SHOP
    LocationType.SCHOOL -> TamaClassicLocationAction.SCHOOL
    LocationType.WORKPLACE -> TamaClassicLocationAction.WORK
    LocationType.BOXING_RING -> TamaClassicLocationAction.TRAIN
    LocationType.ARCADE -> TamaClassicLocationAction.ARCADE
    LocationType.FARM -> TamaClassicLocationAction.FARM
    LocationType.DUNGEON -> TamaClassicLocationAction.DUNGEON
    LocationType.PARK -> TamaClassicLocationAction.QUESTS
    LocationType.ALCHEMIST -> TamaClassicLocationAction.CHANGE
    LocationType.HOSPITAL -> TamaClassicLocationAction.HEAL
    LocationType.ADVENTURE_GATE -> TamaClassicLocationAction.ADVENTURE_GATE
    LocationType.HOME -> null
}

internal fun classicTravelEnergyCost(
    currentLocation: TamaLocation?,
    destination: TamaLocation
): Int {
    if (destination.type == LocationType.HOME) return 0
    val distance = currentLocation?.let {
        abs(it.x - destination.x) + abs(it.y - destination.y)
    } ?: return 3
    return ceil((distance.coerceAtLeast(1) * 3f) / 2f).toInt().coerceAtLeast(2)
}

/**
 * Classic location details retain the old direct destination actions. The
 * dialog body is intentionally bounded by TamaPopupDialog's scroll owner.
 */
@Composable
fun LocationDetailsDialog(
    location: TamaLocation,
    isCurrentLocation: Boolean,
    isDiscovered: Boolean = true,
    petEnergy: Int,
    travelCost: Int,
    onTravel: () -> Unit,
    onAction: (TamaClassicLocationAction) -> Unit = {},
    onArcade: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ambientNpc = remember(location.type) { TamaAmbientNpcCatalog.forLocation(location.type) }
    TamaPopupDialog(
        title = if (isDiscovered) location.type.localizedName(context) else {
            stringResource(R.string.tama_unknown_place)
        },
        backgroundAsset = when (location.type) {
            LocationType.HOME -> "tama/backgrounds/bedroom.png"
            LocationType.SHOP -> "tama/backgrounds/shop.png"
            LocationType.SCHOOL -> "tama/backgrounds/classroom.png"
            LocationType.WORKPLACE -> "tama/backgrounds/workplace.png"
            LocationType.BOXING_RING -> "tama/backgrounds/boxing_ring.png"
            LocationType.PARK -> "tama/backgrounds/park.png"
            LocationType.HOSPITAL -> "tama/backgrounds/hospital.png"
            LocationType.ARCADE -> "tama/backgrounds/arcade_location.png"
            LocationType.ALCHEMIST -> "tama/backgrounds/alchemist.png"
            LocationType.FARM -> "tama/backgrounds/farm.png"
            LocationType.DUNGEON -> "tama/backgrounds/dungeon.png"
            LocationType.ADVENTURE_GATE -> "tama/backgrounds/adventure_gate.png"
        },
        compact = true,
        onDismissRequest = onDismiss,
        bodyContent = {
            ambientNpc?.let { npc ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = TamaLight.copy(alpha = 0.98f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, TamaDark.copy(alpha = 0.18f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = "file:///android_asset/${npc.assetPath}",
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = TamaAmbientNpcCatalog.resolveName(LocalContext.current, npc.id),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TamaDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = TamaAmbientNpcCatalog.resolveLine(
                                    LocalContext.current,
                                    TamaAmbientNpcState(npc.id, 0)
                                ),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TamaDark,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isDiscovered) {
                Text(
                    text = location.type.localizedDescription(context),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TamaDark
                )
            } else {
                Text(
                    text = stringResource(R.string.tama_unknown_warning),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFFD32F2F)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (isCurrentLocation) {
                Text(
                    text = stringResource(R.string.tama_you_are_here),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF2E7D32)
                )
                if (location.type == LocationType.ARCADE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tama_arcade_location_desc),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TamaMutedText
                    )
                }
                if (location.type == LocationType.ADVENTURE_GATE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.adventure_gate_location_hint),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TamaMutedText
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.tama_travel_cost, travelCost),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (petEnergy >= travelCost) TamaAccent else Color.Red
                )
                if (petEnergy < travelCost) {
                    Text(
                        text = stringResource(R.string.tama_not_enough_energy),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.Red
                    )
                }
            }
        },
        footerContent = {
            if (!isCurrentLocation) {
                androidx.compose.material3.TextButton(
                    onClick = onTravel,
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = petEnergy >= travelCost
                ) {
                    Text(
                        if (isDiscovered) stringResource(R.string.tama_btn_travel)
                        else stringResource(R.string.tama_btn_explore)
                    )
                }
            } else {
                location.type.classicAction()?.let { action ->
                    androidx.compose.material3.TextButton(
                        onClick = {
                            if (action == TamaClassicLocationAction.ARCADE) onArcade() else onAction(action)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(action.labelRes()))
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun TamaClassicLocationAction.labelRes(): Int = when (this) {
    TamaClassicLocationAction.SHOP -> R.string.tama_classic_map_open_shop
    TamaClassicLocationAction.SCHOOL -> R.string.tama_btn_study
    TamaClassicLocationAction.WORK -> R.string.tama_btn_work
    TamaClassicLocationAction.TRAIN -> R.string.tama_btn_train
    TamaClassicLocationAction.ARCADE -> R.string.tama_btn_arcade
    TamaClassicLocationAction.FARM -> R.string.tama_classic_map_open_farm
    TamaClassicLocationAction.DUNGEON -> R.string.tama_classic_map_open_dungeon
    TamaClassicLocationAction.QUESTS -> R.string.tama_btn_quests
    TamaClassicLocationAction.CHANGE -> R.string.tama_btn_change
    TamaClassicLocationAction.HEAL -> R.string.tama_btn_heal
    TamaClassicLocationAction.ADVENTURE_GATE -> R.string.tama_btn_adventure_gate
}

@Composable
fun TamaMapIcon(
    assetPath: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = "file:///android_asset/$assetPath",
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}
