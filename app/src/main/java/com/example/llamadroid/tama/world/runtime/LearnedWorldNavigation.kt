package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.policy.LivingPolicy
import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.policy.Observation
import com.example.llamadroid.tama.world.policy.PolicySpec
import com.example.llamadroid.tama.world.policy.RecurrentState

/** Semantic inference at the navigation seam; policy output still passes core collision/safety checks. */
internal class LearnedWorldNavigation(private val policy: LivingPolicy) : NavigationPolicy {
    override fun choose(state: WorldState, pet: CanonicalPetSnapshot, goal: GoalId): NavigationDecision {
        val actor = state.actor
        val known = state.explored.toHashSet()
        val tileFeatures = FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS)
        var offset = 0
        for (dy in -PolicySpec.OBSERVATION_RADIUS..PolicySpec.OBSERVATION_RADIUS) {
            for (dx in -PolicySpec.OBSERVATION_RADIUS..PolicySpec.OBSERVATION_RADIUS) {
                val point = WorldCoordinate(actor.x + dx, actor.y + dy)
                if (point in known && state.contains(point)) {
                    val tile = WorldGenerator.tileAt(state, point.x, point.y)
                    val resource = WorldGenerator.objectAt(state, point.x, point.y)
                    val water = tile.kind in setOf(TileKind.SHALLOW_WATER, TileKind.DEEP_WATER)
                    val channels = floatArrayOf(
                        if (tile.walkable) 1f else 0f,
                        PolicySpec.encodeTerrainClassOrdinal(tile.biome.ordinal),
                        PolicySpec.encodeMovementCost(tile.movementCost),
                        if (water) 1f else 0f,
                        if (resource?.type in setOf(WorldObjectType.BERRY_PATCH, WorldObjectType.MUSHROOM)) 1f else 0f,
                        if (resource != null && resource.state != "consumed") 1f else 0f,
                        if (resource?.type == WorldObjectType.TREE) 1f else 0f,
                        if (pet.farm?.plots?.any { it.x == point.x && it.y == point.y } == true) 1f else 0f,
                        if (state.structures.any { it.contains(point) }) 1f else 0f,
                        if (state.npcs.any { it.x == point.x && it.y == point.y }) 1f else 0f,
                        if (tile.kind == TileKind.DEEP_WATER) 1f else 0f,
                        1f,
                        if (actor.destinationX == point.x && actor.destinationY == point.y) 1f else 0f
                    )
                    channels.copyInto(tileFeatures, offset)
                }
                offset += PolicySpec.TILE_CHANNELS
            }
        }
        val needs = pet.needs
        val distance = actor.destinationX?.let { x -> actor.destinationY?.let { y ->
            val target = WorldCoordinate(x, y)
            if (target in known) actor.coordinate.manhattanDistanceTo(target) / (state.width + state.height).toFloat() else 0f
        } } ?: 0f
        val scalar = floatArrayOf(needs.hunger / 100f, needs.hydration / 100f, needs.energy / 100f,
            needs.health / 100f, needs.hygiene / 100f, needs.happiness / 100f, needs.social / 100f,
            needs.curiosity / 100f, pet.inventory.sumOf { it.quantity }.coerceAtMost(100) / 100f,
            PolicySpec.encodeCurrentGoalOrdinal(goal.ordinal), distance.coerceIn(0f, 1f),
            ((state.tick / 600L) % 1440L) / 1440f, actor.stuckTicks.coerceAtMost(20) / 20f)
        val mask = BooleanArray(PolicySpec.ACTION_COUNT) { true }
        MovementIntent.entries.filter { it.wireId < 8 }.forEach { intent ->
            mask[intent.wireId] = knownWalkableMove(state, actor, intent, known)
        }
        mask[MovementIntent.INTERACT.wireId] = actor.destinationX == actor.x && actor.destinationY == actor.y
        val memory = actor.navigationMemory.takeIf { it.size == PolicySpec.RECURRENT_UNITS }
            ?.let { RecurrentState(it.toFloatArray()) } ?: RecurrentState.zero()
        val action = policy.infer(Observation(tileFeatures, scalar, mask), memory, deterministic = true)
        return NavigationDecision(NavigationIntent.valueOf(action.intent.name), nextMemory = action.nextState.values.toList())
    }

    /**
     * Keep the policy's legal movement set identical to the core's discovered-only pathfinder.
     * The explicit knownOnly flag matters for diagonal corner checks: the previous code checked
     * the destination against [known], but its two cardinal corner tiles could still be read from
     * generated terrain outside the discovered map. WorldKnowledge.observe runs after each core
     * tick, so a legal frontier step reveals the next window before the next policy decision.
     */
    private fun knownWalkableMove(
        state: WorldState,
        actor: WorldActor,
        intent: MovementIntent,
        known: Set<WorldCoordinate>
    ): Boolean {
        val destination = WorldCoordinate(actor.x + intent.dx, actor.y + intent.dy)
        if (destination !in known || !Pathfinder.walkable(state, destination, knownOnly = true)) return false
        if (intent.dx == 0 || intent.dy == 0) return true
        val horizontal = WorldCoordinate(actor.x + intent.dx, actor.y)
        val vertical = WorldCoordinate(actor.x, actor.y + intent.dy)
        return horizontal in known && vertical in known &&
            Pathfinder.walkable(state, horizontal, knownOnly = true) &&
            Pathfinder.walkable(state, vertical, knownOnly = true)
    }
}
