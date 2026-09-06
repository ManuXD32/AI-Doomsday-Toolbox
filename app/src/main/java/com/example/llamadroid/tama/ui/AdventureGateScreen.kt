package com.example.llamadroid.tama.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.TamaDialogTextCatalog
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.rpg.AdventureGateBattleEvent
import com.example.llamadroid.tama.rpg.AdventureGateBattleEventType
import com.example.llamadroid.tama.rpg.AdventureGateBattleLogEntry
import com.example.llamadroid.tama.rpg.AdventureGateBattleSnapshot
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCombatantState
import com.example.llamadroid.tama.rpg.AdventureGateCombatEngine
import com.example.llamadroid.tama.rpg.AdventureGateElement
import com.example.llamadroid.tama.rpg.AdventureGateEquipmentDefinition
import com.example.llamadroid.tama.rpg.AdventureGateEquipmentSlot
import com.example.llamadroid.tama.rpg.AdventureGateLogMessage
import com.example.llamadroid.tama.rpg.AdventureGateNightArenaRun
import com.example.llamadroid.tama.rpg.AdventureGatePhaseDefinition
import com.example.llamadroid.tama.rpg.AdventureGateProfile
import com.example.llamadroid.tama.rpg.AdventureGatePotionUseError
import com.example.llamadroid.tama.rpg.AdventureGateRepository
import com.example.llamadroid.tama.rpg.AdventureGateRecipeDefinition
import com.example.llamadroid.tama.rpg.AdventureGateStatusEffect
import com.example.llamadroid.tama.rpg.AdventureGateSupplyDefinition
import com.example.llamadroid.tama.rpg.AdventureGateSupplyKind
import com.example.llamadroid.tama.rpg.AdventureGateSkillTreePath
import com.example.llamadroid.tama.rpg.AdventureGateSkillKind
import com.example.llamadroid.tama.rpg.AdventureGateSkillDefinition
import com.example.llamadroid.tama.rpg.AdventureGateSkillPurchaseError
import com.example.llamadroid.tama.rpg.AdventureGateWorldDefinition
import com.example.llamadroid.tama.rpg.AdventureGateWorldProgress
import com.example.llamadroid.tama.rpg.NightArenaGenerator
import com.example.llamadroid.tama.rpg.NightArenaLevel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val GateDark = Color(0xFF14101F)
private val GatePanel = Color(0xFF251B34)
private val GateAccent = Color(0xFFFFC857)
private val GateBlue = Color(0xFF77DDE7)
private val GateGreen = Color(0xFF8BE38B)
private val GateDanger = Color(0xFFFF6B6B)
private val GateText = Color(0xFFF5EEFF)
private val ArenaCombatantCardWidth = 84.dp

private data class DamagePopup(
    val id: Long,
    val targetInstanceId: String,
    val amount: Int,
    val healing: Boolean = false,
    val label: String? = null,
    val color: Color = GateDanger
)

private data class GatePurchaseConfirmation(
    val title: String,
    val price: String,
    val onConfirm: () -> Unit
)

enum class AdventureGateScreenMode {
    ADVENTURE_GATE,
    NIGHT_ARENA
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventureGateScreen(
    navController: NavController,
    database: TamaDatabase,
    mode: AdventureGateScreenMode = AdventureGateScreenMode.ADVENTURE_GATE
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(database) { AdventureGateRepository(database) }
    val tamaDao = database.tamaDao()
    val activePet by tamaDao.observeActivePet().collectAsState(initial = null)
    val pet = remember(activePet) { activePet?.let(PetMapper::toDomain) }
    val petId = pet?.id
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val nightArenaInTimeWindow = NightArenaGenerator.isActiveWindow(nowMillis)

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    LaunchedEffect(petId, mode) {
        val id = petId ?: return@LaunchedEffect
        repository.getOrCreateProfile(id)
        val initialNow = System.currentTimeMillis()
        if (mode == AdventureGateScreenMode.NIGHT_ARENA && NightArenaGenerator.isActiveWindow(initialNow)) {
            repository.getOrCreateNightArenaRun(id, initialNow)
        }
        while (true) {
            delay(60_000)
            val tickNow = System.currentTimeMillis()
            repository.recoverProfile(id)
            if (mode == AdventureGateScreenMode.NIGHT_ARENA && NightArenaGenerator.isActiveWindow(tickNow)) {
                repository.getOrCreateNightArenaRun(id, tickNow)
            }
        }
    }

    val profileFlow = remember(petId) {
        petId?.let(repository::observeProfile) ?: flowOf(null)
    }
    val progressFlow = remember(petId) {
        petId?.let(repository::observeProgress) ?: flowOf(emptyList())
    }
    val battleFlow = remember(petId) {
        petId?.let(repository::observeBattle) ?: flowOf(null)
    }
    val nightArenaRunFlow = remember(petId) {
        petId?.let(repository::observeNightArenaRun) ?: flowOf(null)
    }
    val profile by profileFlow.collectAsState(initial = null)
    val progressRows by progressFlow.collectAsState(initial = emptyList())
    val battle by battleFlow.collectAsState(initial = null)
    val nightArenaRun by nightArenaRunFlow.collectAsState(initial = null)
    val routeBattle = battle?.takeIf { activeBattle ->
        val isNightBattle = activeBattle.worldId == AdventureGateCatalog.NIGHT_ARENA_WORLD_ID
        (mode == AdventureGateScreenMode.NIGHT_ARENA && isNightBattle) ||
            (mode == AdventureGateScreenMode.ADVENTURE_GATE && !isNightBattle)
    }
    var showLoadoutDialog by rememberSaveable { mutableStateOf(false) }
    var showShopDialog by rememberSaveable { mutableStateOf(false) }
    var showGearClosetDialog by rememberSaveable { mutableStateOf(false) }
    var showInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showRetreatDialog by rememberSaveable { mutableStateOf(false) }
    var gearInfoDialog by remember { mutableStateOf<AdventureGateEquipmentDefinition?>(null) }
    var pendingStoryPhase by remember { mutableStateOf<AdventureGatePhaseDefinition?>(null) }
    var startBattleBlocked by rememberSaveable { mutableStateOf(false) }
    var selectedWorldId by rememberSaveable(petId) { mutableStateOf(AdventureGateCatalog.worlds.first().id) }
    LaunchedEffect(progressRows, selectedWorldId) {
        val selectedWorld = AdventureGateCatalog.worlds.firstOrNull { it.id == selectedWorldId }
        if (selectedWorld == null || !isWorldUnlocked(selectedWorld, progressRows)) {
            selectedWorldId = furthestUnlockedAdventureGateWorld(progressRows).id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(
                                if (mode == AdventureGateScreenMode.NIGHT_ARENA) {
                                    R.string.night_arena_title
                                } else {
                                    R.string.adventure_gate_title
                                }
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        profile?.let {
                            Text(
                                text = stringResource(R.string.adventure_gate_level_xp, it.level, it.xp, it.xpToNext),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = GateText.copy(alpha = 0.72f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.walkthroughTarget("back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.adventure_gate_info_title))
                    }
                    if (routeBattle != null && petId != null) {
                        IconButton(onClick = {
                            if (routeBattle.isCompleted) {
                                scope.launch { repository.abandonBattle(petId) }
                            } else {
                                showRetreatDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.adventure_gate_abandon_battle))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GateDark,
                    titleContentColor = GateAccent,
                    navigationIconContentColor = GateAccent,
                    actionIconContentColor = GateAccent
                )
            )
        },
        containerColor = GateDark
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GateDark)
        ) {
            when {
                pet == null -> AdventureGateInfo(stringResource(R.string.adventure_gate_no_pet))
                profile == null -> AdventureGateLoading()
                routeBattle != null -> AdventureGateBattleView(
                    battle = routeBattle,
                    profile = profile!!,
                    pet = pet,
                    repository = repository,
                    onCloseBattle = { petId?.let { scope.launch { repository.abandonBattle(it) } } }
                )
                battle != null -> AdventureGateInfo(
                    stringResource(
                        if (mode == AdventureGateScreenMode.NIGHT_ARENA) {
                            R.string.night_arena_other_battle_active
                        } else {
                            R.string.adventure_gate_other_battle_active
                        }
                    )
                )
                mode == AdventureGateScreenMode.NIGHT_ARENA && (!pet.isSleeping || !nightArenaInTimeWindow) -> NightArenaLockedInfo()
                mode == AdventureGateScreenMode.NIGHT_ARENA -> NightArenaHub(
                    profile = profile!!,
                    pet = pet,
                    run = nightArenaRun,
                    nowMillis = nowMillis,
                    onOpenLoadout = { showLoadoutDialog = true },
                    onOpenGear = { showGearClosetDialog = true },
                    onSelectLevel = { level ->
                        val id = petId
                        if (id != null) {
                            scope.launch {
                                val snapshot = repository.startNightArenaBattle(id, level.levelIndex)
                                if (snapshot == null) {
                                    startBattleBlocked = true
                                }
                            }
                        }
                    }
                )
                else -> AdventureGateHub(
                    profile = profile!!,
                    pet = pet,
                    progressRows = progressRows,
                    selectedWorldId = selectedWorldId,
                    onSelectWorld = { world -> selectedWorldId = world.id },
                    onOpenLoadout = { showLoadoutDialog = true },
                    onOpenShop = { showShopDialog = true },
                    onSelectPhase = { phase -> pendingStoryPhase = phase }
                )
            }
            if (showLoadoutDialog && profile != null && petId != null) {
                AdventureGateLoadoutDialog(
                    profile = profile!!,
                    onDismiss = { showLoadoutDialog = false },
                    onSave = { attackIds, magicIds ->
                        scope.launch {
                            repository.updateLoadout(petId, attackIds, magicIds)
                            showLoadoutDialog = false
                        }
                    }
                )
            }
            if (showGearClosetDialog && profile != null && pet != null && petId != null) {
                GearClosetDialog(
                    profile = profile!!,
                    pet = pet,
                    inventory = pet.inventory,
                    onDismiss = { showGearClosetDialog = false },
                    onEquipEquipment = { equipmentId, slot ->
                        scope.launch { repository.equipItem(petId, equipmentId, slot) }
                    },
                    onUnequipEquipment = { slot ->
                        scope.launch { repository.equipItem(petId, null, slot) }
                    },
                    onInspect = { gearInfoDialog = it }
                )
            }
            gearInfoDialog?.let { gear ->
                AlertDialog(
                    onDismissRequest = { gearInfoDialog = null },
                    containerColor = GatePanel,
                    titleContentColor = GateAccent,
                    textContentColor = GateText,
                    title = {
                        Text(
                            text = stringResource(gear.nameRes),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = gearInfoText(context, gear, pet?.inventory?.quantityOf(gear.id) ?: 0 > 0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.heightIn(max = 360.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { gearInfoDialog = null }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    }
                )
            }
            if (showShopDialog && profile != null && pet != null && petId != null) {
                AdventureGateShopDialog(
                    profile = profile!!,
                    pet = pet,
                    inventory = pet.inventory,
                    money = pet.money,
                    progressRows = progressRows,
                    onDismiss = { showShopDialog = false },
                    onBuySupply = { supplyId ->
                        scope.launch { repository.purchaseSupply(petId, supplyId) }
                    },
                    onBuyRecipe = { recipeId ->
                        scope.launch { repository.purchaseRecipe(petId, recipeId) }
                    },
                    onBuySkill = { skillId ->
                        scope.launch { repository.purchaseSkill(petId, skillId) }
                    },
                    onBuyEquipment = { equipmentId ->
                        scope.launch { repository.purchaseEquipment(petId, equipmentId) }
                    },
                    onEquipEquipment = { equipmentId, slot ->
                        scope.launch { repository.equipItem(petId, equipmentId, slot) }
                    },
                    onUnequipEquipment = { slot ->
                        scope.launch { repository.equipItem(petId, null, slot) }
                    },
                    onSellEquipment = { equipmentId ->
                        scope.launch { repository.sellEquipment(petId, equipmentId) }
                    }
                )
            }
            pendingStoryPhase?.let { phase ->
                PhaseStoryDialog(
                    phase = phase,
                    onDismiss = { pendingStoryPhase = null },
                    onStart = {
                        val id = petId
                        pendingStoryPhase = null
                        if (id != null) {
                            scope.launch {
                                val snapshot = repository.startBattle(id, phase.worldId, phase.phaseNumber)
                                if (snapshot == null) {
                                    startBattleBlocked = true
                                }
                            }
                        }
                    }
                )
            }
            if (showInfoDialog) {
                AdventureGateInfoDialog(onDismiss = { showInfoDialog = false })
            }
            if (startBattleBlocked) {
                AlertDialog(
                    onDismissRequest = { startBattleBlocked = false },
                    containerColor = GatePanel,
                    titleContentColor = GateAccent,
                    textContentColor = GateText,
                    title = { Text(stringResource(R.string.adventure_gate_rest_first_title), fontFamily = FontFamily.Monospace) },
                    text = { Text(stringResource(R.string.adventure_gate_rest_first_body), fontFamily = FontFamily.Monospace) },
                    confirmButton = {
                        TextButton(onClick = { startBattleBlocked = false }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    }
                )
            }
        }
        val activeBattle = routeBattle
        if (showRetreatDialog && activeBattle != null && petId != null) {
            val activePhase = activeBattle.phaseOverride
                ?: AdventureGateCatalog.world(activeBattle.worldId).phases[activeBattle.phaseNumber - 1]
            val penalty = AdventureGateCatalog.phaseRetreatPenalty(activePhase)
            AlertDialog(
                onDismissRequest = { showRetreatDialog = false },
                containerColor = GatePanel,
                titleContentColor = GateAccent,
                textContentColor = GateText,
                title = {
                    Text(
                        text = stringResource(R.string.adventure_gate_retreat_confirm_title),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.adventure_gate_retreat_confirm_body, penalty),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRetreatDialog = false
                        scope.launch { repository.abandonBattle(petId, applyRetreatPenalty = true) }
                    }) {
                        Text(stringResource(R.string.adventure_gate_retreat_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRetreatDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun AdventureGateHub(
    profile: AdventureGateProfile,
    pet: com.example.llamadroid.tama.data.TamaPet,
    progressRows: List<AdventureGateWorldProgress>,
    selectedWorldId: String,
    onSelectWorld: (AdventureGateWorldDefinition) -> Unit,
    onOpenLoadout: () -> Unit,
    onOpenShop: () -> Unit,
    onSelectPhase: (AdventureGatePhaseDefinition) -> Unit
) {
    val selectedWorld = AdventureGateCatalog.worlds.firstOrNull { it.id == selectedWorldId }
        ?.takeIf { isWorldUnlocked(it, progressRows) }
        ?: furthestUnlockedAdventureGateWorld(progressRows)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AdventureGateProfileCard(
                profile = profile,
                money = pet.money,
                onOpenLoadout = onOpenLoadout,
                onOpenShop = onOpenShop
            )
        }
        item {
            AdventureGateTravelHub(
                worlds = AdventureGateCatalog.worlds,
                progressRows = progressRows,
                selectedWorldId = selectedWorld.id,
                onSelectWorld = onSelectWorld
            )
        }
        item {
            AdventureGateWorldCard(
                world = selectedWorld,
                progress = progressRows.firstOrNull { it.worldId == selectedWorld.id },
                isUnlocked = isWorldUnlocked(selectedWorld, progressRows),
                onSelectPhase = onSelectPhase
            )
        }
    }
}

@Composable
private fun NightArenaLockedInfo() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            color = GatePanel,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, GateBlue.copy(alpha = 0.42f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.night_arena_locked_title),
                    color = GateAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.night_arena_locked_body),
                    color = GateText.copy(alpha = 0.84f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun NightArenaHub(
    profile: AdventureGateProfile,
    pet: com.example.llamadroid.tama.data.TamaPet,
    run: AdventureGateNightArenaRun?,
    nowMillis: Long,
    onOpenLoadout: () -> Unit,
    onOpenGear: () -> Unit,
    onSelectLevel: (NightArenaLevel) -> Unit
) {
    val resetAt = remember(run?.nightKey, nowMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(NightArenaGenerator.nextResetAtMillis(nowMillis)))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AdventureGateProfileCard(
                profile = profile,
                money = pet.money,
                onOpenLoadout = onOpenLoadout,
                onOpenGear = onOpenGear
            )
        }
        item {
            NightArenaMapCard(
                run = run,
                resetAt = resetAt,
                onSelectLevel = onSelectLevel
            )
        }
    }
}

@Composable
private fun NightArenaMapCard(
    run: AdventureGateNightArenaRun?,
    resetAt: String,
    onSelectLevel: (NightArenaLevel) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GatePanel),
        border = BorderStroke(1.dp, GateBlue.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.night_arena_map_title),
                        color = GateAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.night_arena_reset_at, resetAt),
                        color = GateText.copy(alpha = 0.74f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                run?.let {
                    AdventureGateStatChip(
                        stringResource(R.string.night_arena_levels_cleared),
                        "${it.clearedLevelIds.size}/${it.levels.size}",
                        GateBlue
                    )
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 330.dp, max = 390.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF110D1A))
            ) {
                AsyncImage(
                    model = "file:///android_asset/tama/adventure_gate/maps/night_arena.png",
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                )
                if (run == null) {
                    CircularProgressIndicator(
                        color = GateAccent,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val nodeSize = 64.dp
                    run.levels.forEach { level ->
                        val cleared = level.id in run.clearedLevelIds
                        NightArenaNode(
                            level = level,
                            cleared = cleared,
                            modifier = Modifier
                                .offset(
                                    x = (maxWidth * level.nodeX) - (nodeSize / 2),
                                    y = (maxHeight * level.nodeY) - (nodeSize / 2)
                                )
                                .size(nodeSize),
                            onClick = {
                                if (!cleared) {
                                    onSelectLevel(level)
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
private fun NightArenaNode(
    level: NightArenaLevel,
    cleared: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = !cleared, onClick = onClick),
        color = if (cleared) GateGreen.copy(alpha = 0.90f) else GateAccent.copy(alpha = 0.94f),
        border = BorderStroke(2.dp, GateText.copy(alpha = 0.82f)),
        tonalElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (cleared) "✓" else level.levelIndex.toString(),
                color = Color(0xFF160F20),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AdventureGateTravelHub(
    worlds: List<AdventureGateWorldDefinition>,
    progressRows: List<AdventureGateWorldProgress>,
    selectedWorldId: String,
    onSelectWorld: (AdventureGateWorldDefinition) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GatePanel),
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.38f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = "file:///android_asset/tama/backgrounds/adventure_gate.png",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                GateDark.copy(alpha = 0.32f),
                                GateDark.copy(alpha = 0.74f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = GateDark.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.42f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("*", color = GateAccent, fontSize = 18.sp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.adventure_gate_hub_title),
                                color = GateAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.adventure_gate_hub_body),
                                color = GateText.copy(alpha = 0.78f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    worlds.forEach { world ->
                        AdventureGateWorldPortal(
                            world = world,
                            progress = progressRows.firstOrNull { it.worldId == world.id },
                            selected = world.id == selectedWorldId,
                            unlocked = isWorldUnlocked(world, progressRows),
                            onClick = { onSelectWorld(world) },
                            modifier = Modifier.width(120.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdventureGateWorldPortal(
    world: AdventureGateWorldDefinition,
    progress: AdventureGateWorldProgress?,
    selected: Boolean,
    unlocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cleared = progress?.highestClearedPhase ?: 0
    Surface(
        modifier = modifier
            .height(86.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = unlocked, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) GateAccent.copy(alpha = 0.24f) else GateDark.copy(alpha = 0.58f),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) GateAccent else GateText.copy(alpha = if (unlocked) 0.22f else 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .graphicsLayer(alpha = if (unlocked) 1f else 0.48f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/${world.mapIconAssetPath}",
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Text(
                text = TamaDialogTextCatalog.localizedResource(context, world.nameRes),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (unlocked) {
                    stringResource(R.string.adventure_gate_world_progress_short, cleared, AdventureGateCatalog.PHASES_PER_WORLD)
                } else {
                    stringResource(R.string.adventure_gate_world_locked_short)
                },
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AdventureGateProfileCard(
    profile: AdventureGateProfile,
    money: Long,
    onOpenLoadout: () -> Unit,
    onOpenShop: (() -> Unit)? = null,
    onOpenGear: (() -> Unit)? = null
) {
    val loadout = AdventureGateCatalog.loadoutForProfile(profile)
    val weaponName = loadout.weapon?.nameRes?.let { stringResource(it) } ?: stringResource(R.string.adventure_gate_no_gear_slot)
    val shieldName = loadout.shield?.nameRes?.let { stringResource(it) } ?: stringResource(R.string.adventure_gate_no_gear_slot)
    val ringName = loadout.ring?.nameRes?.let { stringResource(it) } ?: stringResource(R.string.adventure_gate_no_gear_slot)
    val relicName = loadout.relic?.nameRes?.let { stringResource(it) } ?: stringResource(R.string.adventure_gate_no_gear_slot)
    val shieldWeaknesses = loadout.shield?.petWeaknesses?.toList().orEmpty()
    val shieldResistances = loadout.shield?.petResistances?.toList().orEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = GatePanel),
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.adventure_gate_profile_title),
                color = GateAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_hp), "${profile.currentHp}/${profile.stats.maxHp}", GateGreen)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_mana), "${profile.currentMana}/${profile.stats.maxMana}", GateBlue)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdventureGateStatChip(stringResource(R.string.adventure_gate_skill_points_short), profile.skillPoints.toString(), GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_shop_coins, money), "", GateGreen)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), profile.stats.attack.toString(), GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), profile.stats.magic.toString(), GateBlue)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), profile.stats.defense.toString(), GateText)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), profile.stats.speed.toString(), GateGreen)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_accuracy), profile.stats.accuracy.toString(), GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_evasion), profile.stats.evasion.toString(), GateBlue)
            }
            Text(
                text = stringResource(R.string.adventure_gate_current_gear, weaponName, shieldName, ringName, relicName),
                color = GateText.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
            if (shieldWeaknesses.isNotEmpty() || shieldResistances.isNotEmpty()) {
                ElementList(stringResource(R.string.adventure_gate_weak_to), shieldWeaknesses)
                ElementList(stringResource(R.string.adventure_gate_resists), shieldResistances)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenLoadout,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, GateAccent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GateAccent)
                ) {
                    Text(stringResource(R.string.adventure_gate_loadout_button))
                }
                Button(
                    onClick = onOpenShop ?: onOpenGear ?: {},
                    enabled = onOpenShop != null || onOpenGear != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GateAccent, contentColor = GateDark)
                ) {
                    Text(stringResource(if (onOpenGear != null && onOpenShop == null)
                        R.string.adventure_gate_gear_closet else R.string.adventure_gate_shop_button))
                }
            }
        }
    }
}

