package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

object AdventureGateCombatEngine {
    private const val SKELETON_HELPER_INSTANCE_ID = "skeleton_helper"
    private const val SKELETON_HELPER_HP_PERCENT = 50
    private const val SKELETON_HELPER_STAT_PERCENT = 75
    private const val MAX_LEVEL = 50
    private const val WEAKNESS_MULTIPLIER = 1.5f
    private const val RESISTANCE_MULTIPLIER = 0.6f
    private const val BOSS_NON_WEAK_MULTIPLIER = 0.75f
    private const val GUARD_DAMAGE_MULTIPLIER = 0.6f
    private const val DEFAULT_GUARD_MANA_RESTORE = 6
    private const val STUDY_MANA_REGEN_UNLOCK_POINTS = 200f
    private const val ELITE_REGEN_PERCENT = 6
    private const val ELITE_SHIELD_PERCENT = 25
    private const val ELITE_HASTE_PERCENT = 125
    private const val ELITE_THORNS_PERCENT = 10

    private sealed class QueuedPetAction {
        data class Skill(val skill: AdventureGateSkillDefinition, val targetInstanceId: String?) : QueuedPetAction()
        data class Supply(val supply: AdventureGateSupplyDefinition, val amount: Int) : QueuedPetAction()
    }

    fun baseStatsForLevel(level: Int): AdventureGateStats {
        val safeLevel = level.coerceIn(1, MAX_LEVEL)
        return AdventureGateStats(
            maxHp = 120 + ((safeLevel - 1) * 10),
            maxMana = 40 + ((safeLevel - 1) * 5),
            attack = 18 + ((safeLevel - 1) * 2),
            magic = 14 + ((safeLevel - 1) * 2),
            defense = 10 + ((safeLevel - 1) * 2),
            speed = 10 + (safeLevel - 1),
            accuracy = 100 + (safeLevel - 1),
            evasion = 5 + (safeLevel - 1)
        )
    }

    fun xpToNextLevel(level: Int): Int =
        80 + level * 35 + level * level * 8

    fun enemyLevelFor(worldId: String, phaseNumber: Int, boss: Boolean): Int {
        val worldIndex = AdventureGateCatalog.worlds.indexOfFirst { it.id == worldId }.coerceAtLeast(0)
        val bossBonus = when {
            boss && phaseNumber >= AdventureGateCatalog.PHASES_PER_WORLD -> 6
            boss -> 3
            else -> 0
        }
        return 1 + (worldIndex * 8) + (phaseNumber - 1).coerceAtLeast(0) + bossBonus
    }

    fun calculateHealingAmount(
        magic: Int,
        skillPower: Int,
        healingBonusPercent: Int = 0
    ): Int {
        val base = skillPower + (magic.coerceAtLeast(1) * 0.85f)
        return max(8, (base * (100 + healingBonusPercent) / 100f).roundToInt())
    }

    fun manaShellRestoreAmount(magic: Int): Int =
        12 + (magic.coerceAtLeast(1) / 3)


