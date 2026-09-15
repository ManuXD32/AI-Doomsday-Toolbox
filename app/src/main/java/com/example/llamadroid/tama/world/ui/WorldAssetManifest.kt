package com.example.llamadroid.tama.world.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val WORLD_ATLAS_ENTRY_CAP = 64
private const val WORLD_ATLAS_BYTE_CAP = 24L * 1024L * 1024L

private const val WORLD_MANIFEST_SCHEMA_VERSION = 1
const val DEFAULT_WORLD_ASSET_MANIFEST_PATH = "tama/world/manifest.json"

/** Exact schema consumed from [DEFAULT_WORLD_ASSET_MANIFEST_PATH]. */
@Serializable
data class WorldAssetManifest(
    val schemaVersion: Int,
    val assets: List<WorldAssetEntry>,
    /** Layout contracts may be published before their PNG is production-ready. */
    val plannedBuildings: List<WorldPlannedBuilding> = emptyList()
)

/**
 * A planned building is a placement contract, not a drawable asset. The
 * manifest may include extra metadata such as a multi-tile entrance width;
 * [Json] is configured to ignore that metadata while preserving the exact
 * collision and footprint fields needed by the UI readiness check.
 */
@Serializable
data class WorldPlannedBuilding(
    val id: String,
    val path: String,
    val widthTiles: Int,
    val heightTiles: Int,
    val collision: List<List<Int>> = emptyList(),
    val entrance: WorldAssetPoint? = null,
    val productionReady: Boolean = false
)

@Serializable
data class WorldAssetPoint(
    val x: Float,
    val y: Float
)

/**
 * Optional authored facing transform for an equipment overlay. The transform
 * is applied around the tool's grip after the actor hand anchor has been
 * resolved. Empty maps and omitted fields intentionally preserve the original
 * untransformed overlay behavior.
 */
@Serializable
data class WorldAssetDirectionTransform(
    val quarterTurnsClockwise: Int = 0,
    val flipX: Boolean = false,
    val behindActor: Boolean = false
)

@Serializable
data class WorldAssetClip(
    val action: String,
    val direction: String = "south",
    val frames: List<Int>,
    val frameMs: Int
)

@Serializable
data class WorldAssetEntry(
    val id: String,
    val path: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val clips: List<WorldAssetClip>,
    val footAnchor: WorldAssetPoint = WorldAssetPoint(0.5f, 1f),
    val toolAnchors: Map<String, WorldAssetPoint> = emptyMap(),
    val directionTransforms: Map<String, WorldAssetDirectionTransform> = emptyMap(),
    val widthTiles: Int = 1,
    val heightTiles: Int = 1,
    val collision: List<List<Int>> = emptyList(),
    val entrance: WorldAssetPoint? = null,
    /** Optional visual footprint; collision dimensions remain [widthTiles] × [heightTiles]. */
    val visualWidthTiles: Int? = null,
    val visualHeightTiles: Int? = null,
    /** Release readiness must be explicit in the manifest; omitted flags are not ready. */
    val productionReady: Boolean = false
)

data class WorldSpriteAtlasEntry(
    val definition: WorldAssetEntry,
    val image: ImageBitmap
) {
    fun clip(action: String, direction: WorldDirection): WorldAssetClip? {
        val directionName = direction.manifestCode
        val requestedAction = worldManifestAction(action)
        return definition.clips.firstOrNull {
            it.action.equals(requestedAction, ignoreCase = true) &&
                it.direction.equals(directionName, ignoreCase = true)
        } ?: definition.clips.firstOrNull {
            it.action.equals(requestedAction, ignoreCase = true)
        } ?: definition.clips.firstOrNull {
            it.action.equals(action, ignoreCase = true) &&
                it.direction.equals(directionName, ignoreCase = true)
        } ?: definition.clips.firstOrNull { it.action.equals(action, ignoreCase = true) }
    }

}

