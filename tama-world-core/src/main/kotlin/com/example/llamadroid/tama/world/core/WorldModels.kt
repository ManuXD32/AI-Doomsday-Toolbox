package com.example.llamadroid.tama.world.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

/**
 * Version of the deterministic generator. A living world keeps this value so
 * an application upgrade never silently moves terrain or structures.
 */
const val CURRENT_GENERATOR_VERSION: Int = 1
const val STANDARD_WORLD_WIDTH: Int = 256
const val STANDARD_WORLD_HEIGHT: Int = 256
const val TRAINING_WORLD_WIDTH: Int = 64
const val TRAINING_WORLD_HEIGHT: Int = 64
const val CHUNK_SIZE_TILES: Int = 16
const val DEFAULT_TICK_MILLIS: Long = 100L

data class WorldCalendarTime(
    val dayIndex: Long,
    val dayOfMonth: Int,
    /** ISO-8601 day of week: Monday=1 through Sunday=7. */
    val dayOfWeek: Int,
    val minuteOfDay: Int
)

object WorldClock {
    fun at(epochMillis: Long, timezoneOffsetMinutes: Int = 0): WorldCalendarTime {
        val offset = ZoneOffset.ofTotalSeconds(timezoneOffsetMinutes.coerceIn(-1_080, 1_080) * 60)
        val localDateTime = Instant.ofEpochMilli(epochMillis).atOffset(offset)
        return WorldCalendarTime(
            dayIndex = localDateTime.toLocalDate().toEpochDay(),
            dayOfMonth = localDateTime.dayOfMonth,
            dayOfWeek = localDateTime.dayOfWeek.value,
            minuteOfDay = localDateTime.hour * 60 + localDateTime.minute
        )
    }
}

@Serializable
data class WorldCoordinate(
    val x: Int,
    val y: Int
) {
    operator fun plus(other: WorldCoordinate): WorldCoordinate = WorldCoordinate(x + other.x, y + other.y)

    fun manhattanDistanceTo(other: WorldCoordinate): Int =
        kotlin.math.abs(x - other.x) + kotlin.math.abs(y - other.y)

    fun chebyshevDistanceTo(other: WorldCoordinate): Int =
        maxOf(kotlin.math.abs(x - other.x), kotlin.math.abs(y - other.y))
}

@Serializable
enum class Direction {
    NONE,
    NORTH,
    NORTH_EAST,
    EAST,
    SOUTH_EAST,
    SOUTH,
    SOUTH_WEST,
    WEST,
    NORTH_WEST
}

@Serializable
enum class PresenceMode {
    HOME,
    WORLD,
    INTERIOR
}

@Serializable
enum class Biome {
    MEADOW,
    FOREST,
    WETLANDS,
    DESERT,
    HIGHLANDS,
    TUNDRA,
    COAST,
    MYSTIC_GROVE
}

@Serializable
enum class TileKind {
    GRASS,
    FLOWER_MEADOW,
    FOREST_FLOOR,
    WETLAND_MUD,
    SHALLOW_WATER,
    DEEP_WATER,
    SAND,
    ROCK,
    SNOW,
    ICE,
    COAST_SAND,
    MYSTIC_GRASS,
    ROAD,
    STRUCTURE_FLOOR,
    FARM_SOIL
}

@Serializable
enum class StructureType {
    HOME,
    SHOP,
    PARK,
    HOSPITAL,
    ARCADE,
    ALCHEMIST,
    SCHOOL,
    WORKPLACE,
    FARM,
    BOXING_RING,
    DUNGEON_A,
    DUNGEON_B,
    ADVENTURE_GATE,
    FARM_BARN,
    NPC_HOME_A,
    NPC_HOME_B,
    NPC_HOME_C,
    MARKET_STALL
}

@Serializable
enum class WorldObjectType {
    TREE,
    FALLEN_LOG,
    BUSH,
    FLOWER_MEADOW,
    BERRY_PATCH,
    MUSHROOM,
    HERB_PATCH,
    STONE,
    REED,
    CACTUS,
    SHELL,
    GLOWING_PLANT,
    POND,
    DROPPED_ITEM,
    BENCH,
    MARKET_STALL,
    CUSTOM
}

