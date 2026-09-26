package com.example.llamadroid.ui.agent.harness

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.example.llamadroid.ui.ai.llama.MarkdownText

/**
 * Harness-scoped adapter around the canonical Markdown renderer.
 *
 * Link resolution remains owned by the selected session through
 * [LocalHarnessInlineAction]. The renderer itself is shared with native chat,
 * so headings, lists, tables, and code blocks keep one visual contract.
 */
@Composable
internal fun HarnessMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color,
    fontFamily: FontFamily? = null,
    userReferences: Boolean = false,
    skillNames: Set<String> = emptySet(),
) {
    // MarkdownText owns its typography for blocks. Keep the optional font
    // parameter for call-site compatibility; internal payloads use the
    // existing monospace HarnessInlineText path instead.
    if (fontFamily != null) {
        HarnessInlineText(
            text = text,
            modifier = modifier,
            color = textColor,
            fontFamily = fontFamily,
            userReferences = userReferences,
            skillNames = skillNames,
        )
        return
    }
    val open = LocalHarnessInlineAction.current
    MarkdownText(
        text = text,
        textColor = textColor,
        modifier = modifier,
        onLinkClick = { destination ->
            parseHarnessInlineDestination(destination)?.let {
                open(it)
                true
            } ?: false
        },
    )
}

