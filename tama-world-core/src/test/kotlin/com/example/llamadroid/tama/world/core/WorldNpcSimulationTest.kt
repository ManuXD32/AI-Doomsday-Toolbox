package com.example.llamadroid.tama.world.core

import org.junit.Assert.*
import org.junit.Test

class WorldNpcSimulationTest {
    @Test fun pixelPopGreetsThroughTheExecutorWithoutMovingOrAddingAResident() {
        val generated = WorldGenerator.generate(71)
        val pixel = generated.npcs.first { it.stationary }
        var world = generated.copy(petId = "living-pet", npcs = listOf(pixel),
            actor = generated.actor.at(pixel.coordinate).copy(presence = PresenceMode.WORLD))
        repeat(80) {
            world = WorldSimulation.step(world, canonicalPetSnapshot = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
                now = world.lastSimulatedAt + DEFAULT_TICK_MILLIS).state
        }
        assertEquals(pixel.coordinate, world.npcs.single().coordinate)
        assertTrue(world.npcs.single().relationships.getValue("living-pet").familiarity > 0)
        assertEquals(listOf(pixel.id), world.npcs.map { it.id })
    }

    @Test fun workerCompletesRealJobsAndKeepsEffectsOutOfPet() {
        val initial = workerWorld()
        var world = initial
        val pet = CanonicalPetSnapshot(isEgg = true)
        repeat(800) {
            val result = WorldSimulation.step(world, canonicalPetSnapshot = pet,
                now = world.lastSimulatedAt + DEFAULT_TICK_MILLIS)
            assertFalse(result.effects.any { it is WorldEffectRequest.InventoryDelta || it is WorldEffectRequest.MoneyDelta })
            world = result.state
        }
        val worker = world.npcs.single()
        assertTrue("NPC should finish work through the shared action executor", worker.completedJobs > 0)
        assertTrue(worker.money > 0)
        assertTrue(worker.inventory.any { it.itemId == "berry" && it.quantity > 8 })
        assertNotNull(worker.execution)
    }

    @Test fun patchFieldsAreSeparateAndAreActuallyTended() {
        val generated = WorldGenerator.generate(671)
        val patch = generated.npcs.first { it.id == "farm_farmer" }
        assertEquals(9, patch.ownFarm.size)
        assertTrue(patch.ownFarm.none { it.userOwned })
        assertTrue(patch.ownFarm.none { npcPlot -> generated.farmPlots.any { it.id == npcPlot.id || it.x == npcPlot.x && it.y == npcPlot.y } })
        val start = patch.ownFarm.first().let { WorldCoordinate(it.x, it.y) }
        val now = 9 * 3_600_000L
        var world = generated.copy(lastSimulatedAt = now,
            actor = generated.actor.at(start), npcs = listOf(patch.copy(x = start.x, y = start.y,
                preciseX = start.x.toDouble(), preciseY = start.y.toDouble(), lastSimulatedAt = now,
                scheduleState = "working")))
        repeat(300) {
            world = WorldSimulation.step(world, canonicalPetSnapshot = CanonicalPetSnapshot(isEgg = true),
                now = world.lastSimulatedAt + DEFAULT_TICK_MILLIS).state
        }
        assertTrue("Patch should plant and water his own fields", world.npcs.single().ownFarm.any { it.cropId != null })
        assertEquals(generated.farmPlots, world.farmPlots)
    }

    @Test fun activeNpcTenThousandTicksMatchSaveAndReload() {
        val initial = workerWorld()
        fun run(state: WorldState, count: Int): WorldState {
            var current = state
            repeat(count) {
                current = WorldSimulation.step(current, canonicalPetSnapshot = CanonicalPetSnapshot(isEgg = true),
                    now = current.lastSimulatedAt + DEFAULT_TICK_MILLIS).state
            }
            return current
        }
        val uninterrupted = run(initial, 10_000)
        val resumed = run(WorldStateCodec.roundTrip(run(initial, 4_321)), 5_679)
        assertTrue(uninterrupted.npcs.single().completedJobs > 1)
        assertEquals(uninterrupted, resumed)
    }

