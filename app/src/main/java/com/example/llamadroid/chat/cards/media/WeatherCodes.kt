package com.example.llamadroid.chat.cards.media

/** WMO weather interpretation codes (Open-Meteo) to a short English description and an emoji. */
object WeatherCodes {
    data class Info(val description: String, val emoji: String)

    fun describe(code: Int?): Info = when (code) {
        0 -> Info("Clear sky", "☀️")
        1 -> Info("Mainly clear", "🌤️")
        2 -> Info("Partly cloudy", "⛅")
        3 -> Info("Overcast", "☁️")
        45, 48 -> Info("Fog", "🌫️")
        51, 53, 55 -> Info("Drizzle", "🌦️")
        56, 57 -> Info("Freezing drizzle", "🌧️")
        61, 63 -> Info("Rain", "🌧️")
        65 -> Info("Heavy rain", "🌧️")
        66, 67 -> Info("Freezing rain", "🌧️")
        71, 73 -> Info("Snow", "🌨️")
        75 -> Info("Heavy snow", "❄️")
        77 -> Info("Snow grains", "❄️")
        80, 81 -> Info("Rain showers", "🌦️")
        82 -> Info("Violent rain showers", "⛈️")
        85, 86 -> Info("Snow showers", "🌨️")
        95 -> Info("Thunderstorm", "⛈️")
        96, 99 -> Info("Thunderstorm with hail", "⛈️")
        else -> Info("Unknown", "🌡️")
    }
}
