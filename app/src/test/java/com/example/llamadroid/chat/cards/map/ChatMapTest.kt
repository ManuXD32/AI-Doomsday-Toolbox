package com.example.llamadroid.chat.cards.map

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ChatMapTest {
    @Test
    fun worldPixelsMatchKnownTiles() {
        // Greenwich / equator at zoom 1 is the shared corner of the four tiles: world pixel (256, 256).
        assertEquals(256.0, MapTileMath.worldX(0.0, 1), 1e-6)
        assertEquals(256.0, MapTileMath.worldY(0.0, 1), 1e-6)
        // Well-known tile: Berlin (52.52, 13.405) at zoom 10 is tile 550/335.
        assertEquals(550, (MapTileMath.worldX(13.405, 10) / 256).toInt())
        assertEquals(335, (MapTileMath.worldY(52.52, 10) / 256).toInt())
    }

    @Test
    fun fitZoomKeepsBoundsInsideViewport() {
        val b = GeoBounds(48.80, 2.25, 48.90, 2.42) // Paris
        val z = MapTileMath.fitZoom(b, 768, 384, 32)
        assertTrue(MapTileMath.worldX(b.maxLng, z) - MapTileMath.worldX(b.minLng, z) <= 768 - 64)
        assertTrue(MapTileMath.worldY(b.minLat, z) - MapTileMath.worldY(b.maxLat, z) <= 384 - 64)
        // one zoom level more must not fit
        if (z < MapTileMath.MAX_ZOOM) {
            val over = MapTileMath.worldX(b.maxLng, z + 1) - MapTileMath.worldX(b.minLng, z + 1) > 768 - 64 ||
                MapTileMath.worldY(b.minLat, z + 1) - MapTileMath.worldY(b.maxLat, z + 1) > 384 - 64
            assertTrue(over)
        }
    }

    @Test
    fun singlePointUsesStreetZoomAndCentersPin() {
        val p = GeoPoint(40.4168, -3.7038)
        val b = GeoBounds.of(listOf(p))!!
        val vp = MapTileMath.viewportFor(b, 768, 384, 32)
        assertEquals(15, vp.zoom)
        val s = MapTileMath.project(p, vp)
        assertEquals(384.0, s.x, 1e-6)
        assertEquals(192.0, s.y, 1e-6)
    }

    @Test
    fun visibleTilesCoverViewportAndStayBounded() {
        val vp = MapTileMath.viewportFor(GeoBounds.of(listOf(GeoPoint(48.8, 2.3), GeoPoint(48.9, 2.4)))!!, 768, 384, 32)
        val tiles = MapTileMath.visibleTiles(vp)
        assertTrue(tiles.size in 1..12)
        val n = 1 shl vp.zoom
        assertTrue(tiles.all { it.key.x in 0 until n && it.key.y in 0 until n })
        assertTrue(tiles.all { it.dx < 768 && it.dy < 384 && it.dx > -256 && it.dy > -256 })
    }

    @Test
    fun worldWrapsAndPolesDropRows() {
        val vp = MapViewport(zoom = 1, originX = -100.0, originY = -100.0, width = 300, height = 300)
        val tiles = MapTileMath.visibleTiles(vp)
        assertTrue(tiles.all { it.key.y in 0..1 && it.key.x in 0..1 })
        assertTrue(tiles.any { it.key.x == 1 && it.dx < 0 }) // tile -1 wraps to 1
    }

    @Test
    fun parsesPhotonFeature() {
        val json = """{"features":[{"geometry":{"coordinates":[2.3522,48.8566],"type":"Point"}}],"type":"FeatureCollection"}"""
        val p = PhotonGeocoder.parseFirst(json)
        assertNotNull(p)
        assertEquals(48.8566, p!!.lat, 1e-9)
        assertEquals(2.3522, p.lng, 1e-9)
        assertNull(PhotonGeocoder.parseFirst("""{"features":[]}"""))
        assertNull(PhotonGeocoder.parseFirst("not json"))
        assertNull(PhotonGeocoder.parseFirst("""{"features":[{"geometry":{"coordinates":[500,95]}}]}"""))
    }

    @Test
    fun geocoderQueriesFakeServerOnceAndCaches() {
        val body = """{"features":[{"geometry":{"coordinates":[-3.7038,40.4168]}}]}""".toByteArray()
        val server = FakeHttpServer { 200 to body }
        try {
            val dir = Files.createTempDirectory("geo").toFile()
            val g = PhotonGeocoder(OkHttpClient(), "ADT-test", "http://127.0.0.1:${server.port}/api/", dir)
            val p = g.geocode("Plaza Mayor, Madrid")
            assertEquals(40.4168, p!!.lat, 1e-9)
            assertEquals("ADT-test", server.lastHeaders["user-agent"])
            assertTrue(server.lastPath!!.startsWith("/api/"))
            assertTrue(server.lastPath!!.substringAfter('?', "").contains("limit=1"))
            g.geocode("plaza mayor, madrid")
            assertEquals(1, server.hits)
            // a fresh instance reads the disk cache
            PhotonGeocoder(OkHttpClient(), "ADT-test", "http://127.0.0.1:${server.port}/api/", dir).geocode("Plaza Mayor, Madrid")
            assertEquals(1, server.hits)
        } finally {
            server.close()
        }
    }

    @Test
    fun geocoderReturnsNullOnServerError() {
        val server = FakeHttpServer { 500 to ByteArray(0) }
        try {
            val g = PhotonGeocoder(OkHttpClient(), "ADT-test", "http://127.0.0.1:${server.port}/api/")
            assertNull(g.geocode("Nowhere"))
        } finally {
            server.close()
        }
    }

    @Test
    fun tileCacheDownloadsReusesAndTrims() {
        val server = FakeHttpServer { 200 to ByteArray(1000) { 7 } }
        try {
            val dir = Files.createTempDirectory("tiles").toFile()
            val cache = MapTileCache(dir, OkHttpClient(), "ADT-test", maxBytes = 2500, urlTemplate = "http://127.0.0.1:${server.port}/{z}/{x}/{y}.png")
            assertEquals(1000, cache.get(TileKey(3, 1, 1))!!.size)
            cache.get(TileKey(3, 1, 1))
            assertEquals(1, server.hits)
            cache.get(TileKey(3, 1, 2)); cache.get(TileKey(3, 1, 3)); cache.get(TileKey(3, 1, 4))
            assertTrue(cache.sizeBytes() <= 2500)
        } finally {
            server.close()
        }
    }

    @Test
    fun leafletPageEscapesPlaceText() {
        val page = LeafletPageBuilder.build(listOf(ResolvedPlace(0, "</script><b>x", "n", GeoPoint(1.0, 2.0))), route = true)
        assertTrue(!page.contains("</script><b>"))
        assertTrue(page.contains("ROUTE=true"))
        assertTrue(page.contains("/leaflet/leaflet.js"))
    }
}

/** Minimal HTTP/1.1 server on a plain ServerSocket (HttpServer is not on the Android unit-test classpath). */
private class FakeHttpServer(private val handler: (path: String) -> Pair<Int, ByteArray>) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val requestCount = AtomicInteger()
    private val lastPathRef = AtomicReference<String?>()
    private val lastHeadersRef = AtomicReference<Map<String, String>>(emptyMap())

    val port: Int get() = server.localPort
    val hits: Int get() = requestCount.get()
    val lastPath: String? get() = lastPathRef.get()
    val lastHeaders: Map<String, String> get() = lastHeadersRef.get()

    init {
        thread(isDaemon = true, name = "fake-http-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true, name = "fake-http-conn") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val path = requestLine.split(" ").getOrElse(1) { "/" }
                requestCount.incrementAndGet()
                lastPathRef.set(path)
                lastHeadersRef.set(headers)
                val (code, body) = handler(path)
                val reason = if (code == 200) "OK" else "Error"
                val out = it.getOutputStream()
                out.write("HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