    @Test fun resourceVisualsAndRegrowthDoNotResetOnReload() {
        val generated = WorldGenerator.generate(71)
        val tree = (0 until generated.height).asSequence().flatMap { y -> (0 until generated.width).asSequence().mapNotNull { x ->
            WorldGenerator.objectAt(generated, x, y)?.takeIf { it.type == WorldObjectType.TREE }
        } }.first()
        assertFalse(WorldGenerator.tileAt(generated, tree.x, tree.y).walkable)
        val depleted = generated.copy(tick = 20, deltas = listOf(WorldDelta(tree.x, tree.y,
            WorldDeltaKind.RESOURCE, "consumed", 20, tree.id)))
        assertEquals("stump", WorldGenerator.objectAt(depleted, tree.x, tree.y)?.state)
        assertEquals(0, WorldGenerator.objectAt(WorldStateCodec.roundTrip(depleted), tree.x, tree.y)?.quantity)
        assertTrue(WorldGenerator.tileAt(depleted, tree.x, tree.y).walkable)
        val regrown = WorldStateCodec.roundTrip(depleted).copy(tick = 20 + 3 * 864_000L)
        assertEquals(tree.quantity, WorldGenerator.objectAt(regrown, tree.x, tree.y)?.quantity)
    }

    @Test fun resourceIntentsUseTypedTreeLogAndShellActions() {
        val cases = listOf(
            WorldObjectType.TREE to ActionId.CHOP_TREE,
            WorldObjectType.FALLEN_LOG to ActionId.GATHER_WOOD,
            WorldObjectType.SHELL to ActionId.PICK_UP
        )
        cases.forEachIndexed { index, (resourceType, expectedAction) ->
            val generated = WorldGenerator.generate(7_100L + index)
            val origin = generated.structures.first { it.type == StructureType.HOME }.entrance
            val now = 9L * 60L * 60L * 1_000L
            val npcDefinition = WorldNpcCatalog["dungeon_adventurer"]
            val npc = generated.npcs.first { it.id == npcDefinition.id }.copy(
                x = origin.x,
                y = origin.y,
                preciseX = origin.x.toDouble(),
                preciseY = origin.y.toDouble(),
                inventory = listOf(InventoryStack("axe", 1, InventoryKind.TOOL)),
                homeAnchor = origin,
                jobAnchor = origin,
                socialAnchor = origin,
                lastSimulatedAt = now - DEFAULT_TICK_MILLIS,
                lastDecisionAt = 0L,
                path = emptyList(),
                execution = null
            )
            val resource = WorldObject(
                id = "000_test_${resourceType.name.lowercase()}",
                type = resourceType,
                x = origin.x,
                y = origin.y,
                quantity = 1
            )
            val world = generated.copy(
                actor = generated.actor.at(origin),
                objects = listOf(resource),
                npcs = listOf(npc),
                lastSimulatedAt = now,
                petId = ""
            )

            val advanced = WorldNpcSimulation.advance(world, simulationRadius = 32)

            assertEquals(expectedAction, advanced.npcs.single().currentAction)
            if (resourceType == WorldObjectType.SHELL) {
                // Picking up a shell completes in the first executor tick.
                assertEquals(ActionState.COMPLETED, advanced.npcs.single().actionState)
                assertTrue(advanced.npcs.single().inventory.any { it.itemId == "shell" && it.quantity == 1 })
                assertEquals("A collected shell must be depleted for every world actor", 0,
                    WorldGenerator.objectAt(advanced, resource.x, resource.y)?.quantity)
                assertEquals(0, WorldGenerator.objectAt(WorldStateCodec.roundTrip(advanced),
                    resource.x, resource.y)?.quantity)
                assertEquals("NPC collection cannot mutate the player actor", world.actor, advanced.actor)
            } else assertEquals(ActionState.RUNNING, advanced.npcs.single().actionState)
        }
    }

