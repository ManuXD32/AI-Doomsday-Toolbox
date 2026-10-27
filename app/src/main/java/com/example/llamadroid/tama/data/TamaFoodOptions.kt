package com.example.llamadroid.tama.data

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

enum class TamaFoodKind { FREE, SHOP, CROP, WORLD }

/**
 * One edible entry for the pet. [id] is the canonical catalog id; [stackIds] are the (lowercase)
 * inventory ids that feed it, so duplicate stacks and legacy aliases (berries/berry) merge.
 * [quantity] is null for free, unlimited foods.
 */
data class TamaFoodOption(
    val id: String,
    val emoji: String,
    @StringRes val nameRes: Int?,
    val text: TamaLocalizedText?,
    val quantity: Int?,
    val hungerGain: Int,
    val happinessGain: Int,
    val kind: TamaFoodKind,
    val stackIds: Set<String> = emptySet()
) {
    fun label(context: Context): String = nameRes?.let(context::getString)
        ?: text?.resolve(context.resources.configuration.locales[0])
        ?: id

    fun label(locale: Locale): String = text?.resolve(locale) ?: id
}

/** Which raw crops the pet may eat. Everything else (grains, pumpkin, flowers) is sell-or-craft only. */
object TamaCropEdibility {
    val EDIBLE: Set<String> = setOf("carrot", "tomato", "strawberry", "melon", "corn")

    /** [id] is a catalog crop id ("wheat") or an inventory id ("crop_wheat"). */
    fun isEdible(id: String): Boolean = EDIBLE.contains(cropId(id))

    /** True for any crop id (catalog or inventory form) that is not edible. */
    fun isInedibleCrop(id: String): Boolean {
        val crop = cropId(id)
        return !EDIBLE.contains(crop) && CropDefinitions.CROPS.containsKey(crop)
    }

    private fun cropId(id: String): String = id.trim().lowercase(Locale.ROOT).removePrefix("crop_")
}

/** Single source of truth for what the pet can eat, shared by the home dialog, shimeji overlay and engine. */
object TamaFoodOptions {
    private data class WorldFood(val ids: Set<String>, val canonical: String, val emoji: String, val hunger: Int, val happiness: Int)

    private val worldFoods = listOf(
        WorldFood(setOf("berry", "berries"), "berry", "🫐", 8, 3),
        WorldFood(setOf("mushroom"), "mushroom", "🍄", 10, 1),
        WorldFood(setOf("cactus"), "cactus", "🌵", 6, 2)
    )

    private val cropEmoji = mapOf(
        "carrot" to "🥕", "tomato" to "🍅", "corn" to "🌽", "strawberry" to "🍓", "melon" to "🍈", "pumpkin" to "🎃",
        "wheat" to "🌾", "rice" to "🍚"
    )

    /** Hunger roughly proportional to the sell price, plus a small happiness bonus. */
    internal fun cropHunger(sellPrice: Int): Int = (sellPrice / 5).coerceIn(5, 30)
    internal fun cropHappiness(sellPrice: Int): Int = (sellPrice / 25).coerceIn(1, 6)

    private fun edibleCropIds(): List<String> =
        CropDefinitions.CROPS.keys.filter { TamaCropEdibility.isEdible(it) }

    private fun catalog(): List<TamaFoodOption> {
        val shop = TamaFoodCatalog.foods.map {
            TamaFoodOption(it.id, it.emoji, it.titleRes, null, null, it.hungerGain, it.happinessGain,
                if (it.price == null) TamaFoodKind.FREE else TamaFoodKind.SHOP, setOf(it.id))
        }
        val crops = edibleCropIds().map { cropId ->
            val price = CropDefinitions.CROPS.getValue(cropId).sellPrice
            val inv = "crop_$cropId"
            TamaFoodOption(inv, cropEmoji[cropId] ?: ItemType.CROP.emoji, null, FarmTradeItemCatalog.displayText(inv), null,
                cropHunger(price), cropHappiness(price), TamaFoodKind.CROP, setOf(inv))
        }
        val world = worldFoods.map {
            TamaFoodOption(it.canonical, it.emoji, null,
                TamaLocalizedText(
                    WorldResourceCatalog.displayName(it.canonical, Locale.ENGLISH) ?: it.canonical,
                    WorldResourceCatalog.displayName(it.canonical, Locale.forLanguageTag("es")) ?: it.canonical
                ), null, it.hunger, it.happiness, TamaFoodKind.WORLD, it.ids)
        }
        return shop + crops + world
    }

    private val all: List<TamaFoodOption> by lazy { catalog() }

    /** Catalog entry for [foodId] (case-insensitive, alias-aware) regardless of ownership. */
    fun definition(foodId: String): TamaFoodOption? {
        val key = foodId.trim().lowercase(Locale.ROOT)
        return all.firstOrNull { key in it.stackIds || it.id == key }
    }

    /** Free foods first, then owned foods in catalog order, stacks merged; quantity null means unlimited. */
    fun foodOptions(pet: TamaPet): List<TamaFoodOption> = foodOptions(pet.inventory)

    fun foodOptions(inventory: List<InventoryItem>): List<TamaFoodOption> {
        val counts = HashMap<String, Int>()
        inventory.forEach { if (it.quantity > 0) counts.merge(it.id.trim().lowercase(Locale.ROOT), it.quantity, Int::plus) }
        val free = all.filter { it.kind == TamaFoodKind.FREE }
        val owned = all.filter { it.kind != TamaFoodKind.FREE }.mapNotNull { option ->
            val total = option.stackIds.sumOf { counts[it] ?: 0 }
            if (total > 0) option.copy(quantity = total) else null
        }
        return free + owned
    }

    /** The option the pet can eat right now for [foodId], or null when unknown or not owned. */
    fun resolve(pet: TamaPet, foodId: String): TamaFoodOption? {
        val def = definition(foodId) ?: return null
        return foodOptions(pet).firstOrNull { it.id == def.id }
    }
}

fun foodOptions(pet: TamaPet): List<TamaFoodOption> = TamaFoodOptions.foodOptions(pet)
