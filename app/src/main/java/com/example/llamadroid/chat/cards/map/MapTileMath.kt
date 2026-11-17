package com.example.llamadroid.chat.cards.map

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class GeoPoint(val lat: Double, val lng: Double)

data class GeoBounds(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double) {
    val center: GeoPoint get() = GeoPoint((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)

    companion object {
        fun of(points: List<GeoPoint>): GeoBounds? {
            if (points.isEmpty()) return null
            return GeoBounds(
                minLat = points.minOf { it.lat }, minLng = points.minOf { it.lng },
                maxLat = points.maxOf { it.lat }, maxLng = points.maxOf { it.lng },
            )
        }
    }
}

data class TileKey(val z: Int, val x: Int, val y: Int)

/** A tile to draw at screen offset ([dx], [dy]) (pixels, relative to the viewport's top-left). */
data class TileSlot(val key: TileKey, val dx: Double, val dy: Double)

data class ScreenPoint(val x: Double, val y: Double)

/** A viewport of [width] x [height] pixels whose top-left is world pixel ([originX], [originY]) at [zoom]. */
data class MapViewport(
    val zoom: Int,
    val originX: Double,
    val originY: Double,
    val width: Int,
    val height: Int,
)

/** Web-Mercator (EPSG:3857) slippy-map math for 256 px OSM raster tiles. Pure Kotlin. */
object MapTileMath {
    const val TILE_SIZE = 256
    const val MAX_ZOOM = 17
    const val SINGLE_PLACE_ZOOM = 15
    private const val MAX_LAT = 85.0511287798

    fun worldSize(zoom: Int): Double = TILE_SIZE.toDouble() * (1L shl zoom).toDouble()

    fun worldX(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * worldSize(zoom)

    fun worldY(lat: Double, zoom: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)) * worldSize(zoom)
    }

    /** Largest zoom (<= [maxZoom]) at which [bounds] fit inside the viewport minus [padding] on every side. */
    fun fitZoom(bounds: GeoBounds, viewW: Int, viewH: Int, padding: Int, maxZoom: Int = MAX_ZOOM): Int {
        val availW = max(1, viewW - 2 * padding).toDouble()
        val availH = max(1, viewH - 2 * padding).toDouble()
        val cap = maxZoom.coerceIn(0, MAX_ZOOM)
        if (bounds.maxLat == bounds.minLat && bounds.maxLng == bounds.minLng) return min(cap, SINGLE_PLACE_ZOOM)
        for (z in cap downTo 0) {
            val spanX = worldX(bounds.maxLng, z) - worldX(bounds.minLng, z)
            val spanY = worldY(bounds.minLat, z) - worldY(bounds.maxLat, z)
            if (spanX <= availW && spanY <= availH) return z
        }
        return 0
    }

    fun viewportFor(bounds: GeoBounds, viewW: Int, viewH: Int, padding: Int, maxZoom: Int = MAX_ZOOM): MapViewport {
        val zoom = fitZoom(bounds, viewW, viewH, padding, maxZoom)
        val c = bounds.center
        return MapViewport(
            zoom = zoom,
            originX = worldX(c.lng, zoom) - viewW / 2.0,
            originY = worldY(c.lat, zoom) - viewH / 2.0,
            width = viewW,
            height = viewH,
        )
    }

    fun project(point: GeoPoint, vp: MapViewport): ScreenPoint =
        ScreenPoint(worldX(point.lng, vp.zoom) - vp.originX, worldY(point.lat, vp.zoom) - vp.originY)

    /** Tiles intersecting [vp]; x wraps around the antimeridian, rows outside the world are dropped. */
    fun visibleTiles(vp: MapViewport): List<TileSlot> {
        val n = 1 shl vp.zoom
        val firstX = floor(vp.originX / TILE_SIZE).toInt()
        val lastX = floor((vp.originX + vp.width - 1) / TILE_SIZE).toInt()
        val firstY = floor(vp.originY / TILE_SIZE).toInt()
        val lastY = floor((vp.originY + vp.height - 1) / TILE_SIZE).toInt()
        val out = ArrayList<TileSlot>()
        for (ty in firstY..lastY) {
            if (ty < 0 || ty >= n) continue
            for (tx in firstX..lastX) {
                out += TileSlot(
                    key = TileKey(vp.zoom, Math.floorMod(tx, n), ty),
                    dx = tx * TILE_SIZE - vp.originX,
                    dy = ty * TILE_SIZE - vp.originY,
                )
            }
        }
        return out
    }
}
