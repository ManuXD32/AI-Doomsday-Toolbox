package com.example.llamadroid.tama.world.core

internal fun inferTargetKind(
    state: WorldState,
    targetId: String?,
    targetX: Int? = null,
    targetY: Int? = null,
    action: ActionId? = null
): ActionTargetKind {
    if (targetId != null) {
        if (state.structures.any { it.id == targetId }) return ActionTargetKind.STRUCTURE
        if (state.npcs.any { it.id == targetId }) return ActionTargetKind.ACTOR
        if (state.objects.any { it.id == targetId }) return ActionTargetKind.OBJECT
        if (targetId.startsWith("generated_") || targetId.startsWith("natural_water_")) return ActionTargetKind.OBJECT
        if (state.farmPlots.any { it.id == targetId }) return ActionTargetKind.FARM_PLOT
        if (action in setOf(ActionId.DROP, ActionId.EAT, ActionId.DRINK, ActionId.USE, ActionId.USE_MEDICINE, ActionId.BUY, ActionId.SELL, ActionId.GIVE_ITEM, ActionId.RECEIVE_ITEM)) return ActionTargetKind.ITEM
    }
    if (targetX != null && targetY != null) return ActionTargetKind.TILE
    return when (action) {
        ActionId.ENTER_STRUCTURE, ActionId.EXIT_STRUCTURE, ActionId.STUDY, ActionId.WORK,
        ActionId.TRAIN_BOXING, ActionId.VISIT_HOSPITAL, ActionId.USE_ALCHEMY,
        ActionId.ENTER_DUNGEON, ActionId.ENTER_ADVENTURE_GATE -> ActionTargetKind.STRUCTURE
        else -> ActionTargetKind.NONE
    }
}

internal fun actionTarget(
    state: WorldState,
    targetId: String?,
    targetKind: ActionTargetKind,
    targetX: Int? = null,
    targetY: Int? = null,
    arguments: Map<String, String> = emptyMap(),
    action: ActionId? = null
): ActionTarget {
    val explicitCoordinate = targetX?.let { x -> targetY?.let { y -> WorldCoordinate(x, y) } }
    val resolvedCoordinate = explicitCoordinate ?: targetId?.let { targetCoordinate(state, it) }
    val resolvedObject = targetObject(
        state,
        ActionTarget(targetKind, targetId, resolvedCoordinate, arguments = arguments)
    )
    val available = targetId == null || action == null || targetAvailable(state, targetId, action, resolvedCoordinate, arguments)
    return ActionTarget(
        kind = targetKind,
        id = targetId,
        coordinate = resolvedCoordinate,
        available = available,
        arguments = arguments,
        objectType = resolvedObject?.type
    )
}

internal fun targetCoordinate(state: WorldState, targetId: String): WorldCoordinate? =
    state.structures.firstOrNull { it.id == targetId }?.entrance
        ?: state.npcs.firstOrNull { it.id == targetId }?.coordinate
        ?: state.farmPlots.firstOrNull { it.id == targetId }?.let { WorldCoordinate(it.x, it.y) }
        ?: state.objects.firstOrNull { it.id == targetId }?.let { WorldCoordinate(it.x, it.y) }
        ?: generatedObjectCoordinate(state, targetId)

internal fun generatedObjectCoordinate(state: WorldState, targetId: String): WorldCoordinate? {
    if (!targetId.startsWith("generated_") && !targetId.startsWith("natural_water_")) return null
    val parts = targetId.split('_')
    if (parts.size < 4) return null
    val x = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
    val y = parts.last().toIntOrNull() ?: return null
    return WorldCoordinate(x, y).takeIf { state.contains(it) && it in state.explored }
}

internal fun targetAvailable(
    state: WorldState,
    targetId: String?,
    action: ActionId,
    targetCoordinate: WorldCoordinate? = null,
    arguments: Map<String, String> = emptyMap()
): Boolean {
    if (targetId == null) return action == ActionId.DRINK ||
        targetCoordinate?.takeIf { it in state.explored }?.let {
            WorldGenerator.objectAt(state, it.x, it.y) != null
        } == true
    if (state.structures.any { it.id == targetId } || state.npcs.any { it.id == targetId } || state.farmPlots.any { it.id == targetId }) return true
    if (action == ActionId.BUY && WorldActionSemantics.purchaseSelection(
            ActionTarget(ActionTargetKind.ITEM, targetId, arguments = arguments)
        ) != null
    ) return true
    if (action == ActionId.SELL && WorldActionSemantics.saleSelection(
            ActionTarget(ActionTargetKind.ITEM, targetId, arguments = arguments)
        ) != null
    ) return true
    if (state.objects.any { it.id == targetId }) {
        return state.objects.firstOrNull { it.id == targetId }?.let { WorldGenerator.resourceState(state, it) }?.let { objectValue ->
            objectValue.quantity > 0 && (
                objectValue.state.isResourceAvailable() ||
                    (action == ActionId.HELP_NPC && objectValue.type !in setOf(
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
                    ))
                )
        } == true
    }
    if (targetId.startsWith("natural_water_")) {
        val coordinate = targetCoordinate ?: generatedObjectCoordinate(state, targetId)
        return coordinate != null && coordinate in state.explored &&
            WorldGenerator.tileAt(state, coordinate.x, coordinate.y).kind == TileKind.SHALLOW_WATER
    }
    if (action == ActionId.DRINK) return true
    if (targetId.startsWith("generated_")) {
        val coordinate = targetCoordinate ?: generatedObjectCoordinate(state, targetId)
        return coordinate != null && coordinate in state.explored &&
            WorldGenerator.objectAt(state, coordinate.x, coordinate.y)
                ?.let { it.quantity > 0 && it.state.isResourceAvailable() } == true
    }
    return false
}

internal fun String.isResourceAvailable(): Boolean = lowercase() in setOf("available", "full", "mature", "ready")

