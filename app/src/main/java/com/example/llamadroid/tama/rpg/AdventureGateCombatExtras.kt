package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Round-13 combat mechanics shared by the Gate catalog, the engine and the UI:
 * battle modifiers (phase modifiers and Night Arena omens), elite traits, telegraphed enemy intents
 * and the weighted enemy AI that chooses them. Every roll uses the battle's seeded [Random] so a
 * snapshot replays identically. All persisted fields default safely so older saves keep loading.
 */
enum class AdventureGateBattleModifier(
    val nameRes: Int,
    val descriptionRes: Int,
    val omenRes: Int
) {
    FOG(R.string.adventure_gate_modifier_fog, R.string.adventure_gate_modifier_fog_desc, R.string.adventure_gate_omen_fog),
    TIDE(R.string.adventure_gate_modifier_tide, R.string.adventure_gate_modifier_tide_desc, R.string.adventure_gate_omen_tide),
    HEATWAVE(R.string.adventure_gate_modifier_heatwave, R.string.adventure_gate_modifier_heatwave_desc, R.string.adventure_gate_omen_heatwave),
    MOONLIGHT(R.string.adventure_gate_modifier_moonlight, R.string.adventure_gate_modifier_moonlight_desc, R.string.adventure_gate_omen_moonlight),
    CLOCKWORK_TICK(R.string.adventure_gate_modifier_clockwork_tick, R.string.adventure_gate_modifier_clockwork_tick_desc, R.string.adventure_gate_omen_clockwork_tick),
    STARFALL(R.string.adventure_gate_modifier_starfall, R.string.adventure_gate_modifier_starfall_desc, R.string.adventure_gate_omen_starfall),
    SLEEPY_MOON(R.string.adventure_gate_modifier_sleepy_moon, R.string.adventure_gate_modifier_sleepy_moon_desc, R.string.adventure_gate_omen_sleepy_moon);

    companion object {
        const val CRIT_MULTIPLIER = 1.5f
        const val TICK_PERCENT = 5
        const val TICK_EVERY_ROUNDS = 3
        const val YAWN_CHANCE_PERCENT = 8
    }
}

/** Relative hit-chance factor applied to every non-guaranteed attack (both sides). */
fun AdventureGateBattleModifier?.accuracyFactor(): Float =
    if (this == AdventureGateBattleModifier.FOG) 0.85f else 1f

/** Damage factor for one hit of [element] (and magic) under this modifier, for either side. */
fun AdventureGateBattleModifier?.damageFactor(element: AdventureGateElement, magic: Boolean): Float {
    var factor = 1f
    when (this) {
        AdventureGateBattleModifier.TIDE -> if (element == AdventureGateElement.WATER) factor *= 1.2f
        AdventureGateBattleModifier.HEATWAVE -> if (element == AdventureGateElement.FIRE) factor *= 1.2f
        AdventureGateBattleModifier.MOONLIGHT -> if (magic) factor *= 1.15f
        else -> Unit
    }
    return factor
}

/** Flat mana the pet loses at the start of each round (Heatwave: mana regen -1). */
fun AdventureGateBattleModifier?.manaDrainPerRound(): Int =
    if (this == AdventureGateBattleModifier.HEATWAVE) 1 else 0

/** Critical-hit chance in percent; the game has no base crits, only Starfall grants them. */
fun AdventureGateBattleModifier?.critChancePercent(): Int =
    if (this == AdventureGateBattleModifier.STARFALL) 10 else 0

fun AdventureGateBattleModifier?.hasClockworkTick(): Boolean =
    this == AdventureGateBattleModifier.CLOCKWORK_TICK

fun AdventureGateBattleModifier?.yawnChancePercent(): Int =
    if (this == AdventureGateBattleModifier.SLEEPY_MOON) AdventureGateBattleModifier.YAWN_CHANCE_PERCENT else 0

/** Extra trait carried by an elite variant on top of its ×1.35 stats. */
enum class AdventureGateEliteTrait(val nameRes: Int) {
    THORNS(R.string.adventure_gate_trait_thorns),
    REGEN(R.string.adventure_gate_trait_regen),
    SHIELD(R.string.adventure_gate_trait_shield),
    HASTE(R.string.adventure_gate_trait_haste)
}

enum class AdventureGateIntentKind {
    ATTACK,
    SPECIAL,
    HEAL,
    GUARD
}

/**
 * What an enemy has telegraphed for its next action. [damagePercent] is the expected damage
 * multiplier against the pet (move power x elemental effectiveness x enrage) in percent, so the UI
 * can show "x1.3"; it is 0 for heal and guard intents.
 */
@Serializable
data class AdventureGateEnemyIntent(
    val actionId: String,
    val kind: AdventureGateIntentKind,
    val damagePercent: Int = 0
)

/** Weighted, seeded enemy AI. Chooses the next action and describes it as a telegraphed intent. */
internal object AdventureGateEnemyAi {
    const val HEAL_ACTION_ID = "enemy_rest_up"
    const val GUARD_ACTION_ID = "enemy_brace_up"
    const val FALLBACK_ACTION_ID = "enemy_strike_tap"
    const val HEAL_PERCENT = 20
    private const val BIG_HIT_PERCENT = 30
    private const val LOW_HP_PERCENT = 35

    fun isUsable(
        action: AdventureGateEnemyActionDefinition,
        enemy: AdventureGateCombatantState,
        cooldowns: Map<String, Int>
    ): Boolean = enemy.mana >= action.manaCost && (cooldowns[action.id] ?: 0) <= 0

