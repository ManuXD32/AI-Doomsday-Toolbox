package com.example.llamadroid.ui.ai.llama.chat

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.LlamaClientService
import com.example.llamadroid.service.LlamaClientService.GenerationState
import com.example.llamadroid.service.LlamaClientService.QueueReason

/** Which sentence the composer shows for a queued message. Pure so it can be unit tested. */
internal enum class ChatQueueMessageKind { SLOT_LABEL, SLOT, RAM_LABEL, RAM, ENGINE }

internal fun chatQueueMessageKind(queued: GenerationState.Queued): ChatQueueMessageKind {
    val hasLabel = !queued.resourceLabel.isNullOrBlank()
    return when (queued.reason) {
        QueueReason.SLOT -> if (hasLabel) ChatQueueMessageKind.SLOT_LABEL else ChatQueueMessageKind.SLOT
        QueueReason.RAM -> if (hasLabel) ChatQueueMessageKind.RAM_LABEL else ChatQueueMessageKind.RAM
        QueueReason.ENGINE -> ChatQueueMessageKind.ENGINE
    }
}

/** Compact "waiting in the queue" strip above the composer, with a Cancel action. */
@Composable
internal fun ChatQueueStatus(
    queued: GenerationState.Queued,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val label = queued.resourceLabel.orEmpty()
    val text = when (chatQueueMessageKind(queued)) {
        ChatQueueMessageKind.SLOT_LABEL -> stringResource(R.string.chat_queue_slot_label, label, queued.position)
        ChatQueueMessageKind.SLOT -> stringResource(R.string.chat_queue_slot, queued.position)
        ChatQueueMessageKind.RAM_LABEL -> stringResource(R.string.chat_queue_ram_label, label)
        ChatQueueMessageKind.RAM -> stringResource(R.string.chat_queue_ram)
        ChatQueueMessageKind.ENGINE -> stringResource(R.string.chat_queue_engine, queued.position)
    }
    val cancelDescription = stringResource(R.string.chat_queue_cancel_description)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = {
                val intent = Intent(context, LlamaClientService::class.java).apply {
                    action = LlamaClientService.ACTION_CANCEL_QUEUED
                    putExtra(LlamaClientService.EXTRA_CHAT_ID, queued.chatId)
                }
                context.startForegroundService(intent)
            },
            modifier = Modifier.semantics { contentDescription = cancelDescription }
        ) {
            Text(
                text = stringResource(R.string.chat_queue_cancel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
