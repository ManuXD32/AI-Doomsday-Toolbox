package com.example.llamadroid.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import com.example.llamadroid.R
import com.example.llamadroid.service.bubble.ChatBubbleOverlayService
import com.example.llamadroid.ui.overlay.rememberOverlayPermissionRequest

/**
 * Compact top bar used by the floating chat window: title, Open in app, Close bubble and
 * Minimize. Replaces both the normal and the Easy chat top bars while `floating` is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBubbleTopBar(
    title: String,
    onOpenInApp: () -> Unit,
    onCloseBubble: () -> Unit,
    onMinimize: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("native_chat_title")
            )
        },
        actions = { ChatBubbleTopBarActions(onOpenInApp, onCloseBubble, onMinimize) }
    )
}

@Composable
private fun RowScope.ChatBubbleTopBarActions(
    onOpenInApp: () -> Unit,
    onCloseBubble: () -> Unit,
    onMinimize: () -> Unit
) {
    IconButton(onClick = onOpenInApp) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.chat_bubble_open_in_app))
    }
    IconButton(onClick = onCloseBubble) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_bubble_close))
    }
    IconButton(onClick = onMinimize) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_bubble_minimize))
    }
}

/**
 * Returns the "Bubble chat" action for a chat screen. It asks for the overlay permission when
 * needed, starts the bubble service, then calls [onStarted] (leave the chat) and sends the app
 * to the background so the user returns to what they were doing.
 */
@Composable
fun rememberChatBubbleLauncher(chatId: Long, serverId: Long?, onStarted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnStarted = rememberUpdatedState(onStarted)
    return rememberOverlayPermissionRequest(R.string.chat_bubble_permission_body) {
        if (ChatBubbleOverlayService.start(context, chatId, serverId)) {
            currentOnStarted.value()
            context.findHostActivity()?.moveTaskToBack(true)
        } else {
            Toast.makeText(context, R.string.chat_bubble_start_failed, Toast.LENGTH_LONG).show()
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
