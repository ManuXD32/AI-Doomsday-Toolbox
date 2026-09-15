package com.example.llamadroid.tama.world.policy

import kotlin.math.max

/** Stable semantic input contract shared by training and living inference. */
object PolicySpec {
    const val VERSION = 3
    const val OBSERVATION_RADIUS = 7
    const val OBSERVATION_SIDE = OBSERVATION_RADIUS * 2 + 1
    const val TILE_CHANNELS = 13
    const val SCALAR_FEATURES = 13
    const val ACTION_COUNT = 11
    const val RECURRENT_UNITS = 64
    const val TILE_FEATURES = OBSERVATION_SIDE * OBSERVATION_SIDE * TILE_CHANNELS
    const val SPATIAL_SUMMARY_FEATURES = TILE_CHANNELS * 4
    /** Persisted policy vector shape: raw semantic tiles/scalars plus the spatial summary. */
    const val INPUT_SIZE = TILE_FEATURES + SCALAR_FEATURES + SPATIAL_SUMMARY_FEATURES
    /** Compact encoded prefix used by the small recurrent network. */
    const val LOCAL_PATCH_RADIUS = 2
    const val LOCAL_PATCH_SIDE = LOCAL_PATCH_RADIUS * 2 + 1
    const val LOCAL_PATCH_FEATURES = LOCAL_PATCH_SIDE * LOCAL_PATCH_SIDE * TILE_CHANNELS
    const val SPATIAL_BIN_SIDE = 3
    const val SPATIAL_BIN_FEATURES = SPATIAL_BIN_SIDE * SPATIAL_BIN_SIDE * TILE_CHANNELS
    /** Active prefix produced by the deterministic spatial encoder before zero padding. */
    const val ENCODED_INPUT_SIZE =
        SCALAR_FEATURES + LOCAL_PATCH_FEATURES + SPATIAL_BIN_FEATURES + SPATIAL_SUMMARY_FEATURES
    const val MAX_MOVEMENT_COST = 10

    /** The order is persisted in policy checkpoints; append only. */
    val TILE_CHANNEL_NAMES: List<String> = listOf(
        "walkability",
        "terrain_class",
        "movement_cost",
        "water",
        "food",
        "resource",
        "tree",
        "crop",
        "structure",
        "npc",
        "hazard",
        "known",
        "goal"
    )

    /** The order is persisted in policy checkpoints; append only. */
    val SCALAR_FEATURE_NAMES: List<String> = listOf(
        "hunger",
        "hydration",
        "energy",
        "health",
        "hygiene",
        "happiness",
        "social",
        "curiosity",
        "inventory_summary",
        "current_goal",
        "distance_estimate",
        "time_of_day",
        "recent_failure_count"
    )

    /**
     * Canonical terrain values are biome ordinals.  The live world and headless worlds must
     * encode the biome here, rather than the independent TileKind ordinal.
     */
    val TERRAIN_CLASS_NAMES: List<String> = listOf(
        "MEADOW",
        "FOREST",
        "WETLANDS",
        "DESERT",
        "HIGHLANDS",
        "TUNDRA",
        "COAST",
        "MYSTIC_GROVE"
    )

    /** Must stay in lockstep with the core GoalId declaration. */
    val CURRENT_GOAL_NAMES: List<String> = listOf(
        "IDLE",
        "FIND_FOOD",
        "FIND_WATER",
        "REST",
        "SLEEP",
        "SOCIALIZE",
        "PLAY",
        "EXPLORE",
        "RETURN_HOME",
        "VISIT_FRIEND",
        "GATHER_WOOD",
        "GATHER_HERBS",
        "FARM",
        "STUDY",
        "WORK",
        "TRAIN",
        "VISIT_INTERESTING_PLACE",
        "INVESTIGATE",
        "WANDER",
        "ENTER_DUNGEON",
        "FOLLOW_NPC",
        "HELP_NPC",
        "RECOVER_STUCK"
    )

    /** Encode a core Biome ordinal into the stable [0, 1] terrain channel. */
    fun encodeTerrainClassOrdinal(ordinal: Int): Float =
        normalizedOrdinal(ordinal, TERRAIN_CLASS_NAMES.size)

