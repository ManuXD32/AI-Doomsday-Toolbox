package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldResourceGoalSafetyTest {
    @Test
    fun autonomousWoodGoalInteractsFromWalkableTileBesideSolidTree() {
        val target = WorldCoordinate(4, 3)
        val actorCoordinate = WorldCoordinate(3, 3)
        val explored = (0 until 8).flatMap { y -> (0 until 8).map { x -> WorldCoordinate(x, y) } }
        val tree = WorldObject(
            id = "solid_tree",
            type = WorldObjectType.TREE,
            x = target.x,
            y = target.y,
            quantity = 1
        )
        val blockedNeighbours = listOf(
            WorldCoordinate(3, 2),
            WorldCoordinate(4, 2),
            WorldCoordinate(5, 2),
            WorldCoordinate(5, 3),
            WorldCoordinate(3, 4),
            WorldCoordinate(4, 4),
            WorldCoordinate(5, 4)
        ).mapIndexed { index, coordinate ->
            WorldObject("tree_blocker_$index", WorldObjectType.TREE, coordinate.x, coordinate.y)
        }
        val state = WorldState(
            seed = 91L,
            width = 8,
            height = 8,
            actor = WorldActor(
                x = actorCoordinate.x,
                y = actorCoordinate.y,
                preciseX = actorCoordinate.x.toDouble(),
                preciseY = actorCoordinate.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.GATHER_WOOD,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                destinationX = target.x,
                destinationY = target.y
            ),
            explored = explored,
            roads = explored,
            objects = listOf(tree) + blockedNeighbours,
            knownResources = listOf(KnownResourcePatch(WorldObjectType.TREE, target.x, target.y))
        )
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("axe_starter", 1, InventoryKind.TOOL)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )

        val result = WorldSimulation.step(
            state = state,
            canonicalPetSnapshot = pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        assertTrue(result.acceptedCommand)
        assertEquals(ActionId.CHOP_TREE, result.state.actor.action)
        assertEquals(ActionState.RUNNING, result.state.actor.actionState)
        assertEquals(tree.id, result.state.actor.actionTargetId)
        assertFalse(result.state.actor.goal == GoalId.RECOVER_STUCK)
    }

    @Test
    fun depletedNearestResourceDoesNotHideAvailableKnownPatch() {
        val depleted = WorldObject(
            id = "depleted_tree",
            type = WorldObjectType.TREE,
            x = 4,
            y = 3,
            quantity = 0,
            state = "depleted"
        )
        val available = WorldObject(
            id = "available_tree",
            type = WorldObjectType.TREE,
            x = 6,
            y = 3,
            quantity = 1
        )
        val explored = (0 until 8).flatMap { y -> (0 until 8).map { x -> WorldCoordinate(x, y) } }
        val state = WorldState(
            seed = 92L,
            width = 8,
            height = 8,
            actor = WorldActor(
                x = 5,
                y = 3,
                preciseX = 5.0,
                preciseY = 3.0,
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.GATHER_WOOD,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                destinationX = depleted.x,
                destinationY = depleted.y
            ),
            explored = explored,
            roads = explored,
            objects = listOf(depleted, available),
            knownResources = listOf(
                KnownResourcePatch(WorldObjectType.TREE, depleted.x, depleted.y),
                KnownResourcePatch(WorldObjectType.TREE, available.x, available.y)
            )
        )
        val result = WorldSimulation.step(
            state = state,
            canonicalPetSnapshot = CanonicalPetSnapshot(
                inventory = listOf(InventoryStack("axe_starter", 1, InventoryKind.TOOL)),
                autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
            ),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        assertEquals(ActionId.CHOP_TREE, result.state.actor.action)
        assertEquals(available.id, result.state.actor.actionTargetId)
    }

    @Test
    fun criticalHungerReplacesStaleWoodGoalBeforeResourceInteraction() {
        val target = WorldCoordinate(4, 3)
        val explored = (0 until 8).flatMap { y -> (0 until 8).map { x -> WorldCoordinate(x, y) } }
        val state = WorldState(
            seed = 93L,
            width = 8,
            height = 8,
            actor = WorldActor(
                x = 3,
                y = 3,
                preciseX = 3.0,
                preciseY = 3.0,
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.GATHER_WOOD,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                destinationX = target.x,
                destinationY = target.y
            ),
            explored = explored,
            roads = explored,
            objects = listOf(WorldObject("critical_guard_tree", WorldObjectType.TREE, target.x, target.y)),
            knownResources = listOf(KnownResourcePatch(WorldObjectType.TREE, target.x, target.y))
        )
        val result = WorldSimulation.step(
            state = state,
            canonicalPetSnapshot = CanonicalPetSnapshot(
                needs = NeedsProjection(hunger = 5f),
                inventory = listOf(InventoryStack("axe_starter", 1, InventoryKind.TOOL)),
                autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
            ),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        assertTrue(result.state.actor.action != ActionId.CHOP_TREE)
        assertTrue(result.state.actor.goal != GoalId.GATHER_WOOD)
    }
}
