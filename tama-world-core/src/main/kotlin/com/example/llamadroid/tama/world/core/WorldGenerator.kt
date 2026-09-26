package com.example.llamadroid.tama.world.core

import java.lang.Math.atan2
import java.util.LinkedHashSet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic, lazy terrain map. Base terrain is derived from seed,
 * generator version, coordinates, and salts; WorldState only carries sparse
 * mutable deviations.
 */
class WorldMap internal constructor(private val state: WorldState) {
    fun tileAt(x: Int, y: Int): GeneratedTile = WorldGenerator.tileAt(state, x, y)

    fun tileAt(coordinate: WorldCoordinate): GeneratedTile = tileAt(coordinate.x, coordinate.y)

    fun biomeAt(x: Int, y: Int): Biome = WorldGenerator.biomeAt(state, x, y)

    fun objectAt(x: Int, y: Int): WorldObject? = WorldGenerator.objectAt(state, x, y)

    fun objectsInChunk(chunkX: Int, chunkY: Int): List<WorldObject> =
        WorldGenerator.objectsInChunk(state, chunkX, chunkY)

    fun chunkAt(chunkX: Int, chunkY: Int): GeneratedChunk = WorldGenerator.chunkAt(state, chunkX, chunkY)

    fun isWalkable(x: Int, y: Int): Boolean = tileAt(x, y).walkable
}

object WorldGenerator {
    val supportedGeneratorVersions: Set<Int> = setOf(CURRENT_GENERATOR_VERSION)

    private const val BIOME_SALT = 11L
    private const val TERRAIN_SALT = 17L
    private const val OBJECT_SALT = 23L
    private const val STRUCTURE_SALT = 29L
    private const val SETTLEMENT_SALT = 31L
    private const val RESOURCE_SALT = 37L

    private val biomeOrder = listOf(
        Biome.MEADOW,
        Biome.FOREST,
        Biome.WETLANDS,
        Biome.DESERT,
        Biome.HIGHLANDS,
        Biome.TUNDRA,
        Biome.COAST,
        Biome.MYSTIC_GROVE
    )

    /** Creates a complete world shell. Generated base tiles remain lazy. */
    fun generate(
        seed: Long,
        width: Int = STANDARD_WORLD_WIDTH,
        height: Int = STANDARD_WORLD_HEIGHT,
        generatorVersion: Int = CURRENT_GENERATOR_VERSION,
        structureLayouts: Map<StructureType, StructureLayout> = emptyMap()
    ): WorldState {
        require(width > 0 && height > 0) { "World dimensions must be positive" }
        require(generatorVersion in supportedGeneratorVersions) {
            "Unsupported generatorVersion=$generatorVersion; supported=$supportedGeneratorVersions"
        }

        val structures = createStructures(seed, width, height, structureLayouts)
        val home = structures.first { it.type == StructureType.HOME }
        val farmPlots = createFarmPlots(width, height, structures.first { it.type == StructureType.FARM }, structures)
        val roads = createRoads(width, height, home.entrance, structures, farmPlots.map { WorldCoordinate(it.x, it.y) })
        val actorCoordinate = home.entrance
        val actor = WorldActor(
            actorId = "pet",
            x = actorCoordinate.x,
            y = actorCoordinate.y,
            preciseX = actorCoordinate.x.toDouble(),
            preciseY = actorCoordinate.y.toDouble(),
            presence = PresenceMode.HOME,
            structureId = home.id
        )
        val explored = exploreAround(width, height, actorCoordinate, radius = 8)
        val knownPlaces = structures.filter { it.entrance in explored }.map {
            KnownPlace(id = it.id, kind = it.type.name, x = it.entrance.x, y = it.entrance.y)
        }
        val initialState = WorldState(
            seed = seed,
            generatorVersion = generatorVersion,
            width = width,
            height = height,
            actor = actor,
            structures = structures,
            knownPlaces = knownPlaces,
            explored = explored,
            roads = (roads + farmPlots.map { WorldCoordinate(it.x, it.y) }).distinct(),
            farmPlots = farmPlots,
            worldId = "world-$seed"
        )
        val decorated = initialState.copy(objects = settlementProps(initialState))
        return decorated.copy(npcs = createNpcs(decorated))
    }

    fun map(state: WorldState): WorldMap {
        require(state.generatorVersion in supportedGeneratorVersions) {
            "Unsupported generatorVersion=${state.generatorVersion}; supported=$supportedGeneratorVersions"
        }
        return WorldMap(state)
    }

