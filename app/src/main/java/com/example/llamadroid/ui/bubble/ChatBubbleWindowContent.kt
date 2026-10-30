package com.example.llamadroid.ui.bubble

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.MainActivity
import com.example.llamadroid.ui.ai.llama.chat.LlamaChatScreen
import com.example.llamadroid.ui.navigation.Screen

/**
 * Body of [ChatBubbleActivity]: the regular native chat screen in floating mode (no NavController).
 * [onClose] minimizes the window back to the bubble; [onCloseBubble] also removes the bubble.
 */
@Composable
fun ChatBubbleWindowContent(
    chatId: Long,
    serverId: Long?,
    onClose: () -> Unit,
    onCloseBubble: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        LlamaChatScreen(
            navController = null,
            chatId = chatId,
            initialServerId = serverId ?: -1L,
            floating = true,
            onFloatingClose = onClose,
            onCloseBubble = onCloseBubble,
            onOpenInApp = {
                openChatInApp(context, chatId, serverId)
                onClose()
            }
        )
    }
}

/** Opens the chat in the main app through the validated `EXTRA_OPEN_ROUTE` deep link. */
private fun openChatInApp(context: Context, chatId: Long, serverId: Long?) {
    val route = Screen.LlamaChat.createRoute(chatId, serverId ?: -1L)
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    )
}
