package com.example.llamadroid.chat.cards.media

import com.example.llamadroid.chat.cards.map.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class CardWeatherDay(val label: String, val emoji: String, val maxC: Double, val minC: Double, val rainPct: Int?)

data class CardWeather(
    val place: String,
    val currentTempC: Double,
    val feelsLikeC: Double?,
    val description: String,
    val emoji: String,
    val humidity: Int?,
    val windKmh: Double?,
    val hourlyTempC: List<Double>,
    val days: List<CardWeatherDay>,
)

/** Geocodes with [geocode] (Photon) then reads Open-Meteo. Successful forecasts are cached for 30 minutes in memory. */
class CardWeatherClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val geocode: (String) -> GeoPoint?,
    private val baseUrl: String = BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val at: Long, val value: CardWeather)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Null when the place is unknown, the network fails, or the answer is malformed (nothing is cached then). */
    suspend fun forecast(location: String, days: Int): CardWeather? {
        val n = days.coerceIn(1, 7)
        val key = "${location.trim().lowercase()}|$n"
        if (key.startsWith("|")) return null
        cache[key]?.let { if (clock() - it.at < CACHE_MS) return it.value }
        val result = withContext(Dispatchers.IO) {
            val point = runCatching { geocode(location.trim()) }.getOrNull() ?: return@withContext null
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("latitude", point.lat.toString())
                ?.addQueryParameter("longitude", point.lng.toString())
                ?.addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code")
                ?.addQueryParameter("hourly", "temperature_2m")
                ?.addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                ?.addQueryParameter("forecast_days", n.toString())
                ?.addQueryParameter("timezone", "auto")
                ?.build() ?: return@withContext null
            val body = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull() ?: return@withContext null
            parse(location.trim(), body)
        } ?: return null
        cache[key] = Entry(clock(), result)
        return result
    }

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        const val CACHE_MS = 30 * 60 * 1000L
        private const val HOURLY_POINTS = 24

        private fun JSONArray?.doubles(): List<Double?> =
            if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) null else optDouble(it).takeIf { v -> !v.isNaN() } }

        /** Open-Meteo forecast answer to [CardWeather]; null when the essential fields are missing. */
        fun parse(place: String, json: String): CardWeather? = runCatching {
            val root = JSONObject(json)
            val cur = root.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m")
            val info = WeatherCodes.describe(if (cur.has("weather_code") && !cur.isNull("weather_code")) cur.getInt("weather_code") else null)
            val daily = root.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.optJSONArray("weather_code")
            val max = daily.getJSONArray("temperature_2m_max").doubles()
            val min = daily.getJSONArray("temperature_2m_min").doubles()
            val rain = daily.optJSONArray("precipitation_probability_max").doubles()
            val days = (0 until dates.length()).mapNotNull { i ->
                val hi = max.getOrNull(i) ?: return@mapNotNull null
                val lo = min.getOrNull(i) ?: return@mapNotNull null
                val code = codes?.takeIf { !it.isNull(i) }?.optInt(i)
                CardWeatherDay(dates.getString(i), WeatherCodes.describe(code).emoji, hi, lo, rain.getOrNull(i)?.toInt())
            }
            val hourly = root.optJSONObject("hourly")?.optJSONArray("temperature_2m").doubles().filterNotNull().take(HOURLY_POINTS)
            CardWeather(
                place = place,
                currentTempC = temp,
                feelsLikeC = if (cur.has("apparent_temperature") && !cur.isNull("apparent_temperature")) cur.getDouble("apparent_temperature") else null,
                description = info.description,
                emoji = info.emoji,
                humidity = if (cur.has("relative_humidity_2m") && !cur.isNull("relative_humidity_2m")) cur.getInt("relative_humidity_2m") else null,
                windKmh = if (cur.has("wind_speed_10m") && !cur.isNull("wind_speed_10m")) cur.getDouble("wind_speed_10m") else null,
                hourlyTempC = hourly,
                days = days,
            ).takeIf { days.isNotEmpty() }
        }.getOrNull()
    }
}