@Serializable
enum class WorldDeltaKind {
    TILE,
    OBJECT,
    RESOURCE,
    EXPLORATION,
    STRUCTURE
}

@Serializable
data class StructureLayout(
    val widthTiles: Int = 4,
    val heightTiles: Int = 4,
    /** Row-major collision values: 1 blocks movement, 0 permits movement. */
    val collision: List<List<Int>> = emptyList(),
    /** Relative entrance; it may sit just outside the footprint. */
    val entrance: WorldCoordinate = WorldCoordinate(widthTiles / 2, heightTiles)
) {
    init {
        require(widthTiles > 0 && heightTiles > 0) { "Structure dimensions must be positive" }
        require(collision.isEmpty() || collision.size == heightTiles) { "Collision rows must match heightTiles" }
        require(collision.isEmpty() || collision.all { it.size == widthTiles }) { "Collision columns must match widthTiles" }
        require(collision.flatten().all { it == 0 || it == 1 }) { "Collision values must be 0 or 1" }
        require(entrance.x in 0 until widthTiles && entrance.y in 0..heightTiles) {
            "Entrance must be inside or directly below the structure footprint"
        }
    }

    fun isBlocked(localX: Int, localY: Int): Boolean =
        (collision.getOrNull(localY)?.getOrNull(localX) ?: 1) == 1
}

@Serializable
data class WorldStructure(
    val id: String,
    val type: StructureType,
    val x: Int,
    val y: Int,
    val width: Int = 3,
    val height: Int = 3,
    val rotation: Int = 0,
    val entrance: WorldCoordinate = WorldCoordinate(0, 0),
    /** Persisted row-major layout so collision remains stable after reload. */
    val collisionMask: List<List<Int>> = emptyList(),
    val ownerNpcId: String? = null,
    val state: String = "active",
    val mandatory: Boolean = true,
    /** Optional capability contract for generic OPEN/CLOSE/USE/ACTIVATE. */
    val capabilities: Set<ActionId> = emptySet(),
    /** A private structure can restrict interaction to this actor identity. */
    val ownerActorId: String? = null
) {
    val bounds: IntRange
        get() = x until (x + width)

    val yBounds: IntRange
        get() = y until (y + height)

    fun contains(coordinate: WorldCoordinate): Boolean =
        coordinate.x in bounds && coordinate.y in yBounds

    fun blocks(coordinate: WorldCoordinate): Boolean =
        contains(coordinate) && (collisionMask.getOrNull(coordinate.y - y)?.getOrNull(coordinate.x - x) ?: 1) == 1
}

@Serializable
data class WorldObject(
    val id: String,
    val type: WorldObjectType,
    val x: Int,
    val y: Int,
    val state: String = "available",
    val quantity: Int = 1,
    val respawnAtTick: Long? = null,
    /** Item identity for dropped objects; generated resources leave this null. */
    val itemId: String? = null,
    /** Preserve a custom patch's yield independently of its depleted quantity. */
    val regrowthQuantity: Int = quantity.coerceAtLeast(1),
    /** Optional capability contract for generic OPEN/CLOSE/USE/ACTIVATE. */
    val capabilities: Set<ActionId> = emptySet(),
    /** A private object can restrict interaction to this actor identity. */
    val ownerActorId: String? = null
)

@Serializable
data class WorldDelta(
    val x: Int,
    val y: Int,
    val kind: WorldDeltaKind,
    val value: String,
    val updatedAtTick: Long = 0L,
    val objectId: String? = null
)

@Serializable
enum class InventoryKind {
    FOOD,
    WATER,
    MEDICINE,
    TOOL,
    SEED,
    FERTILIZER,
    MATERIAL,
    OTHER
}

