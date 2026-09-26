package com.example.llamadroid.tama.world.core

import kotlin.math.abs
import kotlin.math.min

/**
 * NPCs submit intents to the living action executor. Their inventory, fields,
 * needs and relationships are projected locally; no NPC effect can grant a
 * player item, spend player money or touch a canonical player crop.
 */
object WorldNpcSimulation {
    private const val JOB_INTERVAL_MS = 60_000L
    private const val FAR_INTERVAL_MS = 1_000L
    private const val DAY_MILLIS = 86_400_000L
    private const val MINUTE_MILLIS = 60_000L
    /** Maximum 100 ms physical executor transitions per NPC during one offline catch-up. */
    private const val MAX_OFFLINE_PHYSICAL_STEPS = 240
    /** A schedule slice gets at most two seconds of physical replay. */
    private const val MAX_PHYSICAL_STEPS_PER_SLICE = 20
    /** Only the newest schedule slices receive physical replay after a long gap. */
    private const val MAX_OFFLINE_ACTIVE_SLICES = 12
    /** Older calendar history is represented by schedule events, not tick replay. */
    private const val MAX_OFFLINE_SCHEDULE_DAYS = 14
    /** At most one executor transition is visited for each of 64 schedule events. */
    private const val MAX_OFFLINE_SCHEDULE_EVENTS = 64

    fun advance(state: WorldState, simulationRadius: Int, coarseTicks: Long = 1): WorldState {
        if (state.npcs.isEmpty()) return state
        var working = state
        // Stable catalog order also resolves simultaneous NPC resource claims.
        state.npcs.forEach { original ->
            val npc = working.npcs.first { it.id == original.id }
            val elapsed = (state.lastSimulatedAt - npc.lastSimulatedAt).coerceAtLeast(0L)
            // Proximity is measured against the simulated actor's world
            // coordinate, never a renderer or camera. This keeps mixed
            // near/far calls deterministic in headless and visible modes.
            val near = npc.coordinate.chebyshevDistanceTo(state.actor.coordinate) <= simulationRadius
            val cadence = if (near) DEFAULT_TICK_MILLIS else FAR_INTERVAL_MS
            if (elapsed < cadence) return@forEach
            if (coarseTicks <= 1L && elapsed <= cadence) {
                // Fast online ticks still own passive NPC need decay. Apply it
                // before the executor so action prerequisites see the needs at
                // this wall-clock instant and action costs are charged once.
                val decayed = decayOfflineNeeds(
                    npc,
                    npc.needs,
                    npc.lastSimulatedAt,
                    state.lastSimulatedAt
                )
                val next = advanceOne(working, npc.copy(needs = decayed), state.lastSimulatedAt)
                working = replace(next.second, next.first.copy(lastSimulatedAt = state.lastSimulatedAt))
            } else {
                working = advanceOfflineGap(working, npc, state.lastSimulatedAt)
            }
        }
        return working
    }

