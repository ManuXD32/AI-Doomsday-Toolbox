package com.example.llamadroid.tama.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

class AdventureGateCombatEngineTest {
    @Test
    fun `enemy levels increase globally across worlds and bosses`() {
        val worldOneStart = AdventureGateCombatEngine.enemyLevelFor("sproutvale_gate", 1, boss = false)
        val worldTwoStart = AdventureGateCombatEngine.enemyLevelFor("ember_toyworks", 1, boss = false)
        val worldOneMidBoss = AdventureGateCombatEngine.enemyLevelFor("sproutvale_gate", 7, boss = true)
        val worldOneFinalBoss = AdventureGateCombatEngine.enemyLevelFor("sproutvale_gate", 15, boss = true)

        assertEquals(1, worldOneStart)
        assertTrue(worldTwoStart > worldOneStart)
        assertEquals(10, worldOneMidBoss)
        assertEquals(21, worldOneFinalBoss)
        assertTrue(worldTwoStart < worldOneFinalBoss)
    }

    @Test
    fun `world two first enemies are stronger than world one first enemies`() {
        val profile = AdventureGateProfile(petId = "pet")
        val worldOneBattle = AdventureGateCombatEngine.startBattle(
            profile,
            AdventureGateCatalog.world("sproutvale_gate").phases.first(),
            seed = 1L
        )
        val worldTwoBattle = AdventureGateCombatEngine.startBattle(
            profile,
            AdventureGateCatalog.world("ember_toyworks").phases.first(),
            seed = 1L
        )

        assertTrue(worldTwoBattle.enemies.first().level > worldOneBattle.enemies.first().level)
        assertTrue(worldTwoBattle.enemies.first().maxHp > worldOneBattle.enemies.first().maxHp)
        assertTrue(worldTwoBattle.enemies.first().attack > worldOneBattle.enemies.first().attack)
    }

    @Test
    fun `old combatant json without level decodes with default level`() {
        val json = """
            {
              "instanceId":"enemy_1",
              "definitionId":"dewcap_slime",
              "isPet":false,
              "maxHp":10,
              "hp":10,
              "maxMana":0,
              "mana":0,
              "attack":3,
              "magic":2,
              "defense":1,
              "speed":4,
              "elements":["WATER"]
            }
        """.trimIndent()

        val combatant = Json.decodeFromString<AdventureGateCombatantState>(json)
        assertEquals(1, combatant.level)
    }

    @Test
    fun `weakness hit deals more damage than resisted hit`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()

