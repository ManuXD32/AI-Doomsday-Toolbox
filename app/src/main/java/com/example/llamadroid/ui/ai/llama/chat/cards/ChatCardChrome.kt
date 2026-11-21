package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.theme.LocalAppVisualStyle

// Frontier-UI / ChatGPT look: calm near-monochrome chrome (tonal surface + 1 dp hairline), ink primary button,
// hairline-separated rows. Colour only comes from emoji, semantic tones and content. See .agents/2026-11-14-r10-design.md.

/** True when the active theme (light/dark toggle of the app, not the system) is dark. */
@Composable
internal fun isDarkCardTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Fixed accessible amber for warnings (the theme has no amber role). */
@Composable
internal fun chatCardWarningColor(): Color = if (isDarkCardTheme()) Color(0xFFFFB74D) else Color(0xFFB26A00)

/** Green for positive deltas and tips. */
@Composable
internal fun chatCardPositiveColor(): Color = if (isDarkCardTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/** Hairline colour (about 9 percent ink) for borders and row separators. */
@Composable
internal fun chatCardLine(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/** Fully rounded pill; PIXEL keeps its pixel corners. */
@Composable
internal fun chatCardPillShape(): Shape =
    if (LocalAppVisualStyle.current.isPixel) AppChromeDefaults.CompactShape else CircleShape

/** 1 dp hairline row separator. */
@Composable
internal fun CardHairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = chatCardLine())
}

/**
 * The one shared card frame: tonal surface + 1 dp hairline, 16 dp shape from the app shape provider (PIXEL keeps pixel
 * corners), optional header ([title] 15 sp bold, [subtitle] muted below, [headerTrailing] right of the title) and a body
 * [content] column with 12 dp gaps. [contentPadding] defaults to 14 dp; pass `PaddingValues(0.dp)` for edge-to-edge
 * bodies (hairline lists, maps) and pad the rows yourself. Width is capped at the 320 dp chat bubble.
 */
@Composable
internal fun CardShell(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val hasHeader = !title.isNullOrBlank() || !subtitle.isNullOrBlank()
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 320.dp),
        shape = AppChromeDefaults.InnerCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = AppChromeDefaults.pixelCardBorder() ?: BorderStroke(1.dp, chatCardLine())
    ) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasHeader) {
                Row(
                    Modifier.fillMaxWidth().then(
                        if (contentPadding.calculateTopPadding() == 0.dp) Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp) else Modifier
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!title.isNullOrBlank()) {
                            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    headerTrailing?.invoke()
                }
            }
            content()
        }
    }
}

/** Ink primary pill ("Send", "Send answers"): onSurface background, surface text; quiet disabled state. */
@Composable
internal fun CardPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = chatCardPillShape(),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.onSurface,
            contentColor = scheme.surface,
            disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        modifier = modifier.heightIn(min = 40.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pill chip: selected = tonal fill + 1.5 dp ink border (+ check when [showCheck]); used by segmented selects and answered summaries. */
@Composable
internal fun CardChip(label: String, selected: Boolean, enabled: Boolean = true, showCheck: Boolean = false, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) scheme.surfaceContainerHigh else Color.Transparent, label = "chipFill")
    val border by animateColorAsState(if (selected) scheme.onSurface else chatCardLine(), label = "chipBorder")
    Row(
        Modifier
            .heightIn(min = if (onClick != null) 48.dp else 32.dp)
            .background(fill, chatCardPillShape())
            .border(if (selected) 1.5.dp else 1.dp, border, chatCardPillShape())
            .clip(chatCardPillShape())
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showCheck) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = scheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 48 dp input (Frontier style): tonal fill, hairline border, 1.5 dp primary border on focus; optional trailing slot. */
@Composable
internal fun CardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textAlign: TextAlign = TextAlign.Start,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = AppChromeDefaults.CompactShape
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, color = scheme.onSurface, textAlign = textAlign)
    BasicTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled, readOnly = readOnly, singleLine = true,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(scheme.primary),
        interactionSource = interaction,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(scheme.surfaceContainerHighest, shape)
                    .border(if (focused) 1.5.dp else 1.dp, if (focused) scheme.primary else chatCardLine(), shape)
                    .padding(start = 12.dp, end = if (trailing != null) 0.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = TextStyle(fontSize = 14.5.sp, textAlign = textAlign), color = scheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                trailing?.invoke()
            }
        }
    )
}

/** First grapheme cluster of [icon] (one emoji), or null when blank. Model-supplied icons can be longer than one emoji. */
internal fun cardEmoji(icon: String?): String? {
    val s = icon?.trim().orEmpty()
    if (s.isEmpty()) return null
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    val end = it.next()
    return if (end > 0) s.substring(0, end) else s
}

/** Round tonal badge holding an emoji or a short text (number / initial). Monochrome: colour comes from the emoji. */
@Composable
internal fun CardEmojiBadge(text: String, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(1.dp, chatCardLine(), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
    }
}