    /**
     * Catch up one NPC without replaying every elapsed 100 ms tick. Calendar
     * phase boundaries are visited in order, while only the newest bounded
     * slices receive physical executor steps. Skipped time decays needs using
     * the phase that was active during that slice; no coordinate, job reward,
     * or inventory result is synthesized for skipped time.
     */
    private fun advanceOfflineGap(world: WorldState, npc: WorldNpc, end: Long): WorldState {
        val start = npc.lastSimulatedAt.coerceAtMost(end)
        if (end <= start) return replace(world, npc.copy(lastSimulatedAt = end))
        val phaseBoundaries = offlineScheduleBoundaries(npc, start, end, world.timezoneOffsetMinutes)
        val boundaries = (listOf(start) + phaseBoundaries + end).distinct().sorted()
        val segmentCount = (boundaries.size - 1).coerceAtLeast(0)
        val physicalSegmentStart = (segmentCount - MAX_OFFLINE_ACTIVE_SLICES).coerceAtLeast(0)
        var currentWorld = world
        var updated = npc
        var physicalSteps = 0

        for (segmentIndex in 0 until segmentCount) {
            val segmentStart = boundaries[segmentIndex]
            val segmentEnd = boundaries[segmentIndex + 1]
            if (segmentEnd <= segmentStart) continue

            // One transition at each schedule boundary lets a persisted path
            // or timed action react to the new phase without teleporting.
            val atBoundary = advanceOne(currentWorld, updated, segmentStart)
            updated = atBoundary.first
            currentWorld = atBoundary.second

            val activeSlice = segmentIndex >= physicalSegmentStart
            if (!activeSlice) {
                updated = updated.copy(needs = decayOfflineNeeds(
                    updated,
                    updated.needs,
                    segmentStart,
                    segmentEnd
                ))
                continue
            }

            val segmentTicks = (segmentEnd - segmentStart) / DEFAULT_TICK_MILLIS
            val remainingBudget = (MAX_OFFLINE_PHYSICAL_STEPS - physicalSteps).coerceAtLeast(0)
            val replaySteps = min(
                MAX_PHYSICAL_STEPS_PER_SLICE.toLong(),
                min(segmentTicks, remainingBudget.toLong())
            ).toInt()
            val replayStart = segmentEnd - replaySteps * DEFAULT_TICK_MILLIS
            if (replayStart > segmentStart) {
                updated = updated.copy(needs = decayOfflineNeeds(
                    updated,
                    updated.needs,
                    segmentStart,
                    replayStart
                ))
            }

            var logicalNow = replayStart
            repeat(replaySteps) {
                logicalNow += DEFAULT_TICK_MILLIS
                updated = updated.copy(needs = decayOfflineNeeds(
                    updated,
                    updated.needs,
                    logicalNow - DEFAULT_TICK_MILLIS,
                    logicalNow
                ))
                val next = advanceOne(currentWorld, updated, logicalNow)
                updated = next.first
                currentWorld = next.second
                physicalSteps++
            }
            if (logicalNow < segmentEnd) {
                updated = updated.copy(needs = decayOfflineNeeds(
                    updated,
                    updated.needs,
                    logicalNow,
                    segmentEnd
                ))
            }
        }

        return replace(currentWorld, updated.copy(lastSimulatedAt = end))
    }

    private fun offlineScheduleBoundaries(
        npc: WorldNpc,
        start: Long,
        end: Long,
        timezoneOffsetMinutes: Int
    ): List<Long> {
        if (npc.stationary) return emptyList()
        val scheduleMinutes = when (npc.role) {
            // The end minute is exclusive in the schedule predicate below;
            // visit the first minute of the following phase as the boundary.
            NpcRole.RECYCLER -> intArrayOf(480, 961)
            NpcRole.MARKET_SELLER -> intArrayOf(480, 1_021)
            else -> intArrayOf(420, 1_081, 1_261)
        }
        val lookbackStart = maxOf(start, end - MAX_OFFLINE_SCHEDULE_DAYS * DAY_MILLIS)
        val firstDay = WorldClock.at(lookbackStart, timezoneOffsetMinutes).dayIndex
        val lastDay = WorldClock.at(end, timezoneOffsetMinutes).dayIndex
        val dayCount = (lastDay - firstDay).coerceIn(0L, MAX_OFFLINE_SCHEDULE_DAYS.toLong() + 1L).toInt()
        val result = ArrayList<Long>(MAX_OFFLINE_SCHEDULE_EVENTS)
        for (dayOffset in 0..dayCount) {
            val day = firstDay + dayOffset
            val dayStart = day * DAY_MILLIS - timezoneOffsetMinutes * MINUTE_MILLIS
            scheduleMinutes.forEach { minute ->
                val boundary = dayStart + minute * MINUTE_MILLIS
                if (boundary >= lookbackStart && boundary > start && boundary < end &&
                    result.size < MAX_OFFLINE_SCHEDULE_EVENTS
                ) {
                    result += boundary
                }
            }
        }
        return result
    }

    private fun decayOfflineNeeds(
        npc: WorldNpc,
        needs: NeedsProjection,
        from: Long,
        to: Long
    ): NeedsProjection {
        val elapsed = (to - from).coerceAtLeast(0L)
        if (elapsed == 0L) return needs
        // A schedule phase alone does not prove that an NPC is asleep: a home
        // phase may still be walking to its home anchor. Presence plus the
        // sleep action is the persisted fact that permits sleep-rate decay.
        val sleeping = npc.execution?.presence == PresenceMode.HOME &&
            (npc.execution.action == ActionId.SLEEP || npc.scheduleState == "sleeping")
        return NeedsSystem.decay(
            needs = needs,
            elapsedMillis = elapsed,
            sleeping = sleeping,
            running = npc.execution?.action == ActionId.RUN && npc.execution.path.isNotEmpty()
        )
    }

