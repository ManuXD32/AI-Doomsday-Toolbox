package com.example.llamadroid.tama.world.presentation

import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.Biome
import com.example.llamadroid.tama.world.core.Direction
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.NeedType
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.TileKind
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldObject
import com.example.llamadroid.tama.world.core.WorldObjectType
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.WorldBuildingLayouts
import com.example.llamadroid.tama.world.ui.DEFAULT_WORLD_ASSET_MANIFEST_PATH
import com.example.llamadroid.tama.world.ui.WorldActorUi
import com.example.llamadroid.tama.world.ui.WorldAssetReadinessUi
import com.example.llamadroid.tama.world.ui.WorldBiome
import com.example.llamadroid.tama.world.ui.WorldCameraMode
import com.example.llamadroid.tama.world.ui.WorldCameraUi
import com.example.llamadroid.tama.world.ui.WorldDirection
import com.example.llamadroid.tama.world.ui.WorldFarmCropStage
import com.example.llamadroid.tama.world.ui.WorldFarmOwnershipUi
import com.example.llamadroid.tama.world.ui.WorldFarmSoilState
import com.example.llamadroid.tama.world.ui.WorldFarmTileUi
import com.example.llamadroid.tama.world.ui.WorldHudUi
import com.example.llamadroid.tama.world.ui.WorldInspectTarget
import com.example.llamadroid.tama.world.ui.WorldInspectorAction
import com.example.llamadroid.tama.world.ui.WorldInspectorField
import com.example.llamadroid.tama.world.ui.WorldInspectorUi
import com.example.llamadroid.tama.world.ui.WorldMiniMapMarkerUi
import com.example.llamadroid.tama.world.ui.WorldMiniMapTileUi
import com.example.llamadroid.tama.world.ui.WorldMiniMapUi
import com.example.llamadroid.tama.world.ui.WorldPetCommand
import com.example.llamadroid.tama.world.ui.WorldPetStatusUi
import com.example.llamadroid.tama.world.ui.WorldPointUi
import com.example.llamadroid.tama.world.ui.WorldRelationshipUi
import com.example.llamadroid.tama.world.ui.WorldResourceKind
import com.example.llamadroid.tama.world.ui.WorldResourceState
import com.example.llamadroid.tama.world.ui.WorldResourceUi
import com.example.llamadroid.tama.world.ui.WorldStructureType
import com.example.llamadroid.tama.world.ui.WorldStructureUi
import com.example.llamadroid.tama.world.ui.WorldTileUi
import com.example.llamadroid.tama.world.ui.WorldUiCommand
import com.example.llamadroid.tama.world.ui.WorldUiState
import com.example.llamadroid.tama.world.ui.WorldPetCommandKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.Locale
import java.util.TimeZone

/** Number of tiles the projection asks the renderer to materialize. */
data class WorldUiViewport(
    val widthTiles: Int = 22,
    val heightTiles: Int = 14,
    val marginTiles: Int = 2
) {
    init {
        require(widthTiles > 0 && heightTiles > 0 && marginTiles >= 0)
    }
}

/**
 * Dynamic labels are supplied by the app so this adapter never hard-codes a
 * locale into a state snapshot. Defaults are useful for tests and diagnostics;
 * production callers should provide functions backed by localized resources.
 */
data class WorldUiLabels(
    val petName: String = "Pet",
    val biomeName: (Biome) -> String = ::humanize,
    val goalName: (GoalId) -> String = ::humanize,
    val actionName: (ActionId) -> String = ::humanize,
    val roleName: (NpcRole) -> String = ::humanize,
    val structureName: (StructureType) -> String = ::humanize,
    val objectName: (WorldObjectType) -> String = ::humanize,
    val needName: (NeedType) -> String = ::humanize,
    val presenceName: (PresenceMode) -> String = ::humanize,
    val stateName: (String) -> String = { value -> humanize(value) },
    val inspectorFieldName: (String) -> String = { value -> humanize(value) },
    val timeName: (Long) -> String = { timestamp ->
        formatWorldTime(
            epochMillis = timestamp,
            timezoneOffsetMinutes = TimeZone.getDefault().getOffset(timestamp) / 60_000,
            locale = Locale.getDefault()
        )
    }
)

/**
 * Converts the authoritative core snapshot into the bounded Compose state.
 * The function is pure and may be called again whenever the controller emits a
 * new snapshot or the camera viewport changes.
 */
