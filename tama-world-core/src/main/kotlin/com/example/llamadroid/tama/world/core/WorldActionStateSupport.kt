package com.example.llamadroid.tama.world.core

internal fun appendRelationshipEffects(
    action: ActionId,
    npcId: String,
    effects: MutableList<WorldEffectRequest>,
    actorId: String = "pet"
) {
    val values = when (action) {
        ActionId.GREET -> Triple(1, 1, 0)
        ActionId.TALK -> Triple(2, 2, 1)
        ActionId.PLAY_WITH -> Triple(2, 4, 1)
        ActionId.HELP_NPC -> Triple(2, 3, 3)
        ActionId.GIVE_ITEM -> Triple(2, 3, 2)
        ActionId.RECEIVE_ITEM -> Triple(2, 2, 2)
        ActionId.TRADE -> Triple(2, 1, 1)
        ActionId.THANK -> Triple(1, 2, 1)
        else -> Triple(1, 1, 0)
    }
    effects += WorldEffectRequest.RelationshipDelta(
        npcId,
        values.first,
        values.second,
        values.third,
        "${action.name.lowercase()}_npc"
    )
    effects += WorldEffectRequest.Event(
        eventType = "npc_${action.name.lowercase()}",
        importance = if (action == ActionId.TALK || action == ActionId.HELP_NPC || action == ActionId.TRADE) {
            EventImportance.NOTABLE
        } else {
            EventImportance.ROUTINE
        },
        actorId = actorId,
        payload = mapOf("npcId" to npcId)
    )
}

internal fun inventoryQuantity(inventory: List<InventoryStack>, itemId: String): Int =
    inventory.filter { it.itemId == itemId }.sumOf { it.quantity.coerceAtLeast(0) }

internal fun adjustNpcInventory(state: WorldState, npcId: String, itemId: String, delta: Int): WorldState {
    if (delta == 0) return state
    return state.copy(npcs = state.npcs.map { npc ->
        if (npc.id != npcId) return@map npc
        npc.copy(inventory = adjustInventory(npc.inventory, itemId, delta))
    })
}

private fun adjustInventory(inventory: List<InventoryStack>, itemId: String, delta: Int): List<InventoryStack> {
    val index = inventory.indexOfFirst { it.itemId == itemId }
    if (index < 0) {
        return if (delta > 0) inventory + InventoryStack(itemId, delta, InventoryKinds.infer(itemId)) else inventory
    }
    val current = inventory[index]
    val nextQuantity = (current.quantity + delta).coerceAtLeast(0)
    return if (nextQuantity == 0) {
        inventory.filterIndexed { itemIndex, _ -> itemIndex != index }
    } else {
        inventory.mapIndexed { itemIndex, item -> if (itemIndex == index) item.copy(quantity = nextQuantity) else item }
    }
}

internal fun applyObjectDelta(state: WorldState, delta: WorldEffectRequest.ObjectDelta): WorldState {
    val previous = state.objects.firstOrNull { it.id == delta.objectId }
    val objectValue = WorldObject(
        id = delta.objectId,
        type = delta.type,
        x = delta.x,
        y = delta.y,
        state = delta.state,
        quantity = delta.quantity,
        itemId = delta.itemId,
        regrowthQuantity = previous?.regrowthQuantity ?: delta.quantity.coerceAtLeast(1),
        capabilities = previous?.capabilities.orEmpty(),
        ownerActorId = previous?.ownerActorId
    )
    return state.copy(objects = state.objects.filterNot { it.id == delta.objectId } + objectValue)
}

internal fun applyStructureDelta(state: WorldState, structureId: String, structureState: String): WorldState =
    state.copy(structures = state.structures.map { structure ->
        if (structure.id == structureId) structure.copy(state = structureState) else structure
    })

internal fun targetObject(state: WorldState, target: ActionTarget): WorldObject? =
    target.id?.let { id -> state.objects.firstOrNull { it.id == id }?.let { WorldGenerator.resourceState(state, it) } }
        ?: target.coordinate?.let { coordinate -> WorldGenerator.objectAt(state, coordinate.x, coordinate.y) }

internal fun targetObject(
    state: WorldState,
    targetId: String?,
    targetX: Int?,
    targetY: Int?
): WorldObject? = targetObject(
    state,
    ActionTarget(
        kind = ActionTargetKind.OBJECT,
        id = targetId,
        coordinate = targetX?.let { x -> targetY?.let { y -> WorldCoordinate(x, y) } }
    )
)

internal fun isDaytime(state: WorldState): Boolean {
    val minute = WorldClock.at(state.lastSimulatedAt, state.timezoneOffsetMinutes).minuteOfDay
    return minute in 6 * 60 until 22 * 60
}

