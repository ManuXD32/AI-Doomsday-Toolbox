package com.example.llamadroid.tama.world.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val CANONICAL_ACTION_ARGUMENT = "canonicalAction"
private const val AUTONOMOUS_ACTION_ARGUMENT = "__worldAutonomous"
private const val MAX_COARSE_TRANSITIONS = 2_048L

data class WorldSimulationOptions(
    /** Living Tama persistence already owns passive need decay. */
    val decayPetNeeds: Boolean = false,
    val maxCatchUpTicks: Long = 1_200L,
    val observeRadius: Int = 6,
    val npcSimulationRadius: Int = 32,
    val navigationPolicy: NavigationPolicy? = null,
    /** NPC adapters reuse the action executor without recursively simulating NPCs. */
    val simulateNpcs: Boolean = true
)

/**
 * Pure living-world transition function. It has no clock, Room, Android, or
 * renderer dependency. Callers persist the returned state and commit returned
 * effect requests together with their canonical Tama transaction.
 */
object WorldSimulation {
    fun step(
        state: WorldState,
        command: WorldCommand? = null,
        canonicalPetSnapshot: CanonicalPetSnapshot? = null,
        now: Long = state.lastSimulatedAt + DEFAULT_TICK_MILLIS,
        options: WorldSimulationOptions = WorldSimulationOptions()
    ): SimulationResult {
        require(state.generatorVersion in WorldGenerator.supportedGeneratorVersions) {
            "Unsupported generatorVersion=${state.generatorVersion}"
        }
        val safeNow = max(now, state.lastSimulatedAt + 1L)
        val elapsedMillis = (safeNow - state.lastSimulatedAt).coerceAtLeast(0L)
        val tickAdvance = max(1L, (elapsedMillis / DEFAULT_TICK_MILLIS).coerceAtLeast(1L))
        val nextTick = state.tick + tickAdvance
        val pet = canonicalPetSnapshot
        var working = state.copy(
            tick = nextTick,
            lastSimulatedAt = safeNow,
            actor = state.actor.copy(needs = pet?.needs ?: state.actor.needs)
        )
        val effects = ArrayList<WorldEffectRequest>()
        val observations = ArrayList<String>()

        val commandResult = if (command != null) {
            applyCommand(working, command, pet, effects, observations)
        } else {
            CommandApplication(working, accepted = true)
        }
        working = commandResult.state
        if (!commandResult.accepted) {
            annotateWorldEventContexts(
                effects = effects,
                before = state,
                after = working,
                beforeNeeds = pet?.needs ?: state.actor.needs,
                timestamp = safeNow
            )
            return SimulationResult(
                state = working,
                effects = effects,
                acceptedCommand = false,
                rejectionReason = commandResult.reason,
                observations = observations
            )
        }

        val externalActivityLease = working.actor.pendingActivity?.blocksPetSimulation == true
        val stationaryCanonicalAction = working.actor.action == ActionId.USE &&
            working.actor.actionState == ActionState.RUNNING &&
            working.actor.actionArguments[CANONICAL_ACTION_ARGUMENT]?.trim() in setOf("usePotion", "useMedicine")
        // A durable external activity owns the pet action clock. Keep passive
        // need decay and NPC simulation alive, but never complete a timed
        // action, select an autonomous goal, or move the leased actor.
        if (options.decayPetNeeds && pet != null && !pet.cycleFrozen && !pet.isEgg) {
            val decayed = NeedsSystem.decay(
                needs = working.actor.needs,
                elapsedMillis = elapsedMillis,
                sleeping = pet.sleeping,
                activity = pet.activity,
                running = working.actor.action == ActionId.RUN
            )
            appendNeedDeltas(working.actor.needs, decayed, effects, "world:passive_decay")
            working = working.copy(actor = working.actor.copy(needs = decayed))
        }
        if (externalActivityLease) {
            // The receipt owner must explicitly submit or cancel before this
            // actor can leave the destination. World time and NPCs continue
            // below so the lease does not pause the living world globally.
        } else if (pet?.cycleFrozen == true || pet?.isEgg == true) {
            // Frozen pets and eggs cannot leave home or autonomously act. A
            // deliberately delegated stationary medicine operation is the one
            // exception: it must be allowed to finish so the app can apply its
            // canonical transaction, without opening a movement loophole.
            if (stationaryCanonicalAction) {
                working = advancePetAction(working, pet, effects, observations)
            }
            // NPCs still advance so the world clock remains coherent around
            // the paused pet.
        } else {
            val hadTimedPetAction = working.actor.actionState == ActionState.RUNNING && working.actor.actionTicksRemaining > 0
            working = advancePetAction(working, pet, effects, observations)
            // A stop/enter/leave command is a deliberate boundary. Do not
            // replace it with a newly selected autonomous goal in this same
            // tick; the next clock tick can resume normal planning.
            val suppressAutonomy = command is WorldCommand.Stop ||
                command is WorldCommand.ReturnHome ||
                command is WorldCommand.EnterStructure ||
                command is WorldCommand.LeaveStructure
            if (!suppressAutonomy && !hadTimedPetAction &&
                (working.actor.actionState != ActionState.RUNNING || working.actor.actionTicksRemaining <= 0)
            ) {
                working = advancePetIntent(working, pet, effects, observations, options.navigationPolicy)
            }
        }
        if (options.simulateNpcs) working = advanceNpcs(working, options.npcSimulationRadius)

        working = WorldKnowledge.observe(working, options.observeRadius, working.lastSimulatedAt)
        annotateWorldEventContexts(
            effects = effects,
            before = state,
            after = working,
            beforeNeeds = pet?.needs ?: state.actor.needs,
            timestamp = safeNow
        )
        return SimulationResult(
            state = working,
            effects = effects,
            acceptedCommand = true,
            observations = observations
        )
    }

    /**
     * Deterministic offline advancement. Short gaps replay logical ticks;
     * longer gaps use bounded coarse transitions so a week-old app does not
     * enqueue millions of frame-sized updates.
     */
    fun catchUp(
        state: WorldState,
        canonicalPetSnapshot: CanonicalPetSnapshot? = null,
        now: Long,
        options: WorldSimulationOptions = WorldSimulationOptions()
    ): SimulationResult {
        if (now <= state.lastSimulatedAt) return SimulationResult(state)
        val elapsed = now - state.lastSimulatedAt
        val logicalTicks = elapsed / DEFAULT_TICK_MILLIS
        if (logicalTicks <= options.maxCatchUpTicks) {
            var result = SimulationResult(state)
            var projectedPet = canonicalPetSnapshot
            var stoppedAtAdapterBoundary = false
            repeat(logicalTicks.toInt().coerceAtLeast(1)) { index ->
                if (stoppedAtAdapterBoundary) return@repeat
                val tickNow = min(now, state.lastSimulatedAt + (index + 1L) * DEFAULT_TICK_MILLIS)
                val next = step(result.state, canonicalPetSnapshot = projectedPet, now = tickNow, options = options)
                val adapterBoundary = next.effects.any { effect -> isCoarseAdapterBoundary(effect, projectedPet) }
                projectedPet = projectedPet?.let { projectPetEffects(it, next.effects) }
                result = SimulationResult(
                    state = next.state,
                    effects = result.effects + next.effects,
                    acceptedCommand = next.acceptedCommand,
                    rejectionReason = next.rejectionReason,
                    observations = result.observations + next.observations
                )
                stoppedAtAdapterBoundary = adapterBoundary
            }
            if (!stoppedAtAdapterBoundary && result.state.lastSimulatedAt < now) {
                val next = step(result.state, canonicalPetSnapshot = projectedPet, now = now, options = options)
                projectedPet = projectedPet?.let { projectPetEffects(it, next.effects) }
                result = SimulationResult(
                    state = next.state,
                    effects = result.effects + next.effects,
                    acceptedCommand = next.acceptedCommand,
                    rejectionReason = next.rejectionReason,
                    observations = result.observations + next.observations
                )
            }
            return result
        }
        return coarseAdvance(state, canonicalPetSnapshot, now, options)
    }

    private fun coarseAdvance(
        state: WorldState,
        pet: CanonicalPetSnapshot?,
        now: Long,
        options: WorldSimulationOptions
    ): SimulationResult {
        val elapsed = (now - state.lastSimulatedAt).coerceAtLeast(0L)
        val totalTicks = elapsed / DEFAULT_TICK_MILLIS
        val effects = ArrayList<WorldEffectRequest>()
        val observations = ArrayList<String>()
        var projectedPet = pet
        var current = state.copy(actor = state.actor.copy(needs = pet?.needs ?: state.actor.needs))
        var transitions = 0L
        var adapterBoundary = false

        // A long gap still advances the same action executor at real logical
        // boundaries, but only for a bounded number of physical transitions.
        // The remaining wall time is applied as one passive jump below. This
        // keeps a week-old world finite while preserving an exact timestamp
        // whenever an adapter-owned effect needs to be committed first.
        while (!adapterBoundary && transitions < min(totalTicks, MAX_COARSE_TRANSITIONS)) {
            if (!coarseMayAdvanceActor(current, projectedPet)) break

            val prepared = prepareCoarseMovement(current, projectedPet, observations)
            val next = step(
                state = prepared,
                canonicalPetSnapshot = projectedPet,
                now = prepared.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                options = options.copy(simulateNpcs = false)
            )
            effects += next.effects
            observations += next.observations
            adapterBoundary = next.effects.any { effect ->
                isCoarseAdapterBoundary(effect, projectedPet)
            }
            projectedPet = projectedPet?.let { projectPetEffects(it, next.effects) }
            current = next.state
            transitions++

            // Passive need decay alone does not require another physical
            // transition. A sleeping/busy or autonomy-off actor therefore
            // jumps directly to the real timestamp after this observation.
            if (!coarseActorProgressed(prepared, next.state, next.effects)) break
        }

        if (!adapterBoundary && current.lastSimulatedAt < now) {
            current = coarseAdvanceResidual(current, projectedPet, now, options, effects)
        }
        if (options.simulateNpcs) {
            val coarseTicks = ((current.lastSimulatedAt - state.lastSimulatedAt) / DEFAULT_TICK_MILLIS)
                .coerceAtLeast(1L)
            current = advanceNpcs(current, options.npcSimulationRadius, coarseTicks = coarseTicks)
        }
        current = WorldKnowledge.observe(current, options.observeRadius, current.lastSimulatedAt)
        return SimulationResult(current, effects, observations = observations)
    }

