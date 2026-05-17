package com.example.llamadroid.tama.data

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import java.util.UUID

/**
 * Location in the Tama world.
 */
data class TamaLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LocationType,
    val description: String,
    val cityId: String,  // Which city this location belongs to
    val x: Int = 0,      // Position in city grid
    val y: Int = 0,
    val isDiscovered: Boolean = false,
    val npcIds: List<String> = emptyList(),  // NPCs at this location
    val shopInventory: List<String>? = null,  // Item IDs if it's a shop
    val jobs: List<Job>? = null  // Available jobs if workplace
)

enum class LocationType(
    val emoji: String,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val asciiArt: String,
    val mapIconAssetPath: String
) {
    HOME("🏠", R.string.tama_location_home, R.string.tama_location_home_desc, """
     /\
    /  \
   /____\
   |[][]|
   |____|
    """.trimIndent(), "tama/map/home.png"),

    SCHOOL("🏫", R.string.tama_location_school, R.string.tama_location_school_desc, """
     ___
    |SCH|
    |OOL|
    |___|
   /|   |\
    """.trimIndent(), "tama/map/school.png"),

    WORKPLACE("🏢", R.string.tama_location_workplace, R.string.tama_location_workplace_desc, """
    _____
   |WORK|
   |    |
   |    |
   |____|
    """.trimIndent(), "tama/map/workplace.png"),

    SHOP("🏪", R.string.tama_location_shop, R.string.tama_location_shop_desc, """
     ____
    |SHOP|
    | $$ |
    |____|
    """.trimIndent(), "tama/map/shop.png"),

    ARCADE("🕹️", R.string.tama_location_arcade, R.string.tama_location_arcade_desc, """
     ____
    |GAME|
    |PLAY|
    |____|
    """.trimIndent(), "tama/map/arcade.png"),

    PARK("🌳", R.string.tama_location_park, R.string.tama_location_park_desc, """
      ^
     /|\
    / | \
     _|_
    |___|
    """.trimIndent(), "tama/map/park.png"),

    HOSPITAL("🏥", R.string.tama_location_hospital, R.string.tama_location_hospital_desc, """
     _+_
    |   |
    | + |
    |___|
    """.trimIndent(), "tama/map/hospital.png"),

    ALCHEMIST("⚗️", R.string.tama_location_alchemist, R.string.tama_location_alchemist_desc, """
     ____
    |POTN|
    | ** |
    |____|
    """.trimIndent(), "tama/map/alchemist.png"),

    FARM("🌾", R.string.tama_location_farm, R.string.tama_location_farm_desc, """
    ~ ~ ~
   ~~~~~~
   |FARM|
   |____|
    """.trimIndent(), "tama/map/farm.png"),

    DUNGEON("🏚️", R.string.tama_location_dungeon, R.string.tama_location_dungeon_desc, """
     ___
    |DNG|
    | ? |
    |___|
    """.trimIndent(), "tama/map/dungeon.png"),

    ADVENTURE_GATE("🌀", R.string.tama_location_adventure_gate, R.string.tama_location_adventure_gate_desc, """
     ___
    |ADV|
    | > |
    |___|
    """.trimIndent(), "tama/map/adventure_gate.png")
}

fun LocationType.localizedName(context: Context): String = context.getString(displayNameRes)

fun LocationType.localizedDescription(context: Context): String = context.getString(descriptionRes)

/**
 * Job available at a workplace.
 */
data class Job(
    val id: String,
    val title: String,
    val requiredEducation: Int,  // Min education level
    val hourlyPay: Long,
    val energyCostPerHour: Int,
    val maxHoursPerDay: Int = 8
)

/**
 * City in the procedurally generated world.
 */
data class TamaCity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val seed: Long,  // For procedural generation
    val x: Int,      // World position
    val y: Int,
    val isDiscovered: Boolean = false,
    val locationIds: List<String> = emptyList()
)
