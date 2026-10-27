package com.example.llamadroid.screenshots.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.llamadroid.screenshots.fixtures.TamaFixtures
import com.example.llamadroid.screenshots.harness.ShotLocale
import com.example.llamadroid.screenshots.harness.ShotProfile
import com.example.llamadroid.screenshots.harness.ShotSpec
import com.example.llamadroid.tama.data.FarmLivestockSlot
import com.example.llamadroid.tama.data.FarmLivestockType
import com.example.llamadroid.tama.data.FarmMaterialCost
import com.example.llamadroid.tama.data.FarmObstacle
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.rpg.AdventureGateBattleSnapshot
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCombatantState
import com.example.llamadroid.tama.rpg.AdventureGateElement
import com.example.llamadroid.tama.rpg.AdventureGateEliteTrait
import com.example.llamadroid.tama.rpg.AdventureGateEnemyIntent
import com.example.llamadroid.tama.rpg.AdventureGateIntentKind
import com.example.llamadroid.tama.rpg.AdventureGateStatusEffect
import com.example.llamadroid.tama.rpg.AdventureGateTurn
import com.example.llamadroid.tama.ui.BattleArena
import com.example.llamadroid.tama.ui.DamagePopup
import com.example.llamadroid.tama.ui.FARM_FIELD_BACKGROUND_ASSET
import com.example.llamadroid.tama.ui.FarmMaterialCostRow
import com.example.llamadroid.tama.ui.FarmMaterialStock
import com.example.llamadroid.tama.ui.FarmPlotArea
import com.example.llamadroid.tama.ui.FarmQuickActionPalette
import com.example.llamadroid.tama.ui.FarmQuickActionRow
import com.example.llamadroid.tama.ui.FarmTileOverlay
import com.example.llamadroid.tama.ui.FarmWeatherChip
import com.example.llamadroid.tama.ui.FarmWeatherLabel
import com.example.llamadroid.tama.ui.LivestockSlotCard
import com.example.llamadroid.tama.ui.TamaChatContextSheet
import com.example.llamadroid.tama.ui.TamaNoticeHost
import com.example.llamadroid.tama.ui.TamaNoticeState
import com.example.llamadroid.tama.ui.TamaPopupDialog
import com.example.llamadroid.tama.ui.TamaPromptProgressLine
import com.example.llamadroid.tama.ui.LocalTamaNotice
import com.example.llamadroid.tama.ui.TamaLcdSurface
import com.example.llamadroid.tama.ui.ToolSidebar
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaContextUsage
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.LlamaPromptProcessingProgress
import com.example.llamadroid.ui.components.ContextUsageRing
import com.example.llamadroid.ui.walkthrough.LocalInlineModalHost
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import io.mockk.mockk

/**
 * Round 14 review renders (Gate battle arena, farm plot area, livestock slot cards) at phone widths
 * 320 dp and 411 dp. Built from the real composables with fixed state and a fixed clock; see
 * `render/R14ShotTest`. Output `<out>/r14/<locale>-<widthdp>/<key>.png`.
 */
object R14ShotCatalog {
    val keys = listOf(
        "gate_arena", "farm_field", "livestock", "livestock_empty",
        "notice_screen", "notice_error", "notice_dialog", "chat_context_sheet", "chat_progress"
    )
    val widthsDp = listOf(320, 411)
    const val HEIGHT_DP = 800

    private const val DENSITY = 2.625f // 420 dpi

    fun profile(widthDp: Int) = ShotProfile(
        key = "w$widthDp",
        qualifiers = "w${widthDp}dp-h${HEIGHT_DP}dp-port-notnight-420dpi",
        widthPx = Math.round(widthDp * DENSITY),
        heightPx = Math.round(HEIGHT_DP * DENSITY),
        guideLongSide = 1280
    )