    private fun isCoarseAdapterBoundary(effect: WorldEffectRequest, pet: CanonicalPetSnapshot?): Boolean = when (effect) {
        is WorldEffectRequest.CanonicalAction,
        is WorldEffectRequest.ToolUse,
        is WorldEffectRequest.Activity -> true
        // Commerce deltas are converted to catalog-backed locked operations by
        // the Android adapter. Stop before a second replay tick can spend the
        // same projected money or inventory against an uncommitted purchase.
        is WorldEffectRequest.InventoryDelta -> effect.reason in setOf("buy", "sell", "eat")
        is WorldEffectRequest.MoneyDelta -> effect.reason in setOf("buy", "sell")
        is WorldEffectRequest.FarmTransition -> !canProjectFarmTransition(pet, effect)
        else -> false
    }

    private fun coarseAdvanceResidual(
        state: WorldState,
        pet: CanonicalPetSnapshot?,
        now: Long,
        options: WorldSimulationOptions,
        effects: MutableList<WorldEffectRequest>
    ): WorldState {
        val elapsed = (now - state.lastSimulatedAt).coerceAtLeast(0L)
        var actor = state.actor.copy(needs = pet?.needs ?: state.actor.needs)
        if (options.decayPetNeeds && pet != null && !pet.cycleFrozen && !pet.isEgg) {
            val decayed = NeedsSystem.decay(
                needs = actor.needs,
                elapsedMillis = elapsed,
                sleeping = pet.sleeping,
                activity = pet.activity,
                running = false
            )
            appendNeedDeltas(actor.needs, decayed, effects, "world:coarse_decay")
            actor = actor.copy(needs = decayed)
        }
        return state.copy(
            actor = actor,
            tick = state.tick + elapsed / DEFAULT_TICK_MILLIS,
            lastSimulatedAt = now
        )
    }

    private fun applyCommand(
        state: WorldState,
        command: WorldCommand,
        pet: CanonicalPetSnapshot?,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): CommandApplication {
        return when (command) {
        is WorldCommand.GoTo -> deliberateTravel(
            state,
            WorldCoordinate(command.x, command.y),
            command.run,
            pendingStructureId = null,
            pet = pet,
            goal = GoalId.EXPLORE,
            effects = effects,
            observations = observations
        )
        is WorldCommand.GoToStructure -> {
            val structure = state.structures.firstOrNull { it.id == command.structureId }
                ?: return CommandApplication(state, false, "unknown_structure")
            deliberateTravel(
                state,
                structure.entrance,
                run = false,
                pendingStructureId = structure.id,
                pet = pet,
                goal = if (structure.type == StructureType.HOME) GoalId.RETURN_HOME else GoalId.VISIT_INTERESTING_PLACE,
                effects = effects,
                observations = observations
            )
        }
        WorldCommand.ReturnHome -> {
            val home = state.structures.firstOrNull { it.type == StructureType.HOME }
                ?: return CommandApplication(state, false, "home_structure_missing")
            if (state.actor.presence == PresenceMode.HOME) {
                CommandApplication(state.copy(actor = state.actor.copy(goal = GoalId.RETURN_HOME, action = ActionId.WAIT)), true)
            } else {
                deliberateTravel(
                    state,
                    home.entrance,
                    run = false,
                    pendingStructureId = home.id,
                    pet = pet,
                    goal = GoalId.RETURN_HOME,
                    effects = effects,
                    observations = observations
                )
            }
        }
        is WorldCommand.EnterStructure -> enterStructure(state, command.structureId, pet, effects, observations)
        WorldCommand.LeaveStructure -> leaveStructure(state, pet, effects, observations)
        WorldCommand.Stop -> CommandApplication(
            state.copy(actor = state.actor.copy(
                goal = GoalId.IDLE,
                action = ActionId.WAIT,
                actionState = ActionState.INTERRUPTED,
                actionTicksRemaining = 0,
                destinationX = null,
                destinationY = null,
                pendingStructureId = null,
                actionTargetId = null,
                actionTargetX = null,
                actionTargetY = null,
                path = emptyList(),
                actionArguments = emptyMap(),
                pendingCommand = null,
                pendingActivity = null,
                followTargetId = null
            )),
            true
        )
        is WorldCommand.Interact -> beginAction(
            state,
            command.action,
            actionTarget(state, command.targetId, inferTargetKind(state, command.targetId), action = command.action),
            pet,
            autonomous = false,
            observations = observations
        )
        is WorldCommand.PerformAction -> {
            if (command.action in setOf(
                    ActionId.WALK,
                    ActionId.RUN,
                    ActionId.FLEE,
                    ActionId.WANDER,
                    ActionId.EXPLORE
                )) {
                val destination = command.targetX?.let { x -> command.targetY?.let { y -> WorldCoordinate(x, y) } }
                    ?: command.targetId?.let { targetCoordinate(state, it) }
                    ?: return CommandApplication(state, false, "destination_required")
                return deliberateTravel(
                    state = state,
                    destination = destination,
                    run = command.action == ActionId.RUN,
                    pendingStructureId = null,
                    pet = pet,
                    goal = if (command.action == ActionId.FLEE) GoalId.RECOVER_STUCK else GoalId.EXPLORE,
                    effects = effects,
                    observations = observations
                )
            }
            if (command.action == ActionId.RETURN_HOME) {
                val home = state.structures.firstOrNull { it.type == StructureType.HOME }
                    ?: return CommandApplication(state, false, "home_structure_missing")
                if (state.actor.presence == PresenceMode.HOME) {
                    return CommandApplication(state.copy(actor = state.actor.copy(
                        goal = GoalId.RETURN_HOME,
                        action = ActionId.WAIT,
                        actionState = ActionState.IDLE,
                        destinationX = null,
                        destinationY = null,
                        pendingStructureId = null,
                        pendingCommand = null
                    )), true)
                }
                return deliberateTravel(
                    state = state,
                    destination = home.entrance,
                    run = false,
                    pendingStructureId = home.id,
                    pet = pet,
                    goal = GoalId.RETURN_HOME,
                    effects = effects,
                    observations = observations
                )
            }
            val targetKind = inferTargetKind(state, command.targetId, command.targetX, command.targetY, command.action)
            if (command.action == ActionId.APPROACH) {
                val target = actionTarget(
                    state,
                    command.targetId,
                    targetKind,
                    command.targetX,
                    command.targetY,
                    command.arguments,
                    command.action
                )
                val coordinate = target.coordinate ?: return CommandApplication(state, false, "destination_required")
                val destination = approachCoordinate(state, coordinate) ?: coordinate
                return deliberateTravel(
                    state = state,
                    destination = destination,
                    run = false,
                    pendingStructureId = null,
                    pet = pet,
                    goal = GoalId.VISIT_INTERESTING_PLACE,
                    effects = effects,
                    observations = observations
                )
            }
            beginAction(
                state,
                command.action,
                actionTarget(state, command.targetId, targetKind, command.targetX, command.targetY, command.arguments, command.action),
                pet,
                autonomous = false,
                observations = observations,
                arguments = command.arguments,
                effects = effects
            )
        }
        }
    }

    /**
     * Deliberate travel is allowed to start from an interior. Leaving first
     * keeps the existing sleep/activity/egg gates in one place and still lets
     * an exhausted (energy=0) pet return home by walking.
     */
    private fun deliberateTravel(
        state: WorldState,
        destination: WorldCoordinate,
        run: Boolean,
        pendingStructureId: String?,
        pet: CanonicalPetSnapshot?,
        goal: GoalId,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): CommandApplication {
        var working = state
        if (working.actor.presence != PresenceMode.WORLD) {
            val left = leaveStructure(working, pet, effects, observations)
            if (!left.accepted) return left
            working = left.state
        }
        return startGoTo(working, destination, run, pendingStructureId, pet, goal)
    }

    private fun startGoTo(
        state: WorldState,
        requested: WorldCoordinate,
        run: Boolean,
        pendingStructureId: String?,
        pet: CanonicalPetSnapshot?,
        goal: GoalId = GoalId.EXPLORE
    ): CommandApplication {
        if (state.actor.presence != PresenceMode.WORLD) return CommandApplication(state, false, "world_presence_required")
        if (pet?.sleeping == true || pet?.activity?.let { it != PetActivity.NONE } == true) {
            return CommandApplication(state, false, "pet_busy")
        }
        if (pet?.cycleFrozen == true || pet?.isEgg == true) return CommandApplication(state, false, "pet_cannot_move")
        if (!state.contains(requested)) return CommandApplication(state, false, "destination_outside_world")
        // A direct command may name a still-hidden destination. The plan then
        // stops at a known frontier and the next observation reveals more map.
        // Only known destinations are adjusted for nearby walkability.
        val target = if (state.explored.contains(requested)) {
            Pathfinder.nearestWalkable(state, requested, knownOnly = true)
                ?: return CommandApplication(state, false, "destination_blocked")
        } else {
            requested
        }
        val plan = Pathfinder.planForKnownWorld(state, target)
        val path = plan.path
        if (target != state.actor.coordinate && path.isEmpty() && !plan.stoppedAtKnownFrontier) {
            return CommandApplication(state, false, "destination_unreachable")
        }
        val canRun = run && (pet == null || SafetyInstincts.canRun(pet.needs))
        val next = state.actor.copy(
            goal = goal,
            action = if (path.isEmpty()) ActionId.WAIT else if (canRun) ActionId.RUN else ActionId.WALK,
            actionState = if (path.isEmpty()) ActionState.COMPLETED else ActionState.RUNNING,
            actionTicksRemaining = 0,
            destinationX = target.x,
            destinationY = target.y,
            pendingStructureId = pendingStructureId,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            actionArguments = emptyMap(),
            pendingCommand = null,
            followTargetId = null,
            path = path,
            stuckTicks = 0
        )
        return CommandApplication(state.copy(actor = next), true)
    }

