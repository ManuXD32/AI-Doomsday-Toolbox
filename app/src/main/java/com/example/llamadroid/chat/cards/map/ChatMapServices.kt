package com.example.llamadroid.chat.cards.map

import android.content.Context
import com.example.llamadroid.BuildConfig
import java.io.File

/** Process-wide tile cache and geocoder shared by every map card (disk caches live under cacheDir). */
object ChatMapServices {
    val userAgent: String get() = "AI-Doomsday-Toolbox/${BuildConfig.VERSION_NAME} (Android)"

    @Volatile private var tiles: MapTileCache? = null
    @Volatile private var geocoder: PhotonGeocoder? = null

    fun tiles(context: Context): MapTileCache = tiles ?: synchronized(this) {
        tiles ?: MapTileCache(
            dir = File(context.applicationContext.cacheDir, "chat_map_tiles"),
            client = MapTileCache.defaultClient(),
            userAgent = userAgent,
        ).also { tiles = it }
    }

    fun geocoder(context: Context): PhotonGeocoder = geocoder ?: synchronized(this) {
        geocoder ?: PhotonGeocoder(
            client = MapTileCache.defaultClient(),
            userAgent = userAgent,
            cacheDir = File(context.applicationContext.cacheDir, "chat_map_geocode"),
        ).also { geocoder = it }
    }
}