    fun tileAt(state: WorldState, x: Int, y: Int): GeneratedTile {
        require(state.generatorVersion in supportedGeneratorVersions) {
            "Unsupported generatorVersion=${state.generatorVersion}; supported=$supportedGeneratorVersions"
        }
        val coordinate = WorldCoordinate(x, y)
        if (!state.contains(coordinate)) {
            return GeneratedTile(coordinate, Biome.MEADOW, TileKind.ROCK, walkable = false, movementCost = Int.MAX_VALUE)
        }

        val spatial = WorldSpatialIndex.of(state)
        val structure = spatial.structure(x, y)
        val entrance = spatial.isEntrance(x, y)
        if (structure != null && !entrance) {
            return GeneratedTile(
                coordinate,
                biomeAt(state, x, y),
                TileKind.STRUCTURE_FLOOR,
                walkable = !structure.blocks(coordinate),
                movementCost = if (structure.blocks(coordinate)) Int.MAX_VALUE else 1
            )
        }

        val changedTile = state.deltas.asReversed().firstOrNull {
            it.kind == WorldDeltaKind.TILE && it.x == x && it.y == y
        }
        val changedKind = changedTile?.value?.let { value ->
            runCatching { TileKind.valueOf(value) }.getOrNull()
        }
        val baseBiome = biomeAt(state, x, y)
        val baseKind = changedKind ?: if (spatial.isRoad(x, y)) {
            TileKind.ROAD
        } else {
            baseTileKind(state, x, y, baseBiome)
        }
        val groundWalkable = baseKind.walkableByDefault() || entrance || baseKind == TileKind.ROAD
        // Procedural resources already exclude roads. Persisted objects can still
        // obstruct a route, so road terrain must not bypass their collision.
        val blockingObject = groundWalkable && !entrance &&
            objectAt(state, x, y)?.let { it.quantity > 0 && it.type in SOLID_OBJECTS } == true
        val walkable = groundWalkable && !blockingObject
        return GeneratedTile(
            coordinate = coordinate,
            biome = baseBiome,
            kind = baseKind,
            walkable = walkable,
            movementCost = baseKind.movementCost()
        )
    }

    fun biomeAt(state: WorldState, x: Int, y: Int): Biome =
        biomeAt(state.seed, state.width, state.height, x, y)

    fun biomeAt(seed: Long, width: Int, height: Int, x: Int, y: Int): Biome {
        if (x !in 0 until width || y !in 0 until height) return Biome.MEADOW
        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        val settlementRadius = max(2.0, min(width, height) * 0.08)
        if (distance <= settlementRadius) return Biome.MEADOW

        // Eight deterministic climate sectors guarantee representation even
        // in compact training worlds while a coordinate noise term makes the
        // borders irregular instead of producing a rigid radial diagram.
        val angle = atan2(dy, dx)
        val normalized = (angle + PI) / (2.0 * PI)
        val sectorNoise = DeterministicRandom.signedUnit(seed, x.toLong() / 8L, y.toLong() / 8L, BIOME_SALT) * 0.20
        val sector = floor(((normalized + sectorNoise / 8.0 + 1.0) % 1.0) * biomeOrder.size)
            .toInt()
            .coerceIn(0, biomeOrder.lastIndex)
        return biomeOrder[sector]
    }

    fun chunkAt(state: WorldState, chunkX: Int, chunkY: Int): GeneratedChunk {
        require(chunkX in 0 until state.chunkCountX) { "chunkX outside world" }
        require(chunkY in 0 until state.chunkCountY) { "chunkY outside world" }
        val startX = chunkX * CHUNK_SIZE_TILES
        val startY = chunkY * CHUNK_SIZE_TILES
        val tiles = buildList(CHUNK_SIZE_TILES * CHUNK_SIZE_TILES) {
            for (y in startY until min(startY + CHUNK_SIZE_TILES, state.height)) {
                for (x in startX until min(startX + CHUNK_SIZE_TILES, state.width)) {
                    add(tileAt(state, x, y))
                }
            }
        }
        return GeneratedChunk(WorldChunkCoordinate(chunkX, chunkY), tiles)
    }