    private fun advanceOne(world: WorldState, npc: WorldNpc, now: Long): Pair<WorldNpc, WorldState> {
        val definition = WorldNpcCatalog[npc.id]
        val calendar = WorldClock.at(now, world.timezoneOffsetMinutes)
        val phase = if (npc.stationary) "stationary" else schedule(npc, calendar)
        var actor = npc.execution ?: WorldActor(actorId = npc.id, actorType = ActorType.NPC, x = npc.x, y = npc.y,
            preciseX = npc.preciseX, preciseY = npc.preciseY, presence = PresenceMode.WORLD,
            structureId = null, needs = npc.needs)
        actor = actor.copy(needs = npc.needs)
        var current = growFields(npc, world.tick)
        var command: WorldCommand? = null
        val phaseChanged = phase != npc.scheduleState && npc.scheduleState !in setOf("eating", "drinking", "resting", "sleeping")
        if (phaseChanged && actor.path.isNotEmpty()) actor = actor.copy(path = emptyList(), actionTicksRemaining = 0,
            pendingCommand = null, pendingStructureId = null, destinationX = null, destinationY = null)
        val busy = actor.path.isNotEmpty() || actor.actionState == ActionState.RUNNING && actor.actionTicksRemaining > 0
        if (!busy && now >= npc.lastDecisionAt) {
            val decision = decide(world, current, actor, phase, now)
            actor = decision.actor
            command = decision.command
            current = current.copy(scheduleState = decision.schedule,
                decisionCounter = current.decisionCounter + 1,
                lastDecisionAt = now + decision.delayMs)
        }
        val personalPlots = current.ownFarm.map { FarmPlotReference(it.id, it.x, it.y) }
        // NPC knowledge stays private. Paths are planned against known home/job
        // routes and local targets, never written into the pet's fog of war.
        val localKnowledge = (-6..6).flatMap { dy -> (-6..6).map { dx -> actor.coordinate + WorldCoordinate(dx, dy) } }
            .filter(world::contains)
        val known = (world.roads + actor.path + localKnowledge + current.ownFarm.map { WorldCoordinate(it.x, it.y) }).distinct()
        // A transient target projection lets the same executor validate a nearby pet's position.
        // It is never simulated as an NPC, returned to the world, or persisted in the inhabitants.
        val petTarget = if (world.petId.isNotBlank() && world.actor.presence == PresenceMode.WORLD)
            listOf(WorldNpc(world.petId, world.petId, NpcRole.PARK_RESIDENT, world.actor.x, world.actor.y))
        else emptyList()
        val personalWorld = world.copy(actor = actor, petId = npc.id,
            tick = (world.tick - 1).coerceAtLeast(0), lastSimulatedAt = now - DEFAULT_TICK_MILLIS,
            farmPlots = personalPlots, explored = known,
            npcs = world.npcs.filter { it.id != npc.id } + petTarget,
            knownPlaces = world.structures.map { KnownPlace(it.id, it.type.name, it.entrance.x, it.entrance.y) })
        val projection = CanonicalPetSnapshot(petId = npc.id, needs = current.needs,
            personality = definition.personality, money = current.money, inventory = current.inventory,
            relationships = current.relationships, farm = FarmSnapshot(current.ownFarm),
            autonomy = AutonomyPolicy(level = AutonomyLevel.OFF))
        val result = WorldSimulation.step(personalWorld, command, projection, now,
            WorldSimulationOptions(decayPetNeeds = false, observeRadius = 0, simulateNpcs = false))
        val completed = result.state.actor.actionState == ActionState.COMPLETED &&
            (actor.actionState == ActionState.RUNNING || command is WorldCommand.PerformAction)
        current = applyEffects(current, result.effects, world.tick)
        if (completed && result.state.actor.action == ActionId.WORK) current = completeJob(current)
        if (completed && result.state.actor.action == ActionId.USE && actor.actionTargetId == "settlement_well") {
            current = current.copy(inventory = inventoryDelta(current.inventory, "water", 8))
        }
        val nextActor = result.state.actor.copy(needs = current.needs)
        current = current.copy(x = nextActor.x, y = nextActor.y, preciseX = nextActor.preciseX,
            preciseY = nextActor.preciseY, facing = nextActor.facing, currentGoal = nextActor.goal,
            currentAction = nextActor.action, actionState = nextActor.actionState, path = nextActor.path,
            destinationX = nextActor.destinationX, destinationY = nextActor.destinationY,
            execution = nextActor)
        // Only environmental modifications cross back. NPC-specific effect
        // requests are consumed above and never returned to the pet adapter.
        var changedWorld = world.copy(deltas = result.state.deltas, objects = result.state.objects)
        result.effects.filterIsInstance<WorldEffectRequest.RelationshipDelta>().forEach { effect ->
            val peer = changedWorld.npcs.firstOrNull { it.id == effect.npcId } ?: return@forEach
            val prior = peer.relationships[npc.id] ?: RelationshipProjection()
            changedWorld = replace(changedWorld, peer.copy(relationships = peer.relationships +
                (npc.id to prior.copy(familiarity = (prior.familiarity + effect.familiarity).coerceIn(0, 100),
                    friendship = (prior.friendship + effect.friendship).coerceIn(-100, 100),
                    trust = (prior.trust + effect.trust).coerceIn(-100, 100)))))
        }
        return current to changedWorld
    }

