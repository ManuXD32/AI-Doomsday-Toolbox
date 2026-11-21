package com.example.llamadroid.ui.ai.llama.chat.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.chat.cards.ChatCard
import com.example.llamadroid.chat.cards.media.ChatCardMedia
import com.example.llamadroid.ui.components.AppChromeDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves a photo search phrase to an https URL off the main thread; null while loading or when nothing was found. */
@Composable
internal fun rememberCardPhotoUrl(query: String?): String? {
    val context = LocalContext.current
    val url by produceState<String?>(initialValue = null, query) {
        value = if (query.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { ChatCardMedia.imageResolver(context).resolve(query) }.getOrNull()
        }
    }
    return url
}

/** Photo for [query] over a tonal placeholder (first letter or [emoji]); never an empty box. Caller sizes it. */
@Composable
internal fun CardPhoto(query: String?, label: String, modifier: Modifier = Modifier, emoji: String? = null) {
    val url = rememberCardPhotoUrl(query)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Text(
            emoji ?: label.trim().take(1).uppercase(),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.chat_card_photo_of, label),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatPlacesCard(card: ChatCard.Places) {
    val listState = rememberLazyListState()
    CardShell(title = card.title, contentPadding = PaddingValues(vertical = 0.dp)) {
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = if (card.title.isNullOrBlank()) 14.dp else 0.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(card.items, key = { i, item -> "$i:${item.name}" }) { _, item -> PlaceTile(item) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceTile(item: ChatCard.Places.Item) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val url = item.url
    val shape = AppChromeDefaults.CompactShape
    val subtitle = listOfNotNull(
        item.subtitle?.takeIf { it.isNotBlank() },
        item.price?.takeIf { it.isNotBlank() },
        item.rating?.let { "★" + java.text.DecimalFormat("0.#").format(it) }
    ).joinToString(" · ")
    Surface(
        modifier = Modifier.width(200.dp),
        shape = shape,
        color = scheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, chatCardLine())
    ) {
        Column(
            Modifier.then(if (!url.isNullOrBlank()) Modifier.clickable { runCatching { uriHandler.openUri(url) } } else Modifier)
        ) {
            CardPhoto(item.image ?: item.name, item.name, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!item.description.isNullOrBlank()) {
                    Text(item.description, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (item.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.tags.take(4).forEach { tag ->
                            Text(
                                tag, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
