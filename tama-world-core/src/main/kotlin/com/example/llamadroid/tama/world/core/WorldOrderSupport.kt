package com.example.llamadroid.tama.world.core

/** Converts a user command into the durable retry payload stored on the actor. */
fun WorldCommand.toWorldOrder(): WorldOrder? = when (this) {
    is WorldCommand.GoTo -> WorldOrder(WorldOrderKind.GO_TO, x = x, y = y, run = run)
    is WorldCommand.GoToStructure -> WorldOrder(WorldOrderKind.GO_TO_STRUCTURE, structureId = structureId)
    WorldCommand.ReturnHome -> WorldOrder(WorldOrderKind.RETURN_HOME)
    is WorldCommand.EnterStructure -> WorldOrder(WorldOrderKind.ENTER_STRUCTURE, structureId = structureId)
    WorldCommand.LeaveStructure -> WorldOrder(WorldOrderKind.LEAVE_STRUCTURE)
    WorldCommand.Stop, WorldCommand.ResumeAutonomy, WorldCommand.Retry -> null
    is WorldCommand.Interact -> WorldOrder(
        kind = WorldOrderKind.INTERACT,
        targetId = targetId,
        action = action
    )
    is WorldCommand.VisitNpc -> WorldOrder(WorldOrderKind.VISIT_NPC, targetId = targetId)
    is WorldCommand.PerformAction -> WorldOrder(
        kind = WorldOrderKind.PERFORM_ACTION,
        action = action,
        targetId = targetId,
        targetX = targetX,
        targetY = targetY,
        arguments = arguments
    )
}

/** Rebuilds a blocked order for the explicit Retry command. */
fun WorldOrder.toWorldCommand(): WorldCommand = when (kind) {
    WorldOrderKind.GO_TO -> WorldCommand.GoTo(
        x = requireNotNull(x),
        y = requireNotNull(y),
        run = run
    )
    WorldOrderKind.GO_TO_STRUCTURE -> WorldCommand.GoToStructure(requireNotNull(structureId))
    WorldOrderKind.RETURN_HOME -> WorldCommand.ReturnHome
    WorldOrderKind.ENTER_STRUCTURE -> WorldCommand.EnterStructure(requireNotNull(structureId))
    WorldOrderKind.LEAVE_STRUCTURE -> WorldCommand.LeaveStructure
    WorldOrderKind.INTERACT -> WorldCommand.Interact(
        targetId = requireNotNull(targetId),
        action = action ?: ActionId.INSPECT
    )
    WorldOrderKind.PERFORM_ACTION -> WorldCommand.PerformAction(
        action = requireNotNull(action),
        targetId = targetId,
        targetX = targetX,
        targetY = targetY,
        arguments = arguments
    )
    WorldOrderKind.VISIT_NPC -> WorldCommand.VisitNpc(requireNotNull(targetId))
}

internal fun WorldActor.activateOrder(order: WorldOrder, orderId: String): WorldActor = copy(
    controlMode = WorldControlMode.ORDER_ACTIVE,
    orderStatus = WorldOrderStatus.RUNNING,
    orderBlocker = WorldOrderBlocker.NONE,
    explicitOrderId = orderId,
    explicitOrder = order
)

internal fun WorldActor.holdOrder(status: WorldOrderStatus = WorldOrderStatus.COMPLETED): WorldActor = copy(
    controlMode = WorldControlMode.HOLDING,
    orderStatus = status,
    orderBlocker = WorldOrderBlocker.NONE,
    goal = GoalId.IDLE,
    action = ActionId.WAIT,
    actionTicksRemaining = 0,
    destinationX = null,
    destinationY = null,
    pendingStructureId = null,
    path = emptyList(),
    pendingCommand = null,
    followTargetId = null
)

internal fun WorldActor.blockOrder(reason: String): WorldActor = copy(
    controlMode = WorldControlMode.BLOCKED,
    orderStatus = WorldOrderStatus.BLOCKED,
    orderBlocker = worldOrderBlockerFor(reason),
    actionState = ActionState.BLOCKED,
    actionTicksRemaining = 0,
    action = ActionId.WAIT,
    goal = GoalId.RECOVER_STUCK,
    path = emptyList()
)

internal fun WorldActor.clearOrderForAutonomy(): WorldActor = copy(
    controlMode = WorldControlMode.AUTONOMOUS,
    orderStatus = WorldOrderStatus.NONE,
    orderBlocker = WorldOrderBlocker.NONE,
    explicitOrderId = null,
    explicitOrder = null
)

internal fun worldOrderBlockerFor(reason: String?): WorldOrderBlocker = when (reason) {
    "destination_unreachable", "destination_blocked", "destination_outside_world" ->
        WorldOrderBlocker.DESTINATION_UNREACHABLE
    "known_frontier", "follow_target_unobserved" -> WorldOrderBlocker.KNOWN_FRONTIER
    "action_in_progress" -> WorldOrderBlocker.ACTION_IN_PROGRESS
    "pet_busy" -> WorldOrderBlocker.PET_BUSY
    "pet_cannot_move", "pet_cannot_act", "pet_cannot_enter", "pet_cannot_leave" ->
        WorldOrderBlocker.PET_CANNOT_MOVE
    "sleeping" -> WorldOrderBlocker.SLEEPING
    "frozen", "egg" -> WorldOrderBlocker.FROZEN
    "canonical_activity_active", "activity_in_progress", "external_activity_active" ->
        WorldOrderBlocker.CANONICAL_ACTIVITY_ACTIVE
    "counterparty_missing", "counterparty_required", "target_unavailable" ->
        WorldOrderBlocker.COUNTERPARTY_MISSING
    "capability_missing", "missing_item", "missing_tool" -> WorldOrderBlocker.CAPABILITY_MISSING
    "requirements", "autonomy_disabled", "autonomy_forbidden", "daytime_required", "target_not_walkable" ->
        WorldOrderBlocker.REQUIREMENTS
    else -> WorldOrderBlocker.INVALID_TARGET
}
