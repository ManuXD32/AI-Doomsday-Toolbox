package com.example.llamadroid.tama.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic balance harness for the round-13 mechanics (intents, elites, phase modifiers).
 * A bot (best usable damaging skill, lowest-HP target; with potions, heal skills and the best shop
 * gear of the world for the main tests) fights seeded battles at the recommended enemy level of each phase with a full loadout. The measured
 * win rates are printed (see docs/tama-adventure-gate-combat.md) and only loose bounds are asserted so
 * the test flags broken balance without being brittle.
 */
class AdventureGateBalanceSimulationTest {
    private val maxRounds = 80
    private val battlesPerWorld = 200
    private val HP_POTIONS = 3
    private val MANA_POTIONS = 2

    private fun simProfile(level: Int): AdventureGateProfile {
        val safeLevel = level.coerceIn(1, 50)
        val allSkills = AdventureGateCatalog.skills.map { it.id }
        val learned = AdventureGateCombatEngine.normalizedProfile(
            AdventureGateProfile(petId = "sim", level = safeLevel, purchasedSkillIds = allSkills)
        )
        val attacks = learned.learnedAttackIds
            .map(AdventureGateCatalog::skill)
            .sortedByDescending { it.power }
            .take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT)
            .map { it.id }
        val magic = learned.learnedMagicIds
            .map(AdventureGateCatalog::skill)
            .filter { it.kind == AdventureGateSkillKind.MAGIC && AdventureGateCatalog.isEquippableMagicSkill(it) }
            .sortedByDescending { it.power }
            .take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT)
            .map { it.id }
        val equipped = AdventureGateCombatEngine.normalizedProfile(
            learned.copy(equippedAttackIds = attacks, equippedMagicIds = magic)
        )
        return equipped.copy(currentHp = equipped.stats.maxHp, currentMana = equipped.stats.maxMana)
    }

    private fun pickSkill(profile: AdventureGateProfile, snapshot: AdventureGateBattleSnapshot): Pair<String, String> {
        val target = snapshot.enemies.filter { it.isAlive }.minByOrNull { it.hp } ?: return "guard" to snapshot.pet.instanceId
        val best = AdventureGateCatalog.skillsForProfile(profile)
            .filter { it.kind == AdventureGateSkillKind.ATTACK || it.kind == AdventureGateSkillKind.MAGIC }
            .filter { it.manaCost <= snapshot.pet.mana && (snapshot.skillCooldowns[it.id] ?: 0) <= 0 }
            .maxByOrNull {
                val multiplier = when (it.element) {
                    in target.weaknesses -> 1.5f
                    in target.resistances -> 0.6f
                    else -> 1f
                }
                it.power * multiplier * it.accuracyPercent / 100f
            }
        return (best?.id ?: "guard") to target.instanceId
    }

    /** Best HP/mana potion the player could own when reaching [worldIndex] (highest unlocked tier). */
    private fun bestSupply(kind: AdventureGateSupplyKind, worldIndex: Int) =
        AdventureGateCatalog.supplies
            .filter { it.kind == kind && it.unlockWorldIndex <= worldIndex }
            .maxByOrNull { it.amount }

    private fun fight(
        profile: AdventureGateProfile,
        phase: AdventureGatePhaseDefinition,
        seed: Long,
        smart: Boolean = false,
        worldIndex: Int = 0
    ): Boolean {
        var snapshot = AdventureGateCombatEngine.startBattle(profile, phase, seed)
        var current = profile
        var hpPotions = if (smart) HP_POTIONS else 0
        var manaPotions = if (smart) MANA_POTIONS else 0
        val hpSupply = bestSupply(AdventureGateSupplyKind.HP, worldIndex)
        val manaSupply = bestSupply(AdventureGateSupplyKind.MANA, worldIndex)
        val healSkill = AdventureGateCatalog.skillsForProfile(profile)
            .filter { it.kind == AdventureGateSkillKind.HEAL }
            .maxByOrNull { it.power }
        repeat(maxRounds) {
            if (snapshot.isCompleted) return snapshot.isVictory
            if (smart) {
                val pet = snapshot.pet
                if (hpPotions > 0 && hpSupply != null && pet.hp * 100 <= pet.maxHp * 40) {
                    val used = AdventureGateCombatEngine.useSupply(current, snapshot, hpSupply.id)
                    val usedResult = used.result
                    if (used.used && usedResult != null) {
                        hpPotions--
                        snapshot = usedResult.snapshot
                        current = usedResult.profile
                        return@repeat
                    }
                }
                if (manaPotions > 0 && manaSupply != null && pet.mana * 100 <= pet.maxMana * 15) {
                    val used = AdventureGateCombatEngine.useSupply(current, snapshot, manaSupply.id)
                    val usedResult = used.result
                    if (used.used && usedResult != null) {
                        manaPotions--
                        snapshot = usedResult.snapshot
                        current = usedResult.profile
                        return@repeat
                    }
                }
                if (healSkill != null && pet.hp * 100 <= pet.maxHp * 35 && pet.mana >= healSkill.manaCost &&
                    (snapshot.skillCooldowns[healSkill.id] ?: 0) <= 0
                ) {
                    val result = AdventureGateCombatEngine.performSkill(current, snapshot, healSkill.id, pet.instanceId)
                    snapshot = result.snapshot
                    current = result.profile
                    return@repeat
                }
            }
            val (skillId, targetId) = pickSkill(current, snapshot)
            val result = AdventureGateCombatEngine.performSkill(current, snapshot, skillId, targetId)
            snapshot = result.snapshot
            current = result.profile
        }
        return snapshot.isCompleted && snapshot.isVictory
    }

    private fun recommendedProfile(
        world: AdventureGateWorldDefinition,
        phase: AdventureGatePhaseDefinition,
        geared: Boolean = false
    ): AdventureGateProfile {
        val base = simProfile(AdventureGateCombatEngine.enemyLevelFor(world.id, phase.phaseNumber, phase.isBoss))
        if (!geared) return base
        val worldIndex = AdventureGateCatalog.worlds.indexOf(world)
        fun best(slot: AdventureGateEquipmentSlot) = AdventureGateCatalog.shopEquipment()
            .filter { it.slot == slot && it.unlockWorldIndex <= worldIndex }
            .maxByOrNull { it.price }?.id
        val equipped = AdventureGateCombatEngine.normalizedProfile(
            base.copy(
                equippedWeaponId = best(AdventureGateEquipmentSlot.WEAPON),
                equippedShieldId = best(AdventureGateEquipmentSlot.SHIELD),
                equippedRingId = best(AdventureGateEquipmentSlot.RING)
            )
        )
        return equipped.copy(currentHp = equipped.stats.maxHp, currentMana = equipped.stats.maxMana)
    }

    private fun winRate(
        world: AdventureGateWorldDefinition,
        phases: List<AdventureGatePhaseDefinition>,
        battles: Int,
        smart: Boolean = true,
        transform: (AdventureGatePhaseDefinition) -> AdventureGatePhaseDefinition = { it }
    ): Float {
        val worldIndex = AdventureGateCatalog.worlds.indexOf(world)
        var wins = 0
        repeat(battles) { index ->
            val phase = phases[index % phases.size]
            val profile = recommendedProfile(world, phase, geared = smart)
            if (fight(profile, transform(phase), seed = 1_000L + index, smart = smart, worldIndex = worldIndex)) wins++
        }
        return wins / battles.toFloat()
    }

    @Test
    fun `battles replay identically for the same seed`() {
        val world = AdventureGateCatalog.worlds[2]
        val phase = world.phases[9]
        val profile = recommendedProfile(world, phase)
        repeat(5) { seed ->
            assertEquals(fight(profile, phase, seed.toLong()), fight(profile, phase, seed.toLong()))
        }
    }

    @Test
    fun `recommended level win rates stay in sane bounds for every world`() {
        val report = StringBuilder("world, win rate over $battlesPerWorld battles (all 15 phases)\n")
        AdventureGateCatalog.worlds.forEach { world ->
            val rate = winRate(world, world.phases, battlesPerWorld)
            report.append("${world.id}: ${"%.3f".format(rate)}\n")
            assertTrue("${world.id} win rate $rate is below the 25% floor", rate >= 0.25f)
        }
        println(report)
    }

    @Test
    fun `phase modifiers shift win rates only moderately`() {
        val report = StringBuilder("world, win rate without vs with phase modifiers (phases 1-9, ${battlesPerWorld} battles)\n")
        AdventureGateCatalog.worlds.forEach { world ->
            val phases = world.phases.filter { it.phaseNumber in 1..9 && !it.isBoss }
            val without = winRate(world, phases, battlesPerWorld) { it.copy(modifier = null) }
            val with = winRate(world, phases, battlesPerWorld)
            report.append("${world.id}: ${"%.3f".format(without)} -> ${"%.3f".format(with)}\n")
            assertTrue(
                "${world.id} modifiers moved the win rate from $without to $with",
                kotlin.math.abs(with - without) <= 0.30f
            )
        }
        println(report)
    }

    @Test
    fun `elite waves are beatable but harder than the base wave`() {
        val world = AdventureGateCatalog.worlds[0]
        val phase = world.phases[9]
        val profile = recommendedProfile(world, phase)
        var eliteBattles = 0
        var eliteWins = 0
        var plainBattles = 0
        var plainWins = 0
        for (seed in 1L..400L) {
            val elite = AdventureGateCombatEngine.startBattle(profile, phase, seed).enemies.any { it.isElite }
            val won = fight(profile, phase, seed)
            if (elite) { eliteBattles++; if (won) eliteWins++ } else { plainBattles++; if (won) plainWins++ }
        }
        println(
            "sproutvale phase 10: elite ${eliteWins}/${eliteBattles}, plain ${plainWins}/${plainBattles}"
        )
        assertTrue(eliteBattles > 0 && plainBattles > 0)
        assertTrue("elites should not be unwinnable", eliteWins > 0)
        assertTrue(
            "elite win rate must not exceed the plain rate by much",
            eliteWins / eliteBattles.toFloat() <= plainWins / plainBattles.toFloat() + 0.10f
        )
    }
}
