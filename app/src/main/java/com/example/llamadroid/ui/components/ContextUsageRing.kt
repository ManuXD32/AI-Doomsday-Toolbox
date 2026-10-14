package com.example.llamadroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ring color for a context usage percentage: primary, then tertiary at warn, then error. */
@Composable
internal fun contextUsageRingColor(percentUsed: Int?, warnAtPercent: Int, errorAtPercent: Int): Color = when {
    percentUsed != null && percentUsed >= errorAtPercent -> MaterialTheme.colorScheme.error
    percentUsed != null && percentUsed >= warnAtPercent -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Shared circular context-usage indicator (agent console and native chat top bar).
 * A 28-32dp [size] fits a TopAppBar action slot; the touch target stays at least 48dp when clickable.
 */
@Composable
fun ContextUsageRing(
    percentUsed: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isWorking: Boolean = false,
    warnAtPercent: Int = 75,
    errorAtPercent: Int = 90,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val color = contextUsageRingColor(percentUsed, warnAtPercent, errorAtPercent)
    val progress = percentUsed?.div(100f)?.coerceIn(0f, 1f)
    val compact = size < 40.dp
    val stroke = if (compact) 3.dp else 4.dp
    val touch = if (onClick != null) maxOf(size, 48.dp) else size
    var base = modifier.size(touch)
    if (onClick != null) base = base.clickable(role = Role.Button, onClick = onClick)
    if (contentDescription != null) base = base.semantics { this.contentDescription = contentDescription }
    Box(modifier = base, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            if (progress == null && isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = stroke,
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = stroke,
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Text(
                text = percentUsed?.let { "$it%" } ?: if (isWorking) "…" else "--",
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (compact) 8.sp else MaterialTheme.typography.labelSmall.fontSize,
                lineHeight = if (compact) 9.sp else MaterialTheme.typography.labelSmall.lineHeight,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = if (compact) 2.dp else 0.dp)
            )
        }
    }
}
