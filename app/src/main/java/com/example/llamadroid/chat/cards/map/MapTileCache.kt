package com.example.llamadroid.chat.cards.map

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenStreetMap raster tile fetcher with a disk cache capped at [maxBytes] (oldest-touched files are evicted first).
 * Blocking; call from an IO thread. Returns null when the tile cannot be loaded (offline, HTTP error).
 */
class MapTileCache(
    private val dir: File,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val maxBytes: Long = MAX_CACHE_BYTES,
    private val urlTemplate: String = OSM_TILE_URL,
) {
    private val lock = Any()

    fun get(key: TileKey): ByteArray? {
        val file = File(dir, "${key.z}_${key.x}_${key.y}.png")
        synchronized(lock) {
            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                return runCatching { file.readBytes() }.getOrNull()
            }
        }
        val bytes = download(key) ?: return null
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
                trimLocked()
            }
        }
        return bytes
    }

    private fun download(key: TileKey): ByteArray? = runCatching {
        val url = urlTemplate.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_TILE_BYTES) return@use null
            body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_TILE_BYTES }
        }
    }.getOrNull()

    private fun trimLocked() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        const val MAX_CACHE_BYTES = 50L * 1024 * 1024
        const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val MAX_TILE_BYTES = 1_000_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