fun projectWorldState(
    state: WorldState,
    camera: WorldCameraUi = WorldCameraUi(state.actor.preciseX.toFloat(), state.actor.preciseY.toFloat()),
    viewport: WorldUiViewport = WorldUiViewport(),
    labels: WorldUiLabels = WorldUiLabels(),
    petSpeciesId: String = "pet_dragon",
    petStage: String = "baby",
    relationshipByNpc: Map<String, WorldRelationshipUi> = emptyMap(),
    inspectorTarget: WorldInspectTarget? = null,
    activeCommand: WorldPetCommandKind? = null,
    assetReadiness: WorldAssetReadinessUi = WorldAssetReadinessUi(
        manifestPath = DEFAULT_WORLD_ASSET_MANIFEST_PATH,
        schemaVersion = null,
        isLoading = true
    ),
    isPaused: Boolean = false,
    simulationRateHz: Float = 10f,
    /** Reuse this layer between snapshots; rebuild it when seed/exploration changes. */
    cachedMinimap: WorldMiniMapUi? = null,
    /** Observations emitted by FarmRepository; only these canonical unlocked tiles render. */
    observedFarmTiles: List<FarmTile> = emptyList(),
    /** Optional ownership overlay for shared/patch farms; IDs never share the pet field. */
    farmOwnershipByTileId: Map<Int, WorldFarmOwnershipUi> = emptyMap(),
    /** Pass the repository's unlocked set when its stream can include stale rows. */
    unlockedFarmTileIds: Set<Int>? = null,
    playerPetId: String = state.petId,
    /** Optional manifest aliases for crop_world_<id> when a concrete crop asset exists. */
    cropAssetIdByCropId: Map<String, String> = emptyMap()
): WorldUiState {
    val map = WorldGenerator.map(state)
    val explored = state.explored.mapTo(HashSet()) { it.x to it.y }
    val bounds = visibleBounds(state, camera, viewport)
    val farmTiles = projectObservedFarmTiles(
        state = state,
        observedTiles = observedFarmTiles,
        ownershipByTileId = farmOwnershipByTileId,
        unlockedTileIds = unlockedFarmTileIds,
        playerPetId = playerPetId,
        cropAssetIdByCropId = cropAssetIdByCropId
    )
    // NPC plots are simulation-owned patches. Keep them beside the player farm
    // rows for drawing/inspection, while their Patch ownership and tick fields
    // remain distinct from FarmRepository/player data.
    val npcFarmTiles = projectNpcFarmTiles(state, cropAssetIdByCropId)
    val allFarmTiles = npcFarmTiles + farmTiles
    val farmTilesByCoordinate = allFarmTiles.associateBy { it.x to it.y }
    val visibleStructures = state.structures.filter { structure ->
        structureIsKnown(state, structure) && intersects(structure.x, structure.y, structure.width, structure.height, bounds)
    }
    val visibleNpcs = state.npcs.filter { npc ->
        val known = (npc.coordinate.x to npc.coordinate.y) in explored
        known && npc.x in bounds.left..bounds.right && npc.y in bounds.top..bounds.bottom
    }
    val visibleTiles = ArrayList<WorldTileUi>((bounds.right - bounds.left + 1) * (bounds.bottom - bounds.top + 1))
    val visibleResources = ArrayList<WorldResourceUi>()
    for (y in bounds.top..bounds.bottom) {
        for (x in bounds.left..bounds.right) {
            val coordinate = WorldCoordinate(x, y)
            val generated = map.tileAt(x, y)
            val known = coordinate.x to coordinate.y in explored || coordinate == state.actor.coordinate
            val objectValue = if (known) map.objectAt(x, y) else null
            val structure = state.structures.firstOrNull { it.contains(coordinate) }
            val npc = visibleNpcs.any { it.x == x && it.y == y }
            val worldResource = objectValue?.let { projectResource(it, labels, generated.biome) }
            if (worldResource != null && (objectValue.quantity > 0 || objectValue.type in setOf(
                WorldObjectType.TREE, WorldObjectType.STONE, WorldObjectType.BERRY_PATCH, WorldObjectType.BUSH))) {
                visibleResources += worldResource
            }
            val farmTile = farmTilesByCoordinate[x to y]
            visibleTiles += WorldTileUi(
                x = x,
                y = y,
                terrainId = farmTile?.soil?.terrainId ?: terrainId(generated.kind),
                biome = generated.biome.toUiBiome(),
                known = known,
                movementCost = generated.movementCost.toFloat().coerceAtLeast(1f),
                water = generated.kind.isWater(),
                food = objectValue?.type == WorldObjectType.BERRY_PATCH,
                resource = objectValue != null,
                tree = objectValue?.type == WorldObjectType.TREE,
                crop = farmTile?.hasCrop == true,
                structure = structure != null,
                npc = npc,
                hazard = false,
                goalMarker = state.actor.destination?.let { destination -> destination.x == x && destination.y == y } == true,
                farmTile = farmTile
            )
        }
    }
    val actors = buildList {
        add(projectPet(state, labels, petSpeciesId, petStage))
        visibleNpcs.forEach { npc ->
            add(projectNpc(npc, labels, relationshipByNpc[npc.id]))
        }
    }
    val structures = visibleStructures.map { structure -> projectStructure(structure, labels) }
    val miniMap = cachedMinimap?.copy(
        markers = projectMiniMapMarkers(state, explored, inspectorTarget)
    ) ?: projectMiniMap(state, map, explored, inspectorTarget)
    val petNeeds = state.actor.needs
    val hud = WorldHudUi(
        petName = labels.petName,
        currentGoal = labels.goalName(state.actor.goal),
        currentGoalReason = goalReason(state, labels),
        currentAction = labels.actionName(state.actor.action),
        nextNeed = nextNeed(state, labels),
        worldTime = labels.timeName(state.lastSimulatedAt),
        biome = map.biomeAt(state.actor.x, state.actor.y).toUiBiome(),
        petStatus = WorldPetStatusUi(
            hunger = petNeeds.hunger.percent(),
            hydration = petNeeds.hydration.percent(),
            energy = petNeeds.energy.percent(),
            health = petNeeds.health.percent(),
            happiness = petNeeds.happiness.percent(),
            social = petNeeds.social.percent(),
            hygiene = petNeeds.hygiene.percent(),
            curiosity = petNeeds.curiosity.percent()
        ),
        isPaused = isPaused,
        simulationRateHz = simulationRateHz
    )
    val inspector = inspectorTarget?.let {
        projectInspector(state, map, it, labels, petSpeciesId, petStage, relationshipByNpc, farmTilesByCoordinate)
    }
    return WorldUiState(
        worldSeed = state.seed,
        generatorVersion = state.generatorVersion,
        worldWidthTiles = state.width,
        worldHeightTiles = state.height,
        tiles = visibleTiles,
        actors = actors,
        structures = structures,
        resources = visibleResources,
        camera = camera.clamped(),
        hud = hud,
        minimap = miniMap,
        inspector = inspector,
        activeCommand = activeCommand,
        assetReadiness = assetReadiness,
        isLoading = false,
        farmTiles = allFarmTiles
    )
}

/**
 * Converts the observed FarmRepository rows into world coordinates. The core
 * world stores references for pathing, while this list is the canonical
 * durable farm state used for rendering. Missing rows therefore produce no
 * plot or crop in the world view.
 */
