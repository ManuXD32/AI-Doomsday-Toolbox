package com.example.llamadroid.tama.world.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldRegistrySemanticsTest {
    @Test
    fun canonicalHospitalOwnsTreatmentAndGenericVisitCannotGrantHealth() {
        val generated = WorldGenerator.generate(7_701L)
        val hospital = generated.structures.first { it.type == StructureType.HOSPITAL }
        val target = ActionTarget(
            kind = ActionTargetKind.STRUCTURE,
            id = hospital.id,
            coordinate = hospital.entrance
        )
        val context = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = hospital.entrance,
            target = target,
            presence = PresenceMode.INTERIOR,
            structureType = StructureType.HOSPITAL,
            structureId = hospital.id,
            autonomy = AutonomyPolicy(level = AutonomyLevel.FULL)
        )
        assertTrue(ActionExecutor.validate(ActionId.VISIT_HOSPITAL, context).valid)
        assertTrue(ActionExecutor.effectRequests(ActionId.VISIT_HOSPITAL).none {
            it is WorldEffectRequest.NeedDelta && it.need == NeedType.HEALTH
        })
        val genericEffects = mutableListOf<WorldEffectRequest>()
        appendActionSpecificEffects(generated, ActionId.VISIT_HOSPITAL, target, genericEffects)
        val genericVisit = genericEffects.filterIsInstance<WorldEffectRequest.CanonicalAction>().single()
        assertEquals("visitHospital", genericVisit.action)
        assertTrue(genericVisit.arguments.isEmpty())

        val autonomousEffects = mutableListOf<WorldEffectRequest>()
        appendActionSpecificEffects(
            generated,
            ActionId.VISIT_HOSPITAL,
            target.copy(arguments = mapOf(WorldActionSemantics.AUTONOMOUS_ACTION_ARGUMENT to "true")),
            autonomousEffects
        )
        val autonomousVisit = autonomousEffects.filterIsInstance<WorldEffectRequest.CanonicalAction>().single()
        assertEquals("true", autonomousVisit.arguments[WorldActionSemantics.AUTONOMOUS_ACTION_ARGUMENT])
        assertTrue(ActionExecutor.validate(
            ActionId.VISIT_HOSPITAL,
            context.copy(target = target.copy(arguments = mapOf("canonicalAction" to "visitHospital")))
        ).valid)
        val safeAutonomous = context.copy(
            target = target.copy(arguments = mapOf(WorldActionSemantics.AUTONOMOUS_ACTION_ARGUMENT to "true")),
            autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE, allowPurchases = false)
        )
        assertEquals("autonomy_forbidden", ActionExecutor.validate(ActionId.VISIT_HOSPITAL, safeAutonomous).reason)
        assertTrue(ActionExecutor.validate(
            ActionId.VISIT_HOSPITAL,
            safeAutonomous.copy(autonomy = AutonomyPolicy(
                level = AutonomyLevel.SAFE,
                allowPurchases = true,
                maximumAutonomousPurchase = 25
            ))
        ).valid)
        assertEquals(
            "autonomy_forbidden",
            ActionExecutor.validate(
                ActionId.VISIT_HOSPITAL,
                safeAutonomous.copy(
                    target = target.copy(arguments = mapOf(
                        WorldActionSemantics.AUTONOMOUS_ACTION_ARGUMENT to "true",
                        WorldActionSemantics.ITEM_ID_ARGUMENT to "healing_potion",
                        WorldActionSemantics.QUANTITY_ARGUMENT to "1",
                        WorldActionSemantics.PRICE_ARGUMENT to "26"
                    )),
                    autonomy = AutonomyPolicy(
                        level = AutonomyLevel.SAFE,
                        allowPurchases = true,
                        maximumAutonomousPurchase = 25
                    )
                )
            ).reason
        )

        val manualState = generated.copy(actor = generated.actor.copy(
            x = hospital.entrance.x,
            y = hospital.entrance.y,
            preciseX = hospital.entrance.x.toDouble(),
            preciseY = hospital.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR,
            structureId = hospital.id
        ))
        val manual = WorldSimulation.step(
            manualState,
            WorldCommand.PerformAction(ActionId.VISIT_HOSPITAL, targetId = hospital.id),
            CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.SAFE)),
            now = DEFAULT_TICK_MILLIS,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(manual.acceptedCommand)
        assertEquals(ActionId.VISIT_HOSPITAL, manual.state.actor.action)
        assertEquals(ActionState.RUNNING, manual.state.actor.actionState)
    }

    @Test
    fun commerceAcceptsCanonicalCareVendorsAndRejectsFreeSales() {
        val generated = WorldGenerator.generate(7_702L)
        val pet = AutonomyPolicy(level = AutonomyLevel.FULL)
        fun buyContext(type: StructureType): ActionContext {
            val structure = generated.structures.first { it.type == type }
            return ActionContext(
                actorType = ActorType.PET,
                actorCoordinate = structure.entrance,
                target = ActionTarget(
                    ActionTargetKind.STRUCTURE,
                    structure.id,
                    structure.entrance,
                    arguments = mapOf("itemId" to "healing_potion", "quantity" to "1", "pricePerUnit" to "5")
                ),
                presence = PresenceMode.INTERIOR,
                structureType = type,
                structureId = structure.id,
                money = 10,
                autonomy = pet
            )
        }
        assertTrue(ActionExecutor.validate(ActionId.BUY, buyContext(StructureType.HOSPITAL)).valid)
        assertTrue(ActionExecutor.validate(ActionId.BUY, buyContext(StructureType.ALCHEMIST)).valid)

        val shop = generated.structures.first { it.type == StructureType.SHOP }
        val sale = ActionContext(
            actorType = ActorType.PET,
            actorCoordinate = shop.entrance,
            target = ActionTarget(
                ActionTargetKind.ITEM,
                id = "berry",
                arguments = mapOf("itemId" to "berry", "quantity" to "1", "pricePerUnit" to "0")
            ),
            presence = PresenceMode.INTERIOR,
            structureType = StructureType.SHOP,
            structureId = shop.id,
            hasItem = { it == "berry" },
            itemKind = { InventoryKind.FOOD },
            itemQuantity = { if (it == "berry") 1 else 0 },
            autonomy = pet
        )
        assertEquals("sale_selection_required", ActionExecutor.validate(ActionId.SELL, sale).reason)
        assertEquals("invalid_sale", ActionExecutor.validate(
            ActionId.SELL,
            sale.copy(target = sale.target!!.copy(
                arguments = sale.target!!.arguments + ("canonicalAction" to "sellItem")
            ))
        ).reason)
    }

    @Test
    fun statefulInteractionsHonorCapabilitiesOwnershipAndCurrentState() {
        val world = openWorld()
        val door = WorldObject(
            id = "private_door",
            type = WorldObjectType.CUSTOM,
            x = world.actor.x,
            y = world.actor.y,
            state = "closed",
            capabilities = setOf(ActionId.OPEN, ActionId.CLOSE),
            ownerActorId = world.actor.actorId
        )
        val state = world.copy(objects = listOf(door))
        val target = ActionTarget(ActionTargetKind.OBJECT, door.id, doorCoordinate(door))
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        assertEquals("target_not_owned", validateStatefulAction(
            state.copy(actor = state.actor.copy(actorId = "visitor")), pet, ActionId.OPEN,
            target
        ))
        val openedEffects = mutableListOf<WorldEffectRequest>()
        val opened = appendActionSpecificEffects(state, ActionId.OPEN, target, openedEffects)
        assertEquals("open", opened.objects.single().state)
        assertEquals("already_open", validateStatefulAction(opened, pet, ActionId.OPEN, target))
        assertNull(validateStatefulAction(opened, pet, ActionId.CLOSE, target))

        val noActivate = state.copy(objects = listOf(door.copy(capabilities = setOf(ActionId.OPEN))))
        assertEquals("capability_missing", validateStatefulAction(noActivate, pet, ActionId.ACTIVATE, target))
        assertEquals("target_not_open", validateStatefulAction(state, pet, ActionId.CLOSE, target))
    }

    @Test
    fun depletedHelpAndUnsupportedFarmStoreAreRejectedWithoutEffects() {
        val base = openWorld()
        val depleted = WorldObject(
            id = "depleted_tree",
            type = WorldObjectType.TREE,
            x = base.actor.x,
            y = base.actor.y,
            state = "stump",
            quantity = 0
        )
        val state = base.copy(
            objects = listOf(depleted),
            farmPlots = listOf(FarmPlotReference("plot", base.actor.x, base.actor.y))
        )
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val help = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.HELP_NPC, targetId = depleted.id),
            pet,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(help.acceptedCommand)
        assertEquals("target_unavailable", help.rejectionReason)
        assertTrue(help.effects.isEmpty())

        val tillTile = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.TILL_SOIL, targetX = base.actor.x, targetY = base.actor.y),
            pet.copy(inventory = listOf(InventoryStack("hoe_starter", 1, InventoryKind.TOOL))),
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(tillTile.acceptedCommand)
        assertEquals("farm_plot_required", tillTile.rejectionReason)

        val store = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.STORE_PRODUCE, targetId = "plot"),
            pet,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertFalse(store.acceptedCommand)
        assertEquals("canonical_action_required", store.rejectionReason)
    }

    @Test
    fun locomotionAndFollowArePhysicalAndPersistAcrossReload() {
        val state = openWorld()
        val pet = CanonicalPetSnapshot(autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val walking = WorldSimulation.step(
            state,
            WorldCommand.PerformAction(ActionId.WALK, targetX = 5, targetY = 1),
            pet,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(walking.acceptedCommand)
        assertTrue(walking.state.actor.coordinate.x > state.actor.coordinate.x)

        val npc = WorldNpc("follow_target", "Follow Target", NpcRole.PARK_RESIDENT, x = 5, y = 1)
        val followState = state.copy(npcs = listOf(npc))
        val started = WorldSimulation.step(
            followState,
            WorldCommand.PerformAction(ActionId.FOLLOW_NPC, targetId = npc.id),
            pet,
            options = WorldSimulationOptions(simulateNpcs = false)
        )
        assertTrue(started.acceptedCommand)
        assertEquals(npc.id, started.state.actor.followTargetId)

        var current = WorldStateCodec.roundTrip(started.state)
        repeat(8) {
            current = WorldSimulation.step(
                current,
                canonicalPetSnapshot = pet,
                now = current.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                options = WorldSimulationOptions(simulateNpcs = false)
            ).state
        }
        assertEquals(npc.id, current.actor.followTargetId)
        assertTrue(current.actor.coordinate.x > state.actor.coordinate.x)
    }

    @Test
    fun observationActionsEmitAReceiptEventInsteadOfSilentSuccess() {
        val effects = mutableListOf<WorldEffectRequest>()
        appendActionSpecificEffects(
            openWorld(),
            ActionId.INSPECT,
            ActionTarget(ActionTargetKind.TILE, coordinate = WorldCoordinate(1, 1)),
            effects
        )
        val event = effects.filterIsInstance<WorldEffectRequest.Event>().single()
        assertEquals("observation_inspect", event.eventType)
        assertEquals(EventImportance.TRACE, event.importance)
    }

    private fun openWorld(): WorldState {
        val explored = (0 until 8).flatMap { y -> (0 until 8).map { x -> WorldCoordinate(x, y) } }
        return WorldState(
            seed = 7_700L,
            width = 8,
            height = 8,
            actor = WorldActor(
                actorId = "pet",
                actorType = ActorType.PET,
                x = 1,
                y = 1,
                preciseX = 1.0,
                preciseY = 1.0,
                presence = PresenceMode.WORLD,
                structureId = null
            ),
            explored = explored,
            roads = explored,
            npcs = emptyList()
        )
    }

    private fun doorCoordinate(door: WorldObject): WorldCoordinate = WorldCoordinate(door.x, door.y)
}
