package com.example.llamadroid.ui.bubble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.llamadroid.service.bubble.ChatBubbleOverlayService
import com.example.llamadroid.service.bubble.ChatBubbleState
import com.example.llamadroid.ui.theme.AppThemeFromSettings

/**
 * Floating chat window opened from the chat bubble: a rounded panel (about 0.92 x 0.8 of the
 * screen) over a translucent window. Tapping outside the panel closes it. While resumed it
 * publishes [ChatBubbleState.windowOpen] so the bubble hides and its red dot clears.
 */
class ChatBubbleActivity : ComponentActivity() {
    private val chatIdState = mutableLongStateOf(-1L)
    private val serverIdState = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readTarget(intent)
        if (chatIdState.longValue <= 0L) {
            finish()
            return
        }
        setContent {
            val chatId = chatIdState.longValue
            val serverId = serverIdState.value
            AppThemeFromSettings {
                // Tapping the dimmed area outside the panel closes the window.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() }
                        )
                        .imePadding(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.8f)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            ),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        key(chatId) {
                            ChatBubbleWindowContent(
                                chatId = chatId,
                                serverId = serverId,
                                onClose = { finish() },
                                onCloseBubble = {
                                    ChatBubbleOverlayService.stop(applicationContext)
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // singleTask: a retargeted bubble re-delivers the intent here instead of recreating the activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
        if (chatIdState.longValue <= 0L) finish()
    }

    private fun readTarget(source: Intent) {
        chatIdState.longValue = source.getLongExtra(EXTRA_CHAT_ID, -1L)
        serverIdState.value = source.getLongExtra(EXTRA_SERVER_ID, -1L).takeIf { it >= 0L }
    }

    override fun onResume() {
        super.onResume()
        ChatBubbleState.setWindowOpen(true)
    }

    override fun onPause() {
        ChatBubbleState.setWindowOpen(false)
        super.onPause()
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_SERVER_ID = "server_id"

        fun intent(context: Context, chatId: Long, serverId: Long?): Intent =
            Intent(context, ChatBubbleActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_SERVER_ID, serverId ?: -1L)
    }
}
