package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.CalloutTone
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.ui.components.AppChromeDefaults

/** Stats: 2-column grid of hairline-separated cells (muted label, big tight number, delta with arrow). */
@Composable
internal fun ChatStatsCard(card: ChatCard.Stats) = CardShell(title = null, contentPadding = PaddingValues(0.dp)) {
    val rows = card.items.chunked(2)
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { r, pair ->
            if (r > 0) CardHairline()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StatCell(pair[0], Modifier.weight(1f))
                if (pair.size > 1) {
                    VerticalDivider(color = chatCardLine())
                    StatCell(pair[1], Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCell(item: ChatCard.Stats.Item, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val size = when { item.value.length <= 8 -> 24.sp; item.value.length <= 11 -> 20.sp; else -> 16.sp }
    Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cardEmoji(item.icon)?.let { Text(it, fontSize = 13.sp, maxLines = 1) }
            Text(item.label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            item.value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        item.delta?.takeIf { it.isNotBlank() }?.let { StatDelta(it.trim(), muted) }
    }
}

/** Delta line: "+" reads as positive (green, ▲), "-" as negative (error, ▼), anything else is neutral. */
@Composable
private fun StatDelta(delta: String, neutral: Color) {
    val up = delta.startsWith("+")
    val down = delta.startsWith("-") || delta.startsWith("−")
    val color = when { up -> chatCardPositiveColor(); down -> MaterialTheme.colorScheme.error; else -> neutral }
    val mark = when { up -> "▲ "; down -> "▼ "; else -> "" }
    Text(mark + delta, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Timeline: rail with connector + node (emoji or ring dot), muted time line above the bold title, muted detail. */
@Composable
internal fun ChatTimelineCard(card: ChatCard.Timeline) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.outlineVariant
    CardShell(title = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            card.items.forEachIndexed { i, item ->
                val first = i == 0
                val last = i == card.items.lastIndex
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .width(24.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                // Rail: runs through the node; the first row starts at the node, the last one ends at it.
                                val x = size.width / 2f
                                val nodeY = 14.dp.toPx()
                                drawLine(line, Offset(x, if (first) nodeY else 0f), Offset(x, if (last) nodeY else size.height), strokeWidth = 2.dp.toPx())
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        val emoji = cardEmoji(item.icon)
                        if (emoji != null) {
                            Box(
                                Modifier.padding(top = 2.dp).size(24.dp).background(scheme.surfaceContainerLow, CircleShape).border(1.dp, line, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text(emoji, fontSize = 13.sp, maxLines = 1) }
                        } else {
                            Box(Modifier.padding(top = 8.dp).size(12.dp).background(scheme.surfaceContainerLow, CircleShape).border(2.dp, scheme.onSurface, CircleShape))
                        }
                    }
                    Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.time?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it, Modifier.padding(top = 3.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(item.title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold))
                        item.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = scheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Callout: tonal panel with a 3 dp coloured left bar, tinted icon + bold title; lines starting with "- " render as
 * bullets and `**bold**` lead-ins are honoured. Warning/danger get a faint tint.
 */
@Composable
internal fun ChatCalloutCard(card: ChatCard.Callout) {
    val scheme = MaterialTheme.colorScheme
    val (icon, accent) = when (card.tone) {
        CalloutTone.INFO -> Icons.Filled.Info to scheme.primary
        CalloutTone.TIP -> Icons.Filled.Lightbulb to chatCardPositiveColor()
        CalloutTone.WARNING -> Icons.Filled.Warning to chatCardWarningColor()
        CalloutTone.DANGER -> Icons.Filled.Error to scheme.error
    }
    val tinted = card.tone == CalloutTone.WARNING || card.tone == CalloutTone.DANGER
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
        shape = AppChromeDefaults.InnerCardShape,
        color = if (tinted) accent.copy(alpha = 0.08f).compositeOver(scheme.surfaceContainerLow) else scheme.surfaceContainerLow,
        border = AppChromeDefaults.pixelCardBorder() ?: BorderStroke(1.dp, chatCardLine())
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Column(
                Modifier.weight(1f).heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    CalloutLead(icon, accent, card.title?.takeIf { it.isNotBlank() })
                }
                CalloutBody(card.text)
            }
        }
    }
}

@Composable
private fun CalloutLead(icon: ImageVector, accent: Color, title: String?) {
    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CalloutBody(text: String) {
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
    val lines = text.lines()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("- ") || line.startsWith("• ")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = body)
                    Text(boldSpans(line.drop(2).trim()), Modifier.weight(1f), style = body)
                }
            } else Text(boldSpans(line), style = body)
        }
    }
}

/** `**lead-in**` → bold span; an unmatched marker stays literal. */
private fun boldSpans(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    if (parts.size < 3 || parts.size % 2 == 0) { append(s); return@buildAnnotatedString }
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p) }
}

/** Host of [url] without a leading "www.", or the url itself when it cannot be parsed. */
internal fun linkHost(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

/** Links: hairline rows with a favicon-like initial tile (or emoji), title, muted host, note and a chevron. */
@Composable
internal fun ChatLinksCard(card: ChatCard.Links) {
    val uriHandler = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    CardShell(title = null, contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth()) {
            card.items.forEachIndexed { i, item ->
                val host = linkHost(item.url)
                if (i > 0) CardHairline(Modifier.padding(horizontal = 14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uriHandler.openUri(item.url) } }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        val emoji = cardEmoji(item.icon)
                        if (emoji != null) Text(emoji, fontSize = 18.sp, maxLines = 1)
                        else Text(host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(item.title?.takeIf { it.isNotBlank() } ?: host, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(host, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        item.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.chat_card_link_open), tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