/** Stable fallback classification for legacy item IDs that have no category. */
object InventoryKinds {
    fun infer(itemId: String): InventoryKind {
        val normalized = itemId.trim().lowercase()
        return when {
            normalized == "water" || normalized.contains("bottled_water") || normalized == "water_bottle" -> InventoryKind.WATER
            normalized == "seed" || normalized.startsWith("seed_") -> InventoryKind.SEED
            normalized == "fertilizer" || normalized.startsWith("fertilizer_") -> InventoryKind.FERTILIZER
            normalized.contains("medicine") || normalized.contains("medkit") || normalized == "potion" || normalized.endsWith("_potion") -> InventoryKind.MEDICINE
            normalized == "hoe" || normalized.startsWith("hoe_") ||
                normalized == "watering_can" || normalized.startsWith("watering_can_") ||
                normalized == "axe" || normalized.startsWith("axe_") ||
                normalized == "pickaxe" || normalized.startsWith("pickaxe_") -> InventoryKind.TOOL
            normalized == "berry" || normalized == "berries" || normalized == "mushroom" ||
                normalized.startsWith("food_") -> InventoryKind.FOOD
            else -> InventoryKind.OTHER
        }
    }
}

@Serializable
data class InventoryStack(
    val itemId: String,
    val quantity: Int,
    /** Optional canonical category; OTHER keeps older projections compatible. */
    val kind: InventoryKind = InventoryKind.OTHER
)

@Serializable
enum class NeedType {
    HUNGER,
    HYDRATION,
    ENERGY,
    HEALTH,
    HYGIENE,
    HAPPINESS,
    SOCIAL,
    CURIOSITY
}

/**
 * A projection of canonical Tama needs. The world core may read and propose
 * changes to this value, but never persists it as a second pet record.
 */
@Serializable
data class NeedsProjection(
    val hunger: Float = 100f,
    val hydration: Float = 100f,
    val energy: Float = 100f,
    val health: Float = 100f,
    val hygiene: Float = 100f,
    val happiness: Float = 100f,
    val social: Float = 100f,
    val curiosity: Float = 100f
) {
    fun valueOf(need: NeedType): Float = when (need) {
        NeedType.HUNGER -> hunger
        NeedType.HYDRATION -> hydration
        NeedType.ENERGY -> energy
        NeedType.HEALTH -> health
        NeedType.HYGIENE -> hygiene
        NeedType.HAPPINESS -> happiness
        NeedType.SOCIAL -> social
        NeedType.CURIOSITY -> curiosity
    }

    fun withValue(need: NeedType, value: Float): NeedsProjection {
        val clamped = value.coerceIn(0f, 100f)
        return when (need) {
            NeedType.HUNGER -> copy(hunger = clamped)
            NeedType.HYDRATION -> copy(hydration = clamped)
            NeedType.ENERGY -> copy(energy = clamped)
            NeedType.HEALTH -> copy(health = clamped)
            NeedType.HYGIENE -> copy(hygiene = clamped)
            NeedType.HAPPINESS -> copy(happiness = clamped)
            NeedType.SOCIAL -> copy(social = clamped)
            NeedType.CURIOSITY -> copy(curiosity = clamped)
        }
    }

    fun plus(need: NeedType, delta: Float): NeedsProjection = withValue(need, valueOf(need) + delta)

    fun asMap(): Map<NeedType, Float> = NeedType.entries.associateWith(::valueOf)
}

@Serializable
enum class Personality {
    CHEERFUL,
    SHY,
    PLAYFUL,
    LAZY,
    CURIOUS,
    BRAVE
}

@Serializable
enum class PetActivity {
    NONE,
    WORK,
    STUDY,
    TRAINING,
    RELAXING,
    SLEEPING
}

@Serializable
enum class AutonomyLevel {
    OFF,
    SAFE,
    NORMAL,
    FULL
}