    private data class Decision(val actor: WorldActor, val command: WorldCommand? = null,
                                val schedule: String, val delayMs: Long = 1_000L)

    private fun decide(world: WorldState, npc: WorldNpc, actor: WorldActor, phase: String, now: Long): Decision {
        fun perform(action: ActionId, target: String? = null, schedule: String = phase,
                    arguments: Map<String, String> = emptyMap(), delay: Long = JOB_INTERVAL_MS) =
            Decision(actor, WorldCommand.PerformAction(action, targetId = target, arguments = arguments), schedule, delay)
        val canMeetPet = world.petId.isNotBlank() && world.actor.presence == PresenceMode.WORLD &&
            actor.coordinate.chebyshevDistanceTo(world.actor.coordinate) <= 1 && phase != "home"
        if (canMeetPet && (npc.relationships[world.petId] == null || npc.needs.social < 60f)) {
            val action = if (npc.relationships[world.petId] == null) ActionId.GREET else ActionId.TALK
            return Decision(actor, WorldCommand.PerformAction(action, world.petId, world.actor.x, world.actor.y),
                if (npc.stationary) "stationary" else "social", 60_000L)
        }
        if (npc.stationary) return Decision(actor.copy(action = ActionId.WAIT, actionState = ActionState.IDLE,
            goal = GoalId.IDLE), schedule = "stationary", delayMs = 1_000L)
        val food = npc.inventory.firstOrNull { it.kind == InventoryKind.FOOD && it.quantity > 0 }
        if (npc.needs.hunger < 45 && food != null) return perform(ActionId.EAT, food.itemId, "eating", delay = 10_000L)
        val water = npc.inventory.firstOrNull { it.kind == InventoryKind.WATER && it.quantity > 0 }
        if (npc.needs.hydration < 45 && water != null) return perform(ActionId.DRINK, water.itemId, "drinking", delay = 10_000L)
        if (water == null && (npc.needs.hydration < 45 || npc.role == NpcRole.FARMER)) {
            val well = world.objects.firstOrNull { it.id == "settlement_well" }
            if (well != null) {
                val coordinate = WorldCoordinate(well.x, well.y)
                if (actor.coordinate.chebyshevDistanceTo(coordinate) > 1) {
                    return route(world, actor, adjacentWalkable(world, coordinate, actor.coordinate), "drinking")
                }
                return perform(ActionId.USE, well.id, "drinking", delay = 2_000L)
            }
        }
        if (npc.needs.hunger < 45 && food == null) {
            val resource = localResource(world, actor.coordinate, npc)?.takeIf {
                it.type in setOf(WorldObjectType.BERRY_PATCH, WorldObjectType.MUSHROOM) }
            if (resource != null) {
                val coordinate = WorldCoordinate(resource.x, resource.y)
                if (actor.coordinate.chebyshevDistanceTo(coordinate) > 1)
                    return route(world, actor, adjacentWalkable(world, coordinate, actor.coordinate), "eating")
                return perform(ActionId.FORAGE, resource.id, "eating", delay = 2_000L)
            }
        }
        if (npc.needs.energy < 20) return perform(ActionId.REST, schedule = "resting", delay = 15_000L)
        val target = when (phase) {
            "home" -> npc.homeAnchor
            "social" -> npc.socialAnchor
            "wandering" -> wanderTarget(world, npc)
            else -> npc.jobAnchor
        } ?: actor.coordinate
        val tendingOwnFields = phase == "working" && npc.role == NpcRole.FARMER &&
            npc.ownFarm.any { actor.coordinate.chebyshevDistanceTo(WorldCoordinate(it.x, it.y)) <= 12 }
        if (!tendingOwnFields && actor.coordinate != target &&
            (phase != "wandering" || actor.coordinate.chebyshevDistanceTo(target) > 2)) {
            return route(world, actor, target, phase)
        }
        if (phase == "home") {
            val home = actor.copy(presence = PresenceMode.HOME, structureId = npc.homeStructureId,
                action = ActionId.WAIT, actionState = ActionState.IDLE)
            return Decision(home, WorldCommand.PerformAction(ActionId.SLEEP), "sleeping", 60_000L)
        }
        if (actor.presence != PresenceMode.WORLD) return Decision(actor.copy(presence = PresenceMode.WORLD,
            structureId = null, actionState = ActionState.IDLE), schedule = phase, delayMs = 0)
        if (phase == "social") {
            val friend = world.npcs.filter { it.id != npc.id && it.coordinate.chebyshevDistanceTo(actor.coordinate) <= 1 }
                .minByOrNull { it.id }
            if (friend != null) return perform(ActionId.TALK, friend.id)
            val nearby = world.npcs.filter { it.id != npc.id && it.coordinate.chebyshevDistanceTo(actor.coordinate) <= 8 }
                .minWithOrNull(compareBy<WorldNpc> { it.coordinate.manhattanDistanceTo(actor.coordinate) }.thenBy { it.id })
            if (nearby != null) return route(world, actor, adjacentWalkable(world, nearby.coordinate, actor.coordinate), phase)
            return perform(ActionId.SIT, delay = 30_000L)
        }
        if (npc.role == NpcRole.FARMER) return tendField(world, npc, actor, now)
        if (phase == "wandering" || npc.role in setOf(NpcRole.ADVENTURER, NpcRole.RECYCLER)) {
            val resource = localResource(world, actor.coordinate, npc)
            if (resource != null) {
                if (actor.coordinate.chebyshevDistanceTo(WorldCoordinate(resource.x, resource.y)) > 1) {
                    return route(world, actor, adjacentWalkable(world, WorldCoordinate(resource.x, resource.y), actor.coordinate), phase)
                }
                val action = when (resource.type) {
                    // Trees require the adventurer's axe. Fallen logs are the
                    // only object that may use GATHER_WOOD, so NPC resource
                    // intents obey the same ownership/tool boundary as the
                    // player action registry.
                    WorldObjectType.TREE -> ActionId.CHOP_TREE
                    WorldObjectType.FALLEN_LOG -> ActionId.GATHER_WOOD
                    WorldObjectType.STONE -> ActionId.GATHER_STONE
                    WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT -> ActionId.GATHER_HERB
                    WorldObjectType.SHELL -> ActionId.PICK_UP
                    else -> ActionId.FORAGE
                }
                return perform(action, resource.id, delay = 20_000L)
            }
            return perform(ActionId.LOOK, delay = 30_000L)
        }
        val workplace = world.structures.firstOrNull { it.id == npc.jobStructureId }
        if (workplace != null) {
            val indoor = actor.copy(presence = PresenceMode.INTERIOR, structureId = workplace.id)
            return Decision(indoor, WorldCommand.PerformAction(ActionId.WORK, workplace.id,
                arguments = mapOf("npcRole" to npc.role.name)), "working", JOB_INTERVAL_MS)
        }
        return perform(ActionId.WAIT)
    }