fun projectObservedFarmTiles(
    state: WorldState,
    observedTiles: List<FarmTile>,
    ownershipByTileId: Map<Int, WorldFarmOwnershipUi> = emptyMap(),
    unlockedTileIds: Set<Int>? = null,
    playerPetId: String = state.petId,
    cropAssetIdByCropId: Map<String, String> = emptyMap()
): List<WorldFarmTileUi> {
    val references = state.farmPlots.associateBy { reference ->
        reference.id.removePrefix("farm_plot_").toIntOrNull()
    }
    val unlocked = unlockedTileIds ?: observedTiles.asSequence().map(FarmTile::id).toSet()
    return observedTiles.asSequence()
        .filter { tile -> tile.id in unlocked }
        .mapNotNull { tile ->
            val reference = references[tile.id] ?: return@mapNotNull null
            val crop = tile.crop
            val owner = ownershipByTileId[tile.id]
                ?: playerPetId.takeIf(String::isNotBlank)?.let(WorldFarmOwnershipUi::Player)
                ?: WorldFarmOwnershipUi.Unassigned
            WorldFarmTileUi(
                id = reference.id,
                x = reference.x,
                y = reference.y,
                unlocked = true,
                soil = tile.status.toWorldFarmSoil(),
                cropId = crop?.type,
                cropStage = crop.toWorldFarmCropStage(),
                cropAssetId = crop?.type?.let(cropAssetIdByCropId::get),
                ownership = owner,
                isReady = crop?.let { it.stage >= 3 && !it.isDecayed } == true,
                isDead = crop?.isDecayed == true
            )
        }
        .toList()
}

/**
 * Projects the farmer NPC's own fields without joining them to the player's
 * Room rows. These plots carry simulation ticks for inspection/debugging and
 * use a Patch owner so autonomy permissions cannot be inferred from them.
 */
private fun projectNpcFarmTiles(
    state: WorldState,
    cropAssetIdByCropId: Map<String, String>
): List<WorldFarmTileUi> = state.npcs.asSequence()
    .flatMap { npc ->
        npc.ownFarm.asSequence().map { plot ->
            val cropStage = when {
                plot.isDead -> WorldFarmCropStage.DECAYED
                plot.cropId == null -> WorldFarmCropStage.NONE
                plot.isReady -> WorldFarmCropStage.STAGE_3
                else -> WorldFarmCropStage.STAGE_1
            }
            WorldFarmTileUi(
                id = plot.id,
                x = plot.x,
                y = plot.y,
                soil = plot.status.toWorldFarmSoil(),
                cropId = plot.cropId,
                cropStage = cropStage,
                cropAssetId = plot.cropId?.let(cropAssetIdByCropId::get),
                ownership = WorldFarmOwnershipUi.Patch(npc.id),
                isReady = plot.isReady,
                isDead = plot.isDead,
                plantedAtTick = plot.plantedAtTick,
                wateredAtTick = plot.wateredAtTick
            )
        }
    }
    .toList()

/** Maps a UI command to a pure core command. Navigation and camera-only commands return null. */
fun WorldUiCommand.toCoreCommand(
    state: WorldState,
    inspector: WorldInspectorUi? = null
): WorldCommand? = when (this) {
    is WorldUiCommand.IssuePetCommand -> command.toCoreCommand(state)
    is WorldUiCommand.InspectorAction -> inspector
        ?.takeIf { it.targetId == targetId }
        ?.let { inspectorCommand(it, actionId) }
    is WorldUiCommand.FollowActor -> WorldCommand.PerformAction(ActionId.FOLLOW_NPC, targetId = actorId)
    // Selection is a presentation concern. Only an explicit inspector action
    // may enqueue a simulator command and advance the world clock.
    is WorldUiCommand.Inspect -> null
    else -> null
}

private fun WorldPetCommand.toCoreCommand(state: WorldState): WorldCommand? = when (this) {
    WorldPetCommand.ComeHome -> WorldCommand.ReturnHome
    is WorldPetCommand.GoHere -> WorldCommand.GoTo(x, y)
    is WorldPetCommand.Explore -> WorldCommand.GoTo(x, y)
    is WorldPetCommand.VisitNpc -> state.npcs.firstOrNull { it.id == actorId }?.let {
        WorldCommand.GoTo(it.x, it.y)
    }
    WorldPetCommand.Rest -> WorldCommand.PerformAction(ActionId.REST)
    WorldPetCommand.Stop -> WorldCommand.Stop
}

private fun inspectCommand(target: WorldInspectTarget): WorldCommand = when (target) {
    is WorldInspectTarget.Actor -> WorldCommand.Interact(target.id, ActionId.INSPECT)
    is WorldInspectTarget.Resource -> WorldCommand.Interact(target.id, ActionId.INSPECT)
    is WorldInspectTarget.Structure -> WorldCommand.Interact(target.id, ActionId.INSPECT)
    is WorldInspectTarget.Tile -> WorldCommand.PerformAction(ActionId.INSPECT, targetX = target.x, targetY = target.y)
}

private fun inspectorCommand(inspector: WorldInspectorUi, actionId: String): WorldCommand? {
    val action = runCatching { ActionId.valueOf(actionId) }.getOrNull() ?: return null
    return when (inspector) {
        is WorldInspectorUi.Pet -> WorldCommand.PerformAction(action, targetId = inspector.targetId)
        is WorldInspectorUi.Npc -> WorldCommand.PerformAction(action, targetId = inspector.targetId)
        is WorldInspectorUi.Resource -> WorldCommand.PerformAction(action, targetId = inspector.targetId)
        is WorldInspectorUi.Tree -> WorldCommand.PerformAction(action, targetId = inspector.targetId)
        is WorldInspectorUi.Structure -> if (action == ActionId.ENTER_STRUCTURE) {
            WorldCommand.EnterStructure(inspector.targetId)
        } else {
            WorldCommand.PerformAction(action, targetId = inspector.targetId)
        }
        is WorldInspectorUi.Tile -> WorldCommand.PerformAction(action, targetX = inspector.tile.x, targetY = inspector.tile.y)
    }
}

