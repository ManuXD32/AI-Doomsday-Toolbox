package com.example.llamadroid.tama.data

import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

const val TAMA_MARKET_PRICE_DROP_FULL_VOLUME = 64

data class TamaMarketQuote(
    val itemId: String,
    val displayText: TamaLocalizedText,
    val assetPath: String,
    val currentPrice: Int,
    val maxPrice: Int,
    val minPrice: Int,
    val unitsSoldSinceRefresh: Int,
    val quoteWeekKey: String
)

data class TamaMarketBoard(
    val quotes: List<TamaMarketQuote>,
    val quoteWeekKey: String,
    val nextRefreshAt: Long
)

object TamaMarketPricing {
    fun maxPrice(itemId: String): Int =
        FarmTradeItemCatalog.sellPrice(itemId).coerceAtLeast(1)

    fun minPrice(itemId: String): Int {
        val maxPrice = maxPrice(itemId)
        if (itemId.startsWith("crop_")) {
            val cropId = itemId.removePrefix("crop_")
            val seedPrice = CropDefinitions.CROPS[cropId]?.seedPrice ?: return (maxPrice / 2).coerceAtLeast(1)
            return floor(seedPrice * 0.95f).toInt().coerceAtLeast(1)
        }
        return (maxPrice / 2).coerceAtLeast(1)
    }

    fun priceForWeeklySales(itemId: String, unitsSold: Int): Int {
        val maxPrice = maxPrice(itemId)
        val minPrice = minPrice(itemId).coerceAtMost(maxPrice)
        val sold = unitsSold.coerceIn(0, TAMA_MARKET_PRICE_DROP_FULL_VOLUME)
        val dropRange = maxPrice - minPrice
        val drop = (dropRange * (sold.toFloat() / TAMA_MARKET_PRICE_DROP_FULL_VOLUME)).roundToInt()
        return (maxPrice - drop).coerceIn(minPrice, maxPrice)
    }

    fun quoteWeekKey(now: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun nextFridayRefreshAt(now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        do {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        } while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY)
        return calendar.timeInMillis
    }
}