@Composable
private fun AdventureGateLoadoutDialog(
    profile: AdventureGateProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, List<String>) -> Unit
) {
    var skillInfoDialog by remember { mutableStateOf<AdventureGateSkillDefinition?>(null) }
    var selectedAttacks by remember(profile.petId, profile.level, profile.equippedAttackIds, profile.purchasedSkillIds) {
        mutableStateOf(profile.equippedAttackIds.take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT))
    }
    var selectedMagic by remember(profile.petId, profile.level, profile.equippedMagicIds, profile.purchasedSkillIds) {
        mutableStateOf(
            profile.equippedMagicIds
                .filter { AdventureGateCatalog.skill(it).id != AdventureGateCatalog.ALWAYS_GUARD_SKILL_ID }
                .take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT)
        )
    }
    val learnedAttacks = profile.learnedAttackIds.toSet()
    val learnedMagic = profile.learnedMagicIds.toSet()
    val purchased = profile.purchasedSkillIds.toSet()
    skillInfoDialog?.let { skill ->
        SkillInfoDialog(
            profile = profile,
            skill = skill,
            onDismiss = { skillInfoDialog = null }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(
                text = stringResource(R.string.adventure_gate_loadout_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.adventure_gate_loadout_limit, profile.skillPoints),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                item {
                    LoadoutCounterRow(
                        selectedAttacks = selectedAttacks.size,
                        selectedMagic = selectedMagic.size
                    )
                }
                AdventureGateSkillTreePath.entries.forEach { path ->
                    val pathSkills = AdventureGateCatalog.skills
                        .filter { it.path == path }
                        .sortedWith(compareBy<AdventureGateSkillDefinition> { it.unlockLevel }.thenBy { it.tier }.thenBy { it.id })
                    item {
                        Text(
                            text = stringResource(skillPathNameRes(path)),
                            color = GateAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(pathSkills) { skill ->
                        val isAttack = skill.kind == AdventureGateSkillKind.ATTACK
                        val isGuard = skill.id == AdventureGateCatalog.ALWAYS_GUARD_SKILL_ID
                        val isEquippableMagic = AdventureGateCatalog.isEquippableMagicSkill(skill)
                        val checked = when {
                            isAttack -> skill.id in selectedAttacks
                            isEquippableMagic -> skill.id in selectedMagic
                            isGuard -> true
                            else -> false
                        }
                        val learned = if (isAttack) skill.id in learnedAttacks else skill.id in learnedMagic
                        val bought = skill.id in purchased
                        val slotFull = if (isAttack) {
                            selectedAttacks.size >= AdventureGateCatalog.LOADOUT_ATTACK_LIMIT
                        } else {
                            selectedMagic.size >= AdventureGateCatalog.LOADOUT_MAGIC_LIMIT
                        }
                        val enabled = !isGuard && learned && bought && (checked || !slotFull)
                        SkillLoadoutTreeRow(
                            skill = skill,
                            checked = checked,
                            enabled = enabled,
                            learned = learned,
                            purchased = bought,
                            slotFull = slotFull && !checked,
                            alwaysEquipped = isGuard,
                            onInspect = {
                                skillInfoDialog = skill
                            },
                            onToggle = {
                                if (isAttack) {
                                    selectedAttacks = if (checked) {
                                        selectedAttacks - skill.id
                                    } else {
                                        (selectedAttacks + skill.id).distinct().take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT)
                                    }
                                } else if (isEquippableMagic) {
                                    selectedMagic = if (checked) {
                                        selectedMagic - skill.id
                                    } else {
                                        (selectedMagic + skill.id).distinct().take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedAttacks, selectedMagic) }) {
                Text(stringResource(R.string.adventure_gate_loadout_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AdventureGateShopDialog(
    profile: AdventureGateProfile,
    pet: com.example.llamadroid.tama.data.TamaPet,
    inventory: List<InventoryItem>,
    money: Long,
    progressRows: List<AdventureGateWorldProgress>,
    onDismiss: () -> Unit,
    onBuySupply: (String) -> Unit,
    onBuyRecipe: (String) -> Unit,
    onBuySkill: (String) -> Unit,
    onBuyEquipment: (String) -> Unit,
    onEquipEquipment: (String, AdventureGateEquipmentSlot) -> Unit,
    onUnequipEquipment: (AdventureGateEquipmentSlot) -> Unit,
    onSellEquipment: (String) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var skillInfoDialog by remember { mutableStateOf<AdventureGateSkillDefinition?>(null) }
    var confirmPurchase by remember { mutableStateOf<GatePurchaseConfirmation?>(null) }
    var showGearCloset by rememberSaveable { mutableStateOf(false) }
    val unlockedWorldIndex = highestUnlockedWorldIndex(progressRows)
    if (showGearCloset) {
        GearClosetDialog(
            profile = profile,
            pet = pet,
            inventory = inventory,
            onDismiss = { showGearCloset = false },
            onEquipEquipment = onEquipEquipment,
            onUnequipEquipment = onUnequipEquipment,
            onInspect = { gear ->
                infoDialog = resources.getString(gear.nameRes) to gearInfoText(context, gear, inventory.quantityOf(gear.id) > 0)
            }
        )
    }
    infoDialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            containerColor = GatePanel,
            titleContentColor = GateAccent,
            textContentColor = GateText,
            title = { Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = body,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 360.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { infoDialog = null }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }
    skillInfoDialog?.let { skill ->
        SkillInfoDialog(
            profile = profile,
            skill = skill,
            onDismiss = { skillInfoDialog = null }
        )
    }
    confirmPurchase?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { confirmPurchase = null },
            containerColor = GatePanel,
            titleContentColor = GateAccent,
            textContentColor = GateText,
            title = {
                Text(
                    stringResource(R.string.adventure_gate_shop_confirm_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.adventure_gate_shop_confirm_body, confirmation.title, confirmation.price),
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation.onConfirm()
                    confirmPurchase = null
                }) {
                    Text(stringResource(R.string.tama_farm_store_buy))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurchase = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            color = GatePanel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.34f)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GateDark.copy(alpha = 0.72f))
                        .border(1.dp, GateAccent.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.adventure_gate_shop_title), color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(stringResource(R.string.adventure_gate_shop_coins, money), color = GateText.copy(alpha = 0.76f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = GateText)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        R.string.adventure_gate_shop_tab_supplies,
                        R.string.adventure_gate_shop_tab_skills,
                        R.string.adventure_gate_shop_tab_gear
                    ).forEachIndexed { index, label ->
                        Button(
                            onClick = { selectedTab = index },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedTab == index) GateAccent else GateDark.copy(alpha = 0.82f)
                            ),
                            border = BorderStroke(1.dp, if (selectedTab == index) GateAccent else GateText.copy(alpha = 0.18f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResource(label),
                                color = if (selectedTab == index) GateDark else GateText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            val supplies = AdventureGateCatalog.supplies.filter { it.unlockWorldIndex <= unlockedWorldIndex }
                            if (supplies.isEmpty()) {
                                item { Text(stringResource(R.string.adventure_gate_shop_no_supplies), fontFamily = FontFamily.Monospace) }
                            }
                            items(supplies) { supply ->
                                SupplyShopRow(
                                    supply = supply,
                                    count = inventory.quantityOf(supply.id),
                                    canBuy = money >= supply.price,
                                    onBuy = {
                                        confirmPurchase = GatePurchaseConfirmation(
                                            title = resources.getString(supply.nameRes),
                                            price = resources.getString(R.string.adventure_gate_shop_price, supply.price),
                                            onConfirm = { onBuySupply(supply.id) }
                                        )
                                    },
                                    onInspect = {
                                        infoDialog = resources.getString(supply.nameRes) to supplyInfoText(context, supply, inventory.quantityOf(supply.id))
                                    }
                                )
                            }
                            val recipes = AdventureGateCatalog.recipes.filter { it.unlockWorldIndex <= unlockedWorldIndex }
                            if (recipes.isNotEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(R.string.adventure_gate_shop_recipes_title),
                                        color = GateAccent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                items(recipes) { recipe ->
                                    val supply = AdventureGateCatalog.supply(recipe.supplyId) ?: return@items
                                    val owned = inventory.quantityOf(recipe.id) > 0
                                    RecipeShopRow(
                                        recipe = recipe,
                                        supply = supply,
                                        owned = owned,
                                        canBuy = !owned && money >= recipe.price,
                                        onBuy = {
                                            confirmPurchase = GatePurchaseConfirmation(
                                                title = resources.getString(R.string.adventure_gate_recipe_title, resources.getString(supply.nameRes)),
                                                price = resources.getString(R.string.adventure_gate_shop_price, recipe.price),
                                                onConfirm = { onBuyRecipe(recipe.id) }
                                            )
                                        },
                                        onInspect = {
                                            infoDialog = resources.getString(R.string.adventure_gate_recipe_title, resources.getString(supply.nameRes)) to recipeInfoText(context, recipe)
                                        }
                                    )
                                }
                            }
                        }
                        1 -> {
                            item {
                                SkillTreeHeader(profile = profile)
                            }
                            AdventureGateSkillTreePath.entries.forEach { path ->
                                val pathSkills = AdventureGateCatalog.skills
                                    .filter { it.path == path }
                                    .sortedWith(compareBy<AdventureGateSkillDefinition> { it.unlockLevel }.thenBy { it.tier }.thenBy { it.id })
                                item {
                                    Text(
                                        text = stringResource(skillPathNameRes(path)),
                                        color = GateAccent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                items(pathSkills) { skill ->
                                    SkillShopRow(
                                        profile = profile,
                                        skill = skill,
                                        onBuy = {
                                            confirmPurchase = GatePurchaseConfirmation(
                                                title = resources.getString(skill.nameRes),
                                                price = resources.getString(
                                                    R.string.adventure_gate_skill_shop_price,
                                                    resources.getString(R.string.adventure_gate_buy_skill),
                                                    AdventureGateCatalog.skillPointCost(skill)
                                                ),
                                                onConfirm = { onBuySkill(skill.id) }
                                            )
                                        },
                                        onInspect = {
                                            skillInfoDialog = skill
                                        }
                                    )
                                }
                            }
                        }
                        else -> {
                            val equipment = (AdventureGateCatalog.shopEquipment() + AdventureGateCatalog.bossRelics().filter { inventory.quantityOf(it.id) > 0 })
                                .distinctBy { it.id }
                                .filter { it.uniqueDrop || it.unlockWorldIndex <= unlockedWorldIndex }
                            item {
                                GearClosetLauncher(profile = profile, onOpen = { showGearCloset = true })
                            }
                            if (equipment.isEmpty()) {
                                item { Text(stringResource(R.string.adventure_gate_shop_no_gear), fontFamily = FontFamily.Monospace) }
                            }
                            items(equipment) { gear ->
                                GearShopRow(
                                    gear = gear,
                                    profile = profile,
                                    owned = inventory.quantityOf(gear.id) > 0,
                                    canBuy = !gear.uniqueDrop && money >= gear.price,
                                    locked = !gear.uniqueDrop && gear.unlockWorldIndex > unlockedWorldIndex,
                                    onBuy = {
                                        confirmPurchase = GatePurchaseConfirmation(
                                            title = resources.getString(gear.nameRes),
                                            price = resources.getString(R.string.adventure_gate_shop_price, gear.price),
                                            onConfirm = { onBuyEquipment(gear.id) }
                                        )
                                    },
                                    onSell = { onSellEquipment(gear.id) },
                                    onPreview = { showGearCloset = true },
                                    onInspect = {
                                        infoDialog = resources.getString(gear.nameRes) to gearInfoText(context, gear, inventory.quantityOf(gear.id) > 0)
                                    }
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_ok), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SupplyShopRow(
    supply: AdventureGateSupplyDefinition,
    count: Int,
    canBuy: Boolean,
    onBuy: () -> Unit,
    onInspect: () -> Unit
) {
    ShopRowSurface(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onInspect)) {
        AsyncImage(
            model = "file:///android_asset/${supply.assetPath}",
            contentDescription = stringResource(supply.nameRes),
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(supply.nameRes),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2
            )
            Text(
                text = supplyEffectText(supply),
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2
            )
            Text(
                text = stringResource(R.string.adventure_gate_shop_quantity, count),
                color = GateText.copy(alpha = 0.66f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        Button(
            onClick = onBuy,
            enabled = canBuy,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GateAccent)
        ) {
            Text(stringResource(R.string.adventure_gate_shop_price, supply.price), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeShopRow(
    recipe: AdventureGateRecipeDefinition,
    supply: AdventureGateSupplyDefinition,
    owned: Boolean,
    canBuy: Boolean,
    onBuy: () -> Unit,
    onInspect: () -> Unit
) {
    ShopRowSurface(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onInspect)) {
        AsyncImage(
            model = "file:///android_asset/tama/potions/recipe_scroll.png",
            contentDescription = stringResource(R.string.adventure_gate_recipe_title, stringResource(supply.nameRes)),
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.adventure_gate_recipe_title, stringResource(supply.nameRes)),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 3
            )
            Text(
                text = stringResource(R.string.adventure_gate_recipe_makes, stringResource(supply.nameRes)),
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2
            )
            if (owned) {
                Text(
                    text = stringResource(R.string.adventure_gate_recipe_owned),
                    color = GateAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Button(
            onClick = onBuy,
            enabled = canBuy,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GateAccent)
        ) {
            Text(
                if (owned) stringResource(R.string.adventure_gate_recipe_owned) else stringResource(R.string.adventure_gate_shop_price, recipe.price),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SkillTreeHeader(profile: AdventureGateProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.adventure_gate_skill_tree_title),
            color = GateAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.adventure_gate_skill_tree_hint, profile.skillPoints),
            color = GateText.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillShopRow(
    profile: AdventureGateProfile,
    skill: AdventureGateSkillDefinition,
    onBuy: () -> Unit,
    onInspect: () -> Unit
) {
    val purchased = skill.id in profile.purchasedSkillIds
    val locked = profile.level < skill.unlockLevel
    val missingPrerequisites = skill.prerequisiteSkillIds.filterNot { it in profile.purchasedSkillIds }
    val cost = AdventureGateCatalog.skillPointCost(skill)
    val prerequisiteLocked = missingPrerequisites.isNotEmpty()
    val missingPrerequisiteNames = missingPrerequisites
        .map { id -> stringResource(AdventureGateCatalog.skill(id).nameRes) }
        .joinToString()
    val canBuy = !purchased && !locked && !prerequisiteLocked && profile.skillPoints >= cost
    ShopRowSurface(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onInspect)) {
        SkillIcon(skill, modifier = Modifier.size(36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(skill.nameRes),
                color = if (locked) GateText.copy(alpha = 0.52f) else GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2
            )
            Text(
                text = stringResource(skill.descriptionRes),
                color = GateText.copy(alpha = 0.68f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 3
            )
        }
        TextButton(
            onClick = onBuy,
            enabled = canBuy
        ) {
            Text(
                text = when {
                    purchased -> stringResource(R.string.adventure_gate_shop_owned)
                    locked -> stringResource(R.string.adventure_gate_shop_level_locked, skill.unlockLevel)
                    prerequisiteLocked -> stringResource(
                        R.string.adventure_gate_shop_prerequisite_locked,
                        missingPrerequisiteNames
                    )
                    else -> stringResource(R.string.adventure_gate_skill_shop_price, stringResource(R.string.adventure_gate_buy_skill), cost)
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearShopRow(
    gear: AdventureGateEquipmentDefinition,
    profile: AdventureGateProfile,
    owned: Boolean,
    canBuy: Boolean,
    locked: Boolean,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    onPreview: () -> Unit,
    onInspect: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val equipped = when (gear.slot) {
        AdventureGateEquipmentSlot.WEAPON -> profile.equippedWeaponId == gear.id
        AdventureGateEquipmentSlot.SHIELD -> profile.equippedShieldId == gear.id
        AdventureGateEquipmentSlot.RING -> profile.equippedRingId == gear.id
        AdventureGateEquipmentSlot.RELIC -> profile.equippedRelicId == gear.id
    }
    ShopRowSurface(modifier = Modifier.combinedClickable(onClick = onPreview, onLongClick = onInspect)) {
        AsyncImage(
            model = "file:///android_asset/${gear.assetPath}",
            contentDescription = stringResource(gear.nameRes),
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(gear.nameRes),
                color = if (locked) GateText.copy(alpha = 0.48f) else GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdventureGateStatChip(slotLabel(gear.slot), gearBonusShort(gear), GateBlue)
            }
            if (gear.slot == AdventureGateEquipmentSlot.SHIELD && (gear.petWeaknesses.isNotEmpty() || gear.petResistances.isNotEmpty())) {
                Text(
                    text = stringResource(R.string.adventure_gate_weak_to) + ": " + gear.petWeaknesses.joinToString { resources.getString(elementNameRes(it)) },
                    color = GateDanger.copy(alpha = 0.84f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.adventure_gate_resists) + ": " + gear.petResistances.joinToString { resources.getString(elementNameRes(it)) },
                    color = GateGreen.copy(alpha = 0.84f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        when {
            locked -> Text(stringResource(R.string.adventure_gate_shop_locked_world, gear.unlockWorldIndex + 1), color = GateText.copy(alpha = 0.56f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            equipped -> Text(stringResource(R.string.adventure_gate_shop_equipped), color = GateGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            owned -> Column(horizontalAlignment = Alignment.End) {
                if (!gear.uniqueDrop) {
                    TextButton(onClick = onSell) { Text(stringResource(R.string.adventure_gate_shop_sell), fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
            else -> Button(
                onClick = onBuy,
                enabled = canBuy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GateAccent)
            ) {
                Text(stringResource(R.string.adventure_gate_shop_price, gear.price), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GearClosetLauncher(
    profile: AdventureGateProfile,
    onOpen: () -> Unit
) {
    val current = AdventureGateCombatEngine.normalizedProfile(profile).stats
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GateDark.copy(alpha = 0.65f))
            .border(1.dp, GateAccent.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.adventure_gate_gear_closet), color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.adventure_gate_gear_closet_hint),
                    color = GateText.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = GateAccent),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.adventure_gate_gear_closet_open), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            GearSlotChip(R.string.adventure_gate_slot_weapon, profile.equippedWeaponId)
            GearSlotChip(R.string.adventure_gate_slot_shield, profile.equippedShieldId)
            GearSlotChip(R.string.adventure_gate_slot_ring, profile.equippedRingId)
            GearSlotChip(R.string.adventure_gate_slot_relic, profile.equippedRelicId)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_hp), "${profile.currentHp}/${current.maxHp}", GateGreen)
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_mana), "${profile.currentMana}/${current.maxMana}", GateBlue)
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), "${current.attack}", GateText.copy(alpha = 0.72f))
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), "${current.magic}", GateText.copy(alpha = 0.72f))
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), "${current.defense}", GateText.copy(alpha = 0.72f))
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), "${current.speed}", GateText.copy(alpha = 0.72f))
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_accuracy), "${current.accuracy}", GateText.copy(alpha = 0.72f))
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_evasion), "${current.evasion}", GateText.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun GearClosetDialog(
    profile: AdventureGateProfile,
    pet: com.example.llamadroid.tama.data.TamaPet,
    inventory: List<InventoryItem>,
    onDismiss: () -> Unit,
    onEquipEquipment: (String, AdventureGateEquipmentSlot) -> Unit,
    onUnequipEquipment: (AdventureGateEquipmentSlot) -> Unit,
    onInspect: (AdventureGateEquipmentDefinition) -> Unit
) {
    var selectedSlot by rememberSaveable { mutableStateOf(AdventureGateEquipmentSlot.WEAPON.name) }
    var previewGearId by rememberSaveable { mutableStateOf<String?>(profile.equippedWeaponId) }
    val slot = AdventureGateEquipmentSlot.valueOf(selectedSlot)
    val currentProfile = AdventureGateCombatEngine.normalizedProfile(profile)
    val currentStats = currentProfile.stats
    val currentSlotGearId = profile.equippedGearId(slot)
    val previewGear = previewGearId?.let(AdventureGateCatalog::equipment)?.takeIf { it.slot == slot && inventory.quantityOf(it.id) > 0 }
    val previewProfile = when {
        previewGear != null -> profile.withPreviewGear(previewGear)
        previewGearId == null -> profile.withPreviewUnequipped(slot)
        else -> profile
    }.let(AdventureGateCombatEngine::normalizedProfile)
    val previewStats = previewProfile.stats
    val ownedForSlot = AdventureGateCatalog.equipment
        .filter { it.slot == slot && inventory.quantityOf(it.id) > 0 }
        .sortedWith(compareBy<AdventureGateEquipmentDefinition> { it.uniqueDrop }.thenBy { it.unlockWorldIndex }.thenBy { it.id })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            color = GatePanel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.32f)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.adventure_gate_gear_closet),
                            color = GateAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.adventure_gate_gear_closet_subtitle),
                            color = GateText.copy(alpha = 0.72f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = GateText)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        GearClosetPetPanel(
                            pet = pet,
                            profile = profile,
                            selectedSlot = slot,
                            onSelectSlot = { option ->
                                selectedSlot = option.name
                                previewGearId = profile.equippedGearId(option)
                            }
                        )
                    }
                    item {
                        GearClosetStatsPanel(
                            current = currentStats,
                            preview = previewStats,
                            currentHp = profile.currentHp,
                            currentMana = profile.currentMana,
                            shield = previewProfile.equippedShieldId?.let(AdventureGateCatalog::equipment)
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.adventure_gate_gear_owned_for_slot, slotLabel(slot)),
                                color = GateAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${ownedForSlot.size}",
                                color = GateBlue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(GateDark.copy(alpha = 0.72f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (ownedForSlot.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(GateDark.copy(alpha = 0.5f))
                                    .border(1.dp, GateText.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    stringResource(R.string.adventure_gate_gear_no_owned_slot),
                                    color = GateText.copy(alpha = 0.72f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    items(ownedForSlot) { gear ->
                        GearClosetOwnedRow(
                            gear = gear,
                            selected = previewGearId == gear.id,
                            equipped = currentSlotGearId == gear.id,
                            onSelect = { previewGearId = gear.id },
                            onInspect = { onInspect(gear) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { previewGear?.let { onEquipEquipment(it.id, slot) } },
                        enabled = previewGear != null && previewGear.id != currentSlotGearId,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GateAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.adventure_gate_shop_equip), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            previewGearId = null
                            onUnequipEquipment(slot)
                        },
                        enabled = currentSlotGearId != null,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, GateDanger.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.adventure_gate_shop_unequip), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = { previewGearId = currentSlotGearId },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.adventure_gate_gear_cancel_preview),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GearClosetPetPanel(
    pet: com.example.llamadroid.tama.data.TamaPet,
    profile: AdventureGateProfile,
    selectedSlot: AdventureGateEquipmentSlot,
    onSelectSlot: (AdventureGateEquipmentSlot) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(GateDark.copy(alpha = 0.92f), GateDark.copy(alpha = 0.62f))
                )
            )
            .border(1.dp, GateAccent.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            pet.name,
            color = GateAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GearClosetSlotSocket(
                slot = AdventureGateEquipmentSlot.WEAPON,
                gearId = profile.equippedWeaponId,
                selected = selectedSlot == AdventureGateEquipmentSlot.WEAPON,
                onClick = { onSelectSlot(AdventureGateEquipmentSlot.WEAPON) },
                modifier = Modifier.weight(1f)
            )
            GearClosetSlotSocket(
                slot = AdventureGateEquipmentSlot.SHIELD,
                gearId = profile.equippedShieldId,
                selected = selectedSlot == AdventureGateEquipmentSlot.SHIELD,
                onClick = { onSelectSlot(AdventureGateEquipmentSlot.SHIELD) },
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            modifier = Modifier
                .size(width = 132.dp, height = 96.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(GateAccent.copy(alpha = 0.08f))
                .border(1.dp, GateBlue.copy(alpha = 0.24f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center
        ) {
            TamaPetSprite(pet = pet, size = 90.dp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GearClosetSlotSocket(
                slot = AdventureGateEquipmentSlot.RING,
                gearId = profile.equippedRingId,
                selected = selectedSlot == AdventureGateEquipmentSlot.RING,
                onClick = { onSelectSlot(AdventureGateEquipmentSlot.RING) },
                modifier = Modifier.weight(1f)
            )
            GearClosetSlotSocket(
                slot = AdventureGateEquipmentSlot.RELIC,
                gearId = profile.equippedRelicId,
                selected = selectedSlot == AdventureGateEquipmentSlot.RELIC,
                onClick = { onSelectSlot(AdventureGateEquipmentSlot.RELIC) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GearClosetSlotSocket(
    slot: AdventureGateEquipmentSlot,
    gearId: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gear = gearId?.let(AdventureGateCatalog::equipment)
    Column(
        modifier = modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) GateAccent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.2f))
            .border(
                1.dp,
                if (selected) GateAccent else GateText.copy(alpha = 0.16f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (gear != null) {
            AsyncImage(
                model = "file:///android_asset/${gear.assetPath}",
                contentDescription = stringResource(gear.nameRes),
                modifier = Modifier.size(34.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GateText.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text("-", color = GateText.copy(alpha = 0.58f), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            }
        }
        Text(
            slotLabel(slot),
            color = if (selected) GateAccent else GateText.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            gear?.let { stringResource(it.nameRes) } ?: "-",
            color = GateText.copy(alpha = 0.68f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GearClosetStatsPanel(
    current: com.example.llamadroid.tama.rpg.AdventureGateStats,
    preview: com.example.llamadroid.tama.rpg.AdventureGateStats,
    currentHp: Int,
    currentMana: Int,
    shield: AdventureGateEquipmentDefinition?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GateDark.copy(alpha = 0.62f))
            .border(1.dp, GateBlue.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.adventure_gate_gear_stats_preview), color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_hp), "$currentHp/${preview.maxHp}", GateGreen)
            AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_mana), "$currentMana/${preview.maxMana}", GateBlue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StatDeltaTile(R.string.adventure_gate_stat_hp, current.maxHp, preview.maxHp, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_mana, current.maxMana, preview.maxMana, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_attack, current.attack, preview.attack, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_magic, current.magic, preview.magic, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StatDeltaTile(R.string.adventure_gate_stat_defense, current.defense, preview.defense, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_speed, current.speed, preview.speed, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_accuracy, current.accuracy, preview.accuracy, Modifier.weight(1f))
            StatDeltaTile(R.string.adventure_gate_stat_evasion, current.evasion, preview.evasion, Modifier.weight(1f))
        }
        shield?.let {
            if (it.petWeaknesses.isNotEmpty()) ElementList(stringResource(R.string.adventure_gate_weak_to), it.petWeaknesses.toList())
            if (it.petResistances.isNotEmpty()) ElementList(stringResource(R.string.adventure_gate_resists), it.petResistances.toList())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearClosetOwnedRow(
    gear: AdventureGateEquipmentDefinition,
    selected: Boolean,
    equipped: Boolean,
    onSelect: () -> Unit,
    onInspect: () -> Unit
) {
    ShopRowSurface(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = when {
                    selected -> GateAccent
                    equipped -> GateGreen.copy(alpha = 0.65f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(onClick = onSelect, onLongClick = onInspect)
    ) {
        AsyncImage(
            model = "file:///android_asset/${gear.assetPath}",
            contentDescription = stringResource(gear.nameRes),
            modifier = Modifier.size(44.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(gear.nameRes),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gearBonusShort(gear),
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            equipped -> AdventureGateStatChip(stringResource(R.string.adventure_gate_shop_equipped), "", GateGreen)
            selected -> AdventureGateStatChip(stringResource(R.string.adventure_gate_gear_previewing), "", GateAccent)
            else -> AdventureGateStatChip(stringResource(R.string.adventure_gate_gear_tap_preview), "", GateBlue)
        }
    }
}

private fun AdventureGateProfile.equippedGearId(slot: AdventureGateEquipmentSlot): String? = when (slot) {
    AdventureGateEquipmentSlot.WEAPON -> equippedWeaponId
    AdventureGateEquipmentSlot.SHIELD -> equippedShieldId
    AdventureGateEquipmentSlot.RING -> equippedRingId
    AdventureGateEquipmentSlot.RELIC -> equippedRelicId
}

private fun AdventureGateProfile.withPreviewGear(gear: AdventureGateEquipmentDefinition): AdventureGateProfile = when (gear.slot) {
    AdventureGateEquipmentSlot.WEAPON -> copy(equippedWeaponId = gear.id)
    AdventureGateEquipmentSlot.SHIELD -> copy(equippedShieldId = gear.id)
    AdventureGateEquipmentSlot.RING -> copy(equippedRingId = gear.id)
    AdventureGateEquipmentSlot.RELIC -> copy(equippedRelicId = gear.id)
}

private fun AdventureGateProfile.withPreviewUnequipped(slot: AdventureGateEquipmentSlot): AdventureGateProfile = when (slot) {
    AdventureGateEquipmentSlot.WEAPON -> copy(equippedWeaponId = null)
    AdventureGateEquipmentSlot.SHIELD -> copy(equippedShieldId = null)
    AdventureGateEquipmentSlot.RING -> copy(equippedRingId = null)
    AdventureGateEquipmentSlot.RELIC -> copy(equippedRelicId = null)
}

@Composable
private fun GearSlotChip(labelRes: Int, gearId: String?) {
    AdventureGateStatChip(
        label = stringResource(labelRes),
        value = gearId?.let(AdventureGateCatalog::equipment)?.let { stringResource(it.nameRes) } ?: "-",
        color = if (gearId == null) GateText.copy(alpha = 0.54f) else GateBlue
    )
}

@Composable
private fun StatDeltaTile(labelRes: Int, current: Int, preview: Int, modifier: Modifier = Modifier) {
    val delta = preview - current
    val color = when {
        delta > 0 -> GateGreen
        delta < 0 -> GateDanger
        else -> GateText.copy(alpha = 0.72f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GateText.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(labelRes),
            color = GateText.copy(alpha = 0.62f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            preview.toString(),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (delta == 0) " " else signed(delta),
            color = color.copy(alpha = if (delta == 0) 0f else 1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ShopRowSurface(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun LoadoutCounterRow(
    selectedAttacks: Int,
    selectedMagic: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GateDark.copy(alpha = 0.46f))
            .border(1.dp, GateAccent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AdventureGateStatChip(
            label = stringResource(R.string.adventure_gate_loadout_attacks),
            value = "$selectedAttacks/${AdventureGateCatalog.LOADOUT_ATTACK_LIMIT}",
            color = GateAccent
        )
        AdventureGateStatChip(
            label = stringResource(R.string.adventure_gate_loadout_magic),
            value = "$selectedMagic/${AdventureGateCatalog.LOADOUT_MAGIC_LIMIT}",
            color = GateBlue
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillLoadoutTreeRow(
    skill: AdventureGateSkillDefinition,
    checked: Boolean,
    enabled: Boolean,
    learned: Boolean,
    purchased: Boolean,
    slotFull: Boolean,
    alwaysEquipped: Boolean,
    onInspect: () -> Unit,
    onToggle: () -> Unit
) {
    val cost = AdventureGateCatalog.skillPointCost(skill)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    checked -> GateAccent.copy(alpha = 0.16f)
                    purchased && learned -> Color.Black.copy(alpha = 0.18f)
                    else -> Color.Black.copy(alpha = 0.10f)
                }
            )
            .border(
                1.dp,
                if (checked) GateAccent.copy(alpha = 0.72f) else GateText.copy(alpha = 0.08f),
                RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                enabled = true,
                onClick = { if (enabled || checked) onToggle() },
                onLongClick = onInspect
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled || checked) onToggle() },
            enabled = (enabled || checked) && !alwaysEquipped
        )
        SkillIcon(skill, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(skill.nameRes),
                color = if (!learned || !purchased) GateText.copy(alpha = 0.58f) else GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(skill.descriptionRes),
                color = GateText.copy(alpha = if (!learned || !purchased) 0.44f else 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when {
                alwaysEquipped -> stringResource(R.string.adventure_gate_loadout_always)
                checked -> stringResource(R.string.adventure_gate_loadout_equipped)
                !learned -> stringResource(R.string.adventure_gate_shop_level_locked, skill.unlockLevel)
                !purchased -> stringResource(R.string.adventure_gate_loadout_not_bought, cost)
                slotFull -> stringResource(R.string.adventure_gate_loadout_slot_full)
                skill.manaCost > 0 -> stringResource(R.string.adventure_gate_skill_mana_short, skill.manaCost)
                else -> stringResource(R.string.adventure_gate_skill_free_short)
            },
            color = when {
                checked || alwaysEquipped -> GateAccent
                !learned || !purchased || slotFull -> GateText.copy(alpha = 0.52f)
                skill.manaCost > 0 -> GateBlue
                else -> GateAccent
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 92.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AdventureGateWorldCard(
    world: AdventureGateWorldDefinition,
    progress: AdventureGateWorldProgress?,
    isUnlocked: Boolean,
    onSelectPhase: (AdventureGatePhaseDefinition) -> Unit
) {
    val context = LocalContext.current
    val cleared = progress?.highestClearedPhase ?: 0
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isUnlocked) GatePanel else GatePanel.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, GateAccent.copy(alpha = if (isUnlocked) 0.36f else 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = "file:///android_asset/${world.mapIconAssetPath}",
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = TamaDialogTextCatalog.localizedResource(context, world.nameRes),
                        color = if (isUnlocked) GateAccent else GateText.copy(alpha = 0.45f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.adventure_gate_world_progress, cleared, AdventureGateCatalog.PHASES_PER_WORLD),
                        color = GateText.copy(alpha = 0.76f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = TamaDialogTextCatalog.localizedResource(context, world.descriptionRes),
                color = GateText.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            AdventureWorldMap(world, cleared, isUnlocked, onSelectPhase)
        }
    }
}

@Composable
private fun AdventureWorldMap(
    world: AdventureGateWorldDefinition,
    cleared: Int,
    isWorldUnlocked: Boolean,
    onSelectPhase: (AdventureGatePhaseDefinition) -> Unit
) {
    val nodePositions = remember {
        listOf(
            0.11f to 0.82f,
            0.25f to 0.76f,
            0.38f to 0.69f,
            0.52f to 0.73f,
            0.66f to 0.64f,
            0.79f to 0.55f,
            0.63f to 0.44f,
            0.48f to 0.37f,
            0.33f to 0.42f,
            0.19f to 0.32f,
            0.32f to 0.21f,
            0.47f to 0.19f,
            0.62f to 0.25f,
            0.76f to 0.18f,
            0.86f to 0.08f
        )
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.20f))
    ) {
        AsyncImage(
            model = "file:///android_asset/${world.worldMapAssetPath}",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isWorldUnlocked) 0.10f else 0.42f)))
        world.phases.forEachIndexed { index, phase ->
            val enabled = isWorldUnlocked && phase.phaseNumber <= cleared + 1
            val clearedPhase = phase.phaseNumber <= cleared
            val (x, y) = nodePositions[index]
            val nodeSize = if (phase.isBoss) 48.dp else 38.dp
            PhaseNode(
                phase = phase,
                enabled = enabled,
                cleared = clearedPhase,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (maxWidth - nodeSize) * x,
                        y = (maxHeight - nodeSize) * y
                    ),
                onClick = { onSelectPhase(phase) }
            )
        }
    }
}

@Composable
private fun PhaseNode(
    phase: AdventureGatePhaseDefinition,
    enabled: Boolean,
    cleared: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val size = if (phase.isBoss) 48.dp else 38.dp
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(999.dp),
        color = when {
            !enabled -> GateDark.copy(alpha = 0.78f)
            cleared -> GateGreen.copy(alpha = 0.88f)
            phase.isBoss -> GateDanger.copy(alpha = 0.92f)
            else -> GateAccent.copy(alpha = 0.92f)
        },
        border = BorderStroke(2.dp, GateText.copy(alpha = if (enabled) 0.8f else 0.24f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (phase.isBoss) stringResource(R.string.adventure_gate_phase_boss_short, phase.phaseNumber) else phase.phaseNumber.toString(),
                color = if (enabled) GateDark else GateText.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (phase.isBoss) 11.sp else 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AdventureGateBattleView(
    battle: AdventureGateBattleSnapshot,
    profile: AdventureGateProfile,
    pet: com.example.llamadroid.tama.data.TamaPet,
    repository: AdventureGateRepository,
    onCloseBattle: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var selectedTargetId by rememberSaveable(battle.petId, battle.worldId, battle.phaseNumber, battle.waveIndex) {
        mutableStateOf(battle.enemies.firstOrNull { it.isAlive }?.instanceId)
    }
    var locked by rememberSaveable { mutableStateOf(false) }
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showItemsDialog by rememberSaveable { mutableStateOf(false) }
    var inspectedEnemy by remember { mutableStateOf<AdventureGateCombatantState?>(null) }
    var inspectedMinion by remember { mutableStateOf(false) }
    var inspectedPet by remember { mutableStateOf(false) }
    var battleInfoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var skillInfoDialog by remember { mutableStateOf<AdventureGateSkillDefinition?>(null) }
    var damagePopups by remember { mutableStateOf<List<DamagePopup>>(emptyList()) }
    var popupSequence by remember { mutableStateOf(0L) }
    var displayedBattle by remember { mutableStateOf(battle) }
    var activeActorId by remember { mutableStateOf<String?>(null) }
    var speedUpTurns by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(battle.updatedAt, battle.log.size, locked) {
        if (!locked) displayedBattle = battle
    }
    val viewBattle = if (locked) displayedBattle else battle
    LaunchedEffect(battle.enemies.map { it.instanceId to it.hp }, battle.minion?.instanceId, battle.minion?.hp) {
        val selectedEnemyAlive = battle.enemies.any { it.instanceId == selectedTargetId && it.isAlive }
        val selectedAllyAlive = battle.minion?.instanceId == selectedTargetId && battle.minion?.isAlive == true
        if (!selectedEnemyAlive && !selectedAllyAlive) {
            selectedTargetId = battle.enemies.firstOrNull { it.isAlive }?.instanceId
        }
    }
    LaunchedEffect(locked) {
        if (locked) listState.animateScrollToItem(1)
    }
    val phase = battle.phaseOverride
        ?: AdventureGateCatalog.world(battle.worldId).phases[battle.phaseNumber - 1]
    val battleTitle = if (battle.worldId == AdventureGateCatalog.NIGHT_ARENA_WORLD_ID) {
        stringResource(R.string.night_arena_battle_header, battle.phaseNumber)
    } else {
        val world = AdventureGateCatalog.world(battle.worldId)
        stringResource(
            R.string.adventure_gate_battle_header,
            TamaDialogTextCatalog.localizedResource(context, world.nameRes),
            battle.phaseNumber
        )
    }
    var activeEffectSkillId by remember { mutableStateOf<String?>(null) }
    var activeEffectFrame by remember { mutableStateOf(0) }
    var activeEffectSequence by remember { mutableStateOf(0) }
    LaunchedEffect(activeEffectSequence) {
        if (activeEffectSkillId != null) {
            activeEffectFrame = 0
            delay(130)
            activeEffectFrame = 1
            delay(130)
            activeEffectFrame = 2
            delay(240)
            activeEffectSkillId = null
        }
    }

    fun AdventureGateBattleSnapshot.withUpdatedCombatant(
        instanceId: String?,
        transform: (AdventureGateCombatantState) -> AdventureGateCombatantState
    ): AdventureGateBattleSnapshot {
        if (instanceId == null) return this
        return when (instanceId) {
            this.pet.instanceId -> copy(pet = transform(this.pet))
            minion?.instanceId -> copy(minion = minion?.let(transform))
            else -> copy(enemies = enemies.map { enemy ->
                if (enemy.instanceId == instanceId) transform(enemy) else enemy
            })
        }
    }

    fun manaCostForEvent(event: AdventureGateBattleEvent): Int {
        val skillId = event.skillId ?: return 0
        return when {
            AdventureGateCatalog.skills.any { it.id == skillId } -> AdventureGateCatalog.skill(skillId).manaCost
            else -> runCatching { AdventureGateCatalog.enemyAction(skillId).manaCost }.getOrDefault(0)
        }
    }

    fun applyEventToDisplayedBattle(event: AdventureGateBattleEvent) {
        val manaCost = manaCostForEvent(event)
        if (manaCost > 0 && event.actorInstanceId != null) {
            displayedBattle = displayedBattle.withUpdatedCombatant(event.actorInstanceId) { actor ->
                actor.copy(mana = (actor.mana - manaCost).coerceAtLeast(0))
            }
        }
        displayedBattle = when (event.type) {
            AdventureGateBattleEventType.DAMAGE,
            AdventureGateBattleEventType.STATUS_DAMAGE -> displayedBattle.withUpdatedCombatant(event.targetInstanceId) { target ->
                target.copy(hp = (target.hp - event.amount).coerceAtLeast(0))
            }
            AdventureGateBattleEventType.HEAL -> displayedBattle.withUpdatedCombatant(event.targetInstanceId ?: event.actorInstanceId) { target ->
                target.copy(hp = (target.hp + event.amount).coerceAtMost(target.maxHp))
            }
            AdventureGateBattleEventType.GUARD -> displayedBattle.withUpdatedCombatant(event.targetInstanceId ?: event.actorInstanceId) { target ->
                target.copy(mana = (target.mana + event.amount).coerceAtMost(target.maxMana))
            }
            AdventureGateBattleEventType.ITEM_USED -> {
                val supply = AdventureGateCatalog.supply(event.itemId.orEmpty())
                displayedBattle.withUpdatedCombatant(event.targetInstanceId ?: event.actorInstanceId) { target ->
                    when (supply?.kind) {
                        AdventureGateSupplyKind.MANA -> target.copy(mana = (target.mana + event.amount).coerceAtMost(target.maxMana))
                        AdventureGateSupplyKind.CLEANSE -> target.copy(statuses = target.statuses.filterNot { it.id in AdventureGateCatalog.badStatusIds })
                        else -> target.copy(hp = (target.hp + event.amount).coerceAtMost(target.maxHp))
                    }
                }
            }
            AdventureGateBattleEventType.EQUIPMENT_TRIGGER -> {
                if (event.targetInstanceId != null) {
                    displayedBattle.withUpdatedCombatant(event.targetInstanceId) { target ->
                        target.copy(hp = (target.hp - event.amount).coerceAtLeast(0))
                    }
                } else {
                    displayedBattle.withUpdatedCombatant(event.actorInstanceId) { target ->
                        target.copy(hp = (target.hp + event.amount).coerceAtMost(target.maxHp))
                    }
                }
            }
            else -> displayedBattle
        }
    }

    suspend fun playBattleEvents(
        events: List<AdventureGateBattleEvent>,
        finalSnapshot: AdventureGateBattleSnapshot? = null
    ) {
        var playedVisibleBeat = false
        events.forEach { event ->
            activeActorId = event.actorInstanceId
            if (event.skillId != null && AdventureGateCatalog.skills.any { it.id == event.skillId }) {
                activeEffectSkillId = event.skillId
                activeEffectSequence += 1
            }
            applyEventToDisplayedBattle(event)
            val fallbackTargetId = when (event.type) {
                AdventureGateBattleEventType.HEAL,
                AdventureGateBattleEventType.GUARD,
                AdventureGateBattleEventType.ITEM_USED,
                AdventureGateBattleEventType.EQUIPMENT_TRIGGER -> event.actorInstanceId
                else -> null
            }
            val targetId = event.targetInstanceId ?: fallbackTargetId
            val hasPopup = targetId != null && (event.amount > 0 || event.type == AdventureGateBattleEventType.MISS)
            if (hasPopup && targetId != null) {
                popupSequence += 1
                val healingPopup = event.type == AdventureGateBattleEventType.HEAL ||
                    event.type == AdventureGateBattleEventType.ITEM_USED ||
                    event.type == AdventureGateBattleEventType.GUARD ||
                    (event.type == AdventureGateBattleEventType.EQUIPMENT_TRIGGER && event.targetInstanceId == null)
                val popupColor = when (event.type) {
                    AdventureGateBattleEventType.HEAL,
                    AdventureGateBattleEventType.ITEM_USED -> GateGreen
                    AdventureGateBattleEventType.GUARD -> GateBlue
                    AdventureGateBattleEventType.STATUS_DAMAGE -> statusDamageColor(event.statusId)
                    AdventureGateBattleEventType.EQUIPMENT_TRIGGER -> if (event.targetInstanceId == null) GateGreen else GateAccent
                    AdventureGateBattleEventType.MISS -> GateAccent
                    else -> GateDanger
                }
                val popup = DamagePopup(
                    id = popupSequence,
                    targetInstanceId = targetId,
                    amount = event.amount,
                    healing = healingPopup,
                    label = if (event.type == AdventureGateBattleEventType.MISS) resources.getString(R.string.adventure_gate_miss_popup) else null,
                    color = popupColor
                )
                damagePopups = damagePopups + popup
                delay(if (speedUpTurns) 260 else 1_050)
                damagePopups = damagePopups.filterNot { it.id == popup.id }
                delay(if (speedUpTurns) 40 else 180)
                playedVisibleBeat = true
            } else if (event.type == AdventureGateBattleEventType.SUMMON) {
                delay(if (speedUpTurns) 500 else 620)
                finalSnapshot?.minion?.let { minion ->
                    displayedBattle = displayedBattle.copy(minion = minion)
                }
                delay(if (speedUpTurns) 120 else 360)
                playedVisibleBeat = true
            } else if (event.type == AdventureGateBattleEventType.STATUS_SKIP ||
                event.type == AdventureGateBattleEventType.WAVE_STARTED ||
                event.type == AdventureGateBattleEventType.VICTORY ||
                event.type == AdventureGateBattleEventType.DEFEAT
            ) {
                delay(if (speedUpTurns) 180 else 700)
                playedVisibleBeat = true
            }
            activeActorId = null
        }
        if (!playedVisibleBeat) {
            delay(if (speedUpTurns) 120 else 350)
        }
        activeActorId = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = if (actionsExpanded) 142.dp else 76.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Text(
                text = battleTitle,
                color = GateAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 310.dp, max = 360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF241830))
            ) {
                AsyncImage(
                    model = "file:///android_asset/${phase.backgroundAssetPath}",
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))
                BattleWaveChip(
                    currentWave = viewBattle.waveIndex + 1,
                    totalWaves = phase.waveMonsterIds.size,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        modifier = Modifier.width(ArenaCombatantCardWidth),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        viewBattle.minion?.takeIf { it.isAlive }?.let { minion ->
                            SkeletonHelperCard(
                                combatant = minion,
                                selected = selectedTargetId == minion.instanceId,
                                active = activeActorId == minion.instanceId,
                                damagePopup = damagePopups.lastOrNull { it.targetInstanceId == minion.instanceId },
                                onSelect = {
                                    if (!locked) selectedTargetId = minion.instanceId
                                },
                                onInspect = { inspectedMinion = true }
                            )
                        }
                        ArenaPetCard(
                            pet = pet,
                            combatant = viewBattle.pet,
                            active = activeActorId == viewBattle.pet.instanceId,
                            damagePopup = damagePopups.lastOrNull { it.targetInstanceId == viewBattle.pet.instanceId },
                            onInspect = { inspectedPet = true }
                        )
                    }
                    EnemyGridLayout(
                        enemies = viewBattle.enemies,
                        selectedTargetId = selectedTargetId,
                        activeActorId = activeActorId,
                        damagePopups = damagePopups,
                        modifier = Modifier,
                        onSelect = { enemy ->
                            if (enemy.isAlive && !locked) selectedTargetId = enemy.instanceId
                        },
                        onInspect = { inspectedEnemy = it }
                    )
                }
                activeEffectSkillId?.let { skillId ->
                    AsyncImage(
                        model = "file:///android_asset/${AdventureGateCatalog.effectFrameAssetPath(skillId, activeEffectFrame)}",
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(124.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
                if (viewBattle.isCompleted) {
                    BattleRewardOverlay(
                        battle = viewBattle,
                        phase = phase,
                        onClaim = onCloseBattle,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                    )
                }
            }
        }
        item {
            StatusPanel(viewBattle)
        }
        item {
            BattleLogPanel(viewBattle.log)
        }
    }
        if (!viewBattle.isCompleted) {
            SkillActionDock(
                profile = profile,
                battle = battle,
                inventory = pet.inventory,
                selectedTargetId = selectedTargetId,
                locked = locked,
                expanded = actionsExpanded,
                modifier = Modifier.align(Alignment.BottomCenter),
                onToggleExpanded = { actionsExpanded = !actionsExpanded },
                onOpenItems = {
                    if (!locked && battle.turn == com.example.llamadroid.tama.rpg.AdventureGateTurn.PET) {
                        actionsExpanded = false
                        showItemsDialog = true
                    }
                },
                onInspectSkill = { skill ->
                    skillInfoDialog = skill
                },
                onUseSkill = { skill ->
                    if (locked) return@SkillActionDock
                    actionsExpanded = false
                    locked = true
                    displayedBattle = battle
                    scope.launch {
                        val livingMinion = battle.minion?.takeIf { it.isAlive }
                        val targetForSkill = when (skill.targetMode) {
                            com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SINGLE_ENEMY ->
                                selectedTargetId
                                    ?.takeIf { id -> battle.enemies.any { it.instanceId == id && it.isAlive } }
                                    ?: battle.enemies.firstOrNull { it.isAlive }?.instanceId
                            com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SINGLE_ALLY ->
                                selectedTargetId
                                    ?.takeIf { id -> livingMinion?.instanceId == id }
                                    ?: battle.pet.instanceId
                            com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SELF -> battle.pet.instanceId
                        }
                        val petResult = repository.performSkill(battle.petId, skill.id, targetForSkill)
                        if (petResult != null) {
                            playBattleEvents(petResult.events, petResult.snapshot)
                            displayedBattle = petResult.snapshot
                        }
                        delay(220)
                        locked = false
                    }
                }
            )
        }
        if (showItemsDialog) {
            BattleItemDialog(
                inventory = pet.inventory,
                battle = battle,
                onDismiss = { showItemsDialog = false },
                onInspectSupply = { supply ->
                    battleInfoDialog = resources.getString(supply.nameRes) to supplyInfoText(context, supply, pet.inventory.quantityOf(supply.id))
                },
                onUseSupply = { supply ->
                    if (locked) return@BattleItemDialog
                    showItemsDialog = false
                    actionsExpanded = false
                    locked = true
                    displayedBattle = battle
                    scope.launch {
                        val itemResult = repository.useSupplyInBattle(battle.petId, supply.id)
                        val actionResult = itemResult.result
                        if (actionResult != null) {
                            playBattleEvents(actionResult.events, actionResult.snapshot)
                            displayedBattle = actionResult.snapshot
                        } else if (itemResult.error != null) {
                            Toast.makeText(context, potionUseErrorMessage(context, itemResult.error), Toast.LENGTH_SHORT).show()
                        }
                        delay(220)
                        locked = false
                    }
                }
            )
        }
        inspectedEnemy?.let { enemy ->
            MonsterInspectDialog(
                enemy = enemy,
                battle = viewBattle,
                onDismiss = { inspectedEnemy = null }
            )
        }
        if (inspectedMinion) {
            viewBattle.minion?.let { minion ->
                HelperInspectDialog(
                    combatant = minion,
                    battle = viewBattle,
                    onDismiss = { inspectedMinion = false }
                )
            }
        }
        if (inspectedPet) {
            PetInspectDialog(
                pet = pet,
                profile = profile,
                combatant = viewBattle.pet,
                onDismiss = { inspectedPet = false }
            )
        }
        if (locked) {
            Button(
                onClick = { speedUpTurns = !speedUpTurns },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (speedUpTurns) GateGreen else GatePanel,
                    contentColor = if (speedUpTurns) GateDark else GateAccent
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 86.dp)
            ) {
                Text(
                    text = stringResource(if (speedUpTurns) R.string.adventure_gate_turn_speed_on else R.string.adventure_gate_turn_speed),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
        battleInfoDialog?.let { (title, body) ->
            AlertDialog(
                onDismissRequest = { battleInfoDialog = null },
                containerColor = GatePanel,
                titleContentColor = GateAccent,
                textContentColor = GateText,
                title = { Text(title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(text = body, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { battleInfoDialog = null }) { Text(stringResource(R.string.action_ok)) }
                }
            )
        }
        skillInfoDialog?.let { skill ->
            SkillInfoDialog(
                profile = profile,
                skill = skill,
                onDismiss = { skillInfoDialog = null }
            )
        }
    }
}

@Composable
private fun BattleWaveChip(
    currentWave: Int,
    totalWaves: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = GatePanel.copy(alpha = 0.86f),
        contentColor = GateAccent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.62f))
    ) {
        Text(
            text = stringResource(
                R.string.adventure_gate_wave_count,
                currentWave.coerceAtLeast(1),
                totalWaves.coerceAtLeast(1)
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArenaPetCard(
    pet: com.example.llamadroid.tama.data.TamaPet,
    combatant: AdventureGateCombatantState,
    active: Boolean,
    damagePopup: DamagePopup?,
    modifier: Modifier = Modifier,
    onInspect: () -> Unit
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(ArenaCombatantCardWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) GateGreen.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.38f))
                .border(1.dp, if (active) GateGreen else GateAccent.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
                .combinedClickable(onClick = {}, onLongClick = onInspect)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TamaPetSprite(pet = pet, size = 62.dp)
            Text(
                text = pet.name,
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                combatant.statuses.take(4).forEach { status ->
                    StatusIcon(status.id, modifier = Modifier.size(12.dp))
                }
            }
            CompactArenaBar(
                label = stringResource(R.string.adventure_gate_stat_hp),
                current = combatant.hp,
                max = combatant.maxHp,
                color = GateGreen
            )
            CompactArenaBar(
                label = stringResource(R.string.adventure_gate_stat_mana),
                current = combatant.mana,
                max = combatant.maxMana,
                color = GateBlue
            )
        }
        damagePopup?.let {
            Text(
                text = it.label ?: if (it.healing) "+${it.amount}" else "-${it.amount}",
                color = it.color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-18).dp)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun CompactArenaBar(
    label: String,
    current: Int,
    max: Int,
    color: Color
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = GateText.copy(alpha = 0.82f), fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1)
            Text("${current.coerceAtLeast(0)}/${max.coerceAtLeast(1)}", color = GateText.copy(alpha = 0.82f), fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1)
        }
        LinearProgressIndicator(
            progress = { current.coerceAtLeast(0).toFloat() / max.coerceAtLeast(1).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = Color.Black.copy(alpha = 0.45f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkeletonHelperCard(
    combatant: AdventureGateCombatantState,
    selected: Boolean,
    active: Boolean,
    damagePopup: DamagePopup?,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onInspect: () -> Unit
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(ArenaCombatantCardWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        active -> GateGreen.copy(alpha = 0.26f)
                        selected -> GateBlue.copy(alpha = 0.26f)
                        else -> Color.Black.copy(alpha = 0.24f)
                    }
                )
                .border(1.dp, when {
                    active -> GateGreen
                    selected -> GateBlue
                    else -> GateBlue.copy(alpha = 0.35f)
                }, RoundedCornerShape(10.dp))
                .combinedClickable(onClick = onSelect, onLongClick = onInspect)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            AsyncImage(
                model = "file:///android_asset/tama/adventure_gate/allies/skeleton_helper/idle_0.png",
                contentDescription = stringResource(R.string.adventure_gate_skill_skeleton_helper),
                modifier = Modifier.size(46.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Text(
                text = stringResource(R.string.adventure_gate_skeleton_helper_short),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                combatant.statuses.take(3).forEach { status ->
                    StatusIcon(status.id, modifier = Modifier.size(12.dp))
                }
            }
            CompactArenaBar(
                label = stringResource(R.string.adventure_gate_stat_hp),
                current = combatant.hp,
                max = combatant.maxHp,
                color = GateBlue
            )
        }
        damagePopup?.let {
            Text(
                text = it.label ?: if (it.healing) "+${it.amount}" else "-${it.amount}",
                color = it.color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-18).dp)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun EnemyGridLayout(
    enemies: List<AdventureGateCombatantState>,
    selectedTargetId: String?,
    activeActorId: String?,
    damagePopups: List<DamagePopup>,
    modifier: Modifier,
    onSelect: (AdventureGateCombatantState) -> Unit,
    onInspect: (AdventureGateCombatantState) -> Unit
) {
    val visibleEnemies = enemies.take(AdventureGateCatalog.MAX_ENEMIES_PER_WAVE)
    val columns = if (visibleEnemies.size <= 2) {
        listOf(visibleEnemies)
    } else {
        visibleEnemies.chunked(2)
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        columns.forEach { columnEnemies ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                columnEnemies.forEach { enemy ->
                    EnemySprite(
                        enemy = enemy,
                        selected = selectedTargetId == enemy.instanceId,
                        active = activeActorId == enemy.instanceId,
                        damagePopup = damagePopups.lastOrNull { it.targetInstanceId == enemy.instanceId },
                        onClick = { onSelect(enemy) },
                        onInspect = { onInspect(enemy) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EnemySprite(
    enemy: AdventureGateCombatantState,
    selected: Boolean,
    active: Boolean,
    damagePopup: DamagePopup?,
    onClick: () -> Unit,
    onInspect: () -> Unit
) {
    val monster = AdventureGateCatalog.monster(enemy.definitionId)
    var motionPulse by remember(enemy.instanceId) { mutableStateOf(false) }
    LaunchedEffect(enemy.instanceId, enemy.isAlive) {
        while (enemy.isAlive) {
            delay(620)
            motionPulse = !motionPulse
        }
    }
    val yOffset = if (enemy.isAlive && motionPulse) (-3).dp else 0.dp
    val scale = if (enemy.boss && enemy.enraged) 1.08f else if (enemy.isAlive && motionPulse) 1.03f else 1f
    val spriteAlpha = if (enemy.isAlive) 1f else 0.42f
    Box {
        Column(
            modifier = Modifier
                .width(ArenaCombatantCardWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        active -> GateGreen.copy(alpha = 0.28f)
                        selected -> GateAccent.copy(alpha = 0.24f)
                        else -> Color.Black.copy(alpha = 0.18f)
                    }
                )
                .border(1.dp, when {
                    active -> GateGreen
                    selected -> GateAccent
                    else -> Color.Transparent
                }, RoundedCornerShape(10.dp))
                .combinedClickable(enabled = true, onClick = onClick, onLongClick = onInspect)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = "file:///android_asset/${monster.assetBasePath}/idle_0.png",
                contentDescription = stringResource(monster.nameRes),
                modifier = Modifier
                    .size(58.dp)
                    .offset(y = yOffset)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = spriteAlpha
                        shadowElevation = if (enemy.boss && enemy.enraged) 10f else 0f
                    },
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
            Text(
                text = stringResource(monster.nameRes),
                color = if (enemy.isAlive) GateText else GateText.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                enemy.statuses.take(4).forEach { status ->
                    StatusIcon(status.id, modifier = Modifier.size(14.dp))
                }
            }
            HpBar(current = enemy.hp, max = enemy.maxHp, color = if (enemy.boss) GateDanger else GateGreen)
        }
        Text(
            text = stringResource(R.string.adventure_gate_enemy_level_short, enemy.level),
            color = GateDark,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .background(GateAccent.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        damagePopup?.let {
            Text(
                text = it.label ?: if (it.healing) "+${it.amount}" else "-${it.amount}",
                color = it.color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-18).dp)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun StatusPanel(battle: AdventureGateBattleSnapshot) {
    Card(colors = CardDefaults.cardColors(containerColor = GatePanel)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.adventure_gate_pet_status), color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            HpBar(battle.pet.hp, battle.pet.maxHp, GateGreen)
            ManaBar(battle.pet.mana, battle.pet.maxMana)
            battle.minion?.takeIf { it.isAlive }?.let { minion ->
                Text(
                    text = stringResource(R.string.adventure_gate_skeleton_helper_status),
                    color = GateBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                HpBar(minion.hp, minion.maxHp, GateBlue)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), battle.pet.attack.toString(), GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), battle.pet.magic.toString(), GateBlue)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), battle.pet.defense.toString(), GateText)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), battle.pet.speed.toString(), GateGreen)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_accuracy), battle.pet.accuracy.toString(), GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_evasion), battle.pet.evasion.toString(), GateBlue)
            }
            if (battle.pet.weaknesses.isNotEmpty() || battle.pet.resistances.isNotEmpty()) {
                ElementList(stringResource(R.string.adventure_gate_weak_to), battle.pet.weaknesses)
                ElementList(stringResource(R.string.adventure_gate_resists), battle.pet.resistances)
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdventureGateStatChip(stringResource(R.string.adventure_gate_wave), "${battle.waveIndex + 1}", GateAccent)
                AdventureGateStatChip(stringResource(R.string.adventure_gate_enemies), battle.enemies.count { it.isAlive }.toString(), GateDanger)
                AdventureGateStatChip(
                    stringResource(R.string.adventure_gate_turn),
                    stringResource(
                        when (battle.turn) {
                            com.example.llamadroid.tama.rpg.AdventureGateTurn.PET -> R.string.adventure_gate_turn_pet
                            com.example.llamadroid.tama.rpg.AdventureGateTurn.ENEMY -> R.string.adventure_gate_turn_enemy
                            com.example.llamadroid.tama.rpg.AdventureGateTurn.COMPLETE -> R.string.adventure_gate_turn_complete
                        }
                    ),
                    GateBlue
                )
            }
        }
    }
}

@Composable
private fun SkillActionDock(
    profile: AdventureGateProfile,
    battle: AdventureGateBattleSnapshot,
    inventory: List<InventoryItem>,
    selectedTargetId: String?,
    locked: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    onToggleExpanded: () -> Unit,
    onOpenItems: () -> Unit,
    onInspectSkill: (AdventureGateSkillDefinition) -> Unit,
    onUseSkill: (AdventureGateSkillDefinition) -> Unit
) {
    val skills = AdventureGateCatalog.skillsForProfile(profile)
    val attackSkills = skills.filter { it.kind == AdventureGateSkillKind.ATTACK }
    val magicSkills = skills.filter { it.kind == AdventureGateSkillKind.MAGIC || it.kind == AdventureGateSkillKind.HEAL || it.kind == AdventureGateSkillKind.SUMMON }
    val guardSkill = AdventureGateCatalog.skill("guard")
    val supportSkills = skills.filter { it.kind == AdventureGateSkillKind.GUARD && it.id != guardSkill.id }
    val supplyCount = AdventureGateCatalog.supplies
        .filter { it.kind != AdventureGateSupplyKind.SKILL_POINT }
        .sumOf { inventory.quantityOf(it.id) }
    if (!expanded) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Button(
                onClick = onToggleExpanded,
                enabled = !battle.isCompleted,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (locked) GatePanel else GateAccent,
                    disabledContainerColor = GatePanel.copy(alpha = 0.82f)
                ),
                border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.65f)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(if (locked) R.string.adventure_gate_playing_turn else R.string.adventure_gate_actions),
                    color = if (locked) GateText else GateDark,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GatePanel.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(if (locked) R.string.adventure_gate_playing_turn else R.string.adventure_gate_actions),
                    color = GateAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                TextButton(
                    onClick = onToggleExpanded,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.adventure_gate_actions_hide),
                        color = GateText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SkillActionColumn(
                    title = stringResource(R.string.adventure_gate_shop_attacks),
                    skills = attackSkills,
                    battle = battle,
                    selectedTargetId = selectedTargetId,
                    locked = locked,
                    onInspectSkill = onInspectSkill,
                    onUseSkill = onUseSkill
                )
                SkillActionColumn(
                    title = stringResource(R.string.adventure_gate_shop_magic_skills),
                    skills = magicSkills + supportSkills,
                    battle = battle,
                    selectedTargetId = selectedTargetId,
                    locked = locked,
                    onInspectSkill = onInspectSkill,
                    onUseSkill = onUseSkill
                )
                Column(
                    modifier = Modifier.width(138.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.adventure_gate_action_guard), color = GateText.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text(
                        text = stringResource(
                            R.string.adventure_gate_guard_uses_short,
                            battle.guardUses,
                            AdventureGateCatalog.BATTLE_GUARD_LIMIT
                        ),
                        color = GateText.copy(alpha = 0.62f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                    SkillActionButton(
                        skill = guardSkill,
                        cooldownRemaining = battle.skillCooldowns[guardSkill.id] ?: 0,
                        disabled = skillDisabled(guardSkill, battle, selectedTargetId, locked),
                        onUse = { onUseSkill(guardSkill) },
                        onInspect = { onInspectSkill(guardSkill) }
                    )
                    OutlinedButton(
                        onClick = onOpenItems,
                        enabled = !locked && battle.turn == com.example.llamadroid.tama.rpg.AdventureGateTurn.PET && supplyCount > 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        border = BorderStroke(1.dp, GateBlue.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.adventure_gate_items_button),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SkillActionColumn(
    title: String,
    skills: List<AdventureGateSkillDefinition>,
    battle: AdventureGateBattleSnapshot,
    selectedTargetId: String?,
    locked: Boolean,
    onInspectSkill: (AdventureGateSkillDefinition) -> Unit,
    onUseSkill: (AdventureGateSkillDefinition) -> Unit
) {
    Column(
        modifier = Modifier.width(172.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = GateText.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1)
        skills.forEach { skill ->
            SkillActionButton(
                skill = skill,
                cooldownRemaining = battle.skillCooldowns[skill.id] ?: 0,
                disabled = skillDisabled(skill, battle, selectedTargetId, locked),
                onUse = { onUseSkill(skill) },
                onInspect = { onInspectSkill(skill) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillActionButton(
    skill: AdventureGateSkillDefinition,
    cooldownRemaining: Int,
    disabled: Boolean,
    onUse: () -> Unit,
    onInspect: () -> Unit
) {
    val background = if (skill.kind == AdventureGateSkillKind.MAGIC) GateBlue else GateAccent
    Surface(
        modifier = Modifier
            .width(166.dp)
            .height(78.dp)
            .combinedClickable(
                onClick = { if (!disabled) onUse() },
                onLongClick = onInspect
            ),
        color = background.copy(alpha = if (disabled) 0.42f else 1f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (disabled) 0.10f else 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SkillIcon(skill, modifier = Modifier.size(22.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = stringResource(skill.nameRes),
                    color = if (disabled) Color.White.copy(alpha = 0.55f) else Color(0xFF1B1324),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        cooldownRemaining > 0 -> stringResource(R.string.adventure_gate_skill_cooldown_line, cooldownRemaining)
                        skill.manaCost > 0 -> stringResource(R.string.adventure_gate_skill_mana_short, skill.manaCost)
                        else -> stringResource(R.string.adventure_gate_skill_free_short)
                    },
                    color = if (disabled) Color.White.copy(alpha = 0.50f) else Color(0xFF1B1324).copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun skillDisabled(
    skill: AdventureGateSkillDefinition,
    battle: AdventureGateBattleSnapshot,
    selectedTargetId: String?,
    locked: Boolean
): Boolean =
    battle.isCompleted ||
        locked ||
        battle.turn != com.example.llamadroid.tama.rpg.AdventureGateTurn.PET ||
        battle.pet.mana < skill.manaCost ||
        (skill.kind == AdventureGateSkillKind.SUMMON && battle.minion?.isAlive == true) ||
        (AdventureGateCatalog.consumesGuardUse(skill.id) && battle.guardUses >= AdventureGateCatalog.BATTLE_GUARD_LIMIT) ||
        (battle.skillCooldowns[skill.id] ?: 0) > 0 ||
        (skill.targetMode == com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SINGLE_ENEMY && selectedTargetId == null)

@Composable
private fun BattleItemDialog(
    inventory: List<InventoryItem>,
    battle: AdventureGateBattleSnapshot,
    onDismiss: () -> Unit,
    onInspectSupply: (AdventureGateSupplyDefinition) -> Unit,
    onUseSupply: (AdventureGateSupplyDefinition) -> Unit
) {
    val supplies = AdventureGateCatalog.supplies
        .filter { it.kind != AdventureGateSupplyKind.SKILL_POINT }
        .mapNotNull { supply ->
            val count = inventory.quantityOf(supply.id)
            if (count > 0) supply to count else null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.adventure_gate_items_title), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.adventure_gate_items_limit, battle.potionsUsed, AdventureGateCatalog.BATTLE_POTION_LIMIT),
                    color = GateText.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (supplies.isEmpty()) {
                    item { Text(stringResource(R.string.adventure_gate_items_none), fontFamily = FontFamily.Monospace) }
                }
                items(supplies) { (supply, count) ->
                    SupplyBattleRow(
                        supply = supply,
                        count = count,
                        canUse = battle.potionsUsed < AdventureGateCatalog.BATTLE_POTION_LIMIT,
                        onInspect = { onInspectSupply(supply) },
                        onUse = { onUseSupply(supply) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SupplyBattleRow(
    supply: AdventureGateSupplyDefinition,
    count: Int,
    canUse: Boolean,
    onInspect: () -> Unit,
    onUse: () -> Unit
) {
    ShopRowSurface(modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onInspect)) {
        AsyncImage(
            model = "file:///android_asset/${supply.assetPath}",
            contentDescription = stringResource(supply.nameRes),
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(supply.nameRes),
                color = GateText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supplyEffectText(supply) + " " + stringResource(R.string.adventure_gate_shop_quantity, count),
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
        Button(
            onClick = onUse,
            enabled = canUse,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GateBlue)
        ) {
            Text(stringResource(R.string.adventure_gate_items_use), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BattleLogPanel(log: List<AdventureGateBattleLogEntry>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171020))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.adventure_gate_battle_log), color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            log.takeLast(10).forEach { entry ->
                Text(
                    text = formatLogEntry(entry),
                    color = GateText.copy(alpha = 0.86f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun BattleRewardOverlay(
    battle: AdventureGateBattleSnapshot,
    phase: AdventureGatePhaseDefinition,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coinReward = battle.log.lastOrNull { it.messageKey == AdventureGateLogMessage.COINS_REWARDED }?.amount
    val potionReward = battle.log.lastOrNull { it.messageKey == AdventureGateLogMessage.POTION_REWARDED }?.itemId
        ?.let(AdventureGateCatalog::supply)
    val relicReward = battle.log.lastOrNull { it.messageKey == AdventureGateLogMessage.EQUIPMENT_DROPPED }?.equipmentId
        ?.let(AdventureGateCatalog::equipment)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp),
        color = if (battle.isVictory) GateGreen.copy(alpha = 0.16f) else GateDanger.copy(alpha = 0.16f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, if (battle.isVictory) GateGreen else GateDanger)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GatePanel.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(if (battle.isVictory) R.string.adventure_gate_victory_title else R.string.adventure_gate_defeat_title),
                    color = if (battle.isVictory) GateGreen else GateDanger,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (battle.isVictory) {
                        stringResource(R.string.adventure_gate_victory_body, battle.xpAwarded)
                    } else {
                        stringResource(R.string.adventure_gate_defeat_body)
                    },
                    color = GateText,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                if (battle.isVictory) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_reward_xp), battle.xpAwarded.toString(), GateAccent)
                        coinReward?.let {
                            AdventureGateStatChip(stringResource(R.string.adventure_gate_reward_coins), it.toString(), GateGreen)
                        }
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_reward_happiness), "+15", GateBlue)
                    }
                    potionReward?.let { potion ->
                        Text(
                            text = stringResource(R.string.adventure_gate_reward_potion, stringResource(potion.nameRes)),
                            color = GateBlue,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    relicReward?.let { relic ->
                        Text(
                            text = stringResource(R.string.adventure_gate_boss_drop_found, stringResource(relic.nameRes)),
                            color = GateAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    phase.bossRevealRes?.let { reveal ->
                        Text(
                            text = TamaDialogTextCatalog.localizedResource(context, reveal),
                            color = GateText.copy(alpha = 0.88f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Button(
                onClick = onClaim,
                colors = ButtonDefaults.buttonColors(containerColor = if (battle.isVictory) GateAccent else GateDanger),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp)
            ) {
                Text(stringResource(R.string.adventure_gate_claim_rewards), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AdventureGateStatChip(label: String, value: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text = if (value.isBlank()) label else "$label $value",
            color = GateText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HpBar(current: Int, max: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { current.toFloat() / max.coerceAtLeast(1).toFloat() },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = Color.Black.copy(alpha = 0.35f)
        )
        Text(
            text = stringResource(R.string.adventure_gate_hp_value, current.coerceAtLeast(0), max),
            color = GateText.copy(alpha = 0.82f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ManaBar(current: Int, max: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { current.toFloat() / max.coerceAtLeast(1).toFloat() },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp)),
            color = GateBlue,
            trackColor = Color.Black.copy(alpha = 0.35f)
        )
        Text(
            text = stringResource(R.string.adventure_gate_mana_value, current.coerceAtLeast(0), max),
            color = GateText.copy(alpha = 0.82f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun AdventureGateLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = GateAccent)
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.adventure_gate_loading), color = GateText, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AdventureGateInfo(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = GateText,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PhaseStoryDialog(
    phase: AdventureGatePhaseDefinition,
    onDismiss: () -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val world = AdventureGateCatalog.world(phase.worldId)
    val worldName = TamaDialogTextCatalog.localizedResource(context, world.nameRes)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(
                text = stringResource(R.string.adventure_gate_story_title, worldName, phase.phaseNumber),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = TamaDialogTextCatalog.localizedResource(context, phase.storyRes),
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart) {
                Text(stringResource(R.string.adventure_gate_start_phase))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AdventureGateInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(stringResource(R.string.adventure_gate_info_title), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { InfoParagraph(R.string.adventure_gate_info_story) }
                item { InfoParagraph(R.string.adventure_gate_info_recovery) }
                item { InfoParagraph(R.string.adventure_gate_info_stats) }
                item { InfoParagraph(R.string.adventure_gate_info_potions) }
                item { InfoParagraph(R.string.adventure_gate_info_equipment) }
                item { InfoParagraph(R.string.adventure_gate_info_shields) }
                item { InfoParagraph(R.string.adventure_gate_info_drops) }
                item { InfoParagraph(R.string.adventure_gate_info_skills) }
                item { InfoParagraph(R.string.adventure_gate_info_statuses) }
                item { InfoParagraph(R.string.adventure_gate_info_types) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdventureGateElement.entries.forEach { element ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TypeIcon(element, modifier = Modifier.size(22.dp))
                                Text(elementLabel(element), color = GateText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun InfoParagraph(resId: Int) {
    Text(
        text = stringResource(resId),
        color = GateText.copy(alpha = 0.88f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
}

@Composable
private fun MonsterInspectDialog(
    enemy: AdventureGateCombatantState,
    battle: AdventureGateBattleSnapshot,
    onDismiss: () -> Unit
) {
    val monster = AdventureGateCatalog.monster(enemy.definitionId)
    val context = LocalContext.current
    val resources = LocalResources.current
    val usedAttacks = battle.log
        .filter { it.actorInstanceId == enemy.instanceId && it.messageKey == AdventureGateLogMessage.ENEMY_USED_ATTACK }
        .mapNotNull { it.element }
        .distinct()
    val usedAttackLabels = usedAttacks.joinToString { resources.getString(elementNameRes(it)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(monster.nameRes),
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.adventure_gate_enemy_level_short, enemy.level),
                    color = GateDark,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 42.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(GateAccent.copy(alpha = 0.92f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        enemy.elements.forEach { TypeIcon(it, modifier = Modifier.size(24.dp)) }
                        enemy.statuses.forEach { StatusIcon(it.id, modifier = Modifier.size(22.dp)) }
                    }
                }
                item { HpBar(enemy.hp, enemy.maxHp, if (enemy.boss) GateDanger else GateGreen) }
                item { ManaBar(enemy.mana, enemy.maxMana) }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), enemy.attack.toString(), GateAccent)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), enemy.magic.toString(), GateBlue)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), enemy.defense.toString(), GateText)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), enemy.speed.toString(), GateGreen)
                    }
                }
                item { ElementList(stringResource(R.string.adventure_gate_weak_to), enemy.weaknesses) }
                item { ElementList(stringResource(R.string.adventure_gate_resists), enemy.resistances) }
                item { ActiveStatusEffectsSection(enemy.statuses) }
                item {
                    Text(
                        text = if (usedAttacks.isEmpty()) {
                            stringResource(R.string.adventure_gate_enemy_attacks_none)
                        } else {
                            stringResource(
                                R.string.adventure_gate_enemy_attacks_used,
                                usedAttackLabels
                            )
                        },
                        color = GateText.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun HelperInspectDialog(
    combatant: AdventureGateCombatantState,
    battle: AdventureGateBattleSnapshot,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val usedActions = battle.log
        .filter { it.actorInstanceId == combatant.instanceId && it.skillNameRes != null }
        .distinctBy { it.skillId ?: it.skillNameRes }
        .mapNotNull { it.skillNameRes?.let(context::getString) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(
                text = stringResource(R.string.adventure_gate_skill_skeleton_helper),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        combatant.elements.forEach { TypeIcon(it, modifier = Modifier.size(24.dp)) }
                        combatant.statuses.forEach { StatusIcon(it.id, modifier = Modifier.size(22.dp)) }
                    }
                }
                item { HpBar(combatant.hp, combatant.maxHp, GateBlue) }
                item { ManaBar(combatant.mana, combatant.maxMana) }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), combatant.attack.toString(), GateAccent)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), combatant.magic.toString(), GateBlue)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), combatant.defense.toString(), GateText)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), combatant.speed.toString(), GateGreen)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_accuracy), combatant.accuracy.toString(), GateAccent)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_evasion), combatant.evasion.toString(), GateBlue)
                    }
                }
                item { ElementList(stringResource(R.string.adventure_gate_weak_to), combatant.weaknesses) }
                item { ElementList(stringResource(R.string.adventure_gate_resists), combatant.resistances) }
                item { ActiveStatusEffectsSection(combatant.statuses) }
                item {
                    Text(
                        text = if (usedActions.isEmpty()) {
                            stringResource(R.string.adventure_gate_enemy_attacks_none)
                        } else {
                            stringResource(
                                R.string.adventure_gate_enemy_attacks_used,
                                usedActions.joinToString()
                            )
                        },
                        color = GateText.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun PetInspectDialog(
    pet: com.example.llamadroid.tama.data.TamaPet,
    profile: AdventureGateProfile,
    combatant: AdventureGateCombatantState,
    onDismiss: () -> Unit
) {
    val hasStudyManaRegen = AdventureGateCombatEngine.hasStudyManaRegenPassive(profile)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(pet.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        combatant.elements.forEach { TypeIcon(it, modifier = Modifier.size(24.dp)) }
                        combatant.statuses.forEach { StatusIcon(it.id, modifier = Modifier.size(22.dp)) }
                    }
                }
                item { HpBar(combatant.hp, combatant.maxHp, GateGreen) }
                item { ManaBar(combatant.mana, combatant.maxMana) }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_attack), combatant.attack.toString(), GateAccent)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_magic), combatant.magic.toString(), GateBlue)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_defense), combatant.defense.toString(), GateText)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_speed), combatant.speed.toString(), GateGreen)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_accuracy), combatant.accuracy.toString(), GateAccent)
                        AdventureGateStatChip(stringResource(R.string.adventure_gate_stat_evasion), combatant.evasion.toString(), GateBlue)
                    }
                }
                item { ElementList(stringResource(R.string.adventure_gate_weak_to), combatant.weaknesses) }
                item { ElementList(stringResource(R.string.adventure_gate_resists), combatant.resistances) }
                if (hasStudyManaRegen) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.adventure_gate_pet_passive_title),
                                color = GateAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Surface(
                                color = GateDark.copy(alpha = 0.74f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GateBlue.copy(alpha = 0.36f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.adventure_gate_pet_passive_study_mana_name),
                                        color = GateBlue,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        stringResource(
                                            R.string.adventure_gate_pet_passive_study_mana_desc,
                                            AdventureGateCombatEngine.studyManaRegenPercent(profile)
                                        ),
                                        color = GateText.copy(alpha = 0.84f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
                item { ActiveStatusEffectsSection(combatant.statuses) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun ActiveStatusEffectsSection(statuses: List<AdventureGateStatusEffect>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.adventure_gate_status_active_title),
            color = GateAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        if (statuses.isEmpty()) {
            Text(
                stringResource(R.string.adventure_gate_status_none),
                color = GateText.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        } else {
            statuses.forEach { status ->
                ActiveStatusEffectCard(status)
            }
        }
    }
}

@Composable
private fun ActiveStatusEffectCard(status: AdventureGateStatusEffect) {
    val definition = remember(status.id) { runCatching { AdventureGateCatalog.status(status.id) }.getOrNull() }
    Surface(
        color = Color.Black.copy(alpha = 0.18f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StatusIcon(status.id, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = definition?.let { stringResource(it.nameRes) } ?: status.id,
                        color = GateText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = definition?.let { stringResource(it.descriptionRes) }.orEmpty(),
                        color = GateText.copy(alpha = 0.72f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
                Text(
                    text = stringResource(R.string.adventure_gate_status_turns, status.turnsRemaining),
                    color = GateAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            statusEffectLines(status).forEach { line ->
                Text(
                    text = line,
                    color = GateText.copy(alpha = 0.84f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun statusEffectLines(status: AdventureGateStatusEffect): List<String> {
    return statusEffectLines(LocalContext.current, status)
}

private fun statusEffectLines(context: Context, status: AdventureGateStatusEffect): List<String> {
    val lines = mutableListOf<String>()
    if (status.damagePerTurn > 0) lines += context.getString(R.string.adventure_gate_status_effect_hp_damage, status.damagePerTurn)
    if (status.skipTurnChancePercent > 0) lines += context.getString(R.string.adventure_gate_status_effect_skip, status.skipTurnChancePercent)
    if (status.attackMultiplierPercent != 100) lines += context.getString(R.string.adventure_gate_status_effect_stat_multiplier, context.getString(R.string.adventure_gate_stat_attack), status.attackMultiplierPercent)
    if (status.magicMultiplierPercent != 100) lines += context.getString(R.string.adventure_gate_status_effect_stat_multiplier, context.getString(R.string.adventure_gate_stat_magic), status.magicMultiplierPercent)
    if (status.defenseMultiplierPercent != 100) lines += context.getString(R.string.adventure_gate_status_effect_stat_multiplier, context.getString(R.string.adventure_gate_stat_defense), status.defenseMultiplierPercent)
    if (status.speedMultiplierPercent != 100) lines += context.getString(R.string.adventure_gate_status_effect_stat_multiplier, context.getString(R.string.adventure_gate_stat_speed), status.speedMultiplierPercent)
    if (status.accuracyDelta != 0) lines += context.getString(R.string.adventure_gate_status_effect_stat_delta, context.getString(R.string.adventure_gate_stat_accuracy), signed(status.accuracyDelta))
    if (status.evasionDelta != 0) lines += context.getString(R.string.adventure_gate_status_effect_stat_delta, context.getString(R.string.adventure_gate_stat_evasion), signed(status.evasionDelta))
    if (status.incomingDamageBonusPercent != 0) lines += context.getString(R.string.adventure_gate_status_effect_damage_taken, signed(status.incomingDamageBonusPercent))
    if (status.physicalDamageTakenBonusPercent != 0) lines += context.getString(R.string.adventure_gate_status_effect_physical_damage_taken, signed(status.physicalDamageTakenBonusPercent))
    if (status.hpRegenPercent > 0) lines += context.getString(R.string.adventure_gate_status_effect_hp_regen, status.hpRegenPercent)
    if (status.manaRegenFlat > 0) lines += context.getString(R.string.adventure_gate_status_effect_mana_regen, status.manaRegenFlat)
    if (status.incomingReductionPercent > 0) lines += context.getString(R.string.adventure_gate_status_effect_incoming_reduction, status.incomingReductionPercent)
    return lines
}

@Composable
private fun ElementList(label: String, elements: List<AdventureGateElement>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = GateAccent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            elements.forEach { element ->
                Surface(color = Color.Black.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TypeIcon(element, modifier = Modifier.size(16.dp))
                        Text(elementLabel(element), color = GateText, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeIcon(element: AdventureGateElement, modifier: Modifier = Modifier) {
    AsyncImage(
        model = "file:///android_asset/${AdventureGateCatalog.elementIconAssetPath(element)}",
        contentDescription = elementLabel(element),
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun SkillIcon(skill: AdventureGateSkillDefinition, modifier: Modifier = Modifier) {
    AsyncImage(
        model = "file:///android_asset/${AdventureGateCatalog.skillIconAssetPath(skill)}",
        contentDescription = stringResource(skill.nameRes),
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun elementLabel(element: AdventureGateElement): String =
    stringResource(elementNameRes(element))

private fun elementNameRes(element: AdventureGateElement): Int = when (element) {
    AdventureGateElement.STRIKE -> R.string.adventure_gate_element_strike
    AdventureGateElement.SLASH -> R.string.adventure_gate_element_slash
    AdventureGateElement.FIRE -> R.string.adventure_gate_element_fire
    AdventureGateElement.WATER -> R.string.adventure_gate_element_water
    AdventureGateElement.ICE -> R.string.adventure_gate_element_ice
    AdventureGateElement.STORM -> R.string.adventure_gate_element_storm
    AdventureGateElement.NATURE -> R.string.adventure_gate_element_nature
    AdventureGateElement.STONE -> R.string.adventure_gate_element_stone
    AdventureGateElement.METAL -> R.string.adventure_gate_element_metal
    AdventureGateElement.LIGHT -> R.string.adventure_gate_element_light
    AdventureGateElement.SHADOW -> R.string.adventure_gate_element_shadow
    AdventureGateElement.ARCANE -> R.string.adventure_gate_element_arcane
    AdventureGateElement.BEAST -> R.string.adventure_gate_element_beast
}

private fun skillPathNameRes(path: AdventureGateSkillTreePath): Int = when (path) {
    AdventureGateSkillTreePath.ASSAULT -> R.string.adventure_gate_skill_path_assault
    AdventureGateSkillTreePath.MAGIC -> R.string.adventure_gate_skill_path_magic
    AdventureGateSkillTreePath.SUPPORT -> R.string.adventure_gate_skill_path_support
    AdventureGateSkillTreePath.HEX -> R.string.adventure_gate_skill_path_hex
}

@Composable
private fun StatusIcon(statusId: String, modifier: Modifier = Modifier) {
    val status = remember(statusId) { runCatching { AdventureGateCatalog.status(statusId) }.getOrNull() }
    if (status != null) {
        AsyncImage(
            model = "file:///android_asset/${status.iconAssetPath}",
            contentDescription = stringResource(status.nameRes),
            modifier = modifier,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
    }
}

@Composable
private fun supplyEffectText(supply: AdventureGateSupplyDefinition): String =
    when (supply.kind) {
        AdventureGateSupplyKind.HP -> stringResource(R.string.adventure_gate_shop_restore_hp, supply.amount)
        AdventureGateSupplyKind.MANA -> stringResource(R.string.adventure_gate_shop_restore_mana, supply.amount)
        AdventureGateSupplyKind.CLEANSE -> stringResource(R.string.adventure_gate_shop_cleanse_bad_statuses)
        AdventureGateSupplyKind.SKILL_POINT -> stringResource(R.string.adventure_gate_shop_grants_skill_point)
    }

private fun List<InventoryItem>.quantityOf(itemId: String): Int =
    firstOrNull { it.id == itemId }?.quantity ?: 0

private fun highestUnlockedWorldIndex(progressRows: List<AdventureGateWorldProgress>): Int {
    var unlocked = 0
    AdventureGateCatalog.worlds.forEachIndexed { index, _ ->
        if (index == 0) {
            unlocked = 0
        } else {
            val previousWorld = AdventureGateCatalog.worlds[index - 1]
            if (progressRows.firstOrNull { it.worldId == previousWorld.id }?.finalBossCleared == true) {
                unlocked = index
            }
        }
    }
    return unlocked
}

private fun supplyInfoText(context: Context, supply: AdventureGateSupplyDefinition, count: Int): String {
    val restore = when (supply.kind) {
        AdventureGateSupplyKind.HP -> context.getString(R.string.adventure_gate_shop_restore_hp, supply.amount)
        AdventureGateSupplyKind.MANA -> context.getString(R.string.adventure_gate_shop_restore_mana, supply.amount)
        AdventureGateSupplyKind.CLEANSE -> context.getString(R.string.adventure_gate_shop_cleanse_bad_statuses)
        AdventureGateSupplyKind.SKILL_POINT -> context.getString(R.string.adventure_gate_shop_grants_skill_point)
    }
    return listOf(
        restore,
        context.getString(R.string.adventure_gate_shop_price, supply.price),
        context.getString(R.string.adventure_gate_shop_locked_world, supply.unlockWorldIndex + 1),
        context.getString(R.string.adventure_gate_shop_quantity, count)
    ).joinToString("\n")
}

private fun potionUseErrorMessage(context: Context, error: AdventureGatePotionUseError): String =
    when (error) {
        AdventureGatePotionUseError.NOT_OWNED -> context.getString(R.string.tama_potion_not_owned)
        AdventureGatePotionUseError.FULL -> context.getString(R.string.tama_potion_heal_already_full)
        AdventureGatePotionUseError.LIMIT_REACHED -> context.getString(R.string.adventure_gate_log_potion_limit)
        AdventureGatePotionUseError.NOT_PET_TURN -> context.getString(R.string.adventure_gate_playing_turn)
        AdventureGatePotionUseError.ACTIVE_BATTLE_REQUIRED -> context.getString(R.string.tama_action_adventure_gate_cleanse_battle_only)
        AdventureGatePotionUseError.NO_BAD_STATUS -> context.getString(R.string.adventure_gate_cleanse_no_bad_status)
        AdventureGatePotionUseError.UNKNOWN_ITEM -> context.getString(R.string.tama_potion_missing)
    }

private fun recipeInfoText(context: Context, recipe: AdventureGateRecipeDefinition): String {
    val supply = AdventureGateCatalog.supply(recipe.supplyId)
    return listOf(
        context.getString(R.string.adventure_gate_recipe_makes, supply?.let { context.getString(it.nameRes) } ?: recipe.supplyId),
        context.getString(R.string.adventure_gate_shop_price, recipe.price),
        context.getString(R.string.adventure_gate_shop_locked_world, recipe.unlockWorldIndex + 1),
        context.getString(R.string.adventure_gate_recipe_ingredients_line, recipeIngredientText(context, recipe))
    ).joinToString("\n")
}

private fun recipeIngredientText(context: Context, recipe: AdventureGateRecipeDefinition): String {
    val locale = context.resources.configuration.locales[0]
    return recipe.ingredientCounts.entries
        .sortedBy { FarmTradeItemCatalog.displayName(it.key, locale).toString() }
        .joinToString(", ") { (itemId, count) ->
            val name = FarmTradeItemCatalog.displayName(itemId, locale)
            if (count > 1) "${count}x $name" else name.toString()
        }
}

@Composable
private fun SkillInfoDialog(
    profile: AdventureGateProfile,
    skill: AdventureGateSkillDefinition,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val cost = AdventureGateCatalog.skillPointCost(skill)
    val scaling = stringResource(if (skill.kind == AdventureGateSkillKind.ATTACK) R.string.adventure_gate_stat_attack else R.string.adventure_gate_stat_magic)
    val healingBonus = AdventureGateCatalog.loadoutForProfile(profile)
        .equipment
        .sumOf { it.effect.healingBonusPercent }
    val healPreview = if (skill.kind == AdventureGateSkillKind.HEAL) {
        AdventureGateCombatEngine.calculateHealingAmount(profile.stats.magic, skill.power, healingBonus)
    } else {
        null
    }
    val manaShellPreview = if (skill.id == "mana_shell") {
        AdventureGateCombatEngine.manaShellRestoreAmount(profile.stats.magic)
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GatePanel,
        titleContentColor = GateAccent,
        textContentColor = GateText,
        title = {
            Text(
                text = stringResource(skill.nameRes),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 22.sp
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SkillInfoSection(title = stringResource(R.string.adventure_gate_skill_info_overview)) {
                        SkillInfoLine(stringResource(skill.descriptionRes))
                        SkillInfoLine("${stringResource(elementNameRes(skill.element))} - ${stringResource(skillKindNameRes(skill.kind))}")
                        SkillInfoLine(stringResource(skillPathNameRes(skill.path)))
                    }
                }
                item {
                    SkillInfoSection(title = stringResource(R.string.adventure_gate_skill_info_mechanics)) {
                        SkillInfoLine(stringResource(R.string.adventure_gate_skill_target_info, stringResource(targetModeNameRes(skill.targetMode))))
                        SkillInfoLine(stringResource(R.string.adventure_gate_skill_power_info, skill.power))
                        SkillInfoLine("${stringResource(R.string.adventure_gate_stat_mana)}: ${skill.manaCost}")
                        SkillInfoLine("${stringResource(R.string.adventure_gate_stat_accuracy)}: ${skill.accuracyPercent}%")
                        SkillInfoLine(stringResource(R.string.adventure_gate_skill_cooldown_info, skill.cooldownTurns))
                        SkillInfoLine("$scaling ${if (skill.kind == AdventureGateSkillKind.HEAL) "+" else "x"}")
                        healPreview?.let {
                            SkillInfoLine(stringResource(R.string.adventure_gate_skill_heal_preview, it, profile.stats.magic))
                        }
                        manaShellPreview?.let {
                            SkillInfoLine(stringResource(R.string.adventure_gate_skill_mana_shell_restore_info, it))
                            SkillInfoLine(stringResource(R.string.adventure_gate_skill_mana_shell_recoil_info))
                        }
                    }
                }
                skill.status?.let { status ->
                    item {
                        SkillInfoSection(title = stringResource(R.string.adventure_gate_skill_info_status)) {
                            SkillInfoLine(
                                stringResource(
                                    R.string.adventure_gate_skill_applies_status,
                                    stringResource(AdventureGateCatalog.status(status.id).nameRes),
                                    skill.statusChancePercent,
                                    stringResource(R.string.adventure_gate_status_duration_range)
                                )
                            )
                            statusEffectLines(status).forEach { SkillInfoLine(it) }
                        }
                    }
                }
                item {
                    SkillInfoSection(title = stringResource(R.string.adventure_gate_skill_info_progression)) {
                        SkillInfoLine(stringResource(R.string.adventure_gate_shop_level_locked, skill.unlockLevel))
                        SkillInfoLine(stringResource(R.string.adventure_gate_skill_shop_price, stringResource(R.string.adventure_gate_buy_skill), cost))
                        if (skill.prerequisiteSkillIds.isNotEmpty()) {
                            SkillInfoLine(
                                skill.prerequisiteSkillIds.joinToString(prefix = "${stringResource(R.string.adventure_gate_shop_prerequisites)}: ") { id ->
                                    resources.getString(AdventureGateCatalog.skill(id).nameRes)
                                }
                            )
                        }
                        if (skill.id in profile.purchasedSkillIds) {
                            SkillInfoLine(stringResource(R.string.adventure_gate_shop_owned))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun SkillInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.16f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            color = GateAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        content()
    }
}

@Composable
private fun SkillInfoLine(text: String) {
    Text(
        text = text,
        color = GateText,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp
    )
}

private fun skillInfoText(context: Context, profile: AdventureGateProfile, skill: AdventureGateSkillDefinition): String {
    val cost = AdventureGateCatalog.skillPointCost(skill)
    val scaling = context.getString(if (skill.kind == AdventureGateSkillKind.ATTACK) R.string.adventure_gate_stat_attack else R.string.adventure_gate_stat_magic)
    val healingBonus = AdventureGateCatalog.loadoutForProfile(profile)
        .equipment
        .sumOf { it.effect.healingBonusPercent }
    val healPreview = if (skill.kind == AdventureGateSkillKind.HEAL) {
        AdventureGateCombatEngine.calculateHealingAmount(profile.stats.magic, skill.power, healingBonus)
    } else {
        null
    }
    val status = skill.status
    val statusLines = status?.let { effect ->
        buildList {
            add(
                context.getString(
                    R.string.adventure_gate_skill_applies_status,
                    context.getString(AdventureGateCatalog.status(effect.id).nameRes),
                    skill.statusChancePercent,
                    context.getString(R.string.adventure_gate_status_duration_range)
                )
            )
            addAll(statusEffectLines(context, effect))
        }
    }.orEmpty()
    return listOf(
        context.getString(skill.descriptionRes),
        "${context.getString(elementNameRes(skill.element))} • ${context.getString(skillKindNameRes(skill.kind))}",
        context.getString(R.string.adventure_gate_skill_target_info, context.getString(targetModeNameRes(skill.targetMode))),
        context.getString(R.string.adventure_gate_skill_power_info, skill.power),
        healPreview?.let { context.getString(R.string.adventure_gate_skill_heal_preview, it, profile.stats.magic) }.orEmpty(),
        "${context.getString(R.string.adventure_gate_stat_mana)}: ${skill.manaCost}",
        "${context.getString(R.string.adventure_gate_stat_accuracy)}: ${skill.accuracyPercent}%",
        context.getString(R.string.adventure_gate_skill_cooldown_info, skill.cooldownTurns),
        statusLines.joinToString("\n"),
        context.getString(skillPathNameRes(skill.path)),
        skill.prerequisiteSkillIds
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "${context.getString(R.string.adventure_gate_shop_prerequisites)}: ") { id ->
                context.getString(AdventureGateCatalog.skill(id).nameRes)
            }
            .orEmpty(),
        context.getString(R.string.adventure_gate_shop_level_locked, skill.unlockLevel),
        context.getString(R.string.adventure_gate_skill_shop_price, context.getString(R.string.adventure_gate_buy_skill), cost),
        "$scaling ${if (skill.kind == AdventureGateSkillKind.HEAL) "+" else "x"}",
        if (skill.id in profile.purchasedSkillIds) context.getString(R.string.adventure_gate_shop_owned) else ""
    ).filter { it.isNotBlank() }.joinToString("\n")
}

private fun skillKindNameRes(kind: AdventureGateSkillKind): Int = when (kind) {
    AdventureGateSkillKind.ATTACK -> R.string.adventure_gate_skill_kind_attack
    AdventureGateSkillKind.MAGIC -> R.string.adventure_gate_skill_kind_magic
    AdventureGateSkillKind.HEAL -> R.string.adventure_gate_skill_kind_heal
    AdventureGateSkillKind.GUARD -> R.string.adventure_gate_skill_kind_guard
    AdventureGateSkillKind.SUMMON -> R.string.adventure_gate_skill_kind_summon
}

private fun targetModeNameRes(targetMode: com.example.llamadroid.tama.rpg.AdventureGateTargetMode): Int = when (targetMode) {
    com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SINGLE_ENEMY -> R.string.adventure_gate_skill_target_enemy
    com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SINGLE_ALLY -> R.string.adventure_gate_skill_target_ally
    com.example.llamadroid.tama.rpg.AdventureGateTargetMode.SELF -> R.string.adventure_gate_skill_target_self
}

private fun statusDamageColor(statusId: String?): Color = when (statusId) {
    "burn" -> Color(0xFFFF8A2A)
    "poison" -> Color(0xFFB56BFF)
    "bleed" -> Color(0xFFE3445F)
    else -> Color(0xFFFF77C8)
}

private fun gearInfoText(context: Context, gear: AdventureGateEquipmentDefinition, owned: Boolean): String {
    val bonus = gear.effect.statBonus
    val stats = buildList {
        if (bonus.maxHp != 0) add("${context.getString(R.string.adventure_gate_stat_hp)} ${signed(bonus.maxHp)}")
        if (bonus.maxMana != 0) add("${context.getString(R.string.adventure_gate_stat_mana)} ${signed(bonus.maxMana)}")
        if (bonus.attack != 0) add("${context.getString(R.string.adventure_gate_stat_attack)} ${signed(bonus.attack)}")
        if (bonus.magic != 0) add("${context.getString(R.string.adventure_gate_stat_magic)} ${signed(bonus.magic)}")
        if (bonus.defense != 0) add("${context.getString(R.string.adventure_gate_stat_defense)} ${signed(bonus.defense)}")
        if (bonus.speed != 0) add("${context.getString(R.string.adventure_gate_stat_speed)} ${signed(bonus.speed)}")
        if (bonus.accuracy != 0) add("${context.getString(R.string.adventure_gate_stat_accuracy)} ${signed(bonus.accuracy)}")
        if (bonus.evasion != 0) add("${context.getString(R.string.adventure_gate_stat_evasion)} ${signed(bonus.evasion)}")
    }
    val effects = buildList {
        if (gear.effect.elementDamageElement != null) {
            add(context.getString(R.string.adventure_gate_effect_element_damage, context.getString(elementNameRes(gear.effect.elementDamageElement)), gear.effect.elementDamageBonusPercent))
        }
        if (gear.effect.healingBonusPercent > 0) add(context.getString(R.string.adventure_gate_effect_heal_bonus, gear.effect.healingBonusPercent))
        if (gear.effect.potionBonusPercent > 0) add(context.getString(R.string.adventure_gate_effect_potion_bonus, gear.effect.potionBonusPercent))
        if (gear.effect.manaRefundOnWeakHit > 0) add(context.getString(R.string.adventure_gate_effect_mana_refund, gear.effect.manaRefundOnWeakHit))
        if (gear.effect.statusChanceBonusPercent > 0) add(context.getString(R.string.adventure_gate_effect_status_bonus, gear.effect.statusChanceBonusPercent))
        if (gear.effect.incomingReductionPercent > 0) add(context.getString(R.string.adventure_gate_effect_damage_reduction, gear.effect.incomingReductionPercent))
        if (gear.effect.reflectDamagePercent > 0) add(context.getString(R.string.adventure_gate_effect_reflect, gear.effect.reflectDamagePercent))
        if (gear.effect.statusImmunityIds.isNotEmpty()) {
            add(
                context.getString(
                    R.string.adventure_gate_effect_status_immunity,
                    gear.effect.statusImmunityIds.joinToString { context.getString(AdventureGateCatalog.status(it).nameRes) }
                )
            )
        }
        if (gear.effect.forceFirstTurn) add(context.getString(R.string.adventure_gate_effect_first_turn))
        if (gear.effect.reviveOncePercent > 0) add(context.getString(R.string.adventure_gate_effect_revive, gear.effect.reviveOncePercent))
        if (gear.effect.turnHpRegenPercent > 0 || gear.effect.turnManaRegenPercent > 0) add(context.getString(R.string.adventure_gate_effect_turn_regen, gear.effect.turnHpRegenPercent, gear.effect.turnManaRegenPercent))
    }
    val source = if (gear.uniqueDrop) context.getString(R.string.adventure_gate_boss_drop_source) else context.getString(R.string.adventure_gate_shop_price, gear.price)
    return listOf(
        context.getString(if (gear.slot == AdventureGateEquipmentSlot.RELIC) R.string.adventure_gate_relic_flavor else R.string.adventure_gate_gear_flavor),
        source,
        if (owned) context.getString(R.string.adventure_gate_shop_owned) else "",
        stats.joinToString(" • "),
        effects.joinToString(" • "),
        if (gear.petWeaknesses.isNotEmpty()) "${context.getString(R.string.adventure_gate_weak_to)}: ${gear.petWeaknesses.joinToString { context.getString(elementNameRes(it)) }}" else "",
        if (gear.petResistances.isNotEmpty()) "${context.getString(R.string.adventure_gate_resists)}: ${gear.petResistances.joinToString { context.getString(elementNameRes(it)) }}" else ""
    ).filter { it.isNotBlank() }.joinToString("\n")
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

@Composable
private fun slotLabel(slot: AdventureGateEquipmentSlot): String =
    stringResource(
        when (slot) {
            AdventureGateEquipmentSlot.WEAPON -> R.string.adventure_gate_slot_weapon
            AdventureGateEquipmentSlot.SHIELD -> R.string.adventure_gate_slot_shield
            AdventureGateEquipmentSlot.RING -> R.string.adventure_gate_slot_ring
            AdventureGateEquipmentSlot.RELIC -> R.string.adventure_gate_slot_relic
        }
    )

@Composable
private fun gearBonusShort(gear: AdventureGateEquipmentDefinition): String {
    val bonus = gear.effect.statBonus
    val parts = buildList {
        if (bonus.maxHp != 0) add("${stringResource(R.string.adventure_gate_stat_hp)}+${bonus.maxHp}")
        if (bonus.maxMana != 0) add("${stringResource(R.string.adventure_gate_stat_mana)}+${bonus.maxMana}")
        if (bonus.attack != 0) add("${stringResource(R.string.adventure_gate_stat_attack)}+${bonus.attack}")
        if (bonus.magic != 0) add("${stringResource(R.string.adventure_gate_stat_magic)}+${bonus.magic}")
        if (bonus.defense != 0) add("${stringResource(R.string.adventure_gate_stat_defense)}+${bonus.defense}")
        if (bonus.speed != 0) add("${stringResource(R.string.adventure_gate_stat_speed)}+${bonus.speed}")
        if (bonus.accuracy != 0) add("${stringResource(R.string.adventure_gate_stat_accuracy)}+${bonus.accuracy}")
        if (bonus.evasion != 0) add("${stringResource(R.string.adventure_gate_stat_evasion)}+${bonus.evasion}")
    }
    return parts.take(2).joinToString(" ").ifBlank { stringResource(R.string.adventure_gate_shop_owned) }
}

private fun isWorldUnlocked(
    world: AdventureGateWorldDefinition,
    progressRows: List<AdventureGateWorldProgress>
): Boolean {
    val index = AdventureGateCatalog.worlds.indexOfFirst { it.id == world.id }
    if (index <= 0) return true
    val previousWorld = AdventureGateCatalog.worlds[index - 1]
    return progressRows.firstOrNull { it.worldId == previousWorld.id }?.finalBossCleared == true
}

private fun furthestUnlockedAdventureGateWorld(
    progressRows: List<AdventureGateWorldProgress>
): AdventureGateWorldDefinition =
    AdventureGateCatalog.worlds.lastOrNull { isWorldUnlocked(it, progressRows) }
        ?: AdventureGateCatalog.worlds.first()

@Composable
private fun formatLogEntry(entry: AdventureGateBattleLogEntry): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    fun name(res: Int?) = res?.let { resources.getString(it) }.orEmpty()
    fun element(element: AdventureGateElement?) = element?.let {
        resources.getString(
            when (it) {
                AdventureGateElement.STRIKE -> R.string.adventure_gate_element_strike
                AdventureGateElement.SLASH -> R.string.adventure_gate_element_slash
                AdventureGateElement.FIRE -> R.string.adventure_gate_element_fire
                AdventureGateElement.WATER -> R.string.adventure_gate_element_water
                AdventureGateElement.ICE -> R.string.adventure_gate_element_ice
                AdventureGateElement.STORM -> R.string.adventure_gate_element_storm
                AdventureGateElement.NATURE -> R.string.adventure_gate_element_nature
                AdventureGateElement.STONE -> R.string.adventure_gate_element_stone
                AdventureGateElement.METAL -> R.string.adventure_gate_element_metal
                AdventureGateElement.LIGHT -> R.string.adventure_gate_element_light
                AdventureGateElement.SHADOW -> R.string.adventure_gate_element_shadow
                AdventureGateElement.ARCANE -> R.string.adventure_gate_element_arcane
                AdventureGateElement.BEAST -> R.string.adventure_gate_element_beast
            }
        )
    }.orEmpty()
    return when (entry.messageKey) {
        AdventureGateLogMessage.BATTLE_STARTED -> resources.getString(R.string.adventure_gate_log_battle_started)
        AdventureGateLogMessage.PET_USED_SKILL -> resources.getString(R.string.adventure_gate_log_pet_used_skill, name(entry.skillNameRes), name(entry.targetNameRes), entry.amount)
        AdventureGateLogMessage.PET_SUMMONED -> resources.getString(R.string.adventure_gate_log_pet_summoned, name(entry.skillNameRes))
        AdventureGateLogMessage.PET_GUARDED -> resources.getString(R.string.adventure_gate_log_pet_guarded)
        AdventureGateLogMessage.MANA_SHELL_RECOIL -> resources.getString(R.string.adventure_gate_log_mana_shell_recoil, entry.amount)
        AdventureGateLogMessage.PET_HEALED -> resources.getString(R.string.adventure_gate_log_pet_healed, name(entry.skillNameRes), entry.amount)
        AdventureGateLogMessage.ENEMY_USED_ATTACK -> resources.getString(R.string.adventure_gate_log_enemy_attack, name(entry.actorNameRes), entry.amount)
        AdventureGateLogMessage.MISSED -> resources.getString(R.string.adventure_gate_log_missed)
        AdventureGateLogMessage.WEAK_HIT -> resources.getString(R.string.adventure_gate_log_weak_hit, element(entry.element), name(entry.targetNameRes))
        AdventureGateLogMessage.RESISTED_HIT -> resources.getString(R.string.adventure_gate_log_resisted_hit, element(entry.element), name(entry.targetNameRes))
        AdventureGateLogMessage.ENEMY_DEFEATED -> resources.getString(R.string.adventure_gate_log_enemy_defeated, name(entry.targetNameRes))
        AdventureGateLogMessage.PET_DEFEATED -> resources.getString(R.string.adventure_gate_log_pet_defeated)
        AdventureGateLogMessage.WAVE_STARTED -> resources.getString(R.string.adventure_gate_log_wave_started, entry.amount)
        AdventureGateLogMessage.VICTORY -> resources.getString(R.string.adventure_gate_log_victory, entry.amount)
        AdventureGateLogMessage.DEFEAT -> resources.getString(R.string.adventure_gate_log_defeat)
        AdventureGateLogMessage.LEVEL_UP -> resources.getString(R.string.adventure_gate_log_level_up, entry.amount)
        AdventureGateLogMessage.SKILL_UNLOCKED -> resources.getString(R.string.adventure_gate_log_skill_unlocked, name(entry.skillNameRes))
        AdventureGateLogMessage.NOT_ENOUGH_MANA -> resources.getString(R.string.adventure_gate_log_not_enough_mana, name(entry.skillNameRes), entry.amount)
        AdventureGateLogMessage.SKILL_ON_COOLDOWN -> resources.getString(R.string.adventure_gate_log_skill_on_cooldown, name(entry.skillNameRes), entry.amount)
        AdventureGateLogMessage.GUARD_LIMIT_REACHED -> resources.getString(R.string.adventure_gate_log_guard_limit, entry.amount)
        AdventureGateLogMessage.PET_USED_ITEM -> resources.getString(
            R.string.adventure_gate_log_pet_used_item,
            AdventureGateCatalog.supply(entry.itemId.orEmpty())?.let { resources.getString(it.nameRes) }.orEmpty(),
            entry.amount
        )
        AdventureGateLogMessage.POTION_LIMIT_REACHED -> resources.getString(R.string.adventure_gate_log_potion_limit)
        AdventureGateLogMessage.COINS_REWARDED -> resources.getString(R.string.adventure_gate_log_coins_rewarded, entry.amount)
        AdventureGateLogMessage.POTION_REWARDED -> resources.getString(
            R.string.adventure_gate_log_potion_rewarded,
            AdventureGateCatalog.supply(entry.itemId.orEmpty())?.let { resources.getString(it.nameRes) }.orEmpty()
        )
        AdventureGateLogMessage.EQUIPMENT_DROPPED -> resources.getString(
            R.string.adventure_gate_log_equipment_dropped,
            AdventureGateCatalog.equipment(entry.equipmentId.orEmpty())?.let { resources.getString(it.nameRes) }.orEmpty()
        )
        AdventureGateLogMessage.EQUIPMENT_TRIGGERED -> resources.getString(
            R.string.adventure_gate_log_equipment_triggered,
            AdventureGateCatalog.equipment(entry.equipmentId.orEmpty())?.let { resources.getString(it.nameRes) }.orEmpty(),
            entry.amount
        )
        AdventureGateLogMessage.STATUS_DAMAGE -> resources.getString(R.string.adventure_gate_log_status_damage, name(entry.targetNameRes), entry.amount)
        AdventureGateLogMessage.STATUS_SKIP -> resources.getString(R.string.adventure_gate_log_status_skip, name(entry.actorNameRes))
    }
}