private fun projectPet(
    state: WorldState,
    labels: WorldUiLabels,
    speciesId: String,
    stage: String
): WorldActorUi {
    val actor = state.actor
    val isEgg = stage.equals("egg", ignoreCase = true)
    return WorldActorUi(
        id = actor.actorId,
        displayName = labels.petName,
        role = "pet",
        speciesId = speciesId,
        assetId = "pet_${speciesId.removePrefix("pet_")}_$stage",
        position = actorPosition(actor.preciseX, actor.preciseY, actor.x, actor.y),
        direction = actor.facing.toUiDirection(),
        action = if (isEgg) "idle" else actor.action.name.lowercase(),
        frameIndex = 0,
        depth = actor.preciseY.toFloat(),
        isPet = true,
        stationary = isEgg,
        currentActivity = actor.presence.name.lowercase(),
        nextNeed = labels.needName(lowestNeed(actor.needs)),
        homeStructureId = state.structures.firstOrNull { it.type == StructureType.HOME }?.id
    )
}

private fun projectNpc(
    npc: com.example.llamadroid.tama.world.core.WorldNpc,
    labels: WorldUiLabels,
    relationship: WorldRelationshipUi?
): WorldActorUi = WorldActorUi(
    id = npc.id,
    displayName = npc.name,
    role = labels.roleName(npc.role),
    speciesId = npc.role.name.lowercase(),
    assetId = npcAssetId(npc.id),
    position = actorPosition(npc.preciseX, npc.preciseY, npc.x, npc.y),
    direction = npc.facing.toUiDirection(),
    action = npc.currentAction.name.lowercase(),
    frameIndex = 0,
    depth = npc.preciseY.toFloat(),
    isPet = false,
    stationary = npc.stationary,
    friendship = relationship?.friendship,
    currentActivity = npc.scheduleState,
    nextNeed = labels.needName(lowestNeed(npc.needs)),
    homeStructureId = npc.homeStructureId
)

private fun projectStructure(
    structure: com.example.llamadroid.tama.world.core.WorldStructure,
    labels: WorldUiLabels
): WorldStructureUi {
    return WorldStructureUi(
        id = structure.id,
        displayName = labels.structureName(structure.type),
        type = structure.type.toUiStructureType(),
        assetId = structureAssetId(structure.type),
        position = WorldPointUi(
            structure.x + structure.width / 2f,
            structure.y + structure.height.toFloat()
        ),
        widthTiles = structure.width.coerceAtLeast(1),
        heightTiles = structure.height.coerceAtLeast(1),
        entrance = WorldPointUi(structure.entrance.x.toFloat(), structure.entrance.y.toFloat()),
        collision = structure.collisionMask.joinToString("/") { row -> row.joinToString("") },
        collisionMask = structure.collisionMask,
        discovered = true,
        depth = (structure.y + structure.height).toFloat()
    )
}

private fun projectResource(objectValue: WorldObject, labels: WorldUiLabels, biome: Biome = Biome.MEADOW): WorldResourceUi {
    val kind = objectValue.type.toUiResourceKind()
    val state = objectValue.toUiResourceState()
    return WorldResourceUi(
        id = objectValue.id,
        displayName = labels.objectName(objectValue.type),
        kind = kind,
        state = state,
        assetId = objectAssetId(objectValue.type, state, objectValue.id, biome),
        position = WorldPointUi(objectValue.x + 0.5f, objectValue.y + 1f),
        healthPercent = if (objectValue.quantity > 0) 100 else 0,
        availableActions = availableActions(objectValue.type),
        depth = objectValue.y.toFloat() + 1f
    )
}

private fun availableActions(type: WorldObjectType): List<String> = when (type) {
    WorldObjectType.TREE -> listOf(ActionId.CHOP_TREE)
    WorldObjectType.BERRY_PATCH -> listOf(ActionId.FORAGE)
    WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT,
    WorldObjectType.MUSHROOM -> listOf(ActionId.GATHER_HERB, ActionId.HARVEST_WILD_PLANT)
    WorldObjectType.STONE -> listOf(ActionId.GATHER_STONE)
    WorldObjectType.FALLEN_LOG -> listOf(ActionId.GATHER_WOOD)
    WorldObjectType.SHELL, WorldObjectType.DROPPED_ITEM -> listOf(ActionId.PICK_UP)
    WorldObjectType.BUSH, WorldObjectType.FLOWER_MEADOW, WorldObjectType.REED,
    WorldObjectType.CACTUS, WorldObjectType.POND, WorldObjectType.BENCH,
    WorldObjectType.MARKET_STALL, WorldObjectType.CUSTOM -> listOf(ActionId.INSPECT)
}.plus(ActionId.INSPECT).distinct().map { it.name }