    private fun tendField(world: WorldState, npc: WorldNpc, actor: WorldActor, now: Long): Decision {
        val plot = npc.ownFarm.firstOrNull { it.isReady || it.cropId == null || it.status != "wet_farmland" }
        if (plot == null) return Decision(actor, WorldCommand.PerformAction(ActionId.REST), "working", now % 5_000 + 10_000)
        val position = WorldCoordinate(plot.x, plot.y)
        if (actor.coordinate.chebyshevDistanceTo(position) > 1) {
            return route(world, actor, adjacentWalkable(world, position, actor.coordinate), "working")
        }
        val action = when {
            plot.isReady -> ActionId.HARVEST_CROP
            plot.status == "soil" -> ActionId.TILL_SOIL
            plot.cropId == null -> ActionId.PLANT
            else -> ActionId.WATER
        }
        return Decision(actor, WorldCommand.PerformAction(action, plot.id,
            arguments = if (action == ActionId.PLANT) mapOf("cropId" to "carrot") else emptyMap()),
            "working", 2_000L)
    }

    private fun route(world: WorldState, actor: WorldActor, target: WorldCoordinate, phase: String): Decision {
        val safeTarget = Pathfinder.nearestWalkable(world, target) ?: actor.coordinate
        val path = Pathfinder.findPath(world, actor.coordinate, safeTarget, maxExpanded = 8_192)
        return Decision(actor.copy(presence = PresenceMode.WORLD, structureId = null, path = path,
            destinationX = safeTarget.x, destinationY = safeTarget.y, pendingCommand = null,
            pendingStructureId = null, actionTicksRemaining = 0, action = ActionId.WALK,
            actionState = if (path.isEmpty()) ActionState.IDLE else ActionState.RUNNING,
            goal = when (phase) { "home" -> GoalId.RETURN_HOME; "social" -> GoalId.SOCIALIZE; "working" -> GoalId.WORK; else -> GoalId.WANDER }),
            schedule = phase, delayMs = 1_000L)
    }

