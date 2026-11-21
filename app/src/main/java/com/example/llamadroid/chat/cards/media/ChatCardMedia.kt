package com.example.llamadroid.chat.cards.media

import android.content.Context
import com.example.llamadroid.chat.cards.map.ChatMapServices
import com.example.llamadroid.chat.cards.map.MapTileCache
import java.io.File

/** Process-wide image resolver and weather client for chat cards (caches live under cacheDir). */
object ChatCardMedia {
    @Volatile private var images: CardImageResolver? = null
    @Volatile private var weather: CardWeatherClient? = null

    private fun client() = MapTileCache.defaultClient().newBuilder()
        .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun imageResolver(context: Context): CardImageResolver = images ?: synchronized(this) {
        images ?: HttpCardImageResolver(
            client = client(),
            userAgent = ChatMapServices.userAgent,
            cacheFile = File(context.applicationContext.cacheDir, "chat_card_images/queries.json"),
        ).also { images = it }
    }

    fun weather(context: Context): CardWeatherClient = weather ?: synchronized(this) {
        weather ?: run {
            val geocoder = ChatMapServices.geocoder(context)
            CardWeatherClient(client(), ChatMapServices.userAgent, geocoder::geocode)
        }.also { weather = it }
    }
}