private fun projectInspector(
    state: WorldState,
    map: com.example.llamadroid.tama.world.core.WorldMap,
    target: WorldInspectTarget,
    labels: WorldUiLabels,
    petSpeciesId: String,
    petStage: String,
    relationships: Map<String, WorldRelationshipUi>,
    farmTilesByCoordinate: Map<Pair<Int, Int>, WorldFarmTileUi>
): WorldInspectorUi? = when (target) {
    is WorldInspectTarget.Actor -> {
        if (target.id == state.actor.actorId) {
            val pet = projectPet(state, labels, petSpeciesId, petStage)
            WorldInspectorUi.Pet(
                targetId = pet.id,
                title = pet.displayName,
                subtitle = labels.presenceName(state.actor.presence),
                actor = pet,
                fields = listOf(
                    field(labels.inspectorFieldName("goal"), labels.goalName(state.actor.goal), true),
                    field(labels.inspectorFieldName("action"), labels.actionName(state.actor.action)),
                    field(labels.inspectorFieldName("hunger"), state.actor.needs.hunger.percent().toString()),
                    field(labels.inspectorFieldName("hydration"), state.actor.needs.hydration.percent().toString()),
                    field(labels.inspectorFieldName("energy"), state.actor.needs.energy.percent().toString()),
                    field(labels.inspectorFieldName("health"), state.actor.needs.health.percent().toString()),
                    field(labels.inspectorFieldName("hygiene"), state.actor.needs.hygiene.percent().toString()),
                    field(labels.inspectorFieldName("happiness"), state.actor.needs.happiness.percent().toString()),
                    field(labels.inspectorFieldName("social"), state.actor.needs.social.percent().toString()),
                    field(labels.inspectorFieldName("curiosity"), state.actor.needs.curiosity.percent().toString()),
                    field(labels.inspectorFieldName("location"), "${state.actor.x}, ${state.actor.y}")
                ),
                actions = listOf(
                    inspectorAction(ActionId.REST, labels),
                    inspectorAction(ActionId.INSPECT, labels, enabled = state.actor.actionState != ActionState.RUNNING)
                )
            )
        } else {
            state.npcs.firstOrNull { it.id == target.id }?.let { npc ->
                val actor = projectNpc(npc, labels, relationships[npc.id])
                val relationship = relationships[npc.id] ?: WorldRelationshipUi(0, 0, 0, "", "", 0, 0)
                WorldInspectorUi.Npc(
                    targetId = npc.id,
                    title = npc.name,
                    subtitle = labels.roleName(npc.role),
                    actor = actor,
                    relationship = relationship,
                    fields = listOf(
                        field(labels.inspectorFieldName("role"), labels.roleName(npc.role), true),
                        field(labels.inspectorFieldName("schedule"), npc.scheduleState),
                        field(labels.inspectorFieldName("goal"), labels.goalName(npc.currentGoal)),
                        field(labels.inspectorFieldName("action"), labels.actionName(npc.currentAction)),
                        field(labels.inspectorFieldName("friendship"), relationship.friendship.toString()),
                        field(labels.inspectorFieldName("trust"), relationship.trust.toString()),
                        field(labels.inspectorFieldName("location"), "${npc.x}, ${npc.y}")
                    ),
                    actions = listOf(
                        inspectorAction(ActionId.GREET, labels),
                        inspectorAction(ActionId.TALK, labels),
                        inspectorAction(ActionId.FOLLOW_NPC, labels)
                    )
                )
            }
        }
    }
    is WorldInspectTarget.Resource -> {
        val resource = state.objects.firstOrNull { it.id == target.id }
            ?: state.explored.asSequence()
                .mapNotNull { coordinate -> WorldGenerator.objectAt(state, coordinate.x, coordinate.y) }
                .firstOrNull { it.id == target.id }
        resource?.let {
            val ui = projectResource(it, labels, map.biomeAt(it.x, it.y))
            WorldInspectorUi.Resource(
                targetId = ui.id,
                title = ui.displayName,
                subtitle = labels.stateName(it.state),
                resource = ui,
                fields = listOf(
                    field(labels.inspectorFieldName("type"), labels.objectName(it.type), true),
                    field(labels.inspectorFieldName("state"), labels.stateName(it.state)),
                    field(labels.inspectorFieldName("quantity"), it.quantity.toString()),
                    field(labels.inspectorFieldName("location"), "${it.x}, ${it.y}")
                ),
                actions = ui.availableActions.map { id ->
                    val action = ActionId.valueOf(id)
                    WorldInspectorAction(
                        id = id,
                        label = labels.actionName(action),
                        enabled = it.quantity > 0 || action == ActionId.INSPECT
                    )
                }
            )
        }
    }
    is WorldInspectTarget.Structure -> {
        state.structures.firstOrNull { it.id == target.id }?.let { structure ->
            val ui = projectStructure(structure, labels)
            WorldInspectorUi.Structure(
                targetId = ui.id,
                title = ui.displayName,
                subtitle = labels.structureName(structure.type),
                structure = ui,
                fields = listOf(
                    field(labels.inspectorFieldName("type"), labels.structureName(structure.type), true),
                    field(labels.inspectorFieldName("entrance"), "${structure.entrance.x}, ${structure.entrance.y}"),
                    field(labels.inspectorFieldName("size"), "${structure.width} × ${structure.height}"),
                    field(labels.inspectorFieldName("owner"), structure.ownerNpcId.orEmpty().ifBlank { "—" })
                ),
                actions = listOf(
                    WorldInspectorAction(ActionId.ENTER_STRUCTURE.name, labels.actionName(ActionId.ENTER_STRUCTURE)),
                    WorldInspectorAction(ActionId.INSPECT.name, labels.actionName(ActionId.INSPECT))
                )
            )
        }
    }
    is WorldInspectTarget.Tile -> {
        if (!state.contains(WorldCoordinate(target.x, target.y))) null else {
            val tile = map.tileAt(target.x, target.y)
            val farmTile = farmTilesByCoordinate[target.x to target.y]
            val ui = WorldTileUi(
                x = target.x,
                y = target.y,
                terrainId = farmTile?.soil?.terrainId ?: terrainId(tile.kind),
                biome = tile.biome.toUiBiome(),
                known = state.explored.any { it.x == target.x && it.y == target.y },
                movementCost = tile.movementCost.toFloat().coerceAtLeast(1f),
                water = tile.kind.isWater(),
                crop = farmTile?.hasCrop == true,
                structure = state.structures.any { it.contains(WorldCoordinate(target.x, target.y)) },
                goalMarker = state.actor.destination?.let { it.x == target.x && it.y == target.y } == true,
                farmTile = farmTile
            )
            WorldInspectorUi.Tile(
                targetId = ui.x.toString() + ":" + ui.y,
                title = labels.biomeName(tile.biome),
                subtitle = labels.stateName(tile.kind.name),
                tile = ui,
                fields = listOf(
                    field(labels.inspectorFieldName("coordinates"), "${target.x}, ${target.y}", true),
                    field(labels.inspectorFieldName("terrain"), labels.stateName(tile.kind.name)),
                    field(labels.inspectorFieldName("biome"), labels.biomeName(tile.biome)),
                    field(labels.inspectorFieldName("walkable"), labels.stateName(tile.walkable.toString())),
                    field(labels.inspectorFieldName("movement_cost"), tile.movementCost.toString())
                ) + farmTileFields(farmTile, labels),
                actions = listOf(
                    WorldInspectorAction(ActionId.INSPECT.name, labels.actionName(ActionId.INSPECT)),
                    WorldInspectorAction(ActionId.EXPLORE.name, labels.actionName(ActionId.EXPLORE))
                )
            )
        }
    }
}