@Serializable
data class AutonomyPolicy(
    val level: AutonomyLevel = AutonomyLevel.SAFE,
    val allowPurchases: Boolean = false,
    val maximumAutonomousPurchase: Int = 0,
    val allowSellingItems: Boolean = false,
    val allowUsingRareItems: Boolean = false,
    val allowDungeonEntry: Boolean = false,
    val allowAdventureGate: Boolean = false,
    val allowOvernightExploration: Boolean = false,
    val allowWork: Boolean = false,
    val allowStudy: Boolean = false,
    val allowFarming: Boolean = false,
    val allowHarvestingUserCrops: Boolean = false
) {
    /**
     * Checks whether an autonomous action is enabled. User-owned crops have a
     * separate opt-in because harvesting them changes canonical farm state.
     */
    fun permits(action: ActionId, purchaseCost: Int = 0, userOwnedCrop: Boolean = false): Boolean {
        if (level == AutonomyLevel.OFF) return false
        if (action == ActionId.HARVEST_CROP && userOwnedCrop && !allowHarvestingUserCrops) return false
        if (level == AutonomyLevel.FULL) return true
        return when (action) {
            ActionId.BUY -> allowPurchases && purchaseCost <= maximumAutonomousPurchase
            ActionId.SELL -> allowSellingItems
            // Hospital treatment is selected and priced by the canonical app
            // adapter. The core cannot know that final catalog price yet, but
            // an autonomous visit is still a paid operation and must require
            // the purchase permission before the pet starts travelling.
            ActionId.VISIT_HOSPITAL -> allowPurchases &&
                (purchaseCost <= 0 || purchaseCost <= maximumAutonomousPurchase)
            ActionId.USE_MEDICINE -> allowUsingRareItems
            ActionId.ENTER_DUNGEON -> allowDungeonEntry
            ActionId.ENTER_ADVENTURE_GATE -> allowAdventureGate
            ActionId.WORK -> allowWork
            ActionId.STUDY -> allowStudy
            ActionId.HARVEST_CROP -> allowFarming
            ActionId.TILL_SOIL, ActionId.PLANT, ActionId.WATER, ActionId.POUR_WATER, ActionId.FERTILIZE,
            ActionId.REMOVE_DEAD_CROP, ActionId.STORE_PRODUCE -> allowFarming
            else -> true
        }
    }
}

@Serializable
data class RelationshipProjection(
    val familiarity: Int = 0,
    val friendship: Int = 0,
    val trust: Int = 0
)

@Serializable
data class FarmPlotSnapshot(
    val id: String,
    val x: Int,
    val y: Int,
    val status: String = "soil",
    val cropId: String? = null,
    val isReady: Boolean = false,
    val isDead: Boolean = false,
    /** Canonical pet farm projections represent user-owned plots by default. */
    val userOwned: Boolean = true,
    /** Used only by NPC-owned field simulation; player crop timers stay in Room. */
    val plantedAtTick: Long = 0,
    val wateredAtTick: Long = 0
)

@Serializable
data class FarmSnapshot(
    val plots: List<FarmPlotSnapshot> = emptyList()
)

/**
 * Read-only data supplied by the app adapter. Durable inventory, money,
 * needs, activity, and relationships remain owned by Tama persistence.
 */
@Serializable
data class CanonicalPetSnapshot(
    val petId: String = "pet",
    val needs: NeedsProjection = NeedsProjection(),
    val personality: Personality = Personality.CHEERFUL,
    val money: Int = 0,
    val inventory: List<InventoryStack> = emptyList(),
    val sleeping: Boolean = false,
    val cycleFrozen: Boolean = false,
    val isEgg: Boolean = false,
    val activity: PetActivity = PetActivity.NONE,
    val autonomy: AutonomyPolicy = AutonomyPolicy(),
    val relationships: Map<String, RelationshipProjection> = emptyMap(),
    val farm: FarmSnapshot? = null
)

@Serializable
enum class GoalId {
    IDLE,
    FIND_FOOD,
    FIND_WATER,
    REST,
    SLEEP,
    SOCIALIZE,
    PLAY,
    EXPLORE,
    RETURN_HOME,
    VISIT_FRIEND,
    GATHER_WOOD,
    GATHER_HERBS,
    FARM,
    STUDY,
    WORK,
    TRAIN,
    VISIT_INTERESTING_PLACE,
    INVESTIGATE,
    WANDER,
    ENTER_DUNGEON,
    FOLLOW_NPC,
    HELP_NPC,
    RECOVER_STUCK
}

@Serializable
enum class ActionState {
    IDLE,
    RUNNING,
    COMPLETED,
    BLOCKED,
    INTERRUPTED
}

@Serializable
data class PendingActivityIntent(
    val action: String,
    val destinationId: String,
    val arguments: Map<String, String> = emptyMap(),
    /**
     * An external activity owns the pet action clock until its durable
     * adapter receipt reaches a terminal state. NPCs and world time may still
     * advance while the pet itself is held at its destination.
     */
    val blocksPetSimulation: Boolean = false
)

