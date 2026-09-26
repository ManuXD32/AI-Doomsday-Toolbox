package com.example.llamadroid.tama.data

import androidx.annotation.StringRes
import com.example.llamadroid.R

/** Nutrition and prices shared by the feeding UI, shop and watch action adapter. */
data class TamaFoodDefinition(
    val id: String,
    val emoji: String,
    @StringRes val titleRes: Int,
    val hungerGain: Int,
    val happinessGain: Int,
    val price: Int? = null
)

object TamaFoodCatalog {
    val foods = listOf(
        TamaFoodDefinition("lettuce", "🥬", R.string.tama_food_lettuce, 5, 0),
        TamaFoodDefinition("candy", "🍬", R.string.tama_food_candy, 0, 1),
        TamaFoodDefinition("apple", "🍎", R.string.tama_food_apple, 15, 5, 10),
        TamaFoodDefinition("bread", "🍞", R.string.tama_food_bread, 25, 3, 15),
        TamaFoodDefinition("cake", "🎂", R.string.tama_food_cake, 10, 25, 25),
        TamaFoodDefinition("pizza", "🍕", R.string.tama_food_pizza, 30, 10, 30),
        TamaFoodDefinition("burger", "🍔", R.string.tama_food_burger, 35, 8, 35),
        TamaFoodDefinition("sushi", "🍣", R.string.tama_food_sushi, 20, 15, 40),
        TamaFoodDefinition("donut", "🍩", R.string.tama_food_donut, 5, 20, 20),
        TamaFoodDefinition("salad", "🥗", R.string.tama_food_salad, 20, 2, 12)
    )
    val shopFoods: List<TamaFoodDefinition> = foods.filter { it.price != null }
    val freeFoods: List<TamaFoodDefinition> = foods.filter { it.price == null }
    fun byId(id: String): TamaFoodDefinition? = foods.firstOrNull { it.id == id }
}
