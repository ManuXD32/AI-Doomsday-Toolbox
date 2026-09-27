package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.NeedType
import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldControlMode
import com.example.llamadroid.tama.world.core.WorldOrderBlocker
import com.example.llamadroid.tama.world.core.WorldOrderOutcome
import com.example.llamadroid.tama.world.core.WorldOrderStatus

/** A cold process never silently continues an order from the previous optional session. */
internal fun WorldActor.interruptRestoredOrder(): WorldActor {
    if (controlMode != WorldControlMode.ORDER_ACTIVE || explicitOrder == null ||
        pendingActivity?.blocksPetSimulation == true
    ) return this
    return copy(
        controlMode = WorldControlMode.BLOCKED,
        orderStatus = WorldOrderStatus.BLOCKED,
        orderBlocker = WorldOrderBlocker.SESSION_INTERRUPTED,
        action = ActionId.WAIT,
        actionState = ActionState.BLOCKED,
        actionTicksRemaining = 0,
        path = emptyList()
    )
}

/** Only canonical before/after rows may supply the visible gains from an order. */
internal fun WorldActor.withCommittedOrderOutcome(before: TamaPet, after: TamaPet): WorldActor {
    val order = explicitOrder ?: return this
    if (orderStatus !in setOf(WorldOrderStatus.RUNNING, WorldOrderStatus.COMPLETED)) return this
    val existing = order.outcome ?: WorldOrderOutcome()
    val oldNeeds = before.stats.worldNeeds()
    val newNeeds = after.stats.worldNeeds()
    val needDeltas = NeedType.entries.associateWith { need ->
        (existing.needDeltas[need] ?: 0f) + (newNeeds.valueOf(need) - oldNeeds.valueOf(need))
    }.filterValues { it != 0f }
    val oldItems = before.inventory.associate { it.id to it.quantity }
    val newItems = after.inventory.associate { it.id to it.quantity }
    val itemIds = oldItems.keys + newItems.keys + existing.inventoryDeltas.keys
    val inventoryDeltas = itemIds.associateWith { id ->
        ((existing.inventoryDeltas[id] ?: 0).toLong() + (newItems[id] ?: 0) - (oldItems[id] ?: 0))
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }.filterValues { it != 0 }
    val outcome = WorldOrderOutcome(
        needDeltas = needDeltas,
        moneyDelta = existing.moneyDelta + (after.money - before.money),
        inventoryDeltas = inventoryDeltas
    )
    return copy(explicitOrder = order.copy(outcome = outcome))
}

/** The adapter, not the animation clock, acknowledges canonical action completion. */
internal fun WorldActor.settleCommittedOrder(pet: TamaPet): WorldActor {
    if (explicitOrder == null || orderStatus != WorldOrderStatus.RUNNING ||
        pendingActivity != null || pendingCommand != null || actionTicksRemaining > 0 ||
        controlMode != WorldControlMode.HOLDING
    ) return this
    val ongoing = when {
        pet.isSleeping -> ActionId.SLEEP
        pet.currentActivity == ActivityType.WORKING -> ActionId.WORK
        pet.currentActivity == ActivityType.STUDYING -> ActionId.STUDY
        pet.currentActivity == ActivityType.TRAINING -> ActionId.TRAIN_BOXING
        pet.currentActivity == ActivityType.RELAXING -> ActionId.RELAX
        else -> null
    }
    return copy(
        controlMode = WorldControlMode.HOLDING,
        orderStatus = if (ongoing != null) WorldOrderStatus.RUNNING else WorldOrderStatus.COMPLETED,
        orderBlocker = WorldOrderBlocker.NONE,
        action = ongoing ?: ActionId.WAIT,
        actionState = ActionState.COMPLETED
    )
}
