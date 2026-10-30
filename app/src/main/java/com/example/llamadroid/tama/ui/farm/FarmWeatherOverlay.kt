package com.example.llamadroid.tama.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

/**
 * Day/night/weather colour wash over the field background. It is drawn behind the plot grid (the tiles stay at full
 * brightness) and its alpha is capped by [farmCapTintAlpha], so crops and icons stay readable at night.
 */
@Composable
internal fun FarmWeatherTint(tintArgb: Int, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = Color(farmCapTintAlpha(tintArgb)),
        animationSpec = if (reducedMotion) snap() else tween(1_500),
        label = "farmSkyTint"
    )
    Box(modifier = modifier.fillMaxSize().background(color).clearAndSetSemantics {})
}

private fun farmWeatherEmoji(label: FarmWeatherLabel): String = when (label) {
    FarmWeatherLabel.SUNNY -> "☀️"
    FarmWeatherLabel.CLOUDY -> "☁️"
    FarmWeatherLabel.WINDY -> "💨"
    FarmWeatherLabel.RAIN -> "🌧️"
    FarmWeatherLabel.NIGHT -> "🌙"
}

private fun farmWeatherTextRes(label: FarmWeatherLabel): Int = when (label) {
    FarmWeatherLabel.SUNNY -> R.string.tama_farm_weather_sunny
    FarmWeatherLabel.CLOUDY -> R.string.tama_farm_weather_cloudy
    FarmWeatherLabel.WINDY -> R.string.tama_farm_weather_windy
    FarmWeatherLabel.RAIN -> R.string.tama_farm_weather_rain
    FarmWeatherLabel.NIGHT -> R.string.tama_farm_weather_night
}

/** Header chip showing the current weather; the longest label ("Rain · watering your crops") ellipsizes on narrow phones. */
@Composable
internal fun FarmWeatherChip(label: FarmWeatherLabel, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 150.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = farmWeatherEmoji(label), fontSize = 12.sp, maxLines = 1)
            Text(
                text = stringResource(farmWeatherTextRes(label)),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
