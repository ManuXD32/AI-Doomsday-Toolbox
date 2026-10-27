package com.example.llamadroid.tama.ui

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.tama.rpg.AdventureGateBattleModifier
import com.example.llamadroid.tama.rpg.AdventureGateBattleLogEntry
import com.example.llamadroid.tama.rpg.AdventureGateBossLines
import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateCombatantState
import com.example.llamadroid.tama.rpg.AdventureGateIntentKind
import com.example.llamadroid.tama.rpg.AdventureGateLogMessage
import java.util.Locale

/** Palette handed in by [AdventureGateScreen] so these composables follow the screen's colours. */
internal data class GateCombatUiColors(
    val panel: Color,
    val accent: Color,
    val text: Color,
    val danger: Color,
    val blue: Color
)

/** Telegraphed intent chip ("Fire Bolt ×1.3") that owns the top row of an enemy card at the width the caller gives it (centered, one line, ellipsized); renders nothing without an intent. */
@Composable
internal fun EnemyIntentChip(
    enemy: AdventureGateCombatantState,
    colors: GateCombatUiColors,
    modifier: Modifier = Modifier
) {
    val intent = enemy.intent?.takeIf { enemy.isAlive } ?: return
    val action = AdventureGateCatalog.enemyActionOrNull(intent.actionId) ?: return
    val enemyName = stringResource(AdventureGateCatalog.monster(enemy.definitionId).nameRes)
    val actionName = stringResource(action.nameRes)
    val multiplier = String.format(Locale.getDefault(), "%.1f", intent.damagePercent / 100f)
    val label = when (intent.kind) {
        AdventureGateIntentKind.ATTACK,
        AdventureGateIntentKind.SPECIAL -> stringResource(R.string.adventure_gate_intent_chip, actionName, multiplier)
        AdventureGateIntentKind.HEAL,
        AdventureGateIntentKind.GUARD -> actionName
    }
    val description = when (intent.kind) {
        AdventureGateIntentKind.ATTACK -> stringResource(R.string.adventure_gate_intent_attack_desc, enemyName, actionName, multiplier)
        AdventureGateIntentKind.SPECIAL -> stringResource(R.string.adventure_gate_intent_special_desc, enemyName, actionName, multiplier)
        AdventureGateIntentKind.HEAL -> stringResource(R.string.adventure_gate_intent_heal_desc, enemyName)
        AdventureGateIntentKind.GUARD -> stringResource(R.string.adventure_gate_intent_guard_desc, enemyName)
    }
    val tint = when (intent.kind) {
        AdventureGateIntentKind.ATTACK,
        AdventureGateIntentKind.SPECIAL -> colors.danger
        AdventureGateIntentKind.HEAL,
        AdventureGateIntentKind.GUARD -> colors.blue
    }
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        color = colors.panel.copy(alpha = 0.88f),
        contentColor = tint,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.7f))
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Elite · Thorns" tag line for elite variants; renders nothing for normal monsters. */
@Composable
internal fun EnemyEliteTags(
    enemy: AdventureGateCombatantState,
    colors: GateCombatUiColors,
    modifier: Modifier = Modifier
) {
    if (!enemy.isElite) return
    // `map` is inline (composable calls allowed); joinToString's transform is not.
    val traits = enemy.traits.map { stringResource(it.nameRes) }.joinToString(" · ")
    Text(
        text = stringResource(R.string.adventure_gate_elite_badge) + " · " + traits,
        modifier = modifier,
        color = colors.accent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Battle banner chip with the active phase modifier / omen ("Fog: Accuracy -15% for both sides."). */
@Composable
internal fun BattleModifierChip(
    battleModifier: AdventureGateBattleModifier?,
    colors: GateCombatUiColors,
    modifier: Modifier = Modifier
) {
    val active = battleModifier ?: return
    val name = stringResource(active.nameRes)
    val banner = stringResource(R.string.adventure_gate_modifier_banner, name)
    Surface(
        modifier = modifier
            .widthIn(max = 210.dp)
            .semantics { contentDescription = banner },
        color = colors.panel.copy(alpha = 0.86f),
        contentColor = colors.blue,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.62f))
    ) {
        Text(
            text = stringResource(R.string.adventure_gate_modifier_line, name, stringResource(active.descriptionRes)),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Pre-battle block: the phase modifier, or the night's omen heading and intro line in the Night Arena. */
@Composable
internal fun PhaseModifierBlock(
    battleModifier: AdventureGateBattleModifier?,
    nightArena: Boolean,
    colors: GateCombatUiColors,
    modifier: Modifier = Modifier
) {
    val active = battleModifier ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (nightArena) {
            Text(
                text = stringResource(R.string.adventure_gate_omen_title),
                color = colors.accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = stringResource(active.omenRes),
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        Text(
            text = stringResource(
                R.string.adventure_gate_modifier_line,
                stringResource(active.nameRes),
                stringResource(active.descriptionRes)
            ),
            color = colors.blue,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

/** Log text for the round-13 combat messages (thorns, enemy heal/guard, boss lines, crits, ticks, yawns). */
internal fun gateCombatLogText(resources: Resources, entry: AdventureGateBattleLogEntry): String {
    fun name(res: Int?) = res?.let { runCatching { resources.getString(it) }.getOrNull() }.orEmpty()
    return when (entry.messageKey) {
        AdventureGateLogMessage.THORNS_REFLECTED ->
            resources.getString(R.string.adventure_gate_log_thorns, name(entry.actorNameRes), entry.amount)
        AdventureGateLogMessage.ENEMY_HEALED ->
            resources.getString(R.string.adventure_gate_log_enemy_healed, name(entry.actorNameRes), entry.amount)
        AdventureGateLogMessage.ENEMY_GUARDED ->
            resources.getString(R.string.adventure_gate_log_enemy_guarded, name(entry.actorNameRes))
        AdventureGateLogMessage.BOSS_ENRAGED -> {
            val line = AdventureGateBossLines.enrageLineRes(entry.skillId)?.let { name(it) }.orEmpty()
            if (line.isBlank()) {
                name(entry.actorNameRes)
            } else {
                resources.getString(R.string.adventure_gate_log_boss_enraged, name(entry.actorNameRes), line)
            }
        }
        AdventureGateLogMessage.CRITICAL_HIT -> resources.getString(R.string.adventure_gate_log_critical)
        AdventureGateLogMessage.MODIFIER_TICK ->
            resources.getString(R.string.adventure_gate_log_modifier_tick, entry.amount)
        AdventureGateLogMessage.YAWNED ->
            if (entry.actorNameRes == null) {
                resources.getString(R.string.adventure_gate_log_pet_yawned)
            } else {
                resources.getString(R.string.adventure_gate_log_yawned, name(entry.actorNameRes))
            }
        else -> ""
    }
}
