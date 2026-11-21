package com.example.llamadroid.worlds.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.worlds.WorldPageBuilder
import com.example.llamadroid.worlds.WorldRepository
import com.example.llamadroid.worlds.WorldRuntimeStrings
import com.example.llamadroid.worlds.WorldSource
import com.example.llamadroid.worlds.WorldWebView
import com.example.llamadroid.worlds.WorldWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Process-wide holder: only one inline world may run at a time (WebViews are heavy). */
object WorldActiveSession {
    val activeMessageId = MutableStateFlow<Long?>(null)
}

/** The three.js source for a finished message, or null. Streaming messages never show a card. */
internal fun worldSourceFor(content: String, streaming: Boolean): WorldSource? =
    if (streaming || content.isBlank()) null else WorldPageBuilder.detect(content)

/** Pure visibility rule for the chat card. */
internal fun shouldShowWorldCard(content: String, streaming: Boolean): Boolean =
    worldSourceFor(content, streaming) != null

private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Title from the first `<title>`, else the first non-fence line, else [fallback]. Max 60 chars. */
internal fun worldTitleFrom(source: String, fallback: String): String {
    val fromTag = TITLE_TAG.find(source)?.groupValues?.get(1)?.trim().orEmpty()
    val candidate = fromTag.ifBlank {
        source.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("<") }
            .orEmpty()
    }
    return candidate.replace(Regex("\\s+"), " ").take(60).ifBlank { fallback }
}

/** Inline card under an assistant reply that contains a three.js world. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldChatCard(
    content: String,
    messageId: Long,
    chatId: Long,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    /** Workspace file this card shows (edited-file cards); null saves the reply's world to `worlds/`. */
    workspacePath: String? = null,
    /** Process-wide key of the inline player; edited-file cards use their own so they do not collide. */
    sessionKey: Long = messageId
) {
    val source = remember(content, streaming) { worldSourceFor(content, streaming) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by WorldActiveSession.activeMessageId.collectAsState()
    val playing = active == sessionKey
    var savedId by remember(content) { mutableStateOf<String?>(null) }
    var showFullscreen by remember { mutableStateOf(false) }
    val defaultTitle = stringResource(R.string.worlds_ui_default_title)
    val savedText = stringResource(R.string.worlds_ui_saved)
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }

    var savedPath by remember(messageId, workspacePath) { mutableStateOf(workspacePath) }
    if (workspacePath == null) {
        LaunchedEffect(messageId, content, chatId) {
            val html = source.html ?: return@LaunchedEffect
            savedPath = withContext(Dispatchers.IO) {
                WorldWorkspace.saveOnce(context, chatId, messageId, worldTitleFrom(content, defaultTitle), html)
            }
        }
    }

    DisposableEffect(sessionKey) {
        onDispose {
            if (WorldActiveSession.activeMessageId.value == sessionKey) {
                WorldActiveSession.activeMessageId.value = null
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (source.isThree) Icons.Default.ViewInAr else Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(if (source.isThree) R.string.worlds_ui_card_title else R.string.worlds_ui_card_title_html),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (workspacePath != null) {
                Text(
                    text = stringResource(R.string.worlds_ui_latest_version, workspacePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (savedPath != null) {
                Text(
                    text = stringResource(R.string.worlds_ui_workspace_path, savedPath.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    focusManager.clearFocus(force = true)
                    WorldActiveSession.activeMessageId.value = if (playing) null else sessionKey
                }) {
                    Icon(
                        if (playing) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(if (playing) R.string.worlds_ui_stop else R.string.worlds_ui_play),
                        modifier = Modifier.padding(start = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = {
                    focusManager.clearFocus(force = true)
                    // The inline view and the fullscreen view must not both hold a live WebView.
                    if (playing) WorldActiveSession.activeMessageId.value = null
                    showFullscreen = true
                }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.worlds_ui_fullscreen),
                        modifier = Modifier.padding(start = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (savedId == null) {
                    TextButton(onClick = {
                        scope.launch {
                            val meta = runCatching {
                                WorldRepository(context).save(worldTitleFrom(content, defaultTitle), content, chatId, messageId)
                            }.getOrNull()
                            if (meta != null) {
                                savedId = meta.id
                                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.worlds_ui_save),
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    TextButton(onClick = {
                        focusManager.clearFocus(force = true)
                        if (playing) WorldActiveSession.activeMessageId.value = null
                        showFullscreen = true
                    }) {
                        Text(stringResource(R.string.worlds_ui_open), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (playing) {
                val page = remember(content) { WorldRuntimeStrings.buildPage(context, source) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight * 0.55f)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    WorldWebView(pageHtml = page, modifier = Modifier.fillMaxWidth().height(screenHeight * 0.55f))
                }
            }
        }
    }

    if (showFullscreen) {
        WorldSourceViewerDialog(
            source = content,
            savedId = savedId,
            chatId = chatId,
            messageId = messageId,
            onSaved = { savedId = it },
            onDismiss = { showFullscreen = false }
        )
    }
}
