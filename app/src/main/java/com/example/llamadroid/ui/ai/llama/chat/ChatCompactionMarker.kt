package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import java.util.Locale

/** "950", "12.4k", "1.2M". */
fun formatTokenCount(tokens: Int): String {
    val t = tokens.coerceAtLeast(0)
    return when {
        t < 1000 -> t.toString()
        t < 999_950 -> String.format(Locale.US, "%.1fk", t / 1000.0)
        else -> String.format(Locale.US, "%.1fM", t / 1_000_000.0)
    }
}

/** Thin grey divider with centered small text; not a bubble. */
@Composable
private fun GreyLineNotice(text: String, maxLines: Int, modifier: Modifier, onClick: (() -> Unit)?) {
    val tone = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = tone.copy(alpha = 0.35f))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f, fill = false)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = tone.copy(alpha = 0.35f))
    }
}

@Composable
fun ChatCompactionMarker(tokensSaved: Int, measured: Boolean, modifier: Modifier = Modifier) {
    val count = formatTokenCount(tokensSaved)
    GreyLineNotice(
        text = if (measured) stringResource(R.string.native_chat_attachments_compacted_exact, count)
        else stringResource(R.string.native_chat_attachments_compacted_approx, count),
        maxLines = 1,
        modifier = modifier,
        onClick = null
    )
}

@Composable
fun ChatNoticeLine(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    GreyLineNotice(text = text, maxLines = 2, modifier = modifier, onClick = onClick)
}
