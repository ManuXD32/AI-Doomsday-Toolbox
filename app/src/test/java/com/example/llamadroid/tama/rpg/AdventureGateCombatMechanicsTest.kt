package com.example.llamadroid.tama.rpg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class AdventureGateCombatMechanicsTest {
    private val profile = AdventureGateProfile(petId = "pet")

    private fun phase(worldId: String, number: Int) = AdventureGateCatalog.world(worldId).phases[number - 1]

    private fun enemyOf(id: String, hp: Int? = null): AdventureGateCombatantState {
        val battle = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L)
        val base = battle.enemies.first()
        val monster = AdventureGateCatalog.monster(id)
        return base.copy(
            definitionId = id,
            maxHp = hp ?: 500,
            hp = hp ?: 500,
            maxMana = 60,
            mana = 60,
            boss = monster.isBoss,
            elements = listOfNotNull(monster.primaryElement, monster.secondaryElement),
            weaknesses = monster.weaknesses.toList(),
            resistances = monster.resistances.toList()
        )
    }

    // ---- 1. Telegraphed intents -------------------------------------------------------------

    @Test
    fun `every living enemy telegraphs an intent from the first round and replays by seed`() {
        val first = AdventureGateCombatEngine.startBattle(profile, phase("ember_toyworks", 4), seed = 11L)
        val again = AdventureGateCombatEngine.startBattle(profile, phase("ember_toyworks", 4), seed = 11L)
        assertTrue(first.enemies.all { it.intent != null })
        assertEquals(first.enemies.map { it.intent }, again.enemies.map { it.intent })
    }

    @Test
    fun `intents vary with the seed for enemies that have more than one move`() {
        val seen = (1L..60L).map {
            AdventureGateCombatEngine.startBattle(profile, phase("ember_toyworks", 1), seed = it).enemies.first().intent?.actionId
        }.toSet()
        assertTrue("expected several distinct intents, saw $seen", seen.size >= 2)
    }

    @Test
    fun `the telegraphed intent is the action the enemy executes`() {
        val battle = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 9L)
        val intent = battle.enemies.first().intent
        assertNotNull(intent)
        val fast = battle.copy(enemies = battle.enemies.map { it.copy(speed = 999) })
        val result = AdventureGateCombatEngine.performSkill(profile, fast, "guard", fast.pet.instanceId)
        val acted = result.snapshot.log.first {
            (it.messageKey == AdventureGateLogMessage.ENEMY_USED_ATTACK || it.messageKey == AdventureGateLogMessage.MISSED) &&
                it.actorInstanceId != "pet"
        }
        assertEquals(intent!!.actionId, acted.skillId)
        assertTrue("a new intent is telegraphed for the next round", result.snapshot.enemies.first().intent != null)
    }

    @Test
    fun `ai heals when low guards after a big hit and never at full health`() {
        val pet = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L).pet
        val low = enemyOf("dewcap_slime", hp = 1000).copy(hp = 100)
        val healShare = (1L..400L).count {
            AdventureGateEnemyAi.chooseAction(low, pet, emptyMap(), Random(it)).kind == AdventureGateSkillKind.HEAL
        } / 400f
        assertTrue("heal share $healShare", healShare > 0.6f)

        val struck = enemyOf("dewcap_slime", hp = 1000).copy(lastHitTaken = 400)
        val guardShare = (1L..400L).count {
            AdventureGateEnemyAi.chooseAction(struck, pet, emptyMap(), Random(it)).kind == AdventureGateSkillKind.GUARD
        } / 400f
        assertTrue("guard share $guardShare", guardShare > 0.6f)

        val healthy = enemyOf("dewcap_slime", hp = 1000)
        assertTrue((1L..300L).none {
            AdventureGateEnemyAi.chooseAction(healthy, pet, emptyMap(), Random(it)).kind in
                setOf(AdventureGateSkillKind.HEAL, AdventureGateSkillKind.GUARD)
        })
        val onCooldown = mapOf(AdventureGateEnemyAi.HEAL_ACTION_ID to 3, AdventureGateEnemyAi.GUARD_ACTION_ID to 2)
        assertTrue((1L..300L).none {
            AdventureGateEnemyAi.chooseAction(low.copy(lastHitTaken = 400), pet, onCooldown, Random(it)).kind in
                setOf(AdventureGateSkillKind.HEAL, AdventureGateSkillKind.GUARD)
        })
    }

    @Test
    fun `ai prefers the element the pet is weak to and reports the multiplier`() {
        val neutralPet = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L).pet
        val weakPet = neutralPet.copy(weaknesses = listOf(AdventureGateElement.FIRE))
        val cinder = enemyOf("cinder_pup")
        fun fireShare(pet: AdventureGateCombatantState) = (1L..500L).count {
            AdventureGateEnemyAi.chooseAction(cinder, pet, emptyMap(), Random(it)).element == AdventureGateElement.FIRE
        } / 500f
        assertTrue(fireShare(weakPet) > fireShare(neutralPet) + 0.1f)
        val fire = AdventureGateCatalog.enemyAction("enemy_fire_spark")
        assertEquals(fire.power * 150 / 100, AdventureGateEnemyAi.intentFor(fire, cinder, weakPet).damagePercent)
        assertEquals(fire.power, AdventureGateEnemyAi.intentFor(fire, cinder, neutralPet).damagePercent)
    }

    @Test
    fun `a charged boss favours its special`() {
        val pet = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L).pet
        val boss = enemyOf("bramble_guard", hp = 1000)
        val special = AdventureGateCatalog.monster("bramble_guard").specialActionId
        fun share(enemy: AdventureGateCombatantState) = (1L..400L).count {
            AdventureGateEnemyAi.chooseAction(enemy, pet, emptyMap(), Random(it)).id == special
        } / 400f
        assertTrue(share(boss.copy(hp = 400)) > share(boss) + 0.05f)
        assertTrue(share(boss) > 0f)
    }

    // ---- 2. Elite variants ------------------------------------------------------------------

    @Test
    fun `each world has one elite variant with scaled stats a trait and a stable name key`() {
        val elites = AdventureGateCatalog.monsters.filter { it.eliteOfId != null }
        assertEquals(AdventureGateCatalog.WORLD_COUNT, elites.size)
        elites.forEach { elite ->
            val base = AdventureGateCatalog.monster(elite.eliteOfId.orEmpty())
            assertEquals("elite_${base.id}", elite.id)
            assertNotNull(elite.eliteTrait)
            assertEquals(base.assetBasePath, elite.assetBasePath)
            assertEquals((base.stats.maxHp * 135 / 100f).roundToInt(), elite.stats.maxHp)
            assertEquals((base.stats.attack * 135 / 100f).roundToInt(), elite.stats.attack)
            assertTrue(elite.nameRes != 0 && elite.nameRes != base.nameRes)
            assertEquals(elite.id, AdventureGateCatalog.eliteVariantIdFor(base.id))
        }
    }

    @Test
    fun `elites appear in phases ten to fourteen about a quarter of the time and never elsewhere`() {
        val p10 = phase("sproutvale_gate", 10)
        val elitesSeen = (1L..400L).count { seed ->
            AdventureGateCombatEngine.startBattle(profile, p10, seed = seed).enemies.any { it.isElite }
        }
        val share = elitesSeen / 400f
        assertTrue("elite share $share", share in 0.15f..0.35f)
        val again = (1L..400L).count { seed ->
            AdventureGateCombatEngine.startBattle(profile, p10, seed = seed).enemies.any { it.isElite }
        }
        assertEquals(elitesSeen, again)

        AdventureGateCatalog.worlds.forEach { world ->
            world.phases.filter { it.phaseNumber !in 10..14 }.forEach { phase ->
                (1L..60L).forEach { seed ->
                    assertFalse(
                        "${world.id} ${phase.phaseNumber} must not roll elites",
                        AdventureGateCombatEngine.startBattle(profile, phase, seed = seed).enemies.any { it.isElite }
                    )
                }
            }
        }
        val elite = (1L..400L).map { AdventureGateCombatEngine.startBattle(profile, p10, seed = it) }
            .flatMap { it.enemies }.first { it.isElite }
        assertEquals("elite_pebble_snail", elite.definitionId)
        assertEquals(listOf(AdventureGateEliteTrait.THORNS), elite.traits)
    }

    @Test
    fun `shield elite absorbs damage before hp and thorns pricks physical attackers only`() {
        val base = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L)
        val target = base.enemies.first().copy(
            maxHp = 5000, hp = 5000, shieldHp = 3000,
            traits = listOf(AdventureGateEliteTrait.SHIELD, AdventureGateEliteTrait.THORNS)
        )
        var sawHit = false
        for (seed in 1L..40L) {
            val battle = base.copy(rngSeed = seed, enemies = listOf(target))
            val result = AdventureGateCombatEngine.performSkill(profile, battle, "paw_strike", target.instanceId)
            val hit = result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.PET_USED_SKILL && it.actorInstanceId == "pet" }
            if (!hit) continue
            sawHit = true
            val after = result.snapshot.enemies.first()
            assertEquals(5000, after.hp)
            assertTrue(after.shieldHp < 3000)
            assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.THORNS_REFLECTED && it.amount >= 1 })
            val spark = AdventureGateCombatEngine.performSkill(profile, battle, "spark", target.instanceId)
            assertFalse(spark.snapshot.log.any { it.messageKey == AdventureGateLogMessage.THORNS_REFLECTED })
            break
        }
        assertTrue("no paw_strike landed in 40 seeds", sawHit)
    }

    // ---- 3/4. Modifiers and omens -------------------------------------------------------------

    @Test
    fun `phase modifiers are defined per world and phase and carried into the battle`() {
        AdventureGateCatalog.worlds.forEach { world ->
            val withModifier = world.phases.filter { it.modifier != null }
            assertTrue("${world.id} needs modifiers", withModifier.size >= 3)
            assertNull("boss phase 7 stays modifier-free", world.phases[6].modifier)
            world.phases.forEach { assertEquals(AdventureGateCatalog.phaseModifier(world.id, it.phaseNumber), it.modifier) }
        }
        val fog = phase("sproutvale_gate", 4)
        assertEquals(AdventureGateBattleModifier.FOG, AdventureGateCombatEngine.startBattle(profile, fog, seed = 1L).modifier)
        assertNull(AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 1L).modifier)
    }

    @Test
    fun `modifier effect table matches the design`() {
        assertEquals(0.85f, AdventureGateBattleModifier.FOG.accuracyFactor(), 0.0001f)
        assertEquals(1f, (null as AdventureGateBattleModifier?).accuracyFactor(), 0.0001f)
        assertEquals(1.2f, AdventureGateBattleModifier.TIDE.damageFactor(AdventureGateElement.WATER, true), 0.0001f)
        assertEquals(1f, AdventureGateBattleModifier.TIDE.damageFactor(AdventureGateElement.FIRE, true), 0.0001f)
        assertEquals(1.2f, AdventureGateBattleModifier.HEATWAVE.damageFactor(AdventureGateElement.FIRE, false), 0.0001f)
        assertEquals(1, AdventureGateBattleModifier.HEATWAVE.manaDrainPerRound())
        assertEquals(1.15f, AdventureGateBattleModifier.MOONLIGHT.damageFactor(AdventureGateElement.ARCANE, true), 0.0001f)
        assertEquals(1f, AdventureGateBattleModifier.MOONLIGHT.damageFactor(AdventureGateElement.ARCANE, false), 0.0001f)
        assertEquals(10, AdventureGateBattleModifier.STARFALL.critChancePercent())
        assertEquals(0, AdventureGateBattleModifier.FOG.critChancePercent())
        assertTrue(AdventureGateBattleModifier.CLOCKWORK_TICK.hasClockworkTick())
        assertEquals(8, AdventureGateBattleModifier.SLEEPY_MOON.yawnChancePercent())
        AdventureGateBattleModifier.entries.forEach {
            assertTrue(it.nameRes != 0 && it.descriptionRes != 0 && it.omenRes != 0)
        }
        assertEquals(7, AdventureGateBattleModifier.entries.size)
    }

    @Test
    fun `clockwork tick drains everyone on every third round only`() {
        val battle = AdventureGateCombatEngine.startBattle(profile, phase("ember_toyworks", 12), seed = 3L)
        assertEquals(AdventureGateBattleModifier.CLOCKWORK_TICK, battle.modifier)
        var snapshot = battle.copy(
            enemies = battle.enemies.map { it.copy(maxHp = 9999, hp = 9999, attack = 1, magic = 1, speed = 1) }
        )
        var current = profile
        val tickRounds = mutableListOf<Int>()
        repeat(3) { round ->
            val result = AdventureGateCombatEngine.performSkill(current, snapshot, "guard", snapshot.pet.instanceId)
            if (result.snapshot.log.takeLast(40).any { it.messageKey == AdventureGateLogMessage.MODIFIER_TICK }) {
                tickRounds += round + 1
            }
            assertEquals(round + 1, result.snapshot.actionSequence)
            snapshot = result.snapshot
            current = result.profile
        }
        assertEquals(listOf(3), tickRounds)
        assertTrue(snapshot.enemies.all { it.hp < 9999 })
        assertTrue(snapshot.pet.hp >= 1)
    }

    @Test
    fun `night omens are seeded per night cover all seven modifiers and reach the battle`() {
        val a = NightArenaGenerator.omenForNight("pet", "2026-10-21")
        assertEquals(a, NightArenaGenerator.omenForNight("pet", "2026-10-21"))
        val omens = (0 until 250).map { NightArenaGenerator.omenForNight("pet", "2027-01-${it}") }.toSet()
        assertEquals(AdventureGateBattleModifier.entries.toSet(), omens)

        val run = NightArenaGenerator.generateRun("pet", "2026-10-21", sourceDepth = 12, nowMillis = 1L)
        assertTrue(run.levels.all { it.omen == a })
        val phase = NightArenaGenerator.phaseForLevel(run.levels.first())
        assertEquals(a, phase.modifier)
        assertEquals(a, AdventureGateCombatEngine.startBattle(profile, phase, seed = 1L).modifier)
    }

    // ---- 5. Boss lines ------------------------------------------------------------------------

    @Test
    fun `every boss has an enrage line`() {
        val bosses = AdventureGateCatalog.monsters.filter { it.isBoss }.map { it.id }.toSet()
        assertEquals(14, bosses.size)
        assertEquals(bosses, AdventureGateBossLines.bossIds)
        bosses.forEach { assertTrue(AdventureGateBossLines.enrageLineRes(it) != 0) }
        assertNull(AdventureGateBossLines.enrageLineRes("dewcap_slime"))
    }

    @Test
    fun `boss enrages once below half hp and logs its line`() {
        val battle = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 7), seed = 4L)
        val boss = battle.enemies.first()
        assertTrue(boss.boss)
        val weakened = battle.copy(enemies = listOf(boss.copy(hp = boss.maxHp / 2)))
        val first = AdventureGateCombatEngine.performSkill(profile, weakened, "guard", weakened.pet.instanceId)
        val lines = first.snapshot.log.filter { it.messageKey == AdventureGateLogMessage.BOSS_ENRAGED }
        assertEquals(1, lines.size)
        assertEquals("bramble_guard", lines.first().skillId)
        assertTrue(first.snapshot.enemies.first().enraged)
        val second = AdventureGateCombatEngine.performSkill(first.profile, first.snapshot, "guard", first.snapshot.pet.instanceId)
        assertEquals(
            1,
            second.snapshot.log.count { it.messageKey == AdventureGateLogMessage.BOSS_ENRAGED }
        )
    }

    // ---- Old saves ----------------------------------------------------------------------------

    @Test
    fun `snapshots and night levels saved before round thirteen still load`() {
        val json = Json { ignoreUnknownKeys = true }
        val fresh = AdventureGateCombatEngine.startBattle(profile, phase("sproutvale_gate", 1), seed = 5L)
        val legacy = fresh.copy(enemies = fresh.enemies.map { it.copy(intent = null) })
        val decoded = json.decodeFromString<AdventureGateBattleSnapshot>(json.encodeToString(legacy))
        assertNull(decoded.modifier)
        assertTrue(decoded.enemies.all { it.intent == null && it.traits.isEmpty() && it.shieldHp == 0 })
        val result = AdventureGateCombatEngine.performSkill(profile, decoded, "guard", decoded.pet.instanceId)
        assertTrue("intents are planned again after the first round", result.snapshot.enemies.all { it.intent != null })

        val run = NightArenaGenerator.generateRun("pet", "2026-10-21", sourceDepth = 3, nowMillis = 1L)
        val level = run.levels.first().copy(omen = null)
        assertNull(json.decodeFromString<NightArenaLevel>(json.encodeToString(level)).omen)
    }

    @Test
    fun `intents and elite state survive a snapshot round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val battle = AdventureGateCombatEngine.startBattle(profile, phase("ember_toyworks", 4), seed = 8L)
        val tagged = battle.copy(
            enemies = battle.enemies.map { it.copy(traits = listOf(AdventureGateEliteTrait.HASTE), shieldHp = 7) }
        )
        val decoded = json.decodeFromString<AdventureGateBattleSnapshot>(json.encodeToString(tagged))
        assertEquals(tagged.enemies.map { it.intent }, decoded.enemies.map { it.intent })
        assertEquals(tagged.modifier, decoded.modifier)
        assertTrue(decoded.enemies.all { it.traits == listOf(AdventureGateEliteTrait.HASTE) && it.shieldHp == 7 })
    }
}
