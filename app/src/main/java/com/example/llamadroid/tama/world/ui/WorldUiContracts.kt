package com.example.llamadroid.tama.world.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.example.llamadroid.R

/**
 * The presentation contract between the living-world controller and Compose.
 *
 * These models intentionally contain a complete snapshot of what the screen may
 * render. The screen never reads Room, asks the simulator to make a decision, or
 * mutates inventory/relationships itself. User intent leaves the package through
 * [WorldUiCommand] so the world controller remains the only authority for state.
 */

enum class WorldBiome(
    val id: String,
    @StringRes val labelRes: Int
) {
    MEADOW("meadow", R.string.tama_world_biome_meadow),
    FOREST("forest", R.string.tama_world_biome_forest),
    WETLANDS("wetlands", R.string.tama_world_biome_wetlands),
    DESERT("desert", R.string.tama_world_biome_desert),
    HIGHLANDS("highlands", R.string.tama_world_biome_highlands),
    TUNDRA("tundra", R.string.tama_world_biome_tundra),
    COAST("coast", R.string.tama_world_biome_coast),
    MYSTIC_GROVE("mystic_grove", R.string.tama_world_biome_mystic_grove)
}

enum class WorldDirection {
    NORTH,
    NORTH_EAST,
    EAST,
    SOUTH_EAST,
    SOUTH,
    SOUTH_WEST,
    WEST,
    NORTH_WEST
}

enum class WorldCameraMode(@StringRes val labelRes: Int) {
    FOLLOW_PET(R.string.tama_world_camera_follow_pet),
    FREE(R.string.tama_world_camera_free),
    FOLLOW_NPC(R.string.tama_world_camera_follow_npc)
}

enum class WorldStructureType(
    val id: String,
    @StringRes val labelRes: Int
) {
    HOME("home", R.string.tama_world_structure_home),
    SHOP("shop", R.string.tama_world_structure_shop),
    PARK("park", R.string.tama_world_structure_park),
    HOSPITAL("hospital", R.string.tama_world_structure_hospital),
    ARCADE("arcade", R.string.tama_world_structure_arcade),
    ALCHEMIST("alchemist", R.string.tama_world_structure_alchemist),
    SCHOOL("school", R.string.tama_world_structure_school),
    WORKPLACE("workplace", R.string.tama_world_structure_workplace),
    FARM("farm", R.string.tama_world_structure_farm),
    BOXING_GYM("boxing_gym", R.string.tama_world_structure_boxing_gym),
    DUNGEON_ENTRANCE("dungeon_entrance", R.string.tama_world_structure_dungeon),
    ADVENTURE_GATE("adventure_gate", R.string.tama_world_structure_adventure_gate),
    NPC_HOME("npc_home", R.string.tama_world_structure_npc_home),
    MARKET_STALL("market_stall", R.string.tama_world_structure_market_stall),
    FARM_BARN("farm_barn", R.string.tama_world_structure_farm_barn),
    NPC_HOME_A("npc_home_a", R.string.tama_world_structure_npc_home_a),
    NPC_HOME_B("npc_home_b", R.string.tama_world_structure_npc_home_b),
    NPC_HOME_C("npc_home_c", R.string.tama_world_structure_npc_home_c),
    LANDMARK("landmark", R.string.tama_world_structure_landmark)
}

enum class WorldResourceKind(
    val id: String,
    @StringRes val labelRes: Int
) {
    TREE("tree", R.string.tama_world_resource_tree),
    BERRY_BUSH("berry_bush", R.string.tama_world_resource_berry_bush),
    ROCK("rock", R.string.tama_world_resource_rock),
    MINERAL("mineral", R.string.tama_world_resource_mineral),
    CROP("crop", R.string.tama_world_resource_crop),
    HERB("herb", R.string.tama_world_resource_herb),
    FLOWER("flower", R.string.tama_world_resource_flower),
    SHELL("shell", R.string.tama_world_resource_shell),
    MUSHROOM("mushroom", R.string.tama_world_resource_mushroom),
    OTHER("other", R.string.tama_world_resource_other)
}