/** Maps runtime action IDs to clip names authored by the world actor atlases. */
internal fun worldManifestAction(action: String): String = when (action.lowercase()) {
            "wait", "look", "inspect", "observe_npc", "observe_object", "wake" -> "IDLE"
            "walk", "wander", "explore", "approach", "follow", "flee", "return_home" -> "WALK"
            "run" -> "RUN"
            "rest", "sit", "relax" -> "SIT"
            "sleep" -> "SLEEP"
            "talk", "greet", "help_npc", "thank", "say_goodbye", "follow_npc", "give_item",
            "receive_item", "trade" -> "TALK"
            "play", "play_with", "use_arcade" -> "PLAY"
            "eat", "drink", "wash", "forage", "harvest_wild_plant", "chop_tree",
            "gather_wood", "gather_stone", "gather_herb", "till_soil", "plant", "water",
            "pour_water", "fertilize", "harvest_crop", "remove_dead_crop", "store_produce",
            "pick_up", "drop", "buy", "sell", "use_medicine", "use_alchemy", "work", "study",
            "train", "training", "train_boxing", "visit_hospital", "visit_alchemist",
            "enter_structure", "enter_dungeon",
            "enter_adventure_gate", "investigate", "search_area", "open", "close", "use", "activate" -> "INTERACT"
            else -> action.uppercase()
    }

internal val WorldDirection.manifestCode: String
    get() = when (this) {
        WorldDirection.NORTH, WorldDirection.NORTH_EAST, WorldDirection.NORTH_WEST -> "N"
        WorldDirection.EAST, WorldDirection.SOUTH_EAST -> "E"
        WorldDirection.SOUTH, WorldDirection.SOUTH_WEST -> "S"
        WorldDirection.WEST -> "W"
    }

internal fun WorldAssetEntry.directionTransform(direction: WorldDirection): WorldAssetDirectionTransform =
    directionTransforms.entries.firstOrNull { (key, _) ->
        key.equals(direction.manifestCode, ignoreCase = true)
    }?.value ?: WorldAssetDirectionTransform()

data class WorldSpriteAtlas(
    val entries: Map<String, WorldSpriteAtlasEntry>
) {
    fun entry(assetId: String): WorldSpriteAtlasEntry? = entries[assetId]

    companion object {
        val Empty = WorldSpriteAtlas(emptyMap())
    }
}

data class WorldAssetLoadResult(
    val manifest: WorldAssetManifest?,
    val atlas: WorldSpriteAtlas,
    val readiness: WorldAssetReadinessUi
)

/**
 * Loads manifests and PNGs away from the Compose thread. A missing or malformed
 * asset remains visible in [WorldAssetReadinessUi] so a release cannot silently
 * render an incomplete world.
 */
