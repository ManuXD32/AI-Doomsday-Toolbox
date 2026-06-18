package com.example.llamadroid.tama.rpg

import kotlinx.serialization.Serializable

enum class AdventureGateElement {
    STRIKE,
    SLASH,
    FIRE,
    WATER,
    ICE,
    STORM,
    NATURE,
    STONE,
    METAL,
    LIGHT,
    SHADOW,
    ARCANE,
    BEAST
}

enum class AdventureGateSkillKind {
    ATTACK,
    MAGIC,
    HEAL,
    GUARD,
    SUMMON
}

enum class AdventureGateSkillTreePath {
    ASSAULT,
    MAGIC,
    SUPPORT,
    HEX
}

enum class AdventureGateTargetMode {
    SINGLE_ENEMY,
    SINGLE_ALLY,
    SELF
}

enum class AdventureGateTurn {
    PET,
    ENEMY,
    COMPLETE
}

enum class AdventureGateBattleEventType {
    DAMAGE,
    MISS,
    HEAL,
    MANA,
    ITEM_USED,
    ITEM_BLOCKED,
    COINS_REWARDED,
    POTION_REWARDED,
    EQUIPMENT_DROP,
    EQUIPMENT_TRIGGER,
    STATUS_APPLIED,
    STATUS_DAMAGE,
    STATUS_SKIP,
    SUMMON,
    GUARD,
    WAVE_STARTED,
    VICTORY,
    DEFEAT
}

@Serializable
data class AdventureGateStats(
    val maxHp: Int,
    val maxMana: Int,
    val attack: Int,
    val magic: Int,
    val defense: Int,
    val speed: Int,
    val accuracy: Int = 100,
    val evasion: Int = 5
)

@Serializable
data class AdventureGateProfile(
    val petId: String,
    val level: Int = 1,
    val xp: Int = 0,
    val stats: AdventureGateStats = AdventureGateCombatEngine.baseStatsForLevel(1),
    val currentHp: Int = stats.maxHp,
    val currentMana: Int = stats.maxMana,
    val skillPoints: Int = 0,
    val purchasedSkillIds: List<String> = AdventureGateCatalog.starterSkillIds,
    val learnedAttackIds: List<String> = AdventureGateCatalog.startingAttackIds,
    val equippedAttackIds: List<String> = AdventureGateCatalog.startingAttackIds,
    val learnedMagicIds: List<String> = AdventureGateCatalog.startingMagicIds,
    val equippedMagicIds: List<String> = AdventureGateCatalog.startingMagicIds,
    val equippedWeaponId: String? = null,
    val equippedShieldId: String? = null,
    val equippedRingId: String? = null,
    val equippedRelicId: String? = null,
    val educationLevel: Float = 0f,
    val exerciseLevel: Float = 0f,
    val introspectionLevel: Float = 0f,
    val lastRecoveryAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val xpToNext: Int get() = AdventureGateCombatEngine.xpToNextLevel(level)
}

enum class AdventureGateSupplyKind {
    HP,
    MANA,
    CLEANSE,
    SKILL_POINT
}

data class AdventureGateSupplyDefinition(
    val id: String,
    val kind: AdventureGateSupplyKind,
    val nameRes: Int,
    val descriptionRes: Int? = null,
    val amount: Int,
    val price: Int,
    val unlockWorldIndex: Int,
    val assetPath: String
)

data class AdventureGateRecipeDefinition(
    val id: String,
    val supplyId: String,
    val price: Int,
    val unlockWorldIndex: Int,
    val ingredientItemIds: List<String>
) {
    val ingredientCounts: Map<String, Int> = ingredientItemIds.groupingBy { it }.eachCount()
}

enum class AdventureGateEquipmentSlot {
    WEAPON,
    SHIELD,
    RING,
    RELIC
}

data class AdventureGateStatBonus(
    val maxHp: Int = 0,
    val maxMana: Int = 0,
    val attack: Int = 0,
    val magic: Int = 0,
    val defense: Int = 0,
    val speed: Int = 0,
    val accuracy: Int = 0,
    val evasion: Int = 0
)

