package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.example.llamadroid.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.WorldFarmMaintenance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.llamadroid.tama.game.FarmPlotAction
import com.example.llamadroid.tama.game.ClassicFarmPlots
import com.example.llamadroid.tama.game.FARM_COMPOSTER_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_COMPOSTER_PROCESS_MS
import com.example.llamadroid.tama.game.FARM_WELL_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_MAX_SPEED_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.FARM_COMPOSTER_COST
import com.example.llamadroid.tama.game.composterCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.composterSlotCapacityForLevel
import com.example.llamadroid.tama.game.wellCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.wellIntervalHoursForSpeedLevel
import com.example.llamadroid.tama.game.wellProductionIntervalForSpeedLevel
import com.example.llamadroid.tama.game.wellSpeedUpgradeCostForLevel
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.wellCapacityForLevel
import com.example.llamadroid.ui.components.pressAndHoldRepeat
import com.example.llamadroid.ui.components.rememberPressAndHoldRepeatState
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plot area: a swipeable pager of 3x3 pages ([FARM_TILES_PER_PAGE] tiles each) followed by the page
 * controls. [side] is the pager's square edge. [tileOverlay] is the per-tile extension slot.
 */
@Composable
internal fun FarmPlotArea(
    side: Dp,
    tiles: List<FarmTile>,
    currentTime: Long,
    pagerState: PagerState,
    pageCount: Int,
    onTileClick: (FarmTile) -> Unit,
    onTileLongPress: (FarmTile) -> Unit,
    modifier: Modifier = Modifier,
    tileOverlay: @Composable BoxScope.(FarmTile) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.size(side),
            pageSpacing = 12.dp,
            key = { it }
        ) { page ->
            FarmPlotGrid(
                page = page,
                tiles = tiles,
                currentTime = currentTime,
                onTileClick = onTileClick,
                onTileLongPress = onTileLongPress,
                tileOverlay = tileOverlay
            )
        }
        if (pageCount > 1) {
            FarmPageControls(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                onPrevious = {
                    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                },
                onNext = {
                    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1)) }
                }
            )
        }
    }
}

@Composable
private fun FarmPlotGrid(
    page: Int,
    tiles: List<FarmTile>,
    currentTime: Long,
    onTileClick: (FarmTile) -> Unit,
    onTileLongPress: (FarmTile) -> Unit,
    tileOverlay: @Composable BoxScope.(FarmTile) -> Unit
) {
    val rows = remember(page) { farmTileIdsForPage(page).toList().chunked(3) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { rowIds ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowIds.forEach { tileId ->
                    val tile = tiles.find { it.id == tileId } ?: FarmTile(id = tileId)
                    FarmTileItem(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        currentTime = currentTime,
                        onClick = { onTileClick(tile) },
                        onLongPress = { onTileLongPress(tile) },
                        overlay = tileOverlay
                    )
                }
            }
        }
    }
}

@Composable
private fun FarmPageControls(
    currentPage: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = currentPage > 0,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.tama_farm_page_previous),
                    tint = Color.White
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.tama_farm_page_label, currentPage + 1, pageCount),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Row(
                    modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    repeat(pageCount) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == currentPage) 8.dp else 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = if (index == currentPage) 1f else 0.45f))
                        )
                    }
                }
            }
            IconButton(
                onClick = onNext,
                enabled = currentPage < pageCount - 1,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.tama_farm_page_next),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
internal fun UpgradeItem(
    type: String,
    upgrade: FarmUpgradeEntity?,
    canAffordPurchase: Boolean,
    onOpen: () -> Unit,
    onPurchase: () -> Unit,
    buildCost: FarmMaterialCost? = null,
    materials: FarmMaterialStock? = null
) {
    val icon = if (type == "well") "well.png" else "composter.png"
    val isBought = upgrade?.isPurchased == true
    val stored = upgrade?.storedOutput ?: 0
    val title = if (type == "well") {
        stringResource(R.string.tama_farm_upgrade_well)
    } else {
        stringResource(R.string.tama_farm_upgrade_composter)
    }
    val buyPrice = if (type == "well") FARM_WELL_COST else FARM_COMPOSTER_COST
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clickable(enabled = isBought || canAffordPurchase) {
                    if (!isBought) onPurchase() else onOpen()
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/farm/Others/$icon",
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp)
                    .scale(FARM_UPGRADE_ICON_SCALE),
                contentScale = ContentScale.Fit,
                alpha = if (isBought || canAffordPurchase) 1f else 0.35f,
                filterQuality = FilterQuality.None
            )
            
            if (isBought && stored > 0) {
                Surface(
                    color = Color.Red,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                ) {
                    Text(
                        stored.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.wrapContentSize(),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (!isBought) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = stringResource(R.string.tama_farm_locked_desc),
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            if (isBought) title else stringResource(R.string.tama_farm_buy_upgrade, buyPrice),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = if (isBought) FontWeight.Bold else FontWeight.Normal,
            color = if (isBought || canAffordPurchase) TamaDark else TamaMutedText,
            modifier = Modifier
                .widthIn(max = 114.dp)
                .offset(y = (-6).dp)
        )
        if (!isBought && buildCost != null) {
            FarmMaterialCostRow(buildCost, materials, modifier = Modifier.widthIn(max = 114.dp), compact = true)
        }
    }
}
