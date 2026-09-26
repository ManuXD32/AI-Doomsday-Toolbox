package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldIndoorSelfTargetTest {
    @Test
    fun indoorRestUsesTargetlessTargetAndCompletes() {
        val generated = WorldGenerator.generate(611L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val initial = generated.copy(
            actor = generated.actor.at(home.entrance).copy(
                presence = PresenceMode.HOME,
                structureId = home.id,
                goal = GoalId.REST,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                actionTargetId = null,
                actionTargetX = null,
                actionTargetY = null
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 60f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)
        )

        var state = initial
        var completion: SimulationResult? = null
        repeat(ActionRegistry[ActionId.REST].durationTicks + 1) { index ->
            val result = WorldSimulation.step(
                state = state,
                canonicalPetSnapshot = pet,
                now = (index + 1L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            state = result.state
            if (state.actor.action == ActionId.REST && state.actor.actionState == ActionState.COMPLETED) {
                completion = result
            }
        }

        assertNotNull(completion)
        assertEquals(ActionState.COMPLETED, state.actor.actionState)
        assertNull(state.actor.actionTargetId)
        assertNull(state.actor.actionTargetX)
        assertNull(state.actor.actionTargetY)
        assertTrue(completion!!.effects.any {
            it is WorldEffectRequest.NeedDelta &&
                it.need == NeedType.ENERGY && it.delta == 2f
        })
        assertTrue(completion!!.observations.none { it.contains("action_blocked:REST") })
    }

    @Test
    fun criticalEnergySleepCompletesWithoutTileTargetValidation() {
        val generated = WorldGenerator.generate(612L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val initial = generated.copy(
            actor = generated.actor.at(home.entrance).copy(
                presence = PresenceMode.HOME,
                structureId = home.id,
                goal = GoalId.SLEEP,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                actionTargetId = null,
                actionTargetX = null,
                actionTargetY = null
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 5f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)
        )

        var state = initial
        var completion: SimulationResult? = null
        repeat(ActionRegistry[ActionId.SLEEP].durationTicks + 1) { index ->
            val result = WorldSimulation.step(
                state = state,
                canonicalPetSnapshot = pet,
                now = (index + 1L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            state = result.state
            if (state.actor.action == ActionId.SLEEP && state.actor.actionState == ActionState.COMPLETED) {
                completion = result
            }
        }

        assertNotNull(completion)
        assertEquals(ActionState.COMPLETED, state.actor.actionState)
        assertTrue(completion!!.effects.any {
            it is WorldEffectRequest.Activity && it.activity == PetActivity.SLEEPING
        })
        assertTrue(completion!!.effects.any {
            it is WorldEffectRequest.NeedDelta && it.need == NeedType.HEALTH && it.delta == .5f
        })
        assertTrue(completion!!.observations.none { it.contains("target_type_not_allowed") })
    }

    @Test
    fun savedBlockedSelfTargetCanBeExplicitlyRetried() {
        val generated = WorldGenerator.generate(613L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val blocked = generated.copy(
            actor = generated.actor.at(home.entrance).copy(
                presence = PresenceMode.HOME,
                structureId = home.id,
                goal = GoalId.RECOVER_STUCK,
                action = ActionId.REST,
                actionState = ActionState.BLOCKED,
                actionTicksRemaining = 0,
                // This is the shape written by the pre-fix blocked snapshot.
                actionTargetX = home.entrance.x,
                actionTargetY = home.entrance.y,
                actionArguments = emptyMap(),
                pendingCommand = null
            ),
            npcs = emptyList(),
            lastSimulatedAt = 1_000L
        )
        val reloaded = WorldStateCodec.roundTrip(blocked)
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 60f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )

        val first = WorldSimulation.step(
            state = reloaded,
            command = WorldCommand.PerformAction(ActionId.REST),
            canonicalPetSnapshot = pet,
            now = 1_100L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(first.acceptedCommand)
        assertEquals(ActionId.REST, first.state.actor.action)
        assertEquals(ActionState.RUNNING, first.state.actor.actionState)
        assertNull(first.state.actor.actionTargetX)
        assertNull(first.state.actor.actionTargetY)

        var state = first.state
        var completion = first
        repeat(ActionRegistry[ActionId.REST].durationTicks - 1) { index ->
            completion = WorldSimulation.step(
                state = state,
                canonicalPetSnapshot = pet,
                now = 1_200L + index * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            state = completion.state
        }
        assertEquals(ActionState.COMPLETED, state.actor.actionState)
        assertTrue(completion.observations.none { it.contains("action_blocked:REST") })
        assertFalse(state.actor.actionState == ActionState.BLOCKED)
    }

    @Test
    fun catchUpEventRetainsItsOwnTickContext() {
        val generated = WorldGenerator.generate(614L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val initial = generated.copy(
            actor = generated.actor.at(home.entrance).copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.RETURN_HOME,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE,
                destinationX = home.entrance.x,
                destinationY = home.entrance.y,
                pendingStructureId = home.id,
                actionTargetId = null,
                actionTargetX = null,
                actionTargetY = null
            ),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val needs = NeedsProjection(hunger = 41f, hydration = 52f, energy = 63f)
        val result = WorldSimulation.catchUp(
            state = initial,
            canonicalPetSnapshot = CanonicalPetSnapshot(
                needs = needs,
                autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)
            ),
            now = 3L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        val entered = result.effects.filterIsInstance<WorldEffectRequest.Event>()
            .first { it.eventType == "entered_structure" }
        val context = entered.context
        assertNotNull(context)
        assertEquals(DEFAULT_TICK_MILLIS, context!!.timestamp)
        assertEquals(home.entrance, context.actorCoordinate)
        assertEquals(WorldGenerator.biomeAt(result.state, home.entrance.x, home.entrance.y), context.biome)
        assertEquals(initial.actor.goal, context.beforeGoal)
        assertEquals(initial.actor.action, context.beforeAction)
        assertEquals(needs, context.needs)
        assertEquals(3L * DEFAULT_TICK_MILLIS, result.state.lastSimulatedAt)
    }

    @Test
    fun npcEventContextUsesTheNpcActorFacts() {
        val generated = WorldGenerator.generate(615L)
        val source = generated.npcs.first()
        val target = generated.npcs[1].copy(
            id = "context_target_npc",
            x = source.x + 1,
            y = source.y,
            preciseX = source.x + 1.0,
            preciseY = source.y.toDouble()
        )
        val actor = WorldActor(
            actorId = source.id,
            actorType = ActorType.NPC,
            x = source.x,
            y = source.y,
            preciseX = source.x.toDouble(),
            preciseY = source.y.toDouble(),
            presence = PresenceMode.WORLD,
            structureId = null,
            goal = GoalId.SOCIALIZE,
            action = ActionId.GREET,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            actionTargetId = target.id,
            actionTargetX = target.x,
            actionTargetY = target.y
        )
        val initial = generated.copy(
            actor = actor,
            npcs = listOf(target),
            explored = (generated.explored + actor.coordinate + target.coordinate).distinct(),
            lastSimulatedAt = 0L
        )
        val needs = NeedsProjection(hunger = 33f, hydration = 44f, energy = 55f)
        val result = WorldSimulation.step(
            state = initial,
            canonicalPetSnapshot = CanonicalPetSnapshot(
                petId = source.id,
                needs = needs,
                autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
            ),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )

        val event = result.effects.filterIsInstance<WorldEffectRequest.Event>()
            .single { it.eventType == "npc_greet" }
        val context = event.context
        assertEquals(source.id, event.actorId)
        assertNotNull(context)
        assertEquals(actor.coordinate, context!!.actorCoordinate)
        assertEquals(actor.goal, context.beforeGoal)
        assertEquals(actor.action, context.beforeAction)
        assertEquals(needs, context.needs)
    }
}