data class AdventureGateEquipmentEffect(
    val statBonus: AdventureGateStatBonus = AdventureGateStatBonus(),
    val elementDamageElement: AdventureGateElement? = null,
    val elementDamageBonusPercent: Int = 0,
    val healingBonusPercent: Int = 0,
    val potionBonusPercent: Int = 0,
    val manaRefundOnWeakHit: Int = 0,
    val statusChanceBonusPercent: Int = 0,
    val incomingReductionPercent: Int = 0,
    val reflectDamagePercent: Int = 0,
    val statusImmunityIds: Set<String> = emptySet(),
    val forceFirstTurn: Boolean = false,
    val reviveOncePercent: Int = 0,
    val turnHpRegenPercent: Int = 0,
    val turnManaRegenPercent: Int = 0
)

data class AdventureGateEquipmentDefinition(
    val id: String,
    val slot: AdventureGateEquipmentSlot,
    val nameRes: Int,
    val descriptionRes: Int? = null,
    val price: Int,
    val unlockWorldIndex: Int,
    val assetPath: String,
    val effect: AdventureGateEquipmentEffect,
    val petWeaknesses: Set<AdventureGateElement> = emptySet(),
    val petResistances: Set<AdventureGateElement> = emptySet(),
    val bossDropWorldId: String? = null,
    val bossDropPhase: Int? = null,
    val mysteryDrop: Boolean = false
) {
    val uniqueDrop: Boolean get() = mysteryDrop || (bossDropWorldId != null && bossDropPhase != null)
}

