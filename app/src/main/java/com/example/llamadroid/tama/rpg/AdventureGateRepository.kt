package com.example.llamadroid.tama.rpg

import com.example.llamadroid.tama.db.AdventureGateBattleStateEntity
import com.example.llamadroid.tama.db.AdventureGateNightArenaRunEntity
import com.example.llamadroid.tama.db.AdventureGateProfileEntity
import com.example.llamadroid.tama.db.AdventureGateWorldProgressEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaEventEntity
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.Mood
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.isEffectivelyMad
import com.example.llamadroid.tama.game.PetMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

class AdventureGateRepository(
    private val database: TamaDatabase
) {
    private val dao = database.tamaDao()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val SLEEP_HP_PER_MINUTE = 7
        const val SLEEP_MANA_PER_MINUTE = 4
        const val RELAX_HP_PER_MINUTE = 5
        const val RELAX_MANA_PER_MINUTE = 3
        const val AWAKE_HP_PER_MINUTE = 3
        const val AWAKE_MANA_PER_MINUTE = 2
    }

    fun observeProfile(petId: String): Flow<AdventureGateProfile?> =
        dao.observeAdventureGateProfile(petId).combine(dao.observePet(petId)) { entity, pet ->
            entity?.toDomain()?.let { profile ->
                AdventureGateCombatEngine.normalizedProfile(profile, pet?.educationLevel ?: 0f)
            }
        }

    fun observeProgress(petId: String): Flow<List<AdventureGateWorldProgress>> =
        dao.observeAdventureGateWorldProgress(petId).map { rows -> rows.map { it.toDomain() } }

    fun observeNightArenaRun(petId: String): Flow<AdventureGateNightArenaRun?> =
        dao.observeAdventureGateNightArenaRun(petId).map { entity -> entity?.toDomainNightArenaRun() }

    fun observeBattle(petId: String): Flow<AdventureGateBattleSnapshot?> =
        dao.observeAdventureGateBattleState(petId).map { entity -> entity?.toDomainBattle() }

    fun observePetInventory(petId: String): Flow<List<InventoryItem>> =
        dao.observeActivePet().map { entity ->
            if (entity?.id == petId) {
                PetMapper.toDomain(entity).inventory
            } else {
                dao.getPet(petId)?.let(PetMapper::toDomain)?.inventory.orEmpty()
            }
        }

    suspend fun getOrCreateProfile(petId: String): AdventureGateProfile = withContext(Dispatchers.IO) {
        recoverProfileForPet(petId)
    }

    suspend fun recoverProfile(petId: String): AdventureGateProfile = withContext(Dispatchers.IO) {
        recoverProfileForPet(petId)
    }

    private suspend fun recoverProfileForPet(
        petId: String,
        now: Long = System.currentTimeMillis()
    ): AdventureGateProfile {
        val existing = dao.getAdventureGateProfile(petId)?.toDomain()
        val educationLevel = educationLevelForPet(petId)
        val profile = if (existing != null) {
            AdventureGateCombatEngine.normalizedProfile(existing, educationLevel)
        } else {
            AdventureGateCombatEngine.normalizedProfile(AdventureGateProfile(petId = petId, lastRecoveryAt = now, updatedAt = now), educationLevel)
        }
        val activeBattle = dao.getAdventureGateBattleState(petId)?.toDomainBattle()
        val recovered = if (activeBattle != null && !activeBattle.isCompleted) {
            profile.copy(lastRecoveryAt = now, updatedAt = now)
        } else {
            applyRecovery(profile, petId, now)
        }
        if (recovered != existing) {
            dao.saveAdventureGateProfile(recovered.toEntity())
        }
        return recovered
    }

    private suspend fun educationLevelForPet(petId: String): Float =
        dao.getPet(petId)?.educationLevel ?: 0f

    suspend fun getProgress(petId: String): List<AdventureGateWorldProgress> = withContext(Dispatchers.IO) {
        val existing = dao.getAdventureGateWorldProgress(petId).map { it.toDomain() }
        val known = existing.associateBy { it.worldId }
        AdventureGateCatalog.worlds.map { world ->
            known[world.id] ?: AdventureGateWorldProgress(petId = petId, worldId = world.id)
        }
    }

    suspend fun getOrCreateNightArenaRun(
        petId: String,
        now: Long = System.currentTimeMillis()
    ): AdventureGateNightArenaRun = withContext(Dispatchers.IO) {
        getOrCreateNightArenaRunForPet(petId, now)
    }

    private suspend fun getOrCreateNightArenaRunForPet(
        petId: String,
        now: Long = System.currentTimeMillis()
    ): AdventureGateNightArenaRun {
        val nightKey = NightArenaGenerator.nightKeyFor(now)
        val existing = dao.getAdventureGateNightArenaRun(petId)?.toDomainNightArenaRun()
        if (existing?.nightKey == nightKey) return existing
        val progress = getProgress(petId)
        val sourceDepth = NightArenaGenerator.sourceDepthForProgress(progress)
        val generated = NightArenaGenerator.generateRun(
            petId = petId,
            nightKey = nightKey,
            sourceDepth = sourceDepth,
            nowMillis = now
        )
        dao.saveAdventureGateNightArenaRun(generated.toEntity())
        return generated
    }

    suspend fun startBattle(
        petId: String,
        worldId: String,
        phaseNumber: Int
    ): AdventureGateBattleSnapshot? = withContext(Dispatchers.IO) {
        val profile = recoverProfileForPet(petId)
        if (profile.currentHp <= 0) return@withContext null
        val battleProfile = profile.copy(lastRecoveryAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        dao.saveAdventureGateProfile(battleProfile.toEntity())
        val world = AdventureGateCatalog.world(worldId)
        val phase = world.phases[(phaseNumber - 1).coerceIn(0, AdventureGateCatalog.PHASES_PER_WORLD - 1)]
        val snapshot = AdventureGateCombatEngine.startBattle(battleProfile, phase, educationLevel = educationLevelForPet(petId))
        dao.saveAdventureGateBattleState(snapshot.toEntity())
        logAdventureGateEvent(
            petId = petId,
            type = EventType.BATTLE_START,
            details = "The pet stepped through the Gate Nexum into ${world.id} phase ${phase.phaseNumber} and heard the next dreamlight story."
        )
        snapshot
    }

    suspend fun startNightArenaBattle(
        petId: String,
        levelIndex: Int,
        now: Long = System.currentTimeMillis()
    ): AdventureGateBattleSnapshot? = withContext(Dispatchers.IO) {
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain) ?: return@withContext null
        if (!pet.isSleeping) return@withContext null
        if (!NightArenaGenerator.isActiveWindow(now)) return@withContext null
        val profile = recoverProfileForPet(petId, now)
        if (profile.currentHp <= 0) return@withContext null
        val run = getOrCreateNightArenaRunForPet(petId, now)
        val level = run.levels.firstOrNull { it.levelIndex == levelIndex } ?: return@withContext null
        if (level.id in run.clearedLevelIds) return@withContext null
        val phase = NightArenaGenerator.phaseForLevel(level)
        val battleProfile = profile.copy(lastRecoveryAt = now, updatedAt = now)
        dao.saveAdventureGateProfile(battleProfile.toEntity())
        val snapshot = AdventureGateCombatEngine.startBattle(
            profile = battleProfile,
            phase = phase,
            seed = level.seed,
            educationLevel = educationLevelForPet(petId)
        )
        dao.saveAdventureGateBattleState(snapshot.toEntity())
        logAdventureGateEvent(
            petId = petId,
            type = EventType.BATTLE_START,
            details = "The pet entered Night Arena level ${level.levelIndex} for nightly dream combat."
        )
        snapshot
    }

    suspend fun performSkill(
        petId: String,
        skillId: String,
        targetInstanceId: String?
    ): AdventureGateActionResult? = withContext(Dispatchers.IO) {
        val profile = recoverProfileForPet(petId)
        val snapshot = dao.getAdventureGateBattleState(petId)?.toDomainBattle() ?: return@withContext null
        val result = AdventureGateCombatEngine.performSkill(profile, snapshot, skillId, targetInstanceId, educationLevelForPet(petId))
        val finalized = persistActionResult(result)
        finalized
    }

    suspend fun resolveEnemyTurn(petId: String): AdventureGateActionResult? = withContext(Dispatchers.IO) {
        val profile = recoverProfileForPet(petId)
        val snapshot = dao.getAdventureGateBattleState(petId)?.toDomainBattle() ?: return@withContext null
        val result = AdventureGateCombatEngine.resolveEnemyTurn(profile, snapshot, educationLevelForPet(petId))
        persistActionResult(result)
    }

    suspend fun updateLoadout(
        petId: String,
        attackIds: List<String>,
        magicIds: List<String>
    ): AdventureGateProfile = withContext(Dispatchers.IO) {
        val profile = getOrCreateProfile(petId)
        val normalized = AdventureGateCombatEngine.normalizedProfile(profile, educationLevelForPet(petId))
        val purchased = normalized.purchasedSkillIds.toSet()
        val purchasedAttacks = purchased.filter { AdventureGateCatalog.skill(it).kind == AdventureGateSkillKind.ATTACK }.toSet()
        val purchasedMagic = purchased
            .filter { AdventureGateCatalog.isEquippableMagicSkill(AdventureGateCatalog.skill(it)) }
            .toSet()
        val updated = normalized.copy(
            equippedAttackIds = attackIds
                .distinct()
                .filter { it in purchasedAttacks }
                .take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT)
                .ifEmpty { AdventureGateCatalog.startingAttackIds },
            equippedMagicIds = magicIds
                .distinct()
                .filter { it in purchasedMagic }
                .take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT)
                .ifEmpty { AdventureGateCatalog.startingMagicIds },
            updatedAt = System.currentTimeMillis()
        )
        dao.saveAdventureGateProfile(updated.toEntity())
        updated
    }

    suspend fun purchaseSkill(
        petId: String,
        skillId: String
    ): AdventureGateSkillPurchaseResult = withContext(Dispatchers.IO) {
        val profile = getOrCreateProfile(petId)
        val normalized = AdventureGateCombatEngine.normalizedProfile(profile, educationLevelForPet(petId))
        val skill = AdventureGateCatalog.skill(skillId)
        if (skill.id in normalized.purchasedSkillIds) {
            return@withContext AdventureGateSkillPurchaseResult(normalized, purchased = false, AdventureGateSkillPurchaseError.ALREADY_PURCHASED)
        }
        if (normalized.level < skill.unlockLevel) {
            return@withContext AdventureGateSkillPurchaseResult(normalized, purchased = false, AdventureGateSkillPurchaseError.LEVEL_LOCKED)
        }
        if (skill.prerequisiteSkillIds.any { it !in normalized.purchasedSkillIds }) {
            return@withContext AdventureGateSkillPurchaseResult(normalized, purchased = false, AdventureGateSkillPurchaseError.PREREQUISITE_LOCKED)
        }
        val cost = AdventureGateCatalog.skillPointCost(skill)
        if (normalized.skillPoints < cost) {
            return@withContext AdventureGateSkillPurchaseResult(normalized, purchased = false, AdventureGateSkillPurchaseError.NOT_ENOUGH_POINTS)
        }
        val purchasedIds = (normalized.purchasedSkillIds + skill.id).distinct()
        val attackIds = if (skill.kind == AdventureGateSkillKind.ATTACK && normalized.equippedAttackIds.size < AdventureGateCatalog.LOADOUT_ATTACK_LIMIT) {
            (normalized.equippedAttackIds + skill.id).distinct()
        } else {
            normalized.equippedAttackIds
        }
        val magicIds = if (AdventureGateCatalog.isEquippableMagicSkill(skill) && normalized.equippedMagicIds.size < AdventureGateCatalog.LOADOUT_MAGIC_LIMIT) {
            (normalized.equippedMagicIds + skill.id).distinct()
        } else {
            normalized.equippedMagicIds
        }
        val updated = normalized.copy(
            skillPoints = normalized.skillPoints - cost,
            purchasedSkillIds = purchasedIds,
            equippedAttackIds = attackIds.take(AdventureGateCatalog.LOADOUT_ATTACK_LIMIT),
            equippedMagicIds = magicIds.take(AdventureGateCatalog.LOADOUT_MAGIC_LIMIT),
            updatedAt = System.currentTimeMillis()
        )
        dao.saveAdventureGateProfile(updated.toEntity())
        AdventureGateSkillPurchaseResult(updated, purchased = true)
    }

    suspend fun purchaseSupply(
        petId: String,
        supplyId: String
    ): AdventureGatePurchaseResult = withContext(Dispatchers.IO) {
        val supply = AdventureGateCatalog.supply(supplyId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        val petEntity = dao.getPet(petId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NO_PET)
        val pet = PetMapper.toDomain(petEntity)
        if (!isWorldIndexUnlocked(supply.unlockWorldIndex, getProgress(petId))) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.LOCKED)
        }
        if (pet.money < supply.price) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NOT_ENOUGH_COINS)
        }
        val updated = pet.copy(
            money = pet.money - supply.price,
            inventory = addInventoryItem(
                pet.inventory,
                InventoryItem(
                    id = supply.id,
                    name = supply.id,
                    type = if (supply.kind == AdventureGateSupplyKind.SKILL_POINT) ItemType.TREASURE else ItemType.POTION,
                    quantity = 1
                )
            )
        )
        dao.savePet(PetMapper.toEntity(updated))
        AdventureGatePurchaseResult(true)
    }

    suspend fun purchaseRecipe(
        petId: String,
        recipeId: String
    ): AdventureGatePurchaseResult = withContext(Dispatchers.IO) {
        val recipe = AdventureGateCatalog.recipe(recipeId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        val supply = AdventureGateCatalog.supply(recipe.supplyId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        val petEntity = dao.getPet(petId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NO_PET)
        val pet = PetMapper.toDomain(petEntity)
        if (!isWorldIndexUnlocked(recipe.unlockWorldIndex, getProgress(petId))) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.LOCKED)
        }
        if (pet.inventory.any { it.id == recipe.id }) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.ALREADY_OWNED)
        }
        if (pet.money < recipe.price) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NOT_ENOUGH_COINS)
        }
        val updated = pet.copy(
            money = pet.money - recipe.price,
            inventory = addInventoryItem(
                pet.inventory,
                InventoryItem(
                    id = recipe.id,
                    name = "Recipe: ${supply.id}",
                    type = ItemType.RECIPE,
                    quantity = 1
                )
            )
        )
        dao.savePet(PetMapper.toEntity(updated))
        AdventureGatePurchaseResult(true)
    }

    suspend fun purchaseEquipment(
        petId: String,
        equipmentId: String
    ): AdventureGatePurchaseResult = withContext(Dispatchers.IO) {
        val equipment = AdventureGateCatalog.equipment(equipmentId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        if (equipment.uniqueDrop) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.LOCKED)
        }
        val petEntity = dao.getPet(petId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NO_PET)
        val pet = PetMapper.toDomain(petEntity)
        if (!isWorldIndexUnlocked(equipment.unlockWorldIndex, getProgress(petId))) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.LOCKED)
        }
        if (pet.inventory.any { it.id == equipment.id }) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.ALREADY_OWNED)
        }
        if (pet.money < equipment.price) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NOT_ENOUGH_COINS)
        }
        val updated = pet.copy(
            money = pet.money - equipment.price,
            inventory = addInventoryItem(pet.inventory, equipment.toInventoryItem())
        )
        dao.savePet(PetMapper.toEntity(updated))
        AdventureGatePurchaseResult(true)
    }

    suspend fun sellEquipment(
        petId: String,
        equipmentId: String
    ): AdventureGatePurchaseResult = withContext(Dispatchers.IO) {
        val activeBattle = dao.getAdventureGateBattleState(petId)?.toDomainBattle()
        if (activeBattle != null && !activeBattle.isCompleted) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.LOCKED)
        }
        val equipment = AdventureGateCatalog.equipment(equipmentId)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        if (equipment.uniqueDrop || equipment.price <= 0) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNSELLABLE)
        }
        val profile = getOrCreateProfile(petId)
        if (equipment.id == profile.equippedWeaponId ||
            equipment.id == profile.equippedShieldId ||
            equipment.id == profile.equippedRingId ||
            equipment.id == profile.equippedRelicId
        ) {
            return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.EQUIPPED)
        }
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.NO_PET)
        val updatedInventory = consumeInventoryItem(pet.inventory, equipment.id)
            ?: return@withContext AdventureGatePurchaseResult(false, AdventureGatePurchaseError.UNKNOWN_ITEM)
        val sellValue = (equipment.price * 75 / 100f).toInt().coerceAtLeast(1)
        dao.savePet(PetMapper.toEntity(pet.copy(
            money = pet.money + sellValue,
            inventory = updatedInventory
        )))
        logAdventureGateEvent(
            petId = petId,
            type = EventType.SOLD,
            details = "The pet sold ${equipment.id} from the Gate Nexum shop for $sellValue coins."
        )
        AdventureGatePurchaseResult(true)
    }

    suspend fun equipItem(
        petId: String,
        equipmentId: String?,
        slot: AdventureGateEquipmentSlot
    ): AdventureGateEquipResult = withContext(Dispatchers.IO) {
        val activeBattle = dao.getAdventureGateBattleState(petId)?.toDomainBattle()
        val profile = getOrCreateProfile(petId)
        if (activeBattle != null && !activeBattle.isCompleted) {
            return@withContext AdventureGateEquipResult(profile, equipped = false, AdventureGateEquipError.ACTIVE_BATTLE)
        }
        val normalized = AdventureGateCombatEngine.normalizedProfile(profile, educationLevelForPet(petId))
        val updated = if (equipmentId == null) {
            normalized.withEquippedSlot(slot, null)
        } else {
            val equipment = AdventureGateCatalog.equipment(equipmentId)
                ?: return@withContext AdventureGateEquipResult(normalized, false, AdventureGateEquipError.UNKNOWN_ITEM)
            if (equipment.slot != slot) {
                return@withContext AdventureGateEquipResult(normalized, false, AdventureGateEquipError.WRONG_SLOT)
            }
            val pet = dao.getPet(petId)?.let(PetMapper::toDomain)
            if (pet?.inventory?.none { it.id == equipment.id } != false) {
                return@withContext AdventureGateEquipResult(normalized, false, AdventureGateEquipError.NOT_OWNED)
            }
            normalized.withEquippedSlot(slot, equipment.id)
        }.let { AdventureGateCombatEngine.normalizedProfile(it, educationLevelForPet(petId)) }
        dao.saveAdventureGateProfile(updated.toEntity())
        equipmentId?.let { equippedId ->
            val equipment = AdventureGateCatalog.equipment(equippedId)
            logAdventureGateEvent(
                petId = petId,
                type = EventType.EQUIPPED,
                details = "The pet equipped ${equipment?.id ?: equippedId} in the ${slot.name.lowercase()} slot for Adventure Gate battles."
            )
        }
        AdventureGateEquipResult(updated, equipped = true)
    }

    suspend fun useSupplyAtHome(
        petId: String,
        supplyId: String
    ): AdventureGatePotionUseResult = withContext(Dispatchers.IO) {
        val supply = AdventureGateCatalog.supply(supplyId)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.UNKNOWN_ITEM)
        if (supply.kind == AdventureGateSupplyKind.CLEANSE) {
            return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.ACTIVE_BATTLE_REQUIRED)
        }
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_OWNED)
        val updatedInventory = consumeInventoryItem(pet.inventory, supply.id)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_OWNED)
        val profile = recoverProfileForPet(petId)
        val bonus = AdventureGateCatalog.loadoutForProfile(profile).equipment.sumOf { it.effect.potionBonusPercent }
        val amount = (supply.amount * (100 + bonus) / 100f).toInt().coerceAtLeast(1)
        val updatedProfile = when (supply.kind) {
            AdventureGateSupplyKind.HP -> {
                if (profile.currentHp >= profile.stats.maxHp) return@withContext AdventureGatePotionUseResult(used = false, profile = profile, error = AdventureGatePotionUseError.FULL)
                profile.copy(currentHp = (profile.currentHp + amount).coerceAtMost(profile.stats.maxHp))
            }
            AdventureGateSupplyKind.MANA -> {
                if (profile.currentMana >= profile.stats.maxMana) return@withContext AdventureGatePotionUseResult(used = false, profile = profile, error = AdventureGatePotionUseError.FULL)
                profile.copy(currentMana = (profile.currentMana + amount).coerceAtMost(profile.stats.maxMana))
            }
            AdventureGateSupplyKind.CLEANSE -> profile
            AdventureGateSupplyKind.SKILL_POINT -> profile.copy(skillPoints = profile.skillPoints + supply.amount)
        }.copy(updatedAt = System.currentTimeMillis())
        dao.savePet(PetMapper.toEntity(pet.copy(inventory = updatedInventory)))
        dao.saveAdventureGateProfile(updatedProfile.toEntity())
        AdventureGatePotionUseResult(profile = updatedProfile, used = true)
    }

    suspend fun useSupplyInBattle(
        petId: String,
        supplyId: String
    ): AdventureGatePotionUseResult = withContext(Dispatchers.IO) {
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_OWNED)
        if (pet.inventory.none { it.id == supplyId && it.quantity > 0 }) {
            return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_OWNED)
        }
        val profile = recoverProfileForPet(petId)
        val snapshot = dao.getAdventureGateBattleState(petId)?.toDomainBattle()
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.ACTIVE_BATTLE_REQUIRED)
        val result = AdventureGateCombatEngine.useSupply(profile, snapshot, supplyId, educationLevelForPet(petId))
        if (!result.used || result.result == null) {
            result.result?.let { persistActionResult(it) }
            return@withContext result
        }
        val supply = AdventureGateCatalog.supply(supplyId)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.UNKNOWN_ITEM)
        val updatedInventory = consumeInventoryItem(pet.inventory, supply.id)
            ?: return@withContext AdventureGatePotionUseResult(used = false, error = AdventureGatePotionUseError.NOT_OWNED)
        dao.savePet(PetMapper.toEntity(pet.copy(inventory = updatedInventory)))
        val finalized = persistActionResult(result.result)
        AdventureGatePotionUseResult(result = finalized, used = true)
    }

    suspend fun abandonBattle(petId: String, applyRetreatPenalty: Boolean = false): Long = withContext(Dispatchers.IO) {
        val snapshot = dao.getAdventureGateBattleState(petId)?.toDomainBattle()
        val educationLevel = educationLevelForPet(petId)
        val profile = dao.getAdventureGateProfile(petId)?.toDomain()?.let { AdventureGateCombatEngine.normalizedProfile(it, educationLevel) }
        var paidPenalty = 0L
        if (snapshot != null && profile != null) {
            dao.saveAdventureGateProfile(
                profile.copy(
                    currentHp = snapshot.pet.hp.coerceIn(0, profile.stats.maxHp),
                    currentMana = snapshot.pet.mana.coerceIn(0, profile.stats.maxMana),
                    lastRecoveryAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ).toEntity()
            )
        }
        if (snapshot != null && applyRetreatPenalty && !snapshot.isCompleted) {
            val phase = phaseForSnapshot(snapshot)
            val penalty = AdventureGateCatalog.phaseRetreatPenalty(phase).toLong()
            val pet = dao.getPet(petId)?.let(PetMapper::toDomain)
            if (pet != null) {
                paidPenalty = minOf(pet.money, penalty)
                dao.savePet(PetMapper.toEntity(pet.copy(money = (pet.money - paidPenalty).coerceAtLeast(0L))))
            }
        }
        dao.deleteAdventureGateBattleState(petId)
        if (snapshot != null) {
            logAdventureGateEvent(
                petId = petId,
                type = EventType.BATTLE_LOST,
                details = "The pet retreated from Adventure Gate phase ${snapshot.phaseNumber} in ${snapshot.worldId}, paid $paidPenalty coins, and kept its remaining HP and mana."
            )
        }
        paidPenalty
    }

    private suspend fun applyRecovery(
        profile: AdventureGateProfile,
        petId: String,
        now: Long
    ): AdventureGateProfile {
        val lastRecoveryAt = profile.lastRecoveryAt.takeIf { it > 0L } ?: now
        val minutes = ((now - lastRecoveryAt).coerceAtLeast(0L) / 60_000L).toInt()
        if (minutes <= 0) return profile.copy(lastRecoveryAt = lastRecoveryAt)
        val pet = dao.getPet(petId)
        val sleeping = pet?.isSleeping == true
        val relaxing = pet?.currentActivity == "RELAXING"
        val hpRate = when {
            sleeping -> SLEEP_HP_PER_MINUTE
            relaxing -> RELAX_HP_PER_MINUTE
            else -> AWAKE_HP_PER_MINUTE
        }
        val manaRate = when {
            sleeping -> SLEEP_MANA_PER_MINUTE
            relaxing -> RELAX_MANA_PER_MINUTE
            else -> AWAKE_MANA_PER_MINUTE
        }
        return profile.copy(
            currentHp = (profile.currentHp + minutes * hpRate).coerceAtMost(profile.stats.maxHp),
            currentMana = (profile.currentMana + minutes * manaRate).coerceAtMost(profile.stats.maxMana),
            lastRecoveryAt = lastRecoveryAt + minutes * 60_000L,
            updatedAt = now
        )
    }

    private suspend fun saveVictoryProgress(petId: String, snapshot: AdventureGateBattleSnapshot) {
        val current = dao.getAdventureGateWorldProgress(petId)
            .firstOrNull { it.worldId == snapshot.worldId }
            ?.toDomain()
            ?: AdventureGateWorldProgress(petId = petId, worldId = snapshot.worldId)
        dao.saveAdventureGateWorldProgress(
            current.copy(
                highestClearedPhase = maxOf(current.highestClearedPhase, snapshot.phaseNumber),
                midBossCleared = current.midBossCleared || snapshot.phaseNumber >= 7,
                finalBossCleared = current.finalBossCleared || snapshot.phaseNumber >= 15,
                updatedAt = System.currentTimeMillis()
            ).toEntity()
        )
    }

    private suspend fun markNightArenaLevelCleared(snapshot: AdventureGateBattleSnapshot) {
        val levelId = snapshot.phaseOverride?.nightArenaLevelId ?: snapshot.phaseNumber.toString()
        val run = dao.getAdventureGateNightArenaRun(snapshot.petId)?.toDomainNightArenaRun() ?: return
        if (levelId in run.clearedLevelIds) return
        dao.saveAdventureGateNightArenaRun(
            run.copy(
                clearedLevelIds = (run.clearedLevelIds + levelId).distinct(),
                updatedAt = System.currentTimeMillis()
            ).toEntity()
        )
    }

    private suspend fun persistActionResult(result: AdventureGateActionResult): AdventureGateActionResult {
        var finalResult = result
        if (result.snapshot.isVictory) {
            finalResult = grantVictoryRewards(finalResult)
            if (finalResult.snapshot.isNightArenaBattle()) {
                markNightArenaLevelCleared(finalResult.snapshot)
            } else {
                finalResult = maybeGrantBossRelics(finalResult)
                saveVictoryProgress(finalResult.profile.petId, finalResult.snapshot)
            }
            logVictoryEvent(finalResult)
        } else if (result.snapshot.isCompleted) {
            if (result.snapshot.isNightArenaBattle()) {
                markNightArenaLevelCleared(result.snapshot)
                logAdventureGateEvent(
                    petId = result.profile.petId,
                    type = EventType.BATTLE_LOST,
                    details = "The pet was defeated in Night Arena level ${result.snapshot.phaseNumber}, stayed asleep, and kept its health and happiness."
                )
            } else {
                applyDefeatCarePenalty(result.profile.petId)
                logAdventureGateEvent(
                    petId = result.profile.petId,
                    type = EventType.BATTLE_LOST,
                    details = "The pet was defeated in Adventure Gate ${result.snapshot.worldId} phase ${result.snapshot.phaseNumber} and needs recovery before trying again."
                )
            }
        }
        dao.saveAdventureGateBattleState(finalResult.snapshot.toEntity())
        dao.saveAdventureGateProfile(finalResult.profile.toEntity())
        return finalResult
    }

    private suspend fun applyDefeatCarePenalty(petId: String) {
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain) ?: return
        val penalizedStats = pet.stats.copy(
            happiness = minOf(pet.stats.happiness, 25f),
            health = minOf(pet.stats.health, 25f)
        )
        if (penalizedStats != pet.stats) {
            dao.savePet(PetMapper.toEntity(pet.copy(stats = penalizedStats)))
        }
    }

    private suspend fun grantVictoryRewards(result: AdventureGateActionResult): AdventureGateActionResult {
        val snapshot = result.snapshot
        val phase = phaseForSnapshot(snapshot)
        val pet = dao.getPet(snapshot.petId)?.let(PetMapper::toDomain) ?: return result
        val worldIndex = if (snapshot.isNightArenaBattle()) {
            (((phase.sourceDepth ?: 1) - 1) / AdventureGateCatalog.PHASES_PER_WORLD)
                .coerceIn(0, AdventureGateCatalog.WORLD_COUNT - 1)
        } else {
            AdventureGateCatalog.worlds.indexOfFirst { it.id == snapshot.worldId }.coerceAtLeast(0)
        }
        val isReplay = if (snapshot.isNightArenaBattle()) {
            false
        } else {
            dao.getAdventureGateWorldProgress(snapshot.petId)
                .firstOrNull { it.worldId == snapshot.worldId }
                ?.highestClearedPhase
                ?.let { it >= snapshot.phaseNumber }
                ?: false
        }
        val coins = AdventureGateCatalog.phaseCoinReward(phase, replay = isReplay)
        val rng = Random(snapshot.rngSeed + snapshot.worldId.hashCode() + snapshot.phaseNumber * 53 + snapshot.log.size)
        val potionChance = AdventureGateCatalog.phasePotionRewardChancePercent(phase, replay = isReplay)
        val wonPotion = if (rng.nextInt(100) < potionChance) {
            val pool = AdventureGateCatalog.rewardSupplyPoolForWorld(worldIndex)
            val currentTier = pool.filter { it.unlockWorldIndex == worldIndex }
            val earlierTier = pool.filter { it.unlockWorldIndex < worldIndex }
            val weightedPool = if (currentTier.isNotEmpty() && rng.nextInt(100) < 60) currentTier else earlierTier.ifEmpty { currentTier }
            weightedPool.randomOrNull(rng)
        } else {
            null
        }
        var inventory = pet.inventory
        if (wonPotion != null) {
            inventory = addInventoryItem(
                inventory,
                InventoryItem(
                    id = wonPotion.id,
                    name = wonPotion.id,
                    type = ItemType.POTION,
                    quantity = 1
                )
            )
        }
        dao.savePet(PetMapper.toEntity(pet.copy(
            money = pet.money + coins,
            inventory = inventory
        ).withAdventureGateHappiness(15f)))
        logAdventureGateEvent(
            petId = pet.id,
            type = EventType.PLAYED,
            details = "${pet.name} felt happier after winning an Adventure Gate phase."
        )
        val logs = buildList {
            add(AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.COINS_REWARDED,
                amount = coins
            ))
            if (wonPotion != null) {
                add(AdventureGateBattleLogEntry(
                    messageKey = AdventureGateLogMessage.POTION_REWARDED,
                    itemId = wonPotion.id,
                    amount = 1
                ))
            }
        }
        val updatedSnapshot = snapshot.copy(
            log = (snapshot.log + logs).takeLast(80),
            updatedAt = System.currentTimeMillis()
        )
        return result.copy(
            snapshot = updatedSnapshot,
            events = result.events +
                AdventureGateBattleEvent(AdventureGateBattleEventType.COINS_REWARDED, amount = coins) +
                listOfNotNull(wonPotion?.let {
                    AdventureGateBattleEvent(
                        type = AdventureGateBattleEventType.POTION_REWARDED,
                        itemId = it.id,
                        amount = 1
                    )
                })
        )
    }

    private fun TamaPet.withAdventureGateHappiness(amount: Float): TamaPet {
        val updatedStats = stats.copy(happiness = (stats.happiness + amount.coerceAtLeast(0f)).coerceAtMost(100f))
        val withStats = copy(stats = updatedStats)
        return withStats.copy(mood = adventureGateMoodFor(withStats))
    }

    private fun adventureGateMoodFor(pet: TamaPet): Mood = when {
        pet.isSleeping -> Mood.SLEEPING
        pet.isEffectivelyMad() -> Mood.ANGRY
        pet.stage == GrowthStage.EGG -> calculateAdventureGateMood(pet.stats)
        else -> calculateAdventureGateMood(pet.stats)
    }

    private fun calculateAdventureGateMood(stats: PetStats): Mood = when {
        stats.health < 50 -> Mood.ANGRY
        stats.health < 20 -> Mood.SICK
        stats.energy < 20 -> Mood.SLEEPING
        stats.critical() -> Mood.SAD
        stats.happiness > 80 && stats.hunger > 60 -> Mood.ECSTATIC
        stats.happiness > 50 -> Mood.HAPPY
        stats.happiness > 30 -> Mood.NEUTRAL
        stats.happiness > 10 -> Mood.SAD
        else -> Mood.ANGRY
    }

    private suspend fun maybeGrantBossRelics(result: AdventureGateActionResult): AdventureGateActionResult {
        val snapshot = result.snapshot
        val phase = phaseForSnapshot(snapshot)
        if (!phase.isBoss) return result
        val pet = dao.getPet(snapshot.petId)?.let(PetMapper::toDomain) ?: return result
        val rng = Random(snapshot.rngSeed + snapshot.worldId.hashCode() + snapshot.phaseNumber + snapshot.log.size)
        val candidates = buildList {
            AdventureGateCatalog.bossRelicFor(snapshot.worldId, snapshot.phaseNumber)
                ?.takeIf { relic -> pet.inventory.none { it.id == relic.id } && rng.nextInt(100) < AdventureGateCatalog.BOSS_RELIC_DROP_CHANCE_PERCENT }
                ?.let(::add)
            AdventureGateCatalog.equipment(AdventureGateCatalog.MYSTERY_RELIC_ID)
                ?.takeIf { relic -> pet.inventory.none { it.id == relic.id } && rng.nextInt(100) < AdventureGateCatalog.MYSTERY_RELIC_DROP_CHANCE_PERCENT }
                ?.let(::add)
        }
        if (candidates.isEmpty()) return result
        val updatedInventory = candidates.fold(pet.inventory) { inventory, relic ->
            addInventoryItem(inventory, relic.toInventoryItem())
        }
        dao.savePet(PetMapper.toEntity(pet.copy(inventory = updatedInventory)))
        val logEntries = candidates.map { relic ->
            AdventureGateBattleLogEntry(
                messageKey = AdventureGateLogMessage.EQUIPMENT_DROPPED,
                itemId = relic.id,
                equipmentId = relic.id
            )
        }
        val updatedSnapshot = snapshot.copy(
            log = (snapshot.log + logEntries).takeLast(80),
            updatedAt = System.currentTimeMillis()
        )
        return result.copy(
            snapshot = updatedSnapshot,
            events = result.events + candidates.map { relic ->
                AdventureGateBattleEvent(
                    type = AdventureGateBattleEventType.EQUIPMENT_DROP,
                    itemId = relic.id,
                    equipmentId = relic.id
                )
            }
        )
    }

    private suspend fun logVictoryEvent(result: AdventureGateActionResult) {
        val snapshot = result.snapshot
        val phase = phaseForSnapshot(snapshot)
        val coins = snapshot.log.lastOrNull { it.messageKey == AdventureGateLogMessage.COINS_REWARDED }?.amount ?: 0
        val rewards = snapshot.log.filter {
            it.messageKey == AdventureGateLogMessage.POTION_REWARDED || it.messageKey == AdventureGateLogMessage.EQUIPMENT_DROPPED
        }.mapNotNull { it.itemId }
        val bossText = if (!snapshot.isNightArenaBattle() && phase.isBoss) " This boss reveal moved the dreamlight story forward." else ""
        val battleText = if (snapshot.isNightArenaBattle()) {
            "Night Arena level ${snapshot.phaseNumber}"
        } else {
            "Adventure Gate ${snapshot.worldId} phase ${snapshot.phaseNumber}"
        }
        logAdventureGateEvent(
            petId = snapshot.petId,
            type = EventType.BATTLE_WON,
            details = "The pet won $battleText, earned ${snapshot.xpAwarded} XP and $coins coins" +
                rewards.takeIf { it.isNotEmpty() }?.joinToString(prefix = ", and found ", separator = ", ").orEmpty() +
                ".$bossText"
        )
        if (rewards.contains(AdventureGateCatalog.MYSTERY_RELIC_ID)) {
            logAdventureGateEvent(
                petId = snapshot.petId,
                type = EventType.FOUND_ITEM,
                details = "The pet found the secret Nexum Heart Relic, a living gate charm that restores HP and mana each turn."
            )
        }
    }

    private suspend fun logAdventureGateEvent(
        petId: String,
        type: EventType,
        details: String
    ) {
        dao.saveEvent(
            TamaEventEntity(
                id = "ag_${petId}_${System.currentTimeMillis()}_${details.hashCode()}",
                timestamp = System.currentTimeMillis(),
                petId = petId,
                eventType = type.name,
                details = details,
                locationId = "ADVENTURE_GATE",
                npcId = null,
                statsChangeJson = null
            )
        )
    }

    private fun AdventureGateProfile.withEquippedSlot(
        slot: AdventureGateEquipmentSlot,
        equipmentId: String?
    ): AdventureGateProfile = when (slot) {
        AdventureGateEquipmentSlot.WEAPON -> copy(equippedWeaponId = equipmentId, updatedAt = System.currentTimeMillis())
        AdventureGateEquipmentSlot.SHIELD -> copy(equippedShieldId = equipmentId, updatedAt = System.currentTimeMillis())
        AdventureGateEquipmentSlot.RING -> copy(equippedRingId = equipmentId, updatedAt = System.currentTimeMillis())
        AdventureGateEquipmentSlot.RELIC -> copy(equippedRelicId = equipmentId, updatedAt = System.currentTimeMillis())
    }

    private fun isWorldIndexUnlocked(index: Int, progress: List<AdventureGateWorldProgress>): Boolean {
        if (index <= 0) return true
        val previous = AdventureGateCatalog.worlds.getOrNull(index - 1) ?: return false
        return progress.firstOrNull { it.worldId == previous.id }?.finalBossCleared == true
    }

    private fun addInventoryItem(
        inventory: List<InventoryItem>,
        item: InventoryItem,
        quantity: Int = 1
    ): List<InventoryItem> {
        val existingIndex = inventory.indexOfFirst { it.id == item.id }
        if (existingIndex < 0) return inventory + item.copy(quantity = quantity)
        return inventory.mapIndexed { index, existing ->
            if (index == existingIndex) existing.copy(quantity = existing.quantity + quantity) else existing
        }
    }

    private fun consumeInventoryItem(
        inventory: List<InventoryItem>,
        itemId: String,
        quantity: Int = 1
    ): List<InventoryItem>? {
        val existingIndex = inventory.indexOfFirst { it.id == itemId }
        if (existingIndex < 0 || inventory[existingIndex].quantity < quantity) return null
        val existing = inventory[existingIndex]
        return if (existing.quantity == quantity) {
            inventory.filterIndexed { index, _ -> index != existingIndex }
        } else {
            inventory.mapIndexed { index, item ->
                if (index == existingIndex) existing.copy(quantity = existing.quantity - quantity) else item
            }
        }
    }

    private fun AdventureGateEquipmentDefinition.toInventoryItem(): InventoryItem =
        InventoryItem(
            id = id,
            name = id,
            type = when (slot) {
                AdventureGateEquipmentSlot.WEAPON -> ItemType.WEAPON
                AdventureGateEquipmentSlot.SHIELD -> ItemType.ARMOR
                AdventureGateEquipmentSlot.RING -> ItemType.ACCESSORY
                AdventureGateEquipmentSlot.RELIC -> ItemType.ACCESSORY
            },
            quantity = 1
        )

    private fun AdventureGateProfileEntity.toDomain(): AdventureGateProfile {
        val stats = AdventureGateStats(maxHp, maxMana, attack, magic, defense, speed, accuracy, evasion)
        return AdventureGateProfile(
            petId = petId,
            level = level,
            xp = xp,
            stats = stats,
            currentHp = currentHp,
            currentMana = currentMana,
            skillPoints = skillPoints,
            purchasedSkillIds = decodeIds(purchasedSkillIdsJson, AdventureGateCatalog.starterSkillIds),
            learnedAttackIds = decodeIds(learnedAttackIdsJson, AdventureGateCatalog.startingAttackIds),
            equippedAttackIds = decodeIds(equippedAttackIdsJson, AdventureGateCatalog.startingAttackIds),
            learnedMagicIds = decodeIds(learnedMagicIdsJson, AdventureGateCatalog.startingMagicIds),
            equippedMagicIds = decodeIds(equippedMagicIdsJson, AdventureGateCatalog.startingMagicIds),
            equippedWeaponId = equippedWeaponId.takeUnless { it.isNullOrBlank() },
            equippedShieldId = equippedShieldId.takeUnless { it.isNullOrBlank() },
            equippedRingId = equippedRingId.takeUnless { it.isNullOrBlank() },
            equippedRelicId = equippedRelicId.takeUnless { it.isNullOrBlank() },
            lastRecoveryAt = lastRecoveryAt,
            updatedAt = updatedAt
        )
    }

    private fun AdventureGateProfile.toEntity(): AdventureGateProfileEntity =
        AdventureGateProfileEntity(
            petId = petId,
            level = level,
            xp = xp,
            maxHp = stats.maxHp,
            maxMana = stats.maxMana,
            attack = stats.attack,
            magic = stats.magic,
            defense = stats.defense,
            speed = stats.speed,
            accuracy = stats.accuracy,
            evasion = stats.evasion,
            currentHp = currentHp,
            currentMana = currentMana,
            skillPoints = skillPoints,
            purchasedSkillIdsJson = json.encodeToString(purchasedSkillIds),
            learnedAttackIdsJson = json.encodeToString(learnedAttackIds),
            equippedAttackIdsJson = json.encodeToString(equippedAttackIds),
            learnedMagicIdsJson = json.encodeToString(learnedMagicIds),
            equippedMagicIdsJson = json.encodeToString(equippedMagicIds),
            equippedWeaponId = equippedWeaponId,
            equippedShieldId = equippedShieldId,
            equippedRingId = equippedRingId,
            equippedRelicId = equippedRelicId,
            lastRecoveryAt = lastRecoveryAt,
            updatedAt = updatedAt
        )

    private fun AdventureGateWorldProgressEntity.toDomain(): AdventureGateWorldProgress =
        AdventureGateWorldProgress(
            petId = petId,
            worldId = worldId,
            highestClearedPhase = highestClearedPhase,
            midBossCleared = midBossCleared,
            finalBossCleared = finalBossCleared,
            updatedAt = updatedAt
        )

    private fun AdventureGateWorldProgress.toEntity(): AdventureGateWorldProgressEntity =
        AdventureGateWorldProgressEntity(
            petId = petId,
            worldId = worldId,
            highestClearedPhase = highestClearedPhase,
            midBossCleared = midBossCleared,
            finalBossCleared = finalBossCleared,
            updatedAt = updatedAt
        )

    private fun AdventureGateNightArenaRunEntity.toDomainNightArenaRun(): AdventureGateNightArenaRun =
        AdventureGateNightArenaRun(
            petId = petId,
            nightKey = nightKey,
            levels = runCatching { json.decodeFromString<List<NightArenaLevel>>(levelsJson) }.getOrDefault(emptyList()),
            clearedLevelIds = decodeIds(clearedLevelIdsJson, emptyList()),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun AdventureGateNightArenaRun.toEntity(): AdventureGateNightArenaRunEntity =
        AdventureGateNightArenaRunEntity(
            petId = petId,
            nightKey = nightKey,
            levelsJson = json.encodeToString(levels),
            clearedLevelIdsJson = json.encodeToString(clearedLevelIds),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    private fun AdventureGateBattleStateEntity.toDomainBattle(): AdventureGateBattleSnapshot? =
        runCatching { json.decodeFromString<AdventureGateBattleSnapshot>(stateJson) }.getOrNull()

    private fun AdventureGateBattleSnapshot.toEntity(): AdventureGateBattleStateEntity =
        AdventureGateBattleStateEntity(
            petId = petId,
            worldId = worldId,
            phaseNumber = phaseNumber,
            stateJson = json.encodeToString(this),
            updatedAt = updatedAt
        )

    private fun AdventureGateBattleSnapshot.isNightArenaBattle(): Boolean =
        worldId == AdventureGateCatalog.NIGHT_ARENA_WORLD_ID

    private fun phaseForSnapshot(snapshot: AdventureGateBattleSnapshot): AdventureGatePhaseDefinition =
        snapshot.phaseOverride ?: AdventureGateCatalog.world(snapshot.worldId)
            .phases[(snapshot.phaseNumber - 1).coerceIn(0, AdventureGateCatalog.PHASES_PER_WORLD - 1)]

    private fun decodeIds(raw: String, fallback: List<String>): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(fallback)
            .ifEmpty { fallback }
}
