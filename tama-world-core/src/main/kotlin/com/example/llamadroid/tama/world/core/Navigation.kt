package com.example.llamadroid.tama.world.core

import java.util.PriorityQueue
import java.util.LinkedHashSet
import kotlin.math.abs
import kotlin.math.max

data class NavigationStep(
    val next: WorldCoordinate?,
    val path: List<WorldCoordinate>,
    val reached: Boolean,
    val blocked: Boolean
)

data class NavigationPlan(
    val path: List<WorldCoordinate>,
    val requestedTarget: WorldCoordinate,
    val reachedTarget: Boolean,
    val stoppedAtKnownFrontier: Boolean
)

@kotlinx.serialization.Serializable
enum class NavigationIntent {
    MOVE_N,
    MOVE_NE,
    MOVE_E,
    MOVE_SE,
    MOVE_S,
    MOVE_SW,
    MOVE_W,
    MOVE_NW,
    WAIT,
    INTERACT,
    SEARCH
}

/** Optional adopted-policy seam. Safety and collision remain core-owned. */
data class NavigationDecision(
    val intent: NavigationIntent,
    val waypoint: WorldCoordinate? = null,
    val nextMemory: List<Float> = emptyList()
)

fun interface NavigationPolicy {
    fun choose(state: WorldState, pet: CanonicalPetSnapshot, goal: GoalId): NavigationDecision
}

/** Deterministic A* pathfinder with corner-cutting prevention. */
object Pathfinder {
    private val directions = listOf(
        WorldCoordinate(0, -1),
        WorldCoordinate(1, -1),
        WorldCoordinate(1, 0),
        WorldCoordinate(1, 1),
        WorldCoordinate(0, 1),
        WorldCoordinate(-1, 1),
        WorldCoordinate(-1, 0),
        WorldCoordinate(-1, -1)
    )

    fun findPath(
        state: WorldState,
        start: WorldCoordinate,
        goal: WorldCoordinate,
        maxExpanded: Int = 20_000,
        knownOnly: Boolean = false
    ): List<WorldCoordinate> {
        if (!state.contains(start) || !state.contains(goal)) return emptyList()
        if (start == goal) return emptyList()
        if (!walkable(state, start, knownOnly) || !walkable(state, goal, knownOnly)) return emptyList()

        data class Node(val coordinate: WorldCoordinate, val g: Int, val f: Int, val sequence: Long)
        val open = PriorityQueue<Node>(compareBy<Node> { it.f }.thenBy { it.g }.thenBy { it.coordinate.y }.thenBy { it.coordinate.x }.thenBy { it.sequence })
        val cameFrom = HashMap<WorldCoordinate, WorldCoordinate>()
        val costSoFar = HashMap<WorldCoordinate, Int>()
        var sequence = 0L
        costSoFar[start] = 0
        open.add(Node(start, 0, heuristic(start, goal), sequence++))
        var expanded = 0

        while (open.isNotEmpty() && expanded < maxExpanded) {
            val current = open.remove()
            if (current.g != costSoFar[current.coordinate]) continue
            if (current.coordinate == goal) return reconstruct(cameFrom, start, goal)
            expanded++
            directions.forEach { offset ->
                val next = current.coordinate + offset
                if (!state.contains(next) || !walkable(state, next, knownOnly) || !canCrossCorner(state, current.coordinate, offset, knownOnly)) return@forEach
                val movement = if (offset.x != 0 && offset.y != 0) 14 else 10
                val newCost = current.g + movement * WorldGenerator.tileAt(state, next.x, next.y).movementCost
                if (newCost < (costSoFar[next] ?: Int.MAX_VALUE)) {
                    costSoFar[next] = newCost
                    cameFrom[next] = current.coordinate
                    open.add(Node(next, newCost, newCost + heuristic(next, goal), sequence++))
                }
            }
        }
        return emptyList()
    }

    fun nextStep(state: WorldState, start: WorldCoordinate, goal: WorldCoordinate, maxExpanded: Int = 20_000): NavigationStep {
        if (start == goal) return NavigationStep(null, emptyList(), reached = true, blocked = false)
        val path = findPath(state, start, goal, maxExpanded)
        return NavigationStep(
            next = path.firstOrNull(),
            path = path,
            reached = false,
            blocked = path.isEmpty()
        )
    }