    @Test fun longGapCompletesARealJobAndLeavesCanonicalPlayerStateUntouched() {
        val initial = workerWorld()
        val start = 9L * 3_600_000L
        val end = 10L * 3_600_000L
        val worker = initial.npcs.single().copy(
            lastSimulatedAt = start,
            lastDecisionAt = 0L,
            currentAction = ActionId.WAIT,
            actionState = ActionState.IDLE,
            inventory = initial.npcs.single().inventory.filterNot { it.itemId == "water" },
            path = emptyList(),
            execution = null,
            scheduleState = "working"
        )
        val world = initial.copy(
            actor = initial.actor.copy(needs = NeedsProjection(hunger = 77f, hydration = 71f)),
            npcs = listOf(worker),
            lastSimulatedAt = end,
            tick = end / DEFAULT_TICK_MILLIS
        )

        val advanced = WorldNpcSimulation.advance(
            world,
            simulationRadius = 32,
            coarseTicks = (end - start) / DEFAULT_TICK_MILLIS
        )
        val result = advanced.npcs.single()

        assertTrue("A skipped hour must still complete work through the executor", result.completedJobs > worker.completedJobs)
        assertTrue("The worker's product must stay in the NPC inventory", result.inventory.any { it.itemId == "berry" && it.quantity > 8 })
        assertEquals("Completing work must refill the NPC's own water supply", 4,
            result.inventory.first { it.itemId == "water" }.quantity)
        assertTrue("The worker's own money must be updated", result.money > worker.money)
        assertEquals(end, result.lastSimulatedAt)
        assertEquals("NPC work must not mutate the canonical actor", world.actor, advanced.actor)
        assertEquals("NPC work must not mutate player farms", world.farmPlots, advanced.farmPlots)
        assertEquals("NPC work must not mutate player objects", world.objects, advanced.objects)
    }

    @Test fun fastNearAndFarNpcTicksApplyPassiveNeedDecay() {
        val generated = WorldGenerator.generate(4_272)
        val pixel = generated.npcs.first { it.stationary }
        val actorCoordinate = generated.actor.coordinate
        val nearNpc = pixel.copy(
            x = actorCoordinate.x,
            y = actorCoordinate.y,
            preciseX = actorCoordinate.x.toDouble(),
            preciseY = actorCoordinate.y.toDouble(),
            lastSimulatedAt = 0L,
            needs = NeedsProjection()
        )
        val nearWorld = generated.copy(
            actor = generated.actor.at(actorCoordinate),
            npcs = listOf(nearNpc),
            lastSimulatedAt = DEFAULT_TICK_MILLIS
        )
        val near = WorldNpcSimulation.advance(nearWorld, simulationRadius = 32)

        assertTrue("A near NPC tick must decay passive needs", near.npcs.single().needs.hunger < 100f)

        val farNpc = nearNpc.copy(
            x = actorCoordinate.x + 1,
            y = actorCoordinate.y,
            preciseX = actorCoordinate.x + 1.0,
            preciseY = actorCoordinate.y.toDouble(),
            lastSimulatedAt = 0L
        )
        val farWorld = nearWorld.copy(
            npcs = listOf(farNpc),
            lastSimulatedAt = FAR_CADENCE_MILLIS
        )
        val far = WorldNpcSimulation.advance(farWorld, simulationRadius = 0)

        assertTrue("A far NPC tick must decay passive needs", far.npcs.single().needs.hunger < 100f)
    }

    @Test fun longGapExecutesAnNpcFarmActionWithoutTouchingPlayerFarm() {
        val generated = WorldGenerator.generate(671)
        val farmer = generated.npcs.first { it.id == "farm_farmer" }
        val originalPlot = farmer.ownFarm.first()
        val plot = originalPlot.copy(
            status = "wet_farmland",
            cropId = "carrot",
            isReady = true,
            isDead = false,
            userOwned = false,
            plantedAtTick = 0L
        )
        val plotCoordinate = WorldCoordinate(plot.x, plot.y)
        val start = 9L * 3_600_000L
        val end = 10L * 3_600_000L
        val npc = farmer.copy(
            x = plotCoordinate.x,
            y = plotCoordinate.y,
            preciseX = plotCoordinate.x.toDouble(),
            preciseY = plotCoordinate.y.toDouble(),
            homeAnchor = plotCoordinate,
            jobAnchor = plotCoordinate,
            socialAnchor = plotCoordinate,
            ownFarm = listOf(plot),
            lastSimulatedAt = start,
            lastDecisionAt = 0L,
            currentAction = ActionId.WAIT,
            actionState = ActionState.IDLE,
            path = emptyList(),
            execution = null,
            scheduleState = "working"
        )
        val world = generated.copy(
            actor = generated.actor.at(plotCoordinate),
            npcs = listOf(npc),
            lastSimulatedAt = end,
            tick = end / DEFAULT_TICK_MILLIS
        )

        val advanced = WorldNpcSimulation.advance(
            world,
            simulationRadius = 32,
            coarseTicks = (end - start) / DEFAULT_TICK_MILLIS
        )
        val result = advanced.npcs.single()

        assertTrue("The real farm transition must produce a crop", result.inventory.any { it.itemId == "crop_carrot" && it.quantity > 0 })
        assertNotEquals("The NPC farm plot must be updated by physical farm actions", plot, result.ownFarm.single())
        assertEquals(end, result.lastSimulatedAt)
        assertEquals("NPC farm state must not replace player farm rows", world.farmPlots, advanced.farmPlots)
        assertEquals("NPC farm state must not move the player", world.actor, advanced.actor)
    }