private fun projectMiniMap(
    state: WorldState,
    map: com.example.llamadroid.tama.world.core.WorldMap,
    explored: Set<Pair<Int, Int>>,
    selected: WorldInspectTarget?
): WorldMiniMapUi {
    val span = minimapSpan(state.width, state.height)
    val cells = LinkedHashMap<Long, Pair<Int, Int>>(MAX_MINIMAP_CELLS)
    explored.forEach { (x, y) ->
        if (x in 0 until state.width && y in 0 until state.height) {
            val cellX = x / span
            val cellY = y / span
            val key = (cellY.toLong() shl 32) xor (cellX.toLong() and 0xffffffffL)
            if (cells.size < MAX_MINIMAP_CELLS) cells.putIfAbsent(key, x to y)
        }
    }
    val tiles = cells.values.map { (knownX, knownY) ->
        val x = knownX / span * span
        val y = knownY / span * span
        val tile = map.tileAt(knownX, knownY)
        WorldMiniMapTileUi(x, y, tile.biome.toUiBiome(), terrainId(tile.kind), known = true, spanTiles = span)
    }
    val markers = projectMiniMapMarkers(state, explored, selected)
    return WorldMiniMapUi(state.width, state.height, tiles, markers, knownOnly = true)
}

private fun farmTileFields(
    farmTile: WorldFarmTileUi?,
    labels: WorldUiLabels
): List<WorldInspectorField> = farmTile?.let { tile ->
    buildList {
        add(field(labels.inspectorFieldName("soil"), labels.stateName(tile.soil.name)))
        add(field(
            labels.inspectorFieldName("crop"),
            tile.cropId?.let(labels.stateName) ?: labels.stateName("empty")
        ))
        if (tile.cropStage != WorldFarmCropStage.NONE) {
            val stage = if (tile.cropStage == WorldFarmCropStage.DECAYED) {
                labels.stateName("decayed")
            } else {
                "${labels.stateName("stage")} ${tile.cropStage.stage}"
            }
            add(field(labels.inspectorFieldName("crop_stage"), stage))
        }
        add(field(labels.inspectorFieldName("owner"), farmOwnerLabel(tile.ownership, labels)))
        if (tile.ownership is WorldFarmOwnershipUi.Patch) {
            add(field(labels.inspectorFieldName("ready"), labels.stateName(tile.isReady.toString())))
            add(field(labels.inspectorFieldName("dead"), labels.stateName(tile.isDead.toString())))
            tile.plantedAtTick?.let { tick ->
                add(field(labels.inspectorFieldName("planted_at"), labels.timeName(tick)))
            }
            tile.wateredAtTick?.let { tick ->
                add(field(labels.inspectorFieldName("watered_at"), labels.timeName(tick)))
            }
        }
    }
} ?: emptyList()

private fun farmOwnerLabel(owner: WorldFarmOwnershipUi, labels: WorldUiLabels): String = when (owner) {
    is WorldFarmOwnershipUi.Player -> labels.stateName("player")
    is WorldFarmOwnershipUi.Patch -> labels.stateName("patch")
    WorldFarmOwnershipUi.Unassigned -> labels.stateName("unassigned")
}

private fun projectMiniMapMarkers(
    state: WorldState,
    explored: Set<Pair<Int, Int>>,
    selected: WorldInspectTarget?
): List<WorldMiniMapMarkerUi> = buildList {
        if ((state.actor.coordinate.x to state.actor.coordinate.y) in explored) {
            add(WorldMiniMapMarkerUi(state.actor.actorId, state.actor.x, state.actor.y, "pet", selected is WorldInspectTarget.Actor && selected.id == state.actor.actorId))
        }
        state.structures.filter { structureIsKnown(state, it) }.forEach { structure ->
            val icon = when (structure.type) {
                StructureType.DUNGEON_A, StructureType.DUNGEON_B -> "map_dungeon"
                StructureType.FARM_BARN -> "map_farm"
                StructureType.NPC_HOME_A, StructureType.NPC_HOME_B, StructureType.NPC_HOME_C -> "map_home"
                StructureType.MARKET_STALL -> "map_shop"
                else -> "map_${structure.type.name.lowercase()}"
            }
            add(WorldMiniMapMarkerUi(structure.id, structure.entrance.x, structure.entrance.y,
                "structure", selected?.id == structure.id, icon))
        }
        state.npcs.filter { (it.x to it.y) in explored }.forEach { npc ->
            add(WorldMiniMapMarkerUi(npc.id, npc.x, npc.y, "npc", selected?.id == npc.id))
        }
    }

private const val MAX_MINIMAP_CELLS = 4_096

private fun minimapSpan(width: Int, height: Int): Int = max(
    1,
    ceil(sqrt(width.toDouble() * height.toDouble() / MAX_MINIMAP_CELLS)).toInt()
)

private fun visibleBounds(
    state: WorldState,
    camera: WorldCameraUi,
    viewport: WorldUiViewport
): Bounds {
    val left = floor(camera.centerX - viewport.widthTiles / 2f).toInt() - viewport.marginTiles
    val right = ceil(camera.centerX + viewport.widthTiles / 2f).toInt() + viewport.marginTiles
    val top = floor(camera.centerY - viewport.heightTiles / 2f).toInt() - viewport.marginTiles
    val bottom = ceil(camera.centerY + viewport.heightTiles / 2f).toInt() + viewport.marginTiles
    return Bounds(
        left = max(0, left),
        right = min(state.width - 1, right),
        top = max(0, top),
        bottom = min(state.height - 1, bottom)
    )
}