    private fun enterStructure(
        state: WorldState,
        structureId: String,
        pet: CanonicalPetSnapshot?,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): CommandApplication {
        val structure = state.structures.firstOrNull { it.id == structureId }
            ?: return CommandApplication(state, false, "unknown_structure")
        if (pet?.sleeping == true || pet?.cycleFrozen == true || pet?.isEgg == true) {
            return CommandApplication(state, false, "pet_cannot_enter")
        }
        // A direct user EnterStructure command is already an explicit opt-in.
        // Dangerous-entry permissions belong to autonomous goal selection and
        // are checked when an autonomous actor selects such a goal. Keeping
        // this boundary explicit prevents Safe mode from blocking a user who
        // deliberately opened a dungeon or Adventure Gate.
        val autonomousDangerousEntry = state.actor.goal == GoalId.ENTER_DUNGEON
        if (autonomousDangerousEntry && !SafetyInstincts.canEnterStructure(pet ?: CanonicalPetSnapshot(), structure)) {
            return CommandApplication(state, false, "structure_forbidden")
        }
        if (state.actor.presence == PresenceMode.HOME && structure.type == StructureType.HOME) return CommandApplication(state, true)
        if (state.actor.presence != PresenceMode.WORLD) return CommandApplication(state, false, "world_presence_required")
        val distance = state.actor.coordinate.chebyshevDistanceTo(structure.entrance)
        if (distance > 1) {
            // Preserve the explicit nature of this command through the
            // persisted route; arrival must not be reinterpreted as an
            // autonomous dangerous-entry attempt.
            return startGoTo(
                state,
                structure.entrance,
                false,
                structure.id,
                pet,
                goal = GoalId.VISIT_INTERESTING_PLACE
            )
        }
        val presence = if (structure.type == StructureType.HOME) PresenceMode.HOME else PresenceMode.INTERIOR
        val retainedAutonomousGoal = state.actor.goal.takeIf {
            it in setOf(GoalId.STUDY, GoalId.WORK, GoalId.PLAY, GoalId.TRAIN)
        }
        val actor = state.actor.copy(
            presence = presence,
            structureId = structure.id,
            x = structure.entrance.x,
            y = structure.entrance.y,
            preciseX = structure.entrance.x.toDouble(),
            preciseY = structure.entrance.y.toDouble(),
            // Preserve an autonomous activity goal so the next indoor tick
            // can start its declared action. Explicit structure visits settle
            // at IDLE and remain available to the user until another command.
            goal = if (structure.type == StructureType.HOME) GoalId.RETURN_HOME else retainedAutonomousGoal ?: GoalId.IDLE,
            action = ActionId.ENTER_STRUCTURE,
            actionState = ActionState.COMPLETED,
            actionTicksRemaining = 0,
            destinationX = null,
            destinationY = null,
            pendingStructureId = null,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            actionArguments = emptyMap(),
            pendingCommand = null,
            followTargetId = null,
            path = emptyList()
        )
        effects += WorldEffectRequest.Event(
            eventType = "entered_structure",
            importance = if (structure.type == StructureType.HOME) EventImportance.ROUTINE else EventImportance.NOTABLE,
            actorId = state.actor.actorId,
            payload = mapOf("structureId" to structure.id, "structureType" to structure.type.name)
        )
        observations += "entered:${structure.id}"
        return CommandApplication(state.copy(actor = actor), true)
    }

    private fun leaveStructure(
        state: WorldState,
        pet: CanonicalPetSnapshot?,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): CommandApplication {
        if (state.actor.presence == PresenceMode.WORLD) return CommandApplication(state, true)
        if (state.actor.actionState == ActionState.RUNNING && state.actor.actionTicksRemaining > 0) {
            return CommandApplication(state, false, "action_in_progress")
        }
        if (!SafetyInstincts.canLeaveHome(pet ?: CanonicalPetSnapshot())) return CommandApplication(state, false, "pet_cannot_leave")
        val structure = state.actor.structureId?.let { id -> state.structures.firstOrNull { it.id == id } }
            ?: state.structures.firstOrNull { it.type == StructureType.HOME }
            ?: return CommandApplication(state, false, "structure_missing")
        val spawn = structure.entrance
        val actor = state.actor.copy(
            x = spawn.x,
            y = spawn.y,
            preciseX = spawn.x.toDouble(),
            preciseY = spawn.y.toDouble(),
            presence = PresenceMode.WORLD,
            structureId = null,
            goal = GoalId.IDLE,
            action = ActionId.EXIT_STRUCTURE,
            actionState = ActionState.COMPLETED,
            actionTicksRemaining = 0,
            destinationX = null,
            destinationY = null,
            pendingStructureId = null,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            actionArguments = emptyMap(),
            pendingCommand = null,
            path = emptyList(),
            followTargetId = null
        )
        effects += WorldEffectRequest.Event(
            eventType = "left_structure",
            importance = EventImportance.ROUTINE,
            actorId = state.actor.actorId,
            payload = mapOf("structureId" to structure.id)
        )
        observations += "left:${structure.id}"
        return CommandApplication(state.copy(actor = actor), true)
    }

    private fun beginAction(
        state: WorldState,
        action: ActionId,
        target: ActionTarget,
        pet: CanonicalPetSnapshot?,
        autonomous: Boolean,
        observations: MutableList<String>,
        arguments: Map<String, String> = target.arguments,
        effects: MutableList<WorldEffectRequest> = ArrayList()
    ): CommandApplication {
        val baseArguments = arguments - AUTONOMOUS_ACTION_ARGUMENT
        val canonicalAction = baseArguments[CANONICAL_ACTION_ARGUMENT]?.trim()
        val stationaryCanonicalUse = action == ActionId.USE &&
            canonicalAction in setOf("usePotion", "useMedicine")
        if (pet?.sleeping == true && action != ActionId.WAKE ||
            ((pet?.cycleFrozen == true || pet?.isEgg == true) && !stationaryCanonicalUse)
        ) {
            return CommandApplication(state, false, "pet_cannot_act")
        }
        if (state.actor.actionState == ActionState.RUNNING && state.actor.actionTicksRemaining > 0) {
            return CommandApplication(state, false, "action_in_progress")
        }
        val definition = ActionRegistry[action]
        val effectiveArguments = if (autonomous) {
            baseArguments + (AUTONOMOUS_ACTION_ARGUMENT to "true")
        } else {
            baseArguments
        }
        val normalizedTarget = target.copy(arguments = effectiveArguments)
        val targetStructure = target.id?.let { id -> state.structures.firstOrNull { it.id == id } }
        val context = ActionContext(
            actorType = state.actor.actorType,
            actorCoordinate = state.actor.coordinate,
            target = normalizedTarget,
            presence = state.actor.presence,
            structureType = targetStructure?.type ?: state.actor.structureId?.let { id -> state.structures.firstOrNull { it.id == id }?.type },
            atFarm = isAtFarm(state, normalizedTarget) || isFarmTarget(state, normalizedTarget),
            hasItem = { item -> pet?.inventory.orEmpty().any { stack -> stackMatchesItem(stack, item) } },
            itemKind = { item -> inventoryKind(pet, item) },
            hasTool = { tool -> pet?.inventory.orEmpty().any { it.quantity > 0 && hasUsableTool(it.itemId, tool) } },
            money = pet?.money ?: 0,
            energy = pet?.needs?.energy ?: state.actor.needs.energy,
            health = pet?.needs?.health ?: state.actor.needs.health,
            sleeping = pet?.sleeping == true,
            userOwnedCrop = autonomous && action == ActionId.HARVEST_CROP &&
                pet?.farm?.plots?.firstOrNull { it.id == target.id }?.userOwned == true,
            // Explicit user commands are intentional. Autonomy is enforced
            // only for autonomous planning below.
            autonomy = if (autonomous) pet?.autonomy ?: AutonomyPolicy() else AutonomyPolicy(level = AutonomyLevel.FULL),
            itemQuantity = { item -> pet?.inventory.orEmpty().firstOrNull { stackMatchesItem(it, item) }?.quantity ?: 0 },
            isDaytime = isDaytime(state),
            actorId = state.actor.actorId,
            structureId = state.actor.structureId
        )
        val statefulFailure = validateStatefulAction(state, pet, action, normalizedTarget)
        if (statefulFailure != null) return CommandApplication(state, false, statefulFailure)
        if (ActionPrerequisite.WALKABLE_TARGET in definition.prerequisites &&
            normalizedTarget.coordinate != null &&
            !Pathfinder.walkable(state, normalizedTarget.coordinate, knownOnly = true)
        ) {
            return CommandApplication(state, false, "target_not_walkable")
        }
        if (ActionPrerequisite.AT_STRUCTURE in definition.prerequisites &&
            normalizedTarget.kind == ActionTargetKind.STRUCTURE &&
            state.actor.presence == PresenceMode.INTERIOR &&
            normalizedTarget.id != null && normalizedTarget.id != state.actor.structureId
        ) {
            return CommandApplication(state, false, "wrong_structure")
        }
        val requiresApproach = normalizedTarget.coordinate != null &&
            state.actor.coordinate.chebyshevDistanceTo(normalizedTarget.coordinate) > 1 &&
            (ActionPrerequisite.ADJACENT_TARGET in definition.prerequisites ||
                WorldActionSemantics.requiresPhysicalAdjacency(action, normalizedTarget))
        // Validate the target and all non-distance prerequisites before
        // queuing movement. The final validation runs again on arrival.
        val validation = if (requiresApproach) {
            ActionExecutor.validate(action, context.copy(actorCoordinate = normalizedTarget.coordinate!!))
        } else {
            ActionExecutor.validate(action, context)
        }
        if (!validation.valid) return CommandApplication(state, false, validation.reason)
        if (requiresApproach) {
            return queueApproach(
                state = state,
                action = action,
                target = normalizedTarget,
                pet = pet,
                autonomous = autonomous,
                arguments = effectiveArguments,
                effects = effects,
                observations = observations
            )
        }
        if (action in setOf(ActionId.FOLLOW, ActionId.FOLLOW_NPC)) {
            val followTarget = normalizedTarget.id
                ?: return CommandApplication(state, false, "counterparty_required")
            val actor = state.actor.copy(
                action = action,
                actionState = ActionState.IDLE,
                actionTicksRemaining = 0,
                actionTargetId = followTarget,
                actionTargetX = normalizedTarget.coordinate?.x,
                actionTargetY = normalizedTarget.coordinate?.y,
                actionArguments = effectiveArguments + (WorldActionSemantics.FOLLOW_INTENT_ARGUMENT to "true"),
                pendingCommand = null,
                followTargetId = followTarget,
                goal = GoalId.FOLLOW_NPC
            )
            observations += "follow_started:$followTarget"
            return CommandApplication(state.copy(actor = actor), true)
        }
        val actor = state.actor.copy(
            action = action,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = definition.durationTicks,
            actionTargetId = normalizedTarget.id,
            actionTargetX = normalizedTarget.coordinate?.x,
            actionTargetY = normalizedTarget.coordinate?.y,
            actionArguments = effectiveArguments,
            pendingCommand = null,
            followTargetId = null,
            goal = state.actor.goal.takeUnless { it == GoalId.IDLE } ?: goalForAction(action)
        )
        observations += "action_started:${action.name}"
        return CommandApplication(state.copy(actor = actor), true)
    }

