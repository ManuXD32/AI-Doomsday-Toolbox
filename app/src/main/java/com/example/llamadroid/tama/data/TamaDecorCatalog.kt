package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R

data class TamaDecorDefinition(
    val id: String,
    val titleRes: Int,
    val price: Int,
    val assetPath: String,
    val season: TamaSeason? = null
)

enum class TamaDecorSlot {
    LEFT,
    RIGHT
}

object TamaDecorCatalog {
    const val BALL_PLUSH_ID = "toy_ball_plush"
    const val BLOCK_TRAIN_ID = "toy_block_train"
    const val MINI_ROBOT_ID = "toy_mini_robot"
    const val ROCKING_HORSE_ID = "toy_rocking_horse"
    const val MUSIC_BOX_ID = "toy_music_box"
    const val TREASURE_CHEST_ID = "toy_treasure_chest"
    const val COZY_BEANBAG_ID = "toy_cozy_beanbag"
    const val MOON_LANTERN_ID = "toy_moon_lantern"
    const val CRYSTAL_TERRARIUM_ID = "toy_crystal_terrarium"
    const val MINI_BOOKSHELF_ID = "toy_mini_bookshelf"
    const val TINY_AQUARIUM_ID = "toy_tiny_aquarium"
    const val ROBOT_VACUUM_ID = "toy_robot_vacuum"

    val standardToys: List<TamaDecorDefinition> = listOf(
        TamaDecorDefinition(
            id = BALL_PLUSH_ID,
            titleRes = R.string.tama_toy_ball_plush,
            price = 250,
            assetPath = "tama/decor/ball_plush.png"
        ),
        TamaDecorDefinition(
            id = BLOCK_TRAIN_ID,
            titleRes = R.string.tama_toy_block_train,
            price = 400,
            assetPath = "tama/decor/block_train.png"
        ),
        TamaDecorDefinition(
            id = MINI_ROBOT_ID,
            titleRes = R.string.tama_toy_mini_robot,
            price = 550,
            assetPath = "tama/decor/mini_robot.png"
        ),
        TamaDecorDefinition(
            id = ROCKING_HORSE_ID,
            titleRes = R.string.tama_toy_rocking_horse,
            price = 750,
            assetPath = "tama/decor/rocking_horse.png"
        ),
        TamaDecorDefinition(
            id = MUSIC_BOX_ID,
            titleRes = R.string.tama_toy_music_box,
            price = 950,
            assetPath = "tama/decor/music_box.png"
        ),
        TamaDecorDefinition(
            id = TREASURE_CHEST_ID,
            titleRes = R.string.tama_toy_treasure_chest,
            price = 1200,
            assetPath = "tama/decor/treasure_chest.png"
        ),
        TamaDecorDefinition(
            id = COZY_BEANBAG_ID,
            titleRes = R.string.tama_toy_cozy_beanbag,
            price = 650,
            assetPath = "tama/decor/cozy_beanbag.png"
        ),
        TamaDecorDefinition(
            id = MOON_LANTERN_ID,
            titleRes = R.string.tama_toy_moon_lantern,
            price = 800,
            assetPath = "tama/decor/moon_lantern.png"
        ),
        TamaDecorDefinition(
            id = CRYSTAL_TERRARIUM_ID,
            titleRes = R.string.tama_toy_crystal_terrarium,
            price = 950,
            assetPath = "tama/decor/crystal_terrarium.png"
        ),
        TamaDecorDefinition(
            id = MINI_BOOKSHELF_ID,
            titleRes = R.string.tama_toy_mini_bookshelf,
            price = 700,
            assetPath = "tama/decor/mini_bookshelf.png"
        ),
        TamaDecorDefinition(
            id = TINY_AQUARIUM_ID,
            titleRes = R.string.tama_toy_tiny_aquarium,
            price = 1100,
            assetPath = "tama/decor/tiny_aquarium.png"
        ),
        TamaDecorDefinition(
            id = ROBOT_VACUUM_ID,
            titleRes = R.string.tama_toy_robot_vacuum,
            price = 900,
            assetPath = "tama/decor/robot_vacuum.png"
        )
    )