    /** Encode a core GoalId ordinal into the stable [0, 1] current-goal scalar. */
    fun encodeCurrentGoalOrdinal(ordinal: Int): Float =
        normalizedOrdinal(ordinal, CURRENT_GOAL_NAMES.size)

    /** Encode the core movement cost convention (0..10, with blocked represented by 10). */
    fun encodeMovementCost(cost: Int): Float =
        cost.coerceIn(0, MAX_MOVEMENT_COST).toFloat() / MAX_MOVEMENT_COST.toFloat()

    /** Map a training curriculum to the same current-goal wire code used by the live world. */
    fun trainingGoalOrdinal(curriculumId: Int): Int = when (curriculumId.coerceIn(0, 10)) {
        0 -> 18 // WANDER / locomotion validation
        1 -> 16 // VISIT_INTERESTING_PLACE / visible target
        2 -> 7 // EXPLORE / hidden target search
        3 -> 1 // FIND_FOOD
        4 -> 3 // REST
        5 -> 7 // EXPLORE / biome navigation
        6 -> 10 // GATHER_WOOD
        7 -> 5 // SOCIALIZE
        8 -> 12 // FARM
        9 -> 1 // FIND_FOOD / multi-need survival
        else -> 7 // EXPLORE / unrestricted generalization
    }

    private fun normalizedOrdinal(ordinal: Int, count: Int): Float {
        val denominator = (count - 1).coerceAtLeast(1)
        return ordinal.coerceIn(0, denominator).toFloat() / denominator.toFloat()
    }

    init {
        require(TILE_CHANNEL_NAMES.size == TILE_CHANNELS)
        require(SCALAR_FEATURE_NAMES.size == SCALAR_FEATURES)
        require(TERRAIN_CLASS_NAMES.size == 8)
        require(CURRENT_GOAL_NAMES.size == 23)
    }
}

enum class MovementIntent(
    val wireId: Int,
    val dx: Int,
    val dy: Int
) {
    MOVE_N(0, 0, -1),
    MOVE_NE(1, 1, -1),
    MOVE_E(2, 1, 0),
    MOVE_SE(3, 1, 1),
    MOVE_S(4, 0, 1),
    MOVE_SW(5, -1, 1),
    MOVE_W(6, -1, 0),
    MOVE_NW(7, -1, -1),
    WAIT(8, 0, 0),
    INTERACT(9, 0, 0),
    SEARCH(10, 0, 0);

    companion object {
        fun fromWireId(id: Int): MovementIntent =
            entries.firstOrNull { it.wireId == id }
                ?: throw IllegalArgumentException("Unknown movement intent id: $id")
    }
}

/** Semantic observation. Arrays are copied at the boundary to prevent mutation during inference. */
data class Observation(
    val tileFeatures: FloatArray,
    val scalarFeatures: FloatArray,
    val actionMask: BooleanArray = BooleanArray(PolicySpec.ACTION_COUNT) { true }
) {
    init {
        require(tileFeatures.size == PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS) {
            "Expected ${PolicySpec.OBSERVATION_SIDE}x${PolicySpec.OBSERVATION_SIDE}x${PolicySpec.TILE_CHANNELS} tile features"
        }
        require(scalarFeatures.size == PolicySpec.SCALAR_FEATURES) {
            "Expected ${PolicySpec.SCALAR_FEATURES} scalar features"
        }
        require(actionMask.size == PolicySpec.ACTION_COUNT) {
            "Expected ${PolicySpec.ACTION_COUNT} action-mask entries"
        }
    }

    fun flattened(): FloatArray {
        val flattened = FloatArray(PolicySpec.INPUT_SIZE)
        tileFeatures.copyInto(flattened, 0)
        scalarFeatures.copyInto(flattened, PolicySpec.TILE_FEATURES)
        SemanticFeatureEncoder.appendSummary(tileFeatures, flattened, PolicySpec.TILE_FEATURES + PolicySpec.SCALAR_FEATURES)
        return flattened
    }

    /**
     * Encode the full semantic window for the compact recurrent policy input. The returned array
     * keeps [PolicySpec.INPUT_SIZE] stable for artifact compatibility: the deterministic encoder
     * occupies its prefix and the unused tail is zero. Keeping the complete observation contract
     * here lets future policies consume additional spatial detail without changing the world/core
     * observation type.
     */
    fun encodedForPolicy(): FloatArray {
        val encoded = FloatArray(PolicySpec.INPUT_SIZE)
        SemanticFeatureEncoder.appendEncoded(tileFeatures, scalarFeatures, encoded)
        return encoded
    }

    fun copyOfArrays(): Observation = Observation(
        tileFeatures = tileFeatures.copyOf(),
        scalarFeatures = scalarFeatures.copyOf(),
        actionMask = actionMask.copyOf()
    )

    companion object {
        fun blank(): Observation = Observation(
            tileFeatures = FloatArray(PolicySpec.OBSERVATION_SIDE * PolicySpec.OBSERVATION_SIDE * PolicySpec.TILE_CHANNELS),
            scalarFeatures = FloatArray(PolicySpec.SCALAR_FEATURES),
            actionMask = BooleanArray(PolicySpec.ACTION_COUNT) { true }
        )

        fun fromFlattened(values: FloatArray, actionMask: BooleanArray = BooleanArray(PolicySpec.ACTION_COUNT) { true }): Observation {
            require(values.size == PolicySpec.INPUT_SIZE) { "Expected ${PolicySpec.INPUT_SIZE} flattened features" }
            val scalarStart = PolicySpec.TILE_FEATURES
            return Observation(
                values.copyOfRange(0, scalarStart),
                values.copyOfRange(scalarStart, scalarStart + PolicySpec.SCALAR_FEATURES),
                actionMask.copyOf()
            )
        }
    }
}