    /** Queue an adjacency-dependent action and retain its exact arguments. */
    private fun queueApproach(
        state: WorldState,
        action: ActionId,
        target: ActionTarget,
        pet: CanonicalPetSnapshot?,
        autonomous: Boolean,
        arguments: Map<String, String>,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): CommandApplication {
        var working = state
        if (working.actor.presence != PresenceMode.WORLD) {
            val left = leaveStructure(working, pet, effects, observations)
            if (!left.accepted) return left
            working = left.state
        }
        val targetCoordinate = target.coordinate
            ?: return CommandApplication(working, false, "adjacent_target_required")
        val knownApproach = if (targetCoordinate in working.explored) {
            approachCoordinate(working, targetCoordinate)
        } else {
            null
        }
        val requestedCoordinate = knownApproach ?: targetCoordinate
        val plan = Pathfinder.planForKnownWorld(working, requestedCoordinate)
        val path = plan.path
        if (working.actor.coordinate.chebyshevDistanceTo(targetCoordinate) > 1 &&
            path.isEmpty() && !plan.stoppedAtKnownFrontier) {
            return CommandApplication(working, false, "destination_unreachable")
        }
        val pending = PendingWorldCommand(
            action = action,
            targetId = target.id,
            // IDs are resolved again when the action starts, so a moving NPC
            // or respawned object cannot leave a stale coordinate in storage.
            targetX = if (target.id == null) targetCoordinate.x else null,
            targetY = if (target.id == null) targetCoordinate.y else null,
            arguments = arguments
        )
        val actor = working.actor.copy(
            action = if (path.isEmpty()) ActionId.WAIT else ActionId.APPROACH,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 0,
            destinationX = targetCoordinate.x,
            destinationY = targetCoordinate.y,
            actionTargetId = target.id,
            actionTargetX = targetCoordinate.x,
            actionTargetY = targetCoordinate.y,
            actionArguments = arguments,
            pendingCommand = pending,
            followTargetId = target.id.takeIf { action in setOf(ActionId.FOLLOW, ActionId.FOLLOW_NPC) },
            pendingActivity = working.actor.pendingActivity,
            path = path,
            goal = working.actor.goal.takeUnless { it == GoalId.IDLE } ?: goalForAction(action),
            stuckTicks = 0
        )
        observations += if (autonomous) "approach_started:${action.name}" else "approach_queued:${action.name}"
        return CommandApplication(working.copy(actor = actor), true)
    }

    /** Select the shortest known walkable tile adjacent to a target. */
    private fun approachCoordinate(state: WorldState, target: WorldCoordinate): WorldCoordinate? {
        val offsets = listOf(
            WorldCoordinate(0, -1),
            WorldCoordinate(1, 0),
            WorldCoordinate(0, 1),
            WorldCoordinate(-1, 0),
            WorldCoordinate(1, -1),
            WorldCoordinate(1, 1),
            WorldCoordinate(-1, 1),
            WorldCoordinate(-1, -1)
        )
        return offsets.asSequence()
            .map { target + it }
            .filter { it in state.explored && state.contains(it) }
            .filter { Pathfinder.walkable(state, it, knownOnly = true) }
            .mapNotNull { candidate ->
                val path = Pathfinder.findPath(state, state.actor.coordinate, candidate, knownOnly = true)
                if (candidate == state.actor.coordinate || path.isNotEmpty()) candidate to path else null
            }
            .sortedWith(compareBy<Pair<WorldCoordinate, List<WorldCoordinate>>> { it.second.size }
                .thenBy { it.first.manhattanDistanceTo(target) }
                .thenBy { it.first.y }
                .thenBy { it.first.x })
            .firstOrNull()
            ?.first
    }

    private fun isAtFarm(state: WorldState, target: ActionTarget): Boolean {
        val farmId = state.structures.firstOrNull { it.type == StructureType.FARM }?.id
        val inFarmInterior = state.actor.presence == PresenceMode.INTERIOR && state.actor.structureId == farmId
        if (inFarmInterior) return true
        val plot = farmPlotTarget(state, target)
        return isFarmTarget(state, target) &&
            plot != null && state.actor.coordinate.chebyshevDistanceTo(WorldCoordinate(plot.x, plot.y)) <= 1
    }

    private fun isFarmTarget(state: WorldState, target: ActionTarget): Boolean =
        target.kind == ActionTargetKind.FARM_PLOT && farmPlotTarget(state, target) != null

    private fun farmPlotTarget(state: WorldState, target: ActionTarget): FarmPlotReference? =
        target.id?.let { id -> state.farmPlots.firstOrNull { it.id == id } }
            ?: target.coordinate?.let { coordinate -> state.farmPlots.firstOrNull { it.x == coordinate.x && it.y == coordinate.y } }

    private fun advancePetAction(
        state: WorldState,
        pet: CanonicalPetSnapshot?,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): WorldState {
        val actor = state.actor
        if (actor.actionState != ActionState.RUNNING || actor.actionTicksRemaining <= 0) return state
        val remaining = actor.actionTicksRemaining - 1
        if (remaining > 0) return state.copy(actor = actor.copy(actionTicksRemaining = remaining))

        val action = actor.action
        val actionArguments = actor.actionArguments
        val completionCoordinate = actor.actionTargetId
            ?.let { targetId -> state.npcs.firstOrNull { it.id == targetId }?.coordinate }
            ?: actor.actionTargetX?.let { x -> actor.actionTargetY?.let { y -> WorldCoordinate(x, y) } }
        val target = ActionTarget(
            kind = inferTargetKind(state, actor.actionTargetId, actor.actionTargetX, actor.actionTargetY, action),
            id = actor.actionTargetId,
            coordinate = completionCoordinate,
            available = targetAvailable(
                state,
                actor.actionTargetId,
                action,
                completionCoordinate,
                actionArguments
            ),
            arguments = actionArguments,
            objectType = targetObject(state, actor.actionTargetId, actor.actionTargetX, actor.actionTargetY)?.type
        )
        val autonomous = actionArguments[AUTONOMOUS_ACTION_ARGUMENT] == "true"
        val context = ActionContext(
            actorType = state.actor.actorType,
            actorCoordinate = actor.coordinate,
            target = target,
            presence = actor.presence,
            structureType = actor.actionTargetId?.let { id -> state.structures.firstOrNull { it.id == id }?.type }
                ?: actor.structureId?.let { id -> state.structures.firstOrNull { it.id == id }?.type },
            atFarm = isAtFarm(state, target),
            hasItem = { item -> pet?.inventory.orEmpty().any { stack -> stackMatchesItem(stack, item) } },
            itemKind = { item -> inventoryKind(pet, item) },
            hasTool = { tool -> pet?.inventory.orEmpty().any { it.quantity > 0 && hasUsableTool(it.itemId, tool) } },
            money = pet?.money ?: 0,
            energy = pet?.needs?.energy ?: actor.needs.energy,
            health = pet?.needs?.health ?: actor.needs.health,
            sleeping = pet?.sleeping == true,
            userOwnedCrop = autonomous && action == ActionId.HARVEST_CROP &&
                pet?.farm?.plots?.firstOrNull { it.id == target.id }?.userOwned == true,
            autonomy = if (autonomous) pet?.autonomy ?: AutonomyPolicy() else AutonomyPolicy(level = AutonomyLevel.FULL),
            itemQuantity = { item -> pet?.inventory.orEmpty().firstOrNull { stackMatchesItem(it, item) }?.quantity ?: 0 },
            isDaytime = isDaytime(state),
            actorId = actor.actorId,
            structureId = actor.structureId
        )
        val statefulFailure = validateStatefulAction(state, pet, action, target)
        val validation = ActionExecutor.validate(action, context)
        val completionGoal = when (action) {
            // A completed activity is a physical visit. Once the canonical
            // activity has ended, the pet should leave the building and
            // return home instead of restarting the same action forever.
            ActionId.STUDY, ActionId.WORK, ActionId.PLAY,
            ActionId.TRAIN_BOXING -> GoalId.RETURN_HOME
            ActionId.REST, ActionId.SIT, ActionId.SLEEP -> GoalId.IDLE
            else -> actor.goal
        }
        var next = state.copy(actor = actor.copy(
            actionState = ActionState.COMPLETED,
            actionTicksRemaining = 0,
            goal = completionGoal
        ))
        if (statefulFailure != null || !validation.valid) {
            val reason = statefulFailure ?: validation.reason
            observations += "action_blocked:${action.name}:$reason"
            return next.copy(actor = next.actor.copy(
                actionState = ActionState.BLOCKED,
                goal = GoalId.RECOVER_STUCK,
                actionArguments = emptyMap(),
                pendingCommand = null
            ))
        }

        // Canonical pet operations remain owned by the app's existing
        // transaction path. The world core owns the approach, validation,
        // duration, and completion boundary, then emits one typed request so
        // the adapter cannot accidentally apply a second set of inventory,
        // need, activity, or reward effects.
        val canonicalAction = target.arguments[CANONICAL_ACTION_ARGUMENT]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (canonicalAction != null) {
            val forwardedArguments = target.arguments - CANONICAL_ACTION_ARGUMENT
            // Hospital treatment uses the marker to distinguish an
            // autonomous visit from an explicit user visit when the Android
            // canonical transaction is committed. Other existing canonical
            // adapters retain their historical filtered argument contract.
            val canonicalArguments = if (canonicalAction == "visitHospital") {
                forwardedArguments
            } else {
                forwardedArguments - AUTONOMOUS_ACTION_ARGUMENT
            }
            effects += WorldEffectRequest.CanonicalAction(
                action = canonicalAction,
                arguments = canonicalArguments
            )
            observations += "action_completed:${action.name}"
            return next.copy(actor = next.actor.copy(
                actionArguments = emptyMap(),
                pendingCommand = null
            ))
        }

        effects += ActionExecutor.effectRequests(action)
        next = appendActionSpecificEffects(next, action, target, effects)
        val definition = ActionRegistry[action]
        val consumedObject = target.id?.let { id -> state.objects.firstOrNull { it.id == id } }
            ?: target.coordinate?.let { coordinate -> WorldGenerator.objectAt(state, coordinate.x, coordinate.y) }
        if (definition.worldEffects.contains("consume_resource") && consumedObject != null) {
            next = appendDelta(next, WorldDelta(
                x = consumedObject.x,
                y = consumedObject.y,
                kind = WorldDeltaKind.RESOURCE,
                value = "consumed",
                updatedAtTick = next.tick,
                objectId = consumedObject.id
            ))
        }
        observations += "action_completed:${action.name}"
        return next.copy(actor = next.actor.copy(
            actionArguments = emptyMap(),
            pendingCommand = null
        ))
    }

