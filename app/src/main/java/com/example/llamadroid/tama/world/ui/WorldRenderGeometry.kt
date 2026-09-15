package com.example.llamadroid.tama.world.ui

/** Shared with the published 8×6 atlas contract: N, NE, E, SE, S, SW, W, NW. */
internal object WorldBlobTiles {
    val offsets = listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)
    val masks = (0..255).filter { normalize(it) == it }
    private val indices = masks.withIndex().associate { it.value to it.index }

    fun normalize(mask: Int): Int {
        var value = mask and 255
        if (value and 5 != 5) value = value and 2.inv()
        if (value and 20 != 20) value = value and 8.inv()
        if (value and 80 != 80) value = value and 32.inv()
        if (value and 65 != 65) value = value and 128.inv()
        return value
    }

    fun index(mask: Int): Int = indices.getValue(normalize(mask))

    fun mask(tile: WorldTileUi, tiles: Map<Pair<Int, Int>, WorldTileUi>, connected: (WorldTileUi) -> Boolean): Int {
        var bits = 0
        offsets.forEachIndexed { index, (dx, dy) ->
            val neighbor = tiles[tile.x + dx to tile.y + dy]
            // Unknown terrain is never sampled to draw a revealing boundary through fog.
            if (neighbor == null || !neighbor.known || connected(neighbor)) bits = bits or (1 shl index)
        }
        return normalize(bits)
    }

    fun overlays(tile: WorldTileUi, tiles: Map<Pair<Int, Int>, WorldTileUi>): List<Pair<String, Int>> {
        val neighbors = offsets.mapNotNull { (dx, dy) -> tiles[tile.x + dx to tile.y + dy]?.takeIf { it.known } }
        val water = tile.water
        val grass = tile.terrainId.startsWith("terrain_grass")
        val result = mutableListOf<Pair<String, Int>>()
        fun edge(id: String, outside: (WorldTileUi) -> Boolean) {
            if (neighbors.any(outside)) result += id to mask(tile, tiles) { !outside(it) }
        }
        when {
            water -> edge("edge_water_shore") { !it.water }
            tile.terrainId == "terrain_mud" -> edge("edge_mud_water") { it.water }
            tile.terrainId == "terrain_snow" -> edge("edge_snow_grass") { it.terrainId.startsWith("terrain_grass") }
            grass -> {
                edge("edge_grass_dirt") { it.terrainId in setOf("terrain_dirt", "terrain_path", "terrain_farmland_dry", "terrain_farmland_wet") }
                edge("edge_grass_sand") { it.terrainId == "terrain_sand" }
                edge("edge_grass_rock") { it.terrainId == "terrain_rock" }
            }
        }
        return result
    }
}

/** Retains only the current visible actor set. No camera value is sent to simulation. */
internal class WorldRenderMotion {
    private data class Motion(val from: WorldPointUi, val to: WorldPointUi, val started: Long, val duration: Long) {
        fun at(now: Long): WorldPointUi {
            val fraction = ((now - started).toFloat() / duration).coerceIn(0f, 1f)
            return WorldPointUi(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
        }
    }
    private val actors = mutableMapOf<String, Motion>()
    private val actions = mutableMapOf<String, Pair<String, Long>>()
    private var camera: WorldCameraUi? = null
    private var cameraMotion: Motion? = null

    fun update(values: List<WorldActorUi>, nextCamera: WorldCameraUi, now: Long, duration: Long) {
        val ids = values.mapTo(HashSet()) { it.id }
        actors.keys.retainAll(ids)
        actions.keys.retainAll(ids)
        values.forEach { actor ->
            val old = actors[actor.id]
            if (old?.to != actor.position) {
                val previous = old?.at(now) ?: actor.position
                val nearby = kotlin.math.abs(previous.x - actor.position.x) + kotlin.math.abs(previous.y - actor.position.y) <= 4f
                actors[actor.id] = Motion(if (nearby) previous else actor.position, actor.position, now, duration.coerceAtLeast(1))
            }
            val key = "${actor.action}:${actor.direction}"
            if (actions[actor.id]?.first != key) actions[actor.id] = key to now
        }
        val target = WorldPointUi(nextCamera.centerX, nextCamera.centerY)
        if (cameraMotion?.to != target || camera?.mode != nextCamera.mode) {
            val previous = cameraMotion?.at(now) ?: target
            val smooth = camera?.mode == nextCamera.mode && nextCamera.mode != WorldCameraMode.FREE &&
                kotlin.math.abs(previous.x - target.x) + kotlin.math.abs(previous.y - target.y) <= 4f
            cameraMotion = Motion(if (smooth) previous else target, target, now, duration.coerceAtLeast(1))
        }
        camera = nextCamera
    }

    fun position(actor: WorldActorUi, now: Long): WorldPointUi = actors[actor.id]?.at(now) ?: actor.position
    fun elapsed(actorId: String, now: Long): Long = (now - (actions[actorId]?.second ?: now)).coerceAtLeast(0)
    fun camera(fallback: WorldCameraUi, now: Long): WorldCameraUi = cameraMotion?.at(now)?.let {
        fallback.copy(centerX = it.x, centerY = it.y)
    } ?: fallback
}

internal fun worldToolAsset(action: String): String? = when (action.lowercase()) {
    "chop_tree" -> "tool_axe"
    "gather_stone" -> "tool_pickaxe"
    "till_soil", "plant", "remove_dead_crop" -> "tool_hoe"
    "water", "pour_water" -> "tool_watering_can"
    "harvest_crop", "forage", "harvest_wild_plant", "gather_herb", "store_produce" -> "tool_basket"
    "visit_hospital", "use_medicine" -> "tool_medical"
    "study" -> "tool_book"
    else -> null
}

internal fun worldActionEffect(action: String, water: Boolean = false): String? = when (action.lowercase()) {
    "walk", "run", "wander", "explore", "approach", "follow_npc" -> if (water) "fx_water_step" else "fx_dust_walk"
    "chop_tree", "gather_stone" -> "fx_chop"
    "till_soil" -> "soil_till_effect"
    "water", "pour_water" -> "watering_effect"
    "harvest_crop", "forage", "harvest_wild_plant", "gather_herb" -> "fx_harvest"
    "greet", "talk", "thank", "play_with", "help_npc" -> "fx_social_heart"
    "inspect", "observe_npc", "observe_object" -> "fx_question"
    "happy" -> "fx_level_success"
    else -> null
}
