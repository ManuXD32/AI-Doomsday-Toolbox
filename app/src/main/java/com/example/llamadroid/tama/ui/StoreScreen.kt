package com.example.llamadroid.tama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.FarmLivestockEntity
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.data.FarmShopCatalog
import com.example.llamadroid.ui.components.pressAndHoldRepeat
import com.example.llamadroid.ui.components.rememberPressAndHoldRepeatState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

private const val FARM_STORE_ASSET_SCALE = 1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    pet: TamaPet,
    farmRepository: FarmRepository,
    upgrades: List<FarmUpgradeEntity>,
    livestock: List<FarmLivestockEntity>,
    onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    onSell: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    onBuyUpgrade: suspend (String, Int) -> TamaGameEngine.ActionResult,
    onBuyDrone: suspend (String, Int) -> TamaGameEngine.ActionResult,
    onBuyLivestock: suspend (FarmLivestockType) -> TamaGameEngine.ActionResult,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tama_farm_store_tab_seeds),
        stringResource(R.string.tama_farm_store_tab_tools),
        stringResource(R.string.tama_farm_store_tab_materials),
        stringResource(R.string.tama_farm_store_tab_upgrades),
        stringResource(R.string.tama_farm_store_tab_livestock),
        stringResource(R.string.tama_farm_store_tab_sell)
    )

    LaunchedEffect(pet.id) {
        while (true) {
            farmRepository.refreshFarmState(pet.id)
            delay(30_000L)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            stringResource(R.string.tama_farm_store_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.soft_studio_tama_store_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            pet.money.toString(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TamaUiIcon("🪙", fontSize = 16.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = "file:///android_asset/tama/backgrounds/farm.png",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.34f))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.medium),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 0.dp
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        0 -> SeedList(onBuy)
                        1 -> ToolList(pet.inventory, onBuy, onBuyDrone)
                        2 -> MaterialList(onBuy)
                        3 -> UpgradeList(pet.money, upgrades, farmRepository, onBuyUpgrade)
                        4 -> LivestockList(livestock, onBuyLivestock)
                        5 -> SellList(pet.inventory, onSell)
                    }
                }
            }
        }
    }
}

