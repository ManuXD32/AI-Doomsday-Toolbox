package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.media.CardWeather
import com.example.llamadroid.chat.cards.media.ChatCardMedia
import com.example.llamadroid.ui.components.AppChromeDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private sealed interface WeatherLoad {
    data object Loading : WeatherLoad
    data object Failed : WeatherLoad
    data class Ok(val weather: CardWeather) : WeatherLoad
}

private fun deg(v: Double): String = "${Math.round(v)}°"

private fun shortDay(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}.getOrDefault(iso.takeLast(5))

@Composable
internal fun ChatWeatherCard(card: ChatCard.Weather) {
    val context = LocalContext.current
    val state by produceState<WeatherLoad>(WeatherLoad.Loading, card.location, card.days) {
        val result = withContext(Dispatchers.IO) {
            runCatching { ChatCardMedia.weather(context).forecast(card.location, card.days) }.getOrNull()
        }
        value = if (result == null) WeatherLoad.Failed else WeatherLoad.Ok(result)
    }
    CardShell(title = null) {
        when (val s = state) {
            WeatherLoad.Loading -> WeatherSkeleton()
            WeatherLoad.Failed -> Text(
                stringResource(R.string.chat_card_weather_unavailable, card.location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            is WeatherLoad.Ok -> WeatherBody(s.weather)
        }
    }
}

@Composable
private fun WeatherSkeleton() {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val label = stringResource(R.string.chat_card_weather_loading)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(44.dp).height(44.dp).background(tone, RoundedCornerShape(50)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(80.dp).height(22.dp).background(tone, RoundedCornerShape(6.dp)))
                Box(Modifier.width(120.dp).height(12.dp).background(tone, RoundedCornerShape(6.dp)))
            }
        }
        Box(Modifier.fillMaxWidth().height(36.dp).background(tone, RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun WeatherBody(w: CardWeather) {
    val scheme = MaterialTheme.colorScheme
    val feels = w.feelsLikeC?.let { stringResource(R.string.chat_card_weather_feels, w.description, deg(it)) } ?: w.description
    val humidity = w.humidity?.toString()
    val wind = w.windKmh?.let { Math.round(it).toString() }
    val extra = when {
        humidity != null && wind != null -> stringResource(R.string.chat_card_weather_humidity_wind, humidity, wind)
        humidity != null -> stringResource(R.string.chat_card_weather_humidity, humidity)
        wind != null -> stringResource(R.string.chat_card_weather_wind, wind)
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(w.emoji, fontSize = 38.sp)
        Column(Modifier.weight(1f)) {
            Text(deg(w.currentTempC), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(feels, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(w.place, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (extra != null) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (w.hourlyTempC.size >= 2) Sparkline(w.hourlyTempC)
    if (w.days.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(w.days, key = { i, d -> "$i:${d.label}" }) { _, d ->
                Column(
                    Modifier.width(56.dp).background(scheme.surfaceContainerHighest, AppChromeDefaults.CompactShape).padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(shortDay(d.label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.emoji, fontSize = 20.sp)
                    Text(deg(d.maxC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(deg(d.minC), fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                    if (d.rainPct != null) {
                        Text("💧${d.rainPct}%", fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.chat_card_weather_source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
}

@Composable
private fun Sparkline(values: List<Double>) {
    val color = MaterialTheme.colorScheme.primary
    val lo = values.min()
    val span = (values.max() - lo).takeIf { it > 0.01 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val pad = 3.dp.toPx()
        val h = size.height - pad * 2
        val step = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = pad + h * (1f - ((v - lo) / span).toFloat())
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)), startY = 0f, endY = size.height))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
