package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R

data class TamaRoomDefinition(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val price: Int,
    val assetPath: String,
    val season: TamaSeason? = null
)

object TamaRoomCatalog {
    const val PRINCIPAL_ROOM_ID = "principal_room"
    const val ARCADE_ROOM_ID = "arcade_room"
    const val GARDEN_ROOM_ID = "garden_room"
    const val DREAM_ROOM_ID = "dream_room"
    const val KITCHEN_ROOM_ID = "kitchen_room"
    const val OBSERVATORY_ROOM_ID = "observatory_room"
    const val ART_STUDIO_ROOM_ID = "art_studio_room"
    const val MODERN_LOFT_ROOM_ID = "modern_loft_room"

    val standardRooms: List<TamaRoomDefinition> = listOf(
        TamaRoomDefinition(
            id = PRINCIPAL_ROOM_ID,
            titleRes = R.string.tama_room_principal_title,
            descriptionRes = R.string.tama_room_principal_desc,
            price = 0,
            assetPath = "tama/backgrounds/principal_room.png"
        ),
        TamaRoomDefinition(
            id = ARCADE_ROOM_ID,
            titleRes = R.string.tama_room_arcade_title,
            descriptionRes = R.string.tama_room_arcade_desc,
            price = 3000,
            assetPath = "tama/backgrounds/arcade_room.png"
        ),
        TamaRoomDefinition(
            id = GARDEN_ROOM_ID,
            titleRes = R.string.tama_room_garden_title,
            descriptionRes = R.string.tama_room_garden_desc,
            price = 3000,
            assetPath = "tama/backgrounds/garden_room.png"
        ),
        TamaRoomDefinition(
            id = DREAM_ROOM_ID,
            titleRes = R.string.tama_room_dream_title,
            descriptionRes = R.string.tama_room_dream_desc,
            price = 3000,
            assetPath = "tama/backgrounds/library_room.png"
        ),
        TamaRoomDefinition(
            id = KITCHEN_ROOM_ID,
            titleRes = R.string.tama_room_kitchen_title,
            descriptionRes = R.string.tama_room_kitchen_desc,
            price = 3000,
            assetPath = "tama/backgrounds/kitchen_room.png"
        ),
        TamaRoomDefinition(
            id = OBSERVATORY_ROOM_ID,
            titleRes = R.string.tama_room_observatory_title,
            descriptionRes = R.string.tama_room_observatory_desc,
            price = 3000,
            assetPath = "tama/backgrounds/observatory_room.png"
        ),
        TamaRoomDefinition(
            id = ART_STUDIO_ROOM_ID,
            titleRes = R.string.tama_room_art_studio_title,
            descriptionRes = R.string.tama_room_art_studio_desc,
            price = 3000,
            assetPath = "tama/backgrounds/art_studio_room.png"
        ),
        TamaRoomDefinition(
            id = MODERN_LOFT_ROOM_ID,
            titleRes = R.string.tama_room_modern_loft_title,
            descriptionRes = R.string.tama_room_modern_loft_desc,
            price = 3000,
            assetPath = "tama/backgrounds/modern_loft_room.png"
        )
    )

