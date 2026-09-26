package com.example.llamadroid.ui.agent.harness

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

internal val LocalHarnessInlineAction = staticCompositionLocalOf<(HarnessInlineTarget) -> Unit> { {} }

/** Shares native Text sizing/selection and adds bounded link annotations to transcript previews. */
@Composable
internal fun HarnessInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontFamily: FontFamily? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    userReferences: Boolean = false,
    skillNames: Set<String> = emptySet(),
) {
    val open = LocalHarnessInlineAction.current
    val linkColor = MaterialTheme.colorScheme.primary
    val value = text.take(24_000)
    val links = remember(value, userReferences, skillNames) { harnessInlineLinks(value, userReferences, skillNames) }
    val annotated = remember(value, links, open, linkColor) {
        buildAnnotatedString {
            append(value)
            links.forEachIndexed { index, link ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "harness-link-" + index,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { open(link.target) },
                    ),
                    link.start,
                    link.end,
                )
            }
        }
    }
    Text(annotated, modifier, color = color, fontFamily = fontFamily, style = style, maxLines = maxLines, overflow = overflow)
}