    private fun wanderTarget(world: WorldState, npc: WorldNpc): WorldCoordinate {
        val center = npc.jobAnchor ?: npc.coordinate
        val phase = npc.decisionCounter / 4
        val dx = DeterministicRandom.int(world.seed, 13, npc.id.hashCode().toLong(), phase, 701L) - 6
        val dy = DeterministicRandom.int(world.seed, 13, npc.id.hashCode().toLong(), phase, 709L) - 6
        return Pathfinder.nearestWalkable(world, center + WorldCoordinate(dx, dy), maxRadius = 8) ?: center
    }

    private fun localResource(world: WorldState, center: WorldCoordinate, npc: WorldNpc): WorldObject? {
        val candidates = ArrayList<WorldObject>()
        val canChopTrees = npc.role == NpcRole.ADVENTURER && npc.inventory.any {
            it.quantity > 0 && (it.itemId == "axe" || it.itemId.startsWith("axe_"))
        }
        for (dy in -5..5) for (dx in -5..5) {
            val item = WorldGenerator.objectAt(world, center.x + dx, center.y + dy) ?: continue
            if (item.quantity <= 0) continue
            if (item.type in setOf(WorldObjectType.BERRY_PATCH, WorldObjectType.MUSHROOM, WorldObjectType.HERB_PATCH,
                    WorldObjectType.GLOWING_PLANT, WorldObjectType.SHELL, WorldObjectType.FALLEN_LOG) ||
                (npc.role == NpcRole.ADVENTURER && item.type == WorldObjectType.STONE) ||
                (canChopTrees && item.type == WorldObjectType.TREE)) candidates += item
        }
        return candidates.minWithOrNull(compareBy<WorldObject> { abs(it.x - center.x) + abs(it.y - center.y) }.thenBy { it.id })
    }

    private fun adjacentWalkable(world: WorldState, target: WorldCoordinate, from: WorldCoordinate): WorldCoordinate =
        (-1..1).flatMap { dy -> (-1..1).map { dx -> target + WorldCoordinate(dx, dy) } }
            .filter { Pathfinder.walkable(world, it) }
            .minWithOrNull(compareBy<WorldCoordinate> { it.manhattanDistanceTo(from) }.thenBy { it.y }.thenBy { it.x }) ?: from

    private fun schedule(npc: WorldNpc, calendar: WorldCalendarTime): String {
        val minute = calendar.minuteOfDay
        return when {
            npc.role == NpcRole.RECYCLER -> if (calendar.dayOfMonth in setOf(10, 20, 30) && minute in 480..960) "working" else "home"
            npc.role == NpcRole.MARKET_SELLER -> if (calendar.dayOfWeek == 4 && minute in 480..1_020) "working" else "home"
            minute !in 420..1_260 -> "home"
            minute > 1_080 -> "social"
            npc.role == NpcRole.PARK_RESIDENT -> "wandering"
            else -> "working"
        }
    }

