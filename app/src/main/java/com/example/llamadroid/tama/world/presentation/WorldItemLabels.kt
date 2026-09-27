package com.example.llamadroid.tama.world.presentation

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.FarmTradeItemCatalog
import com.example.llamadroid.tama.data.TamaCommerceCatalog
import com.example.llamadroid.tama.data.WorldResourceCatalog
import com.example.llamadroid.tama.data.cropDisplayName
import com.example.llamadroid.tama.data.seedDisplayText
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import java.util.Locale

/** One catalog-backed item label for the journal and actual order outcomes. */
internal fun localizedWorldItemName(context: Context, raw: String): String? {
    val id = raw.trim().lowercase(Locale.ROOT)
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    if (id == "rotten_crop") return context.getString(R.string.tama_item_rotten_crop)
    if (id in CropDefinitions.CROPS) return cropDisplayName(context, id)
    if (id.startsWith("crop_")) {
        val cropId = id.removePrefix("crop_")
        if (cropId in CropDefinitions.CROPS) return cropDisplayName(context, cropId)
    }
    if (id.startsWith("seed_")) {
        val cropId = id.removePrefix("seed_")
        if (cropId in CropDefinitions.CROPS) return seedDisplayText(cropId).resolve(locale)
    }
    WorldResourceCatalog.displayName(id, locale)?.let { return it }
    FarmTradeItemCatalog.displayText(id)?.resolve(locale)?.let { return it }
    for (vendorId in listOf(LegacyLocationAliases.SHOP, LegacyLocationAliases.HOSPITAL, LegacyLocationAliases.ALCHEMIST)) {
        val offer = runCatching { TamaCommerceCatalog.offer(context, id, vendorId) }.getOrNull()
        if (offer != null) return offer.item.name
    }
    return null
}
