package com.example.llamadroid.tama.world.core

/**
 * Completion effects for actions that mutate sparse world state or request a
 * canonical inventory transaction. Keeping this outside WorldSimulation keeps
 * the clock transition readable while preserving one completion boundary.
 */
internal fun appendActionSpecificEffects(
    state: WorldState,
    action: ActionId,
    target: ActionTarget,
    effects: MutableList<WorldEffectRequest>
): WorldState {
    var next = state
    when (action) {
        ActionId.LOOK, ActionId.INSPECT, ActionId.SEARCH_AREA,
        ActionId.OBSERVE_NPC, ActionId.OBSERVE_OBJECT -> {
            val payload = buildMap {
                target.id?.let { put("targetId", it) }
                target.coordinate?.let {
                    put("x", it.x.toString())
                    put("y", it.y.toString())
                }
                target.objectType?.let { put("objectType", it.name) }
            }
            effects += WorldEffectRequest.Event(
                eventType = "observation_${action.name.lowercase()}",
                importance = EventImportance.TRACE,
                actorId = state.actor.actorId,
                payload = payload
            )
        }
        ActionId.EAT -> WorldActionSemantics.selectedItemId(target)?.let {
            effects += WorldEffectRequest.InventoryDelta(it, -1, "eat")
        }
        ActionId.DRINK -> if (target.kind == ActionTargetKind.ITEM) {
            WorldActionSemantics.selectedItemId(target)?.let {
                effects += WorldEffectRequest.InventoryDelta(it, -1, "drink")
            }
        }
        ActionId.USE_MEDICINE -> WorldActionSemantics.selectedItemId(target)?.let {
            effects += WorldEffectRequest.InventoryDelta(it, -1, "medicine")
        }
        ActionId.FORAGE, ActionId.HARVEST_WILD_PLANT,
        ActionId.GATHER_WOOD, ActionId.GATHER_STONE, ActionId.GATHER_HERB,
        ActionId.PICK_UP -> {
            targetObject(state, target)?.let { objectValue ->
                effects += WorldEffectRequest.InventoryDelta(
                    objectValue.itemId ?: resourceItemId(objectValue.type),
                    objectValue.quantity,
                    "gather:${objectValue.type.name.lowercase()}"
                )
            }
        }
        ActionId.CHOP_TREE -> {
            targetObject(state, target)?.let { objectValue ->
                effects += WorldEffectRequest.InventoryDelta(
                    resourceItemId(objectValue.type),
                    objectValue.quantity,
                    "gather:${objectValue.type.name.lowercase()}"
                )
            }
            effects += WorldEffectRequest.ToolUse("axe", 1, "action:chop_tree")
        }
        ActionId.DROP -> {
            val selection = WorldActionSemantics.transferSelection(target)
            val coordinate = state.actor.coordinate
            if (selection != null) {
                val droppedId = "dropped_${state.actor.actorId}_${state.tick}_${coordinate.x}_${coordinate.y}_${selection.itemId}"
                val dropped = WorldEffectRequest.ObjectDelta(
                    objectId = droppedId,
                    itemId = selection.itemId,
                    type = WorldObjectType.DROPPED_ITEM,
                    x = coordinate.x,
                    y = coordinate.y,
                    state = "available",
                    quantity = selection.quantity,
                    reason = "drop"
                )
                effects += WorldEffectRequest.InventoryDelta(selection.itemId, -selection.quantity, "drop")
                effects += dropped
                next = applyObjectDelta(next, dropped)
                effects += WorldEffectRequest.Event(
                    eventType = "item_dropped",
                    importance = EventImportance.ROUTINE,
                    actorId = state.actor.actorId,
                    payload = mapOf(
                        "itemId" to selection.itemId,
                        "quantity" to selection.quantity.toString(),
                        "objectId" to droppedId
                    )
                )
            }
        }
        ActionId.TILL_SOIL, ActionId.PLANT, ActionId.WATER, ActionId.POUR_WATER,
        ActionId.FERTILIZE, ActionId.HARVEST_CROP,
        ActionId.REMOVE_DEAD_CROP, ActionId.STORE_PRODUCE -> target.id?.let { plotId ->
            effects += WorldEffectRequest.FarmTransition(
                plotId = plotId,
                action = farmAction(action),
                cropId = target.arguments["cropId"],
                arguments = target.arguments
            )
        }
        ActionId.GIVE_ITEM -> {
            val npcId = target.id
            val selection = WorldActionSemantics.transferSelection(target)
            val npc = npcId?.let { id -> state.npcs.firstOrNull { it.id == id } }
            if (npc != null && selection != null) {
                effects += WorldEffectRequest.InventoryDelta(selection.itemId, -selection.quantity, "give_item")
                effects += WorldEffectRequest.NpcInventoryDelta(npc.id, selection.itemId, selection.quantity, "receive_gift")
                next = adjustNpcInventory(next, npc.id, selection.itemId, selection.quantity)
                appendRelationshipEffects(action, npc.id, effects, state.actor.actorId)
            }
        }
        ActionId.RECEIVE_ITEM -> {
            val npcId = target.id
            val selection = WorldActionSemantics.transferSelection(target)
            val npc = npcId?.let { id -> state.npcs.firstOrNull { it.id == id } }
            if (npc != null && selection != null && inventoryQuantity(npc.inventory, selection.itemId) >= selection.quantity) {
                effects += WorldEffectRequest.InventoryDelta(selection.itemId, selection.quantity, "receive_item")
                effects += WorldEffectRequest.NpcInventoryDelta(npc.id, selection.itemId, -selection.quantity, "give_item")
                next = adjustNpcInventory(next, npc.id, selection.itemId, -selection.quantity)
                appendRelationshipEffects(action, npc.id, effects, state.actor.actorId)
            }
        }
        ActionId.TRADE -> {
            val npcId = target.id
            val selection = WorldActionSemantics.tradeSelection(target)
            val npc = npcId?.let { id -> state.npcs.firstOrNull { it.id == id } }
            if (npc != null && selection != null && inventoryQuantity(npc.inventory, selection.receive.itemId) >= selection.receive.quantity) {
                effects += WorldEffectRequest.InventoryDelta(selection.give.itemId, -selection.give.quantity, "trade_give")
                effects += WorldEffectRequest.InventoryDelta(selection.receive.itemId, selection.receive.quantity, "trade_receive")
                effects += WorldEffectRequest.NpcInventoryDelta(npc.id, selection.give.itemId, selection.give.quantity, "trade_receive")
                effects += WorldEffectRequest.NpcInventoryDelta(npc.id, selection.receive.itemId, -selection.receive.quantity, "trade_give")
                next = adjustNpcInventory(next, npc.id, selection.give.itemId, selection.give.quantity)
                next = adjustNpcInventory(next, npc.id, selection.receive.itemId, -selection.receive.quantity)
                appendRelationshipEffects(action, npc.id, effects, state.actor.actorId)
            }
        }
        ActionId.GREET, ActionId.TALK, ActionId.PLAY_WITH, ActionId.THANK, ActionId.SAY_GOODBYE ->
            target.id?.takeIf { id -> state.npcs.any { it.id == id } }?.let { npcId ->
                appendRelationshipEffects(action, npcId, effects, state.actor.actorId)
            }
        ActionId.HELP_NPC -> target.id?.let { targetId ->
            if (state.npcs.any { it.id == targetId }) {
                appendRelationshipEffects(action, targetId, effects, state.actor.actorId)
            } else {
                effects += WorldEffectRequest.Event(
                    eventType = "object_helped",
                    importance = EventImportance.ROUTINE,
                    actorId = state.actor.actorId,
                    payload = mapOf("objectId" to targetId)
                )
            }
        }
        ActionId.BUY -> WorldActionSemantics.purchaseSelection(target)?.let { selection ->
            effects += WorldEffectRequest.InventoryDelta(selection.itemId, selection.quantity, "buy")
            effects += WorldEffectRequest.MoneyDelta(-selection.totalCost, "buy")
            effects += WorldEffectRequest.Event(
                eventType = "item_bought",
                importance = EventImportance.ROUTINE,
                actorId = state.actor.actorId,
                payload = mapOf(
                    "itemId" to selection.itemId,
                    "quantity" to selection.quantity.toString(),
                    "total" to selection.totalCost.toString()
                )
            )
        }
        ActionId.SELL -> WorldActionSemantics.saleSelection(target)?.let { selection ->
            effects += WorldEffectRequest.InventoryDelta(selection.itemId, -selection.quantity, "sell")
            effects += WorldEffectRequest.MoneyDelta(selection.totalCost, "sell")
            effects += WorldEffectRequest.Event(
                eventType = "item_sold",
                importance = EventImportance.ROUTINE,
                actorId = state.actor.actorId,
                payload = mapOf(
                    "itemId" to selection.itemId,
                    "quantity" to selection.quantity.toString(),
                    "total" to selection.totalCost.toString()
                )
            )
        }
        // The treatment catalog, payment, and health change remain owned by
        // the canonical Android transaction. A generic world visit still
        // completes physically, then delegates exactly once at this boundary.
        ActionId.VISIT_HOSPITAL -> effects += WorldEffectRequest.CanonicalAction(
            action = "visitHospital",
            arguments = target.arguments
        )
        // Recipe selection and the success/failure result are canonical
        // Android behavior. A non-canonical request is rejected before this
        // method because the core has no recipe catalog.
        ActionId.OPEN, ActionId.CLOSE, ActionId.USE, ActionId.ACTIVATE -> {
            val requestedState = when (action) {
                ActionId.OPEN -> "open"
                ActionId.CLOSE -> "closed"
                ActionId.USE -> "used"
                else -> "activated"
            }
            val targetId = target.id
            if (targetId != null) {
                val structure = state.structures.firstOrNull { it.id == targetId }
                if (structure != null) {
                    effects += WorldEffectRequest.StructureDelta(targetId, requestedState, "action:${action.name.lowercase()}")
                    next = applyStructureDelta(next, targetId, requestedState)
                } else {
                    val objectValue = targetObject(state, target)
                    if (objectValue != null) {
                        val delta = WorldEffectRequest.ObjectDelta(
                            objectId = objectValue.id,
                            itemId = objectValue.itemId,
                            type = objectValue.type,
                            x = objectValue.x,
                            y = objectValue.y,
                            state = requestedState,
                            quantity = objectValue.quantity,
                            reason = "action:${action.name.lowercase()}"
                        )
                        effects += delta
                        next = applyObjectDelta(next, delta)
                    }
                }
                effects += WorldEffectRequest.Event(
                    eventType = "target_${action.name.lowercase()}",
                    importance = EventImportance.ROUTINE,
                    actorId = state.actor.actorId,
                    payload = mapOf("targetId" to targetId)
                )
            }
        }
        ActionId.ENTER_STRUCTURE -> target.id?.let { structureId ->
            val structure = state.structures.firstOrNull { it.id == structureId } ?: return@let
            val presence = if (structure.type == StructureType.HOME) PresenceMode.HOME else PresenceMode.INTERIOR
            next = next.copy(actor = state.actor.copy(
                x = structure.entrance.x,
                y = structure.entrance.y,
                preciseX = structure.entrance.x.toDouble(),
                preciseY = structure.entrance.y.toDouble(),
                presence = presence,
                structureId = structure.id,
                destinationX = null,
                destinationY = null,
                pendingStructureId = null
            ))
            effects += WorldEffectRequest.Event(
                eventType = "entered_structure_action",
                importance = EventImportance.ROUTINE,
                actorId = state.actor.actorId,
                payload = mapOf("structureId" to structure.id)
            )
        }
        ActionId.EXIT_STRUCTURE -> target.id?.let { structureId ->
            val structure = state.structures.firstOrNull { it.id == structureId } ?: return@let
            next = next.copy(actor = state.actor.copy(
                x = structure.entrance.x,
                y = structure.entrance.y,
                preciseX = structure.entrance.x.toDouble(),
                preciseY = structure.entrance.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                destinationX = null,
                destinationY = null,
                pendingStructureId = null
            ))
            effects += WorldEffectRequest.Event(
                eventType = "left_structure_action",
                importance = EventImportance.ROUTINE,
                actorId = state.actor.actorId,
                payload = mapOf("structureId" to structure.id)
            )
        }
        ActionId.ENTER_DUNGEON, ActionId.ENTER_ADVENTURE_GATE -> target.id?.let { structureId ->
            val structure = state.structures.firstOrNull { it.id == structureId } ?: return@let
            val eventType = if (action == ActionId.ENTER_DUNGEON) "entered_dungeon" else "entered_adventure_gate"
            effects += WorldEffectRequest.StructureDelta(structureId, "entered", "action:${action.name.lowercase()}")
            next = applyStructureDelta(next, structureId, "entered")
            effects += WorldEffectRequest.Event(
                eventType = eventType,
                importance = EventImportance.MAJOR,
                actorId = state.actor.actorId,
                payload = mapOf("structureId" to structure.id, "structureType" to structure.type.name)
            )
        }
        ActionId.SLEEP -> effects += WorldEffectRequest.Activity(PetActivity.SLEEPING, "world:sleep")
        ActionId.WAKE -> effects += WorldEffectRequest.Activity(PetActivity.NONE, "world:wake")
        ActionId.STUDY -> effects += WorldEffectRequest.Activity(PetActivity.STUDY, "world:school")
        ActionId.WORK -> effects += WorldEffectRequest.Activity(PetActivity.WORK, "world:workplace")
        ActionId.TRAIN_BOXING -> effects += WorldEffectRequest.Activity(PetActivity.TRAINING, "world:boxing")
        else -> Unit
    }
    return next
}

