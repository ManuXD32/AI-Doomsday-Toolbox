package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldCoarseCatchUpTest {
    private val coarseOptions = WorldSimulationOptions(
        maxCatchUpTicks = 200L,
        simulateNpcs = false
    )

    @Test
    fun coarseCatchUpStopsAtCanonicalCompletionAndDoesNotRepeatIt() {
        val generated = WorldGenerator.generate(901L)
        val state = generated.copy(
            actor = generated.actor.copy(
                action = ActionId.EAT,
                actionState = ActionState.RUNNING,
                actionTicksRemaining = 1,
                actionTargetId = "berry",
                actionArguments = mapOf(
                    "canonicalAction" to "feedWithFood",
                    "foodId" to "berry"
                )
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )

        val first = WorldSimulation.catchUp(state, pet, now = 201L * DEFAULT_TICK_MILLIS, options = coarseOptions)

        assertEquals(DEFAULT_TICK_MILLIS, first.state.lastSimulatedAt)
        assertEquals(ActionState.COMPLETED, first.state.actor.actionState)
        assertEquals(1, first.effects.filterIsInstance<WorldEffectRequest.CanonicalAction>().size)

        val resumed = WorldSimulation.catchUp(
            first.state,
            pet,
            now = 201L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )
        assertTrue(resumed.effects.none { it is WorldEffectRequest.CanonicalAction })
        assertEquals(201L * DEFAULT_TICK_MILLIS, resumed.state.lastSimulatedAt)
    }

    @Test
    fun shortCatchUpStopsAtActivityBoundaryBeforeContinuing() {
        val generated = WorldGenerator.generate(907L)
        val school = generated.structures.first { it.type == StructureType.SCHOOL }
        val state = generated.copy(
            actor = generated.actor.copy(
                presence = PresenceMode.INTERIOR,
                structureId = school.id,
                action = ActionId.STUDY,
                actionState = ActionState.RUNNING,
                actionTicksRemaining = 1,
                actionTargetId = school.id
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )

        val result = WorldSimulation.catchUp(
            state,
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = 200L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )

        assertEquals(DEFAULT_TICK_MILLIS, result.state.lastSimulatedAt)
        assertTrue(result.effects.any {
            it is WorldEffectRequest.Activity && it.activity == PetActivity.STUDY
        })
    }

    @Test
    fun coarseCatchUpCompletesNonCanonicalTimedActionBeforeAdapterResume() {
        val generated = WorldGenerator.generate(902L)
        val state = generated.copy(
            actor = generated.actor.copy(
                action = ActionId.EAT,
                actionState = ActionState.RUNNING,
                actionTicksRemaining = 1,
                actionTargetId = "berry"
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )

        val first = WorldSimulation.catchUp(state, pet, now = 201L * DEFAULT_TICK_MILLIS, options = coarseOptions)

        // EAT crosses the canonical inventory adapter boundary. The first
        // replay commits the completed action and stops before replaying
        // against the stale one-berry projection.
        assertEquals(DEFAULT_TICK_MILLIS, first.state.lastSimulatedAt)
        assertEquals(ActionState.COMPLETED, first.state.actor.actionState)
        assertEquals(1, first.effects.filterIsInstance<WorldEffectRequest.InventoryDelta>()
            .count { it.itemId == "berry" && it.quantity == -1 })
        assertTrue(first.effects.any { it is WorldEffectRequest.NeedDelta && it.need == NeedType.HUNGER })

        // The adapter has now committed the consumed stack. Resuming from
        // that projected snapshot may advance the remaining wall time, but
        // cannot emit a second consumption for the same action.
        val resumed = WorldSimulation.catchUp(
            first.state,
            pet.copy(inventory = emptyList()),
            now = 201L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )
        assertEquals(201L * DEFAULT_TICK_MILLIS, resumed.state.lastSimulatedAt)
        assertTrue(resumed.effects.none {
            it is WorldEffectRequest.InventoryDelta && it.itemId == "berry" && it.quantity == -1
        })
    }

    @Test
    fun coarseCatchUpLeavesSleepingPetStationary() {
        val generated = WorldGenerator.generate(903L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val next = adjacentWalkable(generated, start)
        val state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.WALK,
                actionState = ActionState.RUNNING,
                path = listOf(next),
                destinationX = next.x,
                destinationY = next.y
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )

        val result = WorldSimulation.catchUp(
            state,
            CanonicalPetSnapshot(sleeping = true, autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = 201L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )

        assertEquals(start, result.state.actor.coordinate)
        assertEquals(listOf(next), result.state.actor.path)
        assertFalse(result.effects.any {
            it is WorldEffectRequest.NeedDelta && it.need == NeedType.ENERGY
        })
    }

    @Test
    fun coarseCatchUpRechecksDynamicCollisionInsteadOfEnteringBlockedTile() {
        val generated = WorldGenerator.generate(904L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val next = adjacentWalkable(generated, start)
        val blocked = WorldObject("coarse_blocker", WorldObjectType.TREE, next.x, next.y, quantity = 1)
        val state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.WALK,
                actionState = ActionState.RUNNING,
                path = listOf(next),
                destinationX = next.x,
                destinationY = next.y
            ),
            objects = generated.objects + blocked,
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )

        val result = WorldSimulation.catchUp(
            state,
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = 201L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )

        assertEquals(start, result.state.actor.coordinate)
        assertEquals(ActionState.BLOCKED, result.state.actor.actionState)
        assertEquals(GoalId.RECOVER_STUCK, result.state.actor.goal)
        assertTrue(result.state.actor.path.isEmpty())
    }

    @Test
    fun coarseCatchUpDowngradesRunAndReturnsHomeWhenEnergyCannotPayWalk() {
        val generated = WorldGenerator.generate(905L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val path = (2..12).asSequence()
            .map { WorldCoordinate(start.x + it, start.y) }
            .filter(generated::contains)
            .map { Pathfinder.findPath(generated, start, it, knownOnly = false) }
            .first { it.size >= 2 }
        val state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.EXPLORE,
                action = ActionId.RUN,
                actionState = ActionState.RUNNING,
                path = path,
                destinationX = path.last().x,
                destinationY = path.last().y
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 12f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )

        val result = WorldSimulation.catchUp(state, pet, now = 201L * DEFAULT_TICK_MILLIS, options = coarseOptions)

        assertTrue(result.observations.contains("coarse:run_downgraded"))
        assertTrue(result.effects.any {
            it is WorldEffectRequest.NeedDelta && it.reason == "movement:walk"
        })
        assertTrue(result.effects.none {
            it is WorldEffectRequest.NeedDelta && it.reason == "movement:run"
        })
    }

    @Test
    fun coarseCatchUpCompletesStructureArrivalBeforePassiveJump() {
        val generated = WorldGenerator.generate(906L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val entrance = home.entrance
        val start = adjacentWalkable(generated, entrance)
        val state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.RETURN_HOME,
                action = ActionId.WALK,
                actionState = ActionState.RUNNING,
                destinationX = entrance.x,
                destinationY = entrance.y,
                pendingStructureId = home.id,
                path = listOf(entrance)
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )

        val result = WorldSimulation.catchUp(
            state,
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = 201L * DEFAULT_TICK_MILLIS,
            options = coarseOptions
        )

        assertEquals(PresenceMode.HOME, result.state.actor.presence)
        assertEquals(home.id, result.state.actor.structureId)
        assertTrue(result.observations.contains("entered:${home.id}"))
        assertEquals(201L * DEFAULT_TICK_MILLIS, result.state.lastSimulatedAt)
    }

    private fun adjacentWalkable(state: WorldState, origin: WorldCoordinate): WorldCoordinate = listOf(
        WorldCoordinate(origin.x + 1, origin.y),
        WorldCoordinate(origin.x - 1, origin.y),
        WorldCoordinate(origin.x, origin.y + 1),
        WorldCoordinate(origin.x, origin.y - 1)
    ).first { coordinate ->
        state.contains(coordinate) && Pathfinder.walkable(state, coordinate, knownOnly = false)
    }
}