    val seasonalToys: List<TamaDecorDefinition> = listOf(
        seasonal("seasonal_spring_blossom_chime", R.string.tama_toy_seasonal_spring_blossom_chime, 950, "blossom_chime", TamaSeason.SPRING),
        seasonal("seasonal_spring_dewdrop_mushroom_lamp", R.string.tama_toy_seasonal_spring_dewdrop_mushroom_lamp, 1150, "dewdrop_mushroom_lamp", TamaSeason.SPRING),
        seasonal("seasonal_spring_rainbow_seedling_pot", R.string.tama_toy_seasonal_spring_rainbow_seedling_pot, 1050, "rainbow_seedling_pot", TamaSeason.SPRING),
        seasonal("seasonal_spring_butterfly_mailbox", R.string.tama_toy_seasonal_spring_butterfly_mailbox, 1250, "butterfly_mailbox", TamaSeason.SPRING),
        seasonal("seasonal_spring_sprout_crystal_bell", R.string.tama_toy_seasonal_spring_sprout_crystal_bell, 1350, "sprout_crystal_bell", TamaSeason.SPRING),
        seasonal("seasonal_spring_puddle_star_umbrella", R.string.tama_toy_seasonal_spring_puddle_star_umbrella, 1000, "puddle_star_umbrella", TamaSeason.SPRING),
        seasonal("seasonal_summer_seashell_radio", R.string.tama_toy_seasonal_summer_seashell_radio, 1100, "seashell_radio", TamaSeason.SUMMER),
        seasonal("seasonal_summer_sun_charm_mobile", R.string.tama_toy_seasonal_summer_sun_charm_mobile, 950, "sun_charm_mobile", TamaSeason.SUMMER),
        seasonal("seasonal_summer_firefly_jar", R.string.tama_toy_seasonal_summer_firefly_jar, 1200, "firefly_jar", TamaSeason.SUMMER),
        seasonal("seasonal_summer_melon_picnic_basket", R.string.tama_toy_seasonal_summer_melon_picnic_basket, 1050, "melon_picnic_basket", TamaSeason.SUMMER),
        seasonal("seasonal_summer_bubbleglass_fan", R.string.tama_toy_seasonal_summer_bubbleglass_fan, 1300, "bubbleglass_fan", TamaSeason.SUMMER),
        seasonal("seasonal_summer_starfish_cushion", R.string.tama_toy_seasonal_summer_starfish_cushion, 1000, "starfish_cushion", TamaSeason.SUMMER),
        seasonal("seasonal_autumn_acorn_tea_set", R.string.tama_toy_seasonal_autumn_acorn_tea_set, 1050, "acorn_tea_set", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_maple_garland", R.string.tama_toy_seasonal_autumn_maple_garland, 900, "maple_garland", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_mushroom_stool", R.string.tama_toy_seasonal_autumn_mushroom_stool, 1150, "mushroom_stool", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_pumpkin_candle", R.string.tama_toy_seasonal_autumn_pumpkin_candle, 950, "pumpkin_candle", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_harvest_cauldron", R.string.tama_toy_seasonal_autumn_harvest_cauldron, 1400, "harvest_cauldron", TamaSeason.AUTUMN),
        seasonal("seasonal_autumn_tiny_scarecrow", R.string.tama_toy_seasonal_autumn_tiny_scarecrow, 1000, "tiny_scarecrow", TamaSeason.AUTUMN),
        seasonal("seasonal_winter_snowglobe_cottage", R.string.tama_toy_seasonal_winter_snowglobe_cottage, 1200, "snowglobe_cottage", TamaSeason.WINTER),
        seasonal("seasonal_winter_candy_cane_sled", R.string.tama_toy_seasonal_winter_candy_cane_sled, 1100, "candy_cane_sled", TamaSeason.WINTER),
        seasonal("seasonal_winter_frost_crystal_tree", R.string.tama_toy_seasonal_winter_frost_crystal_tree, 1450, "frost_crystal_tree", TamaSeason.WINTER),
        seasonal("seasonal_winter_knitted_star_blanket", R.string.tama_toy_seasonal_winter_knitted_star_blanket, 950, "knitted_star_blanket", TamaSeason.WINTER),
        seasonal("seasonal_winter_gingerbread_clock", R.string.tama_toy_seasonal_winter_gingerbread_clock, 1250, "gingerbread_clock", TamaSeason.WINTER),
        seasonal("seasonal_winter_cocoa_kettle", R.string.tama_toy_seasonal_winter_cocoa_kettle, 1000, "cocoa_kettle", TamaSeason.WINTER)
    )

    val toys: List<TamaDecorDefinition> = standardToys + seasonalToys

    fun decorById(itemId: String?): TamaDecorDefinition? {
        return toys.firstOrNull { it.id.equals(itemId, ignoreCase = true) }
    }

    fun isDecorId(itemId: String?): Boolean = decorById(itemId) != null

    fun shopDecor(): List<TamaDecorDefinition> = standardToys

    fun seasonalDecorForSeason(season: TamaSeason): List<TamaDecorDefinition> =
        seasonalToys.filter { it.season == season }

    fun decorInventoryItem(context: Context, decorId: String): InventoryItem? {
        val decor = decorById(decorId) ?: return null
        return InventoryItem(
            id = decor.id,
            name = context.getString(decor.titleRes),
            type = ItemType.TOY,
            quantity = 1
        )
    }

    private fun seasonal(
        id: String,
        titleRes: Int,
        price: Int,
        fileName: String,
        season: TamaSeason
    ): TamaDecorDefinition = TamaDecorDefinition(
        id = id,
        titleRes = titleRes,
        price = price,
        assetPath = "tama/decor/seasonal/$fileName.webp",
        season = season
    )
}
