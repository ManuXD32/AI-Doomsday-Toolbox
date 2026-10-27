package com.example.llamadroid.tama.data

/** Id of the pet-scoped `tama_farm_upgrades` row whose `lastProductionTime` is the last rain check (shifted by cycle freezes). */
const val FARM_WEATHER_STATE_ID = "weather_state"

/** The row is rewritten at most this often when no rain fell, so quiet refreshes cause no database churn. */
const val FARM_WEATHER_CHECKPOINT_MS = 60L * 60L * 1000L

/**
 * Applies [waterings] (as returned by [farmRainWaterings], already time-ordered) to [tiles]: each tile is advanced to
 * the event time and then watered, so crop progress stays consistent. Tiles without events are returned unchanged.
 * Callers pass stored tiles that have not been advanced past the first event.
 */
fun farmApplyRainWaterings(tiles: List<FarmTile>, waterings: List<FarmRainWatering>): List<FarmTile> {
    if (waterings.isEmpty()) return tiles
    val byId = tiles.associateBy { it.id }.toMutableMap()
    for (event in waterings) {
        val tile = byId[event.tileId] ?: continue
        byId[event.tileId] = farmWaterTile(advanceFarmTileCrop(tile, event.wateredAt), event.wateredAt)
    }
    return tiles.map { byId.getValue(it.id) }
}

/** Rain-view of a farm tile for [farmRainWaterings]. */
fun FarmTile.toRainTile(): FarmRainTile = FarmRainTile(id, status, lastWateredTime, obstacle != null)