private data class Bounds(val left: Int, val right: Int, val top: Int, val bottom: Int)

private fun intersects(x: Int, y: Int, width: Int, height: Int, bounds: Bounds): Boolean =
    x <= bounds.right && x + width - 1 >= bounds.left && y <= bounds.bottom && y + height - 1 >= bounds.top

private fun structureIsKnown(state: WorldState, structure: com.example.llamadroid.tama.world.core.WorldStructure): Boolean =
    state.explored.any { structure.contains(it) || it == structure.entrance }

private fun goalReason(state: WorldState, labels: WorldUiLabels): String {
    val lowest = lowestNeed(state.actor.needs)
    return "${labels.needName(lowest)} ${state.actor.needs.valueOf(lowest).percent()}%"
}

private fun nextNeed(state: WorldState, labels: WorldUiLabels): String =
    labels.needName(lowestNeed(state.actor.needs))

private fun lowestNeed(needs: com.example.llamadroid.tama.world.core.NeedsProjection): NeedType =
    NeedType.entries.minByOrNull { needs.valueOf(it) } ?: NeedType.HUNGER

private fun field(label: String, value: String, emphasize: Boolean = false) =
    WorldInspectorField(label, value, emphasize)

private fun inspectorAction(action: ActionId, labels: WorldUiLabels, enabled: Boolean = true) =
    WorldInspectorAction(action.name, labels.actionName(action), enabled)

private fun actorPosition(preciseX: Double, preciseY: Double, x: Int, y: Int): WorldPointUi = WorldPointUi(
    x = (if (preciseX.isFinite()) preciseX.toFloat() else x.toFloat()) + 0.5f,
    y = (if (preciseY.isFinite()) preciseY.toFloat() else y.toFloat()) + 1f
)

private fun Float.percent(): Int = roundToInt().coerceIn(0, 100)

private fun Double.percent(): Int = roundToInt().coerceIn(0, 100)

private fun TileStatus.toWorldFarmSoil(): WorldFarmSoilState = when (this) {
    TileStatus.SOIL -> WorldFarmSoilState.UNPLOWED
    TileStatus.FARMLAND -> WorldFarmSoilState.DRY
    TileStatus.WET_FARMLAND -> WorldFarmSoilState.WET
}

private fun String.toWorldFarmSoil(): WorldFarmSoilState = when (lowercase()) {
    "wet_farmland", "wet", "watered" -> WorldFarmSoilState.WET
    "farmland", "dry", "tilled" -> WorldFarmSoilState.DRY
    else -> WorldFarmSoilState.UNPLOWED
}

private fun com.example.llamadroid.tama.data.PlantedCrop?.toWorldFarmCropStage(): WorldFarmCropStage {
    if (this == null) return WorldFarmCropStage.NONE
    if (isDecayed) return WorldFarmCropStage.DECAYED
    return when (stage.coerceIn(0, 3)) {
        0 -> WorldFarmCropStage.STAGE_0
        1 -> WorldFarmCropStage.STAGE_1
        2 -> WorldFarmCropStage.STAGE_2
        else -> WorldFarmCropStage.STAGE_3
    }
}

private fun Biome.toUiBiome(): WorldBiome = WorldBiome.entries.first { it.id == name.lowercase() }

private fun Direction.toUiDirection(): WorldDirection = when (this) {
    Direction.NORTH -> WorldDirection.NORTH
    Direction.NORTH_EAST -> WorldDirection.NORTH_EAST
    Direction.EAST -> WorldDirection.EAST
    Direction.SOUTH_EAST -> WorldDirection.SOUTH_EAST
    Direction.SOUTH -> WorldDirection.SOUTH
    Direction.SOUTH_WEST -> WorldDirection.SOUTH_WEST
    Direction.WEST -> WorldDirection.WEST
    Direction.NORTH_WEST -> WorldDirection.NORTH_WEST
    Direction.NONE -> WorldDirection.SOUTH
}

private fun StructureType.toUiStructureType(): WorldStructureType = when (this) {
    StructureType.HOME -> WorldStructureType.HOME
    StructureType.SHOP -> WorldStructureType.SHOP
    StructureType.PARK -> WorldStructureType.PARK
    StructureType.HOSPITAL -> WorldStructureType.HOSPITAL
    StructureType.ARCADE -> WorldStructureType.ARCADE
    StructureType.ALCHEMIST -> WorldStructureType.ALCHEMIST
    StructureType.SCHOOL -> WorldStructureType.SCHOOL
    StructureType.WORKPLACE -> WorldStructureType.WORKPLACE
    StructureType.FARM -> WorldStructureType.FARM
    StructureType.BOXING_RING -> WorldStructureType.BOXING_GYM
    StructureType.DUNGEON_A, StructureType.DUNGEON_B -> WorldStructureType.DUNGEON_ENTRANCE
    StructureType.ADVENTURE_GATE -> WorldStructureType.ADVENTURE_GATE
    StructureType.FARM_BARN -> WorldStructureType.FARM_BARN
    StructureType.NPC_HOME_A -> WorldStructureType.NPC_HOME_A
    StructureType.NPC_HOME_B -> WorldStructureType.NPC_HOME_B
    StructureType.NPC_HOME_C -> WorldStructureType.NPC_HOME_C
    StructureType.MARKET_STALL -> WorldStructureType.MARKET_STALL
}

private fun WorldObjectType.toUiResourceKind(): WorldResourceKind = when (this) {
    WorldObjectType.TREE, WorldObjectType.FALLEN_LOG -> WorldResourceKind.TREE
    WorldObjectType.BERRY_PATCH, WorldObjectType.BUSH -> WorldResourceKind.BERRY_BUSH
    WorldObjectType.STONE, WorldObjectType.CACTUS -> WorldResourceKind.ROCK
    WorldObjectType.HERB_PATCH, WorldObjectType.GLOWING_PLANT -> WorldResourceKind.HERB
    WorldObjectType.FLOWER_MEADOW -> WorldResourceKind.FLOWER
    WorldObjectType.SHELL -> WorldResourceKind.SHELL
    WorldObjectType.MUSHROOM, WorldObjectType.REED -> WorldResourceKind.MUSHROOM
    else -> WorldResourceKind.OTHER
}