    val seasonalRooms: List<TamaRoomDefinition> = listOf(
        seasonal("seasonal_spring_blossom_tea_parlor", R.string.tama_room_seasonal_spring_blossom_tea_parlor_title, R.string.tama_room_seasonal_spring_blossom_tea_parlor_desc, "blossom_tea_parlor", TamaSeason.SPRING),
        seasonal("seasonal_spring_raincloud_playroom", R.string.tama_room_seasonal_spring_raincloud_playroom_title, R.string.tama_room_seasonal_spring_raincloud_playroom_desc, "raincloud_playroom", TamaSeason.SPRING),
        seasonal("seasonal_spring_butterfly_atrium", R.string.tama_room_seasonal_spring_butterfly_atrium_title, R.string.tama_room_seasonal_spring_butterfly_atrium_desc, "butterfly_atrium", TamaSeason.SPRING),
        seasonal("seasonal_spring_seedling_workshop", R.string.tama_room_seasonal_spring_seedling_workshop_title, R.string.tama_room_seasonal_spring_seedling_workshop_desc, "seedling_workshop", TamaSeason.SPRING),
        seasonal("seasonal_summer_beach_cabana", R.string.tama_room_seasonal_summer_beach_cabana_title, R.string.tama_room_seasonal_summer_beach_cabana_desc, "beach_cabana", TamaSeason.SUMMER),
        seasonal("seasonal_summer_firefly_porch", R.string.tama_room_seasonal_summer_firefly_porch_title, R.string.tama_room_seasonal_summer_firefly_porch_desc, "firefly_porch", TamaSeason.SUMMER),
        seasonal("seasonal_summer_coral_tide_room", R.string.tama_room_seasonal_summer_coral_tide_room_title, R.string.tama_room_seasonal_summer_coral_tide_room_desc, "coral_tide_room", TamaSeason.SUMMER),
        seasonal("seasonal_summer_ice_cream_parlor", R.string.tama_room_seasonal_summer_ice_cream_parlor_title, R.string.tama_room_seasonal_summer_ice_cream_parlor_desc, "ice_cream_parlor", TamaSeason.SUMMER),
        seasonal("seasonal_autumn_pumpkin_hollow_den", R.string.tama_room_seasonal_autumn_pumpkin_hollow_den_title, R.string.tama_room_seasonal_autumn_pumpkin_hollow_den_desc, "pumpkin_hollow_den", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_maple_attic", R.string.tama_room_seasonal_autumn_maple_attic_title, R.string.tama_room_seasonal_autumn_maple_attic_desc, "maple_attic", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_cider_porch", R.string.tama_room_seasonal_autumn_cider_porch_title, R.string.tama_room_seasonal_autumn_cider_porch_desc, "cider_porch", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_storybook_hearth", R.string.tama_room_seasonal_autumn_storybook_hearth_title, R.string.tama_room_seasonal_autumn_storybook_hearth_desc, "storybook_hearth", TamaSeason.AUTUMN),
        seasonal("seasonal_winter_snowdrift_cabin", R.string.tama_room_seasonal_winter_snowdrift_cabin_title, R.string.tama_room_seasonal_winter_snowdrift_cabin_desc, "snowdrift_cabin", TamaSeason.WINTER),
        seasonal("seasonal_winter_aurora_igloo", R.string.tama_room_seasonal_winter_aurora_igloo_title, R.string.tama_room_seasonal_winter_aurora_igloo_desc, "aurora_igloo", TamaSeason.WINTER),
        seasonal("seasonal_winter_gingerbread_parlor", R.string.tama_room_seasonal_winter_gingerbread_parlor_title, R.string.tama_room_seasonal_winter_gingerbread_parlor_desc, "gingerbread_parlor", TamaSeason.WINTER),
        seasonal("seasonal_winter_starlit_toy_workshop", R.string.tama_room_seasonal_winter_starlit_toy_workshop_title, R.string.tama_room_seasonal_winter_starlit_toy_workshop_desc, "starlit_toy_workshop", TamaSeason.WINTER)
    )

    val rooms: List<TamaRoomDefinition> = standardRooms + seasonalRooms

    fun roomById(roomId: String?): TamaRoomDefinition? =
        rooms.firstOrNull { it.id.equals(roomId, ignoreCase = true) }

    fun isRoomId(roomId: String?): Boolean = roomById(roomId) != null

    fun homeRoomAssetPath(roomId: String?): String =
        roomById(roomId)?.assetPath ?: rooms.first { it.id == PRINCIPAL_ROOM_ID }.assetPath

    fun shopRooms(): List<TamaRoomDefinition> =
        standardRooms.filter { it.id != PRINCIPAL_ROOM_ID }

    fun seasonalRoomsForSeason(season: TamaSeason): List<TamaRoomDefinition> =
        seasonalRooms.filter { it.season == season }

    fun roomInventoryItem(context: Context, roomId: String): InventoryItem? {
        val room = roomById(roomId) ?: return null
        return InventoryItem(
            id = room.id,
            name = context.getString(room.titleRes),
            type = ItemType.DECORATION,
            quantity = 1
        )
    }

    private fun seasonal(
        id: String,
        titleRes: Int,
        descriptionRes: Int,
        fileName: String,
        season: TamaSeason
    ): TamaRoomDefinition = TamaRoomDefinition(
        id = id,
        titleRes = titleRes,
        descriptionRes = descriptionRes,
        price = 4200,
        assetPath = "tama/backgrounds/seasonal/$fileName.webp",
        season = season
    )
}