/**
 * A user action waiting for the actor to reach an adjacent tile. Keeping the
 * command in the actor snapshot makes approach-and-act deterministic across
 * process death and preserves selected crop/options until completion.
 */
@Serializable
data class PendingWorldCommand(
    val action: ActionId,
    val targetId: String? = null,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val arguments: Map<String, String> = emptyMap()
)

@Serializable
data class WorldActor(
    val actorId: String = "pet",
    val actorType: ActorType = ActorType.PET,
    val x: Int = 0,
    val y: Int = 0,
    val preciseX: Double = 0.0,
    val preciseY: Double = 0.0,
    val facing: Direction = Direction.SOUTH,
    val presence: PresenceMode = PresenceMode.HOME,
    val structureId: String? = "fixed_0_0",
    val goal: GoalId = GoalId.IDLE,
    val action: ActionId = ActionId.WAIT,
    val actionState: ActionState = ActionState.IDLE,
    val actionTicksRemaining: Int = 0,
    val destinationX: Int? = null,
    val destinationY: Int? = null,
    val pendingStructureId: String? = null,
    val actionTargetId: String? = null,
    val actionTargetX: Int? = null,
    val actionTargetY: Int? = null,
    val path: List<WorldCoordinate> = emptyList(),
    val navigationMemory: List<Float> = emptyList(),
    val actionArguments: Map<String, String> = emptyMap(),
    val pendingCommand: PendingWorldCommand? = null,
    val pendingActivity: PendingActivityIntent? = null,
    /** Durable physical follow intent; cleared by Stop or a new route. */
    val followTargetId: String? = null,
    val needs: NeedsProjection = NeedsProjection(),
    val policyVersion: String = "baseline-v1",
    val stuckTicks: Int = 0
) {
    val coordinate: WorldCoordinate
        get() = WorldCoordinate(x, y)

    val destination: WorldCoordinate?
        get() = if (destinationX != null && destinationY != null) {
            WorldCoordinate(destinationX, destinationY)
        } else {
            null
        }

    fun at(coordinate: WorldCoordinate): WorldActor = copy(
        x = coordinate.x,
        y = coordinate.y,
        preciseX = coordinate.x.toDouble(),
        preciseY = coordinate.y.toDouble()
    )
}

@Serializable
enum class NpcRole {
    FARMER,
    BOXING_COACH,
    SHOP_SELLER,
    TEACHER,
    DOCTOR,
    ADVENTURER,
    ARCADE_HOST,
    ALCHEMIST,
    PARK_RESIDENT,
    RECYCLER,
    MARKET_SELLER,
    WORKPLACE_RESIDENT
}

@Serializable
data class WorldNpc(
    val id: String,
    val name: String,
    val role: NpcRole,
    val x: Int,
    val y: Int,
    val preciseX: Double = x.toDouble(),
    val preciseY: Double = y.toDouble(),
    val facing: Direction = Direction.SOUTH,
    val homeStructureId: String? = null,
    val jobStructureId: String? = null,
    val currentGoal: GoalId = GoalId.IDLE,
    val currentAction: ActionId = ActionId.WAIT,
    val actionState: ActionState = ActionState.IDLE,
    val scheduleState: String = "idle",
    val inventory: List<InventoryStack> = emptyList(),
    val needs: NeedsProjection = NeedsProjection(),
    val decisionCounter: Long = 0L,
    val lastSimulatedAt: Long = 0L,
    val destinationX: Int? = null,
    val destinationY: Int? = null,
    val path: List<WorldCoordinate> = emptyList(),
    val homeAnchor: WorldCoordinate? = null,
    val jobAnchor: WorldCoordinate? = null,
    val socialAnchor: WorldCoordinate? = null,
    val stationary: Boolean = false,
    /** Resumable action state for the same executor used by the pet. */
    val execution: WorldActor? = null,
    val relationships: Map<String, RelationshipProjection> = emptyMap(),
    val money: Int = 0,
    /** NPC-owned plots are separate from every canonical player farm plot. */
    val ownFarm: List<FarmPlotSnapshot> = emptyList(),
    val completedJobs: Long = 0,
    val lastDecisionAt: Long = 0
) {
    val coordinate: WorldCoordinate
        get() = WorldCoordinate(x, y)
}