enum class WorldResourceState(
    val id: String,
    @StringRes val labelRes: Int
) {
    FULL("full", R.string.tama_world_resource_state_full),
    EMPTY("empty", R.string.tama_world_resource_state_empty),
    REGROWING("regrowing", R.string.tama_world_resource_state_regrowing),
    SEED("seed", R.string.tama_world_resource_state_seed),
    SPROUT("sprout", R.string.tama_world_resource_state_sprout),
    YOUNG("young", R.string.tama_world_resource_state_young),
    MATURE("mature", R.string.tama_world_resource_state_mature),
    HARVESTED("harvested", R.string.tama_world_resource_state_harvested),
    DEPLETED("depleted", R.string.tama_world_resource_state_depleted)
}

enum class WorldFarmSoilState(val terrainId: String) {
    UNPLOWED("terrain_dirt"),
    DRY("terrain_farmland_dry"),
    WET("terrain_farmland_wet")
}

enum class WorldFarmCropStage(val stage: Int) {
    NONE(-1),
    STAGE_0(0),
    STAGE_1(1),
    STAGE_2(2),
    STAGE_3(3),
    DECAYED(-1);

    val hasCrop: Boolean
        get() = this != NONE

    val clipAction: String
        get() = when (this) {
            NONE -> "idle"
            STAGE_0 -> "stage_0"
            STAGE_1 -> "stage_1"
            STAGE_2 -> "stage_2"
            STAGE_3 -> "stage_3"
            DECAYED -> "decayed"
        }
}

/** Farm ownership is deliberately separate from pet/actor identifiers. */
sealed interface WorldFarmOwnershipUi {
    data class Player(val petId: String) : WorldFarmOwnershipUi
    data class Patch(val patchId: String) : WorldFarmOwnershipUi
    data object Unassigned : WorldFarmOwnershipUi
}

@Immutable
data class WorldFarmTileUi(
    val id: String,
    val x: Int,
    val y: Int,
    val unlocked: Boolean = true,
    val soil: WorldFarmSoilState = WorldFarmSoilState.DRY,
    val cropId: String? = null,
    val cropStage: WorldFarmCropStage = WorldFarmCropStage.NONE,
    /** A manifest ID supplied by the asset registry; null uses crop_world_<id>. */
    val cropAssetId: String? = null,
    val ownership: WorldFarmOwnershipUi = WorldFarmOwnershipUi.Unassigned,
    /** NPC patch timers are informational and never reused as player farm state. */
    val isReady: Boolean = false,
    val isDead: Boolean = false,
    val plantedAtTick: Long? = null,
    val wateredAtTick: Long? = null
) {
    val renderCropAssetId: String?
        get() = cropId?.let { cropAssetId ?: "crop_world_${it.lowercase()}" }

    val hasCrop: Boolean
        get() = unlocked && cropStage.hasCrop && renderCropAssetId != null
}

@Immutable
data class WorldPointUi(
    val x: Float,
    val y: Float
)

@Immutable
data class WorldTileUi(
    val x: Int,
    val y: Int,
    val terrainId: String,
    val biome: WorldBiome,
    val known: Boolean,
    val movementCost: Float = 1f,
    val water: Boolean = false,
    val food: Boolean = false,
    val resource: Boolean = false,
    val tree: Boolean = false,
    val crop: Boolean = false,
    val structure: Boolean = false,
    val npc: Boolean = false,
    val hazard: Boolean = false,
    val goalMarker: Boolean = false,
    val tintArgb: Int? = null,
    val farmTile: WorldFarmTileUi? = null
)

@Immutable
data class WorldActorUi(
    val id: String,
    val displayName: String,
    val role: String,
    val speciesId: String,
    val assetId: String,
    val position: WorldPointUi,
    val previousPosition: WorldPointUi? = null,
    val direction: WorldDirection = WorldDirection.SOUTH,
    val action: String = "idle",
    val frameIndex: Int = 0,
    val depth: Float = position.y,
    val isPet: Boolean = false,
    val stationary: Boolean = false,
    val friendship: Int? = null,
    val currentActivity: String? = null,
    val nextNeed: String? = null,
    val homeStructureId: String? = null
)

@Immutable
data class WorldStructureUi(
    val id: String,
    val displayName: String,
    val type: WorldStructureType,
    val assetId: String,
    val position: WorldPointUi,
    val widthTiles: Int,
    val heightTiles: Int,
    val entrance: WorldPointUi? = null,
    val collision: String = "solid",
    val collisionMask: List<List<Int>> = emptyList(),
    val discovered: Boolean = true,
    val depth: Float = position.y + heightTiles
)

