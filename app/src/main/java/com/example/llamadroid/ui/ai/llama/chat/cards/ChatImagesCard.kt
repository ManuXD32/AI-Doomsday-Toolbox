package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.ui.components.AppChromeDefaults

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatImagesCard(card: ChatCard.Images) {
    val items = card.items
    if (items.size == 1) {
        val item = items[0]
        Column(Modifier.fillMaxWidth().widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardPhoto(item.query, item.caption ?: item.query, Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(AppChromeDefaults.InnerCardShape))
            if (!item.caption.isNullOrBlank()) {
                Text(item.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items, key = { i, item -> "$i:${item.query}" }) { _, item ->
            Box(Modifier.width(220.dp).height(150.dp).clip(AppChromeDefaults.CompactShape)) {
                CardPhoto(item.query, item.caption ?: item.query, Modifier.matchParentSize())
                if (!item.caption.isNullOrBlank()) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                            .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp)
                    ) {
                        Text(item.caption, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
