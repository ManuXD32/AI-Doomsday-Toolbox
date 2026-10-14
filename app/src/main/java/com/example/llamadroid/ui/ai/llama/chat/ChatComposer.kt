package com.example.llamadroid.ui.ai.llama.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Square
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import com.example.llamadroid.service.LlamaCallUiState
import com.example.llamadroid.service.LlamaClientService
import java.util.Locale

@Composable
internal fun RecordingStrip(
    seconds: Int,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Square,
                contentDescription = stringResource(R.string.llama_recording),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.action_stop))
            }
        }
    }
}

@Composable
internal fun LlamaCallOverlay(
    state: LlamaCallUiState,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(20.dp)
            .widthIn(max = 320.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(112.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_btn_speak_now),
                        contentDescription = stringResource(R.string.llama_call_mode),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.llama_call_mode),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = state.status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { state.inputLevel.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.llama_call_elapsed, state.elapsedSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.llama_call_hang_up))
            }
        }
    }
}


/**
 * The input area: pending attachments, token estimate, attach menu, text field and Send/Stop.
 * State and side effects (pickers, recording, sending) stay with the screen and arrive as lambdas.
 */
@Composable
internal fun ChatComposer(
    inputMessage: String,
    onInputChange: (String) -> Unit,
    isRecording: Boolean,
    recordingSeconds: Int,
    onStopRecording: () -> Unit,
    attachedImagePath: String?,
    attachedAudioPath: String?,
    attachedVideoPath: String?,
    onPreviewImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onRemoveAudio: () -> Unit,
    onPreviewVideo: () -> Unit,
    onRemoveVideo: () -> Unit,
    isChatGenerating: Boolean,
    queuedState: LlamaClientService.GenerationState.Queued?,
    chatId: Long,
    isCallActiveForChat: Boolean,
    isExtractingDocument: Boolean,
    supportsVision: Boolean,
    supportsVideo: Boolean,
    supportsAudioInput: Boolean,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onStartRecording: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    showAttachFile: Boolean = false,
    onPickFile: () -> Unit = {},
    pendingFiles: List<LlamaMessageAttachmentEntity> = emptyList(),
    onRemovePendingFile: (LlamaMessageAttachmentEntity) -> Unit = {}
) {
    val context = LocalContext.current
    var showAttachmentMenu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, top = 6.dp, end = 2.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (queuedState != null) {
                ChatQueueStatus(queued = queuedState)
            }

            if (isRecording) {
                RecordingStrip(
                    seconds = recordingSeconds,
                    onStop = onStopRecording
                )
            }

            if (attachedImagePath != null) {
                PendingImageAttachment(
                    imagePath = attachedImagePath,
                    onPreview = onPreviewImage,
                    onRemove = onRemoveImage
                )
            }

            if (attachedAudioPath != null) {
                PendingAudioAttachment(
                    audioPath = attachedAudioPath,
                    onRemove = onRemoveAudio
                )
            }

            if (attachedVideoPath != null) {
                PendingVideoAttachment(
                    videoPath = attachedVideoPath,
                    onPreview = onPreviewVideo,
                    onRemove = onRemoveVideo
                )
            }

            if (pendingFiles.isNotEmpty()) {
                PendingAttachmentRow(attachments = pendingFiles, onRemove = onRemovePendingFile)
            }

            val approxTokens = estimateNativeChatTextTokens(inputMessage)
            if (approxTokens > 0) {
                Text(
                    text = stringResource(R.string.llama_token_estimate, approxTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(modifier = Modifier.padding(end = 4.dp)) {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = !isChatGenerating && queuedState == null && !isCallActiveForChat
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.llama_attachment_menu),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        if (supportsVision) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_attach_image)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    onPickImage()
                                }
                            )
                        }
                        if (supportsVideo) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_attach_video)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Movie,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    onPickVideo()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.llama_attach_document)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AttachFile,
                                    contentDescription = null
                                )
                            },
                            enabled = !isExtractingDocument,
                            onClick = {
                                showAttachmentMenu = false
                                onPickDocument()
                            }
                        )
                        if (showAttachFile) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.native_chat_compaction_attach_file),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AttachFile, contentDescription = null)
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    onPickFile()
                                }
                            )
                        }
                        if (supportsAudioInput && !isRecording && !isCallActiveForChat) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_record_audio)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(android.R.drawable.ic_btn_speak_now),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showAttachmentMenu = false
                                    onStartRecording()
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = inputMessage,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isChatGenerating && queuedState == null && !isCallActiveForChat
                )

                if (isChatGenerating) {
                    IconButton(
                        onClick = {
                            val intent = Intent(context, LlamaClientService::class.java).apply {
                                action = LlamaClientService.ACTION_STOP
                                putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                            }
                            context.startForegroundService(intent)
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_media_pause),
                            contentDescription = stringResource(R.string.chat_stop_generating),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else {
                    val canSend = inputMessage.isNotBlank() ||
                        attachedImagePath != null ||
                        attachedAudioPath != null ||
                        attachedVideoPath != null ||
                        pendingFiles.isNotEmpty()
                    IconButton(
                        onClick = { if (canSend) onSend() },
                        enabled = canSend && queuedState == null && !isCallActiveForChat,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

        }
    }
}
