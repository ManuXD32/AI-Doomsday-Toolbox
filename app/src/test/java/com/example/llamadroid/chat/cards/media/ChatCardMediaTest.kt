package com.example.llamadroid.chat.cards.media

import com.example.llamadroid.chat.cards.map.GeoPoint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ChatCardMediaTest {
    private val wiki = """{"query":{"pages":{"11":{"index":2,"thumbnail":{"source":"https://upload.wikimedia.org/b.jpg"}},
        "10":{"index":1,"thumbnail":{"source":"https://upload.wikimedia.org/a.jpg"}},"12":{"index":3}}}}"""

    @Test fun wikipediaPicksBestRankedHttpsThumbnail() {
        assertEquals("https://upload.wikimedia.org/a.jpg", HttpCardImageResolver.parseWikipedia(wiki))
        assertNull(HttpCardImageResolver.parseWikipedia("""{"query":{"pages":{"1":{"thumbnail":{"source":"http://x/a.jpg"}}}}}"""))
        assertNull(HttpCardImageResolver.parseWikipedia("garbage"))
    }

    @Test fun openversePrefersThumbnailThenUrlAndRejectsHttp() {
        assertEquals("https://t/1.jpg", HttpCardImageResolver.parseOpenverse("""{"results":[{"thumbnail":"https://t/1.jpg","url":"https://u/1.jpg"}]}"""))
        assertEquals("https://u/1.jpg", HttpCardImageResolver.parseOpenverse("""{"results":[{"thumbnail":"http://t/1.jpg","url":"https://u/1.jpg"}]}"""))
        assertNull(HttpCardImageResolver.parseOpenverse("""{"results":[]}"""))
    }

    @Test fun weatherCodesMapKnownAndUnknown() {
        assertEquals("Clear sky", WeatherCodes.describe(0).description)
        assertEquals("Thunderstorm", WeatherCodes.describe(95).description)
        assertEquals("Unknown", WeatherCodes.describe(1234).description)
        assertEquals("Unknown", WeatherCodes.describe(null).description)
    }

    private val forecast = """{"current":{"temperature_2m":21.5,"apparent_temperature":20.0,"relative_humidity_2m":55,"wind_speed_10m":12.3,"weather_code":2},
        "hourly":{"temperature_2m":[20.1,19.5,18.0]},
        "daily":{"time":["2026-11-14","2026-11-15"],"weather_code":[2,61],"temperature_2m_max":[22.0,18.5],"temperature_2m_min":[12.0,null],"precipitation_probability_max":[10,80]}}"""

    @Test fun weatherParsesCurrentHourlyAndDays() {
        val w = CardWeatherClient.parse("Madrid", forecast)!!
        assertEquals("Partly cloudy", w.description)
        assertEquals(21.5, w.currentTempC, 1e-9)
        assertEquals(55, w.humidity)
        assertEquals(listOf(20.1, 19.5, 18.0), w.hourlyTempC)
        assertEquals(1, w.days.size) // second day has a null minimum and is dropped
        assertEquals(10, w.days[0].rainPct)
        assertNull(CardWeatherClient.parse("X", """{"current":{}}"""))
    }

    private fun server(body: (String) -> String, hits: AtomicInteger): Pair<ServerSocket, Int> {
        val ss = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    hits.incrementAndGet()
                    val b = body(line).toByteArray()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray() + b)
                    s.close()
                }
            }
        }
        return ss to ss.localPort
    }

    @Test fun resolverFallsBackToOpenverseAndCachesSuccess() = runBlocking {
        val hits = AtomicInteger()
        val (wikiSrv, wikiPort) = server({ """{"query":{}}""" }, hits)
        val (ovSrv, ovPort) = server({ """{"results":[{"thumbnail":"https://t/x.jpg"}]}""" }, hits)
        val dir = Files.createTempDirectory("cardimg").toFile()
        val file = java.io.File(dir, "q.json")
        try {
            val r = HttpCardImageResolver(OkHttpClient(), "UA", file, "http://127.0.0.1:$wikiPort/w", "http://127.0.0.1:$ovPort/v1/images/")
            assertEquals("https://t/x.jpg", r.resolve("Eiffel Tower"))
            val after = hits.get()
            assertEquals("https://t/x.jpg", r.resolve("eiffel tower"))
            assertEquals(after, hits.get())
            assertNotNull(file.takeIf { it.isFile })
            // a fresh resolver reads the disk cache without network
            val r2 = HttpCardImageResolver(OkHttpClient(), "UA", file, "http://127.0.0.1:1/", "http://127.0.0.1:1/")
            assertEquals("https://t/x.jpg", r2.resolve("Eiffel Tower"))
        } finally { wikiSrv.close(); ovSrv.close(); dir.deleteRecursively() }
    }

    @Test fun resolverDoesNotCacheFailures() = runBlocking {
        val r = HttpCardImageResolver(OkHttpClient(), "UA", null, "http://127.0.0.1:1/", "http://127.0.0.1:1/")
        assertNull(r.resolve("nothing"))
        assertNull(r.resolve(""))
    }

    @Test fun weatherClientCachesForThirtyMinutes() = runBlocking {
        val hits = AtomicInteger()
        val (srv, port) = server({ forecast }, hits)
        try {
            var now = 1_000L
            val c = CardWeatherClient(OkHttpClient(), "UA", { GeoPoint(40.0, -3.7) }, "http://127.0.0.1:$port/v1/forecast", { now })
            assertNotNull(c.forecast("Madrid", 2))
            assertNotNull(c.forecast("madrid", 2))
            assertEquals(1, hits.get())
            now += CardWeatherClient.CACHE_MS + 1
            assertNotNull(c.forecast("Madrid", 2))
            assertEquals(2, hits.get())
            assertNull(CardWeatherClient(OkHttpClient(), "UA", { null }, "http://127.0.0.1:$port/").forecast("Nowhere", 3))
        } finally { srv.close() }
    }
}