@Serializable
data class KnownPlace(
    val id: String,
    val kind: String,
    val x: Int,
    val y: Int,
    val lastSeenAt: Long = 0L,
    val confidence: Float = 1f
)

@Serializable
data class KnownResourcePatch(
    val kind: WorldObjectType,
    val approximateX: Int,
    val approximateY: Int,
    val lastSeenAt: Long = 0L,
    val confidence: Float = 1f
)

@Serializable
data class KnownNpcLocation(
    val npcId: String,
    val approximateX: Int,
    val approximateY: Int,
    val lastSeenAt: Long = 0L,
    val confidence: Float = 1f
)

@Serializable
data class FarmPlotReference(
    val id: String,
    val x: Int,
    val y: Int
)

@Serializable
data class WorldState(
    val seed: Long,
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,
    val width: Int = STANDARD_WORLD_WIDTH,
    val height: Int = STANDARD_WORLD_HEIGHT,
    val actor: WorldActor = WorldActor(),
    val structures: List<WorldStructure> = emptyList(),
    val npcs: List<WorldNpc> = emptyList(),
    val explored: List<WorldCoordinate> = emptyList(),
    val deltas: List<WorldDelta> = emptyList(),
    val tick: Long = 0L,
    val lastSimulatedAt: Long = 0L,
    val roads: List<WorldCoordinate> = emptyList(),
    val objects: List<WorldObject> = emptyList(),
    val knownPlaces: List<KnownPlace> = emptyList(),
    val knownResources: List<KnownResourcePatch> = emptyList(),
    val knownNpcs: List<KnownNpcLocation> = emptyList(),
    val farmPlots: List<FarmPlotReference> = emptyList(),
    val autonomy: AutonomyPolicy = AutonomyPolicy(),
    /** Fixed offset used for deterministic local calendar schedules. */
    val timezoneOffsetMinutes: Int = 0,
    val worldId: String = "",
    val petId: String = ""
) {
    val chunkCountX: Int
        get() = (width + CHUNK_SIZE_TILES - 1) / CHUNK_SIZE_TILES

    val chunkCountY: Int
        get() = (height + CHUNK_SIZE_TILES - 1) / CHUNK_SIZE_TILES

    fun contains(coordinate: WorldCoordinate): Boolean =
        coordinate.x in 0 until width && coordinate.y in 0 until height

    fun withActor(actor: WorldActor): WorldState = copy(actor = actor)
}

@Serializable
data class WorldChunkCoordinate(
    val x: Int,
    val y: Int
)

@Serializable
data class GeneratedTile(
    val coordinate: WorldCoordinate,
    val biome: Biome,
    val kind: TileKind,
    val walkable: Boolean,
    val movementCost: Int = 1
)

@Serializable
data class GeneratedChunk(
    val coordinate: WorldChunkCoordinate,
    val tiles: List<GeneratedTile>
)

@Serializable
sealed interface WorldCommand {
    @Serializable
    @SerialName("go_to")
    data class GoTo(
        val x: Int,
        val y: Int,
        val run: Boolean = false
    ) : WorldCommand

    @Serializable
    @SerialName("go_to_structure")
    data class GoToStructure(
        val structureId: String
    ) : WorldCommand

    @Serializable
    @SerialName("return_home")
    data object ReturnHome : WorldCommand

    @Serializable
    @SerialName("enter_structure")
    data class EnterStructure(
        val structureId: String
    ) : WorldCommand

    @Serializable
    @SerialName("leave_structure")
    data object LeaveStructure : WorldCommand

    @Serializable
    @SerialName("stop")
    data object Stop : WorldCommand

    @Serializable
    @SerialName("interact")
    data class Interact(
        val targetId: String,
        val action: ActionId = ActionId.INSPECT
    ) : WorldCommand

    @Serializable
    @SerialName("perform_action")
    data class PerformAction(
        val action: ActionId,
        val targetId: String? = null,
        val targetX: Int? = null,
        val targetY: Int? = null,
        val arguments: Map<String, String> = emptyMap()
    ) : WorldCommand
}