    private fun advancePetIntent(
        state: WorldState,
        pet: CanonicalPetSnapshot?,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>,
        navigationPolicy: NavigationPolicy?
    ): WorldState {
        if (pet == null || pet.cycleFrozen || pet.isEgg || pet.sleeping) return state
        val pendingCommand = state.actor.pendingCommand
        // Explicit recovered actions may target an interior. They retain their
        // own prerequisites and must run even when autonomous planning is off.
        if (state.actor.path.isEmpty() && pendingCommand != null) {
            return executePendingCommand(state, pendingCommand, pet, effects, observations)
        }
        val followIntent = state.actor.followTargetId != null ||
            state.actor.actionArguments[WorldActionSemantics.FOLLOW_INTENT_ARGUMENT] == "true"
        if (state.actor.presence != PresenceMode.WORLD) {
            if (followIntent) {
                val left = leaveStructure(state, pet, effects, observations)
                if (!left.accepted) return state
                val previousActor = state.actor
                val restored = left.state.copy(actor = left.state.actor.copy(
                    followTargetId = previousActor.followTargetId ?: previousActor.actionTargetId,
                    actionTargetId = previousActor.actionTargetId,
                    actionTargetX = previousActor.actionTargetX,
                    actionTargetY = previousActor.actionTargetY,
                    actionArguments = previousActor.actionArguments
                ))
                return advanceFollowIntent(restored, effects, observations)
            }
            if (pet.autonomy.level == AutonomyLevel.OFF) return state
            return advanceIndoorPet(state, pet, effects, observations, navigationPolicy)
        }
        var actor = state.actor
        if (actor.path.isNotEmpty()) {
            val nextCoordinate = actor.path.first()
            val tile = WorldGenerator.tileAt(state, nextCoordinate.x, nextCoordinate.y)
            if (!tile.walkable) {
                return state.copy(actor = actor.copy(
                    path = emptyList(),
                    action = ActionId.WAIT,
                    actionState = ActionState.BLOCKED,
                    goal = GoalId.RECOVER_STUCK,
                    stuckTicks = actor.stuckTicks + 1
                ))
            }
            val remaining = actor.path.drop(1)
            val direction = directionFrom(actor.coordinate, nextCoordinate)
            appendMovementEnergy(1, actor.action, effects)
            actor = actor.at(nextCoordinate).copy(
                facing = direction,
                path = remaining,
                action = if (actor.action == ActionId.RUN) ActionId.RUN else ActionId.WALK,
                actionState = if (remaining.isEmpty()) ActionState.COMPLETED else ActionState.RUNNING,
                stuckTicks = 0
            )
            val pendingStructureId = actor.pendingStructureId
            if (remaining.isEmpty() && pendingStructureId != null) {
                val entered = enterStructure(
                    state.copy(actor = actor),
                    pendingStructureId,
                    pet,
                    effects,
                    observations
                )
                if (entered.accepted) return entered.state
            }
            val pendingAfterMove = actor.pendingCommand
            if (remaining.isEmpty() && pendingAfterMove != null) {
                return executePendingCommand(state.copy(actor = actor), pendingAfterMove, pet, effects, observations)
            }
            return state.copy(actor = actor)
        }

        // A follow request is an explicit persistent route and therefore runs
        // even when autonomy is OFF. Re-read the NPC position on every tick;
        // a pending approach command is handled above until the actor reaches
        // the target's current vicinity.
        if (followIntent && pendingCommand == null) {
            return advanceFollowIntent(state, effects, observations)
        }

        // A resource's generated coordinate may be solid (for example, a
        // standing tree), so planForKnownWorld can finish on an adjacent
        // walkable tile while retaining the resource coordinate as the
        // destination. Interact as soon as that physical range is reached;
        // otherwise the empty-path branch below would mark the pet stuck
        // before actionForGoalAtDestination gets a chance to start CHOP_TREE.
        resourceGoalInteractionAtCurrentPosition(state, pet, observations, effects)?.let { interaction ->
            return interaction
        }

        val currentDestination = actor.destination
        val pendingStructureId = actor.pendingStructureId
        if (currentDestination != null && actor.coordinate == currentDestination) {
            if (pendingStructureId != null) {
                val entered = enterStructure(
                    state,
                    pendingStructureId,
                    pet,
                    effects,
                    observations
                )
                if (entered.accepted) return entered.state
            }
            return state.copy(actor = actor.copy(destinationX = null, destinationY = null, goal = GoalId.IDLE, action = ActionId.WAIT, actionState = ActionState.COMPLETED))
        }

        val pendingStructure = actor.pendingStructureId?.let { id -> state.structures.firstOrNull { it.id == id } }
        val retainedDestination = actor.destination.takeIf { actor.goal == GoalId.EXPLORE }
        // Manual routes and pending structures still replan with autonomy
        // OFF. This matters when a route reaches a known frontier: the
        // destination remains hidden, so another safe known-world plan is
        // required instead of freezing at the frontier. Only fresh goal
        // selection is permission-gated.
        if (pet.autonomy.level == AutonomyLevel.OFF &&
            pendingStructure == null && retainedDestination == null
        ) return state
        val goal = when {
            pendingStructure?.type == StructureType.HOME -> GoalId.RETURN_HOME
            pendingStructure != null -> actor.goal.takeUnless { it == GoalId.IDLE } ?: GoalId.VISIT_INTERESTING_PLACE
            retainedDestination != null -> actor.goal
            else -> GoalSelector.choose(state, pet, actor.goal)
        }
        val destination = pendingStructure?.entrance ?: retainedDestination ?: destinationForGoal(state, pet, goal)
        if (destination == null) {
            return state.copy(actor = actor.copy(goal = GoalId.IDLE, action = ActionId.WAIT, actionState = ActionState.IDLE))
        }
        val plan = Pathfinder.planForKnownWorld(state, destination)
        val policyState = state.copy(actor = actor.copy(
            goal = goal,
            destinationX = destination.x,
            destinationY = destination.y
        ))
        val policyDecision = navigationPolicy?.choose(policyState, pet, goal)
        // Adopted navigation may wait or select a route that is useful for a
        // normal goal, but it cannot defer a survival transition. Critical
        // care goals always use the deterministic core route so an always
        // WAIT policy cannot strand a pet with no energy, food, water, or
        // health. Keep the decision for recurrent-memory persistence and
        // diagnostics even when its movement is safety-overridden.
        val safetyOverride = isCriticalSurvivalNeed(pet.needs)
        val appliedPolicyDecision = if (safetyOverride) null else policyDecision
        if (safetyOverride && policyDecision != null) {
            observations += "navigation_safety_override:${goal.name}"
        }
        val policyPath = appliedPolicyDecision?.let { decision -> policyPath(state, decision) }
        val path = when {
            appliedPolicyDecision?.intent == NavigationIntent.WAIT -> emptyList()
            appliedPolicyDecision?.intent == NavigationIntent.INTERACT -> emptyList()
            appliedPolicyDecision?.intent == NavigationIntent.SEARCH -> {
                val searchTarget = WorldKnowledge.nextUnknownTarget(state)
                if (searchTarget != null) Pathfinder.planForKnownWorld(state, searchTarget).path else plan.path
            }
            policyPath != null -> policyPath
            else -> plan.path
        }
        if (path.isEmpty() && destination != actor.coordinate && !plan.reachedTarget) {
            observations += "known_frontier:${actor.x},${actor.y}"
            return state.copy(actor = actor.copy(
                goal = goal,
                destinationX = destination.x,
                destinationY = destination.y,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                actionTicksRemaining = 0,
                pendingStructureId = actor.pendingStructureId ?: structureForGoal(state, goal),
                navigationMemory = boundedNavigationMemory(policyDecision, actor.navigationMemory)
            ))
        }
        if (destination != actor.coordinate && path.isEmpty() && !plan.stoppedAtKnownFrontier) {
            val stuck = actor.stuckTicks + 1
            return state.copy(actor = actor.copy(goal = GoalId.RECOVER_STUCK, action = ActionId.WAIT, actionState = ActionState.BLOCKED, stuckTicks = stuck))
        }
        val run = goal == GoalId.RETURN_HOME && SafetyInstincts.canRun(pet.needs)
        actor = actor.copy(
            goal = goal,
            destinationX = destination.x,
            destinationY = destination.y,
            path = path,
            action = if (path.isEmpty()) ActionId.WAIT else if (run) ActionId.RUN else ActionId.WALK,
            actionState = if (path.isEmpty()) ActionState.COMPLETED else ActionState.RUNNING,
            pendingStructureId = actor.pendingStructureId ?: structureForGoal(state, goal),
            navigationMemory = boundedNavigationMemory(policyDecision, actor.navigationMemory)
        )
        observations += "goal:${goal.name}"
        if (path.isEmpty()) {
            val interaction = actionForGoalAtDestination(state.copy(actor = actor), pet, goal, observations, effects)
            return interaction
        }
        return state.copy(actor = actor)
    }

    private fun isCriticalSurvivalNeed(needs: NeedsProjection): Boolean =
        setOf(NeedType.HEALTH, NeedType.HUNGER, NeedType.HYDRATION, NeedType.ENERGY)
            .any { NeedsSystem.isCritical(needs, it) }

