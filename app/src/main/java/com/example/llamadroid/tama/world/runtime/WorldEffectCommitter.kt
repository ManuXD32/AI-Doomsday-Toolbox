package com.example.llamadroid.tama.world.runtime

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity

/** Commits validated living effects inside the controller's Room transaction. */
internal class WorldEffectCommitter(
    private val context: Context,
    private val database: TamaDatabase,
    private val farm: FarmRepository,
    @Volatile var engine: TamaGameEngine
) {
    suspend fun resumeActivity(intent: PendingActivityIntent) {
        if (intent.action == TamaArcadeWorldActions.LEASE_ACTION) return
        val canonical = when (intent.action) {
            "WORK" -> "startWork"
            "BOXING" -> "startTraining"
            "NORMAL_STUDY" -> "startNormalStudySession"
            "POMODORO_STUDY" -> "startPomodoroStudySession"
            "START_ACTIVITY" -> "startActivity"
            "SLEEP" -> "goToBed"
            else -> error("unknown_activity_intent")
        }
        val result = engine.completeWorldCanonicalAction(canonical, intent.arguments)
        check(result.success) { result.message }
    }

    suspend fun commit(pet: TamaPet, state: WorldState, effects: List<WorldEffectRequest>): TamaPet {
        val committedEffects = WorldFoodEffects.normalize(effects)
        val commerce = WorldCommerceEffects.canonical(context, pet, state, committedEffects)
        val relationships = database.worldDao().relationships(pet.id).associateBy { it.npcId }.toMutableMap()
        var updated = pet
        val meetingEvents = mutableListOf<Pair<EventType, String>>()
        committedEffects.filterNot(WorldCommerceEffects::isCommerceDelta).forEach { effect ->
            updated = when (effect) {
                is WorldEffectRequest.NeedDelta -> updated.copy(stats = updated.stats.withWorldNeed(effect.need, effect.delta))
                is WorldEffectRequest.MoneyDelta -> {
                    require(updated.money + effect.amount >= 0) { "insufficient_money" }
                    updated.copy(money = updated.money + effect.amount)
                }
                is WorldEffectRequest.InventoryDelta -> updated.copy(inventory = changeInventory(updated.inventory, effect.itemId, effect.quantity))
                is WorldEffectRequest.ToolUse -> updated.copy(inventory = useTool(updated.inventory, effect))
                is WorldEffectRequest.RelationshipDelta -> {
                    val before = relationships[effect.npcId] ?: TamaWorldRelationshipEntity(pet.id, effect.npcId, 0f, 0f, 0f, 0L, 0)
                    relationships[effect.npcId] = before.copy(
                        familiarity = (before.familiarity + effect.familiarity).coerceIn(0f, 100f),
                        friendship = (before.friendship + effect.friendship).coerceIn(0f, 100f),
                        trust = (before.trust + effect.trust).coerceIn(0f, 100f),
                        lastInteraction = state.lastSimulatedAt, sharedEventCount = before.sharedEventCount + 1
                    )
                    updated
                }
                else -> updated
            }
        }
        committedEffects.filterIsInstance<WorldEffectRequest.RelationshipDelta>().map { it.npcId }.distinct().forEach { npcId ->
            val meeting = WorldSocialEncounters(context, database).meet(updated, state, npcId)
            updated = meeting.pet
            meetingEvents += meeting.events
        }
        updated = updated.copy(
            currentLocationId = if (state.actor.presence == PresenceMode.WORLD) "world" else state.actor.structureId ?: "fixed_0_0",
            discoveredLocationIds = updated.discoveredLocationIds + state.knownPlaces.map { it.id }.filter { it in LegacyLocationAliases.allIds },
            relationships = relationships.mapValues { it.value.friendship.toInt() }
        )
        if (updated != pet) database.tamaDao().savePet(PetMapper.toEntity(updated))
        meetingEvents.forEach { (type, details) -> engine.logEvent(pet.id, type, details, updated.currentLocationId) }
        if (committedEffects.any { it is WorldEffectRequest.RelationshipDelta }) database.worldDao().saveRelationships(relationships.values.toList())
        committedEffects.filterIsInstance<WorldEffectRequest.FarmTransition>().forEach { applyFarm(updated.id, it, state) }
        (committedEffects.filterIsInstance<WorldEffectRequest.CanonicalAction>() + commerce).forEach { effect ->
            if (effect.action == TamaParkWorldAction.CANONICAL_ACTION) {
                // The controller invokes commit inside its Room transaction.
                // Receipt claim, locked legacy mutation, and terminal result
                // must therefore share that transaction and gate lease.
                TamaParkWorldActionExecutor(
                    database.worldActionReceiptDao(), TamaGameEngineParkWorldActionPort(engine)
                ).completeInsideTransaction(state, effect, state.lastSimulatedAt)
            } else {
                val result = engine.completeWorldCanonicalAction(effect.action, effect.arguments, worldState = state)
                check(result.success) { result.message }
            }
        }
        committedEffects.filterIsInstance<WorldEffectRequest.Activity>().forEach { effect ->
            val result = when (effect.activity) {
                PetActivity.SLEEPING -> engine.completeWorldCanonicalAction("goToBed", emptyMap())
                PetActivity.NONE -> {
                    val latest = database.tamaDao().getPet(pet.id)?.let(PetMapper::toDomain)
                    engine.completeWorldCanonicalAction(if (latest?.isSleeping == true) "wakeUp" else "stopActivity", emptyMap())
                }
                else -> engine.completeWorldCanonicalAction("startActivity", mapOf("activity" to when (effect.activity) {
                    PetActivity.STUDY -> ActivityType.STUDYING.name
                    PetActivity.WORK -> ActivityType.WORKING.name
                    PetActivity.TRAINING -> ActivityType.TRAINING.name
                    PetActivity.RELAXING -> ActivityType.RELAXING.name
                    else -> error("unknown_activity")
                }))
            }
            check(result.success) { result.message }
        }
        return database.tamaDao().getPet(pet.id)?.let(PetMapper::toDomain) ?: updated
    }

    private fun changeInventory(items: List<InventoryItem>, id: String, amount: Int): List<InventoryItem> {
        val existing = items.firstOrNull { it.id == id }
        val quantity = (existing?.quantity ?: 0).toLong() + amount
        require(quantity in 0..Int.MAX_VALUE.toLong()) { "insufficient_inventory" }
        if (quantity == 0L) return items.filterNot { it.id == id }
        val item = existing ?: WorldResourceCatalog.item(id)
        return items.filterNot { it.id == id } + item.copy(quantity = quantity.toInt())
    }

    private fun useTool(items: List<InventoryItem>, effect: WorldEffectRequest.ToolUse): List<InventoryItem> {
        require(effect.amount > 0) { "invalid_tool_use" }
        val tool = items.firstOrNull { it.type == ItemType.TOOL && it.quantity > 0 &&
            (it.id == effect.kind || it.id.startsWith("${effect.kind}_")) && (it.durability ?: 100) >= effect.amount }
            ?: error("tool_required")
        val remaining = (tool.durability ?: 100) - effect.amount
        return items.map { if (it.id == tool.id) it.copy(durability = remaining, maxDurability = it.maxDurability ?: 100) else it }
    }

    private suspend fun applyFarm(petId: String, effect: WorldEffectRequest.FarmTransition, state: WorldState) {
        val now = state.lastSimulatedAt
        val tileId = effect.plotId.removePrefix("farm_plot_").toIntOrNull() ?: error("unknown_farm_plot")
        val tile = farm.getTiles(petId).firstOrNull { it.id == tileId } ?: error("farm_plot_locked")
        val pet = database.tamaDao().getPet(petId)?.let(PetMapper::toDomain) ?: error("pet_missing")
        val changed = when (effect.action) {
            FarmActionKind.TILL_SOIL -> {
                require(tile.status == TileStatus.SOIL && tile.crop == null) { "already_tilled" }
                require(engine.consumeFarmToolDurability("hoe", 1) == 1) { "tool_required" }
                tile.copy(status = TileStatus.FARMLAND)
            }
            FarmActionKind.PLANT -> {
                require(tile.status != TileStatus.SOIL && tile.crop == null) { "empty_tilled_plot_required" }
                val seed = pet.inventory.firstOrNull { it.type == ItemType.SEED && it.quantity > 0 &&
                    (effect.cropId == null || it.id == "seed_${effect.cropId}") } ?: error("seed_required")
                val cropId = seed.id.removePrefix("seed_")
                require(cropId in CropDefinitions.CROPS && engine.consumeItem(seed, 1)) { "seed_required" }
                engine.logEvent(petId, EventType.PLANTED, context.getString(R.string.tama_event_planted, cropDisplayName(context, cropId)))
                tile.copy(crop = PlantedCrop(cropId, plantedTime = now, lastStageUpdateTime = now))
            }
            FarmActionKind.WATER, FarmActionKind.POUR_WATER -> {
                require(tile.status == TileStatus.FARMLAND) { "dry_tilled_plot_required" }
                val water = pet.inventory.firstOrNull { it.id == "water" && it.quantity > 0 }
                require(water != null || farm.consumeWater(petId, rescheduleNotifications = false)) { "water_required" }
                if (water != null) require(engine.consumeItem(water, 1)) { "water_required" }
                if (effect.action == FarmActionKind.WATER) {
                    require(engine.consumeFarmToolDurability("watering_can", 1) == 1) { "tool_required" }
                }
                engine.logEvent(petId, EventType.WATERED, context.getString(R.string.tama_event_poured_water))
                tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = now)
            }
            FarmActionKind.HARVEST_CROP -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.stage == 3 && !crop.isDecayed) { "crop_not_ready" }
                val seed = "${state.seed}:$tileId:${crop.type}:${crop.plantedTime}"
                val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
                val outcome = (java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL) / 4294967296.0
                require(engine.harvestCrop(crop, outcome.toFloat()).success) { "harvest_failed" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmActionKind.REMOVE_DEAD_CROP -> {
                val crop = tile.crop ?: error("dead_crop_required")
                require(crop.isDecayed && engine.harvestCrop(crop).success) { "dead_crop_required" }
                tile.copy(crop = null, status = TileStatus.SOIL)
            }
            FarmActionKind.FERTILIZE -> {
                val crop = tile.crop ?: error("crop_required")
                require(crop.isDecayed || (crop.stage < 3 && !crop.isFertilized)) { "fertilizer_not_needed" }
                val fertilizer = pet.inventory.firstOrNull { it.id == "fertilizer" && it.quantity > 0 }
                    ?: error("fertilizer_required")
                require(engine.consumeItem(fertilizer, 1)) { "fertilizer_required" }
                val revived = if (crop.isDecayed) crop.copy(isDecayed = false, lastStageUpdateTime = now)
                    else crop.copy(isFertilized = true)
                engine.logEvent(petId, EventType.OTHER, context.getString(if (crop.isDecayed)
                    R.string.tama_event_revived_plant else R.string.tama_event_applied_fertilizer))
                tile.copy(crop = revived)
            }
            FarmActionKind.STORE_PRODUCE -> error("canonical_action_required")
        }
        if (changed != tile) farm.saveTile(petId, changed, rescheduleNotifications = false)
    }
}
