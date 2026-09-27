package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldControlMode
import com.example.llamadroid.tama.world.core.WorldNpc
import com.example.llamadroid.tama.world.core.WorldOrderBlocker
import com.example.llamadroid.tama.world.core.WorldOrderKind
import com.example.llamadroid.tama.world.core.WorldOrderStatus
import com.example.llamadroid.tama.world.core.WorldOrder
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.ui.WorldPetCommandKind
import com.example.llamadroid.tama.world.ui.WorldOrderPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldUiProjectionTest {
    @Test
    fun arcadeHostUsesTheAuthoredPixelPopWorldSheet() {
        assertEquals("npc_arcade_machine", npcAssetId("arcade_host"))
    }

    @Test
    fun ordinaryNpcIdsKeepTheCanonicalPrefixMapping() {
        assertEquals("npc_farm_farmer", npcAssetId("farm_farmer"))
        assertEquals("npc_seller", npcAssetId("seller"))
    }

    @Test
    fun completedNpcOrderProjectsTargetAndResumeAction() {
        val npc = WorldNpc("npc-1", "Milo", NpcRole.PARK_RESIDENT, x = 3, y = 4)
        val state = WorldState(
            seed = 1L,
            npcs = listOf(npc),
            actor = WorldActor(
                controlMode = WorldControlMode.HOLDING,
                orderStatus = WorldOrderStatus.COMPLETED,
                explicitOrder = WorldOrder(WorldOrderKind.VISIT_NPC, targetId = npc.id),
                actionTargetId = npc.id,
                actionTargetX = npc.x,
                actionTargetY = npc.y
            )
        )

        val projected = projectWorldState(state)

        assertEquals(WorldOrderPhase.COMPLETED, projected.order.phase)
        assertEquals(npc.id, projected.order.targetId)
        assertEquals(npc.name, projected.order.targetLabel)
        assertTrue(projected.order.canResumeAutonomy)
    }

    @Test
    fun blockedOrderExposesCoreBlockerAndRetry() {
        val state = WorldState(
            seed = 1L,
            actor = WorldActor(
                controlMode = WorldControlMode.BLOCKED,
                orderStatus = WorldOrderStatus.BLOCKED,
                orderBlocker = WorldOrderBlocker.DESTINATION_UNREACHABLE,
                explicitOrder = WorldOrder(WorldOrderKind.GO_TO, x = 9, y = 9)
            )
        )

        val projected = projectWorldState(state)

        assertEquals(WorldOrderPhase.BLOCKED, projected.order.phase)
        assertTrue(projected.order.canRetry)
        assertTrue(projected.order.canCancel)
        assertEquals(9f, projected.order.target?.x)
        assertEquals(9f, projected.order.target?.y)
    }

    @Test
    fun autonomousGoalsDoNotProjectAsPlayerOrders() {
        listOf(GoalId.EXPLORE, GoalId.RETURN_HOME).forEach { goal ->
            val state = WorldState(
                seed = 1L,
                actor = WorldActor(
                    controlMode = WorldControlMode.AUTONOMOUS,
                    orderStatus = WorldOrderStatus.NONE,
                    goal = goal
                )
            )

            val projected = projectWorldState(state)

            assertEquals(WorldOrderPhase.IDLE, projected.order.phase)
            assertEquals(null, projected.order.command)
        }
    }

    @Test
    fun explicitOrderIdentityWinsOverAnArmedTargetSelection() {
        val npc = WorldNpc("npc-1", "Milo", NpcRole.PARK_RESIDENT, x = 3, y = 4)
        val state = WorldState(
            seed = 1L,
            npcs = listOf(npc),
            actor = WorldActor(
                controlMode = WorldControlMode.HOLDING,
                orderStatus = WorldOrderStatus.COMPLETED,
                explicitOrder = WorldOrder(WorldOrderKind.VISIT_NPC, targetId = npc.id)
            )
        )

        val projected = projectWorldState(state, activeCommand = WorldPetCommandKind.GO_HERE)

        assertEquals(WorldPetCommandKind.VISIT_NPC, projected.order.command)
    }

    @Test
    fun explicitActionsUseLocalizedCoreActionLabels() {
        val state = WorldState(
            seed = 1L,
            actor = WorldActor(
                controlMode = WorldControlMode.ORDER_ACTIVE,
                orderStatus = WorldOrderStatus.RUNNING,
                explicitOrder = WorldOrder(
                    WorldOrderKind.PERFORM_ACTION,
                    action = ActionId.FOLLOW_NPC
                )
            )
        )

        val projected = projectWorldState(state, labels = WorldUiLabels(
            actionName = { action -> "action:${action.name}" }
        ))

        assertEquals("action:FOLLOW_NPC", projected.order.commandLabel)
        assertEquals(null, projected.order.command)
    }

    @Test
    fun structureOrdersUseWalkingLabelWhileKeepingExploreButtonMapping() {
        val state = WorldState(
            seed = 1L,
            actor = WorldActor(
                controlMode = WorldControlMode.ORDER_ACTIVE,
                orderStatus = WorldOrderStatus.RUNNING,
                explicitOrder = WorldOrder(
                    WorldOrderKind.GO_TO_STRUCTURE,
                    structureId = "fixed_1_0"
                )
            )
        )

        val projected = projectWorldState(state, labels = WorldUiLabels(
            actionName = { action -> "action:${action.name}" }
        ))

        assertEquals(WorldPetCommandKind.EXPLORE, projected.order.command)
        assertEquals("action:WALK", projected.order.commandLabel)
    }

    @Test
    fun heldCoordinateOrderKeepsItsMarkerAfterArrival() {
        val state = WorldState(
            seed = 1L,
            actor = WorldActor(
                controlMode = WorldControlMode.HOLDING,
                orderStatus = WorldOrderStatus.COMPLETED,
                explicitOrder = WorldOrder(WorldOrderKind.GO_TO, x = 9, y = 9)
            )
        )

        val projected = projectWorldState(state)

        assertEquals(9f, projected.order.target?.x)
        assertEquals(9f, projected.order.target?.y)
    }

    @Test
    fun runningCanonicalActivityKeepsCancelButHidesResume() {
        val state = WorldState(
            seed = 1L,
            actor = WorldActor(
                controlMode = WorldControlMode.HOLDING,
                orderStatus = WorldOrderStatus.RUNNING,
                explicitOrder = WorldOrder(WorldOrderKind.PERFORM_ACTION)
            )
        )

        val projected = projectWorldState(state)

        assertTrue(projected.order.canCancel)
        assertTrue(!projected.order.canResumeAutonomy)
    }
}