class WorldAssetManifestLoader(
    private val assetManager: AssetManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val catalogs = java.util.concurrent.ConcurrentHashMap<String, WorldAssetManifest>()
    private val existingPaths = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val imageSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun load(
        manifestPath: String = DEFAULT_WORLD_ASSET_MANIFEST_PATH,
        expectedAssetIds: Set<String> = WorldAssetCatalog.requiredAssetIds,
        visibleAssetIds: Set<String> = expectedAssetIds,
        allowDevelopmentFallbacks: Boolean = false,
        /** Narrow one-shot effects can skip the full inventory path scan. */
        validateFullManifest: Boolean = true
    ): WorldAssetLoadResult = withContext(ioDispatcher) {
        val manifest = catalogs[manifestPath] ?: runCatching {
            assetManager.open(manifestPath).bufferedReader().use { reader ->
                json.decodeFromString<WorldAssetManifest>(reader.readText())
            }
        }.getOrNull()?.also { catalogs[manifestPath] = it }

        if (manifest == null) {
            return@withContext WorldAssetLoadResult(
                manifest = null,
                atlas = WorldSpriteAtlas.Empty,
                readiness = WorldAssetReadinessUi(
                    manifestPath = manifestPath,
                    schemaVersion = null,
                    missingAssetIds = expectedAssetIds.sorted(),
                    error = "Manifest could not be read"
                )
            )
        }

        val invalidManifest = manifest.schemaVersion != WORLD_MANIFEST_SCHEMA_VERSION
        val definitions = manifest.assets.associateBy { it.id }
        val loaded = linkedMapOf<String, WorldSpriteAtlasEntry>()
        val missing = linkedSetOf<String>()
        val invalid = linkedSetOf<String>()
        val deferred = linkedSetOf<String>()
        invalid += manifest.assets
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val deferredLayoutIds = if (allowDevelopmentFallbacks) manifest.plannedBuildings
            .asSequence()
            .filterNot(WorldPlannedBuilding::productionReady)
            .map(WorldPlannedBuilding::id)
            .toSet()
        else emptySet()

        // Validate every manifest entry as well as the canonical runtime catalog. This
        // keeps readiness honest when the art pack grows beyond the currently visible
        // subset (for example UI markers/effects that are not loaded into the atlas yet).
        val expected = if (validateFullManifest) {
            (expectedAssetIds + definitions.keys + manifest.plannedBuildings.map { it.id }).sorted()
        } else {
            expectedAssetIds.sorted()
        }.filterNot { it in deferredLayoutIds }

        expected.forEach { assetId ->
            val definition = definitions[assetId]
            if (definition == null) {
                val fallback = if (allowDevelopmentFallbacks) {
                    WorldAssetCatalog.temporaryFallbackPath(assetId)
                } else {
                    null
                }
                if (fallback == null) missing += assetId
                return@forEach
            }
            if (!definition.productionReady && !allowDevelopmentFallbacks) {
                invalid += assetId
                return@forEach
            }
            if (!validDefinition(definition)) {
                invalid += assetId
                return@forEach
            }
            if (!assetExists(definition.path)) missing += assetId
        }

        val candidates = visibleAssetIds.filterNot { it in deferredLayoutIds }.sortedWith(
            compareBy<String> { id -> when {
                id.startsWith("pet_") -> 0
                id.startsWith("terrain_") -> 1
                definitions[id]?.path?.contains("/npcs/") == true -> 2
                id.startsWith("map_") -> 4
                else -> 3
            } }.thenBy { it }
        )
        var loadedBytes = 0L
        candidates.forEach { assetId ->
            if (loaded.size >= WORLD_ATLAS_ENTRY_CAP || loadedBytes >= WORLD_ATLAS_BYTE_CAP) {
                deferred += assetId
                return@forEach
            }
            val definition = definitions[assetId]
            if (definition == null) {
                val fallback = if (allowDevelopmentFallbacks) {
                    WorldAssetCatalog.temporaryFallbackPath(assetId)
                } else {
                    null
                }
                if (fallback == null) {
                    missing += assetId
                } else {
                    val fallbackDefinition = definitionForFallback(assetId, fallback)
                    if (estimateBytes(fallback) > WORLD_ATLAS_BYTE_CAP - loadedBytes) {
                        deferred += assetId
                    } else {
                        loadedBytes += decodeAsset(assetId, fallbackDefinition, loaded, invalid).toLong()
                    }
                }
                return@forEach
            }
            if (!definition.productionReady && !allowDevelopmentFallbacks || !validDefinition(definition)) {
                invalid += assetId
                return@forEach
            }
            if (loaded.size >= WORLD_ATLAS_ENTRY_CAP ||
                loadedBytes >= WORLD_ATLAS_BYTE_CAP ||
                estimateBytes(definition.path) > WORLD_ATLAS_BYTE_CAP - loadedBytes
            ) {
                deferred += assetId
                return@forEach
            }
            loadedBytes += decodeAsset(assetId, definition, loaded, invalid).toLong()
        }

        WorldAssetLoadResult(
            manifest = manifest,
            atlas = WorldSpriteAtlas(loaded),
            readiness = WorldAssetReadinessUi(
                manifestPath = manifestPath,
                schemaVersion = manifest.schemaVersion,
                loadedAssetIds = loaded.keys,
                missingAssetIds = missing.toList(),
                invalidAssetIds = invalid.toList() + if (invalidManifest) listOf("manifest") else emptyList(),
                deferredAssetIds = deferred.toList(),
                error = if (invalidManifest) "Unsupported manifest schema" else null
            )
        )
    }

    private fun validDefinition(definition: WorldAssetEntry): Boolean =
        definition.frameWidth > 0 && definition.frameHeight > 0 && definition.columns > 0 &&
            definition.widthTiles > 0 && definition.heightTiles > 0 &&
            (definition.collision.isEmpty() || definition.collision.size == definition.heightTiles) &&
            definition.collision.all { row ->
                row.size == definition.widthTiles && row.all { cell -> cell in 0..1 }
            } &&
            definition.entrance?.let { entrance ->
                entrance.x in 0f..definition.widthTiles.toFloat() &&
                    entrance.y in 0f..definition.heightTiles.toFloat()
            } != false &&
            definition.visualWidthTiles?.let { it > 0 } != false &&
            definition.visualHeightTiles?.let { it > 0 } != false &&
            definition.directionTransforms.all { (direction, transform) ->
                direction.uppercase() in setOf("N", "E", "S", "W") &&
                    transform.quarterTurnsClockwise in 0..3
            } &&
            definition.clips.isNotEmpty() && definition.clips.all { clip ->
                clip.frames.isNotEmpty() && clip.frameMs > 0
            }

    private fun assetExists(path: String): Boolean = existingPaths.getOrPut(path) { runCatching {
        assetManager.open(path).use { }
        true
    }.getOrDefault(false) }

    private fun estimateBytes(path: String): Long = imageSizes.getOrPut(path) { runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assetManager.open(path).use { BitmapFactory.decodeStream(it, null, options) }
        options.outWidth.toLong().coerceAtLeast(0L) * options.outHeight.toLong().coerceAtLeast(0L) * 4L
    }.getOrDefault(Long.MAX_VALUE) }

    private fun decodeAsset(
        assetId: String,
        definition: WorldAssetEntry,
        loaded: MutableMap<String, WorldSpriteAtlasEntry>,
        invalid: MutableSet<String>
    ): Int {
        val bitmap = WorldBitmapCache.decode(assetManager, definition.path)
        if (bitmap == null || !bitmap.hasAlpha() || bitmap.width != definition.frameWidth * definition.columns ||
            bitmap.height < definition.frameHeight || bitmap.height % definition.frameHeight != 0 ||
            definition.clips.any { clip ->
                clip.frames.any { frame ->
                    frame < 0 ||
                        frame >= (bitmap.height / definition.frameHeight) * definition.columns
                }
            }
        ) {
            invalid += assetId
            return 0
        }
        loaded[assetId] = WorldSpriteAtlasEntry(definition, bitmap.asImageBitmap())
        return bitmap.rowBytes * bitmap.height
    }

    private fun definitionForFallback(assetId: String, path: String): WorldAssetEntry = WorldAssetEntry(
        id = assetId,
        path = path,
        frameWidth = 256,
        frameHeight = 256,
        columns = 1,
        clips = listOf(WorldAssetClip("idle", "S", listOf(0), 1000)),
        footAnchor = WorldAssetPoint(0.5f, 1f),
        collision = emptyList()
    )
}