        val sparkBattle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 1L)
        val sparkResult = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = sparkBattle,
            skillId = "spark",
            targetInstanceId = sparkBattle.enemies.first().instanceId
        )

        val pawBattle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 1L)
        val pawResult = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = pawBattle,
            skillId = "paw_strike",
            targetInstanceId = pawBattle.enemies.first().instanceId
        )

        val sparkDamage = sparkBattle.enemies.first().hp - sparkResult.snapshot.enemies.first().hp
        val pawDamage = pawBattle.enemies.first().hp - pawResult.snapshot.enemies.first().hp
        assertTrue(sparkDamage > pawDamage)
    }

    @Test
    fun `battle events are returned after combat log reaches cap`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val cappedLog = List(80) { index ->
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.BATTLE_STARTED,
                amount = index,
                timestamp = index.toLong()
            )
        }
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 2L)
            .copy(log = cappedLog)

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "paw_strike",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertTrue(result.snapshot.log.size <= 80)
        assertEquals(battle.actionSequence + 1, result.snapshot.actionSequence)
        assertTrue(result.events.any {
            it.type == AdventureGateBattleEventType.DAMAGE &&
                it.actorInstanceId == battle.pet.instanceId &&
                it.skillId == "paw_strike"
        })
    }

    @Test
    fun `capped combat log still advances enemy actions`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val cappedLog = List(80) { index ->
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.BATTLE_STARTED,
                amount = index,
                timestamp = index.toLong()
            )
        }
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 22L).let { snapshot ->
            snapshot.copy(
                log = cappedLog,
                pet = snapshot.pet.copy(hp = snapshot.pet.maxHp, speed = 999),
                enemies = snapshot.enemies.map {
                    it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1, speed = 1, mana = 0, maxMana = 0)
                }
            )
        }

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "guard",
            targetInstanceId = battle.pet.instanceId
        )

        assertEquals(battle.actionSequence + 1, result.snapshot.actionSequence)
        assertTrue(result.snapshot.log.size <= 80)
        assertTrue(result.snapshot.log.any {
            (it.messageKey == AdventureGateLogMessage.ENEMY_USED_ATTACK ||
                it.messageKey == AdventureGateLogMessage.MISSED) &&
                it.actorInstanceId != battle.pet.instanceId
        })
    }

    @Test
    fun `not enough mana keeps turn on pet and logs failure`() {
        val lowManaProfile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(lowManaProfile, phase, seed = 2L)
            .let { it.copy(pet = it.pet.copy(mana = 0)) }

        val result = AdventureGateCombatEngine.performSkill(
            profile = lowManaProfile,
            snapshot = battle,
            skillId = "spark",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertEquals(AdventureGateTurn.PET, result.snapshot.turn)
        assertEquals(AdventureGateLogMessage.NOT_ENOUGH_MANA, result.snapshot.log.last().messageKey)
        assertEquals(battle.enemies.first().hp, result.snapshot.enemies.first().hp)
    }

    @Test
    fun `non basic attacks enter cooldown and cannot be reused immediately`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 3,
            purchasedSkillIds = AdventureGateCatalog.starterSkillIds + "quick_claw",
            learnedAttackIds = listOf("paw_strike", "quick_claw"),
            equippedAttackIds = listOf("quick_claw"),
            equippedMagicIds = emptyList()
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 12L).let { snapshot ->
            snapshot.copy(enemies = snapshot.enemies.map { it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1) })
        }

        val first = AdventureGateCombatEngine.performSkill(profile, battle, "quick_claw", battle.enemies.first().instanceId)
        assertEquals(1, first.snapshot.skillCooldowns["quick_claw"])

        val blocked = AdventureGateCombatEngine.performSkill(first.profile, first.snapshot, "quick_claw", first.snapshot.enemies.first().instanceId)
        assertEquals(AdventureGateTurn.PET, blocked.snapshot.turn)
        assertEquals(AdventureGateLogMessage.SKILL_ON_COOLDOWN, blocked.snapshot.log.last().messageKey)
    }

    @Test
    fun `guard restores mana and reduces incoming damage`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 3L)
            .let { it.copy(pet = it.pet.copy(mana = 10)) }

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "guard",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertTrue(result.snapshot.pet.mana > battle.pet.mana)
        assertTrue(result.snapshot.pet.hp > 0)
    }

    @Test
    fun `guard has a hard limit of four uses per battle`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        var snapshot = AdventureGateCombatEngine.startBattle(profile, phase, seed = 31L).let { battle ->
            battle.copy(enemies = battle.enemies.map { it.copy(attack = 1, magic = 1, speed = 1) })
        }
        var currentProfile = profile

        repeat(AdventureGateCatalog.BATTLE_GUARD_LIMIT) {
            val result = AdventureGateCombatEngine.performSkill(currentProfile, snapshot, "guard", snapshot.enemies.first().instanceId)
            snapshot = result.snapshot
            currentProfile = result.profile
        }

        assertEquals(AdventureGateCatalog.BATTLE_GUARD_LIMIT, snapshot.guardUses)
        val blocked = AdventureGateCombatEngine.performSkill(currentProfile, snapshot, "guard", snapshot.enemies.first().instanceId)

        assertEquals(AdventureGateCatalog.BATTLE_GUARD_LIMIT, blocked.snapshot.guardUses)
        assertEquals(AdventureGateTurn.PET, blocked.snapshot.turn)
        assertEquals(AdventureGateLogMessage.GUARD_LIMIT_REACHED, blocked.snapshot.log.last().messageKey)
    }

    @Test
    fun `ward support skills do not consume or obey base guard limit`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 30,
            purchasedSkillIds = AdventureGateCatalog.starterSkillIds + listOf("heal_dew", "bubble_ward", "dream_mend", "mana_shell"),
            learnedMagicIds = AdventureGateCatalog.startingMagicIds + listOf("heal_dew", "bubble_ward", "dream_mend", "mana_shell"),
            equippedMagicIds = listOf("bubble_ward", "mana_shell")
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val cappedBattle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 33L).copy(
            guardUses = AdventureGateCatalog.BATTLE_GUARD_LIMIT
        ).let { battle ->
            battle.copy(pet = battle.pet.copy(mana = battle.pet.maxMana))
        }

        val bubbleResult = AdventureGateCombatEngine.performSkill(profile, cappedBattle, "bubble_ward", cappedBattle.pet.instanceId)

        assertEquals(AdventureGateCatalog.BATTLE_GUARD_LIMIT, bubbleResult.snapshot.guardUses)
        assertTrue(bubbleResult.snapshot.log.any { it.skillId == "bubble_ward" && it.messageKey == AdventureGateLogMessage.PET_GUARDED })
        assertFalse(bubbleResult.snapshot.log.any { it.skillId == "bubble_ward" && it.messageKey == AdventureGateLogMessage.GUARD_LIMIT_REACHED })

        val manaShellBattle = cappedBattle.copy(
            log = cappedBattle.log.take(1),
            pet = cappedBattle.pet.copy(mana = 20)
        )
        val manaShellResult = AdventureGateCombatEngine.performSkill(profile, manaShellBattle, "mana_shell", manaShellBattle.pet.instanceId)

        assertEquals(AdventureGateCatalog.BATTLE_GUARD_LIMIT, manaShellResult.snapshot.guardUses)
        assertTrue(manaShellResult.snapshot.log.any { it.skillId == "mana_shell" && it.messageKey == AdventureGateLogMessage.PET_GUARDED })
        assertTrue(manaShellResult.snapshot.log.any { it.skillId == "mana_shell" && it.messageKey == AdventureGateLogMessage.MANA_SHELL_RECOIL })
        assertFalse(manaShellResult.snapshot.log.any { it.skillId == "mana_shell" && it.messageKey == AdventureGateLogMessage.GUARD_LIMIT_REACHED })
        assertFalse(manaShellResult.snapshot.pet.statuses.any { it.id == "ward" })
        assertTrue(manaShellResult.snapshot.pet.mana > manaShellBattle.pet.mana - AdventureGateCatalog.skill("mana_shell").manaCost)
        assertTrue(manaShellResult.snapshot.pet.hp < manaShellBattle.pet.hp)
        assertEquals(3, AdventureGateCatalog.skill("mana_shell").cooldownTurns)
    }

    @Test
    fun `loadout normalizes to four attacks four magic and implicit guard`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 50,
            purchasedSkillIds = AdventureGateCatalog.skills.map { it.id },
            equippedAttackIds = listOf("paw_strike", "quick_claw", "bleeding_swipe", "shield_cracker"),
            equippedMagicIds = listOf("spark", "guard", "heal_dew", "dream_mend", "mana_shell")
        )

        val normalized = AdventureGateCombatEngine.normalizedProfile(profile)

        assertEquals(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT, normalized.equippedAttackIds.size)
        assertEquals(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT, normalized.equippedMagicIds.size)
        assertFalse(AdventureGateCatalog.ALWAYS_GUARD_SKILL_ID in normalized.equippedMagicIds)
        assertTrue(AdventureGateCatalog.ALWAYS_GUARD_SKILL_ID in normalized.purchasedSkillIds)
    }

    @Test
    fun `self statuses roll two to five turn durations`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 16,
            purchasedSkillIds = AdventureGateCatalog.starterSkillIds + listOf("heal_dew", "bubble_ward", "dream_mend"),
            learnedMagicIds = AdventureGateCatalog.startingMagicIds + listOf("heal_dew", "bubble_ward", "dream_mend"),
            equippedMagicIds = listOf("dream_mend")
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 32L)

        val result = AdventureGateCombatEngine.performSkill(profile, battle, "dream_mend", battle.pet.instanceId)
        val regen = checkNotNull(result.snapshot.pet.statuses.firstOrNull { it.id == "regen" })

        assertTrue(regen.turnsRemaining in 2..5)
    }

    @Test
    fun `status damage that clears final enemy completes the phase`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 4L)
            .let { snapshot ->
                snapshot.copy(
                    enemies = snapshot.enemies.map { enemy ->
                        enemy.copy(
                            hp = 3,
                            statuses = listOf(
                                AdventureGateStatusEffect(
                                    id = "burn",
                                    turnsRemaining = 1,
                                    damagePerTurn = 5
                                )
                            )
                        )
                    }
                )
            }

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "guard",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertTrue(result.snapshot.isCompleted)
        assertTrue(result.snapshot.isVictory)
        assertEquals(AdventureGateTurn.COMPLETE, result.snapshot.turn)
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.STATUS_DAMAGE })
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.ENEMY_DEFEATED })
    }

    @Test
    fun `boss rage persists after the boss drops below half health`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases[6]
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 5L)
            .let { snapshot ->
                snapshot.copy(
                    pet = snapshot.pet.copy(hp = snapshot.pet.maxHp),
                    enemies = snapshot.enemies.map { enemy ->
                        enemy.copy(hp = enemy.maxHp / 2)
                    }
                )
            }

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "guard",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertTrue(result.snapshot.enemies.first().enraged)
    }

    @Test
    fun `xp grant can level up and unlock skills`() {
        val profile = AdventureGateProfile(petId = "pet")
        val result = AdventureGateCombatEngine.grantXp(profile, AdventureGateCombatEngine.xpToNextLevel(1))

        assertEquals(2, result.profile.level)
        assertTrue(result.leveledUp)
        assertTrue(result.profile.stats.maxHp > profile.stats.maxHp)
        assertEquals(1, result.profile.skillPoints)
    }

    @Test
    fun `level up refreshes every adventure stat from the growth curve`() {
        val profile = AdventureGateProfile(petId = "pet")
        val result = AdventureGateCombatEngine.grantXp(profile, AdventureGateCombatEngine.xpToNextLevel(1))
        val expected = AdventureGateCombatEngine.baseStatsForLevel(2)

        assertEquals(expected.maxHp, result.profile.stats.maxHp)
        assertEquals(expected.maxMana, result.profile.stats.maxMana)
        assertEquals(expected.attack, result.profile.stats.attack)
        assertEquals(expected.magic, result.profile.stats.magic)
        assertEquals(expected.defense, result.profile.stats.defense)
        assertEquals(expected.speed, result.profile.stats.speed)
        assertEquals(expected.accuracy, result.profile.stats.accuracy)
        assertEquals(expected.evasion, result.profile.stats.evasion)
    }

    @Test
    fun `study points increase effective magic and mana`() {
        val profile = AdventureGateProfile(petId = "pet")
        val baseline = AdventureGateCombatEngine.normalizedProfile(profile, educationLevel = 0f)
        val studied = AdventureGateCombatEngine.normalizedProfile(profile, educationLevel = 45f)
        val studiedAgain = AdventureGateCombatEngine.normalizedProfile(studied)

        assertEquals(baseline.stats.magic + 3, studied.stats.magic)
        assertEquals(baseline.stats.maxMana + 16, studied.stats.maxMana)
        assertEquals(studied.stats.magic, studiedAgain.stats.magic)
        assertEquals(studied.stats.maxMana, studiedAgain.stats.maxMana)
    }

    @Test
    fun `introspection points increase effective max hp every ten points`() {
        val profile = AdventureGateProfile(petId = "pet")
        val baseline = AdventureGateCombatEngine.normalizedProfile(profile, introspectionLevel = 0f)
        val belowThreshold = AdventureGateCombatEngine.normalizedProfile(profile, introspectionLevel = 9.99f)
        val firstBonus = AdventureGateCombatEngine.normalizedProfile(profile, introspectionLevel = 10f)
        val secondBonus = AdventureGateCombatEngine.normalizedProfile(profile, introspectionLevel = 20f)

        assertEquals(baseline.stats.maxHp, belowThreshold.stats.maxHp)
        assertEquals(baseline.stats.maxHp + 3, firstBonus.stats.maxHp)
        assertEquals(baseline.stats.maxHp + 6, secondBonus.stats.maxHp)
    }

    @Test
    fun `exercise points increase effective attack like study increases magic`() {
        val profile = AdventureGateProfile(petId = "pet")
        val baseline = AdventureGateCombatEngine.normalizedProfile(profile, exerciseLevel = 0f)
        val trained = AdventureGateCombatEngine.normalizedProfile(profile, exerciseLevel = 45f)
        val trainedAgain = AdventureGateCombatEngine.normalizedProfile(trained)

        assertEquals(baseline.stats.attack + 3, trained.stats.attack)
        assertEquals(trained.stats.attack, trainedAgain.stats.attack)
    }

    @Test
    fun `study mana recovery starts at one percent at two hundred study points`() {
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val profile = AdventureGateCombatEngine.normalizedProfile(
            AdventureGateProfile(
                petId = "pet",
                currentMana = 50,
                educationLevel = 200f
            )
        )
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 73L).copy(
            pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 73L).pet.copy(mana = 50),
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 73L).enemies.map {
                it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1, speed = 1, mana = 0, maxMana = 0)
            }
        )

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "paw_strike",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertEquals(51, result.snapshot.pet.mana)
        assertFalse(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.EQUIPMENT_TRIGGERED })
    }

    @Test
    fun `study mana recovery scales at four hundred and six hundred study points`() {
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()

        val fourHundredProfile = AdventureGateCombatEngine.normalizedProfile(
            AdventureGateProfile(
                petId = "pet",
                currentMana = 50,
                educationLevel = 400f
            )
        )
        val fourHundredBattle = AdventureGateCombatEngine.startBattle(fourHundredProfile, phase, seed = 75L).copy(
            pet = AdventureGateCombatEngine.startBattle(fourHundredProfile, phase, seed = 75L).pet.copy(mana = 50),
            enemies = AdventureGateCombatEngine.startBattle(fourHundredProfile, phase, seed = 75L).enemies.map {
                it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1, speed = 1, mana = 0, maxMana = 0)
            }
        )
        val fourHundredResult = AdventureGateCombatEngine.performSkill(
            profile = fourHundredProfile,
            snapshot = fourHundredBattle,
            skillId = "paw_strike",
            targetInstanceId = fourHundredBattle.enemies.first().instanceId
        )

        val sixHundredProfile = AdventureGateCombatEngine.normalizedProfile(
            AdventureGateProfile(
                petId = "pet",
                currentMana = 50,
                educationLevel = 600f
            )
        )
        val sixHundredBattle = AdventureGateCombatEngine.startBattle(sixHundredProfile, phase, seed = 76L).copy(
            pet = AdventureGateCombatEngine.startBattle(sixHundredProfile, phase, seed = 76L).pet.copy(mana = 50),
            enemies = AdventureGateCombatEngine.startBattle(sixHundredProfile, phase, seed = 76L).enemies.map {
                it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1, speed = 1, mana = 0, maxMana = 0)
            }
        )
        val sixHundredResult = AdventureGateCombatEngine.performSkill(
            profile = sixHundredProfile,
            snapshot = sixHundredBattle,
            skillId = "paw_strike",
            targetInstanceId = sixHundredBattle.enemies.first().instanceId
        )

        assertEquals(54, fourHundredResult.snapshot.pet.mana)
        assertEquals(58, sixHundredResult.snapshot.pet.mana)
    }

    @Test
    fun `hidden study mana recovery stays locked before two hundred study points`() {
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val profile = AdventureGateCombatEngine.normalizedProfile(
            AdventureGateProfile(
                petId = "pet",
                currentMana = 50,
                educationLevel = 199f
            )
        )
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 74L).copy(
            pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 74L).pet.copy(mana = 50),
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 74L).enemies.map {
                it.copy(maxHp = 999, hp = 999, attack = 1, magic = 1, speed = 1, mana = 0, maxMana = 0)
            }
        )

        val result = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = "paw_strike",
            targetInstanceId = battle.enemies.first().instanceId
        )

        assertEquals(50, result.snapshot.pet.mana)
    }

    @Test
    fun `heal amount scales with magic stat`() {
        val lowMagic = AdventureGateCombatEngine.calculateHealingAmount(magic = 10, skillPower = 34)
        val highMagic = AdventureGateCombatEngine.calculateHealingAmount(magic = 60, skillPower = 34)

        assertTrue(highMagic > lowMagic)
    }

    @Test
    fun `skeleton helper summon creates one minion that attacks on later rounds`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 10,
            currentMana = 80,
            purchasedSkillIds = AdventureGateCatalog.starterSkillIds + AdventureGateCatalog.SKELETON_HELPER_SKILL_ID,
            learnedMagicIds = AdventureGateCatalog.startingMagicIds + AdventureGateCatalog.SKELETON_HELPER_SKILL_ID,
            equippedMagicIds = listOf(AdventureGateCatalog.SKELETON_HELPER_SKILL_ID)
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 51L).copy(
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 51L).enemies.map {
                it.copy(hp = 999, maxHp = 999, attack = 1, magic = 1, speed = 1)
            }
        )

        val summoned = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = AdventureGateCatalog.SKELETON_HELPER_SKILL_ID,
            targetInstanceId = battle.pet.instanceId
        )
        val minion = checkNotNull(summoned.snapshot.minion)

        assertEquals((summoned.snapshot.pet.maxHp * 0.5f).roundToInt(), minion.maxHp)
        assertEquals(0, minion.maxMana)
        assertEquals((summoned.snapshot.pet.attack * 0.75f).roundToInt(), minion.attack)
        assertTrue(summoned.snapshot.log.any { it.messageKey == AdventureGateLogMessage.PET_SUMMONED })
        assertTrue(summoned.events.any {
            it.type == AdventureGateBattleEventType.SUMMON &&
                it.skillId == AdventureGateCatalog.SKELETON_HELPER_SKILL_ID &&
                it.targetInstanceId == minion.instanceId
        })

        val beforeEnemyHp = summoned.snapshot.enemies.sumOf { it.hp }
        val nextRound = AdventureGateCombatEngine.performSkill(
            profile = summoned.profile,
            snapshot = summoned.snapshot,
            skillId = "guard",
            targetInstanceId = summoned.snapshot.pet.instanceId
        )

        assertTrue(nextRound.snapshot.enemies.sumOf { it.hp } < beforeEnemyHp)
        assertTrue(nextRound.snapshot.log.any { it.skillId == "skeleton_helper_attack" })
    }

    @Test
    fun `skeleton helper status ticks do not require monster catalog entry`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 18,
            currentMana = 120,
            purchasedSkillIds = AdventureGateCatalog.skills.map { it.id },
            learnedMagicIds = AdventureGateCatalog.learnedMagicIdsForLevel(18),
            equippedMagicIds = listOf(AdventureGateCatalog.SKELETON_HELPER_SKILL_ID, "dream_mend")
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 39L).copy(
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 39L).enemies.map {
                it.copy(hp = 999, maxHp = 999, attack = 1, magic = 1, speed = 1)
            }
        )

        val summoned = AdventureGateCombatEngine.performSkill(
            profile = profile,
            snapshot = battle,
            skillId = AdventureGateCatalog.SKELETON_HELPER_SKILL_ID,
            targetInstanceId = battle.pet.instanceId
        )
        val helper = checkNotNull(summoned.snapshot.minion)
        val mended = AdventureGateCombatEngine.performSkill(
            profile = summoned.profile,
            snapshot = summoned.snapshot,
            skillId = "dream_mend",
            targetInstanceId = helper.instanceId
        )

        val nextRound = AdventureGateCombatEngine.performSkill(
            profile = mended.profile,
            snapshot = mended.snapshot,
            skillId = "guard",
            targetInstanceId = mended.snapshot.pet.instanceId
        )

        assertEquals("skeleton_helper", nextRound.snapshot.minion?.definitionId)
        assertFalse(nextRound.snapshot.log.any {
            it.messageKey == AdventureGateLogMessage.ENEMY_DEFEATED &&
                it.targetInstanceId == helper.instanceId
        })
    }

    @Test
    fun `magic damage grows with caster magic and is reduced by defender magic and defense`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 20,
            purchasedSkillIds = AdventureGateCatalog.skills.map { it.id },
            equippedMagicIds = listOf("spark")
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val baseline = AdventureGateCombatEngine.startBattle(profile, phase, seed = 41L).copy(
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 41L).enemies.map {
                it.copy(hp = 500, maxHp = 500, magic = 10, defense = 10, attack = 1)
            }
        )
        val strongerCaster = baseline.copy(pet = baseline.pet.copy(magic = baseline.pet.magic + 40))
        val strongerDefender = baseline.copy(enemies = baseline.enemies.map { it.copy(magic = it.magic + 40, defense = it.defense + 30) })

        fun damage(snapshot: AdventureGateBattleSnapshot): Int {
            val before = snapshot.enemies.first().hp
            val result = AdventureGateCombatEngine.performSkill(profile, snapshot, "spark", snapshot.enemies.first().instanceId)
            return before - result.snapshot.enemies.first().hp
        }

        val baselineDamage = damage(baseline)
        assertTrue(damage(strongerCaster) > baselineDamage)
        assertTrue(damage(strongerDefender) < baselineDamage)
    }

    @Test
    fun `battle starts from persistent profile hp and mana`() {
        val profile = AdventureGateProfile(petId = "pet", currentHp = 7, currentMana = 3)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()

        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 6L)

        assertEquals(7, battle.pet.hp)
        assertEquals(3, battle.pet.mana)
    }

    @Test
    fun `player chooses first then speed decides round order`() {
        val slowProfile = AdventureGateProfile(petId = "pet")
        val fastPhase = AdventureGateCatalog.world("sproutvale_gate").phases.first()

        val fastEnemyBattle = AdventureGateCombatEngine.startBattle(slowProfile, fastPhase, seed = 7L).copy(
            enemies = AdventureGateCombatEngine.startBattle(slowProfile, fastPhase, seed = 7L).enemies.map {
                it.copy(speed = 999, attack = 1, magic = 1)
            }
        )
        assertEquals(AdventureGateTurn.PET, fastEnemyBattle.turn)
        val resolved = AdventureGateCombatEngine.performSkill(slowProfile, fastEnemyBattle, "guard", fastEnemyBattle.enemies.first().instanceId)
        val enemyIndex = resolved.snapshot.log.indexOfFirst {
            it.messageKey == AdventureGateLogMessage.ENEMY_USED_ATTACK ||
                (it.messageKey == AdventureGateLogMessage.MISSED && it.actorInstanceId != "pet")
        }
        val petIndex = resolved.snapshot.log.indexOfFirst { it.messageKey == AdventureGateLogMessage.PET_GUARDED }
        assertTrue(enemyIndex in 1 until petIndex)

        val forcedProfile = slowProfile.copy(equippedRingId = "ag_ring_second_hand")
        val forcedBattle = AdventureGateCombatEngine.startBattle(forcedProfile, fastPhase, seed = 7L).copy(
            enemies = AdventureGateCombatEngine.startBattle(forcedProfile, fastPhase, seed = 7L).enemies.map {
                it.copy(speed = 999, attack = 1, magic = 1)
            }
        )
        val forcedResolved = AdventureGateCombatEngine.performSkill(forcedProfile, forcedBattle, "guard", forcedBattle.enemies.first().instanceId)
        val forcedEnemyIndex = forcedResolved.snapshot.log.indexOfFirst {
            it.messageKey == AdventureGateLogMessage.ENEMY_USED_ATTACK ||
                (it.messageKey == AdventureGateLogMessage.MISSED && it.actorInstanceId != "pet")
        }
        val forcedPetIndex = forcedResolved.snapshot.log.indexOfFirst { it.messageKey == AdventureGateLogMessage.PET_GUARDED }
        assertTrue(forcedPetIndex in 1 until forcedEnemyIndex)
    }

    @Test
    fun `equipment changes effective stats and shield weaknesses`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            equippedWeaponId = "ag_weapon_sprout_baton",
            equippedShieldId = "ag_shield_leaf_shell",
            equippedRingId = "ag_ring_dewdrop"
        )

        val normalized = AdventureGateCombatEngine.normalizedProfile(profile)

        assertEquals(AdventureGateCombatEngine.baseStatsForLevel(1).attack + 4, normalized.stats.attack)
        assertEquals(AdventureGateCombatEngine.baseStatsForLevel(1).maxHp + 12, normalized.stats.maxHp)
        val battle = AdventureGateCombatEngine.startBattle(normalized, AdventureGateCatalog.world("sproutvale_gate").phases.first(), seed = 8L)
        assertTrue(AdventureGateElement.FIRE in battle.pet.weaknesses)
        assertTrue(AdventureGateElement.WATER in battle.pet.resistances)
    }

    @Test
    fun `battle potion heals spends the pet turn and respects the limit`() {
        val profile = AdventureGateProfile(petId = "pet", currentHp = 50)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 9L)

        val used = AdventureGateCombatEngine.useSupply(profile, battle, "ag_hp_dew_tiny")
        val usedResult = checkNotNull(used.result)

        assertTrue(used.used)
        assertTrue(usedResult.snapshot.pet.hp > 50)
        assertEquals(1, usedResult.snapshot.potionsUsed)
        assertEquals(AdventureGateTurn.PET, usedResult.snapshot.turn)
        assertTrue(usedResult.snapshot.log.any { it.messageKey == AdventureGateLogMessage.PET_USED_ITEM })

        val blocked = AdventureGateCombatEngine.useSupply(
            profile,
            battle.copy(potionsUsed = AdventureGateCatalog.BATTLE_POTION_LIMIT),
            "ag_hp_dew_tiny"
        )

        assertTrue(!blocked.used)
        assertEquals(AdventureGatePotionUseError.LIMIT_REACHED, blocked.error)
        assertTrue(blocked.result!!.snapshot.log.any { it.messageKey == AdventureGateLogMessage.POTION_LIMIT_REACHED })
    }

    @Test
    fun `cleanse draught removes only bad pet statuses and spends the turn`() {
        val profile = AdventureGateProfile(petId = "pet")
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 11L).copy(
            pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 11L).pet.copy(
                statuses = listOf(
                    AdventureGateStatusEffect("poison", turnsRemaining = 3),
                    AdventureGateStatusEffect("burn", turnsRemaining = 2),
                    AdventureGateStatusEffect("regen", turnsRemaining = 4),
                    AdventureGateStatusEffect("ward", turnsRemaining = 4)
                )
            )
        )

        val used = AdventureGateCombatEngine.useSupply(profile, battle, AdventureGateCatalog.CLEANSE_DRAUGHT_ID)
        val snapshot = checkNotNull(used.result).snapshot

        assertTrue(used.used)
        assertEquals(1, snapshot.potionsUsed)
        assertTrue(snapshot.pet.statuses.none { it.id == "poison" || it.id == "burn" })
        assertTrue(snapshot.pet.statuses.any { it.id == "regen" })
        assertTrue(snapshot.pet.statuses.any { it.id == "ward" })

        val blocked = AdventureGateCombatEngine.useSupply(profile, snapshot, AdventureGateCatalog.CLEANSE_DRAUGHT_ID)
        assertFalse(blocked.used)
        assertEquals(AdventureGatePotionUseError.NO_BAD_STATUS, blocked.error)
    }

    @Test
    fun `reflect damage that clears a wave advances without another pet action`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            equippedShieldId = "ag_shield_brass_button"
        )
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases[11]
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 42L).copy(
            pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 42L).pet.copy(speed = 1, hp = 120),
            enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 42L).enemies.take(1).map {
                it.copy(hp = 1, maxHp = 1, speed = 999, attack = 80, magic = 1)
            }
        )

        val result = AdventureGateCombatEngine.performSkill(profile, battle, "guard", battle.enemies.first().instanceId)

        assertTrue(result.snapshot.waveIndex > battle.waveIndex || result.snapshot.isCompleted)
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.EQUIPMENT_TRIGGERED })
    }

    @Test
    fun `shield weaknesses and resistances modify incoming enemy damage`() {
        val phase = AdventureGateCatalog.world("ember_toyworks").phases.first()
        val plain = AdventureGateProfile(petId = "pet", currentHp = 120)
        val weakToFire = AdventureGateProfile(petId = "pet", currentHp = 120, equippedShieldId = "ag_shield_leaf_shell")
        val resistsFire = AdventureGateProfile(petId = "pet", currentHp = 120, equippedShieldId = "ag_shield_brass_button")

        fun damageTaken(profile: AdventureGateProfile): Int {
            val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 10L)
                .copy(turn = AdventureGateTurn.ENEMY, pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 10L).pet.copy(hp = 120))
            val result = AdventureGateCombatEngine.resolveEnemyTurn(profile, battle)
            return 120 - result.snapshot.pet.hp
        }

        val plainDamage = damageTaken(plain)
        assertTrue(damageTaken(weakToFire) > plainDamage)
        assertTrue(damageTaken(resistsFire) < plainDamage)
    }

    @Test
    fun `revive once gear prevents a defeat once per battle`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            currentHp = 10,
            equippedRelicId = "ag_relic_regent_dream_key"
        )
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 11L)
            .copy(
                turn = AdventureGateTurn.ENEMY,
                pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 11L).pet.copy(hp = 10),
                enemies = AdventureGateCombatEngine.startBattle(profile, phase, seed = 11L).enemies.map { it.copy(attack = 999, magic = 1) }
            )

        val result = AdventureGateCombatEngine.resolveEnemyTurn(profile, battle)

        assertTrue(result.snapshot.pet.hp > 0)
        assertTrue(!result.snapshot.isCompleted)
        assertTrue("ag_relic_regent_dream_key" in result.snapshot.usedReviveEquipmentIds)
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.EQUIPMENT_TRIGGERED })
    }

    @Test
    fun `pet defeat completes before turn regeneration can revive it`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            equippedRelicId = AdventureGateCatalog.MYSTERY_RELIC_ID
        )
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val battle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 12L)
            .copy(pet = AdventureGateCombatEngine.startBattle(profile, phase, seed = 12L).pet.copy(hp = 0))

        val result = AdventureGateCombatEngine.performSkill(profile, battle, "guard", battle.enemies.first().instanceId)

        assertTrue(result.snapshot.isCompleted)
        assertFalse(result.snapshot.isVictory)
        assertEquals(AdventureGateTurn.COMPLETE, result.snapshot.turn)
        assertEquals(0, result.snapshot.pet.hp)
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.PET_DEFEATED })
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.DEFEAT })
    }

    @Test
    fun `pet self damage that reaches zero hp ends the battle immediately`() {
        val profile = AdventureGateProfile(
            petId = "pet",
            level = 30,
            purchasedSkillIds = AdventureGateCatalog.starterSkillIds + listOf("dream_mend", "mana_shell"),
            learnedMagicIds = AdventureGateCatalog.startingMagicIds + listOf("dream_mend", "mana_shell"),
            equippedMagicIds = listOf("mana_shell")
        ).let(AdventureGateCombatEngine::normalizedProfile)
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        val baseBattle = AdventureGateCombatEngine.startBattle(profile, phase, seed = 13L)
        val battle = baseBattle.copy(
            pet = baseBattle.pet.copy(hp = 1, mana = 20, speed = 999),
            enemies = baseBattle.enemies.map { it.copy(speed = 1) }
        )

        val result = AdventureGateCombatEngine.performSkill(profile, battle, "mana_shell", battle.pet.instanceId)

        assertTrue(result.snapshot.isCompleted)
        assertFalse(result.snapshot.isVictory)
        assertEquals(AdventureGateTurn.COMPLETE, result.snapshot.turn)
        assertEquals(0, result.snapshot.pet.hp)
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.MANA_SHELL_RECOIL })
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.PET_DEFEATED })
        assertTrue(result.snapshot.log.any { it.messageKey == AdventureGateLogMessage.DEFEAT })
    }
}