    fun objectAt(state: WorldState, x: Int, y: Int): WorldObject? {
        if (!state.contains(WorldCoordinate(x, y))) return null
        val custom = state.objects.asReversed().firstOrNull { it.x == x && it.y == y }
        return resourceState(state, custom ?: baseObjectAt(state, x, y) ?: return null)
    }

    fun objectsInChunk(state: WorldState, chunkX: Int, chunkY: Int): List<WorldObject> {
        require(chunkX in 0 until state.chunkCountX) { "chunkX outside world" }
        require(chunkY in 0 until state.chunkCountY) { "chunkY outside world" }
        val startX = chunkX * CHUNK_SIZE_TILES
        val startY = chunkY * CHUNK_SIZE_TILES
        val result = ArrayList<WorldObject>()
        for (y in startY until min(startY + CHUNK_SIZE_TILES, state.height)) {
            for (x in startX until min(startX + CHUNK_SIZE_TILES, state.width)) {
                objectAt(state, x, y)?.let(result::add)
            }
        }
        return result
    }

    fun baseObjectAt(state: WorldState, x: Int, y: Int): WorldObject? {
        val coordinate = WorldCoordinate(x, y)
        if (!state.contains(coordinate)) return null
        val spatial = WorldSpatialIndex.of(state)
        if (spatial.isRoad(x, y) || spatial.structure(x, y) != null || state.farmPlots.any { it.x == x && it.y == y }) return null
        val roll = DeterministicRandom.unit(state.seed, x.toLong(), y.toLong(), OBJECT_SALT)
        val biome = biomeAt(state, x, y)
        val objectType = when (biome) {
            Biome.MEADOW -> when {
                roll < 0.025 -> WorldObjectType.BERRY_PATCH
                roll < 0.065 -> WorldObjectType.BUSH
                roll < 0.13 -> WorldObjectType.FLOWER_MEADOW
                else -> null
            }
            Biome.FOREST -> when {
                roll < 0.13 -> WorldObjectType.TREE
                roll < 0.16 -> WorldObjectType.FALLEN_LOG
                roll < 0.20 -> WorldObjectType.MUSHROOM
                roll < 0.23 -> WorldObjectType.BERRY_PATCH
                else -> null
            }
            Biome.WETLANDS -> when {
                roll < 0.08 -> WorldObjectType.POND
                roll < 0.20 -> WorldObjectType.REED
                roll < 0.25 -> WorldObjectType.MUSHROOM
                roll < 0.28 -> WorldObjectType.HERB_PATCH
                else -> null
            }
            Biome.DESERT -> when {
                roll < 0.08 -> WorldObjectType.CACTUS
                roll < 0.18 -> WorldObjectType.STONE
                else -> null
            }
            Biome.HIGHLANDS -> when {
                roll < 0.15 -> WorldObjectType.STONE
                roll < 0.20 -> WorldObjectType.FALLEN_LOG
                else -> null
            }
            Biome.TUNDRA -> when {
                roll < 0.10 -> WorldObjectType.TREE
                roll < 0.15 -> WorldObjectType.STONE
                else -> null
            }
            Biome.COAST -> when {
                roll < 0.12 -> WorldObjectType.SHELL
                roll < 0.16 -> WorldObjectType.FALLEN_LOG
                else -> null
            }
            Biome.MYSTIC_GROVE -> when {
                roll < 0.10 -> WorldObjectType.GLOWING_PLANT
                roll < 0.18 -> WorldObjectType.HERB_PATCH
                roll < 0.23 -> WorldObjectType.TREE
                else -> null
            }
        } ?: return null
        val id = "generated_${objectType.name.lowercase()}_${x}_$y"
        return WorldObject(id, objectType, x, y, state = "available", quantity = resourceQuantity(state, x, y))
    }

    fun worldHash(state: WorldState): Long {
        var hash = DeterministicRandom.hash(state.seed, state.generatorVersion.toLong(), state.width.toLong(), state.height.toLong())
        for (y in 0 until state.height) {
            for (x in 0 until state.width) {
                val tile = tileAt(state, x, y)
                hash = DeterministicRandom.hash(hash, x.toLong(), y.toLong(), tile.biome.ordinal.toLong(), tile.kind.ordinal.toLong())
                objectAt(state, x, y)?.let { hash = DeterministicRandom.hash(hash, it.type.ordinal.toLong(), it.quantity.toLong()) }
            }
        }
        state.structures.forEach { structure ->
            hash = DeterministicRandom.hash(hash, DeterministicRandom.hashString(state.seed, structure.id), structure.x.toLong(), structure.y.toLong())
        }
        return hash
    }

