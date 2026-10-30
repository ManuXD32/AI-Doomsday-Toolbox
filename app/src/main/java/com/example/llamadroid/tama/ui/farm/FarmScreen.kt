package com.example.llamadroid.tama.ui

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmScreen(
    pet: TamaPet,
    gameEngine: TamaGameEngine,
    farmRepository: FarmRepository,
    onBack: () -> Unit,
    onOpenBarn: (() -> Unit)? = null,
    onOpenCoop: (() -> Unit)? = null
) {
    val tiles by farmRepository.observeTiles(pet.id).collectAsState(initial = emptyList())
    val upgrades by farmRepository.observeUpgrades(pet.id).collectAsState(initial = emptyList())
    val livestock by farmRepository.observeLivestock(pet.id).collectAsState(initial = emptyList())
    val (fieldChickens, fieldCows) = remember(livestock) {
        fun owned(type: FarmLivestockType) = livestock.firstOrNull { FarmLivestockType.fromId(it.type) == type }
            ?.let { farmRepository.decodeLivestockSlots(it) }?.count { it.occupied } ?: 0
        owned(FarmLivestockType.COOP) to owned(FarmLivestockType.BARN)
    }

    val scope = rememberCoroutineScope()
    var selectedToolId by remember { mutableStateOf<String?>(null) }
    var showSeedPicker by remember { mutableStateOf<Int?>(null) }
    var showPlantAllPicker by remember { mutableStateOf(false) }
    var lastSeedId by remember { mutableStateOf<String?>(null) }
    var showWellDialog by remember { mutableStateOf(false) }
    var showComposterDialog by remember { mutableStateOf(false) }
    var showPlantingDroneDialog by remember { mutableStateOf(false) }
    var showHarvesterDroneDialog by remember { mutableStateOf(false) }
    var showComposterCropPickerForSlot by remember { mutableStateOf<Int?>(null) }
    var inspectedCropTileId by remember { mutableStateOf<Int?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val notice = rememberTamaNotice()
    val juice = remember { FarmJuiceState(context) }
    val haptic = LocalHapticFeedback.current
    val ambient = remember(pet.id) { FarmAmbientState(juice.reducedMotion, pet.id) }
    val ambientSnapshot = remember(pet.id, currentTime / 60_000L) { farmAmbientSnapshot(pet.id, currentTime) }
    SideEffect {
        ambient.snapshot = ambientSnapshot
        ambient.wind.floatValue = ambientSnapshot.wind
    }
    val actions = remember(gameEngine, scope, juice, haptic, notice) { FarmActionController(context, notice, gameEngine, scope, juice, haptic) }
    fun submitMaintenance(operation: String, arguments: Map<String, String> = emptyMap()) {
        scope.launch {
            val result = WorldFarmMaintenance.request(context, gameEngine, operation, arguments)
            notice.show(result.message, isError = !result.success)
        }
    }
    val walkthroughTargets = LocalWalkthroughTargets.current
    val wellUpgrade = upgrades.find { u -> u.type == "well" }
    val composterUpgrade = upgrades.find { u -> u.type == "composter" }
    val farmlandUpgrade = upgrades.find { u -> u.type == FARMLAND_UPGRADE_ID }
    val plantingDroneUpgrade = upgrades.find { u -> u.type == FARM_PLANTING_DRONE_ID }
    val harvesterDroneUpgrade = upgrades.find { u -> u.type == FARM_HARVESTING_DRONE_ID }
    val farmlandLevel = farmlandUpgrade?.takeIf { it.isPurchased }?.level ?: 0
    val unlockedFarmPages = farmPageCountForFarmlandLevel(farmlandLevel)
    val pagerState = rememberPagerState(pageCount = { unlockedFarmPages })
    val wellState = remember(wellUpgrade?.extraDataJson, wellUpgrade?.storedOutput, wellUpgrade?.level, currentTime) {
        farmRepository.decodeWellState(wellUpgrade, currentTime)
    }
    val composterSlots = remember(composterUpgrade?.extraDataJson, composterUpgrade?.level) {
        farmRepository.decodeComposterSlots(composterUpgrade)
    }
    val plantingDroneState = remember(plantingDroneUpgrade?.extraDataJson, currentTime) {
        farmRepository.decodePlantingDroneState(plantingDroneUpgrade, currentTime)
    }
    val harvesterDroneState = remember(harvesterDroneUpgrade?.extraDataJson, currentTime) {
        farmRepository.decodeHarvesterDroneState(harvesterDroneUpgrade, currentTime)
    }
    val materials = remember(pet.inventory) { FarmMaterialStock.of(pet.inventory) }
    val canBuyWell = pet.money >= FARM_WELL_COST && FarmMaterialCosts.WELL_BUILD.isCoveredBy(materials)
    val canBuyComposter = pet.money >= FARM_COMPOSTER_COST && FarmMaterialCosts.COMPOSTER_BUILD.isCoveredBy(materials)
    val compostableItems = remember(pet.inventory, context) {
        pet.inventory
            .filter { it.quantity > 0 && FarmTradeItemCatalog.isCompostableCropItem(it.id) }
            .sortedBy { inventoryItemDisplayName(context, it) }
    }
    // The selected tool is tracked by id so its durability/quantity stays live, and it is cleared
    // as soon as the item is used up or removed from the inventory.
    val selectedTool = remember(pet.inventory, selectedToolId) {
        selectedToolId?.let { id -> pet.inventory.firstOrNull { it.id == id && farmToolIsUsable(it) } }
    }
    LaunchedEffect(selectedToolId, selectedTool == null) {
        if (selectedToolId != null && selectedTool == null) selectedToolId = null
    }
    val inspectedTile = remember(tiles, inspectedCropTileId) {
        inspectedCropTileId?.let { id -> tiles.find { it.id == id }?.takeIf { it.crop != null } }
    }
    LaunchedEffect(inspectedCropTileId, inspectedTile == null) {
        if (inspectedCropTileId != null && inspectedTile == null) inspectedCropTileId = null
    }

    LaunchedEffect(pet.id, pet.cycleFrozen) {
        if (pet.cycleFrozen) return@LaunchedEffect
        while (true) {
            farmRepository.refreshFarmState(pet.id, cycleFrozen = pet.cycleFrozen)
            delay(30_000L)
        }
    }

    LaunchedEffect(pet.cycleFrozen) {
        if (pet.cycleFrozen) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            currentTime = System.currentTimeMillis()
        }
    }

    if (pet.cycleFrozen) {
        Scaffold(
            topBar = {
                TopAppBar(
                    actions = { com.example.llamadroid.ui.walkthrough.FeatureGuideAction() },
                    title = { Text(stringResource(R.string.tama_farm_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.walkthroughTarget("back")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.tama_cycle_frozen_busy),
                    color = TamaDark,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screenWidthDp = with(density) { windowSize.width.toDp().value }
    val screenHeightDp = with(density) { windowSize.height.toDp().value }
    // width >= 600dp (tablet) or any landscape window: grid on the left, controls on the right.
    val sideBySide = screenWidthDp >= 600f || screenWidthDp > screenHeightDp

    val onTileClick: (FarmTile) -> Unit = { tile ->
        val tool = selectedTool
        if (tool != null) {
            handleTileClick(
                tile = tile,
                tool = tool,
                gameEngine = gameEngine,
                scope = scope,
                context = context,
                notice = notice,
                onActionRequested = { action ->
                    juice.bounce(tile.id)
                    ambient.onPlotAction(tile.id)
                    if (action == FarmPlotAction.WATER || action == FarmPlotAction.POUR_WATER) juice.waterSplash(tile.id)
                },
                juice = juice
            )
        } else {
            // Smart tap: till, water, plant, harvest or clear, whichever fits the plot.
            val seeds = pet.inventory.filter { it.type == ItemType.SEED && it.quantity > 0 }
            val remembered = lastSeedId?.takeIf { id -> seeds.any { it.id == "seed_$id" } }
            val seedId = remembered ?: seeds.singleOrNull()?.id?.removePrefix("seed_")
            val needsSeedChoice = tile.crop == null && tile.status != TileStatus.SOIL && seedId == null && seeds.size > 1
            if (needsSeedChoice) showSeedPicker = tile.id else {
                ambient.onPlotAction(tile.id)
                actions.smartTap(tile.id, seedId)
            }
        }
        walkthroughTargets?.recordEvent("tama.farm")
    }
    val clearableCount = farmClearableObstacleCount(tiles, pagerState.currentPage)
    val onQuickAction: (FarmQuickAction) -> Unit = { kind ->
        val page = pagerState.currentPage
        ambient.onPlotAction(farmTileIdsForPage(page).first + 4)
        if (kind == FarmQuickAction.PLANT) {
            showPlantAllPicker = true
        } else {
            actions.batch(kind, page, tiles)
        }
    }
    val tileOverlay: @Composable BoxScope.(FarmTile) -> Unit = { tile -> FarmTileOverlay(tile, currentTime) }
    val onTileLongPress: (FarmTile) -> Unit = { tile ->
        if (tile.crop != null) inspectedCropTileId = tile.id
    }
    val onToolSelect: (InventoryItem?) -> Unit = { selectedToolId = it?.id }
    val facilities: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            UpgradeItem(
                type = "well",
                upgrade = wellUpgrade,
                canAffordPurchase = canBuyWell,
                onOpen = { showWellDialog = true },
                onPurchase = { submitMaintenance("buy_well") },
                buildCost = FarmMaterialCosts.WELL_BUILD,
                materials = materials
            )
            UpgradeItem(
                type = "composter",
                upgrade = composterUpgrade,
                canAffordPurchase = canBuyComposter,
                onOpen = { showComposterDialog = true },
                onPurchase = { submitMaintenance("buy_composter") },
                buildCost = FarmMaterialCosts.COMPOSTER_BUILD,
                materials = materials
            )
        }
    }

    CompositionLocalProvider(LocalFarmJuice provides juice, LocalFarmAmbient provides ambient) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tama_farm_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.walkthroughTarget("back")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                        Row(
                            modifier = Modifier.padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FarmWeatherChip(ambientSnapshot.label, modifier = Modifier.weight(1f, fill = false))
                            Text(
                                pet.money.toString(),
                                fontWeight = FontWeight.Bold
                            )
                            TamaUiIcon("🪙", fontSize = 16.sp)
                        }
                    }
                )
            },
            bottomBar = {
                // Phone portrait keeps the tool strip pinned; the side-by-side layout shows tools in the right pane.
                if (!sideBySide) {
                    ToolSidebar(
                        inventory = pet.inventory,
                        selectedTool = selectedTool,
                        onToolSelect = onToolSelect,
                        onPlantingDroneOpen = { showPlantingDroneDialog = true },
                        onHarvesterDroneOpen = { showHarvesterDroneDialog = true },
                        extra = { FarmQuickActionRow(onQuickAction, clearableCount = clearableCount) }
                    )
                }
            }
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = FARM_FIELD_BACKGROUND_ASSET,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.08f))
                )
                // Ambient back pass (tint + pet lane) sits between the background and the plots; on tablets it
                // covers the field pane only. The front pass (shadows, critters, rain) is added after the plots.
                val ambientModifier = if (sideBySide) {
                    Modifier.width(
                        minOf(
                            maxWidth * 0.58f - 16.dp,
                            maxHeight - 16.dp - (if (unlockedFarmPages > 1) 56.dp else 0.dp)
                        ).coerceAtLeast(160.dp) + 16.dp
                    ).fillMaxHeight()
                } else {
                    Modifier.fillMaxSize()
                }
                FarmAmbientBackLayer(ambient, pet, ambientModifier)
                // Owned coop/barn animals potter along the bottom edge of the field, behind the plots.
                Box(modifier = ambientModifier) {
                    FarmFieldEdgeAnimals(
                        chickens = fieldChickens,
                        cows = fieldCows,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                    )
                }

                val controlsReserve = if (unlockedFarmPages > 1) 56.dp else 0.dp
                if (sideBySide) {
                    val gridSide = minOf(
                        maxWidth * 0.58f - 16.dp,
                        maxHeight - 16.dp - controlsReserve
                    ).coerceAtLeast(160.dp)
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(gridSide + 16.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                                .walkthroughTarget("tama.farm"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            FarmPlotArea(
                                side = gridSide,
                                tiles = tiles,
                                currentTime = currentTime,
                                pagerState = pagerState,
                                pageCount = unlockedFarmPages,
                                onTileClick = onTileClick,
                                onTileLongPress = onTileLongPress,
                                modifier = Modifier.farmReportPlotBounds(ambient, with(density) { gridSide.toPx() }),
                                tileOverlay = tileOverlay
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FarmInfoSection(title = stringResource(R.string.tama_farm_facilities_section)) {
                                facilities()
                            }
                            FarmInfoSection(title = stringResource(R.string.tama_farm_tools_section)) {
                                FarmToolPalette(
                                    inventory = pet.inventory,
                                    selectedTool = selectedTool,
                                    onToolSelect = onToolSelect,
                                    onPlantingDroneOpen = { showPlantingDroneDialog = true },
                                    onHarvesterDroneOpen = { showHarvesterDroneDialog = true }
                                )
                                Text(
                                    text = selectedTool?.let {
                                        stringResource(R.string.tama_farm_selected_tool, inventoryItemDisplayName(context, it))
                                    } ?: stringResource(R.string.tama_farm_no_tool_hint),
                                    color = TamaMutedText,
                                    fontSize = 13.sp
                                )
                            }
                            FarmInfoSection(title = stringResource(R.string.tama_farm_quick_actions_section)) {
                                FarmQuickActionPalette(onAction = onQuickAction, clearableCount = clearableCount)
                            }
                            if (onOpenBarn != null || onOpenCoop != null) {
                                FarmInfoSection(title = stringResource(R.string.tama_farm_animals_section)) {
                                    FarmAnimalShortcuts(onOpenBarn = onOpenBarn, onOpenCoop = onOpenCoop)
                                }
                            }
                        }
                    }
                } else {
                    val gridSide = minOf(maxWidth - 24.dp, 520.dp).coerceAtLeast(160.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            facilities()
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .walkthroughTarget("tama.farm")
                        ) {
                            FarmPlotArea(
                                side = gridSide,
                                tiles = tiles,
                                currentTime = currentTime,
                                pagerState = pagerState,
                                pageCount = unlockedFarmPages,
                                onTileClick = onTileClick,
                                onTileLongPress = onTileLongPress,
                                modifier = Modifier.farmReportPlotBounds(ambient, with(density) { gridSide.toPx() }),
                                tileOverlay = tileOverlay
                            )
                        }
                    }
                }
                FarmAmbientFrontLayer(ambient, ambientModifier)
            }

            if (showSeedPicker != null) {
                SeedPickerDialog(
                    inventory = pet.inventory,
                    onDismiss = { showSeedPicker = null },
                    onSeedSelected = { seed ->
                        val plotId = showSeedPicker ?: return@SeedPickerDialog
                        val cropId = seed.id.removePrefix("seed_")
                        lastSeedId = cropId
                        actions.smartTap(plotId, cropId)
                        showSeedPicker = null
                    }
                )
            }

            if (showPlantAllPicker) {
                SeedPickerDialog(
                    inventory = pet.inventory,
                    onDismiss = { showPlantAllPicker = false },
                    onSeedSelected = { seed ->
                        val cropId = seed.id.removePrefix("seed_")
                        lastSeedId = cropId
                        actions.batch(FarmQuickAction.PLANT, pagerState.currentPage, tiles, cropId)
                        showPlantAllPicker = false
                    }
                )
            }

            if (showWellDialog) {
                WellDialog(
                    upgrade = wellUpgrade,
                    state = wellState,
                    now = currentTime,
                    money = pet.money,
                    materials = materials,
                    onDismiss = { showWellDialog = false },
                    onCollectTile = { slotIndex ->
                        submitMaintenance("collect_water", mapOf("slot" to slotIndex.toString()))
                    },
                    onUpgradeCapacity = { submitMaintenance("well_capacity") },
                    onUpgradeSpeed = { submitMaintenance("well_speed") }
                )
            }

            if (showComposterDialog) {
                ComposterDialog(
                    upgrade = composterUpgrade,
                    money = pet.money,
                    materials = materials,
                    availableCrops = compostableItems,
                    slots = composterSlots,
                    now = currentTime,
                    onDismiss = { showComposterDialog = false },
                    onTileClick = { slotIndex, slot ->
                        when (slot.state) {
                            ComposterSlotState.EMPTY -> {
                                if (compostableItems.isNotEmpty()) {
                                    showComposterCropPickerForSlot = slotIndex
                                }
                            }
                            ComposterSlotState.READY -> {
                                submitMaintenance("collect_compost", mapOf("slot" to slotIndex.toString()))
                            }
                            ComposterSlotState.PROCESSING -> Unit
                        }
                    },
                    onUpgrade = { submitMaintenance("composter_capacity") }
                )
            }

            if (showPlantingDroneDialog) {
                PlantingDroneDialog(
                    state = plantingDroneState,
                    inventory = pet.inventory,
                    currentTime = currentTime,
                    onDismiss = { showPlantingDroneDialog = false },
                    onStateChange = { updated ->
                        submitMaintenance("planting_settings", mapOf("settings" to Json.encodeToString(updated)))
                    },
                    onTransferItem = { item, quantity, _ ->
                        submitMaintenance("planting_item", mapOf("itemId" to item.id, "quantity" to quantity.toString()))
                    },
                    onTransferToolDurability = { familyId, amount, _ ->
                        submitMaintenance("planting_tool", mapOf("family" to familyId, "amount" to amount.toString()))
                    }
                )
            }

            if (showHarvesterDroneDialog) {
                HarvesterDroneDialog(
                    state = harvesterDroneState,
                    inventory = pet.inventory,
                    currentTime = currentTime,
                    onDismiss = { showHarvesterDroneDialog = false },
                    onStateChange = { updated ->
                        submitMaintenance("harvester_settings", mapOf("settings" to Json.encodeToString(updated)))
                    },
                    onTransferFuel = { item, quantity ->
                        submitMaintenance("harvester_fuel", mapOf("itemId" to item.id, "quantity" to quantity.toString()))
                    },
                    onCollectStorage = { submitMaintenance("harvester_collect") }
                )
            }

            if (showComposterCropPickerForSlot != null) {
                ComposterCropPickerDialog(
                    crops = compostableItems,
                    onDismiss = { showComposterCropPickerForSlot = null },
                    onCropSelected = { item ->
                        val slot = showComposterCropPickerForSlot ?: return@ComposterCropPickerDialog
                        submitMaintenance("compost", mapOf("itemId" to item.id, "slot" to slot.toString()))
                        showComposterCropPickerForSlot = null
                    }
                )
            }

            inspectedTile?.let { tile ->
                CropInspectDialog(
                    tile = tile,
                    now = currentTime,
                    onDismiss = { inspectedCropTileId = null }
                )
            }
        }
    }
}