private fun WorldObject.toUiResourceState(): WorldResourceState {
    if (quantity <= 0 || state.equals("consumed", ignoreCase = true)) return WorldResourceState.DEPLETED
    return when (state.lowercase()) {
        "empty" -> WorldResourceState.EMPTY
        "regrowing" -> WorldResourceState.REGROWING
        "seed" -> WorldResourceState.SEED
        "sprout" -> WorldResourceState.SPROUT
        "young" -> WorldResourceState.YOUNG
        "mature" -> WorldResourceState.MATURE
        "harvested" -> WorldResourceState.HARVESTED
        else -> WorldResourceState.FULL
    }
}

private fun TileKind.isWater(): Boolean = this == TileKind.SHALLOW_WATER || this == TileKind.DEEP_WATER

private fun terrainId(kind: TileKind): String = when (kind) {
    TileKind.GRASS, TileKind.FLOWER_MEADOW -> "terrain_grass_meadow"
    TileKind.FOREST_FLOOR -> "terrain_grass_forest"
    TileKind.WETLAND_MUD -> "terrain_mud"
    TileKind.SHALLOW_WATER -> "terrain_shallow_water"
    TileKind.DEEP_WATER -> "terrain_deep_water"
    TileKind.SAND, TileKind.COAST_SAND -> "terrain_sand"
    TileKind.ROCK -> "terrain_rock"
    TileKind.SNOW -> "terrain_snow"
    TileKind.ICE -> "terrain_ice"
    TileKind.MYSTIC_GRASS -> "terrain_grass_mystic"
    TileKind.ROAD -> "terrain_path"
    TileKind.STRUCTURE_FLOOR -> "terrain_dirt"
    TileKind.FARM_SOIL -> "terrain_farmland_dry"
}

private fun structureAssetId(type: StructureType): String =
    WorldBuildingLayouts.assetIds[type.name] ?: "structure_${type.name.lowercase()}"

internal fun objectAssetId(type: WorldObjectType, state: WorldResourceState, objectId: String? = null, biome: Biome = Biome.MEADOW): String {
    val id = objectId.orEmpty().lowercase()
    if (id == "settlement_well" || id.endsWith("_well")) return "well"
    if (id.startsWith("park_bench") || id == "bench") return "park_bench"
    if (id.startsWith("signpost")) return "signpost"
    if (id.startsWith("street_lamp") || id.startsWith("streetlamp")) return "street_lamp"
    if (id.startsWith("wood_bridge")) return "wood_bridge"
    val variant = Math.floorMod(id.hashCode(), 3)
    return when (type) {
    WorldObjectType.TREE -> when (state) {
        WorldResourceState.YOUNG -> "tree_oak_young"
        WorldResourceState.REGROWING -> "tree_oak_regrowing"
        WorldResourceState.DEPLETED -> "tree_oak_stump"
        else -> when (biome) {
            Biome.TUNDRA -> "snow_pine"
            Biome.MYSTIC_GROVE -> "mystic_tree"
            Biome.FOREST -> listOf("tree_oak_mature", "tree_pine", "tree_birch")[variant]
            else -> "tree_oak_mature"
        }
    }
    WorldObjectType.FALLEN_LOG -> if (biome == Biome.COAST) "driftwood" else "fallen_log"
    WorldObjectType.BUSH, WorldObjectType.BERRY_PATCH -> when (state) {
        WorldResourceState.EMPTY, WorldResourceState.DEPLETED -> "berry_bush_empty"
        WorldResourceState.REGROWING -> "berry_bush_regrowing"
        else -> "berry_bush_full"
    }
    WorldObjectType.FLOWER_MEADOW -> listOf("flower_white", "flower_yellow", "flower_blue")[variant]
    WorldObjectType.MUSHROOM -> when (biome) {
        Biome.WETLANDS -> "wetland_mushroom"
        Biome.MYSTIC_GROVE -> "mystic_mushroom"
        else -> if (variant == 0) "mushroom_red" else "mushroom_brown"
    }
    WorldObjectType.HERB_PATCH -> if (biome == Biome.MYSTIC_GROVE) "wisp_plant" else "herb_common"
    WorldObjectType.REED -> "reeds"
    WorldObjectType.CACTUS -> "desert_cactus"
    WorldObjectType.STONE -> if (state == WorldResourceState.DEPLETED) {
        if (biome == Biome.HIGHLANDS && variant == 0) "mineral_node_depleted" else "rock_node_depleted"
    } else when (biome) {
        Biome.DESERT -> "desert_rock"
        Biome.TUNDRA -> "snow_rock"
        Biome.COAST -> "shore_rock"
        Biome.MYSTIC_GROVE -> "mystic_stone"
        Biome.HIGHLANDS -> if (variant == 0) "mineral_node_common" else "rock_node"
        else -> "rock_node"
    }
    WorldObjectType.SHELL -> "shell"
    WorldObjectType.GLOWING_PLANT -> "glow_flower"
    WorldObjectType.POND -> "lily_pad"
    WorldObjectType.DROPPED_ITEM -> "small_rock"
    WorldObjectType.BENCH -> "park_bench"
    WorldObjectType.MARKET_STALL -> "market_stall"
        WorldObjectType.CUSTOM -> "grass_tuft"
    }
}

internal fun npcAssetId(id: String): String = when (id) {
    // The core keeps the canonical NPC identity arcade_host; the authored
    // world sheet is named after the stationary machine it represents.
    "arcade_host" -> "npc_arcade_machine"
    else -> "npc_$id"
}

private fun humanize(value: Enum<*>): String = humanize(value.name)

private fun humanize(value: String): String = value
    .lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
