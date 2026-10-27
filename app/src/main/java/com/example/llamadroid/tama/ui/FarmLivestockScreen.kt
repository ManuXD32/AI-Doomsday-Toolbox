package com.example.llamadroid.tama.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.FarmLivestockSlot
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.FarmMaterialCost
import com.example.llamadroid.tama.data.FarmMaterialCosts
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.LIVESTOCK_FEED_ASSET_PATH
import com.example.llamadroid.tama.data.LIVESTOCK_FEED_ITEM_ID
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.emptyLivestockSlots
import com.example.llamadroid.tama.data.hungryLivestockCount
import com.example.llamadroid.tama.data.livestockNeedsFeed
import com.example.llamadroid.tama.data.livestockStructureCapacity
import com.example.llamadroid.tama.data.occupiedLivestockCount
import com.example.llamadroid.tama.data.storedLivestockOutput
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.WorldFarmMaintenance
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BarnScreen(
    pet: TamaPet,
    gameEngine: TamaGameEngine,
    farmRepository: FarmRepository,
    onBack: () -> Unit
) {
    FarmLivestockScreen(
        pet = pet,
        type = FarmLivestockType.BARN,
        gameEngine = gameEngine,
        farmRepository = farmRepository,
        onBack = onBack
    )
}

@Composable
fun ChickenCoopScreen(
    pet: TamaPet,
    gameEngine: TamaGameEngine,
    farmRepository: FarmRepository,
    onBack: () -> Unit
) {
    FarmLivestockScreen(
        pet = pet,
        type = FarmLivestockType.COOP,
        gameEngine = gameEngine,
        farmRepository = farmRepository,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FarmLivestockScreen(
    pet: TamaPet,
    type: FarmLivestockType,
    gameEngine: TamaGameEngine,
    farmRepository: FarmRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val notice = rememberTamaNotice()
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val reducedMotion = remember { farmReducedMotion(context) }
    val animalClock = rememberFarmAnimalClock(enabled = !reducedMotion)
    var collectHops by remember(type.id) { mutableIntStateOf(0) }
    val feedHops = remember(type.id) { mutableStateMapOf<Int, Int>() }
    val walkthroughTargets = LocalWalkthroughTargets.current
    var feedModeEnabled by rememberSaveable(type.id) { mutableStateOf(false) }
    var now by remember(type.id) { mutableLongStateOf(System.currentTimeMillis()) }
    val allLivestock by farmRepository.observeLivestock(pet.id).collectAsState(initial = emptyList())
    val entity = allLivestock.firstOrNull { it.type == type.id }
    val slots = remember(entity?.slotsJson) {
        farmRepository.decodeLivestockSlots(entity, type).ifEmpty { emptyLivestockSlots(type) }
    }
    val occupied = occupiedLivestockCount(slots)
    val stored = storedLivestockOutput(slots)
    val capacity = livestockStructureCapacity(type, slots)
    val hungryCount = hungryLivestockCount(slots, now)
    val wheatCount = pet.inventory.firstOrNull { it.id == LIVESTOCK_FEED_ITEM_ID }?.quantity ?: 0

    LaunchedEffect(wheatCount) {
        if (wheatCount <= 0 && feedModeEnabled) {
            feedModeEnabled = false
        }
    }

    LaunchedEffect(pet.id, type.id, pet.cycleFrozen) {
        while (true) {
            now = System.currentTimeMillis()
            farmRepository.refreshFarmState(pet.id, cycleFrozen = pet.cycleFrozen)
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = if (type == FarmLivestockType.BARN) {
                            stringResource(R.string.tama_farm_barn_title)
                        } else {
                            stringResource(R.string.tama_farm_coop_title)
                        },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AsyncImage(
                model = "file:///android_asset/${type.backgroundAssetPath}",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.04f))
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Adaptive columns: 2 on phones (1 for very large font scale), 3 on small tablets, 4 on large ones.
            val columnCount = when {
                maxWidth >= 840.dp -> 4
                maxWidth >= 600.dp -> 3
                configuration.fontScale >= 1.3f -> 1
                else -> 2
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF7EFD8).copy(alpha = 0.88f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.walkthroughTarget("tama.livestock.header")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(
                                        if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_storage_milk else R.string.tama_farm_livestock_storage_eggs,
                                        stored,
                                        capacity
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = TamaDark,
                                    fontSize = 17.sp
                                )
                                Text(
                                    stringResource(
                                        if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_animals_cows else R.string.tama_farm_livestock_animals_chickens,
                                        occupied,
                                        type.maxAnimals
                                    ),
                                    color = TamaMutedText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                                if (hungryCount > 0) {
                                    Text(
                                        stringResource(R.string.tama_farm_livestock_hungry_count, hungryCount),
                                        color = Color(0xFFB71C1C),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = WorldFarmMaintenance.request(context, gameEngine, "collect_livestock", mapOf("type" to type.id))
                                        if (result.success) collectHops++
                                        notice.show(result.message, isError = !result.success)
                                }
                                },
                                enabled = stored > 0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LivestockCollectContainer,
                                    contentColor = LivestockCollectContent,
                                    disabledContainerColor = LivestockCollectDisabledContainer,
                                    disabledContentColor = LivestockCollectDisabledContent
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    stringResource(
                                        if (type == FarmLivestockType.BARN) R.string.tama_farm_collect_milk else R.string.tama_farm_collect_eggs
                                    )
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = if (feedModeEnabled) LivestockFeedOnContainer else Color(0xFFE8DFC0),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        feedModeEnabled = !feedModeEnabled
                                        walkthroughTargets?.recordEvent("tama.livestock.feed")
                                    }
                                    .walkthroughTarget("tama.livestock.feed")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AsyncImage(
                                        model = "file:///android_asset/$LIVESTOCK_FEED_ASSET_PATH",
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        contentScale = ContentScale.Fit,
                                        filterQuality = FilterQuality.None
                                    )
                                    Text(
                                        text = wheatCount.toString(),
                                        color = if (feedModeEnabled) LivestockFeedOnContent else TamaDark,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            if (feedModeEnabled) {
                                Text(
                                    stringResource(R.string.tama_farm_livestock_feed_mode_hint),
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFF1565C0),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                }
                    items(slots.indices.toList()) { index ->
                        val slotMaterials = FarmMaterialCosts.livestock(type)
                        val stock = FarmMaterialStock.of(pet.inventory)
                        val slot = slots[index]
                        val isHungry = livestockNeedsFeed(slot, now)
                        LivestockSlotCard(
                            type = type,
                            slot = slot,
                            isHungry = isHungry,
                            canBuy = pet.money >= type.buyPrice && slotMaterials.isCoveredBy(stock),
                            materialCost = slotMaterials,
                            stock = stock,
                            seed = index + (if (type == FarmLivestockType.BARN) 0 else 50),
                            clock = animalClock,
                            reducedMotion = reducedMotion,
                            hopToken = collectHops + (feedHops[index] ?: 0),
                            onBuy = {
                                scope.launch {
                                    val result = WorldFarmMaintenance.request(context, gameEngine, "buy_livestock", mapOf("type" to type.id))
                                    notice.show(result.message, isError = !result.success)
                                }
                            },
                            onClick = {
                                if (!feedModeEnabled || !slot.occupied) return@LivestockSlotCard
                                scope.launch {
                                    val result = WorldFarmMaintenance.request(context, gameEngine, "feed_livestock",
                                        mapOf("type" to type.id, "slot" to index.toString()))
                                    if (result.success) feedHops[index] = (feedHops[index] ?: 0) + 1
                                    notice.show(result.message, isError = !result.success)
                                }
                            }
                        )
                    }
            }
            }
        }
    }
}

@Composable
internal fun LivestockSlotCard(
    type: FarmLivestockType,
    slot: FarmLivestockSlot,
    isHungry: Boolean,
    canBuy: Boolean,
    materialCost: FarmMaterialCost = FarmMaterialCost.NONE,
    stock: FarmMaterialStock? = null,
    seed: Int,
    clock: State<Float>,
    reducedMotion: Boolean,
    onBuy: () -> Unit,
    onClick: () -> Unit,
    hopToken: Int = 0
) {
    var seenHopToken by remember { mutableIntStateOf(hopToken) }
    val hopStart = remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(hopToken) {
        if (hopToken != seenHopToken) {
            seenHopToken = hopToken
            if (!reducedMotion) hopStart.floatValue = clock.value
        }
    }
    val cardHeight = if (type == FarmLivestockType.BARN) 188.dp else 132.dp
    val animalSize = if (type == FarmLivestockType.BARN) 144.dp else 72.dp
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F0DA).copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.clickable(enabled = slot.occupied, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
        ) {
            AsyncImage(
                model = "file:///android_asset/${type.slotAssetPath}",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.05f))
            )
            if (slot.occupied) {
                AsyncImage(
                    model = "file:///android_asset/${type.animalAssetPath}",
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(animalSize)
                        .graphicsLayer {
                            if (reducedMotion) return@graphicsLayer
                            val idle = livestockIdlePose(clock.value, seed, hopStart.floatValue, density)
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            translationY = idle.translationY
                            scaleX = idle.scaleX
                            scaleY = idle.scaleY
                        },
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                AsyncImage(
                    model = "file:///android_asset/${type.productAssetPath}",
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(22.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                if (isHungry) {
                    Surface(
                        color = Color(0xFFC62828),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        TamaUiIcon(
                            "😡",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            fontSize = 10.sp
                        )
                    }
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.58f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        livestockSlotStatus(type, slot, isHungry),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Surface(
                    color = Color(0xFF8E24AA),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        slot.storedOutput.toString(),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.tama_farm_livestock_empty_slot),
                        color = TamaDark,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = onBuy,
                        enabled = canBuy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FarmPopupButtonEnabled,
                            contentColor = FarmPopupButtonOnEnabled,
                            disabledContainerColor = FarmPopupButtonDisabled,
                            disabledContentColor = FarmPopupButtonOnDisabled
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            stringResource(R.string.tama_farm_livestock_buy_slot, type.buyPrice),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FarmMaterialCostRow(materialCost, stock, compact = true, showMissingNote = false)
                }
            }
        }
    }
}