    /**
     * Resolve the home/interior boundary for autonomous simulation. A pet can
     * rest or run a queued activity indoors, but an idle pet must be able to
     * leave again and continue its world goal after a visit. Explicit command
     * handlers suppress this path for their transition tick.
     */
    private fun advanceIndoorPet(
        state: WorldState,
        pet: CanonicalPetSnapshot,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>,
        navigationPolicy: NavigationPolicy?
    ): WorldState {
        val actor = state.actor
        if (actor.pendingActivity != null || pet.activity != PetActivity.NONE) return state
        if (actor.actionState == ActionState.RUNNING && actor.actionTicksRemaining > 0) return state

        val retainedActivityGoal = actor.goal.takeIf {
            it in setOf(GoalId.STUDY, GoalId.WORK, GoalId.PLAY, GoalId.TRAIN)
        }
        val goal = retainedActivityGoal ?: GoalSelector.choose(state, pet, actor.goal)

        if (actor.presence == PresenceMode.HOME) {
            // An explicit ReturnHome command should keep the pet home when it
            // is already rested. Autonomous returns arrive with a critical
            // energy need and take the rest/sleep branch below.
            if (goal == GoalId.RETURN_HOME && actor.action == ActionId.WAIT &&
                !NeedsSystem.isCritical(pet.needs, NeedType.ENERGY)
            ) return state.copy(actor = actor.copy(goal = GoalId.RETURN_HOME, actionState = ActionState.IDLE))

            val restAction = when (goal) {
                GoalId.SLEEP -> ActionId.SLEEP
                GoalId.REST -> ActionId.REST
                GoalId.RETURN_HOME -> if (NeedsSystem.isCritical(pet.needs, NeedType.ENERGY)) ActionId.SLEEP else ActionId.REST
                else -> null
            }
            if (restAction != null) {
                val started = beginAction(
                    state = state,
                    action = restAction,
                    target = ActionTarget(ActionTargetKind.NONE),
                    pet = pet,
                    autonomous = true,
                    observations = observations,
                    effects = effects
                )
                return if (started.accepted) started.state else state.copy(actor = actor.copy(
                    action = ActionId.WAIT,
                    actionState = ActionState.BLOCKED,
                    goal = GoalId.RECOVER_STUCK
                ))
            }
        }

        if (actor.presence == PresenceMode.INTERIOR) {
            val structureType = actor.structureId?.let { id ->
                state.structures.firstOrNull { it.id == id }?.type
            }
            val indoorAction = when {
                goal == GoalId.STUDY && structureType == StructureType.SCHOOL && pet.autonomy.allowStudy -> ActionId.STUDY
                goal == GoalId.WORK && structureType == StructureType.WORKPLACE && pet.autonomy.allowWork -> ActionId.WORK
                goal == GoalId.PLAY && structureType == StructureType.ARCADE -> ActionId.PLAY
                goal == GoalId.TRAIN && structureType == StructureType.BOXING_RING -> ActionId.TRAIN_BOXING
                else -> null
            }
            if (indoorAction != null) {
                val started = beginAction(
                    state = state,
                    action = indoorAction,
                    target = ActionTarget(ActionTargetKind.STRUCTURE, actor.structureId, actor.coordinate),
                    pet = pet,
                    autonomous = true,
                    observations = observations,
                    effects = effects
                )
                return if (started.accepted) started.state else state.copy(actor = actor.copy(
                    action = ActionId.WAIT,
                    actionState = ActionState.BLOCKED,
                    goal = GoalId.RECOVER_STUCK
                ))
            }

            // Explicit visits settle inside the selected building until the
            // next deliberate command. An autonomous return goal, or a goal
            // that no longer matches this building, leaves through the shared
            // safety gate below.
            if (actor.goal == GoalId.IDLE || actor.goal == GoalId.VISIT_INTERESTING_PLACE) return state
        }

        val left = leaveStructure(state, pet, effects, observations)
        if (!left.accepted) return state
        return advancePetIntent(left.state, pet, effects, observations, navigationPolicy)
    }

    private fun executePendingCommand(
        state: WorldState,
        pending: PendingWorldCommand,
        pet: CanonicalPetSnapshot,
        effects: MutableList<WorldEffectRequest>,
        observations: MutableList<String>
    ): WorldState {
        val targetKind = inferTargetKind(state, pending.targetId, pending.targetX, pending.targetY, pending.action)
        val target = actionTarget(
            state = state,
            targetId = pending.targetId,
            targetKind = targetKind,
            targetX = pending.targetX,
            targetY = pending.targetY,
            arguments = pending.arguments,
            action = pending.action
        )
        val started = beginAction(
            state = state.copy(actor = state.actor.copy(pendingCommand = null)),
            action = pending.action,
            target = target,
            pet = pet,
            autonomous = pending.arguments[AUTONOMOUS_ACTION_ARGUMENT] == "true",
            observations = observations,
            arguments = pending.arguments,
            effects = effects
        )
        if (started.accepted) return started.state
        observations += "pending_action_blocked:${pending.action.name}:${started.reason}"
        return state.copy(actor = state.actor.copy(
            action = ActionId.WAIT,
            actionState = ActionState.BLOCKED,
            actionTicksRemaining = 0,
            goal = GoalId.RECOVER_STUCK,
            actionArguments = emptyMap(),
            pendingCommand = null,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            path = emptyList(),
            followTargetId = null
        ))
    }

    private fun actionForGoalAtDestination(
        state: WorldState,
        pet: CanonicalPetSnapshot,
        goal: GoalId,
        observations: MutableList<String>,
        effects: MutableList<WorldEffectRequest>
    ): WorldState {
        val food = pet.inventory.firstOrNull { it.quantity > 0 && inventoryStackIs(it, InventoryKind.FOOD) }
        val water = pet.inventory.firstOrNull { it.quantity > 0 && inventoryStackIs(it, InventoryKind.WATER) }
        val target = when (goal) {
            GoalId.FIND_FOOD -> food?.let {
                ActionTarget(ActionTargetKind.ITEM, it.itemId, state.actor.coordinate)
            } ?: knownResourceTarget(state, WorldObjectType.BERRY_PATCH)
                ?: ActionTarget(ActionTargetKind.TILE, coordinate = state.actor.coordinate)
            GoalId.GATHER_WOOD -> if (pet.inventory.any { it.quantity > 0 && hasUsableTool(it.itemId, "axe") }) {
                knownResourceTarget(state, WorldObjectType.TREE, WorldObjectType.FALLEN_LOG)
            } else knownResourceTarget(state, WorldObjectType.FALLEN_LOG)
            GoalId.GATHER_HERBS -> knownResourceTarget(
                state,
                WorldObjectType.HERB_PATCH,
                WorldObjectType.GLOWING_PLANT,
                WorldObjectType.MUSHROOM
            )
            GoalId.FIND_WATER -> when {
                water != null -> ActionTarget(ActionTargetKind.ITEM, water.itemId, state.actor.coordinate)
                WorldGenerator.tileAt(state, state.actor.x, state.actor.y).kind == TileKind.SHALLOW_WATER -> {
                    val coordinate = state.actor.coordinate
                    ActionTarget(ActionTargetKind.OBJECT, id = "natural_water_${coordinate.x}_${coordinate.y}", coordinate = coordinate)
                }
                else -> ActionTarget(ActionTargetKind.TILE, coordinate = state.actor.coordinate)
            }
            GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> nearestKnownNpc(state)
                ?.let { (npc, coordinate) -> ActionTarget(ActionTargetKind.ACTOR, npc.id, coordinate) }
            GoalId.FARM -> farmTarget(state, pet)
            else -> null
        } ?: return state
        val action = when (goal) {
            GoalId.FIND_FOOD -> if (target.kind == ActionTargetKind.ITEM) ActionId.EAT else if (target.kind == ActionTargetKind.OBJECT) ActionId.FORAGE else ActionId.SEARCH_AREA
            GoalId.FIND_WATER -> if (target.kind == ActionTargetKind.ITEM || target.kind == ActionTargetKind.OBJECT) ActionId.DRINK else ActionId.SEARCH_AREA
            GoalId.GATHER_WOOD -> if (targetObject(state, target)?.type == WorldObjectType.TREE) ActionId.CHOP_TREE else ActionId.GATHER_WOOD
            GoalId.GATHER_HERBS -> ActionId.GATHER_HERB
            GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> ActionId.TALK
            GoalId.FARM -> farmAction(pet, target) ?: return state
            else -> return state
        }
        val begin = beginAction(
            state = state,
            action = action,
            target = target,
            pet = pet,
            autonomous = true,
            observations = observations,
            effects = effects
        )
        return if (begin.accepted) begin.state else state.copy(actor = state.actor.copy(actionState = ActionState.BLOCKED, goal = GoalId.RECOVER_STUCK))
    }

    private fun resourceGoalInteractionAtCurrentPosition(
        state: WorldState,
        pet: CanonicalPetSnapshot,
        observations: MutableList<String>,
        effects: MutableList<WorldEffectRequest>
    ): WorldState? {
        if (pet.autonomy.level == AutonomyLevel.OFF) return null
        // A queued explicit action or structure visit owns this transition.
        // Do not let the autonomous resource shortcut consume that command.
        if (state.actor.pendingCommand != null || state.actor.pendingStructureId != null) return null
        val goal = state.actor.goal
        if (goal !in setOf(GoalId.FIND_FOOD, GoalId.FIND_WATER, GoalId.GATHER_WOOD, GoalId.GATHER_HERBS)) {
            return null
        }
        // Goal selection below has a deterministic emergency ordering. If a
        // stale gather goal survives into this branch, give a newly critical
        // survival need the opportunity to replace it first.
        if (NeedsSystem.isCritical(pet.needs, NeedType.HEALTH) ||
            NeedsSystem.isCritical(pet.needs, NeedType.ENERGY) ||
            (NeedsSystem.isCritical(pet.needs, NeedType.HUNGER) && goal != GoalId.FIND_FOOD) ||
            (NeedsSystem.isCritical(pet.needs, NeedType.HYDRATION) && goal != GoalId.FIND_WATER)
        ) return null
        val target = when (goal) {
            GoalId.FIND_FOOD -> pet.inventory.firstOrNull {
                it.quantity > 0 && inventoryStackIs(it, InventoryKind.FOOD)
            }?.let { ActionTarget(ActionTargetKind.ITEM, it.itemId, state.actor.coordinate) }
                ?: knownResourceTarget(state, WorldObjectType.BERRY_PATCH)
                ?: ActionTarget(ActionTargetKind.TILE, coordinate = state.actor.coordinate)
            GoalId.FIND_WATER -> pet.inventory.firstOrNull {
                it.quantity > 0 && inventoryStackIs(it, InventoryKind.WATER)
            }?.let { ActionTarget(ActionTargetKind.ITEM, it.itemId, state.actor.coordinate) }
                ?: if (WorldGenerator.tileAt(state, state.actor.x, state.actor.y).kind == TileKind.SHALLOW_WATER) {
                    val coordinate = state.actor.coordinate
                    ActionTarget(ActionTargetKind.OBJECT, "natural_water_${coordinate.x}_${coordinate.y}", coordinate)
                } else {
                    null
                }
            GoalId.GATHER_WOOD -> if (pet.inventory.any {
                it.quantity > 0 && hasUsableTool(it.itemId, "axe")
            }) {
                knownResourceTarget(state, WorldObjectType.TREE, WorldObjectType.FALLEN_LOG)
            } else {
                knownResourceTarget(state, WorldObjectType.FALLEN_LOG)
            }
            GoalId.GATHER_HERBS -> knownResourceTarget(
                state,
                WorldObjectType.HERB_PATCH,
                WorldObjectType.GLOWING_PLANT,
                WorldObjectType.MUSHROOM
            )
            else -> null
        } ?: return null
        val targetCoordinate = target.coordinate ?: return null
        if (state.actor.coordinate.chebyshevDistanceTo(targetCoordinate) > 1) return null
        val next = actionForGoalAtDestination(state, pet, goal, observations, effects)
        return next.takeIf { it.actor != state.actor }
    }

