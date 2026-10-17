package com.example.llamadroid.ui.ai.llama.delegation

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.delegation.NativeChatDelegation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Message the subagent" box of the viewer's Conversation tab. Works for any task state: the API
 * delivers it at the subagent's next step, answers a waiting question, or reopens a finished task.
 */
@Composable
internal fun DelegationMessageBox(chatId: Long) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(chatId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank() && !sending
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = !sending,
            maxLines = 4,
            placeholder = {
                Text(
                    stringResource(R.string.native_chat_delegation_viewer_message_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
        IconButton(
            enabled = canSend,
            onClick = {
                val message = text.trim()
                sending = true
                scope.launch {
                    val reply = try {
                        NativeChatDelegation.get(context).messageSubagent(
                            chatId = chatId,
                            message = message,
                            waitSeconds = 0,
                            attachmentRefs = emptyList(),
                            fromUser = true
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    sending = false
                    if (reply == null) {
                        Toast.makeText(context, R.string.native_chat_delegation_viewer_message_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        text = ""
                        val state = delegationStateLabelRes(reply.state)?.let { resources.getString(it) } ?: reply.state
                        val toast = if (reply.reopened) {
                            resources.getString(R.string.native_chat_delegation_viewer_message_reopened)
                        } else {
                            resources.getString(R.string.native_chat_delegation_viewer_message_sent, state)
                        }
                        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.native_chat_delegation_viewer_message_send)
            )
        }
    }
}