private fun farmAction(action: ActionId): FarmActionKind = when (action) {
    ActionId.TILL_SOIL -> FarmActionKind.TILL_SOIL
    ActionId.PLANT -> FarmActionKind.PLANT
    ActionId.WATER -> FarmActionKind.WATER
    ActionId.POUR_WATER -> FarmActionKind.POUR_WATER
    ActionId.FERTILIZE -> FarmActionKind.FERTILIZE
    ActionId.HARVEST_CROP -> FarmActionKind.HARVEST_CROP
    ActionId.REMOVE_DEAD_CROP -> FarmActionKind.REMOVE_DEAD_CROP
    ActionId.STORE_PRODUCE -> FarmActionKind.STORE_PRODUCE
    else -> error("Not a farm action: $action")
}

private fun resourceItemId(type: WorldObjectType): String = when (type) {
    WorldObjectType.BERRY_PATCH -> "berry"
    WorldObjectType.TREE, WorldObjectType.FALLEN_LOG -> "wood"
    WorldObjectType.STONE -> "stone"
    WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT -> "herb"
    WorldObjectType.MUSHROOM -> "mushroom"
    WorldObjectType.REED -> "reed"
    WorldObjectType.CACTUS -> "cactus"
    WorldObjectType.SHELL -> "shell"
    else -> type.name.lowercase()
}
