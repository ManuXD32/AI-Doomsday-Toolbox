package com.example.llamadroid.tama.world.core

/** Small pure helpers kept separate so the main transition function stays readable. */
internal fun coarseMayAdvanceActor(state: WorldState, pet: CanonicalPetSnapshot?): Boolean {
    val actor = state.actor
    if (actor.pendingActivity?.blocksPetSimulation == true) return false
    if (actor.actionState == ActionState.BLOCKED) return false
    val timedAction = actor.actionState == ActionState.RUNNING && actor.actionTicksRemaining > 0
    if (pet == null) return timedAction
    if (pet.cycleFrozen || pet.isEgg) {
        return timedAction && actor.action == ActionId.USE &&
            actor.actionArguments["canonicalAction"]?.trim() in setOf("usePotion", "useMedicine")
    }
    if (pet.sleeping || pet.activity != PetActivity.NONE) return timedAction
    return true
}

internal fun prepareCoarseMovement(
    state: WorldState,
    pet: CanonicalPetSnapshot?,
    observations: MutableList<String>
): WorldState {
    val actor = state.actor
    if (pet == null || actor.presence != PresenceMode.WORLD || actor.path.isEmpty() ||
        pet.cycleFrozen || pet.isEgg || pet.sleeping || pet.activity != PetActivity.NONE
    ) return state

    var prepared = actor
    if (prepared.action == ActionId.RUN && !SafetyInstincts.canRun(pet.needs)) {
        prepared = prepared.copy(action = ActionId.WALK)
        observations += "coarse:run_downgraded"
    }
    val walkingCost = ActionRegistry[ActionId.WALK].energyCost
    if (prepared.action != ActionId.RUN && pet.needs.energy <= walkingCost && prepared.goal != GoalId.RETURN_HOME) {
        val home = state.structures.firstOrNull { it.type == StructureType.HOME }
        prepared = if (home == null) {
            prepared.copy(
                goal = GoalId.RETURN_HOME,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                actionTicksRemaining = 0,
                destinationX = null,
                destinationY = null,
                path = emptyList()
            )
        } else {
            prepared.copy(
                goal = GoalId.RETURN_HOME,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                actionTicksRemaining = 0,
                destinationX = home.entrance.x,
                destinationY = home.entrance.y,
                pendingStructureId = home.id,
                actionTargetId = null,
                actionTargetX = null,
                actionTargetY = null,
                actionArguments = emptyMap(),
                pendingCommand = null,
                path = emptyList()
            )
        }
        observations += "coarse:energy_return_home"
    }
    return if (prepared == actor) state else state.copy(actor = prepared)
}

internal fun coarseActorProgressed(
    before: WorldState,
    after: WorldState,
    effects: List<WorldEffectRequest>
): Boolean {
    val actorWithoutNeedChange = before.actor.copy(needs = after.actor.needs)
    return actorWithoutNeedChange != after.actor || effects.any { it !is WorldEffectRequest.NeedDelta }
}
