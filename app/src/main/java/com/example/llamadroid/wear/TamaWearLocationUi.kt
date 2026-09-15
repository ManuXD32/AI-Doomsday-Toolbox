package com.example.llamadroid.wear

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.LocationType
import com.example.llamadroid.tama.world.core.LegacyLocationAliases

/**
 * Stable location presentation shared by the phone-to-Wear bridges. Legacy
 * fixed-grid IDs and generated facility IDs must resolve before falling back
 * to a human location name; otherwise Wear shows implementation IDs such as
 * `fixed_4_0` or `farm_barn`.
 */
internal data class TamaWearLocationUi(
    val type: LocationType,
    @StringRes val labelRes: Int,
    val backgroundAssetPath: String,
    val usesHomeRoom: Boolean = false,
    val canonical: Boolean = true
)

internal object TamaWearLocationCatalog {
    private val fixed = mapOf(
        LegacyLocationAliases.HOME to TamaWearLocationUi(
            LocationType.HOME, R.string.tama_world_structure_home,
            "tama/backgrounds/bedroom.png", usesHomeRoom = true
        ),
        LegacyLocationAliases.SHOP to TamaWearLocationUi(
            LocationType.SHOP, R.string.tama_world_structure_shop,
            "tama/backgrounds/shop.png"
        ),
        LegacyLocationAliases.PARK to TamaWearLocationUi(
            LocationType.PARK, R.string.tama_world_structure_park,
            "tama/backgrounds/park.png"
        ),
        LegacyLocationAliases.HOSPITAL to TamaWearLocationUi(
            LocationType.HOSPITAL, R.string.tama_world_structure_hospital,
            "tama/backgrounds/hospital.png"
        ),
        LegacyLocationAliases.ARCADE to TamaWearLocationUi(
            LocationType.ARCADE, R.string.tama_world_structure_arcade,
            "tama/backgrounds/arcade_location.png"
        ),
        LegacyLocationAliases.ALCHEMIST to TamaWearLocationUi(
            LocationType.ALCHEMIST, R.string.tama_world_structure_alchemist,
            "tama/backgrounds/alchemist.png"
        ),
        LegacyLocationAliases.SCHOOL to TamaWearLocationUi(
            LocationType.SCHOOL, R.string.tama_world_structure_school,
            "tama/backgrounds/classroom.png"
        ),
        LegacyLocationAliases.WORKPLACE to TamaWearLocationUi(
            LocationType.WORKPLACE, R.string.tama_world_structure_workplace,
            "tama/backgrounds/workplace.png"
        ),
        LegacyLocationAliases.FARM to TamaWearLocationUi(
            LocationType.FARM, R.string.tama_world_structure_farm,
            "tama/backgrounds/farm.png"
        ),
        LegacyLocationAliases.BOXING_RING to TamaWearLocationUi(
            LocationType.BOXING_RING, R.string.tama_world_structure_boxing_gym,
            "tama/backgrounds/boxing_ring.png"
        ),
        LegacyLocationAliases.DUNGEON_A to TamaWearLocationUi(
            LocationType.DUNGEON, R.string.tama_world_structure_dungeon,
            "tama/backgrounds/dungeon.png"
        ),
        LegacyLocationAliases.DUNGEON_B to TamaWearLocationUi(
            LocationType.DUNGEON, R.string.tama_world_structure_dungeon,
            "tama/backgrounds/dungeon.png"
        ),
        LegacyLocationAliases.ADVENTURE_GATE to TamaWearLocationUi(
            LocationType.ADVENTURE_GATE, R.string.tama_world_structure_adventure_gate,
            "tama/backgrounds/adventure_gate.png"
        )
    )

    private val generated = mapOf(
        "farm_barn" to TamaWearLocationUi(
            LocationType.FARM, R.string.tama_world_structure_farm_barn,
            "tama/backgrounds/farm.png"
        ),
        "market_stall" to TamaWearLocationUi(
            LocationType.SHOP, R.string.tama_world_structure_market_stall,
            "tama/backgrounds/street_market.png"
        ),
        "npc_home_a" to TamaWearLocationUi(
            LocationType.HOME, R.string.tama_world_structure_npc_home_a,
            "tama/backgrounds/bedroom.png"
        ),
        "npc_home_b" to TamaWearLocationUi(
            LocationType.HOME, R.string.tama_world_structure_npc_home_b,
            "tama/backgrounds/bedroom.png"
        ),
        "npc_home_c" to TamaWearLocationUi(
            LocationType.HOME, R.string.tama_world_structure_npc_home_c,
            "tama/backgrounds/bedroom.png"
        ),
        "world" to TamaWearLocationUi(
            LocationType.PARK, R.string.tama_world_source_living,
            "tama/backgrounds/park.png"
        ),
        "hometown" to TamaWearLocationUi(
            LocationType.PARK, R.string.tama_world_source_living,
            "tama/backgrounds/park.png"
        )
    )

    private val unknown = TamaWearLocationUi(
        LocationType.PARK,
        R.string.tama_world_structure_landmark,
        "tama/backgrounds/park.png",
        canonical = false
    )

    fun resolve(locationId: String?): TamaWearLocationUi {
        val raw = locationId?.trim()?.lowercase().orEmpty()
        LegacyLocationAliases.normalize(raw)?.let { fixed[it] }?.let { return it }
        generated[raw]?.let { return it }
        when {
            raw.endsWith("farm_barn") -> return generated.getValue("farm_barn")
            raw.endsWith("market_stall") -> return generated.getValue("market_stall")
            raw.endsWith("npc_home_a") -> return generated.getValue("npc_home_a")
            raw.endsWith("npc_home_b") -> return generated.getValue("npc_home_b")
            raw.endsWith("npc_home_c") -> return generated.getValue("npc_home_c")
            raw == "home" || raw == "house" -> return fixed.getValue(LegacyLocationAliases.HOME)
        }
        return unknown
    }

    fun label(context: Context, locationId: String?, legacyName: String? = null): String {
        val presentation = resolve(locationId)
        if (presentation.canonical) return context.getString(presentation.labelRes)
        val name = legacyName?.trim().orEmpty()
        return if (name.isNotBlank() && !name.isGenericLocationLabel()) {
            name
        } else {
            context.getString(presentation.labelRes)
        }
    }

    private fun String.isGenericLocationLabel(): Boolean = when (trim().lowercase()) {
        "world", "living world", "mundo", "mundo vivo", "farm barn", "fixed" -> true
        else -> startsWith("fixed_")
    }
}
