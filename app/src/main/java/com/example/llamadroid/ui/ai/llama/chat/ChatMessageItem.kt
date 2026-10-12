package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Square
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.data.model.extractEmbeddedAudioTranscript
import com.example.llamadroid.data.model.extractEmbeddedDocumentText
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.stripEmbeddedAudioTranscript
import com.example.llamadroid.data.model.stripEmbeddedDocumentText
import java.io.File
import com.example.llamadroid.ui.ai.llama.MarkdownText

@Composable
fun LlamaDeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_delete_message_confirm_title)) },
        text = { Text(stringResource(R.string.llama_delete_message_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_clear_chat_confirm_title)) },
        text = { Text(stringResource(R.string.llama_clear_chat_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.llama_clear_chat_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaRetryMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llama_retry_message_confirm_title)) },
        text = { Text(stringResource(R.string.llama_retry_message_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun LlamaMessageItem(
    message: LlamaMessageEntity,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    onRetryTranscription: () -> Unit,
    onDiscardFailedMessage: () -> Unit,
    onDelete: () -> Unit,
    autoPlayAssistantAudio: Boolean = false,
    onAssistantAudioAutoPlayed: (String) -> Unit = {},
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val clipboardLabelMessageText = stringResource(R.string.clipboard_label_message)
    val termuxCopyToastText = stringResource(R.string.termux_copy_toast)
    val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val imageFile = remember(message.imagePath) { message.imagePath?.let(::File)?.takeIf { it.exists() } }
    val audioFile = remember(message.audioPath) { message.audioPath?.let(::File)?.takeIf { it.exists() } }
    val videoFile = remember(message.videoPath) { message.videoPath?.let(::File)?.takeIf { it.exists() } }
    var isEditing by remember(message.id) { mutableStateOf(false) }
    var editContent by remember(message.id) { mutableStateOf(message.content) }
    var showImagePreview by remember(message.imagePath) { mutableStateOf(false) }
    var showVideoPreview by remember(message.videoPath) { mutableStateOf(false) }
    var audioPlayer by remember(message.audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isAudioPlaying by remember(message.audioPath) { mutableStateOf(false) }
    val embeddedTranscript = remember(message.content) { extractEmbeddedAudioTranscript(message.content) }
    val embeddedDocument = remember(message.content) { extractEmbeddedDocumentText(message.content) }
    val displayContent = remember(message.content) {
        stripEmbeddedDocumentText(stripEmbeddedAudioTranscript(message.content)).trim()
    }
    val transcriptionFailed = isUser && audioFile != null && message.isError && embeddedTranscript.isNullOrBlank()
    val videoAttachmentMissing = !message.videoPath.isNullOrBlank() && videoFile == null

    fun copyMessageToClipboard() {
        val clip = android.content.ClipData.newPlainText(clipboardLabelMessageText, message.content)
        clipboardManager.setPrimaryClip(clip)
        Toast.makeText(context, termuxCopyToastText, Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(message.audioPath) {
        onDispose {
            runCatching { audioPlayer?.release() }
            audioPlayer = null
            isAudioPlaying = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        TextButton(onClick = { isEditing = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = {
                                onEdit(editContent)
                                isEditing = false
                            }
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (imageFile != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showImagePreview = true }
                                ) {
                                    AsyncImage(
                                        model = imageFile,
                                        contentDescription = stringResource(R.string.llama_image_attached),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 140.dp, max = 260.dp)
                                    )
                                }
                            }

                            if (audioFile != null) {
                                AudioPlaybackRow(
                                    audioFile = audioFile,
                                    onPlaybackChanged = { playing, player ->
                                        isAudioPlaying = playing
                                        audioPlayer = player
                                    },
                                    autoPlay = autoPlayAssistantAudio,
                                    onAutoPlayConsumed = { onAssistantAudioAutoPlayed(audioFile.absolutePath) }
                                )
                            }

                            if (videoFile != null) {
                                VideoAttachmentRow(
                                    videoFile = videoFile,
                                    onPreview = { showVideoPreview = true }
                                )
                            } else if (videoAttachmentMissing) {
                                VideoAttachmentMissingContent()
                            }

                            if (isUser && embeddedDocument != null) {
                                EmbeddedDocumentAttachment(document = embeddedDocument)
                            }

                            val thinkStartRegex = Regex("<[^>]*?(?:think|thought|Thought|Think)[^>]*?>")
                            val renderedContent = if (isUser) displayContent else message.content
                            val hasThinkingTags = thinkStartRegex.containsMatchIn(renderedContent)

                            if (renderedContent.isNotBlank()) {
                                if (!isUser && (!message.thinking.isNullOrBlank() || hasThinkingTags)) {
                                    if (!message.thinking.isNullOrBlank()) {
                                        ThinkingMessageContent(
                                            message.thinking,
                                            renderedContent,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                    } else {
                                        val combinedRegex = Regex("(<[^>]*?(?:think|thought|Thought|Think)[^>]*?>)(.*?)(<[^>]*?/(?:think|thought|Thought|Think)[^>]*?>|$)", setOf(RegexOption.DOT_MATCHES_ALL))
                                        val match = combinedRegex.find(renderedContent)
                                        val thinking = match?.groupValues?.get(2)?.trim() ?: ""
                                        val content = renderedContent.replace(combinedRegex, "").trim()
                                        ThinkingMessageContent(
                                            thinking,
                                            content,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                    }
                                } else {
                                    MarkdownText(
                                        text = renderedContent,
                                        textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        onLinkClick = onKnowledgeLinkClick
                                    )
                                }
                            }

                            if (isUser && !embeddedTranscript.isNullOrBlank()) {
                                AudioTranscriptContent(transcript = embeddedTranscript)
                            } else if (transcriptionFailed) {
                                AudioTranscriptionErrorContent(
                                    onRetry = onRetryTranscription,
                                    onDiscard = onDiscardFailedMessage
                                )
                            }
                        }
                    }
                }
            }
        }

        // Message meta + actions
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.role.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (!isUser && message.completionTokens > 0) {
                    Text(
                        text = stringResource(
                            R.string.llama_message_stats,
                            "%.1fs".format(message.generationTimeMs / 1000.0),
                            message.completionTokens,
                            message.tps,
                            message.promptTokens
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                }
            }

            var actionsMenuOpen by remember(message.id) { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Always visible: Copy, and Regenerate (assistant) / Retry (user).
                if (!isUser) {
                    TextButton(
                        onClick = { copyMessageToClipboard() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_copy),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(LLAMA_MESSAGE_ACTION_TOUCH_TARGET_DP.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.llama_regenerate),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    if (!transcriptionFailed) {
                        TextButton(
                            onClick = onRetry,
                            enabled = retryEnabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_retry),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = { copyMessageToClipboard() }, modifier = Modifier.size(LLAMA_MESSAGE_ACTION_TOUCH_TARGET_DP.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                // Everything else lives in the overflow menu.
                Box {
                    IconButton(
                        onClick = { actionsMenuOpen = true },
                        modifier = Modifier.size(LLAMA_MESSAGE_ACTION_TOUCH_TARGET_DP.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = stringResource(R.string.native_chat_message_more),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = actionsMenuOpen,
                        onDismissRequest = { actionsMenuOpen = false }
                    ) {
                        if (imageFile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_image_save_to_device)) },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    actionsMenuOpen = false
                                    saveLlamaChatImageToGallery(context, imageFile)
                                }
                            )
                        }
                        if (isUser) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    actionsMenuOpen = false
                                    isEditing = true
                                    editContent = message.content
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                actionsMenuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImagePreview && imageFile != null) {
        LlamaImagePreviewDialog(
            imageFile = imageFile,
            onDismiss = { showImagePreview = false }
        )
    }
    if (showVideoPreview && videoFile != null) {
        LlamaVideoPreviewDialog(
            videoFile = videoFile,
            onDismiss = { showVideoPreview = false }
        )
    }
}

@Composable
internal fun AudioTranscriptContent(transcript: String) {
    var isExpanded by remember(transcript) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.llama_audio_transcription),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
internal fun AudioTranscriptionErrorContent(
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.llama_audio_transcription),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.llama_transcription_error_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.action_yes))
                }
                TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.action_no))
                }
            }
        }
    }
}



@Composable
fun ThinkingMessageContent(
    thinkingContent: String,
    finalResponse: String,
    forceExpand: Boolean = false,
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    var isExpanded by remember { mutableStateOf(forceExpand) }

    // Auto-update expansion state when forceExpand changes (e.g. at start of generation)
    LaunchedEffect(forceExpand) {
        if (forceExpand) isExpanded = true
    }

    // Auto-expand if the block is actively generating and we haven't seen the final response yet
    val isThinkingFinished = finalResponse.isNotBlank()
    LaunchedEffect(isThinkingFinished) {
        if (!isThinkingFinished) {
            isExpanded = true
        }
    }

    Column {
        if (thinkingContent.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Square,
                            contentDescription = stringResource(R.string.action_thinking),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.llama_thinking_process),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    if (isExpanded && thinkingContent.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        MarkdownText(
                            text = thinkingContent,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            onLinkClick = onKnowledgeLinkClick
                        )
                    }
                }
            }
        }

        if (finalResponse.isNotEmpty()) {
            MarkdownText(
                text = finalResponse,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onLinkClick = onKnowledgeLinkClick
            )
        }
    }
}