    private fun knownResourceTarget(state: WorldState, vararg kinds: WorldObjectType): ActionTarget? =
        state.knownResources
            .filter { it.kind in kinds }
            // Filter availability before selecting the nearest patch. A
            // depleted remembered patch must not hide a usable one farther
            // along the discovered map.
            .mapNotNull { known ->
                val coordinate = WorldCoordinate(known.approximateX, known.approximateY)
                val objectValue = WorldGenerator.objectAt(state, coordinate.x, coordinate.y)
                    ?.takeIf { it.quantity > 0 && it.state.isResourceAvailable() }
                    ?: return@mapNotNull null
                ActionTarget(ActionTargetKind.OBJECT, objectValue.id, coordinate)
            }
            .minByOrNull { target ->
                val coordinate = requireNotNull(target.coordinate)
                abs(coordinate.x - state.actor.x) + abs(coordinate.y - state.actor.y)
            }

    private fun farmTarget(state: WorldState, pet: CanonicalPetSnapshot): ActionTarget? {
        val plot = pet.farm?.plots
            ?.filter { plot ->
                when {
                    plot.isReady -> !plot.userOwned || pet.autonomy.allowHarvestingUserCrops
                    plot.isDead -> true
                    plot.status.equals("soil", ignoreCase = true) -> true
                    plot.cropId != null -> true
                    else -> false
                }
            }
            ?.minByOrNull { abs(it.x - state.actor.x) + abs(it.y - state.actor.y) }
            ?: return null
        return ActionTarget(ActionTargetKind.FARM_PLOT, plot.id, WorldCoordinate(plot.x, plot.y), available = true)
    }

    private fun farmAction(pet: CanonicalPetSnapshot, target: ActionTarget): ActionId? =
        pet.farm?.plots?.firstOrNull { it.id == target.id }?.let { plot ->
            when {
                plot.isReady -> ActionId.HARVEST_CROP
                plot.isDead -> ActionId.REMOVE_DEAD_CROP
                plot.status.equals("soil", ignoreCase = true) -> ActionId.TILL_SOIL
                plot.cropId == null -> ActionId.PLANT
                else -> ActionId.WATER
            }
        }

    private fun destinationForGoal(state: WorldState, pet: CanonicalPetSnapshot, goal: GoalId): WorldCoordinate? = when (goal) {
        GoalId.RETURN_HOME, GoalId.REST, GoalId.SLEEP -> state.structures.firstOrNull { it.type == StructureType.HOME }?.entrance
        // Farm plots are world coordinates. The farmhouse remains a visual
        // destination for explicit commands, while autonomous farm work goes
        // straight to the selected plot and never opens an interior screen.
        GoalId.FARM -> farmTarget(state, pet)?.coordinate
            ?: state.structures.firstOrNull { it.type == StructureType.FARM }?.entrance
        GoalId.STUDY -> if (pet.autonomy.allowStudy) state.structures.firstOrNull { it.type == StructureType.SCHOOL }?.entrance else null
        GoalId.WORK -> if (pet.autonomy.allowWork) state.structures.firstOrNull { it.type == StructureType.WORKPLACE }?.entrance else null
        GoalId.PLAY -> state.structures.firstOrNull { it.type == StructureType.ARCADE }?.entrance
        GoalId.FIND_FOOD, GoalId.GATHER_HERBS, GoalId.GATHER_WOOD ->
            pet.inventory.firstOrNull { it.quantity > 0 && goal == GoalId.FIND_FOOD && inventoryStackIs(it, InventoryKind.FOOD) }
                ?.let { state.actor.coordinate }
                ?: nearestKnownResource(state, goal, pet.inventory.any { it.quantity > 0 && hasUsableTool(it.itemId, "axe") })
                ?: WorldKnowledge.nextUnknownTarget(state)
        GoalId.FIND_WATER ->
            pet.inventory.firstOrNull { it.quantity > 0 && inventoryStackIs(it, InventoryKind.WATER) }
                ?.let { state.actor.coordinate }
                ?: nearestWater(state)
                ?: WorldKnowledge.nextUnknownTarget(state)
        GoalId.SOCIALIZE, GoalId.VISIT_FRIEND -> nearestKnownNpc(state)?.second
        GoalId.TRAIN -> state.structures.firstOrNull { it.type == StructureType.BOXING_RING }?.entrance
        GoalId.EXPLORE, GoalId.INVESTIGATE, GoalId.VISIT_INTERESTING_PLACE -> WorldKnowledge.nextUnknownTarget(state)
        else -> null
    }

    private fun structureForGoal(state: WorldState, goal: GoalId): String? = when (goal) {
        GoalId.RETURN_HOME, GoalId.REST, GoalId.SLEEP -> state.structures.firstOrNull { it.type == StructureType.HOME }?.id
        GoalId.FARM -> null
        GoalId.STUDY -> state.structures.firstOrNull { it.type == StructureType.SCHOOL }?.id
        GoalId.WORK -> state.structures.firstOrNull { it.type == StructureType.WORKPLACE }?.id
        GoalId.PLAY -> state.structures.firstOrNull { it.type == StructureType.ARCADE }?.id
        GoalId.TRAIN -> state.structures.firstOrNull { it.type == StructureType.BOXING_RING }?.id
        else -> null
    }

    private fun nearestKnownResource(state: WorldState, goal: GoalId, canChop: Boolean): WorldCoordinate? {
        val preferred = when (goal) {
            GoalId.GATHER_HERBS -> setOf(WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT, WorldObjectType.MUSHROOM)
            GoalId.GATHER_WOOD -> if (canChop) setOf(WorldObjectType.TREE, WorldObjectType.FALLEN_LOG) else setOf(WorldObjectType.FALLEN_LOG)
            GoalId.FIND_FOOD -> setOf(WorldObjectType.BERRY_PATCH)
            else -> emptySet()
        }
        return state.knownResources.filter { it.kind in preferred }
            .filter { known ->
                WorldGenerator.objectAt(state, known.approximateX, known.approximateY)
                    ?.let { it.quantity > 0 && it.state.isResourceAvailable() } == true
            }
            .minByOrNull { abs(it.approximateX - state.actor.x) + abs(it.approximateY - state.actor.y) }
            ?.let { WorldCoordinate(it.approximateX, it.approximateY) }
    }

    private fun nearestWater(state: WorldState): WorldCoordinate? {
        val actor = state.actor.coordinate
        var best: WorldCoordinate? = null
        var bestDistance = Int.MAX_VALUE
        val radius = 32
        for (y in (actor.y - radius)..(actor.y + radius)) {
            for (x in (actor.x - radius)..(actor.x + radius)) {
                val coordinate = WorldCoordinate(x, y)
                if (!state.contains(coordinate)) continue
                if (coordinate !in state.explored) continue
                val kind = WorldGenerator.tileAt(state, x, y).kind
                if (kind != TileKind.SHALLOW_WATER) continue
                val distance = actor.manhattanDistanceTo(coordinate)
                if (distance < bestDistance) {
                    best = coordinate
                    bestDistance = distance
                }
            }
        }
        return best
    }

    private fun nearestKnownNpc(state: WorldState): Pair<WorldNpc, WorldCoordinate>? =
        state.knownNpcs
            .mapNotNull { known ->
                val npc = state.npcs.firstOrNull { it.id == known.npcId } ?: return@mapNotNull null
                npc to WorldCoordinate(known.approximateX, known.approximateY)
            }
            .minByOrNull { (_, coordinate) -> coordinate.manhattanDistanceTo(state.actor.coordinate) }

    private fun advanceNpcs(state: WorldState, simulationRadius: Int, coarseTicks: Long = 1L): WorldState =
        WorldNpcSimulation.advance(state, simulationRadius, coarseTicks)

    private fun appendNeedDeltas(
        before: NeedsProjection,
        after: NeedsProjection,
        effects: MutableList<WorldEffectRequest>,
        reason: String
    ) {
        NeedType.entries.forEach { need ->
            val delta = after.valueOf(need) - before.valueOf(need)
            if (kotlin.math.abs(delta) > .0001f) effects += WorldEffectRequest.NeedDelta(need, delta, reason)
        }
    }

    private fun appendMovementEnergy(
        steps: Int,
        action: ActionId,
        effects: MutableList<WorldEffectRequest>
    ) {
        if (steps <= 0) return
        val movementAction = if (action == ActionId.RUN) ActionId.RUN else ActionId.WALK
        val cost = ActionRegistry[movementAction].energyCost
        if (cost <= 0f) return
        effects += WorldEffectRequest.NeedDelta(
            need = NeedType.ENERGY,
            delta = -cost * steps,
            reason = "movement:${movementAction.name.lowercase()}"
        )
    }