    private fun baseTileKind(state: WorldState, x: Int, y: Int, biome: Biome): TileKind {
        val roll = DeterministicRandom.unit(state.seed, x.toLong(), y.toLong(), TERRAIN_SALT)
        return when (biome) {
            Biome.MEADOW -> if (roll > 0.88) TileKind.FLOWER_MEADOW else TileKind.GRASS
            Biome.FOREST -> TileKind.FOREST_FLOOR
            Biome.WETLANDS -> when {
                roll < 0.18 -> TileKind.DEEP_WATER
                roll < 0.48 -> TileKind.SHALLOW_WATER
                else -> TileKind.WETLAND_MUD
            }
            Biome.DESERT -> TileKind.SAND
            Biome.HIGHLANDS -> if (roll < 0.45) TileKind.ROCK else TileKind.GRASS
            Biome.TUNDRA -> if (roll < 0.22) TileKind.ICE else TileKind.SNOW
            Biome.COAST -> if (roll < 0.30) TileKind.SHALLOW_WATER else TileKind.COAST_SAND
            Biome.MYSTIC_GROVE -> TileKind.MYSTIC_GRASS
        }
    }

    private fun createStructures(
        seed: Long,
        width: Int,
        height: Int,
        structureLayouts: Map<StructureType, StructureLayout>
    ): List<WorldStructure> {
        data class Slot(
            val id: String,
            val type: StructureType,
            val nx: Double,
            val ny: Double,
            val owner: String?,
            val size: Int = 4
        )
        val slots = listOf(
            Slot("fixed_0_0", StructureType.HOME, .48, .46, null),
            Slot("fixed_1_0", StructureType.SHOP, .54, .46, "shop_seller"),
            Slot("fixed_2_0", StructureType.PARK, .60, .46, null, 6),
            Slot("fixed_3_0", StructureType.HOSPITAL, .66, .46, "hospital_doctor"),
            Slot("fixed_4_0", StructureType.ARCADE, .72, .46, "arcade_host"),
            Slot("fixed_0_1", StructureType.ALCHEMIST, .42, .53, "alchemist_keeper"),
            Slot("fixed_1_1", StructureType.SCHOOL, .52, .53, "school_teacher"),
            Slot("fixed_2_1", StructureType.WORKPLACE, .60, .53, "paper_pup"),
            Slot("fixed_3_1", StructureType.FARM, .70, .54, "farm_farmer", 6),
            Slot("fixed_4_1", StructureType.BOXING_RING, .65, .63, "boxing_coach"),
            Slot("fixed_0_2", StructureType.DUNGEON_A, .25, .76, "dungeon_adventurer"),
            Slot("fixed_2_2", StructureType.ADVENTURE_GATE, .50, .76, null),
            Slot("fixed_4_2", StructureType.DUNGEON_B, .75, .76, null),
            Slot("farm_barn", StructureType.FARM_BARN, .76, .54, "farm_farmer", 6),
            Slot("npc_home_a", StructureType.NPC_HOME_A, .42, .38, null),
            Slot("npc_home_b", StructureType.NPC_HOME_B, .50, .37, null),
            Slot("npc_home_c", StructureType.NPC_HOME_C, .58, .37, null),
            Slot("market_stall", StructureType.MARKET_STALL, .61, .42, "seller", 3)
        )
        val placed = ArrayList<WorldStructure>()
        slots.forEachIndexed { index, slot ->
            val jitterX = DeterministicRandom.signedUnit(seed, index.toLong(), STRUCTURE_SALT, 1L) * 0.012
            val jitterY = DeterministicRandom.signedUnit(seed, index.toLong(), STRUCTURE_SALT, 2L) * 0.012
            val layout = structureLayouts[slot.type] ?: StructureLayout(
                widthTiles = slot.size,
                heightTiles = slot.size,
                entrance = WorldCoordinate(slot.size / 2, slot.size)
            )
            val footprintWidth = min(layout.widthTiles, width.coerceAtLeast(1))
            val footprintHeight = min(layout.heightTiles, height.coerceAtLeast(1))
            val requestedX = (((slot.nx + jitterX).coerceIn(0.02, .98)) * (width - 1)).roundToInt()
                .coerceIn(0, max(0, width - footprintWidth))
            val requestedY = (((slot.ny + jitterY).coerceIn(0.02, .98)) * (height - 1)).roundToInt()
                .coerceIn(0, max(0, height - footprintHeight))
            val repaired = placement(width, height, requestedX, requestedY, footprintWidth, footprintHeight, placed)
            val x = repaired.x
            val y = repaired.y
            val collisionMask = List(footprintHeight) { localY ->
                List(footprintWidth) { localX -> layout.collision.getOrNull(localY)?.getOrNull(localX) ?: 1 }
            }
            val requestedEntrance = WorldCoordinate(
                x + layout.entrance.x.coerceIn(0, footprintWidth - 1),
                y + layout.entrance.y.coerceIn(0, footprintHeight)
            )
            val entrance = if (requestedEntrance.y in 0 until height) {
                requestedEntrance
            } else {
                WorldCoordinate(
                    x + footprintWidth / 2,
                    if (y + footprintHeight < height) y + footprintHeight else max(0, y - 1)
                )
            }
            placed += WorldStructure(
                id = slot.id,
                type = slot.type,
                x = x,
                y = y,
                width = footprintWidth,
                height = footprintHeight,
                entrance = entrance,
                collisionMask = collisionMask,
                ownerNpcId = slot.owner,
                mandatory = slot.id in LegacyLocationAliases.allIds
            )
        }
        return placed
    }