    /**
     * Plans through the pet's explored map. If the requested destination is
     * beyond the known frontier, returns the safest known route to a frontier
     * tile; the caller can observe, replan, and continue on the next tick.
     */
    fun planForKnownWorld(state: WorldState, requestedTarget: WorldCoordinate, maxExpanded: Int = 20_000): NavigationPlan {
        val explored = state.explored.toHashSet()
        if (requestedTarget in explored) {
            // A known destination may be adjusted to a nearby known walkable
            // tile. Never inspect generated terrain while choosing a target
            // outside the explored set.
            val target = nearestWalkable(state, requestedTarget, knownOnly = true)
                ?: return NavigationPlan(emptyList(), requestedTarget, false, false)
            val path = findPath(state, state.actor.coordinate, target, maxExpanded, knownOnly = true)
            if (target == state.actor.coordinate || path.isNotEmpty()) {
                return NavigationPlan(path, requestedTarget, true, false)
            }
            // Legacy migration can discover two facilities without revealing the land between.
            // Explore a reachable frontier before trying the distant known island again.
        }

        val candidates = explored.asSequence()
            .filter { candidate -> walkable(state, candidate, knownOnly = true) }
            .filter { candidate -> hasUnknownNeighbour(state, candidate, explored) }
            .sortedWith(compareBy<WorldCoordinate> {
                it.manhattanDistanceTo(requestedTarget)
            }.thenBy { it.y }.thenBy { it.x })
            .toList()
        for (candidate in candidates) {
            val path = findPath(state, state.actor.coordinate, candidate, maxExpanded, knownOnly = true)
            if (candidate == state.actor.coordinate || path.isNotEmpty()) {
                return NavigationPlan(path, requestedTarget, false, true)
            }
        }
        return NavigationPlan(emptyList(), requestedTarget, false, true)
    }

    fun nearestWalkable(
        state: WorldState,
        target: WorldCoordinate,
        maxRadius: Int = 32,
        knownOnly: Boolean = false
    ): WorldCoordinate? {
        val clamped = WorldCoordinate(
            target.x.coerceIn(0, state.width - 1),
            target.y.coerceIn(0, state.height - 1)
        )
        if (walkable(state, clamped, knownOnly)) return clamped
        for (radius in 1..maxRadius) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (max(abs(dx), abs(dy)) != radius) continue
                    val candidate = clamped + WorldCoordinate(dx, dy)
                    if (state.contains(candidate) && walkable(state, candidate, knownOnly)) return candidate
                }
            }
        }
        return null
    }

    fun walkable(state: WorldState, coordinate: WorldCoordinate, knownOnly: Boolean = false): Boolean =
        state.contains(coordinate) && (!knownOnly || state.explored.contains(coordinate)) &&
            WorldGenerator.tileAt(state, coordinate.x, coordinate.y).walkable

    private fun canCrossCorner(state: WorldState, from: WorldCoordinate, offset: WorldCoordinate, knownOnly: Boolean): Boolean {
        if (offset.x == 0 || offset.y == 0) return true
        return walkable(state, from + WorldCoordinate(offset.x, 0), knownOnly) &&
            walkable(state, from + WorldCoordinate(0, offset.y), knownOnly)
    }

    private fun heuristic(from: WorldCoordinate, to: WorldCoordinate): Int {
        val dx = abs(from.x - to.x)
        val dy = abs(from.y - to.y)
        val diagonal = minOf(dx, dy)
        return diagonal * 14 + (max(dx, dy) - diagonal) * 10
    }

    private fun reconstruct(
        cameFrom: Map<WorldCoordinate, WorldCoordinate>,
        start: WorldCoordinate,
        goal: WorldCoordinate
    ): List<WorldCoordinate> {
        val reversed = ArrayList<WorldCoordinate>()
        var current = goal
        while (current != start) {
            reversed.add(current)
            current = cameFrom[current] ?: return emptyList()
        }
        reversed.reverse()
        return reversed
    }

    private fun hasUnknownNeighbour(
        state: WorldState,
        coordinate: WorldCoordinate,
        explored: Set<WorldCoordinate>
    ): Boolean = directions.any { offset ->
        val neighbour = coordinate + offset
        state.contains(neighbour) && neighbour !in explored
    }
}