    @Test fun longGapUsesCalendarPhaseAndLeavesStationaryPixelInPlace() {
        val generated = WorldGenerator.generate(4_271)
        val sellerDefinition = WorldNpcCatalog["shop_seller"]
        val seller = generated.npcs.first { it.id == sellerDefinition.id }
        val jobStructure = generated.structures.first { it.id == seller.jobStructureId }
        val position = jobStructure.entrance
        // Isolate the evening schedule transition while needs remain healthy.
        // A fourteen-hour gap legitimately makes food/water care more urgent than sleep.
        val start = 20L * 3_600_000L
        val end = 23L * 3_600_000L
        val scheduled = seller.copy(
            x = position.x,
            y = position.y,
            preciseX = position.x.toDouble(),
            preciseY = position.y.toDouble(),
            homeAnchor = position,
            jobAnchor = position,
            socialAnchor = position,
            lastSimulatedAt = start,
            lastDecisionAt = 0L,
            currentAction = ActionId.WAIT,
            actionState = ActionState.IDLE,
            path = emptyList(),
            execution = null,
            scheduleState = "working"
        )
        val world = generated.copy(
            actor = generated.actor.at(position),
            npcs = listOf(scheduled),
            lastSimulatedAt = end,
            tick = end / DEFAULT_TICK_MILLIS
        )

        val advanced = WorldNpcSimulation.advance(
            world,
            simulationRadius = 32,
            coarseTicks = (end - start) / DEFAULT_TICK_MILLIS
        )
        val result = advanced.npcs.single()

        assertEquals("The final 23:00 phase must be home/sleeping", "sleeping", result.scheduleState)
        assertEquals(PresenceMode.HOME, result.execution?.presence)
        assertEquals("The executor must not teleport the scheduled NPC", position, result.coordinate)
        assertEquals(end, result.lastSimulatedAt)

        val pixel = generated.npcs.first { it.stationary }
        val day = 24L * 3_600_000L
        val pixelWorld = generated.copy(
            actor = generated.actor.at(pixel.coordinate),
            npcs = listOf(pixel.copy(lastSimulatedAt = 0L)),
            lastSimulatedAt = day,
            tick = day / DEFAULT_TICK_MILLIS
        )
        val pixelAdvanced = WorldNpcSimulation.advance(
            pixelWorld,
            simulationRadius = 32,
            coarseTicks = day / DEFAULT_TICK_MILLIS
        )
        val pixelResult = pixelAdvanced.npcs.single()

        assertEquals("Stationary Pixel must remain at the generated coordinate", pixel.coordinate, pixelResult.coordinate)
        assertEquals("Stationary Pixel must retain its stationary phase", "stationary", pixelResult.scheduleState)
        assertEquals(day, pixelResult.lastSimulatedAt)
        assertEquals(pixelWorld.actor, pixelAdvanced.actor)
    }

    private fun workerWorld(): WorldState {
        val generated = WorldGenerator.generate(182)
        val seller = generated.npcs.first { it.id == "shop_seller" }
        val position = generated.structures.first { it.id == seller.jobStructureId }.entrance
        val now = 9 * 3_600_000L
        return generated.copy(lastSimulatedAt = now, actor = generated.actor.at(position),
            npcs = listOf(seller.copy(x = position.x, y = position.y, preciseX = position.x.toDouble(),
                preciseY = position.y.toDouble(), lastSimulatedAt = now, scheduleState = "working")))
    }

    private companion object {
        const val FAR_CADENCE_MILLIS = 1_000L
    }
}