@Composable
fun rememberWorldSpriteAtlas(
    manifestPath: String = DEFAULT_WORLD_ASSET_MANIFEST_PATH,
    expectedAssetIds: Set<String> = WorldAssetCatalog.requiredAssetIds,
    visibleAssetIds: Set<String> = expectedAssetIds,
    allowDevelopmentFallbacks: Boolean = false,
    validateFullManifest: Boolean = true
): State<WorldAssetLoadResult> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = remember(context.assets) { WorldAssetManifestLoader(context.assets) }
    return produceState(
        initialValue = WorldAssetLoadResult(
            manifest = null,
            atlas = WorldSpriteAtlas.Empty,
            readiness = WorldAssetReadinessUi(
                manifestPath = manifestPath,
                schemaVersion = null,
                isLoading = true
            )
        ),
        context,
        manifestPath,
        expectedAssetIds,
        visibleAssetIds,
        allowDevelopmentFallbacks,
        validateFullManifest
    ) {
        value = loader.load(
            manifestPath = manifestPath,
            expectedAssetIds = expectedAssetIds,
            visibleAssetIds = visibleAssetIds,
            allowDevelopmentFallbacks = allowDevelopmentFallbacks,
            validateFullManifest = validateFullManifest
        )
    }
}

/** Canonical IDs from the full world asset contract. */
object WorldAssetCatalog {
    private val terrainIds = listOf(
        "terrain_grass_meadow", "terrain_grass_forest", "terrain_grass_mystic", "terrain_dirt",
        "terrain_path", "terrain_sand", "terrain_mud", "terrain_rock", "terrain_snow",
        "terrain_ice", "terrain_shallow_water", "terrain_deep_water", "terrain_farmland_dry",
        "terrain_farmland_wet"
    )
    private val transitionIds = listOf(
        "edge_grass_dirt", "edge_grass_sand", "edge_grass_rock", "edge_snow_grass",
        "edge_water_shore", "edge_mud_water"
    )
    private val objectIds = listOf(
        "flower_white", "flower_yellow", "flower_blue", "grass_tuft", "berry_bush_empty",
        "berry_bush_full", "berry_bush_regrowing", "small_rock", "tree_oak_young", "tree_oak_mature",
        "tree_oak_regrowing", "tree_oak_stump",
        "tree_pine", "tree_birch", "fallen_log", "mushroom_red", "mushroom_brown", "forest_fern",
        "herb_common", "reeds", "lily_pad", "wetland_grass", "wetland_mushroom", "mud_puddle",
        "desert_plant", "desert_cactus", "desert_rock", "dry_bush", "desert_ruin_fragment",
        "rock_node", "rock_node_depleted", "mineral_node_common", "mineral_node_depleted", "boulder_large", "cliff_edge", "mountain_shrub",
        "snow_pine", "snow_bush", "snow_rock", "ice_patch", "shell", "driftwood",
        "coastal_grass", "shore_rock", "mystic_tree", "glow_flower", "mystic_mushroom",
        "wisp_plant", "mystic_stone"
    )
    private val structureIds = listOf(
        "building_home", "building_shop", "building_school", "building_workplace",
        "building_boxing_gym", "building_hospital", "building_arcade", "building_alchemist",
        "building_farmhouse", "building_farm_barn", "structure_dungeon_ruin_a",
        "structure_dungeon_ruin_b", "structure_adventure_gate", "house_npc_a", "house_npc_b",
        "house_npc_c", "market_stall", "park_pavilion", "park_bench", "street_lamp", "signpost",
        "well", "wood_bridge", "stone_bridge", "pond_edge_props", "farm_plot_border",
        "watering_effect", "soil_till_effect", "harvest_effect"
    )
    private val toolIds = listOf(
        "tool_axe", "tool_hoe", "tool_watering_can", "tool_pickaxe",
        "tool_basket", "tool_medical", "tool_book"
    )
    private val effectIds = listOf("actor_shadow")
    private val worldEffectIds = listOf(
        "fx_dust_walk", "fx_water_step", "fx_chop", "fx_harvest", "fx_hatch",
        "fx_level_success", "fx_social_heart", "fx_question", "fx_thought", "fx_portal"
    )
    private val iconIds = listOf(
        "icon_follow_pet", "icon_world_map", "icon_home", "icon_journal", "icon_brain",
        "icon_goal", "icon_inventory", "icon_npc", "icon_resource", "icon_waypoint",
        "icon_unknown", "icon_training", "icon_pause", "icon_speed", "icon_checkpoint"
    )
    private val mapMarkerIds = listOf(
        "map_home", "map_school", "map_workplace", "map_boxing_ring", "map_shop", "map_arcade",
        "map_park", "map_hospital", "map_alchemist", "map_farm", "map_dungeon",
        "map_adventure_gate", "map_unknown"
    )
    private val cropIds = listOf(
        "crop_wheat", "crop_rice", "crop_carrot", "crop_tomato", "crop_corn", "crop_strawberry",
        "crop_daisy", "crop_sunflower", "crop_rose", "crop_tullip", "crop_melon", "crop_pumpkin"
    )
    private val npcIds = listOf(
        "npc_farm_farmer", "npc_boxing_coach", "npc_shop_seller", "npc_school_teacher", "npc_hospital_doctor",
        "npc_dungeon_adventurer", "npc_arcade_machine", "npc_alchemist_keeper", "npc_cloud_bunny", "npc_puddle_duck",
        "npc_mint_fox", "npc_berry_cat", "npc_moss_deer", "npc_sun_lamb", "npc_acorn_mouse",
        "npc_ribbon_bird", "npc_sprout_frog", "npc_paper_pup", "npc_recycler", "npc_seller"
    )
    private val petIds = listOf("dragon", "kitsune", "unicorn").flatMap { species ->
        listOf("egg", "baby", "child", "teen", "adult", "senior").map { stage ->
            "pet_${species}_$stage"
        }
    }