    fun chooseAction(
        enemy: AdventureGateCombatantState,
        pet: AdventureGateCombatantState,
        cooldowns: Map<String, Int>,
        rng: Random
    ): AdventureGateEnemyActionDefinition {
        val monster = AdventureGateCatalog.monster(enemy.definitionId)
        val candidates = mutableListOf<Pair<AdventureGateEnemyActionDefinition, Int>>()
        fun bias(action: AdventureGateEnemyActionDefinition): Int = when (action.element) {
            in pet.weaknesses -> 22
            in pet.resistances -> -6
            else -> 0
        }
        monster.attackActionIds.mapNotNull(AdventureGateCatalog::enemyActionOrNull).forEach {
            candidates += it to (10 + bias(it)).coerceAtLeast(2)
        }
        monster.magicActionIds.mapNotNull(AdventureGateCatalog::enemyActionOrNull)
            .filter { isUsable(it, enemy, cooldowns) }
            .forEach { candidates += it to (14 + bias(it)).coerceAtLeast(2) }
        monster.specialActionId?.let(AdventureGateCatalog::enemyActionOrNull)
            ?.takeIf { enemy.boss && isUsable(it, enemy, cooldowns) }
            ?.let { special ->
                val charged = enemy.enraged || enemy.hp * 2 <= enemy.maxHp
                candidates += special to if (charged) 70 else 45
            }
        AdventureGateCatalog.enemyActionOrNull(HEAL_ACTION_ID)
            ?.takeIf { enemy.hp * 100 <= enemy.maxHp * LOW_HP_PERCENT && isUsable(it, enemy, cooldowns) }
            ?.let { candidates += it to if (enemy.boss) 35 else 55 }
        AdventureGateCatalog.enemyActionOrNull(GUARD_ACTION_ID)
            ?.takeIf { enemy.lastHitTaken * 100 >= enemy.maxHp * BIG_HIT_PERCENT && isUsable(it, enemy, cooldowns) }
            ?.let { candidates += it to 50 }
        if (candidates.isEmpty()) return AdventureGateCatalog.enemyAction(FALLBACK_ACTION_ID)
        var roll = rng.nextInt(candidates.sumOf { it.second })
        for ((action, weight) in candidates) {
            if (roll < weight) return action
            roll -= weight
        }
        return candidates.last().first
    }

    fun intentFor(
        action: AdventureGateEnemyActionDefinition,
        enemy: AdventureGateCombatantState,
        pet: AdventureGateCombatantState
    ): AdventureGateEnemyIntent {
        val monster = AdventureGateCatalog.monster(enemy.definitionId)
        return when (action.kind) {
            AdventureGateSkillKind.HEAL -> AdventureGateEnemyIntent(action.id, AdventureGateIntentKind.HEAL)
            AdventureGateSkillKind.GUARD -> AdventureGateEnemyIntent(action.id, AdventureGateIntentKind.GUARD)
            else -> {
                val element = when (action.element) {
                    in pet.weaknesses -> 150
                    in pet.resistances -> 60
                    else -> 100
                }
                val rage = if (enemy.enraged || (enemy.boss && enemy.hp * 2 <= enemy.maxHp)) 120 else 100
                AdventureGateEnemyIntent(
                    actionId = action.id,
                    kind = if (action.id == monster.specialActionId) AdventureGateIntentKind.SPECIAL else AdventureGateIntentKind.ATTACK,
                    damagePercent = action.power * element / 100 * rage / 100
                )
            }
        }
    }

    /** Seeded generator for the upcoming round's intents; independent from the round's combat rolls. */
    fun intentRandom(snapshot: AdventureGateBattleSnapshot): Random =
        Random(
            snapshot.rngSeed * 31L +
                (snapshot.actionSequence + 1).toLong() * 1_000_033L +
                snapshot.waveIndex.toLong() * 9_973L + 7L
        )
}

/** In-character enrage one-liners per boss; the log entry carries the monster id in `skillId`. */
object AdventureGateBossLines {
    private val lineRes: Map<String, Int> = mapOf(
        "bramble_guard" to R.string.adventure_gate_boss_enrage_bramble_guard,
        "verdant_crown_moth" to R.string.adventure_gate_boss_enrage_verdant_crown_moth,
        "furnace_foreman" to R.string.adventure_gate_boss_enrage_furnace_foreman,
        "molten_marionette" to R.string.adventure_gate_boss_enrage_molten_marionette,
        "tide_knuckle" to R.string.adventure_gate_boss_enrage_tide_knuckle,
        "glassfin_leviathan" to R.string.adventure_gate_boss_enrage_glassfin_leviathan,
        "brass_roc" to R.string.adventure_gate_boss_enrage_brass_roc,
        "chrono_chimera" to R.string.adventure_gate_boss_enrage_chrono_chimera,
        "bookmark_basilisk" to R.string.adventure_gate_boss_enrage_bookmark_basilisk,
        "libram_lich" to R.string.adventure_gate_boss_enrage_libram_lich,
        "nutcracker_knight" to R.string.adventure_gate_boss_enrage_nutcracker_knight,
        "aurora_wyrm" to R.string.adventure_gate_boss_enrage_aurora_wyrm,
        "eclipse_herald" to R.string.adventure_gate_boss_enrage_eclipse_herald,
        "astra_null_regent" to R.string.adventure_gate_boss_enrage_astra_null_regent
    )

    val bossIds: Set<String> get() = lineRes.keys

    /** String resource of the boss's enrage line, or null for a non-boss / unknown id. */
    fun enrageLineRes(monsterId: String?): Int? = monsterId?.let(lineRes::get)
}