    /** Applies only the core's proposed effects to an ephemeral catch-up view. */
    private fun projectPetEffects(
        pet: CanonicalPetSnapshot,
        effects: List<WorldEffectRequest>
    ): CanonicalPetSnapshot {
        var projected = pet
        effects.forEach { effect ->
            projected = when (effect) {
                is WorldEffectRequest.NeedDelta -> projected.copy(needs = projected.needs.plus(effect.need, effect.delta))
                is WorldEffectRequest.InventoryDelta -> {
                    val current = projected.inventory.toMutableList()
                    val index = current.indexOfFirst { it.itemId == effect.itemId || stackMatchesItem(it, effect.itemId) }
                    if (index >= 0) {
                        val nextQuantity = (current[index].quantity + effect.quantity).coerceAtLeast(0)
                        if (nextQuantity == 0) current.removeAt(index) else current[index] = current[index].copy(quantity = nextQuantity)
                    } else if (effect.quantity > 0) {
                        current += InventoryStack(effect.itemId, effect.quantity, InventoryKinds.infer(effect.itemId))
                    }
                    projected.copy(inventory = current)
                }
                is WorldEffectRequest.MoneyDelta -> projected.copy(money = (projected.money + effect.amount).coerceAtLeast(0))
                is WorldEffectRequest.Activity -> projected.copy(activity = effect.activity)
                is WorldEffectRequest.RelationshipDelta -> {
                    val previous = projected.relationships[effect.npcId] ?: RelationshipProjection()
                    projected.copy(relationships = projected.relationships + (effect.npcId to RelationshipProjection(
                        familiarity = (previous.familiarity + effect.familiarity).coerceIn(0, 100),
                        friendship = (previous.friendship + effect.friendship).coerceIn(-100, 100),
                        trust = (previous.trust + effect.trust).coerceIn(-100, 100)
                    )))
                }
                is WorldEffectRequest.FarmTransition -> projectFarmTransition(projected, effect)
                is WorldEffectRequest.ToolUse,
                is WorldEffectRequest.CanonicalAction,
                is WorldEffectRequest.ObjectDelta,
                is WorldEffectRequest.StructureDelta,
                is WorldEffectRequest.NpcInventoryDelta,
                is WorldEffectRequest.Event -> projected
            }
        }
        return projected
    }

    private fun canProjectFarmTransition(
        pet: CanonicalPetSnapshot?,
        effect: WorldEffectRequest.FarmTransition
    ): Boolean = pet?.farm?.plots?.any { it.id == effect.plotId } == true

    /**
     * Keep offline replay's canonical pet projection coherent with farm
     * effects. Farm commits remain owned by the Android adapter; this sparse
     * view only prevents a later replay tick from selecting the same plot or
     * spending the same projected input again.
     */
    private fun projectFarmTransition(
        pet: CanonicalPetSnapshot,
        effect: WorldEffectRequest.FarmTransition
    ): CanonicalPetSnapshot {
        val farm = pet.farm ?: return pet
        val plot = farm.plots.firstOrNull { it.id == effect.plotId } ?: return pet
        val selectedCrop = effect.cropId
            ?: effect.arguments["cropId"]
            ?: pet.inventory.firstOrNull {
                it.quantity > 0 && (it.kind == InventoryKind.SEED || InventoryKinds.infer(it.itemId) == InventoryKind.SEED)
            }?.itemId?.removePrefix("seed_")
        val nextPlot = when (effect.action) {
            FarmActionKind.TILL_SOIL -> plot.copy(status = "farmland", cropId = null, isReady = false, isDead = false)
            FarmActionKind.PLANT -> plot.copy(
                cropId = selectedCrop,
                isReady = false,
                isDead = false
            )
            FarmActionKind.WATER, FarmActionKind.POUR_WATER -> plot.copy(status = "wet_farmland", isDead = false)
            FarmActionKind.FERTILIZE -> plot.copy(isDead = false)
            FarmActionKind.HARVEST_CROP, FarmActionKind.REMOVE_DEAD_CROP -> plot.copy(
                status = "soil",
                cropId = null,
                isReady = false,
                isDead = false
            )
            FarmActionKind.STORE_PRODUCE -> plot
        }
        var projected = pet.copy(farm = farm.copy(plots = farm.plots.map { if (it.id == plot.id) nextPlot else it }))
        when (effect.action) {
            FarmActionKind.PLANT -> {
                projected = consumeProjectedItem(projected) { stack ->
                    stack.quantity > 0 &&
                        (stack.kind == InventoryKind.SEED || InventoryKinds.infer(stack.itemId) == InventoryKind.SEED) &&
                        (selectedCrop == null || stack.itemId == "seed_$selectedCrop" || stack.itemId == selectedCrop)
                }
            }
            FarmActionKind.WATER, FarmActionKind.POUR_WATER -> {
                // WATER may use either a filled can or the canonical bottled
                // water item; only the latter is durable inventory state.
                projected = consumeProjectedItem(projected) { stack ->
                    stack.quantity > 0 && (stack.kind == InventoryKind.WATER || InventoryKinds.infer(stack.itemId) == InventoryKind.WATER)
                }
            }
            FarmActionKind.FERTILIZE -> {
                projected = consumeProjectedItem(projected) { stack ->
                    stack.quantity > 0 && (stack.itemId == "fertilizer" || stack.kind == InventoryKind.FERTILIZER)
                }
            }
            else -> Unit
        }
        return projected
    }

    private fun consumeProjectedItem(
        pet: CanonicalPetSnapshot,
        predicate: (InventoryStack) -> Boolean
    ): CanonicalPetSnapshot {
        val index = pet.inventory.indexOfFirst(predicate)
        if (index < 0) return pet
        val inventory = pet.inventory.toMutableList()
        val stack = inventory[index]
        if (stack.quantity <= 1) inventory.removeAt(index) else inventory[index] = stack.copy(quantity = stack.quantity - 1)
        return pet.copy(inventory = inventory)
    }

    private fun appendDelta(state: WorldState, delta: WorldDelta): WorldState {
        val retained = state.deltas.filterNot {
            it.kind == delta.kind && it.x == delta.x && it.y == delta.y && it.objectId == delta.objectId
        }
        return state.copy(deltas = retained + delta)
    }

    private fun inferTargetKind(
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

    private fun actionTarget(
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

    private fun targetCoordinate(state: WorldState, targetId: String): WorldCoordinate? =
        state.structures.firstOrNull { it.id == targetId }?.entrance
            ?: state.npcs.firstOrNull { it.id == targetId }?.coordinate
            ?: state.farmPlots.firstOrNull { it.id == targetId }?.let { WorldCoordinate(it.x, it.y) }
            ?: state.objects.firstOrNull { it.id == targetId }?.let { WorldCoordinate(it.x, it.y) }
            ?: generatedObjectCoordinate(state, targetId)

    private fun generatedObjectCoordinate(state: WorldState, targetId: String): WorldCoordinate? {
        if (!targetId.startsWith("generated_") && !targetId.startsWith("natural_water_")) return null
        val parts = targetId.split('_')
        if (parts.size < 4) return null
        val x = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val y = parts.last().toIntOrNull() ?: return null
        return WorldCoordinate(x, y).takeIf { state.contains(it) && it in state.explored }
    }

    private fun targetAvailable(
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
        // Inventory water has no world object row. The typed item check in
        // ActionExecutor still rejects arbitrary IDs; this only lets a
        // bottled-water target reach that check instead of being marked as a
        // missing world resource.
        if (action == ActionId.DRINK) return true
        if (targetId.startsWith("generated_")) {
            val coordinate = targetCoordinate ?: generatedObjectCoordinate(state, targetId)
            return coordinate != null && coordinate in state.explored &&
                WorldGenerator.objectAt(state, coordinate.x, coordinate.y)
                    ?.let { it.quantity > 0 && it.state.isResourceAvailable() } == true
        }
        return false
    }

    private fun String.isResourceAvailable(): Boolean = lowercase() in setOf("available", "full", "mature", "ready")

    private fun directionFrom(from: WorldCoordinate, to: WorldCoordinate): Direction {
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

    private fun goalForAction(action: ActionId): GoalId = when (action) {
        ActionId.EAT, ActionId.FORAGE, ActionId.HARVEST_WILD_PLANT -> GoalId.FIND_FOOD
        ActionId.DRINK -> GoalId.FIND_WATER
        ActionId.REST, ActionId.SIT, ActionId.SLEEP -> GoalId.REST
        ActionId.TALK, ActionId.GREET, ActionId.PLAY_WITH -> GoalId.SOCIALIZE
        ActionId.EXPLORE, ActionId.WANDER, ActionId.INVESTIGATE -> GoalId.EXPLORE
        ActionId.GATHER_WOOD, ActionId.CHOP_TREE -> GoalId.GATHER_WOOD
        ActionId.GATHER_HERB -> GoalId.GATHER_HERBS
        ActionId.TILL_SOIL, ActionId.PLANT, ActionId.WATER, ActionId.POUR_WATER, ActionId.FERTILIZE,
        ActionId.HARVEST_CROP,
        ActionId.REMOVE_DEAD_CROP, ActionId.STORE_PRODUCE -> GoalId.FARM
        ActionId.STUDY -> GoalId.STUDY
        ActionId.WORK -> GoalId.WORK
        ActionId.TRAIN_BOXING -> GoalId.TRAIN
        else -> GoalId.IDLE
    }

    private fun hasUsableTool(itemId: String, requiredTool: String): Boolean {
        val normalizedItem = itemId.trim().lowercase()
        val normalizedTool = requiredTool.trim().lowercase()
        return normalizedItem == normalizedTool || normalizedItem.startsWith("${normalizedTool}_")
    }

    private fun inventoryStackIs(stack: InventoryStack, kind: InventoryKind): Boolean =
        if (stack.kind == InventoryKind.OTHER) InventoryKinds.infer(stack.itemId) == kind else stack.kind == kind

    private fun inventoryKind(pet: CanonicalPetSnapshot?, itemId: String): InventoryKind? {
        val stack = pet?.inventory.orEmpty().firstOrNull { stack ->
            stackMatchesItem(stack, itemId)
        }
        return stack?.kind?.takeUnless { it == InventoryKind.OTHER }
            ?: stack?.let { InventoryKinds.infer(it.itemId) }
    }

    private fun stackMatchesItem(stack: InventoryStack, requestedItemId: String): Boolean {
        if (stack.quantity <= 0) return false
        val requested = requestedItemId.trim().lowercase()
        val inferred = stack.kind.takeUnless { it == InventoryKind.OTHER }
            ?: InventoryKinds.infer(stack.itemId)
        return stack.itemId == requestedItemId ||
            (requested == "seed" && inferred == InventoryKind.SEED) ||
            (requested == "water" && inferred == InventoryKind.WATER)
    }

    private fun CanonicalPetSnapshot?.isFrozenOrEgg(): Boolean = this?.cycleFrozen == true || this?.isEgg == true

    private fun policyPath(state: WorldState, decision: NavigationDecision): List<WorldCoordinate>? {
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

    private fun boundedNavigationMemory(decision: NavigationDecision?, previous: List<Float>): List<Float> =
        decision?.nextMemory?.take(64)?.map { if (it.isFinite()) it else 0f } ?: previous

    private data class CommandApplication(
        val state: WorldState,
        val accepted: Boolean,
        val reason: String? = null
    )
}