    fun spec(key: String, widthDp: Int, locale: ShotLocale): ShotSpec = ShotSpec(
        key = key,
        route = "r14:$key",
        profile = profile(widthDp),
        locale = locale,
        seed = { app -> TamaFixtures.seedHealthyPet(app) },
        substitute = true,
        content = {
            when (key) {
                "gate_arena" -> GateArenaShot()
                "farm_field" -> FarmFieldShot()
                "livestock" -> LivestockShot()
                "livestock_empty" -> LivestockEmptyShot()
                "notice_screen" -> InlineModalShot { NoticeScreenShot(isError = false) }
                "notice_error" -> InlineModalShot { NoticeScreenShot(isError = true) }
                "notice_dialog" -> InlineModalShot { NoticeDialogShot() }
                "chat_context_sheet" -> InlineModalShot { ChatContextSheetShot() }
                "chat_progress" -> ChatProgressShot()
                else -> error("Unknown r14 shot '$key'; use $keys")
            }
        }
    )

    // ---- 1) Adventure Gate arena -------------------------------------------------------------

    private fun combatant(
        id: String,
        definitionId: String,
        hp: Int,
        maxHp: Int,
        level: Int = 7,
        boss: Boolean = false,
        enraged: Boolean = false,
        traits: List<AdventureGateEliteTrait> = emptyList(),
        statuses: List<AdventureGateStatusEffect> = emptyList(),
        intent: AdventureGateEnemyIntent? = null
    ) = AdventureGateCombatantState(
        instanceId = id, definitionId = definitionId, isPet = false,
        maxHp = maxHp, hp = hp, maxMana = 30, mana = 30,
        attack = 20, magic = 20, defense = 10, speed = 10, level = level,
        elements = listOf(AdventureGateElement.ARCANE),
        boss = boss, enraged = enraged, traits = traits, statuses = statuses, intent = intent
    )

    private fun arenaBattle(): AdventureGateBattleSnapshot {
        val pet = AdventureGateCombatantState(
            instanceId = "pet", definitionId = "pet", isPet = true,
            maxHp = 120, hp = 84, maxMana = 40, mana = 22,
            attack = 20, magic = 20, defense = 10, speed = 10, level = 7,
            elements = listOf(AdventureGateElement.STRIKE),
            statuses = listOf(AdventureGateStatusEffect("regen", turnsRemaining = 3, hpRegenPercent = 8))
        )
        val minion = AdventureGateCombatantState(
            instanceId = "minion", definitionId = "skeleton_helper", isPet = false, isMinion = true,
            maxHp = 40, hp = 31, maxMana = 0, mana = 0,
            attack = 10, magic = 0, defense = 5, speed = 8, level = 7,
            elements = listOf(AdventureGateElement.SHADOW)
        )
        val enemies = listOf(
            combatant(
                "e1", "libram_lich", hp = 180, maxHp = 400, level = 9, boss = true, enraged = true,
                statuses = listOf(
                    AdventureGateStatusEffect("poison", turnsRemaining = 3, damagePerTurn = 7),
                    AdventureGateStatusEffect("burn", turnsRemaining = 2, damagePerTurn = 5)
                ),
                intent = AdventureGateEnemyIntent("boss_forbidden_index", AdventureGateIntentKind.SPECIAL, 144)
            ),
            combatant(
                "e2", "elite_shelf_golem", hp = 70, maxHp = 90, level = 8,
                traits = listOf(AdventureGateEliteTrait.SHIELD, AdventureGateEliteTrait.THORNS),
                intent = AdventureGateEnemyIntent("boss_deep_tide_prism", AdventureGateIntentKind.SPECIAL, 140)
            ),
            combatant(
                "e3", "bookmark_basilisk", hp = 40, maxHp = 100, level = 8,
                statuses = listOf(
                    AdventureGateStatusEffect("freeze", turnsRemaining = 2, skipTurnChancePercent = 35),
                    AdventureGateStatusEffect("slow", turnsRemaining = 2, speedMultiplierPercent = 70)
                ),
                intent = AdventureGateEnemyIntent("enemy_rest_up", AdventureGateIntentKind.HEAL)
            ),
            combatant(
                "e4", "ink_bat", hp = 55, maxHp = 60, level = 7,
                intent = AdventureGateEnemyIntent("enemy_nature_spore", AdventureGateIntentKind.ATTACK, 104)
            )
        )
        return AdventureGateBattleSnapshot(
            petId = "demo-tama-pet", worldId = "moonmoss_library", phaseNumber = 5, waveIndex = 1,
            turn = AdventureGateTurn.PET, pet = pet, minion = minion, enemies = enemies, log = emptyList(),
            rngSeed = 1L, updatedAt = 0L
        )
    }