/**
 * Fixed spatial features keep the semantic window useful to a small fully-connected policy.
 * They are observation encoding only: no action or target-direction decision is made here.
 *
 * For each tile channel the encoder emits occupancy, x centroid, y centroid, and mean radius.
 * Centroids are conditioned on channel presence and use actor-relative coordinates. A hidden goal
 * has an all-zero goal channel, so the summary cannot reveal its location or distance.
 */
internal object SemanticFeatureEncoder {
    private const val FEATURES_PER_CHANNEL = 4

    fun appendEncoded(tileFeatures: FloatArray, scalarFeatures: FloatArray, destination: FloatArray) {
        require(tileFeatures.size == PolicySpec.TILE_FEATURES)
        require(scalarFeatures.size == PolicySpec.SCALAR_FEATURES)
        require(destination.size == PolicySpec.INPUT_SIZE)
        scalarFeatures.copyInto(destination, 0)
        var offset = PolicySpec.SCALAR_FEATURES
        appendLocalPatch(tileFeatures, destination, offset)
        offset += PolicySpec.LOCAL_PATCH_FEATURES
        appendSpatialBins(tileFeatures, destination, offset)
        offset += PolicySpec.SPATIAL_BIN_FEATURES
        appendSummary(tileFeatures, destination, offset)
    }

    private fun appendLocalPatch(tileFeatures: FloatArray, destination: FloatArray, destinationOffset: Int) {
        val side = PolicySpec.OBSERVATION_SIDE
        val channels = PolicySpec.TILE_CHANNELS
        val radius = PolicySpec.LOCAL_PATCH_RADIUS
        var output = destinationOffset
        for (localY in -radius..radius) {
            val sourceY = localY + PolicySpec.OBSERVATION_RADIUS
            for (localX in -radius..radius) {
                val sourceX = localX + PolicySpec.OBSERVATION_RADIUS
                val source = (sourceY * side + sourceX) * channels
                tileFeatures.copyInto(destination, output, source, source + channels)
                output += channels
            }
        }
    }

    private fun appendSpatialBins(tileFeatures: FloatArray, destination: FloatArray, destinationOffset: Int) {
        val side = PolicySpec.OBSERVATION_SIDE
        val channels = PolicySpec.TILE_CHANNELS
        var output = destinationOffset
        for (binY in 0 until PolicySpec.SPATIAL_BIN_SIDE) {
            val startY = binY * side / PolicySpec.SPATIAL_BIN_SIDE
            val endY = (binY + 1) * side / PolicySpec.SPATIAL_BIN_SIDE
            for (binX in 0 until PolicySpec.SPATIAL_BIN_SIDE) {
                val startX = binX * side / PolicySpec.SPATIAL_BIN_SIDE
                val endX = (binX + 1) * side / PolicySpec.SPATIAL_BIN_SIDE
                val cellCount = ((endY - startY) * (endX - startX)).coerceAtLeast(1)
                for (channel in 0 until channels) {
                    var sum = 0f
                    for (localY in startY until endY) {
                        for (localX in startX until endX) {
                            sum += tileFeatures[(localY * side + localX) * channels + channel]
                        }
                    }
                    destination[output++] = sum / cellCount.toFloat()
                }
            }
        }
    }