@Composable
fun SeedList(onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val seeds = CropDefinitions.CROPS.entries.toList()
    LazyColumn {
        items(seeds) { seed ->
            val cropId = seed.key
            val cropInfo = seed.value
            val seedLabel = seedDisplayText(cropId).resolve(locale)
            StoreItemRow(
                name = seedLabel,
                price = cropInfo.seedPrice,
                icon = "Crops/seed/$cropId.png",
                description = stringResource(R.string.tama_farm_store_growth, formatTime(cropInfo.stageTimes.sum())),
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = "seed_$cropId",
                        name = seedLabel,
                        type = ItemType.SEED
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun ToolList(
    inventory: List<InventoryItem>,
    onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    onBuyDrone: suspend (String, Int) -> TamaGameEngine.ActionResult
) {
    data class ToolShopItem(
        val id: String,
        val name: String,
        val price: Int,
        val icon: String
    )

    val tools = listOf(
        ToolShopItem("hoe", stringResource(R.string.tama_inventory_hoe), 100, "Others/hoe.png"),
        ToolShopItem("watering_can", stringResource(R.string.tama_inventory_watering_can), 150, "Others/watering_can.png")
    )
    val drones = listOf(
        Triple(FARM_PLANTING_DRONE_ID, stringResource(R.string.tama_farm_planting_drone), "Others/planting_drone.png"),
        Triple(FARM_HARVESTING_DRONE_ID, stringResource(R.string.tama_farm_harvesting_drone), "Others/harvesting_drone.png")
    )
    LazyColumn {
        items(tools) { tool ->
            val currentDurability = farmToolTotalDurability(inventory, tool.id)
            val remainingDurability = (FARM_TOOL_DURABILITY_CAP - currentDurability).coerceAtLeast(0)
            val maxBuyQuantity = ((remainingDurability + FARM_TOOL_REPAIR_AMOUNT - 1) / FARM_TOOL_REPAIR_AMOUNT).coerceAtLeast(1)
            StoreItemRow(
                name = tool.name,
                price = tool.price,
                icon = tool.icon,
                description = stringResource(R.string.tama_farm_store_tool_durability, currentDurability, FARM_TOOL_DURABILITY_CAP),
                maxQty = maxBuyQuantity,
                actionEnabled = remainingDurability > 0,
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = tool.id,
                        name = tool.name,
                        type = ItemType.TOOL,
                        durability = FARM_TOOL_REPAIR_AMOUNT,
                        maxDurability = FARM_TOOL_DURABILITY_CAP
                    ), qty)
                }
            )
        }
        items(drones) { (id, name, icon) ->
            StoreItemRow(
                name = name,
                price = FARM_DRONE_BUY_PRICE,
                icon = icon,
                description = stringResource(R.string.tama_farm_store_drone_desc),
                showQuantityControls = false,
                onAction = { onBuyDrone(id, FARM_DRONE_BUY_PRICE) }
            )
        }
    }
}

@Composable
fun MaterialList(onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    data class MaterialShopItem(
        val name: String,
        val itemId: String,
        val price: Int,
        val icon: String,
        val description: String,
        val quantityMultiplier: Int = 1,
        val quantityUnitLabel: String? = null
    )

    val fuelBatchSize = FarmShopCatalog.FUEL_BUCKET_BUY_BATCH_SIZE
    val materials = listOf(
        MaterialShopItem(
            name = "Fertilizer",
            itemId = "fertilizer",
            price = FarmShopCatalog.FERTILIZER_BUY_PRICE,
            icon = "Others/fertilizer.png",
            description = stringResource(R.string.tama_farm_store_material_desc)
        ),
        MaterialShopItem(
            name = "Water",
            itemId = "water",
            price = FarmShopCatalog.materialBuyPrice("water"),
            icon = "Others/water.png",
            description = stringResource(R.string.tama_farm_store_material_desc)
        ),
        MaterialShopItem(
            name = stringResource(R.string.tama_item_fuel_bucket),
            itemId = FARM_FUEL_BUCKET_ID,
            price = FarmShopCatalog.fuelBucketBatchPrice(),
            icon = "Others/fuel_bucket.png",
            description = stringResource(R.string.tama_farm_store_fuel_bulk_desc, fuelBatchSize),
            quantityMultiplier = fuelBatchSize,
            quantityUnitLabel = stringResource(R.string.tama_farm_store_fuel_quantity_unit, fuelBatchSize)
        )
    )
    LazyColumn {
        items(materials) { material ->
            StoreItemRow(
                name = material.name,
                price = material.price,
                icon = material.icon,
                description = material.description,
                quantityMultiplier = material.quantityMultiplier,
                quantityUnitLabel = material.quantityUnitLabel,
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = material.itemId,
                        name = material.name,
                        type = ItemType.MATERIAL
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun UpgradeList(
    petMoney: Long,
    upgrades: List<FarmUpgradeEntity>,
    farmRepository: FarmRepository,
    onBuyUpgrade: suspend (String, Int) -> TamaGameEngine.ActionResult
) {
    data class UpgradeShopItem(
        val type: String,
        val name: String,
        val description: String,
        val price: Int,
        val icon: String,
        val maxed: Boolean
    )

    val farmlandUpgrade = upgrades.firstOrNull { it.type.equals(FARMLAND_UPGRADE_ID, ignoreCase = true) }
    val farmlandLevel = farmlandUpgrade?.takeIf { it.isPurchased }?.level ?: 0
    val farmlandCost = farmlandUpgradeCostForLevel(farmlandLevel)
    val plantingDroneUpgrade = upgrades.firstOrNull { it.type == FARM_PLANTING_DRONE_ID && it.isPurchased }
    val harvesterDroneUpgrade = upgrades.firstOrNull { it.type == FARM_HARVESTING_DRONE_ID && it.isPurchased }
    val plantingDroneState = remember(plantingDroneUpgrade?.extraDataJson) {
        farmRepository.decodePlantingDroneState(plantingDroneUpgrade)
    }
    val harvesterDroneState = remember(harvesterDroneUpgrade?.extraDataJson) {
        farmRepository.decodeHarvesterDroneState(harvesterDroneUpgrade)
    }
    val baseUpgradeItems = listOf(
        UpgradeShopItem("well", stringResource(R.string.tama_farm_upgrade_well), stringResource(R.string.tama_farm_store_upgrade_well_desc), FARM_WELL_COST, "Others/well.png", upgrades.firstOrNull { it.type.equals("well", ignoreCase = true) }?.isPurchased == true),
        UpgradeShopItem("composter", stringResource(R.string.tama_farm_upgrade_composter), stringResource(R.string.tama_farm_store_upgrade_composter_desc), 800, "Others/composter.png", upgrades.firstOrNull { it.type.equals("composter", ignoreCase = true) }?.isPurchased == true),
        UpgradeShopItem(FARMLAND_UPGRADE_ID, stringResource(R.string.tama_farm_upgrade_farmland), stringResource(R.string.tama_farm_store_upgrade_farmland_desc), farmlandCost ?: 0, "Others/farmland.png", farmlandCost == null)
    )
    val droneUpgradeItems = buildList {
        if (plantingDroneUpgrade != null) {
            val cost = farmDroneFuelUpgradeCostForLevel(plantingDroneState.fuelUpgradeLevel)
            add(
                UpgradeShopItem(
                    FARM_PLANTING_DRONE_FUEL_UPGRADE_ID,
                    stringResource(R.string.tama_farm_drone_fuel_upgrade_name, stringResource(R.string.tama_farm_planting_drone)),
                    cost?.let {
                        stringResource(
                            R.string.tama_farm_drone_fuel_upgrade_desc,
                            farmDroneFuelCapacityForUpgradeLevel(plantingDroneState.fuelUpgradeLevel + 1),
                            farmDroneFuelTransferAmountForUpgradeLevel(plantingDroneState.fuelUpgradeLevel + 1)
                        )
                    } ?: stringResource(R.string.tama_farm_upgrade_maxed),
                    cost ?: 0,
                    "Others/planting_drone.png",
                    cost == null
                )
            )
        }
        if (harvesterDroneUpgrade != null) {
            val cost = farmDroneFuelUpgradeCostForLevel(harvesterDroneState.fuelUpgradeLevel)
            add(
                UpgradeShopItem(
                    FARM_HARVESTING_DRONE_FUEL_UPGRADE_ID,
                    stringResource(R.string.tama_farm_drone_fuel_upgrade_name, stringResource(R.string.tama_farm_harvesting_drone)),
                    cost?.let {
                        stringResource(
                            R.string.tama_farm_drone_fuel_upgrade_desc,
                            farmDroneFuelCapacityForUpgradeLevel(harvesterDroneState.fuelUpgradeLevel + 1),
                            farmDroneFuelTransferAmountForUpgradeLevel(harvesterDroneState.fuelUpgradeLevel + 1)
                        )
                    } ?: stringResource(R.string.tama_farm_upgrade_maxed),
                    cost ?: 0,
                    "Others/harvesting_drone.png",
                    cost == null
                )
            )
        }
    }
    val upgradeItems = baseUpgradeItems + droneUpgradeItems
    LazyColumn {
        items(upgradeItems) { item ->
            val canBuy = petMoney >= item.price && !item.maxed
            StoreItemRow(
                name = item.name,
                price = item.price,
                icon = item.icon,
                description = if (item.maxed) stringResource(R.string.tama_farm_upgrade_maxed) else item.description,
                showQuantityControls = false,
                actionEnabled = canBuy,
                onAction = { onBuyUpgrade(item.type, item.price) }
            )
        }
    }
}

@Composable
fun LivestockList(
    livestock: List<FarmLivestockEntity>,
    onBuyLivestock: suspend (FarmLivestockType) -> TamaGameEngine.ActionResult
) {
    LazyColumn {
        items(FarmLivestockType.entries) { type ->
            val entity = livestock.firstOrNull { it.type == type.id }
            val slots = remember(entity?.slotsJson) { runCatching { entity?.let { Json.decodeFromString<List<FarmLivestockSlot>>(it.slotsJson) } }.getOrNull() ?: emptyLivestockSlots(type) }
            val occupied = occupiedLivestockCount(slots)
            val remaining = (type.maxAnimals - occupied).coerceAtLeast(0)
            StoreItemRow(
                name = if (type == FarmLivestockType.BARN) stringResource(R.string.tama_farm_livestock_cow) else stringResource(R.string.tama_farm_livestock_chicken),
                price = type.buyPrice,
                icon = type.animalAssetPath.removePrefix("farm/"),
                description = stringResource(
                    if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_cow_desc else R.string.tama_farm_livestock_chicken_desc,
                    occupied,
                    type.maxAnimals
                ),
                showQuantityControls = false,
                actionEnabled = remaining > 0,
                onAction = { onBuyLivestock(type) }
            )
        }
    }
}

@Composable
fun SellList(inventory: List<InventoryItem>, onSell: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val context = LocalContext.current
    val sellable = inventory.filter { it.type == ItemType.CROP && FarmTradeItemCatalog.isTradeItem(it.id) }
    if (sellable.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.tama_farm_store_no_trade_items), color = Color.Gray)
        }
    } else {
        LazyColumn {
            items(sellable) { item ->
                val sellPrice = FarmTradeItemCatalog.sellPrice(item.id).coerceAtLeast(5)
                
                StoreItemRow(
                    name = inventoryItemDisplayName(context, item),
                    price = sellPrice,
                    icon = FarmTradeItemCatalog.assetPath(item.id)?.removePrefix("farm/") ?: "Others/soil.png",
                    description = stringResource(R.string.tama_farm_store_owned, item.quantity),
                    isSelling = true,
                    maxQty = item.quantity,
                    onAction = { qty -> onSell(item, qty) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StoreItemRow(
    name: String,
    price: Int,
    icon: String,
    description: String,
    isSelling: Boolean = false,
    maxQty: Int = 99,
    showQuantityControls: Boolean = true,
    actionEnabled: Boolean = true,
    quantityMultiplier: Int = 1,
    quantityUnitLabel: String? = null,
    onAction: suspend (Int) -> TamaGameEngine.ActionResult
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qty by remember { mutableIntStateOf(1) }
    val decrementRepeatState = rememberPressAndHoldRepeatState()
    val incrementRepeatState = rememberPressAndHoldRepeatState()

    fun decrementQuantity(): Boolean {
        if (qty <= 1) return false
        qty -= 1
        return true
    }

    fun incrementQuantity(): Boolean {
        if (qty >= maxQty) return false
        qty += 1
        return true
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                val assetUri = remember(icon) { "file:///android_asset/farm/$icon" }
                AsyncImage(
                    model = rememberFarmAssetModel(assetUri),
                    contentDescription = name,
                    modifier = Modifier
                        .size(56.dp)
                        .scale(FARM_STORE_ASSET_SCALE),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TamaSoftLabel(text = name)
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (price * qty).toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (isSelling) Color(0xFF43A047) else Color(0xFFE65100)
                )
                Spacer(Modifier.width(4.dp))
                TamaUiIcon("🪙", fontSize = 14.sp)
            }

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showQuantityControls) {
                    IconButton(
                        onClick = { decrementRepeatState.handleClick { decrementQuantity() } },
                        modifier = Modifier
                            .size(48.dp)
                            .pressAndHoldRepeat(
                                state = decrementRepeatState,
                                enabled = qty > 1
                            ) { decrementQuantity() }
                    ) {
                        Icon(Icons.Default.Remove, stringResource(R.string.soft_studio_quantity_decrease))
                    }
                    Column(
                        modifier = Modifier.widthIn(min = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(qty.toString(), modifier = Modifier.padding(horizontal = 4.dp))
                        quantityUnitLabel?.let { label ->
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = { incrementRepeatState.handleClick { incrementQuantity() } },
                        modifier = Modifier
                            .size(48.dp)
                            .pressAndHoldRepeat(
                                state = incrementRepeatState,
                                enabled = qty < maxQty
                            ) { incrementQuantity() }
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.soft_studio_quantity_increase))
                    }
                }
                Button(
                    onClick = {
                        scope.launch {
                            val result = onAction(qty * quantityMultiplier)
                            if (result.success) {
                                qty = 1
                                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = actionEnabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 104.dp)
                        .padding(start = if (showQuantityControls) 8.dp else 0.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelling) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (isSelling) stringResource(R.string.tama_farm_store_sell) else stringResource(R.string.tama_farm_store_buy),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val hours = ms / 3600000
    return "${hours}h"
}