@Serializable
data class AdventureGateWorldProgress(
    val petId: String,
    val worldId: String,
    val highestClearedPhase: Int = 0,
    val midBossCleared: Boolean = false,
    val finalBossCleared: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AdventureGateSkillDefinition(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val kind: AdventureGateSkillKind,
    val element: AdventureGateElement,
    val manaCost: Int,
    val power: Int,
    val accuracyPercent: Int = 100,
    val cooldownTurns: Int = 0,
    val unlockLevel: Int,
    val path: AdventureGateSkillTreePath = when (kind) {
        AdventureGateSkillKind.ATTACK -> AdventureGateSkillTreePath.ASSAULT
        AdventureGateSkillKind.MAGIC -> AdventureGateSkillTreePath.MAGIC
        AdventureGateSkillKind.HEAL,
        AdventureGateSkillKind.GUARD,
        AdventureGateSkillKind.SUMMON -> AdventureGateSkillTreePath.SUPPORT
    },
    val prerequisiteSkillIds: List<String> = emptyList(),
    val tier: Int = 1,
    val targetMode: AdventureGateTargetMode = AdventureGateTargetMode.SINGLE_ENEMY,
    val status: AdventureGateStatusEffect? = null,
    val statusChancePercent: Int = if (status != null) 70 else 0
)

data class AdventureGateEnemyActionDefinition(
    val id: String,
    val nameRes: Int,
    val kind: AdventureGateSkillKind,
    val element: AdventureGateElement,
    val manaCost: Int = 0,
    val power: Int = 100,
    val accuracyPercent: Int = 90,
    val cooldownTurns: Int = 0,
    val status: AdventureGateStatusEffect? = null,
    val statusChancePercent: Int = if (status != null) 65 else 0,
    val secondaryStatus: AdventureGateStatusEffect? = null,
    val secondaryStatusChancePercent: Int = if (secondaryStatus != null) 35 else 0,
    val manaDamage: Int = 0
)

data class AdventureGateEffectiveLoadout(
    val weapon: AdventureGateEquipmentDefinition? = null,
    val shield: AdventureGateEquipmentDefinition? = null,
    val ring: AdventureGateEquipmentDefinition? = null,
    val relic: AdventureGateEquipmentDefinition? = null
) {
    val equipment: List<AdventureGateEquipmentDefinition> get() = listOfNotNull(weapon, shield, ring, relic)
}

data class AdventureGateMonsterDefinition(
    val id: String,
    val nameRes: Int,
    val primaryElement: AdventureGateElement,
    val secondaryElement: AdventureGateElement? = null,
    val weaknesses: Set<AdventureGateElement>,
    val resistances: Set<AdventureGateElement>,
    val stats: AdventureGateStats,
    val xpReward: Int,
    val isBoss: Boolean = false,
    val assetBasePath: String,
    val attackActionIds: List<String> = emptyList(),
    val magicActionIds: List<String> = emptyList(),
    val specialActionId: String? = null
)

@Serializable
data class AdventureGatePhaseDefinition(
    val worldId: String,
    val phaseNumber: Int,
    val waveMonsterIds: List<List<String>>,
    val isBoss: Boolean,
    val backgroundAssetPath: String,
    val storyRes: Int,
    val bossRevealRes: Int? = null,
    val enemyLevelOverride: Int? = null,
    val xpRewardOverride: Int? = null,
    val coinRewardOverride: Int? = null,
    val potionRewardChanceOverride: Int? = null,
    val sourceDepth: Int? = null,
    val nightArenaLevelId: String? = null
)

data class AdventureGateWorldDefinition(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val mapIconAssetPath: String,
    val worldMapAssetPath: String,
    val phases: List<AdventureGatePhaseDefinition>
)

@Serializable
data class NightArenaLevel(
    val levelIndex: Int,
    val sourceAdventureDepth: Int,
    val waveMonsterIds: List<List<String>>,
    val backgroundAssetPath: String,
    val enemyLevelOverride: Int,
    val xpReward: Int,
    val coinReward: Int,
    val potionRewardChancePercent: Int,
    val seed: Long,
    val nodeX: Float,
    val nodeY: Float
) {
    val id: String get() = levelIndex.toString()
}

@Serializable
data class AdventureGateNightArenaRun(
    val petId: String,
    val nightKey: String,
    val levels: List<NightArenaLevel>,
    val clearedLevelIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AdventureGateStatusDefinition(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val iconAssetPath: String
)

@Serializable
data class AdventureGateStatusEffect(
    val id: String,
    val turnsRemaining: Int,
    val damagePerTurn: Int = 0,
    val skipTurnChancePercent: Int = 0,
    val attackMultiplierPercent: Int = 100,
    val magicMultiplierPercent: Int = 100,
    val defenseMultiplierPercent: Int = 100,
    val speedMultiplierPercent: Int = 100,
    val accuracyDelta: Int = 0,
    val evasionDelta: Int = 0,
    val incomingDamageBonusPercent: Int = 0,
    val physicalDamageTakenBonusPercent: Int = 0,
    val hpRegenPercent: Int = 0,
    val manaRegenFlat: Int = 0,
    val incomingReductionPercent: Int = 0,
    val blocksMagic: Boolean = false
)

@Serializable
data class AdventureGateCombatantState(
    val instanceId: String,
    val definitionId: String,
    val isPet: Boolean,
    val maxHp: Int,
    val hp: Int,
    val maxMana: Int,
    val mana: Int,
    val attack: Int,
    val magic: Int,
    val defense: Int,
    val speed: Int,
    val level: Int = 1,
    val accuracy: Int = 100,
    val evasion: Int = 5,
    val elements: List<AdventureGateElement>,
    val weaknesses: List<AdventureGateElement> = emptyList(),
    val resistances: List<AdventureGateElement> = emptyList(),
    val boss: Boolean = false,
    val isMinion: Boolean = false,
    val guarding: Boolean = false,
    val enraged: Boolean = false,
    val statuses: List<AdventureGateStatusEffect> = emptyList()
) {
    val isAlive: Boolean get() = hp > 0
}

@Serializable
data class AdventureGateBattleSnapshot(
    val petId: String,
    val worldId: String,
    val phaseNumber: Int,
    val waveIndex: Int,
    val turn: AdventureGateTurn,
    val pet: AdventureGateCombatantState,
    val minion: AdventureGateCombatantState? = null,
    val enemies: List<AdventureGateCombatantState>,
    val log: List<AdventureGateBattleLogEntry>,
    val isCompleted: Boolean = false,
    val isVictory: Boolean = false,
    val xpAwarded: Int = 0,
    val potionsUsed: Int = 0,
    val guardUses: Int = 0,
    val skillCooldowns: Map<String, Int> = emptyMap(),
    val actorCooldowns: Map<String, Map<String, Int>> = emptyMap(),
    val usedReviveEquipmentIds: List<String> = emptyList(),
    val actionSequence: Int = 0,
    val rngSeed: Long = System.currentTimeMillis(),
    val phaseOverride: AdventureGatePhaseDefinition? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class AdventureGateBattleLogEntry(
    val messageKey: AdventureGateLogMessage,
    val actorInstanceId: String? = null,
    val targetInstanceId: String? = null,
    val skillId: String? = null,
    val itemId: String? = null,
    val equipmentId: String? = null,
    val statusId: String? = null,
    val actorNameRes: Int? = null,
    val targetNameRes: Int? = null,
    val skillNameRes: Int? = null,
    val amount: Int = 0,
    val element: AdventureGateElement? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AdventureGateLogMessage {
    BATTLE_STARTED,
    PET_USED_SKILL,
    PET_SUMMONED,
    PET_GUARDED,
    MANA_SHELL_RECOIL,
    PET_HEALED,
    ENEMY_USED_ATTACK,
    MISSED,
    WEAK_HIT,
    RESISTED_HIT,
    ENEMY_DEFEATED,
    PET_DEFEATED,
    WAVE_STARTED,
    VICTORY,
    DEFEAT,
    LEVEL_UP,
    SKILL_UNLOCKED,
    NOT_ENOUGH_MANA,
    SKILL_ON_COOLDOWN,
    GUARD_LIMIT_REACHED,
    PET_USED_ITEM,
    POTION_LIMIT_REACHED,
    COINS_REWARDED,
    POTION_REWARDED,
    EQUIPMENT_DROPPED,
    EQUIPMENT_TRIGGERED,
    STATUS_DAMAGE,
    STATUS_SKIP
}

@Serializable
data class AdventureGateBattleEvent(
    val type: AdventureGateBattleEventType,
    val actorInstanceId: String? = null,
    val targetInstanceId: String? = null,
    val skillId: String? = null,
    val itemId: String? = null,
    val equipmentId: String? = null,
    val statusId: String? = null,
    val amount: Int = 0,
    val element: AdventureGateElement? = null
)

data class AdventureGateActionResult(
    val snapshot: AdventureGateBattleSnapshot,
    val profile: AdventureGateProfile,
    val leveledUp: Boolean = false,
    val unlockedSkillIds: List<String> = emptyList(),
    val events: List<AdventureGateBattleEvent> = emptyList()
)

enum class AdventureGateSkillPurchaseError {
    ALREADY_PURCHASED,
    LEVEL_LOCKED,
    PREREQUISITE_LOCKED,
    NOT_ENOUGH_POINTS
}

data class AdventureGateSkillPurchaseResult(
    val profile: AdventureGateProfile,
    val purchased: Boolean,
    val error: AdventureGateSkillPurchaseError? = null
)

enum class AdventureGatePurchaseError {
    NOT_ENOUGH_COINS,
    ALREADY_OWNED,
    LOCKED,
    UNKNOWN_ITEM,
    EQUIPPED,
    UNSELLABLE,
    NO_PET
}

data class AdventureGatePurchaseResult(
    val purchased: Boolean,
    val error: AdventureGatePurchaseError? = null
)

enum class AdventureGateEquipError {
    NOT_OWNED,
    WRONG_SLOT,
    ACTIVE_BATTLE,
    UNKNOWN_ITEM
}

data class AdventureGateEquipResult(
    val profile: AdventureGateProfile,
    val equipped: Boolean,
    val error: AdventureGateEquipError? = null
)

enum class AdventureGatePotionUseError {
    NOT_OWNED,
    FULL,
    LIMIT_REACHED,
    NOT_PET_TURN,
    ACTIVE_BATTLE_REQUIRED,
    NO_BAD_STATUS,
    UNKNOWN_ITEM
}

data class AdventureGatePotionUseResult(
    val result: AdventureGateActionResult? = null,
    val profile: AdventureGateProfile? = null,
    val used: Boolean,
    val error: AdventureGatePotionUseError? = null
)
