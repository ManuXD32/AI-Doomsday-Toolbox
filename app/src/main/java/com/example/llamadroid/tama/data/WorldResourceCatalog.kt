package com.example.llamadroid.tama.data

import java.util.Locale

/** Canonical labels shared by gathered inventory, inspectors, logs and contextual actions. */
object WorldResourceCatalog {
    private val labels = mapOf(
        "berry" to TamaLocalizedText("Wild berries", "Bayas silvestres"),
        "berries" to TamaLocalizedText("Wild berries", "Bayas silvestres"),
        "wood" to TamaLocalizedText("Wood", "Madera"),
        "stone" to TamaLocalizedText("Stone", "Piedra"),
        "herb" to TamaLocalizedText("Wild herbs", "Hierbas silvestres"),
        "mushroom" to TamaLocalizedText("Mushroom", "Seta"),
        "reed" to TamaLocalizedText("Reeds", "Juncos"),
        "cactus" to TamaLocalizedText("Cactus fruit", "Fruto de cactus"),
        "shell" to TamaLocalizedText("Seashell", "Concha marina"),
        "flower_meadow" to TamaLocalizedText("Meadow flowers", "Flores de pradera"),
        "bush" to TamaLocalizedText("Twigs", "Ramitas"),
        "water" to TamaLocalizedText("Water", "Agua"),
        "fertilizer" to TamaLocalizedText("Fertilizer", "Fertilizante"),
        "axe" to TamaLocalizedText("Axe", "Hacha"),
        "pickaxe" to TamaLocalizedText("Pickaxe", "Pico")
    )

    fun displayName(id: String, locale: Locale): String? = labels[id]?.resolve(locale)

    fun item(id: String): InventoryItem = InventoryItem(id, labels[id]?.en ?: id,
        when {
            id.startsWith("crop_") -> ItemType.CROP
            id.startsWith("seed_") -> ItemType.SEED
            id in setOf("berry", "berries", "mushroom", "cactus") -> ItemType.FOOD
            id in setOf("axe", "pickaxe") -> ItemType.TOOL
            else -> ItemType.MATERIAL
        }, durability = if (id in setOf("axe", "pickaxe")) 100 else null,
        maxDurability = if (id in setOf("axe", "pickaxe")) 100 else null)
}