@Immutable
data class WorldResourceUi(
    val id: String,
    val displayName: String,
    val kind: WorldResourceKind,
    val state: WorldResourceState,
    val assetId: String,
    val position: WorldPointUi,
    val widthTiles: Int = 1,
    val heightTiles: Int = 1,
    val healthPercent: Int = 100,
    val availableActions: List<String> = emptyList(),
    val depth: Float = position.y + heightTiles
)

@Immutable
data class WorldPetStatusUi(
    val hunger: Int? = null,
    val hydration: Int? = null,
    val energy: Int? = null,
    val health: Int,
    val happiness: Int,
    val social: Int,
    val hygiene: Int? = null,
    val curiosity: Int? = null
)

@Immutable
data class WorldHudUi(
    val petName: String,
    val currentGoal: String,
    val currentGoalReason: String,
    val currentAction: String,
    val nextNeed: String,
    val worldTime: String,
    val biome: WorldBiome,
    val petStatus: WorldPetStatusUi,
    val isPaused: Boolean = false,
    val simulationRateHz: Float = 5f
)

@Immutable
data class WorldCameraUi(
    val centerX: Float,
    val centerY: Float,
    val zoom: Float = 1f,
    val mode: WorldCameraMode = WorldCameraMode.FOLLOW_PET,
    val followActorId: String? = null
) {
    fun clamped(): WorldCameraUi = copy(zoom = zoom.coerceIn(0.5f, 4f))
}

@Immutable
data class WorldMiniMapTileUi(
    val x: Int,
    val y: Int,
    val biome: WorldBiome,
    val terrainId: String,
    val known: Boolean,
    /** Number of source tiles represented by this bounded minimap cell. */
    val spanTiles: Int = 1
)

@Immutable
data class WorldMiniMapMarkerUi(
    val id: String,
    val x: Int,
    val y: Int,
    val kind: String,
    val selected: Boolean = false,
    val assetId: String? = null
)

@Immutable
data class WorldMiniMapUi(
    val widthTiles: Int,
    val heightTiles: Int,
    val tiles: List<WorldMiniMapTileUi>,
    val markers: List<WorldMiniMapMarkerUi> = emptyList(),
    val knownOnly: Boolean = true
)

@Immutable
data class WorldAssetReadinessUi(
    val manifestPath: String,
    val schemaVersion: Int?,
    val loadedAssetIds: Set<String> = emptySet(),
    val missingAssetIds: List<String> = emptyList(),
    val invalidAssetIds: List<String> = emptyList(),
    /** Valid entries intentionally left out of the bounded visible atlas. */
    val deferredAssetIds: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val isReady: Boolean
        get() = !isLoading && error == null && missingAssetIds.isEmpty() && invalidAssetIds.isEmpty()
}

@Immutable
data class WorldUiState(
    val worldSeed: Long,
    val generatorVersion: Int,
    val worldWidthTiles: Int,
    val worldHeightTiles: Int,
    val tiles: List<WorldTileUi>,
    val actors: List<WorldActorUi>,
    val structures: List<WorldStructureUi>,
    val resources: List<WorldResourceUi> = emptyList(),
    val camera: WorldCameraUi,
    val hud: WorldHudUi,
    val minimap: WorldMiniMapUi? = null,
    val inspector: WorldInspectorUi? = null,
    val activeCommand: WorldPetCommandKind? = null,
    val assetReadiness: WorldAssetReadinessUi,
    val isLoading: Boolean = false,
    val unavailableReason: String? = null,
    /** Canonical FarmRepository observations; locked/generated references are omitted. */
    val farmTiles: List<WorldFarmTileUi> = emptyList()
)

@Immutable
data class WorldInspectorField(
    val label: String,
    val value: String,
    val emphasize: Boolean = false
)

@Immutable
data class WorldInspectorAction(
    val id: String,
    val label: String,
    val enabled: Boolean = true
)

sealed interface WorldInspectorUi {
    val targetId: String
    val title: String
    val subtitle: String
    val fields: List<WorldInspectorField>
    val actions: List<WorldInspectorAction>