    @Composable
    private fun GateArenaShot() {
        val phase = AdventureGateCatalog.world("moonmoss_library").phases.last()
        val battle = remember { arenaBattle() }
        Box(Modifier.fillMaxSize().background(Color(0xFF14091F)).padding(horizontal = 12.dp, vertical = 16.dp)) {
            BattleArena(
                battle = battle,
                phase = phase,
                pet = TamaFixtures.demoPet(),
                selectedTargetId = "e1",
                activeActorId = null,
                damagePopups = listOf(DamagePopup(id = 1L, targetInstanceId = "e2", amount = 23)),
                activeEffectSkillId = null,
                activeEffectFrame = 0,
                onSelectMinion = {}, onSelectEnemy = {}, onInspectMinion = {}, onInspectPet = {}, onInspectEnemy = {},
                onClaim = {}
            )
        }
    }

    // ---- 2) Farm plot area -------------------------------------------------------------------

    private const val NOW = 1_700_000_000_000L
    private const val HOUR = 3_600_000L

    private fun crop(type: String, stage: Int) = PlantedCrop(
        type = type, stage = stage, plantedTime = NOW - 6 * HOUR, lastStageUpdateTime = NOW - HOUR
    )

    /** Plots 0..8: stump, rock, weeds, bare soil, tilled, wet bare, wet growing, tilled growing, ready. */
    private fun farmTiles(): List<FarmTile> = listOf(
        FarmTile(0, obstacle = FarmObstacle.STUMP),
        FarmTile(1, obstacle = FarmObstacle.ROCK),
        FarmTile(2, obstacle = FarmObstacle.WEEDS),
        FarmTile(3),
        FarmTile(4, status = TileStatus.FARMLAND),
        FarmTile(5, status = TileStatus.WET_FARMLAND, lastWateredTime = NOW - 60_000L),
        FarmTile(6, status = TileStatus.WET_FARMLAND, crop = crop("carrot", 1), lastWateredTime = NOW - 60_000L),
        FarmTile(7, status = TileStatus.FARMLAND, crop = crop("wheat", 2)),
        FarmTile(8, status = TileStatus.WET_FARMLAND, crop = crop("tomato", 3), lastWateredTime = NOW - 60_000L)
    )

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FarmFieldShot() {
        val tiles = remember { farmTiles() }
        val pager = rememberPagerState(pageCount = { 1 })
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                modifier = Modifier.fillMaxSize(),
                model = FARM_FIELD_BACKGROUND_ASSET,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = maxWidth - 24.dp
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FarmWeatherChip(FarmWeatherLabel.RAIN)
                        FarmWeatherChip(FarmWeatherLabel.NIGHT)
                    }
                    FarmPlotArea(
                        side = side,
                        tiles = tiles,
                        currentTime = NOW,
                        pagerState = pager,
                        pageCount = 1,
                        onTileClick = {},
                        onTileLongPress = {},
                        tileOverlay = { tile -> FarmTileOverlay(tile, NOW) }
                    )
                    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFFF9F0DA).copy(alpha = 0.94f)) {
                        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val cost = FarmMaterialCost(wood = 12, stone = 6)
                            FarmMaterialCostRow(cost, FarmMaterialStock(wood = 20, stone = 9))
                            FarmMaterialCostRow(cost, FarmMaterialStock(wood = 3, stone = 0))
                            FarmMaterialCostRow(cost, FarmMaterialStock(wood = 3, stone = 0), compact = true)
                        }
                    }
                    ToolSidebar(
                        inventory = emptyList(),
                        selectedTool = null,
                        onToolSelect = {},
                        onPlantingDroneOpen = {},
                        onHarvesterDroneOpen = {},
                        extra = { FarmQuickActionRow({}, clearableCount = 3) }
                    )
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        FarmQuickActionPalette({}, modifier = Modifier.padding(8.dp), clearableCount = 3)
                    }
                }
            }
        }
    }

    // ---- 3) Livestock ------------------------------------------------------------------------

    @Composable
    private fun LivestockShot() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf(0f, 0.6f).forEach { seconds ->
                    listOf(FarmLivestockType.BARN to 1, FarmLivestockType.COOP to 2).forEach { (type, seed) ->
                        val clock = remember { mutableFloatStateOf(seconds) }
                        LivestockSlotCard(
                            type = type,
                            slot = FarmLivestockSlot(occupied = true, storedOutput = 3, lastFedAt = NOW),
                            isHungry = false,
                            canBuy = false,
                            seed = seed,
                            clock = clock,
                            reducedMotion = false,
                            onBuy = {},
                            onClick = {}
                        )
                    }
                }
            }
        }
    }

    /** Empty barn and coop slots with the buy button and a wood/stone cost the pet cannot cover. */
    @Composable
    private fun LivestockEmptyShot() {
        val stock = FarmMaterialStock(wood = 2, stone = 0)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf(FarmLivestockType.BARN, FarmLivestockType.COOP).forEach { type ->
                    LivestockSlotCard(
                        type = type,
                        slot = FarmLivestockSlot(),
                        isHungry = false,
                        canBuy = false,
                        materialCost = FarmMaterialCost(wood = 8, stone = 4),
                        stock = stock,
                        seed = 3,
                        clock = remember { mutableFloatStateOf(0f) },
                        reducedMotion = false,
                        onBuy = {},
                        onClick = {}
                    )
                }
            }
        }
    }

    // ---- 4) Round 15: notices, chat context sheet and prompt progress ------------------------

    /** Enables inline dialogs (like TamaDialogShotCatalog) so popups render in the captured tree. */
    @Composable
    private fun InlineModalShot(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalInlineModalHost provides true) { content() }
    }

    private const val NOTICE_NORMAL = "Harvested 3 carrots (+12 coins)"
    private const val NOTICE_ERROR_LONG =
        "Could not reach the model server: connection refused after 3 retries. " +
            "Check that the server is running, that the port is free and that the phone " +
            "is not in battery saver mode, then try again."

    /** Farm field scene with a [TamaNoticeHost] at the top-left showing an active notice. */
    @Composable
    private fun NoticeScreenShot(isError: Boolean) {
        val state = remember {
            TamaNoticeState().also { if (isError) it.show(NOTICE_ERROR_LONG, isError = true) else it.show(NOTICE_NORMAL) }
        }
        CompositionLocalProvider(LocalTamaNotice provides state) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = FARM_FIELD_BACKGROUND_ASSET,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None
                )
                TamaNoticeHost(state, Modifier.fillMaxSize())
            }
        }
    }

    /** A popup dialog open with an active notice, which must draw inside the dialog. */
    @Composable
    private fun NoticeDialogShot() {
        val state = remember { TamaNoticeState().also { it.show(NOTICE_NORMAL) } }
        CompositionLocalProvider(LocalTamaNotice provides state) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TamaNoticeHost(state, Modifier.fillMaxSize())
                TamaPopupDialog(
                    title = "Notice",
                    backgroundAsset = "tama/backgrounds/popup_generic.png",
                    onDismissRequest = {},
                    bodyContent = {
                        Text("Dialog body content", Modifier.padding(12.dp))
                        Text("The notice above is raised while this dialog is open.", Modifier.padding(horizontal = 12.dp))
                    },
                    footerContent = {}
                )
            }
        }
    }

    /** Pet chat context sheet: 6,400 / 8,192 tokens (78%), auto-compact on (pet not in the disabled set). */
    @Composable
    private fun ChatContextSheetShot() {
        val context = LocalContext.current.applicationContext
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TamaChatContextSheet(
                pet = TamaFixtures.demoPet(),
                usage = TamaContextUsage(usedTokens = 6_400, windowTokens = 8_192),
                generating = false,
                settingsRepo = remember { SettingsRepository(context) },
                agentService = remember { mockk<TamaAgentService>(relaxed = true) },
                onDismiss = {}
            )
        }
    }

    /** Prompt-processing progress line beside the 78% context ring on a Tama surface. */
    @Composable
    private fun ChatProgressShot() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
            TamaLcdSurface(dark = false) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TamaPromptProgressLine(
                            LlamaPromptProcessingProgress(total = 4096, cached = 1024, processed = 2048, timeMs = 900),
                            Modifier.weight(1f)
                        )
                        ContextUsageRing(78, size = 30.dp)
                    }
                }
            }
        }
    }
}