    private fun applyEffects(npc: WorldNpc, effects: List<WorldEffectRequest>, tick: Long): WorldNpc {
        var current = npc
        effects.forEach { effect ->
            current = when (effect) {
                is WorldEffectRequest.NeedDelta -> current.copy(needs = current.needs.plus(effect.need, effect.delta))
                is WorldEffectRequest.InventoryDelta -> current.copy(inventory = inventoryDelta(current.inventory, effect.itemId, effect.quantity))
                is WorldEffectRequest.MoneyDelta -> current.copy(money = (current.money + effect.amount).coerceAtLeast(0))
                is WorldEffectRequest.RelationshipDelta -> {
                    val previous = current.relationships[effect.npcId] ?: RelationshipProjection()
                    current.copy(relationships = current.relationships + (effect.npcId to RelationshipProjection(
                        (previous.familiarity + effect.familiarity).coerceIn(0, 100),
                        (previous.friendship + effect.friendship).coerceIn(-100, 100),
                        (previous.trust + effect.trust).coerceIn(-100, 100))))
                }
                is WorldEffectRequest.FarmTransition -> applyField(current, effect, tick)
                else -> current
            }
        }
        return current
    }

    private fun applyField(npc: WorldNpc, effect: WorldEffectRequest.FarmTransition, tick: Long): WorldNpc {
        val plot = npc.ownFarm.firstOrNull { it.id == effect.plotId } ?: return npc
        check(!plot.userOwned && plot.id.startsWith("npc_${npc.id}_"))
        var inventory = npc.inventory
        val updated = when (effect.action) {
            FarmActionKind.TILL_SOIL -> plot.copy(status = "farmland")
            FarmActionKind.PLANT -> {
                inventory = inventoryDelta(inventory, "seed_${effect.cropId ?: "carrot"}", -1)
                plot.copy(cropId = effect.cropId ?: "carrot", plantedAtTick = tick)
            }
            FarmActionKind.WATER, FarmActionKind.POUR_WATER -> {
                inventory = inventoryDelta(inventory, "water", -1)
                plot.copy(status = "wet_farmland", wateredAtTick = tick)
            }
            FarmActionKind.HARVEST_CROP -> {
                inventory = inventoryDelta(inventory, "crop_${plot.cropId}", 1)
                inventory = inventoryDelta(inventory, "seed_${plot.cropId}", 1)
                plot.copy(cropId = null, status = "soil", isReady = false)
            }
            FarmActionKind.REMOVE_DEAD_CROP -> plot.copy(cropId = null, status = "soil", isDead = false)
            else -> plot
        }
        return npc.copy(inventory = inventory, ownFarm = npc.ownFarm.map { if (it.id == plot.id) updated else it })
    }

    private fun growFields(npc: WorldNpc, tick: Long): WorldNpc = npc.copy(ownFarm = npc.ownFarm.map { plot ->
        if (plot.cropId != null && plot.status == "wet_farmland" && tick - plot.plantedAtTick >= 6_000)
            plot.copy(isReady = true) else plot
    })

    private fun completeJob(npc: WorldNpc): WorldNpc {
        val product = when (npc.role) {
            NpcRole.SHOP_SELLER, NpcRole.MARKET_SELLER -> "berry"
            NpcRole.ALCHEMIST -> "herb"
            NpcRole.DOCTOR -> "medicine"
            NpcRole.TEACHER, NpcRole.WORKPLACE_RESIDENT -> "paper"
            NpcRole.RECYCLER -> "recycled_material"
            else -> null
        }
        var inventory = npc.inventory
        if (product != null) inventory = inventoryDelta(inventory, product, 1)
        // Scheduled workers refill their own lunch/water supplies, never the pet's.
        if (inventory.none { it.itemId == "water" && it.quantity > 0 }) inventory = inventoryDelta(inventory, "water", 4)
        if (inventory.none { it.kind == InventoryKind.FOOD && it.quantity > 0 }) inventory = inventoryDelta(inventory, "berry", 4)
        return npc.copy(completedJobs = npc.completedJobs + 1, inventory = inventory, money = (npc.money + 1).coerceAtMost(999_999))
    }

    private fun inventoryDelta(items: List<InventoryStack>, id: String, amount: Int): List<InventoryStack> {
        val previous = items.firstOrNull { it.itemId == id }
        val quantity = ((previous?.quantity ?: 0) + amount).coerceIn(0, 999)
        val result = items.filterNot { it.itemId == id }
        return if (quantity == 0) result else result + InventoryStack(id, quantity, previous?.kind ?: InventoryKinds.infer(id))
    }

    private fun replace(state: WorldState, npc: WorldNpc): WorldState =
        state.copy(npcs = state.npcs.map { if (it.id == npc.id) npc else it })
}
