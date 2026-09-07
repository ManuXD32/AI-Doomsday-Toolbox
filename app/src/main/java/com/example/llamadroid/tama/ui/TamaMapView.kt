package com.example.llamadroid.tama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.TamaAmbientNpcCatalog
import com.example.llamadroid.tama.data.TamaAmbientNpcState
import com.example.llamadroid.tama.data.TamaLocation
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.data.localizedDescription
import com.example.llamadroid.tama.data.localizedName

private const val UNKNOWN_LOCATION_ICON_ASSET = "tama/map/unknown.png"

/**
 * Map view showing locations in a city grid.
 */
@Composable
fun TamaMapView(
    cityName: String,
    locations: List<TamaLocation>,
    currentLocation: TamaLocation?,
    discoveredLocationIds: Set<String>,
    onLocationClick: (TamaLocation) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TamaLight)
            .padding(8.dp)
    ) {
        // City name header
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
        
        // 5x5 grid of locations
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (y in 0 until 5) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (x in 0 until 5) {
                        val location = locations.find { it.x == x && it.y == y }
                        // Home is always discovered
                        val isDiscovered = location != null && (
                            location.type == com.example.llamadroid.tama.data.LocationType.HOME ||
                            discoveredLocationIds.contains(location.id)
                        )
                        LocationTile(
                            location = location,
                            isCurrentLocation = location?.id == currentLocation?.id,
                            isDiscovered = isDiscovered,
                            onClick = { location?.let { onLocationClick(it) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        
        // Legend
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
        }
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
        isCurrentLocation -> Color(0xFF8BC34A)  // Green for current
        isDiscovered -> TamaLight
        location != null -> Color(0xFFC0C0C0)  // Sillier gray for undiscovered
        else -> TamaBackground  // Empty tile
    }
    
    val borderColor = if (isCurrentLocation) TamaDark else Color.Transparent
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = location != null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (location != null) {
            TamaMapIcon(
                assetPath = if (isDiscovered) location.type.mapIconAssetPath else UNKNOWN_LOCATION_ICON_ASSET,
                size = 108.dp
            )
        }
    }
}

@Composable
fun LegendItem(assetPath: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TamaMapIcon(assetPath = assetPath, size = 68.dp)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TamaAccent
        )
    }
}

/**
 * Dialog showing location details and actions.
 */
@Composable
fun LocationDetailsDialog(
    location: TamaLocation,
    isCurrentLocation: Boolean,
    petEnergy: Int,
    travelCost: Int,
    onTravel: () -> Unit,
    onArcade: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ambientNpc = remember(location.type) { TamaAmbientNpcCatalog.forLocation(location.type) }
    TamaPopupDialog(
        title = location.type.localizedName(context),
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
            else -> "tama/backgrounds/principal_room.png"
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
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                text = TamaAmbientNpcCatalog.resolveLine(LocalContext.current, TamaAmbientNpcState(npc.id, 0)),
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

            Text(
                text = location.type.localizedDescription(context),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = TamaDark
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!isCurrentLocation) {
                Text(
                    text = stringResource(R.string.tama_travel_cost, travelCost),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (petEnergy >= travelCost) TamaDark else Color.Red
                )
            } else {
                Text(
                    text = stringResource(R.string.tama_you_are_here),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF2E7D32)
                )
            }

            if (location.type == LocationType.SHOP && location.shopInventory != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tama_location_shop_items, location.shopInventory.size),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            }

            if (location.type == LocationType.WORKPLACE && location.jobs != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tama_location_work_jobs, location.jobs.size),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TamaMutedText
                )
            }

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
        },
        footerContent = {
            if (!isCurrentLocation) {
                TextButton(
                    onClick = onTravel,
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = petEnergy >= travelCost
                ) {
                    Text(stringResource(R.string.tama_travel_here))
                }
            } else if (location.type == LocationType.ARCADE) {
                TextButton(onClick = onArcade, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tama_btn_arcade))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
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
