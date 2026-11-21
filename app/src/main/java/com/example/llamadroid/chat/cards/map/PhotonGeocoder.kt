package com.example.llamadroid.chat.cards.map

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Geocodes a place name with the public Photon service (https://photon.komoot.io). Results (including "not found")
 * are cached in memory and, when [cacheDir] is set, on disk so a message does not query again. Blocking; IO thread only.
 */
class PhotonGeocoder(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val cacheDir: File? = null,
) {
    private val memory = ConcurrentHashMap<String, GeoPoint>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    /** Null when the name could not be resolved (unknown place, offline, malformed answer). */
    fun geocode(name: String): GeoPoint? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        memory[key]?.let { return it }
        if (misses.contains(key)) return null
        readDisk(key)?.let { memory[key] = it; return it }
        val body = fetch(name.trim()) ?: return null // network failure is not cached
        val point = parseFirst(body)
        if (point != null) {
            memory[key] = point
            writeDisk(key, point)
        } else {
            misses.add(key)
        }
        return point
    }

    private fun fetch(query: String): String? = runCatching {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("limit", "1")
            ?.build() ?: return null
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun diskFile(key: String): File? = cacheDir?.let { File(it, sha(key) + ".txt") }

    private fun readDisk(key: String): GeoPoint? = runCatching {
        val parts = diskFile(key)?.takeIf { it.isFile }?.readText()?.split(',') ?: return null
        GeoPoint(parts[0].toDouble(), parts[1].toDouble()).takeIf { validCoords(it.lat, it.lng) }
    }.getOrNull()

    private fun writeDisk(key: String, p: GeoPoint) {
        runCatching {
            val f = diskFile(key) ?: return
            f.parentFile?.mkdirs()
            f.writeText("${p.lat},${p.lng}")
        }
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/api/"

        fun validCoords(lat: Double, lng: Double): Boolean =
            lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

        /** First feature of a Photon GeoJSON answer; GeoJSON order is [lng, lat]. */
        fun parseFirst(json: String): GeoPoint? = runCatching {
            val features = JSONObject(json).optJSONArray("features") ?: return null
            if (features.length() == 0) return null
            val coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val lng = coords.getDouble(0)
            val lat = coords.getDouble(1)
            if (validCoords(lat, lng)) GeoPoint(lat, lng) else null
        }.getOrNull()
    }
}
