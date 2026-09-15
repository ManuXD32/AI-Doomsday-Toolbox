package com.example.llamadroid.tama.world.persistence

import android.content.Context
import com.example.llamadroid.tama.world.core.StructureLayout
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldCoordinate
import org.json.JSONObject

/** The artwork contract is also the placement/collision contract for newly generated worlds. */
object WorldBuildingLayouts {
    val assetIds = mapOf(
        "HOME" to "building_home", "SHOP" to "building_shop", "PARK" to "park_pavilion",
        "HOSPITAL" to "building_hospital", "ARCADE" to "building_arcade", "ALCHEMIST" to "building_alchemist",
        "SCHOOL" to "building_school", "WORKPLACE" to "building_workplace", "FARM" to "building_farmhouse",
        "BOXING_RING" to "building_boxing_gym", "DUNGEON_A" to "structure_dungeon_ruin_a",
        "DUNGEON_B" to "structure_dungeon_ruin_b", "ADVENTURE_GATE" to "structure_adventure_gate",
        "FARM_BARN" to "building_farm_barn", "NPC_HOME_A" to "house_npc_a", "NPC_HOME_B" to "house_npc_b",
        "NPC_HOME_C" to "house_npc_c", "MARKET_STALL" to "market_stall"
    )

    fun read(context: Context): Map<StructureType, StructureLayout> {
        val manifest = context.assets.open("tama/world/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schemaVersion") == 1)
        val entries = manifest.optJSONArray("plannedBuildings") ?: manifest.getJSONArray("assets")
        val byId = (0 until entries.length()).map { entries.getJSONObject(it) }.associateBy { it.getString("id") }
        return StructureType.entries.mapNotNull { type ->
            val id = assetIds[type.name] ?: return@mapNotNull null
            val entry = byId[id] ?: return@mapNotNull null
            val collision = entry.getJSONArray("collision")
            val entrance = entry.getJSONObject("entrance")
            type to StructureLayout(
                widthTiles = entry.getInt("widthTiles"), heightTiles = entry.getInt("heightTiles"),
                collision = (0 until collision.length()).map { y ->
                    val row = collision.getJSONArray(y)
                    (0 until row.length()).map(row::getInt)
                }, entrance = WorldCoordinate(entrance.getInt("x"), entrance.getInt("y"))
            )
        }.toMap()
    }
}