internal fun validateStatefulAction(
    state: WorldState,
    pet: CanonicalPetSnapshot?,
    action: ActionId,
    target: ActionTarget
): String? {
    val canonical = WorldActionSemantics.canonicalAction(target.arguments)
    if (target.kind == ActionTargetKind.ACTOR && target.id !in state.npcs.map { it.id }) {
        return "counterparty_missing"
    }
    if (action in setOf(ActionId.GREET, ActionId.TALK, ActionId.PLAY_WITH, ActionId.THANK, ActionId.SAY_GOODBYE) &&
        target.kind != ActionTargetKind.ACTOR
    ) return "counterparty_required"
    if (action == ActionId.HELP_NPC && target.kind == ActionTargetKind.ACTOR && target.id !in state.npcs.map { it.id }) {
        return "counterparty_missing"
    }
    if (action in setOf(ActionId.BUY, ActionId.SELL) &&
        target.kind == ActionTargetKind.STRUCTURE && target.id !in state.structures.map { it.id }
    ) return "shop_missing"
    if (action in setOf(ActionId.ENTER_STRUCTURE, ActionId.EXIT_STRUCTURE, ActionId.ENTER_DUNGEON, ActionId.ENTER_ADVENTURE_GATE) &&
        target.id !in state.structures.map { it.id }
    ) return "structure_missing"
    if (action in setOf(ActionId.OPEN, ActionId.CLOSE, ActionId.ACTIVATE) &&
        target.id != null && target.id !in state.structures.map { it.id } && targetObject(state, target) == null
    ) return "target_missing"
    if (action == ActionId.USE && target.kind != ActionTargetKind.ITEM &&
        target.id != null && target.id !in state.structures.map { it.id } && targetObject(state, target) == null
    ) return "target_missing"
    if (action in setOf(ActionId.OPEN, ActionId.CLOSE, ActionId.ACTIVATE, ActionId.USE)) {
        interactionFailure(state, action, target)?.let { return it }
    }
    if (action == ActionId.HELP_NPC && target.kind == ActionTargetKind.OBJECT) {
        val objectValue = targetObject(state, target) ?: return "target_missing"
        if (objectValue.quantity <= 0) return "target_unavailable"
        if (objectValue.type.isResourceObject() && !objectValue.state.isResourceAvailable()) {
            return "target_unavailable"
        }
    }
    if (action == ActionId.USE_ALCHEMY && canonical == null) {
        val ingredients = WorldActionSemantics.ingredientIds(target)
        val resultItem = WorldActionSemantics.resultItemId(target)
        if (ingredients.isEmpty()) return "ingredients_required"
        if (resultItem == null) return "result_item_required"
        val missing = ingredients.firstOrNull { itemId ->
            inventoryQuantity(pet?.inventory.orEmpty(), itemId) <= 0
        }
        if (missing != null) return "ingredient_required:$missing"
    }
    if (action == ActionId.RECEIVE_ITEM || action == ActionId.TRADE) {
        val npc = target.id?.let { id -> state.npcs.firstOrNull { it.id == id } }
        if (npc == null) return "counterparty_missing"
        val requested = if (action == ActionId.RECEIVE_ITEM) {
            WorldActionSemantics.transferSelection(target)?.let { listOf(it) }.orEmpty()
        } else {
            WorldActionSemantics.tradeSelection(target)?.let { listOf(it.receive) }.orEmpty()
        }
        requested.firstOrNull { inventoryQuantity(npc.inventory, it.itemId) < it.quantity }?.let {
            return "counterparty_item_required:${it.itemId}"
        }
    }
    if (action in setOf(ActionId.TILL_SOIL, ActionId.PLANT, ActionId.WATER, ActionId.POUR_WATER,
            ActionId.FERTILIZE, ActionId.HARVEST_CROP, ActionId.REMOVE_DEAD_CROP, ActionId.STORE_PRODUCE)) {
        val canonicalStorage = action == ActionId.STORE_PRODUCE && canonical != null &&
            target.kind == ActionTargetKind.STRUCTURE
        if (action == ActionId.STORE_PRODUCE && canonical == null) return "canonical_action_required"
        if (canonicalStorage) {
            val structure = state.structures.firstOrNull { it.id == target.id }
                ?: return "structure_missing"
            if (structure.type !in setOf(StructureType.FARM, StructureType.FARM_BARN)) {
                return "wrong_structure"
            }
        }
        if (canonicalStorage) return null
        if (target.kind != ActionTargetKind.FARM_PLOT || target.id.isNullOrBlank()) return "farm_plot_required"
        val plot = farmPlotReference(state, target)
        val projectedPlot = pet?.farm?.plots?.firstOrNull { it.id == target.id }
        if (plot == null) return "farm_plot_missing"
        if (pet?.farm != null && projectedPlot == null) return "farm_projection_missing"
        if (projectedPlot != null) {
            if (action == ActionId.TILL_SOIL && state.actor.actorType == ActorType.PET && !projectedPlot.userOwned) {
                return "player_farm_required"
            }
            val farmFailure = when (action) {
                ActionId.TILL_SOIL -> if (!projectedPlot.status.equals("soil", ignoreCase = true) || projectedPlot.cropId != null) "soil_required" else null
                ActionId.PLANT -> if (projectedPlot.status.equals("soil", ignoreCase = true)) "farmland_required" else null
                ActionId.WATER, ActionId.POUR_WATER -> if (projectedPlot.status.equals("soil", ignoreCase = true) || projectedPlot.isDead) "waterable_plot_required" else null
                ActionId.FERTILIZE -> if (projectedPlot.cropId == null) "crop_required" else null
                ActionId.HARVEST_CROP -> if (!projectedPlot.isReady || projectedPlot.isDead) "ready_crop_required" else null
                ActionId.REMOVE_DEAD_CROP -> if (!projectedPlot.isDead) "dead_crop_required" else null
                else -> null
            }
            if (farmFailure != null) return farmFailure
        }
    }
    // Canonical actions are fully validated by the adapter after this
    // boundary. Self-target operations intentionally use a NONE target:
    // goToBed, wakeUp, and stopActivity carry their meaning in the action
    // itself and do not name a world object.
    if (canonical != null && target.id.isNullOrBlank() && target.coordinate == null &&
        action !in setOf(ActionId.WAIT, ActionId.SLEEP, ActionId.WAKE)
    ) return "canonical_target_required"
    return null
}