    /** Bounded local retries followed by a deterministic, finite settlement repair scan. */
    private fun placement(width: Int, height: Int, x: Int, y: Int, w: Int, h: Int,
                          placed: List<WorldStructure>): WorldCoordinate {
        fun fits(px: Int, py: Int): Boolean = px >= 1 && py >= 1 && px + w < width && py + h < height &&
            placed.none { px < it.x + it.width + 2 && px + w + 2 > it.x && py < it.y + it.height + 2 && py + h + 2 > it.y }
        for (radius in 0..8) {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != radius) continue
                if (fits(x + dx, y + dy)) return WorldCoordinate(x + dx, y + dy)
            }
        }
        var best: WorldCoordinate? = null
        var bestDistance = Int.MAX_VALUE
        for (py in 1 until height - h) for (px in 1 until width - w) {
            val distance = kotlin.math.abs(px - x) + kotlin.math.abs(py - y)
            if (distance < bestDistance && fits(px, py)) {
                best = WorldCoordinate(px, py)
                bestDistance = distance
            }
        }
        return requireNotNull(best) { "World is too small to place all required structures" }
    }

    private fun createRoads(
        width: Int,
        height: Int,
        home: WorldCoordinate,
        structures: List<WorldStructure>,
        extraDestinations: List<WorldCoordinate> = emptyList()
    ): List<WorldCoordinate> {
        // Carve one connected road tree. A road may repair rough terrain, but
        // never cuts through a building's collision cells or sealed walls.
        val blocked = BooleanArray(width * height)
        structures.forEach { structure ->
            for (y in structure.yBounds) for (x in structure.bounds) {
                blocked[y * width + x] = structure.blocks(WorldCoordinate(x, y))
            }
            blocked[structure.entrance.y * width + structure.entrance.x] = false
        }
        val predecessor = IntArray(width * height) { -1 }
        val queue = IntArray(width * height)
        val homeIndex = home.y * width + home.x
        predecessor[homeIndex] = homeIndex
        var head = 0
        var tail = 0
        queue[tail++] = homeIndex
        val offsets = listOf(WorldCoordinate(0, 1), WorldCoordinate(1, 0), WorldCoordinate(0, -1), WorldCoordinate(-1, 0))
        while (head < tail) {
            val current = queue[head++]
            val x = current % width
            val y = current / width
            offsets.forEach { delta ->
                val nx = x + delta.x
                val ny = y + delta.y
                if (nx !in 0 until width || ny !in 0 until height) return@forEach
                val next = ny * width + nx
                if (!blocked[next] && predecessor[next] == -1) {
                    predecessor[next] = current
                    queue[tail++] = next
                }
            }
        }
        val roads = LinkedHashSet<WorldCoordinate>()
        roads += home
        (structures.map { it.entrance } + extraDestinations).forEach { destination ->
            var current = destination.y * width + destination.x
            check(predecessor[current] != -1) { "Unreachable settlement destination: $destination" }
            val branch = ArrayList<WorldCoordinate>()
            while (current != homeIndex) {
                branch += WorldCoordinate(current % width, current / width)
                current = predecessor[current]
            }
            roads.addAll(branch.asReversed())
        }
        return roads.toList()
    }

    private fun createFarmPlots(width: Int, height: Int, farm: WorldStructure, structures: List<WorldStructure>): List<FarmPlotReference> {
        val desired = if (min(width, height) < 64) 9 else 27
        val candidates = LinkedHashSet<WorldCoordinate>()
        val startX = (farm.x + farm.width + 2).coerceAtMost(width - 1)
        val startY = (farm.y + 1).coerceAtMost(height - 1)
        for (row in 0 until 9) {
            for (column in 0 until 3) {
                val candidate = WorldCoordinate(
                    (startX + column).coerceIn(0, width - 1),
                    (startY + row).coerceIn(0, height - 1)
                )
                if (structures.none { it.contains(candidate) }) candidates.add(candidate)
            }
        }
        if (candidates.size < desired) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val coordinate = WorldCoordinate(x, y)
                    if (structures.none { it.contains(coordinate) }) candidates.add(coordinate)
                    if (candidates.size >= desired) break
                }
                if (candidates.size >= desired) break
            }
        }
        return candidates.take(desired).mapIndexed { index, coordinate ->
            FarmPlotReference("farm_plot_$index", coordinate.x, coordinate.y)
        }
    }

    private fun createNpcs(state: WorldState): List<WorldNpc> {
        val park = state.structures.first { it.type == StructureType.PARK }
        val reserved = LinkedHashSet<WorldCoordinate>()
        return WorldNpcCatalog.definitions.mapIndexed { index, definition ->
            val homeStructure = definition.homeStructureId?.let { id -> state.structures.firstOrNull { it.id == id } }
            val jobStructure = definition.jobStructureId?.let { id -> state.structures.firstOrNull { it.id == id } }
            val parkResident = definition.role in setOf(NpcRole.PARK_RESIDENT, NpcRole.RECYCLER, NpcRole.MARKET_SELLER)
            val homeAnchor = if (definition.stationary) jobStructure?.entrance else if (parkResident || homeStructure?.id == jobStructure?.id) {
                anchorNear(state, homeStructure?.entrance ?: park.entrance, index, reserved, radius = if (parkResident) 5 else 2)
            } else {
                homeStructure?.entrance
            }
            val jobAnchor = if (definition.role == NpcRole.MARKET_SELLER) jobStructure?.entrance else if (parkResident) {
                preferredAnchor(state, park.entrance, definition.preferredBiomes, index, reserved)
            } else {
                jobStructure?.entrance
            }
            val socialAnchor = if (definition.stationary) null else anchorNear(state, park.entrance, index + 101, reserved, radius = 3)
            val coordinate = homeAnchor ?: jobAnchor ?: park.entrance
            WorldNpc(
                id = definition.id,
                name = definition.name,
                role = definition.role,
                x = coordinate.x,
                y = coordinate.y,
                preciseX = coordinate.x.toDouble(),
                preciseY = coordinate.y.toDouble(),
                homeStructureId = definition.homeStructureId,
                jobStructureId = definition.jobStructureId,
                currentGoal = if (definition.stationary) GoalId.IDLE else GoalId.WORK,
                currentAction = if (definition.stationary) ActionId.WAIT else ActionId.WALK,
                scheduleState = if (definition.stationary) "stationary" else "at_job",
                homeAnchor = homeAnchor,
                jobAnchor = jobAnchor,
                socialAnchor = socialAnchor,
                stationary = definition.stationary,
                inventory = listOf(InventoryStack("berry", 8, InventoryKind.FOOD), InventoryStack("water", 8, InventoryKind.WATER)) +
                    when (definition.role) {
                        NpcRole.FARMER -> listOf(InventoryStack("hoe", 1, InventoryKind.TOOL),
                            InventoryStack("watering_can", 1, InventoryKind.TOOL), InventoryStack("seed_carrot", 20, InventoryKind.SEED))
                        NpcRole.ADVENTURER -> listOf(InventoryStack("axe", 1, InventoryKind.TOOL), InventoryStack("pickaxe", 1, InventoryKind.TOOL))
                        NpcRole.ALCHEMIST, NpcRole.DOCTOR -> listOf(InventoryStack("herb", 8, InventoryKind.MATERIAL))
                        else -> emptyList()
                    },
                ownFarm = if (definition.role == NpcRole.FARMER) npcFarm(state, definition.id, jobStructure ?: park) else emptyList()
            )
        }
    }

    private fun settlementProps(state: WorldState): List<WorldObject> {
        val result = ArrayList<WorldObject>()
        val used = HashSet<WorldCoordinate>()
        fun place(id: String, type: WorldObjectType, desired: WorldCoordinate) {
            val coordinate = nearestWalkable(state, desired)
            if (state.structures.any { it.entrance == coordinate } || !used.add(coordinate)) return
            result += WorldObject(id, type, coordinate.x, coordinate.y)
        }
        val park = state.structures.first { it.type == StructureType.PARK }
        place("settlement_well", WorldObjectType.CUSTOM, park.entrance + WorldCoordinate(-4, 3))
        listOf(WorldCoordinate(-3, 2), WorldCoordinate(3, 2), WorldCoordinate(-3, 5), WorldCoordinate(3, 5)).forEachIndexed { i, offset ->
            place("park_bench_$i", WorldObjectType.BENCH, park.entrance + offset)
        }
        state.structures.filter { it.mandatory }.forEachIndexed { i, building ->
            place("signpost_$i", WorldObjectType.CUSTOM, building.entrance + WorldCoordinate(2, 1))
            place("street_lamp_$i", WorldObjectType.CUSTOM, building.entrance + WorldCoordinate(-2, 1))
        }
        state.roads.filter { baseTileKind(state, it.x, it.y, biomeAt(state, it.x, it.y)) in
            setOf(TileKind.SHALLOW_WATER, TileKind.DEEP_WATER) }.forEach { tile ->
            place("wood_bridge_${tile.x}_${tile.y}", WorldObjectType.CUSTOM, tile)
        }
        return result
    }

    private fun npcFarm(state: WorldState, npcId: String, farm: WorldStructure): List<FarmPlotSnapshot> {
        val plots = ArrayList<FarmPlotSnapshot>()
        for (dy in 0 until 6) for (dx in 0 until 6) {
            val coordinate = WorldCoordinate(farm.x - 8 + dx, farm.y + dy)
            if (plots.size >= 9 || !isUsableAnchor(state, coordinate) ||
                state.farmPlots.any { it.x == coordinate.x && it.y == coordinate.y }) continue
            plots += FarmPlotSnapshot("npc_${npcId}_plot_${plots.size}", coordinate.x, coordinate.y, userOwned = false)
        }
        return plots
    }

    private fun anchorNear(
        state: WorldState,
        center: WorldCoordinate,
        index: Int,
        reserved: MutableSet<WorldCoordinate>,
        radius: Int
    ): WorldCoordinate {
        repeat(64) { attempt ->
            val distance = radius + DeterministicRandom.int(state.seed, radius + 4, index.toLong(), attempt.toLong(), SETTLEMENT_SALT)
            val angle = DeterministicRandom.unit(state.seed, index.toLong(), attempt.toLong(), SETTLEMENT_SALT + 9L) * 2.0 * PI
            val candidate = WorldCoordinate(
                (center.x + cos(angle) * distance).roundToInt(),
                (center.y + sin(angle) * distance).roundToInt()
            )
            if (isUsableAnchor(state, candidate) && reserved.add(candidate)) return candidate
        }
        val fallback = nearestWalkable(state, center)
        reserved.add(fallback)
        return fallback
    }

    private fun preferredAnchor(
        state: WorldState,
        center: WorldCoordinate,
        preferredBiomes: Set<Biome>,
        index: Int,
        reserved: MutableSet<WorldCoordinate>
    ): WorldCoordinate {
        repeat(160) { attempt ->
            val distance = 6 + DeterministicRandom.int(state.seed, 84, index.toLong(), attempt.toLong(), SETTLEMENT_SALT + 17L)
            val angle = DeterministicRandom.unit(state.seed, index.toLong(), attempt.toLong(), SETTLEMENT_SALT + 19L) * 2.0 * PI
            val candidate = WorldCoordinate(
                (center.x + cos(angle) * distance).roundToInt(),
                (center.y + sin(angle) * distance).roundToInt()
            )
            if (isUsableAnchor(state, candidate) &&
                (preferredBiomes.isEmpty() || biomeAt(state, candidate.x, candidate.y) in preferredBiomes) &&
                reserved.add(candidate)
            ) return candidate
        }
        return anchorNear(state, center, index + 503, reserved, radius = 4)
    }

    private fun isUsableAnchor(state: WorldState, coordinate: WorldCoordinate): Boolean =
        state.contains(coordinate) && tileAt(state, coordinate.x, coordinate.y).walkable &&
            state.structures.none { it.contains(coordinate) }

    private fun nearestWalkable(state: WorldState, target: WorldCoordinate): WorldCoordinate {
        val clamped = WorldCoordinate(target.x.coerceIn(0, state.width - 1), target.y.coerceIn(0, state.height - 1))
        if (tileAt(state, clamped.x, clamped.y).walkable) return clamped
        for (radius in 1..16) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != radius) continue
                    val candidate = WorldCoordinate(clamped.x + dx, clamped.y + dy)
                    if (state.contains(candidate) && tileAt(state, candidate.x, candidate.y).walkable) return candidate
                }
            }
        }
        return state.structures.first { it.type == StructureType.HOME }.entrance
    }

    private fun exploreAround(width: Int, height: Int, center: WorldCoordinate, radius: Int): List<WorldCoordinate> {
        val explored = LinkedHashSet<WorldCoordinate>()
        for (y in (center.y - radius)..(center.y + radius)) {
            for (x in (center.x - radius)..(center.x + radius)) {
                val coordinate = WorldCoordinate(x, y)
                if (x in 0 until width && y in 0 until height && center.chebyshevDistanceTo(coordinate) <= radius) {
                    explored.add(coordinate)
                }
            }
        }
        return explored.toList()
    }

    /** Resource changes are sparse; depleted art persists until deterministic regrowth. */
    internal fun resourceState(state: WorldState, objectValue: WorldObject): WorldObject {
        val delta = state.deltas.asReversed().firstOrNull {
            it.kind == WorldDeltaKind.RESOURCE &&
                (it.objectId == objectValue.id || (it.objectId == null && it.x == objectValue.x && it.y == objectValue.y))
        } ?: return objectValue
        if (delta.value != "consumed") return objectValue
        val regrowTicks = when (objectValue.type) {
            WorldObjectType.BERRY_PATCH, WorldObjectType.HERB_PATCH, WorldObjectType.MUSHROOM,
            WorldObjectType.GLOWING_PLANT, WorldObjectType.FLOWER_MEADOW -> 864_000L
            WorldObjectType.TREE -> 3 * 864_000L
            WorldObjectType.REED, WorldObjectType.CACTUS, WorldObjectType.BUSH -> 2 * 864_000L
            WorldObjectType.STONE, WorldObjectType.SHELL, WorldObjectType.FALLEN_LOG -> 7 * 864_000L
            else -> Long.MAX_VALUE
        }
        val age = (state.tick - delta.updatedAtTick).coerceAtLeast(0)
        if (regrowTicks != Long.MAX_VALUE && age >= regrowTicks) return objectValue.copy(
            state = "available", quantity = objectValue.regrowthQuantity.coerceAtLeast(1), respawnAtTick = null)
        val appearance = when (objectValue.type) {
            WorldObjectType.TREE -> "stump"
            WorldObjectType.BERRY_PATCH -> "empty"
            else -> "depleted"
        }
        return objectValue.copy(state = appearance, quantity = 0,
            respawnAtTick = if (regrowTicks == Long.MAX_VALUE) null else delta.updatedAtTick + regrowTicks)
    }

    private val SOLID_OBJECTS = setOf(WorldObjectType.TREE, WorldObjectType.STONE, WorldObjectType.CACTUS)

    private fun resourceQuantity(state: WorldState, x: Int, y: Int): Int =
        1 + DeterministicRandom.int(state.seed, 3, x.toLong(), y.toLong(), RESOURCE_SALT)

    private fun TileKind.walkableByDefault(): Boolean = when (this) {
        TileKind.DEEP_WATER,
        TileKind.ROCK,
        TileKind.STRUCTURE_FLOOR -> false
        else -> true
    }

    private fun TileKind.movementCost(): Int = when (this) {
        TileKind.SHALLOW_WATER, TileKind.WETLAND_MUD, TileKind.SNOW, TileKind.SAND -> 2
        TileKind.DEEP_WATER, TileKind.ROCK, TileKind.STRUCTURE_FLOOR -> Int.MAX_VALUE
        else -> 1
    }
}
