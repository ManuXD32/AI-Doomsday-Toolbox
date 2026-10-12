package com.example.llamadroid.ui.theme.sprites

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.theme.LocalAppVisualStyle

/**
 * Agent row badge: the role emoji in Soft Studio, the matching robot sprite in Pixel. Custom
 * agents (any emoji that is not a built-in role emoji) use the base robot sprite.
 */
@Composable
fun AgentRoleEmoji(
    emoji: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    spriteSize: Dp = 32.dp
) {
    if (LocalAppVisualStyle.current.isPixel) {
        PixelSpriteImage(
            key = SpriteMappings.forAgentEmoji(emoji),
            modifier = modifier.size(spriteSize)
        )
    } else {
        Text(emoji, fontSize = fontSize, modifier = modifier)
    }
}
