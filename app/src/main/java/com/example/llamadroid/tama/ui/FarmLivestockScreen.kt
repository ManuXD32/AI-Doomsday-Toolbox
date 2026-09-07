package com.example.llamadroid.tama.ui

import android.widget.Toast
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
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
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
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

    LaunchedEffect(pet.id, type.id) {
        while (true) {
            now = System.currentTimeMillis()
            farmRepository.refreshFarmState(pet.id)
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
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.04f))
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (configuration.fontScale >= 1.3f) 1 else 2),
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
                                        val collected = farmRepository.collectLivestockOutput(pet.id, type)
                                        if (collected > 0) {
                                            val productName = resources.getString(
                                                if (type == FarmLivestockType.BARN) R.string.tama_item_milk_bottle else R.string.tama_item_egg
                                            )
                                            gameEngine.grantItem(
                                                InventoryItem(
                                                    id = type.productInventoryId,
                                                    name = productName,
                                                    type = ItemType.CROP
                                                ),
                                                collected
                                            )
                                            gameEngine.logEvent(
                                                pet.id,
                                                com.example.llamadroid.tama.data.EventType.OTHER,
                                                resources.getString(
                                                    if (type == FarmLivestockType.BARN) R.string.tama_event_collected_milk else R.string.tama_event_collected_eggs,
                                                    collected
                                                )
                                            )
                                        }
                                    }
                                },
                                enabled = stored > 0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF8D6E63),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFFBCAAA4),
                                    disabledContentColor = Color.White.copy(alpha = 0.75f)
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
                                color = if (feedModeEnabled) Color(0xFF42A5F5) else Color(0xFFE8DFC0),
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
                                        contentScale = ContentScale.Fit
                                    )
                                    Text(
                                        text = wheatCount.toString(),
                                        color = if (feedModeEnabled) Color.White else TamaDark,
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
                        val slot = slots[index]
                        val isHungry = livestockNeedsFeed(slot, now)
                        LivestockSlotCard(
                            type = type,
                            slot = slot,
                            isHungry = isHungry,
                            onClick = {
                                if (!feedModeEnabled || !slot.occupied) return@LivestockSlotCard
                                scope.launch {
                                    if (!isHungry) {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.tama_farm_livestock_not_hungry),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    if (wheatCount <= 0) {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.tama_farm_livestock_no_wheat),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val wheatName = FarmTradeItemCatalog.displayName(
                                        LIVESTOCK_FEED_ITEM_ID,
                                        configuration.locales[0] ?: Locale.getDefault()
                                    )
                                    val consumed = gameEngine.consumeItem(
                                        InventoryItem(
                                            id = LIVESTOCK_FEED_ITEM_ID,
                                            name = wheatName,
                                            type = ItemType.CROP
                                        ),
                                        1
                                    )
                                    if (!consumed) {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.tama_farm_livestock_no_wheat),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val fed = farmRepository.feedLivestockAnimal(
                                        petId = pet.id,
                                        type = type,
                                        slotIndex = index
                                    )
                                    if (!fed) {
                                        gameEngine.grantItem(
                                            InventoryItem(
                                                id = LIVESTOCK_FEED_ITEM_ID,
                                                name = wheatName,
                                                type = ItemType.CROP
                                            ),
                                            1
                                        )
                                        return@launch
                                    }
                                    gameEngine.logEvent(
                                        pet.id,
                                        com.example.llamadroid.tama.data.EventType.OTHER,
                                        resources.getString(
                                            if (type == FarmLivestockType.BARN) R.string.tama_event_fed_cow else R.string.tama_event_fed_chicken
                                        )
                                    )
                                    Toast.makeText(
                                        context,
                                        resources.getString(
                                            if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_fed_cow else R.string.tama_farm_livestock_fed_chicken
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
            }
        }
    }
}

@Composable
private fun LivestockSlotCard(
    type: FarmLivestockType,
    slot: FarmLivestockSlot,
    isHungry: Boolean,
    onClick: () -> Unit
) {
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
                contentScale = ContentScale.Crop
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
                        .size(animalSize),
                    contentScale = ContentScale.Fit
                )
                AsyncImage(
                    model = "file:///android_asset/${type.productAssetPath}",
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(22.dp),
                    contentScale = ContentScale.Fit
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
                Text(
                    stringResource(R.string.tama_farm_livestock_empty_slot),
                    modifier = Modifier.align(Alignment.Center),
                    color = TamaDark,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1
                )
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