    val requiredAssetIds: Set<String> = (
        terrainIds + transitionIds + objectIds + structureIds + toolIds + effectIds + worldEffectIds +
            iconIds + mapMarkerIds + cropIds + npcIds + petIds
    ).toSet()

    /**
     * Development-only bridge for old Tama art while the world PNG pack is being authored.
     * Production callers keep this disabled so missing files are visible.
     */
    fun temporaryFallbackPath(assetId: String): String? = when (assetId) {
        "npc_farm_farmer" -> "tama/npcs/farm_farmer.png"
        "npc_boxing_coach" -> "tama/npcs/boxing_coach.png"
        "npc_shop_seller" -> "tama/npcs/shop_seller.png"
        "npc_school_teacher" -> "tama/npcs/school_teacher.png"
        "npc_hospital_doctor" -> "tama/npcs/hospital_doctor.png"
        "npc_dungeon_adventurer" -> "tama/npcs/dungeon_adventurer.png"
        "npc_arcade_machine" -> "tama/npcs/arcade_machine.png"
        "npc_alchemist_keeper" -> "tama/npcs/alchemist_keeper.png"
        "npc_cloud_bunny" -> "tama/npcs/cloud_bunny.png"
        "npc_puddle_duck" -> "tama/npcs/puddle_duck.png"
        "npc_mint_fox" -> "tama/npcs/mint_fox.png"
        "npc_berry_cat" -> "tama/npcs/berry_cat.png"
        "npc_moss_deer" -> "tama/npcs/moss_deer.png"
        "npc_sun_lamb" -> "tama/npcs/sun_lamb.png"
        "npc_acorn_mouse" -> "tama/npcs/acorn_mouse.png"
        "npc_ribbon_bird" -> "tama/npcs/ribbon_bird.png"
        "npc_sprout_frog" -> "tama/npcs/sprout_frog.png"
        "npc_paper_pup" -> "tama/npcs/paper_pup.png"
        "npc_recycler" -> "tama/npcs/recycler.png"
        "npc_seller" -> "tama/npcs/seller.png"
        else -> null
    }
}