private fun farmPlotReference(state: WorldState, target: ActionTarget): FarmPlotReference? =
    target.takeIf { it.kind == ActionTargetKind.FARM_PLOT }
        ?.id
        ?.let { id -> state.farmPlots.firstOrNull { it.id == id } }

private fun interactionFailure(state: WorldState, action: ActionId, target: ActionTarget): String? {
    if (target.kind == ActionTargetKind.ITEM) return null
    val structure = target.id?.let { id -> state.structures.firstOrNull { it.id == id } }
    val objectValue = if (structure == null) targetObject(state, target) else null
    if (structure == null && objectValue == null) return "target_missing"
    val owner = structure?.ownerActorId ?: objectValue?.ownerActorId
    if (owner != null && owner != state.actor.actorId) return "target_not_owned"
    val requestedOwner = target.arguments["ownerId"]?.trim()?.takeIf { it.isNotEmpty() }
    if (requestedOwner != null && requestedOwner != state.actor.actorId) return "target_not_owned"

    val capabilities = structure?.capabilities?.takeIf { it.isNotEmpty() }
        ?: objectValue?.capabilities?.takeIf { it.isNotEmpty() }
        ?: if (structure != null) defaultStructureCapabilities(structure.type)
        else defaultObjectCapabilities(objectValue!!.type)
    if (action !in capabilities) return "capability_missing"

    val current = (structure?.state ?: objectValue?.state).orEmpty().trim().lowercase()
    return when (action) {
        ActionId.OPEN -> when (current) {
            "open", "opened" -> "already_open"
            "used", "activated" -> "target_not_openable"
            else -> null
        }
        ActionId.CLOSE -> if (current in setOf("open", "opened")) null else "target_not_open"
        ActionId.ACTIVATE -> when (current) {
            "activated", "used" -> "already_activated"
            "closed" -> "target_closed"
            else -> null
        }
        ActionId.USE -> when (current) {
            "closed" -> "target_closed"
            "used" -> "already_used"
            else -> null
        }
        else -> null
    }
}

private fun defaultStructureCapabilities(type: StructureType): Set<ActionId> = when (type) {
    StructureType.DUNGEON_A,
    StructureType.DUNGEON_B,
    StructureType.ADVENTURE_GATE -> setOf(ActionId.OPEN, ActionId.CLOSE, ActionId.USE, ActionId.ACTIVATE)
    else -> setOf(ActionId.OPEN, ActionId.CLOSE, ActionId.USE)
}

private fun defaultObjectCapabilities(type: WorldObjectType): Set<ActionId> = when (type) {
    WorldObjectType.CUSTOM,
    WorldObjectType.MARKET_STALL -> setOf(ActionId.OPEN, ActionId.CLOSE, ActionId.USE, ActionId.ACTIVATE)
    WorldObjectType.BENCH,
    WorldObjectType.DROPPED_ITEM -> setOf(ActionId.USE)
    else -> emptySet()
}

private fun WorldObjectType.isResourceObject(): Boolean = this in setOf(
    WorldObjectType.TREE,
    WorldObjectType.FALLEN_LOG,
    WorldObjectType.BUSH,
    WorldObjectType.FLOWER_MEADOW,
    WorldObjectType.BERRY_PATCH,
    WorldObjectType.MUSHROOM,
    WorldObjectType.HERB_PATCH,
    WorldObjectType.STONE,
    WorldObjectType.REED,
    WorldObjectType.CACTUS,
    WorldObjectType.SHELL,
    WorldObjectType.GLOWING_PLANT,
    WorldObjectType.POND
)

private fun String.isResourceAvailable(): Boolean = lowercase() in setOf("available", "full", "mature", "ready")
