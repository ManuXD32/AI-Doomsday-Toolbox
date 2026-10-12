package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * Banner above the composer, shown only when the chat's server needs attention: stopped (Start),
 * starting (live progress), error (Retry / View log), no server or removed server (Choose server).
 */
@Composable
internal fun ChatServerBanner(
    kind: ChatServerBannerKind,
    progressText: String?,
    errorText: String?,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onViewLog: () -> Unit,
    onChooseServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (kind == ChatServerBannerKind.NONE) return
    val isError = kind == ChatServerBannerKind.ERROR
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().testTag("native_chat_server_banner")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (kind == ChatServerBannerKind.STARTING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = when (kind) {
                        ChatServerBannerKind.STOPPED -> stringResource(R.string.native_chat_banner_stopped)
                        ChatServerBannerKind.STARTING ->
                            progressText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.native_chat_banner_starting)
                        ChatServerBannerKind.ERROR ->
                            errorText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.native_chat_banner_error)
                        ChatServerBannerKind.SERVER_REMOVED -> stringResource(R.string.native_chat_banner_removed)
                        ChatServerBannerKind.NO_SERVER -> stringResource(R.string.native_chat_banner_no_server)
                        ChatServerBannerKind.NONE -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when (kind) {
                    ChatServerBannerKind.STOPPED -> BannerButton(R.string.native_chat_banner_start, onStart)
                    ChatServerBannerKind.NO_SERVER,
                    ChatServerBannerKind.SERVER_REMOVED -> BannerButton(R.string.native_chat_banner_choose, onChooseServer)
                    else -> Unit
                }
            }
            if (isError) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BannerButton(R.string.native_chat_banner_retry, onRetry)
                    BannerButton(R.string.native_chat_banner_view_log, onViewLog)
                }
            }
        }
    }
}

@Composable
private fun BannerButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