    fun normalizedProfile(
        profile: AdventureGateProfile,
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel,
        exerciseLevel: Float = profile.exerciseLevel
    ): AdventureGateProfile {
        val effectiveEducationLevel = educationLevel.coerceAtLeast(0f)
        val effectiveIntrospectionLevel = introspectionLevel.coerceAtLeast(0f)
        val effectiveExerciseLevel = exerciseLevel.coerceAtLeast(0f)
        val sanitizedGear = profile.copy(
            equippedWeaponId = profile.equippedWeaponId
                ?.takeIf { AdventureGateCatalog.equipment(it)?.slot == AdventureGateEquipmentSlot.WEAPON },
            equippedShieldId = profile.equippedShieldId
                ?.takeIf { AdventureGateCatalog.equipment(it)?.slot == AdventureGateEquipmentSlot.SHIELD },
            equippedRingId = profile.equippedRingId
                ?.takeIf { AdventureGateCatalog.equipment(it)?.slot == AdventureGateEquipmentSlot.RING },
            equippedRelicId = profile.equippedRelicId
                ?.takeIf { AdventureGateCatalog.equipment(it)?.slot == AdventureGateEquipmentSlot.RELIC }
        )
        val stats = AdventureGateCatalog.effectiveStats(
            sanitizedGear,
            effectiveEducationLevel,
            effectiveIntrospectionLevel,
            effectiveExerciseLevel
        )
        val learnedAttackIds = AdventureGateCatalog.learnedAttackIdsForLevel(profile.level)
        val learnedMagicIds = AdventureGateCatalog.learnedMagicIdsForLevel(profile.level)
        val levelUnlockedIds = (learnedAttackIds + learnedMagicIds).toSet()
        val purchasedSkillIds = (profile.purchasedSkillIds + AdventureGateCatalog.starterSkillIds)
            .distinct()
            .filter { it in levelUnlockedIds }
        val purchasedAttacks = purchasedSkillIds.filter { AdventureGateCatalog.skill(it).kind == AdventureGateSkillKind.ATTACK }
        val purchasedMagic = purchasedSkillIds.filter { AdventureGateCatalog.isEquippableMagicSkill(AdventureGateCatalog.skill(it)) }
        return sanitizedGear.copy(
            stats = stats,
            educationLevel = effectiveEducationLevel,
            exerciseLevel = effectiveExerciseLevel,
            introspectionLevel = effectiveIntrospectionLevel,
            currentHp = profile.currentHp.coerceIn(0, stats.maxHp),
            currentMana = profile.currentMana.coerceIn(0, stats.maxMana),
            skillPoints = profile.skillPoints.coerceAtLeast(0),
            purchasedSkillIds = purchasedSkillIds,
            learnedAttackIds = learnedAttackIds,
            learnedMagicIds = learnedMagicIds,
            equippedAttackIds = profile.equippedAttackIds.filter { it in purchasedAttacks }.ifEmpty {
                AdventureGateCatalog.startingAttackIds
            }.take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT),
            equippedMagicIds = profile.equippedMagicIds.filter { it in purchasedMagic }.ifEmpty {
                AdventureGateCatalog.startingMagicIds
            }.take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT)
        )
    }

    fun startBattle(
        profile: AdventureGateProfile,
        phase: AdventureGatePhaseDefinition,
        seed: Long = System.currentTimeMillis(),
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel,
        exerciseLevel: Float = profile.exerciseLevel
    ): AdventureGateBattleSnapshot {
        val safeProfile = normalizedProfile(profile, educationLevel, introspectionLevel, exerciseLevel)
        val loadout = AdventureGateCatalog.loadoutForProfile(safeProfile)
        val shield = loadout.shield
        val pet = AdventureGateCombatantState(
            instanceId = "pet",
            definitionId = "pet",
            isPet = true,
            maxHp = safeProfile.stats.maxHp,
            hp = safeProfile.currentHp.coerceIn(0, safeProfile.stats.maxHp),
            maxMana = safeProfile.stats.maxMana,
            mana = safeProfile.currentMana.coerceIn(0, safeProfile.stats.maxMana),
            attack = safeProfile.stats.attack,
            magic = safeProfile.stats.magic,
            defense = safeProfile.stats.defense,
            speed = safeProfile.stats.speed,
            accuracy = safeProfile.stats.accuracy,
            evasion = safeProfile.stats.evasion,
            elements = listOf(AdventureGateElement.BEAST, AdventureGateElement.LIGHT),
            weaknesses = shield?.petWeaknesses?.toList().orEmpty(),
            resistances = shield?.petResistances?.toList().orEmpty()
        )
        val enemies = createWave(phase, 0, seed)
        return planIntents(AdventureGateBattleSnapshot(
            petId = safeProfile.petId,
            worldId = phase.worldId,
            phaseNumber = phase.phaseNumber,
            waveIndex = 0,
            turn = AdventureGateTurn.PET,
            pet = pet,
            enemies = enemies,
            log = listOf(AdventureGateBattleLogEntry(AdventureGateLogMessage.BATTLE_STARTED)),
            rngSeed = seed,
            phaseOverride = phase.takeIf { it.worldId == AdventureGateCatalog.NIGHT_ARENA_WORLD_ID },
            modifier = phase.modifier
        ))
    }

    fun performSkill(
        profile: AdventureGateProfile,
        snapshot: AdventureGateBattleSnapshot,
        skillId: String,
        targetInstanceId: String?,
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel
    ): AdventureGateActionResult {
        if (snapshot.isCompleted) {
            return AdventureGateActionResult(snapshot = snapshot, profile = profile)
        }
        val safeProfile = normalizedProfile(profile, educationLevel, introspectionLevel)
        if (!snapshot.pet.isAlive) {
            val defeated = completeDefeatIfPetDown(snapshot)
            val result = finishBattle(safeProfile, defeated, educationLevel, introspectionLevel)
            return result.copy(events = eventsFromLogs(logsAddedSince(snapshot.log, result.snapshot.log)))
        }
        if (snapshot.turn != AdventureGateTurn.PET) {
            return AdventureGateActionResult(snapshot = snapshot, profile = profile)
        }
        val skill = AdventureGateCatalog.skill(skillId)
        val available = (AdventureGateCatalog.skillsForProfile(safeProfile).map { it.id } + AdventureGateCatalog.ALWAYS_GUARD_SKILL_ID).toSet()
        if (skill.id !in available) {
            return AdventureGateActionResult(snapshot = snapshot, profile = profile)
        }
        if (AdventureGateCatalog.consumesGuardUse(skill.id) && snapshot.guardUses >= AdventureGateCatalog.BATTLE_GUARD_LIMIT) {
            val updated = snapshot.copy(
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.GUARD_LIMIT_REACHED,
                    actorInstanceId = snapshot.pet.instanceId,
                    skillId = skill.id,
                    skillNameRes = skill.nameRes,
                    amount = AdventureGateCatalog.BATTLE_GUARD_LIMIT
                )),
                updatedAt = System.currentTimeMillis()
            )
            return AdventureGateActionResult(snapshot = updated, profile = profile, events = eventsFromLogs(logsAddedSince(snapshot.log, updated.log)))
        }
        val cooldownRemaining = snapshot.skillCooldowns[skill.id] ?: 0
        if (cooldownRemaining > 0) {
            val updated = snapshot.copy(
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.SKILL_ON_COOLDOWN,
                    actorInstanceId = snapshot.pet.instanceId,
                    skillId = skill.id,
                    skillNameRes = skill.nameRes,
                    amount = cooldownRemaining
                )),
                updatedAt = System.currentTimeMillis()
            )
            return AdventureGateActionResult(snapshot = updated, profile = profile, events = eventsFromLogs(logsAddedSince(snapshot.log, updated.log)))
        }
        if (snapshot.pet.mana < skill.manaCost) {
            val updated = snapshot.copy(
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.NOT_ENOUGH_MANA,
                    actorInstanceId = snapshot.pet.instanceId,
                    skillId = skill.id,
                    skillNameRes = skill.nameRes,
                    amount = skill.manaCost
                )),
                updatedAt = System.currentTimeMillis()
            )
            return AdventureGateActionResult(snapshot = updated, profile = profile, events = eventsFromLogs(logsAddedSince(snapshot.log, updated.log)))
        }
        if (skill.kind == AdventureGateSkillKind.SUMMON && snapshot.minion?.isAlive == true) {
            val updated = snapshot.copy(
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.SKILL_ON_COOLDOWN,
                    actorInstanceId = snapshot.pet.instanceId,
                    skillId = skill.id,
                    skillNameRes = skill.nameRes,
                    amount = 1
                )),
                updatedAt = System.currentTimeMillis()
            )
            return AdventureGateActionResult(snapshot = updated, profile = profile, events = eventsFromLogs(logsAddedSince(snapshot.log, updated.log)))
        }

        val afterWave = resolveSpeedRound(
            profile = safeProfile,
            snapshot = snapshot,
            action = QueuedPetAction.Skill(skill, targetInstanceId)
        )
        return if (afterWave.isCompleted) {
            val result = finishBattle(safeProfile, afterWave, educationLevel, introspectionLevel)
            result.copy(events = eventsFromLogs(logsAddedSince(snapshot.log, result.snapshot.log)))
        } else {
            AdventureGateActionResult(
                snapshot = afterWave,
                profile = safeProfile.persistBattleVitals(afterWave.pet),
                events = eventsFromLogs(logsAddedSince(snapshot.log, afterWave.log))
            )
        }
    }

    /**
     * Older saves could be persisted mid-round with the turn marker on ENEMY; rounds now
     * resolve atomically in [performSkill]/[useSupply], so an unfinished battle is always
     * waiting on the pet (a downed pet is finished by the next action). Returns the snapshot unchanged when it needs no repair.
     */
    fun recoverStuckTurn(snapshot: AdventureGateBattleSnapshot): AdventureGateBattleSnapshot {
        if (snapshot.isCompleted || snapshot.turn == AdventureGateTurn.PET) return snapshot
        return snapshot.copy(turn = AdventureGateTurn.PET, updatedAt = System.currentTimeMillis())
    }

    fun useSupply(
        profile: AdventureGateProfile,
        snapshot: AdventureGateBattleSnapshot,
        supplyId: String,
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel
    ): AdventureGatePotionUseResult {
        val supply = AdventureGateCatalog.supply(supplyId)
            ?: return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.UNKNOWN_ITEM)
        if (snapshot.isCompleted) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.ACTIVE_BATTLE_REQUIRED)
        }
        val safeProfile = normalizedProfile(profile, educationLevel, introspectionLevel)
        if (!snapshot.pet.isAlive) {
            val defeated = completeDefeatIfPetDown(snapshot)
            val result = finishBattle(safeProfile, defeated, educationLevel, introspectionLevel)
            return AdventureGatePotionUseResult(
                result = result.copy(events = eventsFromLogs(logsAddedSince(snapshot.log, result.snapshot.log))),
                used = false,
                error = AdventureGatePotionUseError.ACTIVE_BATTLE_REQUIRED
            )
        }
        if (snapshot.turn != AdventureGateTurn.PET) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_PET_TURN)
        }
        if (supply.kind == AdventureGateSupplyKind.SKILL_POINT) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.UNKNOWN_ITEM)
        }
        if (snapshot.potionsUsed >= AdventureGateCatalog.BATTLE_POTION_LIMIT) {
            val blocked = snapshot.copy(
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.POTION_LIMIT_REACHED,
                    actorInstanceId = snapshot.pet.instanceId,
                    itemId = supply.id
                )),
                updatedAt = System.currentTimeMillis()
            )
            return AdventureGatePotionUseResult(
                result = AdventureGateActionResult(
                    snapshot = blocked,
                    profile = normalizedProfile(profile, educationLevel, introspectionLevel).persistBattleVitals(blocked.pet),
                    events = eventsFromLogs(logsAddedSince(snapshot.log, blocked.log))
                ),
                used = false,
                error = AdventureGatePotionUseError.LIMIT_REACHED
            )
        }
        val potionBonus = AdventureGateCatalog.loadoutForProfile(safeProfile)
            .equipment
            .sumOf { it.effect.potionBonusPercent }
        val amount = (supply.amount * (100 + potionBonus) / 100f).roundToInt().coerceAtLeast(1)
        if (supply.kind == AdventureGateSupplyKind.HP && snapshot.pet.hp >= snapshot.pet.maxHp) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.FULL)
        }
        if (supply.kind == AdventureGateSupplyKind.MANA && snapshot.pet.mana >= snapshot.pet.maxMana) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.FULL)
        }
        if (supply.kind == AdventureGateSupplyKind.CLEANSE &&
            snapshot.pet.statuses.none { it.id in AdventureGateCatalog.badStatusIds }
        ) {
            return AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NO_BAD_STATUS)
        }
        val updated = resolveSpeedRound(
            profile = safeProfile,
            snapshot = snapshot.copy(potionsUsed = snapshot.potionsUsed + 1),
            action = QueuedPetAction.Supply(supply, amount)
        )
        val result = AdventureGateActionResult(
            snapshot = updated,
            profile = safeProfile.persistBattleVitals(updated.pet),
            events = eventsFromLogs(logsAddedSince(snapshot.log, updated.log))
        )
        return AdventureGatePotionUseResult(result = result, used = true)
    }

    private fun resolveSpeedRound(
        profile: AdventureGateProfile,
        snapshot: AdventureGateBattleSnapshot,
        action: QueuedPetAction
    ): AdventureGateBattleSnapshot {
        val phase = snapshot.phaseDefinition()
        val loadout = AdventureGateCatalog.loadoutForProfile(profile)
        val turnSnapshot = snapshot.copy(
            turn = AdventureGateTurn.PET,
            actionSequence = snapshot.actionSequence + 1
        )
        completeDefeatIfPetDown(turnSnapshot).takeIf { it.isCompleted }?.let { return it }
        val rng = roundRandom(turnSnapshot)
        var state = applyTurnRegeneration(turnSnapshot, profile, loadout)
        state = applyModifierManaDrain(state)
        val statusLogs = mutableListOf<AdventureGateBattleLogEntry>()
        val petAfterStatus = applyStatusTick(state.pet, statusLogs)
        val minionAfterStatus = state.minion?.let { applyStatusTick(it, statusLogs) }
        val enemiesAfterStatus = state.enemies.map { applyEliteRegen(applyStatusTick(it, statusLogs), statusLogs) }
        state = state.copy(pet = petAfterStatus, minion = minionAfterStatus, enemies = enemiesAfterStatus, log = appendLog(state, statusLogs), updatedAt = System.currentTimeMillis())
        state = applyClockworkTick(state, turnSnapshot.actionSequence)
        if (!state.pet.isAlive) {
            return state.copy(
                turn = AdventureGateTurn.COMPLETE,
                isCompleted = true,
                isVictory = false,
                log = appendLog(state, AdventureGateBattleLogEntry(AdventureGateLogMessage.PET_DEFEATED)),
                updatedAt = System.currentTimeMillis()
            )
        }
        state = advanceWaveOrVictory(state, phase, profile)
        if (state.isCompleted) return state

        val forcePetFirst = loadout.equipment.any { it.effect.forceFirstTurn }
        val actors = buildList {
            add(state.pet.instanceId to if (forcePetFirst) Int.MAX_VALUE else state.pet.effectiveSpeed())
            state.minion?.takeIf { it.isAlive }?.let { add(it.instanceId to it.effectiveSpeed()) }
            state.enemies.filter { it.isAlive }.forEach { add(it.instanceId to it.effectiveSpeed()) }
        }.sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenBy { if (it.first == state.pet.instanceId) 0 else 1 }
        ).map { it.first }

        var petActionTaken = false
        var petYawned = false
        for (actorId in actors) {
            if (state.isCompleted) break
            if (actorId == state.pet.instanceId) {
                if (!state.pet.isAlive || petActionTaken) continue
                if (shouldSkipForStatus(state.pet, rng)) {
                    state = state.copy(
                        log = appendLog(state, AdventureGateBattleLogEntry(
                            messageKey = AdventureGateLogMessage.STATUS_SKIP,
                            actorInstanceId = state.pet.instanceId,
                            statusId = state.pet.statuses.maxByOrNull { it.skipTurnChancePercent }?.id
                        )),
                        updatedAt = System.currentTimeMillis()
                    )
                    petActionTaken = true
                    continue
                }
                // Sleepy Moon: skills can be lost to a yawn; supplies are exempt so a potion is never wasted.
                if (action is QueuedPetAction.Skill && yawns(state.modifier, rng)) {
                    state = state.copy(
                        log = appendLog(state, AdventureGateBattleLogEntry(
                            messageKey = AdventureGateLogMessage.YAWNED,
                            actorInstanceId = state.pet.instanceId
                        )),
                        updatedAt = System.currentTimeMillis()
                    )
                    petActionTaken = true
                    petYawned = true
                    continue
                }
                val petAfterCost = when (action) {
                    is QueuedPetAction.Skill -> state.pet.copy(
                        mana = (state.pet.mana - action.skill.manaCost).coerceAtLeast(0),
                        guarding = false
                    )
                    is QueuedPetAction.Supply -> state.pet.copy(guarding = false)
                }
                state = when (action) {
                    is QueuedPetAction.Skill -> when (action.skill.kind) {
                        AdventureGateSkillKind.ATTACK,
                        AdventureGateSkillKind.MAGIC -> applyPetDamage(state.copy(pet = petAfterCost), profile, action.skill, action.targetInstanceId, rng)
                        AdventureGateSkillKind.HEAL -> applyPetHeal(state.copy(pet = petAfterCost), profile, action.skill, action.targetInstanceId, rng)
                        AdventureGateSkillKind.GUARD -> applyPetGuard(state.copy(pet = petAfterCost), action.skill, rng)
                        AdventureGateSkillKind.SUMMON -> applyPetSummon(state.copy(pet = petAfterCost), action.skill)
                    }
                    is QueuedPetAction.Supply -> applyPetSupply(state.copy(pet = petAfterCost), action.supply, action.amount)
                }
                petActionTaken = true
                state = completeDefeatIfPetDown(state)
                if (state.isCompleted) break
                state = advanceWaveOrVictory(state, phase, profile)
            } else if (actorId == state.minion?.instanceId) {
                if (state.minion?.isAlive != true) continue
                state = applyMinionAttack(state, profile, rng)
                state = advanceWaveOrVictory(state, phase, profile)
                if (state.isCompleted) break
            } else {
                val enemy = state.enemies.firstOrNull { it.instanceId == actorId && it.isAlive } ?: continue
                if (!state.pet.isAlive) break
                val waveBeforeEnemyAction = state.waveIndex
                state = applyEnemyAction(state, enemy, profile, rng)
                if (!state.pet.isAlive && state.isCompleted) break
                state = advanceWaveOrVictory(state, phase, profile)
                if (state.isCompleted || state.waveIndex != waveBeforeEnemyAction) break
            }
        }
        return if (state.isCompleted) {
            state
        } else {
            val cooldowns = when (action) {
                is QueuedPetAction.Skill -> if (petYawned) tickCooldowns(state.skillCooldowns) else nextCooldowns(state.skillCooldowns, action.skill)
                is QueuedPetAction.Supply -> tickCooldowns(state.skillCooldowns)
            }
            planIntents(state.copy(
                pet = state.pet.copy(guarding = false),
                skillCooldowns = cooldowns,
                turn = AdventureGateTurn.PET,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    private fun tickCooldowns(cooldowns: Map<String, Int>): Map<String, Int> =
        cooldowns.mapNotNull { (skillId, turns) ->
            (turns - 1).takeIf { it > 0 }?.let { skillId to it }
        }.toMap()

    private fun nextCooldowns(
        cooldowns: Map<String, Int>,
        usedSkill: AdventureGateSkillDefinition
    ): Map<String, Int> {
        val ticked = tickCooldowns(cooldowns)
        return if (usedSkill.cooldownTurns > 0) {
            ticked + (usedSkill.id to usedSkill.cooldownTurns)
        } else {
            ticked
        }
    }

    private fun nextActorCooldowns(
        cooldowns: Map<String, Map<String, Int>>,
        actorInstanceId: String,
        usedAction: AdventureGateEnemyActionDefinition
    ): Map<String, Map<String, Int>> {
        val actorCooldowns = cooldowns[actorInstanceId].orEmpty()
            .mapNotNull { (actionId, turns) -> (turns - 1).takeIf { it > 0 }?.let { actionId to it } }
            .toMap()
            .let { ticked ->
                if (usedAction.cooldownTurns > 0) ticked + (usedAction.id to usedAction.cooldownTurns) else ticked
            }
        return if (actorCooldowns.isEmpty()) cooldowns - actorInstanceId else cooldowns + (actorInstanceId to actorCooldowns)
    }

    /** Executes the telegraphed intent when it is still usable, else re-rolls with the weighted AI. */
    private fun resolveEnemyAction(
        snapshot: AdventureGateBattleSnapshot,
        enemy: AdventureGateCombatantState,
        rng: Random
    ): AdventureGateEnemyActionDefinition {
        val cooldowns = snapshot.actorCooldowns[enemy.instanceId].orEmpty()
        val planned = enemy.intent
            ?.let { AdventureGateCatalog.enemyActionOrNull(it.actionId) }
            ?.takeIf { AdventureGateEnemyAi.isUsable(it, enemy, cooldowns) }
        return planned ?: AdventureGateEnemyAi.chooseAction(enemy, snapshot.pet, cooldowns, rng)
    }

    /** Telegraphs every living enemy's next action (seeded, so replays match); resets the big-hit tracker. */
    private fun planIntents(snapshot: AdventureGateBattleSnapshot): AdventureGateBattleSnapshot {
        if (snapshot.isCompleted) return snapshot
        val rng = AdventureGateEnemyAi.intentRandom(snapshot)
        val enemies = snapshot.enemies.map { enemy ->
            if (!enemy.isAlive) {
                enemy
            } else {
                val action = AdventureGateEnemyAi.chooseAction(
                    enemy,
                    snapshot.pet,
                    snapshot.actorCooldowns[enemy.instanceId].orEmpty(),
                    rng
                )
                enemy.copy(intent = AdventureGateEnemyAi.intentFor(action, enemy, snapshot.pet), lastHitTaken = 0)
            }
        }
        return snapshot.copy(enemies = enemies)
    }

    private fun yawns(modifier: AdventureGateBattleModifier?, rng: Random): Boolean {
        val chance = modifier.yawnChancePercent()
        return chance > 0 && rng.nextInt(100) < chance
    }

    private fun rollCrit(modifier: AdventureGateBattleModifier?, rng: Random): Boolean {
        val chance = modifier.critChancePercent()
        return chance > 0 && rng.nextInt(100) < chance
    }

    /** Applies [damage] to [target], draining its elite shield first, and records the hit for the AI. */
    private fun absorbDamage(target: AdventureGateCombatantState, damage: Int): AdventureGateCombatantState {
        val absorbed = minOf(target.shieldHp, damage)
        return target.copy(
            shieldHp = target.shieldHp - absorbed,
            hp = (target.hp - (damage - absorbed)).coerceAtLeast(0),
            lastHitTaken = target.lastHitTaken + damage
        )
    }

    private fun applyEliteRegen(
        enemy: AdventureGateCombatantState,
        logs: MutableList<AdventureGateBattleLogEntry>
    ): AdventureGateCombatantState {
        if (!enemy.isAlive || enemy.hp >= enemy.maxHp || AdventureGateEliteTrait.REGEN !in enemy.traits) return enemy
        val healed = (enemy.maxHp * ELITE_REGEN_PERCENT / 100f).roundToInt().coerceAtLeast(1)
        val hp = (enemy.hp + healed).coerceAtMost(enemy.maxHp)
        logs += AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.ENEMY_HEALED,
            actorInstanceId = enemy.instanceId,
            actorNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes,
            amount = hp - enemy.hp
        )
        return enemy.copy(hp = hp)
    }

    private fun applyModifierManaDrain(snapshot: AdventureGateBattleSnapshot): AdventureGateBattleSnapshot {
        val drain = snapshot.modifier.manaDrainPerRound()
        if (drain <= 0 || !snapshot.pet.isAlive) return snapshot
        return snapshot.copy(pet = snapshot.pet.copy(mana = (snapshot.pet.mana - drain).coerceAtLeast(0)))
    }

    /** Clockwork tick: every third round everyone loses 5% max HP (the pet is left at 1 HP at worst). */
    private fun applyClockworkTick(snapshot: AdventureGateBattleSnapshot, round: Int): AdventureGateBattleSnapshot {
        if (!snapshot.modifier.hasClockworkTick() || round <= 0 ||
            round % AdventureGateBattleModifier.TICK_EVERY_ROUNDS != 0
        ) return snapshot
        fun loss(combatant: AdventureGateCombatantState) =
            (combatant.maxHp * AdventureGateBattleModifier.TICK_PERCENT / 100f).roundToInt().coerceAtLeast(1)
        val petLoss = if (snapshot.pet.isAlive) minOf(loss(snapshot.pet), snapshot.pet.hp - 1).coerceAtLeast(0) else 0
        val logs = mutableListOf(
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.MODIFIER_TICK,
                actorInstanceId = snapshot.pet.instanceId,
                amount = petLoss
            )
        )
        val enemies = snapshot.enemies.map { enemy ->
            if (!enemy.isAlive) {
                enemy
            } else {
                enemy.copy(hp = (enemy.hp - loss(enemy)).coerceAtLeast(0)).also { ticked ->
                    if (!ticked.isAlive) {
                        logs += AdventureGateBattleLogEntry(
                            messageKey = AdventureGateLogMessage.ENEMY_DEFEATED,
                            targetInstanceId = enemy.instanceId,
                            targetNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes
                        )
                    }
                }
            }
        }
        return snapshot.copy(
            pet = snapshot.pet.copy(hp = snapshot.pet.hp - petLoss),
            minion = snapshot.minion?.takeIf { it.isAlive }?.let { it.copy(hp = (it.hp - loss(it)).coerceAtLeast(0)) },
            enemies = enemies,
            log = appendLog(snapshot, logs),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun maybeApplyStatus(
        target: AdventureGateCombatantState,
        status: AdventureGateStatusEffect,
        chancePercent: Int,
        rng: Random
    ): List<AdventureGateStatusEffect> {
        val chance = chancePercent.coerceIn(10, 95)
        return if (rng.nextInt(100) < chance) {
            applyStatus(target, status, rng)
        } else {
            target.statuses
        }
    }

    private fun applyStatus(
        target: AdventureGateCombatantState,
        status: AdventureGateStatusEffect,
        rng: Random
    ): List<AdventureGateStatusEffect> {
        val duration = rng.nextInt(2, 6)
        val softened = if (target.boss && status.skipTurnChancePercent > 0) {
            status.copy(
                turnsRemaining = (duration - 1).coerceAtLeast(1),
                skipTurnChancePercent = status.skipTurnChancePercent / 2
            )
        } else {
            status.copy(turnsRemaining = duration)
        }
        return target.statuses.filterNot { it.id == softened.id } + softened
    }

    private fun shouldSkipForStatus(combatant: AdventureGateCombatantState, rng: Random): Boolean =
        combatant.statuses.any { it.skipTurnChancePercent > 0 } &&
            rng.nextInt(100) < combatant.statuses.maxOf { it.skipTurnChancePercent }

    private fun AdventureGateCombatantState.effectiveAttack(): Int =
        statuses.fold(attack.toFloat()) { value, status -> value * status.attackMultiplierPercent / 100f }
            .roundToInt()
            .coerceAtLeast(1)

    private fun AdventureGateCombatantState.effectiveMagic(): Int =
        statuses.fold(magic.toFloat()) { value, status -> value * status.magicMultiplierPercent / 100f }
            .roundToInt()
            .coerceAtLeast(1)

    private fun AdventureGateCombatantState.effectiveDefense(): Int =
        statuses.fold(defense.toFloat()) { value, status -> value * status.defenseMultiplierPercent / 100f }
            .roundToInt()
            .coerceAtLeast(0)

    private fun AdventureGateCombatantState.effectiveSpeed(): Int =
        statuses.fold(speed.toFloat()) { value, status -> value * status.speedMultiplierPercent / 100f }
            .roundToInt()
            .coerceAtLeast(1)

    private fun AdventureGateCombatantState.effectiveAccuracy(): Int =
        (accuracy + statuses.sumOf { it.accuracyDelta }).coerceAtLeast(1)

    private fun AdventureGateCombatantState.effectiveEvasion(): Int =
        (evasion + statuses.sumOf { it.evasionDelta }).coerceAtLeast(0)

    private fun applyTurnRegeneration(
        snapshot: AdventureGateBattleSnapshot,
        profile: AdventureGateProfile,
        loadout: AdventureGateEffectiveLoadout
    ): AdventureGateBattleSnapshot {
        if (!snapshot.pet.isAlive) return snapshot
        val hpPercent = loadout.equipment.sumOf { it.effect.turnHpRegenPercent }
        val gearManaPercent = loadout.equipment.sumOf { it.effect.turnManaRegenPercent }
        val studyManaPercent = studyManaRegenPercent(profile)
        val manaPercent = gearManaPercent + studyManaPercent
        if (hpPercent <= 0 && manaPercent <= 0) return snapshot
        val hpRestored = if (hpPercent > 0) {
            (snapshot.pet.maxHp * hpPercent / 100f).roundToInt().coerceAtLeast(1)
        } else 0
        val gearManaRestored = if (gearManaPercent > 0) {
            (snapshot.pet.maxMana * gearManaPercent / 100f).roundToInt().coerceAtLeast(1)
        } else 0
        val manaRestored = if (manaPercent > 0) {
            (snapshot.pet.maxMana * manaPercent / 100f).roundToInt().coerceAtLeast(1)
        } else 0
        val pet = snapshot.pet.copy(
            hp = (snapshot.pet.hp + hpRestored).coerceAtMost(snapshot.pet.maxHp),
            mana = (snapshot.pet.mana + manaRestored).coerceAtMost(snapshot.pet.maxMana)
        )
        val source = loadout.equipment.firstOrNull {
            it.effect.turnHpRegenPercent > 0 || it.effect.turnManaRegenPercent > 0
        }
        val updated = snapshot.copy(pet = pet, updatedAt = System.currentTimeMillis())
        if (source == null) return updated
        val amount = hpRestored + gearManaRestored
        return updated.copy(
            log = appendLog(updated, AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.EQUIPMENT_TRIGGERED,
                actorInstanceId = snapshot.pet.instanceId,
                equipmentId = source?.id,
                amount = amount
            )),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun hasStudyManaRegenPassive(profile: AdventureGateProfile): Boolean =
        studyManaRegenPercent(profile) > 0

    fun studyManaRegenPercent(profile: AdventureGateProfile): Int = when {
        profile.educationLevel >= 600f -> 3
        profile.educationLevel >= 400f -> 2
        profile.educationLevel >= STUDY_MANA_REGEN_UNLOCK_POINTS -> 1
        else -> 0
    }

    private fun applyPetSupply(
        snapshot: AdventureGateBattleSnapshot,
        supply: AdventureGateSupplyDefinition,
        amount: Int
    ): AdventureGateBattleSnapshot {
        val pet = when (supply.kind) {
            AdventureGateSupplyKind.HP -> snapshot.pet.copy(hp = (snapshot.pet.hp + amount).coerceAtMost(snapshot.pet.maxHp))
            AdventureGateSupplyKind.MANA -> snapshot.pet.copy(mana = (snapshot.pet.mana + amount).coerceAtMost(snapshot.pet.maxMana))
            AdventureGateSupplyKind.CLEANSE -> snapshot.pet.copy(statuses = snapshot.pet.statuses.filterNot { it.id in AdventureGateCatalog.badStatusIds })
            AdventureGateSupplyKind.SKILL_POINT -> snapshot.pet
        }
        return snapshot.copy(
            pet = pet,
            log = appendLog(snapshot, AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.PET_USED_ITEM,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = snapshot.pet.instanceId,
                itemId = supply.id,
                amount = amount
            )),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyPetDamage(
        snapshot: AdventureGateBattleSnapshot,
        profile: AdventureGateProfile,
        skill: AdventureGateSkillDefinition,
        targetInstanceId: String?,
        rng: Random? = null
    ): AdventureGateBattleSnapshot {
        val loadout = AdventureGateCatalog.loadoutForProfile(profile)
        val target = snapshot.enemies.firstOrNull { it.instanceId == targetInstanceId && it.isAlive }
            ?: snapshot.enemies.firstOrNull { it.isAlive }
            ?: return snapshot
        if (rng != null && !rollHit(snapshot.pet, target, skill.accuracyPercent, rng, guaranteed = skill.accuracyPercent >= 100, modifier = snapshot.modifier)) {
            val missLog = AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.MISSED,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = target.instanceId,
                skillId = skill.id,
                targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
                skillNameRes = skill.nameRes,
                element = skill.element
            )
            return snapshot.copy(
                log = appendLog(snapshot, missLog),
                turn = AdventureGateTurn.ENEMY,
                updatedAt = System.currentTimeMillis()
            )
        }
        val baseDamage = calculateDamage(
            attacker = snapshot.pet,
            target = target,
            skillPower = skill.power,
            element = skill.element,
            magic = skill.kind == AdventureGateSkillKind.MAGIC,
            loadout = loadout,
            modifier = snapshot.modifier
        )
        val crit = rollCrit(snapshot.modifier, rng ?: roundRandom(snapshot))
        val damage = if (crit) (baseDamage * AdventureGateBattleModifier.CRIT_MULTIPLIER).roundToInt() else baseDamage
        var pet = snapshot.pet
        val damagedTarget = absorbDamage(target, damage)
        val thornsDamage = if (skill.kind == AdventureGateSkillKind.ATTACK && AdventureGateEliteTrait.THORNS in target.traits) {
            (damage * ELITE_THORNS_PERCENT / 100f).roundToInt().coerceAtLeast(1)
        } else 0
        if (thornsDamage > 0) {
            pet = pet.copy(hp = (pet.hp - thornsDamage).coerceAtLeast(1))
        }
        val updatedTarget = if (damagedTarget.isAlive && skill.status != null) {
            val bonus = loadout.equipment.sumOf { it.effect.statusChanceBonusPercent }
            damagedTarget.copy(statuses = maybeApplyStatus(damagedTarget, skill.status, skill.statusChancePercent + bonus, rng ?: roundRandom(snapshot)))
        } else {
            damagedTarget
        }
        val defeated = target.isAlive && !updatedTarget.isAlive
        val enemies = snapshot.enemies.map { if (it.instanceId == target.instanceId) updatedTarget else it }
        val weakHit = skill.element in target.weaknesses
        val refundItem = loadout.equipment.firstOrNull { it.effect.manaRefundOnWeakHit > 0 }
        val refund = refundItem?.effect?.manaRefundOnWeakHit?.takeIf { weakHit && it > 0 } ?: 0
        if (refund > 0) {
            pet = pet.copy(mana = (pet.mana + refund).coerceAtMost(pet.maxMana))
        }
        val hitLog = AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.PET_USED_SKILL,
            actorInstanceId = snapshot.pet.instanceId,
            targetInstanceId = target.instanceId,
            skillId = skill.id,
            statusId = skill.status?.id?.takeIf { updatedTarget.isAlive },
            targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
            skillNameRes = skill.nameRes,
            amount = damage,
            element = skill.element
        )
        val multiplierLog = when {
            skill.element in target.weaknesses -> AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.WEAK_HIT,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = target.instanceId,
                skillId = skill.id,
                targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
                element = skill.element
            )
            skill.element in target.resistances -> AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.RESISTED_HIT,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = target.instanceId,
                skillId = skill.id,
                targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
                element = skill.element
            )
            else -> null
        }
        val refundLog = if (refund > 0 && refundItem != null) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.EQUIPMENT_TRIGGERED,
                actorInstanceId = snapshot.pet.instanceId,
                equipmentId = refundItem.id,
                amount = refund
            )
        } else null
        val critLog = if (crit) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.CRITICAL_HIT,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = target.instanceId
            )
        } else null
        val thornsLog = if (thornsDamage > 0) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.THORNS_REFLECTED,
                actorInstanceId = target.instanceId,
                targetInstanceId = snapshot.pet.instanceId,
                actorNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
                amount = thornsDamage
            )
        } else null
        val defeatedLog = if (defeated) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.ENEMY_DEFEATED,
                targetInstanceId = target.instanceId,
                targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes
            )
        } else null
        return snapshot.copy(
            pet = pet,
            enemies = enemies,
            log = appendLog(snapshot, listOfNotNull(hitLog, critLog, multiplierLog, thornsLog, refundLog, defeatedLog)),
            turn = AdventureGateTurn.ENEMY,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyPetHeal(
        snapshot: AdventureGateBattleSnapshot,
        profile: AdventureGateProfile,
        skill: AdventureGateSkillDefinition,
        targetInstanceId: String?,
        rng: Random
    ): AdventureGateBattleSnapshot {
        val healingBonus = AdventureGateCatalog.loadoutForProfile(profile)
            .equipment
            .sumOf { it.effect.healingBonusPercent }
        val target = snapshot.allyByInstanceId(targetInstanceId) ?: snapshot.pet
        val healed = calculateHealingAmount(snapshot.pet.magic, skill.power, healingBonus)
        var healedTarget = target.copy(hp = (target.hp + healed).coerceAtMost(target.maxHp))
        if (skill.status != null) {
            healedTarget = healedTarget.copy(statuses = applyStatus(healedTarget, skill.status, rng))
        }
        val updatedSnapshot = snapshot.withAlly(healedTarget)
        return snapshot.copy(
            pet = updatedSnapshot.pet,
            minion = updatedSnapshot.minion,
            log = appendLog(snapshot, AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.PET_HEALED,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = healedTarget.instanceId,
                skillId = skill.id,
                skillNameRes = skill.nameRes,
                amount = healed
            )),
            turn = AdventureGateTurn.ENEMY,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyPetSummon(
        snapshot: AdventureGateBattleSnapshot,
        skill: AdventureGateSkillDefinition
    ): AdventureGateBattleSnapshot {
        if (snapshot.minion?.isAlive == true) return snapshot
        val pet = snapshot.pet
        val minion = AdventureGateCombatantState(
            instanceId = SKELETON_HELPER_INSTANCE_ID,
            definitionId = SKELETON_HELPER_INSTANCE_ID,
            isPet = false,
            isMinion = true,
            maxHp = (pet.maxHp * SKELETON_HELPER_HP_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            hp = (pet.maxHp * SKELETON_HELPER_HP_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            maxMana = 0,
            mana = 0,
            attack = (pet.attack * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            magic = (pet.magic * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            defense = (pet.defense * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(0),
            speed = (pet.speed * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            accuracy = (pet.accuracy * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(1),
            evasion = (pet.evasion * SKELETON_HELPER_STAT_PERCENT / 100f).roundToInt().coerceAtLeast(0),
            elements = listOf(AdventureGateElement.SHADOW, AdventureGateElement.BEAST)
        )
        return snapshot.copy(
            minion = minion,
            log = appendLog(snapshot, AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.PET_SUMMONED,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = minion.instanceId,
                skillId = skill.id,
                skillNameRes = skill.nameRes
            )),
            turn = AdventureGateTurn.ENEMY,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyPetGuard(
        snapshot: AdventureGateBattleSnapshot,
        skill: AdventureGateSkillDefinition,
        rng: Random
    ): AdventureGateBattleSnapshot {
        val manaBeforeRestore = snapshot.pet.mana
        val restored = when (skill.id) {
            "bubble_ward" -> 0
            "mana_shell" -> manaShellRestoreAmount(snapshot.pet.magic)
            else -> DEFAULT_GUARD_MANA_RESTORE
        }
        val manaAfterRestore = (snapshot.pet.mana + restored).coerceAtMost(snapshot.pet.maxMana)
        val actualRestored = (manaAfterRestore - manaBeforeRestore).coerceAtLeast(0)
        val recoilDamage = if (skill.id == "mana_shell" && actualRestored > 0) {
            (actualRestored / 2).coerceAtLeast(1)
        } else {
            0
        }
        var pet = snapshot.pet.copy(
            guarding = skill.id != "mana_shell",
            mana = manaAfterRestore,
            hp = (snapshot.pet.hp - recoilDamage).coerceAtLeast(0)
        )
        if (skill.status != null) {
            pet = pet.copy(statuses = applyStatus(pet, skill.status, rng))
        }
        val guardLog = AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.PET_GUARDED,
            actorInstanceId = snapshot.pet.instanceId,
            targetInstanceId = snapshot.pet.instanceId,
            skillId = skill.id,
            skillNameRes = skill.nameRes,
            amount = actualRestored
        )
        val recoilLog = if (recoilDamage > 0) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.MANA_SHELL_RECOIL,
                actorInstanceId = snapshot.pet.instanceId,
                targetInstanceId = snapshot.pet.instanceId,
                skillId = skill.id,
                skillNameRes = skill.nameRes,
                amount = recoilDamage,
                element = AdventureGateElement.ARCANE
            )
        } else {
            null
        }
        return snapshot.copy(
            pet = pet,
            guardUses = if (AdventureGateCatalog.consumesGuardUse(skill.id)) {
                snapshot.guardUses + 1
            } else {
                snapshot.guardUses
            },
            log = appendLog(snapshot, listOfNotNull(guardLog, recoilLog)),
            turn = AdventureGateTurn.ENEMY,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyMinionAttack(
        snapshot: AdventureGateBattleSnapshot,
        profile: AdventureGateProfile,
        rng: Random
    ): AdventureGateBattleSnapshot {
        val minion = snapshot.minion?.takeIf { it.isAlive } ?: return snapshot
        val target = snapshot.enemies.filter { it.isAlive }.randomOrNull(rng) ?: return snapshot
        val damage = calculateDamage(
            attacker = minion,
            target = target,
            skillPower = 80,
            element = AdventureGateElement.STRIKE,
            magic = false,
            loadout = AdventureGateCatalog.loadoutForProfile(profile),
            modifier = snapshot.modifier
        )
        val damagedTarget = absorbDamage(target, damage)
        val enemies = snapshot.enemies.map { if (it.instanceId == target.instanceId) damagedTarget else it }
        val hitLog = AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.PET_USED_SKILL,
            actorInstanceId = minion.instanceId,
            targetInstanceId = target.instanceId,
            skillId = "skeleton_helper_attack",
            targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes,
            skillNameRes = R.string.adventure_gate_skill_skeleton_helper_attack,
            amount = damage,
            element = AdventureGateElement.STRIKE
        )
        val defeatedLog = if (target.isAlive && !damagedTarget.isAlive) {
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.ENEMY_DEFEATED,
                targetInstanceId = target.instanceId,
                targetNameRes = AdventureGateCatalog.monster(target.definitionId).nameRes
            )
        } else {
            null
        }
        return snapshot.copy(
            enemies = enemies,
            log = appendLog(snapshot, listOfNotNull(hitLog, defeatedLog)),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyEnemyAction(
        snapshot: AdventureGateBattleSnapshot,
        enemy: AdventureGateCombatantState,
        profile: AdventureGateProfile,
        rng: Random
    ): AdventureGateBattleSnapshot {
        val loadout = AdventureGateCatalog.loadoutForProfile(profile)
        var target = snapshot.enemyTarget(rng)
        var usedReviveEquipmentIds = snapshot.usedReviveEquipmentIds
        val logs = mutableListOf<AdventureGateBattleLogEntry>()
        if (shouldSkipForStatus(enemy, rng)) {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.STATUS_SKIP,
                actorInstanceId = enemy.instanceId,
                statusId = enemy.statuses.maxByOrNull { it.skipTurnChancePercent }?.id,
                actorNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes
            )
            return snapshot.copy(log = appendLog(snapshot, logs), updatedAt = System.currentTimeMillis())
        }
        if (yawns(snapshot.modifier, rng)) {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.YAWNED,
                actorInstanceId = enemy.instanceId,
                actorNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes
            )
            val rested = snapshot.enemies.map {
                if (it.instanceId == enemy.instanceId) it.copy(intent = null, guarding = false) else it
            }
            return snapshot.copy(enemies = rested, log = appendLog(snapshot, logs), updatedAt = System.currentTimeMillis())
        }
        val enragedEnemy = (if (enemy.boss && !enemy.enraged && enemy.hp <= enemy.maxHp / 2) {
            enemy.copy(enraged = true)
        } else enemy).copy(guarding = false)
        if (enragedEnemy.enraged && !enemy.enraged) {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.BOSS_ENRAGED,
                actorInstanceId = enemy.instanceId,
                skillId = enemy.definitionId,
                actorNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes
            )
        }
        val chosenAction = resolveEnemyAction(snapshot, enragedEnemy, rng)
        if (chosenAction.kind == AdventureGateSkillKind.HEAL || chosenAction.kind == AdventureGateSkillKind.GUARD) {
            return applyEnemySelfAction(snapshot, enragedEnemy, chosenAction, logs)
        }
        if (!rollHit(enragedEnemy, target, chosenAction.accuracyPercent, rng, modifier = snapshot.modifier)) {
            val missedEnemy = enragedEnemy.copy(
                mana = (enragedEnemy.mana - chosenAction.manaCost).coerceAtLeast(0),
                intent = null
            )
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.MISSED,
                actorInstanceId = enragedEnemy.instanceId,
                targetInstanceId = target.instanceId,
                skillId = chosenAction.id,
                skillNameRes = chosenAction.nameRes,
                actorNameRes = AdventureGateCatalog.monster(enragedEnemy.definitionId).nameRes,
                element = chosenAction.element
            )
            val enemies = snapshot.enemies.map { if (it.instanceId == missedEnemy.instanceId) missedEnemy else it }
            return snapshot.copy(
                enemies = enemies,
                actorCooldowns = nextActorCooldowns(snapshot.actorCooldowns, missedEnemy.instanceId, chosenAction),
                log = appendLog(snapshot, logs),
                updatedAt = System.currentTimeMillis()
            )
        }
        val actingEnemy = enragedEnemy.copy(
            mana = (enragedEnemy.mana - chosenAction.manaCost).coerceAtLeast(0),
            intent = null
        )
        val baseDamage = calculateEnemyDamage(actingEnemy, target, loadout, chosenAction, snapshot.modifier)
        val enemyCrit = rollCrit(snapshot.modifier, rng)
        val damage = if (enemyCrit) (baseDamage * AdventureGateBattleModifier.CRIT_MULTIPLIER).roundToInt() else baseDamage
        val manaDamage = chosenAction.manaDamage.coerceAtLeast(0)
        val reflectPercent = if (target.isPet) loadout.equipment.sumOf { it.effect.reflectDamagePercent } else 0
        val reflected = if (reflectPercent > 0) {
            (damage * reflectPercent / 100f).roundToInt().coerceAtLeast(1)
        } else {
            0
        }
        target = target.copy(
            hp = (target.hp - damage).coerceAtLeast(0),
            mana = (target.mana - manaDamage).coerceAtLeast(0)
        )
        var targetStatuses = target.statuses
        chosenAction.status?.let { status ->
            targetStatuses = maybeApplyStatus(target.copy(statuses = targetStatuses), status, chosenAction.statusChancePercent, rng)
        }
        chosenAction.secondaryStatus?.let { status ->
            targetStatuses = maybeApplyStatus(target.copy(statuses = targetStatuses), status, chosenAction.secondaryStatusChancePercent, rng)
        }
        target = target.copy(statuses = targetStatuses)
        val reflectedEnemy = if (reflected > 0) {
            actingEnemy.copy(hp = (actingEnemy.hp - reflected).coerceAtLeast(0))
        } else {
            actingEnemy
        }
        val enemies = snapshot.enemies.map {
            if (it.instanceId == reflectedEnemy.instanceId) reflectedEnemy else it
        }.toMutableList()
        logs += AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.ENEMY_USED_ATTACK,
            actorInstanceId = reflectedEnemy.instanceId,
            targetInstanceId = target.instanceId,
            skillId = chosenAction.id,
            skillNameRes = chosenAction.nameRes,
            actorNameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes,
            amount = damage + manaDamage,
            element = chosenAction.element
        )
        if (enemyCrit) {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.CRITICAL_HIT,
                actorInstanceId = reflectedEnemy.instanceId,
                targetInstanceId = target.instanceId
            )
        }
        if (reflected > 0) {
            val reflectItem = loadout.equipment.firstOrNull { it.effect.reflectDamagePercent > 0 }
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.EQUIPMENT_TRIGGERED,
                actorInstanceId = target.instanceId,
                targetInstanceId = reflectedEnemy.instanceId,
                equipmentId = reflectItem?.id,
                amount = reflected
            )
            if (enragedEnemy.isAlive && !reflectedEnemy.isAlive) {
                logs += AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.ENEMY_DEFEATED,
                    targetInstanceId = reflectedEnemy.instanceId,
                    targetNameRes = AdventureGateCatalog.monster(reflectedEnemy.definitionId).nameRes
                )
            }
        }
        val afterTargetHit = snapshot.withAlly(target)
        var pet = afterTargetHit.pet
        val minion = afterTargetHit.minion
        if (target.isPet && !pet.isAlive) {
            val reviveItem = loadout.equipment.firstOrNull {
                it.effect.reviveOncePercent > 0 && it.id !in usedReviveEquipmentIds
            }
            if (reviveItem != null) {
                usedReviveEquipmentIds = usedReviveEquipmentIds + reviveItem.id
                val revivedHp = (pet.maxHp * reviveItem.effect.reviveOncePercent / 100f).roundToInt().coerceAtLeast(1)
                pet = pet.copy(hp = revivedHp.coerceAtMost(pet.maxHp))
                logs += AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.EQUIPMENT_TRIGGERED,
                    actorInstanceId = pet.instanceId,
                    equipmentId = reviveItem.id,
                    amount = pet.hp
                )
            } else {
                logs += AdventureGateBattleLogEntry(AdventureGateLogMessage.PET_DEFEATED)
                return snapshot.copy(
                    pet = pet,
                    minion = minion,
                    enemies = enemies,
                    log = appendLog(snapshot, logs),
                    usedReviveEquipmentIds = usedReviveEquipmentIds,
                    turn = AdventureGateTurn.COMPLETE,
                    isCompleted = true,
                    isVictory = false,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        return snapshot.copy(
            pet = pet,
            minion = minion,
            enemies = enemies,
            log = appendLog(snapshot, logs),
            actorCooldowns = nextActorCooldowns(snapshot.actorCooldowns, reflectedEnemy.instanceId, chosenAction),
            usedReviveEquipmentIds = usedReviveEquipmentIds,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** Heal (20% max HP) or guard (damage x0.6 until its next action) chosen by the enemy AI. */
    private fun applyEnemySelfAction(
        snapshot: AdventureGateBattleSnapshot,
        enemy: AdventureGateCombatantState,
        action: AdventureGateEnemyActionDefinition,
        logs: MutableList<AdventureGateBattleLogEntry>
    ): AdventureGateBattleSnapshot {
        val nameRes = AdventureGateCatalog.monster(enemy.definitionId).nameRes
        val reacted = if (action.kind == AdventureGateSkillKind.HEAL) {
            val healed = (enemy.maxHp * AdventureGateEnemyAi.HEAL_PERCENT / 100f).roundToInt().coerceAtLeast(1)
            val hp = (enemy.hp + healed).coerceAtMost(enemy.maxHp)
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.ENEMY_HEALED,
                actorInstanceId = enemy.instanceId,
                skillId = action.id,
                actorNameRes = nameRes,
                skillNameRes = action.nameRes,
                amount = hp - enemy.hp
            )
            enemy.copy(hp = hp)
        } else {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.ENEMY_GUARDED,
                actorInstanceId = enemy.instanceId,
                skillId = action.id,
                actorNameRes = nameRes,
                skillNameRes = action.nameRes
            )
            enemy.copy(guarding = true)
        }
        val acted = reacted.copy(intent = null, lastHitTaken = 0)
        return snapshot.copy(
            enemies = snapshot.enemies.map { if (it.instanceId == acted.instanceId) acted else it },
            actorCooldowns = nextActorCooldowns(snapshot.actorCooldowns, acted.instanceId, action),
            log = appendLog(snapshot, logs),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun roundRandom(snapshot: AdventureGateBattleSnapshot): Random =
        Random(
            snapshot.rngSeed +
                snapshot.actionSequence.toLong() * 1_000_003L +
                snapshot.waveIndex.toLong() * 10_007L +
                snapshot.potionsUsed.toLong() * 101L
        )

    private fun applyStatusTick(
        combatant: AdventureGateCombatantState,
        logs: MutableList<AdventureGateBattleLogEntry>
    ): AdventureGateCombatantState {
        if (combatant.statuses.isEmpty()) return combatant
        val startedAlive = combatant.isAlive
        var hp = combatant.hp
        var mana = combatant.mana
        val nameRes = if (combatant.isPet || combatant.isMinion) {
            null
        } else {
            AdventureGateCatalog.monster(combatant.definitionId).nameRes
        }
        val updatedStatuses = combatant.statuses.mapNotNull { status ->
            if (status.damagePerTurn > 0 && hp > 0) {
                hp = (hp - status.damagePerTurn).coerceAtLeast(0)
                logs += AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.STATUS_DAMAGE,
                    targetInstanceId = combatant.instanceId,
                    statusId = status.id,
                    targetNameRes = nameRes,
                    amount = status.damagePerTurn
                )
            }
            if (status.hpRegenPercent > 0 && hp > 0) {
                hp = (hp + (combatant.maxHp * status.hpRegenPercent / 100f).roundToInt().coerceAtLeast(1))
                    .coerceAtMost(combatant.maxHp)
            }
            if (status.manaRegenFlat > 0 && hp > 0) {
                mana = (mana + status.manaRegenFlat).coerceAtMost(combatant.maxMana)
            }
            status.copy(turnsRemaining = status.turnsRemaining - 1).takeIf { it.turnsRemaining > 0 }
        }
        if (startedAlive && hp <= 0 && !combatant.isPet && !combatant.isMinion) {
            logs += AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.ENEMY_DEFEATED,
                targetInstanceId = combatant.instanceId,
                targetNameRes = nameRes
            )
        }
        return combatant.copy(hp = hp, mana = mana, statuses = updatedStatuses)
    }

    private fun AdventureGateBattleSnapshot.allyByInstanceId(instanceId: String?): AdventureGateCombatantState? =
        when (instanceId) {
            pet.instanceId -> pet
            minion?.instanceId -> minion?.takeIf { it.isAlive }
            else -> null
        }

    private fun AdventureGateBattleSnapshot.withAlly(ally: AdventureGateCombatantState): AdventureGateBattleSnapshot =
        when (ally.instanceId) {
            pet.instanceId -> copy(pet = ally)
            minion?.instanceId -> copy(minion = ally)
            else -> this
        }

    private fun AdventureGateBattleSnapshot.enemyTarget(rng: Random): AdventureGateCombatantState =
        listOfNotNull(pet.takeIf { it.isAlive }, minion?.takeIf { it.isAlive }).randomOrNull(rng) ?: pet

    private fun advanceWaveOrVictory(
        snapshot: AdventureGateBattleSnapshot,
        phase: AdventureGatePhaseDefinition,
        profile: AdventureGateProfile
    ): AdventureGateBattleSnapshot {
        if (snapshot.enemies.any { it.isAlive }) return snapshot
        val nextWaveIndex = snapshot.waveIndex + 1
        if (nextWaveIndex < phase.waveMonsterIds.size) {
            val nextEnemies = createWave(phase, nextWaveIndex, snapshot.rngSeed)
            return snapshot.copy(
                waveIndex = nextWaveIndex,
                enemies = nextEnemies,
                turn = AdventureGateTurn.PET,
                pet = snapshot.pet.copy(
                    guarding = false
                ),
                log = appendLog(snapshot, AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.WAVE_STARTED,
                    amount = nextWaveIndex + 1
                )),
                updatedAt = System.currentTimeMillis()
            )
        }
        return snapshot.copy(
            turn = AdventureGateTurn.COMPLETE,
            isCompleted = true,
            isVictory = true,
            xpAwarded = phaseXpReward(phase),
            log = appendLog(snapshot, AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.VICTORY,
                amount = phaseXpReward(phase)
            )),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun completeDefeatIfPetDown(snapshot: AdventureGateBattleSnapshot): AdventureGateBattleSnapshot {
        if (snapshot.isCompleted || snapshot.pet.isAlive) return snapshot
        val log = if (snapshot.log.lastOrNull()?.messageKey == AdventureGateLogMessage.PET_DEFEATED) {
            snapshot.log
        } else {
            appendLog(snapshot, AdventureGateBattleLogEntry(AdventureGateLogMessage.PET_DEFEATED))
        }
        return snapshot.copy(
            turn = AdventureGateTurn.COMPLETE,
            isCompleted = true,
            isVictory = false,
            log = log,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun finishBattle(
        profile: AdventureGateProfile,
        snapshot: AdventureGateBattleSnapshot,
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel
    ): AdventureGateActionResult {
        val profileWithVitals = normalizedProfile(profile, educationLevel, introspectionLevel)
            .persistBattleVitals(snapshot.pet)
        if (!snapshot.isVictory) {
            return AdventureGateActionResult(
                snapshot = snapshot.copy(
                    log = appendLog(snapshot, AdventureGateBattleLogEntry(AdventureGateLogMessage.DEFEAT))
                ),
                profile = profileWithVitals.copy(
                    lastRecoveryAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val reward = grantXp(profileWithVitals, snapshot.xpAwarded, educationLevel, introspectionLevel)
        val levelLogs = buildList {
            if (reward.leveledUp) {
                add(AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.LEVEL_UP,
                    amount = reward.profile.level
                ))
            }
            reward.unlockedSkillIds.forEach { skillId ->
                add(AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.SKILL_UNLOCKED,
                    skillNameRes = AdventureGateCatalog.skill(skillId).nameRes
                ))
            }
        }
        return AdventureGateActionResult(
            snapshot = snapshot.copy(log = appendLog(snapshot, levelLogs)),
            profile = reward.profile.copy(
                lastRecoveryAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ),
            leveledUp = reward.leveledUp,
            unlockedSkillIds = reward.unlockedSkillIds
        )
    }

    fun grantXp(
        profile: AdventureGateProfile,
        xpAwarded: Int,
        educationLevel: Float = profile.educationLevel,
        introspectionLevel: Float = profile.introspectionLevel
    ): XpGrantResult {
        var level = profile.level
        var xp = profile.xp + xpAwarded
        var leveled = false
        var levelsGained = 0
        val previouslyKnown = (profile.learnedAttackIds + profile.learnedMagicIds).toSet()
        while (level < MAX_LEVEL && xp >= xpToNextLevel(level)) {
            xp -= xpToNextLevel(level)
            level += 1
            leveled = true
            levelsGained += 1
        }
        if (level >= MAX_LEVEL) xp = 0
        val learnedAttacks = AdventureGateCatalog.learnedAttackIdsForLevel(level)
        val learnedMagic = AdventureGateCatalog.learnedMagicIdsForLevel(level)
        val leveledProfile = profile.copy(level = level)
        val stats = AdventureGateCatalog.effectiveStats(leveledProfile, educationLevel, introspectionLevel, profile.exerciseLevel)
        val profileWithLevel = profile.copy(
            level = level,
            xp = xp,
            stats = stats,
            educationLevel = educationLevel.coerceAtLeast(0f),
            exerciseLevel = profile.exerciseLevel.coerceAtLeast(0f),
            introspectionLevel = introspectionLevel.coerceAtLeast(0f),
            currentHp = profile.currentHp.coerceIn(0, stats.maxHp),
            currentMana = profile.currentMana.coerceIn(0, stats.maxMana),
            skillPoints = profile.skillPoints + levelsGained,
            learnedAttackIds = learnedAttacks,
            learnedMagicIds = learnedMagic,
            equippedAttackIds = profile.equippedAttackIds
                .distinct()
                .filter { it in profile.purchasedSkillIds && it in learnedAttacks }
                .ifEmpty { AdventureGateCatalog.startingAttackIds }
                .take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT),
            equippedMagicIds = profile.equippedMagicIds
                .distinct()
                .filter {
                    it in profile.purchasedSkillIds &&
                        it in learnedMagic &&
                        AdventureGateCatalog.isEquippableMagicSkill(AdventureGateCatalog.skill(it))
                }
                .ifEmpty { AdventureGateCatalog.startingMagicIds }
                .take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT),
            updatedAt = System.currentTimeMillis()
        )
        val unlocked = (learnedAttacks + learnedMagic).filterNot(previouslyKnown::contains)
        return XpGrantResult(profileWithLevel, leveled, unlocked)
    }

    fun phaseXpReward(phase: AdventureGatePhaseDefinition): Int =
        phase.xpRewardOverride ?: phase.waveMonsterIds.flatten().sumOf { AdventureGateCatalog.monster(it).xpReward }

    /**
     * In campaign phases 10-14 a wave has a 25% chance (seeded) to swap its first monster that has an
     * elite variant for that variant. Bosses and the Night Arena never roll elites.
     */
    private fun withEliteVariant(
        phase: AdventureGatePhaseDefinition,
        ids: List<String>,
        waveIndex: Int,
        seed: Long
    ): List<String> {
        if (phase.isBoss || phase.worldId == AdventureGateCatalog.NIGHT_ARENA_WORLD_ID ||
            phase.phaseNumber !in AdventureGateCatalog.ELITE_FIRST_PHASE..AdventureGateCatalog.ELITE_LAST_PHASE
        ) return ids
        val roll = Random(seed * 131L + phase.phaseNumber * 7_919L + waveIndex * 104_729L + 17L).nextInt(100)
        if (roll >= AdventureGateCatalog.ELITE_CHANCE_PERCENT) return ids
        val index = ids.indexOfFirst { AdventureGateCatalog.eliteVariantIdFor(it) != null }
        if (index < 0) return ids
        val eliteId = AdventureGateCatalog.eliteVariantIdFor(ids[index]) ?: return ids
        return ids.toMutableList().also { it[index] = eliteId }
    }

    private fun createWave(
        phase: AdventureGatePhaseDefinition,
        waveIndex: Int,
        seed: Long
    ): List<AdventureGateCombatantState> {
        val ids = withEliteVariant(
            phase,
            phase.waveMonsterIds.getOrElse(waveIndex) { emptyList() }.take(AdventureGateCatalog.MAX_ENEMIES_PER_WAVE),
            waveIndex,
            seed
        )
        return ids.mapIndexed { index, monsterId ->
            val definition = AdventureGateCatalog.monster(monsterId)
            val level = phase.enemyLevelOverride ?: enemyLevelFor(phase.worldId, phase.phaseNumber, definition.isBoss)
            val scaled = scaleStats(definition.stats, level, definition.isBoss)
            val traits = listOfNotNull(definition.eliteTrait)
            val speed = if (AdventureGateEliteTrait.HASTE in traits) {
                (scaled.speed * ELITE_HASTE_PERCENT / 100f).roundToInt()
            } else scaled.speed
            AdventureGateCombatantState(
                instanceId = "w${waveIndex}_${index}_$monsterId",
                definitionId = monsterId,
                isPet = false,
                maxHp = scaled.maxHp,
                hp = scaled.maxHp,
                maxMana = scaled.maxMana,
                mana = scaled.maxMana,
                attack = scaled.attack,
                magic = scaled.magic,
                defense = scaled.defense,
                speed = speed,
                level = level,
                accuracy = scaled.accuracy,
                evasion = scaled.evasion,
                elements = listOfNotNull(definition.primaryElement, definition.secondaryElement),
                weaknesses = definition.weaknesses.toList(),
                resistances = definition.resistances.toList(),
                boss = definition.isBoss,
                traits = traits,
                shieldHp = if (AdventureGateEliteTrait.SHIELD in traits) {
                    (scaled.maxHp * ELITE_SHIELD_PERCENT / 100f).roundToInt().coerceAtLeast(1)
                } else 0
            )
        }
    }

    private fun scaleStats(
        stats: AdventureGateStats,
        level: Int,
        boss: Boolean
    ): AdventureGateStats {
        val multiplier = 1f + ((level.coerceAtLeast(1) - 1) * 0.045f) + if (boss) 0.10f else 0f
        return AdventureGateStats(
            maxHp = (stats.maxHp * multiplier).roundToInt().coerceAtLeast(1),
            maxMana = (stats.maxMana * multiplier).roundToInt().coerceAtLeast(0),
            attack = (stats.attack * multiplier).roundToInt().coerceAtLeast(1),
            magic = (stats.magic * multiplier).roundToInt().coerceAtLeast(1),
            defense = (stats.defense * multiplier).roundToInt().coerceAtLeast(0),
            speed = (stats.speed * multiplier).roundToInt().coerceAtLeast(1),
            accuracy = stats.accuracy,
            evasion = stats.evasion
        )
    }

    private fun calculateDamage(
        attacker: AdventureGateCombatantState,
        target: AdventureGateCombatantState,
        skillPower: Int,
        element: AdventureGateElement,
        magic: Boolean,
        loadout: AdventureGateEffectiveLoadout,
        modifier: AdventureGateBattleModifier? = null
    ): Int {
        val offense = if (magic) attacker.effectiveMagic() else attacker.effectiveAttack()
        val mitigation = if (magic) magicMitigation(target, 0.28f, 0.24f) else target.effectiveDefense() * 0.42f
        val base = max(1f, (offense * skillPower / 100f) - mitigation)
        val elementMultiplier = when (element) {
            in target.weaknesses -> WEAKNESS_MULTIPLIER
            in target.resistances -> RESISTANCE_MULTIPLIER
            else -> if (target.boss) BOSS_NON_WEAK_MULTIPLIER else 1f
        }
        val equipmentMultiplier = 1f + loadout.equipment.sumOf {
            if (it.effect.elementDamageElement == element) it.effect.elementDamageBonusPercent else 0
        } / 100f
        val statusIncomingMultiplier = 1f + (
            target.statuses.sumOf { it.incomingDamageBonusPercent } +
                if (!magic) target.statuses.sumOf { it.physicalDamageTakenBonusPercent } else 0
            ) / 100f
        val wardMultiplier = 1f - target.statuses.sumOf { it.incomingReductionPercent } / 100f
        val guard = if (target.guarding) GUARD_DAMAGE_MULTIPLIER else 1f
        val modifierMultiplier = modifier.damageFactor(element, magic)
        return max(1, (base * elementMultiplier * equipmentMultiplier * statusIncomingMultiplier * wardMultiplier.coerceAtLeast(0.25f) * guard * modifierMultiplier).roundToInt())
    }

    private fun AdventureGateBattleSnapshot.phaseDefinition(): AdventureGatePhaseDefinition =
        phaseOverride ?: AdventureGateCatalog.world(worldId).phases[(phaseNumber - 1).coerceIn(0, AdventureGateCatalog.PHASES_PER_WORLD - 1)]

    private fun rollHit(
        attacker: AdventureGateCombatantState,
        defender: AdventureGateCombatantState,
        skillAccuracy: Int,
        rng: Random,
        guaranteed: Boolean = false,
        modifier: AdventureGateBattleModifier? = null
    ): Boolean {
        if (guaranteed) return true
        val raw = skillAccuracy + (attacker.effectiveAccuracy() - 100) - defender.effectiveEvasion()
        val chance = (raw * modifier.accuracyFactor()).roundToInt().coerceIn(15, 99)
        return rng.nextInt(100) < chance
    }

    private fun calculateEnemyDamage(
        enemy: AdventureGateCombatantState,
        target: AdventureGateCombatantState,
        loadout: AdventureGateEffectiveLoadout,
        action: AdventureGateEnemyActionDefinition? = null,
        modifier: AdventureGateBattleModifier? = null
    ): Int {
        val useMagic = action?.kind == AdventureGateSkillKind.MAGIC || (action == null && enemy.magic > enemy.attack)
        val offense = if (useMagic) enemy.effectiveMagic() else enemy.effectiveAttack()
        val rage = if (enemy.enraged) 1.2f else 1f
        val guard = if (target.guarding) GUARD_DAMAGE_MULTIPLIER else 1f
        val element = action?.element ?: enemy.elements.firstOrNull()
        val elementMultiplier = when (element) {
            in target.weaknesses -> WEAKNESS_MULTIPLIER
            in target.resistances -> RESISTANCE_MULTIPLIER
            else -> 1f
        }
        val incomingMultiplier = if (target.isPet) {
            (1f - loadout.equipment.sumOf { it.effect.incomingReductionPercent } / 100f).coerceAtLeast(0.25f)
        } else {
            1f
        }
        val statusIncomingMultiplier = 1f + target.statuses.sumOf { it.incomingDamageBonusPercent } / 100f
        val wardMultiplier = 1f - target.statuses.sumOf { it.incomingReductionPercent } / 100f
        val mitigation = if (useMagic) magicMitigation(target, 0.25f, 0.25f) else target.effectiveDefense() * 0.35f
        val base = max(1f, (offense * (action?.power ?: 100) / 100f * rage) - mitigation)
        val modifierMultiplier = element?.let { modifier.damageFactor(it, useMagic) } ?: 1f
        return max(1, (base * guard * elementMultiplier * incomingMultiplier * statusIncomingMultiplier * wardMultiplier.coerceAtLeast(0.25f) * modifierMultiplier).roundToInt())
    }

    private fun magicMitigation(
        target: AdventureGateCombatantState,
        defenseWeight: Float,
        magicWeight: Float
    ): Float =
        (target.effectiveDefense() * defenseWeight) + (target.effectiveMagic() * magicWeight)

    private fun appendLog(
        snapshot: AdventureGateBattleSnapshot,
        entry: AdventureGateBattleLogEntry
    ): List<AdventureGateBattleLogEntry> =
        appendLog(snapshot, listOf(entry))

    private fun appendLog(
        snapshot: AdventureGateBattleSnapshot,
        entries: List<AdventureGateBattleLogEntry>
    ): List<AdventureGateBattleLogEntry> =
        (snapshot.log + entries).takeLast(80)

    private fun logsAddedSince(
        before: List<AdventureGateBattleLogEntry>,
        after: List<AdventureGateBattleLogEntry>
    ): List<AdventureGateBattleLogEntry> {
        val maxOverlap = minOf(before.size, after.size)
        val overlap = (maxOverlap downTo 0).firstOrNull { count ->
            before.takeLast(count) == after.take(count)
        } ?: 0
        return after.drop(overlap)
    }

    private fun AdventureGateProfile.persistBattleVitals(pet: AdventureGateCombatantState): AdventureGateProfile =
        copy(
            currentHp = pet.hp.coerceIn(0, stats.maxHp),
            currentMana = pet.mana.coerceIn(0, stats.maxMana),
            updatedAt = System.currentTimeMillis()
        )

    private fun eventsFromLogs(logs: List<AdventureGateBattleLogEntry>): List<AdventureGateBattleEvent> =
        logs.mapNotNull { entry ->
            when (entry.messageKey) {
                AdventureGateLogMessage.PET_USED_SKILL,
                AdventureGateLogMessage.ENEMY_USED_ATTACK -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.DAMAGE,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId,
                    statusId = entry.statusId,
                    amount = entry.amount,
                    element = entry.element
                )
                AdventureGateLogMessage.MISSED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.MISS,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId,
                    element = entry.element
                )
                AdventureGateLogMessage.PET_HEALED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.HEAL,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId,
                    amount = entry.amount,
                    element = entry.element
                )
                AdventureGateLogMessage.PET_SUMMONED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.SUMMON,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId
                )
                AdventureGateLogMessage.PET_GUARDED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.GUARD,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId,
                    amount = entry.amount
                )
                AdventureGateLogMessage.MANA_SHELL_RECOIL -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.DAMAGE,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    skillId = entry.skillId,
                    amount = entry.amount,
                    element = entry.element
                )
                AdventureGateLogMessage.STATUS_DAMAGE -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.STATUS_DAMAGE,
                    targetInstanceId = entry.targetInstanceId,
                    statusId = entry.statusId,
                    amount = entry.amount
                )
                AdventureGateLogMessage.STATUS_SKIP -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.STATUS_SKIP,
                    actorInstanceId = entry.actorInstanceId,
                    statusId = entry.statusId
                )
                AdventureGateLogMessage.PET_USED_ITEM -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.ITEM_USED,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    itemId = entry.itemId,
                    amount = entry.amount
                )
                AdventureGateLogMessage.POTION_LIMIT_REACHED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.ITEM_BLOCKED,
                    actorInstanceId = entry.actorInstanceId,
                    itemId = entry.itemId
                )
                AdventureGateLogMessage.SKILL_ON_COOLDOWN -> null
                AdventureGateLogMessage.GUARD_LIMIT_REACHED -> null
                AdventureGateLogMessage.COINS_REWARDED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.COINS_REWARDED,
                    amount = entry.amount
                )
                AdventureGateLogMessage.POTION_REWARDED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.POTION_REWARDED,
                    itemId = entry.itemId,
                    amount = entry.amount
                )
                AdventureGateLogMessage.EQUIPMENT_DROPPED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.EQUIPMENT_DROP,
                    itemId = entry.itemId,
                    equipmentId = entry.equipmentId
                )
                AdventureGateLogMessage.EQUIPMENT_TRIGGERED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.EQUIPMENT_TRIGGER,
                    actorInstanceId = entry.actorInstanceId,
                    targetInstanceId = entry.targetInstanceId,
                    equipmentId = entry.equipmentId,
                    amount = entry.amount
                )
                AdventureGateLogMessage.WAVE_STARTED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.WAVE_STARTED,
                    amount = entry.amount
                )
                AdventureGateLogMessage.VICTORY -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.VICTORY,
                    amount = entry.amount
                )
                AdventureGateLogMessage.DEFEAT,
                AdventureGateLogMessage.PET_DEFEATED -> AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.DEFEAT
                )
                else -> null
            }
        }

    data class XpGrantResult(
        val profile: AdventureGateProfile,
        val leveledUp: Boolean,
        val unlockedSkillIds: List<String>
    )
}
