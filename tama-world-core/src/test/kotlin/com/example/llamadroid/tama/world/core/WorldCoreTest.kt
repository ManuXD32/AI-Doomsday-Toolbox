package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldCoreTest {
    @Test
    fun actionRegistryContainsEverySpecifiedAction() {
        assertEquals(ActionId.entries.toSet(), ActionRegistry.ids)
        assertTrue(ActionRegistry[ActionId.TILL_SOIL].worldEffects.contains("farm:till"))
        assertTrue(ActionRegistry[ActionId.ENTER_DUNGEON].validTargets.contains(ActionTargetKind.STRUCTURE))
        assertEquals(AutonomyLevel.SAFE, AutonomyPolicy().level)
        assertFalse(AutonomyPolicy().allowHarvestingUserCrops)

        val userCropContext = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = WorldCoordinate(4, 4),
            target = ActionTarget(ActionTargetKind.FARM_PLOT, "farm_plot_0", WorldCoordinate(5, 4)),
            atFarm = true,
            userOwnedCrop = true,
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowFarming = true)
        )
        assertFalse(ActionExecutor.validate(ActionId.HARVEST_CROP, userCropContext).valid)
        assertTrue(ActionExecutor.validate(
            ActionId.HARVEST_CROP,
            userCropContext.copy(autonomy = userCropContext.autonomy.copy(allowHarvestingUserCrops = true))
        ).valid)
        assertTrue(ActionExecutor.validate(
            ActionId.HARVEST_CROP,
            userCropContext.copy(userOwnedCrop = false)
        ).valid)
        assertFalse(ActionExecutor.validate(
            ActionId.PLANT,
            userCropContext.copy(
                target = ActionTarget(ActionTargetKind.FARM_PLOT, "farm_plot_0", WorldCoordinate(4, 4)),
                autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowFarming = true)
            )
        ).valid)
        assertFalse(ActionExecutor.validate(
            ActionId.POUR_WATER,
            userCropContext.copy(
                target = ActionTarget(ActionTargetKind.FARM_PLOT, "farm_plot_0", WorldCoordinate(4, 4)),
                autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowFarming = true)
            )
        ).valid)
        assertTrue(ActionRegistry[ActionId.POUR_WATER].inventoryInputs.single().itemId == "water")
        assertTrue(ActionRegistry[ActionId.FERTILIZE].inventoryInputs.single().itemId == "fertilizer")

        val invalidFood = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = WorldCoordinate(0, 0),
            target = ActionTarget(ActionTargetKind.ITEM, "hoe_starter"),
            hasItem = { true },
            itemKind = { InventoryKind.TOOL }
        )
        assertFalse(ActionExecutor.validate(ActionId.EAT, invalidFood).valid)
        assertFalse(ActionExecutor.validate(
            ActionId.DRINK,
            invalidFood.copy(target = ActionTarget(ActionTargetKind.OBJECT, "tree"))
        ).valid)

        val medicine = invalidFood.copy(
            target = ActionTarget(ActionTargetKind.ITEM, "healing_potion"),
            hasItem = { it == "healing_potion" },
            itemKind = { InventoryKind.MEDICINE },
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)
        )
        assertFalse(ActionExecutor.validate(ActionId.USE_MEDICINE, medicine).valid)
        assertTrue(ActionExecutor.validate(
            ActionId.USE_MEDICINE,
            medicine.copy(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        ).valid)

        val alchemy = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = WorldCoordinate(0, 0),
            target = ActionTarget(
                kind = ActionTargetKind.STRUCTURE,
                id = LegacyLocationAliases.ALCHEMIST,
                arguments = mapOf(
                    "canonicalAction" to "brewAdventureGatePotion",
                    "itemId" to "herb",
                    "resultItemId" to "potion"
                )
            ),
            presence = PresenceMode.INTERIOR,
            structureType = StructureType.ALCHEMIST,
            hasItem = { it == "herb" },
            itemKind = { InventoryKind.MATERIAL }
        )
        assertTrue(ActionExecutor.validate(ActionId.USE_ALCHEMY, alchemy).valid)
        assertFalse(ActionExecutor.validate(
            ActionId.USE_ALCHEMY,
            alchemy.copy(presence = PresenceMode.WORLD)
        ).valid)
    }

    @Test
    fun oneHundredSeedsContainAllEightBiomesAndAllFacilities() {
        repeat(100) { seedIndex ->
            val state = WorldGenerator.generate(seedIndex.toLong(), structureLayouts = actualStructureLayouts())
            val biomes = buildSet {
                for (y in 0 until state.height step 4) {
                    for (x in 0 until state.width step 4) add(WorldGenerator.biomeAt(state, x, y))
                }
            }
            assertEquals(Biome.entries.toSet(), biomes)
            val structureIds = state.structures.map { it.id }.toSet()
            assertTrue(structureIds.containsAll(LegacyLocationAliases.allIds))
            assertEquals(18, structureIds.size)
            assertEquals(20, state.npcs.size)
            assertEquals(27, state.farmPlots.size)
            assertTrue(state.roads.isNotEmpty())
            state.structures.forEach { structure ->
                assertTrue("${structure.id} entrance", WorldGenerator.tileAt(state, structure.entrance.x, structure.entrance.y).walkable)
                assertTrue("${structure.id} road", state.roads.contains(structure.entrance))
                assertEquals(structure.height, structure.collisionMask.size)
                assertTrue(structure.collisionMask.all { it.size == structure.width })
            }
        }
    }

    @Test
    fun observedNpcLocationsReplacePreviousCoordinates() {
        val generated = WorldGenerator.generate(19L)
        val npc = generated.npcs.first()
        val first = WorldKnowledge.observe(
            generated.copy(actor = generated.actor.at(npc.coordinate), explored = listOf(npc.coordinate)),
            radius = 2,
            observedAt = 10L
        )
        val moved = npc.copy(x = npc.x + 1, preciseX = npc.x + 1.0)
        val second = WorldKnowledge.observe(
            first.copy(
                actor = first.actor.at(moved.coordinate),
                npcs = first.npcs.map { if (it.id == moved.id) moved else it },
                explored = first.explored + moved.coordinate
            ),
            radius = 2,
            observedAt = 20L
        )
        assertEquals(1, second.knownNpcs.count { it.npcId == npc.id })
        assertEquals(moved.coordinate.x, second.knownNpcs.first { it.npcId == npc.id }.approximateX)
        assertEquals(20L, second.knownNpcs.first { it.npcId == npc.id }.lastSeenAt)
    }

    @Test
    fun npcAnchorsAndLegacyCalendarRulesAreDeterministic() {
        val state = WorldGenerator.generate(29L)
        val parkResidents = state.npcs.filter { it.role == NpcRole.PARK_RESIDENT || it.role == NpcRole.RECYCLER || it.role == NpcRole.MARKET_SELLER }
        assertTrue(parkResidents.mapNotNull { it.homeAnchor }.toSet().size >= parkResidents.size - 1)
        assertTrue(parkResidents.mapNotNull { it.socialAnchor }.toSet().size >= parkResidents.size - 1)
        assertEquals(4, WorldClock.at(0L).dayOfWeek)
        assertEquals(1, WorldClock.at(0L).dayOfMonth)
        assertEquals(10, WorldClock.at(9L * 86_400_000L).dayOfMonth)
        assertEquals(4, WorldClock.at(7L * 86_400_000L).dayOfWeek)
    }

    @Test
    fun generatedTerrainAndSparseWorldHashAreDeterministic() {
        val first = WorldGenerator.generate(0xAD7L)
        val second = WorldGenerator.generate(0xAD7L)
        assertEquals(WorldGenerator.worldHash(first), WorldGenerator.worldHash(second))
        assertEquals(WorldStateCodec.encode(first), WorldStateCodec.encode(second))
        assertEquals(first.structures, second.structures)
        assertEquals(first.npcs, second.npcs)
        assertEquals(first, WorldStateCodec.roundTrip(first))
        assertEquals(16, first.chunkCountX)
        assertEquals(16, first.chunkCountY)
        assertEquals(256, WorldGenerator.chunkAt(first, 0, 0).tiles.size)
    }

    @Test
    fun saveLoadMatchesContinuousTenThousandTickRun() {
        val initial = WorldGenerator.generate(77L).copy(
            actor = WorldGenerator.generate(77L).actor.copy(presence = PresenceMode.HOME),
            npcs = emptyList()
        )
        val egg = CanonicalPetSnapshot(petId = "pet-77", isEgg = true)
        var continuous = initial
        repeat(10_000) { index ->
            continuous = WorldSimulation.step(continuous, canonicalPetSnapshot = egg, now = (index + 1L) * DEFAULT_TICK_MILLIS).state
        }

        var split = initial
        repeat(5_000) { index ->
            split = WorldSimulation.step(split, canonicalPetSnapshot = egg, now = (index + 1L) * DEFAULT_TICK_MILLIS).state
        }
        split = WorldStateCodec.roundTrip(split)
        repeat(5_000) { index ->
            split = WorldSimulation.step(split, canonicalPetSnapshot = egg, now = 500_000L + (index + 1L) * DEFAULT_TICK_MILLIS).state
        }
        assertEquals(continuous, split)
    }

    @Test
    fun navigationNeverUsesUnexploredTiles() {
        val generated = WorldGenerator.generate(11L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val actor = generated.actor.copy(
            presence = PresenceMode.WORLD,
            structureId = null,
            x = home.entrance.x,
            y = home.entrance.y
        )
        val partiallyKnown = generated.copy(
            actor = actor,
            explored = generated.explored.filter { it.chebyshevDistanceTo(home.entrance) <= 4 }
        )
        val target = generated.structures.first { it.type == StructureType.DUNGEON_B }.entrance
        val plan = Pathfinder.planForKnownWorld(partiallyKnown, target)
        assertTrue(plan.stoppedAtKnownFrontier)
        assertTrue(plan.path.all { it in partiallyKnown.explored })
        assertFalse(plan.path.contains(target))
    }

    @Test
    fun commandsRespectEggFreezeAndHomeBoundary() {
        val state = WorldGenerator.generate(3L)
        val egg = CanonicalPetSnapshot(isEgg = true)
        val rejectedLeave = WorldSimulation.step(state, WorldCommand.LeaveStructure, egg, now = 100L)
        assertFalse(rejectedLeave.acceptedCommand)
        assertEquals(PresenceMode.HOME, rejectedLeave.state.actor.presence)

        val rejectedAction = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.REST),
            egg,
            now = 200L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(rejectedAction.acceptedCommand)
        assertEquals("pet_cannot_act", rejectedAction.rejectionReason)

        val activePet = CanonicalPetSnapshot(petId = "pet", autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        val alreadyHome = WorldSimulation.step(state, WorldCommand.ReturnHome, activePet, now = 300L,
            options = WorldSimulationOptions(simulateNpcs = false))
        assertEquals(PresenceMode.HOME, alreadyHome.state.actor.presence)
        assertEquals(GoalId.RETURN_HOME, alreadyHome.state.actor.goal)
        val left = WorldSimulation.step(state, WorldCommand.LeaveStructure, activePet, now = 100L)
        assertTrue(left.acceptedCommand)
        assertEquals(PresenceMode.WORLD, left.state.actor.presence)
        val structure = left.state.structures.first { it.type == StructureType.SHOP }
        val routed = WorldSimulation.step(left.state, WorldCommand.GoToStructure(structure.id), activePet, now = 200L)
        assertTrue(routed.acceptedCommand)
        assertEquals(structure.id, routed.state.actor.pendingStructureId)
        assertNotNull(routed.state.actor.destination)

        val sleepingPet = CanonicalPetSnapshot(sleeping = true)
        val wake = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.WAKE),
            sleepingPet,
            now = 400L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(wake.acceptedCommand)
        assertTrue(wake.effects.any { it is WorldEffectRequest.Activity && it.activity == PetActivity.NONE })
    }

    @Test
    fun explicitDungeonEntryIsNotBlockedByAutonomySafetyPermission() {
        val generated = WorldGenerator.generate(7L)
        val dungeon = generated.structures.first { it.type == StructureType.DUNGEON_A }
        val atEntrance = generated.copy(
            actor = generated.actor.copy(
                x = dungeon.entrance.x,
                y = dungeon.entrance.y,
                preciseX = dungeon.entrance.x.toDouble(),
                preciseY = dungeon.entrance.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = (generated.explored + dungeon.entrance).distinct(),
            npcs = emptyList()
        )
        val safePet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE))
        val result = WorldSimulation.step(
            atEntrance,
            WorldCommand.EnterStructure(dungeon.id),
            safePet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        assertEquals(PresenceMode.INTERIOR, result.state.actor.presence)
        assertEquals(dungeon.id, result.state.actor.structureId)
    }

    @Test
    fun deliberateTravelLeavesInteriorAndReturnHomeWorksAtZeroEnergy() {
        val generated = WorldGenerator.generate(31L)
        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val home = generated.structures.first { it.type == StructureType.HOME }
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 0f),
            sleeping = false,
            activity = PetActivity.NONE,
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val interior = generated.copy(actor = generated.actor.copy(
            x = shop.entrance.x,
            y = shop.entrance.y,
            preciseX = shop.entrance.x.toDouble(),
            preciseY = shop.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = shop.id
        ))
        val result = WorldSimulation.step(interior, WorldCommand.ReturnHome, pet, now = 100L)
        assertTrue(result.acceptedCommand)
        assertEquals(PresenceMode.WORLD, result.state.actor.presence)
        assertEquals(home.id, result.state.actor.pendingStructureId)
        assertTrue(result.observations.contains("left:${shop.id}"))
    }

    @Test
    fun approachAndActPersistsArgumentsAndEmitsFarmTransitionAtWorldPlot() {
        val generated = WorldGenerator.generate(53L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val candidates = buildList {
            for (radius in 3..18) {
                add(WorldCoordinate(home.entrance.x + radius, home.entrance.y))
                add(WorldCoordinate(home.entrance.x - radius, home.entrance.y))
                add(WorldCoordinate(home.entrance.x, home.entrance.y + radius))
                add(WorldCoordinate(home.entrance.x, home.entrance.y - radius))
            }
        }.filter { generated.contains(it) }
        val target = candidates.first { coordinate ->
            WorldGenerator.tileAt(generated, coordinate.x, coordinate.y).walkable &&
                generated.structures.none { it.contains(coordinate) }
        }
        val known = buildList {
            for (y in (target.y - 20).coerceAtLeast(0)..(target.y + 20).coerceAtMost(generated.height - 1)) {
                for (x in (target.x - 20).coerceAtLeast(0)..(target.x + 20).coerceAtMost(generated.width - 1)) {
                    add(WorldCoordinate(x, y))
                }
            }
        }
        val plot = FarmPlotReference("farm_plot_approach", target.x, target.y)
        val state = generated.copy(farmPlots = listOf(plot), explored = (generated.explored + known).distinct())
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("seed_carrot", 1, InventoryKind.SEED)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        assertEquals("seed", ActionRegistry[ActionId.PLANT].inventoryInputs.single().itemId)
        assertTrue(pet.inventory.any { it.itemId == "seed" || it.itemId.startsWith("seed_") })
        assertEquals(InventoryKind.SEED, ActionRegistry[ActionId.PLANT].requiredItemKind)
        val hasSeed = { item: String -> pet.inventory.any { it.itemId == item || (item == "seed" && it.itemId.startsWith("seed_")) } }
        assertTrue(hasSeed("seed"))
        val plantValidation = ActionExecutor.validate(
            ActionId.PLANT,
            ActionContext(
                actorType = ActorType.PET,
                actorCoordinate = target,
                target = ActionTarget(ActionTargetKind.FARM_PLOT, plot.id, target),
                atFarm = true,
                hasItem = hasSeed,
                itemKind = { InventoryKind.SEED },
                autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
            )
        )
        assertTrue("plant validation=${plantValidation.reason}", plantValidation.valid)
        var result = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(
                action = ActionId.PLANT,
                targetId = plot.id,
                arguments = mapOf("cropId" to "carrot", "source" to "seed_picker")
            ),
            pet,
            now = DEFAULT_TICK_MILLIS
        )
        assertTrue("rejection=${result.rejectionReason}", result.acceptedCommand)
        assertEquals(PresenceMode.WORLD, result.state.actor.presence)
        assertEquals("seed_picker", result.state.actor.pendingCommand?.arguments?.get("source"))
        var stateAfter = result.state
        val effects = ArrayList(result.effects)
        for (tick in 2L..80L) {
            result = WorldSimulation.step(stateAfter, canonicalPetSnapshot = pet, now = tick * DEFAULT_TICK_MILLIS)
            effects += result.effects
            stateAfter = result.state
            if (effects.any { it is WorldEffectRequest.FarmTransition }) break
        }
        val farm = effects.filterIsInstance<WorldEffectRequest.FarmTransition>().single()
        assertEquals(FarmActionKind.PLANT, farm.action)
        assertEquals("carrot", farm.cropId)
        assertEquals("seed_picker", farm.arguments["source"])
    }

    @Test
    fun generatedResourceActionEmitsInventoryAndSparseConsumption() {
        val generated = WorldGenerator.generate(41L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val resourceCoordinate = WorldCoordinate(home.entrance.x + 1, home.entrance.y)
        val state = generated.copy(
            actor = generated.actor.copy(
                x = home.entrance.x,
                y = home.entrance.y,
                preciseX = home.entrance.x.toDouble(),
                preciseY = home.entrance.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = generated.explored + resourceCoordinate,
            objects = listOf(WorldObject("berry_patch_test", WorldObjectType.BERRY_PATCH, resourceCoordinate.x, resourceCoordinate.y, quantity = 2))
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        var result = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.FORAGE, targetId = "berry_patch_test"),
            pet,
            now = 100L
        )
        repeat(2) { index ->
            result = WorldSimulation.step(result.state, canonicalPetSnapshot = pet, now = (index + 2L) * 100L)
        }
        assertTrue(result.effects.any { it is WorldEffectRequest.InventoryDelta && it.itemId == "berry" && it.quantity == 2 })
        assertTrue(result.state.deltas.any { it.objectId == "berry_patch_test" && it.value == "consumed" })
    }

    @Test
    fun stopClearsPendingActivityWithoutTouchingCanonicalState() {
        val generated = WorldGenerator.generate(43L)
        val pending = PendingActivityIntent("WORK", LegacyLocationAliases.WORKPLACE, mapOf("jobId" to "job"))
        val state = generated.copy(actor = generated.actor.copy(
            presence = PresenceMode.WORLD,
            structureId = null,
            pendingActivity = pending
        ))
        val result = WorldSimulation.step(
            state,
            WorldCommand.Stop,
            CanonicalPetSnapshot(isEgg = false),
            now = 100L
        )
        assertTrue(result.acceptedCommand)
        assertEquals(null, result.state.actor.pendingActivity)
        assertEquals(state.actor.actorId, result.state.actor.actorId)
        assertEquals(GoalId.IDLE, result.state.actor.goal)
        assertTrue(result.observations.none { it.startsWith("goal:") })
    }

    @Test
    fun movementAndTimedActionsEmitCommittedEnergyAndToolEffects() {
        val generated = WorldGenerator.generate(47L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val start = home.entrance
        val movementTarget = WorldCoordinate(start.x + 1, start.y)
        val tree = WorldObject("tree_cost_test", WorldObjectType.TREE, movementTarget.x, movementTarget.y, quantity = 1)
        val world = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = (generated.explored + movementTarget).distinct(),
            objects = listOf(tree),
            npcs = emptyList()
        )
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("axe_starter", 1, InventoryKind.TOOL)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val walkingDestination = (1..8).map { distance -> WorldCoordinate(start.x + distance, start.y) }
            .first { coordinate ->
                world.contains(coordinate) && coordinate in world.explored && Pathfinder.walkable(world, coordinate, knownOnly = true)
            }
        val movement = WorldSimulation.step(
            world,
            WorldCommand.GoTo(walkingDestination.x, walkingDestination.y),
            pet,
            now = 100L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(movement.effects.any { it is WorldEffectRequest.NeedDelta && it.need == NeedType.ENERGY && it.delta < 0f && it.reason == "movement:walk" })
        var result = WorldSimulation.step(
            world,
            WorldCommand.PerformAction(ActionId.CHOP_TREE, targetId = tree.id),
            pet,
            now = 100L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        val effects = ArrayList(result.effects)
        repeat(ActionRegistry[ActionId.CHOP_TREE].durationTicks) { index ->
            result = WorldSimulation.step(
                result.state,
                canonicalPetSnapshot = pet,
                now = (index + 2L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            effects += result.effects
        }
        assertTrue(effects.any { it is WorldEffectRequest.ToolUse && it.kind == "axe" && it.amount == 1 })
        assertTrue(effects.any { it is WorldEffectRequest.NeedDelta && it.need == NeedType.ENERGY && it.delta < 0f && it.reason == "action:chop_tree" })
    }

    @Test
    fun canonicalActionCompletionDelegatesWithoutDuplicateCoreEffects() {
        val generated = WorldGenerator.generate(48L)
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)),
            needs = NeedsProjection(hunger = 40f, energy = 80f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val arguments = mapOf(
            "canonicalAction" to "feedWithFood",
            "foodId" to "berry",
            "source" to "phone"
        )
        var result = WorldSimulation.step(
            generated,
            WorldCommand.PerformAction(ActionId.EAT, targetId = "berry", arguments = arguments),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        val effects = ArrayList(result.effects)
        // Starting a command consumes the first logical action tick in the
        // same transition; replay only the remaining duration here.
        repeat((ActionRegistry[ActionId.EAT].durationTicks - 1).coerceAtLeast(0)) { index ->
            result = WorldSimulation.step(
                result.state,
                canonicalPetSnapshot = pet,
                now = (index + 2L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            effects += result.effects
        }

        val delegated = effects.filterIsInstance<WorldEffectRequest.CanonicalAction>().single()
        assertEquals("feedWithFood", delegated.action)
        assertEquals(mapOf("foodId" to "berry", "source" to "phone"), delegated.arguments)
        assertTrue(effects.none {
            it is WorldEffectRequest.InventoryDelta ||
                it is WorldEffectRequest.NeedDelta ||
                it is WorldEffectRequest.Activity ||
                it is WorldEffectRequest.FarmTransition
        })
        assertEquals(ActionState.COMPLETED, result.state.actor.actionState)
        assertTrue(result.state.actor.actionArguments.isEmpty())
    }

    @Test
    fun sameTileUseInfersAnItemTargetForCanonicalBridge() {
        val generated = WorldGenerator.generate(60L)
        val coordinate = generated.actor.coordinate
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        var result = WorldSimulation.step(
            generated,
            WorldCommand.PerformAction(
                action = ActionId.USE,
                targetId = "healing_potion",
                targetX = coordinate.x,
                targetY = coordinate.y,
                arguments = mapOf("canonicalAction" to "useMedicine", "itemId" to "healing_potion")
            ),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        result = WorldSimulation.step(
            result.state,
            canonicalPetSnapshot = pet,
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals("useMedicine", result.effects.filterIsInstance<WorldEffectRequest.CanonicalAction>().single().action)
    }

    @Test
    fun frozenEggCanFinishOnlyStationaryCanonicalMedicineUse() {
        val generated = WorldGenerator.generate(63L)
        val coordinate = generated.actor.coordinate
        val egg = CanonicalPetSnapshot(isEgg = true)
        val command = WorldCommand.PerformAction(
            action = ActionId.USE,
            targetId = "healing_potion",
            targetX = coordinate.x,
            targetY = coordinate.y,
            arguments = mapOf("canonicalAction" to "usePotion", "potionId" to "healing_potion")
        )
        var result = WorldSimulation.step(
            generated,
            command,
            egg,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        assertEquals(ActionState.RUNNING, result.state.actor.actionState)
        result = WorldSimulation.step(
            result.state,
            canonicalPetSnapshot = egg,
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals("usePotion", result.effects.filterIsInstance<WorldEffectRequest.CanonicalAction>().single().action)
        assertEquals(ActionState.COMPLETED, result.state.actor.actionState)
    }

    @Test
    fun bottledWaterUsesTypedItemValidationInsteadOfWorldObjectLookup() {
        val generated = WorldGenerator.generate(61L)
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("bottled_water", 1, InventoryKind.WATER)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val result = WorldSimulation.step(
            generated,
            WorldCommand.PerformAction(ActionId.DRINK, targetId = "water"),
            pet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(result.acceptedCommand)
        assertTrue(result.effects.any { it is WorldEffectRequest.InventoryDelta && it.itemId == "water" && it.quantity == -1 })
        assertTrue(result.effects.any { it is WorldEffectRequest.NeedDelta && it.need == NeedType.HYDRATION })
    }

    @Test
    fun catchUpStopsAtCanonicalBoundaryUntilAdapterCommits() {
        val generated = WorldGenerator.generate(58L)
        val actor = generated.actor.copy(
            action = ActionId.EAT,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            actionTargetId = "berry",
            actionArguments = mapOf("canonicalAction" to "feedWithFood", "foodId" to "berry")
        )
        val state = generated.copy(actor = actor, npcs = emptyList(), lastSimulatedAt = 0L)
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val result = WorldSimulation.catchUp(
            state,
            canonicalPetSnapshot = pet,
            now = 5L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(DEFAULT_TICK_MILLIS, result.state.lastSimulatedAt)
        assertEquals(1, result.effects.filterIsInstance<WorldEffectRequest.CanonicalAction>().size)
    }

    @Test
    fun autonomousIndoorBoundaryLeavesIdlePetButRetainsCriticalRest() {
        val generated = WorldGenerator.generate(49L)
        val fullPet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.FULL))
        val left = WorldSimulation.step(
            generated,
            canonicalPetSnapshot = fullPet,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(PresenceMode.WORLD, left.state.actor.presence)
        assertTrue(left.observations.contains("left:${generated.actor.structureId}"))

        val tired = fullPet.copy(needs = NeedsProjection(energy = 5f))
        val resting = WorldSimulation.step(
            generated,
            canonicalPetSnapshot = tired,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(PresenceMode.HOME, resting.state.actor.presence)
        assertEquals(GoalId.REST, resting.state.actor.goal)
        assertEquals(ActionId.SLEEP, resting.state.actor.action)
    }

    @Test
    fun autonomousCareConsumesAvailableFoodAndStartsIndoorStudy() {
        val generated = WorldGenerator.generate(62L)
        val hungry = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)),
            needs = NeedsProjection(hunger = 5f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)
        )
        var result = WorldSimulation.step(
            generated,
            canonicalPetSnapshot = hungry,
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(ActionId.EAT, result.state.actor.action)
        repeat(ActionRegistry[ActionId.EAT].durationTicks) { index ->
            result = WorldSimulation.step(
                result.state,
                canonicalPetSnapshot = hungry,
                now = (index + 2L) * DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
        }
        assertTrue(result.effects.any { it is WorldEffectRequest.InventoryDelta && it.itemId == "berry" && it.quantity == -1 })
        assertTrue(result.effects.any { it is WorldEffectRequest.NeedDelta && it.need == NeedType.HUNGER && it.delta > 0f })

        val school = generated.structures.first { it.type == StructureType.SCHOOL }
        val insideSchool = generated.copy(
            actor = generated.actor.copy(
                x = school.entrance.x,
                y = school.entrance.y,
                preciseX = school.entrance.x.toDouble(),
                preciseY = school.entrance.y.toDouble(),
                presence = PresenceMode.INTERIOR,
                structureId = school.id,
                goal = GoalId.STUDY,
                actionState = ActionState.IDLE,
                action = ActionId.ENTER_STRUCTURE
            ),
            npcs = emptyList()
        )
        val studying = WorldSimulation.step(
            insideSchool,
            canonicalPetSnapshot = CanonicalPetSnapshot(
                autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowStudy = true)
            ),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(studying.acceptedCommand)
        assertEquals(ActionId.STUDY, studying.state.actor.action)
        assertEquals(ActionState.RUNNING, studying.state.actor.actionState)
    }

    @Test
    fun offlineFarmCatchUpProjectsTransitionInputsAndDoesNotRepeatHarvest() {
        val generated = WorldGenerator.generate(51L)
        val plot = FarmPlotReference("farm_plot_replay", generated.actor.x + 1, generated.actor.y)
        val actor = generated.actor.copy(
            x = plot.x,
            y = plot.y,
            preciseX = plot.x.toDouble(),
            preciseY = plot.y.toDouble(),
            presence = PresenceMode.WORLD,
            structureId = null,
            goal = GoalId.FARM,
            action = ActionId.HARVEST_CROP,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            actionTargetId = plot.id,
            actionTargetX = plot.x,
            actionTargetY = plot.y
        )
        val state = generated.copy(
            actor = actor,
            farmPlots = listOf(plot),
            explored = (generated.explored + WorldCoordinate(plot.x, plot.y)).distinct(),
            npcs = emptyList(),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            farm = FarmSnapshot(listOf(FarmPlotSnapshot(
                id = plot.id,
                x = plot.x,
                y = plot.y,
                status = "wet_farmland",
                cropId = "carrot",
                isReady = true,
                userOwned = false
            ))),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val result = WorldSimulation.catchUp(
            state,
            canonicalPetSnapshot = pet,
            now = 5L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        val harvests = result.effects.filterIsInstance<WorldEffectRequest.FarmTransition>()
            .count { it.action == FarmActionKind.HARVEST_CROP }
        assertEquals(1, harvests)
    }

    @Test
    fun legacyAliasesKeepBothDungeonEntrancesDistinct() {
        assertEquals(LegacyLocationAliases.DUNGEON_A, LegacyLocationAliases.normalize("dungeon_1"))
        assertEquals(LegacyLocationAliases.DUNGEON_B, LegacyLocationAliases.normalize("dungeon_2"))
        assertEquals(null, LegacyLocationAliases.normalize("dungeon"))
        assertEquals(StructureType.DUNGEON_A, LegacyLocationAliases.structureType("fixed_0_2"))
        assertEquals(StructureType.DUNGEON_B, LegacyLocationAliases.structureType("fixed_4_2"))
    }

    @Test
    fun selectedItemActionsEmitExactTransactionalEffects() {
        val generated = WorldGenerator.generate(71L)
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 4, InventoryKind.FOOD)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )
        val (droppedState, dropEffects) = runAction(
            generated,
            ActionId.DROP,
            targetId = "berry",
            arguments = mapOf("itemId" to "berry", "quantity" to "2"),
            pet = pet
        )
        assertEquals(2, dropEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>()
            .single { it.reason == "drop" }.quantity.let { -it })
        val drop = dropEffects.filterIsInstance<WorldEffectRequest.ObjectDelta>().single()
        assertEquals("berry", drop.itemId)
        assertEquals(2, drop.quantity)
        assertEquals(WorldObjectType.DROPPED_ITEM, droppedState.objects.single { it.id == drop.objectId }.type)
        assertEquals("available", droppedState.objects.single { it.id == drop.objectId }.state)

        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val shopState = droppedState.copy(
            actor = droppedState.actor.copy(
                x = shop.entrance.x,
                y = shop.entrance.y,
                preciseX = shop.entrance.x.toDouble(),
                preciseY = shop.entrance.y.toDouble(),
                presence = PresenceMode.INTERIOR,
                structureId = shop.id
            ),
            lastSimulatedAt = 0L
        )
        val buyer = pet.copy(money = 50)
        val (_, buyEffects) = runAction(
            shopState,
            ActionId.BUY,
            targetId = "seed_carrot",
            arguments = mapOf("itemId" to "seed_carrot", "quantity" to "2", "pricePerUnit" to "7"),
            pet = buyer,
            startNow = 12L * 60L * 60L * 1_000L
        )
        assertEquals(-14, buyEffects.filterIsInstance<WorldEffectRequest.MoneyDelta>().single().amount)
        assertEquals(2, buyEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>().single { it.itemId == "seed_carrot" }.quantity)

        val seller = buyer.copy(inventory = listOf(InventoryStack("berry", 4, InventoryKind.FOOD)))
        val (_, sellEffects) = runAction(
            shopState.copy(lastSimulatedAt = 0L),
            ActionId.SELL,
            targetId = "berry",
            arguments = mapOf("itemId" to "berry", "quantity" to "3", "pricePerUnit" to "4"),
            pet = seller,
            startNow = 12L * 60L * 60L * 1_000L
        )
        assertEquals(12, sellEffects.filterIsInstance<WorldEffectRequest.MoneyDelta>().single().amount)
        assertEquals(-3, sellEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>().single { it.itemId == "berry" }.quantity)
    }

    @Test
    fun purchasePermissionsAndDaytimeAreRecheckedAtCompletion() {
        val generated = WorldGenerator.generate(72L)
        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val actor = generated.actor.copy(
            x = shop.entrance.x,
            y = shop.entrance.y,
            preciseX = shop.entrance.x.toDouble(),
            preciseY = shop.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = shop.id,
            action = ActionId.BUY,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 1,
            actionTargetId = "rare_seed",
            actionArguments = mapOf(
                "itemId" to "rare_seed",
                "quantity" to "1",
                "pricePerUnit" to "10",
                "__worldAutonomous" to "true"
            )
        )
        val safe = CanonicalPetSnapshot(
            money = 20,
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowPurchases = true, maximumAutonomousPurchase = 5)
        )
        val capped = WorldSimulation.step(
            generated.copy(actor = actor),
            canonicalPetSnapshot = safe,
            now = 12L * 60L * 60L * 1_000L,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertEquals(ActionState.BLOCKED, capped.state.actor.actionState)
        assertTrue(capped.observations.any { it.contains("autonomy_forbidden") })
        assertTrue(capped.effects.none { it is WorldEffectRequest.MoneyDelta || it is WorldEffectRequest.InventoryDelta })

        val nightContext = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = shop.entrance,
            target = ActionTarget(
                ActionTargetKind.STRUCTURE,
                shop.id,
                shop.entrance,
                arguments = mapOf("itemId" to "seed_carrot", "quantity" to "1", "pricePerUnit" to "2")
            ),
            presence = PresenceMode.INTERIOR,
            structureType = StructureType.SHOP,
            money = 10,
            isDaytime = false,
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        assertEquals("daytime_required", ActionExecutor.validate(ActionId.BUY, nightContext).reason)
        assertTrue(ActionExecutor.validate(ActionId.BUY, nightContext.copy(isDaytime = true)).valid)
    }

    @Test
    fun criticalNeedsOverrideAlwaysWaitNavigationAndReturnHome() {
        val generated = WorldGenerator.generate(73L)
        val home = generated.structures.first { it.type == StructureType.HOME }
        val route = generated.explored.asSequence()
            .filter { it != home.entrance && Pathfinder.walkable(generated, it, knownOnly = true) }
            .map { coordinate -> coordinate to Pathfinder.findPath(generated, coordinate, home.entrance, knownOnly = true) }
            .first { (_, path) -> path.size >= 3 }
        val start = route.first
        var state = generated.copy(
            actor = generated.actor.copy(
                x = start.x,
                y = start.y,
                preciseX = start.x.toDouble(),
                preciseY = start.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null,
                goal = GoalId.IDLE,
                action = ActionId.WAIT,
                actionState = ActionState.IDLE
            ),
            lastSimulatedAt = 0L
        )
        val pet = CanonicalPetSnapshot(
            needs = NeedsProjection(energy = 0f),
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        val alwaysWait = NavigationPolicy { _, _, _ -> NavigationDecision(NavigationIntent.WAIT) }
        var sawSafetyOverride = false
        repeat(route.second.size + 6) {
            val result = WorldSimulation.step(
                state,
                canonicalPetSnapshot = pet,
                now = state.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(
                    navigationPolicy = alwaysWait,
                    simulateNpcs = false
                )
            )
            sawSafetyOverride = sawSafetyOverride || result.observations.any {
                it == "navigation_safety_override:RETURN_HOME"
            }
            state = result.state
        }
        assertTrue(sawSafetyOverride)
        assertEquals(home.entrance, state.actor.coordinate)
        assertEquals(PresenceMode.HOME, state.actor.presence)
    }

    @Test
    fun unavailableResourceAndWakeStateCannotPretendToComplete() {
        val generated = WorldGenerator.generate(73L)
        val coordinate = generated.actor.coordinate
        val depleted = WorldObject(
            id = "depleted_berry",
            type = WorldObjectType.BERRY_PATCH,
            x = coordinate.x + 1,
            y = coordinate.y,
            state = "empty",
            quantity = 0
        )
        val world = generated.copy(
            actor = generated.actor.copy(presence = PresenceMode.WORLD, structureId = null),
            explored = (generated.explored + depleted.let { WorldCoordinate(it.x, it.y) }).distinct(),
            objects = listOf(depleted),
            npcs = emptyList()
        )
        val rejected = WorldSimulation.step(
            world,
            WorldCommand.PerformAction(ActionId.FORAGE, targetId = depleted.id),
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(rejected.acceptedCommand)
        assertEquals("target_unavailable", rejected.rejectionReason)
        assertTrue(rejected.effects.isEmpty())

        val awake = WorldSimulation.step(
            generated,
            WorldCommand.PerformAction(ActionId.WAKE),
            CanonicalPetSnapshot(sleeping = false, autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)),
            now = 2L * DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(awake.acceptedCommand)
        assertEquals("already_awake", awake.rejectionReason)
    }

    @Test
    fun npcTransfersRequireInventoryAndTradeBothSides() {
        val generated = WorldGenerator.generate(74L)
        val npc = generated.npcs.first().copy(inventory = listOf(InventoryStack("mushroom", 2, InventoryKind.FOOD)))
        val state = generated.copy(
            actor = generated.actor.copy(
                x = npc.x,
                y = npc.y,
                preciseX = npc.x.toDouble(),
                preciseY = npc.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            npcs = listOf(npc),
            explored = (generated.explored + npc.coordinate).distinct()
        )
        val pet = CanonicalPetSnapshot(
            inventory = listOf(InventoryStack("berry", 3, InventoryKind.FOOD)),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )
        val (givenState, givenEffects) = runAction(
            state,
            ActionId.GIVE_ITEM,
            targetId = npc.id,
            arguments = mapOf("itemId" to "berry", "quantity" to "2"),
            pet = pet
        )
        assertEquals(-2, givenEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>().single().quantity)
        assertEquals(2, givenState.npcs.single().inventory.single { it.itemId == "berry" }.quantity)
        assertEquals(1, givenEffects.filterIsInstance<WorldEffectRequest.RelationshipDelta>().size)

        val (tradedState, tradeEffects) = runAction(
            givenState,
            ActionId.TRADE,
            targetId = npc.id,
            arguments = mapOf(
                "itemId" to "berry",
                "quantity" to "1",
                "receiveItemId" to "mushroom",
                "receiveQuantity" to "1"
            ),
            pet = pet.copy(inventory = listOf(InventoryStack("berry", 1, InventoryKind.FOOD)))
        )
        assertEquals(-1, tradeEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>().single { it.itemId == "berry" }.quantity)
        assertEquals(1, tradeEffects.filterIsInstance<WorldEffectRequest.InventoryDelta>().single { it.itemId == "mushroom" }.quantity)
        assertEquals(3, tradedState.npcs.single().inventory.single { it.itemId == "berry" }.quantity)
        assertEquals(1, tradedState.npcs.single().inventory.single { it.itemId == "mushroom" }.quantity)

        val missing = WorldSimulation.step(
            tradedState,
            WorldCommand.PerformAction(
                ActionId.RECEIVE_ITEM,
                npc.id,
                arguments = mapOf("itemId" to "stone", "quantity" to "1")
            ),
            pet.copy(inventory = emptyList()),
            now = tradedState.lastSimulatedAt + DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(missing.acceptedCommand)
        assertEquals("counterparty_item_required:stone", missing.rejectionReason)
        assertTrue(missing.effects.isEmpty())
    }

    @Test
    fun alchemyAndGenericObjectActionsRequireSelectionsAndPersistState() {
        val generated = WorldGenerator.generate(75L)
        val alchemist = generated.structures.first { it.type == StructureType.ALCHEMIST }
        val inside = generated.copy(
            actor = generated.actor.copy(
                x = alchemist.entrance.x,
                y = alchemist.entrance.y,
                preciseX = alchemist.entrance.x.toDouble(),
                preciseY = alchemist.entrance.y.toDouble(),
                presence = PresenceMode.INTERIOR,
                structureId = alchemist.id
            ),
            npcs = emptyList()
        )
        val crafter = CanonicalPetSnapshot(
            inventory = listOf(
                InventoryStack("herb", 1, InventoryKind.MATERIAL),
                InventoryStack("mushroom", 1, InventoryKind.FOOD)
            ),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF)
        )
        val (_, missingResult) = runAction(
            inside,
            ActionId.USE_ALCHEMY,
            targetId = alchemist.id,
            arguments = mapOf("itemId" to "herb"),
            pet = crafter
        )
        assertTrue(missingResult.isEmpty())

        val (_, unsupportedRecipe) = runAction(
            inside,
            ActionId.USE_ALCHEMY,
            targetId = alchemist.id,
            arguments = mapOf("ingredients" to "herb,mushroom", "resultItemId" to "healing_potion"),
            pet = crafter
        )
        assertTrue(unsupportedRecipe.isEmpty())

        val (_, delegatedRecipe) = runAction(
            inside,
            ActionId.USE_ALCHEMY,
            targetId = alchemist.id,
            arguments = mapOf(
                "canonicalAction" to "brewAdventureGatePotion",
                "ingredients" to "herb,mushroom"
            ),
            pet = crafter
        )
        assertEquals(
            "brewAdventureGatePotion",
            delegatedRecipe.filterIsInstance<WorldEffectRequest.CanonicalAction>().single().action
        )

        val objectCoordinate = WorldCoordinate(alchemist.entrance.x + 1, alchemist.entrance.y)
        val chest = WorldObject("test_chest", WorldObjectType.CUSTOM, objectCoordinate.x, objectCoordinate.y)
        val objectState = inside.copy(
            actor = inside.actor.copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                x = objectCoordinate.x,
                y = objectCoordinate.y
            ),
            objects = listOf(chest),
            explored = (inside.explored + objectCoordinate).distinct()
        )
        val (openedState, opened) = runAction(
            objectState,
            ActionId.OPEN,
            targetId = chest.id,
            pet = crafter
        )
        assertEquals("open", opened.filterIsInstance<WorldEffectRequest.ObjectDelta>().single().state)
        assertEquals("open", openedState.objects.single { it.id == chest.id }.state)
        val (usedState, used) = runAction(
            openedState,
            ActionId.USE,
            targetId = chest.id,
            pet = crafter
        )
        assertEquals("used", used.filterIsInstance<WorldEffectRequest.ObjectDelta>().single().state)
        assertEquals("used", usedState.objects.single { it.id == chest.id }.state)

        val invalidItemUse = WorldSimulation.step(
            objectState,
            WorldCommand.PerformAction(ActionId.USE, targetId = "stone"),
            crafter,
            now = objectState.lastSimulatedAt + DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(invalidItemUse.acceptedCommand)
        assertEquals("canonical_action_required", invalidItemUse.rejectionReason)
    }

    @Test
    fun registryStructureTransitionsProduceWorldStateEffects() {
        val generated = WorldGenerator.generate(76L)
        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val outside = generated.copy(
            actor = generated.actor.copy(
                x = shop.entrance.x,
                y = shop.entrance.y,
                preciseX = shop.entrance.x.toDouble(),
                preciseY = shop.entrance.y.toDouble(),
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            npcs = emptyList()
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val (inside, enterEffects) = runAction(outside, ActionId.ENTER_STRUCTURE, shop.id, pet = pet)
        assertEquals(PresenceMode.INTERIOR, inside.actor.presence)
        assertTrue(enterEffects.any { it is WorldEffectRequest.Event && it.eventType == "entered_structure_action" })

        val dungeon = generated.structures.first { it.type == StructureType.DUNGEON_A }
        val dungeonState = inside.copy(
            actor = inside.actor.copy(
                x = dungeon.entrance.x,
                y = dungeon.entrance.y,
                preciseX = dungeon.entrance.x.toDouble(),
                preciseY = dungeon.entrance.y.toDouble(),
                presence = PresenceMode.INTERIOR,
                structureId = dungeon.id
            )
        )
        val (_, dungeonEffects) = runAction(dungeonState, ActionId.ENTER_DUNGEON, dungeon.id, pet = pet)
        assertTrue(dungeonEffects.any { it is WorldEffectRequest.StructureDelta && it.structureId == dungeon.id && it.state == "entered" })
        assertTrue(dungeonEffects.any { it is WorldEffectRequest.Event && it.eventType == "entered_dungeon" })
    }

    private fun runAction(
        state: WorldState,
        action: ActionId,
        targetId: String? = null,
        arguments: Map<String, String> = emptyMap(),
        pet: CanonicalPetSnapshot,
        startNow: Long = state.lastSimulatedAt + DEFAULT_TICK_MILLIS
    ): Pair<WorldState, List<WorldEffectRequest>> {
        var result = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(action, targetId = targetId, arguments = arguments),
            pet,
            now = startNow,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        val effects = result.effects.toMutableList()
        repeat(ActionRegistry[action].durationTicks + 4) {
            if (result.state.actor.actionState != ActionState.RUNNING) return@repeat
            result = WorldSimulation.step(
                result.state,
                canonicalPetSnapshot = pet,
                now = result.state.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            )
            effects += result.effects
        }
        return result.state to effects
    }

    private fun actualStructureLayouts(): Map<StructureType, StructureLayout> = mapOf(
        StructureType.HOME to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.SHOP to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.SCHOOL to layout(6, 5, listOf("011110", "111111", "111111", "111111", "111011"), 3, 4),
        StructureType.WORKPLACE to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.BOXING_RING to layout(6, 4, listOf("011110", "111111", "111111", "111011"), 3, 3),
        StructureType.HOSPITAL to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.ARCADE to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.ALCHEMIST to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.FARM to layout(5, 4, listOf("01110", "11111", "11111", "11011"), 2, 3),
        StructureType.DUNGEON_A to layout(5, 4, listOf("01110", "11111", "11011", "11011"), 2, 3),
        StructureType.DUNGEON_B to layout(5, 4, listOf("01110", "11111", "11011", "11011"), 2, 3),
        StructureType.ADVENTURE_GATE to layout(5, 5, listOf("01110", "11111", "11111", "11011", "11011"), 2, 4),
        StructureType.PARK to layout(4, 3, listOf("1111", "1001", "0000"), 1, 2)
    )

    private fun layout(width: Int, height: Int, rows: List<String>, entranceX: Int, entranceY: Int): StructureLayout =
        StructureLayout(
            widthTiles = width,
            heightTiles = height,
            collision = rows.map { row -> row.map { it.digitToInt() } },
            entrance = WorldCoordinate(entranceX, entranceY)
        )
}
