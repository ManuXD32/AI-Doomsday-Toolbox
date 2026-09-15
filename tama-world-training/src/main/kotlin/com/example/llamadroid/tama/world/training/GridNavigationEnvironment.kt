package com.example.llamadroid.tama.world.training

import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.policy.Observation
import com.example.llamadroid.tama.world.policy.PolicySpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Small deterministic reference environment. World-core may supply a richer implementation of
 * [TrainingWorldEnvironment], while this environment keeps the policy module testable without
 * Android or Room and exercises the same semantic observation/action contract.
 */
class GridNavigationEnvironment(
    override val environmentId: Int,
    private val fixedTargetForVisibleCurriculum: Boolean = true
) : TrainingWorldEnvironment {
    private var seed = 0L
    private var curriculum = CurriculumLevel.VISIBLE_TARGET
    private var definition = CurriculumCatalog.definition(curriculum)
    private var width = definition.worldWidth
    private var height = definition.worldHeight
    private var x = 1
    private var y = 1
    private var goalX = width - 2
    private var goalY = height - 2
    private var episodeStep = 0
    private var terminated = false
    private var success = false
    private var hunger = 1f
    private var hydration = 1f
    private var energy = 1f
    private var health = 1f
    private var hygiene = 1f
    private var happiness = 1f
    private var social = 1f
    private var curiosity = 1f
    private var lastAction = MovementIntent.WAIT
    private var visited = HashSet<Long>()
    private var knownTiles = HashSet<Long>()
    private var gatheredResourceKeys = HashSet<Long>()
    private var consumedFoodKeys = HashSet<Long>()
    private var consumedWaterKeys = HashSet<Long>()
    private var farmedCropKeys = HashSet<Long>()
    private var visitedBiomeClasses = HashSet<Int>()
    private var wood = 0
    private var berries = 0
    private var herbs = 0
    private var food = 0
    private var cropStage = 0
    private var socialInteractions = 0
    private var farmPlotKey: Long? = null
    private var leftNpcAfterInteraction = false
    private var returnedToNpcAfterInteraction = false
    private var resourceGatheringLeftHome = false
    private var movementSteps = 0
    private var runningSteps = 0
    private var restSteps = 0
    private var sleepSteps = 0
    private var recoveredNeedsMask = 0
    private var lastPositionKey: String? = null
    private var repeatedPositionCount = 0
    private var recentFailureCount = 0
    /** Derived from the full disposable map; never included in policy observations. */
    private var shortestFeasibleRouteSteps: Int? = null
    private var totalTraversableTileCount = 0

    override fun reset(seed: Long, curriculum: CurriculumLevel): Observation {
        this.seed = seed
        this.curriculum = curriculum
        definition = CurriculumCatalog.definition(curriculum)
        width = definition.worldWidth
        height = definition.worldHeight
        // The randomized visible-target task starts in the interior so evaluation covers all
        // compass directions. The legacy fixed-target variant keeps its corner spawn for the
        // deterministic smoke tests and for backwards-compatible checkpoints.
        x = if (!fixedTargetForVisibleCurriculum && curriculum == CurriculumLevel.VISIBLE_TARGET) {
            width / 2
        } else {
            1
        }
        y = if (!fixedTargetForVisibleCurriculum && curriculum == CurriculumLevel.VISIBLE_TARGET) {
            height / 2
        } else {
            1
        }
        val goalSeed = mix(seed)
        val hiddenFood = if (curriculum == CurriculumLevel.HIDDEN_TARGET_SEARCH) {
            findFirstCell(::isFood, goalSeed, width, height)
        } else {
            null
        }
        goalX = when {
            fixedTargetForVisibleCurriculum && curriculum == CurriculumLevel.VISIBLE_TARGET -> width - 2
            curriculum == CurriculumLevel.VISIBLE_TARGET -> visibleTargetCoordinate(goalSeed, width, x)
            curriculum == CurriculumLevel.HIDDEN_TARGET_SEARCH -> requireNotNull(hiddenFood).first
            else -> 2 + floorMod(goalSeed, max(1, width - 4))
        }
        goalY = when {
            fixedTargetForVisibleCurriculum && curriculum == CurriculumLevel.VISIBLE_TARGET -> height - 2
            curriculum == CurriculumLevel.VISIBLE_TARGET -> visibleTargetCoordinate(goalSeed ushr 32, height, y)
            curriculum == CurriculumLevel.HIDDEN_TARGET_SEARCH -> requireNotNull(hiddenFood).second
            else -> 2 + floorMod(goalSeed ushr 32, max(1, height - 4))
        }
        if (goalX == x && goalY == y) goalX = min(width - 2, x + 1)
        episodeStep = 0
        terminated = false
        success = false
        val needsStartActive = curriculum == CurriculumLevel.HIDDEN_TARGET_SEARCH ||
            curriculum == CurriculumLevel.MULTI_NEED_SURVIVAL
        hunger = if (needsStartActive) 0.65f else 1f
        hydration = if (needsStartActive && curriculum == CurriculumLevel.MULTI_NEED_SURVIVAL) 0.55f else 1f
        energy = if (needsStartActive && curriculum == CurriculumLevel.MULTI_NEED_SURVIVAL) 0.55f else 1f
        health = 1f
        hygiene = 1f
        happiness = 1f
        social = 1f
        curiosity = 1f
        lastAction = MovementIntent.WAIT
        visited = hashSetOf(positionKey(x, y))
        knownTiles = HashSet()
        gatheredResourceKeys = HashSet()
        consumedFoodKeys = HashSet()
        consumedWaterKeys = HashSet()
        farmedCropKeys = HashSet()
        visitedBiomeClasses = hashSetOf(biomeClass(x, y))
        wood = 0
        berries = 0
        herbs = 0
        food = 0
        cropStage = 0
        socialInteractions = 0
        farmPlotKey = null
        leftNpcAfterInteraction = false
        returnedToNpcAfterInteraction = false
        resourceGatheringLeftHome = false
        movementSteps = 0
        runningSteps = 0
        restSteps = 0
        sleepSteps = 0
        recoveredNeedsMask = 0
        lastPositionKey = null
        repeatedPositionCount = 0
        recentFailureCount = 0
        revealKnownTiles()
        totalTraversableTileCount = countTraversableTiles()
        shortestFeasibleRouteSteps = if (canCompleteNavigationTarget()) {
            shortestRouteLength(x, y, goalX, goalY)
        } else {
            null
        }
        return observation()
    }

    override fun step(action: MovementIntent): EnvironmentStep {
        if (terminated) {
            return EnvironmentStep(
                observation = observation(),
                reward = RewardBreakdown(),
                terminated = true,
                success = success,
                preview = preview()
            )
        }

        val previousGoalKnown = goalIsKnown()
        val previousDistance = distanceToGoal()
        episodeStep += 1
        lastAction = action
        var valid = true
        var reached = false
        var objective = 0f
        var exploration = 0f
        var needReward = 0f
        var socialReward = 0f
        var efficiencyReward = 0f
        var danger = 0f
        var moved = false
        var discoveryKey: String? = null
        var socialInteractionKey: String? = null
        var foodConsumedWhileNeedy = false
        var completedFarmingTransition = false

        val (dx, dy) = actionVector(action)
        if (action in MOVEMENT_ACTIONS) {
            val nextX = x + dx
            val nextY = y + dy
            if (!isWalkable(nextX, nextY)) {
                valid = false
                danger = if (isWater(nextX, nextY)) 0.5f else 0f
            } else {
                x = nextX
                y = nextY
                moved = dx != 0 || dy != 0
                energy = (energy - if (action == MovementIntent.WAIT) 0f else 0.0125f).coerceAtLeast(0f)
                val key = positionKey(x, y)
                if (moved) {
                    movementSteps += 1
                    if (dx != 0 && dy != 0) runningSteps += 1
                    if (curriculum == CurriculumLevel.RESOURCE_GATHERING && !isAtHome()) {
                        resourceGatheringLeftHome = true
                    }
                    if (curriculum == CurriculumLevel.SOCIAL_NAVIGATION && socialInteractions > 0 && !isAtGoal()) {
                        leftNpcAfterInteraction = true
                    }
                    if (definition.hasBiomes) {
                        val newBiome = visitedBiomeClasses.add(biomeClass(x, y))
                        if (newBiome && curriculum == CurriculumLevel.BIOME_NAVIGATION) {
                            objective += 0.5f
                        }
                    }
                } else if (action == MovementIntent.WAIT) {
                    if (energy < 0.7f) restSteps += 1 else sleepSteps += 1
                }
                if (visited.add(key)) {
                    // In the visible-target curriculum the objective is already known, so a
                    // smaller discovery bonus keeps random wandering from dominating the dense
                    // goal-potential signal. Hidden curricula retain the full exploration bonus.
                    exploration = if (definition.visibleGoal) 0.05f else 0.2f
                    discoveryKey = key.toString()
                }
                revealKnownTiles()
            }
        }

        if (valid && action == MovementIntent.INTERACT) {
            when {
                hasAvailableGatherableResource(x, y) -> {
                    when (resourceKind(x, y)) {
                        RESOURCE_WOOD -> wood += 1
                        RESOURCE_BERRIES -> berries += 1
                        RESOURCE_HERBS -> herbs += 1
                    }
                    gatheredResourceKeys += positionKey(x, y)
                    objective += if (curriculum == CurriculumLevel.RESOURCE_GATHERING) 1.5f else 0.5f
                    happiness = min(1f, happiness + 0.03f)
                }
                definition.hasNeeds && hasAvailableFood(x, y) && isNeedy() -> {
                    consumedFoodKeys += positionKey(x, y)
                    food += 1
                    foodConsumedWhileNeedy = true
                    needReward = 4f
                    hunger = min(1f, hunger + 0.5f)
                    hydration = min(1f, hydration + 0.5f)
                    health = min(1f, health + 0.03f)
                }
                definition.hasNeeds && hasAvailableWater(x, y) && hydration < 0.75f -> {
                    consumedWaterKeys += positionKey(x, y)
                    foodConsumedWhileNeedy = false
                    needReward = 4f
                    hydration = min(1f, hydration + 0.5f)
                    health = min(1f, health + 0.02f)
                }
                definition.hasFarming && canAdvanceFarmAt(x, y) -> {
                    val farmKey = positionKey(x, y)
                    when (cropStage) {
                        0 -> {
                            farmPlotKey = farmKey
                            wood -= 1
                            cropStage = 1
                            objective += 1f
                        }
                        1 -> {
                            cropStage = 2
                            objective += 1f
                        }
                        2 -> {
                            cropStage = 3
                            farmedCropKeys += farmKey
                            objective += 4f
                            happiness = min(1f, happiness + 0.05f)
                        }
                    }
                    completedFarmingTransition = true
                }
                definition.hasSocialGoals && isAtGoal() -> {
                    // A second greeting only counts after the pet has actually left the NPC
                    // tile, so repeated INTERACT calls cannot complete the social curriculum.
                    if (socialInteractions == 0 || leftNpcAfterInteraction) {
                        if (leftNpcAfterInteraction) returnedToNpcAfterInteraction = true
                        socialInteractions += 1
                        socialReward = 1f
                        socialInteractionKey = "goal-npc"
                        objective += if (socialInteractions == 1) 1f else 2f
                        social = min(1f, social + 0.35f)
                        happiness = min(1f, happiness + 0.1f)
                    }
                }
                canCompleteNavigationTarget() && isAtGoal() -> {
                    // Reaching the target is explicit: the policy must choose INTERACT/SEARCH.
                    reached = true
                    objective = 10f
                }
                else -> valid = false
            }
        } else if (valid && action == MovementIntent.SEARCH) {
            if (canCompleteNavigationTarget() && isAtGoal()) {
                reached = true
                objective = 10f
            } else if (!hasNavigationTarget() || !definition.hiddenGoal) {
                valid = false
            } else {
                curiosity = min(1f, curiosity + 0.02f)
            }
        } else if (canCompleteNavigationTarget() && isAtGoal() && action in MOVEMENT_ACTIONS) {
            // Reaching the target is explicit: the policy must choose INTERACT/SEARCH next.
            objective = 0.5f
        }

        if (definition.hasNeeds) {
            hunger = (hunger - 0.004f).coerceAtLeast(0f)
            hydration = (hydration - 0.005f).coerceAtLeast(0f)
            hygiene = (hygiene - 0.001f).coerceAtLeast(0f)
            social = (social - 0.001f).coerceAtLeast(0f)
            if (action == MovementIntent.WAIT) energy = min(1f, energy + 0.02f)
            if (hunger < 0.2f || hydration < 0.2f || energy < 0.2f) health = (health - 0.002f).coerceAtLeast(0f)
            else health = min(1f, health + 0.001f)
        }

        if (curriculum == CurriculumLevel.MULTI_NEED_SURVIVAL) {
            if (hunger >= 0.75f) recoveredNeedsMask = recoveredNeedsMask or NEED_HUNGER
            if (hydration >= 0.75f) recoveredNeedsMask = recoveredNeedsMask or NEED_HYDRATION
            if (energy >= 0.75f) recoveredNeedsMask = recoveredNeedsMask or NEED_ENERGY
        }

        val repetitionKey = "${x}:${y}:${action.wireId}"
        if (repetitionKey == lastPositionKey) repeatedPositionCount += 1 else repeatedPositionCount = 0
        lastPositionKey = repetitionKey
        val stuck = repeatedPositionCount >= 3
        if (stuck) repeatedPositionCount = 0

        if (moved.not() && action in MOVEMENT_ACTIONS && valid.not()) {
            energy = (energy - 0.002f).coerceAtLeast(0f)
        }

        val currentDistance = distanceToGoal()
        if (moved) {
            // Only a visible target, or a hidden target after its tile is discovered, may provide
            // distance shaping. This keeps the hidden-search curriculum from leaking the exact
            // target location through rewards before SEARCH has revealed it.
            val targetKnownForShaping = previousGoalKnown && goalIsKnown()
            val distanceScale = if (definition.visibleGoal) 0.5f else 0.2f / (width + height).toFloat()
            efficiencyReward = -0.002f + if (targetKnownForShaping) {
                (previousDistance - currentDistance).coerceIn(-1, 1) * distanceScale
            } else {
                0f
            }
            curiosity = min(1f, curiosity + if (exploration > 0f) 0.05f else 0.002f)
        }
        recentFailureCount = if (valid) max(0, recentFailureCount - 1) else (recentFailureCount + 1).coerceAtMost(20)

        val taskCompleted = when (curriculum) {
            CurriculumLevel.LOCOMOTION_VALIDATION -> false
            CurriculumLevel.VISIBLE_TARGET, CurriculumLevel.UNRESTRICTED_GENERALIZATION -> false
            CurriculumLevel.HIDDEN_TARGET_SEARCH -> consumedFoodKeys.isNotEmpty()
            CurriculumLevel.FOOD_AND_WATER -> consumedFoodKeys.isNotEmpty() && consumedWaterKeys.isNotEmpty()
            CurriculumLevel.ENERGY_MANAGEMENT -> movementSteps >= ENERGY_MOVEMENT_TARGET &&
                runningSteps >= ENERGY_RUNNING_TARGET && restSteps >= ENERGY_REST_TARGET &&
                sleepSteps >= ENERGY_SLEEP_TARGET
            CurriculumLevel.BIOME_NAVIGATION -> visitedBiomeClasses.size >= BIOME_TARGET_COUNT
            CurriculumLevel.RESOURCE_GATHERING -> wood > 0 && berries > 0 && herbs > 0 &&
                resourceGatheringLeftHome && isAtHome()
            CurriculumLevel.SOCIAL_NAVIGATION -> socialInteractions >= SOCIAL_INTERACTION_TARGET &&
                returnedToNpcAfterInteraction
            CurriculumLevel.FARMING -> cropStage >= FARM_HARVEST_STAGE
            CurriculumLevel.MULTI_NEED_SURVIVAL -> recoveredNeedsMask == NEED_ALL
        }
        reached = reached || taskCompleted
        val criticalNeedFailure = definition.hasNeeds && (
            hunger <= 0.01f || hydration <= 0.01f || energy <= 0.01f || health <= 0.01f
            )
        val truncated = episodeStep >= episodeLimit()
        success = reached
        terminated = reached || truncated || criticalNeedFailure
        val reward = RewardBreakdown(
            objectiveReward = objective,
            needReward = needReward,
            explorationReward = exploration,
            socialReward = socialReward,
            efficiencyReward = efficiencyReward,
            invalidPenalty = if (valid) 0f else 0.1f,
            dangerPenalty = danger,
            repetitionPenalty = if (stuck) 0.2f else 0f,
            stuckPenalty = if (criticalNeedFailure || stuck) 1f else 0f
        )
        return EnvironmentStep(
            observation = observation(),
            reward = reward,
            terminated = reached || criticalNeedFailure,
            truncated = truncated && !reached && !criticalNeedFailure,
            actionWasValid = valid,
            success = reached,
            needFailure = criticalNeedFailure,
            signals = RewardSignals(
                discoveryKey = discoveryKey,
                socialInteractionKey = socialInteractionKey,
                foodConsumedWhileNeedy = foodConsumedWhileNeedy,
                waterConsumedWhileThirsty = needReward > 0f && !foodConsumedWhileNeedy,
                completedFarmingTransition = completedFarmingTransition,
                repetitionKey = repetitionKey,
                stuck = stuck,
                dangerous = danger > 0f
            ),
            preview = preview(),
            outcome = if (reached || criticalNeedFailure || truncated) {
                EnvironmentOutcome(
                    objectiveSteps = if (reached) episodeStep else null,
                    actualMovementSteps = if (reached) movementSteps else null,
                    shortestFeasibleSteps = if (reached) shortestFeasibleRouteSteps else null,
                    discoveredTraversableTiles = knownTraversableTileCount(),
                    totalTraversableTiles = totalTraversableTileCount
                )
            } else {
                null
            }
        )
    }

    override fun preview(): EnvironmentPreview = EnvironmentPreview(
        environmentId = environmentId,
        seed = seed,
        curriculum = curriculum,
        x = x,
        y = y,
        goalX = goalX,
        goalY = goalY,
        episodeStep = episodeStep,
        lastAction = lastAction,
        terminated = terminated,
        success = success,
        observation = observation(),
        needs = NeedSnapshot(
            hunger = hunger,
            hydration = hydration,
            energy = energy,
            health = health,
            hygiene = hygiene,
            happiness = happiness,
            social = social,
            curiosity = curiosity
        ),
        wood = wood,
        berries = berries,
        herbs = herbs,
        food = food,
        cropStage = cropStage,
        socialInteractions = socialInteractions
    )

    override fun checkpointState(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(CHECKPOINT_VERSION)
            data.writeLong(seed)
            data.writeInt(curriculum.id)
            data.writeInt(x)
            data.writeInt(y)
            data.writeInt(goalX)
            data.writeInt(goalY)
            data.writeInt(episodeStep)
            data.writeBoolean(terminated)
            data.writeBoolean(success)
            data.writeFloat(hunger)
            data.writeFloat(hydration)
            data.writeFloat(energy)
            data.writeFloat(health)
            data.writeFloat(hygiene)
            data.writeFloat(happiness)
            data.writeFloat(social)
            data.writeFloat(curiosity)
            data.writeInt(lastAction.wireId)
            writeKeys(data, visited)
            writeKeys(data, knownTiles)
            writeKeys(data, gatheredResourceKeys)
            writeKeys(data, consumedFoodKeys)
            writeKeys(data, consumedWaterKeys)
            writeKeys(data, farmedCropKeys)
            writeInts(data, visitedBiomeClasses)
            data.writeInt(wood)
            data.writeInt(berries)
            data.writeInt(herbs)
            data.writeInt(food)
            data.writeInt(cropStage)
            data.writeInt(socialInteractions)
            data.writeLong(farmPlotKey ?: NO_POSITION_KEY)
            data.writeBoolean(leftNpcAfterInteraction)
            data.writeBoolean(returnedToNpcAfterInteraction)
            data.writeBoolean(resourceGatheringLeftHome)
            data.writeInt(movementSteps)
            data.writeInt(runningSteps)
            data.writeInt(restSteps)
            data.writeInt(sleepSteps)
            data.writeInt(recoveredNeedsMask)
            data.writeUTF(lastPositionKey ?: "")
            data.writeInt(repeatedPositionCount)
            data.writeInt(recentFailureCount)
        }
        return output.toByteArray()
    }

    override fun restoreCheckpointState(state: ByteArray) {
        DataInputStream(ByteArrayInputStream(state)).use { input ->
            require(input.readInt() == CHECKPOINT_VERSION) { "Unsupported grid environment checkpoint" }
            val restoredSeed = input.readLong()
            val restoredCurriculum = CurriculumLevel.fromId(input.readInt())
            reset(restoredSeed, restoredCurriculum)
            x = input.readInt()
            y = input.readInt()
            goalX = input.readInt()
            goalY = input.readInt()
            episodeStep = input.readInt()
            terminated = input.readBoolean()
            success = input.readBoolean()
            hunger = input.readFloat()
            hydration = input.readFloat()
            energy = input.readFloat()
            health = input.readFloat()
            hygiene = input.readFloat()
            happiness = input.readFloat()
            social = input.readFloat()
            curiosity = input.readFloat()
            lastAction = MovementIntent.fromWireId(input.readInt())
            visited = readKeys(input)
            knownTiles = readKeys(input)
            gatheredResourceKeys = readKeys(input)
            consumedFoodKeys = readKeys(input)
            consumedWaterKeys = readKeys(input)
            farmedCropKeys = readKeys(input)
            visitedBiomeClasses = readInts(input)
            wood = input.readInt()
            berries = input.readInt()
            herbs = input.readInt()
            food = input.readInt()
            cropStage = input.readInt()
            socialInteractions = input.readInt()
            farmPlotKey = input.readLong().let { if (it == NO_POSITION_KEY) null else it }
            leftNpcAfterInteraction = input.readBoolean()
            returnedToNpcAfterInteraction = input.readBoolean()
            resourceGatheringLeftHome = input.readBoolean()
            movementSteps = input.readInt()
            runningSteps = input.readInt()
            restSteps = input.readInt()
            sleepSteps = input.readInt()
            recoveredNeedsMask = input.readInt()
            lastPositionKey = input.readUTF().ifEmpty { null }
            repeatedPositionCount = input.readInt()
            recentFailureCount = input.readInt()
            require(input.available() == 0) { "Grid environment checkpoint contains trailing data" }
        }
    }

    private fun observation(): Observation {
        val side = PolicySpec.OBSERVATION_SIDE
        val channels = PolicySpec.TILE_CHANNELS
        val tiles = FloatArray(side * side * channels)
        for (localY in 0 until side) {
            for (localX in 0 until side) {
                val worldX = x + localX - PolicySpec.OBSERVATION_RADIUS
                val worldY = y + localY - PolicySpec.OBSERVATION_RADIUS
                val offset = (localY * side + localX) * channels
                val inside = worldX in 0 until width && worldY in 0 until height
                val known = inside && knownTiles.contains(positionKey(worldX, worldY))
                if (known) {
                    val walkable = isWalkable(worldX, worldY)
                    tiles[offset + 0] = if (walkable) 1f else 0f
                    tiles[offset + 1] = if (definition.hasBiomes) terrainClass(worldX, worldY) else {
                        PolicySpec.encodeTerrainClassOrdinal(0)
                    }
                    tiles[offset + 2] = PolicySpec.encodeMovementCost(
                        if (definition.hasBiomes) movementCost(worldX, worldY) else {
                            if (walkable) 1 else PolicySpec.MAX_MOVEMENT_COST
                        }
                    )
                    tiles[offset + 3] = if (definition.hasNeeds && hasAvailableWater(worldX, worldY)) 1f else 0f
                    tiles[offset + 4] = if (definition.hasNeeds && hasAvailableFood(worldX, worldY)) 1f else 0f
                    tiles[offset + 5] = if (definition.hasResources &&
                        (hasAvailableResource(worldX, worldY) || hasAvailableGatherableResource(worldX, worldY))) 1f else 0f
                    tiles[offset + 6] = if (definition.hasBiomes && isTree(worldX, worldY)) 1f else 0f
                    tiles[offset + 7] = if (isCrop(worldX, worldY) && !farmedCropKeys.contains(positionKey(worldX, worldY))) 1f else 0f
                    tiles[offset + 8] = if (isStructure(worldX, worldY)) 1f else 0f
                    tiles[offset + 9] = if (definition.hasSocialGoals && worldX == goalX && worldY == goalY) 1f else 0f
                    tiles[offset + 10] = if (isWater(worldX, worldY) && definition.hasBiomes) 1f else 0f
                    tiles[offset + 11] = 1f
                }
                val goalVisible = hasNavigationTarget() &&
                    (definition.visibleGoal || (definition.hiddenGoal && known))
                tiles[offset + 12] = if (goalVisible && worldX == goalX && worldY == goalY) 1f else 0f
            }
        }
        val distance = if (hasNavigationTarget() && goalIsKnown()) distanceToGoal() else 0
        val scalars = floatArrayOf(
            hunger,
            hydration,
            energy,
            health,
            hygiene,
            happiness,
            social,
            curiosity,
            ((wood + berries + herbs + food + cropStage + socialInteractions).coerceAtMost(100)) / 100f,
            PolicySpec.encodeCurrentGoalOrdinal(PolicySpec.trainingGoalOrdinal(curriculum.id)),
            (distance / (width + height).toFloat()).coerceIn(0f, 1f),
            (episodeStep % 24) / 24f,
            (recentFailureCount / 20f).coerceIn(0f, 1f)
        )
        val actionMask = BooleanArray(PolicySpec.ACTION_COUNT) { true }
        for (intent in MOVEMENT_ACTIONS) {
            val index = intent.wireId
            val nextX = x + intent.dx
            val nextY = y + intent.dy
            actionMask[index] = knownTiles.contains(positionKey(nextX, nextY)) &&
                isWalkable(nextX, nextY) &&
                (intent.dx == 0 || intent.dy == 0 ||
                    (knownTiles.contains(positionKey(x + intent.dx, y)) &&
                        knownTiles.contains(positionKey(x, y + intent.dy)) &&
                        isWalkable(x + intent.dx, y) && isWalkable(x, y + intent.dy)))
        }
        actionMask[MovementIntent.INTERACT.wireId] = canInteractAtCurrentPosition()
        actionMask[MovementIntent.SEARCH.wireId] = hasNavigationTarget() &&
            (definition.hiddenGoal || isAtGoal())
        return Observation(tiles, scalars, actionMask)
    }

    private fun revealKnownTiles() {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val knownX = x + dx
                val knownY = y + dy
                if (knownX in 0 until width && knownY in 0 until height) {
                    knownTiles += positionKey(knownX, knownY)
                }
            }
        }
    }

    private fun goalIsKnown(): Boolean = hasNavigationTarget() &&
        (definition.visibleGoal || knownTiles.contains(positionKey(goalX, goalY)))

    private fun distanceToGoal(): Int = abs(goalX - x) + abs(goalY - y)

    private fun countTraversableTiles(): Int {
        var count = 0
        for (worldY in 0 until height) {
            for (worldX in 0 until width) {
                if (isWalkable(worldX, worldY)) count += 1
            }
        }
        return count
    }

    private fun knownTraversableTileCount(): Int = knownTiles.count { key ->
        val worldX = (key shr 32).toInt()
        val worldY = key.toInt()
        isWalkable(worldX, worldY)
    }

    /** Breadth-first shortest route using the same walkability and diagonal corner rules as masks. */
    private fun shortestRouteLength(startX: Int, startY: Int, targetX: Int, targetY: Int): Int? {
        if (!isWalkable(startX, startY) || !isWalkable(targetX, targetY)) return null
        val distances = IntArray(width * height) { -1 }
        val queue = ArrayDeque<Int>()
        fun index(worldX: Int, worldY: Int): Int = worldY * width + worldX
        distances[index(startX, startY)] = 0
        queue.addLast(index(startX, startY))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentX = current % width
            val currentY = current / width
            if (currentX == targetX && currentY == targetY) return distances[current]
            for (intent in MOVEMENT_ACTIONS) {
                if (intent == MovementIntent.WAIT) continue
                val nextX = currentX + intent.dx
                val nextY = currentY + intent.dy
                if (!isWalkable(nextX, nextY)) continue
                if (intent.dx != 0 && intent.dy != 0 &&
                    (!isWalkable(currentX + intent.dx, currentY) ||
                        !isWalkable(currentX, currentY + intent.dy))
                ) continue
                val next = index(nextX, nextY)
                if (distances[next] >= 0) continue
                distances[next] = distances[current] + 1
                queue.addLast(next)
            }
        }
        return null
    }

    private fun isNeedy(): Boolean = hunger < 0.75f || hydration < 0.75f

    private fun hasAvailableFood(worldX: Int, worldY: Int): Boolean =
        isFood(worldX, worldY) && !consumedFoodKeys.contains(positionKey(worldX, worldY))

    private fun hasAvailableWater(worldX: Int, worldY: Int): Boolean =
        isWater(worldX, worldY) && !consumedWaterKeys.contains(positionKey(worldX, worldY))

    private fun hasAvailableResource(worldX: Int, worldY: Int): Boolean =
        isResource(worldX, worldY) && !gatheredResourceKeys.contains(positionKey(worldX, worldY))

    private fun hasAvailableGatherableResource(worldX: Int, worldY: Int): Boolean {
        val kind = resourceKind(worldX, worldY)
        val gatheringCurriculum = curriculum == CurriculumLevel.RESOURCE_GATHERING
        val farmingCurriculum = curriculum == CurriculumLevel.FARMING && kind == RESOURCE_WOOD
        return (gatheringCurriculum || farmingCurriculum) && kind >= 0 &&
            !gatheredResourceKeys.contains(positionKey(worldX, worldY))
    }

    private fun hasAvailableCrop(worldX: Int, worldY: Int): Boolean =
        isCrop(worldX, worldY) && !farmedCropKeys.contains(positionKey(worldX, worldY)) &&
            (cropStage == 0 || farmPlotKey == positionKey(worldX, worldY)) && cropStage < FARM_HARVEST_STAGE

    private fun canAdvanceFarmAt(worldX: Int, worldY: Int): Boolean {
        if (!hasAvailableCrop(worldX, worldY)) return false
        if (cropStage == 0) return wood > 0
        return farmPlotKey == positionKey(worldX, worldY)
    }

    private fun canInteractAtCurrentPosition(): Boolean =
        (canCompleteNavigationTarget() && isAtGoal()) ||
            hasAvailableGatherableResource(x, y) ||
            (definition.hasNeeds && hasAvailableFood(x, y) && isNeedy()) ||
            (definition.hasNeeds && hasAvailableWater(x, y) && hydration < 0.75f) ||
            (definition.hasFarming && canAdvanceFarmAt(x, y))

    private fun isAtGoal(): Boolean = x == goalX && y == goalY

    private fun hasNavigationTarget(): Boolean = when (curriculum) {
        CurriculumLevel.VISIBLE_TARGET,
        CurriculumLevel.HIDDEN_TARGET_SEARCH,
        CurriculumLevel.UNRESTRICTED_GENERALIZATION -> true
        else -> false
    }

    private fun canCompleteNavigationTarget(): Boolean =
        curriculum == CurriculumLevel.VISIBLE_TARGET ||
            curriculum == CurriculumLevel.UNRESTRICTED_GENERALIZATION

    private fun isAtHome(): Boolean = x == HOME_X && y == HOME_Y

    private fun isAtHomeCoordinate(worldX: Int, worldY: Int): Boolean =
        worldX == HOME_X && worldY == HOME_Y

    private fun resourceKind(worldX: Int, worldY: Int): Int = when {
        isResource(worldX, worldY) -> RESOURCE_WOOD
        curriculum == CurriculumLevel.RESOURCE_GATHERING && isFood(worldX, worldY) -> RESOURCE_BERRIES
        curriculum == CurriculumLevel.RESOURCE_GATHERING && isTree(worldX, worldY) -> RESOURCE_HERBS
        else -> -1
    }

    private fun biomeClass(worldX: Int, worldY: Int): Int =
        floorMod(mix(seed xor (worldX.toLong() * 31) xor (worldY.toLong() * 131)), PolicySpec.TERRAIN_CLASS_NAMES.size)

    private fun episodeLimit(): Int = if (
        curriculum == CurriculumLevel.VISIBLE_TARGET && !fixedTargetForVisibleCurriculum
    ) {
        VISIBLE_TARGET_EPISODE_LIMIT
    } else {
        definition.maxEpisodeSteps
    }

    private fun isWalkable(worldX: Int, worldY: Int): Boolean {
        if (worldX !in 0 until width || worldY !in 0 until height) return false
        if (worldX == 0 || worldY == 0 || worldX == width - 1 || worldY == height - 1) return false
        if (curriculum.id < CurriculumLevel.VISIBLE_TARGET.id) return true
        // Visible-target worlds contain sparse obstacles as required by the curriculum. The
        // target remains walkable, while the low density keeps alternate routes available without
        // turning the action mask into a target-direction hint.
        val obstacle = floorMod(mix(seed xor (worldX.toLong() shl 32) xor worldY.toLong()), 29) == 0
        val objectiveTile = hasAvailableGatherableResource(worldX, worldY) ||
            (definition.hasFarming && isCrop(worldX, worldY))
        return !obstacle || isAtHomeCoordinate(worldX, worldY) ||
            (hasNavigationTarget() && worldX == goalX && worldY == goalY) || objectiveTile
    }

    private fun visibleTargetCoordinate(seedPart: Long, dimension: Int, origin: Int): Int {
        val direction = if ((seedPart and 1L) == 0L) -1 else 1
        // Keep the target in the local semantic window for several exploratory moves while
        // retaining four-way/diagonal variation across independent seeds.
        val distance = 2 + floorMod(seedPart ushr 1, 3)
        return (origin + direction * distance).coerceIn(2, dimension - 3)
    }

    private fun findFirstCell(
        predicate: (Int, Int) -> Boolean,
        seedPart: Long,
        worldWidth: Int,
        worldHeight: Int
    ): Pair<Int, Int> {
        val interiorWidth = (worldWidth - 4).coerceAtLeast(1)
        val interiorHeight = (worldHeight - 4).coerceAtLeast(1)
        val cellCount = interiorWidth * interiorHeight
        val start = floorMod(seedPart, cellCount)
        for (offset in 0 until cellCount) {
            val index = (start + offset) % cellCount
            val candidateX = 2 + index % interiorWidth
            val candidateY = 2 + index / interiorWidth
            if (predicate(candidateX, candidateY)) return candidateX to candidateY
        }
        return 2 to 2
    }

    private fun isWater(worldX: Int, worldY: Int): Boolean =
        worldX in 0 until width && worldY in 0 until height && floorMod(mix(seed xor worldX.toLong() xor (worldY.toLong() shl 16)), 29) == 0

    private fun isFood(worldX: Int, worldY: Int): Boolean =
        floorMod(worldX.toLong() * 31L + worldY.toLong() * 17L + seed, 47L) == 0L

    private fun isResource(worldX: Int, worldY: Int): Boolean =
        floorMod(worldX.toLong() * 13L + worldY.toLong() * 37L + seed, 53L) == 0L

    private fun isTree(worldX: Int, worldY: Int): Boolean =
        floorMod(worldX.toLong() * 19L + worldY.toLong() * 7L + seed, 61L) == 0L

    private fun isCrop(worldX: Int, worldY: Int): Boolean = definition.hasFarming &&
        floorMod(worldX.toLong() * 11L + worldY.toLong() * 5L + seed, 67L) == 0L

    private fun isStructure(worldX: Int, worldY: Int): Boolean = worldX == 1 && worldY == 1

    private fun terrainClass(worldX: Int, worldY: Int): Float =
        PolicySpec.encodeTerrainClassOrdinal(biomeClass(worldX, worldY))

    private fun movementCost(worldX: Int, worldY: Int): Int {
        if (!isWalkable(worldX, worldY)) return PolicySpec.MAX_MOVEMENT_COST
        return if (isWater(worldX, worldY)) 5 else 1 + floorMod(
            mix(seed xor (worldX.toLong() * 17) xor (worldY.toLong() * 43)),
            2
        )
    }

    private fun writeKeys(data: DataOutputStream, values: Set<Long>) {
        data.writeInt(values.size)
        values.sorted().forEach(data::writeLong)
    }

    private fun writeInts(data: DataOutputStream, values: Set<Int>) {
        data.writeInt(values.size)
        values.sorted().forEach(data::writeInt)
    }

    private fun readKeys(input: DataInputStream): HashSet<Long> {
        val count = input.readInt()
        require(count in 0..(width * height)) { "Grid environment checkpoint key count is invalid" }
        val result = HashSet<Long>(count)
        repeat(count) { result += input.readLong() }
        require(result.size == count) { "Grid environment checkpoint contains duplicate keys" }
        return result
    }

    private fun readInts(input: DataInputStream): HashSet<Int> {
        val count = input.readInt()
        require(count in 0..PolicySpec.TERRAIN_CLASS_NAMES.size) {
            "Grid environment checkpoint biome count is invalid"
        }
        val result = HashSet<Int>(count)
        repeat(count) {
            val value = input.readInt()
            require(value in PolicySpec.TERRAIN_CLASS_NAMES.indices) {
                "Grid environment checkpoint biome class is invalid"
            }
            result += value
        }
        require(result.size == count) { "Grid environment checkpoint contains duplicate biome classes" }
        return result
    }

    private fun positionKey(worldX: Int, worldY: Int): Long = (worldX.toLong() shl 32) xor (worldY.toLong() and 0xffffffffL)

    private fun actionVector(action: MovementIntent): Pair<Int, Int> = action.dx to action.dy

    companion object {
        private const val CHECKPOINT_VERSION = 4
        private const val VISIBLE_TARGET_EPISODE_LIMIT = 32
        private const val HOME_X = 1
        private const val HOME_Y = 1
        private const val RESOURCE_WOOD = 0
        private const val RESOURCE_BERRIES = 1
        private const val RESOURCE_HERBS = 2
        private const val ENERGY_MOVEMENT_TARGET = 12
        private const val ENERGY_RUNNING_TARGET = 2
        private const val ENERGY_REST_TARGET = 4
        private const val ENERGY_SLEEP_TARGET = 2
        private const val BIOME_TARGET_COUNT = 4
        private const val SOCIAL_INTERACTION_TARGET = 2
        private const val FARM_HARVEST_STAGE = 3
        private const val NEED_HUNGER = 1
        private const val NEED_HYDRATION = 2
        private const val NEED_ENERGY = 4
        private const val NEED_ALL = 7
        private const val NO_POSITION_KEY = -9223372036854775807L - 1L
        private val MOVEMENT_ACTIONS = setOf(
            MovementIntent.MOVE_N,
            MovementIntent.MOVE_NE,
            MovementIntent.MOVE_E,
            MovementIntent.MOVE_SE,
            MovementIntent.MOVE_S,
            MovementIntent.MOVE_SW,
            MovementIntent.MOVE_W,
            MovementIntent.MOVE_NW,
            MovementIntent.WAIT
        )

        private fun mix(value: Long): Long {
            var mixed = value + -7046029254386353131L
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }

        private fun floorMod(value: Long, modulus: Int): Int {
            val positiveModulus = modulus.coerceAtLeast(1).toLong()
            return Math.floorMod(value, positiveModulus).toInt()
        }

        private fun floorMod(value: Long, modulus: Long): Long = Math.floorMod(value, modulus.coerceAtLeast(1L))
    }
}

class GridNavigationEnvironmentFactory(
    private val fixedTargetForVisibleCurriculum: Boolean = true
) : TrainingWorldEnvironmentFactory {
    override fun create(environmentId: Int, seed: Long, curriculum: CurriculumLevel): TrainingWorldEnvironment {
        return GridNavigationEnvironment(environmentId, fixedTargetForVisibleCurriculum).also { it.reset(seed, curriculum) }
    }
}
