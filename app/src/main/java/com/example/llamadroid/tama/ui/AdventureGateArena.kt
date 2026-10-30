package com.example.llamadroid.tama.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
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
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
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
import com.example.llamadroid.tama.rpg.AdventureGateStartError
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

private val ArenaCombatantCardWidth = 84.dp
private val ArenaSpriteBoxHeight = 58.dp

/** Floating "-12" / "+8" / "Miss" popup; drawn inside the card, centered over the sprite art. */
@Composable
private fun CombatantDamagePopup(popup: DamagePopup, modifier: Modifier = Modifier) {
    Text(
        text = popup.label ?: if (popup.healing) "+${popup.amount}" else "-${popup.amount}",
        color = popup.color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** Battle arena: background, wave/modifier chips, ally column, enemy grid, skill effect and reward overlay. */
@Composable
internal fun BattleArena(
    battle: AdventureGateBattleSnapshot,
    phase: AdventureGatePhaseDefinition,
    pet: com.example.llamadroid.tama.data.TamaPet,
    selectedTargetId: String?,
    activeActorId: String?,
    damagePopups: List<DamagePopup>,
    activeEffectSkillId: String?,
    activeEffectFrame: Int,
    onSelectMinion: (String) -> Unit,
    onSelectEnemy: (AdventureGateCombatantState) -> Unit,
    onInspectMinion: () -> Unit,
    onInspectPet: () -> Unit,
    onInspectEnemy: (AdventureGateCombatantState) -> Unit,
    onClaim: () -> Unit
) {
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
            currentWave = battle.waveIndex + 1,
            totalWaves = phase.waveMonsterIds.size,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        )
        BattleModifierChip(
            battleModifier = battle.modifier,
            colors = GateCombatColors,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 46.dp)
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
                battle.minion?.takeIf { it.isAlive }?.let { minion ->
                    SkeletonHelperCard(
                        combatant = minion,
                        selected = selectedTargetId == minion.instanceId,
                        active = activeActorId == minion.instanceId,
                        damagePopup = damagePopups.lastOrNull { it.targetInstanceId == minion.instanceId },
                        onSelect = {
                            onSelectMinion(minion.instanceId)
                        },
                        onInspect = onInspectMinion
                    )
                }
                ArenaPetCard(
                    pet = pet,
                    combatant = battle.pet,
                    active = activeActorId == battle.pet.instanceId,
                    damagePopup = damagePopups.lastOrNull { it.targetInstanceId == battle.pet.instanceId },
                    onInspect = onInspectPet
                )
            }
            EnemyGridLayout(
                enemies = battle.enemies,
                selectedTargetId = selectedTargetId,
                activeActorId = activeActorId,
                damagePopups = damagePopups,
                modifier = Modifier,
                onSelect = { enemy ->
                    onSelectEnemy(enemy)
                },
                onInspect = onInspectEnemy
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
        if (battle.isCompleted) {
            BattleRewardOverlay(
                battle = battle,
                phase = phase,
                onClaim = onClaim,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
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
        border = BorderStroke(1.dp, GateAccent.copy(alpha = 0.76f))
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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TamaPetSprite(pet = pet, size = 62.dp)
                damagePopup?.let { CombatantDamagePopup(it) }
            }
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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = "file:///android_asset/tama/adventure_gate/allies/skeleton_helper/idle_0.png",
                    contentDescription = stringResource(R.string.adventure_gate_skill_skeleton_helper),
                    modifier = Modifier.size(46.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None
                )
                damagePopup?.let { CombatantDamagePopup(it) }
            }
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
            EnemyIntentChip(
                enemy = enemy,
                colors = GateCombatColors,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ArenaSpriteBoxHeight),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = "file:///android_asset/${monster.assetBasePath}/idle_0.png",
                    contentDescription = stringResource(monster.nameRes),
                    modifier = Modifier
                        .size(ArenaSpriteBoxHeight)
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
                    text = stringResource(R.string.adventure_gate_enemy_level_short, enemy.level),
                    color = GateDark,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(GateAccent.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                damagePopup?.let { CombatantDamagePopup(it) }
            }
            Text(
                text = stringResource(monster.nameRes),
                color = if (enemy.isAlive) GateText else GateText.copy(alpha = 0.35f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            EnemyEliteTags(enemy = enemy, colors = GateCombatColors)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                enemy.statuses.take(4).forEach { status ->
                    StatusIcon(status.id, modifier = Modifier.size(14.dp))
                }
            }
            HpBar(current = enemy.hp, max = enemy.maxHp, color = if (enemy.boss) GateDanger else GateGreen)
        }
    }
}
