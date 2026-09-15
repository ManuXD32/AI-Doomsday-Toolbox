package com.example.llamadroid.tama.world.core

import kotlinx.serialization.Serializable

/**
 * Facts captured at the logical tick that produced a living-world event.
 *
 * The context is deliberately a value object owned by the pure core. It lets
 * a catch-up caller retain the event's own time and actor state instead of
 * reconstructing every event from the final snapshot of a batch. Defaults keep
 * old effect payloads readable; real events are annotated by WorldSimulation.
 */
@Serializable
data class WorldEventContext(
    val timestamp: Long = 0L,
    val actorCoordinate: WorldCoordinate = WorldCoordinate(0, 0),
    val biome: Biome = Biome.MEADOW,
    val beforeGoal: GoalId = GoalId.IDLE,
    val beforeAction: ActionId = ActionId.WAIT,
    val needs: NeedsProjection = NeedsProjection()
)

/**
 * Fill in missing context on events emitted during one simulation transition.
 * Existing explicit context is preserved for callers that construct a more
 * specific event (for example, an actor-aware NPC transition).
 */
internal fun annotateWorldEventContexts(
    effects: MutableList<WorldEffectRequest>,
    before: WorldState,
    after: WorldState,
    beforeNeeds: NeedsProjection,
    timestamp: Long
) {
    if (effects.none { it is WorldEffectRequest.Event && it.context == null }) return
    effects.indices.forEach { index ->
        val event = effects[index] as? WorldEffectRequest.Event ?: return@forEach
        if (event.context == null) {
            val beforeActor = eventActor(before, event.actorId, beforeNeeds)
            val afterActor = eventActor(after, event.actorId)
            val coordinate = afterActor.coordinate
            effects[index] = event.copy(context = WorldEventContext(
                timestamp = timestamp,
                actorCoordinate = coordinate,
                biome = WorldGenerator.biomeAt(after, coordinate.x, coordinate.y),
                beforeGoal = beforeActor.goal,
                beforeAction = beforeActor.action,
                needs = beforeActor.needs
            ))
        }
    }
}

private data class EventActor(
    val coordinate: WorldCoordinate,
    val goal: GoalId,
    val action: ActionId,
    val needs: NeedsProjection
)

private fun eventActor(
    state: WorldState,
    actorId: String,
    needsOverride: NeedsProjection? = null
): EventActor {
    if (state.actor.actorId == actorId || state.npcs.none { it.id == actorId }) {
        return EventActor(
            coordinate = state.actor.coordinate,
            goal = state.actor.goal,
            action = state.actor.action,
            needs = needsOverride ?: state.actor.needs
        )
    }
    val npc = state.npcs.first { it.id == actorId }
    val execution = npc.execution
    return EventActor(
        coordinate = execution?.coordinate ?: npc.coordinate,
        goal = execution?.goal ?: npc.currentGoal,
        action = execution?.action ?: npc.currentAction,
        needs = execution?.needs ?: npc.needs
    )
}