@Composable
private fun livestockSlotStatus(type: FarmLivestockType, slot: FarmLivestockSlot, isHungry: Boolean): String {
    if (isHungry) {
        return stringResource(R.string.tama_farm_livestock_slot_hungry)
    }
    if (slot.storedOutput >= type.perAnimalStorageCap) {
        return stringResource(R.string.tama_farm_livestock_slot_full)
    }
    val lastProduction = slot.lastProductionTime ?: return stringResource(R.string.status_loading)
    val remainingMs = ((lastProduction + type.productionIntervalMs) - System.currentTimeMillis()).coerceAtLeast(0L)
    val totalSeconds = remainingMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    return String.format(Locale.getDefault(), "%02dh %02dm", hours, minutes)
}

/** Transform of one slot animal: [translationY] in px (negative is up). */
internal data class LivestockIdlePose(val translationY: Float, val scaleX: Float, val scaleY: Float)

private const val LIVESTOCK_BREATH_SECONDS = 2.4f
private const val LIVESTOCK_HOP_SECONDS = 0.35f
private const val LIVESTOCK_HOP_AIR_FRACTION = 0.8f

/**
 * Pure idle pose at clock time [t] seconds: slow breathing (scaleY 1.00 to 1.025, scaleX 1.00 to 0.99) and a
 * 1.5 dp bob, de-synced by [seed], plus a one-shot hop (about 10 dp up, small squash on landing) that started
 * at [hopStart] seconds (negative means none).
 */
internal fun livestockIdlePose(t: Float, seed: Int, hopStart: Float, density: Float): LivestockIdlePose {
    val phase = (t + seed * 0.7f) / LIVESTOCK_BREATH_SECONDS * (2f * PI.toFloat())
    val breath = 0.5f - 0.5f * cos(phase)
    var y = -1.5f * density * sin(phase)
    var sx = 1f - 0.01f * breath
    var sy = 1f + 0.025f * breath
    if (hopStart >= 0f) {
        val p = (t - hopStart) / LIVESTOCK_HOP_SECONDS
        if (p in 0f..1f) {
            if (p < LIVESTOCK_HOP_AIR_FRACTION) {
                y -= 10f * density * sin(PI.toFloat() * p / LIVESTOCK_HOP_AIR_FRACTION)
            } else {
                val squash = sin(PI.toFloat() * (p - LIVESTOCK_HOP_AIR_FRACTION) / (1f - LIVESTOCK_HOP_AIR_FRACTION))
                sy *= 1f - 0.08f * squash
                sx *= 1f + 0.06f * squash
            }
        }
    }
    return LivestockIdlePose(y, sx, sy)
}
