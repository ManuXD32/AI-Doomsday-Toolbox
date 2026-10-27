package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R

/** A failure whose message is already localized and safe to show as-is (e.g. "not enough coins"). */
internal class FarmUserMessageException(message: String) : IllegalStateException(message)

/** Failure code of [FarmMissingMaterialsException]; the UI can offer a shortcut to the store when it sees it. */
const val FARM_MATERIALS_REQUIRED = "materials_required"

/**
 * An upgrade or purchase needs wood and/or stone the pet does not have. Typed so callers can read exact
 * numbers ([need] is the full cost, [have] what is owned, [missing] the shortfall) instead of parsing text.
 */
internal class FarmMissingMaterialsException(
    val need: com.example.llamadroid.tama.data.FarmMaterialCost,
    val haveWood: Int,
    val haveStone: Int
) : IllegalStateException(FARM_MATERIALS_REQUIRED) {
    val missing: com.example.llamadroid.tama.data.FarmMaterialCost get() = need.missing(haveWood, haveStone)
}

/** Turns the farm rules' failure codes into the localized text the player sees. */
internal object FarmActionMessages {
    fun describe(context: Context, error: Throwable): String {
        if (error is FarmUserMessageException) {
            error.message?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (error is FarmMissingMaterialsException) {
            val missing = error.missing
            return context.getString(R.string.tama_farm_err_materials, missing.wood, missing.stone)
        }
        val resource = when (error.message) {
            "axe_required" -> R.string.tama_farm_err_axe
            "pickaxe_required" -> R.string.tama_farm_err_pickaxe
            "obstacle_blocked" -> R.string.tama_farm_err_obstacle
            "cycle_frozen" -> R.string.tama_cycle_frozen_busy
            "farm_plot_locked" -> R.string.tama_farm_err_locked_plot
            "tool_required" -> R.string.tama_farm_err_tool
            "already_tilled", "empty_tilled_plot_required", "dry_tilled_plot_required", "already_wet" -> R.string.tama_farm_err_plot_state
            "seed_required" -> R.string.tama_farm_err_seed
            "water_required" -> R.string.tama_farm_err_water
            "crop_not_ready", "harvest_failed" -> R.string.tama_farm_err_crop_not_ready
            "crop_required", "dead_crop_required" -> R.string.tama_farm_err_no_crop
            "fertilizer_required" -> R.string.tama_farm_err_fertilizer
            "fertilizer_not_needed" -> R.string.tama_farm_err_fertilizer_not_needed
            "item_required", "animal_missing" -> R.string.tama_farm_err_item
            "already_purchased" -> R.string.tama_farm_err_already_owned
            "output_not_ready" -> R.string.tama_farm_err_not_ready
            "animal_not_hungry" -> R.string.tama_farm_err_animal_not_hungry
            "feed_required" -> R.string.tama_farm_err_feed
            "livestock_full" -> R.string.tama_farm_err_livestock_full
            "upgrade_maxed" -> R.string.tama_farm_upgrade_maxed
            "drone_required", "well_required", "composter_required" -> R.string.tama_farm_err_upgrade_required
            "storage_empty" -> R.string.tama_farm_err_storage_empty
            else -> R.string.tama_action_unavailable
        }
        return context.getString(resource)
    }
}