@Serializable
enum class FarmActionKind {
    TILL_SOIL,
    PLANT,
    WATER,
    POUR_WATER,
    FERTILIZE,
    HARVEST_CROP,
    REMOVE_DEAD_CROP,
    STORE_PRODUCE
}

@Serializable
enum class EventImportance {
    TRACE,
    ROUTINE,
    NOTABLE,
    MEMORABLE,
    MAJOR
}

@Serializable
sealed interface WorldEffectRequest {
    @Serializable
    @SerialName("need_delta")
    data class NeedDelta(
        val need: NeedType,
        val delta: Float,
        val reason: String
    ) : WorldEffectRequest

    @Serializable
    @SerialName("inventory_delta")
    data class InventoryDelta(
        val itemId: String,
        val quantity: Int,
        val reason: String
    ) : WorldEffectRequest

    /**
     * A durability use for a tool owned by the canonical Tama inventory.
     * Farm transitions consume their own farm tool durability in the app
     * adapter, so this request is reserved for world actions such as chopping
     * and mining.
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val kind: String,
        val amount: Int = 1,
        val reason: String = "world_action"
    ) : WorldEffectRequest

    /**
     * Requests one of the canonical Tama operations owned by the app layer.
     *
     * The core still validates the location, prerequisites, duration, and
     * interruption boundary. The app commits the durable operation through
     * its existing transaction after receiving this request, so feed, play,
     * shop, and similar legacy operations keep one canonical cost/reward path.
     */
    @Serializable
    @SerialName("canonical_action")
    data class CanonicalAction(
        val action: String,
        val arguments: Map<String, String> = emptyMap()
    ) : WorldEffectRequest

    @Serializable
    @SerialName("money_delta")
    data class MoneyDelta(
        val amount: Int,
        val reason: String
    ) : WorldEffectRequest

    @Serializable
    @SerialName("farm_transition")
    data class FarmTransition(
        val plotId: String,
        val action: FarmActionKind,
        val cropId: String? = null,
        val arguments: Map<String, String> = emptyMap()
    ) : WorldEffectRequest

    @Serializable
    @SerialName("relationship_delta")
    data class RelationshipDelta(
        val npcId: String,
        val familiarity: Int = 0,
        val friendship: Int = 0,
        val trust: Int = 0,
        val reason: String
    ) : WorldEffectRequest

    @Serializable
    @SerialName("activity")
    data class Activity(
        val activity: PetActivity,
        val reason: String
    ) : WorldEffectRequest

    @Serializable
    @SerialName("event")
    data class Event(
        val eventType: String,
        val importance: EventImportance,
        val actorId: String = "pet",
        val payload: Map<String, String> = emptyMap(),
        /** Factual logical-tick context; null keeps legacy event payloads compatible. */
        val context: WorldEventContext? = null
    ) : WorldEffectRequest

    /** Sparse mutable object upsert/state change, persisted with WorldState. */
    @Serializable
    @SerialName("object_delta")
    data class ObjectDelta(
        val objectId: String,
        val itemId: String? = null,
        val type: WorldObjectType = WorldObjectType.CUSTOM,
        val x: Int,
        val y: Int,
        val state: String = "available",
        val quantity: Int = 1,
        val reason: String
    ) : WorldEffectRequest

    /** Sparse structure state transition, such as opening a gate or door. */
    @Serializable
    @SerialName("structure_delta")
    data class StructureDelta(
        val structureId: String,
        val state: String,
        val reason: String
    ) : WorldEffectRequest

    /** Counterparty inventory transfer; the core also applies it to state. */
    @Serializable
    @SerialName("npc_inventory_delta")
    data class NpcInventoryDelta(
        val npcId: String,
        val itemId: String,
        val quantity: Int,
        val reason: String
    ) : WorldEffectRequest
}

@Serializable
data class SimulationResult(
    val state: WorldState,
    val effects: List<WorldEffectRequest> = emptyList(),
    val acceptedCommand: Boolean = true,
    val rejectionReason: String? = null,
    val observations: List<String> = emptyList()
)