    fun appendSummary(tileFeatures: FloatArray, destination: FloatArray, destinationOffset: Int) {
        require(tileFeatures.size == PolicySpec.TILE_FEATURES)
        require(destinationOffset >= 0)
        require(destination.size - destinationOffset >= PolicySpec.SPATIAL_SUMMARY_FEATURES)
        val side = PolicySpec.OBSERVATION_SIDE
        val radius = PolicySpec.OBSERVATION_RADIUS.toFloat().coerceAtLeast(1f)
        val maxRadius = kotlin.math.sqrt(2f * radius * radius)
        for (channel in 0 until PolicySpec.TILE_CHANNELS) {
            var mass = 0.0
            var xMoment = 0.0
            var yMoment = 0.0
            var radiusMoment = 0.0
            for (localY in 0 until side) {
                val relativeY = localY - PolicySpec.OBSERVATION_RADIUS
                for (localX in 0 until side) {
                    val relativeX = localX - PolicySpec.OBSERVATION_RADIUS
                    val value = tileFeatures[(localY * side + localX) * PolicySpec.TILE_CHANNELS + channel]
                    val weight = kotlin.math.abs(value.toDouble())
                    if (weight == 0.0) continue
                    mass += weight
                    xMoment += weight * relativeX / radius
                    yMoment += weight * relativeY / radius
                    radiusMoment += weight * kotlin.math.sqrt(
                        (relativeX * relativeX + relativeY * relativeY).toDouble()
                    ) / maxRadius
                }
            }
            val output = destinationOffset + channel * FEATURES_PER_CHANNEL
            if (mass == 0.0) continue
            destination[output] = (mass / (side * side).toDouble()).toFloat().coerceIn(0f, 1f)
            destination[output + 1] = (xMoment / mass).toFloat().coerceIn(-1f, 1f)
            destination[output + 2] = (yMoment / mass).toFloat().coerceIn(-1f, 1f)
            destination[output + 3] = (radiusMoment / mass).toFloat().coerceIn(0f, 1f)
        }
    }
}

data class RecurrentState(val values: FloatArray = FloatArray(PolicySpec.RECURRENT_UNITS)) {
    init {
        require(values.size == PolicySpec.RECURRENT_UNITS)
    }

    fun copyOf(): RecurrentState = RecurrentState(values.copyOf())

    companion object {
        fun zero(): RecurrentState = RecurrentState()
    }
}

data class PolicyAction(
    val intent: MovementIntent,
    val actionIndex: Int,
    val logProbability: Float,
    val valueEstimate: Float,
    val nextState: RecurrentState,
    val logits: FloatArray
)

/** Minimal living-world inference boundary. The app can retain only an adopted artifact. */
interface LivingPolicy {
    val policyVersion: String

    fun infer(
        observation: Observation,
        state: RecurrentState = RecurrentState.zero(),
        deterministic: Boolean = true,
        rng: DeterministicRng = DeterministicRng(0x10a4d2f63c91e8b7L)
    ): PolicyAction
}

data class PolicyForward(
    val logits: FloatArray,
    val value: Float,
    val nextState: RecurrentState
)

internal fun safeFinite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

internal fun clampUnit(value: Float): Float = value.coerceIn(-1f, 1f)

internal fun normalizedFeature(value: Float): Float = clampUnit(value)

internal fun validActionIndices(mask: BooleanArray): IntArray {
    val indices = IntArray(mask.count { it })
    var cursor = 0
    mask.forEachIndexed { index, enabled -> if (enabled) indices[cursor++] = index }
    return if (indices.isNotEmpty()) indices else intArrayOf(MovementIntent.WAIT.wireId)
}

internal fun normalizedPositive(value: Float): Float = max(0f, safeFinite(value))
