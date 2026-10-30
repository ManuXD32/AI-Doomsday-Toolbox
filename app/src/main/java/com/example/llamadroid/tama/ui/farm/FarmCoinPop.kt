package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

/** One coin fly-up: [amount] earned, [id] distinguishes consecutive sales so each replays the animation. */
internal data class FarmCoinGain(val id: Long, val amount: Long)

/**
 * "+N coins" that rises from the bottom of the store list and fades out. Skipped entirely when the system
 * animator scale is 0 (reduced motion); the sale toast still reports the sale.
 */
@Composable
internal fun BoxScope.FarmCoinFlyUp(gain: FarmCoinGain, reducedMotion: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    if (reducedMotion) {
        LaunchedEffect(gain.id) { onDone() }
        return
    }
    val coin = remember { loadFarmFxBitmap(context, "fx_pop_coin") }
    val progress = remember(gain.id) { Animatable(0f) }
    LaunchedEffect(gain.id) {
        progress.animateTo(1f, tween(1200, easing = LinearOutSlowInEasing))
        onDone()
    }
    Surface(
        color = Color(0xFFFFD54F),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
            .graphicsLayer {
                translationY = -progress.value * 140.dp.toPx()
                alpha = (1f - ((progress.value - 0.6f) / 0.4f)).coerceIn(0f, 1f)
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coin != null) {
                Image(
                    bitmap = coin,
                    contentDescription = null,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.padding(end = 6.dp).size(24.dp)
                )
            }
            Text(
                text = stringResource(R.string.tama_farm_pop_coins, gain.amount),
                color = Color(0xFF3E2723),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1
            )
        }
    }
}
