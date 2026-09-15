package com.example.llamadroid.tama.world.core

/**
 * Advances a durable physical FOLLOW/FOLLOW_NPC intent. The target position
 * is read from the latest observed NPC location and every step is planned
 * through the explored map, so following never grants omniscient routing.
 */
internal fun advanceFollowIntent(
    state: WorldState,
    effects: MutableList<WorldEffectRequest>,
    observations: MutableList<String>
): WorldState {
    val actor = state.actor
    val targetId = actor.followTargetId
        ?: actor.actionTargetId?.takeIf {
            actor.actionArguments[WorldActionSemantics.FOLLOW_INTENT_ARGUMENT] == "true"
        }
    val npc = targetId?.let { id -> state.npcs.firstOrNull { it.id == id } }
    if (npc == null) {
        observations += "follow_target_missing"
        return state.copy(actor = actor.copy(
            followTargetId = null,
            action = ActionId.WAIT,
            actionState = ActionState.IDLE,
            actionTicksRemaining = 0,
            goal = GoalId.IDLE,
            destinationX = null,
            destinationY = null,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            actionArguments = emptyMap(),
            path = emptyList()
        ))
    }

    val observed = state.knownNpcs.firstOrNull { it.npcId == npc.id }
    val target = when {
        npc.coordinate in state.explored -> npc.coordinate
        observed != null -> WorldCoordinate(observed.approximateX, observed.approximateY)
        else -> {
            observations += "follow_target_unobserved:${npc.id}"
            return state.copy(actor = actor.copy(
                followTargetId = npc.id,
                action = ActionId.WAIT,
                actionState = ActionState.BLOCKED,
                actionTicksRemaining = 0,
                goal = GoalId.FOLLOW_NPC,
                destinationX = null,
                destinationY = null,
                actionTargetId = npc.id,
                actionTargetX = null,
                actionTargetY = null,
                actionArguments = actor.actionArguments + (WorldActionSemantics.FOLLOW_INTENT_ARGUMENT to "true"),
                path = emptyList(),
                stuckTicks = actor.stuckTicks + 1
            ))
        }
    }

    val arguments = actor.actionArguments + (WorldActionSemantics.FOLLOW_INTENT_ARGUMENT to "true")
    if (actor.coordinate.chebyshevDistanceTo(target) <= 1) {
        observations += "follow_wait:${npc.id}"
        return state.copy(actor = actor.copy(
            followTargetId = npc.id,
            goal = GoalId.FOLLOW_NPC,
            action = ActionId.FOLLOW_NPC,
            actionState = ActionState.IDLE,
            actionTicksRemaining = 0,
            destinationX = target.x,
            destinationY = target.y,
            actionTargetId = npc.id,
            actionTargetX = target.x,
            actionTargetY = target.y,
            actionArguments = arguments,
            path = emptyList(),
            stuckTicks = 0
        ))
    }

    val approach = adjacentCandidates(target)
        .asSequence()
        .filter { it in state.explored && state.contains(it) }
        .filter { Pathfinder.walkable(state, it, knownOnly = true) }
        .mapNotNull { candidate ->
            val path = Pathfinder.findPath(state, actor.coordinate, candidate, knownOnly = true)
            if (candidate == actor.coordinate || path.isNotEmpty()) candidate to path else null
        }
        .sortedWith(compareBy<Pair<WorldCoordinate, List<WorldCoordinate>>> { it.second.size }
            .thenBy { it.first.manhattanDistanceTo(target) }
            .thenBy { it.first.y }
            .thenBy { it.first.x })
        .firstOrNull()

    val path = approach?.second.orEmpty()
    val next = path.firstOrNull()
    if (next == null) {
        // The target may have moved beyond the known frontier. Let the normal
        // frontier planner advance toward the last observed point while the
        // next observation supplies a fresh target coordinate.
        val frontierPath = Pathfinder.planForKnownWorld(state, target).path
        val frontierNext = frontierPath.firstOrNull()
        if (frontierNext == null) {
            observations += "follow_blocked:${npc.id}"
            return state.copy(actor = actor.copy(
                followTargetId = npc.id,
                goal = GoalId.FOLLOW_NPC,
                action = ActionId.FOLLOW_NPC,
                actionState = ActionState.BLOCKED,
                actionTicksRemaining = 0,
                destinationX = target.x,
                destinationY = target.y,
                actionTargetId = npc.id,
                actionTargetX = target.x,
                actionTargetY = target.y,
                actionArguments = arguments,
                path = emptyList(),
                stuckTicks = actor.stuckTicks + 1
            ))
        }
        return moveAlongFollowPath(state, npc.id, target, frontierNext, arguments, effects)
    }
    return moveAlongFollowPath(state, npc.id, target, next, arguments, effects)
}

private fun moveAlongFollowPath(
    state: WorldState,
    npcId: String,
    target: WorldCoordinate,
    next: WorldCoordinate,
    arguments: Map<String, String>,
    effects: MutableList<WorldEffectRequest>
): WorldState {
    val actor = state.actor
    if (!Pathfinder.walkable(state, next, knownOnly = true)) return state
    val direction = directionBetween(actor.coordinate, next)
    val cost = ActionRegistry[ActionId.FOLLOW_NPC].energyCost
    if (cost > 0f) {
        effects += WorldEffectRequest.NeedDelta(NeedType.ENERGY, -cost, "movement:follow_npc")
    }
    return state.copy(actor = actor.at(next).copy(
        followTargetId = npcId,
        goal = GoalId.FOLLOW_NPC,
        action = ActionId.FOLLOW_NPC,
        actionState = ActionState.RUNNING,
        actionTicksRemaining = 0,
        destinationX = target.x,
        destinationY = target.y,
        actionTargetId = npcId,
        actionTargetX = target.x,
        actionTargetY = target.y,
        actionArguments = arguments,
        path = emptyList(),
        facing = direction,
        stuckTicks = 0
    ))
}

private fun adjacentCandidates(target: WorldCoordinate): List<WorldCoordinate> = listOf(
    target + WorldCoordinate(0, -1),
    target + WorldCoordinate(1, 0),
    target + WorldCoordinate(0, 1),
    target + WorldCoordinate(-1, 0),
    target + WorldCoordinate(1, -1),
    target + WorldCoordinate(1, 1),
    target + WorldCoordinate(-1, 1),
    target + WorldCoordinate(-1, -1)
)

private fun directionBetween(from: WorldCoordinate, to: WorldCoordinate): Direction {
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