object WorldKnowledge {
    private val frontierDirections = listOf(
        WorldCoordinate(0, -1),
        WorldCoordinate(1, 0),
        WorldCoordinate(0, 1),
        WorldCoordinate(-1, 0),
        WorldCoordinate(1, -1),
        WorldCoordinate(1, 1),
        WorldCoordinate(-1, 1),
        WorldCoordinate(-1, -1)
    )

    fun observe(state: WorldState, radius: Int = 6, observedAt: Long = state.tick): WorldState {
        val actor = state.actor.coordinate
        val explored = LinkedHashSet(state.explored)
        val knownResources = state.knownResources.toMutableList()
        val knownPlaces = state.knownPlaces.toMutableList()
        val knownPlaceIds = knownPlaces.map { it.id }.toHashSet()
        // Replace a seen NPC in place so the catalog remains bounded and its
        // persistence order stays stable across save/reload.
        val knownNpcIndexes = LinkedHashMap<String, Int>()
        val knownNpcs = ArrayList<KnownNpcLocation>(state.knownNpcs.size)
        state.knownNpcs.forEach { known ->
            if (knownNpcIndexes.putIfAbsent(known.npcId, knownNpcs.size) == null) knownNpcs += known
        }
        val resourceKeys = knownResources.map { "${it.kind}:${it.approximateX}:${it.approximateY}" }.toMutableSet()
        for (y in actor.y - radius..actor.y + radius) {
            for (x in actor.x - radius..actor.x + radius) {
                val coordinate = WorldCoordinate(x, y)
                if (!state.contains(coordinate) || actor.chebyshevDistanceTo(coordinate) > radius) continue
                explored.add(coordinate)
                WorldGenerator.objectAt(state, x, y)?.let { objectValue ->
                    if (objectValue.quantity > 0 && objectValue.type.isDiscoverableResource()) {
                        val key = "${objectValue.type}:$x:$y"
                        if (resourceKeys.add(key)) {
                            knownResources += KnownResourcePatch(objectValue.type, x, y, observedAt, 1f)
                        }
                    }
                }
            }
        }
        state.npcs.forEach { npc ->
            if (actor.chebyshevDistanceTo(npc.coordinate) <= radius) {
                val known = KnownNpcLocation(npc.id, npc.x, npc.y, observedAt, 1f)
                val index = knownNpcIndexes[npc.id]
                if (index == null) {
                    knownNpcIndexes[npc.id] = knownNpcs.size
                    knownNpcs += known
                } else {
                    knownNpcs[index] = known
                }
            }
        }
        state.structures.forEach { structure ->
            if (actor.chebyshevDistanceTo(structure.entrance) <= radius && knownPlaceIds.add(structure.id)) {
                knownPlaces += KnownPlace(structure.id, structure.type.name, structure.entrance.x, structure.entrance.y, observedAt)
            }
        }
        return state.copy(explored = explored.toList(), knownPlaces = knownPlaces,
            knownResources = knownResources, knownNpcs = knownNpcs)
    }

    fun nextUnknownTarget(state: WorldState, maxRadius: Int = 48): WorldCoordinate? {
        val actor = state.actor.coordinate
        val explored = state.explored.toHashSet()
        return explored.asSequence()
            .filter { it.chebyshevDistanceTo(actor) <= maxRadius }
            .filter { Pathfinder.walkable(state, it, knownOnly = true) }
            .flatMap { known -> frontierDirections.asSequence().map { known + it } }
            .filter { candidate -> state.contains(candidate) && candidate !in explored }
            .distinct()
            .sortedWith(compareBy<WorldCoordinate> {
                it.manhattanDistanceTo(actor)
            }.thenBy { it.y }.thenBy { it.x })
            .firstOrNull()
    }

    private fun WorldObjectType.isDiscoverableResource(): Boolean = when (this) {
        WorldObjectType.TREE,
        WorldObjectType.FALLEN_LOG,
        WorldObjectType.BERRY_PATCH,
        WorldObjectType.MUSHROOM,
        WorldObjectType.HERB_PATCH,
        WorldObjectType.STONE,
        WorldObjectType.REED,
        WorldObjectType.CACTUS,
        WorldObjectType.SHELL,
        WorldObjectType.GLOWING_PLANT -> true
        else -> false
    }
}
