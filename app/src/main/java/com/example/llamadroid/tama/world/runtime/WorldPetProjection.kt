package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity

internal suspend fun TamaPet.worldProjection(
    state: WorldState,
    farm: FarmRepository,
    relationships: List<TamaWorldRelationshipEntity>,
    autonomy: AutonomyPolicy,
    requestedCanonicalAction: String? = null
): CanonicalPetSnapshot {
    val tools = inventory.filter { it.type == ItemType.TOOL && (it.durability ?: it.maxDurability ?: 100) > 0 }
    val aliases = listOf("hoe", "watering_can", "axe", "pickaxe").mapNotNull { family ->
        if (tools.any { it.id == family || it.id.startsWith("${family}_") }) InventoryStack(family, 1, InventoryKind.TOOL) else null
    }
    val plots = farm.getTiles(id).mapNotNull { tile ->
        val coordinate = state.farmPlots.firstOrNull { it.id == "farm_plot_${tile.id}" } ?: return@mapNotNull null
        FarmPlotSnapshot(coordinate.id, coordinate.x, coordinate.y, tile.status.name.lowercase(),
            tile.crop?.type, tile.crop?.stage == 3 && tile.crop.isDecayed.not(), tile.crop?.isDecayed == true)
    }
    val canonicalAction = requestedCanonicalAction ?: state.actor.actionArguments["canonicalAction"]
    val homeFoods = if (state.actor.presence == PresenceMode.HOME && canonicalAction in setOf("feed", "feedWithFood"))
        listOf("meal", "lettuce", "candy").map { InventoryStack(it, 1, InventoryKind.FOOD) } else emptyList()
    return CanonicalPetSnapshot(
        petId = id, needs = stats.worldNeeds(), personality = Personality.valueOf(personality.name),
        money = money.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
        inventory = inventory.filter { it.type != ItemType.TOOL || (it.durability ?: it.maxDurability ?: 100) > 0 }
            .map { item -> InventoryStack(item.id, item.quantity, when {
                item.id == "water" -> InventoryKind.WATER
                item.id == "fertilizer" -> InventoryKind.FERTILIZER
                item.type == ItemType.FOOD -> InventoryKind.FOOD
                item.type == ItemType.MEDICINE -> InventoryKind.MEDICINE
                item.type == ItemType.SEED -> InventoryKind.SEED
                item.type == ItemType.TOOL -> InventoryKind.TOOL
                else -> InventoryKind.MATERIAL
            }) } + aliases.filter { a -> inventory.none { it.id == a.itemId && (it.durability ?: it.maxDurability ?: 100) > 0 } } +
                homeFoods.filter { food -> inventory.none { it.id == food.itemId } },
        sleeping = isSleeping, cycleFrozen = cycleFrozen, isEgg = stage == GrowthStage.EGG,
        activity = when (currentActivity) {
            ActivityType.NONE -> if (isSleeping) PetActivity.SLEEPING else PetActivity.NONE
            ActivityType.WORKING -> PetActivity.WORK
            ActivityType.STUDYING -> PetActivity.STUDY
            ActivityType.TRAINING -> PetActivity.TRAINING
            ActivityType.RELAXING -> PetActivity.RELAXING
        }, autonomy = autonomy,
        relationships = relationships.associate { it.npcId to RelationshipProjection(it.familiarity.toInt(), it.friendship.toInt(), it.trust.toInt()) },
        farm = FarmSnapshot(plots)
    )
}

internal fun PetStats.worldNeeds() = NeedsProjection(hunger, hydration, energy, health, hygiene, happiness, social, curiosity)

internal fun PetStats.withWorldNeed(need: NeedType, delta: Float): PetStats {
    require(delta.isFinite())
    val next = worldNeeds().withValue(need, worldNeeds().valueOf(need) + delta)
    return copy(hunger = next.hunger, hydration = next.hydration, energy = next.energy, health = next.health,
        hygiene = next.hygiene, happiness = next.happiness, social = next.social, curiosity = next.curiosity)
}