    data class Pet(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val actor: WorldActorUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi

    data class Npc(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val actor: WorldActorUi,
        val relationship: WorldRelationshipUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi

    data class Tree(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val resource: WorldResourceUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi

    data class Resource(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val resource: WorldResourceUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi

    data class Structure(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val structure: WorldStructureUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi

    data class Tile(
        override val targetId: String,
        override val title: String,
        override val subtitle: String,
        val tile: WorldTileUi,
        override val fields: List<WorldInspectorField>,
        override val actions: List<WorldInspectorAction>
    ) : WorldInspectorUi
}

@Immutable
data class WorldRelationshipUi(
    val familiarity: Int,
    val friendship: Int,
    val trust: Int,
    val recentInteraction: String,
    val lastMetAt: String,
    val helpCount: Int,
    val sharedEvents: Int
)

sealed interface WorldInspectTarget {
    val id: String

    data class Actor(override val id: String) : WorldInspectTarget
    data class Structure(override val id: String) : WorldInspectTarget
    data class Resource(override val id: String) : WorldInspectTarget
    data class Tile(val x: Int, val y: Int) : WorldInspectTarget {
        override val id: String = "tile:$x:$y"
    }
}

enum class WorldPetCommandKind(@StringRes val labelRes: Int) {
    COME_HOME(R.string.tama_world_command_come_home),
    GO_HERE(R.string.tama_world_command_go_here),
    VISIT_NPC(R.string.tama_world_command_visit_npc),
    EXPLORE(R.string.tama_world_command_explore),
    REST(R.string.tama_world_command_rest),
    STOP(R.string.tama_world_command_stop)
}

sealed interface WorldPetCommand {
    data object ComeHome : WorldPetCommand
    data class GoHere(val x: Int, val y: Int) : WorldPetCommand
    data class VisitNpc(val actorId: String) : WorldPetCommand
    data class Explore(val x: Int, val y: Int) : WorldPetCommand
    data object Rest : WorldPetCommand
    data object Stop : WorldPetCommand
}

sealed interface WorldUiCommand {
    /** Leaves the world overview and returns to the Tama room host. */
    data object CloseWorld : WorldUiCommand
    data object OpenHome : WorldUiCommand
    data object OpenWorld : WorldUiCommand
    data object OpenJournal : WorldUiCommand
    data object OpenBrainTraining : WorldUiCommand
    data object OpenInventory : WorldUiCommand
    data object Recenter : WorldUiCommand
    data object DismissInspector : WorldUiCommand
    data object CancelTargetedCommand : WorldUiCommand
    data object ShowCommandPalette : WorldUiCommand
    data object ToggleMinimap : WorldUiCommand
    data class SetCameraMode(val mode: WorldCameraMode, val actorId: String? = null) : WorldUiCommand
    data class PanCamera(val deltaX: Float, val deltaY: Float) : WorldUiCommand
    data class ZoomCamera(val factor: Float, val focusX: Float, val focusY: Float) : WorldUiCommand
    data class FollowActor(val actorId: String) : WorldUiCommand
    data class Inspect(val target: WorldInspectTarget) : WorldUiCommand
    data class InspectorAction(val targetId: String, val actionId: String) : WorldUiCommand
    data class BeginTargetedCommand(val kind: WorldPetCommandKind) : WorldUiCommand
    data class IssuePetCommand(val command: WorldPetCommand) : WorldUiCommand
}

data class WorldUiCallbacks(
    val onCommand: (WorldUiCommand) -> Unit
)

@Immutable
data class WorldEventResultUi(
    val label: String,
    val value: String
)

enum class WorldEventImportance(@StringRes val labelRes: Int) {
    TRACE(R.string.tama_world_importance_trace),
    ROUTINE(R.string.tama_world_importance_routine),
    NOTABLE(R.string.tama_world_importance_notable),
    MEMORABLE(R.string.tama_world_importance_memorable),
    MAJOR(R.string.tama_world_importance_major)
}

enum class WorldEventSource(@StringRes val labelRes: Int) {
    LIVING_WORLD(R.string.tama_world_source_living),
    TRAINING_WORLD(R.string.tama_world_source_training)
}

@Immutable
data class WorldEventUi(
    val id: String,
    val timestamp: String,
    val title: String,
    val detail: String,
    val importance: WorldEventImportance,
    val source: WorldEventSource,
    val locationLabel: String,
    val biome: WorldBiome,
    val actorName: String? = null,
    val results: List<WorldEventResultUi> = emptyList(),
    val memoryEligible: Boolean = source == WorldEventSource.LIVING_WORLD
)

@Immutable
data class WorldEpisodeUi(
    val id: String,
    val title: String,
    val timeRange: String,
    val locationLabel: String,
    val biome: WorldBiome,
    val importance: WorldEventImportance,
    val summary: String,
    val events: List<WorldEventUi>,
    val memoryEligible: Boolean = events.any { it.memoryEligible },
    val source: WorldEventSource = WorldEventSource.LIVING_WORLD
)

@Immutable
data class WorldJournalUiState(
    val episodes: List<WorldEpisodeUi>,
    val selectedEpisodeId: String? = null,
    val filter: WorldJournalFilter = WorldJournalFilter.ALL,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Per-pet policy; training-world records never enter this state. */
    val memoryPolicy: WorldMemoryPolicy = WorldMemoryPolicy.AUTO_MAJOR,
    /** CLOSED/PENDING candidates can be approved; APPROVED waits for the worker. */
    val pendingMemories: List<WorldMemoryCandidateUi> = emptyList()
)

enum class WorldJournalFilter(@StringRes val labelRes: Int) {
    ALL(R.string.tama_world_journal_filter_all),
    NOTABLE(R.string.tama_world_journal_filter_notable),
    MEMORIES(R.string.tama_world_journal_filter_memories)
}

enum class WorldMemoryPolicy(@StringRes val labelRes: Int) {
    NEVER(R.string.tama_world_memory_policy_never),
    ASK(R.string.tama_world_memory_policy_ask),
    AUTO_MAJOR(R.string.tama_world_memory_policy_auto_major)
}

@Immutable
data class WorldMemoryCandidateUi(
    val episodeId: String,
    val title: String,
    val summary: String,
    val timeRange: String,
    val importance: WorldEventImportance,
    /** CLOSED/PENDING are actionable; APPROVED is queued for the memory worker. */
    val memoryStatus: String,
    val canSave: Boolean = memoryStatus == "CLOSED" || memoryStatus == "PENDING"
)

data class WorldJournalCallbacks(
    val onBack: () -> Unit,
    val onSelectEpisode: (String) -> Unit,
    val onFilterChanged: (WorldJournalFilter) -> Unit,
    val onOpenEvent: (String) -> Unit = {},
    val onCommand: (WorldUiCommand) -> Unit = {},
    val onMemoryPolicyChanged: (WorldMemoryPolicy) -> Unit = {},
    val onSaveMemory: (String) -> Unit = {}
)

@Immutable
data class BrainTrainingMetricsUi(
    val episodes: Long,
    val successRatePercent: Float,
    val meanReward: Float,
    val objectiveSteps: Float,
    val pathEfficiencyPercent: Float,
    val stuckRatePercent: Float,
    val criticalNeedsRatePercent: Float,
    val explorationScorePercent: Float
)

@Immutable
data class BrainMetricPointUi(
    val step: Long,
    val value: Float
)

@Immutable
data class BrainMetricSeriesUi(
    val id: String,
    val label: String,
    val unit: String,
    val points: List<BrainMetricPointUi>,
    val evaluationPoints: List<BrainMetricPointUi> = emptyList()
)

@Immutable
data class BrainMiniTileUi(
    val x: Int,
    val y: Int,
    val terrainId: String,
    val known: Boolean,
    val walkable: Boolean,
    val food: Boolean = false,
    val resource: Boolean = false,
    val hazard: Boolean = false,
    val goal: Boolean = false
)

@Immutable
data class BrainMiniActorUi(
    val id: String,
    val x: Float,
    val y: Float,
    val isPet: Boolean,
    val label: String
)

@Immutable
data class BrainLiveEnvironmentUi(
    val episodeNumber: Long,
    val seed: Long,
    val goal: String,
    val currentAction: String,
    val hunger: Int? = null,
    val hydration: Int? = null,
    val energy: Int? = null,
    val reward: Float? = null,
    val widthTiles: Int = 15,
    val heightTiles: Int = 15,
    val tiles: List<BrainMiniTileUi> = emptyList(),
    val actors: List<BrainMiniActorUi> = emptyList(),
    val goalX: Int? = null,
    val goalY: Int? = null,
    val terminated: Boolean = false,
    val success: Boolean = false
)

@Immutable
data class BrainCheckpointUi(
    val id: String,
    val displayName: String,
    val modelHash: String,
    val trainingEpisodes: Long,
    val curriculum: String,
    val rewardConfiguration: String,
    val metricSummary: String,
    val createdAt: String,
    val parentCheckpointId: String? = null,
    val isCurrent: Boolean = false,
    val isCandidate: Boolean = false,
    val restorable: Boolean = true
)

@Immutable
data class BrainEvaluationUi(
    val candidateCheckpointId: String,
    val candidateName: String,
    val currentSuccessPercent: Float,
    val candidateSuccessPercent: Float,
    val currentObjectiveSteps: Float,
    val candidateObjectiveSteps: Float,
    val currentStuckPercent: Float,
    val candidateStuckPercent: Float,
    val currentNeedFailurePercent: Float,
    val candidateNeedFailurePercent: Float,
    val unseenSeedCount: Int,
    val complete: Boolean,
    val candidateWins: Boolean,
    /** Whether the comparison's current column is the living adopted artifact. */
    val currentPolicyIsAdopted: Boolean = false,
    val currentPathEfficiencyPercent: Float = 0f,
    val candidatePathEfficiencyPercent: Float = 0f,
    val currentExplorationPercent: Float = 0f,
    val candidateExplorationPercent: Float = 0f,
    val currentInvalidActionPercent: Float = 0f,
    val candidateInvalidActionPercent: Float = 0f
)

enum class BrainRunState(@StringRes val labelRes: Int) {
    IDLE(R.string.tama_world_brain_state_idle),
    RUNNING(R.string.tama_world_brain_state_running),
    PAUSED(R.string.tama_world_brain_state_paused),
    FAILED(R.string.tama_world_brain_state_failed)
}

enum class WorldAutonomyLevel(@StringRes val labelRes: Int) {
    OFF(R.string.tama_world_autonomy_level_off),
    SAFE(R.string.tama_world_autonomy_level_safe),
    NORMAL(R.string.tama_world_autonomy_level_normal),
    FULL(R.string.tama_world_autonomy_level_full)
}

@Immutable
data class BrainTrainingProfileUi(
    val id: String,
    val name: String,
    val description: String,
    val parallelEnvironments: Int,
    val cpuThreads: Int,
    val maxThermalLevel: Int,
    val pauseOnLowBattery: Boolean,
    val chargingOnly: Boolean,
    val selected: Boolean = false
)

@Immutable
data class SafeAutonomyUi(
    val level: WorldAutonomyLevel = WorldAutonomyLevel.SAFE,
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
)

@Immutable
data class BrainResourceSettingsUi(
    val chargingOnly: Boolean = true,
    val minimumBatteryPercent: Int = 20,
    val maxThreads: Int = 1,
    val environmentCount: Int = 2
)

@Immutable
data class BrainAdoptedPolicyUi(
    val id: String,
    val version: Int,
    val modelHash: String?,
    val createdAt: Long,
    val parentId: String? = null,
    val sourceCheckpointId: String? = null,
    val active: Boolean = false,
    val baseline: Boolean = false
)

@Immutable
data class BrainTrainingUiState(
    val currentBrainName: String,
    val currentBrainVersion: Int,
    val trainingAgeEpisodes: Long,
    val adopted: Boolean,
    val currentCurriculum: String,
    val currentCurriculumId: Int = 0,
    val metrics: BrainTrainingMetricsUi,
    val liveEnvironment: BrainLiveEnvironmentUi?,
    val runState: BrainRunState,
    val speedMultiplier: Int,
    val charts: List<BrainMetricSeriesUi>,
    val checkpoints: List<BrainCheckpointUi>,
    val adoptedPolicies: List<BrainAdoptedPolicyUi> = emptyList(),
    val activePolicyIsBaseline: Boolean = false,
    val evaluation: BrainEvaluationUi? = null,
    val profiles: List<BrainTrainingProfileUi> = emptyList(),
    val safeAutonomy: SafeAutonomyUi,
    val isLoading: Boolean = false,
    val error: String? = null,
    val resourceSettings: BrainResourceSettingsUi = BrainResourceSettingsUi()
)

data class BrainTrainingCallbacks(
    val onBack: () -> Unit,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onSpeedChanged: (Int) -> Unit,
    val onProfileSelected: (String) -> Unit,
    val onCheckpointSaved: () -> Unit,
    val onEvaluate: (String) -> Unit,
    val onAdoptCandidate: (String) -> Unit,
    val onKeepCurrent: () -> Unit,
    val onRestoreCheckpoint: (String) -> Unit,
    val onRestoreAdoptedPolicy: (String) -> Unit = {},
    val onSafeAutonomyChanged: (SafeAutonomyUi) -> Unit,
    val onOpenJournal: () -> Unit,
    val onCurriculumSelected: (Int) -> Unit = {},
    val onResourcesChanged: (BrainResourceSettingsUi) -> Unit = {}
)
