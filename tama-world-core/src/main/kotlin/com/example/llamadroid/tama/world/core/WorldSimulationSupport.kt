package com.example.llamadroid.tama.world.core

internal fun isCriticalSurvivalNeed(needs: NeedsProjection): Boolean =
    setOf(NeedType.HEALTH, NeedType.HUNGER, NeedType.HYDRATION, NeedType.ENERGY)
        .any { NeedsSystem.isCritical(needs, it) }

internal fun directionFrom(from: WorldCoordinate, to: WorldCoordinate): Direction {
    val dx = (to.x - from.x).coerceIn(-1, 1)
    val dy = (to.y - from.y).coerceIn(-1, 1)
    return when {
        dx == 0 && dy < 0 -> Direction.NORTH
        dx > 0 && dy < 0 -> Direction.NORTH_EAST
        dx > 0 && dy == 0 -> Direction.EAST
        dx > 0 && dy > 0 -> Direction.SOUTH_EAST
        dx == 0 && dy > 0 -> Direction.SOUTH
        dx < 0 && dy > 0 -> Direction.SOUTH_WEST
        dx < 0 && dy == 0 -> Direction.WEST
        dx < 0 && dy < 0 -> Direction.NORTH_WEST
        else -> Direction.NONE
    }
}

internal fun goalForAction(action: ActionId): GoalId = when (action) {
    ActionId.EAT, ActionId.FORAGE, ActionId.HARVEST_WILD_PLANT -> GoalId.FIND_FOOD
    ActionId.DRINK -> GoalId.FIND_WATER
    ActionId.REST, ActionId.SIT, ActionId.SLEEP -> GoalId.REST
    ActionId.TALK, ActionId.GREET, ActionId.PLAY_WITH -> GoalId.SOCIALIZE
    ActionId.EXPLORE, ActionId.WANDER, ActionId.INVESTIGATE -> GoalId.EXPLORE
    ActionId.GATHER_WOOD, ActionId.CHOP_TREE -> GoalId.GATHER_WOOD
    ActionId.GATHER_HERB -> GoalId.GATHER_HERBS
    ActionId.TILL_SOIL, ActionId.PLANT, ActionId.WATER, ActionId.POUR_WATER, ActionId.FERTILIZE,
    ActionId.HARVEST_CROP, ActionId.REMOVE_DEAD_CROP, ActionId.STORE_PRODUCE -> GoalId.FARM
    ActionId.STUDY -> GoalId.STUDY
    ActionId.WORK -> GoalId.WORK
    ActionId.TRAIN_BOXING -> GoalId.TRAIN
    else -> GoalId.IDLE
}

internal fun hasUsableTool(itemId: String, requiredTool: String): Boolean {
    val normalizedItem = itemId.trim().lowercase()
    val normalizedTool = requiredTool.trim().lowercase()
    return normalizedItem == normalizedTool || normalizedItem.startsWith("${normalizedTool}_")
}

internal fun inventoryStackIs(stack: InventoryStack, kind: InventoryKind): Boolean =
    if (stack.kind == InventoryKind.OTHER) InventoryKinds.infer(stack.itemId) == kind else stack.kind == kind

internal fun inventoryKind(pet: CanonicalPetSnapshot?, itemId: String): InventoryKind? {
    val stack = pet?.inventory.orEmpty().firstOrNull { stack -> stackMatchesItem(stack, itemId) }
    return stack?.kind?.takeUnless { it == InventoryKind.OTHER }
        ?: stack?.let { InventoryKinds.infer(it.itemId) }
}

internal fun stackMatchesItem(stack: InventoryStack, requestedItemId: String): Boolean {
    if (stack.quantity <= 0) return false
    val requested = requestedItemId.trim().lowercase()
    val inferred = stack.kind.takeUnless { it == InventoryKind.OTHER }
        ?: InventoryKinds.infer(stack.itemId)
    return stack.itemId == requestedItemId ||
        (requested == "seed" && inferred == InventoryKind.SEED) ||
        (requested == "water" && inferred == InventoryKind.WATER)
}

internal fun CanonicalPetSnapshot?.isFrozenOrEgg(): Boolean = this?.cycleFrozen == true || this?.isEgg == true

internal fun policyPath(state: WorldState, decision: NavigationDecision): List<WorldCoordinate>? {
    val direction = when (decision.intent) {
        NavigationIntent.MOVE_N -> WorldCoordinate(0, -1)
        NavigationIntent.MOVE_NE -> WorldCoordinate(1, -1)
        NavigationIntent.MOVE_E -> WorldCoordinate(1, 0)
        NavigationIntent.MOVE_SE -> WorldCoordinate(1, 1)
        NavigationIntent.MOVE_S -> WorldCoordinate(0, 1)
        NavigationIntent.MOVE_SW -> WorldCoordinate(-1, 1)
        NavigationIntent.MOVE_W -> WorldCoordinate(-1, 0)
        NavigationIntent.MOVE_NW -> WorldCoordinate(-1, -1)
        NavigationIntent.WAIT, NavigationIntent.INTERACT, NavigationIntent.SEARCH -> return null
    }
    val candidate = decision.waypoint ?: (state.actor.coordinate + direction)
    if (!state.explored.contains(candidate)) return null
    if (!SafetyInstincts.validDestination(state, candidate, knownOnly = true)) return null
    return listOf(candidate)
}

internal fun boundedNavigationMemory(decision: NavigationDecision?, previous: List<Float>): List<Float> =
    decision?.nextMemory?.take(64)?.map { if (it.isFinite()) it else 0f } ?: previous

