package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString

class WorldOrderLifecycleTest {
    @Test
    fun legacyActorJsonDefaultsToAutonomousWithoutAnOrder() {
        val actor = WorldStateCodec.json.decodeFromString<WorldActor>("{}")
        assertEquals(WorldControlMode.AUTONOMOUS, actor.controlMode)
        assertEquals(WorldOrderStatus.NONE, actor.orderStatus)
        assertEquals(WorldOrderBlocker.NONE, actor.orderBlocker)
        assertEquals(null, actor.explicitOrder)
    }

    @Test
    fun explicitRouteUsesCorePathAndHoldsAfterArrival() {
        val generated = WorldGenerator.generate(101L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val target = listOf(
            WorldCoordinate(start.x + 1, start.y),
            WorldCoordinate(start.x - 1, start.y),
            WorldCoordinate(start.x, start.y + 1),
            WorldCoordinate(start.x, start.y - 1)
        ).first { generated.contains(it) && WorldGenerator.tileAt(generated, it.x, it.y).walkable }
        val world = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = (generated.explored + start + target).distinct(),
            npcs = emptyList()
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        val policy = NavigationPolicy { _, _, _ -> NavigationDecision(NavigationIntent.WAIT) }

        val arrived = WorldSimulation.step(
            world,
            WorldCommand.GoTo(target.x, target.y),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(navigationPolicy = policy, simulateNpcs = false)
        )

        assertTrue(arrived.acceptedCommand)
        assertEquals(target, arrived.state.actor.coordinate)
        assertEquals(WorldControlMode.HOLDING, arrived.state.actor.controlMode)
        assertEquals(WorldOrderStatus.COMPLETED, arrived.state.actor.orderStatus)
        assertEquals(ActionId.WAIT, arrived.state.actor.action)
        assertEquals(GoalId.IDLE, arrived.state.actor.goal)

        val held = WorldSimulation.step(
            arrived.state,
            canonicalPetSnapshot = pet,
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(navigationPolicy = policy, simulateNpcs = false)
        )
        assertEquals(target, held.state.actor.coordinate)
        assertEquals(WorldControlMode.HOLDING, held.state.actor.controlMode)
        assertTrue(held.observations.none { it.startsWith("goal:") })
    }

    @Test
    fun frozenPetBlocksAnActiveRouteWithDurableReason() {
        val generated = WorldGenerator.generate(109L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val destination = WorldCoordinate(start.x + 1, start.y)
        val order = WorldOrder(WorldOrderKind.GO_TO, x = destination.x, y = destination.y)
        val actor = generated.actor.copy(
            x = start.x,
            y = start.y,
            preciseX = start.x.toDouble(),
            preciseY = start.y.toDouble(),
            presence = PresenceMode.WORLD,
            structureId = null,
            path = listOf(destination),
            destinationX = destination.x,
            destinationY = destination.y,
            action = ActionId.WALK,
            actionState = ActionState.RUNNING,
            controlMode = WorldControlMode.ORDER_ACTIVE,
            orderStatus = WorldOrderStatus.RUNNING,
            explicitOrderId = "frozen-route",
            explicitOrder = order
        )
        val state = generated.copy(actor = actor, npcs = emptyList())
        val result = WorldSimulation.step(
            state,
            canonicalPetSnapshot = CanonicalPetSnapshot(cycleFrozen = true),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        assertEquals(WorldControlMode.BLOCKED, result.state.actor.controlMode)
        assertEquals(WorldOrderStatus.BLOCKED, result.state.actor.orderStatus)
        assertEquals(WorldOrderBlocker.PET_CANNOT_MOVE, result.state.actor.orderBlocker)
        assertEquals(order, result.state.actor.explicitOrder)
        assertEquals("frozen-route", result.state.actor.explicitOrderId)
        assertEquals(ActionId.WAIT, result.state.actor.action)
    }

    @Test
    fun stopPersistsHoldAndResumeRequiresEnabledAutonomy() {
        val generated = WorldGenerator.generate(102L)
        val world = generated.copy(actor = generated.actor.copy(
            presence = PresenceMode.WORLD,
            structureId = null
        ), npcs = emptyList())
        val fullPet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        val started = WorldSimulation.step(
            world,
            WorldCommand.GoTo(world.actor.x + 6, world.actor.y),
            fullPet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(started.acceptedCommand)
        val stopped = WorldSimulation.step(
            started.state,
            WorldCommand.Stop,
            fullPet,
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(WorldControlMode.HOLDING, stopped.state.actor.controlMode)
        assertEquals(WorldOrderStatus.CANCELLED, stopped.state.actor.orderStatus)
        val held = WorldSimulation.step(
            stopped.state,
            canonicalPetSnapshot = fullPet,
            now = 3L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(stopped.state.actor.coordinate, held.state.actor.coordinate)
        assertEquals(WorldControlMode.HOLDING, held.state.actor.controlMode)

        val disabled = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val rejected = WorldSimulation.step(
            stopped.state,
            WorldCommand.ResumeAutonomy,
            disabled,
            now = 4L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(rejected.acceptedCommand)
        assertEquals("autonomy_disabled", rejected.rejectionReason)
        assertEquals(stopped.state, rejected.state)
        assertTrue(rejected.effects.isEmpty())

        val resumed = WorldSimulation.step(
            stopped.state,
            WorldCommand.ResumeAutonomy,
            fullPet,
            now = 5L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(resumed.acceptedCommand)
        assertEquals(WorldControlMode.AUTONOMOUS, resumed.state.actor.controlMode)
        assertEquals(WorldOrderStatus.NONE, resumed.state.actor.orderStatus)
    }

    @Test
    fun invalidReplacementLeavesInteriorActivityAndEffectsUntouched() {
        val generated = WorldGenerator.generate(103L)
        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val pending = PendingActivityIntent("WORLD_ACTION", shop.id, mapOf("actionId" to ActionId.WORK.name))
        val state = generated.copy(actor = generated.actor.copy(
            x = shop.entrance.x,
            y = shop.entrance.y,
            preciseX = shop.entrance.x.toDouble(),
            preciseY = shop.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = shop.id,
            pendingActivity = pending
        ))
        val result = WorldSimulation.step(
            state,
            WorldCommand.GoTo(-1, -1),
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(result.acceptedCommand)
        assertEquals("destination_outside_world", result.rejectionReason)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun movementReplacementCannotInterruptTimedAction() {
        val generated = WorldGenerator.generate(107L)
        val actor = generated.actor.copy(
            presence = PresenceMode.WORLD,
            structureId = null,
            action = ActionId.EAT,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 3,
            actionTargetId = "berry"
        )
        val state = generated.copy(actor = actor, npcs = emptyList())
        val result = WorldSimulation.step(
            state,
            WorldCommand.GoTo(actor.x + 1, actor.y),
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(result.acceptedCommand)
        assertEquals("action_in_progress", result.rejectionReason)
        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun completionFailureRetainsExplicitIntentAndTypedBlocker() {
        val generated = WorldGenerator.generate(108L)
        val coordinate = WorldCoordinate(generated.actor.x + 1, generated.actor.y)
        val depleted = WorldObject(
            id = "depleted_order_target",
            type = WorldObjectType.BERRY_PATCH,
            x = coordinate.x,
            y = coordinate.y,
            state = "empty",
            quantity = 0
        )
        val order = WorldOrder(
            kind = WorldOrderKind.PERFORM_ACTION,
            action = ActionId.FORAGE,
            targetId = depleted.id
        )
        val actor = generated.actor.copy(
            presence = PresenceMode.WORLD,
            structureId = null,
            x = coordinate.x,
            y = coordinate.y,
            preciseX = coordinate.x.toDouble(),
            preciseY = coordinate.y.toDouble(),
            action = ActionId.FORAGE,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            actionTargetId = depleted.id,
            actionTargetX = coordinate.x,
            actionTargetY = coordinate.y,
            explicitOrderId = "order-blocked",
            explicitOrder = order,
            controlMode = WorldControlMode.ORDER_ACTIVE,
            orderStatus = WorldOrderStatus.RUNNING
        )
        val result = WorldSimulation.step(
            generated.copy(actor = actor, objects = listOf(depleted), npcs = emptyList()),
            canonicalPetSnapshot = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(WorldControlMode.BLOCKED, result.state.actor.controlMode)
        assertEquals(WorldOrderStatus.BLOCKED, result.state.actor.orderStatus)
        assertEquals(WorldOrderBlocker.COUNTERPARTY_MISSING, result.state.actor.orderBlocker)
        assertEquals("order-blocked", result.state.actor.explicitOrderId)
        assertEquals(order, result.state.actor.explicitOrder)
    }

    @Test
    fun validReplacementGetsNewOrderIdentityAndRetryPreservesBlockedIdentity() {
        val generated = WorldGenerator.generate(104L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val known = (0..12).flatMap { dy ->
            (0..12).map { dx -> WorldCoordinate(start.x + dx, start.y + dy) }
        }.filter { generated.contains(it) && WorldGenerator.tileAt(generated, it.x, it.y).walkable }
        val state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = (generated.explored + known).distinct(),
            npcs = emptyList()
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val firstTarget = known.first {
            it != start && Pathfinder.findPath(state, start, it, knownOnly = true).isNotEmpty()
        }
        val first = WorldSimulation.step(
            state,
            WorldCommand.GoTo(firstTarget.x, firstTarget.y),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(observeRadius = 0, simulateNpcs = false)
        )
        assertTrue(first.acceptedCommand)
        val firstId = first.state.actor.explicitOrderId
        assertNotNull(firstId)
        val replacement = WorldSimulation.step(
            first.state,
            WorldCommand.GoTo(start.x, start.y),
            pet,
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(observeRadius = 0, simulateNpcs = false)
        )
        assertTrue(replacement.acceptedCommand)
        assertNotEquals(firstId, replacement.state.actor.explicitOrderId)

        val blocked = replacement.state.copy(actor = replacement.state.actor.copy(
            controlMode = WorldControlMode.BLOCKED,
            orderStatus = WorldOrderStatus.BLOCKED,
            orderBlocker = WorldOrderBlocker.KNOWN_FRONTIER,
            explicitOrder = requireNotNull(replacement.state.actor.explicitOrder).copy(
                outcome = WorldOrderOutcome(
                    needDeltas = mapOf(NeedType.HUNGER to -2f),
                    moneyDelta = 7L,
                    inventoryDeltas = mapOf("berry" to 1)
                )
            )
        ))
        val retry = WorldSimulation.step(
            blocked,
            WorldCommand.Retry,
            pet,
            now = 3L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(observeRadius = 0, simulateNpcs = false)
        )
        assertTrue(retry.acceptedCommand)
        assertEquals(blocked.actor.explicitOrderId, retry.state.actor.explicitOrderId)
        assertEquals(blocked.actor.explicitOrder?.outcome, retry.state.actor.explicitOrder?.outcome)
    }

    @Test
    fun visitNpcTracksTargetIdThenHoldsWithoutContinuousFollow() {
        val generated = WorldGenerator.generate(105L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val destination = (2..8).asSequence()
            .map { WorldCoordinate(start.x + it, start.y) }
            .first { generated.contains(it) && WorldGenerator.tileAt(generated, it.x, it.y).walkable }
        val npc = generated.npcs.first().copy(x = destination.x, y = destination.y)
        val explored = (0..12).flatMap { dy ->
            (0..12).map { dx -> WorldCoordinate(start.x + dx, start.y + dy) }
        }.filter { generated.contains(it) && WorldGenerator.tileAt(generated, it.x, it.y).walkable }
        var state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            npcs = listOf(npc),
            explored = (generated.explored + explored).distinct()
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        var result = WorldSimulation.step(
            state,
            WorldCommand.VisitNpc(npc.id),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(observeRadius = 0, simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        state = result.state
        repeat(12) {
            if (state.actor.controlMode == WorldControlMode.HOLDING) return@repeat
            result = WorldSimulation.step(
                state,
                canonicalPetSnapshot = pet,
                now = (it + 2L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(observeRadius = 0, simulateNpcs = false)
            )
            state = result.state
        }
        assertEquals(WorldControlMode.HOLDING, state.actor.controlMode)
        assertEquals(WorldOrderStatus.COMPLETED, state.actor.orderStatus)
        assertEquals(npc.id, state.actor.explicitOrder?.targetId)
        assertEquals(null, state.actor.followTargetId)
        assertTrue(state.actor.coordinate.chebyshevDistanceTo(state.npcs.single().coordinate) <= 1)
    }

    @Test
    fun stopRequestsCanonicalActivityCancellationButKeepsActorHeld() {
        val generated = WorldGenerator.generate(106L)
        val result = WorldSimulation.step(
            generated,
            WorldCommand.Stop,
            CanonicalPetSnapshot(activity = PetActivity.WORK),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        assertTrue(result.effects.any {
            it is WorldEffectRequest.Activity && it.activity == PetActivity.NONE && it.reason == "world:stop"
        })
        assertEquals(WorldControlMode.HOLDING, result.state.actor.controlMode)
    }
}
